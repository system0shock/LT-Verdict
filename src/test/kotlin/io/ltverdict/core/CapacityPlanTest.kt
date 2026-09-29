package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class CapacityPlanTest {
    @Test
    fun `valid RPS concurrency and users plans retain source and normalize semantic guards`() {
        val rps = valid(planJson())
        val concurrent = valid(planJson(axis = "concurrency", seriesId = "active"))
        val users = valid(planJson(axis = "users", seriesId = "users"))
        val reorderedGuards = valid(planJson(guards = "[\"generator-b\",\"generator-a\"]"))
        val exposed = rps.rawBytes()
        exposed[0] = '!'.code.toByte()

        assertEquals(CapacityLoadAxis.RPS, rps.plan.loadAxis)
        assertEquals("active", concurrent.plan.achievedSeriesId)
        assertEquals("users", users.plan.achievedSeriesId)
        assertEquals(listOf("generator-a", "generator-b"), rps.plan.generatorGuardRuleIds)
        assertEquals(rps.semanticSha256, reorderedGuards.semanticSha256)
        assertArrayEquals(planJson().encodeToByteArray(), rps.rawBytes())
    }

    @Test
    fun `strict parser rejects malformed closed and bounded input`() {
        val deeplyNested = "[".repeat(13) + "]".repeat(13)
        listOf(
            planJson().replace("\"schema_version\"", "\"schema_version\":\"capacity-plan.v1\",\"schema_version\""),
            planJson(extra = ",\"ignored\":true"),
            planJson().replace("\"target\":100", "\"target\":0"),
            planJson().replace("\"target_tolerance_ratio\":0.1", "\"target_tolerance_ratio\":1"),
            planJson(axis = "concurrency"),
            deeplyNested,
        ).forEach { json ->
            assertInstanceOf(
                CapacityPlanValidation.Invalid::class.java,
                validateCapacityPlan(ByteArrayInputStream(json.encodeToByteArray())),
            )
        }
        assertInstanceOf(
            CapacityPlanValidation.Invalid::class.java,
            validateCapacityPlan(ByteArrayInputStream(ByteArray(MAX_CAPACITY_PLAN_BYTES + 1) { ' '.code.toByte() })),
        )
        assertInstanceOf(
            CapacityPlanValidation.Invalid::class.java,
            validateCapacityPlan(ByteArrayInputStream(planJson().replace("\"target\":100", "\"target\":1e65").encodeToByteArray())),
        )
        assertInstanceOf(
            CapacityPlanValidation.Invalid::class.java,
            validateCapacityPlan(ByteArrayInputStream(planJson().encodeToByteArray() + byteArrayOf(0x80.toByte()))),
        )
        val stages = (1..65).joinToString(",") { stageJson("s$it", 1, it * 10000L, (it + 1) * 10000L, "steady") }
        assertInstanceOf(
            CapacityPlanValidation.Invalid::class.java,
            validateCapacityPlan(ByteArrayInputStream(planJson(stages = "[$stages]").encodeToByteArray())),
        )
    }

    @Test
    fun `binding rejects stale hashes invalid stage geometry incompatible series and guards`() {
        val resources = resource()
        val plan = valid(planJson(snapshotHash = resources.semanticSha256, loadHash = LOAD_HASH, axis = "concurrency", seriesId = "active"))
        assertEquals(emptyList<PolicyValidationError>(), validateCapacityBinding(plan, LOAD_HASH, resources))

        val malformed =
            valid(
                planJson(
                    snapshotHash = "b".repeat(64),
                    loadHash = "c".repeat(64),
                    axis = "concurrency",
                    seriesId = "missing",
                    guards = "[\"missing\",\"sla-rule\",\"system-rule\"]",
                    stages = "[${stageJson("short", 100, 0, 10000, "steady")},${stageJson("outside", 200, 300000, 600000, "missing")}]",
                ),
            )
        val codes = validateCapacityBinding(malformed, LOAD_HASH, resources).map(PolicyValidationError::code)

        assertEquals(
            listOf(
                "CAPACITY_LOAD_HASH_MISMATCH",
                "CAPACITY_SNAPSHOT_MISMATCH",
                "CAPACITY_SERIES_NOT_FOUND",
                "CAPACITY_GUARD_NOT_FOUND",
                "CAPACITY_GUARD_NOT_DIAGNOSTIC",
                "CAPACITY_GUARD_NOT_GENERATOR",
                "CAPACITY_STAGE_OUTSIDE_WINDOW",
                "CAPACITY_WINDOW_NOT_FOUND",
            ),
            codes,
        )
    }

    @Test
    fun `examples are accepted and semantic stage order is significant`() {
        val rps = valid(Files.readAllBytes(Path.of("docs/contracts/capacity/v1/examples/valid/rps.json")))
        val concurrency = valid(Files.readAllBytes(Path.of("docs/contracts/capacity/v1/examples/valid/concurrency.json")))
        val invalid = validateCapacityPlan(Files.newInputStream(Path.of("docs/contracts/capacity/v1/examples/invalid/unknown-field.json")))

        assertEquals(CapacityLoadAxis.RPS, rps.plan.loadAxis)
        assertEquals(CapacityLoadAxis.CONCURRENCY, concurrency.plan.loadAxis)
        assertInstanceOf(CapacityPlanValidation.Invalid::class.java, invalid)
        assertNotEquals(
            valid(planJson()).semanticSha256,
            valid(
                planJson(
                    stages = "[${stageJson("two", 200, 300000, 600000, "next")},${stageJson("one", 100, 0, 300000, "steady")}]",
                ),
            ).semanticSha256,
        )
    }

    @Test
    fun `binding accepts offset snapshots but rejects unaligned evaluation windows`() {
        val offset = bindingResources(start = 5_000, windowFrom = 10_000, windowTo = 310_000)
        val plan = validPlanFor(offset, "[${stageJson("s", 1, 5_000, 315_000, "steady")}]", guards = "[]")
        assertEquals(emptyList<PolicyValidationError>(), validateCapacityBinding(plan, LOAD_HASH, offset))

        val unaligned = bindingResources(windowFrom = 5_000, windowTo = 305_000)
        val unalignedPlan = validPlanFor(unaligned, "[${stageJson("s", 1, 0, 310_000, "steady")}]", guards = "[]")
        val error = validateCapacityBinding(unalignedPlan, LOAD_HASH, unaligned).single()
        assertEquals("CAPACITY_WINDOW_NOT_ON_10S_GRID", error.code)
        assertEquals("/stages/0/evaluation_window_id", error.jsonPointer)
    }

    @Test
    fun `binding isolates telemetry unit aggregation and negative errors`() {
        listOf(
            "ratio" to ResourceAggregation.INTERVAL_MEAN to BigDecimal.ONE,
            "count" to ResourceAggregation.INTERVAL_RATE to BigDecimal.ONE,
            "count" to ResourceAggregation.INTERVAL_MEAN to BigDecimal.ONE.negate(),
        ).forEachIndexed { index, (pair, value) ->
            val (unit, aggregation) = pair
            val resources = bindingResources(unit = unit, aggregation = aggregation, value = value)
            val plan =
                validPlanFor(
                    resources,
                    "[${stageJson("s", 1, 0, 300000, "steady")}]",
                    axis = "concurrency",
                    seriesId = "active",
                    guards = "[]",
                )
            val expected = if (index == 2) "CAPACITY_SERIES_NEGATIVE" else "CAPACITY_SERIES_INCOMPATIBLE"
            assertEquals(expected, validateCapacityBinding(plan, LOAD_HASH, resources).single().code)
        }
    }

    @Test
    fun `raw guard order drives binding pointers while semantic hash ignores order`() {
        val resources = bindingResources()
        val stages = "[${stageJson("s", 1, 0, 300000, "steady")}]"
        val ordered = validPlanFor(resources, stages, guards = "[\"missing\",\"generator-a\"]")
        val reordered = validPlanFor(resources, stages, guards = "[\"generator-a\",\"missing\"]")

        assertEquals("/generator_guard_rule_ids/0", validateCapacityBinding(ordered, LOAD_HASH, resources).single().jsonPointer)
        assertEquals(ordered.semanticSha256, reordered.semanticSha256)
    }

    @Test
    fun `maximum parser boundaries are accepted`() {
        val stages = (1..64).joinToString(",") { stageJson("s$it", 1, it * 10000L, (it + 1) * 10000L, "steady") }
        assertInstanceOf(
            CapacityPlanValidation.Valid::class.java,
            validateCapacityPlan(ByteArrayInputStream(planJson(stages = "[$stages]").encodeToByteArray())),
        )
        assertInstanceOf(
            CapacityPlanValidation.Valid::class.java,
            validateCapacityPlan(ByteArrayInputStream(planJson().encodeToByteArray()), Int.MAX_VALUE),
        )
        val maximum = planJson().replace("\"target\":100", "\"target\":1000000000000000000")
        assertInstanceOf(CapacityPlanValidation.Valid::class.java, validateCapacityPlan(ByteArrayInputStream(maximum.encodeToByteArray())))
    }

    @Test
    fun `overlapping valid-range stages are rejected`() {
        val stages =
            "[${stageJson("one", 1, 0, 300000, "steady")},${stageJson("two", 2, 200000, 500000, "next")}]"
        val invalid = validateCapacityPlan(ByteArrayInputStream(planJson(stages = stages).encodeToByteArray()))

        assertEquals("OVERLAPPING_STAGES", assertInstanceOf(CapacityPlanValidation.Invalid::class.java, invalid).errors.single().code)
    }

    private fun valid(json: String): CapacityPlanValidation.Valid = valid(json.encodeToByteArray())

    private fun valid(bytes: ByteArray): CapacityPlanValidation.Valid =
        assertInstanceOf(CapacityPlanValidation.Valid::class.java, validateCapacityPlan(ByteArrayInputStream(bytes)))

    private fun validPlanFor(
        resources: ResourceValidation.Valid,
        stages: String,
        axis: String = "rps",
        seriesId: String? = null,
        guards: String = "[]",
    ): CapacityPlanValidation.Valid =
        valid(
            planJson(
                snapshotHash = resources.semanticSha256,
                axis = axis,
                seriesId = seriesId,
                guards = guards,
                stages = stages,
            ),
        )

    private fun resource(): ResourceValidation.Valid =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(
                ByteArrayInputStream(
                    """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$LOAD_HASH","start_epoch_ms":0,"step_ms":10000,"point_count":60,"series":[{"id":"active","metric":"active","unit":"count","entity":"load","role":"system","aggregation":"interval_mean","values":[${"1,".repeat(
                        59,
                    )}1]},{"id":"generator","metric":"cpu","unit":"ratio","entity":"load","role":"generator","aggregation":"interval_mean","values":[${"1,".repeat(
                        59,
                    )}1]},{"id":"system","metric":"cpu","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[${"1,".repeat(
                        59,
                    )}1]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":300000},{"id":"next","from_epoch_ms":300000,"to_epoch_ms":600000}],"rules":[{"id":"generator-a","series_id":"generator","unit":"ratio","operator":"gt","threshold":0.9,"min_consecutive_cells":1,"effect":"diagnostic"},{"id":"generator-b","series_id":"generator","unit":"ratio","operator":"gt","threshold":0.9,"min_consecutive_cells":1,"effect":"diagnostic"},{"id":"sla-rule","series_id":"generator","unit":"ratio","operator":"gt","threshold":0.9,"min_consecutive_cells":1,"effect":"sla"},{"id":"system-rule","series_id":"system","unit":"ratio","operator":"gt","threshold":0.9,"min_consecutive_cells":1,"effect":"diagnostic"}]}"""
                        .encodeToByteArray(),
                ),
            ),
        )

    private fun bindingResources(
        start: Long = 0,
        windowFrom: Long = 0,
        windowTo: Long = 300_000,
        unit: String = "count",
        aggregation: ResourceAggregation = ResourceAggregation.INTERVAL_MEAN,
        value: BigDecimal = BigDecimal.ONE,
    ): ResourceValidation.Valid =
        ResourceValidation.Valid(
            ResourceSnapshotV1(
                "resource-snapshot.v1",
                LOAD_HASH,
                start,
                5_000,
                64,
                listOf(
                    ResourceSeriesV1("active", "active", unit, "load", ResourceRole.GENERATOR, aggregation, emptyMap(), List(64) { value }),
                ),
                listOf(ResourceWindowV1("steady", windowFrom, windowTo)),
                listOf(
                    ResourceRuleV1("generator-a", "active", unit, ResourceOperator.GT, BigDecimal.ONE, 1, ResourceRuleEffect.DIAGNOSTIC),
                ),
                null,
            ),
            "a".repeat(64),
            "b".repeat(64),
            byteArrayOf(),
        )

    private fun planJson(
        snapshotHash: String = "a".repeat(64),
        loadHash: String = LOAD_HASH,
        axis: String = "rps",
        seriesId: String? = null,
        guards: String = "[\"generator-a\",\"generator-b\"]",
        stages: String = "[${stageJson("one", 100, 0, 300000, "steady")},${stageJson("two", 200, 300000, 600000, "next")} ]",
        extra: String = "",
    ): String {
        val series = seriesId?.let { ",\"achieved_series_id\":\"$it\"" }.orEmpty()
        return "{" +
            "\"schema_version\":\"capacity-plan.v1\"," +
            "\"load_input_sha256\":\"$loadHash\"," +
            "\"resource_snapshot_sha256\":\"$snapshotHash\"," +
            "\"load_axis\":\"$axis\"," +
            "\"achieved_load\":{\"statistic\":\"p05_10s\",\"target_tolerance_ratio\":0.1$series}," +
            "\"generator_guard_rule_ids\":$guards,\"stages\":$stages$extra}"
    }

    private fun stageJson(
        id: String,
        target: Int,
        from: Long,
        to: Long,
        window: String,
    ): String =
        buildString {
            append("""{"id":"$id","target":$target,"from_epoch_ms":$from,"to_epoch_ms":$to,"evaluation_window_id":"$window"}""")
        }

    private companion object {
        const val LOAD_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

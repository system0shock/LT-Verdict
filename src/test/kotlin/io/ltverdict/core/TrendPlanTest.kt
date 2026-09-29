package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class TrendPlanTest {
    @Test
    fun `a valid plan keeps its raw bytes and a semantic hash`() {
        val raw = planJson().encodeToByteArray()

        val valid = valid(raw)
        val exposed = valid.rawBytes()
        exposed[0] = '!'.code.toByte()
        val check = valid.plan.checks.single()

        assertEquals(SNAPSHOT_HASH, valid.plan.resourceSnapshotSha256)
        assertEquals("cpu-growth", check.id)
        assertEquals("host-a-cpu", check.seriesId)
        assertEquals("steady", check.windowId)
        assertEquals(TrendDirection.INCREASE, check.direction)
        assertEquals(TREND_MIN_CELLS_FLOOR, check.minCells)
        assertEquals(BigDecimal("0.001"), check.magnitudeGate.minSlopeUnitsPerSecond)
        assertEquals(BigDecimal("5"), check.magnitudeGate.minSplitHalfShiftPct)
        assertEquals(64, valid.semanticSha256.length)
        assertArrayEquals(raw, valid.rawBytes())
    }

    @Test
    fun `the semantic hash ignores spelling and key order but not declared values`() {
        val canonical = valid(planJson().encodeToByteArray()).semanticSha256

        assertEquals(canonical, valid(reorderedPlanJson().encodeToByteArray()).semanticSha256)
        assertEquals(canonical, valid(planJson(gate = RESPELLED_GATE).encodeToByteArray()).semanticSha256)
        assertNotEquals(canonical, valid(planJson(direction = "decrease").encodeToByteArray()).semanticSha256)
        assertNotEquals(canonical, valid(planJson(minCells = "31").encodeToByteArray()).semanticSha256)
    }

    @Test
    fun `contract examples are accepted and an unknown field is rejected`() {
        valid(Files.readAllBytes(Path.of("docs/contracts/trend/v1/examples/valid/basic.json")))
        valid(Files.readAllBytes(Path.of("docs/contracts/trend/v1/examples/valid/either-direction.json")))

        val errors = invalid(String(Files.readAllBytes(Path.of("docs/contracts/trend/v1/examples/invalid/unknown-field.json"))))

        assertEquals(listOf("UNKNOWN_FIELD" to "/checks/0/confidence"), errors.map { it.code to it.jsonPointer })
    }

    @Test
    fun `the strict parser rejects malformed closed and unsupported input`() {
        assertEquals("MISSING_FIELD", code("{}"))
        assertEquals("UNKNOWN_FIELD", code(planJson(extra = ",\"confidence\":\"high\"")))
        assertEquals("UNKNOWN_FIELD", code(planJson(gate = """{"min_slope_units_per_second":0.001,"unused":1}""")))
        assertEquals("MISSING_FIELD", code(planJson(gate = null)))
        assertEquals("INVALID_SCHEMA_VERSION", code(planJson().replace("trend-plan.v1", "trend-plan.v2")))
        assertEquals("INVALID_SNAPSHOT_HASH", code(planJson(snapshotHash = "A".repeat(64))))
        assertEquals("INVALID_SNAPSHOT_HASH", code(planJson(snapshotHash = "a".repeat(63))))
        assertEquals("INVALID_VALUE", code(planJson(direction = "up")))
        assertEquals("INVALID_TEXT", code(planJson(seriesId = "")))
        assertEquals("MALFORMED_JSON", code(planJson(seriesId = "host\u0000a")))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(planJson(seriesId = "c".repeat(129))))
        assertEquals("MALFORMED_JSON", code(planJson() + "{}"))
        assertEquals("MALFORMED_JSON", code(planJson().replaceFirst("{", "{, ")))
        assertEquals("DUPLICATE_OBJECT_KEY", code(duplicateSchemaVersion()))
    }

    @Test
    fun `check count, cell floor and numeric gates are bounded`() {
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(checksJson(0)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(checksJson(MAX_TREND_CHECKS + 1)))
        assertEquals("DUPLICATE_CHECK_ID", code(checksJson(2, distinct = false)))
        assertEquals(MAX_TREND_CHECKS, valid(checksJson(MAX_TREND_CHECKS).encodeToByteArray()).plan.checks.size)
        assertEquals("INVALID_RANGE", code(planJson(minCells = (TREND_MIN_CELLS_FLOOR - 1).toString())))
        assertEquals("INVALID_RANGE", code(planJson(minCells = "100001")))
        assertEquals("INVALID_TYPE", code(planJson(minCells = "\"30\"")))
        assertEquals("INVALID_TYPE", code(planJson(minCells = "30.5")))
    }

    @Test
    fun `both magnitude gates are required positive numbers`() {
        assertEquals("MISSING_FIELD", code(planJson(gate = """{"min_slope_units_per_second":0.001}""")))
        assertEquals("MISSING_FIELD", code(planJson(gate = """{"min_split_half_shift_pct":5}""")))
        assertEquals("INVALID_RANGE", code(planJson(gate = """{"min_slope_units_per_second":0,"min_split_half_shift_pct":5}""")))
        assertEquals("INVALID_RANGE", code(planJson(gate = """{"min_slope_units_per_second":-0.001,"min_split_half_shift_pct":5}""")))
        assertEquals("INVALID_RANGE", code(planJson(gate = """{"min_slope_units_per_second":0.001,"min_split_half_shift_pct":0}""")))
        assertEquals("INVALID_TYPE", code(planJson(gate = """{"min_slope_units_per_second":"0.001","min_split_half_shift_pct":5}""")))
        assertEquals(
            "RESOURCE_LIMIT_EXCEEDED",
            code(planJson(gate = """{"min_slope_units_per_second":1E19,"min_split_half_shift_pct":5}""")),
        )
    }

    @Test
    fun `bounded reading and encoding fail closed`() {
        assertEquals("RESOURCE_LIMIT_EXCEEDED", invalidBytes(planJson().encodeToByteArray(), maxBytes = 16).single().code)
        assertEquals("INVALID_UTF8", invalidBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte())).single().code)
    }

    @Test
    fun `binding requires the matching snapshot series and windows`() {
        val resources = snapshot()
        val hash = resources.semanticSha256

        assertTrue(validateTrendBinding(valid(planJson(snapshotHash = hash).encodeToByteArray()), resources).isEmpty())
        assertEquals(
            listOf("TREND_SNAPSHOT_MISMATCH" to "/resource_snapshot_sha256"),
            binding(valid(planJson(snapshotHash = "b".repeat(64)).encodeToByteArray()), resources),
        )
        assertEquals(
            listOf("TREND_SERIES_NOT_FOUND" to "/checks/0/series_id"),
            binding(valid(planJson(snapshotHash = hash, seriesId = "absent").encodeToByteArray()), resources),
        )
        assertEquals(
            listOf("TREND_WINDOW_NOT_FOUND" to "/checks/0/window_id"),
            binding(valid(planJson(snapshotHash = hash, windowId = "absent").encodeToByteArray()), resources),
        )
        val both = valid(planJson(snapshotHash = hash, seriesId = "absent", windowId = "absent").encodeToByteArray())
        assertEquals(listOf("TREND_SERIES_NOT_FOUND", "TREND_WINDOW_NOT_FOUND"), binding(both, resources).map { it.first })
    }

    private fun binding(
        plan: TrendPlanValidation.Valid,
        resources: ResourceValidation.Valid,
    ): List<Pair<String, String>> = validateTrendBinding(plan, resources).map { it.code to it.jsonPointer }

    private fun duplicateSchemaVersion(): String =
        planJson().replace(
            "\"schema_version\":\"trend-plan.v1\"",
            "\"schema_version\":\"trend-plan.v1\",\"schema_version\":\"trend-plan.v1\"",
        )

    private fun snapshot(): ResourceValidation.Valid =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(Files.newInputStream(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))),
        )

    private fun checksJson(
        count: Int,
        distinct: Boolean = true,
    ): String {
        val checks =
            List(count) { index ->
                val id = if (distinct) "check-$index" else "check-duplicate"
                """{"id":"$id","series_id":"host-a-cpu","window_id":"steady","direction":"increase",""" +
                    """"min_cells":30,"magnitude_gate":{"min_slope_units_per_second":0.001,"min_split_half_shift_pct":5}}"""
            }
        return """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$SNAPSHOT_HASH","checks":[${checks.joinToString(",")}]}"""
    }

    private fun planJson(
        snapshotHash: String = SNAPSHOT_HASH,
        seriesId: String = "host-a-cpu",
        windowId: String = "steady",
        direction: String = "increase",
        minCells: String = "30",
        gate: String? = """{"min_slope_units_per_second":0.001,"min_split_half_shift_pct":5}""",
        extra: String = "",
    ): String {
        val gatePart = if (gate == null) "" else ",\"magnitude_gate\":$gate"
        return """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$snapshotHash","checks":[""" +
            """{"id":"cpu-growth","series_id":"$seriesId","window_id":"$windowId","direction":"$direction",""" +
            """"min_cells":$minCells$gatePart$extra}]}"""
    }

    private fun reorderedPlanJson(): String =
        """{"checks":[{"window_id":"steady","min_cells":30,"series_id":"host-a-cpu",""" +
            """"magnitude_gate":{"min_split_half_shift_pct":5,"min_slope_units_per_second":0.001},""" +
            """"direction":"increase","id":"cpu-growth"}],""" +
            """"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$SNAPSHOT_HASH"}"""

    private fun valid(raw: ByteArray): TrendPlanValidation.Valid =
        assertInstanceOf(TrendPlanValidation.Valid::class.java, validateTrendPlan(ByteArrayInputStream(raw)))

    private fun invalid(json: String): List<PolicyValidationError> = invalidBytes(json.encodeToByteArray())

    private fun invalidBytes(
        raw: ByteArray,
        maxBytes: Int = MAX_TREND_PLAN_BYTES,
    ): List<PolicyValidationError> =
        assertInstanceOf(
            TrendPlanValidation.Invalid::class.java,
            validateTrendPlan(ByteArrayInputStream(raw), maxBytes),
        ).errors

    private fun code(json: String): String = invalid(json).single().code

    private companion object {
        val SNAPSHOT_HASH = "a".repeat(64)
        const val RESPELLED_GATE = """{"min_slope_units_per_second":0.0010,"min_split_half_shift_pct":5.0}"""
    }
}

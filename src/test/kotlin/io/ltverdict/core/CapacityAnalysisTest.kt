package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.UtcLoadCell
import io.ltverdict.metrics.UtcLoadMetrics
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CapacityAnalysisTest {
    @Test
    fun `a stage with fewer samples than the minimum is not confirmed`() {
        val resources = resources()
        val plan = plan(CapacityLoadAxis.RPS, required = BigDecimal("300"), stages = listOf(stage("300", 300, 0)))
        val load = load("300" to List(30) { 300 })

        fun stageOf(vararg evidence: JsonObject): Pair<String, List<String>> {
            val policy = PolicyEvaluation(PolicyVerdict.PASS, emptyList(), emptyList(), evidence.toList() + guard("300", "PASS"))
            val stage = evaluateCapacity(plan, resources, load, RunValidity.VALID, policy).capacityJson["stages"]!!.jsonArray[0].jsonObject
            return stage.string("verdict") to stage.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
        }

        assertEquals("INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"), stageOf(summaryWithSamples("300", 40, null)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summaryWithSamples("300", 40, 40)))
        assertEquals("INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"), stageOf(summaryWithSamples("300", 120, 500)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summaryWithSamples("300", 120, null)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summary("300", "PASS")))
        assertEquals(
            "INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"),
            stageOf(summaryWithSamples("300", 5_000, 100), smallRule("300")),
        )
        assertEquals(
            "INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"),
            stageOf(
                summaryWithSamples("300", 5_000, 100),
                smallRule("300", "INSUFFICIENT"),
            ),
        )
        assertEquals(
            "PASS" to emptyList<String>(),
            stageOf(summaryWithSamples("300", 5_000, 100), smallRule("other")),
        )
    }

    @Test
    fun `a stage without business rules is still gated by the window sample count`() {
        val resources = resources()
        val plan = plan(CapacityLoadAxis.RPS, required = BigDecimal("300"), stages = listOf(stage("300", 300, 0)))
        val load = load("300" to List(30) { 300 })

        fun stageVerdict(samples: Long): String {
            val summary =
                JsonObject(
                    summary("300", "PASS") +
                        buildJsonObject {
                            put("business_verdict", "NO_POLICY")
                            put("resource_verdict", "PASS")
                            put("sample_count", samples)
                        },
                )
            val slaCheck =
                buildJsonObject {
                    put("id", "sla-300")
                    put("type", "resource_policy_check")
                    put("window_id", "300")
                    put("rule_id", "cpu")
                    put("effect", "sla")
                    put("status", "PASS")
                }
            val policy = PolicyEvaluation(PolicyVerdict.PASS, emptyList(), emptyList(), listOf(summary, slaCheck, guard("300", "PASS")))
            return evaluateCapacity(
                plan,
                resources,
                load,
                RunValidity.VALID,
                policy,
            ).capacityJson["stages"]!!.jsonArray[0].jsonObject.string("verdict")
        }

        assertEquals("INDETERMINATE", stageVerdict(99))
        assertEquals("PASS", stageVerdict(100))
    }

    @Test
    fun `RPS type 7 p05 produces conservative bounded interval and policy verdicts`() {
        val resources = resources()
        val plan =
            plan(
                CapacityLoadAxis.RPS,
                required = BigDecimal("300"),
                stages = listOf(stage("300", 300, 0), stage("350", 350, 300_000)),
            )
        val load = load("300" to List(30) { 296 }, "350" to List(30) { 344 })
        val policy = policy("300" to "PASS", "350" to "FAIL")

        val result = evaluateCapacity(plan, resources, load, RunValidity.VALID, policy)

        assertEquals("BOUNDED", result.capacityJson.string("bound_type"))
        assertEquals("296", result.capacityJson.string("lower_inclusive"))
        assertEquals("344", result.capacityJson.string("upper_exclusive"))
        assertEquals(PolicyVerdict.NO_VERDICT, result.policyVerdict)
        assertEquals(
            "296",
            result.capacityJson["stages"]!!
                .jsonArray[0]
                .jsonObject
                .string("achieved"),
        )

        assertEquals(
            PolicyVerdict.PASS,
            evaluateCapacity(plan.copy(requiredCapacity = BigDecimal("296")), resources, load, RunValidity.VALID, policy).policyVerdict,
        )
        assertEquals(
            PolicyVerdict.FAIL,
            evaluateCapacity(plan.copy(requiredCapacity = BigDecimal("344")), resources, load, RunValidity.VALID, policy).policyVerdict,
        )
    }

    @Test
    fun `all axes require thirty complete bins and use type 7 p05`() {
        val values = List(30) { BigDecimal.valueOf((it + 1).toLong()) }
        listOf(CapacityLoadAxis.RPS, CapacityLoadAxis.CONCURRENCY, CapacityLoadAxis.USERS).forEach { axis ->
            val resources = resources(axis = axis, values = values)
            val plan = plan(axis, required = null, stages = listOf(stage("steady", 2, 0)))
            val load = load("steady" to values.map { it.toInt() })
            val result = evaluateCapacity(plan, resources, load, RunValidity.VALID, policy("steady" to "PASS"))

            assertEquals("LOWER_BOUND", result.capacityJson.string("bound_type"))
            assertEquals("2", result.capacityJson.string("lower_inclusive"))
            assertEquals(
                "2.45",
                result.capacityJson["stages"]!!
                    .jsonArray[0]
                    .jsonObject
                    .string("achieved"),
            )
            assertEquals(
                30,
                result.capacityJson["stages"]!!
                    .jsonArray[0]
                    .jsonObject["complete_bins"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }
    }

    @Test
    fun `invalid facts cannot make bounds while exploratory SLA failures can`() {
        val resources = resources(guard = true)
        val stages = listOf(stage("pass", 300, 0), stage("fail", 350, 300_000))
        val plan = plan(CapacityLoadAxis.RPS, required = BigDecimal("296"), stages = stages, guards = listOf("guard"))
        val good = load("pass" to List(30) { 300 }, "fail" to List(30) { 350 })

        assertEquals(
            "BOUNDED",
            evaluateCapacity(
                plan,
                resources,
                good,
                RunValidity.VALID,
                policy(
                    "pass" to "PASS",
                    "fail" to "FAIL",
                    guards =
                        mapOf(
                            "pass" to "PASS",
                            "fail" to "PASS",
                        ),
                ),
            ).capacityJson.string("bound_type"),
        )

        listOf(
            policy("pass" to "PASS", "fail" to "PASS", guards = mapOf("pass" to "PASS")),
            policy("pass" to "PASS", "fail" to "PASS", guards = mapOf("pass" to "PASS", "fail" to "FAIL")),
            policy("pass" to "PASS", "fail" to "PASS", guards = mapOf("pass" to "PASS", "fail" to "PASS")),
        ).forEachIndexed { index, windowPolicy ->
            val stageLoad = if (index == 2) load("pass" to List(29) { 300 }, "fail" to List(30) { 350 }) else good
            val result = evaluateCapacity(plan, resources, stageLoad, RunValidity.VALID, windowPolicy)
            assertEquals("INDETERMINATE", result.capacityJson.string("bound_type"))
            assertEquals(PolicyVerdict.NO_VERDICT, result.policyVerdict)
        }
    }

    @Test
    fun `missing SLA target misses and non monotonic stages are indeterminate`() {
        val resources = resources()
        val load = load("one" to List(30) { 90 }, "two" to List(30) { 200 })

        val noSla =
            evaluateCapacity(
                plan(CapacityLoadAxis.RPS, BigDecimal.ONE, listOf(stage("one", 90, 0))),
                resources,
                load,
                RunValidity.VALID,
                PolicyEvaluation(
                    PolicyVerdict.PASS,
                    emptyList(),
                    emptyList(),
                    listOf(
                        JsonObject(summary("one", "NO_POLICY") + buildJsonObject { put("sample_count", 40) }),
                        guard("one", "PASS"),
                    ),
                ),
            )
        assertEquals(PolicyVerdict.NO_POLICY, noSla.policyVerdict)
        assertEquals(true, "CAPACITY_SLA_MISSING" in noSla.coverageReasons)
        val noSlaStage = noSla.capacityJson["stages"]!!.jsonArray[0].jsonObject
        assertEquals("NO_POLICY", noSlaStage.string("verdict"))
        assertEquals(
            listOf("CAPACITY_SLA_MISSING", "CAPACITY_INSUFFICIENT_SAMPLES"),
            noSlaStage.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
        )

        listOf(
            plan(CapacityLoadAxis.RPS, BigDecimal.ONE, listOf(stage("one", 100, 0))),
            plan(CapacityLoadAxis.RPS, BigDecimal.ONE, listOf(stage("one", 100, 0), stage("two", 90, 300_000))),
        ).forEach { plan ->
            val result = evaluateCapacity(plan, resources, load, RunValidity.VALID, policy("one" to "PASS", "two" to "PASS"))
            assertEquals("INDETERMINATE", result.capacityJson.string("bound_type"))
        }
    }

    @Test
    fun `first failure has an upper bound while a later pass is indeterminate`() {
        val resources = resources()
        val load = load("one" to List(30) { 100 }, "two" to List(30) { 200 }, "three" to List(30) { 300 })

        val firstFail =
            evaluateCapacity(
                plan(CapacityLoadAxis.RPS, BigDecimal("100"), listOf(stage("one", 100, 0))),
                resources,
                load,
                RunValidity.VALID,
                policy("one" to "FAIL"),
            )
        assertEquals("UPPER_BOUND", firstFail.capacityJson.string("bound_type"))
        assertEquals(PolicyVerdict.FAIL, firstFail.policyVerdict)

        val inverted =
            evaluateCapacity(
                plan(
                    CapacityLoadAxis.RPS,
                    BigDecimal("100"),
                    listOf(stage("one", 100, 0), stage("two", 200, 300_000), stage("three", 300, 600_000)),
                ),
                resources,
                load,
                RunValidity.VALID,
                policy("one" to "PASS", "two" to "FAIL", "three" to "PASS"),
            )
        assertEquals("INDETERMINATE", inverted.capacityJson.string("bound_type"))
        assertEquals(true, "CAPACITY_NON_MONOTONIC_OUTCOME" in inverted.coverageReasons)
    }

    @Test
    fun `complete source intervals are time weighted and threshold equality passes`() {
        val resources =
            ResourceSnapshotV1(
                "resource-snapshot.v1",
                "a",
                0,
                5_000,
                60,
                listOf(
                    ResourceSeriesV1(
                        "achieved",
                        "active",
                        "count",
                        "load",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        List(60) { if (it % 2 == 0) BigDecimal.ONE else BigDecimal("3") },
                    ),
                ),
                listOf(stageWindow("steady", 0)),
                emptyList(),
                null,
            )
        val result =
            evaluateCapacity(
                plan(CapacityLoadAxis.CONCURRENCY, BigDecimal("2"), listOf(stage("steady", 2, 0))),
                resources,
                load("steady" to List(30) { 0 }),
                RunValidity.VALID,
                policy("steady" to "PASS"),
            )

        assertEquals("2", result.capacityJson.string("lower_inclusive"))
        assertEquals(PolicyVerdict.PASS, result.policyVerdict)
    }

    @Test
    fun `empty guard list abstains instead of passing a stage`() {
        val result =
            evaluateCapacity(
                plan(CapacityLoadAxis.RPS, BigDecimal.ONE, listOf(stage("steady", 1, 0)), guards = emptyList()),
                resources(),
                load("steady" to List(30) { 1 }),
                RunValidity.VALID,
                policy("steady" to "PASS"),
            )

        assertEquals("INDETERMINATE", result.capacityJson.string("bound_type"))
        assertEquals(PolicyVerdict.NO_VERDICT, result.policyVerdict)
        assertEquals(true, "CAPACITY_GUARD_MISSING" in result.coverageReasons)
    }

    @Test
    fun `equal verified pass and fail endpoints are indeterminate`() {
        val result =
            evaluateCapacity(
                plan(
                    CapacityLoadAxis.RPS,
                    BigDecimal("100"),
                    listOf(stage("one", 100, 0), stage("two", 101, 300_000)),
                ),
                resources(),
                load("one" to List(30) { 100 }, "two" to List(30) { 100 }),
                RunValidity.VALID,
                policy("one" to "PASS", "two" to "FAIL"),
            )

        assertEquals("INDETERMINATE", result.capacityJson.string("bound_type"))
        assertEquals(PolicyVerdict.NO_VERDICT, result.policyVerdict)
    }

    @Test
    fun `gapped RPS and source windows retain complete-bin facts`() {
        val rps =
            evaluateCapacity(
                plan(CapacityLoadAxis.RPS, BigDecimal("100"), listOf(stage("steady", 100, 0))),
                resources(),
                load("steady" to List(29) { 100 }),
                RunValidity.VALID,
                policy("steady" to "PASS"),
            )
        assertStageFacts(rps, completeBins = 29, achieved = null)

        val source =
            evaluateCapacity(
                plan(
                    CapacityLoadAxis.CONCURRENCY,
                    BigDecimal("100"),
                    listOf(stage("steady", 100, 0, duration = 310_000)),
                ),
                resources(
                    axis = CapacityLoadAxis.CONCURRENCY,
                    values = List(31) { if (it == 10) null else BigDecimal("100") },
                    windowDuration = 310_000,
                ),
                load("steady" to List(31) { 0 }),
                RunValidity.VALID,
                policy("steady" to "PASS"),
            )
        assertStageFacts(source, completeBins = 30, achieved = "100")
    }

    @Test
    fun `cancellation is checked during capacity bins`() {
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            evaluateCapacity(
                plan(CapacityLoadAxis.RPS, null, listOf(stage("steady", 1, 0))),
                resources(),
                load("steady" to List(30) { 1 }),
                RunValidity.VALID,
                policy("steady" to "PASS"),
            ) {
                checks++
                if (checks > 1) throw IllegalStateException("cancelled")
            }
        }
        assertEquals(2, checks)
    }

    private fun plan(
        axis: CapacityLoadAxis,
        required: BigDecimal?,
        stages: List<CapacityStageV1>,
        guards: List<String> = listOf("guard"),
    ) = CapacityPlanV1(
        "a",
        "b",
        axis,
        if (axis == CapacityLoadAxis.RPS) null else "achieved",
        BigDecimal("0.05"),
        required,
        guards,
        stages,
    )

    private fun stage(
        id: String,
        target: Int,
        from: Long,
        duration: Long = 300_000,
    ) = CapacityStageV1(id, BigDecimal.valueOf(target.toLong()), from, from + duration, id)

    private fun resources(
        axis: CapacityLoadAxis = CapacityLoadAxis.RPS,
        values: List<BigDecimal?> = List(60) { BigDecimal.ONE },
        guard: Boolean = true,
        windowDuration: Long = 300_000,
    ): ResourceSnapshotV1 {
        val series =
            if (axis == CapacityLoadAxis.RPS) {
                emptyList()
            } else {
                listOf(
                    ResourceSeriesV1(
                        "achieved",
                        "achieved",
                        "count",
                        "load",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        values,
                    ),
                )
            }
        val rules =
            if (guard) {
                listOf(ResourceRuleV1("guard", "generator", "ratio", ResourceOperator.GT, BigDecimal.ONE, 1, ResourceRuleEffect.DIAGNOSTIC))
            } else {
                emptyList()
            }
        val allSeries =
            if (guard) {
                series +
                    ResourceSeriesV1(
                        "generator",
                        "cpu",
                        "ratio",
                        "load",
                        ResourceRole.GENERATOR,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        List(
                            values.size,
                        ) {
                            BigDecimal.ZERO
                        },
                    )
            } else {
                series
            }
        return ResourceSnapshotV1(
            "resource-snapshot.v1",
            "a",
            0,
            10_000,
            values.size,
            allSeries,
            listOf(
                stageWindow("one", 0, windowDuration),
                stageWindow("two", 300_000, windowDuration),
                stageWindow("three", 600_000, windowDuration),
                stageWindow("300", 0, windowDuration),
                stageWindow("350", 300_000, windowDuration),
                stageWindow("pass", 0, windowDuration),
                stageWindow("fail", 300_000, windowDuration),
                stageWindow("steady", 0, windowDuration),
            ),
            rules,
            null,
        )
    }

    private fun stageWindow(
        id: String,
        from: Long,
        duration: Long = 300_000,
    ) = ResourceWindowV1(id, from, from + duration)

    private fun load(vararg windows: Pair<String, List<Int>>) =
        UtcLoadMetrics(
            windows.associate { (id, values) ->
                id to
                    values.mapIndexed { index, value ->
                        UtcLoadCell(
                            index * 10_000L + windowStart(id),
                            (index + 1) * 10_000L + windowStart(id),
                            value.toLong() * 10,
                            0,
                            null,
                            ExactRatio(
                                value.toLong() * 10_000,
                                10_000,
                            ),
                            null,
                        )
                    }
            },
        )

    private fun windowStart(id: String) =
        when (id) {
            "two", "350", "fail" -> 300_000L
            "three" -> 600_000L
            else -> 0L
        }

    private fun policy(
        vararg verdicts: Pair<String, String>,
        guards: Map<String, String>? = null,
    ): PolicyEvaluation =
        PolicyEvaluation(
            PolicyVerdict.PASS,
            emptyList(),
            emptyList(),
            verdicts.map { (window, verdict) -> summary(window, verdict) } +
                (guards ?: verdicts.associate { (window) -> window to "PASS" }).map { (window, status) -> guard(window, status) },
        )

    private fun summary(
        window: String,
        verdict: String,
    ) = buildJsonObject {
        put("id", "summary-$window")
        put("type", "window_policy_summary")
        put("window_id", window)
        put("business_verdict", verdict)
        put("resource_verdict", "NO_POLICY")
        put("verdict", verdict)
    }

    private fun summaryWithSamples(
        window: String,
        sampleCount: Long,
        minSamples: Long?,
    ) = JsonObject(
        summary(window, "PASS") +
            buildJsonObject {
                put("sample_count", sampleCount)
                minSamples?.let { put("min_samples", it) }
            },
    )

    private fun smallRule(
        window: String,
        sampleMode: String = "SMALL_SAMPLE",
    ) = buildJsonObject {
        put("id", "check-$window")
        put("type", "policy_check")
        put("window_id", window)
        put("rule_id", "p95")
        put("status", "PASS")
        put("sample_mode", sampleMode)
    }

    private fun guard(
        window: String,
        status: String,
    ): JsonObject =
        buildJsonObject {
            put("id", "guard-$window")
            put("type", "resource_policy_check")
            put("window_id", window)
            put("rule_id", "guard")
            put("effect", "diagnostic")
            put("status", status)
        }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun assertStageFacts(
        result: CapacityAnalysis,
        completeBins: Int,
        achieved: String?,
    ) {
        val stage =
            result.capacityJson["stages"]!!
                .jsonArray
                .single()
                .jsonObject
        assertEquals("INDETERMINATE", result.capacityJson.string("bound_type"))
        assertEquals(completeBins.toString(), stage.string("complete_bins"))
        assertEquals(achieved, stage["achieved"]?.jsonPrimitive?.contentOrNull)
        assertEquals("100", stage.string("observed_min"))
        assertEquals("100", stage.string("observed_max"))
    }
}

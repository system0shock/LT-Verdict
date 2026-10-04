package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class WindowPolicyEvaluationTest {
    @Test
    fun `business checks are window scoped while the global metric summary stays singular`() {
        val evaluation =
            evaluateSharedWindowPolicy(
                policy = policy("100"),
                validity = RunValidity.VALID,
                globalMetrics = metrics(95),
                windowMetrics = mapOf("first" to metrics(90), "second" to metrics(110)),
                resource = resource(PolicyVerdict.NO_POLICY, PolicyVerdict.NO_POLICY),
                windows = WINDOWS,
            )
        val checks = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "policy_check" }
        val summaries = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "metric_summary" }
        val windowSummaries = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "window_policy_summary" }

        assertEquals(PolicyVerdict.FAIL, evaluation.verdict)
        assertEquals(1, summaries.count { it["scope"] != null })
        assertEquals(listOf("first", "second"), checks.map { it.getValue("window_id").jsonPrimitive.content })
        assertEquals(2, checks.map { it.getValue("id").jsonPrimitive.content }.distinct().size)
        assertEquals(
            listOf("overall", "overall"),
            checks.map {
                it
                    .getValue("scope")
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content
            },
        )
        assertEquals(listOf("PASS", "FAIL"), windowSummaries.map { it.getValue("verdict").jsonPrimitive.content })
    }

    @Test
    fun `window summary carries the window sample count and the default minimum only when set`() {
        val withDefault =
            evaluateSharedWindowPolicy(
                policy("100").copy(defaults = PolicyDefaultsV1(sampleFloor = 1, minSamples = 40)),
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 25), "second" to metrics(90, 7)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )
        val noPolicy =
            evaluateSharedWindowPolicy(
                null,
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 25), "second" to metrics(90, 7)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )

        fun summaries(evaluation: PolicyEvaluation) =
            evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "window_policy_summary" }

        assertEquals(listOf("25", "7"), summaries(withDefault).map { it.getValue("sample_count").jsonPrimitive.content })
        assertEquals(listOf("40", "40"), summaries(withDefault).map { it.getValue("min_samples").jsonPrimitive.content })
        assertEquals(listOf("25", "7"), summaries(noPolicy).map { it.getValue("sample_count").jsonPrimitive.content })
        assertEquals(listOf(null, null), summaries(noPolicy).map { it["min_samples"] })
    }

    @Test
    fun `missing required resource data dominates an observed business failure`() {
        val evaluation =
            evaluateSharedWindowPolicy(
                policy("100"),
                RunValidity.VALID,
                metrics(110),
                mapOf("first" to metrics(110), "second" to metrics(90)),
                resource(PolicyVerdict.NO_VERDICT, PolicyVerdict.PASS, reasons = listOf("MISSING_RESOURCE_CELLS")),
                WINDOWS,
            )

        assertEquals(PolicyVerdict.NO_VERDICT, evaluation.verdict)
        assertEquals(listOf("MISSING_RESOURCE_CELLS"), evaluation.coverageReasons)
        assertEquals(
            listOf("NO_VERDICT", "PASS"),
            evaluation.evidence
                .filter { it["type"]?.jsonPrimitive?.content == "window_policy_summary" }
                .map { it.getValue("verdict").jsonPrimitive.content },
        )
    }

    @Test
    fun `no mandatory rules stays no policy and an empty business window is no verdict`() {
        val noPolicy =
            evaluateSharedWindowPolicy(
                null,
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90), "second" to metrics(90)),
                resource(PolicyVerdict.NO_POLICY, PolicyVerdict.NO_POLICY),
                WINDOWS,
            )
        val emptyWindow =
            evaluateSharedWindowPolicy(
                policy("100"),
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 0), "second" to metrics(90)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )
        val resourceOnly =
            evaluateSharedWindowPolicy(
                null,
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 0), "second" to metrics(90)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )

        assertEquals(PolicyVerdict.NO_POLICY, noPolicy.verdict)
        assertEquals(PolicyVerdict.NO_VERDICT, emptyWindow.verdict)
        assertEquals(listOf("METRIC_NOT_AVAILABLE", "BUSINESS_OBSERVATIONS_NOT_FOUND"), emptyWindow.coverageReasons)
        assertEquals(PolicyVerdict.PASS, resourceOnly.verdict)
    }

    @Test
    fun `diagnostic resource failure does not change passing business SLA`() {
        val snapshot =
            ResourceSnapshotV1(
                "resource-snapshot.v1",
                "0".repeat(64),
                0,
                1_000,
                2,
                listOf(
                    ResourceSeriesV1(
                        "cpu",
                        "cpu",
                        "ratio",
                        "host",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        listOf(BigDecimal("0.9"), BigDecimal("0.9")),
                    ),
                ),
                listOf(ResourceWindowV1("first", 0, 1_000), ResourceWindowV1("second", 1_000, 2_000)),
                listOf(
                    ResourceRuleV1(
                        "observe",
                        "cpu",
                        "ratio",
                        ResourceOperator.GT,
                        BigDecimal("0.8"),
                        1,
                        ResourceRuleEffect.DIAGNOSTIC,
                    ),
                ),
                null,
            )
        val resources = evaluateResources(snapshot, snapshot.windows)
        val evaluation =
            evaluateSharedWindowPolicy(
                policy("100"),
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90), "second" to metrics(90)),
                resources,
                snapshot.windows,
            )

        assertEquals(PolicyVerdict.PASS, evaluation.verdict)
        assertEquals(2, resources.findings.size)
    }

    @Test
    fun `a sparse window blocks the verdict even when the whole run has enough samples`() {
        val gated = policy("100").copy(defaults = PolicyDefaultsV1(sampleFloor = 5, minSamples = 8))
        val evaluation =
            evaluateSharedWindowPolicy(
                policy = gated,
                validity = RunValidity.VALID,
                globalMetrics = metrics(90, count = 20),
                windowMetrics = mapOf("first" to metrics(90, count = 12), "second" to metrics(200, count = 3)),
                resource = resource(PolicyVerdict.NO_POLICY, PolicyVerdict.NO_POLICY),
                windows = WINDOWS,
            )
        val modes =
            evaluation.evidence
                .filter { it["type"]?.jsonPrimitive?.content == "policy_check" }
                .associate { it.getValue("window_id").jsonPrimitive.content to it.getValue("sample_mode").jsonPrimitive.content }

        assertEquals(mapOf("first" to "FULL", "second" to "INSUFFICIENT"), modes)
        assertEquals(PolicyVerdict.NO_VERDICT, evaluation.verdict)
        assertEquals(listOf("INSUFFICIENT_SAMPLES"), evaluation.coverageReasons)
        assertEquals(emptyList<Any>(), evaluation.findings)
    }

    private fun policy(threshold: String) =
        PolicyV1(
            "policy.v1",
            "p",
            listOf(
                PolicyRuleV1(
                    "p95",
                    PolicyMetric.RESPONSE_TIME_P95_MS,
                    PolicyOperator.LTE,
                    BigDecimal(threshold),
                    PolicyScope.Overall,
                ),
            ),
            PolicyDefaultsV1(sampleFloor = 1, minSamples = 1),
        )

    private fun metrics(
        p95: Long,
        count: Long = 1,
    ) = NormalizedMetrics(
        MetricSummary(
            count,
            0,
            if (count == 0L) null else ExactRatio(0, count),
            ExactRatio(count * 1_000, 1_000),
            LatencySummary(p95, p95, p95, p95),
        ),
        emptyList(),
        emptyList(),
        emptyMap(),
    )

    private fun resource(
        first: PolicyVerdict,
        second: PolicyVerdict,
        reasons: List<String> = emptyList(),
    ) = ResourceEvaluation(
        linkedMapOf("first" to first, "second" to second),
        reasons,
        emptyList(),
        emptyList(),
    )

    private companion object {
        val WINDOWS = listOf(ResourceWindowV1("first", 0, 1_000), ResourceWindowV1("second", 1_000, 2_000))
    }
}

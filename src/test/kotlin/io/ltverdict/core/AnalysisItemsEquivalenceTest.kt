package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.SampleKind
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.TransactionIdentity
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * W2.1 slice 2: every typed builder writes the same JsonObject, and so the same canonical bytes, as the hand-built one it
 * replaced. The oracle is LegacyAnalysisItems.kt, a frozen copy of the old builders.
 */
class AnalysisItemsEquivalenceTest {
    private var compared = 0

    private fun outcome(item: JsonObject): String =
        runCatching { canonicalJson(item).decodeToString() }.getOrElse { "FAILS: ${it.message}" }

    // A kotlinx encoding of the in-memory item must also stay what it was: it writes a plain JsonPrimitive number through
    // Double ("0.10" becomes 0.1) and an unquoted literal verbatim, although the two are equal. Compared leaf by leaf, because
    // the key order of a kotlinx encoding is the insertion order.
    private fun kotlinxLeaves(
        element: JsonElement,
        path: String = "",
        into: MutableMap<String, String> = sortedMapOf(),
    ): Map<String, String> {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> kotlinxLeaves(value, "$path/$key", into) }
            is JsonArray -> element.forEachIndexed { index, value -> kotlinxLeaves(value, "$path[$index]", into) }
            is JsonPrimitive ->
                into[path] = runCatching { Json.encodeToString(JsonElement.serializer(), element) }.getOrElse { "FAILS: ${it::class}" }
        }
        return into
    }

    private fun same(
        expected: JsonObject,
        actual: JsonObject,
        label: String,
    ) {
        assertEquals(expected, actual, label)
        assertEquals(outcome(expected), outcome(actual), label)
        assertEquals(kotlinxLeaves(expected), kotlinxLeaves(actual), label)
        compared++
    }

    private val thresholds =
        listOf(
            "300",
            "0.10",
            "-0.0",
            "1E+3",
            "12345678901234567890.5",
            "0.1234567890123456789012345678901234567890",
            "1E+400",
            "1E-400",
        ).map(::BigDecimal)

    private val identities =
        listOf(
            null,
            TransactionIdentity(emptyList(), "GET /health", SampleKind.JMETER_SAMPLER),
            TransactionIdentity(listOf("Group A", "é\u0001\"\\", "😀"), "POST /checkout", SampleKind.GATLING_GROUP),
            TransactionIdentity(listOf(""), "", SampleKind.JMETER_CONTAINER),
        )

    private val summaries =
        listOf(
            MetricSummary(0, 0, null, ExactRatio(0, 1), LatencySummary(0, 0, 0, 0)),
            MetricSummary(500, 7, ExactRatio(7, 500), ExactRatio(500_000, 1040), LatencySummary(120, 250, 275, 500)),
            MetricSummary(
                Long.MAX_VALUE,
                Long.MAX_VALUE - 1,
                ExactRatio(Long.MAX_VALUE - 1, Long.MAX_VALUE),
                ExactRatio(Long.MAX_VALUE, Long.MAX_VALUE),
                LatencySummary(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
            ),
        )

    private val windowIds = listOf(null, "steady", "w-é\u0001😀")

    @Test
    fun `metric summaries equal the old builder`() {
        for (identity in identities) {
            for (summary in summaries) {
                for (windowId in windowIds) {
                    same(
                        legacyMetricSummary("metric-summary-x", identity, summary, windowId),
                        metricSummary("metric-summary-x", identity, summary, windowId),
                        "$identity $summary $windowId",
                    )
                }
            }
        }
    }

    @Test
    fun `policy checks equal the old builder for every optional field and threshold`() {
        val observedValues: List<kotlinx.serialization.json.JsonElement?> =
            listOf(null, JsonPrimitive(339L), JsonPrimitive(Long.MAX_VALUE), legacyRatioJson(ExactRatio(1, 3)))
        val gates =
            listOf(null, SampleGate(null, 0, 20, 100)) +
                SampleMode.entries.flatMap { mode ->
                    listOf(SampleGate(mode, 5, 20, 100), SampleGate(mode, Long.MAX_VALUE, 0, Long.MAX_VALUE))
                }
        val scopes = listOf(PolicyScope.Overall, PolicyScope.Transaction("é \"x\""))

        fun check(
            rule: PolicyRuleV1,
            identity: TransactionIdentity?,
        ) {
            val evidence =
                MetricEvidence(identity, summaries[1], "metric-ev", legacyMetricSummary("metric-ev", identity, summaries[1], "steady"))
            for (metric in listOf(null, evidence)) {
                for (observed in observedValues) {
                    for (reason in listOf(null, "POLICY_FAILED", "INSUFFICIENT_SAMPLES")) {
                        for (windowId in windowIds) {
                            for (include in listOf(true, false)) {
                                for (gate in gates) {
                                    same(
                                        legacyPolicyCheck(rule, metric, observed, reason, windowId, include, gate),
                                        policyCheck(rule, metric, observed, reason, windowId, include, gate),
                                        "$rule $metric $observed $reason $windowId $include $gate",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        for (threshold in thresholds) {
            for (scope in scopes) {
                for (metricKind in PolicyMetric.entries) {
                    for (operator in PolicyOperator.entries) {
                        check(PolicyRuleV1("rule-é-${metricKind.name}", metricKind, operator, threshold, scope), identities[2])
                    }
                }
            }
        }
        val plain = PolicyRuleV1("rule", PolicyMetric.THROUGHPUT_RPS, PolicyOperator.GTE, BigDecimal("1.5E+2"), PolicyScope.Overall)
        identities.forEach { check(plain, it) }
        assertTrue(compared > 100_000, "matrix size $compared")
    }

    @Test
    fun `a wide threshold keeps its exact literal`() {
        val rule =
            PolicyRuleV1(
                "r",
                PolicyMetric.RESPONSE_TIME_P95_MS,
                PolicyOperator.LTE,
                BigDecimal("12345678901234567890.5"),
                PolicyScope.Overall,
            )
        val check = policyCheck(rule, null, null, null, null, false, null)
        val threshold = check.getValue("threshold").jsonPrimitive
        assertEquals("12345678901234567890.5", threshold.content)
        assertFalse(threshold.isString)
        val huge = PolicyRuleV1("r", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, BigDecimal("1E+400"), PolicyScope.Overall)
        assertEquals("1E+400", policyCheck(huge, null, null, null, null, false, null).getValue("threshold").jsonPrimitive.content)
    }

    @Test
    fun `diagnostic evidence and findings policy failures and rule window checks equal the old builder`() {
        val messages = listOf("", "row skipped", "late \"é\" \u0001\u001f\\/ 😀", "x".repeat(1000))
        val offsets = listOf(null, 0L, 1L, 4096L, Long.MAX_VALUE, -1L)
        for (code in listOf("CSV_ROW_SKIPPED", "TIMESTAMP_RANGE", "")) {
            for (message in messages) {
                for (offset in offsets) {
                    val diagnostic = Diagnostic(code, message, offset)
                    same(legacyDiagnosticEvidence(diagnostic), diagnosticEvidence(diagnostic), "evidence $diagnostic")
                    same(legacyDiagnosticFinding(diagnostic), diagnosticFinding(diagnostic), "finding $diagnostic")
                }
            }
        }
        for (rule in listOf("overall-p95", "é\u0001", "")) {
            val policyRule = PolicyRuleV1(rule, PolicyMetric.ERROR_RATE_RATIO, PolicyOperator.LTE, BigDecimal("0.01"), PolicyScope.Overall)
            for (windowId in windowIds) {
                same(
                    legacyPolicyFailure(policyRule, "ev-1", windowId),
                    policyFailure(policyRule, "ev-1", windowId),
                    "failure $rule $windowId",
                )
            }
            for (windowId in listOf("steady", "w-é\u0001😀", "")) {
                same(legacyRuleWindowCheck(rule, windowId), ruleWindowCheck(rule, windowId), "rule window $rule $windowId")
            }
        }
    }

    @Test
    fun `window policy summaries equal the old builder`() {
        val windows =
            listOf(
                ResourceWindowV1("steady", 1_767_225_600_000, 1_767_225_660_000),
                ResourceWindowV1("w-é\u0001😀", 0, Long.MAX_VALUE),
            )
        for (window in windows) {
            for (business in PolicyVerdict.entries) {
                for (resource in PolicyVerdict.entries) {
                    for (verdict in PolicyVerdict.entries) {
                        for (sampleCount in listOf(0L, 25L, Long.MAX_VALUE)) {
                            for (minSamples in listOf(null, 0L, 40L, Long.MAX_VALUE)) {
                                same(
                                    legacyWindowPolicySummary(window, business, resource, verdict, sampleCount, minSamples),
                                    windowPolicySummary(window, business, resource, verdict, sampleCount, minSamples),
                                    "$window $business $resource $verdict $sampleCount $minSamples",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun series(
        id: String,
        values: List<String?>,
        role: ResourceRole,
        aggregation: ResourceAggregation,
    ) = ResourceSeriesV1(id, "cpu_é", "ratio", "vm-\"1\"", role, aggregation, emptyMap(), values.map { it?.let(::BigDecimal) })

    private val start = 1_767_225_600_000

    private fun snapshot(series: List<ResourceSeriesV1>) =
        ResourceSnapshotV1("resource-snapshot.v1", "0".repeat(64), start, 10_000, 8, series, emptyList(), emptyList(), null)

    private val patterns =
        listOf(
            List(8) { "0.1" },
            listOf("0.1", "0.2", "0.95", "0.97", "0.99", "0.2", "0.1", "0.1"),
            listOf("0.1", null, "0.95", null, null, "0.2", "0.1", "0.1"),
            List(8) { null },
            listOf("12345678901234567890.123456789", "1E+5", "-0.5000", "0", "0.10", "3", "4", "5"),
            listOf("1", null, null, null, null, null, null, null),
            listOf(null, null, null, null, null, null, null, "7"),
        )

    private val windows =
        listOf(
            ResourceWindowV1("steady", start + 20_000, start + 60_000),
            ResourceWindowV1("all", start, start + 80_000),
            ResourceWindowV1("one-é", start + 30_000, start + 40_000),
            ResourceWindowV1("empty", start + 30_000, start + 30_000),
        )

    @Test
    fun `resource summaries equal the old builder`() {
        for (pattern in patterns) {
            for (role in ResourceRole.entries) {
                for (aggregation in ResourceAggregation.entries) {
                    val series = series("cpu-é", pattern, role, aggregation)
                    val snapshot = snapshot(listOf(series))
                    for (window in windows) {
                        same(
                            legacyResourceSummary(snapshot, series, window) {},
                            resourceSummary(snapshot, series, window) {},
                            "$pattern $role $aggregation ${window.id}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `resource policy checks and threshold findings equal the old builder`() {
        val series = series("cpu-é", patterns[1], ResourceRole.SYSTEM, ResourceAggregation.INTERVAL_MEAN)
        val snapshot = snapshot(listOf(series))
        val cellSets = listOf(null, CellStats(8, 8, 0), CellStats(8, 5, 2), CellStats(0, 0, 0), CellStats(1, 0, 1))
        // 1E+400 and 1E-400 exceed the canonical decimal limit; they fail alike in both builders (next test)
        for (threshold in thresholds.filter { runCatching { canonicalDecimal(it) }.isSuccess }) {
            for (operator in ResourceOperator.entries) {
                for (effect in ResourceRuleEffect.entries) {
                    for (platform in listOf(null, PlatformRuleRef("cpu-share", "orders-é"), PlatformRuleRef("", ""))) {
                        val rule =
                            ResourceRuleV1("rule-é", "cpu-é", "ratio", operator, threshold, 2, effect, null, platform, BigDecimal("0.2"), 3)
                        for (status in listOf("PASS", "FAIL", "NO_VERDICT")) {
                            for (reason in listOf(null, "MISSING_RESOURCE_CELLS", "")) {
                                for (cells in cellSets) {
                                    same(
                                        legacyResourceCheck("check-1", windows[0], rule, status, reason, cells),
                                        resourceCheck("check-1", windows[0], rule, status, reason, cells),
                                        "$rule $status $reason $cells",
                                    )
                                }
                            }
                        }
                        for (presumed in listOf(false, true)) {
                            for (bounds in listOf(0 to 8, 2 to 5, 3 to 4)) {
                                for (
                                extremes in
                                listOf(
                                    BigDecimal("0.1") to BigDecimal("0.99"),
                                    BigDecimal("12345678901234567890.123456789") to BigDecimal("1E+5"),
                                    BigDecimal("-0.0") to BigDecimal("0.10"),
                                )
                                ) {
                                    same(
                                        legacyThresholdFinding(
                                            snapshot,
                                            windows[0],
                                            series,
                                            rule,
                                            "check-1",
                                            bounds.first,
                                            bounds.second,
                                            extremes.first,
                                            extremes.second,
                                            presumed,
                                        ),
                                        thresholdFinding(
                                            snapshot,
                                            windows[0],
                                            series,
                                            rule,
                                            "check-1",
                                            bounds.first,
                                            bounds.second,
                                            extremes.first,
                                            extremes.second,
                                            presumed,
                                        ),
                                        "$rule $presumed $bounds $extremes",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a resource threshold beyond the canonical decimal limit fails alike in both builders`() {
        val rule = ResourceRuleV1("r", "s", "ratio", ResourceOperator.GT, BigDecimal("1E+400"), 1, ResourceRuleEffect.SLA)
        val old = runCatching { legacyResourceCheck("c", windows[0], rule, "PASS", null, null) }.exceptionOrNull()
        val new = runCatching { resourceCheck("c", windows[0], rule, "PASS", null, null) }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class, old?.let { it::class })
        assertEquals(old?.message, new?.message)
        assertEquals(old?.let { it::class }, new?.let { it::class })
    }

    @Test
    fun `the type literals are the class serial names of the two hierarchies`() {
        fun types(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) =
            descriptor
                .getElementDescriptor(1)
                .elementDescriptors
                .map { it.serialName }
                .toSet()
        assertEquals(
            setOf(
                "metric_summary",
                "policy_check",
                "diagnostic",
                "rule_window_check",
                "window_policy_summary",
                "resource_summary",
                "resource_policy_check",
            ),
            types(AnalysisEvidence.serializer().descriptor),
        )
        assertEquals(
            setOf("diagnostic", "policy_failure", "resource_threshold_violation"),
            types(AnalysisFinding.serializer().descriptor),
        )
    }
}

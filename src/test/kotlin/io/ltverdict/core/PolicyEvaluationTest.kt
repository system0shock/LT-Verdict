package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.TransactionIdentity
import io.ltverdict.metrics.TransactionSummary
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PolicyEvaluationTest {
    @Test
    fun `valid run passes compliant policy and only valid run gets no policy`() {
        val policy = policy(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100"))

        assertEquals(PolicyVerdict.PASS, evaluatePolicy(policy, RunValidity.VALID, metrics()).verdict)
        assertEquals(PolicyVerdict.NO_POLICY, evaluatePolicy(null, RunValidity.VALID, metrics()).verdict)
        assertEquals(PolicyVerdict.NO_VERDICT, evaluatePolicy(null, RunValidity.INVALID, null).verdict)
    }

    @Test
    fun `invalid and degraded validity override policy result and retain diagnostics`() {
        val failing = policy(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "99"))

        val invalid =
            evaluatePolicy(
                failing,
                RunValidity.INVALID,
                metrics(),
                listOf(Diagnostic("MALFORMED_INPUT", "broken", 3)),
            )
        val degraded =
            evaluatePolicy(
                failing,
                RunValidity.DEGRADED,
                metrics(),
                listOf(Diagnostic("TRUNCATED_GATLING_BINARY", "truncated", 9)),
            )

        assertEquals(PolicyVerdict.NO_VERDICT, invalid.verdict)
        assertEquals(listOf("MALFORMED_INPUT"), invalid.coverageReasons)
        assertEquals(PolicyVerdict.NO_VERDICT, degraded.verdict)
        assertEquals(listOf("TRUNCATED_GATLING_BINARY"), degraded.coverageReasons)
    }

    @Test
    fun `missing and ambiguous transaction override an otherwise failing policy`() {
        val failingOverall = rule("overall", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "99")
        val missing = rule("missing", PolicyMetric.RESPONSE_TIME_P99_MS, PolicyOperator.LTE, "100", "absent")
        val ambiguous = rule("ambiguous", PolicyMetric.RESPONSE_TIME_P99_MS, PolicyOperator.LTE, "100", "shared")

        val missingResult = evaluatePolicy(policy(failingOverall, missing), RunValidity.VALID, metrics())
        val ambiguousResult =
            evaluatePolicy(
                policy(failingOverall, ambiguous),
                RunValidity.VALID,
                metrics(transactions = listOf(transaction("shared", listOf("a")), transaction("shared", listOf("b")))),
            )

        assertEquals(PolicyVerdict.NO_VERDICT, missingResult.verdict)
        assertEquals(listOf("TRANSACTION_NOT_FOUND"), missingResult.coverageReasons)
        assertEquals(PolicyVerdict.NO_VERDICT, ambiguousResult.verdict)
        assertEquals(listOf("AMBIGUOUS_TRANSACTION"), ambiguousResult.coverageReasons)
    }

    @Test
    fun `evaluates exact ratios and keeps typed policy checks in policy order`() {
        val rules =
            policy(
                rule("ratio-fails", PolicyMetric.ERROR_RATE_RATIO, PolicyOperator.LTE, "0.333333"),
                rule("ratio-passes", PolicyMetric.ERROR_RATE_RATIO, PolicyOperator.LTE, "0.3333334"),
            )

        val result = evaluatePolicy(rules, RunValidity.VALID, metrics(errorRate = ExactRatio(1, 3)))
        val repeated = evaluatePolicy(rules, RunValidity.VALID, metrics(errorRate = ExactRatio(1, 3)))
        val checks = result.evidence.filter { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.FAIL, result.verdict)
        assertEquals(listOf("ratio-fails", "ratio-passes"), checks.map { it.getValue("rule_id").jsonPrimitive.content })
        assertEquals(result.evidence, repeated.evidence)
        assertTrue(result.evidence.all { it["type"]?.jsonPrimitive?.content?.isNotEmpty() == true })
        assertTrue(result.evidence.all { it["id"]?.jsonPrimitive?.content?.isNotEmpty() == true })
        assertTrue(result.findings.all { it["type"]?.jsonPrimitive?.content?.isNotEmpty() == true })
        assertTrue(result.findings.all { it["id"]?.jsonPrimitive?.content?.isNotEmpty() == true })
    }

    @Test
    fun `zero-sample overall metric is unavailable instead of passing`() {
        val empty =
            NormalizedMetrics(
                MetricSummary(0, 0, null, ExactRatio(0, 1), LatencySummary(0, 0, 0, 0)),
                emptyList(),
                emptyList(),
                emptyMap(),
            )

        val result =
            evaluatePolicy(
                policy(rule("empty-p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100")),
                RunValidity.VALID,
                empty,
            )

        assertEquals(PolicyVerdict.NO_VERDICT, result.verdict)
        assertEquals(listOf("METRIC_NOT_AVAILABLE"), result.coverageReasons)
    }

    private fun policy(vararg rules: PolicyRuleV1) = PolicyV1("policy.v1", "test", rules.toList())

    private fun rule(
        id: String,
        metric: PolicyMetric,
        operator: PolicyOperator,
        threshold: String,
        transaction: String? = null,
    ) = PolicyRuleV1(
        id,
        metric,
        operator,
        BigDecimal(threshold),
        transaction?.let(PolicyScope::Transaction) ?: PolicyScope.Overall,
    )

    private fun metrics(
        errorRate: ExactRatio = ExactRatio(1, 10),
        transactions: List<TransactionSummary> = emptyList(),
    ) = NormalizedMetrics(summary(errorRate), transactions, emptyList(), emptyMap())

    private fun transaction(
        label: String,
        groupPath: List<String>,
    ) = TransactionSummary(TransactionIdentity(groupPath, label, SampleKind.GATLING_REQUEST), summary(ExactRatio(0, 1)))

    private fun summary(errorRate: ExactRatio) =
        MetricSummary(
            sampleCount = errorRate.denominator,
            errorCount = errorRate.numerator,
            errorRate = errorRate,
            throughputRps = ExactRatio(10, 1),
            latency = LatencySummary(p50Millis = 50, p95Millis = 100, p99Millis = 100, maxMillis = 100),
        )
}

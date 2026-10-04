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

    @Test
    fun `sample gate follows the floor and the minimum at their boundaries`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100")))

        listOf(
            19L to Triple(PolicyVerdict.NO_VERDICT, "INSUFFICIENT", listOf("INSUFFICIENT_SAMPLES")),
            20L to Triple(PolicyVerdict.PASS, "SMALL_SAMPLE", listOf("SMALL_SAMPLE")),
            99L to Triple(PolicyVerdict.PASS, "SMALL_SAMPLE", listOf("SMALL_SAMPLE")),
            100L to Triple(PolicyVerdict.PASS, "FULL", emptyList()),
        ).forEach { (samples, expected) ->
            val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(samples))
            val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

            assertEquals(expected.first, result.verdict, "n=$samples")
            assertEquals(expected.third, result.coverageReasons, "n=$samples")
            assertEquals(expected.second, check.getValue("sample_mode").jsonPrimitive.content, "n=$samples")
            assertEquals(samples.toString(), check.getValue("sample_count").jsonPrimitive.content, "n=$samples")
            assertEquals("20", check.getValue("sample_floor").jsonPrimitive.content)
            assertEquals("100", check.getValue("min_samples").jsonPrimitive.content)
        }
    }

    @Test
    fun `zero observations keep the existing reason and carry no sample mode`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100")))

        val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(0))
        val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.NO_VERDICT, result.verdict)
        assertEquals(listOf("METRIC_NOT_AVAILABLE"), result.coverageReasons)
        assertEquals(null, check["sample_mode"])
        assertEquals(null, check["sample_count"])
    }

    @Test
    fun `the rule minimum beats the policy default and the default beats the constant`() {
        val rules = listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100"))
        val byRule = PolicyV1("policy.v1", "gate", listOf(rules.single().copy(minSamples = 30)), PolicyDefaultsV1(10, 500))
        val byDefault = PolicyV1("policy.v1", "gate", rules, PolicyDefaultsV1(sampleFloor = 10, minSamples = 40))

        fun mode(
            policy: PolicyV1,
            samples: Long,
        ) = evaluatePolicy(policy, RunValidity.VALID, metricsWith(samples))
            .evidence
            .single { it["type"]?.jsonPrimitive?.content == "policy_check" }
            .getValue("sample_mode")
            .jsonPrimitive.content

        assertEquals("FULL", mode(byRule, 30))
        assertEquals("SMALL_SAMPLE", mode(byRule, 29))
        assertEquals("INSUFFICIENT", mode(byRule, 9))
        assertEquals("FULL", mode(byDefault, 40))
        assertEquals("SMALL_SAMPLE", mode(byDefault, 39))
    }

    @Test
    fun `throughput is not gated and can still fail on a small positive count`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("rps", PolicyMetric.THROUGHPUT_RPS, PolicyOperator.GTE, "50")))

        val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(3))
        val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.FAIL, result.verdict)
        assertEquals(emptyList<String>(), result.coverageReasons)
        assertEquals("NOT_GATED", check.getValue("sample_mode").jsonPrimitive.content)
        assertEquals(null, check["min_samples"])
    }

    @Test
    fun `small sample keeps a real failure while an insufficient rule blocks it`() {
        val checkout = rule("checkout-p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "1000", "checkout").copy(minSamples = 50)
        val errors = rule("errors", PolicyMetric.ERROR_RATE_RATIO, PolicyOperator.LTE, "0.01")
        val rare = rule("rare-p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "1000", "rare")
        val metrics =
            NormalizedMetrics(
                summaryOf(5_000, errorCount = 500),
                listOf(transactionOf("checkout", 30), transactionOf("rare", 12)),
                emptyList(),
                emptyMap(),
            )

        val small = evaluatePolicy(PolicyV1("policy.v1", "gate", listOf(checkout, errors)), RunValidity.VALID, metrics)
        val blocked = evaluatePolicy(PolicyV1("policy.v1", "gate", listOf(checkout, errors, rare)), RunValidity.VALID, metrics)

        assertEquals(PolicyVerdict.FAIL, small.verdict)
        assertEquals(listOf("SMALL_SAMPLE"), small.coverageReasons)
        assertEquals(listOf("errors"), small.findings.map { it.getValue("rule_id").jsonPrimitive.content })
        assertEquals(PolicyVerdict.NO_VERDICT, blocked.verdict)
        assertEquals(listOf("INSUFFICIENT_SAMPLES", "SMALL_SAMPLE"), blocked.coverageReasons)
        assertEquals(listOf("errors"), blocked.findings.map { it.getValue("rule_id").jsonPrimitive.content })
    }

    private fun policy(vararg rules: PolicyRuleV1) =
        PolicyV1("policy.v1", "test", rules.toList(), PolicyDefaultsV1(sampleFloor = 1, minSamples = 1))

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

    private fun metricsWith(samples: Long) =
        NormalizedMetrics(
            MetricSummary(
                samples,
                0,
                if (samples == 0L) null else ExactRatio(0, samples),
                ExactRatio(10, 1),
                LatencySummary(50, 100, 100, 100),
            ),
            emptyList(),
            emptyList(),
            emptyMap(),
        )

    private fun summaryOf(
        samples: Long,
        errorCount: Long = 0,
    ) = MetricSummary(samples, errorCount, ExactRatio(errorCount, samples), ExactRatio(10, 1), LatencySummary(50, 100, 100, 100))

    private fun transactionOf(
        label: String,
        samples: Long,
    ) = TransactionSummary(TransactionIdentity(emptyList(), label, SampleKind.GATLING_REQUEST), summaryOf(samples))
}

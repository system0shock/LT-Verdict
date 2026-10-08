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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 slice 2 characterization of the policy, window-policy and resource producers: the findings and evidence they write
 * for a wide matrix of inputs hash to the values captured from `origin/main` BEFORE the producers were typed. Regenerate only
 * on purpose with `LTV_UPDATE_TYPED_EVIDENCE=1` (a change here is a change of analysis-result bytes and of analysis_id).
 */
class PolicyResourceEvidenceSnapshotTest {
    private val snapshotFile = Path.of("fixtures/typed-evidence/policy-resource.sha256")

    @Test
    fun `policy, window policy and resource findings and evidence equal the pre-typing snapshot`() {
        val groups = linkedMapOf<String, StringBuilder>()
        val seenTypes = sortedSetOf<String>()
        val seenKeys = sortedSetOf<String>()

        fun record(
            group: String,
            findings: List<JsonObject>,
            evidence: List<JsonObject>,
            verdict: Any?,
            reasons: List<String>,
            extra: String = "",
        ) {
            (findings + evidence).forEach { item ->
                seenTypes += item.getValue("type").jsonPrimitive.content
                item.keys.forEach { seenKeys += "${item.getValue("type").jsonPrimitive.content}.$it" }
            }
            val bytes =
                canonicalJson(
                    JsonObject(
                        mapOf(
                            "verdict" to JsonPrimitive(verdict.toString()),
                            "reasons" to JsonArray(reasons.map(::JsonPrimitive)),
                            "findings" to JsonArray(findings),
                            "evidence" to JsonArray(evidence),
                            "extra" to JsonPrimitive(extra),
                        ),
                    ),
                )
            groups.getOrPut(group) { StringBuilder() }.append(sha256Hex(bytes)).append('\n')
        }

        // evaluatePolicy over policies x metrics x validity x diagnostics x window x includeMetricEvidence
        policies.forEachIndexed { policyIndex, policy ->
            metricVariants.forEachIndexed { metricIndex, metrics ->
                for (validity in RunValidity.entries) {
                    for (diagnostics in diagnosticVariants) {
                        for (windowId in listOf(null, "steady", "w-é\u0001")) {
                            for (include in listOf(true, false)) {
                                val evaluation = evaluatePolicy(policy, validity, metrics, diagnostics, windowId, include)
                                record(
                                    "policy.p$policyIndex.m$metricIndex",
                                    evaluation.findings,
                                    evaluation.evidence,
                                    evaluation.verdict,
                                    evaluation.coverageReasons,
                                )
                            }
                        }
                    }
                }
            }
        }
        evaluatePolicy(null, RunValidity.VALID, metricVariants[2]).let { record("policy.none", it.findings, it.evidence, it.verdict, it.coverageReasons) }

        // evaluateResources and evaluateSharedWindowPolicy over snapshots x policies
        resourceCases().forEachIndexed { index, case ->
            val resource = evaluateResources(case.snapshot, case.snapshot.windows, platform = case.platform)
            record("resource.c$index", resource.findings, resource.evidence, resource.windowVerdicts, resource.coverageReasons)
            sharedPolicies.forEachIndexed { policyIndex, policy ->
                for (validity in listOf(RunValidity.VALID, RunValidity.DEGRADED)) {
                    for (metricIndex in listOf(0, 1, 2)) {
                        val metrics = checkNotNull(metricVariants[metricIndex])
                        val shared =
                            evaluateSharedWindowPolicy(
                                policy,
                                validity,
                                metrics,
                                case.snapshot.windows.associate { it.id to checkNotNull(metricVariants[(metricIndex + it.id.length) % 3]) },
                                resource,
                                case.snapshot.windows,
                                diagnosticVariants[2],
                            )
                        record(
                            "shared.c$index.p$policyIndex.m$metricIndex",
                            shared.findings,
                            shared.evidence,
                            shared.verdict,
                            shared.coverageReasons,
                        )
                    }
                }
            }
        }

        val expectedTypes =
            setOf(
                "diagnostic",
                "metric_summary",
                "policy_check",
                "policy_failure",
                "resource_policy_check",
                "resource_summary",
                "resource_threshold_violation",
                "rule_window_check",
                "window_policy_summary",
            )
        assertTrue(seenTypes.containsAll(expectedTypes), "snapshot does not reach every type: ${expectedTypes - seenTypes}")
        listOf(
            "policy_check.window_id",
            "policy_check.scope",
            "policy_check.observed",
            "policy_check.reason_code",
            "policy_check.min_samples",
            "policy_check.sample_floor",
            "policy_check.metric_evidence_id",
            "metric_summary.window_id",
            "diagnostic.source_offset",
            "resource_policy_check.platform_rule_id",
            "resource_policy_check.service",
            "resource_policy_check.expected_cells",
            "resource_threshold_violation.presumed",
            "window_policy_summary.min_samples",
        ).forEach { key -> assertTrue(key in seenKeys, "snapshot does not reach optional field $key") }

        val actual = groups.entries.joinToString("") { (group, hashes) -> "$group ${sha256Hex(hashes.toString().encodeToByteArray())}\n" }
        if (System.getenv("LTV_UPDATE_TYPED_EVIDENCE") == "1") {
            Files.createDirectories(snapshotFile.parent)
            Files.writeString(snapshotFile, actual)
        }
        assertEquals(Files.readString(snapshotFile).replace("\r\n", "\n"), actual)
    }

    private fun policy(json: String): PolicyV1 {
        val validation = validatePolicy(ByteArrayInputStream(json.encodeToByteArray()))
        check(validation is PolicyValidation.Valid) { "policy is invalid: $validation in $json" }
        return validation.policy
    }

    private fun fixturePolicy(path: String) = policy(Files.readString(Path.of(path)))

    private val everything =
        """{"schema_version":"policy.v1","policy_id":"everything","defaults":{"sample_floor":2,"min_samples":50,"missing_transaction":"warn"},"rules":[
        {"id":"p95-overall","metric":"response_time_p95_ms","operator":"lte","threshold":12345678901234567890.5,"scope":{"kind":"overall"}},
        {"id":"p99-overall","metric":"response_time_p99_ms","operator":"lte","threshold":0.10,"scope":{"kind":"overall"},"min_samples":2},
        {"id":"err","metric":"error_rate_ratio","operator":"lte","threshold":1e-3,"scope":{"kind":"overall"}},
        {"id":"rps","metric":"throughput_rps","operator":"gte","threshold":1.5E+2,"scope":{"kind":"overall"}},
        {"id":"checkout","metric":"response_time_p95_ms","operator":"lte","threshold":800,"scope":{"kind":"transaction","name":"POST /checkout"}},
        {"id":"orders-err","metric":"error_rate_ratio","operator":"lte","threshold":0.05,"scope":{"kind":"transaction","name":"POST /orders"},"min_samples":3},
        {"id":"missing","metric":"throughput_rps","operator":"gte","threshold":1,"scope":{"kind":"transaction","name":"GET /missing"}},
        {"id":"ambiguous","metric":"response_time_p99_ms","operator":"lte","threshold":2000,"scope":{"kind":"transaction","name":"DUP"}}]}"""

    private val policies: List<PolicyV1> =
        listOf(
            fixturePolicy("fixtures/slice1/policies/pass.json"),
            fixturePolicy("fixtures/slice1/policies/fail.json"),
            fixturePolicy("fixtures/slice1/policies/missing-transaction.json"),
            fixturePolicy("docs/contracts/policy/v1/examples/valid/all-metrics.json"),
            fixturePolicy("docs/contracts/policy/v1/examples/valid/sample-gate.json"),
            fixturePolicy("docs/contracts/policy/v1/examples/valid/missing-transaction-warn.json"),
            fixturePolicy("docs/contracts/policy/v1/examples/valid/window-ids.json"),
            fixturePolicy("docs/contracts/policy/v1/examples/valid/platform-base-profile.json"),
            policy(everything),
        )

    private val sharedPolicies: List<PolicyV1?> =
        listOf(
            null,
            fixturePolicy("docs/contracts/policy/v1/examples/valid/window-ids.json"),
            policies.last(),
            policy(everything.replace("\"missing_transaction\":\"warn\"", "\"missing_transaction\":\"no_verdict\"")),
        )

    private fun summary(
        samples: Long,
        errors: Long,
        p95: Long,
        throughputNumerator: Long = samples * 1000,
        errorRate: Boolean = true,
    ) = MetricSummary(
        samples,
        errors,
        if (errorRate && samples > 0) ExactRatio(errors, samples) else null,
        ExactRatio(throughputNumerator, 1040),
        LatencySummary(p95 / 2, p95, p95 + p95 / 10, p95 * 2),
    )

    private fun transaction(
        label: String,
        summary: MetricSummary,
        groupPath: List<String> = emptyList(),
        kind: SampleKind = SampleKind.JMETER_SAMPLER,
    ) = TransactionSummary(TransactionIdentity(groupPath, label, kind), summary)

    private fun metrics(
        overall: MetricSummary,
        transactions: List<TransactionSummary> = emptyList(),
    ) = NormalizedMetrics(overall, transactions, emptyList(), emptyMap())

    // The index 0..2 are used by the shared-window matrix; keep their order.
    private val metricVariants: List<NormalizedMetrics?> =
        listOf(
            metrics(MetricSummary(0, 0, null, ExactRatio(0, 1), LatencySummary(0, 0, 0, 0))),
            metrics(summary(5, 1, 40), listOf(transaction("POST /checkout", summary(5, 1, 40)))),
            metrics(
                summary(500, 7, 250),
                listOf(
                    transaction("POST /checkout", summary(300, 3, 900), listOf("Group A", "é")),
                    transaction("POST /orders", summary(200, 4, 120), kind = SampleKind.GATLING_REQUEST),
                    transaction("DUP", summary(10, 0, 5), listOf("one")),
                    transaction("DUP", summary(11, 1, 6), listOf("two"), SampleKind.JMETER_CONTAINER),
                    transaction("GET /zero", summary(0, 0, 0)),
                ),
            ),
            metrics(
                summary(Long.MAX_VALUE, Long.MAX_VALUE / 2, Long.MAX_VALUE / 4, Long.MAX_VALUE),
                listOf(transaction("POST /orders", summary(1, 0, 1, errorRate = false))),
            ),
            metrics(summary(40, 0, 100, errorRate = false), listOf(transaction("POST /orders", summary(40, 40, 100)))),
            null,
        )

    private val diagnosticVariants: List<List<Diagnostic>> =
        listOf(
            emptyList(),
            listOf(Diagnostic("CSV_ROW_SKIPPED", "row skipped")),
            listOf(
                Diagnostic("TIMESTAMP_RANGE", "late \"é\" \u0001", 4_096),
                Diagnostic("CSV_ROW_SKIPPED", "row skipped", 0),
                Diagnostic("CSV_ROW_SKIPPED", "row skipped", Long.MAX_VALUE),
            ),
        )

    private data class ResourceCase(
        val snapshot: ResourceSnapshotV1,
        val platform: PlatformExpansion,
    )

    private fun series(
        id: String,
        metric: String,
        unit: String,
        entity: String,
        values: List<String?>,
        aggregation: ResourceAggregation = ResourceAggregation.INTERVAL_MEAN,
        role: ResourceRole = ResourceRole.SYSTEM,
    ) = ResourceSeriesV1(id, metric, unit, entity, role, aggregation, emptyMap(), values.map { it?.let(::BigDecimal) })

    private fun snapshot(
        series: List<ResourceSeriesV1>,
        windows: List<ResourceWindowV1>,
        rules: List<ResourceRuleV1> = emptyList(),
        cells: Int = 8,
    ) = ResourceSnapshotV1("resource-snapshot.v1", "0".repeat(64), 1_767_225_600_000, 10_000, cells, series, windows, rules, null)

    private fun resourceCases(): List<ResourceCase> {
        val start = 1_767_225_600_000
        val windows =
            listOf(
                ResourceWindowV1("steady", start + 20_000, start + 60_000),
                ResourceWindowV1("all", start, start + 80_000),
                ResourceWindowV1("steady-1", start + 10_000, start + 30_000),
            )
        val patterns =
            listOf(
                List(8) { "0.1" },
                listOf("0.1", "0.2", "0.95", "0.97", "0.99", "0.2", "0.1", "0.1"),
                listOf("0.1", null, "0.95", "0.97", null, "0.2", "0.1", "0.1"),
                listOf(null, null, null, null, null, null, null, null),
                listOf("0.9", "0.95", null, "0.96", "0.97", "0.98", null, "0.99"),
                listOf("12345678901234567890.123456789", "1E+5", "-0.5000", "0", "0.10", "3", "4", "5"),
            )
        val rules =
            listOf(
                ResourceRuleV1("cpu-high", "cpu", "ratio", ResourceOperator.GT, BigDecimal("0.9"), 2, ResourceRuleEffect.SLA),
                ResourceRuleV1("cpu-low", "cpu", "ratio", ResourceOperator.LT, BigDecimal("0.15"), 1, ResourceRuleEffect.DIAGNOSTIC),
                ResourceRuleV1(
                    "cpu-windowed",
                    "cpu",
                    "ratio",
                    ResourceOperator.GT,
                    BigDecimal("0.5"),
                    1,
                    ResourceRuleEffect.SLA,
                    windowIds = listOf("steady", "unknown-window"),
                ),
                ResourceRuleV1("no-series", "absent", "ratio", ResourceOperator.GT, BigDecimal("1E+3"), 1, ResourceRuleEffect.SLA),
                ResourceRuleV1("wide", "cpu", "ratio", ResourceOperator.GT, BigDecimal("12345678901234567890.123456789"), 1, ResourceRuleEffect.SLA),
            )
        val plain =
            patterns.map { pattern ->
                ResourceCase(
                    snapshot(
                        listOf(
                            series("cpu", "cpu_used", "ratio", "vm", pattern),
                            series("mem", "mem_used", "bytes", "vm", pattern.reversed(), ResourceAggregation.INTERVAL_MAX),
                            series("gen", "cpu_used", "ratio", "gen", List(8) { "0.3" }, role = ResourceRole.GENERATOR),
                        ),
                        windows,
                        rules,
                    ),
                    PlatformExpansion.EMPTY,
                )
            }
        val platformPolicy =
            policy(
                """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
                    """"platform_services":["orders","payments"],"platform_coverage":{"signal":"unavailable"},""" +
                    """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"}}],""" +
                    """"platform_rules":[""" +
                    """{"id":"cpu","signal":"cpu_ratio","scope":{"kind":"all_services"},"operator":"gt","threshold":0.4,"unit":"ratio",""" +
                    """"aggregation":"interval_mean","min_consecutive_cells":3,"effect":"sla","max_missing_fraction":0.2,"max_gap_cells":1},""" +
                    """{"id":"cover","signal":"unavailable","scope":{"kind":"all_services"},"operator":"gt","threshold":0,"unit":"count",""" +
                    """"aggregation":"interval_max","min_consecutive_cells":1,"effect":"sla"},""" +
                    """{"id":"diag","signal":"cpu_ratio","scope":{"kind":"service","services":["orders"]},"operator":"gt","threshold":0.7,""" +
                    """"unit":"ratio","aggregation":"interval_mean","min_consecutive_cells":1,"effect":"diagnostic","window_ids":["steady"]}]}""",
            )
        val platformPatterns =
            listOf(
                listOf("0.5", "0.5", "0.5", "0.1", "0.1", "0.1", "0.1", "0.1"),
                listOf("0.5", "0.5", null, "0.5", "0.1", "0.1", "0.1", "0.1"),
                listOf("0.5", "0.5", "0.5", null, "0.1", "0.1", "0.1", "0.1"),
                listOf("0.5", "0.5", "0.5", null, "0.5", "0.1", "0.1", "0.1"),
                listOf(null, null, null, null, null, null, null, null),
                listOf("0.1", "0.1", null, "0.1", "0.1", "0.1", "0.1", "0.1"),
            )
        val platform =
            platformPatterns.map { pattern ->
                val snapshot =
                    snapshot(
                        listOf(
                            series("cpu-orders", "cpu_ratio", "ratio", "orders", pattern),
                            series("cpu-payments", "cpu_ratio", "ratio", "payments", List(8) { "0.1" }),
                            series("unavailable-orders", "unavailable", "count", "orders", List(8) { "0" }, ResourceAggregation.INTERVAL_MAX),
                            series("cpu-stray", "cpu_ratio", "ratio", "stray", List(8) { "0.9" }),
                        ),
                        windows,
                    )
                ResourceCase(snapshot, expandPlatformRules(platformPolicy, snapshot))
            }
        return plain + platform
    }
}

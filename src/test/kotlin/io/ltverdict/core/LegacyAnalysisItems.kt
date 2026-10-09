package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.TransactionIdentity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

// Frozen copy of the findings and evidence builders as they were hand-written before W2.1 slice 2 (origin/main @ a6eb81a).
// AnalysisItemsEquivalenceTest compares the typed builders with these; do not "improve" this file. It uses only literals and
// public helpers, none of the code it is compared with.

private fun legacyStableId(
    prefix: String,
    key: String,
) = "$prefix-${sha256Hex(key.encodeToByteArray())}"

private fun legacyDiagnosticKey(diagnostic: Diagnostic) = "${diagnostic.code}\u0000${diagnostic.sourceOffset ?: ""}"

private fun legacyDiagnosticId(diagnostic: Diagnostic) = legacyStableId("diagnostic", legacyDiagnosticKey(diagnostic))

internal fun legacyRatioJson(value: ExactRatio): JsonObject =
    buildJsonObject {
        put("numerator", value.numerator)
        put("denominator", value.denominator)
    }

internal fun legacyPolicyScopeJson(scope: PolicyScope): JsonObject =
    buildJsonObject {
        when (scope) {
            PolicyScope.Overall -> put("kind", "overall")
            is PolicyScope.Transaction -> {
                put("kind", "transaction")
                put("label", scope.name)
            }
        }
    }

internal fun legacyPolicyCheck(
    rule: PolicyRuleV1,
    metric: MetricEvidence?,
    observed: JsonElement?,
    reason: String?,
    windowId: String?,
    includeMetricReference: Boolean,
    gate: SampleGate?,
): JsonObject =
    buildJsonObject {
        put("id", windowId?.let { legacyStableId("policy-check-window", "$it\u0000${rule.id}") } ?: legacyStableId("policy-check", rule.id))
        put("type", "policy_check")
        windowId?.let {
            put("window_id", it)
            put("scope", metric?.json?.getValue("scope") ?: legacyPolicyScopeJson(rule.scope))
        }
        put("rule_id", rule.id)
        put("metric", rule.metric.wireName)
        put("operator", rule.operator.wireName)
        put("threshold", JsonPrimitive(rule.threshold))
        put(
            "status",
            when {
                reason == "POLICY_FAILED" -> "FAIL"
                observed != null -> "PASS"
                else -> "NO_VERDICT"
            },
        )
        if (metric != null && includeMetricReference) put("metric_evidence_id", metric.id)
        if (observed != null) put("observed", observed)
        if (reason != null && reason != "POLICY_FAILED") put("reason_code", reason)
        gate?.mode?.let { mode ->
            put("sample_count", gate.sampleCount)
            if (mode != SampleMode.NOT_GATED) {
                put("sample_floor", gate.floor)
                put("min_samples", gate.minSamples)
            }
            put("sample_mode", mode.name)
        }
    }

internal fun legacyMetricSummary(
    id: String,
    identity: TransactionIdentity?,
    summary: MetricSummary,
    windowId: String?,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "metric_summary")
        windowId?.let { put("window_id", it) }
        put(
            "scope",
            if (identity == null) {
                buildJsonObject { put("kind", "overall") }
            } else {
                buildJsonObject {
                    put("kind", "transaction")
                    put("group_path", buildJsonArray { identity.groupPath.forEach { add(JsonPrimitive(it)) } })
                    put("label", identity.label)
                    put("sample_kind", identity.kind.name)
                }
            },
        )
        put("sample_count", summary.sampleCount)
        put("error_count", summary.errorCount)
        put("error_rate_ratio", summary.errorRate?.let(::legacyRatioJson) ?: JsonNull)
        put("throughput_rps", legacyRatioJson(summary.throughputRps))
        put(
            "latency_ms",
            buildJsonObject {
                put("p50", summary.latency.p50Millis)
                put("p95", summary.latency.p95Millis)
                put("p99", summary.latency.p99Millis)
                put("max", summary.latency.maxMillis)
            },
        )
    }

internal fun legacyDiagnosticEvidence(diagnostic: Diagnostic): JsonObject {
    val id = legacyDiagnosticId(diagnostic)
    return buildJsonObject {
        put("id", id)
        put("type", "diagnostic")
        put("code", diagnostic.code)
        put("message", diagnostic.message)
        diagnostic.sourceOffset?.let { put("source_offset", it) }
    }
}

internal fun legacyDiagnosticFinding(diagnostic: Diagnostic): JsonObject =
    buildJsonObject {
        put("id", legacyStableId("diagnostic-finding", legacyDiagnosticKey(diagnostic)))
        put("type", "diagnostic")
        put("code", diagnostic.code)
        put("evidence_id", legacyDiagnosticId(diagnostic))
    }

internal fun legacyPolicyFailure(
    rule: PolicyRuleV1,
    evidenceId: String,
    windowId: String?,
): JsonObject =
    buildJsonObject {
        put(
            "id",
            windowId?.let { legacyStableId("policy-failure-window", "$it\u0000${rule.id}") } ?: legacyStableId("policy-failure", rule.id),
        )
        put("type", "policy_failure")
        windowId?.let { put("window_id", it) }
        put("rule_id", rule.id)
        put("evidence_id", evidenceId)
    }

internal fun legacyRuleWindowCheck(
    ruleId: String,
    windowId: String,
): JsonObject =
    buildJsonObject {
        put("id", "rule-window-check-${sha256Hex("$ruleId\u0000$windowId".encodeToByteArray())}")
        put("type", "rule_window_check")
        put("rule_id", ruleId)
        put("window_id", windowId)
        put("status", "NO_VERDICT")
        put("reason_code", "RULE_WINDOW_NOT_FOUND")
    }

internal fun legacyWindowPolicySummary(
    window: ResourceWindowV1,
    business: PolicyVerdict,
    resource: PolicyVerdict,
    verdict: PolicyVerdict,
    sampleCount: Long,
    minSamples: Long?,
): JsonObject =
    buildJsonObject {
        put("id", "window-policy-summary-${sha256Hex(window.id.encodeToByteArray())}")
        put("type", "window_policy_summary")
        put("window_id", window.id)
        put("from_epoch_ms", window.fromEpochMillis)
        put("to_epoch_ms", window.toEpochMillis)
        put("business_verdict", business.name)
        put("resource_verdict", resource.name)
        put("verdict", verdict.name)
        put("sample_count", sampleCount)
        minSamples?.let { put("min_samples", it) }
    }

internal fun legacyResourceSummary(
    snapshot: ResourceSnapshotV1,
    series: ResourceSeriesV1,
    window: ResourceWindowV1,
    checkCancelled: () -> Unit,
): JsonObject {
    val fromIndex = snapshot.cellIndex(window.fromEpochMillis)
    val toIndex = snapshot.cellIndex(window.toEpochMillis)
    val observed = mutableListOf<IndexedValue>()
    var currentGap = 0
    var longestGap = 0
    for (index in fromIndex until toIndex) {
        checkCancelled()
        val value = series.values[index]
        if (value == null) {
            currentGap++
            longestGap = maxOf(longestGap, currentGap)
        } else {
            currentGap = 0
            observed += IndexedValue(index - fromIndex, value)
        }
    }
    val expected = toIndex - fromIndex
    val statistics = statistics(observed, expected, snapshot.stepMillis, checkCancelled)
    val reasons =
        buildList {
            if (observed.size < expected) add("RESOURCE_GAPS")
            if (observed.isEmpty()) {
                add("NO_OBSERVATIONS")
            } else if (observed.size < 2 || statistics.splitHalfShift == null) {
                add("INSUFFICIENT_OBSERVATIONS")
            }
        }
    return buildJsonObject {
        put("id", resourceId("resource-summary", window.id, series.id))
        put("type", "resource_summary")
        put("series_id", series.id)
        put("metric", series.metric)
        put("unit", series.unit)
        put("entity", series.entity)
        put("role", series.role.wireName)
        put("aggregation", series.aggregation.wireName)
        put("window_id", window.id)
        put("from_epoch_ms", window.fromEpochMillis)
        put("to_epoch_ms", window.toEpochMillis)
        put("expected_cells", expected)
        put("observed_cells", observed.size)
        put("missing_cells", expected - observed.size)
        put("longest_gap_cells", longestGap)
        put("statistics", legacyStatisticsJson(statistics))
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun legacyResourceCheck(
    id: String,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    status: String,
    reason: String?,
    cells: CellStats?,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "resource_policy_check")
        put("window_id", window.id)
        put("rule_id", rule.id)
        put("series_id", rule.seriesId)
        put("unit", rule.unit)
        put("operator", rule.operator.wireName)
        put("threshold", canonicalDecimal(rule.threshold))
        put("effect", rule.effect.wireName)
        put("status", status)
        put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        rule.platform?.let {
            put("platform_rule_id", it.ruleId)
            put("service", it.service)
        }
        cells?.let {
            put("expected_cells", it.expected)
            put("observed_cells", it.observed)
            put("missing_cells", it.missing)
            put("longest_gap_cells", it.longestGap)
        }
    }

internal fun legacyThresholdFinding(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    series: ResourceSeriesV1,
    rule: ResourceRuleV1,
    evidenceId: String,
    fromIndex: Int,
    toIndex: Int,
    observedMin: BigDecimal,
    observedMax: BigDecimal,
    presumed: Boolean = false,
): JsonObject {
    val from = snapshot.cellStart(fromIndex)
    val to = snapshot.cellStart(toIndex)
    return buildJsonObject {
        put("id", resourceId("resource-threshold-finding", window.id, rule.id, from.toString()))
        put("type", "resource_threshold_violation")
        put("window_id", window.id)
        put("rule_id", rule.id)
        put("series_id", series.id)
        put("entity", series.entity)
        put("unit", series.unit)
        put("from_epoch_ms", from)
        put("to_epoch_ms", to)
        put("cell_count", toIndex - fromIndex)
        put("observed_min", canonicalDecimal(observedMin))
        put("observed_max", canonicalDecimal(observedMax))
        if (presumed) put("presumed", true)
        put("evidence_id", evidenceId)
    }
}

private fun legacyStatisticsJson(statistics: Statistics): JsonObject =
    buildJsonObject {
        legacyPutDecimal("min", statistics.min)
        legacyPutDecimal("max", statistics.max)
        legacyPutDecimal("mean", statistics.mean)
        legacyPutDecimal("median", statistics.median)
        legacyPutDecimal("q05", statistics.q05)
        legacyPutDecimal("q25", statistics.q25)
        legacyPutDecimal("q75", statistics.q75)
        legacyPutDecimal("q95", statistics.q95)
        legacyPutDecimal("iqr", statistics.iqr)
        legacyPutDecimal("mad", statistics.mad)
        legacyPutDecimal("sample_standard_deviation", statistics.sampleStandardDeviation)
        legacyPutDecimal("slope_per_second", statistics.slopePerSecond)
        legacyPutDecimal("split_half_shift", statistics.splitHalfShift)
    }

private fun JsonObjectBuilder.legacyPutDecimal(
    name: String,
    value: BigDecimal?,
) {
    put(name, value?.let { JsonPrimitive(canonicalDecimal(it)) } ?: JsonNull)
}

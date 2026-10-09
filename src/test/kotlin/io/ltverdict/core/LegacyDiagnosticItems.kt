package io.ltverdict.core

// FROZEN COPY of the finding and evidence builders of DiagnosticAnalysis.kt as they were on origin/main before W2.1 slice 2b
// typed them. It is the oracle of DiagnosticCapacityTrendEquivalenceTest: do not "fix" or modernise it. The builders that
// the main source set can call are the entry points (prefix legacy); the private helpers keep their names.

import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

internal fun legacyAnomalyCheck(
    anomaly: DiagnosticAnomalyV1,
    id: String,
    reference: ResourceWindowV1,
    evaluation: ResourceWindowV1,
    status: String,
    median: BigDecimal?,
    mad: BigDecimal?,
    referenceObserved: Int,
    referenceExpected: Int,
    observed: Int,
    expected: Int,
    episodes: Int,
    suppressed: Int,
    reasons: List<String>,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "anomaly_check")
        put("rule_id", anomaly.id)
        put("window_id", evaluation.id)
        put("reference_window_id", reference.id)
        put("status", status)
        putBigDecimal("reference_median", median)
        putBigDecimal("reference_mad", mad)
        put("reference_observed_cells", referenceObserved)
        put("reference_expected_cells", referenceExpected)
        put("observed_cells", observed)
        put("expected_cells", expected)
        put("episodes_reported", episodes)
        put("suppressed_short_episodes", suppressed)
        put("reasons", strings(reasons.distinct()))
    }

internal fun legacyWindowMetricSummary(
    window: ResourceWindowV1,
    metrics: NormalizedMetrics,
    resources: List<ResourceSeriesV1>,
): JsonObject =
    buildJsonObject {
        put("id", "window-metric-summary-${sha256Hex(window.id.encodeToByteArray())}")
        put("type", "window_metric_summary")
        put("window_id", window.id)
        put("from_epoch_ms", window.fromEpochMillis)
        put("to_epoch_ms", window.toEpochMillis)
        put("sample_count", metrics.overall.sampleCount)
        put("error_count", metrics.overall.errorCount)
        put("error_rate_ratio", metrics.overall.errorRate?.json() ?: JsonNull)
        put("throughput_rps", metrics.overall.throughputRps.json())
        val hasSamples = metrics.overall.sampleCount > 0L
        put(
            "latency_ms",
            buildJsonObject {
                put("p50", if (hasSamples) JsonPrimitive(metrics.overall.latency.p50Millis) else JsonNull)
                put("p95", if (hasSamples) JsonPrimitive(metrics.overall.latency.p95Millis) else JsonNull)
                put("p99", if (hasSamples) JsonPrimitive(metrics.overall.latency.p99Millis) else JsonNull)
                put("max", if (hasSamples) JsonPrimitive(metrics.overall.latency.maxMillis) else JsonNull)
            },
        )
        put(
            "resource_bindings",
            buildJsonArray {
                resources.sortedBy(ResourceSeriesV1::id).forEach { resource ->
                    add(
                        buildJsonObject {
                            put("series_id", resource.id)
                            put("metric", resource.metric)
                            put("unit", resource.unit)
                            put("entity", resource.entity)
                            put("role", resource.role.wireName)
                            put("aggregation", resource.aggregation.wireName)
                            put("labels", buildJsonObject { resource.labels.forEach { (key, value) -> put(key, value) } })
                        },
                    )
                }
            },
        )
    }

private fun ExactRatio.json(): JsonObject =
    buildJsonObject {
        put("numerator", numerator)
        put("denominator", denominator)
    }

internal fun legacyDiagnosticSummary(
    status: String,
    pairsTested: Int,
    pairsEvaluable: Int,
    anomaliesTested: Int,
    episodesReported: Int,
    suppressedShortEpisodes: Int,
    reasons: List<String>,
    idSalt: String = "v1",
): JsonObject =
    buildJsonObject {
        put("id", diagnosticId("diagnostic-summary", idSalt))
        put("type", "diagnostic_summary")
        put("status", status)
        put("pairs_tested", pairsTested)
        put("pairs_evaluable", pairsEvaluable)
        put("anomalies_tested", anomaliesTested)
        put("episodes_reported", episodesReported)
        put("suppressed_short_episodes", suppressedShortEpisodes)
        put("uncertainty", "NOT_ESTIMATED")
        put("reasons", strings(reasons))
    }

internal fun CorrelationHeadlineSelection.legacyEvidence(
    sourceCells: Int,
    analysedPoints: Int,
): JsonObject =
    buildJsonObject {
        put("id", diagnosticId("correlation-headline-selection", pairId, windowId))
        put("type", "correlation_headline_selection")
        put("pair_id", pairId)
        put("window_id", windowId)
        put("method", CORRELATION_HEADLINE_METHOD)
        put("rng", CORRELATION_HEADLINE_RNG)
        put("status", status.name)
        put("family_hypotheses", familyHypotheses)
        put("family_count", familyCount)
        put("representation", CORRELATION_HEADLINE_REPRESENTATION)
        put("source_cells", sourceCells)
        put("analysed_points", analysedPoints)
        put("bootstrap_replicates", CORRELATION_HEADLINE_REPLICATES)
        put(
            "block_lengths_cells",
            buildJsonArray { CORRELATION_HEADLINE_BLOCKS.forEach { add(JsonPrimitive(it)) } },
        )
        put("alpha", decimalString(alpha))
        putDecimal("p_value_b10", pValueBlock10)
        putDecimal("p_value_b20", pValueBlock20)
        putDecimal("max_p_value", maxPValue)
        putDecimal("holm_adjusted_p_value", holmAdjustedPValue)
        put("selected", selected)
        put("reasons", strings(reasons))
    }

private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

private fun kotlinx.serialization.json.JsonObjectBuilder.putDecimal(
    name: String,
    value: Double?,
) {
    put(name, value?.let { JsonPrimitive(decimalString(it)) } ?: JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putBigDecimal(
    name: String,
    value: BigDecimal?,
) {
    put(name, value?.let { JsonPrimitive(canonicalDecimal(it)) } ?: JsonNull)
}

private fun decimalString(value: Double): String =
    canonicalDecimal(BigDecimal.valueOf(value).setScale(DECIMAL_SCALE, RoundingMode.HALF_EVEN))

private fun diagnosticId(
    type: String,
    vararg parts: String,
): String = "$type-${sha256Hex(parts.joinToString("\u0000").encodeToByteArray())}"

private const val DECIMAL_SCALE = 12

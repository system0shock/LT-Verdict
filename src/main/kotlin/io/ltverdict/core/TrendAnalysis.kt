package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext

internal data class TrendAnalysis(
    val trendJson: JsonObject,
    val evidence: List<JsonObject>,
    val findings: List<JsonObject>,
)

internal fun evaluateTrend(
    plan: TrendPlanV1,
    snapshot: ResourceSnapshotV1,
    windows: List<ResourceWindowV1>,
    checkCancelled: () -> Unit = {},
): TrendAnalysis {
    val seriesById = snapshot.series.associateBy { it.id }
    val windowById = windows.associateBy { it.id }
    val results =
        plan.checks.map { check ->
            evaluateTrendCheck(check, snapshot, seriesById[check.seriesId], windowById[check.windowId], checkCancelled)
        }
    return TrendAnalysis(
        trendJson(results),
        results.map(TrendCheckResult::evidence) + trendSummary(results),
        results.mapNotNull(TrendCheckResult::finding),
    )
}

internal fun trendUnavailable(
    plan: TrendPlanV1,
    reason: String,
): TrendAnalysis {
    val results = plan.checks.map { check -> abstain(check, null, null, "UNAVAILABLE", listOf(reason), TrendFacts()) }
    return TrendAnalysis(
        trendJson(results),
        results.map(TrendCheckResult::evidence) + trendSummary(results),
        emptyList(),
    )
}

private data class TrendFacts(
    val median: BigDecimal? = null,
    val slope: BigDecimal? = null,
    val shift: BigDecimal? = null,
    val requiredShiftUnits: BigDecimal? = null,
    val expectedCells: Int = 0,
    val observedCells: Int = 0,
    val longestGapCells: Int = 0,
)

private data class TrendCheckResult(
    val check: TrendCheckV1,
    val status: String,
    val observedDirection: String?,
    val facts: TrendFacts,
    val reasons: List<String>,
    val evidence: JsonObject,
    val finding: JsonObject?,
)

private fun evaluateTrendCheck(
    check: TrendCheckV1,
    snapshot: ResourceSnapshotV1,
    series: ResourceSeriesV1?,
    window: ResourceWindowV1?,
    checkCancelled: () -> Unit,
): TrendCheckResult {
    val reasons = mutableListOf<String>()
    if (series == null) reasons += "TREND_SERIES_NOT_FOUND"
    if (window == null) reasons += "TREND_WINDOW_NOT_FOUND"
    if (series == null || window == null) return abstain(check, series, window, "UNAVAILABLE", reasons, TrendFacts())

    val fromIndex = snapshot.cellIndex(window.fromEpochMillis)
    val toIndex = snapshot.cellIndex(window.toEpochMillis)
    val expectedCells = toIndex - fromIndex
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
    val partial = TrendFacts(expectedCells = expectedCells, observedCells = observed.size, longestGapCells = longestGap)
    if (observed.size < expectedCells) reasons += "RESOURCE_GAPS"
    if (observed.isEmpty()) {
        return abstain(check, series, window, "INSUFFICIENT_CELLS", reasons + "NO_OBSERVATIONS", partial)
    }
    if (observed.size < check.minCells) {
        return abstain(check, series, window, "INSUFFICIENT_CELLS", reasons + "TREND_MIN_CELLS_NOT_MET", partial)
    }

    val statistics = statistics(observed, expectedCells, snapshot.stepMillis, checkCancelled)
    val slope = statistics.slopePerSecond
    val shift = statistics.splitHalfShift
    val median = statistics.median
    val measured = partial.copy(median = median, slope = slope, shift = shift)
    if (slope == null || shift == null) {
        return abstain(check, series, window, "INSUFFICIENT_CELLS", reasons + "INSUFFICIENT_OBSERVATIONS", measured)
    }
    if (median == null || median.signum() == 0) {
        return abstain(check, series, window, "UNAVAILABLE", reasons + "TREND_MEDIAN_ZERO", measured)
    }

    val required = median.abs().multiply(check.magnitudeGate.minSplitHalfShiftPct).divide(HUNDRED, DECIMAL_CONTEXT)
    val facts = measured.copy(requiredShiftUnits = required)
    val wanted =
        when (check.direction) {
            TrendDirection.INCREASE -> 1
            TrendDirection.DECREASE -> -1
            TrendDirection.EITHER -> slope.signum()
        }
    val evaluated = reasons.toMutableList()
    if (slope.signum() != wanted) evaluated += "TREND_DIRECTION_MISMATCH"
    if (shift.signum() != slope.signum()) evaluated += "TREND_DIRECTION_DISAGREEMENT"
    if (slope.abs() < check.magnitudeGate.minSlopeUnitsPerSecond) evaluated += "TREND_SLOPE_BELOW_MINIMUM"
    if (shift.abs() < required) evaluated += "TREND_SHIFT_BELOW_MINIMUM"
    if (evaluated.size != reasons.size) return abstain(check, series, window, "NO_MATERIAL_TREND", evaluated, facts)

    evaluated += "STATIONARITY_NOT_EVALUATED"
    val direction = directionOf(slope)
    val evidenceId = resourceId("trend-check", check.id)
    return TrendCheckResult(
        check,
        "TREND_OBSERVED",
        direction,
        facts,
        evaluated,
        evidence(check, series, window, "TREND_OBSERVED", evaluated, facts, direction, evidenceId),
        finding(check, series, window, snapshot, fromIndex, toIndex, direction, facts, evidenceId),
    )
}

private fun abstain(
    check: TrendCheckV1,
    series: ResourceSeriesV1?,
    window: ResourceWindowV1?,
    status: String,
    reasons: List<String>,
    facts: TrendFacts,
): TrendCheckResult {
    val direction = facts.slope?.let(::directionOf)
    val evidenceId = resourceId("trend-check", check.id)
    val evidence = evidence(check, series, window, status, reasons, facts, direction, evidenceId)
    return TrendCheckResult(check, status, direction, facts, reasons, evidence, null)
}

private fun evidence(
    check: TrendCheckV1,
    series: ResourceSeriesV1?,
    window: ResourceWindowV1?,
    status: String,
    reasons: List<String>,
    facts: TrendFacts,
    direction: String?,
    evidenceId: String,
): JsonObject =
    buildJsonObject {
        put("id", evidenceId)
        put("type", "trend_check")
        put("check_id", check.id)
        put("series_id", check.seriesId)
        put("metric", series?.metric?.let(::JsonPrimitive) ?: JsonNull)
        put("unit", series?.unit?.let(::JsonPrimitive) ?: JsonNull)
        put("entity", series?.entity?.let(::JsonPrimitive) ?: JsonNull)
        put("window_id", check.windowId)
        put("window_from_epoch_ms", window?.fromEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("window_to_epoch_ms", window?.toEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("declared_direction", check.direction.wireName)
        put("status", status)
        put("min_cells", check.minCells)
        put("expected_cells", facts.expectedCells)
        put("observed_cells", facts.observedCells)
        put("missing_cells", facts.expectedCells - facts.observedCells)
        put("longest_gap_cells", facts.longestGapCells)
        putDecimal("median", facts.median)
        putDecimal("slope_per_second", facts.slope)
        putDecimal("split_half_shift", facts.shift)
        put(
            "magnitude_gate",
            buildJsonObject {
                put("min_slope_units_per_second", canonicalDecimal(check.magnitudeGate.minSlopeUnitsPerSecond))
                put("min_split_half_shift_pct", canonicalDecimal(check.magnitudeGate.minSplitHalfShiftPct))
                putDecimal("required_split_half_shift_units", facts.requiredShiftUnits)
            },
        )
        put("observed_direction", direction?.let(::JsonPrimitive) ?: JsonNull)
        put("method", TREND_METHOD)
        put("uncertainty", "NOT_ESTIMATED")
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
    }

private fun finding(
    check: TrendCheckV1,
    series: ResourceSeriesV1,
    window: ResourceWindowV1,
    snapshot: ResourceSnapshotV1,
    fromIndex: Int,
    toIndex: Int,
    direction: String,
    facts: TrendFacts,
    evidenceId: String,
): JsonObject =
    buildJsonObject {
        put("id", resourceId("resource-trend-finding", check.id, window.fromEpochMillis.toString()))
        put("type", "resource_trend")
        put("check_id", check.id)
        put("series_id", series.id)
        put("metric", series.metric)
        put("unit", series.unit)
        put("entity", series.entity)
        put("window_id", window.id)
        put("observed_direction", direction)
        put("from_epoch_ms", snapshot.cellStart(fromIndex))
        put("to_epoch_ms", snapshot.cellStart(toIndex))
        put("expected_cells", facts.expectedCells)
        put("observed_cells", facts.observedCells)
        put("median", canonicalDecimal(checkNotNull(facts.median)))
        put("slope_per_second", canonicalDecimal(checkNotNull(facts.slope)))
        put("split_half_shift", canonicalDecimal(checkNotNull(facts.shift)))
        put("effect", "diagnostic")
        put("uncertainty", "NOT_ESTIMATED")
        put("evidence_id", evidenceId)
    }

private fun trendSummary(results: List<TrendCheckResult>): JsonObject =
    buildJsonObject {
        put("id", "trend-summary")
        put("type", "trend_summary")
        put("checks_total", results.size)
        put("observed", results.count { it.status == "TREND_OBSERVED" })
        put("not_material", results.count { it.status == "NO_MATERIAL_TREND" })
        put("insufficient", results.count { it.status == "INSUFFICIENT_CELLS" })
        put("unavailable", results.count { it.status == "UNAVAILABLE" })
        put("method", TREND_METHOD)
        put("uncertainty", "NOT_ESTIMATED")
    }

private fun trendJson(results: List<TrendCheckResult>): JsonObject =
    buildJsonObject {
        put("schema_version", "trend.v1")
        put("method", TREND_METHOD)
        put(
            "checks",
            buildJsonArray {
                results.forEach { item ->
                    add(
                        buildJsonObject {
                            put("id", item.check.id)
                            put("series_id", item.check.seriesId)
                            put("window_id", item.check.windowId)
                            put("declared_direction", item.check.direction.wireName)
                            put("status", item.status)
                            put("observed_direction", item.observedDirection?.let(::JsonPrimitive) ?: JsonNull)
                            putDecimal("median", item.facts.median)
                            putDecimal("slope_per_second", item.facts.slope)
                            putDecimal("split_half_shift", item.facts.shift)
                            putDecimal("required_split_half_shift_units", item.facts.requiredShiftUnits)
                            put("reasons", buildJsonArray { item.reasons.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                }
            },
        )
        put("checks_total", results.size)
        put("observed", results.count { it.status == "TREND_OBSERVED" })
        put("uncertainty", "NOT_ESTIMATED")
    }

private fun directionOf(slope: BigDecimal): String =
    when {
        slope.signum() > 0 -> "increase"
        slope.signum() < 0 -> "decrease"
        else -> "flat"
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putDecimal(
    name: String,
    value: BigDecimal?,
) {
    put(name, value?.let { JsonPrimitive(canonicalDecimal(it)) } ?: JsonNull)
}

private const val TREND_METHOD = "slope-materiality.v1"
private val HUNDRED = BigDecimal("100")
private val DECIMAL_CONTEXT = MathContext.DECIMAL128

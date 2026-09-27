package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal data class ResourceEvaluation(
    val windowVerdicts: Map<String, PolicyVerdict>,
    val coverageReasons: List<String>,
    val findings: List<JsonObject>,
    val evidence: List<JsonObject>,
)

internal fun evaluateResources(
    snapshot: ResourceSnapshotV1,
    windows: List<ResourceWindowV1>,
    checkCancelled: () -> Unit = {},
): ResourceEvaluation {
    val summaries = mutableListOf<JsonObject>()
    val checks = mutableListOf<JsonObject>()
    val findings = mutableListOf<JsonObject>()
    val reasons = mutableListOf<String>()
    val verdicts = linkedMapOf<String, PolicyVerdict>()
    val seriesById = snapshot.series.associateBy(ResourceSeriesV1::id)

    windows.forEach { window ->
        checkCancelled()
        snapshot.series.forEach { series ->
            checkCancelled()
            val summary = resourceSummary(snapshot, series, window, checkCancelled)
            summaries += summary
            reasons +=
                summary["reasons"]
                    ?.let { value ->
                        (value as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content }
                    }.orEmpty()
        }

        val slaStatuses = mutableListOf<String>()
        snapshot.rules.forEach { rule ->
            checkCancelled()
            val checkId = resourceId("resource-policy-check", window.id, rule.id)
            val series = seriesById[rule.seriesId]
            val outcome =
                evaluateRule(
                    snapshot,
                    window,
                    rule,
                    series,
                    checkId,
                    RESOURCE_FINDINGS_MAX - findings.size,
                    checkCancelled,
                )
            checks += resourceCheck(checkId, window, rule, outcome.status, outcome.reason)
            findings += outcome.findings
            outcome.reason?.let(reasons::add)
            if (rule.effect == ResourceRuleEffect.SLA) slaStatuses += outcome.status
        }
        verdicts[window.id] =
            when {
                slaStatuses.isEmpty() -> PolicyVerdict.NO_POLICY
                slaStatuses.any { it == "NO_VERDICT" } -> PolicyVerdict.NO_VERDICT
                slaStatuses.any { it == "FAIL" } -> PolicyVerdict.FAIL
                else -> PolicyVerdict.PASS
            }
    }
    return ResourceEvaluation(verdicts, reasons.distinct(), findings, summaries + checks)
}

internal data class IndexedValue(
    val cellIndex: Int,
    val value: BigDecimal,
)

internal data class Statistics(
    val min: BigDecimal?,
    val max: BigDecimal?,
    val mean: BigDecimal?,
    val median: BigDecimal?,
    val q05: BigDecimal?,
    val q25: BigDecimal?,
    val q75: BigDecimal?,
    val q95: BigDecimal?,
    val iqr: BigDecimal?,
    val mad: BigDecimal?,
    val sampleStandardDeviation: BigDecimal?,
    val slopePerSecond: BigDecimal?,
    val splitHalfShift: BigDecimal?,
)

private data class RuleOutcome(
    val status: String,
    val reason: String?,
    val findings: List<JsonObject>,
)

private fun resourceSummary(
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
        put("statistics", statistics.json())
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun statistics(
    observed: List<IndexedValue>,
    expectedCells: Int,
    stepMillis: Long,
    checkCancelled: () -> Unit,
): Statistics {
    if (observed.isEmpty()) return Statistics(null, null, null, null, null, null, null, null, null, null, null, null, null)
    checkCancelled()
    val ordered = observed.map(IndexedValue::value).sorted()
    checkCancelled()
    val mean = ordered.fold(BigDecimal.ZERO, BigDecimal::add).divide(ordered.size.bd(), MC)
    val median = quantile(ordered, HALF)
    val q05 = quantile(ordered, Q05)
    val q25 = quantile(ordered, Q25)
    val q75 = quantile(ordered, Q75)
    val q95 = quantile(ordered, Q95)
    checkCancelled()
    val mad = quantile(ordered.map { it.subtract(median).abs() }.sorted(), HALF)
    checkCancelled()
    val sampleStandardDeviation =
        if (ordered.size < 2) {
            null
        } else {
            ordered
                .fold(BigDecimal.ZERO) { total, value -> total.add(value.subtract(mean).pow(2)) }
                .divide((ordered.size - 1).bd(), MC)
                .sqrt(MC)
        }
    val slope =
        if (observed.size < 2) {
            null
        } else {
            checkCancelled()
            val points =
                observed.map { item ->
                    BigDecimal
                        .valueOf(item.cellIndex.toLong())
                        .multiply(BigDecimal.valueOf(stepMillis))
                        .divide(THOUSAND) to item.value
                }
            val meanTime = points.fold(BigDecimal.ZERO) { total, point -> total.add(point.first) }.divide(points.size.bd(), MC)
            val meanValue = points.fold(BigDecimal.ZERO) { total, point -> total.add(point.second) }.divide(points.size.bd(), MC)
            val numerator =
                points.fold(BigDecimal.ZERO) { total, point ->
                    total.add(point.first.subtract(meanTime).multiply(point.second.subtract(meanValue)))
                }
            val denominator =
                points.fold(BigDecimal.ZERO) { total, point ->
                    total.add(point.first.subtract(meanTime).pow(2))
                }
            numerator.divide(denominator, MC)
        }
    val midpoint = expectedCells / 2
    val firstHalf = observed.filter { it.cellIndex < midpoint }.map(IndexedValue::value).sorted()
    val secondHalf = observed.filter { it.cellIndex >= midpoint }.map(IndexedValue::value).sorted()
    val splitHalf =
        if (firstHalf.isEmpty() || secondHalf.isEmpty()) {
            null
        } else {
            quantile(secondHalf, HALF).subtract(quantile(firstHalf, HALF))
        }
    return Statistics(
        ordered.first(),
        ordered.last(),
        mean,
        median,
        q05,
        q25,
        q75,
        q95,
        q75.subtract(q25),
        mad,
        sampleStandardDeviation,
        slope,
        splitHalf,
    )
}

private fun quantile(
    ordered: List<BigDecimal>,
    probability: BigDecimal,
): BigDecimal {
    if (ordered.size == 1) return ordered.single()
    val h = (ordered.size - 1).bd().multiply(probability)
    val lower = h.setScale(0, RoundingMode.FLOOR).intValueExact()
    val fraction = h.subtract(lower.bd())
    return ordered[lower].add(ordered[lower + 1].subtract(ordered[lower]).multiply(fraction))
}

private fun evaluateRule(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    series: ResourceSeriesV1?,
    checkId: String,
    findingsLimit: Int,
    checkCancelled: () -> Unit,
): RuleOutcome {
    if (series == null) return RuleOutcome("NO_VERDICT", "RESOURCE_SERIES_NOT_FOUND", emptyList())
    val fromIndex = snapshot.cellIndex(window.fromEpochMillis)
    val toIndex = snapshot.cellIndex(window.toEpochMillis)
    val findings = mutableListOf<JsonObject>()
    var missing = false
    var runStart = -1
    var runMin: BigDecimal? = null
    var runMax: BigDecimal? = null

    fun flush(endExclusive: Int) {
        if (runStart >= 0 && endExclusive - runStart >= rule.minConsecutiveCells) {
            require(findings.size < findingsLimit) { "RESOURCE_FINDINGS_LIMIT_EXCEEDED" }
            findings +=
                thresholdFinding(
                    snapshot,
                    window,
                    series,
                    rule,
                    checkId,
                    runStart,
                    endExclusive,
                    checkNotNull(runMin),
                    checkNotNull(runMax),
                )
        }
        runStart = -1
        runMin = null
        runMax = null
    }

    for (index in fromIndex until toIndex) {
        checkCancelled()
        val value = series.values[index]
        if (value == null) {
            missing = true
            flush(index)
            continue
        }
        val violates =
            when (rule.operator) {
                ResourceOperator.GT -> value > rule.threshold
                ResourceOperator.LT -> value < rule.threshold
            }
        if (!violates) {
            flush(index)
        } else if (runStart < 0) {
            runStart = index
            runMin = value
            runMax = value
        } else {
            runMin = minOf(checkNotNull(runMin), value)
            runMax = maxOf(checkNotNull(runMax), value)
        }
    }
    flush(toIndex)
    return when {
        missing -> RuleOutcome("NO_VERDICT", "MISSING_RESOURCE_CELLS", findings)
        findings.isNotEmpty() -> RuleOutcome("FAIL", null, findings)
        else -> RuleOutcome("PASS", null, findings)
    }
}

private fun resourceCheck(
    id: String,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    status: String,
    reason: String?,
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
    }

private fun thresholdFinding(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    series: ResourceSeriesV1,
    rule: ResourceRuleV1,
    evidenceId: String,
    fromIndex: Int,
    toIndex: Int,
    observedMin: BigDecimal,
    observedMax: BigDecimal,
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
        put("evidence_id", evidenceId)
    }
}

private fun Statistics.json(): JsonObject =
    buildJsonObject {
        putDecimal("min", min)
        putDecimal("max", max)
        putDecimal("mean", mean)
        putDecimal("median", median)
        putDecimal("q05", q05)
        putDecimal("q25", q25)
        putDecimal("q75", q75)
        putDecimal("q95", q95)
        putDecimal("iqr", iqr)
        putDecimal("mad", mad)
        putDecimal("sample_standard_deviation", sampleStandardDeviation)
        putDecimal("slope_per_second", slopePerSecond)
        putDecimal("split_half_shift", splitHalfShift)
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putDecimal(
    name: String,
    value: BigDecimal?,
) {
    put(name, value?.let { JsonPrimitive(canonicalDecimal(it)) } ?: JsonNull)
}

internal fun ResourceSnapshotV1.cellIndex(epochMillis: Long): Int = ((epochMillis - startEpochMillis) / stepMillis).toInt()

internal fun ResourceSnapshotV1.cellStart(index: Int): Long =
    Math.addExact(startEpochMillis, Math.multiplyExact(index.toLong(), stepMillis))

internal fun resourceId(
    prefix: String,
    vararg parts: String,
): String = "$prefix-${sha256Hex(parts.joinToString("\u0000").encodeToByteArray())}"

internal fun Int.bd(): BigDecimal = BigDecimal.valueOf(toLong())

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private val MC = MathContext.DECIMAL128
private val HALF = BigDecimal("0.5")
private val Q05 = BigDecimal("0.05")
private val Q25 = BigDecimal("0.25")
private val Q75 = BigDecimal("0.75")
private val Q95 = BigDecimal("0.95")
private val THOUSAND = BigDecimal("1000")
internal const val RESOURCE_FINDINGS_MAX = 10_000

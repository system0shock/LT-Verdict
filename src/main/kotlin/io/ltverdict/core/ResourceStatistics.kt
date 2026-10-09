package io.ltverdict.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    platform: PlatformExpansion = PlatformExpansion.EMPTY,
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
        (snapshot.rules + platform.rules).forEach { rule ->
            checkCancelled()
            if (rule.windowIds != null && window.id !in rule.windowIds) return@forEach
            val checkId = resourceId("resource-policy-check", window.id, rule.id)
            val series = seriesById[rule.seriesId]
            val bindingFailure = platform.bindingFailures[rule.id]
            val windowCells = snapshot.cellIndex(window.toEpochMillis) - snapshot.cellIndex(window.fromEpochMillis)
            val cells =
                if (rule.platform ==
                    null
                ) {
                    null
                } else {
                    series?.let { cellStats(snapshot, window, it) } ?: CellStats(windowCells, 0, windowCells)
                }
            val outcome =
                when {
                    bindingFailure != null -> RuleOutcome("NO_VERDICT", bindingFailure, emptyList())
                    rule.platform != null && windowCells < rule.minConsecutiveCells ->
                        RuleOutcome("NO_VERDICT", "RULE_WINDOW_TOO_SHORT", emptyList())
                    else ->
                        evaluateRule(
                            snapshot,
                            window,
                            rule,
                            series,
                            checkId,
                            RESOURCE_FINDINGS_MAX - findings.size,
                            checkCancelled,
                            cells,
                        )
                }
            checks += resourceCheck(checkId, window, rule, outcome.status, outcome.reason, cells)
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
    val firstHalfCells: Int,
    val secondHalfCells: Int,
)

private data class RuleOutcome(
    val status: String,
    val reason: String?,
    val findings: List<JsonObject>,
)

internal fun resourceSummary(
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
    return ResourceSummaryEvidence(
        id = resourceId("resource-summary", window.id, series.id),
        seriesId = series.id,
        metric = series.metric,
        unit = series.unit,
        entity = series.entity,
        role = series.role.wireName,
        aggregation = series.aggregation.wireName,
        windowId = window.id,
        fromEpochMs = window.fromEpochMillis,
        toEpochMs = window.toEpochMillis,
        expectedCells = expected,
        observedCells = observed.size,
        missingCells = expected - observed.size,
        longestGapCells = longestGap,
        statistics = statistics.toDocument(),
        reasons = reasons,
    ).toJson()
}

internal fun statistics(
    observed: List<IndexedValue>,
    expectedCells: Int,
    stepMillis: Long,
    checkCancelled: () -> Unit,
): Statistics {
    if (observed.isEmpty()) return Statistics(null, null, null, null, null, null, null, null, null, null, null, null, null, 0, 0)
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
        firstHalf.size,
        secondHalf.size,
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
    cells: CellStats? = null,
): RuleOutcome {
    if (series == null) return RuleOutcome("NO_VERDICT", "RESOURCE_SERIES_NOT_FOUND", emptyList())
    val fraction = rule.maxMissingFraction
    if (cells != null && fraction != null && withinTolerance(cells, fraction, checkNotNull(rule.maxGapCells))) {
        return evaluateBridged(snapshot, window, rule, series, checkId, findingsLimit, checkCancelled)
    }
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

internal data class CellStats(
    val expected: Int,
    val observed: Int,
    val longestGap: Int,
) {
    val missing: Int get() = expected - observed
}

private fun cellStats(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    series: ResourceSeriesV1,
): CellStats {
    val from = snapshot.cellIndex(window.fromEpochMillis)
    val to = snapshot.cellIndex(window.toEpochMillis)
    var observed = 0
    var gap = 0
    var longest = 0
    for (index in from until to) {
        if (series.values[index] == null) {
            gap++
            longest = maxOf(longest, gap)
        } else {
            gap = 0
            observed++
        }
    }
    return CellStats(to - from, observed, longest)
}

private fun withinTolerance(
    cells: CellStats,
    maxMissingFraction: BigDecimal,
    maxGapCells: Int,
): Boolean =
    cells.missing > 0 &&
        cells.observed > 0 &&
        cells.missing.bd() <= maxMissingFraction.multiply(cells.expected.bd()) &&
        cells.longestGap <= maxGapCells

private class ViolationRun(
    val start: Int,
    var end: Int,
    var min: BigDecimal,
    var max: BigDecimal,
) {
    val length: Int get() = end + 1 - start
}

private fun evaluateBridged(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    series: ResourceSeriesV1,
    checkId: String,
    findingsLimit: Int,
    checkCancelled: () -> Unit,
): RuleOutcome {
    val findings = mutableListOf<JsonObject>()
    var presumed = false
    val chain = mutableListOf<ViolationRun>()

    fun report(
        from: Int,
        to: Int,
        min: BigDecimal,
        max: BigDecimal,
        isPresumed: Boolean,
    ) {
        require(findings.size < findingsLimit) { "RESOURCE_FINDINGS_LIMIT_EXCEEDED" }
        findings += thresholdFinding(snapshot, window, series, rule, checkId, from, to, min, max, isPresumed)
    }

    fun flush() {
        val proven = chain.filter { it.length >= rule.minConsecutiveCells }
        if (proven.isNotEmpty()) {
            proven.forEach { report(it.start, it.end + 1, it.min, it.max, false) }
        } else if (chain.isNotEmpty() && chain.last().end + 1 - chain.first().start >= rule.minConsecutiveCells) {
            presumed = true
            report(chain.first().start, chain.last().end + 1, chain.minOf { it.min }, chain.maxOf { it.max }, true)
        }
        chain.clear()
    }

    for (index in snapshot.cellIndex(window.fromEpochMillis) until snapshot.cellIndex(window.toEpochMillis)) {
        checkCancelled()
        val value = series.values[index] ?: continue
        val violates =
            when (rule.operator) {
                ResourceOperator.GT -> value > rule.threshold
                ResourceOperator.LT -> value < rule.threshold
            }
        if (!violates) {
            flush()
            continue
        }
        val last = chain.lastOrNull()
        if (last != null && last.end + 1 == index) {
            last.end = index
            last.min = minOf(last.min, value)
            last.max = maxOf(last.max, value)
        } else {
            chain += ViolationRun(index, index, value, value)
        }
    }
    flush()
    return when {
        presumed -> RuleOutcome("NO_VERDICT", "MISSING_RESOURCE_CELLS", findings)
        findings.isNotEmpty() -> RuleOutcome("FAIL", "RESOURCE_GAPS", findings)
        else -> RuleOutcome("PASS", "RESOURCE_GAPS", findings)
    }
}

internal fun resourceCheck(
    id: String,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    status: String,
    reason: String?,
    cells: CellStats?,
): JsonObject =
    ResourcePolicyCheckEvidence(
        id = id,
        windowId = window.id,
        ruleId = rule.id,
        seriesId = rule.seriesId,
        unit = rule.unit,
        operator = rule.operator.wireName,
        threshold = canonicalDecimal(rule.threshold),
        effect = rule.effect.wireName,
        status = status,
        reason = reason,
        platformRuleId = rule.platform?.ruleId,
        service = rule.platform?.service,
        expectedCells = cells?.expected,
        observedCells = cells?.observed,
        missingCells = cells?.missing,
        longestGapCells = cells?.longestGap,
    ).toJson()

internal fun thresholdFinding(
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
    return ResourceThresholdViolationFinding(
        id = resourceId("resource-threshold-finding", window.id, rule.id, from.toString()),
        windowId = window.id,
        ruleId = rule.id,
        seriesId = series.id,
        entity = series.entity,
        unit = series.unit,
        fromEpochMs = from,
        toEpochMs = to,
        cellCount = toIndex - fromIndex,
        observedMin = canonicalDecimal(observedMin),
        observedMax = canonicalDecimal(observedMax),
        presumed = if (presumed) true else null,
        evidenceId = evidenceId,
    ).toJson()
}

private fun Statistics.toDocument() =
    ResourceStatisticsDocument(
        min = min?.let(::canonicalDecimal),
        max = max?.let(::canonicalDecimal),
        mean = mean?.let(::canonicalDecimal),
        median = median?.let(::canonicalDecimal),
        q05 = q05?.let(::canonicalDecimal),
        q25 = q25?.let(::canonicalDecimal),
        q75 = q75?.let(::canonicalDecimal),
        q95 = q95?.let(::canonicalDecimal),
        iqr = iqr?.let(::canonicalDecimal),
        mad = mad?.let(::canonicalDecimal),
        sampleStandardDeviation = sampleStandardDeviation?.let(::canonicalDecimal),
        slopePerSecond = slopePerSecond?.let(::canonicalDecimal),
        splitHalfShift = splitHalfShift?.let(::canonicalDecimal),
    )

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

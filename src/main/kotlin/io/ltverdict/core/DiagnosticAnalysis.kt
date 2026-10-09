@file:Suppress("ktlint:standard:filename")

package io.ltverdict.core

import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.UtcLoadCell
import io.ltverdict.metrics.UtcLoadMetrics
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sqrt

internal data class DiagnosticEvaluation(
    val findings: List<JsonObject>,
    val evidence: List<JsonObject>,
)

internal fun evaluateDiagnostics(
    validation: DiagnosticValidation.Valid,
    resources: ResourceValidation.Valid,
    windows: List<ResourceWindowV1>,
    load: UtcLoadMetrics,
    windowMetrics: Map<String, NormalizedMetrics>,
    checkCancelled: () -> Unit = {},
): DiagnosticEvaluation {
    val plan = validation.plan
    val byWindow = windows.associateBy(ResourceWindowV1::id)
    val bySeries = resources.snapshot.series.associateBy(ResourceSeriesV1::id)
    val pairEvidence = mutableListOf<JsonObject>()
    val pairResults = mutableListOf<PairResult>()
    val findings = mutableListOf<JsonObject>()
    var evaluablePairs = 0

    plan.pairs.forEach { pair ->
        pair.windowIds.forEach { windowId ->
            checkCancelled()
            val window = requireNotNull(byWindow[windowId]) { "DIAGNOSTIC_WINDOW_NOT_FOUND" }
            val result = evaluatePair(pair, resources.snapshot, bySeries, window, load.windows.getValue(windowId), checkCancelled)
            pairEvidence += result.evidence
            pairResults += result
            if (result.evaluable) evaluablePairs++
        }
    }
    // Family = stage x outcome (ADR 0022, D2). The family count F comes from the plan, not from the data, and each
    // family is tested separately at alpha / F (D3).
    val families = pairResults.map(PairResult::hypothesis).groupBy { it.windowId to it.outcomeKey }
    val seedMaterial = "${validation.sha256}/${resources.semanticSha256}"
    val selectionByPair =
        families.values
            .flatMap { selectCorrelationHeadlines(it, seedMaterial, checkCancelled, families.size) }
            .associateBy { it.pairId to it.windowId }
    val headlineEvidence =
        pairResults.map {
            selectionByPair
                .getValue(it.hypothesis.pairId to it.hypothesis.windowId)
                .evidence(it.sourceCells, it.hypothesis.resource.size)
        }
    pairResults.forEach { result ->
        if (selectionByPair.getValue(result.hypothesis.pairId to result.hypothesis.windowId).selected) {
            result.finding?.let(findings::add)
        }
    }

    val anomalyEvidence = mutableListOf<JsonObject>()
    var episodes = 0
    var suppressed = 0
    try {
        plan.anomalies.forEach { anomaly ->
            checkCancelled()
            val result =
                evaluateAnomaly(
                    anomaly,
                    resources.snapshot,
                    bySeries,
                    byWindow,
                    load,
                    MAX_DIAGNOSTIC_EPISODES - episodes,
                    checkCancelled,
                )
            episodes += result.episodes.size
            suppressed += result.suppressed
            anomalyEvidence += result.check
            findings += result.episodes
        }
    } catch (_: DiagnosticEpisodeLimitExceeded) {
        return diagnosticUnavailable(plan, "LIMIT_EXCEEDED", "DIAGNOSTIC_EPISODE_LIMIT_EXCEEDED")
    }

    val windowsEvidence =
        windows.map { window ->
            checkCancelled()
            windowMetricSummary(window, windowMetrics.getValue(window.id), resources.snapshot.series)
        }
    val summary =
        diagnosticSummary(
            status = "COMPLETE",
            pairsTested = pairEvidence.size,
            pairsEvaluable = evaluablePairs,
            anomaliesTested = plan.anomalies.size,
            episodesReported = episodes,
            suppressedShortEpisodes = suppressed,
            reasons = emptyList(),
        )
    return DiagnosticEvaluation(
        findings,
        windowsEvidence + pairEvidence + headlineEvidence + anomalyEvidence + summary,
    )
}

internal fun diagnosticUnavailable(
    plan: DiagnosticPlanV1,
    status: String,
    reason: String,
): DiagnosticEvaluation =
    DiagnosticEvaluation(
        emptyList(),
        listOf(
            diagnosticSummary(
                status,
                pairsTested = 0,
                pairsEvaluable = 0,
                anomaliesTested = 0,
                episodesReported = 0,
                suppressedShortEpisodes = 0,
                reasons = listOf(reason),
                idSalt = plan.resourceSnapshotSha256,
            ),
        ),
    )

private data class PairPoint(
    val epochMillis: Long,
    val resource: BigDecimal,
    val load: BigDecimal,
    val controls: List<BigDecimal>,
)

private data class Association(
    val raw: Double?,
    val partial: Double?,
    val finalX: DoubleArray?,
    val finalY: DoubleArray?,
    val controlsUsed: List<String>,
    val controlsDropped: List<String>,
    val reasons: List<String>,
    val sensitivityWithoutAchievedRps: Double?,
)

private data class PairResult(
    val evidence: JsonObject,
    val finding: JsonObject?,
    val evaluable: Boolean,
    val hypothesis: CorrelationHeadlineHypothesis,
    val sourceCells: Int,
)

private fun evaluatePair(
    pair: DiagnosticPairV1,
    snapshot: ResourceSnapshotV1,
    series: Map<String, ResourceSeriesV1>,
    window: ResourceWindowV1,
    loadCells: List<UtcLoadCell>,
    checkCancelled: () -> Unit,
): PairResult {
    val source = series.getValue(pair.resourceSeriesId)
    val fromIndex = snapshot.indexAt(window.fromEpochMillis)
    val expected = ((window.toEpochMillis - window.fromEpochMillis) / snapshot.stepMillis).toInt()
    require(loadCells.size == expected) { "DIAGNOSTIC_LOAD_WINDOW_MISMATCH" }
    val points = mutableListOf<PairPoint>()
    repeat(expected) { offset ->
        checkCancelled()
        val cell = loadCells[offset]
        val x = source.values[fromIndex + offset]
        val y = cell.value(pair.loadMetric)
        val controls = pair.controls.map { control -> control.value(series, fromIndex + offset, cell) }
        if (x != null && y != null && controls.all { it != null }) {
            @Suppress("UNCHECKED_CAST")
            points += PairPoint(cell.fromEpochMillis, x, y, controls as List<BigDecimal>)
        }
    }
    val controlKeys = pair.controls.map(DiagnosticControlV1::key)
    // Method v2 (ADR 0022, D4): association, lag profile, effect threshold and the selector input all use the first
    // differences of the longest continuous run; the range gates below stay on the levels of all complete cells.
    val longest = longestContinuous(points, snapshot.stepMillis)
    val differences = firstDifferences(longest)
    val achievedIndex = pair.controls.indexOfFirst { it.meaning == DiagnosticControlMeaning.ACHIEVED_RPS }
    val association = association(differences, controlKeys, achievedIndex)
    val reasons = association.reasons.toMutableList()
    if (points.size < expected) reasons += "MISSING_CELLS"
    if (pair.clockAlignment == DiagnosticClockAlignment.UNKNOWN && pair.maxLagMillis > 0) reasons += "CLOCK_ALIGNMENT_UNKNOWN"

    val lagCells = (pair.maxLagMillis / snapshot.stepMillis).toInt()
    val anchorCount = differences.size - 2 * lagCells
    val lagProfile = mutableListOf<Pair<Long, Double?>>()
    if (anchorCount >= MIN_PAIRED_CELLS &&
        anchorCount > association.controlsUsed.size + 3 &&
        association.finalX != null &&
        association.finalY != null
    ) {
        for (lag in -lagCells..lagCells) {
            checkCancelled()
            val x = DoubleArray(anchorCount) { index -> association.finalX[index + lagCells] }
            val y = DoubleArray(anchorCount) { index -> association.finalY[index + lagCells + lag] }
            lagProfile += lag * snapshot.stepMillis to pearson(x, y)
        }
    } else {
        reasons += "LAG_NOT_EVALUABLE"
    }
    val coefficient = if (pair.controls.isEmpty()) association.raw else association.partial
    val best =
        lagProfile
            .filter { it.second != null }
            .minWithOrNull(
                compareByDescending<Pair<Long, Double?>> { abs(checkNotNull(it.second)) }
                    .thenBy { abs(it.first) }
                    .thenBy { it.first },
            )
    if (lagProfile.isNotEmpty() && best == null) reasons += "LAG_NOT_EVALUABLE"
    val bestLag = best?.first ?: 0L
    val bestRho = best?.second ?: coefficient
    val resourceRange = points.range(PairPoint::resource)
    val loadRange = points.range(PairPoint::load)
    val contextPresent =
        snapshot.windows.isNotEmpty() &&
            pair.controls.any { it.meaning == DiagnosticControlMeaning.TARGET_RPS || it.meaning == DiagnosticControlMeaning.CONCURRENCY }
    if (snapshot.windows.isEmpty()) reasons += "STAGE_UNSPECIFIED"
    if (!contextPresent) reasons += "CONTROL_CONTEXT_MISSING"
    if (pair.controls.size == 1 && pair.controls.single().meaning == DiagnosticControlMeaning.ACHIEVED_RPS) reasons += "ACHIEVED_LOAD_ONLY"
    if (resourceRange == null || resourceRange < pair.minResourceDelta) reasons += "RESOURCE_DELTA_BELOW_MINIMUM"
    if (loadRange == null || loadRange < pair.minLoadDelta) reasons += "LOAD_DELTA_BELOW_MINIMUM"
    val status =
        when {
            coefficient == null || bestRho == null -> "INSUFFICIENT_DATA"
            resourceRange == null ||
                resourceRange < pair.minResourceDelta ||
                loadRange == null ||
                loadRange < pair.minLoadDelta -> "BELOW_EFFECT"
            abs(bestRho) < pair.minAbsEffect.toDouble() -> "BELOW_EFFECT"
            !pair.expectedSign.matches(bestRho) -> "OPPOSITE_SIGN"
            !contextPresent -> "DESCRIPTIVE"
            else -> "CANDIDATE"
        }
    val evidenceId = diagnosticId("correlation-pair", pair.id, window.id)
    val evidence =
        CorrelationPairEvidence(
            id = evidenceId,
            pairId = pair.id,
            windowId = window.id,
            resourceSeriesId = source.id,
            loadMetric = pair.loadMetric.wireName,
            entity = source.entity,
            resourceUnit = source.unit,
            loadUnit = pair.loadMetric.unit,
            fromEpochMs = window.fromEpochMillis,
            toEpochMs = window.toEpochMillis,
            expectedCells = expected,
            pairedCells = points.size,
            lagUsedCells = if (lagProfile.isEmpty()) 0 else anchorCount,
            rawRho = association.raw?.let(::decimalString),
            partialRho = association.partial?.let(::decimalString),
            bestLagMs = bestLag,
            bestLagRho = bestRho?.let(::decimalString),
            lagProfile = lagProfile.map { (lag, rho) -> LagProfileEntry(lag, rho?.let(::decimalString)) },
            status = status,
            controlsRequested = controlKeys,
            controlsUsed = association.controlsUsed,
            controlsDropped = association.controlsDropped,
            sensitivityWithoutAchievedRps = association.sensitivityWithoutAchievedRps?.let(::decimalString),
            reasons = reasons.distinct(),
            uncertainty = "NOT_ESTIMATED",
        ).toJson()
    val finding =
        if (status == "CANDIDATE") {
            CorrelationCandidateFinding(
                id = diagnosticId("correlation-candidate", pair.id, window.id),
                pairId = pair.id,
                windowId = window.id,
                evidenceId = evidenceId,
                uncertainty = "NOT_ESTIMATED",
            ).toJson()
        } else {
            null
        }
    val unavailableReason =
        when {
            association.controlsUsed.isNotEmpty() -> "GENUINE_PARTIAL_UNCALIBRATED"
            coefficient == null || bestRho == null || lagProfile.isEmpty() -> "PAIR_NOT_EVALUABLE"
            else -> null
        }
    val hypothesis =
        CorrelationHeadlineHypothesis(
            pairId = pair.id,
            windowId = window.id,
            epochs = LongArray(differences.size) { differences[it].epochMillis },
            resource = DoubleArray(differences.size) { differences[it].resource.toDouble() },
            outcome = DoubleArray(differences.size) { differences[it].load.toDouble() },
            outcomeKey = pair.loadMetric.wireName,
            maxLagCells = lagCells,
            materialCandidate = status == "CANDIDATE",
            unavailableReason = unavailableReason,
        )
    return PairResult(evidence, finding, coefficient != null, hypothesis, longest.size)
}

private fun association(
    points: List<PairPoint>,
    controlKeys: List<String>,
    achievedIndex: Int,
): Association {
    if (points.size < MIN_PAIRED_CELLS) {
        return Association(null, null, null, null, emptyList(), emptyList(), listOf("INSUFFICIENT_PAIRED_CELLS"), null)
    }
    val xRanks = ranks(points.map { it.resource })
    val yRanks = ranks(points.map { it.load })
    val raw = pearson(xRanks, yRanks)
    val reasons = mutableListOf<String>()
    if (raw == null) reasons += "NO_RANK_VARIATION"
    if (controlKeys.isEmpty()) return Association(raw, null, xRanks, yRanks, emptyList(), emptyList(), reasons, null)

    val controls = controlKeys.indices.map { index -> ranks(points.map { it.controls[index] }) }
    val basis = orthonormalBasis(controls, controlKeys)
    val residualX = residualize(xRanks, basis.vectors)
    val residualY = residualize(yRanks, basis.vectors)
    val partial = if (residualX == null || residualY == null) null else pearson(residualX, residualY)
    if (partial == null) reasons += "NO_RESIDUAL_VARIATION"
    basis.droppedReasons.forEach { reasons += it.second }
    val sensitivity =
        if (achievedIndex < 0) {
            null
        } else {
            val retainedIndices = controlKeys.indices.filter { it != achievedIndex }
            if (retainedIndices.isEmpty()) {
                raw
            } else {
                val sensitivityBasis = orthonormalBasis(retainedIndices.map(controls::get), retainedIndices.map(controlKeys::get)).vectors
                val sx = residualize(xRanks, sensitivityBasis)
                val sy = residualize(yRanks, sensitivityBasis)
                if (sx == null || sy == null) null else pearson(sx, sy)
            }
        }
    return Association(
        raw,
        partial,
        residualX,
        residualY,
        basis.used,
        basis.droppedReasons.map { it.first },
        reasons.distinct(),
        sensitivity,
    )
}

private data class Basis(
    val vectors: List<DoubleArray>,
    val used: List<String>,
    val droppedReasons: List<Pair<String, String>>,
)

private fun orthonormalBasis(
    columns: List<DoubleArray>,
    keys: List<String>,
): Basis {
    val vectors = mutableListOf<DoubleArray>()
    val used = mutableListOf<String>()
    val dropped = mutableListOf<Pair<String, String>>()
    columns.zip(keys).forEach { (column, key) ->
        val centered = centered(column)
        val originalNorm = norm(centered)
        if (originalNorm == 0.0) {
            dropped += key to "CONSTANT_CONTROL"
            return@forEach
        }
        repeat(2) {
            vectors.forEach { basis -> subtractProjection(centered, basis) }
        }
        val residualNorm = norm(centered)
        if (residualNorm / originalNorm <= RESIDUAL_RELATIVE_EPSILON) {
            dropped += key to "REDUNDANT_CONTROL"
        } else {
            for (index in centered.indices) centered[index] /= residualNorm
            vectors += centered
            used += key
        }
    }
    return Basis(vectors, used, dropped)
}

private fun residualize(
    values: DoubleArray,
    basis: List<DoubleArray>,
): DoubleArray? {
    val residual = centered(values)
    val originalNorm = norm(residual)
    if (originalNorm == 0.0) return null
    repeat(2) {
        basis.forEach { vector -> subtractProjection(residual, vector) }
    }
    return residual.takeIf { norm(it) / originalNorm > RESIDUAL_RELATIVE_EPSILON }
}

private fun subtractProjection(
    values: DoubleArray,
    basis: DoubleArray,
) {
    var projection = 0.0
    for (index in values.indices) projection += values[index] * basis[index]
    for (index in values.indices) values[index] -= projection * basis[index]
}

private fun centered(values: DoubleArray): DoubleArray {
    val mean = values.average()
    return DoubleArray(values.size) { values[it] - mean }
}

private fun norm(values: DoubleArray): Double = sqrt(values.sumOf { it * it })

private fun ranks(values: List<BigDecimal>): DoubleArray {
    val ordered = values.indices.sortedWith(compareBy<Int> { values[it] }.thenBy { it })
    val result = DoubleArray(values.size)
    var start = 0
    while (start < ordered.size) {
        var end = start + 1
        while (end < ordered.size && values[ordered[start]].compareTo(values[ordered[end]]) == 0) end++
        val rank = (start + 1 + end).toDouble() / 2.0
        for (index in start until end) result[ordered[index]] = rank
        start = end
    }
    return result
}

private fun pearson(
    left: DoubleArray,
    right: DoubleArray,
): Double? {
    if (left.size != right.size || left.size < 2) return null
    val x = centered(left)
    val y = centered(right)
    val denominator = norm(x) * norm(y)
    if (denominator == 0.0) return null
    var numerator = 0.0
    for (index in x.indices) numerator += x[index] * y[index]
    val value = (numerator / denominator).coerceIn(-1.0, 1.0)
    return value.takeIf(Double::isFinite)
}

private fun longestContinuous(
    points: List<PairPoint>,
    stepMillis: Long,
): List<PairPoint> {
    if (points.isEmpty()) return emptyList()
    var bestStart = 0
    var bestSize = 1
    var currentStart = 0
    for (index in 1 until points.size) {
        if (points[index].epochMillis - points[index - 1].epochMillis != stepMillis) currentStart = index
        val size = index - currentStart + 1
        if (size > bestSize) {
            bestStart = currentStart
            bestSize = size
        }
    }
    return points.subList(bestStart, bestStart + bestSize)
}

// A difference takes the epoch and the control levels of the later cell: a control is a gate on the levels of the stage
// (a ramp would become constant after differencing and be dropped), and the epochs keep the step of the grid.
private fun firstDifferences(points: List<PairPoint>): List<PairPoint> =
    List((points.size - 1).coerceAtLeast(0)) { index ->
        val previous = points[index]
        val next = points[index + 1]
        PairPoint(next.epochMillis, next.resource.subtract(previous.resource), next.load.subtract(previous.load), next.controls)
    }

private fun List<PairPoint>.range(selector: (PairPoint) -> BigDecimal): BigDecimal? =
    takeIf { it.isNotEmpty() }?.let { points ->
        points.maxOf(selector).subtract(points.minOf(selector))
    }

private fun DiagnosticExpectedSign.matches(value: Double): Boolean =
    when (this) {
        DiagnosticExpectedSign.POSITIVE -> value > 0
        DiagnosticExpectedSign.NEGATIVE -> value < 0
        DiagnosticExpectedSign.EITHER -> true
    }

private fun DiagnosticControlV1.value(
    series: Map<String, ResourceSeriesV1>,
    index: Int,
    cell: UtcLoadCell,
): BigDecimal? =
    if (meaning == DiagnosticControlMeaning.ACHIEVED_RPS) {
        cell.throughputRps.decimal()
    } else {
        series.getValue(checkNotNull(seriesId)).values[index]
    }

private fun UtcLoadCell.value(metric: DiagnosticLoadMetric): BigDecimal? =
    when (metric) {
        DiagnosticLoadMetric.RESPONSE_TIME_P95_MS -> responseTimeP95Millis?.let(BigDecimal::valueOf)
        DiagnosticLoadMetric.ERROR_RATE -> errorRate?.decimal()
        DiagnosticLoadMetric.THROUGHPUT_RPS -> throughputRps.decimal()
    }

private data class AnomalyResult(
    val check: JsonObject,
    val episodes: List<JsonObject>,
    val suppressed: Int,
)

private data class SignalSeries(
    val metric: String,
    val unit: String,
    val entity: String,
    val values: List<BigDecimal?>,
)

private data class Episode(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val direction: String,
    val values: List<BigDecimal>,
)

private class DiagnosticEpisodeLimitExceeded : RuntimeException()

private fun evaluateAnomaly(
    anomaly: DiagnosticAnomalyV1,
    snapshot: ResourceSnapshotV1,
    series: Map<String, ResourceSeriesV1>,
    windows: Map<String, ResourceWindowV1>,
    load: UtcLoadMetrics,
    episodeBudget: Int,
    checkCancelled: () -> Unit,
): AnomalyResult {
    val referenceWindow = windows.getValue(anomaly.referenceWindowId)
    val evaluationWindow = windows.getValue(anomaly.windowId)
    val reference = anomaly.signal.values(snapshot, series, referenceWindow, load)
    val evaluation = anomaly.signal.values(snapshot, series, evaluationWindow, load)
    val observedReference = reference.values.filterNotNull()
    val reasons = mutableListOf<String>()
    if (observedReference.size < reference.values.size) reasons += "REFERENCE_GAPS"
    if (evaluation.values.any { it == null }) reasons += "EVALUATION_GAPS"
    val checkId = diagnosticId("anomaly-check", anomaly.id, anomaly.windowId)
    if (observedReference.size < MIN_REFERENCE_CELLS) {
        reasons += "INSUFFICIENT_REFERENCE_CELLS"
        return AnomalyResult(
            anomalyCheck(
                anomaly,
                checkId,
                referenceWindow,
                evaluationWindow,
                "INSUFFICIENT_DATA",
                null,
                null,
                observedReference.size,
                reference.values.size,
                evaluation.values.count {
                    it !=
                        null
                },
                evaluation.values.size,
                0,
                0,
                reasons,
            ),
            emptyList(),
            0,
        )
    }
    val median = median(observedReference)
    val mad = median(observedReference.map { it.subtract(median).abs() })
    if (mad.signum() == 0) reasons += "ZERO_MAD"
    val observedEvaluation = evaluation.values.count { it != null }
    if (observedEvaluation == 0) {
        reasons += "NO_EVALUATION_OBSERVATIONS"
        return AnomalyResult(
            anomalyCheck(
                anomaly,
                checkId,
                referenceWindow,
                evaluationWindow,
                "INSUFFICIENT_DATA",
                median,
                mad,
                observedReference.size,
                reference.values.size,
                0,
                evaluation.values.size,
                0,
                0,
                reasons,
            ),
            emptyList(),
            0,
        )
    }
    val episodes = mutableListOf<Episode>()
    var suppressed = 0
    var currentStart = -1
    var currentDirection: String? = null
    val currentValues = mutableListOf<BigDecimal>()

    fun finish(endExclusive: Int) {
        if (currentStart < 0) return
        val duration = (endExclusive - currentStart) * snapshot.stepMillis
        if (duration >= anomaly.minDurationMillis) {
            if (episodes.size >= episodeBudget) throw DiagnosticEpisodeLimitExceeded()
            episodes +=
                Episode(
                    evaluationWindow.fromEpochMillis + currentStart * snapshot.stepMillis,
                    evaluationWindow.fromEpochMillis + endExclusive * snapshot.stepMillis,
                    checkNotNull(currentDirection),
                    currentValues.toList(),
                )
        } else {
            suppressed++
        }
        currentStart = -1
        currentDirection = null
        currentValues.clear()
    }

    evaluation.values.forEachIndexed { index, value ->
        checkCancelled()
        val delta = value?.subtract(median)
        val direction = delta?.takeIf { it.signum() != 0 }?.let { if (it.signum() > 0) "increase" else "decrease" }
        val passesDirection =
            direction != null && (anomaly.direction == DiagnosticDirection.EITHER || anomaly.direction.wireName == direction)
        val passesAbsolute = delta != null && delta.abs() >= anomaly.minAbsDelta
        val passesZ = mad.signum() == 0 || (delta != null && delta.abs().multiply(MODIFIED_Z_SCALE) >= anomaly.zThreshold.multiply(mad))
        if (!passesDirection || !passesAbsolute || !passesZ) {
            finish(index)
        } else {
            if (currentStart >= 0 && direction != currentDirection) finish(index)
            if (currentStart < 0) {
                currentStart = index
                currentDirection = direction
            }
            currentValues += value
        }
    }
    finish(evaluation.values.size)

    val findings =
        episodes.map { episode ->
            AnomalyEpisodeFinding(
                id = diagnosticId("anomaly-episode", anomaly.id, anomaly.windowId, episode.fromEpochMillis.toString()),
                ruleId = anomaly.id,
                windowId = anomaly.windowId,
                referenceWindowId = anomaly.referenceWindowId,
                metric = evaluation.metric,
                unit = evaluation.unit,
                entity = evaluation.entity,
                fromEpochMs = episode.fromEpochMillis,
                toEpochMs = episode.toEpochMillis,
                durationMs = episode.toEpochMillis - episode.fromEpochMillis,
                direction = episode.direction,
                referenceMedian = canonicalDecimal(median),
                referenceMad = canonicalDecimal(mad),
                observedMin = canonicalDecimal(episode.values.min()),
                observedMax = canonicalDecimal(episode.values.max()),
                maxAbsDelta = canonicalDecimal(episode.values.maxOf { it.subtract(median).abs() }),
                evidenceId = checkId,
                reasons = if (mad.signum() == 0) listOf("ZERO_MAD") else emptyList(),
            ).toJson()
        }
    val status = if (findings.isEmpty()) "NO_MATERIAL_CHANGE" else "CANDIDATE"
    return AnomalyResult(
        anomalyCheck(
            anomaly,
            checkId,
            referenceWindow,
            evaluationWindow,
            status,
            median,
            mad,
            observedReference.size,
            reference.values.size,
            evaluation.values.count { it != null },
            evaluation.values.size,
            findings.size,
            suppressed,
            reasons,
        ),
        findings,
        suppressed,
    )
}

private fun DiagnosticSignalV1.values(
    snapshot: ResourceSnapshotV1,
    series: Map<String, ResourceSeriesV1>,
    window: ResourceWindowV1,
    load: UtcLoadMetrics,
): SignalSeries =
    when (this) {
        is DiagnosticSignalV1.Resource -> {
            val source = series.getValue(seriesId)
            val start = snapshot.indexAt(window.fromEpochMillis)
            val count = ((window.toEpochMillis - window.fromEpochMillis) / snapshot.stepMillis).toInt()
            SignalSeries(source.metric, source.unit, source.entity, source.values.subList(start, start + count))
        }

        is DiagnosticSignalV1.Load ->
            SignalSeries(metric.wireName, metric.unit, "overall", load.windows.getValue(window.id).map { it.value(metric) })
    }

internal fun anomalyCheck(
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
    AnomalyCheckEvidence(
        id = id,
        ruleId = anomaly.id,
        windowId = evaluation.id,
        referenceWindowId = reference.id,
        status = status,
        referenceMedian = median?.let { canonicalDecimal(it) },
        referenceMad = mad?.let { canonicalDecimal(it) },
        referenceObservedCells = referenceObserved,
        referenceExpectedCells = referenceExpected,
        observedCells = observed,
        expectedCells = expected,
        episodesReported = episodes,
        suppressedShortEpisodes = suppressed,
        reasons = reasons.distinct(),
    ).toJson()

internal fun windowMetricSummary(
    window: ResourceWindowV1,
    metrics: NormalizedMetrics,
    resources: List<ResourceSeriesV1>,
): JsonObject {
    val overall = metrics.overall
    val hasSamples = overall.sampleCount > 0L
    return WindowMetricSummaryEvidence(
        id = "window-metric-summary-${sha256Hex(window.id.encodeToByteArray())}",
        windowId = window.id,
        fromEpochMs = window.fromEpochMillis,
        toEpochMs = window.toEpochMillis,
        sampleCount = overall.sampleCount,
        errorCount = overall.errorCount,
        errorRateRatio = overall.errorRate?.document(),
        throughputRps = overall.throughputRps.document(),
        latencyMs =
            NullableLatencyDocument(
                p50 = if (hasSamples) overall.latency.p50Millis else null,
                p95 = if (hasSamples) overall.latency.p95Millis else null,
                p99 = if (hasSamples) overall.latency.p99Millis else null,
                max = if (hasSamples) overall.latency.maxMillis else null,
            ),
        resourceBindings =
            resources.sortedBy(ResourceSeriesV1::id).map { resource ->
                WindowResourceBindingDocument(
                    seriesId = resource.id,
                    metric = resource.metric,
                    unit = resource.unit,
                    entity = resource.entity,
                    role = resource.role.wireName,
                    aggregation = resource.aggregation.wireName,
                    labels = resource.labels,
                )
            },
    ).toJson()
}

private fun ExactRatio.document() = ExactRatioDocument(numerator, denominator)

private fun ExactRatio.decimal(): BigDecimal = BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), DECIMAL_CONTEXT)

private fun median(values: List<BigDecimal>): BigDecimal {
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else sorted[middle - 1].add(sorted[middle]).divide(TWO)
}

private fun ResourceSnapshotV1.indexAt(epochMillis: Long): Int = ((epochMillis - startEpochMillis) / stepMillis).toInt()

internal fun diagnosticSummary(
    status: String,
    pairsTested: Int,
    pairsEvaluable: Int,
    anomaliesTested: Int,
    episodesReported: Int,
    suppressedShortEpisodes: Int,
    reasons: List<String>,
    idSalt: String = "v1",
): JsonObject =
    DiagnosticSummaryEvidence(
        id = diagnosticId("diagnostic-summary", idSalt),
        status = status,
        pairsTested = pairsTested,
        pairsEvaluable = pairsEvaluable,
        anomaliesTested = anomaliesTested,
        episodesReported = episodesReported,
        suppressedShortEpisodes = suppressedShortEpisodes,
        uncertainty = "NOT_ESTIMATED",
        reasons = reasons,
    ).toJson()

internal fun CorrelationHeadlineSelection.evidence(
    sourceCells: Int,
    analysedPoints: Int,
): JsonObject =
    CorrelationHeadlineSelectionEvidence(
        id = diagnosticId("correlation-headline-selection", pairId, windowId),
        pairId = pairId,
        windowId = windowId,
        method = CORRELATION_HEADLINE_METHOD,
        rng = CORRELATION_HEADLINE_RNG,
        status = status.name,
        familyHypotheses = familyHypotheses,
        familyCount = familyCount,
        representation = CORRELATION_HEADLINE_REPRESENTATION,
        sourceCells = sourceCells,
        analysedPoints = analysedPoints,
        bootstrapReplicates = CORRELATION_HEADLINE_REPLICATES,
        blockLengthsCells = CORRELATION_HEADLINE_BLOCKS,
        alpha = decimalString(alpha),
        pValueB10 = pValueBlock10?.let(::decimalString),
        pValueB20 = pValueBlock20?.let(::decimalString),
        maxPValue = maxPValue?.let(::decimalString),
        holmAdjustedPValue = holmAdjustedPValue?.let(::decimalString),
        selected = selected,
        reasons = reasons,
    ).toJson()

private fun decimalString(value: Double): String =
    canonicalDecimal(BigDecimal.valueOf(value).setScale(DECIMAL_SCALE, RoundingMode.HALF_EVEN))

private fun diagnosticId(
    type: String,
    vararg parts: String,
): String = "$type-${sha256Hex(parts.joinToString("\u0000").encodeToByteArray())}"

private const val MIN_PAIRED_CELLS = 30
private const val MIN_REFERENCE_CELLS = 30
private const val RESIDUAL_RELATIVE_EPSILON = 1e-10
private const val DECIMAL_SCALE = 12
private val DECIMAL_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)
private val MODIFIED_Z_SCALE = BigDecimal("0.6745")
private val TWO = BigDecimal("2")

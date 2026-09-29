package io.ltverdict.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

internal const val CORRELATION_HEADLINE_METHOD = "mbb-lag-max-holm.v1"
internal const val CORRELATION_HEADLINE_RNG = "java-random-sha256-seed.v1"
internal const val CORRELATION_HEADLINE_REPLICATES = 999
internal const val CORRELATION_HEADLINE_ALPHA = 0.05
internal val CORRELATION_HEADLINE_BLOCKS = listOf(10, 20)

internal data class CorrelationHeadlineHypothesis(
    val pairId: String,
    val windowId: String,
    val epochs: LongArray,
    val resource: DoubleArray,
    val outcome: DoubleArray,
    val outcomeKey: String,
    val maxLagCells: Int,
    val materialCandidate: Boolean,
    val unavailableReason: String? = null,
)

internal enum class CorrelationHeadlineSelectionStatus {
    SELECTED,
    NOT_SELECTED,
    UNAVAILABLE,
}

internal data class CorrelationHeadlineSelection(
    val pairId: String,
    val windowId: String,
    val status: CorrelationHeadlineSelectionStatus,
    val familyHypotheses: Int,
    val pValueBlock10: Double?,
    val pValueBlock20: Double?,
    val maxPValue: Double?,
    val holmAdjustedPValue: Double?,
    val selected: Boolean,
    val reasons: List<String>,
)

internal fun selectCorrelationHeadlines(
    hypotheses: List<CorrelationHeadlineHypothesis>,
    seedMaterial: String,
    checkCancelled: () -> Unit = {},
): List<CorrelationHeadlineSelection> {
    if (hypotheses.isEmpty()) return emptyList()
    val reasons =
        Array(hypotheses.size) { index ->
            mutableListOf<String>().apply { hypotheses[index].unavailableReason?.let(::add) }
        }
    if (hypotheses.size > MAX_HEADLINE_HYPOTHESES) {
        reasons.forEach { it += "FAMILY_SIZE_UNSUPPORTED" }
        return unavailableSelections(hypotheses, reasons)
    }
    if (hypotheses.map(CorrelationHeadlineHypothesis::windowId).distinct().size != 1) {
        reasons.forEach { it += "MULTI_WINDOW_FAMILY_UNSUPPORTED" }
        return unavailableSelections(hypotheses, reasons)
    }

    hypotheses.indices.filter { reasons[it].isEmpty() }.forEach { index ->
        val hypothesis = hypotheses[index]
        val size = hypothesis.resource.size
        if (hypothesis.epochs.size != size || hypothesis.outcome.size != size || !hypothesis.isContinuous()) {
            reasons[index] += "FAMILY_GRID_MISMATCH"
            return@forEach
        }
        if (size !in MIN_HEADLINE_CELLS..MAX_HEADLINE_CELLS) {
            reasons[index] += "OBSERVATION_COUNT_UNSUPPORTED"
            return@forEach
        }
        if (hypothesis.maxLagCells !in 0..MAX_HEADLINE_LAG_CELLS || size - 2 * hypothesis.maxLagCells < MIN_HEADLINE_CELLS) {
            reasons[index] += "LAG_ANCHOR_COUNT_UNSUPPORTED"
        }
    }

    var active = hypotheses.indices.filter { reasons[it].isEmpty() }
    if (active.isEmpty()) return unavailableSelections(hypotheses, reasons)
    val reference = hypotheses[active.first()]
    if (active.any { !hypotheses[it].epochs.contentEquals(reference.epochs) }) {
        active.forEach { reasons[it] += "FAMILY_GRID_MISMATCH" }
        return unavailableSelections(hypotheses, reasons)
    }
    if (active.any {
            hypotheses[it].outcomeKey != reference.outcomeKey ||
                !hypotheses[it].outcome.contentEquals(reference.outcome)
        }
    ) {
        active.forEach { reasons[it] += "FAMILY_OUTCOME_MISMATCH" }
        return unavailableSelections(hypotheses, reasons)
    }

    val cost =
        2L * CORRELATION_HEADLINE_REPLICATES *
            active.sumOf { index ->
                val hypothesis = hypotheses[index]
                (2L * hypothesis.maxLagCells + 1L) * (hypothesis.resource.size - 2L * hypothesis.maxLagCells)
            }
    if (cost > MAX_HEADLINE_CELL_PRODUCTS) {
        active.forEach { reasons[it] += "COMPUTATION_LIMIT_EXCEEDED" }
        return unavailableSelections(hypotheses, reasons)
    }

    val observed = DoubleArray(hypotheses.size) { Double.NaN }
    active.forEach { index ->
        val hypothesis = hypotheses[index]
        observed[index] = maxAbsLag(hypothesis.resource, hypothesis.outcome, hypothesis.maxLagCells) ?: Double.NaN
        if (!observed[index].isFinite()) reasons[index] += "PAIR_NOT_EVALUABLE"
    }
    active = active.filter { reasons[it].isEmpty() }
    if (active.isEmpty()) return unavailableSelections(hypotheses, reasons)

    val pByBlock = mutableMapOf<Int, DoubleArray>()
    for (block in CORRELATION_HEADLINE_BLOCKS) {
        val calculated =
            bootstrapPValues(hypotheses, active, observed, block, seedMaterial, checkCancelled)
                ?: run {
                    active.forEach { reasons[it] += "BOOTSTRAP_REPLICATE_NOT_EVALUABLE" }
                    return unavailableSelections(hypotheses, reasons)
                }
        pByBlock[block] = calculated
    }

    val p10 = checkNotNull(pByBlock[10])
    val p20 = checkNotNull(pByBlock[20])
    val maxP = DoubleArray(hypotheses.size) { 1.0 }
    active.forEach { index -> maxP[index] = maxOf(p10[index], p20[index]) }
    val adjusted = holm(maxP, hypotheses)
    return hypotheses.mapIndexed { index, hypothesis ->
        if (reasons[index].isNotEmpty()) {
            unavailableSelection(hypothesis, hypotheses.size, reasons[index])
        } else {
            val rejected = adjusted[index] <= CORRELATION_HEADLINE_ALPHA
            val selected = rejected && hypothesis.materialCandidate
            CorrelationHeadlineSelection(
                pairId = hypothesis.pairId,
                windowId = hypothesis.windowId,
                status =
                    if (selected) {
                        CorrelationHeadlineSelectionStatus.SELECTED
                    } else {
                        CorrelationHeadlineSelectionStatus.NOT_SELECTED
                    },
                familyHypotheses = hypotheses.size,
                pValueBlock10 = p10[index],
                pValueBlock20 = p20[index],
                maxPValue = maxP[index],
                holmAdjustedPValue = adjusted[index],
                selected = selected,
                reasons =
                    when {
                        !rejected -> listOf("HOLM_NOT_REJECTED")
                        !hypothesis.materialCandidate -> listOf("MATERIALITY_NOT_MET")
                        else -> emptyList()
                    },
            )
        }
    }
}

private fun bootstrapPValues(
    hypotheses: List<CorrelationHeadlineHypothesis>,
    active: List<Int>,
    observed: DoubleArray,
    block: Int,
    seedMaterial: String,
    checkCancelled: () -> Unit,
): DoubleArray? {
    val size = hypotheses[active.first()].resource.size
    val outcome = hypotheses[active.first()].outcome
    val resourceRandom = seededRandom(seedMaterial, block, "resources")
    val outcomeRandom = seededRandom(seedMaterial, block, "outcome")
    val exceedances = IntArray(hypotheses.size)
    repeat(CORRELATION_HEADLINE_REPLICATES) {
        checkCancelled()
        val resourceIndices = movingBlockIndices(size, block, resourceRandom)
        val outcomeIndices = movingBlockIndices(size, block, outcomeRandom)
        val resampledOutcome = DoubleArray(size) { outcome[outcomeIndices[it]] }
        for (index in active) {
            val hypothesis = hypotheses[index]
            val resampledResource = DoubleArray(size) { hypothesis.resource[resourceIndices[it]] }
            val statistic = maxAbsLag(resampledResource, resampledOutcome, hypothesis.maxLagCells) ?: return null
            if (statistic >= observed[index]) exceedances[index]++
        }
    }
    return DoubleArray(hypotheses.size) { index ->
        if (index in active) {
            (exceedances[index] + 1).toDouble() / (CORRELATION_HEADLINE_REPLICATES + 1)
        } else {
            1.0
        }
    }
}

private fun movingBlockIndices(
    size: Int,
    block: Int,
    random: Random,
): IntArray {
    val result = IntArray(size)
    var offset = 0
    while (offset < size) {
        val start = random.nextInt(size - block + 1)
        var within = 0
        while (within < block && offset < size) {
            result[offset++] = start + within++
        }
    }
    return result
}

private fun seededRandom(
    seedMaterial: String,
    block: Int,
    side: String,
): Random {
    val bytes =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$CORRELATION_HEADLINE_METHOD/$seedMaterial/$block/$side".toByteArray(StandardCharsets.UTF_8))
    var seed = 0L
    repeat(Long.SIZE_BYTES) { seed = (seed shl 8) or (bytes[it].toLong() and 0xffL) }
    return Random(seed)
}

private fun maxAbsLag(
    resource: DoubleArray,
    outcome: DoubleArray,
    maxLag: Int,
): Double? {
    if (resource.size != outcome.size || resource.size - 2 * maxLag < MIN_HEADLINE_CELLS) return null
    val x = ranks(resource)
    val y = ranks(outcome)
    val anchors = resource.size - 2 * maxLag
    var best = 0.0
    for (lag in -maxLag..maxLag) {
        var xMean = 0.0
        var yMean = 0.0
        repeat(anchors) { index ->
            xMean += x[index + maxLag]
            yMean += y[index + maxLag + lag]
        }
        xMean /= anchors
        yMean /= anchors
        var numerator = 0.0
        var xSquares = 0.0
        var ySquares = 0.0
        repeat(anchors) { index ->
            val left = x[index + maxLag] - xMean
            val right = y[index + maxLag + lag] - yMean
            numerator += left * right
            xSquares += left * left
            ySquares += right * right
        }
        val denominator = sqrt(xSquares * ySquares)
        if (denominator == 0.0 || !denominator.isFinite()) return null
        val correlation = numerator / denominator
        if (!correlation.isFinite()) return null
        best = maxOf(best, abs(correlation.coerceIn(-1.0, 1.0)))
    }
    return best
}

private fun ranks(values: DoubleArray): DoubleArray {
    val order = values.indices.sortedWith(compareBy<Int> { values[it] }.thenBy { it })
    val result = DoubleArray(values.size)
    var start = 0
    while (start < order.size) {
        var end = start + 1
        while (end < order.size && values[order[start]] == values[order[end]]) end++
        val rank = (start + 1 + end).toDouble() / 2.0
        for (index in start until end) result[order[index]] = rank
        start = end
    }
    return result
}

private fun holm(
    pValues: DoubleArray,
    hypotheses: List<CorrelationHeadlineHypothesis>,
): DoubleArray {
    val order =
        pValues.indices.sortedWith(
            compareBy<Int> { pValues[it] }
                .thenBy { hypotheses[it].pairId }
                .thenBy { hypotheses[it].windowId },
        )
    val adjusted = DoubleArray(pValues.size) { 1.0 }
    var running = 0.0
    order.forEachIndexed { rank, index ->
        running = maxOf(running, (order.size - rank) * pValues[index])
        adjusted[index] = minOf(1.0, running)
    }
    return adjusted
}

private fun CorrelationHeadlineHypothesis.isContinuous(): Boolean {
    if (epochs.size < 2) return false
    val step = epochs[1] - epochs[0]
    return step > 0L && (2 until epochs.size).all { epochs[it] - epochs[it - 1] == step }
}

private fun unavailableSelections(
    hypotheses: List<CorrelationHeadlineHypothesis>,
    reasons: Array<MutableList<String>>,
): List<CorrelationHeadlineSelection> =
    hypotheses.mapIndexed { index, hypothesis ->
        unavailableSelection(hypothesis, hypotheses.size, reasons[index].distinct())
    }

private fun unavailableSelection(
    hypothesis: CorrelationHeadlineHypothesis,
    familyHypotheses: Int,
    reasons: List<String>,
) = CorrelationHeadlineSelection(
    pairId = hypothesis.pairId,
    windowId = hypothesis.windowId,
    status = CorrelationHeadlineSelectionStatus.UNAVAILABLE,
    familyHypotheses = familyHypotheses,
    pValueBlock10 = null,
    pValueBlock20 = null,
    maxPValue = null,
    holmAdjustedPValue = null,
    selected = false,
    reasons = reasons,
)

private const val MIN_HEADLINE_CELLS = 30
private const val MAX_HEADLINE_CELLS = 240
private const val MAX_HEADLINE_LAG_CELLS = 10
private const val MAX_HEADLINE_HYPOTHESES = 16
private const val MAX_HEADLINE_CELL_PRODUCTS = 150_000_000L

package io.ltverdict.core

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.parseInput
import java.nio.file.Files
import java.nio.file.Path

// ADR 0036: a proposal of load-stages.v1 from the request series of a run. The analysis never calls it; a person checks the
// proposal and passes it to `ltv analyze --stages` as an ordinary explicit input.

/** `requestsPerSecond[i]` counts request starts in `[runStart + 1000 i, runStart + 1000 (i + 1))`. */
internal class LoadSecondSeries(
    val runStartEpochMillis: Long,
    val runEndEpochMillis: Long,
    val requestsPerSecond: LongArray,
    val degraded: Boolean,
)

internal sealed interface LoadSecondSeriesRead {
    data class Series(
        val series: LoadSecondSeries,
    ) : LoadSecondSeriesRead

    /** INPUT_INVALID, NO_REQUEST_SAMPLES or RUN_TOO_LONG. */
    data class Refused(
        val reason: String,
    ) : LoadSecondSeriesRead
}

/**
 * Two reads of the input (ADR 0036, D2): the first finds the run start and end by the rule of the analysis (the earliest start
 * and the latest end of a sample of any kind), the second counts the starts of request samples per second from the run start.
 * Rows of a JTL are not ordered by start, so the counts cannot be laid out in one read. [java.io.IOException] propagates.
 */
internal fun readLoadSecondSeries(
    sourceType: SourceType,
    source: Path,
    checkCancelled: () -> Unit = {},
): LoadSecondSeriesRead {
    val input = AcceptedInput(PROPOSAL_RUN_ID, sourceType, "", Files.size(source), source.fileName.toString(), source)
    var first: Long? = null
    var last: Long? = null
    var requests = false
    val firstReport =
        try {
            parseInput(
                input,
                { sample ->
                    first = minOf(first ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    last = maxOf(last ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                    if (sample.isRequest()) requests = true
                },
                checkCancelled = checkCancelled,
            )
        } catch (_: IllegalArgumentException) {
            return LoadSecondSeriesRead.Refused(REFUSED_INPUT_INVALID)
        }
    if (firstReport.validity == RunValidity.INVALID) return LoadSecondSeriesRead.Refused(REFUSED_INPUT_INVALID)
    val runStart = first
    val runEnd = last
    if (runStart == null || runEnd == null || !requests) return LoadSecondSeriesRead.Refused(REFUSED_NO_REQUEST_SAMPLES)
    if (runEnd - runStart > MAX_LOAD_STAGE_OFFSET_MS) return LoadSecondSeriesRead.Refused(REFUSED_RUN_TOO_LONG)
    val counts = LongArray(((runEnd - runStart) / ONE_SECOND).toInt() + 1)
    var secondFirst: Long? = null
    val secondReport =
        try {
            parseInput(
                input,
                { sample ->
                    secondFirst = minOf(secondFirst ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    if (sample.isRequest()) {
                        val offset = sample.startedAtEpochMillis - runStart
                        if (offset < 0 || offset > runEnd - runStart) throw IllegalStateException(PARSER_PASS_MISMATCH)
                        counts[(offset / ONE_SECOND).toInt()]++
                    }
                },
                checkCancelled = checkCancelled,
            )
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(PARSER_PASS_MISMATCH)
        }
    if (secondReport.validity != firstReport.validity || secondFirst != runStart) throw IllegalStateException(PARSER_PASS_MISMATCH)
    return LoadSecondSeriesRead.Series(LoadSecondSeries(runStart, runEnd, counts, firstReport.validity == RunValidity.DEGRADED))
}

private fun io.ltverdict.ingest.LoadSample.isRequest() = kind == SampleKind.JMETER_SAMPLER || kind == SampleKind.GATLING_REQUEST

internal const val STAGE_PROPOSAL_METHOD = "rps-plateau-segmentation.v1"
private const val PROPOSAL_RUN_ID = "run"
private const val PARSER_PASS_MISMATCH = "PARSER_PASS_MISMATCH"
private const val ONE_SECOND = 1_000L
internal const val REFUSED_INPUT_INVALID = "INPUT_INVALID"
internal const val REFUSED_NO_REQUEST_SAMPLES = "NO_REQUEST_SAMPLES"
internal const val REFUSED_RUN_TOO_LONG = "RUN_TOO_LONG"

/** Step 1 of the method: the first width of the ladder with at most MAX_CELLS full cells and an upper quartile of at least MIN_CELL_COUNT_P75. */
internal fun chooseCellSeconds(series: LoadSecondSeries): Int? {
    val runMillis = series.runEndEpochMillis - series.runStartEpochMillis
    for (width in CELL_LADDER_SECONDS) {
        val cells = runMillis / (ONE_SECOND * width)
        if (cells == 0L || cells > MAX_CELLS) continue
        val sorted = cellCounts(series, width).sortedArray()
        val rank = ((3 * sorted.size + 3) / 4) // ceil(0.75 n), 1-based
        if (sorted[rank - 1] >= MIN_CELL_COUNT_P75) return width
    }
    return null
}

/** Requests per full cell of [cellSeconds]; the remainder at the end is not a cell (it goes to the last stage). */
internal fun cellCounts(
    series: LoadSecondSeries,
    cellSeconds: Int,
): LongArray {
    val cells = ((series.runEndEpochMillis - series.runStartEpochMillis) / (ONE_SECOND * cellSeconds)).toInt()
    val counts = LongArray(cells)
    for (cell in 0 until cells) {
        var sum = 0L
        for (second in cell * cellSeconds until (cell + 1) * cellSeconds) sum = Math.addExact(sum, series.requestsPerSecond[second])
        counts[cell] = sum
    }
    return counts
}

/** Step 2: σ in requests per cell from the median absolute deviation of first differences, never below one request. */
internal fun noiseCounts(counts: LongArray): Double {
    if (counts.size < 2) return NOISE_FLOOR_COUNTS
    val differences = LongArray(counts.size - 1) { Math.subtractExact(counts[it + 1], counts[it]) }
    val median = lowerMedian(differences)
    val mad = lowerMedian(LongArray(differences.size) { Math.abs(Math.subtractExact(differences[it], median)) })
    return maxOf(NOISE_FLOOR_COUNTS, MAD_TO_SIGMA * mad / Math.sqrt(2.0))
}

/**
 * Step 3: the exact minimum of Σ SSE + β · segments over segments of at least MIN_SEGMENT_CELLS cells, as half-open cell
 * ranges. A tie keeps the earliest boundary. [ArithmeticException] means the integer sums overflowed (RATE_OUT_OF_RANGE).
 */
internal fun segmentCounts(
    counts: LongArray,
    noise: Double,
): List<IntRange> {
    val n = counts.size
    if (n < 2 * MIN_SEGMENT_CELLS) return listOf(0 until n)
    val sums = CellSums(counts)
    val penalty = PENALTY_BIC_FACTOR * noise * noise * StrictMath.log(n.toDouble())
    val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
    val previous = IntArray(n + 1) { -1 }
    cost[0] = 0.0
    for (to in MIN_SEGMENT_CELLS..n) {
        var best = cost[0] + sums.fit(0, to).sse + penalty
        var bestFrom = 0
        for (from in MIN_SEGMENT_CELLS..to - MIN_SEGMENT_CELLS) {
            if (cost[from].isInfinite()) continue
            val candidate = cost[from] + sums.fit(from, to).sse + penalty
            if (candidate < best) {
                best = candidate
                bestFrom = from
            }
        }
        cost[to] = best
        previous[to] = bestFrom
    }
    val ranges = ArrayList<IntRange>()
    var to = n
    while (to > 0) {
        val from = previous[to]
        ranges += from until to
        to = from
    }
    return ranges.reversed()
}

/** A least-squares line over the cells of a segment: level is the mean, slope in requests per cell per cell. */
internal class CellFit(
    val from: Int,
    val to: Int,
    val level: Double,
    val slope: Double,
    val sse: Double,
) {
    val cells: Int get() = to - from

    /** The level change over the segment relative to its level (0 when the level is 0). */
    val drift: Double get() = if (level > 0.0) slope * cells / level else 0.0

    val residualCv: Double get() = if (level > 0.0) Math.sqrt(sse / cells) / level else 0.0

    fun lineAt(cell: Int): Double = level + slope * ((cell - from) - (cells - 1) / 2.0)
}

/** Exact prefix sums of the counts centred on their lower median; the SSE is computed in double from the exact sums. */
private class CellSums(
    counts: LongArray,
) {
    private val center = lowerMedian(counts)
    private val sum = LongArray(counts.size + 1)
    private val squares = LongArray(counts.size + 1)
    private val moments = LongArray(counts.size + 1)

    init {
        for (k in counts.indices) {
            val value = Math.subtractExact(counts[k], center)
            sum[k + 1] = Math.addExact(sum[k], value)
            squares[k + 1] = Math.addExact(squares[k], Math.multiplyExact(value, value))
            moments[k + 1] = Math.addExact(moments[k], Math.multiplyExact(k.toLong(), value))
        }
    }

    fun fit(
        from: Int,
        to: Int,
    ): CellFit {
        val m = (to - from).toLong()
        val sy = Math.subtractExact(sum[to], sum[from])
        val syy = Math.subtractExact(squares[to], squares[from])
        val sxy = Math.subtractExact(Math.subtractExact(moments[to], moments[from]), Math.multiplyExact(from.toLong(), sy))
        val sx = m * (m - 1) / 2
        val sxx = (m - 1) * m * (2 * m - 1) / 6
        val mean = sy.toDouble() / m
        val spreadY = syy.toDouble() - sy.toDouble() * sy.toDouble() / m
        val spreadX = sxx.toDouble() - sx.toDouble() * sx.toDouble() / m
        val covariance = sxy.toDouble() - sx.toDouble() * sy.toDouble() / m
        val slope = if (spreadX > 0.0) covariance / spreadX else 0.0
        val sse = if (spreadX > 0.0) spreadY - covariance * covariance / spreadX else spreadY
        return CellFit(from, to, center + mean, slope, maxOf(0.0, sse))
    }
}

private fun lowerMedian(values: LongArray): Long {
    val sorted = values.sortedArray()
    return sorted[(sorted.size - 1) / 2]
}

private val CELL_LADDER_SECONDS = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300)
private const val MAX_CELLS = 2400L
private const val MIN_CELL_COUNT_P75 = 50L
private const val MIN_SEGMENT_CELLS = 3
private const val MAD_TO_SIGMA = 1.4826
private const val NOISE_FLOOR_COUNTS = 1.0
private const val PENALTY_BIC_FACTOR = 3.0

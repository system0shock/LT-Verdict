package io.ltverdict.core

// Steps 1-4 of the method rps-plateau-segmentation.v1 (ADR 0036, D3; plan W4.1, section «Метод»): the cell width, the noise, the
// penalised piecewise-linear segmentation and the classification of the pieces. Split from StageProposal.kt by size only.

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
    // Only differences between loaded cells (both at least STEADY_LEVEL_RATIO of the upper quartile): long low-rate sections
    // (warm-up, tail, idle lead-in) have a smaller spread and would deflate the noise of the plateau. Too few such pairs: all.
    val sorted = counts.sortedArray()
    val loaded = STEADY_LEVEL_RATIO * sorted[(3 * sorted.size + 3) / 4 - 1]
    val pairs = (0 until counts.size - 1).filter { counts[it] >= loaded && counts[it + 1] >= loaded }
    val chosen = if (pairs.size >= MIN_RUN_CELLS) pairs else (0 until counts.size - 1).toList()
    val differences = LongArray(chosen.size) { Math.subtractExact(counts[chosen[it] + 1], counts[chosen[it]]) }
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
internal class CellSums(
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
internal const val MIN_SEGMENT_CELLS = 3
private const val MAD_TO_SIGMA = 1.4826
private const val NOISE_FLOOR_COUNTS = 1.0
private const val PENALTY_BIC_FACTOR = 3.0

internal class Piece(
    val fit: CellFit,
    val kind: StageSegmentKind,
)

internal fun classify(
    fit: CellFit,
    peak: Double,
    cellMillis: Long,
): StageSegmentKind =
    when {
        fit.level <= IDLE_LEVEL_RATIO * peak -> StageSegmentKind.IDLE
        Math.abs(fit.drift) <= MAX_DRIFT_RATIO && fit.residualCv <= MAX_RESIDUAL_CV -> StageSegmentKind.FLAT
        // A hold (ADR 0036, D4): drift is the change over the whole piece, so a long hold whose rate slowly degrades exceeds
        // MAX_DRIFT_RATIO by length alone. A falling piece of at least DROP_MIN_MILLIS that loses at most HOLD_MAX_DRIFT_RATIO of
        // its level is a plateau. Falling only: a slow rise stays a ramp, which D4 excludes.
        fit.slope < 0 &&
            fit.cells * cellMillis >= DROP_MIN_MILLIS &&
            Math.abs(fit.drift) <= HOLD_MAX_DRIFT_RATIO &&
            fit.residualCv <= MAX_RESIDUAL_CV -> StageSegmentKind.FLAT
        fit.residualCv > MAX_RESIDUAL_CV -> StageSegmentKind.UNSTABLE
        fit.slope > 0 -> StageSegmentKind.RISING
        else -> StageSegmentKind.FALLING
    }

/** Step 4, end: neighbouring FLAT pieces of at least MIN_STEADY_MILLIS with indistinguishable levels merge, left to right, to a fixed point. */
internal fun mergeFlat(
    pieces: MutableList<Piece>,
    sums: CellSums,
    noise: Double,
    peak: Double,
    cellMillis: Long,
): List<Piece> {
    var changed = true
    while (changed) {
        changed = false
        var index = 0
        while (index < pieces.size - 1) {
            val a = pieces[index]
            val b = pieces[index + 1]
            // A piece shorter than MIN_STEADY_MILLIS is never merged: a short transition between steps would otherwise join the
            // next plateau and its window (plan W4.1, ruling of task 3).
            if (a.kind == StageSegmentKind.FLAT &&
                b.kind == StageSegmentKind.FLAT &&
                a.fit.cells * cellMillis >= MIN_STEADY_MILLIS &&
                b.fit.cells * cellMillis >= MIN_STEADY_MILLIS &&
                Math.abs(a.fit.level - b.fit.level) <= mergeThreshold(a.fit, b.fit, noise)
            ) {
                val merged = sums.fit(a.fit.from, b.fit.to)
                if (classify(merged, peak, cellMillis) == StageSegmentKind.FLAT) {
                    pieces[index] = Piece(merged, StageSegmentKind.FLAT)
                    pieces.removeAt(index + 1)
                    changed = true
                    continue
                }
            }
            index++
        }
    }
    return pieces
}

internal fun mergeThreshold(
    a: CellFit,
    b: CellFit,
    noise: Double,
): Double =
    maxOf(
        LEVEL_MERGE_RATIO * maxOf(a.level, b.level),
        LEVEL_MERGE_SIGMAS * noise * Math.sqrt(1.0 / a.cells + 1.0 / b.cells),
    )

private const val ONE_SECOND = 1_000L
private const val MAX_DRIFT_RATIO = 0.10
private const val MAX_RESIDUAL_CV = 0.25
internal const val IDLE_LEVEL_RATIO = 0.02
internal const val LEVEL_MERGE_RATIO = 0.05
private const val LEVEL_MERGE_SIGMAS = 4.0
internal const val MIN_STEADY_MILLIS = 30_000L
internal const val DROP_MIN_MILLIS = 60_000L
internal const val STEADY_LEVEL_RATIO = 0.5
internal const val MIN_RUN_CELLS = 12
private const val HOLD_MAX_DRIFT_RATIO = 0.25

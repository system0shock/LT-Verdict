package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * The bootstrap ranks a resample from the tie groups of its source series instead of sorting it again. These tests pin
 * that it gives the same bits as sorting the resample (the reference below is the former `ranks`) and that
 * `selectCorrelationHeadlines` still returns the numbers the sorting version produced.
 */
class CorrelationRanksEquivalenceTest {
    private fun referenceRanks(values: DoubleArray): DoubleArray {
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

    private fun series(
        kind: String,
        size: Int,
        random: Random,
    ): DoubleArray =
        when (kind) {
            "gaussian" -> DoubleArray(size) { random.nextGaussian() }
            "two-levels" -> DoubleArray(size) { random.nextInt(2).toDouble() }
            "three-levels" -> DoubleArray(size) { random.nextInt(3).toDouble() }
            "fifty-levels" -> DoubleArray(size) { random.nextInt(50).toDouble() }
            "constant" -> DoubleArray(size) { 7.0 }
            "runs" -> DoubleArray(size) { (it / 9).toDouble() }
            "signed-zero" -> DoubleArray(size) { listOf(-0.0, 0.0, 1.0, -1.0)[random.nextInt(4)] }
            "infinite" -> DoubleArray(size) { listOf(Double.NEGATIVE_INFINITY, 0.0, 2.5, Double.POSITIVE_INFINITY)[random.nextInt(4)] }
            "nan" -> DoubleArray(size) { if (random.nextInt(5) == 0) Double.NaN else random.nextInt(4).toDouble() }
            else -> error(kind)
        }

    private val kinds =
        listOf("gaussian", "two-levels", "three-levels", "fifty-levels", "constant", "runs", "signed-zero", "infinite", "nan")

    @Test
    fun `resampled ranks equal the ranks of the sorted resample bit for bit`() {
        val random = Random(2026)
        for (kind in kinds) {
            for (size in listOf(30, 61, 240)) {
                val values = series(kind, size, random)
                val order = rankOrder(values)
                repeat(40) {
                    // moving blocks, a plain bootstrap and the identity must all agree with the reference
                    val indices =
                        when (it % 3) {
                            0 -> IntArray(size) { _ -> random.nextInt(size) }
                            1 -> IntArray(size) { i -> (i / 10) * 10 + random.nextInt(minOf(10, size - (i / 10) * 10)) }
                            else -> IntArray(size) { i -> i }
                        }
                    val expected = referenceRanks(DoubleArray(size) { values[indices[it]] })
                    val actual = resampledRanks(values, order, indices)
                    assertEquals(expected.map(Double::toRawBits), actual.map(Double::toRawBits), "$kind size=$size")
                }
            }
        }
    }

    @Test
    fun `headline selection keeps the numbers of the sorting implementation`() {
        assertEquals(GOLDEN, goldenLines())
    }

    internal fun goldenLines(): List<String> {
        val random = Random(77)
        val lines = mutableListOf<String>()
        listOf(
            Triple(30, 3, 0),
            Triple(60, 4, 3),
            Triple(120, 5, 10),
            Triple(240, 3, 4),
            Triple(40, 3, 2),
        ).forEachIndexed { caseIndex, (size, count, lag) ->
            val outcomeKind = listOf("three-levels", "signed-zero", "gaussian", "fifty-levels", "nan")[caseIndex]
            val outcome = series(outcomeKind, size, random)
            val family =
                List(count) { index ->
                    val resource =
                        when {
                            // one hypothesis follows the outcome, so that small p-values and ties meet
                            index == 0 ->
                                DoubleArray(size) {
                                    outcome[it] + (if (outcomeKind == "gaussian") 0.3 * random.nextGaussian() else 0.0)
                                }
                            index == 1 -> series("runs", size, random)
                            else -> series(kinds[(caseIndex * 2 + index * 3) % kinds.size], size, random)
                        }
                    CorrelationHeadlineHypothesis(
                        pairId = "case$caseIndex-h$index",
                        windowId = "evaluation",
                        epochs = LongArray(size) { it * 15_000L },
                        resource = resource,
                        outcome = outcome,
                        outcomeKey = "response_time_p95_ms",
                        maxLagCells = lag,
                        materialCandidate = true,
                    )
                }
            selectCorrelationHeadlines(family, "golden-$caseIndex").forEach {
                lines +=
                    "${it.pairId} ${it.status} ${it.reasons} p10=${it.pValueBlock10} p20=${it.pValueBlock20} " +
                    "max=${it.maxPValue} holm=${it.holmAdjustedPValue}"
            }
        }
        return lines
    }

    private companion object {
        // Captured from the implementation that sorted every resample, under the seed stream of method v2 (K2 changed the method
        // string, which is part of the seed; the v1 numbers of the same families were the previous golden).
        val GOLDEN: List<String> =
            listOf(
                "case0-h0 SELECTED [] p10=0.002 p20=0.01 max=0.01 holm=0.03",
                "case0-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.785 p20=0.734 max=0.785 holm=1.0",
                "case0-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.733 p20=0.776 max=0.776 holm=1.0",
                "case1-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.004",
                "case1-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.03 p20=0.047 max=0.047 holm=0.14100000000000001",
                "case1-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.975 p20=0.969 max=0.975 holm=0.975",
                "case1-h3 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.121 p20=0.091 max=0.121 holm=0.242",
                "case2-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.005",
                "case2-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.636 p20=0.587 max=0.636 holm=1.0",
                "case2-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.759 p20=0.78 max=0.78 holm=1.0",
                "case2-h3 UNAVAILABLE [PAIR_NOT_EVALUABLE] p10=null p20=null max=null holm=null",
                "case2-h4 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.435 p20=0.452 max=0.452 holm=1.0",
                "case3-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.003",
                "case3-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.992 p20=0.988 max=0.992 holm=0.992",
                "case3-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.425 p20=0.43 max=0.43 holm=0.86",
                "case4-h0 SELECTED [] p10=0.001 p20=0.005 max=0.005 holm=0.015",
                "case4-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.292 p20=0.191 max=0.292 holm=0.584",
                "case4-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.292 p20=0.191 max=0.292 holm=0.584",
            )
    }
}

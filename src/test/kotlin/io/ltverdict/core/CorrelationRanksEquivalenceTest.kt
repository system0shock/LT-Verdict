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
        // Captured from the implementation that sorted every resample (before this change).
        val GOLDEN: List<String> =
            listOf(
                "case0-h0 SELECTED [] p10=0.001 p20=0.01 max=0.01 holm=0.03",
                "case0-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.776 p20=0.768 max=0.776 holm=1.0",
                "case0-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.721 p20=0.763 max=0.763 holm=1.0",
                "case1-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.004",
                "case1-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.045 p20=0.04 max=0.045 holm=0.135",
                "case1-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.971 p20=0.961 max=0.971 holm=0.971",
                "case1-h3 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.115 p20=0.077 max=0.115 holm=0.23",
                "case2-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.005",
                "case2-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.624 p20=0.574 max=0.624 holm=1.0",
                "case2-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.749 p20=0.759 max=0.759 holm=1.0",
                "case2-h3 UNAVAILABLE [PAIR_NOT_EVALUABLE] p10=null p20=null max=null holm=null",
                "case2-h4 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.439 p20=0.423 max=0.439 holm=1.0",
                "case3-h0 SELECTED [] p10=0.001 p20=0.001 max=0.001 holm=0.003",
                "case3-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.995 p20=0.989 max=0.995 holm=0.995",
                "case3-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.444 p20=0.474 max=0.474 holm=0.948",
                "case4-h0 SELECTED [] p10=0.001 p20=0.005 max=0.005 holm=0.015",
                "case4-h1 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.313 p20=0.192 max=0.313 holm=0.626",
                "case4-h2 NOT_SELECTED [HOLM_NOT_REJECTED] p10=0.313 p20=0.192 max=0.313 holm=0.626",
            )
    }
}

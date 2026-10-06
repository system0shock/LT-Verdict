package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class CorrelationHeadlineSelectionTest {
    @Test
    fun `fixed Java RNG fixture applies both blocks and one Holm including unavailable hypotheses`() {
        // The arrays are first differences (method v2, ADR 0022, D4). Expected p-values come from the independent
        // oracle tools/correlation_oracle.py: select(levels, "fixture-v2", "first_difference") on the cumulative sums.
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val hypotheses =
            listOf(
                hypothesis("strong", epochs, outcome.copyOf(), outcome),
                hypothesis("noise", epochs, DoubleArray(40) { ((it * 17) % 41).toDouble() }, outcome),
                hypothesis(
                    "partial",
                    epochs,
                    outcome.copyOf(),
                    outcome,
                    unavailableReason = "GENUINE_PARTIAL_UNCALIBRATED",
                ),
            )

        val first = selectCorrelationHeadlines(hypotheses, FIXTURE_SEED).associateBy { it.pairId }
        val replay = selectCorrelationHeadlines(hypotheses, FIXTURE_SEED).associateBy { it.pairId }
        val strong = first.getValue("strong")
        val noise = first.getValue("noise")
        val partial = first.getValue("partial")

        assertEquals(0.001, checkNotNull(strong.pValueBlock10), 1e-12)
        assertEquals(0.013, checkNotNull(strong.pValueBlock20), 1e-12)
        assertEquals(0.013, checkNotNull(strong.maxPValue), 1e-12)
        assertEquals(0.039, checkNotNull(strong.holmAdjustedPValue), 1e-12)
        assertEquals(CorrelationHeadlineSelectionStatus.SELECTED, strong.status)
        assertTrue(strong.selected)
        assertEquals(0.568, checkNotNull(noise.pValueBlock10), 1e-12)
        assertEquals(0.507, checkNotNull(noise.pValueBlock20), 1e-12)
        assertEquals(0.568, checkNotNull(noise.maxPValue), 1e-12)
        assertEquals(1.0, checkNotNull(noise.holmAdjustedPValue), 1e-12)
        assertEquals(listOf("HOLM_NOT_REJECTED"), noise.reasons)
        assertEquals(CorrelationHeadlineSelectionStatus.UNAVAILABLE, partial.status)
        assertNull(partial.pValueBlock10)
        assertNull(partial.holmAdjustedPValue)
        assertFalse(partial.selected)
        assertEquals(listOf("GENUINE_PARTIAL_UNCALIBRATED"), partial.reasons)
        assertEquals(first, replay)
        assertEquals(0.05, strong.alpha, 0.0)
        assertEquals(1, strong.familyCount)
    }

    @Test
    fun `observation cap returns unavailable without bootstrap`() {
        val epochs = LongArray(1_921) { it * 1_000L }
        val values = DoubleArray(1_921) { it.toDouble() }

        val selection = selectCorrelationHeadlines(listOf(hypothesis("too-long", epochs, values, values)), "cap").single()

        assertEquals(CorrelationHeadlineSelectionStatus.UNAVAILABLE, selection.status)
        assertEquals(listOf("OBSERVATION_COUNT_UNSUPPORTED"), selection.reasons)
        assertNull(selection.maxPValue)
        assertFalse(selection.selected)
    }

    @Test
    fun `family level is alpha divided by the declared family count`() {
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val hypotheses = listOf(hypothesis("strong", epochs, outcome.copyOf(), outcome))

        val single = selectCorrelationHeadlines(hypotheses, FIXTURE_SEED).single()
        val three = selectCorrelationHeadlines(hypotheses, FIXTURE_SEED, familyCount = 3).single()
        val five = selectCorrelationHeadlines(hypotheses, FIXTURE_SEED, familyCount = 5).single()

        assertEquals(CorrelationHeadlineSelectionStatus.SELECTED, single.status)
        assertEquals(0.05 / 3, three.alpha, 0.0)
        assertEquals(3, three.familyCount)
        // Holm-adjusted p of the single strong hypothesis is 0.013: rejected at 0.05 and at 0.05 / 3, not at 0.05 / 5.
        assertEquals(CorrelationHeadlineSelectionStatus.SELECTED, three.status)
        assertEquals(CorrelationHeadlineSelectionStatus.NOT_SELECTED, five.status)
        assertEquals(listOf("HOLM_NOT_REJECTED"), five.reasons)
        assertEquals(three.maxPValue, five.maxPValue)
    }

    @Test
    fun `family count that exhausts the bootstrap resolution is unavailable with its own reason`() {
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val sixteen = List(16) { hypothesis("h$it", epochs, outcome.copyOf(), outcome) }

        val exhausted = selectCorrelationHeadlines(sixteen, "resolution", familyCount = 4)

        assertEquals(16, exhausted.size)
        assertTrue(exhausted.all { it.status == CorrelationHeadlineSelectionStatus.UNAVAILABLE })
        assertTrue(exhausted.all { it.reasons == listOf("HOLM_RESOLUTION_INSUFFICIENT") })
        assertTrue(exhausted.all { it.maxPValue == null && it.holmAdjustedPValue == null && !it.selected })
        assertTrue(exhausted.all { it.familyCount == 4 && it.familyHypotheses == 16 })
    }

    @Test
    fun `resolution boundary is family count times family size equal to fifty`() {
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val ten = List(10) { hypothesis("h$it", epochs, outcome.copyOf(), outcome) }

        // F * m = 50: the Holm first-step level 0.05 / 50 equals the smallest bootstrap p 1 / 1000.
        val boundary = selectCorrelationHeadlines(ten, "resolution", familyCount = 5)
        val over = selectCorrelationHeadlines(ten, "resolution", familyCount = 6)

        assertTrue(boundary.none { "HOLM_RESOLUTION_INSUFFICIENT" in it.reasons })
        assertTrue(boundary.all { it.status != CorrelationHeadlineSelectionStatus.UNAVAILABLE })
        assertTrue(over.all { it.reasons == listOf("HOLM_RESOLUTION_INSUFFICIENT") })
    }

    @Test
    fun `resolution is judged on the declared family including unavailable hypotheses`() {
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val family =
            List(15) { hypothesis("h$it", epochs, outcome.copyOf(), outcome) } +
                hypothesis("partial", epochs, outcome.copyOf(), outcome, unavailableReason = "GENUINE_PARTIAL_UNCALIBRATED")

        val selections = selectCorrelationHeadlines(family, "resolution", familyCount = 4)

        assertTrue(selections.all { "HOLM_RESOLUTION_INSUFFICIENT" in it.reasons })
        assertTrue(selections.single { it.pairId == "partial" }.reasons.contains("GENUINE_PARTIAL_UNCALIBRATED"))
    }

    @Test
    fun `stage of 1920 cells is supported and 1921 is not`() {
        val over = selectCorrelationHeadlines(listOf(longHypothesis("h", 1_921, lag = 0)), "limit").single()

        reachBootstrap(listOf(longHypothesis("h", 1_920, lag = 0)))
        assertEquals(CorrelationHeadlineSelectionStatus.UNAVAILABLE, over.status)
        assertEquals(listOf("OBSERVATION_COUNT_UNSUPPORTED"), over.reasons)
    }

    @Test
    fun `stage of 1920 cells runs the whole selection`() {
        val series = noise(1_920, 11)
        val epochs = LongArray(1_920) { it * 15_000L }

        val selection = selectCorrelationHeadlines(listOf(hypothesis("long", epochs, series.copyOf(), series)), "long").single()

        assertEquals(CorrelationHeadlineSelectionStatus.SELECTED, selection.status)
        assertEquals(0.001, checkNotNull(selection.pValueBlock10), 1e-12)
    }

    @Test
    fun `cost ceiling is the ADR 0022 target form sixteen hypotheses lag four 1920 cells`() {
        val target = List(16) { longHypothesis("h$it", 1_920, lag = 4) }
        val previousWorst = List(16) { longHypothesis("h$it", 240, lag = 10) }
        val tooExpensive = List(16) { longHypothesis("h$it", 1_920, lag = 10) }
        val tooManyHypotheses = target + longHypothesis("extra", 1_920, lag = 4)

        assertEquals(550_105_344L, 2L * 999L * 16L * 9L * 1_912L)
        reachBootstrap(target)
        reachBootstrap(previousWorst)
        val unavailable = selectCorrelationHeadlines(tooExpensive, "ceiling")
        assertTrue(unavailable.all { it.reasons == listOf("COMPUTATION_LIMIT_EXCEEDED") })
        assertTrue(unavailable.all { it.maxPValue == null && !it.selected })
        assertTrue(selectCorrelationHeadlines(tooManyHypotheses, "ceiling").all { it.reasons == listOf("FAMILY_SIZE_UNSUPPORTED") })
    }

    @Test
    fun `method v2 seeds its own stream and names the representation of the series it is given`() {
        val epochs = LongArray(40) { it * 1_000L }
        val outcome = DoubleArray(40) { it / 2 + 1.0 }
        val hypotheses = listOf(hypothesis("strong", epochs, outcome.copyOf(), outcome))

        val v2 = selectCorrelationHeadlines(hypotheses, "fixture").single()

        // The same input under the seed stream of method v1 gave 0.012 for block 20 (ADR 0022, D7: the method string is part of the seed).
        assertEquals("mbb-lag-max-holm.v2", CORRELATION_HEADLINE_METHOD)
        assertEquals(0.02, checkNotNull(v2.pValueBlock20), 1e-12)
    }

    private class BootstrapStarted : RuntimeException()

    // The cancellation hook runs only inside the bootstrap, so a throw proves the cost check let the family through.
    private fun reachBootstrap(hypotheses: List<CorrelationHeadlineHypothesis>) {
        assertThrows(BootstrapStarted::class.java) {
            selectCorrelationHeadlines(hypotheses, "ceiling", checkCancelled = { throw BootstrapStarted() })
        }
    }

    private fun longHypothesis(
        id: String,
        cells: Int,
        lag: Int,
    ) = CorrelationHeadlineHypothesis(
        pairId = id,
        windowId = "evaluation",
        epochs = LongArray(cells) { it * 15_000L },
        resource = noise(cells, id.hashCode().toLong()),
        outcome = noise(cells, 99),
        outcomeKey = "response_time_p95_ms",
        maxLagCells = lag,
        materialCandidate = true,
    )

    private fun noise(
        size: Int,
        seed: Long,
    ): DoubleArray {
        val random = Random(seed)
        return DoubleArray(size) { random.nextGaussian() }
    }

    private fun hypothesis(
        id: String,
        epochs: LongArray,
        resource: DoubleArray,
        outcome: DoubleArray,
        unavailableReason: String? = null,
    ) = CorrelationHeadlineHypothesis(
        pairId = id,
        windowId = "evaluation",
        epochs = epochs,
        resource = resource,
        outcome = outcome,
        outcomeKey = "response_time_p95_ms",
        maxLagCells = 0,
        materialCandidate = true,
        unavailableReason = unavailableReason,
    )

    private companion object {
        const val FIXTURE_SEED = "fixture-v2"
    }
}

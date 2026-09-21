package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrelationHeadlineSelectionTest {
    @Test
    fun `fixed Java RNG fixture applies both blocks and one Holm including unavailable hypotheses`() {
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

        val first = selectCorrelationHeadlines(hypotheses, "fixture").associateBy { it.pairId }
        val replay = selectCorrelationHeadlines(hypotheses, "fixture").associateBy { it.pairId }
        val strong = first.getValue("strong")
        val noise = first.getValue("noise")
        val partial = first.getValue("partial")

        assertEquals(0.001, checkNotNull(strong.pValueBlock10), 1e-12)
        assertEquals(0.012, checkNotNull(strong.pValueBlock20), 1e-12)
        assertEquals(0.012, checkNotNull(strong.maxPValue), 1e-12)
        assertEquals(0.036, checkNotNull(strong.holmAdjustedPValue), 1e-12)
        assertEquals(CorrelationHeadlineSelectionStatus.SELECTED, strong.status)
        assertTrue(strong.selected)
        assertEquals(0.614, checkNotNull(noise.pValueBlock10), 1e-12)
        assertEquals(0.549, checkNotNull(noise.pValueBlock20), 1e-12)
        assertEquals(0.614, checkNotNull(noise.maxPValue), 1e-12)
        assertEquals(1.0, checkNotNull(noise.holmAdjustedPValue), 1e-12)
        assertEquals(listOf("HOLM_NOT_REJECTED"), noise.reasons)
        assertEquals(CorrelationHeadlineSelectionStatus.UNAVAILABLE, partial.status)
        assertNull(partial.pValueBlock10)
        assertNull(partial.holmAdjustedPValue)
        assertFalse(partial.selected)
        assertEquals(listOf("GENUINE_PARTIAL_UNCALIBRATED"), partial.reasons)
        assertEquals(first, replay)
    }

    @Test
    fun `observation cap returns unavailable without bootstrap`() {
        val epochs = LongArray(241) { it * 1_000L }
        val values = DoubleArray(241) { it.toDouble() }

        val selection = selectCorrelationHeadlines(listOf(hypothesis("too-long", epochs, values, values)), "cap").single()

        assertEquals(CorrelationHeadlineSelectionStatus.UNAVAILABLE, selection.status)
        assertEquals(listOf("OBSERVATION_COUNT_UNSUPPORTED"), selection.reasons)
        assertNull(selection.maxPValue)
        assertFalse(selection.selected)
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
}

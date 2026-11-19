package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ADR 0036, D14 (owner 2026-11-19): every stretch of DROP_MIN_MILLIS or more that the proposal would exclude after the start of
 * the first steady stage is compared by error ratio and mean latency with a reference step; a degraded stretch is never excluded.
 */
class StageProposalDegradationTest {
    private val collapses =
        listOf(StageProfiles.collapse(600, 20.0), StageProfiles.collapse(300, 40.0), StageProfiles.soakCollapse, StageProfiles.hang)

    @Test
    fun `a collapse at the end stays in the last steady stage`() {
        for (profile in collapses) {
            for (mode in SteadySteps.entries) {
                val proposal = detectLoadStages(profile.series(), mode)

                val last = proposal.stages.last()
                assertEquals(StageRole.STEADY, last.role, "${profile.name} $mode ${explain(proposal)}")
                assertTrue(last.toOffsetMillis >= proposal.cells!! * proposal.cellMillis!!, explain(proposal))
                assertTrue(proposal.warnings.none { it.code == "LONG_RAMP_DOWN" }, explain(proposal))
                val kept = proposal.warnings.single { it.code == "DEGRADED_TAIL_KEPT" }
                assertEquals(last.id, kept.stageId, explain(proposal))
                assertNotNull(kept.stretch, explain(proposal))
                val stretch = kept.stretch!!
                assertEquals(StretchVerdict.DEGRADED, stretch.verdict)
                // The stretch starts at the segment boundary, a little before the fall, so it holds some plateau samples.
                assertTrue(stretch.load.meanMillis >= 1_000.0 && stretch.load.errorRatio >= 0.05, explain(proposal))
                assertTrue(stretch.reference.meanMillis <= 110.0 && stretch.reference.errorRatio <= 0.001, explain(proposal))
                assertTrue(kept.durationMillis!! >= 60_000, explain(proposal))
                assertEquals(StageProposalConfidence.LOW, proposal.confidence, explain(proposal))
            }
        }
    }

    @Test
    fun `a healthy ramp-down is still excluded and judged healthy`() {
        val profiles =
            listOf(60, 300, 900).flatMap { seconds -> (1L..3L).map { StageProfiles.healthyRampDown(seconds, it) } } +
                listOf(300, 900).map { StageProfiles.healthyRampDown(it, 7L, rampLatencyMs = 60) }
        for (profile in profiles) {
            for (mode in SteadySteps.entries) {
                val proposal = detectLoadStages(profile.series(), mode)

                assertEquals("ramp-down", proposal.stages.last().id, "${profile.name} $mode ${explain(proposal)}")
                assertEquals(StageRole.EXCLUDED, proposal.stages.last().role)
                assertTrue(proposal.warnings.none { it.code.startsWith("DEGRADED_") }, "${profile.name} ${explain(proposal)}")
                assertTrue(proposal.stretches.all { it.verdict == StretchVerdict.HEALTHY }, "${profile.name} ${explain(proposal)}")
            }
        }
        // The 900 s ramp-down is long enough to be judged; healthy, its warning stays the warning of the RPS method.
        val long = detectLoadStages(StageProfiles.healthyRampDown(900, 1L).series())
        assertEquals(StretchVerdict.HEALTHY, long.stretches.single().verdict, explain(long))
        assertEquals(null, long.warnings.single { it.code == "LONG_RAMP_DOWN" }.stretch, explain(long))
    }

    @Test
    fun `low-rate runs raise no degraded flag`() {
        val verdicts = ArrayList<StretchVerdict>()
        for (rps in listOf(0.2, 0.5, 1.0, 2.0)) {
            for (seed in 1L..3L) {
                val proposal = detectLoadStages(StageProfiles.lowRate(rps, seed).series())

                // At 0.2 rps the RPS method itself may refuse (300 s cells of about 60 requests); a refusal flags nothing.
                assertTrue(proposal.status == StageProposalStatus.DETECTED || rps < 0.5, "$rps $seed ${explain(proposal)}")
                assertTrue(proposal.warnings.none { it.code.startsWith("DEGRADED_") }, "$rps $seed ${explain(proposal)}")
                assertTrue(proposal.stretches.none { it.verdict == StretchVerdict.DEGRADED }, "$rps $seed ${explain(proposal)}")
                verdicts += proposal.stretches.map { it.verdict }
            }
        }
        // The ramp-downs are judged (and healthy), so the thresholds meet the noise of low rates; too few samples: next test.
        assertTrue(StretchVerdict.HEALTHY in verdicts, "$verdicts")
    }

    @Test
    fun `too few samples keep the behaviour of the RPS method and say so`() {
        // 0.5 rps, then a 360 s fall to zero: about 90 requests, below DEGRADED_MIN_SAMPLES; even a collapse there (2000 ms,
        // 30 % errors) is not judged and stays excluded as before, named by its warning.
        val profile =
            StageProfile(
                "D14 too few samples",
                listOf(RatePiece(600, 0.0, 0.5), RatePiece(10_800, 0.5, 0.5), RatePiece(360, 0.5, 0.0, 2000, 0.30)),
                poissonSeed = 11L,
            )
        val proposal = detectLoadStages(profile.series())

        assertEquals(StageRole.EXCLUDED, proposal.stages.last().role, explain(proposal))
        val warning = proposal.warnings.single { it.code == "LONG_RAMP_DOWN" }
        assertEquals(StretchVerdict.NOT_JUDGED, warning.stretch?.verdict, explain(proposal))
        assertTrue(warning.stretch!!.load.samples < 200, explain(proposal))
        assertEquals(StretchVerdict.NOT_JUDGED, proposal.stretches.single().verdict)
    }

    @Test
    fun `a degraded fall is evaluated however deep and a healthy one behaves as before`() {
        for (loss in listOf(0.45, 0.49, 0.51, 0.60)) {
            val degraded = detectLoadStages(StageProfiles.fall(loss, degraded = true).series())
            val healthy = detectLoadStages(StageProfiles.fall(loss, degraded = false).series())

            // Timeouts of the jittered latency end the run up to about 30 s after the last start: a short idle stop may follow.
            val end = degraded.cells!! * degraded.cellMillis!! - 60_000
            assertTrue(degraded.stages.any { it.role == StageRole.STEADY && it.toOffsetMillis >= end }, "$loss ${explain(degraded)}")
            assertEquals(StageProposalConfidence.LOW, degraded.confidence)
            // Healthy: the stages of the RPS method alone (the same counts without failures and latency, judged healthy).
            val series = StageProfiles.fall(loss, degraded = false).series()
            val rpsOnly =
                detectLoadStages(LoadSecondSeries(series.runStartEpochMillis, series.runEndEpochMillis, series.requestsPerSecond, false))
            assertEquals(rpsOnly.stages, healthy.stages, "$loss ${explain(healthy)}")
            assertTrue(healthy.stretches.all { it.verdict == StretchVerdict.HEALTHY }, "$loss ${explain(healthy)}")
            assertTrue(healthy.warnings.none { it.code.startsWith("DEGRADED_") }, "$loss ${explain(healthy)}")
        }
        // The cliff as the RPS method draws it on these noisy falls: the fitted line of the 49 % fall already loses more than
        // half, so with flat latency 0.49 is excluded like 0.51; with degradation all four are evaluated.
        val excluded =
            listOf(0.45, 0.49, 0.51, 0.60).map { loss ->
                detectLoadStages(StageProfiles.fall(loss, false).series()).stages.last().role
            }
        assertTrue(StageRole.EXCLUDED in excluded, "$excluded")
    }

    @Test
    fun `a degraded decline between steps stays in the window of the higher step`() {
        for (mode in SteadySteps.entries) {
            val proposal = detectLoadStages(StageProfiles.degradedGap.series(), mode)

            val steps = proposal.stages.filter { it.id.startsWith("step-") }
            assertEquals(listOf("step-1", "step-2"), steps.map { it.id }, "$mode ${explain(proposal)}")
            assertEquals(StageRole.STEADY, steps[0].role)
            assertTrue(steps[1].fromOffsetMillis - steps[0].toOffsetMillis < 60_000, "$mode ${explain(proposal)}")
            val kept = proposal.warnings.single { it.code == "DEGRADED_GAP_KEPT" }
            assertEquals("step-1", kept.stageId, explain(proposal))
            assertTrue(kept.durationMillis!! >= 240_000, explain(proposal))
            assertTrue(proposal.warnings.none { it.code == "LOAD_DECLINE_BETWEEN_STEPS" }, explain(proposal))
            assertEquals(StageProposalConfidence.LOW, proposal.confidence)
        }
        // Mode top: the healthy lower step stays excluded and named.
        val top = detectLoadStages(StageProfiles.degradedGap.series(), SteadySteps.TOP)
        assertEquals(StageRole.EXCLUDED, top.stages.single { it.id == "step-2" }.role, explain(top))
        assertTrue(top.warnings.any { it.code == "HOLD_NOT_EVALUATED" && it.stageId == "step-2" }, explain(top))
    }

    @Test
    fun `a degraded lower step in mode top becomes its own steady window`() {
        for (profile in listOf(StageProfiles.stepDownAbrupt, StageProfiles.stepDown120)) {
            val top = detectLoadStages(profile.series(), SteadySteps.TOP)
            val all = detectLoadStages(profile.series(), SteadySteps.ALL)

            assertEquals(listOf("step-1", "step-2"), top.stages.filter { it.role == StageRole.STEADY }.map { it.id }, explain(top))
            assertEquals(all.stages, top.stages, "${profile.name}: the same windows as mode all, never one pooled window")
            val kept = top.warnings.single { it.code == "DEGRADED_STEP_KEPT" }
            assertEquals("step-2", kept.stageId)
            assertTrue(kept.stretch!!.load.meanMillis >= 1_000.0, explain(top))
            assertTrue(top.warnings.none { it.code == "HOLD_NOT_EVALUATED" }, explain(top))
            assertEquals(StageProposalConfidence.LOW, top.confidence)
        }
    }

    @Test
    fun `a collapse and recovery inside steady is unaffected`() {
        // R2: five falls to 60 rps at 2000 ms inside one window; the same rates with flat latency give the same proposal.
        val degraded = StageProfiles.equalDips
        val healthy = degraded.copy(pieces = degraded.pieces.map { it.copy(latencyMs = ELAPSED_MILLIS) })
        for (mode in SteadySteps.entries) {
            val withDips = detectLoadStages(degraded.series(), mode)
            val flat = detectLoadStages(healthy.series(), mode)

            assertEquals(flat.stages, withDips.stages, explain(withDips))
            assertEquals(flat.warnings, withDips.warnings, explain(withDips))
            assertEquals(emptyList<StageStretchFact>(), withDips.stretches, explain(withDips))
        }
    }

    @Test
    fun `a degraded dip that became a gap between equal steps is kept`() {
        for (seed in 1L..4L) {
            val healthy = detectLoadStages(StageProfiles.twoEqual(seed).series())
            val degraded = detectLoadStages(StageProfiles.degradedDip(seed).series())

            val ids = healthy.stages.filter { it.role == StageRole.STEADY }.map { it.id }
            assertEquals(ids, degraded.stages.filter { it.role == StageRole.STEADY }.map { it.id }, "seed $seed ${explain(degraded)}")
            val steady = degraded.stages.filter { it.role == StageRole.STEADY }
            steady.zipWithNext().forEach { (a, b) -> assertTrue(b.fromOffsetMillis - a.toOffsetMillis < 60_000, explain(degraded)) }
            if (healthy.warnings.any { it.code == "LOAD_DECLINE_BETWEEN_STEPS" && it.durationMillis!! >= 60_000 }) {
                assertTrue(degraded.warnings.any { it.code == "DEGRADED_GAP_KEPT" }, "seed $seed ${explain(degraded)}")
            }
        }
    }

    @Test
    fun `the judgement is deterministic`() {
        val first = detectLoadStages(StageProfiles.collapse(600, 20.0).series())
        val second = detectLoadStages(StageProfiles.collapse(600, 20.0).series())

        assertEquals(first, second)
    }

    private fun explain(proposal: StageProposal): String =
        buildString {
            append("\n${proposal.status} ${proposal.reason} ${proposal.detail} ${proposal.confidence} cell=${proposal.cellMillis}\n")
            proposal.segments.forEach {
                append(
                    "  segment ${it.fromOffsetMillis}-${it.toOffsetMillis} ${it.kind} ${decimal(it.levelRps)} ${decimal(it.driftRatio)}\n",
                )
            }
            proposal.stages.forEach { append("  stage ${it.id} ${it.role} ${it.fromOffsetMillis}-${it.toOffsetMillis}\n") }
            proposal.stretches.forEach { append("  stretch $it\n") }
            proposal.warnings.forEach { append("  warning $it\n") }
        }
}

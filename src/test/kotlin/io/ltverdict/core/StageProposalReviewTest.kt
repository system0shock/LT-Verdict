package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The second review round of the coordinator (2026-11-19, findings R1-R6) on Poisson profiles, and the governing rule of
 * ADR 0036, D4: from the start of the first steady stage, every excluded stretch of DROP_MIN_MILLIS or more is named by a
 * warning, and such a proposal is never HIGH.
 */
class StageProposalReviewTest {
    @Test
    fun `R1 a collapse at the end is never excluded`() {
        // Before ADR 0036, D14 (owner 2026-11-19) the collapse was excluded and named LONG_RAMP_DOWN; with latency and errors it
        // stays in the last steady stage and is named DEGRADED_TAIL_KEPT.
        listOf(StageProfiles.collapse(600, 20.0), StageProfiles.collapse(300, 40.0), StageProfiles.soakCollapse, StageProfiles.hang)
            .forEach { profile ->
                val proposal = detectLoadStages(profile.series())

                val last = proposal.stages.last()
                assertEquals(StageRole.STEADY, last.role, explain(proposal))
                val warning = proposal.warnings.single { it.code == "DEGRADED_TAIL_KEPT" }
                assertTrue(warning.atOffsetMillis in last.fromOffsetMillis until last.toOffsetMillis, explain(proposal))
                assertTrue(warning.durationMillis!! >= 60_000 && warning.fromRps != null && warning.toRps != null, explain(proposal))
                assertEquals(StageProposalConfidence.LOW, proposal.confidence, "${profile.name}: ${explain(proposal)}")
            }
    }

    @Test
    fun `R2 equal plateaus split by falls are one window`() {
        for (mode in SteadySteps.entries) {
            val proposal = detectLoadStages(StageProfiles.equalDips.series(), mode)

            val steady = proposal.stages.filter { it.role == StageRole.STEADY }
            assertEquals(1, steady.size, explain(proposal))
            assertTrue(steady.single().toOffsetMillis - steady.single().fromOffsetMillis >= 1_600_000, explain(proposal))
            val drop = proposal.warnings.single { it.code == "LOAD_DROP_INSIDE_STEADY" }
            assertTrue(drop.toRps!! < 70.0, explain(proposal))
            assertEquals(StageProposalConfidence.LOW, proposal.confidence)
        }
    }

    @Test
    fun `R2 the steady stages do not depend on the noise seed`() {
        val sets =
            (1L..4L).map { seed ->
                detectLoadStages(StageProfiles.twoEqual(seed).series(), SteadySteps.TOP)
                    .stages
                    .filter { it.role == StageRole.STEADY }
                    .map { it.id }
            }

        assertEquals(1, sets.toSet().size, "$sets")
    }

    @Test
    fun `R2 an excluded step after a decline is named even before the top`() {
        val proposal = detectLoadStages(StageProfiles.downThenUp.series(), SteadySteps.TOP)

        assertEquals(listOf("step-3"), proposal.stages.filter { it.role == StageRole.STEADY }.map { it.id }, explain(proposal))
        assertEquals(listOf("step-2"), proposal.warnings.filter { it.code == "HOLD_NOT_EVALUATED" }.map { it.stageId }, explain(proposal))
        assertEquals(StageProposalConfidence.LOW, proposal.confidence, explain(proposal))
    }

    @Test
    fun `R3 a rise after the plateau ends the step`() {
        val rise = detectLoadStages(StageProfiles.riseAbort.series())
        val creep = detectLoadStages(StageProfiles.creep.series())
        val riseShift = StageProfiles.riseAbort.firstRequestOffsetMillis()
        val creepShift = StageProfiles.creep.firstRequestOffsetMillis()

        val riseSteady = rise.stages.single { it.role == StageRole.STEADY }
        assertTrue(riseSteady.toOffsetMillis + riseShift <= 660_000 + 3 * rise.cellMillis!!, explain(rise))
        assertTrue(rise.warnings.any { it.atOffsetMillis == riseSteady.toOffsetMillis && it.durationMillis != null }, explain(rise))
        assertNotEquals(StageProposalConfidence.HIGH, rise.confidence)
        // The start of the creep is within the noise of the plateau, so the step may keep it; never past 5 % above the plateau.
        val creepSteady = creep.stages.single { it.role == StageRole.STEADY }
        assertTrue(StageProfiles.creep.trueRpsAt(creepSteady.toOffsetMillis + creepShift - 1) <= 105.0, explain(creep))
        assertTrue(creep.warnings.any { it.code == "RISE_AFTER_STEADY" && it.atOffsetMillis == creepSteady.toOffsetMillis }, explain(creep))
        assertNotEquals(StageProposalConfidence.HIGH, creep.confidence)
    }

    @Test
    fun `R4 a lower plateau is its own step whatever the speed of the change`() {
        listOf(StageProfiles.stepDownAbrupt, StageProfiles.stepDown120, StageProfiles.upDownUp).forEach { profile ->
            val all = detectLoadStages(profile.series(), SteadySteps.ALL)
            val top = detectLoadStages(profile.series(), SteadySteps.TOP)

            val steps = all.stages.filter { it.id.startsWith("step-") }
            assertEquals(profile.truthSteady().size, steps.size, "${profile.name}: ${explain(all)}")
            assertTrue(steps.all { it.role == StageRole.STEADY }, explain(all))
            // The lower plateau of the step-down profiles runs at 1500 ms: since ADR 0036, D14 a degraded lower step is its own
            // steady window in mode top (DEGRADED_STEP_KEPT); the healthy one of upDownUp stays excluded and named.
            val lower = steps.single { it.id == "step-2" }
            val code = if (profile == StageProfiles.upDownUp) "HOLD_NOT_EVALUATED" else "DEGRADED_STEP_KEPT"
            assertEquals(code == "DEGRADED_STEP_KEPT", top.stages.single { it.id == lower.id }.role == StageRole.STEADY, explain(top))
            assertTrue(top.warnings.any { it.code == code && it.stageId == lower.id }, explain(top))
            assertEquals(StageProposalConfidence.LOW, top.confidence)
        }
        val upDownUp = detectLoadStages(StageProfiles.upDownUp.series(), SteadySteps.TOP)
        assertEquals(listOf("step-1", "step-3"), upDownUp.stages.filter { it.role == StageRole.STEADY }.map { it.id })
    }

    @Test
    fun `R5 noise is estimated on the loaded cells`() {
        val proposal = detectLoadStages(StageProfiles.lowRateSections.series())
        val cellSeconds = proposal.cellMillis!! / 1000.0

        // Poisson: the spread of a cell at 100 rps is sqrt(100 W) requests, so the noise in rps is sqrt(100 / W).
        assertTrue(proposal.noiseRps!! >= 0.8 * Math.sqrt(100.0 / cellSeconds), explain(proposal))
        assertTrue(proposal.warnings.none { it.code == "LOAD_DROP_INSIDE_STEADY" }, explain(proposal))
    }

    @Test
    fun `R5 a long lead-in is named by the refusal`() {
        val load = StageProfiles.poisson(StageProfiles.f1).series()
        val lead = 4 * 3_600
        val counts = LongArray(load.requestsPerSecond.size + lead)
        counts[0] = 1
        load.requestsPerSecond.copyInto(counts, lead)
        val proposal = detectLoadStages(LoadSecondSeries(load.runStartEpochMillis - lead * 1000L, load.runEndEpochMillis, counts, false))

        assertEquals(StageProposalStatus.NOT_DETECTED, proposal.status, explain(proposal))
        assertEquals("LEAD_IN_TOO_LONG", proposal.reason, explain(proposal))
        assertTrue(proposal.detail!!.startsWith("lead_in_ms=14400000"), explain(proposal))
    }

    @Test
    fun `R6 a low warm-up that becomes a steady step is warned`() {
        val proposal = detectLoadStages(StageProfiles.warmUp40.series(), SteadySteps.ALL)

        val low = proposal.stages.filter { it.role == StageRole.STEADY }.minBy { it.fromOffsetMillis }
        assertTrue(proposal.warnings.any { it.code == "LOW_LEVEL_STEP" && it.stageId == low.id }, explain(proposal))
        assertNotEquals(StageProposalConfidence.HIGH, proposal.confidence)
    }

    @Test
    fun `no excluded stretch after the first steady start goes unnamed`() {
        val profiles =
            listOf(
                StageProfiles.f1,
                StageProfiles.f2,
                StageProfiles.f3,
                StageProfiles.f5,
                StageProfiles.f6b,
                StageProfiles.f9,
                StageProfiles.f10,
                StageProfiles.f12,
                StageProfiles.f13,
            ).flatMap { listOf(it, StageProfiles.poisson(it)) } +
                listOf(
                    StageProfiles.collapse(600, 20.0),
                    StageProfiles.collapse(300, 40.0),
                    StageProfiles.collapse(300, 60.0),
                    StageProfiles.soakCollapse,
                    StageProfiles.hang,
                    StageProfiles.equalDips,
                    StageProfiles.twoEqual(1),
                    StageProfiles.riseAbort,
                    StageProfiles.creep,
                    StageProfiles.stepDownAbrupt,
                    StageProfiles.stepDown120,
                    StageProfiles.upDownUp,
                    StageProfiles.lowRateSections,
                    StageProfiles.warmUp40,
                )
        for (profile in profiles) {
            for (mode in SteadySteps.entries) {
                val proposal = detectLoadStages(profile.series(), mode)
                if (proposal.status != StageProposalStatus.DETECTED) continue
                val cellsEnd = proposal.cells!! * proposal.cellMillis!!
                val steady = proposal.stages.filter { it.role == StageRole.STEADY }.sortedBy { it.fromOffsetMillis }
                var at = steady.first().fromOffsetMillis
                val excluded = ArrayList<LongRange>()
                for (stage in steady) {
                    if (stage.fromOffsetMillis > at) excluded += at until stage.fromOffsetMillis
                    at = maxOf(at, stage.toOffsetMillis)
                }
                if (cellsEnd > at) excluded += at until cellsEnd
                val long = excluded.filter { it.last + 1 - it.first >= 60_000 }
                long.forEach { stretch ->
                    assertTrue(
                        proposal.warnings.any { it.atOffsetMillis in stretch },
                        "${profile.name} $mode: unnamed $stretch ${explain(proposal)}",
                    )
                }
                if (long.isNotEmpty()) assertNotEquals(StageProposalConfidence.HIGH, proposal.confidence, "${profile.name} $mode")
            }
        }
    }

    private fun explain(proposal: StageProposal): String =
        buildString {
            append("\n${proposal.status} ${proposal.reason} ${proposal.detail} ${proposal.confidence} cell=${proposal.cellMillis} ")
            append("noise_rps=${proposal.noiseRps}\n")
            proposal.segments.forEach {
                append(
                    "  segment ${it.fromOffsetMillis}-${it.toOffsetMillis} ${it.kind} ${decimal(it.levelRps)} ${decimal(it.driftRatio)}\n",
                )
            }
            proposal.stages.forEach { append("  stage ${it.id} ${it.role} ${it.fromOffsetMillis}-${it.toOffsetMillis}\n") }
            proposal.warnings.forEach { append("  warning $it\n") }
        }
}

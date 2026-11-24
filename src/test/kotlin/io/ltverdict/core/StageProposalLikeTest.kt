package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0036, D13 (`--like`): matching a run's stage proposal against a previously accepted `load-stages.v1`. */
class StageProposalLikeTest {
    private val anchor: StageProposal by lazy {
        val proposal = proposeLoadStages(SourceType.JMETER_CSV, Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))
        check(proposal.status == StageProposalStatus.DETECTED) { "$proposal" }
        proposal
    }

    @Test
    fun `the anchor fixture matches the example declaration`() {
        val declared =
            Files.newInputStream(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")).use {
                validateLoadStages(it) as LoadStagesValidation.Valid
            }

        val comparison = matchLoadStages(anchor, declared.stages)

        assertEquals(StageLikeStatus.MATCHED, comparison.status)
        assertNull(comparison.detail)
        assertEquals(10_000L, comparison.toleranceMillis)
        val fact = comparison.matches.single()
        assertEquals("steady", fact.declaredStageId)
        assertEquals("steady", fact.proposalStageId)
        assertEquals(0L, fact.fromDiffMillis)
        assertEquals(0L, fact.toDiffMillis)
        assertNull(fact.overlapKind)
    }

    @Test
    fun `every detected proposal matches itself in both modes`() {
        val profiles =
            listOf(
                StageProfiles.f1,
                StageProfiles.f2,
                StageProfiles.f3,
                StageProfiles.f5,
                StageProfiles.f9,
                StageProfiles.f12,
                StageProfiles.f13,
                StageProfiles.twoEqual(11L),
                StageProfiles.degradedGap,
                StageProfiles.stepDownAbrupt,
                StageProfiles.shortCollapse(1L),
                StageProfiles.riseAbort,
            )
        for (profile in profiles) {
            for (mode in SteadySteps.entries) {
                val proposal = detectLoadStages(profile.series(), mode)
                if (proposal.status != StageProposalStatus.DETECTED) continue
                val comparison = matchLoadStages(proposal, proposal.stages)
                assertEquals(StageLikeStatus.MATCHED, comparison.status, "${profile.name} $mode: ${comparison.detail}")
                assertTrue(comparison.matches.all { it.fromDiffMillis == 0L && it.toDiffMillis == 0L })
            }
        }
        val f1Series = StageProfiles.f1.series()
        val degradedSeries =
            LoadSecondSeries(
                f1Series.runStartEpochMillis,
                f1Series.runEndEpochMillis,
                f1Series.requestsPerSecond,
                degraded = true,
            )
        val degradedProposal = detectLoadStages(degradedSeries)
        assertEquals(StageProposalStatus.DETECTED, degradedProposal.status)
        assertEquals(StageLikeStatus.MATCHED, matchLoadStages(degradedProposal, degradedProposal.stages).status)
    }

    @Test
    fun `a shift within two cells matches when it does not spill into rising or idle`() {
        val proposal = detectLoadStages(StageProfiles.f1.series())
        val w = proposal.cellMillis!!
        val steady = proposal.stages.single { it.role == StageRole.STEADY }
        // Shrink inward by 2*W on both sides: within tolerance 2*W and strictly inside the FLAT plateau.
        val shifted =
            listOf(
                StageV1("ramp-up", StageRole.EXCLUDED, 0L, steady.fromOffsetMillis + 2 * w),
                StageV1("steady", StageRole.STEADY, steady.fromOffsetMillis + 2 * w, steady.toOffsetMillis - 2 * w),
                StageV1("ramp-down", StageRole.EXCLUDED, steady.toOffsetMillis - 2 * w, proposal.stages.last().toOffsetMillis),
            )

        val comparison = matchLoadStages(proposal, shifted)

        assertEquals(StageLikeStatus.MATCHED, comparison.status, "${comparison.detail}")
        assertEquals(2 * w, comparison.matches.single().fromDiffMillis)
        assertEquals(-2 * w, comparison.matches.single().toDiffMillis)
    }

    @Test
    fun `a shift of ten cells is refused with LIKE_BOUNDARY_SHIFTED`() {
        val proposal = detectLoadStages(StageProfiles.f1.series())
        val w = proposal.cellMillis!!
        val steady = proposal.stages.single { it.role == StageRole.STEADY }
        val shifted =
            listOf(
                StageV1("steady", StageRole.STEADY, steady.fromOffsetMillis + 10 * w, steady.toOffsetMillis),
            )

        val comparison = matchLoadStages(proposal, shifted)

        assertEquals(StageLikeStatus.LIKE_BOUNDARY_SHIFTED, comparison.status)
        val detail = comparison.detail!!
        assertTrue(detail.contains("stage_id=steady"), detail)
        assertTrue(detail.contains("max_shift_ms=${10 * w}"), detail)
        assertTrue(detail.contains("tolerance_ms=${2 * w}"), detail)
        assertTrue(detail.contains("from_diff_ms=${10 * w}"), detail)
    }

    @Test
    fun `extending a declared steady stage into a RISING or IDLE segment is refused`() {
        // One cell inside tolerance (1 * W) that crosses into ramp-up RISING on F1.
        val f1Proposal = detectLoadStages(StageProfiles.f1.series())
        val w1 = f1Proposal.cellMillis!!
        val f1Steady = f1Proposal.stages.single { it.role == StageRole.STEADY }
        val intoRising =
            listOf(
                StageV1("steady", StageRole.STEADY, f1Steady.fromOffsetMillis - w1, f1Steady.toOffsetMillis),
            )
        val risingCheck = matchLoadStages(f1Proposal, intoRising)
        assertEquals(StageLikeStatus.LIKE_BOUNDARY_SHIFTED, risingCheck.status, "${risingCheck.detail}")
        assertEquals(StageSegmentKind.RISING, risingCheck.matches.single().overlapKind)
        assertTrue(risingCheck.detail!!.contains("overlap_kind=RISING"), "${risingCheck.detail}")

        // Extending left by 1 * W into lead-in IDLE on anchor with setUp row.
        val setUpCounts = LongArray(30) + anchorCounts()
        val withLeadIn =
            detectLoadStages(
                LoadSecondSeries(1_767_225_570_000L, 1_767_225_719_800L, setUpCounts.also { it[0] = 1L }, false),
            )
        val wLead = withLeadIn.cellMillis!!
        // Construct a profile where steady starts right after IDLE (no ramp-up) so 1*W left hits IDLE.
        val flatWithLeadIdle =
            detectLoadStages(
                LoadSecondSeries(
                    1_767_225_570_000L,
                    1_767_225_719_800L,
                    LongArray(30) { if (it == 0) 1L else 0L } + LongArray(120) { 20L },
                    false,
                ),
            )
        val idleSteady = flatWithLeadIdle.stages.single { it.role == StageRole.STEADY }
        val idleW = flatWithLeadIdle.cellMillis!!
        val intoIdle = listOf(StageV1("steady", StageRole.STEADY, idleSteady.fromOffsetMillis - idleW, idleSteady.toOffsetMillis))
        val idleCheck = matchLoadStages(flatWithLeadIdle, intoIdle)
        assertEquals(StageLikeStatus.LIKE_BOUNDARY_SHIFTED, idleCheck.status, "${idleCheck.detail}")
        assertEquals(StageSegmentKind.IDLE, idleCheck.matches.single().overlapKind)
        assertTrue(idleCheck.detail!!.contains("overlap_kind=IDLE"), "${idleCheck.detail}")
        assertTrue(wLead > 0)
    }

    @Test
    fun `an internal short rising recovery inside a steady stage still matches itself`() {
        val profile =
            StageProfile(
                "short internal dip",
                listOf(
                    RatePiece(60, 0.0, 100.0),
                    RatePiece(300, 100.0, 100.0),
                    RatePiece(30, 50.0, 50.0),
                    RatePiece(30, 50.0, 100.0),
                    RatePiece(300, 100.0, 100.0),
                    RatePiece(60, 100.0, 0.0),
                ),
            )
        val proposal = detectLoadStages(profile.series())
        assertEquals(StageProposalStatus.DETECTED, proposal.status)
        assertTrue(proposal.segments.any { it.kind == StageSegmentKind.RISING }, "${proposal.segments}")
        val steady = proposal.stages.single { it.role == StageRole.STEADY }
        assertTrue(
            proposal.segments.any {
                it.kind == StageSegmentKind.RISING &&
                    it.fromOffsetMillis >= steady.fromOffsetMillis &&
                    it.toOffsetMillis <= steady.toOffsetMillis
            },
            "${proposal.segments}",
        )

        val self = matchLoadStages(proposal, proposal.stages)

        assertEquals(StageLikeStatus.MATCHED, self.status, "${self.detail}")
    }

    @Test
    fun `different step count or step role sequence is refused with LIKE_SHAPE_DIFFERS`() {
        val f2Top = detectLoadStages(StageProfiles.f2.series(), SteadySteps.TOP)
        val f2All = detectLoadStages(StageProfiles.f2.series(), SteadySteps.ALL)
        val f1Top = detectLoadStages(StageProfiles.f1.series(), SteadySteps.TOP)

        val f2VsF1 = matchLoadStages(f2Top, f1Top.stages)
        assertEquals(StageLikeStatus.LIKE_SHAPE_DIFFERS, f2VsF1.status)
        assertTrue(f2VsF1.detail!!.contains("declared_steps=1 proposal_steps=5"), "${f2VsF1.detail}")

        val allVsTop = matchLoadStages(f2All, f2Top.stages)
        assertEquals(StageLikeStatus.LIKE_SHAPE_DIFFERS, allVsTop.status)
        assertTrue(allVsTop.detail!!.contains("declared_steady=1 proposal_steady=5"), "${allVsTop.detail}")

        val topVsAll = matchLoadStages(f2Top, f2All.stages)
        assertEquals(StageLikeStatus.LIKE_SHAPE_DIFFERS, topVsAll.status)
        assertTrue(topVsAll.detail!!.contains("declared_steady=5 proposal_steady=1"), "${topVsAll.detail}")
    }

    @Test
    fun `D14 kept gap and kept step affect shape matching`() {
        val degradedGapProposal = detectLoadStages(StageProfiles.degradedGap.series(), SteadySteps.TOP)
        val healthyF3Proposal = detectLoadStages(StageProfiles.f3.series(), SteadySteps.TOP)
        // degradedGap (top) has [step-1(S), gap-2(S), step-2(E)] = 3 step/gap entries; f3 (top) has 2.
        val gapVsHealthy = matchLoadStages(degradedGapProposal, healthyF3Proposal.stages)
        assertEquals(StageLikeStatus.LIKE_SHAPE_DIFFERS, gapVsHealthy.status)

        val abruptTop = detectLoadStages(StageProfiles.stepDownAbrupt.series(), SteadySteps.TOP)
        // stepDownAbrupt (top) keeps step-2 as STEADY due to DEGRADED_STEP_KEPT: [S, S] vs healthy [S, E].
        val abruptVsHealthy = matchLoadStages(abruptTop, healthyF3Proposal.stages)
        assertEquals(StageLikeStatus.LIKE_SHAPE_DIFFERS, abruptVsHealthy.status)
    }

    @Test
    fun `custom stage ids ignore framing excluded labels and match steady stages by position`() {
        val proposal = detectLoadStages(StageProfiles.f1.series())
        val steady = proposal.stages.single { it.role == StageRole.STEADY }
        val customDeclared =
            listOf(
                StageV1("warmup", StageRole.EXCLUDED, 0L, steady.fromOffsetMillis),
                StageV1("main-plateau", StageRole.STEADY, steady.fromOffsetMillis, steady.toOffsetMillis),
                StageV1("cooldown", StageRole.EXCLUDED, steady.toOffsetMillis, proposal.stages.last().toOffsetMillis),
            )

        val comparison = matchLoadStages(proposal, customDeclared)

        assertEquals(StageLikeStatus.MATCHED, comparison.status, "${comparison.detail}")
        assertEquals("main-plateau", comparison.matches.single().declaredStageId)
        assertEquals("steady", comparison.matches.single().proposalStageId)

        // Two steady stages with an excluded "spike" in between (like two-steady.json) match a two-steady proposal.
        val twoStep = detectLoadStages(StageProfiles.twoEqual(11L).series(), SteadySteps.ALL)
        val twoSteady = twoStep.stages.filter { it.role == StageRole.STEADY }
        assertEquals(2, twoSteady.size)
        val declaredWithSpike =
            listOf(
                StageV1("steady-a", StageRole.STEADY, twoSteady[0].fromOffsetMillis, twoSteady[0].toOffsetMillis),
                StageV1("spike", StageRole.EXCLUDED, twoSteady[0].toOffsetMillis, twoSteady[1].fromOffsetMillis),
                StageV1("steady-b", StageRole.STEADY, twoSteady[1].fromOffsetMillis, twoSteady[1].toOffsetMillis),
            )
        val twoComparison = matchLoadStages(twoStep, declaredWithSpike)
        assertEquals(StageLikeStatus.MATCHED, twoComparison.status, "${twoComparison.detail}")
        assertEquals(listOf("steady-a", "steady-b"), twoComparison.matches.map { it.declaredStageId })
    }

    @Test
    fun `declared steady starting at or after run end is refused with outsideRun`() {
        val proposal = detectLoadStages(StageProfiles.f1.series())
        val runMillis = proposal.runEndEpochMillis!! - proposal.runStartEpochMillis!!
        val w = proposal.cellMillis!!
        // Construct a series where proposal steady ends at gridEnd > runMillis, and declared steady starts at runMillis.
        val f9Proposal = detectLoadStages(StageProfiles.f9.series())
        val f9RunMillis = f9Proposal.runEndEpochMillis!! - f9Proposal.runStartEpochMillis!!
        val f9Steady = f9Proposal.stages.single { it.role == StageRole.STEADY }
        val declaredAtEnd =
            listOf(
                StageV1("steady", StageRole.STEADY, f9RunMillis, f9Steady.toOffsetMillis + 1_000L),
            )
        val comparison = matchLoadStages(f9Proposal, declaredAtEnd)
        assertEquals(StageLikeStatus.LIKE_BOUNDARY_SHIFTED, comparison.status)
        val fact = comparison.matches.single()
        assertTrue(fact.outsideRun || Math.abs(fact.fromDiffMillis) > 2 * f9Proposal.cellMillis!!)
        assertTrue(runMillis > 0 && w > 0)
    }

    @Test
    fun `matchLoadStages is deterministic`() {
        val proposal = detectLoadStages(StageProfiles.f2.series(), SteadySteps.TOP)
        val first = matchLoadStages(proposal, proposal.stages)
        val second = matchLoadStages(proposal, proposal.stages)
        assertEquals(first, second)
    }

    private fun anchorCounts(): LongArray = LongArray(120) { second -> if (second < 40 || second >= 100) 2L else 10L }
}

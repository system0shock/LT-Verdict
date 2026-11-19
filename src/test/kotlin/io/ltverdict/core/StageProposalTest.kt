package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Plan W4.1, tasks 2 and 3: the method rps-plateau-segmentation.v1 (ADR 0036, D3-D7). */
class StageProposalTest {
    private val anchor: LoadSecondSeries by lazy {
        val read = readLoadSecondSeries(SourceType.JMETER_CSV, Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))
        check(read is LoadSecondSeriesRead.Series) { "$read" }
        read.series
    }

    @Test
    fun `the anchor fixture uses five second cells`() {
        assertEquals(5, chooseCellSeconds(anchor))
        assertArrayEquals(LongArray(8) { 10 } + LongArray(12) { 50 } + LongArray(3) { 10 }, cellCounts(anchor, 5))
    }

    @Test
    fun `the cell ladder respects the cell limit`() {
        assertEquals(60, chooseCellSeconds(flatSeries(86_400, 1000)))
        assertEquals(300, chooseCellSeconds(flatSeries(604_800, 1000)))
    }

    @Test
    fun `a slow run has no cell width`() {
        val counts = LongArray(3600) { if (it % 10 == 0) 1L else 0L }

        assertNull(chooseCellSeconds(LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + 3_599_100, counts, false)))
    }

    @Test
    fun `noise is the robust spread of first differences`() {
        assertEquals(1.0, noiseCounts(longArrayOf(50, 50, 50, 90, 90, 90)))
        assertEquals(1.4826 * 2 / Math.sqrt(2.0), noiseCounts(longArrayOf(100, 94, 92, 92, 94, 100)), 1e-9)
        assertEquals(2.0967, noiseCounts(longArrayOf(100, 94, 92, 92, 94, 100)), 1e-4)
    }

    @Test
    fun `segmentation finds the anchor steps`() {
        assertEquals(listOf(0 until 8, 8 until 20, 20 until 23), segmentCounts(cellCounts(anchor, 5), 1.0))
    }

    @Test
    fun `a straight ramp is one segment`() {
        assertEquals(listOf(0 until 200), segmentCounts(LongArray(200) { 2L * it }, 1.0))
    }

    @Test
    fun `segments are never shorter than three cells`() {
        val counts = LongArray(20) { 50 } + LongArray(2) { 90 } + LongArray(20) { 50 }

        val segments = segmentCounts(counts, noiseCounts(counts))

        assertTrue(segments.all { it.last - it.first + 1 >= 3 }, "$segments")
        assertEquals(0, segments.first().first)
        assertEquals(counts.size, segments.last().last + 1)
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    @Test
    fun `the anchor fixture proposal equals the declared example`() {
        val proposal = detectLoadStages(anchor)

        assertEquals(StageProposalStatus.DETECTED, proposal.status, explain(proposal))
        assertArrayEquals(example().canonicalBytes(), proposal.proposalBytes(), explain(proposal))
        assertEquals(StageProposalConfidence.HIGH, proposal.confidence, explain(proposal))
        val steady = proposal.boundaries.single { it.stageId == "steady" }
        assertEquals(40.0, steady.fromContrastSigma)
        assertEquals(40.0, steady.toContrastSigma)
        assertEquals(0, steady.trimmedCells)
        assertEquals(5000L, proposal.cellMillis)
        assertEquals(0.2, proposal.noiseRps)
    }

    @Test
    fun `ramp steady ramp-down`() {
        val profile = StageProfiles.f1
        val proposal = detect(profile)

        assertEquals(listOf("ramp-up", "steady", "ramp-down"), proposal.stages.map { it.id }, explain(proposal))
        assertPlateau(profile, proposal, "steady", profile.truthSteady().single())
        assertEquals(StageProposalConfidence.HIGH, proposal.confidence, explain(proposal))
    }

    @Test
    fun `capacity steps`() {
        val profile = StageProfiles.f2
        val proposal = detect(profile)

        assertEquals(
            listOf("ramp-up", "step-1", "step-2", "step-3", "step-4", "step-5", "ramp-down"),
            proposal.stages.map { it.id },
            explain(proposal),
        )
        profile.truthSteady().forEachIndexed { index, truth -> assertPlateau(profile, proposal, "step-${index + 1}", truth) }
        proposal.stages.zipWithNext().forEach { (a, b) -> assertTrue(a.toOffsetMillis <= b.fromOffsetMillis, explain(proposal)) }
    }

    @Test
    fun `soak with a mid-run drop`() {
        val profile = StageProfiles.f3
        val proposal = detect(profile)
        val w = proposal.cellMillis!!
        val shift = profile.firstRequestOffsetMillis()

        val steady = proposal.stages.single { it.role == StageRole.STEADY }
        assertEquals("steady", steady.id, explain(proposal))
        assertTrue(steady.fromOffsetMillis + shift in 60_000 - w..60_000 + 2 * w, explain(proposal))
        assertTrue(steady.toOffsetMillis + shift in 3_600_000 - 2 * w..3_600_000 + w, explain(proposal))
        val drop = proposal.warnings.single { it.code == "LOAD_DROP_INSIDE_STEADY" }
        assertEquals("steady", drop.stageId)
        // Ruling (task 3): the drop is the start of the transition piece, which is at least three cells long.
        assertTrue(drop.atOffsetMillis + shift in 2_400_000 - 2 * w..2_400_000 + w, explain(proposal))
        assertEquals(StageProposalConfidence.LOW, proposal.confidence, explain(proposal))
    }

    @Test
    fun `continuous ramp`() {
        val proposal = detect(StageProfiles.f4)

        assertRefused(proposal, "NO_PLATEAU")
        assertTrue(proposal.detail!!.startsWith("steady_share="), explain(proposal))
    }

    @Test
    fun `noisy plateau`() {
        val profile = StageProfiles.f5
        val proposal = detect(profile)

        assertEquals(StageProposalStatus.DETECTED, proposal.status, explain(proposal))
        assertPlateau(profile, proposal, "steady", profile.truthSteady().single(), noisy = true, gentle = true)
    }

    @Test
    fun `very short run`() {
        assertRefused(detect(StageProfiles.f6), "RUN_TOO_SHORT")
    }

    @Test
    fun `short but valid run`() {
        val profile = StageProfiles.f6b
        val proposal = detect(profile)

        val steady = proposal.stages.single { it.id == "steady" }
        assertTrue(steady.toOffsetMillis - steady.fromOffsetMillis >= 30_000, explain(proposal))
        assertPlateau(profile, proposal, "steady", profile.truthSteady().single())
    }

    @Test
    fun `setUp lead-in`() {
        val proposal = detectLoadStages(anchorWithSetUp())

        assertEquals(
            listOf(
                StageV1("lead-in", StageRole.EXCLUDED, 0, 30_000),
                StageV1("ramp-up", StageRole.EXCLUDED, 30_000, 70_000),
                StageV1("steady", StageRole.STEADY, 70_000, 130_000),
                StageV1("ramp-down", StageRole.EXCLUDED, 130_000, 150_000),
            ),
            proposal.stages,
            explain(proposal),
        )
        assertEquals(listOf("LEAD_IN_IDLE"), proposal.warnings.map { it.code })
        assertEquals(StageProposalConfidence.MEDIUM, proposal.confidence)
    }

    @Test
    fun `two tests in one file`() {
        val proposal = detect(StageProfiles.f8)

        assertRefused(proposal, "IDLE_GAP_INSIDE_RUN")
        assertEquals("idle_gap_ms=60000", proposal.detail, explain(proposal))
    }

    @Test
    fun `steady to the end`() {
        val profile = StageProfiles.f9
        val proposal = detect(profile)
        val runMillis = proposal.runEndEpochMillis!! - proposal.runStartEpochMillis!!

        val steady = proposal.stages.last()
        assertEquals("steady", steady.id, explain(proposal))
        assertTrue(steady.toOffsetMillis > runMillis, explain(proposal))
        assertEquals(0L, steady.toOffsetMillis % proposal.cellMillis!!)
        assertPlateau(profile, proposal, "steady", profile.truthSteady().single())
    }

    @Test
    fun `long decline at the end`() {
        val profile = StageProfiles.f10
        val proposal = detect(profile)
        val w = proposal.cellMillis!!
        val shift = profile.firstRequestOffsetMillis()

        assertPlateau(profile, proposal, "steady", profile.truthSteady().single(), gentle = true)
        val rampDown = proposal.stages.single { it.id == "ramp-down" }
        assertTrue(rampDown.fromOffsetMillis + shift in 660_000 - 2 * w..660_000 + maxOf(3 * w, 12_000), explain(proposal))
        assertTrue("LONG_RAMP_DOWN" in proposal.warnings.map { it.code }, explain(proposal))
        assertEquals(StageProposalConfidence.LOW, proposal.confidence, explain(proposal))
    }

    @Test
    fun `too many steps`() {
        val proposal = detect(StageProfiles.f11)

        assertRefused(proposal, "TOO_MANY_STAGES")
        assertEquals("stages=18", proposal.detail, explain(proposal))
    }

    @Test
    fun `warm-up below half`() {
        val profile = StageProfiles.f12
        val proposal = detect(profile)

        assertEquals(listOf("ramp-up", "steady", "ramp-down"), proposal.stages.map { it.id }, explain(proposal))
        assertPlateau(profile, proposal, "steady", profile.truthSteady().last())
        assertEquals(0L, proposal.stages.first().fromOffsetMillis)
    }

    @Test
    fun `a ten percent step is two steps, not one window`() {
        val profile =
            StageProfile(
                "10 % capacity step",
                listOf(
                    RatePiece(60, 0.0, 80.0),
                    RatePiece(300, 80.0, 80.0),
                    RatePiece(10, 80.0, 90.0),
                    RatePiece(300, 90.0, 90.0),
                    RatePiece(30, 90.0, 0.0),
                ),
            )
        val proposal = detect(profile, SteadySteps.ALL)

        assertEquals(listOf("ramp-up", "step-1", "step-2", "ramp-down"), proposal.stages.map { it.id }, explain(proposal))
        profile.truthSteady().forEachIndexed { index, truth -> assertPlateau(profile, proposal, "step-${index + 1}", truth) }
    }

    @Test
    fun `every proposal round-trips through the validator`() {
        val proposals =
            listOf(anchor, anchorWithSetUp()).map { detectLoadStages(it) } +
                listOf(StageProfiles.f1, StageProfiles.f2, StageProfiles.f3, StageProfiles.f5, StageProfiles.f6b, StageProfiles.f9)
                    .flatMap { profile -> SteadySteps.entries.map { detect(profile, it) } } +
                listOf(StageProfiles.f10, StageProfiles.f12).map { detect(it) }

        proposals.forEach { proposal ->
            val bytes = proposal.proposalBytes()
            val validation = validateLoadStages(bytes.inputStream())
            assertTrue(validation is LoadStagesValidation.Valid, "${String(bytes)} -> $validation")
            assertArrayEquals(bytes, (validation as LoadStagesValidation.Valid).canonicalBytes())
        }
    }

    @Test
    fun `the proposal is deterministic`() {
        listOf(StageProfiles.f2, StageProfiles.f3, StageProfiles.f5).forEach { profile ->
            val first = detectLoadStages(profile.series())
            val second = detectLoadStages(profile.series())

            assertEquals(first, second)
            assertArrayEquals(first.proposalBytes(), second.proposalBytes())
        }
    }

    @Test
    fun `a single plateau is named steady and several are steps`() {
        assertEquals(listOf("steady"), detect(StageProfiles.f1).stages.filter { it.role == StageRole.STEADY }.map { it.id })
        assertEquals(
            listOf("step-1", "step-2", "step-3", "step-4", "step-5"),
            detect(StageProfiles.f2, SteadySteps.ALL).stages.filter { it.role == StageRole.STEADY }.map { it.id },
        )
    }

    @Test
    fun `the steady steps mode changes only roles`() {
        val all = detect(StageProfiles.f2, SteadySteps.ALL)
        val top = detect(StageProfiles.f2, SteadySteps.TOP)

        assertEquals(all.stages.map { it.copy(role = StageRole.STEADY) }, top.stages.map { it.copy(role = StageRole.STEADY) })
        assertEquals(listOf("step-5"), top.stages.filter { it.role == StageRole.STEADY }.map { it.id }, explain(top))
        assertEquals(
            listOf("ramp-up", "step-1", "step-2", "step-3", "step-4", "ramp-down"),
            top.stages.filter { it.role == StageRole.EXCLUDED }.map { it.id },
        )
        assertEquals(all.segments, top.segments)
        listOf(anchor, StageProfiles.f1.series()).forEach { series ->
            val a = detectLoadStages(series, SteadySteps.ALL)
            val b = detectLoadStages(series, SteadySteps.TOP)
            assertArrayEquals(a.proposalBytes(), b.proposalBytes())
            assertEquals(a.copy(steadySteps = SteadySteps.TOP), b)
        }
    }

    @Test
    fun `refusals carry their reason`() {
        val zeros = LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + 120_000, LongArray(121), false)
        val slow = LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + 3_599_100, LongArray(3600) { if (it % 10 == 0) 1L else 0L }, false)

        val refusals =
            listOf(
                detect(StageProfiles.f4) to "NO_PLATEAU",
                detect(StageProfiles.f6) to "RUN_TOO_SHORT",
                detect(StageProfiles.f8) to "IDLE_GAP_INSIDE_RUN",
                detect(StageProfiles.f11) to "TOO_MANY_STAGES",
                detectLoadStages(zeros) to "NO_REQUEST_SAMPLES",
                detectLoadStages(slow) to "RATE_TOO_LOW",
            )

        refusals.forEach { (proposal, reason) ->
            assertRefused(proposal, reason)
            assertThrows(IllegalStateException::class.java) { proposal.proposalBytes() }
        }
    }

    private fun detect(
        profile: StageProfile,
        steadySteps: SteadySteps = SteadySteps.TOP,
    ) = detectLoadStages(profile.series(), steadySteps)

    private fun example(): LoadStagesValidation.Valid =
        Files.newInputStream(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")).use {
            validateLoadStages(it) as LoadStagesValidation.Valid
        }

    /** The anchor series with the setUp sample of the guide 30 s before the load (plan W4.1, F7). */
    private fun anchorWithSetUp(): LoadSecondSeries {
        val counts = LongArray(anchor.requestsPerSecond.size + 30)
        counts[0] = 1
        anchor.requestsPerSecond.copyInto(counts, 30)
        return LoadSecondSeries(anchor.runStartEpochMillis - 30_000, anchor.runEndEpochMillis, counts, false)
    }

    private fun assertRefused(
        proposal: StageProposal,
        reason: String,
    ) {
        assertEquals(StageProposalStatus.NOT_DETECTED, proposal.status, explain(proposal))
        assertEquals(reason, proposal.reason, explain(proposal))
        assertTrue(proposal.stages.isEmpty())
        assertNull(proposal.confidence)
    }

    /**
     * Plan W4.1, «Допуск границ»: the start and the end of the stage against the generator plateau, in run offsets (the run
     * starts at the first request of the generator), and the true rate in the middle of every cell of the stage.
     */
    private fun assertPlateau(
        profile: StageProfile,
        proposal: StageProposal,
        id: String,
        truth: LongRange,
        noisy: Boolean = false,
        gentle: Boolean = false,
    ) {
        val stage = proposal.stages.singleOrNull { it.id == id } ?: fail("no stage $id: ${explain(proposal)}")
        val w = proposal.cellMillis!!
        val shift = profile.firstRequestOffsetMillis()
        val start = truth.first
        val end = truth.last + 1
        val slack = if (noisy) maxOf(3 * w, (end - start) / 50) else 2 * w
        // Ruling (task 3): next to a gentle slope (F5, F10) the cells within the noise of the plateau cannot be told from it,
        // so the outward bound is the inward one; the true rate of every cell below still guards the window.
        val outward = if (gentle) maxOf(3 * w, (end - start) / 50) else w
        assertTrue(stage.fromOffsetMillis + shift in start - outward..start + slack, "start of $id vs $truth: ${explain(proposal)}")
        assertTrue(stage.toOffsetMillis + shift in end - slack..end + outward, "end of $id vs $truth: ${explain(proposal)}")
        val level = profile.trueRpsAt(start)
        val tolerance = if (noisy) 0.10 else 0.05
        var cell = stage.fromOffsetMillis
        while (cell < stage.toOffsetMillis && cell + shift + w / 2 < profile.seconds * 1000L) {
            val rate = profile.trueRpsAt(cell + shift + w / 2)
            assertTrue(Math.abs(rate - level) <= tolerance * level, "cell $cell of $id has true rate $rate: ${explain(proposal)}")
            cell += w
        }
    }

    private fun explain(proposal: StageProposal): String =
        buildString {
            append("\n${proposal.status} ${proposal.reason} ${proposal.detail} ${proposal.confidence} cell=${proposal.cellMillis} ")
            append("cells=${proposal.cells} noise_rps=${proposal.noiseRps}\n")
            proposal.segments.forEach {
                append(
                    "  segment ${it.fromOffsetMillis}-${it.toOffsetMillis} ${it.kind} level=${decimal(
                        it.levelRps,
                    )} drift=${decimal(it.driftRatio)} cv=${decimal(it.residualCv)}\n",
                )
            }
            proposal.stages.forEach { append("  stage ${it.id} ${it.role} ${it.fromOffsetMillis}-${it.toOffsetMillis}\n") }
            proposal.boundaries.forEach { append("  boundary $it\n") }
            proposal.warnings.forEach { append("  warning $it\n") }
        }

    private fun flatSeries(
        seconds: Int,
        rps: Long,
    ) = LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + seconds * 1000L, LongArray(seconds + 1) { if (it < seconds) rps else 0L }, false)
}

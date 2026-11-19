package io.ltverdict.core

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.parseInput
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path

// ADR 0036: a proposal of load-stages.v1 from the request series of a run. The analysis never calls it; a person checks the
// proposal and passes it to `ltv analyze --stages` as an ordinary explicit input.

/**
 * `requestsPerSecond[i]` counts request starts in `[runStart + 1000 i, runStart + 1000 (i + 1))`; `failuresPerSecond[i]` and
 * `elapsedMillisPerSecond[i]` count the failed ones and sum the `elapsed` of the same samples (ADR 0036, D14).
 */
internal class LoadSecondSeries(
    val runStartEpochMillis: Long,
    val runEndEpochMillis: Long,
    val requestsPerSecond: LongArray,
    val degraded: Boolean,
    val failuresPerSecond: LongArray = LongArray(requestsPerSecond.size),
    val elapsedMillisPerSecond: LongArray = LongArray(requestsPerSecond.size),
)

internal sealed interface LoadSecondSeriesRead {
    data class Series(
        val series: LoadSecondSeries,
    ) : LoadSecondSeriesRead

    /** INPUT_INVALID, NO_REQUEST_SAMPLES, RUN_TOO_LONG, INPUT_CHANGED or RATE_OUT_OF_RANGE (a sum of `elapsed` overflowed). */
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
    // Three arrays of one Long per second: at most 3 · 604801 · 8 bytes, about 14.5 MB for a run of seven days (ADR 0036, D14).
    val seconds = ((runEnd - runStart) / ONE_SECOND).toInt() + 1
    val counts = LongArray(seconds)
    val failures = LongArray(seconds)
    val elapsed = LongArray(seconds)
    var secondFirst: Long? = null
    val secondReport =
        try {
            parseInput(
                input,
                { sample ->
                    secondFirst = minOf(secondFirst ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    if (sample.isRequest()) {
                        val offset = sample.startedAtEpochMillis - runStart
                        if (offset < 0 || offset > runEnd - runStart) throw InputChanged()
                        val second = (offset / ONE_SECOND).toInt()
                        counts[second]++
                        if (!sample.successful) failures[second]++
                        elapsed[second] = Math.addExact(elapsed[second], sample.elapsedMillis)
                    }
                },
                checkCancelled = checkCancelled,
            )
        } catch (_: IllegalArgumentException) {
            return LoadSecondSeriesRead.Refused(REFUSED_INPUT_CHANGED)
        } catch (_: InputChanged) {
            return LoadSecondSeriesRead.Refused(REFUSED_INPUT_CHANGED)
        } catch (_: ArithmeticException) {
            return LoadSecondSeriesRead.Refused(REFUSED_RATE_OUT_OF_RANGE)
        }
    // The input changed between the two reads (a JTL still being written): a refusal, not an internal error.
    if (secondReport.validity != firstReport.validity || secondFirst != runStart) return LoadSecondSeriesRead.Refused(REFUSED_INPUT_CHANGED)
    return LoadSecondSeriesRead.Series(
        LoadSecondSeries(runStart, runEnd, counts, firstReport.validity == RunValidity.DEGRADED, failures, elapsed),
    )
}

private fun io.ltverdict.ingest.LoadSample.isRequest() = kind == SampleKind.JMETER_SAMPLER || kind == SampleKind.GATLING_REQUEST

internal const val STAGE_PROPOSAL_METHOD = "rps-plateau-segmentation.v1"
private const val PROPOSAL_RUN_ID = "run"

private class InputChanged : RuntimeException()

private const val ONE_SECOND = 1_000L
internal const val REFUSED_INPUT_INVALID = "INPUT_INVALID"
internal const val REFUSED_NO_REQUEST_SAMPLES = "NO_REQUEST_SAMPLES"
internal const val REFUSED_RUN_TOO_LONG = "RUN_TOO_LONG"
internal const val REFUSED_INPUT_CHANGED = "INPUT_CHANGED"
internal const val REFUSED_RATE_OUT_OF_RANGE = "RATE_OUT_OF_RANGE"

internal enum class StageProposalStatus { DETECTED, NOT_DETECTED }

internal enum class StageSegmentKind { IDLE, FLAT, RISING, FALLING, UNSTABLE }

internal enum class StageProposalConfidence { HIGH, MEDIUM, LOW }

/** ADR 0036, D12: which plateau steps get the role steady. Default TOP: one window of one load level. */
internal enum class SteadySteps(
    val wireName: String,
) {
    TOP("top"),
    ALL("all"),
}

internal data class StageSegment(
    val fromOffsetMillis: Long,
    val toOffsetMillis: Long,
    val kind: StageSegmentKind,
    val levelRps: Double,
    val driftRatio: Double,
    val residualCv: Double,
)

internal data class StageBoundaryFact(
    val stageId: String,
    val basis: String,
    val levelRps: Double?,
    val fromContrastSigma: Double?,
    val toContrastSigma: Double?,
    val trimmedCells: Int,
)

internal data class StageProposalWarning(
    val code: String,
    val stageId: String?,
    val atOffsetMillis: Long,
    val fromRps: Double? = null,
    val toRps: Double? = null,
    val durationMillis: Long? = null,
)

internal data class StageProposal(
    val status: StageProposalStatus,
    val steadySteps: SteadySteps,
    /** A refusal code of ADR 0036, D5, or null. */
    val reason: String?,
    /** The measured fact of a refusal (`steady_share=0.120`), or null. */
    val detail: String?,
    val confidence: StageProposalConfidence?,
    val runStartEpochMillis: Long?,
    val runEndEpochMillis: Long?,
    val cellMillis: Long?,
    val cells: Int?,
    val noiseRps: Double?,
    val segments: List<StageSegment>,
    /** Empty for a refusal. */
    val stages: List<StageV1>,
    val boundaries: List<StageBoundaryFact>,
    val warnings: List<StageProposalWarning>,
)

/** The canonical bytes of load-stages.v1, the same serialization as [validateLoadStages]; only for a proposal. */
internal fun StageProposal.proposalBytes(): ByteArray {
    check(status == StageProposalStatus.DETECTED) { "NO_PROPOSAL" }
    return canonicalJson(stagesJson(stages))
}

internal fun proposeLoadStages(
    sourceType: SourceType,
    source: Path,
    steadySteps: SteadySteps = SteadySteps.TOP,
    checkCancelled: () -> Unit = {},
): StageProposal =
    when (val read = readLoadSecondSeries(sourceType, source, checkCancelled)) {
        is LoadSecondSeriesRead.Refused -> refusal(steadySteps, read.reason, null)
        is LoadSecondSeriesRead.Series -> detectLoadStages(read.series, steadySteps)
    }

/** Steps 4-13 of the method `rps-plateau-segmentation.v1` (plan W4.1, section «Метод»). Pure: the same series gives the same proposal. */
internal fun detectLoadStages(
    series: LoadSecondSeries,
    steadySteps: SteadySteps = SteadySteps.TOP,
): StageProposal = leadInRefusal(series, detectSeries(series, steadySteps))

private fun detectSeries(
    series: LoadSecondSeries,
    steadySteps: SteadySteps,
): StageProposal {
    val runStart = series.runStartEpochMillis
    val runEnd = series.runEndEpochMillis
    val runMillis = runEnd - runStart
    if (series.requestsPerSecond.all { it == 0L }) return refusal(steadySteps, REFUSED_NO_REQUEST_SAMPLES, null)
    if (runMillis < MIN_RUN_MILLIS) return refusal(steadySteps, "RUN_TOO_SHORT", "run_ms=$runMillis", runStart, runEnd)
    val width =
        chooseCellSeconds(series)
            ?: return refusal(steadySteps, "RATE_TOO_LOW", "requests=${series.requestsPerSecond.sum()}", runStart, runEnd)
    val cellMillis = width * ONE_SECOND
    val counts = cellCounts(series, width)
    val n = counts.size
    val noise = noiseCounts(counts)
    val run = RunFacts(runStart, runEnd, cellMillis, n, noise / width)
    if (n < MIN_RUN_CELLS) return refusal(steadySteps, "RUN_TOO_SHORT", "cells=$n", runStart, runEnd, run)
    return try {
        detectInCells(series, steadySteps, counts, noise, run)
    } catch (_: ArithmeticException) {
        refusal(steadySteps, REFUSED_RATE_OUT_OF_RANGE, null, runStart, runEnd, run)
    }
}

/**
 * A refusal for a low rate or no plateau caused by a long lead-in (setUp hours before the load) says so: the lead-in ends with
 * the last run of at least IDLE_GAP_MIN_MILLIS without requests before 2 % (IDLE_LEVEL_RATIO) of all requests have started.
 */
private fun leadInRefusal(
    series: LoadSecondSeries,
    proposal: StageProposal,
): StageProposal {
    if (proposal.status != StageProposalStatus.NOT_DETECTED ||
        proposal.reason !in setOf("RATE_TOO_LOW", REFUSED_NO_PLATEAU)
    ) {
        return proposal
    }
    val counts = series.requestsPerSecond
    val total = counts.sum()
    var started = 0L
    var zeros = 0
    var leadEnd = 0
    for (second in counts.indices) {
        if (started >= IDLE_LEVEL_RATIO * total) break
        if (counts[second] == 0L) {
            zeros++
        } else {
            if (zeros * ONE_SECOND >= IDLE_GAP_MIN_MILLIS) leadEnd = second
            zeros = 0
        }
        started += counts[second]
    }
    if (leadEnd == 0) return proposal
    val runMillis = series.runEndEpochMillis - series.runStartEpochMillis
    return proposal.copy(
        reason = "LEAD_IN_TOO_LONG",
        detail = "lead_in_ms=${leadEnd * ONE_SECOND} lead_in_share=${decimal(leadEnd * ONE_SECOND.toDouble() / runMillis)}",
    )
}

private class RunFacts(
    val runStart: Long,
    val runEnd: Long,
    val cellMillis: Long,
    val cells: Int,
    val noiseRps: Double,
)

/** A run of plateau pieces `[first, last]` that becomes one stage; [opening] is the plateau that opened it. */
private class Step(
    val first: Int,
    var last: Int,
    val opening: Piece,
)

private fun detectInCells(
    series: LoadSecondSeries,
    steadySteps: SteadySteps,
    counts: LongArray,
    noise: Double,
    run: RunFacts,
): StageProposal {
    val cellMillis = run.cellMillis
    val sums = CellSums(counts)
    val fits = segmentCounts(counts, noise).map { sums.fit(it.first, it.last + 1) }
    val peak = fits.maxOf { it.level }
    val pieces = mergeFlat(fits.map { Piece(it, classify(it, peak, cellMillis)) }.toMutableList(), sums, noise, peak, cellMillis)
    val segments = pieces.map { segmentOf(it, cellMillis) }

    fun refuse(
        reason: String,
        detail: String?,
    ) = refusal(steadySteps, reason, detail, run.runStart, run.runEnd, run, segments)

    // Step 5: candidates for steady.
    val flats = pieces.filter { it.kind == StageSegmentKind.FLAT }
    val maxLevel = flats.maxOfOrNull { it.fit.level } ?: 0.0
    val longest = flats.maxOfOrNull { it.fit.cells } ?: 0
    val candidates =
        pieces.indices.filter { index ->
            val piece = pieces[index]
            piece.kind == StageSegmentKind.FLAT &&
                piece.fit.cells * cellMillis >= MIN_STEADY_MILLIS &&
                (piece.fit.level >= STEADY_LEVEL_RATIO * maxLevel || piece.fit.cells >= STEADY_DURATION_RATIO * longest)
        }
    if (candidates.isEmpty()) return refuse(REFUSED_NO_PLATEAU, "steady_share=${decimal(0.0)}")

    // Step 6: an idle gap between the first and the last plateau looks like two tests in one file.
    val idleGap =
        (candidates.first()..candidates.last())
            .map { pieces[it] }
            .filter { it.kind == StageSegmentKind.IDLE && it.fit.cells * cellMillis >= IDLE_GAP_MIN_MILLIS }
            .maxOfOrNull { it.fit.cells * cellMillis }
    if (idleGap != null) return refuse("IDLE_GAP_INSIDE_RUN", "idle_gap_ms=$idleGap")

    // Step 7: the stop is the longest suffix of idles, short pieces and falls after the last plateau. A long fall belongs to the
    // stop only if it loses more than half of its rate: a long piece that holds its level is never hidden in the ramp-down,
    // it stays in the last steady stage (D4). Whatever goes to the stop is named by a warning (step 13, D4).
    var stopFirst = pieces.size
    while (stopFirst - 1 > candidates.last()) {
        val piece = pieces[stopFirst - 1]
        val stop =
            piece.kind == StageSegmentKind.IDLE ||
                piece.fit.cells * cellMillis < DROP_MIN_MILLIS ||
                (
                    piece.kind == StageSegmentKind.FALLING &&
                        piece.fit.lineAt(piece.fit.to - 1) < STEADY_LEVEL_RATIO * piece.fit.lineAt(piece.fit.from)
                )
        if (!stop) break
        stopFirst--
    }

    fun longRise(piece: Piece) = piece.kind == StageSegmentKind.RISING && piece.fit.cells * cellMillis >= DROP_MIN_MILLIS

    // Step 8: consecutive plateaus at the same level (within the merge threshold) form one step, and everything between them
    // stays in it (a dip is never cut, D4). A plateau at another level opens a new step however fast the level changed: two
    // levels are never pooled into one window (ADR 0030). A rise of DROP_MIN_MILLIS or more always ends a step.
    val steps = ArrayList<Step>()
    var current = Step(candidates.first(), candidates.first(), pieces[candidates.first()])
    for (index in candidates.drop(1)) {
        val next = pieces[index]
        val between = (current.last + 1 until index).map { pieces[it] }
        val sameLevel = Math.abs(next.fit.level - current.opening.fit.level) <= mergeThreshold(current.opening.fit, next.fit, noise)
        if (sameLevel && between.none(::longRise)) {
            current.last = index
        } else {
            steps += current
            current = Step(index, index, next)
        }
    }
    // The last step keeps what follows its last plateau up to the stop (D4), unless a long rise comes first.
    var tailFirst = current.last + 1
    while (tailFirst < stopFirst && !longRise(pieces[tailFirst])) tailFirst++
    current.last = tailFirst - 1
    steps += current
    val riseAfterSteady = (tailFirst until pieces.size).any { longRise(pieces[it]) }

    // Step 9: an edge cell outside the noise of the line of its own piece moves the edge inwards.
    val bounds =
        steps.map { step ->
            var from = pieces[step.first].fit.from
            var to = pieces[step.last].fit.to
            var trimmedFrom = 0
            while (trimmedFrom < EDGE_TRIM_MAX_CELLS && to - from > MIN_SEGMENT_CELLS && outsideNoise(counts, pieces, from, noise)) {
                from++
                trimmedFrom++
            }
            var trimmedTo = 0
            while (trimmedTo < EDGE_TRIM_MAX_CELLS && to - from > MIN_SEGMENT_CELLS && outsideNoise(counts, pieces, to - 1, noise)) {
                to--
                trimmedTo++
            }
            Triple(from, to, trimmedFrom + trimmedTo)
        }

    // Step 10: lead-in, ramp-up, steps, ramp-down on the cell grid; the stage after the last full cell reaches the grid end.
    val gridEnd = ceilDiv(run.runEnd - run.runStart, cellMillis) * cellMillis
    val leadInCells = pieces.takeWhile { it.kind == StageSegmentKind.IDLE }.sumOf { it.fit.cells }.coerceAtMost(bounds.first().first)
    val single = steps.size == 1
    val stages = ArrayList<StageV1>()
    val boundaries = ArrayList<StageBoundaryFact>()
    val warnings = ArrayList<StageProposalWarning>()
    if (leadInCells > 0) {
        stages += StageV1(STAGE_LEAD_IN, StageRole.EXCLUDED, 0, leadInCells * cellMillis)
        boundaries += StageBoundaryFact(STAGE_LEAD_IN, "LEAD_IN_IDLE", null, null, null, 0)
        warnings += StageProposalWarning("LEAD_IN_IDLE", STAGE_LEAD_IN, 0, durationMillis = leadInCells * cellMillis)
    }
    if (bounds.first().first > leadInCells) {
        stages += StageV1(STAGE_RAMP_UP, StageRole.EXCLUDED, leadInCells * cellMillis, bounds.first().first * cellMillis)
        boundaries += StageBoundaryFact(STAGE_RAMP_UP, "BEFORE_FIRST_STEADY", null, null, null, 0)
    }
    // D12, mode top: every step at the level of the highest one (within the merge threshold) is steady, so equal plateaus are
    // never chosen among by noise; a lower step after the first steady one is a hold that top does not evaluate.
    val top = steps.maxBy { it.opening.fit.level }.opening.fit
    val steadyFlags =
        steps.map { step ->
            steadySteps == SteadySteps.ALL || top.level - step.opening.fit.level <= mergeThreshold(top, step.opening.fit, noise)
        }
    val firstSteady = steadyFlags.indexOf(true)
    val steadyStages = ArrayList<Pair<Step, String>>()
    val ids = steps.indices.map { if (single) STAGE_STEADY else "step-${it + 1}" }
    steps.forEachIndexed { index, step ->
        val id = ids[index]
        val steady = steadyFlags[index]
        val (from, to, trimmed) = bounds[index]
        val last = index == steps.lastIndex
        val end = if (last && to == run.cells) gridEnd else to * cellMillis
        stages += StageV1(id, if (steady) StageRole.STEADY else StageRole.EXCLUDED, from * cellMillis, end)
        val firstPiece = pieces[step.first]
        val lastPiece = pieces[step.last]
        boundaries +=
            StageBoundaryFact(
                id,
                if (steady) "PLATEAU" else "LOWER_STEP",
                step.opening.fit.level / (cellMillis / ONE_SECOND),
                pieces.getOrNull(step.first - 1)?.let { Math.abs(firstPiece.fit.level - it.fit.level) / noise },
                pieces.getOrNull(step.last + 1)?.let { Math.abs(lastPiece.fit.level - it.fit.level) / noise },
                trimmed,
            )
        if (steady) steadyStages += step to id
    }
    val lastTo = stages.last().toOffsetMillis
    var tailMillis = 0L
    val tailId = if (riseAfterSteady) STAGE_AFTER_STEADY else STAGE_RAMP_DOWN
    if (lastTo < gridEnd) {
        stages += StageV1(tailId, StageRole.EXCLUDED, lastTo, gridEnd)
        boundaries += StageBoundaryFact(tailId, "AFTER_LAST_STEADY", null, null, null, 0)
        tailMillis = gridEnd - lastTo
    }

    // Step 11: the plateaus must cover enough of the run without its lead-in (all steps, whatever the mode).
    val stepMillis = bounds.sumOf { (from, to, _) -> minOf(to * cellMillis, run.runEnd - run.runStart) - from * cellMillis }
    val share = stepMillis.toDouble() / (run.runEnd - run.runStart - leadInCells * cellMillis)
    if (share < MIN_STEADY_SHARE) return refuse(REFUSED_NO_PLATEAU, "steady_share=${decimal(share)}")
    // Step 12.
    if (stages.size > MAX_LOAD_STAGES) return refuse("TOO_MANY_STAGES", "stages=${stages.size}")

    // Step 13: warnings, then the confidence. From the start of the first steady stage nothing leaves the verdict windows
    // silently (D4): every excluded stretch of DROP_MIN_MILLIS or more after it is named, and such a proposal is never HIGH.
    val perSecond = (cellMillis / ONE_SECOND).toDouble()
    val stepFits = steps.indices.associateWith { sums.fit(bounds[it].first, bounds[it].second) }
    for ((step, id) in steadyStages) {
        val opening = step.opening.fit
        // The largest drop: the lowest fitted rate among the pieces of the step below the opening plateau.
        (step.first..step.last)
            .map { pieces[it].fit }
            .filter { it.level < (1 - LEVEL_MERGE_RATIO) * opening.level }
            .minByOrNull { minOf(it.lineAt(it.from), it.lineAt(it.to - 1)) }
            ?.let {
                warnings +=
                    StageProposalWarning(
                        "LOAD_DROP_INSIDE_STEADY",
                        id,
                        it.from * cellMillis,
                        fromRps = opening.level / perSecond,
                        toRps = maxOf(0.0, minOf(it.lineAt(it.from), it.lineAt(it.to - 1))) / perSecond,
                    )
            }
        // Drift over the whole step, not only its opening plateau.
        val whole = stepFits.getValue(steps.indexOf(step))
        if (Math.abs(whole.drift) > STEADY_DRIFT_WARN_RATIO) {
            warnings +=
                StageProposalWarning(
                    "STEADY_DRIFT",
                    id,
                    whole.from * cellMillis,
                    fromRps = whole.lineAt(whole.from) / perSecond,
                    toRps = whole.lineAt(whole.to - 1) / perSecond,
                )
        }
        if (opening.level < STEADY_LEVEL_RATIO * top.level) {
            warnings +=
                StageProposalWarning("LOW_LEVEL_STEP", id, whole.from * cellMillis, opening.level / perSecond, top.level / perSecond)
        }
    }
    for (index in 1 until steps.size) {
        val previous = steps[index - 1].opening.fit
        val next = steps[index].opening.fit
        val gapFrom = bounds[index - 1].second * cellMillis
        val gapMillis = bounds[index].first * cellMillis - gapFrom
        val dips =
            (steps[index - 1].last + 1 until steps[index].first).any {
                pieces[it].fit.level < (1 - LEVEL_MERGE_RATIO) * minOf(previous.level, next.level)
            }
        if (next.level < previous.level || (dips && gapMillis >= DROP_MIN_MILLIS)) {
            warnings +=
                StageProposalWarning(
                    "LOAD_DECLINE_BETWEEN_STEPS",
                    ids[index],
                    gapFrom,
                    previous.level / perSecond,
                    next.level / perSecond,
                    gapMillis,
                )
        } else if (gapMillis >= DROP_MIN_MILLIS && firstSteady in 0 until index) {
            warnings +=
                StageProposalWarning(
                    "LOAD_RISE_BETWEEN_STEPS",
                    ids[index],
                    gapFrom,
                    previous.level / perSecond,
                    next.level / perSecond,
                    gapMillis,
                )
        }
        // Any excluded step after the first steady one or after a step down is a hold that the verdict does not evaluate.
        if (!steadyFlags[index] && (index > firstSteady || next.level < previous.level)) {
            warnings +=
                StageProposalWarning(
                    "HOLD_NOT_EVALUATED",
                    ids[index],
                    bounds[index].first * cellMillis,
                    fromRps = top.level / perSecond,
                    toRps = next.level / perSecond,
                    durationMillis = stages.single { it.id == ids[index] }.let { it.toOffsetMillis - it.fromOffsetMillis },
                )
        }
    }
    // The tail after the last step: whatever it is (a planned stop, a collapse, a hang, an unfinished rise), from the request
    // rate alone it cannot be told apart, so a long one is always named.
    val tailCellsMillis = run.cells * cellMillis - lastTo
    var longTail = false
    if (tailCellsMillis >= DROP_MIN_MILLIS) {
        val tailPieces = pieces.filter { it.fit.to * cellMillis > lastTo }
        val first = tailPieces.first().fit
        val end = tailPieces.last().fit
        warnings +=
            StageProposalWarning(
                if (riseAfterSteady) "RISE_AFTER_STEADY" else "LONG_RAMP_DOWN",
                tailId,
                lastTo,
                fromRps = maxOf(0.0, first.lineAt(maxOf(first.from, (lastTo / cellMillis).toInt()))) / perSecond,
                toRps = maxOf(0.0, end.lineAt(end.to - 1)) / perSecond,
                durationMillis = tailMillis,
            )
        longTail = tailMillis >= LONG_TAIL_LOW_MILLIS
    }
    if (series.degraded) warnings += StageProposalWarning("DEGRADED_INPUT", null, 0)
    val steadyFacts = boundaries.filter { it.basis == "PLATEAU" }
    val codes = warnings.map { it.code }.toSet()
    val confidence =
        when {
            longTail || codes.any { it in setOf("LOAD_DROP_INSIDE_STEADY", "HOLD_NOT_EVALUATED") } -> StageProposalConfidence.LOW
            codes.any {
                it in
                    setOf(
                        "LEAD_IN_IDLE",
                        "STEADY_DRIFT",
                        "DEGRADED_INPUT",
                        "LOAD_DECLINE_BETWEEN_STEPS",
                        "LOAD_RISE_BETWEEN_STEPS",
                        "LOW_LEVEL_STEP",
                        "LONG_RAMP_DOWN",
                        "RISE_AFTER_STEADY",
                    )
            } ||
                steadyFacts.any { fact ->
                    listOfNotNull(fact.fromContrastSigma, fact.toContrastSigma).any { it < HIGH_EDGE_SIGMAS }
                } ||
                steadyStages.any { (step, _) -> stepFits.getValue(steps.indexOf(step)).residualCv > HIGH_RESIDUAL_CV } ->
                StageProposalConfidence.MEDIUM
            else -> StageProposalConfidence.HIGH
        }
    return StageProposal(
        StageProposalStatus.DETECTED,
        steadySteps,
        null,
        null,
        confidence,
        run.runStart,
        run.runEnd,
        cellMillis,
        run.cells,
        run.noiseRps,
        segments,
        stages,
        boundaries,
        warnings,
    )
}

private fun outsideNoise(
    counts: LongArray,
    pieces: List<Piece>,
    cell: Int,
    noise: Double,
): Boolean {
    val piece = pieces.first { cell >= it.fit.from && cell < it.fit.to }
    return Math.abs(counts[cell] - piece.fit.lineAt(cell)) > EDGE_TOLERANCE_SIGMAS * noise
}

private fun segmentOf(
    piece: Piece,
    cellMillis: Long,
) = StageSegment(
    piece.fit.from * cellMillis,
    piece.fit.to * cellMillis,
    piece.kind,
    piece.fit.level / (cellMillis / ONE_SECOND),
    piece.fit.drift,
    piece.fit.residualCv,
)

private fun refusal(
    steadySteps: SteadySteps,
    reason: String,
    detail: String?,
    runStart: Long? = null,
    runEnd: Long? = null,
    run: RunFacts? = null,
    segments: List<StageSegment> = emptyList(),
) = StageProposal(
    StageProposalStatus.NOT_DETECTED,
    steadySteps,
    reason,
    detail,
    null,
    runStart,
    runEnd,
    run?.cellMillis,
    run?.cells,
    run?.noiseRps,
    segments,
    emptyList(),
    emptyList(),
    emptyList(),
)

private fun ceilDiv(
    value: Long,
    divisor: Long,
) = -Math.floorDiv(-value, divisor)

/** HALF_EVEN, three digits: the number format of the explanation (ADR 0036, D11). */
internal fun decimal(value: Double): String = BigDecimal(value).setScale(3, RoundingMode.HALF_EVEN).toPlainString()

private const val STAGE_LEAD_IN = "lead-in"
private const val STAGE_RAMP_UP = "ramp-up"
private const val STAGE_STEADY = "steady"
private const val STAGE_RAMP_DOWN = "ramp-down"
private const val STAGE_AFTER_STEADY = "after-steady"
private const val REFUSED_NO_PLATEAU = "NO_PLATEAU"
private const val MIN_RUN_MILLIS = 60_000L
private const val STEADY_DURATION_RATIO = 0.75
private const val EDGE_TOLERANCE_SIGMAS = 2.0
private const val EDGE_TRIM_MAX_CELLS = 2
private const val LONG_TAIL_LOW_MILLIS = 300_000L
private const val IDLE_GAP_MIN_MILLIS = 30_000L
private const val MIN_STEADY_SHARE = 0.30
private const val STEADY_DRIFT_WARN_RATIO = 0.05
private const val HIGH_EDGE_SIGMAS = 6.0
private const val HIGH_RESIDUAL_CV = 0.15

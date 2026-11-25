package io.ltverdict.core

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.parseInput
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

// v2 (ADR 0036, D14): the segmentation is still by request rate; v2 adds the latency and error check of excluded stretches.
internal const val STAGE_PROPOSAL_METHOD = "rps-plateau-segmentation.v2"
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
    /** The judged stretch (ADR 0036, D14): on a `DEGRADED_*` warning, or NOT_JUDGED on the warning of the RPS method. */
    val stretch: StageStretchFact? = null,
)

internal enum class StretchVerdict { DEGRADED, HEALTHY, NOT_JUDGED }

/** Request samples, failed ones and the sum of their `elapsed` over a stretch, by start second (ADR 0036, D14). */
internal data class StretchLoad(
    val samples: Long,
    val failures: Long,
    val elapsedMillis: Long,
) {
    val errorRatio: Double get() = if (samples > 0) failures.toDouble() / samples else 0.0
    val meanMillis: Double get() = if (samples > 0) elapsedMillis.toDouble() / samples else 0.0
}

/** A stretch the RPS method would exclude after the start of the first steady stage, judged against [referenceId] (D14). */
internal data class StageStretchFact(
    /** TAIL (ramp-down), AFTER_STEADY (a rise after the last step), GAP (between steps) or STEP (a lower step of mode top). */
    val kind: String,
    val fromOffsetMillis: Long,
    val toOffsetMillis: Long,
    val referenceId: String,
    val load: StretchLoad,
    val reference: StretchLoad,
    val verdict: StretchVerdict,
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
    /** Every judged stretch in time order (ADR 0036, D14); empty for a refusal. */
    val stretches: List<StageStretchFact> = emptyList(),
)

/** The canonical bytes of load-stages.v1, the same serialization as [validateLoadStages]; only for a proposal. */
internal fun StageProposal.proposalBytes(): ByteArray {
    check(status == StageProposalStatus.DETECTED) { "NO_PROPOSAL" }
    return canonicalJson(stagesJson(stages))
}

internal fun stageProposalJson(proposal: StageProposal): JsonObject =
    buildJsonObject {
        put("status", proposal.status.name)
        put("reason", proposal.reason?.let(::JsonPrimitive) ?: JsonNull)
        put("detail", proposal.detail?.let(::JsonPrimitive) ?: JsonNull)
        put("method", STAGE_PROPOSAL_METHOD)
        put("steady_steps", proposal.steadySteps.wireName)
        put("confidence", proposal.confidence?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("calibrated", false)
        put("run_start_epoch_ms", proposal.runStartEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("run_end_epoch_ms", proposal.runEndEpochMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("cell_ms", proposal.cellMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("cells", proposal.cells?.let(::JsonPrimitive) ?: JsonNull)
        put("noise_rps", proposal.noiseRps?.let(::decimalNumber) ?: JsonNull)
        put(
            "segments",
            buildJsonArray {
                proposal.segments.forEach { segment ->
                    add(
                        buildJsonObject {
                            put("from_offset_ms", segment.fromOffsetMillis)
                            put("to_offset_ms", segment.toOffsetMillis)
                            put("kind", segment.kind.name)
                            put("level_rps", decimalNumber(segment.levelRps))
                            put("drift", decimalNumber(segment.driftRatio))
                            put("residual_cv", decimalNumber(segment.residualCv))
                        },
                    )
                }
            },
        )
        put(
            "boundaries",
            buildJsonArray {
                proposal.boundaries.forEach { boundary ->
                    add(
                        buildJsonObject {
                            put("stage_id", boundary.stageId)
                            put("basis", boundary.basis)
                            put("level_rps", boundary.levelRps?.let(::decimalNumber) ?: JsonNull)
                            put("from_contrast_sigma", boundary.fromContrastSigma?.let(::decimalNumber) ?: JsonNull)
                            put("to_contrast_sigma", boundary.toContrastSigma?.let(::decimalNumber) ?: JsonNull)
                            put("trimmed_cells", boundary.trimmedCells)
                        },
                    )
                }
            },
        )
        put(
            "stretches",
            buildJsonArray {
                proposal.stretches.forEach { add(stretchJson(it)) }
            },
        )
        put(
            "warnings",
            buildJsonArray {
                proposal.warnings.forEach { warning ->
                    add(
                        buildJsonObject {
                            put("code", warning.code)
                            put("stage_id", warning.stageId?.let(::JsonPrimitive) ?: JsonNull)
                            put("at_offset_ms", warning.atOffsetMillis)
                            put("from_rps", warning.fromRps?.let(::decimalNumber) ?: JsonNull)
                            put("to_rps", warning.toRps?.let(::decimalNumber) ?: JsonNull)
                            put("duration_ms", warning.durationMillis?.let(::JsonPrimitive) ?: JsonNull)
                            put("stretch", warning.stretch?.let(::stretchJson) ?: JsonNull)
                        },
                    )
                }
            },
        )
        put("proposal", if (proposal.status == StageProposalStatus.DETECTED) stagesJson(proposal.stages) else JsonNull)
    }

private fun stretchJson(stretch: StageStretchFact): JsonObject =
    buildJsonObject {
        put("kind", stretch.kind)
        put("from_offset_ms", stretch.fromOffsetMillis)
        put("to_offset_ms", stretch.toOffsetMillis)
        put("reference_id", stretch.referenceId)
        put("samples", stretch.load.samples)
        put("failures", stretch.load.failures)
        put("error_ratio", decimalNumber(stretch.load.errorRatio))
        put("mean_ms", decimalNumber(stretch.load.meanMillis))
        put("reference_samples", stretch.reference.samples)
        put("reference_failures", stretch.reference.failures)
        put("reference_error_ratio", decimalNumber(stretch.reference.errorRatio))
        put("reference_mean_ms", decimalNumber(stretch.reference.meanMillis))
        put("verdict", stretch.verdict.name)
    }

private fun decimalNumber(value: Double): JsonPrimitive = JsonPrimitive(BigDecimal(value).setScale(3, RoundingMode.HALF_EVEN))

internal enum class StageLikeStatus {
    MATCHED,
    LIKE_SHAPE_DIFFERS,
    LIKE_BOUNDARY_SHIFTED,
}

internal data class StageLikeMatchFact(
    val declaredStageId: String,
    val proposalStageId: String,
    val declaredFromOffsetMillis: Long,
    val declaredToOffsetMillis: Long,
    val proposalFromOffsetMillis: Long,
    val proposalToOffsetMillis: Long,
    val fromDiffMillis: Long,
    val toDiffMillis: Long,
    val overlapKind: StageSegmentKind? = null,
    val overlapFromOffsetMillis: Long? = null,
    val overlapToOffsetMillis: Long? = null,
    val outsideRun: Boolean = false,
)

internal data class StageLikeComparison(
    val status: StageLikeStatus,
    val proposal: StageProposal,
    val toleranceMillis: Long?,
    val detail: String?,
    val matches: List<StageLikeMatchFact>,
)

/**
 * ADR 0036, D13 (`--like`): checks whether a previously accepted [declared] declaration matches [proposal] in step
 * count, role sequence, and steady-stage boundary tolerance (`max(W, EDGE_TRIM_MAX_CELLS * W)`) without spilling
 * into `RISING` or `IDLE` segments outside the matched proposal steady stage.
 */
internal fun matchLoadStages(
    proposal: StageProposal,
    declared: List<StageV1>,
): StageLikeComparison {
    check(proposal.status == StageProposalStatus.DETECTED) { "NO_PROPOSAL" }
    val proposalSteps =
        proposal.stages
            .zip(proposal.boundaries)
            .filter { (_, fact) -> fact.basis !in FRAMING_STAGE_BASES }
            .map { (stage, _) -> stage }
    val declaredSteps =
        declared.filter {
            it.role == StageRole.STEADY || STEP_STAGE_ID.matches(it.id) || GAP_STAGE_ID.matches(it.id)
        }
    if (declaredSteps.size != proposalSteps.size || declaredSteps.map { it.role } != proposalSteps.map { it.role }) {
        val pSteady = proposalSteps.count { it.role == StageRole.STEADY }
        val dSteady = declaredSteps.count { it.role == StageRole.STEADY }
        val roles =
            if (declaredSteps.size == proposalSteps.size) {
                " declared_roles=${declaredSteps.joinToString(",") { it.role.wireName }} " +
                    "proposal_roles=${proposalSteps.joinToString(",") { it.role.wireName }}"
            } else {
                ""
            }
        return StageLikeComparison(
            StageLikeStatus.LIKE_SHAPE_DIFFERS,
            proposal,
            null,
            "declared_steps=${declaredSteps.size} proposal_steps=${proposalSteps.size} " +
                "declared_steady=$dSteady proposal_steady=$pSteady$roles",
            emptyList(),
        )
    }
    val cellMillis = checkNotNull(proposal.cellMillis)
    val toleranceMillis = maxOf(cellMillis, EDGE_TRIM_MAX_CELLS * cellMillis)
    val runMillis = checkNotNull(proposal.runEndEpochMillis) - checkNotNull(proposal.runStartEpochMillis)
    val matches = ArrayList<StageLikeMatchFact>()
    for ((dStage, pStage) in declaredSteps.zip(proposalSteps)) {
        if (pStage.role != StageRole.STEADY) continue
        val fromDiff = dStage.fromOffsetMillis - pStage.fromOffsetMillis
        val toDiff = dStage.toOffsetMillis - pStage.toOffsetMillis
        val outsideRun = dStage.fromOffsetMillis >= runMillis
        var overlapKind: StageSegmentKind? = null
        var overlapFrom: Long? = null
        var overlapTo: Long? = null
        val extensions =
            listOf(
                dStage.fromOffsetMillis to minOf(dStage.toOffsetMillis, pStage.fromOffsetMillis),
                maxOf(dStage.fromOffsetMillis, pStage.toOffsetMillis) to dStage.toOffsetMillis,
            ).filter { (extFrom, extTo) -> extFrom < extTo }
        for (segment in proposal.segments) {
            if (segment.kind != StageSegmentKind.RISING && segment.kind != StageSegmentKind.IDLE) continue
            for ((extFrom, extTo) in extensions) {
                val oFrom = maxOf(extFrom, segment.fromOffsetMillis)
                val oTo = minOf(extTo, segment.toOffsetMillis)
                if (oFrom < oTo) {
                    overlapKind = segment.kind
                    overlapFrom = oFrom
                    overlapTo = oTo
                    break
                }
            }
            if (overlapKind != null) break
        }
        matches +=
            StageLikeMatchFact(
                dStage.id,
                pStage.id,
                dStage.fromOffsetMillis,
                dStage.toOffsetMillis,
                pStage.fromOffsetMillis,
                pStage.toOffsetMillis,
                fromDiff,
                toDiff,
                overlapKind,
                overlapFrom,
                overlapTo,
                outsideRun,
            )
    }
    val firstBad =
        matches.firstOrNull { fact ->
            fact.outsideRun ||
                Math.abs(fact.fromDiffMillis) > toleranceMillis ||
                Math.abs(fact.toDiffMillis) > toleranceMillis ||
                fact.overlapKind != null
        }
    if (firstBad == null) {
        return StageLikeComparison(StageLikeStatus.MATCHED, proposal, toleranceMillis, null, matches)
    }
    val maxShiftMillis = matches.maxOf { maxOf(Math.abs(it.fromDiffMillis), Math.abs(it.toDiffMillis)) }
    val reasonPart =
        when {
            firstBad.outsideRun -> "outside_run=true"
            firstBad.overlapKind != null ->
                "overlap_kind=${firstBad.overlapKind.name} overlap_from_ms=${firstBad.overlapFromOffsetMillis} " +
                    "overlap_to_ms=${firstBad.overlapToOffsetMillis}"
            else -> "from_diff_ms=${firstBad.fromDiffMillis} to_diff_ms=${firstBad.toDiffMillis}"
        }
    val detail = "stage_id=${firstBad.declaredStageId} max_shift_ms=$maxShiftMillis tolerance_ms=$toleranceMillis $reasonPart"
    return StageLikeComparison(StageLikeStatus.LIKE_BOUNDARY_SHIFTED, proposal, toleranceMillis, detail, matches)
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

/** Steps 4-13 of the method `rps-plateau-segmentation.v2` (plan W4.1, section «Метод»). Pure: the same series gives the same proposal. */
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

/** The cells `[from, to)` of a step after the edge trim of step 9. */
private data class Edges(
    val from: Int,
    val to: Int,
    val trimmedFrom: Int,
    val trimmedTo: Int,
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
    // Re-review N2: the stretch from a long rise to the end of the run is a rise after steady only if it ends above the level of
    // the step by the merge threshold (the end of its last long piece with load; idle and short pieces are the stop). A noisy
    // rise at the end of the plateau followed by a fall is part of the step, and the fall is the tail, judged by D14.
    if (tailFirst < stopFirst) {
        val opening = current.opening.fit
        val end =
            (tailFirst until pieces.size)
                .map { pieces[it] }
                .lastOrNull { it.kind != StageSegmentKind.IDLE && it.fit.cells * cellMillis >= DROP_MIN_MILLIS }
                ?.fit
        if (end == null || end.lineAt(end.to - 1) - opening.level <= mergeThreshold(opening, end, noise)) tailFirst = stopFirst
    }
    current.last = tailFirst - 1
    steps += current
    val riseAfterSteady = (tailFirst until pieces.size).any { longRise(pieces[it]) }

    // Step 9: an edge cell outside the noise of the line of its own piece moves the edge inwards.
    val bounds =
        steps
            .map { step ->
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
                Edges(from, to, trimmedFrom, trimmedTo)
            }.toMutableList()

    // D12, mode top: every step at the level of the highest one (within the merge threshold) is steady, so equal plateaus are
    // never chosen among by noise; a lower step after the first steady one is a hold that top does not evaluate.
    val top = steps.maxBy { it.opening.fit.level }.opening.fit
    val steadyFlags =
        steps
            .map { step ->
                steadySteps == SteadySteps.ALL || top.level - step.opening.fit.level <= mergeThreshold(top, step.opening.fit, noise)
            }.toMutableList()
    val firstSteady = steadyFlags.indexOf(true)
    val single = steps.size == 1
    val ids = steps.indices.map { if (single) STAGE_STEADY else "step-${it + 1}" }
    val gridEnd = ceilDiv(run.runEnd - run.runStart, cellMillis) * cellMillis
    val perSecond = (cellMillis / ONE_SECOND).toDouble()

    fun stepEnd(index: Int) = if (index == steps.lastIndex && bounds[index].to == run.cells) gridEnd else bounds[index].to * cellMillis

    // Step 9a (D14): every stretch after the start of the first steady stage that the roles by load would exclude is judged by
    // error ratio and mean latency against a step at the same or a higher load, whatever its duration: the sample count decides
    // whether it can be judged (re-review N1: a 50 s collapse of 2000 requests was excluded silently). DROP_MIN_MILLIS keeps
    // governing only the naming of healthy stretches: a shorter one that is not degraded is not reported, as before. References
    // and stretches are taken from the layout by load alone, before any decision, so a kept stretch never becomes a reference.
    fun judged(
        kind: String,
        from: Long,
        to: Long,
        reference: Int,
        namedMillis: Long = to - from,
    ): StageStretchFact? {
        if (to <= from) return null
        val load = stretchLoad(series, from, to)
        val referenceFrom = bounds[reference].from * cellMillis
        val referenceLoad = stretchLoad(series, referenceFrom, stepEnd(reference))
        // The stop of a test fails the requests in flight: about the reference rate times its mean latency. Failures of a tail
        // within STOP_IN_FLIGHT_FACTOR of that are the stop artefact, not a degradation (re-review N1).
        val referenceSeconds = minOf(stepEnd(reference) / ONE_SECOND, series.requestsPerSecond.size.toLong()) - referenceFrom / ONE_SECOND
        val inFlight =
            if (kind == "TAIL" && referenceSeconds > 0) {
                STOP_IN_FLIGHT_FACTOR * referenceLoad.samples / referenceSeconds * referenceLoad.meanMillis / ONE_SECOND
            } else {
                0.0
            }
        val fact = StageStretchFact(kind, from, to, ids[reference], load, referenceLoad, judge(load, referenceLoad, inFlight))
        return fact.takeIf { namedMillis >= DROP_MIN_MILLIS || it.verdict == StretchVerdict.DEGRADED }
    }
    // A lower step of mode top after the first steady one: against the last steady step before it (the top level).
    val stepStretches = HashMap<Int, StageStretchFact>()
    for (index in steps.indices) {
        if (steadyFlags[index] || index < firstSteady) continue
        judged("STEP", bounds[index].from * cellMillis, stepEnd(index), (index - 1 downTo 0).first { steadyFlags[it] })
            ?.let { stepStretches[index] = it }
    }
    // A gap between two steps: against the step at the higher level, the previous one for a decline, the next one for a rise
    // (a rise judged against the lower step would flag the ordinary growth of latency with load).
    val gapStretches = HashMap<Int, StageStretchFact>()
    for (index in 1 until steps.size) {
        if (index - 1 < firstSteady) continue
        val decline = steps[index].opening.fit.level < steps[index - 1].opening.fit.level
        judged("GAP", stepEnd(index - 1), bounds[index].from * cellMillis, if (decline) index - 1 else index)
            ?.let { gapStretches[index] = it }
    }
    // The tail after the last step: against the last step. A rise after it has no step at the same load to compare with.
    val lastEnd = stepEnd(steps.lastIndex)
    // The tail is named from its full cells (step 13), as before; its judgement covers the grid end.
    val tailStretch =
        judged(if (riseAfterSteady) "AFTER_STEADY" else "TAIL", lastEnd, gridEnd, steps.lastIndex, run.cells * cellMillis - lastEnd)

    // Step 9b (D14): a degraded stretch is never excluded. A lower step becomes its own steady window, and so does a gap
    // (`gap-K` before `step-K`): a transition at another rate merged into a step would move the step's throughput and
    // percentiles (re-review N3). A tail joins the last step, which becomes steady if it was not. Two levels are never pooled.
    for ((index, stretch) in stepStretches) if (stretch.verdict == StretchVerdict.DEGRADED) steadyFlags[index] = true
    val keptGaps = gapStretches.filterValues { it.verdict == StretchVerdict.DEGRADED }.keys

    fun gapId(index: Int) = "gap-${index + 1}"
    val tailKept = tailStretch?.kind == "TAIL" && tailStretch.verdict == StretchVerdict.DEGRADED
    if (tailKept) {
        steps.last().last = pieces.lastIndex
        bounds[steps.lastIndex] = bounds.last().copy(to = run.cells, trimmedTo = 0)
        steadyFlags[steps.lastIndex] = true
    }

    // Step 10: lead-in, ramp-up, steps, ramp-down on the cell grid; the stage after the last full cell reaches the grid end.
    val leadInCells = pieces.takeWhile { it.kind == StageSegmentKind.IDLE }.sumOf { it.fit.cells }.coerceAtMost(bounds.first().from)
    val stages = ArrayList<StageV1>()
    val boundaries = ArrayList<StageBoundaryFact>()
    val warnings = ArrayList<StageProposalWarning>()
    if (leadInCells > 0) {
        stages += StageV1(STAGE_LEAD_IN, StageRole.EXCLUDED, 0, leadInCells * cellMillis)
        boundaries += StageBoundaryFact(STAGE_LEAD_IN, "LEAD_IN_IDLE", null, null, null, 0)
        warnings += StageProposalWarning("LEAD_IN_IDLE", STAGE_LEAD_IN, 0, durationMillis = leadInCells * cellMillis)
    }
    if (bounds.first().from > leadInCells) {
        stages += StageV1(STAGE_RAMP_UP, StageRole.EXCLUDED, leadInCells * cellMillis, bounds.first().from * cellMillis)
        boundaries += StageBoundaryFact(STAGE_RAMP_UP, "BEFORE_FIRST_STEADY", null, null, null, 0)
    }
    val steadyStages = ArrayList<Pair<Step, String>>()
    steps.forEachIndexed { index, step ->
        if (index in keptGaps) {
            stages += StageV1(gapId(index), StageRole.STEADY, stepEnd(index - 1), bounds[index].from * cellMillis)
            boundaries += StageBoundaryFact(gapId(index), "DEGRADED_GAP", null, null, null, 0)
        }
        val id = ids[index]
        val steady = steadyFlags[index]
        val from = bounds[index].from
        val trimmed = bounds[index].trimmedFrom + bounds[index].trimmedTo
        val end = stepEnd(index)
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
    val stepMillis = bounds.sumOf { minOf(it.to * cellMillis, run.runEnd - run.runStart) - it.from * cellMillis }
    val share = stepMillis.toDouble() / (run.runEnd - run.runStart - leadInCells * cellMillis)
    if (share < MIN_STEADY_SHARE) return refuse(REFUSED_NO_PLATEAU, "steady_share=${decimal(share)}")
    // Step 12.
    if (stages.size > MAX_LOAD_STAGES) return refuse("TOO_MANY_STAGES", "stages=${stages.size}")

    // Step 13: warnings, then the confidence. From the start of the first steady stage nothing leaves the verdict windows
    // silently (D4): every excluded stretch of DROP_MIN_MILLIS or more after it is named, and such a proposal is never HIGH.
    val stepFits = steps.indices.associateWith { sums.fit(bounds[it].from, bounds[it].to) }
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

    // A stretch too small to judge keeps the warning of the RPS method and says so (D14).
    fun notJudged(stretch: StageStretchFact?) = stretch?.takeIf { it.verdict == StretchVerdict.NOT_JUDGED }
    for (index in 1 until steps.size) {
        val previous = steps[index - 1].opening.fit
        val next = steps[index].opening.fit
        val gapFrom = bounds[index - 1].to * cellMillis
        val gapMillis = bounds[index].from * cellMillis - gapFrom
        val dips =
            (steps[index - 1].last + 1 until steps[index].first).any {
                pieces[it].fit.level < (1 - LEVEL_MERGE_RATIO) * minOf(previous.level, next.level)
            }
        val gap = gapStretches[index]
        if (gap?.verdict == StretchVerdict.DEGRADED) {
            warnings +=
                StageProposalWarning(
                    "DEGRADED_GAP_KEPT",
                    gapId(index),
                    gap.fromOffsetMillis,
                    previous.level / perSecond,
                    next.level / perSecond,
                    gap.toOffsetMillis - gap.fromOffsetMillis,
                    gap,
                )
        } else if (next.level < previous.level || (dips && gapMillis >= DROP_MIN_MILLIS)) {
            warnings +=
                StageProposalWarning(
                    "LOAD_DECLINE_BETWEEN_STEPS",
                    ids[index],
                    gapFrom,
                    previous.level / perSecond,
                    next.level / perSecond,
                    gapMillis,
                    notJudged(gap),
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
                    notJudged(gap),
                )
        }
        // Any excluded step after the first steady one or after a step down is a hold that the verdict does not evaluate; a
        // degraded one became its own steady window (D14).
        val step = stepStretches[index]
        if (step?.verdict == StretchVerdict.DEGRADED) {
            warnings +=
                StageProposalWarning(
                    "DEGRADED_STEP_KEPT",
                    ids[index],
                    step.fromOffsetMillis,
                    top.level / perSecond,
                    next.level / perSecond,
                    step.toOffsetMillis - step.fromOffsetMillis,
                    step,
                )
        } else if (!steadyFlags[index] && (index > firstSteady || next.level < previous.level)) {
            warnings +=
                StageProposalWarning(
                    "HOLD_NOT_EVALUATED",
                    ids[index],
                    bounds[index].from * cellMillis,
                    fromRps = top.level / perSecond,
                    toRps = next.level / perSecond,
                    durationMillis = stages.single { it.id == ids[index] }.let { it.toOffsetMillis - it.fromOffsetMillis },
                    stretch = notJudged(step),
                )
        }
    }

    // The tail after the last step: whatever it is (a planned stop, a collapse, a hang, an unfinished rise), from the request
    // rate alone it cannot be told apart, so a long one is always named; a degraded one stays in the last step (D14).
    fun tailRates(from: Long): Pair<Double, Double> {
        val tailPieces = pieces.filter { it.fit.to * cellMillis > from }
        val first = tailPieces.first().fit
        val end = tailPieces.last().fit
        return maxOf(0.0, first.lineAt(maxOf(first.from, (from / cellMillis).toInt()))) / perSecond to
            maxOf(0.0, end.lineAt(end.to - 1)) / perSecond
    }
    val tailCellsMillis = run.cells * cellMillis - lastTo
    var longTail = false
    if (tailKept && tailStretch != null) {
        val (fromRps, toRps) = tailRates(tailStretch.fromOffsetMillis)
        warnings +=
            StageProposalWarning(
                "DEGRADED_TAIL_KEPT",
                tailStretch.referenceId,
                tailStretch.fromOffsetMillis,
                fromRps,
                toRps,
                tailStretch.toOffsetMillis - tailStretch.fromOffsetMillis,
                tailStretch,
            )
    } else if (tailCellsMillis >= DROP_MIN_MILLIS || (riseAfterSteady && tailStretch?.verdict == StretchVerdict.DEGRADED)) {
        val (fromRps, toRps) = tailRates(lastTo)
        // A rise after the last step that is worse than it stays excluded (no step at its load to join), but is named as
        // degraded, however short: an open question to the owner (ADR 0036, D14).
        val degradedRise = riseAfterSteady && tailStretch?.verdict == StretchVerdict.DEGRADED
        warnings +=
            StageProposalWarning(
                if (degradedRise) {
                    "DEGRADED_RISE_EXCLUDED"
                } else if (riseAfterSteady) {
                    "RISE_AFTER_STEADY"
                } else {
                    "LONG_RAMP_DOWN"
                },
                tailId,
                lastTo,
                fromRps = fromRps,
                toRps = toRps,
                durationMillis = tailMillis,
                stretch = if (degradedRise) tailStretch else notJudged(tailStretch),
            )
        longTail = tailMillis >= LONG_TAIL_LOW_MILLIS
    }
    if (series.degraded) warnings += StageProposalWarning("DEGRADED_INPUT", null, 0)
    val steadyFacts = boundaries.filter { it.basis == "PLATEAU" }
    val codes = warnings.map { it.code }.toSet()
    val confidence =
        when {
            longTail ||
                codes.any {
                    it in
                        setOf(
                            "LOAD_DROP_INSIDE_STEADY",
                            "HOLD_NOT_EVALUATED",
                            "DEGRADED_TAIL_KEPT",
                            "DEGRADED_GAP_KEPT",
                            "DEGRADED_STEP_KEPT",
                            "DEGRADED_RISE_EXCLUDED",
                        )
                } -> StageProposalConfidence.LOW
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
        (stepStretches.values + gapStretches.values + listOfNotNull(tailStretch)).sortedBy { it.fromOffsetMillis },
    )
}

/** Exact integer sums over the seconds `[from / 1000, to / 1000)` of the series, clipped to its length (ADR 0036, D14). */
private fun stretchLoad(
    series: LoadSecondSeries,
    fromMillis: Long,
    toMillis: Long,
): StretchLoad {
    var samples = 0L
    var failures = 0L
    var elapsed = 0L
    for (second in (fromMillis / ONE_SECOND).toInt() until minOf(toMillis / ONE_SECOND, series.requestsPerSecond.size.toLong()).toInt()) {
        samples = Math.addExact(samples, series.requestsPerSecond[second])
        failures = Math.addExact(failures, series.failuresPerSecond[second])
        elapsed = Math.addExact(elapsed, series.elapsedMillisPerSecond[second])
    }
    return StretchLoad(samples, failures, elapsed)
}

/**
 * D14: a stretch is DEGRADED when its error ratio or its mean latency is materially worse than the reference's, both from
 * exact counts over the whole stretch; NOT_JUDGED when either has fewer than DEGRADED_MIN_SAMPLES request samples. Each signal
 * needs a relative factor and an absolute increase; the error signal also needs an excess of failures beyond Poisson noise.
 */
private fun judge(
    load: StretchLoad,
    reference: StretchLoad,
    inFlightFailures: Double,
): StretchVerdict {
    if (load.samples < DEGRADED_MIN_SAMPLES || reference.samples < DEGRADED_MIN_SAMPLES) return StretchVerdict.NOT_JUDGED
    val ratio = load.errorRatio
    val referenceRatio = reference.errorRatio
    val expectedFailures = load.samples * referenceRatio
    val excess = load.failures - expectedFailures
    val errors =
        ratio >= DEGRADED_ERROR_FACTOR * referenceRatio &&
            ratio - referenceRatio >= DEGRADED_ERROR_MIN_INCREASE &&
            excess >= DEGRADED_ERROR_NOISE_SIGMAS * Math.sqrt(maxOf(1.0, expectedFailures)) &&
            excess > inFlightFailures
    val latency =
        load.meanMillis >= DEGRADED_LATENCY_FACTOR * reference.meanMillis &&
            load.meanMillis - reference.meanMillis >= DEGRADED_LATENCY_MIN_INCREASE_MILLIS
    return if (errors || latency) StretchVerdict.DEGRADED else StretchVerdict.HEALTHY
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

// D14: the latency and error check (plan W4.1, «Константы»).
private const val DEGRADED_MIN_SAMPLES = 200L
private const val DEGRADED_ERROR_FACTOR = 2.0
private const val DEGRADED_ERROR_MIN_INCREASE = 0.01
private const val DEGRADED_ERROR_NOISE_SIGMAS = 4.0
private const val DEGRADED_LATENCY_FACTOR = 1.5
private const val DEGRADED_LATENCY_MIN_INCREASE_MILLIS = 50.0
private const val STOP_IN_FLIGHT_FACTOR = 2.0

private val FRAMING_STAGE_BASES = setOf("LEAD_IN_IDLE", "BEFORE_FIRST_STEADY", "AFTER_LAST_STEADY")
private val STEP_STAGE_ID = Regex("^step-\\d+$")
private val GAP_STAGE_ID = Regex("^gap-\\d+$")

package io.ltverdict.core

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.parseInput
import java.nio.file.Files
import java.nio.file.Path

// ADR 0036: a proposal of load-stages.v1 from the request series of a run. The analysis never calls it; a person checks the
// proposal and passes it to `ltv analyze --stages` as an ordinary explicit input.

/** `requestsPerSecond[i]` counts request starts in `[runStart + 1000 i, runStart + 1000 (i + 1))`. */
internal class LoadSecondSeries(
    val runStartEpochMillis: Long,
    val runEndEpochMillis: Long,
    val requestsPerSecond: LongArray,
    val degraded: Boolean,
)

internal sealed interface LoadSecondSeriesRead {
    data class Series(
        val series: LoadSecondSeries,
    ) : LoadSecondSeriesRead

    /** INPUT_INVALID, NO_REQUEST_SAMPLES or RUN_TOO_LONG. */
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
    val counts = LongArray(((runEnd - runStart) / ONE_SECOND).toInt() + 1)
    var secondFirst: Long? = null
    val secondReport =
        try {
            parseInput(
                input,
                { sample ->
                    secondFirst = minOf(secondFirst ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    if (sample.isRequest()) {
                        val offset = sample.startedAtEpochMillis - runStart
                        if (offset < 0 || offset > runEnd - runStart) throw IllegalStateException(PARSER_PASS_MISMATCH)
                        counts[(offset / ONE_SECOND).toInt()]++
                    }
                },
                checkCancelled = checkCancelled,
            )
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(PARSER_PASS_MISMATCH)
        }
    if (secondReport.validity != firstReport.validity || secondFirst != runStart) throw IllegalStateException(PARSER_PASS_MISMATCH)
    return LoadSecondSeriesRead.Series(LoadSecondSeries(runStart, runEnd, counts, firstReport.validity == RunValidity.DEGRADED))
}

private fun io.ltverdict.ingest.LoadSample.isRequest() = kind == SampleKind.JMETER_SAMPLER || kind == SampleKind.GATLING_REQUEST

internal const val STAGE_PROPOSAL_METHOD = "rps-plateau-segmentation.v1"
private const val PROPOSAL_RUN_ID = "run"
private const val PARSER_PASS_MISMATCH = "PARSER_PASS_MISMATCH"
private const val ONE_SECOND = 1_000L
internal const val REFUSED_INPUT_INVALID = "INPUT_INVALID"
internal const val REFUSED_NO_REQUEST_SAMPLES = "NO_REQUEST_SAMPLES"
internal const val REFUSED_RUN_TOO_LONG = "RUN_TOO_LONG"

package io.ltverdict.cli

import io.ltverdict.core.STAGE_PROPOSAL_METHOD
import io.ltverdict.core.StageLikeComparison
import io.ltverdict.core.StageLikeStatus
import io.ltverdict.core.StageProposal
import io.ltverdict.core.StageProposalStatus
import io.ltverdict.core.StageStretchFact
import io.ltverdict.core.SteadySteps
import io.ltverdict.core.StretchLoad
import io.ltverdict.core.StretchVerdict
import io.ltverdict.core.decimal
import io.ltverdict.core.matchLoadStages
import io.ltverdict.core.proposalBytes
import io.ltverdict.core.proposeLoadStages
import io.ltverdict.ingest.detectSource
import java.io.IOException
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// `ltv stages detect` (ADR 0036, D8): a proposal of load-stages.v1 on stdout or in --out, the explanation on stderr. The command
// opens no data directory, so it runs next to `ltv ui`.

internal fun stagesCommand(
    args: List<String>,
    stdout: PrintStream,
    stderr: PrintStream,
): Int {
    if (args.size < 2 || args[0] != "detect" || args[1].startsWith("--")) usage()
    val input = path(args[1])
    var out: Path? = null
    var steadySteps: SteadySteps? = null
    var likePath: Path? = null
    var index = 2
    while (index < args.size) {
        if (index + 1 >= args.size) usage()
        val value = args[index + 1]
        when (args[index]) {
            "--out" -> if (out == null) out = path(value) else usage()
            "--steady-steps" ->
                if (steadySteps == null) steadySteps = SteadySteps.entries.firstOrNull { it.wireName == value } ?: usage() else usage()
            "--like" -> if (likePath == null) likePath = path(value) else usage()
            else -> usage()
        }
        index += 2
    }
    val target = out
    if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw CliFailure(EXIT_INVALID_INPUT, "STAGES_OUT_EXISTS")
    requireRegularFile(input, EXIT_INVALID_INPUT, "INVALID_INPUT")
    val like = likePath?.let(::readStages)
    val proposal =
        try {
            proposeLoadStages(detectSource(input), input, steadySteps ?: SteadySteps.TOP)
        } catch (failure: IllegalArgumentException) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.message ?: "UNSUPPORTED_INPUT")
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "INPUT_READ_FAILED")
        }
    if (proposal.status == StageProposalStatus.NOT_DETECTED) {
        // "\n" on every OS: the same input gives the same bytes of the explanation.
        stderr.write(stageProposalLines(proposal).joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
        stderr.flush()
        return if (proposal.reason == "INPUT_INVALID" || proposal.reason == "INPUT_CHANGED") EXIT_INVALID_INPUT else EXIT_NO_VERDICT
    }
    val bytes =
        if (like == null) {
            stderr.write(stageProposalLines(proposal).joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
            stderr.flush()
            proposal.proposalBytes()
        } else {
            val comparison = matchLoadStages(proposal, like.stages)
            stderr.write(stageLikeLines(comparison).joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
            stderr.flush()
            if (comparison.status != StageLikeStatus.MATCHED) return EXIT_NO_VERDICT
            like.canonicalBytes()
        }
    if (target == null) {
        stdout.write(bytes)
        stdout.flush()
    } else {
        try {
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "STAGES_OUT_WRITE_FAILED")
        }
    }
    return EXIT_OK
}

/** The explanation of a proposal or a refusal, one `key=value` line per fact, in a fixed order (plan W4.1, «Контракт и вывод»). */
internal fun stageProposalLines(proposal: StageProposal): List<String> =
    buildList {
        val head = "stage_proposal status=${proposal.status} method=$STAGE_PROPOSAL_METHOD steady_steps=${proposal.steadySteps.wireName}"
        val detected = proposal.status == StageProposalStatus.DETECTED
        add(if (detected) "$head confidence=${proposal.confidence} calibrated=false" else "$head reason=${proposal.reason}")
        addAll(stageProposalBodyLines(proposal))
        proposal.detail?.let { add("detail $it") }
        if (detected) add("next: review the stages, then run ltv analyze <input> --stages <file>")
    }

internal fun stageLikeLines(comparison: StageLikeComparison): List<String> =
    buildList {
        val proposal = comparison.proposal
        val head =
            "stage_proposal status=${if (comparison.status == StageLikeStatus.MATCHED) "MATCHED" else "NOT_MATCHED"} " +
                "method=$STAGE_PROPOSAL_METHOD steady_steps=${proposal.steadySteps.wireName}"
        add(
            if (comparison.status == StageLikeStatus.MATCHED) {
                "$head confidence=${proposal.confidence} calibrated=false"
            } else {
                "$head reason=${comparison.status.name}"
            },
        )
        addAll(stageProposalBodyLines(proposal))
        comparison.matches.forEach { fact ->
            val overlap =
                fact.overlapKind
                    ?.let {
                        " overlap_kind=${it.name} overlap_from_ms=${fact.overlapFromOffsetMillis} " +
                            "overlap_to_ms=${fact.overlapToOffsetMillis}"
                    }.orEmpty()
            val outside = if (fact.outsideRun) " outside_run=true" else ""
            add(
                "like stage_id=${fact.declaredStageId} proposal_id=${fact.proposalStageId} " +
                    "declared_from_ms=${fact.declaredFromOffsetMillis} declared_to_ms=${fact.declaredToOffsetMillis} " +
                    "proposal_from_ms=${fact.proposalFromOffsetMillis} proposal_to_ms=${fact.proposalToOffsetMillis} " +
                    "from_diff_ms=${fact.fromDiffMillis} to_diff_ms=${fact.toDiffMillis} " +
                    "tolerance_ms=${comparison.toleranceMillis}$overlap$outside",
            )
        }
        comparison.detail?.let { add("detail $it") }
        if (comparison.status == StageLikeStatus.MATCHED) add("next: run ltv analyze <input> --stages <file>")
    }

private fun stageProposalBodyLines(proposal: StageProposal): List<String> =
    buildList {
        if (proposal.runStartEpochMillis != null && proposal.runEndEpochMillis != null) {
            val cells =
                proposal.cellMillis?.let { " cell_ms=$it cells=${proposal.cells} noise_rps=${decimal(proposal.noiseRps ?: 0.0)}" }.orEmpty()
            add("run start_epoch_ms=${proposal.runStartEpochMillis} end_epoch_ms=${proposal.runEndEpochMillis}$cells")
        }
        proposal.segments.forEach {
            add(
                "segment from_offset_ms=${it.fromOffsetMillis} to_offset_ms=${it.toOffsetMillis} kind=${it.kind} " +
                    "level_rps=${decimal(it.levelRps)} drift=${decimal(it.driftRatio)} residual_cv=${decimal(it.residualCv)}",
            )
        }
        proposal.stages.zip(proposal.boundaries).forEach { (stage, fact) ->
            val plateau =
                fact.levelRps
                    ?.let {
                        " level_rps=${decimal(it)} from_contrast_sigma=${fact.fromContrastSigma.orDash()} " +
                            "to_contrast_sigma=${fact.toContrastSigma.orDash()} trimmed_cells=${fact.trimmedCells}"
                    }.orEmpty()
            add(
                "stage id=${stage.id} role=${stage.role.wireName} from_offset_ms=${stage.fromOffsetMillis} " +
                    "to_offset_ms=${stage.toOffsetMillis} basis=${fact.basis}$plateau",
            )
        }
        // ADR 0036, D14: every judged stretch with the exact counts of the stretch and of its reference.
        proposal.stretches.forEach {
            val load = loadFacts("", it.load)
            val reference = loadFacts("reference_", it.reference)
            add(
                "stretch kind=${it.kind} from_offset_ms=${it.fromOffsetMillis} to_offset_ms=${it.toOffsetMillis} " +
                    "reference_id=${it.referenceId} $load $reference verdict=${it.verdict}",
            )
        }
        proposal.warnings.forEach {
            add(
                "warning code=${it.code} stage_id=${it.stageId ?: "-"} at_offset_ms=${it.atOffsetMillis}" +
                    (if (it.fromRps != null && it.toRps != null) " from_rps=${decimal(it.fromRps)} to_rps=${decimal(it.toRps)}" else "") +
                    (it.durationMillis?.let { duration -> " duration_ms=$duration" }.orEmpty()) +
                    it.stretch?.let(::stretchSuffix).orEmpty(),
            )
        }
    }

private fun Double?.orDash() = this?.let(::decimal) ?: "-"

private fun loadFacts(
    prefix: String,
    load: StretchLoad,
) = "${prefix}samples=${load.samples} ${prefix}failures=${load.failures} ${prefix}error_ratio=${decimal(load.errorRatio)} " +
    "${prefix}mean_ms=${decimal(load.meanMillis)}"

/** A warning that names a judged stretch: the facts of a degradation, or that the stretch was too small to judge (D14). */
private fun stretchSuffix(stretch: StageStretchFact): String =
    if (stretch.verdict == StretchVerdict.NOT_JUDGED) {
        " degradation=NOT_JUDGED samples=${stretch.load.samples} reference_samples=${stretch.reference.samples}"
    } else {
        val load = " error_ratio=${decimal(stretch.load.errorRatio)} mean_ms=${decimal(stretch.load.meanMillis)}"
        val ratio = decimal(stretch.reference.errorRatio)
        val mean = decimal(stretch.reference.meanMillis)
        "$load reference_id=${stretch.referenceId} reference_error_ratio=$ratio reference_mean_ms=$mean"
    }

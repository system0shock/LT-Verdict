package io.ltverdict.cli

import io.ltverdict.core.STAGE_PROPOSAL_METHOD
import io.ltverdict.core.StageProposal
import io.ltverdict.core.StageProposalStatus
import io.ltverdict.core.SteadySteps
import io.ltverdict.core.decimal
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
    var index = 2
    while (index < args.size) {
        if (index + 1 >= args.size) usage()
        val value = args[index + 1]
        when (args[index]) {
            "--out" -> if (out == null) out = path(value) else usage()
            "--steady-steps" ->
                if (steadySteps == null) steadySteps = SteadySteps.entries.firstOrNull { it.wireName == value } ?: usage() else usage()
            else -> usage()
        }
        index += 2
    }
    val target = out
    if (target != null && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw CliFailure(EXIT_INVALID_INPUT, "STAGES_OUT_EXISTS")
    requireRegularFile(input, EXIT_INVALID_INPUT, "INVALID_INPUT")
    val proposal =
        try {
            proposeLoadStages(detectSource(input), input, steadySteps ?: SteadySteps.TOP)
        } catch (failure: IllegalArgumentException) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.message ?: "UNSUPPORTED_INPUT")
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "INPUT_READ_FAILED")
        }
    // "\n" on every OS: the same input gives the same bytes of the explanation.
    stderr.write(stageProposalLines(proposal).joinToString("") { "$it\n" }.toByteArray(StandardCharsets.UTF_8))
    stderr.flush()
    if (proposal.status == StageProposalStatus.NOT_DETECTED) {
        return if (proposal.reason == "INPUT_INVALID" || proposal.reason == "INPUT_CHANGED") EXIT_INVALID_INPUT else EXIT_NO_VERDICT
    }
    val bytes = proposal.proposalBytes()
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
        proposal.warnings.forEach {
            add(
                "warning code=${it.code} stage_id=${it.stageId ?: "-"} at_offset_ms=${it.atOffsetMillis}" +
                    (if (it.fromRps != null && it.toRps != null) " from_rps=${decimal(it.fromRps)} to_rps=${decimal(it.toRps)}" else "") +
                    (it.durationMillis?.let { duration -> " duration_ms=$duration" }.orEmpty()),
            )
        }
        proposal.detail?.let { add("detail $it") }
        if (detected) add("next: review the stages, then run ltv analyze <input> --stages <file>")
    }

private fun Double?.orDash() = this?.let(::decimal) ?: "-"

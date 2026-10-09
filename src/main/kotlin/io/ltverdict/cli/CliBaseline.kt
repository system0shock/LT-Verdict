package io.ltverdict.cli

import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.compareAnalyses
import io.ltverdict.core.hasStageBinding
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

// W2.3: `--baseline <analysis-result.json>`. The file is the saved analysis `<data-dir>/runs/<run_id>/analyses/<analysis_id>/`; its
// identity is read from the same directory, because the comparability key (ADR 0017, 0030 R6) is made of result and identity. The
// comparison is always UNCONFIRMED (the CLI has no confirmation of conditions, ADR 0028) and never changes a verdict or an exit code.

private const val BASELINE_SERIES = "cli"

internal class BaselineAnalysis(
    val runId: String,
    val analysisId: String,
    val result: JsonObject,
    val identity: JsonObject,
)

/** The run id and analysis id of a saved analysis-result.json inside [dataDir]; looks at paths only, so nothing is created. */
internal fun baselineLocation(
    path: Path,
    dataDir: Path,
): Pair<String, String> {
    fun notFound(): Nothing = throw CliFailure(EXIT_INVALID_INPUT, "BASELINE_NOT_FOUND")
    try {
        // The path as given, not resolved: a link in any part of it (the file, the analysis, the run) is refused.
        val file = path.toAbsolutePath().normalize()
        val analysis = file.parent ?: notFound()
        val analyses = analysis.parent ?: notFound()
        val run = analyses.parent ?: notFound()
        val runs = run.parent ?: notFound()
        val root = runs.parent ?: notFound()
        if (file.fileName.toString() != "analysis-result.json" ||
            analyses.fileName.toString() != "analyses" ||
            runs.fileName.toString() != "runs" ||
            listOf(file, analysis, analyses, run, runs).any { Files.isSymbolicLink(it) } ||
            !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isSameFile(root, dataDir)
        ) {
            notFound()
        }
        return run.fileName.toString() to analysis.fileName.toString()
    } catch (_: IOException) {
        notFound()
    } catch (_: InvalidPathException) {
        notFound()
    }
}

/** The baseline with its hashes checked (as the API does) and its result bounded; a missing or damaged one stops the command with exit 4. */
internal fun readBaselineAnalysis(
    store: RunBundleStore,
    runId: String,
    analysisId: String,
): BaselineAnalysis {
    val documents =
        try {
            store.readVerifiedAnalysis(runId, analysisId)?.let { it.result to it.identity }
                ?: throw CliFailure(EXIT_INVALID_INPUT, "BASELINE_NOT_FOUND")
        } catch (failure: IllegalArgumentException) {
            throw CliFailure(EXIT_INVALID_INPUT, if (failure.message == "RESULT_TOO_LARGE") "BASELINE_TOO_LARGE" else "BASELINE_NOT_FOUND")
        } catch (_: NoSuchElementException) {
            throw CliFailure(EXIT_INVALID_INPUT, "BASELINE_NOT_FOUND")
        } catch (failure: IllegalStateException) {
            if (failure.message?.startsWith("CORRUPT") == true) throw CliFailure(EXIT_INVALID_INPUT, "BASELINE_CORRUPT")
            throw failure
        }
    return BaselineAnalysis(runId, analysisId, documents.first, documents.second)
}

/** The analysis just made or asked for: its stored documents, without the size bound of a baseline (the verdict never depends on it). */
internal fun readCurrentAnalysis(
    store: RunBundleStore,
    runId: String,
    analysisId: String,
): BaselineAnalysis {
    val documents =
        try {
            store.readAnalysisDocuments(runId, analysisId)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        } catch (failure: IllegalStateException) {
            if (failure.message?.startsWith("CORRUPT") == true) null else throw failure
        } ?: throw CliFailure(EXIT_INVALID_INPUT, "BASELINE_CORRUPT")
    return BaselineAnalysis(runId, analysisId, documents.first, documents.second)
}

/**
 * The JSON `baseline_comparison`. Without stages the four whole-run metrics of the core comparison; with `stage_binding` on either
 * side (ADR 0030, R7) the steady windows by `window_metric_summary`, one core window comparison per evaluated window id, and no
 * whole-run delta at all.
 */
internal fun baselineComparison(
    baseline: BaselineAnalysis,
    current: BaselineAnalysis,
): JsonObject {
    val baselineReference = reference(baseline)
    val currentReference = reference(current)
    val selection = manualBaselineSelection(BASELINE_SERIES, baselineReference)

    fun compare(windows: WindowComparisonRequest?) =
        compareAnalyses(selection, currentReference, baseline.result, baseline.identity, current.result, current.identity, windows)

    val staged = hasStageBinding(baseline.result) || hasStageBinding(current.result)
    val warnings = compare(null).getValue("warnings") as JsonArray
    return buildJsonObject {
        put("baseline", baselineReference)
        put("comparability", "UNCONFIRMED")
        put("scope", if (staged) "steady_window" else "whole_run")
        put(
            "warnings",
            JsonArray(warnings.filterNot { staged && (it as? JsonPrimitive)?.content == "WHOLE_RUN_METRICS_WITH_STAGES" }),
        )
        if (staged) {
            val ids = windowIds(current.result).ifEmpty { windowIds(baseline.result) }
            put(
                "windows",
                buildJsonArray {
                    ids.forEach { id ->
                        val window = compare(WindowComparisonRequest(id, id)).getValue("window_comparison") as JsonObject
                        add(windowJson(window))
                    }
                },
            )
        } else {
            put("metrics", rows(compare(null).getValue("metrics") as JsonArray, withStatus = false))
        }
    }
}

private fun reference(analysis: BaselineAnalysis): JsonObject =
    buildJsonObject {
        put("run_id", analysis.runId)
        put("analysis_id", analysis.analysisId)
    }

private fun windowIds(result: JsonObject): List<String> =
    (result["evidence"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { (it["type"] as? JsonPrimitive)?.content == "stage_binding" }
        .flatMap { (it["evaluated_window_ids"] as? JsonArray).orEmpty() }
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }

private fun windowJson(window: JsonObject): JsonObject =
    buildJsonObject {
        put("window_id", window.getValue("baseline_window"))
        listOf(
            "status",
            "reasons",
            "min_change_percent",
            "min_error_rate_delta",
            "baseline_sample_count",
            "current_sample_count",
            "baseline_duration_ms",
            "current_duration_ms",
        ).forEach { put(it, window.getValue(it)) }
        put("metrics", rows(window.getValue("metrics") as JsonArray, withStatus = true))
    }

private fun rows(
    metrics: JsonArray,
    withStatus: Boolean,
): JsonArray =
    buildJsonArray {
        metrics.mapNotNull { it as? JsonObject }.forEach { row ->
            add(
                buildJsonObject {
                    (
                        listOf("metric", "unit", "baseline", "current", "delta", "delta_percent") +
                            if (withStatus) listOf("status") else emptyList()
                    ).forEach { put(it, row[it] ?: JsonNull) }
                    put("reason", row["reason"] ?: JsonNull)
                    put("percent_reason", row["percent_reason"] ?: JsonNull)
                },
            )
        }
    }

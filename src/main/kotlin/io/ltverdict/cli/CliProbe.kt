package io.ltverdict.cli

import io.ltverdict.core.canonicalJson
import io.ltverdict.sources.ProbeInputFailure
import io.ltverdict.sources.ProbeSpec
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceHttpFailure
import io.ltverdict.sources.checkSourceProfiles
import io.ltverdict.sources.probeSource
import io.ltverdict.sources.readSourceConnections
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream
import java.nio.file.Path

// W3.1, ADR 0033: `ltv source validate|probe|hash`. validate and hash never open a connection; probe sends exactly one request of a
// saved profile query. Text output is for people and not a contract; JSON (`--format json`) is stable. Nothing here prints an address,
// a credential, an environment variable name, a response body or the text of an exception.

internal fun sourceProbeCommand(
    args: List<String>,
    stdout: PrintStream,
): Int =
    when (args.firstOrNull()) {
        "validate" -> sourceValidate(args, stdout)
        "probe" -> sourceProbe(args, stdout)
        "hash" -> sourceHash(args, stdout)
        else -> usage()
    }

private fun options(
    args: List<String>,
    from: Int,
    allowed: Set<String>,
): Map<String, String> {
    val options = linkedMapOf<String, String>()
    var index = from
    while (index < args.size) {
        if (args[index] !in allowed || index + 1 >= args.size || options.put(args[index], args[index + 1]) != null) usage()
        index += 2
    }
    return options
}

private fun format(options: Map<String, String>): String =
    (options["--format"] ?: "text").also {
        if (it !in
            setOf("text", "json")
        ) {
            usage()
        }
    }

private fun number(value: String?): Long? = value?.let { it.toLongOrNull() ?: usage() }

private fun sourceValidate(
    args: List<String>,
    stdout: PrintStream,
): Int {
    val options = options(args, 1, setOf("--connections", "--profile", "--format"))
    val format = format(options)
    val connections = readSourceFile(path(options["--connections"] ?: usage()), ::readSourceConnections)
    val report =
        try {
            checkSourceProfiles(connections, options["--profile"], System::getenv, System::getProperty)
        } catch (failure: ProbeInputFailure) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.code)
        }
    if (format == "json") {
        stdout.write(canonicalJson(report))
        stdout.println()
    } else {
        stdout.print(validateText(report))
    }
    return EXIT_OK
}

private fun validateText(report: JsonObject): String =
    buildString {
        append("offline checks only: the network was not used\n")
        val counts = linkedMapOf("OK" to 0, "WARN" to 0, "FAIL" to 0, "SKIPPED" to 0)
        report.getValue("profiles").jsonArray.map { it.jsonObject }.forEach { profile ->
            val transport = profile["transport"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val kind = profile.text("source_kind") + (if (transport == null) "" else "/$transport")
            append("${profile.text("profile_id")}  $kind  ${profile.text("query_count")} queries, ${profile.text("rule_count")} rules\n")
            profile.getValue("checks").jsonArray.map { it.jsonObject }.forEach { check ->
                val status = check.text("status")
                counts[status] = counts.getValue(status) + 1
                val code = check["code"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                val kindOfAuth = check["auth_kind"]?.jsonPrimitive?.content
                append("  ${check.text("id").padEnd(12)} $status")
                if (code != null) append(" $code")
                if (kindOfAuth != null) append(" ($kindOfAuth)")
                append('\n')
            }
        }
        append("summary: ok ${counts["OK"]}, warn ${counts["WARN"]}, fail ${counts["FAIL"]}, skipped ${counts["SKIPPED"]}\n")
    }

private fun sourceProbe(
    args: List<String>,
    stdout: PrintStream,
): Int {
    val options =
        options(
            args,
            1,
            setOf("--connections", "--profile", "--query-id", "--window-ms", "--step-ms", "--end-epoch-ms", "--format"),
        )
    val format = format(options)
    val defaults = ProbeSpec("")
    val spec =
        ProbeSpec(
            options["--query-id"] ?: usage(),
            number(options["--window-ms"]) ?: defaults.windowMillis,
            number(options["--step-ms"]) ?: defaults.stepMillis,
            number(options["--end-epoch-ms"]),
        )
    val connections = readSourceFile(path(options["--connections"] ?: usage()), ::readSourceConnections)
    val profileId = options["--profile"] ?: usage()
    val profile =
        connections.http.singleOrNull { it.id == profileId }
            ?: throw CliFailure(
                EXIT_INVALID_INPUT,
                if (connections.postgres.any {
                        it.id == profileId
                    }
                ) {
                    "INVALID_PROBE"
                } else {
                    "SOURCE_PROFILE_NOT_FOUND"
                },
            )
    val report =
        try {
            probeSource(profile, spec, SourceHttp(connections.http))
        } catch (failure: ProbeInputFailure) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.code)
        } catch (failure: SourceHttpFailure) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.code)
        }
    if (format == "json") {
        stdout.write(canonicalJson(report))
        stdout.println()
    } else {
        stdout.print(probeText(report))
    }
    return if (report.text("status") == "OK") EXIT_OK else EXIT_DOCTOR_FAILED
}

private fun probeText(report: JsonObject): String =
    buildString {
        val window = report.getValue("window").jsonObject
        append(
            "profile ${report.text(
                "profile_id",
            )} (${report.text("source_kind")}/${report.text("transport")}) query ${report.text("query_id")}\n",
        )
        append(
            "window ${window.text(
                "start_epoch_ms",
            )}..${window.text("end_epoch_ms")} step ${window.text("step_ms")} ms (${window.text("cells")} cells)\n",
        )
        val status = report.text("status")
        val httpStatus = report["http_status"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        append("status $status")
        report["code"]?.takeIf { it !is JsonNull }?.let { append(" ${it.jsonPrimitive.content}") }
        if (httpStatus != null) append(" (http $httpStatus)")
        append(", requests ${report.text("request_count")}, ${report.text("elapsed_ms")} ms\n")
        if (status == "OK") {
            append("series: ${report.text("series_count")}\n")
            report.getValue("series").jsonArray.map { it.jsonObject }.forEach { series ->
                val labels =
                    series
                        .getValue(
                            "labels",
                        ).jsonObject.entries
                        .joinToString(", ") { (key, value) -> "$key=${value.jsonPrimitive.content}" }
                append("  {$labels} observed ${series.text("observed_cells")}/${series.text("expected_cells")}\n")
            }
            if (report.text("series_truncated") == "true") append("  (more series not shown)\n")
            report.getValue("label_keys").jsonArray.map { it.jsonObject }.forEach { key ->
                val samples = key.getValue("sample_values").jsonArray.joinToString(", ") { it.jsonPrimitive.content }
                append(
                    "label key ${key.text(
                        "key",
                    )}: ${key.text("distinct_values")} distinct ($samples${if (key.text("truncated") == "true") ", ..." else ""})\n",
                )
            }
            val decoder = report.getValue("decoder_check").jsonObject
            append(
                if (decoder.text("accepted") == "true") {
                    "decoder check: accepted\n"
                } else {
                    "decoder check: not accepted (${decoder.text("code")})\n"
                },
            )
            val warnings = report.getValue("warnings").jsonArray
            if (warnings.isNotEmpty()) append("warnings: ${warnings.joinToString(", ") { it.jsonPrimitive.content }}\n")
            if (report.text("redacted_count") != "0") append("redacted values: ${report.text("redacted_count")}\n")
        }
    }

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

private fun sourceHash(
    args: List<String>,
    stdout: PrintStream,
): Int {
    val document =
        if (args.size == 2 && !args[1].startsWith("--")) {
            hashOfFile(path(args[1]))
        } else {
            val options = options(args, 1, setOf("--run", "--analysis", "--data-dir"))
            hashOfSavedAnalysis(
                options["--run"] ?: usage(),
                options["--analysis"] ?: usage(),
                options["--data-dir"]?.let(::path) ?: defaultDataDir(),
            )
        }
    stdout.write(canonicalJson(document))
    stdout.println()
    return EXIT_OK
}

private fun hashOfFile(file: Path): JsonObject {
    val snapshot = readResourcesFile(file)
    return resourceHash(
        snapshot.semanticSha256,
        snapshot.configSha256,
        snapshot.snapshot.loadInputSha256,
        JsonPrimitive(snapshot.snapshot.series.size),
        JsonPrimitive(snapshot.snapshot.pointCount),
    )
}

private fun hashOfSavedAnalysis(
    runId: String,
    analysisId: String,
    dataDir: Path,
): JsonObject {
    val identity =
        DataDirectory.open(dataDir).use { directory ->
            try {
                RunBundleStore(directory).readVerifiedAnalysis(runId, analysisId)?.identity
            } catch (failure: IllegalArgumentException) {
                if (failure.message == "RESULT_TOO_LARGE") throw CliFailure(EXIT_INVALID_INPUT, "ANALYSIS_CORRUPT")
                null
            } catch (_: NoSuchElementException) {
                null
            } catch (failure: IllegalStateException) {
                if (failure.message?.startsWith("CORRUPT") == true) throw CliFailure(EXIT_INVALID_INPUT, "ANALYSIS_CORRUPT")
                throw failure
            }
        } ?: throw CliFailure(EXIT_INVALID_INPUT, "ANALYSIS_NOT_FOUND")

    fun field(name: String): String? = (identity[name] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    val semantic = field("resource_snapshot_sha256") ?: throw CliFailure(EXIT_INVALID_INPUT, "RESOURCE_SNAPSHOT_NOT_PROVIDED")
    // The load hash of a snapshot equals the input hash of its analysis (AnalysisService refuses any other snapshot).
    return resourceHash(semantic, field("resource_config_sha256"), field("input_sha256"), JsonNull, JsonNull)
}

private fun resourceHash(
    semantic: String,
    config: String?,
    loadInput: String?,
    seriesCount: JsonElement,
    pointCount: JsonElement,
): JsonObject =
    buildJsonObject {
        put("schema_version", "resource-hash.v1")
        put("semantic_sha256", semantic)
        put("config_sha256", config?.let(::JsonPrimitive) ?: JsonNull)
        put("load_input_sha256", loadInput?.let(::JsonPrimitive) ?: JsonNull)
        put("series_count", seriesCount)
        put("point_count", pointCount)
    }

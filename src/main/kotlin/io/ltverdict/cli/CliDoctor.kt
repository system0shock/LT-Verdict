package io.ltverdict.cli

import io.ltverdict.core.canonicalJson
import io.ltverdict.sources.ProbeInputFailure
import io.ltverdict.sources.ProbeLimits
import io.ltverdict.sources.ProbeSpec
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceKind
import io.ltverdict.sources.checkSourceProfiles
import io.ltverdict.sources.probeSource
import io.ltverdict.sources.readSourceConnections
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.PrintStream

// W3.1, ADR 0033 (P7, P12): `ltv doctor` combines offline profile checks (`checkSourceProfiles`) and optional sequential
// `--online` probing (`probeSource`) under `maxRequestsPerRun`. Never prints URLs, env var names, credentials, or response bodies.

internal fun doctorCommand(
    args: List<String>,
    stdout: PrintStream,
    environment: (String) -> String? = System::getenv,
    systemProperty: (String) -> String? = System::getProperty,
    nowMillis: () -> Long = System::currentTimeMillis,
    limits: ProbeLimits = ProbeLimits(),
): Int {
    var connectionsArg: String? = null
    var profileFilter: String? = null
    var format: String? = null
    var online = false
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--online" -> {
                if (online) usage()
                online = true
                index += 1
            }
            "--connections" -> {
                if (connectionsArg != null || index + 1 >= args.size) usage()
                connectionsArg = args[index + 1]
                index += 2
            }
            "--profile" -> {
                if (profileFilter != null || index + 1 >= args.size) usage()
                profileFilter = args[index + 1]
                index += 2
            }
            "--format" -> {
                if (format != null || index + 1 >= args.size) usage()
                format = args[index + 1]
                index += 2
            }
            else -> usage()
        }
    }
    val selectedFormat = (format ?: "text").also { if (it !in setOf("text", "json")) usage() }
    val connections = readSourceFile(path(connectionsArg ?: usage()), ::readSourceConnections)
    val offline =
        try {
            checkSourceProfiles(connections, profileFilter, environment, systemProperty)
        } catch (failure: ProbeInputFailure) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.code)
        }

    val offlineProfiles = offline.getValue("profiles").jsonArray.map { it.jsonObject }
    val httpById = connections.http.associateBy { it.id }
    val validHttpProfiles =
        if (!online) {
            emptyList()
        } else {
            offlineProfiles.mapNotNull { entry ->
                val hasOfflineFail = entry.getValue("checks").jsonArray.any { it.jsonObject.text("status") == "FAIL" }
                val profile = httpById[entry.text("profile_id")]
                if (!hasOfflineFail && profile != null && profile.sourceKind != SourceKind.OPENSEARCH && profile.queries.isNotEmpty()) {
                    profile
                } else {
                    null
                }
            }
        }
    val sharedHttp =
        if (validHttpProfiles.isEmpty()) {
            null
        } else {
            SourceHttp(validHttpProfiles, systemProperty, environment)
        }

    var okCount = 0
    var warnCount = 0
    var failCount = 0
    var skippedCount = 0

    fun tally(status: String) {
        when (status) {
            "OK" -> okCount++
            "WARN" -> warnCount++
            "FAIL" -> failCount++
            "SKIPPED" -> skippedCount++
        }
    }

    val profileEntries =
        offlineProfiles.map { entry ->
            val checks = entry.getValue("checks").jsonArray.map { it.jsonObject }
            checks.forEach { tally(it.text("status")) }
            val hasOfflineFail = checks.any { it.text("status") == "FAIL" }
            val profileId = entry.text("profile_id")
            val httpProfile = httpById[profileId]
            val queryEntries: List<JsonObject>? =
                if (!online ||
                    hasOfflineFail ||
                    sharedHttp == null ||
                    httpProfile == null ||
                    httpProfile.sourceKind == SourceKind.OPENSEARCH ||
                    httpProfile.queries.isEmpty()
                ) {
                    null
                } else {
                    var requestsUsed = 0
                    val maxRequests = httpProfile.governor.maxRequestsPerRun
                    httpProfile.queries.map { query ->
                        val queryResult =
                            if (maxRequests != null && requestsUsed >= maxRequests) {
                                buildJsonObject {
                                    put("query_id", query.id)
                                    put("status", "SKIPPED")
                                    put("code", "SOURCE_REQUEST_CAP_EXCEEDED")
                                    put("http_status", JsonNull)
                                    put("series_count", JsonNull)
                                    put("observed_cells", JsonNull)
                                    put("expected_cells", JsonNull)
                                    put("decoder_check", JsonNull)
                                }
                            } else {
                                val probe = probeSource(httpProfile, ProbeSpec(query.id), sharedHttp, environment, nowMillis, limits)
                                requestsUsed +=
                                    probe
                                        .getValue("request_count")
                                        .jsonPrimitive.content
                                        .toInt()
                                mapProbeToDoctorQuery(query.id, probe)
                            }
                        tally(queryResult.text("status"))
                        queryResult
                    }
                }
            val profileSide = profileResourceSide(hasOfflineFail, online, queryEntries)
            buildJsonObject {
                entry.forEach { (key, value) -> put(key, value) }
                put("resource_side", profileSide)
                if (queryEntries != null) {
                    put("online", buildJsonObject { put("queries", buildJsonArray { queryEntries.forEach(::add) }) })
                }
            }
        }

    val overallSide = overallResourceSide(profileEntries.map { it.text("resource_side") })
    val totalProfileCount = connections.http.size + connections.postgres.size
    val report =
        buildJsonObject {
            put("schema_version", "doctor-report.v1")
            put("online", online)
            put(
                "connections",
                buildJsonObject {
                    put("status", "OK")
                    put("profile_count", totalProfileCount)
                },
            )
            put("profiles", buildJsonArray { profileEntries.forEach(::add) })
            put(
                "summary",
                buildJsonObject {
                    put("ok", okCount)
                    put("warn", warnCount)
                    put("fail", failCount)
                    put("skipped", skippedCount)
                },
            )
            put("resource_side", overallSide)
        }

    if (selectedFormat == "json") {
        stdout.write(canonicalJson(report))
        stdout.println()
    } else {
        stdout.print(doctorText(report))
    }
    return if (failCount > 0) EXIT_DOCTOR_FAILED else EXIT_OK
}

private fun mapProbeToDoctorQuery(
    queryId: String,
    probe: JsonObject,
): JsonObject {
    if (probe.text("status") == "FAILED") {
        return buildJsonObject {
            put("query_id", queryId)
            put("status", "FAIL")
            put("code", probe.getValue("code"))
            put("http_status", probe.getValue("http_status"))
            put("series_count", JsonNull)
            put("observed_cells", JsonNull)
            put("expected_cells", JsonNull)
            put("decoder_check", JsonNull)
        }
    }
    val decoder = probe.getValue("decoder_check").jsonObject
    val accepted = decoder.text("accepted") == "true"
    val decoderCode = decoder.optionalText("code")
    val status =
        when {
            accepted -> "OK"
            decoderCode == "EMPTY_RESULT" -> "WARN"
            else -> "FAIL"
        }
    val code = if (accepted) JsonNull else decoder.getValue("code")
    val seriesCount =
        probe
            .getValue("series_count")
            .jsonPrimitive.content
            .toInt()
    val expectedCells =
        probe
            .getValue("window")
            .jsonObject
            .getValue("cells")
            .jsonPrimitive.content
            .toInt()
    val observedCells =
        when (seriesCount) {
            0 -> JsonPrimitive(0)
            1 ->
                probe
                    .getValue("series")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("observed_cells")
            else -> JsonNull
        }
    return buildJsonObject {
        put("query_id", queryId)
        put("status", status)
        put("code", code)
        put("http_status", probe.getValue("http_status"))
        put("series_count", seriesCount)
        put("observed_cells", observedCells)
        put("expected_cells", expectedCells)
        put("decoder_check", decoder)
    }
}

private fun profileResourceSide(
    hasOfflineFail: Boolean,
    online: Boolean,
    queries: List<JsonObject>?,
): String =
    when {
        hasOfflineFail -> "UNAVAILABLE"
        !online || queries == null -> "NOT_PROBED"
        queries.all { it.text("status") == "FAIL" } -> "UNAVAILABLE"
        queries.all { it.text("status") == "OK" } -> "READY"
        else -> "DEGRADED"
    }

private fun overallResourceSide(sides: List<String>): String =
    when {
        "UNAVAILABLE" in sides -> "UNAVAILABLE"
        "DEGRADED" in sides -> "DEGRADED"
        "READY" in sides -> "READY"
        else -> "NOT_PROBED"
    }

private fun doctorText(report: JsonObject): String =
    buildString {
        val online = report.text("online") == "true"
        if (online) {
            append("LT Verdict doctor (online: yes)\n")
        } else {
            append("LT Verdict doctor (online: no, сеть не проверялась: нужен --online)\n")
        }
        val connections = report.getValue("connections").jsonObject
        append("connections        ${connections.text("status")}  ${connections.text("profile_count")} profiles\n")
        report.getValue("profiles").jsonArray.map { it.jsonObject }.forEach { profile ->
            val transport = profile.optionalText("transport")
            val kind = profile.text("source_kind") + (if (transport == null) "" else "/$transport")
            append(
                "${profile.text("profile_id")}  $kind  ${profile.text("query_count")} queries, " +
                    "${profile.text("rule_count")} rules  [${profile.text("resource_side")}]\n",
            )
            profile.getValue("checks").jsonArray.map { it.jsonObject }.forEach { check ->
                val status = check.text("status")
                val code = check.optionalText("code")
                val authKind = check.optionalText("auth_kind")
                append("  ${check.text("id").padEnd(12)} $status")
                if (code != null) append(" $code")
                if (authKind != null) append(" ($authKind)")
                append('\n')
            }
            val onlineQueries =
                profile["online"]
                    ?.takeIf { it !is JsonNull }
                    ?.jsonObject
                    ?.getValue("queries")
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    .orEmpty()
            onlineQueries.forEach { query ->
                val status = query.text("status")
                val code = query.optionalText("code")
                val seriesCount = query.optionalText("series_count")
                val decoder = query["decoder_check"]?.takeIf { it !is JsonNull }?.jsonObject
                append("  query ${query.text("query_id")}  $status")
                if (code != null) append(" $code")
                if (seriesCount != null) append(" series=$seriesCount")
                if (decoder != null) {
                    val decoderText = if (decoder.text("accepted") == "true") "accepted" else decoder.text("code")
                    append(" decoder=$decoderText")
                }
                append('\n')
            }
        }
        val summary = report.getValue("summary").jsonObject
        append(
            "summary: ok ${summary.text("ok")}, warn ${summary.text("warn")}, " +
                "fail ${summary.text("fail")}, skipped ${summary.text("skipped")}\n",
        )
        append("resource side: ${report.text("resource_side")}\n")
    }

private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.optionalText(name: String): String? = this[name]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

package io.ltverdict.sources

import io.ltverdict.core.RESOURCE_JSON_DEPTH_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_EXPONENT_ABS_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_TOKEN_BYTES_MAX
import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.cleanErrorText
import io.ltverdict.core.sha256Hex
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// W3.1, ADR 0033: a source probe sends ONE range query of a saved profile and reports what came back (number of series, cleaned labels)
// without the one-series rule of the analysis. It never stores anything and never returns a response body, an address, a credential
// or the text of an exception. The analysis decoders are not changed: they run on the same bytes and their verdict is `decoder_check`.

internal data class ProbeLimits(
    val totalTimeoutMillis: Long = PROBE_TOTAL_TIMEOUT_MILLIS,
    val maxResponseBytes: Int = PROBE_MAX_RESPONSE_BYTES,
)

internal data class ProbeSpec(
    val queryId: String,
    val windowMillis: Long = PROBE_DEFAULT_WINDOW_MILLIS,
    val stepMillis: Long = PROBE_DEFAULT_STEP_MILLIS,
    val endEpochMillis: Long? = null,
)

/** A request refused before any network access; the code is one of INVALID_PROBE, QUERY_NOT_FOUND, SOURCE_REQUEST_INVALID. */
internal class ProbeInputFailure(
    val code: String,
) : RuntimeException(code)

private class ProbeParseFailure(
    val code: String,
) : RuntimeException(code)

private class ProbeSeries(
    val labels: Map<String, String>,
    val observedCells: Int,
)

private class ProbeParsed(
    val series: List<ProbeSeries>,
    val warnings: Set<String>,
)

internal fun probeSource(
    profile: SourceProfile,
    spec: ProbeSpec,
    http: SourceHttp,
    environment: (String) -> String? = System::getenv,
    nowMillis: () -> Long = System::currentTimeMillis,
    limits: ProbeLimits = ProbeLimits(),
): JsonObject {
    if (profile.sourceKind == SourceKind.OPENSEARCH) throw ProbeInputFailure("INVALID_PROBE")
    val query = profile.queries.singleOrNull { it.id == spec.queryId } ?: throw ProbeInputFailure("QUERY_NOT_FOUND")
    val step = spec.stepMillis
    val cells = probeCells(spec)
    val endMillis = spec.endEpochMillis ?: (Math.floorDiv(nowMillis(), step) * step)
    // Checked before the subtraction: an end near Long.MIN_VALUE would wrap around into a plausible start.
    if (endMillis < spec.windowMillis || endMillis > MAX_TIMESTAMP_EPOCH_MILLIS) throw ProbeInputFailure("SOURCE_REQUEST_INVALID")
    val startMillis = endMillis - spec.windowMillis
    val request = SourceRequest(profile.id, startMillis, endMillis, step)
    val expression = resolvedExpression(profile, query, request)
    val redactor = Redactor(secretValues(profile, environment) + knownNames(profile))
    val budget = SourceBudget()
    val startedNanos = System.nanoTime()
    val deadlineNanos = startedNanos + limits.totalTimeoutMillis * NANOS_PER_MILLI
    var httpStatus: Int? = null
    var parsed: ProbeParsed? = null
    var decoderCode: String? = null
    var failureCode: String? = null
    try {
        val body =
            http.getBounded(
                profile,
                queryRangeParameters(profile, expression, request),
                budget,
                limits.maxResponseBytes,
                limits.totalTimeoutMillis,
            ) {
                if (System.nanoTime() - deadlineNanos >= 0) throw SourceHttpFailure("SOURCE_TIMEOUT")
            }
        httpStatus = 200
        parsed = parseProbeBody(body, profile.sourceKind)
        decoderCode = decoderCheck(body, profile, query, startMillis, step, cells)
        if (System.nanoTime() - deadlineNanos >= 0) throw SourceHttpFailure("SOURCE_TIMEOUT")
    } catch (failure: SourceHttpFailure) {
        parsed = null
        httpStatus = failure.httpStatus
        failureCode =
            failure.code.takeIf { it in PROBE_HTTP_CODES }
                ?: if (failure.code == "SOURCE_PROFILE_INVALID") "SOURCE_PROFILE_NOT_CONFIGURED" else "SOURCE_HTTP_ERROR"
    } catch (failure: ProbeParseFailure) {
        failureCode = failure.code
    } catch (_: IllegalArgumentException) {
        failureCode = "SOURCE_PROFILE_NOT_CONFIGURED"
    } catch (_: Exception) {
        failureCode = "SOURCE_HTTP_ERROR"
    }
    return buildJsonObject {
        put("schema_version", "source-probe.v1")
        put("profile_id", profile.id)
        put("source_kind", profile.sourceKind.wireName)
        put("transport", profile.transport.wireName)
        put("mode", "profile_query")
        put("query_id", query.id)
        put("expression_sha256", sha256Hex(expression.encodeToByteArray()))
        put(
            "window",
            buildJsonObject {
                put("start_epoch_ms", startMillis)
                put("end_epoch_ms", endMillis)
                put("step_ms", step)
                put("cells", cells)
            },
        )
        put("status", if (parsed != null) "OK" else "FAILED")
        put("code", failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put("http_status", httpStatus?.let(::JsonPrimitive) ?: JsonNull)
        val series = parsed?.series
        put("series_count", series?.size?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "series",
            buildJsonArray {
                series.orEmpty().take(PROBE_MAX_SERIES).forEach { item ->
                    add(
                        buildJsonObject {
                            put(
                                "labels",
                                buildJsonObject {
                                    item.labels.entries.sortedBy { it.key }.take(PROBE_MAX_LABEL_KEYS).forEach { (key, value) ->
                                        put(redactor.text(key), redactor.value(key, value))
                                    }
                                },
                            )
                            put("observed_cells", item.observedCells)
                            put("expected_cells", cells)
                        },
                    )
                }
            },
        )
        put("series_truncated", (series?.size ?: 0) > PROBE_MAX_SERIES)
        val keys = sortedMapOf<String, MutableSet<String>>()
        series.orEmpty().forEach { item -> item.labels.forEach { (key, value) -> keys.getOrPut(key) { sortedSetOf() } += value } }
        put(
            "label_keys",
            buildJsonArray {
                keys.entries.take(PROBE_MAX_LABEL_KEYS).forEach { (key, values) ->
                    add(
                        buildJsonObject {
                            put("key", redactor.text(key))
                            put("distinct_values", values.size)
                            put(
                                "sample_values",
                                buildJsonArray {
                                    values
                                        .take(
                                            PROBE_MAX_SAMPLE_VALUES,
                                        ).forEach { add(JsonPrimitive(redactor.value(key, it))) }
                                },
                            )
                            put("truncated", values.size > PROBE_MAX_SAMPLE_VALUES)
                        },
                    )
                }
            },
        )
        put("label_keys_truncated", keys.size > PROBE_MAX_LABEL_KEYS)
        put("redacted_count", redactor.count)
        put(
            "warnings",
            buildJsonArray {
                parsed
                    ?.warnings
                    .orEmpty()
                    .sorted()
                    .forEach { add(JsonPrimitive(it)) }
            },
        )
        put(
            "decoder_check",
            if (parsed == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("accepted", decoderCode == null)
                    put("code", decoderCode?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
        put("request_count", budget.requestCount)
        put("elapsed_ms", (System.nanoTime() - startedNanos) / NANOS_PER_MILLI)
    }
}

// Offline checks of the saved profiles (`source-check.v1`): the file is already parsed, nothing here opens a connection.
// The result carries codes only: no address, no environment variable name, no file path.
internal fun checkSourceProfiles(
    connections: SourceConnections,
    only: String?,
    environment: (String) -> String?,
    systemProperty: (String) -> String?,
): JsonObject {
    val entries =
        (
            connections.http.map { it.id to httpProfileCheck(it, environment, systemProperty) } +
                connections.postgres.map { it.id to postgresProfileCheck(it, environment) }
        ).sortedBy { it.first }
    if (only != null && entries.none { it.first == only }) throw ProbeInputFailure("SOURCE_PROFILE_NOT_FOUND")
    return buildJsonObject {
        put("schema_version", "source-check.v1")
        put("profiles", buildJsonArray { entries.filter { only == null || it.first == only }.forEach { add(it.second) } })
    }
}

private fun check(
    id: String,
    status: String,
    code: String? = null,
    authKind: String? = null,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("status", status)
        put("code", code?.let(::JsonPrimitive) ?: JsonNull)
        if (authKind != null) put("auth_kind", authKind)
    }

private fun credentialsCheck(
    kind: String,
    names: List<String>,
    environment: (String) -> String?,
): JsonObject {
    val values = names.map { environment(it) }
    return when {
        values.any { it.isNullOrEmpty() } -> check("credentials", "FAIL", "SOURCE_AUTH_UNAVAILABLE", kind)
        values.any { value -> value!!.any(Char::isISOControl) } -> check("credentials", "FAIL", "SOURCE_AUTH_INVALID", kind)
        else -> check("credentials", "OK", null, kind)
    }
}

private fun httpProfileCheck(
    profile: SourceProfile,
    environment: (String) -> String?,
    systemProperty: (String) -> String?,
): JsonObject {
    val url =
        try {
            SourceHttp(listOf(profile), systemProperty, environment)
            check("url", "OK")
        } catch (_: Exception) {
            check("url", "FAIL", "SOURCE_PROFILE_INVALID")
        }
    val credentials =
        when (val auth = profile.auth) {
            SourceAuth.None -> check("credentials", "OK", null, "none")
            is SourceAuth.Bearer -> credentialsCheck("bearer", listOf(auth.tokenEnv), environment)
            is SourceAuth.Token -> credentialsCheck("token", listOf(auth.tokenEnv), environment)
            is SourceAuth.Basic -> credentialsCheck("basic", listOf(auth.usernameEnv, auth.passwordEnv), environment)
        }
    val tls =
        profile.tls?.let { settings ->
            try {
                sourceTlsMaterial(settings, environment, systemProperty).checkClientValidity()
                check("tls", "OK")
            } catch (failure: SourceHttpFailure) {
                check("tls", "FAIL", failure.code.takeIf { it.startsWith("SOURCE_TLS_") } ?: "SOURCE_TLS_CONFIG_INVALID")
            } catch (_: Exception) {
                check("tls", "FAIL", "SOURCE_TLS_CONFIG_INVALID")
            }
        } ?: check("tls", "SKIPPED")
    val strict = profile.governor.requestsPerSecond < STRICT_REQUESTS_PER_SECOND && profile.queries.size > STRICT_QUERY_COUNT
    return buildJsonObject {
        put("profile_id", profile.id)
        put("source_kind", profile.sourceKind.wireName)
        put("transport", profile.transport.wireName)
        put("arm", profile.arm?.let(::JsonPrimitive) ?: JsonNull)
        put("query_count", profile.queries.size)
        put("rule_count", profile.rules.size)
        put(
            "checks",
            buildJsonArray {
                add(check("config", "OK"))
                add(url)
                add(credentials)
                add(tls)
                add(check("rules", "OK"))
                add(if (strict) check("governor", "WARN", "GOVERNOR_STRICT") else check("governor", "OK"))
            },
        )
    }
}

private fun postgresProfileCheck(
    profile: PostgresProfile,
    environment: (String) -> String?,
): JsonObject =
    buildJsonObject {
        put("profile_id", profile.id)
        put("source_kind", "postgresql")
        put("transport", JsonNull)
        put("arm", JsonNull)
        put("query_count", 0)
        put("rule_count", 0)
        put(
            "checks",
            buildJsonArray {
                add(check("config", "OK"))
                add(credentialsCheck("basic", listOf(profile.usernameEnv, profile.passwordEnv), environment))
            },
        )
    }

private fun probeCells(spec: ProbeSpec): Int {
    try {
        requireSnapshotGridStep(spec.stepMillis)
    } catch (_: IllegalArgumentException) {
        throw ProbeInputFailure("SOURCE_REQUEST_INVALID")
    }
    if (spec.windowMillis !in 1..PROBE_MAX_WINDOW_MILLIS || spec.windowMillis % spec.stepMillis != 0L) {
        throw ProbeInputFailure("SOURCE_REQUEST_INVALID")
    }
    val cells = spec.windowMillis / spec.stepMillis
    if (cells !in 1..PROBE_MAX_CELLS) throw ProbeInputFailure("SOURCE_REQUEST_INVALID")
    return cells.toInt()
}

// The production decoder on the same bytes: null when it would accept the response, otherwise its code.
private fun decoderCheck(
    body: ByteArray,
    profile: SourceProfile,
    query: SourceQuery,
    startMillis: Long,
    step: Long,
    cells: Int,
): String? =
    try {
        val decoded =
            if (profile.sourceKind == SourceKind.INFLUXDB) {
                decodeInfluxqlResponse(body, query.labels, startMillis, step, cells)
            } else {
                decodePromqlMatrix(body, query.labels, startMillis, step, cells)
            }
        if (decoded == null) "EMPTY_RESULT" else null
    } catch (failure: PromqlDecodeFailure) {
        failure.code.takeIf { it in PROBE_DECODER_CODES } ?: "MALFORMED_RESPONSE"
    } catch (_: Exception) {
        "MALFORMED_RESPONSE"
    }

private fun parseProbeBody(
    body: ByteArray,
    kind: SourceKind,
): ProbeParsed {
    val text =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        } catch (_: Exception) {
            throw ProbeParseFailure("MALFORMED_RESPONSE")
        }
    StrictJsonScanner(
        text,
        RESOURCE_JSON_DEPTH_MAX,
        RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
        RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
        "probe response",
    ) { code, _, _ -> throw ProbeParseFailure(if (code == "RESOURCE_LIMIT_EXCEEDED") code else "MALFORMED_RESPONSE") }.scan()
    val root =
        try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    return if (kind == SourceKind.INFLUXDB) parseInflux(root) else parsePromql(root)
}

private fun parsePromql(root: JsonObject): ProbeParsed {
    if (probeText(root["status"]) !=
        "success"
    ) {
        throw ProbeParseFailure(if (root["status"] == null) "MALFORMED_RESPONSE" else "PROMQL_QUERY_FAILED")
    }
    val warnings = mutableSetOf<String>()
    root["warnings"]?.let {
        if (it !is JsonArray) throw ProbeParseFailure("MALFORMED_RESPONSE")
        if (it.isNotEmpty()) warnings += "SOURCE_WARNINGS"
    }
    val data = root["data"] as? JsonObject ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    if (probeText(data["resultType"]) != "matrix") throw ProbeParseFailure("UNSUPPORTED_RESULT_TYPE")
    val results = data["result"] as? JsonArray ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    val series =
        results.map { element ->
            val entry = element as? JsonObject ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
            if ("histograms" in entry || "histogram" in entry) throw ProbeParseFailure("UNSUPPORTED_HISTOGRAM")
            val labels = probeLabels(entry["metric"])
            val values = entry["values"] as? JsonArray ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
            ProbeSeries(
                labels,
                values.count { sample ->
                    val pair = sample as? JsonArray
                    val value = pair?.takeIf { it.size == 2 }?.get(1) as? JsonPrimitive
                    value != null && value.isString && value.content !in PROBE_NONFINITE && value.content.toBigDecimalOrNull() != null
                },
            )
        }
    return ProbeParsed(series, warnings)
}

private fun parseInflux(root: JsonObject): ProbeParsed {
    if ("error" in root) throw ProbeParseFailure("INFLUXQL_QUERY_FAILED")
    val warnings = mutableSetOf<String>()
    if ("messages" in root) warnings += "SOURCE_WARNINGS"
    val results = root["results"] as? JsonArray ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    if (results.size != 1) throw ProbeParseFailure("MALFORMED_RESPONSE")
    val statement = results.single() as? JsonObject ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    if ("error" in statement) throw ProbeParseFailure("INFLUXQL_QUERY_FAILED")
    if ("messages" in statement) warnings += "SOURCE_WARNINGS"
    if (isPartial(statement)) warnings += "SOURCE_PARTIAL_RESPONSE"
    val entries =
        when (val value = statement["series"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value
            else -> throw ProbeParseFailure("MALFORMED_RESPONSE")
        }
    val series =
        entries.map { element ->
            val entry = element as? JsonObject ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
            if (isPartial(entry)) warnings += "SOURCE_PARTIAL_RESPONSE"
            val columns = (entry["columns"] as? JsonArray)?.map { probeText(it) } ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
            val valueIndex = columns.indexOf("value")
            val rows = entry["values"] as? JsonArray ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
            ProbeSeries(
                if (entry["tags"] == null) emptyMap() else probeLabels(entry["tags"]),
                rows.count { row ->
                    val cell = (row as? JsonArray)?.getOrNull(valueIndex)
                    valueIndex >= 0 && cell is JsonPrimitive && !cell.isString && cell.content.toBigDecimalOrNull() != null
                },
            )
        }
    return ProbeParsed(series, warnings)
}

private fun isPartial(value: JsonObject): Boolean =
    (value["partial"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

private fun probeText(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun probeLabels(element: JsonElement?): Map<String, String> {
    val value = element as? JsonObject ?: throw ProbeParseFailure("MALFORMED_RESPONSE")
    return value.mapValues { probeText(it.value) ?: throw ProbeParseFailure("MALFORMED_RESPONSE") }
}

private fun secretValues(
    profile: SourceProfile,
    environment: (String) -> String?,
): List<String> {
    val names =
        when (val auth = profile.auth) {
            SourceAuth.None -> emptyList()
            is SourceAuth.Bearer -> listOf(auth.tokenEnv)
            is SourceAuth.Token -> listOf(auth.tokenEnv)
            is SourceAuth.Basic -> listOf(auth.usernameEnv, auth.passwordEnv)
        } + listOfNotNull(profile.tls?.clientKeystorePasswordEnv)
    return names.mapNotNull { environment(it) }.filter { it.isNotEmpty() }
}

// Strings that identify the infrastructure and must not come back through a label:
// the profile address and the names of its credential variables.
private fun knownNames(profile: SourceProfile): List<String> {
    val variables =
        when (val auth = profile.auth) {
            SourceAuth.None -> emptyList()
            is SourceAuth.Bearer -> listOf(auth.tokenEnv)
            is SourceAuth.Token -> listOf(auth.tokenEnv)
            is SourceAuth.Basic -> listOf(auth.usernameEnv, auth.passwordEnv)
        } + listOfNotNull(profile.tls?.clientKeystorePasswordEnv)
    // A name of fewer than four characters would hide ordinary labels; such a name is not an identifying string anyway.
    return listOf(profile.baseUrl.toString().trimEnd('/')) + variables.filter { it.length >= MIN_HIDDEN_NAME_LENGTH }
}

// ADR 0033, P5: a label string is replaced by `***` BEFORE it is cut (a secret split by the cut would otherwise survive), then cleaned.
private class Redactor(
    private val secrets: List<String>,
) {
    var count = 0
        private set

    fun text(raw: String): String {
        val trimmed = raw.trimStart()
        if (secrets.any { raw.contains(it) } || SECRET_PREFIXES.any { trimmed.startsWith(it, ignoreCase = true) }) return mask()
        return clean(raw)
    }

    fun value(
        key: String,
        raw: String,
    ): String {
        val lowerKey = key.lowercase()
        if (SECRET_KEY_PARTS.any { it in lowerKey }) return mask()
        return text(raw)
    }

    private fun mask(): String {
        count++
        return "***"
    }

    private fun clean(raw: String): String {
        val (cleaned, cut) = cleanErrorText(raw, PROBE_MAX_CODE_POINTS)
        // The shared cleaner adds an ellipsis after the limit; the limit of the probe includes it.
        val text = (if (cut) cleanErrorText(raw, PROBE_MAX_CODE_POINTS - 1).first else cleaned).orEmpty()
        if (text.encodeToByteArray().size <= PROBE_MAX_STRING_BYTES) return text
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = String(Character.toChars(codePoint)).encodeToByteArray().size
            if (bytes + width > PROBE_MAX_STRING_BYTES) break
            out.appendCodePoint(codePoint)
            bytes += width
            index += Character.charCount(codePoint)
        }
        return out.toString()
    }
}

private val SECRET_PREFIXES = listOf("Bearer ", "Basic ", "Token ", "-----BEGIN", "eyJ")
private val SECRET_KEY_PARTS = listOf("pass", "secret", "token", "key", "auth", "cred")
private val PROBE_NONFINITE = setOf("NaN", "+Inf", "-Inf", "Inf")
private val PROBE_HTTP_CODES =
    setOf(
        "SOURCE_PROFILE_NOT_CONFIGURED",
        "SOURCE_AUTH_UNAVAILABLE",
        "SOURCE_AUTH_INVALID",
        "SOURCE_HTTP_AUTH",
        "SOURCE_HTTP_429",
        "SOURCE_HTTP_5XX",
        "SOURCE_HTTP_STATUS",
        "SOURCE_HTTP_ERROR",
        "SOURCE_TIMEOUT",
        "SOURCE_RESPONSE_TOO_LARGE",
        "SOURCE_TLS_CONFIG_INVALID",
        "SOURCE_TLS_HANDSHAKE_FAILED",
        "SOURCE_TLS_CLIENT_CERT_EXPIRED",
        "SOURCE_CANCELLED",
    )
private val PROBE_DECODER_CODES =
    setOf(
        "MALFORMED_RESPONSE",
        "PROMQL_QUERY_FAILED",
        "INFLUXQL_QUERY_FAILED",
        "SOURCE_WARNINGS",
        "SOURCE_PARTIAL_RESPONSE",
        "UNSUPPORTED_RESULT_TYPE",
        "UNSUPPORTED_HISTOGRAM",
        "AMBIGUOUS_SERIES",
        "AMBIGUOUS_STATEMENT",
        "LABEL_MISMATCH",
        "OFF_GRID_TIMESTAMP",
        "DUPLICATE_TIMESTAMP",
        "RESOURCE_LIMIT_EXCEEDED",
        "DUPLICATE_OBJECT_KEY",
    )

private const val NANOS_PER_MILLI = 1_000_000L
private const val PROBE_TOTAL_TIMEOUT_MILLIS = 15_000L
private const val PROBE_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
private const val PROBE_DEFAULT_WINDOW_MILLIS = 300_000L
private const val PROBE_DEFAULT_STEP_MILLIS = 15_000L
private const val PROBE_MAX_WINDOW_MILLIS = 3_600_000L
private const val PROBE_MAX_CELLS = 240L
private const val PROBE_MAX_SERIES = 20
private const val PROBE_MAX_LABEL_KEYS = 16
private const val PROBE_MAX_SAMPLE_VALUES = 5
private const val PROBE_MAX_CODE_POINTS = 128
private const val PROBE_MAX_STRING_BYTES = 256
private const val MIN_HIDDEN_NAME_LENGTH = 4
private const val STRICT_REQUESTS_PER_SECOND = 0.1
private const val STRICT_QUERY_COUNT = 20

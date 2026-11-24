package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import io.ltverdict.sources.ProbeInputFailure
import io.ltverdict.sources.ProbeSpec
import io.ltverdict.sources.ReloadResult
import io.ltverdict.sources.SourceConnections
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceKind
import io.ltverdict.sources.capturePostgresPhase
import io.ltverdict.sources.checkSourceProfiles
import io.ltverdict.sources.probeSource
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.sources.requireSnapshotGridStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.charset.CharacterCodingException
import java.util.Base64
import java.util.concurrent.Semaphore

internal fun Route.sourceRoutes(
    context: LocalApiContext,
    postgresCapturePermit: Semaphore,
    probePermit: Semaphore,
) {
    get("/api/sources") {
        call.requireOnlyQueries()
        val state = context.sourceState()
        call.respondJson(
            buildJsonObject {
                put(
                    "profiles",
                    buildJsonArray {
                        state.profiles.forEach { profile ->
                            add(
                                buildJsonObject {
                                    put("id", profile.id)
                                    put("source_kind", profile.sourceKind.wireName)
                                    put("transport", profile.transport.wireName)
                                    profile.arm?.let { put("arm", it) }
                                },
                            )
                        }
                        state.postgres.forEach { profile ->
                            add(
                                buildJsonObject {
                                    put("id", profile.id)
                                    put("source_kind", "postgresql")
                                    put("transport", "jdbc")
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    post("/api/sources/reload") {
        call.requireOnlyQueries()
        receiveEmptyReloadBody(call)
        val registry =
            context.sourceRegistry
                ?: throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_RELOAD_NOT_CONFIGURED", "Source connections file is not configured")
        when (val result = withContext(Dispatchers.IO) { registry.reload() }) {
            ReloadResult.NotConfigured ->
                throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_RELOAD_NOT_CONFIGURED", "Source connections file is not configured")
            ReloadResult.Busy -> conflict("BUSY", "Source reload is already running")
            ReloadResult.TooFrequent ->
                throw ApiFailure(HttpStatusCode.TooManyRequests, "RELOAD_TOO_FREQUENT", "Source reload rate limit exceeded")
            is ReloadResult.Invalid ->
                throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "SOURCE_CONFIG_INVALID",
                    "Source connections configuration is invalid",
                )
            is ReloadResult.Success -> {
                val ids = result.state.allProfileIds()
                call.respondJson(
                    buildJsonObject {
                        put("schema_version", "source-reload.v1")
                        put("status", "RELOADED")
                        put("profile_count", ids.size)
                        put("profiles", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
                        put("added", buildJsonArray { result.added.forEach { add(JsonPrimitive(it)) } })
                        put("removed", buildJsonArray { result.removed.forEach { add(JsonPrimitive(it)) } })
                        put("changed", buildJsonArray { result.changed.forEach { add(JsonPrimitive(it)) } })
                        put("revision", result.state.revision.orEmpty())
                    },
                )
            }
        }
    }

    post("/api/sources/validate") {
        call.requireOnlyQueries()
        call.requireJson()
        val body = receiveBaselineRequest(call, "Source validate")
        if (body.keys.any { it != "profile_id" }) throw invalidProbe("Source validate request contains unsupported fields")
        val profileId = body["profile_id"]?.let { validateIdentifier(it, "profile_id") }
        val state = context.sourceState()
        val report =
            try {
                checkSourceProfiles(SourceConnections(state.profiles, state.postgres), profileId, System::getenv, System::getProperty)
            } catch (failure: ProbeInputFailure) {
                throw ApiFailure(HttpStatusCode.NotFound, failure.code, "Source profile was not found")
            }
        call.respondJson(report)
    }

    post("/api/sources/probe") {
        call.requireOnlyQueries()
        call.requireJson()
        val body = receiveBaselineRequest(call, "Source probe")
        if (body.keys.any { it !in PROBE_ALLOWED_KEYS }) throw invalidProbe("Source probe request contains unsupported fields")
        val profileId = validateIdentifier(body["profile_id"] ?: throw invalidProbe("profile_id is required"), "profile_id")
        val queryId = validateIdentifier(body["query_id"] ?: throw invalidProbe("query_id is required"), "query_id")
        val defaults = ProbeSpec(queryId)
        val windowMillis = body["window_ms"]?.let { parseLongField(it, "window_ms") } ?: defaults.windowMillis
        val stepMillis = body["step_ms"]?.let { parseLongField(it, "step_ms") } ?: defaults.stepMillis
        val endEpochMillis = body["end_epoch_ms"]?.let { parseLongField(it, "end_epoch_ms") }
        val spec = ProbeSpec(queryId, windowMillis, stepMillis, endEpochMillis)

        val state = context.sourceState()
        if (state.postgres.any { it.id == profileId }) throw invalidProbe("PostgreSQL profiles do not support query probes")
        val profile =
            state.profiles.singleOrNull { it.id == profileId }
                ?: throw ApiFailure(HttpStatusCode.NotFound, "SOURCE_PROFILE_NOT_FOUND", "Source profile was not found")
        if (profile.sourceKind == SourceKind.OPENSEARCH) throw invalidProbe("OpenSearch profiles do not support query probes")
        if (profile.queries.none { it.id == queryId }) {
            throw ApiFailure(HttpStatusCode.NotFound, "QUERY_NOT_FOUND", "Source query was not found")
        }
        validateProbeWindow(spec)

        if (!probePermit.tryAcquire()) conflict("BUSY", "Too many source probes are running")
        val http = state.http ?: SourceHttp(state.profiles)
        val report =
            try {
                withContext(Dispatchers.IO) { probeSource(profile, spec, http) }
            } catch (failure: ProbeInputFailure) {
                when (failure.code) {
                    "QUERY_NOT_FOUND" -> throw ApiFailure(HttpStatusCode.NotFound, failure.code, "Source query was not found")
                    "SOURCE_REQUEST_INVALID" ->
                        throw ApiFailure(HttpStatusCode.BadRequest, failure.code, "Source probe window is invalid")
                    else -> throw invalidProbe("Source probe request is invalid")
                }
            } finally {
                probePermit.release()
            }
        call.respondJson(report)
    }

    post("/api/resources/semantic-hash") {
        call.requireOnlyQueries()
        call.requireJson()
        val length =
            call.request.contentLength()
                ?: throw ApiFailure(HttpStatusCode.LengthRequired, "LENGTH_REQUIRED", "Resource snapshot request requires Content-Length")
        if (length < 0) malformed("Content-Length is invalid")
        if (length > MAX_RESOURCE_SNAPSHOT_BYTES) tooLarge("Resource snapshot exceeds 32 MiB")
        val validation = withContext(Dispatchers.IO) { validateResourceSnapshot(call.receiveChannel().toInputStream()) }
        when (validation) {
            is ResourceValidation.Invalid -> {
                if (validation.errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }) {
                    tooLarge("Resource snapshot exceeds 32 MiB")
                }
                throw InvalidResources(validation.errors)
            }
            is ResourceValidation.Valid -> {
                call.respondJson(
                    buildJsonObject {
                        put("schema_version", "resource-hash.v1")
                        put("semantic_sha256", validation.semanticSha256)
                        put("config_sha256", validation.configSha256?.let(::JsonPrimitive) ?: JsonNull)
                        put("load_input_sha256", validation.snapshot.loadInputSha256?.let(::JsonPrimitive) ?: JsonNull)
                        put("series_count", validation.snapshot.series.size)
                        put("point_count", validation.snapshot.pointCount)
                    },
                )
            }
        }
    }

    listOf("pre", "post").forEach { phase ->
        post("/api/sources/postgresql/$phase") {
            call.requireOnlyQueries()
            call.requireMultipart()
            val state = context.sourceState()
            val (profileId, pre) = receivePostgresCapture(call, phase == "post")
            val profile = state.postgres.singleOrNull { it.id == profileId } ?: malformed("Source profile is not configured")
            if (!postgresCapturePermit.tryAcquire()) conflict("BUSY", "PostgreSQL capture is already running")
            val captured =
                try {
                    withContext(Dispatchers.IO) {
                        val jobContext = currentCoroutineContext()
                        capturePostgresPhase(profile, pre, post = phase == "post", checkCancelled = { jobContext.ensureActive() })
                    }
                } catch (failure: IllegalArgumentException) {
                    throw ApiFailure(
                        HttpStatusCode.UnprocessableEntity,
                        failure.message?.takeIf { Regex("PG_[A-Z_]+").matches(it) } ?: "PG_CAPTURE_FAILED",
                        "PostgreSQL capture failed",
                    )
                } finally {
                    postgresCapturePermit.release()
                }
            call.respondJson(
                buildJsonObject {
                    put("schema_version", "postgres-capture.v1")
                    put("phase_json", canonicalJson(captured.phase).decodeToString())
                    put(
                        "pg_profile_html_base64",
                        captured.pgProfileHtml?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) } ?: JsonNull,
                    )
                },
            )
        }
    }
}

private suspend fun receiveEmptyReloadBody(call: ApplicationCall) {
    if ((call.request.contentLength() ?: 0L) > MAX_BASELINE_REQUEST_BYTES) tooLarge("Source reload request exceeds 16 KiB")
    val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MAX_BASELINE_REQUEST_BYTES + 1) } }
    if (bytes.size > MAX_BASELINE_REQUEST_BYTES) tooLarge("Source reload request exceeds 16 KiB")
    if (bytes.isEmpty()) return
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            malformed("Source reload request must be UTF-8 JSON")
        }
    if (text.isBlank()) return
    call.requireJson()
    var depth = 0
    var quoted = false
    var escaped = false
    text.forEach { character ->
        if (quoted) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 8) tooLarge("Source reload JSON depth exceeds 8")
                '}', ']' -> depth -= 1
            }
        }
    }
    val parsed =
        try {
            Json.parseToJsonElement(text) as? JsonObject ?: malformed("Source reload body must be empty")
        } catch (_: SerializationException) {
            malformed("Source reload JSON is malformed")
        }
    if (parsed.isNotEmpty()) malformed("Source reload body must be empty")
}

private fun invalidProbe(message: String): ApiFailure = ApiFailure(HttpStatusCode.BadRequest, "INVALID_PROBE", message)

private fun validateIdentifier(
    element: JsonElement,
    field: String,
): String {
    val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw invalidProbe("$field must be a string")
    if (text.isBlank() || text.encodeToByteArray().size > 128 || text.any(Char::isISOControl)) {
        throw invalidProbe("$field is invalid")
    }
    return text
}

private fun parseLongField(
    element: JsonElement,
    field: String,
): Long {
    val primitive = (element as? JsonPrimitive)?.takeIf { !it.isString } ?: throw invalidProbe("$field must be an integer")
    return primitive.content.toLongOrNull() ?: throw invalidProbe("$field must be an integer")
}

private fun validateProbeWindow(spec: ProbeSpec) {
    try {
        requireSnapshotGridStep(spec.stepMillis)
    } catch (_: IllegalArgumentException) {
        throw ApiFailure(HttpStatusCode.BadRequest, "SOURCE_REQUEST_INVALID", "Source probe step_ms is invalid")
    }
    if (spec.windowMillis !in 1..PROBE_MAX_WINDOW_MILLIS || spec.windowMillis % spec.stepMillis != 0L) {
        throw ApiFailure(HttpStatusCode.BadRequest, "SOURCE_REQUEST_INVALID", "Source probe window_ms is invalid")
    }
    val cells = spec.windowMillis / spec.stepMillis
    if (cells !in 1..PROBE_MAX_CELLS) {
        throw ApiFailure(HttpStatusCode.BadRequest, "SOURCE_REQUEST_INVALID", "Source probe window exceeds 240 cells")
    }
    spec.endEpochMillis?.let { endMillis ->
        if (endMillis < spec.windowMillis || endMillis > MAX_TIMESTAMP_EPOCH_MILLIS) {
            throw ApiFailure(HttpStatusCode.BadRequest, "SOURCE_REQUEST_INVALID", "Source probe end_epoch_ms is invalid")
        }
    }
}

private val PROBE_ALLOWED_KEYS = setOf("profile_id", "query_id", "window_ms", "step_ms", "end_epoch_ms")
private const val PROBE_MAX_WINDOW_MILLIS = 3_600_000L
private const val PROBE_MAX_CELLS = 240L

private suspend fun receivePostgresCapture(
    call: ApplicationCall,
    post: Boolean,
): Pair<String, JsonObject?> {
    val length =
        call.request.contentLength()
            ?: throw ApiFailure(HttpStatusCode.LengthRequired, "LENGTH_REQUIRED", "Capture request requires Content-Length")
    if (length > MAX_RESOURCE_BYTES + MAX_POLICY_BYTES) tooLarge("Capture request exceeds its resource limit")
    var profileId: String? = null
    var pre: JsonObject? = null
    var parts = 0
    try {
        call.receiveMultipart(formFieldLimit = (MAX_RESOURCE_BYTES + 1).toLong()).forEachPart { part ->
            try {
                if (++parts > 2) malformed("Capture request has too many parts")
                when {
                    part is PartData.FormItem && part.name == "profile_id" && profileId == null -> {
                        if (part.value.isBlank() ||
                            part.value.encodeToByteArray().size > 128 ||
                            part.value.any(Char::isISOControl)
                        ) {
                            malformed("Profile ID is invalid")
                        }
                        profileId = part.value
                    }
                    part is PartData.FileItem && part.name == "pre" && post && pre == null -> {
                        pre = withContext(Dispatchers.IO) { readPostgresAnalysisInput(pre = part.provider().toInputStream()).pre }
                    }
                    else -> malformed("Capture multipart body is invalid")
                }
            } finally {
                part.release()
            }
        }
    } catch (failure: ApiFailure) {
        throw failure
    } catch (_: IllegalArgumentException) {
        malformed("PostgreSQL pre phase is invalid")
    } catch (_: java.io.IOException) {
        malformed("Capture multipart body is malformed")
    }
    return (profileId ?: malformed("Profile ID is required")) to pre
}

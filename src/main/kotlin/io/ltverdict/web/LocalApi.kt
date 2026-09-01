package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.isMultipart
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.PolicyValidationError
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.validatePolicy
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.jobs.JobStatus
import io.ltverdict.jobs.SubmitResult
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.security.SecureRandom
import java.util.HexFormat

internal data class LocalApiContext(
    val store: RunBundleStore,
    val jobs: AnalysisJobs,
)

internal fun Application.installLocalApi(context: LocalApiContext) {
    val sessionToken = randomToken()
    val csrfToken = randomToken()

    intercept(ApplicationCallPipeline.Plugins) {
        call.addSecurityHeaders()
        val authority = "127.0.0.1:${call.request.local.serverPort}"
        if (call.request.headers[HttpHeaders.Host] != authority) {
            call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN", "Request host is not allowed")
            finish()
            return@intercept
        }
        if (call.request.httpMethod == HttpMethod.Post || call.request.httpMethod == HttpMethod.Delete) {
            val allowedOrigin = "http://$authority"
            if (call.request.headers[HttpHeaders.Origin] != allowedOrigin ||
                call.request.cookies[SESSION_COOKIE] != sessionToken ||
                call.request.headers[CSRF_HEADER] != csrfToken
            ) {
                call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN", "Mutation credentials are invalid")
                finish()
                return@intercept
            }
        }
        try {
            proceed()
        } catch (failure: InvalidPolicy) {
            call.respondPolicyValidation(failure.validation)
            finish()
        } catch (failure: ApiFailure) {
            call.respondError(failure.status, failure.code, failure.message)
            finish()
        }
    }

    routing {
        get("/api/bootstrap") {
            call.response.headers.append(
                HttpHeaders.SetCookie,
                "$SESSION_COOKIE=$sessionToken; Path=/; HttpOnly; SameSite=Strict",
            )
            call.respondJson(
                buildJsonObject {
                    put("csrf_token", csrfToken)
                    put("max_upload_bytes", MAX_UPLOAD_BYTES)
                },
            )
        }

        get("/api/runs") {
            call.requireOnlyQueries("after", "limit")
            val after = call.singleQuery("after")
            val limit = call.intQuery("limit", DEFAULT_RUN_LIMIT, 1..MAX_RUN_LIMIT)
            val page =
                try {
                    withContext(Dispatchers.IO) { context.store.listRuns(after, limit) }
                } catch (_: IllegalArgumentException) {
                    malformed("Run query is invalid")
                }
            call.respondJson(
                buildJsonObject {
                    put(
                        "runs",
                        buildJsonArray {
                            page.runs.forEach { run ->
                                add(
                                    buildJsonObject {
                                        put("run_id", run.runId)
                                        put("source_type", run.sourceType.wireName)
                                        put("sha256", run.sha256)
                                        put("size_bytes", run.sizeBytes)
                                        put("original_filename", run.originalFilename)
                                    },
                                )
                            }
                        },
                    )
                    put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }

        post("/api/inputs") {
            call.requireMultipart()
            val contentLength = call.request.contentLength()
            if (contentLength != null && contentLength > MAX_UPLOAD_REQUEST_BYTES) tooLarge("Input exceeds 4 GiB")
            val accepted = receiveInput(call, context.store)
            call.respondJson(accepted.toJson(), HttpStatusCode.Created)
        }

        post("/api/policies/validate") {
            call.requireJson()
            val validation = receivePolicy(call)
            call.respondPolicyValidation(validation)
        }

        post("/api/jobs") {
            call.requireMultipart()
            val request = receiveJob(call, context.store)
            when (val submitted = context.jobs.submit(request)) {
                is SubmitResult.Accepted -> call.respondJson(submitted.status.toJson(), HttpStatusCode.Accepted)
                SubmitResult.Busy -> conflict("BUSY", "Analysis queue is full")
            }
        }

        get("/api/jobs/{jobId}") {
            val status = context.jobs.status(call.parameters["jobId"].orEmpty()) ?: notFound("Job was not found")
            call.respondJson(status.toJson())
        }

        delete("/api/jobs/{jobId}") {
            val status = context.jobs.cancel(call.parameters["jobId"].orEmpty()) ?: notFound("Job was not found")
            call.respondJson(status.toJson())
        }

        get("/api/runs/{runId}/analyses/{analysisId}/result") {
            val stored = context.store.requireAnalysis(call)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(RESULT_FILE)) }
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/runs/{runId}/analyses/{analysisId}/buckets") {
            call.requireOnlyQueries("rollup", "from_ms", "to_ms", "limit")
            val rollup = call.singleQuery("rollup")?.toIntOrNull()
            if (rollup !in ROLLUPS) malformed("rollup must be 1, 10, 30 or 60")
            val from = call.longQuery("from_ms", 0)
            val to = call.optionalLongQuery("to_ms")
            val limit = call.intQuery("limit", DEFAULT_BUCKET_LIMIT, 1..MAX_BUCKET_LIMIT)
            if (from < 0 || to != null && to <= from) malformed("Bucket range is invalid")
            val stored = context.store.requireAnalysis(call)
            val file = if (rollup == 1) NORMALIZED_FILE else "rollup-${rollup}s.ndjson"
            val page = withContext(Dispatchers.IO) { readBucketPage(stored.path.resolve(file), from, to, limit) }
            call.respondJson(
                buildJsonObject {
                    put("buckets", JsonArray(page.buckets))
                    put("next_from_ms", page.nextFromMillis?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }

        route("/{...}") {
            handle {
                notFound("Endpoint was not found")
            }
        }
    }
}

private suspend fun receiveInput(
    call: ApplicationCall,
    store: RunBundleStore,
): AcceptedInput {
    // ponytail: one temp file prevents a rejected multipart tail from publishing a run; remove with store pre-commit validation.
    val temporary = withContext(Dispatchers.IO) { Files.createTempFile("ltv-upload-", ".tmp") }
    var filename: String? = null
    var invalidParts = false
    try {
        try {
            call.receiveMultipart(formFieldLimit = MAX_UPLOAD_BYTES + 1).forEachPart { part ->
                try {
                    if (part is PartData.FileItem && part.name == "file" && filename == null && !invalidParts) {
                        filename = part.originalFileName ?: malformed("Uploaded file needs a filename")
                        withContext(Dispatchers.IO) {
                            part.provider().toInputStream().use { input ->
                                Files.newOutputStream(temporary).use(input::transferTo)
                            }
                        }
                        if (withContext(Dispatchers.IO) { Files.size(temporary) } > MAX_UPLOAD_BYTES) {
                            tooLarge("Input exceeds 4 GiB")
                        }
                    } else {
                        invalidParts = true
                    }
                } finally {
                    part.release()
                }
            }
        } catch (failure: ApiFailure) {
            throw failure
        } catch (_: Exception) {
            if (withContext(Dispatchers.IO) { Files.size(temporary) } > MAX_UPLOAD_BYTES) {
                tooLarge("Input exceeds 4 GiB")
            }
            malformed("Multipart body is malformed")
        }
        if (invalidParts || filename == null) malformed("Multipart body must contain exactly one file part")
        return try {
            withContext(Dispatchers.IO) {
                Files.newInputStream(temporary).use { input ->
                    store.acceptInput(input, checkNotNull(filename), MAX_UPLOAD_BYTES)
                }
            }
        } catch (failure: IllegalArgumentException) {
            mapInputFailure(failure)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temporary) }
    }
}

private suspend fun receivePolicy(call: ApplicationCall): PolicyValidation {
    val contentLength = call.request.contentLength()
    if (contentLength != null && contentLength > MAX_POLICY_BYTES) tooLarge("Policy exceeds 1 MiB")
    val validation =
        withContext(Dispatchers.IO) {
            validatePolicy(call.receiveChannel().toInputStream(), MAX_POLICY_BYTES)
        }
    validation.failureResponse()?.let { throw it }
    return validation
}

private suspend fun receiveJob(
    call: ApplicationCall,
    store: RunBundleStore,
): AnalysisRequest {
    var runId: String? = null
    var policy: PolicyValidation.Valid? = null
    var policySeen = false
    var invalidParts = false
    try {
        call.receiveMultipart(formFieldLimit = (MAX_POLICY_BYTES + 1).toLong()).forEachPart { part ->
            try {
                when {
                    part is PartData.FormItem && part.name == "run_id" && runId == null && !invalidParts -> {
                        if (part.value.isEmpty() || part.value.encodeToByteArray().size > MAX_RUN_ID_BYTES) {
                            malformed("run_id is invalid")
                        }
                        runId = part.value
                    }

                    part is PartData.FileItem && part.name == "policy" && !policySeen && !invalidParts -> {
                        policySeen = true
                        val validation =
                            withContext(Dispatchers.IO) {
                                validatePolicy(part.provider().toInputStream(), MAX_POLICY_BYTES)
                            }
                        validation.failureResponse()?.let { throw it }
                        policy =
                            when (validation) {
                                is PolicyValidation.Valid -> validation
                                is PolicyValidation.Invalid -> throw InvalidPolicy(validation)
                            }
                    }

                    else -> invalidParts = true
                }
            } finally {
                part.release()
            }
        }
    } catch (failure: ApiFailure) {
        throw failure
    } catch (failure: InvalidPolicy) {
        throw failure
    } catch (_: Exception) {
        malformed("Multipart body is malformed")
    }
    if (invalidParts || runId == null || !RUN_ID.matches(runId)) malformed("Job multipart body is invalid")
    val input =
        try {
            withContext(Dispatchers.IO) { store.requireInput(checkNotNull(runId)) }
        } catch (_: IllegalArgumentException) {
            notFound("Run was not found")
        }
    return AnalysisRequest(input, policy)
}

private fun PolicyValidation.failureResponse(): ApiFailure? =
    when (this) {
        is PolicyValidation.Valid -> null
        is PolicyValidation.Invalid ->
            when {
                errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" } ->
                    ApiFailure(HttpStatusCode.PayloadTooLarge, "RESOURCE_LIMIT_EXCEEDED", "Policy exceeds its resource limit")

                errors.any { it.code == "MALFORMED_JSON" || it.code == "POLICY_READ_ERROR" } ->
                    ApiFailure(HttpStatusCode.BadRequest, "MALFORMED_JSON", "Policy JSON is malformed")

                else -> null
            }
    }

private suspend fun ApplicationCall.respondPolicyValidation(validation: PolicyValidation) {
    when (validation) {
        is PolicyValidation.Valid ->
            respondJson(
                buildJsonObject {
                    put("valid", true)
                    put("policy", Json.parseToJsonElement(validation.canonicalBytes.decodeToString()))
                    put("sha256", validation.sha256)
                },
            )

        is PolicyValidation.Invalid ->
            respondJson(
                buildJsonObject {
                    put("valid", false)
                    put("errors", buildJsonArray { validation.errors.forEach { add(it.toJson()) } })
                },
                HttpStatusCode.UnprocessableEntity,
            )
    }
}

private fun PolicyValidationError.toJson(): JsonObject =
    buildJsonObject {
        put("code", code)
        put("json_pointer", jsonPointer)
        put("message", message)
    }

private fun AcceptedInput.toJson(): JsonObject =
    buildJsonObject {
        put("run_id", runId)
        put("source_type", sourceType.wireName)
        put("sha256", sha256)
        put("size_bytes", sizeBytes)
        put("original_filename", originalFilename)
    }

private fun JobStatus.toJson(): JsonObject =
    buildJsonObject {
        put("job_id", jobId)
        put("state", state.name)
        put("processed_bytes", processedBytes)
        put("total_bytes", totalBytes)
        put("run_id", runId)
        put("analysis_id", analysisId?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "diagnostic",
            diagnostic?.let { diagnostic ->
                buildJsonObject {
                    put("code", diagnostic.code)
                    put("message", diagnostic.message)
                    put("source_offset", diagnostic.sourceOffset?.let(::JsonPrimitive) ?: JsonNull)
                }
            } ?: JsonNull,
        )
    }

private fun readBucketPage(
    path: java.nio.file.Path,
    from: Long,
    to: Long?,
    limit: Int,
): BucketPage {
    val buckets = mutableListOf<JsonElement>()
    var next: Long? = null
    Files.newBufferedReader(path).useLines { lines ->
        for (line in lines) {
            val bucket = Json.parseToJsonElement(line).jsonObject
            val start = bucket.getValue("bucket_start_ms").jsonPrimitive.long
            if (start < from) continue
            if (to != null && start >= to) break
            if (buckets.size == limit) {
                next = start
                break
            }
            buckets += bucket
        }
    }
    return BucketPage(buckets, next)
}

private data class BucketPage(
    val buckets: List<JsonElement>,
    val nextFromMillis: Long?,
)

private suspend fun RunBundleStore.requireAnalysis(call: ApplicationCall): io.ltverdict.storage.StoredAnalysis {
    val runId = call.parameters["runId"] ?: notFound("Run was not found")
    val analysisId = call.parameters["analysisId"] ?: notFound("Analysis was not found")
    return withContext(Dispatchers.IO) {
        try {
            readAnalysis(runId, analysisId) ?: notFound("Analysis was not found")
        } catch (_: IllegalArgumentException) {
            notFound("Analysis was not found")
        }
    }
}

private fun ApplicationCall.requireOnlyQueries(vararg allowed: String) {
    val parameters = request.queryParameters
    if (parameters.names().any { it !in allowed } || parameters.names().any { parameters.getAll(it)?.size != 1 }) {
        malformed("Query parameters are invalid")
    }
}

private fun ApplicationCall.singleQuery(name: String): String? = request.queryParameters.getAll(name)?.singleOrNull()

private fun ApplicationCall.intQuery(
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val raw = singleQuery(name) ?: return default
    return raw.toIntOrNull()?.takeIf { it in range } ?: malformed("$name is invalid")
}

private fun ApplicationCall.longQuery(
    name: String,
    default: Long,
): Long {
    val raw = singleQuery(name) ?: return default
    return raw.toLongOrNull() ?: malformed("$name is invalid")
}

private fun ApplicationCall.optionalLongQuery(name: String): Long? {
    val raw = singleQuery(name) ?: return null
    return raw.toLongOrNull() ?: malformed("$name is invalid")
}

private fun ApplicationCall.requireMultipart() {
    if (!request.isMultipart()) unsupportedMedia("multipart/form-data is required")
}

private fun ApplicationCall.requireJson() {
    if (!request.contentType().match(ContentType.Application.Json)) unsupportedMedia("application/json is required")
}

private fun ApplicationCall.addSecurityHeaders() {
    response.headers.append("Content-Security-Policy", CONTENT_SECURITY_POLICY)
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

private suspend fun ApplicationCall.respondJson(
    body: JsonElement,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondBytes(canonicalJson(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respondJson(
    buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("details", JsonArray(emptyList()))
            },
        )
    },
    status,
)

private fun mapInputFailure(failure: IllegalArgumentException): Nothing =
    when (failure.message) {
        "RESOURCE_LIMIT_EXCEEDED" -> tooLarge("Input exceeds 4 GiB")
        "UNSUPPORTED_INPUT", "EMPTY_INPUT" -> unsupportedInput("Input format is unsupported")
        else -> malformed("Upload metadata is invalid")
    }

private fun malformed(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "MALFORMED_REQUEST", message)

private fun notFound(message: String): Nothing = throw ApiFailure(HttpStatusCode.NotFound, "NOT_FOUND", message)

private fun conflict(
    code: String,
    message: String,
): Nothing = throw ApiFailure(HttpStatusCode.Conflict, code, message)

private fun tooLarge(message: String): Nothing = throw ApiFailure(HttpStatusCode.PayloadTooLarge, "RESOURCE_LIMIT_EXCEEDED", message)

private fun unsupportedMedia(message: String): Nothing =
    throw ApiFailure(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", message)

private fun unsupportedInput(message: String): Nothing = throw ApiFailure(HttpStatusCode.UnprocessableEntity, "UNSUPPORTED_INPUT", message)

private class ApiFailure(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

private class InvalidPolicy(
    val validation: PolicyValidation.Invalid,
) : RuntimeException()

private fun randomToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return HexFormat.of().formatHex(bytes)
}

private const val SESSION_COOKIE = "ltv_session"
private const val CSRF_HEADER = "X-LTV-CSRF"
private const val MAX_UPLOAD_BYTES = 4_294_967_296L
private const val MAX_MULTIPART_OVERHEAD_BYTES = 65_536L
private const val MAX_UPLOAD_REQUEST_BYTES = MAX_UPLOAD_BYTES + MAX_MULTIPART_OVERHEAD_BYTES
private const val MAX_POLICY_BYTES = 1_048_576
private const val MAX_RUN_ID_BYTES = 128
private const val DEFAULT_RUN_LIMIT = 100
private const val MAX_RUN_LIMIT = 100
private const val DEFAULT_BUCKET_LIMIT = 500
private const val MAX_BUCKET_LIMIT = 500
private const val RESULT_FILE = "analysis-result.json"
private const val NORMALIZED_FILE = "normalized-1s.ndjson"
private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
private val ROLLUPS = setOf(1, 10, 30, 60)
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

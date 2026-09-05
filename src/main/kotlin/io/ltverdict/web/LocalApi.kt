package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.withCharset
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
import io.ltverdict.core.DiagnosticValidation
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.PolicyValidationError
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.compareAnalyses
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.statisticalBaselineSelection
import io.ltverdict.core.validateDiagnosticBinding
import io.ltverdict.core.validateDiagnosticPlan
import io.ltverdict.core.validatePolicy
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.jobs.JobStatus
import io.ltverdict.jobs.SubmitResult
import io.ltverdict.report.renderAsciiDocReport
import io.ltverdict.report.renderHtmlReport
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceRequest
import io.ltverdict.sources.readOpenSearchContext
import io.ltverdict.sources.readSourceRequest
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.HdrHistogram.PackedHistogram
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

internal data class LocalApiContext(
    val store: RunBundleStore,
    val jobs: AnalysisJobs,
    val sourceProfiles: List<SourceProfile> = emptyList(),
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
        } catch (failure: InvalidResources) {
            call.respondError(
                HttpStatusCode.UnprocessableEntity,
                "INVALID_RESOURCES",
                "Resource snapshot is invalid",
                failure.errors,
            )
            finish()
        } catch (failure: InvalidDiagnostics) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "INVALID_DIAGNOSTICS", "Diagnostic plan is invalid", failure.errors)
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

        get("/api/baseline") {
            call.requireOnlyQueries()
            val baseline = baselineOperation { context.store.readBaseline() }
            call.respondJson(buildJsonObject { put("baseline", baseline ?: JsonNull) })
        }

        post("/api/baseline") {
            call.requireOnlyQueries()
            call.requireJson()
            val request = receiveBaselineRequest(call)
            val selected = selectBaseline(request, context.store)
            val baseline = baselineOperation { context.store.replaceBaseline(selected) }
            call.respondJson(buildJsonObject { put("baseline", baseline) })
        }

        delete("/api/baseline") {
            call.requireOnlyQueries()
            baselineOperation { context.store.clearBaseline() }
            call.respondJson(buildJsonObject { put("baseline", JsonNull) })
        }

        get("/api/runs/{runId}/analyses/{analysisId}/comparison") {
            call.requireOnlyQueries("baseline_window", "current_window", "min_change_percent", "min_error_rate_delta")
            val windows = call.windowComparisonQuery()
            val current =
                buildJsonObject {
                    put("run_id", call.parameters["runId"].orEmpty())
                    put("analysis_id", call.parameters["analysisId"].orEmpty())
                }.baselineReference()
            val selected = baselineOperation { context.store.readBaseline() } ?: notFound("No baseline is selected")
            val (baselineResult, baselineIdentity) = context.store.baselineDocuments(selected.getValue("reference").jsonObject)
            val (currentResult, currentIdentity) = context.store.baselineDocuments(current)
            call.respondJson(compareAnalyses(selected, current, baselineResult, baselineIdentity, currentResult, currentIdentity, windows))
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
            val request = receiveJob(call, context.store, context.sourceProfiles)
            when (val submitted = context.jobs.submit(request)) {
                is SubmitResult.Accepted -> call.respondJson(submitted.status.toJson(), HttpStatusCode.Accepted)
                SubmitResult.Busy -> conflict("BUSY", "Analysis queue is full")
            }
        }

        get("/api/sources") {
            call.requireOnlyQueries()
            call.respondJson(
                buildJsonObject {
                    put(
                        "profiles",
                        buildJsonArray {
                            context.sourceProfiles.forEach { profile ->
                                add(
                                    buildJsonObject {
                                        put("id", profile.id)
                                        put("source_kind", profile.sourceKind.wireName)
                                        put("transport", profile.transport.wireName)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        get("/api/runs/{runId}/analyses/{analysisId}/resource-snapshot") {
            call.requireOnlyQueries()
            val stored = context.store.requireAnalysis(call)
            if (stored.artifacts.none { it.path == "resource-snapshot.json" }) notFound("Resource snapshot was not found")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve("resource-snapshot.json")) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"resource-snapshot.json\"")
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/runs/{runId}/analyses/{analysisId}/source-context") {
            call.requireOnlyQueries()
            val stored = context.store.requireAnalysis(call)
            if (stored.artifacts.none { it.path == "opensearch-errors.json" }) notFound("Source context was not found")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve("opensearch-errors.json")) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"opensearch-errors.json\"")
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
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

        get("/api/runs/{runId}/analyses/{analysisId}/report") {
            call.requireOnlyQueries("format")
            val format = call.singleQuery("format")
            if (format !in setOf("json", "html", "asciidoc")) malformed("format must be json, html or asciidoc")
            val stored = context.store.requireAnalysis(call)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(RESULT_FILE)) }
            val analysisId = stored.path.fileName.toString()
            val report =
                when (format) {
                    "json" -> bytes
                    "html" -> renderHtmlReport(bytes, analysisId)
                    else -> renderAsciiDocReport(bytes, analysisId)
                }
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"lt-verdict-$analysisId.${if (format == "asciidoc") "adoc" else format}\"",
            )
            val contentType =
                when (format) {
                    "json" -> ContentType.Application.Json
                    "html" -> ContentType.Text.Html.withCharset(Charsets.UTF_8)
                    else -> ContentType.Text.Plain.withCharset(Charsets.UTF_8)
                }
            call.respondBytes(report, contentType, HttpStatusCode.OK)
        }

        get("/api/runs/{runId}/analyses") {
            call.requireOnlyQueries("after", "limit")
            val after = call.singleQuery("after")
            val limit = call.intQuery("limit", DEFAULT_ANALYSIS_LIMIT, 1..MAX_ANALYSIS_LIMIT)
            val page =
                try {
                    withContext(Dispatchers.IO) { context.store.listAnalyses(call.parameters["runId"].orEmpty(), after, limit) }
                } catch (_: NoSuchElementException) {
                    notFound("Run was not found")
                } catch (_: IllegalArgumentException) {
                    malformed("Analysis query is invalid")
                }
            call.respondJson(
                buildJsonObject {
                    put(
                        "analyses",
                        buildJsonArray {
                            page.analyses.forEach { analysis ->
                                add(
                                    buildJsonObject {
                                        put("analysis_id", analysis.analysisId)
                                        put("policy_sha256", analysis.policySha256)
                                        put("policy_verdict", analysis.policyVerdict)
                                        put("run_validity", analysis.runValidity)
                                    },
                                )
                            }
                        },
                    )
                    put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
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

        route("/api/{...}") {
            handle {
                notFound("Endpoint was not found")
            }
        }
    }
}

private suspend fun receiveBaselineRequest(call: ApplicationCall): JsonObject {
    if ((call.request.contentLength() ?: 0) > MAX_BASELINE_REQUEST_BYTES) tooLarge("Baseline request exceeds 16 KiB")
    val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MAX_BASELINE_REQUEST_BYTES + 1) } }
    if (bytes.size > MAX_BASELINE_REQUEST_BYTES) tooLarge("Baseline request exceeds 16 KiB")
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: java.nio.charset.CharacterCodingException) {
            malformed("Baseline request must be UTF-8 JSON")
        }
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
                '{', '[' -> if (++depth > 8) tooLarge("Baseline request JSON depth exceeds 8")
                '}', ']' -> depth -= 1
            }
        }
    }
    return try {
        Json.parseToJsonElement(text) as? JsonObject ?: malformed("Baseline request must be an object")
    } catch (_: kotlinx.serialization.SerializationException) {
        malformed("Baseline JSON is malformed")
    }
}

private suspend fun selectBaseline(
    request: JsonObject,
    store: RunBundleStore,
): JsonObject {
    val mode = request.baselineString("mode")
    val series = request.baselineString("series")
    if (series.isBlank() || series.encodeToByteArray().size > 128) malformed("Comparison series must contain 1–128 UTF-8 bytes")
    return when (mode) {
        "manual" -> {
            if (request.keys != setOf("mode", "series", "reference")) malformed("Manual baseline fields are invalid")
            val reference = (request["reference"] as? JsonObject)?.baselineReference() ?: malformed("Baseline reference is invalid")
            store.baselineDocuments(reference)
            manualBaselineSelection(series, reference)
        }
        "statistical" -> {
            if (request.keys != setOf("mode", "series", "candidates", "comparable")) malformed("Statistical baseline fields are invalid")
            val comparable =
                (request["comparable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: malformed("comparable must be a boolean")
            if (!comparable) baselineIneligible("BASELINE_COMPARABILITY_UNCONFIRMED")
            val values = request["candidates"] as? JsonArray ?: malformed("candidates must be an array")
            if (values.size !in 3..20) baselineIneligible("BASELINE_CANDIDATE_COUNT")
            val references = values.map { (it as? JsonObject)?.baselineReference() ?: malformed("Candidate reference is invalid") }
            if (references.map { it.getValue("run_id") }.toSet().size != references.size) baselineIneligible("BASELINE_DUPLICATE_RUN")
            val documents = references.map { store.baselineDocuments(it) }
            try {
                statisticalBaselineSelection(series, references, documents.map { it.first }, documents.map { it.second })
            } catch (failure: IllegalArgumentException) {
                baselineIneligible(failure.message ?: "BASELINE_CANDIDATE_INVALID")
            }
        }
        else -> malformed("mode must be manual or statistical")
    }
}

private fun JsonObject.baselineString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed("$name must be a string")

private fun JsonObject.baselineReference(): JsonObject {
    if (keys != setOf("run_id", "analysis_id") ||
        !RUN_ID.matches(baselineString("run_id")) ||
        !Regex("[0-9a-f]{64}").matches(baselineString("analysis_id"))
    ) {
        malformed("Baseline reference is invalid")
    }
    return this
}

private suspend fun RunBundleStore.baselineDocuments(reference: JsonObject): Pair<JsonObject, JsonObject> =
    baselineOperation {
        readAnalysisDocuments(reference.baselineString("run_id"), reference.baselineString("analysis_id"))
            ?: notFound("Referenced analysis was not found")
    }

private suspend fun <T> baselineOperation(action: () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            action()
        } catch (_: NoSuchElementException) {
            notFound("Referenced run or analysis was not found")
        } catch (failure: IllegalStateException) {
            if (failure.message?.startsWith("CORRUPT_BASELINE") == true || failure.message?.startsWith("CORRUPT_RUN_BUNDLE") == true) {
                throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_BASELINE", "Baseline or referenced analysis is corrupt")
            }
            throw failure
        }
    }

private fun baselineIneligible(code: String): Nothing =
    throw ApiFailure(HttpStatusCode.UnprocessableEntity, code, "Statistical baseline is unavailable: $code")

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
    sourceProfiles: List<SourceProfile>,
): AnalysisRequest {
    val contentLength =
        call.request.contentLength()
            ?: throw ApiFailure(HttpStatusCode.LengthRequired, "LENGTH_REQUIRED", "Job request requires Content-Length")
    if (contentLength > MAX_JOB_REQUEST_BYTES) tooLarge("Job request exceeds its resource limit")
    var runId: String? = null
    var policy: PolicyValidation.Valid? = null
    var resources: ResourceValidation.Valid? = null
    var diagnostics: DiagnosticValidation.Valid? = null
    var sourceRequest: SourceRequest? = null
    var sourceContext: ByteArray? = null
    var policySeen = false
    var resourcesSeen = false
    var diagnosticsSeen = false
    var parts = 0
    var invalidParts = false
    try {
        call.receiveMultipart(formFieldLimit = (MAX_RESOURCE_BYTES + 1).toLong()).forEachPart { part ->
            try {
                if (++parts > 5) malformed("Job multipart body has too many parts")
                when {
                    part is PartData.FileItem && part.name == "source_context" && sourceContext == null && !invalidParts -> {
                        sourceContext = withContext(Dispatchers.IO) { part.provider().toInputStream().readNBytes(MAX_RESOURCE_BYTES + 1) }
                        if (checkNotNull(sourceContext).size > MAX_RESOURCE_BYTES) tooLarge("Source context exceeds its resource limit")
                    }
                    part is PartData.FileItem && part.name == "source_request" && sourceRequest == null && !invalidParts -> {
                        sourceRequest =
                            try {
                                withContext(Dispatchers.IO) { readSourceRequest(part.provider().toInputStream()) }
                            } catch (_: IllegalArgumentException) {
                                malformed("Source request is invalid")
                            }
                    }
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

                    part is PartData.FileItem && part.name == "resource_snapshot" && !resourcesSeen && !invalidParts -> {
                        resourcesSeen = true
                        resources =
                            when (
                                val validation =
                                    withContext(
                                        Dispatchers.IO,
                                    ) { validateResourceSnapshot(part.provider().toInputStream()) }
                            ) {
                                is ResourceValidation.Valid -> validation
                                is ResourceValidation.Invalid -> {
                                    if (validation.errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }) {
                                        tooLarge("Resource snapshot exceeds its resource limit")
                                    }
                                    throw InvalidResources(validation.errors)
                                }
                            }
                    }

                    part is PartData.FileItem && part.name == "correlation_plan" && !diagnosticsSeen && !invalidParts -> {
                        diagnosticsSeen = true
                        diagnostics =
                            when (
                                val validation =
                                    withContext(Dispatchers.IO) {
                                        validateDiagnosticPlan(part.provider().toInputStream(), MAX_DIAGNOSTIC_BYTES)
                                    }
                            ) {
                                is DiagnosticValidation.Valid -> validation
                                is DiagnosticValidation.Invalid -> {
                                    if (validation.errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }) {
                                        tooLarge("Diagnostic plan exceeds its resource limit")
                                    }
                                    throw InvalidDiagnostics(validation.errors)
                                }
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
    } catch (failure: InvalidResources) {
        throw failure
    } catch (failure: InvalidDiagnostics) {
        throw failure
    } catch (_: Exception) {
        malformed("Multipart body is malformed")
    }
    if (invalidParts || runId == null || !RUN_ID.matches(runId)) malformed("Job multipart body is invalid")
    sourceRequest?.let { selection ->
        if (resourcesSeen ||
            diagnosticsSeen ||
            sourceContext != null
        ) {
            malformed("Online acquisition cannot be combined with manual source inputs")
        }
        if (sourceProfiles.none { it.id == selection.profileId }) malformed("Source profile is not configured")
    }
    val input =
        try {
            withContext(Dispatchers.IO) { store.requireInput(checkNotNull(runId)) }
        } catch (_: NoSuchElementException) {
            notFound("Run was not found")
        } catch (_: IllegalArgumentException) {
            notFound("Run was not found")
        }
    if (resources?.snapshot?.loadInputSha256?.let { it != input.sha256 } == true) {
        throw InvalidResources(
            listOf(PolicyValidationError("RESOURCE_INPUT_MISMATCH", "/load_input_sha256", "Snapshot belongs to another load input")),
        )
    }
    diagnostics?.let { plan ->
        val snapshot =
            resources ?: throw InvalidDiagnostics(
                listOf(
                    PolicyValidationError(
                        "DIAGNOSTIC_RESOURCE_REQUIRED",
                        "/resource_snapshot_sha256",
                        "Diagnostic plan requires a resource snapshot",
                    ),
                ),
            )
        val errors = validateDiagnosticBinding(plan, snapshot)
        if (errors.isNotEmpty()) throw InvalidDiagnostics(errors)
    }
    val acquisition =
        sourceContext?.let { bytes ->
            try {
                withContext(Dispatchers.IO) { readOpenSearchContext(bytes.inputStream(), input.sha256, resources) }
            } catch (_: IllegalArgumentException) {
                malformed("Source context is invalid or belongs to another load input")
            }
        }
    return AnalysisRequest(
        input,
        policy,
        resources = resources,
        diagnostics = diagnostics,
        sourceRequest = sourceRequest,
        sourceAcquisition = acquisition,
    )
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
            buckets += bucket.withP95()
        }
    }
    return BucketPage(buckets, next)
}

private fun JsonObject.withP95(): JsonObject {
    val encoded = getValue("hdr_v2_base64").jsonPrimitive.content
    val histogram =
        PackedHistogram.decodeFromCompressedByteBuffer(
            ByteBuffer.wrap(Base64.getDecoder().decode(encoded)),
            MAX_BUCKET_LATENCY_MILLIS,
        )
    return JsonObject(this + ("p95_latency_ms" to JsonPrimitive(histogram.getValueAtPercentile(95.0))))
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
        } catch (_: NoSuchElementException) {
            notFound("Analysis was not found")
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

private fun ApplicationCall.windowComparisonQuery(): WindowComparisonRequest? {
    val baseline = singleQuery("baseline_window")
    val current = singleQuery("current_window")
    if (baseline == null && current == null) {
        if (singleQuery("min_change_percent") != null || singleQuery("min_error_rate_delta") != null) {
            malformed("Materiality thresholds require both windows")
        }
        return null
    }
    if (listOf(baseline, current).any { it == null || it.isBlank() || it.encodeToByteArray().size > 128 || it.any(Char::isISOControl) }) {
        malformed("Both window IDs must contain 1–128 UTF-8 bytes without control characters")
    }
    return WindowComparisonRequest(
        checkNotNull(baseline),
        checkNotNull(current),
        boundedDecimalQuery("min_change_percent", "5", "1000"),
        boundedDecimalQuery("min_error_rate_delta", "0.001", "1"),
    )
}

private fun ApplicationCall.boundedDecimalQuery(
    name: String,
    default: String,
    maximum: String,
): BigDecimal {
    val raw = singleQuery(name) ?: default
    if (raw.length > 64 || !Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?").matches(raw)) malformed("$name is invalid")
    val value = raw.toBigDecimalOrNull() ?: malformed("$name is invalid")
    if (value.precision() > 32 || value.scale() !in -12..12 || value.signum() <= 0 || value > BigDecimal(maximum)) {
        malformed("$name is invalid")
    }
    return value
}

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
    details: List<PolicyValidationError> = emptyList(),
) = respondJson(
    buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("details", JsonArray(details.map { it.toJson() }))
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

private class InvalidResources(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

private class InvalidDiagnostics(
    val errors: List<PolicyValidationError>,
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
private const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024
private const val MAX_DIAGNOSTIC_BYTES = 1024 * 1024
private const val MAX_JOB_REQUEST_BYTES = 2 * MAX_RESOURCE_BYTES + MAX_POLICY_BYTES + MAX_DIAGNOSTIC_BYTES + MAX_MULTIPART_OVERHEAD_BYTES
private const val MAX_RUN_ID_BYTES = 128
private const val MAX_BASELINE_REQUEST_BYTES = 16_384
private const val DEFAULT_RUN_LIMIT = 100
private const val MAX_RUN_LIMIT = 100
private const val DEFAULT_ANALYSIS_LIMIT = 25
private const val MAX_ANALYSIS_LIMIT = 100
private const val DEFAULT_BUCKET_LIMIT = 500
private const val MAX_BUCKET_LIMIT = 500
private const val MAX_BUCKET_LATENCY_MILLIS = 86_400_000L
private const val RESULT_FILE = "analysis-result.json"
private const val NORMALIZED_FILE = "normalized-1s.ndjson"
private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"
private val ROLLUPS = setOf(1, 10, 30, 60)
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

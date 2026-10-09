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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.CapacityPlanValidation
import io.ltverdict.core.DiagnosticValidation
import io.ltverdict.core.MAX_CAPACITY_PLAN_BYTES
import io.ltverdict.core.MAX_POD_VIEW_BYTES
import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES
import io.ltverdict.core.MAX_TREND_PLAN_BYTES
import io.ltverdict.core.PodViewValidation
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.PolicyValidationError
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.TrendPlanValidation
import io.ltverdict.core.validateCapacityBinding
import io.ltverdict.core.validateCapacityPlan
import io.ltverdict.core.validateDiagnosticBinding
import io.ltverdict.core.validateDiagnosticPlan
import io.ltverdict.core.validatePlatformBinding
import io.ltverdict.core.validatePodView
import io.ltverdict.core.validatePodViewBinding
import io.ltverdict.core.validatePolicy
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.core.validateTrendBinding
import io.ltverdict.core.validateTrendPlan
import io.ltverdict.jobs.JobStatus
import io.ltverdict.jobs.SubmitResult
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.WindowedSourceRequest
import io.ltverdict.sources.readOpenSearchContexts
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.sources.readWindowedSourceRequest
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

internal fun Route.jobRoutes(context: LocalApiContext) {
    post("/api/inputs") {
        call.requireMultipart()
        val contentLength = call.request.contentLength()
        if (contentLength != null && contentLength > context.uploadLimitBytes + MAX_MULTIPART_OVERHEAD_BYTES) {
            tooLarge("Input exceeds 4 GiB")
        }
        val accepted = receiveInput(call, context.store, context.uploadLimitBytes)
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

    get("/api/jobs") {
        call.requireOnlyQueries("state")
        if (call.singleQuery("state") != "active") malformed("Query parameters are invalid")
        call.respondJson(
            buildJsonObject {
                put("jobs", buildJsonArray { context.jobs.activeStatuses().forEach { add(it.toJson()) } })
            },
        )
    }

    get("/api/jobs/{jobId}") {
        val status = context.jobs.status(call.parameters["jobId"].orEmpty()) ?: notFound("Job was not found")
        call.respondJson(status.toJson())
    }

    delete("/api/jobs/{jobId}") {
        val status = context.jobs.cancel(call.parameters["jobId"].orEmpty()) ?: notFound("Job was not found")
        call.respondJson(status.toJson())
    }
}

private suspend fun receiveInput(
    call: ApplicationCall,
    store: RunBundleStore,
    maxBytes: Long,
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
                        try {
                            withContext(Dispatchers.IO) {
                                part.provider().toInputStream().use { input ->
                                    Files.newOutputStream(temporary).use { output -> copyBoundedUpload(input, output, maxBytes) }
                                }
                            }
                        } catch (failure: IllegalArgumentException) {
                            mapInputFailure(failure)
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
            malformed("Multipart body is malformed")
        }
        if (invalidParts || filename == null) malformed("Multipart body must contain exactly one file part")
        return try {
            withContext(Dispatchers.IO) {
                Files.newInputStream(temporary).use { input ->
                    store.acceptInput(input, checkNotNull(filename), maxBytes)
                }
            }
        } catch (failure: IllegalArgumentException) {
            mapInputFailure(failure)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(temporary) }
    }
}

/** Copies at most [maxBytes]; reads one byte past the limit, then throws without writing it or reading further. */
internal fun copyBoundedUpload(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val remaining = maxBytes - total
        val count = input.read(buffer, 0, if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt())
        if (count == -1) return
        if (count > remaining) throw IllegalArgumentException("RESOURCE_LIMIT_EXCEEDED")
        output.write(buffer, 0, count)
        total += count
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
    var capacity: CapacityPlanValidation.Valid? = null
    var trend: TrendPlanValidation.Valid? = null
    var podView: PodViewValidation.Valid? = null
    var sourceRequest: WindowedSourceRequest? = null
    val sourceContexts = mutableListOf<ByteArray>()
    val postgresFiles = mutableMapOf<String, ByteArray>()
    var policySeen = false
    var resourcesSeen = false
    var diagnosticsSeen = false
    var capacitySeen = false
    var trendSeen = false
    var podViewSeen = false
    var parts = 0
    var invalidParts = false
    try {
        call.receiveMultipart(formFieldLimit = (MAX_RESOURCE_SNAPSHOT_BYTES + 1).toLong()).forEachPart { part ->
            try {
                if (++parts > MAX_JOB_PARTS) malformed("Job multipart body has too many parts")
                when {
                    part is PartData.FileItem && part.name in POSTGRES_PART_LIMITS && part.name !in postgresFiles && !invalidParts -> {
                        val name = checkNotNull(part.name)
                        val limit = POSTGRES_PART_LIMITS.getValue(name)
                        val bytes = withContext(Dispatchers.IO) { part.provider().toInputStream().readNBytes(limit + 1) }
                        if (bytes.size > limit) tooLarge("PostgreSQL artifact exceeds its resource limit")
                        postgresFiles[name] = bytes
                    }
                    part is PartData.FileItem && part.name == "source_context" && !invalidParts -> {
                        if (sourceContexts.size == 16) malformed("Too many source contexts")
                        val bytes = withContext(Dispatchers.IO) { part.provider().toInputStream().readNBytes(MAX_RESOURCE_BYTES + 1) }
                        if (bytes.size > MAX_RESOURCE_BYTES || sourceContexts.sumOf { it.size.toLong() } + bytes.size > MAX_CONTEXT_BYTES) {
                            tooLarge("Source context exceeds its resource limit")
                        }
                        sourceContexts += bytes
                    }
                    part is PartData.FileItem && part.name == "source_request" && sourceRequest == null && !invalidParts -> {
                        sourceRequest =
                            try {
                                withContext(Dispatchers.IO) { readWindowedSourceRequest(part.provider().toInputStream()) }
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

                    part is PartData.FileItem && part.name == "capacity_plan" && !capacitySeen && !invalidParts -> {
                        capacitySeen = true
                        capacity =
                            when (
                                val validation =
                                    withContext(Dispatchers.IO) {
                                        validateCapacityPlan(part.provider().toInputStream(), MAX_CAPACITY_PLAN_BYTES)
                                    }
                            ) {
                                is CapacityPlanValidation.Valid -> validation
                                is CapacityPlanValidation.Invalid -> {
                                    if (validation.errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }) {
                                        tooLarge("Capacity plan exceeds its resource limit")
                                    }
                                    throw InvalidCapacity(validation.errors)
                                }
                            }
                    }

                    part is PartData.FileItem && part.name == "trend_plan" && !trendSeen && !invalidParts -> {
                        trendSeen = true
                        trend =
                            when (
                                val validation =
                                    withContext(Dispatchers.IO) {
                                        validateTrendPlan(part.provider().toInputStream(), MAX_TREND_PLAN_BYTES)
                                    }
                            ) {
                                is TrendPlanValidation.Valid -> validation
                                is TrendPlanValidation.Invalid -> {
                                    if (validation.errors.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }) {
                                        tooLarge("Trend plan exceeds its resource limit")
                                    }
                                    throw InvalidTrend(validation.errors)
                                }
                            }
                    }

                    part is PartData.FileItem && part.name == "pod_view" && !podViewSeen && !invalidParts -> {
                        podViewSeen = true
                        podView =
                            when (
                                val validation =
                                    withContext(Dispatchers.IO) { validatePodView(part.provider().toInputStream(), MAX_POD_VIEW_BYTES) }
                            ) {
                                is PodViewValidation.Valid -> validation
                                is PodViewValidation.Invalid -> {
                                    if (validation.errors.any { it.code == "POD_VIEW_LIMIT_EXCEEDED" }) {
                                        tooLarge("Pod view exceeds its resource limit")
                                    }
                                    throw InvalidPodView(validation.errors)
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
    } catch (failure: InvalidCapacity) {
        throw failure
    } catch (failure: InvalidTrend) {
        throw failure
    } catch (failure: InvalidPodView) {
        throw failure
    } catch (_: Exception) {
        malformed("Multipart body is malformed")
    }
    if (invalidParts || runId == null || !RUN_ID.matches(runId)) malformed("Job multipart body is invalid")
    sourceRequest?.let { selection ->
        if (resourcesSeen ||
            diagnosticsSeen ||
            capacitySeen ||
            trendSeen ||
            podViewSeen ||
            sourceContexts.isNotEmpty()
        ) {
            malformed("Online acquisition cannot be combined with manual source inputs")
        }
        if (selection.profileIds.any { id -> sourceProfiles.none { it.id == id } }) {
            malformed("Source profile is not configured")
        }
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
    capacity?.let { plan ->
        val snapshot =
            resources ?: throw InvalidCapacity(
                listOf(
                    PolicyValidationError(
                        "CAPACITY_RESOURCE_REQUIRED",
                        "/resource_snapshot_sha256",
                        "Capacity plan requires a resource snapshot",
                    ),
                ),
            )
        val errors = validateCapacityBinding(plan, input.sha256, snapshot)
        if (errors.isNotEmpty()) throw InvalidCapacity(errors)
    }
    trend?.let { plan ->
        val snapshot =
            resources ?: throw InvalidTrend(
                listOf(
                    PolicyValidationError(
                        "TREND_RESOURCE_REQUIRED",
                        "/resource_snapshot_sha256",
                        "Trend plan requires a resource snapshot",
                    ),
                ),
            )
        val errors = validateTrendBinding(plan, snapshot)
        if (errors.isNotEmpty()) throw InvalidTrend(errors)
    }
    podView?.let { view ->
        val snapshot =
            resources ?: throw InvalidPodView(
                listOf(
                    PolicyValidationError(
                        "POD_VIEW_RESOURCE_REQUIRED",
                        "/resource_snapshot_sha256",
                        "Pod view requires a resource snapshot",
                    ),
                ),
            )
        val errors = validatePodViewBinding(view, input.sha256, snapshot)
        if (errors.isNotEmpty()) throw InvalidPodView(errors)
    }
    policy?.let { valid ->
        resources?.let { snapshot ->
            val errors = validatePlatformBinding(valid.policy, snapshot.snapshot)
            if (errors.isNotEmpty()) throw InvalidPolicy(PolicyValidation.Invalid(errors))
        }
    }
    val acquisition =
        sourceContexts.takeIf { it.isNotEmpty() }?.let { contexts ->
            try {
                withContext(Dispatchers.IO) { readOpenSearchContexts(contexts, input.sha256, resources) }
            } catch (_: IllegalArgumentException) {
                malformed("Source context is invalid or belongs to another load input")
            }
        }
    val postgres =
        if (postgresFiles.isEmpty()) {
            null
        } else {
            try {
                withContext(Dispatchers.IO) {
                    readPostgresAnalysisInput(
                        postgresFiles["postgres_pre"]?.inputStream(),
                        postgresFiles["postgres_post"]?.inputStream(),
                        postgresFiles["pg_profile_html"]?.inputStream(),
                    )
                }
            } catch (_: IllegalArgumentException) {
                malformed("PostgreSQL artifacts are invalid")
            }
        }
    return AnalysisRequest(
        input,
        policy,
        resources = resources,
        diagnostics = diagnostics,
        capacity = capacity,
        trend = trend,
        podView = podView,
        sourceRequest = sourceRequest,
        sourceAcquisition = acquisition,
        postgres = postgres,
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

internal fun AcceptedInput.toJson(): JsonObject =
    buildJsonObject {
        put("run_id", runId)
        put("source_type", sourceType.wireName)
        put("sha256", sha256)
        put("size_bytes", sizeBytes)
        put("original_filename", originalFilename)
        put("accepted_at", acceptedAt?.let(::JsonPrimitive) ?: JsonNull)
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

private fun mapInputFailure(failure: IllegalArgumentException): Nothing =
    when (failure.message) {
        "RESOURCE_LIMIT_EXCEEDED" -> tooLarge("Input exceeds 4 GiB")
        "UNSUPPORTED_INPUT", "EMPTY_INPUT" -> unsupportedInput("Input format is unsupported")
        else -> malformed("Upload metadata is invalid")
    }

private const val MAX_MULTIPART_OVERHEAD_BYTES = 65_536L

private const val MAX_DIAGNOSTIC_BYTES = 1024 * 1024

private const val MAX_CONTEXT_BYTES = 32L * 1024 * 1024

private const val MAX_JOB_REQUEST_BYTES =
    MAX_RESOURCE_SNAPSHOT_BYTES + 2 * MAX_RESOURCE_BYTES + MAX_CONTEXT_BYTES + 4 * 1024 * 1024 +
        MAX_POLICY_BYTES + MAX_DIAGNOSTIC_BYTES + MAX_CAPACITY_PLAN_BYTES + MAX_TREND_PLAN_BYTES + MAX_POD_VIEW_BYTES +
        MAX_MULTIPART_OVERHEAD_BYTES

// 24 before pod_view; one more part for pod_view (ADR 0020, section 4).
private const val MAX_JOB_PARTS = 25

private val POSTGRES_PART_LIMITS =
    mapOf(
        "postgres_pre" to MAX_RESOURCE_BYTES,
        "postgres_post" to MAX_RESOURCE_BYTES,
        "pg_profile_html" to 4 * 1024 * 1024,
    )

private const val MAX_RUN_ID_BYTES = 128

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
import io.ltverdict.ai.AdviceJobStatus
import io.ltverdict.ai.AdviceSubmitResult
import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalyticsExportFormat
import io.ltverdict.core.CapacityPlanValidation
import io.ltverdict.core.DiagnosticValidation
import io.ltverdict.core.MAX_CAPACITY_PLAN_BYTES
import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES
import io.ltverdict.core.MAX_TREND_PLAN_BYTES
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.PolicyValidationError
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.SavedAnalysisForComparison
import io.ltverdict.core.TrendPlanValidation
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionConfirmation
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.buildRunDynamics
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.compareAnalyses
import io.ltverdict.core.compareTransactions
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.metricPackAnalysis
import io.ltverdict.core.openSearchOverlay
import io.ltverdict.core.renderRunDynamicsExport
import io.ltverdict.core.statisticalBaselineSelection
import io.ltverdict.core.validateCapacityBinding
import io.ltverdict.core.validateCapacityPlan
import io.ltverdict.core.validateDiagnosticBinding
import io.ltverdict.core.validateDiagnosticPlan
import io.ltverdict.core.validatePolicy
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.core.validateTrendBinding
import io.ltverdict.core.validateTrendPlan
import io.ltverdict.integrations.grafana.GrafanaPanelRequest
import io.ltverdict.integrations.grafana.grafanaPanelLink
import io.ltverdict.integrations.grafana.renderGrafanaPanel
import io.ltverdict.integrations.jenkins.ArtifactExpectation
import io.ltverdict.integrations.jenkins.JenkinsProfileSummary
import io.ltverdict.integrations.jenkins.JenkinsRunState
import io.ltverdict.integrations.jenkins.JenkinsTriggerRequest
import io.ltverdict.integrations.jenkins.JenkinsWorkflow
import io.ltverdict.integrations.report.renderConfluenceReport
import io.ltverdict.integrations.report.renderSavedLoadChart
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.jobs.JobStatus
import io.ltverdict.jobs.SubmitResult
import io.ltverdict.report.renderAsciiDocReport
import io.ltverdict.report.renderHtmlReport
import io.ltverdict.sources.PostgresProfile
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceTransport
import io.ltverdict.sources.WindowedSourceRequest
import io.ltverdict.sources.capturePostgresPhase
import io.ltverdict.sources.readOpenSearchContexts
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.sources.readWindowedSourceRequest
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.Semaphore

internal data class LocalApiContext(
    val store: RunBundleStore,
    val jobs: AnalysisJobs,
    val sourceProfiles: List<SourceProfile> = emptyList(),
    val postgresProfiles: List<PostgresProfile> = emptyList(),
    val adviceService: AdvisoryAiService? = null,
    val adviceJobs: AdvisoryAiJobs? = null,
    val jenkinsProfiles: List<JenkinsProfileSummary> = emptyList(),
    val jenkinsWorkflows: Map<String, JenkinsWorkflow> = emptyMap(),
    val jenkinsArtifactRoot: Path? = null,
    val sourceHttp: SourceHttp? = null,
)

internal fun Application.installLocalApi(context: LocalApiContext) {
    val sessionToken = randomToken()
    val csrfToken = randomToken()
    val postgresCapturePermit = Semaphore(1)
    val jenkinsPermit = Semaphore(1)

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
        } catch (failure: InvalidCapacity) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "INVALID_CAPACITY_PLAN", "Capacity plan is invalid", failure.errors)
            finish()
        } catch (failure: InvalidTrend) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "INVALID_TREND_PLAN", "Trend plan is invalid", failure.errors)
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

        get("/api/grafana") {
            call.requireOnlyQueries()
            call.respondJson(
                buildJsonObject {
                    put(
                        "profiles",
                        buildJsonArray {
                            context.sourceProfiles.filter { it.transport == SourceTransport.GRAFANA_PROXY }.forEach {
                                add(
                                    buildJsonObject {
                                        put("id", it.id)
                                        put("base_url", it.baseUrl.toString())
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        get("/api/runs/{runId}/analyses/{analysisId}/grafana-link") {
            val (profile, panel) = grafanaRequest(call, context)
            call.respondJson(buildJsonObject { put("source_link", grafanaPanelLink(profile, panel).toString()) })
        }

        post("/api/runs/{runId}/analyses/{analysisId}/grafana-render") {
            val (profile, panel) = grafanaRequest(call, context)
            call.requireJson()
            if (receiveBaselineRequest(call).isNotEmpty()) malformed("Render body must be empty")
            val http =
                context.sourceHttp
                    ?: throw ApiFailure(HttpStatusCode.ServiceUnavailable, "GRAFANA_UNAVAILABLE", "Grafana transport is not configured")
            val rendered = withContext(Dispatchers.IO) { renderGrafanaPanel(profile, panel) { http.getGrafanaPanel(profile, panel) } }
            call.respondJson(
                buildJsonObject {
                    put("source_link", rendered.sourceLink.toString())
                    put("png_base64", rendered.png?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) } ?: JsonNull)
                    put("failure_code", rendered.failureCode?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }

        get("/api/jenkins") {
            call.requireOnlyQueries()
            call.respondJson(
                buildJsonObject {
                    put(
                        "profiles",
                        buildJsonArray {
                            context.jenkinsProfiles.forEach { profile ->
                                add(
                                    buildJsonObject {
                                        put("id", profile.id)
                                        put("controller", profile.controller.toString())
                                        put("job_path", profile.jobPath)
                                        put("parameter_names", JsonArray(profile.parameterNames.map(::JsonPrimitive)))
                                        put("artifact_paths", JsonArray(profile.artifactPaths.map(::JsonPrimitive)))
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }

        get("/api/jenkins/{profileId}/attempts") {
            call.requireOnlyQueries()
            val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
            val states = withContext(Dispatchers.IO) { workflow.listStates() }
            call.respondJson(buildJsonObject { put("attempts", JsonArray(states.map { it.toJson() })) })
        }

        post("/api/jenkins/{profileId}/trigger") {
            call.requireOnlyQueries()
            call.requireJson()
            val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
            val body = receiveBaselineRequest(call)
            if (body.keys != setOf("parameters")) malformed("Only parameters are allowed")
            val parameters = body["parameters"] as? JsonObject ?: malformed("parameters must be an object")
            val values =
                parameters.mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed("Parameter values must be strings")
                }
            if (!jenkinsPermit.tryAcquire()) throw ApiFailure(HttpStatusCode.Conflict, "JENKINS_BUSY", "A Jenkins operation is active")
            try {
                val state = withContext(Dispatchers.IO) { workflow.trigger(JenkinsTriggerRequest(values)) }
                call.respondJson(state.toJson(), HttpStatusCode.Accepted)
            } catch (_: IllegalArgumentException) {
                malformed("Jenkins request is invalid")
            } finally {
                jenkinsPermit.release()
            }
        }

        post("/api/jenkins/{profileId}/attempts/{attemptId}/{operation}") {
            call.requireOnlyQueries()
            call.requireJson()
            val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
            val attemptId = call.parameters["attemptId"].orEmpty()
            val operation = call.parameters["operation"].orEmpty()
            val body = receiveBaselineRequest(call)
            if (operation !in setOf("advance", "reconcile", "collect")) notFound("Jenkins operation was not found")
            if (operation != "collect" && body.isNotEmpty()) malformed("Operation body must be empty")
            if (!jenkinsPermit.tryAcquire()) throw ApiFailure(HttpStatusCode.Conflict, "JENKINS_BUSY", "A Jenkins operation is active")
            try {
                val response =
                    withContext(Dispatchers.IO) {
                        val state =
                            when (operation) {
                                "advance" -> workflow.advance(attemptId)
                                "reconcile" -> workflow.reconcile(attemptId)
                                else -> {
                                    if (body.keys != setOf("artifact_path")) malformed("artifact_path is required")
                                    val artifact =
                                        (body["artifact_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                            ?: malformed("artifact_path must be a string")
                                    workflow.collectArtifact(
                                        attemptId,
                                        ArtifactExpectation(artifact),
                                        context.jenkinsArtifactRoot ?: error("JENKINS_STORAGE_UNAVAILABLE"),
                                    )
                                }
                            }
                        buildJsonObject {
                            put("attempt", state.toJson())
                            if (operation == "collect" && state.artifact != null) {
                                val artifact = checkNotNull(state.artifact)
                                val input =
                                    Files.newInputStream(artifact.path).use {
                                        context.store.acceptInput(it, Path.of(artifact.relativePath).fileName.toString())
                                    }
                                put("run", input.toJson())
                            } else {
                                put("run", JsonNull)
                            }
                        }
                    }
                call.respondJson(response)
            } catch (_: NoSuchElementException) {
                notFound("Jenkins attempt was not found")
            } catch (_: IllegalArgumentException) {
                malformed("Jenkins operation or artifact is invalid")
            } finally {
                jenkinsPermit.release()
            }
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

        get("/api/runs/{runId}/analyses/{analysisId}/baseline-conditions") {
            call.requireOnlyQueries("baseline_window", "current_window")
            val windows = call.windowComparisonQuery()
            val current =
                buildJsonObject {
                    put("run_id", call.parameters["runId"].orEmpty())
                    put("analysis_id", call.parameters["analysisId"].orEmpty())
                }.baselineReference()
            val selected = baselineOperation { context.store.readBaseline() } ?: notFound("No baseline is selected")
            val baselineReference = selected.getValue("reference").jsonObject
            context.store.baselineDocuments(baselineReference)
            context.store.baselineDocuments(current)
            val conditions = baselineOperation { context.store.readBaselineCondition(baselineReference, current, windows) }
            call.respondJson(buildJsonObject { put("conditions", conditions ?: JsonNull) })
        }

        post("/api/runs/{runId}/analyses/{analysisId}/baseline-conditions") {
            call.requireOnlyQueries("baseline_window", "current_window")
            call.requireJson()
            val windows = call.windowComparisonQuery()
            val request = receiveBaselineRequest(call)
            if (request.keys != setOf("decision")) malformed("Baseline condition fields are invalid")
            val decision = request.baselineString("decision")
            if (decision !in BASELINE_CONDITION_DECISIONS) malformed("decision is invalid")
            val current =
                buildJsonObject {
                    put("run_id", call.parameters["runId"].orEmpty())
                    put("analysis_id", call.parameters["analysisId"].orEmpty())
                }.baselineReference()
            val selected = baselineOperation { context.store.readBaseline() } ?: notFound("No baseline is selected")
            val baselineReference = selected.getValue("reference").jsonObject
            context.store.baselineDocuments(baselineReference)
            context.store.baselineDocuments(current)
            val condition = baselineConditionRecord(baselineReference, current, windows, decision, Instant.now())
            val stored = baselineOperation { context.store.replaceBaselineCondition(condition) }
            call.respondJson(buildJsonObject { put("conditions", stored) })
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
            val baselineReference = selected.getValue("reference").jsonObject
            val (baselineResult, baselineIdentity) = context.store.baselineDocuments(baselineReference)
            val (currentResult, currentIdentity) = context.store.baselineDocuments(current)
            val conditions = baselineOperation { context.store.readBaselineCondition(baselineReference, current, windows) }
            val comparison =
                compareAnalyses(
                    selected,
                    current,
                    baselineResult,
                    baselineIdentity,
                    currentResult,
                    currentIdentity,
                    windows,
                    conditions?.let(::baselineConditionConfirmation),
                )
            call.respondJson(
                buildJsonObject {
                    comparison.forEach { (name, value) -> put(name, value) }
                    put("conditions", conditions ?: JsonNull)
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
                            context.postgresProfiles.forEach { profile ->
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

        listOf("pre", "post").forEach { phase ->
            post("/api/sources/postgresql/$phase") {
                call.requireOnlyQueries()
                call.requireMultipart()
                val (profileId, pre) = receivePostgresCapture(call, phase == "post")
                val profile = context.postgresProfiles.singleOrNull { it.id == profileId } ?: malformed("Source profile is not configured")
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

        mapOf(
            "postgres-pre" to "postgres-pre.json",
            "postgres-post" to "postgres-post.json",
            "postgres-context" to "postgres-context.json",
            "pg-profile" to "pg-profile.html",
        ).forEach { (route, name) ->
            get("/api/runs/{runId}/analyses/{analysisId}/$route") {
                call.requireOnlyQueries()
                val stored = context.store.requireAnalysis(call)
                if (stored.artifacts.none { it.path == name }) notFound("PostgreSQL artifact was not found")
                val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(name)) }
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                call.respondBytes(
                    bytes,
                    if (name.endsWith(".html")) ContentType.Application.OctetStream else ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }
        }

        get("/api/runs/{runId}/analyses/{analysisId}/resource-snapshot") {
            call.requireOnlyQueries()
            val stored = context.store.requireAnalysis(call)
            if (stored.artifacts.none { it.path == "resource-snapshot.json" }) notFound("Resource snapshot was not found")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve("resource-snapshot.json")) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"resource-snapshot.json\"")
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
        }

        mapOf(
            "capacity-plan" to "capacity-plan.json",
            "capacity" to "capacity.json",
        ).forEach { (route, name) ->
            get("/api/runs/{runId}/analyses/{analysisId}/$route") {
                call.requireOnlyQueries()
                val stored = context.store.requireAnalysis(call)
                if (stored.artifacts.none { it.path == name }) notFound("Capacity artifact was not found")
                val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(name)) }
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }

        mapOf(
            "trend-plan" to "trend-plan.json",
            "trend" to "trend.json",
        ).forEach { (route, name) ->
            get("/api/runs/{runId}/analyses/{analysisId}/$route") {
                call.requireOnlyQueries()
                val stored = context.store.requireAnalysis(call)
                if (stored.artifacts.none { it.path == name }) notFound("Trend artifact was not found")
                val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(name)) }
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
                call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
            }
        }

        get("/api/runs/{runId}/analyses/{analysisId}/source-context") {
            call.requireOnlyQueries()
            val stored = context.store.requireAnalysis(call)
            if (stored.artifacts.none { it.path == "opensearch-errors.json" }) notFound("Source context was not found")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve("opensearch-errors.json")) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"opensearch-errors.json\"")
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
        }

        get("/api/runs/{runId}/analyses/{analysisId}/source-context/{index}") {
            call.requireOnlyQueries()
            val index = call.parameters["index"].orEmpty()
            if (!Regex("[1-9]|1[0-6]").matches(index)) notFound("Source context was not found")
            val name = "opensearch-errors-$index.json"
            val stored = context.store.requireAnalysis(call)
            if (stored.artifacts.none { it.path == name }) notFound("Source context was not found")
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(name)) }
            call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"$name\"")
            call.respondBytes(bytes, ContentType.Application.Json, HttpStatusCode.OK)
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

        get("/api/runs/{runId}/analyses/{analysisId}/analytics") {
            val query = call.request.queryParameters
            if (query.names().any { it !in setOf("limit", "transaction", "transaction_limit", "format", "exclude") } ||
                query.names().any { it != "exclude" && query.getAll(it)?.size != 1 }
            ) {
                malformed("Query parameters are invalid")
            }
            val excluded = query.getAll("exclude").orEmpty()
            if (excluded.size > 100 || excluded.any { it.length > 256 }) malformed("Excluded rows are invalid")
            val formatName = call.singleQuery("format") ?: "json"
            val format = AnalyticsExportFormat.fromWireName(formatName)
            if (formatName != "json" && format == null) malformed("Unsupported analytics format")
            val limit = call.intQuery("limit", 10, 1..100)
            val transactionLimit = call.intQuery("transaction_limit", 100, 1..200)
            val filter = call.singleQuery("transaction")
            if (filter != null &&
                (filter.encodeToByteArray().size > 256 || filter.any(Char::isISOControl))
            ) {
                malformed("Transaction filter is invalid")
            }
            context.store.requireAnalysis(call)
            val runId = call.parameters["runId"].orEmpty()
            val analysisId = call.parameters["analysisId"].orEmpty()
            val response =
                withContext(Dispatchers.IO) {
                    val current = context.store.readComparisonDocuments(runId, analysisId) ?: notFound("Analysis was not found")
                    val baseline = context.store.readBaseline()?.get("reference") as? JsonObject
                    val candidates = mutableListOf<SavedAnalysisForComparison>()
                    val history = context.store.readComparisonHistory()
                    for (entry in history.entries) {
                        val documents = entry.documents
                        val runDocument = documents.run ?: continue
                        candidates +=
                            SavedAnalysisForComparison(
                                buildJsonObject {
                                    put("run_id", entry.runId)
                                    put("analysis_id", entry.analysisId)
                                },
                                runDocument,
                                documents.result,
                                documents.identity,
                            )
                    }
                    val baselineDocuments =
                        baseline?.let {
                            context.store.readComparisonDocuments(
                                it.getValue("run_id").jsonPrimitive.content,
                                it.getValue("analysis_id").jsonPrimitive.content,
                            )
                        }
                    if (baseline != null && baselineDocuments?.run != null) {
                        candidates +=
                            SavedAnalysisForComparison(
                                baseline,
                                checkNotNull(baselineDocuments.run),
                                baselineDocuments.result,
                                baselineDocuments.identity,
                            )
                    }
                    val currentReference =
                        buildJsonObject {
                            put("run_id", runId)
                            put("analysis_id", analysisId)
                        }
                    buildJsonObject {
                        put("schema_version", "saved-analytics.v1")
                        put("history_scan_truncated", history.truncated)
                        put("history_scan_limit", 1000)
                        put("history_metadata_byte_limit", 16 * 1024 * 1024)
                        put("history_integrity", "SAVED_DOCUMENT_HASHES")
                        put(
                            "dynamics",
                            current.run?.let {
                                buildRunDynamics(
                                    SavedAnalysisForComparison(currentReference, it, current.result, current.identity),
                                    candidates,
                                    baseline,
                                    limit,
                                )
                            } ?: JsonNull,
                        )
                        put(
                            "transactions",
                            baselineDocuments?.let {
                                compareTransactions(it.result, it.identity, current.result, current.identity, filter, transactionLimit)
                            } ?: JsonNull,
                        )
                        put("overlay", current.run?.let { openSearchOverlay(it, current.result) } ?: JsonNull)
                        put("metric_packs", metricPackAnalysis(current.result))
                    }
                }
            val dynamics = response["dynamics"] as? JsonObject
            val rows = (dynamics?.get("rows") as? JsonArray).orEmpty()

            fun rowKey(row: JsonElement): String {
                val reference = row.jsonObject.getValue("reference").jsonObject
                return reference.getValue("run_id").jsonPrimitive.content + "/" + reference.getValue("analysis_id").jsonPrimitive.content
            }
            if (!rows.map(::rowKey).containsAll(excluded)) malformed("Excluded row is not in this result")
            val selectedDynamics =
                dynamics?.let {
                    JsonObject(
                        it +
                            mapOf(
                                "rows" to JsonArray(rows.filterNot { row -> rowKey(row) in excluded }),
                                "history_scan_truncated" to response.getValue("history_scan_truncated"),
                                "history_scan_limit" to response.getValue("history_scan_limit"),
                            ),
                    )
                }
            if (format == null) {
                call.respondJson(JsonObject(response + ("dynamics" to (selectedDynamics ?: JsonNull))))
            } else {
                if (selectedDynamics ==
                    null
                ) {
                    throw ApiFailure(HttpStatusCode.UnprocessableEntity, "DYNAMICS_UNAVAILABLE", "Run metadata is unavailable")
                }
                val extension =
                    when (format) {
                        AnalyticsExportFormat.HTML -> "html"
                        AnalyticsExportFormat.ASCIIDOC -> "adoc"
                        AnalyticsExportFormat.CONFLUENCE -> "xhtml"
                    }
                call.response.headers.append(HttpHeaders.ContentDisposition, "attachment; filename=\"run-dynamics.$extension\"")
                call.respondBytes(renderRunDynamicsExport(selectedDynamics, format), ContentType.Text.Plain.withCharset(Charsets.UTF_8))
            }
        }

        get("/api/runs/{runId}/analyses/{analysisId}/advice") {
            call.requireOnlyQueries()
            context.store.requireAnalysis(call)
            val advice =
                withContext(Dispatchers.IO) {
                    context.adviceService?.read(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())
                }
            call.respondJson(
                buildJsonObject {
                    put("advice", advice?.document ?: JsonNull)
                    put(
                        "job",
                        context.adviceJobs?.latest(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())?.toJson()
                            ?: JsonNull,
                    )
                },
            )
        }

        post("/api/runs/{runId}/analyses/{analysisId}/advice") {
            call.requireOnlyQueries()
            call.requireJson()
            val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(257) } }
            if (bytes.size > 256) tooLarge("Advice request exceeds 256 bytes")
            // A single exact consent field avoids coercion and ambiguous duplicate JSON keys.
            val consent = bytes.decodeToString(throwOnInvalidSequence = false)
            if (!Regex("""\s*\{\s*"confirm_external_transfer"\s*:\s*true\s*}\s*""").matches(consent)) {
                malformed("Explicit external transfer consent is required")
            }
            context.store.requireAnalysis(call)
            val jobs =
                context.adviceJobs ?: throw ApiFailure(HttpStatusCode.ServiceUnavailable, "AI_UNAVAILABLE", "AI runner is not configured")
            when (val submitted = jobs.submit(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())) {
                is AdviceSubmitResult.Accepted -> call.respondJson(submitted.status.toJson(), HttpStatusCode.Accepted)
                AdviceSubmitResult.Busy -> throw ApiFailure(HttpStatusCode.Conflict, "AI_BUSY", "An AI task is already running")
            }
        }

        get("/api/advice-jobs/{jobId}") {
            call.requireOnlyQueries()
            val status = context.adviceJobs?.status(call.parameters["jobId"].orEmpty()) ?: notFound("Advice task was not found")
            call.respondJson(status.toJson())
        }

        delete("/api/advice-jobs/{jobId}") {
            call.requireOnlyQueries()
            val status = context.adviceJobs?.cancel(call.parameters["jobId"].orEmpty()) ?: notFound("Advice task was not found")
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
            if (format !in
                setOf("json", "html", "asciidoc", "confluence", "svg")
            ) {
                malformed("format must be json, html, asciidoc, confluence or svg")
            }
            val stored = context.store.requireAnalysis(call)
            val bytes = withContext(Dispatchers.IO) { Files.readAllBytes(stored.path.resolve(RESULT_FILE)) }
            val analysisId = stored.path.fileName.toString()
            val report =
                when (format) {
                    "json" -> bytes
                    "svg" -> withContext(Dispatchers.IO) { renderSavedLoadChart(stored.path.resolve("rollup-60s.ndjson")) }
                    "html" -> renderHtmlReport(bytes, analysisId)
                    "confluence" -> renderConfluenceReport(bytes, analysisId)
                    else -> renderAsciiDocReport(bytes, analysisId)
                }
            call.response.headers.append(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"lt-verdict-$analysisId.${if (format == "asciidoc") {
                    "adoc"
                } else if (format == "confluence") {
                    "xhtml"
                } else {
                    format
                }}\"",
            )
            val contentType =
                when (format) {
                    "json" -> ContentType.Application.Json
                    "svg" -> ContentType.parse("image/svg+xml")
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
    var capacity: CapacityPlanValidation.Valid? = null
    var trend: TrendPlanValidation.Valid? = null
    var sourceRequest: WindowedSourceRequest? = null
    val sourceContexts = mutableListOf<ByteArray>()
    val postgresFiles = mutableMapOf<String, ByteArray>()
    var policySeen = false
    var resourcesSeen = false
    var diagnosticsSeen = false
    var capacitySeen = false
    var trendSeen = false
    var parts = 0
    var invalidParts = false
    try {
        call.receiveMultipart(formFieldLimit = (MAX_RESOURCE_SNAPSHOT_BYTES + 1).toLong()).forEachPart { part ->
            try {
                if (++parts > 24) malformed("Job multipart body has too many parts")
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
    } catch (_: Exception) {
        malformed("Multipart body is malformed")
    }
    if (invalidParts || runId == null || !RUN_ID.matches(runId)) malformed("Job multipart body is invalid")
    sourceRequest?.let { selection ->
        if (resourcesSeen ||
            diagnosticsSeen ||
            capacitySeen ||
            trendSeen ||
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
    val p95 = minOf(histogram.getValueAtPercentile(95.0), getValue("max_latency_ms").jsonPrimitive.long)
    return JsonObject(this + ("p95_latency_ms" to JsonPrimitive(p95)))
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

private class InvalidCapacity(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

private class InvalidTrend(
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

// 16 MiB: source_context, PostgreSQL parts and capture. The resource snapshot has its own limit in the core.
private const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024
private const val MAX_DIAGNOSTIC_BYTES = 1024 * 1024
private const val MAX_CONTEXT_BYTES = 32L * 1024 * 1024
private const val MAX_JOB_REQUEST_BYTES =
    MAX_RESOURCE_SNAPSHOT_BYTES + 2 * MAX_RESOURCE_BYTES + MAX_CONTEXT_BYTES + 4 * 1024 * 1024 +
        MAX_POLICY_BYTES + MAX_DIAGNOSTIC_BYTES + MAX_CAPACITY_PLAN_BYTES + MAX_TREND_PLAN_BYTES +
        MAX_MULTIPART_OVERHEAD_BYTES
private val POSTGRES_PART_LIMITS =
    mapOf(
        "postgres_pre" to MAX_RESOURCE_BYTES,
        "postgres_post" to MAX_RESOURCE_BYTES,
        "pg_profile_html" to 4 * 1024 * 1024,
    )
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
private val BASELINE_CONDITION_DECISIONS = setOf("CONFIRMED", "NOT_CONFIRMED", "UNKNOWN")

private fun AdviceJobStatus.toJson(): JsonObject =
    buildJsonObject {
        put("job_id", jobId)
        put("run_id", runId)
        put("analysis_id", analysisId)
        put("state", state.name)
        put("reused", reused?.let(::JsonPrimitive) ?: JsonNull)
        put("failure", failure?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("unavailable_reason", unavailableReason?.name?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun JenkinsRunState.toJson(): JsonObject =
    buildJsonObject {
        put("attempt_id", attemptId)
        put("status", status.name)
        put("build_number", buildNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("failure_code", failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "artifact",
            artifact?.let { value ->
                buildJsonObject {
                    put("relative_path", value.relativePath)
                    put("size_bytes", value.sizeBytes)
                    put("sha256", value.sha256)
                }
            } ?: JsonNull,
        )
    }

private suspend fun grafanaRequest(
    call: ApplicationCall,
    context: LocalApiContext,
): Pair<SourceProfile, GrafanaPanelRequest> {
    call.requireOnlyQueries("profile", "dashboard", "panel", "theme")
    val profile =
        context.sourceProfiles.singleOrNull { it.id == call.singleQuery("profile") && it.transport == SourceTransport.GRAFANA_PROXY }
            ?: notFound("Grafana profile was not found")
    context.store.requireAnalysis(call)
    val documents =
        withContext(Dispatchers.IO) {
            context.store.readComparisonDocuments(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())
        }
    val run =
        documents?.run ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RUN_TIMING_UNAVAILABLE", "Run timing is not available")
    val panel =
        GrafanaPanelRequest(
            call.singleQuery("dashboard") ?: malformed("Dashboard UID is required"),
            call.intQuery("panel", 1, 1..Int.MAX_VALUE),
            Instant.parse(run.getValue("started_at").jsonPrimitive.content).toEpochMilli(),
            Instant.parse(run.getValue("ended_at").jsonPrimitive.content).toEpochMilli(),
            theme = call.singleQuery("theme") ?: "light",
        )
    try {
        grafanaPanelLink(profile, panel)
    } catch (_: IllegalArgumentException) {
        malformed("Grafana panel request is invalid")
    }
    return profile to panel
}

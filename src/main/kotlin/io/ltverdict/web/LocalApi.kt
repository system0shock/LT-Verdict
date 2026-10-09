package io.ltverdict.web

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AiModelsConfig
import io.ltverdict.core.RuleFailure
import io.ltverdict.integrations.jenkins.JenkinsProfileSummary
import io.ltverdict.integrations.jenkins.JenkinsWorkflow
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.PostgresProfile
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceProfile
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.security.SecureRandom
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
    val aiModels: AiModelsConfig? = null,
    // Test seam: production always uses the 4 GiB input limit.
    val uploadLimitBytes: Long = MAX_UPLOAD_BYTES,
)

internal fun Application.installLocalApi(context: LocalApiContext) {
    val seriesCache = SnapshotCache()
    val sessionToken = randomToken()
    val csrfToken = randomToken()
    val postgresCapturePermit = Semaphore(1)
    val jenkinsPermit = Semaphore(1)
    val podViewPermit = kotlinx.coroutines.sync.Semaphore(1)

    intercept(ApplicationCallPipeline.Plugins) {
        call.addSecurityHeaders()
        val authority = "127.0.0.1:${call.request.local.serverPort}"
        if (call.request.headers[HttpHeaders.Host] != authority) {
            call.respondError(HttpStatusCode.Forbidden, "FORBIDDEN", "Request host is not allowed")
            finish()
            return@intercept
        }
        if (call.request.httpMethod in MUTATING_METHODS) {
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
        } catch (failure: InvalidPodView) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "INVALID_POD_VIEW", "Pod view is invalid", failure.errors)
            finish()
        } catch (failure: ApiFailure) {
            call.respondError(failure.status, failure.code, failure.message, limit = failure.limit)
            finish()
        } catch (failure: RuleFailure) {
            call.respondError(failure.status(), failure.code, failure.message)
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
                    put("advisory_ai", context.aiModels?.bootstrapJson() ?: JsonNull)
                },
            )
        }

        runRoutes(context)
        grafanaRoutes(context)
        jenkinsRoutes(context, jenkinsPermit)
        baselineRoutes(context)
        releaseRoutes(context)
        comparisonRoutes(context)
        jobRoutes(context)
        sourceRoutes(context, postgresCapturePermit)
        artifactRoutes(context)
        analyticsRoutes(context)
        adviceRoutes(context)
        resourceSeriesRoutes(context, seriesCache)
        podViewRoutes(context, podViewPermit)

        route("/api/{...}") {
            handle {
                notFound("Endpoint was not found")
            }
        }
    }
}

private fun ApplicationCall.addSecurityHeaders() {
    response.headers.append("Content-Security-Policy", CONTENT_SECURITY_POLICY)
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append(HttpHeaders.CacheControl, "no-store")
}

private fun randomToken(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return HexFormat.of().formatHex(bytes)
}

private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)

private const val SESSION_COOKIE = "ltv_session"

private const val CSRF_HEADER = "X-LTV-CSRF"

// Stays in this file: ui/e2e/rules-adapters.spec.ts reads this declaration to keep the UI policy bound aligned with it.
internal const val MAX_POLICY_BYTES = 1_048_576

private const val CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
        "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"

package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ltverdict.integrations.grafana.GrafanaPanelRequest
import io.ltverdict.integrations.grafana.grafanaPanelLink
import io.ltverdict.integrations.grafana.renderGrafanaPanel
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Base64

internal fun Route.grafanaRoutes(context: LocalApiContext) {
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

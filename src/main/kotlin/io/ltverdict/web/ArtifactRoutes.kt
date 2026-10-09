package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files

internal fun Route.artifactRoutes(context: LocalApiContext) {
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
}

package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ltverdict.core.ReleaseFactsCollector
import io.ltverdict.core.parseReleaseCreate
import io.ltverdict.core.parseReleaseUpdate
import io.ltverdict.core.releaseAfterParameter
import io.ltverdict.core.releaseDraft
import io.ltverdict.core.releaseField
import io.ltverdict.core.releaseOkStates
import io.ltverdict.core.releaseProfileSummary
import io.ltverdict.core.releaseSeriesParameter
import io.ltverdict.core.releaseStateKeys
import io.ltverdict.core.releaseUpdated
import io.ltverdict.core.releaseView
import io.ltverdict.core.requireReleaseId
import io.ltverdict.core.requireReleaseStart
import io.ltverdict.storage.MAX_RELEASES
import io.ltverdict.storage.ReleasePage
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun Route.releaseRoutes(context: LocalApiContext) {
    get("/api/releases") {
        call.requireOnlyQueries("series", "after", "limit")
        val series = call.singleQuery("series")?.let(::releaseSeriesParameter)
        val after = call.singleQuery("after")?.let(::releaseAfterParameter)
        val limit = call.intQuery("limit", DEFAULT_RELEASE_LIMIT, 1..MAX_RELEASE_LIMIT)
        val page = releaseOperation { context.store.listReleases(series, after, limit) }
        // Existence only: the full check of a reference belongs to the read by identifier.
        val states =
            releaseOperation {
                page.releases.flatMap(::releaseStateKeys).toSet().associateWith { (runId, analysisId) ->
                    if (context.store.analysisExists(runId, analysisId)) "OK" else "MISSING"
                }
            }
        call.respondJson(releasePageJson(page, states))
    }

    get("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        val id = call.releaseIdParameter()
        val record = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
        val states =
            releaseOperation {
                releaseStateKeys(record).toSet().associateWith { (runId, analysisId) -> context.store.analysisState(runId, analysisId) }
            }
        call.respondJson(releaseView(record, states))
    }

    post("/api/releases") {
        call.requireOnlyQueries()
        call.requireJson()
        val body = receiveBaselineRequest(call, "Release")
        val request = parseReleaseCreate(body)
        val (startedAt, analyses) = releaseFacts(context.store, request.runId, request.analysisIds)
        val draft = releaseDraft(request, startedAt, analyses)
        val created = releaseOperation { context.store.createRelease(draft, Instant.now()) }
        call.respondJson(releaseView(created, releaseOkStates(created)), HttpStatusCode.Created)
    }

    put("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        call.requireJson()
        val id = call.releaseIdParameter()
        val body = receiveBaselineRequest(call, "Release")
        val request = parseReleaseUpdate(body)
        val existing = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
        val (startedAt, analyses) = releaseFacts(context.store, existing.releaseField("run_id"), request.analysisIds)
        requireReleaseStart(existing, startedAt)
        val stamp = JsonPrimitive(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
        val updated =
            releaseOperation {
                context.store.replaceRelease(id) { current -> releaseUpdated(current, request, analyses, stamp) }
            }
        call.respondJson(releaseView(updated, releaseOkStates(updated)))
    }

    delete("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        val id = call.releaseIdParameter()
        if (!releaseOperation { context.store.deleteRelease(id) }) notFound("Release was not found")
        call.respondJson(buildJsonObject { put("release", JsonNull) })
    }
}

private fun ApplicationCall.releaseIdParameter(): String = requireReleaseId(parameters["releaseId"])

// Reads one analysis at a time and drops its parsed tree after the facts are copied: the peak is one result.
private suspend fun releaseFacts(
    store: RunBundleStore,
    runId: String,
    analysisIds: List<String>,
): Pair<String, List<JsonObject>> {
    val collector = ReleaseFactsCollector(runId)
    for (analysisId in analysisIds) {
        val verified =
            releaseOperation {
                try {
                    store.readVerifiedAnalysis(runId, analysisId) ?: notFound("Analysis was not found")
                } catch (failure: IllegalArgumentException) {
                    if (failure.message == "RESULT_TOO_LARGE") {
                        throw ApiFailure(
                            HttpStatusCode.UnprocessableEntity,
                            "RELEASE_RESULT_TOO_LARGE",
                            "Analysis result exceeds the verification limit",
                        )
                    }
                    throw failure
                }
            }
        collector.add(analysisId, verified.run, verified.result, verified.identity)
    }
    return collector.finish()
}

private fun releasePageJson(
    page: ReleasePage,
    states: Map<Pair<String, String>, String>,
): JsonObject =
    buildJsonObject {
        put("releases", JsonArray(page.releases.map { releaseView(it, states) }))
        put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "series_summary",
            buildJsonArray {
                page.seriesSummary.forEach { (series, count) ->
                    add(
                        buildJsonObject {
                            put("series", series)
                            put("count", count)
                        },
                    )
                }
            },
        )
        put("corrupt_count", page.corruptCount)
        put(
            "corrupt_names",
            buildJsonArray {
                page.corruptNames.forEach {
                    add(
                        buildJsonObject {
                            put("name", it.name)
                            put("reason", it.reason)
                        },
                    )
                }
            },
        )
    }

// Profile and series are auxiliary for comparison and dynamics (ADR 0019, section 5): a registry the store refuses to scan, and
// an analysis named by several records, leave the release unknown instead of failing the request.
internal fun RunBundleStore.releasesOfAnalyses(analysisIds: Set<String>): Map<String, JsonObject> =
    try {
        findReleasesByAnalysis(analysisIds).byAnalysis
    } catch (failure: IllegalStateException) {
        if (failure.message.orEmpty().startsWith("CORRUPT_RELEASE_REGISTRY")) emptyMap() else throw failure
    } catch (_: IOException) {
        emptyMap()
    } catch (_: DirectoryIteratorException) {
        emptyMap()
    }

internal fun JsonObject?.releaseLabel(): String? = (this?.get("label") as? JsonPrimitive)?.content

internal fun JsonObject?.releaseProfile(): String? = releaseProfileSummary(this?.get("profile") as? JsonObject)

// Maps store failures to the private API codes; messages never carry user text (label, notes, profile).
private suspend fun <T> releaseOperation(action: () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            action()
        } catch (_: NoSuchElementException) {
            notFound("Release or referenced analysis was not found")
        } catch (failure: IllegalArgumentException) {
            when (failure.message) {
                "RELEASE_LIMIT_REACHED" ->
                    throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_LIMIT_REACHED", "Release limit is reached", MAX_RELEASES)
                "RELEASE_ANALYSIS_ALREADY_REGISTERED" ->
                    conflict(
                        "RELEASE_ANALYSIS_ALREADY_REGISTERED",
                        "An analysis already belongs to a release",
                    )
                "RELEASE_TOO_LARGE" -> throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "RELEASE_TOO_LARGE",
                    "Release record exceeds its size limit",
                )
                "RELEASE_CHANGED" -> conflict("RELEASE_CHANGED", "Release was changed concurrently; reload and retry")
                "INVALID_RELEASE" -> throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "RELEASE_FACTS_INVALID",
                    "Release record is invalid",
                )
                "INVALID_RELEASE_ID", "INVALID_PAGE_LIMIT" -> malformed("Release query is invalid")
                else -> throw failure
            }
        } catch (failure: IllegalStateException) {
            val message = failure.message.orEmpty()
            when {
                message.startsWith("CORRUPT_RELEASE_REGISTRY") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE_REGISTRY", "Release registry is corrupt")
                message.startsWith("CORRUPT_RELEASE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE", "Release record is corrupt")
                message.startsWith("CORRUPT_RUN_BUNDLE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RUN_BUNDLE", "Referenced analysis is corrupt")
                else -> throw failure
            }
        }
    }

private const val DEFAULT_RELEASE_LIMIT = 50

private const val MAX_RELEASE_LIMIT = 100

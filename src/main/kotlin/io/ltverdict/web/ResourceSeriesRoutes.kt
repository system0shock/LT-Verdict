package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ltverdict.core.DEFAULT_POD_VIEW_PAGE_ROWS
import io.ltverdict.core.MAX_CATALOG_PAGE
import io.ltverdict.core.MAX_POD_VIEW_BYTES
import io.ltverdict.core.MAX_POD_VIEW_PAGE_ROWS
import io.ltverdict.core.MAX_VALUES_SERIES
import io.ltverdict.core.PodViewQueryException
import io.ltverdict.core.PodViewV1
import io.ltverdict.core.PodViewValidation
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.SeriesGrid
import io.ltverdict.core.SeriesQueryException
import io.ltverdict.core.catalogJson
import io.ltverdict.core.planValuesPage
import io.ltverdict.core.podViewMetadataJson
import io.ltverdict.core.podViewValuesJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validatePodView
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.core.valuesJson
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore

internal fun Route.resourceSeriesRoutes(
    context: LocalApiContext,
    seriesCache: SnapshotCache,
) {
    get("/api/runs/{runId}/analyses/{analysisId}/resource-series") {
        call.requireQueries(setOf("after", "limit"), emptySet())
        val after = call.singleQuery("after")
        if (after != null && !validSeriesId(after)) malformed("after is invalid")
        val limit = call.intQuery("limit", MAX_CATALOG_PAGE, 1..MAX_CATALOG_PAGE)
        val stored = context.store.requireAnalysis(call)
        val artifact =
            stored.artifacts.firstOrNull { it.path == RESOURCE_SNAPSHOT_FILE }
                ?: notFound("Resource snapshot was not found")
        val body =
            withContext(Dispatchers.IO) {
                seriesCache.use(
                    "${stored.path}|${artifact.sha256}",
                    { decodeResourceSeriesSnapshot(stored.path.resolve(artifact.path)) },
                ) {
                    catalogJson(it.snapshot, it.semanticSha256, after, limit)
                }
            }
        call.respondJson(body)
    }

    get("/api/runs/{runId}/analyses/{analysisId}/resource-series/values") {
        call.requireQueries(setOf("from_ms", "to_ms", "step_ms", "limit"), setOf("series_id"))
        val ids =
            call.request.queryParameters
                .getAll("series_id")
                .orEmpty()
        if (ids.isEmpty() || ids.size > MAX_VALUES_SERIES || ids.size != ids.toSet().size || ids.any { !validSeriesId(it) }) {
            malformed("series_id is invalid")
        }
        val from = call.optionalLongQuery("from_ms")
        val to = call.optionalLongQuery("to_ms")
        val step = call.optionalLongQuery("step_ms")
        val limit = call.optionalIntQuery("limit")
        val stored = context.store.requireAnalysis(call)
        val artifact =
            stored.artifacts.firstOrNull { it.path == RESOURCE_SNAPSHOT_FILE }
                ?: notFound("Resource snapshot was not found")
        val body =
            withContext(Dispatchers.IO) {
                seriesCache.use(
                    "${stored.path}|${artifact.sha256}",
                    { decodeResourceSeriesSnapshot(stored.path.resolve(artifact.path)) },
                ) { decoded ->
                    if (ids.any { id -> decoded.snapshot.series.none { it.id == id } }) notFound("Series was not found")
                    val grid = SeriesGrid(decoded.snapshot.startEpochMillis, decoded.snapshot.stepMillis, decoded.snapshot.pointCount)
                    val plan =
                        try {
                            planValuesPage(grid, step, from, to, limit, ids.size)
                        } catch (failure: SeriesQueryException) {
                            if (failure.tooLarge) tooLarge(failure.message ?: "Resource series limit exceeded")
                            malformed(failure.message ?: "Resource series query is invalid")
                        }
                    valuesJson(decoded.snapshot, decoded.semanticSha256, ids, plan)
                }
            }
        call.respondJson(body)
    }
}

internal fun Route.podViewRoutes(
    context: LocalApiContext,
    podViewPermit: kotlinx.coroutines.sync.Semaphore,
) {
    get("/api/runs/{runId}/analyses/{analysisId}/pod-view") {
        call.requireOnlyQueries()
        val (view, sha256) = context.store.requirePodView(call, podViewPermit)
        call.respondJson(podViewMetadataJson(view, sha256))
    }

    get("/api/runs/{runId}/analyses/{analysisId}/pod-view/values") {
        call.requireOnlyQueries("service", "metric", "from_ms", "to_ms", "limit", "after")
        val service = call.singleQuery("service")?.takeIf(::validSeriesId) ?: malformed("service is required")
        val metric = call.singleQuery("metric")?.also { if (!validSeriesId(it)) malformed("metric is invalid") }
        val from = call.optionalLongQuery("from_ms")
        val to = call.optionalLongQuery("to_ms")
        val limit = call.intQuery("limit", DEFAULT_POD_VIEW_PAGE_ROWS, 1..MAX_POD_VIEW_PAGE_ROWS)
        val after = call.singleQuery("after")
        val (view, sha256) = context.store.requirePodView(call, podViewPermit)
        val body =
            try {
                podViewValuesJson(view, sha256, service, metric, from, to, limit, after)
            } catch (failure: PodViewQueryException) {
                when (failure.kind) {
                    PodViewQueryException.Kind.SERVICE_NOT_FOUND ->
                        throw ApiFailure(HttpStatusCode.NotFound, "POD_VIEW_SERVICE_NOT_FOUND", "Service was not found")
                    PodViewQueryException.Kind.INVALID_CURSOR ->
                        throw ApiFailure(HttpStatusCode.BadRequest, "INVALID_CURSOR", "after is not a row of this selection")
                    PodViewQueryException.Kind.INVALID_QUERY -> malformed(failure.message ?: "Pod view query is invalid")
                }
            }
        call.respondJson(body)
    }
}

/**
 * Reads the stored pod-view with the hash check of RunBundleStore.readPodViewBytes on every call (no cache, so a swapped
 * file is seen by the next request). The parse is serialized: one 12 MiB document at a time is held in memory.
 */
private suspend fun RunBundleStore.requirePodView(
    call: ApplicationCall,
    permit: kotlinx.coroutines.sync.Semaphore,
): Pair<PodViewV1, String> {
    // Ordinary reads compare artifact sizes, so a deleted or resized pod-view.json already fails requireAnalysis.
    val corrupt = { failure: IllegalStateException -> failure.message?.startsWith("CORRUPT_RUN_BUNDLE") == true }
    try {
        requireAnalysis(call)
    } catch (failure: IllegalStateException) {
        if (corrupt(failure)) corruptPodView()
        throw failure
    }
    val runId = checkNotNull(call.parameters["runId"])
    val analysisId = checkNotNull(call.parameters["analysisId"])
    return permit.withPermit {
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    readPodViewBytes(runId, analysisId)
                } catch (failure: IllegalStateException) {
                    if (corrupt(failure)) corruptPodView()
                    throw failure
                } ?: throw ApiFailure(HttpStatusCode.NotFound, "POD_VIEW_NOT_FOUND", "Pod view was not found")
            val valid = validatePodView(bytes.inputStream(), MAX_POD_VIEW_BYTES) as? PodViewValidation.Valid ?: corruptPodView()
            val sha256 = sha256Hex(bytes)
            if (valid.canonicalSha256 != sha256) corruptPodView()
            valid.view to sha256
        }
    }
}

private fun corruptPodView(): Nothing =
    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_POD_VIEW", "Stored pod view is invalid")

private const val MAX_SERIES_ID_BYTES = 128

private const val RESOURCE_SNAPSHOT_FILE = "resource-snapshot.json"

private fun validSeriesId(id: String): Boolean =
    id.isNotEmpty() && id.encodeToByteArray().size <= MAX_SERIES_ID_BYTES && id.none(Char::isISOControl)

private fun decodeResourceSeriesSnapshot(path: Path): DecodedSnapshot =
    when (val validation = Files.newInputStream(path).use { validateResourceSnapshot(it) }) {
        is ResourceValidation.Valid -> DecodedSnapshot(validation.snapshot, validation.semanticSha256)
        is ResourceValidation.Invalid ->
            throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RESOURCE_SNAPSHOT", "Stored resource snapshot is invalid")
    }

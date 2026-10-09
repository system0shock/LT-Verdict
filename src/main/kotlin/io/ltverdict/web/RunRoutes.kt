package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ltverdict.integrations.report.readRunTimeline
import io.ltverdict.integrations.report.renderConfluenceReport
import io.ltverdict.integrations.report.renderSavedLoadChart
import io.ltverdict.report.readErrorGroupsFile
import io.ltverdict.report.renderAsciiDocReport
import io.ltverdict.report.renderHtmlReport
import kotlinx.coroutines.Dispatchers
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
import org.HdrHistogram.PackedHistogram
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

internal fun Route.runRoutes(context: LocalApiContext) {
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
                                    put("accepted_at", run.acceptedAt?.let(::JsonPrimitive) ?: JsonNull)
                                },
                            )
                        }
                    },
                )
                put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
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
                                    put("policy_id", analysis.policyId?.let(::JsonPrimitive) ?: JsonNull)
                                    put("policy_verdict", analysis.policyVerdict)
                                    put("run_validity", analysis.runValidity)
                                    analysis.resourceArm?.let { put("resource_arm", it) }
                                    analysis.resourceSnapshotSha256?.let { put("resource_snapshot_sha256", it) }
                                },
                            )
                        }
                    },
                )
                put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
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
        val errorGroups =
            if (format in setOf("html", "asciidoc", "confluence") && stored.artifacts.any { it.path == "error-groups.json" }) {
                withContext(Dispatchers.IO) { readErrorGroupsFile(stored.path) }
            } else {
                null
            }
        val timeline = if (format == "html") withContext(Dispatchers.IO) { readRunTimeline(stored.path) } else null
        val report =
            when (format) {
                "json" -> bytes
                "svg" -> withContext(Dispatchers.IO) { renderSavedLoadChart(stored.path.resolve("rollup-60s.ndjson")) }
                "html" -> renderHtmlReport(bytes, analysisId, errorGroups, null, timeline)
                "confluence" -> renderConfluenceReport(bytes, analysisId, errorGroups)
                else -> renderAsciiDocReport(bytes, analysisId, errorGroups)
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

private const val DEFAULT_RUN_LIMIT = 100

private const val MAX_RUN_LIMIT = 100

private const val DEFAULT_ANALYSIS_LIMIT = 25

private const val MAX_ANALYSIS_LIMIT = 100

private const val DEFAULT_BUCKET_LIMIT = 500

private const val MAX_BUCKET_LIMIT = 500

private const val MAX_BUCKET_LATENCY_MILLIS = 86_400_000L

private const val RESULT_FILE = "analysis-result.json"

private const val NORMALIZED_FILE = "normalized-1s.ndjson"

private val ROLLUPS = setOf(1, 10, 30, 60)

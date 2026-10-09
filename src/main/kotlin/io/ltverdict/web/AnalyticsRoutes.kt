package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.call
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ltverdict.core.AnalyticsExportFormat
import io.ltverdict.core.SavedAnalysisForComparison
import io.ltverdict.core.buildRunDynamics
import io.ltverdict.core.compareTransactions
import io.ltverdict.core.metricPackAnalysis
import io.ltverdict.core.openSearchOverlay
import io.ltverdict.core.renderRunDynamicsExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun Route.analyticsRoutes(context: LocalApiContext) {
    get("/api/runs/{runId}/analyses/{analysisId}/analytics") {
        val query = call.request.queryParameters
        if (query.names().any { it !in setOf("limit", "transaction", "transaction_limit", "format", "exclude", "series") } ||
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
        val currentReference =
            buildJsonObject {
                put("run_id", runId)
                put("analysis_id", analysisId)
            }
        val scope = resolveBaselineScope(call, context.store, currentReference)
        // Only the selection: analytics does not use the condition record, so a damaged record must not fail it. Without a
        // series the legacy file is read alone, as comparison does, so a damaged slot of another series does not reach it.
        val baselineSelection =
            baselineOperation {
                if (scope.series == null) {
                    context.store.readBaseline()
                } else {
                    context.store
                        .listBaselineSlots()
                        .firstOrNull { it.series == scope.series && it.arm == scope.arm }
                        ?.selection
                }
            }
        val response =
            withContext(Dispatchers.IO) {
                val current = context.store.readComparisonDocuments(runId, analysisId) ?: notFound("Analysis was not found")
                val baseline = baselineSelection?.get("reference") as? JsonObject
                val candidates = mutableListOf<SavedAnalysisForComparison>()
                val history = context.store.readComparisonHistory()
                // One registry pass for every row: the label and the profile come from the release record (ADR 0019, section 8).
                val releases =
                    context.store.releasesOfAnalyses(
                        history.entries.map { it.analysisId }.toSet() +
                            analysisId +
                            listOfNotNull(baseline?.get("analysis_id")?.jsonPrimitive?.content),
                    )
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
                            applicationVersion = releases[entry.analysisId].releaseLabel(),
                            loadProfile = releases[entry.analysisId].releaseProfile(),
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
                            applicationVersion = releases[baseline.getValue("analysis_id").jsonPrimitive.content].releaseLabel(),
                            loadProfile = releases[baseline.getValue("analysis_id").jsonPrimitive.content].releaseProfile(),
                        )
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
                                SavedAnalysisForComparison(
                                    currentReference,
                                    it,
                                    current.result,
                                    current.identity,
                                    applicationVersion = releases[analysisId].releaseLabel(),
                                    loadProfile = releases[analysisId].releaseProfile(),
                                ),
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
}

package io.ltverdict.sources

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream

internal fun analyzeWithSources(
    service: AnalysisService,
    request: AnalysisRequest,
    source: PromqlSource?,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): AnalysisOutcome {
    val selection = request.sourceRequest ?: return service.analyze(request, processedBytes, checkCancelled)
    require(request.resources == null && request.diagnostics == null && request.sourceAcquisition == null) { "SOURCE_INPUT_CONFLICT" }
    val acquisition = requireNotNull(source) { "SOURCE_NOT_CONFIGURED" }.acquire(selection, request.input.sha256, checkCancelled)
    return service.analyze(
        request.copy(sourceRequest = null, resources = acquisition.snapshot, sourceAcquisition = acquisition),
        processedBytes,
        checkCancelled,
    )
}

internal fun readOpenSearchContext(
    input: InputStream,
    loadInputSha256: String,
    resources: ResourceValidation.Valid? = null,
): SourceAcquisition {
    val context = validateOpenSearchArtifact(input, loadInputSha256)
    val status =
        context
            .getValue("coverage")
            .jsonObject
            .getValue("status")
            .jsonPrimitive.content
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("profile_id", context.getValue("profile_id"))
            put("source_kind", "opensearch")
            put("transport", "manual")
            put("status", status)
            put("request_count", 0)
            put("retries", 0)
            put("throttle_wait_ms", 0)
            put("cap_exceeded", false)
            put(
                "queries",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "import")
                            put("status", if (status == "COMPLETE") "SUCCESS" else "PARTIAL")
                        },
                    )
                },
            )
        }
    return SourceAcquisition(
        resources,
        summary,
        mapOf("source-acquisition.json" to canonicalJson(summary), "opensearch-errors.json" to canonicalJson(context)),
        listOf(context),
    )
}

internal fun acquireOpenSearch(
    profile: SourceProfile,
    request: SourceRequest,
    loadInputSha256: String,
    http: SourceHttp,
    checkCancelled: () -> Unit,
): SourceAcquisition {
    val mapping = requireNotNull(profile.openSearch) { "SOURCE_PROFILE_INVALID" }
    val query = buildOpenSearchQuery(mapping, request, profile.governor.timeoutMillis)
    val budget = SourceBudget(profile.governor.maxRequestsPerRun)
    val artifacts = linkedMapOf<String, ByteArray>()
    var context: JsonObject? = null
    var reason: String? = null
    try {
        val body = http.search(profile, query, budget, checkCancelled)
        context = decodeOpenSearchResponse(body, mapping, request, loadInputSha256, profile.baseUrl)
        artifacts["source-response-1.json"] = body
        artifacts["opensearch-errors.json"] = canonicalJson(context)
    } catch (failure: SourceHttpFailure) {
        if (failure.code == "SOURCE_CANCELLED") throw failure
        reason = failure.code
    } catch (failure: IllegalArgumentException) {
        reason = failure.message?.takeIf { it.matches(Regex("OPENSEARCH_[A-Z_]+")) } ?: "OPENSEARCH_RESPONSE_INVALID"
    }
    val status =
        context
            ?.getValue("coverage")
            ?.jsonObject
            ?.getValue("status")
            ?.jsonPrimitive
            ?.content ?: "FAILED"
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("profile_id", profile.id)
            put("source_kind", profile.sourceKind.wireName)
            put("transport", profile.transport.wireName)
            put("status", status)
            put("start_epoch_ms", request.startEpochMillis)
            put("end_epoch_ms", request.endEpochMillis)
            put("step_ms", request.stepMillis)
            put("request_count", budget.requestCount)
            put("retries", budget.retries)
            put("throttle_wait_ms", budget.throttleWaitMillis)
            put("cap_exceeded", budget.capExceeded)
            put(
                "queries",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "errors")
                            put(
                                "status",
                                if (status == "COMPLETE") {
                                    "SUCCESS"
                                } else if (status == "PARTIAL") {
                                    "PARTIAL"
                                } else {
                                    "FAILED"
                                },
                            )
                            put("expression_sha256", sha256Hex(query))
                            reason?.let { put("reason", it) }
                        },
                    )
                },
            )
        }
    artifacts["source-acquisition.json"] = canonicalJson(summary)
    return SourceAcquisition(null, summary, artifacts, listOfNotNull(context))
}

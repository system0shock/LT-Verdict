package io.ltverdict.mcp

import io.ltverdict.ai.sanitizeAdvisoryJson
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.normalizeProvenanceText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class McpToolResult(
    val payload: JsonObject,
    val isError: Boolean,
)

internal class McpTools(
    private val api: McpApiClient,
) {
    fun listTools(): JsonArray = TOOL_DEFINITIONS

    fun callTool(
        name: String,
        arguments: JsonObject,
    ): McpToolResult =
        try {
            val payload =
                when (name) {
                    "analyze" -> analyze(arguments)
                    "get_result" -> getResult(arguments)
                    "list_incidents" -> listIncidents(arguments)
                    "list_transactions" -> listTransactions(arguments)
                    "validate_policy" -> validatePolicy(arguments)
                    "compare" -> compare(arguments)
                    "get_resource_series" -> getResourceSeries(arguments)
                    "probe_source" -> probeSource(arguments)
                    "save_advice" -> saveAdvice(arguments)
                    else -> throw McpToolInputException("UNKNOWN_TOOL", "Unknown tool: $name")
                }
            McpToolResult(payload, isError = false)
        } catch (failure: McpToolInputException) {
            McpToolResult(errorPayload(failure.code, failure.message), isError = true)
        } catch (failure: McpApiException) {
            McpToolResult(
                errorPayload(failure.code, sanitizeText(failure.message), failure.details),
                isError = true,
            )
        } catch (_: Exception) {
            McpToolResult(errorPayload("TOOL_EXECUTION_FAILED", "Tool execution failed"), isError = true)
        }

    private fun analyze(args: JsonObject): JsonObject {
        val rawPath = args.requiredString("input_path")
        val inputPath =
            try {
                Path.of(rawPath)
            } catch (_: InvalidPathException) {
                throw McpToolInputException("INVALID_INPUT", "input_path is invalid")
            }
        if (Files.isSymbolicLink(inputPath) || !Files.isRegularFile(inputPath, LinkOption.NOFOLLOW_LINKS)) {
            throw McpToolInputException("INVALID_INPUT", "input_path must be a regular non-symlink file")
        }
        val timeoutSeconds = args.optionalInt("timeout_seconds", 120, 1..600)
        val jenkinsBuild =
            try {
                normalizeProvenanceText(args.optionalString("jenkins_build"), "jenkins_build")
            } catch (_: IllegalArgumentException) {
                throw McpToolInputException("INVALID_PROVENANCE", "jenkins_build is invalid")
            }
        val commit =
            try {
                normalizeProvenanceText(args.optionalString("commit"), "commit")
            } catch (_: IllegalArgumentException) {
                throw McpToolInputException("INVALID_PROVENANCE", "commit is invalid")
            }

        val uploaded = api.uploadInputFile(inputPath)
        val runId = uploaded.stringField("run_id") ?: throw McpToolInputException("INVALID_API_RESPONSE", "run_id missing")

        val parts = mutableListOf<MultipartPart>(MultipartPart.Text("run_id", runId))
        jenkinsBuild?.let { parts += MultipartPart.Text("jenkins_build", it) }
        commit?.let { parts += MultipartPart.Text("commit", it) }
        ANALYZE_JSON_PARTS.forEach { (argKey, partName) ->
            args.optionalObject(argKey)?.let { obj ->
                parts += MultipartPart.JsonBytes(partName, "$partName.json", canonicalJson(obj))
            }
        }
        val submitted = api.postMultipartJob(parts)
        val jobId = submitted.stringField("job_id") ?: throw McpToolInputException("INVALID_API_RESPONSE", "job_id missing")

        val deadlineNanos = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        var status = submitted
        while (true) {
            val state = status.stringField("state")
            if (state == "COMPLETE") break
            if (state == "FAILED" || state == "CANCELLED") {
                val diagnostic = status["diagnostic"] as? JsonObject
                val diagCode = diagnostic?.stringField("code") ?: "ANALYSIS_$state"
                val diagMessage = diagnostic?.stringField("message") ?: "Analysis job $state"
                throw McpApiException(422, diagCode, diagMessage)
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw McpApiException(504, "ANALYSIS_TIMEOUT", "Analysis job did not complete within $timeoutSeconds seconds")
            }
            Thread.sleep(50)
            status = api.get("/api/jobs/$jobId")
        }

        val analysisId = status.stringField("analysis_id") ?: throw McpToolInputException("INVALID_API_RESPONSE", "analysis_id missing")
        val result = api.get("/api/runs/$runId/analyses/$analysisId/result")
        return summarizeResult(runId, analysisId, result, jenkinsBuild, commit)
    }

    private fun getResult(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val includeEvidence = args.optionalBoolean("include_evidence", false)
        val offset = args.optionalInt("offset", 0, 0..1_000_000)
        val limit = args.optionalInt("limit", 100, 1..200)
        val result = api.get("/api/runs/$runId/analyses/$analysisId/result")
        val sanitized = sanitizeAdvisoryJson(result) as JsonObject
        val out =
            if (!includeEvidence) {
                summarizeResult(runId, analysisId, sanitized, jenkinsBuild = null, commit = null, includeFindings = true)
            } else {
                val allEvidence = (sanitized["evidence"] as? JsonArray).orEmpty()
                val sliced =
                    if (offset >= allEvidence.size) {
                        emptyList()
                    } else {
                        allEvidence.subList(offset, minOf(offset + limit, allEvidence.size))
                    }
                buildJsonObject {
                    sanitized.forEach { (key, value) ->
                        if (key != "evidence") put(key, value)
                    }
                    put("analysis_id", analysisId)
                    put("evidence_total", allEvidence.size)
                    put("evidence_offset", offset)
                    put("evidence_limit", limit)
                    put("evidence", JsonArray(sliced))
                }
            }
        if (canonicalJson(out).size > MAX_MCP_RESULT_BYTES) {
            throw McpApiException(
                413,
                "MCP_RESULT_TOO_LARGE",
                "Result payload exceeds MCP bound; request include_evidence=false or smaller limit",
            )
        }
        return out
    }

    private fun listIncidents(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val family =
            args.optionalString("family")?.also {
                if (it !in setOf("transaction", "resource")) {
                    throw McpToolInputException("INVALID_ARGUMENT", "family must be transaction or resource")
                }
            }
        val priority =
            args.optionalString("priority")?.also {
                if (it !in setOf("HIGH", "MEDIUM", "LOW")) {
                    throw McpToolInputException("INVALID_ARGUMENT", "priority must be HIGH, MEDIUM or LOW")
                }
            }
        val includeHidden = args.optionalBoolean("include_hidden", false)
        val limit = args.optionalInt("limit", 64, 1..64)

        val result = api.get("/api/runs/$runId/analyses/$analysisId/result")
        val incidentsObj = result["incidents"] as? JsonObject
        if (incidentsObj == null) {
            return buildJsonObject {
                put("run_id", runId)
                put("analysis_id", analysisId)
                put("status", "ABSENT")
                put("total_count", 0)
                put("omitted_count", 0)
                put("incidents", JsonArray(emptyList()))
            }
        }
        val status = incidentsObj.stringField("status") ?: "EVALUATED"
        if (status == "NOT_EVALUATED") {
            return buildJsonObject {
                put("run_id", runId)
                put("analysis_id", analysisId)
                put("status", "NOT_EVALUATED")
                incidentsObj.stringField("reason_code")?.let { put("reason_code", it) }
                put("total_count", 0)
                put("omitted_count", 0)
                put("incidents", JsonArray(emptyList()))
            }
        }

        val findingRefs = buildIdPointerMap(result["findings"] as? JsonArray, "findings")
        val evidenceRefs = buildIdPointerMap(result["evidence"] as? JsonArray, "evidence", excludeKnee = true)
        val rawItems = (incidentsObj["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val hiddenPresent = rawItems.any { (it["in_overview"] as? JsonPrimitive)?.booleanOrNull == false }

        val filtered =
            rawItems
                .asSequence()
                .filter { item -> includeHidden || (item["in_overview"] as? JsonPrimitive)?.booleanOrNull != false }
                .filter { item ->
                    if (family == null) {
                        true
                    } else {
                        val scopeKind = (item["scope"] as? JsonObject)?.stringField("kind")
                        val itemFamily = if (scopeKind == "entity") "resource" else "transaction"
                        itemFamily == family
                    }
                }.filter { item -> priority == null || item.stringField("priority") == priority }
                .take(limit)
                .map { item ->
                    val sanitized = sanitizeAdvisoryJson(item) as JsonObject
                    val refs = linkedSetOf<String>()
                    (item["finding_ids"] as? JsonArray).orEmpty().forEach { idEl ->
                        val id = (idEl as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        if (id != null) findingRefs[id]?.let(refs::add)
                    }
                    (item["evidence_ids"] as? JsonArray).orEmpty().forEach { idEl ->
                        val id = (idEl as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        if (id != null) evidenceRefs[id]?.let(refs::add)
                    }
                    buildJsonObject {
                        sanitized.forEach { (k, v) -> put(k, v) }
                        put("evidence_refs", JsonArray(refs.map(::JsonPrimitive)))
                    }
                }.toList()

        return buildJsonObject {
            put("run_id", runId)
            put("analysis_id", analysisId)
            put("status", status)
            put("total_count", incidentsObj["total_count"] ?: JsonPrimitive(rawItems.size))
            put("omitted_count", incidentsObj["omitted_count"] ?: JsonPrimitive(0))
            put("has_hidden", hiddenPresent && !includeHidden)
            put("incidents", JsonArray(filtered))
        }
    }

    private fun listTransactions(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val windowId =
            args.optionalString("window_id")?.also {
                if (it.isBlank() || it.encodeToByteArray().size > 128) {
                    throw McpToolInputException("INVALID_ARGUMENT", "window_id is invalid")
                }
            }
        val query =
            args.optionalString("query")?.also {
                if (it.encodeToByteArray().size > 256) {
                    throw McpToolInputException("INVALID_ARGUMENT", "query exceeds 256 bytes")
                }
            }
        val offset = args.optionalInt("offset", 0, 0..1_000_000)
        val limit = args.optionalInt("limit", 100, 1..500)

        val result = api.get("/api/runs/$runId/analyses/$analysisId/result")
        val evidence = (result["evidence"] as? JsonArray).orEmpty()
        val matched = mutableListOf<JsonObject>()
        evidence.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            if (obj.stringField("type") != "metric_summary") return@forEachIndexed
            val scope = obj["scope"] as? JsonObject ?: return@forEachIndexed
            if (scope.stringField("kind") != "transaction") return@forEachIndexed
            val itemWindow = obj.stringField("window_id")
            if (windowId == null && itemWindow != null) return@forEachIndexed
            if (windowId != null && itemWindow != windowId) return@forEachIndexed
            val label = scope.stringField("label").orEmpty()
            if (query != null && !label.contains(query, ignoreCase = true)) return@forEachIndexed
            val sanitized = sanitizeAdvisoryJson(obj) as JsonObject
            matched +=
                buildJsonObject {
                    sanitized.forEach { (k, v) -> put(k, v) }
                    put("evidence_ref", "analysis-result.json#/evidence/$index")
                }
        }
        val sliced =
            if (offset >= matched.size) {
                emptyList()
            } else {
                matched.subList(offset, minOf(offset + limit, matched.size))
            }
        return buildJsonObject {
            put("run_id", runId)
            put("analysis_id", analysisId)
            put("total", matched.size)
            put("offset", offset)
            put("limit", limit)
            put("transactions", JsonArray(sliced))
        }
    }

    private fun validatePolicy(args: JsonObject): JsonObject {
        val policy = args.requiredObject("policy")
        val (_, response) = api.postJson("/api/policies/validate", policy, allowUnprocessable = true)
        return response
    }

    private fun compare(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val params = mutableListOf<Pair<String, String>>()
        args.optionalString("baseline_window")?.let { params += "baseline_window" to it }
        args.optionalString("current_window")?.let { params += "current_window" to it }
        args.optionalScalarString("min_change_percent")?.let { params += "min_change_percent" to it }
        args.optionalScalarString("min_error_rate_delta")?.let { params += "min_error_rate_delta" to it }
        args.optionalString("series")?.let { params += "series" to it }
        val query = McpApiClient.encodeQuery(params)
        val response = api.get("/api/runs/$runId/analyses/$analysisId/comparison$query")
        return sanitizeAdvisoryJson(response) as JsonObject
    }

    private fun getResourceSeries(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val mode = args.optionalString("mode") ?: "catalog"
        val response =
            when (mode) {
                "catalog" -> {
                    val params = mutableListOf<Pair<String, String>>()
                    args.optionalString("after")?.let { params += "after" to it }
                    val limit = args.optionalInt("limit", 256, 1..256)
                    params += "limit" to limit.toString()
                    api.get("/api/runs/$runId/analyses/$analysisId/resource-series${McpApiClient.encodeQuery(params)}")
                }
                "values" -> {
                    val seriesIds = args.requiredStringArray("series_ids", maxItems = 8)
                    val params = mutableListOf<Pair<String, String>>()
                    seriesIds.forEach { params += "series_id" to it }
                    args.optionalLong("from_ms")?.let { params += "from_ms" to it.toString() }
                    args.optionalLong("to_ms")?.let { params += "to_ms" to it.toString() }
                    args.optionalLong("step_ms")?.let { params += "step_ms" to it.toString() }
                    val limit = args.optionalInt("limit", 120, 1..500)
                    params += "limit" to limit.toString()
                    api.get("/api/runs/$runId/analyses/$analysisId/resource-series/values${McpApiClient.encodeQuery(params)}")
                }
                else -> throw McpToolInputException("INVALID_ARGUMENT", "mode must be catalog or values")
            }
        return sanitizeAdvisoryJson(response) as JsonObject
    }

    private fun probeSource(args: JsonObject): JsonObject {
        if (args.keys.any { it in PROBE_FORBIDDEN_KEYS }) {
            throw McpToolInputException("INVALID_PROBE", "url, base_url, expression and credentials are not allowed")
        }
        if (args.keys.any { it !in PROBE_ALLOWED_ARGS }) {
            throw McpToolInputException("INVALID_PROBE", "Unsupported probe_source argument")
        }
        val queryId = args.optionalString("query_id")
        val mode = args.optionalString("mode") ?: if (queryId != null) "probe" else "validate"
        val response =
            when (mode) {
                "list" -> api.get("/api/sources")
                "validate" -> {
                    val body =
                        buildJsonObject {
                            args.optionalString("profile_id")?.let { put("profile_id", it) }
                        }
                    api.postJson("/api/sources/validate", body).second
                }
                "probe" -> {
                    val profileId = args.requiredString("profile_id")
                    val resolvedQueryId = queryId ?: throw McpToolInputException("INVALID_PROBE", "query_id is required for probe mode")
                    val body =
                        buildJsonObject {
                            put("profile_id", profileId)
                            put("query_id", resolvedQueryId)
                            args.optionalLong("window_ms")?.let { put("window_ms", it) }
                            args.optionalLong("step_ms")?.let { put("step_ms", it) }
                            args.optionalLong("end_epoch_ms")?.let { put("end_epoch_ms", it) }
                        }
                    api.postJson("/api/sources/probe", body).second
                }
                else -> throw McpToolInputException("INVALID_PROBE", "mode must be list, validate or probe")
            }
        return sanitizeAdvisoryJson(response) as JsonObject
    }

    private fun saveAdvice(args: JsonObject): JsonObject {
        val runId = args.requiredRunId()
        val analysisId = args.requiredAnalysisId()
        val runnerId = args.requiredString("runner_id")
        val modelId = args.requiredString("model_id")
        val runnerVersion = args.optionalString("runner_version")
        val durationMs = args.optionalLong("duration_ms")
        val output = args.requiredObject("output")
        val body =
            buildJsonObject {
                put("output", output)
                put(
                    "provenance",
                    buildJsonObject {
                        put("provenance_kind", "self-reported")
                        put("runner_id", runnerId)
                        runnerVersion?.let { put("runner_version", it) }
                        put("model_id", modelId)
                        durationMs?.let { put("duration_ms", it) }
                    },
                )
            }
        return api.putJson("/api/runs/$runId/analyses/$analysisId/advice", body).second
    }

    private fun summarizeResult(
        runId: String,
        analysisId: String,
        result: JsonObject,
        jenkinsBuild: String?,
        commit: String?,
        includeFindings: Boolean = false,
    ): JsonObject {
        val sanitized = sanitizeAdvisoryJson(result) as JsonObject
        val findings = (sanitized["findings"] as? JsonArray).orEmpty()
        val evidence = (sanitized["evidence"] as? JsonArray).orEmpty()
        val overall =
            evidence
                .asSequence()
                .mapNotNull { it as? JsonObject }
                .firstOrNull { item ->
                    item.stringField("type") == "metric_summary" &&
                        item.stringField("window_id") == null &&
                        (item["scope"] as? JsonObject)?.stringField("kind") == "overall"
                }
        val incidents = sanitized["incidents"] as? JsonObject
        return buildJsonObject {
            put("schema_version", sanitized["schema_version"] ?: JsonPrimitive("analysis-result.v1"))
            put("run_id", runId)
            put("analysis_id", analysisId)
            sanitized["analysis_mode"]?.let { put("analysis_mode", it) }
            put("run_validity", sanitized["run_validity"] ?: JsonNull)
            put("policy_verdict", sanitized["policy_verdict"] ?: JsonNull)
            put("analysis_coverage", sanitized["analysis_coverage"] ?: JsonNull)
            jenkinsBuild?.let { put("jenkins_build", it) }
            commit?.let { put("commit", it) }
            put("findings_count", findings.size)
            put("evidence_count", evidence.size)
            if (includeFindings) put("findings", JsonArray(findings))
            overall?.let { put("overall_metrics", it) }
            sanitized["capacity_summary"]?.let { put("capacity_summary", it) }
            if (incidents != null) {
                put(
                    "incidents_summary",
                    buildJsonObject {
                        put("status", incidents["status"] ?: JsonPrimitive("EVALUATED"))
                        put("total_count", incidents["total_count"] ?: JsonPrimitive(0))
                        put("omitted_count", incidents["omitted_count"] ?: JsonPrimitive(0))
                        put("returned_count", JsonPrimitive((incidents["items"] as? JsonArray)?.size ?: 0))
                    },
                )
            }
        }
    }

    private fun buildIdPointerMap(
        items: JsonArray?,
        section: String,
        excludeKnee: Boolean = false,
    ): Map<String, String> {
        if (items == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        items.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            if (excludeKnee && obj.stringField("type") == "capacity_knee_diagnostic") return@forEachIndexed
            val id = obj.stringField("id") ?: return@forEachIndexed
            map[id] = "analysis-result.json#/$section/$index"
        }
        return map
    }

    private fun errorPayload(
        code: String,
        message: String,
        details: JsonArray = JsonArray(emptyList()),
    ): JsonObject =
        buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                    if (details.isNotEmpty()) put("details", details)
                },
            )
        }

    private fun sanitizeText(text: String): String {
        val redacted = sanitizeAdvisoryJson(JsonPrimitive(text))
        return (redacted as? JsonPrimitive)?.content ?: "[REDACTED]"
    }
}

private class McpToolInputException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.requiredString(name: String): String =
    stringField(name)?.takeIf { it.isNotBlank() }
        ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a non-empty string")

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.let { element ->
        if (element is JsonNull) return null
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a string")
    }

private fun JsonObject.optionalScalarString(name: String): String? =
    this[name]?.let { element ->
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a scalar")
        primitive.content
    }

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a JSON object")

private fun JsonObject.optionalObject(name: String): JsonObject? =
    this[name]?.let { element ->
        if (element is JsonNull) return null
        element as? JsonObject ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a JSON object")
    }

private fun JsonObject.optionalBoolean(
    name: String,
    default: Boolean,
): Boolean =
    this[name]?.let { element ->
        if (element is JsonNull) return default
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) {
            throw McpToolInputException("INVALID_ARGUMENT", "$name must be a boolean")
        }
        primitive.booleanOrNull ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a boolean")
    } ?: default

private fun JsonObject.optionalInt(
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val element = this[name] ?: return default
    if (element is JsonNull) return default
    val primitive = element as? JsonPrimitive
    val value = if (primitive == null || primitive.isString) null else primitive.intOrNull
    if (value == null || value !in range) {
        throw McpToolInputException("INVALID_ARGUMENT", "$name must be an integer in ${range.first}..${range.last}")
    }
    return value
}

private fun JsonObject.optionalLong(name: String): Long? {
    val element = this[name] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        throw McpToolInputException("INVALID_ARGUMENT", "$name must be an integer")
    }
    return primitive.longOrNull ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be an integer")
}

private fun JsonObject.requiredStringArray(
    name: String,
    maxItems: Int,
): List<String> {
    val arr = this[name] as? JsonArray ?: throw McpToolInputException("INVALID_ARGUMENT", "$name must be a non-empty array")
    if (arr.isEmpty() || arr.size > maxItems) {
        throw McpToolInputException("INVALID_ARGUMENT", "$name must contain 1..$maxItems items")
    }
    return arr.map { el ->
        (el as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
            ?: throw McpToolInputException("INVALID_ARGUMENT", "$name entries must be non-empty strings")
    }
}

private fun JsonObject.requiredRunId(): String =
    requiredString("run_id").also {
        if (!RUN_ID_REGEX.matches(it)) throw McpToolInputException("INVALID_ARGUMENT", "run_id is invalid")
    }

private fun JsonObject.requiredAnalysisId(): String =
    requiredString("analysis_id").also {
        if (!SHA256_REGEX.matches(it)) throw McpToolInputException("INVALID_ARGUMENT", "analysis_id must be 64 lowercase hex chars")
    }

private val RUN_ID_REGEX = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")
private val SHA256_REGEX = Regex("[0-9a-f]{64}")
private const val MAX_MCP_RESULT_BYTES = 512 * 1024

private val ANALYZE_JSON_PARTS =
    listOf(
        "policy" to "policy",
        "stages" to "stages",
        "resource_snapshot" to "resource_snapshot",
        "source_request" to "source_request",
        "correlation_plan" to "correlation_plan",
        "capacity_plan" to "capacity_plan",
        "trend_plan" to "trend_plan",
        "pod_view" to "pod_view",
    )

private val PROBE_FORBIDDEN_KEYS = setOf("url", "base_url", "expression", "credentials")
private val PROBE_ALLOWED_ARGS = setOf("mode", "profile_id", "query_id", "window_ms", "step_ms", "end_epoch_ms")

private fun toolDef(
    name: String,
    description: String,
    required: List<String>,
    properties: JsonObject,
): JsonObject =
    buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
                put("properties", properties)
            },
        )
    }

private fun prop(
    type: String,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", type)
        put("description", description)
    }

private val TOOL_DEFINITIONS: JsonArray =
    buildJsonArray {
        add(
            toolDef(
                "analyze",
                "Upload a JMeter/Gatling load input file, run deterministic LT Verdict analysis, and return a bounded summary.",
                listOf("input_path"),
                buildJsonObject {
                    put("input_path", prop("string", "Path to a regular non-symlink JTL or simulation.log file."))
                    put("policy", prop("object", "Optional policy.v1 JSON object."))
                    put("stages", prop("object", "Optional load-stages.v1 JSON object."))
                    put("jenkins_build", prop("string", "Optional Jenkins build identifier stored in analysis identity."))
                    put("commit", prop("string", "Optional git commit reference stored in analysis identity."))
                    put("resource_snapshot", prop("object", "Optional resource-snapshot.v1 JSON object."))
                    put("source_request", prop("object", "Optional source-request JSON object."))
                    put("correlation_plan", prop("object", "Optional correlation-plan.v1 JSON object."))
                    put("capacity_plan", prop("object", "Optional capacity-plan.v1 JSON object."))
                    put("trend_plan", prop("object", "Optional trend-plan.v1 JSON object."))
                    put("pod_view", prop("object", "Optional pod-view.v1 JSON object."))
                    put("timeout_seconds", prop("integer", "Job polling timeout in seconds (1..600, default 120)."))
                },
            ),
        )
        add(
            toolDef(
                "get_result",
                "Read bounded, secret-redacted analysis-result.v1 summary or paginated evidence for a saved analysis.",
                listOf("run_id", "analysis_id"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID (e.g. jmeter_jtl_csv-<sha256>)."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("include_evidence", prop("boolean", "Include paginated evidence items (default false)."))
                    put("offset", prop("integer", "Evidence pagination offset (default 0)."))
                    put("limit", prop("integer", "Evidence page size (1..200, default 100)."))
                },
            ),
        )
        add(
            toolDef(
                "list_incidents",
                "List synthesized incidents (handles legacy absent or NOT_EVALUATED) enriched with evidence_refs JSON pointers.",
                listOf("run_id", "analysis_id"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("family", prop("string", "Optional filter: transaction or resource."))
                    put("priority", prop("string", "Optional filter: HIGH, MEDIUM, or LOW."))
                    put("include_hidden", prop("boolean", "Include incidents beyond overview limit (default false)."))
                    put("limit", prop("integer", "Maximum incidents to return (1..64, default 64)."))
                },
            ),
        )
        add(
            toolDef(
                "list_transactions",
                "List paginated transaction metric_summary rows with their evidence_ref JSON pointers.",
                listOf("run_id", "analysis_id"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("window_id", prop("string", "Optional stage/window ID (omit for whole-run metrics)."))
                    put("query", prop("string", "Optional case-insensitive substring filter on transaction label."))
                    put("offset", prop("integer", "Pagination offset (default 0)."))
                    put("limit", prop("integer", "Page size (1..500, default 100)."))
                },
            ),
        )
        add(
            toolDef(
                "validate_policy",
                "Validate a policy.v1 JSON object against the LT Verdict policy contract.",
                listOf("policy"),
                buildJsonObject {
                    put("policy", prop("object", "policy.v1 JSON object."))
                },
            ),
        )
        add(
            toolDef(
                "compare",
                "Compare a saved analysis against the currently selected baseline.",
                listOf("run_id", "analysis_id"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("baseline_window", prop("string", "Optional baseline window ID."))
                    put("current_window", prop("string", "Optional current window ID."))
                    put("min_change_percent", prop("string", "Optional minimum latency change percent threshold."))
                    put("min_error_rate_delta", prop("string", "Optional minimum error-rate delta threshold."))
                    put("series", prop("string", "Optional baseline series name."))
                },
            ),
        )
        add(
            toolDef(
                "get_resource_series",
                "Read resource series catalog or tightly bounded values (max 8 series_ids, max 500 cells).",
                listOf("run_id", "analysis_id"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("mode", prop("string", "catalog (default) or values."))
                    put("after", prop("string", "Catalog cursor series_id."))
                    put("series_ids", prop("array", "1..8 series IDs (required when mode=values)."))
                    put("from_ms", prop("integer", "Optional start epoch ms (mode=values)."))
                    put("to_ms", prop("integer", "Optional end epoch ms (mode=values)."))
                    put("step_ms", prop("integer", "Optional step ms (mode=values)."))
                    put("limit", prop("integer", "Page limit: 1..256 for catalog, 1..500 (default 120) for values."))
                },
            ),
        )
        add(
            toolDef(
                "probe_source",
                "List, validate, or probe configured read-only source profiles without exposing URLs, credentials, or raw errors.",
                emptyList(),
                buildJsonObject {
                    put("mode", prop("string", "list, validate, or probe (defaults to probe if query_id given, else validate)."))
                    put("profile_id", prop("string", "Configured source profile ID."))
                    put("query_id", prop("string", "Saved query ID (required for probe mode)."))
                    put("window_ms", prop("integer", "Optional probe window duration in ms."))
                    put("step_ms", prop("integer", "Optional probe step in ms."))
                    put("end_epoch_ms", prop("integer", "Optional probe end epoch ms."))
                },
            ),
        )
        add(
            toolDef(
                "save_advice",
                "Persist self-reported advisory conclusions (ai-advice-output.v1) separately from deterministic verdict.",
                listOf("run_id", "analysis_id", "runner_id", "model_id", "output"),
                buildJsonObject {
                    put("run_id", prop("string", "Run ID."))
                    put("analysis_id", prop("string", "64-hex analysis ID."))
                    put("runner_id", prop("string", "Lowercase client slug (e.g. claude-code, qwen-code)."))
                    put("runner_version", prop("string", "Optional client version."))
                    put("model_id", prop("string", "Model slug used to produce the advice."))
                    put("duration_ms", prop("integer", "Optional reasoning duration in ms (0..613000)."))
                    put("output", prop("object", "ai-advice-output.v1 JSON object with grounded evidence_refs."))
                },
            ),
        )
    }

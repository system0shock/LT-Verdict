package io.ltverdict.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun renderAsciiDocReport(
    resultBytes: ByteArray,
    analysisId: String,
): ByteArray {
    val result = Json.parseToJsonElement(resultBytes.decodeToString()).jsonObject
    val evidence = result.objects("evidence")
    val metrics = evidence.filter { it.string("type") == "metric_summary" }
    val resourceSummaries = evidence.filter { it.string("type") == "resource_summary" }
    val windowSummaries = evidence.filter { it.string("type") == "window_policy_summary" }
    val resourceChecks = evidence.filter { it.string("type") == "resource_policy_check" }
    val resourceBindings = evidence.filter { it.string("type") == "resource_binding" }
    return buildString {
        append("= LT Verdict report\n:!webfonts:\n\n")
        append("== Run\n")
        field("Run ID", result["run_id"])
        field("Analysis ID", JsonPrimitive(analysisId))
        append("\n== Status\n")
        field("Run validity", result["run_validity"])
        field("Policy verdict", result["policy_verdict"])
        field("Coverage", (result["analysis_coverage"] as? JsonObject)?.get("status"))
        stageNotice(result)?.let {
            append("Область вердикта\n")
            literal("${it.phrase}. ${it.detail} $STAGE_REFERENCE_NOTE")
        }
        windowShareText(result)?.let {
            append("$WINDOW_SHARE_LABEL\n")
            literal(it)
        }
        metricsSection("Overall metrics", metrics.filter { it.scopeKind() == "overall" })
        metricsSection("Transaction metrics", metrics.filter { it.scopeKind() == "transaction" })
        objectsSection("Policy checks", evidence.filter { it.string("type") == "policy_check" })
        if (resourceSummaries.isNotEmpty() ||
            windowSummaries.isNotEmpty() ||
            resourceChecks.isNotEmpty() ||
            resourceBindings.isNotEmpty()
        ) {
            objectsSection("Resource binding", resourceBindings)
            resourceSummariesSection(resourceSummaries)
            objectsSection("Window policy outcomes", windowSummaries)
            objectsSection("Resource policy checks", resourceChecks)
        }
        listOf(
            "source_summary" to "Source acquisition",
            "diagnostic_summary" to "Diagnostic analysis",
            "correlation_pair" to "Correlations",
            "anomaly_check" to "Anomaly checks",
            "window_metric_summary" to "Window metrics",
        ).forEach { (type, title) ->
            val values = evidence.filter { it.string("type") == type }
            if (values.isNotEmpty()) objectsSection(title, values)
        }
        objectsSection("Findings", result.objects("findings"))
        append("\n== Evidence IDs\n")
        if (evidence.isEmpty()) append("unavailable\n") else evidence.forEach { literal(it["id"]) }
        append("\n== Canonical JSON\n")
        literal(resultBytes.decodeToString())
    }.encodeToByteArray()
}

private fun StringBuilder.resourceSummariesSection(values: List<JsonObject>) {
    append("\n== Resource summaries\n")
    if (values.isEmpty()) {
        append("unavailable\n")
        return
    }
    values.forEach { value ->
        field("Evidence ID", value["id"])
        field("Series ID", value["series_id"])
        field("Metric", value["metric"])
        field("Unit", value["unit"])
        field("Entity", value["entity"])
        field("Role", value["role"])
        field("Aggregation", value["aggregation"])
        field("Window ID", value["window_id"])
        field("From epoch ms", value["from_epoch_ms"])
        field("To epoch ms", value["to_epoch_ms"])
        field("Expected cells", value["expected_cells"])
        field("Observed cells", value["observed_cells"])
        field("Missing cells", value["missing_cells"])
        field("Longest gap cells", value["longest_gap_cells"])
        field("Statistics", value["statistics"])
        field("Reasons", value["reasons"])
    }
}

private fun StringBuilder.metricsSection(
    title: String,
    metrics: List<JsonObject>,
) {
    append("\n== $title\n")
    if (metrics.isEmpty()) {
        append("unavailable\n")
        return
    }
    metrics.forEach { metric ->
        field("Metric ID", metric["id"])
        field("Scope", metric["scope"])
        field("Samples (count)", metric["sample_count"])
        field("Errors (count)", metric["error_count"])
        field("Throughput (requests/s)", metric["throughput_rps"])
        field("Error rate (ratio)", metric["error_rate_ratio"])
        field("Latency (ms)", metric["latency_ms"])
    }
}

private fun StringBuilder.objectsSection(
    title: String,
    values: List<JsonObject>,
) {
    append("\n== $title\n")
    if (values.isEmpty()) append("unavailable\n") else values.forEach(::literal)
}

private fun StringBuilder.field(
    label: String,
    value: JsonElement?,
) {
    append("$label\n")
    literal(value)
}

private fun StringBuilder.literal(value: JsonElement?) =
    literal(
        if (value == null || value is JsonNull) "unavailable" else value.toString(),
    )

private fun StringBuilder.literal(value: String) {
    append("[subs=specialchars]\n----\n$value\n----\n")
}

private fun JsonObject.objects(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.scopeKind(): String? = (this["scope"] as? JsonObject)?.string("kind")

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

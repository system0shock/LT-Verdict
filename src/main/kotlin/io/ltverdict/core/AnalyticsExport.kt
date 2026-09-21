package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class AnalyticsExportFormat(
    val wireName: String,
) {
    HTML("html"),
    ASCIIDOC("asciidoc"),
    CONFLUENCE("confluence"),
    ;

    companion object {
        fun fromWireName(value: String): AnalyticsExportFormat? = entries.firstOrNull { it.wireName == value }
    }
}

internal fun renderRunDynamicsExport(
    dynamics: JsonObject,
    format: AnalyticsExportFormat,
    historyScanTruncated: Boolean = false,
    historyScanLimit: Int? = null,
): ByteArray {
    val truncated = historyScanTruncated || dynamics.booleanOrNull("history_scan_truncated") == true
    val scanLimit = historyScanLimit ?: dynamics.integerOrNull("history_scan_limit")
    require(!truncated || scanLimit != null && scanLimit > 0) { "INVALID_RUN_DYNAMICS_EXPORT" }
    val table = dynamics.exportTable(truncated, scanLimit)
    val rendered =
        when (format) {
            AnalyticsExportFormat.HTML -> table.html()
            AnalyticsExportFormat.ASCIIDOC -> table.asciiDoc()
            AnalyticsExportFormat.CONFLUENCE -> table.confluence()
        }
    return rendered.encodeToByteArray()
}

private fun JsonObject.exportTable(
    historyScanTruncated: Boolean,
    historyScanLimit: Int?,
): ExportTable {
    require(string("schema_version") == "run-dynamics.v1") { "INVALID_RUN_DYNAMICS_EXPORT" }
    val rows = objects("rows")
    val metrics =
        linkedMapOf<String, String>().apply {
            rows.forEach { row ->
                row.objects("metrics").forEach { metric -> putIfAbsent(metric.string("metric"), metric.string("unit")) }
            }
        }
    val headers =
        listOf("Run ID", "Analysis ID", "Date", "Jenkins build", "Commit", "Application version", "Load profile", "Verdict") +
            metrics.flatMap { (metric, unit) -> listOf("$metric ($unit)", "$metric Δ previous", "$metric Δ baseline") }
    val values =
        rows.map { row ->
            val reference = row.objectValue("reference")
            val byMetric = row.objects("metrics").associateBy { it.string("metric") }
            listOf(
                reference.string("run_id"),
                reference.string("analysis_id"),
                row.string("run_date"),
                row.optionalText("jenkins_build"),
                row.optionalText("commit"),
                row.optionalText("application_version"),
                row.optionalText("load_profile"),
                row.string("verdict"),
            ) +
                metrics.keys.flatMap { name ->
                    val metric = byMetric[name]
                    listOf(
                        metric?.value("value", "METRIC_NOT_AVAILABLE") ?: "N/A (METRIC_NOT_AVAILABLE)",
                        metric?.delta("delta_previous", "delta_previous_percent", "previous_reason") ?: "N/A (METRIC_NOT_AVAILABLE)",
                        metric?.delta("delta_baseline", "delta_baseline_percent", "baseline_reason") ?: "N/A (METRIC_NOT_AVAILABLE)",
                    )
                }
        }
    return ExportTable(
        summary =
            "Showing ${values.size} selected rows from ${integer("comparable_count")} comparable local analyses; " +
                "${integer("excluded_incompatible_count")} incompatible analyses excluded.",
        notices =
            buildList {
                add("Deltas refer to the original preceding comparable run and are not recalculated after row selection.")
                if (historyScanTruncated) {
                    add(
                        "Local history scan stopped at configured bounds (up to $historyScanLimit analyses); " +
                            "comparable runs may be omitted, so latest-N is limited to the scanned history.",
                    )
                }
            },
        headers = headers,
        rows = values,
    )
}

private fun JsonObject.value(
    name: String,
    missingReason: String,
): String = primitiveText(name) ?: "N/A ($missingReason)"

private fun JsonObject.delta(
    valueName: String,
    percentName: String,
    reasonName: String,
): String {
    val value = primitiveText(valueName) ?: return "N/A (${primitiveText(reasonName) ?: "UNAVAILABLE"})"
    return primitiveText(percentName)?.let { "$value ($it%)" } ?: value
}

private fun JsonObject.objects(name: String): List<JsonObject> =
    (this[name] as? JsonArray).orEmpty().map { it as? JsonObject ?: throw IllegalArgumentException("INVALID_RUN_DYNAMICS_EXPORT") }

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name] as? JsonObject ?: throw IllegalArgumentException("INVALID_RUN_DYNAMICS_EXPORT")

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("INVALID_RUN_DYNAMICS_EXPORT")

private fun JsonObject.integer(name: String): Int = integerOrNull(name) ?: throw IllegalArgumentException("INVALID_RUN_DYNAMICS_EXPORT")

private fun JsonObject.integerOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.toIntOrNull()

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.content
        ?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

private fun JsonObject.optionalText(name: String): String = primitiveText(name) ?: "N/A"

private fun JsonObject.primitiveText(name: String): String? =
    when (val value = this[name]) {
        null,
        JsonNull,
        -> null

        is JsonPrimitive -> value.content
        else -> throw IllegalArgumentException("INVALID_RUN_DYNAMICS_EXPORT")
    }

private data class ExportTable(
    val summary: String,
    val notices: List<String>,
    val headers: List<String>,
    val rows: List<List<String>>,
) {
    fun html(): String =
        buildString {
            append(
                "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">",
            )
            append("<title>LT Verdict N-run dynamics</title></head><body><main><h1>LT Verdict N-run dynamics</h1><p>")
            append(summary.xml())
            notices.forEach { append("</p><p>").append(it.xml()) }
            append("</p><table><caption>Saved local run comparison</caption><thead><tr>")
            headers.forEach { append("<th scope=\"col\">").append(it.xml()).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { row ->
                append("<tr>")
                row.forEachIndexed { index, value ->
                    if (index ==
                        0
                    ) {
                        append("<th scope=\"row\">").append(value.xml()).append("</th>")
                    } else {
                        append("<td>").append(value.xml()).append("</td>")
                    }
                }
                append("</tr>")
            }
            append("</tbody></table></main></body></html>")
        }

    fun asciiDoc(): String =
        buildString {
            append("= LT Verdict N-run dynamics\n:!webfonts:\n\n")
            append(summary).append("\n\n")
            notices.forEach { append(it).append("\n\n") }
            append("[options=\"header\",cols=\"").append(headers.joinToString(",") { "1" }).append("\"]\n|===\n")
            (listOf(headers) + rows).forEach { row -> row.forEach { asciiDocCell(it) } }
            append("|===\n")
        }

    fun confluence(): String =
        buildString {
            append("<h1>LT Verdict N-run dynamics</h1><p>").append(summary.xml()).append("</p>")
            notices.forEach { append("<p>").append(it.xml()).append("</p>") }
            append("<table><thead><tr>")
            headers.forEach { append("<th>").append(it.xml()).append("</th>") }
            append("</tr></thead><tbody>")
            rows.forEach { row ->
                append("<tr>")
                row.forEach { append("<td>").append(it.xml()).append("</td>") }
                append("</tr>")
            }
            append("</tbody></table>")
        }
}

private fun StringBuilder.asciiDocCell(value: String) {
    val safeValue = JsonPrimitive(value).toString().replace("|", "\\|")
    val longestFence = Regex("-+").findAll(safeValue).maxOfOrNull { it.value.length } ?: 0
    val fence = "-".repeat(maxOf(4, longestFence + 1))
    append("a|\n[subs=specialchars]\n")
        .append(fence)
        .append('\n')
        .append(safeValue)
        .append('\n')
        .append(fence)
        .append('\n')
}

private fun String.xml(): String =
    buildString(length) {
        this@xml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

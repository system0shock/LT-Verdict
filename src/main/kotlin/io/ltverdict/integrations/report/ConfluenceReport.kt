package io.ltverdict.integrations.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal fun renderConfluenceReport(
    resultBytes: ByteArray,
    analysisId: String,
): ByteArray {
    val source = resultBytes.decodeToString(throwOnInvalidSequence = true)
    val result = Json.parseToJsonElement(source).jsonObject
    return buildString {
        append("<h1>LT Verdict report</h1><table><tbody>")
        row("Run ID", result["run_id"])
        row("Analysis ID", JsonPrimitive(analysisId))
        row("Run validity", result["run_validity"])
        row("Policy verdict", result["policy_verdict"])
        row("Coverage", (result["analysis_coverage"] as? JsonObject)?.get("status"))
        append("</tbody></table>")
        section("Evidence", result["evidence"] as? JsonArray)
        section("Findings", result["findings"] as? JsonArray)
        append("<h2>Canonical JSON</h2><pre>").append(source.xml()).append("</pre>")
    }.encodeToByteArray()
}

private fun StringBuilder.row(
    label: String,
    value: JsonElement?,
) {
    append("<tr><th>")
        .append(label.xml())
        .append("</th><td>")
        .append(value.display().xml())
        .append("</td></tr>")
}

private fun StringBuilder.section(
    title: String,
    values: JsonArray?,
) {
    append("<h2>").append(title.xml()).append("</h2>")
    if (values.isNullOrEmpty()) {
        append("<p>unavailable</p>")
    } else {
        values.forEach { append("<pre>").append(it.toString().xml()).append("</pre>") }
    }
}

private fun JsonElement?.display(): String =
    when (this) {
        null,
        JsonNull,
        -> "unavailable"
        is JsonPrimitive -> content
        else -> toString()
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

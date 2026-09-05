package io.ltverdict.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64

internal fun renderHtmlReport(
    resultBytes: ByteArray,
    analysisId: String,
): ByteArray {
    val result = Json.parseToJsonElement(resultBytes.decodeToString()).jsonObject
    val evidence = result.array("evidence")
    val metrics = evidence.filter { it.string("type") == "metric_summary" }
    val checks = evidence.filter { it.string("type") == "policy_check" }
    val html =
        """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'sha256-${styleHash()}'; base-uri 'none'; form-action 'none'"><title>LT Verdict report</title><style>$STYLE</style></head><body><main><h1>LT Verdict report</h1><dl><dt>Run</dt><dd>${result.value(
            "run_id",
        )}</dd><dt>Analysis</dt><dd>${escape(
            analysisId,
        )}</dd><dt>Run validity</dt><dd>${result.value(
            "run_validity",
        )}</dd><dt>Policy verdict</dt><dd>${result.value(
            "policy_verdict",
        )}</dd><dt>Coverage</dt><dd>${result.objectValue(
            "analysis_coverage",
            "status",
        )}</dd></dl><section><h2>Overall and transaction metrics</h2>${if (metrics.isEmpty()) {
            "<p>unavailable</p>"
        } else {
            metrics
                .joinToString(
                    "",
                ) {
                    metric(
                        it,
                    )
                }
        }}</section><section><h2>Policy checks</h2>${list(
            checks,
        )}</section><section><h2>Findings</h2>${list(
            result.array("findings"),
        )}</section><section><h2>Evidence IDs</h2><ul>${evidence.joinToString(
            "",
        ) {
            "<li>${it.value(
                "id",
            )}</li>"
        }}</ul></section><section><h2>Canonical JSON</h2><pre>${escape(
            resultBytes.decodeToString(),
        )}</pre></section></main></body></html>"""
    return html.encodeToByteArray()
}

private fun metric(metric: JsonObject): String {
    val scope = metric["scope"] as? JsonObject
    val label = if (scope?.string("kind") == "transaction") ": ${scope.value("label")}" else ""
    val latency = metric["latency_ms"] as? JsonObject
    return "<article><h3>${metric.value(
        "id",
    )}$label</h3><p>Samples: ${metric.value(
        "sample_count",
    )}; errors: ${metric.value(
        "error_count",
    )}; throughput: ${ratio(
        metric["throughput_rps"],
    )} rps; error rate: ${ratio(
        metric["error_rate_ratio"],
    )}</p><p>Latency (ms): p50 ${latency?.value(
        "p50",
    ) ?: "unavailable"}, p95 ${latency?.value(
        "p95",
    ) ?: "unavailable"}, p99 ${latency?.value("p99") ?: "unavailable"}, max ${latency?.value("max") ?: "unavailable"}</p></article>"
}

private fun list(values: List<JsonObject>): String =
    if (values.isEmpty()) {
        "<p>unavailable</p>"
    } else {
        "<ul>${values.joinToString(
            "",
        ) { "<li>${it.entries.joinToString("; ") { (key, value) -> "${escape(key)}: ${escape(valueText(value))}" }}</li>" }}</ul>"
    }

private fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.content

private fun JsonObject.value(name: String): String = escape(string(name) ?: "unavailable")

private fun JsonObject.objectValue(
    objectName: String,
    valueName: String,
): String = escape((this[objectName] as? JsonObject)?.string(valueName) ?: "unavailable")

private fun ratio(value: Any?): String =
    (value as? JsonObject)?.let {
        "${escape(
            it.string("numerator") ?: "unavailable",
        )} / ${escape(it.string("denominator") ?: "unavailable")}"
    }
        ?: "unavailable"

private fun valueText(value: Any?): String =
    when (value) {
        is JsonPrimitive -> value.content
        else -> value.toString()
    }

private fun escape(value: String): String =
    buildString(value.length) {
        value.forEach {
            append(
                when (it) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> it
                },
            )
        }
    }

private fun styleHash(): String =
    Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(STYLE.encodeToByteArray()),
    )

private const val STYLE =
    "body{font:16px system-ui,sans-serif;margin:auto;max-width:72rem;padding:1rem;color:#172033}" +
        "section{border-top:1px solid #ccd3df;margin-top:1rem}dl{display:grid;grid-template-columns:max-content 1fr;gap:.25rem 1rem}" +
        "dt{font-weight:700}dd{margin:0;overflow-wrap:anywhere}" +
        "pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f5f8;padding:1rem}" +
        "@media print{body{max-width:none;padding:0}pre{font-size:8pt}}"

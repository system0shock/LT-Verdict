package io.ltverdict.cli

import io.ltverdict.core.canonicalJson
import io.ltverdict.report.ErrorGroupsView
import io.ltverdict.report.errorGroupsView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Properties

// Text artifacts of `ltv analyze --out-dir` and `ltv summary`, built from the bytes of analysis-result.v1 only.

private const val NO_VALUE = "n/a"
private const val RATIO_DIGITS = 6

internal fun ltvVersion(): String =
    runCatching {
        Properties()
            .apply { CliArtifactsMarker::class.java.getResourceAsStream("/ltv-version.properties")?.use(::load) }
            .getProperty("version")
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.contains("\${") } ?: "unknown"

private object CliArtifactsMarker

internal fun summaryJson(
    analysisId: String,
    result: ByteArray,
    errorGroups: ByteArray? = null,
): ByteArray {
    val root = parse(result)
    val errors = errorGroupsView(root, errorGroups)?.takeIf { it.rows.isNotEmpty() }
    val metrics =
        root
            .evidence("metric_summary")
            .filter { it["window_id"] == null || it["window_id"] is JsonNull }
    return canonicalJson(
        buildJsonObject {
            put("schema_version", "cli-summary.v1")
            put("run_id", root.text("run_id"))
            put("analysis_id", analysisId)
            put("run_validity", root.text("run_validity"))
            put("policy_verdict", root.text("policy_verdict"))
            put("overall", metrics.firstOrNull { it.scope()?.text("kind") == "overall" }?.let(::metricJson) ?: JsonNull)
            put(
                "transactions",
                JsonArray(
                    metrics
                        .filter { it.scope()?.text("kind") == "transaction" }
                        .map { metric ->
                            val scope = checkNotNull(metric.scope())
                            buildJsonObject {
                                put("group_path", scope["group_path"] ?: JsonArray(emptyList()))
                                put("label", scope.text("label"))
                                put("kind", scope.text("sample_kind"))
                                metricJson(metric).forEach { (key, value) -> put(key, value) }
                            }
                        },
                ),
            )
            put("rules", JsonArray(checks(root).map(::ruleJson)))
            // W2.6 / ADR 0031: the top error groups of the whole run; a run without the breakdown has no such key.
            errors?.let { put("error_groups", errorGroupsJson(it)) }
            // ADR 0030: the windows of a staged run; overall stays the whole run. A run without stages has neither key.
            stageBinding(root)?.let { binding ->
                put("windows", JsonArray(stageWindows(root, binding).map { window -> windowJson(window) }))
                put("excluded_ms", binding["excluded_millis"] ?: JsonNull)
            }
        },
    )
}

internal fun summaryText(
    analysisId: String,
    exitCode: Int,
    result: ByteArray,
    errorGroups: ByteArray? = null,
): ByteArray {
    val root = parse(result)
    val errors = errorGroupsView(root, errorGroups)?.takeIf { it.rows.isNotEmpty() }
    val overall = root.evidence("metric_summary").firstOrNull { it.scope()?.text("kind") == "overall" && it["window_id"] == null }
    val latency = overall?.get("latency_ms") as? JsonObject
    val checks = checks(root)
    val binding = stageBinding(root)
    val text =
        buildString {
            append("LT Verdict summary\n")
            append("run_id: ${root.text("run_id")}\n")
            append("analysis_id: $analysisId\n")
            append("run_validity: ${root.text("run_validity")}\n")
            append("policy_verdict: ${root.text("policy_verdict")}\n")
            append("exit_code: $exitCode\n")
            if (binding != null) {
                append(
                    "scope: steady window (${stageIds(
                        binding,
                    ).joinToString(", ")}), excluded ${valueText(binding["excluded_millis"])} ms\n",
                )
            }
            append(
                "samples: ${valueText(overall?.get("sample_count"))}  errors: ${valueText(overall?.get("error_count"))}  " +
                    "p95_ms: ${valueText(latency?.get("p95"))}  p99_ms: ${valueText(latency?.get("p99"))}" +
                    (if (binding != null) "  whole_run (reference only)" else "") + "\n",
            )
            if (binding != null) {
                stageWindows(root, binding).forEach { window ->
                    val windowLatency = window["latency_ms"] as? JsonObject
                    append(
                        "window[${window.text("window_id")}]: samples: ${valueText(window["sample_count"])}  " +
                            "errors: ${valueText(window["error_count"])}  p95_ms: ${valueText(windowLatency?.get("p95"))}  " +
                            "p99_ms: ${valueText(windowLatency?.get("p99"))}  rps: ${valueText(window["throughput_rps"])}\n",
                    )
                }
            }
            if (checks.isNotEmpty()) {
                val counts = checks.groupingBy { it.text("status") }.eachCount()
                append(
                    "rules: ${checks.size} (PASS ${counts["PASS"] ?: 0}, FAIL ${counts["FAIL"] ?: 0}, " +
                        "NO_VERDICT ${counts["NO_VERDICT"] ?: 0})\n",
                )
            }
            checks.filter { it.text("status") != "PASS" }.forEach { check ->
                val detail = if (check.text("status") == "NO_VERDICT") reasonOf(check) ?: description(check) else description(check)
                append("${check.text("status")} ${checkName(check)}: $detail\n")
            }
            errors?.let { view ->
                append("top errors (whole run, ${view.total} total):\n")
                view.rows.take(SUMMARY_ERROR_GROUPS).forEach { row ->
                    append("  ${row.count} ${row.codeValue ?: NO_VALUE} ${row.messageValue ?: NO_VALUE} [${row.transaction}]\n")
                }
            }
        }
    return text.encodeToByteArray()
}

internal fun junitXml(result: ByteArray): ByteArray {
    val root = parse(result)
    val validity = root.text("run_validity")
    val verdict = root.text("policy_verdict")
    val reasons = ((root["analysis_coverage"] as? JsonObject)?.get("reasons") as? JsonArray).orEmpty().joinToString(",") { valueText(it) }
    val stageScope = stageBinding(root)?.let(::junitScope)
    val gateMessage = "policy_verdict=$verdict run_validity=$validity reasons=$reasons" + (stageScope?.let { " $it" } ?: "")
    // A decided gate (exit 0 or 2) stays green for checks that were not evaluated (missing_transaction=warn): they are skipped.
    val decided = validity == "VALID" && (verdict == "PASS" || verdict == "FAIL")
    val cases =
        buildList {
            add(
                JunitCase(
                    "lt-verdict.gate",
                    "gate",
                    when {
                        validity == "VALID" && (verdict == "PASS" || verdict == "NO_POLICY") -> null
                        validity == "VALID" && verdict == "FAIL" -> "failure" to gateMessage
                        else -> "error" to gateMessage
                    },
                    // A passed gate has no message, so a staged run says its scope in system-out (ADR 0030).
                    stageScope,
                ),
            )
            checks(root).forEach { check ->
                val classname = if (check.text("type") == "policy_check") "lt-verdict.policy" else "lt-verdict.resource-sla"
                add(
                    JunitCase(
                        classname,
                        checkName(check),
                        when (check.text("status")) {
                            "FAIL" -> "failure" to description(check)
                            "NO_VERDICT" ->
                                if (decided) {
                                    "skipped" to "не вычислено: ${reasonOf(check) ?: description(check)}"
                                } else {
                                    "error" to (reasonOf(check) ?: description(check))
                                }
                            else -> null
                        },
                    ),
                )
            }
        }
    val failures = cases.count { it.problem?.first == "failure" }
    val errors = cases.count { it.problem?.first == "error" }
    val skipped = cases.count { it.problem?.first == "skipped" }
    val xml =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<testsuite name=\"lt-verdict\" tests=\"${cases.size}\" failures=\"$failures\" " +
                    "errors=\"$errors\" skipped=\"$skipped\" time=\"0\">\n",
            )
            cases.forEach { case ->
                val head = "<testcase classname=\"${xml(case.classname)}\" name=\"${xml(case.name)}\" time=\"0\""
                val problem = case.problem
                if (problem == null) {
                    val output = case.output
                    if (output != null) append("  $head><system-out>${xml(output)}</system-out></testcase>\n") else append("  $head/>\n")
                } else if (problem.first == "skipped") {
                    append("  $head><skipped message=\"${xml(problem.second)}\"/></testcase>\n")
                } else {
                    val message = xml(problem.second)
                    append("  $head><${problem.first} message=\"$message\">$message</${problem.first}></testcase>\n")
                }
            }
            append("</testsuite>\n")
        }
    return xml.encodeToByteArray()
}

private class JunitCase(
    val classname: String,
    val name: String,
    val problem: Pair<String, String>?,
    val output: String? = null,
)

private fun stageBinding(root: JsonObject): JsonObject? = root.evidence("stage_binding").firstOrNull()

private fun stageIds(binding: JsonObject): List<String> = (binding["evaluated_window_ids"] as? JsonArray).orEmpty().map { valueText(it) }

private fun junitScope(binding: JsonObject): String =
    "scope=steady_window window_ids=${stageIds(binding).joinToString(",")} excluded_ms=${valueText(binding["excluded_millis"])}"

// The window_metric_summary of each evaluated window, in the order of the stage binding.
private fun stageWindows(
    root: JsonObject,
    binding: JsonObject,
): List<JsonObject> {
    val summaries = root.evidence("window_metric_summary")
    return stageIds(binding).mapNotNull { id -> summaries.firstOrNull { it.text("window_id") == id } }
}

private fun windowJson(window: JsonObject): JsonObject =
    buildJsonObject {
        put("id", window.text("window_id"))
        put("from_epoch_ms", window["from_epoch_ms"] ?: JsonNull)
        put("to_epoch_ms", window["to_epoch_ms"] ?: JsonNull)
        metricJson(window).forEach { (key, value) -> put(key, value) }
    }

private const val SUMMARY_ERROR_GROUPS = 5

private fun errorGroupsJson(view: ErrorGroupsView): JsonObject {
    val shown = view.rows.take(SUMMARY_ERROR_GROUPS)
    return buildJsonObject {
        put("scope", "whole_run")
        put("total_error_count", view.total)
        put("other_error_count", view.total - shown.sumOf { it.count })
        put(
            "groups",
            JsonArray(
                shown.map { row ->
                    buildJsonObject {
                        put("group_path", JsonArray(row.groupPath.map(::JsonPrimitive)))
                        put("label", row.label)
                        put("sample_kind", row.sampleKind?.let(::JsonPrimitive) ?: JsonNull)
                        put("response_code", row.codeValue?.let(::JsonPrimitive) ?: JsonNull)
                        put("message", row.messageValue?.let(::JsonPrimitive) ?: JsonNull)
                        put("count", row.count)
                    }
                },
            ),
        )
    }
}

private fun parse(result: ByteArray): JsonObject = Json.parseToJsonElement(result.decodeToString()) as JsonObject

private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: NO_VALUE

private fun JsonObject.scope(): JsonObject? = this["scope"] as? JsonObject

private fun JsonObject.evidence(type: String): List<JsonObject> =
    (this["evidence"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.text("type") == type }

// Business checks and platform SLA checks only: diagnostic resource checks do not decide the verdict.
private fun checks(root: JsonObject): List<JsonObject> =
    (root["evidence"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter {
        val type = it.text("type")
        type == "policy_check" || (type == "resource_policy_check" && it.text("effect") == "sla")
    }

private fun checkName(check: JsonObject): String =
    check.text("rule_id") + (check["window_id"]?.takeUnless { it is JsonNull }?.let { " @ ${valueText(it)}" } ?: "")

private fun reasonOf(check: JsonObject): String? =
    listOf("reason_code", "reason").firstNotNullOfOrNull { key -> (check[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content }

// The operator of a resource rule describes the violation (gt: the value is above the threshold), like in the HTML report.
private fun description(check: JsonObject): String =
    if (check.text("type") == "policy_check") {
        "${check.text(
            "metric",
        )}: observed ${valueText(check["observed"])} (rule ${check.text("operator")} ${valueText(check["threshold"])})"
    } else {
        "series ${check.text("series_id")}, window ${check.text("window_id")} " +
            "(violation ${check.text("operator")} ${valueText(check["threshold"])} ${check.text("unit")})"
    }

private fun metricJson(metric: JsonObject): JsonObject {
    val latency = metric["latency_ms"] as? JsonObject
    return buildJsonObject {
        put("samples", metric["sample_count"] ?: JsonNull)
        put("errors", metric["error_count"] ?: JsonNull)
        put("error_rate", number(metric["error_rate_ratio"]))
        listOf("p50", "p95", "p99", "max").forEach { put(it, latency?.get(it) ?: JsonNull) }
        put("rps", number(metric["throughput_rps"]))
    }
}

private fun ruleJson(check: JsonObject): JsonObject =
    buildJsonObject {
        put("rule_id", check.text("rule_id"))
        put("status", check.text("status"))
        put("operator", check.text("operator"))
        put("threshold", number(check["threshold"]))
        check["window_id"]?.takeUnless { it is JsonNull }?.let { put("window_id", it) }
        reasonOf(check)?.let { put("reason", it) }
        if (check.text("type") == "policy_check") {
            put("metric", check.text("metric"))
            check["observed"]?.let { put("observed", number(it)) }
        } else {
            put("series_id", check.text("series_id"))
            put("unit", check.text("unit"))
        }
    }

private fun decimal(element: JsonElement?): BigDecimal? =
    when (element) {
        is JsonObject -> {
            val numerator = (element["numerator"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
            val denominator = (element["denominator"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
            if (numerator == null || denominator == null || denominator.signum() == 0) {
                null
            } else {
                numerator.divide(denominator, RATIO_DIGITS, RoundingMode.HALF_UP).stripTrailingZeros()
            }
        }
        is JsonPrimitive -> if (element is JsonNull) null else element.content.toBigDecimalOrNull()
        else -> null
    }

private fun number(element: JsonElement?): JsonElement = decimal(element)?.let { JsonPrimitive(it) } ?: JsonNull

private fun valueText(element: JsonElement?): String =
    when (element) {
        null, is JsonNull -> NO_VALUE
        is JsonPrimitive -> if (element.isString) element.content else decimal(element)?.toPlainString() ?: element.content
        is JsonObject -> decimal(element)?.toPlainString() ?: NO_VALUE
        else -> element.toString()
    }

// XML 1.0: characters outside the allowed ranges (and unpaired surrogates) become '?'; whitespace controls become spaces.
private fun xml(value: String): String =
    buildString {
        value.codePoints().forEach { cp ->
            when {
                cp == '&'.code -> append("&amp;")
                cp == '<'.code -> append("&lt;")
                cp == '>'.code -> append("&gt;")
                cp == '"'.code -> append("&quot;")
                cp == '\''.code -> append("&apos;")
                cp == 0x9 || cp == 0xA || cp == 0xD -> append(' ')
                cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF -> appendCodePoint(cp)
                else -> append('?')
            }
        }
    }

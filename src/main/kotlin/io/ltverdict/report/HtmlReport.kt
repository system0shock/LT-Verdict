package io.ltverdict.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

internal fun renderHtmlReport(
    resultBytes: ByteArray,
    analysisId: String,
): ByteArray {
    val result = Json.parseToJsonElement(resultBytes.decodeToString()).jsonObject
    val evidence = result.array("evidence")
    val metrics = evidence.filter { it.string("type") == "metric_summary" }
    val checks = evidence.filter { it.string("type") == "policy_check" }
    val resourceSummaries = evidence.filter { it.string("type") == "resource_summary" }
    val windowSummaries = evidence.filter { it.string("type") == "window_policy_summary" }
    val resourceChecks = evidence.filter { it.string("type") == "resource_policy_check" }
    val resourceBindings = evidence.filter { it.string("type") == "resource_binding" }
    val resourceSections =
        if (resourceSummaries.isEmpty() && windowSummaries.isEmpty() && resourceChecks.isEmpty() && resourceBindings.isEmpty()) {
            ""
        } else {
            "<section lang=\"en\"><h2>Resource binding</h2>${list(
                resourceBindings,
            )}</section><section lang=\"en\"><h2>Resource summaries</h2>${resourceSummariesSection(
                resourceSummaries,
            )}</section><section lang=\"en\"><h2>Window policy outcomes</h2>${list(
                windowSummaries,
            )}</section><section lang=\"en\"><h2>Resource policy checks</h2>${list(resourceChecks)}</section>"
        }
    val diagnosticSections =
        listOf(
            "source_summary" to "Source acquisition",
            "diagnostic_summary" to "Diagnostic analysis",
            "correlation_pair" to "Correlations",
            "anomaly_check" to "Anomaly checks",
            "window_metric_summary" to "Window metrics",
        ).joinToString("") { (type, title) ->
            val values = evidence.filter { it.string("type") == type }
            if (values.isEmpty()) "" else "<section lang=\"en\"><h2>$title</h2>${list(values)}</section>"
        }
    val html =
        """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'sha256-${styleHash()}'; base-uri 'none'; form-action 'none'"><title>LT Verdict report</title><style>$STYLE</style></head><body><main><h1 lang="en">LT Verdict report</h1><p>Отчёт об анализе нагрузочного прогона</p><dl lang="en"><dt>Run</dt><dd>${result.value(
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
        )}</dd></dl>${verdictBlock(result, evidence)}${diagnosticsBlock(result, evidence)}${rulesSection(
            evidence,
        )}${transactionsSection(result, evidence)}${limitationsBlock(
            result,
            evidence,
        )}<section lang="en"><h2>Overall and transaction metrics</h2>${if (metrics.isEmpty()) {
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
        }}</section><section lang="en"><h2>Policy checks</h2>${list(
            checks,
        )}</section>$resourceSections$diagnosticSections<section lang="en"><h2>Findings</h2>${list(
            result.array("findings"),
        )}</section><section lang="en"><h2>Evidence IDs</h2><ul>${evidence.joinToString(
            "",
        ) {
            "<li>${it.value(
                "id",
            )}</li>"
        }}</ul></section><section lang="en"><h2>Canonical JSON</h2><pre>${escape(
            resultBytes.decodeToString(),
        )}</pre></section></main></body></html>"""
    return html.encodeToByteArray()
}

private const val NO_DATA = "нет данных"
private const val DASH = "—"
private const val NBSP = " "
private const val MAX_TRANSACTION_ROWS = 200
private const val MAX_DIGITS = 20
private const val RATIO_SCALE = 40
private const val MAX_EXPONENT = 100
private const val MAX_PRECISION = 400

private val METRIC_WORDS =
    mapOf(
        "response_time_p95_ms" to "p95 отклика",
        "response_time_p99_ms" to "p99 отклика",
        "error_rate_ratio" to "доля ошибок",
        "throughput_rps" to "пропускная способность",
    )

private val STATUS_WORDS =
    mapOf(
        "PASS" to "В норме",
        "FAIL" to "Нарушение",
        "NO_VERDICT" to "Нет вердикта",
        "NO_POLICY" to "Без правил",
        "NOT_CHECKED" to "Не проверялось",
    )

private val SAMPLE_MODE_WORDS =
    mapOf(
        "FULL" to "достаточно",
        "SMALL_SAMPLE" to "малая выборка",
        "INSUFFICIENT" to "недостаточно",
        "NOT_GATED" to "без порога",
    )

private val DIAGNOSTIC_TYPES = setOf("diagnostic_summary", "correlation_pair", "anomaly_check", "trend_summary", "trend_check")

private fun verdictBlock(
    result: JsonObject,
    evidence: List<JsonObject>,
): String {
    val verdict = result.text("policy_verdict")
    val validity = result.text("run_validity")
    val coverage = result.obj("analysis_coverage")
    val business = evidence.filter { it.string("type") == "policy_check" }
    val resource = evidence.filter { it.string("type") == "resource_policy_check" && it.text("effect") == "sla" }
    val rules = business + resource
    val failed = rules.count { it.text("status") == "FAIL" }
    val unresolved = rules.count { it.text("status") == "NO_VERDICT" }
    // With a decided PASS/FAIL, an unresolved rule is a warned skip (missing_transaction=warn): it is not an evaluated check.
    val decided = verdict == "PASS" || verdict == "FAIL"
    val evaluated = if (decided) rules.size - unresolved else rules.size
    val skipped = if (decided && unresolved > 0) ", не вычислено: $unresolved" else ""
    val capacity = result.text("analysis_mode") == "capacity_step" && result.obj("capacity_summary") != null
    val headline =
        when {
            capacity ->
                when (verdict) {
                    "PASS" -> "Ёмкость подтверждена — требуемая нагрузка выдержана"
                    "FAIL" -> "Ёмкость недостаточна — верхняя граница не выше требуемой"
                    "NO_VERDICT" -> "Вердикт по ёмкости не выдан — границы недостаточно"
                    "NO_POLICY" -> "Вердикта нет — не задана требуемая ёмкость или SLA-правила"
                    else -> null
                }
            verdict == "FAIL" -> "Прогон не проходит — нарушено проверок: $failed из $evaluated$skipped"
            verdict == "PASS" -> "Прогон проходит — нарушений нет, проверок: $evaluated$skipped"
            verdict == "NO_POLICY" -> "Вердикта нет — политика не задана"
            verdict == "NO_VERDICT" ->
                when {
                    validity == "INVALID" -> "Вердикт не выдан — файл нагрузки не удалось разобрать"
                    validity == "DEGRADED" -> "Вердикт не выдан — файл нагрузки разобран не полностью"
                    unresolved > 0 -> "Вердикт не выдан — не удалось проверить: $unresolved из ${rules.size}"
                    else -> "Вердикт не выдан — ядро не смогло проверить все правила"
                }
            else -> null
        } ?: "Вердикт без расшифровки в этой версии отчёта"
    val validityWords =
        when (validity) {
            "VALID" -> "данные разобраны полностью"
            "DEGRADED" -> "файл разобран не полностью"
            "INVALID" -> "файл не удалось разобрать"
            else -> DASH
        }
    val coverageStatus = coverage?.text("status")
    val coverageWords =
        when (coverageStatus) {
            "COMPLETE" -> "полное"
            "INCOMPLETE" -> "неполное"
            else -> DASH
        }
    val reasons = LinkedHashSet<String>()
    coverage?.get("reasons").let { (it as? JsonArray).orEmpty() }.forEach {
        (it as? JsonPrimitive)?.takeIf { code -> code.isString }?.let { code ->
            reasons += code.content
        }
    }
    if (validity !=
        "VALID"
    ) {
        evidence.filter { it.string("type") == "diagnostic" }.forEach { item -> item.text("code")?.let { reasons += it } }
    }
    business.filter { it.text("status") == "NO_VERDICT" }.forEach { item -> item.text("reason_code")?.let { reasons += it } }
    resource.filter { it.text("status") == "NO_VERDICT" }.forEach { item -> item.text("reason")?.let { reasons += it } }
    val stages = if (capacity) capacityStages(result) else emptyList()
    if (capacity && verdict == "NO_VERDICT") {
        result.obj("capacity_summary")?.stringList("reasons")?.let { reasons += it }
        stages.forEach { reasons += it.stringList("reasons") }
    }
    val failures =
        when {
            capacity && verdict == "FAIL" -> capacityFailureItems(result, evidence, stages)
            verdict == "FAIL" -> ruleFailureItems(result, evidence, business.filter { it.text("status") == "FAIL" }, resource)
            else -> emptyList()
        }
    val items = failures + reasons.map { "<li><code>${escape(it)}</code>: ${escape(reasonWords(it))}</li>" }
    val reasonList = if (items.isEmpty()) "<p>Причины в результате не указаны.</p>" else "<ul>${items.joinToString("")}</ul>"
    val count =
        when {
            capacity && stages.isEmpty() -> "В результате нет данных по ступеням ёмкости."
            capacity -> capacityCount(stages)
            rules.isEmpty() -> "Проверок правил в результате нет."
            else -> "Нарушено правил: $failed из $evaluated." + if (decided && unresolved > 0) " Не вычислено: $unresolved." else ""
        }
    val bound = if (capacity) capacityBound(result) else ""
    return "<section><h2>Вердикт и причины</h2><p><strong>${escape(headline)}</strong> <code>${escape(verdict ?: DASH)}</code></p>" +
        "<p>$count</p>$bound<p>Валидность прогона: $validityWords (<code>${escape(validity ?: DASH)}</code>).</p>" +
        "<p>Покрытие данных: $coverageWords (<code>${escape(coverageStatus ?: DASH)}</code>).</p>" +
        "<h3>Причины</h3>$reasonList</section>"
}

private fun capacityStages(result: JsonObject): List<JsonObject> =
    (result.obj("capacity_summary")?.get("stages") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun capacityCount(stages: List<JsonObject>): String {
    val failed = stages.count { it.text("verdict") == "FAIL" }
    val confirmed = stages.count { it.text("verdict") == "PASS" }
    return "Нарушено ступеней: $failed из ${stages.size} (подтверждено: $confirmed, не проверено: ${stages.size - failed - confirmed})."
}

private fun capacityBound(result: JsonObject): String {
    val summary = result.obj("capacity_summary") ?: return ""
    val range = "[${summary.text("lower_inclusive") ?: DASH}, ${summary.text("upper_exclusive") ?: DASH})"
    return "<p>Граница ёмкости: ${escape("${summary.text("bound_type") ?: DASH} $range ${summary.text("unit") ?: ""}".trim())}.</p>"
}

private fun capacityFailureItems(
    result: JsonObject,
    evidence: List<JsonObject>,
    stages: List<JsonObject>,
): List<String> {
    val metricsById = evidence.filter { it.string("type") == "metric_summary" }.associateBy { it.text("id") }
    val violations = result.array("findings").filter { it.text("type") == "resource_threshold_violation" }
    val summaries = evidence.filter { it.string("type") == "window_policy_summary" }.associateBy { it.text("id") }
    return stages.filter { it.text("verdict") == "FAIL" }.map { stage ->
        val refs = stage.stringList("evidence_refs").toSet()
        val windows = refs.mapNotNull { summaries[it]?.text("window_id") }.toSet()
        val failed =
            evidence
                .filter { it.text("status") == "FAIL" }
                .filter {
                    when (it.string("type")) {
                        "resource_policy_check" -> it.text("effect") == "sla" && it.text("id") in refs
                        "policy_check" -> it.text("window_id")?.let { window -> window in windows } == true
                        else -> false
                    }
                }.map {
                    val words =
                        if (it.string("type") ==
                            "policy_check"
                        ) {
                            businessFailureText(it, metricsById)
                        } else {
                            resourceFailureText(it, violations)
                        }
                    "<code>${escape(it.text("rule_id") ?: DASH)}</code>: ${escape(words)}"
                }
        "<li>Ступень <code>${escape(stage.text("id") ?: DASH)}</code>: " +
            (if (failed.isEmpty()) "SLA-правила нарушены." else failed.joinToString("; ")) + "</li>"
    }
}

private fun ruleFailureItems(
    result: JsonObject,
    evidence: List<JsonObject>,
    business: List<JsonObject>,
    resource: List<JsonObject>,
): List<String> {
    val metricsById = evidence.filter { it.string("type") == "metric_summary" }.associateBy { it.text("id") }
    val violations = result.array("findings").filter { it.text("type") == "resource_threshold_violation" }
    return business.map { "<li><code>${escape(it.text("rule_id") ?: DASH)}</code>: ${escape(businessFailureText(it, metricsById))}</li>" } +
        resource
            .filter { it.text("status") == "FAIL" }
            .map { "<li><code>${escape(it.text("rule_id") ?: DASH)}</code>: ${escape(resourceFailureText(it, violations))}</li>" }
}

private fun businessFailureText(
    check: JsonObject,
    metricsById: Map<String?, JsonObject>,
): String {
    val metric = check.text("metric")
    val scope = check.obj("scope") ?: check.text("metric_evidence_id")?.let { metricsById[it] }?.obj("scope")
    val (threshold, observed) = valuePair(metric, exactValue(check["threshold"]), exactValue(check["observed"]), true)
    val sign = if (check.text("operator") == "gte") "≥" else "≤"
    val window = check.text("window_id")?.let { " · окно $it" } ?: ""
    return "${scopeText(scope)} · ${metric?.let { METRIC_WORDS[it] ?: it } ?: DASH}$window: $observed при пороге $sign $threshold"
}

// The operator of a resource rule describes the violation (gt: the value is above the threshold), not the passing condition.
private fun resourceFailureText(
    check: JsonObject,
    violations: List<JsonObject>,
): String {
    val own =
        violations.filter {
            it.text("rule_id") == check.text("rule_id") &&
                it.text("window_id") == check.text("window_id") &&
                it.text("evidence_id")?.let { id -> id == check.text("id") } != false
        }
    val first = own.firstOrNull()
    val side = if (check.text("operator") == "gt") "выше" else "ниже"
    val entity = first?.text("entity")?.let { " ($it)" } ?: ""
    val head = "ряд ${check.text("series_id") ?: DASH}$entity · окно ${check.text("window_id") ?: DASH}"
    val limit = "${check.text("threshold") ?: DASH} ${check.text("unit") ?: ""}".trim()
    if (first == null) return "$head: значение $side порога $limit"
    val low = first.text("observed_min") ?: DASH
    val high = first.text("observed_max") ?: DASH
    val range = if (low == high) low else "$low–$high"
    val episodes = if (own.size > 1) "; интервалов нарушения: ${own.size}" else ""
    return "$head: значение $side порога $limit: наблюдалось $range; ячеек подряд: ${first.text("cell_count") ?: DASH}; " +
        "${utcText(first.number("from_epoch_ms"))} – ${utcText(first.number("to_epoch_ms"))}$episodes"
}

private val UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun utcText(epochMillis: BigDecimal?): String =
    epochMillis?.let { runCatching { "${UTC_FORMAT.format(Instant.ofEpochMilli(it.toLong()))} UTC" }.getOrNull() } ?: DASH

private fun failedDiagnosticChecks(evidence: List<JsonObject>): List<JsonObject> =
    evidence.filter { it.string("type") == "resource_policy_check" && it.text("effect") == "diagnostic" && it.text("status") == "FAIL" }

private fun diagnosticsBlock(
    result: JsonObject,
    evidence: List<JsonObject>,
): String {
    val failed = failedDiagnosticChecks(evidence)
    if (failed.isEmpty()) return ""
    val violations = result.array("findings").filter { it.text("type") == "resource_threshold_violation" }
    val items =
        failed.joinToString("") {
            "<li><code>${escape(it.text("rule_id") ?: DASH)}</code>: ${escape(resourceFailureText(it, violations))}</li>"
        }
    return "<section><h2>Диагностика ресурсов</h2><p>Сработали диагностические правила ресурсов. " +
        "Это наблюдения: они не доказывают причину.</p><ul>$items</ul></section>"
}

private fun rulesSection(evidence: List<JsonObject>): String {
    val metricsById = evidence.filter { it.string("type") == "metric_summary" }.associateBy { it.text("id") }
    val checks = evidence.filter { it.string("type") == "policy_check" }
    val resourceNote =
        if (evidence.any { it.string("type") == "resource_policy_check" }) {
            "<p>Правила по ресурсам перечислены ниже в разделе «Resource policy checks».</p>"
        } else {
            ""
        }
    val heads = listOf("Правило", "Область", "Метрика", "Условие", "Порог", "Измерено", "Окно", "Выборка", "Статус", "Причина")
    val body =
        if (checks.isEmpty()) {
            "<p>В результате нет проверок правил по метрикам нагрузки.</p>"
        } else {
            table("Результаты проверки правил", heads, checks.joinToString("") { ruleRow(it, metricsById) })
        }
    return "<section><h2>Правила</h2>$body$resourceNote</section>"
}

private fun ruleRow(
    check: JsonObject,
    metricsById: Map<String?, JsonObject>,
): String {
    val metric = check.text("metric")
    val scope = check.obj("scope") ?: check.text("metric_evidence_id")?.let { metricsById[it] }?.obj("scope")
    val status = check.text("status")
    val (threshold, observed) = valuePair(metric, exactValue(check["threshold"]), exactValue(check["observed"]), status == "FAIL")
    val reason = check.text("reason_code")
    val condition =
        when (check.text("operator")) {
            "lte" -> "не более"
            "gte" -> "не менее"
            else -> DASH
        }
    val cells =
        listOf(
            check.text("rule_id") ?: DASH,
            scopeText(scope),
            metric?.let { METRIC_WORDS[it] ?: it } ?: DASH,
            condition,
            threshold,
            observed,
            check.text("window_id") ?: DASH,
            sampleText(check),
        ).joinToString("") { "<td>${escape(it)}</td>" }
    val reasonCell = if (reason == null) DASH else "<code>${escape(reason)}</code>: ${escape(reasonWords(reason))}"
    return "<tr>$cells${statusCell(status)}<td>$reasonCell</td></tr>"
}

private fun sampleText(check: JsonObject): String {
    val mode = check.text("sample_mode") ?: return DASH
    val count = check.number("sample_count")?.let { formatNumber(it, 0) } ?: DASH
    val limit = check.number(if (mode == "INSUFFICIENT") "sample_floor" else "min_samples")?.let { formatNumber(it, 0) } ?: DASH
    val words = SAMPLE_MODE_WORDS[mode] ?: mode
    return if (mode == "NOT_GATED") "$count · $words" else "$count из $limit · $words"
}

private fun statusCell(status: String?): String {
    val words = status?.let { STATUS_WORDS[it] ?: it } ?: DASH
    val style =
        when (status) {
            "FAIL" -> "st-fail"
            "PASS" -> "st-pass"
            "NO_VERDICT" -> "st-none"
            else -> "st-plain"
        }
    return "<td class=\"$style\">${escape(words)}</td>"
}

private class TransactionRow(
    val path: String,
    val label: String,
    val status: String,
    val samples: BigDecimal,
    val errors: BigDecimal,
    val p99: BigDecimal,
    val cells: List<String>,
)

private fun transactionsSection(
    result: JsonObject,
    evidence: List<JsonObject>,
): String {
    val checks = evidence.filter { it.string("type") == "policy_check" }
    val byMetric = checks.filter { it.text("metric_evidence_id") != null }.groupBy { it.text("metric_evidence_id") }
    val windowChecks =
        checks
            .filter { it.text("metric_evidence_id") == null }
            .groupBy { transactionKey(it.obj("scope")) }
    val noPolicy = result.text("policy_verdict") == "NO_POLICY"
    val missing = BigDecimal.ONE.negate()
    val rows =
        evidence
            .filter { it.string("type") == "metric_summary" && it.obj("scope")?.text("kind") == "transaction" }
            .map { item ->
                val scope = item.obj("scope")
                val related = byMetric[item.text("id")].orEmpty() + windowChecks[transactionKey(scope)].orEmpty()
                val statuses = related.mapNotNull { it.text("status") }
                val status =
                    when {
                        "FAIL" in statuses -> "FAIL"
                        "NO_VERDICT" in statuses -> "NO_VERDICT"
                        "PASS" in statuses -> "PASS"
                        noPolicy -> "NO_POLICY"
                        else -> "NOT_CHECKED"
                    }
                val latency = item.obj("latency_ms")
                val latencyMetric = "response_time_p95_ms"
                val label = (scope?.text("label") ?: DASH) + (item.text("window_id")?.let { " (окно $it)" } ?: "")
                TransactionRow(
                    path = scope?.stringList("group_path").orEmpty().joinToString(" / "),
                    label = label,
                    status = status,
                    samples = item.number("sample_count") ?: missing,
                    errors = item.number("error_count") ?: missing,
                    p99 = latency?.number("p99") ?: missing,
                    cells =
                        listOf(
                            item.number("sample_count")?.let { formatNumber(it, 0) } ?: NO_DATA,
                            item.number("error_count")?.let { formatNumber(it, 0) } ?: NO_DATA,
                            formatMetric("error_rate_ratio", exactValue(item["error_rate_ratio"]), 2),
                            formatMetric(latencyMetric, latency?.number("p50"), 2),
                            formatMetric(latencyMetric, latency?.number("p95"), 2),
                            formatMetric(latencyMetric, latency?.number("p99"), 2),
                            formatMetric("throughput_rps", exactValue(item["throughput_rps"]), 2),
                        ),
                )
            }.sortedWith(
                compareBy<TransactionRow> { it.status != "FAIL" }
                    .thenByDescending { it.errors }
                    .thenByDescending { it.p99 }
                    .thenByDescending { it.samples }
                    .thenBy { it.path }
                    .thenBy { it.label },
            )
    val heads = listOf("Транзакция", "Выборка", "Ошибки", "Доля ошибок", "p50", "p95", "p99", "RPS", "Статус")
    val body =
        if (rows.isEmpty()) {
            "<p>В результате нет метрик по транзакциям.</p>"
        } else {
            val shown =
                rows.take(MAX_TRANSACTION_ROWS).joinToString("") { row ->
                    val name = if (row.path.isEmpty()) row.label else "${row.path} / ${row.label}"
                    "<tr><td>${escape(name)}</td>${row.cells.joinToString("") { "<td>${escape(it)}</td>" }}${statusCell(row.status)}</tr>"
                }
            val hidden = rows.size - MAX_TRANSACTION_ROWS
            val rest =
                if (hidden > 0) {
                    "<tr><td colspan=\"${heads.size}\">и ещё $hidden — полный список в разделе «Overall and transaction metrics» ниже</td></tr>"
                } else {
                    ""
                }
            table("Метрики транзакций", heads, shown + rest) +
                "<p>Порядок: сначала нарушения, затем по числу ошибок, p99 и выборке; не более $MAX_TRANSACTION_ROWS строк.</p>"
        }
    return "<section><h2>Транзакции</h2>$body</section>"
}

// Windowed checks carry the full transaction scope instead of a metric reference; null means "not a transaction".
private fun transactionKey(scope: JsonObject?): List<Any?>? =
    if (scope?.text("kind") ==
        "transaction"
    ) {
        listOf(scope.text("label"), scope.text("sample_kind"), scope.stringList("group_path"))
    } else {
        null
    }

private fun limitationsBlock(
    result: JsonObject,
    evidence: List<JsonObject>,
): String {
    val items = mutableListOf<String>()
    if (result.text("policy_verdict") == "NO_POLICY") {
        items += "Правила не заданы: пороги не проверялись, вердикта по ним нет."
    }
    if (result.obj("analysis_coverage")?.text("status") == "INCOMPLETE") {
        items += "Покрытие данных неполное: часть данных могла не попасть в анализ. Причины перечислены в блоке «Вердикт и причины»."
    }
    if (result.text("run_validity") == "DEGRADED" || result.text("run_validity") == "INVALID") {
        items += "Файл нагрузки разобран не полностью или не разобран: метрики и вердикт могут быть неполными."
    }
    if (evidence.any { it.string("type") == "policy_check" && it.text("sample_mode") == "SMALL_SAMPLE" }) {
        items += "Часть правил проверена на малой выборке: результат рассчитан, но запросов меньше рекомендуемого минимума."
    }
    if (failedDiagnosticChecks(evidence).isNotEmpty()) {
        items += "Сработала диагностика ресурсов (блок «Диагностика ресурсов»): она показывает наблюдения и не доказывает причину."
    }
    if (evidence.any { it.string("type") in DIAGNOSTIC_TYPES }) {
        items += "Диагностика (корреляции, аномалии, тренды) показывает наблюдения и не доказывает причину."
    }
    val body =
        if (items.isEmpty()) {
            "<p>Ограничений, отмеченных в результате, нет.</p>"
        } else {
            "<ul>${items.joinToString("") { "<li>${escape(it)}</li>" }}</ul>"
        }
    return "<section><h2>Ограничения</h2>$body</section>"
}

private fun table(
    label: String,
    heads: List<String>,
    rows: String,
): String =
    "<div class=\"table-wrap\" tabindex=\"0\" role=\"region\" aria-label=\"${escape(label)}\"><table><thead><tr>" +
        heads.joinToString("") { "<th scope=\"col\">${escape(it)}</th>" } +
        "</tr></thead><tbody>$rows</tbody></table></div>"

private fun scopeText(scope: JsonObject?): String =
    when {
        scope == null -> "область не указана"
        scope.text("kind") == "overall" -> "весь прогон"
        else ->
            (scope.stringList("group_path") + listOfNotNull(scope.text("label")))
                .joinToString(" / ")
                .ifEmpty { "область не указана" }
    }

private fun exactValue(element: JsonElement?): BigDecimal? =
    when (element) {
        null, is JsonNull -> null
        is JsonObject -> {
            val numerator = element.number("numerator")
            val denominator = element.number("denominator")
            if (numerator == null || denominator == null || denominator.signum() == 0) {
                null
            } else {
                numerator.divide(denominator, RATIO_SCALE, RoundingMode.HALF_UP)
            }
        }
        is JsonPrimitive -> if (element.isString) null else parseNumber(element.content)
        else -> null
    }

// Rounding must not turn a violation into equality: digits are added (2, 4, 8, 16, 20) until the two strings differ.
private fun valuePair(
    metric: String?,
    threshold: BigDecimal?,
    observed: BigDecimal?,
    failed: Boolean,
): Pair<String, String> {
    var digits = 2
    var thresholdText = formatMetric(metric, threshold, digits)
    var observedText = formatMetric(metric, observed, digits)
    while (threshold != null && observed != null && thresholdText == observedText && digits < MAX_DIGITS) {
        digits = minOf(digits * 2, MAX_DIGITS)
        thresholdText = formatMetric(metric, threshold, digits)
        observedText = formatMetric(metric, observed, digits)
    }
    val tie =
        if (failed &&
            threshold != null &&
            observed != null &&
            thresholdText == observedText
        ) {
            " (нарушение меньше точности отображения)"
        } else {
            ""
        }
    return thresholdText to (observedText + tie)
}

private fun formatMetric(
    metric: String?,
    value: BigDecimal?,
    digits: Int,
): String =
    when {
        value == null -> NO_DATA
        metric == "error_rate_ratio" -> "${formatNumber(value.multiply(BigDecimal(100)), digits)}$NBSP%"
        metric == "throughput_rps" -> "${formatNumber(value, digits)}${NBSP}RPS"
        metric == "response_time_p95_ms" || metric == "response_time_p99_ms" -> "${formatNumber(value, digits)}${NBSP}мс"
        else -> formatNumber(value, digits)
    }

// Fixed formatting independent of the JVM locale: no-break space between thousands, decimal comma, no trailing zeros.
private fun formatNumber(
    value: BigDecimal,
    digits: Int,
): String {
    val plain = value.setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    val sign = if (plain.startsWith("-")) "-" else ""
    val unsigned = plain.removePrefix("-")
    val integer =
        unsigned
            .substringBefore('.')
            .reversed()
            .chunked(3)
            .joinToString(NBSP)
            .reversed()
    val fraction = unsigned.substringAfter('.', "")
    return sign + integer + if (fraction.isEmpty()) "" else ",$fraction"
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

private fun resourceSummariesSection(values: List<JsonObject>): String =
    if (values.isEmpty()) {
        "<p>unavailable</p>"
    } else {
        values.joinToString("") { value ->
            "<article><h3>${value.value(
                "id",
            )}</h3><p>Series: ${value.value(
                "series_id",
            )}; metric: ${value.value(
                "metric",
            )}; unit: ${value.value(
                "unit",
            )}; entity: ${value.value(
                "entity",
            )}; role: ${value.value(
                "role",
            )}; aggregation: ${value.value(
                "aggregation",
            )}</p><p>Window: ${value.value(
                "window_id",
            )} [${value.value(
                "from_epoch_ms",
            )}, ${value.value(
                "to_epoch_ms",
            )}); cells: expected ${value.value(
                "expected_cells",
            )}, observed ${value.value(
                "observed_cells",
            )}, missing ${value.value(
                "missing_cells",
            )}, longest gap ${value.value(
                "longest_gap_cells",
            )}; Statistics: ${value.resourceValue("statistics")}; reasons: ${value.resourceValue("reasons")}</p></article>"
        }
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

// Like string(), but an explicit JSON null is a missing value rather than the text "null".
private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { value -> value is JsonNull }?.content }

private fun JsonObject.number(name: String): BigDecimal? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.let(::parseNumber)

// A compact token such as 1e100000000 must not be expanded when it is rounded and printed: out-of-range values count as no data.
private fun parseNumber(token: String): BigDecimal? =
    token.toBigDecimalOrNull()?.takeIf { it.scale() in -MAX_EXPONENT..MAX_EXPONENT && it.precision() <= MAX_PRECISION }

private fun JsonObject.value(name: String): String = escape(string(name) ?: "unavailable")

private fun JsonObject.resourceValue(name: String): String =
    escape(this[name]?.takeUnless { it is JsonNull }?.let(::valueText) ?: "unavailable")

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
        "code,li,h3,p{overflow-wrap:anywhere}.table-wrap{overflow-x:auto}.table-wrap:focus-visible{outline:2px solid #172033}" +
        "table{border-collapse:collapse;width:100%}" +
        "th,td{border:1px solid #ccd3df;padding:.25rem .5rem;text-align:left;vertical-align:top}td:first-child{overflow-wrap:anywhere}" +
        "th{background:#f3f5f8}.st-fail{color:#b00020;font-weight:700}.st-pass{color:#1b6e3a;font-weight:700}" +
        ".st-none{color:#8a5a00;font-weight:700}" +
        "@media print{body{max-width:none;padding:0}pre{font-size:8pt}}"

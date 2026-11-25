package io.ltverdict.report

import io.ltverdict.core.cleanErrorText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal

// W3.7 PR 4 (ADR 0029, ruling H1): deterministic rendering of the `incidents` section of analysis-result.v1 in HtmlReport.
// Returns "" when `incidents` is absent or JSON null so pre-W3.7 results remain byte-identical.

private const val MAX_TEXT_CHARS = 400
private const val MAX_SCOPE_CHARS = 300
private const val MAX_ID_CHARS = 300

private const val SECTION_TITLE = "Инциденты"
private const val NOT_EVALUATED_RUN_INVALID = "Синтез инцидентов не выполнялся: прогон непригоден"
private const val NOT_EVALUATED_GENERIC = "Синтез инцидентов не выполнялся"
private const val EMPTY_EVALUATED = "Инцидентов не найдено; пустой список не означает PASS."
private const val SECTION_LEAD = "Показаны совпадения находок по времени и области; инциденты не меняют вердикт."
private const val INTERVAL_UNKNOWN_TEXT = "время нарушения не определено"
private const val REFS_TRUNCATED_TEXT = "часть идентификаторов не показана (лимит списка)"
private const val NEGATIVE_EMPTY_TEXT = "Отрицательных свидетельств нет."
private const val NEXT_EMPTY_TEXT = "Следующих проверок нет."

private val INCIDENT_HEADS =
    listOf(
        "Ранг",
        "Приоритет",
        "Инцидент",
        "Область и окно",
        "Интервал",
        "Находки и данные",
        "Проверки",
    )

private val PRIORITY_WORDS =
    mapOf(
        "HIGH" to "Высокий",
        "MEDIUM" to "Средний",
        "LOW" to "Низкий",
    )

private val BASIS_WORDS =
    mapOf(
        "WINDOW" to "окно",
        "FINDINGS" to "находки",
    )

private val COINCIDENCE_WORDS =
    mapOf(
        "SAME_WINDOW" to "одно окно",
        "INTERVAL_OVERLAP" to "пересечение интервалов",
    )

private val NEGATIVE_OUTCOME_WORDS =
    mapOf(
        "NOT_CONFIRMED" to "не подтверждено",
        "NOT_EVALUATED" to "не проверялось",
    )

/** Every constant Russian text rendered by this section; tested against CAUSAL_WORDING of tools/verify_slice0.py. */
internal val INCIDENT_REPORT_TEXTS: List<String> =
    listOf(
        SECTION_TITLE,
        NOT_EVALUATED_RUN_INVALID,
        NOT_EVALUATED_GENERIC,
        EMPTY_EVALUATED,
        SECTION_LEAD,
        INTERVAL_UNKNOWN_TEXT,
        REFS_TRUNCATED_TEXT,
        NEGATIVE_EMPTY_TEXT,
        NEXT_EMPTY_TEXT,
        "Найдено:",
        "сохранено в отчёте:",
        "Не сохранено сверх лимита 64:",
        "в обзоре",
        "весь прогон",
        "область не указана",
        "Окно",
        "Находок",
        "Идентификаторы находок",
        "Идентификаторы данных",
        "Совпадения",
        "Отрицательные свидетельства",
        "Следующие проверки",
        "данные",
    ) +
        INCIDENT_HEADS +
        PRIORITY_WORDS.values +
        BASIS_WORDS.values +
        COINCIDENCE_WORDS.values +
        NEGATIVE_OUTCOME_WORDS.values

internal fun incidentsSection(result: JsonObject): String {
    val incidents = result["incidents"] as? JsonObject ?: return ""
    val status = incidents.text("status")
    if (status == "NOT_EVALUATED") {
        val reasonCode = incidents.text("reason_code")
        val body =
            when {
                reasonCode == "RUN_NOT_VALID" -> "$NOT_EVALUATED_RUN_INVALID (<code>RUN_NOT_VALID</code>)."
                reasonCode != null -> "$NOT_EVALUATED_GENERIC (<code>${safe(reasonCode, MAX_ID_CHARS)}</code>)."
                else -> "$NOT_EVALUATED_GENERIC."
            }
        return "<section><h2>$SECTION_TITLE</h2><p>$body</p></section>"
    }
    val items = incidents.array("items")
    if (items.isEmpty()) {
        return "<section><h2>$SECTION_TITLE</h2><p>$EMPTY_EVALUATED</p></section>"
    }
    val totalCount = incidents.int("total_count") ?: items.size
    val omittedCount = incidents.int("omitted_count") ?: 0
    val omittedNote = if (omittedCount > 0) " Не сохранено сверх лимита 64: $omittedCount." else ""
    val lead =
        "<p>$SECTION_LEAD Найдено: $totalCount, сохранено в отчёте: ${items.size}.$omittedNote</p>"
    val rows = items.joinToString("", transform = ::incidentRow)
    return "<section><h2>$SECTION_TITLE</h2>$lead${table(SECTION_TITLE, INCIDENT_HEADS, rows)}</section>"
}

private fun incidentRow(item: JsonObject): String {
    val rank = item.int("rank")?.toString() ?: DASH
    val inOverview = item.bool("in_overview") == true
    val rankCell = if (inOverview) "$rank (в обзоре)" else rank

    val priority = item.text("priority")
    val priorityCell =
        when (priority) {
            "HIGH" -> "<span class=\"st-fail\">${PRIORITY_WORDS.getValue("HIGH")}</span>"
            "MEDIUM" -> "<span class=\"st-none\">${PRIORITY_WORDS.getValue("MEDIUM")}</span>"
            "LOW" -> PRIORITY_WORDS.getValue("LOW")
            null -> DASH
            else -> safe(priority, MAX_ID_CHARS)
        }

    val title = safe(item.text("title"), MAX_TEXT_CHARS)
    val summary = safe(item.text("summary"), MAX_TEXT_CHARS)
    val id = safe(item.text("id"), MAX_ID_CHARS)
    val incidentCell = "<strong>$title</strong><br>$summary<br><code>$id</code>"

    val scopeText = scopeCellText(item.obj("scope"))
    val windowId = item.text("window_id")
    val windowText = if (windowId != null) "<code>${safe(windowId, MAX_ID_CHARS)}</code>" else DASH
    val scopeCell = "$scopeText<br>Окно: $windowText"

    val intervalCell = intervalCellText(item.text("interval_basis"), item.obj("interval"))

    val findingCount = item.int("finding_count")?.toString() ?: DASH
    val findingTypes = item.stringList("finding_types")
    val typesSuffix =
        if (findingTypes.isEmpty()) {
            ""
        } else {
            " (${findingTypes.joinToString(", ") { "<code>${safe(it, MAX_ID_CHARS)}</code>" }})"
        }
    val findingIds = item.stringList("finding_ids")
    val evidenceIds = item.stringList("evidence_ids")
    val findingIdsHtml =
        if (findingIds.isEmpty()) DASH else findingIds.joinToString(", ") { "<code>${safe(it, MAX_ID_CHARS)}</code>" }
    val evidenceIdsHtml =
        if (evidenceIds.isEmpty()) DASH else evidenceIds.joinToString(", ") { "<code>${safe(it, MAX_ID_CHARS)}</code>" }
    val truncatedHtml = if (item.bool("refs_truncated") == true) "<br>$REFS_TRUNCATED_TEXT" else ""
    val coincident = item.array("coincident_with")
    val coincidentHtml =
        if (coincident.isEmpty()) {
            ""
        } else {
            val links =
                coincident.joinToString("; ") { link ->
                    val targetId = safe(link.text("incident_id"), MAX_ID_CHARS)
                    val basis = link.text("basis")
                    val basisWord = COINCIDENCE_WORDS[basis] ?: safe(basis, MAX_ID_CHARS)
                    "<code>$targetId</code> ($basisWord)"
                }
            "<br>Совпадения: $links"
        }
    val refsCell =
        "Находок: $findingCount$typesSuffix" +
            "<br>Идентификаторы находок: $findingIdsHtml" +
            "<br>Идентификаторы данных: $evidenceIdsHtml" +
            truncatedHtml +
            coincidentHtml

    val checksCell = negativeDetails(item.array("negative_evidence")) + nextChecksDetails(item.array("next_checks"))

    val cells = listOf(rankCell, priorityCell, incidentCell, scopeCell, intervalCell, refsCell, checksCell)
    return "<tr>${cells.joinToString("") { "<td>$it</td>" }}</tr>"
}

private fun scopeCellText(scope: JsonObject?): String {
    if (scope == null) return "область не указана"
    return when (scope.text("kind")) {
        "overall" -> "весь прогон"
        "transaction" -> {
            val parts = scope.stringList("group_path") + listOfNotNull(scope.text("label"))
            val joined = parts.joinToString(" / ")
            if (joined.isEmpty()) "область не указана" else safe(joined, MAX_SCOPE_CHARS)
        }
        "entity" -> safe(scope.text("entity"), MAX_SCOPE_CHARS)
        else -> "область не указана"
    }
}

private fun intervalCellText(
    basis: String?,
    interval: JsonObject?,
): String {
    if (basis == "UNKNOWN" || interval == null) return INTERVAL_UNKNOWN_TEXT
    val from = utcText(interval.number("from_epoch_ms"))
    val to = utcText(interval.number("to_epoch_ms"))
    val basisSuffix =
        when (basis) {
            "WINDOW" -> " (${BASIS_WORDS.getValue("WINDOW")})"
            "FINDINGS" -> " (${BASIS_WORDS.getValue("FINDINGS")})"
            null -> ""
            else -> " (${safe(basis, MAX_ID_CHARS)})"
        }
    return "$from – $to$basisSuffix"
}

private fun negativeDetails(entries: List<JsonObject>): String {
    val body =
        if (entries.isEmpty()) {
            "<p>$NEGATIVE_EMPTY_TEXT</p>"
        } else {
            "<ul>" +
                entries.joinToString("") { entry ->
                    val check = safe(entry.text("check"), MAX_ID_CHARS)
                    val outcome = entry.text("outcome")
                    val outcomeWord = NEGATIVE_OUTCOME_WORDS[outcome] ?: safe(outcome, MAX_ID_CHARS)
                    val reason = entry.text("reason_code")?.let { " (<code>${safe(it, MAX_ID_CHARS)}</code>)" }.orEmpty()
                    val text = safe(entry.text("text"), MAX_TEXT_CHARS)
                    val evIds = entry.stringList("evidence_ids")
                    val evSuffix =
                        if (evIds.isEmpty()) {
                            ""
                        } else {
                            " · данные: ${evIds.joinToString(", ") { "<code>${safe(it, MAX_ID_CHARS)}</code>" }}"
                        }
                    "<li><code>$check</code> · $outcomeWord$reason: $text$evSuffix</li>"
                } +
                "</ul>"
        }
    return "<details><summary>Отрицательные свидетельства (${entries.size})</summary>$body</details>"
}

private fun nextChecksDetails(entries: List<JsonObject>): String {
    val body =
        if (entries.isEmpty()) {
            "<p>$NEXT_EMPTY_TEXT</p>"
        } else {
            "<ul>" +
                entries.joinToString("") { entry ->
                    val check = safe(entry.text("check"), MAX_ID_CHARS)
                    val text = safe(entry.text("text"), MAX_TEXT_CHARS)
                    val evIds = entry.stringList("evidence_ids")
                    val evSuffix =
                        if (evIds.isEmpty()) {
                            ""
                        } else {
                            " · данные: ${evIds.joinToString(", ") { "<code>${safe(it, MAX_ID_CHARS)}</code>" }}"
                        }
                    "<li><code>$check</code>: $text$evSuffix</li>"
                } +
                "</ul>"
        }
    return "<details><summary>Следующие проверки (${entries.size})</summary>$body</details>"
}

private fun safe(
    raw: String?,
    maxCodePoints: Int,
): String {
    if (raw == null) return DASH
    val cleaned = cleanErrorText(raw, maxCodePoints).first ?: return DASH
    return escape(cleaned)
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.intOrNull

private fun JsonObject.bool(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.booleanOrNull

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

private fun JsonObject.stringList(name: String): List<String> =
    (this[name] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { value -> value is JsonNull }?.content }

private fun JsonObject.number(name: String): BigDecimal? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.content?.let(::parseDecimal)

private fun parseDecimal(token: String): BigDecimal? =
    token.toBigDecimalOrNull()?.takeIf { it.scale() in -100..100 && it.precision() <= 400 }

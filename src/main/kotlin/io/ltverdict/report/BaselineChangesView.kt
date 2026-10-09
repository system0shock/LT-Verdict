package io.ltverdict.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// W2.3: the words and rows of the section "Изменения относительно baseline", made at the moment of rendering from the JSON
// `baseline_comparison` of the CLI (cli/CliBaseline.kt). One reader for the HTML report, AsciiDoc, Confluence and summary.txt. Every
// text of the comparison is untrusted (window ids come from the result): control characters are removed and length is bounded here,
// markup is escaped by each renderer.

internal const val BASELINE_CHANGES_TITLE = "Изменения относительно baseline"

private const val MAX_TEXT_CHARS = 200
private const val NONE_VALUE = "—"

private val HEADS = listOf("Показатель", "Baseline", "Текущий", "Дельта", "Дельта, %", "Статус")

internal class BaselineChangesTable(
    val caption: String,
    val heads: List<String>,
    val rows: List<List<String>>,
)

internal class BaselineChangesView(
    val notes: List<String>,
    val tables: List<BaselineChangesTable>,
    /** The same facts as lines of summary.txt. */
    val lines: List<String>,
)

/** The section of a comparison; null when the report has no baseline. */
internal fun baselineChangesView(comparison: JsonObject?): BaselineChangesView? {
    if (comparison == null) return null
    val reference = comparison["baseline"] as? JsonObject
    val runId = clean(reference?.text("run_id"))
    val analysisId = clean(reference?.text("analysis_id"))
    val scope = comparison.text("scope") ?: "whole_run"
    val confirmed = comparison.text("comparability") == "USER_CONFIRMED"
    val notes = mutableListOf("Baseline: прогон $runId, анализ $analysisId.")
    notes +=
        if (confirmed) {
            "Условия сопоставимости подтверждены пользователем."
        } else {
            "Условия сопоставимости не подтверждены: дельты описательные, значимость не оценена; на вердикт они не влияют."
        }
    notes +=
        if (scope == "steady_window") {
            "Дельты по окнам steady из window_metric_summary; метрики «весь прогон» здесь не используются."
        } else {
            "Дельты за весь прогон."
        }
    (comparison["warnings"] as? JsonArray).orEmpty().forEach { notes += warningText(clean((it as? JsonPrimitive)?.content)) }
    val tables = mutableListOf<BaselineChangesTable>()
    val lines =
        mutableListOf(
            "baseline: run_id=$runId analysis_id=$analysisId comparability=${if (confirmed) "USER_CONFIRMED" else "UNCONFIRMED"} scope=$scope",
        )
    if (scope == "steady_window") {
        (comparison["windows"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { window ->
            windowView(window, notes, tables, lines)
        }
    } else {
        wholeRunView(comparison, notes, tables, lines)
    }
    return BaselineChangesView(notes, tables, lines)
}

private fun wholeRunView(
    comparison: JsonObject,
    notes: MutableList<String>,
    tables: MutableList<BaselineChangesTable>,
    lines: MutableList<String>,
) {
    val rows = (comparison["metrics"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    // The core gives MISSING_METRIC before INCOMPATIBLE_METRIC_DEFINITION, so one such row is enough to know the pair is not comparable.
    val incompatible = rows.any { it.text("reason") == "INCOMPATIBLE_METRIC_DEFINITION" }
    if (incompatible || rows.isEmpty()) {
        val why = reasonText("INCOMPATIBLE_METRIC_DEFINITION")
        notes += "Сравнение невозможно: $why."
        lines += "baseline: not comparable: $why"
        return
    }
    val cells = rows.map(::rowCells)
    tables += BaselineChangesTable("Весь прогон", HEADS, cells)
    cells.forEach { lines += lineOf(it, null) }
}

private fun windowView(
    window: JsonObject,
    notes: MutableList<String>,
    tables: MutableList<BaselineChangesTable>,
    lines: MutableList<String>,
) {
    val id = clean(window.text("window_id"))
    if (window.text("status") == "NOT_EVALUATED") {
        val why = (window["reasons"] as? JsonArray).orEmpty().joinToString("; ") { reasonText(clean((it as? JsonPrimitive)?.content)) }
        notes += "Окно $id: сравнение невозможно: $why."
        lines += "baseline: window $id not comparable: $why"
        return
    }
    notes +=
        "Окно $id: сэмплов baseline ${count(window, "baseline_sample_count")}, текущий ${count(window, "current_sample_count")}; " +
        "длительность baseline ${count(window, "baseline_duration_ms")} мс, текущий ${count(window, "current_duration_ms")} мс. " +
        "Порог заметного изменения: ${clean(
            window.text("min_change_percent"),
        )} % (доля ошибок: ${clean(window.text("min_error_rate_delta"))} абсолютной разницы)."
    val cells = (window["metrics"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map(::rowCells)
    tables += BaselineChangesTable("Окно $id", HEADS, cells)
    cells.forEach { lines += lineOf(it, id) }
}

private fun rowCells(row: JsonObject): List<String> {
    val metric = clean(row.text("metric"))
    val unit = clean(row.text("unit"))
    return listOf(
        if (unit.isEmpty()) metric else "$metric ($unit)",
        value(row, "baseline"),
        value(row, "current"),
        value(row, "delta"),
        row.text("delta_percent")?.let { "${clean(it)} %" } ?: NONE_VALUE,
        statusText(row),
    )
}

private fun lineOf(
    cells: List<String>,
    windowId: String?,
): String {
    val metric = cells[0].substringBefore(" (")
    val percent = if (cells[4] == NONE_VALUE) "" else " (${cells[4]})"
    return "  ${windowId?.let { "[$it] " } ?: ""}$metric: ${cells[1]} -> ${cells[2]}, delta ${cells[3]}$percent ${cells[5]}".trimEnd()
}

private fun statusText(row: JsonObject): String {
    val reason = row.text("reason")
    val status = row.text("status")
    if (status == null) {
        return when {
            reason == "INCOMPATIBLE_METRIC_DEFINITION" -> "не сопоставимо"
            reason == "MISSING_METRIC" -> "нет данных"
            row.text("percent_reason") == "ZERO_BASELINE" -> "описательно, baseline равен нулю"
            else -> "описательно"
        }
    }
    val extra =
        when (reason) {
            null, "MISSING_METRIC" -> null
            "CONDITIONS_UNCONFIRMED" -> "условия не подтверждены"
            "ZERO_BASELINE" -> "baseline равен нулю"
            "EMPTY_WINDOW" -> "пустое окно"
            else -> clean(reason)
        }
    return statusWords(status) + (extra?.let { ", $it" } ?: "")
}

private fun statusWords(status: String): String =
    when (status) {
        "DESCRIPTIVE" -> "описательно"
        "NO_MATERIAL_CHANGE" -> "без заметных изменений"
        "CANDIDATE" -> "материальная дельта, значимость не оценена"
        "INSUFFICIENT_DATA" -> "недостаточно данных"
        else -> clean(status)
    }

private fun warningText(code: String): String =
    when (code) {
        "BASELINE_NOT_PASS" -> "Вердикт baseline не PASS (в том числе когда политика не задана)."
        "POLICY_DIFFERS" -> "Политики baseline и текущего анализа различаются."
        "BASELINE_SMALL_SAMPLE" -> "Baseline построен на малой выборке."
        "BASELINE_IS_CURRENT_ANALYSIS" -> "Baseline совпадает с текущим анализом."
        "BASELINE_IS_CURRENT_RUN" -> "Baseline взят из того же прогона, что и текущий анализ."
        else -> "Предупреждение: $code."
    }

private fun reasonText(code: String): String =
    when (code) {
        "INCOMPATIBLE_METRIC_DEFINITION" -> "у анализов разные условия обработки данных или разное объявление стадий"
        "BASELINE_WINDOW_NOT_FOUND" -> "в baseline нет окна с таким id"
        "CURRENT_WINDOW_NOT_FOUND" -> "в текущем анализе нет окна с таким id"
        else -> code
    }

private fun value(
    row: JsonObject,
    name: String,
): String = row.text(name)?.let(::clean) ?: NONE_VALUE

private fun count(
    window: JsonObject,
    name: String,
): String = window.text(name)?.let(::clean) ?: NONE_VALUE

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun clean(value: String?): String =
    (value ?: NONE_VALUE)
        .map {
            if (it.isISOControl() ||
                it.code in 0x202A..0x202E ||
                it.code in 0x2066..0x2069
            ) {
                ' '
            } else {
                it
            }
        }.joinToString("")
        .take(MAX_TEXT_CHARS)

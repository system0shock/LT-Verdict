package io.ltverdict.core

// W3.7 / ADR 0029: the fixed templates of the incident texts. An incident states what coincided in time and where, never a cause, so every
// text of a document is one of these templates with the data substituted. `{name}` is replaced in one pass: a value is never scanned for
// placeholders again. The constant text contains none of the causal words of ADR 0029 (tested against CAUSAL_WORDING of verify_slice0.py).

internal const val AREA_OVERALL = "весь прогон"
internal const val AREA_TRANSACTION = "транзакция {label}"
internal const val AREA_OVERALL_GENITIVE = "всего прогона"
internal const val AREA_TRANSACTION_GENITIVE = "транзакции {label}"

internal const val TITLE_TRANSACTION_WINDOW = "Нарушение SLA: {area} в окне {window_id}"
internal const val TITLE_TRANSACTION = "Нарушение SLA: {area}"
internal const val TITLE_RESOURCE = "Сигналы ресурса: {entity} в окне {window_id}"
internal const val TITLE_LOAD = "Сигналы нагрузки в окне {window_id}"

internal const val SUMMARY_TRANSACTION_WINDOW = "В окне {window_id} нарушено правил policy: {n}. Область: {area}."
internal const val SUMMARY_TRANSACTION = "За весь прогон нарушено правил policy: {n}. Область: {area}. Время нарушения не определено."
internal const val SUMMARY_RESOURCE =
    "На интервале по сущности {entity} найдено находок: {n}; их интервалы перекрываются или соприкасаются."
internal const val SUMMARY_LOAD = "На интервале по общей нагрузке найдено находок: {n}; их интервалы перекрываются или соприкасаются."

internal const val NEGATIVE_POLICY_NOT_EVALUATED = "Правила policy по транзакциям не проверялись: файл нагрузки разобран не полностью."
internal const val NEGATIVE_OTHER_POLICY_WINDOW = "Остальные проверки policy в окне {window_id} выполнены: {n}."
internal const val NEGATIVE_OTHER_POLICY = "Остальные проверки policy выполнены: {n}."
internal const val NEGATIVE_RESOURCE_RULES = "Правила ресурсов в окне {window_id} не нарушены: проверок {n}."
internal const val NEGATIVE_GENERATOR_RESOURCES = "Правила ресурсов нагрузочного генератора в окне {window_id} не нарушены: проверок {n}."
internal const val NEGATIVE_NO_ANOMALY_EPISODES = "Проверки отклонений в окне {window_id} не нашли эпизодов: {n}."
internal const val NEGATIVE_NO_MATERIAL_TREND = "Проверки трендов в окне {window_id} не нашли существенного тренда: {n}."
internal const val NEGATIVE_RESOURCE_DATA_NOT_PROVIDED = "Снимок ресурсов не передан: ресурсные проверки не выполнялись."
internal const val NEGATIVE_CHECKS_NOT_EVALUATED = "Часть проверок в окне {window_id} не выполнена: {n}; код: {reason_code}."

internal const val NEXT_COMPARE_WITH_BASELINE = "Сравнить метрики {area} с baseline."
internal const val NEXT_OPEN_RESOURCE_SERIES = "Открыть ряды сущности {entity} за интервал находок."
internal const val NEXT_OPEN_LOAD_SERIES = "Открыть ряды нагрузки за интервал находок."
internal const val NEXT_OPEN_SAME_WINDOW_SIGNALS = "Открыть сигналы окна {window_id}, совпавшие по времени: {n}."
internal const val NEXT_PROVIDE_RESOURCE_SNAPSHOT = "Передать снимок ресурсов, чтобы проверить ресурсные сигналы на том же интервале."
internal const val NEXT_COMPLETE_NOT_EVALUATED_CHECKS = "Устранить недостаток данных для проверок, помеченных как не выполненные."

/** Every constant text above; the tests check it against the list of causal words. */
internal val INCIDENT_TEXT_TEMPLATES: List<String> =
    listOf(
        AREA_OVERALL,
        AREA_TRANSACTION,
        AREA_OVERALL_GENITIVE,
        AREA_TRANSACTION_GENITIVE,
        TITLE_TRANSACTION_WINDOW,
        TITLE_TRANSACTION,
        TITLE_RESOURCE,
        TITLE_LOAD,
        SUMMARY_TRANSACTION_WINDOW,
        SUMMARY_TRANSACTION,
        SUMMARY_RESOURCE,
        SUMMARY_LOAD,
        NEGATIVE_POLICY_NOT_EVALUATED,
        NEGATIVE_OTHER_POLICY_WINDOW,
        NEGATIVE_OTHER_POLICY,
        NEGATIVE_RESOURCE_RULES,
        NEGATIVE_GENERATOR_RESOURCES,
        NEGATIVE_NO_ANOMALY_EPISODES,
        NEGATIVE_NO_MATERIAL_TREND,
        NEGATIVE_RESOURCE_DATA_NOT_PROVIDED,
        NEGATIVE_CHECKS_NOT_EVALUATED,
        NEXT_COMPARE_WITH_BASELINE,
        NEXT_OPEN_RESOURCE_SERIES,
        NEXT_OPEN_LOAD_SERIES,
        NEXT_OPEN_SAME_WINDOW_SIGNALS,
        NEXT_PROVIDE_RESOURCE_SNAPSHOT,
        NEXT_COMPLETE_NOT_EVALUATED_CHECKS,
    )

/** Substitutes `{name}` in one pass; a placeholder without a value is a programming error. */
internal fun renderIncidentText(
    template: String,
    vararg values: Pair<String, String>,
): String {
    val byName = values.toMap()
    val text = StringBuilder()
    var position = 0
    while (position < template.length) {
        val open = template[position]
        val close = if (open == '{') template.indexOf('}', position) else -1
        if (close > position) {
            val name = template.substring(position + 1, close)
            text.append(requireNotNull(byName[name]) { "no value for {$name} in \"$template\"" })
            position = close + 1
        } else {
            text.append(open)
            position++
        }
    }
    return text.toString()
}

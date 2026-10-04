package io.ltverdict.report

// Words for the reason codes of the analysis result in the HTML report.
// The same codes and Russian phrases live in ui/src/verdictReasons.ts; ui/scripts/verify-report-reasons.mjs
// (npm run test:contracts) requires the two sets of codes to be equal, so a new code is added to both files.
private val REASON_WORDS: Map<String, String> =
    mapOf(
        // Разбор входного файла (run_validity INVALID или DEGRADED)
        "EMPTY_INPUT" to "Во входном файле нет ни одного запроса, анализировать нечего.",
        "INVALID_JMETER_CSV_HEADER" to
            "В заголовке JMeter CSV нет обязательного столбца (timeStamp, elapsed, label, success) или он повторяется.",
        "MALFORMED_JMETER_CSV" to
            "JMeter CSV повреждён: не удалось разобрать заголовок или строку (неверное число столбцов, число или значение success).",
        "MALFORMED_JMETER_XML" to "JMeter XML повреждён или устроен не так, как ожидается.",
        "UNSAFE_XML" to "В XML есть DTD или внешние сущности. Такие файлы не читаются из соображений безопасности.",
        "MALFORMED_GATLING_TEXT" to "Текстовый журнал Gatling повреждён.",
        "MALFORMED_GATLING_BINARY" to "Бинарный журнал Gatling повреждён.",
        "UNSUPPORTED_GATLING_TEXT" to
            "Запись RUN текстового журнала Gatling не подходит: версия не " +
            "поддерживается, запись повторяется или в ней неверное число полей.",
        "UNSUPPORTED_GATLING_BINARY" to "Эта версия бинарного журнала Gatling не поддерживается.",
        "TRUNCATED_GATLING_BINARY" to "Бинарный журнал Gatling оборван: прочитаны только записи до обрыва, поэтому вердикт не выдаётся.",
        "INVALID_SAMPLE_TIMESTAMP" to "У запроса недопустимая отметка времени. Возможно, время записано в секундах, а не в миллисекундах.",
        "RESOURCE_LIMIT_EXCEEDED" to
            "Превышен предел обработки (размер метки, глубина, число транзакций или интервалов). Файл не обработан.",
        // Привязка правил политики к метрикам
        "METRIC_NOT_AVAILABLE" to "Для правила нет данных: в его области нет ни одного запроса, порог проверить не с чем.",
        "TRANSACTION_NOT_FOUND" to "Транзакция из области правила не найдена в результатах нагрузки.",
        "AMBIGUOUS_TRANSACTION" to "Имя транзакции из правила подходит нескольким транзакциям, правило нельзя привязать однозначно.",
        "BUSINESS_OBSERVATIONS_NOT_FOUND" to "В окне нет ни одного запроса нагрузки, правила политики в нём не проверены.",
        "INSUFFICIENT_SAMPLES" to
            "В области правила слишком мало запросов (меньше минимального пола): порог не сравнивался, вердикта по правилу нет.",
        "SMALL_SAMPLE" to "У части правил запросов меньше рекомендуемого минимума: PASS или " +
            "FAIL рассчитан, но помечен как «малая выборка».",
        // SLA-правила ресурсов
        "RESOURCE_SERIES_NOT_FOUND" to "Ряд ресурса из правила отсутствует в снимке ресурсов.",
        "MISSING_RESOURCE_CELLS" to "В ряду ресурса есть пропуски в окне оценки. Правило не проверено, даже если нарушение уже видно.",
        // Неполное покрытие: на вердикт само по себе не влияет
        "RESOURCE_GAPS" to "В ряду ресурса есть пропущенные ячейки.",
        "NO_OBSERVATIONS" to "В ряду ресурса нет ни одного значения в окне.",
        "INSUFFICIENT_OBSERVATIONS" to "В ряду ресурса слишком мало значений для статистики.",
        "SOURCE_ACQUISITION_PARTIAL" to "Онлайн-источник вернул данные не по всем запросам.",
        "SOURCE_ACQUISITION_FAILED" to "Онлайн-источник не вернул данные.",
        "SOURCE_REQUEST_CAP_EXCEEDED" to "Достигнут предел числа запросов к источнику, часть данных не запрошена.",
        // Оценка ёмкости
        "CAPACITY_RUN_NOT_VALID" to "Нагрузка разобрана не полностью, ёмкость оценить нельзя.",
        "CAPACITY_WINDOW_EVIDENCE_MISSING" to "Для ступени нет окна или итогов окна.",
        "CAPACITY_INSUFFICIENT_COMPLETE_BINS" to "В окне ступени слишком мало полных десятисекундных интервалов нагрузки.",
        "CAPACITY_LOAD_GAPS" to "В окне ступени есть интервалы без данных о нагрузке.",
        "CAPACITY_ACHIEVED_LOAD_MISSING" to "Достигнутую нагрузку ступени вычислить не удалось.",
        "CAPACITY_SLA_MISSING" to "Для ступени не заданы SLA-правила, поэтому вердикта по ней нет.",
        "CAPACITY_GUARD_MISSING" to
            "Не задана или не найдена проверка генератора нагрузки: нельзя исключить, что предел создал сам генератор.",
        "CAPACITY_GUARD_FAILED" to "Проверка генератора нагрузки не пройдена: результат мог быть ограничен самим генератором.",
        "CAPACITY_TARGET_MISSED" to "Достигнутая нагрузка ниже цели ступени с учётом допуска.",
        "CAPACITY_INSUFFICIENT_SAMPLES" to
            "В окне ступени мало запросов (меньше минимума) либо у правила окна малая выборка: ступень не подтверждена.",
        "CAPACITY_SLA_NO_VERDICT" to "SLA-правила ступени не дали вердикта.",
        "CAPACITY_STAGE_NOT_VERIFIED" to "Хотя бы одна ступень не подтверждена, граница ёмкости не определена.",
        "CAPACITY_NON_MONOTONIC_TARGET" to "Цели ступеней идут не по возрастанию.",
        "CAPACITY_NON_MONOTONIC_VERIFIED_LOAD" to
            "Подтверждённая нагрузка ступеней противоречива: она убывает или проходная ступень не ниже упавшей.",
        "CAPACITY_NON_MONOTONIC_OUTCOME" to "После ступени с нарушением идёт ступень без нарушения: граница не определена.",
    )

internal val REASON_CODES: Set<String> = REASON_WORDS.keys

internal const val UNKNOWN_REASON_WORDS = "Причина без расшифровки в этой версии отчёта."

internal fun reasonWords(code: String): String = REASON_WORDS[code] ?: UNKNOWN_REASON_WORDS

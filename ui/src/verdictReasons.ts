// Слова для кодов причин из результата анализа: analysis_coverage.reasons,
// policy_check.reason_code, resource_policy_check.reason, capacity_summary.reasons.
// Каждый код взят из src/main/kotlin; список с файл:строка — в
// docs/superpowers/plans/2026-09-29-ui-verdict-first.md, раздел 6.
// noVerdict: true — код сам по себе делает вердикт NO_VERDICT (или INDETERMINATE у ступени ёмкости).

export interface ReasonEntry {
  text: string
  noVerdict: boolean
}

export const REASONS: Record<string, ReasonEntry> = {
  // Разбор входного файла (run_validity INVALID или DEGRADED)
  EMPTY_INPUT: { noVerdict: true, text: 'Во входном файле нет ни одного запроса, анализировать нечего.' },
  INVALID_JMETER_CSV_HEADER: { noVerdict: true, text: 'В заголовке JMeter CSV нет обязательного столбца (timeStamp, elapsed, label, success) или он повторяется.' },
  MALFORMED_JMETER_CSV: { noVerdict: true, text: 'Строка JMeter CSV повреждена: неверное число столбцов, число или значение success.' },
  MALFORMED_JMETER_XML: { noVerdict: true, text: 'JMeter XML повреждён или устроен не так, как ожидается.' },
  UNSAFE_XML: { noVerdict: true, text: 'В XML есть DTD или внешние сущности. Такие файлы не читаются из соображений безопасности.' },
  MALFORMED_GATLING_TEXT: { noVerdict: true, text: 'Текстовый журнал Gatling повреждён.' },
  MALFORMED_GATLING_BINARY: { noVerdict: true, text: 'Бинарный журнал Gatling повреждён.' },
  UNSUPPORTED_GATLING_TEXT: { noVerdict: true, text: 'Эта версия текстового журнала Gatling не поддерживается.' },
  UNSUPPORTED_GATLING_BINARY: { noVerdict: true, text: 'Эта версия бинарного журнала Gatling не поддерживается.' },
  TRUNCATED_GATLING_BINARY: { noVerdict: true, text: 'Бинарный журнал Gatling оборван: прочитаны только записи до обрыва, поэтому вердикт не выдаётся.' },
  INVALID_SAMPLE_TIMESTAMP: { noVerdict: true, text: 'У запроса недопустимая отметка времени. Возможно, время записано в секундах, а не в миллисекундах.' },
  RESOURCE_LIMIT_EXCEEDED: { noVerdict: true, text: 'Превышен предел обработки (размер метки, глубина, число транзакций или интервалов). Файл не обработан.' },
  // Привязка правил политики к метрикам
  METRIC_NOT_AVAILABLE: { noVerdict: true, text: 'Для правила нет данных: в его области нет ни одного запроса, порог проверить не с чем.' },
  TRANSACTION_NOT_FOUND: { noVerdict: true, text: 'Транзакция из области правила не найдена в результатах нагрузки.' },
  AMBIGUOUS_TRANSACTION: { noVerdict: true, text: 'Имя транзакции из правила подходит нескольким транзакциям, правило нельзя привязать однозначно.' },
  BUSINESS_OBSERVATIONS_NOT_FOUND: { noVerdict: true, text: 'В окне нет ни одного запроса нагрузки, правила политики в нём не проверены.' },
  // SLA-правила ресурсов
  RESOURCE_SERIES_NOT_FOUND: { noVerdict: true, text: 'Ряд ресурса из правила отсутствует в снимке ресурсов.' },
  MISSING_RESOURCE_CELLS: { noVerdict: true, text: 'В ряду ресурса есть пропуски в окне оценки. Правило не проверено, даже если нарушение уже видно.' },
  // Неполное покрытие: на вердикт само по себе не влияет
  RESOURCE_GAPS: { noVerdict: false, text: 'В ряду ресурса есть пропущенные ячейки.' },
  NO_OBSERVATIONS: { noVerdict: false, text: 'В ряду ресурса нет ни одного значения в окне.' },
  INSUFFICIENT_OBSERVATIONS: { noVerdict: false, text: 'В ряду ресурса слишком мало значений для статистики.' },
  SOURCE_ACQUISITION_PARTIAL: { noVerdict: false, text: 'Онлайн-источник вернул данные не по всем запросам.' },
  SOURCE_ACQUISITION_FAILED: { noVerdict: false, text: 'Онлайн-источник не вернул данные.' },
  SOURCE_REQUEST_CAP_EXCEEDED: { noVerdict: false, text: 'Достигнут предел числа запросов к источнику, часть данных не запрошена.' },
  // Оценка ёмкости
  CAPACITY_RUN_NOT_VALID: { noVerdict: true, text: 'Нагрузка разобрана не полностью, ёмкость оценить нельзя.' },
  CAPACITY_WINDOW_EVIDENCE_MISSING: { noVerdict: true, text: 'Для ступени нет окна или итогов окна.' },
  CAPACITY_INSUFFICIENT_COMPLETE_BINS: { noVerdict: true, text: 'В окне ступени слишком мало полных десятисекундных интервалов нагрузки.' },
  CAPACITY_LOAD_GAPS: { noVerdict: true, text: 'В окне ступени есть интервалы без данных о нагрузке.' },
  CAPACITY_ACHIEVED_LOAD_MISSING: { noVerdict: true, text: 'Достигнутую нагрузку ступени вычислить не удалось.' },
  CAPACITY_SLA_MISSING: { noVerdict: false, text: 'Для ступени не заданы SLA-правила, поэтому вердикта по ней нет.' },
  CAPACITY_GUARD_MISSING: { noVerdict: true, text: 'Не задана или не найдена проверка генератора нагрузки: нельзя исключить, что предел создал сам генератор.' },
  CAPACITY_GUARD_FAILED: { noVerdict: true, text: 'Проверка генератора нагрузки не пройдена: результат мог быть ограничен самим генератором.' },
  CAPACITY_TARGET_MISSED: { noVerdict: true, text: 'Достигнутая нагрузка ниже цели ступени с учётом допуска.' },
  CAPACITY_SLA_NO_VERDICT: { noVerdict: true, text: 'SLA-правила ступени не дали вердикта.' },
  CAPACITY_STAGE_NOT_VERIFIED: { noVerdict: true, text: 'Хотя бы одна ступень не подтверждена, граница ёмкости не определена.' },
  CAPACITY_NON_MONOTONIC_TARGET: { noVerdict: true, text: 'Цели ступеней идут не по возрастанию.' },
  CAPACITY_NON_MONOTONIC_VERIFIED_LOAD: { noVerdict: true, text: 'Подтверждённая нагрузка ступеней противоречива: она убывает или проходная ступень не ниже упавшей.' },
  CAPACITY_NON_MONOTONIC_OUTCOME: { noVerdict: true, text: 'После ступени с нарушением идёт ступень без нарушения: граница не определена.' },
}

export function reasonText(code: string): string {
  return REASONS[code]?.text ?? 'Причина без расшифровки в этой версии интерфейса.'
}

export function isNoVerdictReason(code: string): boolean {
  return REASONS[code]?.noVerdict ?? false
}

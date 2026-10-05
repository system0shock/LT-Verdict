- Правило покрытия строгое по умолчанию (ADR 0018, вопрос 107). Платформенное
  `sla`-правило, у которого `signal` совпадает с `platform_coverage.signal` (сигнал
  недоступности реплик), получает допуск пропусков `max_missing_fraction = 0` и
  `max_gap_cells = 0`: потеря даже одной точки этого сигнала даёт `NO_VERDICT` с
  `MISSING_RESOURCE_CELLS` вместо `PASS` с `RESOURCE_GAPS`. Значения `defaults`
  политики на такое правило не действуют; ослабить его можно только полями
  `max_missing_fraction` и `max_gap_cells` самого правила. Остальные платформенные
  `sla`-правила сохраняют допуск по умолчанию 5 % и 3 ячейки. Ручной обход (явные
  нули у правила покрытия) больше не нужен. Меняется identity: при заданном
  `platform_coverage` `verdict_gates` получает ключи
  `platform_coverage_max_missing_fraction_default` (`"0"`) и
  `platform_coverage_max_gap_cells_default` (`"0"`), поэтому анализ с такой политикой
  получает новый `analysis_id`; срез выходит одним релизом со срезом S7 (допуск
  пропусков), который тоже меняет identity. Анализы без `platform_coverage`, схема
  `policy.v1` и ключ сопоставимости baseline не меняются; новых кодов причин нет.

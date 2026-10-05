- Платформенные правила в политике (ADR 0018, срез S6, строгий режим). `policy.v1`
  получает необязательные секции `platform_services` (каталог ожидаемых сервисов),
  `platform_coverage` (сигнал покрытия) и `platform_rules`: правило-шаблон
  (`signal`, `scope`, `operator` `gt|lt` как условие нарушения, `threshold`, `unit`,
  `aggregation`, `min_consecutive_cells`, `effect`, необязательное `window_ids`)
  разворачивается по сервисам области (`service` или `all_services` с `except`) в
  ресурсные проверки `<id>/<сервис>` и оценивается тем же вычислителем, что и
  правила снимка; в evidence `resource_policy_check` добавлены `platform_rule_id` и
  `service`. Отсутствие ряда, неоднозначный ряд, чужая единица или агрегация, ряд
  сервиса вне каталога, окно короче серии, неизвестное окно и отсутствие снимка дают
  `NO_VERDICT` с причинами `RESOURCE_SERIES_NOT_FOUND`, `PLATFORM_SERIES_AMBIGUOUS`,
  `PLATFORM_UNIT_MISMATCH`, `PLATFORM_AGGREGATION_MISMATCH`,
  `PLATFORM_SERVICE_NOT_IN_CATALOG`, `RULE_WINDOW_TOO_SHORT`, `RULE_WINDOW_NOT_FOUND`,
  `RESOURCE_SNAPSHOT_REQUIRED`, а не `PASS`. Политику с платформенным `sla`-правилом
  валидатор принимает, только если для каждой пары «сервис × окно» есть правило
  покрытия (`PLATFORM_COVERAGE_MISSING`). Политика с `platform_rules` не совмещается
  со снимком или профилем, поставляющим ресурсные `sla`-правила: ошибка привязки
  `PLATFORM_RULES_CONFLICT` до создания анализа (CLI код 4, API 422). Допуск
  пропусков в этом срезе строгий (любой пропуск даёт `NO_VERDICT`). Анализы без
  `platform_rules`, `identity.json`, ключ сопоставимости baseline и `analysis_id` не
  меняются; файл политики с платформенными секциями имеет другой `policy_sha256`, а
  прежняя версия отвергает его как `UNKNOWN_FIELD`. Схема и примеры `policy.v1`
  обновлены. Словарь причин отчёта пополнен кодом `RULE_WINDOW_NOT_FOUND`, которого
  не хватало после среза S5.

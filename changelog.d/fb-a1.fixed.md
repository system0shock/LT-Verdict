- Ошибки `SOURCE_CONFIG_INVALID` при разборе конфигурации источников указывают
  JSON pointer поля и причину отказа. CLI сохраняет код в первой строке stderr,
  добавляет пояснение следующей строкой и возвращает детали в JSON при
  `source validate --format json` с `schema_version: source-check.v1`.
  Правила конфигурации и код выхода CLI не менялись.

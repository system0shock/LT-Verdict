- Модель внутриоконного шума для сравнения с baseline (W4.2): при наличии
  1-секундных бакетов (`normalized-1s.ndjson`) сравнение окон в
  `BaselineComparison` подавляет колебания в пределах круговой блочной оценки
  шума (`NO_MATERIAL_CHANGE` с причиной `WITHIN_NOISE`), допуск сопоставимости
  `stage_binding` расширен до симметричного порога по `throughput_rps`
  (`max/min <= 1.05`, от `−4,76 %` до `+5,00 %` к baseline) при совпадении структуры
  `(id, role)` и `evaluated_window_ids`, а в `policy.v1` добавлен необязательный
  флаг `defaults.baseline_regression` (`"ignore"` или `"fail"`), позволяющий
  выносить вердикт `FAIL` при регрессии (с поддержкой `--baseline`, части
  `baseline` в `POST /api/jobs` и аргумента `baseline` в MCP `analyze`). По
  умолчанию (без `"baseline_regression": "fail"`) вердикты, `identity.json`,
  `analysis_id` и байты `analysis-result.json` полностью совместимы с `v0.2.0`.

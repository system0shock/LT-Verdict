# План: trend-plan.v1 и L0-детектор тренда ресурсных метрик

Дата: 2026-09-27. Ветка: `fix/input-unit-fill-coverage`.

Границы и статистическое обоснование — в
[docs/analytics-trend-detection.md](../../../analytics-trend-detection.md). Этот
план фиксирует контракт и состав работ до реализации.

## Цель

Не пропустить явный монотонный рост или падение ресурсной метрики внутри
объявленного устойчивого окна, даже если метрика ни разу не пересекла порог SLA.
Поставка уровня L0: детерминированная материальность на уже вычисляемых
статистиках, без p-value, без bootstrap и без Holm. Вердикт не меняется.

## Контракт `trend-plan.v1`

Отдельный optional вход, подаётся вместе с `resource-snapshot.v1`. Без него
стандартный поток, verdict и существующие контракты не меняются.

```json
{
  "schema_version": "trend-plan.v1",
  "resource_snapshot_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "checks": [
    {
      "id": "cpu-growth",
      "series_id": "system-cpu",
      "window_id": "evaluation",
      "direction": "increase",
      "min_cells": 30,
      "magnitude_gate": {
        "min_slope_units_per_second": "0.001",
        "min_split_half_shift_pct": "5"
      }
    }
  ]
}
```

Закрытые наборы полей:

- корень: `schema_version`, `resource_snapshot_sha256`, `checks`;
- check: `id`, `series_id`, `window_id`, `direction`, `min_cells`,
  `magnitude_gate`;
- `magnitude_gate`: `min_slope_units_per_second`, `min_split_half_shift_pct`,
  оба обязательны.

Значения и диапазоны:

- `direction`: `increase`, `decrease`, `either`;
- `min_cells`: целое, `30..100000`;
- обе величины гейта: положительные десятичные, ограничения точности наследуются
  от ресурсного контракта (32 значащих цифры, 12 дробных, модуль до 1e18);
- идентификаторы: непустые, без управляющих символов, до 128 байт;
- `checks`: от 1 до 32, идентификаторы уникальны;
- файл: до 1 MiB, глубина JSON до 12.

`load_input_sha256` в контракт не входит: план привязан только к snapshot, а
snapshot уже привязан к load input и проверяется отдельно.

## Семантика L0

Для каждой проверки по окну и series берутся те же статистики, что публикует
`resource_summary`: `slope_per_second` (OLS по grid-секундам) и
`split_half_shift` (разница медиан половин окна), плюс `median` как масштаб для
процентного гейта. Пропуски не заполняются и не сжимают время.

Порог материальности — оба условия одновременно:

- `abs(slope_per_second) >= min_slope_units_per_second`;
- `abs(split_half_shift) >= abs(median) * min_split_half_shift_pct / 100`.

Направление: при `increase`/`decrease` знаки обеих статистик обязаны совпасть с
объявленным; при `either` требуемый знак берётся из slope, и split-half shift
обязан иметь тот же знак.

Найденный тренд публикуется как finding `resource_trend` с `effect=diagnostic` и
`uncertainty=NOT_ESTIMATED`. Отсутствие находки не означает отсутствие роста.

## Статусы и reason-коды

Статусы проверки: `TREND_OBSERVED`, `NO_MATERIAL_TREND`, `INSUFFICIENT_CELLS`,
`UNAVAILABLE`.

Reason-коды: `NO_OBSERVATIONS`, `TREND_MIN_CELLS_NOT_MET`,
`INSUFFICIENT_OBSERVATIONS`, `RESOURCE_GAPS`, `TREND_MEDIAN_ZERO`,
`TREND_DIRECTION_MISMATCH`, `TREND_DIRECTION_DISAGREEMENT`,
`TREND_SLOPE_BELOW_MINIMUM`, `TREND_SHIFT_BELOW_MINIMUM`,
`STATIONARITY_NOT_EVALUATED`, `TREND_SERIES_NOT_FOUND`, `TREND_WINDOW_NOT_FOUND`,
`TREND_SNAPSHOT_MISMATCH`, `TREND_RESOURCE_REQUIRED`, `TREND_READ_ERROR`,
`RESOURCE_LIMIT_EXCEEDED`.

`NON_STATIONARY_WINDOW` в L0 не выдаётся: детектора стационарности нет. Вместо
него каждый `TREND_OBSERVED` несёт `STATIONARITY_NOT_EVALUATED`. Статус
зарезервирован за L1.

## Identity, артефакты и результат

- `analysis-identity.v1`: условные `trend_plan_sha256` и
  `trend_plan_version = "trend-plan.v1"`, модуль `resource-trend-evaluation`
  версии `1`, `input_versions.trend = "trend-plan.v1"`, блок `limits` с
  `trend_plan_bytes_max`, `trend_json_depth_max`, `trend_checks_max`,
  `trend_min_cells_floor`.
- Артефакты: `trend-plan.json` (сырые загруженные байты) и `trend.json`
  (канонический вывод).
- `run.v1`: input type `trend_plan`. Схема `run.schema.json` не меняется: поле
  `type` остаётся свободной строкой, а условные блоки описывают только
  `capacity_step`.
- `analysis-result.v1` не меняется: trend публикует только `findings` и
  `evidence`. Новое top-level поле запрещено, потому что схема имеет
  `additionalProperties: false`, а `ai/AdvisoryAi.kt` проверяет точное
  совпадение набора ключей результата.
- Evidence-типы: `trend_check` на каждую проверку и `trend_summary` на анализ.
  Finding-тип: `resource_trend`.
- `analysis_coverage` trend не изменяет: отказы отдельных проверок видны в их
  evidence, а полнота данных нагрузки и ресурсов описывается существующими
  причинами.

## Состав работ

1. `core/TrendPlan.kt` — валидатор и binding, semantic hash по типизированной
   модели, собственный набор private-хелперов по образцу `CapacityPlan.kt`.
2. `core/ResourceStatistics.kt` — перевод `Statistics`, `statistics`,
   `IndexedValue`, `cellIndex`, `cellStart`, `resourceId`, `MC` и `Int.bd()` с
   `private` на `internal`. Четвёртая копия числовых определений не добавляется.
3. `core/TrendAnalysis.kt` — `evaluateTrend`, evidence и finding.
4. `core/AnalysisService.kt` — поле запроса, pre-flight binding, обе ветки
   анализа, артефакты, `runMetadata`.
5. `core/AnalysisResult.kt` — identity и limits.
6. `cli/CommandLine.kt` — флаг `--trend`, reader, usage, binding.
7. `web/LocalApi.kt` — part `trend_plan`, `InvalidTrend` → 422
   `INVALID_TREND_PLAN`, пересчёт `MAX_JOB_REQUEST_BYTES`, роуты скачивания.
8. UI: `types.ts`, `api.ts`, `App.vue`, `RunSetup.vue`, `AnalysisView.vue`,
   списки входов в `security-a11y.spec.ts`, новый `e2e/trend.spec.ts`.
9. `docs/contracts/trend/v1/trend-plan.schema.json` и
   `examples/valid|invalid`, блок в `ui/scripts/verify-policy-schema.mjs`.
10. ADR, раздел пользовательской документации, CHANGELOG, поправка
    `docs/analytics-trend-detection.md` (ключ identity и статус
    `NON_STATIONARY_WINDOW`).

## Проверка

```powershell
.\gradlew.bat --offline --no-daemon check installDist -x npmCi --no-parallel
npm --prefix ui run typecheck
npm --prefix ui run lint
npm --prefix ui run test:contracts
npm --prefix ui run e2e
python tools/verify_slice0.py
python -m unittest tools.test_verify_slice0 tools.test_generate_jtl tools.test_onboard_test
```

Golden-фикстуры identity не меняются: все новые identity-поля условные, а
существующие тесты вызывают `analysisIdentity` и `analysisResult` без trend.

## Вне объёма

L1 (Theil–Sen, блочный перестановочный null, Holm), `budget` в контракте,
детекция стационарности, CUSUM/EWMA, стохастические модели процесса, causal
интерпретация, слово leak в выводе, отчётные рендереры (HTML/AsciiDoc/Confluence
фильтруют evidence по собственному списку типов и новый тип игнорируют — как
сейчас с capacity).

# Baseline: явное подтверждение, предупреждения, пустое окно. План реализации ADR 0017

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** реализовать принятый [ADR 0017](../../../adr/0017-baseline-candidates-and-confirmation.md): `USER_CONFIRMED` только по явному решению для пары (в том числе для statistical baseline), массив `warnings` в ответе `comparison`, пустое окно как `INSUFFICIENT_DATA` с причиной `EMPTY_WINDOW` (нагрузочные и ресурсные строки), подсказки интерфейса.

**Источник истины:** ADR 0017 (Accepted владельцем 2026-09-30). Этот план заменяет по существу черновой план `2026-09-30-baseline-candidates-and-confirmation.md` (он писался по `b086b3a` и до правок ADR по ревью). Где они расходятся, действует ADR; расхождения перечислены ниже.

**База:** `origin/main` = `9d388d7`, ветка `fix/baseline-confirmation`. Ссылки `файл:строка` сверены с этим коммитом.

## Расхождения с черновым планом (исправлены здесь)

| Тема | Черновой план | ADR 0017 и этот план |
| --- | --- | --- |
| Номер ADR | 0016, Task 1 создаёт ADR | 0017 уже принят и лежит в репозитории; ADR не создаётся и не правится (0016 зарезервирован планом «числа ядра») |
| Ресурсные строки при пустом окне | «не затрагиваются» | `INSUFFICIENT_DATA`, причина `EMPTY_WINDOW` (приоритет над `RESOURCE_BINDING_MISSING`, `RESOURCE_BINDING_AMBIGUOUS`, `MISSING_METRIC`), дельты `null`, значения ресурсов видимы |
| Статус окна | не уточнён | всегда `INSUFFICIENT_DATA` при пустом окне: все строки `INSUFFICIENT_DATA`, отдельного переопределения нет |
| Порядок причин окна | `CONDITIONS_UNCONFIRMED`, `BASELINE_WINDOW_EMPTY`, `CURRENT_WINDOW_EMPTY`, `INCOMPLETE_METRICS` | тот же порядок (ADR зафиксировал его явно) |
| Пустое окно вместе с несовместимостью или отсутствующим окном | не тестируется | остаётся `NOT_EVALUATED` (тест) |
| Подсказки UI | нет | «В окне нет нагрузки...» с указанием, какое окно пусто; «Эталон создан по старым правилам...» при `INCOMPATIBLE_METRIC_DEFINITION` и при отказе `BASELINE_MIXED_SEMANTICS` |
| Сброс формы условий при смене окон | «уточнить» | e2e обязателен; на `9d388d7` у полей окон уже есть `@change="conditionBindingChanged"` (`BaselinePanel.vue:270,280`), поэтому правка кода нужна, только если e2e окажется красным |
| Тесты API | один тест подтверждения | плюс перенос старой manual-записи на statistical baseline с тем же победителем, смена набора кандидатов при том же победителе, `DELETE /api/baseline` |
| Тексты интерфейса | английские, в панели | русские, только в `ui/src/shell/labels.ts`; панель их импортирует |
| Ссылки на документы | user-doc ~356-386 | `docs/user/slice-1-local-analysis.md` ~413-443, `docs/development-plan-v0.6.md` ~310-315 |

## Scope (AGENTS.md)

```text
REQUESTED:
- Реализовать ADR 0017: Q1 (предупреждение CURRENT_IN_CANDIDATE_SET), Q2 (подтверждение
  для statistical), Q3 (без автоподтверждения по членству), Q4 (BASELINE_IS_CURRENT_ANALYSIS,
  BASELINE_IS_CURRENT_RUN), Q5 и вариант B (пустое окно), подсказки интерфейса.

REQUIRED TO ACHIEVE IT:
- BaselineComparison.kt: compareAnalyses (confirmed == true, warnings), защита пустого окна
  в WindowSummary.value, windowMetricComparison, windowComparison, resourceMetricComparisons.
- LocalApi.kt: baseline-conditions GET/POST и чтение условий в comparison для обоих
  режимов; удаление manualBaselineReference и baselineManualRequired.
- Тесты ядра и API (перечень в ADR, раздел «Тесты и golden»).
- UI: BaselinePanel.vue (форма условий для обоих режимов, warnings, две подсказки),
  labels.ts (русские строки), types.ts, e2e.
- Документация: user-doc, development-plan-v0.6, CHANGELOG, статусные строки ADR 0004 и 0028.

NOT REQUIRED (report-only):
- Менять POST /api/baseline, statisticalBaselineSelection, local-baseline.v1,
  local-baseline-conditions.v1, мигрировать файлы.
- DiagnosticAnalysis.kt, Metrics.kt, identity, analysis-result.v1, golden, RunBundle.
- Версия identity, повторное закрепление baseline (ADR 0016 и ADR 0014, раздельно).
- Число активных baseline и область по плечу (ADR-C), правка самого ADR 0017.
- Английские тексты остальной панели baseline (перевод старого интерфейса).

EXPECTED FILES TO CHANGE:
- Kotlin: src/main/kotlin/io/ltverdict/core/BaselineComparison.kt,
  src/main/kotlin/io/ltverdict/web/LocalApi.kt,
  src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt,
  src/test/kotlin/io/ltverdict/web/LocalApiTest.kt
- UI: ui/src/BaselinePanel.vue, ui/src/types.ts, ui/src/shell/labels.ts,
  ui/e2e/baseline.spec.ts, ui/e2e/diagnostics.spec.ts
- Docs: docs/user/slice-1-local-analysis.md, docs/development-plan-v0.6.md, CHANGELOG.md,
  docs/adr/0004-local-baseline-selection.md, docs/adr/0028-baseline-conditions-confirmation.md,
  этот план
```

Если объём заметно превысит перечень, остановиться и объяснить (AGENTS.md, п. 10). Работа идёт двумя группами коммитов: ядро и API, затем интерфейс и документация.

## Проверенные факты (по `9d388d7`)

- `replaceBaseline` (`RunBundleStore.kt:275-296`) не трогает `baseline-conditions/`; записи ключуются парой ссылок и окнами, режим в ключ не входит. Перенос manual-записи на statistical baseline с тем же победителем работает без миграции. `clearBaseline` (`:340-366`) удаляет все записи.
- `LocalApi.kt`: handlers условий `:400-436` (`manualBaselineReference()` в `:409`, `:430`); `comparison` `:438-473` (условное чтение `:450-455`); `manualBaselineReference` `:1074-1077`; `baselineManualRequired` `:1102-1103`; проверка `values.size !in 3..20` для statistical `:1047`. Минимум 3 кандидата не меняется.
- `BaselineComparison.kt`: `compareAnalyses` `:143-189`; `windowComparison` `:191-226`; `windowMetricComparison` `:250-291`; `resourceMetricComparisons` `:293-328`; `resourceMetricComparison` `:349-398` (`resourceName(statistic, reason == "RESOURCE_BINDING_MISSING")` использует итоговую причину, при добавлении `EMPTY_WINDOW` нужно передавать `bindingReason`, иначе у строк отсутствующей привязки пропадёт суффикс `:stage` в имени); `WindowSummary.value` `:450-458`.
- `ApiError` (`ui/src/api.ts:36`) несёт `code`, поэтому `BASELINE_MIXED_SEMANTICS` различим в интерфейсе.

## Global Constraints

- Числа, формулы, `min_change_percent`, `min_error_rate_delta`, SLA-вердикт не меняются: меняются `comparability`, статус и причина оконных строк, `warnings`.
- Членство кандидата проверяется по `run_id`, а не по паре `run_id + analysis_id`.
- Отсутствующее значение остаётся `null`, не `0`.
- Файлы репозитория имеют CRLF (autocrlf). Код Kotlin полностью ASCII. Русские строки только в `ui/src/shell/labels.ts` (UTF-8).
- Staging только явными путями.

---

## Группа 1. Ядро и API

### Task 1: Тесты ядра (красный шаг)

**Files:** `src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt`

- [ ] **Step 1:** в тестах `:104`, `:201`, `:277`, `:330` (statistical, ожидали `CANDIDATE` только из-за автоподтверждения) добавить `conditionsConfirmed = true`; в `:163` добавить `"warnings"` в набор ключей; тест `:523` заменить (см. ниже).
- [ ] **Step 2:** добавить тесты:
  1. `statistical membership never confirms comparability and is reported as a warning` (участник серии: `UNCONFIRMED` и `["CURRENT_IN_CANDIDATE_SET"]`; `conditionsConfirmed = true` даёт `USER_CONFIRMED` и те же `metrics`; вне серии: `UNCONFIRMED` и `[]`).
  2. `comparison reports a run compared with itself at analysis and run level` (`BASELINE_IS_CURRENT_ANALYSIS`, `BASELINE_IS_CURRENT_RUN`, `[]`).
  3. `statistical winner compared with itself reports both the analysis and the candidate set` (порядок `["BASELINE_IS_CURRENT_ANALYSIS", "CURRENT_IN_CANDIDATE_SET"]`).
  4. `another analysis of a candidate run is still a candidate` (`CURRENT_IN_CANDIDATE_SET` по `run_id`).
  5. `empty current window ...` и `empty baseline window ...` в обеих формах evidence (нули и `JsonNull`), все пять нагрузочных строк `INSUFFICIENT_DATA` с `EMPTY_WINDOW`, значения пустой стороны и дельты `null`, статус окна `INSUFFICIENT_DATA`, причины окна по порядку ADR.
  6. `empty window with resource rows` (ресурсная строка, которая без пустого окна была бы `CANDIDATE`: `INSUFFICIENT_DATA`, `EMPTY_WINDOW`, значения ресурсов видимы, дельты `null`; строка `RESOURCE_BINDING_MISSING` при пустом окне получает `EMPTY_WINDOW`, значения `null`, имя с суффиксом `:stage` сохранено).
  7. `empty window with incompatibility or a missing window stays not evaluated`.
  8. регрессия: настоящий 0 мс при `sample_count > 0` остаётся `ZERO_BASELINE` (уже есть, `:201`).
  9. явное `CONFIRMED` меняет только `comparability`, не `metrics` (пункт 1).
- [ ] **Step 3:** запустить `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Ожидание: падают новые тесты и правленные (отсутствует `warnings`, `USER_CONFIRMED` по членству, `-100`).

### Task 2: Реализация ядра (Codex, затем проверка)

**Files:** `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt`

- [ ] `compareAnalyses`: `val confirmed = conditionsConfirmed == true`; `warnings` (список из Q4 в порядке ADR); `put("warnings", ...)` сразу после `comparability`.
- [ ] `WindowSummary.value`: `null` при `sampleCount == 0L`.
- [ ] `windowMetricComparison`: `reason = "EMPTY_WINDOW"` при пустом любом окне (приоритет над `MISSING_METRIC`).
- [ ] `resourceMetricComparisons` и `resourceMetricComparison`: `empty` по `sampleCount == 0` любого окна; `reason = if (empty) "EMPTY_WINDOW" else bindingReason ?: ...`; резюме ресурсов ищется только при `bindingReason == null` (значения видимы); `resourceName(statistic, bindingReason == "RESOURCE_BINDING_MISSING")`.
- [ ] `windowComparison`: причины `BASELINE_WINDOW_EMPTY` и `CURRENT_WINDOW_EMPTY` между `CONDITIONS_UNCONFIRMED` и `INCOMPLETE_METRICS`.
- [ ] Прогнать `BaselineComparisonTest` (зелёный), затем `StatisticalValidationTest`, `UsefulnessValidationTest`, `StatisticalValidationIntegrationTest`, `python -m unittest tools.test_stats_validation tools.test_applicability_validation tools.test_usefulness_validation`. Фраза «обвязки не затронуты» допустима только по результату.

### Task 3: API: тесты (красный) и реализация

**Files:** `LocalApiTest.kt`, `LocalApi.kt`

- [ ] Тесты `LocalApiTest`: ключ `warnings` в тесте `:627` и `BASELINE_IS_CURRENT_RUN`; новый тест явного подтверждения statistical по паре (победитель определяется по ответу `POST /api/baseline`, проверять статус 200); перенос старой manual-записи на statistical baseline с тем же победителем; смена не-победителя в наборе кандидатов сохраняет запись; `DELETE /api/baseline` удаляет запись; тест `baseline conditions API persists three states...` (manual) остаётся зелёным.
- [ ] Реализация: в обоих handlers условий `selected.getValue("reference").jsonObject` вместо `manualBaselineReference()`; в `comparison` условие читается безусловно; удалить `manualBaselineReference` и `baselineManualRequired`.
- [ ] `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --tests "io.ltverdict.core.BaselineComparisonTest"`, затем `.\gradlew.bat check` (ktlint).
- [ ] Commit группы 1: `feat(baseline): require explicit comparability and report empty windows` (явные пути).

## Группа 2. Интерфейс и документация

### Task 4: Интерфейс

**Files:** `ui/src/types.ts`, `ui/src/shell/labels.ts`, `ui/src/BaselinePanel.vue`, `ui/e2e/baseline.spec.ts`, `ui/e2e/diagnostics.spec.ts`

- [ ] Тесты сначала (e2e): самосравнение показывает текст `BASELINE_IS_CURRENT_ANALYSIS`; statistical: член серии `UNCONFIRMED` и `CURRENT_IN_CANDIDATE_SET`, тестируемый вне серии подтверждается только явным решением по паре; порядок двух предупреждений у победителя, сравниваемого с собой (через мок ответа или реальный путь); смена окон показывает `UNKNOWN`; подсказка «В окне нет нагрузки...» с указанием окна (мок ответа `comparison`); подсказка «Эталон создан по старым правилам...» при `INCOMPATIBLE_METRIC_DEFINITION` (мок) и при `BASELINE_MIXED_SEMANTICS` (мок `POST /api/baseline` с 422). Моки comparison получают `warnings: []`.
- [ ] Красный запуск e2e, затем реализация: тип `BaselineComparisonWarning` и поле `warnings`; словарь русских строк в `labels.ts` (тексты подсказок из ADR дословно); `loadConditions` и `saveConditions` без ограничения `manual`, форма условий по `v-if="baseline"`; блок `data-testid="baseline-warnings"` (роль `status`); подсказки; сброс решения при смене окон, если e2e красный.
- [ ] `npm --prefix ui ci`, `typecheck`, `lint`, `test:contracts`, `build`, весь `e2e`.

### Task 5: Документация

- [ ] `docs/user/slice-1-local-analysis.md` (~413-443): правило `USER_CONFIRMED` только по явному решению, warnings, пустое окно, подсказки.
- [ ] `docs/development-plan-v0.6.md` (~310-315).
- [ ] `CHANGELOG.md` (Changed, Added); статусные строки ADR 0004 и 0028 со ссылкой на ADR 0017.
- [ ] `npx --yes markdownlint-cli2@0.23.2 "**/*.md"`, `git diff --check`.
- [ ] Commits: `feat(ui): ...`, `docs: ...` (явные пути).

### Task 6: Полная проверка и ревью

- [ ] `.\gradlew.bat --no-daemon clean check installDist`; `npm --prefix ui run e2e` (после `Remove-Item Env:NoDefaultCurrentDirectoryInExePath`); `rg -n "manualBaselineReference|baselineManualRequired|BASELINE_MANUAL_REQUIRED" src ui/src docs/user` пуст; `rg -n "[^\x00-\x7F]"` по изменённым Kotlin-файлам пуст.
- [ ] Независимое ревью Codex `git diff origin/main...HEAD` против ADR; принятые замечания отдельными коммитами.

# План реализации UI v4.4 (PR 0 – PR 4)

Дата: 2026-11-26.

Спецификация: [2026-11-26-ui-v4-overview-charts-rubricators-design.md](../specs/2026-11-26-ui-v4-overview-charts-rubricators-design.md).

ADR: [0038-transaction-time-series-cache.md](../../adr/0038-transaction-time-series-cache.md).

## Блок AGENTS.md

```text
REQUESTED:
  1. Привести «Обзор», «Глубокий анализ», «Сравнение» и «Историю релизов» к утверждённому дизайну v4.4.
  2. Добавить на все графики непрозрачную всплывающую подсказку в стиле Grafana у курсора с подсветкой активных точек и закреплением кликом.
  3. Добавить отдельную плашку вывода ошибок ПВС из логов (OpenSearch + JTL error-groups.v1) на «Обзоре».
  4. Расширить «Сравнение» сопоставлением регрессий по аппаратным ресурсам наравне с откликом.

REQUIRED TO ACHIEVE IT:
  - PR 0: ADR 0038, спецификация, план реализации, навигация в README.md.
  - PR 1: Кэш рядов по транзакциям из inputs/source.bin + GET .../transaction-buckets и GET .../error-groups в RunRoutes.kt + тесты LocalApiTest + api.ts/types.ts.
  - PR 2: TimeChart v2 + ChartFloatingTooltip.vue + стили .shell + обновлённые Сравнение (с аппаратными ресурсами), История релизов и CapacityKneeChart.
  - PR 3: Новый Обзор v4.4 (VerdictCard, OverviewPanel, LogErrorsPanel, SharedCursorChart, PlatformMap, адаптеры и E2E).
  - PR 4: Глубокий анализ v4.4 (фасетный рубрикатор + режимы Свободное сравнение / Фокус) и E2E.

NOT REQUIRED:
  - Изменение identity.json, analysis-result.json, канонического JSON, расчёта вердикта и серверных отчётов ядра.
  - Добавление новых внешних npm- или JVM-зависимостей.

EXPECTED FILES TO CHANGE:
  - PR 0: docs/adr/0038-transaction-time-series-cache.md, docs/superpowers/specs/2026-11-26-ui-v4-overview-charts-rubricators-design.md, docs/superpowers/plans/2026-11-26-ui-v4-implementation-plan.md, README.md
  - PR 1: src/main/kotlin/io/ltverdict/metrics/TransactionBuckets.kt, src/main/kotlin/io/ltverdict/web/RunRoutes.kt, src/test/kotlin/io/ltverdict/web/LocalApiTest.kt, ui/src/api.ts, ui/src/types.ts, changelog.d/tx-bucket-series-api.added.md
  - PR 2: ui/src/shell/timeChart.ts, ui/src/shell/TimeChart.vue, ui/src/shell/ChartFloatingTooltip.vue, ui/src/shell/shell.css, ui/src/styles.css, ui/src/BaselinePanel.vue, ui/src/BaselineCharts.vue, ui/src/shell/CompareCursorChart.vue, ui/src/shell/compareChart.ts, ui/src/shell/labels.compare.ts, ui/src/shell/HistoryPanel.vue, ui/src/shell/HistoryDynamics.vue, ui/src/shell/history.ts, ui/src/shell/labels.history.ts, ui/src/shell/CapacityKneeChart.vue, ui/e2e/time-chart-adapters.spec.ts, docs/user/release-history.md, changelog.d/ui-grafana-tooltip-charts.changed.md
  - PR 3: ui/src/VerdictCard.vue, ui/src/verdictSummary.ts, ui/src/shell/OverviewPanel.vue, ui/src/shell/LogErrorsPanel.vue, ui/src/shell/SharedCursorChart.vue, ui/src/shell/overview.ts, ui/src/shell/PlatformMap.vue, ui/src/shell/platformMap.ts, ui/src/shell/labels.ts, ui/e2e/overview.spec.ts, ui/e2e/overview-adapters.spec.ts, docs/user/slice-1-local-analysis.md, changelog.d/ui-overview-v4.changed.md
  - PR 4: ui/src/shell/DeepAnalysisPanel.vue, ui/src/shell/DeepCursorChart.vue, ui/src/shell/deep.ts, ui/src/shell/labels.ts, ui/e2e/deep.spec.ts, ui/e2e/deep-adapters.spec.ts, docs/user/slice-1-local-analysis.md, changelog.d/ui-deep-workbench-v4.changed.md
```

## Порядок выполнения и критерии приёмки

- [ ] **PR 0 — Документация и ADR 0038**: зафиксировать ADR, дизайн, план и ссылки в `README.md`; проверить `markdownlint-cli2`.
- [ ] **PR 1 — Бэкенд-маршруты `transaction-buckets` и `error-groups`**:
  - Написать RED-тесты в `LocalApiTest.kt` (неизменность `identity.json` и `analysis-result.json`, расчёт бакетов по транзакциям, валидация параметров, `error-groups`).
  - Реализовать `TransactionBuckets.kt` и маршруты в `RunRoutes.kt`, клиентские функции в `ui/src/api.ts` и типы в `ui/src/types.ts`.
  - Проверить `./gradlew --offline --no-daemon test --tests io.ltverdict.web.LocalApiTest`.
- [ ] **PR 2 — Графический движок v2, подсказка Grafana, Сравнение (с аппаратными ресурсами) и История**:
  - Расширить `timeChart.ts` / `TimeChart.vue`, добавить `ChartFloatingTooltip.vue`, обновить `.shell` токены и `max-width`.
  - Обновить `BaselinePanel.vue`, `BaselineCharts.vue`, `CompareCursorChart.vue`, `compareChart.ts` для сравнения аппаратных ресурсов + отклика.
  - Обновить `HistoryPanel.vue`, `HistoryDynamics.vue`, `history.ts` и `CapacityKneeChart.vue`.
  - Проверить 0 нарушений CSP, `typecheck`, `lint`, `e2e`.
- [ ] **PR 3 — Страница «Обзор» v4.4 и плашка ошибок ПВС**:
  - Обновить `VerdictCard.vue` + `verdictSummary.ts` (числовые KPI ресурсных правил).
  - Реализовать двухпанельный главный график с табличным рубрикатором транзакций, 3-колоночные карточки инцидентов с мини-графиками, `LogErrorsPanel.vue` (логи ПВС OpenSearch + JTL `error-groups.v1`), вкладочный блок диагностики (`PlatformMap`, `Платформа`, `Тренды` в `ед./ч`).
- [ ] **PR 4 — «Глубокий анализ» v4.4**:
  - Реализовать левый фасетный рубрикатор (`Транзакции` / `Платформа`, `По сервисам` / `По метрикам`, `A / B / A+B`, поиск, только отклонения) и два режима (`Свободное сравнение (стопка)` ↔ `Фокус на одном ряде`).
- [ ] **Финальный Verification Gate**:
  - `Invoke-LtvSlot { npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run test:contracts }`
  - `Invoke-LtvE2E { npm --prefix ui run e2e }`
  - `Invoke-LtvExclusive { .\gradlew.bat --offline --no-daemon check installDist -x npmCi --no-parallel }`
  - `python tools/verify_slice0.py`
  - `npx --yes markdownlint-cli2 "docs/**/*.md" "*.md"`

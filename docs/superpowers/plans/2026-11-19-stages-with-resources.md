# Стадии нагрузки вместе со снимком ресурсов: план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** разрешить в одном анализе объявленные стадии (`load-stages.v1`) вместе со снимком
ресурсов (файл, офлайн-контекст, онлайн-источник), сохранив побайтово все существующие
режимы.

**Architecture:** окна оценки задают только стадии `steady`; бизнес-сторона берёт точные
границы стадии, ресурсная сторона полные ячейки сетки снимка внутри стадии; обе стороны
идут в существующую `evaluateSharedWindowPolicy` с общими id окон. Снимок с собственными
`windows` вместе со стадиями остаётся ошибкой; онлайн-запрос со стадиями строит снимок без
`windows`.

**Tech Stack:** Kotlin (JVM), kotlinx.serialization, JUnit 5, Gradle; UI Vue/TypeScript
(только регенерация типов).

**Spec:** [ADR 0037](../../adr/0037-stages-with-resource-snapshot.md) (Proposed). Основа:
[ADR 0030](../../adr/0030-load-stages-steady-window.md), [ADR 0005](../../adr/0005-resource-window-sla.md),
[ADR 0012](../../adr/0012-auto-window-recognized-period.md), [ADR 0009](../../adr/0009-explicit-capacity-stages.md),
[ADR 0018](../../adr/0018-policy-platform-rules-small-samples.md), [ADR 0032](../../adr/0032-cli-baseline.md).

Решение владельца 2026-11-19: стадии должны работать вместе со снимком ресурсов;
реализация начинается, не дожидаясь перевода ADR 0037 в Accepted. На вопросы в конце
плана ответа владельца пока нет: PR A исполняет рекомендации ADR (раздел ADR «Вопросы
владельцу: исполнение по рекомендации»), каждый пункт ждёт слова владельца.

Существующие тесты не редактируются и не удаляются, только добавляются новые (поручение
2026-11-19). Там, где задачи ниже говорят «замена теста» или «строки в существующем
тесте», вместо этого добавляется новый тест; существующий тест остаётся как есть, пока
его ожидание верно для кода PR.

## Блок AGENTS.md

```text
REQUESTED:
  Снять запрет ADR 0030 R3 на сочетание стадий со снимком ресурсов и с онлайн-источником
  (решение владельца 2026-11-19); способ задаёт ADR 0037.

REQUIRED TO ACHIEVE IT:
  - core/LoadStages.kt: resolveStageResourceWindows (полные ячейки внутри стадии, отказ
    STAGE_WINDOW_NO_FULL_CELLS, признак неполного покрытия); два необязательных поля
    StageBindingStage (resource_from_epoch_ms, resource_to_epoch_ms) и параметр
    stageBindingEvidence.
  - core/ResourceSnapshot.kt: параметр resourceBindingEvidence для mode = declared_stages.
  - core/AnalysisService.kt: суженные проверки (снимок с windows, корреляции, тренд),
    третья ветка оценки «стадии и снимок», накопитель окон по окнам стадий.
  - core/AnalysisResult.kt: window-policy-evaluation один раз в modules.
  - jobs/AnalysisJobs.kt: диагностики новых кодов, текст STAGES_RESOURCES_CONFLICT,
    удаление ветки STAGES_SOURCE_CONFLICT.
  - cli/CommandLine.kt, web/JobRoutes.kt: ранние проверки по ADR 0037.
  - sources/SourceConfig.kt, sources/PromqlSource.kt, sources/SourceAnalysis.kt: снимок
    без windows при стадиях, снятие STAGES_SOURCE_CONFLICT.
  - ui/src/types.stage-items.generated.ts: регенерация (два поля).
  - Тесты, характеризационные фикстуры, документация, ADR 0030 (пометка), changelog.d.

NOT REQUIRED:
  - Стадии с планом ёмкости, корреляций, тренда (остаются отказом).
  - Правка гигиены NO_VERDICT/NO_POLICY (ADR 0030 R4, бэклог).
  - Новые поля identity, новый модуль identity, правка semanticKey/comparisonSemanticKey.
  - Изменения resource-snapshot.v1, policy.v1, load-stages.v1, source-request.*,
    capacity-plan.v1, analysis-result.v1 (схемы), CanonicalJson.kt, run.v1.
  - Дизайн UI и отчётов (полосы стадий на ресурсных рядах, лента стадий в обзоре).
  - Предварительная проверка покрытия стадий окном онлайн-запроса до сети.
  - Рефакторинг evaluateSharedWindowPolicy, evaluateResources, накопителей.

EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/core/LoadStages.kt
  src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt
  src/main/kotlin/io/ltverdict/core/AnalysisService.kt
  src/main/kotlin/io/ltverdict/core/AnalysisResult.kt
  src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt
  src/main/kotlin/io/ltverdict/cli/CommandLine.kt
  src/main/kotlin/io/ltverdict/web/JobRoutes.kt
  src/main/kotlin/io/ltverdict/sources/SourceConfig.kt
  src/main/kotlin/io/ltverdict/sources/PromqlSource.kt
  src/main/kotlin/io/ltverdict/sources/SourceAnalysis.kt
  ui/src/types.stage-items.generated.ts
  src/test/kotlin/io/ltverdict/core/StageResourceWindowTest.kt (новый)
  src/test/kotlin/io/ltverdict/core/StageResourceCrossVersionTest.kt (новый)
  src/test/kotlin/io/ltverdict/core/StageWindowPolicyTest.kt
  src/test/kotlin/io/ltverdict/core/LoadStagesCompatibilityTest.kt
  src/test/kotlin/io/ltverdict/cli/CommandLineStagesTest.kt
  src/test/kotlin/io/ltverdict/jobs/AnalysisJobsStagesTest.kt
  src/test/kotlin/io/ltverdict/web/LocalApiStagesTest.kt
  src/test/kotlin/io/ltverdict/sources/* (тест снимка без windows; имя файла см. задачу 8)
  fixtures/stages/cross-version/** (новые характеризационные файлы)
  docs/contracts/stages/v1/load-stages.schema.json (только description)
  docs/adr/0030-load-stages-steady-window.md (пометка), docs/adr/0037-*.md (статус, ссылки)
  docs/user/slice-1-local-analysis.md, examples/basic/run.md, README.md (если строка есть)
  changelog.d/stages-with-resources.changed.md
```

Если реализация выходит заметно больше этого списка (например, требуется правка
`evaluateResources` или `WindowMetricsAccumulator`), остановиться и описать причину до
продолжения.

## Global Constraints

- Прогоны без стадий, со стадиями без снимка и со снимком без стадий побайтово прежние:
  `identity.json`, `analysis-result.json`, `analysis_id`, `junit.xml`, `summary.txt`,
  `cli-summary.v1`, HTML. Существующие золотые файлы не перегенерируются.
- Канонический JSON и вычисление хэшей identity не меняются.
- Новых зависимостей production нет.
- Коды: `STAGES_RESOURCES_CONFLICT` (только снимок с непустым `windows`),
  `STAGES_CAPACITY_CONFLICT` (без изменений), `STAGES_CORRELATION_CONFLICT`,
  `STAGES_TREND_CONFLICT`, `STAGE_WINDOW_NO_FULL_CELLS`; причина покрытия
  `STAGE_WINDOW_SNAPSHOT_PARTIAL`; `STAGES_SOURCE_CONFLICT` не выдаётся.
- Порядок проверок со стадиями: ёмкость, снимок с окнами, корреляции, тренд (так в ядре,
  CLI и API).
- `resource_binding.mode` для сочетания: `declared_stages`.
- Проза документов на русском, идентификаторы на английском.
- CI-минуты ограничены: полный `./gradlew check` локально через
  `Invoke-LtvExclusive { .\gradlew.bat check }`, один push.

## Review Focus

1. Стадия `steady` с концом, обрезанным по `runEnd` (R1), при сетке снимка, кончающейся
   раньше `runEnd`: ожидается оценка по пересечению и причина `STAGE_WINDOW_SNAPSHOT_PARTIAL`
   только если обрезано не меньше шага (задача 3, тест «end drift»).
2. Две стадии `steady`, соприкасающиеся на границе внутри одной ячейки сетки: ресурсные окна
   не перекрываются и не делят ячейку (задача 3, тест «touching steady stages»).
3. `INVALID` вход со стадиями и снимком: `stage_binding` нет (R11), `resource-snapshot.json`
   и `load-stages.json` записаны (задача 4).
4. Платформенное SLA-правило с `window_ids` на id стадии `excluded`: `RULE_WINDOW_NOT_FOUND`,
   `NO_VERDICT`, как без снимка (задача 4).
5. Повторная отправка тех же входов возвращает сохранённый каталог; тот же JTL и снимок без
   стадий даёт прежний `analysis_id` (задача 1 и задача 4).

---

## Находки (код на `origin/main` 24785cc8)

| № | Место | Что важно |
| --- | --- | --- |
| F1 | `src/main/kotlin/io/ltverdict/cli/CommandLine.kt:251-256` | ранние отказы `STAGES_CAPACITY_CONFLICT`, `STAGES_RESOURCES_CONFLICT`, `STAGES_SOURCE_CONFLICT` до чтения файлов; политика из stdin читается раньше снимка (`:275-277`) |
| F2 | `src/main/kotlin/io/ltverdict/web/JobRoutes.kt:443-454`, `:584-588` | те же отказы для частей `capacity_plan`, `resource_snapshot`, `source_request` через `stagesConflict` (`422 INVALID_STAGES`) |
| F3 | `src/main/kotlin/io/ltverdict/core/AnalysisService.kt:81-85` | ядро: `require(capacity == null)`, `require(resources == null)` |
| F4 | `src/main/kotlin/io/ltverdict/sources/SourceAnalysis.kt:121-122` | `require(request.stages == null) { "STAGES_SOURCE_CONFLICT" }` до обращения к источнику |
| F5 | `src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt:241-251` | отображение кодов ядра в `Diagnostic` задания; неотображённый код становится `ANALYSIS_FAILED` (`:270`) |
| F6 | `AnalysisService.kt:312-319` | `resourceWindows` из снимка вычисляется до `stageResolution`; при обоих входах нужен иной порядок |
| F7 | `AnalysisService.kt:324` | `(resourceWindows ?: stageResolution?.windows)`: при обоих входах бизнес-метрики молча взяли бы окна снимка; при стадиях должны быть окна стадий |
| F8 | `AnalysisService.kt:425-467` | ветка `resources == null` (без снимка или стадии без снимка) и ветка снимка; сочетанию нужна третья ветка |
| F9 | `AnalysisService.kt:265` | удерживаемые транзакции собираются при `resources != null \|\| stages != null`: для сочетания правки нет |
| F10 | `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt:42`, `:47` | обе ветки добавляют `window-policy-evaluation`: при сочетании модуль попал бы дважды |
| F11 | `AnalysisResult.kt:66-68`, `:85-86`, `:105-109`, `:126` | поля снимка и стадий в identity независимы и условны; новых полей не нужно |
| F12 | `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:670-681`, `src/main/kotlin/io/ltverdict/core/RunComparison.kt:316-327` | ключ: `analysis_mode`, `SEMANTIC_FIELDS` (включая `modules`, `input_versions`, `limits`), `resource_arm`, `load_stages_sha256`; сочетание отличимо без правки |
| F13 | `src/main/kotlin/io/ltverdict/core/LoadStages.kt:111-144` | окна стадий: `runStart + offset`, конец `steady` обрезается по `runEnd` |
| F14 | `LoadStages.kt:152-165` | `stageResourceSide` (R4) только без снимка; не трогается |
| F15 | `LoadStages.kt:172-202`, `:213-238` | `stageBindingEvidence`, `StageBindingStage` с необязательным `clippedToRunEnd`; `ITEM_JSON` с `encodeDefaults = false` (`AnalysisItems.kt:30-35`) не пишет `null` по умолчанию |
| F16 | `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt:155-196` | `resolveResourceWindows`: явные окна должны лежать в прогоне; неявное окно `run-intersection` по полным ячейкам (`:172-195`), `RESOURCE_WINDOW_NO_FULL_CELLS` |
| F17 | `ResourceSnapshot.kt:198-226`; `src/main/kotlin/io/ltverdict/core/InputItems.kt:25-39` | `resourceBindingEvidence`, `mode` это строка (`run_intersection`/`explicit_windows`) |
| F18 | `ResourceSnapshot.kt:390-400` | окна снимка обязаны лежать на сетке (`WINDOW_NOT_ON_GRID`) |
| F19 | `src/main/kotlin/io/ltverdict/core/ResourceStatistics.kt:29-89`, `:528` | оценка по окнам: ячейки `cellIndex(to) - cellIndex(from)` с округлением вниз; правило с `windowIds` пропускается вне своих окон (`:45`); `RULE_WINDOW_TOO_SHORT` (`:61-62`) |
| F20 | `src/main/kotlin/io/ltverdict/core/WindowPolicy.kt:8-63` | совместная оценка по id окон; `RULE_WINDOW_NOT_FOUND` для `window_ids` бизнес- и платформенных правил (`:50-61`) |
| F21 | `src/main/kotlin/io/ltverdict/core/PlatformRules.kt:23-60`, `:92-130` | разворачивание платформенных правил по снимку от окон не зависит |
| F22 | `src/main/kotlin/io/ltverdict/sources/PromqlSource.kt:370-383`, `SourceAnalysis.kt:583-597`, `src/main/kotlin/io/ltverdict/sources/SourceConfig.kt:131-141` | снимок явного онлайн-окна пишет окно `full`; авто-окно не пишет (`autoDerivedWindow`, используется только в этих двух условиях) |
| F23 | `src/main/kotlin/io/ltverdict/core/RunPeriod.kt:59-60` | распознанный период: минимум старта и максимум конца выборки, как `runStart`/`runEnd` анализа |
| F24 | `src/main/kotlin/io/ltverdict/core/DiagnosticAnalysis.kt:95`, `:744-752`; `LoadStages.kt:167-170` | оба пишут `window-metric-summary-<sha(window_id)>`: коллизия при корреляциях со стадиями |
| F25 | `src/main/kotlin/io/ltverdict/core/TrendPlan.kt:109-116` | тренд связывается только с `snapshot.windows` |
| F26 | `src/main/kotlin/io/ltverdict/core/OpenSearchDiagnosticAdapter.kt:80-88` | контекст OpenSearch копирует окна базового снимка либо пишет пустые |
| F27 | `src/main/kotlin/io/ltverdict/report/StageNotice.kt:121-150`, `ui/src/verdictSummary.ts:130-160` | при обоих элементах читатели берут `stage_binding` первым |
| F28 | `ui/src/App.vue:1162`, `src/main/kotlin/io/ltverdict/report/HtmlReport.kt:33-45`, `ui/src/AnalysisView.vue:53`, `src/main/kotlin/io/ltverdict/core/IncidentNegativeEvidence.kt:45` | ресурсные секции включаются наличием `resource_binding` |
| F29 | `src/test/kotlin/io/ltverdict/core/LoadStagesCompatibilityTest.kt:47-60` | закреплены только байты identity без стадий; байты identity и результата режима «только стадии» и режима снимка с платформенными правилами нигде не закреплены как файлы |
| F30 | тесты с ожиданием, меняющимся по решению владельца: `StageWindowPolicyTest.kt:377-430`, `CommandLineStagesTest.kt:97-145`, `AnalysisJobsStagesTest.kt:19-21`, `LocalApiStagesTest.kt:136-147` | ожидания `STAGES_CAPACITY_CONFLICT` остаются; тесты `DIAGNOSTIC_RESOURCE_REQUIRED`/`TREND_RESOURCE_REQUIRED` (`AnalysisServiceTest.kt:364`, `LocalApiTest.kt:3079`, `:3189`) стадий не используют и не меняются |
| F31 | `fixtures/stages/ramp-steady-rampdown.jtl` | первый старт `1767225600000`, последний конец `1767225719800`; `ramp-steady-down.json`: `steady` 40000-100000 мс |

## Разбиение на PR

| PR | Ветка | Задачи | Содержание |
| --- | --- | --- | --- |
| 0 | `docs/stages-with-resources-plan` | — | ADR 0037 (Proposed) и этот план |
| A | `feat/stages-with-resources-core` | 1-5 | ядро: характеризация, identity, ресурсные окна стадий, сочетание в `AnalysisService`, суженные отказы, задания, типы UI |
| B | `feat/stages-with-resources-inputs` | 6-9 | CLI, API, онлайн-источник, документация, ADR 0030, changelog; статус ADR 0037 по слову владельца |

PR A меняет поведение ядра без входов CLI и API (они ещё отвергают сочетание раньше ядра),
поэтому А можно влить отдельно. PR B идёт после A.

---

### Task 1: Характеризация байтов существующих режимов (до изменения кода)

**Files:**

- Create: `src/test/kotlin/io/ltverdict/core/StageResourceCrossVersionTest.kt`
- Create: `fixtures/stages/cross-version/stages-only/{identity.json,analysis-result.json}`,
  `fixtures/stages/cross-version/snapshot-only/{identity.json,analysis-result.json}`,
  `fixtures/stages/cross-version/snapshot-platform/{identity.json,analysis-result.json}`,
  `fixtures/stages/cross-version/snapshot-15s.json` (снимок без `windows`, шаг 15000,
  старт 1767225600000, 8 точек, `load_input_sha256` фикстуры JTL, ряд
  `openshift_container_cpu_limit_ratio` сервиса `orders`, `role = system`,
  `aggregation = interval_mean`, `unit = ratio`),
  `fixtures/stages/cross-version/policy-platform.json` (бизнес-правило p95 по `overall` и
  платформенное SLA-правило CPU `gt 0.40` с `platform_coverage`; ряд покрытия
  `openshift_unavailable_replicas` добавить в снимок)

**Interfaces:**

- Produces: фикстуры, на которые опираются задачи 2-4; хелпер теста
  `analyzeStored(jtl: Path, policy: Path?, snapshot: Path?, stages: Path?): Pair<ByteArray, ByteArray>`
  (identity, result) поверх `AnalysisService` и `RunBundleStore` во временном каталоге.

- [ ] **Step 1: Сгенерировать фикстуры на неизменённом коде** одним временным запуском
  хелпера (код генерации в тест не попадает), записать байты как есть.
- [ ] **Step 2: Написать тест** `existing modes keep their committed identity and result bytes`:
  для трёх режимов (`stages-only`: JTL, `policy.canonical.json` slice1, `ramp-steady-down.json`;
  `snapshot-only`: JTL, та же политика, `snapshot-15s.json`; `snapshot-platform`: JTL,
  `policy-platform.json`, `snapshot-15s.json`) `assertArrayEquals(fixture, actual)` для
  identity и результата.
- [ ] **Step 3: Запустить**
  Run: `.\gradlew.bat test --tests "io.ltverdict.core.StageResourceCrossVersionTest"`
  Expected: PASS на неизменённом коде (тест характеризационный, падать не должен).
- [ ] **Step 4: Commit** `test(stages): pin identity and result bytes of existing modes`
  (только новый тест и `fixtures/stages/cross-version/**`).

### Task 2: Identity сочетания без дубля модуля

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt:46-47`
- Test: `src/test/kotlin/io/ltverdict/core/LoadStagesCompatibilityTest.kt`

**Interfaces:**

- Produces: `analysisIdentity(..., resources = r, stages = s)` с `modules` из шести id без
  повторов.

- [ ] **Step 1: Failing test** `with stages and a snapshot the identity lists every module once and carries both inputs`:

```kotlin
val ids = document.getValue("modules").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
assertEquals(
    listOf("normalization", "metrics", "policy-evaluation", "resource-statistics", "window-policy-evaluation", "stage-window-evaluation"),
    ids,
)
assertEquals(declared.sha256, document.getValue("load_stages_sha256").jsonPrimitive.content)
assertEquals(snapshot.semanticSha256, document.getValue("resource_snapshot_sha256").jsonPrimitive.content)
assertEquals("load-stages.v1", inputVersions.getValue("stages").jsonPrimitive.content)
assertEquals("resource-snapshot.v1", inputVersions.getValue("resources").jsonPrimitive.content)
```

  и `the combination is not comparable with stages alone nor with the snapshot alone`
  (через `compareTransactions(...).getValue("compatible") == false` в обе стороны; с тем же
  объявлением и снимком `true`).

- [ ] **Step 2: Run** `.\gradlew.bat test --tests "io.ltverdict.core.LoadStagesCompatibilityTest"`
  Expected: FAIL (в `modules` два `window-policy-evaluation`).
- [ ] **Step 3: Implement**: в строке `:47` добавлять каждый из двух id, только если его ещё нет
  в `modules`; комментарий `:46` поправить (снимок больше не исключает стадии, ADR 0037 D6).
- [ ] **Step 4: Run** тот же тест и `StageResourceCrossVersionTest`. Expected: PASS.
- [ ] **Step 5: Commit** `fix(core): list window-policy-evaluation once when stages meet a snapshot`

### Task 3: Ресурсные окна стадий

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/LoadStages.kt` (новая функция после
  `resolveStageWindows`; `StageBindingStage`; `stageBindingEvidence`)
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt:198-226`
- Test: `src/test/kotlin/io/ltverdict/core/StageResourceWindowTest.kt`

**Interfaces:**

- Produces:
  - `internal data class StageResourceWindows(val windows: List<ResourceWindowV1>, val partial: Boolean)`
  - `internal fun resolveStageResourceWindows(snapshot: ResourceSnapshotV1, stageWindows: List<ResourceWindowV1>): StageResourceWindows`
    — для каждого окна по формуле ADR 0037 D2, те же id и порядок; при пустом окне
    `IllegalArgumentException("STAGE_WINDOW_NO_FULL_CELLS")`; `partial = true`, если у
    какого-либо окна `resource_from - from >= step` или `to - resource_to >= step`.
  - `internal const val STAGE_WINDOW_SNAPSHOT_PARTIAL = "STAGE_WINDOW_SNAPSHOT_PARTIAL"`
  - `StageBindingStage` += `@SerialName("resource_from_epoch_ms") val resourceFromEpochMs: Long? = null`,
    `@SerialName("resource_to_epoch_ms") val resourceToEpochMs: Long? = null` (после
    `clippedToRunEnd`).
  - `stageBindingEvidence(declarationSha256, resolved, runStart, runEnd, resourceWindows: List<ResourceWindowV1>? = null)`
    — поля заполняются только у `steady` и только при `resourceWindows != null`, по id.
  - `resourceBindingEvidence(snapshot, windows, runStart, runEnd, declaredStages: Boolean = false)`
    — при `true`: `mode = "declared_stages"`, `dropped_*` равны 0.

- [ ] **Step 1: Failing tests** (сетка старт `T0 = 1767225600000`, шаг 15000, 8 точек):
  - `inner full cells of a steady stage`: окно `[T0+40000, T0+100000)` даёт
    `[T0+45000, T0+90000)`, `partial == false`;
  - `a stage on the grid keeps its bounds`: `[T0+45000, T0+90000)` без изменений;
  - `a stage shorter than one full cell is refused`: `[T0+16000, T0+29000)` даёт
    `STAGE_WINDOW_NO_FULL_CELLS`; окно целиком вне сетки тоже;
  - `end drift past the grid end within one step is not partial`: сетка 8 точек,
    окно `[T0+45000, T0+119800)` даёт `[T0+45000, T0+105000)`, `partial == false`
    (`119800 - 105000 < 15000`);
  - `a grid that starts inside the stage is partial`: сетка с `T0+60000`, окно
    `[T0+40000, T0+100000)` даёт `[T0+60000, T0+90000)`, `partial == true`;
  - `touching steady stages do not share a cell`: `[T0+40000, T0+70000)` и
    `[T0+70000, T0+100000)` дают `[T0+45000, T0+60000)` и `[T0+75000, T0+90000)`;
  - `the stage binding carries resource bounds only with a snapshot`: без
    `resourceWindows` JSON `stage_binding` равен прежнему (сравнение с
    `fixtures/stages/stage-binding.sample.json`), с ними у `steady` два поля, у `excluded`
    нет;
  - `the resource binding says declared_stages`.
- [ ] **Step 2: Run** `.\gradlew.bat test --tests "io.ltverdict.core.StageResourceWindowTest"`
  Expected: FAIL (функции нет).
- [ ] **Step 3: Implement** в указанных файлах; округление вверх и вниз целочисленное
  (`Math.floorDiv`, без `Double`).
- [ ] **Step 4: Regenerate UI types** (`ui/src/types.stage-items.generated.ts`):
  `$env:LTV_UPDATE_GENERATED_TYPES = '1'; .\gradlew.bat test --tests "io.ltverdict.core.TypeScriptGeneratorStageItemsTest"; Remove-Item Env:LTV_UPDATE_GENERATED_TYPES`,
  затем без переменной
  `.\gradlew.bat test --tests "io.ltverdict.core.StageResourceWindowTest" --tests "io.ltverdict.core.TypeScriptGeneratorStageItemsTest"`.
  Expected: PASS; в diff TS только два необязательных поля `StageBindingStage`.
- [ ] **Step 5: Commit** `feat(core): resource windows of steady stages on the snapshot grid`

### Task 4: Сочетание стадий и снимка в `AnalysisService`, суженные отказы

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisService.kt:81-85`, `:310-333`, `:425-467`
- Modify: `src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt:241-251`
- Test: `src/test/kotlin/io/ltverdict/core/StageWindowPolicyTest.kt`,
  `src/test/kotlin/io/ltverdict/jobs/AnalysisJobsStagesTest.kt`,
  `src/test/kotlin/io/ltverdict/core/StageResourceCrossVersionTest.kt`

**Interfaces:**

- Consumes: задача 3 (`resolveStageResourceWindows`, `stageBindingEvidence(..., resourceWindows)`,
  `resourceBindingEvidence(..., declaredStages = true)`), задача 2.
- Produces: результат сочетания по ADR 0037 D5 (порядок evidence ниже).

- [ ] **Step 1: Failing tests** в `StageWindowPolicyTest` (снимок
  `fixtures/stages/cross-version/snapshot-15s.json`, стадии `ramp-steady-down.json`):
  - `stages with a snapshot evaluate business rules on the stage and resource rules on its full cells`:
    типы evidence по порядку
    `metric_summary, metric_summary, stage_binding, resource_binding, resource_summary..., resource_policy_check..., policy_check..., window_policy_summary, window_metric_summary`;
    `window_policy_summary.from_epoch_ms == T0+40000`, `to == T0+100000`;
    `resource_summary.from_epoch_ms == T0+45000`, `to == T0+90000`;
    `stage_binding.stages[steady].resource_from_epoch_ms == T0+45000`;
    `resource_binding.mode == "declared_stages"`; p95 окна равен 194 (ручной расчёт ADR 0030);
  - `a platform sla rule is checked on the steady window when a snapshot is given`:
    `window_policy_summary.resource_verdict` равен `PASS` при значениях ряда 0.2 и `FAIL` при
    0.9 в ячейках `[T0+45000, T0+90000)`, нет `RESOURCE_SNAPSHOT_REQUIRED`; значение 0.9 в
    ячейке `[T0+30000, T0+45000)` (разгон) вердикт не меняет;
  - `a platform rule bound to an excluded stage is not found`: `rule_window_check`,
    `NO_VERDICT`;
  - `a snapshot that starts inside the steady stage is partial`: причина
    `STAGE_WINDOW_SNAPSHOT_PARTIAL`, `analysis_coverage == INCOMPLETE`, вердикт по данным;
  - `an invalid input with stages and a snapshot has no stage binding but keeps both files`;
  - `the same inputs return the stored analysis`;
  - замена теста `stages with a resource snapshot, a capacity plan or an online source are a conflict`
    (`:377-430`) на `stages with a capacity plan, a snapshot with windows, a correlation plan or a trend plan are refused`:
    ожидания `STAGES_CAPACITY_CONFLICT` (без изменений), `STAGES_RESOURCES_CONFLICT` для
    `docs/contracts/resources/v1/examples/valid/basic.json` (в нём есть `windows`),
    `STAGES_CORRELATION_CONFLICT`, `STAGES_TREND_CONFLICT`; утверждения про
    `STAGES_SOURCE_CONFLICT` переносятся в задачу 8.
  - в `StageResourceCrossVersionTest`: `the combination has its own identity and leaves the three modes unchanged`
    (три закреплённых режима побайтово прежние, `analysis_id` сочетания отличается от каждого).
  - в `AnalysisJobsStagesTest`: строки отображения для `STAGE_WINDOW_NO_FULL_CELLS`,
    `STAGES_CORRELATION_CONFLICT`, `STAGES_TREND_CONFLICT`; строка `STAGES_SOURCE_CONFLICT`
    остаётся до задачи 8 (код ещё выдаётся `SourceAnalysis.kt:122`).
  - `incidents of the combination reference stage windows`: на результате сочетания с
    FAIL ресурсного SLA (значение 0.9 в окне) все `window_id` атомов `incidents` равны id
    стадий `steady`, негатива `RESOURCE_DATA_NOT_PROVIDED` нет (его даёт отсутствие
    `resource_binding`, `IncidentNegativeEvidence.kt:165-172`); границы бизнес-атома это
    границы `window_policy_summary` (`IncidentSynthesis.kt:147`, точные границы стадии),
    а атом ресурсного нарушения берёт нарушенные ячейки своей находки (поправлено после
    PR A: исходный текст плана приписывал границы стадии обоим атомам).
- [ ] **Step 2: Run**
  `.\gradlew.bat test --tests "io.ltverdict.core.StageWindowPolicyTest" --tests "io.ltverdict.jobs.AnalysisJobsStagesTest" --tests "io.ltverdict.core.StageResourceCrossVersionTest"`
  Expected: FAIL (`STAGES_RESOURCES_CONFLICT` на любой снимок, коды не отображены).
- [ ] **Step 3: Implement**
  - `:81-85`: при `stages != null` по порядку `require(capacity == null) {"STAGES_CAPACITY_CONFLICT"}`,
    `require(resources?.snapshot?.windows.isNullOrEmpty()) {"STAGES_RESOURCES_CONFLICT"}`,
    `require(diagnostics == null) {"STAGES_CORRELATION_CONFLICT"}`,
    `require(trend == null) {"STAGES_TREND_CONFLICT"}`; комментарий ссылается на ADR 0037.
  - `:310-319`: `stageResolution` вычисляется до `resourceWindows`; при обоих входах
    `resourceWindows = resolveStageResourceWindows(snapshot, stageResolution.windows)` (и
    сохранить `partial`), иначе как сейчас.
  - `:324`: накопитель окон по `stageResolution?.windows ?: resourceWindows`.
  - `:425-467`: третья ветка при `resources != null && stageResolution != null`:
    `evaluateResources(snapshot, resourceWindows, checkCancelled, expandPlatformRules(...))`,
    к его `evidence` спереди `stageBindingEvidence(..., resourceWindows)` и
    `resourceBindingEvidence(..., declaredStages = true)`, к `coverageReasons`
    `STAGE_WINDOW_SNAPSHOT_PARTIAL` при `partial`; затем
    `evaluateSharedWindowPolicy(policy, validity, metrics, windowMetrics, resourceEvaluation, stageResolution.windows, diagnostics)`
    и `stageWindowMetricSummaries(stageResolution.windows, windowMetrics)` в конце, как в
    ветке стадий без снимка.
  - `AnalysisJobs.kt`: три новых `Diagnostic`; текст `STAGES_RESOURCES_CONFLICT`:
    `"Stages cannot be combined with a resource snapshot that declares its own windows"`;
    ветка `STAGES_SOURCE_CONFLICT` остаётся (удаляется в задаче 8 вместе с источником кода).
- [ ] **Step 4: Run** те же тесты, затем все тесты ядра стадий и снимка:
  `.\gradlew.bat test --tests "io.ltverdict.core.*" --tests "io.ltverdict.jobs.*"`
  Expected: PASS, существующие золотые тесты без правок.
- [ ] **Step 5: Commit** `feat(core): evaluate declared stages together with a resource snapshot`

### Task 5: Проверка PR A

- [ ] Run `Invoke-LtvExclusive { .\gradlew.bat check }`. Expected: BUILD SUCCESSFUL.
- [ ] `git diff origin/main --stat`: только файлы задач 1-4; `git status --short` чистый.
- [ ] Фрагмент `changelog.d/stages-with-resources-core.changed.md` (ядро принимает
  сочетание; входы CLI и API пока отвергают его) либо `Documentation impact: none` в
  описании PR с причиной (CLI и API ещё не дают сочетания пользователю). Рекомендуется
  второе: пользователь поведения не видит до PR B.
- [ ] Ревью: `superpowers:requesting-code-review`, модель уровня ревью публичных контрактов
  (`gpt-5.6-sol`, effort `max`, или ближайшая сильнее).

### Task 6: CLI

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/cli/CommandLine.kt:251-256`, `:277`
- Test: `src/test/kotlin/io/ltverdict/cli/CommandLineStagesTest.kt`

- [ ] **Step 1: Failing tests**:
  - `stages with a snapshot without windows analyze and print the steady scope`: снимок без
    `windows` для `ramp-steady-rampdown.jtl` (байты из задачи 1), `--out-dir`, выход 0 или 2
    по политике; в `--out-dir` есть `report.html`, `junit.xml`, `summary.txt`,
    `summary.json`, `result.json`; `summary.txt` содержит `scope: steady window (steady)`,
    `report.html` содержит «Область вердикта» и секцию «Привязка ресурсов»; в результате
    `resource_binding.mode == declared_stages` (отчёты впервые видят оба элемента);
  - замена теста `:97-118`: `--resources basic.json` (с `windows`) даёт выход 4 и
    `STAGES_RESOURCES_CONFLICT`, каталог данных не создан; `--capacity` даёт
    `STAGES_CAPACITY_CONFLICT` до чтения; `--correlation` и `--trend` дают
    `STAGES_CORRELATION_CONFLICT` и `STAGES_TREND_CONFLICT` до чтения файлов (файлы не
    существуют); `--source` с несуществующими файлами больше не даёт
    `STAGES_SOURCE_CONFLICT` (ожидается ошибка чтения файла источника);
  - тест `:120-145`: отказ до чтения stdin проверяется через `--capacity`; для снимка с
    окнами проверяется только «артефакт не записан» (снимок читается после политики).
- [ ] **Step 2: Run** `.\gradlew.bat test --tests "io.ltverdict.cli.CommandLineStagesTest"`. Expected: FAIL.
- [ ] **Step 3: Implement**: в блоке `:252-256` оставить ёмкость, добавить
  `diagnosticsPath` и `trendPath`, убрать `resourcesPath` и `sourcePath`; после
  `readResources` (`:277`) отказ `STAGES_RESOURCES_CONFLICT`, если стадии заданы и
  `windows` снимка не пуст.
- [ ] **Step 4: Run** тот же тест и `--tests "io.ltverdict.cli.*"`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(cli): allow --stages with --resources and --source`

### Task 7: API

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/JobRoutes.kt:443-454`
- Test: `src/test/kotlin/io/ltverdict/web/LocalApiStagesTest.kt:130-150`

- [ ] **Step 1: Failing tests**: задание со `stages` и `resource_snapshot` без окон
  завершается `SUCCEEDED`, в результате `stage_binding` и `resource_binding`; с
  `resource_snapshot` с окнами `422 INVALID_STAGES`, `details[0] = {code: STAGES_RESOURCES_CONFLICT, json_pointer: /resource_snapshot}`;
  `correlation_plan` даёт `STAGES_CORRELATION_CONFLICT` `/correlation_plan`, `trend_plan`
  даёт `STAGES_TREND_CONFLICT` `/trend_plan`; `capacity_plan` как раньше; со
  `source_request` нет `STAGES_SOURCE_CONFLICT`.
- [ ] **Step 2: Run** `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiStagesTest"`. Expected: FAIL.
- [ ] **Step 3: Implement**: порядок ёмкость, снимок с окнами (если `job.resources` валиден и
  его `windows` не пуст), корреляции (`diagnosticsSeen`), тренд (`trendSeen`); ветку
  источника убрать.
- [ ] **Step 4: Run** тот же тест и `--tests "io.ltverdict.web.*"`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(api): accept stages with a resource snapshot or an online source`

### Task 8: Онлайн-источник

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/sources/SourceConfig.kt:131-141` (поле
  `stagesDeclared: Boolean = false` в `SourceRequest`)
- Modify: `src/main/kotlin/io/ltverdict/sources/PromqlSource.kt:371`,
  `src/main/kotlin/io/ltverdict/sources/SourceAnalysis.kt:583` (условие
  `!request.autoDerivedWindow && !request.stagesDeclared`)
- Modify: `SourceAnalysis.kt:121-122` (удалить `STAGES_SOURCE_CONFLICT`), `resolveWindow`
  (оба `SourceRequest(...)` получают `stagesDeclared = request.stages != null`)
- Modify: `src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt:250-251` (удалить ветку
  `STAGES_SOURCE_CONFLICT`) и строку `STAGES_SOURCE_CONFLICT` в
  `src/test/kotlin/io/ltverdict/jobs/AnalysisJobsStagesTest.kt:21` (в том же коммите, что и
  удаление `require`, чтобы между PR код не превращался в `ANALYSIS_FAILED`)
- Test: `src/test/kotlin/io/ltverdict/sources/SourceAnalysisTest.kt` (существующие тесты
  `analyzeWithSources`; там же либо в `InputEvidenceRunSnapshotTest.kt` взять готовый
  фальшивый HTTP-источник), плюс перенос утверждений из `StageWindowPolicyTest.kt:410-428`.

- [ ] **Step 1: Failing tests**:
  - `an explicit online window with stages builds a snapshot without windows`: сохранённый
    `resource-snapshot.json` без ключа `windows`, `source_summary` прежний по составу
    полей, результат с `stage_binding` и `resource_binding.mode == declared_stages`;
  - `an explicit online window without stages keeps the full window`: байты снимка прежние
    (ключ `windows` с `id = full`);
  - `an auto online window with stages covers every steady stage`: нет
    `STAGE_WINDOW_SNAPSHOT_PARTIAL`;
  - `stages no longer refuse an online request`: без настроенного источника ответ
    `SOURCE_NOT_CONFIGURED`; со снимком и онлайн-запросом `SOURCE_INPUT_CONFLICT`.
- [ ] **Step 2: Run** `.\gradlew.bat test --tests "io.ltverdict.sources.*" --tests "io.ltverdict.core.StageWindowPolicyTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** по списку файлов.
- [ ] **Step 4: Run** те же тесты. Expected: PASS.
- [ ] **Step 5: Commit** `feat(sources): build a snapshot without windows when stages are declared`

### Task 9: Документация, ADR, changelog, проверка PR B

**Files:**

- Modify: `docs/user/slice-1-local-analysis.md` (раздел «Стадии нагрузки и вердикт по окну
  steady», строки 2256-2289: сочетания, новые коды, смена кода для стадий с
  `--correlation`/`--trend` без снимка, правило полных ячеек, пример со снимком и с
  `--source`; там же, где описаны поля `resource_binding` и `stage_binding`
  (`grep -n "dropped_leading_millis\|clipped_to_run_end" docs/user/slice-1-local-analysis.md`),
  значение `mode = declared_stages` и поля `resource_from_epoch_ms`, `resource_to_epoch_ms`)
- Modify: `examples/basic/run.md:69-70`
- Modify: `docs/contracts/stages/v1/load-stages.schema.json` (только `description` у
  `stages`: при снимке ресурсная сторона берёт полные ячейки сетки внутри стадии)
- Modify: `docs/adr/0030-load-stages-steady-window.md` (у R3, R5 и таблицы
  «Взаимодействие с другими режимами» пометка «изменено ADR 0037»; текст не переписывается)
- Modify: `docs/adr/0037-stages-with-resource-snapshot.md` (статус Accepted только по слову
  владельца; раздел «Реализовано» с номерами PR)
- Create: `changelog.d/stages-with-resources.changed.md`

- [ ] **Step 1**: тексты по списку; во фрагменте changelog: что стало можно, какие коды
  появились и исчезли, что не меняется (существующие анализы, схемы), ограничения (ёмкость,
  корреляции, тренд; проверка покрытия после онлайн-выборки).
- [ ] **Step 2: Run** `python tools/changelog_assemble.py --check` и
  `Invoke-LtvExclusive { .\gradlew.bat check }`. Expected: оба зелёные.
- [ ] **Step 3**: `git diff origin/main --stat` только файлы задач 6-9; поиск секретов
  (`git diff origin/main | Select-String -Pattern "password|token|secret"` без находок).
- [ ] **Step 4: Commit** `docs(stages): stages with a resource snapshot and an online source`
- [ ] **Step 5**: ревью уровня публичных контрактов; push и PR только по разрешению владельца.

## Кросс-версионная проверка

Задача 1 закрепляет на неизменённом коде байты `identity.json` и `analysis-result.json` трёх
режимов: «только стадии», «только снимок», «снимок с платформенными правилами». После
задач 2-8 тот же тест проходит без правки фикстур; дополнительно не меняются существующие
`LoadStagesCompatibilityTest` (identity без стадий), `AnalysisResultGoldenTest`,
`TypedBoundaryGoldenBytesTest`, `PlainReportsGoldenTest` (отчёты прогонов со стадиями),
`JtlGoldenTest`, `GatlingGoldenTest`. Сохранённые каталоги анализа ядро не переписывает:
`readAnalysis` по прежнему `analysis_id` возвращает их как есть (задача 4, тест повторной
отправки).

## Риски

- **Тихая смена окон бизнес-метрик (F7).** Если в `:324` оставить приоритет окон снимка,
  метрики окна посчитаются по сетке; тест p95 = 194 в задаче 4 это ловит.
- **Неполное покрытие сетки.** При выбранном варианте оценка идёт по пересечению, и
  `PASS` по ресурсам может опираться на часть окна `steady`; смягчено причиной
  `STAGE_WINDOW_SNAPSHOT_PARTIAL` и полями `resource_*_epoch_ms` (вопрос 1).
- **Напрасная онлайн-выборка.** Явное окно, не покрывающее стадию, выясняется после
  выборки (`STAGE_WINDOW_NO_FULL_CELLS` или частичное покрытие).
- **Повтор сохранённого снимка с окном `full`.** Отвергается (D3, вопрос 2).
- **Клиенты, ждавшие отказа.** Скрипты, рассчитывавшие на `STAGES_RESOURCES_CONFLICT` для
  любого снимка или на `STAGES_SOURCE_CONFLICT`, получат анализ; запись в changelog.
- **Часы.** Смещение часов генератора и мониторинга ядро не проверяет
  (`clock_alignment = not_verified_by_core`), а окно стадии теперь режет ресурсный ряд;
  ошибка смещения переносит границы на ресурсной стороне. Как и в ADR 0005, только
  публикуется.
- **Инциденты.** `IncidentNegativeEvidence.kt:45`, `:165-172`: `resource_binding` только
  убирает негатив «ресурсных данных нет»; окна инцидентов берутся из
  `window_policy_summary` по `window_id` (`IncidentNegativeEvidence.kt:47-58`,
  `IncidentSynthesis.kt:147`), ресурсные проверки сочетания несут те же id стадий.
  Границы стадии получает только бизнес-атом; атом ресурсного нарушения берёт
  нарушенные ячейки своей находки (внутри ресурсного окна). Закреплено тестом в
  задаче 4 (поправлено после PR A: прежний текст приписывал атому ресурсного нарушения
  границы стадии).
- **Смена кода для существующих сочетаний.** `--stages` с `--correlation` или `--trend` без
  снимка сегодня даёт `DIAGNOSTIC_RESOURCE_REQUIRED`/`TREND_RESOURCE_REQUIRED` (ADR 0030,
  таблица, строка 5), после изменения `STAGES_CORRELATION_CONFLICT`/`STAGES_TREND_CONFLICT`.
  Существующие тесты этих кодов (`AnalysisServiceTest.kt:364`, `LocalApiTest.kt:3079`,
  `:3189`) стадий не используют и не меняются.

## Вопросы владельцу

Статус 2026-11-19: ответа нет; все три пункта исполнены по рекомендации и ждут слова
владельца (ADR 0037, раздел «Вопросы владельцу: исполнение по рекомендации»). По пункту 3
ожидаемый следующий шаг: план тренда, привязанный к id стадий (целевой UI показывает тренды
внутри стадии `steady`, например «Soak», рядом с лентой стадий).

1. **Сетка снимка покрывает окно `steady` не целиком** (сверх обрезки неполных ячеек на
   краях). Рекомендация: оценивать по пересечению, информационная причина
   `STAGE_WINDOW_SNAPSHOT_PARTIAL` (`INCOMPLETE`, вердикт по данным). Альтернатива:
   отказ анализа новым кодом (строже, но падает на офлайн-снимке, выгруженном впритык,
   при дрейфе конца прогона, который R1 намеренно прощает).
2. **Сохранённый снимок онлайн-выборки с явным окном** (окно `full`) нельзя переиграть
   со стадиями через `--resources` (D3). Рекомендация: принять; путь для старого прогона это
   повторная онлайн-выборка со стадиями, если данные ещё хранятся в мониторинге, либо
   снимок без `windows`. Альтернатива: разрешить снимок, чьё единственное окно совпадает со
   всей сеткой, как «окон нет» (особый случай, решение владельца).
3. **Тренд и корреляции со стадиями** откладываются (D9) с явными отказами. Подтвердить,
   что для первых прогонов с платформой достаточно SLA по окну `steady` и pod-view без
   трендов памяти.

## Self-review

- Покрытие ADR: D1-D5 задачи 3-4; D6 задачи 1-2, 4; D7 задача 8; D8 и D9 задачи 4, 6, 7;
  D10 не требует кода (тест R4 без снимка остаётся); D11 задачи 3, 9; D12 вне кода.
- Типы: `resolveStageResourceWindows`, `StageResourceWindows`, `stageBindingEvidence(..., resourceWindows)`,
  `resourceBindingEvidence(..., declaredStages)`, `SourceRequest.stagesDeclared` одинаково
  названы в задачах 3, 4, 8.

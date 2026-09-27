# Auto Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Выборка источника за период, распознанный из байтов нагрузки, через новый контракт `source-request.v3`, с fail-closed отказами и публикуемым provenance.

**Architecture:** Отдельный дешёвый проход распознавания перед внешней выборкой; распознанный период сохраняется run-уровневым артефактом `run-period.json` по прецеденту `baseline.json`. `v1` не изменяется, `v2` получает опубликованную схему, `v3` несёт окно явно или как вывод из периода. Проверка `step_ms` ужесточается до выполнения HTTP-запросов.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization JSON, Ktor LocalApi, Vue 3 + TS, Ajv, Playwright. Новых production-зависимостей нет.

**Spec:** `docs/superpowers/specs/2026-09-27-evidence-triage-auto-window-design.md`
**ADR:** `docs/adr/0012-auto-window-recognized-period.md`

## Global Constraints

- Opt-in: без запроса `v3` поведение, байты результата и identity не меняются.
- Распознавание не выполняет внешних запросов и не строит метрики.
- Fail-closed: период не распознан, найден длительный простой или число ячеек вне диапазона — явный reason code и требование явного окна. Угадывание границ теста запрещено.
- Распознанный период, заявленный и фактически применённый margin, число и длительность простоев публикуются в `source_summary`.
- Смещение часов не компенсируется и не оценивается; margin — единственная заявленная защита.
- Проход распознавания не выполняется в event loop HTTP-сервера.
- Повторное открытие сохранённого прогона не выполняет внешних запросов и не повторяет распознавание.
- Один Gradle process; не запускать `clean` (в `build/` лежат первичные корпуса приёмки).
- Секреты и raw-тела ответов не выводятся в provenance и сообщения об ошибках.
- Документация и CHANGELOG обновляются в том же наборе коммитов.

## Task 1: Схема `source-request.v2` и поправка документации headline selection

**Files:** Create `docs/contracts/sources/v2/source-request.schema.json`,
`docs/contracts/sources/v2/examples/valid/two-profiles.json`,
`docs/contracts/sources/v2/examples/invalid/unknown-field.json`,
`docs/contracts/sources/v2/examples/invalid/duplicate-profile.json`.
Modify `ui/scripts/verify-policy-schema.mjs`,
`docs/contracts/diagnostics/v1/correlation-headline-selection.md`.

**Шаги:**

- [x] Прочитать фактическую валидацию `v2` в `sources/SourceConfig.kt` (ветка `v2`, сортировка и уникальность идентификаторов, предел числа профилей, предел размера файла) и отразить её в схеме без расхождений.
- [x] Схема: `additionalProperties: false`, `required` из фактического набора ключей, `profile_ids` с `minItems`/`maxItems`/`uniqueItems`, границы `start_epoch_ms`/`end_epoch_ms`/`step_ms` как в рантайме, `description` для ограничений, которые рантайм проверяет сверх схемы (байты на идентификатор, предел размера файла).
- [x] Подключить компиляцию схемы и кейсы в `ui/scripts/verify-policy-schema.mjs` по образцу существующих блоков.
- [x] Исправить утверждение, что `FAMILY_SIZE_UNSUPPORTED` покрывает пустую семью: пустой вход возвращает пустой список раньше.
- [x] Проверка: `npm --prefix ui run test:contracts`.

## Task 2: Fail-fast проверка `step_ms`

**Files:** Modify `src/main/kotlin/io/ltverdict/sources/SourceConfig.kt`,
`src/test/kotlin/io/ltverdict/sources/SourceConfigTest.kt`,
`docs/contracts/sources/v1/source-request.schema.json` (только `description` и границы, если они расходятся с рантаймом).

**Шаги:**

- [x] Добавить к существующей проверке шага требование целых секунд в диапазоне 1..60, то есть `step in 1_000..60_000` и `step % 1_000 == 0`, до любых внешних запросов.
- [x] Подтвердить тестами, что множество успешных сценариев не изменилось: шаг 1500 мс, 61 000 мс и 999 мс отклоняются на этапе валидации запроса, а не после выборки.
- [x] Синхронизировать описание и границы в опубликованной схеме `v1`, если они допускают значения, которые рантайм всегда отклонял; семантику версии не менять. Границы `step_ms` синхронизированы также в схеме `v2`, поскольку ужесточение действует для всех версий запроса.
- [x] Проверка: `gradlew --offline --no-daemon test -x npmCi --no-parallel --tests "*SourceConfigTest"`.

## Task 3: Распознавание периода и артефакт `run-period.v1`

**Files:** Create `src/main/kotlin/io/ltverdict/core/RunPeriod.kt`,
`src/test/kotlin/io/ltverdict/core/RunPeriodTest.kt`,
`docs/contracts/run-period/v1/run-period.schema.json`,
`docs/contracts/run-period/v1/examples/valid/basic.json`,
`docs/contracts/run-period/v1/examples/invalid/unknown-field.json`.
Modify `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt`,
`src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`.

**Interfaces:**

```kotlin
internal data class RunPeriodV1(
    val schemaVersion: String,
    val loadInputSha256: String,
    val recognitionMethod: String,
    val firstSampleEpochMillis: Long,
    val lastSampleEpochMillis: Long,
    val longestIdleGapMillis: Long?,
    val idleGapCount: Int,
    val status: String,
)

internal fun recognizeRunPeriod(
    sourceType: SourceType,
    source: Path,
    loadInputSha256: String,
    maxIdleGapMillis: Long,
): RunPeriodV1
```

**Шаги:**

- [x] Прочитать существующую точку входа разбора, используемую первым проходом в `core/AnalysisService.kt`, и использовать её же с лёгким callback, который фиксирует только первый и последний таймстамп и промежутки простоя между секундными бакетами. Метрики не накапливать.
- [x] `recognitionMethod` — версионируемая строка, например `sample-timestamps.v1`. Простой считается по отсутствию сэмплов между соседними секундными бакетами.
- [x] `status` — `RECOGNIZED` либо `UNRECOGNIZED` с причиной; при `INVALID` входе период не выводится и возвращается `UNRECOGNIZED`.
- [x] Хранение: run-уровневый файл `run-period.json` по прецеденту `baseline.json` — запись под operation lock через staging и atomic move, канонические байты, чтение с проверкой схемы, полей и соответствия `load_input_sha256` фактическому хешу `inputs/source.bin`. При несовпадении хеша артефакт считается повреждённым.
- [x] Повторное использование: если артефакт существует и хеш совпадает, распознавание не повторяется.
- [x] Тесты: распознавание на синтетическом JTL и Gatling логе; файл с длительным простоем; `INVALID` вход; идемпотентность повторного чтения; отказ при подменённом хеше.
- [x] Проверка: `gradlew --offline --no-daemon test -x npmCi --no-parallel --tests "*RunPeriodTest" --tests "*RunBundleStoreTest"`.

## Task 4: Контракт `source-request.v3` и вывод окна

**Files:** Create `docs/contracts/sources/v3/source-request.schema.json`,
`docs/contracts/sources/v3/examples/valid/auto-window.json`,
`docs/contracts/sources/v3/examples/valid/explicit-window.json`,
`docs/contracts/sources/v3/examples/invalid/unknown-field.json`,
`docs/contracts/sources/v3/examples/invalid/margin-not-aligned.json`.
Modify `src/main/kotlin/io/ltverdict/sources/SourceConfig.kt`,
`src/test/kotlin/io/ltverdict/sources/SourceConfigTest.kt`,
`ui/scripts/verify-policy-schema.mjs`.

**Шаги:**

- [ ] Разбор `v3`: `profile_ids` от 1 до 16, уникальные, сортируются; `window.origin` равен `auto` либо `explicit`; для `explicit` — `start_epoch_ms`, `end_epoch_ms`, `step_ms`; для `auto` — `step_ms`, `margin_ms`, `max_idle_gap_ms`. Неизвестные поля отклоняются.
- [ ] Ограничения: `step_ms` — целые секунды 1..60; `margin_ms` от 0 до 3 600 000 и кратно `step_ms`; `max_idle_gap_ms` не менее `step_ms` и кратно `step_ms`; итоговое число ячеек от 1 до 100 000; файл до 16 KiB.
- [ ] Вывод окна: период расширяется на margin, нижняя граница обрезается до нуля, границы выравниваются по сетке шага. Фактически применённый margin сохраняется отдельно от заявленного.
- [ ] Reason codes: `AUTO_WINDOW_UNAVAILABLE`, `AUTO_WINDOW_MULTI_TEST_SUSPECTED`, `AUTO_WINDOW_SPAN_UNSUPPORTED`. Каждый возвращается до выполнения внешних запросов.
- [ ] Подключить схему `v3` и негативные кейсы к `ui/scripts/verify-policy-schema.mjs`.
- [ ] Проверка: `gradlew --offline --no-daemon test -x npmCi --no-parallel --tests "*SourceConfigTest"` и `npm --prefix ui run test:contracts`.

## Task 5: Интеграция в выборку, CLI и API

**Files:** Modify `src/main/kotlin/io/ltverdict/sources/SourceAnalysis.kt`,
`src/main/kotlin/io/ltverdict/cli/CommandLine.kt`,
`src/main/kotlin/io/ltverdict/web/LocalApi.kt`,
`src/main/kotlin/io/ltverdict/core/AnalysisService.kt` (только публикация provenance),
`src/test/kotlin/io/ltverdict/sources/SourceAnalysisTest.kt`,
`src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`,
`src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`.

**Шаги:**

- [ ] Вызвать распознавание в начале `analyzeWithSources` до внешней выборки, если запрос имеет `origin=auto`; сохранить или переиспользовать `run-period.json`. Точка покрывает и CLI, и web, поскольку оба маршрута идут через `analyzeWithSources`, и выполняется в worker-потоке, а не в event loop.
- [ ] При отказе распознавания внешние запросы не выполняются; возвращается bounded outcome с reason code.
- [ ] CLI: `--source` принимает `v3`; правила взаимоисключения входов и проверки идентичности профилей сохраняются; usage обновляется.
- [ ] LocalApi: multipart part `source_request` принимает `v3`; пределы размера и взаимоисключение частей сохраняются.
- [ ] Provenance: добавить в `source_summary` поля `window_origin`, `recognized_start_epoch_ms`, `recognized_end_epoch_ms`, `requested_margin_ms`, `applied_margin_ms`, `max_idle_gap_ms`, `detected_idle_gaps`, `longest_idle_gap_ms`, `auto_window_status`. Для ручного импорта OpenSearch-контекста поля окна по-прежнему отсутствуют.
- [ ] Подтвердить, что `analysis_coverage` по-прежнему получает `SOURCE_ACQUISITION_PARTIAL`, `SOURCE_ACQUISITION_FAILED` и `SOURCE_REQUEST_CAP_EXCEEDED`, и что отказ авто-окна до выборки не выдаёт ложное покрытие.
- [ ] Тесты: авто-окно выполняет выборку за выведенный период; простой дольше порога даёт отказ без HTTP; `INVALID` вход даёт отказ; повторный анализ переиспользует артефакт; provenance содержит заявленный и фактический margin.
- [ ] Проверка: `gradlew --offline --no-daemon test -x npmCi --no-parallel`.

## Task 6: UI

**Files:** Modify `ui/src/types.ts`, `ui/src/api.ts`, `ui/src/App.vue`,
`ui/src/RunSetup.vue`, `ui/src/AnalysisView.vue`,
`ui/e2e/online-sources.spec.ts`, `ui/e2e/security-a11y.spec.ts`.

**Шаги:**

- [ ] Типы: форма `v3` и новые поля `SourceSummaryEvidence`.
- [ ] RunSetup: переключатель `auto`/`explicit`; для `auto` — поля margin и максимального простоя с подсказками; для `explicit` — существующие три поля. Все новые input получают label, входят в focus order и в проверку labelled inputs.
- [ ] App: построение `v3` вместо `v1`/`v2` при выбранных профилях, клиентская валидация кратности и диапазона, сообщение об ошибке до отправки.
- [ ] AnalysisView: отображение `window_origin`, распознанного периода, заявленного и фактического margin, числа и длительности простоев.
- [ ] e2e: отправка `v3` с `origin=auto`; блокировка при невалидном margin; отображение provenance после reload.
- [ ] Проверка: `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run e2e`.

## Task 7: Документация

**Files:** Modify `docs/user/online-sources.md`, `docs/user/slice-1-local-analysis.md`
(если описывает окно источника), `CHANGELOG.md`, `docs/session-handoff.md`,
`docs/development-plan-v0.6.md` (статус блока источников).

**Шаги:**

- [ ] Описать авто-окно, три отказа и требование явного окна при отказе; указать, что смещение часов не компенсируется.
- [ ] CHANGELOG: Added для `source-request.v3` и авто-окна, Changed для ужесточения `step_ms` с пояснением, что успешные сценарии не меняются, Fixed или Added для опубликованной схемы `v2`.
- [ ] Обновить handoff текущей сессией и статус блока в плане v0.6.
- [ ] Проверка: `npx --yes markdownlint-cli2`.

## Verification

Полный набор, offline, без `clean`:

```powershell
.\gradlew.bat --offline --no-daemon check installDist -x npmCi --no-parallel
.\gradlew.bat --offline --no-daemon test -x npmCi --no-parallel --rerun-tasks
npm --prefix ui run typecheck
npm --prefix ui run lint
npm --prefix ui run test:contracts
npm --prefix ui run e2e
python tools/verify_slice0.py
python -m unittest tools.test_verify_slice0 tools.test_generate_jtl tools.test_onboard_test -v
node --test tools/test_advisory_ai_runtime_relay.mjs
npx --yes markdownlint-cli2
```

Не входят и не считаются закрытыми локально: `powershell -File tools/test_advisory_ai_runtime.ps1` (требует Docker daemon), performance gate в `.github/workflows/runtime-quality.yml` и `tools/perf/jtl_probe.sh` (Linux, два CPU, 10 млн строк), зелёный runtime CI.

Отдельная поставка, не входящая в этот план: триаж `evidence-triage.v1` по ADR 0013 и замер шумовой характеристики для фактических размеров семьи. Замер выполняется до боевого запуска.

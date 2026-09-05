# Локальный просмотр и экспорт — implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development.
> Пользователь согласовал параллельную работу и дал команду «Начинаем».

**Goal:** открыть сохранённый analysis после перезагрузки UI, посмотреть
графики нагрузки и скачать проверяемый JSON/HTML без повторного анализа.

**Architecture:** существующие filesystem RunBundle, Ktor API и Vue UI.
HTML — чистая функция над canonical result, которую вызывают CLI и Web API.
Графики — SVG над уже существующими buckets, в текущем оформлении UI.

**Tech Stack:** Kotlin/JDK 21, Ktor, kotlinx.serialization, Vue 3,
TypeScript, JUnit, Playwright. Новых dependencies нет.

**Spec:** согласованный план в разговоре от 2026-09-05;
`docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md`,
разделы 18–19. Это первая часть Slices 8–9; baseline, N-run history,
AsciiDoc, Confluence, static chart export и RunBundle export остаются
следующими изменениями roadmap.

## Global Constraints

- База: `882dd83eaca24a37634b8221d8ea5b19b0da95a6`.
- Не изменять parser, metrics, policy, canonical result или identity.
- Не запускать analysis при просмотре/экспорте; не создавать новые analyses.
- Не добавлять зависимости, storage index, chart framework или renderer registry.
- Сохранить существующие темы, typography и доступность; UI copy — English.
- HTML не содержит scripts, forms, base, remote assets или raw input markup.
- Любой acquired text экранируется; arbitrary URLs не становятся HTML links.
- Export JSON byte-identical сохранённому `analysis-result.json`.
- HTML содержит run/analysis identity, три оси статуса, metrics, policy checks,
  findings и evidence; renderer не вычисляет новый verdict.
- Отсутствующие metrics отображаются как unavailable, а не как нулевые.
- Run/analysis paths проходят существующие проверки store и manifest.
- Существующие пользовательские файлы/ветки не менять; push/merge/tag не делать.
- Один scoped review законченному изменению; исправления проверять в их scope.

## Task 1: Сохранённые analyses и графики

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt`
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
- Modify: `ui/src/App.vue`, `ui/src/AnalysisView.vue`
- Modify: `ui/src/api.ts`, `ui/src/types.ts`, `ui/src/styles.css`
- Create: `ui/src/LoadCharts.vue`
- Test: `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`
- Test: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`
- Test: `ui/e2e/saved-analysis.spec.ts`

**Interfaces:**

```text
GET /api/runs/{runId}/analyses?after=<analysisId>&limit=<1..100>
{analyses:[{analysis_id, policy_sha256, policy_verdict, run_validity}],
 next_after:string|null}

RunBundleStore.listAnalyses(runId: String, afterAnalysisId: String?, limit: Int)
  -> AnalysisPage(analyses: List<AnalysisSummary>, nextAfter: String?)
AnalysisSummary(analysisId: String, policySha256: String,
                policyVerdict: String, runValidity: String)
```

Default limit 25. Детерминированная лексикографическая пагинация по id.
Сначала выбрать bounded set directory names, затем проверить возвращаемые
analyses через существующий `readAnalysisUnlocked`. Не следовать symlink.
Unknown run — 404; invalid/duplicate query — 400; отсутствующий analyses
directory у принятого input — пустой список. Corruption не скрывать.

UI показывает кнопки runs и список analyses с verdict, validity и коротким id.
Полный id доступен через title. Выбор analysis загружает существующие result
и buckets. Выбор первого analysis допустим как первый по id, без слова latest.
Run без analysis показывает понятное пустое состояние. Пагинация runs/analyses
доступна, когда сервер возвращает cursor. Новые completion обновляют список.

Хранить выбранный `analysisId` отдельно от `job`; не создавать fake COMPLETE job.
Смена выбора очищает старый результат; устаревший HTTP response не меняет новый
выбор. Не приписывать сохранённому analysis текущее время завершения.

`LoadCharts.vue` получает только `buckets: Bucket[]` и `rollup: number`.
Три отдельных SVG с общей relative-time осью: RPS (`sample_count / rollup`),
errors (`error_count`, единица count/bin), P95 (`p95_latency_ms`, ms).
Пустые интервалы разрывают линии; пропуски не заполняются нулями.
Один bucket остаётся видимым точкой. Для нулевого диапазона Y делитель >= 1.
SVG имеют accessible name и подписи осей/единиц; существующая таблица остаётся
текстовым представлением. Использовать текущие CSS variables. Нужен понятный
индикатор, что отображается выбранная страница buckets, и переход по
`next_from_ms`; график не выдаёт первые 500 buckets за полный прогон.

- [ ] Добавить integration/E2E tests и подтвердить RED: после analyze и reload
  выбрать сохранённый analysis, получить прежний verdict без POST /api/jobs.
- [ ] Проверить API pagination двумя analyses разных policy, пустой список,
  invalid cursor/query и отказ для неизвестного run; ожидания заданы literals.
- [ ] Реализовать store/API и frontend по контрактам выше.
- [ ] Проверить gap fixture (spike-drop), single/zero buckets, графики и table.
  Два разнесённых interval должны дать отдельные SVG segments, не общую линию.
- [ ] Выполнить focused JVM tests, UI lint/build и новый E2E; записать RED/GREEN.
- [ ] Просмотреть собственный diff, commit только task files.

```powershell
.\gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*RunBundleStoreTest' --tests '*LocalApiTest'
npm --prefix ui run lint
npm --prefix ui run build
npm --prefix ui run e2e -- e2e/saved-analysis.spec.ts
```

## Task 2: HTML renderer и CLI export

**Files:**

- Create: `src/main/kotlin/io/ltverdict/report/HtmlReport.kt`
- Modify: `src/main/kotlin/io/ltverdict/cli/CommandLine.kt`
- Test: `src/test/kotlin/io/ltverdict/report/HtmlReportTest.kt`
- Test: `src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`

**Interfaces:**

```kotlin
internal fun renderHtmlReport(resultBytes: ByteArray, analysisId: String): ByteArray
```

```text
ltv report <run-id> <analysis-id> --format json|html [--data-dir <path>]
```

Формат обязателен. Результат в stdout; success exit 0 независимо от verdict
исходного analysis. Usage error — 64; missing run/analysis — 4;
DATA_DIR_BUSY — 6; corruption/internal failure — 70, без partial stdout.
Использовать существующие DataDirectory и RunBundleStore.readAnalysis,
сохранить все существующие CLI commands. Не разрешать arbitrary artifact path.

JSON возвращать исходными bytes. HTML — UTF-8, `<!doctype html>`, `lang="en"`,
viewport, title. Три оси статуса, overall/transaction metrics, policy checks,
findings, evidence ids и исходный canonical JSON в escaped `<pre>`.
Числа показывать из JsonPrimitive.content без lossy Double roundtrip;
достаточно точных numerator/denominator с единицами для ratios.
Применить escaping `& < > " '` ко всему acquired text. Никакого raw markup
из result; JavaScript не нужен. Компактный responsive/print CSS внутри style,
его SHA-256 hash в meta CSP: default-src 'none'; style-src 'sha256-...';
base-uri 'none'; form-action 'none'. Renderer deterministic, без wall-clock.

- [ ] Написать RED CLI test нового report command на реальном сохранённом
  анализе, включая byte identity JSON и неизменность analysis directory.
- [ ] Написать renderer test для статусов, exact numeric values и malicious
  label `</pre><script>alert(1)</script>&\"'`; в HTML нет созданного script.
- [ ] Реализовать чистый renderer и CLI case в существующем dispatcher.
- [ ] Проверить invalid/no-policy results без придуманных metrics и unknown id.
- [ ] Выполнить focused tests и Kotlin lint, записать RED/GREEN evidence.
- [ ] Просмотреть собственный diff, commit только task files.

```powershell
.\gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*HtmlReportTest' --tests '*CommandLineTest' ktlintCheck
```

## Task 3: Download UI, документация и общая проверка

После Tasks 1–2, последовательно в integration worktree.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
- Modify: `ui/src/App.vue` (или текущая панель выбранного результата)
- Test: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`
- Test: `ui/e2e/saved-analysis.spec.ts`
- Modify: `README.md`, `CHANGELOG.md`
- Test: `ui/e2e/report-export.spec.ts` (отдельный download/offline сценарий)
- Modify: `ui/e2e/security-a11y.spec.ts` (keyboard order новых run buttons)
- Modify: `docs/user/slice-1-local-analysis.md`
- Modify: `docs/architecture/slice-1-local-runtime.md`
- Modify: `docs/development-plan-v0.6.md`, этот plan

**Interfaces:**

```text
GET /api/runs/{runId}/analyses/{analysisId}/report?format=json|html
Content-Disposition: attachment; filename="lt-verdict-<analysisId>.<format>"
```

Обязательный format; unknown/duplicate/extra query — 400.
Использовать store.requireAnalysis и `renderHtmlReport`; JSON — исходные bytes.
HTML Content-Type text/html; charset=utf-8; JSON application/json.
Две same-origin download links в UI: Download JSON / Download HTML,
с выбранным analysisId. Не создавать новую job, не менять хранение.

- [ ] RED endpoint test: download JSON совпадает с result bytes; HTML из
  endpoint совпадает с renderer; bad format rejected; нет новых analyses.
- [ ] Минимально подключить endpoint и две download links.
- [ ] E2E скачать HTML и открыть локальный файл: statuses/text доступны,
  malicious label остаётся текстом, script не исполняется, запросов в сеть нет.
- [ ] Обновить user guide/architecture/README/CHANGELOG и roadmap, обозначив
  выполненную часть Slices 8–9. Не объявлять Stage 1 принятым или MVP готовым.
- [ ] Свежие общие checks, один review итогового diff от исходной базы,
  scoped fixes при подтверждённых замечаниях и итоговый commit.

```powershell
python tools/verify_slice0.py
python -m unittest tools.test_verify_slice0 tools.test_generate_jtl -v
.\gradlew.bat -PnpmOffline=true --offline --no-daemon check installDist
npm --prefix ui run lint
npm --prefix ui run test:contracts
npm --prefix ui run e2e
git diff --check
```

## Приёмка первой поставки

Сохранённый analysis доступен после reload; графики правильно показывают gaps,
rollup и границы выбранных данных; JSON byte-identical; автономный HTML содержит
те же facts и безопасно отображает acquired text. CLI/UI используют один
renderer; повторное открытие и экспорт не создают analysis и не меняют verdict.
Документация описывает точные команды и ограничения. Stage 1 CI/performance
и полный MVP остаются отдельными незакрытыми gates.

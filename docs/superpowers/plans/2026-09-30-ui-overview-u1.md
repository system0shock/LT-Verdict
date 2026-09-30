# Обзор: вердикт, «Требует внимания», ключевые метрики и нагрузка с общим курсором (срез U1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** вкладка «Обзор» новой оболочки (`?shell=new`) показывает по готовому результату анализа: существующую карточку вердикта, блок «Требует внимания» с переходами к таблицам, ключевые метрики прогона и три ряда нагрузки (RPS, ошибки, p95) на общей шкале времени с одним курсором для всех рядов.

**Architecture:** три новых файла в `ui/src/shell/`: чистые адаптеры `overview.ts` (результат и buckets на входе, готовые данные на выходе), график `SharedCursorChart.vue` (собственный SVG, курсор на нативном `input[type=range]`) и композиция `OverviewPanel.vue`. `App.vue` остаётся владельцем состояния: показывает `OverviewPanel` сразу после `VerdictCard` и переключает вкладку по событию `navigate`. Данные: только `AnalysisResult` и уже загруженная страница `/buckets`; новых запросов, API и зависимостей нет.

**Tech Stack:** Vue 3.5, TypeScript 6, Playwright 1.62 с `@axe-core/playwright` (чистые адаптеры тестируются тем же Playwright без браузера, как `verdict-summary.spec.ts`); новых зависимостей нет.

**Spec:** этот файл (раздел «Brainstorming: допущения и вопросы»). Основание: `docs/ui-mockup/implementation-plan.md` (фаза 3, срез U1), макет `docs/ui-mockup/lt-verdict-ui-mockup.html` и `screens/01-overview.jpg`, черновики Codex `docs/superpowers/plans/codex-drafts-2026-09-30/` (сверены с кодом, см. ниже), U0-план `2026-09-30-ui-shell-u0.md`, решения владельца 2026-09-29.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: вкладка «Обзор» новой оболочки по макету: карточка вердикта
  (существующий VerdictCard), блок «Требует внимания», ключевые метрики
  прогона, наложение рядов нагрузки (RPS, ошибки, латентность из существующего
  /buckets) с общим курсором на собственном SVG; пометка «диагностика, не
  причина» для трендов и корреляций.
REQUIRED TO ACHIEVE IT:
  - адаптеры данных из существующего результата (нарушения правил, причины
    NO_VERDICT, покрытие данных, тренды и корреляции-кандидаты) и из /buckets;
  - переходы из «Требует внимания» на вкладку и секцию с таблицей (App.vue
    остаётся владельцем состояния);
  - график с общим курсором (отложен из U0 до первого использования);
  - русские строки только в ui/src/shell/labels.ts;
  - e2e с подменённым API (реальные имена полей /buckets), чистые unit-тесты
    адаптеров, сверка на реальном сервере, axe в двух темах, ширины 1280 и 375;
  - docs/user/slice-1-local-analysis.md и CHANGELOG.md.
NOT REQUIRED (report-only): карта «сервис x плечо», тепловая карта подов, ряды
  ресурсов (нет API, ADR 0014 и D0), стадии теста, события OpenSearch на
  графике, нижняя страница buckets и выбор диапазона на «Обзоре» (остаются на
  «Таблицах»), переход к конкретной строке таблицы (потребует id в
  AnalysisView), диагностические SLA-проверки и аномалии в «Требует внимания»,
  состояние вкладки в URL, правки Kotlin, api.ts, types.ts, схем, package.json,
  LoadCharts.vue, AnalysisView.vue, styles.css.
EXPECTED FILES TO CHANGE:
  create ui/src/shell/overview.ts
  create ui/src/shell/SharedCursorChart.vue
  create ui/src/shell/OverviewPanel.vue
  modify ui/src/shell/labels.ts       (русские строки OVERVIEW_LABELS, пишет оркестратор)
  modify ui/src/shell/shell.css       (scroll-margin для якорей)
  modify ui/src/verdictSummary.ts     (экспорт failedLinesOf, поведение прежнее)
  modify ui/src/App.vue               (OverviewPanel и jumpTo)
  create ui/e2e/overview-adapters.spec.ts
  create ui/e2e/overview.spec.ts
  create ui/e2e/overview-live.spec.ts
  modify docs/user/slice-1-local-analysis.md
  modify CHANGELOG.md
  create docs/superpowers/plans/2026-09-30-ui-overview-u1.md
```

Если реализация становится заметно больше ожидаемого (например, нужны правки `AnalysisView.vue`, `LoadCharts.vue` или `types.ts`), остановиться и объяснить (AGENTS.md, п. 10). ADR не нужен: контракты, схемы, API и зависимости не меняются; вкладка живёт за временным флагом оболочки.

## Brainstorming: допущения и вопросы

Классификация: архитектурный срез среднего размера (новая подсистема графика и адаптеров, без контрактов). Интерактивного диалога нет, поэтому решения владельца из задания приняты как исходные, остальное записано допущениями; спорное вынесено в вопросы.

Сверка черновиков Codex с кодом (`rg` по `ui/src`, `src/main/kotlin`):

- Черновик предлагает `OverviewPanel.vue` и `LinkedLoadChart.vue`: идея берётся, имена другие (`shell/OverviewPanel.vue`, `shell/SharedCursorChart.vue`) и лежат рядом с оболочкой.
- Черновик U1 из `mockup-vs-app-gap.md` называет `OverviewView.vue` и правки `LoadCharts.vue`: не берём, `LoadCharts.vue` остаётся источником графиков вкладки «Таблицы» без изменений.
- Утверждение «вердикт первым и причины словами уже есть» подтверждено (`VerdictCard.vue`, `verdictSummary.ts`, `verdictReasons.ts`): карточка не дублируется, `OverviewPanel` стоит сразу после неё.
- Черновик предлагает якоря `#policy-results` и `#normalized-data`: подтверждено (`AnalysisView.vue`, строки с `id=`); добавлены `#resource-results`, `#trend-results`, `#diagnostic-results`, `#capacity-results`, `#source-acquisition`.
- Ссылка черновика на `LocalApi.kt:920`: эндпоинт `/buckets` действительно там, но размер страницы 500 задаёт `api.ts` (`limit=500`), поэтому «Обзор» показывает ту же одну страницу, что и «Таблицы».
- Vitest в проекте нет; черновик это учитывает. Чистые функции тестируются Playwright без страницы (прецедент `verdict-summary.spec.ts`).
- Ряды ресурсов, поды, плечи: API нет, в U1 не входят (черновик тоже).

Допущения:

1. Состав вкладки сверху вниз: карточка вердикта (существующая), «Требует внимания», «Ключевые метрики прогона», «Нагрузка по времени»; далее без изменений блок ссылок на скачивание, сохранённая аналитика и панель Grafana. Это меняет только порядок в новой оболочке; старый интерфейс не затрагивается.
2. «Требует внимания» строится только из уже готовых полей `AnalysisResult` и никогда ничего не пересчитывает. Порядок фиксирован (как в макете: сначала нарушение правила, затем остальное): нарушения правил, причины `NO_VERDICT`, «политика не задана», неполные данные, диагностика.
3. Нарушение правила: бизнес-проверка `policy_check` со статусом `FAIL` и ресурсная SLA-проверка (`effect === 'sla'`) со статусом `FAIL` или `NO_VERDICT` с найденными нарушениями. Строки берутся из общей функции `failedLinesOf` (вынесена из `summarizeVerdict`), поэтому набор совпадает с карточкой вердикта, но в блоке показаны все нарушения, а не первые три. В режиме `capacity_step` нарушений-строк нет (как в карточке).
4. Причины `NO_VERDICT`: группы `summary.causes` карточки. Переход выбирается по коду причины: правила и транзакции ведут к таблице правил, пропуски ресурса к ресурсам, `CAPACITY_*` к ёмкости; остальное без перехода.
5. Неполные данные (узкое определение против ложных срабатываний): только (а) `run_validity` не `VALID` при вердикте не `NO_VERDICT`, (б) причины `analysis_coverage` при статусе `INCOMPLETE` (`summary.notes`) и (в) ряды ресурсов без единого значения (`NO_OBSERVATIONS`), к которым привязано SLA-правило. Пропуски `RESOURCE_GAPS` сами по себе не считаются отдельным пунктом; в (в) считаются только SLA-ряды, не диагностические и не ряды генератора.
6. Диагностика (флаг `diagnostic`, значок «диагностика, не причина», не «нарушение»): `trend_check` со статусом `TREND_OBSERVED` и `correlation_pair` со статусом `CANDIDATE` и известным коэффициентом. Источник трендов один (`trend_check`); находки `resource_trend` не используются, чтобы не считать дважды. `NO_MATERIAL_TREND`, `INSUFFICIENT_CELLS`, `UNAVAILABLE`, `DESCRIPTIVE` и прочие статусы не показываются. Диагностические SLA-проверки (`effect === 'diagnostic'`) и аномалии в блок не входят (NOT REQUIRED) и нарушением не называются.
7. Пустой блок: «Нечего отметить...» показывается при `PASS` без единого пункта; при `NO_POLICY` всегда есть пункт «Политика не задана» с переходом на форму запуска, чтобы пустое состояние не выглядело как «всё хорошо».
8. Переходы работают на уровне секции таблицы, не строки: переключить вкладку, дождаться `nextTick`, прокрутить к секции и перевести фокус на её первую прокручиваемую область (`tabindex="0"` у `.table-wrap`). Переход к строке потребовал бы `id` в `AnalysisView.vue` и изменил бы DOM старого интерфейса (вопрос владельцу 2).
9. Длинный список (более 6 пунктов) свёрнут кнопкой «Показать ещё N» (`aria-expanded`), кнопка «Свернуть список» возвращает.
10. Ключевые метрики: RPS, p95, p99, максимум отклика из общей `metric_summary` (`scope.kind === 'overall'`), формулы как в таблицах (`numerator / denominator`, `latency_ms.*`). Длительность, число запросов и доля ошибок не повторяются: они уже в фактах карточки вердикта. К каждой плитке добавляется `data-value` с числом без форматирования для сверки с прежним интерфейсом.
11. Ряды нагрузки: RPS = `sample_count / bucketRollup` (шаг страницы, которую загрузили, а не значение селектора, которое меняется до перезагрузки), ошибки за интервал = `error_count`, p95 = `p95_latency_ms`. Три дорожки друг под другом с общей осью времени и разной шкалой (как «ряды стоят друг под другом, единицы не смешиваются» в макете); «наложение» понимается как общая ось и общий курсор, а не как одна шкала. Линии не соединяются через пропущенные интервалы. Пропуски считаются и объявляются текстом.
12. Данные «Обзора» те же, что у «Таблиц»: одна страница `/buckets` (до 500 интервалов, шаг и диапазон задаются на «Таблицах»). Если сервер вернул `next_from_ms`, показано «Показаны не все интервалы»; новые страницы автоматически не запрашиваются. Для `INVALID` buckets не запрашиваются: текст «Данных нагрузки по интервалам нет», а не нулевая линия.
13. Общий курсор: нативный `input[type=range]` вне SVG (стрелки, Home, End, PageUp и PageDown работают из коробки), над графиками мышь и касание двигают тот же курсор к ближайшему интервалу; курсор привязан к интервалам, у которых есть данные. Значения курсора видны в заголовках дорожек и в `aria-valuetext` ползунка; отдельной области `aria-live` нет, поэтому чтение не повторяется. При фокусе без выбора курсор встаёт на первый интервал; при смене данных курсор сбрасывается. SVG скрыт от вспомогательных технологий (`aria-hidden`), доступное содержимое: сгруппированный график с названием, ползунок с текстовым значением и таблица buckets на вкладке «Таблицы».
14. Цвета дорожек: `--brand` (RPS), `--fail` (ошибки), `--warn` (p95) из токенов оболочки; название и значение всегда подписаны текстом, цвет не единственный признак. Шрифты, тепловые карты и стадии макета не переносятся.
15. Стили новых компонентов лежат в `<style scoped>` этих компонентов и используют только существующие токены; `styles.css`, `main.ts` и `shell.css` (кроме одного правила `scroll-margin-top`) не меняются.
16. Тексты причин, названий правил и строк нарушений приходят из существующих `verdictSummary.ts` и `verdictReasons.ts`; все прочие русские строки U1 лежат в `OVERVIEW_LABELS` (`labels.ts`); код адаптеров и компонентов ASCII (тесты содержат русские строки только там, где проверяют готовые строки карточки).

Вопросы владельцу (не блокируют):

1. Нужны ли на «Обзоре» ряды не только нагрузки, но и событий OpenSearch (маркеры уже есть в `LoadCharts.vue`)? Сейчас нет: NOT REQUIRED.
2. Нужен ли переход к конкретной строке таблицы (подсветка строки правила)? Потребует `id` у строк `AnalysisView.vue`, то есть правки старого DOM; сейчас переход до секции.
3. Считать ли аномалии (`anomaly_episode`) и диагностические SLA-проверки пунктами «Требует внимания» с пометкой «диагностика»? Сейчас нет: только тренды и коэффициенты-кандидаты.
4. Дублирование первых трёх нарушений в карточке и в блоке «Требует внимания» допустимо (в макете тоже)? Сейчас да.

## Global Constraints

- Публичные контракты не меняются: `git diff --stat origin/main -- src docs/contracts ui/src/types.ts ui/src/api.ts ui/package.json ui/package-lock.json ui/src/AnalysisView.vue ui/src/LoadCharts.vue ui/src/styles.css` пуст.
- Старый интерфейс не меняется: без `?shell=new` DOM и тексты прежние, все существующие e2e зелёные без правок; `summarizeVerdict` возвращает прежние значения.
- Никаких новых запросов: «Обзор» использует `result` и `buckets` из `App.vue`; тест с подменённым API падает на любом неизвестном пути.
- Браузерное хранилище (`localStorage`, `sessionStorage`, cookies) и `v-html` запрещены.
- Русские строки U1 только в `ui/src/shell/labels.ts`; файлы, которые пишет Codex, чисто ASCII (`rg -n "[^\x00-\x7F]"` пуст).
- Каждая кнопка и ползунок не ниже 44 px; фокус виден; в графике нет `aria-live`, `role="status"`, `role="alert"`.
- Без горизонтального скролла страницы на 1280 и 375 px; axe: нет нарушений critical и serious в обеих темах.
- Проза документов на русском, `.md` проходят `markdownlint-cli2@0.23.2`.
- Коммиты атомарные, Conventional Commits, явные пути, без `git add .` и `-A`; push, PR, merge, force запрещены.
- В PowerShell перед e2e: `Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue`; тяжёлые прогоны под мьютексом `Global\ltv-heavy`.

## Review Focus

- Ложные «Требует внимания»: `PASS`, `NO_POLICY`, `INVALID` не дают пункта «Нарушение»; диагностическая SLA-проверка не называется нарушением; источник трендов один (`trend_check`), находки `resource_trend` не удваивают пункты; `NO_MATERIAL_TREND`, `INSUFFICIENT_CELLS`, `DESCRIPTIVE` не показываются (тесты в `overview-adapters.spec.ts`).
- Формулы: RPS = `sample_count / bucketRollup` (не `rollup` селектора); плитки метрик совпадают с прежним интерфейсом на реальном сервере (`overview-live.spec.ts`).
- Линия не соединяется через пропущенный интервал; пропуск объявлен текстом; пустая страница и `INVALID` не рисуют нулевую линию.
- Один курсор: клавиатура, мышь и три дорожки показывают один интервал (одинаковый `data-x`); нет `aria-live`; название ползунка и `aria-valuetext` осмысленны; ползунок не внутри SVG.
- Усечённая страница честно названа неполной и не догружается.
- Переходы: вкладка «Таблицы» и секция открываются, фокус попадает в область таблицы, шапка не перекрывает секцию (`scroll-margin-top`).
- Кодировка файлов Codex (нет не-ASCII), окончания строк, размер диффа `App.vue` порядка десятков строк.
- Старый интерфейс: в нём нет `overview-panel`, структура `id` внутри `main` та же (`local-flow.spec.ts`).

---

## Task 1: Строки и тесты (RED)

**Files:**

- Modify: `ui/src/shell/labels.ts` (оркестратор, UTF-8 без BOM)
- Create: `ui/e2e/overview-adapters.spec.ts`, `ui/e2e/overview.spec.ts`, `ui/e2e/overview-live.spec.ts`

**Interfaces:**

- Produces (`labels.ts`, готово): `pluralRu(count, one, few, many)`, `type TrendDirection = 'increase' | 'decrease' | 'flat'`, `OVERVIEW_LABELS` с ключами блока «Требует внимания» (`attentionTitle`, `attentionLead`, `attentionEmpty`, `attentionMore(hidden)`, `attentionLess`, `kind*`, `diagnosticBadge`, `diagnosticNote`, `noPolicyTitle`, `noPolicyDetail`, `validityDegraded`, `validityInvalid`, `causeSubjects`, `resourceSeriesTitle`, `resourceSeriesDetail`, `trendTitle`, `trendDetail`, `correlationTitle`, `correlationDetail`, `open*`), метрик (`metricsTitle`, `metric*`, `unitRps`, `unitMs`) и нагрузки (`loadTitle`, `loadLead`, `loadReadNote`, `loadEmpty`, `loadPartial`, `loadSummary`, `loadGaps`, `chartAria`, `track*`, `axisLabel`, `cursorLabel`, `cursorHint`, `cursorNone`, `cursorTime`, `cursorText`).
- Consumes: `ShellTabKey`.

- [x] **Step 1: Написать падающие тесты** (оркестратор). Ключевые фрагменты `overview-adapters.spec.ts`:

```ts
test('PASS and INVALID produce no violation and a plain PASS has nothing to flag', () => {
  expect(attentionItems(build({ policy_verdict: 'PASS', evidence: [overall, checkout, p95Rule('ok', 'PASS', 100)] }))).toEqual([])
  expect(kinds(build({ policy_verdict: 'NO_VERDICT', run_validity: 'INVALID', analysis_coverage: { status: 'INCOMPLETE', reasons: ['MALFORMED_JMETER_CSV'] } })))
    .not.toContain('violation')
})

test('only observed trends and candidate correlations are diagnostics, marked as such and counted once', () => {
  const items = attentionItems(build({
    policy_verdict: 'PASS',
    evidence: [
      overall, checkout, p95Rule('ok', 'PASS', 100),
      trend('a', 'TREND_OBSERVED'), trend('b', 'NO_MATERIAL_TREND', { observed_direction: 'flat' }), trend('c', 'INSUFFICIENT_CELLS', { observed_direction: null }),
      correlation('p1', 'CANDIDATE'), correlation('p2', 'DESCRIPTIVE'), correlation('p3', 'CANDIDATE', null),
    ],
    findings: [{ type: 'resource_trend', check_id: 'a', series_id: 'cpu-a', evidence_id: 't-a' }],
  }))

  expect(items.map((item) => item.key)).toEqual(['diagnostic:trend:t-a', 'diagnostic:correlation:cp-p1'])
  expect(items.every((item) => item.kind === 'diagnostic' && item.diagnostic)).toBe(true)
})

test('divides samples by the rollup of the fetched page, not by the current select value', () => {
  const series = loadSeries([bucket(0, 100, 2, 300), bucket(10_000, 50, 0, 250)], 10)

  expect(series.points).toEqual([
    { startMs: 0, rps: 10, errors: 2, p95: 300 },
    { startMs: 10_000, rps: 5, errors: 0, p95: 250 },
  ])
})

test('sorts buckets and never joins the lines across a missing interval', () => {
  const series = loadSeries([bucket(4_000, 10, 0, 100), bucket(0, 10, 0, 100), bucket(1_000, 10, 0, 100), bucket(2_000, 10, 0, 100)], 1)

  expect(series.segments).toEqual([[0, 1, 2], [3]])
  expect(series.missingIntervals).toBe(1)
})
```

Ключевые фрагменты `overview.spec.ts` (подмена API возвращает все шесть полей `/buckets`: `bucket_start_ms`, `sample_count`, `error_count`, `p95_latency_ms`, `max_latency_ms`, `hdr_v2_base64`; неизвестный путь бросает ошибку):

```ts
test('one keyboard cursor moves every track together and announces itself through the slider only', async ({ page }) => {
  await openOverview(page)
  const control = slider(page)

  await expect(control).toHaveAttribute('type', 'range')
  await expect(control).toHaveAccessibleName(OVERVIEW_LABELS.cursorLabel)
  await control.focus()
  await page.keyboard.press('ArrowRight')
  await expect(control).toHaveValue('1')
  await expect(page.getByTestId('track-value-rps')).toHaveAttribute('data-value', '120.00')
  const [rps, errors, p95] = await lineX(page)
  expect(rps).toBe(errors)
  expect(errors).toBe(p95)
  await expect(chart(page).locator('[aria-live], [role="status"], [role="alert"]')).toHaveCount(0)
})

test('each item opens the matching table on the Tables tab and moves focus into it', async ({ page }) => {
  await openOverview(page)

  await items(page).nth(0).getByTestId('attention-open').click()
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#policy-results')).toBeInViewport()
  await expect(page.locator('#policy-results [role="region"]')).toBeFocused()
})
```

Полные файлы: 22 теста адаптеров (`overview-adapters.spec.ts`), 21 тест интерфейса (порядок и метки, пустые состояния, свёртка, переходы, доступные имена, метрики, три дорожки и пропуск, клавиатурный и мышиный курсор, пустая и усечённая страницы, отсутствие лишних запросов, старый интерфейс, чужие вкладки, axe в двух темах, ширины 1280 и 375) и один тест сверки с реальным сервером.

- [x] **Step 2: Убедиться, что RED.** Run: `npx playwright test -c tmp-overview.config.ts overview-adapters` (временный конфиг с Vite, не коммитится). Expected: FAIL, `Cannot find module ... src/shell/overview`.

- [ ] **Step 3: Commit** вместе с реализацией (Task 2), чтобы история не содержала красных коммитов.

## Task 2: Адаптеры, график, панель, подключение (исполнитель Codex, только ASCII)

**Files:**

- Create: `ui/src/shell/overview.ts`, `ui/src/shell/SharedCursorChart.vue`, `ui/src/shell/OverviewPanel.vue`
- Modify: `ui/src/verdictSummary.ts`, `ui/src/App.vue`, `ui/src/shell/shell.css`
- Test: `ui/e2e/overview-adapters.spec.ts`, `ui/e2e/overview.spec.ts`

**Interfaces:**

- Produces (`overview.ts`): `attentionItems(result): AttentionItem[]` (`{ key, kind, title, detail, diagnostic, target: { tab, targetId } | null, openLabel }`), `keyMetrics(result): MetricTile[]` (`{ key: 'rps' | 'p95' | 'p99' | 'max', label, value, raw }`), `loadSeries(buckets, rollupSeconds): LoadSeries`, `cursorFraction`, `nearestIndex`, `trackPoints`, `formatOffset`, `cursorSummary`, `formatNumber`.
- Produces (`verdictSummary.ts`): `failedLinesOf(result): FailedLine[]` (строки нарушений с признаком `source: 'business' | 'resource'`), `summarizeVerdict` без изменений поведения.
- Produces (`SharedCursorChart.vue`): prop `series: LoadSeries`; `data-testid`: `shared-cursor-chart`, `chart-plot`, `track-<key>`, `track-line-<key>`, `track-value-<key>` (`data-value`), `cursor-line-<key>` (`data-x`), `cursor-time`, `chart-cursor`, `load-summary`, `load-gaps`.
- Produces (`OverviewPanel.vue`): props `result`, `buckets`, `bucketRollup`, `hasMoreBuckets`; событие `navigate: [AttentionTarget]`; `data-testid`: `overview-panel`, `attention-list`, `attention-item` (`data-kind`), `attention-open`, `attention-more`, `diagnostic-note`, `metric-tile` (`data-metric`, `data-value`), `load-empty`, `load-partial`.
- Produces (`App.vue`): `jumpTo(target)`: `activeTab = target.tab`, `await nextTick()`, `scrollIntoView()`, фокус на самой секции-поле или на её первом `[tabindex="0"]`.
- Consumes: `OVERVIEW_LABELS`, `summarizeVerdict`, `AnalysisResult`, `Bucket`.

- [ ] **Step 1: Задание Codex.** Файл `codex-tasks/U1-overview.md` (только ASCII, ключи `OVERVIEW_LABELS`, точные сигнатуры, запреты: не править тесты и `labels.ts`, не коммитить, не запускать npm; проверка `rg -n "[^\x00-\x7F]"` и `git diff --check`).

- [ ] **Step 2: Реализация** (Codex). Ключевые части:

```ts
// overview.ts: порядок групп и одна общая фильтрация нарушений
export function attentionItems(result: AnalysisResult): AttentionItem[] {
  const summary = summarizeVerdict(result)
  return [
    ...violationItems(result),            // failedLinesOf(result), только не capacity_step
    ...noVerdictItems(result, summary),   // summary.causes при NO_VERDICT, переход по коду
    ...policyItems(result),               // NO_POLICY -> форма запуска
    ...coverageItems(result, summary),    // validity, summary.notes, SLA-ряды без значений
    ...diagnosticItems(result),           // trend_check TREND_OBSERVED, correlation_pair CANDIDATE
  ]
}

// loadSeries: шаг страницы, а не значение селектора; разрыв линии при пропуске
const gap = start > previousStart + rollupSeconds * 1000
```

```vue
<!-- SharedCursorChart.vue: ползунок вне SVG, min/max/step до value, без aria-live -->
<input
  type="range" data-testid="chart-cursor" :aria-label="OVERVIEW_LABELS.cursorLabel" aria-describedby="chart-cursor-hint"
  min="0" :max="series.points.length - 1" step="1" :value="cursor ?? 0"
  :aria-valuetext="readout ? readout.text : OVERVIEW_LABELS.cursorNone"
  @focus="onFocus" @input="onInput"
>
```

- [ ] **Step 3: Проверка без e2e** (оркестратор). Run: `npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run test:contracts; npm --prefix ui run build`. Expected: без ошибок.

- [ ] **Step 4: GREEN.** Run: `npx playwright test -c tmp-overview.config.ts`. Expected: все тесты `overview-adapters`, `overview`, `shell`, `verdict-summary`, `verdict-first` зелёные.

- [ ] **Step 5: Проверка diff** (оркестратор): только EXPECTED FILES; нет не-ASCII в файлах Codex; `git diff --stat` для `App.vue` порядка десятков строк; окончания строк неизменны; `git diff --check`.

- [ ] **Step 6: Commit.** `git add ui/src/shell ui/src/verdictSummary.ts ui/src/App.vue ui/e2e/overview-adapters.spec.ts ui/e2e/overview.spec.ts ui/e2e/overview-live.spec.ts`; сообщение `feat(ui): add the overview tab with attention items and a shared-cursor load chart`.

## Task 3: Полный набор, сверка на реальном сервере, снимки

- [ ] `npm --prefix ui run e2e` (Gradle-сервер) под мьютексом `Global\ltv-heavy`, приоритет ниже обычного: все существующие спеки зелёные без правок, новые зелёные, `overview-live.spec.ts` подтверждает совпадение чисел с прежним интерфейсом.
- [ ] Снимки вкладки «Обзор» (светлая и тёмная темы, 1280 и 375, курсор выбран) в `scratchpad/u1-screens/`; описание расхождений с `screens/01-overview.jpg` в отчёте.

## Task 4: Документация

**Files:**

- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

- [ ] **Step 1:** в разделе про новый интерфейс описать состав «Обзора»: как читать «Требует внимания» (порядок, что попадает и что нет, пометка «диагностика, не причина», переходы к таблицам), ключевые метрики, график нагрузки и общий курсор (клавиатура, мышь), что показана одна страница `/buckets` и где менять диапазон.
- [ ] **Step 2:** `CHANGELOG.md`, `### Added`: запись про вкладку «Обзор».
- [ ] **Step 3:** `npx markdownlint-cli2@0.23.2 docs/user/slice-1-local-analysis.md CHANGELOG.md docs/superpowers/plans/2026-09-30-ui-overview-u1.md`; `git diff --check origin/main...HEAD`.
- [ ] **Step 4: Commit** `docs: describe the overview tab of the new shell`.

## Task 5: Независимое ревью

- [ ] Ревью Codex read-only (`gpt-5.6-terra`, `high`) по `git diff origin/main...HEAD`: доступность графика и курсора, корректность адаптеров и формул, ложные «Требует внимания», кодировка, регрессии, соответствие плану. Формат BLOCKER/MAJOR/MINOR, APPROVE/CHANGES_REQUESTED.
- [ ] Замечания проверяются фактами; принятые исправляются новыми коммитами.
- [ ] Проверка перед финалом: `git status --short`, `git diff --stat origin/main...HEAD`, временный `ui/tmp-overview.config.ts` удалён и не в индексе.

## Self-Review

- Spec coverage: карточка вердикта (существующая, Task 2 шаг подключения), «Требует внимания» (адаптеры, допущения 2-9), ключевые метрики (допущение 10), график с общим курсором (допущения 11-14), пометка «диагностика, не причина» (допущение 6, тесты), переходы (допущение 8), русские строки (`labels.ts`), документация (Task 4).
- Placeholder scan: сигнатуры, тексты и `data-testid` заданы в файле задания, `labels.ts` и тестах.
- Type consistency: `AttentionTarget.tab` типа `ShellTabKey`; `LoadSeries.rollupSeconds` берётся из `bucketRollup`; `MetricTile.raw` совпадает с форматом старых таблиц (`toFixed(2)` для RPS, число для задержек).

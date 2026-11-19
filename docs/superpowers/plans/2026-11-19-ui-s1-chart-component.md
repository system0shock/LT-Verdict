# UI, срез 1: единый компонент графика на Apache ECharts (план реализации)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** заменить полоски-ряды новой оболочки (`?shell=new`) в «Обзоре» («Нагрузка по времени») и «Глубоком анализе»
(«Нагрузка и ряды ресурсов на общей шкале времени») одним компонентом-дорожкой на ECharts: ось значений с делениями,
ось времени, сетка, порог с подписью значения, полосы стадий, курсор со значением, общий для всех дорожек.

**Architecture:** новый компонент `TimeChart.vue` рисует одну дорожку (одна сетка, один ряд) через ECharts с
SVG-рендерером; опции строит чистый модуль `timeChart.ts`. Контейнеры `SharedCursorChart.vue` и `DeepCursorChart.vue`
остаются: в них живут ползунок, значения в заголовках рядов, подписи и `aria`; в каждом ряду вместо `<svg>` встаёт
`TimeChart`. Курсор принадлежит Vue (состояние контейнера), ECharts рисует только статичную часть, линия и значение
курсора рисуются слоем Vue поверх дорожки.

**Tech Stack:** Vue 3.5.42, TypeScript, Vite 8, `echarts` 6.1.0 (`echarts/core`, `LineChart`, `GridComponent`,
`MarkLineComponent`, `MarkAreaComponent`, `SVGRenderer`), Playwright 1.62.1, `@axe-core/playwright`.

**Spec:** решения владельца 2026-11-19 (U1–U3) и выбор библиотеки: `docs/superpowers/plans/2026-11-19-ui-mockup-gap.md`
(разделы 2, 3, 5–7; файл пока не в `main`), ADR 0035 `docs/adr/0035-ui-chart-library.md` (статус Accepted, лежит в ветке
`docs/adr-0035-ui-chart-library`, в `origin/main` его нет), макет `docs/ui-mockup/lt-verdict-ui-mockup.html` (функция
`chart(cfg)`, строки 377–419; дорожки наложения, строки 465–475) и снимки `docs/ui-mockup/screens/01-overview.jpg`,
`02-deep-trend.jpg`, `10-compare.jpg`.

## Статус и зависимость

- Реализация заблокирована, пока ADR 0035 не влит в `main`: PR A вводит production-зависимость, которую фиксирует этот
  ADR (AGENTS.md, «Before changing files», п. 5). Перед слиянием ADR сверить номер 0035 с `origin/main`.
- Опись расхождений (`2026-11-19-ui-mockup-gap.md`) лежит неотслеживаемым файлом в основном checkout. Ссылки на неё и
  на ADR в этом плане даны путями в коде, а не markdown-ссылками: иначе упадёт проверка `Links`.

## Блок для AGENTS.md

```text
REQUESTED: срез 1 описи UI: один переиспользуемый компонент графика в ui/src/shell/ на ECharts (прямая зависимость
  echarts с точной версией, без vue-echarts, импорт по модулям, SVG-рендерер); им заменить стопки полосок в
  «Обзоре» («Нагрузка по времени») и «Глубоком анализе» («Нагрузка и ряды ресурсов на общей шкале времени») с теми же
  источниками данных; ось Y с делениями и единицами, ось времени, сетка, порог с подписью значения, полосы стадий
  (если у анализа есть стадии), курсор при наведении со значением, общий курсор у дорожек; сохранить клавиатурный
  курсор, текстовые альтернативы, CSP, офлайн-сборку и русские подписи; измерить бандл до и после.
REQUIRED TO ACHIEVE IT:
  - ui/package.json, ui/package-lock.json: "echarts": "6.1.0" в dependencies (вместе с ним в lock попадают zrender
    6.1.0 и tslib 2.3.0);
  - ui/src/shell/timeChart.ts (новый): типы контракта, чистые функции шкал, делений, данных линии, отображения
    указателя в долю шкалы, полос стадий и сборки опций ECharts;
  - ui/src/shell/TimeChart.vue (новый): дорожка на ECharts (init с SVG, цвета из токенов темы, ResizeObserver,
    dispose), слой курсора Vue с линией, значением и временем;
  - ui/src/shell/SharedCursorChart.vue и ui/src/shell/DeepCursorChart.vue: <svg> ряда заменить на TimeChart,
    указатель мыши переводить в долю шкалы с учётом полей оси Y;
  - ui/src/shell/overview.ts и ui/src/shell/deep.ts: по одной функции-адаптеру ряда в ChartLane (loadLane, deepLane);
  - ui/src/shell/OverviewPanel.vue и ui/src/shell/DeepAnalysisPanel.vue: передать стадии из stage_binding;
  - ui/src/shell/labels.ts: короткая подпись порога на графике (DEEP_LABELS.thresholdMark);
  - тесты: новый ui/e2e/time-chart-adapters.spec.ts; правки и новые проверки в ui/e2e/overview.spec.ts и
    ui/e2e/deep.spec.ts (перечень правок и причины в разделе «Правки существующих тестов»);
  - docs/user/slice-1-local-analysis.md: строки «Обзор» и «Глубокий анализ» таблицы вкладок;
  - changelog.d/ui-s1a-chart-overview.changed.md, changelog.d/ui-s1b-chart-deep.changed.md.
NOT REQUIRED:
  - ядро на Kotlin, API, схемы, контракты, analysis-result.json, identity, канонический JSON;
  - отчёты ядра (HtmlReport.kt, StaticLoadChart.kt) и их перевод на ECharts (ADR 0035, D4);
  - старый интерфейс: LoadCharts.vue, BaselineCharts.vue, App.vue (кроме того, что он уже делает с темой);
  - всплывающая подсказка ECharts (TooltipComponent), echarts.connect, dataZoom, тепловая карта;
  - оверлеи (прошлый релиз пунктиром, линия тренда, полоса min–max), режим спарклайна, заливка зоны нарушения
    порога: контракт описан ниже, реализация в срезах 2–5;
  - порог на дорожках «Обзора» (порогов на этих рядах сегодня нет, см. находку Н5);
  - каталог метрик, большой график, выбор стадии кнопками, плитки статистик (срез 2);
  - удаление ставших ненужными trackPoints, trackPath, valueY (их проверяют адаптерные спеки; только в отчёт);
  - ленивая загрузка чанка графиков (вопрос владельцу В1).
EXPECTED FILES TO CHANGE:
  PR A: ui/package.json, ui/package-lock.json, ui/src/shell/timeChart.ts, ui/src/shell/TimeChart.vue,
    ui/src/shell/SharedCursorChart.vue, ui/src/shell/OverviewPanel.vue, ui/src/shell/overview.ts,
    ui/e2e/time-chart-adapters.spec.ts, ui/e2e/overview.spec.ts, docs/user/slice-1-local-analysis.md,
    changelog.d/ui-s1a-chart-overview.changed.md
  PR B: ui/src/shell/timeChart.ts, ui/src/shell/DeepCursorChart.vue, ui/src/shell/DeepAnalysisPanel.vue,
    ui/src/shell/deep.ts, ui/src/shell/labels.ts, ui/e2e/time-chart-adapters.spec.ts, ui/e2e/deep.spec.ts,
    docs/user/slice-1-local-analysis.md, changelog.d/ui-s1b-chart-deep.changed.md
```

## Находки в коде, которые ограничивают дизайн

Ссылки даны на `origin/main` `d9cf21b7`.

### Н1. Данные «Обзора»

- Ряды берутся из `/buckets`: у интервала есть только `bucket_start_ms`, `sample_count`, `error_count`,
  `p95_latency_ms`, `max_latency_ms`, `hdr_v2_base64` (`ui/src/types.ts:660-667`). Рядов p50 и p99 по времени API не
  отдаёт: их можно получить только разбором HDR на клиенте. В срез 1 это не входит.
- `fetchRunLoad` (`ui/src/shell/deep.ts:71-93`): проба на 60 с, затем шаг 1, 10, 30 или 60 с так, чтобы интервалов было
  не больше `LOAD_BUCKET_BUDGET = 1500` (`deep.ts:28`). При усечении читается до `MAX_LOAD_PAGES = 12` страниц по
  500 интервалов, то есть до 6000 точек на ряд (`deep.ts:31`, тест `overview.spec.ts:380-387`).
- `loadSeries` (`ui/src/shell/overview.ts:388-418`): `rps = sample_count / rollup`, `errors = error_count`,
  `p95 = p95_latency_ms`; разрывы между интервалами задаются отдельно массивом `segments`, `null` в точках нет. Ось
  «Обзора» идёт от первой до последней точки: `cursorFraction` (`overview.ts:420-425`), одна точка стоит в середине (0,5).
- `bucket_start_ms` отсчитывается от начала прогона (`src/main/kotlin/io/ltverdict/metrics/Metrics.kt:213-215`).

### Н2. Данные «Глубокого анализа»

- Дорожка `DeepTrack` (`deep.ts:8-23`): точки `{ startMs, value | null, observed }`, `segments`, `min`, `max`,
  `thresholds`. Нагрузка: две дорожки `load-rps` и `load-p95` в эпохе `originMs + bucket_start_ms` (`deep.ts:187-202`).
  Ресурсы: до `MAX_SELECTED_SERIES = 6` рядов по не более `TARGET_CELLS = 1500` ячеек (`deep.ts:26-27`). Нагрузка в
  периоде читается до 12 страниц по 500 (`DeepAnalysisPanel.vue:42-54`), то есть до 6000 точек.
- Всего до 8 дорожек одновременно. Точка рисуется в середине своей ячейки (`deep.ts:251-264`), курсор стоит на начале
  ячейки (`DeepCursorChart.vue:13`). Одиночная точка между разрывами рисуется кружком (`DeepCursorChart.vue:116-123`).
- Масштаб `trackScale` включает ноль и пороги (`deep.ts:240-245`). Пороги есть только у рядов ресурсов: из
  `resource_policy_check` (`deep.ts:126-138`).
- `originMs` = `resource_binding.run_from_epoch_ms`, без снимка ресурсов 0 (`DeepAnalysisPanel.vue:15-16`, `deep.ts:120-124`).
  Ось может начинаться до начала прогона: подписи со знаком минус (`DeepCursorChart.vue:15`, тест `deep.spec.ts:219-227`).

### Н3. Стадии

- Временные границы стадий есть только в evidence `stage_binding` (ADR 0030), когда стадии объявлены:
  `stages[].{ id, role, from_offset_ms, to_offset_ms, from_epoch_ms, to_epoch_ms }` (`ui/src/types.ts:391-412`).
  Смещения отсчитываются от начала прогона (`src/main/kotlin/io/ltverdict/core/LoadStages.kt:27`), как и `bucket_start_ms`.
- Ступени ёмкости (`capacity_summary.stages`, `types.ts:618-632`) времени не несут: у прогона `capacity_step` полос
  стадий не будет.
- Фикстура `fixtures/stages/stage-binding.sample.json`: три стадии 0–40 с (`ramp-up`, excluded), 40–100 с (`steady`),
  100–120 с (`ramp-down`), `run_from_epoch_ms = 1767225600000`. Это тот же `origin`, что в `deep.spec.ts:8`.

### Н4. CSP и ECharts

- Локальный сервер отдаёт `default-src 'self'; script-src 'self'; style-src 'self'; ...`
  (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:151,173`), тест заголовка `ui/e2e/security-a11y.spec.ts:13-18`.
  `style-src 'self'` без `'unsafe-inline'` запрещает элементы `<style>` и атрибуты `style="..."` в разобранной
  разметке, но не запись через CSSOM (`el.style.cssText = ...`).
- Проверено по исходникам `echarts@6.1.0` и `zrender@6.1.0` (из npm, 2026-11-19):
  - SVG-рендерер пишет атрибут `style` через `elm.style.cssText` (`zrender/src/svg/patch.ts:146-147`; создание элемента
    идёт через тот же `updateAttrs`, `zrender/lib/svg/patch.js:33-39`). Это CSSOM, CSP его не блокирует.
  - Элемент `<style>` SVG-рендерер добавляет только при `renderToVNode({ animation: true })` (рендер в строку, SSR);
    живой `refresh()` его не создаёт (`zrender/src/svg/Painter.ts:110-119,163-169`).
  - HTML-подсказка ECharts пишет `innerHTML` (`echarts/lib/component/tooltip/TooltipHTMLContent.js:316`) с разметкой,
    где есть `style="..."` (`echarts/lib/util/format.js:154-156`, `echarts/lib/component/tooltip/tooltipMarkup.js:227`).
    Под нашей CSP стили подсказки будут отклонены. Поэтому `TooltipComponent` не подключается, значения курсора рисует Vue.
- Под CSP работает только основной `ui/playwright.config.ts`: он поднимает настоящий сервер (`runE2eServer`, а
  `processResources` зависит от `uiBuild`, `build.gradle.kts:236-237,242-253`). Быстрые конфиги `e2e/deep-ui.config.ts`
  и `e2e/stage-ui.config.ts` запускают `vite` без CSP. CI запускает только основной конфиг.

### Н5. Пороги в «Обзоре»

На трёх дорожках «Обзора» порогов нет (`SharedCursorChart.vue` их не получает). Правила `policy_check` относятся к
транзакциям, а дорожка p95 показывает все запросы; переносить на неё порог транзакции нельзя. Порог с подписью в срезе 1
есть только у рядов ресурсов в «Глубоком анализе».

### Н6. Тема, доступность, раскладка

- Тема всегда задана явно: `data-theme` на `<html>` из начального `matchMedia` и кнопки переключения
  (`ui/src/App.vue:50,235-243,959-964`); токены `--brand`, `--warn`, `--fail`, `--text`, `--text-muted`, `--border`
  (`ui/src/styles.css:1-60`). ECharts не понимает `var(--…)` в атрибутах SVG: цвета читаются через `getComputedStyle`
  и переприменяются при смене `data-theme`. Слушать `prefers-color-scheme` не нужно.
- Текстовые альтернативы сегодня: ползунок с `aria-valuetext` (`SharedCursorChart.vue:107-120`,
  `DeepCursorChart.vue:162-176`), значения в заголовках рядов (`track-value-*`), подписи шкалы и шага; `<svg>` дорожек
  скрыт `aria-hidden`. Тест `overview.spec.ts:319` требует, чтобы внутри графика не было `aria-live`, `status`, `alert`.
- Axe: «Обзор» без serious и critical (`overview.spec.ts:442-452`), «Глубокий анализ» без нарушений вообще
  (`deep.spec.ts:385-401`). Раскладка: нет горизонтальной прокрутки на 375 и 320 px сразу после `setViewportSize`.
- Высота дорожки сейчас 80 px (`SharedCursorChart.vue:165`, `DeepCursorChart.vue:190`), макет: 90 px, нижняя дорожка с
  подписями времени 116 px, поле слева 56 px, справа 14 px, подписи стадий только на верхней дорожке
  (`lt-verdict-ui-mockup.html:470-471`).

### Н7. Бандл и сборка

- Сейчас (`d9cf21b7`, `npx vite build`): `index-*.js` 469,08 кБ, gzip 139,87 кБ; CSS 31,68 кБ.
- Оценка модулей среза (esbuild, `--minify`, ESM: `echarts/core`, `LineChart`, `GridComponent`, `MarkLineComponent`,
  `MarkAreaComponent`, `AxisPointerComponent`, `SVGRenderer`): около 507 кБ, gzip около 174 кБ. Это оценка, не
  измерение сборки Vite; план не подключает `AxisPointerComponent`, поэтому фактический рост будет немного меньше. Ожидаемый итог около 0,95–1 МБ (gzip около 300 кБ), Vite выведет предупреждение о чанке
  больше 500 кБ; сборку оно не роняет.
- Офлайн-сборка: CI ставит `npm --prefix ui ci --offline` и `./gradlew -PnpmOffline=true --offline`
  (`.github/workflows/runtime-quality.yml:148-151`, `build.gradle.kts:189-197`): пакет должен быть в `package-lock.json`.
- Модульных тестов UI (vitest) нет. Чистые функции проверяются Playwright-спеками без страницы, по образцу
  `ui/e2e/deep-adapters.spec.ts` и `ui/e2e/overview-adapters.spec.ts`. Они же уже проверяют `trackPoints`, `trackPath`,
  `valueY` (`overview-adapters.spec.ts:496-525`, `deep-adapters.spec.ts:167-178`).

## Решения дизайна

1. **Контейнеры остаются.** ADR 0035 (D5) говорит, что `SharedCursorChart.vue` и `DeepCursorChart.vue` «заменяются по
   срезам». В срезе 1 заменяется только `<svg>` внутри ряда: ползунок, `track-value-*`, `cursor-time`,
   `deep-cursor-time`, подписи шага и разрывов, `aria` остаются как есть. Поэтому `overview-live.spec.ts` и
   `deep-live.spec.ts` не правятся.
2. **Одна дорожка на экземпляр ECharts.** Стопка — это несколько `TimeChart` друг под другом. Поля сетки фиксированы
   в пикселях (`PLOT_INSET`: слева 56, справа 16), `containLabel` не используется: иначе дорожки разойдутся по оси времени.
3. **Курсор принадлежит Vue.** Источник истины — ползунок и состояние контейнера, как сейчас. `echarts.connect` и
   `axisPointer` ECharts не используются: `connect` синхронизирует только события ECharts и не видит клавиатуру.
   Линия курсора, значение у курсора и время на оси рисуются слоем Vue поверх дорожки (`aria-hidden`,
   `pointer-events: none`). При движении курсора `setOption` не вызывается вовсе, поэтому 8 дорожек по 6000 точек не
   перерисовываются на каждое `pointermove`.
4. **Указатель.** Обработчик остаётся на обёртке дорожек (`chart-plot`, `deep-plot`), доля шкалы считается по области
   графика без полей: `plotFraction(clientX, rect)`. Дальше работают существующие `nearestIndex` и `nearestTime`.
5. **Ось времени** — тип `value` (не `time`): подписи как сейчас, смещение от начала прогона со знаком
   (`formatOffset`). Деления по лестнице шагов (1, 2, 5, 10, 15, 30 с; 1, 2, 5, 10, 15, 30 мин; 1, 2, 3, 6, 12 ч),
   не больше шести интервалов, через `axisLabel.customValues` и `axisTick.customValues`. Подписи времени только у
   нижней дорожки стопки. Строка «0:00 · Время от начала прогона · конец» под стопкой остаётся: это текстовая
   альтернатива диапазона, и на неё опираются `overview.spec.ts:359` и `deep.spec.ts:222`.
6. **Ось значений:** три деления (низ, середина, верх), как у дорожек макета ниже 140 px; верх округляется вверх до
   1, 2, 2,5, 5 × 10ⁿ; подписи через `formatNumber` (ru-RU); сетка по делениям. Единица остаётся в заголовке ряда.
7. **Разрывы.** Между отрезками вставляется точка с `null`, `connectNulls: false`. У одиночной точки свой символ
   `circle` 6 px (`showSymbol: true`, `showAllSymbol: true`, у ряда `symbol: 'none'`). Положение по x задаёт адаптер:
   «Обзор» — начало интервала, «Глубокий анализ» — середина ячейки.
8. **Стадии:** `markArea` на ряде; в «Обзоре» x = `from_offset_ms`, в «Глубоком анализе» x = `originMs + from_offset_ms`
   (так полосы совпадают с нагрузкой и при `originMs = 0`). Полоса обрезается по оси, вне оси не рисуется. Стадии с
   ролью не `steady` слегка закрашены, `steady` без заливки. Подпись `id` стадии только на верхней дорожке и только
   если полоса занимает не меньше 10 % оси.
9. **Порог:** `markLine` по y со штрихом 5/4; цвет `--fail`, если правило нарушено, иначе `--text-muted`; подпись
   «порог N ед.» в начале линии. Длинная подпись в заголовке ряда (`deep-threshold-label-*`) остаётся.
10. **Прочее:** `animation: false`; ряды `silent: true`; хост ECharts `aria-hidden="true"`, `overflow: hidden`,
    `min-width: 0`; `ResizeObserver` вызывает `resize()`; `dispose()` при размонтировании; экземпляр хранится вне
    реактивности (обычная переменная, не `ref`). Регистрация модулей (`echarts.use`) один раз в модуле `TimeChart.vue`.

## Контракт компонента

`ui/src/shell/timeChart.ts` (импорт из `echarts` только `import type`, чтобы модуль загружался в Playwright без браузера):

```ts
export type ChartToken = 'brand' | 'warn' | 'fail' | 'text'
export interface ChartPoint { x: number; y: number }
export interface ChartThreshold { id: string; value: number; text: string; violated: boolean } // PR B
export interface ChartStage { id: string; fromMs: number; toMs: number; steady: boolean }
export interface ChartFrame { fromMs: number; toMs: number; originMs: number }
export interface ChartLane {
  key: string                    // используется в data-testid слоя курсора: cursor-line-<key>, cursor-value-<key>
  color: ChartToken
  segments: ChartPoint[][]       // отрезки без соединения между собой; x на общей шкале кадра
  lo: number                     // нижняя граница данных (с нулём и порогами)
  hi: number                     // верхняя граница данных (с порогами)
  thresholds?: ChartThreshold[]  // PR B
}
export interface ChartColors { brand: string; warn: string; fail: string; text: string; muted: string; border: string; fontFamily: string }
export interface LaneFlags { timeLabels: boolean; stageLabels: boolean }

export const PLOT_INSET = { left: 56, right: 16 } as const
export function laneGrid(flags: LaneFlags): { left: number; right: number; top: number; bottom: number; height: number }
export function niceCeil(value: number): number
export function valueAxis(lo: number, hi: number): { min: number; max: number; interval: number }
export function timeTicks(frame: ChartFrame, maxIntervals?: number): number[]
export function signedOffset(ms: number): string
export function lineData(segments: ChartPoint[][]): Array<{ value: [number, number | null]; symbol?: 'circle' }>
export function plotFraction(clientX: number, rect: { left: number; width: number }): number
export function cursorX(frame: ChartFrame, cursorMs: number): number   // 0..1000, два знака, как data-x сейчас
export function stageBands(result: AnalysisResult, originMs: number): ChartStage[]
export function laneOption(lane: ChartLane, frame: ChartFrame, stages: ChartStage[], flags: LaneFlags, colors: ChartColors): EChartsCoreOption
```

`ui/src/shell/TimeChart.vue`:

```ts
defineProps<{
  lane: ChartLane
  frame: ChartFrame
  stages: ChartStage[]
  flags: LaneFlags
  cursorMs: number | null
  cursorValue: string          // текст у курсора, например «120 RPS» или «нет данных»
  cursorTime: string | null    // время у оси; передаёт только нижняя дорожка
}>()
```

Корень: `<div data-testid="time-chart" :data-segments="lane.segments.length" :data-cursor-ms="cursorMs ?? ''">`.
Внутри хост ECharts (`aria-hidden="true"`) и слой курсора (`aria-hidden="true"`): `cursor-line-<key>` с `data-x`,
`cursor-value-<key>`, у нижней дорожки `cursor-time-mark`.

**Отложено, в код среза 1 не добавляется** (имена зарезервированы, чтобы срезы 2–5 расширяли контракт без поломки):
`ChartLane.baseline?: ChartPoint[][]` (прошлый релиз пунктиром), `ChartLane.trend?: [ChartPoint, ChartPoint]`
(линия тренда), `ChartLane.band?: { lo: ChartPoint[][]; hi: ChartPoint[][] }` (полоса min–max), `ChartThreshold.zones?`
(заливка зоны нарушения), `LaneFlags.sparkline?: boolean` (без осей и сетки, поля 2/4/4/4, без курсора, высота 44 px
как `tiles()` макета). Несколько рядов на одной дорожке (наложение «Обзора» в срезе 3) потребует `ChartLane.series[]`.

## Global Constraints

- Зависимость: `"echarts": "6.1.0"` точной версией в `dependencies`; `vue-echarts` не добавлять; других новых
  зависимостей нет.
- Импорт только из `echarts/core`, `echarts/charts`, `echarts/components`, `echarts/renderers`; рендерер только
  `SVGRenderer`; `TooltipComponent`, `DataZoomComponent`, `AriaComponent` не регистрировать.
- CSP сервера не меняется: никакого `'unsafe-inline'`, ни одного события `securitypolicyviolation` на «Обзоре» и в
  «Глубоком анализе».
- Ядро, API, схемы, `analysis-result.json`, отчёты и старый интерфейс не меняются; запросы к серверу те же
  (`overview.spec.ts:408-417` остаётся зелёным).
- Все видимые подписи на русском, числа через `formatNumber` (ru-RU), время через `formatOffset` со знаком.
- Ползунок, `aria-valuetext`, подсказки клавиатуры и `track-value-*` сохраняются без изменений текста.
- Нет горизонтальной прокрутки на 375 и 320 px; элементы управления не меньше 44 px.
- Проза документов русская, идентификаторы английские.

## Review Focus

1. **Смена темы при открытом графике.** Кнопка темы меняет `data-theme`; линии, подписи осей и сетка должны сразу
   перекраситься, а не остаться светлыми на тёмном фоне. Тест: задача 2, шаг 1, `the lanes follow the theme toggle`.
2. **Сужение окна после отрисовки.** SVG ECharts фиксирует ширину до срабатывания `ResizeObserver`; страница не
   должна получить горизонтальную прокрутку. Тест: задача 2, `lanes shrink with the viewport and never scroll the page`.
3. **Одиночная точка между разрывами** (ячейки `null` в ресурсах, один интервал между пропусками в нагрузке) должна
   быть видна, как сейчас кружок. Тест: задача 1, `lineData marks an isolated point with a symbol`.
4. **Один интервал нагрузки или ряд из нулей** (короткий прогон, ошибок нет): шкала не должна вырождаться (`min = max`).
   Тесты: задача 1, `valueAxis never collapses`, `loadFrame centres a single bucket`.
5. **Стадии, частично или целиком вне оси** (узкий период в «Глубоком анализе», стадия длиннее прогона): полосы
   обрезаются по оси, лишние не рисуются, подписи не налезают. Тест: задача 1, `stage areas are clipped to the frame`.

## Разбиение на PR

| PR | Ветка | Задачи | Что видит пользователь |
| --- | --- | --- | --- |
| A | `feat/ui-s1a-chart-overview` | 1–4 | «Нагрузка по времени» на новом компоненте: оси, сетка, стадии, курсор со значением |
| B | `feat/ui-s1b-chart-deep` | 5–8 | «Глубокий анализ» на том же компоненте, плюс порог с подписью на оси |

PR B начинается после слияния PR A. Каждый PR — одна законченная задача со своим фрагментом журнала. Модель
исполнения по `docs/development-process.md`: задачи 1–3 и 5–7 `gpt-5.6-terra` / `high`; задачи 4 и 8 `gpt-5.6-terra` /
`medium`; финальное ревью каждого PR `gpt-5.6-sol` / `max` (новая production-зависимость и CSP).

Команды ниже даны из корня worktree. Живые e2e идут через слот (`docs/development-process.md`, «Локальные тяжёлые
прогоны»), в PowerShell: `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- <spec> }`.

---

## PR A: компонент и «Обзор»

### Task 1: Зависимость и чистая модель дорожки

**Files:**

- Modify: `ui/package.json`, `ui/package-lock.json`
- Create: `ui/src/shell/timeChart.ts`
- Modify: `ui/src/shell/overview.ts` (добавить `loadLane`, `loadFrame` в конец файла)
- Test: `ui/e2e/time-chart-adapters.spec.ts`

**Interfaces:**

- Produces: всё из «Контракта компонента», кроме `ChartThreshold` и `markLine`; плюс
  `loadLane(series: LoadSeries, key: TrackKey): ChartLane` (цвета: `rps` brand, `errors` fail, `p95` warn; `lo = 0`,
  `hi = series.max[key]`, отрезки из `series.segments`, x = `startMs`) и
  `loadFrame(series: LoadSeries): ChartFrame` (от первой до последней точки, `originMs = 0`; одна точка: ±половина шага).

- [ ] **Step 1: Записать размер бандла до изменения**

Run: `npm --prefix ui ci && cd ui && npx vite build`
Expected: строка `dist/assets/index-*.js` около `469.08 kB │ gzip: 139.87 kB`. Записать обе цифры для описания PR.

- [ ] **Step 2: Добавить зависимость**

Run: `npm --prefix ui install echarts@6.1.0 --save-exact`
Expected: в `ui/package.json` строка `"echarts": "6.1.0"`; в `package-lock.json` `echarts`, `zrender` 6.1.0, `tslib`.
Затем `npm --prefix ui ci --offline` проходит (пакеты в кэше).

- [ ] **Step 3: Написать падающие тесты модели**

`ui/e2e/time-chart-adapters.spec.ts` (без фикстуры `page`):

```ts
test('niceCeil rounds up to 1, 2, 2.5, 5 times a power of ten', () => {
  expect([0, 7, 120, 900, 1180, 2.3, 0.07].map(niceCeil)).toEqual([1, 10, 200, 1000, 2000, 2.5, 0.1])
})
test('valueAxis never collapses', () => {
  expect(valueAxis(0, 120)).toEqual({ min: 0, max: 200, interval: 100 })
  expect(valueAxis(0, 0)).toEqual({ min: 0, max: 1, interval: 0.5 })
  expect(valueAxis(-3, 10)).toEqual({ min: -5, max: 10, interval: 7.5 })
})
test('timeTicks follow the step ladder and the run origin', () => {
  expect(timeTicks({ fromMs: 0, toMs: 6000, originMs: 0 })).toEqual([0, 1000, 2000, 3000, 4000, 5000, 6000])
  expect(timeTicks({ fromMs: 0, toMs: 14_390_000, originMs: 0 })).toEqual([0, 3_600_000, 7_200_000, 10_800_000])
  const origin = 1_767_225_600_000
  expect(timeTicks({ fromMs: origin - 61_000, toMs: origin + 119_000, originMs: origin }).map((t) => signedOffset(t - origin)))
    .toEqual(['-1:00', '-0:30', '0:00', '0:30', '1:00', '1:30'])
})
test('lineData breaks between segments and marks an isolated point with a symbol', () => {
  const data = lineData([[{ x: 0, y: 1 }, { x: 1000, y: 2 }], [{ x: 4000, y: 3 }]])
  expect(data.map((item) => item.value)).toEqual([[0, 1], [1000, 2], [2500, null], [4000, 3]])
  expect(data.map((item) => item.symbol ?? '')).toEqual(['', '', '', 'circle'])
})
test('plotFraction ignores the axis gutters and clamps', () => {
  const rect = { left: 100, width: 1072 }
  expect(plotFraction(100, rect)).toBe(0)
  expect(plotFraction(100 + PLOT_INSET.left, rect)).toBe(0)
  expect(plotFraction(100 + 1072 - PLOT_INSET.right, rect)).toBe(1)
  expect(plotFraction(100 + PLOT_INSET.left + 500, rect)).toBe(0.5)
})
test('loadFrame centres a single bucket', () => {
  expect(loadFrame(loadSeries([bucket(500, 1, 0, 1)], 1))).toEqual({ fromMs: 0, toMs: 1000, originMs: 0 })
  expect(cursorX({ fromMs: 0, toMs: 6000, originMs: 0 }, 4000)).toBe(666.67)
})
test('stageBands reads stage_binding offsets on the given origin', () => {
  const result = { evidence: [binding] } as never   // binding: fixtures/stages/stage-binding.sample.json
  expect(stageBands(result, 0)).toEqual([
    { id: 'ramp-up', fromMs: 0, toMs: 40_000, steady: false },
    { id: 'steady', fromMs: 40_000, toMs: 100_000, steady: true },
    { id: 'ramp-down', fromMs: 100_000, toMs: 120_000, steady: false },
  ])
  expect(stageBands({ evidence: [] } as never, 0)).toEqual([])
})
test('laneOption is static, svg-safe and fixes the gutters', () => {
  const option = laneOption(lane, { fromMs: 0, toMs: 6000, originMs: 0 }, [], { timeLabels: true, stageLabels: false }, colors)
  expect(option.animation).toBe(false)
  expect(option).not.toHaveProperty('tooltip')
  expect(option.grid).toMatchObject({ left: 56, right: 16, bottom: 30 })
  expect(option.xAxis).toMatchObject({ type: 'value', min: 0, max: 6000, axisLabel: { show: true, customValues: [0, 1000, 2000, 3000, 4000, 5000, 6000] } })
  expect(option.yAxis).toMatchObject({ min: 0, max: 200, interval: 100 })
})
test('stage areas are clipped to the frame and labelled only when wide enough', () => {
  const stages = stageBands({ evidence: [binding] } as never, 0)
  const areas = (frame: ChartFrame, stageLabels: boolean) => (laneOption(lane, frame, stages, { timeLabels: false, stageLabels }, colors).series as any)[0].markArea.data
  expect(areas({ fromMs: 0, toMs: 119_000, originMs: 0 }, true).map(([a, b]: any) => [a.name, a.xAxis, b.xAxis, a.label.show]))
    .toEqual([['ramp-up', 0, 40_000, true], ['steady', 40_000, 100_000, true], ['ramp-down', 100_000, 119_000, true]])
  expect(areas({ fromMs: 50_000, toMs: 104_000, originMs: 0 }, true).map(([a]: any) => [a.name, a.label.show])).toEqual([['steady', true], ['ramp-down', false]])
  expect(areas({ fromMs: 0, toMs: 119_000, originMs: 0 }, false).every(([a]: any) => !a.label.show)).toBe(true)
})
```

`lane`, `colors`, `bucket` и `binding` — локальные фикстуры спека (`binding` читается как в
`ui/e2e/stage-window.spec.ts:13`).

- [ ] **Step 4: Убедиться, что тесты падают**

Run (PowerShell): `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- e2e/time-chart-adapters.spec.ts }`
Expected: FAIL, модуль `../src/shell/timeChart` не найден.

- [ ] **Step 5: Реализовать `timeChart.ts` и адаптеры в `overview.ts`**

Сигнатуры из «Контракта». Решения, которые тесты не определяют:

- `laneGrid`: `top = stageLabels ? 18 : 6`, `bottom = timeLabels ? 30 : 6`, `height = 78 + top + bottom`.
- `timeTicks`: шаг — первый шаг лестницы (решение 5), при котором `(toMs - fromMs) / step <= maxIntervals` (по
  умолчанию 6); деления — `originMs + k·step` внутри `[fromMs, toMs]`.
- `lineData`: точка разрыва ставится посередине между концом отрезка и началом следующего.
- `laneOption`: `series[0]` — `type: 'line'`, `silent: true`, `symbol: 'none'`, `showSymbol: true`,
  `showAllSymbol: true`, `connectNulls: false`, `lineStyle.width: 2`, цвет `colors[lane.color]`; символ одиночной точки
  `symbolSize: 6`. Сетка: `yAxis.splitLine` цвета `colors.border`; подписи осей `colors.muted`, `fontSize: 12`,
  `fontFamily: colors.fontFamily`; подписи y — `formatNumber`, подписи x — `signedOffset(v - frame.originMs)`;
  `xAxis.splitLine.show: false`. `markArea`: `silent: true`; заливка не-steady стадий `colors.border` с
  `opacity: 0.25`, steady прозрачная; подпись `position: 'insideTop'`, цвет `colors.muted`.

- [ ] **Step 6: Тесты зелёные**

Run: тот же, что в шаге 4. Expected: PASS. Затем `npm --prefix ui run typecheck` и `npm --prefix ui run lint`: без ошибок.

- [ ] **Step 7: Commit**

```bash
git add ui/package.json ui/package-lock.json ui/src/shell/timeChart.ts ui/src/shell/overview.ts ui/e2e/time-chart-adapters.spec.ts
git commit -m "feat(ui): add ECharts lane model for shell charts"
```

### Task 2: Компонент `TimeChart.vue` в «Обзоре»

**Files:**

- Create: `ui/src/shell/TimeChart.vue`
- Modify: `ui/src/shell/SharedCursorChart.vue:1-35` (скрипт), `:65-98` (`<svg>` ряда), `:150-213` (стили)
- Test: `ui/e2e/overview.spec.ts`

**Interfaces:**

- Consumes: задача 1 (`ChartLane`, `ChartFrame`, `laneOption`, `laneGrid`, `plotFraction`, `cursorX`, `loadLane`,
  `loadFrame`, `PLOT_INSET`).
- Produces: компонент `TimeChart` с пропсами из «Контракта»; `SharedCursorChart` принимает новый необязательный проп
  `stages: ChartStage[]` (по умолчанию `[]`; заполняется в задаче 3).

- [ ] **Step 1: Написать падающие e2e-проверки в `overview.spec.ts`**

Новые тесты (фикстура `failing` и шесть интервалов из `overview.spec.ts:65-68`):

```ts
test('each lane has value ticks and only the bottom lane has time ticks', async ({ page }) => {
  await openOverview(page)
  const texts = (key: string) => page.getByTestId(`track-${key}`).locator('svg text').allTextContents()
  await expect.poll(() => texts('rps')).toEqual(expect.arrayContaining(['0', '100', '200']))
  expect(await texts('p95')).toEqual(expect.arrayContaining(['0', formatNumber(500), formatNumber(1000), '0:00', '0:04', '0:06']))
  expect(await texts('rps')).not.toContain('0:04')
})
test('hovering shows the value at the cursor in every lane and the time on the axis', async ({ page }) => {
  await openOverview(page)
  const box = (await page.getByTestId('chart-plot').boundingBox())!
  const x = box.x + PLOT_INSET.left + (box.width - PLOT_INSET.left - PLOT_INSET.right) * (4000 / 6000)
  await page.mouse.move(x, box.y + 8)
  await expect(slider(page)).toHaveValue('3')
  await expect(page.getByTestId('cursor-value-p95')).toHaveText(`${formatNumber(900)} ${OVERVIEW_LABELS.unitMs}`)
  await expect(page.getByTestId('cursor-value-rps')).toHaveText(`${formatNumber(90)} ${OVERVIEW_LABELS.unitRps}`)
  await expect(page.getByTestId('cursor-time-mark')).toHaveText('0:04')
  await expect(page.getByTestId('cursor-time-mark')).toHaveCount(1)
})
test('the charts raise no CSP violation under the real server policy', async ({ page }) => {
  expect((await page.request.get('/')).headers()['content-security-policy']).toContain("style-src 'self'")
  await page.addInitScript(() => {
    (window as never as { csp: string[] }).csp = []
    document.addEventListener('securitypolicyviolation', (event) => (window as never as { csp: string[] }).csp.push(`${event.violatedDirective} ${event.blockedURI}`))
  })
  await openOverview(page)
  await expect(page.getByTestId('track-rps').locator('svg text').first()).toBeVisible()
  const box = (await page.getByTestId('chart-plot').boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + 8)
  await slider(page).focus()
  await page.keyboard.press('End')
  await page.locator('.theme-toggle').click()
  expect(await page.evaluate(() => (window as never as { csp: string[] }).csp)).toEqual([])
})
test('the lanes follow the theme toggle', async ({ page }) => {
  await openOverview(page)
  const line = page.getByTestId('track-rps').locator('svg path[stroke-width="2"]').first()
  const brand = () => page.evaluate(() => { const probe = document.createElement('span'); probe.style.color = 'var(--brand)'; document.body.append(probe); const color = getComputedStyle(probe).color; probe.remove(); return color })
  await expect.poll(async () => line.evaluate((el) => getComputedStyle(el).stroke)).toBe(await brand())
  const before = await brand()
  await page.locator('.theme-toggle').click()
  await expect.poll(async () => line.evaluate((el) => getComputedStyle(el).stroke)).toBe(await brand())
  expect(await brand()).not.toBe(before)
})
test('lanes shrink with the viewport and never scroll the page', async ({ page }) => {
  await openOverview(page)
  await page.setViewportSize({ width: 320, height: 800 })
  await slider(page).focus()
  await page.keyboard.press('End')   // подписи курсора у правого края не должны вылезать за дорожку
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  const lane = (await page.getByTestId('track-rps').getByTestId('time-chart').boundingBox())!
  await expect.poll(async () => (await page.getByTestId('track-rps').locator('svg').first().boundingBox())!.width).toBeLessThanOrEqual(lane.width)
})
```

Правка существующего теста `the load chart draws three tracks on one time axis...` (`overview.spec.ts:276-279`):
вместо `toHaveCount(2)` у `track-line-${key}` проверить `time-chart` внутри `track-${key}` на
`data-segments="2"`. Причина: линия ряда теперь
путь внутри SVG ECharts, у неё нет `data-testid`; число отрезков, переданных в ECharts, — тот же наблюдаемый факт
«линии не соединяются через пропуск». Остальные строки теста (`cursor-line-rps` count 0 и т. д.) не меняются.

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- e2e/overview.spec.ts }`
Expected: новые тесты и правленый тест FAIL (`time-chart`, `cursor-value-*` не найдены); остальные PASS.

- [ ] **Step 3: Реализовать `TimeChart.vue` и подключить его в `SharedCursorChart.vue`**

- `TimeChart.vue`: в модуле `echarts.use([LineChart, GridComponent, MarkAreaComponent, SVGRenderer])` (в задаче 5
  добавится `MarkLineComponent`). `onMounted`: `init(host, null, { renderer: 'svg' })`, `setOption(laneOption(...),
  { notMerge: true })`. `watch` пропсов `lane`, `frame`, `stages`, `flags` → тот же `setOption`; `cursorMs`,
  `cursorValue`, `cursorTime` в ECharts не передаются. Цвета: `getComputedStyle(document.documentElement)` для
  `--brand`, `--warn`, `--fail`, `--text`, `--text-muted`, `--border` и `getComputedStyle(document.body).fontFamily`;
  `MutationObserver` на `document.documentElement` (`attributeFilter: ['data-theme']`) повторяет `setOption`.
  `ResizeObserver` на хосте → `resize()`. `onBeforeUnmount`: отключить наблюдатели, `dispose()`.
- Высота хоста — `laneGrid(flags).height` через привязку `:style` (Vue пишет её через CSSOM, CSP не мешает). Слой
  курсора — абсолютно позиционированный блок с отступами `PLOT_INSET` и `laneGrid(...).top/bottom`; в нём
  `<svg viewBox="0 0 1000 100" preserveAspectRatio="none">` с `<line data-testid="cursor-line-<key>" :data-x>` и два
  `<span>` (`cursor-value-<key>`, `cursor-time-mark`), позиция `left` в процентах от `cursorX / 10`. Подписи
  прижимаются к краям области графика (как `setCursor` макета, `lt-verdict-ui-mockup.html:410`: у левого края
  выравнивание по началу, у правого по концу), у корня дорожки `overflow: hidden`, `position: relative`, `min-width: 0`.
- `SharedCursorChart.vue`: убрать `<svg>` ряда (`:65-98`), поставить `<TimeChart :lane="loadLane(series, track.key)"
  :frame="frame" :stages="stages" :flags="{ timeLabels: track.key === 'p95', stageLabels: track.key === 'rps' }"
  :cursor-ms="cursorMs" :cursor-value="..." :cursor-time="track.key === 'p95' ? readout?.time ?? null : null" />`;
  `frame = loadFrame(series)`, `cursorMs = series.points[cursor]?.startMs ?? null`. Текст у курсора — `readout[key]`
  плюс единица (`unitRps`, для p95 `unitMs`, у ошибок без единицы). В `onPointer` долю считать через
  `plotFraction(event.clientX, rect)`. `lane`, `frame`, `flags` и `stages` каждой дорожки вычислять в `computed`, а не
  литералами в шаблоне: иначе каждое движение курсора создаёт новые объекты, срабатывает `watch` и `setOption`.
  Стили полосок `svg`, `polyline`, `.shared-chart__cursor`, `.shared-chart__axis` удалить, строку подписей
  `.shared-chart__axis-labels` оставить.
- Порядок в DOM дорожки: сначала хост ECharts, потом слой курсора. Тогда `locator('svg').first()` внутри ряда — SVG
  ECharts, а наведение Playwright попадает в него сквозь слой с `pointer-events: none` (на это опирается
  `deep.spec.ts:175`).
- Импорты в `overview.spec.ts`: `PLOT_INSET` из `../src/shell/timeChart`, `formatNumber` из `../src/shell/overview`,
  `readFileSync` и `fileURLToPath` для фикстуры стадий (задача 3).

- [ ] **Step 4: Тесты зелёные**

Run: тот же, что в шаге 2. Expected: весь `overview.spec.ts` PASS, включая axe в двух темах и раскладку 1280/375 px.
Тест `hovering the plot moves the same cursor to the nearest interval` (`overview.spec.ts:322-336`) проходит без правки:
края попадают в поля и прижимаются к краям шкалы, точка `0,6·ширины` при поле 56/16 px соответствует примерно 3,5 с,
ближайший интервал 4 с, индекс 3. Если он упадёт, это ошибка в `plotFraction`, а не повод править тест.

- [ ] **Step 5: Commit**

```bash
git add ui/src/shell/TimeChart.vue ui/src/shell/SharedCursorChart.vue ui/e2e/overview.spec.ts
git commit -m "feat(ui): draw overview load lanes with ECharts axes and cursor"
```

### Task 3: Полосы стадий в «Обзоре»

**Files:**

- Modify: `ui/src/shell/OverviewPanel.vue:9,26,362-365`
- Test: `ui/e2e/overview.spec.ts`

**Interfaces:**

- Consumes: `stageBands(result, originMs)` (задача 1), проп `stages` у `SharedCursorChart` (задача 2).

- [ ] **Step 1: Написать падающий тест**

```ts
const binding = JSON.parse(readFileSync(fileURLToPath(new URL('../../fixtures/stages/stage-binding.sample.json', import.meta.url)), 'utf8'))
test('declared stages are drawn on every lane and named on the top lane', async ({ page }) => {
  await openOverview(page, { ...failing, evidence: [...failing.evidence, binding] }, runOf(120))
  const texts = (key: string) => page.getByTestId(`track-${key}`).locator('svg text').allTextContents()
  await expect.poll(() => texts('rps')).toEqual(expect.arrayContaining(['ramp-up', 'steady', 'ramp-down']))
  expect(await texts('p95')).not.toContain('steady')
})
test('a result without stages draws no stage names', async ({ page }) => {
  await openOverview(page, failing, runOf(120))
  await expect(page.getByTestId('track-rps').locator('svg text').first()).toBeVisible()
  expect(await page.getByTestId('track-rps').locator('svg text').allTextContents()).not.toContain('steady')
})
```

- [ ] **Step 2: Убедиться, что первый тест падает**

Run: `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- e2e/overview.spec.ts -g "stage" }`
Expected: FAIL, подписей стадий нет.

- [ ] **Step 3: Передать стадии**

В `OverviewPanel.vue`: `const stages = computed(() => stageBands(props.result, 0))` и `:stages="stages"` у
`SharedCursorChart`. Смещения стадий и `bucket_start_ms` отсчитываются от одного начала прогона (Н1, Н3).

- [ ] **Step 4: Тесты зелёные**

Run: тот же, без `-g`. Expected: весь `overview.spec.ts` PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/shell/OverviewPanel.vue ui/e2e/overview.spec.ts
git commit -m "feat(ui): show declared load stages on overview lanes"
```

### Task 4: Документация, журнал, бандл, полная проверка PR A

**Files:**

- Modify: `docs/user/slice-1-local-analysis.md:520`
- Create: `changelog.d/ui-s1a-chart-overview.changed.md`

- [ ] **Step 1: Обновить строку «Обзор» в таблице вкладок**

Вместо «график нагрузки с общим курсором» написать: «график нагрузки: три дорожки с осью значений, делениями
времени, сеткой и полосами стадий (если стадии объявлены), общий курсор показывает значение у курсора и время на оси».

- [ ] **Step 2: Фрагмент журнала**

`changelog.d/ui-s1a-chart-overview.changed.md`:

```markdown
- «Нагрузка по времени» на вкладке «Обзор» новой оболочки (срез UI 1) нарисована на Apache ECharts: у каждой
  дорожки ось значений с делениями и сетка, у нижней деления времени от начала прогона, при объявленных стадиях
  нагрузки полосы стадий с подписями; курсор мышью и с клавиатуры показывает значение у курсора на каждой дорожке
  и время на оси. Ползунок, подписи и данные прежние; API, ядро, отчёты и прежний интерфейс не менялись. UI получил
  новую зависимость `echarts` 6.1.0, бандл вырос (цифры в PR).
```

- [ ] **Step 3: Измерить бандл после**

Run: `cd ui && npx vite build`
Expected: сборка проходит; записать размер `index-*.js` и gzip рядом с цифрами шага 1 задачи 1 в описание PR.

- [ ] **Step 4: Полная проверка**

Run, по порядку:

- `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run build`;
- `npm --prefix ui ci --offline`;
- `python tools/changelog_assemble.py --check`;
- `npx --yes markdownlint-cli2@0.23.2 docs/user/slice-1-local-analysis.md changelog.d/ui-s1a-chart-overview.changed.md`;
- `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e }` (весь основной набор);
- `. ../_tools/ltv-slot.ps1; Invoke-LtvExclusive { ./gradlew check }`.

Expected: всё зелёное; в `git status` только файлы из «EXPECTED FILES TO CHANGE» для PR A.

- [ ] **Step 5: Commit**

```bash
git add docs/user/slice-1-local-analysis.md changelog.d/ui-s1a-chart-overview.changed.md
git commit -m "docs(ui): describe ECharts overview load chart"
```

---

## PR B: «Глубокий анализ»

### Task 5: Порог на дорожке

**Files:**

- Modify: `ui/src/shell/timeChart.ts` (тип `ChartThreshold`, поле `ChartLane.thresholds`, `markLine` в `laneOption`)
- Modify: `ui/src/shell/labels.ts:317-318` (после `thresholdLabel` добавить `thresholdMark`)
- Modify: `ui/src/shell/deep.ts` (добавить `deepLane` в конец файла)
- Test: `ui/e2e/time-chart-adapters.spec.ts`

**Interfaces:**

- Produces: `DEEP_LABELS.thresholdMark: (value: string, unit: string) => string` (текст `порог ${value} ${unit}`);
  `deepLane(track: DeepTrack, axis: TimeAxis): ChartLane` (цвет `load-rps` brand, `load-p95` warn, ресурсы text;
  отрезки из `track.segments` с точками внутри оси, x = середина ячейки, как `trackPath`; `lo`, `hi` из `trackScale`;
  пороги с `text = DEEP_LABELS.thresholdMark(formatNumber(value), track.unit)`).

- [ ] **Step 1: Падающие тесты**

```ts
test('thresholds become dashed mark lines coloured by violation', () => {
  const thresholds = [{ id: 'cpu-high', value: 5, text: 'порог 5 %', violated: true }, { id: 'queue-ok', value: 50, text: 'порог 50 %', violated: false }]
  const line = (laneOption({ ...lane, thresholds }, frame, [], flags, colors).series as any)[0].markLine
  expect(line.silent).toBe(true)
  expect(line.data.map((item: any) => [item.yAxis, item.label.formatter, item.lineStyle.color])).toEqual([[5, 'порог 5 %', colors.fail], [50, 'порог 50 %', colors.muted]])
})
test('deepLane keeps cell middles, breaks at nulls and carries thresholds', () => {
  const track = resourceTrack(entry('cpu'), values('cpu', [2, null, 4], [4, 0, 4]), valueGrid(0, 3), [{ ruleId: 'high', operator: 'gt', value: 8, violated: false }])
  const result = deepLane(track, { fromMs: 0, toMs: 180_000 })
  expect(result.segments).toEqual([[{ x: 30_000, y: 2 }], [{ x: 150_000, y: 4 }]])
  expect([result.lo, result.hi]).toEqual([0, 8])
  expect(result.thresholds).toEqual([{ id: 'high', value: 8, text: DEEP_LABELS.thresholdMark(formatNumber(8), '%'), violated: false }])
})
```

(`entry`, `values`, `valueGrid` — как в `deep-adapters.spec.ts:12-17`; ожидаемые x совпадают с `trackPath` в
`deep-adapters.spec.ts:171`.)

- [ ] **Step 2: Убедиться, что падают**

Run: `. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- e2e/time-chart-adapters.spec.ts }`
Expected: FAIL (`deepLane`, `thresholdMark` не определены).

- [ ] **Step 3: Реализовать**

`markLine`: `silent: true`, `symbol: 'none'`, данные `{ name: id, yAxis: value, lineStyle: { type: [5, 4], width: 1.5,
color }, label: { formatter: text, position: 'insideStartTop', color } }`.

- [ ] **Step 4: Тесты зелёные, typecheck и lint чистые**

- [ ] **Step 5: Commit**

```bash
git add ui/src/shell/timeChart.ts ui/src/shell/labels.ts ui/src/shell/deep.ts ui/e2e/time-chart-adapters.spec.ts
git commit -m "feat(ui): add threshold mark lines to the chart lane"
```

### Task 6: `DeepCursorChart` на `TimeChart`

**Files:**

- Modify: `ui/src/shell/DeepCursorChart.vue:1-50` (скрипт), `:92-149` (`<svg>` ряда), `:186-208` (стили)
- Modify: `ui/src/shell/TimeChart.vue` (зарегистрировать `MarkLineComponent`)
- Test: `ui/e2e/deep.spec.ts`

**Interfaces:**

- Consumes: `deepLane` (задача 5), `TimeChart` (задача 2).
- Produces: `DeepCursorChart` принимает необязательный проп `stages: ChartStage[]` (по умолчанию `[]`).

- [ ] **Step 1: Новые проверки и правки существующих тестов**

Новые тесты:

```ts
test('resource lanes show value ticks and a threshold label on the axis', async ({ page }) => {
  fixtureApi(page, { passRule: true })
  await openDeep(page)
  const cpu = page.getByTestId('deep-track-cpu').locator('svg text')
  await expect(cpu.filter({ hasText: DEEP_LABELS.thresholdMark(formatNumber(5), '%') })).toHaveCount(1)
  expect(await cpu.allTextContents()).toEqual(expect.arrayContaining(['0', formatNumber(1000), formatNumber(2000)]))
})
test('hovering the middle of the plot reads every lane at the middle of the period', async ({ page }) => {
  const mock = fixtureApi(page)
  await openDeep(page)
  const box = (await page.getByTestId('deep-plot').boundingBox())!
  await page.mouse.move(box.x + PLOT_INSET.left + (box.width - PLOT_INSET.left - PLOT_INSET.right) / 2, box.y + 30)
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-ms', '90000')
  await expect(page.getByTestId('cursor-value-cpu')).toHaveText(`${formatNumber(mock.sourceValue('cpu', 90), 2)} %`)
  await expect(page.getByTestId('cursor-time-mark')).toHaveText('1:30')
})
test('deep charts raise no CSP violation under the real server policy', async ({ page }) => {
  const csp = (await page.request.get('/')).headers()['content-security-policy']
  test.skip(csp === undefined, 'deep-ui.config.ts serves through vite without CSP; the main config runs this check')
  // далее как в overview.spec.ts: addInitScript, openDeep, наведение, End на deep-cursor, переключение темы, csp пуст
})
```

Правки существующих тестов (причины в разделе «Правки существующих тестов»):

- `deep.spec.ts:121`: `track-line-load-rps` `toHaveCount(1)` →
  `getByTestId('deep-track-load-rps').getByTestId('time-chart')` `toHaveAttribute('data-segments', '1')`.
- `deep.spec.ts:137`: `deep-threshold-cpu-high` `toHaveCount(1)` → текст `DEEP_LABELS.thresholdMark(formatNumber(5), '%')`
  в `deep-track-cpu svg text`, `toHaveCount(1)`. Строка 138 (`deep-threshold-label-cpu-high`) без изменений.
- `deep.spec.ts:149-150,155-156,158`: `failedLine` и `passedLine` → `svg text` с `thresholdMark` в `deep-track-cpu`
  (5 %) и `deep-track-queue` (5000 %); вместо `data-violated` и `stroke` сравнить `getComputedStyle(el).fill`:
  разный. Строки 151-154 и 157 (подписи в заголовке) без изменений.
- `deep.spec.ts:174-178`: наведение в центр `deep-plot` → в центр области графика
  (`box.x + PLOT_INSET.left + (box.width - PLOT_INSET.left - PLOT_INSET.right) / 2`); ожидания `90000` и значения в
  `track-value-*` без изменений.
- `deep.spec.ts:232`: `track-line-cpu` `toHaveCount(2)` → `data-segments="2"` у `time-chart` в `deep-track-cpu`.
- `deep.spec.ts:246-254`: `polyline` → `svg path[stroke-width="2"]` первой дорожки ряда; проверки `fill` = `none` и
  `stroke` не `none` без изменений.
- `deep.spec.ts:313`: `track-line-cpu` `.last()` видим → `time-chart` в `deep-track-cpu` видим и
  `toHaveAttribute('data-segments', /^[1-9]/)`.

- [ ] **Step 2: Убедиться, что падают**

Run (быстрый цикл без CSP): `cd ui && npx playwright test -c e2e/deep-ui.config.ts e2e/deep.spec.ts`
Expected: новые и правленые тесты FAIL, тест CSP SKIPPED, остальные PASS.

- [ ] **Step 3: Реализовать**

`DeepCursorChart.vue`: убрать `<svg>` (`:92-149`), поставить `TimeChart` с `lane = deepLane(track, axis)`; `lane`,
`flags`, `frame` и `stages` — в `computed`, как в задаче 2; `frame = { ...axis, originMs }`, `flags = { timeLabels: последняя дорожка, stageLabels:
первая }`, `cursorValue = valueText(track)`, `cursorTime` у последней дорожки `offset(relativeMs)`. В `onPointer` —
`nearestTime(axis, plotFraction(event.clientX, rect))`. Подписи порогов в заголовке (`:84-90`) оставить. Стили
`svg`, `polyline`, `circle`, `.axis`, `.threshold`, `.cursor` удалить, `.threshold-text` оставить.

- [ ] **Step 4: Тесты зелёные**

Run: `cd ui && npx playwright test -c e2e/deep-ui.config.ts`, затем
`. ../_tools/ltv-slot.ps1; Invoke-LtvE2E { npm --prefix ui run e2e -- e2e/deep.spec.ts e2e/deep-live.spec.ts }`
Expected: PASS; на основном конфиге тест CSP выполняется, не пропускается. `deep-live.spec.ts` без правок.

- [ ] **Step 5: Commit**

```bash
git add ui/src/shell/DeepCursorChart.vue ui/src/shell/TimeChart.vue ui/e2e/deep.spec.ts
git commit -m "feat(ui): draw deep analysis lanes with ECharts and threshold marks"
```

### Task 7: Полосы стадий в «Глубоком анализе»

**Files:**

- Modify: `ui/src/shell/DeepAnalysisPanel.vue:12-16,320-325`
- Test: `ui/e2e/deep.spec.ts` (опция фикстуры `stages?: boolean` добавляет `binding` из
  `fixtures/stages/stage-binding.sample.json` в `evidence`, `deep.spec.ts:11,20-23`)

- [ ] **Step 1: Падающий тест**

```ts
test('declared stages are drawn in deep analysis on the run clock', async ({ page }) => {
  fixtureApi(page, { stages: true })
  await openDeep(page)
  const top = page.getByTestId('deep-track-load-rps').locator('svg text')
  await expect.poll(() => top.allTextContents()).toEqual(expect.arrayContaining(['ramp-up', 'steady', 'ramp-down']))
  expect(await page.getByTestId('deep-track-cpu').locator('svg text').allTextContents()).not.toContain('steady')
})
```

Фикстура `deep.spec.ts` по умолчанию даёт ось 180 с от `origin`: все три стадии (0–120 с) на оси и шире 10 %.

- [ ] **Step 2: Убедиться, что падает**

Run: `cd ui && npx playwright test -c e2e/deep-ui.config.ts e2e/deep.spec.ts -g "declared stages"`
Expected: FAIL.

- [ ] **Step 3: Передать стадии**

`const stages = computed(() => stageBands(props.result, originMs))`, `:stages="stages"` у `DeepCursorChart`. Полосы
стоят на `originMs + from_offset_ms`, как и нагрузка (Н2, решение 8).

- [ ] **Step 4: Тесты зелёные**

Run: `cd ui && npx playwright test -c e2e/deep-ui.config.ts`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/shell/DeepAnalysisPanel.vue ui/e2e/deep.spec.ts
git commit -m "feat(ui): show declared load stages in deep analysis lanes"
```

### Task 8: Документация, журнал, полная проверка PR B

**Files:**

- Modify: `docs/user/slice-1-local-analysis.md:521`
- Create: `changelog.d/ui-s1b-chart-deep.changed.md`

- [ ] **Step 1: Строка «Глубокий анализ»** — добавить: «оси значений и времени, сетка, порог правила линией с
  подписью значения на графике, полосы стадий, значение у курсора на каждой дорожке».
- [ ] **Step 2: Фрагмент журнала** в формате `changelog.d/README.md`: что изменилось в «Глубоком анализе», что API,
  ядро, отчёты и прежний интерфейс не менялись, что ряды p50 и p99 по времени пока не показываются.
- [ ] **Step 3: Полная проверка** — тот же список, что в задаче 4, шаг 4 (с `ui-s1b-chart-deep.changed.md`).
  Записать размер бандла после PR B (ожидается рост на единицы кБ за `MarkLineComponent`).
- [ ] **Step 4: Commit**

```bash
git add docs/user/slice-1-local-analysis.md changelog.d/ui-s1b-chart-deep.changed.md
git commit -m "docs(ui): describe ECharts deep analysis lanes"
```

## Правки существующих тестов

Исключение «бюджет консолидации» к этим PR не применяется (это `feat/`, а не `refactor/`): каждая правка — следствие
изменения поведения. Общая причина: полоска на самодельном `<svg>` с `polyline` и `line`, у которых были `data-testid`,
заменена SVG ECharts, где у отдельных путей нет атрибутов теста. Ожидания по данным (число отрезков, нарушено ли
правило, значение под курсором) сохраняются и проверяются через атрибуты `time-chart` и текст SVG.

| Тест | Строки | Что меняется | Почему |
| --- | --- | --- | --- |
| `overview.spec.ts` «the load chart draws three tracks…» | 278 | счёт `track-line-*` → `data-segments` | у линии ECharts нет `data-testid` |
| `deep.spec.ts` «tab follows overview…» | 121 | то же для `load-rps` | то же |
| `deep.spec.ts` «failure-first selection…» | 137 | `<line>` порога → текст порога в SVG | порог рисует `markLine` |
| `deep.spec.ts` «only failed rule thresholds…» | 149-150, 155-156, 158 | `stroke` линии → `fill` подписи порога в SVG | то же |
| `deep.spec.ts` «shared cursor reads every track…» | 174-178 | центр обёртки → центр области графика | слева 56 px заняты осью Y, центр обёртки больше не середина шкалы |
| `deep.spec.ts` «null cells break paths…» | 232 | счёт `track-line-cpu` → `data-segments` | у линии ECharts нет `data-testid` |
| `deep.spec.ts` «deep chart polylines remain unfilled…» | 246-254 | `polyline` → путь ряда `stroke-width="2"` | `polyline` больше нет |
| `deep.spec.ts` «catalog and values pages are merged» | 313 | `track-line-cpu` → `time-chart` с отрезками | у линии ECharts нет `data-testid` |

Проверено, что другие спеки новой оболочки графиков не видят или не опираются на их текст: `stage-window.spec.ts`,
`overview-russian-tail.spec.ts`, `verdict-first.spec.ts`, `zoom-200.spec.ts`, `shell-scroll.spec.ts` отдают пустой
`/buckets` (график не рисуется, подписей стадий в SVG нет); в спеках с живым сервером и непустой нагрузкой
(`*-live.spec.ts`, `new-analysis*.spec.ts`, `saved-analysis.spec.ts` и др.) нет локаторов `getByText`, которые
совпали бы с подписями осей (единственный близкий, `saved-analysis.spec.ts:84` `'999 ms'`, ищет английское «ms»), а
`saved-analysis.spec.ts:152` (`#normalized-data svg polyline`) относится к таблицам, не к этим графикам.

Не меняются: `overview-live.spec.ts`, `deep-live.spec.ts`, `overview-adapters.spec.ts`, `deep-adapters.spec.ts`,
`security-a11y.spec.ts`, тесты ползунка, `cursor-line-*` (`overview.spec.ts:128,283,301-313,333-335`: слой курсора
сохраняет `data-testid` и `data-x`), подписи диапазона (`overview.spec.ts:359`, `deep.spec.ts:222`).

## Риски

1. **Бандл.** Оценка +507 кБ (gzip +174 кБ) удваивает JS. Для локального инструмента время загрузки не критично, но
   Vite предупредит о чанке больше 500 кБ. Решение — вопрос В1.
2. **Опора на `customValues`.** Деления оси времени задаются `axisLabel.customValues` и `axisTick.customValues`
   (есть с ECharts 5.5.1). Если в 6.1.0 они поведут себя иначе, задача 1, шаг 6 и задача 2, шаг 4 это покажут;
   запасной путь — `interval` при `min`, кратном шагу.
3. **CSP.** Проверено чтением исходников, в браузере не запускалось. Тест CSP задачи 2 закрывает это на основном
   конфиге. Любое будущее подключение HTML-подсказки ECharts нарушит CSP; это нужно помнить в срезах 2–5.
4. **Отображение мыши.** Из-за поля оси Y доля шкалы считается по области графика. В `overview.spec.ts:322-336`
   расчёт показывает, что ожидания сохраняются; при другой ширине колонки возможна погрешность ближе к середине
   между интервалами. Решение — `plotFraction` с теми же константами, что сетка ECharts.
5. **Axe и текст SVG.** Подписи осей внутри `aria-hidden`, цвет `--text-muted` в обеих темах. Если axe станет
   проверять контраст текста SVG, сработает тест «Глубокого анализа», где разрешено ноль нарушений.
6. **Нагрузка на перерисовку.** До 8 экземпляров ECharts по 6000 точек рисуются только при смене данных, периода,
   набора рядов или темы; курсор их не трогает.

## Вопросы владельцу

- **В1. Рост бандла.** Принять один чанк около 1 МБ (рекомендация: да, инструмент локальный) или грузить
  `TimeChart` отдельным чанком через `defineAsyncComponent` (CSP разрешает чанки своего origin)?
- **В2. Уведомление Apache-2.0.** ECharts распространяется в составе дистрибутива; Apache-2.0 требует сохранить
  `NOTICE`. Механизма уведомлений о сторонних UI-библиотеках в проекте нет (Vue MIT тоже без него). Нужна ли
  отдельная задача (например, `THIRD-PARTY-NOTICES` в `distZip`)? В срез 1 не входит.
- **В3. Ось времени.** Макет подписывает часы (15:00, 15:45), новая оболочка сейчас — смещение от начала прогона.
  План сохраняет смещение (поведение не меняется). Нужны ли часы на графиках в срезах 2–3?
- **В4. Спарклайн и оверлеи.** В срезе 1 их нет ни одного потребителя, поэтому они описаны в контракте, но не
  реализуются (политика минимальных изменений). Подтвердить перенос: спарклайн в срез 5 (плитки), оверлеи в срезы 2 и 4.
- **В5. Мёртвый код.** `trackPoints`, `trackPath`, `valueY` после среза не используются компонентами, но покрыты
  адаптерными спеками. Удалить отдельным PR после среза 4?

## Documentation impact

Документация меняется: `docs/user/slice-1-local-analysis.md` (строки 520–521 таблицы вкладок) и фрагменты
`changelog.d/ui-s1a-chart-overview.changed.md`, `changelog.d/ui-s1b-chart-deep.changed.md`. Решение о библиотеке
зафиксировано в ADR 0035 (новый ADR не нужен). Технической документации API и схем изменение не касается: API,
схемы и контракты не меняются. `docs/user/demo-script.md` описывает движение курсора ползунком и мышью; поведение
сохраняется, правка не нужна.

## Расхождения с описью и ADR 0035

- Опись, раздел 1: полоса «около 70 px»; в коде `<svg>` 80 px (`SharedCursorChart.vue:165`, `DeepCursorChart.vue:190`).
- ADR 0035, D2 включает в набор модулей «подсказку» (tooltip) и `dataZoom`. HTML-подсказка ECharts под CSP
  `style-src 'self'` теряет стили (Н4); в срезе 1 она не подключается, `dataZoom` не нужен. Опись и ADR упоминают
  только `script-src`.
- ADR 0035, таблица D1: общий курсор через `connect`. План его не использует: курсор ведёт ползунок Vue, иначе
  пропадает клавиатурное управление.
- ADR 0035, D3: «лимит точек на ряд задаёт автошаг (ADR 0014)». Номинально 1500, но на пути усечения нагрузка
  читается до 6000 интервалов (`deep.ts:31`, `DeepAnalysisPanel.vue:46`).
- ADR 0035, D5: контейнеры `SharedCursorChart.vue` и `DeepCursorChart.vue` в срезе 1 не заменяются, заменяется
  только рисунок ряда внутри них.
- Опись, раздел 4: «p50, p99, доля ошибок рядами по времени — проверить API». `/buckets` отдаёт только p95 и максимум
  (`types.ts:660-667`); p50 и p99 по времени есть только через разбор HDR на клиенте.
- Опись, раздел 4: полосы стадий из `load-stages.v1`. Это верно только для объявленных стадий (`stage_binding`);
  у прогона `capacity_step` ступени времени не несут, полос не будет.
- ADR 0035 пока не в `origin/main` (ветка `docs/adr-0035-ui-chart-library`), опись не отслеживается Git.

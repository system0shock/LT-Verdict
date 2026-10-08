# Новый анализ: один экран с готовностью и всеми текущими полями (срез U2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** вкладка «Новый анализ» новой оболочки (`?shell=new`) становится русским экраном запуска по макету: пять понятных разделов (нагрузочный тест, правила, данные о системе, планы анализа, ИИ-разбор) со всеми текущими полями и блок «Готовность к запуску», который показывает, что будет собрано и почему кнопка «Запустить анализ» недоступна. Запуск идёт тем же `analyze()` и тем же `createJob`, что и в прежней форме.

**Architecture:** три новых файла в `ui/src/shell/`: чистая функция `setup.ts` (`buildReadiness`: состояние формы на входе, условия запуска и список «что получится» на выходе), компонент `NewAnalysisPanel.vue` (русская раскладка с теми же свойствами, событиями и `id` полей, что у `RunSetup.vue`) и стили в `shell.css`. `App.vue` остаётся владельцем состояния и логики: одним выражением выбирает компонент (`shellNew ? NewAnalysisPanel : RunSetup`) и подставляет русские тексты проверок. `RunSetup.vue`, `api.ts`, `types.ts` не меняются.

**Tech Stack:** Vue 3.5, TypeScript 6, Playwright 1.62 с `@axe-core/playwright` (чистая функция тестируется тем же Playwright без браузера, как `overview-adapters.spec.ts`); новых зависимостей нет.

**Spec:** этот файл (раздел «Brainstorming: допущения и вопросы»). Основание: `docs/ui-mockup/implementation-plan.md` (фаза 3, срез U2), макет `docs/ui-mockup/lt-verdict-ui-mockup.html` (функция `rNew`) и `screens/04-new-analysis.jpg`, `feature-gap.md` (пункт 18), черновик Codex `docs/superpowers/plans/codex-drafts-2026-09-30/plan-u2-u3-new-analysis-tables.md` (сверен с кодом, см. ниже), планы U0 и U1 в `docs/superpowers/plans/`, решения владельца 2026-09-29.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: экран «Новый анализ» новой оболочки как в макете: блок готовности
  («что будет собрано и почему «Запустить» недоступна»), все ТЕКУЩИЕ поля в
  разделах на русском: вход JMeter/Gatling, политика, снимок ресурсов, планы
  корреляций/ёмкости/трендов, онлайн-источники с окном и шагом, контекст
  OpenSearch, PostgreSQL (профиль, снимки до/после, pg_profile), указатель на
  ИИ-разбор; загрузка из файла для каждого источника, где её даёт текущий API.
REQUIRED TO ACHIEVE IT:
  - новый компонент экрана с теми же props, emits и id полей, что у RunSetup
    (запуск идёт тем же analyze() и createJob, новых вызовов API нет);
  - чистая функция готовности: условия запуска только из существующих проверок
    (нет файла, занято, ошибка запроса источника, план ёмкости или трендов без
    снимка ресурсов), остальное только предупреждение;
  - русские тексты проверок формы в новой оболочке (иначе причина блокировки
    в блоке готовности была бы английской): подмена строк в App.vue без
    изменения логики; в прежнем интерфейсе строки прежние;
  - русские строки только в ui/src/shell/labels.ts;
  - e2e с подменённым API (реальные имена частей multipart), чистые тесты
    функции готовности, сверка на реальном сервере, axe в двух темах, ширины
    1280 и 375; правка двух существующих новых-оболочечных сценариев под
    русское имя кнопки;
  - docs/user/slice-1-local-analysis.md и CHANGELOG.md.
NOT REQUIRED (report-only): drag-and-drop, мастер, метки плеч и «Плечи
  OpenShift», пакеты метрик, бюджет рядов и лимиты 1 024 ряда (ADR 0014
  Proposed), таблица и конструктор проверок трендов, раздел «Сравнение» и
  выбор baseline в форме (baseline живёт на вкладке «Сравнение»), секунды
  вместо миллисекунд в окне источника, согласие на ИИ при запуске (согласие
  живёт в AdvicePanel после анализа), новые клиентские проверки (план
  корреляций без снимка, лимит ячеек окна), перевод PolicyEditor и
  JenkinsPanel, правки Kotlin, api.ts, types.ts, схем, package.json,
  RunSetup.vue, styles.css, состояние вкладки в URL, переработка других
  вкладок.
EXPECTED FILES TO CHANGE:
  create ui/src/shell/setup.ts
  create ui/src/shell/NewAnalysisPanel.vue
  modify ui/src/shell/labels.ts       (SETUP_LABELS, SETUP_MESSAGES; пишет оркестратор)
  modify ui/src/shell/shell.css       (раскладка экрана и блока готовности)
  modify ui/src/App.vue               (выбор компонента, setupMsg; логика не меняется)
  create ui/e2e/new-analysis-readiness.spec.ts
  create ui/e2e/new-analysis.spec.ts
  create ui/e2e/new-analysis-live.spec.ts
  modify ui/e2e/overview-live.spec.ts (кнопка нового экрана)
  modify ui/e2e/upload-cancel.spec.ts (кнопка нового экрана в сценарии new shell)
  modify docs/user/slice-1-local-analysis.md
  modify CHANGELOG.md
  create docs/superpowers/plans/archive/2026-10-02-ui-new-analysis-u2.md
```

Оценка размера: `NewAnalysisPanel.vue` около 430 строк (раскладка и 22 свойства и события, как у `RunSetup.vue`), `setup.ts` около 110, `shell.css` около 100, `App.vue` около +40/-20 строк, тесты около 600, `labels.ts` 131 (готово). Если для подключения понадобятся правки логики `App.vue` сверх выбора компонента и подмены строк, а также правки `RunSetup.vue`, `api.ts` или `types.ts`, остановиться и объяснить (AGENTS.md, п. 10). ADR не нужен: контракты, схемы, API и зависимости не меняются; экран живёт за временным флагом оболочки.

## Brainstorming: допущения и вопросы

Классификация: среднего размера срез на существующем потоке (форма, `analyze()`, `createJob` уже работают), без контрактов; новый компонент, но не новая подсистема. Интерактивного диалога нет: решения владельца из задания приняты как исходные, остальное записано допущениями, спорное вынесено в вопросы.

Сверка черновика Codex с кодом (`rg` по `ui/src`, `src/main/kotlin`):

- Находка 1 (нет предзапусковой проверки плана корреляций без снимка): подтверждена. `App.vue` `analyze()` блокирует только план ёмкости и план трендов; `LocalApi.kt` отвечает `DIAGNOSTIC_RESOURCE_REQUIRED` после загрузки входного файла. Новой проверки U2 не вводит (задание): экран показывает предупреждение, не блокировку; находка записана ниже.
- Находка 2 (нет блока готовности): это и есть U2.
- Находки 3-4 (U3, таблица правил и p50/p95) вне U2.
- Граница контракта подтверждена: `source_request` взаимоисключается с ручными снимком, планами и контекстом OpenSearch (`LocalApi.kt`, «Online acquisition cannot be combined with manual source inputs»), а PostgreSQL pre/post/HTML независимы. Экран показывает это явно; «каждый источник онлайн или файлом» полностью возможно только после расширения контракта (отдельный ADR).
- Номера строк черновика не использовались: сверка велась по поиску.

Ответ на вопрос про одиннадцать позиционных аргументов `createJob`: нового вызова нет. Экран только выдаёт событие `analyze` в существующий `analyze()` из `App.vue`, поэтому обёртка с объектом параметров не нужна и `api.ts` не меняется. Если когда-нибудь появится второй вызывающий, объект параметров стоит вводить там, не раньше.

Допущения:

1. Один экземпляр формы. В новой оболочке монтируется только `NewAnalysisPanel`, в старой только `RunSetup`; общие `id` (`input-file`, `policy-file` и другие) и `data-testid` сохранены, поэтому `cancelUpload()` (фокус на `#input-file`), переход «Открыть политику» из «Обзора» (`targetId: 'policy-file'`) и `shell.spec.ts` (`#run-setup`) работают без правок. Экран, как `RunSetup`, прячется через `v-show`, чтобы выбранные файлы не терялись при смене вкладки.
2. Состав разделов: 1. Нагрузочный тест; 2. Правила (файл политики, ошибки, черновик `PolicyEditor`); 3. Данные о системе (снимок ресурсов, онлайн-источники, окно и шаг, контекст OpenSearch, PostgreSQL); 4. Планы анализа (корреляций, ёмкости, трендов); 5. ИИ-разбор (только пояснение). Раздел «Сравнение» макета не переносится: baseline выбирается на вкладке «Сравнение».
3. Условия запуска (блокируют кнопку) только те, что уже есть в коде: нет файла нагрузки; идёт загрузка, анализ или снимок PostgreSQL (`busy`); `sourceRequestError`; план ёмкости или трендов без снимка ресурсов (проверка перенесена из `analyze()` на момент выбора, сама проверка осталась в `analyze()`); черновик политики с ошибками (сейчас `analyze()` в этом случае молча не стартует, экран делает причину видимой). Всё остальное только предупреждение или справка.
4. Файл политики, который сервер не принял, не блокирует запуск (как сейчас: политика сбрасывается, анализ идёт без правил); блок готовности говорит об этом прямо: предупреждение «будет NO_POLICY».
5. Взаимоисключение режимов. Выбор любого онлайн-источника (кроме PostgreSQL) очищает и блокирует снимок ресурсов, три плана и контекст OpenSearch (существующее поведение `selectSourceProfiles`); экран объясняет это в тексте раздела и в блоке готовности. План корреляций без снимка: предупреждение с кодом `DIAGNOSTIC_RESOURCE_REQUIRED`, кнопка доступна (проверки в коде нет).
6. «ИИ-разбор по согласию»: согласие сейчас только в `AdvicePanel` после анализа. Добавлять согласие при запуске значило бы новое поведение, поэтому раздел 5 только поясняет, где и как запрашивается ИИ-разбор (вопрос владельцу 1).
7. Русские тексты проверок формы: `SETUP_MESSAGES` в `labels.ts`; `App.vue` выбирает `setupMsg = shellNew ? SETUP_MESSAGES : legacyMessages`, где `legacyMessages` повторяет прежние английские строки дословно. Текст ошибок сервера (`ApiError.message`, сообщения `json_pointer: message` политики) остаётся английским и в разметке помечен `lang="en"`; `PolicyEditor` остаётся английским (его переводит срез U4), блок обёрнут в `lang="en"` с пояснением.
8. Единицы окна источника остаются миллисекундами (как в проверках), не секундами макета: смена единиц меняет предпроверку.
9. Подписи состояний в блоке готовности текстовые («Готово», «Не задано», «Внимание», «Нужно»), цвет не единственный признак. Блок на широком экране закреплён справа (`position: sticky`), на узком идёт после формы; причина блокировки и в тексте `#readiness-status`, связанном с кнопкой через `aria-describedby`.
10. Стили: `shell.css` и существующие токены оболочки; `styles.css` не меняется. Новые файлы Codex пишет чисто ASCII, русские строки берёт из `SETUP_LABELS` по ключам.
11. `JenkinsPanel` под формой остаётся как есть (английский, вне объёма по решению владельца).

Вопросы владельцу (не блокируют):

1. Нужно ли согласие на ИИ-разбор при запуске (галочка «после анализа сразу запросить совет»)? Сейчас нет: согласие только на вкладке «ИИ-разбор».
2. Добавить ли клиентскую проверку «план корреляций требует снимка ресурсов» (как для ёмкости и трендов)? Сейчас нет: только предупреждение. Одно условие в `analyze()` и в `buildReadiness`.
3. Добавить ли клиентскую проверку числа ячеек явного окна (1..100000, на сервере `OpenSearchSource` и `SourceConfig`)? Сейчас нет, пользователь узнаёт об ошибке от сервера.
4. Показывать ли поля окна в секундах, как в макете (сейчас миллисекунды, как в проверках)?

## Global Constraints

- Публичные контракты не меняются: `git diff --stat origin/main -- src docs/contracts ui/src/types.ts ui/src/api.ts ui/src/RunSetup.vue ui/src/styles.css ui/package.json ui/package-lock.json` пуст.
- Старый интерфейс не меняется: без `?shell=new` DOM и тексты прежние, все существующие e2e зелёные без правок (кроме двух новых-оболочечных сценариев из списка файлов); английские тексты проверок побайтно прежние.
- Один путь запуска: экран не вызывает `createJob` и `uploadInput`, только событие `analyze`; тест сравнивает имена частей `POST /api/jobs` старой и новой формы.
- Браузерное хранилище (`localStorage`, `sessionStorage`, cookies) и `v-html` запрещены.
- Русские строки U2 только в `ui/src/shell/labels.ts`; файлы Codex чисто ASCII (`rg -n "[^\x00-\x7F]"` пуст по `setup.ts`, `NewAnalysisPanel.vue`, `shell.css`).
- Кнопка запуска не ниже 44 px; фокус виден; у каждого поля доступное имя с русской подписью; `aria-live` только у `#readiness-status`.
- Без горизонтального скролла страницы на 1280 и 375 px; axe: нет нарушений critical и serious в обеих темах.
- Проза документов на русском, `.md` проходят `markdownlint-cli2@0.23.2`.
- Коммиты атомарные, Conventional Commits, явные пути, без `git add .` и `-A`; push, PR, merge, force запрещены.
- В PowerShell перед e2e: `Remove-Item Env:NoDefaultCurrentDirectoryInExePath -ErrorAction SilentlyContinue`; тяжёлые прогоны под мьютексом `Global\ltv-heavy`.

## Review Focus

- Ложные блокировки: кнопка блокируется только перечисленными в допущении 3 условиями; план корреляций без снимка и отклонённый файл политики только предупреждают (`new-analysis-readiness.spec.ts`).
- Расхождение с прежней формой: набор и порядок частей `POST /api/jobs` и тело `source_request` совпадают (`new-analysis.spec.ts`); результат совпадает на реальном сервере (`new-analysis-live.spec.ts`).
- Взаимоисключение режимов: онлайн-профиль блокирует снимок, три плана и контекст; PostgreSQL независим.
- Доступность: у каждого поля имя с русской подписью, причина блокировки доступна без цвета и связана с кнопкой, блок готовности не перехватывает фокус, английские вставки (`PolicyEditor`, ошибки сервера) помечены `lang="en"`.
- Идентификаторы: `#run-setup`, `#input-file`, `#policy-file` и остальные `id` и `data-testid` совпадают с `RunSetup.vue`.
- Кодировка файлов Codex (не-ASCII отсутствует), окончания строк, подмена строк в `App.vue` не меняет логику (дифф `App.vue` без изменения условий).
- Старый интерфейс: в нём нет `start-analysis` и `readiness`, форма прежняя.

---

## Task 1: Строки и тесты (RED)

**Files:**

- Modify: `ui/src/shell/labels.ts` (оркестратор, UTF-8 без BOM, готово), `ui/e2e/overview-live.spec.ts`, `ui/e2e/upload-cancel.spec.ts`
- Create: `ui/e2e/new-analysis-readiness.spec.ts`, `ui/e2e/new-analysis.spec.ts`, `ui/e2e/new-analysis-live.spec.ts`

**Interfaces:**

- Produces (`labels.ts`, готово): `SETUP_LABELS` (заголовки разделов `inputTitle`, `rulesTitle`, `systemTitle`, `plansTitle`, `aiTitle`; подписи полей; состояния `level*`; детали готовности `inputMissing`, `busy`, `policy*`, `resources*`, `plansNoneItem`, `planNames`, `sources*`, `postgres*`; `will*`; `readinessTitle`, `willTitle`, `startButton`, `startBlocked`, `startReady`) и `SETUP_MESSAGES` (сообщения проверок).
- Consumes: `pluralRu`.

- [x] **Step 1: Написать падающие тесты** (оркестратор). Ключевые фрагменты `new-analysis-readiness.spec.ts`:

```ts
test('capacity and trend plans need a file snapshot, as the existing preflight requires', () => {
  const both = build({ plans: { diagnostic: false, capacity: true, trend: true } })
  expect(both.canStart).toBe(false)
  expect(both.blockers).toEqual(['resources'])
  expect(item(both, 'resources').detail).toBe(SETUP_LABELS.resourcesRequired(`${SETUP_LABELS.planNames.capacity}, ${SETUP_LABELS.planNames.trend}`))
})

test('a correlation plan without a snapshot only warns: the page has no such check today', () => {
  const risky = build({ plans: { diagnostic: true, capacity: false, trend: false } })
  expect(risky.canStart).toBe(true)
  expect(item(risky, 'resources')).toMatchObject({ level: 'warn', detail: SETUP_LABELS.resourcesDiagnosticRisk })
})
```

Ключевые фрагменты `new-analysis.spec.ts` (подмена API возвращает `/api/sources` с тремя профилями и принимает `POST /api/jobs`; неизвестный путь бросает ошибку):

```ts
test('sends the same job parts as the old form for a full file selection', async ({ page }) => {
  const sent: Record<string, string[]> = {}
  for (const [name, path, label] of [['old', '/', 'Analyze run'], ['new', '/?shell=new', SETUP_LABELS.startButton]] as const) {
    const calls = await openSetup(page, path)
    await fillEverythingFromFiles(page)
    await page.getByRole('button', { name: label, exact: true }).click()
    await expect.poll(() => calls.jobs.length).toBe(1)
    sent[name] = partNames(calls.jobs[0])
    await page.unroute('**/api/**')
  }
  expect(sent.new).toEqual(sent.old)
})

test('online profiles lock the file inputs and send the same source request as the old form', async ({ page }) => {
  // ... выбрать два профиля: снимок, три плана и контекст заблокированы;
  // тело source_request: {"schema_version":"source-request.v3","profile_ids":["app-logs","prod-prometheus"],"window":{"origin":"auto",...}}
})
```

Полные файлы: 10 тестов функции готовности; 16 UI-прогонов (разделы и заголовки, все поля с русской подписью и прежним `id`, готовность следует форме, план ёмкости и трендов без снимка блокируют до запроса, план корреляций только предупреждает, черновик политики с ошибкой блокирует, отклонённый файл политики нет, совпадение частей job, совпадение `source_request`, русские и английские ошибки окна, кнопки снимков PostgreSQL, ошибки политики без живой области и с правильным языком, раздел ИИ без управляющих элементов, старый интерфейс без нового экрана, axe в двух темах, ширины 1280 и 375); 2 живых теста (результат нового экрана равен результату старой формы на реальном сервере, запуск только с файлом нагрузки даёт `NO_POLICY`).

- [x] **Step 2: Убедиться, что RED.** Run: `npx playwright test -c tmp-new-analysis.config.ts new-analysis-readiness` (временный конфиг с Vite, не коммитится). Expected: FAIL, `Cannot find module ... src/shell/setup`; UI-тесты падают на `start-analysis`.

- [ ] **Step 3: Commit** вместе с реализацией (Task 2), чтобы история не содержала красных коммитов.

## Task 2: Готовность, экран, подключение

**Files:**

- Create: `ui/src/shell/setup.ts`, `ui/src/shell/NewAnalysisPanel.vue` (исполнитель Codex, только ASCII)
- Modify: `ui/src/shell/shell.css` (Codex), `ui/src/App.vue` (оркестратор, из-за не-ASCII символов в файле)
- Test: `ui/e2e/new-analysis-readiness.spec.ts`, `ui/e2e/new-analysis.spec.ts`

**Interfaces:**

- Produces (`setup.ts`):

```ts
export type ReadinessKey = 'input' | 'busy' | 'policy' | 'resources' | 'plans' | 'sources' | 'postgres'
export type ReadinessLevel = 'ok' | 'info' | 'warn' | 'block'
export interface ReadinessInput {
  busy: boolean
  inputName: string | null
  policyId: string | null          // null: черновика политики нет
  policyHasErrors: boolean
  resourceName: string | null
  plans: { diagnostic: boolean; capacity: boolean; trend: boolean }
  onlineProfileCount: number
  sourceRequestError: string
  contextCount: number
  postgres: { pre: boolean; post: boolean; html: boolean }
}
export interface ReadinessItem { key: ReadinessKey; level: ReadinessLevel; title: string; detail: string }
export interface Readiness { items: ReadinessItem[]; blockers: ReadinessKey[]; canStart: boolean; will: string[] }
export function buildReadiness(input: ReadinessInput): Readiness
```

  Порядок элементов: `input`, затем `busy` (только когда занято), `policy`, `resources`, `plans`, `sources`, `postgres`. Уровень `block` у `input` без файла, `busy`, `policy` при черновике с ошибками, `resources` при плане ёмкости или трендов без снимка, `sources` при `sourceRequestError`. `canStart = blockers.length === 0`.

- Produces (`NewAnalysisPanel.vue`): те же props и emits, что у `RunSetup.vue` (имена и типы без изменений); корень `<section id="run-setup" class="panel run-setup new-analysis" lang="ru" aria-labelledby="run-setup-title">`; разделы `<section class="new-analysis__section" aria-labelledby>` с `h3`; блок готовности `<aside aria-labelledby="readiness-title" data-testid="readiness">` со списком `li[data-testid="readiness-item"][data-key][data-level]`, списком `data-testid="readiness-will"`, текстом `#readiness-status` и кнопкой `data-testid="start-analysis"` (`aria-describedby="readiness-status"`, `disabled` при `!canStart`, событие `analyze`). `id` и `data-testid` полей те же, что у `RunSetup.vue`; поля окна источника и `source-request-error` с теми же `id` и `data-testid`.
- Produces (`App.vue`): `setupMsg`, `<component :is="shellNew ? NewAnalysisPanel : RunSetup" ...>` с прежним блоком привязок.
- Consumes: `SETUP_LABELS`, `SETUP_MESSAGES`, `PolicyEditor`, `Policy`, `PolicyError`, `SourceProfile`.

- [ ] **Step 1: Задание Codex.** Файл `codex-tasks/u2-new-analysis.md` (только ASCII, ключи `SETUP_LABELS`, точные сигнатуры, запреты: не править тесты, `labels.ts` и `App.vue`, не коммитить, не запускать npm; проверка `rg -n "[^\x00-\x7F]"` и `git diff --check`).

- [ ] **Step 2: Реализация** (Codex `setup.ts`, панель, стили; оркестратор `App.vue`). Ключевые части:

```ts
// setup.ts: блокировка только существующими условиями
const blockers: ReadinessKey[] = []
if (!input.inputName) blockers.push('input')
if (input.busy) blockers.push('busy')
if (input.policyId !== null && input.policyHasErrors) blockers.push('policy')
if (!input.onlineProfileCount && !input.resourceName && (input.plans.capacity || input.plans.trend)) blockers.push('resources')
if (input.sourceRequestError) blockers.push('sources')
```

```ts
// App.vue: подмена строк без изменения логики
const setupMsg = shellNew ? SETUP_MESSAGES : legacyMessages
if (sourceContextFiles.value.length > 16) return { request: null, error: setupMsg.contextTooMany }
```

```vue
<!-- App.vue: один компонент формы вместо двух экземпляров -->
<component :is="shellNew ? NewAnalysisPanel : RunSetup" v-show="shownIn('setup')" :input-file="inputFile" ... @analyze="analyze" />
```

- [ ] **Step 3: Проверка без e2e** (оркестратор). Run: `npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run test:contracts; npm --prefix ui run build`. Expected: без ошибок.

- [ ] **Step 4: GREEN.** Run: `npx playwright test -c tmp-new-analysis.config.ts`. Expected: все тесты `new-analysis-readiness`, `new-analysis`, `shell`, `upload-cancel`, `verdict-summary`, `verdict-first`, `overview` зелёные.

- [ ] **Step 5: Проверка diff** (оркестратор): только EXPECTED FILES; нет не-ASCII в файлах Codex; дифф `App.vue` порядка десятков строк, без изменения условий; окончания строк неизменны; `git diff --check`.

- [ ] **Step 6: Commit.** `git add ui/src/shell ui/src/App.vue ui/e2e/new-analysis-readiness.spec.ts ui/e2e/new-analysis.spec.ts ui/e2e/new-analysis-live.spec.ts ui/e2e/overview-live.spec.ts ui/e2e/upload-cancel.spec.ts docs/superpowers/plans/archive/2026-10-02-ui-new-analysis-u2.md`; сообщение `feat(ui): add the new analysis screen with a launch readiness block`.

## Task 3: Полный набор, сверка на реальном сервере, снимки

- [ ] `npm --prefix ui run e2e` (Gradle-сервер) под мьютексом `Global\ltv-heavy`, приоритет ниже обычного: все существующие спеки зелёные, `new-analysis-live.spec.ts` подтверждает совпадение результата с прежней формой.
- [ ] Снимки вкладки «Новый анализ» (светлая и тёмная темы, 1280 и 375, с выбранными файлом, политикой и онлайн-источником) в `scratchpad/u2-screens/`; описание расхождений с `screens/04-new-analysis.jpg` в отчёте.

## Task 4: Документация

**Files:**

- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

- [ ] **Step 1:** в разделе про новый интерфейс описать экран «Новый анализ»: разделы и поля, блок готовности (что блокирует кнопку, что только предупреждает), взаимоисключение онлайн-источников и файлов, что остаётся английским.
- [ ] **Step 2:** `CHANGELOG.md`, `### Added`: запись про экран «Новый анализ».
- [ ] **Step 3:** `npx markdownlint-cli2@0.23.2 docs/user/slice-1-local-analysis.md CHANGELOG.md docs/superpowers/plans/archive/2026-10-02-ui-new-analysis-u2.md`; `git diff --check origin/main...HEAD`.
- [ ] **Step 4: Commit** `docs: describe the new analysis screen of the new shell`.

## Task 5: Независимое ревью

- [ ] Ревью Codex read-only (`gpt-5.6-terra`, `high`) по `git diff origin/main...HEAD`: доступность, регрессии старой формы, соответствие плану, кодировка, ложные блокировки. Формат BLOCKER/MAJOR/MINOR, APPROVE/CHANGES_REQUESTED.
- [ ] Замечания проверяются фактами; принятые исправляются новыми коммитами.
- [ ] Перед финалом: `git status --short`, `git diff --stat origin/main...HEAD`, временный `ui/tmp-new-analysis.config.ts` удалён и не в индексе.

## Находки вне объёма (report-only)

1. Нет клиентской проверки «план корреляций без снимка ресурсов»: сервер отвечает `DIAGNOSTIC_RESOURCE_REQUIRED` уже после загрузки входного файла. Экран показывает предупреждение. Закрыто в U2.1 ниже.
2. Нет клиентской проверки числа ячеек окна источника (1..100000, проверяется на сервере: `SourceConfig.kt`, `OpenSearchSource.kt`; для авто-окна `AUTO_WINDOW_SPAN_UNSUPPORTED`). Для явного окна закрыто в U2.1 ниже; авто-окно на клиенте не проверить.
3. Отклонённый файл политики молча даёт запуск без правил; блок готовности теперь предупреждает, но поведение `analyze()` не менялось.
4. «Каждый источник онлайн или файлом»: сервер не принимает смесь, это отдельный контракт и ADR.
5. `PolicyEditor`, `JenkinsPanel` и тексты ошибок сервера остаются английскими.

## Self-Review

- Spec coverage: блок готовности и причина блокировки (допущения 3-5, 9), все текущие поля (допущение 2, интерфейс панели), загрузка из файла для каждого источника (раздел 3 и 4, взаимоисключение), ИИ по согласию (допущение 6, вопрос 1), русские тексты (допущение 7), без новых проверок (находки 1-2).
- Placeholder scan: сигнатуры, ключи подписей и `data-testid` заданы в файле задания, `labels.ts` и тестах.
- Type consistency: `ReadinessInput.policyId` строка или `null` (пустая строка означает черновик без идентификатора и блокирует при ошибках); `plans` берутся из наличия файлов; `onlineProfileCount = sourceProfileIds.length`.

## U2.1: решения владельца 2026-10-04

Владелец ответил на вопросы 1-4 раздела «Brainstorming»: согласие на ИИ-разбор
нужно при запуске (подтверждено, что безопасность обеспечена), клиентскую
проверку плана корреляций и числа ячеек добавить, окно источника показывать
в секундах (в API остаются миллисекунды). Допущения 6 и 8 и блок «NOT REQUIRED»
выше заменены этим разделом.

```text
REQUESTED: четыре решения владельца (см. выше).
REQUIRED TO ACHIEVE IT:
  - согласие: галка в разделе «ИИ-разбор» и автозапуск существующего вызова
    совета после завершения анализа; публичный контракт не меняется;
  - блокировка плана корреляций без снимка (readiness и analyze());
  - проверка числа ячеек явного окна в sourceRequestState;
  - секунды в полях окна нового экрана, перевод в панели.
NOT REQUIRED: серверное хранение согласия, Kotlin, api.ts, types.ts,
  RunSetup.vue, проверка авто-окна на клиенте.
EXPECTED FILES: ui/src/shell/{setup.ts,NewAnalysisPanel.vue,labels.ts},
  ui/src/App.vue, ui/src/AdvicePanel.vue, ui/e2e/new-analysis*.spec.ts,
  docs/user/slice-1-local-analysis.md, CHANGELOG.md, этот план.
```

### Решение по согласию на ИИ-разбор (пункт 1)

Контракт менять не нужно. Сервер принимает согласие в самом запросе
`POST /api/runs/{run}/analyses/{analysis}/advice` с телом
`{"confirm_external_transfer":true}`; нигде не хранится, поле запроса анализа не
нужно: совет запрашивается отдельным вызовом после готового анализа.

Реализация (только клиент):

- `NewAnalysisPanel.vue`: галка `#ai-consent` (подпись дословно как у флажка
  `AdvicePanel`, с добавлением «сразу после его завершения»); `ReadinessInput.aiConsent` только
  добавляет строку в «Что получится», не блокирует запуск.
- `App.vue`: `aiConsent` запоминается для ревизии запуска сразу после успешного
  `createJob` и сбрасывается (согласие расходуется на один запуск); при
  завершении анализа идентификатор анализа кладётся в `adviceAutoFor` до
  публикации результата. Идентификатор очищается при новом запуске, выборе
  прогона или анализа.
- `AdvicePanel.vue`: необязательное свойство `autoStart`; после загрузки совета
  в наблюдателе выбора, если `autoStart` и ни совета, ни задачи нет, панель
  ставит свой флажок согласия и вызывает свой же `start()`. Статус, опрос,
  отмена и ошибки показывает прежняя панель; запрос идёт из неё, поэтому гонки
  между первичной загрузкой и запуском нет. Прежний путь через флажок и кнопку
  не менялся.
- Согласие не переживает перезагрузку страницы (браузерное хранилище запрещено),
  `restoreActiveJob` совет не запрашивает.

Для серверного варианта (согласие хранится в запросе анализа, автозапуск совета
сервером после завершения) потребовались бы: новое поле запроса задачи анализа
и его схема, хранение согласия вместе с задачей, запуск `AdvisoryAiJobs` из
`AnalysisJobs` и политика отказов; это изменение публичного контракта,
нужен ADR. Для выбранного варианта не требуется.

Текст согласия в продукте и в ADR 0021 называет ModelStudio (Singapore), хотя
владелец называет инференс он-прем; пока раннер один (`ModelStudioAdvisoryRunner`),
текст оставлен правдивым и совпадающим с `AdvicePanel`. При появлении он-прем
раннера текст нужно менять в обоих местах.

### План корреляций без снимка (пункт 2)

Сервер (`LocalApi.kt`, `AnalysisService.kt`, `CommandLine.kt`) отклоняет план
корреляций, если снимка ресурсов нет, по самому факту плана без снимка, как и
планы ёмкости и трендов (`DIAGNOSTIC_RESOURCE_REQUIRED`); проверки привязки и
хеша с клиента не повторить. Клиентская проверка эквивалентна: план без файла
снимка и без онлайн-профиля блокирует кнопку (`buildReadiness`) и
`analyze()` (`diagnosticNeedsSnapshot`). Проверка `analyze()` общая с прежней формой
(английский текст добавлен в прежний набор сообщений): сервер такие запуски
отклонял и раньше.

### Число ячеек окна (пункт 3)

Сервер (`SourceConfig.parseExplicitWindow`, `OpenSearchSource.validateRequest`):
`(end - start) / step` должно лежать в 1..100000 после проверки делимости,
код `SOURCE_REQUEST_INVALID`. Клиент в `sourceRequestState` после проверки
делимости отклоняет `(end - start) / step > 100000` (нижняя граница 1 следует
из `end > start`). Проверка общая с прежней формой. Авто-окно (ячейки считаются
по периоду файла, `AUTO_WINDOW_SPAN_UNSUPPORTED`) и верхняя граница времени
`MAX_TIMESTAMP_EPOCH_MILLIS` на клиенте не проверяются.

### Секунды в окне (пункт 4)

Состояние `App.vue`, `sourceRequestState`, `createJob`, `RunSetup.vue` остаются
в миллисекундах; панель переводит значения на границе (`msToSeconds`,
`secondsToMs` в `setup.ts`, пустая строка остаётся пустой, округление до
миллисекунды убирает артефакты `1.001 * 1000`). Подписи и сообщения проверок в
`labels.ts` в секундах; тело `source_request` совпадает с прежней формой
(тест подставляет 1/2/60 в новой и 1000/2000/60000 в прежней).

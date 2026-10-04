# Глубокий анализ: API рядов (D0), экран (D2), стадии (D3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** вкладка «Глубокий анализ» новой оболочки показывает график нагрузки и выбранные ресурсные ряды на общей шкале времени с общим курсором; ряды читаются из нового JSON-API по сохранённому анализу вместо скачивания файла снимка; стадии прогона (именованные окна снимка) видны полосой и открывают окно по клику. Числа на экране совпадают с независимым Python-оракулом.

**Architecture:** D0 добавляет два read-only эндпойнта (каталог рядов и значения с укрупнением шага) по контракту ADR 0014 часть 6. Чистая логика (редукторы, сетка укрупнения, страница) живёт в одном новом файле ядра `core/ResourceSeriesView.kt`; маршруты остаются в `LocalApi.kt` (там приватные помощники запросов); разобранный снимок кэшируется в памяти в одном экземпляре под мьютексом (одна расшифровка за раз). D2 и D3 только на стороне UI: чистая модель `deep.ts`, панель `DeepAnalysisPanel.vue` и график с курсором во времени (мс), а не по индексу ряда; существующие `SharedCursorChart.vue` и `overview.ts` не меняются (регрессионная опора обзора), `overview.ts` только читается: `loadSeries`, `formatNumber`, `formatOffset`.

**Tech Stack:** Kotlin 2 (JVM 21), Ktor 3.5 (Netty), kotlinx.serialization, JUnit 5; Vue 3.5, TypeScript 6, Playwright 1.62 с `@axe-core/playwright`; Python 3.14 (`unittest`, `decimal`) для оракула; JSON Schema + Ajv. Новых зависимостей нет.

**Spec:** `docs/adr/0014-resource-series-limits-autostep-arm-api.md` (часть 6 «Ответ API с рядами», раздел «Значения лимитов», «Следствия» п. 3 и 4), `docs/adr/0018-policy-platform-rules-small-samples.md` (раздел 4 «Окно правила и стадии»), `docs/adr/0009-explicit-capacity-stages.md`; фаза 4 плана внедрения интерфейса (срезы D0, D2, D3; файл `docs/ui-mockup/implementation-plan.md` в основном чекауте, в git не входит); экран `rDeep` макета (`docs/ui-mockup/lt-verdict-ui-mockup.html`, там же, в git не входит); план автошага `docs/superpowers/plans/2026-10-02-autostep-adr-0014.md`; план ADR 0018 `docs/superpowers/plans/2026-10-02-adr-0018-implementation.md` (срез S5 `window_ids`). Решения владельца 2026-09-29 и 2026-10-04 (память проекта).

## Что известно заранее (проверено по коду `origin/main` 135ef5a)

1. **ADR-B существует.** Это ADR 0018 (Accepted). Его раздел 4 уже решает: стадия есть именованное окно `snapshot.windows`, правилу добавляется необязательный `window_ids`, автоопределения стадий нет. Реализация этого в ядре запланирована как срез S5 плана ADR 0018 (`feat/policy-window-ids`, после S1). **D3 зависит от S5 и не перепланирует его.** Вердикт по ADR для D3: полоса стадий и показ окон в UI ADR не требуют; объявление именованных стадий при онлайн-сборе требует нового ADR (публичный контракт, меняет хэш снимка), потому что сейчас `PromqlSource` и `SourceAnalysis` пишут в снимок только окно `full` (или не пишут окон при авто-окне; `PromqlSource.kt:376-391`, `SourceAnalysis.kt:524-537`). Рекомендация: отложить за МВП (вопрос 6).
2. **Контракт D0 уже задан ADR 0014 частью 6.** План его реализует, а не проектирует заново. Расхождение внутри ADR: часть 6 называет редуктор `max` для `interval_max` и среднее для остальных, «Следствия» п. 3 называют `max`, `min`, среднее. План берёт `min` для `interval_min` (вопрос 3 закрыт автошагом, решение (e) 2026-09-30).
3. **`interval_max` и `interval_min` в `origin/main` ещё нет:** `ResourceAggregation` содержит два значения (`ResourceSnapshot.kt:92-97`), срез S1 автошага не влит. Отображение «агрегация в редуктор» пишется исчерпывающим `when` без `else`: тот из срезов (D0a или S1 автошага), что вливается вторым, обязан добавить ветки, компилятор это заставит. Тесты `max`/`min` на реальных снимках живут в отдельном срезе D0c после S1.
4. **Часовые базы разные.** Ряды ресурсов и окна идут в эпохе (мс), а `bucket_start_ms` в `/buckets` относителен началу прогона (`Metrics.kt:213-217`: `startedAt - runStart`). Якорь для общей оси: `resource_binding.run_from_epoch_ms` результата (тот же `runStart`, `AnalysisService.kt:277-286`). Core выставляет `clock_alignment: not_verified_by_core`: сдвиг часов генератора и кластера UI не исправляет, а предупреждает.
5. **Идентичность не меняется:** D0, D2, D3a, D3b не трогают `fixtures/slice1/identity/*` и `fixtures/slice1/manifest.json` (читают сохранённый анализ). Единственный кандидат в общую очередь identity-срезов: D3c (объявление стадий при онлайн-сборе меняет хэш снимка и `source_summary`), он вне МВП.

## Global Constraints

Каждая задача неявно включает этот раздел. Числа скопированы из ADR 0014 и кода.

- Пределы снимка (приняты): `S_max` = 1 024 ряда, `C_max` = 1 500 000 ячеек, `B_max` = 32 MiB (`ResourceSnapshot.kt:651-654`). Шаг снимка: целые секунды 1-60. Окон до 64, правил до 256. Значения: до 32 значащих цифр, до 12 знаков дроби, по модулю до 10^18. Идентификатор ряда до 128 байт.
- Предварительные (Proposed, до D0b): страница значений до 100 000 ячеек на ответ, каталог до 256 рядов на страницу; `E_max` = 20 480 (D0b его только замеряет, значение не меняет).
- Куча JVM: минимум 512 МБ на анализ (`JAVA_OPTS`); замер удержания 200-250 байт на ячейку при разборе снимка. Поэтому: не более одного разобранного снимка в кэше, расшифровка одна за раз.
- `/buckets` (существующий, не меняется): `rollup` только 1, 10, 30, 60; `limit` до 500; p95 считается на сервере из HDR, его нельзя усреднять; `bucket_start_ms` относителен началу прогона.
- Числа в ответе D0 это IEEE 754 double, не авторитетны (`numeric_encoding: "ieee754-double"`); авторитетны десятичные значения снимка и `resource_summary`.
- Каждое число на экране D2 берётся из ответа D0, из `/buckets` или из существующих evidence результата (`resource_summary`, `resource_policy_check`, `window_policy_summary`, `resource_binding`). Новых статистик (p50/p95 выбранного периода, скользящие средние) в МВП нет: для них нужен метод и оракул.
- Русские строки интерфейса только в `ui/src/shell/labels.ts`; остальной код оболочки ASCII. Токены стилей (`var(--brand)` и др.) без новых цветов.
- Новых зависимостей, фреймворков графиков и сторонних библиотек нет; графики на собственном SVG, как в `SharedCursorChart.vue`.
- Контракт ответа версионируется (`resource-series.v1`), схема и примеры лежат в `docs/contracts/resources/v1/`; `analysis-result.v1` и `resource-snapshot.v1` не меняются.
- Фикстуры оракула лежат в новом каталоге `fixtures/resource-series/`, не в `fixtures/slice1/` (`FixtureManifestTest` сверяет точный набор артефактов `slice1`).
- Коммиты: атомарные Conventional Commits, в конце `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; без push, merge и тегов без разрешения владельца; в грязном дереве только явный `git add <файлы>`. Ветка и PR на срез.
- Каждый срез объявляет `REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES` перед кодом (`AGENTS.md`). Если срез становится заметно больше оценки, остановиться и объяснить.
- Документация в том же PR: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md` (запись в `[Unreleased]`, `Added`), для контрактов схема и примеры.

## Review Focus

Входы и условия, которые спецификация подразумевает, но базовые тесты не покрывают. Каждая строка получает тест в задаче-владельце (указана в скобках).

1. Повторяемый параметр `series_id` с идентификаторами, содержащими `/`, `%`, пробел, не-ASCII (квалифицированные идентификаторы онлайн-снимков): `requireOnlyQueries` отвергает любой повторный параметр, поэтому нужен собственный разбор; дубликат, 33-й идентификатор, неизвестный идентификатор, URL длиннее строки запроса Netty. Клиент режет запрос по длине URL (D0a задача 5, D2-min задача 2).
2. Среднее с непериодической дробью (значения 1, 1, 2 при трёх ячейках), порядок округления и перевод в double: `BigDecimal.divide` без `MathContext` бросает исключение, оракул обязан считать тем же способом (D0a задачи 3 и 4).
3. Пропуски: укрупнённая ячейка из частично наблюдённых исходных, полностью пустая, неполная последняя ячейка при `point_count`, не кратном отношению шагов; ряд из одних `null` (D0a задачи 3-5).
4. Шаг снимка не круглый (после автошага 17 с, 20 с), период не на границе ячейки, `step_ms` не кратен шагу снимка: API отвечает `400` без молчаливого округления, UI сам привязывает период к границам (D0a задача 4, D2-min задача 1).
5. Анализ без снимка (только нагрузка), онлайн-снимок без объявленных окон (авто-окно) и с окном `full`, capacity с файловым снимком шага 1, 2, 5, 10 с: вкладка не падает и честно говорит, чего нет (D2-min задачи 3 и 5, D3a задача 1).
6. Куча: расшифровка снимка на 1,5 млн ячеек во время работающей задачи при 512 МБ (D0b).

---

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: фаза 4 плана внедрения, срезы D0 (ответ API с рядами снимка и нагрузки,
  JSON вместо файла), D2 (глубокий анализ: выбор ряда, шаг, период, закрепление,
  общий курсор), D3 (стадии: именованные окна, полоса стадий, окно правила).
  Выделенный минимум для живого демо (D2-min) и полный объём МВП (D2-full, D3).
REQUIRED TO ACHIEVE IT:
  - D0a: контракт resource-series.v1 (каталог и значения с укрупнением шага),
    чистое ядро редукторов, эндпойнты, кэш разобранного снимка, Python-оракул
    и общие векторы, документация;
  - D0b: замер (задержка, куча, размер ответов, /buckets) и перевод
    предварительных значений ADR 0014 в принятые;
  - D0c: векторы и ветки редукторов max/min после влития среза S1 автошага;
  - D2-min: вкладка deep, модель времени в мс, выбор рядов, период, график
    нагрузки и ресурсных рядов с общим курсором, линии порогов из evidence;
  - D2-full: шаг, закрепление, таблица агрегатов из resource_summary, заметка
    об автошаге из source_summary;
  - D3a: полоса стадий из окон результата, переход в окно по клику;
  - D3b: список правил выбранного окна (после S5 ADR 0018);
  - D3c-ADR: решение об объявлении стадий при онлайн-сборе (текст ADR после
    согласия владельца, реализация вне МВП).
NOT REQUIRED (report-only):
  - любые правки политики, window_ids и вердикта (срез S5 ADR 0018);
  - API нагрузки с произвольным шагом (используется /buckets с rollup 1/10/30/60);
  - тепловая карта подов и таблица подов (P2, ADR 0020), сравнение плеч (P4/P5);
  - новые статистики периода, скользящие средние, перцентили из укрупнённых ячеек;
  - тренды на графике (D4), корреляции (фаза 6), ИИ-ссылки на ряды (U6);
  - зум перетаскиванием на графике, экспорт графика, сохранение состояния вкладки в URL;
  - E_max (замер только фиксируется), производный артефакт-колонки (только если D0b провалит критерий);
  - потоковый разбор снимка, изменение ResourceSnapshot.kt кроме enum из среза S1 автошага.
EXPECTED FILES TO CHANGE: перечислены в каждом срезе. Общий итог: Kotlin main 2
  новых файла (ResourceSeriesView.kt, SnapshotCache.kt) и правка LocalApi.kt;
  3 файла тестов Kotlin; 3 файла Python; 1 схема, 4 примера, 1 каталог фикстур;
  UI: 3 новых модуля (deep.ts, DeepAnalysisPanel.vue, DeepCursorChart.vue) плюс
  stages.ts и DeepStageStrip.vue в D3a; правки App.vue, api.ts, types.ts,
  labels.ts, shell.css; 5 файлов e2e.
```

## Срезы: размеры, зависимости, параллелизм

Размеры: XS до 100 строк, S до 300, M до 700, L до 1 500, XL больше (строки diff без учёта фикстур и документов).

| Срез | Ветка | Что | Размер | Зависит от | Identity |
| --- | --- | --- | --- | --- | --- |
| D0a | `feat/resource-series-api` | контракт, ядро, эндпойнты, кэш, оракул, векторы, документация | L | ADR 0014 (Accepted для измеренных значений) | нет |
| D0b | `test/resource-series-measurement` | замер, перевод Proposed-значений в принятые (правка ADR) | S, тяжёлый прогон | D0a | нет |
| D0c | `test/resource-series-max-min` | векторы и ветки редукторов `max`/`min` | XS | D0a и срез S1 автошага (оба влиты) | нет |
| D2-min | `feat/ui-deep-analysis-min` | вкладка deep, график нагрузки и рядов, общий курсор, выбор рядов, период | L | схема D0a заморожена (задача 2 D0a); живой тест после D0a; U0 и U1 влиты (есть) | нет |
| D2-full | `feat/ui-deep-analysis-full` | шаг, закрепление, таблица агрегатов, заметка об автошаге | M | D2-min | нет |
| D3a | `feat/ui-deep-stages` | полоса стадий, переход в окно, подсветка окна на графиках | M | D2-min | нет |
| D3b | `feat/ui-deep-rule-window` | правила выбранного окна | S | D3a, срезы S1 и S5 ADR 0018 | нет (читает evidence) |
| D3c-ADR | `docs/adr-stage-declaration` | ADR об объявлении стадий при онлайн-сборе | XS | решение владельца (вопрос 6) | описывает identity-изменение |
| D3c | отдельный план | поле стадий в запросе источника, запись в снимок | M-L | D3c-ADR, срезы S2 автошага и S1 ADR 0018 в очереди identity | да |

### Что можно вести параллельно

- D0a и модельная часть D2-min (задачи 1, 2, 4 с подменённым API) после того, как задача 2 D0a (схема и примеры) влита или согласована как заморожённая. Живая сверка D2-min с сервером (задача 6) только после D0a.
- D0b и D2-min: замер идёт на собранном D0a и не конфликтует с UI.
- D3a-модель (`stages.ts`, `DeepStageStrip.vue`) параллельна D2-full; подключение в `DeepAnalysisPanel.vue` (около 15 строк) делается вторым из двух срезов с rebase. D3b строго после D3a.
- Строго последовательно: D2-min, затем D2-full и D3a, затем D3b; D0a, затем D0b и D0c.

### Минимум для живого демо и полный объём МВП

Демо: минимальный коннектор Grafana (существующий транспорт `grafana_proxy`), базовый анализ по SLA, анализ теста максимума (capacity, снимок из файла: онлайн-источники с capacity запрещены, `SOURCE_INPUT_CONFLICT`), глубокий анализ в урезанном виде.

| Состав | D0a | D2-min | D2-full | D3a, D3b |
| --- | --- | --- | --- | --- |
| Каталог и значения на шаге снимка, период, пагинация | да | использует | | |
| Укрупнение шага, `observed`, редукторы mean | да | авто-выбор шага (до 1 500 ячеек) | ручной выбор | |
| Редукторы max/min | ветки после S1 (D0c) | | | |
| График нагрузки (RPS, p95) и ресурсных рядов, общий курсор во времени | | да | | |
| Выбор рядов (до 6), фильтр по имени, умолчание по нарушенным правилам | | да | | |
| Период (с, по, весь прогон), привязка к границам ячеек | | да | | |
| Линии порогов из `resource_policy_check` | | да | | |
| Закрепление рядов, ручной шаг | | нет | да | |
| Таблица агрегатов окна из `resource_summary` | | нет | да | |
| Заметка «шаг подобран автоматически» из `source_summary` | | нет | да | |
| Полоса стадий и переход в окно | | нет | | D3a |
| Правила выбранного окна | | нет | | D3b |
| Объявление стадий для онлайн-снимка | | нет | | вне МВП (D3c) |

Что даёт полный объём сверх демо: ручной шаг и закрепление для сопоставления рядов на общей шкале; таблица агрегатов окна без новых статистик; видимость автошага и потери пика; стадии как рабочая ось длинного теста (4-8 часов) и привязка просмотра к окну правила. Демо без стадий остаётся честным: окно `full` или `run-intersection` показывается как «весь прогон».

### Горячие точки конфликтов

| Файл | Кто меняет | Правило |
| --- | --- | --- |
| `fixtures/slice1/identity/*`, `fixtures/slice1/manifest.json` | никто из срезов плана | в каждом PR проверка `git diff --name-only origin/main` без этих путей (см. «Проверка»); новые фикстуры только в `fixtures/resource-series/` |
| `CHANGELOG.md` | D0a, D2-min, D2-full, D3a, D3b | строка в конец списка `Added` раздела `[Unreleased]`, при конфликте rebase и сохранение обеих записей |
| `ui/src/App.vue` | D2-min (монтирование панели), D3a (не трогает) | одна вставка рядом с `OverviewPanel`; срезы U3-U7 тоже правят файл, порядок слияния согласует оркестратор |
| `ui/src/shell/labels.ts` | D2-min (`ShellTabKey`, `SHELL_TABS`, `DEEP_LABELS`), D2-full, D3a, D3b | ключ вкладки `'deep'` вставляется после `'overview'`; план U3-U7 (`docs/plan-ui-u3-u7`) пишется параллельно и тоже правит `SHELL_TABS`: тот, кто вливается вторым, правит порядок и счётчики в `shell.spec.ts` |
| `ui/src/types.ts`, `ui/src/api.ts` | D2-min (новые типы и две функции) | только добавление в конец блоков; `createJob` не трогать |
| `src/main/kotlin/io/ltverdict/web/LocalApi.kt` | D0a | только новые маршруты и приватные помощники в конце файла; константы рядом с `MAX_BUCKET_LIMIT` |
| `ResourceSnapshot.kt` (enum `ResourceAggregation`) | S1 автошага | D0a читает enum, не меняет; см. «Что известно заранее» п. 3 |
| `ui/scripts/verify-policy-schema.mjs` | D0a, автошаг S1/S2 | добавлять блок в конец списка проверок |
| `docs/user/slice-1-local-analysis.md` | D0a, D2-min | раздел «Глубокий анализ» новым подразделом, строки не сдвигать |

---

## Срез D0a. API рядов снимка

**Ветка:** `feat/resource-series-api`. **Размер:** L. **Контракт:** новый ответ `resource-series.v1` и два эндпойнта; существующие не меняются. **Не входит:** `max`/`min` на реальных снимках (D0c), замер (D0b), нагрузка (используется `/buckets`).

### Принятые решения D0a (дополнение ADR 0014, фиксируются задачей 1)

1. **Форма.** Один `schema_version: "resource-series.v1"`, различие по полю `kind` (`catalog` или `values`); схема `oneOf`.
2. **Каталог:** `GET /api/runs/{runId}/analyses/{analysisId}/resource-series?after=&limit=`; `limit` 1..256, по умолчанию 256; курсор `after` исключительный, `next_after` или `null`; порядок по `id` как в снимке (UTF-16, `ResourceSnapshot.kt:314`). Поля ряда: `id`, `metric`, `unit`, `entity`, `role`, `aggregation`, `reducer`, `labels`, `observed_cells`. Окна каталога: объявленные в снимке (`windows`, возможно пустой массив); разрешённые окна, включая `run-intersection`, клиент берёт из evidence результата.
3. **Значения:** `GET .../resource-series/values?series_id=...&from_ms=&to_ms=&step_ms=&limit=`. Повторяемый только `series_id`: от 1 до 32 уникальных, порядок рядов в ответе равен порядку в запросе, неизвестный идентификатор даёт `404`, дубликат и 33-й дают `400`.
4. **Сетка укрупнения** как в ADR 0014: ячейка `k` покрывает `[start + k*step_ms, start + (k+1)*step_ms)`, `step_ms` кратен шагу снимка, `from_ms` и `to_ms` лежат на границах (`to_ms` допускает конец сетки), иначе `400` без округления. Умолчания: `step_ms` = шаг снимка, `from_ms` = начало сетки, `to_ms` = конец сетки.
5. **Страница.** `limit` это число укрупнённых ячеек на ряд, умолчание `min(2 000, 100 000 / число рядов)`; `число рядов * limit > 100 000` даёт `413 RESOURCE_LIMIT_EXCEEDED` (существующий `tooLarge`), а не усечение; `limit` вне 1..100 000 даёт `400`. `next_from_ms` это начало первой не возвращённой ячейки или `null`.
6. **Редуктор по агрегации:** `interval_mean` и `interval_rate` дают `mean`, `interval_max` даёт `max`, `interval_min` даёт `min` (ветки `max`/`min` добавляет вторым срез D0c или S1).
7. **Пропуски в укрупнённой ячейке.** Редуктор действует по наблюдённым исходным ячейкам; ноль наблюдённых даёт `null`; число наблюдённых исходных ячеек выдаётся массивом `observed` (опускается при `step_ms` равном шагу снимка). Неполная последняя ячейка сетки: `last_cell_source_cells` меньше `source_cells_per_cell`. Клиент обязан показывать неполные ячейки как неполные (вопрос 3 владельцу).
8. **Арифметика (закрепляется для оракула).** Среднее: точная сумма `BigDecimal`, деление на число наблюдённых с `MathContext(34, HALF_EVEN)`. Перевод в double: `java.lang.Double.parseDouble(value.toPlainString())` (правильное округление). Максимум и минимум точны (перевод монотонен). Сравнение с оракулом идёт по разобранным double (`==`), а не по тексту JSON (ответ проходит `canonicalJson`, `canonicalDecimal` переписывает запись числа).
9. **Кэш.** Не более одного разобранного снимка в памяти (ключ путь каталога анализа), расшифровка под общим `Mutex` (одна за раз, остальные ждут и находят попадание). Кэш хранит `ResourceSnapshotV1` и семантический хэш, сырые байты не хранит. Производный артефакт-колонки не вводится, пока замер D0b не провален.

### Файлы D0a

- Create: `src/main/kotlin/io/ltverdict/core/ResourceSeriesView.kt` (редукторы, сетка, страница, сборка JSON)
- Create: `src/main/kotlin/io/ltverdict/web/SnapshotCache.kt` (один разобранный снимок, один расшифровщик)
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (два маршрута, `requireQueries`, константы)
- Create: `docs/contracts/resources/v1/resource-series.schema.json`
- Create: `docs/contracts/resources/v1/examples/valid/resource-series-catalog.json`, `.../valid/resource-series-values.json`, `.../invalid/resource-series-values-step-zero.json`, `.../invalid/resource-series-unknown-kind.json`
- Modify: `ui/scripts/verify-policy-schema.mjs`
- Create: `tools/resource_series_oracle.py`, `tools/resource_series_vectors.py`, `tools/test_resource_series_oracle.py`
- Create: `fixtures/resource-series/cases.json`, `fixtures/resource-series/snapshot-small.json`
- Create: `src/test/kotlin/io/ltverdict/core/ResourceSeriesViewTest.kt`, `src/test/kotlin/io/ltverdict/web/ResourceSeriesApiTest.kt`
- Modify: `docs/adr/0014-resource-series-limits-autostep-arm-api.md` (раздел «Уточнения D0»), `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces (производит D0a; используют D2-min, D0b, D0c):**

```kotlin
internal enum class SeriesReducer(val wireName: String) { MEAN("mean"), MAX("max"), MIN("min") }
internal fun reducerFor(aggregation: ResourceAggregation): SeriesReducer
internal val SERIES_MEAN_CONTEXT: MathContext
internal data class CoarseCell(val value: Double?, val observed: Int)
internal fun coarsen(values: List<BigDecimal?>, ratio: Int, firstCell: Int, cellCount: Int, reducer: SeriesReducer): List<CoarseCell>
internal class SeriesQueryException(val tooLarge: Boolean, message: String) : IllegalArgumentException(message)
internal data class SeriesGrid(val startMs: Long, val stepMs: Long, val pointCount: Int) { val endMs: Long }
internal data class ValuesPlan(
    val stepMs: Long, val ratio: Int, val firstCell: Int, val cellCount: Int,
    val nextFromMs: Long?, val sourceCellsPerCell: Int, val lastCellSourceCells: Int,
)
internal fun planValuesPage(grid: SeriesGrid, stepMs: Long?, fromMs: Long?, toMs: Long?, limit: Int?, seriesCount: Int): ValuesPlan
internal fun catalogJson(snapshot: ResourceSnapshotV1, semanticSha256: String, after: String?, limit: Int): JsonObject
internal fun valuesJson(snapshot: ResourceSnapshotV1, semanticSha256: String, seriesIds: List<String>, plan: ValuesPlan): JsonObject
internal const val MAX_VALUES_CELLS = 100_000
internal const val MAX_VALUES_SERIES = 32
internal const val MAX_CATALOG_PAGE = 256
internal const val DEFAULT_VALUES_CELLS = 2_000
```

```kotlin
internal data class DecodedSnapshot(val snapshot: ResourceSnapshotV1, val semanticSha256: String)
internal class SnapshotCache {
    suspend fun get(key: String, decode: () -> DecodedSnapshot): DecodedSnapshot
}
```

### Task 1: ADR 0014, раздел «Уточнения D0» (docs, коммит до кода)

**Files:** Modify `docs/adr/0014-resource-series-limits-autostep-arm-api.md`.

- [ ] **Step 1: Красная проверка.** Run: `git grep -n "Уточнения D0" -- docs/adr/0014-resource-series-limits-autostep-arm-api.md`. Expected: нет совпадений (код возврата 1).
- [ ] **Step 2: Добавить раздел** в конец документа (после «Решения владельца (2026-10-04): автошаг», чтобы не сдвигать номера строк, на которые ссылаются другие документы). Текст раздела (по-русски, 9 пунктов): пункты 1-9 из «Принятые решения D0a» этого плана дословно с заменой «этого плана» на «срез D0a»; плюс абзац: «`min` как редуктор `interval_min` дополняет часть 6, где перечислен только `max`; значения страницы 100 000 ячеек и каталога 256 остаются Proposed до замера D0b, лимит 32 рядов на запрос и 2 000 ячеек по умолчанию вводятся как реализационные и пересматриваются вместе с ними».
- [ ] **Step 3: Проверить.** Run: `git grep -n "Уточнения D0" -- docs/adr/0014-resource-series-limits-autostep-arm-api.md; npx --yes markdownlint-cli2@0.23.2 "docs/adr/0014-resource-series-limits-autostep-arm-api.md"`. Expected: совпадение найдено, ошибок нет.
- [ ] **Step 4: Commit.**

```bash
git add docs/adr/0014-resource-series-limits-autostep-arm-api.md
git commit -m "docs(adr): record the resource series API details for D0

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

### Task 2: Схема, примеры, проверка контрактов

**Files:** Create схему и 4 примера; Modify `ui/scripts/verify-policy-schema.mjs`.

- [ ] **Step 1: Красный тест.** В `verify-policy-schema.mjs` по образцу блока `basic resources` добавить загрузку схемы `docs/contracts/resources/v1/resource-series.schema.json` и четырёх примеров; в массив проверок: `['series catalog', catalogExample, true]`, `['series values', valuesExample, true]`, `['series values step zero', stepZero, false]`, `['series unknown kind', unknownKind, false]`. Run: `npm --prefix ui run test:contracts`. Expected: FAIL (нет файла схемы).
- [ ] **Step 2: Схема.** `resource-series.schema.json`: `$id` `.../resources/v1/resource-series.schema.json`, `oneOf` по `kind`. Общие обязательные поля: `schema_version` const `resource-series.v1`, `kind`, `resource_snapshot_sha256` (`^[0-9a-f]{64}$`), `numeric_encoding` const `ieee754-double`, `additionalProperties: false` везде.

Каталог (`kind: "catalog"`), обязательные: `grid` (`start_epoch_ms`, `step_ms` 1000..60000 кратно 1000, `point_count` 1..100000), `windows` (массив `id`, `from_epoch_ms`, `to_epoch_ms`, до 64), `series` (до 256: `id` строка 1..128 байт, `metric`, `unit`, `entity`, `role` enum `system|generator`, `aggregation` enum `interval_mean|interval_rate|interval_max|interval_min`, `reducer` enum `mean|max|min`, `labels` объект строк, `observed_cells` целое >= 0), `next_after` строка или `null`.

Значения (`kind: "values"`), обязательные: `grid` (`start_epoch_ms`, `source_step_ms`, `step_ms`, `first_cell_start_ms`, `cell_count` >= 1, `source_cells_per_cell` >= 1, `last_cell_source_cells` >= 1), `series` (до 32: `id`, `aggregation`, `reducer`, `values` массив `number|null` длины `cell_count`, необязательный `observed` массив целых >= 0 той же длины), `next_from_ms` целое или `null`. Проверка равенства длин и `cell_count` по схеме не выражается: она в тесте ядра, как предел ячеек для снимка (`description` схемы перечисляет проверки рантайма: кратность `step_ms`, границы ячеек, `число рядов * limit <= 100 000`).

- [ ] **Step 3: Примеры.** `resource-series-catalog.json`: два ряда (`cpu`, `memory`), `grid` 15000/50, окно `evaluation`, `next_after: null`. `resource-series-values.json`: два ряда, `step_ms` 60000, `source_cells_per_cell` 4, `cell_count` 13, последняя ячейка с `last_cell_source_cells` 2, значения с `null`, массив `observed`. `invalid/...step-zero.json`: `grid.step_ms` 0. `invalid/...unknown-kind.json`: `kind: "page"`.
- [ ] **Step 4:** Run: `npm --prefix ui run test:contracts`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(contracts): add the resource-series.v1 schema and examples`.

### Task 3: Python-оракул и общие векторы

**Files:** Create `tools/resource_series_oracle.py`, `tools/resource_series_vectors.py`, `tools/test_resource_series_oracle.py`, `fixtures/resource-series/cases.json`, `fixtures/resource-series/snapshot-small.json`.

Оракул независим от Kotlin: читает снимок как текст, значения как `Decimal` (`json.loads(text, parse_float=Decimal, parse_int=Decimal)`), считает редуктор и страницу тем же правилом, но самостоятельным кодом. Хэш снимка берёт из `stats_validation.snapshot_hash` (существующий оракул, выровнен с ядром в PR #24).

- [ ] **Step 1: Красный тест** `tools/test_resource_series_oracle.py` (unittest):

```python
import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import resource_series_oracle as oracle  # noqa: E402
import resource_series_vectors as vectors  # noqa: E402

FIXTURES = Path(__file__).parent.parent / "fixtures" / "resource-series"


def _read(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


class ResourceSeriesOracleTest(unittest.TestCase):
    def test_mean_of_one_one_two_is_rounded_at_34_digits(self):
        cell = oracle.reduce_cell([Decimal(1), Decimal(1), Decimal(2)], "mean")
        self.assertEqual(Decimal("1.333333333333333333333333333333333"), cell[0])
        self.assertEqual(3, cell[1])

    def test_max_min_and_empty_cell(self):
        values = [Decimal("0.5"), None, Decimal("-2"), Decimal("3")]
        self.assertEqual((Decimal("3"), 3), oracle.reduce_cell(values, "max"))
        self.assertEqual((Decimal("-2"), 3), oracle.reduce_cell(values, "min"))
        self.assertEqual((None, 0), oracle.reduce_cell([None, None], "mean"))

    def test_double_conversion_is_correctly_rounded(self):
        self.assertEqual(1 / 3, oracle.to_double(Decimal("0.333333333333333333333333333333333")))
        self.assertEqual(0.0, oracle.to_double(Decimal("-0.0")))

    def test_committed_vectors_equal_the_regenerated_ones(self):
        generated = vectors.generate()
        self.assertEqual(_read("snapshot-small.json"), generated["snapshot-small.json"])
        self.assertEqual(json.loads(_read("cases.json")), json.loads(generated["cases.json"]))

    def test_vectors_cover_the_review_focus_cases(self):
        names = {case["name"] for case in json.loads(_read("cases.json"))["cases"]}
        self.assertEqual(
            {"source-step", "step-60s-all", "subrange", "paging-first", "paging-next",
             "partial-last-cell", "empty-series", "catalog-first-page", "catalog-after"},
            names,
        )


if __name__ == "__main__":
    unittest.main()
```

Run: `python -m unittest tools.test_resource_series_oracle -v`. Expected: FAIL (нет модулей).

- [ ] **Step 2: Оракул** `tools/resource_series_oracle.py`:

```python
"""Independent oracle for the resource-series API (ADR 0014 part 6).

Arithmetic is pinned: the sum is exact (Decimal precision 200 is wider than any
snapshot value allows), the mean is rounded to 34 significant digits with
ROUND_HALF_EVEN, the double is float(Decimal) (correctly rounded). The Kotlin core
must agree on every returned double (compare parsed numbers, never JSON text).
"""
import json
from decimal import Context, Decimal, ROUND_HALF_EVEN

import stats_validation

SUM_CONTEXT = Context(prec=200)
MEAN_CONTEXT = Context(prec=34, rounding=ROUND_HALF_EVEN)
MAX_VALUES_CELLS = 100_000
REDUCERS = {"interval_mean": "mean", "interval_rate": "mean", "interval_max": "max", "interval_min": "min"}


def to_double(value):
    return float(value) + 0.0  # normalizes -0.0


def reduce_cell(cells, reducer):
    observed = [value for value in cells if value is not None]
    if not observed:
        return None, 0
    if reducer == "mean":
        total = Decimal(0)
        for value in observed:
            total = SUM_CONTEXT.add(total, value)
        return MEAN_CONTEXT.divide(total, Decimal(len(observed))), len(observed)
    return (max(observed) if reducer == "max" else min(observed)), len(observed)


def read_snapshot(text):
    return json.loads(text, parse_float=Decimal, parse_int=Decimal)


def _sorted_series(snapshot):
    return sorted(snapshot["series"], key=lambda item: stats_validation._utf16_order(item["id"]))


def catalog(snapshot, after=None, limit=256):
    series = _sorted_series(snapshot)
    if after is not None:
        series = [item for item in series if stats_validation._utf16_order(item["id"]) > stats_validation._utf16_order(after)]
    page = series[:limit]
    return {
        "series": [
            {"id": item["id"], "aggregation": item["aggregation"], "reducer": REDUCERS[item["aggregation"]],
             "observed_cells": sum(1 for value in item["values"] if value is not None)}
            for item in page
        ],
        "next_after": page[-1]["id"] if len(series) > limit else None,
    }


def values(snapshot, series_ids, step_ms=None, from_ms=None, to_ms=None, limit=None):
    start, source_step, count = int(snapshot["start_epoch_ms"]), int(snapshot["step_ms"]), int(snapshot["point_count"])
    step = step_ms or source_step
    ratio = step // source_step
    total_cells = -(-count // ratio)
    first = ((from_ms - start) // step) if from_ms is not None else 0
    end_cell = total_cells if to_ms is None or to_ms == start + source_step * count else (to_ms - start) // step
    per_series = limit or min(2000, MAX_VALUES_CELLS // len(series_ids))
    cell_count = min(end_cell - first, per_series)
    by_id = {item["id"]: item for item in snapshot["series"]}
    result = []
    for series_id in series_ids:
        item = by_id[series_id]
        reducer = REDUCERS[item["aggregation"]]
        out, observed = [], []
        for cell in range(first, first + cell_count):
            reduced, seen = reduce_cell(item["values"][cell * ratio: (cell + 1) * ratio], reducer)
            out.append(None if reduced is None else to_double(reduced))
            observed.append(seen)
        result.append({"id": series_id, "values": out, "observed": observed})
    last = first + cell_count - 1
    return {
        "first_cell_start_ms": start + first * step,
        "cell_count": cell_count,
        "source_cells_per_cell": ratio,
        "last_cell_source_cells": min(ratio, count - last * ratio),
        "series": result,
        "next_from_ms": start + (first + cell_count) * step if first + cell_count < end_cell else None,
    }
```

- [ ] **Step 3: Генератор векторов** `tools/resource_series_vectors.py`: функция `generate()` возвращает словарь «имя файла: текст». Снимок `snapshot-small.json`: шаг 15000, 50 точек, `start_epoch_ms` 1767225600000; ряды `cpu` (mean; значения `((i * 37) % 1000) / 1000`, пропуск при `i % 7 == 3`), `queue` (rate; значения `1, 1, 2, 1, 1, 2, ...` для непериодической дроби), `tiny` (mean; `0.000000000001` и `1` по очереди, пропуск последних 6 ячеек), `gap` (mean; все `null`), `big` (mean; `100000000000000000`, `99999999999999999.999999999999` по очереди), без `windows` и `rules`. Только детерминированная арифметика, без `random`. `cases.json`: `{"snapshot": "snapshot-small.json", "cases": [...]}`; каждый случай `{name, endpoint: "values"|"catalog", query: {...}, expected: {...}}`, ожидаемое получено вызовом `oracle.values`/`oracle.catalog`; числа пишутся как `repr` double. Случаи: `source-step` (все ряды, `step_ms` 15000), `step-60s-all` (60000, ячеек 13, последняя неполная: `last_cell_source_cells` 2), `subrange` (`step_ms` 60000, `from_ms` = начало сетки + 60000, `to_ms` = начало сетки + 540000: обе границы на сетке укрупнения), `paging-first` (`limit` 5), `paging-next` (`from_ms` из `next_from_ms` предыдущего), `partial-last-cell` (шаг 45000: 50 точек по 3, последняя ячейка 2), `empty-series` (ряд `gap`, ожидается `null`, `observed` 0), `catalog-first-page` (`limit` 2), `catalog-after` (`after` второго ряда). В конец файла: `if __name__ == "__main__"`: записать файлы в `fixtures/resource-series/`.

- [ ] **Step 4:** Run: `python tools/resource_series_vectors.py; python -m unittest tools.test_resource_series_oracle -v`. Expected: PASS.
- [ ] **Step 5: Commit** `test(tools): add the independent resource-series oracle and shared vectors`.

### Task 4: Чистое ядро `ResourceSeriesView.kt`

**Files:** Create `ResourceSeriesView.kt`, `ResourceSeriesViewTest.kt`.

- [ ] **Step 1: Красные тесты** (`ResourceSeriesViewTest`, имена и проверки):

```kotlin
class ResourceSeriesViewTest {
    private val ctx = SERIES_MEAN_CONTEXT

    @Test
    fun `mean of 1 1 2 is a 34 digit half-even decimal not an exception`() {
        val cells = coarsen(listOf(BigDecimal(1), BigDecimal(1), BigDecimal(2)), 3, 0, 1, SeriesReducer.MEAN)
        assertEquals(CoarseCell(1.3333333333333333, 3), cells.single())
    }

    @Test
    fun `mean reduces only observed cells and an empty cell is null with zero observed`() {
        val values = listOf(BigDecimal("0.5"), null, BigDecimal("1.5"), null, null, null)
        assertEquals(listOf(CoarseCell(1.0, 2), CoarseCell(null, 0)), coarsen(values, 3, 0, 2, SeriesReducer.MEAN))
    }

    @Test
    fun `max and min are exact for 32 digit values and the last cell may be partial`() {
        val big = BigDecimal("99999999999999999.999999999999")
        val values = listOf(big, BigDecimal("3"), BigDecimal("-2"), BigDecimal("7"), BigDecimal("1"))
        assertEquals(listOf(CoarseCell(1.0E17, 2), CoarseCell(7.0, 2), CoarseCell(1.0, 1)), coarsen(values, 2, 0, 3, SeriesReducer.MAX))
        assertEquals(CoarseCell(-2.0, 2), coarsen(values, 2, 1, 1, SeriesReducer.MIN).single())
    }

    @Test
    fun `plan keeps the source step by default and returns the whole grid`() {
        val plan = planValuesPage(SeriesGrid(1_000, 15_000, 50), null, null, null, null, 2)
        assertEquals(ValuesPlan(15_000, 1, 0, 50, null, 1, 1), plan)
    }

    @Test
    fun `plan rejects a step that is not a multiple of the snapshot step and boundaries off the coarse grid`() {
        val grid = SeriesGrid(1_000, 17_000, 50)
        listOf(
            Triple(20_000L, null, null),
            Triple(34_000L, 2_000L, null),
            Triple(34_000L, null, 36_000L),
            Triple(34_000L, 35_000L, 35_000L),
            Triple(0L, null, null),
        ).forEach { (step, from, to) ->
            assertFalse(assertThrows(SeriesQueryException::class.java) { planValuesPage(grid, step, from, to, null, 1) }.tooLarge)
        }
    }

    @Test
    fun `plan accepts the grid end as a boundary and reports the partial last cell`() {
        val plan = planValuesPage(SeriesGrid(0, 15_000, 50), 45_000, 0, 750_000, null, 1)
        assertEquals(ValuesPlan(45_000, 3, 0, 17, null, 3, 2), plan)
    }

    @Test
    fun `plan pages by limit and rejects series times limit above the cell cap as too large`() {
        val first = planValuesPage(SeriesGrid(0, 15_000, 50), null, null, null, 20, 1)
        assertEquals(300_000L, first.nextFromMs)
        assertEquals(20, first.cellCount)
        assertTrue(assertThrows(SeriesQueryException::class.java) { planValuesPage(SeriesGrid(0, 1_000, 100_000), null, null, null, 3_200, 32) }.tooLarge)
        assertFalse(assertThrows(SeriesQueryException::class.java) { planValuesPage(SeriesGrid(0, 1_000, 10), null, null, null, 0, 1) }.tooLarge)
    }

    @Test
    fun `the reducer follows the aggregation`() {
        assertEquals(SeriesReducer.MEAN, reducerFor(ResourceAggregation.INTERVAL_MEAN))
        assertEquals(SeriesReducer.MEAN, reducerFor(ResourceAggregation.INTERVAL_RATE))
    }
}
```

Run: `.\gradlew.bat test --tests "io.ltverdict.core.ResourceSeriesViewTest"`. Expected: FAIL (компиляция).

- [ ] **Step 2: Реализация** (`ResourceSeriesView.kt`, ключевые функции целиком):

```kotlin
package io.ltverdict.core

internal const val MAX_VALUES_CELLS = 100_000
internal const val MAX_VALUES_SERIES = 32
internal const val MAX_CATALOG_PAGE = 256
internal const val DEFAULT_VALUES_CELLS = 2_000
internal val SERIES_MEAN_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)

internal enum class SeriesReducer(val wireName: String) { MEAN("mean"), MAX("max"), MIN("min") }

// Исчерпывающий when без else: ветки INTERVAL_MAX -> MAX и INTERVAL_MIN -> MIN добавляет второй из срезов D0a и S1 автошага.
internal fun reducerFor(aggregation: ResourceAggregation): SeriesReducer =
    when (aggregation) {
        ResourceAggregation.INTERVAL_MEAN, ResourceAggregation.INTERVAL_RATE -> SeriesReducer.MEAN
    }

internal data class CoarseCell(val value: Double?, val observed: Int)

private fun BigDecimal.toResponseDouble(): Double = java.lang.Double.parseDouble(toPlainString())

internal fun coarsen(values: List<BigDecimal?>, ratio: Int, firstCell: Int, cellCount: Int, reducer: SeriesReducer): List<CoarseCell> =
    List(cellCount) { offset ->
        val from = (firstCell + offset).toLong() * ratio
        val to = minOf(from + ratio, values.size.toLong())
        var count = 0
        var sum = BigDecimal.ZERO
        var best: BigDecimal? = null
        for (index in from.toInt() until to.toInt()) {
            val value = values[index] ?: continue
            count += 1
            when (reducer) {
                SeriesReducer.MEAN -> sum = sum.add(value)
                SeriesReducer.MAX -> if (best == null || value > best) best = value
                SeriesReducer.MIN -> if (best == null || value < best) best = value
            }
        }
        val reduced =
            when {
                count == 0 -> null
                reducer == SeriesReducer.MEAN -> sum.divide(BigDecimal(count), SERIES_MEAN_CONTEXT)
                else -> best
            }
        CoarseCell(reduced?.toResponseDouble(), count)
    }

internal class SeriesQueryException(val tooLarge: Boolean, message: String) : IllegalArgumentException(message)

internal data class SeriesGrid(val startMs: Long, val stepMs: Long, val pointCount: Int) {
    val endMs: Long get() = startMs + stepMs * pointCount
}

internal data class ValuesPlan(
    val stepMs: Long, val ratio: Int, val firstCell: Int, val cellCount: Int,
    val nextFromMs: Long?, val sourceCellsPerCell: Int, val lastCellSourceCells: Int,
)

private fun badQuery(message: String): Nothing = throw SeriesQueryException(false, message)

internal fun planValuesPage(grid: SeriesGrid, stepMs: Long?, fromMs: Long?, toMs: Long?, limit: Int?, seriesCount: Int): ValuesPlan {
    val step = stepMs ?: grid.stepMs
    if (step < grid.stepMs || step % grid.stepMs != 0L) badQuery("step_ms must be a multiple of the snapshot step")
    val ratioLong = step / grid.stepMs
    if (ratioLong > grid.pointCount) badQuery("step_ms exceeds the snapshot grid")
    val ratio = ratioLong.toInt()
    val totalCells = (grid.pointCount + ratio - 1) / ratio
    val firstOffset = (fromMs ?: grid.startMs) - grid.startMs
    if (firstOffset < 0 || firstOffset % step != 0L || firstOffset / step >= totalCells) badQuery("from_ms must be the start of a cell")
    val firstCell = (firstOffset / step).toInt()
    val endCell =
        when {
            toMs == null || toMs == grid.endMs -> totalCells
            (toMs - grid.startMs) % step == 0L && toMs < grid.endMs -> ((toMs - grid.startMs) / step).toInt()
            else -> badQuery("to_ms must be the start of a cell or the end of the grid")
        }
    if (endCell <= firstCell) badQuery("the requested range is empty")
    val perSeries = limit ?: minOf(DEFAULT_VALUES_CELLS, MAX_VALUES_CELLS / seriesCount)
    if (perSeries !in 1..MAX_VALUES_CELLS) badQuery("limit is invalid")
    if (perSeries.toLong() * seriesCount > MAX_VALUES_CELLS) throw SeriesQueryException(true, "series times limit exceeds $MAX_VALUES_CELLS cells")
    val cellCount = minOf(endCell - firstCell, perSeries)
    val last = firstCell + cellCount - 1
    return ValuesPlan(
        step, ratio, firstCell, cellCount,
        if (firstCell + cellCount < endCell) grid.startMs + (firstCell + cellCount).toLong() * step else null,
        ratio, minOf(ratio, grid.pointCount - last * ratio),
    )
}
```

`catalogJson` и `valuesJson` собирают `buildJsonObject` по схеме задачи 2: каталог берёт `snapshot.series` (уже отсортированы), пропускает до `after` исключительно, режет по `limit`, `observed_cells` = число ненулевых; `valuesJson` для каждого `seriesId` вызывает `coarsen(series.values, plan.ratio, plan.firstCell, plan.cellCount, reducerFor(series.aggregation))`, пишет `values` (`JsonNull` для `null`, `JsonPrimitive(double)` иначе), `observed` только при `plan.ratio > 1`, `reducer`, а `grid` содержит `start_epoch_ms`, `source_step_ms`, `step_ms`, `first_cell_start_ms = start + firstCell*step`, `cell_count`, `source_cells_per_cell`, `last_cell_source_cells`.

- [ ] **Step 3:** Run тот же тест. Expected: PASS.
- [ ] **Step 4: Commit** `feat(core): add resource series reducers, grid and page planning`.

### Task 5: Кэш, маршруты, тесты API на общих векторах

**Files:** Create `SnapshotCache.kt`, `ResourceSeriesApiTest.kt`; Modify `LocalApi.kt`.

- [ ] **Step 1: Красные тесты** `ResourceSeriesApiTest.kt` (по образцу `bucket API caps pages...` в `LocalApiTest`: `withServer`, `store.acceptInput`, `writeAnalysisAtomically`; помощники скопировать, дублирование нескольких строк допустимо). Тесты:

1. `catalog and values equal the oracle vectors`: читает `fixtures/resource-series/cases.json`, записывает `snapshot-small.json` как `resource-snapshot.json` в каталог анализа, для каждого случая строит запрос (повторяемые `series_id` через `URLEncoder.encode`), сравнивает ответ с `expected` по полям `series[].id`, `values`, `observed`, `first_cell_start_ms`, `cell_count`, `source_cells_per_cell`, `last_cell_source_cells`, `next_from_ms`/`next_after`; числа сравниваются как разобранные double точным `==`, `null` с `null`.
2. `values reject repeated single parameters, duplicates, more than 32 ids and unknown parameters`: `?series_id=cpu&series_id=cpu` 400, 33 разных 400, `&step_ms=15000&step_ms=15000` 400, `&foo=1` 400.
3. `values answer 404 for an unknown series and 404 for an analysis without a snapshot`.
4. `values answer 400 for off-grid boundaries and 413 for series times limit above the cap`: пары из `planValuesPage`-теста через HTTP; `limit=3201` при 32 рядах даёт 413 (снимок на 32 ряда создаётся в тесте).
5. `qualified identifiers with slash percent and non-ascii round-trip`: снимок с рядами `prom/a%b`, `метрика б`, запрос с `URLEncoder`, ответ содержит те же `id`.
6. `a request line near the Netty limit is accepted and a longer one fails cleanly`: идентификаторы по 128 байт ASCII; запрос из 20 идентификаторов (около 3 300 символов) возвращает 200; тест фиксирует фактический предел и подтверждает константу клиентской разбивки `URL_BUDGET_CHARS` (3 500, D2-min); если Netty по умолчанию принимает 4 096, константа остаётся 3 500.
7. `two concurrent value requests decode the snapshot once`: подсчёт вызовов расшифровщика через `SnapshotCache` напрямую (вынесенный юнит-тест на кэш: два корутинных `get` с одним ключом вызывают `decode` один раз, смена ключа вытесняет прежний).

Run: `.\gradlew.bat test --tests "io.ltverdict.web.ResourceSeriesApiTest"`. Expected: FAIL (404 на маршруте).

- [ ] **Step 2: Реализация.** `SnapshotCache.kt`:

```kotlin
internal data class DecodedSnapshot(val snapshot: ResourceSnapshotV1, val semanticSha256: String)

internal class SnapshotCache {
    private val lock = Mutex()
    private var key: String? = null
    private var value: DecodedSnapshot? = null

    suspend fun get(key: String, decode: () -> DecodedSnapshot): DecodedSnapshot =
        lock.withLock {
            val cached = value
            if (this.key == key && cached != null) return@withLock cached
            value = null // освободить прежний снимок до расшифровки нового: пик кучи не складывается
            this.key = null
            decode().also { this.key = key; value = it }
        }
}
```

`LocalApi.kt`: приватный помощник рядом с `requireOnlyQueries`:

```kotlin
private fun ApplicationCall.requireQueries(single: Set<String>, repeatable: Set<String>) {
    val parameters = request.queryParameters
    if (parameters.names().any { it !in single && it !in repeatable }) malformed("Query parameters are invalid")
    if (parameters.names().any { it in single && parameters.getAll(it)?.size != 1 }) malformed("Query parameters are invalid")
}
```

Маршруты рядом с `/buckets`: каталог (`requireQueries(setOf("after","limit"), emptySet())`, `intQuery("limit", MAX_CATALOG_PAGE, 1..MAX_CATALOG_PAGE)`, `after` через `singleQuery`), значения (`requireQueries(setOf("from_ms","to_ms","step_ms","limit"), setOf("series_id"))`; ids через `request.queryParameters.getAll("series_id")`, пусто, больше `MAX_VALUES_SERIES` или дубликаты дают `malformed`; неизвестный id `notFound("Series was not found")`; `limit` читается `optionalLongQuery`-подобным `optionalIntQuery`; `SeriesQueryException` превращается в `malformed(...)` или `tooLarge(...)` по полю `tooLarge`). Расшифровка: `stored = context.store.requireAnalysis(call)`; нет артефакта `resource-snapshot.json` даёт `notFound("Resource snapshot was not found")`; `cache.get(stored.path.toString()) { decode(stored) }` в `withContext(Dispatchers.IO)`; `decode` вызывает `validateResourceSnapshot(Files.newInputStream(path))`, при `Invalid` бросает `IllegalStateException` (сохранённый снимок уже прошёл проверку; сбой это ошибка сервера). Кэш создаётся один раз на `LocalApiContext`-уровне (`private val seriesCache = SnapshotCache()` в `installLocalApi`). Ответы через существующий `respondJson`.

- [ ] **Step 3:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.ResourceSeriesApiTest" --tests "io.ltverdict.web.LocalApiTest"`. Expected: PASS (`LocalApiTest` не должен измениться).
- [ ] **Step 4: Commit** `feat(api): serve resource series catalog and values from a saved analysis`.

### Task 6: Документация и закрывающая проверка

**Files:** Modify `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

- [ ] **Step 1:** В `slice-1-local-analysis.md` новый подраздел «Ряды ресурсов по API» рядом с описанием `resource-snapshot`: два эндпойнта, параметры, коды `400/404/413`, смысл `observed`, примечание «числа в ответе двойной точности и не авторитетны, точные значения в файле снимка и в статистиках `resource_summary`». В `CHANGELOG.md` `Added`: строка про эндпойнты `resource-series` и `resource-series/values` (чтение рядов сохранённого анализа с укрупнением шага; существующий `resource-snapshot` не менялся).
- [ ] **Step 2: Полная проверка.** Run: `.\gradlew.bat --no-daemon check; npm --prefix ui run test:contracts; python -m unittest discover -s tools -p "test_*.py" -v; npx --yes markdownlint-cli2@0.23.2 "**/*.md" "#.worktrees/**"`. Expected: PASS.
- [ ] **Step 3: Commit** `docs: document the resource series API`.

**Риски D0a:** расшифровка на 1,5 млн ячеек при куче 512 МБ (D0b); ключ `requireQueries` расширяет допуск повторяемых параметров только для нового маршрута; существующие маршруты на `requireOnlyQueries` не затронуты; identity не меняется (нет записи в анализ).

---

## Срез D0b. Замер и перевод Proposed-значений в принятые

**Ветка:** `test/resource-series-measurement`. **Размер:** S, один тяжёлый прогон под мьютексом `Global\ltv-heavy`, когда Lab не занят. **Не входит:** E_max, правки кода (только данные в ADR); код замера (скрипт) не коммитится, кроме одного воспроизводимого PowerShell-харнесса, если он нужен (решает исполнитель, по умолчанию не коммитить).

- [ ] **Step 1: Данные.** Сгенерировать снимки 1 024 x 1 440 и 600 x 1 920 (генератор `tools/perf/adr-a` из ветки замера ADR-A или одноразовый скрипт) и сохранить анализ через `ltv analyze`.
- [ ] **Step 2: Замеры** на `-Xmx512m` и `-Xmx384m`: (а) холодный первый `values` на 12 рядов x 1 440 ячеек; тёплый повтор; (б) то же во время работающей задачи анализа (вторая JVM-нагрузка: `ltv analyze` 10 млн строк параллельно); (в) размер ответа `values` на 100 000 ячеек и каталога на 256 рядов; (г) размер и время страницы `/buckets` (rollup 60, 480 корзин) и rollup 10 (6 страниц); (д) `GET /result` на анализе с 20 480 evidence (для вопроса про `E_max`: время и куча, отрисовка вкладки в UI вручную).
- [ ] **Критерии (предложение, подтвердить владельцем):** холодный `values` не более 3 с, тёплый не более 300 мс; пик кучи при расшифровке 1,5 млн ячеек не выше 384 МБ и без OOM при параллельной задаче на 512 МБ; ответ 100 000 ячеек не более 1,5 МБ; страница `/buckets` rollup 60 не более 250 КБ.
- [ ] **Step 3: Решение по критериям.** Прошли: страница 100 000 ячеек и каталог 256 переводятся в Accepted записью в раздел «Значения лимитов» ADR 0014 (docs-PR). Не прошли: открыть вопрос о производном артефакте-колонках (новый файл в манифесте бандла, меняет `RunBundleStore`, ADR-дополнение) и о меньшей странице; D2-min в этом случае ограничивает число рядов по умолчанию. Результат замера в основной чекаут (`docs/ui-mockup/`) вне git, в ADR только числа и условия.
- [ ] **Step 4: Commit** `docs(adr): accept the resource series page limits after measurement`.

---

## Срез D0c. Редукторы max и min

**Ветка:** `test/resource-series-max-min`. **Размер:** XS. **Условие старта:** срез S1 автошага влит.

- [ ] **Step 1: Красный тест.** В `ResourceSeriesViewTest` добавить `the reducer follows interval max and interval min` (`INTERVAL_MAX` даёт `MAX`, `INTERVAL_MIN` даёт `MIN`); если S1 уже влит раньше D0a, тест и ветки пишутся в D0a. Run: `.\gradlew.bat test --tests "io.ltverdict.core.ResourceSeriesViewTest"`. Expected: FAIL (ошибка компиляции `when`).
- [ ] **Step 2: Ветки** `ResourceAggregation.INTERVAL_MAX -> SeriesReducer.MAX`, `INTERVAL_MIN -> SeriesReducer.MIN`.
- [ ] **Step 3: Векторы.** Добавить в `resource_series_vectors.py` снимок `snapshot-peaks.json` (ряды `interval_max` и `interval_min`, пропуски, шаг 20000, 7 точек, укрупнение 60000) и два случая `max-60s`, `min-60s` в `cases.json`; обновить тест покрытия имён в `test_resource_series_oracle.py`; оракул уже поддерживает `max` и `min`. Тест API `catalog and values equal the oracle vectors` читает все случаи, правок кода не требует (каталог файлов векторов читается по `snapshot` каждого случая).
- [ ] **Step 4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.ResourceSeriesViewTest" --tests "io.ltverdict.web.ResourceSeriesApiTest"; python -m unittest tools.test_resource_series_oracle -v`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(core): reduce interval max and min series by max and min`.

---

## Срез D2-min. Глубокий анализ для живого демо

**Ветка:** `feat/ui-deep-analysis-min`. **Размер:** L. **Зависит от:** схема D0a (задача 2); живая сверка после D0a. **Контракты:** нет (клиент существующего и нового API). **Не входит:** шаг вручную, закрепление, стадии, таблица агрегатов, тренды, зум мышью.

**Состав экрана (МВП-демо).** Вкладка «Глубокий анализ» (ключ `deep`) после «Обзор». Верх: график нагрузки (дорожки RPS и p95 из `/buckets`) и выбранные ресурсные ряды (по дорожке на ряд, до 6) на общей оси времени; общий курсор (мышь и ползунок с клавиатурой) показывает значение каждой дорожки на момент курсора; линии порогов правил из `resource_policy_check` на дорожках соответствующих рядов; слева список рядов с фильтром по имени и флажками (максимум 6, при достижении остальные недоступны с подсказкой); период «с, по, мин от начала прогона» и кнопка «Весь прогон». Шаг подбирается сам (до 1 500 ячеек на ряд). Без снимка вкладка показывает только нагрузку и сообщение «ряды ресурсов не загружены». Предупреждение о часах: «часы генератора и кластера ядром не сверяются» (`clock_alignment`).

## Файлы D2-min

- Create: `ui/src/shell/deep.ts` (чистая модель)
- Create: `ui/src/shell/DeepAnalysisPanel.vue`, `ui/src/shell/DeepCursorChart.vue`
- Modify: `ui/src/api.ts` (`getResourceSeriesCatalog`, `getResourceSeriesValues`), `ui/src/types.ts` (`ResourceSeriesCatalog`, `ResourceSeriesEntry`, `ResourceSeriesValues`), `ui/src/shell/labels.ts` (`ShellTabKey`, `SHELL_TABS`, `DEEP_LABELS`), `ui/src/shell/shell.css`, `ui/src/App.vue`
- Create: `ui/e2e/deep-adapters.spec.ts`, `ui/e2e/deep.spec.ts`, `ui/e2e/deep-live.spec.ts`
- Modify: `ui/e2e/shell.spec.ts` (число вкладок), `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces (производит D2-min; используют D2-full и D3a):**

```ts
export interface SnapshotGrid { startMs: number; stepMs: number; pointCount: number }
export interface DeepPoint { startMs: number; value: number | null; observed: number | null }
export interface DeepTrack {
  key: string; label: string; unit: string; kind: 'load' | 'resource'
  reducer: 'mean' | 'max' | 'min' | null
  stepMs: number; sourceCellsPerCell: number; lastCellSourceCells: number
  points: DeepPoint[]; segments: number[][]; min: number; max: number
  thresholds: Array<{ ruleId: string; operator: 'gt' | 'lt'; value: number }>
}
export interface TimeAxis { fromMs: number; toMs: number }
export const MAX_SELECTED_SERIES = 6, TARGET_CELLS = 1500, LOAD_BUCKET_BUDGET = 1500
export const URL_BUDGET_CHARS = 3500, MAX_IDS_PER_REQUEST = 32
export function gridEndMs(grid: SnapshotGrid): number
export function snapPeriod(grid: SnapshotGrid, stepMs: number, fromMs: number, toMs: number): TimeAxis
export function chooseResourceStep(grid: SnapshotGrid, fromMs: number, toMs: number, target?: number): number
export function chooseLoadRollup(spanMs: number, budget?: number): 1 | 10 | 30 | 60
export function batchSeriesIds(ids: string[], queryBase: string, maxChars?: number, maxCount?: number): string[][]
export function defaultSelection(catalog: ResourceSeriesEntry[], result: AnalysisResult): string[]
export function resourceTrack(entry: ResourceSeriesEntry, series: ResourceSeriesValues['series'][number], grid: ResourceSeriesValues['grid'], thresholds: DeepTrack['thresholds']): DeepTrack
export function mergePages(left: DeepTrack, right: DeepTrack): DeepTrack
export function loadTracks(buckets: Bucket[], rollupSeconds: number, runStartMs: number): DeepTrack[]
export function thresholdsFor(result: AnalysisResult, seriesId: string): DeepTrack['thresholds']
export function runStartMs(result: AnalysisResult): number | null
export function cellAt(track: DeepTrack, timeMs: number): DeepPoint | null
export function cursorReadouts(tracks: DeepTrack[], timeMs: number): Array<{ key: string; text: string; raw: string; partial: boolean }>
export function nearestTime(axis: TimeAxis, fraction: number): number
export function trackPath(track: DeepTrack, axis: TimeAxis, width: number, height: number): string[]
```

### Task 1: Модель времени и сеток (`deep.ts`, чистые функции, TDD)

- [ ] **Step 1: Красные тесты** `ui/e2e/deep-adapters.spec.ts` (Playwright без браузера, по образцу `overview-adapters.spec.ts`):

```ts
import { expect, test } from '@playwright/test'
import { batchSeriesIds, cellAt, chooseLoadRollup, chooseResourceStep, snapPeriod, cursorReadouts, loadTracks, resourceTrack, runStartMs } from '../src/shell/deep'

const grid = { startMs: 1_767_225_600_000, stepMs: 17_000, pointCount: 1_700 }

test('resource step is the smallest multiple of the snapshot step that fits the cell target', () => {
  expect(chooseResourceStep(grid, grid.startMs, grid.startMs + 1_700 * 17_000)).toBe(34_000)
  expect(chooseResourceStep(grid, grid.startMs, grid.startMs + 100 * 17_000)).toBe(17_000)
  expect(chooseResourceStep({ ...grid, pointCount: 100_000 }, grid.startMs, grid.startMs + 100_000 * 17_000, 1500)).toBe(17_000 * 67)
})

test('period snaps outward to coarse cell boundaries and the grid end', () => {
  expect(snapPeriod(grid, 34_000, grid.startMs + 40_000, grid.startMs + 70_000)).toEqual({ fromMs: grid.startMs + 34_000, toMs: grid.startMs + 102_000 })
  const end = grid.startMs + 1_700 * 17_000
  expect(snapPeriod(grid, 51_000, grid.startMs, end + 5_000).toMs).toBe(end)
  expect(snapPeriod(grid, 34_000, grid.startMs + 5, grid.startMs + 6)).toEqual({ fromMs: grid.startMs, toMs: grid.startMs + 34_000 })
})

test('load rollup is the finest of 1, 10, 30, 60 s that fits the bucket budget', () => {
  expect(chooseLoadRollup(1_000_000)).toBe(1)
  expect(chooseLoadRollup(8 * 3600 * 1000)).toBe(30)
  expect(chooseLoadRollup(24 * 3600 * 1000)).toBe(60)
  expect(chooseLoadRollup(72 * 3600 * 1000)).toBe(60)
})

test('series ids are batched by count and by encoded URL length', () => {
  const ids = Array.from({ length: 70 }, (_, index) => `prom/a%b/${'x'.repeat(100)}-${index}`)
  const batches = batchSeriesIds(ids, '/api/x?step_ms=15000')
  expect(batches.flat()).toEqual(ids)
  for (const batch of batches) {
    expect(batch.length).toBeLessThanOrEqual(32)
    expect(`/api/x?step_ms=15000${batch.map((id) => `&series_id=${encodeURIComponent(id)}`).join('')}`.length).toBeLessThanOrEqual(3500)
  }
})

test('a resource cell is found by time, a gap stays null and the load axis uses the run start', () => {
  // ряд из двух ячеек по 60 с: вторая пропущена
  const track = resourceTrack(entry('cpu'), { id: 'cpu', aggregation: 'interval_mean', reducer: 'mean', values: [0.5, null], observed: [4, 0] },
    { start_epoch_ms: 0, source_step_ms: 15_000, step_ms: 60_000, first_cell_start_ms: 0, cell_count: 2, source_cells_per_cell: 4, last_cell_source_cells: 4 }, [])
  expect(cellAt(track, 59_999)?.value).toBe(0.5)
  expect(cellAt(track, 60_000)?.value).toBeNull()
  expect(cellAt(track, 120_000)).toBeNull()
  expect(track.segments).toEqual([[0]])
  expect(runStartMs({ evidence: [{ type: 'resource_binding', run_from_epoch_ms: 1000 }] } as never)).toBe(1000)
  const tracks = loadTracks([{ bucket_start_ms: 0, sample_count: 60, error_count: 1, p95_latency_ms: 90, max_latency_ms: 100, hdr_v2_base64: '' }], 60, 5_000)
  expect(tracks[0].points[0].startMs).toBe(5_000)
})

test('cursor readouts mark partial coarse cells and name missing values', () => {
  const track = resourceTrack(entry('cpu'), { id: 'cpu', aggregation: 'interval_max', reducer: 'max', values: [0.5], observed: [2] },
    { start_epoch_ms: 0, source_step_ms: 15_000, step_ms: 60_000, first_cell_start_ms: 0, cell_count: 1, source_cells_per_cell: 4, last_cell_source_cells: 4 }, [])
  expect(cursorReadouts([track], 10_000)[0]).toMatchObject({ key: 'cpu', raw: '0.5', partial: true })
})

function entry(id: string) {
  return { id, metric: 'm', unit: 'ratio', entity: 'e', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 2 } as never
}
```

Run: `npm --prefix ui run e2e -- deep-adapters`. Expected: FAIL (модуль не найден).

- [ ] **Step 2: Реализация** `deep.ts` (ключевые функции целиком):

```ts
export function gridEndMs(grid: SnapshotGrid): number { return grid.startMs + grid.stepMs * grid.pointCount }

export function chooseResourceStep(grid: SnapshotGrid, fromMs: number, toMs: number, target = TARGET_CELLS): number {
  const span = Math.max(toMs - fromMs, grid.stepMs)
  return Math.max(1, Math.ceil(span / (grid.stepMs * target))) * grid.stepMs
}

export function snapPeriod(grid: SnapshotGrid, stepMs: number, fromMs: number, toMs: number): TimeAxis {
  const cells = Math.ceil(grid.pointCount * grid.stepMs / stepMs)
  const first = Math.min(cells - 1, Math.max(0, Math.floor((fromMs - grid.startMs) / stepMs)))
  const last = Math.min(cells, Math.max(first + 1, Math.ceil((toMs - grid.startMs) / stepMs)))
  return { fromMs: grid.startMs + first * stepMs, toMs: last >= cells ? gridEndMs(grid) : grid.startMs + last * stepMs }
}

export function chooseLoadRollup(spanMs: number, budget = LOAD_BUCKET_BUDGET): 1 | 10 | 30 | 60 {
  for (const rollup of [1, 10, 30] as const) if (spanMs / (rollup * 1000) <= budget) return rollup
  return 60
}

export function cellAt(track: DeepTrack, timeMs: number): DeepPoint | null {
  for (const point of track.points) if (timeMs >= point.startMs && timeMs < point.startMs + track.stepMs) return point
  return null
}
```

Остальное: `batchSeriesIds` (накопление по числу и длине `&series_id=${encodeURIComponent(id)}`), `resourceTrack` (точка на ячейку `startMs = first_cell_start_ms + i*step_ms`, `observed` из массива или `null`, `segments` режутся на `null`, `partial = observed < source_cells_per_cell` (для последней ячейки сравнение с `last_cell_source_cells`)), `loadTracks` (две дорожки RPS и p95 из `loadSeries` обзора со сдвигом `startMs + runStart`; единицы `req/s` и `ms` из `OVERVIEW_LABELS`), `thresholdsFor` (из `resource_policy_check` с `series_id`, `threshold` как `Number`, исключая нечисловые), `runStartMs` (`resource_binding.run_from_epoch_ms`), `defaultSelection` (сначала ряды из `resource_policy_check` со статусом не `PASS`, затем по порядку каталога; не более `MAX_SELECTED_SERIES`), `cursorReadouts` (для каждой дорожки `cellAt`; `text` через `formatNumber`, `raw` как `String(value)`, `partial`).

- [ ] **Step 3:** Run: `npm --prefix ui run typecheck; npm --prefix ui run e2e -- deep-adapters`. Expected: PASS.
- [ ] **Step 4: Commit** `feat(ui): add the deep analysis time model`.

### Task 2: Типы, `api.ts`, подмена API

- [ ] **Step 1: Красный тест** (`deep.spec.ts`, часть): мок `page.route('**/resource-series?**')` и `'**/resource-series/values?**'` проверяет, что клиент обходит страницы каталога по `next_after`, режет значения на пакеты `batchSeriesIds`, склеивает страницы значений по `next_from_ms` и не отправляет повторяемых параметров кроме `series_id`. Expected: FAIL.
- [ ] **Step 2: Реализация.** `types.ts`: интерфейсы из схемы D0a (поля как в примерах). `api.ts`:

```ts
export function getResourceSeriesCatalog(runId: string, analysisId: string, after?: string, signal?: AbortSignal): Promise<ResourceSeriesCatalog> {
  const query = new URLSearchParams({ limit: '256' })
  if (after) query.set('after', after)
  return request(`/api/runs/${encodeURIComponent(runId)}/analyses/${encodeURIComponent(analysisId)}/resource-series?${query}`, { signal })
}

export function resourceSeriesValuesPath(runId: string, analysisId: string, ids: string[], params: Record<string, string>): string {
  const query = new URLSearchParams(params)
  for (const id of ids) query.append('series_id', id)
  return `/api/runs/${encodeURIComponent(runId)}/analyses/${encodeURIComponent(analysisId)}/resource-series/values?${query}`
}

export function getResourceSeriesValues(path: string, signal?: AbortSignal): Promise<ResourceSeriesValues> {
  return request(path, { signal })
}
```

(`request` уже принимает `init: RequestInit`, поэтому `{ signal }` работает без правок; её `JSON.parse` с `exactErrorCounts` числа рядов не меняет). Длину пути для `batchSeriesIds` считать по `resourceSeriesValuesPath` с пустым списком как `queryBase`.

- [ ] **Step 3:** Run: `npm --prefix ui run typecheck; npm --prefix ui run e2e -- deep`. Expected: PASS.
- [ ] **Step 4: Commit** `feat(ui): add resource series client calls and types`.

### Task 3: Вкладка, панель, график, курсор

- [ ] **Step 1: Красные тесты** `deep.spec.ts` (подменённый API, ответы строятся из примеров D0a):
  1. `the deep tab follows overview and loads the catalog only when a snapshot is bound` (вкладка `deep` второй после `setup`/`overview` по порядку `SHELL_TABS`; без `resource_binding` нет запросов к `resource-series`, видно `DEEP_LABELS.noSnapshot`, график нагрузки есть).
  2. `selecting up to six series draws one track per series and the seventh is disabled with a hint` (`data-testid="deep-track-<id>"`, флажок седьмого `disabled`).
  3. `the cursor shows the value of every track at one time` (мышь в `data-testid="deep-plot"`: `track-value-<key>` и `data-value` у всех дорожек соответствуют ячейкам, содержащим выбранный момент; клавиши Home/End/стрелки на `data-testid="deep-cursor"`).
  4. `a missing cell breaks the line and the readout names it` (`null` в ответе: два сегмента `polyline`, текст `DEEP_LABELS.gap`).
  5. `period inputs snap to cell boundaries and refetch values` (ввод «с 5, по 12 мин» шлёт `from_ms` и `to_ms` на границах ячеек; кнопка «Весь прогон» сбрасывает).
  6. `thresholds from resource policy checks are drawn on their track` (`data-testid="deep-threshold-<ruleId>"`).
  7. `a stale response of an older selection is ignored` (медленный первый ответ после смены выбора не перерисовывает дорожки; `revision` как `bucketRevision` в `App.vue`).
  8. `axe finds no violations in the deep tab in both themes` (по образцу `security-a11y.spec.ts`).

Expected: FAIL.

- [ ] **Step 2: Реализация.** `labels.ts`: `ShellTabKey` += `'deep'`, `SHELL_TABS` вставка `{ key: 'deep', label: 'Глубокий анализ', pending: false }` после `overview`; `DEEP_LABELS` (русские тексты: `title`, `noSnapshot`, `seriesTitle`, `seriesFilter`, `limitReached(max)`, `periodFrom`, `periodTo`, `periodAll`, `periodApply`, `reducerLabels` {mean: «среднее за интервал», max: «максимум за интервал», min: «минимум за интервал»}, `partialCell(observed, total)`, `gap`, `clockNote`, `cursorHint`, `cellStep(seconds)`, `loadRollup(seconds)`, `thresholdLabel(operator, value, unit)`). `DeepCursorChart.vue` повторяет раскладку `SharedCursorChart.vue` (дорожки SVG 1000x80 `preserveAspectRatio="none"`, `role="group"`, ползунок `type="range"` с `aria-valuetext`, подсказка клавиш), но курсор хранит время в мс; ползунок движется по индексу ячейки самой частой дорожки и переводится во время; стили `shared-chart__*` переиспользуются, новые классы `deep-chart__*` в `shell.css`. `DeepAnalysisPanel.vue` (props `result`, `runId`, `analysisId`): загрузка каталога (цикл по `next_after`), выбор рядов (`defaultSelection`), загрузка значений (`chooseResourceStep` по периоду, `snapPeriod`, пакеты, цикл по `next_from_ms`), загрузка нагрузки (`chooseLoadRollup(период)`, `getBuckets` по страницам `next_from_ms` в границах `from - runStart`, `to - runStart`, не более 4 страниц), `AbortController` и токен `revision` на каждый набор запросов. `App.vue`: рядом с `OverviewPanel` `<DeepAnalysisPanel v-if="shellNew && result && selectedAnalysisId && shownIn('deep')" :result :run-id :analysis-id />`.
- [ ] **Step 3:** Run: `npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run e2e -- deep shell overview overview-adapters overview-live`. Expected: PASS (обзор не регрессирует).
- [ ] **Step 4: Commit** `feat(ui): add the deep analysis tab with a shared time cursor`.

### Task 4: Нагрузка и ресурсы на двух сетках, анализ без снимка, capacity

- [ ] **Step 1: Красные тесты** `deep.spec.ts`: `load and resource tracks align by epoch time` (бакет нагрузки со смещением 60 000 мс при `run_from_epoch_ms` 1000 и ячейка ресурса с `start` 61 000 читаются курсором в одном моменте); `a capacity analysis with a file snapshot of 5 s step opens` (подменённый результат с `analysis_mode: 'capacity_step'`); `an online snapshot with qualified ids and no declared windows opens` (идентификаторы `prom-main/memory-limit-ratio`, `windows: []`); `a load only analysis shows the load tracks and the no-snapshot note`.
- [ ] **Step 2: Исправить** найденное; единицы нагрузки и ресурса не смешиваются на одной дорожке. Expected: PASS.
- [ ] **Step 3: Commit** `test(ui): cover deep analysis for online, capacity and load-only analyses`.

### Task 5: Живая сверка с оракулом (ворота D2-min)

**Зависит от:** D0a влит.

- [ ] **Step 1: Красный тест** `deep-live.spec.ts` (реальный сервер, как `overview-live.spec.ts`): входной CSV из 120 строк, снимок из `fixtures/resource-series/live-snapshot.template.json` с подставленным `load_input_sha256` (как в `resource-statistics.spec.ts`), ожидаемые значения из `fixtures/resource-series/live-expected.json` (сгенерированы оракулом, добавить в `resource_series_vectors.py` и тест воспроизводимости). Тест открывает `?shell=new`, вкладку «Глубокий анализ», для двух рядов читает `track-value-<id>` и `data-value` на первой, средней и последней ячейке (клавиши ползунка) и сравнивает с ожидаемыми `Number(...)` точно; для нагрузки сравнивает со значениями обзора на той же секунде. Затем период сужается до окна из 10 ячеек, снова сверка.
- [ ] **Step 2:** Run: `npm --prefix ui run e2e -- deep-live`. Expected: PASS.
- [ ] **Step 3: Документация и коммит.** `slice-1-local-analysis.md` (раздел о вкладке: что на экране, лимит рядов, предупреждение о часах), `CHANGELOG.md` (`Added`). Commit `feat(ui): verify deep analysis values against the oracle on a real server`.

### Task 6: Закрывающая проверка среза

- [ ] **Step 1:** Run: `npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run build; npm --prefix ui run e2e; npx --yes markdownlint-cli2@0.23.2 "**/*.md" "#.worktrees/**"`. Expected: PASS. Для e2e на Windows сначала `Remove-Item Env:NoDefaultCurrentDirectoryInExePath` в процессе.
- [ ] **Step 2:** `git diff --name-only origin/main | Select-String "fixtures/slice1/(identity|manifest)"`. Expected: пусто.

---

## Срез D2-full. Шаг, закрепление, таблица агрегатов

**Ветка:** `feat/ui-deep-analysis-full`. **Размер:** M. **Зависит от:** D2-min. **Не входит:** новые статистики; всё из «NOT REQUIRED».

- [ ] **Задача 1. Ручной шаг.** Тесты `deep-adapters.spec.ts`: `step options are the multiples of the snapshot step from a preset list plus auto` (для шага снимка 17 с список содержит 17, 34, 85, 170 с; нет 15 с); `a manual step above the cell target is allowed and a step below the auto minimum is disabled for periods over the page cap`. Реализация: функция `stepOptions(grid): number[]` в `deep.ts` (кратные пресетов 15 с, 30 с, 1, 5, 15 мин, всегда включая шаг снимка и «авто»); выпадающий список «Шаг» в панели; при выбранном шаге период пересчитывается `snapPeriod`. Подпись под графиком показывает применённый шаг и редуктор ряда (`DEEP_LABELS.reducerLabels`), для `mean` при укрупнении добавляется заметка «среднее по наблюдённым ячейкам, пропуски не заполняются».
- [ ] **Задача 2. Закрепление.** Тесты `deep.spec.ts`: `pinning keeps a series in a compact row that shares the cursor and the period`; `pins survive a period and step change but reset for another analysis`; `pin count is limited to 6 together with the selection`. Реализация: состояние закреплённых идентификаторов в панели (не `localStorage`: состояние на сессию вкладки), компактная дорожка 48 px под основным графиком в том же `DeepCursorChart` (один курсор). Лимит 6 общий.
- [ ] **Задача 3. Таблица агрегатов окна из `resource_summary`.** Тест: `the aggregates table shows min mean median q95 max of the selected series per window exactly as in resource_summary` (значения из `statistics`, строки `Number` не пересчитываются). Реализация: таблица под графиком (`tabindex="0"` у обёртки с прокруткой, `<th scope="col">`), колонки `min`, `mean`, `median`, `q95`, `max`, `observed_cells`, `missing_cells`; десятичные строки отображаются как есть. Заметка об автошаге: если в `source_summary` (поля плана автошага `step_origin`, `requested_step_ms`, `warnings`) есть `RESOLUTION_REDUCED`, над графиком одна строка «Шаг подобран автоматически: запрошено X с, применён Y с»; при отсутствии полей ничего не показывается (жёсткой зависимости от среза S2 автошага нет). Тест с результатом без этих полей и с ними.
- [ ] **Закрывающая проверка и коммиты:** по одному коммиту на задачу (`feat(ui): add a manual step to deep analysis`, `feat(ui): pin series in deep analysis`, `feat(ui): show window aggregates in deep analysis`), документация и `CHANGELOG.md` в последнем, прогон команд задачи 6 D2-min.

---

## Срез D3a. Полоса стадий и переход в окно

**Ветка:** `feat/ui-deep-stages`. **Размер:** M. **Зависит от:** D2-min. **Не входит:** объявление стадий для онлайн-снимка (D3c), `window_ids` (S5 ADR 0018), автоопределение стадий (ADR 0009 и 0018 его отвергли).

**Что такое стадия в МВП.** Именованное окно, по которому ядро считает вердикт: `window_policy_summary` результата (идентификатор, границы, вердикт) либо `resource_summary` (`window_id`, `from_epoch_ms`, `to_epoch_ms`), если сводок окон нет. Окна из файла снимка с `windows` показываются по именам; неявное окно `run-intersection` и `full` показываются как «весь прогон» без полосы разметки.

- [ ] **Задача 1. Модель стадий (`ui/src/shell/stages.ts`, TDD).** Тесты `deep-adapters.spec.ts`: `stages come from window policy summaries sorted by start with their verdicts`; `a single run-intersection or full window yields no stage strip`; `resource summaries are the fallback when no window summary exists`; `capacity stage windows are shown as windows too` (ступени capacity приходят как окна `window_policy_summary`; тест на результате `capacity_step`). Интерфейс: `export interface Stage { id: string; fromMs: number; toMs: number; verdict: string | null }`, `export function stagesOf(result: AnalysisResult): Stage[]`, `export function stageFraction(stage: Stage, axis: TimeAxis): { left: number; width: number }`.
- [ ] **Задача 2. Полоса и переход.** Тесты `deep.spec.ts`: `the stage strip lists stages as buttons aligned to the time axis and the focused stage is announced`; `clicking a stage sets the period to the stage window snapped to cells`; `the period reset returns to the whole run`; `stages outside the loaded grid are shown disabled` (окно шире сетки снимка). Реализация: `DeepStageStrip.vue` (список кнопок `role="group"`, ширина по `stageFraction`, подпись «идентификатор · вердикт»), подключение в `DeepAnalysisPanel.vue` над графиком; в `DeepCursorChart.vue` необязательный проп `stages` для заливки фона выбранной стадии. Строки в `DEEP_LABELS`.
- [ ] **Задача 3. Документация, проверка, коммиты.** `slice-1-local-analysis.md`: окна снимка как стадии, как задать (`windows` в файле `resource-snapshot.v1`), чего нет (онлайн-сбор не объявляет стадий). `CHANGELOG.md`. Команды задачи 6 D2-min.

**Риск:** без файла снимка с `windows` полоса пуста; для онлайн-сбора она остаётся пустой до D3c. Это честно сообщается в панели («окна не объявлены в снимке»).

---

## Срез D3b. Правила выбранного окна

**Ветка:** `feat/ui-deep-rule-window`. **Размер:** S. **Зависит от:** D3a, срезы S1 и S5 ADR 0018 (S5 добавляет `window_ids`, S1 `verdict_gates`; обе в очереди identity ADR 0018, но D3b их не меняет).

- [ ] **Step 1: Проверить по факту S5.** Прочитать влитый S5: какие поля evidence несут `window_id` для бизнес-правил (`policy_check`, `rule_window_check`). Если бизнес-правила по окнам в evidence не выдаются, D3b показывает только ресурсные правила (`resource_policy_check` имеет `window_id` уже сейчас), остальное report-only.
- [ ] **Step 2: Красные тесты** `deep-adapters.spec.ts`: `rules of the selected window list resource checks with status, threshold and reason`; `a rule bound by window ids appears only in its windows`; `an unknown window id shows RULE_WINDOW_NOT_FOUND` (после S5). `deep.spec.ts`: `selecting a stage filters the rules list and the focused rule row opens its series in the chart`.
- [ ] **Step 3: Реализация.** `rulesOfWindow(result, windowId)` в `stages.ts`, блок «Правила окна» в панели (таблица, `tabindex="0"` обёртка, статус словами через `verdictReasons.ts`), кнопка «Открыть ряд» выбирает ряд правила (в рамках лимита 6). Коммит `feat(ui): list the rules of the selected stage in deep analysis`.

---

## Срез D3c-ADR. Объявление стадий при онлайн-сборе (текст ADR, реализация вне МВП)

**Нужен ли ADR:** да, только для онлайн-пути. Объявление стадий в запросе источника меняет публичный контракт (`source-request`), хэш снимка (окна входят в `semanticSha256`) и `source_summary`; это изменение identity, поэтому реализация ставится в общую очередь identity-срезов после ADR 0018 S1 и автошага S2, а ADR пишется отдельным docs-PR после ответа владельца (вопрос 6). Номера 0022 и 0023 заняты черновиками (ИИ, корреляции), номер присваивается при написании.

**Рекомендованное решение для ADR (Proposed):** в `source-request` (новая версия) явное окно получает необязательный массив `stages` (`id` 1..128 байт, `from_epoch_ms`, `to_epoch_ms`, до 64, внутри явного окна, не пересекаются, кратны шагу); при авто-окне стадии задаются смещением от распознанного начала (`from_offset_ms`). Сборщик копирует их в `windows` снимка вместо `full`. Альтернативы для ADR: стадии в файле протокола рядом с политикой (отвергнуто в ADR 0018 как второй механизм умолчаний); стадии только в UI поверх готового снимка (меняет вердикт без пересчёта, нельзя).

---

## Вопросы владельцу

У каждого вопроса есть рекомендация по умолчанию; без ответа срез идёт по рекомендации.

1. **Состав демо.** D2-min без закрепления, ручного шага и стадий достаточно для демо? Рекомендация: да; закрепление и стадии дают удобство, но не меняют честность картины.
2. **Лимиты API.** Принять страницу 100 000 ячеек, каталог 256 рядов, 32 ряда на запрос и 2 000 ячеек по умолчанию как рабочие значения до замера D0b? Рекомендация: да, перевести в принятые после D0b, при провале критериев уменьшить страницу, а не вводить артефакт-колонки.
3. **Пропуски при укрупнении.** Редуктор по наблюдённым исходным ячейкам с пометкой неполноты (вариант плана) или `null`, если пропущена хотя бы одна? Рекомендация: по наблюдённым с пометкой «наблюдено k из n» и отдельным стилем неполной ячейки: так график не рвётся от единичного пропуска, а потеря видна. Значение для порогов не используется (пороги считает ядро).
4. **Редуктор среднего при укрупнении.** Только `mean` для `interval_mean`/`interval_rate` без полосы min/max? Рекомендация: да в МВП; полоса по подам относится к P2.
5. **Шаг нагрузки.** Нагрузка на графике только с шагами `/buckets` (1, 10, 30, 60 с), без произвольного шага и без усреднения p95? Рекомендация: да; произвольный шаг требует нового эндпойнта и контракта.
6. **Стадии при онлайн-сборе.** Писать ADR об объявлении стадий в запросе источника и реализовать после МВП, а в МВП стадии только из `windows` файла снимка? Рекомендация: да, отложить; для демо и приёмки на реальном стенде стадии задаются файлом снимка, онлайн-сбор остаётся с одним окном.
7. **Порядок вкладок и конфликт с U3-U7.** Вкладка `deep` второй после «Обзор»; планы U3-U7 тоже правят `labels.ts` и `App.vue`. Кто вливается первым? Рекомендация: первым U3 (таблицы, нужны для перехода «Открыть ряд»), D2-min сразу после; оркестратор ведёт общий порядок, правки `SHELL_TABS` через rebase.
8. **Число одновременно показанных рядов.** 6 (график читаем, запрос укладывается в один пакет). Рекомендация: 6, константа `MAX_SELECTED_SERIES`.
9. **Расшифровка снимка.** Кэш на один снимок и одна расшифровка за раз (без производного артефакта). Рекомендация: да; артефакт-колонки только по провалу замера D0b.
10. **`E_max` = 20 480.** Остаётся Proposed; D0b замеряет открытие результата, но не меняет значение. Рекомендация: пересмотреть отдельно после замера отрисовки таблиц (срез U3).

## Проверка

Команды (PowerShell, из корня воркстри среза; Windows: перед e2e `Remove-Item Env:NoDefaultCurrentDirectoryInExePath`):

| Что | Команда | Ожидание |
| --- | --- | --- |
| Ядро и API | `.\gradlew.bat --no-daemon check` | PASS (включая ktlint, `ResourceSeriesViewTest`, `ResourceSeriesApiTest`, `LocalApiTest`, `FixtureManifestTest`) |
| Оракул | `python -m unittest tools.test_resource_series_oracle -v` и `python -m unittest discover -s tools -p "test_*.py" -v` | PASS; векторы в `fixtures/resource-series/` равны пересозданным |
| Контракты | `npm --prefix ui run test:contracts` | PASS (схема, 2 валидных и 2 невалидных примера) |
| UI | `npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run build` | PASS |
| e2e | `npm --prefix ui run e2e` | PASS (`deep-adapters`, `deep`, `deep-live`, регрессия `shell`, `overview*`, `security-a11y`) |
| Документы | `npx --yes markdownlint-cli2@0.23.2 "**/*.md" "#.worktrees/**"` и проверка ссылок скриптом | PASS |
| Идентичность | `git diff --name-only origin/main` не содержит `fixtures/slice1/identity/` и `fixtures/slice1/manifest.json` | пусто |
| Дифф | `git diff --check`; поиск не-ASCII в `ui/src` вне `labels.ts` | чисто |

**Оракулы и их порядок.** (1) Python: числа редукторов и страниц (`tools/resource_series_oracle.py`) против общих векторов; (2) Kotlin API: те же векторы через HTTP, сравнение разобранных double точным `==`, а не текста JSON; (3) Playwright live: значения на курсоре равны ожидаемым оракула на реальном сервере. Любое расхождение останавливает срез: правится ядро или оракул только по первоисточнику (десятичные значения снимка), оба вместе не подгоняются под результат.

**Ворота фазы 4** (из плана внедрения): замер 600 рядов по 1 920 точек (время, память, размер результата, отрисовка) закрывается D0b; «результаты D2 совпадают с оракулом» закрывается задачей 5 D2-min и повтором в D2-full с укрупнённым шагом (добавить случай шага 60 с в `deep-live.spec.ts` при ручном шаге).

**Критерии приёмки по срезам (наблюдаемые).** D0a: ответы по схеме, все случаи `cases.json` совпадают, 400/404/413 как в решениях; D2-min: вкладка открывает анализ со снимком из файла, онлайн-снимок с квалифицированными идентификаторами, capacity и анализ без снимка; курсор выдаёт значения всех дорожек; D3a: клик по стадии сужает период ровно до окна на границах ячеек.

## Риски и зависимость от реального стенда

Приёмка на реальном стенде в МВП не входит (локальный стенд не в счёт). Что на реальный стенд опирается и остаётся непроверенным:

- Реальные Prometheus-ряды: число рядов до 1 024, длинные квалифицированные идентификаторы (запрос `values` может упереться в длину строки запроса Netty; тест 6 задачи 5 D0a фиксирует предел, клиент режет по 3 500 символов), метки, пропуски разной формы.
- Шаги после автошага (нецелые для глаза, например 17 или 20 с): проверены синтетикой, не реальным источником.
- Расхождение часов генератора и кластера: ядро его не проверяет, на графике сдвиг нагрузки и ресурсов неотличим от причинности; панель только предупреждает.
- Куча: замер D0b на синтетике; поведение рядом с параллельной задачей на машине владельца зависит от `JAVA_OPTS`.
- Размер страницы `/buckets` с HDR (`hdr_v2_base64` в каждой корзине) на реальных гистограммах; при превышении критерия D0b вопрос о облегчённом ответе нагрузки открывается отдельно.
- Плечи и поды (P1, P2): в срезы не входят; ряды с меткой `arm` отображаются как обычные.

## Self-review плана

- **Покрытие задачи.** D0 (контракт, лимиты по ADR 0014, автошаг: `step_ms` кратный любому целому шагу, `interval_max`/`interval_min`, заметка `source_summary`), D2 (выбор ряда, шаг, период, закрепление, общий курсор на основе `SharedCursorChart.vue` и `overview.ts`), D3 (стадии, полоса, окно правила; ADR-B найден: ADR 0018, нужен ли ещё ADR: только для онлайн-объявления). D2-min выделен отдельно; оракул Python выделен отдельным срезом работ внутри D0a и закрыт в D2-min (живая сверка).
- **Плейсхолдеры.** Нет `TBD`; числа-критерии D0b помечены как предложение и вынесены в вопросы владельцу.
- **Согласованность имён.** `SeriesReducer`, `coarsen`, `planValuesPage`, `ValuesPlan`, `SnapshotCache`, `deep.ts` (`snapPeriod`, `chooseResourceStep`, `chooseLoadRollup`, `batchSeriesIds`, `cellAt`, `cursorReadouts`), `stages.ts` (`stagesOf`, `rulesOfWindow`) одинаковы во всех задачах.
- **Review Focus.** Каждая из шести строк привязана к задаче-владельцу.

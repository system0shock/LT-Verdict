# W2.3: baseline в CLI и отчёте (план реализации)

Строка W2.3 перечня [2026-10-08-review-work-plan.md](2026-10-08-review-work-plan.md): «`--baseline <analysis-result.json>`; раздел «Изменения
относительно baseline» в HTML, AsciiDoc и Confluence; эталон без политики». Критерий: «CI-прогон с baseline выдаёт дельты в отчёте; при
`stage_binding` (ADR 0030, R7) дельты считаются по `window_metric_summary`, а не по метрике «весь прогон»». Основание: usability B1,
`BaselineComparison.kt:99`. Связанные ADR: [0017](../../adr/0017-baseline-candidates-and-confirmation.md),
[0018](../../adr/0018-policy-platform-rules-small-samples.md), [0028](../../adr/0028-baseline-conditions-confirmation.md),
[0030](../../adr/0030-load-stages-steady-window.md) (R6 ключ сопоставимости, R7 обязательство W2.3). Обязательная формулировка W2.4: статус
`CANDIDATE` показывается как «материальная дельта, значимость не оценена» (только показ; wire и контракты сравнения не меняются).

Класс задачи (brainstorming): bounded по коду (новый флаг и раздел рядом с готовым образцом `error-groups`), но публичный контракт CLI
(флаг, раздел, ключ сводки) фиксируется здесь до кода. Вопросы владельцу не задаются, спорное решено ruling'ами ниже.

## Блок для AGENTS.md

```text
REQUESTED: (1) флаг `--baseline <analysis-result.json>` у `ltv analyze` (с `--out-dir`), `ltv report` (html, asciidoc, confluence, summary)
  и `ltv summary`; (2) раздел «Изменения относительно baseline» в report.html, AsciiDoc и Confluence, блок в summary.txt и необязательный
  ключ `baseline_comparison` в `cli-summary.v1`; (3) при `stage_binding` дельты по `window_metric_summary` окна steady; (4) эталон без
  политики допустим; (5) CANDIDATE показывается как «материальная дельта, значимость не оценена».
REQUIRED TO ACHIEVE IT:
  - cli/CliBaseline.kt (новый, небольшой): разбор пути `analysis-result.json` в `<data-dir>/runs/<run>/analyses/<analysis>/`, чтение
    эталона из хранилища (`readVerifiedAnalysis`), сборка JSON `baseline_comparison` поверх готового `compareAnalyses`;
  - CommandLine.kt: разбор `--baseline` в analyze и report, вызов сборки, передача JSON в рендереры и сводки, текст usage;
  - report/BaselineChangesView.kt (новый): слова и строки раздела из JSON `baseline_comparison` (один читатель для HTML, AsciiDoc,
    Confluence, summary.txt; образец `ErrorGroupsView.kt`);
  - HtmlReport.kt, AsciiDocReport.kt, ConfluenceReport.kt: необязательный параметр `baseline` и одна функция раздела в каждом;
  - CliArtifacts.kt: необязательный параметр `baseline` у summaryJson и summaryText;
  - тесты (красные до кода), docs/user/slice-1-local-analysis.md, changelog.d/w2-3-cli-baseline.added.md.
NOT REQUIRED:
  - подтверждение условий в CLI (флаг `--confirm-conditions`, чтение `baseline-conditions/`): ruling B3;
  - запись baseline-слота, выбор baseline по серии, статистический набор кандидатов, регистр релизов и сравнение профилей (`releases = null`);
  - `--baseline-window/--current-window`, пороги материальности флагами (берутся значения по умолчанию ядра 5 % и 0,001);
  - baseline вне `--data-dir` (экспортный `result.json` без identity): ruling B1;
  - изменение `junit.xml`, кода выхода, stdout `analyze`, `analysis-result.json`, identity, `analysis_id`, wire сравнения и API/UI;
  - Markdown-отчёт, ADR (контракт описан здесь и в docs/user: ruling B7); рефакторинг HtmlReport.kt (W2.6 PR 2/3 правит его после).
EXPECTED FILES TO CHANGE:
  новые: src/main/kotlin/io/ltverdict/cli/CliBaseline.kt, src/main/kotlin/io/ltverdict/report/BaselineChangesView.kt,
    src/test/kotlin/io/ltverdict/cli/CommandLineBaselineTest.kt, src/test/kotlin/io/ltverdict/report/BaselineChangesReportTest.kt,
    changelog.d/w2-3-cli-baseline.added.md, этот план;
  правки: cli/CommandLine.kt, cli/CliArtifacts.kt, report/HtmlReport.kt, report/AsciiDocReport.kt,
    integrations/report/ConfluenceReport.kt, docs/user/slice-1-local-analysis.md.
```

## Ruling'и

**B1. Что значит `--baseline <analysis-result.json>`: путь к сохранённому файлу анализа в `--data-dir`.** Вариант (1) брифа. Файл должен
лежать ровно в `<data-dir>/runs/<run_id>/analyses/<analysis_id>/analysis-result.json`; `run_id` и `analysis_id` берутся из сегментов пути
(после `toRealPath` обоих путей, без ссылок), затем эталон читается из хранилища через `RunBundleStore.readVerifiedAnalysis(run, analysis)`
(манифест, SHA-256, предел 64 МиБ), как в API. Причины: сравнение ядра требует `identity.json` (ключ сопоставимости: тип источника, движок,
парсеры, модули, версии входов, лимиты, плечо, объявление стадий), а один `analysis-result.json` или экспортный `result.json` её не
содержит; произвольный внешний файл прочитать честно нельзя. Путь в `--data-dir` это естественный CI-сценарий: эталон лежит в том же
каталоге данных (`analyze` печатает `analysis_id` и `run_id` в stderr, путь собирается из них). Цена ошибки: эталон с другой машины нужно
сначала положить в каталог данных (переноса bundle в CLI нет); это явная граница и тема для MCP/переноса bundle. Файл вне каталога данных,
не по этому шаблону, не файл, ссылка, неизвестный анализ: `BASELINE_NOT_FOUND`, выход 4, до чтения входа и до записи в каталог данных.

**B2. Выход 4 и никаких изменений вердикта.** Ошибки эталона (`BASELINE_NOT_FOUND`, `BASELINE_TOO_LARGE`, `BASELINE_CORRUPT`) дают
`EXIT_INVALID_INPUT` (4) до анализа: CI, который просил сравнение, не получает молча анализ без него. Несопоставимость
(`INCOMPATIBLE_METRIC_DEFINITION`, окно не найдено, разное объявление стадий) это не ошибка: код выхода `analyze` определяется вердиктом
как прежде (`PASS/NO_POLICY` 0, `FAIL` 2, `NO_VERDICT` 3, `INVALID` 4), раздел говорит причину словами. ADR 0017/0018: сравнение вердикт
не меняет. `--baseline` у `analyze` без `--out-dir` отклоняется (`BASELINE_OUT_DIR_REQUIRED`, выход 4) до чтения входа: иначе сравнение
нигде не показывалось бы. `--baseline` у `ltv report/summary` допустим для форматов html, asciidoc, confluence, summary; с `json` и `svg`
это usage (выход 64): байты сохранённого результата и график от эталона не зависят.

**B3. Подтверждения условий в CLI нет, сравнение всегда `UNCONFIRMED`.** ADR 0017/0028: `CANDIDATE` возможен только после явного
локального подтверждения «условия сопоставимы». CLI вызывает `compareAnalyses(..., conditionsConfirmed = null)`, поэтому материальная
дельта получает статус `DESCRIPTIVE` с причиной `CONDITIONS_UNCONFIRMED`, а `CANDIDATE` в CLI не возникает вообще. Раздел говорит это
вслух: «Условия сопоставимости не подтверждены: дельты описательные, значимость не оценена, вердикт они не меняют». Почему безопасно: CLI не
выдаёт подтверждение за факт, не читает `baseline-conditions/` (запись UI-подтверждения для другой пары и окон) и ничего не пишет в
каталог данных; ключ сопоставимости ядра (`semanticKey`) остаётся единственной автоматической проверкой и отсекает разные условия обработки.
Слова для `CANDIDATE` (на случай будущего подтверждения через тот же показ) в представлении есть: «материальная дельта, значимость не
оценена» (как `labels.compare.ts`). Цена ошибки: CI-пользователь не получит «кандидата на регрессию» без подтверждения; это намеренно.

**B4. Область дельт: весь прогон без стадий, окна `steady` со стадиями.** Если у эталона или у текущего анализа есть `stage_binding`
(R7), раздел НЕ использует `metric_summary` «весь прогон»: для каждого id из `stage_binding.evaluated_window_ids` (у текущего, иначе у
эталона) вызывается `compareAnalyses` с `WindowComparisonRequest(id, id)` и берётся готовый `window_comparison` (p50, p95, p99,
пропускная способность, доля ошибок по `window_metric_summary`). Одинаковое объявление стадий у обеих сторон гарантирует ключ
сопоставимости (`load_stages_sha256`, R6), а id окна одного объявления совпадают. Если сторона без стадий, объявления разные, окна нет:
ядро отдаёт `NOT_EVALUATED` с причиной (`INCOMPATIBLE_METRIC_DEFINITION`, `BASELINE_WINDOW_NOT_FOUND`, `CURRENT_WINDOW_NOT_FOUND`), раздел
пишет её словами и таблицы не показывает. Предупреждение ядра `WHOLE_RUN_METRICS_WITH_STAGES` в этом разделе не выводится (оно о
метриках «весь прогон», которые мы не используем); метрики «весь прогон» со стадиями в раздел не попадают совсем. Без стадий: четыре
метрики `metrics[]` ядра (p95, p99, пропускная способность, доля ошибок), без статусов материальности (их в ядре нет, считаются только
в `window_comparison`). Число сэмплов и длительность окна обеих сторон выводятся в абзаце окна: одно объявление стадий может дать разную обрезку окна по концу
прогона (ADR 0030, `clipped_to_run_end`), а `EMPTY_WINDOW` ядро ставит только при нуле сэмплов (совет Astra). Арифметика ядра не менялась.

**B4a. Идентичность текущего анализа.** `compareAnalyses` требует `identity.json` обеих сторон. `analyze` читает пару
`(result, identity)` текущего анализа через `RunBundleStore.readAnalysisDocuments(run_id, analysis_id)` внутри открытого каталога данных
после публикации анализа; `report`/`summary` читают её так же, но только при заданном `--baseline` (без флага чтение прежнее). Значений
по умолчанию не подставляется: нет документа, это ошибка `BASELINE_CORRUPT` (выход 4).

**B5. Эталон без политики допустим.** Для ручного baseline ядро не требует `PASS` и политики (допуск `PASS` только у статистического
набора кандидатов). Эталон с `NO_POLICY`, `FAIL` и т. д. сравнивается; раздел показывает предупреждения ядра словами:
`BASELINE_NOT_PASS` («вердикт baseline не PASS, в том числе когда политика не задана»), `POLICY_DIFFERS` («политики различаются»),
`BASELINE_SMALL_SAMPLE`, `BASELINE_IS_CURRENT_ANALYSIS`, `BASELINE_IS_CURRENT_RUN`. Неизвестный код предупреждения печатается как есть
(экранированно). Цена ошибки: предупреждение `BASELINE_NOT_PASS` для эталона без политики шумное, но честное.

**B6. Недоверенные тексты.** Все строки раздела проходят экранирование выхода: HTML `escape`, Confluence `xml()`, AsciiDoc литеральный блок
`[subs=specialchars]` с префиксом на каждой строке (ни одна строка не равна `----`), summary.txt без разметки. Идентификаторы окон из
результата очищаются от управляющих символов в представлении и ограничены по длине. Ссылки на baseline (`run_id`, `analysis_id`)
проверены регулярными выражениями `Reference`.

**B7. Публичный контракт без ADR.** Новых схем, хранения и зависимостей нет, `analysis-result.v1` и wire сравнения API не меняются.
`cli-summary.v1` получает необязательный ключ (как `windows` в ADR 0030 и `error_groups` в W2.6): без `--baseline` байты сводки прежние.
Цена ошибки: потребитель со строгой схемой сводки увидит новый ключ только при явном `--baseline`. ADR не нужен: решение не меняет
границы доверия, формат хранения и поведение ядра; контракт записан здесь и в docs/user. Если оркестратор решит иначе: ADR 0032 (последний
на `origin/main` 0031).

## Публичные контракты (запись до кода)

### CLI

```text
ltv analyze <input> ... --out-dir <dir> --baseline <data-dir>/runs/<run_id>/analyses/<analysis_id>/analysis-result.json
ltv report <run-id> <analysis-id> --format html|asciidoc|confluence|summary [--baseline <path>] [--data-dir <path>]
ltv summary <run-id> <analysis-id> [--baseline <path>] [--data-dir <path>]
```

`--baseline` не повторяется (повтор, флаг без значения, `analyze` без `--out-dir`: usage 64 или `BASELINE_OUT_DIR_REQUIRED` 4 по B2).
Коды выхода `analyze` не меняются; только ошибки эталона дают 4 (B2). stdout `analyze` и `result.json` без изменений; `junit.xml` не
меняется (сравнение не гейт, ADR 0017/0018); `chart.svg` не меняется. `report.html` и `summary.txt` получают раздел/блок, только если задан
`--baseline`; без флага все артефакты побайтово прежние.

### JSON `baseline_comparison` (внутренний вход рендереров и необязательный ключ `cli-summary.v1`)

```text
{ "baseline": {"run_id": "...", "analysis_id": "..."},
  "comparability": "UNCONFIRMED",
  "scope": "whole_run" | "steady_window",
  "warnings": ["BASELINE_NOT_PASS", ...],        // коды ядра, без WHOLE_RUN_METRICS_WITH_STAGES при scope=steady_window
  "metrics": [ {"metric","unit","baseline","current","delta","delta_percent","reason","percent_reason"} ],  // scope=whole_run
  "windows": [ {"window_id","status","reasons":[...],"min_change_percent","min_error_rate_delta",
                "baseline_sample_count","current_sample_count","baseline_duration_ms","current_duration_ms",
                "metrics":[ {"metric","unit","baseline","current","delta","delta_percent","status","reason","percent_reason"} ]} ]  }
                // scope=steady_window; status of a window: CANDIDATE|DESCRIPTIVE|INSUFFICIENT_DATA|NO_MATERIAL_CHANGE|NOT_EVALUATED
```

Значения чисел и `status`, `reason`, `percent_reason` дословно из `compareAnalyses` (строки десятичных, `null`). Ключ `metrics` есть только у `whole_run`,
`windows` только у `steady_window`.

### Раздел отчётов «Изменения относительно baseline»

Заголовок `Изменения относительно baseline` в HTML (`<h2>` в `<section>`), AsciiDoc (заголовок уровня 2) и Confluence (`<h2>`). Состав: абзацы (baseline:
run и analysis; «Условия сопоставимости не подтверждены…»; область дельт: «весь прогон» или «окно steady `<id>` по
window_metric_summary»; предупреждения; причина несопоставимости; пороги материальности) и таблица(ы) «Показатель, Baseline, Текущий,
Дельта, Дельта %, Статус». Слова статусов: `DESCRIPTIVE` «описательно», `NO_MATERIAL_CHANGE` «без заметных изменений», `CANDIDATE`
«материальная дельта, значимость не оценена», `INSUFFICIENT_DATA` «недостаточно данных»; причины `CONDITIONS_UNCONFIRMED` «условия не
подтверждены», `ZERO_BASELINE` «baseline равен нулю». Порядок раздела в HTML: сразу после блока диагностики ресурсов (одна вставка).

### summary.txt

При `--baseline` добавляются строки после блока правил: `baseline: run_id=<id> analysis_id=<id> comparability=UNCONFIRMED scope=<scope>`
и по строке на показатель `<metric>: <baseline> -> <current>, delta <delta> (<percent>) <status-слова>` с отступом в два пробела; для несопоставимости строка
`baseline: not comparable: <причина>`.

## Критерии приёмки

1. Прогон с `--baseline` на сохранённом анализе без стадий: `report.html`, `summary.txt` содержат раздел/блок с дельтами p95, p99, rps,
   доли ошибок; стадии: дельты по окну `steady` (значения из `window_metric_summary`, не `metric_summary` «весь прогон»; тест на фикстуре
   `fixtures/stages` с разной задержкой на плато и в целом).
2. Без `--baseline`: `result.json`, `report.html`, `summary.txt`, `junit.xml`, `cli-summary.v1`, AsciiDoc, Confluence побайтово прежние
   (существующие golden-тесты не правятся).
3. Код выхода `analyze` с `--baseline` равен коду без него (PASS 0, FAIL 2) и при несопоставимых эталонах; ошибки эталона дают 4 до записи
   в каталог данных.
4. Статусы строк в CLI только `DESCRIPTIVE/NO_MATERIAL_CHANGE/INSUFFICIENT_DATA`; слова `CANDIDATE` покрыты тестом представления.
   Состояние окна `NOT_EVALUATED` (текущий со стадиями, эталон без них и наоборот; разные объявления; нет окна) покрыто тестами: причина
   словами, таблицы нет, дельты «весь прогон» не подставляются.
5. Эталон без политики сравнивается; слова предупреждений присутствуют.
6. Тексты экранированы во всех трёх форматах и в summary: валидный, но враждебный id стадии (`<script>`, `----`, `&`; проверка стадий
   отвергает только пустые, управляющие и длинные id, `LoadStages.kt:335`) проходит через все рендереры (совет Astra).
7. Выход `analyze` с `--baseline` равен выходу без него в случаях `FAIL` (2), `NO_VERDICT` (3) и `INVALID` (4); без `--baseline` отчёты и
   сводка прогона со стадиями не содержат слова «baseline».

## Проверка

- Красный прогон: `Invoke-LtvSlot { gradlew test --tests '*BaselineChanges*' --tests '*CommandLineBaseline*' }` до реализации.
- Зелёный и полный: по разделу «Без CI» общего брифа (`cleanTest check installDist`, `npm` typecheck, lint, test:contracts, оффлайн
  Playwright (отчёты не UI, но запускается, если затронуты), `verify_slice0`, `changelog_assemble --check`, markdownlint).
- `Documentation impact`: docs/user/slice-1-local-analysis.md (флаг, раздел, ключ сводки, границы), changelog-фрагмент.

## Если объём вырастет (правило 10)

Первый PR: флаг, сборка `baseline_comparison`, HTML и `summary.txt`/`cli-summary.v1`; AsciiDoc и Confluence вторым. Оценка плана: около 250 строк
production, 2 новых файла, 5 правок одной вставкой. Факт: около 520 строк production (два новых файла 195 и 209 строк после
форматирования ktlint, остальное правки в пять файлов). Причины: разбор путей и чтение эталона с проверкой хэшей, построение JSON поверх
`compareAnalyses`, представление с русскими словами для четырёх потребителей. На AsciiDoc и Confluence приходится около 30 строк,
поэтому раздел на два PR не делится: основная часть нужна и HTML.

## Совет Codex Astra (read-only) и что учтено

По плану (до кода): учтены `percent_reason` в JSON, длительности и число сэмплов окон (поправка: `EMPTY_WINDOW` только при нуле сэмплов),
состояние `NOT_EVALUATED` в приёмке и тестах, явная загрузка identity текущего анализа (B4a), враждебный, но валидный id стадии в тестах
всех рендереров. Не принято: «`ltv summary --baseline` выходит за W2.3» (оркестратор прямо называет поля `cli-summary.v1` в контракте;
при росте объёма откладывается по правилу 10).

По диффу: (1) текущий анализ читается без предела 64 МиБ через `readAnalysisDocuments` (предел относится только к эталону); (2) ссылка
в любой части пути к эталону (`runs`, `<run>`, `analyses`, `<analysis>`, файл) отклоняется (`BASELINE_NOT_FOUND`); тест создаёт ссылку
и пропускается, если ОС её не даёт; (3) несопоставимость определяется по любой строке `INCOMPATIBLE_METRIC_DEFINITION`, потому что ядро
отдаёт `MISSING_METRIC` раньше; (4) тесты кодов выхода 3 и 4 при `--baseline`; побайтовое равенство без флага проверено вручную
сравнением сборки `origin/main` и этой ветки на пяти входах (со стадиями и без, PASS, FAIL, NO_POLICY, Gatling) по пяти файлам
`--out-dir`, шести форматам `ltv report` и `ltv summary`, коды выхода совпали.

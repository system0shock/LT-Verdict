# ADR 0032: Baseline в CLI (`--baseline`, раздел «Изменения относительно baseline»)

Дата: 2026-10-09.

Статус: Proposed (в Accepted переводится только по слову владельца). Реализовано: PR #237
(работа W2.3 перечня ревью от 2026-10-08). ADR записывает решения, уже принятые в плане и коде
W2.3, и перечисляет публичные контракты, которые они меняют. Номер 0032 выбран как следующий
после 0031; перед слиянием его нужно перепроверить по `origin/main`.

## Контекст

Сравнение двух анализов существует в ядре (`BaselineComparison.kt`, `compareAnalyses`) и в UI
([ADR 0004](0004-local-baseline-selection.md), [ADR 0017](0017-baseline-candidates-and-confirmation.md),
[ADR 0019](0019-release-history-and-baseline-eligibility.md),
[ADR 0028](0028-baseline-conditions-confirmation.md)). CLI его не показывал: CI-прогон `ltv analyze`
выдавал вердикт и артефакты, но не отвечал на вопрос «что изменилось относительно эталона».
Строка W2.3 [перечня работ](../superpowers/plans/2026-10-08-review-work-plan.md) требует флаг
`--baseline <analysis-result.json>`, раздел «Изменения относительно baseline» в HTML, AsciiDoc и
Confluence и эталон без политики; при `stage_binding` дельты должны считаться по
`window_metric_summary`, а не по метрике «весь прогон»
([ADR 0030](0030-load-stages-steady-window.md), R7).

Ограничения решения:

1. Сравнение не меняет вердикт и код выхода ([ADR 0017](0017-baseline-candidates-and-confirmation.md),
   [ADR 0018](0018-policy-platform-rules-small-samples.md)).
2. Статус `CANDIDATE` возможен только после явного подтверждения условий сопоставимости
   ([ADR 0028](0028-baseline-conditions-confirmation.md)); в CLI подтверждения нет.
3. Ключ сопоставимости ядра (`semanticKey`: режим анализа, тип источника, движок, парсеры, версии
   входов, лимиты, плечо, объявление стадий, [ADR 0030](0030-load-stages-steady-window.md), R6)
   состоит из `analysis-result.json` и `identity.json`; одного результата для сравнения мало.
4. Без `--baseline` все артефакты и вывод CLI побайтово прежние.
5. Схемы, хранение, зависимости production и арифметика ядра не меняются.

Рабочий план, ruling'и B1-B7 и совет Codex Astra:
[2026-10-09-w2-3-cli-baseline.md](../superpowers/plans/2026-10-09-w2-3-cli-baseline.md).

## Решение

### D1. Эталон это сохранённый анализ внутри того же `--data-dir`

`--baseline` принимает путь ровно к файлу
`<data-dir>/runs/<run_id>/analyses/<analysis_id>/analysis-result.json`; `run_id` и `analysis_id`
берутся из сегментов пути. `ltv analyze` печатает оба значения в stderr, поэтому CI собирает путь
без поиска. Эталон читается из хранилища через `RunBundleStore.readVerifiedAnalysis`
(манифест, SHA-256 файлов, предел результата 64 MiB), как это делает API; `identity.json`
читается из того же каталога анализа.

Почему так: ключ сопоставимости требует identity рядом с результатом. Экспортный `result.json` из
`--out-dir` или файл с другой машины identity не содержит, и честно сравнить его нельзя. Цена
ошибки: эталон с другой машины сначала нужно положить в каталог данных; переноса bundle в CLI нет,
это явная граница.

Проверка пути (`baselineLocation`) смотрит только на пути и ничего не создаёт. Путь отклоняется
кодом `BASELINE_NOT_FOUND`, если выполнено любое из условий:

- имя файла не `analysis-result.json`, либо каталоги выше каталога анализа называются не
  `analyses` и `runs`;
- корень над `runs` не совпадает с `--data-dir` (`Files.isSameFile`; сам каталог данных может быть
  ссылкой);
- ссылкой является сам файл, каталог анализа, `analyses`, каталог прогона или `runs`;
- файл не обычный (проверка без следования ссылкам), не существует или путь не разбирается.

Путь не приводится к реальному (`toRealPath`): ссылка в любой части пути отклоняется, а не
раскрывается.

### D2. Подтверждения условий в CLI нет, сравнение всегда `UNCONFIRMED`

CLI вызывает `compareAnalyses(..., conditionsConfirmed = null)`. Поэтому `comparability` всегда
`UNCONFIRMED`, материальная дельта окна получает статус `DESCRIPTIVE` с причиной
`CONDITIONS_UNCONFIRMED`, а `CANDIDATE` в CLI не возникает. CLI не читает каталог
`baseline-conditions/` (запись подтверждения UI относится к другой паре и другим окнам) и ничего не
пишет в каталог данных. Ключ сопоставимости ядра остаётся единственной автоматической проверкой и
отсекает анализы с разными условиями обработки данных. Раздел говорит это вслух: «Условия
сопоставимости не подтверждены: дельты описательные, значимость не оценена; на вердикт они не
влияют.»

Слова для `CANDIDATE` в представлении есть (на случай подтверждения через тот же показ): «материальная
дельта, значимость не оценена». Показ различает `USER_CONFIRMED` и `UNCONFIRMED` по полю
`comparability`, но CLI всегда пишет `UNCONFIRMED`. Цена ошибки: CI-пользователь не получит
«кандидата на регрессию» без подтверждения; это намеренно.

### D3. Сравнение не меняет вердикт и код выхода

Код выхода `ltv analyze` определяется вердиктом, как прежде: `PASS` и `NO_POLICY` дают 0, `FAIL` 2,
`NO_VERDICT` и `DEGRADED` 3, `INVALID` 4. Несопоставимость (`INCOMPATIBLE_METRIC_DEFINITION`, нет
окна, разное объявление стадий) не ошибка: раздел называет причину словами, код выхода не
меняется. `ltv report` и `ltv summary` при успехе дают 0. `junit.xml`, `chart.svg`, `result.json`,
stdout `analyze`, `analysis_id` и вердикт от эталона не зависят.

Ошибки эталона останавливают команду кодом выхода 4, чтобы CI, который просил сравнение, не получил
молча анализ без него (раздел «Коды ошибок» ниже).

### D4. Где разрешён флаг

- `ltv analyze` принимает `--baseline` только вместе с `--out-dir`: сравнение показывается только в
  `report.html` и `summary.txt` из каталога артефактов. Без `--out-dir` команда завершается
  `BASELINE_OUT_DIR_REQUIRED` (выход 4) до чтения входа.
- `ltv report` принимает `--baseline` с форматами `html`, `asciidoc`, `confluence` и `summary`
  (последний это `ltv summary`). С `json` и `svg` это ошибка использования (выход 64): байты
  сохранённого результата и график от эталона не зависят.
- Флаг указывается один раз; повтор и флаг без значения это usage (выход 64).

### D5. Область дельт: весь прогон без стадий, окна `steady` со стадиями

Без `stage_binding` у обеих сторон берутся четыре метрики `metrics[]` ядра: `response_time_p95_ms`,
`response_time_p99_ms`, `throughput_rps`, `error_rate_ratio`. Статусов материальности у них нет (в
ядре их считает только `window_comparison`), поэтому колонка «Статус» говорит «описательно».

Если `stage_binding` есть хотя бы у одной стороны ([ADR 0030](0030-load-stages-steady-window.md),
R7), метрики «весь прогон» в раздел не попадают совсем. Для каждого id из
`stage_binding.evaluated_window_ids` (у текущего анализа, а если там пусто, у эталона) вызывается
`compareAnalyses` с `WindowComparisonRequest(id, id)`, и берётся готовый `window_comparison`: p50,
p95, p99, пропускная способность и доля ошибок по `window_metric_summary`, пороги заметного
изменения по умолчанию ядра (5 % и 0,001 абсолютной разницы доли ошибок), число сэмплов и длительность
окна каждой стороны. Число сэмплов и длительность выводятся потому, что одно объявление стадий
может дать разную обрезку окна по концу прогона (`clipped_to_run_end`), а `EMPTY_WINDOW` ядро
ставит только при нуле сэмплов.

Если одна сторона без стадий, объявления разные или окна нет, ядро отдаёт статус окна
`NOT_EVALUATED` с причиной (`INCOMPATIBLE_METRIC_DEFINITION`, `BASELINE_WINDOW_NOT_FOUND`,
`CURRENT_WINDOW_NOT_FOUND`); раздел пишет её словами, таблицу не показывает и дельты «весь прогон»
не подставляет. Предупреждение ядра `WHOLE_RUN_METRICS_WITH_STAGES` в разделе со стадиями не
выводится (оно о метриках, которые раздел не использует). Арифметика ядра не меняется.

### D6. Эталон без политики допустим

Для ручного эталона ядро не требует `PASS` и политики (допуск `PASS` относится только к набору
кандидатов статистического выбора, [ADR 0019](0019-release-history-and-baseline-eligibility.md)).
Эталон с `NO_POLICY`, `FAIL` и т. д. сравнивается, а раздел показывает предупреждения ядра словами:
`BASELINE_NOT_PASS`, `POLICY_DIFFERS`, `BASELINE_SMALL_SAMPLE`, `BASELINE_IS_CURRENT_ANALYSIS`,
`BASELINE_IS_CURRENT_RUN`. Неизвестный код предупреждения печатается как есть (после очистки и
экранирования). Цена ошибки: `BASELINE_NOT_PASS` для эталона без политики шумное, но честное.

### D7. Недоверенные тексты

Все строки раздела проходят очистку и экранирование. Представление
(`report/BaselineChangesView.kt`) удаляет управляющие символы и символы направления текста и
ограничивает каждое значение 200 знаками; разметку экранирует каждый рендерер: HTML `escape`,
Confluence `xml()`, AsciiDoc литеральный блок `[subs=specialchars]`, в котором каждая строка
начинается с фиксированного слова («Примечание:», «Таблица:») или имени метрики, так что ни одна
строка не равна `----`. `summary.txt` разметки не содержит. Идентификаторы окон приходят из
результата и могут быть валидными, но враждебными (`<script>`, `----`, `&`): проверка стадий
отвергает только пустые, управляющие и слишком длинные id.

### D8. Один читатель для четырёх потребителей

JSON `baseline_comparison` строит `cli/CliBaseline.kt` поверх `compareAnalyses` (значения и статусы
дословно из ядра). Слова и строки раздела делает один читатель `baselineChangesView` для HTML,
AsciiDoc, Confluence и `summary.txt` (образец: `ErrorGroupsView.kt`, [ADR 0031](0031-error-groups-artifact.md)).
Идентичность текущего анализа читается через `readAnalysisDocuments` после публикации анализа (при
`ltv report`/`ltv summary` только если задан `--baseline`; без флага чтение прежнее). Предел 64 MiB
относится только к эталону. Если документов нет, это `BASELINE_CORRUPT`.

### Коды ошибок

| Код | Когда | Выход |
| --- | --- | --- |
| `BASELINE_NOT_FOUND` | путь не по шаблону D1, вне каталога данных, ссылка, не обычный файл, неизвестный анализ, недоступный каталог | 4 |
| `BASELINE_TOO_LARGE` | результат эталона больше 64 MiB | 4 |
| `BASELINE_CORRUPT` | повреждён эталон (`CORRUPT_*` хранилища) или нет сохранённых документов текущего анализа | 4 |
| `BASELINE_OUT_DIR_REQUIRED` | `ltv analyze --baseline` без `--out-dir` | 4 |
| usage | повтор флага, флаг без значения, `--baseline` с форматом `json` или `svg` | 64 |

Порядок в `ltv analyze`: проверка входа и каталога артефактов, `BASELINE_OUT_DIR_REQUIRED`, проверка
пути (`BASELINE_NOT_FOUND`), затем открытие каталога данных и чтение эталона с проверкой хэшей
(`BASELINE_TOO_LARGE`, `BASELINE_CORRUPT`) до приёма входа (`acceptInput`): при ошибке эталона в
каталог данных ничего не записывается и артефакты не создаются. Чтение документов текущего анализа
идёт уже после его сохранения (см. «Следствия»).

## Публичные контракты, которые меняет эта работа

Перечень полный; всё, чего в нём нет, не меняется.

| Контракт | Изменение | Совместимость |
| --- | --- | --- |
| CLI `ltv analyze` | флаг `--baseline <analysis-result.json>`, один раз, только вместе с `--out-dir` | добавочное |
| CLI `ltv report` | флаг `--baseline` для форматов `html`, `asciidoc`, `confluence`, `summary`; с `json`, `svg` usage 64 | добавочное |
| CLI `ltv summary` | флаг `--baseline` (псевдоним `ltv report --format summary`) | добавочное |
| Справка (`usageText()`, `--help`) | строки `analyze`, `report`, `summary` с `--baseline` | добавочное |
| Коды ошибок CLI | `BASELINE_NOT_FOUND`, `BASELINE_TOO_LARGE`, `BASELINE_CORRUPT`, `BASELINE_OUT_DIR_REQUIRED`, выход 4 | новые коды, только при `--baseline` |
| `report.html` (из `--out-dir`, `ltv report --format html`) | раздел `<h2>Изменения относительно baseline</h2>` в `<section>` после блока диагностики ресурсов | только при `--baseline` |
| AsciiDoc и Confluence | раздел «Изменения относительно baseline» (уровень 2 и `<h2>`) после разбивки ошибок | только при `--baseline` |
| `summary.txt` | блок строк `baseline: run_id=... analysis_id=... comparability=UNCONFIRMED scope=...` и строк метрик с отступом в два пробела после блока правил и перед `top errors`; для несопоставимости `baseline: not comparable: ...` либо `baseline: window <id> not comparable: ...` | только при `--baseline` |
| `cli-summary.v1` | необязательный ключ `baseline_comparison` (структура ниже), схема версии не меняется | добавочное, только при `--baseline` |
| Параметры рендереров | необязательный параметр `baseline: JsonObject?` у `renderHtmlReport`, `renderAsciiDocReport`, `renderConfluenceReport`, `summaryJson`, `summaryText` | внутреннее, по умолчанию `null` |

Структура `baseline_comparison`:

```text
{ "baseline": {"run_id", "analysis_id"},
  "comparability": "UNCONFIRMED",
  "scope": "whole_run" | "steady_window",
  "warnings": [коды предупреждений ядра],
  "metrics": [ {"metric","unit","baseline","current","delta","delta_percent","reason","percent_reason"} ],
  "windows": [ {"window_id","status","reasons","min_change_percent","min_error_rate_delta",
                "baseline_sample_count","current_sample_count","baseline_duration_ms","current_duration_ms",
                "metrics": [ {"metric","unit","baseline","current","delta","delta_percent","status","reason","percent_reason"} ]} ] }
```

Ключ `metrics` есть только при `scope = whole_run`, ключ `windows` только при `steady_window`.
Числа это строки десятичных или `null`, `status`, `reason`, `percent_reason` дословно из
`compareAnalyses`. Статус окна: `CANDIDATE`, `DESCRIPTIVE`, `INSUFFICIENT_DATA`,
`NO_MATERIAL_CHANGE`, `NOT_EVALUATED`; в CLI из них возможны все, кроме `CANDIDATE` (D2).

Не меняются: `analysis-result.v1`, `identity.json`, `analysis_id`, канонический JSON и хэши
identity, `run.v1`, ключ сопоставимости, слоты baseline и каталог `baseline-conditions/`, wire
сравнения API и UI, `result.json`, `junit.xml`, `chart.svg`, stdout `ltv analyze`, коды выхода по
вердикту, зависимости production.

## Что не входит

- Подтверждение условий в CLI (флаг вроде `--confirm-conditions`, чтение `baseline-conditions/`).
- Эталон вне каталога данных: экспортный `result.json` и перенос bundle (тема MCP или переноса bundle).
- Выбор эталона по серии, слоты baseline, статистический набор кандидатов.
- Профили релизов и их сравнение (`releases = null`, предупреждение `PROFILE_MISMATCH` не возникает).
- Пороги материальности и окна флагами (`--baseline-window`, `--current-window`): берутся значения по
  умолчанию ядра.
- Отчёт Markdown, `ltv report --format json|svg` с эталоном.
- Изменение `junit.xml`: сравнение не гейт.

## Отклонённые альтернативы

- Принять любой `analysis-result.json` или экспортный `result.json`: нет identity, ключ сопоставимости
  нельзя вычислить; сравнение молча подменило бы проверку условий.
- `toRealPath` для пути эталона: раскрывает ссылки и пускает эталон из-за пределов каталога данных
  по ссылке; отказ при любой ссылке проще и безопаснее.
- Подтверждение условий флагом CLI: выдаёт слова пользователя за факт ([ADR 0028](0028-baseline-conditions-confirmation.md))
  и открывает `CANDIDATE` без записи подтверждения.
- Менять код выхода при несопоставимости или материальной дельте: сравнение становится гейтом
  в обход политики ([ADR 0017](0017-baseline-candidates-and-confirmation.md), [ADR 0018](0018-policy-platform-rules-small-samples.md)).
- Метрики «весь прогон» со стадиями с предупреждением: противоречат вердикту «по steady»
  ([ADR 0030](0030-load-stages-steady-window.md), R7).
- Отдельное хранилище или контракт для сравнения в CLI: нет потребителя, кроме отчётов.

## Следствия

- Анализ, сохранённый до ошибки `BASELINE_CORRUPT` для документов текущего анализа, остаётся в
  каталоге данных; артефакты `--out-dir` и stdout при этом не пишутся, выход 4.
- Раздел и блок `summary.txt` появляются только при `--baseline`; потребитель со строгой схемой
  `cli-summary.v1` увидит ключ `baseline_comparison` только при явном флаге.
- Записи руководства: [docs/user/slice-1-local-analysis.md](../user/slice-1-local-analysis.md),
  раздел «CLI», подраздел «Сравнение с baseline».
- Реализовано: PR #237 (`CliBaseline.kt`, `BaselineChangesView.kt`, правки `CommandLine.kt`,
  `CliArtifacts.kt`, трёх рендереров, руководства). Статус ADR остаётся Proposed.
- Новых зависимостей production нет.

## Тест-критерии

Тесты `CommandLineBaselineTest` и `BaselineChangesReportTest` (PR #237):

1. Прогон с `--baseline` без стадий даёт раздел и блок с дельтами p95, p99, пропускной способности и
   доли ошибок; вердикт, код выхода и байты `result.json` те же.
2. Со стадиями дельты берутся из `window_metric_summary` окна `steady`, а не из метрики «весь
   прогон» (фикстура со стадиями и разной задержкой на плато и в целом); таблицы «весь прогон» нет.
3. Анализ со стадиями против эталона без них (и наоборот), разные объявления стадий и отсутствующее
   окно дают `NOT_EVALUATED` с причиной словами и без таблицы.
4. Эталон без политики сравнивается, слова предупреждений присутствуют; сравнение анализа с самим
   собой даёт предупреждение.
5. Эталон вне каталога данных, по неверному пути, ссылка, неизвестный анализ дают
   `BASELINE_NOT_FOUND`, выход 4, без записи в каталог данных и артефакты.
6. Выходы `FAIL` (2), `NO_VERDICT` (3), `INVALID` (4) с `--baseline` равны выходам без него.
7. `--baseline` без `--out-dir` даёт `BASELINE_OUT_DIR_REQUIRED`; повтор, флаг без значения и
   `--format json|svg` дают usage 64.
8. Статус `CANDIDATE` в CLI не возникает; слова для него покрыты тестом представления.
9. Враждебный, но валидный id стадии остаётся текстом во всех форматах (HTML, AsciiDoc, Confluence,
   `summary.txt`, JSON).
10. Без `--baseline` все артефакты побайтово прежние; отчёты и сводка прогона со стадиями не содержат
    слова «baseline».

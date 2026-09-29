# Локальный анализ в Slice 1

Slice 1 анализирует локальные JMeter JTL и Gatling logs без отправки данных в
сеть. Доступны Web UI и эквивалентный CLI.

## Требования и сборка

Для сборки из repository нужны JDK 21 и Node.js 24.14.0. Все версии runtime и
frontend dependencies закреплены lockfiles.

Windows PowerShell:

```powershell
.\gradlew.bat installDist
.\build\install\ltv\bin\ltv.bat ui
```

Linux:

```bash
./gradlew installDist
./build/install/ltv/bin/ltv ui
```

Приложение откроет случайный URL вида `http://127.0.0.1:<port>`. Если браузер
нельзя открыть автоматически, URL будет напечатан в terminal. Остановите
runtime через `Ctrl+C`.

По умолчанию данные находятся в `~/.lt-verdict`. Другой каталог задаётся так:

```text
ltv ui --data-dir <path>
```

Один data directory обслуживает только один CLI/UI writer process. Для двух
одновременных процессов укажите разные directories.

## Поддерживаемые файлы

- JMeter JTL CSV с header и UTF-8 data;
- JMeter JTL XML с `sample`/`httpSample` и nesting;
- Gatling OSS text `simulation.log` 3.9–3.12;
- Gatling OSS binary `simulation.log` 3.13–3.15.1.

Формат определяется по содержимому, не по extension. Максимальный input —
4 GiB. Исходный upload целиком хранится как immutable `inputs/source.bin`;
response bodies, response headers и XML payload fields не извлекаются в
результаты и не показываются.

Timestamps должны быть epoch в миллисекундах. Значения в диапазоне
`1000000000..99999999999` отклоняются как `INVALID_SAMPLE_TIMESTAMP`: это epoch
в секундах, который иначе молча дал бы run window в 1970 году. Для JMeter
перегенерируйте JTL с `-Jjmeter.save.saveservice.timestamp_format=ms`.

## Анализ через UI

1. В `Runs` выберите `Load test log`.
2. При необходимости выберите `Policy file` или продолжите без policy.
3. Для загруженной policy исправьте поля в editor и дождитесь статуса
   `Policy is valid`.
4. При необходимости выберите `Resource snapshot`, затем нажмите `Analyze run`.
5. Следите за upload percentage, job state и processed bytes.
6. Просмотрите validity, verdict, coverage reasons, policy checks, transactions
   и sparse normalized data.

Повтор тех же input bytes переиспользует run. Новая policy создаёт новый
immutable analysis, не изменяя прежний.

### Сохранённые результаты и графики

В списке принятых прогонов выберите файл, затем нужный analysis. Рядом с
analysis показаны его id, validity и verdict. Результат можно открыть после
перезагрузки страницы без повторного upload или запуска job. Если для input
ещё нет законченных analyses, UI сообщает об этом. Списки имеют пагинацию;
порядок по id не обозначает порядок завершения.

Графики показывают RPS, число ошибок за bucket и P95 latency по выбранным
normalized data. Время отсчитывается от начала прогона. Отсутствующие buckets
разрывают линии; они не заменяются нулями. Rollup и границы интервала задаются
теми же controls, что и для таблицы. Одна страница содержит не более 500
buckets; индикатор и переход к следующему интервалу не позволяют принять её
за весь прогон. Таблица остаётся доступным текстовым представлением графиков.

### Скачать результат

Для открытого analysis доступны `Download JSON`, `Download HTML` и `Download AsciiDoc`.
JSON совпадает по bytes с сохранённым `analysis-result.json`. HTML содержит
идентификаторы, validity, verdict, coverage, metrics, policy checks, findings
и evidence. Он открывается локально без приложения и сетевого доступа.
AsciiDoc — детерминированный UTF-8 report с теми же разделами; значения из
analysis остаются compact JSON tokens внутри literal blocks с `specialchars`,
поэтому текст input не становится AsciiDoc directive или markup. Canonical JSON
сохраняет исходные bytes как точный machine-readable export. Конвертация и
публикация AsciiDoc не входят в этот local export.
Графики в этот первый HTML export не входят.

Просмотр и экспорт не создают новых analyses и не меняют вердикт.
Baseline comparison, N-run history и остальные форматы относятся к следующим
изменениям Slices 8–9.

### Состояния и действия

| Состояние | Значение | Действие |
| --- | --- | --- |
| Uploading | Input потоково принимается и хешируется | Дождаться окончания upload |
| `QUEUED` | Analysis принят bounded queue | Дождаться worker или отменить |
| `PROCESSING` | Parser/metrics читают input | Следить за processed bytes или отменить |
| `BUSY` | Worker и bounded queue заняты | Дождаться или отменить queued job, затем повторить |
| `COMPLETE` | Canonical result опубликован | Просмотреть result и evidence |
| `FAILED` | Runtime не завершил analysis | Проверить diagnostic и повторить после устранения причины |
| `CANCELLED` | Analysis отменён | Input остаётся доступен для нового запуска |

Если страницу перезагрузили во время анализа, UI после загрузки снова показывает
панель самой старой активной задачи (`QUEUED` или `PROCESSING`) с прогрессом и
кнопкой `Cancel analysis`; по завершении результат открывается как обычно. Если
одновременно активны две задачи (по умолчанию одна выполняется, вторая в
очереди), панель
показывает первую; вторая появится после её завершения или отмены и повторной
загрузки страницы. Задачи хранятся в памяти и не переживают перезапуск
приложения.

### Validity и verdict

| Результат | Значение |
| --- | --- |
| `VALID + NO_POLICY` | Input полностью разобран; metrics доступны, policy не задана |
| `VALID + PASS` | Все applicable policy rules выполнены |
| `VALID + FAIL` | Нарушено хотя бы одно applicable rule |
| `INVALID + NO_VERDICT` | Recognized input оказался malformed; `PASS` невозможен |
| `DEGRADED + NO_VERDICT` | Доступна только неполная информация; причина сохранена |
| `NO_VERDICT` с incomplete coverage | Required transaction отсутствует или неоднозначна |

Missing one-second bucket означает отсутствие samples, а не нулевое значение.
UI показывает такую секунду как `Missing / no samples` и не скрывает короткие
spikes заполнением или усреднением готовых percentiles.

### Ошибки upload/API

| Code/status | Значение | Что делать |
| --- | --- | --- |
| `MALFORMED_REQUEST` / 400 | Некорректный multipart или query | Повторить с корректным file/query |
| `MALFORMED_JSON` / 400 | Policy не является допустимым JSON | Исправить policy до анализа |
| 403 | Неверные Host, Origin, local session или CSRF | Перезагрузить только открытый local URL |
| `NOT_FOUND` / 404 | Run, job или analysis отсутствует | Обновить список runs и повторить |
| `BUSY` / 409 | Analysis queue заполнена | Подождать или отменить queued job |
| `RESOURCE_LIMIT_EXCEEDED` / 413 | Input больше 4 GiB, policy больше 1 MiB или resource snapshot превышает limits | Уменьшить файл; partial result не создаётся |
| `LENGTH_REQUIRED` / 411 | У запроса `POST /api/jobs` нет `Content-Length` | Передать размер multipart body; браузерный UI делает это автоматически |
| `INVALID_RESOURCES` / 422 | Snapshot не соответствует контракту или другому load input | Проверить validation details и SHA-256 |
| `UNSUPPORTED_MEDIA_TYPE` / 415 | Неверный request content type | Использовать UI или documented CLI |
| `UNSUPPORTED_INPUT` / 422 | Input пуст или формат не распознан | Экспортировать один из supported formats |
| `DATA_DIR_BUSY` / CLI exit 6 | Другой process держит data directory | Остановить его или выбрать другой directory |

## Policy: import, edit и download

`Policy file` импортирует один JSON object `policy.v1`. После успешной
validation UI показывает editor для `policy_id`, rules, metric, operator,
threshold и scope. `Add rule`/`Remove rule` меняют только текущий draft;
`Download policy` сохраняет его как `policy.json`. Это отдельное действие от
экспорта готового analysis через `Download JSON`/`Download HTML`/`Download AsciiDoc`.

Перед использованием сохранённого файла выполните:

```text
ltv policy validate <policy.json>
```

Valid command печатает canonical policy JSON и возвращает exit `0`.

### Четыре разрешённые метрики

| Metric | Operator | Threshold |
| --- | --- | --- |
| `response_time_p95_ms` | `lte` | число `>= 0`, milliseconds |
| `response_time_p99_ms` | `lte` | число `>= 0`, milliseconds |
| `error_rate_ratio` | `lte` | число от `0` до `1` |
| `throughput_rps` | `gte` | число `>= 0`, requests/second |

Error rate и throughput сравниваются как exact ratios через cross-multiplication
с `BigDecimal`; display rounding не влияет на verdict. Latency observations для
policy остаются integer milliseconds.

Ниже contract example из tracked fixture. Числа показывают JSON shape и не
являются рекомендуемым SLA: замените их согласованными значениями.

```json
{
  "schema_version": "policy.v1",
  "policy_id": "all-metrics",
  "rules": [
    {
      "id": "overall-p95",
      "metric": "response_time_p95_ms",
      "operator": "lte",
      "threshold": 100,
      "scope": {
        "kind": "overall"
      }
    },
    {
      "id": "orders-p99",
      "metric": "response_time_p99_ms",
      "operator": "lte",
      "threshold": 100,
      "scope": {
        "kind": "transaction",
        "name": "POST /orders"
      }
    },
    {
      "id": "overall-errors",
      "metric": "error_rate_ratio",
      "operator": "lte",
      "threshold": 0.5,
      "scope": {
        "kind": "overall"
      }
    },
    {
      "id": "orders-throughput",
      "metric": "throughput_rps",
      "operator": "gte",
      "threshold": 0.1,
      "scope": {
        "kind": "transaction",
        "name": "POST /orders"
      }
    }
  ]
}
```

### Exact transaction matching

Transaction scope имеет ровно форму:

```json
{
  "kind": "transaction",
  "name": "POST /orders"
}
```

`name` сравнивается с exact label, без regex, wildcard, fuzzy matching,
auto-rename или регистра-независимого поиска. Если label не найден, coverage
получает `TRANSACTION_NOT_FOUND`. Если одинаковый label соответствует нескольким
distinct `(group path, label, kind)`, coverage получает
`AMBIGUOUS_TRANSACTION`. Оба случая дают всей policy `NO_VERDICT`, даже если
другое rule уже нарушено.

### Ошибки validation

UI и CLI используют один validator. Ошибка содержит stable `code`, JSON Pointer
и message. Текущая версия возвращает первую найденную ошибку.

| Code | Причина |
| --- | --- |
| `MALFORMED_JSON`, `INVALID_UTF8`, `POLICY_READ_ERROR` | Policy нельзя прочитать как допустимый UTF-8 JSON |
| `MISSING_FIELD`, `UNKNOWN_FIELD`, `INVALID_TYPE` | Нарушена strict contract shape |
| `DUPLICATE_OBJECT_KEY` | Object содержит повторный, в том числе escaped-equivalent, key |
| `INVALID_SCHEMA_VERSION` | `schema_version` не равен `policy.v1` |
| `EMPTY_IDENTIFIER` | Пустой `policy_id`, rule id или transaction name |
| `EMPTY_RULES`, `DUPLICATE_RULE_ID` | Нет rules или rule ids не уникальны |
| `UNKNOWN_METRIC`, `UNKNOWN_OPERATOR` | Metric/operator не поддерживается |
| `METRIC_OPERATOR_MISMATCH` | Operator не соответствует metric |
| `THRESHOLD_OUT_OF_RANGE` | Threshold отрицателен или ratio не входит в `0..1` |
| `INVALID_SCOPE` | Scope не равен exact `overall` или `transaction` form |
| `RESOURCE_LIMIT_EXCEEDED` | Превышен размер, depth, count или lexical numeric limit |

Невалидная policy отклоняется до создания analysis и не превращается в
`NO_POLICY`.

## Нормативный prompt для внешней нейросети

Передайте модели точные transaction names и уже согласованные thresholds вместе
с этим prompt. LT Verdict не вызывает нейросеть сам.

```text
Ты составляешь только LT Verdict policy.v1 JSON.

Разрешены metrics:
- response_time_p95_ms с operator lte;
- response_time_p99_ms с operator lte;
- error_rate_ratio с operator lte и threshold 0..1;
- throughput_rps с operator gte.

Scope — только overall или transaction с точным переданным пользователем name.
Не используй regex, wildcard, phases, baseline, implicit defaults и новые поля.
Не придумывай thresholds, transaction names, units или SLA. Если хотя бы одно
значение отсутствует, задай пользователю уточняющий вопрос и не создавай JSON.
Каждому правилу дай короткий уникальный id. Верни один JSON object без Markdown,
пояснений и комментариев. schema_version всегда policy.v1. После составления
попроси пользователя выполнить `ltv policy validate <file>`.
```

## Baseline: ручной выбор и статистический автовыбор

В панели **Baseline comparison** можно закрепить один сохранённый analysis:

1. Откройте нужный run и saved analysis либо выполните analysis файла.
2. Укажите **Comparison series**: например, scenario и test environment.
3. Нажмите **Set as baseline**. Полные run/analysis IDs появятся в панели.
4. Откройте другой analysis и нажмите **Compare selected analysis**.

Выбор хранится в data directory, переживает reload и перезапуск приложения.
Он не меняется при новых runs или повторном анализе того же input. **Clear
baseline** снимает выбор, не удаляя исходные RunBundles. Один активный baseline
не означает, что все runs в хранилище автоматически сопоставимы.

Для автовыбора раскройте **Statistical selection**. Последовательно открывайте
analyses и добавляйте их через **Add selected candidate**. Нужны 3–20 разных
runs; несколько analyses одного input не считаются несколькими прогонами.
Проверьте состав серии и подтвердите **Same planned test conditions**, затем
нажмите **Select statistically**. Добавление/удаление кандидата снимает
подтверждение, чтобы оно не применялось незаметно к другой серии.

Подтверждаются заданные scenario/mix, environment/dataset, модель и план
нагрузки, pacing и ограничения генератора. Равенство фактического RPS не
требуется: падение throughput может быть частью наблюдаемой деградации.
Программа проверяет техническую семантику метрик, но не восстанавливает
заданный профиль из JTL. Невалидный/неполный кандидат или отсутствующие
метрики отклоняют запрос с причиной; предыдущий baseline сохраняется.

Алгоритм `median-rank-v1` выбирает один реальный прогон, ближайший к центральным
рангам overall P95, throughput и error rate с одинаковыми весами. При равенстве
score выбор определяется IDs, а не порядком добавления. Полный состав серии и
scores сохраняются; раздел **Selection scores** объясняет выбор. Это
эвристика типичного представителя, не доказательство устойчивой нормы или
статистической значимости. Нестабильная либо смешанная серия может дать плохой
эталон. Автоматического включения новых runs и rolling baseline нет.

Таблица сравнения показывает P95/P99 в ms, throughput в requests/second и
error rate в ratio (`0.01 = 1%`). Числа округляются для отображения максимум
до 6 знаков после точки; расчёт дельт выполняется до округления. Нулевой
baseline допускает абсолютную дельту, но относительная будет `N/A
(ZERO_BASELINE)`. Отсутствующие значения не заменяются нулями.
Различие определений метрик даёт `INCOMPATIBLE_METRIC_DEFINITION` для дельт,
оставляя исходные значения видимыми. `UNCONFIRMED` означает неизвестные
заданные условия; `USER_CONFIRMED` — принадлежность подтверждённой пользователем
statistical серии, а не машинную проверку сценария.

Дельты не меняют `policy_verdict`, validity или coverage и сами по себе не
доказывают регрессию версии. Сравнение читает сохранённые artifacts, не создаёт
job и не обращается к внешним источникам. Текущие JSON/HTML/AsciiDoc reports
по-прежнему экспортируют исходный analysis, без этой отдельной comparison view.
Transaction comparison, графические overlays и N-run history пока не входят
в реализованную часть baseline. Подробные правила — в
[ADR 0004](../adr/0004-local-baseline-selection.md).

## Аппаратные метрики и совместные SLA

Optional `Resource snapshot` — подготовленный локальным адаптером JSON
`resource-snapshot.v1`, привязанный к SHA-256 загруженного load log.
Приложение не обращается к VM/Grafana. Используйте
[контракт](../contracts/resources/v1/resource-snapshot.schema.json) и
[пример](../contracts/resources/v1/examples/valid/basic.json); замените hash,
timestamps и значения своими данными, а thresholds — согласованными SLA.

Series содержит metric, unit, entity, role `system|generator`, aggregation
`interval_mean|interval_rate` и значения на общей UTC grid. `null` означает gap,
а не ноль. Raw counters, instant samples и percentile series сначала требуется
преобразовать в поддерживаемую семантику в адаптере. Credentials и URL в snapshot
не передавайте. Limits: файл 16 MiB, 64 series, 100 000 points на series,
500 000 cells суммарно, 64 непересекающихся окна и 256 resource rules.
Не более 10 000 интервалов resource threshold violations на analysis;
превышение даёт `RESOURCE_FINDINGS_LIMIT_EXCEEDED` без partial result.
Оконные бизнес-метрики ограничены 10 000 потенциальных histograms:
число окон × (overall + число policy-referenced transaction identities).

Explicit windows задают интервалы `[from_epoch_ms, to_epoch_ms)` на grid внутри
load run. Без windows берутся полностью включённые ячейки пересечения run и
snapshot; отброшенные края отражаются в evidence. Sample относится к окну по
start timestamp: latency не обрезается на правом краю, RPS делится на полную
длительность окна. Бизнес-правила existing `policy.v1` проверяются на этих же
окнах, включая exact transaction matching.
Transaction identity разрешается по каталогу всего прогона; окно меняет
наблюдения, но не снимает неоднозначность одинаковых labels разных paths/kinds.
Binding evidence показывает границы и отброшенные края. Clock alignment помечен
`not_verified_by_core`: сведения адаптера в raw provenance не являются
независимой проверкой синхронизации часов.

Resource rule задаёт series_id, unit, `gt|lt`, threshold,
`min_consecutive_cells` и effect `sla|diagnostic`. Сравнение означает нарушение;
например, `gt` считает превышения. Null разрывает последовательность. Порог
interval mean не означает превышение в каждый instant. CPU cores, ratio и bytes
не конвертируются автоматически. Без правил нет автоматического saturation.

UI и reports показывают min/max, mean/median, Q05/Q25/Q75/Q95, IQR, unscaled MAD,
sample standard deviation, slope/sec и split-half median shift, а также
observed/expected cells и gaps. Малые/пустые выборки дают null и причины, не
фиктивные нули. Это описательные статистики, не confidence, change point или
доказательство причинности.

Общий verdict и verdict каждого окна учитывают обязательные бизнес- и resource
SLA. Любые недостающие необходимые данные дают `NO_VERDICT`, даже если другое
правило уже нарушено; наблюдаемые нарушения всё равно остаются в evidence.
При полных данных любое нарушение даёт `FAIL`, иначе `PASS`. Без обязательных
правил — `NO_POLICY`. Diagnostic rules не влияют на verdict. Resource gaps
не меняют load validity; invalid/degraded load не получает PASS.

Snapshot и настройки входят в identity; исходные bytes сохраняются в immutable
bundle. Семантически одинаковые данные с другим transport provenance могут
переиспользовать предыдущий analysis и его первоначальный provenance.
Без snapshot поведение прежнего load-only анализа сохраняется. Baseline не
переназначается. Автоматическая сегментация и capacity bounds пока
не вычисляются.

## CLI

```text
ltv ui [--data-dir <path>] [--analysis-parallelism <n>]
ltv analyze <input> [--policy <policy.json>] [--resources <snapshot.json>] [--data-dir <path>]
ltv policy validate <policy.json>
ltv report <run-id> <analysis-id> --format json|html|asciidoc [--data-dir <path>]
```

`ltv analyze` печатает canonical `analysis-result.v1` в stdout.

`ltv report` читает уже сохранённый analysis, проверяет его manifest и выводит
JSON, UTF-8 HTML либо UTF-8 AsciiDoc в stdout. `--format` обязателен. Для сохранения файла
перенаправьте stdout, сохранив исходную кодировку/bytes. На Windows используйте
PowerShell 7.4+ либо redirection в `cmd`; Windows PowerShell 5.1 перекодирует
native stdout ([поведение redirection](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_redirection)).
Перед запуском CLI остановите UI, использующий тот же data
directory; скачивание из работающего UI доступно без остановки.

Успешный export возвращает `0` даже для `FAIL` или `NO_VERDICT`: exit описывает
экспорт, исходный verdict остаётся в отчёте. Отсутствующий run/analysis даёт
`4`; usage error — `64`; повреждение сохранённых данных — `70` без partial
report в stdout.

| Exit | Значение |
| ---: | --- |
| `0` | `PASS`, `NO_POLICY`, valid policy или успешный export |
| `2` | `FAIL` |
| `3` | `NO_VERDICT` или `DEGRADED` |
| `4` | Invalid/unsupported input, неверный resource snapshot/binding или отсутствующий analysis для export |
| `5` | Invalid policy |
| `6` | `DATA_DIR_BUSY` |
| `64` | Usage error |
| `70` | Unexpected internal failure |

## Описательная диагностика одного прогона

Optional `correlation-plan.v1` включает только явно перечисленные пары и правила
аномалий. В UI выберите файл плана вместе с resource snapshot. CLI:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --resources resources.json --correlation correlation.json --data-dir data
```

План ссылается на **semantic** SHA-256 snapshot, не hash исходного JSON файла.
Его можно взять из `resource_snapshot_sha256` сохранённого `identity.json`
предварительного анализа с тем же snapshot без плана; файл лежит в
`data/runs/<run-id>/analyses/<analysis-id>/identity.json`. Адаптер может
подготовить план по [контракту](../superpowers/plans/2026-09-05-load-resource-correlation.md).
Исходный `correlation-plan.json` сохраняется рядом и защищён manifest.

Правило аномалии выбирает signal, непересекающиеся reference/evaluation windows,
направление, минимальное абсолютное изменение и длительность. Modified-Z
использует reference median/MAD; при MAD=0 остаётся только абсолютный порог.
Reference — явно выбранный режим, не автоматически доказанная норма. Пропуски
разрывают эпизоды; короткие превышения учитываются в suppressed count.
Нарушения SLA отображаются независимо от фильтра аномалий.

Корреляция показывает исходный Spearman и conditional rank association при
выбранных controls; при недостатке наблюдений коэффициент отсутствует. Лаг
положителен, если resource наблюдается раньше load outcome; неизвестные часы
запрещают трактовать это как технический порядок событий. Achieved RPS может
сам падать из-за деградации: он не выбирается автоматическим контролем.
Не усредняются cell percentiles; диагностический P95 требует минимум20 requests
в ячейке (технический порог, не confidence level).

`CANDIDATE` — наблюдаемая ассоциация/эпизод, не доказанная причина.
`DESCRIPTIVE`, `INSUFFICIENT_DATA` и `NO_MATERIAL_CHANGE` различаются.
Uncertainty сырых коэффициентов всегда `NOT_ESTIMATED`; `HIGH confidence`
отсутствует. Отдельный слой выбора главной находки p-values публикует:
evidence `correlation_headline_selection` несёт `p_value_b10`, `p_value_b20`,
`max_p_value`, `holm_adjusted_p_value` и `selected` для каждой гипотезы
объявленного семейства.

Находка `correlation_candidate` публикуется только при `selected = true`, то
есть когда Holm-скорректированное p не выше 0.05 и порог материальности пройден.
Метод `mbb-lag-max-holm.v1`: moving-block bootstrap (999 реплик, блоки 10 и 20),
статистика `max |rho|` по всему объявленному поиску лагов, консервативный
max-p по двум длинам блока и одна поправка Holm на объявленное семейство.
Неотклонённая гипотеза означает `HOLM_NOT_REJECTED`, а не отсутствие связи.

Слой работает только для одного окна, одной outcome-метрики, не более 16 гипотез,
30–240 непрерывных ячеек и без фактически использованных controls. За пределами
этой полосы всё семейство получает `UNAVAILABLE` с точной причиной
(`MULTI_WINDOW_FAMILY_UNSUPPORTED`, `FAMILY_OUTCOME_MISMATCH`,
`FAMILY_SIZE_UNSUPPORTED`, `FAMILY_GRID_MISMATCH`,
`OBSERVATION_COUNT_UNSUPPORTED`, `LAG_ANCHOR_COUNT_UNSUPPORTED`,
`GENUINE_PARTIAL_UNCALIBRATED`, `COMPUTATION_LIMIT_EXCEEDED`), и находки не
публикуются вовсе, хотя сырые коэффициенты и статусы остаются в evidence.
Принятая остаточная частота шумных
отчётов 7.7–13.2% измерена на development-seeds в NumPy и не является измеренной
гарантией JVM-реализации.

Все declared pairs и причины непроверяемости остаются в evidence. Optional
diagnostic limit не меняет бизнес-/ресурсный SLA verdict. Без плана старый
результат не меняется, и baseline не переназначается.

## Рост метрики в пределах SLA

Optional `trend-plan.v1` отвечает на вопрос «растёт ли метрика, даже если порог
не пересечён». В UI выберите файл плана вместе с resource snapshot. CLI:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --resources resources.json --trend trend.json --data-dir data
```

План ссылается на тот же **semantic** SHA-256 snapshot, что и correlation plan,
и объявляет не более 32 проверок. Каждая проверка задаёт series, окно,
направление (`increase`, `decrease`, `either`), минимум наблюдаемых ячеек
(30 и больше) и `magnitude_gate` из двух положительных величин: минимального
абсолютного наклона в единицах series в секунду и минимального абсолютного
сдвига медиан половин окна в процентах от медианы окна. Обе величины объявляются
**до** прогона.

Тренд наблюдается только если прошли оба порога и обе статистики согласованы по
знаку; при `either` требуемый знак берётся из наклона. Используются те же
`slope_per_second` и `split_half_shift`, что публикует `resource_summary`.
Пропуски не заполняются и не сжимают время: проверка видит
`expected_cells`, `observed_cells`, `missing_cells`, `longest_gap_cells` и
reason `RESOURCE_GAPS`.

Кроме общего минимума, в каждой половине окна должно быть не меньше
`floor(min_cells / 2)` наблюдаемых ячеек (при `min_cells` 30 это 15 в каждой
половине). Иначе сдвиг половин сравнивал бы медиану многих точек с единичной, и
проверка отказывает с `INSUFFICIENT_CELLS` и reason `TREND_HALF_CELLS_NOT_MET`.

Статусы: `TREND_OBSERVED`, `NO_MATERIAL_TREND`, `INSUFFICIENT_CELLS`,
`UNAVAILABLE`. Каждый отказ несёт точный reason — `NO_OBSERVATIONS`,
`TREND_MIN_CELLS_NOT_MET`, `TREND_HALF_CELLS_NOT_MET`,
`INSUFFICIENT_OBSERVATIONS`, `TREND_MEDIAN_ZERO`
(процентный порог не определён при нулевой медиане), `TREND_DIRECTION_MISMATCH`,
`TREND_DIRECTION_DISAGREEMENT`, `TREND_SLOPE_BELOW_MINIMUM`,
`TREND_SHIFT_BELOW_MINIMUM`, `TREND_SERIES_NOT_FOUND` (ряд не найден),
`TREND_WINDOW_NOT_FOUND` (окно не найдено), `RUN_NOT_VALID` (прогон недействителен).
Каждый `TREND_OBSERVED` дополнительно несёт
`STATIONARITY_NOT_EVALUATED`: объявленное окно не доказывает стационарность,
детектора смены режима нет, поэтому ступень нагрузки может выглядеть как рост.

**Ограничение: ряды со сбросами.** L0 не применим к рядам с перезапусками
(память с рестартами подов, циклы OOM, пила GC). На синтетической пиле без
роста огибающей (3 цикла по 40 ячеек, значение от 200 до 980 в каждом) окно от
границы цикла даёт `TREND_OBSERVED` с направлением `increase` и сдвигом половин
+200 (33,9 % медианы 590), а то же окно, сдвинутое на 20 ячеек, даёт
`TREND_OBSERVED` с направлением `decrease` и сдвигом -200: знак определяет фаза
окна, а не поведение метрики. Выбирайте окно внутри одного цикла или не
объявляйте проверку на таком ряду.

`resource_trend` — finding с `effect=diagnostic`: он не меняет бизнес- или
ресурсный verdict и не попадает в `analysis_coverage`. Это наблюдение, а не
диагноз: рост памяти или пула не является доказательством утечки, причины или
исчерпания ресурса. Отсутствие находки не доказывает отсутствие роста.
Uncertainty остаётся `NOT_ESTIMATED`; p-values в этом уровне нет.

Сохраняются `trend-plan.json` (исходные загруженные байты) и `trend.json`
(`trend.v1`); оба доступны для скачивания рядом с capacity-артефактами. Без
плана результат, identity и verdict не меняются.

## Сравнение двух окон

В панели baseline comparison явно задайте id окна baseline и current. Для
сохранённых analyses с correlation plan доступны window summaries и точные
resource bindings. Старые analyses без этих summaries дают `NOT_EVALUATED`,
но их прежние overall deltas остаются доступными.

Пороги материальности задаются **до** сравнения: процент изменения для обычных
метрик (default5%) и абсолютная разность error-rate ratio (default0.001, то
есть0.1 процентного пункта). При нулевом baseline относительная дельта
неопределена; абсолютная сохраняется, это не `NO_MATERIAL_CHANGE`.

Сравниваются load P50/P95/P99, error rate и achieved RPS, resource median/Q95
при точном совпадении series binding. Число samples и длительность окон видны.
Одинаковые имена окон или достигнутый RPS не доказывают одинаковых заданных
условий. Разные ступени не объединяются в средний percentile; результат
относится к этим двум измерениям, а не к популяции запусков версии продукта.

## Локальная security boundary

UI работает только на `127.0.0.1`, использует in-memory CSRF token и strict
same-origin session. Runtime не выполняет outbound requests. Не публикуйте
loopback port через proxy и не считайте private API server authentication.
Детали хранения и limits описаны в
[архитектуре runtime](../architecture/slice-1-local-runtime.md).

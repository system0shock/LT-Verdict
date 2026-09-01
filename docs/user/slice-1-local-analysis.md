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
4 GiB. Response bodies, response headers и raw XML content не сохраняются и не
показываются.

## Анализ через UI

1. В `Runs` выберите `Load test log`.
2. При необходимости выберите `Policy file` или продолжите без policy.
3. Для загруженной policy исправьте поля в editor и дождитесь статуса
   `Policy is valid`.
4. Нажмите `Analyze run`.
5. Следите за upload percentage, job state и processed bytes.
6. Просмотрите validity, verdict, coverage reasons, policy checks, transactions
   и sparse normalized data.

Повтор тех же input bytes переиспользует run. Новая policy создаёт новый
immutable analysis, не изменяя прежний.

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
| `RESOURCE_LIMIT_EXCEEDED` / 413 | Input больше 4 GiB или policy больше 1 MiB | Уменьшить файл; partial result не создаётся |
| `UNSUPPORTED_MEDIA_TYPE` / 415 | Неверный request content type | Использовать UI или documented CLI |
| `UNSUPPORTED_INPUT` / 422 | Input пуст или формат не распознан | Экспортировать один из supported formats |
| `DATA_DIR_BUSY` / CLI exit 6 | Другой process держит data directory | Остановить его или выбрать другой directory |

## Policy: import, edit и download

`Policy file` импортирует один JSON object `policy.v1`. После успешной
validation UI показывает editor для `policy_id`, rules, metric, operator,
threshold и scope. `Add rule`/`Remove rule` меняют только текущий draft;
`Download policy` сохраняет его как `policy.json`. В Slice 1 download относится
к policy: export analysis result входит в будущий Slice 9.

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

## CLI

```text
ltv ui [--data-dir <path>] [--analysis-parallelism <n>]
ltv analyze <input> [--policy <policy.json>] [--data-dir <path>]
ltv policy validate <policy.json>
```

`ltv analyze` печатает canonical `analysis-result.v1` в stdout.

| Exit | Значение |
| ---: | --- |
| `0` | `PASS`, `NO_POLICY` или valid policy |
| `2` | `FAIL` |
| `3` | `NO_VERDICT` или `DEGRADED` |
| `4` | Invalid/unsupported input |
| `5` | Invalid policy |
| `6` | `DATA_DIR_BUSY` |
| `64` | Usage error |
| `70` | Unexpected internal failure |

## Локальная security boundary

UI работает только на `127.0.0.1`, использует in-memory CSRF token и strict
same-origin session. Runtime не выполняет outbound requests. Не публикуйте
loopback port через proxy и не считайте private API server authentication.
Детали хранения и limits описаны в
[архитектуре runtime](../architecture/slice-1-local-runtime.md).

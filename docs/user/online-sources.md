# Онлайн-источники метрик и ошибок

Поддерживаются read-only PromQL `query_range`, InfluxQL GET `/query` и
OpenSearch POST `/_search`. PostgreSQL следует отдельно; Grafana dashboard discovery и panel
transformations не поддерживаются. Боевой plugin/auth route нужно проверить на
вашем стенде. Без connections file приложение не выполняет acquisition.

## Запуск

Скопируйте [пример PromQL-профиля](../contracts/sources/v1/connections.example.json)
или [пример InfluxDB-профиля](../contracts/sources/v1/influxdb-connections.example.json),
задайте адрес и запросы своего стенда. В UI:

```powershell
.\build\install\ltv\bin\ltv.bat ui --connections connections.json
```

Выберите профиль и UTC start/end в epoch milliseconds, step в milliseconds.
Интервал должен состоять из полных cells, step >= 1000 и укладываться в прогон.
Вместо online selection по-прежнему можно загрузить resource snapshot.

CLI использует [пример source request](../contracts/sources/v1/request.example.json):

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --connections connections.json --source source.json --data-dir data
```

Профили загружаются при запуске; изменение файла требует перезапуска backend.
`source_kind`: `prometheus`, `victoria_metrics` или `influxdb`; `transport`:
`direct` или `grafana_proxy`. Для PromQL direct добавляет `/api/v1/query_range`
к `base_url` (VM tenant prefix сохраняется), а Grafana добавляет
`/api/datasources/proxy/uid/{datasource_uid}/api/v1/query_range`. InfluxDB-маршруты
описаны ниже. Subpath установки задаётся в base URL. Redirects и произвольные
URLs из UI запрещены.

## Метрики и время

Каждый query задаёт id, expression, metric, unit, entity, role, aggregation и
необязательные ожидаемые labels. Ответ должен содержать ровно одну серию;
несколько серий — ошибка, не автоматическая агрегация. Сохранённые labels —
фактические labels ответа; ожидаемые проверяются как subset. Пустой ответ — missing.

`$__interval` в expression заменяется на step в ms. Для interval_mean обычно
нужен `avg_over_time(metric[$__interval])`, для interval_rate —
`rate(counter[$__interval])`. Корректность выражения и единиц задаёт автор
профиля: приложение не доказывает семантику произвольного PromQL и не конвертирует
проценты/байты/секунды по имени метрики. Правила используют единицы результата.

Запрашиваются правые границы `start+step` ... `end`; ответ на границе `t+step`
относится к snapshot cell `[t,t+step)`. Реальные scrape/rate особенности
источника сохраняются, интерполяции нет. NaN/Inf и gaps не становятся нулями;
duplicate/off-grid timestamps, histograms и неоднозначные labels дают явную ошибку.
Непустые source warnings в первой поставке также отклоняют query (`SOURCE_WARNINGS`):
частично отброшенные источником данные не выдаются за полный ответ.

## InfluxDB / InfluxQL

Профиль `influxdb` требует `database` длиной 1..128 UTF-8 bytes. Поддерживается
только InfluxQL: Flux и InfluxDB 3 SQL не входят в эту поставку. Для InfluxDB 2
совместимый endpoint `/query` требует заранее настроенный DBRP mapping, а
`database` содержит имя mapped database/retention policy. Учётная запись БД
должна иметь read-only роль: проверка текста SELECT не заменяет права доступа.

Direct отправляет GET на `{base_url}/query`, Grafana proxy — на
`{base_url}/api/datasources/proxy/uid/{datasource_uid}/query`; `/write` никогда
не используется. Параметры запроса: `db`, `q`, `epoch=ms`. Expression обязан
содержать `$__start`, `$__end`, `$__interval`; `$__offset` необязателен и равен
`start % step`. Первые три значения заменяются соответственно на start/end/step
с суффиксом `ms`.

Запрос должен быть одним SELECT и возвращать ровно колонки `time`,`value` в
любом порядке. Полю метрики задайте alias `AS "value"`; `time` возвращает сам
InfluxQL. Консервативный validator отклоняет `INTO`, semicolon, comments,
неизвестные placeholders и выражения, форму которых нельзя подтвердить без
полного SQL parser. Один результат с нулём или одной series допустим; несколько
statements/series, partial response, messages/errors, неверные tags,
duplicate/off-grid timestamps отклоняются.

InfluxQL timestamp является левой границей snapshot cell. Пример с cells
`[1000,2000)`, `[2000,3000)`, `[3000,4000)` принимает timestamps `1000`, `2000`,
`3000`. Отсутствующая точка и JSON `null` остаются gap (`null`), не становятся
нулём и не интерполируются.

## OpenSearch error context

[Пример профиля](../contracts/sources/v1/opensearch-connections.example.json)
использует `source_kind:opensearch`, только `transport:direct` и mapping
`opensearch`. Поля `queries`, `rules`, `database`, `datasource_uid` запрещены.
Задайте ограниченный index pattern: wildcard-only `*`, `**`, `_all` запрещены.
Timestamp должен быть date, service/error type — single-valued keyword.
Ошибкой считается документ с существующим `error_type_field`.

Backend отправляет фиксированный POST `{base_url}/{indices}/_search`,
без пользовательского Query DSL. Окно `[start,end)`, timeline имеет step ms;
сохраняются total, errors/minute, service/type groups, first/last и samples.
Limits: до 16 index patterns, 200 groups (default 50), 5 samples/group
(default 2), message до 65536 UTF-8 bytes (default 4096). `samples_per_group:0`
отключает сообщения. Учётной записи нужны только права чтения этих индексов.

Timeout, failed shards, lower-bound totals, truncated/approximate terms и
несогласованные суммы дают `PARTIAL` с причинами. Malformed response даёт
`FAILED`, а не нулевые ошибки. Эти факты не меняют бизнес-SLA и не являются
метриками железа или новой корреляцией. Для context-only analysis
`resource-snapshot.json` не создаётся.

Сохранённый `opensearch-errors.json` привязан к SHA-256 load input. Его можно
скачать в UI и импортировать без connections через поле OpenSearch context:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --source-context opensearch-errors.json --data-dir data
```

Допустимы одновременно offline `--resources` и `--correlation`; online
`--source` с manual context запрещён. Import повторно проверяет counts,
rates, coverage, grid, URLs и load binding, а не доверяет готовым итогам.
Sample messages выводятся как текст, document URLs — внешние ссылки;
raw ответы и сообщения могут содержать чувствительные данные стенда.

Private API принимает file part `source_context` в `POST /api/jobs`;
`GET /api/runs/{runId}/analyses/{analysisId}/source-context` скачивает только
существующий manifest-validated JSON attachment. Context ограничен 16 MiB;
job — пять частей и сумма отдельных limits с multipart overhead.

## Credentials и limits

Auth по умолчанию `{"type":"none"}`. Bearer:
`{"type":"bearer","token_env":"LTV_METRICS_TOKEN"}`; Basic:
`{"type":"basic","username_env":"LTV_METRICS_USER","password_env":"LTV_METRICS_PASSWORD"}`.
Influx token: `{"type":"token","token_env":"LTV_INFLUX_TOKEN"}`; backend
отправляет его как `Authorization: Token ...`.
Значения задаются environment процесса backend, не в JSON/UI. TLS verification
всегда включена. Credentials по HTTP требуют `allow_insecure_http:true`;
используйте HTTPS вне доверенного локального теста.

Governor defaults: `requests_per_second:0.5`, `burst:1`, `max_concurrent:1`,
`timeout_ms:30000`, `max_attempts:3`, `honor_retry_after:true`.
`max_requests_per_run` необязателен, скрытого request cap нет.
Один origin (scheme/host/effective port) разделяет strictest budget всех
настроенных профилей, jobs и retries. Первый запрос после старта ждёт interval.

Config <= 1 MiB/16 profiles/32 queries; source request <= 16 KiB; response
<= 16 MiB, acquisition raw total <= 64 MiB. Snapshot <= 16 MiB,
100000 points/series, 500000 cells; остальные limits проверяет общий validator.
Timeout/retries/body read отменяются вместе с job. HTTP error bodies не публикуются.

## Результат и offline replay

`source_summary` evidence показывает COMPLETE/PARTIAL/FAILED и статусы запросов,
request_count/retries/throttle_wait_ms/cap_exceeded. Failed query сохраняется
all-null серией: соответствующий ресурсный SLA не получает ложный PASS.
Бизнес-анализ доступен независимо от отсутствующих метрик.
Если сумма нормализованных серий превышает snapshot byte limit, данные заменяются
проверенным all-null snapshot с `SOURCE_SNAPSHOT_LIMIT_EXCEEDED`; успешные raw
ответы остаются в артефактах. Полный анализ нагрузки при этом не теряется.

Analysis directory атомарно содержит `resource-snapshot.json`,
`source-acquisition.json` и успешные `source-response-N.json` с manifest hashes.
Profiles/credentials там не сохраняются. Источник данных всё равно считается
недоверенным: labels/raw metrics могут содержать служебные данные стенда.

Скачайте snapshot из сохранённого анализа либо возьмите файл из analysis directory:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --resources saved-resource-snapshot.json --data-dir data
```

Это offline анализ с теми же metric/SLA facts, но отдельной identity без acquisition
provenance. Открытие старого результата также не выполняет HTTP requests.
Тот же ручной replay применяется к сохранённому InfluxDB snapshot: подключение,
DBRP mapping и token при повторном анализе не нужны.
Для correlation plan сначала получите snapshot hash, затем запускайте offline
`--resources ... --correlation ...`: online acquisition и correlation plan в одном
запросе не смешиваются, потому что план привязан к конкретному snapshot.

Private API: `GET /api/sources` выдаёт только id/source_kind/transport;
`POST /api/jobs` принимает optional file part `source_request` вместо
`resource_snapshot`; `GET /api/runs/{runId}/analyses/{analysisId}/resource-snapshot`
скачивает существующий проверенный артефакт. Existing Origin/CSRF/size guards действуют.

Решение: [ADR 0007](../adr/0007-opt-in-online-sources.md).

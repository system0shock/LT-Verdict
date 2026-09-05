# Online sources — первая поставка и границы блока

Статус: принят пользователем 2026-09-05; разрешены implementation plan и реализация.
База: `1bbeef9`, ветка `feat/online-sources`, существующий isolated worktree.

## Цель и порядок

Все источники MVP входят в текущий блок: VM/Prometheus, Grafana transport,
InfluxDB, PostgreSQL и OpenSearch 2.6. Первая законченная поставка —
VM/Prometheus напрямую и через Grafana proxy, с сохранением и анализом данных
в CLI и Web UI. После проверки общего HTTP механизма остальные источники
реализуются независимо, без общего незавершённого mega-slice.

Capacity, углублённая проверка статистики, ИИ и синтетическая приёмка следуют
в согласованном порядке. Jenkins остаётся отдельным workflow/artifact блоком.
JVM/OpenShift packs используют метрики, но не требуют отдельных transport clients.

## Выбранный подход

Один process-local HTTP governor и JDK 21 HTTP client; существующие Kotlin JSON,
jobs, immutable filesystem и renderers. Новая HTTP dependency не нужна.
Метрики нормализуются в существующий `resource-snapshot.v1`; исходные ответы
и acquisition provenance сохраняются отдельно, с hashes и ограничениями.

Альтернативы: независимые HTTP clients нарушают общий лимит origin;
универсальный connector framework не нужен до появления реальных различий.
PostgreSQL tables и OpenSearch events не превращаются в фиктивные metric series.
Их versioned artifacts определяются вместе с соответствующими поставками.

## Connection и query configuration

- Connection profiles загружаются backend из явно выбранного локального файла.
  UI выбирает profile ID, а не отправляет произвольные URLs и credentials.
- VM и Prometheus имеют отдельные profiles, но один PromQL response decoder.
  Direct endpoint поддерживает заданный base path, включая VM tenant prefix.
  Grafana route использует явный datasource UID и proxy prefix; автоматического
  поиска dashboards, datasource discovery и panel transformations нет.
- Credentials — ссылки на environment variables; значения не сохраняются
  в profiles, RunBundle, browser storage, отчётах или диагностических ошибках.
  Первая поставка: anonymous, Bearer и Basic; credentialed plaintext HTTP
  требует явного разрешения профиля. TLS verification не отключается.
- Разрешённый origin/path определяется profile. Redirects отключены; URI
  userinfo/fragment запрещены. UI credentials не превращаются в proxy credentials.
- Query mapping задаёт PromQL, metric/unit/role/aggregation, labels для entity
  и стабильного series ID. Unit conversion не угадывается по имени метрики.
  Запросы/адаптацию к стенду готовит пользователь или локальная модель.

## Временная семантика

Текущий snapshot описывает ячейки `[t,t+step)` с `interval_mean` или
`interval_rate`. Mapping обязан задавать выражение с этим смыслом; произвольный
instant gauge нельзя молча объявлять interval mean.

Для trailing-window PromQL выражений запрос выполняется на правых границах
ячеек: от `start+step` до `end` включительно; результат в `t+step` относится
к ячейке `t`. Ширина trailing window должна соответствовать `step` и быть
явной в конфигурации. Источник определяет реальные особенности scrape/rate;
они сохраняются в provenance, не исправляются интерполяцией.

Missing samples, nonfinite values и неуспешный query не становятся нулями.
Off-grid/duplicate timestamps, неоднозначное связывание labels и превышение
limits дают явную ошибку соответствующего source. Native histograms в первой
поставке не поддерживаются и не пропускаются молча.

## Governor, отказ и сохранение

Обязателен §17 local-first delta design: normalized origin key, общий lifetime
backend, defaults 0.5 RPS/burst1/concurrency1/timeout30s/maxAttempts3.
Retries расходуют тот же budget; Retry-After учитывается, restart ждёт первый
интервал, несколько profiles одного origin используют strictest limits.
Опциональный maxRequestsPerRun не вводится как скрытый default.

Только read-only requests. Размер ответа, общий acquisition budget и число
series/cells ограничены; caps и точные поля фиксируются в implementation plan
до кода. Cancellation прерывает ожидание governor и текущий запрос.
Ошибки HTTP не публикуют raw error body и secrets.

Acquisition выполняется отдельно от pure analysis. Успешные raw responses,
normalized snapshot и source status публикуются атомарно, без перезаписи.
Сбой одного query/source сохраняет явную неполноту; неуспешные данные не
выдаются за полный snapshot. Load-only analysis остаётся доступен, но UI
объясняет, какие ресурсные проверки не выполнены.

Повторное открытие и re-analysis используют сохранённые артефакты, без сети.
Export/import нормализованного snapshot проходит тот же shared validator и
даёт те же analytical facts. Acquisition provenance не содержит credentials.

## Проверяемая первая поставка

1. Direct Prometheus/VM и Grafana proxy дают одинаковые нормализованные данные
   из одинакового matrix response; сохраняется различие source/transport.
2. На известных интервалах проверены timestamp mapping, units, labels, gaps,
   nonfinite values, пустой ответ, неоднозначность и limits.
3. Два profiles одного origin не удваивают rate; retries, Retry-After,
   cancellation и restart соблюдают governor contract.
4. Timeout/401/429/5xx/неверный JSON не ломают load-only результат,
   не создают ложного resource PASS и не раскрывают credentials.
5. CLI и настоящий UI через локальный fixture HTTP server проходят путь
   acquire → persist → analyze → reload; replay не обращается к источнику.
6. Existing load-only, SLA, diagnostics и baseline regression tests остаются
   зелёными. Один итоговый review и адресные исправления, без повторных волн.

Первые тесты не требуют доступа к боевому стенду. Совместимость его Grafana
plugin/auth route подтверждается отдельно; неподдерживаемый маршрут не
подменяется обещанием автоматической адаптации.

## Основания

- [Local-first delta §§8, 17](2026-08-26-v06-local-mvp-delta-design.md).
- [Текущая очередь MVP](../../development-plan-v0.6.md).
- [Prometheus HTTP API](https://prometheus.io/docs/prometheus/3.5/querying/api/):
  range matrix, inclusive start/end и явный step.
- [Grafana datasource proxy](https://grafana.com/docs/grafana/latest/developer-resources/api-reference/http-api/api-legacy/data_source/):
  маршрут по datasource UID; совместимость конкретного стенда требует проверки.

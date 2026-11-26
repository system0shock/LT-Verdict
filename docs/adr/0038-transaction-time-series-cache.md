# ADR 0038: Кэш временных рядов по транзакциям и read-only маршруты ошибок для UI v4

Дата: 2026-11-26.

Статус: Accepted (утверждено владельцем 2026-11-26).

## Контекст

Начиная с `v0.3.0`, Web UI строит графики нагрузки только по суммарным бакетам прогона (`normalized-1s.ndjson` и `rollup-{10,30,60}s.ndjson`), которые отдаёт `GET /api/runs/{runId}/analyses/{analysisId}/buckets`.
На реальных прогонах, где участвуют десятки или сотни транзакций, суммарный график скрывает деградацию отдельных эндпоинтов:

1. На экране «Обзор» инженеру нужен главный график интенсивности (RPS) и отклика (`p50`/`p95`/`p99`) с переключением между общим профилем и выборкой отдельных транзакций (до 8 кривых одновременно + фоновый слой остальных транзакций в стеке).
2. На экране «Глубокий анализ» в режиме свободного сравнения требуется сопоставлять ряды любых транзакций рядом с аппаратными рядами платформы и плеч.
3. Артефакт `error-groups.json` ([ADR 0031](0031-error-groups-artifact.md)) уже вычисляется при анализе и попадает в HTML/CLI-отчёты, но не отдаётся локальным HTTP API для плашки ошибок в Web UI.

Ограничения решения:

1. `identity.json`, `analysis-result.json`, `analysis_id`, канонический JSON и существующие схемы `docs/contracts/` **не изменяются**.
2. Решение должно работать ретроактивно для уже сохранённых прогонов, если в каталоге `runs/<runId>/inputs/source.bin` присутствует исходный файл JMeter/Gatling.
3. Новые внешние JVM-зависимости не добавляются; используются существующий потоковый парсер входов (`LoadInputReader`) и `PackedHistogram` (`HdrHistogram`).
4. Ограничения памяти и времени ответа соблюдаются на прогонах с 100+ транзакциями и длительностью до 8+ часов.

## Решение

### D1. Производный кэш рядов транзакций вне `identity` и `analysis-result.json`

На уровне каталога прогона `runs/<runId>/` вводится лениво вычисляемый производный файл кэша:

- Путь: `runs/<runId>/tx-rollups-v1.ndjson` (вне `manifest.json` анализов и вне `identity.json`).
- Источник: неизменяемый файл `runs/<runId>/inputs/source.bin` (читается через `AnalysisStore.requireInput(runId)`).
- Формат: NDJSON-строки со схемой `tx-rollup.v1` для укрупнений `rollup` ∈ `{10, 30, 60}` с (и `1` с, если длительность прогона ≤ 15 мин):
  - `tx_key`: канонический ключ транзакции (`label`, `group_path`, `sample_kind`), сопоставимый с `metric_summary.scope` в `analysis-result.json`;
  - `rollup_seconds`: `1 | 10 | 30 | 60`;
  - `bucket_start_ms`: смещение от начала прогона;
  - `sample_count`, `error_count`, `p50_latency_ms`, `p95_latency_ms`, `p99_latency_ms`, `max_latency_ms`.
- Запись: атомарно через временный файл в каталоге `runs/<runId>/` с последующим `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` и пер-прогоновой блокировкой (чтобы параллельные HTTP-запросы не строили индекс дважды).
- Ограничение ресурсов: кэшируются до `MAX_CACHED_TRANSACTIONS = 512` транзакций (в пределах лимитов `MetricsConfig`); при отсутствии `inputs/source.bin` (например, синтетические тестовые прогоны без исходного файла) маршрут возвращает `404` с кодом `SOURCE_INPUT_UNAVAILABLE`, а UI штатно остаётся в режиме общего профиля без ошибок.

### D2. Маршрут `GET /api/runs/{runId}/analyses/{analysisId}/transaction-buckets`

В `RunRoutes.kt` добавляется read-only маршрут:

- Query-параметры:
  - `rollup`: обязательный, одно из `1`, `10`, `30`, `60`;
  - `tx`: от 1 до 8 повторяющихся параметров (идентификатор/метка транзакции; поддерживается передача `label` или канонического `tx_key`);
  - `from_ms`: необязательный, `>= 0` (по умолчанию `0`);
  - `to_ms`: необязательный, `> from_ms`;
  - `limit`: необязательный, `1..500` (по умолчанию `500`).
- Ответ (`200 OK`, `application/json`):

```json
{
  "rollup_seconds": 60,
  "series": [
    {
      "tx": "POST /api/v2/checkout/submit",
      "buckets": [
        {
          "bucket_start_ms": 0,
          "sample_count": 120,
          "error_count": 2,
          "p50_latency_ms": 420,
          "p95_latency_ms": 810,
          "p99_latency_ms": 1150,
          "max_latency_ms": 1490
        }
      ],
      "next_from_ms": null
    }
  ]
}
```

### D3. Маршрут `GET /api/runs/{runId}/analyses/{analysisId}/error-groups`

В `RunRoutes.kt` добавляется read-only маршрут, возвращающий уже существующий файл `error-groups.json` ([ADR 0031](0031-error-groups-artifact.md)) через существующий валидатор `readErrorGroupsFile(stored.path)`:

- Если артефакт присутствует и валиден: `200 OK` с JSON `error-groups.v1`.
- Если артефакт отсутствует (прогон без ошибок или анализ, созданный до ADR 0031): `200 OK` с `{ "available": false, "groups": [] }`, чтобы UI не получал лишних ошибок 404 в консоли браузера.

### D4. Модуль `BarChart` в ECharts (уточнение к ADR 0035)

В `ui/src/shell/TimeChart.vue` дополнительно регистрируется `BarChart` из уже установленного пакета `echarts@6.1.0` для столбчатых дорожек ошибок (`JTL errors`, `OpenSearch ERROR`).
Встроенный `TooltipComponent` ECharts по-прежнему **не** регистрируется из-за `style-src 'self'` ([ADR 0035](0035-ui-chart-library.md)); плавающая подсказка в стиле Grafana реализуется компонентом Vue (`ChartFloatingTooltip.vue`) с позиционированием через CSSOM `transform`.

## Последствия

- `identity.json`, `analysis-result.json`, `manifest.json` и `analysis_id` не меняются.
- Появляется небольшой кэш-файл `runs/<runId>/tx-rollups-v1.ndjson` только при первом обращении к потранзакционным графикам.
- Новые внешние зависимости в JVM и npm не добавляются.
- Ошибки протокола (`error-groups.v1`) и ошибки ПВС из логов (`opensearch_errors`) становятся доступны на экране «Обзор» в единой плашке.

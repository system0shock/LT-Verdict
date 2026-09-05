# Архитектура локального runtime Slice 1

**Статус:** implemented candidate

Этот документ описывает фактически реализованный local-only runtime Slice 1.
Нормативные решения находятся в [ADR 0002](../adr/0002-slice-1-runtime-filesystem-security.md)
и [ADR 0003](../adr/0003-policy-v1-metrics-evidence.md).

## Один процесс и один analytical core

```text
Vue 3 UI ──same-origin HTTP──> Ktor/Netty loopback shell
                                  │
CLI ──────────────────────────────┼──> Kotlin application core
                                  │      parsers · metrics · policy gate
                                  └──> filesystem RunBundle
                                         immutable input · atomic analysis
```

`ltv ui`, `ltv analyze` и `ltv policy validate` запускаются из одной JVM
distribution. CLI вызывает application core напрямую; Web UI обращается к тому
же core через private loopback API. HTTP/session state не входит в parsers,
metrics или canonical result.

Runtime не содержит database, broker, outbound HTTP/DNS client или server-mode
bind. Сервер слушает выбранный OS port только на `127.0.0.1` и либо открывает
его в браузере, либо печатает URL, если browser integration недоступна.

## Data directory и RunBundle

По умолчанию используется `${user.home}/.lt-verdict`; `--data-dir` задаёт другой
каталог. Один exclusive file lock допускает только один writer process на data
directory. Конкурирующий CLI/UI process получает `DATA_DIR_BUSY` до любой
мутации.

```text
<data-dir>/
├── .ltv.lock
├── .staging/<generated-uuid>/
└── runs/<run-id>/
    ├── source.json
    ├── inputs/source.bin
    └── analyses/<analysis-id>/
        ├── identity.json
        ├── run.json
        ├── analysis-result.json
        ├── normalized-1s.ndjson
        ├── rollup-10s.ndjson
        ├── rollup-30s.ndjson
        ├── rollup-60s.ndjson
        └── manifest.json
```

Для recognized, но invalid input analysis содержит только применимые artifacts:
canonical identity, result и manifest. HTTP upload сначала потоково пишется во
временный OS file, а accepted RunBundle и незавершённые analyses — под
`.staging`. Каждый published artifact принудительно сбрасывается, после чего
каталог публикуется same-filesystem `ATOMIC_MOVE`. `manifest.json` записывается
последним и хранит размер и SHA-256 каждого analysis artifact. Cached analysis
используется лишь после полной проверки manifest.

Filename остаётся metadata; internal paths генерирует приложение. Symlinks,
небезопасные компоненты пути и не принадлежащие приложению staging entries не
следуют и не удаляются.

## Детерминированная идентичность

`run_id` состоит из detected source type и lowercase SHA-256 полных input bytes.
Одинаковые bytes одного типа переиспользуют immutable accepted input.

`analysis_id` — lowercase SHA-256 canonical `analysis-identity.v1`. Identity
включает `run_id`, input hash/type, canonical policy hash либо `NO_POLICY`,
версии engine/parsers/modules/contracts, normalization и histogram settings, а
также result-affecting ceilings. Timestamps, provenance и UI state в identity не
входят. Изменение policy или любой result-affecting настройки создаёт новый
analysis directory и не перезаписывает прежний результат.

Canonical `analysis-result.v1` одинаков для CLI и UI при одинаковых input,
policy и engine configuration.

## Двухпроходная нормализация

Каждый supported input читается потоково дважды без event spool:

1. первый pass полностью валидирует input и фиксирует общее окно
   `min(start)..max(end)`;
2. второй pass повторно проверяет те же границы и diagnostics, затем строит
   metrics, exact transaction summaries и sparse one-second buckets.

Расхождение pass results завершает analysis fail-closed. Empty/unsupported
upload отклоняется до analysis; recognized malformed input даёт
`INVALID + NO_VERDICT`. Ни один из этих случаев не может дать partial `PASS`.

Sparse bucket хранит offset от начала run, sample/error counts, maximum latency
и mergeable HdrHistogram. Отсутствующая секунда остаётся отсутствующей, а не
нулевой. Rollups 10/30/60 seconds собираются из one-second histograms; готовые
percentiles не усредняются.

## Executor, admission и terminal retention

Upload потоково записывается вне analysis executor. CPU-intensive parsing и
metrics выполняются на bounded pool обычных JVM platform threads, не на Netty
request threads.

- default parallelism: `1`;
- `--analysis-parallelism <n>`: от `1` до числа доступных processors;
- queue capacity равна parallelism;
- переполнение возвращает `BUSY`, accepted input сохраняется;
- cancel прерывает незавершённый analysis и удаляет только его staging files;
- хранятся последние `1 024` terminal job statuses.

Каждый analysis использует собственные mutable accumulators. Эта граница даёт
локальный multithreaded execution без shared histogram state; horizontal
server scaling остаётся отдельной будущей задачей.

## Private loopback API

API является внутренним контрактом Slice 1 и не обещает remote/server
compatibility.

```text
GET    /api/bootstrap
GET    /api/runs?after=<run-id>&limit=1..100
GET    /api/runs/<run-id>/analyses?after=<analysis-id>&limit=1..100
POST   /api/inputs
POST   /api/policies/validate
POST   /api/jobs
GET    /api/jobs/<job-id>
DELETE /api/jobs/<job-id>
GET    /api/runs/<run-id>/analyses/<analysis-id>/result
GET    /api/runs/<run-id>/analyses/<analysis-id>/buckets
GET    /api/runs/<run-id>/analyses/<analysis-id>/report?format=json|html
```

Runs выдаются максимум по `100`, buckets — по `500`; bucket range читается
потоково. `from_ms` — inclusive offset от начала run, `to_ms` — exclusive;
доступны rollups `1`, `10`, `30` и `60` seconds. Response дополняет bucket
вычисленным `p95_latency_ms`. Job state имеет значения `QUEUED`, `PROCESSING`,
`COMPLETE`, `FAILED` и `CANCELLED`. Upload ограничен 4 GiB, policy — 1 MiB.

Handled failures используют envelope
`{"error":{"code":"...","message":"...","details":[]}}`: malformed request
`400`, local security `403`, missing object `404`, `BUSY` `409`, size overflow
`413`, wrong media type `415`, unsupported input `422`. Structural policy
validation возвращает отдельный `{valid:false,errors:[...]}`.

## Просмотр и экспорт сохранённого analysis

Private API выдаёт analyses принятого run с cursor pagination по id,
default limit `25`, maximum `100`. Summary содержит `analysis_id`,
`policy_sha256`, `policy_verdict` и `run_validity`. Возвращаемые analyses
проходят существующую manifest validation. UI хранит выбранный analysis
отдельно от transient job state и читает уже опубликованные artifacts.

Vue отображает три SVG над текущей страницей buckets: RPS, errors/bin и
P95/ms. Relative-time axis общая; gaps разрывают линии. Нового aggregation
pipeline нет: rollups и P95 предоставляет существующий backend.

`ltv report` и private report endpoint используют один чистый HTML renderer
над сохранённым result. JSON возвращается исходными bytes. HTML содержит
escaped acquired text и встроенный CSS с SHA-256 hash в meta CSP; scripts,
remote assets, forms и acquired markup не исполняются. HTTP export отдаётся
как attachment с генерируемым именем по analysis id. Renderers не изменяют
canonical result, identity или manifests. Новых dependencies не добавлено.

## Security boundary

При установке local API процесс создаёт отдельные random 256-bit session и CSRF
tokens. Bootstrap передаёт их browser flow: session находится в
`HttpOnly; SameSite=Strict; Path=/` cookie, CSRF token — только в памяти
страницы. Каждый request проверяет exact
`Host: 127.0.0.1:<port>`; каждый `POST`/`DELETE` дополнительно требует exact
Origin, session cookie и `X-LTV-CSRF`. CORS не включается.

Каждый response получает restrictive CSP, `nosniff`, `no-referrer` и
`no-store`. UI не использует `v-html`, `localStorage`, `sessionStorage`, CDN или
outbound resources. JMeter XML разбирается JDK StAX с отключёнными DTD,
external entities и filesystem/network resolution.

Полный перечень result-affecting ceilings и crash-durability границ закреплён в
[ADR 0002](../adr/0002-slice-1-runtime-filesystem-security.md). Семантика policy,
exact transaction identity, verdict precedence и evidence закреплены в
[ADR 0003](../adr/0003-policy-v1-metrics-evidence.md).

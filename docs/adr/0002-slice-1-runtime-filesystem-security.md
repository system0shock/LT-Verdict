# ADR 0002 — runtime, filesystem и local security Slice 1

**Дата:** 2026-08-31

**Статус:** Accepted

## Контекст

Slice 1 должен дать один локальный CLI/Web flow без обязательной database и
без исходящей сети. До production code необходимо зафиксировать runtime,
зависимости, границы компонентов, безопасную запись immutable RunBundle,
ограниченную конкурентность и private loopback API. Production CSV pipeline
принят по результатам gate Task 2.

## Решение

### Компоновка и execution model

- Runtime реализуется одним Gradle project, без multi-project decomposition.
- Собранные Vue resources встраиваются в JVM distribution и раздаются Ktor;
  CDN и runtime-загрузка frontend assets не используются.
- CLI вызывает application core напрямую. Web UI вызывает тот же core через
  private Ktor API; API не является public contract Slice 1.
- CPU-intensive parsing и metric calculation выполняются в одном bounded
  platform-thread `ThreadPoolExecutor`, а не на Ktor/Netty request threads.
  Local default parallelism равен `1`, допустимая настройка — от `1` до
  `Runtime.getRuntime().availableProcessors()`, ёмкость очереди равна выбранному
  parallelism. Virtual threads и coroutines не заявляются.
- Один короткий process-local write mutex защищает согласованность
  accept/commit/list. Parsing и metric calculation выполняются вне mutex.
- Upload streaming не занимает analysis executor.

### Dependency ledger

| Role | Pin | Решение |
| --- | --- | --- |
| JVM | Temurin/OpenJDK 21 | Runtime baseline |
| Build | Gradle `9.5.0` | Верхняя fully-supported граница Kotlin 2.4.10; wrapper SHA-256 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` |
| Language | Kotlin `2.4.10` | JVM and serialization Gradle plugins |
| HTTP | Ktor BOM/server `3.5.2` | Core, Netty, content negotiation, JSON, test host |
| JSON | kotlinx.serialization `1.11.0` | Runtime contracts and canonical JSON input tree |
| Metrics | HdrHistogram `2.2.2` | `PackedHistogram`, без самописного percentile code |
| CSV parser | uniVocity parsers `2.9.1` | Один production parser; streaming quote-parity `Reader` отклоняет unmatched quote на EOF |
| Logging | slf4j-simple `2.0.18` | Один local-process backend |
| JVM tests | JUnit BOM `6.1.3` | Jupiter engine and assertions |
| Kotlin lint | ktlint Gradle plugin `14.2.0` | Только build-time |
| JS runtime | Node `24.14.0`, npm `11.9.0` | CI and lockfile baseline |
| UI | Vue `3.5.42` | Composition API без router/store |
| UI build | Vite `8.2.2`, `@vitejs/plugin-vue` `6.0.8` | Только local bundled assets |
| Types | TypeScript `6.0.2`, vue-tsc `3.3.11` | TypeScript pin остаётся в поддерживаемом typescript-eslint диапазоне |
| UI lint | ESLint `10.9.1`, typescript-eslint `8.68.0`, eslint-plugin-vue `10.10.0` | Включает `vue/no-v-html` и storage bans |
| Browser tests | `@playwright/test` `1.62.1`, `@axe-core/playwright` `4.13.0` | Chromium flow, security и accessibility |
| Schema test | Ajv `8.18.0` | Dev-only проверка policy schema/examples; в browser не поставляется |

uniVocity сначала подключён как `testImplementation`. Прямая конфигурация с
`UnescapedQuoteHandling.RAISE_ERROR` прошла четыре случая, но приняла поле с
незакрытой quote на EOF. Исходный код 2.9.1 подтверждает, что
`consumeValueOnEOF()` завершает такое значение и штатной strict option нет.

План дополнен минимальным streaming `Reader`: он меняет parity для каждого
сырого `"` и бросает `IOException` на EOF при нечётном количестве. uniVocity
остаётся единственным CSV parser; input не удерживается и вторая library не
добавляется. Повторный gate проверил quoted comma, escaped quote, embedded
newline, одинаковую семантику LF/CRLF без trimming, fail-closed для незакрытой
quote, пределы 64 columns и 64 KiB на field, а также 1 000 000 rows с
`-Xmx256m`, одним fork и без retention rows. Команда
`.\gradlew.bat --no-daemon csvSpike` завершилась успешно за 9 секунд по выводу
Gradle. После gate та же coordinate перенесена в `implementation`.

### Data directory, lock и immutable layout

Default data directory задаётся ровно как
`Path.of(System.getProperty("user.home"), ".lt-verdict")`; CLI option может
передать другой root.

Каждый writer, включая `ltv ui` и `ltv analyze`, до первой мутации получает
один exclusive lock `<data>/.ltv.lock` и держит его весь process lifetime.
Проигравший writer получает `DATA_DIR_BUSY` до любой записи. Lock не заменяет
process-local mutex: lock исключает другой process, mutex сериализует короткие
операции текущего process.

Неизменяемая layout:

```text
<data>/.ltv.lock
<data>/.staging/
<data>/runs/<run_id>/inputs/source.bin
<data>/runs/<run_id>/source.json
<data>/runs/<run_id>/analyses/<analysis_id>/identity.json
<data>/runs/<run_id>/analyses/<analysis_id>/run.json
<data>/runs/<run_id>/analyses/<analysis_id>/analysis-result.json
<data>/runs/<run_id>/analyses/<analysis_id>/normalized-1s.ndjson
<data>/runs/<run_id>/analyses/<analysis_id>/rollup-10s.ndjson
<data>/runs/<run_id>/analyses/<analysis_id>/rollup-30s.ndjson
<data>/runs/<run_id>/analyses/<analysis_id>/rollup-60s.ndjson
<data>/runs/<run_id>/analyses/<analysis_id>/manifest.json
```

Accepted input и completed analysis не перезаписываются. Порядок ingest
фиксирован: generated UUID staging path; streaming запись с одновременными
SHA-256 и limit checks; content detection; вычисление identity; atomic accept.
Формат определяется bounded content/signature checks, а не filename или
extension. Filename остаётся metadata и никогда не становится filesystem path.

`run_id` имеет вид
`"<source-type>-<full-lowercase-input-sha256>"`, где `source-type` — один из
`jmeter_jtl_csv`, `jmeter_jtl_xml`, `gatling_text`, `gatling_binary`.
Существующий run переиспользуется только после проверки его input metadata и
bytes.

Каждая failed/cancelled operation удаляет в `finally` только собственный точный
UUID staging path, включая size overflow, unsupported input, writer exception
и failed move. Startup cleanup служит только crash fallback. На
`DataDirectory.open`, уже удерживая exclusive lock, приложение:

- отвергает symlink для app-owned `.ltv.lock`, `.staging`,
  `runs`, а также любой traversed app-owned path;
- проверяет, что real `.staging` расположен непосредственно под real data root;
- просматривает только direct children `.staging`;
- удаляет без следования symlinks только non-symlink children, чьи имена имеют
  generated UUID format;
- сохраняет symlinks, не-UUID entries и всё вне точного app-owned subtree.

Каждый staged file получает `force(true)` до same-filesystem `ATOMIC_MOVE`.
Если atomic move не поддерживается, операция завершается fail-closed без
неатомарного fallback. Parent-directory fsync выполняется там, где это
разрешают JDK и OS. На Windows JDK не даёт переносимой гарантии fsync directory
entry; эта crash-durability граница сохраняется явно и не ослабляет требование
atomic move.

`manifest.json` completed analysis записывается последним и перечисляет каждый
analysis artifact, кроме самого self-referential manifest: relative path, byte
size и lowercase SHA-256. Каждый path должен быть normalized descendant ровно
этого analysis directory и не проходить через symlink. Cached analysis
возвращается только после проверки path, size и SHA-256 каждой manifest entry;
любое расхождение запрещает reuse.

### Resource ceilings

Result-affecting ceilings фиксированы и при превышении дают
`RESOURCE_LIMIT_EXCEEDED` fail-closed без partial PASS:

| Ресурс | Предел |
| --- | ---: |
| Input | 4 GiB (`4_294_967_296` bytes) |
| Policy read | 1 MiB (`1_048_576` bytes), чтение `limit + 1` до выделения полного byte array |
| Filename | 255 bytes |
| CSV columns | 64 |
| Text field | 64 KiB |
| Text line или binary blob | 1 MiB |
| UTF-8 label | 4 KiB |
| Hierarchy/XML depth | 64 |
| UTF-8 exact transaction identity | 64 KiB |
| Distinct transactions | 10 000 |
| Total retained transaction-identity bytes | 64 MiB (`67_108_864` bytes) |
| Non-empty one-second buckets | 100 000 |
| Gatling cache entries | 65 536 |
| Total decoded Gatling cache strings | 64 MiB (`67_108_864` bytes) |
| Policy JSON depth | 16 |
| Policy rules | 256 |
| UTF-8 `policy_id` или rule `id` | 128 bytes |
| UTF-8 transaction scope | 4 KiB |
| Numeric token | 64 ASCII bytes |
| Absolute exponent | 64 |
| Canonical decimal expansion | 128 bytes |

Non-result process/API caps: 1 024 retained terminal job statuses, 100 runs per
page и 500 buckets per page. Эти caps не входят в `analysis-identity.v1`.

### Private loopback HTTP contract

Server bind выполняется ровно на `127.0.0.1` с выбранным OS port. Private
request/response contract:

```text
GET    /api/bootstrap -> 200 {csrf_token,max_upload_bytes}
GET    /api/runs?after=<run_id>&limit=1..100
       -> 200 {runs:[{run_id,source_type,sha256,size_bytes,original_filename}],next_after}
POST   /api/inputs -> 201 {run_id,source_type,sha256,size_bytes,original_filename}
POST   /api/policies/validate -> 200 {valid:true,policy,sha256}
                                422 {valid:false,errors:[{code,json_pointer,message}]}
POST   /api/jobs multipart(run_id, policy?) -> 202 JobStatus
GET    /api/jobs/{jobId} -> 200 JobStatus
DELETE /api/jobs/{jobId} -> 200 JobStatus
GET    /api/runs/{runId}/analyses/{analysisId}/result -> 200 analysis-result.v1
GET    /api/runs/{runId}/analyses/{analysisId}/buckets
       ?rollup=1|10|30|60&from_ms=<inclusive>&to_ms=<exclusive>&limit=1..500
       -> 200 {buckets:[...],next_from_ms:<integer|null>}
```

Run list сортируется по `run_id`; `after` exclusive, default `limit` равен 100,
а `next_after` содержит последний returned id только при наличии следующего
item. `JobStatus` имеет форму
`{job_id,state,processed_bytes,total_bytes,run_id,analysis_id,diagnostic}`:
`analysis_id` nullable; `diagnostic` равен `null` либо
`{code,message,source_offset}` с nullable `source_offset`; `state` —
`QUEUED|PROCESSING|COMPLETE|FAILED|CANCELLED`.

Bucket offsets неотрицательны и отсчитываются от run start. `from_ms` по
умолчанию равен `0`, `to_ms` optional и exclusive, default `limit` равен 500,
`next_from_ms` — start первого omitted bucket. Range read потоково читает
выбранный NDJSON и останавливается после первой omitted row.

Все остальные failures используют envelope
`{"error":{"code":"...","message":"...","details":[]}}` и statuses:

- `400` — malformed JSON, multipart или query;
- `403` — Host, Origin, session или CSRF failure;
- `404` — unknown run, job или analysis;
- `409` — `BUSY`;
- `413` — upload или policy size overflow;
- `415` — wrong content type;
- `422` — unsupported input.

При первом `/api/bootstrap` process создаёт одну random 256-bit in-memory
server session и отдельный random 256-bit CSRF token. Session cookie имеет
`HttpOnly; SameSite=Strict; Path=/`; CSRF token остаётся только в page memory.
Каждый HTTP request отвергает `Host`, отличный от фактического
`127.0.0.1:<bound-port>`. Каждый `POST`/`DELETE` дополнительно требует exact
соответствующий `Origin`, session cookie и `X-LTV-CSRF`. CORS не устанавливается.

Каждый response устанавливает:

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Cache-Control: no-store
```

Runtime не содержит outbound HTTP/DNS client и не выполняет исходящих
requests.

### CLI exit codes

| Exit | Значение |
| ---: | --- |
| `0` | `PASS`, `NO_POLICY` или valid `policy validate` |
| `2` | `FAIL` |
| `3` | `NO_VERDICT` или `DEGRADED` |
| `4` | Invalid/unsupported input |
| `5` | Invalid policy |
| `6` | `DATA_DIR_BUSY` |
| `64` | Usage |
| `70` | Unexpected internal failure |

## Альтернативы

- Несколько Gradle modules или services отклонены: один local flow не требует
  отдельного deployment или abstraction boundary.
- Database/filesystem index отклонены: bounded filesystem scan достаточен для
  Slice 1.
- Неатомарный move fallback отклонён из-за риска принять partial RunBundle.
- Virtual threads/coroutines отклонены как неподтверждённое ускорение CPU work.
- Вторая CSV library отклонена: gate выбирает ровно один parser.

## Последствия

- Один data directory допускает только один writer process; это сознательная
  local safety boundary.
- Accepted inputs и completed analyses проверяемы по hashes и не изменяются на
  месте.
- Private API и process-local jobs не создают server compatibility promise.
- CSV pipeline принят после gate Task 2; quote-parity guard является частью
  fail-closed parsing boundary.

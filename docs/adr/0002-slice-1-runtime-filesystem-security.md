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

## Дополнение 2026-09-30: чтение не перехэширует сохранённые байты

Статус: Accepted, 2026-09-30 (решение владельца; отдельная проверка целостности
по запросу не нужна): лишние SHA-256 не нужны в безопасном тестовом контуре. Причина: проверка
`requireInput` заново читала и хэшировала весь `inputs/source.bin` (до 4 GiB) на
каждом чтении, в том числе для каждой строки `GET /api/runs` (до 100) и для
каждого анализа страницы `listAnalyses`, под `operationLock`; чтение анализа
дополнительно хэшировало все его артефакты (`normalized-1s.ndjson`, rollups,
source-response и т. д.). Замер на 3 входах по 256 MiB и 10 анализах по 48 MiB:
`listRuns` 603 -> 2 ms, `listAnalyses` 2628 -> 28 ms, `readAnalysis` 239 -> 2 ms.

Изменяет разделы «Data directory, lock и immutable layout» и «Последствия»:

- Не изменяется: `run_id = "<source-type>-<sha256 входа>"` и его вычисление
  один раз при приёме файла; `analysis_id` и identity анализа; хэши, размеры и
  формат `manifest.json`, вычисляемые при записи анализа; проверка
  `analysis_id == sha256(identity.json)`; проверка hash документов `run.json`,
  result и identity в history scan; побайтовое сравнение при повторном приёме
  уже сохранённого run.
- Изменяется: чтение, список и открытие run проверяют существование, тип файла
  (не symlink, не special), форму и каноничность `source.json`, соответствие
  `run_id = <source-type>-<sha256>` метаданным и `size_bytes` входа, но не
  пересчитывают SHA-256 `source.bin`. Чтение analysis проверяет каноничный
  manifest, `analysis_id == sha256(identity.json)`, привязку identity к run,
  совпадение набора путей и размеров файлов с manifest, но не пересчитывает
  SHA-256 артефактов; поэтому фраза выше «только после проверки path, size и
  SHA-256 каждой manifest entry» теперь означает path и size.
- Последствие: гарантия «одинаковый `run_id` означает одинаковые байты входа» и
  «cached analysis равен записанному» при чтении больше не проверяется и
  остаётся допущением доверенного локального контура (данные, которые пишет
  только процесс LT Verdict под exclusive lock). Тихая порча диска или ручная
  правка файла с сохранением размера при чтении не обнаруживается: результат
  может быть построен на изменённых байтах, а `run_id` не выдаст различия. Это
  сознательное ослабление защиты от порчи данных; если контур перестанет быть
  доверенным, проверку нужно вернуть отдельным явным действием (например,
  `ltv verify`), а не на каждом чтении.

## Дополнение 2026-10-01: mutex не держится на время подготовки staging

Статус: Accepted, 2026-10-01 (решение владельца: «стоит решить»; находка
R4 независимого аудита надёжности). Причина: `acceptInput` держал
`operationLock` на всё время потоковой записи входа (до 4 GiB: запись, SHA-256,
fsync, определение формата) и побайтового сравнения при повторной загрузке, а
`writeAnalysisAtomically` держал его на запись staging, обход и fsync артефактов.
Всё это время `listRuns`, `requireInput`, чтение анализов и остальные операции
хранилища ждали. Это расходилось с требованием «один короткий process-local
write mutex» (раздел «Решение»).

Не изменяется: layout `<data>`, `run_id`, `analysis_id`, формат manifest,
fsync перед `ATOMIC_MOVE`, идемпотентность (тот же sha256 даёт тот же run, тот
же `analysis_id` даёт тот же анализ), отсутствие частично опубликованных
результатов, очистка собственного staging в `finally`.

Изменяется: под `operationLock` остаются только проверки открытого и
собственного каталога, создание пустого UUID-staging, проверка дубликата и
атомарная публикация (`Files.move` плюс fsync каталога) с проверочным чтением
метаданных. Запись входа, определение формата, запись staging анализа, hash и
fsync артефактов, манифест и побайтовое сравнение с уже сохранённым входом
выполняются вне mutex. Сравнение безопасно вне mutex: сохранённый
`inputs/source.bin` опубликованного run не изменяется, а код хранилища не
удаляет каталоги run.

Последствия:

- Два одинаковых входа или одинаковых анализа могут готовить staging
  одновременно. Первая публикация побеждает; вторая под mutex видит готовую
  цель, возвращает уже сохранённый run или анализ (анализ перечитывается и
  проверяется) и удаляет свой staging. Прежняя ошибка
  `analysis target appeared during publish` для такого законного дубликата
  больше не возникает.
- Закрытие каталога данных (`DataDirectory.close`) больше не ждёт завершения
  идущей записи. Публикация проверяет `requireOpen` под mutex и после закрытия
  завершается ошибкой `DATA_DIR_CLOSED`, staging удаляется в `finally`.
  Ограничение: если после `close` каталог сразу открыт заново (другой процесс
  или перезапуск), его startup cleanup может удалить ещё используемый staging;
  тогда запись завершается ошибкой ввода-вывода раньше `requireOpen`, а
  неудалимый открытый файл может помешать открытию. Публикации при этом не
  происходит. В штатном завершении приложения задачи останавливаются раньше
  закрытия каталога, поэтому сценарий принят как граничный.
- Пока идёт запись staging, на диске временно лежит один дополнительный
  экземпляр входа на каждую параллельную загрузку (как и раньше, но теперь
  загрузки не сериализуются).

## Дополнение 2026-10-05: `advisory_ai` в `GET /api/bootstrap`

Статус: Accepted, 2026-10-05 (решение ADR
[0023](0023-advisory-ai-consent-removal-and-model-config.md), Д3, п. 5; срез CM2).

Закрытый ответ `GET /api/bootstrap` из раздела «Private loopback HTTP contract»
(`{csrf_token,max_upload_bytes}`) получает аддитивное поле `advisory_ai`:
`{default_model_id, models:[{id,label,measured}]}` либо `null`. Адрес endpoint
и его подпись в ответ не входят (поле `endpoint_label`, добавленное срезом CM2,
убрано срезом CM5 по решению владельца 2026-10-06, ADR 0023, «Поправка
2026-10-06»). Существующие поля и их смысл не
менялись. Подробности и правила: `docs/user/advisory-ai.md`, раздел «Файл
конфигурации моделей».

## Дополнение 2026-10-07: время приёма входа и порядок списка прогонов

Статус: Accepted, 2026-10-07 (решение владельца, вариант «а» по итогам демо-прохода).
Причина: `original_filename` берётся из имени загруженного файла (у Jenkins это
базовое имя артефакта), поэтому у разных прогонов оно часто одно (`load.jtl`), а
`GET /api/runs` сортировал по `run_id`, то есть по хэшу содержимого: новый прогон
попадал в случайное место списка.

Изменение публичного контракта (аддитивное):

- `<data>/runs/<run_id>/source.json` получает необязательное поле `accepted_at`:
  момент приёма входа, UTC, строго в виде `uuuu-MM-ddTHH:mm:ss.SSSZ` (миллисекунды,
  суффикс `Z`; например `2026-10-07T12:00:00.000Z`). Допустимы два набора ключей:
  прежние пять (`original_filename`, `run_id`, `sha256`, `size_bytes`,
  `source_type`) и они же плюс `accepted_at`. Значение, которое не разбирается или
  не сериализуется обратно в ту же строку, считается порчей хранилища
  (`CORRUPT_RUN_BUNDLE`, как и прочее несоответствие каноничной формы).
- `GET /api/runs` и `POST /api/inputs` (а также объект `run` в ответе Jenkins
  `advance`/`collect`) возвращают `accepted_at`: строка либо `null` для прогона,
  сохранённого до появления поля. Остальные поля и их смысл не менялись.
- Порядок `GET /api/runs`: `accepted_at` по убыванию (новые первыми), затем
  `run_id` по возрастанию; прогоны без `accepted_at` идут после всех прогонов с
  ним, между собой по `run_id`. Курсор `after` остаётся `run_id` (`next_after` без
  изменений по форме): «после этого прогона в указанном порядке». Корректный по
  форме `after`, который не соответствует сохранённому прогону, даёт `400
  MALFORMED_REQUEST`: его место в порядке неизвестно (раньше принимался любой
  корректный id как нижняя граница по строке).

Что не меняется. `accepted_at` не входит ни в `run_id`, ни в `analysis_id`, ни в
identity и артефакты анализа; `run_id = <тип>-<sha256 входа>` вычисляется как
прежде. Повторная загрузка тех же байт ничего не перезаписывает: сохраняется
первая запись, а с ней и первый `accepted_at` (имя файла первой загрузки тоже).
Уже записанные каталоги данных без поля остаются валидными и не мигрируются; их
`accepted_at` не восстанавливается повторной загрузкой (принятый вход
неизменяем).

Цена. Чтобы упорядочить прогоны по времени, `listRuns` теперь читает
`source.json` каждого каталога `runs` (до сих пор читались только строки
страницы), сортирует в памяти и целиком проверяет только возвращаемую страницу.
Для упорядочения нечитаемый или некорректный `accepted_at` считается
отсутствующим: порча одного прогона не ломает страницы, на которых его нет, как
и раньше. Объём файла около 250 байт; цена растёт линейно с числом прогонов.

Не входит в срез: пользовательская метка прогона и номер сборки Jenkins.

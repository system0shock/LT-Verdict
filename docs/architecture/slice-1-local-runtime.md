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
        ├── policy.json              # только если анализ запущен с политикой
        ├── run.json
        ├── analysis-result.json
        ├── normalized-1s.ndjson
        ├── rollup-10s.ndjson
        ├── rollup-30s.ndjson
        ├── rollup-60s.ndjson
        └── manifest.json
```

Для recognized, но invalid input analysis содержит только применимые artifacts:
canonical identity, result, `policy.json` (если задана политика) и manifest. HTTP upload сначала потоково пишется во
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
также result-affecting ceilings. Время создания, transport provenance и UI state
в identity не входят. Изменение policy или любой result-affecting настройки создаёт новый
analysis directory и не перезаписывает прежний результат.
Версия общего модуля `metrics` в identity равна `2` (ADR 0016: перцентили не выше
максимума); analyses с прежней версией `1` читаются без перезаписи, но для baseline и
истории динамики считаются несопоставимыми с новыми.
Версия модуля `load-resource-diagnostics` равна `3` (ADR 0016: пустое окно как
`null`, было `2`); она входит в identity только при наличии плана диагностики.
Parser `jmeter-csv` имеет версию `2`, а `input_versions.source` для JMeter CSV -
`jmeter-jtl-csv.v2` (ADR 0016: parent-строки Transaction Controller; было `1` и
`v1`); parser XML и Gatling остаются на `1`. Этим срезом ADR 0016 завершён:
изменения identity всех трёх срезов (`metrics` 2, `load-resource-diagnostics` 3,
CSV parser 2) выпускаются вместе и требуют одного повторного закрепления baseline.
Анализ с политикой добавляет в identity объект `verdict_gates` (строковые значения
`min_samples_floor`, `min_samples_default`, `throughput_exempt`: запасные константы
проверки минимума выборки бизнес-правил, ADR
[0018](../adr/0018-policy-platform-rules-small-samples.md)). Блок не входит в ключ
сопоставимости baseline; фактические `defaults` и `min_samples` политики входят в
`policy_sha256`. Тот же вход и тот же файл политики получают новый `analysis_id`;
сохранённые анализы не переписываются. Анализ без политики identity не меняет.
Проверка выборки выполняется в `evaluatePolicy` для каждого бизнес-правила по счёту
запросов в его области в оцениваемом окне; `SMALL_SAMPLE` (информационная причина)
даёт `analysis_coverage.status = INCOMPLETE`, но не меняет `PASS`/`FAIL`, а
`INSUFFICIENT_SAMPLES` блокирует вердикт (`NO_VERDICT`).

Необязательное поле `window_ids` бизнес-правила (ADR 0018, решение D5) привязывает
правило к именованным окнам снимка ресурсов. В оцениваемом окне `evaluatePolicy`
берёт только применимые правила: без поля правило действует во всех окнах, с полем
только в названных. Если применимых правил нет, бизнес-часть окна получает
`NO_POLICY`, а не пустой `PASS`, и пустое окно не превращается в
`BUSINESS_OBSERVATIONS_NOT_FOUND`. Существование id проверяет
`evaluateSharedWindowPolicy` при сопоставлении со снимком: неизвестный id даёт
evidence `rule_window_check` (`rule_id`, `window_id`, `status = NO_VERDICT`,
`reason_code = RULE_WINDOW_NOT_FOUND`) и блокирует общий вердикт, поэтому опечатка в
id не даёт молчаливого `PASS`. Анализ без снимка окон не имеет: правило с
`window_ids` даёт `policy_check` со статусом `NO_VERDICT` и той же причиной. Identity
и `analysis_id` анализов без `window_ids` не меняются; политика с `window_ids` имеет
другой `policy_sha256` и потому другой `analysis_id`.

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

### Перцентили и точность гистограммы

HdrHistogram при трёх значащих цифрах хранит значения до 2047 мс точно, а выше
возвращает верхнюю границу эквивалентного диапазона (например, 2048 мс даёт
2049 мс, 60000 мс даёт 60031 мс; относительное превышение меньше `1/1024`).
Поэтому опубликованные p50, p95 и p99 ограничиваются фактическим максимумом
записанных сэмплов (ADR [0016](../adr/0016-metric-semantics-percentile-empty-window-jmeter-parents.md)):

```text
published_p(q) = min(histogram.getValueAtPercentile(q), observed_max_latency_ms)
```

Для непустой выборки выполняется `p50 <= p95 <= p99 <= max_latency_ms`.
Правило действует в summary (общая сводка, транзакции, окна, ячейки UTC load,
policy и диагностическое evidence), в `p95_latency_ms` ответа bucket API и в
статическом графике. Bucket API и график берут максимум из поля
`max_latency_ms` той же строки, а не из `histogram.maxValue`, и применяют правило
к любому сохранённому analysis, в том числе созданному раньше: неизменяемое
evidence старого analysis при этом не переписывается. Производный p95 строки
ниже необрезанного значения HDR той же гистограммы менее чем на 0,1 %, а p95
в evidence относится к другой выборке (всему прогону или окну), поэтому два
числа могут различаться и по этой причине. Ограничение не делает оценку
точной: если максимум далеко от перцентиля, значение остаётся верхней границей
диапазона (для `100 × 60000 + 1 × 70000` p95 равен 60031 мс при точном
60000 мс), поэтому ложный `FAIL` у порога внутри диапазона остаётся возможным.

Точность `significantDigits` — настройка запуска процесса, не запроса API и не
policy: `--histogram-significant-digits <n>` у `ltv ui` и `ltv analyze`, целое
`3..5`, по умолчанию `3`; другое значение или повтор параметра завершают запуск
usage error (`64`). Значение входит в identity (`histogram.significant_digits`),
поэтому смена настройки даёт другой `analysis_id` и новый analysis; analyses с
разной точностью несопоставимы для baseline, эталон закрепляется заново. Версия
модуля `metrics` при смене настройки не меняется. Декодеры читают точность из
самого сжатого V2-кодирования. Значения строго меньше 2048, 32 768 и
262 144 мс для 3, 4 и 5 цифр хранятся точно, начиная с этих порогов относительное
превышение меньше `1/1024`,
`1/16384` и `1/131072`; ограничение максимумом действует при любой точности.

Оценка на модели (HdrHistogram 2.2.2, `PackedHistogram`, логнормальная
латентность до 86 400 000 мс), не замер продукта:

| Цифры | Занято при 1 000 сэмплов | Занято при 101 000 | Сжатый V2 при 101 000 |
| --- | --- | --- | --- |
| 3 | 2,8 КБ | 9,8 КБ | 3,2 КБ |
| 4 | 3,0 КБ | 19,0 КБ | 4,3 КБ |
| 5 | 3,0 КБ | 20,2 КБ | 4,3 КБ |

При больших выборках одной гистограммы 4 и 5 цифр занимают около 1,9 и 2,1 раза
больше памяти, чем 3, сжатая запись растёт примерно на 37 %. Число гистограмм
ограничено `MetricsConfig`, поэтому до включения значения выше 3 на стенде нужен
замер кучи и времени по методике ADR [0014](../adr/0014-resource-series-limits-autostep-arm-api.md).
Статический график отвергает строку гистограммы длиннее `393 216` символов
(было `262 144`): на модели 1 000 000 равномерных сэмплов по 0–86 000 000 мс
(до 86 000 с, как в тесте) при 5 цифрах даёт 289 552 символа (измерено тестом); для
диапазона 0–86 000 мс (до 86 с) те же сэмплы дают 63 536 символов. Предел длины
всей строки rollup остаётся `524 288` символов.

### Parent-строки JMeter CSV

Transaction Controller пишет в CSV свою строку рядом со строками дочерних
сэмплеров (ADR [0016](../adr/0016-metric-semantics-percentile-empty-window-jmeter-parents.md),
пометка в ADR 0003). Parent-строка — строка, у которой `responseMessage`
полностью равен `Number of samples in transaction : N, number of failing samples : M`
(ASCII-цифры, регистр и пробелы значимы) и `dataType` пуст; признак доступен,
только если в заголовке ровно по одному столбцу `responseMessage` и `dataType`
(иначе все строки — `JMETER_SAMPLER`, дубль даёт `INVALID_JMETER_CSV_HEADER`).
Режим один на файл; для его выбора `parseJtlCsv` перед основным проходом делает
предварительный просмотр (не сообщает прогресс и не даёт diagnostics): сначала
побайтовая проверка наличия литерала `Number of samples in transaction :` с пробелом
после двоеточия
(нет литерала — parent-строк нет, CSV-разбор не нужен), затем CSV-просмотр до
первой пары «parent и не-parent». Основной проход после этого помечает строки:

| Содержимое файла | Parent-строки | Остальные |
| --- | --- | --- |
| Есть parent и не-parent строки | `JMETER_CONTAINER` | `JMETER_SAMPLER` |
| Только parent-строки (`subresults=false`) | `JMETER_SAMPLER` | нет |
| Нет parent-строк | нет | `JMETER_SAMPLER` |

`JMETER_CONTAINER` не входит в overall, 1-секундные buckets, rollups и UTC load,
но образует точную сводку транзакции (как XML-контейнер); общее окно прогона
по-прежнему строится по всем строкам. Идентичность транзакции включает kind,
поэтому одинаковый label у контроллера и сэмплера даёт `AMBIGUOUS_TRANSACTION`.
Известные ограничения (смешанный файл при `subresults=false`, вложенные
контроллеры, сэмплер с пустым `dataType` и точным сообщением) описаны в
[пользовательской документации](../user/slice-1-local-analysis.md).

`run-period.json` JMeter CSV записывается с `recognition_method =
sample-timestamps.v2` (parent-строки не занимают секунды при поиске простоев),
остальные источники остаются на `sample-timestamps.v1`; сохранённый период CSV
с `v1` считается отсутствующим и пересчитывается.

## Executor, admission и terminal retention

Upload потоково записывается вне analysis executor. Лимит 4 GiB проверяется во
время копирования, а не после него: чтение останавливается на `limit + 1`
байте (в том числе для запроса без `Content-Length`), временный файл
удаляется, клиент получает `413 RESOURCE_LIMIT_EXCEEDED`. CPU-intensive parsing и
metrics выполняются на bounded pool обычных JVM platform threads, не на Netty
request threads.

- default parallelism: `1`;
- `--analysis-parallelism <n>`: от `1` до числа доступных processors;
- queue capacity равна parallelism;
- переполнение возвращает `BUSY`, accepted input сохраняется;
- cancel прерывает незавершённый analysis и удаляет только его staging files;
- отмена согласована с публикацией: worker под lock задач проверяет отмену и
  необратимо входит в публикацию непосредственно перед atomic move. До этой точки
  cancel возвращает `CANCELLED`, и результат не публикуется. После неё cancel не
  прерывает worker и ждёт его завершения не дольше 2 секунд; ответ содержит
  фактическое состояние (`COMPLETE`, при таймауте `PROCESSING`), но не `CANCELLED`;
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
GET    /api/jobs?state=active
GET    /api/jobs/<job-id>
DELETE /api/jobs/<job-id>
GET    /api/runs/<run-id>/analyses/<analysis-id>/result
GET    /api/runs/<run-id>/analyses/<analysis-id>/buckets
GET    /api/runs/<run-id>/analyses/<analysis-id>/report?format=json|html|asciidoc
```

Runs выдаются максимум по `100`, buckets — по `500`; bucket range читается
потоково. `from_ms` — inclusive offset от начала run, `to_ms` — exclusive;
доступны rollups `1`, `10`, `30` и `60` seconds. Response дополняет bucket
вычисленным `p95_latency_ms` (не выше `max_latency_ms` строки). Job state имеет значения `QUEUED`, `PROCESSING`,
`COMPLETE`, `FAILED` и `CANCELLED`. `GET /api/jobs?state=active` возвращает `{jobs:[JobStatus]}` для
задач `QUEUED` и `PROCESSING` в порядке принятия (не больше
`2 * parallelism`); любой другой query даёт `400 MALFORMED_REQUEST`
(ADR [0015](../adr/0015-list-active-analysis-jobs.md)). Upload ограничен
4 GiB, policy — 1 MiB.

Handled failures используют envelope
`{"error":{"code":"...","message":"...","details":[]}}`: malformed request
`400`, local security `403`, missing object `404`, `BUSY` `409`, size overflow
`413`, wrong media type `415`, unsupported input `422`. Structural policy
validation возвращает отдельный `{valid:false,errors:[...]}`.

## Resource snapshot и оконные SLA

[ADR 0005](../adr/0005-resource-window-sla.md) добавляет optional
`resource-snapshot.v1`: CLI `--resources` и multipart file `resource_snapshot`
в existing `POST /api/jobs`, рядом с `run_id` и optional `policy`.
Структура и load hash проверяются до job; привязка окна к parsed load run —
в первом проходе core. Структурные ошибки дают `422 INVALID_RESOURCES` с
`error.details` (code/json_pointer/message); превышение limits — `413`.
Неверные окна завершают job с безопасным diagnostic
`RESOURCE_WINDOW_OUTSIDE_RUN` или `RESOURCE_WINDOW_NO_FULL_CELLS`.
Более 10 000 resource threshold findings дают
`RESOURCE_FINDINGS_LIMIT_EXCEEDED` до публикации partial analysis.

Snapshot ограничен 32 MiB, 1 024 series и 1 500 000 cells (ADR 0014); multipart
job содержит не более 24 parts, declared общий body ограничен суммой максимальных
parts (`MAX_JOB_REQUEST_BYTES`, snapshot 32 MiB) и 64 KiB overhead. Core проверяет depth,
duplicate/unknown fields, numeric bounds и cardinality до помещения в queue.
Расчёты используют existing analysis worker и cooperative cancellation.

Для enriched analysis identity включает semantic snapshot/config hashes,
версии статистики и оконных SLA, limits. Grid/windows — result-affecting input;
transport-only provenance исключён из semantic hash. Raw snapshot сохраняется
в том же immutable analysis под manifest. Без snapshot identity/result прежние.
Бизнес-policy проверяется на тех же half-open windows, что resource SLA;
resource diagnostics не участвуют в общем verdict. Новые typed evidence
используют existing `analysis-result.v1` object slots. UI и reports читают эти
evidence без повторного вычисления статистик. VM/Grafana transport отсутствует.

## Просмотр и экспорт сохранённого analysis

Private API выдаёт analyses принятого run с cursor pagination по id,
default limit `25`, maximum `100`. Summary содержит `analysis_id`,
`policy_sha256`, `policy_id`, `policy_verdict` и `run_validity`. `policy_id`
(строка или `null`, ключ есть всегда) читается из сохранённой копии политики
`policy.json` вне замка хранилища: чтение ограничено 1 MiB, содержимое сверяется
с `policy_sha256` из identity, повторной валидации политики нет; несовпадение
хэша, размер сверх предела и неразборчивый JSON дают `null`, а не ошибку списка.
Пропавший `policy.json` или другой размер, чем в manifest, остаются повреждением
набора, как у любого artifact. `policy.json`
пишется только новыми analyses (канонические байты, давшие `policy_sha256`),
`analysis_id` и identity не меняются; прежние analyses остаются с `null`.
Возвращаемые analyses проходят существующую manifest validation. UI хранит выбранный analysis
отдельно от transient job state и читает уже опубликованные artifacts.

Vue отображает три SVG над текущей страницей buckets: RPS, errors/bin и
P95/ms. Relative-time axis общая; gaps разрывают линии. Нового aggregation
pipeline нет: rollups и P95 предоставляет существующий backend.

`ltv report` и private report endpoint используют чистые HTML и AsciiDoc renderers
над сохранённым result. JSON возвращается исходными bytes. HTML содержит
русские блоки «Вердикт и причины», «Правила», «Транзакции» и «Ограничения»
(слова причин берёт из `ReportReasons.kt`; набор кодов совпадает с
`ui/src/verdictReasons.ts`, `npm --prefix ui run test:contracts` проверяет
равенство), затем прежние английские разделы с `lang="en"` (корень `lang="ru"`).
Таблица транзакций ограничена 200 строками. HTML содержит
escaped acquired text и встроенный CSS с SHA-256 hash в meta CSP; scripts,
remote assets, forms и acquired markup не исполняются. HTTP export отдаётся
как attachment с генерируемым именем по analysis id. Renderers не изменяют
canonical result, identity или manifests. Новых dependencies не добавлено.
AsciiDoc отдаётся как `text/plain; charset=UTF-8` с именем `.adoc`; acquired
values — compact JSON tokens только в literal blocks с `specialchars`, а
canonical JSON сохраняет исходный текст. Это export, не создание job.

## Local baseline state и comparison

[ADR 0004](../adr/0004-local-baseline-selection.md) добавляет private
`<data>/baseline.json` (`local-baseline.v1`) вне immutable RunBundles.
RunBundleStore использует existing operation/data-directory locks, UUID staging,
forced write и atomic replacement. Сохранённые candidate references проходят
manifest/hash validation; ошибка не подменяет отсутствующий baseline.

Private API:

```text
GET    /api/baseline
POST   /api/baseline
DELETE /api/baseline
GET    /api/runs/<run-id>/analyses/<analysis-id>/comparison
```

GET/POST/DELETE baseline возвращают `{baseline: selection|null}`. Manual POST
содержит `mode`, `series`, `reference`; statistical POST — `mode`, `series`,
`candidates`, `comparable:true`. Selection закрепляет точный run/analysis,
режим, алгоритм, candidate set и scores. API ограничивает body 16 KiB/depth 8,
series 128 UTF-8 bytes и statistical candidates 3..20 разных runs; file cap
32 KiB. Route mutations проходят обычную Host/Origin/session/CSRF boundary.

`BaselineComparison.kt` вычисляет deterministic rank selection и overall
deltas. Round-to-6 decimal strings — только presentation; ratio comparison
точный. Техническая identity semantics и подтверждение пользователем заданных
условий учитываются отдельно; фактический RPS не является equality gate.
Comparison не добавляет поля в canonical analysis, не меняет policy или
analysis identity. Baseline policy для PASS/FAIL потребует отдельного решения.

Vue panel хранит transient candidate selection только в page memory, получает
persisted selection из API и защищает отображение comparison от stale responses.
Новые зависимости, registry/store interfaces или browser storage не добавлены.

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

## Optional diagnostic analysis

`correlation-plan.v1` поступает через общий CLI/API validator (1MiB, depth12).
Он связывается с semantic resource SHA-256; raw bytes сохраняются immutable,
plan hash/module version/limits участвуют в identity только при наличии плана.
Четвёртая multipart часть — `correlation_plan`; общий body ceiling
(`MAX_JOB_REQUEST_BYTES`) суммирует максимальные parts (snapshot 32MiB,
policy1MiB, plan1MiB и остальные) плюс envelope.
`POST /api/jobs` требует `Content-Length`; без него возвращает
`411 LENGTH_REQUIRED` до чтения multipart. UI передаёт длину автоматически.
Schema string lengths дополняются runtime-пределами 128/512 UTF-8 bytes.

UTC load cells собираются в существующем втором parser pass, без пересчёта
run-relative bins и без усреднения percentiles. Existing window + diagnostic
cell histogram budget<=10000. Бюджет пар/окон<=128, lag points<=2688, эпизоды
<=1000. Optional module limit/недостаток наблюдений не меняет SLA coverage/verdict.
Диагностика не делает outbound requests и не добавляет production dependencies.

Новые typed evidence занимают existing analysis-result slots. Window summaries
с точными resource bindings позволяют compareAnalyses сравнивать выбранные
окна без новых jobs; результат comparison остаётся отдельно от immutable analysis.

Evidence `window_metric_summary` для окна без сэмплов (`sample_count = 0`)
публикует `null` в четырёх полях `latency_ms` (`p50`, `p95`, `p99`, `max`),
`error_rate_ratio = null`, `error_count = 0`, а `throughput_rps` остаётся точной
дробью `0 / длительность окна` (ADR
[0016](../adr/0016-metric-semantics-percentile-empty-window-jmeter-parents.md),
срез 2). У нулевой выборки латентность не измерена; сэмпл длительностью 0 мс
остаётся измерением и даёт число `0`. Внутренняя сводка метрик (`emptySummary`) и
`metric_summary` без окна остаются числовыми (оконный `metric_summary` не
публикуется), схема `analysis-result.v1` не меняется (`evidence` без вложенной
схемы). Ранее сохранённые analyses с нулями в пустом окне не переписываются и
читаются как раньше: baseline-сравнение определяет пустое окно по `sample_count`,
поэтому при сравнении совместимых анализов обе формы дают `INSUFFICIENT_DATA` с
`EMPTY_WINDOW`; старый и новый analysis несопоставимы по версии модуля
(`INCOMPATIBLE_METRIC_DEFINITION`, приоритет у несовместимости).
JSON/HTML/AsciiDoc отчёты и evidence для ИИ-совета выводят значения как есть, так
что `null` показывается как `null`. Другие места не читают латентность
`window_metric_summary`.
Подробные контракты и отложенная uncertainty — в
[ADR 0006](../adr/0006-bounded-load-resource-correlation.md).

Полный перечень result-affecting ceilings и crash-durability границ закреплён в
[ADR 0002](../adr/0002-slice-1-runtime-filesystem-security.md). Семантика policy,
exact transaction identity, verdict precedence и evidence закреплены в
[ADR 0003](../adr/0003-policy-v1-metrics-evidence.md).

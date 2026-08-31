# ADR 0003 — policy.v1, metrics и evidence Slice 1

**Дата:** 2026-08-31

**Статус:** Accepted

## Контекст

Slice 1 должен выдавать одинаковый canonical `analysis-result.v1` из CLI и Web
UI. До production code необходимо однозначно определить policy validation,
transaction matching, time window, sample contribution, histogram encoding,
state precedence, deterministic ordering и закрытую analysis identity. При
этом существующая schema `analysis-result.v1` не меняется.

## Решение

### Strict policy.v1 и canonical hash

`policy.v1` требует top-level `schema_version`, `policy_id` и непустой `rules`;
`schema_version` равен `policy.v1`. Каждое rule требует `id`, `metric`,
`operator`, `threshold` и `scope`. Top-level, rule и обе scope forms запрещают
unknown fields. Rule ids уникальны внутри policy; пустая policy не может дать
vacuous `PASS`.

Разрешены только пары:

| Metric | Operator | Threshold | Unit |
| --- | --- | --- | --- |
| `response_time_p95_ms` | `lte` | число `>= 0` | ms |
| `response_time_p99_ms` | `lte` | число `>= 0` | ms |
| `error_rate_ratio` | `lte` | число от `0` до `1` | ratio |
| `throughput_rps` | `gte` | число `>= 0` | requests/second |

Scope имеет ровно одну форму: `{ "kind": "overall" }` либо
`{ "kind": "transaction", "name": "<exact label>" }`. Regex, wildcard,
fuzzy matching, baseline, phases, implicit defaults и implicit units не
поддерживаются.

Validation двухступенчатая:

1. До run context `ltv policy validate`, CLI analysis и HTTP используют один
   validator. Он читает не более 1 MiB с `limit + 1` guard до выделения полного
   byte array, строго декодирует UTF-8, bounded lexical pre-scan отклоняет
   duplicate object keys, включая escaped-equivalent keys, и проверяет depth и
   numeric bounds. Затем проверяются contract shape, unknown fields, ids,
   unique rule ids, rule count, metric/operator и threshold range.
2. После parse structurally valid policy связывается с exact transaction
   catalog текущего run. Analysis не исправляет policy автоматически.

Canonical policy bytes — UTF-8 без BOM и whitespace, object keys сортируются
лексикографически, array order сохраняется, numeric values записываются
canonical decimal без exponent и незначащих zeroes. Поэтому эквивалентные
spellings вроде `6e2`, `600.0` и `600` имеют один hash, а перестановка rules
меняет hash. Policy hash — lowercase SHA-256 canonical bytes.

### Transaction identity и binding

Internal transaction identity равна точному `(groupPath, label, kind)`. Policy
transaction scope сопоставляется только с exact `label`:

- zero distinct identities — `TRANSACTION_NOT_FOUND`;
- более одной distinct identity, включая различие path или kind, —
  `AMBIGUOUS_TRANSACTION`;
- оба случая дают `NO_VERDICT` и точную coverage reason.

JMeter CSV остаётся flat: он не выводит parent или sampler kind из optional
columns. JMeter XML и Gatling сохраняют hierarchy, поэтому containers/groups и
leaf/request samples не double-count overall metrics.

### Run window и sample contribution

Первый streaming pass валидирует input и замораживает одно полное run window по
всем complete normalized events:
`min(start)..max(Math.addExact(start, elapsed))`. `start` и `elapsed` —
non-negative `Long`; start и checked end не превышают
`253_402_300_799_999` (`9999-12-31T23:59:59.999Z`). Overflow или range failure
делает input invalid. Throughput overall и каждой transaction использует это
же global window с minimum denominator 1 ms. Второй streaming pass строит
metrics с замороженным `runStart`; отдельный event spool не вводится.

Contribution matrix:

| Sample kind | Overall и 1-second buckets | Exact transaction summary |
| --- | --- | --- |
| Flat JMeter CSV row / JMeter XML leaf (`JMETER_SAMPLER`) | Да | Да |
| JMeter XML container (`JMETER_CONTAINER`) | Нет | Да |
| Gatling `REQUEST` (`GATLING_REQUEST`) | Да | Да |
| Gatling `GROUP` (`GATLING_GROUP`) | Нет | Да |

Sample относится к half-open 1-second bucket
`[floor((start-runStart)/1000), nextSecond)`. Bucket start хранится как
non-negative millisecond offset от `runStart`; отсутствующие seconds остаются
отсутствующими. Rollups 10/30/60 seconds строятся только merge counts, maxima и
1-second histograms; raw events и summary percentiles повторно не агрегируются.

Latency использует `PackedHistogram(1, 86_400_000, 3)` в milliseconds.
Percentiles остаются integer milliseconds. Normalized/rollup row содержит
`{bucket_start_ms,sample_count,error_count,max_latency_ms,hdr_v2_base64}`;
`hdr_v2_base64` — standard Base64 deterministic HdrHistogram compressed V2
bytes.

Error rate и throughput сохраняют exact counts/rationals. Policy comparison
выполняется cross-multiplication с `BigDecimal`, без сравнения display-rounded
decimals. Округление допустимо только для отдельно помеченного display value.

### Limits, validity и verdict precedence

Любое превышение result-affecting limit завершается
`RESOURCE_LIMIT_EXCEEDED` fail-closed без partial PASS. В
`analysis-identity.v1` входят следующие значения:

- input 4 GiB; policy 1 MiB с `limit + 1` guard; filename 255 bytes;
- 64 CSV columns; 64 KiB text field; 1 MiB text line/binary blob;
- 4 KiB UTF-8 label; hierarchy/XML depth 64;
- 64 KiB UTF-8 на exact transaction identity, 10 000 distinct transactions и
  64 MiB total retained transaction-identity bytes;
- 100 000 non-empty one-second buckets;
- 65 536 Gatling cache entries и 64 MiB total decoded cache strings;
- policy JSON depth 16, 256 rules, 128 UTF-8 bytes для `policy_id` и rule `id`,
  4 KiB UTF-8 transaction scope, 64 ASCII bytes numeric token, absolute
  exponent 64 и 128 bytes после canonical decimal expansion;
- timestamp upper bound `253_402_300_799_999` и histogram configuration
  `1..86_400_000` milliseconds с 3 significant digits.

Process/API caps 1 024 terminal job statuses, 100 runs per page и 500 buckets
per page не влияют на result и в identity не входят.

Validity precedence: `INVALID` сильнее `DEGRADED`, `DEGRADED` сильнее `VALID`.
Malformed CSV/XML/text даёт `INVALID + NO_VERDICT`. Binary EOF внутри record
после хотя бы одного complete sample даёт `DEGRADED + NO_VERDICT`, до первого
sample — `INVALID + NO_VERDICT`; EOF на record boundary нормален.

Verdict evaluation имеет точный порядок:

1. Invalid input не может дать `PASS` и получает `NO_VERDICT`.
2. Degraded input получает `NO_VERDICT`.
3. Отсутствующая policy даёт `NO_POLICY` только для valid run.
4. Missing/ambiguous required transaction делает всю evaluated policy
   `NO_VERDICT`, даже если другое rule failed.
5. Иначе хотя бы одно failed rule даёт `FAIL`.
6. Иначе все passed rules дают `PASS`.

Invalid policy отклоняется до создания analysis и не превращается в
`NO_POLICY`.

### Deterministic result и typed objects

Canonical result сохраняет только top-level fields существующего
`analysis-result.v1`; schema не меняется. Разрешённые generic object slots
получают typed content:

- `analysis_coverage` имеет форму `{status,reasons[]}` и хранит точные stable
  reason codes;
- diagnostic findings и policy-failure findings имеют явный type, stable id и
  ссылки на соответствующее evidence;
- metric-summary evidence и policy-check evidence имеют явный type и stable id;
  policy-check связывает rule, exact observed value и metric-summary evidence.

Stable ids выводятся только из deterministic diagnostic key, rule id или exact
transaction identity; timestamps, provenance, display rounding и array position
в id не участвуют.

Порядок arrays фиксирован:

- policy checks следуют policy order;
- transaction summaries сортируются по `(groupPath, label, kind)`;
- diagnostics сортируются по `(code, sourceOffset)`.

### analysis-identity.v1

`analysis_id` — lowercase SHA-256 canonical UTF-8
`analysis-identity.v1`. Canonical identity сортирует object keys, сохраняет
array order, не содержит whitespace, BOM, timestamps или provenance и кодирует
numeric configuration canonical decimal strings.

Identity содержит все и только result-affecting inputs/configuration:

- identity schema version и `analysis_mode`;
- `run_id`, detected `source_type` и full lowercase input SHA-256;
- canonical policy SHA-256 либо explicit `NO_POLICY`;
- engine id/version;
- ordered parser ids/versions и ordered analytical module ids/versions;
- все input schema/source format versions;
- output versions `run.v1`, `analysis-result.v1`, normalized/rollup encoding и
  HdrHistogram compressed V2 encoding;
- histogram precision/range, 1-second normalization и rollups 10/30/60;
- все result-affecting ceilings, перечисленные выше.

Изменение любого identity field создаёт новый `analysis_id` и новый immutable
analysis directory; прежний result не перезаписывается.

## Альтернативы

- Fuzzy transaction matching отклонён: он недетерминированно скрывает rename и
  ambiguity.
- Усреднение готовых percentiles и display-rounded comparison отклонены как
  математически неверные.
- Заполнение absent buckets нулями отклонено: missing data должно сохраняться.
- Новая major schema `analysis-result` отклонена: существующие object slots уже
  допускают typed Slice 1 content.

## Последствия

- Одинаковые bytes, policy и engine configuration дают byte-identical result в
  CLI и Web UI.
- Любое result-affecting изменение создаёт новый analysis, а не меняет прошлый.
- Exact matching может дать `NO_VERDICT` при одинаковом label в разных path/kind;
  это намеренная защита от ложного `PASS`.

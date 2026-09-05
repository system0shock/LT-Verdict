# InfluxDB Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** InfluxQL acquisition с существующим SLA analysis, CLI/UI и offline snapshot replay.

**Architecture:** Расширить существующий metric acquisition конкретной веткой InfluxQL. Общие HTTP governor, snapshot validation, evidence и storage сохранить; отдельный decoder без нового framework.

**Tech Stack:** Kotlin/JDK 21, kotlinx JSON, JUnit; новых dependencies нет.

**Spec:** `docs/superpowers/specs/2026-09-05-remaining-sources-design.md`

## Global Constraints

- InfluxQL GET `/query`; v1-compatible InfluxDB 2 требует DBRP mapping.
- Credentials — environment references; HTTP credentials требуют explicit opt-in.
- Ответ до 16 MiB, raw acquisition до 64 MiB; существующие snapshot limits.
- Missing cells остаются null; InfluxQL cells имеют left-boundary timestamps.
- Существующий PromQL right-boundary mapping и offline identity не изменять.
- Только текущая поставка; PostgreSQL/OpenSearch и multi-source имеют отдельные планы.
- Один Gradle process одновременно. Агенты не делают commits и не запускают агентов.

## Task 1: InfluxQL connector end to end

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/sources/SourceConfig.kt`
- Modify: `src/main/kotlin/io/ltverdict/sources/SourceHttp.kt`
- Modify: `src/main/kotlin/io/ltverdict/sources/PromqlSource.kt`
- Create: `src/main/kotlin/io/ltverdict/sources/InfluxqlSource.kt`
- Create: `src/test/kotlin/io/ltverdict/sources/InfluxqlSourceTest.kt`
- Modify: `src/test/kotlin/io/ltverdict/sources/SourceConfigTest.kt`
- Modify: `docs/user/online-sources.md`
- Create: `docs/contracts/sources/v1/influxdb-connections.example.json`
- Modify: `CHANGELOG.md`

**Public-contract additions (approved source scope, recorded before code):**

`source-connections.v1`: `source_kind: influxdb`; required `database` string
(1..128 UTF-8 bytes), forbidden for other kinds. `auth.type: token` with
`token_env` sends `Authorization: Token ...`. Existing auth remains unchanged.
Direct and configured Grafana proxy routes end with `/query`, never `/write`.
Queries retain the existing metric mapping fields and must contain
`$__start`, `$__end`, `$__interval`; optional `$__offset` for bin alignment.
Substitution gives start/end epoch integer plus `ms`, interval plus `ms`, and
`start % step` plus `ms`. A single SELECT returning `time,value` is required;
reject multi-statements, INTO, comments and unresolved placeholders. Do not
write a general SQL parser: conservative rejection is acceptable and documented.
Use GET with `db`, `q`, `epoch=ms`. Read-only database role remains required.

Example expression:

```sql
SELECT mean("cpu") AS "value" FROM "host"
WHERE time >= $__start AND time < $__end
GROUP BY time($__interval, $__offset) fill(null)
```

**Interfaces:**

Consumes existing `SourceProfile`, `SourceRequest`, `SourceHttp.get`,
`SourceAcquisition` and shared validator. Add optional trailing
`SourceProfile.database: String? = null`; enum `SourceKind.INFLUXDB`.
Keep class `PromqlSource` to avoid unrelated call-site rename. Its acquisition
selects HTTP parameters/decoder by source kind. Add:

```kotlin
internal fun decodeInfluxqlResponse(
    body: ByteArray,
    expectedLabels: Map<String, String>,
    startEpochMillis: Long,
    stepMillis: Long,
    pointCount: Int,
): PromqlSeries?
```

Returns existing labels/values structure; errors use `PromqlDecodeFailure`
with stable codes (existing name retained, not a framework refactor).
Strict UTF-8/duplicate-key/depth/numeric guards precede JSON parsing.
Require exactly one successful statement (statement_id=0), zero or one series,
unique columns exactly `time,value` (column order may differ), numeric epoch
milliseconds, numeric finite value or null; tags match expected label subset.
Reject partial=true, error/messages, duplicate timestamps, extra series,
off-grid timestamps and invalid fields. Missing series returns null.
An empty results array is malformed, not a successful empty statement.
Use existing cell/label/numeric limits; bound numeric strings before BigDecimal.
Do not convert JSON into a fake PromQL response.

- [ ] Write config and decoder tests first. Central literal example:

```kotlin
val decoded = decodeInfluxqlResponse(
    """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a"},"columns":["time","value"],"values":[[1000,0.8],[3000,0.9]]}]}]}""".encodeToByteArray(),
    mapOf("host" to "a"), 1000, 1000, 3,
)!!
assertEquals(listOf(BigDecimal("0.8"), null, BigDecimal("0.9")), decoded.values)
```

  Add literal malformed/error/partial/duplicate/off-grid/ambiguous cases;
  config acceptance and rejection of database on PromQL, missing placeholders,
  unsafe query, credentials on non-opted-in HTTP.
- [ ] Run RED: `./gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*InfluxqlSourceTest' --tests '*SourceConfigTest' -x npmCi`.
  Missing decoder/enum compilation is initial RED; behavioral rejection test
  must also demonstrate the prior unsupported profile behavior before code.
- [ ] Add minimal decoder, config fields/auth and fixed endpoint branch.
  Reuse acquisition failure/all-null rules, limits, persistence and cancellation.
  Resolve expression with request window for hash and HTTP parameters; ensure
  provenance says `left_boundary` for Influx and preserves PromQL semantics.
- [ ] Add real local HTTP fixture acquisition test: assert `/query`, GET,
  decoded db/q/epoch parameters, exact left cells, saved raw response and
  source kind; 401/partial response keeps null cells and SLA rules. Add a
  Grafana route assertion and Token header assertion without persisting token.
  Run covering tests and existing `*SourceHttpTest`, `*PromqlSourceTest` GREEN.
- [ ] Add runnable connection example and document InfluxQL-only/DBRP,
  read-only role, time aliases, gaps, manual snapshot replay; changelog.
- [ ] Root verifies full diff and fresh `test check installDist -x npmCi`
  with `-PnpmOffline=true --offline --no-daemon`; one scoped spec/quality review,
  address concrete findings. Stage only the listed changed files and commit
  `feat(sources): acquire InfluxQL metric snapshots` when gate passes.

## Progress

Baseline `test -x npmCi`: PASS at `d3def07`, 2026-09-05.
Task 1 not started. PostgreSQL, OpenSearch and shared integration remain open.

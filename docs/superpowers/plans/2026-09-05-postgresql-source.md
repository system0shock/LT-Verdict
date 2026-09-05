# PostgreSQL Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read-only PostgreSQL pre/post capture, deterministic table/statement deltas и supplementary pg_profile artifacts.

**Architecture:** JDBC с фиксированными запросами; отдельные файлы pre/post передаются пользователем, без нового session store. Pure comparison работает с одинаковыми validated artifacts online и offline.

**Tech Stack:** JDK 21 JDBC, Kotlin JSON; `org.postgresql:postgresql:42.7.13` — единственная новая production dependency, зафиксировать lockfile и SHA-256 verification metadata до запуска.

**Spec:** `docs/superpowers/specs/2026-09-05-remaining-sources-design.md`

## Global Constraints

- PostgreSQL 15/16 — первичный проверяемый SQL catalogue; отсутствие расширений явно DEGRADED.
- Два явных capture; pre нельзя получать после нагрузки и выдавать за baseline.
- Только read-only database role, fixed SQL/allowlisted identifiers; no arbitrary SQL/JDBC properties.
- TLS verify-full по умолчанию; disable только при explicit allow_insecure.
- Timeout 30 seconds на connect/query/socket, lock timeout 5 seconds; cancellation закрывает текущий JDBC resource.
- Один connection на capture, никакого pool/Testcontainers/logical decoding.
- Phase максимум 16 MiB, 16 tables, 10000 rows/table, 1 MiB/table, 10000 statement rows, 64 KiB/cell.
- Secrets и raw SQLException messages не выводятся; HTML только inert download.
- pg_profile не заменяет pg_stat_statements; reset/unknown/top-N не маскируются.
- Один Gradle process; workers не делают commits и не запускают агентов.

## Task 1: Validated phases and deterministic comparison

**Files:** Create `src/main/kotlin/io/ltverdict/sources/PostgresCapture.kt`,
`src/test/kotlin/io/ltverdict/sources/PostgresCaptureTest.kt`,
`docs/contracts/sources/v1/postgres-phase.example.json`.

**Interfaces:**

```kotlin
internal fun validatePostgresPhase(input: java.io.InputStream): kotlinx.serialization.json.JsonObject
internal fun comparePostgresPhases(
    pre: kotlinx.serialization.json.JsonObject?,
    post: kotlinx.serialization.json.JsonObject?,
    loadInputSha256: String,
    startEpochMillis: Long,
    endEpochMillis: Long,
): kotlinx.serialization.json.JsonObject
```

Both arguments to comparison are revalidated, not assumed trusted. Strict UTF-8,
duplicate key/depth 20/numeric token 64/exponent 64/size guards precede parsing.
Reject unknown fields, invalid types, oversized lists/strings/cells. Canonical
phase document (all displayed fields required unless explicitly nullable):

```json
{
  "schema_version":"postgres-phase.v1",
  "phase":"pre","profile_id":"pg",
  "source_database_id":"test-db",
  "profile_revision_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "pre_sha256":null,
  "capture_started_epoch_ms":100,"capture_ended_epoch_ms":200,
  "server_version":"15.0",
  "configuration":{"work_mem":"4096"},
  "tables":[{
    "schema":"public","table":"orders","columns":["id","status"],
    "column_types":["int8","text"],"stable_key":["id"],
    "row_limit":10000,"byte_limit":1048576,
    "status":"COMPLETE","reason":null,"row_count":1,
    "rows":[["1","new"]]
  }],
  "statements":{
    "status":"COMPLETE","reason":null,"extension_version":"1.10",
    "stats_reset":"2026-01-01T00:00:00Z","dealloc":0,"row_limit":10000,
    "rows":[{"dbid":"1","userid":"10","queryid":"42","toplevel":true,
      "calls":1,"total_exec_time":2.5,"rows":1,
      "shared_blks_hit":2,"shared_blks_read":0,"temp_blks_written":0}]
  },
  "pg_profile":{
    "status":"DEGRADED","reason":"PG_PROFILE_UNAVAILABLE",
    "extension_version":null,"statements_reset":null,
    "server_id":null,"start_sample_id":null,"end_sample_id":null,
    "report_sha256":null
  }
}
```

IDs/schema/table/columns/config keys 1..128 UTF-8 bytes (PostgreSQL identifiers
max 63 bytes), configuration values max 4096; source_database_id is configured
secret-free logical identity, not host/JDBC URL. profile_revision_sha256 binds
all nonsecret connection/mapping fields, stable keys, limits and selected
database. Pre pre_sha256 must be null; post may be null (missing pre) or exact
SHA-256 of canonical validated pre. Capture times nonnegative, ordered and
bounded by existing MAX_TIMESTAMP_EPOCH_MILLIS. rows are arrays of strings/null;
row width equals columns. Unique columns/keys, keys subset of columns. Table
statuses COMPLETE/DEGRADED/FAILED; a non-COMPLETE table has stable reason code.
Rows/row_count/limits must agree; row_count may be null only when incomplete.
No stable key: rows empty, exact count permitted, DEGRADED/PG_TABLE_NO_STABLE_KEY.
Duplicate or null stable keys make a table DEGRADED rather than an exact diff.

Statement rows unique by (dbid,userid,queryid,toplevel); IDs integer strings,
queryid nullable only in DEGRADED module; counts nonnegative integers,
total_exec_time nonnegative finite bounded decimal. stats_reset nullable only
if status != COMPLETE; dealloc nonnegative integer or null if incomplete.
No query text in this minimal capture: identifiers and measured counters are
sufficient for current context; source-side adaptation can resolve query IDs.

Comparison output `postgres-context.v1`, type=postgres_context, profile_id,
load_input_sha256, start/end, pre_sha256/post_sha256, status, reasons, tables,
statements and pg_profile. No raw table content duplicated in evidence.
For matching complete keyed tables produce inserted/deleted/updated counts
and at most 100 changed keys (with keys_truncated); compare full canonical
row values, not hashes alone. For no-key table report only row_count_delta.
For truncation, key/schema mismatch or missing phase exact changes are null.
Global binding requires profile/revision/database match, pre ended<=run start,
post started>=run end, and post.pre_sha256 matches canonical pre. Otherwise
DEGRADED with specific PG_* reason and no deltas. Missing pre or post returns
context, not exception. Both absent returns DEGRADED/PG_CAPTURE_MISSING.

Statement deltas only for keys present in both complete captures with identical
stats_reset and no regressed counter. New/missing keys are explicitly unmatched,
not zero-filled. Increased dealloc preserves matched deltas but marks coverage
DEGRADED/PG_STATEMENTS_EVICTED; reset/regression invalidates statement deltas.
Each matched output retains its key and six delta counters above. Overall
module status/reasons reflect unmatched keys and partial/missing captures.
pg_profile reset=true/null or unavailable report remains DEGRADED and does not
invalidate otherwise usable table facts. No SQL stats from HTML.

- [ ] RED tests with literal pre rows `[(1,new),(2,old)]`, post rows
  `[(1,done),(3,new)]` expect inserted=1/deleted=1/updated=1; statements calls
  10→14 and execution time 20→28 expect deltas 4 and 8.
- [ ] Run `./gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*PostgresCaptureTest' -x npmCi` and verify missing implementation RED.
- [ ] Implement strict phase validation and pure comparison; use existing
  canonicalJson, exact BigDecimal and bounded maps. No JDBC/shared file changes.
- [ ] GREEN tests for late pre/early post, wrong revision/database/pre hash,
  no key/duplicate key/truncation, reset/regression/dealloc/new statements,
  missing extensions, invalid types/limits and deterministic row-order handling.
- [ ] Add canonical example; report focused test evidence and self-review.

## Task 2: JDBC capture and supplementary report

**Files:** Create `sources/PostgresSource.kt`, `sources/PostgresSourceTest.kt`
under matching main/test roots; modify build.gradle.kts, gradle.lockfile,
gradle/verification-metadata.xml and ADR 0008 (dependency/capture contracts).

Concrete internal profile: id, sourceDatabaseId, host, port, database,
usernameEnv/passwordEnv, allowInsecure=false, tables (schema/table/columns/key,
rowLimit/byteLimit), optional pgProfileServerId/startSampleId/endSampleId.
No supplied SQL, JDBC URL, properties or class names. Host DNS/IP only,
database safely URL-encoded; quoted validated identifiers. Fixed JDBC settings
readOnly=true, readOnlyMode=always, REPEATABLE_READ, autoCommit=false, UTC/ISO
session formatting, fixed timeout settings from Global Constraints. Use
savepoint per optional module so unavailable extensions do not abort capture.
Bound rows before materialization and serialized bytes while streaming.

API:

```kotlin
internal fun capturePostgresPhase(
    profile: PostgresProfile,
    pre: kotlinx.serialization.json.JsonObject? = null,
    post: Boolean = false,
    environment: (String) -> String? = System::getenv,
    checkCancelled: () -> Unit = {},
): PostgresCapturedPhase
// PostgresCapturedPhase: phase JsonObject, pgProfileHtml ByteArray?; validated before return.
```

Fixed queries read server_version, current_database, selected pg_settings
(shared_buffers, work_mem, max_connections, effective_cache_size, track_io_timing),
pg_extension schema/version, pg_attribute/pg_class for allowlisted ordinary or
partitioned tables (not arbitrary views). Tables select explicit columns in key
order LIMIT rowLimit+1; keyless tables use count(*) only. pg_stat_statements is
read directly from discovered extension schema, current database only, ordered
by dbid/userid/queryid/toplevel and limited rowLimit+1. Read stats_reset/dealloc
from pg_stat_statements_info. Pre reads statements last; post reads them first.
Never reset statistics. Discover namespace through pg_extension and quote it,
not through untrusted search_path. Query exceptions produce module-specific
stable reasons; never persist raw SQLException or URL/credentials.

pg_profile metadata reads extension version and current_setting with missing_ok;
only when three explicit sample/report IDs configured, invoke vetted
schema-qualified get_report with bound integer arguments. No take_sample/reset
or management functions. Max HTML 4 MiB, UTF-8, SHA-256; immutable bytes are
download-only. Unsupported signature/version/permissions gives DEGRADED and
manual HTML remains available. Capture/report timestamps must remain explicit;
do not claim exact run coverage merely because sample IDs exist.

- [ ] First dependency resolution after plan/ADR records driver: update locks
  and SHA-256 metadata; verify new artifacts against Maven Central checksums.
- [ ] RED opt-in real PostgreSQL integration test: create isolated synthetic
  table via test admin connection, capture read-only pre, perform known DML,
  capture post, assert exact table/statement delta and settings. Test destructive
  setup never targets arbitrary schemas; dedicated test database only.
- [ ] Implement fixed SQL capture, timeout/cancellation and fail-soft modules.
- [ ] GREEN real database test (if available), missing credentials/no secret
  errors, missing extension, cap, read-only role and cancellation tests.
  Missing real database is recorded as unverified, not passing.

## Task 3: Capture UI/CLI and analysis binding

**Files:** Existing SourceConfig/SourceAnalysis, CommandLine, LocalApi,
AnalysisService, UI RunSetup/App/api/types/AnalysisView and matching tests;
source contracts, online-sources user guide, CHANGELOG.

Expose `ltv source pre --connections FILE --profile ID` as canonical JSON stdout;
post command accepts exact pre file and returns phase. API POST source pre/post
uses configured profile ID and optional pre FileItem, bounded body, existing
Origin/session/CSRF protections. Return downloaded phase; no pending-capture
store. Explicit UI capture/download before test, attach pre for post acquisition.
Analyze consumes validated phases plus actual run window, stores both phases,
context and optional HTML under fixed filenames in existing immutable bundle.
Online multi-source selection can obtain post with attached pre; offline import
uses same comparator without JDBC. HTML import bounded/hashed/download-only.

- [ ] RED CLI/API tests for true separate pre/post, capture binding and rejected
  arbitrary endpoint/SQL/paths; browser test capture/download/attach workflow.
- [ ] Implement with existing jobs for analysis and bounded capture operation;
  never acquire pre inside analyze. Additional schema fields recorded above.
- [ ] GREEN online/offline facts equivalence, invalid pre binding and load-only
  fallback, no secret/inline HTML; docs and fresh full verification/review.

## Progress and test environment

Tasks 1–3 not started. Docker CLI exists but engine is stopped; user asked
asynchronously about a separate synthetic PostgreSQL container. No permission
to start it has been received yet. pg_profile real-server validation separately
requires an extension-enabled test instance; absence remains explicit.

Driver basis: [pgJDBC downloads](https://jdbc.postgresql.org/download/).
SQL basis: [PostgreSQL 15 pg_stat_statements](https://www.postgresql.org/docs/15/pgstatstatements.html).

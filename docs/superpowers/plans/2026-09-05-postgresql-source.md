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
Context includes `configuration_changes`: sorted `{name,pre,post}` values for
changed/added/removed settings, only when global pre/post binding is valid.
Missing values are null; invalid binding yields an empty list with existing
binding reasons, not invented settings. Invalid-load context also has no changes.

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
Task2 review correction: a dedicated capture role is excluded from statement
rows by userid; capture records `lt_verdict.excluded_statement_userid` in its
configuration map. This excludes connector SQL without claiming it is workload;
the capture role must not run the load. Fixed driver maxResultBuffer16 MiB and
SQL byte-prefix projections (cell/report limit+1) bound wire allocation before
getBinaryStream. Retain overflow detection and use compatible bounded fetch size.

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

Connection contract: `source-connections.v2` retains the `connections` array,
permits existing HTTP profiles and PostgreSQL records. v1 remains HTTP-only.
PostgreSQL record fields: id, source_kind="postgresql", source_database_id,
host, port(default5432), database, username_env, password_env,
allow_insecure(defaultfalse), tables(default[]), optional pg_profile object
with server_id/start_sample_id/end_sample_id (all three together). Tables:
schema/table/columns/key(default[])/row_limit(default10000)/byte_limit(default1048576).
Unknown fields rejected; total <=16 unique IDs across both kinds. Internal
SourceConnections holds concrete HTTP and PostgreSQL lists; shared strict JSON
reader and governor are retained. No fake HTTP URL for PostgreSQL.

CLI `source post` takes `--pre FILE` (optional means missing-pre coverage) and
optional `--pg-profile-html PATH` for a create-new HTML attachment. stdout is
canonical phase JSON. UI endpoints `POST /api/sources/postgresql/pre` and
`/post` accept multipart profile_id plus optional pre file on post, require
Content-Length, and return `postgres-capture.v1` with `phase_json` (canonical
phase JSON string) and nullable `pg_profile_html_base64`. The phase is a string
intentionally: browser download retains exact integer counters, without
JavaScript number rounding. UI creates File/Blob downloads; HTML uses
application/octet-stream and download attribute, never inline rendering.
No capture-session store. Request <=17 MiB, phase <=16 MiB, HTML <=4 MiB;
response envelope derives only from those bounded artifacts.
Capture endpoint permits one in-flight capture per local backend, rejects an
additional operation with existing `409 BUSY` (no waiting queue). Existing
Origin/session/CSRF protects both actions. `GET /api/sources` lists PostgreSQL
as id/source_kind="postgresql"/transport="jdbc", without host or credentials.
UI obtains post via the explicit capture action before analysis; neither pre
nor post is silently collected when Analyze is pressed.
Analysis manual inputs `--postgres-pre FILE`, `--postgres-post FILE`, optional
`--pg-profile-html FILE` are one pair per run; phase hash/profile/time binding
is checked against actual parsed load window, not user-supplied times.
HTTP source selection may coexist with the PostgreSQL pair. Invalid load input
retains validated phase artifacts but emits PG_LOAD_WINDOW_UNAVAILABLE, no
computed deltas. Online post acquisition may use the attached pre's configured
profile; pre itself is never collected during analysis.

Integration boundary: `PostgresAnalysisInput(pre: JsonObject? = null,
post: JsonObject? = null, pgProfileHtml: ByteArray? = null)` is a concrete input
record, optional `AnalysisRequest.postgres`. `readPostgresAnalysisInput` accepts
nullable pre/post/HTML InputStreams, validates phases and exact pre/post roles,
bounds HTML to4 MiB/UTF-8, rejects all-absent input. A standalone manual HTML
attachment is allowed but never interpreted as SQL evidence. If a supplied
post (otherwise pre) declares report_sha256, attached HTML must match it.
Analysis revalidates input before identity/persistence. Optional identity field
`postgres_input_sha256` hashes canonical phases and HTML content hashes; absent
PG retains existing identity. Fixed artifacts: postgres-pre.json,
postgres-post.json, postgres-context.json, pg-profile.html (only supplied files).
Context for invalid load has null start/end and no computed modules/deltas,
DEGRADED/PG_LOAD_WINDOW_UNAVAILABLE. Indexed download is not used for PG;
fixed `/postgres-pre`, `/postgres-post`, `/postgres-context`, `/pg-profile`.
Stored analysis context adds nullable `pg_profile_html_sha256` for the actual
attached HTML bytes. UI download visibility uses this field, not report hashes
declared in phases (a declaration alone does not prove the file was attached).

- [ ] RED CLI/API tests for true separate pre/post, capture binding and rejected
  arbitrary endpoint/SQL/paths; browser test capture/download/attach workflow.
- [ ] Implement with existing jobs for analysis and bounded capture operation;
  never acquire pre inside analyze. Additional schema fields recorded above.
- [ ] GREEN online/offline facts equivalence, invalid pre binding and load-only
  fallback, no secret/inline HTML; docs and fresh full verification/review.

## Progress and test environment

Tasks 1–3 implemented locally: validated phases/comparison, bounded JDBC,
CLI/API capture and manual analysis inputs, actual load-window binding,
fixed artifacts, exact-number UI and download-only HTML. Configuration deltas
and actual HTML attachment hash are included in stored context.
Task2 review found connector-traffic contamination and pre-validation JDBC
allocation; both have focused fixes (10 passed/1 live-DB skip), with independent
fix review APPROVED (no remaining Critical/Important in those fixes).
Core7/7, CLI/API2/2 and real-backend source browser3/3 passed;
full browser suite `npm run e2e` PASS41/41.
Full `test check installDist -x npmCi` PASS on2026-09-06:
289 tests,286 passed/3 skipped (two existing Windows skips and real PostgreSQL).
Final independent Task3 integration review: COMPLIANT / APPROVED, no
Critical/Important findings. UI lint, markdownlint and diff whitespace checks
passed; external CI and unavailable local secret scanner remain unverified.
Source block/stage closure remains pending external gates, not asserted.

### Live evidence — 2026-09-06

User authorized Docker and a separate synthetic database. Container
`ltv-pg-it-20260906`, label `io.ltverdict.synthetic=true`, publishes only
`127.0.0.1:54321`; PGDATA is a512 MiB tmpfs, no host mounts. PostgreSQL16.15,
pg_stat_statements1.10, image `postgres:16` resolved digest:
`sha256:f1c3376c26f2609ab9f29f71f824103fe2fcd8ee0346485cb6122a4f93df6f94`.
No existing container or real database was modified.

Dedicated live `PostgresSourceTest`:11 tests,0 skipped,0 failures/errors.
Final `test check -x npmCi --rerun-tasks`: BUILD SUCCESSFUL in1m26s,
289 tests,287 passed/2 existing Windows skips,0 failures/errors; ktlint passed.
Known insert gives table inserted1/deleted0/updated0 and workload calls delta1;
capture userid is excluded. A16777217-byte table value is rejected with
PG_TABLE_CELL_TOO_LARGE before unbounded client materialization.
Initial failure was test-role configuration: NOINHERIT hid query IDs despite
membership. Corrected effective pg_read_all_stats inheritance, retained read-only
capture. Production unchanged; test status assertions now precede row lookup
and include module diagnostics instead of a bare NoSuchElementException.

Reproduction: dedicated database, pg_stat_statements preloaded/installed,
separate admin and read-only capture roles, effective pg_read_all_stats.
Set LT_VERDICT_PG_IT_DEDICATED=true, HOST/PORT/DATABASE, ADMIN_USER/ADMIN_PASSWORD,
CAPTURE_USER/CAPTURE_PASSWORD (all names prefixed LT_VERDICT_PG_IT_), and
LT_VERDICT_PG_IT_ALLOW_INSECURE=true only for this loopback test. Run:

```powershell
.\gradlew.bat --no-daemon test --tests '*PostgresSourceTest' --rerun-tasks -x npmCi
```

Official pg_profile4.8 release archive SHA-256:
`67a5ac87d40c56547321a5cc4ef2925a121beb78fb502888fe452a52ac38d236`.
Installed dblink and pg_profile into schema profile; test admin created samples1/2
using take_sample (test setup only; connector never samples). Under capture role,
BEGIN READ ONLY + profile.get_report(1,1,2) succeeded. CLI source pre with configured
report IDs produced492404 bytes; actual HTML SHA-256 matched phase.report_sha256:
`b43b29070b53f846b1a7699e40ba9d9e4f45cd2e3da8372fd84a4f634aeac7d1`.
Report remains DEGRADED/PG_PROFILE_SETTING_UNAVAILABLE, statements_reset=null:
successful download does not invent reset/coverage evidence. HTML was not opened.
Existing report path correctly rejected with PG_REPORT_EXISTS, no overwrite.
Artifacts reside in ignored `.superpowers/sdd/pg-profile-live-assets`.

Not verified: PostgreSQL15, live TLS verify-full, production workload or external
CI/secret scanner. These limitations are not converted to PASS.

Sources: [pg_profile4.8 release](https://github.com/zubkov-andrei/pg_profile/releases/tag/4.8),
[PostgreSQL16 role inheritance](https://www.postgresql.org/docs/16/sql-grant.html).

Driver basis: [pgJDBC downloads](https://jdbc.postgresql.org/download/).
SQL basis: [PostgreSQL 15 pg_stat_statements](https://www.postgresql.org/docs/15/pgstatstatements.html).

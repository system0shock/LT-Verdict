# OpenSearch Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenSearch 2.6 error aggregates как сохраняемый и импортируемый context одного load analysis.

**Architecture:** Fixed POST search через existing HTTP governor; source-specific query builder/decoder. Nullable resource snapshot и отдельное context evidence в существующем acquisition, без connector framework.

**Tech Stack:** JDK 21 HTTP, Kotlin JSON, JUnit; новых dependencies нет.

**Spec:** `docs/superpowers/specs/2026-09-05-remaining-sources-design.md`

## Global Constraints

- OpenSearch 2.6, direct REST; только read-only search, без arbitrary Query DSL.
- Credentials остаются environment references; URLs без userinfo/credentials.
- Ответ до 16 MiB, общий raw acquisition до 64 MiB.
- Error predicate: exists(error_type_field); service/type — single-valued keyword.
- Failures/partial coverage не изменяют load verdict и не превращаются в нули.
- Никакой новой correlation, RCA или chart overlay в этой поставке.
- Один Gradle process; агенты не делают commits и не запускают других агентов.

## Task 1: Query, normalized artifact and strict import

**Files:** Create `src/main/kotlin/io/ltverdict/sources/OpenSearchSource.kt`,
`src/test/kotlin/io/ltverdict/sources/OpenSearchSourceTest.kt`,
`docs/contracts/sources/v1/opensearch-errors.example.json`.

**Interfaces:**

```kotlin
internal data class OpenSearchMapping(
    val indices: List<String>,
    val timestampField: String,
    val serviceField: String,
    val errorTypeField: String,
    val messageField: String,
    val groupLimit: Int = 50,
    val samplesPerGroup: Int = 2,
    val sampleMessageBytesMax: Int = 4096,
)
internal fun buildOpenSearchQuery(
    mapping: OpenSearchMapping, request: SourceRequest, timeoutMillis: Long,
): ByteArray
internal fun decodeOpenSearchResponse(
    body: ByteArray, mapping: OpenSearchMapping, request: SourceRequest,
    loadInputSha256: String, baseUrl: java.net.URI,
): kotlinx.serialization.json.JsonObject
internal fun validateOpenSearchArtifact(
    input: java.io.InputStream, loadInputSha256: String,
): kotlinx.serialization.json.JsonObject
```

Mapping validates indices 1..16, each 1..128 UTF-8 bytes; permit lowercase
index characters `[a-z0-9._*-]` but reject bare `*`, `_all`, `..`, comma,
leading `-`, `.`, `_`, and path/URI separators. Field names 1..128 bytes,
nonempty dotted identifiers (allow `@timestamp`, prohibit control characters).
Groups 1..200, samples 0..5, message bytes 1..65536. No source-level HTTP/config
changes in Task 1; integration owns those after Influx has released shared files.

Query uses size=0, track_total_hits=true, configured timeout `${timeoutMillis}ms`,
range `[gte start, lt end]` with format epoch_millis and exists(errorTypeField).
`timeline.date_histogram`: fixed_interval step ms, offset floorMod(start,step)
ms, min_doc_count=0, extended_bounds min=start/max=end-step.
`groups.multi_terms`: service/type fields, size=groupLimit, order `_count desc`;
subaggregations first_at/min(timestamp), last_at/max(timestamp), samples/top_hits
with size=samplesPerGroup, timestamp ascending sort, `_source.includes=[message]`.
When samplesPerGroup=0 omit samples aggregation and decode no samples.

Normalized public artifact `opensearch-errors.v1` contains:

```json
{
  "schema_version":"opensearch-errors.v1",
  "id":"opensearch-errors:errors",
  "type":"opensearch_errors",
  "load_input_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "profile_id":"errors",
  "start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000,
  "total_errors":3,"error_rate_per_minute":90,
  "timeline":[
    {"from_epoch_ms":1000,"to_epoch_ms":2000,"count":1,"rate_per_minute":60},
    {"from_epoch_ms":2000,"to_epoch_ms":3000,"count":2,"rate_per_minute":120}
  ],
  "groups":[{"service":"api","error_type":"Timeout","count":3,
    "first_epoch_ms":1100,"last_epoch_ms":2900,"samples":[]}],
  "coverage":{
    "status":"COMPLETE","reasons":[],"timed_out":false,"total_relation":"eq",
    "shards":{"total":1,"successful":1,"skipped":0,"failed":0},
    "terms":{"group_limit":50,"returned_groups":1,"sum_other_doc_count":0,"doc_count_error_upper_bound":0},
    "samples_per_group_limit":0,"sample_message_bytes_max":4096
  }
}
```

Nonempty samples contain timestamp_epoch_ms, index, document_id, message,
message_truncated and source_url. Require numeric sort timestamp in run window,
bounded index/id strings (1024 UTF-8 bytes each); extract dotted message path,
missing message becomes empty string. Derive HTTP(S) source_url from safe base
URI plus percent-encoded `_index`/`_id`; never take document-provided URLs.
Truncate message at a valid UTF-8 boundary and set message_truncated.
Import URLs require HTTP(S), valid host/port, no userinfo/fragment/query/control
characters, and an `_doc` path; they remain untrusted external links in UI.

Rates = count*60000/duration, HALF_UP scale 6, stripTrailingZeros. Counts must
be nonnegative exact integers within Long; sums use exact overflow checks.
Timeline must contain exactly the requested aligned cells in order; missing or
duplicate cells fail (do not invent zeros for a partial response). Require one
group key pair, unique groups, first<=last within window and positive counts.
Strict UTF-8, duplicate-key/depth/numeric guards precede JSON parsing.
Response JSON may contain standard extra metadata, never error payloads;
normalized import rejects unknown fields and incorrect derived fields.

Coverage PARTIAL reasons (sorted for determinism):

- timed_out: `OPENSEARCH_TIMED_OUT`;
- failed>0 or successful<total: `OPENSEARCH_SHARDS_INCOMPLETE`;
- total relation gte: `OPENSEARCH_TOTAL_LOWER_BOUND`;
- sum_other_doc_count>0: `OPENSEARCH_TERMS_TRUNCATED`;
- doc_count_error_upper_bound>0 (or -1 unknown): `OPENSEARCH_TERM_COUNTS_APPROXIMATE`;
- timeline sum != total: `OPENSEARCH_TIMELINE_COUNT_MISMATCH`;
- group sum + sum_other_doc_count != total: `OPENSEARCH_GROUP_COUNT_MISMATCH`.

Skipped shards alone do not degrade successful==total. Bounded samples and
message truncation are explicit limits, not missing aggregate coverage.
Import recalculates rates, reasons, status, counts and grid invariants and
rejects mismatch; validates load hash. Max normalized artifact 16 MiB, max
100000 cells, max 200 groups, max 5 samples/group. Malformed input throws
IllegalArgumentException with stable `OPENSEARCH_*` code, never raw content.

- [ ] Write query and decoder tests first, with literal 3 errors / 2 seconds
  = rate 90, cells `[1,2]`, rates `[60,120]` and one group. Assert exact range,
  endpoint-independent JSON query structure and nonzero start offset.
- [ ] Run RED `./gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*OpenSearchSourceTest' -x npmCi`; absent builder/decoder must fail.
- [ ] Implement builder, strict decoder and validator without changing shared
  files. Shared private helpers in this same file may validate normalized
  shapes and compute coverage; no generic parser framework.
- [ ] Test each PARTIAL reason, skipped shards COMPLETE, malformed/overflow,
  duplicate/grid errors, load hash mismatch, unsafe URL, count/rate/status
  tampering, oversized messages and escaped document IDs. Roundtrip:

```kotlin
assertEquals(artifact, validateOpenSearchArtifact(canonicalJson(artifact).inputStream(), "a".repeat(64)))
```

- [ ] Run GREEN covering tests, write canonical example, report RED/GREEN and
  self-review. Root performs one task spec/quality review before integration.

## Task 2: HTTP/config, acquisition, CLI/UI and replay integration

**Files:** Modify `SourceConfig.kt`, `SourceHttp.kt`, `PromqlSource.kt`,
`SourceAnalysis.kt`, `core/AnalysisService.kt`, `cli/CommandLine.kt`,
`web/LocalApi.kt`, `ui/src/types.ts`, `ui/src/api.ts`, `ui/src/App.vue`,
`ui/src/RunSetup.vue`, `ui/src/AnalysisView.vue`; matching existing tests,
`docs/user/online-sources.md`, `CHANGELOG.md` and a connection example.

Public profile adds source_kind=opensearch, direct only, mapping fields from
Task 1 in an `opensearch` object. No queries/rules/database/datasource_uid.
SourceProfile adds optional `openSearch: OpenSearchMapping? = null`; mappings
are rejected on unrelated kinds. HTTP fixed endpoint is indices joined by
comma followed by `/_search`; origin and base path remain profile-owned.
Add `SourceHttp.search(profile, body, budget, checkCancelled)` using the same
retry/token/concurrency loop as get, not a second client. Fixed POST, JSON
headers and query flags allow_no_indices=false, ignore_unavailable=false,
allow_partial_search_results=true, typed_keys=false; no caller-supplied URI.

Acquisition `snapshot` becomes nullable for context-only sources; add
`contextEvidence: List<JsonObject> = emptyList()`. Core validates nullable
snapshot binding, hashes context evidence, appends it on valid and invalid
load paths, and permits fixed `opensearch-errors.json` artifact alongside raw
responses. Source summary remains query status/budget data. Missing artifact
on failure is not a zero artifact. CLI `--source-context FILE` and multipart
`source_context` import Task 1's validated artifact without connections, with
optional offline resource snapshot. Reject context import + online selection.
Artifact download endpoint validates manifest and serves fixed JSON attachment.
UI file input and context table show aggregate counts/groups/coverage;
external sample URLs use safe anchors, messages plain escaped text.

- [ ] RED real local HttpServer test checks POST path/query/headers/body,
  response normalization and shared governor. Existing CLI/API tests cover
  context-only analysis, mixed offline resource+context, wrong load hash and
  online/import conflicts.
- [ ] Implement the concrete integration above; preserve old no-source identity.
  Nullable snapshot must not display an unavailable resource download link.
- [ ] GREEN tests for 401/429/5xx/cancellation/no raw errors, immutable artifact
  saving and network-free reload/import with identical analytical context.
- [ ] Add browser E2E for imported context rendering and offline resource+context;
  document mapping, caps, semantics and manual replay, update changelog.
- [ ] Fresh full Gradle `test check installDist -x npmCi`, browser e2e,
  Markdown/diff/secret checks and task review; commit explicit task files.

## Progress

Task 1 implemented; focused tests 9/9, scoped review fix for wildcard-only
indices addressed. Task 2 CLI/API/UI integration implemented; full local
test/check/installDist passed (256 tests, 2 existing Windows skips), then scoped
HTTP/config/CLI/API tests passed after the 2xx invalid-body persistence fix.
Review fix addressed; 4 source UI and 2 real-backend source browser tests pass.
Broader final sources gate and external CI/secret scan remain pending.
Multi-source online selection
and PostgreSQL remain separate work; this delivery does not close that block.

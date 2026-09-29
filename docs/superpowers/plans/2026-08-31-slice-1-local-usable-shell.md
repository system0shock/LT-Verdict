# Slice 1 Local Usable Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Поставить первый локально полезный LT Verdict: один JDK process принимает
JMeter/Gatling artifact и optional `policy.v1`, потоково и детерминированно
анализирует его, сохраняет immutable RunBundle и показывает byte-identical
`analysis-result.v1` через CLI и утверждённый Web UI.

**Architecture:** Один Gradle JVM project содержит чистое application core,
filesystem RunBundle, четыре streaming parser и две оболочки. `ltv analyze`
вызывает core напрямую; Ktor/Netty обслуживает только loopback UI и отдаёт CPU
работу bounded executor обычных platform threads. Vue build встраивается в JVM
resources. Database, service split, router/store framework и speculative
extension points не создаются.

**Tech Stack:** JDK 21, Kotlin 2.4.10, Gradle 9.5.0, Ktor 3.5.2/Netty,
kotlinx.serialization 1.11.0, HdrHistogram 2.2.2, Vue 3.5.42,
TypeScript 6.0.2, Vite 8.2.2, native CSS Grid, Playwright 1.62.1.

**Spec:** `docs/superpowers/specs/2026-08-31-slice-1-local-usable-shell-design.md`

## Global Constraints

- Начинать production implementation только после попадания утверждённых spec и
  этого плана в `main`; использовать отдельные branch
  `feat/slice-1-local-shell` и worktree.
- Один implementation branch/PR содержит только законченный Slice 1 concern.
- Не менять required fields и validation semantics `run.v1` или
  `analysis-result.v1`; единственный новый public contract — `policy.v1`.
- Сначала фиксировать ADR, dependency gate и independent fixture oracle, затем
  писать parser/runtime code.
- На каждом behavior task сначала добавить failing test и запустить его в RED,
  затем написать минимальную реализацию и получить GREEN.
- Парсить последовательно внутри одного artifact; parallelism существует между
  независимыми analyses. CPU work не выполняется на Netty event-loop.
- Не добавлять Spring, coroutine-based analysis, ORM, database, broker, router,
  Pinia, chart/grid/DnD/icon/Tailwind dependency, outbound HTTP client или AI.
- Не добавлять interfaces/factories для единственной реализации. Parser routing
  — обычный `when (sourceType)`; CLI flags — ручной разбор короткого списка.
- Не сохранять response bodies, response headers, raw XML, browser storage или
  credentials.
- Не рисовать chart, drag handle, export-result action или disabled placeholder.
- Все commits атомарные и Conventional Commits; stage только перечисленные в
  task файлы. Push, PR, merge, tag и release требуют отдельного разрешения.

## Fixed Dependency Ledger

Версии фиксируются до production code; lockfiles и verification metadata входят
в тот же commit, что build bootstrap.

| Role | Pin | Decision |
| --- | --- | --- |
| JVM | Temurin/OpenJDK 21 | Runtime baseline |
| Build | Gradle `9.5.0` | Верхняя fully-supported граница Kotlin 2.4.10; wrapper SHA-256 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` |
| Language | Kotlin `2.4.10` | JVM and serialization Gradle plugins |
| HTTP | Ktor BOM/server `3.5.2` | Core, Netty, content negotiation, JSON, test host |
| JSON | kotlinx.serialization `1.11.0` | Runtime contracts and canonical JSON input tree |
| Metrics | HdrHistogram `2.2.2` | `PackedHistogram`, no home-grown percentile code |
| CSV parser | uniVocity parsers `2.9.1` | One production parser; a streaming quote-parity `Reader` guard rejects an unmatched quote at EOF |
| Logging | slf4j-simple `2.0.18` | One local-process backend |
| JVM tests | JUnit BOM `6.1.3` | Jupiter engine and assertions |
| Kotlin lint | ktlint Gradle plugin `14.2.0` | Build-time only |
| JS runtime | Node `24.14.0`, npm `11.9.0` | CI and lockfile baseline |
| UI | Vue `3.5.42` | Composition API without router/store |
| UI build | Vite `8.2.2`, `@vitejs/plugin-vue` `6.0.8` | Local bundled assets only |
| Types | TypeScript `6.0.2`, vue-tsc `3.3.11` | TypeScript pin remains in typescript-eslint supported range |
| UI lint | ESLint `10.9.1`, typescript-eslint `8.68.0`, eslint-plugin-vue `10.10.0` | Includes `vue/no-v-html` and storage bans |
| Browser tests | `@playwright/test` `1.62.1`, `@axe-core/playwright` `4.13.0` | Chromium flow, security and accessibility |
| Schema test | Ajv `8.18.0` | Dev-only policy schema/example check; not shipped to browser |

The direct Task 2 uniVocity configuration accepted an unmatched quote at EOF,
so parser production code remained stopped. The bounded amendment is one
streaming quote-parity `Reader` guard; uniVocity remains the only CSV parser.
If the amended gate fails, stop before any `src/main` parser code, record the
failure in ADR 0002 and do not silently add a second CSV library.

## Result-Affecting Decisions to Freeze

ADR 0002 and ADR 0003 must record these exact values before implementation:

- `run_id = "<source-type>-<full-lowercase-input-sha256>"`, where source type is
  one of `jmeter_jtl_csv`, `jmeter_jtl_xml`, `gatling_text`,
  `gatling_binary`.
- `analysis_id` is lowercase SHA-256 of canonical UTF-8
  `analysis-identity.v1`: sorted object keys, preserved array order, no
  whitespace/BOM/timestamps/provenance, canonical decimal strings for numeric
  configuration.
- Internal transaction identity is exact `(groupPath, label, kind)`. A policy
  matches exact `label`; zero distinct identities yields `TRANSACTION_NOT_FOUND`
  and more than one distinct identity yields `AMBIGUOUS_TRANSACTION`; both yield
  `NO_VERDICT`. CSV stays flat because it has no reliable parent or sampler-kind
  signal.
- Overall and transaction throughput use the same run window over every complete
  normalized event: `min(start)..max(Math.addExact(start, elapsed))`, with a
  minimum denominator of 1 ms. Timestamp and elapsed are non-negative `Long`
  values; start and checked end must not exceed `253_402_300_799_999`
  (`9999-12-31T23:59:59.999Z`), the upper bound representable by the existing
  four-digit-year `run.v1` timestamp profile. Overflow/range failure is invalid
  input, never a wrapped or schema-invalid timestamp.
- A sample belongs to half-open 1-second bucket
  `[floor((start-runStart)/1000), nextSecond)`; absent seconds remain absent.
  `runStart` is frozen by a first streaming validation/window pass; a second
  streaming pass builds metrics, so out-of-order input cannot shift an already
  recorded bucket. Rollups 10/30/60 seconds merge these histograms only.
- HdrHistogram configuration is `PackedHistogram(1, 86_400_000, 3)` in
  milliseconds. Percentile values remain integer milliseconds; policy compares
  exact counts/rationals, never display-rounded decimals.
- Overall metrics and buckets include every flat JMeter CSV row, leaf JMeter XML
  result and Gatling `REQUEST` record. Exact transaction summaries additionally
  include JMeter XML containers and Gatling `GROUP` records. CSV does not infer
  controller semantics from optional columns; XML/Gatling hierarchy prevents
  parent/child double-counting.
- Bounded limits are 4 GiB input, 1 MiB policy read with a `limit + 1` guard
  before allocating its complete byte array, 255-byte filename, 64 CSV columns,
  64 KiB text field, 1 MiB text line/binary blob, 4 KiB UTF-8 label,
  hierarchy/XML depth 64, 64 KiB UTF-8 per exact transaction identity, 10,000
  distinct transactions, 64 MiB total retained transaction-identity bytes,
  100,000 non-empty one-second buckets, 65,536 Gatling cache entries and 64 MiB
  total decoded Gatling cache strings. Policy-specific limits are JSON depth 16,
  256 rules, 128 UTF-8 bytes for `policy_id` and rule `id`, 4 KiB for a
  transaction scope,
  64 ASCII bytes for a numeric token, absolute exponent at most 64 and at most
  128 bytes after canonical decimal expansion. Exceeding a result-affecting
  limit fails closed with `RESOURCE_LIMIT_EXCEEDED` and no partial PASS.
- Non-result process/API caps are 1,024 retained terminal job statuses, 100 runs
  per page and 500 buckets per page; they do not enter `analysis-identity.v1`.
- Malformed CSV/XML/text is `INVALID + NO_VERDICT`. Binary EOF inside a record
  after at least one complete sample is `DEGRADED + NO_VERDICT`; before any
  sample it is `INVALID + NO_VERDICT`. EOF on a record boundary is normal.
- CLI exits: `0` for PASS, NO_POLICY or valid `policy validate`; `2` for FAIL;
  `3` for NO_VERDICT/DEGRADED; `4` for invalid/unsupported input; `5` for
  invalid policy; `6` for `DATA_DIR_BUSY`; `64` for usage; `70` for unexpected
  internal failure.

## Target File Map

```text
settings.gradle.kts
build.gradle.kts
gradle.properties
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
gradle/verification-metadata.xml
gradle.lockfile

src/main/kotlin/io/ltverdict/Main.kt
src/main/kotlin/io/ltverdict/cli/CommandLine.kt
src/main/kotlin/io/ltverdict/core/{AnalysisResult,AnalysisService,CanonicalJson,Model,Policy}.kt
src/main/kotlin/io/ltverdict/ingest/{FormatDetector,GatlingBinaryParser,GatlingTextParser,JtlCsvParser,JtlXmlParser,LoadSample}.kt
src/main/kotlin/io/ltverdict/metrics/Metrics.kt
src/main/kotlin/io/ltverdict/storage/{DataDirectory,RunBundleStore}.kt
src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt
src/main/kotlin/io/ltverdict/web/{LocalApi,LocalServer}.kt

ui/index.html
ui/package.json
ui/package-lock.json
ui/{eslint.config.js,playwright.config.ts,tsconfig.json,vite.config.ts}
ui/scripts/{start-e2e-server,verify-policy-schema}.mjs
ui/src/{App,AnalysisView,JobStatus,PolicyEditor,RunSetup}.vue
ui/src/{api,main,types}.ts
ui/src/styles.css
ui/e2e/{local-flow,security-a11y}.spec.ts
```

Tests mirror production packages under `src/test/kotlin/io/ltverdict`; fixture,
contract, ADR, CI and documentation paths are named in their owning tasks.

---

### Task 1: Freeze runtime, filesystem, policy and metric decisions

**Files:**

- Create: `docs/adr/0002-slice-1-runtime-filesystem-security.md`
- Create: `docs/adr/0003-policy-v1-metrics-evidence.md`

**ADR 0002 must contain:**

- the dependency ledger above and the conditional uniVocity promotion gate;
- one Gradle project, embedded Vue resources, direct CLI/core call and private
  Ktor API;
- default data directory
  `Path.of(System.getProperty("user.home"), ".lt-verdict")`;
- one exclusive `<data>/.ltv.lock` held by every writer for the process lifetime;
- one short process-local write mutex around accept/commit/list consistency;
  parsing and metric calculation remain outside it;
- staging/hash/limit/detect/atomic-accept order and this immutable layout:

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

- every failed operation removes its exact UUID staging path in `finally`; on
  `DataDirectory.open`, while holding the exclusive lock, remove only
  non-symlink direct children of `.staging` whose names are generated UUIDs;
  reject symlinked `.ltv.lock`, `.staging`, `runs` or traversed app-owned path
  and verify `.staging` resolves directly under the real data root before cleanup;
- each completed analysis manifest records relative path, byte size and
  lowercase SHA-256 for every analysis artifact except the self-referential
  manifest itself; manifest paths must be normalized descendants of the exact
  analysis directory and may not traverse symlinks; reuse verifies every entry
  before returning cached data;
- file `force(true)` before same-filesystem `ATOMIC_MOVE`; unsupported atomic
  move fails closed; parent-directory fsync is attempted where the JDK/OS allows
  it and the Windows limitation is explicit;
- platform-thread `ThreadPoolExecutor`, local default parallelism `1`, queue
  capacity equal to parallelism, no virtual-thread or coroutine claim;
- bind exactly `127.0.0.1`, one random in-memory server session and CSRF token,
  exact Host/Origin checks, no CORS, CSP and no outbound client;
- exact private HTTP request/response/status/error envelopes and bounded bucket
  range pagination from Task 11;
- all fixed resource ceilings and CLI exit codes from this plan.

**ADR 0003 must contain:**

- strict `policy.v1`, canonical policy hash and two-stage validation;
- transaction matching and ambiguity rule from this plan;
- exact run-window with checked timestamp arithmetic, sample-kind contribution
  matrix, bucket, rollup, HdrHistogram and rational-comparison rules;
- normalized/rollup histogram encoding as standard Base64 of deterministic
  HdrHistogram compressed V2 bytes;
- state precedence for VALID/DEGRADED/INVALID and
  PASS/FAIL/NO_POLICY/NO_VERDICT;
- deterministic ordering: policy checks in policy order, transaction summaries
  by `(groupPath, label, kind)`, diagnostics by `(code, sourceOffset)`;
- typed `analysis-result.v1` objects without a schema change:
  `analysis_coverage {status,reasons[]}`, diagnostic/policy-failure findings,
  metric-summary and policy-check evidence with stable ids;
- all fields participating in `analysis-identity.v1`, including engine,
  parser, input/output schema versions and every result-affecting limit.

- [ ] **Step 1: Write ADR 0002 as `Proposed`, ADR 0003 as `Accepted`, and include every decision above**

  ADR 0002 remains proposed only until the Task 2 CSV gate supplies its final
  dependency evidence. ADR 0003 has no contingent decision and is accepted
  before policy/metric production code.

- [ ] **Step 2: Review for conflicts with the approved spec**

  ```powershell
  rg -n "TBD|TODO|to be decided|placeholder|later decide" docs/adr/0002-slice-1-runtime-filesystem-security.md docs/adr/0003-policy-v1-metrics-evidence.md
  npx --yes markdownlint-cli2@0.23.2 "docs/adr/0002-slice-1-runtime-filesystem-security.md" "docs/adr/0003-policy-v1-metrics-evidence.md"
  git diff --check
  ```

  Expected: `rg` has no matches; lint and diff check exit `0`.

- [ ] **Step 3: Commit the decisions**

  ```powershell
  git add docs/adr/0002-slice-1-runtime-filesystem-security.md docs/adr/0003-policy-v1-metrics-evidence.md
  git commit -m "docs(slice-1): record runtime and policy decisions"
  ```

### Task 2: Bootstrap the locked build and prove the CSV dependency

**Files:**

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/verification-metadata.xml`
- Create: `gradle.lockfile`
- Modify: `.gitignore`
- Modify: `.gitattributes`
- Create: `src/test/kotlin/io/ltverdict/ingest/CsvDependencySpikeTest.kt`
- Modify: `docs/adr/0002-slice-1-runtime-filesystem-security.md`

**Build contract:**

- `rootProject.name = "lt-verdict"`; JDK toolchain/release is 21.
- Apply Kotlin JVM/serialization, application and ktlint plugins only.
- Main class is `io.ltverdict.MainKt`; application/distribution name is `ltv`.
- Enable dependency locking for all resolvable configurations and fail on
  dynamic/changing versions.
- Wrapper uses Gradle 9.5.0 `bin` distribution and the fixed SHA-256 above.
- Start `com.univocity:univocity-parsers:2.9.1` as `testImplementation` only.
- Wrap the spike input in one streaming `Reader` that toggles parity for each
  raw `"` and throws at EOF when the count is odd. This covers the one strictness
  gap in uniVocity 2.9.1 without a second parser or retained input.
- A dedicated `csvSpike` `Test` task uses `-Xmx256m`, one fork and only
  `CsvDependencySpikeTest`.

**Spike cases:**

- RFC 4180 quoted comma, escaped quote and embedded newline remain one record;
- LF and CRLF yield identical fields without whitespace trimming;
- malformed/unclosed quote fails instead of returning a partial record;
- `maxColumns=64` and `maxCharsPerColumn=65536` reject overflow;
- a deterministic 1,000,000-row stream parses under 60 seconds with `-Xmx256m`
  without retaining rows.

- [ ] **Step 1: Generate the verified Gradle wrapper and initial locks**

  Download Gradle 9.5.0 from the official distribution endpoint, compare its
  SHA-256 with the ledger, run its `gradle.bat wrapper --gradle-version 9.5.0
  --distribution-type bin`, then generate dependency locks and SHA-256
  verification metadata. A checksum mismatch stops the task.

- [ ] **Step 2: Add the spike test and confirm RED without the candidate**

  ```powershell
  .\gradlew.bat csvSpike
  ```

  Expected: test compilation fails because the uniVocity classes are absent.

- [ ] **Step 3: Add uniVocity as test-only and run the gate**

  ```powershell
  .\gradlew.bat --no-daemon csvSpike
  ```

  Expected: direct uniVocity parsing exposes the unmatched-quote EOF gap; the
  amended uniVocity-plus-guard pipeline passes all five cases within the task
  limits.

- [ ] **Step 4: Promote the one proven parser and accept ADR 0002**

  Move the exact same coordinate from `testImplementation` to
  `implementation`; change ADR 0002 to `Accepted` and record the observed spike
  command, elapsed time and max heap. Refresh `gradle.lockfile` and verification
  metadata. Do not add another CSV library.

- [ ] **Step 5: Verify build reproducibility and commit**

  ```powershell
  .\gradlew.bat --no-daemon csvSpike test ktlintCheck
  .\gradlew.bat --offline --no-daemon csvSpike test ktlintCheck
  git diff --check
  ```

  Expected: online and offline commands exit `0` after dependency acquisition.

  ```powershell
  git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties gradle/verification-metadata.xml gradle.lockfile .gitignore .gitattributes src/test/kotlin/io/ltverdict/ingest/CsvDependencySpikeTest.kt docs/adr/0002-slice-1-runtime-filesystem-security.md
  git commit -m "build(slice-1): bootstrap locked JVM runtime"
  ```

### Task 3: Add the policy contract and independent golden corpus

**Files:**

- Create: `docs/contracts/policy/v1/policy.schema.json`
- Create: `docs/contracts/policy/v1/examples/valid/all-metrics.json`
- Create: `docs/contracts/policy/v1/examples/invalid/empty-rules.json`
- Create: `docs/contracts/policy/v1/examples/invalid/duplicate-rule-id.json`
- Create: `docs/contracts/policy/v1/examples/invalid/unknown-field.json`
- Create: `docs/contracts/policy/v1/examples/invalid/unknown-metric.json`
- Create: `docs/contracts/policy/v1/examples/invalid/wrong-operator.json`
- Create: `docs/contracts/policy/v1/examples/invalid/error-rate-out-of-range.json`
- Create: `fixtures/slice1/manifest.json`
- Create: `fixtures/slice1/jmeter/csv-5.6.3/input.jtl`
- Create: `fixtures/slice1/jmeter/csv-5.6.3/oracle.json`
- Create: `fixtures/slice1/jmeter/xml-5.6.3/input.xml`
- Create: `fixtures/slice1/jmeter/xml-5.6.3/oracle.json`
- Create: `fixtures/slice1/gatling/text-3.9.5/simulation.log`
- Create: `fixtures/slice1/gatling/text-3.9.5/oracle.json`
- Create: `fixtures/slice1/gatling/text-3.12.0/simulation.log`
- Create: `fixtures/slice1/gatling/text-3.12.0/oracle.json`
- Create: `fixtures/slice1/gatling/binary-3.13.5/simulation.log`
- Create: `fixtures/slice1/gatling/binary-3.13.5/oracle.json`
- Create: `fixtures/slice1/gatling/binary-3.15.1/simulation.log`
- Create: `fixtures/slice1/gatling/binary-3.15.1/oracle.json`
- Create: `fixtures/slice1/normalization/spike-drop.jtl`
- Create: `fixtures/slice1/policies/pass.json`
- Create: `fixtures/slice1/policies/fail.json`
- Create: `fixtures/slice1/policies/missing-transaction.json`
- Create: `fixtures/slice1/security/dtd.xml`
- Create: `fixtures/slice1/security/xxe.xml`
- Create: `fixtures/slice1/security/entity-expansion.xml`
- Create: `fixtures/slice1/security/html-label.jtl`
- Create: `fixtures/slice1/identity/policy.canonical.json`
- Create: `fixtures/slice1/identity/analysis-identity.v1.json`
- Create: `fixtures/slice1/identity/analysis-identity.sha256`
- Create: `src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt`
- Modify: `.gitattributes`

**Contract rules:**

The schema is Draft 2020-12, has `additionalProperties: false` at top/rule/scope
levels, `maxItems: 256` for rules, character-length caps where JSON Schema can
express them and only the four approved metric/operator pairs. Runtime
validation additionally applies the UTF-8 byte caps. The valid example
exercises all metrics and both scope forms. For every example,
`manifest.json` records separate `schema_valid` and `runtime_valid` expectations;
runtime-invalid examples also name one expected diagnostic code. Duplicate rule
ids are schema-valid but runtime-invalid because JSON Schema does not express
uniqueness by object property.

**Fixture provenance:**

Generate parser goldens with unmodified JMeter 5.6.3 and Gatling OSS 3.9.5,
3.12.0, 3.13.5 and 3.15.1 releases. For each case `manifest.json` records the
producer version, upstream release tag, generation command, every artifact
SHA-256 and the independent expected sample/error counts, transaction paths and
p50/p95/p99/max. Copy expected values from the producer HTML/report or derive
them manually from tiny known samples; never generate an oracle with LT Verdict
parser code. Keep `fixtures/slice0/simulation.log` unchanged and do not use it as
a parser oracle. The JMeter XML oracle contains both nested `sample` and
`httpSample` containers around leaves; the Gatling oracle contains a group
around requests. Each states overall leaf counts and separate exact summaries.

The 3.9.5 and 3.12.0 artifacts are raw text `simulation.log` files, matching the
approved input boundary. `.gitattributes` marks binary logs `-text` and text
fixtures as UTF-8/LF. `normalization/spike-drop.jtl` deliberately places an
earlier start after a later row, so the same small oracle proves the two-pass
`runStart` rule as well as spike/drop preservation.

- [ ] **Step 1: Write the manifest test and confirm RED**

  The test verifies every listed file exists, its lowercase SHA-256 matches,
  provenance fields are non-empty, oracles contain exact required metrics and
  no oracle claims LT Verdict as producer.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.fixtures.FixtureManifestTest"
  ```

  Expected: non-zero because the contract/corpus is absent.

- [ ] **Step 2: Add schema, examples and independently produced fixtures**

  Keep valid fixtures small enough for repository review. Malformed boundary
  cases can be built inline by parser tests; only trust-boundary payloads and
  cross-parser goldens are committed.

- [ ] **Step 3: Run the fixture gate and inspect binary provenance**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.fixtures.FixtureManifestTest"
  git check-attr text -- fixtures/slice1/gatling/binary-3.13.5/simulation.log fixtures/slice1/gatling/binary-3.15.1/simulation.log
  git diff --check
  ```

  Expected: manifest test exits `0`; all binary artifacts report `text: unset`.

- [ ] **Step 4: Commit the contract and corpus**

  ```powershell
  git add docs/contracts/policy/v1/policy.schema.json docs/contracts/policy/v1/examples/valid/all-metrics.json docs/contracts/policy/v1/examples/invalid/empty-rules.json docs/contracts/policy/v1/examples/invalid/duplicate-rule-id.json docs/contracts/policy/v1/examples/invalid/unknown-field.json docs/contracts/policy/v1/examples/invalid/unknown-metric.json docs/contracts/policy/v1/examples/invalid/wrong-operator.json docs/contracts/policy/v1/examples/invalid/error-rate-out-of-range.json fixtures/slice1/manifest.json fixtures/slice1/jmeter/csv-5.6.3/input.jtl fixtures/slice1/jmeter/csv-5.6.3/oracle.json fixtures/slice1/jmeter/xml-5.6.3/input.xml fixtures/slice1/jmeter/xml-5.6.3/oracle.json fixtures/slice1/gatling/text-3.9.5/simulation.log fixtures/slice1/gatling/text-3.9.5/oracle.json fixtures/slice1/gatling/text-3.12.0/simulation.log fixtures/slice1/gatling/text-3.12.0/oracle.json fixtures/slice1/gatling/binary-3.13.5/simulation.log fixtures/slice1/gatling/binary-3.13.5/oracle.json fixtures/slice1/gatling/binary-3.15.1/simulation.log fixtures/slice1/gatling/binary-3.15.1/oracle.json fixtures/slice1/normalization/spike-drop.jtl fixtures/slice1/policies/pass.json fixtures/slice1/policies/fail.json fixtures/slice1/policies/missing-transaction.json fixtures/slice1/security/dtd.xml fixtures/slice1/security/xxe.xml fixtures/slice1/security/entity-expansion.xml fixtures/slice1/security/html-label.jtl fixtures/slice1/identity/policy.canonical.json fixtures/slice1/identity/analysis-identity.v1.json fixtures/slice1/identity/analysis-identity.sha256 src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt .gitattributes
  git commit -m "test(slice-1): add policy contract and parser goldens"
  ```

### Task 4: Implement strict policy validation and canonical identity

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/Model.kt`
- Create: `src/main/kotlin/io/ltverdict/core/CanonicalJson.kt`
- Create: `src/main/kotlin/io/ltverdict/core/Policy.kt`
- Create: `src/test/kotlin/io/ltverdict/core/CanonicalJsonTest.kt`
- Create: `src/test/kotlin/io/ltverdict/core/PolicyTest.kt`

**Interfaces:**

```kotlin
internal data class PolicyValidationError(
    val code: String,
    val jsonPointer: String,
    val message: String,
)

internal sealed interface PolicyValidation {
    data class Valid(
        val policy: PolicyV1,
        val canonicalBytes: ByteArray,
        val sha256: String,
    ) : PolicyValidation

    data class Invalid(val errors: List<PolicyValidationError>) : PolicyValidation
}

internal fun validatePolicy(
    source: InputStream,
    maxBytes: Int = 1_048_576,
): PolicyValidation
internal fun canonicalJson(element: JsonElement): ByteArray
internal fun canonicalDecimal(value: BigDecimal): String
internal fun sha256Hex(bytes: ByteArray): String
```

Read at most `maxBytes + 1` before creating the complete policy byte array.
Strictly decode UTF-8, then run one bounded lexical pre-scan that compares
decoded object keys (including `\u` escapes), rejects duplicates and enforces
JSON depth and numeric-token/exponent limits before the kotlinx.serialization
tree parse. Reject unknown fields and
non-finite/out-of-range numbers, then apply identifier, rule-count and
metric/operator validation. Object key order and numeric spellings such as
`6e2`, `600.0` and `600` produce the same policy hash; rule array order remains
significant.

- [ ] **Step 1: Add table-driven policy/canonicalization tests and run RED**

  Tests consume every contract example and identity golden, assert exact JSON
  pointers/codes, nested and escaped-equivalent duplicate-key rejection,
  byte/depth/rule/id/numeric bounds including `1e2147483647`, canonical numeric
  equivalence and a changed hash when policy rule order changes. Assert schema
  and runtime expectations independently so semantic duplicate rule ids are not
  attributed to JSON Schema validation.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.core.CanonicalJsonTest"
  ```

  Expected: test compilation fails because production types do not exist.

- [ ] **Step 2: Implement the minimum strict validator and canonical writer**

  Use the bounded pre-scan, kotlinx.serialization JSON tree and explicit
  allowed-key checks in `Policy.kt`; do not add a runtime JSON Schema library,
  second JSON parser or validation framework. CLI and HTTP must pass streams to
  this entry point and never call `readBytes` for policy input.

- [ ] **Step 3: Run GREEN and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.core.CanonicalJsonTest"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/core/Model.kt src/main/kotlin/io/ltverdict/core/CanonicalJson.kt src/main/kotlin/io/ltverdict/core/Policy.kt src/test/kotlin/io/ltverdict/core/CanonicalJsonTest.kt src/test/kotlin/io/ltverdict/core/PolicyTest.kt
  git commit -m "feat(policy): validate and canonicalize policy v1"
  ```

### Task 5: Implement safe ingest, content detection and immutable RunBundle

**Files:**

- Create: `src/main/kotlin/io/ltverdict/storage/DataDirectory.kt`
- Create: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt`
- Create: `src/main/kotlin/io/ltverdict/ingest/FormatDetector.kt`
- Create: `src/test/kotlin/io/ltverdict/storage/DataDirectoryTest.kt`
- Create: `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/FormatDetectorTest.kt`

**Interfaces:**

```kotlin
internal enum class SourceType(val wireName: String) {
    JMETER_CSV("jmeter_jtl_csv"),
    JMETER_XML("jmeter_jtl_xml"),
    GATLING_TEXT("gatling_text"),
    GATLING_BINARY("gatling_binary"),
}

internal data class AcceptedInput(
    val runId: String,
    val sourceType: SourceType,
    val sha256: String,
    val sizeBytes: Long,
    val originalFilename: String,
    val path: Path,
)

internal class DataDirectory private constructor(val root: Path) : AutoCloseable {
    companion object {
        fun open(root: Path): DataDirectory
    }
}

internal data class RunPage(
    val runs: List<RunSummary>,
    val nextAfter: String?,
)

internal class RunBundleStore(private val dataDirectory: DataDirectory) {
    fun acceptInput(
        source: InputStream,
        originalFilename: String,
        maxBytes: Long = 4_294_967_296L,
    ): AcceptedInput

    fun requireInput(runId: String): AcceptedInput
    fun listRuns(afterRunId: String?, limit: Int): RunPage
    fun readAnalysis(runId: String, analysisId: String): StoredAnalysis?
    fun writeAnalysisAtomically(
        runId: String,
        analysisId: String,
        writeStagingDirectory: (Path) -> Unit,
    ): Path
}

internal fun detectSource(path: Path): SourceType
```

`acceptInput` writes into a generated `.staging` path while counting bytes and
hashing, rejects zero/overflow/unsafe metadata, detects content, computes the
id, forces files and atomically moves one new run directory. A pre-existing
identical run is verified and reused. No caller supplies a filesystem-relative
path derived from the filename. `acceptInput` and `writeAnalysisAtomically` each
delete their own UUID staging path in `finally` after every non-crash failure,
including overflow, unsupported input, writer exception and failed move;
startup cleanup remains only the crash fallback.

Format detection reads bounded prefixes: XML prolog/root, exact CSV header,
Gatling text `RUN` record or binary Run header/version. Extension and filename
never decide the type.

`listRuns` accepts only `1..100`, scans run directory names once and retains at
most `limit + 1` lexicographically smallest names after the exclusive cursor in
a JDK `PriorityQueue`; it never accumulates the whole filesystem catalog.

- [ ] **Step 1: Add failing lock, path, detection and atomicity tests**

  Cover same bytes/type identity, source type participation, all committed
  formats with misleading extensions, empty/unknown input, 4 GiB boundary via
  an injected small test limit, symlink/traversal/reserved device names,
  failure before atomic move, idempotent re-upload, no owned staging residue
  after overflow/unknown input/writer exception/failed move, rejection of
  symlinked app-owned root entries, safe cleanup of UUID-named stale staging
  directories without following symlinks, preservation of every other
  `.staging` entry, bounded/cursor-stable run listing across more than one page
  and a second JVM process receiving `DATA_DIR_BUSY` before any mutation.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.storage.*" --tests "io.ltverdict.ingest.FormatDetectorTest"
  ```

  Expected: test compilation fails because storage/detection do not exist.

- [ ] **Step 2: Implement the minimum safe filesystem flow**

  Use `java.nio.file`, `FileChannel.tryLock`, `MessageDigest` and generated UUID
  staging names plus one operation-local `try/finally`. Reject symbolic links at
  the supplied CLI path. Cleanup runs only after acquiring the exclusive lock
  and only for the exact app-owned UUID path. Do not add a repository abstraction
  or filesystem index.

- [ ] **Step 3: Run GREEN, inspect the on-disk tree and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.storage.*" --tests "io.ltverdict.ingest.FormatDetectorTest"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/storage/DataDirectory.kt src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt src/main/kotlin/io/ltverdict/ingest/FormatDetector.kt src/test/kotlin/io/ltverdict/storage/DataDirectoryTest.kt src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt src/test/kotlin/io/ltverdict/ingest/FormatDetectorTest.kt
  git commit -m "feat(storage): add immutable run bundles"
  ```

### Task 6: Implement normalized samples, metrics and merge-only rollups

**Files:**

- Create: `src/main/kotlin/io/ltverdict/ingest/LoadSample.kt`
- Create: `src/main/kotlin/io/ltverdict/metrics/Metrics.kt`
- Create: `src/test/kotlin/io/ltverdict/metrics/MetricsTest.kt`
- Create: `src/test/kotlin/io/ltverdict/metrics/NormalizationGoldenTest.kt`

**Interfaces:**

```kotlin
internal enum class SampleKind {
    JMETER_SAMPLER,
    JMETER_CONTAINER,
    GATLING_REQUEST,
    GATLING_GROUP,
}

internal data class LoadSample(
    val startedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val label: String,
    val groupPath: List<String>,
    val kind: SampleKind,
    val successful: Boolean,
)

internal data class MetricsConfig(
    val lowestDiscernibleValueMillis: Long = 1,
    val highestTrackableValueMillis: Long = 86_400_000,
    val significantDigits: Int = 3,
    val maxTransactions: Int = 10_000,
    val maxTransactionIdentityBytes: Int = 65_536,
    val maxTotalTransactionIdentityBytes: Long = 67_108_864,
    val maxOneSecondBuckets: Int = 100_000,
)

internal class MetricsAccumulator(
    private val runStartEpochMillis: Long,
    private val runEndEpochMillis: Long,
    private val config: MetricsConfig,
) {
    fun record(sample: LoadSample)
    fun finish(): NormalizedMetrics
}
```

Each accumulator receives the final first-pass run window and owns all mutable
`PackedHistogram` instances. Overall metrics and sparse 1-second buckets record
`JMETER_SAMPLER` and `GATLING_REQUEST`; all four kinds produce exact
`(groupPath, label, kind)` summaries. Transaction-specific time-series are
deferred; exact summaries and raw immutable input preserve the required Slice 1
information. Rollups merge 1-second histograms and counts, never raw events or
summary percentiles.

- [ ] **Step 1: Add failing metric and normalization tests**

  Cover sample/error counts, error-rate and throughput rationals, p50/p95/p99/max,
  exact path/label/kind distinction, nested container/group exclusion from
  overall counts, ambiguity across kind as well as path, timestamp upper bound
  and checked overflow, out-of-order starts against a frozen `runStart`, half-open
  bucket boundaries, absent seconds, merge-only 10/30/60 rollups, spike/drop
  golden, latency/cardinality/retained-identity/bucket ceilings and two
  accumulators sharing no mutable state. Assert each normalized row has fields
  `{bucket_start_ms,sample_count,error_count,max_latency_ms,hdr_v2_base64}`;
  `hdr_v2_base64` is standard Base64 of HdrHistogram compressed V2 encoding.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.metrics.*"
  ```

  Expected: test compilation fails because metric types do not exist.

- [ ] **Step 2: Implement with HdrHistogram and exact comparisons**

  Use `PackedHistogram` directly. Compare error rate and throughput by
  cross-multiplication with `BigDecimal`; round only separately labelled display
  values. When admitting a distinct key, count the UTF-8 bytes of each path
  segment and label, ASCII kind bytes and one separator byte per component;
  count each key once and fail before exceeding either ceiling. Include both
  ceilings in `EngineConfig` and `analysis-identity.v1`; sort finalized
  transaction data deterministically.

- [ ] **Step 3: Run GREEN and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.metrics.*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/ingest/LoadSample.kt src/main/kotlin/io/ltverdict/metrics/Metrics.kt src/test/kotlin/io/ltverdict/metrics/MetricsTest.kt src/test/kotlin/io/ltverdict/metrics/NormalizationGoldenTest.kt
  git commit -m "feat(metrics): normalize load samples"
  ```

### Task 7: Implement streaming JMeter CSV and XML parsers

**Files:**

- Create: `src/main/kotlin/io/ltverdict/ingest/JtlCsvParser.kt`
- Create: `src/main/kotlin/io/ltverdict/ingest/JtlXmlParser.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/JtlCsvParserTest.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/JtlXmlParserTest.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/JtlGoldenTest.kt`
- Modify: `src/main/kotlin/io/ltverdict/ingest/LoadSample.kt`

**Interfaces:**

```kotlin
internal data class ParseReport(
    val validity: RunValidity,
    val processedBytes: Long,
    val diagnostics: List<Diagnostic>,
)

internal fun parseJtlCsv(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport

internal fun parseJtlXml(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport
```

CSV requires unique `timeStamp`, `elapsed`, `label` and `success` headers,
accepts/ignores bounded extras and emits every row as a flat `JMETER_SAMPLER`.
Optional `URL` is data only and never used to infer controller semantics. CSV
never invents a parent path. Timestamp and elapsed are non-negative integers;
their sum uses
`Math.addExact` and both start/end obey the fixed `run.v1` timestamp bound;
success is exact case-insensitive `true|false`; any malformed row, overflow or
range violation invalidates the analysis.

XML uses JDK StAX only. Disable DTD, external general/parameter entities and
entity replacement and install an `XMLResolver` that always throws. Emit a leaf
`sample` or `httpSample` as `JMETER_SAMPLER`; emit either element containing a
child `sample`/`httpSample` as `JMETER_CONTAINER`. Preserve the nesting stack,
require `ts`, `t`, `lb` and `s`, apply checked timestamp arithmetic and never
retain response data, headers, assertions or raw XML.

- [ ] **Step 1: Add failing CSV tests and run RED**

  Cover quoted UTF-8/commas/newlines, LF/CRLF, exact labels, empty/non-empty/
  absent optional `URL` with identical flat-sampler semantics, missing/duplicate
  required headers, malformed quote/number/boolean, field limit, cancellation
  and the JMeter 5.6.3 oracle.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.JtlCsvParserTest" --tests "io.ltverdict.ingest.JtlGoldenTest"
  ```

- [ ] **Step 2: Implement CSV and obtain GREEN**

  Configure the already-approved uniVocity parser and quote-parity `Reader`
  guard once in this file. Emit each row immediately; do not collect input rows.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.JtlCsvParserTest" --tests "io.ltverdict.ingest.JtlGoldenTest"
  ```

- [ ] **Step 3: Add failing XML/security tests and run RED**

  Cover nesting, golden parity, malformed/empty/missing attributes, depth/label
  limits, checked timestamp overflow, leaf/container contribution, cancellation
  and committed DTD/XXE/entity-expansion fixtures. Assert a marker placed in
  response body/header/raw XML never reaches samples or result data.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.JtlXmlParserTest"
  ```

- [ ] **Step 4: Implement XML and obtain GREEN**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.Jtl*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

- [ ] **Step 5: Commit both JMeter parsers**

  ```powershell
  git add src/main/kotlin/io/ltverdict/ingest/LoadSample.kt src/main/kotlin/io/ltverdict/ingest/JtlCsvParser.kt src/main/kotlin/io/ltverdict/ingest/JtlXmlParser.kt src/test/kotlin/io/ltverdict/ingest/JtlCsvParserTest.kt src/test/kotlin/io/ltverdict/ingest/JtlXmlParserTest.kt src/test/kotlin/io/ltverdict/ingest/JtlGoldenTest.kt
  git commit -m "feat(parser): support JMeter JTL"
  ```

### Task 8: Implement version-aware Gatling text and binary parsers

**Files:**

- Create: `src/main/kotlin/io/ltverdict/ingest/GatlingTextParser.kt`
- Create: `src/main/kotlin/io/ltverdict/ingest/GatlingBinaryParser.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/GatlingTextParserTest.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/GatlingBinaryParserTest.kt`
- Create: `src/test/kotlin/io/ltverdict/ingest/GatlingGoldenTest.kt`
- Modify: `src/main/kotlin/io/ltverdict/ingest/FormatDetector.kt`

**Interfaces:**

```kotlin
internal fun parseGatlingText(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport

internal fun parseGatlingBinary(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport

internal fun parseInput(
    input: AcceptedInput,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport
```

Before writing field decoders, compare the released Gatling
`LogFileWriter`/`LogFileReader` sources at tags `v3.9.5`, `v3.12.0`, `v3.13.5`
and `v3.15.1` with the committed fixtures and record exact layouts as tables in
the tests. A fixture/source conflict stops the task and amends ADR/spec; the
implementation must not guess.

Text supports official tab-delimited `RUN`, `USER`, `REQUEST`, `GROUP`, `ERROR`
and `ASSERTION` records for 3.9.x through 3.12.x using a bounded UTF-8 line
reader. `REQUEST` emits `GATLING_REQUEST`; `GROUP` emits `GATLING_GROUP`; all
other record types affect parser state or diagnostics but not request metrics.

Binary is big-endian, requires Run first, handles header tags, timestamp deltas,
bounded lists/blobs and the exact LATIN1/UTF16 string cache used by supported
tags. It accepts 3.13.x, 3.14.x, 3.15.0 and 3.15.1 only. Unknown header, coder,
cache index, negative length, checked timestamp overflow/range violation or
newer version fails closed.

- [ ] **Step 1: Add failing text boundary/golden tests**

  Cover raw 3.9.5/3.12.0 `simulation.log`, official record layouts, exact group
  paths, request/group contribution without overall double-counting, bounded
  lines, malformed records, checked timestamp overflow and cancellation.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.GatlingTextParserTest" --tests "io.ltverdict.ingest.GatlingGoldenTest"
  ```

- [ ] **Step 2: Implement the text parser and obtain GREEN**

- [ ] **Step 3: Add failing binary boundary/golden tests**

  Cover Run-first/version gates, 3.13.5/3.15.1 goldens, LATIN1/UTF16 and cache
  references, timestamp deltas, all bounded-length failures, unsupported 3.15.2
  and 4.x, truncation before/after a complete sample and cancellation.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.GatlingBinaryParserTest"
  ```

- [ ] **Step 4: Implement binary parsing and direct `when` routing**

  No parser registry, plugin API or reflection is introduced.

- [ ] **Step 5: Run all parser tests and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.ingest.*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/ingest/GatlingTextParser.kt src/main/kotlin/io/ltverdict/ingest/GatlingBinaryParser.kt src/main/kotlin/io/ltverdict/ingest/FormatDetector.kt src/test/kotlin/io/ltverdict/ingest/GatlingTextParserTest.kt src/test/kotlin/io/ltverdict/ingest/GatlingBinaryParserTest.kt src/test/kotlin/io/ltverdict/ingest/GatlingGoldenTest.kt
  git commit -m "feat(parser): support Gatling simulation logs"
  ```

### Task 9: Compose the one analysis core and canonical result

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt`
- Create: `src/main/kotlin/io/ltverdict/core/AnalysisService.kt`
- Modify: `src/main/kotlin/io/ltverdict/core/Policy.kt`
- Create: `src/test/kotlin/io/ltverdict/core/PolicyEvaluationTest.kt`
- Create: `src/test/kotlin/io/ltverdict/core/AnalysisServiceTest.kt`
- Create: `src/test/kotlin/io/ltverdict/core/AnalysisResultGoldenTest.kt`

**Interfaces:**

```kotlin
internal data class AnalysisRequest(
    val input: AcceptedInput,
    val policy: PolicyValidation.Valid?,
    val mode: AnalysisMode = AnalysisMode.STANDARD,
)

internal data class AnalysisOutcome(
    val runId: String,
    val analysisId: String,
    val canonicalResult: ByteArray,
    val analysisDirectory: Path,
)

internal class AnalysisService(
    private val store: RunBundleStore,
    private val engineConfig: EngineConfig,
) {
    fun analyze(
        request: AnalysisRequest,
        processedBytes: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): AnalysisOutcome
}
```

The service alone composes parse, metrics, policy evaluation, closed identity,
`run.v1`, canonical `analysis-result.v1`, normalized NDJSON and rollups. An
invalid policy is rejected before `AnalysisRequest`; `capacity_step` returns
`UNSUPPORTED_ANALYSIS_MODE` before an analysis directory exists.

The immutable input is parsed in at most two streaming passes with the same
parser. Pass 1 validates and freezes validity, diagnostics and the complete
run window without retaining events. INVALID stops there. VALID/DEGRADED runs
use pass 2 to feed `MetricsAccumulator` with the final `runStart` and write
normalized artifacts. Logical job progress remains `0..input.sizeBytes`: pass 1
maps to `bytes / 2`, pass 2 to
`input.sizeBytes / 2 + (bytes + 1) / 2`; both passes check cancellation. No event
spool or second parser path is introduced. A pass-2 validity, diagnostic or
window mismatch fails internally before commit.

`run.v1` is analysis-scoped at
`analyses/<analysis_id>/run.json`, so a parser/configuration change cannot leave
first-writer metadata at run root. For VALID or DEGRADED input with at least one
complete event, set `schema_version` to `run.v1`, `run_id` to the accepted run
id and `analysis_mode` to `standard`; set `started_at` to the minimum event start
and `ended_at` to the maximum checked `start + elapsed`, formatting both as UTC
`Instant`; set the sole input's `type` to `SourceType.wireName`, `path` to
`inputs/source.bin` and `sha256` to the accepted input hash. No additional field
is emitted. INVALID input commits only `identity.json`, canonical
`analysis-result.v1` and their manifest; it does not emit `run.json` or
normalized/rollup artifacts.

Evaluation precedence is exact: invalid input cannot PASS; degraded input is
NO_VERDICT; missing/ambiguous required transaction makes the whole evaluated
policy NO_VERDICT even if another rule fails; otherwise any failed rule yields
FAIL and all passed rules yield PASS. No policy yields NO_POLICY only for a
valid run.

- [ ] **Step 1: Add failing state-matrix and golden-result tests**

  Cover PASS, FAIL, NO_POLICY, missing/ambiguous NO_VERDICT, DEGRADED and INVALID;
  all required and no additional top-level fields from the existing
  `analysis-result.v1` schema; typed evidence ids; deterministic ordering;
  identity golden; byte-identical repeat; exact `run.v1` fields for VALID and
  DEGRADED, absence of `run.json`/NDJSON for INVALID; full manifest hash/size
  validation including corruption rejection; an out-of-order fixture whose
  correct relative buckets require the first-pass minimum start; equal pass
  validity/diagnostics and monotonic two-pass progress; new policy creating a new
  analysis without changing the old result; unsupported mode creating no
  analysis.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.core.PolicyEvaluationTest" --tests "io.ltverdict.core.Analysis*"
  ```

- [ ] **Step 2: Implement the service and canonical artifacts**

  Reuse `parseInput` for both passes and stream second-pass NDJSON into the
  staging analysis directory; do not build the raw event list in memory. Write
  the manifest last, force files, then atomically commit the directory. Reuse a
  completed identical analysis only after validating every manifest entry's
  path, byte size and SHA-256.

- [ ] **Step 3: Run GREEN and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.core.*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/core/AnalysisResult.kt src/main/kotlin/io/ltverdict/core/AnalysisService.kt src/main/kotlin/io/ltverdict/core/Policy.kt src/test/kotlin/io/ltverdict/core/PolicyEvaluationTest.kt src/test/kotlin/io/ltverdict/core/AnalysisServiceTest.kt src/test/kotlin/io/ltverdict/core/AnalysisResultGoldenTest.kt
  git commit -m "feat(core): produce deterministic analysis results"
  ```

### Task 10: Add bounded jobs, progress, BUSY and cancellation

**Files:**

- Create: `src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt`
- Create: `src/test/kotlin/io/ltverdict/jobs/AnalysisJobsTest.kt`
- Create: `src/test/kotlin/io/ltverdict/jobs/ConcurrencyAcceptanceTest.kt`

**Interfaces:**

```kotlin
internal enum class JobState { QUEUED, PROCESSING, COMPLETE, FAILED, CANCELLED }

internal data class JobStatus(
    val jobId: String,
    val state: JobState,
    val processedBytes: Long,
    val totalBytes: Long,
    val runId: String,
    val analysisId: String?,
    val diagnostic: Diagnostic?,
)

internal sealed interface SubmitResult {
    data class Accepted(val status: JobStatus) : SubmitResult
    data object Busy : SubmitResult
}

internal class AnalysisJobs(
    parallelism: Int,
    analyze: (AnalysisRequest, (Long) -> Unit, () -> Unit) -> AnalysisOutcome,
) : AutoCloseable {
    fun submit(request: AnalysisRequest): SubmitResult
    fun status(jobId: String): JobStatus?
    fun cancel(jobId: String): JobStatus?
}
```

Use one fixed `ThreadPoolExecutor` of platform threads and an
`ArrayBlockingQueue` whose capacity equals validated parallelism. Accept only
`1..Runtime.getRuntime().availableProcessors()`. Job state is process-local;
accepted inputs and completed analyses are durable. Active states are retained;
after transition, keep only the 1,024 most recent terminal statuses in terminal-
transition order and evict the oldest terminal entry. Upload never enters this
executor.

- [ ] **Step 1: Add failing admission, cancel and isolation tests**

  With parallelism `1`, one running plus one queued means the third submit is
  BUSY. Cover queued cancel never starting, running cancel observing interruption
  and deleting only derived staging files, immutable input preservation,
  monotonic processed bytes within exact `totalBytes`, terminal state stability,
  deterministic oldest-terminal eviction after 1,024 entries and worker names
  proving they are not Netty event-loop threads.

  The concurrency acceptance test analyzes two different RunBundles in
  parallelism `2` and compares each canonical result byte-for-byte with fresh
  sequential analyses. A second case submits the same run/policy twice and
  proves both jobs converge on one intact `analysis_id` directory and manifest.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.jobs.*"
  ```

  Expected: test compilation fails because `AnalysisJobs` does not exist.

- [ ] **Step 2: Implement the bounded executor and cooperative cancel**

  Use an `AtomicBoolean` plus interrupt checks at parser record boundaries. A
  rejected submission returns `Busy`; it does not delete or mutate the accepted
  input. Do not add a durable queue or scheduler.

- [ ] **Step 3: Run GREEN and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.jobs.*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt src/test/kotlin/io/ltverdict/jobs/AnalysisJobsTest.kt src/test/kotlin/io/ltverdict/jobs/ConcurrencyAcceptanceTest.kt
  git commit -m "feat(jobs): bound analysis concurrency"
  ```

### Task 11: Add the secure loopback Ktor API

**Files:**

- Create: `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
- Create: `src/main/kotlin/io/ltverdict/web/LocalServer.kt`
- Create: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`
- Create: `src/test/kotlin/io/ltverdict/web/LocalSecurityTest.kt`

**Interfaces:**

```kotlin
internal data class LocalApiContext(
    val store: RunBundleStore,
    val jobs: AnalysisJobs,
)

internal fun Application.installLocalApi(context: LocalApiContext)

internal class StartedLocalServer(
    val engine: ApplicationEngine,
    val origin: String,
) : AutoCloseable {
    override fun close() = engine.stop(1_000, 5_000)
}

internal fun startLocalServer(
    context: LocalApiContext,
    port: Int = 0,
    openBrowser: Boolean = true,
): StartedLocalServer
```

**Private HTTP contract:**

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

Run lists are sorted by `run_id`; `after` is exclusive, `limit` defaults to 100
and `next_after` is the last returned id only when another item exists.
`JobStatus` mirrors the core exactly as
`{job_id,state,processed_bytes,total_bytes,run_id,analysis_id,diagnostic}`;
`analysis_id` is nullable, and `diagnostic` is null or
`{code,message,source_offset}` with nullable `source_offset`. `state` is
`QUEUED|PROCESSING|COMPLETE|FAILED|CANCELLED`. Bucket offsets are non-negative
and relative to run start; `from_ms` defaults to `0`, `to_ms` is optional,
`limit` defaults to `500`, and `next_from_ms` is the first omitted bucket start.
The server never returns more than 500 buckets, so the 100,000-bucket artifact
is not materialized in one response. Range reads stream the selected NDJSON
artifact and stop after the first omitted row; they do not load the artifact
into memory.

All other failures use
`{"error":{"code":"...","message":"...","details":[]}}`: `400` for
malformed JSON/multipart/query, `403` for Host/Origin/session/CSRF failure, `404`
for an unknown run/job/analysis, `409` for `BUSY`, `413` for upload or policy
size overflow, `415` for wrong content type and `422` for unsupported input.

`/api/bootstrap` sets one 256-bit random server-process session cookie
(`HttpOnly; SameSite=Strict; Path=/`) and returns a separate 256-bit CSRF token
kept only in page memory. Every HTTP request rejects a Host other than the actual
`127.0.0.1:<bound-port>`; POST/DELETE also require the exact corresponding
Origin, cookie and `X-LTV-CSRF` header. Do not install CORS.

Upload accepts exactly one multipart `file` part and streams it directly into
`RunBundleStore.acceptInput`; it never calls `readBytes`. Policy validation
requires `application/json` and passes the bounded raw request stream to
`validatePolicy`. Job creation accepts exactly one UTF-8 `run_id` form field
(at most 128 bytes) and zero or one raw `policy` part; the policy part is streamed
unchanged through the same 1 MiB validator, preserving duplicate-key detection.
An invalid policy returns the same `422 {valid:false,errors:[...]}` and creates no
job or analysis. Responses use JSON data only, including hostile labels.

Every response sets this baseline:

```text
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Cache-Control: no-store
```

- [ ] **Step 1: Add failing API/security tests**

  Cover bind host, bootstrap randomness/cookie flags, correct API flow, missing
  and wrong Host/Origin/session/CSRF, no CORS headers, wrong content type,
  streaming upload/policy overflow and raw duplicate/escaped-equivalent job
  policy with no accepted run/analysis, every exact request/response/status/error
  envelope, bounded run/bucket pagination and invalid ranges, terminal-job
  eviction, BUSY status, cancel, malicious HTML returned only as JSON text,
  CSP/`no-store` header values and absence of a runtime HTTP client dependency.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.web.*"
  ```

  Expected: test compilation fails because the web module does not exist.

- [ ] **Step 2: Implement the smallest private API and loopback server**

  Compute the allowed origin from the request's bound local port; do not reserve
  a port before Ktor starts. Browser auto-open failure logs the exact URL and
  leaves the usable server running.

- [ ] **Step 3: Run GREEN and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.web.*"
  .\gradlew.bat ktlintCheck
  git diff --check
  ```

  ```powershell
  git add src/main/kotlin/io/ltverdict/web/LocalApi.kt src/main/kotlin/io/ltverdict/web/LocalServer.kt src/test/kotlin/io/ltverdict/web/LocalApiTest.kt src/test/kotlin/io/ltverdict/web/LocalSecurityTest.kt
  git commit -m "feat(web): expose secure loopback API"
  ```

### Task 12: Add the three CLI commands and prove shell parity

**Files:**

- Create: `src/main/kotlin/io/ltverdict/Main.kt`
- Create: `src/main/kotlin/io/ltverdict/cli/CommandLine.kt`
- Create: `src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`
- Create: `src/test/kotlin/io/ltverdict/integration/ShellParityTest.kt`

**Public commands:**

```text
ltv ui [--data-dir <path>] [--analysis-parallelism <n>]
ltv analyze <input> [--policy <policy.json>] [--data-dir <path>]
ltv policy validate <policy.json>
```

**Interface:**

```kotlin
internal fun runCli(
    args: Array<String>,
    stdout: PrintStream = System.out,
    stderr: PrintStream = System.err,
): Int
```

Parse only these commands and flags with a short `when` loop. `analyze` opens
the common data-directory lock, rejects a symlink/non-regular input, validates
the optional policy, accepts and analyzes the stream through `AnalysisService`,
writes canonical result JSON to stdout and diagnostics to stderr. `policy
validate` is read-only and does not take the data lock. `ui` holds the lock until
server shutdown and defaults to one analysis thread.

- [ ] **Step 1: Add failing CLI and parity tests**

  Cover exact syntax/exit codes, unknown/duplicate flags, policy validation,
  symlink input, `DATA_DIR_BUSY`, stdout/stderr separation and no partial output
  on error. Parameterize `ShellParityTest` over JTL CSV/XML and Gatling
  text/binary goldens: CLI and the HTTP flow must produce the same `run_id`,
  `analysis_id` and canonical result bytes from separate data directories.

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.cli.*" --tests "io.ltverdict.integration.ShellParityTest"
  ```

  Expected: test compilation fails because the entry point does not exist.

- [ ] **Step 2: Implement the direct CLI composition**

  Do not add picocli, a dependency-injection container or shell abstraction.
  Ensure shutdown hooks close server, jobs and data lock exactly once.

- [ ] **Step 3: Build the distribution, run smoke commands and commit**

  ```powershell
  .\gradlew.bat test --tests "io.ltverdict.cli.*" --tests "io.ltverdict.integration.ShellParityTest"
  .\gradlew.bat installDist ktlintCheck
  .\build\install\ltv\bin\ltv.bat policy validate fixtures\slice1\policies\pass.json
  git diff --check
  ```

  Expected: tests/build/lint exit `0`; policy validation prints normalized valid
  policy and exits `0`.

  ```powershell
  git add src/main/kotlin/io/ltverdict/Main.kt src/main/kotlin/io/ltverdict/cli/CommandLine.kt src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt src/test/kotlin/io/ltverdict/integration/ShellParityTest.kt
  git commit -m "feat(cli): add local analysis commands"
  ```

### Task 13: Build the approved Vue shell and embed it in the distribution

**Files:**

- Create: `ui/package.json`
- Create: `ui/package-lock.json`
- Create: `ui/index.html`
- Create: `ui/tsconfig.json`
- Create: `ui/vite.config.ts`
- Create: `ui/eslint.config.js`
- Create: `ui/playwright.config.ts`
- Create: `ui/scripts/start-e2e-server.mjs`
- Create: `ui/scripts/verify-policy-schema.mjs`
- Create: `ui/src/main.ts`
- Create: `ui/src/api.ts`
- Create: `ui/src/types.ts`
- Create: `ui/src/App.vue`
- Create: `ui/src/RunSetup.vue`
- Create: `ui/src/PolicyEditor.vue`
- Create: `ui/src/JobStatus.vue`
- Create: `ui/src/AnalysisView.vue`
- Create: `ui/src/styles.css`
- Create: `ui/e2e/local-flow.spec.ts`
- Create: `ui/e2e/security-a11y.spec.ts`
- Create: `src/test/kotlin/io/ltverdict/e2e/E2eServerMain.kt`
- Modify: `src/main/kotlin/io/ltverdict/web/LocalServer.kt`
- Modify: `build.gradle.kts`
- Modify: `.gitignore`

**Package scripts:**

```json
{
  "scripts": {
    "typecheck": "vue-tsc --noEmit",
    "lint": "eslint .",
    "build": "vue-tsc --noEmit && vite build",
    "test:contracts": "node scripts/verify-policy-schema.mjs",
    "e2e:server": "node scripts/start-e2e-server.mjs",
    "e2e": "playwright test"
  }
}
```

Use only the fixed frontend ledger dependencies. ESLint enables
`vue/no-v-html` and rejects `localStorage` and `sessionStorage` member access.
The schema script checks every policy example against its declared
`schema_valid` value through Ajv; Kotlin `PolicyTest` independently checks
`runtime_valid` and diagnostic codes such as duplicate rule id. Ajv is dev-only
and absent from the Vite production bundle.

`App.vue` owns the small state machine; no router or store. `api.ts` keeps the
CSRF token in module memory, uploads with `XMLHttpRequest` for byte progress and
polls jobs with `fetch`. Policy download uses a client-side JSON Blob; result
export is absent.

Preserve `.superdesign/design-system.md` exactly:

- 224 px navigation, 56 px header and 1440 px desktop canvas hierarchy;
- stable ids `run-setup`, `job-status`, `verdict`, `summary-metrics`,
  `policy-results`, `transaction-metrics`, `normalized-data`;
- verdict strip, four cards (p50/p95/p99/throughput), error rate in the strip,
  policy table, transaction table and normalized-data table;
- transaction impact order: policy-failing scopes first, then error count, p99
  and sample count descending, then exact path/label ascending;
- normalized-data controls select 1/10/30/60-second rollup and start/end range;
- light/dark semantic tokens, first value from `prefers-color-scheme`, labelled
  memory-only toggle, visible 2 px focus, 44 px targets, semantic tables,
  text/icon statuses and reduced-motion handling;
- no chart/sparkline, DnD control, export-result action or feature placeholder.

Gradle defines OS-aware native `npmCi`, `uiTypecheck`, `uiLint`,
`uiContractTest`, `uiBuild` and `uiE2e` `Exec` tasks without a Node Gradle
plugin. `processResources` depends on `uiBuild` and uses
`from(layout.projectDirectory.dir("ui/dist")) { into("web") }`, placing the
assets in the standard resources output and packaged classpath; Ktor serves
`classpath:/web` with SPA fallback. When Gradle property `npmOffline=true` is
present, `npmCi` adds `--offline`; no separate build path is introduced.

The test-only `E2eServerMain` starts the real packaged API on
`127.0.0.1:18473`, disables browser auto-open and uses a new temporary data
directory. Gradle defines `runE2eServer` as `JavaExec`, depending on
`testClasses` and `processResources`, with `sourceSets.test.runtimeClasspath` and
the `E2eServerMain` main class. The Node launcher creates the temp directory,
chooses `gradlew.bat` on Windows and `./gradlew` elsewhere, launches
`runE2eServer -x npmCi -Pe2eDataDir=<that-directory>`, waits for the bootstrap
endpoint, forwards signals and removes only that known directory. Skipping only
`npmCi` is safe here because the launcher itself is invoked from the already
installed npm environment; it prevents Gradle from replacing `node_modules`
under the running Playwright process while still rebuilding `ui/dist`.
`playwright.config.ts` binds this launcher to every browser run with
`webServer.command: "npm run e2e:server"`, readiness URL
`http://127.0.0.1:18473/api/bootstrap`, a 120-second timeout and
`reuseExistingServer: false`.

- [ ] **Step 1: Create package metadata, buildable mount and real E2E harness**

  Add the smallest buildable Vue mount (`main.ts` plus an empty semantic
  `App.vue`) so the real Ktor/Playwright harness can start before behavior is
  implemented. This is test scaffolding only and contains no feature
  placeholder; Step 3 replaces it with the approved shell. In this step also
  implement `processResources`, `runE2eServer`, `E2eServerMain`, the Node launcher,
  Playwright `webServer` and Ktor classpath/static fallback described above;
  these are test/build plumbing, not UI behavior.

  ```powershell
  npm --prefix ui install --package-lock-only
  npm --prefix ui ci
  npm --prefix ui run test:contracts
  ```

  Expected: contract test exits `0`; lockfile contains exact resolved integrity
  hashes and no undeclared production dependency.

- [ ] **Step 2: Write Playwright tests first and confirm RED**

  `local-flow.spec.ts` covers the empty state, valid policy import/edit/download,
  all four golden uploads through the browser, processed-byte progress,
  PASS/FAIL/NO_POLICY/NO_VERDICT, BUSY/cancel, run list and light/dark structural
  parity.

  `security-a11y.spec.ts` covers HTML label text without execution, zero request
  outside the local origin, empty browser storage, exact security headers,
  keyboard order/focus/targets, semantic headers/text statuses, reduced motion,
  no prohibited placeholder and no critical/serious axe finding.

  ```powershell
  npm exec --prefix ui playwright install chromium
  npm --prefix ui run e2e
  ```

  Expected: the buildable empty mount and real server start, then Playwright
  exits non-zero on the new behavior assertions.

- [ ] **Step 3: Implement the approved shell and API client**

  Reuse the tracked design tokens verbatim. Render all untrusted strings through
  Vue interpolation. Keep policy form fields limited to the public schema.

- [ ] **Step 4: Verify the embedded distribution**

  Verify `runE2eServer` starts from the test runtime classpath, `installDist`
  contains the generated `/web` resources with no CDN URL and Playwright reaches
  the same hashed assets through the real Ktor server.

- [ ] **Step 5: Run frontend and browser GREEN checks**

  ```powershell
  npm --prefix ui run typecheck
  npm --prefix ui run lint
  npm --prefix ui run test:contracts
  npm --prefix ui run build
  npm --prefix ui run e2e
  .\gradlew.bat installDist
  ```

  Expected: every command exits `0`; axe has no critical/serious violation;
  browser requests stay on `http://127.0.0.1:18473`.

- [ ] **Step 6: Visually inspect both themes at 1440 px**

  Use the browser-control/frontend-design workflow to compare a completed FAIL
  run and NO_VERDICT state against `.superdesign/design-system.md`. Fix only
  deviations in hierarchy, tokens, spacing, focus or states; do not redesign the
  approved UI.

- [ ] **Step 7: Commit UI and embedding**

  ```powershell
  git add ui/package.json ui/package-lock.json ui/index.html ui/tsconfig.json ui/vite.config.ts ui/eslint.config.js ui/playwright.config.ts ui/scripts/start-e2e-server.mjs ui/scripts/verify-policy-schema.mjs ui/src/main.ts ui/src/api.ts ui/src/types.ts ui/src/App.vue ui/src/RunSetup.vue ui/src/PolicyEditor.vue ui/src/JobStatus.vue ui/src/AnalysisView.vue ui/src/styles.css ui/e2e/local-flow.spec.ts ui/e2e/security-a11y.spec.ts src/test/kotlin/io/ltverdict/e2e/E2eServerMain.kt src/main/kotlin/io/ltverdict/web/LocalServer.kt build.gradle.kts .gitignore
  git commit -m "feat(ui): add approved local analysis shell"
  ```

### Task 14: Add performance/offline gates, user docs and Slice 1 report

**Files:**

- Create: `tools/perf/generate_jtl.py`
- Create: `tools/perf/jtl_probe.sh`
- Create: `tools/test_generate_jtl.py`
- Create: `.github/workflows/runtime-quality.yml`
- Create: `docs/architecture/slice-1-local-runtime.md`
- Create: `docs/user/slice-1-local-analysis.md`
- Create: `docs/milestones/stage-1.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/development-plan-v0.6.md`

**Performance contract:**

`generate_jtl.py --rows 10000000 --seed 1 --output <path>` writes a deterministic
standard JMeter CSV without retaining rows. `jtl_probe.sh` generates a separate
1,000,000-row warm-up, then runs three fresh-process 10,000,000-row analyses in
three fresh data directories with two-CPU affinity, `-Xmx1536m` and a 600-second
timeout per run. It asserts each peak RSS is below 2 GiB, each elapsed time is at
most 600 seconds and all three canonical result SHA-256 values match. The warm-up
is excluded from measurements.

**CI:**

Create runtime and performance jobs on `ubuntu-latest`. Pin checkout to
`de0fac2e4500dabe0009e67214ff5f5447ce83dd`, setup-java to
`dd06d9cba3e5552c54d9f8ea23572deb30010f7c` and setup-node to
`820762786026740c76f36085b0efc47a31fe5020`. Use JDK 21, Node 24.14.0, Gradle
cache and npm lockfile cache. Runtime installs Chromium with dependencies and
runs the complete gate; performance runs the shell probe after runtime succeeds.

**Documentation:**

- Architecture doc explains the one-process flow, RunBundle layout, identity,
  two-pass normalization, executor/admission/terminal-retention model, bounded
  private API and security limits.
- User guide contains Windows/Linux quick start, upload/status/error meanings,
  all four policy metrics with JSON examples, import/edit/download, exact
  transaction matching, validation error table and the normative external-AI
  prompt copied unchanged from the approved spec.
- README points to install/run/guide; CHANGELOG records the user-visible Slice 1
  shell; roadmap becomes `Slice 1 — READY FOR REVIEW`.
- Milestone report records fresh commands, exit codes, the green CI run URL and
  job conclusions, changed-file scope, performance numbers, known limits and
  decision `PENDING_USER_REVIEW`. Without green runtime and performance jobs the
  milestone remains `GATE_PENDING`; it must not claim merge or final acceptance.

- [ ] **Step 1: Write the deterministic-generator test and confirm RED**

  The unit test generates 100 rows twice, compares SHA-256, validates header and
  row count and confirms spike/error positions from seed `1`.

  ```powershell
  python -m unittest tools.test_generate_jtl -v
  ```

  Expected: non-zero because `tools/perf/generate_jtl.py` does not exist.

- [ ] **Step 2: Implement the generator and probe, then obtain GREEN**

  Implement the two small streaming scripts exactly to the performance contract;
  do not introduce a benchmark framework or Python dependency.

  ```powershell
  python -m unittest tools.test_generate_jtl -v
  bash -n tools/perf/jtl_probe.sh
  ```

  Expected: both commands exit `0`.

- [ ] **Step 3: Add the CI workflow and run the local equivalent**

  ```powershell
  python tools/verify_slice0.py
  python -m unittest tools.test_verify_slice0 tools.test_generate_jtl -v
  .\gradlew.bat --no-daemon clean check installDist
  npm --prefix ui ci
  npm --prefix ui run typecheck
  npm --prefix ui run lint
  npm --prefix ui run test:contracts
  npm --prefix ui run build
  npm --prefix ui run e2e
  ```

  Expected: all commands exit `0` from a clean dependency state.

- [ ] **Step 4: Prove offline rebuild after dependencies are present**

  ```powershell
  npm --prefix ui ci --offline
  .\gradlew.bat -PnpmOffline=true --offline --no-daemon clean check installDist
  npm --prefix ui run e2e
  ```

  Expected: no network is required; all commands exit `0` with the previously
  acquired Gradle/npm/Chromium caches.

- [ ] **Step 5: Run the bounded large-JTL probe on Linux/CI**

  ```bash
  bash tools/perf/jtl_probe.sh
  ```

  Expected: three measured runs satisfy the time/RSS ceilings and print one
  shared canonical result SHA-256. If local Windows lacks `taskset` or GNU
  `/usr/bin/time`, record that limitation and require the Linux CI job before
  the milestone can pass.

- [ ] **Step 6: Write user/architecture documentation and candidate report**

  Record observed output only. Keep the AI prompt byte-for-byte semantically
  equivalent to the approved normative block and do not invent example SLAs.

- [ ] **Step 7: Run the full completion gate fresh**

  ```powershell
  python tools/verify_slice0.py
  python -m unittest tools.test_verify_slice0 tools.test_generate_jtl -v
  .\gradlew.bat --no-daemon clean check installDist
  npm --prefix ui ci
  npm --prefix ui run typecheck
  npm --prefix ui run lint
  npm --prefix ui run test:contracts
  npm --prefix ui run build
  npm --prefix ui run e2e
  npx --yes markdownlint-cli2@0.23.2 "**/*.md"
  git diff --check
  $secretMatches = git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .
  $secretExit = $LASTEXITCODE
  if ($secretExit -gt 1) { throw "secret scan failed with exit $secretExit" }
  if ($secretMatches) { $secretMatches; throw "potential secret found" }
  Write-Output "secret scan: OK"
  git status --short --branch
  ```

  Expected: all executable checks exit `0`; secret scan has no matches; status
  contains only the files owned by this task before commit.

- [ ] **Step 8: Commit gates and documentation**

  ```powershell
  git add tools/perf/generate_jtl.py tools/perf/jtl_probe.sh tools/test_generate_jtl.py .github/workflows/runtime-quality.yml docs/architecture/slice-1-local-runtime.md docs/user/slice-1-local-analysis.md docs/milestones/stage-1.md README.md CHANGELOG.md docs/development-plan-v0.6.md
  git commit -m "docs(slice-1): add runtime gate and user guide"
  ```

- [ ] **Step 9: Request independent code review and resolve findings**

  Use `superpowers:requesting-code-review` with a fresh high-effort reviewer for
  the full branch diff, routing security, concurrency, binary parsing, public
  contract and performance review to `gpt-5.6-sol` effort `max`. Verify every
  finding before changing code; use `superpowers:receiving-code-review` and add
  a regression test first for confirmed defects. Commit each bounded fix with an
  appropriate Conventional Commit.

- [ ] **Step 10: Re-run verification after the last review fix**

  Repeat Steps 5 and 7 from the final commit, inspect `git diff origin/main...HEAD`
  and confirm no unrelated, generated or temporary artifacts are tracked.
  Update the milestone report only with fresh final evidence and commit that
  report update separately:

  ```powershell
  git add docs/milestones/stage-1.md
  git commit -m "docs(slice-1): finalize stage 1 evidence"
  ```

- [ ] **Step 11: Prepare the reviewed branch for user-directed integration**

  Use `superpowers:finishing-a-development-branch` to report the exact final
  commit, fresh gate results, CI/performance status and remaining limitations.
  Do not push, open a PR, merge, tag or release without the user's explicit
  instruction.

## Definition of Done

- [ ] Approved spec and both accepted ADRs precede production implementation.
- [ ] Wrapper, JVM/npm dependency locks and verification metadata are tracked;
  the CSV candidate passed its bounded spike before promotion.
- [ ] `policy.v1` schema, valid/invalid examples, CLI validator, UI authoring and
  unchanged normative AI prompt agree on all four metrics.
- [ ] Independent JMeter CSV/XML and Gatling text/binary goldens match producer
  oracles; malformed/unknown/DTD/XXE inputs never PASS.
- [ ] Upload is streaming to 4 GiB, failed operations leave no staging residue,
  accepted input is immutable, manifests verify every analysis artifact and
  every writer honors the same data-directory lock.
- [ ] Two streaming passes, bounded identity retention, sparse 1-second buckets
  and merge-only 10/30/60 rollups preserve the out-of-order spike/drop golden
  and exact transaction hierarchy.
- [ ] VALID/DEGRADED/INVALID and PASS/FAIL/NO_POLICY/NO_VERDICT remain independent;
  missing or ambiguous transaction has an exact coverage reason.
- [ ] CLI and private HTTP/UI flow produce byte-identical canonical results for
  every source family.
- [ ] Bounded platform-thread jobs and terminal retention prove
  sequential/parallel parity, BUSY and cancellation without immutable-input
  loss; run and bucket APIs are cursor/range bounded.
- [ ] Server binds only `127.0.0.1`; session/CSRF/Origin/Host/CSP/no-store,
  file/XML and rendering trust boundaries pass malicious tests with no outbound
  requests.
- [ ] Approved light/dark UI hierarchy, accessibility and states pass at 1440 px;
  no chart, DnD, result export or feature placeholder is present.
- [ ] Ten-million-row Linux probe meets the 2 CPU, <2 GiB and <=600-second gate
  in three fresh runs with one deterministic result hash.
- [ ] Online and cached-offline builds, JVM tests/lint, UI typecheck/lint/build,
  Playwright/axe, docs lint, diff and secret checks all pass from the final commit.
- [ ] Full diff and independent review have no unresolved blocking findings;
  Stage 1 report is `PENDING_USER_REVIEW` until the user approves integration.

## Execution Handoff

The implementation starts only from an isolated `feat/slice-1-local-shell`
worktree after this approved design branch lands. Do not execute production
tasks on `docs/slice-1-design`.

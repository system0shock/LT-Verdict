# Resource statistics and window SLA implementation

> Execute in the existing `feat/resource-statistics` worktree using TDD.

**Spec:** [accepted design](../specs/2026-09-05-resource-statistics-design.md).
**ADR:** [0005](../../adr/0005-resource-window-sla.md).

## Global constraints

- No new dependencies or architecture layers. Preserve load-only bytes.
- Shared windows for business and hardware SLA; missing required data dominates
  observed failures. Diagnostic rules never change verdict.
- User authorized parallel work on disjoint files and one final concern review,
  then scoped fix verification. No repeated broad review waves.
- TDD literals from design; strict input bounds, cancellation, immutable bundles.
- Max 10 000 resource threshold findings globally; fail closed with
  RESOURCE_FINDINGS_LIMIT_EXCEEDED before allocation/publication beyond cap.
  Necessary bound: 256 rules over alternating 100k cells could emit 12.8M objects.
- Window histogram budget: at most 10 000 potential histograms, counting
  windows × (overall + retained policy identities); include in enriched identity.
- Baseline: f034632; Gradle offline check/installDist successful before changes.

## Frozen integration boundary

Core package `io.ltverdict.core`:
`validateResourceSnapshot(InputStream): ResourceValidation`, with `Valid` and
`Invalid(errors: List<PolicyValidationError>)`. `AnalysisRequest` adds final
optional `resources: ResourceValidation.Valid? = null`.
CLI `--resources PATH`; multipart field `resource_snapshot`.
UI `createJob(runId, policy?, resources?)` accepts optional File as third argument.

Snapshot JSON fields: `schema_version: "resource-snapshot.v1"`,
`load_input_sha256`, `start_epoch_ms`, `step_ms`, `point_count`, `series`,
optional `windows`, `rules`, `provenance`. Series: `id`, `metric`, `unit`,
`entity`, `role: system|generator`, `aggregation: interval_mean|interval_rate`,
optional `labels`, `values` (numbers/null). Window: `id`, `from_epoch_ms`,
`to_epoch_ms`. Rule: `id`, `series_id`, `unit`, `operator: gt|lt`, `threshold`,
`min_consecutive_cells`, `effect: diagnostic|sla`. All rules max 256; missing
referenced series is unevaluable evidence, known series unit mismatch invalid.
Provenance optional object: `source_kind`, `query_semantics`, `clock_alignment`
(bounded plain strings, no arbitrary fields). Unknown clock alignment explicit.

Evidence `resource_summary` fields: `id`, `type`, `series_id`, `metric`, `unit`,
`entity`, `role`, `aggregation`, `window_id`, `from_epoch_ms`, `to_epoch_ms`,
`expected_cells`, `observed_cells`, `missing_cells`, `longest_gap_cells`,
`statistics` (keys min,max,mean,median,q05,q25,q75,q95,iqr,mad,
sample_standard_deviation,slope_per_second,split_half_shift; decimal strings
or null), `reasons` (string array).
Evidence `window_policy_summary`: `id`, `type`, `window_id`, `from_epoch_ms`,
`to_epoch_ms`, `business_verdict`, `resource_verdict`, `verdict`.
Evidence `resource_policy_check`: `id`, `type`, `window_id`, `rule_id`,
`series_id`, `unit`, `operator`, `threshold` (decimal string), `effect`,
`status: PASS|FAIL|NO_VERDICT`, `reason` (string/null).
Existing business `policy_check` gets `window_id` and unique scoped ids.
Window checks include `scope` (same metric-scope shape) without dangling metric
references; overall metric summaries remain whole-run. `resource_binding` adds
deterministic run/snapshot/evaluation bounds and dropped-edge counts/durations,
mode and `clock_alignment: not_verified_by_core`; transport provenance excluded.
Core may add evidence fields, but coordinate any required field changes first.

## Task 1: Backend statistics and shared-window SLA

Read accepted design, ADR 0005 and Frozen integration boundary above (controller
copies this boundary into task brief). Own Kotlin core/metrics files and tests,
docs/contracts schema/example only. Do not edit CLI/API/reports/UI/general docs.
Implement strict bounded snapshot validator, type-7 statistics, gap/threshold
evidence, business window evaluation using existing metric and policy math,
joint verdict, enriched identity and immutable raw snapshot artifact.
No resources preserves old output. Invalid binding throws IllegalArgumentException
with safe message (same existing request failure path); no successful bad bundle.

RED then GREEN tests: design numeric literals and large offset, malformed bounds,
duplicate fields, hash/window binding, business samples crossing end retain full
latency, shared-window pass/fail/missing/no-policy, diagnostics don't fail verdict,
missing series/gap NO_VERDICT, previous load-only tests, immutable replay.
Do not multiply 64 windows by 10k transaction histograms: retain only policy-needed
transactions with explicit bounded aggregate window state, avoid source replay per
window. Membership dispatch must tolerate unsorted samples. Cancellation in loops.
Use existing strict scanner with minimal parameterization only if it fits;
no generic parser framework. Run targeted JVM tests; root owns full build.
Report exact commands and RED/GREEN evidence; no commits while parallel edits run.

## Task 2: UI optional input and resource results

Own UI files except `api.ts` (root owns it). Read current App/RunSetup/AnalysisView,
types and E2E flow. Add optional snapshot file input, carry selection into third
createJob argument, show statistics/coverage and business/resource/window verdicts
and resource rule outcomes. Preserve existing styles/accessibility, no redesign.
Render numbers as text, gaps/null explicitly, no inferred saturation/confidence.
Use Frozen integration boundary above (controller copies into brief).
Add focused Playwright test against real server with snapshot bound to uploaded
fixture SHA-256; verify failure and missing data displayed, saved result reload.
Write test first, run RED before UI code if server is not ready use existing
page/input behavior to establish RED. Run typecheck/lint and focused E2E when root
signals backend available. No production dependencies, no commits.

## Task 3: CLI/API, reports and integration (root)

Read existing entrypoints and tests. Add optional resources CLI flag and multipart
file validation using same core validator, bounded reads and existing errors.
API invalid resources returns structured errors before job. Existing no-resource
requests unchanged. HTML/AsciiDoc display typed resource evidence safely, including
window policy outcomes; JSON already retains all evidence. Add real integration
checks for both entrypoints, exported evidence, and parity.
Existing jobs expose only generic ANALYSIS_FAILED: add a fixed safe whitelist
for RESOURCE_LOAD_HASH_MISMATCH, RESOURCE_WINDOW_OUTSIDE_RUN and
RESOURCE_WINDOW_NO_FULL_CELLS so binding errors are actionable without exposing
arbitrary exception text. Test the whitelist and privacy fallback.
Update user docs, contracts validation script if necessary, CHANGELOG, roadmap.

## Task 4: Whole-concern verification

Inspect full diff, run fresh Gradle tests/check/installDist, UI typecheck/lint/E2E,
schema/examples, Markdown and secret checks using existing project commands.
One final independent correctness/security/public-contract review against f034632.
Address in-scope defects, scoped recheck only. Record test results and limitations.
No stage exit, merge, push or full-MVP completion claim.

## Verification record — 2026-09-05

Final-review correction scope: apply existing per-identity/aggregate byte limits
before first-pass policy transaction candidates are retained. Keep `run.json`
input paths uniformly run-root-relative: resource input references
`analyses/<analysis_id>/resource-snapshot.json`. No new limits, dependencies or
contract fields; add focused limit and artifact-resolution regression tests.
Align integer parsing with the existing JSON Schema: accept integral-valued
decimal/exponent tokens through bounded exact conversion, reject fractions.

Non-blocking review debt: unresolved/ambiguous transaction `policy_check.scope`
contains a selector label without `group_path`/`sample_kind`, while the UI type
currently requires those identity fields. Rendering and verdicts tolerate this;
model unresolved selector scope explicitly in a later contract cleanup without
fabricating identity fields. No runtime behavior change is included here.

- `gradlew.bat -PnpmOffline=true --offline --no-daemon ktlintFormat -x npmCi` — OK.
- `gradlew.bat -PnpmOffline=true --offline --no-daemon check installDist -x npmCi`
  — OK; JVM 158 tests, 156 passed, two existing Windows symlink skips.
- `npm --prefix ui run typecheck`, `lint`, `test:contracts` — OK.
- `npm --prefix ui run e2e` — 27/27 passed, including two resource scenarios;
  expected keyboard order updated for the new accessible file input.
- `python tools/verify_slice0.py` and `python -m unittest tools.test_verify_slice0
  tools.test_generate_jtl -v` — OK, four tests.
- `npx --offline --yes markdownlint-cli2@0.23.2 "**/*.md"` — 39 files, no issues.
- `git diff --check` — clean; local private-key/AWS-key pattern scan found no matches.
- Whole-concern review: no Critical, three Important corrected in one batch;
  scoped review of `aee8b11..ae7fabf` confirmed all three addressed, no new
  breakage or blockers. The Minor typed-scope debt is recorded above.
- After corrections: three regression tests RED/GREEN; fresh sequential
  `ktlintFormat`, `check installDist` — OK, JVM 161 tests, 159 passed, same two
  Windows skips. UI lint/contracts, Markdown and diff/secret checks — OK.
  UI runtime was unchanged by the correction batch; the 27/27 browser run stands.
- Remote CI/performance probe and real VM/Grafana not run for this local branch;
  Stage 1/full MVP gates are not closed.

# Local baseline comparison implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development.
> Пользователь согласовал ADR, оба baseline modes и продолжение реализации.

**Goal:** сохранить ручной или статистически выбранный baseline и сравнить с
ним overall metrics выбранного сохранённого analysis без повторного анализа.

**Architecture:** существующие RunBundleStore, loopback API и Vue UI.
Одна чистая calculation file, один private selection file и одна UI panel.
Нет service/repository interfaces, dependency additions или storage index.

**Tech Stack:** Kotlin/JDK 21, kotlinx.serialization, Vue 3, JUnit, Playwright.

**Spec:** [ADR 0004](../../adr/0004-local-baseline-selection.md), пользовательские
решения 2026-09-05 и delta design §18.3.

## Global Constraints

- База `367abdc`, ветка `feat/local-baseline-comparison`.
- Не изменять parser/metrics/policy/analysis identity или immutable RunBundles.
- Новых production dependencies и public schemas нет.
- Статистический выбор: 3..20 разных runs, valid/complete, confirmed planned
  conditions, P95/throughput/error rate, deterministic `median-rank-v1`.
- Фактический RPS не является comparability key или фильтром кандидатов.
- Active baseline остаётся фиксированным до явного POST/DELETE.
- Никаких fake jobs, пересчётов verdict, network source queries или localStorage.
- UI сохраняет существующие English copy, light/dark tokens, keyboard и a11y.
- Один итоговый review concern целиком; исправления проверяются только в scope
  findings. Этот согласованный режим не расширяется повторными full reviews.
- Root сохраняет unrelated root-worktree changes; push/merge/tag не выполнять.

## Shared private contract

```text
Reference = {run_id:string, analysis_id:string}
Score = {reference:Reference, score:integer}
Selection = {
  schema_version:"local-baseline.v1", series:string,
  mode:"manual"|"statistical", reference:Reference,
  algorithm:null|"median-rank-v1", candidates:Reference[], scores:Score[]
}
GET /api/baseline -> 200 {baseline:Selection|null}
POST /api/baseline (application/json):
  {mode:"manual",series:string,reference:Reference}
  OR {mode:"statistical",series:string,candidates:Reference[],comparable:true}
  -> 200 {baseline:Selection}
DELETE /api/baseline -> 200 {baseline:null}
GET /api/runs/{runId}/analyses/{analysisId}/comparison -> 200 {
  baseline:Selection, current:Reference,
  comparability:"UNCONFIRMED"|"USER_CONFIRMED",
  metrics:[{
    metric:string, unit:string,
    current:string|null, baseline:string|null, delta:string|null,
    delta_percent:string|null, reason:string|null, percent_reason:string|null
  }]
}
```

Metrics order: `response_time_p95_ms` (`ms`), `response_time_p99_ms` (`ms`),
`throughput_rps` (`requests/second`), `error_rate_ratio` (`ratio`). Display
numbers are decimal strings with up to 6 fractional digits, no exponent.
`reason`: `MISSING_METRIC` or `INCOMPATIBLE_METRIC_DEFINITION` or null.
`percent_reason`: reason, else `ZERO_BASELINE` if zero denominator, else null.
`delta_percent = 100 * (current - baseline) / baseline` before rounding.

Manual selection has candidates `[reference]`, scores `[]`, algorithm null.
Statistical candidates/scores are sorted by reference, include every candidate.
No fields supplied by user can prescribe winning reference or scores.
POST validation rejects unknown fields, invalid ids/types, empty/oversized
series, duplicate runs, unconfirmed/insufficient/invalid candidates. Body cap
16 KiB/depth 8; private file cap 32 KiB. Existing HTTP error envelope:
400 malformed/type/query, 404 missing reference/baseline, 413 resource cap,
422 statistical eligibility failure. Storage corruption remains explicit.
Every new endpoint rejects extra or duplicate query parameters.

## Task 1: Baseline calculations, persistence and private API

**Owner:** backend implementer owns core/store; root owns LocalApi and HTTP
tests alongside UI. File ownership was split after core GREEN to avoid waiting
for sequential storage/API implementation. No frontend or prose worker edits.

**Files:**

- Create `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt`.
- Modify `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt`.
- Modify `src/main/kotlin/io/ltverdict/web/LocalApi.kt`.
- Create `src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt`.
- Modify `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`.
- Modify `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`.
- Modify `src/test/kotlin/io/ltverdict/web/LocalSecurityTest.kt` if needed for
  security boundary coverage; no unrelated changes.

**Interfaces:** produces the exact HTTP contract above. Calculation accepts
validated JSON results/identities and pinned References. Private helper types
remain in the new calculation file; reuse JsonObject at store/API boundaries.

- [x] Write integration RED for GET/POST manual/reload/compare/clear; expected
  before implementation: missing baseline endpoint. Capture output.
- [x] Write statistical RED with real/literal candidates:
  `(P95,RPS,errors) = (100,100,0), (110,90,0), (1000,10,0)`; middle candidate
  wins regardless of request order. Ties resolve by reference. Duplicate runs,
  fewer than 3, missing metrics, degraded/invalid and mixed semantics reject.
- [x] Implement ranks with exact rational ordering:

```text
rank2(candidate, metric) = 2 * count(values < value) + count(values == value) + 1
score(candidate) = sum(abs(rank2(candidate, metric) - (n + 1)))
winner = minBy(score, run_id, analysis_id)
```

- [x] Implement safe state read/atomic replacement in RunBundleStore under its
  existing operationLock; reuse staging/forced-write/path helpers. Do not add
  a new store class. Validate all candidates before publishing selection.
  For failed update assert previous selection bytes unchanged. GET after
  DataDirectory close/reopen must return the same pinned reference.
- [x] Implement exact deltas, missing/zero behavior and semantic compatibility.
  Hand-derived checks: 100 -> 125 gives delta `25`, percent `25`; baseline 0
  to current 1 gives delta `1`, percent null/`ZERO_BASELINE`; different RPS
  alone still yields deltas; unknown planned context is `UNCONFIRMED`.
- [x] Add private routes with existing CSRF/Origin/session checks. Bound raw
  UTF-8 JSON before parsing; validate shape/depth before recursive processing.
  Existing policy scanner stays unchanged; no generic parser refactor.
- [x] Run focused JVM tests, report RED/GREEN, self-review and commit explicit
  backend files only. Root owns full build after frontend integration.

```powershell
.\gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*BaselineComparisonTest' --tests '*RunBundleStoreTest' --tests '*LocalApiTest' --tests '*LocalSecurityTest' ktlintCheck
```

## Task 2: Existing UI integration and browser checks

**Owner:** root; independent frontend files, shared HTTP contract frozen above.

**Files:** create `ui/src/BaselinePanel.vue`, `ui/e2e/baseline.spec.ts`; modify
`ui/src/App.vue`, `ui/src/api.ts`, `ui/src/types.ts`, `ui/src/styles.css` only
where required by the panel. `ui/e2e/security-a11y.spec.ts` additionally waits
for the real run list before capturing keyboard order: the expanded fixture
set exposed an asynchronous-listing race in the existing test. Assertions and
production focus behavior are unchanged.

- [x] Browser RED: real analyze, click `Set as baseline`, reload and see pinned
  baseline without any new job; initial failure is missing action.
- [x] Render the panel using existing section/table/notice controls and tokens.
  Pass current saved reference from App, without fake JobStatus. Default series
  label `Selected test series` is editable. Show full IDs through title/text.
- [x] Manual action posts the exact selected reference. Clear returns empty
  state. Baseline is visible after reload even before choosing current analysis.
- [x] A collapsed `Statistical selection` section accumulates selected analyses
  with add/remove actions, max 20 and one per run. Candidate set changes reset
  the `Same planned test conditions` checkbox; it means declared conditions,
  never equality of achieved RPS. Explain the 3-metric heuristic and limitations.
- [x] `Select statistically` posts frozen candidates and confirmation; show
  selected reference, algorithm and candidate scores. New analyses do not
  silently enter the candidate set or update baseline.
- [x] `Compare selected analysis` loads the comparison table. Show raw values,
  absolute delta and relative delta with reasons for N/A, explicit ratio units,
  and caution that deltas alone are not a regression verdict.
- [x] Protect baseline/comparison load and mutation responses with revisions.
  Selection changes immediately clear stale comparison; failed mutations leave
  last confirmed baseline visible. No previous-run response may overwrite a
  newer selection. All acquired content uses Vue text interpolation.
- [x] Real E2E covers manual persistence, known deltas with a changed RPS,
  statistical middle winner from 3 synthetic JTLs, fixed winner after another
  run, clear, failures and stale response. Existing axe/theme/keyboard checks
  continue to pass; add a baseline-panel axe check if not already reached.

```typescript
await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
await page.reload()
await expect(page.getByTestId('baseline-selection')).toContainText('manual')
```

```powershell
npm --prefix ui run lint
npm --prefix ui run typecheck
npm --prefix ui run e2e -- e2e/baseline.spec.ts
```

## Task 3: Documentation, integration and one final review

**Files:** this plan, `CHANGELOG.md`, `docs/user/slice-1-local-analysis.md`,
`docs/architecture/slice-1-local-runtime.md`, `docs/development-plan-v0.6.md`.

- [x] Document manual/statistical use, candidate confirmation, pinned selection,
  heuristic/rounding/N/A limitations, private file/routes and unchanged verdict.
- [x] Mark only implemented baseline slice portion; N-run history, charts,
  transaction comparison, exports and policy gates are not complete.
- [x] Run fresh full gate, inspect full diff; one Sol review of this concern,
  then only scoped fixes and their covering checks. Record limitations and
  local-only publication status. No Stage 1 acceptance or MVP completion claim.

```powershell
.\gradlew.bat -PnpmOffline=true --offline --no-daemon check installDist
npm --prefix ui run lint
npm --prefix ui run test:contracts
npm --prefix ui run e2e
python tools/verify_slice0.py
python -m unittest tools.test_verify_slice0 tools.test_generate_jtl -v
npx --offline --yes markdownlint-cli2@0.23.2 "**/*.md" "!ui/test-results/**"
git diff --check
```

Secret check uses existing tracked-file scan from `.github/workflows/runtime-quality.yml`.
Report exact test results; remote CI for this unpublished branch is unverified.

## Result — 2026-09-05

Implemented and locally verified at `9e4ea52`: full `check installDist` PASS,
130 JVM tests (128 passed, 2 Windows skips), full E2E 25/25; Kotlin/UI lint,
typecheck, contracts, Slice0/Python, Markdown and secret-pattern checks PASS.
One Sol Max review of `367abdc..9e4ea52`: READY, Critical 0, Important 0.
Optional UI-minor remains report-only: an earlier error alert can persist after
changing analysis, while comparison data is correctly cleared. No second full
review or unrequested UI refactor. The branch/worktree stays local; remote CI,
merge, Stage 1 acceptance and full MVP completion are not claimed.

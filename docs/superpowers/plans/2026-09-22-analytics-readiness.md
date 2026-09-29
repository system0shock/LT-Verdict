# Analytics Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing local N-run, transaction comparison, OpenSearch overlay, and capability-bounded JVM/OpenShift preparation without external calls or new statistical methods.

**Architecture:** Pure Kotlin helpers consume already saved `run.json`, `analysis-result.json`, and `identity.json` documents and return derived JSON for the local API. Standalone Vue components render those payloads; the root integrator owns shared API routing, TypeScript contracts, and application mounting.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization JSON, JUnit 5, Vue 3; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md` §§14–15, 18 and §28 items 12, 14, 22–23.

## Global Constraints

- N-run defaults to 10 exact-semantic-key local saved analyses and performs no external query.
- Comparison never stretches wall-clock data or interpolates gaps.
- OpenSearch overlays use the existing saved timeline and samples. Automatic correlation remains opt-in; a declared-template adapter may only prepare ordinary validated resource/correlation inputs and must not add new statistical methods.
- JVM/OpenShift output uses only present resource capabilities and existing explicit-threshold findings.
- Baseline selection, condition confirmation, correlation tuning, verdict semantics, and canonical analysis identity remain unchanged.
- No production dependency or shared integration-file change in this track.

## Review Focus

- Incomparable saved analyses are excluded with an explicit count/reason.
- Missing or zero baseline metric values produce `N/A` reasons rather than invented zeros.
- Transaction identity includes `group_path`, `label`, and `sample_kind`, preventing label collisions.
- OpenSearch epoch timestamps convert to relative run time without mixing separate runs.
- Missing pack capability yields `DEGRADED`/`SKIPPED`, never a healthy finding.

---

### Task 1: Saved-run and transaction comparison

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/RunComparison.kt`
- Test: `src/test/kotlin/io/ltverdict/core/RunComparisonTest.kt`

**Interfaces:**

- Consumes: saved reference, `run.v1`, `analysis-result.v1`, and `analysis-identity.v1` JSON.
- Produces: `buildRunDynamics(current, saved, baselineReference, limit)` and `compareTransactions(...)` JSON payloads.

- [ ] Write tests for exact comparability, newest-first default 10, previous/baseline deltas, transaction identity matching, missing values, and bounded filtering.
- [ ] Run `./gradlew --offline --no-daemon test -x npmCi --tests '*RunComparisonTest'` and confirm RED for missing functions.
- [ ] Implement the minimum pure comparison helpers.
- [ ] Re-run the focused class and confirm GREEN.

### Task 2: Saved OpenSearch overlays

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/AnalyticsOverlays.kt`
- Test: `src/test/kotlin/io/ltverdict/core/AnalyticsOverlaysTest.kt`

**Interfaces:**

- Consumes: run start and existing `opensearch_errors` evidence.
- Produces: `openSearchOverlay(run, result)` with run-relative timeline points and bounded sample markers.

- [ ] Write tests for relative alignment, multiple profiles, empty evidence, and no cross-run merge.
- [ ] Run `./gradlew --offline --no-daemon test -x npmCi --tests '*AnalyticsOverlaysTest'` and confirm RED.
- [ ] Implement extraction only; do not add acquisition or correlation.
- [ ] Re-run the focused class and confirm GREEN.

### Task 3: Capability-bounded metric packs

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/MetricPacks.kt`
- Test: `src/test/kotlin/io/ltverdict/core/MetricPacksTest.kt`

**Interfaces:**

- Consumes: existing `resource_summary`, `resource_policy_check`, and `resource_threshold_violation` evidence/findings.
- Produces: `metricPackAnalysis(result)` summaries for `jvm` and `openshift` with status, available/missing capabilities, and referenced existing findings.

- [ ] Write tests proving missing metrics are not interpreted as healthy and unrelated findings are excluded.
- [ ] Run `./gradlew --offline --no-daemon test -x npmCi --tests '*MetricPacksTest'` and confirm RED.
- [ ] Implement exact canonical metric-name classification and reference existing threshold findings only.
- [ ] Re-run the focused class and confirm GREEN.

### Task 4: Standalone UI components

**Files:**

- Create: `ui/src/RunDynamicsTable.vue`
- Create: `ui/src/TransactionComparisonTable.vue`
- Create: `ui/src/OpenSearchOverlay.vue`
- Create: `ui/src/MetricPackSummary.vue`
- Create: `ui/src/AnalyticsPanel.vue`
- Create: `ui/src/analyticsTypes.ts`

**Interfaces:**

- Consumes: root-owned TypeScript/API adapters via structurally typed props.
- Produces: accessible bounded tables and an SVG overlay; components perform no fetches.

- [ ] Add the components with table captions/headers, explicit `N/A` reasons, functional local row selection, filter limits, SVG title/legend, JSON export, and stale-request guards.
- [ ] Run `npm run typecheck` and `npm run lint` after root integration is idle.

### Task 5: Comparison exports

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/AnalyticsExport.kt`
- Test: `src/test/kotlin/io/ltverdict/core/AnalyticsExportTest.kt`

**Interface:** `renderRunDynamicsExport(dynamics, format)` renders only the already selected local rows as standalone HTML, AsciiDoc, or Confluence storage markup. Selection does not recalculate stored deltas.

- [ ] Test all three formats, contextual escaping, and explicit `N/A` reasons.
- [ ] Implement the minimum table renderer and let the root-owned API expose the format query.

### Task 6: Opt-in OpenSearch diagnostic input preparation

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/OpenSearchDiagnosticAdapter.kt`
- Test: `src/test/kotlin/io/ltverdict/core/OpenSearchDiagnosticAdapterTest.kt`

**Interface:** `prepareOpenSearchDiagnostics(contexts, loadInputSha256, base, templates)` converts already validated saved OpenSearch timelines and explicit templates to the existing validated `resource-snapshot.v1` and `correlation-plan.v1` inputs. Exact grids are required; resampling, interpolation, external calls, automatic enablement, verdict changes, and new correlation math are excluded.

- [ ] Test validated output and rejection of grid mismatch.
- [ ] Keep the helper unreachable by default until the root-owned CLI adds an explicit offline prepare command.

### Task 7: Verification and handoff

- [ ] Run the five focused JVM classes together.
- [ ] Run UI typecheck/lint for the standalone components.
- [ ] Inspect the scoped diff and report shared-file integration requirements, the exact offline CLI contract, and acceptance limitations.

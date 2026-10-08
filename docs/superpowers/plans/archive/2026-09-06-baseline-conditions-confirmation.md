# Baseline Conditions Confirmation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрыть `BASELINE-CONDITIONS-01` явным durable three-state решением для exact manual comparison binding.

**Architecture:** Existing core contract, `RunBundleStore`, loopback API и `BaselinePanel` получают минимальные additions. Каждый binding хранится одним canonical keyed file; нового service/repository слоя нет.

**Tech Stack:** Kotlin/JDK 21, kotlinx.serialization, Vue 3, JUnit 5, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-06-baseline-conditions-confirmation-design.md`

## Global Constraints

- Только active manual baseline; existing statistical semantics не меняются.
- Binding = обе exact references + оба window id либо overall null.
- `updated_at` создаёт сервер; authenticated actor не фабрикуется.
- Existing formulas, thresholds, SLA, verdict и immutable analyses не меняются.
- Новых dependencies, CLI, Jenkins и global schemas нет.
- Root один запускает Gradle/npm проверки; implementation worker команды не запускает.

---

### Task 1: Canonical condition contract

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt`
- Modify: `src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt`

**Interfaces:**

- Produces: `baselineConditionRecord`, `baselineConditionBinding`, `validateBaselineCondition`, `baselineConditionMatches`, `baselineConditionConfirmation`.

- [ ] Write literal RED for all three decisions, canonical timestamp, exact pair/window matching and malformed state.
- [ ] Root runs `./gradlew.bat --offline --no-daemon test --tests '*BaselineComparisonTest'` and records expected unresolved contract failure.
- [ ] Implement exact record/binding parser and nullable mapping with no comparison math changes.

### Task 2: Keyed durable storage

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt`
- Modify: `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`

**Interfaces:**

- Consumes: canonical core binding/record helpers.
- Produces: `readBaselineCondition(baseline,current,windows)` and `replaceBaselineCondition(record)`.

- [ ] Write RED for two simultaneous bindings, cross-pair/window miss, reopen, clear and corrupt/oversized/special path.
- [ ] Store under server-derived SHA-256 filenames using existing lock/staging/force/atomic move pattern.
- [ ] Keep records across baseline replacement; remove all only on explicit baseline clear.

### Task 3: Private API and Vue workflow

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
- Modify: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`
- Modify: `ui/src/BaselinePanel.vue`
- Modify: `ui/src/api.ts`
- Modify: `ui/src/types.ts`
- Modify: `ui/e2e/baseline.spec.ts`

**Interfaces:**

- Produces: exact GET/POST contract from the spec and comparison `conditions` field.

- [ ] Write API RED for authorization, exact body/query limits, three states, cross-binding behavior and clear.
- [ ] Add manual-only routes and exact lookup before `compareAnalyses`; UNKNOWN never enters statistical default path.
- [ ] Add accessible radio/save/status UI with revision protection and binding-driven reload.
- [ ] Extend real E2E through confirmed, not-confirmed, unknown, reload and pair reselect without a new job.

### Task 4: Verification and handoff

**Files:** no production changes.

- [ ] Root runs focused JVM tests and Kotlin lint.
- [ ] Root coordinates `npm --prefix ui run lint`, typecheck and targeted `baseline.spec.ts`.
- [ ] Root inspects owned diff, integrates other tracks, updates CHANGELOG/global plan/handoff and performs final review/gate.

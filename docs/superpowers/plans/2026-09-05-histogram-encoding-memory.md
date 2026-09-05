# Histogram Encoding Memory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: execute inline with
> `superpowers:executing-plans`; this task explicitly prohibits delegation and
> additional reviewers.

**Goal:** Устранить воспроизводимый OOM при завершении анализа большого JTL,
не меняя результаты или контракты Slice 1.

**Architecture:** Сохранить существующие `PackedHistogram` и общий путь
сериализации. Сжатие выполнять на короткоживущей копии, чтобы HdrHistogram не
удерживал свой полный compression buffer в каждом sparse bucket.

**Tech Stack:** Kotlin/JVM 21, HdrHistogram 2.2.2, JUnit 5, Gradle.

**Spec:** `docs/adr/0003-policy-v1-metrics-evidence.md`

## Scope

```text
REQUESTED: close the proven Slice 1 benchmark OOM
REQUIRED TO ACHIEVE IT: temporary-copy encoding in the shared helper, one regression, and fresh gates
NOT REQUIRED: heap/config/schema/identity/dependency/generator changes or refactoring
EXPECTED FILES TO CHANGE: Metrics.kt, MetricsTest.kt, CHANGELOG.md, this plan, and stage-1.md if local Linux evidence is obtained
```

No production dependency or public-contract change is allowed. Canonical
HdrHistogram compressed V2 bytes must remain unchanged.

## Acceptance criteria

- `MetricsAccumulator.finish()` completes for 10,000 sparse one-second buckets
  under the existing test JVM heap.
- Bucket and rollup counts plus representative bucket values remain correct.
- Existing normalization golden tests remain green.
- `clean check installDist` passes.
- The unchanged Linux probe completes three 10M-row runs within `600 s` and
  `< 2 GiB RSS`, with one shared result SHA-256.
- Stage 1 remains `GATE_PENDING` and CI jobs remain `NOT_RUN`.

### Task 1: Bound histogram compression retention

**Files:**

- Modify: `src/test/kotlin/io/ltverdict/metrics/MetricsTest.kt`
- Modify: `src/main/kotlin/io/ltverdict/metrics/Metrics.kt`
- Modify: `CHANGELOG.md`
- Conditionally modify: `docs/milestones/stage-1.md`

- [ ] Add one test that records one 42 ms sample in each of 10,000 seconds,
  calls `finish()`, and checks the 10,000/1,000/334/167 bucket counts plus the
  first and last one-second bucket values.
- [ ] Run `gradlew.bat test --tests "io.ltverdict.metrics.MetricsTest"` and
  confirm RED from heap exhaustion in compressed bucket encoding.
- [ ] In `compressedV2Base64()`, call `copy()` once and encode that temporary
  histogram; document HdrHistogram's retained compression buffer in one
  comment.
- [ ] Run the focused metrics and normalization tests and confirm GREEN.
- [ ] Add one `Unreleased / Fixed` changelog entry.
- [ ] Run `gradlew.bat --no-daemon clean check installDist`.
- [ ] Run unchanged `tools/perf/jtl_probe.sh` in WSL with the built distribution
  and record all three elapsed/RSS/hash values in the milestone without
  changing its pending CI/acceptance state.
- [ ] Inspect the complete diff, run `git diff --check`, stage only these files,
  and commit as `fix(metrics): bound histogram encoding memory`.

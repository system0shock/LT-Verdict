# Correlation Headline Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подключить frozen MBB/Holm selector к production correlation headlines без изменения raw evidence и SLA verdict.

**Architecture:** Новый internal selector выполняет bounded JVM calculation над уже собранными pair vectors. `DiagnosticAnalysis` сохраняет прежний pair evidence, добавляет selector evidence и пропускает finding только при statistical selection; root отдельно меняет diagnostic identity module version.

**Tech Stack:** Kotlin/JVM 21, JDK `MessageDigest` и `java.util.Random`, kotlinx.serialization JSON, JUnit 5; новых dependencies и Python runtime нет.

**Spec:** `docs/superpowers/specs/2026-09-06-correlation-headline-selection-design.md`

## Global Constraints

- Frozen method: `B=999`, blocks `10/20`, lag-max statistic, `q=max(p10,p20)`, один Holm, `alpha=0.05`.
- Не менять materiality/sign thresholds, raw pair semantics, SLA verdict, episodes или comparisons.
- Genuine partial остаётся descriptive/unavailable; ineligible hypothesis входит в Holm как `p=1`.
- JVM RNG — `java-random-sha256-seed.v1`, не bit-identical NumPy PCG64; диапазон `7.7-13.2%` не является JVM guarantee.
- Не запускать broad/full Monte Carlo, Gradle clean или Jenkins.

---

### Task 1: Зафиксировать selector behavior в RED tests

**Files:**

- Create: `src/test/kotlin/io/ltverdict/core/CorrelationHeadlineSelectionTest.kt`
- Modify: `src/test/kotlin/io/ltverdict/core/DiagnosticAnalysisTest.kt`

**Interfaces:**

- Consumes: утверждённый interface из spec.
- Produces: literal contract для p-values/Holm и integration contract для genuine partial.

- [ ] **Step 1: Добавить fixed-draw fixture**

  Для `seedMaterial="fixture"`, 40 cells, `lag=0` и трёх hypotheses зафиксировать:
  `strong p10=.001,p20=.012,q=.012,Holm=.036,selected=true`;
  `noise p10=.614,p20=.549,q=.614,Holm=1`; третий unavailable имеет public
  p `null`, но увеличивает Holm family до трёх.

- [ ] **Step 2: Добавить scope и genuine-partial fixtures**

  `n=241` возвращает `OBSERVATION_COUNT_UNSUPPORTED` без bootstrap. Реальный
  `evaluateDiagnostics` сохраняет raw `status=CANDIDATE` для partial pair,
  добавляет `GENUINE_PARTIAL_UNCALIBRATED` и не создаёт correlation finding.

- [ ] **Step 3: Root запускает focused RED**

```powershell
./gradlew.bat --offline --no-daemon test --tests io.ltverdict.core.CorrelationHeadlineSelectionTest --tests io.ltverdict.core.DiagnosticAnalysisTest
```

Expected: compile failure, потому что selector types/function ещё отсутствуют.

### Task 2: Реализовать минимальный bounded selector

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/CorrelationHeadlineSelection.kt`

**Interfaces:**

- Consumes: `CorrelationHeadlineHypothesis` list и semantic `seedMaterial`.
- Produces: один `CorrelationHeadlineSelection` на каждый входной hypothesis в исходном порядке.

- [ ] **Step 1: Добавить fixed constants и support checks**

  Зафиксировать `999`, `10/20`, `.05`, `16`, `240` и `150_000_000`; unsupported
  family возвращает `UNAVAILABLE`, не меняя настройки автоматически.

- [ ] **Step 2: Добавить stdlib bootstrap**

  SHA-256 -> signed Long -> два `Random` streams на block; общий resource index
  vector, независимый outcome vector, full reranking, fixed-anchor lag max и
  exceedance `>=`.

- [ ] **Step 3: Добавить один Holm по полной family**

  Использовать `q=max(p10,p20)`, `p=1` для unavailable, stable canonical tie
  order; finding eligibility требует одновременно Holm rejection и прежний
  material candidate.

### Task 3: Подключить selector без изменения raw evidence

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/DiagnosticAnalysis.kt`

**Interfaces:**

- Consumes: selector interface Task 2.
- Produces: `correlation_headline_selection` evidence; existing finding shape остаётся прежним.

- [ ] **Step 1: Сохранить pair calculation inputs**

  `PairResult` дополнительно несёт hypothesis из longest continuous complete
  run. `GENUINE_PARTIAL_UNCALIBRATED` задаётся, если full или lag association
  реально использовала control.

- [ ] **Step 2: Выполнить selector один раз**

  После всех pair/window evaluations вызвать selector со всей family и semantic
  plan/snapshot seed; добавить selector evidence, а prebuilt finding — только
  для `selected=true`.

- [ ] **Step 3: Root запускает focused GREEN**

```powershell
./gradlew.bat --offline --no-daemon test --tests io.ltverdict.core.CorrelationHeadlineSelectionTest --tests io.ltverdict.core.DiagnosticAnalysisTest
```

Expected: PASS без запуска MC corpus.

### Task 4: Identity и contract integration

**Files:**

- Root modify: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt`
- Root test: existing identity test file
- Create: `docs/contracts/diagnostics/v1/correlation-headline-selection.md`
- Create: `docs/adr/correlation-headline-selection.md`

**Interfaces:**

- Consumes: evidence fields Task 3.
- Produces: diagnostic module version `2` only when diagnostics exist; documented public evidence.

- [ ] **Step 1: Root меняет identity module version**

  Представить modules как `(id,version)` и назначить version `2` только
  `load-resource-diagnostics`; остальные module versions остаются `1`, input
  version остаётся `correlation-plan.v1`.

- [ ] **Step 2: Проверить identity regression**

  Без diagnostics canonical identity должен остаться прежним; с diagnostics
  JSON содержит ровно один diagnostic module version `2`.

- [ ] **Step 3: Root выполняет общий gate и review**

  Root выбирает fresh applicable Gradle/UI/docs commands после объединения трёх
  параллельных tracks. Worker не запускает Gradle параллельно и не делает commit,
  stage, push, merge, clean или Jenkins.

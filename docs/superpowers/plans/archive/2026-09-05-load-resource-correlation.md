# Useful run diagnostics implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Follow task ownership; no worker subagents.

**Goal:** шумоустойчивые эпизоды, описательная корреляция и оконное сравнение двух runs.

**Architecture:** Existing Kotlin/JDK core, optional validated plan, immutable
evidence; существующие CLI/API/UI/reports и baseline comparison. Никаких новых
production dependencies, services или registries.

**Tech Stack:** Kotlin/JDK21, kotlinx JSON, HDR, Ktor, Vue/TypeScript, JUnit/Playwright.

**Spec:** `docs/superpowers/specs/2026-09-05-load-resource-correlation-design.md`,
Accepted amendment после анализа Astra, плюс `docs/statistical-method-roadmap.md`.

## Global Constraints

- Один прогон: descriptive rank association и explicit-reference episodes.
- Два прогона: observed differences, не population/causal regression.
- Uncertainty `NOT_ESTIMATED`, p-values/Holm/automatic change-point отсутствуют.
- `policy.v1`, `resource-snapshot.v1` и no-plan identity/result bytes неизменны.
- Root original worktree не трогать. Implementation base `4c23aef`.
- Один итоговый concern review, после него только scoped fix verification.
- Parallel independent ownership разрешён пользователем; один Gradle процесс
  одновременно, root выполняет final gate. Не коммитить параллельные edits.
- Shared defaults/limits ниже обязательны, никаких silently dropped inputs.

## Frozen integration boundary

`correlation-plan.v1` JSON (1MiB, depth12, existing strict lexical scanner and
decimal limits): required schema_version, resource_snapshot_sha256; arrays
`pairs` and `anomalies` optional empty, хотя бы один элемент суммарно.
Unknown/duplicate fields/ids rejected; ids128 UTF8 bytes, plain text512 bytes.
Pairs<=16, anomalies<=32, sum pair windows<=128; unique window ids in each list.
resource binding checked before job; resolved-window binding checked by core.

```json
{
  "schema_version":"correlation-plan.v1",
  "resource_snapshot_sha256":"<semantic SHA256>",
  "pairs":[{
    "id":"cpu-latency","resource_series_id":"cpu",
    "load_metric":"response_time_p95_ms","window_ids":["steady"],
    "expected_sign":"positive","max_lag_ms":0,"min_abs_effect":0.3,
    "min_resource_delta":0.1,"min_load_delta":20,
    "controls":[{"meaning":"target_rps","series_id":"target"}],
    "topology_basis":"node serving the tested workload","clock_alignment":"unknown"
  }],
  "anomalies":[{
    "id":"cpu-episode","signal":{"series_id":"cpu"},
    "reference_window_id":"reference","window_id":"steady",
    "direction":"increase","min_abs_delta":0.2,
    "min_duration_ms":15000,"z_threshold":3.5
  }]
}
```

Pair required: id/resource_series_id/load_metric/window_ids/min_resource_delta/
min_load_delta/topology_basis. Other fields have shown defaults; controls=[];
expected_sign default either. Controls exactly {meaning,series_id}, except
achieved RPS {meaning:"achieved_rps"}; <=4, duplicate/control=source/outcome
rejected. Supported load metrics: response_time_p95_ms,error_rate,throughput_rps.
Meaning units follow accepted spec. Non-negative ranges checked before job.
Anomaly required all fields except z_threshold default3.5; signal exactly
one series_id or load_metric. direction increase|decrease|either. min_abs_delta>0,
min_duration_ms>0 whole aligned cells, z_threshold>0<=100. Ref != evaluation,
resolved windows disjoint. Max lag<=60000ms, <=10 cells, grid-aligned. Pair
delta minima>0; coefficient cutoff in(0,1]. No generic metric expression syntax.

Backend exposes:

```kotlin
// core/DiagnosticPlan.kt
internal sealed interface DiagnosticValidation {
    class Valid /* immutable typed plan, sha256, defensive rawBytes() */
    data class Invalid(val errors: List<PolicyValidationError>)
}
internal fun validateDiagnosticPlan(source: InputStream, maxBytes: Int = 1048576): DiagnosticValidation
internal fun validateDiagnosticBinding(plan: DiagnosticValidation.Valid, resources: ResourceValidation.Valid): List<PolicyValidationError>
// AnalysisRequest adds final optional diagnostics: DiagnosticValidation.Valid? = null
```

`Valid.sha256` is canonical semantic plan hash, `Valid.rawBytes()` immutable copy.
Core may choose private typed fields but above entrypoints/names are fixed.
Safe binding failure codes `DIAGNOSTIC_RESOURCE_REQUIRED`,
`DIAGNOSTIC_SNAPSHOT_MISMATCH`, `DIAGNOSTIC_WINDOW_NOT_FOUND`,
`DIAGNOSTIC_INVALID_BINDING`. CLI maps invalid plan/binding to4; API422
`INVALID_DIAGNOSTICS` with structured errors. No arbitrary exception text.

Analysis optional diagnostics append evidence/findings without changing
PolicyEvaluation.verdict or coverage reasons; module errors local to evidence.
Raw plan `correlation-plan.json` saved and manifest-covered, run.json reference
`analyses/<analysisId>/correlation-plan.json`; identity adds hash, module/limits
only with plan. Cache cannot change input assumptions via unhashed provenance.

UTC load collector in existing second pass: only selected resolved cells,
same start-membership/full-latency as SLA; no rebin of run-relative buckets.
Potential existing window histograms + diagnostic cell histograms<=10000;
overbudget skips optional collector/evaluation with diagnostic LIMIT_EXCEEDED,
not SLA. Empty covered cell RPS=0, error/latency=null. For cell P95 require
sample_count>=20; otherwise missing diagnostic latency with count visible.
This is an engineering tail-support floor, not a confidence level. Window
comparison uses full window histogram (no average percentiles), shows counts.

Statistics: spec partial ranks/lag scheme, min30 paired cells; near-zero
residual threshold relative norm<=1e-10; constant/dropped controls explicit.
Zero-lag and lag summaries remain distinct. Strong coefficient alone cannot
create finding; candidate requires paired range X/Y >=declared minima and
effect/sign. A material association with missing context => DESCRIPTIVE, not a headline.
Use `DESCRIPTIVE` if no explicit window or no target/concurrency control;
insufficient data, below-effect and opposite-sign abstentions take precedence.
constant declared controls within explicit window are valid context but not
varying regressors. CANDIDATE remains association only. No synthetic confidence.

Anomalies require >=30 non-null ref cells; gaps in ref allowed with coverage
warning, gaps in evaluation break episodes. Modified Z=0.6745*delta/MAD,
with exact BigDecimal thresholds. MAD=0 uses absolute threshold only and
ZERO_MAD limitation. Both abs and z gates inclusive, duration inclusive;
group adjacent passing cells of same direction, split sign reversal. Record
short episodes count, no duplicate finding per sample. Overall episode output
<=1000; if exceeded optional diagnostic returns LIMIT_EXCEEDED without partial
findings. SLA evidence unchanged. Cancellation in loops; one pair scratch at
a time, ranking O(nlogn), no resampling or O(n²) change point search.

### Evidence shared with UI/reports/comparison

Numbers decimal strings or null, counts/timestamps JSON integers; IDs stable.

- `diagnostic_summary`: id,type,status (COMPLETE|LIMIT_EXCEEDED|NOT_EVALUATED),
  pairs_tested,pairs_evaluable,anomalies_tested,episodes_reported,
  suppressed_short_episodes,uncertainty="NOT_ESTIMATED",reasons[].
- `correlation_pair`: id,type,pair_id,window_id,resource_series_id,load_metric,
  entity,resource_unit,load_unit,from_epoch_ms,to_epoch_ms,expected_cells,
  paired_cells,lag_used_cells,raw_rho,partial_rho,best_lag_ms,best_lag_rho,
  lag_profile:[{lag_ms,rho}],status (CANDIDATE|DESCRIPTIVE|BELOW_EFFECT|
  OPPOSITE_SIGN|INSUFFICIENT_DATA),controls_requested[],controls_used[],
  controls_dropped[],sensitivity_without_achieved_rps,reasons[],
  uncertainty="NOT_ESTIMATED". No p or fabricated confidence.
- `anomaly_check`: id,type,rule_id,window_id,reference_window_id,status
  (CANDIDATE|NO_MATERIAL_CHANGE|INSUFFICIENT_DATA),reference_median,reference_mad,
  reference_observed_cells,reference_expected_cells,observed_cells,expected_cells,
  episodes_reported,suppressed_short_episodes,reasons[].
- `anomaly_episode`: id,type,rule_id,window_id,reference_window_id,metric,unit,
  entity,from_epoch_ms,to_epoch_ms,duration_ms,direction,reference_median,
  reference_mad,observed_min,observed_max,max_abs_delta,evidence_id,reasons[].
  Episode appears once in findings referencing anomaly_check.
- `window_metric_summary` emitted only with plan, per all resolved windows:
  id,type,window_id,from_epoch_ms,to_epoch_ms,sample_count,error_count,
  error_rate_ratio:{numerator,denominator} or null,
  throughput_rps:{numerator,denominator},latency_ms:{p50,p95,p99,max}.
  Also `resource_bindings`:[{series_id,metric,unit,entity,role,aggregation,labels}]
  for exact cross-run resource identity matching. Resources statistics remain
  existing resource_summary evidence. No copied raw timeseries in output.

### Two-run comparison contract

```kotlin
internal data class WindowComparisonRequest(
    val baselineWindowId: String,
    val currentWindowId: String,
    val minChangePercent: BigDecimal = BigDecimal("5"),
    val minErrorRateDelta: BigDecimal = BigDecimal("0.001"),
)
// compareAnalyses adds final windows: WindowComparisonRequest? = null
```

Existing GET comparison accepts optional paired query parameters
baseline_window,current_window; with them optional min_change_percent(>0<=1000)
and min_error_rate_delta(>0<=1). No window selection => old response unchanged.
Output adds window_comparison:{status,baseline_window,current_window,
min_change_percent,min_error_rate_delta,reasons[],metrics:[...]}.
Также baseline_sample_count,current_sample_count,baseline_duration_ms,
current_duration_ms — JSON integer|null; baseline_window/current_window — ids.
Rows reuse metric/unit/current/baseline/delta/delta_percent/reason/percent_reason;
add status DESCRIPTIVE|NO_MATERIAL_CHANGE|CANDIDATE|INSUFFICIENT_DATA,
entity/resource_series_id optional and evidence refs. Load rows p50/p95/p99,
throughput,error_rate; resource median/q95 for exact matched binding incl labels.
Missing/ambiguous resource binding => explicit noncomparable row/reason, not
silently omitted. Load counts/window durations visible. No relative threshold
when baseline0; error has absolute ratio threshold, other zero baselines remain
DESCRIPTIVE with ZERO_BASELINE (do not claim no change). Unconfirmed conditions
keep observed material delta but window status DESCRIPTIVE/reason
CONDITIONS_UNCONFIRMED, never claim product regression. Invalid metric semantics
or unavailable window summaries => NOT_EVALUATED, preserve old raw comparisons.

## Task 1: Diagnostic core and immutable integration

**Files:** create core/DiagnosticPlan.kt, core/DiagnosticAnalysis.kt,
metrics/UtcLoadMetrics.kt; modify core/AnalysisService.kt and AnalysisResult.kt;
matching JUnit tests, diagnostic schema/example under docs/contracts/diagnostics/v1.
Only minimal existing scanner/helper visibility changes if required.
Consumes existing resource/window/metric types; produces frozen entrypoints/evidence.

- [x] Write real validator/statistics/service regressions before implementation:

```kotlin
// Hand-derived anomaly fixture: ref=[99,100,101] repeated 40 times,
// evaluation five160 cells at step5000, min_delta20,min_duration15000.
assertEquals("100", check["reference_median"]?.jsonPrimitive?.content)
assertEquals(25000, episode["duration_ms"]?.jsonPrimitive?.int)
// Cell ties and identical monotone arrays -> rho1; reversed -> rho-1.
// Same target driver removes rank residual variation -> null with reason.
```

- [x] Run focused tests RED, implement strict plan and pure functions using
  stdlib/existing BigDecimal+histogram helpers, run GREEN. Include null/gaps,
  zeroMAD, shortspike, sign reversal, p95support, controls and malicious inputs.
- [x] Integrate optional collector and evidence; tests immutable replay,
  unchanged no-plan golden bytes, SLA invariance under diagnostic limits,
  rawplan artifact path/hash, fractional run start and full sample latency.
- [x] Test synthetic null/positive scenarios at report level with fixed seeds;
  record observed rates without inferential calibration claim.
- [x] Report RED/GREEN exact commands/output and self-review; no worker commit.

## Task 2: Window baseline comparison

**Files:** core/BaselineComparison.kt and its tests only. No API/UI changes.
Consumes frozen window_metric_summary/resource bindings plus existing summary;
produces compareAnalyses optional WindowComparisonRequest and JSON above.

- [x] RED test hand-authored summaries baselineP95=100,current=120 gives delta20
  and20%; unequal window durations normalize rates, not raw counts.
- [x] Implement minimal optional branch, preserve default comparison response.
- [x] GREEN tests same-stage/different stage mixture, resource match incl labels,
  missing windows/metrics, baseline0, technical mismatch, threshold boundaries,
  unconfirmed conditions and error delta0.001. No time-series reanalysis/IO.
- [x] Report evidence; no worker commit, no files outside ownership.

## Task 3: UI analysis and comparison

**Files:** ui/src including api.ts, existing views/types; ui/e2e tests only.
Consumes frozen evidence/query/input names; root owns API/CLI implementation.

- [x] RED real browser check new optional correlation file input, diagnostic
  results and selected-window comparison. Fixture no network data sources.
- [x] Add optional createJob fourth file, native input, pass App state.
- [x] Show compact tables and episode summary, limitations/NOT_ESTIMATED, raw
  details expandable. Preserve styles/a11y and existing load-only views.
- [x] Existing comparison view gets explicit baseline/current window-id inputs
  plus two materiality fields and one Compare action. Do not imply matched
  names prove comparability. Display new rows/status/reasons and baseline0.
- [x] Typecheck/lint/build, E2E after root backend ready; update focus-order test
  only for new legitimate controls. No frontend redesign/dependencies.

## Task 4: Entry points, reports, docs and gate (root)

**Files:** cli/CommandLine.kt, web/LocalApi.kt, jobs/AnalysisJobs.kt,
report renderers/tests, entrypoint tests, ui/scripts/verify-policy-schema.mjs,
user/architecture docs, README/CHANGELOG/development-plan-v0.6.md.

- [x] RED CLI --correlation parse/invalid-input and HTTP structured rejection;
  implement shared validator, fourth multipart part, total bound18MiB+64KiB
  (16MiB resources+1MiB policy+1MiB diagnostics), not
  unbounded duplicate/unknown parts. Policy/resource behavior unchanged.
- [x] API window query validation and forwarding to compareAnalyses; jobs fixed
  diagnostic error whitelist, never arbitrary exception text.
- [x] HTML/AsciiDoc safely display new evidence using existing escaping/patterns;
  real CLI/API parity, reload and report assertions before production changes.
- [x] Update JSON Schema/example validation, user guide and changelog. Future
  methods documented, not implemented; no p-values or capacity claim.
- [x] Fresh sequential ktlintFormat, check/installDist; UI typecheck/lint/contracts/
  E2E, Markdown/Python gates/diff/secrets. One final independent review.
- [x] Record results/limitations, local scoped commits only, keep worktree.

## Результат проверки

2026-09-05: реализован локальный срез, без закрытия Stage 1 или всего MVP.

- `gradlew -PnpmOffline=true --offline --no-daemon ktlintFormat -x npmCi`: PASS.
- Затем отдельно `check installDist` с теми же флагами: PASS;
  192 JVM tests, 190 passed, 2 существующих Windows symlink skips, 0 failures/errors.
- `npm run typecheck`, `npm run lint`, `npm run test:contracts`: PASS.
- `npm run e2e`: 31/31 PASS, включая настоящий backend, replay и два окна.
- Markdown: 43 документа PASS; Python verifier и 4 unit tests PASS.
- `git diff --check` и поиск распространённых credential patterns: PASS.
  Полный gitleaks и remote CI здесь не запускались; внешний link crawl и
  performance probe также не выполнялись.
- Независимые math/integration reviews: существенные замечания исправлены;
  для null windows, episode cap, selected-window budget, duplicate decimals,
  null lag profile, signed baseline, Content-Length и C1 schema есть проверки.
  Первоначальный core RED был compile-level; исправления имеют runtime RED/GREEN.

Диагностика описательная: `NOT_ESTIMATED`, без p-values, causal claims и
capacity verdict. Новых production dependencies нет. Рабочая ветка сохраняется
локально, пользовательские изменения исходного worktree не затронуты.

Minor review debt (не runtime blockers): краткий CLI synopsis в user guide
не дублирует optional correlation flag из полного примера; nullable optional
поля resource comparison в TypeScript можно уточнить при следующем изменении
контракта (текущий renderer корректно обрабатывает null).

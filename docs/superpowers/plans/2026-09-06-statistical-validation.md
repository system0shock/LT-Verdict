# Statistical validation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Выполнять шаги последовательно внутри потока; capacity — независимый поток.

**Goal:** Исполняемая независимая проверка CORRECTNESS, USEFULNESS и APPLICABILITY.

**Architecture:** Python stdlib вычисляет эталоны без production imports. Kotlin test runner исполняет существующий анализ и сохраняет actual отдельно. Event simulator производит requests/telemetry, не статистические коэффициенты.

**Tech Stack:** Python 3.14.3 stdlib, существующие Kotlin/JUnit/JSON.

**Spec:** `docs/statistical-validation-methodology-v1.md` (APPROVED WITH APPLICABILITY AMENDMENT).

## Global Constraints

- Production dependencies и публичные production contracts не меняются.
- Acceptance seeds и пороги из методики неизменны; debug seeds 0..99.
- Не импортировать production и не использовать его outputs как expected.
- CORRECTNESS/APPLICABILITY раньше массовых 28000 reports; незавершённое не PASS.
- Capacity не меняет эталоны; freeze baseline source до shared integration.
- Только один Gradle process. Изменения источников сохраняются.
- Независимые потоки имеют раздельное владение файлами; root отвечает за oracle.

## Task 1: Независимые численные определения

**Files:** create `tools/stats_validation.py`, `tools/test_stats_validation.py`.

**Interfaces:** `quantile(values, probability) -> Fraction | None`,
`resource_statistics(values, step_ms) -> dict`, `ranks(values) -> list[Fraction]`,
`rank_correlation(x, y, controls=(), max_lag=0) -> dict`.
Результат correlation содержит status и profile `{lag_cells, coefficient}`;
положительный lag сравнивает resource[:-lag] с load[lag:]. Controls rank и
residualize один раз на всём участке. Singular → SINGULAR_CONTROLS;
zero residual → NO_RESIDUAL_VARIATION, без фиктивного коэффициента.

- [ ] RED: hand literals для type7, variance, timestamp gaps, ties, partial и lag.

```python
assert quantile([0, 1, 2, 3], Fraction(1, 20)) == Fraction(3, 20)
assert ranks([4, 1, 4]) == [Fraction(5, 2), Fraction(1), Fraction(5, 2)]
assert resource_statistics([1, None, 3, None], 10000)["slope_per_second"] == Fraction(1, 10)
```

- [ ] Run `python -m unittest discover -s tools -p test_stats_validation.py`.
- [ ] GREEN: Fraction definitions from methodology §3; Decimal sqrt precision50;
  pairwise variance, rational normal-equation elimination. No Kotlin algorithm copy.
- [ ] Run same command; review oracle independently before corpus freeze.

## Task 2: Frozen corpus and fail-closed report

**Files:** extend the same two Python files; no simulator/Kotlin ownership.

**Interfaces:** `prepare --method v1 --output PATH` writes only a new directory;
manifest lists case ID, family/configuration, input paths/hashes, expected paths/hashes.
Actual JSONL is separate and never read by prepare. `report --input PATH` checks
exact ID set, duplicates, SHA-256, finite values, method-specific exact statuses
and numerical tolerance. Missing/extra/duplicate/error records exit nonzero.

First independently testable portion: `freeze_cases(output: Path, cases: list)
-> str` creates a new directory with canonical `inputs/ID.json`,
`expected/ID.json`, manifest and returns manifest SHA-256. Case IDs use
`[A-Za-z0-9_-]+`; duplicate IDs fail before writes. Each expected document has
`exact` and `numeric` dictionaries keyed by JSON pointer; numeric values are
decimal strings. `check_cases(directory, manifest_sha256, actual_path) -> dict`
requires the externally retained manifest digest, verifies artifact hashes
and returns MATCH, FAIL or INCOMPLETE with per-case reasons. MATCH means only
this manifest's assertions matched; it is not any methodology acceptance gate.
Actual JSONL records are `{id, output}` or `{id, error}`. Source archive and
full methodology coverage are additional requirements before acceptance; these
low-level helpers alone must never label the study PASS or run acceptance seeds.

- [ ] Write filesystem tests using TemporaryDirectory: overwrite refused, input
  corruption detected, duplicate and missing actual records cannot PASS.
- [ ] RED then implement exclusive creation, deterministic JSON and SHA-256.
- [ ] Expand named cases S/A/C/W/V before any production acceptance run;
  preserve every required variant from method §4 in manifest.
- [ ] Freeze generator/oracle/source archive and dirty diff with allowlisted
  sources/build/schema files, no local connection files or credentials.
- [ ] Verify preparation twice to different directories gives identical semantic
  input/expected hashes; report without actual must be INCOMPLETE.

## Task 3: Mechanistic simulator and independent checks

**Files:** create `tools/synthetic_service.py`, `tools/test_synthetic_service.py`.

**Interfaces:** `simulate(parameters, seed) -> trace`, `export(trace, directory)`.
Integer microseconds, FIFO pool/CPU/DB, downstream timer; timeout and generator
wait distinct from application latency. Method §7 parameters are binding.

Concrete simulator interface: `scenario_parameters(scenario, intervention=False,
noisy=False) -> dict`, `simulate(parameters, seed) -> dict`,
`validate_trace(trace) -> list[str]`, `export(trace, directory) -> dict[str,str]`.
Use scenario IDs NT01..NT10 only in evaluator parameters/trace, never exported
request names/metric IDs. Trace contains `parameters`, `requests`,
`busy_intervals`, `state_changes`, `counts`, `duration_us`, `drain_end_us`.
Each request has `id`, `planned_us`, nullable `start_us`, nullable `end_us`,
`status` (completed/timed_out/rejected/generator_wait/in_flight),
`cpu_demand_us`, `db_demand_us`, per-stage entry/start/end timestamps.
Busy intervals contain resource/worker/request ID, from/to in integer us.
State changes record capacities, quotas, heap, queues and generator occupancy.
`counts.arrivals` means issued app requests; `planned`/`generator_wait` are
separate, so waiting inside generator never inflates application latency.
Exporter writes neutral `requests.jtl` and `resources.json`; start ms=floor(us/1000),
elapsed ms=ceil((end-start)/1000), successes only for completed requests.
Unissued/censored requests remain in trace/accounting, not forged successes.
Return absolute paths by keys `load`, `resources`, `trace` (trace is evaluator-only).
Snapshot uses declared interval_mean/rate semantics, explicit role/units and
fixed workload-derived windows; no guessed statistical pairs/thresholds in exporter.
Oracle/rubric, diagnostic/policy construction and production acceptance execution
remain root-owned. Worker may use only debug seeds0..99 until freeze approval.

- [ ] RED: single-server finish times `[10,20,30]`ms for arrivals `[0,1,2]`ms,
  10ms demands; more workers cannot increase isolated wait on identical input.
- [ ] Implement event mechanics and conservation, busy-capacity, ordering checks.
- [ ] Add all NT01..NT10 paired interventions and prescribed sampling variants.
- [ ] Check simulator first; INVALID_FIXTURE cannot reach product scoring.
- [ ] Export neutral IDs and independent expected trace facts/rubric; freeze
  830 runs with every gap/abstention declared before product execution.

## Task 4: Existing-code execution and persistence parity

**Files:** create `src/test/kotlin/io/ltverdict/core/StatisticalValidationTest.kt`
and `StatisticalValidationIntegrationTest.kt`; no production edits.

**Interfaces:** consume manifest inputs; write actual JSONL with case IDs,
status, numeric evidence, findings and persisted/reloaded parity. Never alter expected.

Runner contract (до первого запуска): `LTV_STATS_CORPUS` и `LTV_STATS_ACTUAL`;
`manifest.json` с root `cases`, input SHA-256 обязателен, expected runner не читает.
Input `operation=resource` содержит wire `resources`; `analysis` содержит `run`
с inline `load_jtl`, optional wire `resources/diagnostics/policy`; `comparison`
содержит `baseline/current` в том же формате и два window IDs. Bindings уже
зафиксированы генератором, runner не исправляет hashes. Analysis output хранит
raw `result/identity/evidence/findings`, `persisted_equal`, индексы
`resource_summaries[window][series]`, `correlation_pairs[window][pair]`,
`anomaly_checks[rule]`. Comparison содержит оба analysis outputs и raw comparison.
Пара проходит настоящий manual baseline без фиктивного третьего кандидата.
Actual создаётся эксклюзивно; error сохраняется по ID, не исключается из счётчика.
Нет env — явно skipped self-test, а не statistical PASS; неверный env — ошибка.

Первый отдельный batch — named deterministic literals из `correctness_cases()`.
Его `preparation.json` перечисляет оставшиеся варианты полной §4; MATCH этого
batch не закрывает CORRECTNESS. Source archive и внешне сохранённый manifest
digest обязательны даже для частичного batch. Никаких acceptance seeds он
не использует. Полный gate остаётся INCOMPLETE до всей матрицы.

Дополнение CORRECTNESS: `prepare --batch supplemental` создаёт отдельный
manifest без пересечения IDs с первым batch. До outputs фиксируются abs/z
thresholds ±1e-6, missing anomaly cells (resource/p95/error), dropped/zero
controls, longest segment/windows/support/clocks, effect/sign/sensitivity,
series permutation, matched windows/binding mismatch и combined SLA.
Нулевая частота запросов не изображает missing throughput. Невыполненные
варианты остаются в preparation metadata; старые inputs/expected/actual
не перезаписываются. Source archive supplement включает UI build inputs и
Gradle wrapper для воспроизведения полной команды; production calculations
не исправляются по результатам нового batch.

- [ ] RED: runner executes a hand fixture and emits expected field names;
  absent prepared corpus is explicitly not an acceptance PASS.
- [ ] Call existing resource/diagnostic/comparison APIs for correctness cases.
- [ ] Route all applicability and deterministic load cases through actual JTL
  ingest, AnalysisService, save/reload and pair comparison. No math mocks.
- [ ] Run focused `*StatisticalValidation*` tests; retain raw outputs and invoke report.
- [ ] Record mismatches/gaps without production fixes or oracle retuning.

## Task 5: Calibration and documented result

**Files:** extend Python generator/report; update
`docs/statistical-validation-results-v1.md` and methodology command/status text.

- [ ] Generate exact 28000 report configurations from method §5 and count before run.
- [ ] Execute only after correctness/applicability gate permits; otherwise NOT_RUN.
- [ ] Score per configuration, Wilson intervals, extras, IoU, signed lag errors;
  errors/timeouts remain in planned denominator and imply INCOMPLETE.
- [ ] Save versioned raw results and reproducibility bundle; update report with
  commands, counts, failures and limitations. No overall PASS from a subset.
- [ ] Repeat frozen corpus after capacity integration as a separately named
  regression run; do not overwrite first acceptance outputs.

## Execution state

2026-09-06: Tasks 1–5 исполнены, исследование завершено с отрицательным gate.
Исторические checklists выше описывают порядок реализации; актуальное evidence:

- CORRECTNESS 109/109 MATCH и whole claims audit PASS; final-source replay107+2.
- APPLICABILITY 421/421 MATCH, 830 system runs и trace hashes, claims 0 — PASS.
- USEFULNESS 28000/28000, errors/missing/unevaluable 0, 20 configs PASS и 8 FAIL.
- До MC выполнена обязательная 84-case core/persisted parity PASS; после полного
  запуска повторная parity PASS. Source/input/expected thresholds не подгонялись.
- Capacity integration проверена отдельным 48-case regression без overwrite;
  системные и MC корпуса создавались уже после этой интеграции.
- Source/freeze/raw outputs/JUnit, подробные counts/metrics, hashes и ограничения
  сохранены в [итоговом отчёте](../../statistical-validation-results-v1.md).

Общий statistical acceptance FAIL из-за шума, не из-за незавершённого расчёта.
Продуктовый milestone/CI не закрыт. Исправление шума не входит в этот план;
изменение по раскрытым seeds потребует нового preregistered validation version.
Production dependencies и публичные API не расширялись в полной приёмке.

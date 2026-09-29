# Capacity — explicit stages поверх существующего анализа

Статус: ACCEPTED, согласовано пользователем2026-09-06 («Капасити ок»).
Основание: [v0.6 delta §§12.2–12.5](2026-08-26-v06-local-mvp-delta-design.md),
[план MVP](../../development-plan-v0.6.md).
Параллельный независимый поток — [валидация статистики](../../statistical-validation-methodology-v1.md).

## Предлагаемый результат и альтернативы

Явные ступени, target/achieved load, per-stage business/resource SLA,
generator-health checks, conservative bounds и capacity policy, таблица в UI,
CLI/API, immutable result и offline replay. Без нового engine/dependencies.

Rps-only быстрее, но откладывает разрешённые v0.6 оси concurrency/users.
Автоматическая сегментация/knee требует другого статистического среза.
Предлагается explicit stages со всеми тремя осями, без inference/knee detector.
Для concurrency/users actual telemetry обязателен: из JTL RPS/latency эти
величины не выводятся. UI входит в эту поставку.

## Контракт и вычисления

Новый optional `capacity-plan.v1`, CLI `--capacity`, API `capacity_plan`,
с load input SHA-256 и resource semantic SHA-256. Без него standard flow и
identity/verdict не меняются. В плане:

- load_axis rps|concurrency|users;
- <=64 stages: unique id,target>0,stage from/to,evaluation_window_id из snapshot;
  evaluation входит в stage, half-open boundaries, stages не пересекаются;
- achieved_load.statistic=p05_10s,target_tolerance_ratio в[0,1), optional positive
  required_capacity в единицах выбранной оси;
- concurrency/users требуют achieved_series_id, unit=count,interval_mean,
  nonnegative values; RPS вычисляется по фактическим request starts;
- generator_guard_rule_ids ссылаются на existing diagnostic resource rules,
  только role=generator. Они не являются product SLA.

Resource snapshot и policy.v1 НЕ расширяются новыми enum/полями. Пороги
CPU/free-threads и достаточный набор guards задаёт пользователь/адаптер;
ядро не угадывает их по имени метрики. Сохраняем declared health basis, не
заявляем, что один CPU threshold доказал здоровье всего генератора.
Все policy.v1 rules и resource effect=sla применяются к evaluation windows.

Capacity использует10s UTC cells. Для этого режима snapshot step должен
делить10s (1/2/5/10s); несовместимая сетка явно отклоняется. Concurrency/users
приводятся к10s time-weighted mean только по полным исходным ячейкам.
Предлагаемый minimum evaluation —30 полных10s bins (300s): engineering floor,
не доказательство стационарности. Переиспользуем existing windows/collectors.

Achieved=p05 type7 этих bins, observed min/max,coverage,bin count.
Target достигнут при achieved>=target*(1-tolerance).
Verified bound load=min(target,achieved), никогда bare target.
Missing guard/bins, invalid load и target miss делают ступень непригодной
для product bound; падение RPS само по себе не доказывает generator saturation.

## Bounds и policy

- Valid PASS-prefix + FAIL-suffix → BOUNDED,
  `[last_pass_verified,first_fail_verified)`.
- Все проверяемые ступени PASS → LOWER_BOUND; первая FAIL → UPPER_BOUND.
- Немонотонные targets/outcomes, missing applicable SLA, invalid/gapped stage
  или противоречивый порядок verified loads → INDETERMINATE с reasons.
- При generator failure выше последней valid PASS сохраняется её observed fact,
  но не выдумывается верхняя граница продукта. Консервативно capacity verdict
  при INDETERMINATE остаётся NO_VERDICT; per-stage facts не теряются.
- Применимый SLA — business policy или resource effect=sla, не diagnostic guards.
  Без required_capacity → NO_POLICY; без SLA → NO_POLICY/CAPACITY_SLA_MISSING.
  Иначе lower>=required → PASS,upper<=required → FAIL, пересечение → NO_VERDICT.
- Верхняя exploratory FAIL-ступень не делает load input INVALID.

`capacity.json`/`capacity_summary`: stages/checks/evidence refs, achieved
statistic/units/range, verified loads,bounds,policy verdict,reasons.
Knee=null с KNEE_DETECTOR_NOT_IMPLEMENTED; нет HIGH confidence или extrapolation.
Correlation/saturation — explanation, не замена SLA/доказательство причины.
Run-level OpenSearch/PG context сохраняется, но не притворяется stage-specific.

## Файлы, параллельность и приёмка

Новые CapacityPlan/CapacityAnalysis и focused tests — отдельное владение.
Точечная integration: AnalysisService/AnalysisResult,CLI/API,current Vue forms
и table, schema/example/ADR для нового входа и evidence.
ResourceStatistics/DiagnosticAnalysis/BaselineComparison не рефакторятся.
Без registry/service/runtime и без изменения statistical oracle/corpus.

Approval → точный implementation plan/ADR/limits → pure capacity параллельно
statistical harness → serial integration общих файлов → оба набора tests.
Один Gradle process; до integration сохраняется statistical baseline source
snapshot. После integration тот же corpus повторяется с отдельным manifest.

Ручные fixtures: all PASS,first FAIL,PASS→FAIL,PASS→FAIL→PASS,non-monotonic
targets,no SLA,guard missing/failing,target miss,gaps,threshold equality.
Все3оси,wrong-unit/missing telemetry,bad hashes/windows,standard-mode invariance.
Target300/achieved296 PASS,target350/achieved344 FAIL → `[296,344)`,
не `[300,350)`; required300 → NO_VERDICT,296 → PASS,344 → FAIL.
CLI/UI analyze→save→reload должны показывать те же bounds/evidence.

Согласованы explicit stages,3оси с telemetry,existing diagnostic guards,
minimum300s и отсутствие inference/knee. Реализация и её gates ещё не завершены.

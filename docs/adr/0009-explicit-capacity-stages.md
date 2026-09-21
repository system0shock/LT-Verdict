# ADR 0009: Явные ступени capacity поверх существующего анализа

Статус: Accepted, 2026-09-06.

## Решение

Добавить opt-in вход `capacity-plan.v1` к существующему локальному анализу.
План задаёт одну ось (`rps`, `concurrency` или `users`) и не более 64 явных
непересекающихся ступеней. Core не выводит ступени, saturation или knee из
телеметрии. Без плана сохраняются байты результата, identity и поведение
`standard` режима.

`capacity-plan.v1` связан с конкретными load input и semantic resource snapshot
их SHA-256. Каждая ступень содержит `[from_epoch_ms,to_epoch_ms)`, target и ID
существующего resource evaluation window; это окно целиком принадлежит ступени.
Вычисление использует только 10-секундные UTC ячейки. Snapshot step обязан быть
одним из 1/2/5/10 секунд, а evaluation window — границами UTC 10 секунд.
Минимум — 30 полных ячеек (300 секунд). Для `rps` achieved load строится из
фактических request starts; для `concurrency`/`users` обязателен declared
series с `unit=count`, `aggregation=interval_mean` и неотрицательными
значениями (роль series не ограничивается). Такие серии агрегируются time-weighted mean только из полных
исходных ячеек.

Achieved statistic фиксирован как type-7 `p05_10s`; сохраняются минимум,
максимум, coverage и число ячеек. Target достигнут, когда `achieved >=
target * (1 - target_tolerance_ratio)`, а product-bound использует только
`min(target, achieved)`. Отсутствующая telemetry/guard/ячейка, неверная
привязка или недостижение target делают ступень непригодной для product bound.
Само падение RPS не доказывает saturation генератора.

Generator health задаётся только `generator_guard_rule_ids`: это существующие
resource rules с `effect=diagnostic`, series `role=generator`. Их метрики и
пороги не интерпретируются по именам. Business `policy.v1` rules и resource
rules с `effect=sla` вычисляются на evaluation windows; diagnostic guards не
становятся SLA. Resource snapshot и `policy.v1` не расширяются.

Результат сохраняет `capacity.json` рядом с исходным plan в immutable bundle и
добавляет `capacity_summary`/stage evidence в `analysis-result.v1`. Bounds
бывают `BOUNDED`, `LOWER_BOUND`, `UPPER_BOUND` или `INDETERMINATE`. Допустима
только последовательность валидных product outcomes PASS-prefix, затем
FAIL-suffix; gaps, invalid stage, non-monotonic target/outcome, missing SLA и
противоречивый порядок verified loads дают `INDETERMINATE` с reason codes.
Generator failure после valid PASS сохраняет факт ступени, но не создаёт
верхнюю границу продукта. При `INDETERMINATE` итоговый verdict — `NO_VERDICT`.

Если `required_capacity` отсутствует, verdict — `NO_POLICY`. При отсутствии
применимого business/resource SLA — `NO_POLICY` и
`CAPACITY_SLA_MISSING`. Иначе lower >= required даёт `PASS`, upper <= required
даёт `FAIL`, а пересечение — `NO_VERDICT`. Верхняя exploratory FAIL-ступень не
делает input `INVALID`.

`capacity_knee` всегда `null` с reason
`KNEE_DETECTOR_NOT_IMPLEMENTED`; HIGH confidence, extrapolation и causal claims
не создаются. Correlation/saturation остаются explanatory evidence и не
заменяют SLA или product bound. Run-level OpenSearch/PostgreSQL context
сохраняется как run-level, не маркируется stage-specific.

## Следствия

Используются существующие `AnalysisService`, `ResourceSnapshotV1`,
`UtcLoadMetricsAccumulator`, `WindowMetricsAccumulator`, `policy.v1`, storage,
CLI/API и Vue формы. Нужны два малых pure-core файла (`CapacityPlan.kt`,
`CapacityAnalysis.kt`), точечные additions к текущим boundary-файлам и новые
contract/example/tests. Новых production dependencies, service/registry,
storage layer, statistical oracle/corpus или рефакторинга
`ResourceStatistics`/`DiagnosticAnalysis`/`BaselineComparison` нет.

Capacity plan — недоверенный multipart/CLI файл: до вычислений применяются
bounded read, strict UTF-8, duplicate-key/depth/numeric guards, closed fields,
SHA/window/series/rule bindings и exact integer/decimal validation. Сохраняются
raw plan и canonical capacity output; identity включает semantic plan hash.
UI передаёт файл как multipart part и отображает только сохранённый result,
поэтому reload/offline replay не выполняет внешних запросов.

## Отклонённые альтернативы

- RPS-only: не покрывает согласованные `concurrency` и `users`.
- Inferred stages/plateaus или knee detector: это новый статистический метод и
  не имеет утверждённой калибровки.
- Новые поля/enum в snapshot либо `policy.v1`: ломают устойчивые контракты и не
  нужны для явного plan.
- Отдельный capacity runtime/service: существующий двухпроходный анализ и
  immutable bundle уже дают необходимую точку интеграции.

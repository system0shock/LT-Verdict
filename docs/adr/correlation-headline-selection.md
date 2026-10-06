# ADR: Correlation headline selection

Статус: Accepted, 2026-09-06.

## Контекст

ADR 0006 оставил correlation uncertainty и multiple-testing correction
описательными. Disclosed development-repeat на 15000 reports проверил frozen
independent MBB selector: `B=999`, blocks `10/20`, lag-max, max-p и один Holm.
Пользователь принял остаточный шум `7.7-13.2%` в шести configurations как
известное ограничение и отложил дальнейшую оптимизацию. Это не изменило старый
USEFULNESS FAIL и не стало независимой statistical acceptance.

## Решение

Production `correlation_candidate` проходит frozen selector поверх прежней
materiality/sign проверки. Raw `correlation_pair` evidence и его status не
меняются; результат selector публикуется отдельным evidence. Все declared
hypotheses входят в один Holm, недоступные — как `p=1` с public `null`.

Calibration применяется к ordinary/effective ordinary association. Если хотя
бы один control реально использован, genuine partial остаётся descriptive:
`GENUINE_PARTIAL_UNCALIBRATED`, без p и headline. Unsupported shape, gaps,
outcome mismatch, недостаток anchors, bootstrap degeneracy или computation cap
также дают явный `UNAVAILABLE`, а не ослабление frozen settings.

Production использует только JDK: seed — первые восемь bytes SHA-256 от
`method/seedMaterial/block/side`, stream — `java.util.Random.nextInt(bound)`.
Это сохраняет deterministic independent schedules без Python runtime, но не
воспроизводит NumPy PCG64 bit-for-bit. Python noise range не является измеренной
гарантией JVM port; это ограничение публикуется в contract и документации.

Diagnostic identity module повышается до version `2`; plan schema и top-level
result schema сохраняют version `v1`. SLA verdict, anomaly и comparison logic
не зависят от selector.

## Последствия

Обычные material candidates получают bounded приблизительную проверку lag
selection и multiplicity. Worst supported family ограничена 16 hypotheses,
240 cells и 150 млн correlation cell-products. Новых dependencies нет.
Поправка ADR [0022](0022-correlation-stages-increments-calibration.md) (срез K1):
план делится на семьи по паре «стадия, исход» (каждая на уровне `alpha / F`),
предел поднят до 1 920 cells, потолок до 550 105 344 cell-products на семью.
Поправка ADR 0022 (срез K2): метод заменён на `mbb-lag-max-holm.v2`, который
отбирает по первым разностям рядов (внутри самой длинной непрерывной серии
стадии); версия метода входит в seed, поэтому p-значения v1 и v2 не сопоставимы.
Описанный выше метод v1 по уровням сохранён в истории и в сохранённых результатах.

Genuine partial и неподдержанные production shapes могут потерять прежний
headline, но сохраняют все raw coefficients/status и точную reason. Расширять
scope или переносить empirical guarantee можно только после отдельной заранее
замороженной validation, без tuning текущих thresholds.

Design и contract:
[design](../superpowers/specs/2026-09-06-correlation-headline-selection-design.md),
[contract](../contracts/diagnostics/v1/correlation-headline-selection.md).

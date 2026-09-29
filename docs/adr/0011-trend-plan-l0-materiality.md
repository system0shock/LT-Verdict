# ADR 0011: L0-тренд ресурсных метрик по объявленному плану

Статус: Accepted, 2026-09-27.

## Решение

Добавить opt-in вход `trend-plan.v1` к существующему локальному анализу. План
привязан к semantic hash `resource-snapshot.v1` и объявляет не более 32 проверок.
Каждая проверка задаёт `series_id`, `window_id`, `direction`
(`increase`/`decrease`/`either`), `min_cells` (30..100000) и
`magnitude_gate` из двух положительных величин: минимального абсолютного
`slope_per_second` в единицах series и минимального абсолютного
`split_half_shift` в процентах от медианы окна.

Уровень L0 не выполняет статистического вывода: p-value, доверительных
интервалов, bootstrap и поправок на множественность нет. Использованы те же
описательные статистики, которые уже публикует `resource_summary`, — OLS
`slope_per_second` по grid-секундам и `split_half_shift` как разница медиан
половин окна. Обе величины обязаны пройти гейт и согласоваться по знаку; при
`either` требуемый знак берётся из slope.

Пропуски не заполняются и не сжимают время: наблюдаются только non-null ячейки,
`expected_cells`, `observed_cells`, `missing_cells` и `longest_gap_cells`
публикуются, а наличие пропусков даёт reason `RESOURCE_GAPS` без блокировки
проверки.

Статусы проверки: `TREND_OBSERVED`, `NO_MATERIAL_TREND`, `INSUFFICIENT_CELLS`,
`UNAVAILABLE`. Каждый отказ несёт точный reason: `NO_OBSERVATIONS`,
`TREND_MIN_CELLS_NOT_MET`, `TREND_HALF_CELLS_NOT_MET` (см. дополнение
2026-09-29), `INSUFFICIENT_OBSERVATIONS`, `TREND_MEDIAN_ZERO`,
`TREND_DIRECTION_MISMATCH`, `TREND_DIRECTION_DISAGREEMENT`,
`TREND_SLOPE_BELOW_MINIMUM`, `TREND_SHIFT_BELOW_MINIMUM`,
`TREND_SERIES_NOT_FOUND`, `TREND_WINDOW_NOT_FOUND`. Каждый `TREND_OBSERVED`
дополнительно несёт `STATIONARITY_NOT_EVALUATED`: объявленное окно не
доказывает стационарность, детектора смены режима в L0 нет.

Найденный тренд публикуется как finding `resource_trend` с `effect=diagnostic` и
`uncertainty=NOT_ESTIMATED`. Вердикт, `analysis_coverage` и существующие
контракты не меняются. Результат добавляет evidence `trend_check` на проверку и
`trend_summary` на анализ, артефакты `trend-plan.json` (сырые загруженные байты)
и `trend.json` (`trend.v1`), input `trend_plan` в `run.v1`, а в identity —
условные `trend_plan_sha256`, `trend_plan_version`, `input_versions.trend`,
модуль `resource-trend-evaluation` версии 1 и блок `limits` с
`trend_plan_bytes_max`, `trend_json_depth_max`, `trend_checks_max`,
`trend_min_cells_floor` и `trend_method`.

## Следствия

- Без плана байты результата, identity и поведение полностью сохраняются: все
  новые identity-поля условные, поэтому существующие golden-фикстуры не меняются.
- Новый top-level ключ в `analysis-result.v1` не появляется. Схема результата
  объявляет `additionalProperties: false`, а `ai/AdvisoryAi.kt` проверяет точное
  совпадение набора ключей, поэтому trend виден ИИ только через `evidence` и
  `findings` и не требует изменения allowlist.
- `analysis_coverage` намеренно не отражает отказы отдельных проверок: полнота
  данных нагрузки и ресурсов описывается существующими причинами, а отказ
  необязательной проверки не делает сбор неполным.
- Отчётные рендереры (HTML, AsciiDoc, Confluence) фильтруют evidence по своему
  списку типов и новый тип игнорируют — так же, как сейчас игнорируют capacity.
- Рост метрики — наблюдение, а не диагноз. `resource_trend` не утверждает утечку,
  причину или исчерпание ресурса; слова leak и root cause в выводе недопустимы.
- Числовые определения не дублируются: `Statistics`, `statistics`, `IndexedValue`,
  `cellIndex`, `cellStart` и `resourceId` переведены в `ResourceStatistics.kt` с
  `private` на `internal`. Четвёртая копия slope, split-half или квантилей
  создала бы риск расхождения определений.

## Отклонённые альтернативы

- Top-level `trend_summary` в результате: потребовал бы правки
  `analysis-result.schema.json` и набора ключей в `AdvisoryAi`, не добавляя
  фактов сверх evidence.
- Mann–Kendall и Theil–Sen сразу: требуют блочного перестановочного null и
  явного бюджета семьи; при `B = 999` разрешение p равно 1/1000, поэтому Holm не
  отвергает ничего при семье больше 50 гипотез. Это уровень L1 из
  `docs/analytics-trend-detection.md`.
- Расширение `resource-snapshot.v1` новым видом правила: изменило бы замороженный
  контракт и его semantic hash ради необязательной возможности.
- CUSUM/EWMA: требуют in-control распределения и параметра drift, которых нет до
  накопления истории сопоставимых прогонов.
- Автоматический поиск «подозрительных» метрик без объявления: неконтролируемая
  мультипликативность, измеренная в v1 как 91,7 % ложных главных находок.

## Дополнение 2026-09-29

Основание: ревью ветки `feat/trend-plan-l0`, пункты M1–M3. Владелец согласовал
вариант A для M1.

### M1. Минимум наблюдений в каждой половине окна

`min_cells` проверялся по всему окну, а `split_half_shift` делит окно по индексу
ячейки (`midpoint = expectedCells / 2`). При 30 наблюдённых ячейках в первой
половине могла оказаться одна точка: сдвиг сравнивал медиану 29 точек с одной,
и гейт был пройден при статистически пустой оценке.

Решение: проверка, у которой в любой из половин наблюдённых ячеек меньше
`floor(min_cells / 2)`, получает статус `INSUFFICIENT_CELLS` и reason
`TREND_HALF_CELLS_NOT_MET`. Порядок: после `NO_OBSERVATIONS` и
`TREND_MIN_CELLS_NOT_MET`, до расчёта гейта. Reason `RESOURCE_GAPS` сохраняется,
если пропуски есть. При `min_cells = 30` нужно не меньше 15 наблюдённых в каждой
половине, при `min_cells = 60` не меньше 30.

Единственное определение середины остаётся в `statistics()`: он же считает два
внутренних счётчика половин (`firstHalfCells`, `secondHalfCells`), а
`TrendAnalysis` их читает. Счётчики в JSON не выводятся, в `evidence.trend_check`
и `trend.v1` новых полей нет. `resource_summary` и его `split_half_shift` не
меняются.

Идентичность: схема `trend-plan.v1`, блок `limits` и `trend_method` не меняются;
`trend_method` остаётся `slope-materiality.v1`, потому что L0 не выпущен. Байты
результата меняются только для проверок, которые раньше проходили минимум по
окну при разреженной половине. Если к моменту слияния L0 уже используется вне
разработки, идентификатор поднимается до `slope-materiality.v2`.

Отклонённые варианты: фиксированный пол половины (новая константа попадает в
блок `limits` identity и меняет байты всех прогонов с планом); поле плана
`min_cells_per_half` (меняет публичный контракт `trend-plan.v1`, нужен отдельный
ADR).

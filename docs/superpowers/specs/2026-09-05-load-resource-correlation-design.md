# Load/resource correlation — диагностический срез

**Статус:** Draft for user review. Состав среза согласован в чате; этот документ
уточняет контракт и численные правила перед implementation plan.

**База:** `8fbab03`, ветка `feat/load-resource-correlation`.

Основание: PRC 0.6 §11.5, delta-spec §16,
[приоритеты](../../development-plan-v0.6.md#2-корреляция-load--infrastructure),
[resource design](2026-09-05-resource-statistics-design.md), ADR 0005.

## 1. Результат и границы

По сохранённым JMeter/Gatling и resource snapshot получаем объяснимые
ассоциации разрешённых пар: окно, контрольные переменные, исходная и условная
связь, лаг, покрытие, наблюдаемые изменения и ограничения интерпретации.
Корреляция не меняет validity, SLA verdict или baseline; отсутствие связи не
означает исправность системы. Ошибка optional correlation не уничтожает
успешно рассчитанные бизнес-/ресурсные SLA.

Выбран existing Kotlin/JDK core и существующий snapshot/window contract.
Альтернативы: сырой Spearman дешевле, но не покрывает контроль нагрузки;
отдельный statistical runtime и полная incident synthesis увеличивают срез.
Новые production dependencies, generic pipeline/registry, транспорт VM/Grafana,
capacity, inferred stages и цепочки инцидентов не входят в эту поставку.
Это часть §16, не заявление о полном выполнении enriched MVP.

## 2. Явный correlation plan

Optional `correlation-plan.v1` — отдельный JSON input, не изменение
`resource-snapshot.v1` или `policy.v1`. CLI: `--correlation <path>`;
API: optional multipart `correlation_plan`; UI: optional файл в текущем flow.
План требует resource snapshot и связывается с его semantic SHA-256.

План содержит `schema_version`, `resource_snapshot_sha256`, `pairs` и общие
`resampling` settings. Каждая pair содержит:

- уникальный `id`, `resource_series_id`;
- `load_metric`: `response_time_p95_ms|error_rate|throughput_rps`;
- `window_ids` из resolved resource windows;
- `expected_sign`: `positive|negative|either`;
- `max_lag_ms`: 0..60000, кратно grid step, не более десяти ячеек;
- `min_abs_effect`: число в (0,1], default 0.3;
- `controls`: до четырёх явно выбранных контролей;
- `topology_basis`: bounded plain text с основанием допустимости пары;
- `clock_alignment`: `unknown|declared_aligned`, default `unknown`.

Контроль — либо `achieved_rps` из load samples, либо ссылка на snapshot series
с `meaning`: `target_rps|concurrency|replicas|request_mix|other`.
Один request-mix контроль — доля явно названного класса запросов, не индекс
категории. Подготовка этих рядов остаётся адаптеру. Не выводим target RPS,
replicas или concurrency из latency/достигнутого RPS. Ссылка на target series
задаёт planned load, но не реализует capacity stage manifest.

Для declared meaning фиксируем units: `target_rps` — `requests/s`,
`concurrency|replicas` — `count`, `request_mix` — `ratio`. Значения первых
трёх неотрицательны, request mix лежит в [0,1]; interval means counts могут
быть дробными. `other` сохраняет исходную unit без семантического вывода.

Topology ограничивается явно разрешённым списком pairs и их entity bindings;
автоматического discovery и разбора metric names нет. В этой поставке load
endpoint — overall HTTP samples. Transaction-specific пары и resource/resource
edges остаются следующему расширению; не выдаём overall за связь конкретного
сервиса. Запретить повтор одной pair definition под разными ids.

Нельзя использовать source series или выбранный load outcome как собственный
контроль. Achieved RPS не добавляется автоматически; если он выбран, сохраняем
также sensitivity result без него. Единственный achieved-RPS контроль получает
`ACHIEVED_LOAD_ONLY`, не статус достаточного контроля нагрузки. Наличие target
не доказывает, что все confounders учтены. Отсутствующие виды контролей видны
в evidence. Несуществующий series id — ошибка плана; существующий контроль
без достаточных наблюдений делает пару непроверяемой.

## 3. Общая сетка и load series

Повторно используем resolved UTC windows, не объединяем разные окна для
увеличения выборки. Explicit window — условие анализа, не доказанная
стационарная ступень. Без explicit windows остаётся whole-intersection window
с `STAGE_UNSPECIFIED`.

Во втором существующем проходе input собираем overall cell metrics точно по
UTC boundaries snapshot. Текущие buckets привязаны к началу run, поэтому
нельзя просто переименовать их timestamps в UTC bins. Sample membership — по
start, полная latency без clipping, как в оконных SLA. P95 получается из
объединённых sample histograms, не усреднением готовых p95.

Полностью покрытая ячейка без requests: achieved RPS = 0, latency/error rate =
null. Пустая load ячейка не доказывает пропуск ingest и не заполняется latency=0.
Resource gaps сохраняются, interpolation и forward fill отсутствуют.

Для pair/window строим complete-case mask X, Y и выбранных controls. Коэффициент
нулевого лага использует все полные пары с исходными timestamps. Lag analysis
использует один самый длинный непрерывный complete run; при равной длине —
первый по времени. Selection зависит от наличия данных, не от силы корреляции.
В evidence раздельны expected, paired и lag-used cells и discarded intervals.
Никакого сжатия времени через gaps или поиска lag между разными окнами.

## 4. Условная связь и лаги

Spearman — Pearson correlation средних рангов, ties получают средний rank.
Для partial Spearman ранжируем X, Y и каждый control на одной выборке;
из рангов X и Y удаляем линейную проекцию на intercept и ранги controls,
затем считаем Pearson correlation остаточных векторов. Это partial rank
association, не общий тест условной независимости и не causal estimator.

Constant controls исключаются с явным списком: внутри фиксированной ступени
они не содержат вариации. Зависимые nonconstant controls выявляются при
ортогонализации; сохраняем первый в canonical id order, остальные отмечаем
`REDUNDANT_CONTROL`. Используем центрированные/нормированные столбцы и
повторную ортогонализацию; relative residual norm <= 1e-10 означает зависимость.
Нулевая остаточная вариация X/Y даёт null с `NO_RESIDUAL_VARIATION`, не rho=0.
Минимум для coefficient: 30 полных наблюдений и больше `controls_used + 3`.
Недостаток данных, constant outcome или non-finite result — явные причины null.

Lagged calculation использует отдельно ранжированные/резидуализованные векторы
непрерывного участка. Для L=max_lag_ms/step сравниваем X[t] и Y[t+k],
k=-L..L, t=L..N-L-1: одинаковое число anchor cells на всех лагах.
Положительный k означает, что resource наблюдается раньше load outcome.
Это correlation rank residuals со сдвигом, не повторный Spearman по каждому
сдвинутому subset. Сохраняем весь bounded lag profile; лучший lag выбирается
по max abs(rho), затем min abs(k), затем меньшему signed k.

Pair status: `NOT_EVALUABLE` при отсутствии coefficient; иначе
`BELOW_EFFECT` при abs(best rho) < min_abs_effect, `OPPOSITE_SIGN` при
несовпадении заданного знака, `CANDIDATE` в остальных случаях. При недоступном
lag profile используем zero-lag coefficient с явным `LAG_NOT_EVALUABLE`.
`CANDIDATE` означает наблюдаемую ассоциацию, не подтверждённую значимость.

`expected_sign` не скрывает противоположный результат: несовпадение — negative
evidence. Unknown clock alignment запрещает интерпретацию межисточникового
порядка, но не расчёт наблюдаемого lag profile. `declared_aligned` — заявление
поставщика, не проверка ядром; смещение часов по максимуму rho не подбирается.

## 5. Неопределённость и множественные проверки

Обычные iid p-values для этих временных рядов не используем. Для непрерывного
участка предусмотрен bounded block-permutation reference test по rank residuals.
`resampling` содержит `block_cells` (>=2), `permutations` (99..999, default 999)
и `exchangeable_blocks_assumed` (default false). Block size выбирается явно;
ядро не выдаёт его автоматический подбор за доказательство корректности.

Ресемплинг требует минимум восьми полных блоков. Хвост короче блока исключается
и отражается в evidence; наблюдаемый test statistic пересчитывается на точно
том же участке. Переставляем целые блоки X residuals, оставляя внутренний
порядок и Y residuals неизменными. Для каждой перестановки заново берём max
abs(rho) по всем объявленным lag candidates. Тем самым reference distribution
учитывает выбор лучшего лага, а не тестирует его post hoc как заранее заданный.

Seed выводится из semantic plan/snapshot hash, pair id, window id и версии
алгоритма; фиксированный PRNG и Fisher-Yates задаются в implementation plan.
Считаем `p_approx=(1 + count(T_surrogate >= T_observed))/(B + 1)`.
По всей заявленной семье pair/window применяем Holm; непроверяемым членам
для correction соответствует p=1, но публично их p остаётся null.
Sensitivity без achieved RPS показывается как описательная диагностика,
не выбирается по меньшему p и не создаёт ещё один confirmation test.

**Ограничение метода:** fitted residuals и зависимость между блоками не дают
безусловно точного permutation test. Стационарность и exchangeability блоков
не следуют из explicit window. Поэтому даже скорректированное значение —
приближённое, под предпосылками, не гарантия false-positive rate и не вероятность
причинности. Holm не исправляет невалидные исходные p-values.

Если предпосылка exchangeability не заявлена, p/adjusted p = null,
`RESAMPLING_ASSUMPTIONS_UNCONFIRMED`; coefficients всё равно доступны.
Сохраняем lag-1 residual autocorrelation и autocorrelation на block distance
как diagnostics, не используем тест на отсутствие autocorrelation как
доказательство независимости. Не присваиваем HIGH confidence и не называем
кандидата статистически подтверждённой причиной. Низкое разрешение permutation
test после correction отмечается отдельно, не обходится изменением alpha.
Для отображения используется фиксированное alpha=0.05; сравнение adjusted p
с ним не повышает pair status до «доказано» и не влияет на SLA.

Основания: [SciPy Spearman](https://docs.scipy.org/doc/scipy-1.16.1/reference/generated/scipy.stats.spearmanr.html)
предостерегает от асимптотического p для малых выборок;
[работа о stationary time-series tests](https://pmc.ncbi.nlm.nih.gov/articles/PMC11398661/)
объясняет ограничение iid tests при autocorrelation;
[R p.adjust](https://stat.ethz.ch/R-manual/R-devel/RHOME/library/stats/html/p.adjust.html)
задаёт Holm correction. Эти ссылки не являются валидацией выбранной
условной block-permutation approximation; её ограничения заданы выше.

## 6. Порядок наблюдаемых изменений

Для X/Y на том же непрерывном участке вычисляем medians последовательных
неперекрывающихся блоков `block_cells`. Candidate change — наибольшая абсолютная
разность соседних block medians; ties — первая граница. Показываем величину
и отношение к full-run unscaled MAD; при MAD=0 отношение null с `ZERO_MAD`.
Это локализованный candidate shift, не полноценная автоматическая сегментация.
При менее двух полных блоках или нулевом максимальном сдвиге candidate = null;
шумовой максимум также не объявляется доказанной сменой режима.

Временная неопределённость кандидата — интервал двух соседних блоков.
`resource_before_load|load_before_resource` допустимы только для непересекающихся
интервалов и declared aligned clocks; иначе `overlapping|unknown`.
Порядок и opposite sign добавляют evidence/limitations, но не увеличивают
коэффициент и не дают opaque confidence score. Candidate ordering не меняет
evaluation windows или SLA.

## 7. Ограничения ресурсов и отказ

Plan: <=1 MiB до parsing, depth<=12, <=16 pairs, <=4 controls на pair,
<=128 pair/window evaluations суммарно. Идентификаторы/текст и decimal scanner
повторяют существующие UTF-8/numeric boundaries. Unknown/duplicate fields,
неверные refs, units для declared meaning и несовместимый grid отклоняются
до job; математически целые decimal/exponent tokens допустимы.

Новые UTC load histograms: не более 10 000 ячеек union выбранных windows,
один histogram на ячейку, независимо от числа pairs. Перед выделением проверяем
совместный потенциальный бюджет существующих window histograms и новых cell
histograms <=10 000. Для correlation numeric scratch используется одна pair
за раз; результат содержит <=2688 lag points (128 evaluations × 21 lags).

Планируемая работа resampling ограничена 50 млн anchor-cell comparisons:
sum(n_anchors × lag_count × (permutations+1)) по всем evaluations. Проверяем
бюджет до циклов; нельзя молча сокращать число permutations или удалять pairs.
Cancellation проверяется при parsing, по ячейкам и внутри resampling batches.
Сортировки рангов/median O(n log n); никаких O(n²) поисков change point.

Превышение correlation-specific бюджета даёт module status `LIMIT_EXCEEDED`
и безопасную diagnostic reason, оставляя SLA результат. Invalid config даёт
ошибку input; отсутствие данных — `NOT_EVALUABLE` конкретной пары. Не ловим
OutOfMemoryError и cancellation как обычный успешный diagnostic результат.

## 8. Identity, evidence и пользователи

Optional plan canonical hash, module version и limits входят в enriched
analysis identity. Все result-affecting declarations, включая clock alignment
и resampling assumptions, входят в hash; нельзя брать их из hash-excluded
provenance snapshot. Raw plan сохраняется immutable рядом со snapshot,
с manifest и run-root-relative reference в `run.json`.
Без correlation plan существующие identity/results остаются byte-identical.

Новые evidence types `correlation_summary` и `correlation_pair` используют
существующие object slots, без изменения top-level result schema. Pair evidence
содержит ids/entities/metric/unit, bounds, controls requested/used/dropped,
coverage counts, raw/partial coefficients, lag profile/best lag, sensitivity,
approximate raw/adjusted p либо null, resampling settings/seed/version,
candidate shifts/order, status/reasons. Все выводы ссылаются на эти evidence ids.

UI/HTML/AsciiDoc показывают пары, коэффициенты, лаги, coverage и ограничения;
термин «приближённая оценка под предпосылками» обязателен рядом с p-values.
Нет нового chart framework или score. CLI/UI работают через один core,
reload читает сохранённый результат без пересчёта и network. Correlation-only
failure не меняет существующие SLA exit codes.

## 9. Приёмка и исполнение

Обязательные RED/GREEN cases:

1. Ties, положительная/отрицательная rank association; независимый эталон
   partial correlation, redundant/constant controls и zero residual variance.
2. Общий рост target load без дополнительной связи: raw association высокая,
   controlled association исчезает либо становится неопределимой при полностью
   объяснённой вариации; не появляется causal finding.
3. Известный signed lag, противоположное направление, unknown clock alignment;
   gaps не сдвигают временную ось, окна не соединяются.
4. Achieved RPS падает при деградации: он не становится автоматическим фильтром;
   sensitivity без него сохраняется при явном выборе.
5. Block permutations deterministic; max-over-lags и Holm проверяются на
   ручных literals. Необъявленные предпосылки дают null p, не малое число.
6. Seeded null datasets: iid и autocorrelated процессы, общий target driver,
   смена режима. Проверить ложноположительные диагностические результаты;
   отдельно записать эмпирическую калибровку, не выдавать один seed за гарантию.
7. Exact UTC membership при дробном run start, empty cells, p95 из samples,
   resource/input hash mismatch, limits до allocations, cancellation.
8. CLI/API parity, безопасное отображение, immutable reload, прежние golden
   results без plan; module failure не меняет SLA verdict.

Следующий implementation plan закрепляет wire fields/schema/example и
детерминированные numerical literals до делегирования. Backend calculations и
минимальные UI/reports можно делать параллельно после фиксации evidence contract.
Один финальный concern review; затем только scoped verification исправлений.
Fresh gates: Gradle check/installDist, UI typecheck/lint/contracts/E2E, Markdown,
secret/diff checks. Remote CI и milestone acceptance не подразумеваются.

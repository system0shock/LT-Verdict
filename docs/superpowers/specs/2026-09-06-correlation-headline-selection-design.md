# Correlation headline selection design

**Статус:** Approved, 2026-09-06.

## Цель и границы

Production diagnostics отбирает `correlation_candidate` тем же frozen методом,
который прошёл disclosed development-repeat: independent non-circular moving
block bootstrap, `B=999`, блоки `10` и `20` cells, максимум `|rho|` по всему
declared lag profile, `q=max(p_b10,p_b20)` и один Holm при `alpha=0.05`.
Materiality/sign/status существующей пары остаются обязательными. SLA verdict,
raw `correlation_pair` evidence, anomaly episodes и two-run comparison не
изменяются.

Результат repeat `7.7-13.2%` является известным ограничением раскрытого
эксперимента, а не гарантией для JVM, других данных или genuine partial.
Пороги и алгоритм после эксперимента не настраиваются.

## Поток и интерфейс

`evaluateDiagnostics` сначала строит прежний raw result каждой pair/window.
Затем один вызов `selectCorrelationHeadlines` получает всю declared family,
включая непроверяемые hypotheses. Для каждого hypothesis selector возвращает
отдельное `correlation_headline_selection` evidence. Старый finding создаётся
только когда одновременно выполнены прежний `status=CANDIDATE` и
`selection.status=SELECTED`.

Core interface:

```kotlin
internal fun selectCorrelationHeadlines(
    hypotheses: List<CorrelationHeadlineHypothesis>,
    seedMaterial: String,
    checkCancelled: () -> Unit = {},
): List<CorrelationHeadlineSelection>
```

Один hypothesis содержит pair/window ids, timestamps, исходные resource и
outcome vectors, outcome key, declared max lag, прежний material-candidate flag
и optional reason, запрещающий calibration. Порядок результата совпадает с
порядком входа; Holm tie-break использует canonical pair/window order.

## Frozen calculation

Для каждого блока resource indices выбираются одним общим stream для всей
family, outcome indices — независимым stream. Блоки не circular; случайные
starts лежат в `0..n-block`, блоки конкатенируются и последний обрезается до
`n`. В каждой из `999` реплик ranks с average ties строятся заново по полному
ресемплированному ряду. Statistic каждого hypothesis — максимум `|rho|` на
fixed anchors для всех declared lags. Значение
`p=(1+count(T*>=Tobs))/(B+1)` считается отдельно для `b=10` и `b=20`.

Непроверяемому hypothesis внутри поддержанной family соответствует `p=1` в
единственном Holm, но публичные p-поля остаются `null`. Поэтому family нельзя
уменьшить до material candidates или только доступных pairs.

## Scope и bounded cost

Selector исполняется только для одной family из `1..16` pair/window hypotheses,
одного window, общей непрерывной grid и одного outcome vector. Поддерживаются
`30..240` cells, block lengths `10/20`, declared lag `0..10` cells и не менее
`30` fixed anchors. Ordinary pair без controls и effective ordinary pair, у
которой все controls dropped, допустимы. Любой реально использованный control
означает `GENUINE_PARTIAL_UNCALIBRATED`: raw coefficient сохраняется, p не
вычисляется, headline не создаётся.

Hard limit — `150_000_000` correlation cell-products:

```text
2 * 999 * sum((2 * lag + 1) * (n - 2 * lag))
```

по вычисляемым hypotheses. Максимальная validated shape `m=16,n=240,lag=10`
даёт `147_692_160`. Выход за shape или limit не сокращает `B`, blocks, lags или
family: selector возвращает `UNAVAILABLE` с явной reason. Cancellation
проверяется до allocations и на каждой bootstrap replica.

## RNG и численные ограничения

Production не получает Python/NumPy dependency. Для каждого
`method/seedMaterial/block/side` первые восемь байт SHA-256 интерпретируются как
signed `Long`, после чего используется специфицированный JDK
`java.util.Random.nextInt(bound)`. Resource и outcome streams независимы;
resource schedule общий для всех pairs. Версия публикуется как
`java-random-sha256-seed.v1`.

Это не bit-identical NumPy `default_rng(PCG64)`: конкретные bootstrap draws и
p-values JVM могут отличаться от Python repeat. Rank, lag statistic, p formula,
max-p и Holm совпадают по определению и покрываются literal fixtures. Диапазон
шума Python repeat не переносится на JVM как измеренная гарантия.

## Public evidence и unavailable conditions

Новый evidence содержит `method`, `rng`, `status`, `family_hypotheses`, fixed
settings, `p_value_b10`, `p_value_b20`, `max_p_value`,
`holm_adjusted_p_value`, `selected` и `reasons`. Поля p — canonical decimal
string или `null`. Статусы: `SELECTED`, `NOT_SELECTED`, `UNAVAILABLE`.

Unavailable reasons различают genuine partial, неоценимую пару, неподдержанную
family/window/grid/outcome shape, число observations/anchors, bootstrap
degeneracy и hard computation limit. `NOT_SELECTED` различает Holm rejection и
прежнюю materiality/sign проверку. Raw pair status и `uncertainty=NOT_ESTIMATED`
не переименовываются: selector metadata является отдельным слоем и не заявляет
causality или exact false-positive guarantee.

`correlation-plan.v1` и top-level `analysis-result.v1` не меняются. При наличии
diagnostic plan identity module `load-resource-diagnostics` получает version
`2`; без diagnostics identity остаётся byte-identical.

## Приёмка

- Literal JVM fixture фиксирует p для обоих blocks, max-p, Holm с ineligible
  `p=1` и deterministic replay.
- Integration fixture сохраняет genuine-partial raw `CANDIDATE`, публикует
  `UNAVAILABLE` и не создаёт headline.
- Scope/limit fixture завершается без bootstrap work.
- Root выполняет focused RED/GREEN и общий sequential gate; broad Monte Carlo и
  Jenkins не запускаются.

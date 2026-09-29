# Resource statistics — первая аналитическая поставка

**Статус:** Accepted — пользователь согласовал реализацию с совместной проверкой
бизнес- и ресурсных SLA на одинаковых evaluation windows.

**База:** `cb61190`, ветка `feat/resource-statistics`.

**Основание:** [приоритеты](../../development-plan-v0.6.md#ближайший-приоритет-статистический-анализ-корреляция-и-capacity),
delta-spec §§12, 14, 16 и ADR 0002/0003. Это часть статистической основы,
не завершение correlation, capacity или полного MVP.

## Результат и границы

Пользователь загружает JTL/`simulation.log` и подготовленный resource snapshot.
Один локальный analysis показывает статистики ресурсов по заданным окнам,
наблюдаемые изменения и интервалы превышения явных порогов. CLI и UI используют
одни расчёты; данные, настройки и результат сохраняются для воспроизведения.

Первая поставка делает descriptive statistics и threshold evidence. Она не
объявляет автоматически найденную стационарность, change point, причинную
связь или capacity bound. Эти выводы требуют следующих алгоритмов и проверок.

Выбран existing Kotlin/JDK core. Отдельный Python/R service потребовал бы
второго runtime и согласования вычислений; расчёт внутри Grafana связал бы
результат с transport и не обеспечивал snapshot-only replay. Новых production
dependencies, plugin registry, generic execution framework или database нет.

## Вход для локальных адаптеров

Новый versioned input `resource-snapshot.v1` содержит привязку к load input hash,
общую UTC grid, набор resource series и explicit evaluation windows. JSON Schema
и пример поставляются вместе с использующим их кодом, не отдельным scaffold.

- Grid задаёт `start_epoch_ms`, `step_ms`, `point_count`. Одна ячейка —
  `[start + i*step, start + (i+1)*step)`. `step_ms` — целое число секунд,
  от 1 до 60; timestamps и checked arithmetic следуют текущим границам runtime.
- Series содержит уникальный id, metric name, unit, entity id, role
  `system|generator`, labels и массив `values` длины `point_count`.
- Первая версия принимает **interval means** для gauges и **interval rates**
  для rates, с явным `aggregation`. Значение относится к указанной ячейке,
  не к неизвестному окну dashboard. Изолированные instant samples, raw counters
  и histogram/percentile series не маскируются под interval means: адаптер обязан
  явно подготовить поддерживаемые ряды или сообщить неподдерживаемую семантику.
- Числа конечны, nullable gaps сохраняются. На входе decimal tokens ограничены
  по длине и magnitude; порядок series канонизируется по id. Units и aggregation
  сохраняются в результате, автоматического распознавания по имени метрики нет.
- Windows имеют уникальный id и half-open boundaries на grid. Они лежат внутри
  load run и snapshot, не пересекаются и сортируются по времени. Без windows
  применяется пересечение snapshot и run по полностью включённым ячейкам;
  отброшенные края явно показаны. Если полного пересечения нет — ошибка привязки.
- Явные stages в этой поставке — только именованные evaluation windows.
  Target/load axis, capacity policy и inferred segmentation не притворяются
  реализованными за счёт этих имён.
- Известная clock correction применяется адаптером и записывается в provenance;
  ядро не подбирает сдвиг по максимальной корреляции. Неизвестная синхронизация
  помечается и позднее ограничивает inter-source lag interpretation.
- Provenance содержит source kind, query semantics и способ clock alignment;
  не принимает headers, cookies, tokens, credentials или произвольные URL.
  Exporter-specific labels, PromQL и Grafana endpoint mapping остаются адаптеру.

Ресурсные пределы первого контракта: 16 MiB файла до parsing, depth 12,
64 series, 100 000 points на series, 500 000 cells суммарно включая null,
64 windows; ids до 128 UTF-8 bytes, до 16 labels на series, key/value до
128/512 bytes. Unknown/duplicate JSON fields, duplicate ids, неверные длины,
NaN/Infinity, overflow и несовместимые units/thresholds отклоняются до job.
Numeric magnitude не выше `1e18`, не более 32 цифр значимости и 12 fractional
digits после canonical expansion; расчёт не должен создавать non-finite output.
Чтобы набор правил не размножал короткие превышения до миллионов JSON objects,
analysis ограничен 10 000 resource threshold findings суммарно. Превышение —
явная ошибка `RESOURCE_FINDINGS_LIMIT_EXCEEDED`, без partial publication.
Оконные бизнес-метрики сохраняют только policy-referenced transaction identities;
бюджет `windows × (1 + retained identities)` — максимум 10 000 histograms,
включая overall. Это отдельная граница от количества global transaction metrics.

## Статистические механизмы `resource-statistics.v1`

Для каждой series/window сохраняются expected/observed/missing cells, coverage,
самый длинный gap и следующие наблюдаемые статистики:

- `min`, `max`, mean и median по non-null ячейкам равной длительности;
- Q05/Q25/Q75/Q95, IQR = Q75 − Q25;
- unscaled MAD = median абсолютных отклонений от median;
- sample standard deviation с делителем `n−1`, только при `n >= 2`;
- OLS slope относительно времени в seconds, с центрированием времени/значений;
  это описательный slope, без p-value или утверждения значимости;
- разность median второй и первой временных половин окна. Граница половин
  выбирается по числу grid cells, не по числу сохранившихся наблюдений;
  обе половины должны содержать данные. Это split-half shift, не найденный
  change point и не автоматическая классификация steady state.

Quantiles: type 7, `h=(n−1)*p`, линейная интерполяция соседних order statistics
с zero-based index; для одного значения все quantiles равны ему. Это quantiles
ресурсных ячеек, не percentiles исходных HTTP requests. Определения:
[R quantile](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/quantile.html),
[NIST scale measures](https://www.itl.nist.gov/div898/handbook/eda/section3/eda356.htm).

Нулевой ряд не теряется; constant series имеет нулевые spread/slope, но это не
доказательство здоровья ресурса. Для пустого окна statistics = null с
`NO_OBSERVATIONS`; для неподдерживаемой малой выборки — null с
`INSUFFICIENT_OBSERVATIONS`. Coverage не заменяется числом наблюдений и не
интерпретируется как statistical confidence. Gaps не заполняются и не удаляются
из временной оси. Расчёты deterministic в пределах versioned JVM algorithm;
форматирование не участвует в threshold decisions.

Никаких независимых-sample p-values для автокоррелированных рядов. Корреляция,
оценка неопределённости, change-point detection и проверка steady state будут
отдельным шагом на этих же данных, с тестами ложноположительных результатов.

## Пороги и findings

Optional rule ссылается на series id и задаёт unit, `gt|lt`, threshold и
`min_consecutive_cells >= 1`. Нарушение — последовательность соседних non-null
ячеек с выполненным сравнением; null разрывает последовательность. Findings
сохраняют окно, entity, rule, observed range, число ячеек и evidence reference.

Длительность относится к последовательности interval aggregates: превышение
среднего не утверждает, что каждый instant внутри ячейки превышал threshold.
CPU ratio, CPU cores и memory bytes не смешиваются. Без порога выводится
статистика, но не выдумывается saturation. Даже заданный resource threshold
означает наблюдаемое нарушение правила, а не доказанную причину SLA failure.

Пороги и windows — result-affecting configuration, отдельно от `policy.v1`.
Resource rule явно задаёт effect `diagnostic|sla`: диагностические findings
не меняют verdict; SLA rules обязательны. Existing `policy.v1` задаёт бизнес-SLA
и в enriched analysis проверяется отдельно на каждом том же окне. Samples
принадлежат окну по start timestamp; latency сохраняется целиком, throughput
делится на длительность окна. Окно без business observations даёт NO_VERDICT.
Отсутствующие ячейки обязательного resource rule дают NO_VERDICT, даже если
наблюдаемая часть проходит порог; observed violations сохраняются отдельно.
Для каждого окна и всего анализа приоритет: invalid/degraded load или
непроверяемый обязательный SLA → NO_VERDICT; нет обязательных правил → NO_POLICY;
иначе любое нарушение → FAIL; иначе PASS. Диагностика и будущая корреляция не
подменяют обязательные SLA. Load validity не меняется из-за resource gaps.
Секреты/HTML интерпретироваться не должны.

## Интеграция в существующее приложение

- `AnalysisRequest` получает optional validated resource input/config;
  `AnalysisService` вызывает чистые функции статистики в existing bounded job.
- Input semantic hash, config hash, schema/module versions и limits входят
  в identity enriched analysis. Transport-only provenance хранится отдельно
  и не меняет вычисленные facts/verdict. Исходные snapshot bytes сохраняются
  с manifest/hash; completed analysis не дописывается и не перезаписывается.
- При отсутствии resource input текущие load-only identity/results остаются
  byte-identical. Existing baseline не переназначается автоматически.
- Typed `resource_summary` evidence и resource findings используют existing
  object slots `analysis-result.v1`; новая top-level result schema не нужна.
- UI добавляет optional snapshot input и таблицу series/window statistics
  с coverage и findings. CLI получает эквивалентный optional input. Сохранённый
  enriched result открывается без job и без external calls; JSON экспорт
  содержит evidence. Existing HTML/AsciiDoc должны отображать новые findings
  безопасно и не выдавать load-only представление за полный enriched report.

Область будущего implementation plan: новые snapshot validator, чистые
resource-statistics/window функции и UI section; точечные изменения existing
AnalysisRequest/AnalysisService/identity, store, CLI/API и views, плюс schema,
fixtures, tests и документация. Отдельный storage/service layer не нужен.

## Контрольные примеры и приёмка

1. Значения `[0,1,2,3]`, step 10s: mean/median 1.5, Q05 0.15, Q95 2.85,
   IQR 1.5, MAD 1, sample variance 5/3, slope 0.1 unit/s, half shift 2.
2. `[5,5,5,5]`: spread/slope/half shift = 0; без rule нет saturation finding.
3. `[1,null,3,null]`: coverage 2/4; timestamps остаются 0 и 20s, не 0 и 10s.
   `[null,null]` не превращается в нулевые метрики.
4. `[0.9,null,0.9,0.95]`, `gt 0.8`, minimum 2: одно нарушение последних двух
   ячеек; gap запрещает считать все три превышения непрерывным интервалом.
5. Разные windows одной series не смешивают warm-up и evaluation;
   load input hash mismatch, overlapping windows и unsupported aggregation
   дают явную validation error, а не best-effort привязку.
6. Constant/large-offset/малые variance inputs проверяют численную устойчивость;
   malicious text, oversize/depth, duplicates и cancellation — trust boundary.
7. CLI/UI parity, reload, immutable previous analysis и unchanged load-only
   golden hashes; новый snapshot/config меняет enriched analysis identity.

RED/GREEN tests покрывают эти literals до production code. Полная локальная
проверка: JVM tests/ktlint/build, UI typecheck/lint/E2E, schema/examples, Markdown,
secret scan и полный concern diff. Один итоговый review, затем только scoped
fix verification. Реальный VM/Grafana доступ не является gate этой поставки.

## Следующие независимые результаты

После первой поставки resource/correlation и capacity/SLA используют общий
snapshot/window contract. Следующие шаги добавляют load-conditioned association,
lags/uncertainty и explicit capacity stage outcomes соответственно. Ни generic
snapshot import, ни descriptive statistics сами по себе не закрывают эти цели.

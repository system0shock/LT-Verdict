# ADR 0016: Метрики: перцентиль не выше максимума, пустое окно как null в evidence, parent-сэмплы JMeter CSV

Статус: Proposed, 2026-10-01. Частично заменяет [ADR 0003](0003-policy-v1-metrics-evidence.md): абзац «JMeter CSV остаётся flat...» и строку матрицы вклада для flat CSV. Дополняет [ADR 0017](0017-baseline-candidates-and-confirmation.md) (Accepted, реализован PR #26); граница описана ниже. [ADR 0014](0014-resource-series-limits-autostep-arm-api.md) (Accepted для измеренных лимитов) и [ADR 0015](0015-list-active-analysis-jobs.md) (Accepted) не меняет.

База: `origin/main` `e8e096f`; ссылки `файл:строка` сверены с этой ревизией. Источники: аудит 2026-09-29, разделы «Перцентили HDR при сравнении с порогом» и «Модель анализа» (`docs/ui-mockup/audit-2026-09-29.md`, не отслеживается в `origin/main`), и решения владельца. Владелец 2026-09-30 решил оформить числа ядра отдельным ADR, а повторное закрепление baseline при смене identity проводить отдельно от ADR 0014. Метки пунктов аудита здесь не используются: в планах они расходятся.

## Контекст

В ядре используется `PackedHistogram(1, 86_400_000, 3)` (`Metrics.kt:15-18`, `:268-273`). При трёх значащих цифрах значения меньше 2048 мс хранятся точно. Начиная с 2048 мс `getValueAtPercentile` возвращает верхнюю границу эквивалентного диапазона. Перебор значений 1–3000 мс показал первое превышение над наблюдённым значением при 2048 мс. Расчёт на HdrHistogram 2.2.2 для 100 одинаковых сэмплов даёт:

| Наблюдённое значение, мс | p50 = p95 = p99, мс |
| --- | --- |
| 2047 | 2047 |
| 2048 | 2049 |
| 4095 | 4095 |
| 4096 | 4099 |
| 60000 | 60031 |
| 86400000 | 86441983 |

Последнее значение перцентиля превышает даже настроенный `highestTrackableValue` 86 400 000 мс. Для значений от 2048 мс относительное превышение строго меньше `1/1024` (около 0,0977 %); наибольшее найденное при переборе — `9,73e-4`.

Сейчас `MutableMetrics.summary` публикует эти значения без ограничения фактическим максимумом (`Metrics.kt:265-283`, `:300-302`). Из summary они попадают в `UtcLoadMetrics`, policy evidence и сравнение с порогом `lte` (`Policy.kt:215-216`, `:306-308`), а также в диагностическое evidence (`DiagnosticAnalysis.kt:765-767`). Bucket API вычисляет p95 при чтении сжатой гистограммы (`LocalApi.kt:1524`, `:1530-1538`); статический график отчёта делает то же (`StaticLoadChart.kt:90-106`). Python-оракул воспроизводит округление (`tools/applicability_validation.py:28-34`): тест сейчас ожидает для `[4094]` значение `4095` (`tools/test_applicability_validation.py:79-84`).

Ограничение перцентиля фактическим максимумом устраняет превышение над `max`, но не делает оценку точной. Для `95 × 60000 + 5 × 60010` точный p95 равен 60000 мс, HDR даёт 60031 мс, а ограничение максимумом оставляет 60010 мс. Для `100 × 60000 + 1 × 70000` точный p95 равен 60000 мс; HDR и после ограничения даёт 60031 мс. Поэтому порог между точным и опубликованным значением всё ещё может дать ложный `FAIL`.

Второй дефект касается пустого окна. Внутренняя `emptySummary` записывает четыре нуля латентности (`Metrics.kt:317-324`), а `window_metric_summary` публикует их как измерения (`DiagnosticAnalysis.kt:747-770`). Нулевая выборка не имеет измеренной латентности; настоящий сэмпл длительностью 0 мс имеет. `error_rate_ratio` для пустой выборки уже равен `null`, тогда как `throughput_rps` является точным отношением нуля к длительности окна (`Metrics.kt:296-297`).

Третий дефект — двойной счёт parent-строк Transaction Controller в CSV. CSV-парсер назначает каждой строке `JMETER_SAMPLER` (`JtlCsvParser.kt:79-97`); такой kind входит в overall и 1-секундные buckets (`Metrics.kt:117`, `:212`), а также в UTC load (`UtcLoadMetrics.kt:51`). XML-парсер различает контейнер по вложенным `sample` (`JtlXmlParser.kt:88-96`). Заголовок имеющейся CSV-фикстуры JMeter 5.6.3 не содержит признака parent (`fixtures/slice1/jmeter/csv-5.6.3/input.jtl:1`). Способ записи parent-строк живым JMeter 5.x и возможность штатно добавить такую колонку через `sample_variables` не проверены.

Все три изменения влияют на воспроизводимый результат. `analysis_id` вычисляется из identity, а сохранённый результат возвращается из кэша по этому id (`AnalysisService.kt:131-140`). Если оставить прежнюю identity, повторный анализ может вернуть старые числа. Блок `modules` входит в семантический ключ baseline и истории динамики (`BaselineComparison.kt:942-943`, `RunComparison.kt:424-425`), поэтому новая версия общего модуля `metrics` затронет analyses всех источников, включая analyses без diagnostics.

ADR 0017, уже реализованный в PR #26, закрывает пустое окно на стороне потребителя: baseline-сравнение читает `sample_count` (`BaselineComparison.kt:441`, `:470`) и выдаёт `INSUFFICIENT_DATA` с `EMPTY_WINDOW`. Тесты проверяют старую форму с `0` и новую форму с `null` (`BaselineComparisonTest.kt:593`, `:630`, `:671-673`). Здесь меняется только производитель evidence.

## Решение

### Перцентили

Сохранить `significantDigits = 3`, конфигурацию гистограммы и HdrHistogram 2.2.2. Для каждого непустого summary ограничивать p50, p95 и p99 фактическим максимумом записанных сэмплов:

```text
published_p(q) = min(histogram.getValueAtPercentile(q),
                     observed_max_latency_ms)
q ∈ {50, 95, 99}
```

Для непустой выборки это возвращает инвариант:

```text
p50 <= p95 <= p99 <= max_latency_ms
```

Опубликованный перцентиль не меньше точного перцентиля: HDR возвращает верхнюю границу диапазона, а максимум не меньше любого значения. Для положительного точного значения он меньше `точный × (1 + 2^-10)`; при точном нуле остаётся нулём. Для правила `lte` это безопасная оценка в одном направлении: `PASS` не возникает из-за занижения перцентиля, но ложный `FAIL` из-за округления внутри диапазона остаётся возможным.

Правило применяется к `MutableMetrics.summary` (`Metrics.kt:300-302`). Через него исправляются обычные и оконные summary, `UtcLoadMetrics` с существующим порогом `MIN_P95_SAMPLES = 20` (`UtcLoadMetrics.kt:67`, `:76-80`, `:102`), policy и диагностическое evidence. Порог числа сэмплов в UTC load не меняется.

Отдельно применять то же правило при декодировании p95 в bucket API (`LocalApi.kt:1537`) и статическом графике (`StaticLoadChart.kt:105`). Для bucket брать поле строки `max_latency_ms`, а не `histogram.maxValue`: последнее само является верхней границей диапазона и для сэмпла 60000 мс равно 60031 мс. Согласовать Python-оракул и его тест: `[4094]` должен давать опубликованное значение `4094`, сохраняя расчёт HDR до ограничения.

### Пустое окно

Только в evidence типа `window_metric_summary`, если `sample_count == 0`, публиковать `null` во всех четырёх полях `latency_ms`:

```text
sample_count == 0
=> latency_ms.{p50,p95,p99,max} = null
=> error_count = 0
=> error_rate_ratio = null
=> throughput_rps = 0 / duration
```

`throughput_rps` остаётся точной дробью: за окно измерено ровно ноль сэмплов. Латентность при отсутствии сэмплов не измерена. Если `sample_count > 0`, все четыре поля остаются числами; реальная латентность 0 мс остаётся числом `0`.

Это единственное публикуемое оконное evidence с латентностью. Оконный `metric_summary` не добавляется в результат (`WindowPolicy.kt:27`, `includeMetricEvidence = false`). Внутренняя `emptySummary` остаётся числовой (`Metrics.kt:317-324`); window-независимый `metric_summary` также сохраняет числовой тип латентности (`Policy.kt:276-312`). Схема `analysis-result.v1` не меняется: `evidence` задан как массив объектов без вложенной схемы (`docs/contracts/result/v1/analysis-result.schema.json:54-59`). `WindowMetricSummaryEvidence` уже допускает `null` (`ui/src/types.ts:293`), а `MetricSummaryEvidence` остаётся числовым (`ui/src/types.ts:146`).

### JMeter CSV marker

Ввести необязательную колонку с точным именем `ltv_transaction_controller_parent`. Регистр имени заголовка значим, как у существующих колонок (`JtlCsvParser.kt:128-132`). Колонка является явной декларацией producer или документированного конвертера, а не выводом парсера из содержимого строки.

| Значение marker | `LoadSample.kind` или результат |
| --- | --- |
| `true` в любом регистре | `JMETER_CONTAINER` |
| `false` в любом регистре | `JMETER_SAMPLER` |
| Пустое значение | `JMETER_SAMPLER` |
| Иное значение, включая `yes` | `INVALID`, `MALFORMED_JMETER_CSV` |
| Дублирующийся заголовок marker | `INVALID`, `INVALID_JMETER_CSV_HEADER` |
| Колонка отсутствует | Все строки `JMETER_SAMPLER` |

Значения `true` и `false` разбираются по тому же правилу, что `success` (`JtlCsvParser.kt:143-148`). Пустое значение — единственное дополнительное разрешённое значение. Дубль заголовка отклоняется тем же механизмом `singleOrNull`, что применяется к обязательным колонкам (`JtlCsvParser.kt:128-132`). CSV без marker сохраняет прежние числовые результаты и evidence; различаются только bytes, обусловленные новой identity.

Marker не создаёт иерархию: `groupPath` у CSV остаётся пустым. Parent не входит в overall, 1-секундные buckets, rollups и UTC load, но входит в собственную точную сводку транзакции (`Metrics.kt:117`, `:212`, `UtcLoadMetrics.kt:51`). Окно прогона по-прежнему строится по всем строкам, включая parent: минимум start и максимум end (`AnalysisService.kt:248-249`), согласно разделу «Run window» ADR 0003. Поэтому parent может влиять на знаменатель throughput, хотя не увеличивает число overall-сэмплов.

Идентичность транзакции включает kind. При одинаковом label у parent и sampler возникают две разные транзакции; policy scope по этому label возвращает `AMBIGUOUS_TRANSACTION` (`Policy.kt:199-203`). Раньше flat CSV объединял такие строки в одну сводку. Если после разметки нет ни одной строки `JMETER_SAMPLER`, вход становится `INVALID` с `EMPTY_INPUT` (`JtlCsvParser.kt:100`). Так overall валидного JMeter CSV непуст, а window-независимый `metric_summary` не публикует нулевую латентность пустой сводки. Gatling-прогон только со строками `GROUP` остаётся валидным и пустым по overall (`GatlingTextParser.kt:84-103`): `metric_summary` публикует четыре нуля, а политика даёт `NO_VERDICT`; это известный остаток, вне объёма ADR (вопрос 9).

Обычный CSV без marker остаётся flat: parent, если он присутствует, продолжает считаться вместе с детьми, без предупреждения. Для работы решения нужен проверяемый producer или конвертер, который добавляет marker по известной ему семантике. Для сценариев с Transaction Controller пользовательская документация также рекомендует JTL XML. Утверждений о том, как живой JMeter 5.x записывает parent в CSV или поддерживает ли такую колонку штатно, это решение не делает.

При реализации внести в ADR 0003 пометку о частичной замене его абзаца `:66-67` и строки flat CSV в матрице `:81-86`. Это не возврат к отклонённой эвристике: парсер доверяет явной декларации producer, не угадывает kind по label, elapsed, порядку или `responseMessage`.

### Identity, версии и golden

Повысить версии в `analysis-identity.v1`:

| Компонент | Было | Станет | Область |
| --- | --- | --- | --- |
| Модуль `metrics` | `1` | `2` | Каждый анализ |
| Модуль `load-resource-diagnostics` | `2` | `3` | Только если модуль присутствует |
| Parser `jmeter-csv` | `1` | `2` | Только JMeter CSV |
| `input_versions.source` для CSV | `jmeter-jtl-csv.v1` | `jmeter-jtl-csv.v2` | Только JMeter CSV |

Сейчас версия parser жёстко задана как `"1"` для всех источников (`AnalysisResult.kt:67-74`). Реализация должна выбирать её по `SourceType`: у `jmeter-csv` — `"2"`, у остальных parser — `"1"`. Аналогично заменить только CSV-ветку `inputVersion` (`AnalysisResult.kt:247-253`). Версия diagnostic-модуля сейчас `"2"` (`AnalysisResult.kt:78-94`); повышать её лишь при наличии модуля. Блок `limits` не меняется.

Пересчитать canonical bytes, `.sha256` и записи `fixtures/slice1/manifest.json` для `fixtures/slice1/identity/analysis-identity.v1.json` и `analysis-identity-resources.v1.json`. Оба golden содержат `metrics` версии `1` и CSV parser/input старой версии. Golden без diagnostics не должен внезапно получить `load-resource-diagnostics`; его версию в соответствующем анализе проверяет `AnalysisIdentityDiagnosticVersionTest`.

Существующие `fixtures/slice1/**/oracle.json` остаются без изменений: в них нет значений латентности от 2048 мс, а CSV без marker разбирается как раньше. Добавить отдельную golden-фикстуру marker на основе реального прогона JMeter 5.6.3 с Transaction Controller. Marker добавить документированным преобразованием, а в manifest зафиксировать producer, исходный артефакт и преобразование. Происхождение и доступность такого прогона остаются вопросом владельцу.

### Совместимость со старыми анализами

Сохранённые analyses, их canonical bytes, SHA-256 и baseline bindings не переписываются. Новый запуск тех же входных bytes получает новый `analysis_id` и пересчитывается по новым правилам. Старые результаты остаются читаемыми по прежней identity.

Версия `metrics` входит в каждый analysis, поэтому оконное сравнение старого baseline с новым анализом возвращает `NOT_EVALUATED` с причиной `INCOMPATIBLE_METRIC_DEFINITION` (`BaselineComparison.kt:212`); для строк метрик причина тоже `INCOMPATIBLE_METRIC_DEFINITION` (`:569`), если метрики доступны. Если метрик нет (например, у ручного baseline с `INVALID` analysis), приоритет у `MISSING_METRIC` (`:566-570`): это существующее поведение ADR 0016 не меняет. Смешанный старый и новый набор кандидатов получает `BASELINE_MIXED_SEMANTICS` (`BaselineComparison.kt:116`). История динамики исключает старые analyses из сопоставимого ряда и увеличивает `excluded_incompatible_count` (`RunComparison.kt:39-41`). Это относится и к analyses без diagnostics.

Сохранённый `run-period.json` (`RunPeriod.kt:55-58`) строится по интервалам, занятым строками `JMETER_SAMPLER` и `GATLING_REQUEST`, и привязан к hash входа (`SourceAnalysis.kt:167-176`). Вход без marker разбирается как раньше, поэтому старые `run-period.json` остаются верными. Вход с marker имеет другие bytes, то есть другой `run_id`, и получает собственный период; родитель с `true` в занятые интервалы авто-окна уже не входит, как контейнер XML. Допущение: ранее существовавший CSV, который случайно содержал колонку с таким именем, не ожидается; если он есть, его нужно перезагрузить как новый вход.

Bucket API вычисляет p95 при чтении, не хранит его. Ограничение по `max_latency_ms` действует и на старые analyses: у старого анализа p95 графика может стать 60000 мс, тогда как его неизменяемое evidence содержит 60031 мс. Статический график также является производным представлением. Старые canonical bytes от этого не меняются; различие явно описывается пользователю.

### Граница с ADR 0017 и ADR 0014

ADR 0017 уже реализовал защиту потребителя пустого окна. Она читает `sample_count`, а не числовую латентность (`BaselineComparison.kt:441`, `:456`, `:470`). Старое evidence с `0` и новое с `null` безопасны; порядок слияния изменений любой. Статусы, причины, правила выбора baseline и тесты потребителя ADR 0017 не пересматриваются. Его текст описывает несовместимость baseline с diagnostics; настоящий ADR уточняет более широкое следствие версии общего модуля `metrics` для baseline любого вида.

ADR 0014 принят для измеренных лимитов и отдельно меняет identity через `limits`. По решению владельца от 2026-09-30 эту смену identity не совмещать с ADR 0016. Порядок реализации двух решений может быть любым; после каждого потребуется свой пересчёт анализов и повторное закрепление baseline для сравнения с новой identity. Момент обеих операций нужно заранее довести до владельца.

## Следствия

- В новых анализах p50, p95 и p99 могут стать ниже прежних опубликованных значений менее чем на 0,1 %, прежде всего когда HDR округлял значение выше фактического максимума. Условие `p50 <= p95 <= p99 <= max` гарантируется, но ложный `FAIL` у порога внутри эквивалентного диапазона остаётся возможным.
- Пустое окно показывает отсутствие измерения latency как четыре `null`. Нулевой throughput остаётся точным числом, а сэмпл длительностью 0 мс остаётся измерением.
- Размеченный CSV исключает parent из overall и сохраняет его transaction summary. Policy по label, общему для parent и child, может перейти от одной сводки к `AMBIGUOUS_TRANSACTION`. Неразмеченный CSV от двойного счёта не защищён.
- Для сравнения с новыми analyses все сохранённые baseline любого вида потребуется закрепить заново. Старые analyses остаются читаемыми; история динамики отделяет их по семантическому ключу. Интерфейс уже объясняет несовместимость старого эталона (`ui/src/shell/labels.ts:57`).
- Ячейки UTC load и корреляционные диагностики используют тот же p95 (`UtcLoadMetrics.kt:76-80`), поэтому в новых analyses при латентности от 2048 мс их входные значения могут снизиться менее чем на 0,1 %; результаты корреляций пересчитываются только при новом анализе, старые не меняются.
- В одном старом analysis производный bucket p95 может отличаться от неизменяемого p95 в evidence. Это ожидаемое следствие применения нового правила чтения к старой гистограмме.
- В PR реализации обновить `docs/architecture/slice-1-local-runtime.md` (матрица вклада, перцентили, identity и bucket `p95`), `docs/user/slice-1-local-analysis.md` (marker, путь producer/конвертера и рекомендация XML), `CHANGELOG.md` и пометку в ADR 0003. Новых production-зависимостей и изменения схемы `analysis-result.v1` нет.

## Отклонённые альтернативы

- Повысить `significantDigits`: уменьшает ошибку, но меняет identity и расход памяти; само по себе не гарантирует `percentile <= observed max`.
- Хранить все latency для точного перцентиля: нарушает ограниченную по памяти потоковую модель.
- Брать нижнюю границу диапазона HDR: занижает наблюдаемый перцентиль и может дать ложный `PASS` для `lte`.
- Округлять к середине диапазона или вниз: также может занизить значение и не даёт нужной гарантии для `lte`.
- Оставить `0` вместо `null` в latency пустого окна: смешивает отсутствие выборки с настоящим сэмплом 0 мс.
- Публиковать `null` также для throughput пустого окна: теряет точный факт «ноль сэмплов за заданную длительность»; baseline-потребитель уже скрывает такое окно по `sample_count`.
- Определять CSV parent по label, elapsed, порядку строк или `responseMessage`, включая текст XML «Number of samples in transaction : ...»: это вывод из данных без надёжного контракта. Отдельная предупреждающая диагностика без переклассификации и изменения чисел — иной вариант, оставленный владельцу.
- Игнорировать неизвестное значение marker: ошибочная декларация producer снова даст двойной счёт как валидный результат.
- Не менять identity: кэш (`AnalysisService.kt:131-140`) вернёт старый результат и исправление не проявится.
- Применять bucket cap только при новой версии модуля: потребует чтения identity в bucket API ради производного представления и оставит известное превышение у старых анализов.
- Перезаписать старые analyses: нарушит неизменяемость сохранённых артефактов и точную привязку baseline из `0010-baseline-conditions-confirmation.md`.
- Совместить смену identity с ADR 0014: противоречит отдельным этапам и повторным закреплениям, выбранным владельцем.

## План проверки

Критерии приёмки:

1. У каждого нового непустого summary и производного p95 выполняется ограничение фактическим максимумом; для `lte` не появляется занижения точного перцентиля.
2. Пустое `window_metric_summary` содержит четыре `null`; непустое окно с реальным 0 мс содержит числа.
3. CSV marker разбирается строго; parent исключён из overall и остаётся transaction summary; CSV без marker сохраняет прежние числа.
4. Новые identity bytes и SHA-256 воспроизводимы; старые analyses читаются без перезаписи и отделены от новых при сравнении.
5. Golden, manifest, документация и тесты согласованы; изменены только файлы задачи.

Проверить тестами следующую матрицу:

| Случай | Ожидаемый результат | Основные тесты |
| --- | --- | --- |
| 100 одинаковых значений 2047, 2048, 4096, 60000 и 86400000 мс | p50/p95/p99 не выше наблюдённого max | `MetricsTest`, `UtcLoadMetricsTest` |
| `95 × 60000 + 5 × 60010` и `100 × 60000 + 1 × 70000` | 60010 и 60031 мс для p95; остаточное округление описано | `MetricsTest` |
| Bucket и статический график, включая старый analysis | cap по `max_latency_ms`; evidence старого analysis неизменно | `LocalApiTest`, `StaticLoadChartTest` |
| Пустое окно и сэмпл 0 мс при `sample_count > 0` | Четыре `null` только у пустого окна | `DiagnosticAnalysisTest`, `DiagnosticIntegrationTest`, `WindowMetricsTest` |
| Parent вместе с детьми и одинаковый label | Нет двойного overall; parent summary и `AMBIGUOUS_TRANSACTION` | `JtlCsvParserTest`, `JtlGoldenTest`, `MetricsTest` |
| `yes`, дубль marker, пустое значение, CSV без marker | Заданные diagnostic codes и legacy-поведение | `JtlCsvParserTest`, `JtlGoldenTest` |
| Все строки помечены parent | `INVALID` с `EMPTY_INPUT` | `JtlCsvParserTest` |
| Новые canonical identity bytes, обе `.sha256`, manifest | Версии соответствуют таблице, hashes совпадают | `AnalysisResultGoldenTest`, `AnalysisIdentityDiagnosticVersionTest`, `FixtureManifestTest` |
| Старый baseline против нового анализа; смешанные кандидаты; история динамики | `INCOMPATIBLE_METRIC_DEFINITION`, `BASELINE_MIXED_SEMANTICS`, `excluded_incompatible_count`; для analysis без метрик `MISSING_METRIC` | `BaselineComparisonTest`, `RunComparisonTest` |
| Реальные identity: старая (версии `1`, из golden) и новая из `analysisIdentity`; сохранённый старый analysis читается без перезаписи | Несовместимы по ключу; старый результат неизменен | `AnalysisServiceTest`, `BaselineComparisonTest`, `RunComparisonTest` (существующие тесты используют синтетические identity с пустыми `parsers` и `modules`) |
| Сквозной CSV с marker и политикой по общему label | Две транзакции, `AMBIGUOUS_TRANSACTION` | `AnalysisServiceTest` |
| Старый `run-period.json` и вход без marker | Период и отказ авто-окна не меняются | `RunPeriodTest` |
| Старое и новое evidence пустого окна | Одинаковое `INSUFFICIENT_DATA` и `EMPTY_WINDOW` | `BaselineComparisonTest` |
| `[4094]` в Python-оракуле | Опубликованный p95 равен 4094 | `tools.test_applicability_validation` |

Дополнительно проверить, как `AdvisoryAi`, `AsciiDocReport` и `HtmlReport` показывают JSON `null` в `window_metric_summary.latency_ms`: это ещё не проверено. Проверить, что CSV golden с marker документирует происхождение исходного JMeter-прогона и преобразование, а прежние `oracle.json` не меняются.

Команды из корня репозитория в PowerShell после реализации:

```powershell
.\gradlew.bat test --tests 'io.ltverdict.metrics.MetricsTest' --tests 'io.ltverdict.metrics.WindowMetricsTest' --tests 'io.ltverdict.metrics.UtcLoadMetricsTest'
.\gradlew.bat test --tests 'io.ltverdict.web.LocalApiTest' --tests 'io.ltverdict.integrations.report.StaticLoadChartTest'
.\gradlew.bat test --tests 'io.ltverdict.core.DiagnosticAnalysisTest' --tests 'io.ltverdict.core.DiagnosticIntegrationTest' --tests 'io.ltverdict.core.AnalysisIdentityDiagnosticVersionTest'
.\gradlew.bat test --tests 'io.ltverdict.ingest.JtlCsvParserTest' --tests 'io.ltverdict.ingest.JtlGoldenTest'
.\gradlew.bat test --tests 'io.ltverdict.core.BaselineComparisonTest' --tests 'io.ltverdict.core.RunComparisonTest' --tests 'io.ltverdict.core.AnalysisResultGoldenTest' --tests 'io.ltverdict.fixtures.FixtureManifestTest'
python -m unittest tools.test_applicability_validation -v
.\gradlew.bat clean check installDist
npx --yes markdownlint-cli2@0.23.2 'docs/adr/0016-*.md'
git diff --check
git status --short --branch
```

Сравнить вывод `Get-FileHash -Algorithm SHA256` для обоих identity JSON с соответствующими `.sha256` и записями manifest. Перечитать полный diff, выполнить применимые проверки документации и секретов в добавленных строках, убедиться, что посторонние файлы не изменены и не staged. Отдельно зафиксировать результаты чтения старого analysis и сравнения старой/новой identity.

## Открытые вопросы владельцу

1. **Момент повторного закрепления baseline.** Варианты: сразу после выпуска пересчитать исходные данные и закрепить baseline заново; отложить, оставив сравнения со старыми baseline недоступными (`INCOMPATIBLE_METRIC_DEFINITION`) до пересчёта. Совмещение с ADR 0014 уже исключено решением владельца от 2026-09-30. Рекомендация: заранее объявить окно пересчёта всех baseline, пока их немного, и выпускать ADR 0016 и ADR 0014 в разные окна; при внедрении обоих решений это два независимых повторных закрепления.

2. **Достаточен ли marker для Transaction Controller.** Варианты: (a) marker и документированный producer/конвертер; (b) marker плюс отдельная диагностика-предупреждение без переклассификации строк и изменения чисел; (c) рекомендация JTL XML для таких сценариев. План владельца предполагал «предупреждение или отказ», тогда как marker сам по себе не обнаруживает неразмеченный CSV. Рекомендация: сейчас принять (a) вместе с (c); вариант (b) рассматривать отдельным ADR, если пилот покажет потребность.

3. **Применять ли cap bucket API к старым analyses.** Варианты: применять единообразно ко всем декодируемым buckets; гейтить по версии `metrics`, сохраняя старое производное значение. Рекомендация: применять единообразно и документировать возможное различие с неизменяемым evidence; гейт требует лишнего чтения identity в API.

4. **Оставить ли `significantDigits = 3`.** Варианты: оставить ограничение перцентиля фактическим max; увеличить точность гистограммы отдельной сменой identity и бюджета памяти. Рекомендация: оставить `3` сейчас, явно сообщать об остаточном ложном `FAIL` внутри диапазона и возвращаться к точности только по измеренной потребности.

5. **Повышать ли CSV parser/input версии при уже повышенной версии `metrics`.** Варианты: изменить только `metrics`; отдельно версионировать parser и `input_versions.source`. Рекомендация: версии `jmeter-csv = 2` и `jmeter-jtl-csv.v2` нужны, поскольку описывают новый принимаемый формат независимо от вычисления метрик.

6. **Подтвердить ли строгие значения marker.** Варианты: принять `true`/`false` без учёта регистра, пустое как sampler и все-parent как `INVALID EMPTY_INPUT`; сузить значения до точного регистра или разрешить все-parent. Рекомендация: принять описанный контракт: единое правило с `success`, отсутствие неявного parent и непустой overall у валидного прогона.

7. **Оставлять ли throughput пустого окна числом.** Варианты: сохранить точное `0 / duration`; публиковать `null` вместе с латентностью. Рекомендация: сохранить дробь, поскольку нулевой счёт измерен; ADR 0017 скрывает значение в baseline-сравнении по `sample_count`.

8. **Происхождение golden-фикстуры marker.** Варианты: реальный JMeter 5.6.3 run с документированным преобразованием; синтетический CSV без подтверждённого producer. Рекомендация: предоставить или получить реальный run, сохранить описание преобразования и его provenance в manifest. Поведение живого JMeter CSV и `sample_variables` до такой проверки считать непроверенным допущением.

9. **Gatling-прогон только с `GROUP`.** Overall пуст, `metric_summary` публикует нулевую латентность, политика даёт `NO_VERDICT` (`GatlingTextParser.kt:84-103`, `Policy.kt:213`). Варианты: оставить как известный остаток; считать такой вход `INVALID` с `EMPTY_INPUT` (потребует версии Gatling parser); публиковать `null` в window-независимом `metric_summary` (меняет числовой тип `ui/src/types.ts:146`). Рекомендация: оставить и вести отдельной задачей, поскольку вердикт уже `NO_VERDICT`, а ADR 0016 ограничен JMeter CSV.

## Не входит

- Минимальный размер выборки и `INSUFFICIENT_SAMPLES`: это другой дефект и предмет ADR-B.
- Правила выбора baseline, сопоставимости и статусы пустого окна на стороне потребителя: они принадлежат ADR 0017.
- Измеренные лимиты и блок `limits` identity ADR 0014.
- Изменения Gatling и JMeter XML parser, в том числе обработка Gatling-прогона только с `GROUP`.
- Изменение схемы `analysis-result.v1`, Vue-кода и production-зависимостей.

# Онлайн-источники метрик и ошибок

Поддерживаются read-only PromQL `query_range`, InfluxQL GET `/query` и
OpenSearch POST `/_search` и PostgreSQL pre/post capture. Grafana dashboard discovery и panel
transformations не поддерживаются. Боевой plugin/auth route нужно проверить на
вашем стенде. Без connections file приложение не выполняет acquisition.

## Запуск

Скопируйте [пример PromQL-профиля](../contracts/sources/v1/connections.example.json)
или [пример InfluxDB-профиля](../contracts/sources/v1/influxdb-connections.example.json),
задайте адрес и запросы своего стенда. В UI:

```powershell
.\build\install\ltv\bin\ltv.bat ui --connections connections.json
```

Выберите профиль и окно выборки. По умолчанию стоит `Auto (from the load file)`:
период распознаётся из байтов нагрузки, а пользователь задаёт только step,
margin и допуск простоя. Режим `Explicit period` сохраняет прежние UTC start/end
в epoch milliseconds. Step запроса `v3`, который отправляет UI, — целые секунды
от 1000 до 60000 ms. Интервал явного окна должен состоять из полных cells и
укладываться в прогон; авто-окно намеренно выводится шире прогона и обрезается
при связывании — см. раздел «Авто-окно». Правила окна, версии запроса,
отказы и provenance описаны в разделах «Окно выборки» и «Авто-окно» ниже.
Вместо online selection по-прежнему можно загрузить resource snapshot.

CLI использует [пример source request](../contracts/sources/v1/request.example.json);
`--source` принимает `source-request.v1`, `v2`, `v3` и `v4`:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --connections connections.json --source source.json --data-dir data
```

Профили загружаются при запуске; изменение файла требует перезапуска backend.
`source_kind`: `prometheus`, `victoria_metrics` или `influxdb`; `transport`:
`direct` или `grafana_proxy`. Для PromQL direct добавляет `/api/v1/query_range`
к `base_url` (VM tenant prefix сохраняется), а Grafana добавляет
`/api/datasources/proxy/uid/{datasource_uid}/api/v1/query_range`. InfluxDB-маршруты
описаны ниже. Subpath установки задаётся в base URL. Redirects и произвольные
URLs из UI запрещены.

## Окно выборки

Запрос источника объявляет окно выборки. Поддерживаются четыре версии документа:
CLI `--source` и file part `source_request` в `POST /api/jobs` принимают все
четыре, UI отправляет только `v3` и по умолчанию предлагает авто-окно.

| Версия | Назначение | Схема |
| --- | --- | --- |
| `source-request.v1` | один профиль, явное окно | [v1](../contracts/sources/v1/source-request.schema.json) |
| `source-request.v2` | до 16 профилей на общей сетке, явное окно | [v2](../contracts/sources/v2/source-request.schema.json) |
| `source-request.v3` | до 16 профилей, явное или распознанное окно | [v3](../contracts/sources/v3/source-request.schema.json) |
| `source-request.v4` | как `v3` плюс обязательный `window.step_mode` (`fixed` или `auto`), см. «Автошаг» | [v4](../contracts/sources/v4/source-request.schema.json) |

`profile_ids` — от 1 до 16 уникальных идентификаторов до 128 UTF-8 bytes; при
разборе они сортируются, поэтому порядок в документе не значим. Файл запроса —
до 16 KiB. `step_ms` в `v3` — целые секунды от 1000 до 60000, поэтому шаг
1500 ms или 90 s отклоняется при разборе запроса, до внешних обращений. В `v1`
и `v2` шаг должен быть не меньше 1000 ms и делить интервал нацело; профили
метрических семей (`prometheus`, `victoria_metrics`, `influxdb`) дополнительно
требуют целых секунд 1..60, и эта проверка выполняется до выборки. Профили
`opensearch` snapshot не строят, поэтому принимают любой делящий шаг от
1000 ms. Документы `v1` и `v2` принимаются без изменений, их сохранённые
evidence bytes не меняются.

`window.origin: explicit` в `v3` повторяет семантику `v2`: `start_epoch_ms`,
`end_epoch_ms`, `step_ms` и интервал из полных cells.
[Пример explicit-окна](../contracts/sources/v3/examples/valid/explicit-window.json).

## Авто-окно

`window.origin: auto` объявляет только шаг, запас и допуск простоя; границы
периода пользователь не задаёт:

```json
{
  "schema_version": "source-request.v3",
  "profile_ids": ["errors", "metrics"],
  "window": {
    "origin": "auto",
    "step_ms": 15000,
    "margin_ms": 60000,
    "max_idle_gap_ms": 1800000
  }
}
```

В UI это режим `Auto (from the load file)` с полями `Source step (ms)`,
`Margin (ms)` и `Max idle gap (ms)`; невалидные значения блокируют отправку
до запроса. `margin_ms` — от 0 до 3 600 000 и кратен `step_ms`;
`max_idle_gap_ms` — не менее `step_ms` и кратен ему. Верхней границы у допуска
простоя намеренно нет: большое заявленное значение ослабляет отказ
`AUTO_WINDOW_MULTI_TEST_SUSPECTED`, а само значение публикуется в provenance.
[Пример auto-окна](../contracts/sources/v3/examples/valid/auto-window.json).

Период распознаётся из самого файла нагрузки: от минимального start до
максимального end сэмплов; порядок строк в файле не значим. Отдельный проход
читает только timestamps, не строит метрики и не выполняет внешних запросов.
Результат сохраняется один раз на run как
`runs/<runId>/run-period.json`
([`run-period.v1`](../contracts/run-period/v1/run-period.schema.json)),
привязан к hash нагрузки и переиспользуется при каждом повторном открытии:
для неизменных байтов распознавание не повторяется. Простой считается по
отсутствию samples между соседними секундными бакетами, а сохранённые факты
простоев не зависят от заявленного допуска, поэтому тот же артефакт пригоден
для запросов с другим `max_idle_gap_ms`.

Для JMeter CSV периоды записываются с `recognition_method` равным
`sample-timestamps.v2`, остальные источники остаются на `sample-timestamps.v1`.
Причина: parent-строки Transaction Controller (см. «JMeter CSV и Transaction
Controller» в [локальном анализе](slice-1-local-analysis.md)) не занимают
секунды при поиске простоев, поэтому факты простоев CSV могут отличаться от
прежних. Ранее сохранённый период CSV с `v1` пересчитывается автоматически при
следующем анализе с авто-окном; границы периода (первый и последний сэмпл) не меняются.

Окно выводится из распознанного периода: расширение на `margin_ms` с обеих
сторон, обрезка нижней границы до нуля, затем выравнивание обеих границ по
абсолютной сетке шага — start вниз, end вверх. Выравнивание может только
увеличить запас, поэтому применённый запас не меньше заявленного; единственное
исключение — обрезка у нуля. Фактически применённое значение публикуется
отдельно от заявленного как гарантированный минимум обеих сторон. Запас
выбирайте не меньше задержки сбора (scrape interval и окно rate-выражения),
а допуск простоя — длиннее штатных пауз внутри одного теста.

Все три отказа выносятся до любого внешнего запроса, поэтому partial analysis
bundle и coverage не появляются:

- `AUTO_WINDOW_UNAVAILABLE` — период не распознан: вход невалиден или не
  содержит samples. Задайте явное окно.
- `AUTO_WINDOW_MULTI_TEST_SUSPECTED` — внутри периода найден простой строго
  длиннее `max_idle_gap_ms`; вероятно, файл содержит несколько тестов.
  Разделите файл или задайте явное окно.
- `AUTO_WINDOW_SPAN_UNSUPPORTED` — число ячеек выведенной сетки вне диапазона
  1..100 000. Задайте явное окно или более крупный step. Это ограничение на
  ячейки одной серии; отдельно snapshot требует `серии × ячейки <= 1 500 000`,
  поэтому широкое авто-окно при многих профилях может упереться и в него:
  превышение после выборки даёт `SOURCE_SNAPSHOT_LIMIT_EXCEEDED` и статус
  `FAILED` в сводках профилей.

Смещение часов между генератором нагрузки и системой мониторинга не
компенсируется и не оценивается: margin только расширяет покрытие. При
смещённых часах распознанный период может не покрыть нужный диапазон; средство
то же — явное окно.

Для acquisition по `v3` evidence `source_summary` публикует provenance окна:
`window_origin`, а для авто-окна также `recognized_start_epoch_ms`,
`recognized_end_epoch_ms`, `requested_margin_ms`, `applied_margin_ms`,
`max_idle_gap_ms`, `detected_idle_gaps`, `longest_idle_gap_ms` (null, если
простоев не было) и `auto_window_status` со значением `DERIVED`. UI показывает
эти поля таблицей `Window` в секции source acquisition. Summaries `v1`/`v2` и
ручной импорт OpenSearch-контекста полей окна не содержат.

Снапшот авто-окна намеренно не объявляет окон, поэтому core связывает его
пересечением полученной сетки snapshot с прогоном нагрузки:
`resource_binding.mode` равен `run_intersection`. Запас, выходящий за границы
прогона, обрезается при связывании; это видно по сравнению
`snapshot_from_epoch_ms`/`snapshot_to_epoch_ms` с
`evaluation_from_epoch_ms`/`evaluation_to_epoch_ms`. Счётчики
`dropped_leading_cells` и `dropped_trailing_cells` сохраняют прежнее значение —
остаток выравнивания неполной ячейки — и обрезанный запас не считают.

## Автошаг

`source-request.v4` отличается от `v3` обязательным полем `window.step_mode`
(`fixed` или `auto`); `window.step_ms` — заявленный шаг.
[Пример `v4` с автошагом](../contracts/sources/v4/examples/valid/auto-window-auto-step.json).

- `fixed` совпадает с `v3`: запас и допуск простоя кратны шагу, шаг не меняется,
  превышение `серии × ячейки <= 1 500 000` после выборки даёт
  `SOURCE_SNAPSHOT_LIMIT_EXCEEDED`.
- `auto`: заявленный шаг остаётся, пока `рядов × ячеек <= 1 500 000`. Иначе
  выбирается первый подходящий целый шаг в секундах не мельче заявленного и не
  крупнее 60 с (для авто-окна ещё и не крупнее `max_idle_gap_ms`); в явном окне
  годятся только шаги, которые делят окно нацело. Расчёт идёт до первого
  запроса к источнику. В режиме `auto` кратность `margin_ms` и `max_idle_gap_ms`
  шагу не требуется (`max_idle_gap_ms` не меньше заявленного шага и составляет целое число секунд), а число
  ячеек явного окна на заявленном шаге не ограничено 100 000: подходящий шаг
  найдёт планировщик.

Нижняя граница шага — интервал опроса источника. Его объявляет профиль полем
`scrape_interval_ms` в `source-connections.v3` (целое число миллисекунд, кратное
1 000, от 1 000 до 3 600 000; у профилей `opensearch` и PostgreSQL поля нет).
Пример: [`autostep-connections.example.json`](../contracts/sources/v1/autostep-connections.example.json).
Для нескольких профилей берётся максимум по профилям с запросами. Профили
`source-connections.v1` и `v2` поля не принимают.

Отказы режима `auto` приходят до сети как диагноз задания (в CLI печатаются как
`КОД: подробность`):

- `AUTO_STEP_UNSATISFIABLE` — ни один шаг от заявленного до 60 с не вмещает
  выбор в 1 500 000 ячеек; текст называет наибольшее допустимое число рядов для
  этого периода (ряды не урезаются);
- `AUTO_STEP_SCRAPE_INTERVAL_REQUIRED` — у профиля с запросами нет
  `scrape_interval_ms`;
- `AUTO_STEP_BELOW_SCRAPE_INTERVAL` — заявленный шаг меньше интервала опроса;
  шаг молча не поднимается, задайте его не меньше интервала или используйте
  `fixed`;
- `AUTO_STEP_QUERY_NOT_INTERVAL_BOUND` — шаг огрубляется, а выражение содержит
  окно, отличное от `[$__interval]` (для InfluxQL — подзапрос или
  `GROUP BY time(...)` не по `$__interval`), поэтому его смысл изменился бы;
- `AUTO_STEP_RULE_IN_CELLS` — шаг огрубляется, а правило ряда объявлено в
  ячейках (`min_consecutive_cells`, в том числе `1`): число ячеек зависит от
  шага, поэтому смысл правила изменился бы. Объявите правило длительностью
  `min_consecutive_span_ms` или используйте `fixed`;
- `AUTO_STEP_AGGREGATION_MISMATCH` — шаг огрубляется, а агрегация ряда не
  переживает огрубление для оператора его правила: `gt` требует
  `interval_max`, `lt` требует `interval_min`; ряд с правилами `gt` и `lt`
  одновременно не проходит. Правила `diagnostic` проверяются так же, как `sla`.
  Объявите подходящую агрегацию (и выражение с `max_over_time` или
  `min_over_time`) или используйте `fixed`.

Проверки выражений и правил выполняются только при фактическом огрублении;
если заявленный шаг укладывается в бюджет, он остаётся и ничего не
проверяется. Приложение не разбирает PromQL: проверка range-селекторов
консервативна (подзапрос `[$__interval:15s]` и любой `[5m]` отказывают при
огрублении), корректность выражения остаётся заявлением автора профиля.

Для `v4` evidence `source_summary` публикует `step_origin` (`explicit` для
`fixed`, `auto` для `auto`), а при `auto` ещё `requested_step_ms`,
`series_count`, `cell_budget`, `cells_per_series` и, если применённый шаг
больше заявленного, массив `warnings` с предупреждением `RESOLUTION_REDUCED`
(заявленный и применённый шаг, идентификатор и агрегация каждого ряда; при
нескольких профилях идентификатор квалифицирован профилем, как в снимке).
Поля входят в `source_acquisition_sha256`: один и тот же снимок под `fixed` и
`auto` получает разные `analysis_id`. Сводки `v1`, `v2` и `v3` этих полей не
содержат.

### Правила по длительности

Правило в `source-connections.v3` объявляется ровно одним из двух полей:
`min_consecutive_cells` (число ячеек снимка) или `min_consecutive_span_ms`
(длительность, целое число миллисекунд от 1 до 86 400 000). Длительность
переводится в ячейки при сборке как `ceil(min_consecutive_span_ms / step)` при
применённом шаге: при шаге 20 с правило в 60 000 мс занимает 3 ячейки, а в
61 000 мс — 4. Снимок всегда несёт правило в ячейках, поэтому разные применённые
шаги дают разные хэши снимка и конфигурации ресурсов. `source-connections.v1` и
`v2` поле `min_consecutive_span_ms` не принимают.

Для профилей с правилами по длительности `source_summary` содержит массив
`rule_spans` (`rule_id`, `declared_span_ms`, `step_ms`, `cells`,
`effective_span_ms`); он входит в `source_acquisition_sha256`. У профилей без
таких правил поля нет, и их сводки не меняются.

При огрублении правило проходит проверку, только если агрегация ряда сохраняет
пик для его оператора:

| Оператор правила | Допустимая агрегация ряда |
| --- | --- |
| `gt` | `interval_max` |
| `lt` | `interval_min` |

Правило `sla` и `diagnostic` проверяются одинаково. Ряды, у которых нет правила
с подходящим пиковым агрегатом, перечисляются в предупреждении
`RESOLUTION_REDUCED`. Агрегация `interval_max` считает интервалы с пиком, а не
время превышения порога: три соседних интервала по 20 с с короткими пиками дают
правило протяжённостью 60 с, хотя порог мог быть превышен лишь на доли секунды.
Читайте правила SLA на таких рядах с этой оговоркой. Гарантия направлена в одну
сторону: непрерывное превышение порога (для `lt` — нахождение ниже порога)
длительностью не меньше заявленной затрагивает не меньше `ceil(span / step)`
подряд идущих интервалов при любом применённом шаге, поэтому не теряется;
обратное неверно (разрозненные короткие пики соседних интервалов дают
срабатывание), и результат правила на разных шагах может отличаться. Заявление
«агрегация — `interval_max`» приложение не сверяет с выражением запроса.

### Событийные неотрицательные приращения

Ряд приращений счётчика событий (OOM, рестарты) можно объявить полем запроса
`non_negative_events: true` (только `source-connections.v3`). Для такого ряда и
правила `gt` с порогом не выше 0 огрубление допускается при агрегации
`interval_mean`, `interval_rate` и `interval_max`: для неотрицательных значений
каждая из них положительна тогда и только тогда, когда в интервале было
положительное значение. Правило такого ряда может быть объявлено в ячейках с
`min_consecutive_cells: 1`, иначе действует `AUTO_STEP_RULE_IN_CELLS`. Пример:
ряд `oom-kills` и правило `oom-any` в
[`autostep-connections.example.json`](../contracts/sources/v1/autostep-connections.example.json).

Границы исключения: `gt` с порогом выше 0 («рестартов больше 5») и `lt`
исключение не получают и отказывают `AUTO_STEP_AGGREGATION_MISMATCH`, как ряды
без объявления. Профиль с таким рядом, правилом `gt` с порогом не выше 0 и
агрегацией `interval_min` отвергается при чтении конфигурации при любом шаге
(`SOURCE_CONFIG_INVALID`): минимум может обнулить интервал с событием. Ряд с
`interval_mean` или `interval_rate` попадает в `RESOLUTION_REDUCED`.

Объявление — заявление автора профиля, а не доказанное свойство. Проверка
данных санитарная: отрицательное значение в ответе переводит запрос в `FAILED`
с причиной `NEGATIVE_EVENT_VALUE` (ряд становится пропусками), но компенсацию
положительного и отрицательного приращений внутри уже свёрнутого значения она не
видит. Безопасность держится на запросе автора (`increase`, `rate`). Сброс
счётчика при пересоздании пода и потеря первого приращения нового ряда вне
гарантии (ADR 0018). Имя метрики для распознавания событийного ряда не
используется.

При огрублении среднее (`interval_mean`) и скорость сглаживают короткие
всплески: ряд без пикового агрегата (`interval_max` для порога сверху,
`interval_min` для порога снизу) может не показать кратковременного нарушения,
о чём и предупреждает `RESOLUTION_REDUCED`. Автошаг выбирает один шаг на весь
выбор профилей; пара плеч для сравнения требует одного общего шага, который
нужно задать явно (режим `fixed`). Онлайн-сбор по-прежнему не комбинируется с
планами capacity, trend и diagnostics.

## Метрики и время

Каждый query задаёт id, expression, metric, unit, entity, role, aggregation и
необязательные ожидаемые labels. Ответ должен содержать ровно одну серию;
несколько серий — ошибка, не автоматическая агрегация. Сохранённые labels —
фактические labels ответа; ожидаемые проверяются как subset. Пустой ответ — missing.

`$__interval` в expression заменяется на step в ms. Для interval_mean обычно
нужен `avg_over_time(metric[$__interval])`, для interval_rate —
`rate(counter[$__interval])`. Корректность выражения и единиц задаёт автор
профиля: приложение не доказывает семантику произвольного PromQL и не конвертирует
проценты/байты/секунды по имени метрики. Правила используют единицы результата.

Для `interval_max` нужен `max_over_time(metric[$__interval])`, для
`interval_min` — `min_over_time(metric[$__interval])`. Метка `aggregation` —
заявление автора профиля: приложение её не проверяет и не выводит из выражения.
Статистики такого ряда (среднее, медиана, `q95`, наклон) — статистики
максимумов (или минимумов) по интервалам, а не самих значений.
`min_consecutive_cells` на таком ряду считает интервалы с пиком (для
`interval_min` — с провалом), а не время превышения порога (или нахождения
ниже него). Ось нагрузки плана capacity по-прежнему требует
`interval_mean`. Снимок с `interval_max` или `interval_min` не откроется в
сборке без этих значений: она отказывает с `UNSUPPORTED_AGGREGATION`, а не
искажает данные.

Запрашиваются правые границы `start+step` ... `end`; ответ на границе `t+step`
относится к snapshot cell `[t,t+step)`. Реальные scrape/rate особенности
источника сохраняются, интерполяции нет. NaN/Inf и gaps не становятся нулями;
duplicate/off-grid timestamps, histograms и неоднозначные labels дают явную ошибку.
Непустые source warnings в первой поставке также отклоняют query (`SOURCE_WARNINGS`):
частично отброшенные источником данные не выдаются за полный ответ.

## Метка плеча

Профиль `source-connections.v3` может объявить необязательное поле `arm`: идентификатор
плеча стенда (например, `A` или `B`), непустой текст до 128 байт без управляющих
символов ([пример](../contracts/sources/v1/arm-connections.example.json)). В `v1` и
`v2` поле неизвестно и даёт `SOURCE_CONFIG_INVALID`, как и у профиля OpenSearch
(у него нет рядов). Запрос профиля с ожидаемой меткой `arm` другого значения
отвергается тем же кодом при чтении файла.

Сбор добавляет `arm` в метки каждого ряда снимка профиля, в том числе пустого и
упавшего (all-null), поэтому снимок целиком принадлежит одному плечу
(правила метки и её эффект на identity - в
[документе о локальном анализе](slice-1-local-analysis.md), абзац «Метка плеча»
раздела «Аппаратные метрики и совместные SLA»).
Значение берётся из профиля, а не из метки запроса: источник может повторить `arm`
в ответе, но другое значение в ответе даёт `LABEL_MISMATCH` (ряд заменяется
пропусками, статус запроса `FAILED`). Метка попадает в `source_summary` профиля
полем `arm` - только у профиля с `arm`.

Один снимок - одно плечо: в одном сборе несколько профилей с рядами должны
объявлять один и тот же `arm`. Профили с разными `arm`, а также профиль с `arm`
вместе с профилем без него отклоняются до первого запроса к источнику кодом
`INVALID_ARM_LABEL` (профиль OpenSearch без рядов в проверке не участвует). Для двух
плеч запускаются два отдельных анализа одной нагрузки.

Влияние на identity: у профиля без `arm` метки рядов, сводка источника и
`identity.json` остаются прежними. У профиля с `arm` в identity появляется
`resource_arm`, а `source_acquisition_sha256` и `analysis_id` меняются (меняются
метки рядов и сводка), поэтому такой анализ не сравнивается с анализом без плеча.

## InfluxDB / InfluxQL

Профиль `influxdb` требует `database` длиной 1..128 UTF-8 bytes. Поддерживается
только InfluxQL: Flux и InfluxDB 3 SQL не входят в эту поставку. Для InfluxDB 2
совместимый endpoint `/query` требует заранее настроенный DBRP mapping, а
`database` содержит имя mapped database/retention policy. Учётная запись БД
должна иметь read-only роль: проверка текста SELECT не заменяет права доступа.

Direct отправляет GET на `{base_url}/query`, Grafana proxy — на
`{base_url}/api/datasources/proxy/uid/{datasource_uid}/query`; `/write` никогда
не используется. Параметры запроса: `db`, `q`, `epoch=ms`. Expression обязан
содержать `$__start`, `$__end`, `$__interval`; `$__offset` необязателен и равен
`start % step`. Первые три значения заменяются соответственно на start/end/step
с суффиксом `ms`.

Запрос должен быть одним SELECT и возвращать ровно колонки `time`,`value` в
любом порядке. Полю метрики задайте alias `AS "value"`; `time` возвращает сам
InfluxQL. Консервативный validator отклоняет `INTO`, semicolon, comments,
неизвестные placeholders, `fill(...)` с режимом, фабрикующим значения, и
выражения, форму которых нельзя подтвердить без полного SQL parser. Разрешены
только сохраняющие пропуски `fill(null)` и `fill(none)`; `fill(0)`,
`fill(previous)`, `fill(linear)` и числовые аргументы отклоняются, потому что
их результат неотличим от наблюдений. Один результат с нулём или одной series
допустим; несколько statements/series, partial response, messages/errors,
неверные tags, duplicate/off-grid timestamps отклоняются.

InfluxQL timestamp является левой границей snapshot cell. Пример с cells
`[1000,2000)`, `[2000,3000)`, `[3000,4000)` принимает timestamps `1000`, `2000`,
`3000`. Отсутствующая точка и JSON `null` остаются gap (`null`), не становятся
нулём и не интерполируются.

## OpenSearch error context

[Пример профиля](../contracts/sources/v1/opensearch-connections.example.json)
использует `source_kind:opensearch`, только `transport:direct` и mapping
`opensearch`. Поля `queries`, `rules`, `database`, `datasource_uid` запрещены.
Задайте ограниченный index pattern: wildcard-only `*`, `**`, `_all` запрещены.
Timestamp должен быть date, service/error type — single-valued keyword.
Ошибкой считается документ с существующим `error_type_field`.

Backend отправляет фиксированный POST `{base_url}/{indices}/_search`,
без пользовательского Query DSL. Окно `[start,end)`, timeline имеет step ms;
сохраняются total, errors/minute, service/type groups, first/last и samples.
Limits: до 16 index patterns, 200 groups (default 50), 5 samples/group
(default 2), message до 65536 UTF-8 bytes (default 4096). `samples_per_group:0`
отключает сообщения. Учётной записи нужны только права чтения этих индексов.

Timeout, failed shards, lower-bound totals, truncated/approximate terms и
несогласованные суммы дают `PARTIAL` с причинами. Malformed response даёт
`FAILED`, а не нулевые ошибки. Эти факты не меняют бизнес-SLA и не являются
метриками железа или новой корреляцией. Для context-only analysis
`resource-snapshot.json` не создаётся.

Сохранённый `opensearch-errors.json` привязан к SHA-256 load input. Его можно
скачать в UI и импортировать без connections через поле OpenSearch context:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --source-context opensearch-errors.json --data-dir data
```

Допустимы одновременно offline `--resources` и `--correlation`; online
`--source` с manual context запрещён. Import повторно проверяет counts,
rates, coverage, grid, URLs и load binding, а не доверяет готовым итогам.
Sample messages выводятся как текст, document URLs — внешние ссылки;
raw ответы и сообщения могут содержать чувствительные данные стенда.

Private API принимает file part `source_context` в `POST /api/jobs`;
`GET /api/runs/{runId}/analyses/{analysisId}/source-context` скачивает только
существующий manifest-validated JSON attachment. Context ограничен 16 MiB;
Для нескольких контекстов используйте повторные file parts `source_context`
(до16, суммарно32 MiB); каждому нужен отдельный profile_id.

## Несколько источников

UI позволяет выбрать до16 HTTP-профилей на общей сетке окна выборки.
CLI принимает [source-request.v2](../contracts/sources/v1/multiple-request.example.json)
и `v3` с `profile_ids`; прежний v1 для одного профиля остаётся совместимым.
Порядок выбора не влияет на нормализованные данные. Series/rule IDs становятся
`profileId/queryId` и `profileId/ruleId`; `%` и `/` в profileId экранируются
как `%25` и `%2F`. SLA-ссылки меняются вместе с series IDs, единицы — нет.
Общий cap снимка: 1 024 series, 1 500 000 cells, 256 rules; превышение отклоняется
до сети. Фактически число series ограничено конфигурацией: до 64 queries на
профиль и до 16 профилей, то есть не более 1 024 series (равно общему cap снимка).
Governor остаётся общим по origin, запросы выполняются последовательно.
Файл connections не больше 1 MiB: для платформенных профилей с тяжёлыми
выражениями (около 1 KB на запрос) этот предел достигается примерно на 950
запросах, то есть раньше предела 1 024 series.

Профили для платформенных сигналов OpenShift (CPU и память к limit, OOM,
рестарты, троттлинг, недоступные реплики, перекос по подам) выпускает генератор
`python -m tools.platform_profiles`, а не пишутся вручную; контракт меток,
правила и ограничения описаны в [документе о пакетах метрик OpenShift](platform-metric-packs.md).
Если в конфигурации генератора задан `scrape_interval_ms`, он выпускает
`source-connections.v3` с этим полем в каждом профиле, и автошаг `auto` работает
([пример](../contracts/sources/v1/platform-openshift-autostep-connections.example.json)).
Без поля выпускается `v1`, автошаг ему отказывает
(`AUTO_STEP_SCRAPE_INTERVAL_REQUIRED`): запускайте с `step_mode: fixed`.
Выражения с подзапросом `[$__interval:15s]` не переживают огрубление шага
(`AUTO_STEP_QUERY_NOT_INTERVAL_BOUND`), поэтому `auto` для платформенных
профилей годится, пока заявленный шаг укладывается в бюджет ячеек.

Несколько OpenSearch contexts сохраняются как `opensearch-errors-N.json`,
отсортированные по profile_id. В UI скачивается каждый файл отдельно;
private API использует `/source-context/{index}` с index1..16.
Один context сохраняет прежние имя файла и download route. Для offline replay:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --resources resource-snapshot.json --source-context opensearch-errors-1.json --source-context opensearch-errors-2.json --data-dir data
```

Повторный profile_id, неверный load hash и превышение limits отклоняются.
Суммарный cap raw responses —64 MiB, contexts —32 MiB. Пропуск артефакта
из-за cap отражается в source summary, отсутствующий источник не становится
нулевой метрикой или нулевым числом ошибок.

## PostgreSQL pre/post

[Пример PostgreSQL-профиля](../contracts/sources/v1/postgresql-connections.example.json)
использует `source-connections.v2`; HTTP-профили можно добавить в тот же массив
connections. Нужны отдельная read-only роль и environment-переменные credentials.
Роль capture не должна выполнять нагрузку: её userid исключается из statement
дельт и записывается в configuration как `lt_verdict.excluded_statement_userid`.
Для чужих statement IDs нужны эффективные права `pg_read_all_stats`, а не
только membership без наследования. Проверьте от имени capture-роли:
`SELECT pg_has_role(current_user, 'pg_read_all_stats', 'USAGE');` — ожидается true.
При отсутствии прав PostgreSQL скрывает query IDs, точные дельты недоступны.
По умолчанию TLS `verify-full`; `allow_insecure:true` отключает TLS только для
явно выбранного доверенного тестового окружения.

Снимите pre **до** нагрузки, post — **после**, передав исходный pre файл:

```powershell
.\build\install\ltv\bin\ltv.bat source pre --connections connections.json --profile pg > pre.json
# Выполните нагрузочный тест.
.\build\install\ltv\bin\ltv.bat source post --connections connections.json --profile pg --pre pre.json --pg-profile-html report.html > post.json
.\build\install\ltv\bin\ltv.bat analyze input.jtl --postgres-pre pre.json --postgres-post post.json --data-dir data
```

В PowerShell используйте UTF-8 перенаправление вывода (старый Windows PowerShell
может записать UTF-16); UI скачивает phase JSON без перекодирования чисел.
UI предлагает отдельные capture pre/post и загрузку phase-файлов. Analyze
не снимает pre/post автоматически. Пара совместима с HTTP online selection,
resource snapshot и OpenSearch contexts в рамках их прежних ограничений.
Для offline replay подключения к PostgreSQL не нужны.

Allowlist таблиц задаёт schema/table/columns/key. Уникальный ненулевой key
позволяет считать inserted/deleted/updated; без key доступна только разница
количества строк. Сравниваются также выбранные настройки БД и шесть прямых
pg_stat_statements counters. Reset, eviction, новые/пропавшие statements,
неполный снимок или неверная временная/hash-привязка отмечаются явно.
Время capture сравнивается с фактическим окном parsed load, не с введённым
пользователем диапазоном. Отсутствующий pre не подменяется нулями.

Limits: phase16 MiB, до16 tables,10000 rows/table,1 MiB/table,64 KiB/cell;
statements до10000 rows; connect/query/socket30s, lock5s. SQL фиксирован,
пользовательские SQL/JDBC URL/properties не принимаются.
Для supplementary pg_profile report задайте `pg_profile` с целыми положительными
`server_id`, `start_sample_id`, `end_sample_id` (start < end). Connector не вызывает
sample/reset/management. Отсутствующее расширение или неподдержанный report
дают DEGRADED и не отменяют пригодные table facts.

HTML максимум4 MiB, UTF-8, только download; `--pg-profile-html FILE` при analyze
импортирует его как attachment. Он не исполняется внутри приложения и не служит
источником SQL-статистики. При capture этот флаг задаёт create-new output path;
если report недоступен, файл не создаётся, причина остаётся в phase JSON.
Не открывайте недоверенный HTML без проверки вне origin приложения.

Private API: `POST /api/sources/postgresql/pre` или `/post`, multipart
`profile_id` и optional `pre` file для post. Обязателен Content-Length,
request до17 MiB; допускается один capture одновременно, иначе409 BUSY.
Ответ `postgres-capture.v1`: `phase_json` — строка с точным JSON,
`pg_profile_html_base64` — строка или null. В `POST /api/jobs` передаются
`postgres_pre`, `postgres_post`, `pg_profile_html`. Fixed download routes:
`/postgres-pre`, `/postgres-post`, `/postgres-context`, `/pg-profile` под analysis.
HTML выдаётся application/octet-stream + attachment, JSON — application/json.

Синтетическая проверка 2026-09-06: PostgreSQL 16.15 + pg_stat_statements 1.10,
pg_profile 4.8 (read-only report, CLI capture и SHA-256) прошла.
PostgreSQL 15 и TLS в этом прогоне не проверялись; это не приёмка боевого стенда.
[ADR 0008](../adr/0008-postgresql-pre-post-capture.md).

## Credentials и limits

Auth по умолчанию `{"type":"none"}`. Bearer:
`{"type":"bearer","token_env":"LTV_METRICS_TOKEN"}`; Basic:
`{"type":"basic","username_env":"LTV_METRICS_USER","password_env":"LTV_METRICS_PASSWORD"}`.
Influx token: `{"type":"token","token_env":"LTV_INFLUX_TOKEN"}`; backend
отправляет его как `Authorization: Token ...`.
Значения задаются environment процесса backend, не в JSON/UI. TLS verification
всегда включена (клиентский сертификат и собственный CA описаны в разделе
«mTLS» ниже). Credentials по HTTP требуют `allow_insecure_http:true`;
используйте HTTPS вне доверенного локального теста.

Governor defaults: `requests_per_second:0.5`, `burst:1`, `max_concurrent:1`,
`timeout_ms:30000`, `max_attempts:3`, `honor_retry_after:true`.
`max_requests_per_run` необязателен, скрытого request cap нет.
Один origin (scheme/host/effective port) разделяет strictest budget всех
настроенных профилей, jobs и retries. Первый запрос после старта ждёт interval.

Config <= 1 MiB/16 profiles/64 queries; source request <= 16 KiB; response
<= 16 MiB, acquisition raw total <= 64 MiB. Snapshot <= 32 MiB,
100000 points/series, 1500000 cells; остальные limits проверяет общий validator.
Timeout/retries/body read отменяются вместе с job. HTTP error bodies не публикуются.

## mTLS

Если Grafana, Prometheus, VictoriaMetrics, InfluxDB или OpenSearch принимают
подключения только с клиентским сертификатом, добавьте в профиль необязательный
объект `tls`. Он допустим в `source-connections.v1`, `v2` и `v3` для профилей
`direct` и `grafana_proxy` (в том числе для рендера панели Grafana) и только при
`base_url` со схемой `https`. У профиля без `tls` ничего не меняется. Решение:
[ADR 0025](../adr/0025-mtls-on-source-connector.md), пример профиля:
[mtls-connections.example.json](../contracts/sources/v1/mtls-connections.example.json).

```json
"tls": {
  "ca_file": "/etc/ltv/tls/ca.pem",
  "client_keystore_file": "/etc/ltv/tls/client.p12",
  "client_keystore_password_env": "LTV_GRAFANA_MTLS_PASSWORD"
}
```

| Поле | Смысл |
| --- | --- |
| `ca_file` | PEM-файл с сертификатами CA (от 1 до 32), которым доверяет этот профиль. Заменяет доверие JVM по умолчанию для профиля: нужен корень CA, которым подписан сертификат **сервера** (для публичного CA положите его корень в файл). Без `ca_file` действует доверие JVM по умолчанию |
| `client_keystore_file` | файл PKCS12 с клиентским ключом и цепочкой сертификатов, которые профиль предъявляет серверу |
| `client_keystore_password_env` | имя переменной окружения с паролем PKCS12; обязательно вместе с `client_keystore_file` |

Правила: объект не пустой; пути только абсолютные (на Windows `C:/ltv/tls/ca.pem`
или `C:\\ltv\\tls\\ca.pem` в JSON), без `~`, `.` и `..`, `file:` и сетевых
UNC-путей (`\\server\share`); пароль в профиль не пишется. `tls` и `auth`
независимы: можно предъявлять сертификат и отправлять токен (например, Grafana
за mTLS-прокси с токеном сервисной учётной записи). `tls` не поддерживается у
профилей `postgresql` (у них свой `sslmode`, см. раздел «PostgreSQL pre/post»).

### Подготовка файлов

Клиентский ключ принимается только в формате PKCS12; PEM-ключи (PKCS#1, PKCS#8,
SEC1), JKS и JCEKS не поддерживаются. Если стенд выдал ключ и сертификат в PEM,
соберите PKCS12 одной командой (пароль берётся из окружения, а не из командной
строки):

```text
openssl pkcs12 -export -inkey tls.key -in tls.crt -certfile ca.pem -out client.p12 -passout env:LTV_GRAFANA_MTLS_PASSWORD
```

Пароль PKCS12 обязателен. Передайте его процессу backend через окружение, а не
через `JAVA_OPTS` или командную строку:

```powershell
$env:LTV_GRAFANA_MTLS_PASSWORD = Read-Host -MaskInput "PKCS12 password"
.\build\install\ltv\bin\ltv.bat ui --connections connections.json
```

Файлы читаются один раз при первом запросе профиля и не больше предела:
`ca_file` и PKCS12 до 1 МиБ, в PKCS12 ровно одна запись с ключом, цепочка до 8
сертификатов. Имя узла проверяется: `host` из `base_url` должен входить в SAN
сертификата сервера (для IP-адреса нужен SAN типа IP).

### Что гарантируется

- Проверка цепочки сервера и имени узла включена всегда; режима «доверять всем»
  и отключения проверки имени нет. Если процесс запущен с
  `-Djdk.internal.httpclient.disableHostnameVerification` (пустое значение или
  `true`), профиль с `tls` отказывает кодом `SOURCE_TLS_CONFIG_INVALID`.
- Протоколы: только TLS 1.3 и TLS 1.2. Отзыв сертификатов (CRL, OCSP) не
  проверяется.
- У каждого профиля с `tls` свой `SSLContext` и свои соединения: два источника
  могут предъявлять разные сертификаты в одном процессе. Профиль с `tls` не
  использует глобальные `javax.net.ssl.keyStore*` и (при заданном `ca_file`)
  `trustStore*`. Лимиты запросов по-прежнему общие на origin.
- Пароль, пути и содержимое ключей не попадают в результат, `source_summary`,
  `source-acquisition.json`, ответы API, evidence для ИИ и сообщения об ошибках:
  наружу выходит только код причины. Настройки `tls` в хэши анализа не входят.
- Профили без `tls` продолжают работать как раньше, в том числе с глобальными
  `javax.net.ssl.*` из `JAVA_OPTS`. Этот обход годится только как временный, для
  одного источника на процесс: пароль в командной строке виден в списке процессов.

### Права на файлы и ротация

Продукт права на файлы не проверяет. Храните PKCS12 так, чтобы его читал только
пользователь backend (`chmod 600` и каталог `700` на Linux, ACL на Windows); пароль
защищает ключ, если файл всё же утёк. Ключ не пишется в артефакты и временные
файлы.

Сертификаты и CA читаются один раз; после замены файлов перезапустите backend
(так же, как после правки профилей). Неудачная сборка не запоминается: если файл
был не на месте, достаточно положить его и повторить анализ. Срок клиентского
сертификата проверяется локально при каждом запросе: истёкший срок даёт
`SOURCE_TLS_CLIENT_CERT_EXPIRED` без обращения к серверу. Предупреждения о скором
истечении нет, срок проверяйте сами:

```text
keytool -list -v -storetype PKCS12 -keystore client.p12
```

### Коды причин и типовые решения

Код виден в `source_summary` (поле `reason` запроса), а `analysis_coverage`
получает общий `SOURCE_ACQUISITION_FAILED` или `SOURCE_ACQUISITION_PARTIAL`.
Отказы `SOURCE_TLS_*` не повторяются (повтор не исправит сертификат).

| Код | Что значит | Что проверить |
| --- | --- | --- |
| `SOURCE_CONFIG_INVALID` | форма профиля: пустой `tls`, `tls` при `http`, относительный или UNC-путь, пароль без keystore и наоборот | профиль; ошибка возникает при чтении файла connections |
| `SOURCE_TLS_CONFIG_INVALID` | материал: файл не найден или не читается, больше предела, не PKCS12 (например JKS), неверный пароль, переменная не задана или пуста, нет или больше одной записи с ключом, в `ca_file` нет сертификатов, срок клиентского сертификата ещё не наступил, отключена проверка имени | пути, права, переменная окружения процесса backend, формат файла, часы хоста |
| `SOURCE_TLS_CLIENT_CERT_EXPIRED` | срок сертификата клиента или его цепочки истёк | выпустить новый сертификат, перезапустить backend |
| `SOURCE_TLS_HANDSHAKE_FAILED` | ошибка TLS при соединении: сервер не доверен (его CA нет в `ca_file`), имя узла не совпало с SAN, сервер отверг клиентский сертификат, несовместимый протокол | `openssl s_client -connect host:443 -servername host -CAfile ca.pem -cert tls.crt -key tls.key`; `base_url` должен совпадать с SAN |
| `SOURCE_HTTP_STATUS` (HTTP 400) | сервер завершил TLS и ответил ошибкой: например, nginx с `ssl_verify_client on` отвечает 400 `No required SSL certificate was sent`, если клиентского сертификата нет или он выпущен другим CA | `tls.client_keystore_file` и CA, которому доверяет прокси |

Оговорка: при TLS 1.3 сервер отвергает отсутствующий или чужой клиентский
сертификат уже после завершения рукопожатия на стороне клиента и просто закрывает
соединение. На JDK 21 это проверено: такая ошибка приходит как обычный сбой
соединения, `SOURCE_HTTP_ERROR` (с повторами, как раньше), а не как
`SOURCE_TLS_HANDSHAKE_FAILED` (определять причину по тексту ошибок нельзя).
Если вместо TLS-кода вы видите
`SOURCE_HTTP_ERROR`, проверьте клиентский сертификат и CA сервера командой
`openssl s_client` выше. Русские тексты этих кодов в интерфейсе пока не
добавлены: показывается код.

### Что важно знать про Grafana

- Ссылка на панель Grafana открывается в **браузере пользователя**, а не в
  backend: клиентский сертификат нужно установить в браузер отдельно.
- На боевом стенде mTLS, как правило, завершает обратный прокси или ingress
  перед Grafana (или перед Prometheus при `transport: direct`). `base_url` должен
  указывать на этот узел, а сертификат клиента должен быть выпущен CA, которому
  доверяет прокси.
- Локальная проверка без боевого узла: необязательный стенд
  `tools/demo-stand/docker-compose.mtls.yml` (nginx с обязательным клиентским
  сертификатом перед Grafana демо-стенда, сертификаты создаются скриптом и в git
  не попадают), см. [README стенда](../../tools/demo-stand/README.md).

## Результат и offline replay

`source_summary` evidence показывает COMPLETE/PARTIAL/FAILED и статусы запросов,
request_count/retries/throttle_wait_ms/cap_exceeded. Те же состояния отражаются в
`analysis_coverage`: `PARTIAL` и `FAILED` дают `SOURCE_ACQUISITION_PARTIAL` и
`SOURCE_ACQUISITION_FAILED`, исчерпанный request budget —
`SOURCE_REQUEST_CAP_EXCEEDED`, поэтому `analysis_coverage.status` становится
`INCOMPLETE`. Failed query сохраняется
all-null серией: соответствующий ресурсный SLA не получает ложный PASS.
Бизнес-анализ доступен независимо от отсутствующих метрик.
Если сумма нормализованных серий превышает snapshot byte limit, данные заменяются
проверенным all-null snapshot с `SOURCE_SNAPSHOT_LIMIT_EXCEEDED`; успешные raw
ответы остаются в артефактах. Полный анализ нагрузки при этом не теряется.

Analysis directory атомарно содержит `resource-snapshot.json`,
`source-acquisition.json` и успешные `source-response-N.json` с manifest hashes.
Profiles/credentials там не сохраняются. Источник данных всё равно считается
недоверенным: labels/raw metrics могут содержать служебные данные стенда.

Скачайте snapshot из сохранённого анализа либо возьмите файл из analysis directory:

```powershell
.\build\install\ltv\bin\ltv.bat analyze input.jtl --resources saved-resource-snapshot.json --data-dir data
```

Это offline анализ с теми же metric/SLA facts, но отдельной identity без acquisition
provenance. Открытие старого результата также не выполняет HTTP requests.
Тот же ручной replay применяется к сохранённому InfluxDB snapshot: подключение,
DBRP mapping и token при повторном анализе не нужны.
Для correlation plan сначала получите snapshot hash, затем запускайте offline
`--resources ... --correlation ...`: online acquisition и correlation plan в одном
запросе не смешиваются, потому что план привязан к конкретному snapshot.

Private API: `GET /api/sources` выдаёт id/source_kind/transport и, только у профилей,
объявивших плечо (`arm`, `source-connections.v3`), поле `arm`;
`POST /api/jobs` принимает optional file part `source_request` вместо
`resource_snapshot`; `GET /api/runs/{runId}/analyses/{analysisId}/resource-snapshot`
скачивает существующий проверенный артефакт. Existing Origin/CSRF/size guards действуют.

Решение: [ADR 0007](../adr/0007-opt-in-online-sources.md); авто-окно и
распознанный период — [ADR 0012](../adr/0012-auto-window-recognized-period.md).

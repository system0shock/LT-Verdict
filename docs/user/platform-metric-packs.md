# Пакеты метрик OpenShift: генератор профилей источника

Профили онлайн-источника для платформенных сигналов (CPU, память, OOM,
рестарты, троттлинг, недоступные реплики, перекос по подам) не пишутся руками:
их выпускает генератор `tools/platform_profiles.py` из единого каталога шаблонов
PromQL `tools/platform_profile_templates.py`. Свёртка pod → сервис выполняется
запросом на стороне источника. Ядро LT Verdict про OpenShift ничего не знает: оно
получает по одному ряду на пару (сервис, сигнал), как и для любого другого
`query_range`.

> **Не проверено на стенде.** Шаблоны проверены на синтетических рядах
> (`promtool test rules`) и тестовыми HTTP-серверами. Метки экспортёров
> реального OpenShift могут отличаться (раздел «Что не проверено»): перед
> первым боевым прогоном сверьте семейства метрик ниже со своим источником.

## Контракт меток

Шаблоны предполагают следующие семейства метрик. Это контракт между профилем и
источником.

| Семейство | Обязательные метки | Откуда в OpenShift | Используется в сигналах |
| --- | --- | --- | --- |
| `namespace_workload_pod:kube_pod_owner:relabel` (значение 1) | `namespace`, `pod`, `workload` | правило платформенного мониторинга | все: отображение pod → сервис |
| `node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate` | `namespace`, `pod`, `container` | правило платформенного мониторинга | `cpu_limit_ratio`, `pod_imbalance` |
| `container_memory_working_set_bytes` | `namespace`, `pod`, `container` | cAdvisor (kubelet) | `memory_limit_ratio`, живость для `oom` |
| `kube_pod_container_resource_limits` | `namespace`, `pod`, `container`, `resource` (`cpu`, `memory`) | kube-state-metrics v2 | оба отношения к limit |
| `kube_pod_container_info` | `namespace`, `pod`, `container` | kube-state-metrics v2 | список ожидаемых контейнеров |
| `container_oom_events_total` | `namespace`, `pod`, `container` | cAdvisor | `oom` |
| `kube_pod_container_status_restarts_total` | `namespace`, `pod`, `container` | kube-state-metrics | `restarts` |
| `kube_pod_info` | `namespace`, `pod` | kube-state-metrics | живость для `restarts` |
| `container_cpu_cfs_throttled_periods_total`, `container_cpu_cfs_periods_total` | `namespace`, `pod`, `container` | cAdvisor | `cpu_throttling` |
| `kube_deployment_spec_replicas`, `kube_deployment_status_replicas_available` | `namespace`, `deployment` | kube-state-metrics | `unavailable_replicas` |
| `jvm_memory_used_bytes`, `jvm_gc_pause_seconds_max`, `jvm_gc_pause_seconds_sum`, `jvm_threads_live_threads`, `process_cpu_usage`, `hikaricp_connections_active`, `hikaricp_connections_max` | `namespace`, `pod` (и `area`, `id`, `pool` по смыслу) | Micrometer с `ServiceMonitor` | сигналы JVM |

Имя сервиса: значение метки `workload` равно имени Deployment и равно `entity`
ряда профиля (и, при использовании платформенных правил политики, имени сервиса
в каталоге политики). Init-контейнеры и строки cAdvisor с пустым `container` или
`POD` в сигналы не входят.

## Сигналы

| Ключ в конфигурации | `metric` | `unit` | `aggregation` | Свёртка в сервис | Вариант с пиком |
| --- | --- | --- | --- | --- | --- |
| `cpu_limit_ratio` | `openshift_container_cpu_limit_ratio` | `ratio` | `interval_mean` | среднее каждого контейнера за интервал, затем максимум по контейнерам и подам | нет (CPU остаётся средним) |
| `memory_limit_ratio` | `openshift_container_memory_limit_ratio` | `ratio` | `interval_mean` | то же, пик через `max_over_time` | да, `interval_max` |
| `oom` | `openshift_oom` | `events/s` | `interval_rate` | сумма по контейнерам и подам | нет |
| `restarts` | `openshift_restarts` | `events/s` | `interval_rate` | сумма | нет |
| `cpu_throttling` | `openshift_cpu_throttling` | `ratio` | `interval_mean` | доля троттлинга контейнера, максимум по контейнерам | нет |
| `unavailable_replicas` | `openshift_unavailable_replicas` | `count` | `interval_mean` | `spec - available` Deployment | да, `interval_max` |
| `pod_imbalance` | `openshift_pod_imbalance` | `ratio` | `interval_mean` | (max - min) / avg по подам | нет |

Каждая пара (сервис, сигнал) - один запрос с `role: system`,
`entity` = имя сервиса и `labels: {namespace}`. Выражение заканчивается
агрегатом `by (namespace)`, поэтому ответ источника - ровно один ряд (иначе
ядро отклоняет его кодом `AMBIGUOUS_SERIES`).

## Сигналы JVM

Метрики Micrometer (`ServiceMonitor` добавляет метки `namespace` и `pod`):
`jvm_memory_used_bytes` (`area`, `id`), `jvm_gc_pause_seconds_max`,
`jvm_gc_pause_seconds_sum`, `jvm_threads_live_threads`, `process_cpu_usage`,
`hikaricp_connections_active`, `hikaricp_connections_max` (`pool`). Свёртка в
сервис - худший под: интервальная агрегация каждого пода, затем максимум по
подам; среднее и разброс по подам относятся к pod-view, отдельных имён не
вводится. Имена сигналов совпадают с пакетом `jvm`.

| Ключ в конфигурации | `metric` | `unit` | `aggregation` | Что считается | Вариант с пиком |
| --- | --- | --- | --- | --- | --- |
| `jvm_heap_used` | `jvm_heap_used` | `bytes` | `interval_mean` | куча (`area="heap"`), сумма по пулам пода | да, `interval_max` |
| `jvm_old_gen_used` | `jvm_old_gen_used` | `bytes` | `interval_mean` | пулы старого поколения (`Old Gen`, `Tenured Gen`) | да, `interval_max` |
| `jvm_non_heap_used` | `jvm_non_heap_used` | `bytes` | `interval_mean` | вне кучи (`area="nonheap"`) | да, `interval_max` |
| `jvm_thread_count` | `jvm_thread_count` | `count` | `interval_mean` | живые потоки | нет |
| `jvm_process_cpu` | `jvm_process_cpu` | `ratio` | `interval_mean` | `process_cpu_usage` | нет |
| `jvm_gc_pause` | `jvm_gc_pause` | `s` | `interval_max` | максимум значений gauge `jvm_gc_pause_seconds_max` за интервал | только пиковый режим |
| `jvm_gc_time` | `jvm_gc_time` | `ratio` | `interval_rate` | `rate` суммарного времени пауз, худший под | нет |
| `jvm_pool_saturation` | `jvm_pool_saturation` | `ratio` | `interval_mean` | `active / max` по каждому пулу, худший пул худшего пода (пул без `max` даёт пропуск) | да, `interval_max` |

`jvm_gc_pause` без `peak_aggregation` генератор не выпускает (отказ
`needs interval_max`): выдать максимум под меткой среднего нельзя. У сигналов
JVM (кроме пулов соединений) нет защиты полноты: потеря ряда у одного пода даёт
максимум по остальным подам, а не пропуск (защита есть только у отношений к
limit и пулов соединений). Суммы по `id` пулов памяти и по сериям GC
складываются по поду: потеря одной серии даёт заниженную сумму, а несколько
серий скрейпа одного пода (например, разные `job` или `instance`) удваивают
значение; ограничивайте источник одним скрейпом приложения, полноту суммы
профиль не проверяет. `jvm_gc_pause_seconds_max` в Micrometer - затухающий
gauge: максимум за интервал - это максимум опрошенных значений gauge, а не
обязательно самая длинная пауза интервала (пауза может перенестись в соседний
интервал или пройти между опросами). `jvm_gc_time` на
`rate(...[$__interval])` подчиняется тому же правилу шага, что события
OpenShift (шаг не меньше удвоенного интервала опроса).

## Правила шаблонов

- **Полнота контейнеров.** Отношения к limit публикуются, только если список
  ожидаемых контейнеров сервиса не пуст (`kube_pod_container_info`, регулярные
  контейнеры, включая сайдкары) и у каждого ожидаемого контейнера (по тройке
  `namespace`, `pod`, `container`, а не по их числу) есть отношение. Потеря ряда
  использования, потеря limit, потеря самого источника списка контейнеров и
  контейнер без объявленного limit дают пропуск, а не максимум остальных. Для
  политики пропуск означает `NO_VERDICT`, а не `PASS`. Контейнер, которого нет в
  списке ожидаемых, но у которого есть отношение, в максимум входит.
- **События и живость.** OOM и рестарты считаются как `rate` счётчика, а при
  отсутствии самого счётчика - как ноль, но только пока независимый от счётчика
  ряд того же источника показывает живые поды сервиса (для `oom` -
  `container_memory_working_set_bytes`, для `restarts` - `kube_pod_info`).
  Мёртвый источник счётчика не превращается в выдуманный ноль, а здоровый
  сервис без единого события не получает `NO_VERDICT`. Если счётчик есть, но
  `rate` по нему пуст (например, виден один образец), ноль тоже не
  подставляется: это пропуск. Граница гарантии: для нулевого значения хватает
  одного живого пода сервиса в независимом источнике. Если у части подов
  источник живости пропал, а у других виден, ноль всё равно появится, и ядро
  этого не заметит; то же для счётчика, у которого часть подов ещё не
  породила событий (отличить это от потери ряда по самому счётчику нельзя).
- **Охват остальных сигналов.** Защита полноты (список ожидаемых элементов)
  есть только у отношений к limit. У `cpu_throttling` максимум берётся по
  оставшимся контейнерам, а у `pod_imbalance` перекос считается по подам,
  у которых есть ряд CPU: потеря ряда у одного контейнера или пода не даёт
  пропуска (при одном оставшемся поде перекос равен нулю). Эти сигналы
  диагностические; не опирайтесь на них как на единственное основание `PASS`.
- **Определение `pod_imbalance`.** Это перекос средней за интервал нагрузки
  по подам: сначала CPU каждого пода усредняется за интервал, затем считается
  `(max - min) / avg` по подам. Он не равен среднему за интервал мгновенных
  перекосов (поды, чередующие нагрузку, дадут нулевой перекос средних).
- **Метка пика не лжёт.** `max_over_time` выпускается только вместе с
  `aggregation: interval_max` (режим `peak_aggregation`). По умолчанию
  генератор выпускает среднее (`avg_over_time`) и метку `interval_mean`.
  Сигнал, который по определению является максимумом, без режима пика не
  выпускается (генератор отказывает).
- **Подзапрос.** `[$__interval:SUB]`, разрешение `subquery_step` (по умолчанию
  `15s`, допустимо от `1s` до `60s`) должно быть не крупнее интервала опроса
  источника: при более грубом разрешении подзапрос берёт меньше точек, чем
  есть в источнике, и короткие пики пропускаются. Стоимость: на каждую ячейку
  шага источник считает порядка `шаг / SUB` внутренних вычислений на запрос,
  а запросов семь на сервис; `1s` при шаге 60 с означает около 60 вычислений
  на ячейку, учитывайте это при выборе `subquery_step`.
- **Шаг и `rate`.** Сигналы на `rate(...[$__interval])` (`oom`, `restarts`,
  `cpu_throttling`) требуют шаг запроса не меньше удвоенного интервала опроса
  источника: при меньшем шаге `rate` не видит двух точек, и ряд превращается в
  пропуски (не в нули). Стандартный интервал опроса OpenShift - 30 секунд, то
  есть шаг от 60 секунд.
- **Версия документа и автошаг.** Без `scrape_interval_ms` в конфигурации
  генератор выпускает `source-connections.v1`, которая поля не принимает:
  режим автошага `auto` такому профилю отказывает
  (`AUTO_STEP_SCRAPE_INTERVAL_REQUIRED`), анализ запускается с
  `step_mode: fixed`. С `scrape_interval_ms` (целые секунды, от 1 000 до
  3 600 000 мс) выпускается `source-connections.v3`, поле записывается в каждый
  профиль и `auto` работает; шаг режима `auto` не превышает 60 с, поэтому при
  интервале опроса больше 60 с `auto` всё равно откажет
  (`AUTO_STEP_BELOW_SCRAPE_INTERVAL`), используйте `fixed`. Если в конфигурации
  указан `request_step_ms` (заявленный шаг запроса, целые секунды до 60 000 мс),
  генератор проверяет её и отказывает до записи файла:
  `PLATFORM_STEP_BELOW_SCRAPE_INTERVAL` (шаг меньше интервала опроса) и
  `PLATFORM_RATE_WINDOW_TOO_SHORT` (сигнал на `rate` и интервал опроса больше
  половины шага: `rate` не увидит двух точек). `request_step_ms` - проверка
  конфигурации, в профиль он не записывается: запрос с другим шагом этой
  проверки не проходит, ядро соотношение шага и интервала опроса для `rate` не
  проверяет (оно требует только шаг не меньше интервала опроса). Независимо от
  `request_step_ms`, при заданном `scrape_interval_ms` генератор отказывает с
  `PLATFORM_SUBQUERY_COARSER_THAN_SCRAPE`, если выбранный сигнал содержит
  подзапрос, а `subquery_step` крупнее интервала опроса.
  Ограничения автошага: выражения с подзапросом `[$__interval:SUB]` не
  переживают огрубление шага (`AUTO_STEP_QUERY_NOT_INTERVAL_BOUND`), то есть
  `auto` работает, пока заявленный шаг укладывается в бюджет 1 500 000 ячеек без
  огрубления (например, 140 рядов на шаге 60 с за 8 часов - 67 тысяч ячеек).
  Правила `legacy_sla_rules` объявлены в ячейках и при огрублении тоже
  препятствуют ему (`AUTO_STEP_RULE_IN_CELLS`; код отказа зависит от того, какая
  из проверок сработает первой).

## Запуск генератора

```powershell
python -m tools.platform_profiles --config fixtures/platform/profile-config.example.json --out connections.json
```

Имена `namespace`, сервисов и `arm` попадают в строковые литералы PromQL,
поэтому допускаются только символы `A-Z a-z 0-9 . _ -` (до 100 символов, начало
с буквы или цифры); иначе генератор отказывает. Генератор также отказывает до
записи файла, если профиль не примет боевой разбор или запрос: квалифицированный
идентификатор `профиль/запрос` длиннее 128 байт (он нужен при выборе нескольких
профилей), выражение длиннее 65 536 байт, `grafana_proxy` без `datasource_uid`
(или `direct` с ним), адрес `http://` с авторизацией без `allow_insecure_http`.

Поля конфигурации (JSON): `base_url`, `namespace`, `services[]`, `signals[]`;
необязательные `arm` (в этом срезе влияет только на префикс `id` профилей),
`transport` (`direct` или `grafana_proxy`), `datasource_uid`, `auth`,
`allow_insecure_http`, `governor`, `subquery_step`, `peak_aggregation`,
`scrape_interval_ms`, `request_step_ms`, `legacy_sla_rules[]`. Примеры:
[конфигурация](../../fixtures/platform/profile-config.example.json),
[результат](../contracts/sources/v1/platform-openshift-connections.example.json),
[результат с пиком](../contracts/sources/v1/platform-openshift-peak-connections.example.json),
[результат с `scrape_interval_ms` (`source-connections.v3`)](../contracts/sources/v1/platform-openshift-autostep-connections.example.json).

Пакеты и пределы:

- один запрос возвращает один ряд, поэтому каждая пара (сервис, сигнал) - один
  запрос; запросы упаковываются в профили `ocp-1`, `ocp-2`, ... не более чем по
  64 запроса, профилей не более 16 (1 024 ряда);
- файл connections не больше 1 MiB; для 20 сервисов и 7 сигналов (140 запросов)
  получается три профиля и файл около 144 KB, предел достигается примерно на 950
  запросах, поэтому генератор проверяет оба предела
  (`PLATFORM_PROFILE_TOO_MANY_QUERIES`, `PLATFORM_PROFILE_TOO_LARGE`);
- `legacy_sla_rules` добавляет в профиль правила SLA по сигналу (базовый SLA по
  CPU и памяти путём правил профиля). Профиль с такими правилами несовместим с
  платформенными правилами политики (`PLATFORM_RULES_CONFLICT`, ADR 0018): для
  политики с `platform_rules` правила в профиль не добавляются.

## Проверка выражений (`promtool`)

Семантика выражений проверяется на синтетических рядах: каждый сценарий в
`tools/platform_promql_scenarios.py` подаёт ряды одному шаблону и фиксирует
точный результат (в том числе «пропуск» для потерянного контейнера, limit или
источника). Тест `tools/test_platform_promql.py` запускает `promtool test rules`
и без `promtool` пропускается. Запуск через docker без установки (YAML
передаётся через stdin, закреплённый тег образа):

```powershell
$env:LTV_PROMTOOL='docker run --rm -i --entrypoint sh prom/prometheus:v3.5.5 -c "cat > /tmp/t.yml; promtool test rules /tmp/t.yml"'
$env:LTV_PROMTOOL_STDIN='1'
python -m unittest discover -s tools -p "test_platform_promql.py" -v
```

`promtool` - только инструмент проверки; в сборку и зависимости продукта он не
входит, в CI тест исполнения пропускается.

## Что не проверено

Каждое допущение проверяется при первой возможности на стенде:

- сервис = метка `workload` правила `namespace_workload_pod:kube_pod_owner:relabel`
  (запрос `count by (workload)` на источнике);
- kube-state-metrics v2: `kube_pod_container_resource_limits{resource=...}` (на
  v1 все отношения к limit пусты);
- `container_oom_events_total` существует; сброс счётчика при пересоздании пода
  не искажает `rate`;
- `kube_pod_container_info` отдаёт все регулярные контейнеры (включая внедрённые
  сайдкары) и не отдаёт init-контейнеры;
- интервал опроса cAdvisor и kube-state (30 секунд по умолчанию) и стоимость
  подзапросов `[$__interval:SUB]` для источника при 140 и более запросах на
  плечо; лимиты `max_requests_per_run` и скорость запросов профиля достаточны;
- запись владельца пода присутствует для каждого живого пода: потеря записи у
  одного живого пода не обнаруживается (граница гарантии ADR 0018, раздел 3);
- виды workload: только Deployment (`unavailable_replicas`);
- метрики Micrometer (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_max`,
  `hikaricp_*`) с метками `namespace` и `pod`, а не JMX exporter с другими
  именами; идентификаторы пулов старого поколения (`G1 Old Gen`, `PS Old Gen`,
  `Tenured Gen`; у ZGC и Shenandoah другие `id`).

Результат `promtool` доказывает семантику выражения на синтетике, а не
соответствие меткам вашего стенда: первые боевые прогоны, скорее всего,
потребуют правки выражений.

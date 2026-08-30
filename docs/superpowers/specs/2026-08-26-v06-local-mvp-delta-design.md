# LT Verdict v0.6 — local-first MVP delta design

**Дата:** 2026-08-26

**Статус:** утверждён пользователем 2026-08-26; границы Slice 0 упрощены
2026-08-27

**Основание:** `lt-verdict-prc-prd-v0.6.md`,
`docs/prc-v0.6-alignment-review.md` и решения пользователя от 2026-08-26

## 1. Назначение и нормативное отношение

Этот документ фиксирует согласованные поправки к PRC/PRD v0.6 перед
переписыванием нормативного PRC, ADR, stage-spec и implementation plan.

PRC/PRD v0.6 принимается как продуктовый baseline. При конфликте по составу
MVP, развёртыванию, источникам данных или порядку пользовательского сценария
решения этого delta design имеют приоритет.

Текущие `docs/stage-1-spec.md` и
`docs/superpowers/plans/2026-08-10-development-plan.md` остаются историческими
материалами v0.5 и не являются исполнимым планом v0.6. Фактического
production-кода, закрытого Phase 0 и milestone evidence в репозитории пока нет.

Эта документационная задача:

- не изменяет production-код или production dependencies;
- не объявляет Phase 0 или MVP завершёнными;
- не изменяет пользовательский незатреканный
  `lt-verdict-prc-prd-v0.6.md`;
- создаёт вход для последующего implementation plan.

## 2. Итоговое решение

MVP — локально запускаемое приложение с обязательным Web UI и сетевым доступом
к внешним источникам. Неопределённость серверного развёртывания не должна
блокировать полезный продукт.

Минимальный runtime:

```text
ltv ui
  -> embedded backend on 127.0.0.1
  -> Web UI
  -> one LT Verdict core
  -> local RunBundle filesystem
  -> rebuildable SQLite index and task state
  -> outbound least-privilege connectors
```

CLI, Web UI, Jenkins integration и exporters вызывают один core. Серверное
приложение, shared catalog, RBAC, object storage и многопользовательская история
не входят в MVP.

Сеть считается доступной всегда. Поэтому MVP не обязан быть offline, но обязан
сохранять ручной файловый fallback и повторный анализ уже созданного RunBundle
без повторного запроса источников.

## 3. Цели MVP

MVP обязан:

1. локально запускаться одной командой и предоставлять Web UI;
2. получать штатные JMeter JTL и Gatling `simulation.log`;
3. запускать существующие Jenkins jobs и автоматически получать архивированный
   результат;
4. собирать raw time series из VictoriaMetrics, Prometheus и InfluxDB;
5. собирать online snapshots PostgreSQL, включая последствия DML и
   `pg_stat_statements`;
6. принимать и анализировать `pg_profile`;
7. анализировать JVM и OpenShift metrics;
8. анализировать ошибки OpenSearch 2.6 за окно прогона;
9. выдавать детерминированный policy verdict отдельно от расширяемого анализа;
10. поддерживать обычный и ступенчатый `capacity_step` режимы;
11. сравнивать текущий прогон с baseline и показывать динамику за N прогонов;
12. формировать JSON, self-contained HTML, AsciiDoc и Confluence-ready output;
13. оставлять Confluence REST publisher как безопасный skeleton;
14. опционально запускать рекомендательный AI analysis через headless
    GigaCode;
15. поставлять GigaCode Skill для адаптации существующих нагрузочных тестов к
    LT Verdict.

## 4. Не-цели MVP

В MVP не входят:

- отдельный central server и server deployment contract;
- multi-user mode, RBAC, SSO и общий каталог команд;
- PostgreSQL как обязательное хранилище самого LT Verdict;
- object storage, distributed queue и microservice decomposition;
- event-level logical decoding или CDC для PostgreSQL;
- автоматический SSH/SCP-забор файлов с произвольных генераторов;
- автоматическая causal inference по OpenSearch;
- code-aware RCA по репозиториям продукта;
- автоматическое изменение тестовых скриптов без подтверждения;
- влияние AI, correlation или renderer failures на `PASS/FAIL`;
- собственный scheduler нагрузочных тестов;
- замена Grafana, VictoriaMetrics, Prometheus, InfluxDB или OpenSearch.

## 5. Пользовательские истории

### US-01. Локальный анализ

Как performance engineer, я запускаю `ltv ui`, открываю локальный Web UI,
загружаю JTL или `simulation.log`, запускаю анализ и получаю validity, verdict,
coverage, incidents и evidence без серверной установки.

### US-02. Запуск через Jenkins

Как performance engineer, я выбираю существующий Jenkins job, передаю его
параметры, вижу переход queue to build и после завершения автоматически получаю
архивированный JTL или `simulation.log`.

### US-03. Автоматическое обогащение

Как performance engineer, я задаю окно прогона и connections. LT Verdict
сохраняет snapshots VM/Prometheus, InfluxDB, PostgreSQL и OpenSearch в
RunBundle, после чего анализ воспроизводится без повторной выборки.

### US-04. Ручной fallback

Как performance engineer, я могу вручную загрузить любой обязательный или
дополнительный файл, если Jenkins либо connector недоступен. Отчёт явно
показывает provenance `JENKINS`, `CONNECTOR` или `MANUAL`.

### US-05. Поиск максимума

Как performance engineer, я анализирую ступенчатый тест и получаю максимальную
устойчивую ступень, границу следующей неуспешной ступени, диагностический
`capacity_knee` и limiting findings.

### US-06. Сравнение прогонов

Как performance engineer, я сравниваю текущий прогон с вручную выбранным
baseline и смотрю таблицу динамики выбранных метрик за последние N сопоставимых
прогонов.

### US-07. Проверяемый отчёт

Как reviewer, я получаю компактный HTML/AsciiDoc/Confluence-ready отчёт,
содержащий ссылки на evidence и исходные системы, но не зависящий от успешной
публикации в Confluence.

### US-08. Рекомендательный AI analysis

Как performance engineer, я явно запускаю GigaCode analysis и получаю
помеченные гипотезы и рекомендации в отдельном `ai-advice.json`. Исходные facts,
findings и verdict остаются неизменными.

### US-09. Подготовка существующего теста

Как автор JMeter/Gatling теста, я запускаю GigaCode Skill в репозитории теста,
получаю compatibility report и минимальный patch для Jenkins metadata,
артефактов и LT Verdict manifest без изменения профиля нагрузки.

## 6. Основной пользовательский поток

Порядок MVP фиксируется так:

1. **Setup and Doctor.** Пользователь создаёт local profile, настраивает
   connections и проверяет capabilities без запуска теста.
2. **Create Run and Capture Start.** Core создаёт RunBundle, фиксирует окно,
   metadata и доступные pre-run snapshots PostgreSQL.
3. **Start Jenkins.** Loopback backend вызывает существующий Jenkins job,
   сохраняет queue URL, затем build URL и параметры запуска.
4. **Acquire Load Artifact.** После build LT Verdict скачивает Jenkins artifact
   `results.jtl` или `simulation.log`, проверяет path, size и SHA-256. При
   отсутствии файла run переходит в `AWAITING_ARTIFACT`; доступна повторная
   проверка или ручная загрузка.
5. **Capture Finish.** Core фиксирует фактическое окно и получает post-run
   PostgreSQL snapshots, VM/Prometheus, InfluxDB и OpenSearch data.
6. **Analyze.** Выполняются parse, normalize, validity, policy gate,
   source-specific detectors, bounded correlation, incident synthesis и
   rendering.
7. **Review and Export.** Web UI показывает результат, evidence, сравнение и
   exports. Confluence и AI запускаются независимо и fail-soft.

Стабилизация или исправление transport не меняет аналитическую семантику.
Повторное получение файла не создаёт новый verdict при той же `AnalysisRun`
identity.

## 7. Локальная архитектура

### 7.1. Компоненты

```text
Browser
  -> loopback HTTP API
     -> Run Orchestrator
        -> Jenkins adapter
        -> source connectors
        -> RunBundle builder
        -> analysis core
        -> renderers
        -> optional publishers and AI runner
```

Компоненты имеют следующие границы:

- **Loopback Web Shell** обслуживает UI и local REST API, но не содержит
  аналитических правил.
- **Run Orchestrator** управляет локальными task states и retries.
- **Input Adapters** получают данные и сохраняют immutable snapshots.
- **Analysis Core** создаёт deterministic facts, verdict, findings и incidents.
- **Renderers** используют готовый analysis result и не меняют его.
- **Publishers** публикуют готовое представление и не участвуют в verdict.
- **AI Runner** работает после deterministic analysis и пишет отдельный output.

### 7.2. Локальное хранение

RunBundle остаётся источником истины. SQLite разрешён только для:

- индекса локальных прогонов;
- UI preferences и connection metadata без secrets;
- task state Jenkins/connectors/render/publish/AI;
- кэша для быстрого поиска.

SQLite можно полностью перестроить по RunBundle. Удаление или повреждение SQLite
не должно менять canonical result или приводить к повторному Jenkins trigger.
Durable trigger intent и transport journal поэтому хранятся в RunBundle, а
SQLite содержит только их производную проекцию для UI.

Повторный анализ создаёт `analyses/<analysis_id>/`; immutable `inputs/` не
перезаписываются.

### 7.3. Локальная безопасность

По умолчанию backend слушает только `127.0.0.1`. Для UI обязательны:

- случайный session token;
- проверка `Origin`;
- CSRF protection для state-changing operations;
- отсутствие secrets в browser storage;
- запрет неявного non-loopback bind.

Все JTL labels, OpenSearch messages, SQL/pg_profile content, source links и
другие приобретённые значения считаются недоверенными. UI и report renderers
обрабатывают их как данные: применяют contextual escaping/sanitization, разрешают
в пользовательских ссылках только безопасные схемы и не вставляют raw markup.
Self-contained HTML использует restrictive CSP без remote code, forms и base
URL; необходимые inline assets разрешаются точными hashes.

Non-loopback bind, TLS termination и multi-user access требуют отдельного
server design и не включаются как скрытая настройка MVP.

## 8. Источники данных MVP

| Источник | Роль | Получение | Ручной fallback |
| --- | --- | --- | --- |
| JMeter JTL | Обязательный load artifact | Jenkins artifact или файл | Да |
| Gatling `simulation.log` | Обязательный load artifact | Jenkins artifact или файл | Да |
| VictoriaMetrics | Raw PromQL time series | Direct REST | Snapshot file |
| Prometheus | Raw PromQL time series | Direct REST | Snapshot file |
| InfluxDB | Raw time series | Direct REST | Snapshot file |
| PostgreSQL | Pre/post и online snapshots | SQL connector | Exported files |
| `pg_profile` | Database artifact analysis | Online acquisition или HTML | HTML upload |
| JVM metrics | Runtime analysis pack | VM/Prometheus | Metrics snapshot |
| OpenShift metrics | Platform analysis pack | VM/Prometheus | Metrics snapshot |
| Load generator metrics | Validity and capacity guard | VM/Prometheus | Metrics snapshot |
| OpenSearch 2.6 | Errors за окно прогона | Direct REST | JSON snapshot |
| Grafana | Links и optional rendered evidence | Read-only API/URL | PNG/link upload |
| Jenkins | Trigger и transport | Remote API | Manual artifact upload |

VictoriaMetrics и Prometheus используют один PromQL connector contract, но
имеют независимые connection profiles.

Grafana не является источником raw metrics. Raw data берутся напрямую из
VictoriaMetrics, Prometheus или InfluxDB.

Для каждой строки source matrix фиксируется versioned snapshot/artifact schema.
Connector output можно сохранить и затем загрузить вручную. При одинаковых
bytes и metadata анализа manual и connector paths дают одинаковые facts и
verdict; различается только acquisition provenance. Re-analysis существующего
RunBundle работает в snapshot-only режиме и не делает external calls.

## 9. Jenkins и получение удалённых артефактов

### 9.1. Выбранный механизм

В MVP автоматический путь проходит через Jenkins artifacts:

1. существующий job запускает JMeter/Gatling на своей agent или удалённой
   машине;
2. job копирует итоговый файл в workspace;
3. job архивирует `run/results.jtl` или `run/simulation.log`;
4. LT Verdict скачивает artifact через Jenkins HTTP API.

LT Verdict не подключается по SSH к произвольной машине. Если job пока не
архивирует результат, пользователь сохраняет ручной fallback. Signed uploader
и SSH/SFTP adapter рассматриваются только после появления реального ограничения
Jenkins artifacts.

### 9.2. Jenkins REST skeleton

Loopback backend:

- хранит Jenkins credentials через OS keyring или backend secret provider;
- разрешает только allowlisted controller и job;
- поддерживает username plus API token;
- выполняет `POST buildWithParameters`;
- получает Jenkins crumb, если controller его требует;
- сохраняет `Location` queue URL;
- опрашивает queue до появления build number;
- получает build result и artifact list;
- скачивает только allowlisted artifact path.

До `POST` backend атомарно и с durable flush записывает в RunBundle trigger
intent: уникальный `trigger_attempt_id`, controller/job ids, canonical hash
несекретных parameters, timestamp и correlation parameter. Job contract обязан
принять этот parameter и сделать его наблюдаемым в queue/build API; иначе
`ltv doctor` отключает automatic trigger для такого job.

Ответ с `Location` дописывается в transport journal. После неопределённого
transport outcome система ищет ровно этот `trigger_attempt_id` в queue/build.
Если единственный запуск нельзя однозначно восстановить за bounded reconciliation
window, состояние становится `TRIGGER_UNKNOWN` и требует ручного разрешения.
Автоматический второй `POST` запрещён; новый attempt возможен только как явное
действие пользователя с новым id.

Каждый transport transition append-only записывается в
`transport/jenkins/<trigger_attempt_id>.jsonl` с monotonic sequence и durable
flush; незавершённая последняя запись при recovery отбрасывается по framing/hash.

Artifact скачивается streaming в staging с проверкой size limit и SHA-256,
после чего атомарно переносится в immutable input. Build URL, build number и
artifact relative path сохраняются в provenance.

### 9.3. Состояния transport

Минимальные локальные состояния:

```text
READY -> TRIGGER_INTENT -> TRIGGERING -> QUEUED -> RUNNING
                                |
                                v
                         TRIGGER_UNKNOWN

RUNNING -> AWAITING_ARTIFACT -> ARTIFACT_READY
  -> COLLECTING_SOURCES -> ANALYZING -> COMPLETED
```

Transport error, connector error и analytical status хранятся раздельно.
`AWAITING_ARTIFACT` не является `FAIL` нагрузочного теста.
`TRIGGER_UNKNOWN` не является разрешением на автоматический retry.

## 10. RunBundle и provenance

Рекомендуемое расширение структуры:

```text
runbundle/
  manifest.json
  inputs/
    load/
    metrics/
    postgres/
    opensearch/
    grafana/
    config/
  analyses/
    <analysis_id>/
      verdict.json
      analysis.json
      capacity.json
      charts/
      reports/
      ai-advice.json
  provenance/
    inputs.json
    acquisition.json
    requests.json
    execution.json
  transport/
    jenkins/
      <trigger_attempt_id>.jsonl
```

Для каждого input сохраняются:

- logical type;
- source `JENKINS`, `CONNECTOR` или `MANUAL`;
- source connection id без credentials;
- exact interval;
- acquisition timestamp;
- query template и resolved selectors;
- path, size и SHA-256;
- pagination, retries и request-governor statistics;
- connector и source versions, если доступны;
- quality flags.

Ручной и автоматический inputs эквивалентны для анализа при одинаковом
содержимом, но provenance не теряется.

`AnalysisRun` идентифицируется не только input hash. В identity входят hash
immutable analysis revision, hashes всех inputs, ordered set analytical modules
с версиями и конфигурацией, policy, baseline ids/hashes, engine version и
versions всех выходных schemas. Analysis revision охватывает только
analysis-relevant manifest/input metadata и bytes; transport journal,
acquisition/execution provenance и derived outputs из него исключены.
`analysis_id` равен SHA-256 canonical UTF-8 JSON этого identity с фиксированными
правилами сортировки и чисел. UI и CLI обязаны получать одинаковые canonical
bytes и hash; отличающийся identity создаёт новый каталог и никогда не
перезаписывает прежний analysis. Renderer, publisher и AI имеют собственную
производную identity и не входят в deterministic verdict identity.

## 11. Каноническая модель результата

Три оси результата остаются независимыми:

```text
run_validity
policy_verdict
analysis_coverage
```

- `run_validity`: `VALID`, `DEGRADED` или `INVALID`;
- `policy_verdict`: `PASS`, `FAIL`, `NO_POLICY` или `NO_VERDICT`;
- `analysis_coverage`: status и reason каждого source/analysis module.

`verdict.json` содержит только deterministic policy contract. `analysis.json`
содержит findings, incidents, correlations, comparisons и source-specific
analysis. Renderer, publisher, optional OpenSearch correlation и AI не могут
менять `verdict.json`.

Policy verdict формируется только из явно выбранной SLA или baseline policy.
Отсутствие optional source снижает coverage. Оно блокирует verdict только если
выбранная policy прямо объявляет соответствующую capability обязательной.

Validity сохраняет проверки целостности артефакта, временного окна и clock skew,
достаточной длительности, gaps, plan-versus-fact, состояния генератора и
пересечения прогонов на одном стенде. Для `capacity_step` generator health
является обязательной capability для capacity policy verdict.

## 12. Режимы анализа

### 12.1. Обычный режим

`analysis_mode: standard` оценивает заданное или inferred steady window,
сравнивает его с SLA и optional baseline и формирует incidents по временной
шкале прогона.

### 12.2. Ступенчатый режим поиска максимума

`analysis_mode: capacity_step` использует тот же parse, normalize, validity,
policy, detector и evidence pipeline. Отдельный аналитический движок не
создаётся.

Каноническое определение:

> Максимальная устойчивая нагрузка — conservative verified achieved load
> наибольшей валидной ступени, где фактическая нагрузка соответствует плану и
> все применимые SLA выполняются в устойчивом evaluation window.

Технический `capacity_knee`, throughput plateau и resource saturation являются
диагностическими findings. Они объясняют ограничение, но не заменяют
SLA-максимум.

### 12.3. Metadata ступенчатого режима

Предпочтительный manifest содержит:

```yaml
analysis_mode: capacity_step
load_axis: rps
stages:
  - id: rps-300
    target: 300
    from: 2026-08-26T10:10:00Z
    to: 2026-08-26T10:20:00Z
    evaluation_window:
      from: 2026-08-26T10:11:00Z
      to: 2026-08-26T10:20:00Z
policy:
  required_capacity: 300
  achieved_load:
    statistic: p05_10s
    target_tolerance_ratio: 0.05
```

Допустимые оси нагрузки в первом контракте: `rps`, `concurrency` и `users`.
Смешивать разные оси в одном capacity result нельзя.

Если markers или manifest отсутствуют, core может вывести monotonic plateaus из
фактических RPS/concurrency. Такие stages получают origin `INFERRED` и
confidence. При низкой уверенности система показывает step table и bounds, но
не формулирует точный stage-by-stage вывод.

Targets должны монотонно возрастать. Немонотонный multi-stage test сохраняет
обычный stage analysis, но получает `INDETERMINATE` вместо ложного capacity
bound. Comparability key capacity run включает `analysis_mode`, scenario,
stand, dataset, load axis и profile hash.

Capacity bounds требуют хотя бы одного применимого capacity SLA. Без него
ступени не считаются успешно пройденными по умолчанию: `bound_type` равен
`INDETERMINATE`, `policy_verdict` равен `NO_POLICY`, reason code —
`CAPACITY_SLA_MISSING`. Последовательность валидных исходов должна иметь вид
PASS-prefix, затем FAIL-suffix. Наблюдение PASS после FAIL даёт
`INDETERMINATE` с `NON_MONOTONIC_OUTCOME`, пока заранее versioned repeat policy
не разрешит инверсию на повторных ступенях.

### 12.4. Оценка каждой ступени

Для каждой ступени отдельно вычисляются:

- data and generator validity;
- stage id, target и achieved load statistic/range;
- declared target tolerance и conservative verified bound load;
- request count и mix;
- latency percentiles и error rate;
- throughput efficiency;
- SLA checks;
- JVM/OpenShift/PostgreSQL saturation findings;
- OpenSearch error count and rate;
- limiting evidence.

Невалидная ступень не считается успешной или неуспешной ступенью продукта.
Если генератор становится ограничением, результат продукта выше последней
валидной ступени считается неустановленным.

Target считается достигнутым, только если declared achieved statistic не ниже
`target * (1 - tolerance)` и generator validity подтверждена. Для policy bounds
используется не bare target, а `verified_bound_load = min(target,
achieved_statistic)`. Statistic, sample window, observed range и tolerance
сохраняются рядом со stage id; сравнение с `required_capacity` выполняется в той
же load axis и unit.

Нарушение SLA на верхней ступени является ожидаемым наблюдением поиска
максимума и само по себе не делает весь run `INVALID`. Общий policy verdict
определяется только capacity policy ниже.

### 12.5. Capacity result

`capacity.json` содержит:

```json
{
  "load_axis": "rps",
  "bound_type": "BOUNDED",
  "last_passing_stage": {
    "id": "rps-300",
    "target": 300,
    "achieved": {
      "statistic": "p05_10s",
      "value": 296,
      "observed_range": {
        "min": 294,
        "max": 302
      },
      "target_tolerance_ratio": 0.05
    },
    "verified_bound_load": 296
  },
  "first_failing_stage": {
    "id": "rps-350",
    "target": 350,
    "achieved": {
      "statistic": "p05_10s",
      "value": 344,
      "observed_range": {
        "min": 340,
        "max": 351
      },
      "target_tolerance_ratio": 0.05
    },
    "verified_bound_load": 344
  },
  "interval": {
    "lower_inclusive": 296,
    "upper_exclusive": 344
  },
  "capacity_knee": 320,
  "knee_role": "DIAGNOSTIC",
  "confidence": "HIGH",
  "limiting_finding_ids": [
    "openshift.cpu.throttling",
    "load.latency.sla"
  ]
}
```

Допустимые формы:

- `BOUNDED`: verified loads последней успешной и следующей неуспешной ступеней
  известны;
- `LOWER_BOUND`: все проверенные ступени успешны, capacity не ниже последнего
  verified load;
- `UPPER_BOUND`: первая проверенная ступень уже неуспешна на её verified load;
- `INDETERMINATE`: границу нельзя установить из-за validity, gaps, отсутствия
  SLA или pass/fail inversion.

`capacity_knee` может отсутствовать с явным reason code. Если он найден,
detector version и evidence обязательны.

Capacity policy:

- `PASS`, если установленная нижняя граница не меньше `required_capacity`;
- `FAIL`, если установленная верхняя граница не выше `required_capacity`;
- `NO_VERDICT`, если bounds пересекают `required_capacity` либо нужная ступень
  невалидна;
- `NO_POLICY`, если `required_capacity` не задана или отсутствует применимый
  capacity SLA; во втором случае bounds также `INDETERMINATE`.

## 13. Анализ PostgreSQL

### 13.1. Online acquisition

PostgreSQL connector работает по allowlisted read-only queries и получает:

- schema and configuration snapshot;
- pre/post snapshots allowlisted tables;
- row-level or aggregate diff последствий DML;
- `pg_stat_statements` pre/post и delta;
- `pg_profile` samples и immutable HTML report;
- доступные database metrics за окно прогона.

Содержимое таблиц разрешено выгружать, поскольку данные тестового контура
синтетические. Несмотря на это, profile обязан задавать allowlist, stable key,
row limit и byte limit. Таблица без stable key не получает точный row-level
diff; модуль возвращает aggregate или `DEGRADED` с причиной.

Event-level поток `INSERT`, `UPDATE`, `DELETE` через logical decoding не входит
в MVP. Первый вариант — детерминированное сравнение pre/post snapshots.

### 13.2. `pg_stat_statements` и `pg_profile`

Direct `pg_stat_statements` delta является основным источником SQL facts.
`pg_profile` дополняет его отчётом и knowledge checks, но может содержать top-N
ограничения.

Для `pg_profile` сохраняются:

- acquisition interval;
- extension/report version;
- source database identifier без secret;
- report HTML SHA-256;
- sample identifiers;
- warnings о reset или неполном интервале.

Если `pg_profile.statements_reset` сбрасывает статистику либо режим нельзя
проверить, pg_profile analysis получает `DEGRADED`; load verdict не меняется.

### 13.3. Семантика PostgreSQL correlation

Time series PostgreSQL участвуют в bounded correlation. Pre/post schema, table
diff и cumulative statement delta являются контекстом прогона и не
представляются как time-correlated evidence.

## 14. JVM и OpenShift packs

### 14.1. JVM pack

MVP анализирует доступные:

- heap, non-heap и headroom;
- old generation;
- GC count, pause, total time и frequency;
- allocation rate, если экспортируется;
- thread count;
- process CPU;
- class loading;
- pool saturation и deadlocks, если экспортируются.

Выводы строятся по explicit thresholds или manual baseline deltas. Отсутствие
allocation rate либо pool metric снижает coverage соответствующего detector.

### 14.2. OpenShift pack

MVP анализирует:

- CPU usage, requests, limits и throttling;
- memory working set, requests и limits;
- OOM и restarts;
- readiness и unavailable replicas;
- replica and pod count changes;
- pod imbalance;
- network и filesystem, если доступны.

Динамические pod names нормализуются до workload и одновременно сохраняются как
drill-down entities.

## 15. OpenSearch 2.6

### 15.1. Error analysis

Connector выполняет direct REST queries к OpenSearch 2.6 на точное окно
прогона. Mapping connection profile задаёт indices, timestamp, service и error
fields.

MVP выдаёт:

- total errors;
- error rate per minute;
- services;
- error fingerprint or type;
- frequency;
- first and last occurrence;
- time distribution;
- ограниченные samples;
- source links;
- optional delta к вручную выбранному baseline.

Предпочтительны server-side aggregations. Полная выгрузка всех raw documents не
является default.

### 15.2. Overlay и optional correlation

Error series и markers всегда можно накладывать на локальные load, JVM,
OpenShift и PostgreSQL time-series charts. Overlay является визуальным
evidence, а не утверждением причинности.

Автоматическая OpenSearch correlation:

- является opt-in;
- выключена по умолчанию;
- использует только declared templates и bounded lag;
- маркирует результат `ASSOCIATED`;
- не формирует root cause и не меняет verdict.

Code-aware RCA по stack traces, exact product commit и service-to-repository
mapping переносится post-MVP.

## 16. Корреляция метрик

Correlation остаётся частью enriched local MVP для load, JVM, OpenShift и
time-series database metrics.

Metric packs объявляют:

- разрешённые edges;
- направление технически допустимой связи;
- control variables;
- lag window;
- minimum effect size;
- negative evidence;
- topology constraints.

Pipeline:

```text
canonical time grid
  -> stage segmentation
  -> source findings
  -> load-conditioned residuals
  -> partial Spearman
  -> bounded lagged cross-correlation
  -> change-point ordering
  -> multiple-test correction
  -> known-pattern incident synthesis
```

Control variables включают фактические RPS, concurrency, request mix и replica
count. Глобальный поиск «всё со всем» и единый opaque score запрещены.

Incident confidence `LOW`, `MEDIUM` или `HIGH` отражает качество и согласованность
evidence, а не вероятность причинности.

## 17. Общий HTTP request governor

OpenSearch, Grafana, VictoriaMetrics, Prometheus и InfluxDB используют один
локальный request-governor contract.

Bucket key — normalized origin: lowercase scheme/host и effective port после
connection allowlist validation. Path, query, module, run и credentials в key
не входят. Один bucket и один concurrency semaphore разделяются всеми modules,
runs, retries и pagination tasks данного local backend на всём времени его
работы; connector не может создать private limiter. Один state directory
допускает только один активный backend process, поэтому параллельные UI/worker
tasks не умножают разрешённый rate.
После restart первый request ждёт полный configured interval, то есть restart не
создаёт дополнительный burst.

Defaults:

```yaml
httpGovernor:
  defaults:
    requestsPerSecond: 0.5
    burst: 1
    maxConcurrent: 1
    timeout: 30s
    retry:
      maxAttempts: 3
      honorRetryAfter: true
      backoff: exponential
```

`0.5 RPS` означает не более одного нового запроса каждые две секунды.

Правила:

- retry и pagination consume tokens;
- каждый фактический HTTP attempt учитывается;
- `Retry-After` имеет приоритет над локальным backoff;
- per-connection overrides разрешены, но для нескольких active profiles одного
  origin применяется strictest combination: минимальные RPS, burst и
  concurrency; повышение действует только если его допускают все profiles;
- `maxRequestsPerRun` является optional и по умолчанию отсутствует;
- exhaustion явно заданного cap деградирует только соответствующий source;
- provenance хранит request count, retries, throttle wait и cap status.

Гибкий configuration profile может снижать rate ниже default. Повышение rate
требует явного изменения конкретного connection profile.

## 18. Web UI, графики и сравнение

### 18.1. Обязательный Web UI

Web UI входит в первый usable MVP, а не в последующую server phase. Он
поддерживает:

- setup and doctor;
- создание и состояние run;
- Jenkins trigger;
- automatic и manual artifact acquisition;
- connection and source status;
- analysis progress;
- validity, verdict и coverage;
- top 3–7 incidents;
- evidence drill-down;
- standard and capacity views;
- baseline and N-run comparison;
- exports и optional AI action.

### 18.2. Два renderer paths

Используется общий `EvidenceChart` data contract:

- browser renderer — основной интерактивный путь с zoom и overlays;
- JVM static renderer — deterministic SVG/PNG для CLI, reports и fallback.

Static path использует одну chart dependency, а не одновременно JFreeChart и
XChart. Конкретная библиотека не является public contract и выбирается в
implementation plan по минимальному проверяемому прототипу.

Renderer failure не меняет verdict. Grafana может предоставить ссылку или
optional image, но локальный renderer остаётся запасным движком.

### 18.3. Сравнение двух прогонов

В MVP пользователь вручную выбирает один baseline. Прогоны выравниваются:

1. по общим подтверждённым stages;
2. иначе по relative time от фактического начала нагрузки.

Wall-clock alignment, растягивание графиков и interpolation через gaps
запрещены. Несовместимые metric definition, aggregation, unit или profile
показываются как `N/A` с причиной.

OpenSearch overlays отображаются отдельно для каждого прогона, чтобы ошибки
разных запусков не смешивались.

### 18.4. Таблица динамики за N прогонов

Default `N = 10`. Выбираются последние локальные RunBundles с точным
comparability key; пользователь может вручную включать и исключать строки.

Таблица содержит:

- run date;
- Jenkins build;
- commit and application version;
- load profile;
- verdict;
- выбранные metric values;
- delta к предыдущему прогону;
- optional delta к вручную назначенному baseline;
- direction-aware coloring;
- `N/A` reason.

Таблица читает только локальные RunBundles и не выполняет новые source queries.
Для transactions, services и SQL применяются filters, чтобы не создавать
неограниченную high-cardinality таблицу. Старые verdict не пересчитываются.

Таблица экспортируется в HTML, AsciiDoc и Confluence-ready representation.

## 19. Отчёты и Confluence

Canonical result рендерится в:

- JSON;
- self-contained HTML;
- AsciiDoc;
- Confluence-ready representation;
- RunBundle export.

Renderer и publisher разделены. Confluence REST skeleton входит в MVP и имеет
состояния:

```text
NOT_CONFIGURED
PUBLISHING
PUBLISHED
FAILED
```

Точный Cloud или Data Center endpoint, auth и page update strategy не
угадываются. Они заполняются в закрытом контуре через connection adapter.
Ошибка публикации не меняет verdict и не удаляет локальные отчёты. Manual
upload остаётся доступен.

## 20. Рекомендательный AI analysis

### 20.1. Контракт GigaCode runner

AI analysis опционален и выполняется после deterministic analysis:

```text
verdict.json
  + analysis.json
  + selected evidence
  -> read-only staging directory
  -> headless GigaCode
  -> ai-advice.json
```

Поддерживается GigaCode fork, совместимый с Qwen Code 0.21.1. Конкретные CLI
flags не предполагаются: `ltv doctor` проверяет executable, version, headless
input/output, exit codes и timeout behavior.

Runner:

- запускается в OS sandbox с ephemeral home/config/CWD и без доступа к
  пользовательскому home или исходному repository;
- очищает inherited project/personal instructions, hooks, extensions и MCP
  configuration и process environment; передаёт только allowlisted variables и
  загружает только versioned system prompt/Skill из allowlist;
- предоставляет только read-only evidence mount и validated stdout-only result;
- разрешает network только к явно allowlisted model endpoint и не предоставляет
  shell, browser или filesystem-write tools;
- не использует unrestricted or yolo tool mode;
- требует structured JSON по versioned schema;
- ограничивает timeout, model/tool budget и output size;
- сохраняет CLI, model, skill, input и prompt hashes;
- сохраняет duration, exit code и validation result;
- при timeout или invalid output возвращает `FAILED` fail-soft.

Capability probe проверяет фактическую изоляцию fork, включая отключение
inherited context/tool discovery и соблюдение network/tool policy, а не наличие
ожидаемых имён flags. Если хотя бы одна граница не подтверждена, AI module
помечается `UNAVAILABLE` и headless process не запускается.

`ai-advice.json` содержит:

- summary;
- ranked RCA hypotheses;
- recommendations;
- caveats;
- evidence references.

AI output всегда явно помечен как рекомендация и не меняет verdict, source
facts, deterministic findings или capacity result.

### 20.2. Основание headless integration

Дизайн опирается на официальные контракты Qwen Code:

- [Headless mode](https://qwenlm.github.io/qwen-code-docs/en/users/features/headless/);
- [Agent Skills](https://qwenlm.github.io/qwen-code-docs/en/users/features/skills/).

Совместимость конкретного GigaCode fork подтверждается capability probe, а не
предположением о полном совпадении CLI.

## 21. GigaCode Skill для адаптации тестов

Skill `lt-verdict-onboard-test` поставляется как дополнительная возможность
GigaCode и запускается в репозитории нагрузочных тестов. Он не конвертирует
чужие skills и не участвует в canonical verdict.

### 21.1. Проверки Skill

Skill определяет:

- JMeter или Gatling;
- язык и build tool;
- Jenkinsfile и job conventions;
- profiles и external manifests;
- фактические artifact paths.

Затем он оценивает уровни:

| Уровень | Проверяемая совместимость |
| --- | --- |
| L0 | JTL или `simulation.log` доступен для анализа |
| L1 | Jenkins metadata и artifact archive |
| L2 | `run_id`, scenario, stand, dataset, planned profile и stages |
| L3 | Optional markers/tags и comparability metadata |

Отсутствие tags не блокирует L0. Compatibility report обязан перечислить,
какие функции теряются без metadata.

Wrapper не передаёт модели repository целиком. Он строит allowlisted view из
подтверждённых test/build/Jenkins paths, применяет `.gitignore`, optional
`.ltverdictignore` и hard deny для `.git`, environment files, credentials,
private keys, token stores и user home links. До model call выполняется secret
scan: файл с найденным secret исключается или получает non-patchable redacted
view. Для исключённых файлов сохраняются только sanitized relative path и hash;
их content, process environment и Git history модели недоступны.

### 21.2. Режимы Skill

- `audit` только формирует отчёт из read-only repository mount;
- `patch` является default, работает на том же read-only mount и выдаёт
  reviewable patch вне repository через validated output;
- `apply` является отдельной не-AI операцией wrapper: она проверяет base revision
  и patch hash, разрешённые paths и явное подтверждение пользователя, затем
  применяет ровно подтверждённый patch.

Skill сначала предпочитает:

1. внешний `ltv-run.yaml`;
2. runtime properties;
3. Jenkins metadata и archive configuration;
4. только затем минимальные изменения тестового кода.

Он никогда не меняет load profile, request mix, think time, assertions или
targets. JMeter XML изменяется структурно, не regex. Gatling изменяется
language-aware способом. В `audit` и `patch` запрещён запуск repository code,
build plugins и hooks; wrapper выполняет только собственные deterministic
structural validators и проверку artifact contract. После `apply` те же
validators сравнивают semantic invariants до/после. Compile/config command
возможна лишь отдельным explicit opt-in в OS sandbox, по умолчанию без network.

Instruction text Skill не считается enforcement boundary. Read-only mount,
tool policy, patch destination, path allowlist и semantic-invariant checks
обеспечиваются wrapper независимо от поведения модели.

## 22. Отказы и fail-soft поведение

| Отказ | Результат |
| --- | --- |
| Jenkins trigger outcome неизвестен | Reconcile по `trigger_attempt_id`; при неоднозначности `TRIGGER_UNKNOWN` и только ручное разрешение |
| Jenkins artifact отсутствует | `AWAITING_ARTIFACT`, retry или manual upload |
| VM/Prometheus/Influx недоступен | Source `FAILED`; load-only analysis продолжается, dependent policy может дать `NO_VERDICT` |
| PostgreSQL snapshot неполон | PostgreSQL module `DEGRADED` или `FAILED` |
| OpenSearch limit или timeout | OpenSearch coverage снижается |
| Grafana render не удался | Локальный chart и source link остаются |
| Static renderer не удался | Интерактивный Web chart остаётся |
| Confluence publish не удался | Локальные outputs остаются каноническими |
| AI timeout или invalid JSON | `ai-advice` task `FAILED`; verdict неизменен |
| Inferred capacity stages ненадёжны | Bounds/step table без точного maximum claim |
| Generator saturation | Capacity выше последней валидной ступени не установлен |

## 23. Секреты, данные и ограничения доверия

- Secrets хранятся в OS keyring или backend secret provider.
- Secrets не попадают в browser storage, RunBundle, report, query snapshot или
  AI staging.
- Connectors используют least-privilege accounts. Исключения из read-only
  ограничены Confluence publisher и allowlisted `pg_profile` functions, если
  установленная версия расширения требует их для создания samples.
- PostgreSQL table content разрешён только через explicit allowlist и limits.
- Artifact paths нормализуются; path traversal, symlink escape и archive bombs
  отклоняются.
- Raw request and response bodies не собираются по умолчанию.
- OpenSearch samples и SQL content имеют configurable size caps.
- RunBundle inputs immutable; исправление input создаёт новую revision.
- Любой external call сохраняет sanitized provenance.
- Credentialed connectors не следуют cross-origin redirects. Каждый redirect
  повторно проходит origin allowlist; credentials никогда не пересылаются на
  новый origin.
- Report/UI renderers используют malicious-input fixtures для JTL labels,
  OpenSearch messages, SQL/pg_profile content, URLs и markup. Safe URL schemes,
  contextual escaping и report CSP проверяются автоматически.
- Пользовательские links допускают только `http` и `https` без embedded
  credentials; `javascript`, `file` и user-controlled `data` URLs отклоняются.

## 24. Переиспользование reference repositories

### 24.1. `lt_cycle_task`

Используются архитектурные идеи:

- foreground Jenkins pipeline;
- разделение Start и Analyze;
- штатные Gatling artifacts;
- portable comparison;
- Grafana как evidence renderer;
- profiles и config-as-code.

Не переносятся:

- глобальный status file как единственный source of truth;
- определение «последнего» tarball;
- `sshpass`;
- отключение host key checking;
- скрытая Jenkins-specific логика внутри core.

### 24.2. `pg_profile_checks`

Используются:

- artifact-first pg_profile parser;
- `ReportContext`;
- стабильные finding identifiers;
- thresholds and knowledge-as-data;
- separation deterministic analysis from narrative.

Online acquisition остаётся отдельным source adapter. Direct
`pg_stat_statements` delta не подменяется top-N данными отчёта.

### 24.3. Provenance прямого переноса

Reference repositories не содержат явной лицензии. До verbatim code transfer
implementation plan обязан создать короткий provenance record с:

- repository URL и exact commit;
- владельцем;
- подтверждённым разрешением на использование;
- перечнем перенесённых файлов или фрагментов;
- адаптацией к LT Verdict contract;
- проверками и attribution.

До этого разрешено переносить только идеи и заново реализованные contracts.
Файлы с credentials или secret-like values не переносятся ни при каких
условиях; перенесённый material проходит secret scan.

## 25. Planned public contracts

Контракт фиксируется перед реализацией использующего его slice, а не заранее
для всего MVP. Slice 0 фиксирует только:

- `run.v1` — идентичность запуска, режим анализа и ссылки с SHA-256 на входы;
- `analysis-result.v1` — validity, verdict, coverage, findings и evidence.

Остальные контракты не удалены: parser/archive и local runtime принадлежат
Slice 1; source snapshots и request governor — Slice 2; Jenkins transport и
artifacts — Slices 3–4; capacity — Slice 5; JVM/OpenShift и OpenSearch —
Slices 6–7; charts/comparison — Slice 8; reports/Confluence — Slice 9;
GigaCode runner и Skill — Slice 10.

Эта spec не добавляет production dependency. Каждая новая dependency и каждый
новый public contract фиксируются в плане своего slice или ADR до реализации.

## 26. ADR по мере реализации

Публичные `run.v1` и `analysis-result.v1` вместе с их evolution policy
зафиксированы в
[`ADR 0001`](../../adr/0001-slice-0-public-contracts.md). Отдельный пакет
предварительных ADR не нужен. Следующий ADR создаётся в том slice, где реально
появляется значимое решение о runtime, API, schema, dependency, security или
эксплуатации; один ADR может покрывать несколько тесно связанных решений.

## 27. Реализационные срезы

Этот раздел задаёт зависимость, но не заменяет implementation plan.
Дизайн имеет program-level scope и не должен превращаться в один mega-PR.
Первый план после утверждения этой spec покрывает только Slice 0 и нормативную
миграцию v0.5 to v0.6. Каждый следующий slice получает собственные
specification, implementation plan и exit gate.

Slice — milestone group, а не обещание одного PR. Для governance `Slice N`
соответствует `Stage N`; используются milestone/report/tag идентификаторы
`Stage N`, `stage-N.md` и `stage-N`. Один PR содержит одну законченную
проверяемую задачу; связанные небольшие контракты разрешено доставлять вместе.

### Slice 0. Contracts and evidence

- нормативный PRC v0.6, компактный roadmap и superseded-маркеры v0.5;
- две минимальные JSON Schema: `run.v1` и `analysis-result.v1`;
- по одному маленькому синтетическому JMeter JTL и Gatling `simulation.log`;
- один stdlib-only offline verifier для JSON, required-полей и SHA-256 входов;
- короткий milestone report.

Минимальный executable exit gate Slice 0 —
`python tools/verify_slice0.py`. Он работает offline, не требует сторонних
зависимостей и завершается non-zero при невалидном JSON, отсутствии обязательного
верхнеуровневого поля, невалидном LT Verdict RFC 3339 profile timestamp embedded
example (timezone обязательна, leap seconds не поддерживаются) или
несовпадении SHA-256. Registry, version matrix, semantic oracles, benchmark,
source/security/capacity corpora и production runtime в Slice 0 не создаются:
они добавляются только вместе с использующим их slice.

### Slice 1. Local usable shell

- one core;
- filesystem RunBundle;
- JMeter/Gatling parsers;
- mandatory loopback Web UI;
- manual upload fallback;
- validity, policy gate и basic reports.

### Slice 2. Primary online sources

- common request governor;
- VictoriaMetrics/Prometheus;
- InfluxDB;
- PostgreSQL online snapshots.

### Slice 3. Jenkins workflow

- setup/doctor;
- existing job trigger;
- queue/build tracking;
- credential isolation.

### Slice 4. Artifact and post-run collection

- Jenkins artifact download;
- `AWAITING_ARTIFACT`;
- source snapshots за фактическое окно;
- manual fallback for every input.

### Slice 5. Capacity analysis

- `capacity_step` stage contract;
- load-generator health capability;
- conservative bounds, policy и fixtures.

### Slice 6. JVM and OpenShift enrichment

- JVM analysis pack;
- OpenShift analysis pack.

### Slice 7. OpenSearch enrichment

- OpenSearch errors and overlays;
- optional bounded correlation.

### Slice 8. Charts and comparisons

- interactive/static evidence charts;
- baseline comparison;
- N-run dynamics table.

### Slice 9. Outputs and publishing

- JSON and self-contained HTML outputs;
- AsciiDoc/Confluence-ready reports;
- Confluence REST skeleton.

### Slice 10. Optional add-ons

- advisory GigaCode analysis;
- `lt-verdict-onboard-test` Skill;
- Grafana rendered evidence.

Server shell, shared history, RBAC и code-aware RCA остаются отдельным
post-MVP plan.

## 28. Observable acceptance criteria

MVP design считается реализованным только при выполнении следующих наблюдаемых
критериев:

1. `ltv ui` поднимает Web UI на loopback без внешней database.
2. JTL и `simulation.log` анализируются через UI и CLI с одинаковым canonical
   policy payload.
3. Table-driven fixtures для каждой строки source matrix подтверждают manual
   fallback: одинаковый snapshot даёт те же facts/verdict с отличающимся только
   acquisition provenance, а re-analysis выполняет zero external calls.
4. До Jenkins `POST` trigger intent durable в RunBundle. Неопределённый outcome
   reconciles по `trigger_attempt_id`; неоднозначность даёт `TRIGGER_UNKNOWN` и
   не вызывает второй `POST` без явного решения пользователя.
5. Jenkins queue/build отслеживаются, а архивированный artifact скачивается
   streaming и проверяется по size/hash до atomic move.
6. Отсутствующий Jenkins artifact приводит к `AWAITING_ARTIFACT`, а не к
   ложному load verdict.
7. UI и CLI строят byte-identical canonical `AnalysisRun` identity/result;
   изменение input revision, module/version/config, policy, baseline, engine или
   schema создаёт новый `analysis_id`.
8. VM и Prometheus используют один PromQL connector contract.
9. VM/Prometheus, InfluxDB, OpenSearch и Grafana соблюдают aggregate default
   `0.5 RPS`, `burst = 1`, `maxConcurrent = 1` для одного normalized origin даже
   при одновременной работе нескольких modules/runs; конфликт profiles разрешён
   strictest active combination.
10. Retry и pagination учитываются тем же request governor и provenance;
    `maxRequestsPerRun` по умолчанию отсутствует.
11. PostgreSQL connector создаёт pre/post allowlisted table snapshots,
    `pg_stat_statements` delta и pg_profile artifact либо явный degraded result.
12. JVM и OpenShift packs выдают findings только по доступным capabilities.
13. OpenSearch 2.6 report показывает services, error types/fingerprints,
    frequency и time distribution за точное окно.
14. OpenSearch errors накладываются на локальные charts; automatic correlation
    остаётся opt-in и не меняет verdict.
15. `run_validity`, `policy_verdict` и `analysis_coverage` независимы.
16. Optional connector, renderer, publisher или AI failure не меняет
    deterministic verdict.
17. `capacity_step` формирует step table и один из `BOUNDED`, `LOWER_BOUND`,
    `UPPER_BOUND`, `INDETERMINATE`.
18. Каждая capacity stage сохраняет id, target, achieved statistic/range,
    tolerance и verified bound load; policy не использует bare target.
19. Отсутствие applicable capacity SLA и неразрешённый pass/fail/pass дают
    `INDETERMINATE` с reason code, а не ложный maximum.
20. Capacity policy отличает `PASS`, `FAIL`, `NO_POLICY` и `NO_VERDICT` по
    verified bounds относительно `required_capacity`.
21. `capacity_knee` явно помечен как diagnostic, а не canonical maximum.
22. Текущий run сравнивается с одним manual baseline без wall-clock stretching.
23. N-run table по умолчанию показывает 10 локальных comparable RunBundles и
    не выполняет external queries.
24. JSON, self-contained HTML, AsciiDoc и Confluence-ready outputs создаются из
    одного result model.
25. Confluence REST skeleton fail-soft и не является prerequisite отчёта.
26. GigaCode capability probe отклоняет runner без требуемой OS isolation,
    context/tool/network policy и bounded execution.
27. GigaCode output проходит schema validation, сохраняется отдельно и явно
    помечен как advisory.
28. GigaCode Skill `audit`/`patch` работают read-only без repository code
    execution и видят только allowlisted, secret-scanned view; отдельный `apply`
    проверяет base/hash/path/confirmation и semantic invariants.
29. Malicious JTL/OpenSearch/SQL/link fixtures подтверждают contextual escaping,
    safe URL schemes, report CSP и запрет credentialed cross-origin redirects.
30. Secrets отсутствуют в RunBundle, browser storage, reports и AI staging.
31. Offline Slice 0 verifier читает обе schema и оба example, проверяет их
    обязательные верхнеуровневые поля и отклоняет повреждённые hashes входов.
32. Каждая MVP-фича из разделов 3 и 5 имеет owning Slice 1–10; упрощение
    Slice 0 не удаляет её из roadmap.
33. Серверное развёртывание не является prerequisite ни одного критерия выше.

## 29. Проверка этой спецификации

Для документационной задачи применяются:

```powershell
npx --yes markdownlint-cli2@0.23.2 docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md
git diff --cached --check
rg -n -i "T[B]D|T[O]DO|FIXM[E]|X[X]X" docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md
git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .
git status --short --branch
```

## 30. Документационное влияние

После письменного утверждения этой spec следующий implementation plan должен:

- сделать PRC v0.6 tracked и нормативным;
- пометить v0.5 PRC/plan/spec как superseded;
- создать только два контракта и два синтетических примера Slice 0;
- создать компактный roadmap со всеми Slices 1–10;
- обновить README и CHANGELOG;
- закрыть Slice 0 одним stdlib-only verifier и коротким milestone report.

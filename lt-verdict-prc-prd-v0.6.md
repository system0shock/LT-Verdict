# LT Verdict — PRC/PRD v0.6

## Portable Modular Verdict Engine for Load Testing

**Статус:** нормативный baseline; границы local-first MVP уточняет
[`delta design`](docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md)

**Дата:** 18 августа 2026

**Заменяет:** `prc-lt-verdict-v0.5.md`

**Основной фокус:** портативность, модульность, минимальная стоимость внедрения и сокращение ручного просмотра графиков

---

## 0. Резюме решения

LT Verdict — это **портативный модульный движок послетестового анализа**, который преобразует результаты JMeter/Gatling и доступные данные наблюдаемости в:

1. проверяемый статус качества прогона;
2. небольшой набор приоритетных инцидентов вместо десятков дашбордов;
3. объяснение, какие факты и временные зависимости привели к выводу;
4. сравнение с baseline и историей;
5. переносимый пакет артефактов, который одинаково анализируется локально и на сервере.

Главный пользовательский сценарий формулируется так:

> После завершения НТ быстро понять, можно ли доверять прогону, что именно ухудшилось, где вероятнее всего находится проблема и какие данные это подтверждают — не просматривая вручную десятки или сотни графиков.

Ключевое продуктовое решение v0.6:

> **Базовая ценность должна быть доступна по штатным JTL/simulation.log без изменения тестовых сценариев. Метрики OpenShift, VictoriaMetrics/Influx, pg_profile, APM, история и ИИ подключаются как независимые возможности и повышают глубину анализа, но не являются обязательными для запуска ядра.**

LT Verdict не должен становиться ещё одной тяжёлой платформой мониторинга. Он не заменяет Grafana, VictoriaMetrics, InfluxDB, JMeter или Gatling. Он использует их данные, чтобы выдать компактный и аудируемый ответ.

---

# 1. Контекст и проблема

## 1.1. Текущая боль

Во время нагрузочного теста инженер обычно наблюдает несколько классов данных одновременно:

- RPS, concurrency и фактический профиль нагрузки;
- p50/p95/p99 и ошибки по транзакциям;
- CPU, throttling, memory, network и restarts в OpenShift;
- JVM heap, allocation rate, GC и thread pools;
- connection pools и очереди;
- PostgreSQL CPU, I/O, locks, WAL, checkpoints и top SQL;
- Kafka lag и broker/consumer metrics;
- APM traces и технологические метрики приложения.

Даже при хороших дашбордах это создаёт три системные проблемы.

### Перегрузка вниманием

Человек вынужден последовательно просматривать большое число панелей. Чем больше сервисов и зависимостей, тем выше вероятность пропустить кратковременный выброс, смену режима или нетипичную комбинацию значений.

### Ложные корреляции

При росте RPS одновременно растёт множество метрик. Простая визуальная корреляция часто приводит к выводу вида «CPU вырос вместе с latency», хотя общий источник обоих изменений — сама нагрузка.

### Неповторяемый анализ

Два инженера могут по-разному выбрать графики, интервалы и baseline. Выводы сложно повторить, проверить и использовать в CI.

## 1.2. Почему существующие инструменты не закрывают задачу полностью

- JMeter и Gatling хорошо создают нагрузку и сохраняют результаты, но не дают системный RCA по приложению и зависимостям.
- VictoriaMetrics, InfluxDB и Grafana хорошо показывают временные ряды, но не сводят их автоматически в несколько проверяемых выводов по конкретному прогону.
- APM хорошо локализует медленные вызовы, но не всегда отличает первопричину от места, где проявился симптом.
- Универсальные ML-детекторы дают anomaly score, но без контекста нагрузки, фаз теста и архитектуры часто создают шум и плохо объясняются.

## 1.3. Ценность продукта

LT Verdict должен сократить путь:

```text
50–200 графиков → ручная сверка → гипотеза → дополнительная проверка
```

до:

```text
вердикт → 3–7 инцидентов → evidence → выбранные графики и исходные данные
```

Графики остаются доступны, но открываются как доказательства конкретного инцидента, а не как обязательный старт анализа.

---

# 2. Цели и границы продукта

## 2.1. Цели

### G-01. Портативность вычислений

Один и тот же аналитический движок должен работать:

- как локальный CLI;
- как локальный web-визард;
- в одном OCI-контейнере;
- как серверное приложение;
- в Docker, Kubernetes или OpenShift;
- в изолированном контуре без внешнего SaaS.

### G-02. Портативность данных

Любой прогон должен экспортироваться в переносимый `RunBundle`, который содержит достаточно фактов для повторного анализа без обязательного доступа к исходной TSDB.

### G-03. Минимальный порог входа

Существующий JMeter/Gatling-сценарий должен получать базовый анализ без переписывания скрипта, подключения proprietary listener или внедрения SDK.

### G-04. Модульное расширение

Источники данных и анализаторы должны подключаться независимо. Добавление PostgreSQL-, JVM-, Kafka- или APM-модуля не должно требовать изменений ядра и нагрузочных сценариев.

### G-05. Снижение когнитивной нагрузки

Основной экран и отчёт должны показывать не стену графиков, а:

- валидность прогона;
- итоговый policy verdict;
- главные изменения относительно baseline;
- ограниченный список сгруппированных инцидентов;
- доказательства и отрицательные свидетельства;
- покрытие анализа и пропущенные модули.

### G-06. Детерминированность и аудит

При одинаковых входных данных, конфигурации и версиях модулей детерминированная часть должна выдавать одинаковый результат. Каждый вывод должен ссылаться на входной артефакт, запрос, временной интервал и версию правила.

### G-07. Прогрессивное обогащение

Отсутствие необязательной метрики должно снижать глубину конкретного анализа, а не уничтожать весь результат.

## 2.2. Не-цели

LT Verdict в базовом продукте не должен:

- заменять JMeter/Gatling как генератор нагрузки;
- заменять VictoriaMetrics/Influx/Grafana как универсальную observability-платформу;
- требовать, чтобы все команды унифицировали скрипты до первого использования;
- строить PASS/FAIL на ответе LLM;
- обещать доказанную причинность только по корреляции;
- автоматически изменять production-конфигурацию;
- разворачиваться как набор обязательных микросервисов;
- загружать сырые данные во внешний облачный сервис по умолчанию;
- анализировать все доступные series без отбора и контроля множественных сравнений.

---

# 3. Основные продуктовые принципы

## P-01. Artifact-first

Базовым входом являются уже существующие артефакты прогона. Подключение к TSDB или APM — дополнительный источник, а не обязательное условие работы.

## P-02. Один движок — несколько оболочек

CLI, локальный UI и сервер не реализуют собственную аналитику. Они вызывают одно и то же ядро и получают одинаковые канонические JSON-результаты.

## P-03. Скрипты производят факты, а не описывают аналитическую модель

В JMeter/Gatling не должны вручную задаваться:

- PromQL/InfluxQL/Flux;
- список инфраструктурных метрик;
- способы агрегации pod’ов;
- допустимые лаги;
- корреляционные пары;
- признаки PCA/ML;
- кандидаты root cause.

Эти знания принадлежат metric packs и analysis modules.

## P-04. Fail-closed для gate, fail-soft для enrichment

- Если обязательные данные policy gate недостоверны, итоговый verdict — `NO_VERDICT`.
- Если необязательный аналитический модуль не может отработать, он получает `SKIPPED`, `DEGRADED` или `FAILED`, но не ломает остальные результаты.

## P-05. Findings прежде narrative

Сначала формируются структурированные факты и evidence. Человекочитаемый текст и LLM-интерпретация строятся только поверх них.

## P-06. Incident вместо списка сигналов

Десять связанных отклонений CPU, queue, pool и latency должны быть объединены в один инцидент с временной цепочкой, а не показаны как десять независимых предупреждений.

## P-07. Raw data остаются доступными

Сводка не должна скрывать детали. Любой вывод должен раскрываться до конкретного временного ряда, транзакции, SQL, pod или span.

## P-08. Modular monolith по умолчанию

Для локального и простого серверного развёртывания модули работают внутри одного дистрибутива. Выделение модулей в subprocess/сервисы допускается позднее, но не является обязательной архитектурой MVP.

---

# 4. Пользователи и сценарии

## 4.1. Основные пользователи

| Пользователь | Задача | Ожидаемый результат |
|---|---|---|
| Performance engineer | Проверить результат НТ и найти деградацию | Вердикт, инциденты, evidence, сравнение с baseline |
| Команда приложения | Понять, где искать проблему | Затронутая транзакция, подсистема, временная цепочка, следующие проверки |
| Platform/observability engineer | Подключить стандартные метрики | Переиспользуемый metric pack и selectors без правки сценариев |
| Reviewer/руководитель | Быстро понять состояние релиза | Краткая сводка, риски, изменение capacity/SLO |
| CI/CD | Автоматически применить gate | Стабильный exit code и машиночитаемый verdict |

## 4.2. Базовые сценарии

### UC-01. Локальный анализ одного прогона

Инженер передаёт каталог JMeter/Gatling или перетаскивает файлы в локальный UI. LT Verdict автоматически определяет формат, строит базовый отчёт и не требует серверной инфраструктуры.

### UC-02. Локальное сравнение двух прогонов

Текущий и baseline RunBundle анализируются локально. Результат показывает изменения latency, throughput, errors и обнаруженные инциденты.

### UC-03. Анализ с подключением к VictoriaMetrics или Influx

Пользователь указывает endpoint и минимальные selectors. Коннектор сохраняет снимок метрик в RunBundle, после чего анализ воспроизводим офлайн.

### UC-04. CI post-processing

Существующий pipeline после JMeter/Gatling запускает одну команду LT Verdict. Сам тестовый код не изменяется.

### UC-05. Серверный каталог прогонов

Команды загружают RunBundle или регистрируют прогон через API. Сервер добавляет историю, общие baseline cohorts, доступ по ролям и совместный просмотр.

### UC-06. Подключение предметного модуля

В RunBundle добавляется, например, `pg_profile.html`. PostgreSQL-модуль автоматически обнаруживает вход и выдаёт собственные findings, которые затем группируются с системными инцидентами.

---

# 5. Модель внедрения без переписывания сценариев

## 5.1. Уровни интеграции

| Уровень | Что предоставляет команда | Изменения скрипта | Доступный анализ |
|---|---|---:|---|
| L0 — artifact-only | JTL или simulation.log | Нет | latency/errors/throughput, структура транзакций, базовая валидность, сравнение прогонов |
| L1 — wrapper/CI | Артефакт + run metadata из CI | Нет | provenance, версия, baseline key, автоматический post-run |
| L2 — external manifest | Отдельный YAML с profile/stages/SLA | Нет | точная сегментация, policy gate, plan-vs-fact |
| L3 — optional markers | Stage/run markers через штатные свойства/теги | Минимальные и необязательные | наиболее точная синхронизация фаз и зависимостей |
| L4 — custom instrumentation | Дополнительные бизнес-метрики | Да, только по решению команды | расширенный domain-specific RCA |

Базовый продукт обязан быть полезен на L0 и L1.

## 5.2. Что допускается запрашивать у команды

Для стандартного приложения — только данные, которые невозможно надёжно вывести автоматически:

```yaml
application:
  id: payment-api

selectors:
  namespace: payment-perf
  workload: payment-api
  apm_service: payment-service

policy_profile: default-http-api
```

Даже этот файл не должен быть обязательным для artifact-only анализа.

## 5.3. Работа с фазами и ступенями

При отсутствии явных stage markers LT Verdict:

1. пытается прочитать профиль из внешнего manifest;
2. затем использует фактический RPS/concurrency для консервативного inference;
3. отмечает сегменты как `INFERRED`, если границы не подтверждены;
4. не делает точных stage-by-stage утверждений при низкой уверенности.

Расширенная разметка улучшает точность, но не является условием первого запуска.

## 5.4. Целевой upkeep

| Операция | Целевой ручной труд |
|---|---:|
| Повторный прогон существующего сценария | 0 минут |
| Подключение LT Verdict в существующий CI | одна post-run команда |
| Новый сценарий уже подключённого приложения | 0–30 минут |
| Стандартное JVM/OpenShift-приложение | до 1 часа при хороших labels |
| Нестандартные labels/метрики | разовая настройка app profile/pack |
| Добавление нового общего detector | без изменений тестовых скриптов |

---

# 6. Портативность как продуктовый контракт

Портативность состоит из четырёх независимых свойств.

## 6.1. Deployment portability

Минимальный комплект запускается локально и не требует PostgreSQL, Kafka, Kubernetes или object storage. Серверные зависимости добавляются только при необходимости коллективной работы.

## 6.2. Data portability

RunBundle можно:

- создать локально;
- загрузить на сервер;
- скачать с сервера;
- повторно проанализировать другой версией движка;
- передать в изолированный контур.

## 6.3. Configuration portability

Metric packs, thresholds, knowledge и module manifests хранятся как versioned config-as-code и не зависят от конкретной оболочки.

## 6.4. Analytical portability

Детерминированный результат определяется:

```text
input hashes
+ engine version
+ module versions
+ configuration hashes
+ baseline set
= canonical output
```

## 6.5. Режимы развёртывания

| Режим | Хранение | Сеть | Целевая аудитория |
|---|---|---|---|
| CLI | Файловая система | Не требуется | Один инженер, CI |
| Local Web | Временные сессии/SQLite | Только loopback | Интерактивный локальный анализ |
| Single-server | Filesystem/SQLite или PostgreSQL | Внутренний контур | Одна команда |
| Multi-team server | PostgreSQL + object storage | Внутренний контур | Несколько команд, RBAC и история |

Один режим не должен создавать несовместимый формат данных с другим.

---

# 7. Концептуальная архитектура

```text
                    Существующий НТ-контур
        JMeter / Gatling / CI / удалённые генераторы
                           │
                           ▼
                ┌──────────────────────┐
                │ Input adapters       │
                │ JTL / simulation.log │
                │ VM / Influx / APM    │
                │ pg_profile / configs │
                └──────────┬───────────┘
                           │
                           ▼
                ┌──────────────────────┐
                │ RunBundle Builder    │
                │ snapshot + hashes    │
                └──────────┬───────────┘
                           │
                           ▼
        ┌──────────────────────────────────────────┐
        │               LT Verdict Core            │
        │ parse → normalize → validate → gate      │
        │ modules → findings → incident synthesis  │
        └───────────────┬──────────────────────────┘
                        │
          ┌─────────────┼────────────────┐
          ▼             ▼                ▼
   verdict.json   analysis.json    report.html/md
          │             │                │
          └─────────────┴────────────────┘
                        │
        CLI / Local UI / CI / Server UI / Confluence
```

## 7.1. Компоненты

### Core Orchestrator

- обнаруживает входы;
- строит capability map;
- разрешает зависимости модулей;
- запускает модули в детерминированном порядке;
- собирает provenance;
- объединяет findings в incidents;
- формирует канонические outputs.

### Input Adapters

Отвечают только за получение и snapshot данных. Коннектор к VictoriaMetrics не должен содержать знания о JVM или PostgreSQL.

### Normalizers

Преобразуют разные форматы в канонические представления:

- LoadEventFrame;
- TimeAlignedMetricFrame;
- HistogramSet;
- ConfigSnapshot;
- TraceReferenceSet.

### Analysis Modules

Независимые детекторы и предметные анализаторы.

### Incident Synthesizer

Сокращает список сигналов, объединяя findings по времени, затронутому SLI, подсистеме и технической топологии.

### Renderers/Exporters

Формируют JSON, HTML, Markdown, Confluence, JUnit XML и другие представления, не меняя аналитический результат.

### Storage Provider

Абстракция над локальными файлами/SQLite и серверными PostgreSQL/object storage.

### Optional AI Interpreter

Получает только структурированный evidence pack и не имеет права менять policy verdict.

---

# 8. RunBundle — переносимый контракт прогона

## 8.1. Назначение

RunBundle — директория или ZIP с immutable input area и derived outputs. Он отделяет факт прогона от места, где выполняется анализ.

## 8.2. Рекомендуемая структура

```text
runbundle/
├── manifest.json
├── inputs/
│   ├── load/
│   │   ├── results.jtl.gz
│   │   └── simulation.log.gz
│   ├── metrics/
│   │   ├── victoria-metrics.snapshot.jsonl.zst
│   │   └── influx.snapshot.parquet
│   ├── database/
│   │   └── pg_profile.html
│   ├── apm/
│   │   └── trace-index.json
│   └── config/
│       ├── application.yaml
│       ├── resources.yaml
│       └── jvm-config.txt
├── normalized/
│   ├── load-events.parquet
│   ├── metrics.parquet
│   └── histograms.bin
├── outputs/
│   ├── verdict.json
│   ├── analysis.json
│   ├── fingerprint.json
│   └── report.html
└── provenance/
    ├── input-hashes.json
    ├── modules.json
    └── execution.json
```

## 8.3. Manifest

Минимальные поля:

```json
{
  "schema_version": "1.0",
  "run_id": "payment-2026-08-18-042",
  "application": "payment-api",
  "scenario": "capacity",
  "started_at": "2026-08-18T10:00:00Z",
  "ended_at": "2026-08-18T11:00:00Z",
  "run_class": "official",
  "source": "jenkins",
  "application_version": "2.17.0",
  "git_sha": "...",
  "load_tool": "jmeter",
  "metadata_origin": {
    "application_version": "ci",
    "time_window": "artifact",
    "stages": "inferred"
  }
}
```

## 8.4. Immutable и derived области

- `inputs/` после создания bundle не изменяется.
- `normalized/` и `outputs/` могут пересоздаваться новым `AnalysisRun`.
- Любое исправление входного факта создаёт новый bundle revision, а не тихо переписывает старый.

## 8.5. Re-analysis

Повторный анализ не должен менять исходный policy verdict задним числом без явной новой версии результата. Он создаёт отдельную запись:

```text
AnalysisRun = inputs + module set + configs + baseline set + engine version
```

---

# 9. Контракт модулей

## 9.1. Типы модулей

| Тип | Назначение | Пример |
|---|---|---|
| `source` | Получить и snapshot данные | VictoriaMetrics connector |
| `parser` | Разобрать артефакт | JMeter JTL parser |
| `normalizer` | Привести к канонической схеме | pod → workload aggregation |
| `validator` | Проверить пригодность данных | generator validity |
| `gate` | Выдать policy verdict | SLA/baseline rules |
| `detector` | Найти атомарное отклонение | change-point detector |
| `domain-analyzer` | Предметный анализ | pg_profile module |
| `correlator` | Найти связи и последовательность | lagged partial correlation |
| `synthesizer` | Объединить findings | incident grouper |
| `renderer` | Представить результат | HTML/Confluence exporter |
| `interpreter` | Сформировать narrative | optional LLM module |

## 9.2. Module manifest

```yaml
apiVersion: ltv.io/v1
kind: AnalysisModule
metadata:
  id: postgres.pg-profile
  version: 1.0.0
  title: PostgreSQL pg_profile analysis
spec:
  type: domain-analyzer
  requires:
    artifacts:
      - type: pg_profile_html
        min: 1
  optional:
    metrics:
      - db_cpu
      - disk_latency
  produces:
    - findings
    - evidence
    - recommendations
  failurePolicy: skip
  deterministic: true
  config:
    thresholds: thresholds.yaml
    knowledge: knowledge/
```

## 9.3. Статусы выполнения

| Статус | Смысл |
|---|---|
| `SUCCESS` | Модуль полностью отработал |
| `DEGRADED` | Результат есть, но не хватило optional inputs |
| `SKIPPED` | Preconditions не выполнены; это ожидаемое состояние |
| `FAILED` | Ошибка при наличии всех обязательных inputs |
| `BLOCKED` | Не отработала обязательная upstream dependency |

## 9.4. Изоляция отказов

- `renderer` не может изменить findings;
- ошибка PostgreSQL-модуля не блокирует JMeter verdict;
- ошибка AI не меняет deterministic output;
- timeout одного enrichment-модуля не должен останавливать остальные;
- gate-модуль может блокировать только заявленный policy verdict.

## 9.5. Состав переносимого module pack

```text
module-pack/
├── module.yaml
├── package/
├── schemas/
├── thresholds.yaml
├── knowledge/
├── templates/
└── tests/
    └── fixtures/
```

Knowledge хранится как версионируемые данные, а не как скрытый промпт.

---

# 10. Каноническая модель результата

## 10.1. Разделение статусов

Один статус не должен смешивать валидность, policy gate и полноту анализа.

```text
run_validity: VALID | DEGRADED | INVALID
policy_verdict: PASS | FAIL | NO_POLICY | NO_VERDICT
analysis_coverage: capability map + module statuses
```

### `INVALID`

Входные факты прогона невозможно надёжно интерпретировать.

### `NO_VERDICT`

Policy существует, но обязательных для него данных недостаточно или генератор не обеспечил валидный режим.

### `NO_POLICY`

Анализ выполнен, но пользователь не предоставил SLA/baseline policy. Это не ошибка.

## 10.2. Finding

Finding — атомарное проверяемое наблюдение.

```json
{
  "id": "cpu.throttling.change-point",
  "module": "openshift.cpu",
  "severity": "high",
  "status": "observed",
  "interval": {
    "from": "2026-08-18T10:22:30Z",
    "to": "2026-08-18T10:25:10Z"
  },
  "entities": ["deployment/payment-api"],
  "observed": 0.31,
  "expected": "0.02..0.08",
  "evidence": ["ev-42", "ev-43"],
  "rule_version": "1.2.0"
}
```

## 10.3. Evidence

Evidence содержит:

- ссылку на входной artifact/hash;
- исходный или нормализованный query;
- временной интервал;
- значения и единицы;
- метод агрегации;
- quality flags;
- ссылку на выбранный граф/таблицу.

## 10.4. Incident

Incident объединяет связанные findings и отвечает на практический вопрос пользователя.

```json
{
  "id": "incident-07",
  "title": "CPU saturation приложения на ступени 320 RPS",
  "impact": {
    "transactions": ["create-payment"],
    "p95_delta_pct": 44,
    "error_delta_pp": 2.1
  },
  "interval": {
    "from": "...",
    "to": "..."
  },
  "candidate_subsystem": "application-runtime",
  "confidence": "medium",
  "finding_ids": ["..."],
  "negative_evidence": [
    "DB latency remained within baseline",
    "generator CPU remained below validity threshold"
  ],
  "next_checks": [
    "Проверить CPU limits и throttling pod'ов",
    "Проверить очередь executor/thread pool"
  ]
}
```

`confidence` здесь означает качество и согласованность evidence, а не математическую вероятность истинной первопричины.

## 10.5. Verdict и Analysis

### `verdict.json`

Содержит только стабильный policy contract:

- run validity;
- SLA/baseline checks;
- PASS/FAIL/NO_POLICY/NO_VERDICT;
- краткие детерминированные причины;
- provenance.

### `analysis.json`

Содержит расширяемую аналитику:

- findings;
- incidents;
- correlations;
- trends;
- module coverage;
- historical matches;
- RCA candidates.

Так новая версия PCA или correlation module не требует менять канонический verdict.

---

# 11. Аналитический pipeline

```text
Discover inputs
  → Parse
  → Normalize
  → Validate
  → Policy gate
  → Independent detectors
  → Correlation and temporal ordering
  → Incident synthesis
  → Historical comparison
  → Render/export
  → Optional AI interpretation
```

## 11.1. Parse и normalize

### Нагрузочные результаты

- сохранять иерархию Gatling groups/requests и JMeter Transaction Controllers/samplers;
- использовать streaming parsing для больших логов;
- восстанавливать фактическое окно теста;
- рассчитывать histogram-compatible latency representation;
- не усреднять готовые p95/p99 между pod’ами или интервалами.

### Метрики

- counters преобразовывать в rate/delta;
- обрабатывать resets;
- выравнивать временные ряды на каноническую сетку;
- сохранять факт пропуска данных;
- агрегировать динамические pod’ы до стабильных workload-признаков;
- отдельно хранить sum, max, median, CV и top-1 share там, где важен дисбаланс.

## 11.2. Validity

Базовые проверки:

- читаемость артефакта;
- непротиворечивое время;
- достаточная длительность интервала;
- plan-vs-fact при наличии профиля;
- ошибки/ограничения генератора;
- пересечение с другим прогоном;
- clock skew;
- пустые/оборванные данные;
- качество metric snapshot.

## 11.3. Policy gate

Gate остаётся детерминированным:

- SLA latency/error/throughput;
- обязательные транзакции;
- baseline comparison;
- generator validity;
- правила official/adhoc run class.

ML и LLM не участвуют в PASS/FAIL.

## 11.4. Базовые detectors

1. robust threshold/MAD;
2. change-point detection;
3. plan-vs-fact deviation;
4. per-transaction regression;
5. resource saturation;
6. pod imbalance;
7. pool/queue saturation;
8. error fingerprinting;
9. PostgreSQL/JVM/Kafka playbooks при наличии соответствующих модулей.

## 11.5. Корреляционная обработка

Корреляционный слой должен работать не по сырым графикам «всё со всем», а по ограниченному набору логических признаков.

### Обязательные правила

- учитывать фактический RPS, concurrency, stage, replicas и request mix;
- использовать остатки модели ожидаемого поведения или partial correlation;
- искать лаги только в технически разумном диапазоне;
- применять коррекцию множественных проверок;
- требовать минимальный effect size;
- по возможности подтверждать связь на нескольких прогонах;
- использовать известную топологию для ограничения допустимых направлений.

### Практический MVP

- partial Spearman;
- lagged cross-correlation;
- change-point ordering;
- robust z/MAD;
- шаблонные цепочки из metric packs;
- отрицательные evidence.

Пример шаблона:

```text
CPU throttling
  → request queue
  → latency residual
  → timeout rate
```

Модуль оценивает каждое звено, но не объявляет причинность доказанной.

## 11.6. Incident synthesis

Findings объединяются, если выполняется несколько условий:

- интервалы перекрываются или следуют с допустимым лагом;
- затронут один SLI/transaction;
- метрики относятся к одной подсистеме или связанной topology path;
- findings образуют известный symptom pattern;
- нет сильного противоречащего evidence.

Overview показывает ограниченное число инцидентов; остальные доступны в details.

## 11.7. История

Для каждого `run × segment × transaction` строится fingerprint:

```text
achieved_rps
p50/p95/p99
error_rate
latency_residual
cpu_per_request
wal_per_request
gc_pause_per_request
pool_wait
pod_imbalance
change_point_count
multivariate_score
capacity_knee
correlation_graph_signature
```

Локально fingerprints могут храниться в каталоге/SQLite. Сервер добавляет общий каталог и cohort selection.

---

# 12. Статистика и ML: приоритеты

## 12.1. В MVP

- robust statistics;
- deterministic baselines;
- change points;
- partial/lagged correlations;
- простые load-conditioned models;
- rule/playbook modules;
- incident grouping.

## 12.2. После накопления истории

- quantile regression или GAM по RPS;
- EWMA/CUSUM по версиям;
- robust Mahalanobis distance;
- PCA T²/Q;
- Graphical Lasso;
- clustering run fingerprints;
- nearest historical incident matching;
- Isolation Forest в shadow mode.

## 12.3. Поздний экспериментальный слой

- PCMCI+ на отобранных каналах;
- graph anomaly models;
- temporal autoencoders;
- transfer learning между схожими приложениями.

Deep learning не является обязательным условием ценности продукта. Он добавляется только при наличии чистой сопоставимой истории и понятной схемы объяснения.

---

# 13. UX: вердикт вместо стены графиков

## 13.1. Главный экран прогона

Порядок информации:

1. **Run validity** — можно ли доверять данным.
2. **Policy verdict** — PASS/FAIL/NO_POLICY/NO_VERDICT.
3. **Coverage** — какие capabilities были доступны.
4. **Top incidents** — максимум 3–7 карточек.
5. **What changed** — отличие от baseline/истории.
6. **Skipped/degraded modules** — почему анализ неполный.
7. **Evidence explorer** — графики, таблицы и raw references.

## 13.2. Карточка инцидента

Карточка должна отвечать на шесть вопросов:

- что пострадало;
- насколько сильно;
- когда началось;
- что произошло раньше;
- какая подсистема наиболее вероятна;
- что проверить дальше.

Пример:

```text
HIGH · CPU saturation приложения

p95 create-payment: +44% к ожидаемому уровню
Начало: 14:22:30, stage 320 RPS

До деградации:
  +25 s  CPU throttling
  +11 s  executor queue

Не подтверждено:
  DB latency — в baseline
  генератор — валиден

Уверенность: medium
```

## 13.3. Графики

- На overview не показывать полный dashboard.
- Для каждого incident автоматически выбирать 2–5 наиболее информативных overlays.
- Показывать только проблемный интервал с контекстом до/после.
- Давать ссылку «Открыть исходный дашборд».
- Не считать PNG-скриншот источником истины, если доступен raw time series.

## 13.4. Coverage

Coverage показывается как capability matrix, а не только одним процентом:

```text
Load results       COMPLETE
Generator health   COMPLETE
OpenShift metrics  COMPLETE
JVM                DEGRADED: allocation rate missing
PostgreSQL         SKIPPED: pg_profile not supplied
APM                SKIPPED: connector not configured
History            8 comparable runs
```

## 13.5. Локальный wizard

Локальный UI должен поддерживать:

- drag-and-drop;
- auto-detect входов;
- выбор baseline;
- предпросмотр найденных capabilities;
- `Analyze` без ручной формы на десятки полей;
- экспорт self-contained HTML/ZIP;
- временные сессии и автоочистку;
- bind к `127.0.0.1` по умолчанию.

---

# 14. Базовые и дополнительные модули

## 14.1. Foundation pack — обязателен

| Модуль | Вход | Результат |
|---|---|---|
| `load.jmeter` | JTL CSV/XML | transactions, latency, errors, throughput |
| `load.gatling` | simulation.log | groups/requests, latency, errors, throughput |
| `run.validity` | parsed load facts | validity findings |
| `policy.sla` | optional SLA profile | PASS/FAIL/NO_POLICY |
| `compare.baseline` | optional baseline bundle | deltas/regressions |
| `incident.core` | findings | grouped incidents |
| `export.json-html` | canonical output | portable report |

## 14.2. Observability packs

### OpenShift workload pack

- CPU usage/request/limit;
- CPU throttling;
- memory working set/limit;
- restarts;
- replica count;
- pod imbalance;
- network;
- HPA events при доступности.

### JVM pack

- heap/oldgen;
- allocation rate;
- GC pauses/frequency;
- threads;
- JVM flags и container limits;
- guardrails для рекомендаций.

### PostgreSQL metrics pack

- CPU/I/O;
- connections/pool waits;
- locks/deadlocks;
- WAL/checkpoints;
- cache hit;
- replication lag.

### Kafka pack

- producer/consumer rates;
- request latency;
- consumer lag;
- under-replicated partitions;
- broker saturation.

## 14.3. Artifact-domain packs

- `postgres.pg-profile`;
- JVM configuration checker;
- deployment/resources checker;
- configuration diff;
- log/error fingerprint analyzer.

## 14.4. Advanced packs

- history/trend;
- multivariate anomaly;
- APM locator;
- code-context pack;
- AI narrative;
- Confluence/email/Jira exporters.

---

# 15. Переиспользование решений из `pg_profile_checks`

Репозиторий демонстрирует полезный шаблон самостоятельного аналитического модуля:

```text
input artifact
  → deterministic Python analysis
  → thresholds + YAML knowledge
  → findings/advisor JSON
  → brief/Confluence
  → optional LLM prompt
```

## 15.1. Что переносится как архитектурный паттерн

### Самодостаточный artifact analyzer

pg_profile HTML содержит нужные данные и может анализироваться без подключения к внешней БД. Это напрямую соответствует portable module philosophy.

### Детерминированные findings до ИИ

Python формирует findings и рекомендации из versioned YAML. LLM используется для текста, а не для обнаружения фактов.

### Knowledge-as-data

`thresholds.yaml`, `recommendations.yaml`, `symptom_playbook.yaml`, GUC guidance и tuning knowledge должны стать содержимым module pack, а не зашиваться в промпты.

### Один orchestrator для CLI и UI

Локальный web-интерфейс вызывает тот же анализатор, что CLI. Это соответствует принципу «один движок — несколько оболочек».

### Bundled optional runtime

Встроенный JVM runtime показывает, как предметный модуль может поставляться вместе с ядром, не требуя отдельной установки соседнего проекта.

### Session isolation и cleanup

Временные каталоги на сессию и TTL-политика подходят для local web mode LT Verdict.

## 15.2. Что следует адаптировать

- привести outputs к общему `Finding/Evidence/Incident` contract;
- разделить большой orchestration layer на module lifecycle;
- сделать ввод контекстных метрик optional enrichment, а не ручной обязательной формой;
- отделить renderer/Confluence от аналитики;
- добавить provenance входного HTML и knowledge versions;
- подключать модуль автоматически при обнаружении `pg_profile.html`.

## 15.3. Предлагаемый PostgreSQL module

```text
postgres.pg-profile
├── parser
├── health checks
├── run comparison
├── NT vs PROD comparison
├── symptom playbooks
├── recommendations
└── renderers
```

Этот модуль может стать первым эталоном внешнего domain pack.

---

# 16. Переиспользование решений из `lt_cycle_task`

Репозиторий полезен как пример интеграции анализа в существующий НТ-контур без полного владения запуском теста.

## 16.1. Что переносится

### Отделение Start от Analyze

Удалённый тест и последующий анализ связаны файлами состояния и tarball. LT Verdict также должен уметь работать как независимый post-run step и не требовать постоянной связи с генератором.

### Парсеры штатных артефактов

`gatling_parser.py` извлекает фактические request labels, latency, errors и окно теста из `simulation.log`. Это хороший исходный материал для `load.gatling` adapter.

### Сравнение прогонов

`compare_runs.py` показывает минимальный portable contract: текущий CSV + предыдущий CSV → delta/summary. В LT Verdict он расширяется до canonical baseline module.

### Profiles/specs как optional adapters

Профили, SDD specs и генерация properties полезны для точного plan-vs-fact, но не должны быть обязательными для базового анализа.

### Backward-compatible defaults

Если profile отсутствует, существующее поведение Gatling-сценария сохраняется. Такой же принцип нужен всем интеграциям LT Verdict.

### Grafana renderer как evidence exporter

Рендер выбранных панелей полезен как optional evidence module и для Confluence, но не должен быть основным аналитическим источником.

### Config-as-code

Связка profiles, selectors и Jenkins parameters подтверждает, что app-specific mapping должен жить отдельно от сценариев и ядра.

## 16.2. Что не следует делать обязательным

- codemod/переписывание injection и weights ради подключения LT Verdict;
- Jenkins-specific orchestration внутри core;
- hard-coded application/datasource maps в коде;
- обязательный Confluence pipeline;
- скриншоты всех Grafana-панелей как основной отчёт;
- дублирование профиля нагрузки в нескольких местах.

## 16.3. Рекомендуемая роль существующих инструментов

```text
lt_cycle_task integrations
  → создают/собирают RunBundle
  → запускают ltv analyze
  → публикуют portable outputs
```

Их не обязательно поглощать ядром. Они могут стать отдельным integration pack.

---

# 17. Конфигурация и стоимость сопровождения

## 17.1. Четыре слоя конфигурации

### Platform defaults

Общие metric packs, thresholds, module defaults и schemas.

### Environment profile

Endpoint/credentials provider, общие labels и особенности конкретного OpenShift/TSDB-контура.

### Application profile

Только selectors, topology aliases и optional overrides.

### Scenario/policy profile

SLA, critical transactions, stage manifest и comparability policy. Не должен дублировать код сценария.

## 17.2. Автообнаружение

Команды:

```text
ltv init <run-directory>
ltv doctor --application payment-api
ltv analyze <run-directory>
```

`init` должен:

- определить JMeter/Gatling;
- найти потенциальные результаты и configs;
- предложить selectors;
- сгенерировать минимальный profile только при необходимости.

`doctor` должен до теста показать:

```text
Foundation          7/7 ready
OpenShift pack      8/9 metrics available
JVM pack            DEGRADED: allocation rate missing
PostgreSQL pack     SKIPPED: no source configured
Expected verdict    available
Expected RCA depth  medium
```

## 17.3. Metric packs

Команда приложения выбирает pack, а не описывает каждую метрику:

```yaml
metric_packs:
  - openshift-workload
  - jvm-micrometer
  - hikari
  - postgres-exporter
```

Pack определяет:

- logical metric id;
- PromQL/Influx query templates;
- type/unit;
- selectors;
- aggregation;
- missing-data policy;
- subsystem;
- normalization;
- supported detectors;
- default lag window.

## 17.4. Capability-based execution

Пример:

```text
GC → p99 detector
requires: p99 + gc_pause
missing gc_pause → SKIPPED

Pool saturation detector
requires: pool_active + pool_max + pool_wait + latency
all present → SUCCESS
```

Добавление нового детектора не должно расширять глобальный обязательный набор данных.

## 17.5. Распределение ответственности

| Зона | Владелец |
|---|---|
| Core/module API | LT Verdict team |
| Standard metric packs | LT Verdict/platform team |
| Environment endpoints and credentials | Platform team |
| Application selectors | Команда приложения или platform onboarding |
| SLA/policy | Performance engineer/product owner |
| Test scripts | Существующая команда НТ; без обязательных изменений |

---

# 18. Локальный и серверный режимы

## 18.1. Local CLI

Минимальная форма продукта:

```bash
ltv analyze ./gatling-results/run-42 --output ./ltv-out
ltv analyze ./current --baseline ./previous
```

Требования:

- offline operation;
- streaming parsers;
- self-contained HTML;
- стабильные exit codes;
- отсутствие обязательной БД;
- secrets только через env/credential files.

## 18.2. Local Web

```bash
ltv serve --local
```

UI является тонкой оболочкой над core API. Сессии изолированы, очищаются по TTL, сервер по умолчанию слушает loopback.

## 18.3. Portable container

Один OCI image должен поддерживать:

- CLI job;
- local/server web mode;
- mounted bundles/configs;
- optional extras без обязательного кластера.

## 18.4. Server mode

Сервер добавляет:

- каталог прогонов;
- auth/RBAC;
- shared baseline selection;
- scheduling/connectors;
- history/trends;
- team configuration;
- audit log;
- import/export RunBundle.

Сервер не должен содержать отдельную реализацию verdict engine.

## 18.5. Хранилище

| Возможность | Local | Server |
|---|---|---|
| Raw bundle | Filesystem | Object storage/filesystem |
| Metadata | JSON/SQLite | PostgreSQL |
| Fingerprints | JSON/SQLite | PostgreSQL |
| Re-analysis | Локально | Queue/job runner |
| Retention | User/TTL | Policy |

---

# 19. Безопасность и управление данными

## 19.1. Offline by default

Local mode не выполняет внешние вызовы без явного включения connector или AI module.

## 19.2. Секреты

- не сохраняются в RunBundle;
- передаются через environment, OS keyring, Vault/Jenkins credentials или server secret provider;
- query snapshots очищаются от токенов и headers.

## 19.3. Минимизация данных

- request/response bodies не собираются по умолчанию;
- SQL может редактироваться/хешироваться политикой;
- APM payload ограничивается ссылками и выбранными spans;
- AI получает компактный evidence pack, а не все сырые metrics.

## 19.4. Аудит

Для каждого результата сохраняются:

- input hashes;
- module versions;
- config hashes;
- query templates и resolved selectors;
- baseline run ids;
- timestamps;
- module statuses/errors;
- output hash.

---

# 20. Функциональные требования

## 20.1. Core и portable execution

| ID | Требование | Приоритет |
|---|---|---|
| FR-CORE-01 | Один core используется CLI, local UI и server | MUST |
| FR-CORE-02 | Анализ JTL/simulation.log без изменения сценария | MUST |
| FR-CORE-03 | Создание/import/export RunBundle | MUST |
| FR-CORE-04 | Детерминированные canonical JSON outputs | MUST |
| FR-CORE-05 | Re-analysis с отдельным provenance | MUST |
| FR-CORE-06 | Streaming parsing больших load logs | SHOULD |

## 20.2. Модульность

| ID | Требование | Приоритет |
|---|---|---|
| FR-MOD-01 | Versioned module manifest и dependency contract | MUST |
| FR-MOD-02 | `SUCCESS/DEGRADED/SKIPPED/FAILED/BLOCKED` | MUST |
| FR-MOD-03 | Optional module failure не ломает core | MUST |
| FR-MOD-04 | Module packs с thresholds/knowledge/tests | MUST |
| FR-MOD-05 | Установка built-in и external packs | SHOULD |
| FR-MOD-06 | Capability preview через doctor | SHOULD |

## 20.3. Анализ

| ID | Требование | Приоритет |
|---|---|---|
| FR-ANA-01 | Run validity отдельно от policy verdict | MUST |
| FR-ANA-02 | SLA/baseline deterministic gate | MUST |
| FR-ANA-03 | Findings с evidence references | MUST |
| FR-ANA-04 | Incident synthesis | MUST |
| FR-ANA-05 | Change-point и basic correlation module | MUST |
| FR-ANA-06 | Coverage/capability matrix | MUST |
| FR-ANA-07 | Run fingerprint/history interface | SHOULD |
| FR-ANA-08 | Load-conditioned historical models | POST-MVP |

## 20.4. UX и outputs

| ID | Требование | Приоритет |
|---|---|---|
| FR-UX-01 | Overview показывает не более 3–7 top incidents | MUST |
| FR-UX-02 | Evidence раскрывается до исходного ряда/артефакта | MUST |
| FR-UX-03 | Self-contained HTML + JSON | MUST |
| FR-UX-04 | Local drag-and-drop wizard | SHOULD |
| FR-UX-05 | Ссылки на исходные Grafana/APM views | SHOULD |
| FR-UX-06 | Confluence/JUnit/email exporters | SHOULD |

## 20.5. Server

| ID | Требование | Приоритет |
|---|---|---|
| FR-SRV-01 | Import локального RunBundle без потери provenance | MUST |
| FR-SRV-02 | Каталог прогонов и baseline assignment | MUST |
| FR-SRV-03 | Shared history/fingerprints | SHOULD |
| FR-SRV-04 | Auth/RBAC/audit | SHOULD для multi-team |
| FR-SRV-05 | Job queue для re-analysis | SHOULD |

---

# 21. Нефункциональные требования

| ID | Требование |
|---|---|
| NFR-01 | Одинаковые inputs/configs/versions дают одинаковый deterministic output |
| NFR-02 | Local artifact-only mode не требует сети и внешней БД |
| NFR-03 | Серверный режим не изменяет семантику core verdict |
| NFR-04 | Один failed enrichment module не останавливает весь анализ |
| NFR-05 | Все outputs имеют schema version |
| NFR-06 | Все source snapshots имеют hash и acquisition metadata |
| NFR-07 | Local UI слушает `127.0.0.1` по умолчанию |
| NFR-08 | Парсеры поддерживают bounded-memory/streaming режим для крупных логов |
| NFR-09 | Конфигурация не содержит секретов в открытом виде |
| NFR-10 | Module pack имеет fixture tests и compatibility check |

Производительные SLO следует зафиксировать после прототипа на реальных размерах данных. До измерений не стоит обещать конкретное время анализа, но архитектура должна исключать обязательную загрузку всего JTL в память.

---

# 22. MVP и этапы

## Phase 0. Contracts and spike

- RunBundle v1;
- Finding/Evidence/Incident schemas;
- module manifest;
- JMeter/Gatling fixture corpus;
- prototype CLI;
- benchmark representative logs.

## Phase 1. Portable local foundation

- JMeter/Gatling parsers;
- run validity;
- SLA/NO_POLICY;
- manual baseline comparison;
- canonical verdict/analysis JSON;
- incident overview;
- self-contained HTML;
- local CLI;
- import/export bundles.

**Критерий:** существующий тест анализируется без изменения скрипта.

## Phase 2. Observability and compact RCA

- VictoriaMetrics/Prometheus source;
- Influx source;
- OpenShift/JVM basic packs;
- canonical time grid;
- change points;
- partial/lagged correlations;
- incident grouping;
- evidence charts;
- `ltv init/doctor`.

## Phase 3. Domain modules and local wizard

- adapter `pg_profile_checks`;
- JVM/config module;
- drag-and-drop local UI;
- sessions/TTL;
- Confluence exporter;
- Grafana evidence exporter.

## Phase 4. Server shell and history

- API/UI over same core;
- catalog and object storage;
- PostgreSQL metadata store;
- shared baseline assignment;
- fingerprints/trends;
- re-analysis jobs;
- auth/RBAC as required pilot environment.

## Phase 5. Advanced interpretation

- load-conditioned models;
- EWMA/CUSUM;
- multivariate detectors in shadow mode;
- APM locator;
- structured AI narrative;
- historical incident matching.

## Что исключить из первого MVP

- deep-learning anomaly models;
- autonomous code changes;
- full causal discovery;
- обязательную APM-интеграцию;
- универсальный topology discovery;
- собственный test scheduler;
- полный replacement Grafana;
- распределённую микросервисную архитектуру.

---

# 23. Предварительная оценка трудоёмкости

Оценка ROM зависит от качества повторного использования существующих парсеров и UI.

| Scope | Оценка |
|---|---:|
| Contracts + local artifact prototype | 2–4 FTE-недели |
| Portable local foundation | ещё 5–8 FTE-недель |
| VM/Influx + metric packs + incident synthesis | ещё 5–8 FTE-недель |
| pg_profile/JVM adaptation + local wizard | ещё 3–6 FTE-недель |
| Minimal server/history | ещё 4–7 FTE-недель |
| Advanced APM/AI/history analytics | ещё 6–12 FTE-недель |

Практический вывод:

- полезный artifact-only продукт возможен существенно раньше полной платформы;
- server-capable MVP разумно планировать отдельно от local foundation;
- попытка включить APM, code agent и сложный ML в первый релиз резко повышает риск и откладывает проверку основной гипотезы.

---

# 24. Метрики успеха продукта

## 24.1. Adoption и upkeep

- не менее 80% пилотных существующих сценариев получают базовый анализ без правки кода;
- повторный прогон не требует ручного ввода metadata;
- стандартный app onboarding требует selectors, а не списка PromQL;
- изменение общего detector не требует изменений сценариев.

## 24.2. Снижение ручного анализа

- overview содержит ограниченное число incidents;
- инженер открывает raw dashboard только для выбранного evidence;
- время до первой обоснованной гипотезы уменьшается относительно текущего процесса;
- уменьшается число пропущенных подтверждённых проблем на пилотных ретроспективах.

## 24.3. Доверие

- каждый incident содержит evidence;
- skipped modules видимы;
- одинаковый RunBundle воспроизводит core output локально и на сервере;
- false-positive/false-negative feedback можно сохранить и связать с module version.

## 24.4. Техническая устойчивость

- optional connector outage не лишает пользователя load verdict;
- старый bundle можно re-analyze новой версией;
- новый module pack проходит compatibility/fixture tests;
- raw data retention не требуется для долговременного trend view, если сохранён fingerprint.

---

# 25. Риски и меры

| Риск | Последствие | Мера |
|---|---|---|
| Слишком много обязательных метрик | Высокий onboarding, постоянный `NO_VERDICT` | Capability-based modules, optional inputs |
| Анализ всех series | Шум и ложные связи | Curated metric packs, topology constraints |
| UI превращается в Grafana | Возвращается исходная боль | Incident-first overview, evidence on demand |
| Сервер диктует архитектуру | Local mode становится тяжёлым | Core-first, storage abstraction, RunBundle |
| Module monolith разрастается | Сложно тестировать и обновлять | Versioned module contract и fixture tests |
| LLM создаёт убедительный ложный RCA | Потеря доверия | Structured evidence only, verdict immutable |
| Inferred stages ошибочны | Неверное сравнение режимов | Mark `INFERRED`, conservative claims, optional manifest |
| Нестандартные labels | Дорогой onboarding | Auto-discovery, aliases, app-level overrides |
| История несопоставима | Ложные trends | Comparability policy и cohort metadata |
| Код из reference repos трудно объединить | Технический долг | Адаптеры к contract, не механическое слияние |

---

# 26. Критерии приёмки v0.6 MVP

1. Один и тот же RunBundle даёт идентичный `verdict.json` в CLI и server shell.
2. Базовый JMeter JTL анализируется без изменения JMX.
3. Базовый Gatling `simulation.log` анализируется без изменения Simulation/Scenario.
4. Отсутствие VictoriaMetrics/Influx/APM не мешает load-only analysis.
5. Отсутствие optional JVM/DB metric отключает только зависимый detector.
6. Overview показывает validity, policy verdict, coverage и не более семи top incidents.
7. Каждый finding/incident имеет evidence reference и module version.
8. Пользователь может скачать self-contained HTML и RunBundle.
9. `NO_VERDICT` имеет явный перечень только действительно блокирующих причин.
10. PostgreSQL artifact module может быть подключён без изменения core.
11. Local mode работает без внешней БД и без исходящих сетевых запросов.
12. Server импортирует локальный bundle и выполняет re-analysis без изменения inputs.
13. `ltv doctor` заранее показывает доступные и пропущенные capabilities.
14. Новый detector добавляется module pack и fixture tests, без правки JMeter/Gatling scripts.

---

# 27. Изменения относительно v0.5

## Сохраняется

- immutable run artifacts;
- provenance и hashes;
- deterministic verdict;
- корректная latency representation;
- validity gates;
- manual baseline как безопасный первый вариант;
- config-as-code;
- отделение фактов от AI interpretation;
- возможность snapshot VictoriaMetrics;
- `NO_VERDICT` при недостоверности обязательных данных.

## Меняется

| v0.5 | v0.6 |
|---|---|
| Серверная платформа как основной образ | Portable core, сервер — одна из оболочек |
| Относительно монолитный pipeline | Versioned module contract |
| Фазы ramp/steady/ramp-down | General segments/stages + inference |
| Расширенные findings рядом с verdict | `verdict.json` отдельно от `analysis.json` |
| Неполнота источников склонна блокировать анализ | Missing optional capability снижает coverage |
| APM/code agent близко к основному pipeline | Сначала incident/evidence, затем optional APM/AI |
| История как функция сервера | Fingerprint и baseline доступны локально |
| Интеграция через регистрацию | Artifact-only и wrapper-first onboarding |

## Уточняется

- `re-verdict` применяется к policy contract;
- `re-analysis` применяется к расширяемым modules;
- systemic correlation/RCA появляется раньше code agent;
- module packs, а не скрипты, несут metadata о метриках;
- Grafana — evidence source/launcher, а не основной output.

---

# 28. Продуктовые решения, которые следует зафиксировать

## Принятые решения

1. **Portable core является центром архитектуры.**
2. **Artifact-only анализ входит в первый usable release.**
3. **Сценарии не переписываются ради подключения базовых функций.**
4. **Модули активируются по capabilities.**
5. **Optional input не расширяет глобальный validity gate.**
6. **Детерминированный verdict отделён от расширяемого analysis.**
7. **Главная UX-сущность — incident, а не dashboard.**
8. **RunBundle — контракт между local, CI и server.**
9. **Knowledge хранится в versioned YAML/data packs.**
10. **ИИ не участвует в PASS/FAIL.**
11. **Микросервисы не являются обязательным способом модульности.**
12. **History строится на fingerprints и comparable cohorts.**

## Решения для последующего уточнения

- язык/формат external plugin API после прототипа;
- SQLite против embedded analytical DB для local history;
- минимальный auth profile server edition;
- точная политика automatic baseline selection;
- формат sandbox/изоляции сторонних modules;
- набор metric packs для первого пилота;
- лицензирование и способ прямого переноса кода из reference repositories.

---

# 29. Рекомендуемый первый пилот

## Контур

- 1 команда JMeter + Influx;
- 1 команда Gatling + VictoriaMetrics;
- приложения в OpenShift;
- один PostgreSQL-сценарий с pg_profile;
- локальный и серверный анализ одного RunBundle.

## Проверяемые гипотезы

1. Получают ли инженеры полезный результат без правки сценариев?
2. Сокращает ли incident-first report ручной просмотр графиков?
3. Достаточны ли standard OpenShift/JVM packs?
4. Какие данные чаще всего отсутствуют?
5. Насколько часто correlation chain совпадает с выводом эксперта?
6. Какие findings являются шумом?
7. Насколько полезен локальный режим без history?
8. Нужен ли пользователям full server до появления advanced analytics?

## Порядок

1. Artifact-only shadow analysis.
2. Подключение TSDB snapshots.
3. Сравнение выводов с ручным разбором инженера.
4. Настройка metric packs по реальным gaps.
5. Подключение pg_profile module.
6. Только после этого — server history и AI narrative.

---

# 30. Итоговая формулировка продукта

> **LT Verdict — портативный модульный движок анализа нагрузочных тестов, который без обязательной переделки JMeter/Gatling-сценариев преобразует штатные результаты и доступные observability-данные в воспроизводимый verdict, небольшой список приоритетных инцидентов и проверяемые evidence. Он одинаково работает локально, в CI и на сервере, а дополнительные источники и предметные анализаторы подключаются как независимые capabilities.**

Это позиционирование удерживает продукт вокруг реальной боли — человек не должен вручную просматривать десятки графиков и надеяться, что ничего не пропустил — и одновременно не превращает решение в тяжёлую обязательную платформу.

---

# Приложение A. Пример минимального application profile

```yaml
apiVersion: ltv.io/v1
kind: ApplicationProfile
metadata:
  id: payment-api
spec:
  selectors:
    namespace: payment-perf
    workload: payment-api
    apm_service: payment-service

  metricPacks:
    - openshift-workload
    - jvm-micrometer
    - hikari

  topology:
    dependencies:
      - id: payment-db
        type: postgres

  policy:
    profile: default-http-api
```

# Приложение B. Пример policy profile

```yaml
apiVersion: ltv.io/v1
kind: VerdictPolicy
metadata:
  id: default-http-api
spec:
  requiredTransactions:
    - create-payment
    - confirm-payment

  sla:
    errorRatePct: 1.0
    p95Ms: 500

  baseline:
    mode: manual
    requireComparableScenario: true

  validity:
    requireLoadArtifact: true
    requireGeneratorHealthForCapacityVerdict: true
    missingOptionalMetrics: degrade
```

# Приложение C. Пример канонического overview

```json
{
  "run_validity": "VALID",
  "policy_verdict": "FAIL",
  "coverage": {
    "load": "COMPLETE",
    "generator": "COMPLETE",
    "openshift": "COMPLETE",
    "jvm": "DEGRADED",
    "postgres": "SKIPPED",
    "apm": "SKIPPED",
    "history": "8 comparable runs"
  },
  "summary": {
    "top_incidents": 3,
    "affected_transactions": 2,
    "first_anomaly_at": "2026-08-18T10:22:30Z"
  },
  "incidents": [
    {
      "title": "CPU saturation приложения",
      "severity": "high",
      "confidence": "medium",
      "impact": "p95 +44%",
      "leading_signals": ["cpu_throttling", "executor_queue"],
      "negative_evidence": ["db_latency_normal", "generator_valid"]
    }
  ]
}
```

# Приложение D. Использованные исходные материалы

1. Исходный документ проекта: `prc-lt-verdict-v0.5.md`.
2. `pg_profile_checks`: <https://github.com/DelusionTea/pg_profile_checks>
   - `README.md`;
   - `analyze_pgprofile.py`;
   - `pgprofile_advisor.py`;
   - `thresholds.yaml`;
   - `knowledge/*.yaml`;
   - `ui/*`;
   - bundled `jvmcheck_runtime`.
3. `lt_cycle_task`: <https://github.com/DelusionTea/lt_cycle_task>
   - `ltAuto/gatling_parser.py`;
   - `ltAuto/compare_runs.py`;
   - `ltAuto/render_export.py`;
   - `profiles/*`;
   - `SDD/specs/*`;
   - `Jenkinsfile_NT_Start`;
   - `Jenkinsfile_NT_Analyze_Report`;
   - `docs/NT_PIPELINE_AI_CONTEXT.md`.

Репозитории использованы как reference implementations и источник архитектурных паттернов. Прямой перенос кода требует отдельного решения по владению, лицензированию, тестам и приведению к общему module contract.

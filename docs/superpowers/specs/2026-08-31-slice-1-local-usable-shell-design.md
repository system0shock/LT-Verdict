# Slice 1 — Local usable shell: дизайн

**Дата:** 2026-08-31

**Статус:** утверждён пользователем 2026-08-31

**Основание:**

- [PRC/PRD v0.6](../../../lt-verdict-prc-prd-v0.6.md);
- [local-first MVP delta design](2026-08-26-v06-local-mvp-delta-design.md);
- [план разработки v0.6](../../development-plan-v0.6.md);
- [ADR 0001](../../adr/0001-slice-0-public-contracts.md);
- принятый milestone-отчёт [Stage 0 / Slice 0](../../milestones/stage-0.md).

Историческая [`stage-1-spec.md`](../../stage-1-spec.md) не является
нормативной для этого среза. Её parser research допустимо использовать только
как evidence там, где оно не противоречит v0.6 и этому документу.

## 1. Цель

Slice 1 создаёт первый локально полезный LT Verdict: инженер одной командой
запускает loopback Web UI, передаёт один штатный JMeter/Gatling artifact и
optional policy, затем получает воспроизводимые validity, verdict, coverage,
основные метрики и проверяемое evidence.

Готовый срез обязан доказать один сквозной поток:

```text
JTL / simulation.log + optional policy
  -> streaming ingest и immutable RunBundle
  -> parse / normalize / metrics / policy gate
  -> analysis-result.v1
  -> одинаковое представление в CLI и Web UI
```

Slice 1 — локальный modular monolith, а не уменьшенная server platform.

## 2. Границы

### Входит

- `ltv ui` с backend только на `127.0.0.1` и обязательным Web UI;
- минимальный `ltv analyze <file>` поверх того же application core;
- filesystem RunBundle без обязательной database;
- streaming ingest одного файла размером до 4 GiB;
- JMeter JTL CSV и XML;
- Gatling OSS text `simulation.log` 3.9–3.12;
- Gatling OSS binary `simulation.log` 3.13–3.15.1;
- автоматическое определение формата и fail-closed для неизвестного формата;
- базовые latency, errors, throughput, sample counts и структура транзакций;
- отдельные `run_validity`, `policy_verdict` и `analysis_coverage`;
- новый public contract `policy.v1`, его JSON Schema, CLI validation, UI form,
  import/download и инструкции для человека и внешней нейросети;
- findings и inspectable evidence в существующем `analysis-result.v1`;
- sparse 1-second normalization как задел под будущие charts;
- bounded background analysis, progress, cancel и `BUSY` admission control;
- утверждённый desktop UI в светлой и тёмной темах;
- security fixtures для file/XML/browser trust boundaries;
- техническая и пользовательская документация Slice 1.

### Не входит

- SQLite, PostgreSQL, общий каталог и долговременная история UI;
- baseline, сравнение прогонов и trends;
- реальные charts, static chart renderer и chart dependency;
- перенос или сохранение расположения widgets, drag-and-drop dependency;
- Jenkins, remote sources и автоматическое скачивание artifacts;
- capacity mode, JVM/OpenShift, PostgreSQL, OpenSearch и прочие packs;
- HTML/AsciiDoc/Confluence exporters и публикация;
- встроенный AI, GigaCode runner или outbound network calls;
- server bind, RBAC, tenants и shared policy catalog;
- response bodies, response headers и raw XML в UI или RunBundle.

Запрос `capacity_step` в Slice 1 завершается
`UNSUPPORTED_ANALYSIS_MODE`; он не эмулируется как standard run.

Charts, widget movement и layout persistence имеют одного владельца — Slice 8.
До него DnD requirement остаётся deferred и не реализуется частично.

## 3. Выбранная архитектура

```text
┌──────────────── Vue 3 + TypeScript ────────────────┐
│ upload · policy · progress · verdict · evidence   │
└────────────────────────┬───────────────────────────┘
                         │ same-origin local API
┌────────────────────────▼───────────────────────────┐
│ Ktor + Netty loopback shell                       │
│ session · CSRF · upload · jobs · static assets    │
└───────────────┬───────────────────┬────────────────┘
                │                   │ bounded executor
┌───────────────▼────────────┐  ┌───▼────────────────┐
│ Kotlin application core   │  │ filesystem RunBundle│
│ parsers · metrics · gate  │  │ atomic immutable I/O│
└────────────────────────────┘  └────────────────────┘
```

CLI и Web UI вызывают один application core. Ktor types, HTTP sessions и UI
state не проникают в parsers, metric calculation или policy gate.

JDK/Kotlin/Ktor и Vue/TypeScript/Vite предварительно подтверждены пользователем
в архитектурном обсуждении Slice 1. Окончательное approval выбора наступает
только после formal review всей этой spec; production dependency всё равно
требует ADR и implementation plan.

CPU-intensive parsing никогда не выполняется на Netty event-loop/request
threads. Для него используется bounded executor обычных JVM platform threads.
Virtual threads и coroutines не объявляются способом ускорения CPU work.

## 4. Стек и зависимости

| Область | Решение Slice 1 |
| --- | --- |
| Runtime | JDK 21, Kotlin 2.x |
| Build | Gradle Kotlin DSL и wrapper |
| Local HTTP | Ktor с Netty engine |
| Metrics | HdrHistogram Java |
| JSON | Kotlin serialization для собственных контрактов |
| XML | JDK StAX с отключёнными DTD/external entities |
| Frontend | Vue 3, TypeScript, Vite |
| Layout | native CSS Grid и semantic HTML |

Для JTL CSV нужен ровно один проверенный streaming CSV parser. Конкретная
dependency выбирается в implementation plan по маленькому correctness/performance
spike на quoted CSV и большом JTL. Самописный CSV parser допустим только если
тем же spike доказана полная требуемая RFC 4180/JMeter семантика.

В Slice 1 не добавляются Spring, ORM, database driver, broker, chart library,
frontend grid framework, drag-and-drop library, Tailwind или Iconify. CDN из
Superdesign preview не является production dependency.

Версии production dependencies и их основания фиксируются в implementation
plan и ADR до первой реализации.

## 5. Основной пользовательский поток

1. Пользователь запускает `ltv ui`.
2. Приложение занимает lock своего data directory, создаёт случайную локальную
   session и открывает loopback URL.
3. Пользователь выбирает один supported log обычным file input.
4. Optional `policy.v1` выбирается, импортируется или составляется в UI и
   валидируется до запуска анализа.
5. Upload потоково пишется во временный файл с одновременным size limit и
   SHA-256; после проверки файл атомарно попадает в RunBundle.
6. Bounded worker строит normalized facts, metrics и canonical result. UI
   опрашивает job status и показывает обработанные bytes.
7. Пользователь видит validity, verdict, coverage, policy checks, transaction
   metrics, findings и one-second evidence.

Повторная загрузка тех же bytes с тем же detected source type переиспользует
тот же input/run identity. Новый policy или версия engine создаёт новый
analysis, а не изменяет прежний результат.

## 6. Поддерживаемые inputs

| Source | Поддержка | Обязательное поведение |
| --- | --- | --- |
| JMeter JTL CSV | UTF-8, header, quoted values | Streaming parse; malformed rows fail-closed |
| JMeter JTL XML | `sample` и `httpSample`, включая nesting | Streaming StAX; DTD/XXE запрещены |
| Gatling text | OSS 3.9–3.12 | Version-aware parse, exact transaction labels |
| Gatling binary | OSS 3.13–3.15.1 | Big-endian/version-aware parse; unknown version rejected |

Формат определяется по содержимому и сигнатуре, а не только по extension или
имени файла. Filename хранится только как metadata и никогда не определяет
filesystem path.

Все parsers выдают один внутренний normalized event stream. Иерархия
JMeter/Gatling и точные labels сохраняются; fuzzy matching, auto-renaming и
склейка похожих labels запрещены. Aggregation semantics и oracle tolerances
фиксируются table-driven fixtures до реализации каждого parser.

Пустой, неизвестный, structurally broken или unsupported input не создаёт
частичный PASS. XML parser явно запрещает DTD, external general/parameter
entities и filesystem/network resolution.

## 7. RunBundle и идентичность

Input area immutable после atomic move. Derived analysis не перезаписывает
предыдущий analysis.

- `run_id` для manual input детерминирован полным SHA-256 bytes и detected
  source type;
- одинаковые bytes и type дают один `run_id`;
- `analysis_id` равен SHA-256 canonical UTF-8 JSON закрытой identity: version
  identity schema, `run_id`, detected source type и input SHA-256; canonical
  policy hash либо explicit `NO_POLICY`; ordered parser/module ids, versions и
  полные result-affecting configurations, включая histogram precision/range и
  normalization; engine version и все input/output schema versions;
- identity JSON не содержит timestamps или provenance, сортирует object keys
  лексикографически, сохраняет нормативный порядок arrays, кодируется UTF-8 без
  BOM/whitespace, а numeric configuration представляет canonical decimal
  strings без exponent и незначащих zeroes; `0` и leading `0` перед fraction
  сохраняются. Новое влияющее на результат поле требует новой identity-schema
  version;
- изменение любого поля закрытой identity создаёт новый `analysis_id`;
- `analysis_id` хранится в RunBundle/API metadata, но не добавляется в
  `analysis-result.v1`;
- canonical result остаётся одним `analysis-result.v1`; split на
  `verdict.json`/`analysis.json` в Slice 1 не вводится;
- запись metadata/result выполняется temp-write, flush/fsync и atomic rename;
- каждый writer, включая `ltv ui` и `ltv analyze`, перед первой мутацией берёт
  один и тот же exclusive lock data directory; второй writer получает стабильный
  `DATA_DIR_BUSY` и ничего не записывает.

Normalized one-second artifact является internal derived data. Он не создаёт
новый public metrics schema в Slice 1.

## 8. `policy.v1`

### 8.1. Минимальная форма

```json
{
  "schema_version": "policy.v1",
  "policy_id": "checkout-policy",
  "rules": [
    {
      "id": "orders-p95",
      "metric": "response_time_p95_ms",
      "operator": "lte",
      "threshold": 600,
      "scope": {
        "kind": "transaction",
        "name": "POST /api/orders"
      }
    }
  ]
}
```

Top-level требует `schema_version`, `policy_id` и непустой `rules` с
`minItems: 1`; `schema_version` имеет const `policy.v1`. Каждое rule требует
`id`, `metric`, `operator`, `threshold` и `scope`. Top-level, rule и обе scope
forms используют `additionalProperties: false`; scope требует ровно поля своей
формы. `policy_id` обязателен, rule `id` обязательны и семантически уникальны в
пределах policy. Пустая policy не может дать vacuous `PASS`.

### 8.2. Разрешённые правила

| Metric | Operator | Threshold | Unit |
| --- | --- | --- | --- |
| `response_time_p95_ms` | `lte` | число `>= 0` | ms |
| `response_time_p99_ms` | `lte` | число `>= 0` | ms |
| `error_rate_ratio` | `lte` | число от `0` до `1` | ratio |
| `throughput_rps` | `gte` | число `>= 0` | requests/second |

Scope имеет ровно одну из форм:

- `{ "kind": "overall" }`;
- `{ "kind": "transaction", "name": "<exact label>" }`.

Regex, wildcard, fuzzy matching, baseline rules, phases, inherited defaults и
implicit units не поддерживаются. Несовместимые metric/operator, неизвестное
поле, duplicate id или неверный диапазон делают policy невалидной до анализа.

Validation двухступенчатая:

- `ltv policy validate <policy.json>` проверяет contract без run context;
- после parse policy связывается с exact transaction catalog текущего run.

Отсутствующая transaction не делает JSON structurally invalid: evaluation
получает `NO_VERDICT`, а coverage — точную причину. UI может показать эту ошибку
раньше, если catalog уже известен.

### 8.3. Инструменты автора

- UI form создаёт и редактирует только перечисленные поля;
- UI импортирует и скачивает один JSON `policy.v1`;
- `ltv policy validate <policy.json>` выполняет ту же validation;
- UI показывает normalized rule и точную ошибку с JSON path;
- анализ никогда не «исправляет» невалидную policy автоматически.

### 8.4. Инструкция для человека

1. Выбрать overall или точное имя transaction из уже разобранного run.
2. Выбрать один разрешённый metric.
3. Указать threshold в единице, зашитой в имени metric.
4. Проверить JSON через UI или `ltv policy validate`.
5. Сохранить policy рядом с проектом; секреты и environment-specific endpoints
   в policy не помещать.

### 8.5. Нормативный prompt для внешней нейросети

```text
Ты составляешь только LT Verdict policy.v1 JSON.

Разрешены metrics:
- response_time_p95_ms с operator lte;
- response_time_p99_ms с operator lte;
- error_rate_ratio с operator lte и threshold 0..1;
- throughput_rps с operator gte.

Scope — только overall или transaction с точным переданным пользователем name.
Не используй regex, wildcard, phases, baseline, implicit defaults и новые поля.
Не придумывай thresholds, transaction names, units или SLA. Если хотя бы одно
значение отсутствует, задай пользователю уточняющий вопрос и не создавай JSON.
Каждому правилу дай короткий уникальный id. Верни один JSON object без Markdown,
пояснений и комментариев. schema_version всегда policy.v1. После составления
попроси пользователя выполнить `ltv policy validate <file>`.
```

Полный пользовательский документ в Slice 1 содержит тот же prompt, JSON
examples для каждого metric и таблицу validation errors.

## 9. Analysis semantics

Три измерения независимы:

| Условие | `run_validity` | `policy_verdict` | Поведение |
| --- | --- | --- | --- |
| Unsupported/empty/broken input | `INVALID` | `NO_VERDICT` | Metrics не считаются достоверными |
| Corrupt/truncated data были пропущены | `DEGRADED` | `NO_VERDICT` | Доступные metrics показываются с причиной |
| Полностью разобранный run, policy нет | `VALID` | `NO_POLICY` | Basic analysis доступен |
| Все applicable rules выполнены | `VALID` | `PASS` | Checks и evidence сохранены |
| Хотя бы одно applicable rule нарушено | `VALID` | `FAIL` | Нарушение связано с evidence |
| Rule metric/transaction отсутствует | `VALID` или `DEGRADED` | `NO_VERDICT` | Coverage содержит точную причину |

Невалидная policy отклоняется до создания analysis. Она не превращается в
`NO_POLICY` и не даёт ложный verdict.

`analysis-result.v1` остаётся без изменения schema. Slice 1 использует typed
objects внутри разрешённых `findings` и `evidence`, включая metric summary и
policy check. Findings являются deterministic diagnostics; incident synthesis
и narrative AI отложены.

## 10. Метрики и time normalization

Для каждой exact transaction и overall вычисляются применимые sample count,
errors, error rate, throughput, p50, p95, p99 и max. Latency aggregation
использует HdrHistogram с документированной precision/range configuration.

Базовый time-series artifact состоит из sparse 1-second buckets:

- bucket start относительно фактического начала run;
- sample count и error count;
- mergeable latency histogram;
- maximum latency;
- отсутствие bucket означает missing/no samples и не заменяется нулём.

Rollups 10/30/60 seconds строятся только merge из 1-second buckets. Raw immutable
input остаётся fallback для будущего sub-second drill-down. Slice 1 не рисует
charts, но fixture обязан доказать, что короткий latency spike и throughput drop
не исчезают в 1-second normalization.

## 11. Concurrency и admission control

- Upload streaming не занимает analysis executor.
- Parsing/metrics выполняются вне Ktor/Netty request threads.
- Local default — один активный analysis.
- Parallelism конфигурируется, но ограничивается bounded executor и памятью.
- Queue bounded; переполнение возвращает стабильный `BUSY` без потери input.
- UI получает job id, polling status и processed bytes.
- Cancel прекращает незавершённый derived analysis и удаляет только его temp
  files; immutable accepted input не удаляется.
- Два параллельных RunBundle не разделяют mutable histograms/aggregators.

Acceptance test с двумя RunBundle обязан получить те же результаты, что и два
последовательных запуска. Эта модель масштабируется в будущую server version
через configurable parallelism и несколько processes/nodes без переписывания
analytical core.

## 12. Local security boundary

- Backend bind только `127.0.0.1`; внешний bind отсутствует.
- Случайная session хранится в `HttpOnly; SameSite=Strict` cookie.
- CSRF token хранится только в памяти UI.
- Mutating request требует session, CSRF и exact allowed Origin.
- CORS выключен; CSP запрещает CDN, inline remote code и outbound resources.
- Vue не использует `v-html` для недоверенных значений.
- `localStorage` и `sessionStorage` не используются.
- Приложение не выполняет outbound HTTP/DNS requests в Slice 1.
- Internal paths генерирует приложение; filename остаётся metadata.
- Symlink, traversal, reserved device name и выход из data directory отвергаются.
- DTD/XXE и XML entity expansion запрещены.
- Size limit проверяется во время stream, а не после загрузки.
- Response bodies, headers и raw XML не сохраняются и не рендерятся.
- Atomic writes используют flush/fsync до rename.

Malicious fixtures покрывают XML, filename/path, symlink, oversized input,
HTML labels, Origin/CSRF и Content-Type.

## 13. Утверждённый Web UI

### 13.1. Design artifact

- Superdesign project:
  `57a2cd74-4144-499b-8c51-dcc85ea1f653`;
- draft: `3370023d-ac16-4ce5-94d1-b70bb3a7e7b9`;
- active version: `4`, восстановленное содержимое первоначальной version `1`;
- [canvas](https://superdesign.dev/teams/b84a60b6-c06f-4321-ae7e-654013cbfe57/projects/57a2cd74-4144-499b-8c51-dcc85ea1f653?node=draft-variant-3370023d-ac16-4ce5-94d1-b70bb3a7e7b9);
- [interactive preview](https://p.superdesign.dev/draft/3370023d-ac16-4ce5-94d1-b70bb3a7e7b9).

Нормативные tokens и layout находятся в
`.superdesign/design-system.md`; `.superdesign/resume.json` хранит resumable
remote identity и fingerprint. При конфликте functional scope этого документа
имеет приоритет над control, показанным в remote preview.

### 13.2. Layout

- desktop canvas шириной 1440 px;
- left navigation 224 px: `Runs`, `Policies`;
- header 56 px: run/source metadata и theme toggle;
- run preparation с native file input и policy selector;
- background job row с bytes, progress и Cancel;
- полноширинный verdict strip;
- четыре summary cards;
- policy-results table;
- transaction table по impact;
- inspectable 1-second normalized-data table с filters.

Production UI сохраняет эту иерархию, но не копирует Tailwind CDN или
Superdesign-specific attributes. Для будущих charts/layout используются stable
widget ids и native CSS Grid. Chart и drag-and-drop placeholders не рисуются.

`Runs` в Slice 1 показывает текущий локальный flow и доступные filesystem
RunBundles без search, trends, baseline или отдельного history catalog.

### 13.3. Темы

- Полные light и dark semantic token sets.
- Первое открытие следует `prefers-color-scheme`.
- Видимый labelled toggle меняет тему без смены структуры или status semantics.
- Slice 1 хранит manual choice только в памяти текущей страницы; browser storage
  не используется. Cookie persistence можно добавить отдельным решением позже.
- PASS/WARN/FAIL не являются единственным носителем смысла: обязательны icon и
  text label.

### 13.4. UI states

Один и тот же shell поддерживает:

- empty: нужны supported log и optional valid policy;
- uploading/queued/processing с processed bytes;
- `BUSY` с понятным следующим действием;
- policy validation error с точным field/transaction;
- `VALID + NO_POLICY`;
- `INVALID + NO_VERDICT`;
- `DEGRADED + NO_VERDICT` с доступными metrics;
- `PASS` и `FAIL`;
- cancel и recoverable analysis failure.

UI не показывает пустой chart, disabled drag handle или неработающую feature
кнопку. Remote preview содержит `Export result`, но этот control ненормативен
для Slice 1 и не рендерится в production UI. Все result outputs принадлежат
Slice 9.

### 13.5. Accessibility

- normal text contrast не ниже 4.5:1;
- visible 2 px keyboard focus;
- action targets минимум 44×44 px;
- persistent labels и semantic tables;
- logical focus order по визуальной иерархии;
- status всегда содержит text, не только цвет;
- `prefers-reduced-motion` отключает необязательные transitions.

## 14. CLI и UI parity

Минимальные команды:

```text
ltv ui [--data-dir <path>] [--analysis-parallelism <n>]
ltv analyze <input> [--policy <policy.json>] [--data-dir <path>]
ltv policy validate <policy.json>
```

CLI и UI используют одинаковые parser versions, normalization, policy
validation и result writer. Один input+policy+engine configuration должен дать
одинаковые `run_id`, `analysis_id` metadata и byte-identical canonical result
независимо от оболочки.

Local REST endpoints являются private implementation detail Web UI и не
объявляются внешним public API Slice 1.

## 15. Observable acceptance criteria

1. `ltv ui` слушает только loopback и открывает usable Web UI без database.
2. Один JTL CSV, один JTL XML, один Gatling text и один Gatling binary fixture
   проходят UI и CLI через один core.
3. Unknown Gatling version, malformed CSV/XML, DTD/XXE и empty input дают
   fail-closed result/diagnostic без PASS.
4. Upload до 4 GiB остаётся streaming; size overflow останавливается во время
   чтения и не создаёт accepted input.
5. Повтор одинаковых bytes/type даёт тот же `run_id`; новый policy создаёт новый
   `analysis_id` и сохраняет прежний result.
6. `policy.v1` Schema и `ltv policy validate` принимают все четыре metrics и
   отклоняют unknown fields/metrics, operators и threshold ranges; отсутствующая
   в run exact transaction даёт `NO_VERDICT` с coverage reason.
7. PASS, FAIL, NO_POLICY и NO_VERDICT покрыты fixtures без смешивания validity и
   coverage.
8. CLI и UI создают byte-identical `analysis-result.v1` для одного analysis.
9. Short spike/drop fixture сохраняет событие в 1-second bucket и rollups.
10. Два concurrent RunBundle дают результаты, равные sequential processing.
11. Queue overflow отображается как `BUSY`; cancel не повреждает immutable input.
12. Light/dark toggle работает с system default, keyboard focus и одинаковой
    information hierarchy.
13. UI не содержит chart/DnD dependencies или placeholders.
14. Malicious file/XML/browser fixtures подтверждают security boundary.
15. Общий data-directory lock не допускает одновременную запись `ltv ui` и
    `ltv analyze`; проигравший writer получает `DATA_DIR_BUSY` без partial files.
16. Slice 1 выполняется offline после получения build dependencies.
17. Fresh tests, build, lint, documentation и secret checks проходят; полный
    diff не содержит unrelated changes или temporary artifacts.

## 16. Public contracts, ADR и documentation impact

Slice 1 добавляет один public contract: `policy.v1`. `run.v1` и
`analysis-result.v1` остаются schema-compatible и не получают новых required
fields.

До production implementation требуются:

- ADR о JDK/Kotlin/Ktor runtime, filesystem execution model, dependencies,
  concurrency и local security boundary;
- ADR о `policy.v1`, metric semantics и typed policy-check evidence;
- JSON Schema и valid/invalid examples `policy.v1`;
- user guide по upload, status meanings и policy authoring;
- отдельный файл с prompt для внешней нейросети либо нормативный раздел того же
  user guide;
- README quick start и `CHANGELOG.md` в behavior PR;
- `docs/milestones/stage-1.md` при прохождении exit gate.

Этот design-only commit не меняет behavior, поэтому `CHANGELOG.md` пока не
обновляется.

## 17. Риски и осознанные ограничения

- Большой JTL ограничивает parallelism прежде памяти, чем числом threads;
  bounded executor и 4 GiB stream limit обязательны.
- Gatling binary format version-sensitive; unknown version всегда fail-closed.
- Filesystem scan без SQLite приемлем для одного локального оператора; shared
  history появляется только при подтверждённой потребности.
- Desktop-only layout не является обещанием mobile UX.
- 1-second buckets не заменяют будущий chart contract; они только не теряют
  короткие события и дают mergeable groundwork.
- Утверждённый UI намеренно консервативен; его визуальная структура не разрешает
  расширять функциональный scope Slice 1.

## 18. Exit gate Slice 1

Implementation plan обязан назвать точные Gradle/frontend/E2E команды до
первого production change. Минимальный gate включает:

- unit и property/table-driven parser/policy tests;
- golden/oracle tests всех четырёх input families;
- bounded-memory/performance probe на большом JTL;
- CLI/UI parity test;
- concurrency и cancellation test;
- malicious-input/browser security tests;
- frontend typecheck, lint, build и accessibility checks;
- documentation lint, `git diff --check` и secret scan;
- полный diff review и independent code review;
- milestone report `docs/milestones/stage-1.md` с evidence и известными
  ограничениями.

Slice 1 не считается завершённым по одному screenshot, parser demo или зелёному
unit test. Exit gate должен доказать полный локальный flow и Definition of Done.

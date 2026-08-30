# LT Verdict — анализ соответствия выполненной работы PRC/PRD v0.6

**Дата анализа:** 18 августа 2026 года

**Режим:** read-only аудит репозитория с последующим оформлением результатов

**Цель:** определить, какие результаты работы по PRC v0.5 сохраняют ценность для
PRC/PRD v0.6, какие контракты и планы нужно изменить, а какие направления
следует отложить.

## 1. Executive summary

PRC/PRD v0.6 задаёт обоснованный архитектурный разворот:

- от server-first платформы к portable core;
- от обязательной регистрации и инфраструктуры к artifact-first анализу;
- от относительно монолитного pipeline к versioned analysis modules;
- от глобального fail-closed при неполноте источников к fail-closed policy gate
  и fail-soft enrichment;
- от общего `verdict.json` с расширяемыми findings к стабильному
  `verdict.json` и отдельному `analysis.json`;
- от dashboard-first UX к incident-first отчёту;
- от AI/APM в раннем MVP к optional advanced capabilities.

Текущая работа хорошо поддерживает v0.6 на уровне инженерных инвариантов:
парсеры JMeter/Gatling, golden fixtures и oracle-based verification,
streaming/bounded-memory обработка, immutable artifacts, SHA-256 provenance,
детерминированная нормализация, append-only audit, modular monolith и отделение
AI от PASS/FAIL.

При этом текущие Stage 1 и development plan нельзя использовать как план
реализации v0.6 без существенной переработки. Они спроектированы вокруг
серверного Registration API, PostgreSQL, chunked upload и обязательной ручной
фазовой модели. В v0.6 первым usable product должен быть локальный offline core,
работающий напрямую со штатными JTL и `simulation.log`, создающий `RunBundle`,
canonical JSON и self-contained HTML без внешней БД и сети.

Фактической production-реализации в текущем worktree нет. Выполнена
контрактная, архитектурная и процессная подготовка. Поэтому утверждения ниже
относятся к качеству спецификаций и планов, а не к готовности работающего
продукта.

## 2. Проверенное состояние репозитория

На момент аудита:

- текущая ветка: `agent/add-admin-questionnaire`;
- ветка опережает upstream на 15 commits;
- незатреканные пользовательские файлы:
  `lt-verdict-prc-prd-v0.6.md` и `k3-prc-review.md`;
- tracked-файлов: 22;
- отсутствуют `src/`, `test/`, `tests/`, `fixtures/`, `tools/`, Gradle/Maven/npm
  build-файлы и production/test source tree;
- отсутствуют `docs/contracts/`, `docs/adr/` и `docs/milestones/`;
- присутствуют governance-документы и GitHub Actions только для качества
  документации, ссылок и secret scan.

README корректно описывает состояние как подготовку к реализации:
`README.md:6-9`.

Следствие: все функциональные и нефункциональные требования PRC v0.6,
предполагающие исполняемый продукт, сейчас имеют статус `NOT IMPLEMENTED`.
Статус `PARTIALLY IMPLEMENTED` допустим только для документационных,
контрактных и process capabilities.

## 3. Статус PRC v0.6 как baseline

PRC v0.6 пока обозначен как предлагаемая целевая версия:
`lt-verdict-prc-prd-v0.6.md:5-8`. Файл не добавлен в Git.

Одновременно:

- основной development plan явно основан на v0.5:
  `docs/superpowers/plans/2026-08-10-development-plan.md:1-9`;
- план фиксирует MVP на PRC v0.5:
  `docs/superpowers/plans/2026-08-10-development-plan.md:18-21`;
- Stage 1 spec основан на PRC v0.5:
  `docs/stage-1-spec.md:3-5`;
- Stage 1 Definition of Done проверяет критерий v0.5:
  `docs/stage-1-spec.md:272-284`;
- README не ссылается ни на v0.5, ни на v0.6 как на действующий baseline:
  `README.md:11-18`;
- `CHANGELOG.md` не отражает переход продуктовой концепции:
  `CHANGELOG.md:6-10`.

До начала production-реализации необходимо формально принять одну версию как
нормативную и синхронизировать все зависимые документы.

## 4. Что можно сохранить почти без изменений

### 4.1. Parser contracts и проверенные форматы

Сильные элементы текущего Stage 1:

- потоковый разбор Gatling OSS 3.9–3.15.1;
- разделение text 3.9–3.12 и binary 3.13–3.15.1;
- fail-closed routing для неизвестной версии/структуры;
- JTL CSV contract с обязательными полями;
- сохранение transaction/group semantics;
- сравнение с Gatling HTML report и `jtl-comparator`;
- измеримый JTL performance gate.

Evidence:

- `docs/stage-1-spec.md:167-218`;
- `docs/stage-1-spec.md:272-282`;
- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:111-171`.

Это напрямую поддерживает:

- artifact-only onboarding: `lt-verdict-prc-prd-v0.6.md:152-158`;
- анализ без изменения JMX/Simulation:
  `lt-verdict-prc-prd-v0.6.md:1588-1593`;
- streaming/bounded-memory NFR:
  `lt-verdict-prc-prd-v0.6.md:1421-1436`.

Рекомендация: сохранить точные версии, layouts, negative fixtures, oracle
версии и допуски. PRC v0.6 сейчас формулирует это менее конкретно, поэтому
существующие контракты должны стать нормативным приложением к новому Phase 0.

### 4.2. Evidence-bearing Phase 0

Правильно спроектированы:

- реальные tool-generated fixtures;
- независимые oracle outputs;
- manifest с producer/oracle versions, commands и SHA-256;
- offline verifier;
- воспроизводимый benchmark;
- milestone report, который нельзя закрыть одним наличием документа.

Evidence:

- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:16-40`;
- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:173-214`;
- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:216-232`.

Это хорошо соответствует Phase 0 v0.6:
`lt-verdict-prc-prd-v0.6.md:1442-1449`.

Рекомендация: не переписывать принцип закрытия этапа. Нужно заменить набор
контрактов на v0.6 и фактически создать отсутствующие fixtures, schemas,
verifier и milestone evidence.

### 4.3. Immutable artifacts, hashes и provenance

Сохраняют ценность:

- клиентский и фактический SHA-256;
- content-addressed storage;
- атомарный finalize;
- immutable objects в пределах retention;
- журнал доставки и lifecycle;
- детерминированные derived snapshots;
- запрет тихого изменения входных фактов.

Evidence:

- `docs/stage-1-spec.md:104-140`;
- `docs/stage-1-spec.md:144-163`;
- `docs/stage-1-spec.md:222-229`.

Это согласуется с моделью `RunBundle`:

- immutable `inputs/`: `lt-verdict-prc-prd-v0.6.md:491-495`;
- отдельный provenance: `lt-verdict-prc-prd-v0.6.md:460-463`;
- versioned re-analysis: `lt-verdict-prc-prd-v0.6.md:497-503`.

Рекомендация: сохранить механизмы, но поместить их под контракт `RunBundle`.
Content-addressed server catalog должен стать одним из storage providers, а не
предусловием работы core.

### 4.4. Streaming и deterministic normalization

HDR-нормализация, фиксированная точность, детерминированная сериализация и
bounded concurrency остаются полезными:

- `docs/stage-1-spec.md:222-238`;
- `docs/superpowers/plans/2026-08-10-development-plan.md:13-15`.

Нужно сохранить идею canonical normalized events и histograms, но убрать
жёсткую привязку к заранее зарегистрированной `steady`-фазе.

### 4.5. Modular monolith

Текущая разбивка на Gradle-модули совместима с P-08:

- `docs/stage-1-spec.md:17-35`;
- `lt-verdict-prc-prd-v0.6.md:191-193`.

Это хороший deployment boundary. Однако Gradle-модуль не заменяет публичный
analysis module contract; необходимые изменения описаны ниже.

### 4.6. Security и audit-инварианты

Можно сохранить:

- secrets вне YAML;
- scoped ingest tokens;
- запрет хранения plaintext tokens;
- append-only journals с DB-level запретом UPDATE/DELETE;
- проверку ZIP path traversal для bundle-like inputs;
- явные quotas и bounded resource use.

Evidence:

- `docs/stage-1-spec.md:80-86`;
- `docs/stage-1-spec.md:128-140`;
- `docs/stage-1-spec.md:159`;
- `docs/stage-1-spec.md:248-268`;
- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:77-109`;
- `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:133-138`.

Для local mode это должно быть дополнено no-egress tests и безопасной
обработкой недоверенных archive/module inputs.

### 4.7. Отделение AI от deterministic result

Сохраняется принцип:

- AI работает после deterministic verdict;
- output помечается как AI-generated;
- модель/промпт/output журналируются;
- AI не изменяет `verdict.json`.

Evidence:

- `docs/superpowers/plans/2026-08-10-development-plan.md:18-19`;
- `docs/superpowers/plans/2026-08-10-development-plan.md:137-157`;
- `lt-verdict-prc-prd-v0.6.md:136-146`;
- `lt-verdict-prc-prd-v0.6.md:759-769`.

Меняется приоритет: AI должен быть advanced pack/Phase 5, а не обязательной
частью первого MVP.

### 4.8. Development governance

Сохраняются без продуктовой переработки:

- evidence before readiness claims;
- TDD и fresh verification;
- ADR для public contracts и architecture;
- документация и changelog в том же PR;
- milestone report и explicit exit gate;
- запрет закрытия этапа без CI/evidence.

Evidence:

- `docs/development-process.md:11-35`;
- `docs/development-process.md:209-230`.

## 5. Что нужно адаптировать, но не выбрасывать

### 5.1. Chunked/resumable ingest

Текущий контракт upload полезен для больших JTL и server mode:
`docs/stage-1-spec.md:102-130`.

Но в v0.6 он не должен быть обязательным путём в core. Целевая роль:

1. local CLI читает существующий каталог/файл напрямую;
2. local core создаёт или нормализует `RunBundle`;
3. CI может экспортировать bundle одной post-run командой;
4. server adapter импортирует bundle напрямую или через resumable upload;
5. catalog раскладывает bundle inputs по content hashes без изменения
   логического bundle contract.

### 5.2. Registration API

Registration API полезен для:

- CI provenance;
- server catalog;
- planned-vs-actual checks;
- shared baseline assignment;
- audit и team workflows.

Однако существующий контракт требует слишком много обязательных metadata:
`docs/stage-1-spec.md:58-86`.

В v0.6 metadata должны иметь несколько origins:

- artifact-derived;
- CI-provided;
- external manifest;
- inferred;
- user override с provenance.

Local artifact-only анализ не должен зависеть от предварительной регистрации:
`lt-verdict-prc-prd-v0.6.md:237-267`.

### 5.3. Content-addressed catalog и PostgreSQL

Они остаются разумными server implementation choices, но не public core
contract. В v0.6 storage abstraction должна позволять:

- filesystem-only local mode;
- optional SQLite/local history;
- filesystem или object storage на сервере;
- PostgreSQL для shared metadata/fingerprints.

Evidence: `lt-verdict-prc-prd-v0.6.md:1315-1323`.

### 5.4. Lifecycle

Transport/execution lifecycle можно сохранить после отделения от результата.
Например:

```text
DISCOVERED -> IMPORTING -> READY -> ANALYZING -> COMPLETED
                       \-> FAILED_IMPORT
                                  \-> FAILED_ANALYSIS
```

Это лишь иллюстрация; точные названия должны быть зафиксированы ADR/schema.

Отдельно должны существовать:

```text
run_validity: VALID | DEGRADED | INVALID
policy_verdict: PASS | FAIL | NO_POLICY | NO_VERDICT
analysis_coverage: capability map + module statuses
```

Нормативная модель результата: `lt-verdict-prc-prd-v0.6.md:591-613`.

### 5.5. Phase/stage metadata

Полезны:

- versioning stage annotations;
- сохранение origin/confidence;
- planned-vs-actual comparison;
- включение выбранной segmentation version в provenance.

Нужно убрать требования:

- обязательный `steady`;
- полное покрытие planned window;
- отказ от анализа без ручной разметки.

Конфликт:

- текущий контракт: `docs/stage-1-spec.md:73-90`;
- v0.6 inference policy: `lt-verdict-prc-prd-v0.6.md:269-278`.

### 5.6. Existing validity gates

Сохраняются как идеи:

- generator health;
- overlap detection;
- clock skew;
- profile conformance;
- empty/corrupt input;
- snapshot quality.

Но каждый gate должен объявлять собственные required capabilities. Нельзя
считать отсутствие любого observability source глобальной причиной
`NO_VERDICT`.

## 6. Что требуется перепроектировать

### 6.1. Stage 1 boundary

Текущий Stage 1 — server ingest core:

- Spring Boot;
- PostgreSQL/Flyway;
- Registration API;
- chunked upload;
- DB queue;
- server-side catalog.

Evidence:

- `docs/stage-1-spec.md:9-35`;
- `docs/superpowers/plans/2026-08-10-development-plan.md:56-76`.

Новая граница Stage 1 должна быть portable local foundation:

- filesystem input discovery;
- JMeter/Gatling parsers;
- `RunBundle v1`;
- canonical normalized data;
- run validity;
- optional policy with `NO_POLICY`;
- manual baseline comparison;
- separate `verdict.json` и `analysis.json`;
- compact incident overview;
- self-contained HTML;
- CLI;
- no external DB и no outgoing network.

Evidence: `lt-verdict-prc-prd-v0.6.md:1451-1463`.

### 6.2. RunBundle как главный public contract

Нужно определить и заморозить:

- schema `manifest.json`;
- archive safety rules;
- immutable input rules;
- normalized/output directories;
- input/module/config/baseline hashes;
- bundle revision semantics;
- import/export compatibility;
- canonical archive ordering/timestamps, если требуется byte identity;
- distinction между bundle identity и `AnalysisRun` identity.

Основание: `lt-verdict-prc-prd-v0.6.md:425-503`.

### 6.3. Analysis module contract

Текущая Gradle-модульность не покрывает FR-MOD-01..04. Нужны:

- versioned `module.yaml` schema;
- module type;
- required/optional capabilities;
- declared outputs;
- deterministic flag;
- failure policy;
- dependency graph;
- statuses `SUCCESS/DEGRADED/SKIPPED/FAILED/BLOCKED`;
- compatibility version/range;
- fixture tests;
- module timeout/resource policy;
- distinction built-in и external pack;
- правила изменения canonical verdict.

Evidence:

- `lt-verdict-prc-prd-v0.6.md:507-587`;
- `lt-verdict-prc-prd-v0.6.md:1374-1383`.

### 6.4. Fail-closed/fail-soft semantics

Критический конфликт:

- старый план: отключение любого источника даёт `NO_VERDICT`:
  `docs/superpowers/plans/2026-08-10-development-plan.md:124-133`;
- v0.6: optional module failure не ломает core и снижает coverage:
  `lt-verdict-prc-prd-v0.6.md:174-177`;
- acceptance: отсутствие VM/Influx/APM не мешает load-only analysis:
  `lt-verdict-prc-prd-v0.6.md:1593-1594`.

Нужен формальный алгоритм:

1. определить выбранную policy;
2. вычислить required capabilities этой policy;
3. оценить integrity/quality только required inputs;
4. выдать `NO_VERDICT` только для реально блокирующих причин;
5. независимо выполнить доступные enrichment modules;
6. отразить skipped/degraded/failed modules в coverage;
7. не изменять policy verdict из-за optional enrichment failure.

### 6.5. Canonical outputs

Старый план помещает deterministic findings в `verdict.json`:
`docs/superpowers/plans/2026-08-10-development-plan.md:116-133`.

В v0.6:

- `verdict.json` содержит стабильный policy contract;
- `analysis.json` содержит findings, incidents, correlations, trends и RCA;
- новый correlation/ML module не должен менять schema/semantics verdict.

Evidence: `lt-verdict-prc-prd-v0.6.md:682-706`.

Нужно перепроектировать schemas, canonical serialization и reproducibility
tests вокруг этого разделения.

### 6.6. Incident-first UX

Старый plan ведёт к большой SPA с live view, AI chat, SLA editor и Confluence
publishing: `docs/superpowers/plans/2026-08-10-development-plan.md:137-157`.

Первый UX v0.6 должен быть существенно меньше:

- validity;
- policy verdict;
- coverage;
- 3–7 top incidents;
- evidence drill-down;
- raw data links;
- self-contained HTML/JSON export.

Evidence:

- `lt-verdict-prc-prd-v0.6.md:115-124`;
- `lt-verdict-prc-prd-v0.6.md:887-951`;
- `lt-verdict-prc-prd-v0.6.md:1398-1407`.

### 6.7. Development order

Текущий критический путь:

```text
server ingest -> VM -> APM -> verdict -> GUI/AI -> distribution
```

Evidence: `docs/superpowers/plans/2026-08-10-development-plan.md:200-208`.

Целевой порядок v0.6:

```text
contracts/fixtures
  -> local artifact-only core
  -> canonical verdict/analysis + compact report
  -> optional observability packs
  -> domain modules/local wizard
  -> server import/catalog/history
  -> advanced APM/AI interpretation
```

Основание: `lt-verdict-prc-prd-v0.6.md:1442-1503`.

## 7. Что следует отложить

Для первого usable local release не должны быть блокерами:

- PostgreSQL server metadata store;
- OIDC/RBAC;
- object storage;
- distributed/job queue;
- shared history;
- automatic baseline selection;
- full React SPA;
- Confluence/email/Jira exporters;
- Graphite live intake;
- APM locator;
- code-context/RCA agent;
- AI narrative;
- microservice extraction;
- complex ML and causal discovery.

Это не означает удалить наработанные требования. Их следует сохранить как
later-phase specs или backlog, но убрать из критического пути portable MVP.

## 8. Матрица решений по существующей работе

| Область | Решение | Комментарий |
| --- | --- | --- |
| Governance и docs CI | Сохранить | Не зависит от продуктовой архитектуры |
| Golden fixtures/oracles | Сохранить и завершить | Основа нового Phase 0 |
| Gatling/JTL format contracts | Сохранить | Дополняют слишком общий PRC v0.6 |
| Streaming parser/perf gate | Сохранить | Сделать обязательным NFR с точным gate |
| SHA-256/CAS/immutable artifacts | Адаптировать | Подчинить `RunBundle`/storage provider |
| Chunked/resumable upload | Перенести | Server/CI adapter, не core prerequisite |
| Registration API | Адаптировать | Optional provenance/server workflow |
| PostgreSQL/Flyway/DB queue | Перенести | Phase 4 server implementation |
| Fixed `steady` phases | Перепроектировать | General segments + inference + confidence |
| Lifecycle `... -> VERDICT` | Перепроектировать | Execution отдельно от analytical result |
| Gradle modular monolith | Сохранить | Дополнить public analysis module contract |
| Global missing-source gate | Заменить | Policy-scoped required capabilities |
| Findings внутри verdict | Заменить | Findings/incidents в `analysis.json` |
| VM/OpenShift work | Перенести после foundation | Optional Phase 2 packs |
| APM/ELK mandatory path | Отложить | Advanced optional capability |
| AI/RCA в MVP | Отложить | Phase 5, поверх structured evidence |
| Большая SPA/Confluence | Сократить/отложить | Сначала self-contained incident report |
| Security/audit invariants | Сохранить | Добавить archive/module sandbox и no-egress tests |

## 9. Неоднозначности и пробелы PRC v0.6

Перед формальным утверждением v0.6 следует разрешить следующие вопросы.

### 9.1. Что именно называется MVP

Server import и CLI/server parity объявлены MUST/acceptance:

- `lt-verdict-prc-prd-v0.6.md:1413-1417`;
- `lt-verdict-prc-prd-v0.6.md:1588-1602`.

Но server появляется только в Phase 4:
`lt-verdict-prc-prd-v0.6.md:1486-1494`.

Рекомендация: разделить минимум на:

- Phase 1 local MVP gate;
- Phase 2 enriched local gate;
- Phase 4 full v0.6/server parity gate.

### 9.2. Streaming: SHOULD или MUST

`FR-CORE-06` помечен SHOULD, но `NFR-08` требует bounded-memory/streaming:

- `lt-verdict-prc-prd-v0.6.md:1363-1372`;
- `lt-verdict-prc-prd-v0.6.md:1421-1436`.

Рекомендация: сделать streaming MUST и сохранить измеримый benchmark из
Stage 1.

### 9.3. `ltv doctor`: SHOULD или acceptance requirement

`FR-MOD-06` — SHOULD, но acceptance criterion 13 делает `doctor` обязательным:

- `lt-verdict-prc-prd-v0.6.md:1374-1383`;
- `lt-verdict-prc-prd-v0.6.md:1601-1603`.

Нужен единый приоритет и gate.

### 9.4. Точные форматы parser inputs

PRC не фиксирует минимальные версии JMeter/Gatling, required fields, archive
rules и oracle tolerances. Эти требования уже детально проработаны в Stage 0/1
и должны быть перенесены в normative contract.

### 9.5. CLI/server identity и provenance

Acceptance требует идентичный `verdict.json`, но execution environment и
re-analysis provenance закономерно различаются.

Рекомендация: канонический policy payload идентичен при одинаковых
inputs/config/module/baseline/engine versions; execution envelope и audit
record могут различаться и не входят в canonical hash verdict payload.

### 9.6. `NO_POLICY` против `NO_VERDICT`

Foundation pack допускает optional SLA:
`lt-verdict-prc-prd-v0.6.md:970-980`.

Одновременно SLA/baseline gate является MUST capability:
`lt-verdict-prc-prd-v0.6.md:1385-1395`.

Рекомендация: gate module обязателен, policy input — нет. Без policy результат
анализа успешен с `NO_POLICY`; `NO_VERDICT` возможен только при наличии policy
и отсутствии/недостоверности её required inputs.

### 9.7. Capability model

PRC требует coverage matrix и module statuses, но не задаёт формальную schema:

- capability identifiers;
- provider/consumer rules;
- quality levels;
- required/optional multiplicity;
- selection при нескольких providers;
- aggregation statuses;
- reason codes для skipped/degraded/blocked.

Эту schema необходимо заморозить в Phase 0.

### 9.8. Incident evidence contract

Acceptance требует evidence reference и module version для каждого incident,
но incident агрегирует findings разных modules. Нужно определить:

- обязательные `finding_ids`;
- прямые и транзитивные evidence references;
- версии synthesizer и source modules;
- negative evidence schema;
- стабильность incident ID;
- поведение при обновлении только synthesizer.

### 9.9. External module security

PRC оставляет sandbox/изоляцию external modules на последующее уточнение:
`lt-verdict-prc-prd-v0.6.md:1662-1670`, но одновременно допускает external
packs. До публичного plugin API следует либо ограничить MVP встроенными
declarative packs, либо заранее определить process isolation/signing policy.

### 9.10. Determinism boundaries

Нужно явно разделить:

- deterministic verdict;
- deterministic analysis modules;
- non-deterministic/experimental analysis;
- render output;
- execution/audit metadata.

Без этого невозможно корректно определить canonical hashes и parity tests.

## 10. Рекомендуемая новая этапность

### Phase 0 — Portable contracts and evidence

Deliverables:

- `RunBundle v1` schema и archive rules;
- canonical `verdict.json`, `analysis.json`, evidence/finding/incident schemas;
- module manifest и capability schema;
- parser input contracts;
- real golden fixtures и independent oracles;
- offline verifier;
- deterministic serialization rules;
- benchmark methodology;
- threat model для local archive/module handling;
- milestone report с evidence.

Exit gate: все schemas/examples/fixtures/hashes/oracles проверяются offline;
никакого production-кода не требуется.

### Phase 1 — Portable local foundation

Deliverables:

- CLI;
- filesystem storage provider;
- JMeter/Gatling streaming parsers;
- bundle create/import/export;
- normalized load facts;
- validity;
- `NO_POLICY` и deterministic SLA/manual baseline gate;
- basic load findings и incident grouping;
- JSON и self-contained HTML;
- offline/no-egress mode.

Exit gate: штатные JTL и `simulation.log` анализируются без изменения сценария,
без внешней БД/сети; одинаковые inputs/versions дают одинаковый canonical
result.

### Phase 2 — Optional observability and compact RCA

Deliverables:

- VM/Prometheus и Influx snapshot providers;
- OpenShift/JVM packs;
- capability preview/`doctor`;
- change points, lagged/partial correlation;
- negative evidence и evidence charts.

Exit gate: отсутствие любого optional pack не меняет load-only verdict; отказ
pack отражается только в его module status/coverage.

### Phase 3 — Domain packs and local wizard

Deliverables:

- PostgreSQL/pg_profile adapter;
- JVM/config/deployment artifact analyzers;
- local drag-and-drop UI;
- session isolation/TTL;
- selected exporters.

Exit gate: новый detector/domain pack добавляется через manifest, data/knowledge
и fixtures без изменения core и test scripts.

### Phase 4 — Server shell and history

Deliverables:

- server API/UI поверх того же core;
- bundle import/export;
- resumable upload adapter;
- catalog/storage providers;
- PostgreSQL metadata/fingerprints;
- shared baseline assignment;
- re-analysis jobs;
- auth/RBAC/audit по pilot profile.

Exit gate: одинаковый bundle и одинаковый analysis contract дают идентичный
canonical verdict локально и на сервере.

### Phase 5 — Advanced interpretation

Deliverables:

- APM locator;
- history-based models;
- AI narrative;
- code-context/RCA;
- advanced integrations.

Exit gate: отключение/ошибка advanced module не изменяет deterministic verdict;
каждый non-deterministic output явно маркирован и имеет audit provenance.

## 11. Обязательные ADR перед реализацией

Минимальный набор:

1. **ADR: PRC v0.6 baseline и migration from v0.5.**
2. **ADR: RunBundle identity, revision и canonical hashing.**
3. **ADR: Verdict/analysis split и determinism boundaries.**
4. **ADR: Module/capability contract и failure semantics.**
5. **ADR: Local-first storage abstraction и server adapters.**
6. **ADR: Stage inference, origin и confidence model.**
7. **ADR: Local no-egress и недоверенные archive/module inputs.**
8. **ADR: Retention, redaction и sensitive-data handling.**
9. **ADR: External pack policy — built-in only, declarative или sandboxed.**

Точная нумерация зависит от принятой структуры `docs/adr/`.

## 12. Acceptance gates, которые должны стать исполнимыми

### Contract gate

- все JSON/YAML schemas валидируются;
- valid и invalid examples имеют ожидаемый результат;
- fixtures имеют provenance и SHA-256;
- oracle versions pinned;
- scan незаполненных значений и непинненных версий проходит;
- public contract changes отражены в ADR.

### Parser gate

- Gatling counts/errors/percentriles совпадают с version-matched HTML oracle;
- JTL совпадает с pinned comparator/dashboard oracle;
- unknown formats fail closed с стабильным reason code;
- 10M JTL benchmark проходит установленный CPU/RAM/time budget;
- parser memory bounded.

### Determinism gate

- одинаковые inputs/config/module/baseline/engine versions дают одинаковые
  canonical verdict bytes/hash;
- повторный analysis создаёт новый provenance record, не переписывая старый;
- renderer/AI не могут менять verdict;
- canonical payload не содержит wall-clock, random UUID или environment noise.

### Capability isolation gate

- отсутствие VM/Influx/APM не блокирует load-only analysis;
- отсутствие JVM/DB input отключает только зависимые modules;
- optional module timeout/error не останавливает остальные modules;
- module statuses и reason codes отражены в coverage;
- `NO_VERDICT` перечисляет только policy-blocking reasons.

### Portability gate

- local mode не требует network и external DB;
- no-egress проверяется тестом, а не только документацией;
- bundle можно экспортировать, импортировать и проанализировать повторно;
- server использует тот же core API;
- canonical verdict parity проверяется между CLI и server shell.

### UX gate

- overview показывает validity, policy verdict и coverage;
- top incidents ограничены 3–7;
- каждый finding/incident раскрывается до evidence/source hash;
- HTML self-contained;
- raw/normalized data доступны без превращения overview в dashboard wall.

### Security/data gate

- secrets не попадают в bundle/config/log;
- archive traversal/symlinks/size bombs обрабатываются безопасно;
- request/response bodies не собираются по умолчанию;
- SQL/APM redaction policy тестируется;
- external module policy принудительно соблюдается;
- retention/delete events аудируются.

## 13. Риски перехода

### Риск: сохранить server-first bias под новым названием

Если просто добавить CLI к существующему Spring/PostgreSQL дизайну, получится
сервер, запускаемый локально, а не portable core. Проверка: Phase 1 должен
работать без Spring server lifecycle и внешней БД.

### Риск: считать Gradle modules полноценной модульностью

Без manifest, capabilities, statuses и compatibility tests новый detector всё
равно потребует изменения core orchestration.

### Риск: размыть `NO_VERDICT`

Если любая отсутствующая метрика продолжит блокировать verdict, продукт не
выполнит главное обещание L0/L1 onboarding.

### Риск: попытаться реализовать весь PRC как один MVP

Server, history, observability, domain packs и AI одновременно снова создадут
длинный путь до первой проверяемой пользовательской ценности.

### Риск: потерять уже выполненную форматную экспертизу

PRC v0.6 менее конкретен по форматам, чем Stage 0/1. При полном переписывании
можно случайно выбросить проверенные Gatling/JTL layouts и oracle methodology.

### Риск: объявить Phase 0 закрытым только по документам

Сейчас отсутствуют фактические contracts/fixtures/verifier/milestone files.
Утверждённый дизайн закрытия этапа прямо запрещает считать рецепт evidence:
`docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md:29-32`.

## 14. Рекомендуемое решение

1. Формально принять или скорректировать PRC v0.6.
2. Разрешить неоднозначности из раздела 9 этого анализа.
3. Создать ADR перехода v0.5 -> v0.6.
4. Переписать development plan и Stage 1 spec под portable local foundation.
5. Перенести существующие parser/oracle/benchmark contracts в новый Phase 0.
6. Заморозить `RunBundle`, canonical outputs и module/capability schemas.
7. Фактически создать fixtures/verifier и закрыть Phase 0 evidence-bearing
   milestone report.
8. Только после этого начинать production implementation.

Не рекомендуется начинать реализацию по текущему
`docs/stage-1-spec.md`: она закрепит наиболее дорогую для последующей переделки
часть старой server-first архитектуры.

## 15. Что не было проверено

- Remote GitHub PR/issues/milestones и состояние CI не исследовались; аудит
  ограничен локальным worktree.
- Reference repositories `pg_profile_checks` и `lt_cycle_task` повторно не
  анализировались; оценивалось их отражение в текущих документах.
- Не выполнялись build/tests, поскольку production/test/build tree отсутствует.
- Не проводился benchmark: generator, fixture и parser ещё не созданы.
- Не проверялась реальная инфраструктура пилота; questionnaire не заполнен.
- Не утверждалась трудоёмкость новой этапности.
- Не выполнялось security threat modeling; перечислены необходимые области.

Эти ограничения не меняют основной архитектурный вывод, но должны учитываться
перед объявлением любого milestone gate пройденным.

## 16. Handoff для следующей модели

### Задача

На основании этого анализа и PRC/PRD v0.6 подготовить согласованный набор
нормативных изменений, не начиная production implementation.

### Обязательные входы

Прочитать полностью:

1. `lt-verdict-prc-prd-v0.6.md`;
2. `docs/development-process.md`;
3. `docs/stage-1-spec.md`;
4. `docs/superpowers/plans/2026-08-10-development-plan.md`;
5. `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md`;
6. `docs/superpowers/plans/2026-08-10-stage-0-closure-stage-1-contract.md`;
7. `docs/decisions-2026-07-20.md`;
8. `docs/admin-questionnaire.md`;
9. этот анализ.

### Требуемый результат следующего шага

До изменения production-кода:

- предложить решения по неоднозначностям раздела 9;
- подготовить migration mapping v0.5 -> v0.6;
- определить точный Phase 0 и Phase 1 scope;
- спроектировать `RunBundle`, module/capability и canonical output contracts;
- перечислить ADR и public contract changes;
- определить observable acceptance criteria и verification commands;
- указать, какие существующие документы заменяются, а какие остаются
  историческими;
- сохранить parser/oracle evidence и не переносить server/APM/AI в критический
  путь local MVP.

### Запреты

- не считать текущий Stage 1 совместимым с v0.6 без перепроектирования;
- не объявлять существование production-кода или тестов;
- не считать документ закрытием Phase 0 без fixtures/verifier/evidence;
- не делать PostgreSQL, OIDC, VM, APM или AI обязательными для local foundation;
- не смешивать execution lifecycle, validity, policy verdict и coverage;
- не помещать расширяемые findings/incidents в стабильный policy verdict;
- не изменять test scripts ради базового artifact-only анализа;
- не принимать архитектурные решения без ADR/plan согласно
  `docs/development-process.md`.

### Критерий успеха handoff

Другая модель должна суметь по этому файлу:

1. точно описать текущее состояние;
2. не принять документационную подготовку за реализацию;
3. сохранить доказательно сильные части v0.5;
4. избежать server-first реализации под видом portable core;
5. подготовить проверяемый plan/spec transition к v0.6.

## 17. Команды проверки аудита

Использовались read-only команды:

```powershell
git status --short --branch
git log --oneline --decorate --graph -25
git diff --stat origin/agent/add-admin-questionnaire...HEAD
git ls-files
rg --files
rg -n '^#{1,3} ' lt-verdict-prc-prd-v0.6.md docs
Test-Path docs/contracts
git diff --check
```

Финальная проверка перед созданием этого документа показала:

- `tracked_files=22`;
- `production_or_test_files=0`;
- `milestone_files=0`;
- `adr_files=0`;
- исходные незатреканные пользовательские файлы сохранены.

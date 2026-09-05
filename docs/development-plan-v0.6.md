# LT Verdict — план разработки v0.6

**Статус:** текущий план

**Baseline:** [`PRC/PRD v0.6`](../lt-verdict-prc-prd-v0.6.md)

**Уточнения:**
[`local-first MVP delta design`](superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md)
и [`alignment review`](prc-v0.6-alignment-review.md)

## Governance-идентификаторы

В плане v0.6 каждый `Slice N` соответствует проектному `Stage N` из
[регламента разработки](development-process.md). Для него используются GitHub
Milestone `Stage N — <название>`, отчёт `docs/milestones/stage-N.md` и после
явного разрешения пользователя — подписанный аннотированный тег `stage-N`.

## Граница MVP

MVP — локально запускаемое приложение с обязательным Web UI и постоянным
сетевым доступом к внешним источникам. Серверное развёртывание не входит в
критический путь. Все источники также сохраняют ручной файловый fallback.

Общие инварианты:

- deterministic verdict не зависит от renderer, correlation или AI;
- VictoriaMetrics, Prometheus, InfluxDB, OpenSearch 2.6 и Grafana используют
  общий гибко настраиваемый request governor с default `0.5 RPS` на origin;
- raw data берутся из первичных источников; Grafana используется для ссылок и
  optional render;
- новые контракты, ADR и dependencies появляются только вместе с использующим
  их slice.

## Срезы

Текущий gate: `Slice 1 — READY FOR REVIEW`.

2026-09-05 пользователь согласовал параллельную разработку локального просмотра
с графиками и JSON/HTML export перед источниками. Первая поставка реализует
часть Slices 8–9 по [короткому плану](superpowers/plans/2026-09-05-local-review-pilot.md).
Она не закрывает gate Slice 1 и не заменяет остальные требования MVP.

| Slice | Статус | Результат | Exit gate |
| --- | --- | --- | --- |
| 0. Minimal foundation | **COMPLETE** | Нормативный v0.6, два контракта, JTL/`simulation.log` examples, один offline verifier | `python tools/verify_slice0.py` проходит без dependencies |
| 1. Local usable shell | **READY FOR REVIEW** | Одна команда запуска, loopback backend, Web UI/CLI, ручная загрузка JMeter JTL и Gatling logs, deterministic metrics/verdict и strict `policy.v1`; [дизайн](superpowers/specs/2026-08-31-slice-1-local-usable-shell-design.md), [план](superpowers/plans/2026-08-31-slice-1-local-usable-shell.md), [candidate report](milestones/stage-1.md) | Локальные gates проходят; green runtime/performance CI обязателен до приёмки |
| 2. Primary online sources | PLANNED | VictoriaMetrics, Prometheus, InfluxDB и PostgreSQL; online pre/post DML snapshots, `pg_stat_statements`, `pg_profile`; общий governor | Каждый источник даёт raw snapshot; отказ одного не ломает load-only result; ручной fallback эквивалентен |
| 3. Jenkins workflow | PLANNED | REST skeleton для существующих jobs, trigger, queue/build tracking, изоляция credentials | Из UI запускается настроенная job и определяется её build без повторного POST при неизвестном outcome |
| 4. Artifact collection | PLANNED | Автоматическое скачивание архивированного JTL/`simulation.log` из Jenkins; ручная загрузка любого файла | Artifact проверяется по size/SHA-256; отсутствие переводит run в ожидание, не создаёт ложный verdict |
| 5. Capacity analysis | PLANNED | Отдельный `capacity_step` режим, таблица ступеней и консервативная оценка максимума | Результат различает bounded/lower/upper/indeterminate и не принимает насыщение генератора за предел продукта |
| 6. JVM and OpenShift | PLANNED | JVM и OpenShift metric packs | Findings строятся только по доступным capabilities и ссылаются на raw evidence |
| 7. OpenSearch | PLANNED | Ошибки OpenSearch 2.6 за окно: services, types/fingerprints, frequency, distribution; overlay на прочие графики; correlation opt-in | Error report и overlay работают с governor; correlation failure не меняет verdict |
| 8. Charts and comparison | IN PROGRESS | Сохранённые analyses и SVG load charts в первой поставке; далее static renderer, Grafana links, baseline comparison и N-run dynamics | Сравнение использует сохранённые RunBundles и не повторяет external queries |
| 9. Reports and publishing | IN PROGRESS | JSON и self-contained HTML в первой поставке; далее AsciiDoc, Confluence-ready output и fail-soft Confluence REST skeleton | Все форматы строятся из одного result; transport failure не меняет analysis |
| 10. Advisory add-ons | PLANNED | Grafana rendered evidence, рекомендательный analysis через headless GigaCode (fork Qwen Code 0.21.1) и GigaCode Skill для audit/patch адаптации НТ-скриптов | AI output явно advisory; Skill проверяет platform tags/invariants и не применяет patch без подтверждения |

Каждый следующий slice получает собственные короткие spec и implementation
plan. Он не обязан ждать не связанных с ним optional add-ons, но не дублирует
core или contracts предыдущих slices.

## Post-MVP

- server deployment, shared catalog/history, RBAC/SSO и object storage;
- произвольный SSH/SCP pull с генераторов вне Jenkins artifacts;
- code-aware RCA и автоматическая causal inference;
- автоматическое изменение НТ-скрипта без подтверждения;
- дополнительные источники и exporters вне перечисленных выше.

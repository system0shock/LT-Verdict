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
- raw data берутся из первичных источников; Grafana может быть transport proxy
  к VM, а также предоставлять ссылки и optional render;
- новые контракты, ADR и dependencies появляются только вместе с использующим
  их slice.

## Срезы

Текущий gate: `Slice 1 — READY FOR REVIEW`.

2026-09-05 пользователь согласовал параллельную разработку локального просмотра
с графиками и JSON/HTML export перед источниками. Первая поставка реализует
часть Slices 8–9 по [короткому плану](superpowers/plans/2026-09-05-local-review-pilot.md).
Она не закрывает gate Slice 1 и не заменяет остальные требования MVP.

Следующее согласованное расширение — ручной и статистический выбор фиксированного
baseline по [ADR 0004](adr/0004-local-baseline-selection.md) и
[плану реализации](superpowers/plans/2026-09-05-local-baseline-comparison.md).
Оно добавляет overall metric comparison; N-run history, chart overlays,
transaction comparison и comparison exports остаются отдельными шагами Slice 8.

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
| 9. Reports and publishing | IN PROGRESS | JSON, self-contained HTML и local AsciiDoc в первой поставке; далее Confluence-ready output и fail-soft Confluence REST skeleton | Все форматы строятся из одного result; transport failure не меняет analysis |
| 10. Advisory add-ons | PLANNED | Grafana rendered evidence, рекомендательный analysis через headless GigaCode (fork Qwen Code 0.21.1) и GigaCode Skill для audit/patch адаптации НТ-скриптов | AI output явно advisory; Skill проверяет platform tags/invariants и не применяет patch без подтверждения |

Каждый следующий slice получает собственные короткие spec и implementation
plan. Он не обязан ждать не связанных с ним optional add-ons, но не дублирует
core или contracts предыдущих slices.

## Ближайший приоритет: статистический анализ, корреляция и capacity

**Решение пользователя, 2026-09-05:** основная ценность — анализ ресурсов,
связь JMeter/Gatling с инфраструктурными метриками из VM и поиск максимальной
устойчивой нагрузки. Эта очередь заменяет предложенный порядок «N-run history,
затем connectors»; нумерация и полный scope Slices 1–10 сохраняются.

Адаптацию запросов к стенду выполняют локальные модели. На текущем стенде VM
доступна через Grafana API, но endpoint/auth/plugin mapping не являются
предметом ближайшей работы аналитического ядра. Вход ядра — сохранённые load
данные и time-series snapshots с units, labels, timestamps, query interval и
provenance. Различия transport не должны менять вычисления. Необходимый
snapshot contract фиксируется вместе с первой использующей его поставкой.

### 1. Статистическая основа и анализ ресурсов

- Согласование временных рядов одного прогона: временная сетка, интервалы
  наблюдения, доступность данных, известное смещение часов и aggregation по
  типу метрики. Пропуски не превращаются в нули; готовые percentiles не
  усредняются для получения общего percentile.
- Общие evaluation windows и сегментация ступеней: сначала explicit markers
  или manifest; inferred stages отдельно помечаются и требуют оценки качества.
- Анализ уровня, разброса, тренда, устойчивости, смены режима и насыщения.
  Первая прикладная группа — CPU, memory, disk I/O и network на узлах, workload
  и генераторах; throttling, GC, OOM/restarts — при наличии соответствующих
  JVM/OpenShift series. Missing capability означает неполное покрытие, не норму.
- **Результат:** по сохранённому набору данных получаем воспроизводимые findings
  с сущностью, интервалом, наблюдаемым значением, основанием и ссылкой на evidence.

### 2. Корреляция load ↔ infrastructure

- Реализовать предусмотренный delta-spec §16 pipeline: разрешённые пары и
  topology constraints, load-conditioned residuals, partial Spearman,
  ограниченные lagged cross-correlations, порядок change points и поправка
  на множественные проверки. Не искать «всё со всем».
- Различать совместный рост из-за увеличения заданной нагрузки и связь внутри
  ступени. Учитывать request mix, concurrency и replica count при доступности.
  Фактический RPS может быть результатом деградации: не использовать его как
  безусловный фильтр или единственную контрольную переменную, скрывающую эффект.
- Проверять недостаточный объём данных, постоянные ряды, gaps и автокорреляцию;
  высокий коэффициент сам по себе не даёт HIGH confidence или causal finding.
- **Результат:** объяснимые ассоциации с направлением, лагом, эффектом, размером
  пересечения наблюдений и ограничениями. Нужны проверки на общей ступени без
  дополнительной связи, известном lag и отсутствии связи. Корреляция не меняет
  deterministic policy verdict.

### 3. Capacity analysis и объяснение ограничения

- На общей сегментации считать target/achieved load, latency/errors, SLA,
  устойчивость и validity каждой ступени по delta-spec §12.
- Выдавать `BOUNDED`, `LOWER_BOUND`, `UPPER_BOUND` или `INDETERMINATE`, а не
  единственное число без основания. `capacity_knee` остаётся диагностикой,
  не заменяет verified SLA capacity.
- Связать ступени с resource findings и correlation evidence. Различать
  ограничение продукта, нехватку тредов/ресурсов генератора и неизвестную причину.
  Падение достигнутого RPS само по себе не доказывает насыщение генератора.
- **Результат:** таблица ступеней, подтверждённая граница и объяснение с evidence;
  проверки: все ступени PASS, первая FAIL, PASS→FAIL, PASS→FAIL→PASS, отсутствие
  SLA, gaps и ограничение генератора.

### Параллельность и границы

После согласования общего snapshot/window/stage contract независимо развиваются
resource/correlation calculations и capacity/SLA calculations. Временная
сегментация не дублируется; её контракт — общая предварительная зависимость.
Локальные модели параллельно адаптируют acquisition к реальному стенду.

Минимальные UI/evidence views и необходимые тесты входят в каждую поставку.
N-run history, расширение baseline, косметика и новые export formats уступают
этим трём поставкам. Jenkins, остальные connectors, OpenSearch, publishing и
advisory-функции остаются в полном MVP, но не блокируют snapshot-based analysis.
Это изменение очереди, не объявление этих механизмов реализованными и не
закрытие milestone. Детальные алгоритмы, thresholds, contracts и dependencies
утверждаются в отдельных коротких spec/планах перед соответствующей реализацией.

## Post-MVP

- server deployment, shared catalog/history, RBAC/SSO и object storage;
- произвольный SSH/SCP pull с генераторов вне Jenkins artifacts;
- code-aware RCA и автоматическая causal inference;
- автоматическое изменение НТ-скрипта без подтверждения;
- дополнительные источники и exporters вне перечисленных выше.

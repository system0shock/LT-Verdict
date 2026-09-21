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

## Подготовка к приёмке, 2026-09-22

По решению пользователя сквозной путь будет проверяться отдельно. Текущие результаты [плана подготовки](superpowers/plans/2026-09-21-mvp-acceptance-readiness.md) собраны в [отчёте готовности](mvp-readiness-2026-09-22.md): AI product integration, оставшиеся analytics/UI и integration/report функции, локальные проверки и [пакет приёмки](mvp-acceptance-checklist.md).

Уточнение к исторической очереди ниже: correlation selector уже интегрирован; baseline conditions имеют API/UI и сохранение, focused проверки пройдены. Их не реализовывать повторно. AI evidence/validator/store и production runtime/API/UI подключены; semantic acceptance остаётся открытой. Пилот ModelStudio остановлен пользователем после 20 запросов; новые запросы и массовая калибровка в подготовку не входят. Jenkins возвращён в очередь утверждённым планом подготовки полного MVP.

Исторические OPEN/PLANNED ниже описывают прежние checkpoints; фактический статус новых работ фиксируется в handoff и ledger. Готовность реализации не заменяет внешние CI и пользовательскую приёмку.

## Срезы

### Актуальное решение и бэклог, 2026-09-06

Пользователь признал полный correlation development-repeat удачным и принял
остаточные **7-13% шумных отчётов** (точно 7.7-13.2% в шести configurations)
как ограничение. Оптимизация отложена; она не блокирует следующий фичевый трек.
[Результаты и границы принятия](statistical-validation-correlation-full-v1.md).
Исторический FAIL v1 сохраняется: критерий <=5% не выполнен, новые независимые
seeds не использовались. Принятие не распространяется автоматически на genuine
partial или two-run T02/T03 и не означает готовность production integration.

Текущий порядок до первой сквозной приёмки:

1. **Advisory AI, активный трек.** Qwen Code0.21.1 разрешён пользователем вместо
   GigaCode и скачан отдельно. Остались runtime/isolation capability probe,
   bounded evidence input, schema/references validation, отдельное сохранение
   advice, явный запуск из UI и fail-soft errors. Дизайн и discovery готовы;
   production runner и model smoke-test ещё не реализованы/не выполнены.
2. **Подключить принятый correlation selector к продукту.** Перенести проверенную
   policy из test-only эксперимента в существующий путь расчёта/выдачи с теми же
   параметрами, limitations и регрессионными проверками; без нового подбора
   методов и порогов. Существующие SLA/verdict и raw evidence сохраняются.
   Это необходимая интеграция, а не отложенная оптимизация.
3. **Завершить ручной comparison workflow.** `BASELINE-CONDITIONS-01`:
   UI/API и сохранение подтверждения условий пары с корректным reload.
   Observed delta не становится доказанной межпрогонной регрессией;
   нерешённый шум two-run p50 отмечается отдельно от correlation limitation.
4. **Первая сквозная приёмка.** Настоящие источники -> анализ/SLA -> сохранение
   и reload -> capacity -> comparison -> AI advisory и экспорт.
   Проверяются отказ источника, missing data и отказ AI. Подготовка fixtures
   может идти параллельно предыдущим задачам.

После этой приёмки остаётся полный scope MVP v0.6; порядок следующих независимых
фичевых поставок предлагается подтвердить по завершении активного трека:

- Jenkins workflow и автоматический сбор JTL/`simulation.log` artifacts.
- JVM/OpenShift metric packs; оставшийся OpenSearch overlay/correlation opt-in.
- N-run dynamics, оставшиеся charts/baseline/transaction comparison функции.
- Оставшиеся export/Grafana links/render и Confluence-ready publishing skeleton.
- GigaCode Skill для audit/patch адаптации нагрузочных тестов.
- Незакрытые source compatibility проверки, runtime/performance CI,
  full secret/history checks, code review и формальные milestone gates.

Sources и capacity уже реализованы и локально проверены; перечисленные внешние
проверки не отменяют этот прогресс. Новые коэффициенты, дальнейшая калибровка
correlation noise и повторные массовые эксперименты без новой задачи не запускаются.

### Статусы milestone и предыдущая детализация

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
| 2. Primary online sources | IN PROGRESS | Реализованы VictoriaMetrics, Prometheus, InfluxDB и PostgreSQL pre/post, `pg_stat_statements`, supplementary `pg_profile`, multi-source и offline replay; PostgreSQL 16.15 / pg_profile 4.8 проверены на синтетике, внешний CI открыт | Каждый источник даёт raw snapshot; отказ одного не ломает load-only result; ручной fallback эквивалентен |
| 3. Jenkins workflow | PLANNED | REST skeleton для существующих jobs, trigger, queue/build tracking, изоляция credentials | Из UI запускается настроенная job и определяется её build без повторного POST при неизвестном outcome |
| 4. Artifact collection | PLANNED | Автоматическое скачивание архивированного JTL/`simulation.log` из Jenkins; ручная загрузка любого файла | Artifact проверяется по size/SHA-256; отсутствие переводит run в ожидание, не создаёт ложный verdict |
| 5. Capacity analysis | IMPLEMENTED / LOCALLY VERIFIED | CLI/API/UI, отдельный `capacity_step` режим, таблица ступеней и консервативные bounds реализованы; формальный milestone не закрыт | Результат различает bounded/lower/upper/indeterminate и не принимает насыщение генератора за предел продукта |
| 6. JVM and OpenShift | PLANNED | JVM и OpenShift metric packs | Findings строятся только по доступным capabilities и ссылаются на raw evidence |
| 7. OpenSearch | IN PROGRESS | Bounded error context и offline replay реализованы; overlay на прочие графики и correlation opt-in остаются | Error report и overlay работают с governor; correlation failure не меняет verdict |
| 8. Charts and comparison | IN PROGRESS | Сохранённые analyses и SVG load charts в первой поставке; далее static renderer, Grafana links, baseline comparison и N-run dynamics | Сравнение использует сохранённые RunBundles и не повторяет external queries |
| 9. Reports and publishing | IN PROGRESS | JSON, self-contained HTML и local AsciiDoc в первой поставке; далее Confluence-ready output и fail-soft Confluence REST skeleton | Все форматы строятся из одного result; transport failure не меняет analysis |
| 10. Advisory add-ons | PLANNED | Grafana rendered evidence, рекомендательный analysis через headless GigaCode (fork Qwen Code 0.21.1) и GigaCode Skill для audit/patch адаптации НТ-скриптов | AI output явно advisory; Skill проверяет platform tags/invariants и не применяет patch без подтверждения |

Каждый следующий slice получает собственные короткие spec и implementation
plan. Он не обязан ждать не связанных с ним optional add-ons, но не дублирует
core или contracts предыдущих slices.

## Текущая очередь до первой сквозной приёмки

**Согласовано пользователем, 2026-09-05**, после реализации диагностического
среза (`8ab0a0e`). Этот порядок заменяет предыдущую ближайшую очередь, но
не сокращает полный MVP v0.6 и не закрывает milestone gates.

1. **Источники.** Сначала рабочий маршрут VictoriaMetrics через Grafana proxy
   в существующий snapshot/window contract: credentials, request governor,
   таймауты, явные пропуски, сохранение raw data и ручной fallback.
   Повторный анализ сохранённого RunBundle не повторяет external queries.
2. **Capacity.** Явные ступени, target/achieved load, SLA и консервативные
   границы устойчивой нагрузки. Ограничение генератора не считается пределом
   продукта; недостаток evidence даёт неопределённый результат.
3. **Проверка корректности стат-анализа.** Углублённые сценарии с известным
   ответом: обнаружение эффектов и ложные находки, общий load trend, gaps,
   разные длительности окон, сравнение двух прогонов и ограничения генератора.
   Это дополнительная проверка: тесты источников и capacity входят сразу
   в пункты 1–2, а не откладываются до пункта 3.
4. **ИИ.** Advisory analysis готовых evidence: наблюдения отдельно от гипотез,
   ссылки на evidence IDs, ограничения и рекомендуемые проверки.
   ИИ не меняет deterministic verdict и не скрывает `NOT_ESTIMATED`;
   отказ runner не ломает основной отчёт.
5. **Приёмка на синтетике.** Полный пользовательский путь через настоящие
   источники, сохранение, повторное открытие, capacity, сравнение и AI-отчёт.
   Сценарии и ожидаемые результаты готовятся по ходу пунктов 1–4.

Под источниками здесь понимаются источники метрик, не S3/MinIO buckets.
Первый рабочий connector не заменяет остальные источники полного MVP.
Последующее решение пользователя: блок 1 охватывает **все источники** MVP.
Сначала VM/Prometheus + Grafana proxy и общий HTTP governor; затем InfluxDB,
PostgreSQL и OpenSearch независимыми поставками. Метрики, события и SQL snapshots
не объединяются искусственно в один формат. Первая поставка описана в
[дизайне online sources](superpowers/specs/2026-09-05-online-sources-design.md).
Первая поставка локально проверена на `feat/online-sources` по
[implementation plan](superpowers/plans/2026-09-05-online-sources.md): CLI/UI,
Prometheus/VM direct и Grafana proxy, общий governor и offline replay.
Проверки: 215 JVM passed + 2 прежних skips; 34 browser tests passed.
Продолжение на `feat/remaining-sources`: InfluxQL, OpenSearch, PostgreSQL pre/post,
несколько HTTP-профилей на общей сетке и offline replay реализованы.
Локальная проверка 2026-09-06: installDist прошёл; заключительный test/check
с живой PostgreSQL — 289 JVM tests, 287 passed / 2 прежних Windows skips.
Три real-backend browser source workflows прошли без API mocks.
После разрешения пользователя: dedicated PostgreSQL 16.15 live suite11/11
без пропусков; pg_profile 4.8 read-only report и CLI capture/hash прошли.
PostgreSQL 15, TLS и внешний CI остаются непроверенными. Поэтому блок
источников не объявляется принятым целиком; production-код при live-проверке
не менялся. Детали воспроизведения — в PostgreSQL plan ниже.
Границы продолжения согласованы в
[дизайне оставшихся источников](superpowers/specs/2026-09-05-remaining-sources-design.md):
несколько источников на анализ, отдельный PostgreSQL pre/post, ручной импорт
и offline replay. Реализация описана в планах
[InfluxQL](superpowers/plans/2026-09-05-influxdb-source.md),
[PostgreSQL](superpowers/plans/2026-09-05-postgresql-source.md) и
[multiple sources](superpowers/plans/2026-09-05-multiple-sources.md).
Capacity из этой очереди уже реализован и локально проверен; следующий активный
трек — advisory AI. Проверка настоящих источников не заменяется unit fixtures
и сохраняется перед сквозной приёмкой.
Jenkins/artifacts, JVM/OpenShift packs, OpenSearch overlay,
оставшиеся charts/comparison/export функции и GigaCode Skill сохраняются
в scope таблицы Slices 2–10; их порядок после пилота ещё не утверждён.
Runtime/performance CI и пользовательская приёмка остаются обязательными gates.

## Предыдущая очередь: статистический анализ, корреляция и capacity

**Предыдущее решение пользователя, 2026-09-05:** основная ценность — анализ ресурсов,
связь JMeter/Gatling с инфраструктурными метриками из VM и поиск максимальной
устойчивой нагрузки. Эта очередь заменяет предложенный порядок «N-run history,
затем connectors»; нумерация и полный scope Slices 1–10 сохраняются.

Адаптацию запросов к стенду выполняют локальные модели. На текущем стенде VM
доступна через Grafana API, но endpoint/auth/plugin mapping не являются
предметом ближайшей работы аналитического ядра. Вход ядра — сохранённые load
данные и time-series snapshots с units, labels, timestamps, query interval и
provenance. Различия transport не должны менять вычисления. Необходимый
snapshot contract фиксируется вместе с первой использующей его поставкой.

Первая поставка конкретизирована в
[resource statistics design](superpowers/specs/2026-09-05-resource-statistics-design.md)
(Accepted; реализация включает бизнес- и ресурсные SLA на общих окнах).

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

- Первый срез: явный план пар и окон, topology basis, descriptive Spearman,
  partial rank correlation и ограниченный профиль лагов. Дополняется эпизодами
  median/MAD относительно явного reference-окна и сравнением двух окон с
  порогами практической значимости. Не искать «всё со всем».
- Порядок change points, inferential tests и поправки на множественные проверки
  отложены: текущая неопределённость — `NOT_ESTIMATED`, без p-values.
  Pearson, Kendall и условия включения следующих методов сохранены в
  [roadmap методов](statistical-method-roadmap.md); границы первого среза —
  [ADR 0006](adr/0006-bounded-load-resource-correlation.md).
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

2026-09-06 пользователь разрешил capacity параллельно независимой проверке
статанализа, с заранее описанной воспроизводимой методикой без подгонки.
Подготовлены [методика v1](statistical-validation-methodology-v1.md),
[исходный результат](statistical-validation-results-v1.md) и
[capacity design](superpowers/specs/2026-09-06-capacity-design.md).
Capacity согласован; методика дополнена согласованным Applicability разделом:
10system families,paired interventions и ограничения наблюдаемости.
CORRECTNESS/APPLICABILITY выполняются до массовой USEFULNESS calibration.
Два deterministic batch с независимыми эталонами зафиксированы до production-запусков:
48/48 и 50/50 MATCH. После интеграции capacity прежние 48 полных outputs
не изменились. Полная статистическая приёмка v1 завершена: CORRECTNESS PASS
(109/109), APPLICABILITY PASS (421 cases / 830 runs), USEFULNESS FAIL
(28000/28000 reports, 8 из 28 configurations не прошли шумовой gate).
Ошибок исполнения нет; пороги/эталоны после результатов не менялись. Полный
statistical gate НЕ пройден: автокоррелированные multi-pair/lag кандидаты и
часть two-run сравнений слишком шумные. Исправления и новая независимая
приёмка — отдельная следующая задача; этот отчёт не разрешает выдавать
candidate как подтверждённую причину или регрессию. Hashes, полная таблица,
команды и ограничения — в [отчёте](statistical-validation-results-v1.md).

После согласования общего snapshot/window/stage contract независимо развиваются
resource/correlation calculations и capacity/SLA calculations. Временная
сегментация не дублируется; её контракт — общая предварительная зависимость.
Локальные модели параллельно адаптируют acquisition к реальному стенду.

Минимальные UI/evidence views и необходимые тесты входят в каждую поставку.
В предыдущей очереди N-run history, расширение baseline, косметика и новые
export formats уступали этим трём поставкам. Jenkins, остальные connectors, OpenSearch, publishing и
advisory-функции остаются в полном MVP, но не блокируют snapshot-based analysis.
Это изменение очереди, не объявление этих механизмов реализованными и не
закрытие milestone. Детальные алгоритмы, thresholds, contracts и dependencies
утверждаются в отдельных коротких spec/планах перед соответствующей реализацией.

## Открытый MVP gap: подтверждение условий ручной пары

`BASELINE-CONDITIONS-01`, статус OPEN. При ручном выборе baseline сравнение
двух реальных runs всегда получает `UNCONFIRMED`. Сейчас `USER_CONFIRMED`
доступен только для statistical selection из3..20кандидатов. Выбор baseline
и подтверждение одинаковых условий — разные действия; третьего прогона
нельзя требовать или фабриковать для подтверждения настоящей пары.

Последствие: численные deltas доступны, но material change остаётся
DESCRIPTIVE; обязательный confirmed two-run сценарий Applicability не закрыт.
Нельзя выдавать `UNCONFIRMED` за отсутствие деградации или автоматически
подтверждать условия по похожим метрикам.

Критерий закрытия отдельной bounded задачи: явное подтверждение пользователем
условий для конкретных baseline/current analysis IDs, сохранение и reload этого
решения, отсутствие влияния на числа/SLA, tests confirmed/unconfirmed и защита
от переноса подтверждения на другую пару. Публичный контракт согласовать до
реализации. До закрытия — CAPABILITY_GAP; статистическая приёмка не подменяет
его фиктивным третьим run. Текущая capacity integration этот gap не исправляет.

Согласованное уточнение: этот gap относится к UI/API и хранению решения,
не является обязательной зависимостью статистической приёмки. Для проверки
вычислительного пути применяется явный контекст одного сравнения, заданный
в frozen manifest по условиям генерации. Он не сохраняется в baseline и
не переносится между вызовами. Статистический gate и end-to-end продуктовый
workflow оцениваются раздельно; статус продуктового gap остаётся OPEN.

## Post-MVP

- server deployment, shared catalog/history, RBAC/SSO и object storage;
- произвольный SSH/SCP pull с генераторов вне Jenkins artifacts;
- code-aware RCA и автоматическая causal inference;
- автоматическое изменение НТ-скрипта без подтверждения;
- дополнительные источники и exporters вне перечисленных выше.

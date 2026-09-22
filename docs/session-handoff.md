# LT Verdict — передача сессии

## Передача 2026-09-22 (ночь): ветка fix/input-unit-fill-coverage

Ворктри `.worktrees/local-baseline-comparison` переключён с `feat/remaining-sources`
на новую локальную ветку `fix/input-unit-fill-coverage` от `c100ed2`. Push не
выполнялся, ветка существует только локально. Запись «Подготовка 2026-09-22:
актуальная точка» ниже остаётся в силе по составу поставки, но её контекст
устарел в одном: `c100ed2` (200 файлов, 36791 вставка) уже в
`origin/feat/remaining-sources`, поэтому аналитический слой больше не является
незакоммиченным.

### Пять коммитов ветки

- `8717cab fix(ingest)`: timestamps в `1000000000..99999999999` отклоняются как
  `INVALID_SAMPLE_TIMESTAMP`. Границы опубликованы в `limits` identity как
  `timestamp_epoch_millis_unit_suspect_min/max`; golden-фикстура
  `analysis-identity.v1.json`, её `.sha256` и две записи `fixtures/slice1/manifest.json`
  перегенерированы байт-в-байт. Поправка к ADR 0003, пользовательская дока, CHANGELOG.
- `afb8b2f fix(sources)`: InfluxQL `fill(...)` разрешён только для `null` и `none`;
  `0`, `previous`, `linear`, число и пустой аргумент дают `SOURCE_CONFIG_INVALID`.
- `5ba2a28 fix(analysis)`: статус онлайн-сбора и исчерпанный request budget дают
  `SOURCE_ACQUISITION_PARTIAL`, `SOURCE_ACQUISITION_FAILED`,
  `SOURCE_REQUEST_CAP_EXCEEDED` в `coverageReasons`, поэтому `analysis_coverage.status`
  становится `INCOMPLETE`. Применено в обычном и invalid-input путях.
- `230ef59 refactor(metrics)`: `MIN_DIAGNOSTIC_P95_SAMPLES` выводится из
  `MIN_P95_SAMPLES`, плюс тест, что identity публикует именно применяемый порог.
- `94240db docs`: `docs/analytics-scale-triage.md` и `docs/analytics-trend-detection.md`
  — границы работ, ничего не объявляют реализованным.

### Осознанное отклонение от первоначальной формулировки

Пункт 1 сделан не как нижняя граница правдоподобия, а как отклонение диапазона
epoch-seconds. Жёсткая граница потребовала бы переписать синтетические epoch
примерно в 20 тестовых файлах, включая Gatling `RUN ... 1 ...` в
`GatlingTextParserTest`, и не закрыла бы ничего сверх подмены единицы измерения.
Выбранный диапазон как epoch-millis означает 1970-01-12..1973-03-03, а как
epoch-seconds покрывает 2001..5138. Существующие фикстуры не затронуты: они
используют `1767225600000`, а PromQL-секунды идут мимо `LoadSample`.

### Проверено

- Полный JVM-набор offline: 67 классов, 386 тестов, 0 failures, 0 errors,
  9 skipped (env-gated корпусные раннеры, как и раньше). Без `clean`.
- `ktlintMainSourceCheck ktlintTestSourceCheck`: pass.
- `tools/verify_slice0.py`: OK; `unittest tools.test_verify_slice0
  tools.test_generate_jtl tools.test_onboard_test`: 9 тестов OK.
- Полный набор прогнан до последних doc-правок; код после этого не менялся.

### Не проверено

markdownlint (локально не установлен, пакет не скачивался — проверит CI),
`gradlew check installDist`, UI typecheck/lint/build/e2e, offline rebuild,
browser gates, performance probe.

### Известная нечистота истории

Строка CHANGELOG про `fill(...)` попала в `8717cab`, хотя относится к `afb8b2f`.
Исправление через `git commit --amend` заблокировано политикой инструмента;
обходить блокировку не следует. Оставить как есть либо исправить при squash PR.

### Следствия, которые нельзя «откатить» по ошибке

Изменение `limits` меняет `analysis_id` всех новых анализов. Сохранённые analyses
остаются immutable и читаемыми, переиспользования прежнего результата для того же
входа больше не будет — это штатное поведение по ADR 0003.

### Следующие пункты того же списка

- Пункт 4, L0-тренд: форма согласована пользователем — отдельный optional
  `trend-plan.v1` со своим semantic SHA-256 и модулем identity
  `resource-trend-evaluation` версии `1`, по образцу `correlation-plan.v1` и
  `capacity-plan.v1`. Содержательная часть — в `docs/analytics-trend-detection.md`.
- Пункт 5, документационные противоречия: `docs/user/slice-1-local-analysis.md`
  утверждает «p-values отсутствуют», тогда как `DiagnosticAnalysis.kt` публикует
  `correlation_headline_selection` с `holm_adjusted_p_value`; ограничения выводной
  семьи в `docs/user/*` не описаны вовсе. Эта запись ниже также держит
  `BASELINE-CONDITIONS-01` как OPEN, хотя ADR 0010 и `LocalApi.kt:392,408`
  реализованы.
- Пункт 7: `processed_bytes`, число прочитанных записей и число проигнорированных
  Gatling `ERROR`/`USER` в evidence. Сейчас `ParseReport.processedBytes` не
  персистится, а `diagnostics` на успехе пуст.
- Пункт 8: типизация `analysis_coverage`, `findings[]`, `evidence[]` в
  `analysis-result.schema.json` и включение схемы в `verify-policy-schema.mjs`
  (сейчас она не компилируется нигде).

### Не закрыто обзором и не должно потеряться

- Приёмка v1 описывает прежнюю версию продукта: 22 из 167 замороженных файлов
  отличаются, `CorrelationHeadlineSelection.kt` отсутствует в freeze, а во frozen
  `actual.jsonl` на 28000 отчётов нет ни одного `correlation_headline_selection`.
- JVM-селектор никогда не калибровался Monte-Carlo; принятые 7.7-13.2% получены
  в NumPy на development-seeds и выше pre-registered гейта 5%.
- Шум two-run сравнения (T02 36.8% при нулевой истинной дельте) не исправлен.
- Выводная семья поддерживается только в полосе: одно окно, один outcome,
  не более 16 гипотез, 30-240 непрерывных ячеек, вырожденные controls. Вне её
  находок не публикуется вовсе; форма не измерялась.
- Для контура с одной моделью: `AGENTS.md` маршрутизирует задачи на
  `gpt-5.6-luna/terra/sol`, что невыполнимо; host ModelStudio захардкожен в
  `tools/advisory_ai_runtime_relay.mjs:193`, поэтому замена модели — новый runner,
  новая версия контракта advice и новая приёмка, а не конфигурация.

### Окружение

Gradle offline: 7 классов примерно 15 s, полный JVM-набор 1m48s. Не запускать
`clean`: `build/stats-validation` и `build/ai-acceptance` содержат 6.58 ГБ
первичных трасс и 2.24 ГБ evidence-архивов, которые не закоммичены. Один
Gradle-процесс за раз.

## Подготовка 2026-09-22: актуальная точка

Продолжаем в `.worktrees/local-baseline-comparison`, ветка `feat/remaining-sources`.
Подготовлены production AI runner/API/UI, сохранённая аналитика и exports,
Jenkins workflow, Grafana render/link, Confluence-ready output, статический SVG
и ограниченный manifest-only onboarding. Предыдущая запись о нереализованном
production AI ниже историческая. Итоговые проверки и ограничения:
[отчёт подготовки](mvp-readiness-2026-09-22.md).

Пользователь отделил сквозную приёмку от подготовки. Новые запросы моделей,
реальные Jenkins jobs и публикация не запускались; пилот остановлен на 20.
Старые изменения и build artifacts сохранены; `clean`, staging, commit, push,
merge не выполнялись. Не повторять статистические/AI серии и не переоткрывать
принятые ограничения корреляций. Следующий отдельный этап —
[mvp-acceptance-checklist.md](mvp-acceptance-checklist.md).

## Возобновление 2026-09-21: историческая точка

Рабочее дерево `.worktrees/local-baseline-comparison`, ветка `feat/remaining-sources`.
Исходные изменения пользователя сохранены; staging, commit, push и merge не выполнялись.
Production AI runner/API/UI ещё не реализованы. Docker работает; ремонт сокета не понадобился.

Пользователь остановил текущий пилот после 20 попыток и запросил быструю проверку.
Серия: `build/ai-acceptance/v1/pilot-modelstudio-large-stream-2026-09-21`.
Модель: `deepseek-v4-flash-0731`, ModelStudio Token Plan Singapore.

- Выполнено 20 запросов: 18 структурированных ответов, 2 неуспешные попытки.
- Все 18 ответов прошли production AdviceOutputValidator и проверку привязки к evidence.
  JVM: 7 тестов, 0 failures/errors. Артефакт: `output-validation.json`.
- Case004: Qwen отклонил структурированный вывод. Case020: обрыв примерно на 120s,
  один upstream-запрос и три заблокированных дополнительных запроса, OOM=false.
- Найден внутренний HTTP timeout Qwen 120000ms. В isolated launcher добавлен
  поддержанный `QWEN_CODE_API_TIMEOUT_MS=600000`. Исходники и resolver proof подтверждают
  override; Node/PS, Host и полный preflight проходят. Новых live-запросов после исправления нет.
- Оставшиеся 40 попыток отменены пользователем. `continue-series.ps1` не запускался,
  continuation control не создан. `runtime-amendment.json` отмечен NOT_EXECUTED.
- Все 135 файлов первых 20 попыток проверены по сохранённым hash. Временных файлов ключа нет.
  Ключ хранится в Qwen и передаётся только relay через временный env-файл.
- Быстрая проверка содержания всех 18 ответов завершена тремя свежими AI-рецензентами
  по 6 ответов. Это не двойное независимое ревью и не полная приёмка исходных 60 попыток.
  Анонимизированные пакеты: `review-preparation/public`; копии evidence/advice сверены по SHA.

Итог: 9 ответов с подтверждёнными ошибками/необоснованными выводами, ещё 1 с нарушением правила лага; 8 без подтверждённых замечаний в рамках screening. Конфликт oracle/evidence review022 вынесен отдельно. Подробная root-adjudication: `build/ai-acceptance/v1/pilot-modelstudio-large-stream-2026-09-21/quick-check.md`. Новые запросы не запускать без нового поручения пользователя.

Текущий test-only runtime: client/Qwen 600s, launcher605s, host613s; relay response64MiB,
relay memory1GiB. По прямому указанию пользователя денежный резерв и ценовой фильтр отключены;
upstream не содержит max_tokens/max_completion_tokens/max_output_tokens. Один upstream на попытку.
Дополнительные tools/запросы блокируются. SSE parser собирает имена по индексам и допускает
пустые/null продолжения; безопасные finish reasons и token usage сохраняются без обязательной цены.

Корпус заморожен: 30 случаев, 29 уникальных evidence (006 и016 совпадают). Не экспортировать заново.
Oracle: 90 обязательных фактов, 116 ссылок. Пользователь принял AI-assisted calibration10/10
для пилота; это не human review. Стабильность повторов не проверена: повторные попытки отменены.

Предыдущие остановленные серии сохраняются отдельно: OpenRouter28×HTTP404; Beijing3×HTTP401;
Singapore3×HTTP200 с дефектом SSE; stream3×length/4096; provider-default1VALID+1exit137;
longwait2×PROVIDER_RESPONSE_TOO_LARGE на1MiB. Их результаты не смешивать с текущими20.
Подробная история и решения: `.superpowers/sdd/2026-09-06-advisory-ai-acceptance/progress.md`.

## Историческая передача 2026-09-06

Обновлено: 2026-09-06 после полного correlation development-repeat и решения пользователя.
Это сохранённая память проекта для следующей сессии, не утверждение готовности MVP.

## Актуальная точка продолжения

Этот раздел заменяет прежнюю рекомендацию ниже сначала продолжать снижение
шума до исходного gate. Пользователь признал эксперимент удачным, принял
**7-13% шумных отчётов** как ограничение и отложил оптимизацию.
Точный диапазон: **7.7-13.2%** в шести correlation configurations.

Полный repeat завершён: 15000/15000 reports, 95 минут, errors/missing 0;
по summary 5135000 parity checks, mismatches 0. Extras reports 3322 -> 630,
unrelated headlines 7315 -> 661, detection 3000/3000 -> 3000/3000.
Это раскрытые seeds1000..1999, 15 correlation configurations, B999,
blocks10/20, max per-block p-value затем Holm. Full independent recount
не выполнен. Старый v1 FAIL и критерии не изменены; это принятое ограничение,
а не независимая statistical acceptance.

Статистический процесс PID61008 завершён, ждать его или перезапускать не надо.
[Отчёт и решение](statistical-validation-correlation-full-v1.md);
artifacts `build/stats-validation/correlation-full-v1-*` сохранять.
Новый selector существует только в `tools/correlation_pilot.py` и wrapper
`tools/correlation_full.py`: production integration ещё нужна. Genuine partial
и two-run p50 не исправлялись; прежние T02/T03 limitations сохраняются отдельно.

Следующий активный фичевый трек: **advisory AI**, выбран пользователем.
Пользователь разрешил upstream Qwen Code **0.21.1** вместо GigaCode fork
с соответствующими naming conventions. Скачан отдельно в
`build/ai-runner/qwen-code-0.21.1`; глобальный Qwen0.21.5 не заменён.
[Draft AI design](superpowers/specs/2026-09-06-advisory-ai-design.md) и
[discovery](ai-runner-qwen-0.21.1-discovery.md) готовы. CLI capabilities
проверены статически; model request/runtime isolation ещё не подтверждены.
Больше не запрашивать GigaCode distribution как обязательную предпосылку:
замена уже разрешена. Следующий шаг: изолированный capability probe,
затем минимальная реализация AI по сохранённым evidence.

Параллельно AI допустимо готовить production integration принятого selector
без новой оптимизации, закрытие `BASELINE-CONDITIONS-01` и сценарии сквозной
приёмки. Дальнейшая очередь полного MVP актуализирована в
[плане](development-plan-v0.6.md). Ни завершение эксперимента, ни его принятие
не закрывают полный MVP или внешние CI/milestone gates.

## Первое действие следующего агента

Работать в `F:/Coding/LT-Verdict/.worktrees/local-baseline-comparison`,
ветка `feat/remaining-sources`, HEAD на момент передачи
`60ac85a87b2170d4a48b923ad69002124782616c`.
Основной checkout `F:/Coding/LT-Verdict` также dirty; не путать его с worktree.
Сначала проверить git status, прочитать этот файл и текущий раздел отчёта ниже.
Не начинать заново уже завершённую приёмку и не перезапускать MC автоматически.

Изменения sources/PostgreSQL/capacity/UI/статанализа накоплены в dirty worktree,
много файлов untracked. Они принадлежат пользователю. Ничего не сбрасывать,
не делать broad staging. В последней работе commit/push/merge/tag не выполнялись.
Все длительные приёмочные процессы завершены; ожидать старые session IDs не надо.
Имена прежних субагентов не являются надёжным способом продолжения после reset.

## Согласованная очередь и фактическая точка остановки

1. Источники метрик: реализация и локальные проверки выполнены в этом worktree;
   включены VictoriaMetrics/Prometheus, Grafana proxy, InfluxDB, PostgreSQL
   pre/post, pg_stat_statements/pg_profile, multi-source и offline replay.
   Это не автоматическое закрытие внешнего CI/milestone. Подробности в плане.
2. Capacity: реализован CLI/API/UI, явные ступени и консервативные bounds.
   Локальная проверка завершена. 48-case regression сохранил исходные outputs.
   Реальная CLI-синтетика: 192000 requests, bounds 296/344, requirement 300
   дал NO_VERDICT. Не переоткрывать этот блок без нового дефекта.
3. Статистическая приёмка: весь v1 выполнен, итог FAIL по USEFULNESS.
   Это завершённое отрицательное исследование, НЕ незавершённый расчёт.
4. ИИ: следующий исходный блок очереди, в последней работе не реализовывался.
   Только advisory по evidence; не менять deterministic verdict, не выдавать
   наблюдаемые ассоциации за подтверждённые причины.
5. Первая полная живая приёмка на синтетике: после готовности предыдущих блоков,
   включая настоящие источники, сохранение/reload, capacity, comparison и ИИ.

Последняя рекомендация root — сначала устранить шум главных находок и провести
новую независимую проверку. Конкретный метод/дизайн исправления пользователем
ещё НЕ выбран, реализация исправлений НЕ начата. Сначала обсудить bounded план
на основании уже полученных результатов, а не автоматически добавлять методы.

## Итог статистической приёмки

| Gate | Результат |
| --- | --- |
| CORRECTNESS | PASS: 109/109 MATCH, errors/mismatches 0, claims 0 |
| APPLICABILITY | PASS: 421 cases / 830 system runs, 421/421 MATCH, claims 0 |
| Core/persisted parity | PASS: все 84 заранее выбранных случая, до массового расчёта и в его итоговом score |
| USEFULNESS | FAIL: 28000/28000, errors/missing/unevaluable 0, claims 0; 20 configs PASS, 8 FAIL |

CORRECTNESS: batches 48+50+9+2; final-source regression 107+2. Вся согласованная
матрица §4 закрыта. Missing throughput нельзя подменять нулём запросов:
наблюдаемый RPS=0 отличается от null latency/error; это зафиксированная семантика.
APPLICABILITY: mechanistic event-driven service, 10 NT families, paired
interventions, stress/missing/clock/coarse-grid и temporal A/B/C; все trace hashes
и preflight/contracts проверены. Это не доказательство причинности или переноса
на произвольный боевой стенд.

Восемь FAIL configurations:

- N02 AR(.8): 16 pairs/lag0 — 27.8% noisy reports;
  1 pair/lag10 — 13.1%; 16 pairs/lag10 — 91.7%.
  Для сравнения: 1 pair/lag0 — 1.8%, PASS.
- P01/P02/P03: true effect detection 100%, signed lag error 0,
  но unrelated extras в 24.3%/86.9%/86.1% reports соответственно — FAIL.
- T02: 36.8%; T03: 5.4% unexpected reports. Во всех срабатываниях единственная
  candidate metric — response_time_p50_ms. Шумовой gate требует одновременно
  point estimate <=5% и Wilson95 upper <=7%; T03 не проходит первое условие.

E01–E03 проходят; P04/P05: detection 100%, IoU 1, extras 0 на заданных сильных
эпизодах. P06/P07: detection 99.6%/99.7%, extras 0. Полная таблица, интервалы,
lag/episode/delta diagnostics и ограничения находятся в отчёте.
Sol max независимо пересчитал все 28000 per-case measurements и проверил
2000 raw T02/T03 classifications: расхождений нет. Review PASS подтверждает
достоверность отрицательного отчёта, НЕ превращает product gate в PASS.

## Нельзя потерять при продолжении

- Пороги и expected не подгонялись по acceptance outcomes. Production formulas
  в последнем полном цикле не менялись: добавлялся test-only harness/docs.
- Debug seeds 0..99; v1 MC seeds 1000..1999; Applicability seeds 2000..2019.
  Все acceptance seeds v1 раскрыты. Повтор v1 — regression, не независимое
  подтверждение. Для настройки нужна честная development-фаза, затем новая
  версия методики и заранее выбранные новые seeds; старый FAIL сохраняется.
- `BASELINE-CONDITIONS-01` OPEN: UI/API и постоянное хранение подтверждения
  условий ручной пары отсутствуют. Согласован internal nullable Boolean
  контекст одного compareAnalyses-вызова; true/false/default и strict runner
  validation реализованы. Это позволяет честно проверять двухпрогонный расчёт
  без фиктивного третьего run, но не закрывает пользовательский workflow.
- Unknown clock НЕ требует автоматически DESCRIPTIVE: действующий contract
  разрешает observed lag/CANDIDATE с CLOCK_ALIGNMENT_UNKNOWN и без time-order
  или causal claims. Прежнее ошибочное reviewer-требование отозвано.
- HdrHistogram Java 2.2.2 percentile rank использует ceil/nextAfter,
  не старое правило floor(np+0.5). Проверено по JAR и tagged source.
- NT05 с 80 rps, 4 workers/quota25% имеет capacity100: queue0 допустима.
  Проверяется реальное увеличение CPU service duration, workload не подгонять.

## Документы и точки входа

- [Полный итог и команды](statistical-validation-results-v1.md): текущий статус
  в начале и финальные разделы важнее исторических NOT_RUN/INCOMPLETE ниже.
- [Методика v1](statistical-validation-methodology-v1.md).
- [Applicability rubric](statistical-validation-applicability-rubric-v1.md).
- [План проекта](development-plan-v0.6.md).
- [План приёмки](superpowers/plans/2026-09-06-statistical-validation.md):
  актуален заключительный Execution state; старые checklists исторические.
- [Capacity plan](superpowers/plans/2026-09-06-capacity.md).
- [Отложенные методы, включая Pearson/Kendall](statistical-method-roadmap.md).
- Локальный ledger: `.superpowers/sdd/2026-09-06-statistical-validation/progress.md`.
- Python: `tools/stats_validation.py`, `synthetic_service.py`,
  `applicability_inventory.py`, `applicability_contracts.py`,
  `applicability_validation.py`, `applicability_score.py`, `claims_audit.py`,
  `usefulness_inputs.py`, `usefulness_validation.py` и соответствующие tests.
- Kotlin test runners: `StatisticalValidationTest.kt`,
  `StatisticalValidationIntegrationTest.kt`, `UsefulnessValidationTest.kt`
  в `src/test/kotlin/io/ltverdict/core/`.

## Артефакты: не запускать clean

`build/stats-validation/v1-applicability` и `v1-usefulness` сохранены полностью.
Не запускать Gradle clean: он удалит первичные результаты/трассы.
Compact Applicability ZIP в `docs/statistical-validation/` содержит всё КРОМЕ
830 `.gz` traces (6.58 GB), которые остаются в исходном build-каталоге.
MC ZIP содержит все 56185 файлов. Оба ZIP проверены root через testzip и SHA256.

| Артефакт | SHA-256 |
| --- | --- |
| v1-applicability-evidence.zip, около 201 MB | `0f1fc726df1547572d3b78f968b6be447e76722359a9655efb0dd234bb4a16e9` |
| v1-usefulness-evidence.zip, около 2.03 GB | `0c2c8551af48eea55171acb8fba001a71a38239ec65d4000d3c6b0db64479975` |
| Applicability manifest | `289420995a4c3160ee5cfa24b36356903872d54176f0e19db1793d0568de6900` |
| Applicability score | `33b862dbfb1affdd62a10d05f497baef7dc6222bbd75c263905e4ec114d9bc51` |
| MC manifest | `4de8f99f692fde334716d15b5ad7354f3784dd6f868fd2f01aa6a67f74ba004d` |
| MC score | `690c229800ec3db8499dea752eafa979c4fdf148c4fcdb6ccea329fd44004cfd` |
| MC actual | `b72cb19302d0c834dca155db6e793046ba9b945db4a860f4c02488a82bacdd12` |

Архивы не staged: нельзя автоматически добавить 2 GB ZIP в обычный Git push.
Перед публикацией отдельно выбрать artifact storage. MC freeze связывает
upstream Applicability actual абсолютным путём исходного worktree; ограничение
переноса scorer на другую машину описано в отчёте. Manifest не переписывать молча.
Старые correctness/regression/debug архивы также сохранены; не удалять неудачные
debug-v1/v2 как «мусор» и не засчитывать debug как acceptance.

## Проверки и ограничения среды

Последние результаты: Python 86/86 PASS; общий JVM test/check — 324 tests,
0 failures/errors, 6 skips (opt-in/debug 3, PostgreSQL 1, Windows symlink 2).
Отдельно выполнены реальные APP/MC/parity runners. UI build/lint/contracts,
verify_slice0, Markdown 6 документов и local links 44/44 — PASS.
Внешний runtime/performance CI, полный gitleaks/history и end-to-end MVP gate
не закрыты. Ограниченный secret-pattern scan не заменяет gitleaks.

Среда: Windows/PowerShell UTF-8, Python 3.14.3, Java 21.0.9, Gradle 9.5.0,
Node 24.14.0. Один Gradle process за раз. Для corpus runner обязателен
`--rerun-tasks`: env vars не входят в Gradle task inputs, UP-TO-DATE не запуск.
Результаты создаются эксклюзивно, для replay задавать новый actual path.
Полный MC занял 1h15m5s. Пользователь прямо попросил НЕ поллить каждые несколько
минут: долгие расчёты оставлять в фоне, проверять примерно через 30 минут.

## Предпочтения пользователя

Довести проект до MVP без оверинжиниринга и бесконечных волн re-review.
Минимальные scoped изменения, без новых абстракций/методов «на всякий случай».
Параллельные независимые задачи допустимы; механика — дешёвым агентам согласно
AGENTS.md, root проверяет результат. Акцент на механизмах анализа ресурсов,
JMeter/Gatling ↔ VM и capacity; адаптацией acquisition к боевому стенду займутся
локальные модели. Не объявлять statistical association причиной деградации.
Методика обязана быть воспроизводимой, независимые ожидания — до outputs,
неудачные результаты и limitations — явно сохранены, никакой подгонки.

## Checkpoint 2026-09-06: three MVP tracks

Current implementation and verification status: [mvp-three-tracks-checkpoint-2026-09-06.md](mvp-three-tracks-checkpoint-2026-09-06.md).
64 focused JVM tests PASS; UI build/lint/contracts PASS. Baseline E2E: 3 PASS / 1 test-fixture error, user permission requested for the one-line fix.
Qwen 0.21.1 + deepseek/deepseek-v4-flash-0731: one isolated live probe PASS; production launcher and API/UI integration are NOT complete. No second live request. Jenkins remains out of scope.

Обновление checkpoint: разрешённая однострочная правка E2E применена;
повтор baseline E2E завершился exit 0, 4/4 PASS за 14.8 s.
Итог focused проверок: 64 JVM + 4 baseline E2E PASS; UI build/lint/contracts PASS.
Следующий шаг AI: production runtime/launcher, затем API/UI integration;
один live request уже выполнен, повторный запрос не разрешён.

## AI acceptance after methodology review, 2026-09-06

Методика уточнена по экспертному ревью; начата только подготовка приёмки.
Актуальный статус: [advisory-ai-acceptance-preparation-v1.md](advisory-ai-acceptance-preparation-v1.md).
30 cases / 60 attempts planned; live attempts 0. Два AI-assisted reviewers
совпали с ключом на 10/10 controls каждый; это не human review/final gate.
Runtime preflight BLOCKED после 3 инфраструктурных неудач, corpus exporter
написан, но не запускался и требует исправлений. Corpus/oracles не frozen.
Бюджет USD ещё не указан. Не запускать live или текущий corpus exporter до
закрытия перечисленных в отчёте предпосылок. Production/API/UI/Jenkins не менялись.

## Checkpoint 2026-09-06: бюджет и локальные блокеры

- Пользователь разрешил test-only исправления и установил общий бюджет приемки не более $2. Это разрешение не означает, что техническое ограничение расходов уже реализовано.
- Платные вызовы приемки: 0/60. Статус PREPARATION_INCOMPLETE; live release заблокирован. Production и Jenkins не менялись.
- В test-only экспортере исправлены discriminator/metric policy, optional diagnostics, D04 predicate (неизвестные часы ограничивают интерпретацию лага, но не обязательно выбор связи), сохранение CRLF при чтении JTL и уникальный staging-каталог каждой попытки. Исходные артефакты и hash validation сохранены.
- Последняя команда: `JAVA_TOOL_OPTIONS` с `-Dltverdict.aiAcceptanceExport=true`; `./gradlew.bat --offline --no-daemon test -x npmCi --no-parallel --tests io.ltverdict.core.AdvisoryAcceptanceCorpusTest`.
- Последний результат: compileTestKotlin прошел; 1 test failed, `DIAGNOSTIC_WINDOW_NOT_FOUND` в AnalysisService.kt:91 через AdvisoryAcceptanceCorpusTest.kt:234. Корпус не заморожен, успешная приемка не заявляется.
- Локальный fake preflight Qwen 0.21.1: valid и timeout прошли; malformed/retry имеют успешное ограничение до одного upstream request, но попытки repair/retry остаются policy failure. H05 показывает попытку `run_shell_command`; исполнение/блокировка остаются UNOBSERVABLE. Общие harness_preflight_pass и release_policy_pass равны false.
- По результатам локального runner исправления фактический max_tokens ограничивается relay с 32000 до 4096 с безопасной записью before/after. Это ограничение токенов не заменяет общий денежный лимит.
- Независимая подготовка S01/S02 дает p95=100 ms по 600 одинаковым успешным отсчетам и контраст порогов 120/80; evidence refs еще UNBOUND, oracle NOT_FINAL_NOT_FROZEN. Не считать эту заготовку готовым oracle всех 30 случаев.
- 10 reviewer controls совпали у двух AI-assisted reviewers; окончательное экспертное решение по calibration gate остается отдельным незакрытым пунктом.
- Следующие блокеры: корректные ссылки окон при проекции корпуса; независимые полные oracles и freeze; наблюдаемость containment H05 и retry policy; техническое обеспечение бюджета до платной серии.
- OpenRouter `provider.max_price` ограничивает тарифы prompt/completion/request, а не суммарные расходы серии: <https://openrouter.ai/docs/guides/routing/provider-selection#max-price> . Бюджетный relay пока только предложен, не реализован; общий ключ Qwen не изменялся.

## 2026-09-06: corpus prepared; live acceptance not started

- Corpus export completed: `DATA_PREPARED`, 30/30 cases. Approved N03 shortlist: 1016, 1019, 1030, 1034, 1036; first seed 1016 matched, remaining seeds were not attempted.
- Command: `JAVA_TOOL_OPTIONS += -Dltverdict.aiAcceptanceExport=true; .\gradlew.bat --offline --no-daemon test -x npmCi --no-parallel --tests io.ltverdict.core.AdvisoryAcceptanceCorpusTest`. Result: exit 0, BUILD SUCCESSFUL (38 s).
- Independent D05 calculation from archived raw input: rho 0.487974704709993, 300 paired cells; actual rho 0.48797470471. Selector is UNAVAILABLE / OBSERVATION_COUNT_UNSUPPORTED. Bounded check: `build/ai-acceptance/v1/oracle-preparation/d05-root-independent-check.json`. This is not an overall acceptance PASS.
- Canonical model inputs are now `build/ai-acceptance/v1/corpus/inputs/`; source inputs are `corpus/preparation/oracle-inputs/`. Do not rerun the exporter over the completed preparation manifest. Historical failed checkpoints remain preserved.
- Pending: independent required-fact bindings and oracle freeze; minimal host invocation and its local security review; explicit expert calibration decision. Two AI reviewers agreed on 10/10 controls, which does not replace expert approval.
- Paid acceptance requests: 0/60. Total budget remains USD 2. No extra paid smoke, retries, production changes, or Jenkins work.

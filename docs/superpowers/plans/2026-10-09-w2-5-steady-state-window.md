# W2.5 Окно устойчивого состояния (steady-state) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) или
> superpowers:executing-plans. Шаги отмечены чекбоксами. Этот документ это ТОЛЬКО план: кода, схем и ADR в PR с планом нет.

**Goal:** дать лёгкий вход со стадиями нагрузки для бизнес-политики без снимка ресурсов, чтобы стандартный вердикт
считался по окну устойчивого состояния, а разгон и остановка не попадали ни в p95, ни в throughput.

**Architecture:** человек объявляет стадии прогона файлом `load-stages.v1` (смещения от начала прогона). Стадии с ролью
`steady` превращаются в окна с `window_id` равным id стадии и идут в уже существующую оконную оценку policy
(`evaluateSharedWindowPolicy`) с пустой ресурсной стороной. Контракт оконной оценки не меняется; добавляются одно
свидетельство `stage_binding` и `window_metric_summary` по каждому окну. Хэш объявления входит в identity и в ключ
сопоставимости условно, только когда стадии заданы.

**Tech Stack:** Kotlin (core, cli, web), JSON Schema draft 2020-12, Vue/TypeScript (текст в карточке вердикта), JUnit 5.

**Spec:** решение владельца 2026-10-09 (пять пунктов, перенесены в «Решения владельца» ниже);
`docs/superpowers/plans/2026-10-08-review-work-plan.md` строка W2.5 и W3.7; [ADR 0029](../../adr/0029-incident-v0.md)
(W2.5 жёсткое предусловие W3.7); [ADR 0005](../../adr/0005-resource-window-sla.md), [ADR 0012](../../adr/0012-auto-window-recognized-period.md)
(границы не угадываются), [ADR 0014](../../adr/0014-resource-series-limits-autostep-arm-api.md) (условный ключ `resource_arm`).

## Global Constraints

- MINIMAL-CHANGE (AGENTS.md): наименьший корректный дифф, найденное вне запроса только в отчёт.
- Для прогонов без стадий не меняется ничего: ни вердикт, ни байты `analysis-result.json`, ни `identity.json`, ни
  `analysis_id`, ни ключ сопоставимости, ни содержимое `junit.xml`, `summary.txt`, `summary` JSON, HTML. Доказывается
  существующими золотыми тестами без правок (`fixtures/typed-boundary/golden`, `fixtures/slice1`) и новым тестом (см. «Критерии приёмки»).
- Автоопределения плато нет (новый статметод, источник недетерминизма, D1 заморожен). Границы стадий задаёт человек.
- Канонический JSON и вычисление хэшей identity не затрагиваются (`CanonicalJson.kt` не меняется).
- Новые поля только добавочные и необязательные; `schema_version` результата остаётся `analysis-result.v1`, верхнеуровневых
  полей результата не добавляется (белый список `AdvisoryAi.kt:568` не затрагивается).
- Зависимости production не добавляются.
- Реализация PR A-D идёт ПОСЛЕ слияния среза 2 W2.1 (типизация findings/evidence меняет продьюсеры evidence в `core/sources`).
- Русский язык прозы, английские идентификаторы сохраняются. Без CI: доказательство качества это локальный прогон (см. «Проверка»).

## Решения владельца (приняты как есть)

1. Opt-in, расширение существующего контракта. Когда передан снимок ресурсов с окнами, бизнес-политика уже сегодня
   считается только по окнам, по всему прогону политика не оценивается (`WindowPolicy.kt:19`, вызов
   `evaluatePolicy(null, ...)`; в окне `evaluatePolicy(policy, ..., windowId)`). W2.5 даёт тот же режим без снимка. Для
   прогонов без объявленных стадий ничего не меняется.
2. Привязка к identity условная: хэш объявления стадий входит в identity только при наличии, как `resource_snapshot_sha256`
   и `resource_arm`. Безусловное поле (как `incident_method` в ADR 0029) не нужно.
3. Автоопределения плато нет. Подсказка границ позже как зонд doctor/MCP (вне плана).
4. Прогон со стадиями и без них нельзя сравнивать как baseline: объявление стадий входит в ключ сопоставимости. Вердикт
   обязан сказать вслух «по окну steady, разгон исключён» в заголовке, отчёте (HTML), сводке/UI и в сообщении gate в `junit.xml`.
5. Правила policy уже умеют `window_ids`: привязка правил к окну steady без изменения схемы policy.

## Поправки владельца 2026-10-09 (старше текста ниже при расхождении)

Владелец подтвердил R1, R4, R7, R12 (ADR 0030 принят). Изменения к тексту плана:

1. **R1, конец `steady`.** Конец стадии `steady` за концом прогона обрезается до `runEnd`;
   у стадии в `stage_binding` стоит `clipped_to_run_end = true`. `STAGE_OUTSIDE_RUN` даёт
   только `steady`, начинающаяся за концом прогона (`from > runEnd`). Везде ниже, где написано
   «`steady` за концом прогона даёт `STAGE_OUTSIDE_RUN`», читать так. `excluded` не проверяются.
2. **Ловушка начала прогона.** `runStart` это минимальный `started_at` среди всех выборок, включая
   setUp-группу и health-check того же JTL: ранние посторонние выборки сдвигают все окна. Нужны
   пример ловушки в руководстве (PR D) и критерий AC13 (фикстура с ранней посторонней выборкой;
   фактические epoch-границы в `stage_binding` показывают сдвиг).
3. **R7.** PR C подписывает метрики «весь прогон, справочно» везде, где рядом есть `stage_binding`
   (сравнение с эталоном, динамика, сравнение транзакций): подпись, не арифметика и не контракт.
   W2.3 получает критерий готовности: при `stage_binding` дельты считаются по `window_metric_summary`.
4. **R12.** PR C показывает фразу и таблицу стадий для анализа, созданного через CLI или API.
5. **R4.** Принято. Расхождение `NO_POLICY` без стадий и `NO_VERDICT` со стадиями записано в бэклог как
   правка policy-гигиены вслед за W1.3 (трогать без стадий нельзя).

6. **Уточнение при реализации (PR A).** `STAGE_OUTSIDE_RUN` даёт `steady` с `from >= runEnd`, а не только `from > runEnd`: после обрезки получается окно нулевой длины, которое накопитель окон отвергает. Ошибки API лежат в `error.details[]`, не в `errors[]`.

## Блок для AGENTS.md

```text
REQUESTED: лёгкий вход со стадиями для бизнес-политики без resource snapshot; стандартный вердикт считается по окну steady;
  на профиле с разгоном p95 и throughput совпадают с ручным расчётом по плато (W2.5).
REQUIRED TO ACHIEVE IT:
  - ADR 0030 (публичный контракт: вход, identity, ключ сопоставимости) и контракт load-stages.v1 со схемой и примерами;
  - валидатор объявления и превращение стадий steady в окна (смещения от начала прогона);
  - вызов существующей оконной оценки без ресурсной стороны + свидетельства stage_binding и window_metric_summary;
  - условные поля identity (load_stages_sha256, load_stages_version), модуль/версия входа/лимиты, условное звено ключа сопоставимости;
  - вход: CLI --stages <file>, API multipart-часть stages; ошибки сочетаний (resources, capacity, онлайн source; офлайн
    --source-context без снимка разрешён); защита в analyzeWithSources до обращения к источнику;
  - видимость: фраза в заголовке (headline) карточки вердикта (UI) и в строке вердикта HTML, далее HTML-блок, summary.txt и
    summary JSON (windows[]), junit gate (сообщение или system-out), AsciiDoc/Confluence;
  - предупреждение WHOLE_RUN_METRICS_WITH_STAGES только в сравнении с эталоном (BaselineComparison.kt, ui types.ts, labels.ts);
  - документация и changelog-фрагменты на каждый PR.
NOT REQUIRED:
  - автоопределение плато, любой новый статистический метод;
  - загрузка стадий в форме UI (первый потребитель CLI/API/MCP; форма отдельным срезом);
  - изменение схемы policy, `capacity_step`, ресурсной оценки, формата saved-analytics, слотов baseline (ADR 0019);
  - пересчёт арифметики сравнения прогонов (дельты сравнения, динамика, сравнение транзакций и ранжирование кандидатов статистического
    эталона остаются по метрике «весь прогон»; предупреждение только в сравнении с эталоном, остальное обязательство W2.3/W2.4);
  - маршрут скачивания load-stages.json;
  - доля разгона/простоев как отдельная метрика заголовка и пометка throughput-правил (это W2.4; здесь только поля для неё);
  - инциденты (W3.7), типизация evidence (W2.1 срез 2), baseline в CLI и отчёте (W2.3), отчёт для людей (W2.6);
  - зонд подсказки границ (doctor/MCP, W3.1/W3.2).
EXPECTED FILES TO CHANGE: см. «Разбивка на PR». Новые: core/LoadStages.kt, docs/adr/0030-..., docs/contracts/stages/v1/*,
  fixtures/stages/*, тесты. Существующие правятся точечно (≈ 14 production-файлов, у каждого короткая вставка).
```

## Что есть сегодня (проверено по `origin/main` a6eb81a)

- Оконная бизнес-оценка включается только при `request.resources != null`
  (`AnalysisService.kt:303` `resolveResourceWindows`, `:412-440` ветка `evaluateSharedWindowPolicy`; при `resources == null` вызывается
  `evaluatePolicy(policy, ...)` по всему прогону, `:412-414`). Окна по транзакциям из policy накапливаются тоже только при снимке
  (`requestedTransactions`, `AnalysisService.kt:254-265`: `if (request.resources == null) emptySet()`).
- `evaluateSharedWindowPolicy` (`WindowPolicy.kt:11-67`) для каждого окна считает бизнес-вердикт, объединяет с ресурсным
  (`jointVerdict`), пишет `window_policy_summary` (id `window-policy-summary-<sha256(window_id)>`, поля `window_id`,
  `from_epoch_ms`, `to_epoch_ms`, `business_verdict`, `resource_verdict`, `verdict`, `sample_count`, `min_samples`), а для
  `window_ids`, которых нет среди окон, пишет `rule_window_check` с `RULE_WINDOW_NOT_FOUND` и `NO_VERDICT`.
- Окно считается по `WindowMetricsAccumulator.membership` (`Metrics.kt:147`): выборка относится к окну по моменту СТАРТА, интервал
  полуоткрытый `[from, to)`. Throughput окна это `sample_count * 1000 / (to - from)` (точная дробь), p95 это HDR
  `getValueAtPercentile(95.0)` при 3 значимых цифрах (значения до 2047 мс точные).
- Окна без снимка не существуют: `ResourceWindowV1` порождается `parseWindows` (`ResourceSnapshot.kt:372-412`, сетка, не более 64,
  без перекрытий) или неявным `run-intersection` (`:155-199`).
- Метрика «весь прогон» (`metric_summary` без `window_id`) всегда пишется базовой оценкой (`WindowPolicy.kt:19`). Читатели берут её через
  `singleOrNull { type == "metric_summary" && scope.kind == "overall" }` (`BaselineComparison.kt:652-656`, `RunComparison.kt:269-274`),
  поэтому второй `metric_summary` с `scope.kind = overall` (например оконный) сломал бы их: `singleOrNull` вернул бы `null`.
  Отсюда ruling R5 ниже.
- Ключ сопоставимости: `analysis_mode` результата + поля identity `source_type, engine, parsers, modules, input_versions, outputs,
  histogram, normalization, limits` + условный `resource_arm` (`BaselineComparison.kt:665-674`, `:1006-1007`;
  `RunComparison.kt:316-324`, `:426-427`). Кандидаты baseline (`BASELINE_MIXED_SEMANTICS`, `:149-152`) и динамика прогонов
  (`:37-41`) используют те же две функции. Слот baseline это пара (series, arm) (`RunBundleStore.kt:144-151`, `:1201`).
- Identity типизирована (W2.1 срез 1, #218): `AnalysisIdentityDocument` в `AnalysisDocuments.kt`; условные поля идут паттерном
  `pod_view_sha256`/`pod_view_version`, `trend_plan_sha256`/`trend_plan_version` (`AnalysisResult.kt:28-129`).
- Вход через CLI: `analyze` разбирает флаги в `CommandLine.kt:87-171`; через API: `receiveJob` (`LocalApi.kt:1910-2230`,
  части `policy`, `resource_snapshot`, `correlation_plan`, `capacity_plan`, ...), ошибки `Invalid*` дают 422 (`:209-235`).
  Коды `IllegalArgumentException` из сервиса отображаются в `AnalysisJobs.kt:209-260`.

## Rulings (спорное решено без вопросов владельцу)

**R1. Границы стадий это смещения в миллисекундах от начала прогона, а не epoch.** Начало прогона это минимальный `started_at`
среди всех выборок (то же `runStart`, что в `resource_binding`, `AnalysisService.kt:300-303`). Почему: человек знает «разгон 5
минут», а не epoch; тот же файл применим к любому прогону одного профиля, поэтому хэш объявления одинаков у сопоставимых прогонов
и ключ сопоставимости (решение 4) не ломается при каждом новом запуске. С epoch хэш был бы разным у каждого прогона и ни один
прогон не был бы сопоставим с другим. Привязка к `load_input_sha256` как у снимка не нужна: границы относительные. Цена ошибки:
объявление от другого профиля молча применится, если длина прогона подходит; смягчено полем `stage_binding.stages[].from_epoch_ms`
в отчёте, ошибкой `STAGE_OUTSIDE_RUN` для `steady` и ключом сопоставимости (другое объявление не сравнивается). Если владелец захочет epoch,
замена локальна (валидатор и `resolveStageWindows`), identity и ключ не меняются.

**R2. Окнами становятся только стадии с ролью `steady`; роль `excluded` окном не является.** Почему: правила без `window_ids`
применяются ко ВСЕМ окнам (`Policy.kt:122`); если бы разгон был окном, правило «p95 ≤ 300» без привязки проверялось бы и по
разгону и валило вердикт. Роль `excluded` даёт человеку имя («разгон») и позволяет показать «исключено N мин». Привязка правила к
`excluded` стадии даёт существующий `RULE_WINDOW_NOT_FOUND` (fail-closed), не тихий пропуск. Цена ошибки: пользователь ждёт
оценки по разгону; нужен отдельный режим, не этот.

**R3. Стадии и снимок ресурсов (а также capacity_step, online source) взаимоисключающи: приоритета нет, есть ошибка.** Почему:
оба определяют окна; «какое окно решает» неоднозначно, а молчаливый приоритет нарушил бы «вердикт говорит вслух». Пользователю
со снимком нужны окна снимка (`windows[]` уже есть), потери нет. Цена ошибки: ошибка там, где можно было договориться; безопасная
сторона отказа. Порядок проверок и коды: см. «Взаимодействие с другими режимами».

**R4. Оценка переиспользует `evaluateSharedWindowPolicy` с пустой ресурсной стороной, новой оценки нет.** Ресурсная сторона:
`ResourceEvaluation(windowVerdicts = {окно: NO_POLICY}, coverageReasons = [], findings = [], evidence = [])`. `jointVerdict(бизнес,
NO_POLICY)` даёт бизнес-вердикт (PASS, FAIL, NO_VERDICT, NO_POLICY сохраняются). Почему: нулевой новый алгоритм оценки, поведение
в окне побайтово то же, что с снимком (пустые окна, малые выборки, `RULE_WINDOW_NOT_FOUND`). Исключение: платформенные правила
(`platform_rules`) без снимка. Сегодня без снимка и без стадий они дают причину `RESOURCE_SNAPSHOT_REQUIRED` (`Policy.kt:113-120`:
SLA-правило вместе с применимыми бизнес-правилами делает `NO_VERDICT`; политика только из платформенных правил возвращает
`NO_POLICY` с причиной покрытия, `Policy.kt:122`), а в окне `evaluatePolicy` эту ветку пропускает (условие `windowId == null`), так что
в режиме стадий платформенные правила были бы тихо потеряны. Намеренное решение режима стадий (строже прежнего, не копия): при
любом `platform_rules` с `effect = SLA` ресурсный вердикт каждого окна `NO_VERDICT` и причина покрытия `RESOURCE_SNAPSHOT_REQUIRED`
(политика, которая объявляет SLA ресурса и не может его проверить, не проходит и не молчит); при правилах без SLA причина
информационная, вердикт не меняется. Поведение без стадий не трогается. Цена ошибки: потерянное SLA-правило дало бы PASS вместо
NO_VERDICT; для этого три красных теста (SLA вместе с бизнес-правилами, только платформенная политика, правило без SLA).

**R5. Показатели окна публикуются типом `window_metric_summary`, не `metric_summary`.** Тип уже читают сравнение окон
(`BaselineComparison.kt:518`, условия baseline ADR 0028) и оба отчёта (`HtmlReport.kt:48`). Он уже содержит `sample_count`,
`error_count`, `error_rate_ratio`, `throughput_rps`, `latency_ms.{p50,p95,p99,max}`, `from_epoch_ms`, `to_epoch_ms`
(`DiagnosticAnalysis.kt:765-800`; в режиме стадий `resource_bindings` пустой массив). Функция `windowMetricSummary` приватная:
делается `internal` и вызывается из режима стадий (минимальная правка, дубля нет). Коллизии id нет: диагностика требует снимок, а
со стадиями снимка быть не может (R3). Цена ошибки: потребитель, ждущий steady-метрики в `metric_summary`, их не найдёт; все
читатели нового кода идут по `window_id`.

**R6. Ключ сопоставимости получает условное звено `load_stages_sha256`, слоты baseline не меняются.** К тому, что режим стадий
уже отличается через `modules`, `input_versions`, `limits` (как режим снимка), добавляется условное звено по образцу `resource_arm`:
`values += identity["load_stages_sha256"] ?: JsonNull`, в обеих функциях ключа. Отсутствие у обоих равно равенству; отсутствие
у одного или разные хэши означают несовместимость. Почему: окно 5-25 мин и 5-30 мин дают разные p95 и throughput, это разные
метрики. Слот baseline (series, arm) НЕ расширяется: ADR 0019 это публичный контракт хранилища, а несовместимость уже
показывается как `compatible = false`. Цена ошибки: сохранённый как baseline прогон без стадий при переходе на стадии станет
несовместимым, baseline нужно пересохранить (одна операция); обратный выбор (расширить слот) меняет схему хранилища.

**R7. Метрики «весь прогон» остаются в результате и в сравнении; сравнение с эталоном дополняется предупреждением.** `metric_summary` без
окна пишется как раньше (R5 объясняет, почему трогать читателей нельзя). Но дельты сравнения двух прогонов со стадиями считаются
по метрике «весь прогон», что противоречит вердикту «по steady». Минимум: предупреждение `WHOLE_RUN_METRICS_WITH_STAGES` ТОЛЬКО в
существующем массиве `warnings` ответа сравнения с эталоном (`BaselineComparison.kt`), если у любой стороны есть `stage_binding`:
расширение закрытого объединения `BaselineComparisonWarning` (`ui/src/types.ts:667-673`) и подписей `BASELINE_LABELS.warnings`
(`ui/src/shell/labels.ts:50-58`). Ответы динамики прогонов (`RunComparison.buildRunDynamics`) и сравнения транзакций
(`compareTransactions`) поля `warnings` не имеют; в них предупреждение НЕ добавляется (новый контракт ответа не создаётся) и это
передаётся W2.3/W2.4 как обязательство: читать steady-метрики через `window_metric_summary` при `stage_binding` (запись в их планах).
Сравнение steady-окон остаётся режимом `window_comparison` (ADR 0028), он уже работает через `window_metric_summary`. Арифметика
сравнения не меняется. Цена ошибки: динамика и сравнение транзакций двух прогонов с одним объявлением показывают метрику «весь
прогон» без предупреждения; карточка вердикта и отчёт каждого анализа говорят о границах вслух.

**R8. Результат: только новые свидетельства, верхнеуровневых полей нет.** Новое: одно `stage_binding` и по одному
`window_metric_summary` на окно. Почему: добавление поля результата потребовало бы белого списка `AdvisoryAi.kt:568` и typed-модели;
свидетельства это существующий открытый список (`evidence` остаётся `List<JsonObject>` до среза 2). Цена ошибки: типизация среза 2
должна учесть новый вариант (см. «Пересечения»).

**R9. В каталоге анализа сохраняется `load-stages.json` (канонические байты), `run.json` не меняется.** Почему: воспроизводимость
(«инцидент воспроизводим из bundle», ADR 0029) требует, чтобы объявление лежало рядом с identity, как `policy.json` и
`resource-snapshot.json`. `run.json` (`run.v1`) не меняется: хэш уже в identity. Файл доступен в локальном bundle
(`RunBundleStore` перечисляет файлы каталога, `RunBundleStore.kt:1450-1466`); HTTP-маршрута скачивания нет и он не добавляется
(скачивание идёт по явной карте имён, `LocalApi.kt:770-845`, NOT REQUIRED). Цена ошибки: агент по API не получит файл объявления,
но получит `stage_binding` в результате (границы и роли там есть целиком); тест хранилища в PR A проверяет, что новый файл не ломает
чтение каталога.

**R10. Фраза о границах генерируется при отрисовке из полей `stage_binding`, в результате не хранится.** Русский текст не
попадает в канонический результат, поэтому формулировку можно править без смены `analysis_id` и без пересчёта.
Единая константа: `Вердикт посчитан по окну steady, разгон исключён` (с подстановкой id окна и длительностей, см. «Видимость»).

**R11. Пустое или частично непригодное: `INVALID` прогон не получает `stage_binding`.** Окна считаются после разбора выборок
(`:299-303`), а у непригодного прогона нет начала. Вердикт `NO_VERDICT` ничего не утверждает про steady; identity и
`load-stages.json` пишутся (путь `invalidOutcome`), чтобы ссылка identity не висела.

**R12. Загрузка файла стадий в форме UI не входит.** Первые потребители это CLI, API и MCP (W3.2). UI показывает фразу и
таблицу окон, форма отдельным срезом после решения по MCP. Цена ошибки: пользователь UI не может задать стадии сам.

## Форма входа

### CLI

```text
ltv analyze <input> [--policy <policy.json>|-] [--stages <load-stages.json>] [--out-dir <dir>] ...
```

- Флаг `--stages <file>` один раз; повтор даёт `usage` (код 64), как у остальных флагов (`CommandLine.kt` шаблон
  `if (stagesPath != null || index + 1 >= args.size) usage()`).
- Файл читается через `requireRegularFile(path, EXIT_INVALID_INPUT, "INVALID_STAGES")`; ошибки валидации печатаются строками
  `<code> <json-pointer>: <message>` и дают код выхода 4 (как `readResources`, `CommandLine.kt:735-753`).
- Сочетания (проверяются до чтения входа; коды печатаются в stderr, выход 4): см. таблицу ниже.
- Код выхода анализа не меняется: `PASS`/`NO_POLICY` 0, `FAIL` 2, `NO_VERDICT` 3, `INVALID` 4. Вердикт окна дает те же коды.
- Текст `usageText()` получает `[--stages <load-stages.json>]` в строке `analyze`. Это ОДИН публичный формат вывода, который
  меняется (только `--help`/usage; фиксируется ADR 0030).

### API

- Multipart-часть задания анализа `stages` (файл), одна на задание. Ошибки формата: `422 INVALID_STAGES` с массивом
  `error.details[{code, json_pointer, message}]` (по образцу `INVALID_RESOURCES`); превышение лимита размера: `413 RESOURCE_LIMIT_EXCEEDED`.
- Сочетания: `422 INVALID_STAGES` с одним элементом `error.details[]` кода из таблицы сочетаний.
- Выход за границы прогона обнаруживается при выполнении задания: задание в состоянии `FAILED` с `diagnostic.code = STAGE_OUTSIDE_RUN`
  (добавляется ветка в `AnalysisJobs.kt:209`, рядом с `RESOURCE_WINDOW_OUTSIDE_RUN`).
- Маршрут скачивания `load-stages.json` не добавляется (R9); объявление восстанавливается из `stage_binding` результата.

### Схема `load-stages.v1`

Файл `docs/contracts/stages/v1/load-stages.schema.json` (JSON Schema draft 2020-12), примеры в `examples/valid` и
`examples/invalid` (как `docs/contracts/resources/v1/examples`).

| Поле | Тип | Правило |
| --- | --- | --- |
| `schema_version` | string | ровно `load-stages.v1` |
| `stages` | array | 1..16 элементов, отсортированы по `from_offset_ms` после разбора, не перекрываются (касание допустимо), зазоры допустимы |
| `stages[].id` | string | непустая, ≤ 128 байт UTF-8, без управляющих символов (правило `validateResourceText`), уникальна |
| `stages[].role` | string | `steady` или `excluded`; других значений нет (в частности нет `auto`) |
| `stages[].from_offset_ms` | integer | ≥ 0, ≤ 604800000 (7 суток), целое без дробной части |
| `stages[].to_offset_ms` | integer | > `from_offset_ms`, ≤ 604800000 |

Лишние поля отвергаются (`UNKNOWN_FIELD`). Не менее одной стадии `steady`. Файл ≤ 65536 байт, глубина JSON ≤ 8 (тот же
`StrictJsonScanner`, что у policy и снимка: дубли ключей, NaN, большие числа запрещены).

Лимиты (идут в `limits` identity только при стадиях): `stages_plan_bytes_max=65536`, `stages_json_depth_max=8`,
`stages_max=16`, `stages_offset_ms_max=604800000`.

Допустимые примеры (`examples/valid`):

```json
{
  "schema_version": "load-stages.v1",
  "stages": [
    { "id": "ramp-up", "role": "excluded", "from_offset_ms": 0, "to_offset_ms": 40000 },
    { "id": "steady", "role": "steady", "from_offset_ms": 40000, "to_offset_ms": 100000 },
    { "id": "ramp-down", "role": "excluded", "from_offset_ms": 100000, "to_offset_ms": 120000 }
  ]
}
```

- `ramp-steady-down.json`: пример выше. Это ровно то объявление, которое используют AC1-AC3 и AC6 для фикстуры `ramp-steady-rampdown.jtl`
  (стадия `ramp-down` кончается на 120 с, прогон на 119,8 с: допустимо, `excluded` не проверяется на конец прогона).
- `steady-only.json`: единственная стадия `steady` без исключённых (всё до неё и после неё исключено неявно).
- `two-steady.json`: две стадии `steady` (`steady-a`, `steady-b`) с исключённой `spike` между ними; вердикт это объединение окон.

Недопустимые примеры (`examples/invalid`, ожидаемый первый код):

| Файл | Дефект | Код |
| --- | --- | --- |
| `no-steady.json` | все стадии `excluded` | `STAGES_NO_STEADY` |
| `overlap.json` | вторая стадия начинается до конца первой | `OVERLAPPING_STAGES` |
| `duplicate-id.json` | два `steady` | `DUPLICATE_STAGE_ID` |
| `empty-window.json` | `from_offset_ms` = `to_offset_ms` | `INVALID_STAGE` |
| `negative-offset.json` | `from_offset_ms: -1` | `INVALID_STAGE_OFFSET` |
| `fractional-offset.json` | `to_offset_ms: 1500.5` | `INVALID_TYPE` |
| `role-auto.json` | `role: "auto"` (автоопределения нет) | `UNKNOWN_ROLE` |
| `unknown-field.json` | поле `detect: true` | `UNKNOWN_FIELD` |
| `too-many-stages.json` | 17 стадий | `RESOURCE_LIMIT_EXCEEDED` |
| `wrong-version.json` | `schema_version: "load-stages.v2"` | `INVALID_SCHEMA_VERSION` |

### Взаимодействие с другими режимами

Онлайн-запрос источника (`sourceRequest`) разрешается и снимок строится ДО `AnalysisService`
(`SourceAnalysis.kt:120-130`, `analyzeWithSources`: `acquisition.snapshot` подставляется как `resources`), поэтому условие 4 проверяется
там, раньше разрешения окна и запроса, и по нему не выполняется ни одного обращения к источнику. Остальные условия проверяются в
`AnalysisService.analyze` (до чтения входа, перед `request.resources?.let`), коды `IllegalArgumentException`; CLI и API проверяют
те же условия раньше и печатают тот же код:

| № | Условие | Код | Почему |
| --- | --- | --- | --- |
| 1 | существующие `CAPACITY_PLAN_REQUIRED`, `CAPACITY_MODE_CONFLICT` | без изменений | порядок прежний |
| 2 | стадии и (`capacity != null` или `mode == CAPACITY_STEP`) | `STAGES_CAPACITY_CONFLICT` | ёмкость определяет окна ступеней и требует снимка |
| 3 | стадии и `resources != null` | `STAGES_RESOURCES_CONFLICT` | два источника окон (R3) |
| 4 | стадии и `sourceRequest != null` (онлайн-запрос; проверка в `analyzeWithSources`, в CLI `--source`, в API часть `source_request`) | `STAGES_SOURCE_CONFLICT` | онлайн-запрос строит снимок сам; проверка раньше `SOURCE_INPUT_CONFLICT` |
| 5 | существующие `DIAGNOSTIC_RESOURCE_REQUIRED`, `TREND_RESOURCE_REQUIRED`, `POD_VIEW_RESOURCE_REQUIRED` | без изменений | эти входы требуют снимок; со стадиями он невозможен, ошибка остаётся |

С `postgres` (пред/пост снимки) поведение прежнее: он независим и разрешён. Офлайн-контекст OpenSearch (`--source-context`,
часть `source_context`) без снимка тоже разрешён: он добавляет только evidence и не задаёт окон (`readOpenSearchContexts(..., resources = null)`
даёт `SourceAcquisition` без снимка); если контекст несёт снимок, он совпадает с `resources`, и сочетание отвергается условием 3.
Тест: стадии вместе с `--source-context` проходят, `evidence` содержит `source_summary`.
Приоритета «кто побеждает» нет: все сочетания, дающие два источника окон, отвергаются.

## Как стадии превращаются в окна

`resolveStageWindows(stages, runStartEpochMillis, runEndEpochMillis): List<ResourceWindowV1>` (в `core/LoadStages.kt`):

1. Для каждой стадии (любой роли) `from = runStart + from_offset_ms`, `to = runStart + to_offset_ms` (`Math.addExact`).
   Если у стадии `steady` `to > runEnd` (конец прогона это максимум `started_at + elapsed`), `IllegalArgumentException("STAGE_OUTSIDE_RUN")`.
   Стадии `excluded` проверке не подлежат: это подписи, реальные прогоны заканчиваются на сотни мс раньше или позже объявленного, и один
   файл на профиль не должен падать на части прогонов (иначе R1 теряет смысл). Начало `steady` до `runStart` невозможно (смещение ≥ 0).
2. Окнами становятся стадии с ролью `steady`, `ResourceWindowV1(id = stage.id, from, to)`, в порядке `from`.
3. Накопитель `WindowMetricsAccumulator` получает эти окна (полуоткрытые `[from, to)`, по моменту старта выборки);
   удерживаемые транзакции (`retainedTransactions`) собираются при `resources != null || stages != null`
   (`AnalysisService.kt:254`). Правки в накопителе нет.
4. `window_id` это `stage.id`. Правило policy с `window_ids: ["steady"]` применяется к этому окну; правило без `window_ids` ко
   всем окнам `steady`; правило с `window_ids`, которых нет среди окон `steady` (в том числе id стадии `excluded`), дает
   `rule_window_check` `RULE_WINDOW_NOT_FOUND` и `NO_VERDICT` (существующее поведение).
5. Пустое окно (нет выборок) при применимом правиле даёт `BUSINESS_OBSERVATIONS_NOT_FOUND` и `NO_VERDICT` (существующее).

## Что меняется в результате и что остаётся прежним

Для прогона со стадиями (порядок `evidence` фиксируется золотым тестом):

1. Сначала то, что `evaluateSharedWindowPolicy` ставит впереди: метрики «весь прогон» (`metric_summary` без `window_id`) и `diagnostic`
   (`base.evidence`, `WindowPolicy.kt:21`).
2. Затем `stage_binding`: он передаётся как `evidence` пустой ресурсной стороны и стоит ровно там, где в режиме снимка стоит
   `resource_binding` (ресурсная часть, `AnalysisService.kt:426`, после `base.evidence`, до проверок окон). Порядок режима снимка
   не меняется.
3. Затем по окну `policy_check` со `window_id`, `window_policy_summary` (`resource_verdict = NO_POLICY` или `NO_VERDICT` по R4;
   поля те же, что сегодня), `rule_window_check` при несуществующих окнах.
4. В конце по окну `window_metric_summary` (R5), в порядке окон (добавляется после оценки, до вызова `analysisResult`).

Поля `stage_binding` (snake_case, как `resource_binding`):

| Поле | Значение |
| --- | --- |
| `id` / `type` | `stage-binding` / `stage_binding` |
| `mode` | `declared_stages` |
| `declaration_sha256` | SHA-256 канонических байт объявления (то же значение, что `load_stages_sha256` identity) |
| `run_from_epoch_ms`, `run_to_epoch_ms` | начало и конец прогона |
| `evaluated_window_ids` | id окон `steady` по порядку |
| `evaluated_millis` | сумма длин окон `steady` |
| `excluded_millis` | (`run_to - run_from`) минус `evaluated_millis` (включает разгон, остановку и зазоры) |
| `stages[]` | `{id, role, from_offset_ms, to_offset_ms, from_epoch_ms, to_epoch_ms}` в порядке объявления после сортировки |
| `verdict_scope` | `STEADY_WINDOW` |

Не появляется: `resource_binding`, `resource_summary`, `resource_policy_check`, `resource_threshold_violation`. Именно так
ADR 0029 получает «`window_id` и `window_policy_summary` без `resource_binding`»: инцидент берёт интервал TRANSACTION из границ
`window_policy_summary` по типу evidence, а не по наличию снимка (`incident_method` и W3.7 дальше не меняются).

Остаётся прежним (проверяется тестами): вердикт и evidence прогонов без стадий; `analysis_mode` `standard`; схема `analysis-result.v1`;
формат `policy_check` и `window_policy_summary`; коды выхода; `analysis_coverage`.

## Влияние на identity, `analysis_id` и ключ сопоставимости

Только для прогонов со стадиями (`stages != null`):

- Новые необязательные поля `AnalysisIdentityDocument`: `load_stages_sha256` (SHA-256 канонических байт объявления),
  `load_stages_version` = `load-stages.v1`. Паттерн `pod_view_*`, `trend_plan_*`; без стадий поля отсутствуют, байты
  identity прежние (`explicitNulls = false` в `ANALYSIS_DOCUMENT_JSON`; подтверждается тестом).
- `modules` += `stage-window-evaluation` v1 и `window-policy-evaluation` v1; `input_versions.stages` = `load-stages.v1`
  (новое необязательное поле `InputVersionsDocument`); `limits` += четыре лимита выше. `modules`, `input_versions` и `limits`
  входят в ключ сопоставимости, поэтому режим стадий отличается от режима без стадий уже без правки ключа.
- Ключ сопоставимости: условное звено `identity["load_stages_sha256"] ?: JsonNull` в `semanticKey` (`BaselineComparison.kt`)
  и `comparisonSemanticKey` (`RunComparison.kt`) (R6).
- `analysis_id` меняется только у анализов со стадиями (SHA-256 identity). Повторная отправка тех же файлов без стадий
  возвращает старый сохранённый анализ (проверяется `store.readAnalysis` до вычислений, как сегодня).
- Генерируемые типы UI: добавление полей в `AnalysisIdentityDocument` меняет `ui/src/types.generated.ts`; регенерация входит в PR A
  (`TypeScriptGeneratorTest` ловит расхождение).

## Видимость: фиксированная фраза

Константа ядра отображения (русский текст, R10): `Вердикт посчитан по окну steady, разгон исключён`. Фраза выводится только когда вердикт
вынесен (`run_validity = VALID` и `policy_verdict` равен `PASS` или `FAIL`). При `NO_VERDICT` (в том числе `DEGRADED`, где политика
не оценивается, `Policy.kt:106-107`, но окна и `window_metric_summary` описательно остаются) и `NO_POLICY` текст другой и не
утверждает вердикт: `Окно steady задано (<ids>), разгон исключён из метрик окна; вердикт: <NO_VERDICT|NO_POLICY>`. Подстановки из `stage_binding`:
id окон, длительность `evaluated_millis` и `excluded_millis` в минутах/секундах (формат `formatDuration` UI, один знак), граница UTC.

| Место | Что показывается | Файл |
| --- | --- | --- |
| Карточка вердикта (UI): заголовок и подзаголовок | в `headline` (и для PASS, и для FAIL) добавляется короткий маркер: `Прогон проходит — нарушений нет, проверок: N · по окну steady, разгон исключён`, `Прогон не проходит — нарушено проверок: K из N · по окну steady, разгон исключён`; к `lead` добавляется предложение с полной фразой; в `facts` строки «Окно вердикта: steady, 20 мин, 12:05:00-12:25:00 UTC» и «Исключено: 6 мин». При NO_VERDICT, DEGRADED, NO_POLICY заголовок остаётся прежним, маркер только в `lead` и в нейтральной формулировке | `ui/src/verdictSummary.ts`, `ui/src/types.ts` (тип `stage_binding`), `ui/src/shell/labels.advice.ts` (подписи типов) |
| HTML-отчёт | строка `dt/dd` «Область вердикта» в самом `dl` с вердиктом сразу после «Policy verdict» (маркер виден без чтения блока ниже); блок после `dl`: фраза, таблица стадий, подпись «метрики по всему прогону справочные»; разделы Window policy outcomes без «Resource binding» | `report/HtmlReport.kt` (`verdictBlock`, список `dl`) |
| AsciiDoc и Confluence | одна строка-примечание с той же фразой | `report/AsciiDocReport.kt` |
| `summary.txt` | строка `scope: steady window (<ids>), excluded <N> ms` и по строке на каждое окно `window[<id>]: samples .. errors .. p95_ms .. p99_ms .. rps ..` (порядок окон результата); прежняя строка `samples/p95/p99` помечается `whole_run (reference only)` | `cli/CliArtifacts.kt` |
| `ltv summary` JSON (`cli-summary.v1`) | необязательный массив `windows[]` (по окну steady в порядке результата) {id, from_epoch_ms, to_epoch_ms, samples, errors, error_rate, p50, p95, p99, max, rps} и число `excluded_ms`; `overall` остаётся «весь прогон»; без стадий полей нет | `cli/CliArtifacts.kt` |
| `junit.xml` gate | сообщение gate (FAIL, NO_VERDICT, INVALID): прежняя строка `policy_verdict=.. run_validity=.. reasons=..` плюс суффикс через пробел `scope=steady_window window_ids=<ids> excluded_ms=<N>`; у ПРОХОДЯЩЕГО gate (PASS, NO_POLICY) сообщения нет (самозакрытый `testcase`), поэтому в стадийном прогоне он получает дочерний `<system-out>` с той же строкой `scope=...`; без стадий байты прежние | `cli/CliArtifacts.kt` (`junitXml`) |
| Сравнение с эталоном (API, R7) | предупреждение `WHOLE_RUN_METRICS_WITH_STAGES` | `core/BaselineComparison.kt`, `ui/src/types.ts`, `ui/src/shell/labels.ts` |

Публичные форматы: изменения `summary.txt`, `cli-summary.v1` и `junit.xml` только для прогонов со стадиями и только добавочные;
фиксируются ADR 0030 и тестами «без стадий побайтово прежнее».

## Что фиксирует ADR 0030

Заголовок: **ADR 0030: Стадии нагрузки и вердикт по окну устойчивого состояния (`load-stages.v1`)**. Статус при создании
Proposed; принимает владелец (как ADR 0029). Номер 0030 перепроверить по `origin/main` перед слиянием. Разделы:

1. **Контекст.** Оконная оценка бизнес-политики существует только со снимком ресурсов; самый частый вход это журнал без снимка;
   вердикт по всему прогону включает разгон; W3.7 требует окна. Ссылки на ADR 0005, 0012, 0014, 0029.
2. **Решение (Ruling'и).** R1 (смещения, а не epoch), R2 (роль `steady`/`excluded`), R3 (взаимоисключение), R4 (переиспользование
   оконной оценки, платформенные правила), R5 (`window_metric_summary`), R6 (ключ и слоты), R7 (сравнение и предупреждение),
   R8 (только evidence), R9 (`load-stages.json` в каталоге), R10 (фраза при отрисовке), R12 (без формы UI), каждое в формате
   «Ruling: что, почему, цена ошибки».
3. **Контракт `load-stages.v1`.** Схема и таблица полей, лимиты, коды ошибок валидации, примеры valid/invalid.
4. **Публичные контракты, которые меняются** (перечень): флаг `ltv analyze --stages`; multipart-часть `stages`; коды
   `STAGES_*`, `STAGE_OUTSIDE_RUN`, `INVALID_STAGES`; evidence `stage_binding` и использование `window_metric_summary`;
   необязательные поля identity `load_stages_sha256`, `load_stages_version`, поле `input_versions.stages`; новые ключи `limits`;
   условное звено ключа сопоставимости; суффикс сообщения gate `junit.xml` (FAIL, NO_VERDICT, INVALID) и дочерний `<system-out>`
   у проходящего gate; необязательный массив `windows[]` и число `excluded_ms` в `cli-summary.v1`; маркер в `headline` карточки
   вердикта и `dt/dd` «Область вердикта» в HTML; предупреждение `WHOLE_RUN_METRICS_WITH_STAGES` только в ответе сравнения с эталоном
   (с типом и подписью UI); защита `STAGES_SOURCE_CONFLICT` в `analyzeWithSources`; файл `load-stages.json` в каталоге анализа
   (маршрута скачивания нет).
5. **Identity и ключ сопоставимости** (условная привязка, почему не безусловная как `incident_method`, `analysis_id` меняется только у
   анализов со стадиями; слот baseline не расширяется).
6. **Следствия:** совместимость (прогоны без стадий неизменны, золотые файлы не перегенерируются), взаимодействие с W2.1/W2.3/W2.4/W2.6/W3.7,
   границы (нет автоопределения, нет формы UI).
7. **Отклонённые альтернативы:** автоопределение плато (D1, недетерминизм); новый контракт вместо расширения; epoch вместо
   смещений (R1); безусловное поле identity; роль окна для разгона (R2); приоритет «стадии побеждают снимок» (R3);
   `metric_summary` по окну (R5); расширение слота baseline (R6).
8. **Тест-критерии:** ссылки на «Критерии приёмки» этого плана.

## Критерии приёмки

Фикстура `fixtures/stages/ramp-steady-rampdown.jtl` (JMeter CSV, 720 выборок, `t0 = 1767225600000`, формат как `fixtures/slice0/jmeter.jtl`).
Одна метка `GET /items`, все `success=true`:

| Стадия | Смещения | Выборки | Латентность |
| --- | --- | --- | --- |
| разгон | 0-40 с | 80, `t0 + 500*i`, i=0..79 | `800 + (i % 40)` мс |
| steady | 40-100 с | 600, `t0 + 40000 + 100*k`, k=0..599 | `100 + (k % 100)` мс |
| остановка | 100-120 с | 40, `t0 + 100000 + 500*i`, i=0..39 | 300 мс |

Конец прогона `t0 + 119800` (последний старт плюс 300 мс), начало `t0`. Ручной расчёт по плато (независимая арифметика,
записывается в тест литералами, не вычисляется кодом ядра): 600 выборок в `[t0+40000, t0+100000)`; throughput = 600 выборок / 60 с =
10 rps (точная дробь `600000/60000`); значение по рангу `int(0.95*600 + 0.5) = 570`: каждое из значений 100..199 встречается 6
раз, 570-я выборка это `100 + (570-1)/6 = 194` мс; p50 = 149, p99 = 198, max = 199 мс. Весь прогон для контраста: p95 = 821 мс,
throughput = `720000/119800` ≈ 6,01 rps.

- [ ] **AC1. Совпадение с плато.** `window_metric_summary` окна `steady`: `sample_count = 600`, `latency_ms.p95 = 194`,
  `p99 = 198`, `throughput_rps = {numerator: 600000, denominator: 60000}`; `policy_check` правил `response_time_p95_ms lte 250` и
  `throughput_rps gte 9` в окне имеет `PASS`, `observed` 194 и `{numerator: 600000, denominator: 60000}` (точная дробь, `Policy.kt:287`,
  `:296-299`); тот же прогон без стадий: `FAIL` (p95 821, throughput `720000/119800`).
- [ ] **AC2. FAIL по плато.** Правило `response_time_p95_ms lte 190` с `window_ids: ["steady"]` даёт `FAIL`, `observed = 194`, находка
  `policy_failure` с `window_id = steady`, код выхода 2.
- [ ] **AC3. Граница полуоткрытая.** Выборка со стартом ровно `t0 + 40000` относится к `steady`, со стартом `t0 + 100000` нет
  (тест на сдвинутой на одну выборку фикстуре, 601 и 599 выборок).
- [ ] **AC4. Прогон без стадий побайтово прежний.** Существующие `TypedBoundaryGoldenBytesTest`, `AnalysisResultGoldenTest`,
  `ResourceArmIdentityTest`, `FixtureManifestTest` проходят БЕЗ правок; новый тест `LoadStagesCompatibilityTest` строит identity
  и результат без стадий для каждого сценария золотых файлов `fixtures/typed-boundary/golden/*` и `fixtures/slice1/identity/*` и
  сравнивает байты, а также проверяет, что в identity нет ключей `load_stages_*`, `input_versions.stages`, модулей стадий.
  Для `junit.xml`, `summary.txt`, `cli-summary.v1` и HTML без стадий добавляется тест побайтового равенства со снимком до изменения.
- [ ] **AC5. Identity и ключ.** Идентичные байты объявления дают один `analysis_id`; другой `to_offset_ms` другой `analysis_id` и
  `compatible = false`; анализ со стадиями и без стадий `compatible = false` и в сравнении baseline, и в динамике прогонов, и в
  `BASELINE_MIXED_SEMANTICS`; два анализа с одним объявлением `compatible = true`.
- [ ] **AC6. Без `resource_binding`.** В результате со стадиями нет `resource_binding`, `resource_summary`, `resource_policy_check`;
  есть `window_policy_summary` окна `steady` с `resource_verdict = NO_POLICY`, `window_id`, границами; `stage_binding` после `metric_summary` «весь
  прогон» (общего и по транзакции) и до `policy_check` / `window_policy_summary` (так в ADR 0030 и в коде PR A); `evaluated_millis = 60000`, `excluded_millis = 59800`.
- [ ] **AC7. Правила и окна.** Правило без `window_ids` применяется к `steady`; правило с `window_ids: ["ramp-up"]` даёт
  `rule_window_check` `RULE_WINDOW_NOT_FOUND` и `NO_VERDICT`; две стадии `steady` дают два окна и объединённый вердикт.
- [ ] **AC8. Платформенные правила.** Policy с `platform_rules` `effect = SLA` и стадиями даёт `NO_VERDICT` и причину
  `RESOURCE_SNAPSHOT_REQUIRED` (как сегодня без снимка), правило не теряется молча.
- [ ] **AC9. Сочетания.** Стадии и `--resources`, `--capacity`, `--source` дают коды `STAGES_RESOURCES_CONFLICT`,
  `STAGES_CAPACITY_CONFLICT`, `STAGES_SOURCE_CONFLICT` (без обращения к источнику), выход 4 (CLI) и 422 (API); стадии с
  `--source-context` без снимка проходят; `steady` за концом прогона даёт `STAGE_OUTSIDE_RUN`, `excluded` за концом прогона (ramp-down до 120 с при конце 119,8 с) проходит
  (CLI выход 4, API задание `FAILED` с `diagnostic.code`).
- [ ] **AC10. Валидатор.** Все `examples/valid` проходят, все `examples/invalid` дают ожидаемый код (таблица выше), граничные случаи:
  16 стадий (проходит), 17 (лимит), id 128 и 129 байт, дубль ключа JSON, число `1e3`.
- [ ] **AC11. Видимость.** Тесты на фразу: HTML, `summary.txt`, `summary` JSON (`windows[]`, в том числе два окна `steady`),
  `junit.xml` (суффикс сообщения у FAIL и NO_VERDICT, `<system-out>` у PASS), карточка вердикта (контрактные тесты UI),
  AsciiDoc/Confluence; текст при `NO_VERDICT`/`DEGRADED`/`NO_POLICY` не утверждает вердикт; для результата без стадий этих фраз нет
  (отрицательный тест).
- [ ] **AC11a. Края оценки в режиме стадий.** Правило по транзакции (политика называет `GET /items`): удерживается накопителем
  окна (`requestedTransactions` при стадиях), `policy_check` окна с `scope.kind = transaction`; пустое окно при применимом правиле
  даёт `BUSINESS_OBSERVATIONS_NOT_FOUND` и `NO_VERDICT`; окно меньше порога `sample_floor` даёт `INSUFFICIENT_SAMPLES`;
  `DEGRADED` вход (Gatling binary): `stage_binding` и `window_metric_summary` есть, `policy_verdict = NO_VERDICT`, `policy_check` нет.
- [ ] **AC12. Инварианты.** Результат с `INVALID` входом и стадиями не содержит `stage_binding`; `load-stages.json` лежит в каталоге
  анализа (канонические байты, SHA-256 равен `load_stages_sha256`); повторный анализ тех же входов возвращает сохранённый каталог.

## Разбивка на PR

Порядок: PR 0 можно делать сейчас (не зависит от W2.1). PR A-D начинаются после слияния среза 2 W2.1 и идут последовательно;
каждый PR содержит один целостный предмет, свой changelog-фрагмент `changelog.d/w2-5-<часть>.added.md` и свою документацию.

### PR 0. `docs/adr-0030-stage-window` (только документы)

- Создать `docs/adr/0030-load-stages-steady-window.md` (разделы выше, статус Proposed), `docs/superpowers/plans/2026-10-09-w2-5-...` уже в PR плана.
- Принять номер после проверки `origin/main`; принимает владелец. Кода нет. `Documentation impact:` в теле.

### PR A. `feat/w2-5-stage-window-core` (ядро; зависит от PR 0 и срез 2 W2.1)

Файлы:

- Новый `src/main/kotlin/io/ltverdict/core/LoadStages.kt` (≈ 250 строк): `LoadStagesV1`, `StageV1`, `StageRole`,
  `LoadStagesValidation.Valid(stages, canonicalBytes, sha256)`/`Invalid`, `validateLoadStages(source, maxBytes)`, `resolveStageWindows`,
  `stageBindingEvidence`, `stageWindowMetricSummaries`, `emptyResourceSide` (по образцу `ResourceSnapshot.kt`/`PodView.kt`).
- `sources/SourceAnalysis.kt`: `STAGES_SOURCE_CONFLICT` в `analyzeWithSources` до разрешения окна и запроса источника.
- `core/AnalysisService.kt`: поле `AnalysisRequest.stages`; проверки сочетаний 2-3; `requestedTransactions` при `stages != null`;
  `resolveStageWindows` вместо `resolveResourceWindows` при стадиях; вызов `evaluateSharedWindowPolicy` с пустой ресурсной стороной и
  платформенным правилом R4; запись `load-stages.json` в обоих путях записи (`invalidOutcome` и основной); `STAGE_OUTSIDE_RUN`.
- `core/WindowPolicy.kt`: без правок, если пустая сторона собирается снаружи (проверить; иначе минимальный параметр).
- `core/DiagnosticAnalysis.kt`: `windowMetricSummary` становится `internal` (одно слово).
- `core/AnalysisResult.kt` и `core/AnalysisDocuments.kt`: параметр `stages` в `analysisIdentity`, поля `load_stages_sha256`/`load_stages_version`,
  `input_versions.stages`, модули, лимиты стадий в `limits(...)`.
- `core/BaselineComparison.kt`, `core/RunComparison.kt`: условное звено ключа; предупреждение `WHOLE_RUN_METRICS_WITH_STAGES` только в
  `BaselineComparison.kt` вместе с `ui/src/types.ts` (объединение `BaselineComparisonWarning`) и `ui/src/shell/labels.ts`.
- `ui/src/types.generated.ts`: регенерация.
- Новые контракты: `docs/contracts/stages/v1/load-stages.schema.json`, `examples/valid/{ramp-steady-down,steady-only,two-steady}.json`,
  `examples/invalid/*.json` (10 файлов).
- Новые фикстуры: `fixtures/stages/ramp-steady-rampdown.jtl`.
- Тесты (новые): `LoadStagesTest` (валидатор + примеры контракта), `StageWindowPolicyTest` (AC1-AC3, AC6-AC8, AC12),
  `LoadStagesCompatibilityTest` (AC4, AC5), расширение `BaselineComparisonTest`/`RunComparisonTest` новыми методами (существующие
  тесты не правятся).
- `changelog.d/w2-5-stage-window-core.added.md`.

Пересечение с срезом 2 W2.1 (первым): срез 2 меняет продьюсеров evidence (36 мест `put("type", ...)` в 13 файлах `core`/`sources`, в
том числе `WindowPolicy.kt`, `ResourceSnapshot.kt`, `DiagnosticAnalysis.kt`, `Policy.kt`). PR A открывается после слияния, строит
`stage_binding` как новый ТИПИЗИРОВАННЫЙ вариант evidence по паттерну среза 2 (имена типов определяет он), а не `JsonObject`;
`windowMetricSummary` берётся в том виде, какой оставил срез 2. До слияния среза 2 в PR A не писать ни строки.

### PR B. `feat/w2-5-stage-window-input` (CLI и API; зависит от PR A)

- `cli/CommandLine.kt`: `--stages`, `readStages` (по образцу `readResources`), `usageText()`, сочетания до чтения входа; передача в `AnalysisRequest`.
- `web/LocalApi.kt`: часть `stages` в `receiveJob`, `InvalidStages` -> 422 `INVALID_STAGES`, сочетания (строки 2099-2190 шаблон),
  лимит размера; `jobs/AnalysisJobs.kt`: ветка `STAGE_OUTSIDE_RUN`.
- Тесты: `CommandLineTest` (новые методы: коды выхода 0/2/3/4/64, сочетания, повтор флага), API-тест задания со стадиями (часть `stages`,
  `INVALID_STAGES`, сочетания, `FAILED` с `STAGE_OUTSIDE_RUN`).
- `changelog.d/w2-5-stage-window-input.added.md`.
- Пересечение с W2.2 (идёт после W2.1: `installLocalApi` на маршруты по ресурсам, `RunBundleStore` на хранилища): PR B правит
  `receiveJob`, отображение `Invalid*` в 422 и тест хранилища из R9, то есть те же места. PR B идёт ПОСЛЕ W2.2 либо rebase на неё;
  код PR A (core) от W2.2 не зависит.
- Пересечение с W2.3 (`--baseline` в `CommandLine.kt`): оба пишут в разбор флагов `analyze` и `usageText()`; конфликт механический,
  разрешается по порядку слияния; семантически независимо (сравнение берёт ключ из PR A).

### PR C. `feat/w2-5-stage-window-visibility` (отчёт, UI, junit; зависит от PR B)

- `cli/CliArtifacts.kt` (`summaryText`, `summaryJson` с `windows[]`, `junitXml` с суффиксом и `<system-out>`), `report/HtmlReport.kt`
  (`verdictBlock` и `dl`), `report/AsciiDocReport.kt`, `ui/src/verdictSummary.ts` (`headline`, `lead`, `facts`),
  `ui/src/types.ts` (тип `stage_binding`, объединение `BaselineComparisonWarning`), `ui/src/shell/labels.advice.ts`,
  `ui/src/shell/labels.ts` (подпись предупреждения), `core/BaselineComparison.kt` (предупреждение), контрактные тесты `ui/`
  (`npm run test:contracts`), при изменении UI оффлайн Playwright.
- Тесты: `CliArtifactsTest` (новые методы + побайтовое равенство без стадий), `HtmlReport`-тест, контрактный тест UI.
- `changelog.d/w2-5-stage-window-visibility.changed.md`.
- Пересечение с W2.6 (отчёт для людей: русский `<h1>`, панели, `HtmlReport.kt`, `LoadSample`): оба пишут в `HtmlReport.kt`; фраза
  PR C это одна функция `stageNotice(...)` и один вызов в `verdictBlock`, чтобы конфликт был в одной строке. Рекомендуемый порядок
  `W2.6` до PR C или PR C затем rebase W2.6; решает оркестратор по очереди волны 2.
- Пересечение с W2.4 (честные заголовки: «доля разгона и простоев», пометка throughput-правил): PR C публикует `excluded_millis` и
  `evaluated_millis`, W2.4 берёт долю из `stage_binding`; заголовок «окно вердикта» задаёт PR C, W2.4 расширяет его, не заменяет.
- Пересечение с W2.3 (раздел «Изменения относительно baseline» в HTML/AsciiDoc/Confluence): оба меняют `HtmlReport.kt`
  и `AsciiDocReport.kt`; после слияния W2.3 раздел сравнения для анализов со стадиями должен брать steady-метрики из `window_metric_summary` (обязательство R7).

### PR D. `docs/w2-5-stage-window-guide` (документация; зависит от PR C)

- Пользовательское руководство `docs/user/load-stages.md` (сценарий «журнал JMeter с разгоном», пример файла, пример вызова
  `ltv analyze ... --stages`, как читать фразу, как привязать правило `window_ids`), строка «что умеет сегодня» в `README.md`,
  статус ADR 0030 Accepted (по слову владельца), отметка W2.5 выполненной в `2026-10-08-review-work-plan.md`.
- Проверки: markdownlint, `changelog_assemble.py --check`. Кода нет.

Критическая цепочка к W3.7: PR 0 -> срез 2 W2.1 -> PR A -> PR B (достаточно для W3.7: окна есть в результате и вход доступен).
PR C и D закрывают критерий видимости W2.5, W3.7 не блокируют, но W2.5 считается выполненным только после PR C.

## Риски

| Риск | Что случится | Как закрыто |
| --- | --- | --- |
| Срез 2 W2.1 меняет продьюсеры evidence, PR A на устаревшей базе | конфликты и ручное перенесение | PR A только после слияния среза 2; `stage_binding` по его паттерну |
| Второй `metric_summary` с `overall` | `singleOrNull` в двух читателях возвращает `null`, метрики сравнения пропадают | R5: только `window_metric_summary`; тест сравнения со стадиями проверяет, что `metrics` не пустые |
| Платформенные правила теряются в окне без снимка | SLA-правило даёт PASS | R4 и AC8 |
| Смещения от другого профиля | steady-окно покрывает разгон | `STAGE_OUTSIDE_RUN`, `stage_binding.stages[].from_epoch_ms` в отчёте, ключ сопоставимости |
| Дельты сравнения «весь прогон» читаются как steady | ложный вывод о регрессии | R7: предупреждение и текст; окно сравнивается режимом `window_comparison` |
| Старый baseline без стадий перестаёт быть совместимым | пользователь теряет сравнение | это замысел решения 4; пересохранить baseline; запись в руководстве PR D |
| Генерируемые типы UI расходятся | падает `TypeScriptGeneratorTest` | регенерация `types.generated.ts` в PR A |
| Фикстуры вне манифеста | `FixtureManifestTest` не знает `fixtures/stages` | проверить область теста в PR A (манифест охватывает `fixtures/slice1`); при необходимости добавить запись |
| PR слишком большой | правило 10 MINIMAL-CHANGE | PR A ≈ 350 production-строк, 14 файлов с короткими вставками; при превышении вдвое остановиться и описать причину |

## Вопросы владельцу

Блокирующих вопросов нет: решения 1-5 задают форму. Но четыре ruling'а не следуют из ответов владельца напрямую и названы явно;
каждый можно переиграть до PR A:

- **R4.** В режиме стадий политика только из платформенных SLA-правил даёт `NO_VERDICT`, а без стадий та же политика даёт
  `NO_POLICY` с причиной покрытия. Это намеренное ужесточение (не молчать про непроверенный SLA ресурса).
- **R7.** Динамика прогонов, сравнение транзакций и ранжирование кандидатов статистического эталона (`BaselineComparison.kt`,
  `metricValue`) остаются на метрике «весь прогон» без предупреждения; предупреждение есть только в сравнении с эталоном. Пересчёт
  на steady это обязательство W2.3/W2.4.
- **R1.** Смещения от начала прогона, а не epoch; `excluded` стадии не проверяются на конец прогона.
- **R12.** Форма загрузки стадий в UI отдельным срезом (первые потребители CLI, API, MCP).

## Проверка (без CI; перед пушем каждого PR, на результате слияния со свежим `origin/main`)

Для PR с планом и PR 0, D (только документы): `python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`;
`npx --yes markdownlint-cli2@0.23.2 "**/*.md"` (или конкретные файлы); проверка секретов (`gitleaks`, если доступен), `git diff --stat`
без чужих файлов, без `.qwen/`. Gradle и UI не запускаются, потому что кода нет.

Для PR A, B, C (код):

1. `. F:\Coding\LT-Verdict\.worktrees\_tools\ltv-slot.ps1; Invoke-LtvExclusive { gradlew --no-daemon cleanTest check installDist }`;
2. `cd ui; npm run typecheck; npm run lint; npm run test:contracts`;
3. оффлайн Playwright (`Invoke-LtvE2E`) для PR C;
4. `python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`; markdownlint для `.md`.

TDD: в каждом PR красный тест раньше кода (валидатор по примерам, затем окно по фикстуре, затем identity/ключ, затем видимость).

## Шаги выполнения

### Task 1: PR 0, ADR 0030

**Files:** Create `docs/adr/0030-load-stages-steady-window.md`.

- [ ] Проверить номер: `git fetch; git ls-tree origin/main docs/adr/ | tail`.
- [ ] Написать ADR по разделу «Что фиксирует ADR 0030»; статус Proposed.
- [ ] `npx --yes markdownlint-cli2@0.23.2 docs/adr/0030-load-stages-steady-window.md`; `python tools/verify_slice0.py`.
- [ ] Коммит `docs(adr): add ADR 0030 load stages and steady-state window`; один push; PR без автослияния.

### Task 2: PR A, ядро

**Files:** см. PR A.

- [ ] Красный: `LoadStagesTest` читает `docs/contracts/stages/v1/examples/*` и ожидает коды таблицы; запуск
  `gradlew test --tests "*LoadStagesTest"` падает (класса нет).
- [ ] Зелёный: схема, примеры и `validateLoadStages`.
- [ ] Красный: `StageWindowPolicyTest` на `fixtures/stages/ramp-steady-rampdown.jtl` с литералами AC1-AC3; зелёный: `resolveStageWindows` и ветка в
  `AnalysisService`.
- [ ] Красный: AC6-AC8; зелёный: `stage_binding`, пустая ресурсная сторона, платформенные правила.
- [ ] Красный: `LoadStagesCompatibilityTest` (AC4, AC5); зелёный: поля identity, модули, лимиты, звено ключа, предупреждение.
- [ ] Регенерировать `ui/src/types.generated.ts`; полная проверка (раздел «Проверка»); коммиты атомарные по строке выше; push один раз.

### Task 3: PR B, CLI и API

- [ ] Красный: `CommandLineTest` методы для `--stages` и сочетаний; зелёный: `readStages`, `usageText`.
- [ ] Красный: API-тест части `stages`; зелёный: `receiveJob`, `InvalidStages`, `AnalysisJobs`.
- [ ] Полная проверка; push.

### Task 4: PR C, видимость

- [ ] Красный: `CliArtifactsTest`/HTML/UI-контрактные тесты на фразу (AC11), отрицательные тесты без стадий; зелёный: правки файлов PR C.
- [ ] Полная проверка включая Playwright; push.

### Task 5: PR D, документация

- [ ] Руководство, README, статус ADR, отметка в перечне работ; проверки документов; push.

## Совет Codex Astra (read-only, по плану до кода)

Расчёт фикстуры Astra пересчитала независимо (HdrHistogram 2.2.2, 3 знака): плато p50=149, p95=194, p99=198, 10 rps; весь прогон p95=821,
p99=836, 6,01 rps. Совпало с планом. Замечания проверены по коду и учтены:

| № | Замечание | Проверка по коду | Решение |
| --- | --- | --- | --- |
| 1 | У проходящего gate в `junit.xml` нет сообщения | верно: `CliArtifacts.kt:117`, `:148-149` самозакрытый `testcase` | `<system-out>` у PASS/NO_POLICY, суффикс у остальных |
| 2 | Сводка не умеет два окна `steady` | верно: один объект `window` | массив `windows[]`, тест на два окна |
| 3 | Конфликт с `sourceAcquisition` лишний | верно: `readOpenSearchContexts(..., resources = null)` даёт контекст без снимка | разрешено; отвергается только онлайн-запрос и сочетание через снимок |
| 4 | Онлайн-вход проходит до `AnalysisService` | верно: `SourceAnalysis.kt:120-130` | проверка в `analyzeWithSources` до обращения к источнику |
| 5 | `DEGRADED` и формулировка | верно: `Policy.kt:106-107` | фраза только при вынесенном вердикте, иначе нейтральный текст, AC11a |
| 6 | Предупреждение сравнения недоставимо | верно: `warnings` только у сравнения с эталоном | предупреждение только там плюс типы и подписи UI; динамика и транзакции передаются W2.3/W2.4 |
| 7 | Скачивание артефакта не существует | верно: карта имён `LocalApi.kt:770-845` | файл только в bundle, маршрут не добавляется |
| 8 | Порядок evidence: binding не первый | верно: `base.evidence + resource.evidence` | порядок описан по факту, повторяет `resource_binding` |
| 9 | Платформенное правило: прежнее поведение описано неверно | верно: платформенная политика без бизнес-правил даёт `NO_POLICY` | описано как намеренное ужесточение режима стадий, три теста |
| 10 | `observed` это точная дробь; нужны краевые тесты | верно: `Policy.kt:287`, `:296-299` | AC1 уточнён, добавлен AC11a |

## Self-Review

- Покрытие: решения владельца 1-5 покрыты R1-R12 и AC1-AC12; «форма входа», «стадии в окна», «evidence», «identity и ключ»,
  «влияние на analysis_id», «взаимодействие», «критерии», «PR и пересечения», «риски», «ADR 0030» присутствуют.
- Плейсхолдеров нет. Имена согласованы (после правок по совету Astra перечитаны все места): `load-stages.v1`, `load_stages_sha256`,
  `stage_binding`, `windows[]` в сводке, `STAGES_{RESOURCES,CAPACITY,SOURCE}_CONFLICT`, `STAGE_OUTSIDE_RUN` (только `steady`).
- Допущение, не проверенное кодом: каталог анализа с новым файлом `load-stages.json` читается хранилищем (проверка тестом в PR A).
- Допущение: `FixtureManifestTest` не охватывает `fixtures/stages` (проверка в PR A).

## Review Focus

- Платформенные правила без снимка в режиме стадий тихо теряются: закрыто R4 и AC8.
- Второй `metric_summary` `overall` ломает сравнение и отчёт: закрыто R5, тест сравнения со стадиями.
- Выборка на границе окна относится к соседнему окну: закрыто AC3 (полуоткрытый интервал по старту).
- Прогон без стадий меняет байты identity или junit: закрыто AC4 (золотые файлы и побайтовые снимки).
- Объявление от чужого профиля подходит по длине и молча применяется: `STAGE_OUTSIDE_RUN`, границы в отчёте, ключ сопоставимости.

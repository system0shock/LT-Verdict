# Advisory AI synthetic acceptance implementation plan

## Возобновление 2026-09-21

Пользователь поручил продолжать в существующем worktree и распараллелить
независимые задачи. Бюджет признан некритичным: доработка общего денежного
ограничителя отложена; существующий код бюджета пока сохраняется.
Это не меняет semantic gates, изоляцию, запрет retries или объём 30 × 2.

Ближайший объём: строгая проверка boolean-предпосылок live, сохранение
structured advice после удаления временных файлов, завершение независимых
oracle bindings и проверка calibration/freeze. Готовый корпус 30/30 повторно
не экспортируется. Production AI, Jenkins и новые статистические эксперименты
в этот шаг не входят. Проверки runner выполняются локально без model requests;
live RELEASE возможен только с подтверждёнными предпосылками.

## Minimal test-only live-attempt contract

`LiveAttempt` executes one member of the frozen 30-case x 2-attempt plan. It requires
`-ExecuteLive` and a `RELEASED` control file; these are operational interlocks, not a
substitute for the root RELEASE decision. The control binds the four completed gates
(`corpus_complete`, `oracles_frozen`, `expert_calibration_passed`,
`offline_preflight_passed`), hashes for the methodology, prompt, schema, runner,
guarded relay, `run-live.sh`, corpus manifest, oracle manifest, calibration record and
passing preflight manifest, fixed runtime/budget values, and exactly 60 attempts. Each
attempt has `ordinal`, `attempt_id`, `case_id`, `repeat_index`, repo-relative
`evidence_path`, and `evidence_sha256`; every one of 30 cases occurs at repeats 1 and 2
with the same evidence hash. Gate evidence is hash-checked and never mounted into Qwen.

The host reserves the persistent `$2` ledger before network/provider access, mounts the
authorization read-only into the dedicated relay, and gives the real credential only to
that relay through the existing env file. Qwen has only the internal relay network. The
same guarded relay settles trusted terminal usage or retains the full reservation for an
unknown outcome. No automatic retry, fallback, repair, smoke request, or request outside
the 60 frozen IDs is permitted; budget exhaustion is `INCOMPLETE` before forwarding.

## Исправление блокеров стенда, 2026-09-06

Пользователь поручил исправить блокеры и разрешил локальные проверки.
Бюджет платной приемки: не более $2 суммарно, включая неуспешные попытки.
Production, Jenkins, prompt, статистические пороги и semantic gates не меняются.

- Исправить ссылки диагностических окон в общем test-only projection;
  невозможное сравнение с удаленным reference не заменять self-comparison.
- Собрать независимые case-local gaps за один проход, не публикуя частичный
  corpus как готовый и не заменяя неудобные случаи.
- В preflight разделить результат отрицательного контроля и исправность
  стенда. По методике §6 и §11.5 relay может блокировать retries/repair;
  обнаруженная запрещенная попытка остается injection FAIL модели.
- Доказать блокировку запрещенного tool до передачи ответа Qwen, а не по
  отсутствию marker-файла. Оставить один upstream request на попытку.
- Ограничить общий бюджет до forwarding; неоднозначный исход не возвращает
  резерв автоматически. Состояние бюджета не сбрасывается перезапуском.
  Нехватка бюджета означает INCOMPLETE, не дополнительное разрешение расходов.
- Подготовить независимые per-case oracles, затем отдельно проверить binding
  к реальному evidence. Это не назначение production output эталоном.

Файлы реализации: `src/test/kotlin/io/ltverdict/core/AdvisoryAcceptanceCorpusTest.kt`,
`tools/advisory_ai_acceptance_runner.ps1`; oracle/preflight artifacts под
`build/ai-acceptance/v1/`. Новых production dependencies и публичных контрактов нет.
Команды проверки остаются командами corpus export и локального fake preflight
ниже. Платные вызовы разрешены только после всех исходных live prerequisites.

> **For agentic workers:** use `superpowers:subagent-driven-development` for
> independent preparation tasks; root coordinates the freeze and sole Gradle process.

**Goal:** Подготовить и провести 30 x 2 приёмку выводов ИИ после экспертных правок,
не подменяя качество текста технической работоспособностью runner.

**Architecture:** Штатный AnalysisService/store/projector создаёт inputs.
Test-only runner использует pinned digest уже имеющегося Qwen probe runtime.
Evaluator views отделены от model input и друг от друга; product code не меняется.

**Tech Stack:** существующие Kotlin/JUnit, PowerShell/Node.js, локальный Docker;
без новых production dependencies и downloads образов.

**Spec:** `docs/advisory-ai-synthetic-acceptance-methodology-v1.md`, включая §11.

## Ограничения

- Не менять production, API/UI, Jenkins, frozen статистические исследования.
- Qwen `0.21.1`, модель `deepseek/deepseek-v4-flash-0731`, без fallback/retries.
- Точный prompt: `docs/contracts/advice/v1/system-prompt.md`; schema:
  `docs/contracts/advice/v1/ai-advice-output.schema.json`. Не настраивать их по outputs.
- До live: представимость всех cases, independent oracle checks, reviewer
  calibration, frozen manifest, offline preflight и бюджет расходов.
- Исполнение локальных проверок разрешено просьбой начать приёмку.
  Live RELEASE root выдаётся только после обязательных предпосылок.
- Один Gradle process у root; агенты не запускают Gradle параллельно.
- Существующие пользовательские изменения сохраняются, Git/коммиты не выполняются.

## 1. Методика и rubric: root

- [x] Прочитать экспертное ревью и отделить обязательные уточнения от будущих расширений.
- [x] Уточнить hard/soft, claim support, два независимых review всех 60 outputs,
  calibration controls, два evaluator views, контрастные пары и плотный evidence.
- [x] Сохранить 30 x 2 и численные gates, не объявлять их результатом испытаний.
- [ ] Получить и записать верхний бюджет live серии.

## 2. Representability и corpus: correlation agent

Файлы: новый `src/test/kotlin/io/ltverdict/core/AdvisoryAcceptanceCorpusTest.kt`;
артефакты только `build/ai-acceptance/v1/corpus/`.

- [ ] Сопоставить 30 case IDs с реальными frozen fixtures/минимальными literals;
  сначала сообщить gaps, не писать большой новый generator.
- [ ] Test-only exporter вызывает настоящий ingest/AnalysisService/store и
  `AdvisoryEvidenceBuilder`; не редактирует immutable analysis.
- [ ] Выдать `inputs/<case_id>/evidence.json`, manifests с исходными hashes,
  отдельные `evidence-oracle.jsonl` и `process-truth.jsonl`.
- [ ] Independent oracle не выводится из production expected; roots чисел и
  значимости должны иметь отдельное обоснование. Найденные upstream gaps не скрывать.
- [ ] Выполнить разрешённый root checkpoint после готовности exporter:

```powershell
.\gradlew.bat --offline --no-daemon test -x npmCi --tests '*AdvisoryAcceptanceCorpusTest'
```

Это запланированная команда после создания test, не доказательство его наличия
или успеха. Незакрытая строка corpus оставляет live заблокированным.

## 3. Reviewer controls: baseline agent

Файлы: `docs/advisory-ai-acceptance-reviewer-controls-v1.md` и
`build/ai-acceptance/v1/reviewer-controls/evidence-view.jsonl`,
`build/ai-acceptance/v1/reviewer-controls/expected-labels.jsonl`.

- [ ] Подготовить 10 ручных/детерминированных controls по §11.2 в настоящей
  `ai-advice-output.v1` shape; hidden labels не включать в reviewer view.
- [ ] Два независимых первичных чтения без ключа; проверить по ключу и сохранить
  disagreement/adjudication record. LLM-assistance не обозначать human review.
- [ ] Незакрытая calibration не даёт разрешения запускать live.

## 4. Offline runtime: AI agent

Файл: новый `tools/advisory_ai_acceptance_runner.ps1`;
артефакты `build/ai-acceptance/v1/preflight-runtime/`.

- [ ] Переиспользовать проверенную topology с digest-pinned cached image,
  без новых provider abstractions и production launcher.
- [ ] Локально проверить valid/malformed/timeout/prohibited-tool/retry сценарии,
  safe action events и wire-level upstream count <= 1 на попытку.
- [ ] Сохранить sanitized результаты, не raw diagnostics, secrets или env dump.

```powershell
.\tools\advisory_ai_acceptance_runner.ps1 -Mode Preflight
```

Команда исполняется после создания harness; live mode не активируется автоматически.

## 5. Общая точка решения: root

- [ ] Свести completeness, representability, calibration и preflight statuses.
- [ ] Заморозить inputs/oracles/prompt/schema/runner hashes и 60 attempt IDs.
- [ ] Согласованный budget и upstream enforcement должны реально ограничивать
  серию; без этого не обещать безопасное соблюдение денежного лимита.
- [ ] Выполнить live только при выполнении предпосылок. При блокере сохранить
  причину и достигнутые результаты, не заменять cases удобными и не объявлять PASS.
- [ ] После live отдельно execution/semantics/utility/review; все 60 outputs,
  оба первичных scores и adjudication доступны для итогового экспертного решения.

Optional deterministic baseline не блокирует эту подготовку; без него added
value остаётся `NOT_EVALUATED`. Production launcher/API/UI остаются следующим
треком, а не условием оценки текста через test-only runner.

### Фиксированный повтор подготовки покрытия N02/N03/N04

Первый bounded JVM поиск исчерпан: N02 seeds 1000..1009 не дали selected;
N03 seed 1000 не дал selected; N04 seeds 1000..1009 выбрали только pair-00.
Это сохраненный INPUT_GAP подготовки, не результат платной приемки.
До новых JVM запусков и до любых model outputs фиксируется shortlist из уже
существующего frozen NumPy correlation-full-v1-cases.jsonl:

- N02: 1016, 1039, 1044, 1046, 1062, 1089, 1091, 1093, 1099, 1116.
- N03: 1007.
- N04: 1012, 1015, 1048, 1060, 1064, 1067, 1106, 1108, 1131, 1134.

Это целевой отбор representability для challenge corpus, не случайная выборка
и не новая калибровка частоты ошибок. NumPy и JVM не обещают одинаковые RNG
результаты. Число кандидатов на этот повтор остается 10/1/10; прежние попытки
не стираются и учитываются отдельно. Если shortlist не подходит, остается gap,
а не ослабляется требование иметь реальную лишнюю находку.

Одновременно исправляются точные predicates контракта: C02 BELOW_EFFECT и
MATERIALITY_NOT_MET; unavailable требует status=UNAVAILABLE; C03 требует
непустые controls_used и GENUINE_PARTIAL_UNCALIBRATED; C05 дополнительно требует
NO_RESIDUAL_VARIATION. SLA, alpha, materiality thresholds и исходные ряды неизменны.

### Исправление ошибочного выбора окон и member до live

Независимые расчеты на evaluator-only canonical inputs выявили отсутствие
обещанной деградации U01/U02. Технически подготовленные 29 inputs не означают
29 подтвержденных semantic scenarios. Исправляется подбор, не измерения:

- U01: тот же frozen NT01-warmup_cpu_demand_x2_first_60s-2000 member0,
  existing workload-04 вместо раннего start+60..300s; CPU plateau=1.0 и
  независимый request p95=28550ms (не утверждение о точном HDR output).
- D05: тот же NT01-clean-2000 member0, existing workload-04 (300 cells),
  association-04/cpu-queue. Требовать raw-evaluable и именно compute-cap
  UNAVAILABLE; прежний 180-cell constant input этого не доказывал.
- U02/H05: NT07-clean-2000 member0 вместо member1, тот же dropDownstream;
  H05 остается canary-only counterpart. В member0 reference/workload p95
  меняется 50 -> 100ms; member1 является intervention и остается 50 -> 50ms.
- N05: тот же NT07 member0, downstream telemetry сохранена, clockUnknown.

Для всех пяти случаев добавляются проверки соответствующего наблюдаемого
смысла. Исходные traces, thresholds, prompt и правило отсутствия causality
не меняются. Это исправление ошибки mapping до model outputs, не подгонка
ответов. N03 остается на прежнем seed1007; дополнительный список пяти seeds
запрошен у пользователя отдельно и пока не разрешен.

## Пользовательское изменение runtime, 2026-09-21

После 28 ответов HTTP404 пользователь явно поручил: «Отключи ценовой резерв и
используй вызов модели deepseek v4 flash». Для отдельной новой серии используется
`deepseek/deepseek-v4-flash` (OpenRouter: DeepSeek V4 Flash 0423), денежный резерв
и provider price filter отключены. Предыдущая серия остаётся INCOMPLETE /
UPSTREAM_ERROR, без оценки качества модели. Новый объём — прежний план30×2;
те же evidence, prompt, schema, oracle, изоляция и один upstream request на попытку.
Это решение имеет приоритет над прежними требованиями $2/caps/reservation ниже.
Учёт доступного usage остаётся наблюдением, без удержания резерва и без
необоснованных утверждений о фактической стоимости.
REQUESTED: Disable monetary reserve and use deepseek/deepseek-v4-flash.
REQUIRED TO ACHIEVE IT: remove price routing filter/reservation in test runner and
relay; enforce explicit disabled_by_user control; preserve nonmonetary guards;
repeat offline preflight; freeze separate60-attempt pilot; validate and review outputs.
NOT REQUIRED: production code, corpus regeneration, prompt/oracle changes,
statistical calibration, redesign of budget accounting.
EXPECTED FILES TO CHANGE: tools/advisory_ai_acceptance_runner.ps1,
tools/test_advisory_ai_acceptance_runner.ps1, build/ai-probe/fixed-openrouter-relay.mjs,
test output adapter root selection, pilot documentation/artifacts.
Observable checks: no reserve ledger or provider.max_price; model exact match;
strict release booleans; one external request; bounded output; fake negative cases
contained; retained advice validates against actual input; full60 outcome report.
Documentation impact: test-only methodology/runbook updated; no product behavior
or public contract changes, no CHANGELOG entry required.

Итоговое уточнение пользователя: ModelStudio Token Plan из настроенного Qwen,
модель deepseek-v4-flash, фиксированный endpoint
`https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions`.
OpenRouter-вариант новой серии не запускался. Денежный резерв отключён явно;
OpenRouter provider/max_price параметры не передаются. Три подряд upstream
ошибки прекращают серию с INCOMPLETE; повторов и изменения prompt нет.
Root HostPreflight свежий PASS, parsed advice сохранён; live_requests=0.

## ModelStudio streaming compatibility

REQUESTED: Continue the authorized pilot through configured Singapore Token Plan.
REQUIRED TO ACHIEVE IT: Reassemble streamed tool names by call index; retain empty
continuations without treating them as new forbidden tools, while rejecting truly
unnamed/forbidden calls. Preserve safe finish reasons and token usage without requiring
OpenRouter-only cost metadata. Add bounded synthetic stream regression and repeat
local preflight before a new immutable series. Existing SG series stopped after3HTTP200.
NOT REQUIRED: prompt/thinking/output-limit tuning, relaxed semantic gates, production code.
EXPECTED FILES TO CHANGE: build/ai-probe/fixed-openrouter-relay.mjs, a bounded test
for the real inspector, pilot artifacts and documentation. PS wrapper only if contract
integration needs it. No production dependencies or public API changes.

## 2026-09-21 — пользователь отменил жёсткий output-token limit

REQUESTED: убрать принудительный лимит 4096 токенов целиком.
REQUIRED TO ACHIEVE IT: relay удаляет max_tokens/max_completion_tokens/max_output_tokens из upstream request; отсутствие лимита фиксируется как provider_default, max_output_tokens=null. Снять проверку completion_tokens<=4096 и обновить активные control/auth/wire/preflight checks. Сохранить безопасную валидацию usage. Остановить прежнюю серию и зафиксировать новую.
NOT REQUIRED: менять prompt, thinking mode, модель, semantic gates, timeout, byte limits, production runner/API/UI; возвращать денежный резерв.
EXPECTED FILES TO CHANGE: build/ai-probe/fixed-openrouter-relay.mjs, tools/advisory_ai_acceptance_runner.ps1, tools/test_advisory_ai_relay.mjs, tools/test_advisory_ai_acceptance_runner.ps1; docs и artifacts серии.
Acceptance: upstream request не содержит ни одного output-token cap; synthetic completion_tokens>4096 принимается; Host/full preflight проходит; старые controls не запускаются как новая конфигурация.

## 2026-09-21 — ожидание ответа до10 минут

REQUESTED: пользователь подтвердил увеличение ожидания до10 минут.
REQUIRED TO ACHIEVE IT: Qwen wall time600s, launcher KILL605s, host watchdog613s, provider socket inactivity600s; сохранить exit/OOM/duration для различения причин остановки. Новая отдельная серия с прежними60inputs. Проверить согласованность таймаутов и повторить preflight.
NOT REQUIRED: менять byte limits, prompt, thinking, модель, schema, semantic gates или возвращать token cap/денежный резерв.
EXPECTED FILES TO CHANGE: build/ai-probe/run-live.sh, build/ai-probe/fixed-openrouter-relay.mjs, tools/advisory_ai_acceptance_runner.ps1, tools/test_advisory_ai_acceptance_runner.ps1, docs/artifacts.

## 2026-09-21 — увеличение SSE transport и relay memory

REQUESTED: пользователь подтвердил64MiB SSE и1GiB memory relay.
REQUIRED TO ACHIEVE IT: изменить provider response bound в relay/runner на67108864 и relay Docker memory на1g. Добавить >1MiB fake response в существующий valid preflight; показать RED старого bound и GREEN нового. Сохранить10-minute wait и отсутствие token cap. Новая отдельная серия60inputs.
NOT REQUIRED: менять Qwen memory, output/schema limits, prompt/thinking/model, semantic gates, production API/UI или зависимости.
EXPECTED FILES TO CHANGE: build/ai-probe/fixed-openrouter-relay.mjs, tools/advisory_ai_acceptance_runner.ps1, docs/artifacts.

## 2026-09-21 — client timeout amendment после ordinal20

REQUESTED: довести уже одобренное10-minute ожидание до внутреннего Qwen HTTPclient.
REQUIRED TO ACHIEVE IT: установить поддержанный request timeout600000ms в isolatedlauncher, проверить связанную regression/preflight. Сохранить все20attempts неизменными (18VALID,2FAILED); продолжить только21..60 под отдельным control/runtime snapshot. Не повторять успешные или неуспешные попытки. Hash manifest checkpoint-20/artifact-hashes.json фиксирует135артефактов до изменения.
NOT REQUIRED: менять prompt/model/evidence/schema/thinking, semantic gates, уже полученные результаты, token/byte/memory limits; перезапускать весьпилот.
EXPECTED FILES TO CHANGE: build/ai-probe/run-live.sh, tools/test_advisory_ai_acceptance_runner.ps1, docs и continuation artifacts. КонкретныйCLIflag должен быть подтверждён исходниками установленногоQwen.
Acceptance/reporting: итог содержит два runtime blocks(1..20 и21..60), с явной причиной amendment. Не выдавать mixed-runtime series за неизменённую initialfreeze или финальныйhumanexpertPASS; ранееfailed остаютсяfailed.

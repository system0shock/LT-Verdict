# AI acceptance v1: результат подготовки после ревью

## Возобновление 2026-09-21

Состояние ниже заменяет устаревшие checkpoint в исторической части.

### Продолжение после решения пользователя

- Пользователь разрешил восстановление Docker и явно принял AI-assisted
  calibration как допуск к пилоту; решение с hash исходного отчёта сохранено
  в `build/ai-acceptance/v1/resume-2026-09-21/calibration-decision.json`.
  Это не human review и не итоговый acceptance PASS.
- Docker engine 29.2.1 стал доступен до переименования сокета: файл не менялся.
  Свежие `HostPreflight` и полный `Preflight` прошли; последний — 7/7 controls,
  0 live requests. Артефакты: `preflight-runtime/host-resume-20260921/` и
  `preflight-runtime/full-resume-20260921/` внутри `build/ai-acceptance/v1/`.
- Дополнительные policy bindings завершены: 6 случаев, 6 фактов, 9 references.
  Root независимо проверил hashes входов, точные references и согласие SLA.
  Артефакты — `oracle-preparation/supplemental-policy-bindings-2026-09-21.json`
  и соответствующий validation report. Они дополняют прежние слои;
  сами по себе не объявляют весь oracle frozen.

### Предыдущий checkpoint этого дня

- Корпус сохранён: 30/30 evidence файлов повторно проверены по размеру и
  SHA-256, расхождений нет. Повторный экспорт не запускался.
- Подготовлен `build/ai-acceptance/v1/resume-2026-09-21/attempt-plan.json`:
  60 уникальных попыток, две на каждый case, concurrency 1; статус
  `DRAFT_NOT_RELEASED`. Платных запросов серии по-прежнему 0/60.
- В test-only runner исправлены приведения строк к boolean в четырёх
  предпосылках допуска и в preflight gate. Принимается только JSON `true`.
- Structured advice сохраняется в `advice-output.json` каталога попытки;
  `result.json` содержит его SHA-256. Удаление временного stdout больше не
  уничтожает единственную копию ответа. Raw stdout/stderr не публикуются.
- RED подтвердил оба дефекта: строка `false` открывала gate, parsed advice
  терялся. Команда `pwsh -NoProfile -File tools/test_advisory_ai_acceptance_runner.ps1`
  прошла после исправлений и в отдельном запуске root. Синтаксические проверки
  обоих PowerShell файлов прошли; независимое scoped review — PASS.
- Свежая контейнерная проверка не выполнена: Docker Desktop падает при старте
  Inference manager на сокете `dockerInference`. Engine недоступен. Настройки
  и данные Docker не сбрасывались; прежний preflight не выдаётся за свежий.
- Проверка controls подтверждает bounded `AI_ASSISTED_CONTROL_CHECK_PASS`,
  10/10 у обоих прежних reviewers. Экспертное решение остаётся открытым.
  Для итоговых оценок нужны явные claim-to-ref/support/severity записи;
  совпадение суммарных labels само по себе этот формат не проверяет.
- Пользователь отложил доработку денежного лимита как некритичную. Код бюджета
  не менялся; прежнее замечание о выборе ledger path не объявляется исправленным.
- Oracle review не завершён. Предварительные вопросы к привязкам S05/H03,
  U01, U02/H05 и H02 сохранены в
  `build/ai-acceptance/v1/oracle-preparation/resume-checkpoint-2026-09-21.json`.
  Это список для проверки по уже подготовленным независимым фактам, не
  подтверждённые дефекты и не окончательный oracle. Historical artifacts
  не изменены; final freeze остаётся открытым.

Production AI/API/UI, Jenkins, статистические пороги и frozen experiments
не менялись. Documentation impact: уточнены состояние и воспроизведение
test-only приёмки; пользовательское поведение продукта не изменилось.

## Исторические checkpoint 2026-09-06

Дата: 2026-09-06. Статус: `PREPARATION_INCOMPLETE`; live series `BLOCKED`.
Запланировано 30 cases / 60 attempts; выполнено **0 live attempts**.
Этот отчёт не является семантической оценкой DeepSeek и не закрывает MVP gate.

## Правки методики

В [методику](advisory-ai-synthetic-acceptance-methodology-v1.md) внесены
уточнения по [экспертному ревью](advisory-ai-acceptance-validity-review.md):

- Исполнимый per-case oracle, support types и явные HARD/SOFT правила.
- Два независимых первичных чтения всех 60 outputs, включая успешные.
- Reviewer calibration на 10 controls и отдельные evidence/truth views.
- Контрастные пары, плотный mixed-evidence U05, объявленная область сложности.
- Evidence-faithful usefulness отдельно от process-level noise/utility.
- Wire-level проверка request budget и различение attempted/contained actions.
- Optional deterministic baseline; без него added value `NOT_EVALUATED`.

Объём 30 x 2 и исходные численные gates не снижались. Код продукта, API/UI,
Jenkins и production dependencies не менялись в этой подготовке.
Порядок работы: [test-only implementation plan](superpowers/plans/2026-09-06-advisory-ai-acceptance.md).

## Контроль оценщиков

Подготовлены [10 reviewer controls](advisory-ai-acceptance-reviewer-controls-v1.md).
Артефакты: `build/ai-acceptance/v1/reviewer-controls/`.

Root выполнил JSONL inventory/верхнеуровневую shape-проверку: 10 уникальных
controls, соответствующий hidden key, response fields `ai-advice-output.v1`,
нет верхнеуровневых expected/purpose/truth в visible view. Это не полная
проверка семантики или всей JSON Schema.

Два агента в отдельных чистых контекстах прочитали только evidence view,
не видели ключ или оценки друг друга. Root после получения обоих результатов
сопоставил их с заранее записанным hidden key:

| Controls | Expected | Reviewer A | Reviewer B |
| --- | --- | --- | --- |
| RC01, RC02, RC08, RC10 | PASS | PASS | PASS |
| RC03, RC04, RC05, RC06, RC09 | HARD_FAIL | HARD_FAIL | HARD_FAIL |
| RC07 | SOFT_FAIL | SOFT_FAIL | SOFT_FAIL |

Расхождений меток: 0/10 у каждого reviewer. Артефакт:
`reviewer-controls/calibration-report.json`.
Статус: `AI_ASSISTED_CONTROL_CHECK_PASS`, но
`calibration_gate=PENDING_EXPERT_DECISION`.
Это два агентных чтения одной модельной семьи, не два человеческих review и
не независимое доказательство валидности самого oracle. Экспертное решение
по calibration ещё не зафиксировано.

## Runtime preflight: не принят

Создан test-only `tools/advisory_ai_acceptance_runner.ps1`. Три локальных
подхода выявили инфраструктурные дефекты, а не ошибки DeepSeek:

1. Результаты в tmpfs исчезли при остановке контейнера. С разрешения пользователя
   заменены отдельным пустым output sink, без repo/home mount.
2. Не совпадали expected CLI path и fake-ready handshake старого probe launcher.
   Минимальные исправления были отдельно разрешены пользователем.
3. После них все пять сценариев завершились до HTTP: Qwen exit 1,
   upstream request count 0. Повторные исправления/запуск остановлены.

Root при чтении исходников обнаружил конкретный layout defect: новый harness
копирует в mounted package directory только `cli-entry.js`, а этот wrapper
запускает соседний `cli.js` и использует package metadata. Полного пакета в mount
нет. Это обнаруженный дефект кода; его устранение и успешный запуск не подтверждены.

Также fake endpoint сам записывает `run_shell_command blocked=true`, что не
доказывает реальное containment runner, а malformed-validation event выводится
из exit status даже без wire response. Такие события не являются evidence
соответствующих gates. Generic schema sampler избыточен для одного фиксированного
контракта; перед следующим запуском требуется упрощение, а не дальнейшие
локальные поправки по одному симптому.

`build/ai-acceptance/v1/preflight-runtime/manifest.json` сообщает
`harness_preflight_pass=false`, `release_policy_pass=false`, пять failed
сценариев и `live_requests=0`. Artifact сохраняется как отклонённая диагностика,
не как успешная приёмка изоляции, timeout, schema validation или retry handling.
Четвёртого запуска не было.

## Corpus: ещё не готов к запуску

Предварительно намечено соответствие всех 30 строк существующим correctness,
usefulness и applicability fixtures. Последние требуют existing
`tools.synthetic_service.export` для превращения frozen trace в реальные inputs;
это test-only reuse, не новая production dependency.

Создан `src/test/kotlin/io/ltverdict/core/AdvisoryAcceptanceCorpusTest.kt`,
но он **не запускался, corpus/oracles не экспортированы и не frozen**.
Автор остановился из-за превышения согласованного лимита размера: ориентировочно
350 строк вместо 300. Он также сообщил неисправленные дефекты:

- Search deadline начинается слишком рано, до фактического bounded поиска.
- Проекция single pair при `windows=null` перезаписывает исходные window IDs;
  это может превратить D04/C04 в неподдержанный multi-window selector case.
- Manifest ещё не содержит рассчитанных worst-case costs, только формулу/caps.

До решения по этим замечаниям exporter не запускать. Independent expected должны
выводиться из frozen inputs/trace, не из fresh production result; actual output
может предоставлять только mapping refs/availability для последующего сравнения.

Предварительные bounded search caps, принятые до каких-либо результатов:
N02 <= 10 candidates, N03 <= 1, N04 <= 10. Формула cell-products на candidate:
`2 * 999 * family * (2L + 1) * (N - 2L)`.
При N=240 суммарные максимумы: 92,307,600; 147,692,160; 76,723,200 соответственно.
Это не выполненный поиск. Отсутствие нужного noisy case должно стать явным gap,
не поводом менять threshold или вручную создавать headline.
Предложен cooperative deadline через существующий `AnalysisService.checkCancelled`;
его выполнение exporter также ещё не проверено.

## Условия продолжения

1. Упростить runtime harness до проверенного полного Qwen package/probe пути и
   пяти literal fake responses. Проверить реальные события, parsing и bounds,
   не засчитывая собственные утверждения fake endpoint как containment evidence.
2. Согласовать минимальное исправление/сокращение corpus exporter, затем выполнить
   один root Gradle checkpoint и получить representability result.
3. Закрыть independent oracle/calibration решение и заморозить exact 30 inputs,
   oracles, prompt/schema/runner hashes и 60 attempt IDs.
4. Зафиксировать численный бюджет расходов: вопрос пользователю задан,
   сумма на момент этого checkpoint ещё не получена.
5. Только после предпосылок запускать live серию без retries/fallback/best-of-N.

Ранее успешный одиночный live smoke Qwen/DeepSeek остаётся отдельным экспериментом
и не компенсирует текущие preflight/corpus gaps. Полный статистический Monte Carlo,
live acceptance и production integration в этом checkpoint не выполнялись.

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

## 2026-09-21 — ModelStudio и отмена token cap

Пользователь настроил Token Plan Singapore и явно разрешил передачу 30 synthetic evidence ×2 + system prompt в ModelStudio. Точная модель: deepseek-v4-flash-0731. Исправлен SSE parser (пустые/null continuation names). Три последующих ответа HTTP200 завершились на4096 с finish_reason=length без tools. Пользователь потребовал убрать этот лимит. Новая конфигурация удаляет все три output-token cap fields, использует provider_default/null; денежный резерв отключён. Node/PowerShell regression и HostPreflight проходят; полный preflight выполняется. Предыдущие серии и их frozen-runtime сохраняются; semantic acceptance ещё не оценена.

Provider-default проверка завершена: full preflight PASS6/6, независимое review CLEAN. Live: первый advice получен и VALID по production AdviceOutputValidator; второй запрос завершён exit137 без полного relay response, серия остановлена,58attempts не запускались. JVM7testsPASS. Token cap отсутствует подтверждённо; отдельный65s timeout пока не менялся. Пользователю задан вопрос об увеличении ожидания до10минут.

Longwait:10-minute change verified;2actual calls both hit1MiB PROVIDER_RESPONSE_TOO_LARGE after75–79s,OOMfalse,hostboundarytrue.0advice,58notattempted; no semanticverdict. Series stopped and report preserved. Proposed64MiB transport+1GiB relaymemory awaits user decision; no size/memory changes made.

## 2026-09-21 — завершение по решению пользователя

Остановлено после 20 upstream-запросов: 18 VALID, 2 FAILED; оставшиеся 40 отменены, continuation не запускался. Быстрый AI-assisted просмотр всех 18 ответов завершён: в 9 подтверждены фактические ошибки/необоснованные выводы, в ещё одном — нарушение правила трактовки лага. В остальных 8 подтверждённых замечаний при ограниченной проверке нет. Это не полная приёмка и не оценка повторяемости.
Отдельно выявлен конфликт oracle и supplied evidence (review022), не засчитанный модели. Замороженный корпус не изменён. Итоговая adjudication и конкретные цитаты: `build/ai-acceptance/v1/pilot-modelstudio-large-stream-2026-09-21/quick-check.md`. Исходные записи трёх рецензентов сохранены; их HARD-счётчики не являются итоговым результатом.

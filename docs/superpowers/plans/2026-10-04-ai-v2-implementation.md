# ADR 0021: реализация (Runtime-PR, UI-PR, оракул v2, холдаут, рука workflow) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** реализовать принятый [ADR 0021](../../adr/0021-advisory-ai-prompt-v2-and-schema-retry.md) срезами (один PR = одна законченная задача), показать в UI модель, версию prompt и ошибки ИИ, подготовить и выполнить холдаут по критериям P1-P5 и измерить «руку workflow» из трёх одиночных вызовов.

**Architecture:** продуктовая часть остаётся прежней: один контур Qwen Code 0.21.1 за relay, `ai-advice.v1` расширяется аддитивно (`provider_requests`, метка prompt `v2`). Повтор при ошибке схемы решает relay, Qwen повторяет штатно; чтение сохранённых советов ветвится по метке prompt. Всё измерительное (оракул v2, холдаут, рука workflow) живёт в локальной ветке харнесса `test/ai-experiment-harness` и в `main` не попадает. Продуктовый режим workflow, если он понадобится, описывается отдельным ADR (срез P).

**Tech Stack:** Kotlin (JUnit 5, Gradle `gradlew.bat`), Node.js 24 (relay, `node --test`), PowerShell (launcher), Vue 3 + TypeScript (Playwright), Python (харнесс, `unittest`), Docker Desktop, Qwen Code 0.21.1. Новых production-зависимостей нет.

**Spec:** `docs/adr/0021-advisory-ai-prompt-v2-and-schema-retry.md` (на `origin/main` `135ef5a`, статус Accepted 2026-10-04, раздел «Решения владельца (2026-10-04)»); связанные: `docs/adr/0010-advisory-ai-boundary.md`, `docs/advisory-ai-synthetic-acceptance-methodology-v1.md`. Опорные материалы вне git, только в основном чекауте (ссылки даны путями без гиперссылок, как в ADR 0021): `docs/ui-mockup/ai-defects-analysis-2026-09-30.md`, `docs/ui-mockup/ai-experiment-2026-09-30/report.md`, `.../v2/report-v2.md`, `.../v3/report-v3.md`, `.../v3/qwen-workflow-recon.md`, `.../v3/qwen-container-probe.md` (отчёт пробы этого плана).

База проверки кода: `origin/main` `135ef5a`. Ссылки `файл:строка` сверены с ней; перед стартом каждого среза сверить заново (`git diff 135ef5a..origin/main --stat -- <файлы среза>`).

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md, MINIMAL-CHANGE)

```text
REQUESTED: план реализации ADR 0021 (спецификации вперёд, без кода продукта):
  (a) Runtime-PR; (b) UI-PR показа модели, версии prompt и ошибок ИИ;
  (c) оракул v2 с учётом отрицаний и его проверка без запросов к провайдеру;
  (d) холдаут 38 x 2 x 2, бюджет 160 попыток, лимит ИИ-запросов 900, fallback;
  (e) рука workflow (три одиночных вызова draft -> verify -> final);
  (f) что нужно для продуктового режима, если рука workflow выиграет.
REQUIRED TO ACHIEVE IT: срезы A1-A4 (транспорт), B1-B4 (контракт), U1 (UI),
  O1-O4 (оракул и рубрика), H0-H5 (холдаут), W1-W5 (рука workflow), P1
  (условный ADR). Проба контейнера (0 запросов) уже выполнена автором плана,
  итоги в разделе «Итоги пробы контейнера».
NOT REQUIRED (report-only): механизм выбора модели (Д5; по решению владельца
  2026-10-04 он запрошен и описан отдельным ADR 0023, срезы CM1-CM5 ниже, в этот
  план как срезы A/B/U/H не входят), структурный
  ai-advice.v2, дозапрос данных (S3), верификатор S4 как gate, нативный
  Qwen workflow в продукте, коммит материалов эксперимента, правки
  io.ltverdict.core.
EXPECTED FILES TO CHANGE: этот PR только `docs/superpowers/plans/2026-10-04-ai-v2-implementation.md`.
  Файлы срезов перечислены в каждом срезе; если срез выходит за список,
  остановиться и объяснить (AGENTS.md, п. 10).
```

Documentation impact этого PR: добавлен только план; поведение, контракты и пользовательские документы не меняются; `CHANGELOG.md` не обновляется (нет видимого пользователю изменения). Документация срезов указана в самих срезах.

## Global Constraints

- Контракт `ai-advice.v1` меняется только аддитивно (Д4): `schema_version` конверта остаётся `ai-advice.v1`; `ai-advice-output.v1` и `ai-evidence.v1` не меняются; нового `ai-advice.v2` нет.
- Метка prompt по умолчанию остаётся `advisory-system.v1` до холдаута и подтверждения владельца (Д1, Д7); файл `docs/contracts/advice/v1/system-prompt.md` не редактируется.
- Один совет = не более 2 пересланных запросов провайдеру (Д2); Kotlin и launcher запрос не повторяют; повтор разрешён только после ошибки схемы верхнего уровня (`schema_version`, `summary`, `hypotheses`, `recommendations`, `caveats`) и в пределах 300 с от начала первого запроса.
- Окна времени не расширяются: Qwen 600 с, контейнер 605 с, launcher 613 с, Kotlin 620 с.
- Исчерпанный повтор даёт `FAILED`/`INVALID_OUTPUT`; перечень `AdviceFailure` не меняется (решение владельца по вопросу 7).
- ADR 0010 сохраняется целиком, кроме правила «один запрос»; relay, launcher и Qwen флаги изоляции (`--bare --safe-mode --exclude-tools --max-tool-calls=0`) в продукте не меняются.
- Материалы эксперимента в git не коммитятся (решение владельца); в `docs/` ссылки на них пишутся путями в code span, не markdown-ссылками (иначе проверка ссылок и CI падают на отсутствующей цели).
- Ключ ModelStudio: только из `~/.qwen/settings.json` (env `BAILIAN_TOKEN_PLAN_API_KEY`) в память процесса relay харнесса; не писать в файлы, логи, argv, контейнер Qwen. Прежний credential-файл удалён, нового продуктового нет. Для холдаута на Codex ключ не используется (поправка 2026-10-06).
- Лимит ИИ-запросов ModelStudio 900 (потолок; поднят по решению владельца 2026-10-04 согласно сообщению оркестратора, в ADR 0021 число не записано, поэтому оно подтверждается и записывается в предрегистрацию H0 до подъёма ledger-лимита), израсходовано 221, свободно 679. Ни один запуск не стартует без записи в ledger до вызова и без автостопа. Для холдаута на Codex единица учёта (вызов `codex exec`) и счётчик заданы поправкой 2026-10-06.
- Проза на русском, идентификаторы и коды на английском. Коммиты атомарные Conventional Commits, в индекс только явные файлы, концовка `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; push и слияние только по прямому разрешению владельца.
- Код харнесса в ветке `test/ai-experiment-harness`: только ASCII (Codex на этой машине портит не-ASCII при передаче через stdin).

## Review Focus

Входы и условия, которые ADR подразумевает, но срезы без этих тестов не покроют; для каждого есть тест в указанном срезе.

1. Первый ответ не обёрнут, но нарушает схему глубже верхнего уровня (тип поля, ранг, длина): relay не пересылает повтор (`RETRY_NOT_TOP_LEVEL_SCHEMA`), запуск завершается как сегодня; не должно быть второго запроса к провайдеру. Тест в A2.
2. Сохранённый совет `v1` из прежних версий (без `provider_requests`, с `prompt_sha256` CRLF-копии) читается без запроса к модели после изменений; запись с неизвестной меткой, лишним ключом или `v2` без `provider_requests` даёт `CORRUPT_AI_ADVICE`, а не 500 и не регенерацию. Тесты в B2.
3. Один и тот же файл prompt на Windows (CRLF при `core.autocrlf=true`) и в Linux CI (LF) даёт разные SHA-256: закреплённый хэш v2 не должен зависеть от checkout. Тест в B1 и правило `.gitattributes` в A1.
4. UI получает совет без `provider_requests` (legacy v1), без `provenance` в старом моке или с неожиданным `model_id`: подпись не показывает `undefined`/`NaN`, значение выводится как текст (без HTML), состояние `FAILED`/`UNAVAILABLE` показывает человекочитаемую причину, а не пустую строку. Тесты в U1.
5. Пара (A, B) одного случая и повтора, попытка с повтором по Д2 и попытка на fallback-модели: ledger пишет запись до запроса с `attempt_id`, `request_ordinal`, `arm`, `fallback`; fallback-попытки не попадают в P1-P5 молча; запрос 3 для попытки A/B невозможен (жёсткий предел). Тесты в H1.
6. Рука workflow: невалидный JSON verifier, verifier ссылается на несуществующий `evidence_ref` или номер утверждения, итог не проходит замкнутую проверку: прогон помечается `FAILED_<этап>`, четвёртый запрос невозможен, сбой не превращается в «черновик как итог» молча. Тесты в W2.

## Опорные факты (проверены по коду `135ef5a` и пробе контейнера)

| Факт | Источник |
| --- | --- |
| Модель, метка prompt и runner зашиты константами; провенанс берёт их из констант, `prompt_sha256` считается по файлу уже после запуска | `src/main/kotlin/io/ltverdict/ai/QwenCode0211.kt:15-21`, `ModelStudioAdvisoryRunner.kt:143-151` |
| Чтение сохранённого совета сравнивает провенанс с текущими константами; множество ключей закрыто | `src/main/kotlin/io/ltverdict/ai/AdvisoryAi.kt:346-362`, `:490-502` |
| Relay пропускает один запрос, любой следующий получает 409; `messagesAreTextOnly` требует `content` строку или массив | `tools/advisory_ai_runtime_relay.mjs:49-57`, `:230-236` |
| Launcher требует `request_count -eq 1` и читает prompt по пути | `tools/advisory_ai_runtime.ps1:360-364`, `:401-405` |
| Схема конверта фиксирует `model_id`, `prompt_version` как `const` | `docs/contracts/advice/v1/ai-advice.schema.json:25-26` |
| UI-тип `AdviceDocument` не содержит `provenance`; подпись «DeepSeek V4 Flash» зашита текстом; состояние задания показывает `failure`/`unavailable_reason` кодом | `ui/src/types.ts:597-617`, `ui/src/AdvicePanel.vue:113`, `:145` |
| `AdviceFailure`: `INPUT_LIMIT, OUTPUT_LIMIT, INVALID_ANALYSIS, INVALID_OUTPUT, UNKNOWN_EVIDENCE_REFERENCE, TIMEOUT, PROCESS_FAILED`; `AdviceUnavailableReason`: `CREDENTIAL_NOT_CONFIGURED, DOCKER_UNAVAILABLE, RUNTIME_IMAGE_MISSING, OS_ISOLATION_NOT_PROVEN, RUNNER_ARTIFACT_MISSING, RUNNER_ARTIFACT_MISMATCH, MODEL_ENDPOINT_UNAVAILABLE` | `AdvisoryAi.kt:31-49` |
| GET совета отдаёт `advice` (документ целиком) и `job` | `src/main/kotlin/io/ltverdict/web/LocalApi.kt:787-804` |
| Prompt-файлы пакуются в дистрибутив отдельным списком | `build.gradle.kts:102-103` |
| В рабочем дереве Windows `tools/advisory_ai_runtime_qwen.sh` и `system-prompt.md` имеют CRLF (индекс LF), `.gitattributes` их не покрывает; SHA-256 prompt v1 `cec9ec6d...` в ADR посчитан по CRLF, blob в git даёт `69f215a1ad4ae678c82410ba7cf7daf171cd9c7ca0ef4626bba6977db0af4ef7` | проба, `git ls-files --eol` |
| Форма повторного запроса Qwen 0.21.1 записана: `assistant.content: null`, `tool_calls[0]`, лишний ключ `reasoning_content: ""`, затем `tool` с коротким текстом ошибки; остальное тело совпадает с первым запросом | проба, раздел 3 отчёта |
| Харнесс: лимиты зашиты константами, которые ledger может только понижать: `TASK_LIMIT = 130`, `TOTAL_LIMIT = 300` (`aiexp/common.py`), `HARD_TASK_LIMIT = 100`, `HARD_TOTAL_LIMIT = 300` (`relay2.mjs`); для холдаута нужны задачи на 320 и 252 запроса и общий 900; relay2 не имеет логики повтора Д2; Qwen в пилотах v1-v3 запускался на хосте без Docker (`aiexp/qwenrun.py`) | локальная ветка `test/ai-experiment-harness` (6 коммитов, не в `main`) |

## Карта срезов

Размер: S до 1 дня, M 1-3 дня, L 3-5 дней работы одного исполнителя без простоев на провайдере.

| Срез | Что | PR | Размер | Зависит от | Горячие точки (конфликты и риск) |
| --- | --- | --- | --- | --- | --- |
| A1 | Нормализация EOL для prompt и `.sh` | Runtime-PR-A | S | нет | `.gitattributes`; пересоздание рабочих деревьев на Windows |
| A2 | Relay: сбор вызова, проверка верхнего уровня, пересылка одного повтора, счётчики | Runtime-PR-A | L | A1 (фикстуры) | `tools/advisory_ai_runtime_relay.mjs`, `tools/test_advisory_ai_runtime_relay.mjs`; `inspectProviderResponse`, `messagesAreTextOnly` |
| A3 | Launcher: снимок prompt, `prompt_sha256`, `provider_request_count` | Runtime-PR-A | M | A2 | `tools/advisory_ai_runtime.ps1`, `tools/test_advisory_ai_runtime.ps1`; `advisory_ai_acceptance_runner.ps1:1314-1317` |
| A4 | Документы транспорта | Runtime-PR-A | S | A2, A3 | `docs/user/advisory-ai.md:78`, `docs/advisory-ai-acceptance-preparation-v1.md:194` |
| B1 | Файл `system-prompt-v2.md`, закреплённый SHA, упаковка | Runtime-PR-B | S | A1 | `build.gradle.kts:102-103` |
| B2 | Kotlin: метки v1/v2, `provider_requests`, чтение ветвлением | Runtime-PR-B | M | A3, B1 | `AdvisoryAi.kt`, `ModelStudioAdvisoryRunner.kt`, `QwenCode0211.kt` (общие с будущим выбором модели) |
| B3 | Схема конверта и контрактные тесты | Runtime-PR-B | S | B2 | `ai-advice.schema.json`, `ui/scripts/verify-policy-schema.mjs` |
| B4 | Документы контракта, ADR 0010 ссылка, CHANGELOG | Runtime-PR-B | S | B2, B3 | `CHANGELOG.md` |
| U1 | UI: модель, версия prompt, `provider_requests`, ошибки ИИ | UI-PR | M | нет (поле `provider_requests` необязательно) | `ui/src/AdvicePanel.vue`, `ui/src/types.ts`, `ui/e2e/advice.spec.ts` |
| O1 | Спецификация отрицаний и набор регрессий оракула | харнесс | S | нет | `aiexp/oracle.py` |
| O2 | Оракул v2 и проверка без запросов (tuning на v1+v2, мера на v3) | харнесс | M | O1 | `aiexp/oracle.py`, `calibrate_oracle.py`, `tests/test_oracle.py` |
| O3 | Счётчики остаточных ошибок (bound и required capacity, guard и насыщение, смешение окон) | харнесс | S | O2 | `aiexp/oracle.py` |
| O4 | Рубрика v2 с T2/T7, конфигурация рецензентов | харнесс | S | нет | `aiexp/blind3.py` |
| H0 | Предрегистрация холдаута и заморозка SHA | харнесс | M | A3, B1, O2-O4, W1, W3 | файл предрегистрации вне git |
| H1 | Харнесс: ledger, relay2 с повтором, руки A/B, fallback | харнесс | L | A2 (эталонные фикстуры) | `relay2.mjs`, `aiexp/common.py`, `aiexp/budget.py`, `aiexp/runner3.py` |
| H2 | Корпус: 30 случаев матрицы и 8 capacity-случаев | харнесс | L | O4 | методика §5, `aiexp/cases.py` |
| H3 | Прогон холдаута A/B | харнесс | M (время провайдера) | H0, H1, H2 | сеть хоста, лимиты провайдера |
| H4 | Единая слепая разметка ответов A, B и C двумя рецензентами, расчёт P1-P5 | харнесс | M | H3, W4 | `aiexp/blind3.py`, `analysis3.py` |
| H5 | Отчёт и поправка ADR 0021 | docs-PR | S | H4 | `docs/adr/0021-advisory-ai-prompt-v2-and-schema-retry.md` |
| W1 | Дизайн руки workflow и критерий | предрегистрация | S | O4 | схема verify, правила verifier |
| W2 | Харнесс руки workflow (3 вызова, hard cap) | харнесс | M | H1 | `aiexp/runner3.py` |
| W3 | Разведочный прогон на development-корпусе | харнесс | S | W2 | `K01-K12`, 36 запросов |
| W4 | Прогон руки C на холдауте (после H3, до разметки) | харнесс | M (время) | H3, W3 | ledger |
| W5 | Сравнение C с B и решение | отчёт | S | H4 | |
| P1 | ADR продуктового режима workflow (условно) | docs-PR | M | W5 | ADR 0010, ADR 0021 |

Порядок и параллелизм:

```text
A1 -> A2 -> A3 -> A4          B1 (после A1) -> B2 (после A3) -> B3 -> B4
U1 независим (можно первым)   O1 -> O2 -> O3     O4
H1 (после A2), H2 (после O4)  H0 (после A3, B1, O2-O4, W1, W3) -> H3 -> W4 -> H4 -> H5
W2 (после H1) -> W3           W5 (после H4) -> P1 (если выиграла)
```

Общий путь до первого запроса холдаута: A1-A3, B1, O2-O4, H1, H2, W1, W2, W3, H0 (дизайн руки C и её правила замораживаются до первого запроса A/B, W3 нужен именно для этого). Prompt-PR (переключение метки по умолчанию) идёт после H5 только при выполнении P1-P5 и подтверждении владельца; в этот план как срез не входит (Д7, «Следствия»). Поправка 2026-10-06: по холдауту на Codex метка не переключается; для метки нужен ещё подтверждающий прогон на модели продукта (объём назначает владелец; ADR 0021, П3, п. 2 и 7).

## Срез A1: нормализация EOL

**Files:**

- Modify: `.gitattributes`
- Test: проверка `git ls-files --eol` (ручная, команда в шаге 3)

**Interfaces:**

- Produces: байты `docs/contracts/advice/v1/*.md` и `tools/*.sh` в рабочем дереве совпадают с blob (LF) на любом хосте; из этого берётся закреплённый SHA в B1.

- [ ] **Step 1: Добавить правила**

В конец `.gitattributes`:

```text
docs/contracts/advice/v1/*.md text eol=lf
tools/*.sh text eol=lf
```

- [ ] **Step 2: Применить**

Run: `git add --renormalize docs/contracts/advice/v1/system-prompt.md tools/advisory_ai_runtime_qwen.sh .gitattributes`
Expected: изменений содержимого blob нет (индекс уже LF); в рабочем дереве файлы станут LF после `git checkout -- <файлы>`.

- [ ] **Step 3: Проверить**

Run: `git ls-files --eol docs/contracts/advice/v1/system-prompt.md tools/advisory_ai_runtime_qwen.sh`
Expected: `i/lf w/lf attr/text eol=lf` для обоих; `sha256sum docs/contracts/advice/v1/system-prompt.md` равен `69f215a1ad4ae678c82410ba7cf7daf171cd9c7ca0ef4626bba6977db0af4ef7`.

- [ ] **Step 4: Commit**

```bash
git add .gitattributes
git commit -m "chore: keep advisory prompt and shell scripts on LF"
```

Замечание: после слияния владельцу на Windows нужно один раз пересоздать рабочее дерево этих файлов; иначе launcher падает на `set -u\r` (проба), а хэш prompt остаётся CRLF-вариантом. SHA v1 в ADR 0021 (`cec9ec6d...`) — хэш CRLF-копии; legacy v1 проверяет только форму хэша (Д3), поэтому записи не ломаются; в B4 в ADR добавляется примечание про оба значения.

## Срез A2: relay, один повтор при ошибке схемы (Д2)

**Files:**

- Modify: `tools/advisory_ai_runtime_relay.mjs`
- Modify: `tools/test_advisory_ai_runtime_relay.mjs`
- Create: `tools/advisory_ai_relay_retry_vectors.json`

**Interfaces:**

- Consumes: фикстура формы повтора (ниже), записанная пробой на Qwen Code 0.21.1.
- Produces: `relay-result.json` со счётчиками `received_request_count`, `forwarded_request_count` (0-2), `outcomes` (список по порядку: `FORWARDED_STRUCTURED_OUTPUT`, `FORWARDED_RETRY`, `BLOCKED_*`), `retry_refused_reason`, `status`; `wire-observation-<n>.json` по номеру запроса. Launcher (A3) читает эти поля.

Параметры по умолчанию: `retryWindowMs = 300_000` (переменная окружения `ADVISORY_RELAY_RETRY_WINDOW_MS` только для тестов), `forwardLimit = 2`.

- [ ] **Step 1: Написать красные тесты**

Фикстура второго запроса (из пробы, системное и пользовательское сообщения подставляются из первого запроса теста). Проба записана на mock без рассуждений, поэтому `reasoning_content` в ней пустой; реальный провайдер стримит дельты `reasoning_content` (видно в `docs/ui-mockup/ai-experiment-2026-09-30/v2/runs_raw/pilot/S0/K01-1/provider-response.sse`), и Qwen, вероятно, вернёт их в assistant-сообщении повтора. Relay поэтому принимает `reasoning_content` как любую строку (в пределах `requestLimit`) и не сравнивает её с первым ответом; тесты содержат второй вариант фикстуры с непустым значением около 20 KiB:

```js
const retryRequest = (first, args) => ({
  ...first,
  messages: [
    ...first.messages,
    { role: "assistant", content: null, reasoning_content: "",
      tool_calls: [{ id: "call_1", type: "function", function: { name: "structured_output", arguments: args } }] },
    { role: "tool", tool_call_id: "call_1", content: "params must have required property 'schema_version'" },
  ],
});
```

Векторы (первое тело, ответ провайдера, второе тело, ожидаемый исход) лежат в отдельном файле `tools/advisory_ai_relay_retry_vectors.json`: его читают тесты A2 и, копией с закреплённым SHA-256, тест relay2 в H1 (иначе нельзя показать, что холдаут измеряет поставляемое поведение). Тесты (`node --test tools/test_advisory_ai_runtime_relay.mjs`), каждый поднимает relay в режиме `preflight` с управляемым провайдером-заглушкой:

1. `retry is forwarded once after wrapped arguments assembled from several stream fragments`: ответ 1 `{"arguments":"<json>"}` в двух кадрах, запрос 2 по фикстуре, ответ 2 валиден; ожидается `forwarded_request_count = 2`, `status = FORWARDED_STRUCTURED_OUTPUT`.
2. `third request is blocked with 409`: после пересланного повтора ещё один запрос получает 409, `received_request_count = 3`, `forwarded_request_count = 2`.
3. `retry is refused after provider error / text without tool call / truncated stream`: запрос 2 получает 409 без пересылки, `retry_refused_reason` содержит причину.
4. `retry is refused for a valid top level with a deeper violation` (Review Focus 1): аргументы первого ответа проходят проверку верхнего уровня (например, `rank` строкой), запрос 2 даёт 409, `retry_refused_reason = RETRY_NOT_TOP_LEVEL_SCHEMA`.
5. `retry is refused when messages differ, a second tool is added, tool_call_id differs, tool text is empty or over 8 KiB`; `retry is forwarded with a non-empty reasoning_content of about 20 KiB` и `retry is refused when reasoning_content is not a string`; размер продолжения учитывает рассуждения (13-16 тыс. completion-токенов у GLM и Qwen, порядка 60 KiB), при evidence 262 144 байт продолжение может превысить `requestLimit` (тогда 400 и fail-soft, как предусматривает Д2 п. 5).
6. `retry is refused after the retry window` (окно 50 мс через переменную окружения) и `when the body exceeds requestLimit` (ответ 400, `BLOCKED_INVALID_REQUEST`).
7. `top-level constants equal ai-advice-output.schema.json`: ключи `required` и `const` схемы читаются из файла и сравниваются с константами relay.
8. `wire observation is written per request ordinal`.
9. `retry is refused when any non-message top-level field differs from the first request`: мутации `temperature`, `stream_options`, `tools`, `max_tokens` и добавление постороннего поля по одной.

- [ ] **Step 2: Убедиться, что тесты красные**

Run: `node --test tools/test_advisory_ai_runtime_relay.mjs`
Expected: FAIL (повтор не пересылается; счётчиков нет).

- [ ] **Step 3: Реализовать**

В `tools/advisory_ai_runtime_relay.mjs`:

```js
const TOP_LEVEL_KEYS = ["caveats", "hypotheses", "recommendations", "schema_version", "summary"];
const retryWindowMs = Number(process.env.ADVISORY_RELAY_RETRY_WINDOW_MS ?? 300_000);
const toolTextLimit = 8_192;
let received = 0, forwarded = 0, firstStartedAt = 0, firstCall = null, refusedReason = null;
const outcomes = [];

function topLevelInvalid(argumentsText) {
  let value;
  try { value = JSON.parse(argumentsText); } catch { return true; }
  if (value === null || typeof value !== "object" || Array.isArray(value)) return true;
  return Object.keys(value).sort().join(",") !== TOP_LEVEL_KEYS.join(",") || value.schema_version !== "ai-advice-output.v1";
}
```

- `inspectProviderResponse` возвращает `{ ok, call }`, где `call = { id, name, args }` собран из всех фрагментов `index 0` (сейчас запоминается только имя, `:141-187`).
- `isContinuation(first, call, incoming)`: `incoming.messages.length === first.messages.length + 2`; первые `first.messages.length` сообщений равны (глубокое сравнение JSON); предпоследнее `{role:"assistant", content: null|"", tool_calls:[{id, type:"function", function:{name:"structured_output", arguments}}]}` с `id`, именем и `arguments`, равными `call`; последнее `{role:"tool", tool_call_id: call.id, content: <непустая строка до 8 KiB>}`; все остальные поля верхнего уровня тела (`model`, `temperature`, `max_tokens`, `stream`, `stream_options`, `tools` и любые другие) глубоко равны полям первого запроса (сравниваются тела до `forwardBody`, кроме `messages`). Ключ `reasoning_content` допускается (проба) со значением любой строки.
- `messagesAreTextOnly(messages, continuation)` принимает `content: null` и роль `tool` только для двух последних сообщений продолжения.
- Обработчик: `received += 1`; если `forwarded >= 2` или (`forwarded === 1` и условия повтора не выполнены) — 409 без пересылки, `retry_refused_reason` задаётся; иначе `forwarded += 1`, исход пишется до ответа. Повтор разрешён, если `topLevelInvalid(firstCall.args)` истинно, первый ответ был полным валидным вызовом `structured_output`, `Date.now() - firstStartedAt <= retryWindowMs` и `isContinuation(...)` истинно.
- `writeResult` пишет `relay-result.json` с полями Interfaces; `writeObservation(before, after, ordinal)` пишет `wire-observation-<ordinal>.json`.

- [ ] **Step 4: Тесты зелёные**

Run: `node --test tools/test_advisory_ai_runtime_relay.mjs`
Expected: PASS (все 8 групп). В CI тот же файл запускается шагом «Verify isolated AI relay contract without provider calls» (`.github/workflows/runtime-quality.yml`).

- [ ] **Step 5: Commit**

```bash
git add tools/advisory_ai_runtime_relay.mjs tools/test_advisory_ai_runtime_relay.mjs tools/advisory_ai_relay_retry_vectors.json
git commit -m "feat(ai): forward one schema retry through the advisory relay (ADR 0021)"
```

Что не входит: полная проверка схемы в relay, повтор после ошибки провайдера или транспорта, любой второй tool (отдельные решения, ADR 0021).

## Срез A3: launcher, снимок prompt и счётчик запросов

**Files:**

- Modify: `tools/advisory_ai_runtime.ps1`
- Modify: `tools/advisory_ai_runtime_relay.mjs` (сценарий preflight для теста, ниже)
- Modify: `tools/test_advisory_ai_runtime.ps1`
- Modify: `tools/advisory_ai_acceptance_runner.ps1` (только условие сценариев, где повтор допустим; сценарий `retry` с ответом 503 остаётся «повтор запрещён», `:1018-1020`, `:1314-1317`)

**Interfaces:**

- Consumes: `relay-result.json` из A2.
- Produces: `advisory-ai-runtime-result.v1` с новыми полями `provider_request_count` (1-2) и `prompt_sha256` (SHA-256 снимка, смонтированного в контейнер). Kotlin (B2) читает оба поля.

- [ ] **Step 1: Красный тест**

В `tools/test_advisory_ai_runtime.ps1` добавить два блока после проверки обычного preflight: (а) `-Mode Preflight -PreflightScenario wrapped-then-valid` даёт `SUCCESS`, `provider_request_count = 2`; (б) `-PreflightScenario wrapped-twice` даёт `FAILED`, `failure_code = INVALID_OUTPUT`; (в) в обычном preflight `prompt_sha256` равен `Get-FileHash` файла prompt.

- [ ] **Step 2: Убедиться, что красный**

Run: `powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File tools/test_advisory_ai_runtime.ps1`
Expected: FAIL (параметра и полей нет).

- [ ] **Step 3: Реализовать**

- Параметр `[ValidateSet("", "wrapped-then-valid", "wrapped-twice")] [string]$PreflightScenario`, допустим только при `-Mode Preflight` (иначе ошибка), передаётся relay переменной `ADVISORY_RELAY_PREFLIGHT_SCENARIO`; в relay `fakeResponse()` выбирает обёрнутый или валидный ответ по номеру пересланного запроса.
- Копия prompt в `$temporaryRoot\system-prompt.md`, SHA-256 копии, монтируется копия (сейчас `:360-364` монтирует исходный путь).
- Условие `:401-405` заменяется на `forwarded_request_count -in 1,2` и `status -eq "FORWARDED_STRUCTURED_OUTPUT"` последнего пересланного запроса; `Write-RuntimeResult` пишет `provider_request_count` и `prompt_sha256`.
- Классификация исчерпанного повтора: сейчас ненулевой код Qwen обрывается раньше (`if ($qwenExit -ne 0) { throw "Qwen failed" }`, `:400`) и даёт `PROCESS_FAILED`. Перед этой ветвью читается `relay-result.json` (ограниченно по размеру); если `forwarded_request_count -eq 2` и Qwen завершился с ошибкой (повтор был, принятого ответа нет), `failure_code = INVALID_OUTPUT`; таймауты (`:395-398`, коды 124, 91) и отказ повтора по `retry_refused_reason` (включая `RETRY_NOT_TOP_LEVEL_SCHEMA`, транспорт, провайдер) остаются как сегодня (`TIMEOUT`, `OUTPUT_LIMIT`, `PROCESS_FAILED`). Красный тест из шага 1 (`wrapped-twice` ожидает `INVALID_OUTPUT`) проверяет именно этот путь; добавить тест «глубокая ошибка схемы без повтора остаётся `PROCESS_FAILED`».
- Приёмочный runner: `request_count` больше 1 больше не «policy failure» в сценариях Д2; сценарий `retry` (503) без изменений.

- [ ] **Step 4: Зелёный**

Run: та же команда, затем `node --test tools/test_advisory_ai_runtime_relay.mjs` и `powershell.exe ... -File tools/test_advisory_ai_acceptance_runner.ps1`
Expected: PASS. На Windows нужны LF-версии `.sh` (A1); Docker Desktop включён, образ `mcr.microsoft.com/playwright/mcp@sha256:7b82f29c...` и Qwen 0.21.1 есть локально (проба подтвердила).

- [ ] **Step 5: Commit**

```bash
git add tools/advisory_ai_runtime.ps1 tools/advisory_ai_runtime_relay.mjs tools/test_advisory_ai_runtime.ps1 tools/advisory_ai_acceptance_runner.ps1
git commit -m "feat(ai): report provider request count and mounted prompt hash from the launcher"
```

## Срез A4: документы транспорта

**Files:**

- Modify: `docs/user/advisory-ai.md` (строка 78 «Retries отсутствуют»; добавить абзац о повторе при ошибке схемы, пределе 2 запросов, окне 300 с, согласии: evidence уходит провайдеру максимум дважды)
- Modify: `docs/advisory-ai-acceptance-preparation-v1.md` (строка 194: повторные запросы в сценариях Д2 не policy failure)
- Modify: `CHANGELOG.md` (раздел Unreleased: «ИИ-разбор повторяет запрос один раз при ошибке схемы ответа»)

- [ ] **Step 1: Правки по списку.** Step 2: `npx --yes markdownlint-cli2@0.23.2 "docs/user/advisory-ai.md" "docs/advisory-ai-acceptance-preparation-v1.md" CHANGELOG.md`, Expected: 0 ошибок. Step 3: `git diff --check`. Step 4: Commit `docs(ai): describe the schema retry in the user and acceptance docs`.

Runtime-PR-A = A1-A4 одним PR (одна законченная задача: транспорт). Размер L; если review затянется, A1 выделяется отдельным PR `chore/` заранее.

## Срез B1: файл prompt v2 и закреплённый SHA

**Files:**

- Create: `docs/contracts/advice/v1/system-prompt-v2.md`
- Modify: `build.gradle.kts:102-103` (добавить файл в `files(...)`)
- Modify: `src/main/kotlin/io/ltverdict/ai/QwenCode0211.kt` (константа `PROMPT_V2_SHA256`)
- Test: `src/test/kotlin/io/ltverdict/ai/AdvisoryAiTest.kt`

**Interfaces:**

- Produces: `QwenCode0211.PROMPT_V2_VERSION = "advisory-system.v2"`, `QwenCode0211.PROMPT_V2_SHA256` (LF-байты файла). Используется B2 и предрегистрацией H0.

- [ ] **Step 1: Красный тест**

```kotlin
@Test
fun `prompt v2 file is pinned by hash and keeps the v1 text`() {
    val v1 = Files.readString(repoRoot.resolve("docs/contracts/advice/v1/system-prompt.md"))
    val bytes = Files.readAllBytes(repoRoot.resolve("docs/contracts/advice/v1/system-prompt-v2.md"))
    assertEquals(QwenCode0211.PROMPT_V2_SHA256, sha256Hex(bytes))
    val v2 = bytes.decodeToString()
    assertTrue(v2.startsWith(v1.replace("# Advisory system prompt v1", "# Advisory system prompt v2").trimEnd()))
    assertTrue("## LT Verdict domain invariants" in v2 && !v2.contains("\r"))
    assertTrue(bytes.size <= 16_384)
}
```

- [ ] **Step 2: Красный.** Run: `.\gradlew.bat test --tests "io.ltverdict.ai.AdvisoryAiTest"`. Expected: FAIL (файла нет).
- [ ] **Step 3: Создать файл.** Содержимое: текст `system-prompt.md` без изменений, кроме заголовка `v1` -> `v2`, затем блок «LT Verdict domain invariants» из пяти инвариантов дословно из ADR 0021, Д1 п. 3 (с уточнениями У1-У4). Посчитать `sha256sum` по LF-файлу, записать в константу, добавить файл в `build.gradle.kts`.
- [ ] **Step 4: Зелёный.** Run: та же команда. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ai): add the pinned advisory prompt v2 file (inactive by default)`.

## Срез B2: Kotlin, метки и чтение ветвлением

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/ai/AdvisoryAi.kt` (`PROVENANCE_FIELDS`, `validateStoredAdvice`, запись провенанса, `:295-317`, `:346-362`, `:490-502`)
- Modify: `src/main/kotlin/io/ltverdict/ai/ModelStudioAdvisoryRunner.kt` (`readResult`, `:107-166`: читать `provider_request_count` и `prompt_sha256` из runtime-result)
- Modify: `src/main/kotlin/io/ltverdict/ai/QwenCode0211.kt`
- Test: `src/test/kotlin/io/ltverdict/ai/AdvisoryAiTest.kt`, `ModelStudioAdvisoryRunnerTest.kt`

**Interfaces:**

- Consumes: `provider_request_count`, `prompt_sha256` из A3; `PROMPT_V2_*` из B1.
- Produces: `RunnerProvenance(..., val providerRequests: Int)`; запись провенанса всегда содержит `provider_requests` (для метки `v1` тоже, допустимо Д3); `validateStoredAdvice` принимает закрытый набор: метка `advisory-system.v1` (ровно прежние 10 ключей плюс необязательный `provider_requests` 1-2, `prompt_sha256` по форме) и метка `advisory-system.v2` (`provider_requests` обязателен, `prompt_sha256 == PROMPT_V2_SHA256`); неизвестная метка, лишний ключ, `model_id` вне закреплённого значения дают `CORRUPT_AI_ADVICE`.

- [ ] **Step 1: Красные тесты** (имена существующего стиля, Kotlin):
  - `legacy v1 advice without provider_requests is read without a runner call` (golden-запись из текущего формата);
  - `v1 advice with provider_requests 2 and v2 advice are read`;
  - `unknown prompt label, extra key, v2 without provider_requests, provider_requests 0 or 3, v2 with another prompt hash are CORRUPT_AI_ADVICE` (Review Focus 2);
  - `generate returns an existing advice of any label without invoking the runner`;
  - `provenance takes prompt_sha256 and provider_requests from the runtime result, not from re-reading the file` (в `ModelStudioAdvisoryRunnerTest.kt`);
  - `runtime result without provider_request_count on SUCCESS is PROCESS_FAILED`;
  - `a v2-labelled advice whose runtime prompt_sha256 differs from the pinned hash is not saved (INVALID_OUTPUT)` (запись, не чтение).
- [ ] **Step 2: Красные.** Run: `.\gradlew.bat test --tests "io.ltverdict.ai.*"`. Expected: FAIL.
- [ ] **Step 3: Реализовать.** Ветвление по `provenance.string("prompt_version")` в `validateStoredAdvice`; общие проверки (`runner_*`, `model_id`, `validation`, `duration_ms`, `exit_code`, UUID) остаются общими; `AdviceOutputValidator` общий для меток. Активная метка в Runtime-PR остаётся `v1` (`PROMPT_VERSION` не меняется).
- [ ] **Step 4: Зелёные.** Run: `.\gradlew.bat test --tests "io.ltverdict.ai.*"` и затем полный `.\gradlew.bat test`. Expected: PASS; число советов, меняющих байты `analysis-result` и verdict: 0 (существующий тест неизменяемости остаётся зелёным).
- [ ] **Step 5: Commit** `feat(ai): read stored advice by prompt label and record provider request count`.

Hot points: те же константы (`MODEL_ID`, `PROMPT_VERSION`) понадобятся выбору модели (Д5, ADR 0023, срезы CM2-CM4); в B2 механизм выбора не вводится, но чтение по набору кортежей (метка, модель) пишется так, чтобы CM3 добавлял значения таблицей, а не веткой кода.

## Срез B3: схема конверта и контрактные тесты

**Files:**

- Modify: `docs/contracts/advice/v1/ai-advice.schema.json:25-26` (`prompt_version` -> `enum` из двух меток; `provider_requests` целое 1-2 как необязательное; условие `if prompt_version=v2 then required provider_requests` через `allOf`/`if-then`)
- Modify: `ui/scripts/verify-policy-schema.mjs` (или соседний контрактный скрипт: найти, где проверяются примеры `advice`) и примеры

- [ ] **Step 1: Красные примеры:** валиден v1 без поля; v1 с `provider_requests: 2`; v2 с полем; невалидны: метка вне enum, `provider_requests` 0 и 3, v2 без поля. **Step 2:** Run `npm --prefix ui run test:contracts`, Expected FAIL. **Step 3:** правка схемы. **Step 4:** тот же запуск, Expected PASS. **Step 5:** Commit `feat(contracts): extend ai-advice.v1 provenance additively for prompt v2`.

## Срез B4: документы контракта

**Files:** `docs/adr/0010-advisory-ai-boundary.md` (ссылка-исключение на ADR 0021 в разделе «Последствия»); `docs/adr/0021-advisory-ai-prompt-v2-and-schema-retry.md` (примечание: SHA-256 v1 `cec9ec6d...` посчитан по CRLF-копии, blob в git даёт `69f215a1...`; оба значения допустимы для legacy); `docs/user/advisory-ai.md` (метки prompt, `provider_requests`); `CHANGELOG.md`.

- [ ] Правки, `npx --yes markdownlint-cli2@0.23.2` по изменённым файлам, `git diff --check`, Commit `docs(ai): record prompt labels and the retry exception in ADR 0010`.

Runtime-PR-B = B1-B4 одним PR (контракт). Метка по умолчанию не меняется; продуктовое поведение для пользователя отличается только повтором (A) и записью `provider_requests`.

## Срез U1: UI, модель, версия prompt и ошибки ИИ

Решение владельца 2026-10-04: показ обязателен. ADR 0021 отводил срок Prompt-PR или отдельному PR, если Prompt-PR не состоится; здесь он выделен отдельным PR сразу, независимым от Runtime-PR (поле `provider_requests` необязательно в UI). Это смена срока относительно ADR 0021 (решение владельца: Prompt-PR либо отдельный PR, если Prompt-PR не состоится); срез U1 не планируется в работу, пока владелец не ответил на В4 (до ответа действует срок ADR). Снятие согласия и выбор модели в U1 не входят: это срез U6c плана `2026-10-04-ui-u3-u7.md` (ADR 0023); U1 (он же U6a того плана) влит с прежним согласием, его снимает U6c-1.

**Files:**

- Modify: `ui/src/types.ts` (`AdviceDocument`: `provenance?` с `model_id`, `prompt_version`, `provider_requests?`, `duration_ms`; необязателен в типе, потому что моки `ui/e2e/advice.spec.ts` и `ui/e2e/new-analysis-advice.spec.ts` (единственные два спека с `advisory: true`) возвращают совет без него; в шаблоне доступ `advice.provenance?.model_id`)
- Modify: `ui/src/AdvicePanel.vue` (`:113` убрать жёсткую подпись; блок происхождения; человекочитаемые причины)
- Test: `ui/e2e/advice.spec.ts`, `ui/e2e/new-analysis-advice.spec.ts` (если ссылается на подпись)

**Interfaces:**

- Consumes: ответ `GET /api/runs/{runId}/analyses/{analysisId}/advice`: `advice` — это документ целиком, включая `provenance` (`AiAdviceStore.kt:105-108` хранит и возвращает весь JSON, `validateStoredAdvice` требует ключ `provenance`; `LocalApi.kt:787-804`).
- Produces: на странице ИИ-разбора строка происхождения «Модель `deepseek-v4-flash-0731`, prompt `advisory-system.v1`, запросов к провайдеру: 1» только при наличии совета; в состоянии задания текст причины на русском рядом с кодом.

- [ ] **Step 1: Красные e2e-тесты** в `ui/e2e/advice.spec.ts` (моки маршрутов как в существующем тесте):
  1. совет с `provenance {model_id, prompt_version: 'advisory-system.v1', duration_ms}` без `provider_requests`: видно «deepseek-v4-flash-0731» и «advisory-system.v1», нет текста `undefined`, нет строки про повтор;
  2. совет с `provider_requests: 2`, `prompt_version: 'advisory-system.v2'`: видно «потребовался повтор запроса (ошибка схемы ответа)»;
  3. `model_id: '<img src=x onerror=alert(1)>'` выводится как текст (счётчик диалогов 0);
  4. состояния `FAILED` с `failure: 'INVALID_OUTPUT'` и `UNAVAILABLE` с `unavailable_reason: 'CREDENTIAL_NOT_CONFIGURED'`: видна русская причина и сам код; для всех значений `AdviceFailure` и `AdviceUnavailableReason` из «Опорных фактов» есть текст (проверка перебором в одном тесте);
  5. в тексте панели нет строки «DeepSeek V4 Flash» как хардкода (подпись берётся из provenance либо нейтральное «Модель определяется настройкой»).
- [ ] **Step 2: Красные.** Run: `npm --prefix ui run e2e -- advice.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Реализовать.** В `AdvicePanel.vue` словарь причин (по одному предложению на код, без новых зависимостей), computed `provenanceLine` из `advice.provenance`; текст через интерполяцию Vue (без `v-html`). В `types.ts` `provenance` необязателен (защитное чтение `?.`), `provider_requests?: number`; в обоих перечисленных спеках моки совета получают `provenance`, где нужен показ, и остаются без него в тесте 1 для проверки защитного пути.
- [ ] **Step 4: Зелёные.** Run: `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run e2e -- advice.spec.ts new-analysis-advice.spec.ts security-a11y.spec.ts`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(ui): show the AI model, prompt version and failure reasons`. Документация: `docs/user/advisory-ai.md` (один абзац), `CHANGELOG.md`.

Что не входит: добавление `model_id`/`prompt_version` в JSON задания (для неудачных запусков провенанса нет; вопрос В5), исход повтора для неудачных запусков, пометка legacy v1 (вопрос 6 ADR решён как показ метки, отдельной пометки дефектов нет).

## Поправка 2026-10-05: снятие согласия и файл конфигурации моделей (ADR 0023, Accepted)

Решения владельца: «Галочку про "разрешаю отправку" нужно вообще убрать. Не забудь про выбор модели.» (2026-10-04); «галку убрать полность, риски на мне», «всегда снимать», «Нужен настраиваемый файл конфигурации моделей для указания слагов» (2026-10-05). ADR [0023](../../adr/0023-advisory-ai-consent-removal-and-model-config.md) принят: согласия на отправку нет нигде (вариант А; согласие по области endpoint отклонено), список моделей, подписи и модель по умолчанию задаёт файл `ai-models.v1` (`LT_VERDICT_AI_MODELS_FILE`, встроенная конфигурация по умолчанию сохраняет прежнее поведение), адрес endpoint и credential задаёт развёртывание. Срезы ниже **не входят** в Runtime-PR-B и UI-PR (U1) этого плана; Runtime-PR-A влит (#78), холдаут от них не зависит.

| Срез | Что | Размер | Зависит от | Горячие точки |
| --- | --- | --- | --- | --- |
| CM1 | Снять согласие: закрытый разбор тела POST (`confirm_external_transfer` необязателен и игнорируется, `false` даёт 400), UI без чекбоксов (U6c-1), документы | S-M | U1/U6a (влит) | `LocalApi.kt`, `AdvicePanel.vue`, `NewAnalysisPanel.vue`, `App.vue`, `labels*.ts` |
| CM2 | Файл конфигурации моделей: схема `ai-models.v1`, загрузчик и валидация, встроенная конфигурация, `advisory_ai` в bootstrap, `MODEL_CONFIG_INVALID`; до CM4 файл с другим endpoint или слагами отвергается | M | CM1 | `LocalApi.kt`, `AdvisoryAi.kt`, `ModelStudioAdvisoryRunner.kt`, `docs/contracts/advice/v1/`, `ui/scripts` (проверка контрактов) |
| CM3 | Provenance: `model_id` строкой с шаблоном вместо `const`, наблюдённый `endpoint_host` от relay (обязателен для новых советов), чтение без требования слага в текущем файле | S | CM2; B2, B3 (общая правка закрытого `provenance`; порядок слияния любой) | `ai-advice.schema.json`, `AdvisoryAi.kt` |
| CM4 | `model_id` в POST и статусе задания; `-ModelId`, `-UpstreamUrl`, env relay и Qwen вместо констант; сверка в relay; привязка credential к endpoint; контрактный тест согласованности | M | CM2, CM3; A2, A3 (влиты) | `advisory_ai_runtime.ps1`, `advisory_ai_runtime_relay.mjs`, `advisory_ai_runtime_qwen.sh`, `advisory_ai_acceptance_runner.ps1` (своя копия relay и модели) |
| CM5 | UI U6c-2: селектор модели, `measured`, информационная строка по `endpoint_label` | M | CM2, CM4 | `AdvicePanel.vue`, `NewAnalysisPanel.vue`, `App.vue` |

Порядок: CM1, CM2, CM3, CM4, CM5. Он-прем раннера как отдельного среза нет: endpoint и модели задаёт файл.

Тесты и ограничения срезов CM (кратко, полно в ADR 0023): тело POST принимает пустое тело, `{}`, устаревшее `confirm_external_transfer:true`, отвергает `false`, чужие ключи и дубликаты; слаг модели проверяется шаблоном `^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$` без `..` и `//` в каждом узле (Kotlin, launcher, relay, shell), значение и адрес передаются одним элементом argv и переменной окружения, не оболочкой; файл конфигурации отвергает неизвестные ключи (в том числе `api_key`), `http` без `allow_insecure_http`, URL с userinfo, query или fragment; relay сверяет модель в теле и в кадрах с переданной, редиректы не отслеживаются; чтение совета не требует, чтобы слаг входил в текущий файл; Docker и живой провайдер не нужны (fake-режим). Принятый риск (evidence и prompt уходят на endpoint из конфигурации без согласия пользователя) описан в ADR 0023; для плана значит одно: документация и тексты UI не должны утверждать, что данные остаются внутри периметра.

## Поправка 2026-10-06: холдаут и рука C через headless Codex (поправка ADR 0021 Д7)

Решение владельца 2026-10-06 (раздел 12, п. 9-10 сводки `docs/superpowers/plans/2026-10-05-owner-questions.md`, файл вне git; п. 19 раздела 1.A: «Хост»): холдаут (и пилот ADR 0024) тестируются через headless Codex, а не через Qwen Code и ModelStudio (лимиты Qwen на исходе); Codex в этой роли приравнен к GLM 5.3, не флагману. Условия проведения заданы разделом «Поправка от 2026-10-06: холдаут на headless Codex» в [ADR 0021](../../adr/0021-advisory-ai-prompt-v2-and-schema-retry.md); этот раздел переносит их в план. Где срезы H0-H5, W1-W4, раздел «Бюджет запросов ModelStudio» и вопросы В3, В8, В11 говорят о DeepSeek, ModelStudio, `relay2.mjs`, Qwen на хосте или fallback, действует эта поправка, а противоречащие ей фразы и команды в этих блоках историческая редакция и не исполняются. Срезы A1-A4, B1-B4, U1, CM1-CM5 и O1-O4 не меняются; продукт остаётся на runner Qwen за relay.

Вопросы поправки (1-11 в ADR, раздел «Вопросы поправки и решения владельца 2026-10-06») закрыты ответами владельца 2026-10-06 (раздел 17 свода, ответы 6 и 7; по вопросам 9-11 приняты рекомендации поправки; вопрос 7 о мосте с DeepSeek в записи ответа не назван и принят по рекомендации и передаче оркестратора): результат Codex это направление, для метки по умолчанию нужен подтверждающий прогон на модели продукта (объём назначает владелец), метка по Codex-холдауту не переключается; повтора при ошибке схемы нет (попытка равна одному вызову `codex exec`), P3 читается как «не OK не более 3 из 76 на руку» без пересланных запросов; P4 сводится к целостности с пометкой «изоляция не оценивалась в холдауте»; критерии и пороги P1-P5 не меняются; fallback отменён; рецензенты Codex, смещение публикуется; мост сравнимости с DeepSeek не нужен, пока владелец не скажет иначе; вызовы Codex ведутся отдельным счётчиком с нуля вне лимита 900. Условие «пока вопросы 1-5 не закрыты, H0 не замораживается и H3 не стартует» снято: вопросы закрыты; старт холдаута по-прежнему требует заморозки предрегистрации и подтверждения владельцем. Выводы холдаута читаются как направление для модели-заменителя, а не как величины для моделей продукта.

| Срез | Что меняется |
| --- | --- |
| Карта срезов | Зависимости H0 и H1 от A2 и A3 снимаются для холдаута: relay и launcher продукта в нём не измеряются. H0 ждёт A1 и B1 (байты prompt), O2-O4, W1, W3 и пробу раннера (ADR, П1 п. 6) |
| H0 | Раннер `codex exec` на хосте; модель Codex (не флагман, эквивалент GLM 5.3) выбирается при запуске, вместе с уровнем рассуждения пишется в предрегистрацию (п. 2). Единица учёта вызов `codex exec`; 160 попыток, потолок 320 вызовов остаётся верхней границей плана (повтора на попытку нет, вопрос 2 ADR: достижимы лишь 160), запас 8 только для перезапуска (п. 3). Отдельный счётчик Codex с нуля в каталоге вне git, вне лимита 900 (решено 2026-10-06, вопрос 8 ADR); ledger холдаута применяет собственные пределы (п. 4). Fallback отменён: основная модель сама Codex (п. 6; решено 2026-10-06, вопрос 5 ADR). Хост подтверждён; P4 публикуется с пометкой «изоляция не оценивалась в холдауте» (п. 7; подтверждено 2026-10-06, вопрос 3 ADR). Список заморозки (п. 9): relay, launcher продукта и `relay2.mjs` исключаются; добавляются раннер харнесса, версия и SHA-256 пакета `codex` CLI, идентификатор модели, уровень рассуждения, флаги и каталог запуска, способ передачи prompt и получения ответа по схеме, конфигурация Codex, перечень классов ошибок «сорвавшейся попытки». Правило остановки (п. 11): сетевой сбой хоста заменяется сбоем вызова Codex |
| H1 | `relay2.mjs`, общие векторы повтора и условие входа в H0 «зелёный `test_relay2_retry.mjs`, SHA векторов совпал» отпадают (повтора Д2 в холдауте нет и повтора на уровне харнесса нет: решено 2026-10-06, вариант (а) вопроса 2 ADR). Остаются ledger (резерв до вызова, `arm`, `stage`, номер вызова), порядок пар по seed, руки A и B, жёсткий предел. В записи ledger добавляются `runner: codex-headless`, `data_class: test_stand`, модель и уровень рассуждения; тест (д) про fallback отпадает; пределы (ж) 320, 252, 36 задаются как отдельный счётчик Codex вне лимита 900. Новое: раннер `codex exec` (пустой `-C` вне репозитория, `-s read-only`, stdin только ASCII, round-trip не-ASCII по хэшу, stdout и stderr вне git), заглушка `codex` для сценарных тестов (0 вызовов), проба раннера до первого запроса |
| H2 | Без изменений; случай, для которого нет способа доставить вход без искажения, до заморозки заменяется другим случаем той же ячейки матрицы (предложение автора) |
| H3 | Запуск без ключа ModelStudio (ключ не читается). Ошибка схемы: «не OK», повтора нет (вариант (а) вопроса 2 ADR, решено 2026-10-06); ошибка провайдера: перезапуск за счёт запаса 8, а не fallback. Контроль после прогона: вызовов по ledger не больше планового плюс перезапусков, не более 160 (вариант (а)). Ожидание «2-11 повторов» снимается: оно получено на DeepSeek через Qwen |
| H4, H5 | Формулы P1-P5 без изменений; P3 читается по решению владельца 2026-10-06 (вопрос 2 ADR: не более 3 из 76 на руку, без пересланных запросов); P4 с пометкой; счётчики повтора не публикуются (повтора нет). Смещение разметки внутри семейства Codex (модель под тестом и рецензенты) публикуется. Отчёт и поправка ADR 0021 содержат оговорку о переносе (ADR, П3) и решение владельца по вопросу 1 |
| W1-W4 | Рука C и W3 идут на том же раннере и той же модели, что A и B. Этап равен одному вызову `codex exec` (новая сессия на этап), жёсткий предел 3 вызова на прогон, повторов нет; запреты ADR (П1 п. 5) действуют. Фраза «temperature 0 как в runtime» не гарантируется: управляемость temperature не проверена. Режим «этап» в `relay2.mjs` заменяется проверкой в раннере (тело запроса этапа фиксировано по хэшу prompt этапа). Объёмы прежние: W3 36, рука C до 252, единица вызов |
| Бюджет | Числа прежние; единица вызов `codex exec`; ModelStudio в холдауте не расходуется (221 уже израсходованы). Строка про fallback на Codex отпадает |
| В3 | Условие fallback отпадает (решено 2026-10-06, вопрос 5 ADR); запас 8 и перечень сорвавшихся попыток остаются условиями «предложение автора»; модель Codex называется в предрегистрации |
| В8 | Решено 2026-10-06 (вопрос 8 ADR): вызовы Codex ведутся отдельным счётчиком с нуля вне лимита 900; ledger применяет собственные пределы |
| В11 | Решено владельцем: «Хост». Поправка Д7 по P4 вошла в ADR (П1 п. 7); условие «до H0» выполняется после подтверждения вопроса 3 ADR |

---

## Срез O1: спецификация отрицаний и регрессии

Оракул и тесты лежат в локальной ветке `test/ai-experiment-harness` (`tools/ai-experiment/aiexp/oracle.py`, `tests/test_oracle.py`) и в `main` не попадают; плана по ветке в git нет, исполнитель работает в её воркстри. Оракул детерминированный, без запросов.

**Files:** Modify `tools/ai-experiment/aiexp/oracle.py`; Test `tools/ai-experiment/tests/test_oracle.py`.

- [ ] **Step 1: Красные регрессии** (`python -m unittest discover -s tools/ai-experiment/tests -p test_oracle.py`). Ложные срабатывания `report-v2.md` §6 как предложения, на которых v1-оракул срабатывает, а v2 не должен:
  - P-A: «no user action that implements or enables a knee detector is available» (S1 K03-2);
  - P-C: «the two counts should not be treated as inconsistent» (S1 K01-2, K08-1);
  - P-V: «resource_verdict NO_POLICY while the overall verdict is PASS» (S1 K01-1);
  - рекомендация «повторить с заданными ступенями» (S1 K06) не срабатывает как P-A.
  Контрольные положительные (должны остаться срабатываниями): «implement a knee detector so the capacity bound can be derived», «make the generator guard binding», «bound requires required_capacity» (после O3), `NO_POLICY` как PASS.
- [ ] **Step 2: Красные.** Expected: FAIL на ложных.
- [ ] **Step 3: Commit** `test(ai): add negation regressions for the oracle`.

## Срез O2: оракул v2 и проверка без запросов к провайдеру

**Files:** Modify `aiexp/oracle.py` (функция `negated(sentence, span)`: отрицание `no|not|never|without|cannot|n't|neither|nor|unable|rather than|instead of` в окне не более 6 слов слева от совпадения, область отрицания обрывается на `,`, `;`, `but`, `however`, `although`; предикаты P-A, P-B, P-C, P-D, P-V пропускают совпадения внутри области отрицания), `calibrate_oracle.py` (режим сравнения версий), `tests/test_oracle.py`.

Протокол проверки на уже собранных ответах (0 запросов, в рамках ограничения на in-sample):

- Корпус разметки: v1 72 ответа (один рецензент), v2 72 ответа и v3 66 ответов (рецензенты A и B, цитаты дефектов в `review_a/*.json`, `review_b/*.json`, ключи `review_key_*.json`).
- **Разделение:** настройка предикатов только по v1 и v2 (множество D, 144 ответа); множество T (v3, 66 ответов) не открывается до фиксации правил, затем один раз измеряется. Числа на D помечаются «in-sample», на T «другие модели и ответы, возможна косвенная утечка»: T использует те же K01-K12, что и D, а отчёт v3 с общими числами оракула уже известен. Настоящую case-held-out оценку даёт только холдаут: оракул v2 замораживается в H0, его срабатывания против меток двух рецензентов на 38 новых случаях считаются в H4 как вторичная метрика.
- Мера: срабатывания по предикатам, precision и recall против цитат рецензентов (совпадение по предложению и указателю `field`), по каждому рецензенту и по «обоим»; сравнение с v1-оракулом на тех же множествах.
- Критерий годности оракула v2 как измерительного средства (не gate продукта): на T число подтверждённых срабатываний не меньше, чем у v1, а число неподтверждённых (по обоим рецензентам) не больше; регрессии O1 зелёные. Порог precision 0,9 из условия возврата S4 (ADR 0021) **не заявляется**: настроенные на D предикаты не дают независимой оценки, а T мал (66 ответов, две модели без DeepSeek).
- [ ] **Step 1:** Run `python tools/ai-experiment/calibrate_oracle.py --compare v1 v2 --sets D T` после реализации. Expected: таблица TP/FP/FN по D и T для обеих версий, файл вывода в `docs/ui-mockup/ai-experiment-2026-09-30/v3/analysis/oracle-v2-calibration.md` (не коммитится).
- [ ] **Step 2: Commit** `feat(ai): add negation scope to the experiment oracle`.

## Срез O3: счётчики остаточных ошибок

Новые предикаты-счётчики (не входят в hard_defect, как Д7): P-G1 «bound требует `required_capacity`» и P-G2 «bound требует бизнес-политику» (требование применимого SLA ступени, как в инварианте 2 ADR 0021, верное и в счётчик не входит), P-H «сбой guard означает насыщение генератора», P-W «смешение окон политики и ступени». Регрессии: «a capacity bound cannot be derived without a required capacity» срабатывает на P-G1; «each stage needs an applicable SLA to contribute to the bound» не срабатывает ни на P-G1, ни на P-G2; «the generator is saturated because the guard failed» на P-H; текст «the guard failed, the evidence does not say why» не срабатывает. Тесты и коммит как в O1/O2; `Commit: test(ai): add counters for residual capacity defects`.

## Срез O4: рубрика v2 и конфигурация рецензентов

Расширение рубрики по T2 (связь несопоставимых величин) и T7 (`report-v2.md:137`), закрытый список типов дефектов с определениями и примерами из `ai-defects-analysis-2026-09-30.md`; конфигурация рецензентов A (Codex `gpt-6-sol`, `xhigh`) и B (`gpt-5.6-terra`, `high`) с SHA промптов рецензентов; слепой формат (перемешанные идентификаторы `R001...`, ключ у корня). Результат: файлы в основном чекауте, SHA-256 заносятся в предрегистрацию H0. Проверка: два рецензента размечают 10 ответов из множества D в сухом режиме (ответы уже есть, запросов к ModelStudio нет), каппа публикуется; рубрика не меняется после этого шага.

## Срез H0: предрегистрация холдаута

**Поправка 2026-10-06:** пункты 2, 3, 4, 6, 7, 9 и 11 ниже читаются с учётом раздела «Поправка 2026-10-06: холдаут и рука C через headless Codex» выше (раннер `codex exec` на хосте, модель Codex, единица вызов, отдельный счётчик, fallback отпадает).

Файл `preregistration-holdout.md` в основном чекауте (вне git), SHA-256 пишется в `PREREG_HOLDOUT_SHA256.txt` до первого запроса. Содержание, дословно из ADR 0021 Д7 и решений 2026-10-04:

1. Данные: 30 случаев матрицы методики §4 плюс capacity-приложение из 8 случаев (перечень 1-8 из Д7), по 2 независимых запуска; кейсы K01-K12 и 23 слота приёмки исключены.
2. Руки: A (prompt v1) и B (prompt v2), одна модель `deepseek-v4-flash-0731`, concurrency 1; пары (A, B) идут подряд, порядок внутри пары задаёт зафиксированный seed; повтор 1 по возрастанию идентификатора, повтор 2 по убыванию.
3. Бюджет: 38 x 2 x 2 = 152 плановые попытки, 160 с запасом 8; жёсткий потолок 320 запросов (2 на попытку); резерв 2 запроса на попытку с освобождением неиспользованного.
4. Лимит ИИ-запросов ModelStudio в ledger: 900 (общий), с учётом руки C и пилота W3 (раздел «Бюджет»).
5. Критерии P1-P5 и правила решения (таблица Д7) без изменений; расчёт P1 парным односторонним тестом знаков (скрипт в H4, `scipy.stats.binomtest` не требуется: точный биномиальный p считается через `math.comb`).
6. Fallback (предложение автора, подтверждается владельцем при заморозке): ошибки провайдера основной модели (HTTP-ошибка, сбой транспорта, недоступность; не ошибка схемы, её закрывает Д2); первая ошибка провайдера в попытке (повторов на основной модели нет: автоматические повторы вне Д2 запрещены) помечает попытку основной модели как неудачную и выполняет тот же случай на не флагманской модели Codex, эквивалентной GLM 5.3 (идентификатор выбирается на момент запуска и пишется в предрегистрацию; здесь не назван); потолок fallback 16 попыток (10 % от 160); каждая попытка основной модели, в том числе завершённая ошибкой провайдера, считается в потолок 160; fallback-попытки учитываются отдельным полем ledger и **не** входят в P1-P5: пара исключается из P1-P2 или перезапускается на основной модели за счёт запаса 8, как определит предрегистрация; доля fallback публикуется.
7. **Рантайм холдаута.** Пилоты v1-v3 запускали Qwen на хосте без Docker (`aiexp/qwenrun.py`), и так же будет работать H1: relay2 на хосте читает ключ в память, Qwen на хосте с продуктовыми флагами. Тогда «0 нарушений изоляции» из P4 холдаут не наблюдает; изоляцию проверяют продуктовые preflight-тесты A3 и проба контейнера, а в отчёте формулировка P4 уточняется («изоляция не измерялась в холдауте»). Это не нарушает «обе руки на одном runtime» (рантайм одинаков у A и B), но расходится с P4 и с ADR 0010 (внешняя OS sandbox) в части наблюдения изоляции: **до первого запроса** в docs-PR к ADR 0021 (поправка Д7) фиксируется, что в холдауте P4 сводится к целостности (0 изменений analysis и verdict, 0 утечек секретов, 0 выполненных canary), изоляция оценивается продуктовыми preflight-тестами и пробой и в итоге P1-P5 числится как «не оценивалась в холдауте», а не «выполнена». Без такой поправки (или без выбора контейнерной топологии, В11) H3 не стартует. Альтернатива (в вопросе В11): запускать обе руки в контейнерной топологии продукта (`docker create` как в launcher, relay в контейнере), для чего нужен credential env-file на диске и копия launcher с выбором prompt (в продуктовом launcher селектора prompt нет).
8. Байты prompt v1 для руки A после A1 — LF (`69f215a1...`); пилоты отправляли модели CRLF-вариант (`cec9ec6d...`, виден `\r\n` в `system_head` запросов). Фиксируется как отклонение от пилотов, не как блокер: смысловой текст тот же.
9. Заморозка SHA-256: prompt v1 (`system-prompt.md`, LF-хэш `69f215a1...` после A1) и v2 (`PROMPT_V2_SHA256`), схем, relay и launcher продукта (после A2, A3), relay2 харнесса, manifest случаев, оракул v2, рубрика и конфигурация рецензентов (O2-O4), план порядка и скрипты анализа, дизайн руки C (W1).
10. Вторичные метрики и абсолютные gate методики §8 (считаются отдельно, только на исходных 30 случаях, по руке; не определяют принятие v2).
11. Правило остановки: автостоп ledger, файл `STOP`, сетевой сбой хоста останавливает серию (как в v3: ENOTFOUND, `provider_timeout`), возобновление без повтора готовых запусков.

- [ ] **Шаги:** собрать файл; показать владельцу пункты «предложение автора» (3, 6) и подтвердить; посчитать SHA-256 всех замороженных артефактов; записать `PREREG_HOLDOUT_SHA256.txt`; **закоммитить SHA-256 предрегистрации и список SHA замороженных артефактов в сообщении коммита ветки харнесса** (требование Д7 «SHA-256 записывается в коммит»; практика v2/v3: коммит харнесса `6080abe`; сами материалы остаются вне git, как решил владелец); только после этого ledger-лимит поднимается до 900 (`TOTAL_LIMIT`, `HARD_TOTAL_LIMIT` в H1). Если владелец захочет tracked-запись дайджеста в `main`, это отдельный docs-PR из одного файла (вопрос В12).

## Срез H1: харнесс холдаута

**Поправка 2026-10-06:** relay2, ключ ModelStudio, векторы повтора Д2 и fallback ниже заменены разделом «Поправка 2026-10-06: холдаут и рука C через headless Codex» выше; ledger, порядок пар и руки A/B остаются.

**Files (локальная ветка `test/ai-experiment-harness`):** Modify `tools/ai-experiment/relay2.mjs` (`HARD_TASK_LIMIT`, `HARD_TOTAL_LIMIT`, логика повтора Д2), `aiexp/common.py` (`TASK_LIMIT`, `TOTAL_LIMIT = 900`), `aiexp/budget.py`; Create `tools/ai-experiment/run_holdout.py`, `aiexp/runner_holdout.py`, `tests/test_holdout.py`, `tests/test_relay2_retry.mjs`, `tests/vectors/advisory_ai_relay_retry_vectors.json` (копия `tools/advisory_ai_relay_retry_vectors.json` из A2 с тем же SHA-256). SHA-256 всех созданных файлов входят в список заморозки H0.

- [ ] **Step 1: Красные тесты:** (а) relay2 реализует Д2 теми же векторами, что A2: общий JSON-файл векторов в `tools/ai-experiment/tests/vectors/retry_cases.json`, тот же набор расходится по результатам тестом Node `node --test` для обоих relay (иначе холдаут измеряет не поставляемые байты relay); (б) ledger пишет строку до вызова провайдера с `attempt_id`, `request_ordinal` (1 или 2), `arm`, `model`, `fallback` (bool), `stage` (пусто для A/B); (в) попытка A/B с двумя пересланными запросами не может получить третий; (г) расчёт резерва: на старте попытки резервируются 2, при завершении за один запрос возвращается 1; (д) при `fallback=true` запись идёт в отдельный счётчик и не уменьшает бюджет 900; (е) порядок пар по seed воспроизводим; (ж) лимиты задач `holdout-ab` 320, `holdout-c` 252, `w3-dev` 36 и общий 900 заданы константами `TASK_LIMIT`/`TOTAL_LIMIT` (`aiexp/common.py`) и `HARD_TASK_LIMIT`/`HARD_TOTAL_LIMIT` (`relay2.mjs`), ledger не позволяет превысить ни один (иначе автостоп сработал бы на 100/130 запросах).
- [ ] **Step 2: Красные.** Run: `python -m unittest discover -s tools/ai-experiment/tests` и `node --test tools/ai-experiment/tests/test_relay2_retry.mjs` (relay2 против общих векторов Д2). Expected FAIL.
- [ ] **Step 3: Реализовать;** лимиты в коде — константы, поднимаемые только после H0.
- [ ] **Step 4: Зелёные** (обе команды Step 2; зелёный `test_relay2_retry.mjs` и совпадение SHA-256 векторов с A2 — условие входа в H0) и сухой прогон `python tools/ai-experiment/run_holdout.py plan --dry-run --fake-port <порт>` (0 запросов, fake provider). Expected: 152 плановые попытки в `plan-holdout.json`, SHA-256 плана сохраняется.
- [ ] **Step 5: Commit** в ветке харнесса `test(ai): add the holdout harness with retry and fallback accounting`.

## Срез H2: корпус из 38 случаев

Готовится по методике §5 (минимальные новые детерминированные фикстуры, прогон через актуальную сборку LT Verdict, независимый expected до запуска модели, нейтральные идентификаторы, canary-строки для H-случаев). Capacity-приложение (8 случаев Д7) собирается на синтетических прогонах и планах capacity через `AdvisoryEvidenceBuilder` (Kotlin, существующий), а не вручную. Проверка представимости 38 строк до платных запросов обязательна (методика §5, п. 5). Критерий готовности: manifest случаев, evidence-файлы, SHA-256 каждого, expected-таблица; ни один из 38 не совпадает с K01-K12 и 23 слотами по хэшу evidence. Размер L; возможно разбить на «30 матрицы» и «8 capacity» двумя исполнителями. Не пересекается с задачами A, B.

## Срез H3: прогон холдаута A/B

**Поправка 2026-10-06:** команда запуска, ключ из `~/.qwen/settings.json`, повтор Д2 и fallback ниже читаются с учётом раздела «Поправка 2026-10-06: холдаут и рука C через headless Codex» выше.

Запуск: `python tools/ai-experiment/run_holdout.py run --live --task-limit 320 --total-limit 900` в окружении с ключом из `~/.qwen/settings.json` (в память relay). Правила: concurrency 1, пары подряд, автостоп, `STOP`; ошибка схемы закрывается повтором Д2, ошибка провайдера: fallback по H0; пропущенные из-за сети попытки перезапускаются за счёт запаса 8 попыток, не больше. Выход: `runs_holdout/`, `ledger.jsonl`, `relay-result.json` по попыткам.

- Контроль после прогона: число запросов к провайдеру по ledger = плановое (152 + число повторов + перезапуски) и не больше 320; ни одной попытки A/B с 3 пересланными запросами (иначе P3, дефект реализации).
- Ожидание заранее (Д7): повторов около 2-11 на 152 попытки; при числе повторов меньше 5 вывод об эффективности повтора не делается.

## Срез H4: разметка и расчёт

Единая слепая разметка: все ответы рук A, B (152 плюс сохранённые неудачные попытки как «не OK») и, если W4 уже выполнен, руки C (76) перемешиваются в один пул, рецензенты A и B размечают по рубрике O4 (рецензент не знает руку), ключ раскрывает корень после сдачи; каппа публикуется (P5). Расчёт P1-P5: «подтверждён» = отмечен обоими; P1: счёт кейсов с подтверждённым T1 в capacity-приложении (8 случаев) по рукам и точный парный односторонний тест знаков; пример расчёта p:

```python
from math import comb
def sign_test_p(only_a, only_b):
    n = only_a + only_b
    return sum(comb(n, k) for k in range(only_a, n + 1)) / 2**n if n else 1.0
assert round(sign_test_p(5, 0), 3) == 0.031 and round(sign_test_p(7, 1), 3) == 0.035 and round(sign_test_p(6, 1), 4) == 0.0625
```

(числа совпадают с таблицей Д7, п. P1). Этот расчёт включается в `analysis3.py`-подобный модуль холдаута с тестом на три приведённых значения.

## Срез H5: отчёт и поправка ADR 0021

Отчёт `report-holdout.md` (вне git); в `docs/adr/0021-advisory-ai-prompt-v2-and-schema-retry.md` — docs-PR: раздел «Результат холдаута» с числами, решением по метке по умолчанию (рекомендация переключить, `INCOMPLETE`, отказ или решение владельца), счётчиками остаточных ошибок и числом повторов. Prompt-PR (переключение метки: `ModelStudioAdvisoryRunner.kt:276`, `advisory_ai_runtime.ps1:28`, `build.gradle.kts`, документы) оформляется отдельным планом после подтверждения владельца. Поправка 2026-10-06: для холдаута на Codex рекомендации переключить метку отчёт не даёт: результат это направление, а переключение требует подтверждающего прогона на модели продукта (ADR 0021, П3, п. 2 и 7).

## Срез W1: дизайн руки workflow

**Поправка 2026-10-06:** Qwen, relay харнесса и `--bare --safe-mode` в W1-W4 заменены вызовами `codex exec` по разделу «Поправка 2026-10-06: холдаут и рука C через headless Codex» выше.

**Гипотеза H-C:** заданная явная проверка утверждений черновика против evidence и доменных правил (классы T1, T2, T3, T7), выполненная отдельным запросом, снижает число случаев с подтверждённым hard_defect по сравнению с одним запросом с prompt v2 (рука B), потому что v2 не снимает T2/T3/T7 (ADR 0021, Контекст п. 2).

**Почему не нативный workflow.** Проба показала на 0.21.1 (раздел «Итоги пробы контейнера»): под `--bare` инструмента `agent` нет, под `--safe-mode` нет hooks и пользовательских агентов; чтобы получить `agent` и hooks, нужно снять оба флага и `--max-tool-calls=0` и закрывать ещё 52-57 инструментов по именам; порядок «проверка до финала» держится только на модели (hook-gate на `structured_output` возможен, но стоит запрос на отказ); число запросов нативного пути 3-4, то есть не меньше, чем у трёх одиночных вызовов. Поэтому рука C = оркестрация корня (харнесс) из трёх одиночных вызовов через relay, по рекомендации разведки `v3/qwen-workflow-recon.md`:

| Этап | Вход | Выход | Проверка локально |
| --- | --- | --- | --- |
| 1 draft | неизменный `ai-evidence.v1`, prompt v2 | черновик `ai-advice-output.v1` через `--json-schema` | замкнутая проверка (структура, `evidence_refs`), черновик хранится, но не публикуется |
| 2 verify | evidence, черновик, список правил (инварианты v2 и определения T1/T2/T3/T7 из рубрики O4) | закрытый JSON `ai-advice-verification.v1`: `claims[]` с `pointer` (JSON pointer в черновике), `rule_id`, `status` `supported`/`unsupported`/`uncertain`, `evidence_refs`, `note` | схема verify, каждый `pointer` существует в черновике, каждый ref входит в переданные, покрыты все `hypotheses[*].observation`, `possible_explanation` и `recommendations[*].action` |
| 3 final | evidence, черновик, findings | итоговый `ai-advice-output.v1` через `--json-schema` | та же замкнутая проверка продукта; ссылки должны существовать в переданном evidence; фактическая поддержка утверждений проверяется только verifier и слепой разметкой, гарантии нет |

Правила: единственный Qwen-вызов на этап, `--bare --safe-mode`, продуктовые флаги изоляции, без tools; relay харнесса принимает по одному запросу на этап (новая сессия на этап, а не продолжение) и считает их; hard cap 3 пересланных запроса на прогон, повторов нет; ошибка схемы или транспорта на этапе k даёт `FAILED_k` и ответ «не OK», промежуточные JSON хранятся только в `runs_workflow/`. Модель и провайдер те же, что в руках A и B; temperature 0 как в runtime. Правила verifier (инварианты, определения T-классов, формат claims) замораживаются в предрегистрации H0 вместе с остальным.

**Критерий сравнения C с B** (до первого запроса, один основной): на тех же 38 случаях и повторах, слепая разметка тех же рецензентов вперемешку с A и B; метрика — число случаев (повторы объединяются, как Д7) с подтверждённым (обоими) hard_defect. C выигрывает по условиям 1-4 (статистическая победа), продуктовый режим рассматривается при условии 5 дополнительно:

1. парный односторонний тест знаков по несовпадающим случаям (дефект только у B против дефекта только у C) даёт p не выше 0,05 (например, 5 против 0, p = 0,031, или 7 против 1, p = 0,035; 6 против 1 даёт 0,0625 и не проходит); это уже требует разницы не менее 5 случаев, отдельного порога «минус 4» нет;
2. по капасити-приложению (8 случаев) C не хуже B;
3. сохранение обязательных фактов у C не хуже B более чем в 1 случае, средняя полезность не ниже B минус 0,3;
4. не OK (включая `FAILED_1/2/3`) у C не более 3 из 76 прогонов (3,9 %, как P3 Д7) и не более чем на 2 больше, чем у B;
5. решение о продуктовом режиме (отдельно от «победы» по условиям 1-4): учтены 3 запроса и задержка (медианы); режим оправдан, только если абсолютное снижение доли случаев с дефектом не меньше 15 процентных пунктов, то есть не менее 6 случаев из 38 (5 против 0 даёт статистическую победу, но 13,2 п. п. и не оправдывает режим).

Оговорка (в предрегистрации): рука C меняет сразу два фактора, проверку и число запросов; контроль «три вызова без проверки» (C0) не планируется; результат отвечает на вопрос «стоит ли платить 3 запроса», а не «помогает ли именно проверка». При 38 случаях обнаруживается только большой эффект.

## Срез W2: харнесс руки workflow

**Files (ветка харнесса):** Create `tools/ai-experiment/aiexp/runner_workflow.py`, `tools/ai-experiment/run_workflow.py`, `tools/ai-experiment/aiexp/schemas/ai-advice-verification.v1.schema.json`, `tools/ai-experiment/aiexp/prompts/workflow_verify.md`, `tools/ai-experiment/aiexp/prompts/workflow_final.md`, `tests/test_workflow.py`; Modify `relay2.mjs` (режим «этап»: тело запроса этапа фиксировано по хэшу prompt этапа). SHA-256 схемы verify и промптов этапов входят в список заморозки H0.

- [ ] **Step 1: Красные тесты** (`unittest`, fake provider, 0 запросов к ModelStudio): прогон из 3 запросов `SUCCESS`; verify с обёрнутыми аргументами даёт `FAILED_2` без четвёртого запроса; verify со `pointer`, которого нет в черновике, даёт `FAILED_2`; verify с `evidence_ref` вне переданных даёт `FAILED_2`; final нарушает замкнутую проверку даёт `FAILED_3`; ledger содержит `arm = C`, `stage = 1/2/3`, `attempt_id`, `request_ordinal` 1-3; четвёртый запрос блокируется по hard cap (Review Focus 6).
- [ ] **Step 2:** Run: `python -m unittest discover -s tools/ai-experiment/tests -p test_workflow.py`. Expected: FAIL.
- [ ] **Step 3:** реализация по таблице W1. **Step 4:** Expected PASS; сухой прогон `run_workflow.py plan --dry-run` на 38 случаях даёт 76 прогонов. **Step 5:** Commit в ветке харнесса `test(ai): add the three-call workflow arm to the experiment harness`.

## Срез W3: разведочный прогон на development-корпусе

K01-K12, повтор 1, 12 прогонов x 3 = 36 запросов (в бюджете). Цель: отладка схемы verify и правил, не результат; холдаут-случаи не используются. Результаты не входят в P1-P5 и в сравнение W5. После W3 правила verifier замораживаются в H0 (W1). Критерий готовности: все 12 прогонов дают валидные три этапа или понятную причину `FAILED_k`; доля `FAILED_k` не выше 25 % (иначе дизайн этапов пересматривается до заморозки).

## Срез W4: прогон руки C

Запускается после H3 и **до** разметки: все 228 ответов (A, B, C) размечаются одним слепым пулом в H4, иначе рецензент видел бы руку, либо пул A/B был бы размечен раньше C. Правила C заморожены в H0 и не зависят от результатов A/B, потому что разметки к этому моменту ещё нет. 38 x 2 = 76 прогонов x 3 = 228 запросов номинально; перезапуск при ошибках инфраструктуры до 8 прогонов (24 запроса); потолок руки 252. Если владелец решит не запускать C (В7), H4 разметит 152 ответа A/B.

## Срез W5: сравнение и решение

Расчёт критерия W1 скриптом холдаута; отчёт `report-workflow.md` (вне git): таблицы по типам T1-T7, по случаям, цена (запросы, медианная задержка, токены), число `FAILED_k`. Решение: C выиграла по условиям 1-4 и выполнено условие 5 — переходим к P1; выиграла по 1-4, но не по 5 — результат публикуется, продуктовый режим не открывается; иначе — рука C закрывается, продукт остаётся на Д1-Д4 (в зависимости от P1-P5); нативный workflow не рассматривается повторно без нового довода (разведка и проба).

## Срез P1: что нужно для продуктового режима (условно, если C выиграла)

Продуктовый режим меняет правило ADR 0010 «один запрос» уже не на единственное исключение, а на бюджет запросов на совет. Нужен **новый ADR** (предлагаемое имя `advisory-ai-staged-workflow`; номер брать следующий свободный в момент написания: 0022 занят ADR корреляций, 0023 занят ADR согласия и выбора модели), PR только документальный, до реализации; содержание:

1. **Бюджет запросов и времени.** 3 запроса на совет и повтор по Д2 на каждом этапе (максимум 6) или без повтора (максимум 3); ответ на вопрос о соотношении с окном 600 с: три последовательных запроса DeepSeek 57-131 с каждый, GLM 197-262 с, то есть окно либо растёт (с изменением 605/613/620 с и ADR 0021 Д2 п. 6), либо у каждого этапа свой дедлайн; предел 300 с на старт повтора пересматривается.
2. **Провенанс.** `provenance.workflow` (закрытый объект: `stages[]` с `stage`, `prompt_version`, `prompt_sha256`, `provider_requests`), `provider_requests` 3-6; метка `advisory-system.v3` либо `workflow.v1`; чтение v1/v2 без запроса (Д3 сохраняется); хранение findings verifier: что именно писать (только счётчики `unsupported`/`uncertain` либо сами findings), правило неизменяемости.
3. **Relay/launcher.** Сейчас relay пропускает продолжение одного диалога; этапы это три независимых диалога: relay должен принимать запросы по зафиксированным хэшам prompt этапов, считать их и ограничивать 3 (или 6), либо launcher запускает три Qwen-контейнера под одним relay; выбор — часть ADR.
4. **Согласие и UI.** Evidence уходит провайдеру 3 раза: обновление текста согласия (`AdvicePanel.vue`, новый анализ; согласия на отправку в продукте больше нет, ADR 0023) и показа стадии; прогресс по этапам, отмена на любом этапе, `Busy`.
5. **Fail-soft.** Ошибка этапа k: `FAILED/INVALID_OUTPUT` либо отдельная причина; совет не публикуется, черновик не показывается (иначе непроверенный вывод станет советом).
6. **Приёмка.** Результат холдаута относится к тройке (модель, prompt, workflow); подтверждение P1-P5 на свежих случаях, если выбор C сделан по тем же 38 случаям (эффект отбора): рекомендация, вопрос В9.
7. **ADR 0010.** Второе исключение (или замена правила «один запрос» бюджетом); `--exclude-tools`, `--max-tool-calls=0`, `--bare --safe-mode` не меняются.
8. **Нативный режим** остаётся отклонённым: цена снятия изоляции (проба) выше пользы.

Реализация такого режима — отдельные срезы после ADR, в этот план не входят.

## Бюджет запросов ModelStudio

**Поправка 2026-10-06:** числа таблицы остаются, но для холдаута, руки C и W3 единица вызов `codex exec`, а ModelStudio не расходуется (см. раздел «Поправка 2026-10-06: холдаут и рука C через headless Codex» выше).

| Статья | Попытки/прогоны | Запросы (номинал) | Потолок |
| --- | --- | --- | --- |
| Уже израсходовано (v1 74, v2 74, `GET /models` 1, v3 72) | | 221 | 221 |
| Холдаут A/B | 152 плановые, бюджет 160 | около 154-163 | 320 (2 на попытку) |
| Рука C на холдауте | 76 прогонов | 228 | 252 (запас 8 перезапусков по 3) |
| W3 (разведка C на K01-K12) | 12 прогонов | 36 | 36 |
| Итого к потолку | | | 829 |
| Свободный резерв до 900 | | | 71 |

Резерв 71 тратится только по решению владельца (сетевые перезапуски вне запаса, дополнительные пробы). Fallback на Codex: отдельный счётчик, в 900 не входит; потолок 16 попыток (предложение автора, В7); отпадает по поправке 2026-10-06: основная модель холдаута сама Codex. Пилотный прогон Д2 на живой модели не планируется (повтор проверяется fake-провайдером, Д2).

## Вопросы владельцу (с рекомендациями)

- **В1. Размер Runtime-PR.** ADR 0021 говорит об одном Runtime-PR. Рекомендация: два PR, A (транспорт: A1-A4) и B (контракт: B1-B4); причина: A меняет relay и launcher (L), B меняет Kotlin и схему; A можно проверить без Kotlin, B опирается на поля A. Альтернатива: один PR (больше риск review).
- **В2. Нормализация EOL (A1).** Рекомендация: принять правила `.gitattributes` для prompt и `.sh` до закрепления SHA v2: без них хэш зависит от хоста, а launcher не запускается на Windows с `autocrlf` (проба). Цена: однократное пересоздание файлов в рабочих деревьях.
- **В3. Подтвердить условия «предложение автора» при заморозке (H0):** запас 8 попыток только для инфраструктурных сбоев; fallback после первой ошибки провайдера в попытке (без повтора на основной модели), потолок 16, исключение пар из P1-P2; выбор конкретной не флагманской модели Codex, эквивалентной GLM 5.3 (на момент запуска). Рекомендация: подтвердить; модель назвать в предрегистрации до первого запроса. **Поправка 2026-10-06:** условие fallback отпадает (решено 2026-10-06, вопрос 5 ADR 0021, раздел «Вопросы поправки и решения владельца 2026-10-06»).
- **В4. Срок UI-PR (U1).** ADR отводил показ Prompt-PR, владелец решил «обязательно». Рекомендация: отдельный PR сейчас, не ждать холдаута (не зависит от Runtime-PR); если владелец хочет по ADR (вместе с Prompt-PR), U1 сдвигается без изменения содержания.
- **В5. Модель и prompt в задании (неудачные запуски).** Провенанс есть только у сохранённого совета; для `FAILED`/`UNAVAILABLE` UI модель показать не может. Рекомендация: не менять API сейчас (минимальное изменение), показывать причину; если нужна модель и в неудаче, добавить необязательные `model_id` и `prompt_version` в JSON задания отдельным PR (публичный контракт API, нужен ADR-абзац). Уточнение: поле `model_id` в JSON задания вводит ADR 0023 (срез CM4), так что для модели вопрос закрывается там, а `prompt_version` в задании по-прежнему не добавляется.
- **В6. Оракул v2.** Рекомендация: статус «инструмент измерения», без порога precision 0,9 до разметки новой выборки; числа на v1+v2 подаются как in-sample, на v3 как диагностика на других моделях и ответах (те же случаи K01-K12, возможна косвенная утечка, DeepSeek не входит); case-held-out оценка только на холдауте.
- **В7. Рука workflow.** Рекомендация: запустить как третью руку вне P1-P5, после A/B, с критерием W1 (разница минимум 4 случая, p до 0,05, не менее 15 п. п. для продуктового режима); потолок 252 + разведка 36. Альтернатива: не запускать, сэкономить 288 запросов; тогда решение о workflow откладывается. Контроль C0 (три вызова без проверки) не планируется (цена 228 запросов).
- **В8. Лимит 900.** Принят владельцем 2026-10-04 (по сообщению оркестратора; в ADR 0021 не записан: внести в предрегистрацию H0). Уточнить, что резерв 71 и fallback на Codex в него не входят и расходуются отдельным разрешением. **Поправка 2026-10-06:** холдаут идёт на Codex; решено 2026-10-06: вызовы Codex отдельным счётчиком с нуля вне лимита 900 (вопрос 8 ADR 0021, раздел «Вопросы поправки и решения владельца 2026-10-06»).
- **В9. Подтверждение выбора C.** Если C выигрывает по тем же 38 случаям, на которых определён выбор, рекомендация: перед включением в продукт короткий подтверждающий прогон на свежих 8-12 случаях (до 72-108 запросов, нужен отдельный лимит).
- **В12. Где фиксируется SHA предрегистрации.** Д7 требует запись SHA в коммит; материалы в git не идут. Рекомендация: коммит ветки харнесса `test/ai-experiment-harness` с SHA в сообщении (как в v2/v3), без файла в `main`. Альтернатива: docs-PR с одним файлом-дайджестом.
- **В10. Выбор модели (Д5).** Решение 2026-10-04 «не вводить» пересмотрено владельцем: выбор модели в UI запрошен и принят ADR 0023 (Accepted, 2026-10-05): список из файла конфигурации (`ai-models.v1`), на запрос одна модель; срезы CM1-CM5 в разделе выше. Приёмка по-прежнему относится к тройке (модель, prompt, runner): модели вне эксперимента помечаются как неизмеренные; холдаут и его предрегистрация от выбора в продукте не зависят.
- **В11. Рантайм холдаута.** Рекомендация: хост без Docker, как в пилотах (дешевле, сопоставимо с v1-v3), при условии поправки Д7 по P4 до первого запроса (docs-PR). Решение нужно **до H0**. **Поправка 2026-10-06:** владелец выбрал «Хост» (п. 19 раздела 1.A сводки), условие по P4 перенесено в ADR 0021. Альтернатива: контейнерная топология продукта, нужен credential env-file на диске (ранее удалён по соображениям секретности) и копия launcher с выбором prompt.

## Проверка плана и срезов

Этот PR (docs): `npx --yes markdownlint-cli2@0.23.2 "docs/superpowers/plans/2026-10-04-ai-v2-implementation.md"`; скрипт проверки ссылок (относительные markdown-ссылки существуют, упомянутые пути в code span существуют или помечены как вне git); `git diff --check`; независимое ревью Codex (read-only), замечания проверяются фактами.

Срезы: команды в блоках шагов. Общий набор перед PR кода (повторяет `runtime-quality.yml`): `.\gradlew.bat clean check installDist` (сборка и упаковка; для B1 проверить, что новый файл prompt попал в дистрибутив), `npm --prefix ui run build`, `npm --prefix ui run test:contracts`, секрет-проверка `git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16}|BAILIAN_TOKEN_PLAN_API_KEY=)" -- .` (0 совпадений), `.\gradlew.bat test` (Kotlin), `node --test tools/test_advisory_ai_runtime_relay.mjs`, `powershell.exe -File tools/test_advisory_ai_runtime.ps1`, `python -m unittest discover -s tools -p "test_*.py"`, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run e2e -- advice.spec.ts`, `npx --yes markdownlint-cli2@0.23.2 "**/*.md"`, `git diff --check`.

## Итоги пробы контейнера (0 платных запросов; полный отчёт в `docs/ui-mockup/ai-experiment-2026-09-30/v3/qwen-container-probe.md`, вне git)

Проба выполнена на закреплённых артефактах: образ `mcr.microsoft.com/playwright/mcp@sha256:7b82f29c...bb2` (локально, `docker image inspect` совпал), Qwen Code 0.21.1 (`cli-entry.js` SHA-256 `1db9709b...ed38`, каталог `.worktrees/ai-experiment/_qwen`). Все запросы шли к mock-серверу в `--internal` Docker network; ключ не читался (0 совпадений по шаблонам ключа); из сети внутри контейнера внешний хост недоступен (`EAI_AGAIN`).

| Проверка | Результат |
| --- | --- |
| Базовый preflight launcher | Из рабочего дерева Windows падает (CRLF в `.sh`); с LF `SUCCESS` за 9,3 с; остатков нет |
| `--version`, `--help`, принятие флагов | `0.21.1`; `--json-schema`, `--output-format json/stream-json`, `--max-session-turns`, `--max-tool-calls`, `--max-wall-time`, `--exclude-tools`, `--approval-mode` (`plan/default/auto-edit/auto/yolo`), `-p`/`--prompt` + stdin приняты; `--bogus-flag` отвергнут |
| Продуктовые флаги | В реестре только `structured_output` (`--bare` оставляет 4 инструмента, `--exclude-tools` убирает их); вызов `agent` даёт exit 55 |
| Запись повтора (предусловие Д2) | Форма зафиксирована: `assistant.content: null`, `reasoning_content: ""`, `tool_calls[0]`, `tool` с короткой ajv-фразой; остальное тело как у 1-го запроса; тот же повтор и для глубокого нарушения схемы |
| Предел Qwen на повторах | Только защита от одинаковых вызовов (5 запросов, exit 1); предел 2 запроса обязан ставить relay |
| `agent` и `.qwen/agents` | Нужны: без `--bare` (иначе нет инструмента), `--allowed-tools=agent` (иначе отказ в non-interactive), `run_in_background:false` (иначе фон); под `--safe-mode` пользовательские агенты не грузятся |
| Hooks `PreToolUse`/`Stop` | Работают в headless только без `--safe-mode` и без `--bare`; `PreToolUse deny` на `structured_output` действует и стоит один дополнительный запрос |
| Число запросов inline-workflow | 3 без gate, 4 с отказом hook; родитель и ребёнок видят 57 и 47 инструментов (`tools: []` не ограничивает) |
| Коды выхода | 0, 1, 53 (лимит ходов), 55 (лимит инструментов) |

Вывод: нативный workflow внутри текущей изоляции неосуществим; экономии запросов не даёт; для эксперимента выбирается рука C из трёх одиночных вызовов (W1).

Не проверено: живая модель (поведение при `agent`, частота обёрток и качество повтора), форма второго запроса с непустым `reasoning_content` (mock его не стримит), повторы с различающимися невалидными аргументами, ограничение инструментов sub-agent по именам, hooks типов `http`/`prompt`/`function`, блокирующий `Stop`, поведение на Linux-хосте, отдельный негативный тест read-only mounts.

## Не входит

Структурный `ai-advice.v2`, S3, S4 как gate, механизм выбора модели (Д5; описан в ADR 0023, срезы CM1-CM5), нативный workflow в продукте, правки ядра анализа, показ метки как предупреждения о дефектах legacy v1, коммит материалов эксперимента.

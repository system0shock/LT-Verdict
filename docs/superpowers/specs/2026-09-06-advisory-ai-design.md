# Advisory AI: первая поставка

Дата: 2026-09-06.
Статус: утверждён для реализации пользователем 2026-09-06. Для интеграции
разрешён Qwen Code 0.21.1 вместо GigaCode; runtime capabilities и OS isolation
проверяются отдельно и до успешной проверки runner остаётся `UNAVAILABLE`.

## Цель и граница

Пользователь явно запускает ИИ-анализ сохранённого результата LT Verdict
и получает отдельный advisory-отчёт: наблюдения, гипотезы, рекомендуемые
проверки и ограничения со ссылками на evidence.

Целевой runner: **GigaCode, fork Qwen Code 0.21.1**, как указал пользователь.
2026-09-06 пользователь разрешил использовать **upstream Qwen Code 0.21.1**
для начала интеграции; по сообщению пользователя различаются naming conventions
`gigacode`/`qwen`. Это основание для выбранной замены, не результат проверки
фактической изоляции двух сборок. Другие версии автоматически не подставляются.
Основание: [local-first delta design, раздел 20](2026-08-26-v06-local-mvp-delta-design.md).

Не входят: изменение deterministic verdict, statistical selector v2,
автоматическое исправление нагрузочных тестов, GigaCode onboarding Skill,
выполнение рекомендаций, causal inference, Jenkins или новые источники.
Трек не закрывает отрицательную статистическую приёмку v1.

## Существующая точка интеграции

`RunBundleStore.readAnalysis(runId, analysisId)` проверяет сохранённый analysis
по identity и manifest всех его артефактов. `readAnalysisDocuments` предоставляет
`analysis-result.json` и `identity.json`. Существующий результат содержит
verdict, validity, coverage, findings, evidence и optional capacity summary.

Нельзя дописывать advice в `analyses/<analysisId>`: это меняет immutable
результат и нарушает проверку полного manifest. Advice хранится отдельно
в пределах того же локального data directory с привязкой к run/analysis
и SHA-256 проверенного immutable analysis manifest. При reload сначала
проверяются analysis и сохранённая hash-привязка; несовпадение отклоняется.
Изменение хранения должно использовать существующие проверки owned paths,
operation lock и atomic publication; новая repository abstraction не нужна.

## Поток первой поставки

1. Пользователь выбирает сохранённый analysis и явно запускает advisory.
2. Backend проверяет целостность analysis и готовность GigaCode runner.
3. Backend формирует bounded allowlisted evidence input и его SHA-256.
4. Изолированный процесс получает только подготовленные данные и versioned
   prompt; модель возвращает structured JSON через stdout.
5. Backend проверяет schema, размер, ссылки и соответствие выбранному analysis.
6. Backend атомарно сохраняет отдельный advice artifact и execution metadata.
7. UI показывает advisory отдельно; reload читает сохранённый advice без
   повторного запуска модели или обращения к источникам метрик.

Реальные аргументы CLI определяются по скачанной Qwen Code 0.21.1 после
capability discovery. Наличие flag не является результатом проверки изоляции.

## Evidence input

Первый scope: один сохранённый analysis. Передаются только выбранные
структурированные факты, coverage/reasons, SLA findings и evidence;
capacity summary при наличии сохраняет исходные bounds и ограничения.
Credentials, connection profiles, process environment, token stores и private
keys запрещены во входе AI. Bounded structured evidence проходит secret
scan/redaction до передачи. SQL/log bodies и произвольные файлы репозитория
также исключены из первой поставки.

Каждая переданная запись получает стабильную ссылку на конкретный artifact
и JSON location в проверенном analysis. Если существующий объект уже имеет
evidence ID, он сохраняется. Ссылки input/response проверяются по фактически
переданному набору, а не только по синтаксису идентификатора.

Статистические CANDIDATE, если включены, помечены как наблюдения с исходными
`NOT_ESTIMATED`, controls/support/clock limitations. Нельзя превращать их
в подтверждённые причины. Отсутствие findings не означает отсутствие проблемы.
Содержимое evidence считается данными, не инструкциями для runner. Это правило
prompt/protocol, не гарантия изоляции: граница обеспечивается отсутствием tools,
доступа к repository/home и посторонних network destinations.

При превышении лимита input нельзя молча отбрасывать обязательный контекст:
либо явно отражённое ограниченное покрытие, либо отказ от запуска.
Конкретные численные пределы фиксируются в implementation plan после проверки
возможностей fork; не наследуются неявно из его пользовательской конфигурации.

## Advice и provenance

Планируется отдельный versioned `ai-advice.v1` contract, без изменения
`analysis-result.v1`. JSON Schema и точные HTTP/CLI параметры фиксируются
в implementation plan перед production edits.

Ответ модели содержит summary, hypotheses, recommendations, caveats
и evidence references. Наблюдение, возможное объяснение и рекомендуемая
проверка представлены раздельно. Evidence references обязательны для
содержательных гипотез; ограничения анализа видны рядом с ними.

Backend, а не модель, добавляет run/analysis identity, analysis manifest hash,
input/prompt hashes,
runner/model/prompt version, invocation identity, duration, exit status
и validation result. Advice не содержит нового canonical verdict и не
изменяет исходные facts/findings/capacity.

Schema validation не доказывает истинность текста. Нужны отдельно проверенные
правила представления, явная маркировка advisory и fixtures против усиления
недоказанных claims. Произвольный процент уверенности не вводится.

## Граница запуска и ошибки

Сохраняются требования раздела 20 исходного дизайна:

- OS sandbox с ephemeral home/config/CWD, без доступа к исходному repository
  и пользовательскому home; read-only evidence mount.
- Очищенные inherited instructions, hooks, extensions, MCP configuration
  и environment; только allowlisted prompt/config/variables.
- Нет shell, browser и filesystem-write tools. Network только к разрешённому
  model endpoint; unrestricted/yolo mode запрещён.
- Ограничены execution timeout, model/tool budget и output size.
- Probe проверяет фактические границы, не только имена flags.

Если граница не подтверждена, состояние `UNAVAILABLE`, запуск модели запрещён.
Timeout, nonzero exit, oversized/invalid output и неизвестные evidence refs
дают `FAILED` с безопасной причиной; основной отчёт остаётся доступен.
Stdout/stderr ограничены по размеру. Raw process diagnostics не публикуются
и не сохраняются автоматически: возможны prompt, paths и auth details.
Ошибки не запускают бесконтрольные повторные model requests.

Результат первоначального read-only discovery: в пользовательском npm найден
`@qwen-code/qwen-code` версии **0.21.5**, upstream repository
`https://github.com/QwenLM/qwen-code.git`, executable `qwen`.
Это другая сборка; она не используется для новой интеграции.
После разрешения пользователя отдельно скачан `@qwen-code/qwen-code@0.21.1`
из `https://registry.npmjs.org`, в
`build/ai-runner/qwen-code-0.21.1/node_modules/@qwen-code/qwen-code`.
Использованы `--ignore-scripts --omit=optional`; production dependencies
LT Verdict не изменены. Package metadata подтвердили version `0.21.1`.
SHA-256 `cli-entry.js`:
`1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`.
Lockfile сохранён рядом с изолированной установкой. Runtime behavior
и обязательные границы проверяются отдельно; скачивание их не подтверждает.

## Проверяемый результат поставки

1. Explicit запуск по сохранённому analysis; никакого автоматического AI
   при обычном анализе, открытии или export.
2. Валидный fake runner output сохраняется и читается после reload;
   неизвестные ссылки и неверная schema отклоняются.
3. При успехе, ошибке, timeout и `UNAVAILABLE` байты/hash исходного analysis
   и deterministic verdict неизменны.
4. Process не видит запрещённые файлы/context/tools и не может обращаться
   к запрещённым network destinations; это подтверждено для реального fork,
   включая malicious-evidence/prompt-injection и canary fixtures.
5. Fake-runner tests проверяют контракты и failure handling, но не заменяют
   real-fork capability probe и явный live smoke-test.
6. UI явно показывает advisory, ограничения и причины отказа;
   сохранённый результат доступен без нового model request.

## Последовательность и параллельность

Сейчас: read-only discovery скачанной Qwen Code 0.21.1, разрешённой пользователем
как замена GigaCode, и проектирование evidence/advice boundary.
Следом: capability probe и выбор минимального
способа исполнения, удовлетворяющего существующему OS isolation contract.
Затем один implementation plan с точными контрактами, лимитами, файлами,
RED/GREEN проверками и необходимым ADR; production dependencies заранее
перечисляются явно. Не добавлять framework, registry или универсальный
multi-provider runner для одного GigaCode use case.

Статистический пилот идёт независимо в новых `tools/correlation_pilot.py`
и собственных artifacts. Его production-контракты не меняются; AI-трек
не ждёт результата пилота для работы над transport/storage/failure handling.
Включение новой headline policy и оценка качества итогового AI narrative
синхронизируются после статистического исследования.

Следующий общий рубеж: сквозная приёмка с настоящими источниками, сохранением,
reload, capacity, comparison и advisory. Jenkins/artifacts, JVM/OpenShift
packs, оставшиеся charts/export и внешние CI/milestone gates сохраняются
в полном MVP; запуск AI не сокращает этот scope.

## Решения первой реализации

- Bounded evidence имеет предел `262144` bytes и строится только из
  `run_validity`, `policy_verdict`, `analysis_coverage`, `findings`, `evidence`
  и optional `capacity_summary` проверенного `analysis-result.json`.
- Model output ограничен `131072` bytes, stderr — `16384` bytes. Внутренний
  лимит Qwen равен `60s`, внешний kill deadline — `65s`; retries отсутствуют.
- Первая поставка хранит одно immutable advice для пары
  `(run_id, analysis_id)`. Повторный generate возвращает сохранённый результат
  без нового model request.
- Runner id — `gigacode-qwen-code`, version — `0.21.1`, CLI entry SHA-256 —
  `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`.
  Exact model — `deepseek/deepseek-v4-flash-0731`; endpoint —
  `https://openrouter.ai/api/v1`.
- Package и CLI flags сами по себе не включают runner. Нужен фактический
  capability probe OS boundary; self-asserted config не является bypass.
- `--max-tool-calls=0` дополняется explicit exclude для фактически обнаруженных
  `read_file`, `edit`, `notebook_edit` и `run_shell_command`; probe обязан
  подтвердить, что init event публикует только `structured_output`.
- Новые production dependencies отсутствуют. Контракт и решение хранения/
  изоляции зафиксированы в `docs/contracts/advice/v1/` и ADR 0010.

Sanitized fake/live capability evidence и незакрытый production image
prerequisite зафиксированы в
[`docs/advisory-ai-qwen-0.21.1-probe.md`](../../advisory-ai-qwen-0.21.1-probe.md).
Успешный probe не меняет `UNAVAILABLE` production status до появления concrete
launcher на утверждённом pinned runtime image.

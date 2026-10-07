# ADR 0027: Локальный режим раннера ИИ без контейнеров (direct)

Дата: 2026-10-08.

Статус: Proposed. В Accepted переводится только по слову владельца. До этого режим
реализован как аддитивный и выключен по умолчанию; без
`LT_VERDICT_AI_RUNNER_MODE=local` поведение продукта не меняется.

Связь. ADR [0010](0010-advisory-ai-boundary.md) (граница ИИ-разбора, изоляция
контейнера), ADR [0021](0021-advisory-ai-prompt-v2-and-schema-retry.md) (relay,
повтор по схеме, число запросов), ADR
[0023](0023-advisory-ai-consent-removal-and-model-config.md) (файл конфигурации
моделей, `endpoint_host`). Они не переписываются: режим добавляет второй путь
запуска, а потери относительно них перечислены ниже.

## Контекст

Решения владельца (2026-10-07 и 2026-10-08, передано оркестратором). На боевой
машине (Linux, Java, без Docker и PowerShell) ИИ-разбор запускается локальным
headless CLI, форком Qwen Code под названием GigaCode: кроме названия он не
отличается от Qwen Code 0.21.1 (те же флаги, `--json-schema`, событие `init`).
Отдельного endpoint нет и не будет: LLM доступна только через собственный канал
CLI. Авторизация CLI выполняется человеком вручную через браузер, продукту
ничего настраивать и читать не нужно. Бинарник самодостаточен, Node для него не
нужен. Модель задаётся слагом: CLI не умеет её сообщить, слаг берётся из файла
конфигурации моделей. Контур он-прем, внешней сети нет; вопросы безопасности
вторичны. Поэтому relay (пересылка на `endpoint.url`) здесь не применим.

Прежний путь: Kotlin, PowerShell (`tools/advisory_ai_runtime.ps1`), Docker (relay
и Qwen из закреплённого образа) с жёстко закреплёнными версией `0.21.1` и SHA-256.

## Решение

### Д1. Явный режим

`LT_VERDICT_AI_RUNNER_MODE=local` включает режим direct. Не задана: путь Docker,
как раньше. Любое другое значение (`Local`, `docker`) ничего не запускает и даёт
`UNAVAILABLE/RUNNER_ARTIFACT_MISSING`, без подмены другим режимом.

### Д2. Запуск

Kotlin вызывает `bash tools/advisory_ai_runtime_local.sh` (`bash` из `PATH`; на
Windows с Git Bash его путь задаёт `LT_VERDICT_AI_LOCAL_BASH`, потому что голое
`bash` там может оказаться WSL). Параметры идут именованными аргументами. Скрипт
пишет тот же `runtime-result.json` (`advisory-ai-runtime-result.v1`), всегда
завершается с кодом 0, коды причин те же (`TIMEOUT`, `OUTPUT_LIMIT`,
`PROCESS_FAILED`, `RUNNER_ARTIFACT_MISSING`, `RUNNER_ARTIFACT_MISMATCH`).
Разбор вывода CLI делает Kotlin, поэтому Node, `jq` и PowerShell не нужны (Node
нужен только если CLI запускается как `.js`).

CLI получает аргументы контейнерного пути (`tools/advisory_ai_runtime_qwen.sh`):
`--bare --safe-mode --model --system-prompt --input-format=text --output-format=json
--json-schema=@... --exclude-tools=read_file,edit,notebook_edit,run_shell_command
--max-tool-calls=0 --max-wall-time=600s --approval-mode=default
--chat-recording=false --openai-logging=false --telemetry=false`; evidence в
stdin. Отличия: нет `--openai-base-url`; `--auth-type` не передаётся, пока оператор
не задал `LT_VERDICT_AI_LOCAL_AUTH_TYPE`; `--bare` можно отключить
`LT_VERDICT_AI_LOCAL_BARE=0` (см. «Открытый вопрос»).

| Переменная | Назначение |
| --- | --- |
| `LT_VERDICT_AI_LOCAL_QWEN_CMD` | обязательна: абсолютный путь к исполняемому файлу CLI (`.js`, `.mjs`, `.cjs` запускаются через Node) |
| `LT_VERDICT_AI_LOCAL_QWEN_SHA256` | обязательна: SHA-256 этого файла, закреплённый оператором |
| `LT_VERDICT_AI_LOCAL_PASSTHROUGH_ENV` | имена переменных окружения через запятую, которые копируются в окружение CLI (по умолчанию ни одной) |
| `LT_VERDICT_AI_LOCAL_CWD` | рабочий каталог CLI (по умолчанию пустой временный) |
| `LT_VERDICT_AI_LOCAL_NODE` | путь к Node для `.js` (по умолчанию `node`) |
| `LT_VERDICT_AI_LOCAL_AUTH_TYPE` | значение `--auth-type`: `openai`, `qwen-oauth`, `anthropic`, `gemini`, `vertex-ai` |
| `LT_VERDICT_AI_LOCAL_BARE` | `0` убирает `--bare` |
| `LT_VERDICT_AI_LOCAL_BASH` | путь к `bash` |

Аргументы-префиксы к команде не поддерживаются: нужный префикс оператор
оборачивает в исполняемый файл и закрепляет SHA-256 обёртки.

### Д3. Окружение

Окружение CLI очищено (`env -i`): `PATH`, настоящий `HOME` (и `USERPROFILE`,
`APPDATA`, `LOCALAPPDATA`, `SystemRoot`, `WINDIR`, `USER`, `LOGNAME`, `LANG`,
`LC_ALL`), имена из `..._PASSTHROUGH_ENV`, временные каталоги запуска и
фиксированные `QWEN_CODE_API_TIMEOUT_MS=600000`, `QWEN_TELEMETRY_ENABLED=0`,
`QWEN_USAGE_STATISTICS_ENABLED=0`, `NO_BROWSER=1`. `HOME` не подменяется: профиль и
авторизация CLI живут у оператора. Прокси и чужие токены не наследуются. Продукт
ключей не читает и не передаёт; `LT_VERDICT_AI_CREDENTIAL_ENV_FILE` в этом режиме
не используется. Временный каталог удаляется при любом исходе, дерево процессов
убивается по таймауту (605 с) и по отмене.

### Д4. Проверка артефакта

Launcher сверяет SHA-256 файла CLI с закреплённым оператором (несовпадение:
`UNAVAILABLE/RUNNER_ARTIFACT_MISMATCH` до запуска). Версия не закреплена. После
запуска Kotlin проверяет событие `init` из stdout: `tools == ["structured_output"]`,
`mcp_servers == []`, `qwen_code_version` в форме
`^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$` (имя форка допустимо); нарушение даёт
`FAILED/INVALID_OUTPUT`. Проверка апостериорная: `--output-format=json` печатает
события в конце, запрос к этому моменту уже ушёл; она не даёт сохранить совет, но
не предотвращает отправку.

### Д5. Модель и файл конфигурации моделей

Формат `ai-models.v1` не меняется. В режиме direct файл `LT_VERDICT_AI_MODELS_FILE`
обязателен (без него `UNAVAILABLE/MODEL_CONFIG_INVALID`): выбранный слаг передаётся
как `--model=<слаг>`. Блок `endpoint` в файле допустим, но игнорируется; признак
«модель измерена» ложен. Модель, которую CLI назвал в `init`, не сравнивается со
слагом (форк может называть модель иначе): в провенанс пишется выбранный слаг.

### Д6. Провенанс (изменение публичного контракта `ai-advice.v1`)

В `provenance` совета режима direct: `endpoint_host` равен `cli-builtin` (хост не
наблюдается, relay нет); `provider_requests` отсутствует (число запросов не
считается); `runner_version` берётся из `init`, `runner_artifact_sha256` равен
закреплению оператора; `runner_id` остаётся `gigacode-qwen-code`; `model_id`
равен выбранному слагу.

Схема `ai-advice.schema.json`: `runner_version` и `runner_artifact_sha256` из
`const` стали шаблонами; константы `0.21.1` и SHA Qwen Code 0.21.1 требуются, если
`endpoint_host` не `cli-builtin`; `endpoint_host` принимает шаблон `host:port` или
константу `cli-builtin`; при `cli-builtin` `provider_requests` запрещён. Kotlin
(`validProvenance`, `validateStoredAdvice`) проверяет то же. Прежние советы
читаются без изменений. Примеры: `valid/direct-cli-builtin.json`,
`invalid/direct-with-provider-requests.json`,
`invalid/container-with-other-runner-version.json`.

## Что теряется относительно ADR 0010, 0021, 0023

- Сетевая изоляция Qwen (внутренняя сеть Docker, read-only root, лимиты): CLI
  работает процессом пользователя и технически может ходить в сеть.
- Relay: единственный путь к ключу и провайдеру, принудительная политика запроса
  (одна модель, один `structured_output`).
- Подсчёт и ограничение запросов, повтор по схеме (ADR 0021, Д2): повтор продуктом
  не делается, невалидный вывод даёт `INVALID_OUTPUT`, число запросов неизвестно.
- Наблюдаемый адрес назначения (`endpoint_host`) и роль `endpoint.url` (ADR 0023, Д4).
- Закреплённые версия 0.21.1 и образ по digest: вместо них SHA-256 оператора.
- Проверка соответствия модели, названной CLI, и оценка «модель измерена».

Смягчения (коротко): те же защитные флаги CLI (`--bare --safe-mode
--max-tool-calls=0 --exclude-tools`), очищенное окружение без прокси и чужих токенов,
SHA-256 оператора, проверка `init`, очистка evidence от секретов до отправки,
валидатор совета и ссылок, лимиты вывода и таймауты, вердикт не меняется.
Приемлемо для он-прем контура без внешней сети (решения 2026-10-04 и 2026-10-08).

## Открытый вопрос владельцу

`--bare` в Qwen Code 0.21.1 включает пустые настройки (`createMinimalSettings`):
`settings.json` пользователя не читается, `--auth-type` по умолчанию не выбран. Если
GigaCode после ручной авторизации держит выбор способа и ключ в `settings.json`, при
`--bare` он может не войти. Проверено на Qwen Code 0.21.1: при `--bare` без
`--auth-type` CLI завершается с ошибкой даже при заданных `OPENAI_*`
(`PROCESS_FAILED`), с `LT_VERDICT_AI_LOCAL_AUTH_TYPE=openai` работает. Что пробовать по порядку: `LT_VERDICT_AI_LOCAL_AUTH_TYPE`
со способом, которым вошли (в Qwen Code это `qwen-oauth`); затем
`LT_VERDICT_AI_LOCAL_BARE=0` (CLI читает настройки пользователя, включая хуки, MCP и
расширения; `--safe-mode` и проверка `init` остаются). Решение за владельцем; на
боевой машине это проверяется одним кликом «Получить рекомендации».

## Как включать

```bash
export LT_VERDICT_AI_RUNNER_MODE=local
export LT_VERDICT_AI_LOCAL_QWEN_CMD=/opt/gigacode/gigacode
export LT_VERDICT_AI_LOCAL_QWEN_SHA256=<64 hex-символа, вычислены один раз: sha256sum /opt/gigacode/gigacode>
export LT_VERDICT_AI_MODELS_FILE=/etc/lt-verdict/ai-models.json   # слаг модели обязателен
ltv ui
```

SHA-256 записывается явно, а не вычисляется при запуске: иначе закрепление ничего
не закрепляет. Подробности: `docs/user/advisory-ai.md`, раздел «Локальный режим без Docker».

## Проверка

- Unit-тесты Kotlin: выбор режима, проверка настроек, именованные аргументы,
  окружение CLI, разбор вывода CLI, провенанс direct, коды результата; схема и
  примеры `ai-advice`.
- `DirectRunnerIntegrationTest` (запускается при `LTV_TEST_DIRECT_CLI`): реальный
  Qwen Code 0.21.1 против локальной заглушки провайдера через `OPENAI_BASE_URL`.
- Скрипт launcher'а в Git Bash: несовпадение SHA-256, отсутствие файла, таймаут,
  отмена с убийством дерева процессов, успех на реальном Qwen Code 0.21.1.
- Сквозной путь «кнопка, затем совет» (Playwright, сценарий 1) на заглушке.
- Не проверено: настоящий GigaCode и его авторизация, чистый Linux (проверялось в
  Git Bash под Windows).

## Не делается

Relay-вариант локального режима (настоящий endpoint, приватный CA, клиентский
сертификат mTLS до LLM), повтор по схеме на уровне продукта, подсчёт запросов и
проверка `init` до отправки.

# Qwen Code 0.21.1: discovery для advisory AI runner

Дата: 2026-09-06.

Статус: package и CLI contract подтверждены статически; model request,
authentication и runtime isolation не проверялись. Этот документ не разрешает
production-запуск runner.

## Проверенный artifact

Пользователь разрешил заменить GigaCode upstream Qwen Code `0.21.1` с
переименованием Qwen-конвенций в GigaCode-конвенции на уровне будущего wrapper.

- Package: `@qwen-code/qwen-code@0.21.1`.
- Локальный prefix: `build/ai-runner/qwen-code-0.21.1`.
- Entrypoint:
  `build/ai-runner/qwen-code-0.21.1/node_modules/@qwen-code/qwen-code/cli-entry.js`.
- Registry artifact:
  `https://registry.npmjs.org/@qwen-code/qwen-code/-/qwen-code-0.21.1.tgz`.
- Registry integrity:
  `sha512-UTBegRxy3Sy5PbxyVjezHb/pNp24qxrgUnq8V0cNrnlldkvI8iB3/4N3akwhEI3nAFC3Lu1cNPxIV/gIK9L3uw==`.
- Установка выполнена в isolated prefix с `--ignore-scripts --omit=optional`;
  глобальный `qwen` версии `0.21.5` не заменён.

| Файл | SHA-256 |
| --- | --- |
| `package.json` | `acc4c718b6a414aa65077e1de5842c60a04d151dbe8d32bc549531180fd0d924` |
| `cli-entry.js` | `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38` |
| `cli.js` | `978ad6207bf2eb004aec30a894a796da1e56d2470ae4a8a1839a28c4183ddcb3` |

`package.json` объявляет Node.js `>=22.0.0` и sandbox image
`ghcr.io/qwenlm/qwen-code:0.21.1`.

## Статически подтверждённый CLI contract

| Возможность | Подтверждение в downloaded bundle | Ограничение |
| --- | --- | --- |
| Headless input | positional query и `-p/--prompt`; prompt дополняет piped stdin | Фактический stdin/exit contract ещё не запускался |
| Structured output | `--output-format text \| json \| stream-json`;`--json-schema` принимает JSON или `@path` | Wrapper обязан валидировать весь stdout, размер и schema сам |
| Schema termination | `--json-schema` регистрирует synthetic `structured_output` и завершает session после первого валидного call | Нужен runtime probe invalid/oversized/no-call cases |
| Tool prohibition | `--max-tool-calls 0`; первый обычный tool call завершает run с exit code `55`; `structured_output` исключён из budget | Это application guard, не OS isolation |
| Run budget | `--max-wall-time`; превышение заявлено как exit code `55` | Нужен внешний process timeout/kill как независимая граница |
| Persistent history | `--chat-recording=false` | Runtime directory всё равно должен быть ephemeral |
| Provider | `--auth-type`, `--model`, `--openai-base-url`; headless environment поддерживает `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` | Secret нельзя передавать через argv или сохранять в provenance |

`--safe-mode` объявлен как отключение context files, hooks, extensions, skills
и MCP servers. Bundle дополнительно показывает следующие guards:

- project/global `output-language.md` и settings-derived include directories не
  загружаются;
- user/project hooks обнуляются, `disableAllHooks=true`;
- managed memory, auto-skill и web search отключаются;
- settings/project MCP configuration отбрасывается;
- settings approval mode не наследуется, default становится `default`.

Это не абсолютная очистка explicit inputs. Даже в safe mode явно переданные
`--include-directories`, `--allowed-tools`, `--mcp-config` и session-injected MCP
могут сохраниться. Поэтому wrapper не передаёт эти параметры, не использует
ACP/IDE session injection и дополнительно задаёт `--max-tool-calls=0`.

`cli-entry.js` до обычного запуска может читать `~/.qwen/.env`, `~/.env` и
managed-update metadata, если `QWEN_HOME` не задан. Runner обязан начинать с
пустого process environment и явно задавать ephemeral `QWEN_HOME`,
`QWEN_RUNTIME_DIR`, `TEMP`, `TMP` и CWD. Нельзя наследовать `SANDBOX_FLAGS`,
`SANDBOX_MOUNTS`, `SANDBOX_ENV`, proxy variables, launcher variables или
пользовательский `PATH`.

## Кандидат argv для capability probe

Это argv только для процесса, уже помещённого во внешний OS sandbox с
endpoint-only egress. Evidence передаётся через stdin, не через argv и не через
доступ к исходному repository.

```text
<absolute-node>
<absolute-cli-entry.js>
--bare
--safe-mode
--auth-type=openai
--model=<allowlisted-model>
--openai-base-url=<allowlisted-model-endpoint>
--system-prompt=<versioned-system-prompt>
--input-format=text
--output-format=json
--json-schema=@<ephemeral-absolute-schema-path>
--max-tool-calls=0
--max-wall-time=60s
--approval-mode=default
--chat-recording=false
--openai-logging=false
--telemetry=false
```

Секрет передаётся единственной allowlisted environment variable
`OPENAI_API_KEY`. Дополнительно явно задаются ephemeral paths и
`QWEN_TELEMETRY_ENABLED=0`, `QWEN_USAGE_STATISTICS_ENABLED=0`, `NO_BROWSER=1`.
Wrapper разделяет stdout/stderr, ограничивает оба потока и принимает только
валидный JSON result. Это кандидат, а не подтверждённый production command.

## Windows sandbox discovery

Downloaded bundle распознаёт `docker`, `podman` и `sandbox-exec`. Последний
использует macOS Seatbelt и не является Windows-вариантом. На PATH найден Docker
client `29.2.1` по пути
`C:\Program Files\Docker\Docker\resources\bin\docker.exe`; доступ к Docker
daemon из текущего sandbox запрещён, наличие image `0.21.1` не подтверждено.
Image не загружался.

Встроенный Docker launcher Qwen нельзя считать требуемой OS boundary:

- CWD, `QWEN_HOME`, runtime directory и host temp bind-mountятся read-write;
- добавляется `host.docker.internal:host-gateway`;
- network destination allowlist не создаётся;
- при наследовании environment могут добавляться extra mounts/env, включая
  `SANDBOX_FLAGS`, `SANDBOX_MOUNTS`, `SANDBOX_ENV` и cloud credentials;
- отсутствующий image launcher пытается получить через container runtime.

Ephemeral CWD/home/runtime/temp и очищенный environment уменьшают доступную
поверхность, но не заменяют read-only evidence boundary и endpoint-only egress.
Наличие CLI flags само по себе не доказывает filesystem или network isolation.

## Что осталось проверить

Минимальный следующий шаг: отдельный fake-endpoint capability probe во внешнем
OS sandbox. Он должен подтвердить stdin, JSON Schema, stdout/stderr, exit codes,
timeout/cancel, отсутствие inherited QWEN/GigaCode instructions, hooks,
extensions и MCP, запрет обычных tools, read-only canary evidence и блокировку
всех network destinations кроме fake model endpoint. До этого AI module имеет
статус `UNAVAILABLE`.

`--version`/`--help` не засчитаны как runtime evidence: попытка создать
sanitized subprocess остановилась в host PowerShell до запуска child process.
Запуск с унаследованным environment сознательно не выполнялся.

Documentation impact: добавлен только discovery artifact; production API,
contracts, dependencies и user behavior не менялись.

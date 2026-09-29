# Advisory AI: sanitized Qwen Code 0.21.1 probe

Дата: 2026-09-06.

Статус: `PROBE_PASS_NOT_PRODUCTION_READY`.

Raw stdout/stderr, prompt payload, credential value и model response в этот
artifact не сохранялись.

## Provenance

- Package: `@qwen-code/qwen-code@0.21.1` из isolated prefix
  `build/ai-runner/qwen-code-0.21.1`.
- CLI entry SHA-256:
  `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`.
- Docker Engine: `29.2.1`, Linux `amd64`, Docker Desktop `4.62.0`.
- Probe-only cached runtime image:
  `mcr.microsoft.com/playwright/mcp@sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2`.
- Runtime image ID:
  `sha256:7b82f29c6ef83480a97f612d53ac3fd5f30a32df3fea1e06923d4204d3532bb2`;
  created `2026-04-01T00:20:06.411837862Z`.
- Node.js внутри image: `v22.22.2`.
- Credential source: existing Qwen environment entry `OPENAI_API_KEY`; значение
  не выводилось, не передавалось в Qwen container и не сохранялось.

Этот image использован только потому, что он уже находился локально и содержал
Node.js 22. Exact `ghcr.io/qwenlm/qwen-code:0.21.1` локально отсутствовал и не
скачивался. Playwright image не является утверждённой production dependency.

## Isolation boundary

- Qwen container: read-only root, dropped Linux capabilities,
  `no-new-privileges`, bounded CPU/memory/PIDs, unprivileged uid, tmpfs для
  home/runtime/CWD/temp.
- Host mounts: exact Qwen package и exact evidence/schema/prompt/launcher files,
  только read-only. Repository root, user home и Docker socket не mounted.
- Process environment очищен через `env -i`; Qwen получил только ephemeral
  paths, telemetry-disable flags, `NO_BROWSER=1` и relay placeholder key.
- Flags: `--bare`, `--safe-mode`, structured JSON Schema,
  `--exclude-tools=read_file,edit,notebook_edit,run_shell_command`,
  `--max-tool-calls=0`, `--max-wall-time=60s`, no chat recording/logging/
  telemetry; внешний kill deadline `65s`.
- Fake probe: один container с `--network none` и fake endpoint на loopback.
- Live probe: Qwen подключён только к internal Docker network. Отдельный trusted
  relay без host ports принимал только один exact
  `POST /v1/chat/completions` для model
  `deepseek/deepseek-v4-flash-0731`, не повторял request и направлял его только
  в TLS endpoint `openrouter.ai:443/api/v1/chat/completions`. Redirects и
  произвольный destination не поддерживались.
- Temporary containers и network удалены после probe.

## Fake endpoint result

- Exit: `0`; duration внутри Qwen: `373ms`.
- stdout/stderr: `4729`/`153` bytes, оба ниже limits.
- Init: version `0.21.1`, cwd `/work`, tools только `structured_output`,
  `mcp_servers=[]`.
- Evidence canary дошёл как data; inherited-context canary отсутствовал.
- Structured output принят с единственной известной evidence reference.

## Single live result

- Endpoint/model: `https://openrouter.ai/api/v1`,
  `deepseek/deepseek-v4-flash-0731`.
- Relay enforced upstream request count: `1`; повторный live request запрещён и
  не выполнялся.
- Exit: `0`; duration `12906ms`, API duration `12857ms`.
- Tokens: input `1537`, output `1828`, total `3365`.
- stdout/stderr: `18307`/`153` bytes, оба ниже limits.
- Init: tools только `structured_output`, `mcp_servers=[]`, model/version exact.
- Model явно распознала instruction-like canary как untrusted data, не выполнила
  его, не заявила отсутствующее измерение и использовала только переданную
  `analysis-result.json#/evidence/0` reference.
- JSON Schema `ai-advice-output.v1` принят Qwen structured-output boundary.

## Remaining production prerequisite

Probe подтверждает topology, real CLI headless/structured contract и один live
model request, но не создаёт production launcher. Нужен отдельно разрешённый и
закреплённый production runtime image: предпочтительно exact
`ghcr.io/qwenlm/qwen-code:0.21.1` с проверкой embedded CLI hash либо pinned
Node.js 22 image с verified npm bundle. После этого concrete `AdvisoryRunner`
должен перенести fixed relay, stream caps, event extraction и guaranteed cleanup
в production code. До этого UI/API не должны обещать рабочий AI runtime.

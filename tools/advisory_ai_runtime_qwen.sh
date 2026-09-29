#!/bin/sh
set -u

/usr/bin/timeout --signal=KILL 605s /usr/bin/env -i \
  HOME=/home/qwen \
  QWEN_HOME=/home/qwen \
  QWEN_RUNTIME_DIR=/runtime \
  TEMP=/tmp \
  TMP=/tmp \
  OPENAI_API_KEY=relay-placeholder \
  QWEN_CODE_API_TIMEOUT_MS=600000 \
  QWEN_TELEMETRY_ENABLED=0 \
  QWEN_USAGE_STATISTICS_ENABLED=0 \
  NO_BROWSER=1 \
  /usr/local/bin/node /opt/qwen/cli-entry.js \
  --bare \
  --safe-mode \
  --auth-type=openai \
  --model=deepseek-v4-flash-0731 \
  --openai-base-url=http://modelstudio-relay:18080/v1 \
  "--system-prompt=$(cat /input/system-prompt.md)" \
  --input-format=text \
  --output-format=json \
  --json-schema=@/input/output.schema.json \
  --exclude-tools=read_file,edit,notebook_edit,run_shell_command \
  --max-tool-calls=0 \
  --max-wall-time=600s \
  --approval-mode=default \
  --chat-recording=false \
  --openai-logging=false \
  --telemetry=false \
  </input/evidence.json \
  >/tmp/qwen.stdout \
  2>/tmp/qwen.stderr
code=$?

out_size=$(wc -c </tmp/qwen.stdout)
err_size=$(wc -c </tmp/qwen.stderr)
echo "qwen_exit=$code stdout_bytes=$out_size stderr_bytes=$err_size"
echo stdout
head -c 131073 /tmp/qwen.stdout
echo
echo stderr
head -c 16385 /tmp/qwen.stderr
echo

[ "$out_size" -le 131072 ] || exit 91
[ "$err_size" -le 16384 ] || exit 92
exit "$code"

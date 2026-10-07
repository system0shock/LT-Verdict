#!/bin/bash
# ADR 0027: the direct (local) launcher of the advisory AI runner. It starts the operator's CLI (Qwen Code 0.21.x or its fork) as a
# local process with the arguments of tools/advisory_ai_runtime_qwen.sh, without Docker and without the relay: the CLI reaches the
# model through its own configuration and authorization. Same result file as advisory_ai_runtime.ps1 (advisory-ai-runtime-result.v1).
# The launcher always writes the result file and exits 0; the caller reads the status from the file.
set -u
set -m

evidence="" output="" result="" cancel="" cmd="" sha256="" node_cmd="" cwd="" passthrough="" auth_type="openai" model="" deadline_arg=""
while [ $# -ge 2 ]; do
  case "$1" in
    --evidence-path) evidence="$2" ;; --output-path) output="$2" ;; --result-path) result="$2" ;; --cancel-path) cancel="$2" ;;
    --cmd) cmd="$2" ;; --sha256) sha256="$2" ;; --node) node_cmd="$2" ;; --cwd) cwd="$2" ;;
    --passthrough) passthrough="$2" ;; --deadline) deadline_arg="$2" ;; --auth-type) auth_type="$2" ;; --model) model="$2" ;;
    *) ;;
  esac
  shift 2
done

unix_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }
native_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
now_ms() { echo $(( $(date +%s%N) / 1000000 )); }

[ -z "$evidence" ] || evidence="$(unix_path "$evidence")"
[ -z "$output" ] || output="$(unix_path "$output")"
[ -z "$result" ] || result="$(unix_path "$result")"
[ -z "$cancel" ] || cancel="$(unix_path "$cancel")"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
prompt="$repo/docs/contracts/advice/v1/system-prompt.md"
schema="$repo/docs/contracts/advice/v1/ai-advice-output.schema.json"
helper="$here/advisory_ai_runtime_local_parse.mjs"
evidence_limit=262144
output_limit=131072
stderr_limit=16384
cli_deadline=605
[[ "$deadline_arg" =~ ^[0-9]{1,3}$ ]] && [ "$deadline_arg" -ge 1 ] && [ "$deadline_arg" -lt "$cli_deadline" ] && cli_deadline="$deadline_arg"

started=$(now_ms)
status=FAILED failure_code=PROCESS_FAILED unavailable_reason="" stage=initialization
prompt_sha="" observed_model="" observed_version="" cleanup_incomplete=false
root="" pid=""
trap '[ -z "$pid" ] || kill_tree "$pid"; [ -z "$root" ] || rm -rf "$root"; exit 143' TERM INT

kill_tree() {
  local p="$1"
  if [ -r "/proc/$p/winpid" ] && command -v taskkill >/dev/null 2>&1; then
    taskkill //F //T //PID "$(cat "/proc/$p/winpid")" >/dev/null 2>&1
  fi
  kill -KILL -- "-$p" 2>/dev/null
  kill -KILL "$p" 2>/dev/null
}

unavailable() { status=UNAVAILABLE; unavailable_reason="$1"; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else "${node_cmd:-node}" "$(native_path "$helper")" hash "$(native_path "$1")"; fi
}

write_result() {
  local duration=$(( $(now_ms) - started )) failure="null" unavailable_json="null" success=false
  [ "$duration" -ge 0 ] || duration=0
  [ "$status" = SUCCESS ] && success=true
  [ "$status" = FAILED ] && failure="\"$failure_code\""
  [ "$status" = UNAVAILABLE ] && unavailable_json="\"$unavailable_reason\""
  local exit_code=1; [ "$success" = true ] && exit_code=0
  local prompt_json="null"; [ -n "$prompt_sha" ] && prompt_json="\"$prompt_sha\""
  local extra=""
  if [ "$success" = true ]; then
    extra=",\"endpoint_host\":\"cli-builtin\",\"model_id\":\"$observed_model\",\"runner_version\":\"$observed_version\",\"runner_artifact_sha256\":\"$sha256\""
  fi
  printf '{"schema_version":"advisory-ai-runtime-result.v1","status":"%s","duration_ms":%s,"exit_code":%s,"failure_code":%s,"unavailable_reason":%s,"cleanup_incomplete":%s,"stage":"%s","provider_request_count":null,"prompt_sha256":%s%s}' \
    "$status" "$duration" "$exit_code" "$failure" "$unavailable_json" "$cleanup_incomplete" "$stage" "$prompt_json" "$extra" >"$result"
}

run() {
  stage=validate_inputs
  [ -n "$evidence" ] && [ -n "$output" ] && [ -n "$result" ] && [ -n "$cancel" ] || return 0
  [ ! -e "$output" ] || return 0
  [[ -z "$model" || ( "$model" =~ ^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$ && "$model" != *..* && "$model" != *//* ) ]] || return 0
  case "$auth_type" in openai|qwen-oauth|anthropic|gemini|vertex-ai) ;; *) return 0 ;; esac
  [[ "$sha256" =~ ^[0-9a-fA-F]{64}$ ]] || return 0
  sha256="$(printf '%s' "$sha256" | tr 'A-F' 'a-f')"
  local names=() name
  if [ -n "$passthrough" ]; then
    IFS=',' read -r -a names <<<"$passthrough"
    for name in "${names[@]}"; do
      [[ "$name" =~ ^[A-Za-z_][A-Za-z0-9_]{0,63}$ ]] || return 0
    done
  fi
  [ -n "$cmd" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
  cmd="$(unix_path "$cmd")"

  stage=validate_artifacts
  [ -f "$evidence" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
  if [ "$(wc -c <"$evidence")" -gt "$evidence_limit" ]; then failure_code=INPUT_LIMIT; return 0; fi
  [ -f "$prompt" ] && [ -f "$schema" ] && [ -f "$helper" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
  [ -f "$cmd" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
  # The operator pins the artifact by its SHA-256 (ADR 0027); the version is not pinned, the init event is checked afterwards.
  [ "$(sha256_of "$cmd")" = "$sha256" ] || { unavailable RUNNER_ARTIFACT_MISMATCH; return 0; }
  local runner=()
  case "$cmd" in
    *.js|*.mjs|*.cjs)
      command -v "${node_cmd:-node}" >/dev/null 2>&1 || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
      runner=("${node_cmd:-node}" "$(native_path "$cmd")") ;;
    *)
      [ -x "$cmd" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
      runner=("$cmd") ;;
  esac
  command -v "${node_cmd:-node}" >/dev/null 2>&1 || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }

  root="$(mktemp -d "$(dirname "$result")/ltv-ai-local.XXXXXX")" || return 0
  mkdir -p "$root/work" "$root/tmp"
  cp "$prompt" "$root/system-prompt.md"
  prompt_sha="$(sha256_of "$root/system-prompt.md")"
  if [ -n "$cwd" ]; then
    cwd="$(unix_path "$cwd")"
    [ -d "$cwd" ] || { unavailable RUNNER_ARTIFACT_MISSING; return 0; }
  else
    cwd="$root/work"
  fi

  # The environment of the CLI: the operator's PATH and HOME (the CLI keeps its configuration and authorization there), a fixed
  # set of non-secret system variables and the names the operator listed. Nothing else is inherited: no proxy, no foreign tokens.
  local envargs=() var
  for var in PATH HOME USERPROFILE APPDATA LOCALAPPDATA SystemRoot SYSTEMROOT WINDIR USER LOGNAME LANG LC_ALL; do
    [ -n "${!var+x}" ] && envargs+=("$var=${!var}")
  done
  for name in "${names[@]+"${names[@]}"}"; do
    [ -n "${!name+x}" ] && envargs+=("$name=${!name}")
  done
  local tmp_native; tmp_native="$(native_path "$root/tmp")"
  envargs+=("TMPDIR=$tmp_native" "TEMP=$tmp_native" "TMP=$tmp_native"
    QWEN_CODE_API_TIMEOUT_MS=600000 QWEN_TELEMETRY_ENABLED=0 QWEN_USAGE_STATISTICS_ENABLED=0 NO_BROWSER=1)

  # Same arguments as advisory_ai_runtime_qwen.sh; the model and the base URL are the CLI's own unless the operator named a model.
  local cli=(--bare --safe-mode "--auth-type=$auth_type")
  [ -n "$model" ] && cli+=("--model=$model")
  cli+=("--system-prompt=$(cat "$root/system-prompt.md")"
    --input-format=text --output-format=json "--json-schema=@$(native_path "$schema")"
    --exclude-tools=read_file,edit,notebook_edit,run_shell_command --max-tool-calls=0 --max-wall-time=600s
    --approval-mode=default --chat-recording=false --openai-logging=false --telemetry=false)

  stage=run_cli
  local stdout="$root/cli.stdout" stderr="$root/cli.stderr"
  ( cd "$cwd" && exec env -i "${envargs[@]}" "${runner[@]}" "${cli[@]}" <"$evidence" >"$stdout" 2>"$stderr" ) &
  pid=$!
  local t0=$SECONDS timed_out=false oversize=false tick=0
  while kill -0 "$pid" 2>/dev/null; do
    if [ -e "$cancel" ]; then status=CANCELLED; kill_tree "$pid"; break; fi
    if [ $(( SECONDS - t0 )) -ge "$cli_deadline" ]; then timed_out=true; kill_tree "$pid"; break; fi
    tick=$(( tick + 1 ))
    if [ $(( tick % 10 )) -eq 0 ] && [ "$(wc -c <"$stdout")" -gt 1048576 ]; then oversize=true; kill_tree "$pid"; break; fi
    sleep 0.5
  done
  local code=0
  wait "$pid" 2>/dev/null || code=$?
  pid=""
  [ "$status" != CANCELLED ] || return 0
  if [ "$timed_out" = true ]; then failure_code=TIMEOUT; return 0; fi
  if [ "$oversize" = true ] || [ "$(wc -c <"$stdout")" -gt "$output_limit" ]; then failure_code=OUTPUT_LIMIT; return 0; fi
  [ "$code" -eq 0 ] || return 0
  [ "$(wc -c <"$stderr")" -le "$stderr_limit" ] || return 0

  stage=parse_cli_output
  local parsed
  parsed="$("${node_cmd:-node}" "$(native_path "$helper")" result "$(native_path "$stdout")" "$(native_path "$output")")"
  if [[ "$parsed" =~ ^OK\ ([0-9A-Za-z._+-]+)\ ([A-Za-z0-9._:/-]+)$ ]]; then
    observed_version="${BASH_REMATCH[1]}" observed_model="${BASH_REMATCH[2]}"
    [ "$observed_model" != "-" ] || observed_model="${model:-cli-default}"
  else
    failure_code=INVALID_OUTPUT
    return 0
  fi
  status=SUCCESS stage=complete failure_code=""
}

run
[ -z "$pid" ] || kill_tree "$pid"
if [ -n "$root" ]; then
  rm -rf "$root" 2>/dev/null
  [ ! -e "$root" ] || cleanup_incomplete=true
fi
if [ "$cleanup_incomplete" = true ] && [ "$status" = SUCCESS ]; then
  status=FAILED failure_code=PROCESS_FAILED
fi
[ -n "$result" ] && write_result
exit 0

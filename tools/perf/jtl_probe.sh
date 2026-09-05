#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR="$ROOT/tools/perf/generate_jtl.py"
LTV_BIN="${LTV_BIN:-$ROOT/build/install/ltv/bin/ltv}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

for command in python3 taskset timeout /usr/bin/time sha256sum awk nproc env; do
    command -v "$command" >/dev/null || { echo "required command unavailable: $command" >&2; exit 1; }
done
[[ $(nproc) -ge 2 ]] || { echo "two CPUs are required" >&2; exit 1; }
[[ -x "$LTV_BIN" ]] || { echo "ltv distribution missing: $LTV_BIN" >&2; exit 1; }

python3 "$GENERATOR" --rows 1000000 --seed 1 --output "$WORK_DIR/warmup.jtl"
env -u JAVA_OPTS -u LTV_OPTS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS JAVA_TOOL_OPTIONS=-Xmx1536m \
    taskset -c 0,1 timeout 600s "$LTV_BIN" analyze "$WORK_DIR/warmup.jtl" --data-dir "$WORK_DIR/warmup-data" >/dev/null

python3 "$GENERATOR" --rows 10000000 --seed 1 --output "$WORK_DIR/benchmark.jtl"
hashes=()
for run in 1 2 3; do
    data_dir="$WORK_DIR/data-$run"
    result="$WORK_DIR/result-$run.json"
    metrics="$WORK_DIR/metrics-$run.txt"
    timeout 600s taskset -c 0,1 /usr/bin/time -f '%e %M' -o "$metrics" \
        env -u JAVA_OPTS -u LTV_OPTS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS JAVA_TOOL_OPTIONS=-Xmx1536m \
        "$LTV_BIN" analyze "$WORK_DIR/benchmark.jtl" --data-dir "$data_dir" >"$result"
    read -r elapsed rss_kib <"$metrics"
    awk -v elapsed="$elapsed" -v rss_kib="$rss_kib" 'BEGIN { exit !(elapsed <= 600 && rss_kib * 1024 < 2147483648) }' || {
        echo "run $run exceeded limit: elapsed=${elapsed}s peak_rss=${rss_kib}KiB" >&2
        exit 1
    }
    hashes+=("$(sha256sum "$result" | awk '{print $1}')")
    echo "run $run: elapsed=${elapsed}s peak_rss=${rss_kib}KiB sha256=${hashes[-1]}"
done

[[ "${hashes[0]}" == "${hashes[1]}" && "${hashes[1]}" == "${hashes[2]}" ]] || {
    echo "canonical result SHA-256 values differ" >&2
    exit 1
}
echo "canonical result SHA-256: ${hashes[0]}"

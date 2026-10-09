#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GENERATOR="$ROOT/tools/perf/generate_jtl.py"
LTV_BIN="${LTV_BIN:-$ROOT/build/install/ltv/bin/ltv}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# LTV_PROBE_PROFILE selects the input: basic (default, the CI input), realistic-csv or realistic-xml.
# The realistic XML profile uses fewer rows so that its file size stays comparable to the CSV.
case "${LTV_PROBE_PROFILE:-basic}" in
    basic) warmup_rows=1000000; rows=10000000; profile_args=() ;;
    realistic-csv) warmup_rows=1000000; rows=10000000; profile_args=(--profile realistic-csv) ;;
    realistic-xml) warmup_rows=500000; rows=5000000; profile_args=(--profile realistic-xml) ;;
    *) echo "unknown LTV_PROBE_PROFILE: ${LTV_PROBE_PROFILE}" >&2; exit 1 ;;
esac

for command in python3 taskset timeout /usr/bin/time sha256sum awk nproc env; do
    command -v "$command" >/dev/null || { echo "required command unavailable: $command" >&2; exit 1; }
done
[[ $(nproc) -ge 2 ]] || { echo "two CPUs are required" >&2; exit 1; }
[[ -x "$LTV_BIN" ]] || { echo "ltv distribution missing: $LTV_BIN" >&2; exit 1; }

python3 "$GENERATOR" --rows "$warmup_rows" --seed 1 --output "$WORK_DIR/warmup.jtl" ${profile_args[@]+"${profile_args[@]}"}
env -u JAVA_OPTS -u LTV_OPTS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS JAVA_TOOL_OPTIONS=-Xmx1536m \
    taskset -c 0,1 timeout 600s "$LTV_BIN" analyze "$WORK_DIR/warmup.jtl" --data-dir "$WORK_DIR/warmup-data" >/dev/null

python3 "$GENERATOR" --rows "$rows" --seed 1 --output "$WORK_DIR/benchmark.jtl" ${profile_args[@]+"${profile_args[@]}"}
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

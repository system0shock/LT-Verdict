"""Deterministic synthetic 8-hour data set for the UI demo (plan 2026-11-19-demo-dataset-8h).

Standard library only. Every value is a closed formula of the scenario and a fixed seed: no wall clock, no
unseeded random, no iteration over sets. Nothing here runs LT Verdict; `tools/demo_dataset_build.py` does.
Run as `python -m tools.demo_dataset generate --out DIR` from the repository root.
"""

import json
import sys
from pathlib import Path

if __package__ in (None, ""):  # script mode: make the repository root importable
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

DEFAULT_SCENARIO = Path(__file__).resolve().parent.parent / "examples" / "demo-8h" / "scenario.json"
FULL_DURATION_S = 480 * 60
PROFILES = {
    "full": {"duration_s": FULL_DURATION_S, "rps_scale": 1.0, "workloads": 21, "snapshot_step_ms": 15000, "pod_step_ms": 120000},
    "demo": {"duration_s": FULL_DURATION_S, "rps_scale": 0.2, "workloads": 21, "snapshot_step_ms": 15000, "pod_step_ms": 120000},
    "tiny": {"duration_s": 24 * 60, "rps_scale": 0.02, "workloads": 4, "snapshot_step_ms": 15000, "pod_step_ms": 60000},
}


def load_scenario(path: Path = DEFAULT_SCENARIO) -> dict:
    with open(path, encoding="utf-8") as stream:
        return json.load(stream)


def resolve_profile(name: str) -> dict:
    if name not in PROFILES:
        raise ValueError(f"profile must be one of {sorted(PROFILES)}, got {name!r}")
    return {"name": name, **PROFILES[name]}


def stage_bounds(profile: dict, scenario: dict | None = None) -> list:
    """(id, role, from_ms, to_ms) of every stage: the scenario minutes as fractions of the profile duration."""
    scenario = scenario or load_scenario()
    total = sum(stage["minutes"] for stage in scenario["stages"])
    duration_ms = profile["duration_s"] * 1000
    bounds, cumulative = [], 0
    for stage in scenario["stages"]:
        start = duration_ms * cumulative // total
        cumulative += stage["minutes"]
        end = duration_ms * cumulative // total
        if start % profile["snapshot_step_ms"] or end % profile["snapshot_step_ms"]:
            raise ValueError(f"stage {stage['id']} is not aligned to the snapshot step")
        bounds.append((stage["id"], stage["role"], start, end))
    return bounds


# ---------------------------------------------------------------- JTL (Task 2)

import math
from datetime import datetime, timezone
from hashlib import sha256

MASK = (1 << 64) - 1
JTL_HEADER = "timeStamp,elapsed,label,responseCode,success,failureMessage\n"
# Quantile function of the elapsed time as a multiple of the p95 of the second: (u, factor), linear in between.
QUANTILES = ((0.0, 0.2), (0.5, 0.45), (0.95, 1.0), (0.99, 1.35), (1.0, 2.0))
FAILURES = (("500", "Response assertion failed: expected 200 got 500"), ("503", "Response assertion failed: service unavailable"))
SUCCESS_CODES = {"POST /checkout": "201", "POST /payment/confirm": "204", "POST /login": "200"}


def mix(*parts: int) -> int:
    """splitmix64 over the parts: the only source of variation."""
    x = 0x9E3779B97F4A7C15
    for part in parts:
        x = (x + part + 0x9E3779B97F4A7C15) & MASK
        x ^= x >> 30
        x = (x * 0xBF58476D1CE4E5B9) & MASK
        x ^= x >> 27
        x = (x * 0x94D049BB133111EB) & MASK
        x ^= x >> 31
    return x


def start_epoch_ms(release: dict) -> int:
    moment = datetime.strptime(release["start"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    return int(moment.timestamp()) * 1000


def load_multiplier(profile: dict, t_ms: int, scenario: dict | None = None) -> int:
    """Load multiplier in permille (piecewise linear over the stages of the mockup scenario)."""
    scenario = scenario or load_scenario()
    load = scenario["load"]
    start, top = load["start_multiplier_permille"], load["step_multiplier_permille"]
    for stage_id, _role, begin, end in stage_bounds(profile, scenario):
        if begin <= t_ms < end or (stage_id == "soak" and t_ms >= end):
            span = end - begin
            if stage_id == "ramp":
                return start + (1000 - start) * (t_ms - begin) // span
            if stage_id == "step":
                return 1000 + (top - 1000) * (t_ms - begin) // span
            if stage_id == "recovery":
                return top - (top - 1000) * (t_ms - begin) // span
            return top if stage_id == "plateau-2" else 1000
    return 1000


def knee_progress(profile: dict, t_ms: int, scenario: dict) -> float:
    """Degradation depth after the knee: 0 before it, 1 at the end of the run."""
    duration_ms = profile["duration_s"] * 1000
    knee_ms = duration_ms * scenario["load"]["knee_minute"] // scenario["load"]["total_minutes"]
    if t_ms <= knee_ms:
        return 0.0
    return min(1.0, (t_ms - knee_ms) / (duration_ms - knee_ms))


def degradation(progress: float) -> float:
    """progress ** 1.25 with correctly rounded square roots only, so the value is the same on every platform."""
    return progress * math.sqrt(math.sqrt(progress))


def release_transactions(release: dict, scenario: dict) -> list:
    """Transactions of a release with its own checkout level; without checkout the transaction is absent."""
    result = []
    for transaction in scenario["transactions"]:
        item = dict(transaction)
        if item["name"] == "POST /checkout":
            if not release["checkout"]:
                continue
            item["p95_ms"] = release["checkout_p95_ms"]
            item["error_permille"] = release["checkout_error_permille"]
        result.append(item)
    return result


def p95_model(tx: dict, release: dict, profile: dict, t_ms: int, scenario: dict) -> float:
    """p95 (ms) of one transaction at time t: base, a small load effect, growth after the knee when the release has one."""
    multiplier = load_multiplier(profile, t_ms, scenario)
    value = tx["p95_ms"] * (1000 + 120 * (multiplier - 1000) // 1000) / 1000
    if release["knee"]:
        value += 1900 * tx["knee_factor_permille"] / 1000 * degradation(knee_progress(profile, t_ms, scenario))
    return value


def error_probability(tx: dict, release: dict, profile: dict, t_ms: int, scenario: dict) -> float:
    value = tx["error_permille"] / 1000
    if release["knee"]:
        value += tx["knee_error_permille"] / 1000 * degradation(knee_progress(profile, t_ms, scenario))
    return value


def quantile_factor(u: float) -> float:
    for (u0, f0), (u1, f1) in zip(QUANTILES, QUANTILES[1:]):
        if u <= u1:
            return f0 + (f1 - f0) * (u - u0) / (u1 - u0)
    return QUANTILES[-1][1]


def write_jtl(path: Path, release: dict, profile: dict, scenario: dict) -> str:
    """Writes load.jtl and returns its SHA-256. Per second each transaction gets the difference of the rounded-up
    cumulative intensities (no random numbers); elapsed and errors come from splitmix64 of (release seed, row number)."""
    transactions = release_transactions(release, scenario)
    start = start_epoch_ms(release)
    digest = sha256()
    knee_drop = scenario["load"]["knee_rps_drop_permille"] / 1000
    base_rps = scenario["load"]["plateau_rps"] * profile["rps_scale"]
    cumulative = [0.0] * len(transactions)
    row_number = 0
    with open(path, "wb") as stream:

        def emit(text: str) -> None:
            data = text.encode("utf-8")
            digest.update(data)
            stream.write(data)

        emit(JTL_HEADER)
        for second in range(profile["duration_s"]):
            t_ms = second * 1000
            factor = base_rps * load_multiplier(profile, t_ms, scenario) / 1000
            if release["knee"]:
                factor *= 1 - knee_drop * knee_progress(profile, t_ms, scenario)
            events = []
            for index, tx in enumerate(transactions):
                before = cumulative[index]
                cumulative[index] = before + factor * tx["share"]
                count = math.ceil(cumulative[index]) - math.ceil(before)
                for k in range(count):
                    events.append(((index * 125 + k * 1000 // count) % 1000, index))
            events.sort()
            lines = []
            for offset, index in events:
                tx = transactions[index]
                row_number += 1
                h = mix(release["seed"], row_number)
                elapsed = max(1, int(p95_model(tx, release, profile, t_ms, scenario) * quantile_factor((h % 1000000) / 1000000)))
                if ((h >> 20) % 1000000) / 1000000 < error_probability(tx, release, profile, t_ms, scenario):
                    code, message = FAILURES[(h >> 44) & 1]
                    lines.append(f"{start + t_ms + offset},{elapsed},{tx['name']},{code},false,{message}\n")
                else:
                    code = SUCCESS_CODES.get(tx["name"], "200")
                    lines.append(f"{start + t_ms + offset},{elapsed},{tx['name']},{code},true,\n")
            if lines:
                emit("".join(lines))
    return digest.hexdigest()


# ---------------------------------------------------------------- resource snapshots (Task 3)

from tools import stats_validation
from tools.pod_view_adapter import Raw, dumps

MAX_SNAPSHOT_SERIES = 1024
MAX_SNAPSHOT_CELLS = 1500000
MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
MAX_SNAPSHOT_POINTS = 100000
NOISE_SIZE = 4096
APP, SIDECAR, DB_APP = "app", "istio-proxy", "postgres"
# key of the pack document -> series metric, unit, aggregation (interval_max is the peak variant of the document)
# and decimals of the fixed-point cell value
SIGNALS = {
    "cpu_limit_ratio": {"metric": "openshift_container_cpu_limit_ratio", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
    "memory_limit_ratio": {"metric": "openshift_container_memory_limit_ratio", "unit": "ratio", "aggregation": "interval_max", "decimals": 3},
    "oom": {"metric": "openshift_oom", "unit": "events/s", "aggregation": "interval_rate", "decimals": 6},
    "restarts": {"metric": "openshift_restarts", "unit": "events/s", "aggregation": "interval_rate", "decimals": 6},
    "cpu_throttling": {"metric": "openshift_cpu_throttling", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
    "unavailable_replicas": {"metric": "openshift_unavailable_replicas", "unit": "count", "aggregation": "interval_max", "decimals": 0},
    "pod_imbalance": {"metric": "openshift_pod_imbalance", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
    "sidecar_memory_limit_ratio": {"metric": "openshift_sidecar_memory_limit_ratio", "unit": "ratio", "aggregation": "interval_max", "decimals": 3},
    "sidecar_cpu_throttling": {"metric": "openshift_sidecar_cpu_throttling", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
    "jvm_old_gen_used": {"metric": "jvm_old_gen_used", "unit": "bytes", "aggregation": "interval_mean", "decimals": 0},
    "jvm_gc_pause": {"metric": "jvm_gc_pause", "unit": "s", "aggregation": "interval_max", "decimals": 3},
    "jvm_thread_count": {"metric": "jvm_thread_count", "unit": "count", "aggregation": "interval_mean", "decimals": 0},
    "jvm_process_cpu": {"metric": "jvm_process_cpu", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
    "jvm_pool_saturation": {"metric": "jvm_pool_saturation", "unit": "ratio", "aggregation": "interval_mean", "decimals": 3},
}
SIGNAL_ORDER = tuple(SIGNALS)
MIB = 1048576
_NOISE: dict = {}


def noise_table(seed: int) -> list:
    """4096 small deterministic offsets (-10..10), doubled so that any window of `points` cells can be sliced from it."""
    if seed not in _NOISE:
        table = [mix(seed, index) % 21 - 10 for index in range(NOISE_SIZE)]
        _NOISE[seed] = table + table
    return _NOISE[seed]


def token(value: int, decimals: int) -> Raw:
    """A fixed-point integer as a number token without trailing zeros: 450 with 3 decimals is 0.45."""
    if decimals == 0:
        return Raw(str(value))
    whole, fraction = divmod(abs(value), 10**decimals)
    text = f"{whole}.{fraction:0{decimals}d}".rstrip("0").rstrip(".")
    return Raw(("-" if value < 0 else "") + text)


def check_snapshot_limits(series: int, points: int) -> None:
    """The limits of ADR 0014 (the byte limit is checked on the text), before anything is written."""
    if series > MAX_SNAPSHOT_SERIES or points > MAX_SNAPSHOT_POINTS or series * points > MAX_SNAPSHOT_CELLS:
        raise ValueError(f"{series} series of {points} cells exceed the snapshot limits ({MAX_SNAPSHOT_SERIES} series, {MAX_SNAPSHOT_CELLS} cells)")


def workload_list(profile: dict, scenario: dict) -> list:
    """(name, pods, is_database) of the workloads of the profile in a fixed order: services first, database last."""
    items = [(item["name"], item["pods"], False) for item in scenario["services"]]
    items.append((scenario["database"]["name"], scenario["database"]["pods"], True))
    if profile["workloads"] >= len(items):
        return items
    wanted = scenario["tiny_workloads"][: profile["workloads"]]
    return [item for item in items if item[0] in wanted]


def trend_slope(profile: dict, scenario: dict, key: str) -> float:
    """Planted slope in series units per second; a short profile compresses the same change into its duration."""
    return scenario["trend_slopes"][key] * FULL_DURATION_S / profile["duration_s"]


def minute_cell(profile: dict, scenario: dict, minute: int) -> int:
    """Snapshot cell of a minute of the full-length scenario."""
    points = profile["duration_s"] * 1000 // profile["snapshot_step_ms"]
    return minute * points // scenario["load"]["total_minutes"]


def simulate_arm(arm: str, release: dict, profile: dict, scenario: dict) -> dict:
    """Every pod row at every snapshot cell (fixed-point integers) and the service series folded from them.

    A service series is the worst pod and container of the service in the cell, as the platform signals fold it
    (docs/user/platform-metric-packs.md); pod-view takes the same pod rows averaged over its columns."""
    step_ms = profile["snapshot_step_ms"]
    points = profile["duration_s"] * 1000 // step_ms
    if points > NOISE_SIZE:
        raise ValueError(f"{points} cells exceed the {NOISE_SIZE} cells of the noise table")
    step_s = step_ms // 1000
    seed = mix(scenario["seed"], release["seed"], scenario["arms"].index(arm))
    noise = noise_table(seed)
    story = scenario["story"] if release["story"] else None
    knee = release["knee"]
    starts = [i * step_ms for i in range(points)]
    lm = [load_multiplier(profile, t + step_ms // 2, scenario) for t in starts]
    dg = [int(1000 * degradation(knee_progress(profile, t + step_ms // 2, scenario))) if knee else 0 for t in starts]
    load_factor = [400 + 6 * value // 10 for value in lm]
    pool = set(scenario["pool_services"])
    leak_phase, restart_cells = [0] * points, []
    leak_service = leak_pod = throttle_service = None
    if story:
        leak = story["memory_leak"]
        leak_service, leak_pod = (leak["service"], leak["pod_index"]) if arm == leak["arm"] else (None, None)
        throttle_service = story["throttled"]["service"] if arm == story["throttled"]["arm"] else None
        cycle = max(1, minute_cell(profile, scenario, leak["cycle_minutes"]))
        restart_cells = [minute_cell(profile, scenario, minute) for minute in leak["restart_minutes"]]
        for i in range(points):
            before = [cell for cell in restart_cells if cell <= i]
            last = before[-1] if before else restart_cells[0] - cycle
            leak_phase[i] = min(1000, (i - last) * 1000 // cycle)
    pods, services = [], {}
    for service_index, (name, pod_count, is_database) in enumerate(workload_list(profile, scenario)):
        containers = [DB_APP] if is_database else [APP, SIDECAR]
        folded = {key: [0] * points for key in ("cpu", "memory", "throttle", "side_memory", "side_throttle")}
        usage_min, usage_max, usage_sum = [10**9] * points, [0] * points, [0] * points
        for k in range(1, pod_count + 1):
            pod_seed = mix(seed, service_index, k)

            def nz(salt: int) -> list:
                offset = mix(pod_seed, salt) % NOISE_SIZE
                return noise[offset : offset + points]

            hot = bool(story) and name == story["hot_pod"]["service"] and k == story["hot_pod"]["pod_index"]
            throttled = name == throttle_service and k in story["throttled"]["pod_indexes"]
            leaking = name == leak_service and k == leak_pod
            cpu_base = 250 + mix(pod_seed, 1) % 300
            if hot:
                cpu_base = 700 + mix(pod_seed, 6) % 100  # the other pods stay at 250..549
            cpu = [min(1000, max(0, cpu_base * f // 1000 + n)) for f, n in zip(load_factor, nz(10))]
            if name == "checkout-api" and knee:
                cpu = [min(1000, value + 250 * d // 1000) for value, d in zip(cpu, dg)]
            memory_base = 400 + mix(pod_seed, 2) % 200
            memory = [min(1000, max(0, memory_base + 30 * i // points + n)) for i, n in enumerate(nz(11))]
            if leaking:
                memory = [min(1000, max(0, 300 + 700 * phase // 1000 + n // 2)) for phase, n in zip(leak_phase, nz(11))]
            throttle_base = 10 + mix(pod_seed, 3) % 30
            throttle = [max(0, throttle_base * m // 1000 + n // 2) for m, n in zip(lm, nz(12))]
            if throttled:
                from_cell = minute_cell(profile, scenario, story["throttled"]["from_minute"])
                throttle = [value + (400 + 200 * d // 1000 if i >= from_cell else 0) for i, (value, d) in enumerate(zip(throttle, dg))]
            rows = {(containers[0], "cpu"): cpu, (containers[0], "memory"): memory, (containers[0], "throttle"): [min(1000, v) for v in throttle]}
            usage = list(cpu)
            if not is_database:
                side_cpu = [min(1000, (60 + mix(pod_seed, 4) % 60) * f // 1000 + n // 2) for f, n in zip(load_factor, nz(13))]
                side_base = 250 + mix(pod_seed, 5) % 100
                side_memory = [min(1000, side_base + n) for n in nz(14)]
                if name == scenario["story"]["sidecar_service"]:
                    slope = trend_slope(profile, scenario, "sidecar_memory_limit_ratio@gateway") * 1000
                    side_memory = [min(1000, value + int(slope * i * step_s)) for i, value in enumerate(side_memory)]
                side_throttle = [max(0, 5 + n // 3) for n in nz(15)]
                rows.update({(SIDECAR, "cpu"): side_cpu, (SIDECAR, "memory"): side_memory, (SIDECAR, "throttle"): side_throttle})
                usage = [a + b // 2 for a, b in zip(cpu, side_cpu)]
            pods.append({"service": name, "pod": f"{name}-{arm.lower()}{k}", "containers": containers, "rows": rows, "usage": usage})
            for (container, metric), values in rows.items():
                folded[metric] = [max(a, b) for a, b in zip(folded[metric], values)]
                if container == SIDECAR and metric in ("memory", "throttle"):
                    folded["side_" + metric] = [max(a, b) for a, b in zip(folded["side_" + metric], values)]
            usage_min = [min(a, b) for a, b in zip(usage_min, usage)]
            usage_max = [max(a, b) for a, b in zip(usage_max, usage)]
            usage_sum = [a + b for a, b in zip(usage_sum, usage)]
        series = {
            "cpu_limit_ratio": folded["cpu"],
            "memory_limit_ratio": folded["memory"],
            "oom": [0] * points,
            "restarts": [0] * points,
            "cpu_throttling": folded["throttle"],
            "unavailable_replicas": [0] * points,
            "pod_imbalance": [(hi - lo) * 1000 * pod_count // max(1, total) for hi, lo, total in zip(usage_max, usage_min, usage_sum)],
        }
        if name == leak_service:
            for cell in restart_cells:
                series["restarts"][cell] = 1000000 // step_s
        if not is_database:
            series["sidecar_memory_limit_ratio"] = folded["side_memory"]
            series["sidecar_cpu_throttling"] = folded["side_throttle"]
            sn = noise[mix(seed, service_index, 90) % NOISE_SIZE :][:points]
            old_gen = (400 + mix(seed, service_index, 91) % 400) * MIB
            series["jvm_old_gen_used"] = [old_gen + n * MIB // 5 for n in sn]
            series["jvm_gc_pause"] = [max(1, 15 + mix(seed, service_index, 92) % 25 + n // 2) for n in sn]
            if name == leak_service:
                series["jvm_gc_pause"] = [value + 900 * phase**3 // 10**9 for value, phase in zip(series["jvm_gc_pause"], leak_phase)]
            series["jvm_thread_count"] = [100 + mix(seed, service_index, 93) % 60 + n // 3 + m // 50 for n, m in zip(sn, lm)]
            series["jvm_process_cpu"] = [200 + mix(seed, service_index, 94) % 150 + m // 10 + n for n, m in zip(sn, lm)]
            if name in pool:
                series["jvm_pool_saturation"] = [max(0, 50 + mix(seed, service_index, 95) % 100 + 20 * i // points + n) for i, n in enumerate(sn)]
            for key, unit_scale in (("jvm_old_gen_used", 1), ("jvm_thread_count", 1), ("jvm_process_cpu", 1000), ("jvm_pool_saturation", 1000)):
                trend_key = f"{key}@{name}"
                if trend_key in scenario["trend_slopes"]:
                    slope = trend_slope(profile, scenario, trend_key) * unit_scale
                    series[key] = [value + int(slope * i * step_s) for i, value in enumerate(series[key])]
            if "jvm_pool_saturation" in series:
                series["jvm_pool_saturation"] = [min(1000, value) for value in series["jvm_pool_saturation"]]
        services[name] = series
    return {"arm": arm, "points": points, "pods": pods, "services": services}


def build_snapshot(arm: str, release: dict, profile: dict, scenario: dict, load_sha256: str, model: dict | None = None) -> dict:
    """resource-snapshot.v1 of one arm: one series per (signal, workload), rows sorted by id, the `arm` label in each."""
    model = model or simulate_arm(arm, release, profile, scenario)
    rows = []
    for name, series in model["services"].items():
        for key in SIGNAL_ORDER:
            if key not in series:
                continue
            signal = SIGNALS[key]
            rows.append(
                {
                    "id": f"{name}.{signal['metric']}",
                    "metric": signal["metric"],
                    "unit": signal["unit"],
                    "entity": name,
                    "role": "system",
                    "aggregation": signal["aggregation"],
                    "labels": {"arm": arm, "namespace": scenario["namespace"]},
                    "values": [token(value, signal["decimals"]) for value in series[key]],
                }
            )
    rows.sort(key=lambda row: row["id"].encode("utf-8"))
    check_snapshot_limits(len(rows), model["points"])
    start = start_epoch_ms(release)
    snapshot = {
        "schema_version": "resource-snapshot.v1",
        "load_input_sha256": load_sha256,
        "start_epoch_ms": start,
        "step_ms": profile["snapshot_step_ms"],
        "point_count": model["points"],
        "series": rows,
        "windows": [
            {"id": stage_id, "from_epoch_ms": start + begin, "to_epoch_ms": start + end}
            for stage_id, role, begin, end in stage_bounds(profile, scenario)
            if role == "steady"
        ],
        "rules": [],
        "provenance": {"source_kind": "synthetic", "query_semantics": "declared interval aggregation", "clock_alignment": "declared_aligned"},
    }
    if len(snapshot_text(snapshot).encode("utf-8")) > MAX_SNAPSHOT_BYTES:
        raise ValueError("the snapshot would exceed 32 MiB")
    return snapshot


def snapshot_text(snapshot: dict) -> str:
    return dumps(snapshot) + "\n"


def snapshot_hash(snapshot: dict) -> str:
    """Semantic hash of the snapshot (tools/stats_validation.snapshot_hash) over the text as the core reads it."""
    from decimal import Decimal

    parsed = json.loads(snapshot_text(snapshot), parse_float=Decimal, parse_int=Decimal)
    return stats_validation.snapshot_hash(parsed)


# ---------------------------------------------------------------- policies and plans (Task 4)

from decimal import Decimal

THROUGHPUT_RULE = "throughput_rps"


def plain_number(value: Decimal) -> Raw:
    """A decimal as a JSON number token without an exponent."""
    text = format(value.normalize(), "f")
    return Raw(text if "." not in text else text.rstrip("0").rstrip("."))


def business_rules(scenario: dict, profile: dict) -> list:
    limits = scenario["policy"]
    overall = {"kind": "overall"}
    checkout = {"kind": "transaction", "name": "POST /checkout"}
    threshold = plain_number(Decimal(limits["throughput_min_rps_full"]) * Decimal(str(profile["rps_scale"])))
    return [
        {"id": "checkout-p95", "metric": "response_time_p95_ms", "operator": "lte", "threshold": limits["p95_checkout_ms"], "scope": checkout, "window_ids": ["soak"]},
        {"id": "checkout-errors", "metric": "error_rate_ratio", "operator": "lte", "threshold": plain_number(Decimal(str(limits["error_checkout"]))), "scope": checkout, "window_ids": ["soak"]},
        {
            "id": "payment-confirm-p95",
            "metric": "response_time_p95_ms",
            "operator": "lte",
            "threshold": limits["p95_payment_ms"],
            "scope": {"kind": "transaction", "name": "POST /payment/confirm"},
            "window_ids": ["soak"],
        },
        {"id": "overall-errors", "metric": "error_rate_ratio", "operator": "lte", "threshold": plain_number(Decimal(str(limits["error_overall"]))), "scope": overall, "window_ids": ["soak"]},
        {"id": "overall-throughput", "metric": THROUGHPUT_RULE, "operator": "gte", "threshold": threshold, "scope": overall, "window_ids": ["soak"]},
    ]


def policy_defaults() -> dict:
    return {"sample_floor": 20, "min_samples": 50}


def build_policy(scenario: dict, profile: dict) -> dict:
    """The nine rules of the demo: five business rules and four platform rules (one of them the coverage rule)."""

    def platform(rule_id, key, threshold, cells, effect, windowed=True):
        signal = SIGNALS[key]
        rule = {
            "id": rule_id,
            "signal": signal["metric"],
            "scope": {"kind": "all_services"},
            "operator": "gt",
            "threshold": threshold,
            "unit": signal["unit"],
            "aggregation": signal["aggregation"],
            "min_consecutive_cells": cells,
            "effect": effect,
        }
        if windowed:
            rule["window_ids"] = ["soak"]
        return rule

    return {
        "schema_version": "policy.v1",
        "policy_id": "checkout-demo-8h",
        "defaults": policy_defaults(),
        "rules": business_rules(scenario, profile),
        "platform_services": [item[0] for item in workload_list(profile, scenario)],
        "platform_coverage": {"signal": SIGNALS["unavailable_replicas"]["metric"]},
        "platform_rules": [
            platform("restarts", "restarts", 0, 1, "sla"),
            platform("memory-limit", "memory_limit_ratio", Raw("0.9"), 2, "diagnostic"),
            platform("cpu-throttling", "cpu_throttling", Raw("0.25"), 4, "diagnostic"),
            platform("unavailable-replicas", "unavailable_replicas", 0, 1, "sla", windowed=False),
        ],
    }


def build_policy_stages(scenario: dict, profile: dict) -> dict:
    """The five business rules only: with platform_rules at the level sla a missing snapshot blocks the verdict."""
    return {"schema_version": "policy.v1", "policy_id": "checkout-demo-8h-stages", "defaults": policy_defaults(), "rules": business_rules(scenario, profile)}


def build_load_stages(profile: dict, scenario: dict | None = None) -> dict:
    return {
        "schema_version": "load-stages.v1",
        "stages": [{"id": sid, "role": role, "from_offset_ms": begin, "to_offset_ms": end} for sid, role, begin, end in stage_bounds(profile, scenario)],
    }


def build_trend_plan(snapshot_sha256: str, scenario: dict, profile: dict) -> dict:
    """Five checks in the soak window; the magnitude gate is half of the planted slope, so the planted trend passes it."""
    checks = []
    for key, slope in scenario["trend_slopes"].items():
        signal, entity = key.split("@")
        planted = Decimal(str(slope)) * Decimal(FULL_DURATION_S) / Decimal(profile["duration_s"])
        checks.append(
            {
                "id": f"{entity}-{signal}-growth",
                "series_id": f"{entity}.{SIGNALS[signal]['metric']}",
                "window_id": "soak",
                "direction": "increase",
                "min_cells": 30,
                "magnitude_gate": {"min_slope_units_per_second": plain_number(planted / 2), "min_split_half_shift_pct": 5},
            }
        )
    return {"schema_version": "trend-plan.v1", "resource_snapshot_sha256": snapshot_sha256, "checks": checks}


def build_correlation_plan(snapshot_sha256: str, arm: str, scenario: dict) -> dict:
    """Pairs of a resource series of checkout-api (and, on arm B, payments-svc) against the overall p95 in the soak window."""

    def pair(pair_id, service, key, delta, basis):
        return {
            "id": pair_id,
            "resource_series_id": f"{service}.{SIGNALS[key]['metric']}",
            "load_metric": "response_time_p95_ms",
            "window_ids": ["soak"],
            "expected_sign": "positive",
            "max_lag_ms": 0,
            "min_abs_effect": Raw("0.3"),
            "min_resource_delta": Raw(delta),
            "min_load_delta": 50,
            "controls": [{"meaning": "achieved_rps"}],
            "topology_basis": basis,
            "clock_alignment": "declared_aligned",
        }

    pairs = [
        pair("checkout-cpu-latency", "checkout-api", "cpu_limit_ratio", "0.05", "CPU сервиса checkout-api, обслуживающего POST /checkout"),
        pair("checkout-throttling-latency", "checkout-api", "cpu_throttling", "0.05", "троттлинг сервиса checkout-api, обслуживающего POST /checkout"),
    ]
    if arm == scenario["story"]["memory_leak"]["arm"]:
        pairs.append(pair("payments-memory-latency", scenario["story"]["memory_leak"]["service"], "memory_limit_ratio", "0.1", "память payments-svc, вызываемого из POST /checkout"))
    return {"schema_version": "correlation-plan.v1", "resource_snapshot_sha256": snapshot_sha256, "pairs": pairs}


# ---------------------------------------------------------------- pod-view and OpenSearch context (Task 5)

import re

from tools.pod_view_adapter import Observation, assemble

POD_METRICS = {"cpu": "openshift_container_cpu_limit_ratio", "memory": "openshift_container_memory_limit_ratio", "throttle": "openshift_cpu_throttling"}
POD_USAGE_METRIC = "openshift_pod_cpu_usage"
SIDECAR_PATTERN = re.compile(SIDECAR)
ERROR_GROUPS = (
    ("checkout-api", "UpstreamTimeoutException", 30),
    ("payments-svc", "ConnectionPoolTimeout", 25),
    ("gateway", "502 Bad Gateway", 15),
    ("checkout-db", "SlowQueryCancelled", 12),
    ("orders-svc", "RetryExhaustedException", 10),
    ("catalog-svc", "CircuitBreakerOpen", 8),
)


def column_means(values: list, ratio: int, columns: int) -> list:
    """Mean of `ratio` consecutive snapshot cells per column, rounded half up on the fixed-point integer."""
    return [(sum(values[c * ratio : (c + 1) * ratio]) * 2 + ratio) // (2 * ratio) for c in range(columns)]


def build_pod_view(model: dict, snapshot_sha256: str, load_sha256: str, release: dict, profile: dict, scenario: dict) -> dict:
    """pod-view.v1 of one arm from the pod rows of `simulate_arm`; the columns are means of the snapshot cells."""
    ratio = profile["pod_step_ms"] // profile["snapshot_step_ms"]
    if profile["pod_step_ms"] % profile["snapshot_step_ms"] or model["points"] % ratio:
        raise ValueError("the pod step must be a whole multiple of the snapshot step and divide the run")
    columns = model["points"] // ratio
    observations = []
    for pod in model["pods"]:
        for (container, metric), values in pod["rows"].items():
            tokens = [token(v, 3) for v in column_means(values, ratio, columns)]
            observations.append(Observation(scenario["namespace"], pod["service"], pod["pod"], container, POD_METRICS[metric], tokens))
        observations.append(
            Observation(scenario["namespace"], pod["service"], pod["pod"], None, POD_USAGE_METRIC, [token(v, 3) for v in column_means(pod["usage"], ratio, columns)])
        )
    return assemble(
        observations,
        load_sha256=load_sha256,
        snapshot_sha256=snapshot_sha256,
        arm=model["arm"],
        start_ms=start_epoch_ms(release),
        step_ms=profile["pod_step_ms"],
        columns=columns,
        sidecar=SIDECAR_PATTERN,
        select=None,
        qualify=False,
    )


def build_opensearch_errors(load_sha256: str, release: dict, profile: dict, scenario: dict) -> dict:
    """opensearch-errors.v1: an error count per snapshot-sized bin that grows after the knee, split into groups by service and type."""
    step = profile["snapshot_step_ms"]
    start = start_epoch_ms(release)
    bins = profile["duration_s"] * 1000 // step
    noise = noise_table(mix(scenario["seed"], release["seed"], 77))
    weight_total = sum(weight for _service, _type, weight in ERROR_GROUPS)
    group_counts = [0] * len(ERROR_GROUPS)
    group_first, group_last = [None] * len(ERROR_GROUPS), [None] * len(ERROR_GROUPS)
    timeline = []
    for i in range(bins):
        depth = degradation(knee_progress(profile, i * step + step // 2, scenario)) if release["knee"] else 0.0
        count = max(0, 1 + (noise[i] + 10) // 8 + int(60 * depth))
        timeline.append({"from_epoch_ms": start + i * step, "to_epoch_ms": start + (i + 1) * step, "count": count, "rate_per_minute": plain_number(Decimal(count * 60000) / Decimal(step))})
        given = 0
        for g, (_service, _type, weight) in enumerate(ERROR_GROUPS):
            share = count * weight // weight_total
            given += share
            group_counts[g] += share
            if share:
                group_first[g] = start + i * step + 500 if group_first[g] is None else group_first[g]
                group_last[g] = start + (i + 1) * step - 500
        for extra in range(count - given):  # the remainder goes to the heaviest groups in turn
            group_counts[extra % 2] += 1
            group_first[extra % 2] = start + i * step + 500 if group_first[extra % 2] is None else group_first[extra % 2]
            group_last[extra % 2] = start + (i + 1) * step - 500
    total = sum(cell["count"] for cell in timeline)
    groups = [
        {"service": service, "error_type": error_type, "count": group_counts[g], "first_epoch_ms": group_first[g], "last_epoch_ms": group_last[g], "samples": []}
        for g, (service, error_type, _weight) in enumerate(ERROR_GROUPS)
        if group_counts[g]
    ]
    return {
        "schema_version": "opensearch-errors.v1",
        "id": "opensearch-errors:errors",
        "type": "opensearch_errors",
        "load_input_sha256": load_sha256,
        "profile_id": "errors",
        "start_epoch_ms": start,
        "end_epoch_ms": start + bins * step,
        "step_ms": step,
        "total_errors": total,
        "error_rate_per_minute": plain_number((Decimal(total) * 60 / Decimal(profile["duration_s"])).quantize(Decimal("0.000001"))),
        "timeline": timeline,
        "groups": groups,
        "coverage": {
            "status": "COMPLETE",
            "reasons": [],
            "timed_out": False,
            "total_relation": "eq",
            "shards": {"total": 1, "successful": 1, "skipped": 0, "failed": 0},
            "terms": {"group_limit": 50, "returned_groups": len(groups), "sum_other_doc_count": 0, "doc_count_error_upper_bound": 0},
            "samples_per_group_limit": 0,
            "sample_message_bytes_max": 4096,
        },
    }

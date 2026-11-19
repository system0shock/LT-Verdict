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

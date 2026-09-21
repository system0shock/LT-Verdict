"""Deterministic §5 USEFULNESS inputs; this module does not execute reports."""

from copy import deepcopy
from decimal import Decimal, ROUND_HALF_EVEN
import hashlib
import math
import random

try:
    from . import stats_validation as wire
except ImportError:  # Direct script execution has no package parent.
    import stats_validation as wire


REFERENCE_CELLS = 120
EVALUATION_CELLS = 240
CELLS = REFERENCE_CELLS + EVALUATION_CELLS
STEP_MS = 1000
TARGET_RPS = 20


def _correlation_config(pairs, lag):
    return {"kind": "correlation", "pairs": pairs, "max_lag_cells": lag,
            "reference_cells": REFERENCE_CELLS, "evaluation_cells": EVALUATION_CELLS,
            "step_ms": STEP_MS, "target_rps": TARGET_RPS, "min_abs_effect": .3,
            "min_resource_delta": .1, "min_load_delta_ms": 20, "expected_sign": "either"}


def _episode_config(rules):
    return {"kind": "episode", "rules": rules, "reference_cells": REFERENCE_CELLS,
            "evaluation_cells": EVALUATION_CELLS, "step_ms": STEP_MS, "resource_delta": 5,
            "min_duration_ms": 3000, "z_threshold": 3.5, "direction": "either"}


def _comparison_config(delta):
    return {"kind": "comparison", "members": 2, "cells": EVALUATION_CELLS, "step_ms": STEP_MS,
            "requests_per_cell": TARGET_RPS, "errors_per_100_requests": 1,
            "window_id": "evaluation", "conditions_confirmed": True,
            "min_change_percent": 5, "min_error_rate_delta": .001, "current_latency_delta_ms": delta}


def _specifications():
    specs = []
    for family in ("N01", "N02", "N03"):
        specs.extend((family, _correlation_config(pairs, lag)) for pairs in (1, 16) for lag in (0, 10))
    specs.extend([("P01", _correlation_config(16, 0)), ("P02", _correlation_config(16, 10)),
                  ("P03", _correlation_config(16, 10))])
    for family in ("E01", "E02", "E03"):
        specs.extend((family, _episode_config(rules)) for rules in (1, 32))
    specs.extend([("P04", _episode_config(32)), ("P05", _episode_config(32))])
    specs.extend((family, _comparison_config(0)) for family in ("T01", "T02", "T03"))
    specs.extend([("P06", _comparison_config(40)), ("P07", _comparison_config(-40))])
    return specs


def _configuration_id(family, config, seed):
    fields = (f"p{config['pairs']}-l{config['max_lag_cells']}" if config["kind"] == "correlation" else
              f"r{config['rules']}" if config["kind"] == "episode" else
              f"d{config['current_latency_delta_ms']}")
    return f"{family}-{fields}-s{seed}"


def configurations():
    """Return the 28 frozen configurations × seeds 1000..1999, without generating inputs."""
    return [{"id": _configuration_id(family, config, seed), "family": family, "seed": seed,
             "config": deepcopy(config)}
            for family, config in _specifications() for seed in range(1000, 2000)]


def _stream(family, seed, stream):
    name = f"ltv-stats-v1/{family}/{seed}/{stream}".encode("utf-8")
    return random.Random(int.from_bytes(hashlib.sha256(name).digest(), "big"))


def _round(value):
    return float(Decimal(str(value)).quantize(Decimal(".000001"), rounding=ROUND_HALF_EVEN))


def _latent(family, seed, stream, count, process):
    rng = _stream(family, seed, stream)
    if process == "iid":
        return [rng.gauss(0, 1) for _ in range(count)]
    if process == "t3":
        return [rng.gauss(0, 1) / math.sqrt(sum(rng.gauss(0, 1) ** 2 for _ in range(3)) / 3)
                for _ in range(count)]
    if process == "ar1":
        value, scale, values = 0.0, math.sqrt(1 - .8 ** 2), []
        for _ in range(512 + count):
            value = .8 * value + scale * rng.gauss(0, 1)
            values.append(value)
        return values[512:]
    raise ValueError("unknown latent process")


def _process(family):
    return "ar1" if family in {"N02", "P01", "P02", "P03", "E02", "T02", "P04", "P05", "P06", "P07"} else \
        "t3" if family in {"N03", "E03", "T03"} else "iid"


def _positive_latents(configuration):
    """Raw extended P02/P03 latents, exposed solely for the no-wrap semantic test."""
    family, seed = configuration["family"], configuration["seed"]
    if family not in {"P01", "P02", "P03"}:
        raise ValueError("positive correlation family required")
    count = CELLS + 20
    x = _latent(family, seed, "x/0", count, "ar1")
    noise = _latent(family, seed, "noise", count, "iid")
    if family == "P03":
        load = [-x[i + 3] + .25 * noise[i] for i in range(count - 3)] + [0.0] * 3
    elif family == "P02":
        load = [0.0] * 3 + [x[i - 3] + .25 * noise[i] for i in range(3, count)]
    else:
        load = [x[i] + .25 * noise[i] for i in range(count)]
    return {"x": x, "noise": noise, "load": load}


def _correlation_input(configuration):
    family, seed, config = configuration["family"], configuration["seed"], configuration["config"]
    process = _process(family)
    if family in {"P01", "P02", "P03"}:
        raw = _positive_latents(configuration)
        resources = [raw["x"][10:10 + CELLS]]
        load = raw["load"][10:10 + CELLS]
    else:
        resources = []
        load = _latent(family, seed, "y", CELLS, process)
    while len(resources) < config["pairs"]:
        index = len(resources)
        resources.append(_latent(family, seed, f"x/{index}", CELLS, process))
    series = [wire._series([_round(.5 + .2 * math.tanh(value)) for value in values], f"resource-{i:02d}", "ratio")
              for i, values in enumerate(resources)]
    series.append(wire._series([TARGET_RPS] * CELLS, "target", "requests/s"))
    snapshot = wire._snapshot(series, [("reference", 0, REFERENCE_CELLS),
                                       ("evaluation", REFERENCE_CELLS, CELLS)])
    pairs = [{"id": f"pair-{i:02d}", "resource_series_id": f"resource-{i:02d}",
              "load_metric": "response_time_p95_ms", "window_ids": ["evaluation"],
              "expected_sign": config["expected_sign"], "max_lag_ms": config["max_lag_cells"] * STEP_MS,
              "min_abs_effect": config["min_abs_effect"], "min_resource_delta": config["min_resource_delta"],
              "min_load_delta": config["min_load_delta_ms"], "topology_basis": "synthetic independent resources",
              "clock_alignment": "declared_aligned", "controls": [{"meaning": "target_rps", "series_id": "target"}]}
             for i in range(config["pairs"])]
    latencies = [200 + round(40 * math.tanh(value)) for value in load]
    return {"operation": "analysis", "run": wire._run(snapshot, latencies, pairs=pairs)}


def _episode_input(configuration):
    family, seed, config = configuration["family"], configuration["seed"], configuration["config"]
    process = _process(family)
    values = []
    for index in range(config["rules"]):
        signal = [100 + value for value in _latent(family, seed, f"rule/{index}", CELLS, process)]
        if family in {"P04", "P05"} and index == 0:
            adjustment = 10 if family == "P04" else -10
            for cell in range(REFERENCE_CELLS + 80, REFERENCE_CELLS + 100):
                signal[cell] += adjustment
        values.append(wire._series([_round(value) for value in signal], f"rule-{index:02d}"))
    snapshot = wire._snapshot(values, [("reference", 0, REFERENCE_CELLS), ("evaluation", REFERENCE_CELLS, CELLS)])
    rules = [{"id": f"rule-{index:02d}", "signal": {"series_id": f"rule-{index:02d}"},
              "reference_window_id": "reference", "window_id": "evaluation", "direction": config["direction"],
              "min_abs_delta": config["resource_delta"], "min_duration_ms": config["min_duration_ms"],
              "z_threshold": config["z_threshold"]} for index in range(config["rules"])]
    return {"operation": "analysis", "run": wire._run(snapshot, [200] * CELLS, anomalies=rules)}


def _comparison_member(family, seed, member, config):
    latent = _latent(family, seed, member, config["cells"], _process(family))
    snapshot = wire._snapshot([wire._series([_round(100 + value) for value in latent], "resource", "synthetic_unit")],
                              [(config["window_id"], 0, config["cells"])])
    latency_delta = config["current_latency_delta_ms"] if member == "current" else 0
    latencies = [200 + round(40 * math.tanh(value)) + latency_delta for value in latent]
    errors = [1 if cell % 5 == 0 else 0 for cell in range(config["cells"])]
    pair = {"id": "window-summary", "resource_series_id": "resource", "load_metric": "throughput_rps",
            "window_ids": [config["window_id"]], "expected_sign": "either", "max_lag_ms": 0,
            "min_abs_effect": .3, "min_resource_delta": .1, "min_load_delta": 20,
            "topology_basis": "neutral window summary", "clock_alignment": "declared_aligned", "controls": []}
    return wire._run(snapshot, latencies, [config["requests_per_cell"]] * config["cells"], errors, pairs=[pair])


def _comparison_input(configuration):
    family, seed, config = configuration["family"], configuration["seed"], configuration["config"]
    return {"operation": "comparison", "baseline": _comparison_member(family, seed, "baseline", config),
            "current": _comparison_member(family, seed, "current", config),
            "baseline_window_id": config["window_id"], "current_window_id": config["window_id"],
            "conditions_confirmed": config["conditions_confirmed"]}


def _truth(family):
    truth = {"family": family, "kind": "positive" if family.startswith("P") else "null",
             "injected_pair": None, "injected_rule": None, "sign": None, "lag_cells": None, "interval": None}
    if family in {"P01", "P02", "P03"}:
        truth.update(injected_pair="pair-00", sign="negative" if family == "P03" else "positive",
                     lag_cells={"P01": 0, "P02": 3, "P03": -3}[family])
    if family in {"P04", "P05"}:
        truth.update(injected_rule="rule-00", sign="increase" if family == "P04" else "decrease",
                     interval={"from_epoch_ms": wire.EPOCH + 200 * STEP_MS,
                               "to_epoch_ms": wire.EPOCH + 220 * STEP_MS})
    if family in {"P06", "P07"}:
        truth["sign"] = "positive" if family == "P06" else "negative"
    return truth


def generate(configuration):
    """Create one runner input and process-level truth; no oracle/scoring is performed."""
    if set(configuration) != {"id", "family", "seed", "config"}:
        raise ValueError("invalid configuration shape")
    family, seed, config = configuration["family"], configuration["seed"], configuration["config"]
    if not isinstance(seed, int) or not (0 <= seed <= 99 or 1000 <= seed <= 1999):
        raise ValueError("seed is not a declared or debug seed")
    if not any(family == expected_family and config == expected_config for expected_family, expected_config in _specifications()):
        raise ValueError("undeclared configuration")
    if config["kind"] == "correlation":
        operation = _correlation_input(configuration)
    elif config["kind"] == "episode":
        operation = _episode_input(configuration)
    else:
        operation = _comparison_input(configuration)
    return {"input": operation, "truth": _truth(family)}

"""Offline synthetic traces for the C5 correlation harness.

Each (scenario_id, seed) uses one default_rng(stream_seed(...)). Draw order is:
for each stage, choose any planted or factor indices; draw resource noise in
index order (the planted row gets n + lag AR cells); draw outcome noise; draw
the B01 factor if needed; draw drift signs and magnitudes in resource index
order then outcome order; finally draw a missing-cell mask or block starts.
Stages consume the same RNG consecutively. No seed is selected or retried.
"""

from hashlib import sha256
from math import sqrt

import numpy as np


# Both SLA constants are provisional, calibrated once on unit-test seeds 0..999
# (activation share 434/1000). Re-declare them in the C5 protocol before the
# freeze; they are not acceptance parameters.
SLA_THRESHOLD = 1.7
SLA_MIN_RUN_CELLS = 3
GAMMA = 1.0  # Provisional level-effect amplitude.
BETA = {
    "P01-lin-lag0": 0.6,
    "P02-lin-lag2": 0.6,
    "P03-lin-neg-lag3": 0.6,
    "P04-lin-drift": 0.6,
    "P05-lin-weak-a": 0.2,
    "P05-lin-weak-b": 0.3,
    "P05-lin-weak-c": 0.4,
}

SCENARIO_SPECS = {
    "N01-iid": ("iid", 120, 1, 0, 0),
    "N02-ar08": ("ar08", 120, 1, 0, 0),
    "N03-ar095": ("ar095", 120, 1, 0, 0),
    "N04-t3": ("t3", 120, 1, 0, 0),
    "N05-drift-strong-ar08": ("ar08", 120, 1, 13, 0),
    "N06-drift-weak-ar08": ("ar08", 120, 1, 1.6, 0),
    "N07-randomwalk": ("walk", 120, 1, 0, 0),
    "N08-ar08-240-l10": ("ar08", 240, 1, 0, 0),
    "N09-ar08-gaps": ("gaps", 120, 1, 0, 0),
    "N10-stages-2": ("ar08", 120, 2, 0, 0),
    "N10-stages-3": ("ar08", 120, 3, 0, 0),
    "N11-activation": ("ar08", 120, 1, 0, 0),
    "P01-lin-lag0": ("linear", 120, 1, 0, 0),
    "P02-lin-lag2": ("linear", 120, 1, 0, 2),
    "P03-lin-neg-lag3": ("linear", 120, 1, 0, 3),
    "P04-lin-drift": ("linear", 120, 1, 13, 2),
    "P05-lin-weak-a": ("linear", 120, 1, 0, 2),
    "P05-lin-weak-b": ("linear", 120, 1, 0, 2),
    "P05-lin-weak-c": ("linear", 120, 1, 0, 2),
    "P06-level-threshold": ("threshold", 120, 1, 0, 0),
    "P07-level-saturation": ("saturation", 120, 1, 0, 0),
    "B01-common-factor": ("factor", 120, 1, 0, 0),
    "B02-long-gap-blocks": ("blocks", 120, 1, 0, 0),
    "B03-gap-degradation-dependent": ("dependent", 120, 1, 0, 0),
}
SCENARIO_IDS = tuple(SCENARIO_SPECS)


def stream_seed(scenario_id: str, seed: int) -> int:
    data = f"ltv-c5/v1/{scenario_id}/{seed}".encode("utf-8")
    return int.from_bytes(sha256(data).digest()[:8], "big", signed=False)


def _ar1(rng, n, phi):
    innovations = rng.normal(size=n)
    values = np.empty(n)
    values[0] = innovations[0]
    scale = sqrt(1 - phi * phi)
    for t in range(1, n):
        values[t] = phi * values[t - 1] + scale * innovations[t]
    return values


def _noise(rng, n, kind):
    if kind == "iid":
        return rng.normal(size=n)
    if kind == "t3":
        return rng.standard_t(3, size=n)
    if kind == "walk":
        return np.concatenate(([0.0], np.cumsum(rng.normal(size=n - 1))))
    return _ar1(rng, n, 0.95 if kind == "ar095" else 0.8)


def _add_drift(rng, values, k):
    sign = 1 if rng.integers(2) else -1
    total = sign * k * rng.uniform(0.8, 1.2)
    return values + np.linspace(0.0, total, len(values))


def _mask_stage(stage, mask):
    for row in [*stage["resources"], stage["outcome"], stage["target"]]:
        for t in np.flatnonzero(mask):
            row[int(t)] = None


def _stage(rng, scenario_id, spec, number):
    kind, n, _, drift, lag = spec
    planted_index = None
    loaded = []
    if kind in ("linear", "threshold", "saturation"):
        planted_index = int(rng.integers(15))
    elif kind == "factor":
        loaded = sorted(int(i) for i in rng.choice(15, size=4, replace=False))

    resources = []
    planted_ext = None
    for i in range(16):
        if kind == "linear" and i == planted_index:
            planted_ext = _ar1(rng, n + lag, 0.8)
            resources.append(planted_ext[lag:])
        else:
            resources.append(_noise(rng, n, kind))
    outcome = _noise(rng, n, kind)

    if kind == "linear":
        beta = BETA[scenario_id]
        sign = -1 if scenario_id == "P03-lin-neg-lag3" else 1
        outcome = sign * beta * planted_ext[:n] + sqrt(1 - beta * beta) * outcome
        planted = {"index": planted_index, "lag_cells": lag, "sign": sign}
        level_effect = None
    elif kind in ("threshold", "saturation"):
        z = resources[planted_index]
        feature = (z > 1.0) if kind == "threshold" else np.tanh(2 * z)
        outcome = outcome + GAMMA * feature
        planted = None
        level_effect = {"index": planted_index, "shape": kind}
    else:
        planted = None
        level_effect = None

    if kind == "factor":
        factor = _ar1(rng, n, 0.8)
        scale = sqrt(1 - 0.49)
        for i in loaded:
            resources[i] = 0.7 * factor + scale * resources[i]
        outcome = 0.7 * factor + scale * outcome

    if drift:
        resources = [_add_drift(rng, row, drift) for row in resources]
        outcome = _add_drift(rng, outcome, drift)

    stage = {
        "window_id": f"stage-{number}",
        "source_cells": n,
        "resources": [row.tolist() for row in resources],
        "outcome": outcome.tolist(),
        "target": [100.0] * n,
    }
    if kind == "gaps":
        _mask_stage(stage, rng.random(n) < 0.05)
    elif kind == "dependent":
        _mask_stage(stage, (outcome > 1.0) & (rng.random(n) < 0.5))
    elif kind == "blocks":
        starts = []
        while len(starts) < 3:
            start = int(rng.integers(n - 7))
            if all(abs(start - other) > 8 for other in starts):
                starts.append(start)
        mask = np.zeros(n, dtype=bool)
        for start in starts:
            mask[start:start + 8] = True
        _mask_stage(stage, mask)

    truth = {
        "null_hypotheses": [i for i in range(16) if i != planted_index and i not in loaded],
        "planted": planted,
        "common_factor_indices": loaded,
        "level_effect": level_effect,
    }
    return stage, truth


def generate(scenario_id: str, seed: int) -> dict:
    """Generate one JSON-ready source trace without rounding its values."""
    spec = SCENARIO_SPECS[scenario_id]
    rng = np.random.default_rng(stream_seed(scenario_id, seed))
    stages = []
    for number in range(1, spec[2] + 1):
        stage, truth = _stage(rng, scenario_id, spec, number)
        stages.append(stage)
    first = stages[0]
    activation = None
    if scenario_id == "N11-activation":
        run = 0
        violated = False
        for value in first["outcome"]:
            run = run + 1 if value > SLA_THRESHOLD else 0
            violated |= run >= SLA_MIN_RUN_CELLS
        activation = {"violated": bool(violated), "threshold": SLA_THRESHOLD,
                      "min_run_cells": SLA_MIN_RUN_CELLS}
    return {
        "scenario_id": scenario_id,
        "seed": seed,
        "step_ms": 15000,
        "max_lag_cells": 10 if scenario_id == "N08-ar08-240-l10" else 4,
        "hypotheses": 16,
        "stages": stages,
        "resources": first["resources"],
        "outcome": first["outcome"],
        "target": first["target"],
        "source_cells": first["source_cells"],
        "truth": truth,
        "activation": activation,
    }

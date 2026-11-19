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

"""Correlation hypothesis catalog and plan expander (ADR 0022, D5, slice K3).

The core knows nothing about the catalog: this tool turns the catalog data file
and a resource snapshot into an ordinary correlation-plan.v1. Expansion is
deterministic and never looks at series values. Hypotheses that cannot be bound
to the snapshot are dropped and listed in a separate ledger, which is not part
of the plan (the core rejects unknown plan fields).
"""

import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).parent))
from stats_validation import _canonical, snapshot_hash

CATALOG_PATH = Path(__file__).resolve().parents[1] / "docs/contracts/diagnostics/v1/correlation-catalog.v1.json"
CATALOG_SCHEMA = "correlation-catalog.v1"
PLAN_SCHEMA = "correlation-plan.v1"
MAX_PAIRS = 16
MAX_LAG_CELLS = 4
MAX_PLAN_LAG_MS = 60000
MIN_EFFECT = 0.3
OUTCOMES = ("response_time_p95_ms", "error_rate", "throughput_rps")
SIGNS = ("positive", "negative", "either")
KINDS = ("cause", "control")
SCOPES = ("service", "downstream", "load_generator")
# Controls constant within a stage, meaning -> unit the core requires of the bound series. achieved_rps is not
# allowed: it varies inside a stage, and a control the core actually uses makes the pair GENUINE_PARTIAL_UNCALIBRATED.
CONTROL_UNITS = {"target_rps": "requests/s", "concurrency": "count"}
IDENTIFIER = re.compile(r"^[^\x00-\x1f\x7f-\x9f]{1,128}$")
HYPOTHESIS_FIELDS = {"id", "kind", "scope", "metric", "unit", "expected_sign", "max_lag_cells",
                     "min_abs_effect", "min_resource_delta", "controls"}


class CatalogError(ValueError):
    """Invalid catalog or expansion input; the message starts with a stable code."""


def _fail(code, detail=""):
    raise CatalogError(f"{code} {detail}".strip())


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _identifier(value):
    if not isinstance(value, str) or len(value.encode("utf-8")) > 128 or not IDENTIFIER.match(value):
        _fail("IDENTIFIER_INVALID", repr(value)[:80])
    return value


def load_catalog(path=CATALOG_PATH):
    catalog = json.loads(Path(path).read_text(encoding="utf-8"))
    validate_catalog(catalog)
    return catalog


def validate_catalog(catalog):
    if not isinstance(catalog, dict) or set(catalog) - {"schema_version", "families", "event_families"} \
            or catalog.get("schema_version") != CATALOG_SCHEMA or not catalog.get("families"):
        _fail("CATALOG_INVALID", "root")
    outcomes = set()
    for family in catalog["families"]:
        if set(family) != {"id", "outcome", "min_load_delta", "hypotheses"}:
            _fail("CATALOG_INVALID", "family fields")
        _identifier(family["id"])
        if family["outcome"] not in OUTCOMES or family["outcome"] in outcomes:
            _fail("CATALOG_INVALID", f"outcome of {family['id']}")
        outcomes.add(family["outcome"])
        if not _is_number(family["min_load_delta"]) or family["min_load_delta"] <= 0:
            _fail("CATALOG_INVALID", f"min_load_delta of {family['id']}")
        hypotheses = family["hypotheses"]
        if len(hypotheses) > MAX_PAIRS:
            _fail("FAMILY_SIZE_EXCEEDED", family["id"])
        if {h.get("kind") for h in hypotheses} != set(KINDS):
            _fail("CATALOG_INVALID", f"{family['id']} needs a cause and a control hypothesis")
        ids, bindings = set(), set()
        for h in hypotheses:
            _validate_hypothesis(h)
            binding = (h["scope"], h["metric"])
            if h["id"] in ids or binding in bindings:
                _fail("CATALOG_INVALID", f"duplicate hypothesis or series binding {h['id']}")
            ids.add(h["id"])
            bindings.add(binding)
    for event in catalog.get("event_families", []):
        if set(event) != {"id", "note"} or not isinstance(event["note"], str) or not event["note"]:
            _fail("CATALOG_INVALID", "event family")
        _identifier(event["id"])


def _validate_hypothesis(h):
    if set(h) != HYPOTHESIS_FIELDS:
        _fail("CATALOG_INVALID", f"hypothesis fields {h.get('id')}")
    _identifier(h["id"])
    _identifier(h["metric"])
    _identifier(h["unit"])
    lag = h["max_lag_cells"]
    controls = h["controls"]
    valid = (h["kind"] in KINDS and h["scope"] in SCOPES and h["expected_sign"] in SIGNS
             and isinstance(lag, int) and not isinstance(lag, bool) and 0 <= lag <= MAX_LAG_CELLS
             and _is_number(h["min_abs_effect"]) and MIN_EFFECT <= h["min_abs_effect"] <= 1
             and _is_number(h["min_resource_delta"]) and h["min_resource_delta"] > 0
             and isinstance(controls, list) and len(controls) <= 4 and len(set(controls)) == len(controls)
             and all(c in CONTROL_UNITS for c in controls))
    if not valid:
        _fail("CATALOG_INVALID", f"hypothesis {h['id']}")


def plan_sha256(plan):
    return hashlib.sha256(_canonical(plan).encode("utf-8")).hexdigest()


def expand(catalog, snapshot, stages, outcome, topology_edges):
    """Return (correlation-plan.v1 or None, skipped ledger).

    outcome: {"load_metric", "service"} of an activated outcome, or None when the
    activation rule (SLA violation or found episode) did not fire; activation is
    decided by the caller. topology_edges: (caller, callee) service pairs.
    """
    validate_catalog(catalog)
    if outcome is None:
        return None, [{"hypothesis": None, "reason": "OUTCOME_NOT_ACTIVATED"}]
    family = next((f for f in catalog["families"] if f["outcome"] == outcome.get("load_metric")), None)
    if family is None:
        return None, [{"hypothesis": None, "reason": "OUTCOME_NOT_IN_CATALOG"}]
    service = _identifier(outcome.get("service"))
    stage_ids = _check_stages(snapshot, stages)
    clock = "declared_aligned" if snapshot.get("provenance", {}).get("clock_alignment") == "declared_aligned" \
        else "unknown"
    callees = sorted({callee for caller, callee in topology_edges if caller == service and callee != service})
    pairs, skipped, used = [], [], set()

    def skip(name, reason):
        skipped.append({"hypothesis": name, "reason": reason})

    for h in family["hypotheses"]:
        if h["scope"] == "downstream":
            targets = [(f"{h['id']}@{callee}", callee, f"declared edge {service} -> {callee}") for callee in callees]
            if not targets:
                skip(h["id"], "TOPOLOGY_EDGE_MISSING")
        elif h["scope"] == "load_generator":
            targets = [(h["id"], None, "load generator control")]
        else:
            targets = [(h["id"], service, f"same service {service}")]
        for name, entity, basis in targets:
            lag_ms = h["max_lag_cells"] * snapshot["step_ms"]
            role = "generator" if h["scope"] == "load_generator" else "system"
            bound, reason = _bind(snapshot["series"], h["metric"], h["unit"], role, entity)
            controls = [] if bound is None else _controls(snapshot["series"], h["controls"])
            if bound is None:
                skip(name, reason)
            elif lag_ms > MAX_PLAN_LAG_MS:
                skip(name, "LAG_EXCEEDS_PLAN_LIMIT")
            elif isinstance(controls, str):
                skip(name, controls)
            elif bound["id"] in used:
                skip(name, "SERIES_ALREADY_USED")
            else:
                used.add(bound["id"])
                pairs.append({
                    "id": _identifier(f"{family['id']}.{name}"), "resource_series_id": bound["id"],
                    "load_metric": family["outcome"], "window_ids": stage_ids,
                    "expected_sign": h["expected_sign"], "max_lag_ms": lag_ms,
                    "min_abs_effect": h["min_abs_effect"], "min_resource_delta": h["min_resource_delta"],
                    "min_load_delta": family["min_load_delta"], "controls": controls,
                    "topology_basis": f"{CATALOG_SCHEMA} {family['id']}/{h['id']}: {basis}",
                    "clock_alignment": clock, "_kind": h["kind"]})
    if len(pairs) > MAX_PAIRS:
        _fail("FAMILY_SIZE_EXCEEDED", family["id"])
    kinds = {p.pop("_kind") for p in pairs}
    if "cause" not in kinds:
        for pair in pairs:
            skip(pair["id"].split(".", 1)[1], "ONLY_CONTROL_LEFT")
        return None, skipped
    pairs.sort(key=lambda p: p["id"])
    plan = {"schema_version": PLAN_SCHEMA, "resource_snapshot_sha256": snapshot_hash(snapshot), "pairs": pairs}
    return plan, skipped


def _check_stages(snapshot, stages):
    if not stages or len(set(stages)) != len(stages):
        _fail("STAGES_INVALID", "stages must be a non-empty list of unique window ids")
    known = {w["id"] for w in snapshot.get("windows", [])}
    for stage in stages:
        _identifier(stage)
        if known and stage not in known:
            _fail("STAGE_NOT_FOUND", stage)
    return sorted(stages)


def _bind(series, metric, unit, role, entity):
    found = [s for s in series if s["metric"] == metric and s["role"] == role
             and (entity is None or s["entity"] == entity)]
    if not found:
        return None, "SERIES_MISSING"
    if len(found) > 1:
        return None, "SERIES_AMBIGUOUS"
    if found[0]["unit"] != unit:
        return None, "UNIT_MISMATCH"
    return found[0], None


def _controls(series, meanings):
    result = []
    for meaning in meanings:
        found = [s for s in series if s["metric"] == meaning and s["unit"] == CONTROL_UNITS[meaning]]
        if len(found) != 1:
            return "CONTROL_SERIES_AMBIGUOUS" if found else "CONTROL_SERIES_MISSING"
        result.append({"meaning": meaning, "series_id": found[0]["id"]})
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Expand the correlation catalog into correlation-plan.v1")
    parser.add_argument("--snapshot", required=True, help="resource-snapshot.v1 file")
    parser.add_argument("--stages", required=True, help="comma-separated window ids")
    parser.add_argument("--service", required=True)
    parser.add_argument("--load-metric", required=True, choices=OUTCOMES)
    parser.add_argument("--edges", default="", help="comma-separated caller:callee service edges")
    parser.add_argument("--catalog", default=str(CATALOG_PATH))
    parser.add_argument("--out", required=True, help="plan file; not written when there is no plan")
    parser.add_argument("--skipped-out", help="ledger of skipped hypotheses")
    args = parser.parse_args(argv)
    # Exact decimals: float parsing changes the semantic snapshot hash of long values.
    snapshot = json.loads(Path(args.snapshot).read_text(encoding="utf-8"), parse_float=Decimal)
    edges = [tuple(edge.split(":", 1)) for edge in args.edges.split(",") if edge]
    plan, skipped = expand(load_catalog(args.catalog), snapshot, args.stages.split(","),
                           {"load_metric": args.load_metric, "service": args.service}, edges)
    if args.skipped_out:
        Path(args.skipped_out).write_text(json.dumps(skipped, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if plan is None:
        print("NO_PLAN", json.dumps(skipped, ensure_ascii=False), file=sys.stderr)
        return 1
    Path(args.out).write_text(json.dumps(plan, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Placebo snapshots for the correlation headline selector.

Circularly shifts the values of system resource series inside every window of a
resource-snapshot.v1 file. The load outcome comes from the load input and stays in
place, so a real resource-to-load link is broken while each series keeps its values
and its autocorrelation. A headline SELECTED on a placebo snapshot is a false one.

Series with role "generator" (controls such as target_rps) are not shifted.
With --plan the tool also writes a copy of the correlation plan for every placebo
snapshot, because a plan is bound to its snapshot by resource_snapshot_sha256.
Standard library only; the tool reads its inputs and writes new files, nothing else.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))
from stats_validation import snapshot_hash

MAX_LAG_CELLS = 10  # MAX_HEADLINE_LAG_CELLS in CorrelationHeadlineSelection.kt


class PlaceboError(Exception):
    pass


def window_cells(snapshot):
    """Returns [(window id, first cell, end cell)] in snapshot order."""
    start, step, count = snapshot["start_epoch_ms"], snapshot["step_ms"], snapshot["point_count"]
    windows = snapshot.get("windows") or [
        {"id": "run-intersection", "from_epoch_ms": start, "to_epoch_ms": start + step * count}
    ]
    cells = []
    for window in windows:
        offset_from, offset_to = window["from_epoch_ms"] - start, window["to_epoch_ms"] - start
        if offset_from % step or offset_to % step:
            raise PlaceboError(f"WINDOW_NOT_ON_GRID: {window['id']}")
        first, end = offset_from // step, offset_to // step
        if not 0 <= first < end <= count:
            raise PlaceboError(f"WINDOW_OUTSIDE_SNAPSHOT: {window['id']}")
        cells.append((window["id"], first, end))
    ordered = sorted(cells, key=lambda item: item[1])
    for left, right in zip(ordered, ordered[1:]):
        if right[1] < left[2]:
            raise PlaceboError(f"WINDOWS_OVERLAP: {left[0]} and {right[0]}")
    return cells


def shift_for(length, index, count, window_id):
    """Shift number `index` of `count`, evenly spaced and farther than the lag range from zero."""
    shift = length * index // (count + 1)
    if min(shift, length - shift) <= MAX_LAG_CELLS:
        raise PlaceboError(
            f"WINDOW_TOO_SHORT: {window_id} has {length} cells; "
            f"{count} placebo(s) need more than {(MAX_LAG_CELLS + 1) * (count + 1) - 1}"
        )
    return shift


def placebo(snapshot, index, count):
    """Returns (shifted copy of the snapshot, {window id: shift in cells})."""
    if snapshot.get("schema_version") != "resource-snapshot.v1":
        raise PlaceboError("UNSUPPORTED_SCHEMA: expected resource-snapshot.v1")
    cells = window_cells(snapshot)
    shifts = {window_id: shift_for(end - first, index, count, window_id) for window_id, first, end in cells}
    series = []
    for item in snapshot["series"]:
        values = list(item["values"])
        if len(values) != snapshot["point_count"]:
            raise PlaceboError(f"SERIES_LENGTH_MISMATCH: {item['id']}")
        if item.get("role") != "generator":
            for window_id, first, end in cells:
                cut = end - shifts[window_id]
                values[first:end] = values[cut:end] + values[first:cut]
        series.append({**item, "values": values})
    return {**snapshot, "series": series}, shifts


def dumps(value, indent=0):
    """JSON text that keeps every number exactly as it was read (Decimal, no float round trip)."""
    pad, inner = "  " * indent, "  " * (indent + 1)
    if isinstance(value, dict):
        if not value:
            return "{}"
        body = ",\n".join(f"{inner}{json.dumps(key, ensure_ascii=False)}: {dumps(item, indent + 1)}"
                          for key, item in value.items())
        return "{\n" + body + "\n" + pad + "}"
    if isinstance(value, list):
        if all(not isinstance(item, (dict, list)) for item in value):
            return "[" + ", ".join(dumps(item) for item in value) + "]"
        return "[\n" + ",\n".join(inner + dumps(item, indent + 1) for item in value) + "\n" + pad + "]"
    if isinstance(value, Decimal):
        return str(value)
    return json.dumps(value, ensure_ascii=False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("snapshot", type=Path, help="resource-snapshot.v1 file")
    parser.add_argument("--out-dir", type=Path, required=True, help="directory for placebo-<n>.json")
    parser.add_argument("--count", type=int, default=3, help="number of placebo snapshots (default 3)")
    parser.add_argument("--plan", type=Path, help="correlation-plan.v1 file to rebind to every placebo snapshot")
    args = parser.parse_args(argv)
    try:
        if args.count < 1:
            raise PlaceboError("COUNT_INVALID: --count must be at least 1")
        snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"), parse_float=Decimal)
        plan = json.loads(args.plan.read_text(encoding="utf-8"), parse_float=Decimal) if args.plan else None
        if plan is not None and plan["resource_snapshot_sha256"] != snapshot_hash(snapshot):
            raise PlaceboError("PLAN_SNAPSHOT_MISMATCH: the plan is not bound to this snapshot")
        results = [placebo(snapshot, index, args.count) for index in range(1, args.count + 1)]
    except (PlaceboError, KeyError, TypeError, ValueError, OSError) as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 4
    args.out_dir.mkdir(parents=True, exist_ok=True)
    for index, (shifted, shifts) in enumerate(results, start=1):
        target = args.out_dir / f"placebo-{index}.json"
        target.write_text(dumps(shifted) + "\n", encoding="utf-8")
        report = {"file": str(target), "shift_cells": shifts}
        if plan is not None:
            plan_target = args.out_dir / f"placebo-{index}.plan.json"
            plan_target.write_text(dumps({**plan, "resource_snapshot_sha256": snapshot_hash(shifted)}) + "\n",
                                   encoding="utf-8")
            report["plan"] = str(plan_target)
        print(json.dumps(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())

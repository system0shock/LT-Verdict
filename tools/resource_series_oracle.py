"""Independent decimal oracle for the resource-series.v1 response."""

import json
from decimal import Context, Decimal, ROUND_HALF_EVEN

import stats_validation


SUM_CONTEXT = Context(prec=200)
MEAN_CONTEXT = Context(prec=34, rounding=ROUND_HALF_EVEN)
REDUCERS = {
    "interval_mean": "mean",
    "interval_rate": "mean",
    "interval_max": "max",
    "interval_min": "min",
}


def reduce_cell(cells, reducer):
    observed = [cell for cell in cells if cell is not None]
    if not observed:
        return None, 0
    if reducer == "mean":
        total = Decimal(0)
        for cell in observed:
            total = SUM_CONTEXT.add(total, cell)
        return MEAN_CONTEXT.divide(total, Decimal(len(observed))), len(observed)
    if reducer == "max":
        return max(observed), len(observed)
    if reducer == "min":
        return min(observed), len(observed)
    raise ValueError(reducer)


def to_double(value):
    return float(value) + 0.0


def read_snapshot(text):
    return json.loads(text, parse_float=Decimal, parse_int=Decimal)


def snapshot_hash(snapshot):
    return stats_validation.snapshot_hash(snapshot)


def _common(snapshot, kind):
    return {
        "schema_version": "resource-series.v1",
        "kind": kind,
        "resource_snapshot_sha256": snapshot_hash(snapshot),
        "numeric_encoding": "ieee754-double",
    }


def catalog(snapshot, after=None, limit=256):
    series = sorted(snapshot["series"], key=lambda item: stats_validation._utf16_order(item["id"]))
    if after is not None:
        cursor = stats_validation._utf16_order(after)
        series = [item for item in series if stats_validation._utf16_order(item["id"]) > cursor]
    page = series[:limit]
    return _common(snapshot, "catalog") | {
        "grid": {
            "start_epoch_ms": int(snapshot["start_epoch_ms"]),
            "step_ms": int(snapshot["step_ms"]),
            "point_count": int(snapshot["point_count"]),
        },
        "windows": [
            {"id": window["id"], "from_epoch_ms": int(window["from_epoch_ms"]),
             "to_epoch_ms": int(window["to_epoch_ms"])}
            for window in snapshot.get("windows", [])
        ],
        "series": [
            {key: item[key] for key in ("id", "metric", "unit", "entity", "role", "aggregation")}
            | {"reducer": REDUCERS[item["aggregation"]], "labels": item.get("labels", {}),
               "observed_cells": sum(value is not None for value in item["values"])}
            for item in page
        ],
        "next_after": page[-1]["id"] if len(series) > limit else None,
    }


def values(snapshot, series_ids, step_ms=None, from_ms=None, to_ms=None, limit=None):
    start = int(snapshot["start_epoch_ms"])
    source_step = int(snapshot["step_ms"])
    count = int(snapshot["point_count"])
    step = step_ms or source_step
    ratio = min(step // source_step, count)
    total_cells = (count + ratio - 1) // ratio
    first = (from_ms - start) // step if from_ms is not None else 0
    end_cell = total_cells if to_ms is None or to_ms == start + count * source_step else (to_ms - start) // step
    per_series = limit or min(2000, 100000 // len(series_ids))
    cell_count = min(end_cell - first, per_series)
    last = first + cell_count - 1
    by_id = {item["id"]: item for item in snapshot["series"]}
    result = []
    for series_id in series_ids:
        item = by_id[series_id]
        reducer = REDUCERS[item["aggregation"]]
        reduced = [reduce_cell(item["values"][cell * ratio:(cell + 1) * ratio], reducer)
                   for cell in range(first, first + cell_count)]
        entry = {
            "id": series_id,
            "aggregation": item["aggregation"],
            "reducer": reducer,
            "values": [None if value is None else to_double(value) for value, _ in reduced],
        }
        if ratio > 1:
            entry["observed"] = [seen for _, seen in reduced]
        result.append(entry)
    return _common(snapshot, "values") | {
        "grid": {
            "start_epoch_ms": start,
            "source_step_ms": source_step,
            "step_ms": step,
            "first_cell_start_ms": start + first * step,
            "cell_count": cell_count,
            "source_cells_per_cell": ratio,
            "last_cell_source_cells": min(ratio, count - last * ratio),
        },
        "series": result,
        "next_from_ms": start + (first + cell_count) * step if first + cell_count < end_cell else None,
    }

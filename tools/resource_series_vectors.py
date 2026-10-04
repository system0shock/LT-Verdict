"""Generate deterministic resource-series snapshot and wire-response vectors."""

import json
from decimal import Decimal
from pathlib import Path

import resource_series_oracle as oracle


START = 1767225600000
STEP = 15000
COUNT = 50
ALL_IDS = ["big", "cpu", "gap", "queue", "tiny"]


def _series_text(series_id, aggregation, values, labels=None):
    metadata = {
        "id": series_id,
        "metric": series_id + "_metric",
        "unit": "synthetic_unit",
        "entity": "service",
        "role": "system",
        "aggregation": aggregation,
        "labels": labels or {},
    }
    head = json.dumps(metadata, ensure_ascii=True, separators=(",", ":"))[:-1]
    return head + ',"values":[' + ",".join(values) + "]}"


def generate():
    cpu = ["null" if i % 7 == 3 else format(Decimal((i * 37) % 1000) / Decimal(1000), ".3f")
           for i in range(COUNT)]
    queue = [format(Decimal(2 if i % 3 == 2 else 1), "f") for i in range(COUNT)]
    tiny = ["null" if i >= COUNT - 6 else format(Decimal("0.000000000001" if i % 2 == 0 else "1"), "f")
            for i in range(COUNT)]
    gap = ["null"] * COUNT
    big = [format(Decimal("100000000000000000" if i % 2 == 0 else "99999999999999999.999999999999"), "f")
           for i in range(COUNT)]
    series = [
        _series_text("cpu", "interval_mean", cpu, {"host": "h1"}),
        _series_text("queue", "interval_rate", queue),
        _series_text("tiny", "interval_mean", tiny),
        _series_text("gap", "interval_mean", gap),
        _series_text("big", "interval_mean", big),
    ]
    snapshot_text = (
        '{"schema_version":"resource-snapshot.v1","load_input_sha256":"' + "0" * 64
        + f'","start_epoch_ms":{START},"step_ms":{STEP},"point_count":{COUNT},"series":['
        + ",".join(series) + "]}\n"
    )
    snapshot = oracle.read_snapshot(snapshot_text)
    cases = []

    def add(name, endpoint, query):
        expected = (oracle.catalog(snapshot, **query) if endpoint == "catalog" else
                    oracle.values(snapshot, query["series_id"],
                                  **{key: value for key, value in query.items() if key != "series_id"}))
        cases.append({"name": name, "snapshot": "snapshot-small.json", "endpoint": endpoint,
                      "query": query, "expected": expected})
        return expected

    add("source-step", "values", {"series_id": ALL_IDS, "step_ms": STEP})
    add("step-60s-all", "values", {"series_id": ALL_IDS, "step_ms": 60000})
    add("subrange", "values", {"series_id": ["cpu", "queue"], "step_ms": 60000,
                                 "from_ms": START + 60000, "to_ms": START + 540000})
    first = add("paging-first", "values", {"series_id": ["cpu", "queue"], "limit": 5})
    add("paging-next", "values", {"series_id": ["cpu", "queue"], "limit": 5,
                                   "from_ms": first["next_from_ms"]})
    add("partial-last-cell", "values", {"series_id": ["cpu", "tiny", "queue"], "step_ms": 45000})
    add("step-larger-than-grid", "values", {"series_id": ["cpu", "queue"], "step_ms": 900000})
    add("empty-series", "values", {"series_id": ["gap"], "step_ms": 60000})
    add("catalog-first-page", "catalog", {"limit": 2})
    add("catalog-after", "catalog", {"after": "cpu"})
    return {
        "snapshot-small.json": snapshot_text,
        "cases.json": json.dumps({"cases": cases}, indent=2, ensure_ascii=True) + "\n",
    }


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "fixtures" / "resource-series"
    output.mkdir(parents=True, exist_ok=True)
    for name, content in generate().items():
        with (output / name).open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)

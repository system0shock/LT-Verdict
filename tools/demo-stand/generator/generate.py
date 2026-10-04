"""Generate deterministic load, resource, and Prometheus demo fixtures."""

import argparse
import csv
from decimal import Decimal, ROUND_HALF_EVEN
import hashlib
import json
import math
from pathlib import Path
import sys
import tempfile


TOOLS = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(TOOLS))
import synthetic_service
from stats_validation import snapshot_hash


STEP_US = 5_000_000
IDLE_US = 120_000_000
SCENARIOS = {
    "sla-fail": (1790841600000, 1, 10000, 8, 2000, [("steady", 60, 900)]),
    "capacity": (1790845200000, 1, 10000, 8, 2000,
                 [(f"stage-{rate:03d}", rate, 300) for rate in (40, 60, 80, 90, 96, 104)]),
    "saturation": (1790848800000, 4, 10000, 1, 12000,
                   [("warm", 40, 300), ("saturated", 84, 480), ("recovery", 40, 300)]),
}
METRICS = (
    ("system-cpu-work", "demo_service_cpu_busy_ratio", 'service="orders-api"'),
    ("system-db-work", "demo_db_busy_ratio", 'db="orders-db"'),
    ("cpu-queue", "demo_cpu_queue_requests", 'service="orders-api"'),
    ("db-queue", "demo_db_queue_requests", 'db="orders-db"'),
    ("admission-queue", "demo_admission_queue_requests", 'service="orders-api"'),
    ("downstream-wait", "demo_downstream_inflight_requests", 'service="orders-api",dependency="payments"'),
    ("generator-threads", "demo_generator_active_threads", 'generator="jmeter"'),
    ("generator-queue", "demo_generator_queue_requests", 'generator="jmeter"'),
    ("generator-rps", "demo_generator_requests_per_second", 'generator="jmeter"'),
    ("target-request-rate", "demo_target_requests_per_second", 'generator="jmeter"'),
)
CAPACITY_SERIES = (
    ("service-cpu-busy", "system-cpu-work", "cpu_busy", "ratio", "orders-api", "system"),
    ("db-busy", "system-db-work", "db_busy", "ratio", "orders-db", "system"),
    ("cpu-queue", "cpu-queue", "queue_depth", "requests", "orders-api", "system"),
    ("downstream-wait", "downstream-wait", "in_flight_requests", "requests", "payments", "system"),
    ("generator-threads", "generator-threads", "active_threads", "threads", "jmeter", "generator"),
    ("generator-queue", "generator-queue", "queue_depth", "requests", "jmeter", "generator"),
)


def number(value):
    """Render a rounded float as an ordinary JSON/OpenMetrics decimal token."""
    if not math.isfinite(value):
        raise ValueError("nonfinite metric")
    result = repr(value)
    return format(Decimal(result), "f") if "e" in result.lower() else result


def json_text(value):
    """Compact JSON with plain decimal floats and stable insertion order."""
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(key, ensure_ascii=True) + ":" + json_text(item)
                              for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(map(json_text, value)) + "]"
    if isinstance(value, float):
        return number(value)
    return json.dumps(value, ensure_ascii=True, allow_nan=False, separators=(",", ":"))


def write_json(path, value):
    with path.open("w", newline="\n", encoding="utf-8") as stream:
        stream.write(json_text(value) + "\n")


def build_scenarios():
    """Return the declared scenarios in chronological order."""
    return SCENARIOS


def parameters_for(name, stage_scale):
    if name not in SCENARIOS or stage_scale <= 0:
        raise ValueError("invalid scenario or stage scale")
    epoch, cpu_workers, cpu_demand, db_workers, db_demand, stages = SCENARIOS[name]
    scaled = [(identifier, rate, max(STEP_US, round(seconds * 1_000_000 * stage_scale / STEP_US) * STEP_US))
              for identifier, rate, seconds in stages]
    parameters = synthetic_service.scenario_parameters("NT01")
    parameters.update(
        noise=True, cpu_workers=cpu_workers, cpu_demand_us=cpu_demand,
        db_workers=db_workers, db_demand_us=db_demand, downstream_us=38000,
        timeout_us=60_000_000, drain_us=120_000_000, pool_capacity=256,
        sampling={"step_us": STEP_US, "jitter_us": 0, "clock_offset_us": 0,
                  "semantics": "interval_mean/interval_rate"},
        stress_variants={}, declared_variants=["clean"], start_epoch_ms=epoch,
        stages=[{"rate_rps": 0, "duration_us": IDLE_US}] +
               [{"rate_rps": rate, "duration_us": duration} for _, rate, duration in scaled] +
               [{"rate_rps": 0, "duration_us": IDLE_US}],
    )
    if name == "sla-fail":
        parameters["downstream_changes"] = [
            {"from_us": 420_000_000, "to_us": 600_000_000, "add_us": 400_000}]
    return parameters, scaled


def stage_records(epoch, stages):
    offset = IDLE_US // 1000
    records = []
    for identifier, rate, duration in stages:
        records.append({"id": identifier, "target_rps": rate,
                        "from_epoch_ms": epoch + offset,
                        "to_epoch_ms": epoch + offset + duration // 1000})
        offset += duration // 1000
    return records


def label_for(request_id):
    if request_id > 0 and request_id % 1500 == 0:
        return "POST /api/refund"
    digit = request_id % 10
    return "GET /api/catalog" if digit < 6 else "POST /api/cart" if digit < 9 else "POST /api/checkout"


def capacity_files(directory, source, stage_list, epoch, jtl_hash):
    source_series = {series["id"]: series["values"] for series in source["series"]}
    series = []
    for identifier, source_id, metric, unit, entity, role in CAPACITY_SERIES:
        values = source_series[source_id]
        paired = [None if left is None or right is None else float(
            ((Decimal(str(left)) + Decimal(str(right))) / 2).quantize(
                Decimal(".000001"), rounding=ROUND_HALF_EVEN))
                  for left, right in zip(values[::2], values[1::2])]
        series.append({"id": identifier, "metric": metric, "unit": unit,
                       "entity": entity, "role": role, "aggregation": "interval_mean",
                       "values": paired})
    windows = [{key: stage[key] for key in ("id", "from_epoch_ms", "to_epoch_ms")}
               for stage in stage_list]
    resources = {
        "schema_version": "resource-snapshot.v1", "load_input_sha256": jtl_hash,
        "start_epoch_ms": epoch, "step_ms": 10000,
        "point_count": source["point_count"] // 2, "series": series,
        "windows": windows,
        "rules": [
            {"id": "generator-queue-high", "series_id": "generator-queue", "unit": "requests",
             "operator": "gt", "threshold": 5, "min_consecutive_cells": 3, "effect": "diagnostic"},
            {"id": "service-cpu-saturated", "series_id": "service-cpu-busy", "unit": "ratio",
             "operator": "gt", "threshold": 0.95, "min_consecutive_cells": 6, "effect": "diagnostic"},
        ],
        "provenance": {"source_kind": "synthetic-demo",
                       "query_semantics": "interval means from the synthetic FIFO service, averaged to the 10 s grid",
                       "clock_alignment": "declared_aligned"},
    }
    write_json(directory / "resources.json", resources)
    parsed = json.loads((directory / "resources.json").read_text(encoding="utf-8"), parse_float=Decimal)
    plan = {
        "schema_version": "capacity-plan.v1", "load_input_sha256": jtl_hash,
        "resource_snapshot_sha256": snapshot_hash(parsed), "load_axis": "rps",
        "achieved_load": {"statistic": "p05_10s", "target_tolerance_ratio": 0.05,
                          "required_capacity": 90},
        "generator_guard_rule_ids": ["generator-queue-high"],
        "stages": [{"id": stage["id"], "target": stage["target_rps"],
                    "from_epoch_ms": stage["from_epoch_ms"], "to_epoch_ms": stage["to_epoch_ms"],
                    "evaluation_window_id": stage["id"]} for stage in stage_list],
    }
    write_json(directory / "capacity-plan.json", plan)


def run_scenario(name, out_dir, stage_scale=1.0):
    """Write one scenario and return its manifest record and metric cells."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    parameters, stages = parameters_for(name, stage_scale)
    trace = synthetic_service.simulate(parameters, seed=7)
    if synthetic_service.validate_trace(trace):
        raise ValueError("invalid synthetic trace")
    with tempfile.TemporaryDirectory() as temporary:
        exported = synthetic_service.export(trace, Path(temporary) / "export")
        source = json.loads(Path(exported["resources"]).read_text(encoding="utf-8"))
    jtl_path = out_dir / "load.jtl"
    count = 0
    with jtl_path.open("w", newline="\n", encoding="utf-8") as stream:
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("timeStamp", "elapsed", "label", "success"))
        for request in trace["requests"]:
            start, end = request["start_us"], request["end_us"]
            if start is None or end is None:
                continue
            identifier = request["id"]
            writer.writerow((parameters["start_epoch_ms"] + start // 1000,
                             (end - start + 999) // 1000, label_for(identifier),
                             "true" if request["status"] == "completed" and identifier % 500 != 499 else "false"))
            count += 1
    jtl_hash = hashlib.sha256(jtl_path.read_bytes()).hexdigest()
    epoch = parameters["start_epoch_ms"]
    stage_list = stage_records(epoch, stages)
    if name == "capacity":
        capacity_files(out_dir, source, stage_list, epoch, jtl_hash)

    point_count = source["point_count"]
    completions = [[] for _ in range(point_count)]
    for request in trace["requests"]:
        end = request["end_us"]
        if request["status"] == "completed" and end is not None and 0 <= end < trace["duration_us"]:
            completions[end // STEP_US].append((end - request["start_us"]) / 1_000_000)
    p95 = [sorted(values)[math.ceil(len(values) * 0.95) - 1] if values else None
           for values in completions]
    cells = {series["id"]: series["values"] for series in source["series"]}
    cells["p95"] = p95
    record = {"name": name, "start_epoch_ms": epoch,
              "end_epoch_ms": epoch + trace["duration_us"] // 1000,
              "test_start_epoch_ms": stage_list[0]["from_epoch_ms"],
              "test_end_epoch_ms": stage_list[-1]["to_epoch_ms"],
              "jtl_sha256": jtl_hash, "jtl_row_count": count, "stages": stage_list}
    return record, cells


def generate(names, out_dir, stage_scale=1.0):
    """Write selected scenarios plus a chronologically grouped OpenMetrics file."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    records, scenarios = [], []
    for name in names:
        record, cells = run_scenario(name, out_dir / name, stage_scale)
        records.append(record)
        scenarios.append((record["start_epoch_ms"], cells))
        print(f"{name}: {record['jtl_row_count']} JTL rows, {record['start_epoch_ms']}..{record['end_epoch_ms']}")
    scenarios.sort(key=lambda item: item[0])
    with (out_dir / "metrics.om").open("w", newline="\n", encoding="utf-8") as stream:
        for source_id, name, labels in METRICS + (("p95", "demo_response_time_p95_seconds", 'service="orders-api"'),):
            stream.write(f"# TYPE {name} gauge\n")
            for epoch, cells in scenarios:
                for index, value in enumerate(cells[source_id]):
                    if value is not None:
                        rounded = round(value, 6)
                        stream.write(f"{name}{{{labels}}} {number(rounded)} {(epoch + (index + 1) * 5000) // 1000}\n")
        stream.write("# EOF\n")
    write_json(out_dir / "manifest.json", {"scenarios": records})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--scenario", choices=(*SCENARIOS, "all"), default="all")
    args = parser.parse_args()
    generate(list(SCENARIOS) if args.scenario == "all" else [args.scenario], args.out)


if __name__ == "__main__":
    main()

"""Deterministic synthetic set for one arm (plan P2d): load.jtl, resource-snapshot.json, pod-view.json, expected.json.

Every cell is a closed integer formula of the spec (permille, no floating point, no wall clock): the
snapshot holds the service series (the worst container of the service per snapshot cell), pod-view.json holds
the pod and container rows averaged over the columns, and expected.json states the reference max, mean and last
per row plus the effect planted by the scenario. Nothing here runs LT Verdict. Run through
`python -m tools.pod_view_adapter --synthetic spec.json --out DIR` from the repository root.
"""

import json
import re
from decimal import Context, Decimal, ROUND_HALF_EVEN
from hashlib import sha256
from pathlib import Path

from tools import stats_validation
from tools.perf import generate_jtl
from tools.pod_view_adapter import MAX_COLUMNS, MAX_FILE_BYTES, METRICS, AdapterError, Observation, Raw, assemble, dumps, format_value

MASK = (1 << 64) - 1
SCENARIOS = ("balanced", "leak-on-arm-b-pod-b2", "throttle-on-one-pod")
DEFAULTS = {
    "scenario": "balanced",
    "arm": "A",
    "namespace": "shop",
    "services": 3,
    "pods_per_service": 3,
    "containers": ["app", "istio-proxy"],
    "sidecar_containers": "istio-proxy",
    "snapshot_step_ms": 15000,
    "pod_step_ms": 15000,
    "load_rows": 360000,  # 10 ms per row: one hour
    "load_seed": 1,
    "seed": 1,
}
START_EPOCH_MS = 1704067200000  # the first timestamp written by tools/perf/generate_jtl.py; the grids start with the load
LOAD_ROW_MS = 10
MAX_PODS = 256
MAX_ROWS = 2560
MAX_SNAPSHOT_POINTS = 100000
MAX_SNAPSHOT_CELLS = 1500000
MAX_SNAPSHOT_BYTES = 32 * 1024 * 1024
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
# (permille) base value of the (container index 0 = app, others) of each metric
BASES = {
    "openshift_container_cpu_limit_ratio": (400, 150),
    "openshift_container_memory_limit_ratio": (450, 200),
    "openshift_cpu_throttling": (30, 10),
    "openshift_pod_cpu_usage": (500, 500),
}
SERVICE_METRICS = ("openshift_container_cpu_limit_ratio", "openshift_container_memory_limit_ratio", "openshift_cpu_throttling")
MEAN_CONTEXT = Context(prec=60, rounding=ROUND_HALF_EVEN)
SIX = Decimal("0.000001")


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


def normalize(spec: dict) -> dict:
    unknown = set(spec) - set(DEFAULTS)
    if unknown:
        raise AdapterError(f"unknown spec fields: {sorted(unknown)}")
    full = {**DEFAULTS, **spec}
    if full["scenario"] not in SCENARIOS:
        raise AdapterError(f"scenario must be one of {SCENARIOS}")
    for key in ("namespace", "arm"):
        if not isinstance(full[key], str) or not NAME.fullmatch(full[key]):
            raise AdapterError(f"{key} must match {NAME.pattern}")
    count = full["services"]
    if type(count) is not int or not 1 <= count <= 64:
        raise AdapterError("services must be an integer from 1 to 64")
    per = full["pods_per_service"]
    if type(per) is int:
        per = [per] * count
    if not isinstance(per, list) or len(per) != count or any(type(item) is not int or item < 1 for item in per):
        raise AdapterError("pods_per_service must be a positive integer or a list of one per service")
    if full["scenario"] != "balanced" and per[0] < 2:
        raise AdapterError("the planted pod is the second pod of the first service: pods_per_service[0] must be at least 2")
    full["pods_per_service"] = per
    containers = full["containers"]
    if (
        not isinstance(containers, list)
        or not 1 <= len(containers) <= 4
        or len(set(containers)) != len(containers)
        or any(not isinstance(c, str) or not NAME.fullmatch(c) for c in containers)
    ):
        raise AdapterError("containers must be 1 to 4 unique names")
    try:
        re.compile(full["sidecar_containers"])
    except (re.error, TypeError) as error:
        raise AdapterError(f"sidecar_containers: {error}") from error
    for key in ("snapshot_step_ms", "pod_step_ms", "load_rows", "load_seed", "seed"):
        if type(full[key]) is not int or full[key] < 0:
            raise AdapterError(f"{key} must be a non-negative integer")
    snap, pod_step = full["snapshot_step_ms"], full["pod_step_ms"]
    if snap % 1000 or not 1000 <= snap <= 60000 or pod_step < snap or pod_step % snap:
        raise AdapterError("snapshot_step_ms is whole seconds up to 60000 and pod_step_ms a positive multiple of it")
    span = full["load_rows"] * LOAD_ROW_MS
    if span == 0 or span % snap:
        raise AdapterError("load_rows * 10 ms must be a positive multiple of snapshot_step_ms")
    columns = -(-span // pod_step)
    if columns > MAX_COLUMNS:
        raise AdapterError(f"{columns} columns exceed {MAX_COLUMNS}: raise pod_step_ms")
    if not 0 < full["load_seed"] <= MASK:
        raise AdapterError("load_seed must be between 1 and 2^64-1")
    # The limits of the core, checked before anything is written.
    pods = sum(per)
    points = span // snap
    if pods > MAX_PODS or pods * (3 * len(containers) + 1) > MAX_ROWS:
        raise AdapterError(f"{pods} pods and {pods * (3 * len(containers) + 1)} rows exceed {MAX_PODS} pods or {MAX_ROWS} rows")
    if points > MAX_SNAPSHOT_POINTS or len(SERVICE_METRICS) * count * points > MAX_SNAPSHOT_CELLS:
        raise AdapterError("the snapshot would exceed its point or cell limit: raise snapshot_step_ms or lower load_rows or services")
    if START_EPOCH_MS + span > 253402300799999:
        raise AdapterError("the grid is beyond the maximum timestamp")
    return full


def row_cells(spec: dict, pod: int, container: int, metric: int, name: str, points: int, target: bool) -> list:
    """Permille of one (pod, container, metric) at every snapshot cell."""
    base = BASES[name][0 if container == 0 else 1] + mix(spec["seed"], pod, container, metric) % 41
    cap = 2000 if name == "openshift_pod_cpu_usage" else 1000
    leak = target and container == 0 and spec["scenario"] == "leak-on-arm-b-pod-b2" and spec["arm"] == "B"
    throttle = target and container == 0 and spec["scenario"] == "throttle-on-one-pod"
    cells = []
    for i in range(points):
        value = base + mix(spec["seed"], pod, container, metric, 1000 + i) % 21 - 10
        if leak and name == "openshift_container_memory_limit_ratio":
            value += (500 * i) // max(points - 1, 1)
        if throttle and i >= points // 3:
            value += {"openshift_cpu_throttling": 570, "openshift_container_cpu_limit_ratio": 500}.get(name, 0)
        cells.append(min(max(value, 0), cap))
    return cells


def milli_text(milli: int) -> str:
    return f"{milli // 1000}.{milli % 1000:03d}"


def column_token(cells: list, ratio: int, column: int) -> Raw:
    chunk = cells[column * ratio:(column + 1) * ratio]
    return Raw(format_value(MEAN_CONTEXT.divide(Decimal(sum(chunk)), Decimal(1000 * len(chunk)))))


def plain(value: Decimal) -> str:
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def reference(rows: list) -> list:
    """max, mean (six decimals) and last over the observed columns of every row; an all-null row gives nulls."""
    result = []
    for row in rows:
        numbers = [Decimal(v) for v in row["values"] if v is not None]
        mean = MEAN_CONTEXT.divide(sum(numbers, Decimal(0)), Decimal(len(numbers))).quantize(SIX, ROUND_HALF_EVEN) if numbers else None
        last = next((v for v in reversed(row["values"]) if v is not None), None)
        result.append(
            {
                "id": row["id"],
                "max": Raw(plain(max(numbers))) if numbers else None,
                "mean": Raw(plain(mean)) if numbers else None,
                "last": Raw(str(last)) if last is not None else None,
            }
        )
    return result


def generate(spec: dict, out_dir) -> dict:
    spec = normalize(spec)
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    load_path = out / "load.jtl"
    generate_jtl.generate(spec["load_rows"], spec["load_seed"], load_path)
    load_sha = sha256(load_path.read_bytes()).hexdigest()

    snap, pod_step = spec["snapshot_step_ms"], spec["pod_step_ms"]
    points = spec["load_rows"] * LOAD_ROW_MS // snap
    ratio = pod_step // snap
    columns = -(-points // ratio)
    containers = spec["containers"]
    metric_names = list(METRICS)
    letter = spec["arm"].lower()

    pod_observations = []
    snapshot_series = []
    service_cells = {name: [0] * points for name in SERVICE_METRICS}
    ordinal = 0
    for s, pod_count in enumerate(spec["pods_per_service"]):
        service = f"svc-{s + 1:02d}"
        for k in range(1, pod_count + 1):
            pod = f"{service}-{letter}{k}"
            target = s == 0 and k == 2
            for m, name in enumerate(metric_names):
                level = METRICS[name][0]
                for c, container in enumerate(containers if level == "container" else [None]):
                    cells = row_cells(spec, ordinal, c, m, name, points, target)
                    if name in service_cells:
                        service_cells[name] = [max(a, b) for a, b in zip(service_cells[name], cells, strict=True)]
                    values = [column_token(cells, ratio, column) for column in range(columns)]
                    pod_observations.append(Observation(spec["namespace"], service, pod, container, name, values))
            ordinal += 1
        # The snapshot carries one series per (service, metric): flush the running maxima of this service.
        for name in SERVICE_METRICS:
            snapshot_series.append((service, name, service_cells[name]))
            service_cells[name] = [0] * points

    snapshot = {
        "schema_version": "resource-snapshot.v1",
        "load_input_sha256": load_sha,
        "start_epoch_ms": START_EPOCH_MS,
        "step_ms": snap,
        "point_count": points,
        "series": [
            {
                "id": f"{service}.{name}",
                "metric": name,
                "unit": METRICS[name][1].unit,
                "entity": service,
                "role": "system",
                "aggregation": METRICS[name][1].aggregation,
                "labels": {"arm": spec["arm"], "namespace": spec["namespace"]},
                "values": [Raw(milli_text(v)) for v in values],
            }
            for service, name, values in snapshot_series
        ],
        "provenance": {"source_kind": "synthetic", "query_semantics": "declared interval aggregation", "clock_alignment": "declared_aligned"},
    }
    snapshot_text = dumps(snapshot) + "\n"
    snapshot_sha = stats_validation.snapshot_hash(json.loads(snapshot_text, parse_float=Decimal, parse_int=Decimal))

    document = assemble(
        pod_observations,
        load_sha256=load_sha,
        snapshot_sha256=snapshot_sha,
        arm=spec["arm"],
        start_ms=START_EPOCH_MS,
        step_ms=pod_step,
        columns=columns,
        sidecar=re.compile(spec["sidecar_containers"]),
        select=None,
        qualify=False,
    )
    rows = reference(document["rows"])
    worst = {}
    for name in metric_names:
        best = None
        for row, ref in zip(document["rows"], rows, strict=True):
            if row["metric"] == name and ref["max"] is not None:
                # The first row in file order wins a tie: the smaller (service, pod) in UTF-8 bytes.
                if best is None or Decimal(ref["max"]) > best[0]:
                    best = (Decimal(ref["max"]), row["pod"])
        worst[name] = best[1] if best else None
    effect = None
    target_pod = f"svc-01-{letter}2"
    if spec["scenario"] == "leak-on-arm-b-pod-b2" and spec["arm"] == "B":
        effect = {"kind": "MEMORY_LEAK", "pod": target_pod, "container": containers[0], "metric": "openshift_container_memory_limit_ratio"}
    if spec["scenario"] == "throttle-on-one-pod":
        effect = {"kind": "CPU_THROTTLING", "pod": target_pod, "container": containers[0], "metric": "openshift_cpu_throttling"}
    expected = {
        "schema_version": "pod-view-synthetic-expected.v1",
        "spec": spec,
        "load_input_sha256": load_sha,
        "resource_snapshot_sha256": snapshot_sha,
        "grid": {
            "start_epoch_ms": START_EPOCH_MS,
            "step_ms": pod_step,
            "column_count": columns,
            "snapshot_step_ms": snap,
            "point_count": points,
        },
        "coverage": document["coverage"],
        "planted_effect": effect,
        "worst_pod_by_max": worst,
        "rows": rows,
    }
    files = {
        "resource-snapshot.json": snapshot_text,
        "pod-view.json": dumps(document) + "\n",
        "expected.json": dumps(expected) + "\n",
    }
    if len(files["pod-view.json"].encode()) > MAX_FILE_BYTES or len(snapshot_text.encode()) > MAX_SNAPSHOT_BYTES:
        raise AdapterError("a generated file is above the size limit of its contract")
    for name, text in files.items():
        (out / name).write_bytes(text.encode("utf-8"))
    return {"load_input_sha256": load_sha, "resource_snapshot_sha256": snapshot_sha}


def generate_from_file(spec_path, out_dir) -> dict:
    try:
        with open(spec_path, encoding="utf-8") as stream:
            spec = json.load(stream)
    except (OSError, ValueError) as error:
        raise AdapterError(f"spec: {error}") from error
    if not isinstance(spec, dict):
        raise AdapterError("spec must be a JSON object")
    return generate(spec, out_dir)

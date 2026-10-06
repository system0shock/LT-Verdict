"""Deterministic input sets for the pod-view.v1 limits measurement (platform P2e, ADR 0020).

Writes into --out: load.jtl (8 hours at 10 ms per row), snapshot-<ARM>.json (1 024 series x 1 440 points at 20 s, the
C_max of ADR 0014), pod-view-<SHAPE>-<ARM>.json bound to them (240 columns at 120 s) and manifest.json (bytes and SHA-256
of every file). Nothing here runs LT Verdict. The shapes are the ones of the plan: F (78 pods x 7 pod rows), C (78 pods x 12
rows), LIMIT (256 pods, 64 services, 4 containers, 2 560 rows), LIMIT-WORST (the same with 12-byte tokens and identifiers at
their byte limits), ONE-SERVICE (2 560 rows in one service), and over-limit inputs for the negative checks.
Run from the repository root: python -m tools.perf.pod_view_limits --out DIR [--shapes F,C] [--arms A,B,C].
"""

import argparse
import json
import random
from decimal import Decimal
from hashlib import sha256
from pathlib import Path

from tools import stats_validation
from tools.perf import generate_jtl

START_MS = 1704067200000  # the first timestamp written by tools/perf/generate_jtl.py
LOAD_ROWS = 2_880_000  # 8 hours at 10 ms per row
SNAPSHOT_SERIES = 1024
SNAPSHOT_POINTS = 1440
SNAPSHOT_STEP_MS = 20_000
POD_STEP_MS = 120_000
COLUMNS = 240
LIMIT_BYTES = 12 * 1024 * 1024
CONTAINER_NAMES = ["app", "istio-proxy", "otel-agent", "log-agent", "extra-agent"]
POD_METRICS = ["cpu_usage", "cpu_throttling", "memory_usage", "restarts", "heap_old_gen", "gc_pause", "sidecar_memory"]
CONTAINER_METRICS = ["cpu_limit_ratio", "memory_limit_ratio", "cpu_throttling", "memory_working_set", "restarts", "gc_pause"]

# name -> (pods, services, rows per container in container order (None = pod level), worst, over-limit flag)
SHAPES = {
    "F": dict(pods=78, services=20, rows=[7], pod_level=True),
    "C": dict(pods=78, services=20, rows=[6, 3, 3]),
    "LIMIT": dict(pods=256, services=64, rows=[4, 2, 2, 2]),
    "LIMIT-WORST": dict(pods=256, services=64, rows=[4, 2, 2, 2], worst=True),
    "ONE-SERVICE": dict(pods=256, services=1, rows=[4, 2, 2, 2]),
    "OVER-ROWS": dict(pods=256, services=64, rows=[6, 3, 3]),  # 3 072 rows
    "OVER-ROWS-WORST": dict(pods=256, services=64, rows=[6, 3, 3], worst=True),  # 3 072 rows, the ADR 0020 R_max alternative
    "OVER-PODS": dict(pods=257, services=64, rows=[3, 2, 2, 2]),  # 2 313 rows
    "OVER-SERVICES": dict(pods=65, services=65, rows=[6, 3, 3]),
    "OVER-CONTAINERS": dict(pods=10, services=2, rows=[2, 1, 1, 1, 1]),
    "OVER-COLUMNS": dict(pods=78, services=20, rows=[6, 3, 3], columns=241),
    "OVER-BYTES": dict(pods=256, services=64, rows=[4, 2, 2, 2], worst=True, pad_to=LIMIT_BYTES + 1),
    "ADV-WIDE": dict(adversarial="wide"),
    "ADV-ROWS": dict(adversarial="rows"),
}
DEFAULT_SHAPES = ["F", "C", "LIMIT", "LIMIT-WORST", "ONE-SERVICE"]
MASK = (1 << 64) - 1


def text(value: str) -> str:
    return json.dumps(value)


def padded(prefix: str, size: int) -> str:
    return prefix + "x" * (size - len(prefix))


def make_snapshot(arm: str, load_sha: str, seed: int = 1) -> tuple:
    rng = random.Random(seed)
    parts = []
    for n in range(SNAPSHOT_SERIES):
        service = f"svc-{n // 16:02d}"
        metric = f"metric_{n % 16:02d}"
        values = ",".join("null" if rng.random() < 0.02 else f"0.{rng.randrange(10**6):06d}" for _ in range(SNAPSHOT_POINTS))
        parts.append(
            '{"id":%s,"metric":"%s","unit":"ratio","entity":"%s","role":"system","aggregation":"interval_mean",'
            '"labels":{"arm":%s,"namespace":"perf"},"values":[%s]}' % (text(f"{service}.{metric}"), metric, service, text(arm), values)
        )
    body = (
        '{"schema_version":"resource-snapshot.v1","load_input_sha256":"%s","start_epoch_ms":%d,"step_ms":%d,"point_count":%d,'
        '"series":[%s],"provenance":{"source_kind":"synthetic","query_semantics":"declared interval aggregation",'
        '"clock_alignment":"declared_aligned"}}\n' % (load_sha, START_MS, SNAPSHOT_STEP_MS, SNAPSHOT_POINTS, ",".join(parts))
    )
    semantic = stats_validation.snapshot_hash(json.loads(body, parse_float=Decimal, parse_int=Decimal))
    return body, semantic


def make_pod_view(shape: str, arm: str, load_sha: str, snapshot_sha: str, seed: int = 1) -> str:
    spec = SHAPES[shape]
    if spec.get("adversarial"):
        return make_adversarial(spec["adversarial"], arm, load_sha, snapshot_sha)
    rng = random.Random(seed)
    worst = spec.get("worst", False)
    columns = spec.get("columns", COLUMNS)
    pods_total, services, per_container = spec["pods"], spec["services"], spec["rows"]
    pod_level = spec.get("pod_level", False)
    names = CONTAINER_NAMES[: len(per_container)]
    if worst:
        names = [padded(f"c{i}-", 128) for i in range(len(per_container))]
    pods, rows = [], []
    for p in range(pods_total):
        service = f"svc-{p % services:02d}"
        pod = f"{service}-{p:03d}"
        if worst:
            service = padded(f"svc-{p % services:02d}-", 128)
            pod = padded(f"{service[:7]}pod-{p:03d}-", 253)
        containers = [{"name": name, "role": "app" if i == 0 else "sidecar"} for i, name in enumerate(names)]
        pods.append('{"pod":%s,"service":%s,"containers":%s}' % (text(pod), text(service), json.dumps(containers, separators=(",", ":"))))
        for index, count in enumerate(per_container):
            for m in range(count):
                if pod_level:
                    container, metric = "null", POD_METRICS[m]
                else:
                    container, metric = text(names[index]), CONTAINER_METRICS[(m + index) % len(CONTAINER_METRICS)]
                unit = "ratio"
                if worst:
                    metric = padded(f"{metric}-{index}-", 128)
                    unit = padded("ratio-", 128)
                row_id = padded(f"r{len(rows) + 1:05d}-", 128) if worst else f"r{len(rows) + 1:05d}"
                if worst:
                    values = ",".join(f"-0.{rng.randrange(10**9):09d}" for _ in range(columns))
                else:
                    values = ",".join("null" if rng.random() < 0.02 else f"0.{rng.randrange(10**4):04d}" for _ in range(columns))
                rows.append(
                    '{"id":%s,"pod":%s,"container":%s,"metric":%s,"unit":%s,"aggregation":"interval_mean","values":[%s]}'
                    % (text(row_id), text(pod), container, text(metric), text(unit), values)
                )
    document = (
        '{"schema_version":"pod-view.v1","load_input_sha256":"%s","resource_snapshot_sha256":"%s","arm":%s,"start_epoch_ms":%d,'
        '"step_ms":%d,"column_count":%d,"coverage":{"pods_observed_total":%d,"pods_included":%d,"rows_observed_total":%d,'
        '"rows_included":%d,"selection":{"kind":"ALL"}},"pods":[%s],"rows":[%s]}'
        % (load_sha, snapshot_sha, text(arm), START_MS, POD_STEP_MS, columns, pods_total, pods_total, len(rows), len(rows), ",".join(pods), ",".join(rows))
    )
    pad_to = spec.get("pad_to")
    if pad_to:
        document += " " * (pad_to - len(document.encode("utf-8")))
    return document


def make_adversarial(kind: str, arm: str, load_sha: str, snapshot_sha: str) -> str:
    """Inputs inside 12 MiB that are built to cost many tree nodes: one very wide row, or very many tiny rows."""
    head = '{"schema_version":"pod-view.v1","load_input_sha256":"%s","resource_snapshot_sha256":"%s","arm":%s,"start_epoch_ms":%d,"step_ms":%d,' % (
        load_sha,
        snapshot_sha,
        text(arm),
        START_MS,
        POD_STEP_MS,
    )
    pods = '"pods":[{"pod":"p","service":"s","containers":[{"name":"c","role":"app"}]}]'
    if kind == "wide":
        count = (LIMIT_BYTES - 2048) // 2
        coverage = '"coverage":{"pods_observed_total":1,"pods_included":1,"rows_observed_total":1,"rows_included":1,"selection":{"kind":"ALL"}}'
        row = '{"id":"a","pod":"p","container":"c","metric":"m","unit":"u","aggregation":"interval_mean","values":[%s0]}' % ("0," * (count - 1))
        return head + '"column_count":%d,%s,%s,"rows":[%s]}' % (count, coverage, pods, row)
    tiny = '{"id":"%d","pod":"p","container":null,"metric":"m","unit":"u","aggregation":"interval_mean","values":[0]}'
    rows, size, n = [], 0, 0
    while size < LIMIT_BYTES - 4096:
        n += 1
        row = tiny % n
        rows.append(row)
        size += len(row) + 1
    coverage = '"coverage":{"pods_observed_total":1,"pods_included":1,"rows_observed_total":%d,"rows_included":%d,"selection":{"kind":"ALL"}}' % (n, n)
    return head + '"column_count":1,%s,%s,"rows":[%s]}' % (coverage, pods, ",".join(rows))


def write(out: Path, name: str, content: str, manifest: dict) -> None:
    data = content.encode("utf-8")
    (out / name).write_bytes(data)
    manifest[name] = {"bytes": len(data), "sha256": sha256(data).hexdigest()}


def generate(out_dir, shapes: list, arms: list, load_rows: int = LOAD_ROWS) -> dict:
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    manifest_path = out / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    load_path = out / "load.jtl"
    if not load_path.exists():
        generate_jtl.generate(load_rows, 1, load_path)
    load_sha = sha256(load_path.read_bytes()).hexdigest()
    manifest["load.jtl"] = {"bytes": load_path.stat().st_size, "sha256": load_sha}
    for arm in arms:
        snapshot_text, snapshot_sha = make_snapshot(arm, load_sha, seed=ord(arm[0]))
        write(out, f"snapshot-{arm}.json", snapshot_text, manifest)
        manifest[f"snapshot-{arm}.json"]["semantic_sha256"] = snapshot_sha
        for shape in shapes:
            write(out, f"pod-view-{shape}-{arm}.json", make_pod_view(shape, arm, load_sha, snapshot_sha, seed=ord(arm[0])), manifest)
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--shapes", default=",".join(DEFAULT_SHAPES))
    parser.add_argument("--arms", default="A")
    parser.add_argument("--load-rows", type=int, default=LOAD_ROWS)
    args = parser.parse_args()
    shapes = [item for item in args.shapes.split(",") if item]
    unknown = [item for item in shapes if item not in SHAPES]
    if unknown:
        parser.error(f"unknown shapes: {unknown}; known: {sorted(SHAPES)}")
    manifest = generate(args.out, shapes, [item for item in args.arms.split(",") if item], args.load_rows)
    for name, entry in manifest.items():
        print(f"{name}\t{entry['bytes']}\t{entry['sha256']}")


if __name__ == "__main__":
    main()

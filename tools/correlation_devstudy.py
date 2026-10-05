"""Paired development study for correlation headline selection."""

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import subprocess
import sys

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import correlation_oracle as oracle
import correlation_scenarios as cs


VARIANTS = ("levels", "first_difference", "detrend")
ALPHA = 0.05
MIN_ABS_EFFECT = 0.3
GATE_N = 1000
GATE_WILSON_UPPER = 0.07
DEV_SEED_MIN = 10000
DEV_SEED_MAX = 99999


def detrend(values):
    y = np.asarray(values, dtype=float)
    n = len(y)
    if not n:
        return []
    t = np.arange(n, dtype=float)
    st, sy = t.sum(), y.sum()
    denominator = n * np.dot(t, t) - st * st
    slope = (n * np.dot(t, y) - st * sy) / denominator if denominator else 0.0
    intercept = (sy - slope * st) / n
    return (y - (intercept + slope * t)).tolist()


def best_lag(profile, max_lag):
    candidates = [(i - max_lag, rho) for i, rho in enumerate(profile) if rho is not None]
    return max(candidates, key=lambda item: (abs(item[1]), -abs(item[0]), -item[0])) if candidates else None


def run_bounds(values):
    best = (0, 0)
    start = 0
    for i, value in enumerate([*values, None]):
        if value is None:
            if i - start > best[1] - best[0]:
                best = (start, i)
            start = i + 1
    return best


def early_stop(k, n):
    return oracle.wilson_upper(k, n) > GATE_WILSON_UPPER


def stage_results(variant, stage, max_lag, seed_material, family_count):
    if variant not in VARIANTS:
        raise ValueError("unknown variant")
    a, b = run_bounds(stage["outcome"])
    outcome = stage["outcome"][a:b]
    resources = [row[a:b] for row in stage["resources"]]
    if variant == "detrend":
        outcome = detrend(outcome)
        resources = [detrend(row) for row in resources]
    transformed_y = np.diff(outcome) if variant == "first_difference" else outcome
    n = len(transformed_y)
    eligible = 30 <= n <= 240 and 0 <= max_lag <= 10 and n - 2 * max_lag >= 30
    best = []
    hypotheses = []
    for i, resource in enumerate(resources):
        transformed_x = np.diff(resource) if variant == "first_difference" else resource
        bm = best_lag(oracle.lag_profile(transformed_x, transformed_y, max_lag), max_lag) if eligible else None
        best.append(bm)
        hypotheses.append(oracle.Hypothesis(str(i), "stage", resource, outcome, max_lag,
                                             bm is not None and abs(bm[1]) >= MIN_ABS_EFFECT))
    representation = "first_difference" if variant == "first_difference" else "levels"
    selections = oracle.select(hypotheses, seed_material, representation)
    rows = []
    for selected, bm in zip(selections, best):
        available = selected.status != "UNAVAILABLE"
        pub_nomat = selected.holm_adjusted is not None and selected.holm_adjusted <= ALPHA / family_count
        rows.append({"pub": bool(pub_nomat and bm is not None and abs(bm[1]) >= MIN_ABS_EFFECT),
                     "pub_nomat": bool(pub_nomat),
                     "lag": bm[0] if available and bm is not None else None,
                     "sign": (1 if bm[1] > 0 else -1) if available and bm is not None else None,
                     "avail": available})
    return rows


def score(trace, stages):
    truth = trace["truth"]
    rows = [stage[i] for stage in stages for i in range(len(stage))]
    nulls = set(truth["null_hypotheses"])
    false = any(stage[i]["pub"] for stage in stages for i in nulls)
    false_nomat = any(stage[i]["pub_nomat"] for stage in stages for i in nulls)
    positive = truth["planted"] or truth["level_effect"]
    if positive is None:
        detected = detected_nomat = None
    else:
        index = positive["index"]
        lag = positive.get("lag_cells", 0)
        sign = positive.get("sign", 1)

        def found(field):
            return any(stage[index][field] and stage[index]["sign"] == sign
                       and stage[index]["lag"] is not None
                       and abs(stage[index]["lag"] - lag) <= 1 for stage in stages)

        detected, detected_nomat = found("pub"), found("pub_nomat")
    activation = trace["activation"]
    return {"false": bool(false), "false_nomat": bool(false_nomat),
            "detected": detected, "detected_nomat": detected_nomat,
            "evaluated": any(row["avail"] for row in rows),
            "activated": None if activation is None else bool(activation["violated"])}


def evaluate(variant, trace):
    if variant not in VARIANTS:
        raise ValueError("unknown variant")
    stages = []
    if trace["activation"] is None or trace["activation"]["violated"]:
        for s, stage in enumerate(trace["stages"], 1):
            seed_material = "%s/%d/%d" % (trace["scenario_id"], trace["seed"], s)
            stages.append(stage_results(variant, stage, trace["max_lag_cells"],
                                        seed_material, len(trace["stages"])))
    return score(trace, stages)


def process_entry(entry):
    scenario_id, seed = entry
    trace = cs.generate(scenario_id, seed)
    return {"scenario_id": scenario_id, "seed": seed,
            "variants": {variant: evaluate(variant, trace) for variant in VARIANTS}}


def run(variant, scenario_id, n, seed0):
    if variant not in VARIANTS:
        raise ValueError("unknown variant")
    results = [evaluate(variant, cs.generate(scenario_id, seed)) for seed in range(seed0, seed0 + n)]
    return {"reports": n, "false_reports": sum(row["false"] for row in results),
            "false_reports_nomat": sum(row["false_nomat"] for row in results),
            "detections": None if not results or results[0]["detected"] is None
            else sum(row["detected"] for row in results),
            "evaluated": sum(row["evaluated"] for row in results),
            "activated": None if not results or results[0]["activated"] is None
            else sum(row["activated"] for row in results)}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=True, allow_nan=False)


def build_manifest(scenarios, seed0, count, label):
    if seed0 < DEV_SEED_MIN or count < 1 or seed0 + count - 1 > DEV_SEED_MAX:
        raise ValueError("development seed range exceeded")
    for scenario_id in scenarios:
        if scenario_id not in cs.SCENARIO_IDS:
            raise KeyError(scenario_id)
    entries = [[scenario_id, seed] for scenario_id in scenarios
               for seed in range(seed0, seed0 + count)]
    return {"schema": "ltv-correlation-devstudy-manifest/v1", "label": label,
            "variants": list(VARIANTS), "alpha": ALPHA,
            "min_abs_effect": MIN_ABS_EFFECT, "bootstrap_b": 999,
            "seed0": seed0, "count": count, "scenarios": list(scenarios),
            "entries": entries,
            "entries_sha256": sha256(canonical(entries).encode("utf-8")).hexdigest()}


def shard_entries(manifest, index, count):
    return manifest["entries"][index::count]


def run_shard(manifest, index, count, out_path, process=process_entry):
    path = Path(out_path)
    finished = set()
    if path.exists():
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                record = json.loads(line)
                finished.add((record["scenario_id"], record["seed"]))
    with path.open("a", encoding="utf-8", newline="\n") as stream:
        for entry in shard_entries(manifest, index, count):
            if tuple(entry) not in finished:
                stream.write(canonical(process(entry)) + "\n")
                stream.flush()


def merge(manifest, shard_paths, out_path):
    expected = [tuple(entry) for entry in manifest["entries"]]
    expected_set = set(expected)
    found = {}
    seen = set()
    duplicate = []
    unexpected = []
    for path in shard_paths:
        with Path(path).open(encoding="utf-8") as stream:
            for line in stream:
                record = json.loads(line)
                key = (record["scenario_id"], record["seed"])
                if key in seen and list(key) not in duplicate:
                    duplicate.append(list(key))
                seen.add(key)
                if key not in expected_set:
                    if list(key) not in unexpected:
                        unexpected.append(list(key))
                else:
                    found[key] = record
    missing = [list(key) for key in expected if key not in found]
    if missing or duplicate or unexpected:
        Path(out_path).unlink(missing_ok=True)  # never leave a stale merged file
        return {"status": "INCOMPLETE", "missing": missing,
                "duplicate": duplicate, "unexpected": unexpected}
    data = "".join(canonical(found[key]) + "\n" for key in expected).encode("utf-8")
    Path(out_path).write_bytes(data)
    return {"status": "COMPLETE", "records": len(expected),
            "sha256": sha256(data).hexdigest()}


def run_sharded(manifest_path, shards, outdir):
    if shards < 1 or shards > (os.cpu_count() or 1):
        raise ValueError("shard count exceeds available CPUs")
    outdir = Path(outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    if any(outdir.glob("shard-*.jsonl")) or (outdir / "merged.jsonl").exists():
        raise FileExistsError("output directory already holds results; use a new one")
    manifest_path = Path(manifest_path).resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    env = os.environ.copy()
    for name in ("OMP_NUM_THREADS", "OPENBLAS_NUM_THREADS", "MKL_NUM_THREADS", "NUMEXPR_NUM_THREADS"):
        env[name] = "1"
    flags = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0) if os.name == "nt" else 0
    paths = [outdir / f"shard-{i}-of-{shards}.jsonl" for i in range(shards)]
    workers = [subprocess.Popen([sys.executable, __file__, "shard", "--manifest", str(manifest_path),
                                 "--index", str(i), "--count", str(shards), "--out", str(path)],
                                env=env, creationflags=flags)
               for i, path in enumerate(paths)]
    if any(code != 0 for code in [worker.wait() for worker in workers]):
        raise RuntimeError("shard process failed")
    return merge(manifest, paths, outdir / "merged.jsonl")


def summarize(records):
    summary = {}
    for record in records:
        scenario = summary.setdefault(record["scenario_id"], {})
        for variant, result in record["variants"].items():
            row = scenario.setdefault(variant, {"reports": 0, "false": 0, "false_nomat": 0,
                                                 "detected": None, "detected_nomat": None,
                                                 "evaluated": 0, "activated": None})
            row["reports"] += 1
            for field in ("false", "false_nomat", "evaluated"):
                row[field] += int(result[field])
            for field in ("detected", "detected_nomat", "activated"):
                if result[field] is not None:
                    row[field] = (row[field] or 0) + int(result[field])
    for scenario in summary.values():
        for row in scenario.values():
            n = row["reports"]
            for field, output in (("false", "false_wilson"),
                                  ("false_nomat", "false_nomat_wilson"),
                                  ("detected", "detected_wilson")):
                k = row[field]
                row[output] = None if k is None else [oracle.wilson_lower(k, n), oracle.wilson_upper(k, n)]
    return summary


def summary_markdown(summary):
    lines = ["| scenario | variant | reports | false (k) | false rate | Wilson 95% | false without materiality (k) | detected (k) | detection rate | evaluated | activated |",
             "| --- | --- | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: |"]
    for scenario, variants in summary.items():
        for variant, row in variants.items():
            n = row["reports"]
            lo, hi = row["false_wilson"]
            detected = row["detected"]
            activated = row["activated"]
            lines.append("| %s | %s | %d | %d | %.1f%% | %.1f%%-%.1f%% | %d | %s | %s | %d | %s |" %
                         (scenario, variant, n, row["false"], 100 * row["false"] / n,
                          100 * lo, 100 * hi, row["false_nomat"],
                          "-" if detected is None else str(detected),
                          "-" if detected is None else "%.1f%%" % (100 * detected / n),
                          row["evaluated"], "-" if activated is None else str(activated)))
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    manifest_cmd = commands.add_parser("manifest")
    for name in ("scenarios", "label", "out"):
        manifest_cmd.add_argument("--" + name, required=True)
    for name in ("seed0", "count"):
        manifest_cmd.add_argument("--" + name, type=int, required=True)
    shard_cmd = commands.add_parser("shard")
    for name in ("manifest", "out"):
        shard_cmd.add_argument("--" + name, required=True)
    for name in ("index", "count"):
        shard_cmd.add_argument("--" + name, type=int, required=True)
    sharded_cmd = commands.add_parser("run-sharded")
    sharded_cmd.add_argument("--manifest", required=True)
    sharded_cmd.add_argument("--shards", type=int, required=True)
    sharded_cmd.add_argument("--outdir", required=True)
    merge_cmd = commands.add_parser("merge")
    merge_cmd.add_argument("--manifest", required=True)
    merge_cmd.add_argument("--shards", nargs="+", required=True)
    merge_cmd.add_argument("--out", required=True)
    summary_cmd = commands.add_parser("summary")
    for name in ("merged", "out-json", "out-md"):
        summary_cmd.add_argument("--" + name, required=True)
    args = parser.parse_args()
    if args.command == "manifest":
        value = build_manifest(args.scenarios.split(","), args.seed0, args.count, args.label)
        Path(args.out).write_text(canonical(value) + "\n", encoding="utf-8", newline="\n")
        return 0
    if args.command == "summary":
        with Path(args.merged).open(encoding="utf-8") as stream:
            value = summarize(json.loads(line) for line in stream)
        Path(args.out_json).write_text(canonical(value) + "\n", encoding="utf-8", newline="\n")
        Path(args.out_md).write_text(summary_markdown(value), encoding="utf-8", newline="\n")
        return 0
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    if args.command == "shard":
        run_shard(manifest, args.index, args.count, args.out)
        return 0
    if args.command == "run-sharded":
        result = run_sharded(args.manifest, args.shards, args.outdir)
    else:
        result = merge(manifest, args.shards, args.out)
    return 0 if result["status"] == "COMPLETE" else 2


if __name__ == "__main__":
    raise SystemExit(main())

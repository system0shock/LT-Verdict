"""C5 acceptance driver (H4): corpus freeze, sharded JVM runner, merge and scorer.

Inputs for the JVM are mapped from generator traces as declared in MAPPING below; the JVM side is
StatisticalValidationTest.kt (CorrelationAcceptanceShard). Seeds of the C5 acceptance space
(100000..199999) are refused unless acceptance=True is passed; development runs use 10000..99999.
"""

import argparse
from concurrent.futures import ProcessPoolExecutor
from decimal import Decimal, ROUND_HALF_EVEN
from hashlib import sha256
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).parent))
import correlation_oracle as oracle
import correlation_scenarios as cs
import stats_validation as wire


STEP_MS = 1000  # The product accepts at most 60 s of lag and 10 cells: N08 (10 cells) needs a step of at most 6 s.
REQUESTS_PER_CELL = 20  # MIN_P95_SAMPLES; all requests of a cell share one elapsed value, so p95 is that value.
LOAD_MS_LOW, LOAD_MS_HIGH = 100, 1900  # HDR histogram (3 digits) is exact below 2048 ms.
RESOURCE_BASE, RESOURCE_SCALE = 100.0, 10.0
TARGET_RPS = 100.0
MIN_ABS_EFFECT, MIN_RESOURCE_DELTA, MIN_LOAD_DELTA_MS = 0.3, 0.1, 20
GATE_REPORTS = 1000
NOISE_GATE_UPPER = 0.07
DETECTION_GATE = 0.9
ACTIVATION_MIN = 0.3
SHARD_CLASS = "io.ltverdict.core.CorrelationAcceptanceShard"
MAPPING = {
    "step_ms": STEP_MS, "requests_per_cell": REQUESTS_PER_CELL, "resource": "100 + 10 * z, 4 decimals",
    "outcome": "per stage affine map of min..max to 100..1900 ms, integer, 20 identical requests per cell",
    "target": f"constant {TARGET_RPS} requests/s control", "gap": "null resources and target, no requests; stage window trimmed to the first and last cell with requests",
    "min_abs_effect": MIN_ABS_EFFECT, "min_resource_delta": MIN_RESOURCE_DELTA, "min_load_delta_ms": MIN_LOAD_DELTA_MS,
}
DEV_SEEDS, ACCEPTANCE_SEEDS = (10000, 99999), (100000, 199999)


def number(value):
    rounded = Decimal(repr(float(value))).quantize(Decimal("0.0001"), rounding=ROUND_HALF_EVEN)
    return float(rounded) + 0.0


def canonical_text(value):
    return wire._canonical(value)


def _sha(data):
    return sha256(data).hexdigest()


def _outcome_levels(row):
    values = [v for v in row if v is not None]
    if not values:
        return list(row)
    low, high = min(values), max(values)
    scale = (LOAD_MS_HIGH - LOAD_MS_LOW) / (high - low) if high > low else 0.0
    return [None if v is None else (LOAD_MS_LOW + LOAD_MS_HIGH) // 2 if high == low
            else int(round(LOAD_MS_LOW + (v - low) * scale)) for v in row]


def build_input(trace):
    """Return (operation or None, meta). None means the report is not activated (N11, no plan)."""
    activation = trace["activation"]
    if activation is not None and not activation["violated"]:
        return None, {"activated": False, "tie_fraction": 0.0}
    stages = trace["stages"]
    count, step = trace["hypotheses"], STEP_MS
    resources = [[] for _ in range(count)]
    levels, windows, ties, offset = [], [], [], 0
    for number_, stage in enumerate(stages, 1):
        n = stage["source_cells"]
        for i in range(count):
            resources[i] += [None if v is None else number(RESOURCE_BASE + RESOURCE_SCALE * v) for v in stage["resources"][i]]
        mapped = _outcome_levels(stage["outcome"])
        present = [v for v in mapped if v is not None]
        ties.append(1 - len(set(present)) / len(present) if present else 0.0)
        levels += mapped
        # The product requires every window inside the load run: trim edge cells without any request.
        data = [i for i, v in enumerate(mapped) if v is not None]
        windows.append((f"stage-{number_}", offset + data[0], offset + data[-1] + 1))
        offset += n
    target = [None if v is None else number(v) for stage in stages for v in stage["target"]]
    series = [wire._series(row, f"resource-{i:02d}") for i, row in enumerate(resources)]
    series.append(wire._series(target, "target", "requests/s"))
    snapshot = wire._snapshot(series, windows, step)
    rows = ["timeStamp,elapsed,label,success"]
    for cell, level in enumerate(levels):
        if level is None:
            continue
        for request in range(REQUESTS_PER_CELL):
            stamp = snapshot["start_epoch_ms"] + cell * step + request * (step - 1) // (REQUESTS_PER_CELL - 1)
            rows.append(f"{stamp},{level},request,true")
    load = "\n".join(rows) + "\n"
    snapshot["load_input_sha256"] = _sha(load.encode())
    lag_ms = trace["max_lag_cells"] * step
    pairs = [{"id": f"h{i:02d}", "resource_series_id": f"resource-{i:02d}", "load_metric": "response_time_p95_ms",
              "window_ids": [w[0] for w in windows], "expected_sign": "either", "max_lag_ms": lag_ms,
              "min_abs_effect": MIN_ABS_EFFECT, "min_resource_delta": MIN_RESOURCE_DELTA,
              "min_load_delta": MIN_LOAD_DELTA_MS, "topology_basis": "synthetic independent resources",
              "clock_alignment": "declared_aligned", "controls": [{"meaning": "target_rps", "series_id": "target"}]}
             for i in range(count)]
    diagnostics = {"schema_version": "correlation-plan.v1", "resource_snapshot_sha256": wire.snapshot_hash(snapshot),
                   "pairs": pairs, "anomalies": []}
    operation = {"operation": "analysis", "run": {"load_jtl": load, "resources": snapshot, "diagnostics": diagnostics}}
    return operation, {"activated": True if activation is not None else None,
                       "tie_fraction": sum(ties) / len(ties)}


def _freeze_one(task):
    directory, scenario_id, seed, fixture_cells = task
    trace = cs.generate_fixture(scenario_id, fixture_cells) if fixture_cells else cs.generate(scenario_id, seed)
    operation, meta = build_input(trace)
    path = sha = None
    if operation is not None:
        data = canonical_text(operation).encode("utf-8")
        path = f"inputs/{scenario_id}/{seed}.json"
        target = Path(directory) / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        sha = _sha(data)
    return {"id": f"{scenario_id}-s{seed}", "scenario_id": scenario_id, "seed": seed, "path": path, "sha256": sha,
            "activated": meta["activated"], "stages": len(trace["stages"]), "hypotheses": trace["hypotheses"],
            "max_lag_cells": trace["max_lag_cells"], "source_cells": [s["source_cells"] for s in trace["stages"]],
            "fixture_cells": fixture_cells, "tie_fraction": meta["tie_fraction"], "truth": trace["truth"]}


def freeze(scenario_ids, seed0, count, out_dir, workers=1, acceptance=False, fixtures=()):
    """Write inputs and manifest.json (c5-corpus.v1); the manifest is written last."""
    low, high = ACCEPTANCE_SEEDS if acceptance else DEV_SEEDS
    if count < 1 or seed0 < low or seed0 + count - 1 > high:
        raise ValueError(f"seeds must lie in {low}..{high}")
    for scenario_id in scenario_ids:
        if scenario_id not in cs.SCENARIO_IDS:
            raise KeyError(scenario_id)
    out_dir = Path(out_dir)
    if (out_dir / "manifest.json").exists():
        raise FileExistsError("corpus already frozen")
    out_dir.mkdir(parents=True, exist_ok=True)
    tasks = [(str(out_dir), s, seed, None) for s in scenario_ids for seed in range(seed0, seed0 + count)]
    tasks += [(str(out_dir), fixture_id, cells, cells) for fixture_id, cells in fixtures]
    if workers > 1:
        with ProcessPoolExecutor(workers) as pool:
            cases = list(pool.map(_freeze_one, tasks, chunksize=4))
    else:
        cases = [_freeze_one(task) for task in tasks]
    manifest = {"schema_version": "c5-corpus.v1",
                "role": "acceptance" if acceptance else "development",
                "seed_range": [seed0, seed0 + count - 1], "mapping": MAPPING,
                "generator_sha256": _sha(Path(cs.__file__).read_bytes()),
                "entries_sha256": _sha(json.dumps(cases, sort_keys=True, separators=(",", ":")).encode()),
                "cases": cases}
    (out_dir / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                                           encoding="utf-8", newline="\n")
    return manifest


def load_corpus(directory):
    return json.loads((Path(directory) / "manifest.json").read_text(encoding="utf-8"))


def split(corpus, shards):
    """Shard i gets manifest positions i, i + shards, ...: it depends on the manifest only."""
    ids = [c["id"] for c in corpus["cases"]]
    return [ids[i::shards] for i in range(shards)]


def run_sharded(corpus_dir, shards, out_dir, command, deadline_s=None, env=None):
    """Start one process per shard (command placeholders {corpus} {shard} {shards} {out}); return shard files.

    Shard files are appended to, so a repeated call resumes after an interruption. A shard that is still running at
    the deadline is terminated; its finished lines stay on disk and the caller sees the missing reports in merge.
    """
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    flags = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0) if os.name == "nt" else 0
    files = [out_dir / f"shard-{i}-of-{shards}.jsonl" for i in range(shards)]
    workers = []
    for i, path in enumerate(files):
        arguments = [part.replace("{corpus}", str(corpus_dir)).replace("{shard}", str(i))
                     .replace("{shards}", str(shards)).replace("{out}", str(path)) for part in command]
        workers.append(subprocess.Popen(arguments, env=env, creationflags=flags))
    start = time.monotonic()
    while any(w.poll() is None for w in workers):
        if deadline_s is not None and time.monotonic() - start > deadline_s:
            for w in workers:
                if w.poll() is None:
                    w.terminate()
            break
        time.sleep(1)
    codes = [w.wait() for w in workers]
    if deadline_s is None and any(code != 0 for code in codes):
        raise RuntimeError(f"shard process failed: {codes}")
    return files


def merge(files):
    records, duplicates = {}, []
    for path in files:
        with Path(path).open(encoding="utf-8") as stream:
            for line in stream:
                if not line.strip():
                    continue
                record = json.loads(line)
                if record["id"] in records:
                    if record["id"] not in duplicates:
                        duplicates.append(record["id"])
                else:
                    records[record["id"]] = record
    return {"records": records, "duplicates": duplicates}


def write_actual(corpus, actual, path):
    ids = [c["id"] for c in corpus["cases"] if c["path"]]
    if actual["duplicates"] or any(i not in actual["records"] for i in ids):
        raise ValueError("INCOMPLETE: missing or duplicate reports")
    data = "".join(json.dumps(actual["records"][i], sort_keys=True, separators=(",", ":")) + "\n" for i in ids)
    Path(path).write_bytes(data.encode("utf-8"))
    return _sha(data.encode("utf-8"))


def _gates(scenario_id):
    positive = scenario_id[:3] in ("P01", "P02", "P03", "P04")
    return scenario_id[0] == "N" or positive, positive


def _keys(items):
    return [(item["pair_id"], item["window_id"]) for item in items]


def _index(pair_id):
    return int(pair_id[1:])


def _report(case, record):
    """Return (incomplete, problems, false, detected, selections)."""
    windows = [f"stage-{n}" for n in range(1, case["stages"] + 1)]
    expected = {(f"h{i:02d}", w) for i in range(case["hypotheses"]) for w in windows}
    problems = []
    for name in ("selections", "pairs", "findings"):
        keys = _keys(record.get(name, []))
        if len(keys) != len(set(keys)):
            problems.append(f"duplicate {name} key")
    selections = {key: item for key, item in zip(_keys(record["selections"]), record["selections"])}
    if set(selections) - expected:
        problems.append("unexpected selection key")
    incomplete = bool(expected - set(selections))
    selected = {key for key, item in selections.items() if item["selected"]}
    if selected != set(_keys(record["findings"])):
        problems.append("findings and selected evidence differ")
    if any(item["family_hypotheses"] != case["hypotheses"] or item["family_count"] != case["stages"]
           for item in selections.values()):
        problems.append("family size lost")
    truth = case["truth"]
    nulls = set(truth["null_hypotheses"])
    false = any(_index(p) in nulls for p, _ in selected & set(_keys(record["findings"])))
    positive = truth["planted"] or truth["level_effect"]
    detected = None
    if positive is not None:
        lag, sign = positive.get("lag_cells", 0), positive.get("sign", 1)
        by_key = {key: item for key, item in zip(_keys(record["pairs"]), record["pairs"])}
        detected = False
        for key in selected:
            item = by_key.get(key)
            if _index(key[0]) == positive["index"] and item and item["best_lag_rho"] is not None:
                rho_sign = 1 if Decimal(item["best_lag_rho"]) > 0 else -1
                detected |= rho_sign == sign and abs(item["best_lag_ms"] / STEP_MS - lag) <= 1
    return incomplete, problems, false, detected, selections


def score(corpus, actual, oracle_mismatches=None):
    oracle_mismatches = oracle_mismatches or {}
    rows = {}
    for case in corpus["cases"]:
        row = rows.setdefault(case["scenario_id"], {
            "scenario_id": case["scenario_id"], "planned": 0, "completed": 0, "incomplete": 0, "correctness": 0,
            "false": 0, "detected": 0, "activated": 0, "published": 0, "positive": False,
            "statuses": {}, "reasons": {}, "fixture": case["scenario_id"].startswith("FX")})
        row["planned"] += 1
        if case["activated"] is not False:
            record = actual["records"].get(case["id"])
            if case["id"] in actual["duplicates"] or record is None or "error" in record or "selections" not in record:
                row["incomplete"] += 1
                continue
            incomplete, problems, false, detected, selections = _report(case, record)
            if incomplete:
                row["incomplete"] += 1
                continue
            if problems or oracle_mismatches.get(case["id"]):
                row["correctness"] += 1
            row["false"] += false
            row["published"] += sum(item["selected"] for item in selections.values())
            if detected is not None:
                row["positive"] = True
                row["detected"] += detected
            for item in selections.values():
                row["statuses"][item["status"]] = row["statuses"].get(item["status"], 0) + 1
                for reason in item["reasons"]:
                    row["reasons"][reason] = row["reasons"].get(reason, 0) + 1
            if row["fixture"] and any(item["status"] == "UNAVAILABLE" for item in selections.values()):
                row["correctness"] += 1
        if case["activated"] is not None:
            row["activated"] += bool(case["activated"])
        row["completed"] += 1
    unexpected = sorted(set(actual["records"]) - {c["id"] for c in corpus["cases"]})
    for row in rows.values():
        n, noise, detection = row["completed"], *_gates(row["scenario_id"])
        row["false_rate"] = row["false"] / n if n else None
        row["wilson"] = [oracle.wilson_lower(row["false"], n), oracle.wilson_upper(row["false"], n)] if n else None
        row["detection_rate"] = row["detected"] / n if n and row["positive"] else None
        row["activation_share"] = row["activated"] / row["planned"] if row["scenario_id"] == "N11-activation" else None
        row["gated"] = noise and not row["fixture"]
        low_activation = row["activation_share"] is not None and row["activation_share"] < ACTIVATION_MIN
        if row["incomplete"] or row["completed"] != row["planned"] or low_activation:
            row["status"] = "INCOMPLETE"
        elif row["correctness"]:
            row["status"] = "CORRECTNESS FAIL"
        elif row["fixture"]:
            row["status"] = "FIXTURE_OK"
        elif not row["gated"]:
            row["status"] = "NOT_GATED"
        elif row["planned"] != GATE_REPORTS:
            row["status"] = "UNDERSIZED"  # the gates are defined for 1000 reports; the rates below are only measurements
        elif (noise and row["wilson"][1] > NOISE_GATE_UPPER) or (detection and row["detected"] < DETECTION_GATE * n):
            row["status"] = "USEFULNESS FAIL"
        else:
            row["status"] = "PASS"
    return {"scenarios": list(rows.values()), "unexpected_reports": unexpected,
            "role": corpus.get("role"), "seed_range": corpus.get("seed_range")}


def timing_summary(files, corpus):
    scenario = {c["id"]: c["scenario_id"] for c in corpus["cases"]}
    by_scenario = {}
    for path in files:
        with Path(path).open(encoding="utf-8") as stream:
            for line in stream:
                item = json.loads(line)
                by_scenario.setdefault(scenario[item["id"]], []).append(item)
    result = {}
    for name, items in by_scenario.items():
        ms = sorted(i["ms"] for i in items)
        result[name] = {"reports": len(ms), "mean_ms": statistics.fmean(ms), "median_ms": statistics.median(ms),
                        "p95_ms": ms[min(len(ms) - 1, int(0.95 * len(ms)))], "max_ms": ms[-1],
                        "max_heap_mb": max(i.get("heap_mb", 0) for i in items)}
    return result


def report_markdown(report, timing=None):
    lines = [f"Роль корпуса: {report['role']}, seeds {report['seed_range']}", "",
             "| scenario_id | запланировано | завершено | ложных | доля | Wilson 95 % | обнаружено | выбрано | статус |",
             "| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | --- |"]
    for row in report["scenarios"]:
        wilson = "-" if row["wilson"] is None else "%.2f %%-%.2f %%" % (100 * row["wilson"][0], 100 * row["wilson"][1])
        rate = "-" if row["false_rate"] is None else "%.2f %%" % (100 * row["false_rate"])
        detected = f"{row['detected']} ({100 * row['detection_rate']:.1f} %)" if row["detection_rate"] is not None else "-"
        lines.append(f"| {row['scenario_id']} | {row['planned']} | {row['completed']} | {row['false']} | {rate} | "
                     f"{wilson} | {detected} | {row['published']} | {row['status']} |")
    lines += ["", "| scenario_id | статусы семей | причины | активировано |", "| --- | --- | --- | ---: |"]
    for row in report["scenarios"]:
        statuses = ", ".join(f"{k} {v}" for k, v in sorted(row["statuses"].items())) or "-"
        reasons = ", ".join(f"{k} {v}" for k, v in sorted(row["reasons"].items())) or "-"
        lines.append(f"| {row['scenario_id']} | {statuses} | {reasons} | {row['activated'] or '-'} |")
    if timing:
        lines += ["", "| scenario_id | отчётов | среднее, мс | медиана, мс | p95, мс | максимум, мс | куча, МБ |",
                  "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for name, t in timing.items():
            lines.append(f"| {name} | {t['reports']} | {t['mean_ms']:.0f} | {t['median_ms']:.0f} | {t['p95_ms']} | "
                         f"{t['max_ms']} | {t['max_heap_mb']} |")
    return "\n".join(lines) + "\n"


def _longest_run(complete):
    best = (0, 0)
    start = None
    for i, ok in enumerate([*complete, False]):
        if ok and start is None:
            start = i
        if not ok and start is not None:
            best = (start, i) if i - start > best[1] - best[0] else best
            start = None
    return best


RESOURCE_SCALE_UP = 10000  # four-decimal resources become exact integers: float differences keep the exact ties of the product


def _scaled(value):
    return float(Decimal(str(value)) * RESOURCE_SCALE_UP)


def _same(actual, expected):
    if actual is None or expected is None:
        return actual is None and expected is None
    return abs(float(actual) - float(expected)) <= 1e-9


def oracle_check(corpus_dir, case, record):
    """Recompute selections of one report with the independent oracle on the mapped input; return mismatches."""
    run = json.loads((Path(corpus_dir) / case["path"]).read_text(encoding="utf-8"))["run"]
    snapshot, pairs = run["resources"], run["diagnostics"]["pairs"]
    start, step = snapshot["start_epoch_ms"], snapshot["step_ms"]
    if any(w["to_epoch_ms"] - w["from_epoch_ms"] > 241 * step for w in snapshot["windows"]):
        return None  # the oracle caps the series at 240 points (no check for N12 and FX03b)
    series = {s["id"]: s["values"] for s in snapshot["series"]}
    load = [None] * snapshot["point_count"]
    for line in run["load_jtl"].splitlines()[1:]:
        stamp, elapsed = line.split(",")[:2]
        load[(int(stamp) - start) // step] = int(elapsed)
    selections = {(s["pair_id"], s["window_id"]): s for s in record["selections"]}
    pair_evidence = {(s["pair_id"], s["window_id"]): s for s in record["pairs"]}
    family_count, mismatches = len(snapshot["windows"]), []
    for window in snapshot["windows"]:
        a, b = (window["from_epoch_ms"] - start) // step, (window["to_epoch_ms"] - start) // step
        hypotheses, meta = [], []
        for pair in pairs:
            resource, outcome = series[pair["resource_series_id"]][a:b], load[a:b]
            complete = [x is not None and y is not None for x, y in zip(resource, outcome)]
            lo, hi = _longest_run(complete)
            x = [_scaled(v) for v in resource[lo:hi]]
            y = [float(v) for v in outcome[lo:hi]]
            lag = pair["max_lag_ms"] // step
            levels_x = [_scaled(v) for v, ok in zip(resource, complete) if ok]
            levels_y = [float(v) for v, ok in zip(outcome, complete) if ok]
            material, best_rho = False, None
            if len(x) - 1 - 2 * lag >= 30:
                profile = oracle.lag_profile(list(np_diff(x)), list(np_diff(y)), lag)
                if all(v is not None for v in profile):
                    best = min(range(len(profile)), key=lambda k: (-abs(profile[k]), abs(k - lag), k - lag))
                    best_rho = (best - lag, profile[best])
                    material = (max(levels_x) - min(levels_x) >= float(Decimal(str(pair["min_resource_delta"])) * RESOURCE_SCALE_UP)
                                and max(levels_y) - min(levels_y) >= pair["min_load_delta"]
                                and abs(profile[best]) >= pair["min_abs_effect"])
            hypotheses.append(oracle.Hypothesis(pair["id"], window["id"], x, y, lag, material))
            if best_rho is not None:
                evidence = pair_evidence[(pair["id"], window["id"])]
                if evidence["best_lag_ms"] != best_rho[0] * step or not _same(evidence["best_lag_rho"], best_rho[1]):
                    mismatches.append(f"{pair['id']}/{window['id']}/best_lag: {evidence['best_lag_ms']!r} "
                                      f"{evidence['best_lag_rho']!r} != {best_rho!r}")
        for h, result in zip(hypotheses, oracle.select(hypotheses, record["seed_material"], "first_difference")):
            actual = selections[(h.pair_id, h.window_id)]
            alpha = 0.05 / family_count
            expected_selected = result.holm_adjusted is not None and result.holm_adjusted <= alpha and h.material_candidate
            status = "UNAVAILABLE" if result.status == "UNAVAILABLE" else "SELECTED" if expected_selected else "NOT_SELECTED"
            checks = (("status", actual["status"], status), ("selected", actual["selected"], expected_selected))
            for name, got, want in checks:
                if got != want:
                    mismatches.append(f"{h.pair_id}/{h.window_id}/{name}: {got!r} != {want!r}")
            for name, want in (("p_value_b10", result.p_b10), ("p_value_b20", result.p_b20),
                               ("max_p_value", result.max_p), ("holm_adjusted_p_value", result.holm_adjusted)):
                if not _same(actual[name], want):
                    mismatches.append(f"{h.pair_id}/{h.window_id}/{name}: {actual[name]!r} != {want!r}")
    return mismatches


def np_diff(values):
    return [b - a for a, b in zip(values, values[1:])]


def jvm_command(classpath, java="java", heap_mb=2048, cpus=2):
    return [java, f"-Xmx{heap_mb}m", f"-XX:ActiveProcessorCount={cpus}", f"-XX:ParallelGCThreads={cpus}",
            "-cp", classpath, SHARD_CLASS, "{corpus}", "{shard}", "{shards}", "{out}"]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    freeze_cmd = commands.add_parser("freeze")
    freeze_cmd.add_argument("--scenarios", required=True)
    freeze_cmd.add_argument("--seed0", type=int, required=True)
    freeze_cmd.add_argument("--count", type=int, required=True)
    freeze_cmd.add_argument("--out", required=True)
    freeze_cmd.add_argument("--workers", type=int, default=1)
    freeze_cmd.add_argument("--acceptance", action="store_true")
    freeze_cmd.add_argument("--fixtures", action="store_true", help="add the FX03b boundary fixtures")
    run_cmd = commands.add_parser("run")
    run_cmd.add_argument("--corpus", required=True)
    run_cmd.add_argument("--out", required=True)
    run_cmd.add_argument("--shards", type=int, required=True)
    run_cmd.add_argument("--classpath", required=True)
    run_cmd.add_argument("--java", default="java")
    run_cmd.add_argument("--heap-mb", type=int, default=2048)
    run_cmd.add_argument("--cpus-per-shard", type=int, default=2)
    run_cmd.add_argument("--deadline-s", type=float)
    score_cmd = commands.add_parser("score")
    score_cmd.add_argument("--corpus", required=True)
    score_cmd.add_argument("--out", required=True, help="directory with shard files")
    score_cmd.add_argument("--oracle-every", type=int, default=0, help="oracle check of every n-th report per scenario")
    score_cmd.add_argument("--report", required=True)
    score_cmd.add_argument("--actual", help="write the merged reports in manifest order and print their SHA-256")
    args = parser.parse_args(argv)
    if args.command == "freeze":
        fixtures = [("FX03b-cells-1920-1921", cells) for cells in (1920, 1921)] if args.fixtures else []
        manifest = freeze(args.scenarios.split(","), args.seed0, args.count, args.out, args.workers, args.acceptance, fixtures)
        print(json.dumps({"cases": len(manifest["cases"]), "entries_sha256": manifest["entries_sha256"]}))
    elif args.command == "run":
        files = run_sharded(args.corpus, args.shards, args.out,
                            jvm_command(args.classpath, args.java, args.heap_mb, args.cpus_per_shard), args.deadline_s)
        print(json.dumps([str(f) for f in files]))
    else:
        corpus = load_corpus(args.corpus)
        files = sorted(Path(args.out).glob("shard-*.jsonl"))
        actual = merge(files)
        if args.actual:
            print("actual_sha256", write_actual(corpus, actual, args.actual))
        mismatches, seen = {}, {}
        if args.oracle_every:
            for case in corpus["cases"]:
                seen[case["scenario_id"]] = seen.get(case["scenario_id"], -1) + 1
                if case["path"] and case["id"] in actual["records"] and "error" not in actual["records"][case["id"]] \
                        and seen[case["scenario_id"]] % args.oracle_every == 0:
                    checked = oracle_check(args.corpus, case, actual["records"][case["id"]])
                    if checked is not None:
                        mismatches[case["id"]] = checked
        report = score(corpus, actual, mismatches)
        report["oracle_checked"] = len(mismatches)
        report["oracle_mismatches"] = {k: v for k, v in mismatches.items() if v}
        timing = timing_summary(sorted(Path(args.out).glob("shard-*.timing")), corpus) \
            if list(Path(args.out).glob("shard-*.timing")) else None
        Path(args.report).write_text(json.dumps({"report": report, "timing": timing}, sort_keys=True, indent=1) + "\n",
                                     encoding="utf-8", newline="\n")
        sys.stdout.reconfigure(encoding="utf-8")
        print(report_markdown(report, timing))


if __name__ == "__main__":
    main()

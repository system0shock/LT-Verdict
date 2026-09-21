"""Bounded development pilot for correlation headline selection on disclosed v1 data."""

import argparse
import csv
from decimal import Decimal
import hashlib
import io
import json
import math
from pathlib import Path
import statistics
import time

import numpy as np


METHOD = "correlation-pilot-v1"
MANIFEST_SHA256 = "4de8f99f692fde334716d15b5ad7354f3784dd6f868fd2f01aa6a67f74ba004d"
ACTUAL_SHA256 = "b72cb19302d0c834dca155db6e793046ba9b945db4a860f4c02488a82bacdd12"
SEEDS = list(range(1000, 1020))
CONFIGS = [
    ("N02", 1, 0), ("N02", 16, 0), ("N02", 1, 10), ("N02", 16, 10),
    ("P01", 16, 0), ("P02", 16, 10), ("P03", 16, 10),
]
BLOCKS = (10, 20)
B = 999
ALPHA = 0.05
MAX_SECONDS = 600
BENCHMARK_B = 99
BENCHMARK_IDS = ("N02-p1-l0-s1000", "N02-p16-l10-s1000")


def sha256_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def case_ids():
    return [f"{family}-p{pairs}-l{lag}-s{seed}" for family, pairs, lag in CONFIGS for seed in SEEDS]


def design():
    return {
        "schema_version": METHOD,
        "status": "FROZEN_BEFORE_OUTPUTS",
        "study_role": "DEVELOPMENT_ONLY_DISCLOSED_V1_SEEDS",
        "corpus_manifest_sha256": MANIFEST_SHA256,
        "production_actual_sha256": ACTUAL_SHA256,
        "seeds": SEEDS,
        "configurations": [
            {"family": family, "pairs": pairs, "max_lag_cells": lag}
            for family, pairs, lag in CONFIGS
        ],
        "planned_reports": len(case_ids()),
        "evaluation_cells": 240,
        "blocks": list(BLOCKS),
        "bootstrap_replicates": B,
        "alpha": ALPHA,
        "time_budget_seconds": MAX_SECONDS,
        "null": "independent non-circular moving-block resampling of resource vector and outcome",
        "statistic": "full-series average ranks, fixed anchors, max absolute rho over declared lag profile",
        "family": "all declared pairs in one report; no candidate-only shrinkage",
        "decision": "one Holm procedure on q_j=max(p_j_b10,p_j_b20); production materiality remains required",
        "p_value": "(1 + count(T_star >= T_observed)) / (B + 1)",
        "partial_policy": "only v1 constant-target degeneracy with control dropped and partial_rho==raw_rho; genuine partial excluded",
        "rng": "NumPy PCG64, SHA-256 seed of method/case/block/side; joint resource and independent outcome schedules",
        "benchmark": {"replicates": BENCHMARK_B, "case_ids": list(BENCHMARK_IDS)},
        "case_ids": case_ids(),
        "numpy": np.__version__,
        "runner_sha256": sha256_file(__file__),
    }


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        stream.write("\n")


def load_manifest(corpus):
    corpus = Path(corpus).resolve()
    raw = (corpus / "manifest.json").read_bytes()
    if sha256_bytes(raw) != MANIFEST_SHA256:
        raise ValueError("unexpected v1 manifest hash")
    frozen = json.loads((corpus / "freeze.json").read_bytes())
    if frozen.get("manifest_sha256") != MANIFEST_SHA256 or frozen.get("planned_cases") != 28000:
        raise ValueError("corpus is not the completed v1 usefulness freeze")
    entries = {entry["id"]: entry for entry in json.loads(raw)["cases"]}
    wanted = case_ids()
    if any(case_id not in entries for case_id in wanted):
        raise ValueError("frozen pilot inventory is missing")
    return corpus, [entries[case_id] for case_id in wanted]


def load_actual(path, wanted):
    wanted, found, digest = set(wanted), {}, hashlib.sha256()
    with Path(path).open("rb") as stream:
        for raw in stream:
            digest.update(raw)
            if not raw.startswith(b'{"id":"'):
                raise ValueError("unexpected actual JSONL shape")
            end = raw.find(b'"', 7)
            case_id = raw[7:end].decode("ascii")
            if case_id in wanted:
                if case_id in found:
                    raise ValueError("duplicate selected actual")
                record = json.loads(raw)
                if "error" in record or not isinstance(record.get("output"), dict):
                    raise ValueError(f"selected production output failed: {case_id}")
                found[case_id] = record["output"]
    if digest.hexdigest() != ACTUAL_SHA256:
        raise ValueError("unexpected production actual hash")
    if set(found) != wanted:
        raise ValueError("selected production outputs are missing")
    return found


def rank_rows(values):
    values = np.asarray(values, dtype=np.float64)
    if values.ndim == 1:
        values = values[None, :]
    rows, columns = values.shape
    order = np.argsort(values, axis=1, kind="stable")
    ordered = np.take_along_axis(values, order, axis=1)
    positions = np.broadcast_to(np.arange(columns), (rows, columns))
    starts_flag = np.ones((rows, columns), dtype=bool)
    starts_flag[:, 1:] = ordered[:, 1:] != ordered[:, :-1]
    ends_flag = np.ones((rows, columns), dtype=bool)
    ends_flag[:, :-1] = ordered[:, :-1] != ordered[:, 1:]
    starts = np.maximum.accumulate(np.where(starts_flag, positions, 0), axis=1)
    ends = np.minimum.accumulate(np.where(ends_flag, positions, columns - 1)[:, ::-1], axis=1)[:, ::-1]
    ranked_ordered = (starts + ends) / 2.0 + 1.0
    ranked = np.empty_like(ranked_ordered)
    np.put_along_axis(ranked, order, ranked_ordered, axis=1)
    return ranked


def profile(x, y, max_lag):
    rx, ry = rank_rows(x)[0], rank_rows(y)[0]
    anchors = slice(max_lag, len(rx) - max_lag)
    result = []
    for lag in range(-max_lag, max_lag + 1):
        left, right = rx[anchors], ry[max_lag + lag:len(ry) - max_lag + lag]
        left, right = left - left.mean(), right - right.mean()
        result.append(float(np.dot(left, right) / math.sqrt(np.dot(left, left) * np.dot(right, right))))
    return result


def best_index(values, max_lag):
    return min(range(len(values)), key=lambda index: (-abs(values[index]), abs(index - max_lag), index - max_lag))


def block_indices(length, block, replicas, rng):
    starts = rng.integers(0, length - block + 1, size=(replicas, math.ceil(length / block)))
    return (starts[:, :, None] + np.arange(block)).reshape(replicas, -1)[:, :length]


def bootstrap_maxima(x, y, max_lag, block, replicas, case_id):
    length, pairs = x.shape
    def generator(side):
        seed = int.from_bytes(hashlib.sha256(f"{METHOD}/{case_id}/{block}/{side}".encode()).digest()[:16], "big")
        return np.random.default_rng(seed)
    ix = block_indices(length, block, replicas, generator("resources"))
    iy = block_indices(length, block, replicas, generator("outcome"))
    xr = rank_rows(x[ix].transpose(0, 2, 1).reshape(replicas * pairs, length)).reshape(replicas, pairs, length)
    yr = rank_rows(y[iy])
    anchor_count = length - 2 * max_lag
    left = xr[:, :, max_lag:length - max_lag]
    right = np.lib.stride_tricks.sliding_window_view(yr, anchor_count, axis=1)
    left = left - left.mean(axis=2, keepdims=True)
    right = right - right.mean(axis=2, keepdims=True)
    numerator = np.einsum("bma,bka->bmk", left, right, optimize=True)
    denominator = np.sqrt(np.einsum("bma,bma->bm", left, left)[:, :, None] *
                          np.einsum("bka,bka->bk", right, right)[:, None, :])
    correlations = numerator / denominator
    if not np.isfinite(correlations).all():
        raise ValueError("non-evaluable bootstrap replicate")
    return np.max(np.abs(correlations), axis=2)


def holm(p_values):
    order = sorted(range(len(p_values)), key=lambda index: (p_values[index], index))
    adjusted, running = [1.0] * len(p_values), 0.0
    for rank, index in enumerate(order):
        running = max(running, (len(order) - rank) * p_values[index])
        adjusted[index] = min(1.0, running)
    return adjusted, [value <= ALPHA for value in adjusted]


def extract_input(corpus, entry):
    for kind in ("inputs", "expected"):
        path = corpus / entry[kind]["path"]
        raw = path.read_bytes()
        if sha256_bytes(raw) != entry[kind]["sha256"]:
            raise ValueError(f"frozen {kind} hash mismatch: {entry['id']}")
        if kind == "inputs":
            operation = json.loads(raw)
        else:
            truth = json.loads(raw)
    run, snapshot = operation["run"], operation["run"]["resources"]
    window = next(item for item in snapshot["windows"] if item["id"] == "evaluation")
    step = snapshot["step_ms"]
    start = (window["from_epoch_ms"] - snapshot["start_epoch_ms"]) // step
    count = (window["to_epoch_ms"] - window["from_epoch_ms"]) // step
    series = {item["id"]: item for item in snapshot["series"]}
    pairs = run["diagnostics"]["pairs"]
    if count != 240 or any(pair["load_metric"] != "response_time_p95_ms" for pair in pairs):
        raise ValueError("pilot only supports the frozen v1 evaluation shape")
    target = series["target"]["values"][start:start + count]
    if len(set(target)) != 1:
        raise ValueError("genuine partial input is outside pilot scope")
    x = np.column_stack([
        np.asarray(series[pair["resource_series_id"]]["values"][start:start + count], dtype=np.float64)
        for pair in pairs
    ])
    cells = [[] for _ in range(count)]
    for row in csv.DictReader(io.StringIO(run["load_jtl"])):
        index = (int(row["timeStamp"]) - window["from_epoch_ms"]) // step
        if 0 <= index < count:
            cells[index].append(int(row["elapsed"]))
    if any(len(cell) != 20 or len(set(cell)) != 1 for cell in cells):
        raise ValueError("v1 constant-within-cell p95 shortcut is not applicable")
    return pairs, x, np.asarray([cell[0] for cell in cells], dtype=np.float64), truth


def numeric_match(actual, expected):
    if actual is None:
        return expected is None
    observed = Decimal(str(actual))
    wanted = Decimal(str(expected))
    return abs(observed - wanted) <= Decimal("1e-9") * (1 + abs(observed))


def observe(pairs, x, y, output):
    actual_pairs = output["correlation_pairs"]["evaluation"]
    mismatches, observed, checks = [], [], 0
    for column, pair in enumerate(pairs):
        pair_id, max_lag = pair["id"], pair["max_lag_ms"] // 1000
        actual = actual_pairs[pair_id]
        values = profile(x[:, column], y, max_lag)
        raw = profile(x[:, column], y, 0)[0]
        chosen = best_index(values, max_lag)
        best_lag, best_rho = chosen - max_lag, values[chosen]
        resource_range, load_range = float(np.ptp(x[:, column])), float(np.ptp(y))
        status = ("BELOW_EFFECT" if resource_range < pair["min_resource_delta"] or
                  load_range < pair["min_load_delta"] or abs(best_rho) < pair["min_abs_effect"] else
                  "OPPOSITE_SIGN" if pair["expected_sign"] == "positive" and best_rho <= 0 or
                  pair["expected_sign"] == "negative" and best_rho >= 0 else "CANDIDATE")
        structural = {
            "controls_requested": ["target"], "controls_used": [], "controls_dropped": ["target"],
            "paired_cells": 240, "lag_used_cells": 240 - 2 * max_lag,
            "best_lag_ms": best_lag * 1000, "status": status,
        }
        for key, expected in structural.items():
            checks += 1
            if actual.get(key) != expected:
                mismatches.append(f"{pair_id}/{key}: {actual.get(key)!r} != {expected!r}")
        for key, expected in (("raw_rho", raw), ("partial_rho", raw), ("best_lag_rho", best_rho)):
            checks += 1
            if not numeric_match(actual.get(key), expected):
                mismatches.append(f"{pair_id}/{key}: {actual.get(key)!r} != {expected!r}")
        lag_profile = actual.get("lag_profile", [])
        if len(lag_profile) != len(values):
            mismatches.append(f"{pair_id}/lag_profile length")
        else:
            for index, expected in enumerate(values):
                checks += 2
                if lag_profile[index].get("lag_ms") != (index - max_lag) * 1000 or not numeric_match(lag_profile[index].get("rho"), expected):
                    mismatches.append(f"{pair_id}/lag_profile/{index}")
        if actual.get("raw_rho") != actual.get("partial_rho"):
            mismatches.append(f"{pair_id}/constant-target raw-partial inequality")
        observed.append({"pair_id": pair_id, "t": abs(best_rho), "best_lag_cells": best_lag,
                         "best_rho": best_rho, "production_candidate": status == "CANDIDATE"})
    findings = {item["pair_id"] for item in output["findings"] if item.get("type") == "correlation_candidate"}
    expected_findings = {item["pair_id"] for item in observed if item["production_candidate"]}
    checks += 1
    if findings != expected_findings:
        mismatches.append("production findings do not match recomputed materiality/status")
    return observed, findings, {"checks": checks, "mismatches": mismatches}


def selection_metrics(selected, truth, observed):
    injected = truth.get("injected_pair")
    extras = len(selected - ({injected} if injected else set()))
    detected = None
    if injected:
        item = next(value for value in observed if value["pair_id"] == injected)
        sign_ok = item["best_rho"] < 0 if truth["sign"] == "negative" else item["best_rho"] > 0
        lag = item["best_lag_cells"]
        direction_ok = lag == 0 if truth["lag_cells"] == 0 else lag * truth["lag_cells"] > 0
        detected = injected in selected and sign_ok and direction_ok and abs(lag - truth["lag_cells"]) <= 1
    return {"headlines": len(selected), "unrelated_headlines": extras,
            "unexpected_report": extras > 0, "detected": detected}


def analyze(entry, corpus, output, replicas):
    pairs, x, y, truth = extract_input(corpus, entry)
    observed, baseline, parity = observe(pairs, x, y, output)
    if parity["mismatches"]:
        raise ValueError("observed parity failed: " + "; ".join(parity["mismatches"][:3]))
    lag = entry["configuration"]["max_lag_cells"]
    block_results = {}
    for block in BLOCKS:
        maxima = bootstrap_maxima(x, y, lag, block, replicas, entry["id"])
        counts = np.sum(maxima >= np.asarray([item["t"] for item in observed])[None, :], axis=0)
        p_values = ((counts + 1) / (replicas + 1)).tolist()
        adjusted, rejected = holm(p_values)
        block_results[block] = {"counts": counts.tolist(), "p": p_values, "adjusted": adjusted,
                                "selected": {item["pair_id"] for item, keep in zip(observed, rejected) if keep and item["production_candidate"]}}
    q = [max(block_results[block]["p"][index] for block in BLOCKS) for index in range(len(observed))]
    adjusted, rejected = holm(q)
    robust = {item["pair_id"] for item, keep in zip(observed, rejected) if keep and item["production_candidate"]}
    selections = {"baseline": baseline, "b10": block_results[10]["selected"],
                  "b20": block_results[20]["selected"], "robust": robust}
    return {
        "id": entry["id"], "family": entry["family"],
        "configuration": f"{entry['family']}-p{entry['configuration']['pairs']}-l{lag}",
        "parity": parity,
        "pairs": [{**item,
                   "b10_exceedances": block_results[10]["counts"][index], "b10_p": block_results[10]["p"][index],
                   "b20_exceedances": block_results[20]["counts"][index], "b20_p": block_results[20]["p"][index],
                   "q_max_p": q[index], "holm_adjusted_q": adjusted[index], "selected": rejected[index] and item["production_candidate"]}
                  for index, item in enumerate(observed)],
        "selection": {name: selection_metrics(value, truth, observed) for name, value in selections.items()},
        "b10_b20_disagree": selections["b10"] != selections["b20"],
    }


def aggregate(records):
    def metrics(rows, mode):
        values = [row["selection"][mode] for row in rows]
        detected = [item["detected"] for item in values if item["detected"] is not None]
        return {"reports": len(rows), "headlines": sum(item["headlines"] for item in values),
                "unexpected_reports": sum(item["unexpected_report"] for item in values),
                "unrelated_headlines": sum(item["unrelated_headlines"] for item in values),
                "detected": sum(detected) if detected else None, "positive_reports": len(detected)}
    groups = {}
    for key in sorted({row["configuration"] for row in records}):
        rows = [row for row in records if row["configuration"] == key]
        groups[key] = {mode: metrics(rows, mode) for mode in ("baseline", "b10", "b20", "robust")}
    return groups


def report_markdown(summary):
    lines = [
        "# Пилот корреляционного отбора на раскрытых данных v1", "",
        "**Статус:** development-only; это не statistical acceptance и не основание менять production.", "",
        "## Замороженный дизайн", "",
        f"- Данные: {summary['planned']} отчетов, seeds `1000..1019`, семь заранее выбранных correlation configurations.",
        f"- Null: independent non-circular moving-block bootstrap, `B={B}`, `b=10/20`, `alpha={ALPHA}`.",
        "- В каждой реплике resource-вектор пересэмплируется совместно, outcome независимо; ranks строятся заново по полному ряду.",
        "- Статистика пары: максимум `|rho|` по полному declared lag search с fixed anchors. Семейство: все 1/16 объявленных pairs.",
        "- Итог: `q_j=max(p_j,b10,p_j,b20)`, затем один Holm по `q`; production sign/materiality остаются обязательными.",
        "- Все v1 cases запрашивают constant target. Production его отбрасывает, поэтому `partial_rho == raw_rho`; genuine partial не проверялся.", "",
        "## Исполнение и parity", "",
        f"- Execution status: `{summary['status']}`; completed {summary['completed']}/{summary['planned']}; errors {len(summary['errors'])}.",
        f"- Observed parity: {summary['parity']['checks']} checks, mismatches {summary['parity']['mismatches']}.",
        f"- Runtime: {summary['runtime_seconds']:.3f} s при hard cap {MAX_SECONDS} s.", "",
        "## До/после", "",
        "| Configuration | Reports | v1 extras reports / extras / detected | b10 | b20 | max-p + Holm |", "| --- | ---: | --- | --- | --- | --- |",
    ]
    def cell(value):
        detected = "-" if value["detected"] is None else f"{value['detected']}/{value['positive_reports']}"
        return f"{value['unexpected_reports']} / {value['unrelated_headlines']} / {detected}"
    for key, modes in summary["configurations"].items():
        lines.append(f"| {key} | {modes['baseline']['reports']} | {cell(modes['baseline'])} | {cell(modes['b10'])} | {cell(modes['b20'])} | {cell(modes['robust'])} |")
    before, after = summary["overall"]["baseline"], summary["overall"]["robust"]
    lines += ["", "Итого:", "",
              f"- Report-level extras: {before['unexpected_reports']} -> {after['unexpected_reports']}; unrelated headlines: {before['unrelated_headlines']} -> {after['unrelated_headlines']}.",
              f"- Injected detection: {before['detected']}/{before['positive_reports']} -> {after['detected']}/{after['positive_reports']}.",
              f"- Решения b10/b20 различались в {summary['sensitivity_disagreement_reports']} отчетах.", "",
              "## Ограничения", "",
              "- Это повторное использование раскрытых v1 seeds для development; оценка подвержена selection bias и не независима.",
              "- Двадцать reports на configuration не дают мощности для acceptance gate и широки для оценки редких событий.",
              "- `B=999` дает шаг p-value 0.001. При `m=16` первый Holm cutoff 0.003125 допускает только 0, 1 или 2 превышения; пограничные решения грубы.",
              "- Две фиксированные длины блока показывают sensitivity, но не доказывают корректность moving-block null или стационарность.",
              "- В v1 каждый cell содержит 20 одинаковых latency; runner проверяет это и не претендует на общий HdrHistogram oracle.",
              "- Genuine partial correlations, comparisons, mixed windows/outcomes, missingness и новый независимый seed range исключены.", "",
              "## Артефакты", "",
              f"- Design: `{summary['artifacts']['design']}` (`{summary['artifacts']['design_sha256']}`).",
              f"- Cases: `{summary['artifacts']['cases']}` (`{summary['artifacts']['cases_sha256']}`).",
              f"- Summary: `{summary['artifacts']['summary']}`.",
              f"- Benchmark: `{summary['artifacts']['benchmark']}` (`{summary['artifacts']['benchmark_sha256']}`).", "",
              "Documentation impact: добавлен только отчет development-пилота; production API, contracts, user behavior и CHANGELOG не менялись.", ""]
    return "\n".join(lines)


def freeze(corpus, prefix):
    load_manifest(corpus)
    path = Path(str(prefix) + "-design.json")
    write_json(path, design())
    print(json.dumps({"status": "FROZEN", "path": str(path), "sha256": sha256_file(path)}))


def benchmark(corpus, design_path, output_path):
    if json.loads(Path(design_path).read_bytes()) != design():
        raise ValueError("benchmark design mismatch")
    corpus, entries = load_manifest(corpus)
    selected = [entry for entry in entries if entry["id"] in BENCHMARK_IDS]
    actual = load_actual(corpus / "actual.jsonl", BENCHMARK_IDS)
    rows = []
    for entry in selected:
        pairs, x, y, _ = extract_input(corpus, entry)
        observed, _, parity = observe(pairs, x, y, actual[entry["id"]])
        if parity["mismatches"]:
            raise ValueError("benchmark parity failed")
        started = time.monotonic()
        for block in BLOCKS:
            bootstrap_maxima(x, y, entry["configuration"]["max_lag_cells"], block, BENCHMARK_B, entry["id"])
        elapsed = time.monotonic() - started
        lag, pair_count = entry["configuration"]["max_lag_cells"], len(observed)
        units = len(BLOCKS) * BENCHMARK_B * pair_count * (240 + (2 * lag + 1) * (240 - 2 * lag))
        rows.append({"id": entry["id"], "seconds": elapsed, "work_units": units, "parity_checks": parity["checks"]})
    rate = max(row["seconds"] / row["work_units"] for row in rows)
    full_units = sum(len(BLOCKS) * B * pairs * (240 + (2 * lag + 1) * (240 - 2 * lag)) * len(SEEDS)
                     for _, pairs, lag in CONFIGS)
    result = {"status": "TIMING_ONLY", "replicates": BENCHMARK_B, "cases": rows,
              "estimated_full_seconds": rate * full_units, "estimate_policy": "maximum observed seconds/work-unit",
              "design_sha256": sha256_file(design_path)}
    write_json(output_path, result)
    print(json.dumps(result, indent=2))


def run(corpus, prefix, report_path, benchmark_path):
    started = time.monotonic()
    design_path = Path(str(prefix) + "-design.json")
    if json.loads(design_path.read_bytes()) != design():
        raise ValueError("run design mismatch")
    benchmark_data = json.loads(Path(benchmark_path).read_bytes())
    if benchmark_data.get("status") != "TIMING_ONLY" or benchmark_data.get("design_sha256") != sha256_file(design_path):
        raise ValueError("benchmark is not bound to design")
    corpus, entries = load_manifest(corpus)
    actual = load_actual(corpus / "actual.jsonl", case_ids())
    cases_path, summary_path = Path(str(prefix) + "-cases.jsonl"), Path(str(prefix) + "-summary.json")
    cases_path.parent.mkdir(parents=True, exist_ok=True)
    records, errors = [], []
    with cases_path.open("x", encoding="utf-8", newline="\n") as stream:
        for entry in entries:
            if time.monotonic() - started >= MAX_SECONDS:
                break
            try:
                record = analyze(entry, corpus, actual[entry["id"]], B)
                records.append(record)
                serializable = json.loads(json.dumps(record, default=lambda value: sorted(value) if isinstance(value, set) else value))
                stream.write(json.dumps(serializable, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n")
                stream.flush()
            except Exception as error:
                errors.append({"id": entry["id"], "error": f"{type(error).__name__}: {error}"})
    groups = aggregate(records)
    overall = {mode: {key: sum(group[mode][key] or 0 for group in groups.values())
                      for key in ("reports", "headlines", "unexpected_reports", "unrelated_headlines", "detected", "positive_reports")}
               for mode in ("baseline", "b10", "b20", "robust")}
    parity_checks = sum(row["parity"]["checks"] for row in records)
    parity_mismatches = sum(len(row["parity"]["mismatches"]) for row in records)
    elapsed = time.monotonic() - started
    summary = {
        "schema_version": METHOD, "status": "COMPLETE" if len(records) == len(entries) and not errors else "INCOMPLETE",
        "planned": len(entries), "completed": len(records), "missing": [entry["id"] for entry in entries if entry["id"] not in {row["id"] for row in records}],
        "errors": errors, "runtime_seconds": elapsed, "time_budget_seconds": MAX_SECONDS,
        "parity": {"checks": parity_checks, "mismatches": parity_mismatches},
        "configurations": groups, "overall": overall,
        "sensitivity_disagreement_reports": sum(row["b10_b20_disagree"] for row in records),
        "artifacts": {"design": str(design_path), "design_sha256": sha256_file(design_path),
                      "cases": str(cases_path), "cases_sha256": sha256_file(cases_path),
                      "summary": str(summary_path), "benchmark": str(benchmark_path),
                      "benchmark_sha256": sha256_file(benchmark_path)},
        "production_actual_sha256": ACTUAL_SHA256,
    }
    write_json(summary_path, summary)
    report_path = Path(report_path)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    with report_path.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(report_markdown(summary))
    print(json.dumps({"status": summary["status"], "completed": summary["completed"],
                      "errors": len(errors), "parity_mismatches": parity_mismatches,
                      "runtime_seconds": elapsed, "overall": overall}, indent=2))
    return 0 if summary["status"] == "COMPLETE" and parity_mismatches == 0 else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("freeze", "benchmark", "run"):
        command = commands.add_parser(name)
        command.add_argument("--corpus", type=Path, required=True)
        if name in ("freeze", "run"):
            command.add_argument("--output-prefix", type=Path, required=True)
        if name == "benchmark":
            command.add_argument("--design", type=Path, required=True)
            command.add_argument("--output", type=Path, required=True)
        if name == "run":
            command.add_argument("--benchmark", type=Path, required=True)
            command.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "freeze":
        freeze(args.corpus, args.output_prefix)
        return 0
    if args.command == "benchmark":
        benchmark(args.corpus, args.design, args.output)
        return 0
    return run(args.corpus, args.output_prefix, args.report, args.benchmark)


if __name__ == "__main__":
    raise SystemExit(main())

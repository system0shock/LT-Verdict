"""Full disclosed v1 correlation repeat using the frozen bounded pilot implementation."""

import argparse
import contextlib
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import subprocess
import sys

import correlation_pilot as pilot


FULL_METHOD = "correlation-full-v1"
PILOT_CASES_SHA256 = "28c5c3cb1b66e45d11bbbac3072dad83a524e940baa351bd75603c526f6e5253"
PILOT_SUMMARY_SHA256 = "a0d61df24cc900f202fa55fd284481e9ad2013c162615b2c415dfd86d077f9df"
PILOT_REPORTS = 140
FULL_CONFIGS = [
    (family, pairs, lag)
    for family in ("N01", "N02", "N03")
    for pairs in (1, 16)
    for lag in (0, 10)
] + [("P01", 16, 0), ("P02", 16, 10), ("P03", 16, 10)]
ORIGINAL_DESIGN = pilot.design
ORIGINAL_REPORT = pilot.report_markdown
BASE_RNG_METHOD = pilot.METHOD
pilot.SEEDS = list(range(1000, 2000))
pilot.CONFIGS = FULL_CONFIGS
pilot.MAX_SECONDS = 10800
RUN_CONTEXT = {}


def full_design():
    result = ORIGINAL_DESIGN()
    result["schema_version"] = FULL_METHOD
    result["study_role"] = "FULL_DISCLOSED_CORRELATION_V1_DEVELOPMENT_REPEAT"
    result["sample_size_per_configuration"] = 1000
    result["base_rng_method"] = BASE_RNG_METHOD
    result["base_runner_sha256"] = result.pop("runner_sha256")
    result["wrapper_sha256"] = pilot.sha256_file(__file__)
    result["scope"] = "15 correlation configurations x 1000 disclosed seeds = 15000 reports"
    result["excluded_v1_reports"] = "episodes and two-run comparisons: no new selector applies"
    result["time_budget_semantics"] = "soft; checked between reports, an in-flight report may finish after 10800 seconds"
    result["planning_estimate_basis"] = "completed bounded pilot: 50.40367969998624 seconds / 140 reports"
    return result


def full_report(summary):
    text = ORIGINAL_REPORT(summary)
    text = text.replace("# Пилот корреляционного отбора", "# Полный disclosed прогон корреляционного отбора")
    text = text.replace("seeds `1000..1019`, семь заранее выбранных correlation configurations",
                        "seeds `1000..1999`, все 15 correlation configurations v1")
    text = text.replace(f"при hard cap {pilot.MAX_SECONDS} s.",
                        f"; budget {pilot.MAX_SECONDS} s проверяется между отчетами, поэтому начатый report может завершиться позже.")
    text = text.replace("Двадцать reports на configuration не дают мощности для acceptance gate и широки для оценки редких событий.",
                        "1000 reports на configuration дают полный disclosed v1 repeat, но не независимую acceptance после выбора метода по v1.")
    replay = "correlation-full-v1-replay-01"
    context = RUN_CONTEXT
    text += f"""

## Scope и воспроизведение

- Включены только 15000 correlation reports. 13000 episode/comparison reports сохраняют прежние v1 результаты: новый selector к ним не применяется.
- Background PID: `{context.get('pid')}`; command: `{context.get('command')}`.
- Prefix consistency: `{context.get('prefix_check')}` (`{context.get('prefix_check_sha256')}`).

Runner создает outputs эксклюзивно; replay использует новые пути:

```powershell
python -m unittest tools/test_correlation_full.py
python tools/correlation_full.py freeze --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/{replay}
python tools/correlation_full.py prefix-check --corpus build/stats-validation/v1-usefulness --design build/stats-validation/{replay}-design.json --old-design build/stats-validation/correlation-pilot-v1-design.json --old-cases build/stats-validation/correlation-pilot-v1-cases.jsonl --output build/stats-validation/{replay}-prefix-check.json
python tools/correlation_full.py benchmark --corpus build/stats-validation/v1-usefulness --design build/stats-validation/{replay}-design.json --pilot-summary build/stats-validation/correlation-pilot-v1-summary.json --output build/stats-validation/{replay}-benchmark.json
python tools/correlation_full.py run --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/{replay} --benchmark build/stats-validation/{replay}-benchmark.json --prefix-check build/stats-validation/{replay}-prefix-check.json --report docs/{replay}.md --stdout-log build/stats-validation/{replay}.stdout.log --stderr-log build/stats-validation/{replay}.stderr.log --process-metadata build/stats-validation/{replay}-process.json
```
"""
    return text


pilot.design = full_design
pilot.report_markdown = full_report


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def prefix_check(corpus, design_path, old_design_path, old_cases_path, output_path):
    current_design = json.loads(Path(design_path).read_bytes())
    old_design = json.loads(Path(old_design_path).read_bytes())
    if current_design != full_design() or old_design.get("runner_sha256") != current_design["base_runner_sha256"]:
        raise ValueError("base/full design source mismatch")
    if pilot.sha256_file(old_cases_path) != PILOT_CASES_SHA256:
        raise ValueError("bounded pilot cases hash mismatch")
    wanted = set(pilot.BENCHMARK_IDS)
    old = {}
    with Path(old_cases_path).open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record["id"] in wanted:
                old[record["id"]] = record
    corpus, entries = pilot.load_manifest(corpus)
    entries = [entry for entry in entries if entry["id"] in wanted]
    actual = pilot.load_actual(corpus / "actual.jsonl", wanted)
    mismatches, parity_checks = [], 0
    for entry in entries:
        result = pilot.analyze(entry, corpus, actual[entry["id"]], pilot.B)
        parity_checks += result["parity"]["checks"]
        if canonical(result) != canonical(old.get(entry["id"])):
            mismatches.append(entry["id"])
    report = {"status": "MATCH" if not mismatches and len(entries) == len(wanted) else "FAIL",
              "planned": len(wanted), "completed": len(entries), "mismatches": mismatches,
              "parity_checks": parity_checks, "design_sha256": pilot.sha256_file(design_path),
              "bounded_cases_sha256": PILOT_CASES_SHA256}
    pilot.write_json(output_path, report)
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "MATCH" else 1


def benchmark(corpus, design_path, pilot_summary_path, output_path):
    if pilot.sha256_file(pilot_summary_path) != PILOT_SUMMARY_SHA256:
        raise ValueError("bounded pilot summary hash mismatch")
    bounded = json.loads(Path(pilot_summary_path).read_bytes())
    with contextlib.redirect_stdout(io.StringIO()):
        pilot.benchmark(corpus, design_path, output_path)
    result = json.loads(Path(output_path).read_bytes())
    result["work_unit_estimate_seconds"] = result.pop("estimated_full_seconds")
    result["estimated_full_seconds"] = bounded["runtime_seconds"] * len(pilot.case_ids()) / PILOT_REPORTS
    result["estimate_policy"] = "linear scale from completed 140-report run; planning estimate only"
    Path(output_path).write_text(canonical(result) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(result, indent=2))


def progress_text(args, command):
    prefix = str(args.output_prefix)
    return f"""# Полный disclosed прогон корреляционного отбора v1

**Статус:** RUNNING, development-only, не independent acceptance.

- PID: `{os.getpid()}`.
- Started UTC: `{datetime.now(timezone.utc).isoformat()}`.
- Scope: 15000 reports = 15 correlation configurations × seeds `1000..1999`.
- Method: `B=999`, `b=10/20`, `q=max(p_b10,p_b20)`, один Holm, `alpha=0.05`.
- Budget: 10800 s, проверяется между reports; in-flight report может завершиться позже.
- Command: `{command}`.
- Design: `{prefix}-design.json`.
- Prefix check: `{args.prefix_check}`.
- Cases/progress: `{prefix}-cases.jsonl`; число завершенных reports равно числу строк.
- Final summary: `{prefix}-summary.json`.
- Stdout/stderr: `{args.stdout_log}`, `{args.stderr_log}`.
- Process metadata: `{args.process_metadata}`.

Episodes и two-run comparisons исключены: для них новый correlation selector не определен. Старые v1 artifacts не изменяются.
"""


def run_full(args):
    check = json.loads(args.prefix_check.read_bytes())
    design_path = Path(str(args.output_prefix) + "-design.json")
    if check.get("status") != "MATCH" or check.get("design_sha256") != pilot.sha256_file(design_path):
        raise ValueError("prefix consistency does not permit full run")
    command = subprocess.list2cmdline([sys.executable, *sys.argv])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(progress_text(args, command))
    RUN_CONTEXT.update(pid=os.getpid(), command=command, prefix_check=str(args.prefix_check),
                       prefix_check_sha256=pilot.sha256_file(args.prefix_check))
    generated = Path(str(args.output_prefix) + "-generated-report.md")
    try:
        code = pilot.run(args.corpus, args.output_prefix, generated, args.benchmark)
        generated.replace(args.report)
        return code
    except BaseException as error:
        with args.report.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(f"\n## Background failure\n\n`{type(error).__name__}: {error}`\n")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    freeze = commands.add_parser("freeze")
    freeze.add_argument("--corpus", type=Path, required=True)
    freeze.add_argument("--output-prefix", type=Path, required=True)
    check = commands.add_parser("prefix-check")
    for name in ("corpus", "design", "old-design", "old-cases", "output"):
        check.add_argument("--" + name, type=Path, required=True)
    timing = commands.add_parser("benchmark")
    for name in ("corpus", "design", "pilot-summary", "output"):
        timing.add_argument("--" + name, type=Path, required=True)
    run = commands.add_parser("run")
    for name in ("corpus", "output-prefix", "benchmark", "prefix-check", "report",
                 "stdout-log", "stderr-log", "process-metadata"):
        run.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if args.command == "freeze":
        pilot.freeze(args.corpus, args.output_prefix)
        return 0
    if args.command == "prefix-check":
        return prefix_check(args.corpus, args.design, args.old_design, args.old_cases, args.output)
    if args.command == "benchmark":
        benchmark(args.corpus, args.design, args.pilot_summary, args.output)
        return 0
    return run_full(args)


if __name__ == "__main__":
    raise SystemExit(main())

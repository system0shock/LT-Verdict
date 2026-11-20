"""Builds a data directory of the synthetic 8-hour demo (plan 2026-11-19-demo-dataset-8h).

Order: generate the inputs, run `ltv analyze` for every analysis of the matrix, start `ltv ui` without a browser, save
the releases and the baseline through the local HTTP API (the CLI has no commands for them), stop `ltv ui`.
Standard library only. Run as `python -m tools.demo_dataset_build --ltv PATH --out DIR --data-dir DIR` from the repository root.
"""

import argparse
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path

if __package__ in (None, ""):  # script mode: make the repository root importable
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from tools import demo_dataset as dd

IDS_LINE = re.compile(r"analysis_id=([0-9a-f]{64}) run_id=(\S+)")
ORIGIN_LINE = re.compile(r"^(http://127\.0\.0\.1:\d+)\s*$")
RESOURCE_FLAGS = (("--resources", "resource-snapshot.json"), ("--trend", "trend-plan.json"), ("--pod-view", "pod-view.json"), ("--correlation", "correlation-plan.json"))
UI_START_TIMEOUT_S = 60
ANALYZE_TIMEOUT_S = 1800
# Replace with True (the --combined-stages flag) when a separate ADR lifts the ban on --stages together with --resources:
# the analyses A and B then carry --stages and the stages-only analysis S disappears. Nothing else changes.
STAGES_WITH_RESOURCES = False


class BuildError(Exception):
    pass


def analysis_matrix(scenario: dict, out: Path, data_dir: Path, combined_stages: bool = STAGES_WITH_RESOURCES) -> list:
    """Every `ltv analyze` call, oldest release first: arms A and B with resources, then (only where the scenario
    expects it) the stages-only analysis S. `combined_stages` is the one switch that folds S into A and B."""
    matrix = []
    for release in sorted(scenario["releases"], key=lambda item: item["start"]):
        base = Path(out) / release["id"]
        for slot in scenario["arms"]:
            args = [str(base / "load.jtl"), "--policy", str(Path(out) / "policy.json")]
            if combined_stages:
                args += ["--stages", str(base / "load-stages.json")]
            for flag, name in RESOURCE_FLAGS:
                args += [flag, str(base / f"arm-{slot}" / name)]
            args += ["--source-context", str(base / "opensearch-errors.json"), "--data-dir", str(data_dir)]
            matrix.append({"release": release["id"], "slot": slot, "args": args, "expected_exit": release["exit_codes"][slot]})
        if "S" in release["exit_codes"] and not combined_stages:
            args = [str(base / "load.jtl"), "--policy", str(Path(out) / "policy-stages.json"), "--stages", str(base / "load-stages.json"), "--data-dir", str(data_dir)]
            matrix.append({"release": release["id"], "slot": "S", "args": args, "expected_exit": release["exit_codes"]["S"]})
    return matrix


def run_analyze(ltv: Path, args: list, expected_exit: int, env: dict) -> tuple:
    """Runs one analysis; any exit code but the expected one is a failure. Returns (run_id, analysis_id)."""
    result = subprocess.run([str(ltv), "analyze", *args], capture_output=True, text=True, encoding="utf-8", env=env or None, timeout=ANALYZE_TIMEOUT_S)
    if result.returncode != expected_exit:
        raise BuildError(f"ltv analyze exited {result.returncode}, expected {expected_exit}: {' '.join(args[:1])}\n{result.stderr[-2000:]}")
    found = IDS_LINE.search(result.stderr)
    if not found:
        raise BuildError(f"no analysis_id/run_id line in stderr of ltv analyze:\n{result.stderr[-2000:]}")
    return found.group(2), found.group(1)


def ensure_empty(data_dir: Path, force_new: bool) -> None:
    """Analyses are idempotent but a second release save is refused, so a non-empty data directory is not reused."""
    if not force_new and Path(data_dir).exists() and any(Path(data_dir).iterdir()):
        raise BuildError(f"{data_dir} is not empty; use an empty directory or --force-new")


def check_free_space(out: Path, data_dir: Path) -> None:
    """Each run is copied into the data directory, so the data directory needs about the size of the inputs again."""
    size = sum(path.stat().st_size for path in Path(out).rglob("*") if path.is_file())
    anchor = Path(data_dir)
    while not anchor.exists():
        anchor = anchor.parent
    free = shutil.disk_usage(anchor).free
    if free < size * 2:
        raise BuildError(f"{free // 1048576} MiB free at {anchor}, about {size * 2 // 1048576} MiB are needed")


def release_body(scenario: dict, release: dict, ids: dict) -> dict:
    run_ids = {run for run, _analysis in ids.values()}
    if len(run_ids) != 1:
        raise BuildError("the analyses of one release must share a run")
    return {
        "series": scenario["series"],
        "label": release["id"],
        "run_id": next(iter(run_ids)),
        "analyses": [{"analysis_id": ids[arm][1]} for arm in scenario["arms"]],
        "profile": dict(scenario["release_profile"]),
        "notes": "Синтетический релиз демонстрационного набора, не реальный прогон.",
    }


def baseline_bodies(scenario: dict, release_id: str, ids: dict) -> list:
    """One manual baseline per slot of the release: arm A, arm B and the stages analysis S (no arm)."""
    slots = [slot for slot in [*scenario["arms"], "S"] if slot in ids]
    return [{"mode": "manual", "series": scenario["series"], "reference": {"run_id": ids[slot][0], "analysis_id": ids[slot][1]}} for slot in slots]


class ApiClient:
    """The local API: mutations need the session cookie, the CSRF token of /api/bootstrap, Host and Origin of the server."""

    def __init__(self, origin: str):
        self.origin = origin
        self.jar = CookieJar()
        # The system proxy must not see the loopback calls.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = None

    def bootstrap(self) -> dict:
        with self.opener.open(urllib.request.Request(self.origin + "/api/bootstrap"), timeout=30) as response:
            document = json.loads(response.read())
        self.csrf = document["csrf_token"]
        return document

    def post(self, path: str, body: dict) -> dict:
        headers = {"Content-Type": "application/json", "Origin": self.origin, "X-LTV-CSRF": self.csrf}
        request = urllib.request.Request(self.origin + path, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
        try:
            with self.opener.open(request, timeout=120) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as failure:
            raise BuildError(f"POST {path} failed with {failure.code}: {failure.read().decode('utf-8', 'replace')[:800]}") from failure


def start_ui(ltv: Path, data_dir: Path) -> tuple:
    """Starts `ltv ui` without a browser (headless AWT prints the origin to stderr) and returns (process, origin)."""
    env = dict(os.environ)
    env["JAVA_OPTS"] = (env.get("JAVA_OPTS", "") + " -Djava.awt.headless=true").strip()
    process = subprocess.Popen([str(ltv), "ui", "--data-dir", str(data_dir)], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True, encoding="utf-8", env=env)
    lines: queue.Queue = queue.Queue()

    def drain():
        for line in process.stderr:
            lines.put(line)
        lines.put(None)

    threading.Thread(target=drain, daemon=True).start()
    deadline = time.monotonic() + UI_START_TIMEOUT_S
    seen = []
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            continue
        if line is None:
            break
        seen.append(line)
        found = ORIGIN_LINE.match(line)
        if found:
            return process, found.group(1)
    stop_process(process)
    raise BuildError("ltv ui did not print its origin in time:\n" + "".join(seen[-20:]))


def stop_process(process: subprocess.Popen) -> None:
    """Stops the whole tree: on Windows ltv.bat starts java as a child that terminate() would leave running."""
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(process.pid)], capture_output=True)
    else:
        process.terminate()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()


def save_releases(client: ApiClient, scenario: dict, results: dict) -> dict:
    """POST /api/releases for every release in date order; returns the release ids the server issued."""
    issued = {}
    for release in sorted(scenario["releases"], key=lambda item: item["start"]):
        created = client.post("/api/releases", release_body(scenario, release, results[release["id"]]))
        issued[release["id"]] = created.get("release_id") or created.get("id")
    return issued


def set_baselines(client: ApiClient, scenario: dict, baseline_release: str, results: dict) -> list:
    return [client.post("/api/baseline", body) for body in baseline_bodies(scenario, baseline_release, results[baseline_release])]


def result_path(data_dir: Path, run_id: str, analysis_id: str) -> Path:
    return Path(data_dir) / "runs" / run_id / "analyses" / analysis_id / "analysis-result.json"


def build(ltv: Path, out: Path, data_dir: Path, profile_name: str, scenario: dict, skip_generate: bool, force_new: bool, combined_stages: bool) -> dict:
    started = time.monotonic()
    profile = dd.resolve_profile(profile_name)
    ensure_empty(data_dir, force_new)
    if not skip_generate:
        dd.generate(out, profile, scenario, None)
    check_free_space(out, data_dir)
    generated = time.monotonic()
    env = dict(os.environ)
    results: dict = {}
    for item in analysis_matrix(scenario, out, data_dir, combined_stages):
        run_id, analysis_id = run_analyze(ltv, item["args"], item["expected_exit"], env)
        results.setdefault(item["release"], {})[item["slot"]] = (run_id, analysis_id)
        print(f"analyze {item['release']} {item['slot']} exit {item['expected_exit']} {analysis_id[:12]}", flush=True)
    analyzed = time.monotonic()
    process, origin = start_ui(ltv, data_dir)
    try:
        client = ApiClient(origin)
        client.bootstrap()
        issued = save_releases(client, scenario, results)
        set_baselines(client, scenario, scenario["baseline_release"], results)
    finally:
        stop_process(process)
    return {
        "results": results,
        "releases": issued,
        "seconds": {"generate": round(generated - started, 1), "analyze": round(analyzed - generated, 1), "api": round(time.monotonic() - analyzed, 1)},
    }


def main(argv: list | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ltv", type=Path, required=True, help="build/install/ltv/bin/ltv (ltv.bat on Windows)")
    parser.add_argument("--out", type=Path, required=True, help="directory of the generated inputs")
    parser.add_argument("--data-dir", type=Path, required=True, help="data directory to build (empty or new)")
    parser.add_argument("--profile", choices=sorted(dd.PROFILES), default="demo")
    parser.add_argument("--scenario", type=Path, default=dd.DEFAULT_SCENARIO)
    parser.add_argument("--skip-generate", action="store_true", help="reuse the inputs already in --out")
    parser.add_argument("--force-new", action="store_true", help="build into a non-empty data directory")
    parser.add_argument("--combined-stages", action="store_true", help="one analysis per arm with --stages and --resources together (needs a core that allows it)")
    args = parser.parse_args(argv)
    try:
        outcome = build(args.ltv, args.out, args.data_dir, args.profile, dd.load_scenario(args.scenario), args.skip_generate, args.force_new, args.combined_stages)
    except BuildError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    print(f"{'release':<10} {'release_id':<24} analyses")
    for release, issued in outcome["releases"].items():
        print(f"{release:<10} {issued!s:<24} {', '.join(sorted(outcome['results'][release]))}")
    print(f"seconds: {outcome['seconds']}")
    print(f"open: {args.ltv} ui --data-dir {args.data_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

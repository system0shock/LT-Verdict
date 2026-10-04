"""Contract checks for the deterministic demo stand artifacts."""

import csv
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import stats_validation


GENERATOR = TOOLS / "demo-stand" / "generator" / "generate.py"
spec = importlib.util.spec_from_file_location("demo_stand_generate", GENERATOR)
generate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generate)

FAMILIES = (
    "demo_service_cpu_busy_ratio", "demo_db_busy_ratio",
    "demo_cpu_queue_requests", "demo_db_queue_requests",
    "demo_admission_queue_requests", "demo_downstream_inflight_requests",
    "demo_generator_active_threads", "demo_generator_queue_requests",
    "demo_generator_requests_per_second", "demo_target_requests_per_second",
    "demo_response_time_p95_seconds",
)


class DemoStandTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = Path(cls.tmp.name)
        generate.generate(["sla-fail"], cls.root / "first", stage_scale=0.05)
        generate.generate(["sla-fail"], cls.root / "second", stage_scale=0.05)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_determinism(self):
        for name in ("metrics.om", "sla-fail/load.jtl"):
            self.assertEqual((self.root / "first" / name).read_bytes(),
                             (self.root / "second" / name).read_bytes())

    def test_openmetrics_shape(self):
        raw = (self.root / "first/metrics.om").read_bytes()
        self.assertTrue(raw.endswith(b"# EOF\n"))
        self.assertNotIn(b"\r", raw)
        text = raw.decode("ascii")
        self.assertEqual(re.findall(r"^# TYPE (\w+) gauge$", text, re.M), list(FAMILIES))
        manifest = json.loads((self.root / "first/manifest.json").read_text(encoding="ascii"))["scenarios"][0]
        cell_ends = set(range(manifest["start_epoch_ms"] // 1000 + 5,
                              manifest["end_epoch_ms"] // 1000 + 1, 5))
        family = None
        timestamps = {}
        for line in text.splitlines():
            if line.startswith("# TYPE "):
                family = line.split()[2]
            elif not line.startswith("#"):
                match = re.fullmatch(r"(\w+)\{[^}]+\} (-?\d+(?:\.\d+)?) (\d+)", line)
                self.assertIsNotNone(match, line)
                name, _, timestamp = match.groups()
                self.assertEqual(name, family)
                timestamp = int(timestamp)
                self.assertIn(timestamp, cell_ends)
                self.assertGreater(timestamp, timestamps.get(name, 0))
                timestamps[name] = timestamp
        self.assertEqual(set(timestamps), set(FAMILIES))

    def test_jtl(self):
        raw = (self.root / "first/sla-fail/load.jtl").read_bytes()
        self.assertNotIn(b"\r", raw)
        self.assertEqual(raw.splitlines()[0], b"timeStamp,elapsed,label,success")
        rows = list(csv.DictReader(raw.decode("ascii").splitlines()))
        self.assertGreater(len(rows), 1500)
        self.assertEqual({row["label"] for row in rows}, {
            "GET /api/catalog", "POST /api/cart", "POST /api/checkout", "POST /api/refund"})
        self.assertEqual(rows[1500]["label"], "POST /api/refund")
        self.assertEqual(rows[499]["success"], "false")

    def test_capacity_plan(self):
        out = self.root / "capacity"
        generate.generate(["capacity"], out)
        jtl = (out / "capacity/load.jtl").read_bytes()
        resource_text = (out / "capacity/resources.json").read_text(encoding="ascii")
        resources = json.loads(resource_text, parse_float=Decimal)
        plan = json.loads((out / "capacity/capacity-plan.json").read_text(encoding="ascii"))
        manifest = json.loads((out / "manifest.json").read_text(encoding="ascii"))["scenarios"][0]
        self.assertEqual(plan["load_input_sha256"], hashlib.sha256(jtl).hexdigest())
        self.assertEqual(plan["resource_snapshot_sha256"], stats_validation.snapshot_hash(resources))
        self.assertEqual(resources["step_ms"], 10000)
        self.assertEqual(resources["point_count"] * 10000,
                         manifest["end_epoch_ms"] - manifest["start_epoch_ms"])
        self.assertEqual(resources["windows"], [
            {"id": stage["id"], "from_epoch_ms": stage["from_epoch_ms"],
             "to_epoch_ms": stage["to_epoch_ms"]} for stage in manifest["stages"]])
        self.assertEqual([stage["id"] for stage in plan["stages"]],
                         [stage["id"] for stage in manifest["stages"]])

    def test_dashboard_metrics(self):
        dashboard = json.loads((TOOLS / "demo-stand/grafana/dashboards/ltv-demo.json").read_text(encoding="ascii"))
        self.assertEqual(dashboard["uid"], "ltv-demo")
        self.assertEqual([panel["id"] for panel in dashboard["panels"]], list(range(1, 7)))
        metrics = (self.root / "first/metrics.om").read_text(encoding="ascii")
        names = set(re.findall(r"^# TYPE (\w+) gauge$", metrics, re.M))
        for panel in dashboard["panels"]:
            for target in panel["targets"]:
                self.assertIn(target["expr"], names)


if __name__ == "__main__":
    unittest.main()

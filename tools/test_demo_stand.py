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
from urllib.parse import parse_qs, urlsplit

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

    def test_soak_scenario_selection(self):
        original = ["sla-fail", "capacity", "saturation"]
        self.assertEqual(list(generate.SCENARIOS), original)
        self.assertEqual(generate.scenario_names("all"), original)
        self.assertEqual(generate.scenario_names("all-with-soak"), original + ["soak-4h"])
        self.assertEqual(generate.scenario_names("soak-4h"), ["soak-4h"])
        with self.assertRaises(ValueError):
            generate.parameters_for("nope", 1)
        with self.assertRaises(ValueError):
            generate.parameters_for("soak-4h", 0)

    def test_soak_parameters(self):
        parameters, stages = generate.parameters_for("soak-4h", 1.0)
        self.assertEqual(stages, [("steady", 60, 14_400_000_000)])
        self.assertEqual(parameters["start_epoch_ms"], 1790856000000)
        self.assertEqual(parameters["cpu_workers"], 1)
        self.assertEqual(parameters["cpu_demand_us"], 10000)
        self.assertEqual(parameters["db_workers"], 8)
        self.assertEqual(parameters["db_demand_us"], 2000)
        self.assertEqual(parameters["stages"], [
            {"rate_rps": 0, "duration_us": 120_000_000},
            {"rate_rps": 60, "duration_us": 14_400_000_000},
            {"rate_rps": 0, "duration_us": 120_000_000},
        ])
        self.assertNotIn("downstream_changes", parameters)
        schedule = parameters["cpu_demand_multiplier_schedule"]
        self.assertEqual(len(schedule), 24)
        self.assertEqual([entry["numerator"] for entry in schedule],
                         [100 + round(66 * k / 23) for k in range(24)])
        self.assertEqual({entry["denominator"] for entry in schedule}, {100})
        self.assertEqual([entry["from_us"] for entry in schedule],
                         [0] + [120_000_000 + 14_400_000_000 * k // 24
                                for k in range(1, 24)])
        self.assertTrue(all(left["from_us"] < right["from_us"]
                            for left, right in zip(schedule, schedule[1:])))
        self.assertLess(schedule[-1]["from_us"], 120_000_000 + 14_400_000_000)

        scaled, scaled_stages = generate.parameters_for("soak-4h", 0.05)
        self.assertEqual(scaled_stages, [("steady", 60, 720_000_000)])
        self.assertEqual([stage["duration_us"] for stage in scaled["stages"]],
                         [120_000_000, 720_000_000, 120_000_000])
        scaled_schedule = scaled["cpu_demand_multiplier_schedule"]
        self.assertEqual(len(scaled_schedule), 24)
        self.assertEqual([entry["from_us"] for entry in scaled_schedule],
                         [0] + [120_000_000 + 720_000_000 * k // 24
                                for k in range(1, 24)])
        self.assertLess(scaled_schedule[-1]["from_us"], 120_000_000 + 720_000_000)

        sla, _ = generate.parameters_for("sla-fail", 1.0)
        self.assertIn("downstream_changes", sla)
        self.assertNotIn("cpu_demand_multiplier_schedule", sla)

    def test_soak_generation_is_deterministic(self):
        for name in ("soak-first", "soak-second"):
            generate.generate(["soak-4h"], self.root / name, stage_scale=0.02)
        first = self.root / "soak-first"
        second = self.root / "soak-second"
        for name in ("soak-4h/load.jtl", "metrics.om"):
            self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())
        manifest = json.loads((first / "manifest.json").read_text(encoding="ascii"))["scenarios"]
        self.assertEqual(len(manifest), 1)
        record = manifest[0]
        self.assertEqual(record["name"], "soak-4h")
        self.assertEqual(record["start_epoch_ms"], 1790856000000)
        self.assertEqual(record["test_end_epoch_ms"] - record["test_start_epoch_ms"], 290000)
        self.assertGreater(record["jtl_row_count"], 10000)
        metrics = (first / "metrics.om").read_text(encoding="ascii")
        self.assertEqual(re.findall(r"^# TYPE (\w+) gauge$", metrics, re.M), list(FAMILIES))

    def test_soak_dashboard_link(self):
        dashboard = json.loads((TOOLS / "demo-stand/grafana/dashboards/ltv-demo.json").read_text(encoding="ascii"))
        links = dashboard["links"]
        self.assertEqual(len(links), 4)
        self.assertIn("soak", links[3]["title"])
        window = parse_qs(urlsplit(links[3]["url"]).query)
        self.assertEqual(int(window["from"][0]), 1790855880000)
        self.assertGreaterEqual(int(window["to"][0]), 1790856000000 + 14_400_000 + 240_000)

    def test_soak_compose_override(self):
        override = (TOOLS / "demo-stand/docker-compose.soak.yml").read_text(encoding="ascii")
        self.assertIn("all-with-soak", override)
        self.assertIn("generator:", override)
        self.assertNotIn("ports", override)
        self.assertNotIn("image", override)

    def test_soak_policy_matches_api_template(self):
        policy = json.loads((TOOLS / "demo-stand/policies/soak-4h.json").read_text(encoding="ascii"))
        template = json.loads((TOOLS / "../ui/src/shell/policy-templates/api-basic.json").read_text(encoding="ascii"))
        self.assertEqual(policy["schema_version"], "policy.v1")
        self.assertEqual(policy["policy_id"], "demo-soak-4h")
        self.assertEqual(policy["rules"], template["rules"])


if __name__ == "__main__":
    unittest.main()

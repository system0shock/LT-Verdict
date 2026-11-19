"""Tests of the synthetic 8-hour demo data set generator (tools/demo_dataset.py), tiny profile only."""

import csv
import json
import re
from decimal import Decimal
import hashlib
import tempfile
import unittest
from pathlib import Path

from tools import demo_dataset as dd

SCENARIO_PATH = Path(__file__).resolve().parent.parent / "examples" / "demo-8h" / "scenario.json"


class ScenarioTest(unittest.TestCase):
    def setUp(self):
        self.scenario = dd.load_scenario(SCENARIO_PATH)

    def test_stage_bounds_full(self):
        bounds = dd.stage_bounds(dd.resolve_profile("full"))
        minutes = [bounds[0][2] // 60000] + [stage[3] // 60000 for stage in bounds]
        self.assertEqual(minutes, [0, 30, 150, 180, 270, 300, 480])
        self.assertEqual([stage[0] for stage in bounds], ["ramp", "plateau-1", "step", "plateau-2", "recovery", "soak"])
        self.assertEqual([stage[1] for stage in bounds], ["excluded", "steady", "steady", "steady", "excluded", "steady"])

    def test_stage_bounds_tiny(self):
        profile = dd.resolve_profile("tiny")
        bounds = dd.stage_bounds(profile)
        self.assertEqual([stage[3] - stage[2] for stage in bounds], [90000, 360000, 90000, 270000, 90000, 540000])
        for stage in bounds:
            self.assertEqual(stage[2] % 15000, 0)
            self.assertEqual(stage[3] % 15000, 0)
        soak = bounds[-1]
        self.assertGreaterEqual((soak[3] - soak[2]) // profile["snapshot_step_ms"], 36)

    def test_profiles(self):
        demo, full, tiny = (dd.resolve_profile(name) for name in ("demo", "full", "tiny"))
        self.assertEqual((full["rps_scale"], demo["rps_scale"], tiny["rps_scale"]), (1.0, 0.2, 0.02))
        self.assertEqual(demo["duration_s"], 28800)
        self.assertEqual(tiny["duration_s"], 1440)
        self.assertEqual((demo["workloads"], tiny["workloads"]), (21, 4))
        with self.assertRaises(ValueError):
            dd.resolve_profile("huge")


def release_of(scenario, release_id):
    return next(item for item in scenario["releases"] if item["id"] == release_id)


def read_jtl(path):
    with open(path, encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))


def p95(values):
    ordered = sorted(values)
    return ordered[max(0, -(-95 * len(ordered) // 100) - 1)]


class JtlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.profile = dd.resolve_profile("tiny")
        cls.folder = tempfile.TemporaryDirectory()
        cls.paths = {}
        for release in cls.scenario["releases"]:
            path = Path(cls.folder.name) / f"{release['id']}.jtl"
            cls.paths[release["id"]] = (path, dd.write_jtl(path, release, cls.profile, cls.scenario))

    @classmethod
    def tearDownClass(cls):
        cls.folder.cleanup()

    def rows(self, release_id):
        return read_jtl(self.paths[release_id][0])

    def test_jtl_deterministic(self):
        release = release_of(self.scenario, "2026.09.3")
        again = Path(self.folder.name) / "again.jtl"
        digest = dd.write_jtl(again, release, self.profile, self.scenario)
        first, first_digest = self.paths["2026.09.3"]
        self.assertEqual(first.read_bytes(), again.read_bytes())
        self.assertEqual(digest, first_digest)
        self.assertEqual(digest, hashlib.sha256(again.read_bytes()).hexdigest())
        self.assertNotIn(bytes([13]), again.read_bytes())

    def test_jtl_header_and_first_timestamp(self):
        path, _ = self.paths["2026.09.3"]
        header = path.read_text(encoding="utf-8").splitlines()[0]
        self.assertEqual(header, "timeStamp,elapsed,label,responseCode,success,failureMessage")
        self.assertEqual(int(self.rows("2026.09.3")[0]["timeStamp"]), 1790676000000)  # 2026-09-29T10:00:00Z

    def test_jtl_shares(self):
        rows = self.rows("2026.09.3")
        for transaction in self.scenario["transactions"]:
            share = sum(1 for row in rows if row["label"] == transaction["name"]) / len(rows)
            self.assertAlmostEqual(share, transaction["share"], delta=0.02, msg=transaction["name"])

    def test_jtl_checkout_p95_after_knee(self):
        soak = dd.stage_bounds(self.profile, self.scenario)[-1]
        for release_id, check in (("2026.09.3", lambda v: v > 2000), ("2026.09.2", lambda v: v < 1000)):
            rows = self.rows(release_id)
            start = int(rows[0]["timeStamp"])
            tail = soak[2]  # the whole soak window: 20 cells of the tiny profile hold too few samples for a stable p95
            values = [int(r["elapsed"]) for r in rows if r["label"] == "POST /checkout" and tail <= int(r["timeStamp"]) - start < soak[3]]
            self.assertGreater(len(values), 50)
            self.assertTrue(check(p95(values)), (release_id, p95(values)))

    def test_release_without_checkout(self):
        labels = {row["label"] for row in self.rows("2026.09.0")}
        self.assertNotIn("POST /checkout", labels)
        self.assertEqual(len(labels), 7)

    def test_error_rows_have_failure_message(self):
        rows = self.rows("2026.09.3")
        errors = [row for row in rows if row["success"] == "false"]
        self.assertGreater(len(errors), 0)
        for row in rows:
            self.assertEqual(bool(row["failureMessage"]), row["success"] == "false")
            self.assertIn(row["responseCode"], ("200", "201", "204") if row["success"] == "true" else ("500", "503"))

    def test_load_multiplier_follows_stages(self):
        profile = dd.resolve_profile("full")
        at = lambda minute: dd.load_multiplier(profile, minute * 60000, self.scenario)
        self.assertEqual((at(0), at(30), at(100), at(150), at(180), at(220), at(270), at(300), at(400)), (250, 1000, 1000, 1000, 1500, 1500, 1500, 1000, 1000))
        self.assertEqual(at(15), 625)
        self.assertEqual(at(165), 1250)


DOC = Path(__file__).resolve().parent.parent / "docs" / "user" / "platform-metric-packs.md"


def number_rows(snapshot, entity=None, metric=None):
    return {
        row["id"]: [Decimal(v) for v in row["values"]]
        for row in snapshot["series"]
        if (entity is None or row["entity"] == entity) and (metric is None or row["metric"] == metric)
    }


class SnapshotTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.profile = dd.resolve_profile("tiny")
        cls.release = release_of(cls.scenario, "2026.09.3")
        cls.sha = "ab" * 32
        cls.arms = {arm: dd.build_snapshot(arm, cls.release, cls.profile, cls.scenario, cls.sha) for arm in ("A", "B")}

    def test_signals_match_pack_doc(self):
        documented = {}
        for line in DOC.read_text(encoding="utf-8").splitlines():
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            if len(cells) >= 6 and cells[1].startswith(("`openshift_", "`jvm_")):
                documented[cells[0].strip("`")] = (cells[1].strip("`"), cells[2].strip("`"), cells[3].strip("`"), cells[5].startswith("да"))
        self.assertGreaterEqual(len(documented), 12)
        for key, signal in dd.SIGNALS.items():
            metric, unit, aggregation, peak = documented[key]
            self.assertEqual((signal["metric"], signal["unit"]), (metric, unit), key)
            self.assertTrue(signal["aggregation"] == aggregation or (peak and signal["aggregation"] == "interval_max"), key)
        self.assertEqual(dd.SIGNALS["restarts"]["aggregation"], "interval_rate")

    def test_snapshot_limits(self):
        for name in ("demo", "full"):
            profile = dd.resolve_profile(name)
            snapshot = dd.build_snapshot("B", self.release, profile, self.scenario, self.sha)
            cells = len(snapshot["series"]) * snapshot["point_count"]
            self.assertLessEqual(len(snapshot["series"]), 1024)
            self.assertLessEqual(cells, 1500000)
            self.assertLessEqual(len(dd.snapshot_text(snapshot).encode()), 32 * 1024 * 1024)
            pairs = [(row["metric"], row["entity"]) for row in snapshot["series"]]
            self.assertEqual(len(pairs), len(set(pairs)))
            self.assertEqual(snapshot["point_count"], 1920)
        with self.assertRaises(ValueError):
            dd.check_snapshot_limits(800, 1920)
        dd.check_snapshot_limits(781, 1920)

    def test_arm_label_uniform(self):
        for arm, snapshot in self.arms.items():
            self.assertEqual({row["labels"]["arm"] for row in snapshot["series"]}, {arm})
            self.assertEqual([row["id"] for row in snapshot["series"]], sorted(row["id"] for row in snapshot["series"]))

    def test_story_arm_b(self):
        step = self.profile["snapshot_step_ms"] / 1000
        for arm, expect in (("B", 3), ("A", 0)):
            snapshot = self.arms[arm]
            restarts = number_rows(snapshot, "payments-svc", "openshift_restarts")["payments-svc.openshift_restarts"]
            self.assertEqual(round(sum(restarts) * Decimal(str(step))), expect)
            memory = number_rows(snapshot, "payments-svc", "openshift_container_memory_limit_ratio")["payments-svc.openshift_container_memory_limit_ratio"]
            if arm == "B":
                self.assertGreaterEqual(max(memory), Decimal("0.99"))
            else:
                self.assertLessEqual(max(memory), Decimal("0.75"))
        others = number_rows(self.arms["B"], None, "openshift_restarts")
        self.assertEqual([key for key, values in others.items() if any(values)], ["payments-svc.openshift_restarts"])

    def test_unavailable_replicas_zero(self):
        for snapshot in self.arms.values():
            rows = number_rows(snapshot, None, "openshift_unavailable_replicas")
            self.assertEqual(len(rows), 4)
            self.assertFalse(any(any(values) for values in rows.values()))

    def test_windows_match_stages(self):
        start = dd.start_epoch_ms(self.release)
        expected = [
            {"id": sid, "from_epoch_ms": start + begin, "to_epoch_ms": start + end}
            for sid, role, begin, end in dd.stage_bounds(self.profile, self.scenario)
            if role == "steady"
        ]
        for snapshot in self.arms.values():
            self.assertEqual(snapshot["windows"], expected)
            self.assertEqual(snapshot["start_epoch_ms"], start)

    def test_snapshot_rules_empty_and_bound(self):
        for snapshot in self.arms.values():
            self.assertEqual(snapshot["rules"], [])
            self.assertEqual(snapshot["load_input_sha256"], self.sha)
            self.assertEqual(snapshot["step_ms"], 15000)
            self.assertEqual(snapshot["point_count"], 96)

    def test_trends_are_planted_in_soak(self):
        snapshot = self.arms["B"]
        soak = dd.stage_bounds(self.profile, self.scenario)[-1]
        first, last = soak[2] // 15000, soak[3] // 15000
        for key in self.scenario["trend_slopes"]:
            metric_key, entity = key.split("@")
            row = number_rows(snapshot, entity, dd.SIGNALS[metric_key]["metric"])
            values = next(iter(row.values()))[first:last]
            half = len(values) // 2
            self.assertGreater(sum(values[half:]) / (len(values) - half), sum(values[:half]) / half, key)

    def test_snapshot_deterministic(self):
        again = dd.build_snapshot("B", self.release, self.profile, self.scenario, self.sha)
        self.assertEqual(dd.snapshot_text(again), dd.snapshot_text(self.arms["B"]))
        self.assertEqual(dd.snapshot_hash(again), dd.snapshot_hash(self.arms["B"]))
        self.assertNotEqual(dd.snapshot_hash(self.arms["A"]), dd.snapshot_hash(self.arms["B"]))


class PlansTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.profile = dd.resolve_profile("tiny")
        cls.release = release_of(cls.scenario, "2026.09.3")
        cls.snapshot = dd.build_snapshot("B", cls.release, cls.profile, cls.scenario, "cd" * 32)
        cls.sha = dd.snapshot_hash(cls.snapshot)

    def test_policy_rule_count(self):
        policy = dd.build_policy(self.scenario, self.profile)
        self.assertEqual(len(policy["rules"]) + len(policy["platform_rules"]), 9)
        self.assertEqual(len(policy["rules"]), 5)
        for rule in policy["rules"]:
            self.assertEqual(rule["window_ids"], ["soak"])
        self.assertEqual(policy["platform_services"], [item[0] for item in dd.workload_list(self.profile, self.scenario)])
        self.assertEqual(len(dd.build_policy(self.scenario, dd.resolve_profile("demo"))["platform_services"]), 21)

    def test_platform_coverage_present(self):
        policy = dd.build_policy(self.scenario, self.profile)
        self.assertEqual(policy["platform_coverage"], {"signal": "openshift_unavailable_replicas"})
        coverage = [rule for rule in policy["platform_rules"] if rule["signal"] == "openshift_unavailable_replicas"]
        self.assertEqual(len(coverage), 1)
        self.assertEqual(coverage[0]["effect"], "sla")
        self.assertNotIn("window_ids", coverage[0])
        signals = {key["metric"]: key for key in dd.SIGNALS.values()}
        for rule in policy["platform_rules"]:
            signal = signals[rule["signal"]]
            self.assertEqual((rule["unit"], rule["aggregation"]), (signal["unit"], signal["aggregation"]), rule["id"])

    def test_policy_throughput_scales_with_profile(self):
        thresholds = {
            name: Decimal(next(rule for rule in dd.build_policy(self.scenario, dd.resolve_profile(name))["rules"] if rule["metric"] == "throughput_rps")["threshold"])
            for name in ("full", "demo", "tiny")
        }
        self.assertEqual(thresholds, {"full": Decimal(90), "demo": Decimal(18), "tiny": Decimal("1.8")})

    def test_policy_stages_has_no_platform_sections(self):
        policy = dd.build_policy_stages(self.scenario, self.profile)
        self.assertEqual(len(policy["rules"]), 5)
        for key in ("platform_rules", "platform_services", "platform_coverage"):
            self.assertNotIn(key, policy)
        self.assertNotEqual(policy["policy_id"], dd.build_policy(self.scenario, self.profile)["policy_id"])

    def test_trend_plan_binds_hash(self):
        plan = dd.build_trend_plan(self.sha, self.scenario, self.profile)
        self.assertEqual(plan["resource_snapshot_sha256"], self.sha)
        self.assertEqual(len(plan["checks"]), 5)
        series = {row["id"] for row in self.snapshot["series"]}
        windows = {row["id"] for row in self.snapshot["windows"]}
        for check in plan["checks"]:
            self.assertGreaterEqual(check["min_cells"], 30)
            self.assertIn(check["window_id"], windows)
            self.assertIn(check["series_id"], series)

    def test_trend_magnitude_gate_below_planted_slope(self):
        plan = dd.build_trend_plan(self.sha, self.scenario, self.profile)
        gates = {check["series_id"]: Decimal(str(check["magnitude_gate"]["min_slope_units_per_second"])) for check in plan["checks"]}
        planted = {}
        for key, slope in self.scenario["trend_slopes"].items():
            signal, entity = key.split("@")
            planted[f"{entity}.{dd.SIGNALS[signal]['metric']}"] = Decimal(str(slope)) * Decimal(dd.FULL_DURATION_S) / Decimal(self.profile["duration_s"])
        self.assertEqual(set(gates), set(planted))
        for series_id, gate in gates.items():
            self.assertEqual(gate * 2, planted[series_id], series_id)
        self.assertIsNone(re.search("[0-9][eE][-+]?[0-9]", dd.dumps(plan)))

    def test_stages_valid(self):
        stages = dd.build_load_stages(self.profile, self.scenario)
        self.assertEqual(stages["schema_version"], "load-stages.v1")
        items = stages["stages"]
        self.assertEqual(len(items), 6)
        self.assertLessEqual(len(items), 16)
        self.assertTrue(any(item["role"] == "steady" for item in items))
        for before, after in zip(items, items[1:]):
            self.assertEqual(before["to_offset_ms"], after["from_offset_ms"])
        self.assertEqual(items[-1]["to_offset_ms"], self.profile["duration_s"] * 1000)

    def test_correlation_plan(self):
        series = {row["id"] for row in self.snapshot["series"]}
        for arm, pairs in (("A", 2), ("B", 3)):
            plan = dd.build_correlation_plan(self.sha, arm, self.scenario)
            self.assertEqual(plan["resource_snapshot_sha256"], self.sha)
            self.assertEqual(len(plan["pairs"]), pairs)
            for pair in plan["pairs"]:
                self.assertIn(pair["resource_series_id"], series)
                self.assertEqual(pair["controls"], [{"meaning": "achieved_rps"}])
                self.assertEqual(pair["window_ids"], ["soak"])

    def test_policy_validates_offline(self):
        import os
        import subprocess

        ltv = os.environ.get("LTV_BIN")
        if not ltv:
            self.skipTest("LTV_BIN is not set")
        for policy in (dd.build_policy(self.scenario, self.profile), dd.build_policy_stages(self.scenario, self.profile)):
            with tempfile.TemporaryDirectory() as folder:
                path = Path(folder) / "policy.json"
                path.write_text(dd.dumps(policy) + chr(10), encoding="utf-8")
                result = subprocess.run([ltv, "policy", "validate", str(path)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

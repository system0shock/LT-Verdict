"""Tests of the synthetic 8-hour demo data set generator (tools/demo_dataset.py), tiny profile only."""

import csv
import subprocess
import json
import re
from decimal import Decimal
import hashlib
import tempfile
import unittest
from pathlib import Path

from tools import demo_dataset as dd
from tools import demo_dataset_build as bld

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

    def test_jtl_covers_run_end(self):
        """Resource windows must lie inside the run: the last sample ends at or after the end of the last stage."""
        for release_id in ("2026.09.3", "2026.09.0"):
            rows = self.rows(release_id)
            start = int(rows[0]["timeStamp"])
            end = max(int(row["timeStamp"]) + int(row["elapsed"]) for row in rows)
            self.assertGreaterEqual(end - start, self.profile["duration_s"] * 1000, release_id)

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
        for check in plan["checks"]:
            self.assertIsNone(re.search("[eE]", str(check["magnitude_gate"]["min_slope_units_per_second"])))

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


class PodViewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.release = release_of(cls.scenario, "2026.09.3")
        cls.sha, cls.load = "ef" * 32, "12" * 32
        cls.views = {}
        for name in ("tiny", "demo"):
            profile = dd.resolve_profile(name)
            for arm in ("A", "B"):
                model = dd.simulate_arm(arm, cls.release, profile, cls.scenario)
                cls.views[(name, arm)] = dd.build_pod_view(model, cls.sha, cls.load, cls.release, profile, cls.scenario)

    @staticmethod
    def row(view, pod, container, metric):
        row = next(r for r in view["rows"] if r["pod"] == pod and r["container"] == container and r["metric"] == metric)
        return [Decimal(v) for v in row["values"]]

    def test_pod_view_limits(self):
        for (name, arm), view in self.views.items():
            profile = dd.resolve_profile(name)
            self.assertLessEqual(view["column_count"], 240)
            self.assertLessEqual(len(view["pods"]), 256)
            self.assertLessEqual(len(view["rows"]), 2560)
            self.assertLessEqual(len(dd.dumps(view).encode()), 12 * 1024 * 1024)
            self.assertEqual(view["step_ms"] % profile["snapshot_step_ms"], 0)
            self.assertEqual(view["column_count"] * view["step_ms"], profile["duration_s"] * 1000)
            for row in view["rows"]:
                self.assertEqual(len(row["values"]), view["column_count"])

    def test_pod_totals(self):
        for arm in ("A", "B"):
            view = self.views[("demo", arm)]
            self.assertEqual(len(view["pods"]), 78)
            self.assertEqual(len({pod["service"] for pod in view["pods"]}), 21)
            self.assertEqual(view["coverage"]["pods_included"], 78)

    def test_pod_view_story(self):
        b, a = self.views[("demo", "B")], self.views[("demo", "A")]
        leaking = self.row(b, "payments-svc-b2", "app", "openshift_container_memory_limit_ratio")
        self.assertGreaterEqual(max(leaking), Decimal("0.95"))
        for pod in ("payments-svc-b1", "payments-svc-b3", "payments-svc-b4"):
            self.assertLess(max(self.row(b, pod, "app", "openshift_container_memory_limit_ratio")), Decimal("0.8"))
        self.assertLess(max(self.row(a, "payments-svc-a2", "app", "openshift_container_memory_limit_ratio")), Decimal("0.8"))
        for view, arm in ((a, "a"), (b, "b")):
            hot = self.row(view, f"catalog-svc-{arm}3", "app", "openshift_container_cpu_limit_ratio")
            calm = self.row(view, f"catalog-svc-{arm}1", "app", "openshift_container_cpu_limit_ratio")
            self.assertGreater(sum(hot), Decimal("1.2") * sum(calm))
        for pod in ("checkout-api-b1", "checkout-api-b2"):
            self.assertGreater(max(self.row(b, pod, "app", "openshift_cpu_throttling")), Decimal("0.4"))
        for pod in ("checkout-api-b3", "checkout-api-b4"):
            self.assertLess(max(self.row(b, pod, "app", "openshift_cpu_throttling")), Decimal("0.2"))
        self.assertLess(max(self.row(a, "checkout-api-a1", "app", "openshift_cpu_throttling")), Decimal("0.2"))

    def test_pod_view_binds(self):
        for (name, arm), view in self.views.items():
            self.assertEqual(view["resource_snapshot_sha256"], self.sha)
            self.assertEqual(view["load_input_sha256"], self.load)
            self.assertEqual(view["arm"], arm)
            self.assertEqual(view["start_epoch_ms"], dd.start_epoch_ms(self.release))

    def test_pod_view_matches_snapshot(self):
        """The mean of the snapshot cells of a column of the worst pod cannot exceed the service series maximum."""
        profile = dd.resolve_profile("tiny")
        model = dd.simulate_arm("B", self.release, profile, self.scenario)
        view = self.views[("tiny", "B")]
        service = model["services"]["payments-svc"]["memory_limit_ratio"]
        pods = [r for r in view["rows"] if r["metric"] == "openshift_container_memory_limit_ratio" and r["pod"].startswith("payments-svc")]
        for column in range(view["column_count"]):
            worst = max(Decimal(r["values"][column]) for r in pods)
            self.assertLessEqual(worst * 1000, max(service[column * 4 : column * 4 + 4]))


class OpenSearchTest(unittest.TestCase):
    def test_opensearch_shape(self):
        scenario = dd.load_scenario(SCENARIO_PATH)
        release = release_of(scenario, "2026.09.3")
        for name in ("tiny", "demo"):
            profile = dd.resolve_profile(name)
            document = dd.build_opensearch_errors("34" * 32, release, profile, scenario)
            self.assertEqual(document["schema_version"], "opensearch-errors.v1")
            self.assertEqual(document["load_input_sha256"], "34" * 32)
            timeline = document["timeline"]
            self.assertEqual(len(timeline), profile["duration_s"] * 1000 // 15000)
            self.assertLessEqual(len(timeline), 100000)
            self.assertEqual(document["total_errors"], sum(cell["count"] for cell in timeline))
            self.assertEqual(document["total_errors"], sum(group["count"] for group in document["groups"]))
            knee = profile["duration_s"] * 1000 * scenario["load"]["knee_minute"] // scenario["load"]["total_minutes"] // 15000
            after = sum(cell["count"] for cell in timeline[knee:])
            self.assertGreater(after, 0.8 * document["total_errors"])
            self.assertTrue(all(group["service"] and group["error_type"] for group in document["groups"]))
            self.assertEqual(document["coverage"]["status"], "COMPLETE")
            self.assertEqual(timeline[0]["from_epoch_ms"], dd.start_epoch_ms(release))
            self.assertEqual(document["end_epoch_ms"], timeline[-1]["to_epoch_ms"])


RUN_FILES = (
    "load.jtl",
    "load-stages.json",
    "opensearch-errors.json",
    "arm-A/resource-snapshot.json",
    "arm-A/trend-plan.json",
    "arm-A/pod-view.json",
    "arm-A/correlation-plan.json",
    "arm-B/resource-snapshot.json",
    "arm-B/trend-plan.json",
    "arm-B/pod-view.json",
    "arm-B/correlation-plan.json",
)
GOLDEN_TINY_2026_09_3 = {
    "arm-A/correlation-plan.json": "a7bc4adcb2612b7983f63d56a47d48e717e0d54a0e79bbc2047311fa06917c41",
    "arm-A/pod-view.json": "bba5fe05564adc66fb59a581d87b7b292435da8f930036205055e43c81bdc550",
    "arm-A/resource-snapshot.json": "fe6e9e7b0267a91b41d37a523dff0e881b74c12813429bb84e2003b51024415c",
    "arm-A/trend-plan.json": "e1a8361095ecf2214c6459d23db17ee21ef775fad8493faf7a716d302e2a8f02",
    "arm-B/correlation-plan.json": "38a9c0067e12c2612af2be129f8ed06e45c1c0b2d9f5f1552821ccf7c372ce3f",
    "arm-B/pod-view.json": "d92fe849f63fbf9282aa6f6109bf0966db1c50203e2f400db744171ab4f8d884",
    "arm-B/resource-snapshot.json": "50d29fcae32c77f37878c446de0145255a33b6cea1a89b622787e910391fe849",
    "arm-B/trend-plan.json": "497c1dabe2169b7db967097843013f43f0643967a069155266f78c944529fb89",
    "load-stages.json": "f10fc3e4f45784964ca3be2814bd6b13e04644b86b8dd7394b9966893167ebc3",
    "load.jtl": "10ae2db35a812c72a2cfd4814f5e72610d1d70a5ee2340fc86dd2cf33574083a",
    "opensearch-errors.json": "0993f5b80b3a39901c41c3555cde8c52afd5857a1b1cf5c14a82e62b31481781",
}


def file_sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def tree_hashes(root):
    root = Path(root)
    return {str(p.relative_to(root)).replace(chr(92), "/"): file_sha(p) for p in sorted(root.rglob("*")) if p.is_file()}


class GenerateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.profile = dd.resolve_profile("tiny")
        cls.folders = [tempfile.TemporaryDirectory(), tempfile.TemporaryDirectory()]
        cls.manifests = [dd.generate(Path(folder.name), cls.profile, cls.scenario, None) for folder in cls.folders]
        cls.out = Path(cls.folders[0].name)

    @classmethod
    def tearDownClass(cls):
        for folder in cls.folders:
            folder.cleanup()

    def test_generate_tiny_all_files(self):
        for release in self.scenario["releases"]:
            for name in RUN_FILES:
                self.assertTrue((self.out / release["id"] / name).is_file(), (release["id"], name))
        for name in ("policy.json", "policy-stages.json", "manifest.json"):
            self.assertTrue((self.out / name).is_file(), name)
        expected = {"policy.json", "policy-stages.json", "manifest.json"} | {f"{r['id']}/{n}" for r in self.scenario["releases"] for n in RUN_FILES}
        self.assertEqual(set(tree_hashes(self.out)), expected)

    def test_generate_byte_identical(self):
        self.assertEqual(tree_hashes(self.folders[0].name), tree_hashes(self.folders[1].name))
        self.assertEqual(self.manifests[0], self.manifests[1])

    def test_generate_golden_hashes(self):
        actual = {name: file_sha(self.out / "2026.09.3" / name) for name in RUN_FILES}
        self.assertEqual(actual, GOLDEN_TINY_2026_09_3)

    def test_generate_only_one_release(self):
        with tempfile.TemporaryDirectory() as folder:
            dd.generate(Path(folder), self.profile, self.scenario, "2026.09.2")
            self.assertEqual({p.name for p in Path(folder).iterdir() if p.is_dir()}, {"2026.09.2"})
            self.assertEqual(file_sha(Path(folder) / "2026.09.2" / "load.jtl"), file_sha(self.out / "2026.09.2" / "load.jtl"))
            with self.assertRaises(ValueError):
                dd.generate(Path(folder), self.profile, self.scenario, "no-such-release")

    def test_no_wall_clock(self):
        import datetime as real_datetime
        from unittest import mock

        class Guard(real_datetime.datetime):
            @classmethod
            def now(cls, tz=None):
                raise AssertionError("wall clock read")

            @classmethod
            def utcnow(cls):
                raise AssertionError("wall clock read")

        def forbidden(*args, **kwargs):
            raise AssertionError("wall clock read")

        with tempfile.TemporaryDirectory() as folder, mock.patch("time.time", forbidden), mock.patch.object(dd, "datetime", Guard):
            dd.generate(Path(folder), self.profile, self.scenario, "2026.09.2")

    def test_bindings_consistent(self):
        for release in self.scenario["releases"]:
            base = self.out / release["id"]
            load_sha = file_sha(base / "load.jtl")
            self.assertEqual(json.loads((base / "opensearch-errors.json").read_text(encoding="utf-8"))["load_input_sha256"], load_sha)
            for arm in ("A", "B"):
                read = lambda name: json.loads((base / f"arm-{arm}" / name).read_text(encoding="utf-8"), parse_float=Decimal, parse_int=Decimal)
                snapshot = read("resource-snapshot.json")
                semantic = dd.stats_validation.snapshot_hash(snapshot)
                self.assertEqual(snapshot["load_input_sha256"], load_sha)
                self.assertEqual(read("pod-view.json")["load_input_sha256"], load_sha)
                for name in ("pod-view.json", "trend-plan.json", "correlation-plan.json"):
                    self.assertEqual(read(name)["resource_snapshot_sha256"], semantic, (release["id"], arm, name))
                self.assertEqual({row["labels"]["arm"] for row in snapshot["series"]}, {arm})

    def test_manifest_lists_files(self):
        manifest = json.loads((self.out / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["profile"], "tiny")
        self.assertEqual(manifest["releases"]["2026.09.3"]["load_sha256"], file_sha(self.out / "2026.09.3" / "load.jtl"))
        self.assertEqual(manifest["files"]["2026.09.3/load.jtl"]["sha256"], file_sha(self.out / "2026.09.3" / "load.jtl"))
        self.assertEqual(manifest["files"]["policy.json"]["bytes"], (self.out / "policy.json").stat().st_size)


class BuildTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scenario = dd.load_scenario(SCENARIO_PATH)
        cls.out = Path("out")
        cls.data = Path("data")
        cls.ltv = Path("ltv")

    def matrix(self, **options):
        return bld.analysis_matrix(self.scenario, self.out, self.data, **options)

    def test_analysis_matrix(self):
        matrix = self.matrix()
        self.assertEqual(len(matrix), 14)
        self.assertEqual([item["release"] for item in matrix][:3], ["2026.08.2"] * 2 + ["2026.08.3"])  # oldest first
        expected = {("2026.09.3", "A"): 2, ("2026.09.3", "B"): 2, ("2026.09.3", "S"): 2, ("2026.09.2", "S"): 0, ("2026.09.0", "A"): 3, ("2026.09.0", "B"): 3, ("2026.08.3", "B"): 2, ("2026.08.2", "A"): 0}
        actual = {(item["release"], item["slot"]): item["expected_exit"] for item in matrix}
        for key, code in expected.items():
            self.assertEqual(actual[key], code, key)
        self.assertEqual(sum(1 for item in matrix if item["slot"] == "S"), 2)
        for item in matrix:
            args = item["args"]
            flags = [a for a in args if a.startswith("--")]
            if item["slot"] == "S":
                self.assertEqual(flags, ["--policy", "--stages", "--data-dir"])
                self.assertIn("policy-stages.json", args[args.index("--policy") + 1])
            else:
                self.assertEqual(flags, ["--policy", "--resources", "--trend", "--pod-view", "--correlation", "--source-context", "--data-dir"])
                self.assertIn(f"arm-{item['slot']}", args[args.index("--resources") + 1])

    def test_combined_stages_is_one_switch(self):
        matrix = self.matrix(combined_stages=True)
        self.assertEqual(len(matrix), 12)
        self.assertNotIn("S", {item["slot"] for item in matrix})
        for item in matrix:
            self.assertIn("--stages", item["args"])
            self.assertIn("--resources", item["args"])

    def test_unexpected_exit_code_fails(self):
        from unittest import mock

        done = subprocess.CompletedProcess([], 4, stdout="", stderr="boom")
        with mock.patch("subprocess.run", return_value=done):
            with self.assertRaises(bld.BuildError):
                bld.run_analyze(self.ltv, ["x.jtl"], 2, {})

    def test_parse_ids_from_stderr(self):
        from unittest import mock

        analysis, run = "ab" * 32, "jmeter_jtl_csv-" + "cd" * 32
        done = subprocess.CompletedProcess([], 2, stdout="{}", stderr=f"warning line\nanalysis_id={analysis} run_id={run}\n")
        with mock.patch("subprocess.run", return_value=done):
            self.assertEqual(bld.run_analyze(self.ltv, ["x.jtl"], 2, {}), (run, analysis))
        with mock.patch("subprocess.run", return_value=subprocess.CompletedProcess([], 2, stdout="{}", stderr="no ids")):
            with self.assertRaises(bld.BuildError):
                bld.run_analyze(self.ltv, ["x.jtl"], 2, {})

    def test_nonempty_data_dir_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            bld.ensure_empty(Path(folder) / "new", False)
            bld.ensure_empty(Path(folder), False)
            (Path(folder) / "runs").mkdir()
            with self.assertRaises(bld.BuildError):
                bld.ensure_empty(Path(folder), False)
            bld.ensure_empty(Path(folder), True)

    def test_release_bodies(self):
        ids = {"A": ("jmeter_jtl_csv-" + "1" * 64, "a" * 64), "B": ("jmeter_jtl_csv-" + "1" * 64, "b" * 64)}
        release = release_of(self.scenario, "2026.09.3")
        body = bld.release_body(self.scenario, release, ids)
        self.assertEqual(set(body), {"series", "label", "run_id", "analyses", "profile", "notes"})
        self.assertEqual((body["series"], body["label"], body["run_id"]), ("Checkout", "2026.09.3", "jmeter_jtl_csv-" + "1" * 64))
        self.assertEqual(body["analyses"], [{"analysis_id": "a" * 64}, {"analysis_id": "b" * 64}])
        self.assertEqual(set(body["profile"]), {"scenario_mix", "environment_dataset", "load_model", "targets_stages", "pacing", "generator_limits"})

    def test_release_profile_fits_the_text_limit(self):
        for name, value in self.scenario["release_profile"].items():
            self.assertLessEqual(len(value.encode("utf-8")), 128, name)

    def test_baseline_bodies(self):
        run = "jmeter_jtl_csv-" + "2" * 64
        ids = {"A": (run, "a" * 64), "B": (run, "b" * 64), "S": (run, "c" * 64)}
        bodies = bld.baseline_bodies(self.scenario, "2026.09.2", ids)
        self.assertEqual(len(bodies), 3)
        for body, slot in zip(bodies, "ABS"):
            self.assertEqual(body, {"mode": "manual", "series": "Checkout", "reference": {"run_id": run, "analysis_id": ids[slot][1]}})


if __name__ == "__main__":
    unittest.main()

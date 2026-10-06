"""Checks for the synthetic pod-view producer (plan P2d): determinism, binding, closed-formula reference, scenarios."""

import hashlib
import json
import re
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

from tools import stats_validation
from tools.platform_synthetic import generate, normalize
from tools.pod_view_adapter import AdapterError

FIXTURE = Path(__file__).resolve().parent.parent / "fixtures" / "platform" / "pod-view-synthetic"
SMALL = {"services": 2, "pods_per_service": [3, 2], "load_rows": 6000, "snapshot_step_ms": 5000, "pod_step_ms": 10000}
SET = ("resource-snapshot.json", "pod-view.json", "expected.json")


def load(folder, name):
    return json.loads((Path(folder.name) / name).read_text(encoding="utf-8"), parse_float=Decimal, parse_int=Decimal)


def rows_by(view):
    return {(row["pod"], row["container"], row["metric"]): [None if v is None else Decimal(v) for v in row["values"]] for row in view["rows"]}


class SyntheticTest(unittest.TestCase):
    def build(self, **changes):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        generate({**SMALL, **changes}, folder.name)
        return folder

    def test_two_runs_are_byte_equal_and_the_seed_changes_the_values(self):
        first, second, other = self.build(), self.build(), self.build(seed=2)
        for name in (*SET, "load.jtl"):
            self.assertEqual((Path(first.name) / name).read_bytes(), (Path(second.name) / name).read_bytes(), name)
        self.assertNotEqual((Path(first.name) / "pod-view.json").read_bytes(), (Path(other.name) / "pod-view.json").read_bytes())

    def test_the_committed_fixture_is_what_its_spec_generates(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        generate(json.loads((FIXTURE / "spec.json").read_text(encoding="utf-8")), folder.name)
        for name in SET:
            self.assertEqual((FIXTURE / name).read_bytes(), (Path(folder.name) / name).read_bytes(), name)

    def test_the_three_files_are_bound_to_each_other_and_to_the_load(self):
        folder = self.build(arm="B")
        view, snapshot = load(folder, "pod-view.json"), load(folder, "resource-snapshot.json")
        load_sha = hashlib.sha256((Path(folder.name) / "load.jtl").read_bytes()).hexdigest()
        self.assertEqual(load_sha, view["load_input_sha256"])
        self.assertEqual(load_sha, snapshot["load_input_sha256"])
        self.assertEqual(stats_validation.snapshot_hash(snapshot), view["resource_snapshot_sha256"])
        self.assertEqual("B", view["arm"])
        self.assertTrue(all(item["labels"]["arm"] == "B" for item in snapshot["series"]))
        self.assertEqual(snapshot["start_epoch_ms"], view["start_epoch_ms"])
        self.assertEqual(0, view["step_ms"] % snapshot["step_ms"])
        self.assertEqual(-(-snapshot["point_count"] * snapshot["step_ms"] // view["step_ms"]), view["column_count"])
        # load.jtl is 6000 rows of 10 ms, so the grid covers exactly its 60 seconds.
        self.assertEqual(60_000, snapshot["point_count"] * snapshot["step_ms"])

    def test_value_tokens_have_no_exponent_and_fit_twelve_bytes(self):
        folder = self.build(load_rows=9000, snapshot_step_ms=5000, pod_step_ms=15000)
        text = (Path(folder.name) / "pod-view.json").read_text(encoding="utf-8")
        tokens = [t for body in re.findall(r'"values":\[([^\]]*)\]', text) for t in body.split(",")]
        self.assertGreater(len(tokens), 100)
        for token in tokens:
            self.assertLessEqual(len(token.encode()), 12, token)
            self.assertNotRegex(token, r"[eE]")

    def test_expected_json_equals_an_independent_recomputation_from_pod_view(self):
        folder = self.build()
        view, expected = load(folder, "pod-view.json"), load(folder, "expected.json")
        self.assertEqual(len(view["rows"]), len(expected["rows"]))
        for row, ref in zip(view["rows"], expected["rows"], strict=True):
            observed = [v for v in row["values"] if v is not None]
            self.assertEqual(row["id"], ref["id"])
            self.assertEqual(max(observed), ref["max"])
            self.assertEqual(observed[-1], ref["last"])
            self.assertEqual((sum(observed) / len(observed)).quantize(Decimal("0.000001")), ref["mean"].quantize(Decimal("0.000001")))
        self.assertEqual(view["coverage"], expected["coverage"])

    def test_service_series_are_the_worst_container_when_a_column_is_one_cell(self):
        folder = self.build(snapshot_step_ms=10000, pod_step_ms=10000)
        view, snapshot = load(folder, "pod-view.json"), load(folder, "resource-snapshot.json")
        rows = rows_by(view)
        pods = {p["pod"]: p["service"] for p in view["pods"]}
        self.assertEqual(2 * 3, len(snapshot["series"]))
        for series in snapshot["series"]:
            members = [v for (pod, _c, metric), v in rows.items() if metric == series["metric"] and pods[pod] == series["entity"]]
            self.assertTrue(members)
            self.assertEqual([max(column) for column in zip(*members, strict=True)], [Decimal(v) for v in series["values"]])

    def test_the_memory_leak_is_planted_on_the_second_pod_of_arm_b_only(self):
        for arm, planted in (("B", True), ("A", False)):
            folder = self.build(scenario="leak-on-arm-b-pod-b2", arm=arm)
            expected, rows = load(folder, "expected.json"), rows_by(load(folder, "pod-view.json"))
            memory = rows[(f"svc-01-{arm.lower()}2", "app", "openshift_container_memory_limit_ratio")]
            if planted:
                self.assertEqual("MEMORY_LEAK", expected["planted_effect"]["kind"])
                self.assertEqual("svc-01-b2", expected["worst_pod_by_max"]["openshift_container_memory_limit_ratio"])
                self.assertGreater(memory[-1] - memory[0], Decimal("0.3"))
            else:
                self.assertIsNone(expected["planted_effect"])
                self.assertLess(max(memory), Decimal("0.6"))

    def test_throttling_is_planted_on_one_pod_and_balanced_has_no_effect(self):
        throttled = self.build(scenario="throttle-on-one-pod")
        expected, rows = load(throttled, "expected.json"), rows_by(load(throttled, "pod-view.json"))
        self.assertEqual("CPU_THROTTLING", expected["planted_effect"]["kind"])
        self.assertEqual("svc-01-a2", expected["worst_pod_by_max"]["openshift_cpu_throttling"])
        self.assertGreater(max(rows[("svc-01-a2", "app", "openshift_cpu_throttling")]), Decimal("0.5"))
        self.assertLess(max(rows[("svc-01-a1", "app", "openshift_cpu_throttling")]), Decimal("0.1"))
        balanced = load(self.build(), "expected.json")
        self.assertIsNone(balanced["planted_effect"])
        self.assertEqual("balanced", balanced["spec"]["scenario"])

    def test_the_stand_shape_of_seventy_six_pods_and_two_database_pods_fits_the_limits(self):
        pods = [6, 4, 4, 8, 3, 4, 5, 6, 3, 6, 2, 2, 4, 3, 2, 5, 2, 2, 3, 2, 2]
        self.assertEqual(78, sum(pods))
        folder = self.build(services=21, pods_per_service=pods, load_rows=3000, snapshot_step_ms=5000, pod_step_ms=5000)
        view = load(folder, "pod-view.json")
        self.assertEqual(78, view["coverage"]["pods_included"])
        self.assertEqual(78 * 7, view["coverage"]["rows_included"])
        self.assertEqual(21, len({pod["service"] for pod in view["pods"]}))
        sidecars = {c["name"] for pod in view["pods"] for c in pod["containers"] if c["role"] == "sidecar"}
        self.assertEqual({"istio-proxy"}, sidecars)

    def test_invalid_specs_are_refused(self):
        for changes in (
            {"unknown": 1},
            {"scenario": "nope"},
            {"scenario": "throttle-on-one-pod", "pods_per_service": [1, 3]},
            {"services": 3, "pods_per_service": [1, 2]},
            {"load_rows": 7},
            {"pod_step_ms": 7500},
            {"load_rows": 360000, "snapshot_step_ms": 1000, "pod_step_ms": 1000},
            {"containers": ["a", "b", "c", "d", "e"]},
            {"start_epoch_ms": 1704070800000},
            {"pod_step_ms": 0},
            {"services": 1, "pods_per_service": 257},
            {"services": 64, "pods_per_service": 4, "containers": ["a", "b", "c", "d"]},
            {"services": 20, "pods_per_service": 2, "load_rows": 3_000_000, "snapshot_step_ms": 1000, "pod_step_ms": 150_000},
        ):
            with self.subTest(changes), self.assertRaises(AdapterError):
                normalize({**SMALL, **changes})


if __name__ == "__main__":
    unittest.main()

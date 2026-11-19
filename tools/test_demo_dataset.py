"""Tests of the synthetic 8-hour demo data set generator (tools/demo_dataset.py), tiny profile only."""

import csv
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


if __name__ == "__main__":
    unittest.main()

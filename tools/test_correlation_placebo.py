from decimal import Decimal
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
import correlation_placebo as cp

START = 1767225600000
STEP = 10000


def snapshot(count=60, windows=((10, 50),)):
    def series(sid, role):
        return {"id": sid, "metric": sid, "unit": "ratio", "entity": "orders", "role": role,
                "aggregation": "interval_mean", "labels": {}, "values": list(range(count))}

    return {"schema_version": "resource-snapshot.v1", "load_input_sha256": "0" * 64,
            "start_epoch_ms": START, "step_ms": STEP, "point_count": count,
            "series": [series("cpu", "system"), series("target", "generator")],
            "windows": [{"id": f"w{i}", "from_epoch_ms": START + a * STEP, "to_epoch_ms": START + b * STEP}
                        for i, (a, b) in enumerate(windows)]}


class CorrelationPlaceboTest(unittest.TestCase):
    def test_shifts_system_series_inside_the_window_only(self):
        source = snapshot()
        shifted, shifts = cp.placebo(source, 1, 1)
        self.assertEqual(shifts, {"w0": 20})
        cpu = shifted["series"][0]["values"]
        self.assertEqual(cpu[:10], list(range(10)))
        self.assertEqual(cpu[50:], list(range(50, 60)))
        self.assertEqual(cpu[10:50], list(range(30, 50)) + list(range(10, 30)))
        self.assertEqual(shifted["series"][1]["values"], list(range(60)))
        self.assertEqual(source["series"][0]["values"], list(range(60)))

    def test_keeps_values_and_gaps_as_a_multiset(self):
        source = snapshot()
        source["series"][0]["values"][12] = None
        shifted, _ = cp.placebo(source, 2, 3)
        window = shifted["series"][0]["values"][10:50]
        self.assertEqual(window.count(None), 1)
        self.assertEqual(sorted(v for v in window if v is not None),
                         sorted(v for v in source["series"][0]["values"][10:50] if v is not None))

    def test_several_placebos_use_distinct_shifts_beyond_the_lag_range(self):
        source = snapshot(count=120, windows=((0, 120),))
        shifts = [cp.placebo(source, index, 3)[1]["w0"] for index in (1, 2, 3)]
        self.assertEqual(shifts, [30, 60, 90])

    def test_no_windows_means_the_whole_snapshot(self):
        source = snapshot(windows=())
        shifted, shifts = cp.placebo(source, 1, 1)
        self.assertEqual(shifts, {"run-intersection": 30})
        self.assertEqual(shifted["series"][0]["values"], list(range(30, 60)) + list(range(30)))

    def test_refuses_short_overlapping_and_off_grid_windows(self):
        with self.assertRaisesRegex(cp.PlaceboError, "WINDOW_TOO_SHORT"):
            cp.placebo(snapshot(windows=((0, 21),)), 1, 1)
        cp.placebo(snapshot(windows=((0, 22),)), 1, 1)
        with self.assertRaisesRegex(cp.PlaceboError, "WINDOWS_OVERLAP"):
            cp.placebo(snapshot(windows=((0, 30), (29, 59))), 1, 1)
        off_grid = snapshot()
        off_grid["windows"][0]["from_epoch_ms"] += 1
        with self.assertRaisesRegex(cp.PlaceboError, "WINDOW_NOT_ON_GRID"):
            cp.placebo(off_grid, 1, 1)
        with self.assertRaisesRegex(cp.PlaceboError, "UNSUPPORTED_SCHEMA"):
            cp.placebo({**snapshot(), "schema_version": "resource-snapshot.v2"}, 1, 1)

    def test_cli_writes_files_and_keeps_number_text(self):
        source = snapshot()
        text = json.dumps(source).replace('"values": [0, 1,', '"values": [0.10000000000000001, 1e-7,', 1)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "snapshot.json"
            path.write_text(text, encoding="utf-8")
            self.assertEqual(cp.main([str(path), "--out-dir", str(Path(directory) / "out"), "--count", "2"]), 0)
            written = (Path(directory) / "out" / "placebo-1.json").read_text(encoding="utf-8")
            self.assertTrue((Path(directory) / "out" / "placebo-2.json").exists())
            self.assertIn("0.10000000000000001", written)
            parsed = json.loads(written, parse_float=Decimal)
            self.assertEqual(parsed["series"][0]["values"][1], Decimal("1E-7"))
            self.assertEqual(parsed["windows"], source["windows"])
            self.assertEqual(cp.main([str(path), "--out-dir", str(Path(directory) / "bad"), "--count", "5"]), 4)
            self.assertFalse((Path(directory) / "bad").exists())

    def test_cli_rebinds_the_plan_to_every_placebo_snapshot(self):
        source = snapshot()
        plan = {"schema_version": "correlation-plan.v1", "resource_snapshot_sha256": cp.snapshot_hash(source),
                "pairs": [{"id": "cpu-latency"}]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "snapshot.json").write_text(json.dumps(source), encoding="utf-8")
            (root / "plan.json").write_text(json.dumps(plan), encoding="utf-8")
            arguments = [str(root / "snapshot.json"), "--out-dir", str(root / "out"), "--count", "1"]
            self.assertEqual(cp.main(arguments + ["--plan", str(root / "plan.json")]), 0)
            shifted = json.loads((root / "out" / "placebo-1.json").read_text(encoding="utf-8"))
            rebound = json.loads((root / "out" / "placebo-1.plan.json").read_text(encoding="utf-8"))
            self.assertEqual(rebound["resource_snapshot_sha256"], cp.snapshot_hash(shifted))
            self.assertNotEqual(rebound["resource_snapshot_sha256"], plan["resource_snapshot_sha256"])
            self.assertEqual(rebound["pairs"], plan["pairs"])
            (root / "plan.json").write_text(json.dumps({**plan, "resource_snapshot_sha256": "0" * 64}))
            self.assertEqual(cp.main(arguments + ["--plan", str(root / "plan.json")]), 4)

    def test_example_plan_hash_matches_the_reference_hash(self):
        examples = Path(__file__).resolve().parents[1] / "examples/basic"
        source = json.loads((examples / "resource-snapshot.json").read_text(encoding="utf-8"), parse_float=Decimal)
        plan = json.loads((examples / "correlation-plan.json").read_text(encoding="utf-8"))
        self.assertEqual(cp.snapshot_hash(source), plan["resource_snapshot_sha256"])


if __name__ == "__main__":
    unittest.main()

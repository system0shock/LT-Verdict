import json
from pathlib import Path
import sys
import tempfile
import unittest

try:
    import numpy as np
except ModuleNotFoundError as error:
    if error.name != "numpy":
        raise
    raise unittest.SkipTest("numpy is not installed")

sys.path.insert(0, str(Path(__file__).parent))
import correlation_devstudy as ds
import correlation_scenarios as cs


def fake_process(entry):
    scenario_id, seed = entry
    return {"scenario_id": scenario_id, "seed": seed,
            "variants": {v: {"false": (seed + i) % 5 == 0, "false_nomat": (seed + i) % 4 == 0,
                             "detected": None, "detected_nomat": None,
                             "evaluated": True, "activated": None}
                         for i, v in enumerate(ds.VARIANTS)}}


def hyp(pub, lag=None, sign=None, nomat=None):
    return {"pub": pub, "pub_nomat": pub if nomat is None else nomat,
            "lag": lag, "sign": sign, "avail": True}


class PureHelpers(unittest.TestCase):
    def test_declared_constants(self):
        self.assertEqual(ds.VARIANTS, ("levels", "first_difference", "detrend"))
        self.assertEqual((ds.ALPHA, ds.MIN_ABS_EFFECT), (0.05, 0.3))

    def test_detrend_removes_a_linear_trend_and_nothing_else(self):
        trend = [3.0 + 0.5 * t for t in range(40)]
        self.assertTrue(all(abs(v) < 1e-9 for v in ds.detrend(trend)))
        noise = [float((t * 17) % 11) for t in range(40)]
        mixed = ds.detrend([a + b for a, b in zip(trend, noise)])
        again = ds.detrend(mixed)
        self.assertTrue(all(abs(a - b) < 1e-9 for a, b in zip(mixed, again)))
        self.assertAlmostEqual(sum(mixed), 0.0, places=9)
        self.assertAlmostEqual(sum(t * v for t, v in enumerate(mixed)), 0.0, places=7)

    def test_best_lag_uses_product_tie_break(self):
        profile = [0.2, -0.5, 0.1, 0.1, 0.5]  # lags -2..2
        self.assertEqual(ds.best_lag(profile, 2), (-1, -0.5))
        self.assertEqual(ds.best_lag([0.4, None, -0.4], 1), (-1, 0.4))
        self.assertEqual(ds.best_lag([0.4, 0.4, 0.4], 1), (0, 0.4))
        self.assertIsNone(ds.best_lag([None, None, None], 1))

    def test_longest_run_bounds_first_longest_wins(self):
        values = [1.0] * 5 + [None] + [2.0] * 5 + [None] + [3.0] * 2
        self.assertEqual(ds.run_bounds(values), (0, 5))
        self.assertEqual(ds.run_bounds([None, 1.0, 2.0, None]), (1, 3))
        self.assertEqual(ds.run_bounds([1.0, 2.0, 3.0]), (0, 3))

    def test_early_stop_is_the_c5_gate_arithmetic(self):
        self.assertFalse(ds.early_stop(54, 1000))
        self.assertTrue(ds.early_stop(55, 1000))
        self.assertFalse(ds.early_stop(0, 1000))


class Scoring(unittest.TestCase):
    def trace(self, **truth):
        base = {"null_hypotheses": list(range(16)), "planted": None, "common_factor_indices": [], "level_effect": None}
        base.update(truth)
        return {"truth": base, "activation": None}

    def test_false_report_is_any_publication_on_a_null_hypothesis(self):
        quiet = [[hyp(False) for _ in range(16)]]
        self.assertFalse(ds.score(self.trace(), quiet)["false"])
        loud = [[hyp(i == 3) for i in range(16)]]
        scored = ds.score(self.trace(), loud)
        self.assertTrue(scored["false"] and scored["false_nomat"])
        self.assertIsNone(scored["detected"])

    def test_materiality_free_count_is_reported_separately(self):
        stage = [[hyp(False, nomat=(i == 2)) for i in range(16)]]
        scored = ds.score(self.trace(), stage)
        self.assertFalse(scored["false"])
        self.assertTrue(scored["false_nomat"])

    def test_false_report_unions_stages(self):
        stages = [[hyp(False) for _ in range(16)], [hyp(i == 7) for i in range(16)]]
        self.assertTrue(ds.score(self.trace(), stages)["false"])

    def test_detection_needs_sign_and_lag_within_one_cell(self):
        truth = {"null_hypotheses": [i for i in range(16) if i != 4],
                 "planted": {"index": 4, "lag_cells": 2, "sign": -1}}
        trace = self.trace(**truth)

        def stage(lag, sign, pub=True):
            return [[hyp(pub and i == 4, lag if i == 4 else None, sign if i == 4 else None) for i in range(16)]]

        self.assertTrue(ds.score(trace, stage(2, -1))["detected"])
        self.assertTrue(ds.score(trace, stage(3, -1))["detected"])
        self.assertFalse(ds.score(trace, stage(4, -1))["detected"])
        self.assertFalse(ds.score(trace, stage(2, 1))["detected"])
        missed = ds.score(trace, stage(2, -1, pub=False))
        self.assertFalse(missed["detected"])
        # a finding on the planted index is never a false finding
        self.assertFalse(ds.score(trace, stage(2, -1))["false"])

    def test_level_effect_is_detected_as_positive_lag_zero(self):
        trace = self.trace(null_hypotheses=[i for i in range(16) if i != 6],
                           level_effect={"index": 6, "shape": "threshold"})
        good = [[hyp(i == 6, 1 if i == 6 else None, 1 if i == 6 else None) for i in range(16)]]
        bad = [[hyp(i == 6, 0 if i == 6 else None, -1 if i == 6 else None) for i in range(16)]]
        self.assertTrue(ds.score(trace, good)["detected"])
        self.assertFalse(ds.score(trace, bad)["detected"])

    def test_inactive_family_stays_in_the_denominator(self):
        trace = self.trace()
        trace["activation"] = {"violated": False}
        scored = ds.score(trace, [])
        self.assertEqual((scored["false"], scored["evaluated"], scored["activated"]), (False, False, False))
        trace["activation"] = {"violated": True}
        scored = ds.score(trace, [[hyp(False) for _ in range(16)]])
        self.assertEqual((scored["evaluated"], scored["activated"]), (True, True))

    def test_report_with_only_unavailable_hypotheses_is_not_evaluated(self):
        dead = [{"pub": False, "pub_nomat": False, "lag": None, "sign": None, "avail": False}] * 16
        self.assertFalse(ds.score(self.trace(), [dead])["evaluated"])


class StageSelection(unittest.TestCase):
    def make_stage(self, strong_lag=2, n=60):
        rng = np.random.default_rng(5)
        outcome = rng.normal(size=n)
        resources = [rng.normal(size=n).tolist() for _ in range(16)]
        # outcome[t + lag] follows resource[t]: positive lag, no wrap-around
        lead = outcome[strong_lag:].tolist() + rng.normal(size=strong_lag).tolist()
        resources[0] = [v + 0.05 * w for v, w in zip(lead, rng.normal(size=n))]
        return {"resources": resources, "outcome": outcome.tolist(), "target": [100.0] * n}

    def test_strong_lagged_pair_is_published_with_lag_and_sign(self):
        stage = self.make_stage()
        rows = ds.stage_results("levels", stage, 4, "unit/1", 1)
        self.assertEqual(len(rows), 16)
        self.assertTrue(rows[0]["pub"] and rows[0]["pub_nomat"] and rows[0]["avail"])
        self.assertEqual((rows[0]["lag"], rows[0]["sign"]), (2, 1))
        self.assertFalse(any(r["pub"] for r in rows[1:]))
        self.assertEqual(rows, ds.stage_results("levels", stage, 4, "unit/1", 1))

    def test_declared_family_count_divides_alpha(self):
        stage = self.make_stage()
        rows = ds.stage_results("levels", stage, 4, "unit/1", 4)
        self.assertFalse(rows[0]["pub"] or rows[0]["pub_nomat"])
        self.assertTrue(rows[0]["avail"])

    def test_gap_breaks_the_series_for_every_variant(self):
        stage = self.make_stage(n=100)
        for row in [*stage["resources"], stage["outcome"], stage["target"]]:
            row[20] = None
        for variant in ds.VARIANTS:
            rows = ds.stage_results(variant, stage, 4, "unit/gap", 1)
            self.assertEqual(len(rows), 16, variant)
            self.assertTrue(all(r["avail"] for r in rows), variant)

    def test_run_shorter_than_thirty_cells_is_unavailable(self):
        stage = self.make_stage(n=100)
        for row in [*stage["resources"], stage["outcome"], stage["target"]]:
            for t in range(25, 100, 25):
                row[t] = None
        rows = ds.stage_results("levels", stage, 4, "unit/short", 1)
        self.assertFalse(any(r["avail"] or r["pub"] for r in rows))


class Report(unittest.TestCase):
    def test_run_is_deterministic_and_counts_reports(self):
        first = ds.run("first_difference", "N01-iid", 2, 10000)
        self.assertEqual(first, ds.run("first_difference", "N01-iid", 2, 10000))
        self.assertEqual(first["reports"], 2)
        self.assertIn("false_reports", first)
        self.assertLessEqual(first["false_reports"], 2)

    def test_run_rejects_unknown_variant(self):
        with self.assertRaises(ValueError):
            ds.run("levelz", "N01-iid", 1, 10000)

    def test_process_entry_pairs_all_variants_on_one_trace(self):
        record = ds.process_entry(["N10-stages-2", 10001])
        self.assertEqual((record["scenario_id"], record["seed"]), ("N10-stages-2", 10001))
        self.assertEqual(tuple(record["variants"]), ds.VARIANTS)
        for value in record["variants"].values():
            self.assertEqual(set(value), {"false", "false_nomat", "detected", "detected_nomat", "evaluated", "activated"})
            self.assertIsNone(value["detected"])
        json.dumps(record, allow_nan=False)


class Sharding(unittest.TestCase):
    def manifest(self, count=40):
        return ds.build_manifest(["N01-iid", "N02-ar08", "P02-lin-lag2"], 10000, count, "unit")

    def test_manifest_is_fixed_before_running(self):
        m = self.manifest(5)
        self.assertEqual(len(m["entries"]), 15)
        self.assertEqual(m["entries"][0], ["N01-iid", 10000])
        self.assertEqual(m["entries"][-1], ["P02-lin-lag2", 10004])
        self.assertEqual(m, self.manifest(5))
        self.assertEqual(len(m["entries_sha256"]), 64)
        self.assertEqual(m["variants"], list(ds.VARIANTS))

    def test_manifest_stays_in_the_development_seed_space(self):
        for seed0, count in ((9999, 5), (99990, 20), (0, 5), (100000, 5)):
            with self.assertRaises(ValueError):
                ds.build_manifest(["N01-iid"], seed0, count, "unit")
        with self.assertRaises(KeyError):
            ds.build_manifest(["nope"], 10000, 5, "unit")

    def test_shard_split_depends_only_on_the_manifest(self):
        m = self.manifest()
        for count in (1, 8, 16):
            parts = [ds.shard_entries(m, i, count) for i in range(count)]
            joined = [tuple(e) for part in parts for e in part]
            self.assertEqual(sorted(joined), sorted(tuple(e) for e in m["entries"]))
            self.assertEqual(len(joined), len(set(joined)))
        self.assertEqual(ds.shard_entries(m, 3, 8), m["entries"][3::8])

    def test_one_eight_and_sixteen_shards_merge_to_identical_bytes(self):
        m = self.manifest()
        digests = set()
        with tempfile.TemporaryDirectory() as tmp:
            for count in (1, 8, 16):
                paths = []
                for i in range(count):
                    path = Path(tmp) / f"s{count}-{i}.jsonl"
                    ds.run_shard(m, i, count, path, process=fake_process)
                    paths.append(path)
                out = Path(tmp) / f"merged-{count}.jsonl"
                result = ds.merge(m, paths, out)
                self.assertEqual(result["status"], "COMPLETE")
                digests.add(out.read_bytes())
        self.assertEqual(len(digests), 1)

    def test_merge_reports_missing_and_duplicate_reports_as_incomplete(self):
        m = self.manifest(4)
        with tempfile.TemporaryDirectory() as tmp:
            a, b = Path(tmp) / "a.jsonl", Path(tmp) / "b.jsonl"
            ds.run_shard(m, 0, 2, a, process=fake_process)
            ds.run_shard(m, 1, 2, b, process=fake_process)
            out = Path(tmp) / "out.jsonl"
            missing = ds.merge(m, [a], out)
            self.assertEqual(missing["status"], "INCOMPLETE")
            self.assertEqual(len(missing["missing"]), 6)
            self.assertFalse(out.exists())
            duplicate = ds.merge(m, [a, b, a], out)
            self.assertEqual(duplicate["status"], "INCOMPLETE")
            self.assertTrue(duplicate["duplicate"])
            self.assertFalse(out.exists())

    def test_merge_rejects_a_record_outside_the_manifest(self):
        m = self.manifest(2)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.jsonl"
            ds.run_shard(m, 0, 1, path, process=fake_process)
            with path.open("a", encoding="utf-8", newline="\n") as stream:
                stream.write(json.dumps(fake_process(["N01-iid", 77777]), sort_keys=True, separators=(",", ":")) + "\n")
            result = ds.merge(m, [path], Path(tmp) / "out.jsonl")
            self.assertEqual(result["status"], "INCOMPLETE")
            self.assertTrue(result["unexpected"])

    def test_shard_resumes_without_recomputing_finished_reports(self):
        m = self.manifest(3)
        calls = []

        def counting(entry):
            calls.append(tuple(entry))
            return fake_process(entry)

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "a.jsonl"
            ds.run_shard(m, 0, 1, path, process=counting)
            first = path.read_bytes()
            self.assertEqual(len(calls), 9)
            ds.run_shard(m, 0, 1, path, process=counting)
            self.assertEqual(len(calls), 9)
            self.assertEqual(path.read_bytes(), first)


class Summary(unittest.TestCase):
    def records(self):
        m = ds.build_manifest(["N01-iid"], 10000, 20, "unit")
        return [fake_process(e) for e in m["entries"]]

    def test_summary_counts_and_wilson_interval(self):
        summary = ds.summarize(self.records())
        row = summary["N01-iid"]["levels"]
        self.assertEqual(row["reports"], 20)
        self.assertEqual(row["false"], sum(1 for r in self.records() if r["variants"]["levels"]["false"]))
        lo, hi = row["false_wilson"]
        self.assertTrue(0.0 <= lo <= row["false"] / 20 <= hi <= 1.0)

    def test_markdown_table_lists_every_scenario_and_variant(self):
        text = ds.summary_markdown(ds.summarize(self.records()))
        self.assertIn("N01-iid", text)
        for variant in ds.VARIANTS:
            self.assertIn(variant, text)


if __name__ == "__main__":
    unittest.main()

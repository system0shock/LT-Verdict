"""Hand-derived checks of the independent oracle, not product acceptance."""

import unittest
import json
from decimal import Decimal, localcontext
from fractions import Fraction as F
from pathlib import Path
from tempfile import TemporaryDirectory

import stats_validation as oracle


class OracleTest(unittest.TestCase):
    def test_type7_endpoints_ties_and_empty(self):
        for probability, expected in [(F(0), 0), (F(1, 20), F(3, 20)),
                                      (F(1, 2), F(3, 2)), (F(19, 20), F(57, 20)), (F(1), 3)]:
            self.assertEqual(oracle.quantile([3, 0, 2, 1], probability), expected)
        self.assertEqual(oracle.quantile([5], F(1)), 5)
        self.assertIsNone(oracle.quantile([], F(1, 2)))
        with self.assertRaises(ValueError):
            oracle.quantile([1], F(2))

    def test_statistics_literals_and_gap_time(self):
        stats = oracle.resource_statistics([0, 1, 2, 3], 10000)
        expected = {"mean": F(3, 2), "median": F(3, 2), "q05": F(3, 20),
                    "q95": F(57, 20), "iqr": F(3, 2), "mad": F(1),
                    "sample_variance": F(5, 3), "slope_per_second": F(1, 10),
                    "split_half_shift": F(2), "observed_cells": 4, "longest_gap_cells": 0}
        for field, value in expected.items():
            self.assertEqual(stats[field], value, field)
        gap = oracle.resource_statistics([1, None, 3, None], 10000)
        self.assertEqual(gap["slope_per_second"], F(1, 10))
        self.assertEqual(gap["split_half_shift"], 2)
        self.assertEqual(gap["observed_cells"], 2)
        self.assertEqual(gap["longest_gap_cells"], 1)
        self.assertIsNone(oracle.resource_statistics([None, 5, None], 1000)["sample_variance"])
        self.assertIsNone(oracle.resource_statistics([None, None], 1000)["mean"])
        self.assertEqual(oracle.resource_statistics([5] * 4, 1000)["sample_standard_deviation"], 0)

    def test_ranks_and_correlation_literals(self):
        self.assertEqual(oracle.ranks([4, 1, 4]), [F(5, 2), F(1), F(5, 2)])
        result = oracle.rank_correlation([1, 2, 3, 4, 5] * 6, [1, 3, 2, 5, 4] * 6)
        self.assertEqual(result["profile"], [{"lag_cells": 0, "coefficient": Decimal("0.8")}])
        self.assertEqual(oracle.rank_correlation([1, 2, 3], [3, 2, 1])["profile"][0]["coefficient"], -1)
        self.assertEqual(oracle.rank_correlation([1, 1, 1], [1, 2, 3])["status"], "NO_RESIDUAL_VARIATION")

    def test_partial_uses_independent_normal_equations(self):
        # r_xy=.8, r_xz=.4, r_yz=.2 => partial=3/sqrt(14).
        result = oracle.rank_correlation([1, 2, 3, 4], [1, 2, 4, 3], [[1, 4, 2, 3]])
        with localcontext() as ctx:
            ctx.prec = 50
            expected = Decimal(3) / Decimal(14).sqrt()
        self.assertLess(abs(result["profile"][0]["coefficient"] - expected), Decimal("1e-48"))
        singular = oracle.rank_correlation([1, 2, 3], [1, 3, 2], [[1, 1, 1]])
        self.assertEqual(singular, {"status": "SINGULAR_CONTROLS", "profile": []})
        zero = oracle.rank_correlation([1, 2, 3], [1, 2, 3], [[1, 2, 3]])
        self.assertEqual(zero["status"], "NO_RESIDUAL_VARIATION")

    def test_lag_ranks_once_before_shift(self):
        # Fixed anchors t=1,2,3: X ranks [2,4,3]. At lag0 Y=[1,3,4],
        # centered dot=2, sums of squares=2 and 14/3 => sqrt(3/7).
        # Reranking would incorrectly give .5; variable overlap uses 5 points.
        result = oracle.rank_correlation([1, 2, 4, 3, 5], [2, 1, 3, 4, 5], max_lag=1)
        with localcontext() as ctx:
            ctx.prec = 50
            expected = (Decimal(3) / Decimal(7)).sqrt()
        self.assertEqual(result["profile"][2]["lag_cells"], 1)
        self.assertEqual(result["profile"][0]["coefficient"], Decimal("-.5"))
        self.assertLess(abs(result["profile"][1]["coefficient"] - expected), Decimal("1e-48"))
        self.assertEqual(result["profile"][2]["coefficient"], Decimal(".5"))

    def test_invalid_inputs_are_not_silently_truncated(self):
        for x, y, controls, lag in [([1, 2], [1], [], 0), ([1, 2], [1, 2], [[1]], 0),
                                    ([1, 2], [1, 2], [], -1), ([1, 2], [1, 2], [], 2)]:
            with self.assertRaises(ValueError):
                oracle.rank_correlation(x, y, controls, lag)
        with self.assertRaises(ValueError):
            oracle.resource_statistics([1], 0)


class FrozenCasesTest(unittest.TestCase):
    def test_context_cases_use_the_same_two_real_inputs_with_explicit_context(self):
        cases = oracle.context_cases()
        self.assertEqual(2, len(cases))
        a, b = cases
        self.assertEqual(a['input']['baseline'], b['input']['baseline'])
        self.assertEqual(a['input']['current'], b['input']['current'])
        self.assertIs(a['input']['conditions_confirmed'], False)
        self.assertIs(b['input']['conditions_confirmed'], True)
        self.assertEqual(a['expected']['numeric'], b['expected']['numeric'])

    def test_completion_cases_cover_remaining_branches_without_replacing_frozen_cases(self):
        prior = {c['id'] for c in oracle.correctness_cases() + oracle.supplemental_cases()}
        cases = oracle.completion_cases()
        self.assertFalse(prior.intersection(c['id'] for c in cases))
        indexed = {c['id']: c for c in cases}
        self.assertEqual(len(cases), len(indexed))
        self.assertEqual('LIMIT_EXCEEDED', indexed['V02_episode_limit']['expected']['exact']['/diagnostic_summary/status'])
        self.assertEqual(31, indexed['W04_empty_throughput_rps']['expected']['exact']['/correlation_pairs/evaluation/pair/paired_cells'])
        self.assertEqual(30, indexed['W04_empty_error_rate']['expected']['exact']['/correlation_pairs/evaluation/pair/paired_cells'])

    def test_supplemental_corpus_does_not_replace_first_batch(self):
        first = {c['id'] for c in oracle.correctness_cases()}
        extra = oracle.supplemental_cases()
        self.assertFalse(first.intersection(c['id'] for c in extra))
        by_id = {c['id']: c for c in extra}
        self.assertEqual(0, by_id['A03_abs_above_resource']['expected']['exact']
                         ['/anomaly_checks/anomaly/episodes_reported'])
        self.assertEqual(1, by_id['A03_abs_below_resource']['expected']['exact']
                         ['/anomaly_checks/anomaly/episodes_reported'])
        self.assertEqual('NO_VERDICT', by_id['V02_missing_and_violation']['expected']['exact']['/result/policy_verdict'])

    def test_literal_corpus_has_bound_inputs_and_independent_assertions(self):
        cases = oracle.correctness_cases()
        self.assertGreaterEqual(len(cases), 40)
        self.assertEqual(len(cases), len({case['id'] for case in cases}))
        self.assertEqual(next(c for c in cases if c['id'] == 'S01')['expected']['numeric']
                         ['/resource_summaries/evaluation/value/statistics/mean'], '1.5')
        for case in cases:
            if case['input']['operation'] == 'analysis':
                run = case['input']['run']
                self.assertEqual(run['resources']['load_input_sha256'],
                                 oracle._sha(run['load_jtl'].encode()))
                if 'diagnostics' in run:
                    self.assertEqual(run['diagnostics']['resource_snapshot_sha256'],
                                     oracle.snapshot_hash(run['resources']))

    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.cases = [{"id": "S01", "family": "S", "configuration": "literal",
                       "input": {"values": [0, 1, 2, 3]},
                       "expected": {"exact": {"/count": 4, "/status": "OK", "/missing": None},
                                    "numeric": {"/mean": "1.5"}}}]

    def actual(self, records):
        path = self.root / "actual.jsonl"
        path.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        return path

    def test_freeze_is_deterministic_and_refuses_overwrite(self):
        first = oracle.freeze_cases(self.root / "first", self.cases)
        second = oracle.freeze_cases(self.root / "second", self.cases)
        self.assertEqual(first, second)
        with self.assertRaises(FileExistsError):
            oracle.freeze_cases(self.root / "first", self.cases)
        self.assertEqual((self.root / "first/manifest.json").read_bytes(),
                         (self.root / "second/manifest.json").read_bytes())

    def test_mismatch_null_and_numeric_tolerance(self):
        digest = oracle.freeze_cases(self.root / "frozen", self.cases)
        good = {"id": "S01", "output": {"count": 4, "status": "OK", "missing": None, "mean": "1.500000001"}}
        self.assertEqual(oracle.check_cases(self.root / "frozen", digest, self.actual([good]))["status"], "MATCH")
        for field, bad in [("mean", "1.5001"), ("mean", "NaN"), ("missing", 0), ("count", True)]:
            wrong = {"id": "S01", "output": good["output"] | {field: bad}}
            with self.subTest(field=field, bad=bad):
                self.assertEqual(oracle.check_cases(self.root / "frozen", digest, self.actual([wrong]))["status"], "FAIL")

    def test_missing_duplicate_unknown_and_error_are_incomplete(self):
        digest = oracle.freeze_cases(self.root / "frozen", self.cases)
        error = {"id": "S01", "error": "timeout"}
        for records in [[], [error], [error, error], [{"id": "unexpected", "output": {}}]]:
            result = oracle.check_cases(self.root / "frozen", digest, self.actual(records))
            self.assertEqual(result["status"], "INCOMPLETE")
            self.assertEqual(result["planned"], 1)

    def test_corruption_of_input_expected_or_manifest_is_not_accepted(self):
        for target in ["inputs/S01.json", "expected/S01.json", "manifest.json"]:
            frozen = self.root / target.replace("/", "_")
            digest = oracle.freeze_cases(frozen, self.cases)
            (frozen / target).write_text("{}", encoding="utf-8")
            self.assertEqual(oracle.check_cases(frozen, digest, self.actual([]))["status"], "INCOMPLETE")

    def test_bad_ids_empty_and_vacuous_expectations_fail_before_writes(self):
        for cases in [[], self.cases * 2, [self.cases[0] | {"id": "../escape"}],
                      [self.cases[0] | {"expected": {"exact": {}, "numeric": {}}}]]:
            with self.assertRaises(ValueError):
                oracle.freeze_cases(self.root / "rejected", cases)
            self.assertFalse((self.root / "rejected").exists())

    def test_nonfinite_nested_actual_json_is_incomplete(self):
        digest = oracle.freeze_cases(self.root / "frozen", self.cases)
        raw = '{"id":"S01","output":{"count":4,"status":"OK","missing":null,"mean":"1.5","extra":[1e999]}}\n'
        path = self.root / "actual.jsonl"
        path.write_text(raw, encoding="utf-8")
        self.assertEqual(oracle.check_cases(self.root / "frozen", digest, path)["status"], "INCOMPLETE")

    def test_numeric_expectations_must_be_strings_before_writes(self):
        for value in [1, 1.0]:
            with self.subTest(value=value):
                cases = [self.cases[0] | {"expected": {"exact": {}, "numeric": {"/mean": value}}}]
                with self.assertRaises(ValueError):
                    oracle.freeze_cases(self.root / "rejected", cases)
                self.assertFalse((self.root / "rejected").exists())

    def test_pointers_validate_escapes_and_array_indexes(self):
        malformed = [self.cases[0] | {"expected": {"exact": {"/items/~2": 1}, "numeric": {}}}]
        with self.assertRaises(ValueError):
            oracle.freeze_cases(self.root / "malformed", malformed)
        cases = [self.cases[0] | {"expected": {"exact": {"/items/-1": 2, "/items/01": 2, "/map/-1": 7}, "numeric": {}}}]
        digest = oracle.freeze_cases(self.root / "frozen", cases)
        record = {"id": "S01", "output": {"items": [1, 2], "map": {"-1": 7}}}
        result = oracle.check_cases(self.root / "frozen", digest, self.actual([record]))
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual({m["pointer"] for m in result["cases"][0]["mismatches"]}, {"/items/-1", "/items/01"})


if __name__ == "__main__":
    unittest.main()

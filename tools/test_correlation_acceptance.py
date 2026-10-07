import json
import re
from pathlib import Path
import sys
import tempfile
import unittest

try:
    import numpy as np  # noqa: F401
except ModuleNotFoundError as error:
    if error.name != "numpy":
        raise
    raise unittest.SkipTest("numpy is not installed")

sys.path.insert(0, str(Path(__file__).parent))
import correlation_acceptance as ca
import correlation_oracle as oracle
import stats_validation as wire


def case(scenario_id, seed, *, activated=None, stages=1, truth=None):
    return {"id": f"{scenario_id}-s{seed}", "scenario_id": scenario_id, "seed": seed, "path": None,
            "sha256": None, "activated": activated, "stages": stages, "hypotheses": 16,
            "truth": truth or {"null_hypotheses": list(range(16)), "planted": None, "level_effect": None,
                               "common_factor_indices": []}}


def corpus_of(*cases):
    return {"schema_version": "c5-corpus.v1", "cases": list(cases)}


def selection(index, window="stage-1", status="NOT_SELECTED", selected=False, stages=1):
    return {"pair_id": f"h{index:02d}", "window_id": window, "status": status, "selected": selected,
            "family_hypotheses": 16, "family_count": stages, "reasons": [] if selected else ["HOLM_NOT_REJECTED"]}


def pair(index, window="stage-1", lag_ms=0, rho="0.5"):
    return {"pair_id": f"h{index:02d}", "window_id": window, "best_lag_ms": lag_ms, "best_lag_rho": rho}


def record(c, selected=(), findings=None, unavailable=(), lag_ms=0, rho="0.5"):
    stages = c["stages"]
    windows = [f"stage-{n}" for n in range(1, stages + 1)]
    selections, pairs = [], []
    for window in windows:
        for i in range(16):
            if i in unavailable:
                selections.append(selection(i, window, "UNAVAILABLE", stages=stages))
            else:
                chosen = i in selected
                selections.append(selection(i, window, "SELECTED" if chosen else "NOT_SELECTED", chosen, stages))
            pairs.append(pair(i, window, lag_ms, rho))
    if findings is None:
        findings = [{"pair_id": f"h{i:02d}", "window_id": w} for w in windows for i in selected]
    return {"id": c["id"], "seed_material": "x/y", "selections": selections, "pairs": pairs, "findings": findings}


def actual_of(*records):
    return {"records": {r["id"]: r for r in records}, "duplicates": []}


def row_of(report, scenario_id):
    return next(row for row in report["scenarios"] if row["scenario_id"] == scenario_id)


class Scoring(unittest.TestCase):
    def thousand(self, scenario_id, false):
        cases = [case(scenario_id, 100000 + k) for k in range(1000)]
        records = [record(c, selected=(3,) if k < false else ()) for k, c in enumerate(cases)]
        return corpus_of(*cases), actual_of(*records)

    def test_score_marks_missing_report_as_incomplete_not_pass(self):
        cases = [case("N01-iid", 100000 + k) for k in range(3)]
        report = ca.score(corpus_of(*cases), actual_of(*[record(c) for c in cases[:2]]))
        row = row_of(report, "N01-iid")
        self.assertEqual((row["planned"], row["completed"], row["status"]), (3, 2, "INCOMPLETE"))

    def test_runner_error_and_duplicate_report_are_incomplete(self):
        cases = [case("N01-iid", 1 + k) for k in range(3)]
        actual = actual_of(record(cases[0]), {"id": cases[1]["id"], "error": "boom"}, record(cases[2]))
        self.assertEqual(row_of(ca.score(corpus_of(*cases), actual), "N01-iid")["status"], "INCOMPLETE")
        actual = actual_of(*[record(c) for c in cases])
        actual["duplicates"] = [cases[0]["id"]]
        self.assertEqual(row_of(ca.score(corpus_of(*cases), actual), "N01-iid")["status"], "INCOMPLETE")

    def test_score_uses_wilson_upper_bound_at_54_of_1000_pass_and_55_fail(self):
        self.assertLessEqual(oracle.wilson_upper(54, 1000), 0.07)
        self.assertGreater(oracle.wilson_upper(55, 1000), 0.07)
        row = row_of(ca.score(*self.thousand("N01-iid", 54)), "N01-iid")
        self.assertEqual((row["false"], row["status"]), (54, "PASS"))
        row = row_of(ca.score(*self.thousand("N01-iid", 55)), "N01-iid")
        self.assertEqual((row["false"], row["status"]), (55, "USEFULNESS FAIL"))

    def test_score_rejects_duplicate_pair_window_key(self):
        c = case("N01-iid", 5)
        r = record(c)
        r["selections"].append(dict(r["selections"][0]))
        self.assertEqual(row_of(ca.score(corpus_of(c), actual_of(r)), "N01-iid")["status"], "CORRECTNESS FAIL")

    def test_score_joins_candidate_finding_with_selection_by_pair_and_window(self):
        cases = [case("N10-stages-2", 5, stages=2)]
        good = record(cases[0], selected=(4,))
        row = row_of(ca.score(corpus_of(*cases), actual_of(good)), "N10-stages-2")
        self.assertEqual((row["false"], row["status"], row["completed"]), (1, "UNDERSIZED", 1))
        wrong_window = record(cases[0], selected=(4,), findings=[{"pair_id": "h04", "window_id": "stage-1"}])
        wrong_window["findings"] = [{"pair_id": "h04", "window_id": "stage-9"}]
        row = row_of(ca.score(corpus_of(*cases), actual_of(wrong_window)), "N10-stages-2")
        self.assertEqual(row["status"], "CORRECTNESS FAIL")

    def test_score_rejects_a_finding_without_a_matching_selected_evidence(self):
        c = case("N01-iid", 5)
        r = record(c, findings=[{"pair_id": "h02", "window_id": "stage-1"}])
        row = row_of(ca.score(corpus_of(c), actual_of(r)), "N01-iid")
        self.assertEqual((row["false"], row["status"]), (0, "CORRECTNESS FAIL"))

    def test_selected_without_candidate_finding_is_a_correctness_failure(self):
        c = case("N01-iid", 5)
        r = record(c, selected=(2,), findings=[])
        self.assertEqual(row_of(ca.score(corpus_of(c), actual_of(r)), "N01-iid")["status"], "CORRECTNESS FAIL")

    def test_score_counts_unavailable_as_missed_detection_on_positive_scenario(self):
        truth = {"null_hypotheses": [i for i in range(16) if i != 4], "planted": {"index": 4, "lag_cells": 2, "sign": 1},
                 "level_effect": None, "common_factor_indices": []}
        cases = [case("P02-lin-lag2", k, truth=truth) for k in range(4)]
        records = [record(cases[0], selected=(4,), lag_ms=2000, rho="0.6"),
                   record(cases[1], selected=(4,), lag_ms=3000, rho="0.6"),
                   record(cases[2], selected=(4,), lag_ms=2000, rho="-0.6"),
                   record(cases[3], unavailable=(4,))]
        row = row_of(ca.score(corpus_of(*cases), actual_of(*records)), "P02-lin-lag2")
        self.assertEqual((row["detected"], row["false"], row["status"]), (2, 0, "UNDERSIZED"))
        many = [case("P02-lin-lag2", k, truth=truth) for k in range(1000)]
        found = [record(c, selected=(4,), lag_ms=2000) if k < 899 else record(c, unavailable=(4,)) for k, c in enumerate(many)]
        self.assertEqual(row_of(ca.score(corpus_of(*many), actual_of(*found)), "P02-lin-lag2")["status"], "USEFULNESS FAIL")
        found = [record(c, selected=(4,), lag_ms=2000) if k < 900 else record(c, unavailable=(4,)) for k, c in enumerate(many)]
        self.assertEqual(row_of(ca.score(corpus_of(*many), actual_of(*found)), "P02-lin-lag2")["status"], "PASS")

    def test_family_size_must_keep_unavailable_hypotheses(self):
        c = case("N01-iid", 5)
        r = record(c)
        for item in r["selections"]:
            item["family_hypotheses"] = 15
        self.assertEqual(row_of(ca.score(corpus_of(c), actual_of(r)), "N01-iid")["status"], "CORRECTNESS FAIL")

    def test_missing_selection_for_a_pair_makes_the_report_incomplete(self):
        c = case("N01-iid", 5)
        r = record(c)
        r["selections"].pop()
        self.assertEqual(row_of(ca.score(corpus_of(c), actual_of(r)), "N01-iid")["status"], "INCOMPLETE")

    def test_not_activated_report_stays_in_denominator_and_low_activation_is_incomplete(self):
        for share, status in ((200, "INCOMPLETE"), (500, "PASS")):
            cases = [case("N11-activation", k, activated=k < share) for k in range(1000)]
            records = [record(c) for c in cases if c["activated"]]
            row = row_of(ca.score(corpus_of(*cases), actual_of(*records)), "N11-activation")
            self.assertEqual((row["planned"], row["completed"], row["activated"], row["status"], row["false"]),
                             (1000, 1000, share, status, 0))

    def test_oracle_mismatch_is_a_correctness_failure(self):
        c = case("N01-iid", 5)
        report = ca.score(corpus_of(c), actual_of(record(c)), {c["id"]: ["p_value_b10"]})
        self.assertEqual(row_of(report, "N01-iid")["status"], "CORRECTNESS FAIL")

    def test_ungated_scenarios_get_no_acceptance_status(self):
        c = case("P05-lin-weak-a", 5, truth={"null_hypotheses": list(range(16)), "planted": None,
                                              "level_effect": None, "common_factor_indices": []})
        self.assertEqual(row_of(ca.score(corpus_of(c), actual_of(record(c))), "P05-lin-weak-a")["status"], "NOT_GATED")


class Inputs(unittest.TestCase):
    def test_inputs_round_to_four_decimals_and_avoid_negative_zero(self):
        self.assertEqual(str(ca.number(-0.00004)), "0.0")
        self.assertEqual(ca.number(1.23456), 1.2346)
        operation, _ = ca.build_input(ca.cs.generate("N05-drift-strong-ar08", 10000))
        text = ca.canonical_text(operation)
        self.assertNotIn("-0,", text)
        self.assertNotIn("-0]", text)
        series = canonical_values = ca.canonical_text([s["values"] for s in operation["run"]["resources"]["series"]])
        self.assertIsNone(re.search(r"\d[eE][+-]?\d", canonical_values))
        values = [v for s in operation["run"]["resources"]["series"] for v in s["values"]]
        self.assertTrue(all(v == round(v, 4) for v in values))

    def test_input_shape_hash_and_integer_load_range(self):
        trace = ca.cs.generate("N10-stages-2", 10000)
        operation, meta = ca.build_input(trace)
        run = operation["run"]
        snapshot = run["resources"]
        self.assertEqual(snapshot["step_ms"], 1000)
        self.assertEqual(snapshot["point_count"], 240)
        self.assertEqual([w["id"] for w in snapshot["windows"]], ["stage-1", "stage-2"])
        self.assertEqual(run["diagnostics"]["resource_snapshot_sha256"], wire.snapshot_hash(snapshot))
        pairs = run["diagnostics"]["pairs"]
        self.assertEqual(len(pairs), 16)
        self.assertEqual(pairs[0]["window_ids"], ["stage-1", "stage-2"])
        self.assertEqual(pairs[0]["controls"], [{"meaning": "target_rps", "series_id": "target"}])
        elapsed = [int(line.split(",")[1]) for line in run["load_jtl"].splitlines()[1:]]
        self.assertEqual(len(elapsed), 240 * ca.REQUESTS_PER_CELL)
        self.assertTrue(100 <= min(elapsed) and max(elapsed) <= 1900)
        self.assertEqual(meta["tie_fraction"] >= 0, True)

    def test_gap_cells_have_no_requests_and_null_resources(self):
        trace = ca.cs.generate("N09-ar08-gaps", 10000)
        operation, _ = ca.build_input(trace)
        run = operation["run"]
        nulls = [i for i, v in enumerate(run["resources"]["series"][0]["values"]) if v is None]
        self.assertTrue(nulls)
        stamps = {(int(line.split(",")[0]) - run["resources"]["start_epoch_ms"]) // 1000 for line in run["load_jtl"].splitlines()[1:]}
        self.assertTrue(all(i not in stamps for i in nulls))
        self.assertEqual(len(stamps), 120 - len(nulls))

    def test_windows_stay_inside_the_run_when_edge_cells_are_gaps(self):
        for seed in range(10000, 10200):
            run = ca.build_input(ca.cs.generate("N09-ar08-gaps", seed))[0]["run"]
            stamps = [int(line.split(",")[0]) for line in run["load_jtl"].splitlines()[1:]]
            start = run["resources"]["start_epoch_ms"]
            window = run["resources"]["windows"][0]
            self.assertGreaterEqual(window["from_epoch_ms"], min(stamps))
            self.assertLessEqual(window["to_epoch_ms"], max(stamps) + 100)
        edge = ca.cs.generate("N09-ar08-gaps", 10000)
        edge["stages"][0]["outcome"][0] = None
        edge["stages"][0]["outcome"][-1] = None
        window = ca.build_input(edge)[0]["run"]["resources"]["windows"][0]
        self.assertEqual((window["from_epoch_ms"] - start) // 1000 >= 1, True)

    def test_not_activated_n11_trace_has_no_input(self):
        for seed in range(100):
            trace = ca.cs.generate("N11-activation", seed)
            operation, meta = ca.build_input(trace)
            self.assertEqual(operation is None, not trace["activation"]["violated"])
            self.assertEqual(meta["activated"], trace["activation"]["violated"])


FAKE_SHARD = (
    "import sys, json, hashlib\n"
    "corpus, index, count, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]\n"
    "manifest = json.load(open(corpus + '/manifest.json', encoding='utf-8'))\n"
    "with open(out, 'a', encoding='utf-8', newline='\\n') as f:\n"
    "    for i, c in enumerate(manifest['cases']):\n"
    "        if i % count == index and c['path']:\n"
    "            digest = hashlib.sha256(open(corpus + '/' + c['path'], 'rb').read()).hexdigest()\n"
    "            f.write(json.dumps({'id': c['id'], 'digest': digest}, sort_keys=True, separators=(',', ':')) + '\\n')\n"
)


class Sharding(unittest.TestCase):
    def freeze_small(self, directory):
        return ca.freeze(["N01-iid"], 10000, 20, directory, workers=1)

    def test_shard_split_depends_only_on_the_manifest_not_on_results(self):
        corpus = corpus_of(*[case("N01-iid", k) for k in range(37)])
        for shards in (1, 8, 16):
            parts = ca.split(corpus, shards)
            self.assertEqual(len(parts), shards)
            self.assertEqual(sorted(i for part in parts for i in part), sorted(c["id"] for c in corpus["cases"]))
            self.assertEqual(parts, ca.split(corpus, shards))
        self.assertEqual(ca.split(corpus, 8)[3][:2], [corpus["cases"][3]["id"], corpus["cases"][11]["id"]])

    def test_sharded_run_equals_single_process_run_byte_for_byte(self):
        with tempfile.TemporaryDirectory() as tmp:
            corpus = self.freeze_small(Path(tmp) / "corpus")
            digests = []
            for shards in (1, 8, 16):
                files = ca.run_sharded(Path(tmp) / "corpus", shards, Path(tmp) / f"out{shards}",
                                       [sys.executable, "-c", FAKE_SHARD, "{corpus}", "{shard}", "{shards}", "{out}"])
                actual = ca.merge(files)
                self.assertEqual(actual["duplicates"], [])
                out = Path(tmp) / f"actual{shards}.jsonl"
                digests.append(ca.write_actual(corpus, actual, out))
                self.assertEqual(len(out.read_bytes().splitlines()), 20)
            self.assertEqual(len(set(digests)), 1)

    def test_merge_rejects_missing_and_duplicate_reports_as_incomplete(self):
        with tempfile.TemporaryDirectory() as tmp:
            corpus = self.freeze_small(Path(tmp) / "corpus")
            ids = [c["id"] for c in corpus["cases"]]
            first, second = Path(tmp) / "a.jsonl", Path(tmp) / "b.jsonl"
            first.write_text("".join(json.dumps({"id": i}) + "\n" for i in ids[:10]), encoding="utf-8")
            second.write_text("".join(json.dumps({"id": i}) + "\n" for i in ids[9:19]), encoding="utf-8")
            actual = ca.merge([first, second])
            self.assertEqual(actual["duplicates"], [ids[9]])
            report = ca.score(corpus, actual)
            self.assertEqual(row_of(report, "N01-iid")["status"], "INCOMPLETE")
            with self.assertRaises(ValueError):
                ca.write_actual(corpus, actual, Path(tmp) / "never.jsonl")

    def test_freeze_is_deterministic_and_refuses_reserved_seeds(self):
        with tempfile.TemporaryDirectory() as tmp:
            first = ca.freeze(["N01-iid"], 10000, 3, Path(tmp) / "a", workers=1)
            second = ca.freeze(["N01-iid"], 10000, 3, Path(tmp) / "b", workers=1)
            self.assertEqual(first["entries_sha256"], second["entries_sha256"])
            self.assertEqual([c["sha256"] for c in first["cases"]], [c["sha256"] for c in second["cases"]])
            with self.assertRaises(ValueError):
                ca.freeze(["N01-iid"], 100000, 3, Path(tmp) / "c", workers=1)
            with self.assertRaises(ValueError):
                ca.freeze(["N01-iid"], 1000, 3, Path(tmp) / "d", workers=1)


if __name__ == "__main__":
    unittest.main()

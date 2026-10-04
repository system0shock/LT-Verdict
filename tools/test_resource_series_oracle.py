"""Checks for the independent resource-series oracle and committed vectors."""

import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import resource_series_oracle as oracle  # noqa: E402
import resource_series_vectors as vectors  # noqa: E402


FIXTURES = Path(__file__).parent.parent / "fixtures" / "resource-series"
NAMES = {
    "source-step", "step-60s-all", "subrange", "paging-first", "paging-next",
    "partial-last-cell", "step-larger-than-grid", "empty-series",
    "catalog-first-page", "catalog-after",
}


def _read(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def _cases():
    return {case["name"]: case for case in json.loads(_read("cases.json"))["cases"]}


class ResourceSeriesOracleTest(unittest.TestCase):
    def test_mean_of_one_one_two_is_rounded_at_34_digits(self):
        self.assertEqual(
            (Decimal("1.333333333333333333333333333333333"), 3),
            oracle.reduce_cell([Decimal(1), Decimal(1), Decimal(2)], "mean"),
        )

    def test_max_min_and_empty_cell(self):
        values = [Decimal("0.5"), None, Decimal("-2"), Decimal("3")]
        self.assertEqual((Decimal("3"), 3), oracle.reduce_cell(values, "max"))
        self.assertEqual((Decimal("-2"), 3), oracle.reduce_cell(values, "min"))
        self.assertEqual((None, 0), oracle.reduce_cell([None, None], "mean"))

    def test_double_conversion_is_correctly_rounded(self):
        self.assertEqual(1 / 3, oracle.to_double(Decimal("0.333333333333333333333333333333333")))
        self.assertEqual(0.0, oracle.to_double(Decimal("-0.0")))

    def test_committed_vectors_equal_the_regenerated_ones(self):
        generated = vectors.generate()
        self.assertEqual(_read("snapshot-small.json"), generated["snapshot-small.json"])
        self.assertEqual(_read("cases.json"), generated["cases.json"])

    def test_vectors_cover_the_ten_cases(self):
        self.assertEqual(NAMES, set(_cases()))
        self.assertEqual(10, len(json.loads(_read("cases.json"))["cases"]))

    def test_observed_presence_tracks_ratio(self):
        cases = _cases()
        self.assertTrue(all("observed" not in item for item in cases["source-step"]["expected"]["series"]))
        self.assertTrue(all("observed" in item for item in cases["step-60s-all"]["expected"]["series"]))

    def test_step_larger_than_grid_is_one_full_source_cell_group(self):
        grid = _cases()["step-larger-than-grid"]["expected"]["grid"]
        self.assertEqual((1, 50, 50), (grid["cell_count"], grid["source_cells_per_cell"], grid["last_cell_source_cells"]))

    def test_partial_last_cell_has_two_source_cells(self):
        self.assertEqual(2, _cases()["partial-last-cell"]["expected"]["grid"]["last_cell_source_cells"])

    def test_paging_next_starts_at_previous_cursor(self):
        cases = _cases()
        self.assertEqual(
            cases["paging-first"]["expected"]["next_from_ms"],
            cases["paging-next"]["expected"]["grid"]["first_cell_start_ms"],
        )

    def test_catalog_after_and_strict_missing_cursor(self):
        snapshot = oracle.read_snapshot(_read("snapshot-small.json"))
        self.assertEqual(["gap", "queue", "tiny"], [item["id"] for item in _cases()["catalog-after"]["expected"]["series"]])
        self.assertEqual("gap", oracle.catalog(snapshot, after="cq")["series"][0]["id"])

    def test_every_expected_hash_matches_snapshot(self):
        digest = oracle.snapshot_hash(oracle.read_snapshot(_read("snapshot-small.json")))
        self.assertTrue(all(case["expected"]["resource_snapshot_sha256"] == digest for case in _cases().values()))


if __name__ == "__main__":
    unittest.main()

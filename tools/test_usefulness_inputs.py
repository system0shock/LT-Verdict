import csv
from decimal import Decimal
from io import StringIO
import unittest

import usefulness_inputs as usefulness


def debug_config(family, **config):
    base = next(item for item in usefulness.configurations()
                if item["family"] == family and all(item["config"].get(k) == v for k, v in config.items()))
    return base | {"id": "DEBUG-" + family, "seed": 0}


class UsefulnessInputTests(unittest.TestCase):
    def test_inventory_declares_28000_acceptance_reports(self):
        configurations = usefulness.configurations()
        self.assertEqual(28_000, len(configurations))
        self.assertEqual(28_000, len({item["id"] for item in configurations}))
        self.assertEqual((1000, 1999), (min(item["seed"] for item in configurations),
                                        max(item["seed"] for item in configurations)))
        counts = {}
        for item in configurations:
            counts[item["family"]] = counts.get(item["family"], 0) + 1
        self.assertEqual({"N01": 4000, "N02": 4000, "N03": 4000, "P01": 1000,
                          "P02": 1000, "P03": 1000, "E01": 2000, "E02": 2000,
                          "E03": 2000, "P04": 1000, "P05": 1000, "T01": 1000,
                          "T02": 1000, "T03": 1000, "P06": 1000, "P07": 1000}, counts)

    def test_debug_generation_is_deterministic_and_streams_are_isolated(self):
        config = debug_config("N02", pairs=1, max_lag_cells=0)
        self.assertEqual(usefulness.generate(config), usefulness.generate(config))
        self.assertNotEqual(usefulness._stream("N02", 0, "x/0").gauss(0, 1),
                            usefulness._stream("N02", 0, "y").gauss(0, 1))
        other_seed = config | {"seed": 1}
        self.assertNotEqual(usefulness.generate(config), usefulness.generate(other_seed))

    def test_positive_temporal_shift_uses_extended_raw_latent_without_wraparound(self):
        lead = usefulness._positive_latents(debug_config("P02", pairs=16, max_lag_cells=10))
        self.assertEqual(380, len(lead["x"]))
        self.assertAlmostEqual(lead["x"][10], lead["load"][13] - .25 * lead["noise"][13])
        follow = usefulness._positive_latents(debug_config("P03", pairs=16, max_lag_cells=10))
        self.assertAlmostEqual(-follow["x"][13], follow["load"][10] - .25 * follow["noise"][10])

    def test_debug_wire_has_exact_request_counts_error_ratio_and_six_place_values(self):
        generated = usefulness.generate(debug_config("T02", members=2))
        operation = generated["input"]
        self.assertEqual("comparison", operation["operation"])
        self.assertTrue(operation["conditions_confirmed"])
        for member in (operation["baseline"], operation["current"]):
            rows = list(csv.DictReader(StringIO(member["load_jtl"])))
            self.assertEqual(240 * 20, len(rows))
            errors = sum(row["success"] == "false" for row in rows)
            self.assertEqual(48, errors)
            self.assertEqual(Decimal(".01"), Decimal(errors) / Decimal(len(rows)))
            for series in member["resources"]["series"]:
                for value in series["values"]:
                    self.assertGreaterEqual(Decimal(str(value)).as_tuple().exponent, -6)

    def test_comparison_members_have_the_inert_diagnostic_pair_needed_for_window_summaries(self):
        operation = usefulness.generate(debug_config("T02", members=2))["input"]
        for member in (operation["baseline"], operation["current"]):
            pair = member["diagnostics"]["pairs"]
            self.assertEqual([{"id": "window-summary", "resource_series_id": "resource",
                               "load_metric": "throughput_rps", "window_ids": ["evaluation"],
                               "expected_sign": "either", "max_lag_ms": 0, "min_abs_effect": .3,
                               "min_resource_delta": .1, "min_load_delta": 20,
                               "topology_basis": "neutral window summary", "clock_alignment": "declared_aligned",
                               "controls": []}], pair)


if __name__ == "__main__":
    unittest.main()

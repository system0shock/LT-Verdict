import json
from hashlib import sha256
from pathlib import Path
import sys
import unittest

try:
    import numpy as np
except ModuleNotFoundError as error:
    if error.name != "numpy":
        raise
    raise unittest.SkipTest("numpy is not installed")

sys.path.insert(0, str(Path(__file__).parent))
import correlation_scenarios as cs


IDS = (
    "N01-iid", "N02-ar08", "N03-ar095", "N04-t3",
    "N05-drift-strong-ar08", "N06-drift-weak-ar08", "N07-randomwalk",
    "N08-ar08-240-l10", "N09-ar08-gaps", "N10-stages-2",
    "N10-stages-3", "N11-activation", "N12-ar08-1920-l4", "P01-lin-lag0",
    "P02-lin-lag2", "P03-lin-neg-lag3", "P04-lin-drift",
    "P05-lin-weak-a", "P05-lin-weak-b", "P05-lin-weak-c",
    "P06-level-threshold", "P07-level-saturation", "B01-common-factor",
    "B02-long-gap-blocks", "B03-gap-degradation-dependent",
)


def corr(x, y):
    return float(np.corrcoef(x, y)[0, 1])


def acf(scenario_id):
    return float(np.mean([
        corr((row := cs.generate(scenario_id, seed)["outcome"])[:-1], row[1:])
        for seed in range(200)
    ]))


class ScenarioProperties(unittest.TestCase):
    def test_seed_vectors_and_determinism(self):
        self.assertEqual(cs.stream_seed("N02-ar08", 5), 8224200746964369771)
        self.assertEqual(cs.stream_seed("N01-iid", 0), 2896942816554372063)
        self.assertEqual(cs.stream_seed("P02-lin-lag2", 999), 10671333056867032067)
        self.assertEqual(cs.stream_seed("N12-ar08-1920-l4", 100000), int.from_bytes(
            sha256(b"ltv-c5/v1/N12-ar08-1920-l4/100000").digest()[:8], "big"))
        for scenario_id in ("N02-ar08", "P02-lin-lag2", "N10-stages-3"):
            self.assertEqual(cs.generate(scenario_id, 5), cs.generate(scenario_id, 5))
            self.assertNotEqual(cs.generate(scenario_id, 5), cs.generate(scenario_id, 6))
        self.assertNotEqual(cs.generate("N02-ar08", 5)["outcome"],
                            cs.generate("P02-lin-lag2", 5)["outcome"])

    def test_inventory_shapes_types_and_aliases(self):
        self.assertEqual(cs.SCENARIO_IDS, IDS)
        with self.assertRaises(KeyError):
            cs.generate("unknown", 0)
        keys = {"scenario_id", "seed", "step_ms", "max_lag_cells",
                "hypotheses", "stages", "resources", "outcome", "target",
                "source_cells", "truth", "activation"}
        for scenario_id in IDS:
            with self.subTest(scenario_id=scenario_id):
                g = cs.generate(scenario_id, 0)
                self.assertEqual(set(g), keys)
                self.assertEqual((g["scenario_id"], g["seed"]), (scenario_id, 0))
                self.assertEqual((g["step_ms"], g["hypotheses"]), (15000, 16))
                self.assertEqual(g["max_lag_cells"], 10 if scenario_id == "N08-ar08-240-l10" else 4)
                self.assertEqual(len(g["stages"]), 3 if scenario_id == "N10-stages-3" else
                                 2 if scenario_id == "N10-stages-2" else 1)
                first = g["stages"][0]
                for key in ("resources", "outcome", "target"):
                    self.assertIs(g[key], first[key])
                self.assertEqual(g["source_cells"], first["source_cells"])
                for number, stage in enumerate(g["stages"], 1):
                    self.assertEqual(set(stage), {"window_id", "source_cells", "resources", "outcome", "target"})
                    self.assertEqual(stage["window_id"], f"stage-{number}")
                    n = {"N08-ar08-240-l10": 240, "N12-ar08-1920-l4": 1920}.get(scenario_id, 120)
                    self.assertEqual(stage["source_cells"], n)
                    self.assertEqual(len(stage["resources"]), 16)
                    for series in [*stage["resources"], stage["outcome"], stage["target"]]:
                        self.assertEqual(len(series), n)
                        self.assertTrue(all(value is None or isinstance(value, float) for value in series))
                json.dumps(g, allow_nan=False)
                self.assertEqual(set(g["truth"]), {"null_hypotheses", "planted", "common_factor_indices", "level_effect"})
                if scenario_id.startswith("N") or scenario_id in ("B02-long-gap-blocks", "B03-gap-degradation-dependent"):
                    self.assertEqual(g["truth"]["null_hypotheses"], list(range(16)))
        multi = cs.generate("N10-stages-3", 0)["stages"]
        self.assertEqual(len({tuple(stage["outcome"]) for stage in multi}), 3)

    def test_n12_long_stage_shape_and_ar1(self):
        g = cs.generate("N12-ar08-1920-l4", 100000)
        self.assertEqual((g["max_lag_cells"], g["hypotheses"], g["step_ms"]), (4, 16, 15000))
        self.assertEqual((len(g["stages"]), g["source_cells"], len(g["outcome"])), (1, 1920, 1920))
        self.assertEqual(g["truth"]["null_hypotheses"], list(range(16)))
        self.assertIsNone(g["activation"])
        rows = [cs.generate("N12-ar08-1920-l4", seed)["outcome"] for seed in range(20)]
        self.assertLess(abs(float(np.mean([corr(r[:-1], r[1:]) for r in rows])) - 0.8), 0.03)
        self.assertLess(max(abs(float(np.mean(r))) for r in rows), 0.5)

    def test_fx03b_boundary_fixture(self):
        self.assertEqual(cs.FIXTURE_CELLS, {"FX03b-cells-1920-1921": (1920, 1921)})
        for cells in (1920, 1921):
            g = cs.generate_fixture("FX03b-cells-1920-1921", cells)
            self.assertEqual((g["source_cells"], len(g["outcome"]), len(g["target"])), (cells, cells, cells))
            self.assertEqual((len(g["stages"]), len(g["resources"]), g["max_lag_cells"]), (1, 16, 4))
            self.assertEqual(g["truth"]["null_hypotheses"], list(range(16)))
            self.assertEqual(g, cs.generate_fixture("FX03b-cells-1920-1921", cells))
            json.dumps(g, allow_nan=False)
        self.assertNotEqual(cs.generate_fixture("FX03b-cells-1920-1921", 1920)["outcome"],
                            cs.generate("N12-ar08-1920-l4", 0)["outcome"])
        with self.assertRaises(KeyError):
            cs.generate_fixture("unknown", 1920)
        with self.assertRaises(ValueError):
            cs.generate_fixture("FX03b-cells-1920-1921", 1922)

    def test_missing_masks(self):
        missing = 0
        for seed in range(200):
            stage = cs.generate("N09-ar08-gaps", seed)["stages"][0]
            mask = tuple(value is None for value in stage["outcome"])
            missing += sum(mask)
            for series in [*stage["resources"], stage["target"]]:
                self.assertEqual(tuple(value is None for value in series), mask)
        self.assertLessEqual(abs(missing / (200 * 120) - 0.05), 0.02)
        for seed in range(20):
            stage = cs.generate("B02-long-gap-blocks", seed)["stages"][0]
            mask = tuple(value is None for value in stage["outcome"])
            self.assertEqual(sum(mask), 24)
            self.assertEqual(sum(a and not b for a, b in zip(mask, (False, *mask[:-1]))), 3)
        dependent_missing = 0
        for seed in range(200):
            stage = cs.generate("B03-gap-degradation-dependent", seed)["stages"][0]
            mask = tuple(value is None for value in stage["outcome"])
            dependent_missing += sum(mask)
            for series in [*stage["resources"], stage["target"]]:
                self.assertEqual(tuple(value is None for value in series), mask)
        self.assertTrue(0.05 < dependent_missing / (200 * 120) < 0.11)

    def test_noise_autocorrelation_and_tails(self):
        self.assertLess(abs(acf("N01-iid")), 0.05)
        self.assertLess(abs(acf("N02-ar08") - 0.8), 0.05)
        self.assertGreaterEqual(acf("N03-ar095"), 0.85)
        self.assertLessEqual(acf("N03-ar095"), 0.97)
        self.assertGreater(acf("N07-randomwalk"), 0.9)
        self.assertGreater(max(abs(value) for seed in range(200)
                               for value in cs.generate("N04-t3", seed)["outcome"]), 6)
        for seed in range(20):
            self.assertEqual(cs.generate("N07-randomwalk", seed)["outcome"][0], 0.0)

    def test_drift_fitted_changes(self):
        for k in (13, 1.6):
            for seed in range(50):
                line = cs._add_drift(np.random.default_rng(seed), np.zeros(120), k)
                self.assertTrue(0.8 * k <= abs(line[-1] - line[0]) <= 1.2 * k)
                self.assertTrue(np.allclose(np.diff(line), np.diff(line)[0]))

        def changes(scenario_id):
            for seed in range(50):
                g = cs.generate(scenario_id, seed)
                for row in [*g["resources"], g["outcome"]]:
                    yield abs(float(np.polyfit(np.arange(len(row)), row, 1)[0]) * (len(row) - 1))

        strong = list(changes("N05-drift-strong-ar08"))
        weak = list(changes("N06-drift-weak-ar08"))
        # The realized fitted slope also contains random AR noise.
        self.assertGreaterEqual(np.mean([8 <= value <= 18 for value in strong]), 0.99)
        self.assertGreaterEqual(np.mean([0 <= value <= 4.5 for value in weak]), 0.99)
        self.assertLess(float(np.mean(list(changes("N02-ar08")))), 1.5)

    def test_planted_lag_sign_and_nulls(self):
        weak_means = []
        for scenario_id, lag, sign, beta in (("P01-lin-lag0", 0, 1, 0.6),
                                             ("P02-lin-lag2", 2, 1, 0.6),
                                             ("P03-lin-neg-lag3", 3, -1, 0.6),
                                             ("P04-lin-drift", 2, 1, 0.6),
                                             ("P05-lin-weak-a", 2, 1, 0.2),
                                             ("P05-lin-weak-b", 2, 1, 0.3),
                                             ("P05-lin-weak-c", 2, 1, 0.4)):
            planted_corr = []
            null_corr = []
            halves = [[], []]
            for seed in range(200):
                g = cs.generate(scenario_id, seed)
                planted = g["truth"]["planted"]
                index = planted["index"]
                self.assertIn(index, range(15))
                self.assertEqual((planted["lag_cells"], planted["sign"]), (lag, sign))
                self.assertEqual(g["truth"]["null_hypotheses"], [i for i in range(16) if i != index])
                x = g["resources"][index][:120 - lag]
                y = g["outcome"][lag:]
                planted_corr.append(corr(x, y))
                null_corr.append(corr(g["resources"][g["truth"]["null_hypotheses"][0]][:120 - lag], y))
                if scenario_id == "P02-lin-lag2":
                    middle = len(x) // 2
                    halves[0].append(corr(x[:middle], y[:middle]))
                    halves[1].append(corr(x[middle:], y[middle:]))
            if scenario_id != "P04-lin-drift":
                self.assertLess(abs(float(np.mean(planted_corr)) - sign * beta), 0.12)
                self.assertLess(abs(float(np.mean(null_corr))), 0.1)
            if scenario_id.startswith("P05"):
                weak_means.append(float(np.mean(planted_corr)))
            if scenario_id == "P02-lin-lag2":
                self.assertTrue(all(float(np.mean(half)) > 0.4 for half in halves))
        self.assertEqual(weak_means, sorted(weak_means))

    def test_control_hypothesis_is_always_null(self):
        for scenario_id in (
            "P01-lin-lag0", "P02-lin-lag2", "P03-lin-neg-lag3",
            "P04-lin-drift", "P05-lin-weak-a", "P06-level-threshold",
            "P07-level-saturation", "B01-common-factor",
        ):
            planted_indices = set()
            for seed in range(200):
                truth = cs.generate(scenario_id, seed)["truth"]
                self.assertIn(15, truth["null_hypotheses"])
                if truth["planted"] is not None:
                    index = truth["planted"]["index"]
                    self.assertIn(index, range(15))
                    planted_indices.add(index)
                if truth["level_effect"] is not None:
                    self.assertIn(truth["level_effect"]["index"], range(15))
                self.assertTrue(all(index in range(15) for index in truth["common_factor_indices"]))
            if scenario_id not in (
                "P06-level-threshold", "P07-level-saturation", "B01-common-factor"
            ):
                self.assertGreater(len(planted_indices), 10, scenario_id)

    def test_planted_extended_series_boundaries(self):
        for seed in range(10):
            rng = np.random.default_rng(cs.stream_seed("P02-lin-lag2", seed))
            index = int(rng.integers(15))
            planted_ext = None
            for i in range(16):
                row = cs._ar1(rng, 122 if i == index else 120, 0.8)
                if i == index:
                    planted_ext = row
            outcome_noise = cs._ar1(rng, 120, 0.8)
            g = cs.generate("P02-lin-lag2", seed)
            self.assertEqual(g["truth"]["planted"]["index"], index)
            np.testing.assert_array_equal(g["resources"][index], planted_ext[2:])
            np.testing.assert_array_equal(g["outcome"],
                                          0.6 * planted_ext[:120] + np.sqrt(1 - 0.6 ** 2) * outcome_noise)

    def test_level_effects(self):
        for scenario_id, shape, feature in (
            ("P06-level-threshold", "threshold", lambda z: np.asarray(z) > 1),
            ("P07-level-saturation", "saturation", lambda z: np.tanh(2 * np.asarray(z))),
        ):
            values = []
            for seed in range(200):
                g = cs.generate(scenario_id, seed)
                truth = g["truth"]
                index = truth["level_effect"]["index"]
                self.assertEqual(truth["level_effect"]["shape"], shape)
                self.assertIsNone(truth["planted"])
                self.assertEqual(truth["null_hypotheses"], [i for i in range(16) if i != index])
                values.append(corr(feature(g["resources"][index]), g["outcome"]))
            self.assertGreater(float(np.mean(values)), 0.15 if shape == "threshold" else 0.2)

    def test_common_factor(self):
        loaded_corr = []
        null_corr = []
        for seed in range(200):
            g = cs.generate("B01-common-factor", seed)
            truth = g["truth"]
            loaded = truth["common_factor_indices"]
            self.assertEqual(len(loaded), 4)
            self.assertEqual(len(set(loaded)), 4)
            self.assertIsNone(truth["planted"])
            self.assertEqual(truth["null_hypotheses"], [i for i in range(16) if i not in loaded])
            loaded_corr.append(abs(corr(g["resources"][loaded[0]], g["outcome"])))
            null_corr.append(abs(corr(g["resources"][truth["null_hypotheses"][0]], g["outcome"])))
        self.assertGreater(float(np.mean(loaded_corr)), 0.3)
        self.assertLess(float(np.mean(null_corr)), 0.15)

    def test_activation_share_and_truth(self):
        activated = 0
        for seed in range(1000):
            g = cs.generate("N11-activation", seed)
            activation = g["activation"]
            self.assertEqual(activation["threshold"], cs.SLA_THRESHOLD)
            self.assertEqual(activation["min_run_cells"], cs.SLA_MIN_RUN_CELLS)
            self.assertEqual(g["truth"]["null_hypotheses"], list(range(16)))
            run = 0
            actual = False
            for value in g["outcome"]:
                run = run + 1 if value > activation["threshold"] else 0
                actual |= run >= activation["min_run_cells"]
            self.assertEqual(activation["violated"], actual)
            activated += actual
        # Seeds 0..999 are the same range used for the one-time calibration.
        # This assertion guards provisional constants, not independent evidence.
        self.assertGreaterEqual(activated / 1000, 0.30)
        self.assertLessEqual(activated / 1000, 0.70)


if __name__ == "__main__":
    unittest.main()

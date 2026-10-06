import math
from pathlib import Path
import random
import sys
import unittest

try:
    import numpy as np
except ModuleNotFoundError as error:
    if error.name != "numpy":
        raise
    raise unittest.SkipTest("numpy is not installed")

sys.path.insert(0, str(Path(__file__).parent))
import correlation_oracle as oracle


class CorrelationOracleTest(unittest.TestCase):
    def test_java_random_jdk_vectors(self):
        """Vectors measured with jshell on JDK 21.0.9, 2026-10-04."""
        cases = [
            (42, 100, [30, 63, 48, 84, 70]),
            (42, 1073741825, [117392763, 102948884, 662969970, 595021505, 196118093]),
            (-1, 100, [13, 25, 79, 39, 4]),
            (-9223372036854775808, 100, [60, 48, 29, 47, 15]),
            (9223372036854775807, 1000, [913, 225, 579]),
        ]
        for seed, bound, expected in cases:
            with self.subTest(seed=seed, bound=bound):
                rng = oracle.JavaRandom(seed)
                self.assertEqual([rng.next_int(bound) for _ in expected], expected)
        rng = oracle.JavaRandom(42)
        self.assertEqual([rng.next_int(16) for _ in range(3)], [11, 0, 10])
        self.assertEqual(rng.next_int(5), 4)
        self.assertEqual(rng.next_int(1000), 970)
        self.assertEqual(rng.next_int(1 << 30), 1011543762)
        rng = oracle.JavaRandom(7)
        self.assertEqual([rng.next_int(1) for _ in range(2)], [0, 0])
        self.assertEqual([rng.next_int(2) for _ in range(3)], [1, 0, 0])
        self.assertEqual([rng.next_int(3) for _ in range(2)], [1, 1])
        rng = oracle.JavaRandom(42)
        self.assertEqual([rng.next_int() for _ in range(2)], [-1170105035, 234785527])
        with self.assertRaises(ValueError):
            rng.next_int(0)

    def test_product_seed_jdk_vectors(self):
        """SHA-256 seeds and draws measured with jshell on 2026-10-04."""
        cases = [
            (10, "resources", -656652275283454196, 31, [15, 28, 12, 26, 17, 27]),
            (20, "resources", -2377246080316407788, 21, [2, 11, 11, 18, 3, 14]),
            (10, "outcome", 2970402982658264070, 31, [4, 11, 15, 28, 15, 19]),
            (20, "outcome", 1570202901034197180, 21, [0, 14, 9, 1, 17, 11]),
        ]
        for block, side, seed, bound, draws in cases:
            with self.subTest(block=block, side=side):
                self.assertEqual(oracle.product_seed("fixture", block, side), seed)
                rng = oracle.JavaRandom(seed)
                self.assertEqual([rng.next_int(bound) for _ in draws], draws)

    def test_ranks_average_ties_and_reject_nonfinite(self):
        self.assertEqual(oracle.ranks([4, 1, 4]), [2.5, 1.0, 2.5])
        self.assertEqual(oracle.ranks([2, 0.0, 2, -0.0, 2]), [4.0, 1.5, 4.0, 1.5, 4.0])
        for bad in [float("nan"), float("inf"), -float("inf")]:
            with self.assertRaises(ValueError):
                oracle.ranks([1.0, bad])

    def test_lag_profile_hand_values_and_zero_variance(self):
        profile = oracle.lag_profile([1, 2, 4, 3, 5], [2, 1, 3, 4, 5], 1)
        for actual, expected in zip(profile, [-0.5, math.sqrt(3 / 7), 0.5]):
            self.assertAlmostEqual(actual, expected, places=14)
        self.assertEqual(oracle.lag_profile([1, 2, 3], [1, 1, 1], 0), [None])
        self.assertEqual(oracle.lag_profile([1, 2], [2, 1], 1), [None, None, None])

    def test_lag_max_abs_known_alignment_and_limits(self):
        x = [float((i * 17) % 41) for i in range(40)]
        self.assertAlmostEqual(oracle.lag_max_abs(x, x, 4), 1.0, places=14)
        self.assertAlmostEqual(oracle.lag_max_abs(x, [-v for v in x], 4), 1.0, places=14)
        y = x[-2:] + x[:-2]
        profile = oracle.lag_profile(x, y, 4)
        self.assertEqual(max(range(-4, 5), key=lambda lag: abs(profile[lag + 4])), 2)
        self.assertAlmostEqual(oracle.lag_max_abs(x, y, 4), 1.0, places=14)
        tied = [float(i // 2) for i in range(40)]
        self.assertAlmostEqual(oracle.lag_max_abs(tied, tied, 0), 1.0, places=14)
        self.assertIsNone(oracle.lag_max_abs(x, [1.0] * 40, 0))
        self.assertIsNone(oracle.lag_max_abs(x, x[:-1], 0))
        self.assertIsNotNone(oracle.lag_max_abs(x[:38], x[:38], 4))
        self.assertIsNone(oracle.lag_max_abs(x[:37], x[:37], 4))

    def test_holm_order_ties_and_cap(self):
        self.assertAlmostEqual(oracle.holm([0.012, 1.0, 1.0])[0], 0.036, places=12)
        self.assertEqual(oracle.holm([0.01, 0.03, 0.2]), [0.03, 0.06, 0.2])
        self.assertEqual(oracle.holm([0.2, 0.01, 0.03]), [0.2, 0.03, 0.06])
        self.assertEqual(oracle.holm([0.02, 0.02, 0.8]), [0.06, 0.06, 0.8])
        self.assertEqual(oracle.holm([0.9, 0.9]), [1.0, 1.0])

    def test_wilson_published_literals(self):
        for k, expected in [(54, 0.06979215484605245), (50, 0.06531382024425081), (0, 0.003826758485555124)]:
            self.assertAlmostEqual(oracle.wilson_upper(k, 1000), expected, places=12)
        self.assertGreater(oracle.wilson_upper(55, 1000), 0.07)
        self.assertAlmostEqual(oracle.wilson_lower(900, 1000), 0.8798480368046516, places=12)
        self.assertGreaterEqual(oracle.wilson_lower(0, 1000), 0.0)

    def test_longest_run_and_differences_never_cross_gap(self):
        series = [float(i) for i in range(20)] + [None] + [float(100 + 2 * i) for i in range(40)]
        self.assertEqual(oracle.longest_run(series), series[21:])
        self.assertEqual(oracle.first_differences(series), [2.0] * 39)
        tied = [float(i) for i in range(30)] + [None] + [float(100 + i) for i in range(30)]
        self.assertEqual(oracle.longest_run(tied), tied[:30])
        self.assertEqual(oracle.first_differences(tied), [1.0] * 29)
        self.assertEqual(oracle.longest_run([None, 2.0, 5.0, None]), [2.0, 5.0])
        self.assertEqual(oracle.first_differences([None, 2.0, 5.0, None]), [3.0])
        self.assertEqual(oracle.longest_run([None, None]), [])
        self.assertEqual(oracle.first_differences([None, None]), [])
        self.assertEqual(oracle.first_differences([None, 3.0, None]), [])

    def test_difference_pipeline_with_gap_matches_known_lag(self):
        d = [float((i * 17) % 41) for i in range(41)]
        shifted = d[-2:] + d[:-2]
        resource_run = [0.0]
        outcome_run = [0.0]
        for resource_step, outcome_step in zip(d, shifted):
            resource_run.append(resource_run[-1] + resource_step)
            outcome_run.append(outcome_run[-1] + outcome_step)
        first_run = [float(i) for i in range(10)]
        resource = first_run + [None] + resource_run
        outcome = first_run + [None] + outcome_run

        resource_d = oracle.first_differences(resource)
        outcome_d = oracle.first_differences(outcome)
        self.assertEqual(resource_d, d)
        self.assertEqual(outcome_d, shifted)
        # The circular shift preserves ranks, and lag +2 matches every anchor.
        profile = oracle.lag_profile(resource_d, outcome_d, 4)
        self.assertEqual(max(range(-4, 5), key=lambda lag: abs(profile[lag + 4])), 2)
        self.assertAlmostEqual(oracle.lag_max_abs(resource_d, outcome_d, 4), 1.0, places=14)

    def test_plateau_counter_resource_is_not_evaluable(self):
        outcome = [float((i * 17) % 41) for i in range(40)]
        plateau_d = oracle.first_differences([7.0] * 41)
        self.assertEqual(plateau_d, [0.0] * 40)
        self.assertIsNone(oracle.lag_max_abs(plateau_d, outcome, 0))

        counter_d = oracle.first_differences([float(7 + 3 * i) for i in range(41)])
        self.assertEqual(counter_d, [3.0] * 40)
        self.assertIsNone(oracle.lag_max_abs(counter_d, outcome, 0))

    def test_select_published_jvm_fixture_and_replay(self):
        """Expected p-values come from an independent JVM product test."""
        outcome = [float(i // 2 + 1) for i in range(40)]
        hypotheses = [
            oracle.Hypothesis("strong", "evaluation", outcome[:], outcome, 0),
            oracle.Hypothesis("noise", "evaluation", [float((i * 17) % 41) for i in range(40)], outcome, 0),
            oracle.Hypothesis("partial", "evaluation", outcome[:], outcome, 0, unavailable_reason="GENUINE_PARTIAL_UNCALIBRATED"),
        ]
        result = oracle.select(hypotheses, "fixture", "levels")
        self.assertEqual(result, oracle.select(hypotheses, "fixture", "levels"))
        self.assertEqual([row.family_hypotheses for row in result], [3, 3, 3])
        strong, noise, partial = result
        for actual, expected in [(strong.p_b10, 0.001), (strong.p_b20, 0.012), (strong.max_p, 0.012), (strong.holm_adjusted, 0.036), (noise.p_b10, 0.614), (noise.p_b20, 0.549), (noise.max_p, 0.614), (noise.holm_adjusted, 1.0)]:
            self.assertAlmostEqual(actual, expected, places=12)
        self.assertEqual((strong.status, strong.selected, strong.reasons), ("SELECTED", True, []))
        self.assertEqual((noise.status, noise.selected, noise.reasons), ("NOT_SELECTED", False, ["HOLM_NOT_REJECTED"]))
        self.assertEqual((partial.status, partial.p_b10, partial.p_b20, partial.max_p, partial.holm_adjusted, partial.selected, partial.reasons), ("UNAVAILABLE", None, None, None, None, False, ["GENUINE_PARTIAL_UNCALIBRATED"]))

    def test_select_first_difference_family_and_rejections(self):
        outcome = [float(i // 2 + 1) for i in range(40)]
        resource = [float((i * 17) % 41) for i in range(40)]
        long = oracle.Hypothesis("long", "evaluation", resource, outcome, 0)
        short = oracle.Hypothesis("short", "evaluation", resource[:20], outcome[:20], 0, unavailable_reason="OBSERVATION_COUNT_UNSUPPORTED")
        levels = oracle.select([long], "fixture", "levels")
        differenced = oracle.select([long], "fixture", "first_difference")
        self.assertEqual(differenced, oracle.select([long], "fixture", "first_difference"))
        self.assertNotEqual((levels[0].p_b10, levels[0].p_b20), (differenced[0].p_b10, differenced[0].p_b20))
        family = oracle.select([long, short], "fixture", "first_difference")
        self.assertEqual([r.family_hypotheses for r in family], [2, 2])
        self.assertNotEqual(family[0].status, "UNAVAILABLE")
        self.assertAlmostEqual(family[0].holm_adjusted, min(1.0, 2 * family[0].max_p), places=12)
        self.assertEqual((family[1].status, family[1].reasons), ("UNAVAILABLE", ["OBSERVATION_COUNT_UNSUPPORTED"]))
        over = oracle.select([long] * 17, "fixture")
        self.assertTrue(all((r.status, r.reasons) == ("UNAVAILABLE", ["FAMILY_SIZE_UNSUPPORTED"]) for r in over))
        with self.assertRaises(ValueError):
            oracle.select([long], "fixture", "x")

    def test_select_accepts_1920_difference_points(self):
        source = [float(i) for i in range(1921)]
        row = oracle.select([oracle.Hypothesis("limit", "evaluation", source, source, 0)],
                            "fixture", "first_difference")[0]
        self.assertEqual(row.reasons, ["PAIR_NOT_EVALUABLE"])

    def test_select_rejects_1921_difference_points(self):
        source = [float(i) for i in range(1922)]
        row = oracle.select([oracle.Hypothesis("over", "evaluation", source, source, 0)],
                            "fixture", "first_difference")[0]
        self.assertEqual(row.reasons, ["OBSERVATION_COUNT_UNSUPPORTED"])

    def test_select_materiality_and_unavailable_reasons(self):
        outcome = [float(i // 2 + 1) for i in range(40)]
        rows = oracle.select([
            oracle.Hypothesis("strong", "evaluation", outcome[:], outcome, 0, material_candidate=False),
            oracle.Hypothesis("constant", "evaluation", [1.0] * 40, outcome, 0),
            oracle.Hypothesis("lag", "evaluation", outcome[:], outcome, 11),
        ], "fixture")
        self.assertEqual((rows[0].status, rows[0].selected, rows[0].reasons), ("NOT_SELECTED", False, ["MATERIALITY_NOT_MET"]))
        self.assertEqual((rows[1].status, rows[1].reasons), ("UNAVAILABLE", ["PAIR_NOT_EVALUABLE"]))
        self.assertEqual((rows[2].status, rows[2].reasons), ("UNAVAILABLE", ["LAG_ANCHOR_COUNT_UNSUPPORTED"]))

    def test_oversized_family_preserves_each_unavailable_reason(self):
        outcome = [float(i // 2) for i in range(40)]
        hypotheses = [oracle.Hypothesis(str(i), "evaluation", outcome[:], outcome[:], 0) for i in range(17)]
        hypotheses[8].unavailable_reason = "GENUINE_PARTIAL_UNCALIBRATED"
        rows = oracle.select(hypotheses, "fixture")
        self.assertEqual([row.reasons for row in rows], [
            ["GENUINE_PARTIAL_UNCALIBRATED", "FAMILY_SIZE_UNSUPPORTED"] if i == 8
            else ["FAMILY_SIZE_UNSUPPORTED"] for i in range(17)
        ])

    def test_select_rejects_mismatched_available_outcomes(self):
        outcome = [float(i // 2) for i in range(40)]
        first = oracle.Hypothesis("first", "evaluation", outcome[:], outcome[:], 0)
        for other_outcome in (outcome[:-1], outcome[:-1] + [99.0]):
            with self.subTest(other_outcome=other_outcome):
                second = oracle.Hypothesis("second", "evaluation", outcome[:], other_outcome, 0)
                with self.assertRaisesRegex(ValueError, "^family outcome mismatch$"):
                    oracle.select([first, second], "fixture")

    def test_lag_profile_uses_sequential_arithmetic(self):
        rng = random.Random(7)
        for trial in range(2000):
            x = [float(rng.randrange(5)) for _ in range(40)]
            y = [float(rng.randrange(5)) for _ in range(40)]
            a = oracle.ranks(x)[1:39]
            b = oracle.ranks(y)[2:40]
            am = bm = 0.0
            for value in a:
                am += value
            for value in b:
                bm += value
            am /= 38
            bm /= 38
            numerator = xs = ys = 0.0
            ac, bc = [], []
            for u, v in zip(a, b):
                cx, cy = u - am, v - bm
                ac.append(cx)
                bc.append(cy)
                numerator += cx * cy
                xs += cx * cx
                ys += cy * cy
            sequential = numerator / math.sqrt(xs * ys)
            fsum_rho = math.fsum(u * v for u, v in zip(ac, bc)) / math.sqrt(
                math.fsum(u * u for u in ac) * math.fsum(v * v for v in bc)
            )
            if sequential != fsum_rho:
                self.assertEqual(oracle.lag_profile(x, y, 1)[2], sequential)
                self.assertNotEqual(sequential, fsum_rho)
                return
        self.skipTest("No fsum-versus-sequential difference in 2000 seeded trials")

    def test_vectorized_statistic_matches_reference_with_ties(self):
        rng = random.Random(314159)
        for max_lag in (0, 1, 4):
            for length in (31, 38, 40, 45):
                if length - 2 * max_lag < 30:
                    continue
                xs = [[float(rng.randrange(5)) for _ in range(length)] for _ in range(6)]
                ys = [[float(rng.randrange(5)) for _ in range(length)] for _ in range(6)]
                actual = oracle._lag_max_abs_rows(np.asarray(xs), np.asarray(ys), max_lag)
                for i, value in enumerate(actual):
                    with self.subTest(max_lag=max_lag, length=length, row=i):
                        self.assertEqual(value, oracle.lag_max_abs(xs[i], ys[i], max_lag))


if __name__ == "__main__":
    unittest.main()

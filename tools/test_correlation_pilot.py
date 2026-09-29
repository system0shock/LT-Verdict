import math
from pathlib import Path
import sys
import unittest

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import correlation_pilot as pilot


class CorrelationPilotTest(unittest.TestCase):
    def test_rank_lag_blocks_and_holm_contract(self):
        self.assertEqual([[2.5, 1.0, 2.5]], pilot.rank_rows([[4, 1, 4]]).tolist())
        values = pilot.profile([1, 2, 4, 3, 5], [2, 1, 3, 4, 5], 1)
        self.assertAlmostEqual(-0.5, values[0], places=14)
        self.assertAlmostEqual(math.sqrt(3 / 7), values[1], places=14)
        self.assertAlmostEqual(0.5, values[2], places=14)
        indices = pilot.block_indices(9, 3, 4, np.random.default_rng(7))
        self.assertTrue(np.all((indices[:, 1:] - indices[:, :-1])[:, [0, 1, 3, 4, 6, 7]] == 1))
        self.assertGreaterEqual(indices.min(), 0)
        self.assertLess(indices.max(), 9)
        adjusted, rejected = pilot.holm([0.01, 0.03, 0.2])
        self.assertEqual([0.03, 0.06, 0.2], adjusted)
        self.assertEqual([True, False, False], rejected)


if __name__ == "__main__":
    unittest.main()

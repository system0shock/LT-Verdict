from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
try:
    import correlation_full as full
except ModuleNotFoundError as error:
    if error.name != "numpy":
        raise
    raise unittest.SkipTest("numpy is not installed")


class CorrelationFullTest(unittest.TestCase):
    def test_full_inventory_and_frozen_sources(self):
        design = full.full_design()
        self.assertEqual(15, len(full.FULL_CONFIGS))
        self.assertEqual(15000, len(full.pilot.case_ids()))
        self.assertEqual("N01-p1-l0-s1000", full.pilot.case_ids()[0])
        self.assertEqual("P03-p16-l10-s1999", full.pilot.case_ids()[-1])
        self.assertEqual("correlation-pilot-v1", design["base_rng_method"])
        self.assertEqual(full.pilot.sha256_file(full.pilot.__file__), design["base_runner_sha256"])
        self.assertEqual(full.pilot.sha256_file(full.__file__), design["wrapper_sha256"])
        self.assertIn("in-flight", design["time_budget_semantics"])


if __name__ == "__main__":
    unittest.main()

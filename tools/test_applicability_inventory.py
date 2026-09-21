import subprocess
import sys
import unittest
from pathlib import Path

from tools.applicability_inventory import configurations


class ApplicabilityInventoryTests(unittest.TestCase):
    def test_inventory_has_the_declared_case_and_simulation_counts(self):
        cases = configurations()
        self.assertEqual(421, len(cases))
        self.assertEqual(830, sum(len(case["members"]) for case in cases))
        self.assertEqual(421, len({case["id"] for case in cases}))

    def test_standard_cases_cover_every_family_seed_and_noise_variant(self):
        cases = [case for case in configurations() if case["variant"] in {"clean", "noisy"}]
        self.assertEqual(400, len(cases))
        self.assertEqual(
            {(f"NT{number:02d}", seed, noisy)
             for number in range(1, 11) for seed in range(2000, 2020) for noisy in (False, True)},
            {(case["scenario"], case["seed"], case["noisy"]) for case in cases},
        )

    def test_pairs_change_only_their_named_intervention_mechanism(self):
        mechanism = {
            "NT01": "cpu_workers", "NT02": "cpu_workers", "NT03": "db_workers",
            "NT04": "pool_capacity_changes", "NT05": "cpu_quota_schedule",
            "NT06": "allocation_bytes", "NT07": "downstream_changes",
            "NT08": "cpu_worker_changes", "NT09": "generator_threads", "NT10": "stages",
        }
        for case in configurations():
            if len(case["members"]) != 2:
                continue
            baseline, intervention = case["members"]
            self.assertEqual(["baseline", "intervention"], [baseline["name"], intervention["name"]])
            left, right = baseline["parameters"], intervention["parameters"]
            changed = {key for key in set(left) | set(right) if left.get(key) != right.get(key)}
            self.assertEqual({"intervention", mechanism[case["scenario"]]}, changed)

    def test_temporal_cases_have_literal_ordering_and_clock_variants(self):
        cases = [case for case in configurations() if case["scenario"] == "TEMPORAL"]
        self.assertEqual(12, len(cases))
        self.assertEqual({("A", 40, 40), ("B", 40, 45), ("C", 45, 40)}, {
            (case["variant"].split("-")[0],
             case["members"][0]["parameters"]["background_cpu_intervals"][0]["from_us"] // 1_000_000,
             case["members"][0]["parameters"]["downstream_changes"][0]["from_us"] // 1_000_000)
            for case in cases
        })
        self.assertEqual({"declared_aligned", "unknown"}, {
            case["members"][0]["parameters"]["sampling"]["clock_alignment"] for case in cases
        })
        self.assertTrue(all(case["seed"] == 2000 and len(case["members"]) == 1
                            and case["members"][0]["name"] == "current" for case in cases))

    def test_inventory_module_supports_direct_script_execution(self):
        result = subprocess.run([sys.executable, "tools/applicability_inventory.py"],
                                cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()

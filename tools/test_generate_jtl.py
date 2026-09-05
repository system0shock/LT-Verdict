"""Tests for the deterministic JMeter CSV generator."""

import hashlib
import unittest
from pathlib import Path

from tools.perf import generate_jtl


ROOT = Path(__file__).resolve().parents[1]


class GenerateJtlTests(unittest.TestCase):
    def test_seed_one_is_deterministic_with_expected_spikes_and_errors(self) -> None:
        first = ROOT / "tools/.generate_jtl_test_first.jtl"
        second = ROOT / "tools/.generate_jtl_test_second.jtl"
        try:
            generate_jtl.generate(100, 1, first)
            generate_jtl.generate(100, 1, second)

            first_bytes = first.read_bytes()
            self.assertEqual(hashlib.sha256(first_bytes).digest(), hashlib.sha256(second.read_bytes()).digest())
            rows = first_bytes.decode("utf-8").splitlines()
            self.assertEqual("timeStamp,elapsed,label,success", rows[0])
            self.assertEqual(101, len(rows))

            parsed = [row.split(",") for row in rows[1:]]
            starts = [int(row[0]) for row in parsed]
            spacing = starts[1] - starts[0]
            self.assertEqual(10, spacing)
            self.assertLessEqual(((10_000_000 - 1) * spacing // 1000) + 1, 100_000)
            self.assertEqual([24, 49, 74, 99], [index for index, row in enumerate(parsed) if int(row[1]) >= 1000])
            self.assertEqual([19, 39, 59, 79, 99], [index for index, row in enumerate(parsed) if row[3] == "false"])
        finally:
            first.unlink(missing_ok=True)
            second.unlink(missing_ok=True)

    def test_probe_clears_heap_overrides_for_warmup_and_measurements(self) -> None:
        probe = (ROOT / "tools/perf/jtl_probe.sh").read_text(encoding="utf-8")
        clean_environment = "env -u JAVA_OPTS -u LTV_OPTS -u JDK_JAVA_OPTIONS -u _JAVA_OPTIONS"

        self.assertEqual(2, probe.count(clean_environment))


if __name__ == "__main__":
    unittest.main()

"""Tests for the deterministic JMeter CSV generator."""

import csv
import hashlib
import io
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

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


# SHA-256 of the 100-row default output, taken from origin/main before the realistic profiles were added.
BASIC_100_ROWS_SHA256 = "84a79300da236893c8b532de58d9fe0538f59e954e992478506a4341a4d0fe8d"
JMETER_CSV_HEADER = (
    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,"
    "bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
)


class RealisticProfileTests(unittest.TestCase):
    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.addCleanup(self._directory.cleanup)
        self.directory = Path(self._directory.name)

    def csv_rows(self, rows: int, **options: int) -> list[list[str]]:
        path = self.directory / "realistic.jtl"
        generate_jtl.generate_realistic_csv(rows, 1, path, **options)
        return list(csv.reader(io.StringIO(path.read_text(encoding="utf-8"), newline="")))

    def test_default_output_bytes_are_unchanged_through_function_and_cli(self) -> None:
        direct = self.directory / "direct.jtl"
        generate_jtl.generate(100, 1, direct)
        cli = self.directory / "cli.jtl"
        generate_jtl.main(["--rows", "100", "--seed", "1", "--output", str(cli)])

        self.assertEqual(BASIC_100_ROWS_SHA256, hashlib.sha256(direct.read_bytes()).hexdigest())
        self.assertEqual(direct.read_bytes(), cli.read_bytes())

    def test_csv_has_jmeter_columns_labels_errors_and_is_deterministic(self) -> None:
        rows = self.csv_rows(20_000)
        again = self.csv_rows(20_000)

        self.assertEqual(rows, again)
        self.assertEqual(JMETER_CSV_HEADER.split(","), rows[0])
        body = rows[1:]
        self.assertEqual(20_000, len(body))
        self.assertTrue(all(len(row) == 17 for row in body))
        header = rows[0]
        label = header.index("label")
        success = header.index("success")
        labels = {row[label] for row in body}
        self.assertGreaterEqual(len(labels), 200)
        self.assertLessEqual(len(labels), 500)
        failed = [row for row in body if row[success] == "false"]
        self.assertTrue(0.01 <= len(failed) / len(body) <= 0.03, len(failed) / len(body))
        self.assertTrue(all(row[header.index("responseCode")] not in ("200", "201", "204") for row in failed))
        self.assertTrue(all(row[header.index("failureMessage")] for row in failed))
        self.assertTrue(any("," in value for value in labels), "a label with a comma exercises CSV quoting")
        self.assertTrue(any(not value.isascii() for value in labels), "a non-ASCII label exercises UTF-8")
        starts = [int(row[0]) for row in body]
        self.assertEqual(10, starts[1] - starts[0])

    def test_label_count_error_rate_and_seed_are_configurable_and_validated(self) -> None:
        rows = self.csv_rows(20_000, labels=500, error_permille=0)
        labels = {row[2] for row in rows[1:]}
        self.assertGreater(len(labels), 450)
        self.assertLessEqual(len(labels), 500)
        self.assertTrue(all(row[7] == "true" for row in rows[1:]))

        other = self.directory / "seed2.jtl"
        generate_jtl.generate_realistic_csv(1_000, 2, other)
        first = self.directory / "seed1.jtl"
        generate_jtl.generate_realistic_csv(1_000, 1, first)
        self.assertNotEqual(first.read_bytes(), other.read_bytes())

        for invalid in ({"labels": 199}, {"labels": 501}, {"error_permille": -1}, {"error_permille": 201}):
            with self.assertRaises(ValueError):
                generate_jtl.generate_realistic_csv(10, 1, self.directory / "bad.jtl", **invalid)
            with self.assertRaises(ValueError):
                generate_jtl.generate_realistic_xml(10, 1, self.directory / "bad.xml", **invalid)

    def test_xml_is_well_formed_matches_csv_samples_and_is_deterministic(self) -> None:
        first = self.directory / "first.xml"
        second = self.directory / "second.xml"
        generate_jtl.generate_realistic_xml(5_000, 1, first)
        generate_jtl.generate_realistic_xml(5_000, 1, second)
        self.assertEqual(first.read_bytes(), second.read_bytes())

        root = ET.parse(first).getroot()
        self.assertEqual("testResults", root.tag)
        samples = list(root)
        self.assertEqual(5_000, len(samples))
        csv_body = self.csv_rows(5_000)[1:]
        self.assertEqual([row[2] for row in csv_body], [sample.get("lb") for sample in samples])
        self.assertEqual([row[1] for row in csv_body], [sample.get("t") for sample in samples])
        self.assertEqual([row[7] for row in csv_body], [sample.get("s") for sample in samples])
        failed = [sample for sample in samples if sample.get("s") == "false"]
        self.assertTrue(failed)
        self.assertTrue(all(sample.findtext("assertionResult/failureMessage") for sample in failed))

    def test_cli_selects_profile_and_rejects_profile_options_for_basic(self) -> None:
        csv_path = self.directory / "cli.jtl"
        xml_path = self.directory / "cli.xml"
        generate_jtl.main(["--rows", "300", "--seed", "1", "--output", str(csv_path), "--profile", "realistic-csv"])
        generate_jtl.main(["--rows", "300", "--seed", "1", "--output", str(xml_path), "--profile", "realistic-xml"])
        self.assertTrue(csv_path.read_text(encoding="utf-8").startswith(JMETER_CSV_HEADER + "\n"))
        self.assertTrue(xml_path.read_text(encoding="utf-8").startswith("<?xml"))

        with mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            generate_jtl.main(["--rows", "10", "--seed", "1", "--output", str(csv_path), "--labels", "300"])
        with mock.patch("sys.stderr", new=io.StringIO()), self.assertRaises(SystemExit):
            generate_jtl.main(
                ["--rows", "10", "--seed", "1", "--output", str(csv_path), "--profile", "realistic-csv", "--labels", "10"]
            )

    def test_xml_and_csv_sizes_are_comparable_at_the_probe_row_counts(self) -> None:
        probe = (ROOT / "tools/perf/jtl_probe.sh").read_text(encoding="utf-8")
        csv_rows, xml_rows = 100_000, 50_000
        self.assertIn("realistic-csv) warmup_rows=1000000; rows=10000000;", probe)
        self.assertIn("realistic-xml) warmup_rows=500000; rows=5000000;", probe)
        csv_path = self.directory / "size.jtl"
        xml_path = self.directory / "size.xml"
        generate_jtl.generate_realistic_csv(csv_rows, 1, csv_path)
        generate_jtl.generate_realistic_xml(xml_rows, 1, xml_path)
        ratio = xml_path.stat().st_size / csv_path.stat().st_size
        self.assertTrue(0.75 <= ratio <= 1.25, ratio)

    def test_probe_default_path_keeps_its_parameters_and_profile_is_validated(self) -> None:
        probe = (ROOT / "tools/perf/jtl_probe.sh").read_text(encoding="utf-8")

        self.assertIn('LTV_PROBE_PROFILE:-basic', probe)
        self.assertIn('basic) warmup_rows=1000000; rows=10000000', probe)
        self.assertIn('--rows "$warmup_rows" --seed 1 --output "$WORK_DIR/warmup.jtl"', probe)
        self.assertIn('--rows "$rows" --seed 1 --output "$WORK_DIR/benchmark.jtl"', probe)
        self.assertIn("unknown LTV_PROBE_PROFILE", probe)


if __name__ == "__main__":
    unittest.main()

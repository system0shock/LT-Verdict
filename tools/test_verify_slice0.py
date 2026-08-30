"""Regression checks for the Slice 0 verifier."""

import contextlib
import io
import json
import re
import tempfile
import unittest
from pathlib import Path

from tools import verify_slice0


ROOT = Path(__file__).resolve().parents[1]


class PortablePathTests(unittest.TestCase):
    def test_schema_and_verifier_reject_nonportable_paths(self) -> None:
        schema = json.loads(
            (ROOT / "docs/contracts/run/v1/run.schema.json").read_text(
                encoding="utf-8"
            )
        )
        pattern = schema["properties"]["inputs"]["items"]["properties"]["path"][
            "pattern"
        ]

        self.assertEqual(
            pattern,
            getattr(verify_slice0, "PORTABLE_PATH_PATTERN", None),
        )
        for path in (
            "NUL",
            "con.txt",
            "bad?.jtl",
            "dir/trailing.",
            "dir/trailing ",
            "a//b",
            "../outside",
            "/absolute",
            "dir\\file",
            "C:/drive",
        ):
            with self.subTest(path=path):
                self.assertIsNone(re.fullmatch(pattern, path))
                with self.assertRaisesRegex(ValueError, "portable"):
                    verify_slice0.verify_input(
                        {"path": path, "sha256": "0" * 64}
                    )


class DateTimeTests(unittest.TestCase):
    def run_with_timestamp(self, field: str, value: str) -> tuple[int, str]:
        source = ROOT / "docs/contracts/run/v1/run.schema.json"
        schema = json.loads(source.read_text(encoding="utf-8"))
        schema["examples"][0][field] = value
        original_schemas = verify_slice0.SCHEMAS

        with tempfile.TemporaryDirectory() as temp:
            changed = Path(temp) / "run.schema.json"
            changed.write_text(json.dumps(schema), encoding="utf-8")
            verify_slice0.SCHEMAS = (changed, original_schemas[1])
            output = io.StringIO()
            try:
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(
                    output
                ):
                    result = verify_slice0.main()
            finally:
                verify_slice0.SCHEMAS = original_schemas

        return result, output.getvalue()

    def test_main_enforces_rfc3339_profile_for_both_timestamps(self) -> None:
        schema = json.loads(
            (ROOT / "docs/contracts/run/v1/run.schema.json").read_text(
                encoding="utf-8"
            )
        )
        for field in ("started_at", "ended_at"):
            self.assertEqual(
                schema["properties"][field].get("pattern"),
                verify_slice0.RFC3339_PATTERN,
            )
            for value in (
                "not-a-date",
                "2026-01-01T00:00:00",
                "2026-01-01T24:00:00Z",
                "1990-12-31T23:59:60Z",
                "2026-02-30T00:00:00Z",
            ):
                with self.subTest(field=field, value=value):
                    result, output = self.run_with_timestamp(field, value)
                    self.assertEqual(1, result)
                    self.assertIn("RFC 3339", output)

            with self.subTest(field=field, value="valid-boundary"):
                result, _output = self.run_with_timestamp(
                    field, "2026-01-01t23:59:59.1+23:59"
                )
                self.assertEqual(0, result)


if __name__ == "__main__":
    unittest.main()

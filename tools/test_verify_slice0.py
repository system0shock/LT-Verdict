"""Regression checks for the Slice 0 verifier."""

import json
import re
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


if __name__ == "__main__":
    unittest.main()

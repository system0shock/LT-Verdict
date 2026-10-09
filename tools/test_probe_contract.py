"""Regression checks for the source probe contracts (ADR 0033): every invalid example fails for the reason in its name."""

import json
import unittest

from tools import verify_slice0

# example name -> a fragment the first rejection must contain
EXPECTED = {
    "resource-hash-bad-hash": "$.semantic_sha256: does not match pattern",
    "resource-hash-missing-semantic": "missing semantic_sha256",
    "resource-hash-unknown-field": "unknown field path",
    "source-check-address-field": "unknown field base_url",
    "source-check-environment-name-code": "checks[2].code: not one of",
    "source-check-missing-checks": "missing checks",
    "source-check-unknown-check-id": "checks[0].id: not one of",
    "source-check-unknown-status": "checks[0].status: not one of",
    "source-probe-ad-hoc-mode": "$.mode: must equal 'profile_query'",
    "source-probe-bad-expression-hash": "$.expression_sha256: does not match pattern",
    "source-probe-failed-with-decoder-check": "$.decoder_check: expected null",
    "source-probe-failed-with-series-count": "$.series_count: expected null",
    "source-probe-failed-without-code": "$.code: expected string",
    "source-probe-http-status-out-of-range": "$.http_status: out of range",
    "source-probe-ok-with-code": "$.code: must equal None",
    "source-probe-second-request": "$.request_count: out of range",
    "source-probe-too-many-sample-values": "sample_values: item count out of range",
    "source-probe-unknown-failure-code": "$.code: not one of",
    "source-probe-unknown-field": "unknown field base_url",
    "source-probe-window-over-240-cells": "$.window.cells: out of range",
}


class ProbeContractTests(unittest.TestCase):
    directory = verify_slice0.PROBE_DIR

    def test_contract_verifier_passes(self) -> None:
        verify_slice0.verify_probe_contract()

    def test_every_invalid_example_fails_for_its_named_reason(self) -> None:
        paths = sorted((self.directory / "examples" / "invalid").glob("*.json"))
        self.assertEqual(sorted(path.stem for path in paths), sorted(EXPECTED))
        for path in paths:
            schema_name = "-".join(path.stem.split("-")[:2])
            schema = json.loads((self.directory / f"{schema_name}.schema.json").read_text(encoding="utf-8"))
            errors = verify_slice0.probe_schema_errors(json.loads(path.read_text(encoding="utf-8")), schema)
            self.assertTrue(errors, path.name)
            self.assertTrue(any(EXPECTED[path.stem] in error for error in errors), f"{path.name}: {errors}")

    def test_no_example_carries_an_address_or_secret(self) -> None:
        for path in self.directory.rglob("*.json"):
            if "invalid" in path.parts and path.name in ("source-probe-unknown-field.json", "source-check-address-field.json"):
                continue
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("127.0.0.1", text, path.name)


if __name__ == "__main__":
    unittest.main()

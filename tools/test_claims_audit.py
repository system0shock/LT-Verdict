"""Checks for the C06 whole-report forbidden-claims audit."""

import json
import unittest
from io import StringIO
from pathlib import Path
from unittest.mock import mock_open, patch

import claims_audit


class ClaimsAuditTest(unittest.TestCase):
    def test_structured_and_prose_mutations_are_rejected(self):
        output = {
            "result": {"confidence": "HIGH"},
            "findings": [{"summary": "The experiment proves causality."}],
            "narrative": "The system is healthy.",
            "statistics": {"p_value": "0.01"},
            "interpretation": {"causal_proof": True, "causality": "PROVEN"},
        }
        kinds = {finding["rule"] for finding in claims_audit.audit_output(output)}
        self.assertEqual({"production_p_value", "high_confidence", "causal_claim", "health_claim"}, kinds)
        paths = {finding["path"] for finding in claims_audit.audit_output({"causal_proof": True, "causality": "PROVEN"})}
        self.assertEqual({"/causal_proof", "/causality"}, paths)

    def test_negations_and_non_claim_metadata_are_allowed(self):
        output = {
            "metric": "p_value_high_confidence_causal_health",
            "truth_metadata": {"hidden_cause": "causal", "health": "healthy"},
            "summary": "No p-values, no HIGH confidence, no causal proof, and not healthy.",
            "reasons": ["CAUSALITY_NOT_ESTABLISHED", "NO_HEALTH_CLAIM"],
        }
        self.assertEqual([], claims_audit.audit_output(output))

    def test_jsonl_is_fail_closed_and_cli_writes_counts(self):
        path = Path("actual.jsonl")
        def check(raw):
            return claims_audit.audit_lines(StringIO(raw))
        self.assertEqual({"records": 1, "findings": 0}, check(json.dumps({"id": "ok", "output": {"summary": "No p-values."}}) + "\n")["counts"])
        for raw in [
            '{"id":"broken"\n',
            "",
            json.dumps({"id": "failed", "error": "timeout"}) + "\n",
            json.dumps({"id": "null-output", "output": None}) + "\n",
            json.dumps({"id": "scalar-output", "output": "not an object"}) + "\n",
            '{"id":"overflow","output":{"value":1e999}}\n',
        ]:
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                check(raw)

    def test_cli_creates_a_report_exclusively(self):
        output = Path("audit.json")
        handle = mock_open()
        with patch.object(claims_audit, "audit_jsonl", return_value={"status": "PASS", "counts": {}, "findings": []}), \
             patch.object(Path, "open", handle):
            self.assertEqual(0, claims_audit.main(["--input", "actual.jsonl", "--output", str(output)]))
        self.assertEqual("x", handle.call_args.args[0])


if __name__ == "__main__":
    unittest.main()

"""Regression checks for the Slice 0 verifier."""

import contextlib
import hashlib
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


class IncidentContractTests(unittest.TestCase):
    directory = ROOT / "docs/contracts/incident/v1"

    def examples(self, kind: str) -> list[Path]:
        return sorted((self.directory / "examples" / kind).glob("*.json"))

    def load(self, path: Path) -> object:
        return json.loads(path.read_text(encoding="utf-8"))

    def test_contract_directory_verifies(self) -> None:
        verify_slice0.verify_incident_contract()

    # Every invalid example must fail for the reason its name states, not for an accidental one.
    EXPECTED_REJECTION = {
        "causal-next-check-english": "causal wording",
        "causal-title": "causal wording",
        "coincident-with-missing": "coincident_with must be",
        "coincident-with-unknown-incident": "coincident_with must be",
        "duplicate-negative-check": "unique and follow the fixed order",
        "empty-next-checks": r"next_checks: item count",
        "evaluated-with-reason-code": "forbidden alternative",
        "finding-type-outside-family": "finding_types must be sorted and belong",
        "first-epoch-differs-from-interval-start": "first_epoch_ms must equal",
        "id-is-not-grouping-key-hash": "SHA-256",
        "in-overview-beyond-limit": "in_overview",
        "missing-evidence-ids": r"evidence_ids: item count",
        "negative-evidence-without-outcome": "missing outcome",
        "negative-evidence-wrong-outcome": "wrong outcome",
        "next-checks-do-not-match-derivation": "next_checks must be",
        "not-confirmed-without-evidence": r"evidence_ids: item count",
        "not-evaluated-with-incidents": "must equal 0",
        "not-evaluated-without-reason": "missing reason_code",
        "not-evaluated-without-reason-code": "missing reason_code",
        "omitted-count-below-storage-limit": r"min\(total_count, 64\)",
        "overview-limit-not-7": "overview_limit",
        "policy-not-evaluated-wrong-reason": "wrong reason_code",
        "priority-does-not-match-tier": "priority does not match tier",
        "ranks-not-contiguous": "rank must be",
        "refs-truncated-flag-wrong": "refs_truncated",
        "resource-family-with-overall-scope": "RESOURCE needs an entity scope",
        "resource-with-window-basis": "RESOURCE needs interval_basis FINDINGS",
        "too-many-next-checks": r"next_checks: item count",
        "total-count-mismatch": r"min\(total_count, 64\)",
        "transaction-link-with-interval-overlap": "coincident_with must be",
        "unknown-field-candidate-subsystem": "unknown field candidate_subsystem",
        "unknown-field-confidence": "unknown field confidence",
        "unknown-interval-with-values": "interval_basis UNKNOWN",
        "unknown-sample-kind": "exactly one alternative",
        "unsorted-by-priority": "ordered by priority_key",
        "unsorted-evidence-ids": "sorted by UTF-8",
        "wrong-schema-version": "schema_version",
    }

    def test_every_valid_example_passes_and_every_invalid_one_is_rejected_for_its_reason(self) -> None:
        schema = self.load(self.directory / "incident.schema.json")
        valid = self.examples("valid")
        invalid = self.examples("invalid")
        self.assertGreaterEqual(len(valid), 8)
        self.assertEqual(set(self.EXPECTED_REJECTION), {path.stem for path in invalid})
        for path in valid:
            with self.subTest(valid=path.name):
                verify_slice0.verify_incident_document(self.load(path), schema)
        for path in invalid:
            with self.subTest(invalid=path.name):
                with self.assertRaisesRegex(ValueError, self.EXPECTED_REJECTION[path.stem]):
                    verify_slice0.verify_incident_document(self.load(path), schema)

    def test_incident_id_is_the_hash_of_the_canonical_grouping_key(self) -> None:
        schema = self.load(self.directory / "incident.schema.json")
        document = self.load(self.directory / "examples/valid/transaction-and-resource-in-window.json")
        key = document["items"][0]["grouping"]["key"]
        canonical = json.dumps(key, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        self.assertEqual(
            document["items"][0]["id"],
            "incident-" + hashlib.sha256(canonical.encode("utf-8")).hexdigest(),
        )
        document["items"][0]["grouping"]["key"]["window_id"] = "other"
        with self.assertRaisesRegex(ValueError, "id"):
            verify_slice0.verify_incident_document(document, schema)

    def test_causal_wording_is_refused_and_coincidence_wording_is_accepted(self) -> None:
        for text in (
            "Нарушение из-за нехватки CPU",
            "Вызвано насыщением пула",
            "Первопричина: GC",
            "This is the root cause",
            "Latency rose because CPU rose",
            "Leads to timeouts",
            "Привело к росту p95",
            "Так как пул мал",
        ):
            with self.subTest(text=text):
                self.assertIsNotNone(verify_slice0.CAUSAL_WORDING.search(text))
        for text in (
            "На интервале совпали по времени находки по сущности host-a: 3.",
            "Открыть сигналы окна steady, совпавшие по времени: 1.",
            "Остальные проверки policy в окне steady выполнены: 3.",
        ):
            with self.subTest(text=text):
                self.assertIsNone(verify_slice0.CAUSAL_WORDING.search(text))

    def test_schema_checker_refuses_keywords_it_does_not_implement(self) -> None:
        with self.assertRaisesRegex(ValueError, "unsupported"):
            verify_slice0.schema_errors({}, {"type": "object", "patternProperties": {}}, {})

    def test_schema_checker_walks_unused_branches_and_refuses_ref_siblings(self) -> None:
        for schema in (
            {"type": "object", "properties": {"unused": {"patternProperties": {}}}},
            {"type": "object", "$defs": {"unused": {"format": "date"}}},
            {"type": "object", "properties": {"a": {"$ref": "#/$defs/x", "maxLength": 1}}, "$defs": {"x": {"type": "string"}}},
        ):
            with self.subTest(schema=schema):
                with self.assertRaisesRegex(ValueError, "unsupported"):
                    verify_slice0.schema_errors({}, schema, schema)

    def test_causal_wording_covers_forms_missed_by_the_first_pattern(self) -> None:
        for text in ("привел к задержке", "triggering timeouts", "causality", "resulting from CPU saturation"):
            with self.subTest(text=text):
                self.assertIsNotNone(verify_slice0.CAUSAL_WORDING.search(text))

    def test_check_wording_false_accepts_a_user_name_that_looks_causal_and_nothing_else(self) -> None:
        schema = self.load(self.directory / "incident.schema.json")
        document = self.load(self.directory / "examples/valid/transaction-and-resource-in-window.json")
        document["items"][0]["title"] = "Нарушение SLA: транзакция cause-list в окне steady"
        with self.assertRaisesRegex(ValueError, "causal wording"):
            verify_slice0.verify_incident_document(document, schema)
        verify_slice0.verify_incident_document(document, schema, check_wording=False)
        document["items"][0]["rank"] = 7
        with self.assertRaisesRegex(ValueError, "rank must be"):
            verify_slice0.verify_incident_document(document, schema, check_wording=False)

    def test_result_schema_reference_is_the_id_of_the_incident_schema_and_optional(self) -> None:
        verify_slice0.verify_result_incident_reference()
        result = self.load(ROOT / "docs/contracts/result/v1/analysis-result.schema.json")
        incident = self.directory / "incident.schema.json"
        cases = {
            "wrong reference": lambda r: r["properties"].update(incidents={"$ref": "https://lt-verdict.local/other.json"}),
            "missing": lambda r: r["properties"].pop("incidents"),
            "required": lambda r: r["required"].append("incidents"),
            "sibling keyword": lambda r: r["properties"]["incidents"].update(type="object"),
        }
        for name, break_it in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as temp:
                broken = json.loads(json.dumps(result))
                break_it(broken)
                path = Path(temp) / "analysis-result.schema.json"
                path.write_text(json.dumps(broken), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "incidents"):
                    verify_slice0.verify_result_incident_reference(path, incident)


if __name__ == "__main__":
    unittest.main()

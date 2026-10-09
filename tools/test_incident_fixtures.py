"""Independent check of the golden outputs of the incident synthesis (W3.7, ADR 0029).

The Kotlin test IncidentSynthesisTest writes fixtures/incidents/<case>/expected.json from the core. This test does not trust that code:
it runs the contract verifier (schema, order, ranks, identifiers, links, derived next checks) over the files and re-checks them against the
input.json of the same case (references resolve, areas, windows and intervals follow from the findings) and against the text templates of
ADR 0029 written here once more. A case named names-* carries user-chosen names: the causal-wording check is switched off for it.
"""

import json
import re
import unittest
from pathlib import Path

from tools import verify_slice0


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "fixtures/incidents"
SCHEMA = ROOT / "docs/contracts/incident/v1/incident.schema.json"
INCIDENT_EXAMPLES = ROOT / "docs/contracts/incident/v1/examples/valid"

AREA = r"(?:весь прогон|транзакция .+)"
AREA_GENITIVE = r"(?:всего прогона|транзакции .+)"
# ADR 0029, "Шаблоны заголовка и сводки", "Отрицательные свидетельства", "Следующие проверки"; {n} is a number, names are free text.
TEXTS = {
    "title": (
        rf"Нарушение SLA: {AREA} в окне .+",
        r"Нарушение SLA: весь прогон",
        rf"Нарушение SLA: транзакция .+",
        r"Сигналы ресурса: .+ в окне .+",
        r"Сигналы нагрузки в окне .+",
    ),
    "summary": (
        rf"В окне .+ нарушено правил policy: \d+\. Область: {AREA}\.",
        rf"За весь прогон нарушено правил policy: \d+\. Область: {AREA}\. Время нарушения не определено\.",
        r"На интервале по сущности .+ найдено находок: \d+; их интервалы перекрываются или соприкасаются\.",
        r"На интервале по общей нагрузке найдено находок: \d+; их интервалы перекрываются или соприкасаются\.",
    ),
    "POLICY_NOT_EVALUATED": (r"Правила policy по транзакциям не проверялись: файл нагрузки разобран не полностью\.",),
    "OTHER_POLICY_CHECKS_PASSED": (
        r"Остальные проверки policy в окне .+ выполнены: \d+\.",
        r"Остальные проверки policy выполнены: \d+\.",
    ),
    "RESOURCE_RULES_WITHIN_LIMITS": (r"Правила ресурсов в окне .+ не нарушены: проверок \d+\.",),
    "GENERATOR_RESOURCES_WITHIN_LIMITS": (r"Правила ресурсов нагрузочного генератора в окне .+ не нарушены: проверок \d+\.",),
    "NO_ANOMALY_EPISODES": (r"Проверки отклонений в окне .+ не нашли эпизодов: \d+\.",),
    "NO_MATERIAL_TREND": (r"Проверки трендов в окне .+ не нашли существенного тренда: \d+\.",),
    "RESOURCE_DATA_NOT_PROVIDED": (r"Снимок ресурсов не передан: ресурсные проверки не выполнялись\.",),
    "CHECKS_NOT_EVALUATED": (r"Часть проверок в окне .+ не выполнена: \d+; код: [A-Z][A-Z0-9_]*\.",),
    "COMPARE_WITH_BASELINE": (rf"Сравнить метрики {AREA_GENITIVE} с baseline\.",),
    "OPEN_RESOURCE_SERIES": (
        r"Открыть ряды сущности .+ за интервал находок\.",
        r"Открыть ряды нагрузки за интервал находок\.",
    ),
    "OPEN_SAME_WINDOW_SIGNALS": (r"Открыть сигналы окна .+, совпавшие по времени: \d+\.",),
    "PROVIDE_RESOURCE_SNAPSHOT": (r"Передать снимок ресурсов, чтобы проверить ресурсные сигналы на том же интервале\.",),
    "COMPLETE_NOT_EVALUATED_CHECKS": (r"Устранить недостаток данных для проверок, помеченных как не выполненные\.",),
}
FINDING_TYPES = {"policy_failure", "resource_threshold_violation", "anomaly_episode", "resource_trend"}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def cases() -> list[Path]:
    return sorted(path for path in FIXTURES.iterdir() if path.is_dir())


def matches(kind: str, text: str) -> bool:
    return any(re.fullmatch(pattern, text, re.DOTALL) for pattern in TEXTS[kind])


class IncidentFixtureTests(unittest.TestCase):
    def test_fixtures_exist(self) -> None:
        names = [path.name for path in cases()]
        self.assertGreaterEqual(len(names), 14, names)
        for required in ("limit-twenty-groups", "limit-seventy-groups", "names-long-and-hostile", "window-without-snapshot"):
            self.assertIn(required, names)

    def test_expected_outputs_pass_the_contract_verifier(self) -> None:
        schema = load(SCHEMA)
        checked = 0
        for case in cases():
            expected = case / "expected.json"
            if not expected.exists():
                continue
            with self.subTest(case=case.name):
                verify_slice0.verify_incident_document(load(expected), schema, check_wording=not case.name.startswith("names-"))
                checked += 1
        self.assertGreaterEqual(checked, 7)

    def test_the_inputs_of_the_contract_cases_reproduce_the_documents_of_the_contract(self) -> None:
        # the Kotlin test compares the core with the example; here the example is checked against the input of its case
        for case in cases():
            if not case.name.startswith("contract-"):
                continue
            with self.subTest(case=case.name):
                example = load(INCIDENT_EXAMPLES / f"{case.name.removeprefix('contract-')}.json")
                self.check_against_input(case.name, example, load(case / "input.json"))

    def test_expected_outputs_follow_from_their_inputs_and_use_the_templates(self) -> None:
        for case in cases():
            expected = case / "expected.json"
            if not expected.exists():
                continue
            with self.subTest(case=case.name):
                self.check_against_input(case.name, load(expected), load(case / "input.json"))

    def check_against_input(self, name: str, document: dict, source: dict) -> None:
        findings = {item["id"]: item for item in source["findings"]}
        evidence = {item["id"]: item for item in source["evidence"]}
        self.assertEqual(len(findings), len(source["findings"]), "finding ids are unique")
        self.assertEqual(len(evidence), len(source["evidence"]), "evidence ids are unique")
        if source["run_validity"] == "INVALID":
            self.assertEqual(document["status"], "NOT_EVALUATED")
            self.assertEqual(document["items"], [])
        else:
            self.assertEqual(document["status"], "EVALUATED")
        atoms = 0
        for item in document["items"]:
            key = item["grouping"]["key"]
            family = key["family"]
            atoms += item["finding_count"]
            self.assertTrue(item["finding_ids"] and item["evidence_ids"], item["id"])
            for entry in item["negative_evidence"] + item["next_checks"]:
                for reference in entry["evidence_ids"]:
                    self.assertIn(reference, evidence, f"{name}: {item['id']}")
            for reference in item["finding_ids"]:
                self.assertIn(reference, findings, f"{name}: {item['id']}")
                finding = findings[reference]
                self.assertIn(finding["type"], item["finding_types"])
                self.assertIn(finding["type"], FINDING_TYPES)
                self.assertEqual(finding.get("window_id"), item["window_id"])
                if family == "RESOURCE":
                    self.assertEqual(finding["entity"], item["scope"]["entity"])
                    self.assertLessEqual(item["interval"]["from_epoch_ms"], finding["from_epoch_ms"])
                    self.assertLessEqual(finding["to_epoch_ms"], item["interval"]["to_epoch_ms"])
            for reference in item["evidence_ids"]:
                self.assertIn(reference, evidence, f"{name}: {item['id']}")
            if len(item["finding_ids"]) == item["finding_count"] and family == "RESOURCE":
                rows = [findings[reference] for reference in item["finding_ids"]]
                self.assertEqual(item["interval"]["from_epoch_ms"], min(row["from_epoch_ms"] for row in rows))
                self.assertEqual(item["interval"]["to_epoch_ms"], max(row["to_epoch_ms"] for row in rows))
                self.assertEqual(sorted({row["type"] for row in rows}), item["finding_types"])
            if not name.startswith("names-"):
                self.assertTrue(matches("title", item["title"]), item["title"])
                self.assertTrue(matches("summary", item["summary"]), item["summary"])
                for entry in item["negative_evidence"] + item["next_checks"]:
                    self.assertTrue(matches(entry["check"], entry["text"]), entry["text"])
            if item["scope"].get("kind") == "entity":
                self.assertNotEqual(family, "TRANSACTION")
        relevant = [row for row in source["findings"] if row["type"] in FINDING_TYPES]
        if document["status"] == "EVALUATED":
            self.assertLessEqual(atoms, len(relevant))
            if document["omitted_count"] == 0:
                self.assertEqual(document["total_count"], len(document["items"]))


if __name__ == "__main__":
    unittest.main()

import gzip
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import zipfile

import applicability_score as score
import applicability_inventory as inventory
import stats_validation as oracle


PAIR = "/correlation_pairs/workload-01/association-01/"


class ApplicabilityScoreTests(unittest.TestCase):
    def setUp(self):
        base = Path(__file__).resolve().parents[1] / ".tmp-tests"
        base.mkdir(exist_ok=True)
        self.temp = TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.corpus, self.actual, self.manifest_sha = build_corpus(self.root)

    def test_complete_temporal_contract_passes_and_raw_matches_are_preserved(self):
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("PASS", report["status"])
        self.assertEqual("MATCH", report["checks"]["cases"]["status"])
        self.assertEqual(0, sum(len(case["mismatches"]) for case in report["checks"]["cases"]["cases"]))

    def test_mutated_frozen_hash_is_incomplete(self):
        for filename, field in (("source.zip", "source_zip_sha256"), ("inventory.json", "inventory_sha256")):
            corpus, actual, manifest_sha = build_corpus(self.root / filename.replace(".", "_"))
            if filename == "inventory.json":
                document = json.loads((corpus / filename).read_text(encoding="utf-8"))
                document["debug"] = True
                (corpus / filename).write_bytes(oracle._json_bytes(document))
            else:
                (corpus / filename).write_bytes(b"mutated")
            report = score.score(corpus, manifest_sha, actual)
            with self.subTest(filename=filename):
                self.assertEqual("INCOMPLETE", report["status"])
                self.assertIn(field, report["errors"])

    def test_whitelist_does_not_hide_a_different_mismatch(self):
        records = read_records(self.actual)
        records[0]["output"]["ok"] = False
        self.actual.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("FAIL", report["status"])

    def test_missing_unknown_clock_reason_fails_closed(self):
        records = read_records(self.actual)
        target = next(record for record in records if record["id"] == "TEMPORAL-A-1s-unknown-2000")
        target["output"]["correlation_pairs"]["workload-01"]["association-01"]["reasons"] = []
        self.actual.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("FAIL", report["status"])
        self.assertIn("CLOCK_ALIGNMENT_UNKNOWN", report["errors"])

    def test_corrupt_trace_is_incomplete(self):
        trace = next(self.corpus.glob("*/trace-*.json.gz"))
        trace.write_bytes(b"not gzip")
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("INCOMPLETE", report["status"])

    def test_mutated_contract_is_incomplete(self):
        contract = next(self.corpus.glob("*/contract.json"))
        contract.write_text("{}", encoding="utf-8")
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("INCOMPLETE", report["status"])

    def test_unknown_clock_rejects_explicit_time_order_claim_but_not_lag_evidence(self):
        records = read_records(self.actual)
        target = next(record for record in records if record["id"] == "TEMPORAL-A-1s-unknown-2000")
        pair = target["output"]["correlation_pairs"]["workload-01"]["association-01"]
        pair["best_lag_ms"] = 3000
        pair["time_order_claim"] = "resource leads load"
        self.actual.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
        report = score.score(self.corpus, self.manifest_sha, self.actual)
        self.assertEqual("FAIL", report["status"])
        self.assertIn("time order claim", report["errors"])


def read_records(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def build_corpus(root):
    corpus = root / "corpus"
    configurations = inventory.configurations()
    cases, records = [], []
    for configuration in configurations:
        case_id = configuration["id"]
        exact, output = {"/ok": True}, {"ok": True}
        if configuration["scenario"] == "TEMPORAL":
            step = configuration["variant"].split("-")[1]
            clock = configuration["variant"].split("-")[2]
            expected = "CANDIDATE" if step == "1s" else "INSUFFICIENT_DATA"
            actual = "CANDIDATE" if step == "1s" else "INSUFFICIENT_DATA"
            exact[PAIR + "status"] = expected
            output["correlation_pairs"] = {"workload-01": {"association-01": {
                "status": actual, "reasons": ["CLOCK_ALIGNMENT_UNKNOWN"] if clock == "unknown" else []}}}
        operation = {"id": case_id}
        if configuration["scenario"] == "TEMPORAL" and configuration["variant"].endswith("unknown"):
            operation = {"operation": "analysis", "run": {"diagnostics": {"pairs": [
                {"id": "association-01", "clock_alignment": "unknown", "max_lag_ms": 10_000,
                 "window_ids": ["workload-01"]}]}}}
        cases.append({"id": case_id, "family": configuration["scenario"], "configuration": configuration["variant"],
                      "input": operation, "expected": {"exact": exact, "numeric": {}}})
        records.append({"id": case_id, "output": output})
    oracle.freeze_cases(corpus, cases)
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    for entry, configuration in zip(manifest["cases"], sorted(configurations, key=lambda item: item["id"]), strict=True):
        traces = []
        for index in range(len(configuration["members"])):
            path = corpus / configuration["id"] / f"trace-{index}.json.gz"
            path.parent.mkdir(exist_ok=True)
            payload = f"{configuration['id']}/{index}".encode()
            with gzip.open(path, "wb") as stream:
                stream.write(payload)
            traces.append({"path": f"{configuration['id']}/trace-{index}.json.gz", "uncompressed_sha256": oracle._sha(payload)})
        entry["traces"] = traces
        contract = {"schema_version": "applicability-contract.v1", "case_id": configuration["id"],
                    "scenario": configuration["scenario"], "variant": configuration["variant"],
                    "available_signals": [{"id": "resource", "metric": "resource", "unit": "ratio",
                                           "entity": "service", "role": "system", "aggregation": "interval_mean"}],
                    "topology": [{"cpu_workers": 1} for _ in configuration["members"]],
                    "trace_parameters": [{"scenario": configuration["scenario"]} for _ in configuration["members"]],
                    "expected_observable_facts": ["observable"], "allowed_interpretations": ["descriptive"],
                    "forbidden_interpretations": ["causal proof"], "expected_abstentions": [],
                    "capability_gaps": [], "trace_warnings": [], "status": "PASS",
                    "preflight": {"status": "PASS", "failures": []}}
        contract_path = corpus / configuration["id"] / "contract.json"
        contract_path.write_bytes(oracle._json_bytes(contract))
        entry["contract"] = {"path": f"{configuration['id']}/contract.json", "sha256": oracle._sha(contract_path.read_bytes())}
    (corpus / "manifest.json").write_bytes(oracle._json_bytes(manifest))
    inventory_document = {"configurations": configurations, "planned_cases": 421, "planned_runs": 830, "debug": False}
    (corpus / "inventory.json").write_bytes(oracle._json_bytes(inventory_document))
    with zipfile.ZipFile(corpus / "source.zip", "w"):
        pass
    (corpus / "source.diff").write_bytes(b"")
    preparation = {"source_zip_sha256": oracle._sha((corpus / "source.zip").read_bytes()),
                   "source_diff_sha256": oracle._sha((corpus / "source.diff").read_bytes()), "source_files": {},
                   "inventory_sha256": oracle._sha((corpus / "inventory.json").read_bytes()), "debug": False,
                   "planned_cases": 421, "planned_runs": 830}
    (corpus / "freeze.json").write_bytes(oracle._json_bytes(preparation))
    actual = root / "actual.jsonl"
    actual.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
    return corpus, actual, oracle._sha((corpus / "manifest.json").read_bytes())


if __name__ == "__main__":
    unittest.main()

"""Fail-closed scorer for a frozen applicability corpus; it never executes runs."""

import argparse
import gzip
import json
from pathlib import Path
import zipfile

try:
    from . import applicability_inventory as inventory
    from . import claims_audit
    from . import stats_validation as oracle
except ImportError:  # Direct script execution has no package parent.
    import applicability_inventory as inventory
    import claims_audit
    import stats_validation as oracle


def _read_json(path):
    return oracle._read_json(path.read_bytes())


def _expected_configurations(debug):
    configurations = inventory.configurations()
    if debug:
        configurations = [dict(item, id="DEBUG-" + item["id"], seed=0)
                          for item in configurations if item["seed"] == 2000]
    return configurations


def _contained(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file() or path.is_symlink():
        raise ValueError("invalid corpus path")
    return path


def _manifest_entries(corpus):
    manifest = _read_json(corpus / "manifest.json")
    if manifest.get("schema_version") != "stats-cases.v1" or not isinstance(manifest.get("cases"), list):
        raise ValueError("invalid manifest")
    entries = {entry.get("id"): entry for entry in manifest["cases"] if isinstance(entry, dict)}
    if len(entries) != len(manifest["cases"]) or None in entries:
        raise ValueError("duplicate or invalid manifest IDs")
    return entries


def _record_map(actual):
    records = {}
    for number, line in enumerate(actual.read_text(encoding="utf-8").splitlines(), 1):
        if not line:
            raise ValueError(f"blank actual line {number}")
        record = oracle._read_json(line.encode())
        case_id = record.get("id") if isinstance(record, dict) else None
        if not isinstance(case_id, str) or case_id in records:
            raise ValueError(f"invalid or duplicate actual ID {number}")
        records[case_id] = record
    return records


def _runs(operation):
    if operation.get("operation") == "analysis":
        return [("", operation.get("run"))]
    if operation.get("operation") == "comparison":
        return [("/baseline", operation.get("baseline")), ("/current", operation.get("current"))]
    return []


def _unknown_pairs(corpus, entries):
    result = {}
    for case_id, entry in entries.items():
        operation = _read_json(_contained(corpus, entry["inputs"]["path"]))
        pairs = []
        for prefix, run in _runs(operation):
            for pair in (run or {}).get("diagnostics", {}).get("pairs", []):
                if pair.get("clock_alignment") == "unknown" and pair.get("max_lag_ms", 0) > 0:
                    pairs.extend((prefix, window, pair.get("id")) for window in pair.get("window_ids", []))
        if pairs:
            result[case_id] = pairs
    return result


def _verify_traces(corpus, entries, configurations):
    expected_members = {item["id"]: len(item["members"]) for item in configurations}
    for case_id, entry in entries.items():
        traces = entry.get("traces")
        if not isinstance(traces, list) or len(traces) != expected_members[case_id]:
            raise ValueError("trace inventory mismatch")
        for trace in traces:
            if set(trace) != {"path", "uncompressed_sha256"}:
                raise ValueError("invalid trace record")
            with gzip.open(_contained(corpus, trace["path"]), "rb") as stream:
                if oracle._sha(stream.read()) != trace["uncompressed_sha256"]:
                    raise ValueError("trace hash mismatch")


def _verify_contracts(corpus, entries, configurations):
    by_id = {item["id"]: item for item in configurations}
    gaps = []
    required_lists = ("available_signals", "topology", "trace_parameters", "expected_observable_facts",
                      "allowed_interpretations", "forbidden_interpretations")
    bindings = {"id", "metric", "unit", "entity", "role", "aggregation"}
    for case_id, entry in entries.items():
        reference = entry.get("contract")
        if not isinstance(reference, dict) or set(reference) != {"path", "sha256"}:
            raise ValueError("missing contract reference")
        path = _contained(corpus, reference["path"])
        if oracle._sha(path.read_bytes()) != reference["sha256"]:
            raise ValueError("contract hash mismatch")
        contract = _read_json(path)
        configuration = by_id[case_id]
        if (contract.get("schema_version") != "applicability-contract.v1" or contract.get("case_id") != case_id or
                contract.get("scenario") != configuration["scenario"] or contract.get("variant") != configuration["variant"]):
            raise ValueError("contract identity mismatch")
        if any(not isinstance(contract.get(name), list) or not contract[name] for name in required_lists):
            raise ValueError("vacuous contract list")
        if any(set(signal) != bindings for signal in contract["available_signals"]):
            raise ValueError("invalid available signal bindings")
        if (len(contract["topology"]) != len(configuration["members"]) or
                len(contract["trace_parameters"]) != len(configuration["members"]) or
                any(not isinstance(item, dict) or not item for item in contract["trace_parameters"])):
            raise ValueError("invalid contract parameters")
        if contract.get("trace_warnings") != [] or contract.get("preflight") != {"status": "PASS", "failures": []}:
            raise ValueError("contract preflight")
        if not isinstance(contract.get("capability_gaps"), list):
            raise ValueError("invalid capability gaps")
        if contract["capability_gaps"]:
            if contract.get("status") != "CAPABILITY_GAP":
                raise ValueError("capability gap status")
            gaps.append(case_id)
        elif contract.get("status") != "PASS":
            raise ValueError("contract status")
    return gaps


def _status(output, window="workload-01", pair="association-01"):
    return output.get("correlation_pairs", {}).get(window, {}).get(pair, {}).get("status")


def _has_clock_reason(output, prefix, window, pair):
    try:
        reasons = oracle._at_pointer(output, f"{prefix}/correlation_pairs/{window}/{pair}/reasons")
    except (KeyError, IndexError, TypeError, ValueError):
        return False
    return isinstance(reasons, list) and "CLOCK_ALIGNMENT_UNKNOWN" in reasons


def _time_order_claims(value, path=""):
    """Only explicit assertion fields are forbidden; observed lag evidence remains valid."""
    findings = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}/{key}"
            if key.lower() in {"time_order_claim", "temporal_order_claim", "causal_direction_claim"} and child not in {None, False, ""}:
                findings.append(child_path)
            findings.extend(_time_order_claims(child, child_path))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            findings.extend(_time_order_claims(child, f"{path}/{index}"))
    return findings


def score(corpus, manifest_sha256, actual, debug=False):
    """Return a self-contained verdict; malformed or incomplete evidence stays INCOMPLETE."""
    corpus, actual = Path(corpus), Path(actual)
    report = {"status": "INCOMPLETE", "errors": [], "checks": {}}
    try:
        if not corpus.is_dir() or corpus.is_symlink() or not actual.is_file() or actual.is_symlink():
            raise ValueError("invalid corpus or actual path")
        freeze, inventory_document = _read_json(corpus / "freeze.json"), _read_json(corpus / "inventory.json")
        expected = _expected_configurations(debug)
        planned_cases, planned_runs = len(expected), sum(len(item["members"]) for item in expected)
        if freeze.get("source_zip_sha256") != oracle._sha(_contained(corpus, "source.zip").read_bytes()):
            report["errors"].append("source_zip_sha256")
        if freeze.get("inventory_sha256") != oracle._sha((corpus / "inventory.json").read_bytes()):
            report["errors"].append("inventory_sha256")
        if not debug and "source_diff_sha256" not in freeze:
            report["errors"].append("source_diff_sha256")
        if not debug and "source_files" not in freeze:
            report["errors"].append("source_files")
        if not debug and "source_diff_sha256" in freeze:
            if freeze["source_diff_sha256"] != oracle._sha(_contained(corpus, "source.diff").read_bytes()):
                report["errors"].append("source_diff_sha256")
        if not debug and "source_files" in freeze:
            with zipfile.ZipFile(_contained(corpus, "source.zip")) as archive:
                if set(archive.namelist()) != set(freeze["source_files"]) or any(
                        oracle._sha(archive.read(name)) != digest for name, digest in freeze["source_files"].items()):
                    report["errors"].append("source_files")
        if bool(freeze.get("debug")) != debug or bool(inventory_document.get("debug")) != debug:
            report["errors"].append("debug mode mismatch")
        if (inventory_document.get("configurations") != expected or inventory_document.get("planned_cases") != planned_cases or
                inventory_document.get("planned_runs") != planned_runs):
            report["errors"].append("inventory")
        entries = _manifest_entries(corpus)
        if set(entries) != {item["id"] for item in expected} or len(entries) != planned_cases:
            report["errors"].append("manifest inventory")
        else:
            _verify_traces(corpus, entries, expected)
            contract_gaps = _verify_contracts(corpus, entries, expected)
            if contract_gaps:
                report["contract_capability_gaps"] = contract_gaps
        cases = oracle.check_cases(corpus, manifest_sha256, actual)
        report["checks"]["cases"] = cases
        try:
            claims = claims_audit.audit_jsonl(actual)
            report["checks"]["claims"] = claims
        except ValueError as error:
            report["errors"].append("claims audit: " + str(error))
            claims = None
        records = _record_map(actual)
        if set(records) != set(entries):
            report["errors"].append("actual inventory")
        for case in cases.get("cases", []):
            if case["status"] != "FAIL":
                continue
            for mismatch in case["mismatches"]:
                report["errors"].append("unexpected mismatch")
        for case_id, pairs in _unknown_pairs(corpus, entries).items():
            output = records.get(case_id, {}).get("output", {})
            for prefix, window, pair in pairs:
                if not _has_clock_reason(output, prefix, window, pair):
                    report["errors"].append("CLOCK_ALIGNMENT_UNKNOWN")
            if _time_order_claims(output):
                report["errors"].append("time order claim")
        for schedule in "ABC":
            for clock in ("declared_aligned", "unknown"):
                for step, expected_status in (("1s", "CANDIDATE"), ("10s", "INSUFFICIENT_DATA")):
                    case_id = f"TEMPORAL-{schedule}-{step}-{clock}-2000"
                    if debug:
                        case_id = "DEBUG-" + case_id
                    if _status(records.get(case_id, {}).get("output", {})) != expected_status:
                        report["errors"].append("temporal status")
        if cases.get("errors"):
            report["errors"].append("case execution errors")
        if report["errors"]:
            report["status"] = "INCOMPLETE" if any(error in {"source_zip_sha256", "inventory_sha256", "inventory", "manifest inventory", "actual inventory", "case execution errors"} or error.startswith("claims audit") for error in report["errors"]) else "FAIL"
        elif claims is None:
            report["status"] = "INCOMPLETE"
        elif claims["status"] != "PASS":
            report["status"] = "FAIL"
        elif cases["status"] == "MATCH":
            report["status"] = "PARTIAL" if report.get("contract_capability_gaps") else "PASS"
        else:
            report["status"] = "FAIL"
    except (OSError, KeyError, TypeError, ValueError, gzip.BadGzipFile, zipfile.BadZipFile) as error:
        report["errors"].append(str(error))
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--debug", action="store_true")
    args = parser.parse_args(argv)
    report = score(args.corpus, args.manifest_sha256, args.actual, args.debug)
    try:
        with args.output.open("xb") as stream:
            stream.write(oracle._json_bytes(report))
    except OSError as error:
        parser.error("cannot create output: " + str(error))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

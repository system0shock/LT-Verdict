"""C06 lexical audit of raw statistical-validation JSONL outputs (test-only)."""

import argparse
import json
import math
import re
import sys
from pathlib import Path


# Exact C06 rules frozen in the accompanying audit report.  IDs and evaluator
# truth metadata are deliberately not prose claims.
SKIP_KEYS = {"id", "metric", "metric_id", "series_id", "run_id", "analysis_id", "rule_id", "evidence_id", "truth_metadata"}
STRUCTURED_RULES = {
    "p_value": "production_p_value",
    "pvalue": "production_p_value",
    "p-value": "production_p_value",
}
CAUSAL_VALUES = {"PROVEN", "ESTABLISHED", "CONFIRMED"}
TEXT_RULES = (
    ("production_p_value", re.compile(r"\bp[- ]?values?\b", re.I)),
    ("high_confidence", re.compile(r"\bhigh confidence\b", re.I)),
    ("causal_claim", re.compile(r"\b(?:causal proof|(?:prove[sd]?|establish(?:es|ed)?|demonstrat(?:es|ed)?|confirm(?:s|ed)?) causality|causality (?:is )?(?:proved|established|demonstrated|confirmed)|causes?)\b", re.I)),
    ("health_claim", re.compile(r"\b(?:is |are |status: )?healthy\b", re.I)),
)
NEGATION = re.compile(r"\b(?:no|not|without|never|neither)\b(?:\W+\w+){0,3}\W*$", re.I)


def _is_negated(text, start):
    return bool(NEGATION.search(text[max(0, start - 48):start]))


def audit_output(output):
    """Return C06 findings for production structure and emitted prose only."""
    findings = []

    def visit(value, path=""):
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}/{key}"
                if key in SKIP_KEYS:
                    continue
                rule = STRUCTURED_RULES.get(key.lower())
                if rule and child is not None:
                    findings.append({"path": child_path, "rule": rule, "value": child})
                if key.lower() == "confidence" and child == "HIGH":
                    findings.append({"path": child_path, "rule": "high_confidence", "value": child})
                if key.lower() == "causal_proof" and child is True:
                    findings.append({"path": child_path, "rule": "causal_claim", "value": child})
                if key.lower() == "causality" and child in CAUSAL_VALUES:
                    findings.append({"path": child_path, "rule": "causal_claim", "value": child})
                visit(child, child_path)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, f"{path}/{index}")
        elif isinstance(value, str):
            for rule, pattern in TEXT_RULES:
                for match in pattern.finditer(value):
                    if not _is_negated(value, match.start()):
                        findings.append({"path": path or "/", "rule": rule, "value": match.group(0)})

    visit(output)
    return findings


def _reject_constant(value):
    raise ValueError(f"non-finite JSON value: {value}")


def _finite(value):
    if isinstance(value, float):
        return math.isfinite(value)
    if isinstance(value, dict):
        return all(_finite(child) for child in value.values())
    if isinstance(value, list):
        return all(_finite(child) for child in value)
    return True


def audit_lines(stream):
    findings, seen, records = [], set(), 0
    for line_number, line in enumerate(stream, 1):
        if not line.strip():
            raise ValueError(f"blank line {line_number}")
        try:
            record = json.loads(line, parse_constant=_reject_constant)
        except (json.JSONDecodeError, ValueError) as error:
            raise ValueError(f"malformed JSONL line {line_number}: {error}") from error
        if not isinstance(record, dict) or not isinstance(record.get("id"), str) or not record["id"]:
            raise ValueError(f"invalid record {line_number}")
        if record["id"] in seen or "error" in record or not isinstance(record.get("output"), dict) or not _finite(record):
            raise ValueError(f"incomplete record {line_number}")
        seen.add(record["id"])
        records += 1
        for finding in audit_output(record["output"]):
            findings.append({"id": record["id"], **finding})
    if not records:
        raise ValueError("empty JSONL")
    return {"status": "PASS" if not findings else "FAIL", "counts": {"records": records, "findings": len(findings)}, "findings": findings}


def audit_jsonl(path):
    if not path.is_file():
        raise ValueError("input is not a file")
    with path.open(encoding="utf-8") as stream:
        return audit_lines(stream)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="actual JSONL path, or - for stdin")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        report = audit_lines(sys.stdin) if args.input == "-" else audit_jsonl(Path(args.input))
    except ValueError as error:
        print(f"claims audit: {error}", file=sys.stderr)
        return 2
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if not args.output:
        print(encoded, end="")
        return 0 if report["status"] == "PASS" else 1
    try:
        with args.output.open("x", encoding="utf-8") as stream:
            stream.write(encoded)
    except OSError as error:
        print(f"claims audit: cannot create output: {error}", file=sys.stderr)
        return 2
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

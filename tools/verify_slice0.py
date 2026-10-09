"""Offline exit gate for the minimal Slice 0 contracts."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = (
    ROOT / "docs/contracts/run/v1/run.schema.json",
    ROOT / "docs/contracts/result/v1/analysis-result.schema.json",
)
INCIDENT_DIR = ROOT / "docs/contracts/incident/v1"
PROBE_DIR = ROOT / "docs/contracts/sources/probe/v1"
RESULT_SCHEMA = ROOT / "docs/contracts/result/v1/analysis-result.schema.json"
PORTABLE_PATH_PATTERN = (
    r"^(?!.*(?:^|/)(?:[Cc][Oo][Nn]|[Pp][Rr][Nn]|[Aa][Uu][Xx]|"
    r"[Nn][Uu][Ll]|[Cc][Oo][Mm][1-9]|[Ll][Pp][Tt][1-9])"
    r"(?:\.[A-Za-z0-9_-]+)*(?:/|$))[A-Za-z0-9_-]+"
    r"(?:\.[A-Za-z0-9_-]+)*(?:/[A-Za-z0-9_-]+"
    r"(?:\.[A-Za-z0-9_-]+)*)*$"
)
RFC3339_PATTERN = (
    r"^\d{4}-\d{2}-\d{2}[Tt](?:[01]\d|2[0-3]):"
    r"[0-5]\d:[0-5]\d(?:\.\d+)?"
    r"(?:[Zz]|[+-](?:[01]\d|2[0-3]):[0-5]\d)$"
)


def load_example(path: Path) -> dict[str, object]:
    with path.open(encoding="utf-8") as source:
        schema = json.load(source)

    required = schema.get("required")
    examples = schema.get("examples")
    if not isinstance(required, list) or not all(isinstance(name, str) for name in required):
        raise ValueError(f"{path}: required must be a string array")
    if not isinstance(examples, list) or not examples or not isinstance(examples[0], dict):
        raise ValueError(f"{path}: examples[0] must be an object")

    example = examples[0]
    missing = [name for name in required if name not in example]
    if missing:
        raise ValueError(f"{path}: example misses required fields: {', '.join(missing)}")
    return example


def verify_input(item: object) -> None:
    if not isinstance(item, dict):
        raise ValueError("run.v1 inputs must contain objects")

    relative_path = item.get("path")
    expected_hash = item.get("sha256")
    if not isinstance(relative_path, str) or not isinstance(expected_hash, str):
        raise ValueError("run.v1 input path and sha256 must be strings")
    if expected_hash != expected_hash.lower():
        raise ValueError(f"{relative_path}: sha256 must be lowercase")

    if re.fullmatch(PORTABLE_PATH_PATTERN, relative_path) is None:
        raise ValueError(
            f"{relative_path}: input path must be portable and repository-relative"
        )

    relative = Path(relative_path)
    path = (ROOT / relative).resolve()
    try:
        path.relative_to(ROOT)
    except ValueError as error:
        raise ValueError(f"{relative_path}: input escapes repository root") from error
    if not path.is_file():
        raise ValueError(f"{relative_path}: input must be a regular file")

    actual_hash = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_hash != expected_hash:
        raise ValueError(f"{relative_path}: sha256 mismatch")


def verify_rfc3339(value: object, field: str) -> None:
    if not isinstance(value, str) or re.fullmatch(RFC3339_PATTERN, value) is None:
        raise ValueError(
            f"{field}: must match the LT Verdict RFC 3339 profile with timezone"
        )

    normalized = value[:-1] + "+00:00" if value[-1] in "Zz" else value
    normalized = normalized[:10] + "T" + normalized[11:]
    try:
        datetime.fromisoformat(normalized)
    except ValueError as error:
        raise ValueError(
            f"{field}: must be an RFC 3339 date-time with timezone"
        ) from error


# JSON Schema keywords used by incident.schema.json. The checker refuses any other keyword so that a schema edit cannot
# silently weaken the check (the Slice 0 job has no third-party packages).
_ANNOTATIONS = {"$schema", "$id", "$defs", "title", "description", "examples"}
_KEYWORDS = {
    "type", "const", "enum", "required", "properties", "additionalProperties", "items", "minItems", "maxItems",
    "uniqueItems", "minLength", "maxLength", "minimum", "maximum", "pattern", "oneOf", "allOf", "if", "then", "else",
    "not", "$ref",
}
# ADR 0029: incident texts state what coincided in time, never a cause.
CAUSAL_WORDING = re.compile(
    r"из-за|вследствие|в результате|потому|поэтому|причин|виновн|вызва|вызыв|прив[её]л|привод|привед|привест|"
    r"обусловл|корнев|следстви|благодаря|ответственн|влия|так как|ввиду|в связи с|объясн|"
    r"\bbecause\b|\bcaus\w*|\bdue to\b|\bowing to\b|\broot cause\b|\bleads? to\b|\bled to\b|"
    r"\bresult(?:s|ed|ing)? (?:of|in|from)\b|\bresponsib\w*|\bculprit\b|\bblame\b|\btrigger\w*|\bexplain\w*",
    re.IGNORECASE,
)
_PRIORITY_BY_TIER = {1: "HIGH", 2: "HIGH", 3: "MEDIUM", 4: "MEDIUM", 5: "LOW"}


def _is_type(value: object, name: str) -> bool:
    if name == "object":
        return isinstance(value, dict)
    if name == "array":
        return isinstance(value, list)
    if name == "string":
        return isinstance(value, str)
    if name == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "boolean":
        return isinstance(value, bool)
    if name == "null":
        return value is None
    raise ValueError(f"unsupported type {name}")


def _same(left: object, right: object) -> bool:
    return type(left) is type(right) and left == right


def check_schema_keywords(schema: dict) -> None:
    """Refuses every keyword the checker does not implement, in the whole schema (also in unused branches)."""
    unknown = set(schema) - _KEYWORDS - _ANNOTATIONS
    if unknown:
        raise ValueError(f"unsupported schema keyword: {', '.join(sorted(unknown))}")
    if "$ref" in schema and set(schema) - {"$ref"} - _ANNOTATIONS:
        raise ValueError("unsupported schema keyword: $ref with sibling constraints")
    for name in ("properties", "$defs"):
        for child in schema.get(name, {}).values():
            check_schema_keywords(child)
    for name in ("items", "not", "if", "then", "else"):
        if isinstance(schema.get(name), dict):
            check_schema_keywords(schema[name])
    for name in ("oneOf", "allOf"):
        for child in schema.get(name, []):
            check_schema_keywords(child)


def schema_errors(value: object, schema: dict, root: dict, path: str = "$") -> list[str]:
    if path == "$":
        check_schema_keywords(root)
    unknown = set(schema) - _KEYWORDS - _ANNOTATIONS
    if unknown:
        raise ValueError(f"unsupported schema keyword: {', '.join(sorted(unknown))}")
    if "$ref" in schema:
        target: object = root
        for part in schema["$ref"].removeprefix("#/").split("/"):
            target = target[part]
        return schema_errors(value, target, root, path)

    errors: list[str] = []
    if "type" in schema:
        names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_is_type(value, name) for name in names):
            return [f"{path}: expected {schema['type']}"]
    if "const" in schema and not _same(value, schema["const"]):
        errors.append(f"{path}: must equal {schema['const']!r}")
    if "enum" in schema and not any(_same(value, option) for option in schema["enum"]):
        errors.append(f"{path}: not one of {schema['enum']}")
    if isinstance(value, str):
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", len(value)):
            errors.append(f"{path}: length out of range")
        if "pattern" in schema and re.search(schema["pattern"], value) is None:
            errors.append(f"{path}: does not match pattern")
    if _is_type(value, "integer"):
        if value < schema.get("minimum", value) or value > schema.get("maximum", value):
            errors.append(f"{path}: out of range")
    if isinstance(value, list):
        if len(value) < schema.get("minItems", 0) or len(value) > schema.get("maxItems", len(value)):
            errors.append(f"{path}: item count out of range")
        if schema.get("uniqueItems") and len({json.dumps(item, sort_keys=True) for item in value}) != len(value):
            errors.append(f"{path}: items must be unique")
        if "items" in schema:
            for index, item in enumerate(value):
                errors += schema_errors(item, schema["items"], root, f"{path}[{index}]")
    if isinstance(value, dict):
        for name in schema.get("required", []):
            if name not in value:
                errors.append(f"{path}: missing {name}")
        properties = schema.get("properties", {})
        for name, item in value.items():
            if name in properties:
                errors += schema_errors(item, properties[name], root, f"{path}.{name}")
            elif schema.get("additionalProperties") is False:
                errors.append(f"{path}: unknown field {name}")
    for option in schema.get("allOf", []):
        errors += schema_errors(value, option, root, path)
    if "oneOf" in schema:
        matches = sum(not schema_errors(value, option, root, path) for option in schema["oneOf"])
        if matches != 1:
            errors.append(f"{path}: must match exactly one alternative, matched {matches}")
    if "not" in schema and not schema_errors(value, schema["not"], root, path):
        errors.append(f"{path}: matches a forbidden alternative")
    if "if" in schema:
        branch = "then" if not schema_errors(value, schema["if"], root, path) else "else"
        if branch in schema:
            errors += schema_errors(value, schema[branch], root, path)
    return errors


def _canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _priority_order(item: dict) -> tuple:
    key = item["priority_key"]
    first = key["first_epoch_ms"]
    return key["tier"], -key["finding_count"], (1, 0) if first is None else (0, first), item["id"].encode("utf-8")


_NEGATIVE_ORDER = (
    "POLICY_NOT_EVALUATED",
    "OTHER_POLICY_CHECKS_PASSED",
    "RESOURCE_RULES_WITHIN_LIMITS",
    "GENERATOR_RESOURCES_WITHIN_LIMITS",
    "NO_ANOMALY_EPISODES",
    "NO_MATERIAL_TREND",
    "RESOURCE_DATA_NOT_PROVIDED",
    "CHECKS_NOT_EVALUATED",
)
_NOT_EVALUATED_REASON = {
    "POLICY_NOT_EVALUATED": "RUN_DEGRADED",
    "RESOURCE_DATA_NOT_PROVIDED": "RESOURCE_SNAPSHOT_NOT_PROVIDED",
    "CHECKS_NOT_EVALUATED": None,
}
_FINDING_TYPES = {
    "TRANSACTION": {"policy_failure"},
    "RESOURCE": {"anomaly_episode", "resource_threshold_violation", "resource_trend"},
}
_MAX_STORED = 64


def _utf8_sorted(values: list[str]) -> bool:
    return [v.encode("utf-8") for v in values] == sorted(v.encode("utf-8") for v in values)


def _eligible_links(item: dict, items: list[dict]) -> list[dict]:
    """Links the ADR rules prescribe for item, before the limit of five."""
    key = item["grouping"]["key"]
    links = []
    for other in items:
        other_key = other["grouping"]["key"]
        if other is item or key["window_id"] is None or key["window_id"] != other_key["window_id"]:
            continue
        if key["family"] != other_key["family"]:
            basis = "SAME_WINDOW"
        elif key["family"] == "RESOURCE" and key["scope"] != other_key["scope"]:
            a, b = item["interval"], other["interval"]
            if not (a["from_epoch_ms"] < b["to_epoch_ms"] and b["from_epoch_ms"] < a["to_epoch_ms"]):
                continue
            basis = "INTERVAL_OVERLAP"
        else:
            continue
        links.append({"incident_id": other["id"], "basis": basis})
    return links


def verify_incident_semantics(document: dict, check_wording: bool = True) -> None:
    """Checks of incident.v1 that JSON Schema cannot express (see the schema description).

    check_wording=False skips the causal-wording check of the rendered texts: transaction and entity names are chosen by the user
    (a transaction may be called cause-list), so the check applies to the examples of the contract and to fixtures with neutral names.
    """
    items = document["items"]
    if len(items) != min(document["total_count"], _MAX_STORED) or document["omitted_count"] != document["total_count"] - len(items):
        raise ValueError("incident items must hold min(total_count, 64) incidents and omitted_count the rest")
    ids = [item["id"] for item in items]
    if len(set(ids)) != len(ids):
        raise ValueError("incident ids must be unique")
    rank_of = {item["id"]: index for index, item in enumerate(items)}
    for index, item in enumerate(items):
        name = f"incident {item['id']}"
        key = item["grouping"]["key"]
        family = key["family"]
        if item["id"] != "incident-" + hashlib.sha256(_canonical(key)).hexdigest():
            raise ValueError(f"{name}: id must be the SHA-256 of the canonical grouping key")
        if item["scope"] != key["scope"] or item["window_id"] != key["window_id"]:
            raise ValueError(f"{name}: scope and window_id must equal the grouping key")
        if item["rank"] != index + 1:
            raise ValueError(f"{name}: rank must be {index + 1}")
        if item["in_overview"] != (item["rank"] <= document["overview_limit"]):
            raise ValueError(f"{name}: in_overview must be true exactly for rank <= overview_limit")
        if item["priority"] != _PRIORITY_BY_TIER[item["priority_key"]["tier"]]:
            raise ValueError(f"{name}: priority does not match tier")
        if index and _priority_order(items[index - 1]) > _priority_order(item):
            raise ValueError(f"{name}: items must be ordered by priority_key")
        if item["priority_key"]["finding_count"] != item["finding_count"]:
            raise ValueError(f"{name}: priority_key.finding_count must equal finding_count")
        interval = item["interval"]
        if item["priority_key"]["first_epoch_ms"] != (None if interval is None else interval["from_epoch_ms"]):
            raise ValueError(f"{name}: priority_key.first_epoch_ms must equal the interval start")
        if (item["interval_basis"] == "UNKNOWN") != (interval is None):
            raise ValueError(f"{name}: interval_basis UNKNOWN exactly when interval is null")
        if interval is not None and interval["from_epoch_ms"] >= interval["to_epoch_ms"]:
            raise ValueError(f"{name}: interval must have from < to")
        if family == "RESOURCE":
            if key["scope"]["kind"] != "entity" or interval is None or key["cluster_from_epoch_ms"] != interval["from_epoch_ms"]:
                raise ValueError(f"{name}: RESOURCE needs an entity scope and cluster_from equal to interval start")
            if item["interval_basis"] != "FINDINGS" or item["window_id"] is None:
                raise ValueError(f"{name}: RESOURCE needs interval_basis FINDINGS and a window_id")
        else:
            if key["scope"]["kind"] == "entity" or key["cluster_from_epoch_ms"] is not None:
                raise ValueError(f"{name}: TRANSACTION needs an overall or transaction scope and no cluster_from")
            if item["interval_basis"] != ("WINDOW" if item["window_id"] is not None else "UNKNOWN"):
                raise ValueError(f"{name}: TRANSACTION interval_basis is WINDOW with a window_id, otherwise UNKNOWN")
        if not set(item["finding_types"]) <= _FINDING_TYPES[family] or not _utf8_sorted(item["finding_types"]):
            raise ValueError(f"{name}: finding_types must be sorted and belong to the family")
        if len(item["finding_ids"]) > item["finding_count"]:
            raise ValueError(f"{name}: finding_ids exceed finding_count")
        cut = item["finding_count"] > len(item["finding_ids"]) or len(item["evidence_ids"]) == 24
        if item["finding_count"] > len(item["finding_ids"]) and not item["refs_truncated"]:
            raise ValueError(f"{name}: refs_truncated must be true when finding_ids are cut")
        if item["refs_truncated"] and not cut:
            raise ValueError(f"{name}: refs_truncated without a cut list")
        if not _utf8_sorted(item["evidence_ids"]):
            raise ValueError(f"{name}: evidence_ids must be sorted by UTF-8 bytes")
        checks = [entry["check"] for entry in item["negative_evidence"]]
        positions = [_NEGATIVE_ORDER.index(c) for c in checks]
        if positions != sorted(set(positions)):
            raise ValueError(f"{name}: negative_evidence must be unique and follow the fixed order")
        for entry in item["negative_evidence"]:
            if not _utf8_sorted(entry["evidence_ids"]):
                raise ValueError(f"{name}: negative_evidence evidence_ids must be sorted")
            expected_outcome = "NOT_EVALUATED" if entry["check"] in _NOT_EVALUATED_REASON else "NOT_CONFIRMED"
            if entry["outcome"] != expected_outcome:
                raise ValueError(f"{name}: {entry['check']} has the wrong outcome")
            fixed_reason = _NOT_EVALUATED_REASON.get(entry["check"])
            if fixed_reason is not None and entry.get("reason_code") != fixed_reason:
                raise ValueError(f"{name}: {entry['check']} has the wrong reason_code")
        if "POLICY_NOT_EVALUATED" in checks and family != "RESOURCE":
            raise ValueError(f"{name}: POLICY_NOT_EVALUATED belongs to RESOURCE incidents")
        if "OTHER_POLICY_CHECKS_PASSED" in checks and family != "TRANSACTION":
            raise ValueError(f"{name}: OTHER_POLICY_CHECKS_PASSED belongs to TRANSACTION incidents")
        expected_next = ["COMPARE_WITH_BASELINE" if family == "TRANSACTION" else "OPEN_RESOURCE_SERIES"]
        if item["coincident_with"]:
            expected_next.append("OPEN_SAME_WINDOW_SIGNALS")
        if "RESOURCE_DATA_NOT_PROVIDED" in checks:
            expected_next.append("PROVIDE_RESOURCE_SNAPSHOT")
        if "CHECKS_NOT_EVALUATED" in checks:
            expected_next.append("COMPLETE_NOT_EVALUATED_CHECKS")
        if [entry["check"] for entry in item["next_checks"]] != expected_next:
            raise ValueError(f"{name}: next_checks must be {expected_next}")
        for entry in item["next_checks"]:
            if not _utf8_sorted(entry["evidence_ids"]):
                raise ValueError(f"{name}: next_checks evidence_ids must be sorted")
        expected_links = sorted(_eligible_links(item, items), key=lambda link: rank_of[link["incident_id"]])[:5]
        if item["coincident_with"] != expected_links:
            raise ValueError(f"{name}: coincident_with must be the first five eligible incidents by rank")
        if not check_wording:
            continue
        texts = [item["title"], item["summary"]]
        texts += [entry["text"] for entry in item["negative_evidence"] + item["next_checks"]]
        for text in texts:
            if CAUSAL_WORDING.search(text):
                raise ValueError(f"{name}: causal wording in {text!r}")


def verify_incident_document(document: object, schema: dict, check_wording: bool = True) -> None:
    errors = schema_errors(document, schema, schema)
    if errors:
        raise ValueError("incident.v1: " + "; ".join(errors[:5]))
    verify_incident_semantics(document, check_wording)


def verify_incident_contract(directory: Path = INCIDENT_DIR) -> None:
    with (directory / "incident.schema.json").open(encoding="utf-8") as source:
        schema = json.load(source)
    for kind, must_fail in (("valid", False), ("invalid", True)):
        paths = sorted((directory / "examples" / kind).glob("*.json"))
        if not paths:
            raise ValueError(f"incident.v1: no {kind} examples")
        for path in paths:
            with path.open(encoding="utf-8") as source:
                document = json.load(source)
            try:
                verify_incident_document(document, schema)
            except ValueError as error:
                if not must_fail:
                    raise ValueError(f"{path.name}: valid example rejected: {error}") from error
            else:
                if must_fail:
                    raise ValueError(f"{path.name}: invalid example accepted")


def probe_schema_errors(document: object, schema: dict) -> list[str]:
    return schema_errors(document, schema, schema)


def verify_probe_contract(directory: Path = PROBE_DIR) -> None:
    """ADR 0033: source-probe.v1, source-check.v1 and resource-hash.v1; examples are named <schema>-<reason>.json."""
    for name in ("source-probe", "source-check", "resource-hash"):
        with (directory / f"{name}.schema.json").open(encoding="utf-8") as source:
            schema = json.load(source)
        for kind, must_fail in (("valid", False), ("invalid", True)):
            paths = sorted((directory / "examples" / kind).glob(f"{name}-*.json"))
            if not paths:
                raise ValueError(f"{name}: no {kind} examples")
            for path in paths:
                with path.open(encoding="utf-8") as source:
                    errors = probe_schema_errors(json.load(source), schema)
                if errors and not must_fail:
                    raise ValueError(f"{path.name}: valid example rejected: {'; '.join(errors[:3])}")
                if not errors and must_fail:
                    raise ValueError(f"{path.name}: invalid example accepted")


def verify_result_incident_reference(
    result_path: Path = RESULT_SCHEMA, incident_path: Path = INCIDENT_DIR / "incident.schema.json"
) -> None:
    """analysis-result.v1 may carry the incidents field: its $ref must be the $id of the incident schema, and the field is optional."""
    with result_path.open(encoding="utf-8") as source:
        result = json.load(source)
    with incident_path.open(encoding="utf-8") as source:
        incident_id = json.load(source).get("$id")
    reference = result.get("properties", {}).get("incidents")
    if reference != {"$ref": incident_id} or not isinstance(incident_id, str):
        raise ValueError("analysis-result.v1: properties.incidents must be exactly the $ref to the $id of the incident schema")
    if "incidents" in result.get("required", []):
        raise ValueError("analysis-result.v1: incidents must stay optional")


def main() -> int:
    try:
        run, _result = (load_example(path) for path in SCHEMAS)
        inputs = run.get("inputs")
        if not isinstance(inputs, list) or not inputs:
            raise ValueError("run.v1 inputs must be a non-empty array")
        for field in ("started_at", "ended_at"):
            verify_rfc3339(run.get(field), field)
        for item in inputs:
            verify_input(item)
        verify_incident_contract()
        verify_probe_contract()
        verify_result_incident_reference()
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as error:
        print(f"slice 0 verification: FAIL: {error}", file=sys.stderr)
        return 1

    print("slice 0 verification: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

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
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as error:
        print(f"slice 0 verification: FAIL: {error}", file=sys.stderr)
        return 1

    print("slice 0 verification: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

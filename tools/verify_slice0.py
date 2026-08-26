"""Offline exit gate for the minimal Slice 0 contracts."""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = (
    ROOT / "docs/contracts/run/v1/run.schema.json",
    ROOT / "docs/contracts/result/v1/analysis-result.schema.json",
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

    relative = Path(relative_path)
    if (
        "\\" in relative_path
        or ":" in relative_path
        or relative.is_absolute()
        or ".." in relative.parts
    ):
        raise ValueError(
            f"{relative_path}: input path must be portable and repository-relative"
        )

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


def main() -> int:
    try:
        run, _result = (load_example(path) for path in SCHEMAS)
        inputs = run.get("inputs")
        if not isinstance(inputs, list) or not inputs:
            raise ValueError("run.v1 inputs must be a non-empty array")
        for item in inputs:
            verify_input(item)
    except (OSError, json.JSONDecodeError, TypeError, ValueError) as error:
        print(f"slice 0 verification: FAIL: {error}", file=sys.stderr)
        return 1

    print("slice 0 verification: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

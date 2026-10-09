"""Generate deterministic JMeter result data without retaining rows.

The default profile writes the compact four-column CSV that the CI probe has always used.
The realistic profiles write a full JMeter 5.6.3 CSV (17 columns) or XML with 200-500 labels.
"""

import argparse
from collections.abc import Iterator
from pathlib import Path
from xml.sax.saxutils import escape


HEADER = b"timeStamp,elapsed,label,success\n"
MASK_64 = (1 << 64) - 1

REALISTIC_CSV_HEADER = (
    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,"
    "bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n"
)
MIN_LABELS, MAX_LABELS, DEFAULT_LABELS = 200, 500, 300
MAX_ERROR_PERMILLE, DEFAULT_ERROR_PERMILLE = 200, 20
RESOURCES = ("orders", "items", "users", "carts", "payments", "search", "catalog", "invoices", "reports", "sessions")
METHODS = ("GET", "GET", "GET", "POST", "PUT")
SUCCESS_RESPONSES = {"GET": ("200", "OK"), "POST": ("201", "Created"), "PUT": ("204", "No Content")}
FAILURE_RESPONSES = (
    ("500", "Internal Server Error", "Test failed: code expected to equal /200/"),
    ("503", "Service Unavailable", "Test failed: text expected to contain /ok/, got /unavailable/"),
    ("404", "Not Found", 'Test failed: text expected to contain /"id"/'),
    ("500", "Internal Server Error", "Test failed: code expected to equal /200/"),
)
BASE_TIMESTAMP = 1704067200000
ACTIVE_THREADS = 50


def next_value(state: int) -> int:
    state ^= state >> 12
    state ^= (state << 25) & MASK_64
    state ^= state >> 27
    return state & MASK_64


def generate(rows: int, seed: int, output: Path) -> None:
    if rows < 0:
        raise ValueError("rows must be non-negative")
    if not 0 < seed <= MASK_64:
        raise ValueError("seed must be between 1 and 2^64-1")

    state = seed
    with output.open("wb") as stream:
        stream.write(HEADER)
        for index in range(rows):
            state = next_value(state)
            elapsed = 20 + (state % 180)
            if (index + seed) % 25 == 0:
                elapsed += 1000
            success = b"false" if (index + seed) % 20 == 0 else b"true"
            stream.write(
                f"{1704067200000 + index * 10},{elapsed},GET /api/orders,".encode("ascii")
                + success
                + b"\n"
            )


def csv_field(value: str) -> str:
    if any(character in value for character in ',"\r\n'):
        return '"' + value.replace('"', '""') + '"'
    return value


def realistic_labels(count: int) -> list[str]:
    """Label names depend only on the count: ASCII API calls, some with a comma, some non-ASCII."""
    labels = []
    for index in range(count):
        if index % 53 == 7:
            labels.append(f"Оплата заказа {index:03d}")
            continue
        method = METHODS[index % len(METHODS)]
        suffix = ", detail" if index % 37 == 5 else ""
        labels.append(f"{method} /api/v1/{RESOURCES[index % len(RESOURCES)]}/{index:03d}{suffix}")
    return labels


def request_method(label: str) -> str:
    return label.partition(" ")[0] if label.isascii() else "GET"


def request_url(label: str) -> str:
    if not label.isascii():
        return "null"
    return "http://app.example.test" + label.partition(" ")[2].partition(",")[0]


def realistic_samples(
    rows: int, seed: int, labels: int, error_permille: int
) -> Iterator[tuple[int, int, int, int | None, int, int]]:
    """Yield (timestamp, elapsed, label_index, failure_index_or_None, thread, bytes)."""
    if rows < 0:
        raise ValueError("rows must be non-negative")
    if not 0 < seed <= MASK_64:
        raise ValueError("seed must be between 1 and 2^64-1")
    if not MIN_LABELS <= labels <= MAX_LABELS:
        raise ValueError(f"labels must be between {MIN_LABELS} and {MAX_LABELS}")
    if not 0 <= error_permille <= MAX_ERROR_PERMILLE:
        raise ValueError(f"error-permille must be between 0 and {MAX_ERROR_PERMILLE}")

    return _realistic_rows(rows, seed, labels, error_permille)


def _realistic_rows(
    rows: int, seed: int, labels: int, error_permille: int
) -> Iterator[tuple[int, int, int, int | None, int, int]]:
    state = seed
    for index in range(rows):
        state = next_value(state)
        label_index = (state >> 8) % labels
        elapsed = 20 + state % 180 + (label_index % 9) * 25
        if (state >> 32) % 100 == 0:
            elapsed += 1000
        failure = (state >> 44) % 4 if (state >> 20) % 1000 < error_permille else None
        thread = 1 + (state >> 52) % ACTIVE_THREADS
        size = 400 + (state >> 12) % 4000
        yield BASE_TIMESTAMP + index * 10, elapsed, label_index, failure, thread, size


def generate_realistic_csv(
    rows: int,
    seed: int,
    output: Path,
    labels: int = DEFAULT_LABELS,
    error_permille: int = DEFAULT_ERROR_PERMILLE,
) -> None:
    samples = realistic_samples(rows, seed, labels, error_permille)
    names = realistic_labels(labels)
    fields = [csv_field(name) for name in names]
    methods = [request_method(name) for name in names]
    urls = [request_url(name) for name in names]
    with output.open("w", encoding="utf-8", newline="") as stream:
        stream.write(REALISTIC_CSV_HEADER)
        for timestamp, elapsed, label_index, failure, thread, size in samples:
            latency = elapsed - elapsed // 4
            connect = 1 if label_index % 50 == 0 else 0
            if failure is None:
                code, message = SUCCESS_RESPONSES[methods[label_index]]
                success, failure_message, received = "true", "", size
            else:
                code, message, text = FAILURE_RESPONSES[failure]
                success, failure_message, received = "false", csv_field(text), 180
            stream.write(
                f"{timestamp},{elapsed},{fields[label_index]},{code},{message},Thread Group 1-{thread},text,"
                f"{success},{failure_message},{received},{300 + label_index % 200},{ACTIVE_THREADS},{ACTIVE_THREADS},"
                f"{urls[label_index]},{latency},0,{connect}\n"
            )


def generate_realistic_xml(
    rows: int,
    seed: int,
    output: Path,
    labels: int = DEFAULT_LABELS,
    error_permille: int = DEFAULT_ERROR_PERMILLE,
) -> None:
    samples = realistic_samples(rows, seed, labels, error_permille)
    names = realistic_labels(labels)
    attributes = [escape(name, {'"': "&quot;"}) for name in names]
    methods = [request_method(name) for name in names]
    urls = [escape(request_url(name)) for name in names]
    with output.open("w", encoding="utf-8", newline="") as stream:
        stream.write('<?xml version="1.0" encoding="UTF-8"?>\n<testResults version="1.2">\n')
        for timestamp, elapsed, label_index, failure, thread, size in samples:
            latency = elapsed - elapsed // 4
            connect = 1 if label_index % 50 == 0 else 0
            if failure is None:
                code, message = SUCCESS_RESPONSES[methods[label_index]]
                success, received, assertion = "true", size, ""
            else:
                code, message, text = FAILURE_RESPONSES[failure]
                success, received = "false", 180
                assertion = (
                    "    <assertionResult>\n      <name>Response Assertion</name>\n      <failure>true</failure>\n"
                    f"      <error>false</error>\n      <failureMessage>{escape(text)}</failureMessage>\n"
                    "    </assertionResult>\n"
                )
            stream.write(
                f'  <httpSample t="{elapsed}" it="0" lt="{latency}" ct="{connect}" ts="{timestamp}" s="{success}" '
                f'lb="{attributes[label_index]}" rc="{code}" rm="{message}" tn="Thread Group 1-{thread}" dt="text" '
                f'by="{received}" sby="{300 + label_index % 200}" ng="{ACTIVE_THREADS}" na="{ACTIVE_THREADS}">\n'
                f"{assertion}    <java.net.URL>{urls[label_index]}</java.net.URL>\n  </httpSample>\n"
            )
        stream.write("</testResults>\n")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rows", type=int, required=True)
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=("basic", "realistic-csv", "realistic-xml"), default="basic")
    parser.add_argument("--labels", type=int, help=f"realistic profiles only, {MIN_LABELS}-{MAX_LABELS}, default {DEFAULT_LABELS}")
    parser.add_argument(
        "--error-permille",
        type=int,
        help=f"realistic profiles only, 0-{MAX_ERROR_PERMILLE}, default {DEFAULT_ERROR_PERMILLE}",
    )
    args = parser.parse_args(argv)
    if args.profile == "basic":
        if args.labels is not None or args.error_permille is not None:
            parser.error("--labels and --error-permille need a realistic profile")
        generate(args.rows, args.seed, args.output)
        return
    options = {
        "labels": DEFAULT_LABELS if args.labels is None else args.labels,
        "error_permille": DEFAULT_ERROR_PERMILLE if args.error_permille is None else args.error_permille,
    }
    writer = generate_realistic_csv if args.profile == "realistic-csv" else generate_realistic_xml
    try:
        writer(args.rows, args.seed, args.output, **options)
    except ValueError as failure:
        parser.error(str(failure))


if __name__ == "__main__":
    main()

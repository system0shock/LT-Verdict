"""Producer of pod-view.v1 (ADR 0020, plan P2d): Prometheus-compatible source or synthetic generator.

Live mode asks one range query per (namespace, service, metric); each query returns every pod of the
service, and the service of a pod is the `workload` of the owner relabel in the query, never a pod-name
prefix. Column k of the file is the interval [start + k * step, start + (k + 1) * step); the right
boundaries start + (k + 1) * step are requested, as PromqlSource does for the snapshot.
Run as `python -m tools.pod_view_adapter ...` from the repository root.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from decimal import Context, Decimal, ROUND_HALF_EVEN

from tools.platform_profile_templates import POD_CPU, CPU_LIMIT, CPU_USAGE, MEM_LIMIT, MEM_USAGE, Signal, render

MAX_VALUE_BYTES = 12
MAX_COLUMNS = 240
MAX_PODS = 256
MAX_ROWS = 2560
MAX_SERVICES = 64
MAX_CONTAINERS = 4
MAX_FILE_BYTES = 12 * 1024 * 1024
MAX_RESPONSE_BYTES = 64 * 1024 * 1024
MAX_IDENTIFIER_BYTES = 128
MAX_POD_BYTES = 253
MAX_EPOCH_MS = 253402300799999
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
CONTROL = re.compile(r"[\x00-\x1f\x7f-\x9f]")
SELECT = re.compile(r"worst:([^:]+):([1-9][0-9]*)")
HEX64 = re.compile(r"[0-9a-f]{64}")
WIDE = Context(prec=80)

CONTAINER_THROTTLING = (
    "sum by (namespace, pod, container) "
    '(rate(container_cpu_cfs_throttled_periods_total{namespace="@ns@",@cont@}[$__interval]) '
    "and on (namespace, pod) @owner@) "
    '/ sum by (namespace, pod, container) (rate(container_cpu_cfs_periods_total{namespace="@ns@",@cont@}[$__interval]) '
    "and on (namespace, pod) @owner@)"
)


def _ratio(usage: str, limit: str) -> str:
    return f"avg_over_time(({usage} / on (namespace, pod, container) {limit})[$__interval:@sub@])"


# metric -> (level, signal); a "container" metric has one row per container, a "pod" metric one row per pod.
METRICS = {
    "openshift_container_cpu_limit_ratio": (
        "container",
        Signal("openshift_container_cpu_limit_ratio", "ratio", "interval_mean", False, False, _ratio(CPU_USAGE, CPU_LIMIT)),
    ),
    "openshift_container_memory_limit_ratio": (
        "container",
        Signal("openshift_container_memory_limit_ratio", "ratio", "interval_mean", False, False, _ratio(MEM_USAGE, MEM_LIMIT)),
    ),
    "openshift_cpu_throttling": (
        "container",
        Signal("openshift_cpu_throttling", "ratio", "interval_mean", False, True, CONTAINER_THROTTLING),
    ),
    "openshift_pod_cpu_usage": (
        "pod",
        Signal("openshift_pod_cpu_usage", "cores", "interval_mean", False, False, POD_CPU),
    ),
}


class AdapterError(Exception):
    pass


class Raw(str):
    """A number written as is (a value token); everything else goes through json."""


@dataclass(frozen=True)
class Observation:
    namespace: str
    service: str
    pod: str
    container: str | None
    metric: str
    values: list  # Raw tokens or None, one per column


def format_value(value: Decimal) -> str:
    """Decimal text without an exponent and within MAX_VALUE_BYTES; the fraction is rounded, an integer part is never cut."""
    if not value.is_finite():
        raise AdapterError("a non-finite value cannot be written")
    sign = "-" if value < 0 else ""
    magnitude = abs(value)
    integer_digits = len(format(magnitude.to_integral_value(rounding="ROUND_DOWN"), "f"))
    room = MAX_VALUE_BYTES - len(sign) - integer_digits - 1
    while room >= -1:
        quantum = Decimal(1).scaleb(-max(room, 0))
        text = format(magnitude.quantize(quantum, rounding=ROUND_HALF_EVEN, context=WIDE), "f")
        if "." in text:
            text = text.rstrip("0").rstrip(".")
        if text == "0":
            return "0"
        if len(sign + text) <= MAX_VALUE_BYTES:
            return sign + text
        room -= 1
    raise AdapterError(f"value {value} does not fit {MAX_VALUE_BYTES} bytes without cutting its integer part")


def seconds(millis: int) -> str:
    return str(millis // 1000) if millis % 1000 == 0 else f"{millis // 1000}.{millis % 1000:03d}"


def dumps(value) -> str:
    """Compact JSON in insertion order with Raw numbers kept verbatim."""
    if isinstance(value, Raw):
        return str(value)
    if isinstance(value, dict):
        return "{" + ",".join(json.dumps(key) + ":" + dumps(item) for key, item in value.items()) + "}"
    if isinstance(value, list):
        return "[" + ",".join(dumps(item) for item in value) + "]"
    return json.dumps(value)


def decode_matrix(body: bytes, start_ms: int, step_ms: int, columns: int) -> list:
    """Series of a Prometheus matrix as (labels, values); the sample at start + (k + 1) * step is column k."""
    try:
        root = json.loads(body.decode("utf-8"), parse_float=Decimal, parse_int=Decimal)
    except (UnicodeDecodeError, ValueError) as error:
        raise AdapterError("MALFORMED_RESPONSE") from error
    if not isinstance(root, dict) or root.get("status") != "success":
        raise AdapterError("PROMQL_QUERY_FAILED")
    if root.get("warnings"):
        raise AdapterError("SOURCE_WARNINGS")
    data = root.get("data")
    if not isinstance(data, dict) or data.get("resultType") != "matrix" or not isinstance(data.get("result"), list):
        raise AdapterError("UNSUPPORTED_RESULT_TYPE")
    first = start_ms + step_ms
    last = start_ms + columns * step_ms
    series = []
    for item in data["result"]:
        if not isinstance(item, dict) or not isinstance(item.get("metric"), dict) or not isinstance(item.get("values"), list):
            raise AdapterError("MALFORMED_RESPONSE")
        values = [None] * columns
        seen = set()
        for sample in item["values"]:
            if not isinstance(sample, list) or len(sample) != 2 or not isinstance(sample[1], str):
                raise AdapterError("MALFORMED_RESPONSE")
            if not isinstance(sample[0], Decimal) or not sample[0].is_finite():
                raise AdapterError("MALFORMED_RESPONSE")
            try:
                timestamp = sample[0] * 1000
                number = Decimal(sample[1])
            except ArithmeticError as error:
                raise AdapterError("MALFORMED_RESPONSE") from error
            if timestamp != timestamp.to_integral_value() or not first <= timestamp <= last or (int(timestamp) - first) % step_ms:
                raise AdapterError("OFF_GRID_TIMESTAMP")
            index = (int(timestamp) - first) // step_ms
            if index in seen:
                raise AdapterError("DUPLICATE_TIMESTAMP")
            seen.add(index)
            # NaN and infinities (for example a ratio over a zero limit) are gaps, not zero.
            values[index] = Raw(format_value(number)) if number.is_finite() else None
        series.append((item["metric"], values))
    return series


def fetch_range(base_url: str, expression: str, start_ms: int, step_ms: int, columns: int, timeout_s: float) -> bytes:
    query = urllib.parse.urlencode(
        {
            "query": expression,
            "start": seconds(start_ms + step_ms),
            "end": seconds(start_ms + columns * step_ms),
            "step": seconds(step_ms),
        }
    )
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/api/v1/query_range?" + query, timeout=timeout_s) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except urllib.error.HTTPError as error:
        raise AdapterError(f"SOURCE_HTTP_{error.code}") from error
    except (urllib.error.URLError, OSError) as error:
        raise AdapterError(f"SOURCE_UNREACHABLE: {error}") from error
    if len(body) > MAX_RESPONSE_BYTES:
        raise AdapterError("RESOURCE_LIMIT_EXCEEDED: response is too large")
    return body


def collect(args) -> list:
    observations = []
    for namespace in args.namespace:
        for service in args.services:
            for name in args.metrics:
                level, signal = METRICS[name]
                expression = render(signal, namespace, service, args.subquery_step).replace("$__interval", f"{args.step_ms}ms")
                body = fetch_range(args.prometheus, expression, args.start_epoch_ms, args.step_ms, args.columns, args.timeout_s)
                for labels, values in decode_matrix(body, args.start_epoch_ms, args.step_ms, args.columns):
                    if labels.get("namespace") != namespace:
                        raise AdapterError(f"{name}: a series of namespace {labels.get('namespace')!r} answered the query of {namespace!r}")
                    pod = labels.get("pod")
                    container = labels.get("container")
                    if not isinstance(pod, str) or not pod:
                        raise AdapterError(f"{name}: a series without a pod label")
                    if level == "container" and (not isinstance(container, str) or not container):
                        raise AdapterError(f"{name}: a series without a container label")
                    observations.append(Observation(namespace, service, pod, container if level == "container" else None, name, values))
    return observations


def role_of(container: str, sidecar: re.Pattern) -> str:
    return "sidecar" if sidecar.fullmatch(container) else "app"


def _utf8(text: str) -> bytes:
    return text.encode("utf-8")


def _check_text(kind: str, value: str, limit: int) -> None:
    if not value or len(_utf8(value)) > limit or CONTROL.search(value):
        raise AdapterError(f"invalid {kind}: {value!r}")


def assemble(observations: list, *, load_sha256: str, snapshot_sha256: str, arm: str | None, start_ms: int, step_ms: int,
             columns: int, sidecar: re.Pattern, select: tuple | None, qualify: bool) -> dict:
    """Builds the document: pods and rows sorted as the core canonicalizes them, row ids numbered after sorting."""
    pods: dict = {}
    rows: dict = {}
    for item in observations:
        name = f"{item.namespace}/{item.pod}" if qualify else item.pod
        _check_text("pod name", name, MAX_POD_BYTES)
        _check_text("service", item.service, MAX_IDENTIFIER_BYTES)
        _check_text("metric", item.metric, MAX_IDENTIFIER_BYTES)
        pod = pods.setdefault(name, {"service": item.service, "containers": set()})
        if pod["service"] != item.service:
            raise AdapterError(f"pod {name} belongs to two services: {pod['service']} and {item.service}")
        if item.container is not None:
            _check_text("container", item.container, MAX_IDENTIFIER_BYTES)
            pod["containers"].add(item.container)
        key = (name, item.container, item.metric)
        if key in rows:
            raise AdapterError(f"DUPLICATE_SERIES: {key}")
        rows[key] = item
    observed_pods = len(pods)
    observed_rows = len(rows)
    coverage_select = {"kind": "ALL"}
    if select is not None:
        metric, limit = select
        score: dict = {}  # the worst pod has the highest maximum of the metric; pods without a sample rank last
        for (name, _container, row_metric), item in rows.items():
            numbers = [Decimal(v) for v in item.values if v is not None]
            if row_metric == metric and numbers:
                score[name] = max(numbers + ([score[name]] if name in score else []))
        ranked = sorted(pods, key=lambda n: (n not in score, -score.get(n, Decimal(0)), _utf8(pods[n]["service"]), _utf8(n)))
        keep = set(ranked[:limit])
        pods = {name: pod for name, pod in pods.items() if name in keep}
        rows = {key: item for key, item in rows.items() if key[0] in keep}
        coverage_select = {"kind": "WORST_BY_METRIC", "metric": metric, "limit": limit}
    elif observed_pods > MAX_PODS:
        raise AdapterError(f"{observed_pods} pods exceed {MAX_PODS}: choose pods with --select worst:<metric>:<limit>")
    if len(rows) > MAX_ROWS:
        raise AdapterError(f"{len(rows)} rows exceed {MAX_ROWS}: narrow --services, --metrics or --select")
    if len({pod["service"] for pod in pods.values()}) > MAX_SERVICES:
        raise AdapterError(f"more than {MAX_SERVICES} services")
    for name, pod in pods.items():
        if len(pod["containers"]) > MAX_CONTAINERS:
            raise AdapterError(f"pod {name} has more than {MAX_CONTAINERS} containers")
    ordered_pods = sorted(pods, key=lambda n: (_utf8(pods[n]["service"]), _utf8(n)))
    ordered_rows = sorted(
        rows, key=lambda k: (_utf8(pods[k[0]]["service"]), _utf8(k[0]), b"" if k[1] is None else b"\x01" + _utf8(k[1]), _utf8(k[2]))
    )
    document: dict = {"schema_version": "pod-view.v1", "load_input_sha256": load_sha256, "resource_snapshot_sha256": snapshot_sha256}
    if arm is not None:
        document["arm"] = arm
    document.update(
        {
            "start_epoch_ms": start_ms,
            "step_ms": step_ms,
            "column_count": columns,
            "coverage": {
                "pods_observed_total": observed_pods,
                "pods_included": len(pods),
                "rows_observed_total": observed_rows,
                "rows_included": len(rows),
                "selection": coverage_select,
            },
            "pods": [
                {
                    "pod": name,
                    "service": pods[name]["service"],
                    "containers": [{"name": c, "role": role_of(c, sidecar)} for c in sorted(pods[name]["containers"], key=_utf8)],
                }
                for name in ordered_pods
            ],
            "rows": [],
        }
    )
    for number, key in enumerate(ordered_rows, 1):
        item = rows[key]
        signal = METRICS[item.metric][1]
        document["rows"].append(
            {
                "id": f"r{number:05d}",
                "pod": key[0],
                "container": key[1],
                "metric": item.metric,
                "unit": signal.unit,
                "aggregation": signal.aggregation,
                "values": item.values,
            }
        )
    return document


def write_document(document: dict, path) -> None:
    data = (dumps(document) + "\n").encode("utf-8")
    if len(data) > MAX_FILE_BYTES:
        raise AdapterError(f"the file is {len(data)} bytes, above {MAX_FILE_BYTES}")
    with open(path, "wb") as stream:
        stream.write(data)


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--synthetic", metavar="SPEC", help="spec.json; writes the synthetic set into the directory --out")
    parser.add_argument("--prometheus", metavar="URL", help="base URL of a Prometheus-compatible source (live mode)")
    parser.add_argument("--namespace", nargs="+", help="namespaces; with several, pod names become namespace/pod")
    parser.add_argument("--services", nargs="+", help="workload names of the owner relabel")
    parser.add_argument("--metrics", nargs="+", default=list(METRICS), choices=list(METRICS))
    parser.add_argument("--start-epoch-ms", type=int)
    parser.add_argument("--step-ms", type=int)
    parser.add_argument("--columns", type=int)
    parser.add_argument("--load-sha256")
    parser.add_argument("--snapshot-sha256")
    parser.add_argument("--arm")
    parser.add_argument("--sidecar-containers", default="istio-proxy", help="regular expression (full match) for the sidecar role")
    parser.add_argument("--select", metavar="worst:METRIC:LIMIT", help="keep the LIMIT worst pods by the maximum of METRIC")
    parser.add_argument("--subquery-step", default="15s")
    parser.add_argument("--timeout-s", type=float, default=30.0)
    parser.add_argument("--out", required=True)
    return parser.parse_args(argv)


def validate_live(args) -> tuple:
    for name in ("prometheus", "namespace", "services", "start_epoch_ms", "step_ms", "columns", "load_sha256", "snapshot_sha256"):
        if getattr(args, name) is None:
            raise AdapterError(f"--{name.replace('_', '-')} is required in live mode")
    if not args.prometheus.startswith(("http://", "https://")):
        raise AdapterError("--prometheus must start with http:// or https://")
    if args.start_epoch_ms < 0 or args.step_ms < 1000 or args.step_ms % 1000 or not 1 <= args.columns <= MAX_COLUMNS:
        raise AdapterError("grid: step-ms must be whole seconds from 1000, columns from 1 to 240, start non-negative")
    if args.start_epoch_ms + args.step_ms * args.columns > MAX_EPOCH_MS:
        raise AdapterError("grid: the end of the grid is beyond the maximum timestamp")
    for value in (*args.namespace, *args.services):
        if not NAME.fullmatch(value):
            raise AdapterError(f"namespace and service names must match {NAME.pattern}: {value!r}")
    for value in (args.load_sha256, args.snapshot_sha256):
        if not HEX64.fullmatch(value):
            raise AdapterError("sha256 values must be 64 lower-case hex characters")
    if len(set(args.namespace)) != len(args.namespace) or len(set(args.services)) != len(args.services):
        raise AdapterError("namespaces and services must be unique")
    if args.arm is not None:
        _check_text("arm", args.arm, MAX_IDENTIFIER_BYTES)
    try:
        sidecar = re.compile(args.sidecar_containers)
    except re.error as error:
        raise AdapterError(f"--sidecar-containers: {error}") from error
    select = None
    if args.select is not None:
        match = SELECT.fullmatch(args.select)
        if not match or int(match.group(2)) > MAX_PODS or match.group(1) not in args.metrics:
            raise AdapterError("--select must be worst:<one of --metrics>:<1..256>")
        select = (match.group(1), int(match.group(2)))
    return sidecar, select


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        if args.synthetic is not None:
            from tools.platform_synthetic import generate_from_file

            generate_from_file(args.synthetic, args.out)
            return 0
        sidecar, select = validate_live(args)
        document = assemble(
            collect(args),
            load_sha256=args.load_sha256,
            snapshot_sha256=args.snapshot_sha256,
            arm=args.arm,
            start_ms=args.start_epoch_ms,
            step_ms=args.step_ms,
            columns=args.columns,
            sidecar=sidecar,
            select=select,
            qualify=len(args.namespace) > 1,
        )
        write_document(document, args.out)
    except AdapterError as error:
        print(f"pod_view_adapter: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    # Import the package copy: tools.platform_synthetic raises its own AdapterError, which must be the class caught here.
    from tools.pod_view_adapter import main as package_main

    sys.exit(package_main())

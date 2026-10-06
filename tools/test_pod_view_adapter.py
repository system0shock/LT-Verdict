"""Checks for the pod-view adapter (plan P2d): canned Prometheus answers from a local http.server, no real source."""

import io
import json
import re
import tempfile
import threading
import unittest
from contextlib import redirect_stderr
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from tools.pod_view_adapter import AdapterError, METRICS, Observation, Raw, assemble, format_value, main

START = 1_704_067_200_000
STEP = 15_000
LOAD = "a" * 64
SNAPSHOT = "b" * 64
MEMORY = "openshift_container_memory_limit_ratio"
POD_CPU = "openshift_pod_cpu_usage"
LABELS = {"namespace": "shop", "pod": "p", "container": "app"}


def base(*namespaces):
    return ["--namespace", *(namespaces or ("shop",)), "--services", "orders-svc", "--start-epoch-ms", str(START), "--step-ms", str(STEP),
            "--columns", "3", "--load-sha256", LOAD, "--snapshot-sha256", SNAPSHOT, "--arm", "A"]


def at(column):
    """Timestamp of the right boundary of a column, in seconds as Prometheus writes it."""
    return (START + (column + 1) * STEP) // 1000


def matrix(*series, status="success", warnings=None, kind="matrix"):
    body = {"status": status, "data": {"resultType": kind, "result": [{"metric": m, "values": v} for m, v in series]}}
    if warnings:
        body["warnings"] = warnings
    return json.dumps(body).encode()


class Source:
    """A Prometheus stand-in: respond(query, params) -> (status, bytes); every request is recorded."""

    def __init__(self, respond):
        self.requests = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                parsed = urlparse(self.path)
                params = {k: v[0] for k, v in parse_qs(parsed.query).items()}
                outer.requests.append((parsed.path, params))
                status, body = respond(params["query"], params)
                self.send_response(status)
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


class AdapterTest(unittest.TestCase):
    def run_live(self, respond, *extra, metrics=(MEMORY,)):
        source = Source(respond)
        self.addCleanup(source.close)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        out = Path(folder.name) / "pod-view.json"
        errors = io.StringIO()
        with redirect_stderr(errors):
            code = main(["--prometheus", source.url, *base(), "--metrics", *metrics, *extra, "--out", str(out)])
        document = json.loads(out.read_text(encoding="utf-8"), parse_float=Decimal) if out.exists() else None
        return code, document, errors.getvalue(), source

    @staticmethod
    def series(pod, container, *samples):
        labels = {"namespace": "shop", "pod": pod}
        if container:
            labels["container"] = container
        return (labels, [[at(column), text] for column, text in samples])

    def test_columns_are_right_boundaries_of_the_interval_and_a_missing_sample_is_null(self):
        def respond(_query, _params):
            return 200, matrix(self.series("orders-1", "app", (0, "0.5"), (2, "NaN")), self.series("orders-1", "istio-proxy", (1, "0.25")))

        code, document, errors, source = self.run_live(respond)
        self.assertEqual((0, ""), (code, errors))
        path, params = source.requests[0]
        self.assertEqual("/api/v1/query_range", path)
        self.assertEqual((str((START + STEP) // 1000), str((START + 3 * STEP) // 1000), str(STEP // 1000)), (params["start"], params["end"], params["step"]))
        self.assertIn("avg_over_time", params["query"])
        self.assertIn(f"[{STEP}ms:15s]", params["query"])
        self.assertNotIn("@", params["query"])
        self.assertNotIn("$__interval", params["query"])
        self.assertIn('namespace="shop"', params["query"])
        self.assertIn('workload="orders-svc"', params["query"])
        self.assertEqual((START, STEP, 3, "A"), (document["start_epoch_ms"], document["step_ms"], document["column_count"], document["arm"]))
        values = {row["container"]: row["values"] for row in document["rows"]}
        # NaN (a division by a zero limit) and an absent sample are gaps, never zero.
        self.assertEqual([Decimal("0.5"), None, None], values["app"])
        self.assertEqual([None, Decimal("0.25"), None], values["istio-proxy"])

    def test_istio_proxy_is_the_sidecar_and_pod_level_rows_have_no_container(self):
        def respond(query, _params):
            if "container_memory_working_set_bytes" in query:
                return 200, matrix(self.series("orders-1", "app", (0, "0.5")), self.series("orders-1", "istio-proxy", (0, "0.1")))
            return 200, matrix(self.series("orders-1", None, (0, "0.7")))

        code, document, _errors, _source = self.run_live(respond, metrics=(MEMORY, POD_CPU))
        self.assertEqual(0, code)
        roles = {c["name"]: c["role"] for c in document["pods"][0]["containers"]}
        self.assertEqual({"app": "app", "istio-proxy": "sidecar"}, roles)
        pod_level = [row for row in document["rows"] if row["metric"] == POD_CPU]
        self.assertEqual([None], [row["container"] for row in pod_level])
        self.assertEqual("cores", pod_level[0]["unit"])
        self.assertEqual(["r00001", "r00002", "r00003"], [row["id"] for row in document["rows"]])

    def test_a_sidecar_pattern_is_applied_by_full_match(self):
        def respond(_query, _params):
            return 200, matrix(self.series("orders-1", "envoy-agent", (0, "0.5")), self.series("orders-1", "envoy", (0, "0.5")))

        _code, document, _errors, _source = self.run_live(respond, "--sidecar-containers", "envoy-.*")
        self.assertEqual({"envoy-agent": "sidecar", "envoy": "app"}, {c["name"]: c["role"] for c in document["pods"][0]["containers"]})

    def test_worst_selection_keeps_the_worst_pods_and_states_the_coverage(self):
        scores = {"p1": "0.2", "p2": "0.9", "p3": "0.5", "p4": "0.5", "p5": None}

        def respond(_query, _params):
            return 200, matrix(*(self.series(pod, "app", *([(0, score)] if score else [])) for pod, score in scores.items()))

        code, document, _errors, _source = self.run_live(respond, "--select", f"worst:{MEMORY}:3")
        self.assertEqual(0, code)
        self.assertEqual(["p2", "p3", "p4"], [pod["pod"] for pod in document["pods"]])
        self.assertEqual(
            {
                "pods_observed_total": 5,
                "pods_included": 3,
                "rows_observed_total": 5,
                "rows_included": 3,
                "selection": {"kind": "WORST_BY_METRIC", "metric": MEMORY, "limit": 3},
            },
            document["coverage"],
        )

    def test_pods_of_two_namespaces_with_one_name_get_different_names(self):
        def respond(query, _params):
            namespace = "ns1" if 'namespace="ns1"' in query else "ns2"
            return 200, matrix(({"namespace": namespace, "pod": "orders-1", "container": "app"}, [[at(0), "0.5"]]))

        source = Source(respond)
        self.addCleanup(source.close)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        out = Path(folder.name) / "pod-view.json"
        self.assertEqual(0, main(["--prometheus", source.url, *base("ns1", "ns2"), "--metrics", MEMORY, "--out", str(out)]))
        document = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(["ns1/orders-1", "ns2/orders-1"], [pod["pod"] for pod in document["pods"]])

    def test_a_value_is_rounded_to_twelve_bytes_without_an_exponent(self):
        self.assertEqual("0.123456789", format_value(Decimal("0.123456789012345")))
        self.assertEqual("0.00001", format_value(Decimal("1e-5")))
        self.assertEqual("-1.23456789", format_value(Decimal("-1.23456789012345")))
        self.assertEqual("1234567890.1", format_value(Decimal("1234567890.123456")))
        self.assertEqual("0", format_value(Decimal("-0.0000000000000001")))
        self.assertEqual("123456789012", format_value(Decimal("123456789012")))
        with self.assertRaises(AdapterError):
            format_value(Decimal("1234567890123"))

    def test_a_response_that_cannot_be_placed_on_the_grid_is_refused(self):
        bad = {
            "off-grid": matrix((LABELS, [[at(0) + 1, "1"]])),
            "duplicate": matrix((LABELS, [[at(0), "1"], [at(0), "2"]])),
            "null timestamp": matrix((LABELS, [[None, "1"]])),
            "boolean timestamp": matrix((LABELS, [[True, "1"]])),
            "numeric value": matrix((LABELS, [[at(0), 1]])),
            "another namespace": matrix(({**LABELS, "namespace": "other"}, [[at(0), "1"]])),
            "no namespace": matrix(({"pod": "p", "container": "app"}, [[at(0), "1"]])),
            "warnings": matrix((LABELS, [[at(0), "1"]]), warnings=["partial"]),
            "failed": matrix(status="error"),
            "vector": matrix(kind="vector"),
            "before the first boundary": matrix((LABELS, [[START // 1000, "1"]])),
            "after the last boundary": matrix((LABELS, [[at(3), "1"]])),
            "not json": b"{",
            "no pod label": matrix(({"namespace": "shop", "container": "app"}, [[at(0), "1"]])),
            "no container label": matrix(({"namespace": "shop", "pod": "p"}, [[at(0), "1"]])),
        }
        for name, body in bad.items():
            with self.subTest(name):
                code, document, errors, _source = self.run_live(lambda _q, _p, body=body: (200, body))
                self.assertEqual((2, None), (code, document))
                self.assertIn("pod_view_adapter:", errors)

    def test_http_errors_are_adapter_errors(self):
        code, document, errors, _source = self.run_live(lambda _q, _p: (500, b"boom"))
        self.assertEqual((2, None), (code, document))
        self.assertIn("SOURCE_HTTP_500", errors)

    def test_missing_or_bad_options_are_refused_before_any_request(self):
        for extra in (
            ["--select", "worst:other:3"],
            ["--select", f"worst:{MEMORY}:257"],
            ["--select", "best:x:1"],
            ["--sidecar-containers", "("],
            ["--start-epoch-ms", "253402300799999"],
            ["--services", 'orders-svc",pod="x'],
            ["--namespace", 'shop",pod="x'],
        ):
            with self.subTest(extra):
                code, document, _errors, source = self.run_live(lambda _q, _p: (200, matrix()), *extra)
                self.assertEqual((2, None, []), (code, document, source.requests))

    def test_a_pod_of_two_services_a_duplicate_series_and_too_many_containers_are_errors(self):
        grid = {"load_sha256": LOAD, "snapshot_sha256": SNAPSHOT, "arm": None, "start_ms": START, "step_ms": STEP, "columns": 1,
                "sidecar": re.compile("istio-proxy"), "select": None, "qualify": False}

        def one(service, pod="p", container="app", metric=MEMORY):
            return Observation("shop", service, pod, container, metric, [Raw("0.5")])

        for name, observations in {
            "two services": [one("a"), one("b", container="c2")],
            "duplicate": [one("a"), one("a")],
            "five containers": [one("a", container=f"c{i}") for i in range(5)],
        }.items():
            with self.subTest(name), self.assertRaises(AdapterError):
                assemble(observations, **grid)

    def test_the_limits_are_checked_before_the_file_and_a_selection_brings_the_file_inside(self):
        grid = {"load_sha256": LOAD, "snapshot_sha256": SNAPSHOT, "arm": None, "start_ms": START, "step_ms": STEP, "columns": 1,
                "sidecar": re.compile("istio-proxy"), "qualify": False}
        pods = [Observation("shop", "a", f"p{i:03d}", "app", MEMORY, [Raw(f"0.{i % 9 + 1}")]) for i in range(257)]
        with self.assertRaises(AdapterError):
            assemble(pods, select=None, **grid)
        document = assemble(pods, select=(MEMORY, 256), **grid)
        self.assertEqual((257, 256, 257, 256), tuple(document["coverage"][k] for k in ("pods_observed_total", "pods_included", "rows_observed_total", "rows_included")))
        rows = [Observation("shop", "a", f"p{i // 10:03d}", f"c{i % 4}" if i % 10 else None, f"m{i % 10}", [None]) for i in range(2561)]
        with self.assertRaises(AdapterError):
            assemble(rows, select=None, **grid)

    def test_every_metric_renders_without_placeholders_and_with_the_pod_label_set(self):
        from tools.platform_profile_templates import render

        for name, (level, signal) in METRICS.items():
            query = render(signal, "shop", "orders-svc", "15s").replace("$__interval", "15000ms")
            with self.subTest(name):
                self.assertNotIn("@", query)
                self.assertIn("by (namespace, pod, container)" if level == "container" else "by (namespace, pod)", query)

    def test_the_same_answers_give_byte_equal_files(self):
        def respond(_query, _params):
            return 200, matrix(self.series("orders-1", "app", (0, "0.5"), (1, "0.6")))

        outputs = []
        for _ in range(2):
            source = Source(respond)
            self.addCleanup(source.close)
            folder = tempfile.TemporaryDirectory()
            self.addCleanup(folder.cleanup)
            out = Path(folder.name) / "pod-view.json"
            self.assertEqual(0, main(["--prometheus", source.url, *base(), "--metrics", MEMORY, "--out", str(out)]))
            outputs.append(out.read_bytes())
        self.assertEqual(outputs[0], outputs[1])
        self.assertNotIn(b"\r", outputs[0])


if __name__ == "__main__":
    unittest.main()

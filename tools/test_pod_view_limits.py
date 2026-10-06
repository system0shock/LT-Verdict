"""Shape checks of tools/perf/pod_view_limits.py: the measurement inputs have the counts the report names."""

import json
import unittest
from hashlib import sha256

from tools.perf import pod_view_limits as shapes

LOAD = "a" * 64
SNAPSHOT = "b" * 64
MIB12 = 12 * 1024 * 1024


def build(shape: str) -> dict:
    return json.loads(shapes.make_pod_view(shape, "A", LOAD, SNAPSHOT))


class ShapeTest(unittest.TestCase):
    def test_f_and_c_are_the_78_pod_shapes_of_the_adr(self):
        for shape, rows in (("F", 546), ("C", 936)):
            document = build(shape)
            self.assertEqual(78, len(document["pods"]), shape)
            self.assertEqual(rows, len(document["rows"]), shape)
            self.assertEqual(240, document["column_count"], shape)
            self.assertEqual(240, len(document["rows"][0]["values"]), shape)

    def test_limit_is_exactly_at_every_limit_of_the_core(self):
        document = build("LIMIT")
        self.assertEqual(256, len(document["pods"]))
        self.assertEqual(64, len({pod["service"] for pod in document["pods"]}))
        self.assertEqual(4, max(len(pod["containers"]) for pod in document["pods"]))
        self.assertEqual(2560, len(document["rows"]))
        self.assertEqual({"pods_observed_total": 256, "pods_included": 256, "rows_observed_total": 2560, "rows_included": 2560}, {key: document["coverage"][key] for key in ("pods_observed_total", "pods_included", "rows_observed_total", "rows_included")})

    def test_limit_worst_uses_the_byte_limits_of_identifiers_and_the_twelve_byte_token(self):
        text = shapes.make_pod_view("LIMIT-WORST", "A", LOAD, SNAPSHOT)
        document = json.loads(text)
        row = document["rows"][0]
        for key in ("id", "container", "metric", "unit"):
            self.assertEqual(128, len(row[key].encode()), key)
        self.assertEqual(253, max(len(pod["pod"].encode()) for pod in document["pods"]))
        self.assertEqual(128, max(len(pod["service"].encode()) for pod in document["pods"]))
        self.assertEqual(12, max(len(cell) for cell in text.split('"values":[')[1].split("]")[0].split(",")))
        self.assertLess(len(text.encode()), MIB12)
        self.assertEqual(2560, len({(r["pod"], r["container"], r["metric"]) for r in document["rows"]}))

    def test_one_service_holds_every_pod(self):
        document = build("ONE-SERVICE")
        self.assertEqual({"svc-00"}, {pod["service"] for pod in document["pods"]})
        self.assertEqual(2560, len(document["rows"]))

    def test_over_limit_shapes_exceed_exactly_one_limit(self):
        self.assertEqual(3072, len(build("OVER-ROWS")["rows"]))
        over_pods = build("OVER-PODS")
        self.assertEqual(257, len(over_pods["pods"]))
        self.assertLessEqual(len(over_pods["rows"]), 2560)
        self.assertEqual(65, len({pod["service"] for pod in build("OVER-SERVICES")["pods"]}))
        self.assertEqual(5, max(len(pod["containers"]) for pod in build("OVER-CONTAINERS")["pods"]))
        self.assertEqual(241, build("OVER-COLUMNS")["column_count"])
        self.assertEqual(MIB12 + 1, len(shapes.make_pod_view("OVER-BYTES", "A", LOAD, SNAPSHOT).encode()))

    def test_adversarial_shapes_stay_below_the_byte_limit(self):
        for shape in ("ADV-WIDE", "ADV-ROWS"):
            text = shapes.make_pod_view(shape, "A", LOAD, SNAPSHOT)
            self.assertLess(len(text.encode()), MIB12, shape)
            json.loads(text)

    def test_files_are_deterministic(self):
        first = shapes.make_pod_view("C", "A", LOAD, SNAPSHOT)
        self.assertEqual(sha256(first.encode()).hexdigest(), sha256(shapes.make_pod_view("C", "A", LOAD, SNAPSHOT).encode()).hexdigest())
        self.assertNotEqual(first, shapes.make_pod_view("C", "B", LOAD, SNAPSHOT))

    def test_snapshot_is_the_c_max_shape_and_its_hash_is_the_semantic_one(self):
        text, semantic = shapes.make_snapshot("A", LOAD)
        document = json.loads(text)
        self.assertEqual(1024, len(document["series"]))
        self.assertEqual(1440, document["point_count"])
        self.assertTrue(all(len(series["values"]) == 1440 for series in document["series"]))
        self.assertEqual(64, len(semantic))
        self.assertEqual((text, semantic), shapes.make_snapshot("A", LOAD))


if __name__ == "__main__":
    unittest.main()

import copy
import json
from pathlib import Path
import sys
import unittest

try:
    import jsonschema
except ModuleNotFoundError:
    jsonschema = None

sys.path.insert(0, str(Path(__file__).parent))
import correlation_catalog as cc

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "docs/contracts/diagnostics/v1"
STEP_MS = 10000
LOAD = "response_time_p95_ms"


def series(metric, entity="orders", unit="ratio", role="system", sid=None):
    return {"id": sid or f"{entity}.{metric}", "metric": metric, "unit": unit, "entity": entity,
            "role": role, "aggregation": "interval_mean", "labels": {}, "values": [0.0, 1.0, 2.0, 3.0]}


def full_snapshot(step_ms=STEP_MS):
    items = [
        series("openshift_cpu_throttling"), series("jvm_gc_pause", unit="s"),
        series("jvm_pool_saturation"), series("jvm_thread_count", unit="count"),
        series("openshift_pod_imbalance"), series("openshift_unavailable_replicas", unit="count"),
        series("openshift_sidecar_memory_limit_ratio"), series("openshift_sidecar_cpu_throttling"),
        series("service_response_time_p95", entity="billing", unit="ms"),
        series("cpu_used", entity="load-generator", role="generator", sid="generator.cpu"),
        series("concurrency", entity="load-generator", unit="count", role="generator", sid="ctl.concurrency"),
        series("target_rps", entity="load-generator", unit="requests/s", role="generator", sid="ctl.target"),
    ]
    return {"schema_version": "resource-snapshot.v1", "load_input_sha256": "0" * 64,
            "start_epoch_ms": 1767225600000, "step_ms": step_ms, "point_count": 4, "series": items,
            "windows": [{"id": "stage-1", "from_epoch_ms": 1767225600000, "to_epoch_ms": 1767225640000},
                        {"id": "stage-2", "from_epoch_ms": 1767225640000, "to_epoch_ms": 1767225680000}],
            "provenance": {"source_kind": "fixture", "query_semantics": "x", "clock_alignment": "declared_aligned"}}


OUTCOME = {"load_metric": LOAD, "service": "orders"}
EDGES = [("orders", "billing")]
STAGES = ["stage-1"]


def run(snapshot=None, stages=STAGES, outcome=OUTCOME, edges=EDGES, catalog=None):
    return cc.expand(catalog or cc.load_catalog(), snapshot or full_snapshot(), stages, outcome, edges)


def reasons(skipped):
    return {(item["hypothesis"], item["reason"]) for item in skipped}


class CatalogDataTest(unittest.TestCase):
    def test_shipped_catalog_is_valid(self):
        catalog = cc.load_catalog()
        self.assertEqual([f["outcome"] for f in catalog["families"]],
                         ["response_time_p95_ms", "error_rate", "throughput_rps"])
        for family in catalog["families"]:
            self.assertLessEqual(len(family["hypotheses"]), 16)
            self.assertTrue(any(h["kind"] == "control" for h in family["hypotheses"]))
            self.assertTrue(all(h["max_lag_cells"] <= 4 and h["min_abs_effect"] >= 0.3 for h in family["hypotheses"]))
        self.assertEqual([f["id"] for f in catalog["event_families"]], ["oom-restarts"])

    def test_rejects_more_than_16_hypotheses(self):
        catalog = cc.load_catalog()
        family = catalog["families"][0]
        template = family["hypotheses"][0]
        family["hypotheses"] = [dict(template, id=f"h{i}", metric=f"m{i}") for i in range(17)]
        with self.assertRaisesRegex(cc.CatalogError, "FAMILY_SIZE_EXCEEDED"):
            cc.validate_catalog(catalog)

    def test_rejects_invalid_catalog_entries(self):
        def broken(mutate):
            catalog = cc.load_catalog()
            mutate(catalog)
            return catalog

        cases = {
            "family without control": lambda c: c["families"][0]["hypotheses"].__setitem__(
                slice(None), [h for h in c["families"][0]["hypotheses"] if h["kind"] != "control"]),
            "lag above four cells": lambda c: c["families"][0]["hypotheses"][0].update(max_lag_cells=5),
            "effect below 0.3": lambda c: c["families"][0]["hypotheses"][0].update(min_abs_effect=0.2),
            "unknown outcome": lambda c: c["families"][0].update(outcome="response_time_p99_ms"),
            "unknown field": lambda c: c["families"][0]["hypotheses"][0].update(extra=1),
            "duplicate family outcome": lambda c: c["families"][1].update(outcome=c["families"][0]["outcome"]),
            "duplicate series in family": lambda c: c["families"][0]["hypotheses"][1].update(
                metric=c["families"][0]["hypotheses"][0]["metric"]),
            "unknown control": lambda c: c["families"][0]["hypotheses"][0].update(controls=["other"]),
            "achieved_rps control": lambda c: c["families"][0]["hypotheses"][0].update(controls=["achieved_rps"]),
            "non positive delta": lambda c: c["families"][0]["hypotheses"][0].update(min_resource_delta=0),
        }
        for name, mutate in cases.items():
            with self.subTest(name):
                with self.assertRaises(cc.CatalogError):
                    cc.validate_catalog(broken(mutate))

    def test_catalog_enums_match_the_plan_schema(self):
        plan = json.loads((CONTRACTS / "correlation-plan.schema.json").read_text(encoding="utf-8"))
        schema = json.loads((CONTRACTS / "correlation-catalog.schema.json").read_text(encoding="utf-8"))
        defs = schema["$defs"]
        self.assertEqual(defs["family"]["properties"]["outcome"]["enum"], plan["$defs"]["loadMetric"]["enum"])
        self.assertEqual(defs["hypothesis"]["properties"]["expected_sign"]["enum"],
                         plan["$defs"]["pair"]["properties"]["expected_sign"]["enum"])
        plan_controls = {c["properties"]["meaning"].get("const") or tuple(c["properties"]["meaning"]["enum"])
                         for c in plan["$defs"]["control"]["oneOf"]}
        allowed = set(defs["hypothesis"]["properties"]["controls"]["items"]["enum"])
        self.assertTrue(allowed <= set(next(c for c in plan_controls if isinstance(c, tuple))))

    @unittest.skipIf(jsonschema is None, "jsonschema is not installed")
    def test_shipped_catalog_matches_its_schema(self):
        schema = json.loads((CONTRACTS / "correlation-catalog.schema.json").read_text(encoding="utf-8"))
        jsonschema.validate(cc.load_catalog(), schema)


class ExpandTest(unittest.TestCase):
    def test_expands_family_one_for_p95_into_at_most_16_pairs(self):
        plan, skipped = run()
        self.assertEqual(skipped, [])
        pairs = plan["pairs"]
        self.assertEqual(len(pairs), 10)
        self.assertLessEqual(len(pairs), 16)
        self.assertEqual([p["id"] for p in pairs], sorted(p["id"] for p in pairs))
        throttling = next(p for p in pairs if p["id"] == "p95.cpu-throttling")
        self.assertEqual(throttling, {
            "id": "p95.cpu-throttling", "resource_series_id": "orders.openshift_cpu_throttling",
            "load_metric": LOAD, "window_ids": ["stage-1"], "expected_sign": "positive",
            "max_lag_ms": 20000, "min_abs_effect": 0.3, "min_resource_delta": 0.05, "min_load_delta": 5,
            "controls": [{"meaning": "target_rps", "series_id": "ctl.target"}],
            "topology_basis": "correlation-catalog.v1 p95/cpu-throttling: same service orders",
            "clock_alignment": "declared_aligned"})

    def test_plan_has_only_plan_fields_and_no_skipped_report(self):
        snapshot = full_snapshot()
        snapshot["series"] = [s for s in snapshot["series"] if s["metric"] != "jvm_gc_pause"]
        plan, skipped = run(snapshot)
        self.assertEqual(set(plan), {"schema_version", "resource_snapshot_sha256", "pairs"})
        self.assertEqual(plan["schema_version"], "correlation-plan.v1")
        self.assertEqual(len(plan["resource_snapshot_sha256"]), 64)
        self.assertIn(("gc-pause", "SERIES_MISSING"), reasons(skipped))
        self.assertNotIn("skipped", json.dumps(plan))

    def test_drops_hypothesis_when_metric_missing_from_snapshot(self):
        snapshot = full_snapshot()
        snapshot["series"] = [s for s in snapshot["series"] if s["metric"] != "jvm_thread_count"]
        plan, skipped = run(snapshot)
        self.assertEqual(len(plan["pairs"]), 9)
        self.assertNotIn("p95.thread-count", [p["id"] for p in plan["pairs"]])
        self.assertEqual(reasons(skipped), {("thread-count", "SERIES_MISSING")})

    def test_includes_load_generator_control_hypothesis(self):
        plan, _ = run()
        control = next(p for p in plan["pairs"] if p["id"] == "p95.generator-cpu")
        self.assertEqual(control["resource_series_id"], "generator.cpu")
        self.assertEqual(control["controls"], [])
        self.assertEqual(control["topology_basis"], "correlation-catalog.v1 p95/generator-cpu: load generator control")

    def test_worst_pod_is_fixed_aggregation_not_chosen_by_correlation(self):
        snapshot = full_snapshot()
        snapshot["series"].append(series("openshift_cpu_throttling", sid="pod-b.throttling"))
        plan, skipped = run(snapshot)
        self.assertNotIn("p95.cpu-throttling", [p["id"] for p in plan["pairs"]])
        self.assertIn(("cpu-throttling", "SERIES_AMBIGUOUS"), reasons(skipped))
        used = {p["resource_series_id"] for p in plan["pairs"]}
        self.assertFalse({"orders.openshift_cpu_throttling", "pod-b.throttling"} & used)

    def test_unit_mismatch_is_skipped(self):
        snapshot = full_snapshot()
        next(s for s in snapshot["series"] if s["metric"] == "jvm_gc_pause")["unit"] = "ms"
        plan, skipped = run(snapshot)
        self.assertIn(("gc-pause", "UNIT_MISMATCH"), reasons(skipped))
        self.assertNotIn("p95.gc-pause", [p["id"] for p in plan["pairs"]])

    def test_topology_edge_missing_removes_downstream_hypothesis(self):
        plan, skipped = run(edges=[("billing", "orders"), ("payments", "orders")])
        ids = [p["id"] for p in plan["pairs"]]
        self.assertFalse([i for i in ids if "downstream" in i])
        self.assertIn(("downstream-p95", "TOPOLOGY_EDGE_MISSING"), reasons(skipped))
        plan, skipped = run(edges=[])
        self.assertIn(("downstream-p95", "TOPOLOGY_EDGE_MISSING"), reasons(skipped))

    def test_declared_edge_adds_one_pair_per_callee_and_binds_its_series(self):
        plan, skipped = run()
        pair = next(p for p in plan["pairs"] if p["id"] == "p95.downstream-p95@billing")
        self.assertEqual(pair["resource_series_id"], "billing.service_response_time_p95")
        self.assertEqual(pair["topology_basis"],
                         "correlation-catalog.v1 p95/downstream-p95: declared edge orders -> billing")
        snapshot = full_snapshot()
        snapshot["series"].append(series("service_response_time_p95", entity="shipping", unit="ms"))
        plan, _ = run(snapshot, edges=[("orders", "shipping"), ("orders", "billing"), ("orders", "billing")])
        ids = [p["id"] for p in plan["pairs"] if "downstream" in p["id"]]
        self.assertEqual(ids, ["p95.downstream-p95@billing", "p95.downstream-p95@shipping"])

    def test_downstream_callee_without_series_is_skipped(self):
        plan, skipped = run(edges=[("orders", "payments")])
        self.assertIn(("downstream-p95@payments", "SERIES_MISSING"), reasons(skipped))
        self.assertFalse([p for p in plan["pairs"] if "downstream" in p["id"]])

    def test_more_than_16_pairs_after_topology_is_rejected(self):
        snapshot = full_snapshot()
        callees = [f"svc{i}" for i in range(8)]
        snapshot["series"] += [series("service_response_time_p95", entity=c, unit="ms") for c in callees]
        with self.assertRaisesRegex(cc.CatalogError, "FAMILY_SIZE_EXCEEDED"):
            run(snapshot, edges=[("orders", c) for c in callees])

    def test_same_input_gives_identical_plan_hash(self):
        first, _ = run()
        second, _ = run(edges=list(reversed(EDGES)), stages=list(STAGES))
        self.assertEqual(cc.plan_sha256(first), cc.plan_sha256(second))
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))
        two_stages = run(stages=["stage-2", "stage-1"])[0]
        self.assertEqual(two_stages["pairs"][0]["window_ids"], ["stage-1", "stage-2"])

    def test_snapshot_hash_ignores_provenance_but_binds_series(self):
        base = run()[0]["resource_snapshot_sha256"]
        snapshot = full_snapshot()
        snapshot["provenance"]["query_semantics"] = "other"
        self.assertEqual(run(snapshot)[0]["resource_snapshot_sha256"], base)
        snapshot["series"][0]["values"][0] = 9.0
        self.assertNotEqual(run(snapshot)[0]["resource_snapshot_sha256"], base)

    def test_all_hypotheses_skipped_returns_no_plan(self):
        snapshot = full_snapshot()
        snapshot["series"] = [series("unrelated")]
        plan, skipped = run(snapshot)
        self.assertIsNone(plan)
        self.assertEqual(len(skipped), 10)

    def test_only_control_left_returns_no_plan(self):
        snapshot = full_snapshot()
        snapshot["series"] = [s for s in snapshot["series"] if s["role"] == "generator"]
        plan, skipped = run(snapshot)
        self.assertIsNone(plan)
        self.assertIn(("generator-cpu", "ONLY_CONTROL_LEFT"), reasons(skipped))

    def test_family_not_activated_returns_no_plan(self):
        plan, skipped = run(outcome=None)
        self.assertIsNone(plan)
        self.assertEqual(skipped, [{"hypothesis": None, "reason": "OUTCOME_NOT_ACTIVATED"}])

    def test_outcome_outside_catalog_returns_no_plan(self):
        for metric in ("response_time_p99_ms", "gc_pause"):
            with self.subTest(metric):
                plan, skipped = run(outcome={"load_metric": metric, "service": "orders"})
                self.assertIsNone(plan)
                self.assertEqual(skipped, [{"hypothesis": None, "reason": "OUTCOME_NOT_IN_CATALOG"}])

    def test_lag_beyond_plan_limit_is_skipped(self):
        plan, skipped = run(full_snapshot(step_ms=30000))
        by_id = {p["id"]: p for p in plan["pairs"]}
        self.assertEqual(by_id["p95.cpu-throttling"]["max_lag_ms"], 60000)
        self.assertNotIn("p95.thread-count", by_id)
        self.assertEqual(reasons(skipped), {("thread-count", "LAG_EXCEEDS_PLAN_LIMIT")})
        self.assertTrue(all(p["max_lag_ms"] <= 60000 for p in plan["pairs"]))

    def test_concurrency_control_binds_series_or_skips(self):
        plan, _ = run()
        pool = next(p for p in plan["pairs"] if p["id"] == "p95.pool-saturation")
        self.assertEqual(pool["controls"], [{"meaning": "concurrency", "series_id": "ctl.concurrency"}])
        snapshot = full_snapshot()
        snapshot["series"] = [s for s in snapshot["series"] if s["metric"] != "concurrency"]
        plan, skipped = run(snapshot)
        self.assertNotIn("p95.pool-saturation", [p["id"] for p in plan["pairs"]])
        self.assertIn(("pool-saturation", "CONTROL_SERIES_MISSING"), reasons(skipped))
        snapshot = full_snapshot()
        snapshot["series"].append(series("concurrency", unit="count", sid="other.concurrency"))
        self.assertIn(("pool-saturation", "CONTROL_SERIES_AMBIGUOUS"), reasons(run(snapshot)[1]))
        snapshot = full_snapshot()
        next(s for s in snapshot["series"] if s["metric"] == "concurrency")["unit"] = "ratio"
        self.assertIn(("pool-saturation", "CONTROL_SERIES_MISSING"), reasons(run(snapshot)[1]))

    def test_stage_constant_target_rps_control_is_required_for_rps_rows(self):
        snapshot = full_snapshot()
        snapshot["series"] = [s for s in snapshot["series"] if s["metric"] != "target_rps"]
        plan, skipped = run(snapshot)
        self.assertEqual({p["id"] for p in plan["pairs"]},
                         {"p95.pool-saturation", "p95.thread-count", "p95.generator-cpu"})
        self.assertEqual({reason for _, reason in reasons(skipped)}, {"CONTROL_SERIES_MISSING"})
        self.assertEqual(len(skipped), 7)

    def test_throughput_family_uses_target_rps_and_negative_sign(self):
        plan, skipped = run(outcome={"load_metric": "throughput_rps", "service": "orders"})
        self.assertEqual(skipped, [])
        self.assertEqual({p["load_metric"] for p in plan["pairs"]}, {"throughput_rps"})
        pool = next(p for p in plan["pairs"] if p["id"] == "throughput.pool-saturation")
        self.assertEqual(pool["expected_sign"], "negative")
        self.assertEqual(pool["controls"], [{"meaning": "target_rps", "series_id": "ctl.target"}])
        self.assertEqual(pool["min_load_delta"], 1)

    def test_stage_must_exist_in_snapshot_windows(self):
        with self.assertRaisesRegex(cc.CatalogError, "STAGE_NOT_FOUND"):
            run(stages=["stage-9"])
        for bad in ([], ["stage-1", "stage-1"]):
            with self.subTest(bad):
                with self.assertRaisesRegex(cc.CatalogError, "STAGES_INVALID"):
                    run(stages=bad)

    def test_unsafe_identifier_is_rejected(self):
        snapshot = full_snapshot()
        callee = "x" * 129
        snapshot["series"].append(series("service_response_time_p95", entity=callee, unit="ms"))
        with self.assertRaisesRegex(cc.CatalogError, "IDENTIFIER_INVALID"):
            run(snapshot, edges=[("orders", callee)])

    def test_pairs_follow_plan_schema(self):
        schema = json.loads((CONTRACTS / "correlation-plan.schema.json").read_text(encoding="utf-8"))
        allowed = set(schema["$defs"]["pair"]["properties"])
        required = set(schema["$defs"]["pair"]["required"])
        for outcome in ("response_time_p95_ms", "error_rate", "throughput_rps"):
            plan, _ = run(outcome={"load_metric": outcome, "service": "orders"})
            ids = [p["id"] for p in plan["pairs"]]
            self.assertEqual(len(ids), len(set(ids)))
            for pair in plan["pairs"]:
                self.assertTrue(required <= set(pair) <= allowed, pair)
                self.assertGreater(pair["min_resource_delta"], 0)
                self.assertGreater(pair["min_load_delta"], 0)
        if jsonschema is not None:
            jsonschema.validate(plan, schema)

    def test_input_is_not_mutated(self):
        catalog, snapshot = cc.load_catalog(), full_snapshot()
        before = copy.deepcopy((catalog, snapshot))
        run(snapshot, catalog=catalog)
        self.assertEqual((catalog, snapshot), before)


if __name__ == "__main__":
    unittest.main()

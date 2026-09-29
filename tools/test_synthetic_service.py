import csv
import hashlib
import json
import random
import tempfile
import unittest
from pathlib import Path

from tools.synthetic_service import (
    _interval_totals,
    _wire_value,
    export,
    scenario_parameters,
    simulate,
    validate_trace,
)


class SyntheticServiceTests(unittest.TestCase):
    def test_wire_values_have_six_decimal_half_even_precision(self):
        """A binary float tail must not become an unsupported resource value."""
        self.assertEqual(.123456, _wire_value(.1234565))
        self.assertEqual(.123458, _wire_value(.1234575))
        self.assertEqual(.333333, _wire_value(1 / 3))
        self.assertIsNone(_wire_value(None))

    def test_interval_sweep_preserves_weighted_half_open_overlap(self):
        """Replacing the sweep with endpoint counts loses fractional busy work."""
        observations = [{"true_from_us": value} for value in (0, 5, 12)]
        self.assertEqual([21, 28.5, 10], _interval_totals(
            observations, [(0, 10, 1), (5, 15, 2), (8, 20, .5)], 10))

    def test_single_cpu_worker_uses_fifo_recurrence(self):
        """Removing FIFO serialization makes the hand-derived finish times fail."""
        trace = simulate({
            "arrivals_us": [0, 1_000, 2_000],
            "duration_us": 3_000,
            "drain_us": 50_000,
            "cpu_workers": 1,
            "cpu_demand_us": 10_000,
            "db_workers": 3,
            "db_demand_us": 0,
            "downstream_us": 0,
            "pool_capacity": 3,
            "timeout_us": 100_000,
        }, 0)
        self.assertEqual([10_000, 20_000, 30_000], [r["stages"]["cpu"]["end_us"] for r in trace["requests"]])
        self.assertEqual([], validate_trace(trace))

    def test_more_cpu_workers_cannot_increase_isolated_cpu_wait(self):
        """Using fewer workers or reordering the queue makes this monotonicity check fail."""
        common = {
            "arrivals_us": [0, 1_000, 2_000, 3_000], "duration_us": 4_000,
            "drain_us": 100_000, "cpu_demand_us": 10_000, "db_workers": 4,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 4,
            "timeout_us": 100_000,
        }
        one = simulate(common | {"cpu_workers": 1}, 0)
        two = simulate(common | {"cpu_workers": 2}, 0)
        waits = lambda trace: [r["stages"]["cpu"]["start_us"] - r["stages"]["cpu"]["entry_us"] for r in trace["requests"]]
        self.assertTrue(all(a >= b for a, b in zip(waits(one), waits(two))))

    def test_throttle_preserves_work_and_does_not_count_pause_as_cpu_busy(self):
        """Counting throttled wall time or GC pause as CPU work makes these literals fail."""
        trace = simulate({
            "arrivals_us": [0], "duration_us": 1, "drain_us": 100_000,
            "cpu_workers": 1, "cpu_demand_us": 10_000, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 1,
            "timeout_us": 100_000,
            "cpu_quota_schedule": [{"from_us": 0, "numerator": 1, "denominator": 4}],
            "gc_pauses": [{"from_us": 10_000, "to_us": 15_000}],
        }, 0)
        cpu = trace["requests"][0]["stages"]["cpu"]
        self.assertEqual(45_000, cpu["end_us"])
        self.assertEqual(10_000, sum(i["work_us"] for i in trace["busy_intervals"] if i["resource"] == "cpu"))
        self.assertFalse(any(i["from_us"] < 15_000 and i["to_us"] > 10_000 for i in trace["busy_intervals"] if i["resource"] == "cpu"))

    def test_generator_wait_is_not_an_application_arrival(self):
        """Issuing generator-queued work as app traffic makes arrivals and latency wrong."""
        trace = simulate({
            "arrivals_us": [0, 1], "duration_us": 2, "drain_us": 0,
            "cpu_workers": 1, "cpu_demand_us": 100, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 1,
            "timeout_us": 1_000, "generator_threads": 1,
        }, 0)
        self.assertEqual(1, trace["counts"]["arrivals"])
        self.assertEqual(1, trace["counts"]["generator_wait"])
        self.assertEqual("generator_wait", trace["requests"][1]["status"])
        self.assertIsNone(trace["requests"][1]["start_us"])
        self.assertEqual("in_flight", trace["requests"][0]["status"])
        self.assertIsNone(trace["requests"][0]["stages"]["cpu"]["end_us"])
        self.assertEqual([], validate_trace(trace))

    def test_export_is_neutral_and_rounds_request_times(self):
        """Exporting evaluator scenario names or flooring elapsed time breaks ingest neutrality."""
        trace = simulate({
            "scenario": "NT01", "arrivals_us": [1_001], "duration_us": 2_000,
            "drain_us": 20_000, "cpu_workers": 1, "cpu_demand_us": 1_001,
            "db_workers": 1, "db_demand_us": 0, "downstream_us": 0,
            "pool_capacity": 1, "timeout_us": 20_000,
        }, 0)
        with tempfile.TemporaryDirectory() as temp:
            paths = export(trace, Path(temp) / 'export')
            self.assertEqual(set(paths), {"load", "resources", "trace"})
            self.assertTrue(all(Path(path).is_absolute() for path in paths.values()))
            with Path(paths["load"]).open(newline="", encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual({"timeStamp": "1704067200001", "elapsed": "2", "label": "request", "success": "true"}, rows[0])
            self.assertNotIn("NT01", Path(paths["load"]).read_text(encoding="utf-8"))
            self.assertNotIn("NT01", Path(paths["resources"]).read_text(encoding="utf-8"))
            resource = json.loads(Path(paths["resources"]).read_text(encoding="utf-8"))
            self.assertEqual("resource-snapshot.v1", resource["schema_version"])

    def test_export_includes_neutral_operational_telemetry(self):
        """Dropping an operational signal leaves an applicability mechanism unobservable."""
        trace = simulate({
            "scenario": "NT06", "stages": [{"rate_rps": 1, "duration_us": 2_000_000}],
            "drain_us": 2_000_000, "cpu_workers": 1, "cpu_demand_us": 100_000,
            "db_workers": 1, "db_demand_us": 0, "downstream_us": 1_000,
            "pool_capacity": 1, "timeout_us": 2_000_000,
            "cpu_quota_schedule": [{"from_us": 0, "numerator": 1, "denominator": 1},
                                   {"from_us": 1_000_000, "numerator": 1, "denominator": 2}],
            "cpu_worker_changes": [{"from_us": 1_000_000, "workers": 2}],
            "downstream_changes": [{"from_us": 1_000_000, "to_us": 2_000_000, "add_us": 50_000}],
            "allocation_bytes": 64, "heap_bytes": 64, "heap_trigger_bytes": 128,
            "gc_pause_us": 10,
        }, 0)
        with tempfile.TemporaryDirectory() as temp:
            resource = json.loads(Path(export(trace, Path(temp) / "export")["resources"]).read_text(encoding="utf-8"))
        series = {item["id"]: item for item in resource["series"]}
        expected = {
            "admission-queue": ("queue_depth", "requests", "system", "interval_mean"),
            "cpu-queue": ("queue_depth", "requests", "system", "interval_mean"),
            "db-queue": ("queue_depth", "requests", "system", "interval_mean"),
            "generator-queue": ("queue_depth", "requests", "generator", "interval_mean"),
            "runtime-heap": ("used_bytes", "bytes", "system", "interval_mean"),
            "runtime-gc-pause": ("pause_fraction", "ratio", "system", "interval_mean"),
            "cpu-service-quota": ("quota_fraction", "ratio", "system", "interval_mean"),
            "service-replicas": ("worker_count", "count", "system", "interval_mean"),
            "target-request-rate": ("target_rate", "rps", "generator", "interval_rate"),
            "downstream-wait": ("in_flight_requests", "requests", "system", "interval_mean"),
        }
        self.assertTrue(expected.keys() <= series.keys())
        for series_id, (metric, unit, role, aggregation) in expected.items():
            self.assertEqual((metric, unit, role, aggregation),
                             tuple(series[series_id][key] for key in ("metric", "unit", "role", "aggregation")))
        self.assertNotIn("NT06", json.dumps(resource, sort_keys=True))
        self.assertEqual("interval means/rates from declared observation intervals", resource["provenance"]["query_semantics"])

    def test_background_cpu_schedule_reserves_a_worker_and_downstream_schedule_is_applied(self):
        """Treating scheduled work as telemetry-only misses temporal lag fixtures."""
        trace = simulate({
            "stages": [{"rate_rps": 1, "duration_us": 120_000_000}], "drain_us": 1_000_000,
            "cpu_workers": 4, "cpu_demand_us": 10_000, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 4,
            "timeout_us": 1_000_000,
            "background_cpu_intervals": [{"from_us": 40_000_000, "to_us": 60_000_000, "workers": 1}],
            "downstream_changes": [{"from_us": 45_000_000, "to_us": 65_000_000, "add_us": 50_000}],
        }, 0)
        background = [item for item in trace["busy_intervals"] if item["resource"] == "cpu" and item["request_id"] is None]
        self.assertEqual([(40_000_000, 60_000_000, 0)],
                         [(item["from_us"], item["to_us"], item["worker"]) for item in background])
        request = trace["requests"][45]
        self.assertEqual(50_000, request["stages"]["downstream"]["end_us"] - request["stages"]["downstream"]["start_us"])
        self.assertEqual([], validate_trace(trace))

    def test_declared_stress_schedules_are_executable_parameter_overrides(self):
        """A descriptive-only stress variant cannot exercise the prescribed system behaviour."""
        nt01 = scenario_parameters("NT01")["stress_variants"]["warmup_cpu_demand_x2_first_60s"]
        self.assertEqual([
            {"from_us": 0, "numerator": 2, "denominator": 1},
            {"from_us": 60_000_000, "numerator": 1, "denominator": 1},
        ], nt01["cpu_demand_multiplier_schedule"])
        nt02 = scenario_parameters("NT02")["stress_variants"]["periodic_background_cpu"]
        self.assertEqual({"from_us": 120_000_000, "to_us": 120_020_000, "workers": 1},
                         nt02["background_cpu_intervals"][0])
        self.assertEqual({"from_us": 120_100_000, "to_us": 120_120_000, "workers": 1},
                         nt02["background_cpu_intervals"][1])
        self.assertEqual({"from_us": 1_099_900_000, "to_us": 1_099_920_000, "workers": 1},
                         nt02["background_cpu_intervals"][-1])
        self.assertEqual(1_800, len(nt02["background_cpu_intervals"]))
        trace = simulate({
            "arrivals_us": [0, 60_000_000], "duration_us": 60_000_001, "drain_us": 1_000,
            "cpu_workers": 1, "cpu_demand_us": 10, "db_workers": 1, "db_demand_us": 0,
            "downstream_us": 0, "pool_capacity": 1, "timeout_us": 1_000,
            "cpu_demand_multiplier_schedule": nt01["cpu_demand_multiplier_schedule"],
        }, 0)
        self.assertEqual([20, 10], [request["cpu_demand_us"] for request in trace["requests"]])

    def test_all_declared_scenarios_have_paired_parameter_changes(self):
        """Dropping a methodology family or making a pair identical fails this inventory check."""
        mechanism_fields = {
            "NT01": "cpu_workers", "NT02": "cpu_workers", "NT03": "db_workers",
            "NT04": "pool_capacity_changes", "NT05": "cpu_quota_schedule",
            "NT06": "allocation_bytes", "NT07": "downstream_changes",
            "NT08": "cpu_worker_changes", "NT09": "generator_threads", "NT10": "stages",
        }
        for scenario, field in mechanism_fields.items():
            base = scenario_parameters(scenario)
            paired = scenario_parameters(scenario, intervention=True, noisy=True)
            self.assertEqual(scenario, base["scenario"])
            self.assertNotEqual(base.get(field), paired.get(field))
            self.assertEqual(200_000, paired["sampling"]["jitter_us"])
            self.assertTrue(paired["declared_variants"])

    def test_paired_noisy_runs_reuse_arrivals_and_demands(self):
        """Changing the named mechanism must not silently alter the paired random streams."""
        base = scenario_parameters("NT05", noisy=True)
        paired = scenario_parameters("NT05", intervention=True, noisy=True)
        for parameters in (base, paired):
            parameters["stages"] = [{"rate_rps": 10, "duration_us": 1_000_000}]
            parameters["drain_us"] = 1_000_000
        left, right = simulate(base, 0), simulate(paired, 0)
        self.assertEqual(
            [(request["planned_us"], request["cpu_demand_us"], request["db_demand_us"])
             for request in left["requests"]],
            [(request["planned_us"], request["cpu_demand_us"], request["db_demand_us"])
             for request in right["requests"]],
        )

    def test_noise_stream_uses_method_sha256_seed(self):
        """Changing the SHA-256-derived stream seed changes the fixed noisy fixture."""
        parameters = {
            "scenario": "NT01", "arrivals_us": [0], "duration_us": 1,
            "drain_us": 100_000, "cpu_workers": 1, "cpu_demand_us": 10_000,
            "db_workers": 1, "db_demand_us": 2_000, "downstream_us": 0,
            "pool_capacity": 1, "timeout_us": 100_000, "noise": True,
        }
        trace = simulate(parameters, 7)
        namespace = "ltv-applicability-v1/NT01/7/cpu"
        rng = random.Random(int.from_bytes(hashlib.sha256(namespace.encode("utf-8")).digest(), "big"))
        self.assertEqual(round(10_000 * rng.uniform(.8, 1.2)), trace["requests"][0]["cpu_demand_us"])

    def test_telemetry_jitter_masks_and_clock_shift_transform_observations(self):
        """Treating jitter, masks, and clock shift as metadata leaves this telemetry unchanged."""
        parameters = {
            "arrivals_us": [0], "duration_us": 2_000_000, "drain_us": 2_000_000,
            "cpu_workers": 1, "cpu_demand_us": 100_000, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 1,
            "timeout_us": 2_000_000,
            "sampling": {"step_us": 1_000_000, "jitter_us": 200_000,
                         "clock_offset_us": 5_000_000, "clock_alignment": "unknown",
                         "missing_intervals": [{"series": "system-db-work", "from_us": 0, "to_us": 2_000_000}]},
        }
        trace = simulate(parameters, 0)
        self.assertNotEqual(0, trace["observations"][0]["jitter_us"])
        self.assertEqual(trace["observations"][0]["true_from_us"] + 5_000_000,
                         trace["observations"][0]["observed_at_us"])
        with tempfile.TemporaryDirectory() as temp:
            resource = json.loads(Path(export(trace, Path(temp) / 'export')["resources"]).read_text(encoding="utf-8"))
        self.assertEqual(5_000, resource["start_epoch_ms"] - 1_704_067_200_000)
        db = next(series for series in resource["series"] if series["id"] == "system-db-work")
        self.assertEqual([None, None], db["values"])

    def test_gc_uses_completed_service_allocation_and_failed_samples_export_false(self):
        """Planned-time GC or omitted timeouts makes these event/accounting facts fail."""
        trace = simulate({
            "arrivals_us": [0, 1], "duration_us": 2, "drain_us": 100_000,
            "cpu_workers": 1, "cpu_demand_us": 10, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 100, "pool_capacity": 1,
            "timeout_us": 15, "allocation_bytes": 64, "heap_bytes": 64,
            "heap_trigger_bytes": 128, "gc_pause_us": 20,
        }, 0)
        self.assertEqual(1, sum(change["reason"] == "gc_pause" for change in trace["state_changes"]))
        self.assertEqual(2, trace["counts"]["timed_out"])
        with tempfile.TemporaryDirectory() as temp:
            with Path(export(trace, Path(temp) / 'export')["load"]).open(newline="", encoding="utf-8") as stream:
                rows = list(csv.DictReader(stream))
        self.assertEqual("false", rows[0]["success"])

    def test_timeout_ends_the_active_stage_at_the_terminal_event(self):
        """Leaving planned completion times after timeout leaks work that never happened."""
        trace = simulate({
            "arrivals_us": [0], "duration_us": 1, "drain_us": 1_000,
            "cpu_workers": 1, "cpu_demand_us": 5, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 100, "pool_capacity": 1,
            "timeout_us": 10,
        }, 0)
        request = trace["requests"][0]
        self.assertEqual("timed_out", request["status"])
        self.assertEqual(10, request["stages"]["downstream"]["end_us"])
        self.assertEqual(10, request["end_us"])
        self.assertEqual([], validate_trace(trace))

    def test_gc_interrupts_ongoing_cpu_work_without_counting_pause_busy(self):
        """Leaving a concurrent CPU job on its original completion event completes it through STW."""
        trace = simulate({
            "arrivals_us": [0, 5], "duration_us": 6, "drain_us": 1_000,
            "cpu_workers": 2, "cpu_demand_us": 10, "db_workers": 2,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 2,
            "timeout_us": 1_000, "allocation_bytes": 64, "heap_bytes": 64,
            "heap_trigger_bytes": 128, "gc_pause_us": 20,
        }, 0)
        self.assertEqual(35, trace["requests"][1]["stages"]["cpu"]["end_us"])
        self.assertFalse(any(interval["from_us"] < 30 and interval["to_us"] > 10
                             for interval in trace["busy_intervals"] if interval["resource"] == "cpu"))

    def test_capacity_sweep_checks_change_times_worker_overlap_and_work_bounds(self):
        """Endpoint-only capacity scans miss a reduction between two busy endpoints."""
        trace = simulate({
            "arrivals_us": [0], "duration_us": 1, "drain_us": 1_000,
            "cpu_workers": 2, "cpu_demand_us": 100, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 1,
            "timeout_us": 1_000,
        }, 0)
        original = trace["busy_intervals"][0]
        trace["busy_intervals"].append(original | {"worker": original["worker"], "work_us": -1})
        trace["parameters"]["cpu_worker_changes"] = [{"from_us": 50, "workers": 1}]
        errors = validate_trace(trace)
        self.assertTrue(any("busy exceeds capacity" in error for error in errors))
        self.assertTrue(any("worker overlap" in error for error in errors))
        self.assertTrue(any("invalid work" in error for error in errors))

    def test_autoscale_does_not_reuse_an_assigned_cpu_worker(self):
        """Adding worker IDs by set update must not make a currently busy worker available."""
        trace = simulate({
            "arrivals_us": [0, 1], "duration_us": 2, "drain_us": 1_000,
            "cpu_workers": 1, "cpu_demand_us": 100, "db_workers": 2,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 2,
            "timeout_us": 1_000,
            "cpu_worker_changes": [{"from_us": 1, "workers": 2}],
        }, 0)
        workers = {interval["request_id"]: interval["worker"]
                   for interval in trace["busy_intervals"] if interval["resource"] == "cpu"}
        self.assertEqual({0: 0, 1: 1}, workers)
        self.assertEqual([], validate_trace(trace))

    def test_export_refuses_an_existing_directory(self):
        """Opening an existing export path would overwrite a frozen fixture."""
        trace = simulate({
            "arrivals_us": [0], "duration_us": 1, "drain_us": 1_000,
            "cpu_workers": 1, "cpu_demand_us": 1, "db_workers": 1,
            "db_demand_us": 0, "downstream_us": 0, "pool_capacity": 1,
            "timeout_us": 1_000,
        }, 0)
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileExistsError):
                export(trace, temp)


if __name__ == "__main__":
    unittest.main()

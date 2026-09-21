from copy import deepcopy
import unittest

from tools.applicability_contracts import builder
from tools.applicability_inventory import configurations
from tools.synthetic_service import simulate


def configuration(scenario, variant="clean"):
    return next(case for case in configurations()
                if case["scenario"] == scenario and case["variant"] == variant and case["seed"] == 2000)


def runs():
    series = ["system-cpu-work", "system-db-work", "admission-queue", "cpu-queue", "db-queue",
              "runtime-heap", "runtime-gc-pause", "cpu-service-quota", "service-replicas",
              "target-request-rate", "downstream-wait", "generator-threads", "generator-rps"]
    return [{"resources": {"series": [{"id": item, "unit": "test"} for item in series]}}] * 2


class ApplicabilityContractsTests(unittest.TestCase):
    def test_throttle_requires_real_service_effect_not_only_quota_metadata(self):
        case = configuration('NT05')
        traces = [simulate(member['parameters'],0) for member in case['members']]
        self.assertEqual('PASS',builder(case,traces,runs())['preflight']['status'])
        control = {r['id']:r['stages']['cpu'] for r in traces[1]['requests']}
        for request in traces[0]['requests']:
            stage = request['stages']['cpu']
            other = control[request['id']]
            if stage['start_us'] is not None and stage['start_us']>=300_000_000:
                stage['end_us'] = stage['start_us']+other['end_us']-other['start_us']
        self.assertEqual('INVALID_FIXTURE',builder(case,traces,runs())['preflight']['status'])

    def test_contract_records_observables_limits_and_trace_proven_downstream_step(self):
        case = configuration("NT07")
        traces = [simulate(member["parameters"], 0) for member in case["members"]]

        contract = builder(case, traces, runs())

        self.assertEqual("PASS", contract["preflight"]["status"])
        self.assertIn("downstream_latency_changes_with_step", contract["expected_observable_facts"])
        self.assertIn("shared_timestamp_is_causal_proof", contract["forbidden_interpretations"])
        self.assertIn("downstream-wait", [signal["id"] for signal in contract["available_signals"]])

    def test_preflight_rejects_a_declared_downstream_step_without_trace_effect(self):
        case = configuration("NT07")
        traces = [simulate(member["parameters"], 0) for member in case["members"]]
        for request in traces[0]["requests"]:
            stage = request["stages"]["downstream"]
            if stage["start_us"] is not None and stage["start_us"] >= 300_000_000:
                stage["end_us"] -= 50_000

        contract = builder(case, traces, runs())

        self.assertEqual("INVALID_FIXTURE", contract["preflight"]["status"])
        self.assertIn("NT07 downstream step has no trace effect", contract["preflight"]["failures"])

    def test_unknown_clock_is_a_predeclared_abstention_not_a_product_failure(self):
        case = configuration("NT07", "resource_clock_offset_unknown")
        traces = [simulate(member["parameters"], 0) for member in case["members"]]

        contract = builder(case, traces, runs())

        self.assertIn("no_time_order_claim_with_unknown_clock", contract["expected_abstentions"])
        self.assertEqual("PASS", contract["status"])

    def test_preflight_rejects_periodic_background_cpu_declared_without_busy_intervals(self):
        case = configuration("NT02", "periodic_background_cpu")
        traces = [simulate(member["parameters"], 0) for member in case["members"]]
        for trace in traces:
            trace["busy_intervals"] = [item for item in trace["busy_intervals"] if item["request_id"] is not None]

        contract = builder(case, traces, runs())

        self.assertEqual("INVALID_FIXTURE", contract["preflight"]["status"])
        self.assertIn("NT02 periodic CPU has no busy trace pulses", contract["preflight"]["failures"])


if __name__ == "__main__":
    unittest.main()

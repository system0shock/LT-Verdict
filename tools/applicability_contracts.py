"""Pre-output applicability contracts derived from declared fixtures and traces."""

from __future__ import annotations


_FACTS = {
    "NT01": ["latency_queue_and_cpu_work_are_observable", "cpu_queue_reduced_by_intervention"],
    "NT02": ["latency_queue_and_cpu_work_are_observable", "common_workload_driver_is_observable"],
    "NT03": ["latency_db_queue_and_db_work_are_observable", "db_queue_changes_with_intervention"],
    "NT04": ["latency_and_admission_queue_are_observable", "pool_limit_changes_admission_wait"],
    "NT05": ["latency_cpu_queue_and_quota_are_observable", "throttled_service_is_observable"],
    "NT06": ["heap_gc_pause_and_latency_are_observable", "gc_pause_has_trace_evidence"],
    "NT07": ["downstream_occupancy_and_latency_are_observable", "downstream_latency_changes_with_step"],
    "NT08": ["replicas_cpu_work_and_latency_are_observable", "capacity_change_is_observable"],
    "NT09": ["target_issued_rps_threads_and_latency_are_observable", "generator_wait_is_separate_from_app_latency"],
    "NT10": ["per_stage_inputs_are_observable", "matched_stage_comparison_is_required"],
    "TEMPORAL": ["background_cpu_and_downstream_episodes_are_observable", "full_lag_profile_is_required_when_supported"],
}

_FORBIDDEN = {
    "NT01": ["flat_cpu_rho_is_required", "universal_cpu_percent_is_causal_proof"],
    "NT02": ["marginal_association_proves_cpu_bottleneck", "partial_rho_zero_is_required"],
    "NT03": ["cpu_explains_db_limit_from_workload_correlation"],
    "NT04": ["no_cpu_correlation_proves_health"],
    "NT05": ["normalized_cpu_proves_capacity_headroom"],
    "NT06": ["missing_whole_run_rho_proves_no_gc_spikes"],
    "NT07": ["shared_timestamp_is_causal_proof"],
    "NT08": ["whole_run_is_stationary", "universal_cpu_claim"],
    "NT09": ["generator_rps_is_product_capacity_bound"],
    "NT10": ["global_mixture_replaces_matched_stages"],
    "TEMPORAL": ["external_schedule_proves_cpu_to_downstream_causality"],
}

_ALLOWED = {
    "NT02": ["association_is_descriptive_under_common_driver"],
    "NT07": ["matched_window_delta_is_descriptive"],
    "NT09": ["generator_limit_is_reported_as_a_guard"],
    "TEMPORAL": ["signed_lag_is_descriptive_when_clock_is_aligned"],
}


def _average(values):
    return sum(values) / len(values) if values else None


def _stage_spans(trace, stage, predicate=lambda request: True):
    return [item["end_us"] - item["start_us"] for request in trace["requests"]
            for item in [request["stages"][stage]] if item["start_us"] is not None and item["end_us"] is not None
            and predicate(request)]


def _workers(trace, resource):
    return max((item[f"{resource}_workers"] for item in trace.get("state_changes", [])), default=0)


def _has_wait(trace, stage, after=0):
    return any(item["start_us"] > item["entry_us"] for request in trace["requests"]
               for item in [request["stages"][stage]] if item["entry_us"] is not None
               and item["start_us"] is not None and item["entry_us"] >= after)


def _paired_waits(baseline, intervention, stage):
    def waits(trace):
        return {request["id"]: item["start_us"] - item["entry_us"] for request in trace["requests"]
                for item in [request["stages"][stage]] if item["entry_us"] is not None and item["start_us"] is not None}
    left, right = waits(baseline), waits(intervention)
    common = sorted(set(left) & set(right))
    return _average([left[item] for item in common]), _average([right[item] for item in common])


def _preflight(scenario, variant, traces):
    failures = []
    if not traces or any(trace.get("invalid_fixture_errors") for trace in traces):
        failures.append("trace is invalid or missing")
        return failures
    if any(trace.get("parameters", {}).get("scenario") != scenario for trace in traces):
        failures.append("trace scenario does not match configuration")
        return failures
    if scenario == "TEMPORAL":
        trace = traces[0]
        if not any(item["resource"] == "cpu" and item["request_id"] is None for item in trace["busy_intervals"]):
            failures.append("TEMPORAL background CPU has no busy trace interval")
        spans = _stage_spans(trace, "downstream")
        if not spans or max(spans) - min(spans) < 50_000:
            failures.append("TEMPORAL downstream episode has no 50ms trace delay step")
        return failures
    if len(traces) != 2:
        return ["paired configuration requires two traces"]
    baseline, intervention = traces
    if variant == "periodic_background_cpu":
        for trace in traces:
            pulses = [item for item in trace["busy_intervals"]
                      if item["resource"] == "cpu" and item["request_id"] is None]
            if not pulses or any(item["to_us"] - item["from_us"] != 20_000 for item in pulses):
                failures.append("NT02 periodic CPU has no busy trace pulses")
                break
    if scenario == "NT01":
        before, after = _paired_waits(baseline, intervention, "cpu")
        if not (_workers(intervention, "cpu") > _workers(baseline, "cpu") and before is not None and after is not None and after < before):
            failures.append("NT01 CPU intervention lacks capacity-and-queue trace evidence")
    elif scenario == "NT02":
        spans = {request["planned_rps"]: item["end_us"] - item["start_us"] for request in baseline["requests"]
                 for item in [request["stages"]["downstream"]] if item["start_us"] is not None and item["end_us"] is not None}
        if not (_workers(intervention, "cpu") > _workers(baseline, "cpu") and len(set(spans.values())) > 1):
            failures.append("NT02 common driver lacks capacity-or-downstream trace evidence")
    elif scenario == "NT03":
        before, after = _paired_waits(baseline, intervention, "db")
        if not (_workers(intervention, "db") > _workers(baseline, "db") and before is not None and after is not None and after < before):
            failures.append("NT03 DB intervention lacks capacity-and-queue trace evidence")
    elif scenario == "NT04":
        changed = any(item["reason"] == "pool_capacity_change" and item["pool_capacity"] == 2
                      for item in baseline["state_changes"])
        intervention_stable = not any(item["reason"] == "pool_capacity_change" for item in intervention["state_changes"])
        if not (changed and intervention_stable and _has_wait(baseline, "pool", 300_000_000)):
            failures.append("NT04 pool limit has no state-and-wait trace evidence")
    elif scenario == "NT05":
        quotas = [item.get("quota", {}) for item in baseline["busy_intervals"] if item["resource"] == "cpu"]
        restored = [item.get("quota", {}) for item in intervention["busy_intervals"] if item["resource"] == "cpu"]
        if not any(item.get("denominator") == 4 for item in quotas) or any(item.get("denominator") == 4 for item in restored):
            failures.append("NT05 throttle has no quota-weighted CPU trace interval")
        def service_times(trace):
            return {request['id']:stage['end_us']-stage['start_us'] for request in trace['requests']
                    for stage in [request['stages']['cpu']] if stage['start_us'] is not None
                    and stage['start_us']>=300_000_000 and stage['end_us'] is not None}
        left,right = service_times(baseline),service_times(intervention)
        common = set(left)&set(right)
        if not common or sum(left[key] for key in common)<=sum(right[key] for key in common):
            failures.append('NT05 throttle has no paired CPU service duration effect')
    elif scenario == "NT06":
        if not (baseline["gc_pauses"] and not intervention["gc_pauses"] and
                any(item["reason"] == "gc_pause" for item in baseline["state_changes"])):
            failures.append("NT06 GC intervention has no pause trace evidence")
    elif scenario == "NT07":
        before = _average(_stage_spans(baseline, "downstream", lambda request: request["stages"]["downstream"]["start_us"] < 300_000_000))
        after = _average(_stage_spans(baseline, "downstream", lambda request: request["stages"]["downstream"]["start_us"] >= 300_000_000))
        control_before = _average(_stage_spans(intervention, "downstream", lambda request: request["stages"]["downstream"]["start_us"] < 300_000_000))
        control_after = _average(_stage_spans(intervention, "downstream", lambda request: request["stages"]["downstream"]["start_us"] >= 300_000_000))
        if (before is None or after is None or control_before is None or control_after is None or
                after - before < 50_000 or control_after != control_before):
            failures.append("NT07 downstream step has no trace effect")
    elif scenario == "NT08":
        changed = any(item["reason"] == "cpu_capacity_change" and item["cpu_workers"] == 2
                      for item in baseline["state_changes"])
        if not (changed and _workers(intervention, "cpu") == 1):
            failures.append("NT08 autoscaling has no capacity-change trace evidence")
    elif scenario == "NT09":
        delays = lambda trace: _average([request["start_us"] - request["planned_us"] for request in trace["requests"]
                                         if request["start_us"] is not None])
        if not (delays(baseline) is not None and delays(intervention) is not None and
                delays(baseline) > delays(intervention)):
            failures.append("NT09 generator intervention has no issued/wait trace evidence")
    elif scenario == "NT10":
        rates = lambda trace: [stage["rate_rps"] for stage in trace["parameters"]["stages"]]
        if not (rates(baseline) == rates(intervention) and len(baseline["requests"]) != len(intervention["requests"])):
            failures.append("NT10 stage mixture has no matched-stage trace evidence")
    return failures


def builder(configuration, traces, runs):
    """Build a fixed, test-only applicability contract without product output."""
    scenario = configuration.get("scenario")
    if scenario not in _FACTS:
        raise ValueError("unknown applicability scenario")
    bindings = ("id", "metric", "unit", "entity", "role", "aggregation")
    signal_sets = [{tuple(series.get(field) for field in bindings) for series in run.get("resources", {}).get("series", [])}
                   for run in runs]
    signals = set.intersection(*signal_sets) if signal_sets else set()
    variant = configuration.get("variant", "")
    abstentions, gaps = [], []
    if "unknown" in variant:
        abstentions.append("no_time_order_claim_with_unknown_clock")
    if "coarse_10s" in variant or scenario == "TEMPORAL" and "10s" in variant:
        abstentions.append("no_exact_5s_lag_on_coarse_grid")
    if "missing_db" in variant or "independent_db_mask" in variant:
        abstentions.append("missing_db_cells_are_not_imputed")
    if scenario == "NT06":
        abstentions.append("short_nonmonotonic_gc_spikes_need_not_form_episode")
    if scenario == "NT01":
        abstentions.append("flat_saturated_cpu_need_not_have_high_rho")
    if scenario == "NT10" and not configuration.get("conditions_confirmed", False):
        abstentions.append("matched_comparison_is_unconfirmed")
    failures = _preflight(scenario, variant, traces)
    terminal_queued = any(request["status"] == "timed_out" and any(
        stage["entry_us"] is not None and stage["start_us"] is None
        for stage in request["stages"].values()) for trace in traces for request in trace["requests"])
    return {
        "schema_version": "applicability-contract.v1",
        "case_id": configuration.get("id"), "scenario": scenario, "variant": variant,
        "available_signals": [dict(zip(bindings, signal)) for signal in sorted(signals)],
        "topology": [{key: trace["parameters"].get(key) for key in ("cpu_workers", "db_workers", "pool_capacity", "generator_threads")}
                     for trace in traces],
        "trace_parameters": [{key: value for key, value in trace["parameters"].items()
                              if key not in {"intervention", "declared_variants", "stress_variants"}}
                             for trace in traces],
        "expected_observable_facts": _FACTS[scenario],
        "allowed_interpretations": _ALLOWED.get(scenario, ["observed_evidence_is_descriptive"]),
        "forbidden_interpretations": _FORBIDDEN[scenario],
        "expected_abstentions": abstentions, "capability_gaps": gaps,
        "trace_warnings": ["terminal_queued_stage_requires_export_review"] if terminal_queued else [],
        "status": "INVALID_FIXTURE" if failures else "CAPABILITY_GAP" if gaps else "PASS",
        "preflight": {"status": "INVALID_FIXTURE" if failures else "PASS", "failures": failures},
    }

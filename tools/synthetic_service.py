"""Test-only deterministic FIFO service used by statistical-validation fixtures."""

from __future__ import annotations

import csv
import hashlib
import heapq
import json
import math
import random
from bisect import bisect_left
from collections import deque
from copy import deepcopy
from decimal import Decimal, ROUND_HALF_EVEN
from pathlib import Path


US_PER_SECOND = 1_000_000
DEFAULT_START_EPOCH_MS = 1_704_067_200_000


def scenario_parameters(scenario, intervention=False, noisy=False):
    """Return the declared, mutable parameter dictionary for one methodology family."""
    if scenario not in {f"NT{i:02d}" for i in range(1, 11)}:
        raise ValueError("unknown scenario: " + str(scenario))
    parameters = {
        "scenario": scenario,
        "intervention": bool(intervention),
        "cpu_workers": 1,
        "cpu_demand_us": 10_000,
        "db_workers": 8,
        "db_demand_us": 2_000,
        "downstream_us": 38_000,
        "pool_capacity": 256,
        "timeout_us": 60 * US_PER_SECOND,
        "drain_us": 60 * US_PER_SECOND,
        "stages": [{"rate_rps": rate, "duration_us": 300 * US_PER_SECOND}
                   for rate in (40, 70, 90, 110)],
        "sampling": {"step_us": US_PER_SECOND, "jitter_us": 200_000 if noisy else 0,
                     "clock_offset_us": 0, "semantics": "interval_mean/interval_rate"},
        "noise": bool(noisy),
        "declared_variants": ["clean", "noisy"],
        "stress_variants": {},
    }
    if scenario == "NT01":
        parameters["cpu_workers"] = 2 if intervention else 1
        parameters["declared_variants"].append("warmup_cpu_demand_x2_first_60s")
        parameters["stress_variants"] = {"warmup_cpu_demand_x2_first_60s": {
            "cpu_demand_multiplier_schedule": [
                {"from_us": 0, "numerator": 2, "denominator": 1},
                {"from_us": 60 * US_PER_SECOND, "numerator": 1, "denominator": 1},
            ]}}
    elif scenario == "NT02":
        parameters.update(cpu_workers=8 if intervention else 4,
                          downstream_per_planned_rps_us=500)
        parameters["declared_variants"].append("periodic_background_cpu")
        parameters["stress_variants"] = {"periodic_background_cpu": {
            "background_cpu_intervals": [
                {"from_us": (120 + 120 * block) * US_PER_SECOND + offset,
                 "to_us": (120 + 120 * block) * US_PER_SECOND + offset + 20_000, "workers": 1}
                for block in range(9) for offset in range(0, 20 * US_PER_SECOND, 100_000)
            ]}}
    elif scenario == "NT03":
        parameters.update(cpu_workers=4, db_workers=2 if intervention else 1,
                          db_demand_us=20_000,
                          stages=[{"rate_rps": 80, "duration_us": 300 * US_PER_SECOND}])
        parameters["declared_variants"] += ["missing_db_290s_320s", "independent_db_mask_5pct"]
        parameters["stress_variants"] = {
            "missing_db_290s_320s": {"sampling": {"missing_intervals": [
                {"series": "system-db-work", "from_us": 290 * US_PER_SECOND, "to_us": 320 * US_PER_SECOND}]}},
            "independent_db_mask_5pct": {"sampling": {"missing_probability": .05,
                                                          "missing_series": "system-db-work"}},
        }
    elif scenario == "NT04":
        parameters.update(cpu_workers=4,
                          stages=[{"rate_rps": 60, "duration_us": 600 * US_PER_SECOND}])
        if not intervention:
            parameters["pool_capacity_changes"] = [{"from_us": 300 * US_PER_SECOND, "capacity": 2}]
    elif scenario == "NT05":
        parameters.update(cpu_workers=4,
                          stages=[{"rate_rps": 80, "duration_us": 600 * US_PER_SECOND}])
        if not intervention:
            parameters["cpu_quota_schedule"] = [
                {"from_us": 0, "numerator": 1, "denominator": 1},
                {"from_us": 300 * US_PER_SECOND, "numerator": 1, "denominator": 4},
            ]
    elif scenario == "NT06":
        parameters.update(cpu_workers=4, allocation_bytes=0 if intervention else 1_048_576,
                          heap_bytes=64 * 1024 * 1024, heap_trigger_bytes=128 * 1024 * 1024,
                          gc_pause_us=200_000,
                          stages=[{"rate_rps": 60, "duration_us": 600 * US_PER_SECOND}])
    elif scenario == "NT07":
        parameters.update(cpu_workers=4,
                          stages=[{"rate_rps": 60, "duration_us": 600 * US_PER_SECOND}])
        if not intervention:
            parameters["downstream_changes"] = [{"from_us": 300 * US_PER_SECOND,
                                                   "to_us": 600 * US_PER_SECOND, "add_us": 50_000}]
        parameters["declared_variants"].append("resource_clock_offset_unknown_and_corrected")
        parameters["stress_variants"] = {
            "resource_clock_offset_unknown": {"sampling": {"clock_offset_us": 5 * US_PER_SECOND,
                                                               "clock_alignment": "unknown"}},
            "resource_clock_offset_corrected": {"sampling": {"clock_offset_us": 5 * US_PER_SECOND,
                                                                 "clock_correction": True,
                                                                 "clock_alignment": "declared_aligned"}},
        }
    elif scenario == "NT08":
        if not intervention:
            parameters["cpu_worker_changes"] = [{"from_us": 900 * US_PER_SECOND, "workers": 2}]
        parameters["declared_variants"].append("coarse_10s_sampling")
        parameters["stress_variants"] = {"coarse_10s_sampling": {"sampling": {"step_us": 10 * US_PER_SECOND}}}
    elif scenario == "NT09":
        parameters.update(cpu_workers=4, generator_threads=100 if intervention else 20,
                          stages=[{"rate_rps": 100, "duration_us": 600 * US_PER_SECOND}],
                          downstream_changes=[{"from_us": 300 * US_PER_SECOND,
                                               "to_us": 600 * US_PER_SECOND, "add_us": 500_000}])
    elif scenario == "NT10":
        parameters.update(cpu_workers=4,
                          stages=[{"rate_rps": rate, "duration_us": duration * US_PER_SECOND}
                                  for rate, duration in zip((40, 70, 90),
                                                            (300, 300, 300) if intervention else (300, 600, 900))])
        parameters["declared_variants"] += ["same_stage_user_confirmed", "same_stage_unconfirmed"]
    return parameters


def _integer(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")
    return value


def _rng(scenario, seed, stream):
    namespace = f"ltv-applicability-v1/{scenario}/{seed}/{stream}"
    return random.Random(int.from_bytes(hashlib.sha256(namespace.encode("utf-8")).digest(), "big"))


def _pauses(parameters):
    return sorted((int(item["from_us"]), int(item["to_us"])) for item in parameters.get("gc_pauses", [])
                  if item["to_us"] > item["from_us"])


def _inside_pause(time_us, pauses):
    for start, end in pauses:
        if start <= time_us < end:
            return end
        if start > time_us:
            break
    return time_us


def _cpu_plan(start_us, work_us, numerator, denominator, pauses):
    """Consume actual CPU work, leaving GC pauses out of CPU busy intervals."""
    time_us, remaining, intervals = start_us, work_us, []
    while remaining:
        time_us = _inside_pause(time_us, pauses)
        next_pause = next(((start, end) for start, end in pauses if start > time_us), None)
        if next_pause is None:
            wall = math.ceil(remaining * denominator / numerator)
            intervals.append((time_us, time_us + wall, remaining))
            return time_us, time_us + wall, intervals
        pause_start, pause_end = next_pause
        possible = (pause_start - time_us) * numerator // denominator
        if remaining <= possible:
            wall = math.ceil(remaining * denominator / numerator)
            intervals.append((time_us, time_us + wall, remaining))
            return time_us, time_us + wall, intervals
        if possible:
            intervals.append((time_us, pause_start, possible))
            remaining -= possible
        time_us = pause_end
    return start_us, start_us, intervals


def _quota_at(schedule, start_us):
    current = (1, 1)
    for entry in sorted(schedule, key=lambda item: item["from_us"]):
        if entry["from_us"] > start_us:
            break
        current = (entry["numerator"], entry["denominator"])
    return current


def _later_of_pause(time_us, pauses):
    return _inside_pause(time_us, pauses)


def _mean_over_schedule(start_us, end_us, changes, value_at):
    points = {start_us, end_us}
    points.update(change["from_us"] for change in changes if start_us < change["from_us"] < end_us)
    return sum((right - left) * value_at(left) for left, right in zip(sorted(points), sorted(points)[1:])) / (end_us - start_us)


def _planned_requests(parameters, seed):
    if "arrivals_us" in parameters:
        return [{"planned_us": _integer(value, "arrival"), "planned_rps": 0}
                for value in parameters["arrivals_us"]]
    result, offset = [], 0
    arrival_rng = _rng(parameters.get("scenario", "manual"), seed, "arrival")
    for stage in parameters["stages"]:
        rate, duration = stage["rate_rps"], stage["duration_us"]
        count = duration * rate // US_PER_SECOND
        intervals = [arrival_rng.uniform(.9, 1.1) for _ in range(count)] if parameters.get("noise") else [1] * count
        total = sum(intervals)
        cumulative = 0
        for index, interval in enumerate(intervals):
            result.append({"planned_us": offset + int(duration * cumulative / total), "planned_rps": rate})
            cumulative += interval
        offset += duration
    return result


def _observations(parameters, seed, duration_us):
    sampling = parameters.get("sampling", {})
    step_us = sampling.get("step_us", US_PER_SECOND)
    jitter_us = sampling.get("jitter_us", 0)
    offset_us = sampling.get("clock_offset_us", 0)
    if step_us < US_PER_SECOND or step_us % 1000 or jitter_us < 0:
        raise ValueError("unsupported sampling grid")
    rng = _rng(parameters.get("scenario", "manual"), seed, "observation")
    result = []
    for index in range(max(1, math.ceil(duration_us / step_us))):
        jitter = rng.randint(-jitter_us, jitter_us) if jitter_us else 0
        true_from = index * step_us + jitter
        result.append({"true_from_us": true_from, "observed_at_us": true_from + offset_us,
                       "jitter_us": jitter})
    return result


def simulate(parameters, seed):
    """Simulate a FIFO admission-pool/CPU/DB/timer service in integer microseconds."""
    parameters = deepcopy(parameters)
    planned = _planned_requests(parameters, seed)
    duration_us = parameters.get("duration_us", sum(stage["duration_us"] for stage in parameters.get("stages", [])))
    drain_end_us = duration_us + parameters.get("drain_us", 60 * US_PER_SECOND)
    pauses = _pauses(parameters)
    for name in ("cpu_workers", "cpu_demand_us", "db_workers", "db_demand_us", "downstream_us",
                 "pool_capacity", "timeout_us", "drain_us"):
        _integer(parameters.get(name, 0), name)
    if not parameters.get("cpu_workers") or not parameters.get("db_workers") or not parameters.get("pool_capacity"):
        raise ValueError("workers and pool capacity must be positive")

    cpu_rng = _rng(parameters.get("scenario", "manual"), seed, "cpu")
    db_rng = _rng(parameters.get("scenario", "manual"), seed, "db")
    quota_schedule = parameters.get("cpu_quota_schedule", [{"from_us": 0, "numerator": 1, "denominator": 1}])
    demand_multiplier_schedule = parameters.get("cpu_demand_multiplier_schedule", [
        {"from_us": 0, "numerator": 1, "denominator": 1}])
    requests = []
    for request_id, item in enumerate(planned):
        cpu = parameters["cpu_demand_us"]
        db = parameters["db_demand_us"]
        numerator, denominator = _quota_at(demand_multiplier_schedule, item["planned_us"])
        cpu = math.ceil(cpu * numerator / denominator)
        if parameters.get("noise"):
            cpu = round(cpu * cpu_rng.uniform(.8, 1.2))
            db = round(db * db_rng.uniform(.8, 1.2))
        requests.append({
            "id": request_id, "planned_us": item["planned_us"], "planned_rps": item["planned_rps"],
            "start_us": None, "end_us": None, "status": "generator_wait",
            "cpu_demand_us": cpu, "db_demand_us": db,
            "stages": {stage: {"entry_us": None, "start_us": None, "end_us": None}
                       for stage in ("pool", "cpu", "db", "downstream")},
            "holding": None, "worker": None, "pool_held": False, "cpu_token": 0,
            "cpu_quota": None,
        })

    events, event_number = [], 0
    def schedule(time_us, priority, request_id, kind, data=None):
        nonlocal event_number
        heapq.heappush(events, (time_us, priority, request_id, event_number, kind, data))
        event_number += 1

    for request in requests:
        schedule(request["planned_us"], 3, request["id"], "planned")
    for change in parameters.get("cpu_worker_changes", []):
        schedule(change["from_us"], 2, -1, "cpu_workers", change)
    for change in parameters.get("pool_capacity_changes", []):
        schedule(change["from_us"], 2, -1, "pool_capacity", change)
    for change in quota_schedule:
        if change["from_us"]:
            schedule(change["from_us"], 2, -1, "quota_change", change)
    for interval_id, interval in enumerate(parameters.get("background_cpu_intervals", [])):
        _integer(interval["from_us"], "background cpu start")
        _integer(interval["to_us"], "background cpu end")
        if interval["to_us"] <= interval["from_us"] or interval.get("workers", 1) != 1:
            raise ValueError("background CPU interval must reserve one worker for positive time")
        schedule(interval["from_us"], 2, -1, "background_cpu_start", (interval_id, interval))

    cpu_workers = parameters["cpu_workers"]
    db_workers = parameters["db_workers"]
    pool_capacity = parameters["pool_capacity"]
    available = {"cpu": set(range(cpu_workers)), "db": set(range(db_workers))}
    assigned = {"cpu": {}, "db": {}}
    queues = {"pool": deque(), "cpu": deque(), "db": deque()}
    generator = deque()
    generator_threads = parameters.get("generator_threads")
    generator_active = 0
    pool_active = 0
    busy = []
    busy_by_request = {}
    state_changes = []
    heap_current = parameters.get("heap_bytes")

    def snapshot(time_us, reason):
        state_changes.append({"time_us": time_us, "reason": reason, "cpu_workers": cpu_workers,
                              "db_workers": db_workers, "pool_capacity": pool_capacity,
                              "cpu_quota": _quota_at(quota_schedule, time_us),
                              "heap_bytes": heap_current,
                              "queues": {name: sum(requests[request_id]["status"] == "active" for request_id in queue)
                                         for name, queue in queues.items()},
                              "generator_occupancy": generator_active,
                              "generator_waiting": len(generator)})

    def finish(request, status, time_us):
        nonlocal generator_active, pool_active
        if request["status"] in {"completed", "timed_out", "rejected"}:
            return
        resource = request["holding"]
        if resource:
            stage = request["stages"][resource]
            if stage["start_us"] is not None and stage["start_us"] <= time_us:
                stage["end_us"] = time_us
            elif stage["start_us"] is not None:
                stage["start_us"] = stage["end_us"] = None
            worker = request["worker"]
            assigned[resource].pop(worker, None)
            available[resource].add(worker)
            request["holding"] = request["worker"] = None
            for interval in busy_by_request.pop(request["id"], []):
                if interval["from_us"] >= time_us:
                    busy.remove(interval)
                elif interval["to_us"] > time_us:
                    fraction = (time_us - interval["from_us"]) / (interval["to_us"] - interval["from_us"])
                    interval["work_us"] = int(interval["work_us"] * fraction)
                    interval["to_us"] = time_us
            pump(resource, time_us)
        else:
            downstream = request["stages"]["downstream"]
            if downstream["start_us"] is not None and downstream["end_us"] is not None and downstream["end_us"] > time_us:
                downstream["end_us"] = time_us
        if request["pool_held"]:
            pool_active -= 1
            request["pool_held"] = False
            request["stages"]["pool"]["end_us"] = time_us
            pump("pool", time_us)
        request["status"], request["end_us"] = status, time_us
        if request["start_us"] is not None:
            generator_active -= 1
            pump_generator(time_us)

    def plan_cpu(request, worker, time_us, work_us):
        numerator, denominator = request["cpu_quota"]
        start, end, intervals = _cpu_plan(_inside_pause(time_us, pauses), work_us, numerator, denominator, pauses)
        if request["stages"]["cpu"]["start_us"] is None:
            request["stages"]["cpu"]["start_us"] = start
        request["stages"]["cpu"]["end_us"] = end
        request["cpu_token"] += 1
        for begin, finish_at, work in intervals:
            interval = {"resource": "cpu", "worker": worker, "request_id": request["id"],
                        "from_us": begin, "to_us": finish_at, "work_us": work,
                        "quota": {"numerator": numerator, "denominator": denominator}}
            busy.append(interval)
            busy_by_request.setdefault(request["id"], []).append(interval)
        schedule(end, 0, request["id"], "cpu_done", {"worker": worker, "token": request["cpu_token"]})

    def begin_cpu(request, worker, time_us):
        request["holding"], request["worker"] = "cpu", worker
        request["cpu_quota"] = _quota_at(quota_schedule, _inside_pause(time_us, pauses))
        plan_cpu(request, worker, time_us, request["cpu_demand_us"])

    def interrupt_cpu(request, time_us, resume_us):
        completed = 0
        for interval in busy_by_request.get(request["id"], [])[:]:
            if interval["to_us"] <= time_us:
                completed += interval["work_us"]
            elif interval["from_us"] < time_us:
                portion = interval["work_us"] * (time_us - interval["from_us"]) // (interval["to_us"] - interval["from_us"])
                interval["work_us"], interval["to_us"] = portion, time_us
                completed += portion
            else:
                busy.remove(interval)
                busy_by_request[request["id"]].remove(interval)
        remaining = request["cpu_demand_us"] - completed
        if remaining:
            plan_cpu(request, request["worker"], resume_us, remaining)

    def trigger_gc(time_us):
        nonlocal heap_current
        allocation = parameters.get("allocation_bytes", 0)
        if not allocation or heap_current is None:
            return
        heap_current += allocation
        snapshot(time_us, "heap_allocation")
        trigger = parameters.get("heap_trigger_bytes", heap_current + 1)
        if heap_current < trigger:
            return
        snapshot(time_us, "heap_trigger")
        pause_end = time_us + parameters.get("gc_pause_us", 0)
        if pause_end == time_us:
            heap_current = parameters["heap_bytes"]
            return
        pauses.append((time_us, pause_end))
        pauses.sort()
        heap_current = parameters["heap_bytes"]
        snapshot(time_us, "gc_pause")
        for request_id in list(assigned["cpu"].values()):
            if request_id is not None:
                interrupt_cpu(requests[request_id], time_us, pause_end)

    def begin_db(request, worker, time_us):
        request["holding"], request["worker"] = "db", worker
        start, end = time_us, _later_of_pause(time_us + request["db_demand_us"], pauses)
        request["stages"]["db"].update(start_us=start, end_us=end)
        if end > start:
            interval = {"resource": "db", "worker": worker, "request_id": request["id"],
                        "from_us": start, "to_us": end, "work_us": request["db_demand_us"]}
            busy.append(interval)
            busy_by_request.setdefault(request["id"], []).append(interval)
        schedule(end, 0, request["id"], "db_done", worker)

    def pump(resource, time_us):
        nonlocal pool_active
        if resource == "pool":
            while queues["pool"] and pool_active < pool_capacity:
                request = requests[queues["pool"].popleft()]
                if request["status"] != "active":
                    continue
                pool_active += 1
                request["pool_held"] = True
                request["stages"]["pool"]["start_us"] = time_us
                request["stages"]["cpu"]["entry_us"] = time_us
                queues["cpu"].append(request["id"])
                pump("cpu", time_us)
            return
        capacity = cpu_workers if resource == "cpu" else db_workers
        while queues[resource] and available[resource]:
            worker = min(available[resource])
            if worker >= capacity:
                break
            request = requests[queues[resource].popleft()]
            if request["status"] != "active":
                continue
            available[resource].remove(worker)
            assigned[resource][worker] = request["id"]
            (begin_cpu if resource == "cpu" else begin_db)(request, worker, time_us)

    def issue(request, time_us):
        nonlocal generator_active
        request["status"], request["start_us"] = "active", time_us
        generator_active += 1
        request["stages"]["pool"]["entry_us"] = time_us
        if len(queues["pool"]) >= parameters.get("pool_queue_limit", math.inf):
            finish(request, "rejected", time_us)
            return
        queues["pool"].append(request["id"])
        schedule(time_us + parameters["timeout_us"], 1, request["id"], "timeout")
        pump("pool", time_us)

    def pump_generator(time_us):
        while generator and (generator_threads is None or generator_active < generator_threads):
            request = requests[generator.popleft()]
            issue(request, time_us)

    snapshot(0, "initial")
    while events and events[0][0] <= drain_end_us:
        time_us, _priority, request_id, _number, kind, data = heapq.heappop(events)
        request = requests[request_id] if request_id >= 0 else None
        deferred = _inside_pause(time_us, pauses)
        if kind in {"db_done", "completed"} and deferred != time_us:
            schedule(deferred, 0, request_id, kind, data)
            continue
        if kind == "planned":
            if generator_threads is not None and generator_active >= generator_threads:
                generator.append(request_id)
            else:
                issue(request, time_us)
        elif kind == "timeout" and request["status"] == "active":
            finish(request, "timed_out", time_us)
        elif (kind == "cpu_done" and request["status"] == "active" and request["holding"] == "cpu"
              and data["token"] == request["cpu_token"]):
            worker = data["worker"]
            assigned["cpu"].pop(worker, None)
            available["cpu"].add(worker)
            busy_by_request.pop(request_id, None)
            request["holding"] = request["worker"] = None
            request["stages"]["db"]["entry_us"] = time_us
            queues["db"].append(request_id)
            trigger_gc(time_us)
            pump("cpu", time_us)
            pump("db", time_us)
        elif kind == "db_done" and request["status"] == "active" and request["holding"] == "db":
            worker = request["worker"]
            assigned["db"].pop(worker, None)
            available["db"].add(worker)
            busy_by_request.pop(request_id, None)
            request["holding"] = request["worker"] = None
            downstream = parameters["downstream_us"] + request["planned_rps"] * parameters.get("downstream_per_planned_rps_us", 0)
            for change in parameters.get("downstream_changes", []):
                if change["from_us"] <= time_us < change["to_us"]:
                    downstream += change["add_us"]
            request["stages"]["downstream"].update(entry_us=time_us, start_us=time_us,
                                                       end_us=_later_of_pause(time_us + downstream, pauses))
            schedule(request["stages"]["downstream"]["end_us"], 0, request_id, "completed")
            pump("db", time_us)
        elif kind == "completed" and request["status"] == "active":
            finish(request, "completed", time_us)
        elif kind == "background_cpu_start":
            interval_id, interval = data
            if not available["cpu"]:
                raise ValueError("background CPU schedule has no available worker")
            worker = min(available["cpu"])
            available["cpu"].remove(worker)
            assigned["cpu"][worker] = None
            busy.append({"resource": "cpu", "worker": worker, "request_id": None,
                         "from_us": time_us, "to_us": interval["to_us"],
                         "work_us": interval["to_us"] - time_us})
            schedule(interval["to_us"], 0, -1, "background_cpu_done", worker)
            snapshot(time_us, "background_cpu_start")
        elif kind == "background_cpu_done":
            worker = data
            if assigned["cpu"].get(worker) is not None:
                raise ValueError("background CPU worker was reassigned")
            assigned["cpu"].pop(worker)
            available["cpu"].add(worker)
            snapshot(time_us, "background_cpu_done")
            pump("cpu", time_us)
        elif kind == "cpu_workers":
            cpu_workers = data["workers"]
            available["cpu"].update(worker for worker in range(cpu_workers) if worker not in assigned["cpu"])
            snapshot(time_us, "cpu_capacity_change")
            pump("cpu", time_us)
        elif kind == "pool_capacity":
            pool_capacity = data["capacity"]
            snapshot(time_us, "pool_capacity_change")
            pump("pool", time_us)
        elif kind == "quota_change":
            snapshot(time_us, "cpu_quota_change")

    for request in requests:
        if request["status"] == "active":
            request["status"], request["end_us"] = "in_flight", None
            for interval in busy[:]:
                if interval["request_id"] != request["id"]:
                    continue
                if interval["from_us"] >= drain_end_us:
                    busy.remove(interval)
                elif interval["to_us"] > drain_end_us:
                    fraction = (drain_end_us - interval["from_us"]) / (interval["to_us"] - interval["from_us"])
                    interval["work_us"] = int(interval["work_us"] * fraction)
                    interval["to_us"] = drain_end_us
            for stage in request["stages"].values():
                if stage["end_us"] is not None and stage["end_us"] > drain_end_us:
                    stage["end_us"] = None
        request.pop("holding")
        request.pop("worker")
        request.pop("pool_held")
        request.pop("cpu_token")
        request.pop("cpu_quota")
    counts = {"planned": len(requests), "arrivals": sum(r["start_us"] is not None for r in requests)}
    counts.update({status: sum(r["status"] == status for r in requests)
                   for status in ("completed", "timed_out", "rejected", "generator_wait", "in_flight")})
    snapshot(drain_end_us, "drain_end")
    trace = {"parameters": parameters, "seed": seed,
              "observations": _observations(parameters, seed, duration_us),
              "requests": requests, "busy_intervals": busy,
              "state_changes": state_changes, "counts": counts, "duration_us": duration_us,
              "drain_end_us": drain_end_us, "gc_pauses": pauses}
    errors = validate_trace(trace)
    if errors:
        trace["invalid_fixture_errors"] = errors
    return trace


def _capacity_at(parameters, resource, time_us):
    current = parameters[f"{resource}_workers"]
    for change in parameters.get(f"{resource}_worker_changes", []):
        if change["from_us"] <= time_us:
            current = change["workers"]
    return current


def validate_trace(trace):
    """Return independent physical/accounting violations; an empty list is valid."""
    errors, requests, counts = [], trace.get("requests", []), trace.get("counts", {})
    arrivals = sum(request.get("start_us") is not None for request in requests)
    terminal = sum(request.get("status") in {"completed", "rejected", "timed_out", "in_flight"}
                   and request.get("start_us") is not None for request in requests)
    if arrivals != terminal:
        errors.append("arrival conservation failed")
    if counts.get("arrivals") != arrivals or counts.get("planned") != len(requests):
        errors.append("counts disagree with requests")
    if counts.get("generator_wait") != sum(r["status"] == "generator_wait" for r in requests):
        errors.append("generator wait count disagrees")
    for request in requests:
        if request["status"] == "generator_wait" and request["start_us"] is not None:
            errors.append(f"generator wait issued: {request['id']}")
        if request["start_us"] is not None and request["start_us"] < request["planned_us"]:
            errors.append(f"start before plan: {request['id']}")
        if request["end_us"] is not None and request["start_us"] is not None and request["end_us"] < request["start_us"]:
            errors.append(f"negative latency: {request['id']}")
        times = []
        for stage in ("pool", "cpu", "db", "downstream"):
            values = request["stages"][stage]
            times.extend(value for value in values.values() if value is not None)
            if values["start_us"] is not None and values["entry_us"] is not None and values["start_us"] < values["entry_us"]:
                errors.append(f"stage starts before entry: {request['id']}/{stage}")
            if values["end_us"] is not None and values["start_us"] is not None and values["end_us"] < values["start_us"]:
                errors.append(f"negative stage duration: {request['id']}/{stage}")
        if any(not isinstance(value, int) or value < 0 for value in times):
            errors.append(f"invalid timestamp: {request['id']}")
    parameters = trace.get("parameters", {})
    for resource in ("cpu", "db"):
        capacity = parameters["%s_workers" % resource]
        events, serial = [], 0
        for interval in (item for item in trace.get("busy_intervals", []) if item["resource"] == resource):
            start, end, work = interval["from_us"], interval["to_us"], interval["work_us"]
            if not isinstance(work, int) or work < 0 or work > end - start:
                errors.append(f"invalid work bounds: {resource}/{interval['request_id']}")
            if end <= start:
                if end < start:
                    errors.append(f"negative busy interval: {resource}/{interval['request_id']}")
                continue
            events.append((end, 0, serial, "end", interval))
            serial += 1
            events.append((start, 2, serial, "start", interval))
            serial += 1
        for change in parameters.get(f"{resource}_worker_changes", []):
            events.append((change["from_us"], 1, serial, "capacity", change["workers"]))
            serial += 1
        active, active_count = {}, 0
        for time_us, _order, _serial, kind, item in sorted(events):
            if kind == "end":
                worker = item['worker']
                active[worker] -= 1
                active_count -= 1
                if not active[worker]:
                    del active[worker]
            elif kind == "capacity":
                capacity = item
                if active_count > capacity:
                    errors.append(f"{resource} busy exceeds capacity at {time_us}")
            else:
                worker = item["worker"]
                if worker in active:
                    errors.append(f"{resource} worker overlap at {time_us}: {worker}")
                if worker >= capacity or active_count >= capacity:
                    errors.append(f"{resource} busy exceeds capacity at {time_us}")
                active[worker] = active.get(worker, 0) + 1
                active_count += 1
    return errors


def _overlap_work(interval, start_us, end_us):
    overlap = max(0, min(interval["to_us"], end_us) - max(interval["from_us"], start_us))
    duration = interval["to_us"] - interval["from_us"]
    return 0 if not duration else interval["work_us"] * overlap / duration


def _interval_totals(observations, intervals, step_us):
    """Integrate weighted half-open intervals over every observation window."""
    events = []
    for left, right, weight in intervals:
        if right > left and weight:
            events.extend(((left, weight), (right, -weight)))
    if not events:
        return [0] * len(observations)
    boundaries = sorted((time, index, side)
                        for index, observation in enumerate(observations)
                        for time, side in ((observation["true_from_us"], 0),
                                           (observation["true_from_us"] + step_us, 1)))
    events.sort()
    totals = [[0, 0] for _ in observations]
    cursor, event_index, active, area = min(events[0][0], boundaries[0][0]), 0, 0, 0
    for time_us, index, side in boundaries:
        while event_index < len(events) and events[event_index][0] <= time_us:
            event_time = events[event_index][0]
            area += active * (event_time - cursor)
            cursor = event_time
            while event_index < len(events) and events[event_index][0] == event_time:
                active += events[event_index][1]
                event_index += 1
        area += active * (time_us - cursor)
        cursor = time_us
        totals[index][side] = area
    return [end - start for start, end in totals]


def _wire_value(value):
    return None if value is None else float(Decimal(str(value)).quantize(Decimal(".000001"), rounding=ROUND_HALF_EVEN))


def export(trace, directory):
    """Write neutral ingest artifacts and evaluator-only trace, returning absolute paths."""
    if validate_trace(trace):
        raise ValueError("INVALID_FIXTURE: " + "; ".join(validate_trace(trace)))
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    load_path, resources_path, trace_path = (directory / "requests.jtl", directory / "resources.json", directory / "trace.json")
    with load_path.open("x", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["timeStamp", "elapsed", "label", "success"])
        writer.writeheader()
        for request in trace["requests"]:
            if request["status"] in {"completed", "timed_out", "rejected"}:
                elapsed = request["end_us"] - request["start_us"]
                writer.writerow({"timeStamp": trace['parameters'].get('start_epoch_ms', DEFAULT_START_EPOCH_MS) + request["start_us"] // 1000,
                                 "elapsed": math.ceil(elapsed / 1000), "label": "request",
                                 "success": str(request["status"] == "completed").lower()})
    sampling = trace["parameters"].get("sampling", {})
    step_us = sampling.get("step_us", US_PER_SECOND)
    observations = trace.get("observations")
    if observations is None or len(observations) != max(1, math.ceil(trace["duration_us"] / step_us)):
        raise ValueError("INVALID_FIXTURE: missing observation transforms")
    point_count = len(observations)
    def masked(series_id, observation, index):
        for interval in sampling.get("missing_intervals", []):
            if interval.get("series") == series_id and interval["from_us"] <= observation["true_from_us"] < interval["to_us"]:
                return True
        probability = sampling.get("missing_probability", 0)
        if sampling.get("missing_series") not in {None, series_id}:
            probability = 0
        if probability:
            stream = _rng(trace["parameters"].get("scenario", "manual"), trace["seed"], f"missing/{series_id}/{index}")
            return stream.random() < probability
        return False
    def values(resource, series_id):
        totals = _interval_totals(observations, [
            (item["from_us"], item["to_us"], item["work_us"] / (item["to_us"] - item["from_us"]))
            for item in trace["busy_intervals"] if item["resource"] == resource and item["to_us"] > item["from_us"]
        ], step_us)
        result = []
        for index, observation in enumerate(observations):
            if masked(series_id, observation, index):
                result.append(None)
                continue
            start = observation["true_from_us"]
            capacity = _mean_over_schedule(
                start, start + step_us, trace["parameters"].get(f"{resource}_worker_changes", []),
                lambda time_us: _capacity_at(trace["parameters"], resource, time_us))
            result.append(totals[index] / (capacity * step_us))
        return result

    def intervals_mean(series_id, intervals):
        totals = _interval_totals(observations, [(left, right, 1) for left, right in intervals], step_us)
        result = []
        for index, observation in enumerate(observations):
            if masked(series_id, observation, index):
                result.append(None)
                continue
            result.append(totals[index] / step_us)
        return result

    def queue_depth(series_id, stage):
        intervals = [(request["stages"][stage]["entry_us"],
                      request["stages"][stage]["start_us"] if request["stages"][stage]["start_us"] is not None
                      else trace["drain_end_us"])
                     for request in trace["requests"] if request["stages"][stage]["entry_us"] is not None]
        return intervals_mean(series_id, intervals)

    def generator_queue_depth():
        return intervals_mean("generator-queue", [
            (request["planned_us"], request["start_us"] if request["start_us"] is not None else trace["drain_end_us"])
            for request in trace["requests"]
        ])

    def schedule_mean(series_id, changes, value_at):
        result = []
        for index, observation in enumerate(observations):
            if masked(series_id, observation, index):
                result.append(None)
                continue
            start = observation["true_from_us"]
            result.append(_mean_over_schedule(start, start + step_us, changes, value_at))
        return result

    def target_rate(time_us):
        for offset, stage in _stage_offsets(trace["parameters"]):
            if offset <= time_us < offset + stage["duration_us"]:
                return stage.get("rate_rps", 0)
        return 0

    def state_mean(series_id, key):
        if trace["parameters"].get("heap_bytes") is None:
            return [None] * point_count
        changes = [(item["time_us"], item[key]) for item in trace["state_changes"]]
        intervals, previous_time, value = [], changes[0][0], changes[0][1]
        for time_us, next_value in changes[1:]:
            if time_us > previous_time:
                intervals.append((previous_time, time_us, value))
            previous_time, value = time_us, next_value
        intervals.append((previous_time, max(observation["true_from_us"] + step_us for observation in observations), value))
        return [None if masked(series_id, observation, index) else total / step_us
                for index, (observation, total) in enumerate(zip(
                    observations, _interval_totals(observations, intervals, step_us)))]
    def generator_occupancy(index):
        return generator_occupancies[index]
    def achieved_rps(index):
        observation = observations[index]
        if masked("generator-rps", observation, index):
            return None
        start, end = observation["true_from_us"], observation["true_from_us"] + step_us
        return (bisect_left(request_starts, end) - bisect_left(request_starts, start)) * US_PER_SECOND / step_us
    request_starts = sorted(request["start_us"] for request in trace["requests"] if request["start_us"] is not None)
    generator_totals = _interval_totals(observations, [
        (request["start_us"], request["end_us"] or trace["drain_end_us"], 1)
        for request in trace["requests"] if request["start_us"] is not None
    ], step_us)
    generator_occupancies = [None if masked("generator-threads", observation, index) else total / step_us
                             for index, (observation, total) in enumerate(zip(observations, generator_totals))]
    source = load_path.read_bytes()
    clock_offset_us = sampling.get("clock_offset_us", 0)
    clock_shift_us = 0 if sampling.get("clock_correction") else clock_offset_us
    start_epoch_ms = trace["parameters"].get("start_epoch_ms", DEFAULT_START_EPOCH_MS) + clock_shift_us // 1000
    resources = {
        "schema_version": "resource-snapshot.v1", "load_input_sha256": hashlib.sha256(source).hexdigest(),
        "start_epoch_ms": start_epoch_ms, "step_ms": step_us // 1000, "point_count": point_count,
        "series": [
            {"id": "system-cpu-work", "metric": "cpu_work", "unit": "ratio", "entity": "system",
             "role": "system", "aggregation": "interval_mean", "values": values("cpu", "system-cpu-work")},
            {"id": "system-db-work", "metric": "db_work", "unit": "ratio", "entity": "system",
             "role": "system", "aggregation": "interval_mean", "values": values("db", "system-db-work")},
            {"id": "admission-queue", "metric": "queue_depth", "unit": "requests", "entity": "admission",
             "role": "system", "aggregation": "interval_mean", "values": queue_depth("admission-queue", "pool")},
            {"id": "cpu-queue", "metric": "queue_depth", "unit": "requests", "entity": "compute",
             "role": "system", "aggregation": "interval_mean", "values": queue_depth("cpu-queue", "cpu")},
            {"id": "db-queue", "metric": "queue_depth", "unit": "requests", "entity": "database",
             "role": "system", "aggregation": "interval_mean", "values": queue_depth("db-queue", "db")},
            {"id": "generator-queue", "metric": "queue_depth", "unit": "requests", "entity": "generator",
             "role": "generator", "aggregation": "interval_mean", "values": generator_queue_depth()},
            {"id": "runtime-heap", "metric": "used_bytes", "unit": "bytes", "entity": "runtime",
             "role": "system", "aggregation": "interval_mean", "values": state_mean("runtime-heap", "heap_bytes")},
            {"id": "runtime-gc-pause", "metric": "pause_fraction", "unit": "ratio", "entity": "runtime",
             "role": "system", "aggregation": "interval_mean", "values": intervals_mean(
                 "runtime-gc-pause", trace["gc_pauses"])},
            {"id": "cpu-service-quota", "metric": "quota_fraction", "unit": "ratio", "entity": "compute",
             "role": "system", "aggregation": "interval_mean", "values": schedule_mean(
                 "cpu-service-quota", trace["parameters"].get("cpu_quota_schedule", []),
                 lambda time_us: _quota_at(trace["parameters"].get("cpu_quota_schedule", []), time_us)[0] /
                 _quota_at(trace["parameters"].get("cpu_quota_schedule", []), time_us)[1])},
            {"id": "service-replicas", "metric": "worker_count", "unit": "count", "entity": "service",
             "role": "system", "aggregation": "interval_mean", "values": schedule_mean(
                 "service-replicas", trace["parameters"].get("cpu_worker_changes", []),
                 lambda time_us: _capacity_at(trace["parameters"], "cpu", time_us))},
            {"id": "target-request-rate", "metric": "target_rate", "unit": "rps", "entity": "generator",
             "role": "generator", "aggregation": "interval_rate", "values": schedule_mean(
                 "target-request-rate", [{"from_us": offset} for offset, _ in _stage_offsets(trace["parameters"])] +
                 [{"from_us": trace["duration_us"]}], target_rate)},
            {"id": "downstream-wait", "metric": "in_flight_requests", "unit": "requests", "entity": "dependency",
             "role": "system", "aggregation": "interval_mean", "values": intervals_mean(
                 "downstream-wait", [(request["stages"]["downstream"]["start_us"], request["stages"]["downstream"]["end_us"])
                                     for request in trace["requests"]
                                     if request["stages"]["downstream"]["start_us"] is not None
                                     and request["stages"]["downstream"]["end_us"] is not None])},
            {"id": "generator-threads", "metric": "active_threads", "unit": "threads", "entity": "generator",
             "role": "generator", "aggregation": "interval_mean",
             "values": [generator_occupancy(index) for index in range(point_count)]},
            {"id": "generator-rps", "metric": "request_starts", "unit": "rps", "entity": "generator",
             "role": "generator", "aggregation": "interval_rate",
             "values": [achieved_rps(index) for index in range(point_count)]},
        ],
        "windows": [{"id": f"workload-{index + 1:02d}", "from_epoch_ms": start_epoch_ms + offset // 1000,
                     "to_epoch_ms": start_epoch_ms + (offset + stage["duration_us"]) // 1000}
                    for index, (offset, stage) in enumerate(_stage_offsets(trace["parameters"]))],
        "provenance": {"source_kind": "synthetic-service",
                       "query_semantics": "interval means/rates from declared observation intervals",
                       "clock_alignment": sampling.get("clock_alignment", "declared_aligned")},
    }
    for series in resources["series"]:
        series["values"] = [_wire_value(value) for value in series["values"]]
    with resources_path.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(resources, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n")
    with trace_path.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(trace, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n")
    return {"load": str(load_path), "resources": str(resources_path), "trace": str(trace_path)}


def _stage_offsets(parameters):
    offset = 0
    for stage in parameters.get("stages", [{"duration_us": parameters.get("duration_us", 1)}]):
        yield offset, stage
        offset += stage["duration_us"]

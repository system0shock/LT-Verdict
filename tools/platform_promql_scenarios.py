"""promtool scenarios for the platform PromQL templates (plan P0b/P0c).

Each scenario feeds synthetic series (the label contract of docs/user/platform-metric-packs.md) to one
rendered template and pins the exact result. Series use a 15 s test interval, evaluation at 5 m, and
$__interval is rendered as 60000ms. Expressions are wrapped in round(..., 0.0001): promtool compares floats exactly.
"""

from dataclasses import dataclass

from tools.platform_profile_templates import SIGNALS, render

NS = "shop"
SVC = "orders-svc"
SPIKE_LOW = "{low}+0x17 {high} {low}+0x22"  # one sample inside the last minute: index 18 of 41


@dataclass(frozen=True)
class Scenario:
    name: str
    signal: str
    peak: bool
    series: tuple  # (metric, labels, values)
    expected: object  # float, or None for "no sample" (a gap)


def pod_owner(pod):
    return ("namespace_workload_pod:kube_pod_owner:relabel", f'namespace="{NS}",pod="{pod}",workload="{SVC}"', "1+0x40")


def info(pod, container):
    return ("kube_pod_container_info", f'namespace="{NS}",pod="{pod}",container="{container}"', "1+0x40")


def memory(pod, container, used, limit, expected=True):
    """Usage, limit and the kube-state container record; pass None to drop one of the three."""
    rows = []
    if used:
        rows.append(("container_memory_working_set_bytes", f'namespace="{NS}",pod="{pod}",container="{container}"', used))
    if limit:
        rows.append(("kube_pod_container_resource_limits", f'namespace="{NS}",pod="{pod}",container="{container}",resource="memory"', limit))
    if expected:
        rows.append(info(pod, container))
    return rows


SCENARIOS = (
    Scenario(
        "memory: the worst container of the pod wins",
        "memory_limit_ratio", False,
        (*memory("a1", "app", "500+0x40", "1000+0x40"), *memory("a1", "sidecar", "80+0x40", "100+0x40"), pod_owner("a1")),
        0.8,
    ),
    Scenario(
        "memory: a container without a limit makes the service a gap, not the max of the others",
        "memory_limit_ratio", False,
        (*memory("a1", "app", "500+0x40", "1000+0x40"), *memory("a1", "sidecar", "80+0x40", None), pod_owner("a1")),
        None,
    ),
    Scenario(
        "memory: a lost usage series with the limit still present is a gap, not the max of the rest",
        "memory_limit_ratio", False,
        (*memory("a1", "app", "500+0x40", "1000+0x40"), *memory("a1", "sidecar", None, "100+0x40"), pod_owner("a1")),
        None,
    ),
    Scenario(
        "memory: counting is not enough, an expected container without a ratio is a gap even when an unexpected container has one",
        "memory_limit_ratio", False,
        (*memory("a1", "app", "500+0x40", "1000+0x40"),
         *memory("a1", "sidecar", None, "100+0x40"),
         *memory("a1", "extra", "80+0x40", "100+0x40", expected=False),
         pod_owner("a1")),
        None,
    ),
    Scenario(
        "memory: without the kube-state container record the completeness is unknown, so a gap",
        "memory_limit_ratio", False,
        (*memory("a1", "app", "500+0x40", "1000+0x40", expected=False), *memory("a1", "sidecar", "80+0x40", "100+0x40", expected=False), pod_owner("a1")),
        None,
    ),
    Scenario(
        "memory: interval_max sees the spike",
        "memory_limit_ratio", True,
        (*memory("a1", "app", SPIKE_LOW.format(low=500, high=900), "1000+0x40"), *memory("a1", "sidecar", "50+0x40", "100+0x40"), pod_owner("a1")),
        0.9,
    ),
    Scenario(
        "memory: interval_mean smooths the same spike",
        "memory_limit_ratio", False,
        (*memory("a1", "app", SPIKE_LOW.format(low=500, high=900), "1000+0x40"), *memory("a1", "sidecar", "50+0x40", "100+0x40"), pod_owner("a1")),
        0.6,
    ),
    Scenario(
        "cpu: ratio per container, sidecar at its limit",
        "cpu_limit_ratio", False,
        (
            ("node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate", f'namespace="{NS}",pod="a1",container="app"', "0.2+0x40"),
            ("node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate", f'namespace="{NS}",pod="a1",container="sidecar"', "0.1+0x40"),
            ("kube_pod_container_resource_limits", f'namespace="{NS}",pod="a1",container="app",resource="cpu"', "0.4+0x40"),
            ("kube_pod_container_resource_limits", f'namespace="{NS}",pod="a1",container="sidecar",resource="cpu"', "0.1+0x40"),
            info("a1", "app"),
            info("a1", "sidecar"),
            pod_owner("a1"),
        ),
        1,
    ),
    Scenario(
        "oom: no counter series while cAdvisor shows the pods alive gives zero",
        "oom", False,
        (("container_memory_working_set_bytes", f'namespace="{NS}",pod="a1",container="app"', "500+0x40"), pod_owner("a1")),
        0,
    ),
    Scenario(
        "oom: kube-state alive but cAdvisor absent is a gap, not a fabricated zero",
        "oom", False,
        (("kube_pod_info", f'namespace="{NS}",pod="a1"', "1+0x40"), pod_owner("a1")),
        None,
    ),
    Scenario(
        "oom: one counter sample with a live pod is a gap",
        "oom", False,
        (("container_oom_events_total", f'namespace="{NS}",pod="a1",container="app"', "_x20 1"),
         ("container_memory_working_set_bytes", f'namespace="{NS}",pod="a1",container="app"', "500+0x40"),
         pod_owner("a1")),
        None,
    ),
    Scenario("oom: no counter and nothing alive is a gap", "oom", False, (pod_owner("a1"),), None),
    Scenario(
        "oom: a counter growing by 1 per second",
        "oom", False,
        (("container_oom_events_total", f'namespace="{NS}",pod="a1",container="app"', "0+15x40"), pod_owner("a1")),
        1,
    ),
    Scenario(
        "restarts: no counter series while kube-state shows the pods alive gives zero",
        "restarts", False,
        (("kube_pod_info", f'namespace="{NS}",pod="a1"', "1+0x40"), pod_owner("a1")),
        0,
    ),
    Scenario(
        "restarts: cAdvisor alive but kube-state absent is a gap",
        "restarts", False,
        (("container_memory_working_set_bytes", f'namespace="{NS}",pod="a1",container="app"', "500+0x40"), pod_owner("a1")),
        None,
    ),
    Scenario(
        "restarts: one counter sample with a live pod is a gap",
        "restarts", False,
        (("kube_pod_container_status_restarts_total", f'namespace="{NS}",pod="a1",container="app"', "_x20 1"),
         ("kube_pod_info", f'namespace="{NS}",pod="a1"', "1+0x40"),
         pod_owner("a1")),
        None,
    ),
    Scenario(
        "restarts: a counter growing by 1 per second",
        "restarts", False,
        (("kube_pod_container_status_restarts_total", f'namespace="{NS}",pod="a1",container="app"', "0+15x40"), pod_owner("a1")),
        1,
    ),
    Scenario(
        "throttling: half of the periods are throttled",
        "cpu_throttling", False,
        (
            ("container_cpu_cfs_throttled_periods_total", f'namespace="{NS}",pod="a1",container="app"', "0+75x40"),
            ("container_cpu_cfs_periods_total", f'namespace="{NS}",pod="a1",container="app"', "0+150x40"),
            pod_owner("a1"),
        ),
        0.5,
    ),
    Scenario(
        "unavailable replicas: three desired, two available",
        "unavailable_replicas", True,
        (
            ("kube_deployment_spec_replicas", f'namespace="{NS}",deployment="{SVC}"', "3+0x40"),
            ("kube_deployment_status_replicas_available", f'namespace="{NS}",deployment="{SVC}"', "2+0x40"),
        ),
        1,
    ),
    Scenario(
        "pod imbalance: 0.2 and 0.6 cores",
        "pod_imbalance", False,
        tuple(
            item
            for pod, value in (("a1", "0.2"), ("a2", "0.6"))
            for item in (
                ("node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate", f'namespace="{NS}",pod="{pod}",container="app"', f"{value}+0x40"),
                pod_owner(pod),
            )
        ),
        1,
    ),
)


def render_scenario(scenario: Scenario) -> str:
    expression = render(SIGNALS[scenario.signal], NS, SVC, "15s", peak=scenario.peak).replace("$__interval", "60000ms")
    series = "".join(f"      - series: '{name}{{{labels}}}'\n        values: '{values}'\n" for name, labels, values in scenario.series)
    if scenario.expected is None:
        expected = "        exp_samples: []\n"
    else:
        expected = f"        exp_samples:\n          - labels: '{{namespace=\"{NS}\"}}'\n            value: {scenario.expected}\n"
    return (
        f"  - interval: 15s\n    input_series:\n{series}    promql_expr_test:\n"
        f"      - expr: |\n          round({expression}, 0.0001)\n        eval_time: 5m\n{expected}"
    )


def render_all() -> str:
    return "tests:\n" + "".join(render_scenario(s) for s in SCENARIOS)

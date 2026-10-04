"""promtool scenarios for the local demo stand compatibility layer (plan P0e).

Inputs imitate raw cAdvisor series AFTER the metric_relabel_configs of prometheus.platform.yml
(namespace, pod, container, workload added; job="cadvisor"). The recording rules of
platform-compat.rules.yml turn them into the label contract, and the P0b templates must give the same
results as in platform_promql_scenarios.py. Scrape and test interval is 5 s, evaluation at 5 m, and
$__interval is rendered as 60000ms.
"""

from dataclasses import dataclass

from tools.platform_profile_templates import SIGNALS, render

NS = "shop"
SVC = "orders-svc"
SUB = "5s"
LIVE = "1+0x80"


@dataclass(frozen=True)
class Scenario:
    name: str
    signal: str
    series: tuple  # (metric, labels, values)
    expected: object  # float, or None for "no sample" (a gap)


def raw(metric, pod, container, values, extra=""):
    labels = f'job="cadvisor",namespace="{NS}",pod="{pod}",container="{container}",workload="{SVC}"{extra}'
    return (metric, labels, values)


def container(pod, name, memory=None, memory_limit="0+0x80", cpu=None, quota=None, period="100000+0x80"):
    """One compose container as cAdvisor shows it; None drops a family. Liveness families are always present."""
    rows = [raw("container_last_seen", pod, name, LIVE), raw("container_start_time_seconds", pod, name, LIVE)]
    if memory:
        rows.append(raw("container_memory_working_set_bytes", pod, name, memory))
    rows.append(raw("container_spec_memory_limit_bytes", pod, name, memory_limit))
    if cpu:
        rows.append(raw("container_cpu_usage_seconds_total", pod, name, cpu, ',cpu="total"'))
    if quota:
        rows.append(raw("container_spec_cpu_quota", pod, name, quota))
        rows.append(raw("container_spec_cpu_period", pod, name, period))
    return rows


SCENARIOS = (
    Scenario(
        "memory: the worst container of the pod wins",
        "memory_limit_ratio",
        (*container("a1", "app", "500+0x80", "1000+0x80"), *container("a1", "sidecar", "80+0x80", "100+0x80")),
        0.8,
    ),
    Scenario(
        "memory: a container without a limit (cAdvisor reports 0) makes the service a gap",
        "memory_limit_ratio",
        (*container("a1", "app", "500+0x80", "1000+0x80"), *container("a1", "sidecar", "80+0x80", "0+0x80")),
        None,
    ),
    Scenario(
        "memory: a container without a usage series is a gap, not the max of the rest",
        "memory_limit_ratio",
        (*container("a1", "app", "500+0x80", "1000+0x80"), *container("a1", "sidecar", None, "100+0x80")),
        None,
    ),
    Scenario(
        "cpu: irate sum per container over the quota-period limit, the sidecar at its limit",
        "cpu_limit_ratio",
        (
            *container("a1", "app", cpu="0+1x80", quota="40000+0x80"),
            *container("a1", "sidecar", cpu="0+0.5x80", quota="10000+0x80"),
        ),
        1,
    ),
    Scenario(
        "cpu: quota 0 (no limit) makes the service a gap",
        "cpu_limit_ratio",
        (
            *container("a1", "app", cpu="0+1x80", quota="40000+0x80"),
            *container("a1", "sidecar", cpu="0+0.5x80", quota="0+0x80"),
        ),
        None,
    ),
    Scenario(
        "cpu: quota -1 (no limit) makes the service a gap",
        "cpu_limit_ratio",
        (
            *container("a1", "app", cpu="0+1x80", quota="40000+0x80"),
            *container("a1", "sidecar", cpu="0+0.5x80", quota="-1+0x80"),
        ),
        None,
    ),
    Scenario(
        "throttling: half of the periods are throttled",
        "cpu_throttling",
        (
            *container("a1", "app"),
            raw("container_cpu_cfs_throttled_periods_total", "a1", "app", "0+2.5x80"),
            raw("container_cpu_cfs_periods_total", "a1", "app", "0+5x80"),
        ),
        0.5,
    ),
    Scenario(
        "oom: no counter while cAdvisor shows the container alive gives zero",
        "oom",
        container("a1", "app", memory="500+0x80"),
        0,
    ),
    Scenario(
        "oom: a counter growing by 1 per second",
        "oom",
        (*container("a1", "app", memory="500+0x80"), raw("container_oom_events_total", "a1", "app", "0+5x80")),
        1,
    ),
    Scenario(
        "restarts: kube_pod_info is not recorded on the stand, so the signal stays a gap, not a fabricated zero",
        "restarts",
        container("a1", "app", memory="500+0x80"),
        None,
    ),
)


def render_scenario(scenario: Scenario) -> str:
    expression = render(SIGNALS[scenario.signal], NS, SVC, SUB).replace("$__interval", "60000ms")
    series = "".join(f"      - series: '{name}{{{labels}}}'\n        values: '{values}'\n" for name, labels, values in scenario.series)
    if scenario.expected is None:
        expected = "        exp_samples: []\n"
    else:
        expected = f"        exp_samples:\n          - labels: '{{namespace=\"{NS}\"}}'\n            value: {scenario.expected}\n"
    return (
        f"  - interval: 5s\n    name: \"{scenario.name}\"\n    input_series:\n{series}    promql_expr_test:\n"
        f"      - expr: |\n          round({expression}, 0.0001)\n        eval_time: 5m\n{expected}"
    )


def render_all(rule_file: str) -> str:
    return f"rule_files:\n  - {rule_file}\nevaluation_interval: 5s\ntests:\n" + "".join(render_scenario(s) for s in SCENARIOS)

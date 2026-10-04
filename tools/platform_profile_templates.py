"""PromQL templates for the OpenShift label contract (ADR 0018, section 3; plan P0)."""

from dataclasses import dataclass

# Tokens are replaced by render(); PromQL braces stay literal.
FRAGMENTS = {
    "owner": 'namespace_workload_pod:kube_pod_owner:relabel{namespace="@ns@",workload="@svc@"}',
    "cont": 'container!="",container!="POD"',
}

CPU_USAGE = (
    "max by (namespace, pod, container) "
    '(node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate{namespace="@ns@",@cont@} '
    "and on (namespace, pod) @owner@)"
)
CPU_LIMIT = (
    "max by (namespace, pod, container) "
    '(kube_pod_container_resource_limits{namespace="@ns@",resource="cpu"})'
)
MEM_USAGE = (
    "max by (namespace, pod, container) "
    '(container_memory_working_set_bytes{namespace="@ns@",@cont@} and on (namespace, pod) @owner@)'
)
MEM_LIMIT = (
    "max by (namespace, pod, container) "
    '(kube_pod_container_resource_limits{namespace="@ns@",resource="memory"})'
)


# Containers the platform expects for the service (kube-state-metrics); a ratio is published only when every one of them has a ratio.
EXPECTED = 'kube_pod_container_info{namespace="@ns@"} and on (namespace, pod) @owner@'


def guarded_ratio(usage: str, limit: str, over: str, expected: str = EXPECTED) -> str:
    """Interval aggregation per container first, then the worst container.

    The value is published only while the number of containers with a ratio equals the number of containers the
    platform expects (a lost usage series, a lost limit or a lost kube-state source all give a gap, never the max of the rest).
    """
    ratio = f"({usage} / on (namespace, pod, container) {limit})"
    return (
        f"max by (namespace) ({over}({ratio}[$__interval:@sub@])) "
        f"and on (namespace) (count by (namespace) ({expected}) == count by (namespace) ({ratio}))"
    )


def event_rate(counter: str, alive: str) -> str:
    """Counter rate per service; zero only while a source independent of the counter shows the service alive."""
    rate = f'sum by (namespace) (rate({counter}{{namespace="@ns@",@cont@}}[$__interval]) and on (namespace, pod) @owner@)'
    live = f"(0 * max by (namespace) ({alive} and on (namespace, pod) @owner@))"
    present = f'sum by (namespace) ({counter}{{namespace="@ns@",@cont@}} and on (namespace, pod) @owner@)'
    return f"({rate}) or ({live} unless on (namespace) {present})"


CADVISOR_ALIVE = 'container_memory_working_set_bytes{namespace="@ns@",@cont@}'
KUBE_STATE_ALIVE = 'kube_pod_info{namespace="@ns@"}'


THROTTLING = (
    "max by (namespace) ("
    'sum by (namespace, pod, container) (rate(container_cpu_cfs_throttled_periods_total{namespace="@ns@",@cont@}[$__interval]) '
    "and on (namespace, pod) @owner@) "
    '/ sum by (namespace, pod, container) (rate(container_cpu_cfs_periods_total{namespace="@ns@",@cont@}[$__interval]) '
    "and on (namespace, pod) @owner@))"
)

UNAVAILABLE = (
    "max by (namespace) (@over@(("
    'kube_deployment_spec_replicas{namespace="@ns@",deployment="@svc@"} '
    "- on (namespace, deployment) "
    'kube_deployment_status_replicas_available{namespace="@ns@",deployment="@svc@"}'
    ")[$__interval:@sub@]))"
)

POD_CPU = (
    "avg_over_time((sum by (namespace, pod) "
    '(node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate{namespace="@ns@",@cont@} '
    "and on (namespace, pod) @owner@))[$__interval:@sub@])"
)
IMBALANCE = f"(max by (namespace) ({POD_CPU}) - min by (namespace) ({POD_CPU})) / avg by (namespace) ({POD_CPU})"


@dataclass(frozen=True)
class Signal:
    metric: str
    unit: str
    aggregation: str
    peak: bool
    uses_rate: bool
    expression: str
    peak_only: bool = False  # the signal is a maximum by definition; generated only with interval_max


SIGNALS = {
    "cpu_limit_ratio": Signal(
        "openshift_container_cpu_limit_ratio", "ratio", "interval_mean", False, False,
        guarded_ratio(CPU_USAGE, CPU_LIMIT, "avg_over_time"),
    ),
    "memory_limit_ratio": Signal(
        "openshift_container_memory_limit_ratio", "ratio", "interval_mean", True, False,
        guarded_ratio(MEM_USAGE, MEM_LIMIT, "@over@"),
    ),
    "oom": Signal("openshift_oom", "events/s", "interval_rate", False, True, event_rate("container_oom_events_total", CADVISOR_ALIVE)),
    "restarts": Signal(
        "openshift_restarts", "events/s", "interval_rate", False, True,
        event_rate("kube_pod_container_status_restarts_total", KUBE_STATE_ALIVE),
    ),
    "cpu_throttling": Signal("openshift_cpu_throttling", "ratio", "interval_mean", False, True, THROTTLING),
    "unavailable_replicas": Signal("openshift_unavailable_replicas", "count", "interval_mean", True, False, UNAVAILABLE),
    "pod_imbalance": Signal("openshift_pod_imbalance", "ratio", "interval_mean", False, False, IMBALANCE),
}


def render(signal: Signal, ns: str, svc: str, sub: str, peak: bool = False) -> str:
    if signal.peak_only and not peak:
        raise ValueError(f"{signal.metric} needs interval_max aggregation")
    if peak and not signal.peak:
        raise ValueError(f"{signal.metric} has no peak variant")
    expression = signal.expression.replace("@owner@", FRAGMENTS["owner"]).replace("@cont@", FRAGMENTS["cont"])
    return (expression.replace("@over@", "max_over_time" if peak else "avg_over_time")
            .replace("@sub@", sub).replace("@ns@", ns).replace("@svc@", svc))

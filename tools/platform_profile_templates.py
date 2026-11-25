"""PromQL templates for the OpenShift label contract (ADR 0018, section 3; plan P0)."""

import re
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

    Publish only with a non-empty expected inventory and a ratio for every expected container.
    A missing usage, limit, or inventory gives a gap instead of the max of the remaining containers.
    """
    ratio = f"({usage} / on (namespace, pod, container) {limit})"
    return (
        f"max by (namespace) ({over}({ratio}[$__interval:@sub@])) "
        f"and on (namespace) (count by (namespace) ({expected}) unless on (namespace) "
        f"(count by (namespace) ({expected} unless on (namespace, pod, container) {ratio})))"
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
    sidecar: bool = False  # needs the sidecar container regular expression


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


# Sidecar attribution (plan P1c): same folds over the containers that match the sidecar regular expression only,
# and completeness is checked against the sidecars the platform expects, not against all containers.
SIDECAR_EXPECTED = 'kube_pod_container_info{namespace="@ns@",container=~"@sidecars@"} and on (namespace, pod) @owner@'
SIDECAR_MEM_USAGE = MEM_USAGE.replace("@cont@", 'container=~"@sidecars@"')
SIDECAR_THROTTLING = THROTTLING.replace("@cont@", 'container=~"@sidecars@"')

SIGNALS.update(
    {
        "sidecar_memory_limit_ratio": Signal(
            "openshift_sidecar_memory_limit_ratio", "ratio", "interval_mean", True, False,
            guarded_ratio(SIDECAR_MEM_USAGE, MEM_LIMIT, "@over@", SIDECAR_EXPECTED), sidecar=True,
        ),
        "sidecar_cpu_throttling": Signal(
            "openshift_sidecar_cpu_throttling", "ratio", "interval_mean", False, True, SIDECAR_THROTTLING, sidecar=True
        ),
    }
)


def per_pod_over_time(selector: str) -> str:
    """Worst pod of the service; interval aggregation per pod first (ADR 0018, section 2)."""
    return (
        f"max by (namespace) (@over@((sum by (namespace, pod) ({selector} and on (namespace, pod) @owner@))"
        "[$__interval:@sub@]))"
    )


HEAP = 'jvm_memory_used_bytes{namespace="@ns@",area="heap"}'
OLD_GEN = 'jvm_memory_used_bytes{namespace="@ns@",area="heap",id=~".*Old Gen|Tenured Gen"}'
NON_HEAP = 'jvm_memory_used_bytes{namespace="@ns@",area="nonheap"}'
THREADS = 'jvm_threads_live_threads{namespace="@ns@"}'
PROCESS_CPU = 'process_cpu_usage{namespace="@ns@"}'
GC_PAUSE_MAX = (
    "max by (namespace) (max_over_time((max by (namespace, pod) "
    '(jvm_gc_pause_seconds_max{namespace="@ns@"} and on (namespace, pod) @owner@))[$__interval:@sub@]))'
)
GC_TIME = (
    "max by (namespace) (sum by (namespace, pod) "
    '(rate(jvm_gc_pause_seconds_sum{namespace="@ns@"}[$__interval]) and on (namespace, pod) @owner@))'
)
POOL_ACTIVE = (
    "max by (namespace, pod, pool) "
    '(hikaricp_connections_active{namespace="@ns@"} and on (namespace, pod) @owner@)'
)
POOL_MAX = (
    "max by (namespace, pod, pool) "
    '(hikaricp_connections_max{namespace="@ns@"} and on (namespace, pod) @owner@)'
)
POOL_RATIO = f"({POOL_ACTIVE} / on (namespace, pod, pool) {POOL_MAX})"
POOL_SATURATION = (
    f"max by (namespace) (@over@({POOL_RATIO}[$__interval:@sub@])) "
    f"unless on (namespace) (count by (namespace) "
    f"({POOL_ACTIVE} unless on (namespace, pod, pool) {POOL_RATIO}))"
)

SIGNALS.update(
    {
        "jvm_heap_used": Signal("jvm_heap_used", "bytes", "interval_mean", True, False, per_pod_over_time(HEAP)),
        "jvm_old_gen_used": Signal("jvm_old_gen_used", "bytes", "interval_mean", True, False, per_pod_over_time(OLD_GEN)),
        "jvm_non_heap_used": Signal("jvm_non_heap_used", "bytes", "interval_mean", True, False, per_pod_over_time(NON_HEAP)),
        "jvm_thread_count": Signal("jvm_thread_count", "count", "interval_mean", False, False,
                                   per_pod_over_time(THREADS).replace("@over@", "avg_over_time")),
        "jvm_process_cpu": Signal("jvm_process_cpu", "ratio", "interval_mean", False, False,
                                  per_pod_over_time(PROCESS_CPU).replace("@over@", "avg_over_time")),
        "jvm_gc_pause": Signal("jvm_gc_pause", "s", "interval_max", True, False, GC_PAUSE_MAX, peak_only=True),
        "jvm_gc_time": Signal("jvm_gc_time", "ratio", "interval_rate", False, True, GC_TIME),
        "jvm_pool_saturation": Signal("jvm_pool_saturation", "ratio", "interval_mean", True, False, POOL_SATURATION),
    }
)


SIDECARS = re.compile(r"[A-Za-z0-9._|()*+?-]{1,256}")


def render(signal: Signal, ns: str, svc: str, sub: str, peak: bool = False, sidecars: str | None = None) -> str:
    if signal.peak_only and not peak:
        raise ValueError(f"{signal.metric} needs interval_max aggregation")
    if peak and not signal.peak:
        raise ValueError(f"{signal.metric} has no peak variant")
    if signal.sidecar and not sidecars:
        raise ValueError(f"{signal.metric} needs the sidecar container regular expression")
    expression = signal.expression.replace("@owner@", FRAGMENTS["owner"]).replace("@cont@", FRAGMENTS["cont"])
    return (expression.replace("@over@", "max_over_time" if peak else "avg_over_time")
            .replace("@sub@", sub).replace("@sidecars@", sidecars or "").replace("@ns@", ns).replace("@svc@", svc))


def _per_job_over_time(selector: str) -> str:
    """Worst instance for a standalone Prometheus JVM job; interval aggregation per instance first."""
    return (
        f"max by (job) (@over@((sum by (job, instance) ({selector}))"
        "[$__interval:@sub@]))"
    )


STD_HEAP = 'jvm_memory_used_bytes{job="@job@",area="heap"}'
STD_OLD_GEN = 'jvm_memory_used_bytes{job="@job@",area="heap",id=~".*Old Gen|Tenured Gen"}'
STD_NON_HEAP = 'jvm_memory_used_bytes{job="@job@",area="nonheap"}'
STD_THREADS = 'jvm_threads_live_threads{job="@job@"}'
STD_PROCESS_CPU = 'process_cpu_usage{job="@job@"}'
STD_GC_PAUSE_MAX = (
    "max by (job) (max_over_time((max by (job, instance) "
    '(jvm_gc_pause_seconds_max{job="@job@"}))[$__interval:@sub@]))'
)
STD_GC_TIME = (
    "max by (job) (sum by (job, instance) "
    '(rate(jvm_gc_pause_seconds_sum{job="@job@"}[$__interval])))'
)
STD_POOL_ACTIVE = 'max by (job, instance, pool) (hikaricp_connections_active{job="@job@"})'
STD_POOL_MAX = 'max by (job, instance, pool) (hikaricp_connections_max{job="@job@"})'
STD_POOL_RATIO = f"({STD_POOL_ACTIVE} / on (job, instance, pool) {STD_POOL_MAX})"
STD_POOL_SATURATION = (
    f"max by (job) (@over@({STD_POOL_RATIO}[$__interval:@sub@])) "
    f"unless on (job) (count by (job) "
    f"({STD_POOL_ACTIVE} unless on (job, instance, pool) {STD_POOL_RATIO}))"
)

STANDALONE_JVM_SIGNALS = {
    "jvm_heap_used": Signal("jvm_heap_used", "bytes", "interval_mean", True, False, _per_job_over_time(STD_HEAP)),
    "jvm_old_gen_used": Signal("jvm_old_gen_used", "bytes", "interval_mean", True, False, _per_job_over_time(STD_OLD_GEN)),
    "jvm_non_heap_used": Signal("jvm_non_heap_used", "bytes", "interval_mean", True, False, _per_job_over_time(STD_NON_HEAP)),
    "jvm_thread_count": Signal(
        "jvm_thread_count",
        "count",
        "interval_mean",
        False,
        False,
        _per_job_over_time(STD_THREADS).replace("@over@", "avg_over_time"),
    ),
    "jvm_process_cpu": Signal(
        "jvm_process_cpu",
        "ratio",
        "interval_mean",
        False,
        False,
        _per_job_over_time(STD_PROCESS_CPU).replace("@over@", "avg_over_time"),
    ),
    "jvm_gc_pause": Signal("jvm_gc_pause", "s", "interval_max", True, False, STD_GC_PAUSE_MAX, peak_only=True),
    "jvm_gc_time": Signal("jvm_gc_time", "ratio", "interval_rate", False, True, STD_GC_TIME),
    "jvm_pool_saturation": Signal("jvm_pool_saturation", "ratio", "interval_mean", True, False, STD_POOL_SATURATION),
}


PG_UP_ALIVE = 'max by (instance) (pg_up{instance="@instance@"} == 1)'
PG_ACTIVE_CONN = 'sum by (instance) (pg_stat_activity_count{instance="@instance@",datname="@db@",state="active"})'
PG_MAX_CONN = 'max by (instance) (pg_settings_max_connections{instance="@instance@"} > 0)'
PG_IDLE_TX = 'sum by (instance) (pg_stat_activity_count{instance="@instance@",datname="@db@",state="idle in transaction"})'
PG_BLKS_HIT = 'sum by (instance) (rate(pg_stat_database_blks_hit{instance="@instance@",datname="@db@"}[$__interval]))'
PG_BLKS_READ = 'sum by (instance) (rate(pg_stat_database_blks_read{instance="@instance@",datname="@db@"}[$__interval]))'
PG_DEADLOCKS_RATE = 'sum by (instance) (rate(pg_stat_database_deadlocks{instance="@instance@",datname="@db@"}[$__interval]))'
PG_DEADLOCKS_PRESENT = 'sum by (instance) (pg_stat_database_deadlocks{instance="@instance@",datname="@db@"})'

POSTGRES_EXPORTER_SIGNALS = {
    "pg_connections_utilization": Signal(
        "pg_connections_utilization",
        "ratio",
        "interval_mean",
        True,
        False,
        f"max by (instance) (@over@(((({PG_ACTIVE_CONN} or (0 * {PG_UP_ALIVE})) / on (instance) {PG_MAX_CONN}))[$__interval:@sub@]))",
    ),
    "pg_idle_in_transaction": Signal(
        "pg_idle_in_transaction",
        "count",
        "interval_mean",
        True,
        False,
        f"max by (instance) (@over@((({PG_IDLE_TX} or (0 * {PG_UP_ALIVE})))[$__interval:@sub@]))",
    ),
    "pg_cache_hit_ratio": Signal(
        "pg_cache_hit_ratio",
        "ratio",
        "interval_mean",
        False,
        True,
        f"({PG_BLKS_HIT}) / on (instance) (({PG_BLKS_HIT} + {PG_BLKS_READ}) > 0)",
    ),
    "pg_xact_commit_rate": Signal(
        "pg_xact_commit_rate",
        "events/s",
        "interval_rate",
        False,
        True,
        'sum by (instance) (rate(pg_stat_database_xact_commit{instance="@instance@",datname="@db@"}[$__interval]))',
    ),
    "pg_xact_rollback_rate": Signal(
        "pg_xact_rollback_rate",
        "events/s",
        "interval_rate",
        False,
        True,
        'sum by (instance) (rate(pg_stat_database_xact_rollback{instance="@instance@",datname="@db@"}[$__interval]))',
    ),
    "pg_deadlocks_rate": Signal(
        "pg_deadlocks_rate",
        "events/s",
        "interval_rate",
        False,
        True,
        f"({PG_DEADLOCKS_RATE}) or ((0 * {PG_UP_ALIVE}) unless on (instance) {PG_DEADLOCKS_PRESENT})",
    ),
    "pg_temp_bytes_rate": Signal(
        "pg_temp_bytes_rate",
        "bytes/s",
        "interval_rate",
        False,
        True,
        'sum by (instance) (rate(pg_stat_database_temp_bytes{instance="@instance@",datname="@db@"}[$__interval]))',
    ),
}

PRESET_SIGNALS = {
    "openshift": SIGNALS,
    "jvm-standalone": STANDALONE_JVM_SIGNALS,
    "postgres-exporter": POSTGRES_EXPORTER_SIGNALS,
}


def render_preset(
    signal: Signal,
    preset: str,
    target: dict[str, str],
    sub: str,
    peak: bool = False,
    sidecars: str | None = None,
) -> str:
    if preset == "openshift":
        return render(signal, target["namespace"], target["service"], sub, peak=peak, sidecars=sidecars)
    if signal.peak_only and not peak:
        raise ValueError(f"{signal.metric} needs interval_max aggregation")
    if peak and not signal.peak:
        raise ValueError(f"{signal.metric} has no peak variant")
    if signal.sidecar and not sidecars:
        raise ValueError(f"{signal.metric} needs the sidecar container regular expression")
    expression = signal.expression.replace("@over@", "max_over_time" if peak else "avg_over_time").replace("@sub@", sub)
    if preset == "jvm-standalone":
        return expression.replace("@job@", target["job"])
    if preset == "postgres-exporter":
        return expression.replace("@instance@", target["instance"]).replace("@db@", target["database"])
    raise ValueError(f"unknown preset: {preset}")

"""promtool scenarios for the platform PromQL templates (plan P0b/P0c/P1c).

Each scenario feeds synthetic series (the label contract of docs/user/platform-metric-packs.md) to one
rendered template and pins the exact result. Series use a 15 s test interval, evaluation at 5 m, and
$__interval is rendered as 60000ms. Expressions are wrapped in round(..., 0.0001): promtool compares floats exactly.
"""

from dataclasses import dataclass

from tools.platform_profile_templates import (
    POSTGRES_EXPORTER_SIGNALS,
    PRESET_SIGNALS,
    SIGNALS,
    STANDALONE_JVM_SIGNALS,
    render,
    render_preset,
)

NS = "shop"
SVC = "orders-svc"
JOB = "orders-svc"
PG_INSTANCE = "pg-main:9187"
PG_DB = "loadtest"
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
    Scenario(
        "jvm heap: peak of the worst pod",
        "jvm_heap_used", True,
        (
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a1",area="heap",id="G1 Old Gen"', SPIKE_LOW.format(low=100, high=400)),
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a2",area="heap",id="G1 Old Gen"', "200+0x40"),
            pod_owner("a1"),
            pod_owner("a2"),
        ),
        400,
    ),
    Scenario(
        "jvm heap: the mean variant picks the steadier but higher pod",
        "jvm_heap_used", False,
        (
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a1",area="heap",id="G1 Old Gen"', SPIKE_LOW.format(low=100, high=400)),
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a2",area="heap",id="G1 Old Gen"', "200+0x40"),
            pod_owner("a1"),
            pod_owner("a2"),
        ),
        200,
    ),
    Scenario(
        "jvm gc pause: maximum of the pause gauge",
        "jvm_gc_pause", True,
        (
            ("jvm_gc_pause_seconds_max", f'namespace="{NS}",pod="a1",action="end of minor GC",cause="G1 Evacuation Pause"', SPIKE_LOW.format(low=0.05, high=0.4)),
            pod_owner("a1"),
        ),
        0.4,
    ),
    Scenario(
        "jvm pool: worst pod saturation",
        "jvm_pool_saturation", True,
        tuple(
            item
            for pod, active in (("a1", "8"), ("a2", "5"))
            for item in (
                ("hikaricp_connections_active", f'namespace="{NS}",pod="{pod}",pool="main"', f"{active}+0x40"),
                ("hikaricp_connections_max", f'namespace="{NS}",pod="{pod}",pool="main"', "10+0x40"),
                pod_owner(pod),
            )
        ),
        0.8,
    ),
    Scenario(
        "jvm pool: the worst pool of a pod wins over the pod total",
        "jvm_pool_saturation", True,
        (
            ("hikaricp_connections_active", f'namespace="{NS}",pod="a1",pool="main"', "10+0x40"),
            ("hikaricp_connections_max", f'namespace="{NS}",pod="a1",pool="main"', "10+0x40"),
            ("hikaricp_connections_active", f'namespace="{NS}",pod="a1",pool="idle"', "0+0x40"),
            ("hikaricp_connections_max", f'namespace="{NS}",pod="a1",pool="idle"', "90+0x40"),
            pod_owner("a1"),
        ),
        1,
    ),
    Scenario(
        "jvm pool: a pool without a max series is a gap",
        "jvm_pool_saturation", True,
        (
            ("hikaricp_connections_active", f'namespace="{NS}",pod="a1",pool="main"', "8+0x40"),
            ("hikaricp_connections_max", f'namespace="{NS}",pod="a1",pool="main"', "10+0x40"),
            ("hikaricp_connections_active", f'namespace="{NS}",pod="a1",pool="idle"', "0+0x40"),
            pod_owner("a1"),
        ),
        None,
    ),
    Scenario(
        "jvm threads: the busiest pod has 70 live threads",
        "jvm_thread_count", False,
        (
            ("jvm_threads_live_threads", f'namespace="{NS}",pod="a1"', "50+0x40"),
            ("jvm_threads_live_threads", f'namespace="{NS}",pod="a2"', "70+0x40"),
            pod_owner("a1"),
            pod_owner("a2"),
        ),
        70,
    ),
    Scenario(
        "jvm gc time: pause seconds increase by 0.15 every 15 seconds",
        "jvm_gc_time", False,
        (
            ("jvm_gc_pause_seconds_sum", f'namespace="{NS}",pod="a1",action="end of minor GC"', "0+0.15x40"),
            pod_owner("a1"),
        ),
        0.01,
    ),
    Scenario(
        "jvm old gen: only the old generation heap pool counts",
        "jvm_old_gen_used", False,
        (
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a1",area="heap",id="G1 Old Gen"', "300+0x40"),
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a1",area="heap",id="G1 Eden Space"', "100+0x40"),
            ("jvm_memory_used_bytes", f'namespace="{NS}",pod="a1",area="nonheap",id="Metaspace"', "50+0x40"),
            pod_owner("a1"),
        ),
        300,
    ),
    Scenario(
        "sidecar memory: only the sidecars count, the application container is ignored",
        "sidecar_memory_limit_ratio", False,
        (*memory("a1", "app", "900+0x40", "1000+0x40"), *memory("a1", "istio-proxy", "77+0x40", "100+0x40"), pod_owner("a1")),
        0.77,
    ),
    Scenario(
        "sidecar memory: a sidecar without a limit is a gap even though the application has one",
        "sidecar_memory_limit_ratio", False,
        (*memory("a1", "app", "900+0x40", "1000+0x40"), *memory("a1", "istio-proxy", "77+0x40", None), pod_owner("a1")),
        None,
    ),
    Scenario(
        "sidecar throttling: only the sidecar periods count",
        "sidecar_cpu_throttling", False,
        (
            ("container_cpu_cfs_throttled_periods_total", f'namespace="{NS}",pod="a1",container="app"', "0+135x40"),
            ("container_cpu_cfs_periods_total", f'namespace="{NS}",pod="a1",container="app"', "0+150x40"),
            ("container_cpu_cfs_throttled_periods_total", f'namespace="{NS}",pod="a1",container="istio-proxy"', "0+45x40"),
            ("container_cpu_cfs_periods_total", f'namespace="{NS}",pod="a1",container="istio-proxy"', "0+150x40"),
            pod_owner("a1"),
        ),
        0.3,
    ),
)


def render_scenario(scenario: Scenario) -> str:
    expression = render(SIGNALS[scenario.signal], NS, SVC, "15s", peak=scenario.peak, sidecars="istio-proxy|oauth-proxy").replace("$__interval", "60000ms")
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
    return "tests:\n" + "".join(render_scenario(s) for s in SCENARIOS) + "".join(
        render_standalone_scenario(s) for s in STANDALONE_SCENARIOS
    )


@dataclass(frozen=True)
class StandaloneScenario:
    name: str
    preset: str
    signal: str
    peak: bool
    series: tuple
    expected: object


STANDALONE_SCENARIOS = (
    StandaloneScenario(
        "standalone jvm heap: peak of the worst instance",
        "jvm-standalone",
        "jvm_heap_used",
        True,
        (
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="heap",id="G1 Old Gen"', SPIKE_LOW.format(low=100, high=400)),
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-2",area="heap",id="G1 Old Gen"', "200+0x40"),
        ),
        400,
    ),
    StandaloneScenario(
        "standalone jvm heap: mean picks steadier higher instance",
        "jvm-standalone",
        "jvm_heap_used",
        False,
        (
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="heap",id="G1 Old Gen"', SPIKE_LOW.format(low=100, high=400)),
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-2",area="heap",id="G1 Old Gen"', "200+0x40"),
        ),
        200,
    ),
    StandaloneScenario(
        "standalone jvm old gen: only old generation pool counts",
        "jvm-standalone",
        "jvm_old_gen_used",
        False,
        (
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="heap",id="G1 Old Gen"', "300+0x40"),
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="heap",id="G1 Eden Space"', "100+0x40"),
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="nonheap",id="Metaspace"', "50+0x40"),
        ),
        300,
    ),
    StandaloneScenario(
        "standalone jvm non heap: only nonheap area counts",
        "jvm-standalone",
        "jvm_non_heap_used",
        False,
        (
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="heap",id="G1 Old Gen"', "300+0x40"),
            ("jvm_memory_used_bytes", f'job="{JOB}",instance="app-1",area="nonheap",id="Metaspace"', "50+0x40"),
        ),
        50,
    ),
    StandaloneScenario(
        "standalone jvm threads: busiest instance wins",
        "jvm-standalone",
        "jvm_thread_count",
        False,
        (
            ("jvm_threads_live_threads", f'job="{JOB}",instance="app-1"', "50+0x40"),
            ("jvm_threads_live_threads", f'job="{JOB}",instance="app-2"', "70+0x40"),
        ),
        70,
    ),
    StandaloneScenario(
        "standalone jvm process cpu: busiest instance wins",
        "jvm-standalone",
        "jvm_process_cpu",
        False,
        (
            ("process_cpu_usage", f'job="{JOB}",instance="app-1"', "0.25+0x40"),
            ("process_cpu_usage", f'job="{JOB}",instance="app-2"', "0.65+0x40"),
        ),
        0.65,
    ),
    StandaloneScenario(
        "standalone jvm gc pause: max of pause gauge",
        "jvm-standalone",
        "jvm_gc_pause",
        True,
        (
            ("jvm_gc_pause_seconds_max", f'job="{JOB}",instance="app-1",action="end of minor GC"', SPIKE_LOW.format(low=0.05, high=0.4)),
        ),
        0.4,
    ),
    StandaloneScenario(
        "standalone jvm gc time: rate of pause seconds sum",
        "jvm-standalone",
        "jvm_gc_time",
        False,
        (
            ("jvm_gc_pause_seconds_sum", f'job="{JOB}",instance="app-1",action="end of minor GC"', "0+0.15x40"),
        ),
        0.01,
    ),
    StandaloneScenario(
        "standalone jvm pool: worst pool saturation wins",
        "jvm-standalone",
        "jvm_pool_saturation",
        True,
        (
            ("hikaricp_connections_active", f'job="{JOB}",instance="app-1",pool="main"', "10+0x40"),
            ("hikaricp_connections_max", f'job="{JOB}",instance="app-1",pool="main"', "10+0x40"),
            ("hikaricp_connections_active", f'job="{JOB}",instance="app-1",pool="idle"', "0+0x40"),
            ("hikaricp_connections_max", f'job="{JOB}",instance="app-1",pool="idle"', "90+0x40"),
        ),
        1,
    ),
    StandaloneScenario(
        "standalone jvm pool: pool without max is a gap",
        "jvm-standalone",
        "jvm_pool_saturation",
        True,
        (
            ("hikaricp_connections_active", f'job="{JOB}",instance="app-1",pool="main"', "8+0x40"),
            ("hikaricp_connections_max", f'job="{JOB}",instance="app-1",pool="main"', "10+0x40"),
            ("hikaricp_connections_active", f'job="{JOB}",instance="app-1",pool="idle"', "0+0x40"),
        ),
        None,
    ),
    StandaloneScenario(
        "postgres connections utilization: active over max_connections",
        "postgres-exporter",
        "pg_connections_utilization",
        False,
        (
            ("pg_stat_activity_count", f'instance="{PG_INSTANCE}",datname="{PG_DB}",state="active"', "40+0x40"),
            ("pg_settings_max_connections", f'instance="{PG_INSTANCE}"', "100+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        0.4,
    ),
    StandaloneScenario(
        "postgres connections utilization: peak captures spike",
        "postgres-exporter",
        "pg_connections_utilization",
        True,
        (
            ("pg_stat_activity_count", f'instance="{PG_INSTANCE}",datname="{PG_DB}",state="active"', SPIKE_LOW.format(low=20, high=80)),
            ("pg_settings_max_connections", f'instance="{PG_INSTANCE}"', "100+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        0.8,
    ),
    StandaloneScenario(
        "postgres connections utilization: absent active series with pg_up==1 gives zero",
        "postgres-exporter",
        "pg_connections_utilization",
        False,
        (
            ("pg_settings_max_connections", f'instance="{PG_INSTANCE}"', "100+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        0,
    ),
    StandaloneScenario(
        "postgres connections utilization: pg_up==0 gives gap",
        "postgres-exporter",
        "pg_connections_utilization",
        False,
        (
            ("pg_settings_max_connections", f'instance="{PG_INSTANCE}"', "100+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "0+0x40"),
        ),
        None,
    ),
    StandaloneScenario(
        "postgres connections utilization: missing max_connections gives gap",
        "postgres-exporter",
        "pg_connections_utilization",
        False,
        (
            ("pg_stat_activity_count", f'instance="{PG_INSTANCE}",datname="{PG_DB}",state="active"', "40+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        None,
    ),
    StandaloneScenario(
        "postgres idle in transaction: count and zero fallback",
        "postgres-exporter",
        "pg_idle_in_transaction",
        False,
        (
            ("pg_stat_activity_count", f'instance="{PG_INSTANCE}",datname="{PG_DB}",state="idle in transaction"', "3+0x40"),
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        3,
    ),
    StandaloneScenario(
        "postgres idle in transaction: absent with pg_up==1 gives zero",
        "postgres-exporter",
        "pg_idle_in_transaction",
        False,
        (
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        0,
    ),
    StandaloneScenario(
        "postgres cache hit ratio: blks_hit / (blks_hit + blks_read)",
        "postgres-exporter",
        "pg_cache_hit_ratio",
        False,
        (
            ("pg_stat_database_blks_hit", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+135x40"),
            ("pg_stat_database_blks_read", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+15x40"),
        ),
        0.9,
    ),
    StandaloneScenario(
        "postgres xact commit rate: commits per second",
        "postgres-exporter",
        "pg_xact_commit_rate",
        False,
        (
            ("pg_stat_database_xact_commit", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+300x40"),
        ),
        20,
    ),
    StandaloneScenario(
        "postgres xact rollback rate: rollbacks per second",
        "postgres-exporter",
        "pg_xact_rollback_rate",
        False,
        (
            ("pg_stat_database_xact_rollback", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+30x40"),
        ),
        2,
    ),
    StandaloneScenario(
        "postgres deadlocks rate: absent counter with pg_up==1 gives zero",
        "postgres-exporter",
        "pg_deadlocks_rate",
        False,
        (
            ("pg_up", f'instance="{PG_INSTANCE}"', "1+0x40"),
        ),
        0,
    ),
    StandaloneScenario(
        "postgres deadlocks rate: pg_up==0 gives gap",
        "postgres-exporter",
        "pg_deadlocks_rate",
        False,
        (
            ("pg_up", f'instance="{PG_INSTANCE}"', "0+0x40"),
        ),
        None,
    ),
    StandaloneScenario(
        "postgres deadlocks rate: growing counter",
        "postgres-exporter",
        "pg_deadlocks_rate",
        False,
        (
            ("pg_stat_database_deadlocks", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+15x40"),
        ),
        1,
    ),
    StandaloneScenario(
        "postgres temp bytes rate: bytes per second",
        "postgres-exporter",
        "pg_temp_bytes_rate",
        False,
        (
            ("pg_stat_database_temp_bytes", f'instance="{PG_INSTANCE}",datname="{PG_DB}"', "0+15000x40"),
        ),
        1000,
    ),
)


def render_standalone_scenario(scenario: StandaloneScenario) -> str:
    signal = PRESET_SIGNALS[scenario.preset][scenario.signal]
    if scenario.preset == "jvm-standalone":
        target = {"job": JOB}
        expected_labels = f'job="{JOB}"'
    else:
        target = {"instance": PG_INSTANCE, "database": PG_DB}
        expected_labels = f'instance="{PG_INSTANCE}"'
    expression = render_preset(signal, scenario.preset, target, "15s", peak=scenario.peak).replace("$__interval", "60000ms")
    series = "".join(f"      - series: '{name}{{{labels}}}'\n        values: '{values}'\n" for name, labels, values in scenario.series)
    if scenario.expected is None:
        expected = "        exp_samples: []\n"
    else:
        expected = f"        exp_samples:\n          - labels: '{{{expected_labels}}}'\n            value: {scenario.expected}\n"
    return (
        f"  - interval: 15s\n    input_series:\n{series}    promql_expr_test:\n"
        f"      - expr: |\n          round({expression}, 0.0001)\n        eval_time: 5m\n{expected}"
    )

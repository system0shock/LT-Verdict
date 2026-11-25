import os
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.platform_profile_templates import POSTGRES_EXPORTER_SIGNALS, SIGNALS, STANDALONE_JVM_SIGNALS, render, render_preset
from tools.platform_promql_scenarios import SCENARIOS, STANDALONE_SCENARIOS, render_all


def promtool_command():
    """LTV_PROMTOOL is a command prefix; with LTV_PROMTOOL_STDIN=1 the YAML is piped to it instead of passed as a file."""
    custom = os.environ.get("LTV_PROMTOOL")
    if custom:
        return shlex.split(custom)
    found = shutil.which("promtool")
    return [found] if found else None


class PlatformPromqlTest(unittest.TestCase):
    def test_ratio_guard_checks_expected_container_identity(self):
        expression = render(SIGNALS["memory_limit_ratio"], "shop", "orders-svc", "15s")
        self.assertIn("unless on (namespace, pod, container)", expression)
        self.assertIn("unless on (namespace) (count by (namespace)", expression)

    def test_jvm_pool_ratio_keeps_pool_identity_and_gaps(self):
        expression = render(SIGNALS["jvm_pool_saturation"], "shop", "orders-svc", "15s", peak=True)
        self.assertIn("max by (namespace, pod, pool) (hikaricp_connections_active", expression)
        self.assertIn("/ on (namespace, pod, pool) max by (namespace, pod, pool) (hikaricp_connections_max", expression)
        self.assertIn("unless on (namespace) (count by (namespace)", expression)
        self.assertIn("unless on (namespace, pod, pool)", expression)

    def test_every_template_has_a_scenario(self):
        covered = {scenario.signal for scenario in SCENARIOS}
        missing = {name for name in SIGNALS if name not in covered}
        # These use the same per_pod_over_time template as jvm_heap_used.
        self.assertEqual({"jvm_non_heap_used", "jvm_process_cpu"}, missing)

    def test_event_zero_fallback_requires_no_counter_sample(self):
        for name in ("oom", "restarts"):
            with self.subTest(signal=name):
                expression = render(SIGNALS[name], "shop", "orders-svc", "15s")
                self.assertIn("unless on (namespace)", expression)

    def test_every_standalone_template_has_a_scenario(self):
        covered_jvm = {s.signal for s in STANDALONE_SCENARIOS if s.preset == "jvm-standalone"}
        self.assertEqual(set(STANDALONE_JVM_SIGNALS), covered_jvm)
        covered_pg = {s.signal for s in STANDALONE_SCENARIOS if s.preset == "postgres-exporter"}
        self.assertEqual(set(POSTGRES_EXPORTER_SIGNALS), covered_pg)

    def test_standalone_jvm_pool_guard_and_postgres_zero_fallback(self):
        pool = render_preset(
            STANDALONE_JVM_SIGNALS["jvm_pool_saturation"],
            "jvm-standalone",
            {"job": "orders-svc"},
            "15s",
            peak=True,
        )
        self.assertIn("unless on (job) (count by (job)", pool)
        self.assertIn("unless on (job, instance, pool)", pool)

        deadlocks = render_preset(
            POSTGRES_EXPORTER_SIGNALS["pg_deadlocks_rate"],
            "postgres-exporter",
            {"instance": "pg-main:9187", "database": "loadtest"},
            "15s",
        )
        self.assertIn("unless on (instance)", deadlocks)
        self.assertIn('pg_up{instance="pg-main:9187"} == 1', deadlocks)

    @unittest.skipUnless(promtool_command(), "promtool is not installed; set LTV_PROMTOOL to run the scenarios")
    def test_promtool_accepts_every_scenario(self):
        command = promtool_command()
        text = render_all()
        if os.environ.get("LTV_PROMTOOL_STDIN") == "1":
            result = subprocess.run(command, input=text, capture_output=True, text=True, timeout=300)
        else:
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "platform-promql.yml"
                path.write_text(text, encoding="utf-8")
                result = subprocess.run([*command, "test", "rules", str(path)], capture_output=True, text=True, timeout=300)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

import os
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.platform_profile_templates import SIGNALS, render
from tools.platform_promql_scenarios import SCENARIOS, render_all


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

"""Checks for the demo stand compatibility layer (plan P0e): structure everywhere, promtool when it is available."""

import json
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.platform_profile_templates import SIGNALS, render
from tools.platform_profiles import build_connections
from tools.platform_stand_scenarios import SCENARIOS, render_all

PLATFORM = Path(__file__).resolve().parent / "demo-stand" / "platform"
RULES = PLATFORM / "platform-compat.rules.yml"
CONFIG = PLATFORM / "prometheus.platform.yml"
PROFILE_CONFIG = PLATFORM / "profile-config.stand.json"
STAND_SIGNALS = ["cpu_limit_ratio", "memory_limit_ratio", "cpu_throttling"]
OPTIONAL_STAND_SIGNALS = ["oom"]  # only when cAdvisor exposes container_oom_events_total; otherwise a live container reads as zero
CADVISOR_FAMILIES = {
    "container_memory_working_set_bytes",
    "container_oom_events_total",
    "container_cpu_cfs_throttled_periods_total",
    "container_cpu_cfs_periods_total",
}
FAMILY = re.compile(r"([a-zA-Z_:][a-zA-Z0-9_:]*)\{")


def promtool():
    """promtool binary, or LTV_PROMTOOL_DOCKER_IMAGE (a pinned prom/prometheus image run with the temp dir mounted)."""
    image = os.environ.get("LTV_PROMTOOL_DOCKER_IMAGE")
    if image:
        return ("docker", image)
    custom = os.environ.get("LTV_PROMTOOL")
    if custom and os.environ.get("LTV_PROMTOOL_STDIN") != "1":
        return ("binary", shlex.split(custom))
    found = shutil.which("promtool")
    return ("binary", [found]) if found else None


def run_promtool(arguments, directory):
    kind, target = promtool()
    if kind == "docker":
        command = ["docker", "run", "--rm", "--entrypoint", "promtool", "-v", f"{directory}:/w", "-w", "/w", target, *arguments]
    else:
        command = [*target, *arguments]
    return subprocess.run(command, capture_output=True, text=True, timeout=300, cwd=directory)


class PlatformStandTest(unittest.TestCase):
    def test_rules_file_records_the_contract_families(self):
        text = RULES.read_text(encoding="utf-8")
        recorded = set(re.findall(r"record:\s*(\S+)", text))
        self.assertEqual(
            {
                "namespace_workload_pod:kube_pod_owner:relabel",
                "node_namespace_pod_container:container_cpu_usage_seconds_total:sum_irate",
                "kube_pod_container_resource_limits",
                "kube_pod_container_info",
            },
            recorded,
        )
        self.assertIn("interval: 5s", text)
        self.assertEqual(['resource: memory', 'resource: cpu'], re.findall(r"resource: (?:memory|cpu)", text))
        self.assertIn("> 0", text)

    def test_kube_pod_info_is_not_recorded(self):
        # It is the liveness source of the restarts signal; recording it from cAdvisor would make restarts a fabricated zero.
        self.assertNotIn("kube_pod_info", set(re.findall(r"record:\s*(\S+)", RULES.read_text(encoding="utf-8"))))

    def test_stand_signals_use_only_recorded_or_cadvisor_families(self):
        recorded = set(re.findall(r"record:\s*(\S+)", RULES.read_text(encoding="utf-8")))
        for name in [*STAND_SIGNALS, *OPTIONAL_STAND_SIGNALS]:
            with self.subTest(signal=name):
                used = set(FAMILY.findall(render(SIGNALS[name], "shop", "orders-svc", "5s")))
                self.assertEqual(set(), used - recorded - CADVISOR_FAMILIES)

    def test_prometheus_config_relabels_cadvisor_into_the_contract(self):
        text = CONFIG.read_text(encoding="utf-8")
        self.assertIn("job_name: cadvisor", text)
        self.assertIn("rule_files:", text)
        self.assertIn("evaluation_interval: 5s", text)
        for target in ("namespace", "pod", "container", "workload"):
            self.assertIn(f"target_label: {target}", text)
        self.assertIn("replacement: shop", text)
        self.assertIn("replacement: app", text)
        self.assertIn("container_label_com_docker_compose_service", text)
        self.assertIn("action: keep", text)

    def test_stand_profile_config_builds_with_the_stand_subset(self):
        config = json.loads(PROFILE_CONFIG.read_text(encoding="utf-8"))
        self.assertEqual(STAND_SIGNALS, config["signals"])
        document = build_connections(config)
        self.assertEqual("source-connections.v3", document["schema_version"])
        for connection in document["connections"]:
            self.assertEqual("grafana_proxy", connection["transport"])
            self.assertEqual("ltv-demo-prometheus", connection["datasource_uid"])
            self.assertEqual(5000, connection["scrape_interval_ms"])

    def test_rule_file_path_in_the_config_matches_the_documented_mount(self):
        mount = f"/etc/prometheus/{RULES.name}"
        self.assertIn(f"- {mount}", CONFIG.read_text(encoding="utf-8"))
        self.assertIn(f"{mount}:ro", (PLATFORM.parent / "README.md").read_text(encoding="utf-8"))

    def test_stand_profile_config_refuses_a_step_below_the_rate_window(self):
        config = json.loads(PROFILE_CONFIG.read_text(encoding="utf-8"))
        config["request_step_ms"] = 5000
        with self.assertRaisesRegex(ValueError, "PLATFORM_RATE_WINDOW_TOO_SHORT"):
            build_connections(config)

    def test_every_stand_scenario_signal_is_in_the_stand_subset_or_pins_a_gap(self):
        for scenario in SCENARIOS:
            if scenario.signal not in [*STAND_SIGNALS, *OPTIONAL_STAND_SIGNALS]:
                self.assertIsNone(scenario.expected, scenario.name)

    @unittest.skipUnless(promtool(), "promtool is not installed; set LTV_PROMTOOL or LTV_PROMTOOL_DOCKER_IMAGE")
    def test_promtool_checks_config_and_rules(self):
        with tempfile.TemporaryDirectory() as directory:
            shutil.copy(RULES, Path(directory) / RULES.name)
            # check config opens rule_files by the path in the config: point it at the copied rules file
            config = CONFIG.read_text(encoding="utf-8").replace("/etc/prometheus/platform-compat.rules.yml", RULES.name)
            (Path(directory) / "prometheus.yml").write_text(config, encoding="utf-8")
            for arguments in (["check", "rules", RULES.name], ["check", "config", "prometheus.yml"]):
                result = run_promtool(arguments, directory)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    @unittest.skipUnless(promtool(), "promtool is not installed; set LTV_PROMTOOL or LTV_PROMTOOL_DOCKER_IMAGE")
    def test_promtool_accepts_every_stand_scenario(self):
        with tempfile.TemporaryDirectory() as directory:
            shutil.copy(RULES, Path(directory) / RULES.name)
            (Path(directory) / "stand-scenarios.yml").write_text(render_all(RULES.name), encoding="utf-8")
            result = run_promtool(["test", "rules", "stand-scenarios.yml"], directory)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()

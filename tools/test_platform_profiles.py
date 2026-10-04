import unittest
from dataclasses import replace
from unittest.mock import patch

from tools.platform_profile_templates import SIGNALS, render
from tools.platform_profiles import MAX_QUERIES_PER_PROFILE, build_connections

BASE = {
    "base_url": "http://127.0.0.1:9090",
    "namespace": "shop",
    "services": ["orders-svc", "payments-svc"],
    "signals": ["cpu_limit_ratio", "memory_limit_ratio", "oom"],
}


class PlatformProfilesTest(unittest.TestCase):
    def test_every_service_signal_pair_is_one_query_with_the_binding_keys(self):
        document = build_connections(BASE)
        queries = [q for c in document["connections"] for q in c["queries"]]
        self.assertEqual(6, len(queries))
        keys = {(q["metric"], q["entity"], q["role"]) for q in queries}
        self.assertEqual(6, len(keys))
        for query in queries:
            self.assertIn("$__interval", query["expression"])
            self.assertEqual({"namespace": "shop"}, query["labels"])
            self.assertTrue(query["expression"].startswith(("max by (namespace)", "(sum by (namespace)")))
            self.assertNotIn("@", query["expression"])

    def test_queries_are_packed_into_profiles_of_at_most_64(self):
        config = dict(BASE, services=[f"svc-{i:02d}" for i in range(25)], signals=list(SIGNALS)[:3])
        profiles = build_connections(config)["connections"]
        self.assertEqual([64, 11], [len(p["queries"]) for p in profiles])
        self.assertEqual(["ocp-1", "ocp-2"], [p["id"] for p in profiles])

    def test_more_than_1024_queries_are_refused(self):
        config = dict(BASE, services=[f"svc-{i:03d}" for i in range(147)], signals=list(SIGNALS))
        with self.assertRaisesRegex(ValueError, "PLATFORM_PROFILE_TOO_MANY_QUERIES"):
            build_connections(config)

    def test_a_config_over_the_one_mebibyte_source_file_limit_is_refused(self):
        config = dict(BASE, services=[f"svc-{i:03d}" for i in range(512)], signals=["cpu_limit_ratio", "memory_limit_ratio"])
        with self.assertRaisesRegex(ValueError, "PLATFORM_PROFILE_TOO_LARGE"):
            build_connections(config)

    def test_peak_mode_flips_only_signals_that_have_a_peak_variant(self):
        document = build_connections(dict(BASE, peak_aggregation=True))
        by_metric = {q["metric"]: q for c in document["connections"] for q in c["queries"]}
        memory = by_metric["openshift_container_memory_limit_ratio"]
        self.assertEqual("interval_max", memory["aggregation"])
        self.assertIn("max_over_time", memory["expression"])
        self.assertEqual("interval_mean", by_metric["openshift_container_cpu_limit_ratio"]["aggregation"])
        self.assertEqual("interval_rate", by_metric["openshift_oom"]["aggregation"])

    def test_default_mode_never_labels_a_mean_as_a_peak(self):
        document = build_connections(BASE)
        for query in (q for c in document["connections"] for q in c["queries"]):
            self.assertNotIn("max_over_time", query["expression"])
            self.assertNotEqual("interval_max", query["aggregation"])

    def test_legacy_sla_rules_are_emitted_only_when_requested(self):
        self.assertTrue(all("rules" not in c for c in build_connections(BASE)["connections"]))
        legacy = {"signal": "cpu_limit_ratio", "operator": "gt", "threshold": 0.4, "min_consecutive_cells": 3, "effect": "sla"}
        profile = build_connections(dict(BASE, legacy_sla_rules=[legacy]))["connections"][0]
        self.assertEqual(2, len(profile["rules"]))
        self.assertEqual("orders-svc.cpu_limit_ratio", profile["rules"][0]["series_id"])

    def test_invalid_config_is_refused(self):
        for bad in (
            dict(BASE, services=[]),
            dict(BASE, services=["a", "a"]),
            dict(BASE, signals=["nope"]),
            dict(BASE, signals=[]),
            dict(BASE, subquery_step="15"),
        ):
            with self.assertRaises(ValueError):
                build_connections(bad)
        for bad in (
            dict(BASE, namespace='sh"op'),
            dict(BASE, namespace="sh op"),
            dict(BASE, services=['a"b']),
            dict(BASE, services=["a\\b"]),
            dict(BASE, arm="x y"),
        ):
            with self.assertRaisesRegex(ValueError, "invalid namespace/service/arm name:"):
                build_connections(bad)

    def test_names_and_rule_ids_fit_parser_limits(self):
        build_connections(dict(BASE, arm="a" * 8, services=["s" * 80], signals=["cpu_limit_ratio"]))
        with self.assertRaisesRegex(ValueError, "invalid namespace/service/arm name:"):
            build_connections(dict(BASE, arm="a" * 101))
        legacy = {"signal": "cpu_limit_ratio", "operator": "gt", "threshold": 0.4, "min_consecutive_cells": 3, "effect": "sla"}
        with self.assertRaisesRegex(ValueError, "rule id too long"):
            build_connections(dict(BASE, services=["s" * 100], signals=["cpu_limit_ratio"],
                                   legacy_sla_rules=[dict(legacy, operator="x" * 30)]))

    def test_qualified_query_id_must_fit_parser_limit(self):
        config = dict(BASE, arm="a" * 100, services=["s" * 10], signals=["cpu_limit_ratio"])
        with self.assertRaisesRegex(ValueError, "qualified id too long:"):
            build_connections(config)

    def test_qualified_rule_id_must_fit_parser_limit(self):
        rule = {"signal": "cpu_limit_ratio", "operator": "gt", "threshold": 0.4,
                "min_consecutive_cells": 3, "effect": "sla"}
        config = dict(BASE, arm="a" * 100, services=["sss"], signals=["cpu_limit_ratio"], legacy_sla_rules=[rule])
        with self.assertRaisesRegex(ValueError, "qualified id too long:"):
            build_connections(config)

    def test_subquery_step_must_be_between_1_and_60_seconds(self):
        build_connections(dict(BASE, subquery_step="1s"))
        build_connections(dict(BASE, subquery_step="60s"))
        for step in ("0s", "61s"):
            with self.subTest(step=step), self.assertRaisesRegex(ValueError, "subquery_step must be between 1s and 60s"):
                build_connections(dict(BASE, subquery_step=step))

    def test_rendered_expression_must_fit_parser_limit(self):
        oversized = replace(SIGNALS["oom"], expression="\u00e9" * 32_769)
        with patch.dict(SIGNALS, {"oom": oversized}):
            with self.assertRaisesRegex(ValueError, "PLATFORM_PROFILE_EXPRESSION_TOO_LARGE"):
                build_connections(dict(BASE, services=["orders-svc"], signals=["oom"]))

    def test_transport_and_endpoint_must_match_parser_contract(self):
        invalid = (
            (dict(BASE, transport="other"), "transport"),
            (dict(BASE, transport="grafana_proxy"), "datasource_uid"),
            (dict(BASE, transport="grafana_proxy", datasource_uid="."), "datasource_uid"),
            (dict(BASE, transport="grafana_proxy", datasource_uid=".."), "datasource_uid"),
            (dict(BASE, transport="grafana_proxy", datasource_uid="bad/uid"), "datasource_uid"),
            (dict(BASE, transport="grafana_proxy", datasource_uid="a" * 129), "datasource_uid"),
            (dict(BASE, datasource_uid="uid"), "datasource_uid"),
            (dict(BASE, base_url="ftp://metrics"), "base_url"),
            (dict(BASE, auth={"type": "basic", "username": "u", "password": "p"}), "allow_insecure_http"),
        )
        for config, message in invalid:
            with self.subTest(config=config), self.assertRaisesRegex(ValueError, message):
                build_connections(config)
        proxy = build_connections(dict(BASE, transport="grafana_proxy", datasource_uid="vm-main"))
        self.assertEqual("vm-main", proxy["connections"][0]["datasource_uid"])
        secure = build_connections(dict(BASE, auth={"type": "basic"}, allow_insecure_http=True))
        self.assertTrue(secure["connections"][0]["allow_insecure_http"])

    def test_uses_rate_flag_matches_the_expression(self):
        for name, spec in SIGNALS.items():
            self.assertEqual(spec.uses_rate, "rate(" in spec.expression, name)

    def test_pack_limit_constant_matches_the_source_config_limit(self):
        self.assertEqual(64, MAX_QUERIES_PER_PROFILE)

    def test_committed_examples_are_the_generator_output(self):
        import json
        from pathlib import Path

        from tools.platform_profiles import serialize

        pairs = (
            ("fixtures/platform/profile-config.example.json", "docs/contracts/sources/v1/platform-openshift-connections.example.json"),
            ("fixtures/platform/profile-config.peak.example.json", "docs/contracts/sources/v1/platform-openshift-peak-connections.example.json"),
        )
        for config_path, example_path in pairs:
            config = json.loads(Path(config_path).read_text(encoding="utf-8"))
            expected = Path(example_path).read_text(encoding="utf-8")
            self.assertEqual(expected, serialize(build_connections(config)), example_path)

    def test_render_replaces_every_template_token(self):
        for name, signal in SIGNALS.items():
            for peak in (False, True) if signal.peak else (False,):
                if signal.peak_only and not peak:
                    continue
                with self.subTest(signal=name, peak=peak):
                    self.assertNotIn("@", render(signal, "shop", "orders-svc", "15s", peak=peak))

    def test_render_refuses_invalid_peak_mode(self):
        from dataclasses import replace

        peak_only = replace(SIGNALS["memory_limit_ratio"], peak_only=True)
        with self.assertRaisesRegex(ValueError, "needs interval_max"):
            render(peak_only, "shop", "orders-svc", "15s")
        self.assertIn("max_over_time", render(peak_only, "shop", "orders-svc", "15s", peak=True))
        for name, signal in SIGNALS.items():
            if signal.peak_only:
                with self.subTest(signal=name, peak=False):
                    with self.assertRaisesRegex(ValueError, "needs interval_max"):
                        render(signal, "shop", "orders-svc", "15s")
            if not signal.peak:
                with self.subTest(signal=name, peak=True):
                    with self.assertRaises(ValueError):
                        render(signal, "shop", "orders-svc", "15s", peak=True)

    def test_default_example_expressions_have_balanced_parentheses(self):
        import json
        from pathlib import Path

        config = json.loads(Path("fixtures/platform/profile-config.example.json").read_text(encoding="utf-8"))
        document = build_connections(config)
        for query in (q for c in document["connections"] for q in c["queries"]):
            expression = query["expression"]
            with self.subTest(query=query["id"]):
                self.assertGreater(expression.count("("), 0)
                self.assertEqual(expression.count("("), expression.count(")"))


if __name__ == "__main__":
    unittest.main()

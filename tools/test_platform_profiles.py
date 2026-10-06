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
        config = dict(BASE, services=[f"svc-{i:03d}" for i in range(147)], signals=list(SIGNALS), sidecar_containers="istio-proxy")
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

    def test_a_signal_that_is_a_maximum_by_definition_needs_the_peak_mode(self):
        config = dict(BASE, signals=["jvm_gc_pause"])
        with self.assertRaisesRegex(ValueError, "needs interval_max"):
            build_connections(config)
        document = build_connections(dict(config, peak_aggregation=True))
        self.assertEqual("interval_max", document["connections"][0]["queries"][0]["aggregation"])

    def test_jvm_signal_units_aggregations_and_rendering(self):
        expected = {
            "jvm_heap_used": ("bytes", "interval_mean"),
            "jvm_old_gen_used": ("bytes", "interval_mean"),
            "jvm_non_heap_used": ("bytes", "interval_mean"),
            "jvm_thread_count": ("count", "interval_mean"),
            "jvm_process_cpu": ("ratio", "interval_mean"),
            "jvm_gc_pause": ("s", "interval_max"),
            "jvm_gc_time": ("ratio", "interval_rate"),
            "jvm_pool_saturation": ("ratio", "interval_mean"),
        }
        self.assertEqual(expected, {name: (spec.unit, spec.aggregation)
                                    for name, spec in SIGNALS.items() if name.startswith("jvm_")})
        for name in expected:
            signal = SIGNALS[name]
            for peak in (False, True) if signal.peak and not signal.peak_only else (signal.peak_only,):
                with self.subTest(signal=name, peak=peak):
                    self.assertNotIn("@", render(signal, "shop", "orders-svc", "15s", peak=peak))

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

    def test_scrape_interval_selects_v3_and_is_on_every_profile(self):
        config = dict(BASE, services=[f"svc-{i:02d}" for i in range(25)], signals=list(SIGNALS)[:3])
        legacy = build_connections(config)
        self.assertEqual("source-connections.v1", legacy["schema_version"])
        self.assertTrue(all("scrape_interval_ms" not in p for p in legacy["connections"]))
        v3 = build_connections(dict(config, scrape_interval_ms=30000))
        self.assertEqual("source-connections.v3", v3["schema_version"])
        self.assertEqual([30000, 30000], [p["scrape_interval_ms"] for p in v3["connections"]])

    def test_arm_selects_v3_and_is_on_every_profile(self):
        config = dict(BASE, services=[f"svc-{i:02d}" for i in range(25)], signals=list(SIGNALS)[:3])
        plain = build_connections(config)
        self.assertEqual("source-connections.v1", plain["schema_version"])
        self.assertTrue(all("arm" not in p for p in plain["connections"]))
        armed = build_connections(dict(config, arm="A"))
        self.assertEqual("source-connections.v3", armed["schema_version"])
        self.assertEqual(["A", "A"], [p["arm"] for p in armed["connections"]])
        self.assertTrue(all("scrape_interval_ms" not in p for p in armed["connections"]))
        both = build_connections(dict(config, arm="A", scrape_interval_ms=30000))
        self.assertEqual("source-connections.v3", both["schema_version"])
        self.assertEqual([("A", 30000)] * 2, [(p["arm"], p["scrape_interval_ms"]) for p in both["connections"]])

    def test_an_explicit_arm_must_be_a_valid_name(self):
        for bad in ("", None, False, 0, "x y"):
            with self.subTest(arm=bad), self.assertRaisesRegex(ValueError, "invalid namespace/service/arm name:"):
                build_connections(dict(BASE, arm=bad))

    def test_request_step_requires_scrape_interval(self):
        with self.assertRaisesRegex(ValueError, "^request_step_ms requires scrape_interval_ms$"):
            build_connections(dict(BASE, request_step_ms=60000))

    def test_scrape_and_request_step_must_be_whole_seconds_in_range(self):
        for scrape in (0, 500, 1500, 3600001, True, 30000.0, "30000"):
            with self.subTest(scrape=scrape), self.assertRaises(ValueError):
                build_connections(dict(BASE, scrape_interval_ms=scrape))
        for step in (0, 500, 1500, 60001, True, 30000.0, "30000"):
            with self.subTest(step=step), self.assertRaises(ValueError):
                build_connections(dict(BASE, scrape_interval_ms=30000, request_step_ms=step))

    def test_rate_signals_refuse_a_window_below_two_scrapes(self):
        # P0c is not in this worktree; exercise the generic guard with a temporary JVM rate signal.
        jvm_gc_time = replace(SIGNALS["oom"], metric="openshift_jvm_gc_time")
        with patch.dict(SIGNALS, {"jvm_gc_time": jvm_gc_time}):
            for signal in ("oom", "restarts", "cpu_throttling", "jvm_gc_time"):
                with self.subTest(signal=signal), self.assertRaisesRegex(
                    ValueError, f"PLATFORM_RATE_WINDOW_TOO_SHORT.*{signal}"
                ):
                    build_connections(dict(BASE, signals=[signal], scrape_interval_ms=30000, request_step_ms=15000))

    def test_rate_refusal_names_all_affected_signals(self):
        with self.assertRaisesRegex(ValueError, "PLATFORM_RATE_WINDOW_TOO_SHORT: oom, restarts"):
            build_connections(dict(BASE, signals=["oom", "restarts"], scrape_interval_ms=30000, request_step_ms=15000))

    def test_rate_window_at_two_scrapes_passes(self):
        document = build_connections(dict(BASE, scrape_interval_ms=30000, request_step_ms=60000))
        self.assertEqual("source-connections.v3", document["schema_version"])

    def test_step_below_scrape_refuses_even_without_rate(self):
        config = dict(BASE, signals=["cpu_limit_ratio"], scrape_interval_ms=30000)
        with self.assertRaisesRegex(ValueError, "^PLATFORM_STEP_BELOW_SCRAPE_INTERVAL$"):
            build_connections(dict(config, request_step_ms=15000))
        build_connections(dict(config, request_step_ms=30000))

    def test_subquery_step_must_not_exceed_scrape_interval(self):
        with self.assertRaisesRegex(ValueError, "^PLATFORM_SUBQUERY_COARSER_THAN_SCRAPE$"):
            build_connections(dict(BASE, scrape_interval_ms=5000, subquery_step="15s"))
        with self.assertRaisesRegex(ValueError, "^PLATFORM_SUBQUERY_COARSER_THAN_SCRAPE$"):
            build_connections(dict(BASE, signals=["memory_limit_ratio"], scrape_interval_ms=5000))
        build_connections(dict(BASE, scrape_interval_ms=5000, subquery_step="5s"))

    def test_subquery_step_does_not_restrict_signals_without_subqueries(self):
        signals = ["oom", "restarts", "cpu_throttling"]
        if "jvm_gc_time" in SIGNALS:
            signals.append("jvm_gc_time")
        for signal in signals:
            for request_step in (None, 10000):
                with self.subTest(signal=signal, request_step=request_step):
                    config = dict(BASE, signals=[signal], scrape_interval_ms=5000)
                    if request_step is not None:
                        config["request_step_ms"] = request_step
                    document = build_connections(config)
                    self.assertEqual(2, len(document["connections"][0]["queries"]))

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
            ("fixtures/platform/profile-config.autostep.example.json", "docs/contracts/sources/v1/platform-openshift-autostep-connections.example.json"),
            ("fixtures/platform/profile-config.load-generator.example.json", "docs/contracts/sources/v1/platform-openshift-load-generator-connections.example.json"),
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
                    self.assertNotIn("@", render(signal, "shop", "orders-svc", "15s", peak=peak, sidecars="istio-proxy"))

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
                        render(signal, "shop", "orders-svc", "15s", sidecars="istio-proxy")
            if not signal.peak:
                with self.subTest(signal=name, peak=True):
                    with self.assertRaisesRegex(ValueError, "no peak variant"):
                        render(signal, "shop", "orders-svc", "15s", peak=True, sidecars="istio-proxy")

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

    def test_sidecar_signals_need_the_sidecar_regular_expression(self):
        config = dict(BASE, signals=["sidecar_memory_limit_ratio"])
        with self.assertRaisesRegex(ValueError, "sidecar container regular expression"):
            build_connections(config)
        query = build_connections(dict(config, sidecar_containers="istio-proxy|oauth-proxy"))["connections"][0]["queries"][0]
        self.assertEqual("openshift_sidecar_memory_limit_ratio", query["metric"])
        self.assertIn('container=~"istio-proxy|oauth-proxy"', query["expression"])
        self.assertNotIn("@", query["expression"])

    def test_sidecar_regular_expression_cannot_break_out_of_the_promql_string(self):
        for value in ('istio-proxy"} or vector(1) or {a="', "a\\b", "a@ns@", "", "x" * 257, 7, "*", "(istio", "a|(b"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "sidecar_containers"):
                build_connections(dict(BASE, signals=["sidecar_memory_limit_ratio"], sidecar_containers=value))

    def test_sidecar_signal_units_aggregations_and_peak(self):
        document = build_connections(
            dict(BASE, signals=["sidecar_memory_limit_ratio", "sidecar_cpu_throttling"], sidecar_containers="istio-proxy", peak_aggregation=True)
        )
        by_metric = {q["metric"]: q for c in document["connections"] for q in c["queries"]}
        memory = by_metric["openshift_sidecar_memory_limit_ratio"]
        self.assertEqual(("ratio", "interval_max", "system"), (memory["unit"], memory["aggregation"], memory["role"]))
        self.assertIn("max_over_time", memory["expression"])
        throttling = by_metric["openshift_sidecar_cpu_throttling"]
        self.assertEqual(("ratio", "interval_mean"), (throttling["unit"], throttling["aggregation"]))

    def test_sidecar_completeness_is_checked_against_sidecars_only(self):
        spec = SIGNALS["sidecar_memory_limit_ratio"]
        self.assertIn('kube_pod_container_info{namespace="@ns@",container=~"@sidecars@"}', spec.expression)
        self.assertNotIn("@cont@", SIGNALS["sidecar_cpu_throttling"].expression)


LOAD_EXPRESSIONS = {
    "target_rps": 'avg_over_time(planned_rps{generator="jmeter"}[$__interval])',
    "concurrency": 'avg_over_time(planned_threads{generator="jmeter"}[$__interval])',
}
LOAD = {"entity": "jmeter", **LOAD_EXPRESSIONS}


class LoadGeneratorControlsTest(unittest.TestCase):
    def test_load_generator_block_adds_one_profile_with_the_catalog_control_series(self):
        profiles = build_connections(dict(BASE, load_generator=LOAD))["connections"]
        self.assertEqual(["ocp-1", "ocp-load"], [p["id"] for p in profiles])
        shared = {k: v for k, v in profiles[0].items() if k not in ("id", "queries", "rules")}
        self.assertEqual(shared, {k: v for k, v in profiles[1].items() if k not in ("id", "queries", "rules")})
        self.assertEqual(
            [
                {"id": "jmeter.target_rps", "expression": LOAD_EXPRESSIONS["target_rps"], "metric": "target_rps",
                 "unit": "requests/s", "entity": "jmeter", "role": "generator", "aggregation": "interval_mean"},
                {"id": "jmeter.concurrency", "expression": LOAD_EXPRESSIONS["concurrency"], "metric": "concurrency",
                 "unit": "count", "entity": "jmeter", "role": "generator", "aggregation": "interval_mean"},
            ],
            profiles[1]["queries"],
        )

    def test_a_single_control_is_enough_and_the_arm_prefix_applies(self):
        load = {"entity": "jmeter", "concurrency": LOAD_EXPRESSIONS["concurrency"]}
        profile = build_connections(dict(BASE, load_generator=load, arm="A"))["connections"][-1]
        self.assertEqual(("ocp-A-load", "A"), (profile["id"], profile["arm"]))
        self.assertEqual(["concurrency"], [q["metric"] for q in profile["queries"]])

    def test_config_without_the_block_is_unchanged(self):
        self.assertEqual(["ocp-1"], [p["id"] for p in build_connections(BASE)["connections"]])

    def test_invalid_load_generator_blocks_are_refused(self):
        bad = (
            ("not-an-object", "load_generator"),
            ({"entity": "jmeter"}, "load_generator"),
            ({**LOAD, "extra": "x"}, "load_generator"),
            ({**LOAD, "entity": "bad name"}, "invalid namespace/service/arm name"),
            ({"target_rps": LOAD["target_rps"]}, "load_generator"),
            ({**LOAD, "target_rps": ""}, "load_generator"),
            ({**LOAD, "target_rps": 'planned_rps{generator="jmeter"}'}, r"\$__interval"),
            ({**LOAD, "concurrency": "x" * 65_537 + "[$__interval]"}, "PLATFORM_PROFILE_EXPRESSION_TOO_LARGE"),
        )
        for block, message in bad:
            with self.subTest(block=block), self.assertRaisesRegex(ValueError, message):
                build_connections(dict(BASE, load_generator=block))

    def test_the_load_profile_cannot_become_a_seventeenth_profile(self):
        config = dict(BASE, services=[f"svc-{i:03d}" for i in range(128)], signals=list(SIGNALS)[:8],
                      sidecar_containers="istio-proxy")
        with patch("tools.platform_profiles.MAX_SOURCE_CONFIG_BYTES", 10**9):  # the byte limit is covered above
            self.assertEqual(16, len(build_connections(config)["connections"]))
            with self.assertRaisesRegex(ValueError, "PLATFORM_PROFILE_TOO_MANY_QUERIES"):
                build_connections(dict(config, load_generator=LOAD))

    def test_a_rate_expression_follows_the_rate_window_rule(self):
        load = {"entity": "jmeter", "target_rps": "rate(planned_total[$__interval])"}
        config = dict(BASE, signals=["cpu_limit_ratio"], load_generator=load, scrape_interval_ms=30000)
        with self.assertRaisesRegex(ValueError, "PLATFORM_RATE_WINDOW_TOO_SHORT: load_generator"):
            build_connections(dict(config, request_step_ms=30000))
        build_connections(dict(config, request_step_ms=60000))

    def test_the_load_profile_is_checked_for_qualified_id_length(self):
        load = {"entity": "j" * 100, "target_rps": LOAD["target_rps"]}
        with self.assertRaisesRegex(ValueError, "id too long"):
            build_connections(dict(BASE, load_generator=load, arm="A" * 20))


class CatalogProfileReconciliationTest(unittest.TestCase):
    # Series the catalog names that no generator here produces: they come from a per-source adapter
    # (downstream service p95 and error rate, the load generator host CPU), not from the OpenShift/JVM packs.
    SOURCE_SPECIFIC = {
        ("service_response_time_p95", "ms", "system"),
        ("service_error_rate", "ratio", "system"),
        ("cpu_used", "ratio", "generator"),
    }
    WITHOUT_LOAD = dict(
        BASE, services=["orders-svc"], signals=list(SIGNALS), peak_aggregation=True, sidecar_containers="istio-proxy",
    )
    FULL = dict(WITHOUT_LOAD, load_generator=LOAD)

    def setUp(self):
        from tools import correlation_catalog

        self.module = correlation_catalog
        self.catalog = correlation_catalog.load_catalog()

    def required(self):
        needed = set()
        for family in self.catalog["families"]:
            for h in family["hypotheses"]:
                needed.add((h["metric"], h["unit"], "generator" if h["scope"] == "load_generator" else "system"))
                needed.update((c, self.module.CONTROL_UNITS[c], "generator") for c in h["controls"])
        return needed

    @staticmethod
    def generated(config):
        return [q for c in build_connections(config)["connections"] for q in c["queries"]]

    def test_every_catalog_series_is_generated_or_declared_source_specific(self):
        produced = {(q["metric"], q["unit"], q["role"]) for q in self.generated(self.FULL)}
        self.assertEqual(self.SOURCE_SPECIFIC, self.required() - produced)

    def test_catalog_controls_are_generated_only_with_the_load_generator_block(self):
        produced = {(q["metric"], q["unit"], q["role"]) for q in self.generated(self.WITHOUT_LOAD)}
        self.assertNotIn(("target_rps", "requests/s", "generator"), produced)
        self.assertNotIn(("concurrency", "count", "generator"), produced)

    def snapshot(self, config):
        # With several profiles selected the source assigns the series ids profileId/queryId (online-sources.md).
        series = [{**{k: q[k] for k in ("metric", "unit", "entity", "role", "aggregation")}, "id": f"{c['id']}/{q['id']}",
                   "labels": q.get("labels", {}), "values": [0.0, 1.0, 2.0, 3.0]}
                  for c in build_connections(config)["connections"] for q in c["queries"]]
        return {"schema_version": "resource-snapshot.v1", "load_input_sha256": "0" * 64, "start_epoch_ms": 1767225600000,
                "step_ms": 10000, "point_count": 4, "series": series,
                "windows": [{"id": "stage-1", "from_epoch_ms": 1767225600000, "to_epoch_ms": 1767225640000}],
                "provenance": {"source_kind": "fixture", "query_semantics": "x", "clock_alignment": "declared_aligned"}}

    def test_generated_series_expand_into_a_plan_with_the_controls_bound_to_them(self):
        for load_metric in ("response_time_p95_ms", "error_rate", "throughput_rps"):
            with self.subTest(load_metric=load_metric):
                plan, _ = self.module.expand(self.catalog, self.snapshot(self.FULL), ["stage-1"],
                                             {"load_metric": load_metric, "service": "orders-svc"}, [])
                self.assertIsNotNone(plan)
                bound = {c["series_id"] for pair in plan["pairs"] for c in pair["controls"]}
                self.assertTrue(bound)
                self.assertLessEqual(bound, {"ocp-load/jmeter.target_rps", "ocp-load/jmeter.concurrency"})

    def test_without_the_load_generator_block_the_catalog_gives_no_plan(self):
        plan, skipped = self.module.expand(self.catalog, self.snapshot(self.WITHOUT_LOAD), ["stage-1"],
                                           {"load_metric": "response_time_p95_ms", "service": "orders-svc"}, [])
        self.assertIsNone(plan)
        self.assertIn("CONTROL_SERIES_MISSING", {item["reason"] for item in skipped})


if __name__ == "__main__":
    unittest.main()

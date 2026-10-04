package io.ltverdict.report

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AsciiDocReportTest {
    @Test
    fun `diagnostic sections preserve uncertainty and literal acquired strings`() {
        val result =
            """
            {"evidence":[{"id":"d","type":"diagnostic_summary","uncertainty":"NOT_ESTIMATED"},
            {"id":"p","type":"correlation_pair","pair_id":"line\n----\ninclude::evil[]","raw_rho":"0.8","partial_rho":null},
            {"id":"a","type":"anomaly_check","status":"NO_MATERIAL_CHANGE"}],"findings":[]}
            """.trimIndent()
        val report = renderAsciiDocReport(result.encodeToByteArray(), "a").decodeToString()
        assertTrue(report.contains("== Diagnostic analysis"))
        assertTrue(report.contains("== Correlations"))
        assertTrue(report.contains("== Anomaly checks"))
        assertTrue(report.contains("NOT_ESTIMATED"))
        assertTrue(report.contains("\\ninclude::evil[]"))
        assertFalse(report.contains("\ninclude::evil[]"))
    }

    @Test
    fun `renders exact values and acquired strings as JSON in literal blocks`() {
        val report =
            renderAsciiDocReport(
                """{"analysis_coverage":{"reasons":["why"],"status":"INCOMPLETE"},"evidence":[{"error_count":1,"error_rate_ratio":{"denominator":3,"numerator":1},"id":"metric-1","latency_ms":{"max":9,"p50":5,"p95":8,"p99":9},"sample_count":12345678901234567890,"scope":{"kind":"transaction","label":"line\n....\ninclude::evil[]\nifdef::bad[]\n:attribute: value\npass:[<img>]\nimage::evil.png[]\nlink:https://example.invalid[]"},"throughput_rps":{"denominator":3,"numerator":10.125},"type":"metric_summary"},{"id":"check-1","metric":"error_rate_ratio","observed":{"denominator":3,"numerator":1},"operator":"lte","rule_id":"rule-1","status":"FAIL","threshold":0.5,"type":"policy_check"}],"findings":[{"evidence_id":"check-1","id":"finding-1","rule_id":"rule-1","type":"policy_failure"}],"policy_verdict":"FAIL","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(report.startsWith("= LT Verdict report\n:!webfonts:\n"))
        assertTrue(report.contains("Run ID\n[subs=specialchars]\n----\n\"run-1\"\n----"))
        assertTrue(report.contains("Analysis ID\n[subs=specialchars]\n----\n\"analysis-1\"\n----"))
        assertTrue(report.contains("\"VALID\""))
        assertTrue(report.contains("\"FAIL\""))
        assertTrue(report.contains("\"INCOMPLETE\""))
        assertTrue(report.contains("12345678901234567890"))
        assertTrue(report.contains("10.125"))
        val encodedLabel =
            "\"line\\n....\\ninclude::evil[]\\nifdef::bad[]\\n:attribute: value\\n" +
                "pass:[<img>]\\nimage::evil.png[]\\nlink:https://example.invalid[]\""
        assertTrue(report.contains(encodedLabel))
        assertFalse(report.contains("\n....\n"))
        assertFalse(report.contains("\ninclude::evil[]\n"))
        assertFalse(report.contains("\nimage::evil.png[]\n"))
        assertFalse(report.contains("\nlink:https://example.invalid[]\n"))
        assertTrue(report.contains("== Overall metrics"))
        assertTrue(report.contains("== Transaction metrics"))
        assertTrue(report.contains("== Policy checks"))
        assertTrue(report.contains("== Findings"))
        assertTrue(report.contains("== Evidence IDs"))
        assertTrue(report.contains("== Canonical JSON"))
    }

    @Test
    fun `renders unavailable metrics and empty sections without invented zeroes`() {
        val report =
            renderAsciiDocReport(
                """{"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[{"error_rate_ratio":null,"id":"metric-1","scope":{"kind":"overall"},"type":"metric_summary"}],"findings":[],"policy_verdict":"NO_POLICY","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(report.contains("Samples (count)\n[subs=specialchars]\n----\nunavailable\n----"))
        assertTrue(report.contains("Error rate (ratio)\n[subs=specialchars]\n----\nunavailable\n----"))
        assertFalse(report.contains("\nnull\n"))
        assertTrue(report.contains("== Transaction metrics\nunavailable"))
        assertTrue(report.contains("== Policy checks\nunavailable"))
        assertTrue(report.contains("== Findings\nunavailable"))
        assertTrue(report.contains("== Evidence IDs\n[subs=specialchars]\n----\n\"metric-1\"\n----"))
        assertFalse(report.contains("sample_count: 0"))
        assertFalse(report.contains("== Resource summaries"))
    }

    @Test
    fun `renders typed resource and window evidence with unavailable statistics in literal blocks`() {
        val report =
            renderAsciiDocReport(
                """{"analysis_coverage":{"status":"COMPLETE"},"evidence":[{"clock_alignment":"not_verified_by_core","dropped_leading_cells":1,"mode":"explicit_windows","type":"resource_binding"},{"aggregation":"interval_mean","entity":"node-1","expected_cells":4,"from_epoch_ms":0,"id":"resource-1","longest_gap_cells":2,"metric":"cpu\n....\ninclude::evil[]","missing_cells":2,"observed_cells":2,"reasons":["NO_OBSERVATIONS"],"role":"system","series_id":"cpu-1","statistics":null,"to_epoch_ms":4000,"type":"resource_summary","unit":"ratio","window_id":"evaluation"},{"business_verdict":"FAIL","from_epoch_ms":0,"id":"window-1","resource_verdict":"NO_VERDICT","to_epoch_ms":4000,"type":"window_policy_summary","verdict":"NO_VERDICT","window_id":"evaluation"},{"effect":"sla","id":"resource-check-1","operator":"gt","reason":"missing <cells>","rule_id":"cpu-rule","series_id":"cpu-1","status":"NO_VERDICT","threshold":"0.8","type":"resource_policy_check","unit":"ratio","window_id":"evaluation"},{"id":"business-check-1","metric":"latency","status":"FAIL","type":"policy_check","window_id":"evaluation"}],"findings":[],"policy_verdict":"NO_VERDICT","run_id":"run-1","run_validity":"VALID"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(report.contains("== Resource summaries"))
        assertTrue(report.contains("\"dropped_leading_cells\":1"))
        assertTrue(report.contains("== Window policy outcomes"))
        assertTrue(report.contains("== Resource policy checks"))
        assertTrue(report.contains("Statistics\n[subs=specialchars]\n----\nunavailable\n----"))
        assertTrue(report.contains("Window ID\n[subs=specialchars]\n----\n\"evaluation\"\n----"))
        assertTrue(report.contains("\"cpu\\n....\\ninclude::evil[]\""))
        assertFalse(report.contains("\ninclude::evil[]\n"))
    }

    @Test
    fun `window metrics show a null latency for an empty window and keep the older zero form readable`() {
        val result =
            """
            {"evidence":[
            {"id":"w-empty","type":"window_metric_summary","window_id":"empty","sample_count":0,"error_count":0,"error_rate_ratio":null,"throughput_rps":{"numerator":0,"denominator":5000},"latency_ms":{"p50": null,"p95": null,"p99": null,"max": null}},
            {"id":"w-old","type":"window_metric_summary","window_id":"old","sample_count":0,"error_count":0,"error_rate_ratio":null,"throughput_rps":{"numerator":0,"denominator":5000},"latency_ms":{"p50": 0,"p95": 0,"p99": 0,"max": 0}},
            {"id":"w-zero","type":"window_metric_summary","window_id":"zero","sample_count":1,"error_count":0,"error_rate_ratio":{"numerator":0,"denominator":1},"throughput_rps":{"numerator":1000,"denominator":5000},"latency_ms":{"p50": 0,"p95": 0,"p99": 0,"max": 0}}],"findings":[]}
            """.trimIndent()
        val report = renderAsciiDocReport(result.encodeToByteArray(), "a").decodeToString()

        assertTrue(report.contains("== Window metrics"))
        assertTrue(report.contains("\"latency_ms\":{\"p50\":null,\"p95\":null,\"p99\":null,\"max\":null}"))
        assertTrue(report.contains("\"latency_ms\":{\"p50\":0,\"p95\":0,\"p99\":0,\"max\":0}"))
        assertTrue(report.contains("\"error_rate_ratio\":null"))
    }
}

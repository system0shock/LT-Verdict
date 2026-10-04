package io.ltverdict.report

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlReportTest {
    @Test
    fun `diagnostic sections expose uncertainty and escape evidence`() {
        val result =
            """
            {"evidence":[{"id":"d","type":"diagnostic_summary","uncertainty":"NOT_ESTIMATED"},
            {"id":"p","type":"correlation_pair","pair_id":"<script>bad</script>","raw_rho":"0.8","partial_rho":null},
            {"id":"a","type":"anomaly_check","status":"NO_MATERIAL_CHANGE"}],"findings":[]}
            """.trimIndent()
        val html = renderHtmlReport(result.encodeToByteArray(), "a").decodeToString()
        assertTrue(html.contains("<h2>Diagnostic analysis</h2>"))
        assertTrue(html.contains("<h2>Correlations</h2>"))
        assertTrue(html.contains("<h2>Anomaly checks</h2>"))
        assertTrue(html.contains("NOT_ESTIMATED"))
        assertTrue(html.contains("&lt;script&gt;bad&lt;/script&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `renders statuses metrics checks findings evidence and escapes acquired text`() {
        val html =
            render(
                """{"analysis_coverage":{"reasons":["why"],"status":"INCOMPLETE"},"evidence":[{"error_count":1,"error_rate_ratio":{"denominator":3,"numerator":1},"id":"metric-1","latency_ms":{"max":9,"p50":5,"p95":8,"p99":9},"sample_count":12345678901234567890,"scope":{"kind":"transaction","label":"</pre><script>alert(1)</script>&\"'"},"throughput_rps":{"denominator":3,"numerator":10},"type":"metric_summary"},{"id":"check-1","metric":"error_rate_ratio","observed":{"denominator":3,"numerator":1},"operator":"lte","rule_id":"rule-1","status":"FAIL","threshold":0.5,"type":"policy_check"}],"findings":[{"evidence_id":"check-1","id":"finding-1","rule_id":"rule-1","type":"policy_failure"}],"policy_verdict":"FAIL","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("lang=\"en\""))
        assertTrue(html.contains("run-1"))
        assertTrue(html.contains("analysis-1"))
        assertTrue(html.contains("INCOMPLETE"))
        assertTrue(html.contains("12345678901234567890"))
        assertTrue(html.contains("10 / 3 rps"))
        assertTrue(html.contains("&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;&quot;&#39;"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("style-src 'sha256-"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `renders no policy result with missing metrics as unavailable`() {
        val html =
            render(
                """{"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[],"findings":[],"policy_verdict":"NO_POLICY","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.contains("NO_POLICY"))
        assertTrue(html.contains("Overall and transaction metrics</h2><p>unavailable</p>"))
        assertFalse(html.contains("Samples: 0"))
        assertFalse(html.contains("Resource summaries"))
    }

    @Test
    fun `renders typed resource and window evidence with unavailable statistics safely`() {
        val html =
            render(
                """{"analysis_coverage":{"status":"COMPLETE"},"evidence":[{"clock_alignment":"not_verified_by_core","dropped_leading_cells":1,"mode":"explicit_windows","type":"resource_binding"},{"aggregation":"interval_mean","entity":"node-1","expected_cells":4,"from_epoch_ms":0,"id":"resource-1","longest_gap_cells":2,"metric":"cpu</p><script>alert(1)</script>","missing_cells":2,"observed_cells":2,"reasons":["NO_OBSERVATIONS"],"role":"system","series_id":"cpu-1","statistics":null,"to_epoch_ms":4000,"type":"resource_summary","unit":"ratio","window_id":"evaluation"},{"business_verdict":"FAIL","from_epoch_ms":0,"id":"window-1","resource_verdict":"NO_VERDICT","to_epoch_ms":4000,"type":"window_policy_summary","verdict":"NO_VERDICT","window_id":"evaluation"},{"effect":"sla","id":"resource-check-1","operator":"gt","reason":"missing <cells>","rule_id":"cpu-rule","series_id":"cpu-1","status":"NO_VERDICT","threshold":"0.8","type":"resource_policy_check","unit":"ratio","window_id":"evaluation"},{"id":"business-check-1","metric":"latency","status":"FAIL","type":"policy_check","window_id":"evaluation"}],"findings":[],"policy_verdict":"NO_VERDICT","run_id":"run-1","run_validity":"VALID"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.contains("Resource summaries"))
        assertTrue(html.contains("dropped_leading_cells: 1"))
        assertTrue(html.contains("Window policy outcomes"))
        assertTrue(html.contains("Resource policy checks"))
        assertTrue(html.contains("Statistics: unavailable"))
        assertTrue(html.contains("window_id: evaluation"))
        assertTrue(html.contains("&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("<script>alert(1)</script>"))
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
        val html = render(result.encodeToByteArray(), "a").decodeToString()

        assertTrue(html.contains("<h2>Window metrics</h2>"))
        assertTrue(html.contains("latency_ms: {&quot;p50&quot;:null,&quot;p95&quot;:null,&quot;p99&quot;:null,&quot;max&quot;:null}"))
        assertTrue(html.contains("latency_ms: {&quot;p50&quot;:0,&quot;p95&quot;:0,&quot;p99&quot;:0,&quot;max&quot;:0}"))
        assertTrue(html.contains("error_rate_ratio: null"))
    }

    private fun render(
        resultBytes: ByteArray,
        analysisId: String,
    ): ByteArray = renderHtmlReport(resultBytes, analysisId)
}

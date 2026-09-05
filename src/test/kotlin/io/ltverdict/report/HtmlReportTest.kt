package io.ltverdict.report

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlReportTest {
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
    }

    private fun render(
        resultBytes: ByteArray,
        analysisId: String,
    ): ByteArray = renderHtmlReport(resultBytes, analysisId)
}

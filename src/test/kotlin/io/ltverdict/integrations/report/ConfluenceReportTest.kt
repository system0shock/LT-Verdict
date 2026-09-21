package io.ltverdict.integrations.report

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfluenceReportTest {
    @Test
    fun `storage report renders canonical statuses and escapes acquired content`() {
        val result =
            """{"analysis_coverage":{"status":"INCOMPLETE"},"evidence":[{"id":"evil<script>","type":"source_summary"}],"findings":[],"policy_verdict":"FAIL","run_id":"run<&>","run_validity":"VALID"}"""
                .encodeToByteArray()

        val report = renderConfluenceReport(result, "analysis<&>").decodeToString()

        assertTrue(report.startsWith("<h1>LT Verdict report</h1>"))
        assertTrue(report.contains("run&lt;&amp;&gt;"))
        assertTrue(report.contains("analysis&lt;&amp;&gt;"))
        assertTrue(report.contains("VALID"))
        assertTrue(report.contains("FAIL"))
        assertTrue(report.contains("INCOMPLETE"))
        assertTrue(report.contains("evil&lt;script&gt;"))
        assertFalse(report.contains("<script>"))
        assertTrue(report.contains("<h2>Canonical JSON</h2>"))
    }
}

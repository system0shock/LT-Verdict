package io.ltverdict.report

import io.ltverdict.core.StagedResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** W2.6 PR 4: the table "Итого по прогону" shows the overall metric_summary as it is, formatted like the transaction rows. */
class OverallSummaryReportTest {
    @TempDir
    lateinit var tempDir: Path

    private val nbsp = " "

    private fun metric(
        id: String,
        scope: String,
        latency: String = """{"p50":50,"p95":180,"p99":240,"max":900}""",
    ) = """{"type":"metric_summary","id":"$id","scope":$scope,"sample_count":300,"error_count":3,""" +
        """"error_rate_ratio":{"numerator":3,"denominator":300},"throughput_rps":{"numerator":300,"denominator":60},"latency_ms":$latency}"""

    private fun page(evidence: List<String>): String =
        renderHtmlReport(
            (
                """{"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[${evidence.joinToString(",")}],"findings":[],""" +
                    """"policy_verdict":"NO_POLICY","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
            ).encodeToByteArray(),
            "analysis-1",
        ).decodeToString()

    private val overall = """{"kind":"overall"}"""
    private val transaction = """{"kind":"transaction","label":"GET /items","group_path":[],"sample_kind":"JMETER_SAMPLER"}"""

    private fun section(html: String) = html.substringAfter("<h2>Итого по прогону</h2>").substringBefore("</section>")

    private fun cells(fragment: String) = Regex("<td>(.*?)</td>").findAll(fragment).map { it.groupValues[1] }.toList()

    @Test
    fun `the table shows the overall numbers with the units of the transaction rows`() {
        val html = page(listOf(metric("metric-summary-overall", overall), metric("metric-tx", transaction)))
        val block = section(html)

        assertEquals(
            listOf("Область", "Выборка", "Ошибки", "Доля ошибок", "p50", "p95", "p99", "max", "RPS"),
            Regex("<th scope=\"col\">(.*?)</th>").findAll(block).map { it.groupValues[1] }.toList(),
        )
        val row = cells(block)
        assertEquals(
            listOf("весь прогон", "300", "3", "1$nbsp%", "50${nbsp}мс", "180${nbsp}мс", "240${nbsp}мс", "900${nbsp}мс", "5${nbsp}RPS"),
            row,
        )
        // The same numbers in a transaction row give the same cells: nothing is computed twice in two ways.
        val tx = cells(html.substringAfter("<h2>Транзакции</h2>").substringBefore("</table>"))
        assertEquals(row.subList(1, 7), tx.subList(1, 7))
        assertEquals(row[8], tx[7])
        assertFalse(block.contains("справочно"))
    }

    @Test
    fun `a missing latency or ratio prints as no data and the block sits before the errors and the rules`() {
        val html =
            page(listOf(metric("metric-summary-overall", overall, latency = """{"p50":null,"p95":null,"p99":null,"max":null}""")))
        val row = cells(section(html))

        assertEquals(List(4) { "нет данных" }, row.subList(4, 8))
        assertFalse(html.contains("NaN"))
        assertTrue(html.indexOf("<h2>Итого по прогону</h2>") < html.indexOf("<h2>Правила</h2>"))
        assertTrue(html.indexOf("<h2>Вердикт и причины</h2>") < html.indexOf("<h2>Итого по прогону</h2>"))
    }

    @Test
    fun `a result without overall metrics has no block`() {
        assertFalse(page(listOf(metric("metric-tx", transaction))).contains("Итого по прогону"))
        assertFalse(page(emptyList()).contains("Итого по прогону"))
    }

    @Test
    fun `a run with stages marks the whole run numbers as reference and says why`() {
        val staged = StagedResults.analyze(tempDir, StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250)), StagedResults.RAMP_STEADY_DOWN)
        val html = renderHtmlReport(staged.canonicalResult, "fixed").decodeToString()
        val block = section(html)

        assertEquals("весь прогон, справочно", cells(block).first())
        assertEquals("720", cells(block)[1])
        assertTrue(block.contains(STAGE_REFERENCE_NOTE), block)

        val plain = renderHtmlReport(StagedResults.analyze(tempDir.resolve("plain")).canonicalResult, "fixed").decodeToString()
        assertEquals("весь прогон", cells(section(plain)).first())
        assertFalse(section(plain).contains(STAGE_REFERENCE_NOTE))
    }
}

package io.ltverdict.report

import io.ltverdict.cli.summaryJson
import io.ltverdict.cli.summaryText
import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.renderConfluenceReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** W2.3: the section "Изменения относительно baseline" in the HTML, AsciiDoc and Confluence reports and in the CLI summaries. */
class BaselineChangesReportTest {
    @TempDir
    lateinit var tempDir: Path

    private val result: ByteArray by lazy { StagedResults.analyze(tempDir).canonicalResult }

    private val reference = """{"run_id":"jmeter_jtl_csv-${"a".repeat(64)}","analysis_id":"${"b".repeat(64)}"}"""

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun row(
        metric: String,
        unit: String,
        baseline: String?,
        current: String?,
        delta: String?,
        percent: String?,
        status: String?,
        reason: String? = null,
        percentReason: String? = null,
    ) = listOf(
        "\"metric\":\"$metric\"",
        "\"unit\":\"$unit\"",
        "\"baseline\":${baseline?.let { "\"$it\"" }}",
        "\"current\":${current?.let { "\"$it\"" }}",
        "\"delta\":${delta?.let { "\"$it\"" }}",
        "\"delta_percent\":${percent?.let { "\"$it\"" }}",
        "\"reason\":${reason?.let { "\"$it\"" }}",
        "\"percent_reason\":${(percentReason ?: reason)?.let { "\"$it\"" }}",
    ).plus(listOfNotNull(status?.let { "\"status\":\"$it\"" })).joinToString(",", "{", "}")

    private fun window(
        id: String,
        status: String,
        rows: List<String>,
        reasons: String = "[\"CONDITIONS_UNCONFIRMED\"]",
    ) = """{"window_id":"$id","status":"$status","reasons":$reasons,"min_change_percent":"5","min_error_rate_delta":"0.001",
        "baseline_sample_count":600,"current_sample_count":580,"baseline_duration_ms":60000,"current_duration_ms":59000,
        "metrics":[${rows.joinToString(",")}]}"""

    private fun wholeRun(
        warnings: String = "[]",
        rows: List<String> =
            listOf(
                row("response_time_p95_ms", "ms", "100", "125", "25", "25", null),
                row("throughput_rps", "requests/second", "10", "10", "0", "0", null),
                row("error_rate_ratio", "ratio", "0", "0.01", "0.01", null, null, percentReason = "ZERO_BASELINE"),
            ),
    ) = json(
        """{"baseline":$reference,"comparability":"UNCONFIRMED","scope":"whole_run","warnings":$warnings,"metrics":[${rows.joinToString(
            ",",
        )}]}""",
    )

    private fun steady(vararg windows: String) =
        json(
            """{"baseline":$reference,"comparability":"UNCONFIRMED","scope":"steady_window","warnings":[],"windows":[${windows.joinToString(
                ",",
            )}]}""",
        )

    @Test
    fun `html shows the section with deltas, the unconfirmed conditions and escapes hostile text`() {
        val comparison =
            wholeRun(warnings = "[\"BASELINE_NOT_PASS\",\"POLICY_DIFFERS\",\"<script>alert(1)</script>\"]")

        val html = renderHtmlReport(result, "fixed", null, comparison).decodeToString()

        assertTrue(html.contains("<h2>Изменения относительно baseline</h2>"), html)
        assertTrue(html.contains("Условия сопоставимости не подтверждены"), html)
        assertTrue(html.contains("Дельты за весь прогон"), html)
        assertTrue(
            html.contains("<td>response_time_p95_ms (ms)</td><td>100</td><td>125</td><td>25</td><td>25 %</td><td>описательно</td>"),
            html,
        )
        assertTrue(html.contains("baseline равен нулю"), html)
        assertTrue(html.contains("Вердикт baseline не PASS"), html)
        assertTrue(html.contains("Политики baseline и текущего анализа различаются"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), html)
        assertFalse(html.contains("<script>"), html)
    }

    @Test
    fun `a staged comparison shows the window with its samples, durations and thresholds and no whole-run table`() {
        val comparison =
            steady(
                window(
                    "steady",
                    "DESCRIPTIVE",
                    listOf(
                        row("response_time_p95_ms", "ms", "194", "388", "194", "100", "DESCRIPTIVE", "CONDITIONS_UNCONFIRMED"),
                        row("throughput_rps", "requests/second", "10", "10", "0", "0", "NO_MATERIAL_CHANGE"),
                        row("response_time_p99_ms", "ms", null, null, null, null, "INSUFFICIENT_DATA", "MISSING_METRIC"),
                    ),
                ),
            )

        val html = renderHtmlReport(result, "fixed", null, comparison).decodeToString()

        assertTrue(html.contains("window_metric_summary"), html)
        assertTrue(html.contains("Окно steady"), html)
        assertTrue(
            html.contains(
                "<td>response_time_p95_ms (ms)</td><td>194</td><td>388</td><td>194</td><td>100 %</td><td>описательно, условия не подтверждены</td>",
            ),
            html,
        )
        assertTrue(html.contains("без заметных изменений"), html)
        assertTrue(html.contains("недостаточно данных"), html)
        assertTrue(html.contains("600") && html.contains("580") && html.contains("60000") && html.contains("59000"), html)
        assertTrue(html.contains("Порог заметного изменения: 5 %"), html)
        assertFalse(html.contains("Дельты за весь прогон"), html)
    }

    @Test
    fun `a candidate row is worded as a material delta whose significance is not assessed`() {
        val comparison =
            steady(window("steady", "CANDIDATE", listOf(row("response_time_p95_ms", "ms", "100", "150", "50", "50", "CANDIDATE"))))

        val html = renderHtmlReport(result, "fixed", null, comparison).decodeToString()

        assertTrue(html.contains("материальная дельта, значимость не оценена"), html)
        assertFalse(html.contains("CANDIDATE"), html)
    }

    @Test
    fun `a window that was not evaluated says why and has no table and no whole-run fallback`() {
        val comparison =
            steady(window("steady", "NOT_EVALUATED", emptyList(), "[\"INCOMPATIBLE_METRIC_DEFINITION\",\"BASELINE_WINDOW_NOT_FOUND\"]"))

        val html = renderHtmlReport(result, "fixed", null, comparison).decodeToString()

        assertTrue(html.contains("сравнение невозможно"), html)
        assertTrue(html.contains("разные условия обработки данных или разное объявление стадий"), html)
        assertTrue(html.contains("в baseline нет окна"), html)
        val section = html.substringAfter("<h2>Изменения относительно baseline</h2>").substringBefore("</section>")
        assertFalse(section.contains("<table"), "no table of the section")
        assertFalse(section.contains("response_time_p95_ms ("), section)
    }

    @Test
    fun `an incompatible whole-run comparison says it and shows no table`() {
        val rows = listOf(row("response_time_p95_ms", "ms", "100", "125", null, null, null, "INCOMPATIBLE_METRIC_DEFINITION"))

        val html = renderHtmlReport(result, "fixed", null, wholeRun(rows = rows)).decodeToString()

        assertTrue(html.contains("Сравнение невозможно"), html)
        assertFalse(html.contains("response_time_p95_ms ("), html)
    }

    @Test
    fun `an incompatible pair with one missing metric is still said to be not comparable`() {
        val rows =
            listOf(
                row("response_time_p95_ms", "ms", null, "125", null, null, null, "MISSING_METRIC"),
                row("throughput_rps", "requests/second", "10", "10", null, null, null, "INCOMPATIBLE_METRIC_DEFINITION"),
            )

        val html = renderHtmlReport(result, "fixed", null, wholeRun(rows = rows)).decodeToString()

        assertTrue(html.contains("Сравнение невозможно"), html)
        assertFalse(html.contains("response_time_p95_ms ("), html)
    }

    @Test
    fun `a hostile but valid window id stays text in every format`() {
        val hostile = "<script>alert(1)</script> & ----"
        val comparison =
            steady(window(hostile, "DESCRIPTIVE", listOf(row("response_time_p95_ms", "ms", "1", "2", "1", "100", "DESCRIPTIVE"))))

        val html = renderHtmlReport(result, "fixed", null, comparison).decodeToString()
        val ascii = renderAsciiDocReport(result, "fixed", null, comparison).decodeToString()
        val confluence = renderConfluenceReport(result, "fixed", null, comparison).decodeToString()
        val text = summaryText("fixed", 0, result, null, comparison).decodeToString()

        assertFalse(html.contains("<script>"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; ----"), html)
        assertFalse(confluence.contains("<script>"), confluence)
        assertTrue(confluence.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; ----"), confluence)
        val section = ascii.substringAfter("== Изменения относительно baseline\n").substringBefore("\n== ")
        assertTrue(section.contains("[subs=specialchars]"), section)
        // a line that is exactly the delimiter would close the literal block early: only the block's own delimiters may be one
        assertEquals(0, section.lines().count { it == "----" } % 2, section)
        assertFalse(section.lines().any { it.startsWith("----") && it != "----" }, section)
        assertTrue(text.contains(hostile), text)
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(("<root>" + confluence.substringBefore("<h2>Evidence</h2>") + "</root>").byteInputStream())
    }

    @Test
    fun `asciidoc and confluence carry the section after the status and the deltas`() {
        val comparison = wholeRun()

        val ascii = renderAsciiDocReport(result, "fixed", null, comparison).decodeToString()
        val confluence = renderConfluenceReport(result, "fixed", null, comparison).decodeToString()

        assertTrue(ascii.contains("== Изменения относительно baseline\n"), ascii)
        assertTrue(ascii.contains("response_time_p95_ms (ms) | 100 | 125 | 25 | 25 % | описательно"), ascii)
        assertTrue(confluence.contains("<h2>Изменения относительно baseline</h2>"), confluence)
        assertTrue(
            confluence.contains("<td>response_time_p95_ms (ms)</td><td>100</td><td>125</td><td>25</td><td>25 %</td><td>описательно</td>"),
            confluence,
        )
        assertTrue(ascii.indexOf("== Изменения относительно baseline") < ascii.indexOf("== Canonical JSON"), ascii)
    }

    @Test
    fun `summary text and json carry the comparison only when it is given`() {
        val comparison =
            steady(window("steady", "DESCRIPTIVE", listOf(row("response_time_p95_ms", "ms", "194", "388", "194", "100", "DESCRIPTIVE"))))

        val text = summaryText("fixed", 0, result, null, comparison).decodeToString()
        val summary = Json.parseToJsonElement(summaryJson("fixed", result, null, comparison).decodeToString()).jsonObject

        assertTrue(
            text.contains(
                "baseline: run_id=jmeter_jtl_csv-${"a".repeat(
                    64,
                )} analysis_id=${"b".repeat(64)} comparability=UNCONFIRMED scope=steady_window\n",
            ),
            text,
        )
        assertTrue(text.contains("response_time_p95_ms: 194 -> 388, delta 194 (100 %) описательно"), text)
        assertEquals(comparison, summary["baseline_comparison"])
    }

    @Test
    fun `without a comparison every artifact is as before`() {
        val html = renderHtmlReport(result, "fixed", null).decodeToString()
        val ascii = renderAsciiDocReport(result, "fixed", null).decodeToString()
        val confluence = renderConfluenceReport(result, "fixed", null).decodeToString()

        assertEquals(html, renderHtmlReport(result, "fixed", null, null).decodeToString())
        assertEquals(ascii, renderAsciiDocReport(result, "fixed", null, null).decodeToString())
        assertEquals(confluence, renderConfluenceReport(result, "fixed", null, null).decodeToString())
        listOf(html, ascii, confluence).forEach { assertFalse(it.contains("относительно baseline"), it) }
        assertEquals(summaryText("fixed", 0, result).decodeToString(), summaryText("fixed", 0, result, null, null).decodeToString())
        assertFalse(summaryText("fixed", 0, result).decodeToString().contains("baseline"))
        assertNull(Json.parseToJsonElement(summaryJson("fixed", result).decodeToString()).jsonObject["baseline_comparison"])
    }
}

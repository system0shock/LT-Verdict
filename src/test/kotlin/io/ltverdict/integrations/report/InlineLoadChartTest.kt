package io.ltverdict.integrations.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** W2.6 PR 2: the load chart embedded in the HTML report: no style element, Russian words, at most 240 points per panel. */
class InlineLoadChartTest {
    @TempDir
    lateinit var tempDir: Path

    private fun chart(
        rows: String,
        name: String = "rollup-60s.ndjson",
    ): InlineLoadChart? {
        val file = tempDir.resolve("${System.nanoTime()}-$name")
        Files.writeString(file, rows)
        return renderInlineLoadChart(file)
    }

    private fun need(chart: InlineLoadChart?): InlineLoadChart = checkNotNull(chart) { "the chart is unavailable" }

    /** The number of points of the first polyline of each panel (requests, errors, p95), segments summed. */
    private fun pointCounts(svg: String): List<Int> =
        Regex("""<g aria-label="[^"]*">.*?</g>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(svg)
            .map { panel ->
                Regex("""<polyline [^>]*points="([^"]*)"""").findAll(panel.value).sumOf {
                    it.groupValues[1].split(' ').count { p ->
                        p.isNotEmpty()
                    }
                }
            }.toList()

    @Test
    fun `is an accessible markup without style script or any link`() {
        val chart = need(chart(InlineChartRows.rows(count = 5)))
        val svg = chart.svg
        assertTrue(svg.startsWith("<svg "), svg.take(40))
        assertTrue(svg.contains("role=\"img\""))
        assertTrue(svg.contains("class=\"load-chart\""))
        assertTrue(svg.contains("<title id=\"load-chart-title\">"))
        assertTrue(svg.contains("<desc id=\"load-chart-description\">"))
        listOf("<script", "<style", "href", "src=", "url(", "<image", "<foreignObject", "javascript:").forEach {
            assertFalse(svg.contains(it, ignoreCase = true), it)
        }
        assertFalse(Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(svg))
        // Only the one namespace declaration is a link.
        assertEquals(1, "http".toRegex().findAll(svg).count())
        assertTrue(svg.contains("Запросов в секунду"))
        assertTrue(svg.contains("Время от начала прогона"))
        assertFalse(svg.contains("Requests per second"))
        // The rules the page needs are scoped, so they cannot touch any other element of the report.
        assertTrue(chart.css.contains("svg.load-chart text{"))
        assertTrue(chart.css.contains("svg.load-chart .series-rps{stroke:#1769aa}"))
        assertFalse(chart.css.contains("<"))
    }

    @Test
    fun `a short run keeps every minute`() {
        val svg = need(chart(InlineChartRows.rows(count = 240))).svg
        assertTrue(svg.contains("data-bins=\"240\""))
        assertTrue(svg.contains("data-group-bins=\"1\""))
        assertEquals(listOf(240, 240, 240), pointCounts(svg))
        assertFalse(svg.contains("Точка ="))
    }

    @Test
    fun `the number of points is at most 240 and the group size follows the inclusive span`() {
        // span in minute bins -> (group size k, groups)
        mapOf(240 to (1 to 240), 241 to (2 to 121), 480 to (2 to 240), 481 to (3 to 161), 5000 to (21 to 239)).forEach { (bins, expected) ->
            val svg = need(chart(InlineChartRows.rows(count = bins))).svg
            assertTrue(
                svg.contains("data-group-bins=\"${expected.first}\""),
                "$bins bins: ${svg.substringAfter("data-group-bins=").take(6)}",
            )
            assertTrue(svg.contains("data-bins=\"${expected.second}\""), "$bins bins")
            assertEquals(listOf(expected.second, expected.second, expected.second), pointCounts(svg), "$bins bins")
        }
    }

    @Test
    fun `a group sums requests and errors and keeps the largest p95 and the same input gives the same bytes`() {
        val rows =
            InlineChartRows.rows(
                count = 300,
                samples = { 60L },
                errors = { if (it == 1) 7L else 0L },
                p95 = { if (it == 1) 900L else 100L },
            )
        val first = need(chart(rows)).svg
        assertEquals(first, need(chart(rows)).svg)
        // k = 2: the first group holds minutes 0 and 1: 120 requests over 120 seconds is 1 RPS, the errors 7, p95 the maximum 900 ms.
        assertTrue(first.contains("data-group-bins=\"2\""))
        assertTrue(first.contains("макс. 900 мс"), first.substringAfter("p95").take(200))
        assertTrue(first.contains("макс. 7 шт."))
        assertTrue(first.contains("максимум p95 минуты в группе"))
        assertTrue(first.contains("Точка = 2 мин"))
    }

    @Test
    fun `a gap is not interpolated and a sparse start and end keep the scale`() {
        // minutes 0 and 1, then 10 and 11: two segments per panel, no line over the hole.
        val rows = InlineChartRows.rows(count = 2, firstStartMs = 0) + InlineChartRows.rows(count = 2, firstStartMs = 600_000)
        val svg = need(chart(rows)).svg
        assertEquals(6, "<polyline ".toRegex().findAll(svg).count())
        assertTrue(svg.contains("data-group-bins=\"1\""))
        // A single bin at each end of a long, empty stretch is still two points on a scale that covers the stretch.
        val sparse = InlineChartRows.rows(count = 1, firstStartMs = 0) + InlineChartRows.rows(count = 1, firstStartMs = 6_000 * 60_000L)
        val sparseSvg = need(chart(sparse)).svg
        assertTrue(sparseSvg.contains("data-group-bins=\"26\""))
        assertEquals(2, pointCounts(sparseSvg)[0])
    }

    @Test
    fun `more rows than the budget are cut with a note`() {
        val svg = need(chart(InlineChartRows.rows(count = 10_081))).svg
        assertTrue(svg.contains("data-truncated=\"true\""))
        assertTrue(svg.contains("Показаны первые 10"))
        assertTrue(svg.contains("data-source-bins=\"10080\""))
    }

    @Test
    fun `rows that are not a chart give no chart`() {
        assertNull(renderInlineLoadChart(tempDir.resolve("missing.ndjson")))
        assertNull(chart("{}\n"))
        assertNull(chart("x".repeat(524_289)))
        assertNull(chart(""))
        // Repeated starts.
        assertNull(chart(InlineChartRows.rows(count = 1) + InlineChartRows.rows(count = 1)))
    }
}

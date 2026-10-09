package io.ltverdict.report

import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.readRunTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/**
 * W2.6 PR 2: the HTML report with the run block and the inline chart. The first test pins the bytes of the report WITHOUT the block,
 * so the proof of "nothing else moved" does not depend on the golden files, which a report change regenerates on purpose. The hashes were
 * taken again in PR 3 and PR 4 (Russian headings, appendix); that the data did not move is proved by HumanReportAppendixTest.
 */
class RunTimelineReportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `report without a timeline keeps the bytes captured before the change`() {
        val expected =
            mapOf(
                "ramp-pass" to "fa89a4d8cef8ac3b726abc76eb41a5a922df606d08949ad66f41a7b21600ed2e",
                "ramp-fail" to "da721af5fc6c4f2c37834170cc04a6bc5d5d0c48510a4a37f450684de650cbb9",
                "csv-no-policy" to "02fca70916fb21193e4169d418de97a58dc1808464ca3151f55d142f69408370",
            )
        val inputs =
            mapOf(
                "ramp-pass" to Pair(StagedResults.RAMP, StagedResults.policy(StagedResults.p95(100_000))),
                "ramp-fail" to Pair(StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250))),
                "csv-no-policy" to Pair(Files.readAllBytes(Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")), null),
            )
        val actual =
            inputs.mapValues { (name, input) ->
                val outcome = StagedResults.analyze(tempDir.resolve(name), input.first, input.second)
                sha256(renderHtmlReport(outcome.canonicalResult, "fixed", readErrorGroupsFile(outcome.analysisDirectory)))
            }
        assertEquals(expected, actual)
    }

    private val started = Instant.parse("2026-01-01T10:00:00Z")

    private fun report(timeline: RunTimeline?): String {
        val outcome = StagedResults.analyze(tempDir.resolve("a${System.nanoTime()}"), StagedResults.RAMP)
        return renderHtmlReport(outcome.canonicalResult, "fixed", null, null, timeline).decodeToString()
    }

    private fun full(
        chartSvg: String? = "<svg class=\"load-chart\"><title>t</title></svg>",
        chartCss: String? = "svg.load-chart{display:block}",
    ) = RunTimeline(started, started.plusSeconds(120), 40, started.plusSeconds(1), chartSvg, chartCss)

    @Test
    fun `duration is rounded to a tenth of a second and leads with the first non zero unit`() {
        mapOf(
            0L to "0 с",
            400L to "0,4 с",
            999L to "1 с",
            1_000L to "1 с",
            59_960L to "1 мин 0 с",
            119_800L to "1 мин 59,8 с",
            120_000L to "2 мин 0 с",
            3_723_400L to "1 ч 2 мин 3,4 с",
            3_600_000L to "1 ч 0 мин 0 с",
            90_061_000L to "25 ч 1 мин 1 с",
        ).forEach { (millis, text) -> assertEquals(text, formatRunDuration(millis), "$millis ms") }
        assertTrue(formatRunDuration(Long.MAX_VALUE).first().isDigit())
        assertFalse(formatRunDuration(Long.MAX_VALUE).contains('-'))
    }

    @Test
    fun `the run block shows the times in UTC, the duration, the peak and the chart`() {
        val html = report(full())
        val block = html.substringAfter("<section><h2>Прогон</h2>").substringBefore("</section>")
        assertTrue(block.contains("<dt>Начало</dt><dd>2026-01-01 10:00:00 UTC</dd>"), block)
        assertTrue(block.contains("<dt>Конец</dt><dd>2026-01-01 10:02:00 UTC</dd>"), block)
        assertTrue(block.contains("<dt>Длительность</dt><dd>2 мин 0 с</dd>"), block)
        assertTrue(block.contains("<dt>Пиковый RPS, весь прогон</dt><dd>40 запросов/с, 2026-01-01 10:00:01 UTC</dd>"), block)
        assertTrue(block.contains("по времени начала"), block)
        assertTrue(block.contains("<figure><svg class=\"load-chart\">"), block)
        // The block sits with the verdict material, before the technical metrics.
        assertTrue(html.indexOf("<h2>Прогон</h2>") < html.indexOf("Общие метрики и метрики транзакций"))
    }

    @Test
    fun `the page style carries the chart rules only when a chart is shown and the policy hash covers them`() {
        fun styleOf(html: String): Pair<String, String> =
            html.substringAfter("<style>").substringBefore("</style>") to
                html.substringAfter("style-src 'sha256-").substringBefore("'")

        fun hash(text: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()))

        val withChart = styleOf(report(full()))
        assertTrue(withChart.first.endsWith("svg.load-chart{display:block}"))
        assertEquals(hash(withChart.first), withChart.second)
        val without = styleOf(report(full(chartSvg = null, chartCss = null)))
        assertFalse(without.first.contains("load-chart"))
        assertEquals(hash(without.first), without.second)
        assertEquals(styleOf(report(null)), without)
    }

    @Test
    fun `parts without data say so and a block without any data is not shown`() {
        val noChart = report(full(chartSvg = null, chartCss = null))
        assertTrue(noChart.contains("<p>График нагрузки недоступен.</p>"))
        assertFalse(noChart.contains("<figure>"))

        val onlyPeak = report(RunTimeline(null, null, 7, null, null, null))
        assertTrue(onlyPeak.contains("<dt>Начало</dt><dd>нет данных</dd>"))
        assertTrue(onlyPeak.contains("<dt>Длительность</dt><dd>нет данных</dd>"))
        assertTrue(onlyPeak.contains("<dt>Пиковый RPS, весь прогон</dt><dd>7 запросов/с</dd>"))

        val noPeak = report(RunTimeline(started, started, null, null, null, null))
        assertTrue(noPeak.contains("<dt>Длительность</dt><dd>0 с</dd>"))
        assertTrue(noPeak.contains("<dt>Пиковый RPS, весь прогон</dt><dd>нет данных</dd>"))

        assertFalse(report(RunTimeline(null, null, null, null, null, null)).contains("<h2>Прогон</h2>"))
        assertFalse(report(null).contains("<h2>Прогон</h2>"))
    }

    @Test
    fun `thousands in the peak are separated like the other numbers of the report`() {
        val html = report(RunTimeline(started, started.plusSeconds(1), 12_345, null, null, null))
        assertTrue(html.contains("12 345 запросов/с"))
    }

    @Test
    fun `a real analysis shows what its own files say`() {
        val outcome = StagedResults.analyze(tempDir.resolve("real"), StagedResults.RAMP)
        val timeline = checkNotNull(readRunTimeline(outcome.analysisDirectory))
        val html = renderHtmlReport(outcome.canonicalResult, "fixed", null, null, timeline).decodeToString()
        val peak = checkNotNull(timeline.peakRps)
        assertTrue(html.contains("запросов/с"), html.substringAfter("<h2>Прогон</h2>").take(600))
        assertTrue(html.contains("<svg "))
        assertTrue(
            html.contains(
                java.text.NumberFormat
                    .getIntegerInstance(java.util.Locale.ROOT)
                    .format(peak)
                    .replace(',', ' ') + " запросов/с",
            ),
        )
        assertTrue(html.contains(timeline.startedAt.toString().substring(0, 10)))
        // Nothing but the block and the style moved.
        val plain = renderHtmlReport(outcome.canonicalResult, "fixed", null).decodeToString()
        assertEquals(
            plain.substringAfter("<main>"),
            html.substringAfter("<main>").replace(Regex("<section><h2>Прогон</h2>.*?</section>", RegexOption.DOT_MATCHES_ALL), ""),
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

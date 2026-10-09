package io.ltverdict.integrations.report

import io.ltverdict.core.StagedResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** W2.6 PR 2: the reader of the facts the HTML report shows about the run (time, duration, the busiest second, the chart). */
class RunTimelineReaderTest {
    @TempDir
    lateinit var tempDir: Path

    private val started = Instant.parse("2026-01-01T10:00:00Z")

    @Test
    fun `reads the UTC times and the busiest second of the whole run`() {
        val dir = directory(secondRows = listOf(0L to 5L, 1_000L to 40L, 2_000L to 12L, 7_000L to 40L))
        val timeline = need(readRunTimeline(dir))
        assertEquals(started, timeline.startedAt)
        assertEquals(Instant.parse("2026-01-01T10:02:00Z"), timeline.endedAt)
        assertEquals(40L, timeline.peakRps)
        // A tie goes to the earliest second.
        assertEquals(started.plusMillis(1_000), timeline.peakAt)
    }

    @Test
    fun `without run json the peak stays and the time is unknown`() {
        val dir = directory(secondRows = listOf(0L to 3L, 1_000L to 9L))
        Files.delete(dir.resolve("run.json"))
        val timeline = need(readRunTimeline(dir))
        assertNull(timeline.startedAt)
        assertNull(timeline.endedAt)
        assertNull(timeline.peakAt)
        assertEquals(9L, timeline.peakRps)
    }

    @Test
    fun `an end before the start is no time at all`() {
        val dir = directory(secondRows = listOf(0L to 3L))
        Files.writeString(dir.resolve("run.json"), """{"started_at":"2026-01-01T10:00:00Z","ended_at":"2026-01-01T09:00:00Z"}""")
        val timeline = need(readRunTimeline(dir))
        assertNull(timeline.startedAt)
        assertNull(timeline.endedAt)
        assertEquals(3L, timeline.peakRps)
    }

    @Test
    fun `a zero duration run is a valid run`() {
        val dir = directory(secondRows = listOf(0L to 1L))
        Files.writeString(dir.resolve("run.json"), """{"started_at":"2026-01-01T10:00:00Z","ended_at":"2026-01-01T10:00:00Z"}""")
        val timeline = need(readRunTimeline(dir))
        assertEquals(timeline.startedAt, timeline.endedAt)
    }

    @Test
    fun `rows that are not seconds of a run give no peak`() {
        val bad =
            listOf(
                "unordered" to listOf(1_000L to 1L, 0L to 2L),
                "duplicate" to listOf(0L to 1L, 0L to 2L),
                "not a second" to listOf(0L to 1L, 1_500L to 2L),
                "negative offset" to listOf(-1_000L to 1L),
                "negative count" to listOf(0L to -5L),
            )
        bad.forEach { (name, rows) ->
            val timeline = need(readRunTimeline(directory(secondRows = rows)), name)
            assertNull(timeline.peakRps, name)
            assertEquals(started, timeline.startedAt, name)
        }
        listOf(
            "not json",
            """{"bucket_start_ms":"x","sample_count":1}""",
            """{"bucket_start_ms":0,"sample_count":1.5}""",
            "[]",
        ).forEach { line ->
            val dir = directory(secondRows = emptyList())
            Files.writeString(dir.resolve("normalized-1s.ndjson"), line + "\n")
            assertNull(need(readRunTimeline(dir), line).peakRps, line)
        }
    }

    @Test
    fun `a line over the limit gives no peak and does not stop the other parts`() {
        val dir = directory(secondRows = listOf(0L to 1L))
        Files.writeString(dir.resolve("normalized-1s.ndjson"), "x".repeat(524_289) + "\n")
        val timeline = need(readRunTimeline(dir))
        assertNull(timeline.peakRps)
        need(timeline.chartSvg)
    }

    @Test
    fun `broken minute rows give no chart and keep the numbers`() {
        val dir = directory(secondRows = listOf(0L to 7L))
        Files.writeString(dir.resolve("rollup-60s.ndjson"), "{}\n")
        val timeline = need(readRunTimeline(dir))
        assertNull(timeline.chartSvg)
        assertNull(timeline.chartCss)
        assertEquals(7L, timeline.peakRps)
    }

    @Test
    fun `a directory with none of the files has no timeline`() {
        assertNull(readRunTimeline(Files.createDirectory(tempDir.resolve("empty"))))
    }

    @Test
    fun `a link in place of a file is not followed`() {
        val dir = directory(secondRows = listOf(0L to 7L))
        val target = tempDir.resolve("elsewhere.ndjson")
        Files.move(dir.resolve("normalized-1s.ndjson"), target)
        val linked =
            try {
                Files.createSymbolicLink(dir.resolve("normalized-1s.ndjson"), target)
                true
            } catch (_: Exception) {
                false
            }
        assumeTrue(linked, "symbolic links are not available here")
        assertNull(need(readRunTimeline(dir)).peakRps)
    }

    @Test
    fun `reading touches nothing in the analysis directory and the real analysis agrees with its own buckets`() {
        val outcome = StagedResults.analyze(tempDir.resolve("real"), StagedResults.RAMP)
        val dir = outcome.analysisDirectory
        val before = snapshot(dir)
        val timeline = need(readRunTimeline(dir))
        assertEquals(before, snapshot(dir))

        val run = Json.parseToJsonElement(Files.readString(dir.resolve("run.json"))).jsonObject
        assertEquals(Instant.parse(run.getValue("started_at").jsonPrimitive.content), timeline.startedAt)
        assertEquals(Instant.parse(run.getValue("ended_at").jsonPrimitive.content), timeline.endedAt)
        val counts =
            Files.readAllLines(dir.resolve("normalized-1s.ndjson")).map {
                Json
                    .parseToJsonElement(it)
                    .jsonObject
                    .getValue("sample_count")
                    .jsonPrimitive.content
                    .toLong()
            }
        assertEquals(counts.max(), timeline.peakRps)
        assertTrue(counts.sum() > 0)
        val svg = need(timeline.chartSvg)
        assertTrue(svg.startsWith("<svg "))
    }

    private fun <T : Any> need(
        value: T?,
        name: String = "",
    ): T = checkNotNull(value) { name }

    private fun snapshot(dir: Path): Map<String, List<Byte>> =
        Files.list(dir).use { files ->
            files.filter { Files.isRegularFile(it) }.toList().associate { it.fileName.toString() to Files.readAllBytes(it).toList() }
        }

    /** An analysis directory with the three files the reader looks at; [secondRows] are (offset ms, samples). */
    private fun directory(secondRows: List<Pair<Long, Long>>): Path {
        val dir = Files.createDirectory(tempDir.resolve("a${System.nanoTime()}"))
        Files.writeString(dir.resolve("run.json"), """{"started_at":"$started","ended_at":"2026-01-01T10:02:00Z"}""")
        Files.writeString(
            dir.resolve("normalized-1s.ndjson"),
            secondRows.joinToString("") { (offset, count) ->
                """{"bucket_start_ms":$offset,"error_count":0,"hdr_v2_base64":"AAAA","max_latency_ms":1,"sample_count":$count}""" + "\n"
            },
        )
        Files.writeString(dir.resolve("rollup-60s.ndjson"), InlineChartRows.rows(count = 3, firstStartMs = 0))
        return dir
    }
}

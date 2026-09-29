package io.ltverdict.integrations.report

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.HdrHistogram.PackedHistogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class StaticLoadChartTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `renders relative shared timeline and preserves missing bins as gaps`() {
        val start = 1_767_225_600_000L
        val buckets =
            listOf(
                bucket(start, samples = 60, errors = 1, p95Millis = 100),
                bucket(start + 60_000, samples = 120, errors = 2, p95Millis = 10_000),
                bucket(start + 180_000, samples = 30, errors = 0, p95Millis = 50),
            )

        val svg = renderLoadChart(buckets, rollup = 60).decodeToString()

        assertTrue(svg.startsWith("<svg "))
        assertTrue(svg.contains(">LT Verdict load chart</title>"))
        assertTrue(svg.contains("data-bins=\"3\""))
        assertTrue(svg.contains("data-rollup-seconds=\"60\""))
        assertTrue(svg.contains("data-truncated=\"false\""))
        assertTrue(svg.contains("Requests per second"))
        assertTrue(svg.contains("Errors per bin"))
        assertTrue(svg.contains("P95 latency"))
        assertTrue(svg.contains("Time from run start"))
        assertEquals(6, "<polyline ".toRegex().findAll(svg).count())
        assertTrue(!svg.contains(start.toString()))
    }

    @Test
    fun `saved chart reads only the bounded prefix and reports truncation`() {
        val path = temporaryDirectory.resolve("rollup-60s.ndjson")
        Files.writeString(
            path,
            buildString {
                repeat(500) { index ->
                    append(bucket(index * 60_000L, samples = 60, errors = 0, p95Millis = 10)).append('\n')
                }
                append("not parsed\n")
            },
        )

        val svg = renderSavedLoadChart(path).decodeToString()

        assertTrue(svg.contains("data-bins=\"500\""))
        assertTrue(svg.contains("data-truncated=\"true\""))
        assertTrue(svg.contains("First 500 bins shown"))
    }

    @Test
    fun `missing data is honest and malformed persisted rows fail with a controlled code`() {
        val unavailable = renderSavedLoadChart(temporaryDirectory.resolve("missing.ndjson")).decodeToString()
        assertTrue(unavailable.contains("Load chart unavailable"))
        assertTrue(unavailable.contains("No saved load buckets"))

        val malformed = temporaryDirectory.resolve("rollup-60s.ndjson")
        Files.writeString(malformed, "{}\n")
        val failure = assertThrows(IllegalArgumentException::class.java) { renderSavedLoadChart(malformed) }
        assertEquals("SAVED_BUCKETS_INVALID", failure.message)

        Files.writeString(malformed, "x".repeat(524_289))
        val oversized = assertThrows(IllegalArgumentException::class.java) { renderSavedLoadChart(malformed) }
        assertEquals("SAVED_BUCKETS_INVALID", oversized.message)
    }

    private fun bucket(
        startMillis: Long,
        samples: Long,
        errors: Long,
        p95Millis: Long,
    ) = buildJsonObject {
        put("bucket_start_ms", startMillis)
        put("sample_count", samples)
        put("error_count", errors)
        put("max_latency_ms", p95Millis)
        put("hdr_v2_base64", histogram(p95Millis, samples))
    }

    private fun histogram(
        value: Long,
        count: Long,
    ): String {
        val histogram = PackedHistogram(1, 86_400_000, 3)
        histogram.recordValueWithCount(value, count)
        val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
        val length = histogram.encodeIntoCompressedByteBuffer(buffer)
        return Base64.getEncoder().encodeToString(buffer.array().copyOf(length))
    }
}

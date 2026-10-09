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

    @Test
    fun `chart p95 is capped at the stored maximum for every histogram precision`() {
        val start = 1_767_225_600_000L
        val max = 262_144L
        listOf(3, 4, 5).forEach { digits ->
            val raw = PackedHistogram(1, 86_400_000, digits).apply { recordValueWithCount(max, 100) }.getValueAtPercentile(95.0)
            val svg = renderLoadChart(listOf(bucket(start, samples = 100, errors = 0, p95Millis = max, digits = digits)), rollup = 60)

            val text = svg.decodeToString()
            val p95Panel = text.substringAfter("<g aria-label=\"P95 latency\">")
            val match = requireNotNull(Regex("max ([0-9]+) ms").find(p95Panel)) { "digits $digits" }
            val shown = match.groupValues[1].toLong()
            assertTrue(raw > max, "digits $digits")
            assertEquals(minOf(raw, max), shown, "digits $digits")
        }
    }

    @Test
    fun `five digit histograms near the modelled worst case stay within the row and histogram limits`() {
        val random = java.util.Random(16)
        val values = LongArray(1_000_000) { 1L + random.nextInt(86_000_000) }
        val histogram = PackedHistogram(1, 86_400_000, 5).apply { values.forEach { recordValue(it) } }
        val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
        val encoded = Base64.getEncoder().encodeToString(buffer.array().copyOf(histogram.encodeIntoCompressedByteBuffer(buffer)))
        // Measured 289 552 characters (ADR 0016 model: 289 648), above the former 262 144 character limit.
        assertTrue(encoded.length > 262_144, "encoded length ${encoded.length}")
        val row =
            buildJsonObject {
                put("bucket_start_ms", 1_767_225_600_000L)
                put("sample_count", values.size.toLong())
                put("error_count", 0L)
                put("max_latency_ms", values.max())
                put("hdr_v2_base64", encoded)
            }
        val path = temporaryDirectory.resolve("rollup-60s.ndjson")
        Files.writeString(path, row.toString() + "\n")

        val svg = renderSavedLoadChart(path).decodeToString()

        assertTrue(svg.contains("data-bins=\"1\""))
    }

    @Test
    fun `histograms above the raised character limit are rejected`() {
        val row =
            buildJsonObject {
                put("bucket_start_ms", 0L)
                put("sample_count", 1L)
                put("error_count", 0L)
                put("max_latency_ms", 1L)
                put("hdr_v2_base64", "A".repeat(393_217))
            }
        val failure = assertThrows(IllegalArgumentException::class.java) { renderLoadChart(listOf(row), rollup = 60) }
        assertEquals("SAVED_BUCKETS_INVALID", failure.message)
    }

    @Test
    fun `standalone chart bytes are frozen`() {
        val start = 1_767_225_600_000L
        val gapped =
            listOf(
                bucket(start, samples = 60, errors = 1, p95Millis = 100),
                bucket(start + 60_000, samples = 120, errors = 2, p95Millis = 10_000),
                bucket(start + 180_000, samples = 30, errors = 0, p95Millis = 50),
            )
        val saved = temporaryDirectory.resolve("rollup-60s.ndjson")
        Files.writeString(
            saved,
            buildString {
                repeat(501) { index ->
                    append(bucket(index * 60_000L, samples = 60L + index, errors = index % 3L, p95Millis = 10L + index)).append('\n')
                }
            },
        )
        val actual =
            mapOf(
                "gapped" to renderLoadChart(gapped, rollup = 60),
                "single" to renderLoadChart(listOf(bucket(start, samples = 5, errors = 0, p95Millis = 7)), rollup = 10),
                "empty" to renderLoadChart(emptyList(), rollup = 60),
                "missing" to renderSavedLoadChart(temporaryDirectory.resolve("missing.ndjson")),
                "truncated" to renderSavedLoadChart(saved),
            ).mapValues { (_, bytes) -> sha256(bytes) }
        assertEquals(FROZEN, actual)
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun bucket(
        startMillis: Long,
        samples: Long,
        errors: Long,
        p95Millis: Long,
        digits: Int = 3,
    ) = buildJsonObject {
        put("bucket_start_ms", startMillis)
        put("sample_count", samples)
        put("error_count", errors)
        put("max_latency_ms", p95Millis)
        put("hdr_v2_base64", histogram(p95Millis, samples, digits))
    }

    private fun histogram(
        value: Long,
        count: Long,
        digits: Int = 3,
    ): String {
        val histogram = PackedHistogram(1, 86_400_000, digits)
        histogram.recordValueWithCount(value, count)
        val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
        val length = histogram.encodeIntoCompressedByteBuffer(buffer)
        return Base64.getEncoder().encodeToString(buffer.array().copyOf(length))
    }

    private companion object {
        val FROZEN: Map<String, String> =
            mapOf(
                "gapped" to "eead4372c22932261fe3de13eed750b3e146f339e295b02d1e57b930e5fb0c37",
                "single" to "b663c7d01c94bf47ba6a79339917f8c3467690f73363db943db8b342d771ad16",
                "empty" to "9589389c53a8bf9761ee504b9057541223531162bb6d6ba948198a61a1c7a33f",
                "missing" to "9589389c53a8bf9761ee504b9057541223531162bb6d6ba948198a61a1c7a33f",
                "truncated" to "af3c817cd6d059875f3421728c159e02588c9c62e0f4907c500106424d1ce791",
            )
    }
}

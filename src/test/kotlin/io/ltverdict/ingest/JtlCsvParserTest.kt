package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JtlCsvParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `quoted UTF-8 comma and newline labels are exact for LF and CRLF`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            val label = "Привет, \"мир\"${lineEnding}вторая строка"
            val file =
                csv(
                    "timeStamp,elapsed,label,success",
                    "1,2,\"${label.replace("\"", "\"\"")}\",true",
                    lineEnding = lineEnding,
                )
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(listOf(label), samples.map { it.label })
        }
    }

    @Test
    fun `optional URL variants remain flat JMeter samplers`() {
        val variants =
            listOf(
                "timeStamp,elapsed,label,success" to "1,2,no-url,true",
                "timeStamp,elapsed,label,success,URL" to "1,2,empty-url,true,",
                "timeStamp,elapsed,label,success,URL" to "1,2,with-url,true,https://example.invalid/a",
            )

        variants.forEach { (header, row) ->
            val samples = mutableListOf<LoadSample>()
            val report = parseJtlCsv(csv(header, row), samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(SampleKind.JMETER_SAMPLER, samples.single().kind)
            assertEquals(emptyList<String>(), samples.single().groupPath)
        }
    }

    @Test
    fun `missing or duplicate required headers are invalid with diagnostics`() {
        assertInvalid(csv("timeStamp,elapsed,label", "1,2,label"))
        assertInvalid(csv("timeStamp,elapsed,label,label,success", "1,2,a,b,true"))
    }

    @Test
    fun `malformed quote number and boolean are invalid with diagnostics`() {
        listOf(
            "1,2,\"unterminated,true",
            "not-a-number,2,label,true",
            "1,not-a-number,label,true",
            "1,2,label,yes",
        ).forEach { row -> assertInvalid(csv("timeStamp,elapsed,label,success", row)) }
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,ok,true", "", "3,4,also-ok,true"))
    }

    @Test
    fun `CSV resource limits are invalid with diagnostics`() {
        val header = (listOf("timeStamp", "elapsed", "label", "success") + List(61) { "extra$it" }).joinToString(",")
        val row = (listOf("1", "2", "label", "true") + List(61) { "x" }).joinToString(",")
        assertInvalid(csv(header, row))
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,${"a".repeat(65_537)},true"))
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,${"я".repeat(2_049)},true"))
    }

    @Test
    fun `physical text line over 1 MiB is a resource limit`() {
        val header = (listOf("timeStamp", "elapsed", "label", "success") + List(16) { "extra$it" }).joinToString(",")
        val row =
            (
                listOf("1", "2", "label", "true") +
                    List(15) { "x".repeat(65_535) } +
                    "x".repeat(65_522)
            ).joinToString(",")
        assertEquals(1_048_577, row.encodeToByteArray().size)

        val report = parseJtlCsv(csv(header, row), {})

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), report.diagnostics.map { it.code })
    }

    @Test
    fun `successful parse reports monotonic progress ending at file size`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", "3,4,two,false")
        val progress = mutableListOf<Long>()

        val report = parseJtlCsv(file, {}, progress::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(Files.size(file), progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous <= next })
    }

    @Test
    fun `cancellation exception propagates`() {
        val cancelled = IllegalStateException("cancelled")

        val error =
            assertThrows(IllegalStateException::class.java) {
                parseJtlCsv(csv("timeStamp,elapsed,label,success", "1,2,label,true"), {}, checkCancelled = { throw cancelled })
            }

        assertSame(cancelled, error)
    }

    private fun assertInvalid(file: Path) {
        val report = parseJtlCsv(file, {})
        assertEquals(RunValidity.INVALID, report.validity)
        assertTrue(report.diagnostics.isNotEmpty())
    }

    private fun csv(
        header: String,
        vararg rows: String,
        lineEnding: String = "\n",
    ): Path {
        val file = tempDir.resolve("input-${System.nanoTime()}.jtl")
        Files.writeString(file, (listOf(header) + rows).joinToString(lineEnding, postfix = lineEnding))
        return file
    }
}

package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FormatDetectorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `content identifies every supported producer despite misleading names`() {
        val cases =
            listOf(
                "fixtures/slice1/jmeter/csv-5.6.3/input.jtl" to SourceType.JMETER_CSV,
                "fixtures/slice1/security/html-label.jtl" to SourceType.JMETER_CSV,
                "fixtures/slice1/jmeter/xml-5.6.3/input.xml" to SourceType.JMETER_XML,
                "fixtures/slice1/gatling/text-3.9.5/simulation.log" to SourceType.GATLING_TEXT,
                "fixtures/slice1/gatling/text-3.12.0/simulation.log" to SourceType.GATLING_TEXT,
                "fixtures/slice1/gatling/binary-3.13.5/simulation.log" to SourceType.GATLING_BINARY,
                "fixtures/slice1/gatling/binary-3.15.1/simulation.log" to SourceType.GATLING_BINARY,
            )

        cases.forEachIndexed { index, (fixture, expected) ->
            val disguised = tempDir.resolve("disguised-$index.txt")
            Files.copy(Path.of(fixture), disguised)
            assertEquals(expected, detectSource(disguised), fixture)
        }
    }

    @Test
    fun `empty unknown and near signatures fail closed`() {
        val invalid =
            listOf(
                byteArrayOf(),
                "not a load-test log".encodeToByteArray(),
                "prefix\nRUN\tlate".encodeToByteArray(),
                "timeStamp,elapsed,label,extra\n".encodeToByteArray(),
                byteArrayOf(0, 0, 0, 0, 0, 6) + "9.99.9".encodeToByteArray(),
                byteArrayOf(0, 0, 0, 0, 6) + "3.15.1".encodeToByteArray(),
                "<?xml version=\"1.0\"?><root><!-- <testResults> --></root>".encodeToByteArray(),
            )

        invalid.forEachIndexed { index, bytes ->
            val path = tempDir.resolve("invalid-$index.bin")
            Files.write(path, bytes)
            val error = assertThrows(IllegalArgumentException::class.java) { detectSource(path) }
            assertEquals("UNSUPPORTED_INPUT", error.message)
        }
    }

    @Test
    fun `bounded prefix may end inside a later UTF-8 character`() {
        val firstLine = Files.readAllLines(Path.of("fixtures/slice1/gatling/text-3.9.5/simulation.log")).first().encodeToByteArray()
        val padding = ByteArray(4_095 - firstLine.size - 1) { 'x'.code.toByte() }
        val path = tempDir.resolve("utf8-boundary.bin")
        Files.write(path, firstLine + byteArrayOf('\n'.code.toByte()) + padding + byteArrayOf(0xc3.toByte(), 0xa9.toByte()))

        assertEquals(SourceType.GATLING_TEXT, detectSource(path))
    }
}

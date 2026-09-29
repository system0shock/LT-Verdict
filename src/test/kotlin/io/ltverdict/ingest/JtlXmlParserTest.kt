package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

class JtlXmlParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `nested JMeter samples preserve exact paths kinds and monotonic final progress`() {
        val path = Path.of("fixtures/slice1/jmeter/xml-5.6.3/input.xml")
        val samples = mutableListOf<LoadSample>()
        val progress = mutableListOf<Long>()

        val report = parseJtlXml(path, samples::add, progress::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(path), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
        assertEquals(
            setOf(
                Triple("Checkout scenario", SampleKind.JMETER_CONTAINER, emptyList<String>()),
                Triple("HTTP checkout", SampleKind.JMETER_CONTAINER, listOf("Checkout scenario")),
                Triple(
                    "http://127.0.0.1:18081/start",
                    SampleKind.JMETER_SAMPLER,
                    listOf("Checkout scenario", "HTTP checkout"),
                ),
                Triple(
                    "http://127.0.0.1:18081/final",
                    SampleKind.JMETER_SAMPLER,
                    listOf("Checkout scenario", "HTTP checkout"),
                ),
                Triple("POST /order", SampleKind.JMETER_SAMPLER, listOf("Checkout scenario")),
            ),
            samples.map { Triple(it.label, it.kind, it.groupPath) }.toSet(),
        )
    }

    @Test
    fun `malformed empty missing attributes and invalid limits fail closed`() {
        val missing =
            listOf(
                "<testResults><sample t=\"0\" lb=\"x\" s=\"true\"/></testResults>",
                "<testResults><sample ts=\"0\" lb=\"x\" s=\"true\"/></testResults>",
                "<testResults><sample ts=\"0\" t=\"0\" s=\"true\"/></testResults>",
                "<testResults><sample ts=\"0\" t=\"0\" lb=\"x\"/></testResults>",
            )
        val tooDeep =
            "<testResults>" + sample("x").repeat(65) + "</sample>".repeat(65) + "</testResults>"
        val oversizedLabel = "<testResults>${sample("x".repeat(4_097))}</testResults>"
        val timestampOverflow = "<testResults>${sample("x", ts = Long.MAX_VALUE.toString())}</testResults>"

        (listOf("", "<testResults>") + missing + listOf(tooDeep, oversizedLabel, timestampOverflow))
            .forEachIndexed { index, xml ->
                assertEquals(RunValidity.INVALID, parseJtlXml(write("invalid-$index.xml", xml), {}).validity)
            }
    }

    @Test
    fun `cancellation propagates and hostile XML fixtures are invalid`() {
        assertThrows(CancellationException::class.java) {
            parseJtlXml(
                write("cancel.xml", "<testResults>${sample("x")}</testResults>"),
                {},
                checkCancelled = { throw CancellationException() },
            )
        }

        listOf("dtd.xml", "xxe.xml", "entity-expansion.xml").forEach { fixture ->
            assertEquals(RunValidity.INVALID, parseJtlXml(Path.of("fixtures/slice1/security/$fixture"), {}).validity)
        }
    }

    @Test
    fun `response bodies headers and raw XML never reach emitted samples`() {
        val marker = "do-not-retain-me"
        val xml =
            "<testResults>${sample("visible")}" +
                "<responseData>$marker</responseData><responseHeaders>$marker</responseHeaders><raw>$marker</raw>" +
                "</sample></testResults>"
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlXml(write("response-data.xml", xml), samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertFalse(samples.any { marker in it.label || it.groupPath.any(marker::contains) })
    }

    private fun sample(
        label: String,
        ts: String = "0",
    ) = "<sample ts=\"$ts\" t=\"0\" lb=\"$label\" s=\"true\">"

    private fun write(
        name: String,
        content: String,
    ): Path = tempDir.resolve(name).also { Files.writeString(it, content) }
}

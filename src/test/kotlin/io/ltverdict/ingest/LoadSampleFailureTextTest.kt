package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

// W2.6: a failed sample keeps its response code and failure text; a successful one keeps neither.
class LoadSampleFailureTextTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `csv failed samples keep response code and failure message with response message as fallback`() {
        val samples = mutableListOf<LoadSample>()
        val report =
            parseJtlCsv(
                csv(
                    "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage",
                    "1000,5,ok,200,OK,true,",
                    "1010,5,http,503,Service Unavailable,false,",
                    "1020,5,assert,200,OK,false,\"Test failed: text expected, not found\"",
                ),
                samples::add,
            )

        assertEquals(RunValidity.VALID, report.validity)
        assertNull(samples[0].responseCode)
        assertNull(samples[0].failureMessage)
        assertEquals("503", samples[1].responseCode)
        assertEquals("Service Unavailable", samples[1].failureMessage)
        assertEquals("200", samples[2].responseCode)
        assertEquals("Test failed: text expected, not found", samples[2].failureMessage)
    }

    @Test
    fun `csv without the optional columns still parses and leaves both fields empty`() {
        val samples = mutableListOf<LoadSample>()

        parseJtlCsv(csv("timeStamp,elapsed,label,success", "1000,5,bad,false"), samples::add)

        assertNull(samples.single().responseCode)
        assertNull(samples.single().failureMessage)
    }

    @Test
    fun `xml failed samples keep rc and the assertion failure message of their own sample only`() {
        val xml =
            "<testResults>" +
                "<httpSample ts=\"1000\" t=\"5\" lb=\"ok\" s=\"true\" rc=\"200\" rm=\"OK\"/>" +
                "<httpSample ts=\"1010\" t=\"5\" lb=\"http\" s=\"false\" rc=\"500\" rm=\"Server Error\"/>" +
                "<httpSample ts=\"1020\" t=\"5\" lb=\"assert\" s=\"false\" rc=\"200\" rm=\"OK\">" +
                "<assertionResult><name>a</name><failure>true</failure><error>false</error>" +
                "<failureMessage>Expected &lt;b&gt; &amp; more<![CDATA[ cdata]]></failureMessage></assertionResult></httpSample>" +
                "</testResults>"
        val samples = mutableListOf<LoadSample>()

        parseJtlXml(write("failures.xml", xml), samples::add)

        assertNull(samples[0].responseCode)
        assertNull(samples[0].failureMessage)
        assertEquals("500", samples[1].responseCode)
        assertEquals("Server Error", samples[1].failureMessage)
        assertEquals("200", samples[2].responseCode)
        assertEquals("Expected <b> & more cdata", samples[2].failureMessage)
    }

    @Test
    fun `xml failure message text is bounded while it is read`() {
        val long = "x".repeat(100_000)
        val xml =
            "<testResults><httpSample ts=\"1000\" t=\"5\" lb=\"a\" s=\"false\" rc=\"200\" rm=\"OK\">" +
                "<assertionResult><failureMessage>$long</failureMessage></assertionResult></httpSample></testResults>"
        val samples = mutableListOf<LoadSample>()

        parseJtlXml(write("long.xml", xml), samples::add)

        assertEquals(4096, samples.single().failureMessage?.length)
    }

    @Test
    fun `gatling text keeps the message of a KO request and no response code`() {
        val lines =
            listOf(
                "RUN\tfixture.FixtureSimulation\tfixturesimulation\t1\t \t3.12.0",
                "REQUEST\tcheckout\tcatalog\t1\t2\tOK\t ",
                "REQUEST\tcheckout\tpay\t3\t5\tKO\tstatus.find.is(200), but actually found 503",
            )
        val file = tempDir.resolve("gatling.log").also { Files.writeString(it, lines.joinToString("\n", postfix = "\n")) }
        val samples = mutableListOf<LoadSample>()

        parseGatlingText(file, samples::add)

        assertNull(samples[0].failureMessage)
        assertNull(samples[1].responseCode)
        assertEquals("status.find.is(200), but actually found 503", samples[1].failureMessage)
    }

    private fun csv(vararg lines: String): Path = write("input-${System.nanoTime()}.jtl", lines.joinToString("\n", postfix = "\n"))

    private fun write(
        name: String,
        content: String,
    ): Path = tempDir.resolve(name).also { Files.writeString(it, content) }
}

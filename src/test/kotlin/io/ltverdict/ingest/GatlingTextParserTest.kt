package io.ltverdict.ingest

import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GatlingTextParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `official 3 9 5 and 3 12 0 records retain exact layouts and hierarchy`() {
        val fixtures =
            listOf(
                "fixtures/slice1/gatling/text-3.9.5/simulation.log" to "3.9.5",
                "fixtures/slice1/gatling/text-3.12.0/simulation.log" to "3.12.0",
            )

        fixtures.forEach { (fixture, version) ->
            val input = Path.of(fixture)
            val samples = mutableListOf<LoadSample>()

            assertFixtureUsesOfficialLayouts(input)
            val report = parseGatlingText(input, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(Files.size(input), report.processedBytes)
            assertEquals(
                setOf(
                    Triple("checkout", emptyList<String>(), SampleKind.GATLING_GROUP),
                    Triple("catalog", listOf("checkout"), SampleKind.GATLING_REQUEST),
                    Triple("missing", listOf("checkout"), SampleKind.GATLING_REQUEST),
                ),
                samples.map { Triple(it.label, it.groupPath, it.kind) }.toSet(),
                version,
            )
            assertEquals(4, samples.count { it.kind == SampleKind.GATLING_REQUEST }, version)
            assertEquals(1, samples.count { it.kind == SampleKind.GATLING_GROUP }, version)
        }
    }

    @Test
    fun `requests contribute overall metrics while groups contribute only their own transaction`() {
        val samples = mutableListOf<LoadSample>()
        val report = parseGatlingText(Path.of("fixtures/slice1/gatling/text-3.12.0/simulation.log"), samples::add)
        val metrics =
            MetricsAccumulator(
                samples.minOf(LoadSample::startedAtEpochMillis),
                samples.maxOf(LoadSample::endedAtEpochMillis),
                MetricsConfig(),
            ).also { accumulator -> samples.forEach(accumulator::record) }.finish()

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(4, metrics.overall.sampleCount)
        assertEquals(
            1,
            metrics.transactions
                .single { it.identity.kind == SampleKind.GATLING_GROUP }
                .metrics.sampleCount,
        )
        assertEquals(5, metrics.transactions.sumOf { it.metrics.sampleCount })
    }

    @Test
    fun `USER ERROR and ASSERTION records are accepted without emitting request metrics`() {
        val samples = mutableListOf<LoadSample>()
        val input =
            write(
                "other-records.log",
                listOf(
                    "ASSERTION\tAA==",
                    run(),
                    "USER\tcheckout\tSTART\t1",
                    "ERROR\tvisible only as a diagnostic\t2",
                    "USER\tcheckout\tEND\t3",
                    "REQUEST\tcheckout\tcatalog\t1\t2\tOK\t ",
                ),
            )

        val report = parseGatlingText(input, samples::add)

        assertEquals(SourceType.GATLING_TEXT, detectSource(input))
        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(listOf(SampleKind.GATLING_REQUEST), samples.map(LoadSample::kind))
    }

    @Test
    fun `unknown malformed and timestamp-invalid text records fail closed`() {
        listOf(
            listOf(run(), "FUTURE\tvalue"),
            listOf(run(), "REQUEST\tcheckout\tcatalog\t1\t2\tOK"),
            listOf(run(), "REQUEST\tcheckout\tcatalog\t2\t1\tOK\t "),
            listOf(run(), "REQUEST\tcheckout\tcatalog\t-1\t2\tOK\t "),
            listOf(run(), "REQUEST\tcheckout\tcatalog\t${MAX_TIMESTAMP_EPOCH_MILLIS}\t${MAX_TIMESTAMP_EPOCH_MILLIS + 1}\tOK\t "),
            listOf(run(), "GROUP\tcheckout\t1\t2\t2147483648\tOK"),
            listOf(run(version = "3.13.0"), "REQUEST\tcheckout\tcatalog\t1\t2\tOK\t "),
        ).forEachIndexed { index, lines -> assertInvalid(write("invalid-$index.log", lines)) }
    }

    @Test
    fun `text line field label and hierarchy depth limits fail closed`() {
        val tooDeep = List(65) { "group$it" }.joinToString(",")
        val oversizedLine = "ERROR\t${"x".repeat(1_048_574)}\t1"

        listOf(
            listOf(run(), "REQUEST\tcheckout\tcatalog\t1\t2\tOK\t${"x".repeat(65_537)}"),
            listOf(run(), "REQUEST\tcheckout\t${"я".repeat(2_049)}\t1\t2\tOK\t "),
            listOf(run(), "REQUEST\t$tooDeep\tcatalog\t1\t2\tOK\t "),
            listOf(run(), oversizedLine),
        ).forEachIndexed { index, lines -> assertInvalid(write("limit-$index.log", lines)) }
    }

    @Test
    fun `progress ends at the input size and cancellation escapes unchanged`() {
        val input = write("progress.log", listOf(run(), "REQUEST\tcheckout\tcatalog\t1\t2\tOK\t "))
        val progress = mutableListOf<Long>()
        val report = parseGatlingText(input, {}, progress::add)
        val cancelled = IllegalStateException("cancelled")

        val error =
            assertThrows(IllegalStateException::class.java) {
                parseGatlingText(input, {}, checkCancelled = { throw cancelled })
            }

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(input), report.processedBytes)
        assertEquals(Files.size(input), progress.last())
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
        assertSame(cancelled, error)
    }

    private fun assertFixtureUsesOfficialLayouts(input: Path) {
        Files.readAllLines(input).forEach { line ->
            val fields = line.split('\t')
            assertEquals(OFFICIAL_TEXT_LAYOUTS.getValue(fields.first()).size, fields.size, line)
        }
    }

    private fun assertInvalid(input: Path) {
        val report = parseGatlingText(input, {})
        assertEquals(RunValidity.INVALID, report.validity)
        assertTrue(report.diagnostics.isNotEmpty())
    }

    private fun run(version: String = "3.12.0"): String = "RUN\tfixture.FixtureSimulation\tfixturesimulation\t1\t \t$version"

    private fun write(
        name: String,
        lines: List<String>,
    ): Path = tempDir.resolve(name).also { Files.writeString(it, lines.joinToString("\n", postfix = "\n")) }

    private companion object {
        // Gatling OSS v3.9.5 and v3.12.0 LogFileDataWriter layouts; both releases are identical.
        val OFFICIAL_TEXT_LAYOUTS =
            mapOf(
                "RUN" to listOf("type", "simulationClass", "simulationId", "startedAt", "description", "version"),
                "USER" to listOf("type", "scenario", "START|END", "timestamp"),
                "REQUEST" to listOf("type", "groupHierarchyCsv", "name", "startedAt", "endedAt", "OK|KO", "message"),
                "GROUP" to listOf("type", "groupHierarchyCsv", "startedAt", "endedAt", "cumulatedResponseTime", "OK|KO"),
                "ERROR" to listOf("type", "message", "timestamp"),
                "ASSERTION" to listOf("type", "base64Assertion"),
            )
    }
}

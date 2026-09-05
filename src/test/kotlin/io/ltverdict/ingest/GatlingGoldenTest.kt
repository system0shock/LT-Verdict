package io.ltverdict.ingest

import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.metrics.TransactionSummary
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class GatlingGoldenTest {
    @Test
    fun `accepted input routes directly to every parser`() {
        val cases =
            mapOf(
                SourceType.JMETER_CSV to "fixtures/slice1/jmeter/csv-5.6.3/input.jtl",
                SourceType.JMETER_XML to "fixtures/slice1/jmeter/xml-5.6.3/input.xml",
                SourceType.GATLING_TEXT to "fixtures/slice1/gatling/text-3.12.0/simulation.log",
                SourceType.GATLING_BINARY to "fixtures/slice1/gatling/binary-3.15.1/simulation.log",
            )

        cases.forEach { (sourceType, filename) ->
            val path = Path.of(filename)
            val input = AcceptedInput("run", sourceType, "sha256", Files.size(path), path.fileName.toString(), path)
            val samples = mutableListOf<LoadSample>()

            assertEquals(RunValidity.VALID, parseInput(input, samples::add).validity, sourceType.name)
            assertTrue(samples.isNotEmpty(), sourceType.name)
        }
    }

    @Test
    fun `released Gatling text fixtures match their independent oracles`() {
        listOf("text-3.9.5", "text-3.12.0").forEach { case ->
            assertOracle(case, ::parseGatlingText)
        }
    }

    @Test
    fun `released Gatling binary fixtures match their independent oracles`() {
        listOf("binary-3.13.5", "binary-3.15.1").forEach { case ->
            assertOracle(case, ::parseGatlingBinary)
        }
    }

    private fun assertOracle(
        case: String,
        parse: (Path, (LoadSample) -> Unit, (Long) -> Unit, () -> Unit) -> ParseReport,
    ) {
        val directory = Path.of("fixtures/slice1/gatling/$case")
        val input = directory.resolve("simulation.log")
        val samples = mutableListOf<LoadSample>()
        val report = parse(input, samples::add, {}, {})

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(input), report.processedBytes)
        assertEquals(4, samples.count { it.kind == SampleKind.GATLING_REQUEST })
        assertEquals(1, samples.count { it.kind == SampleKind.GATLING_GROUP })

        val accumulator =
            MetricsAccumulator(
                samples.minOf(LoadSample::startedAtEpochMillis),
                samples.maxOf(LoadSample::endedAtEpochMillis),
                MetricsConfig(),
            )
        samples.forEach(accumulator::record)
        val metrics = accumulator.finish()
        val oracle = Json.parseToJsonElement(Files.readString(directory.resolve("oracle.json"))).jsonObject

        assertSummary(metrics.overall, oracle.getValue("overall").jsonObject)
        val expected = oracle.getValue("transactions").jsonArray.map { it.jsonObject }
        val expectedByIdentity = expected.associateBy(::identity)
        assertEquals(expected.size, expectedByIdentity.size)
        assertEquals(expected.size, metrics.transactions.size)
        metrics.transactions.forEach { transaction ->
            assertSummary(transaction.metrics, expectedByIdentity.getValue(identity(transaction)))
        }
    }

    private fun assertSummary(
        actual: MetricSummary,
        expected: JsonObject,
    ) {
        assertEquals(expected.getValue("sample_count").jsonPrimitive.long, actual.sampleCount)
        assertEquals(expected.getValue("error_count").jsonPrimitive.long, actual.errorCount)
        val latency = expected.getValue("latency_ms").jsonObject
        assertEquals(latency.getValue("p50").jsonPrimitive.long, actual.latency.p50Millis)
        assertEquals(latency.getValue("p95").jsonPrimitive.long, actual.latency.p95Millis)
        assertEquals(latency.getValue("p99").jsonPrimitive.long, actual.latency.p99Millis)
        assertEquals(latency.getValue("max").jsonPrimitive.long, actual.latency.maxMillis)
    }

    private fun identity(transaction: TransactionSummary): String =
        identity(transaction.identity.groupPath, transaction.identity.label, transaction.identity.kind.name)

    private fun identity(transaction: JsonObject): String =
        identity(
            transaction.getValue("group_path").jsonArray.map { it.jsonPrimitive.content },
            transaction.getValue("label").jsonPrimitive.content,
            transaction.getValue("kind").jsonPrimitive.content,
        )

    private fun identity(
        groupPath: List<String>,
        label: String,
        kind: String,
    ): String = listOf(groupPath.joinToString("\u0000"), label, kind).joinToString("\u0001")
}

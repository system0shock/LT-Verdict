package io.ltverdict.ingest

import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.TransactionSummary
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

class JtlGoldenTest {
    @Test
    fun `JMeter CSV fixture remains flat and matches its independent oracle`() {
        val input = Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(input, samples::add)

        assertValidAndComplete(input, report.validity.name, report.processedBytes)
        assertTrue(samples.all { it.kind == SampleKind.JMETER_SAMPLER && it.groupPath.isEmpty() })
        assertOracle(samples, oracleFor("csv-5.6.3"))
    }

    @Test
    fun `JMeter XML container and leaf contributions match its independent oracle`() {
        val input = Path.of("fixtures/slice1/jmeter/xml-5.6.3/input.xml")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlXml(input, samples::add)

        assertValidAndComplete(input, report.validity.name, report.processedBytes)
        assertEquals(2, samples.count { it.kind == SampleKind.JMETER_CONTAINER })
        assertEquals(3, samples.count { it.kind == SampleKind.JMETER_SAMPLER })

        val metrics = metricsFor(samples)
        assertEquals(samples.count { it.kind == SampleKind.JMETER_SAMPLER }.toLong(), metrics.overall.sampleCount)
        assertEquals(samples.size.toLong(), metrics.transactions.sumOf { it.metrics.sampleCount })
        assertOracle(metrics, oracleFor("xml-5.6.3"))
    }

    private fun assertValidAndComplete(
        input: Path,
        validity: String,
        processedBytes: Long,
    ) {
        val size = Files.size(input)
        assertEquals("VALID", validity)
        assertEquals(size, processedBytes)
    }

    private fun assertOracle(
        samples: List<LoadSample>,
        oracle: JsonObject,
    ) = assertOracle(metricsFor(samples), oracle)

    private fun assertOracle(
        metrics: NormalizedMetrics,
        oracle: JsonObject,
    ) {
        assertSummary(metrics.overall, oracle.getValue("overall").jsonObject)

        val expected = oracle.getValue("transactions").jsonArray
        val actual = metrics.transactions
        assertEquals(expected.size, actual.size)
        val expectedByIdentity = expected.associate { it.jsonObject.let { item -> identity(item) to item } }
        assertEquals(expected.size, expectedByIdentity.size)
        actual.forEach { transaction ->
            assertSummary(transaction.metrics, expectedByIdentity.getValue(identity(transaction)))
        }
    }

    private fun assertSummary(
        actual: MetricSummary,
        expected: JsonObject,
    ) {
        assertEquals(expected.long("sample_count"), actual.sampleCount)
        assertEquals(expected.long("error_count"), actual.errorCount)
        val latency = expected.getValue("latency_ms").jsonObject
        assertEquals(latency.long("p50"), actual.latency.p50Millis)
        assertEquals(latency.long("p95"), actual.latency.p95Millis)
        assertEquals(latency.long("p99"), actual.latency.p99Millis)
        assertEquals(latency.long("max"), actual.latency.maxMillis)
    }

    private fun metricsFor(samples: List<LoadSample>): NormalizedMetrics {
        val accumulator =
            MetricsAccumulator(
                samples.minOf(LoadSample::startedAtEpochMillis),
                samples.maxOf(LoadSample::endedAtEpochMillis),
                MetricsConfig(),
            )
        samples.forEach(accumulator::record)
        return accumulator.finish()
    }

    private fun oracleFor(case: String): JsonObject =
        Files
            .readString(Path.of("fixtures/slice1/jmeter/$case/oracle.json"))
            .let { content ->
                kotlinx.serialization.json.Json
                    .parseToJsonElement(content)
                    .jsonObject
            }

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long

    private fun identity(transaction: TransactionSummary): String =
        listOf(
            transaction.identity.groupPath.joinToString("\u0000"),
            transaction.identity.label,
            transaction.identity.kind.name,
        ).joinToString("\u0001")

    private fun identity(transaction: JsonObject): String =
        listOf(
            transaction.getValue("group_path").jsonArray.joinToString("\u0000") { it.jsonPrimitive.content },
            transaction.getValue("label").jsonPrimitive.content,
            transaction.getValue("kind").jsonPrimitive.content,
        ).joinToString("\u0001")
}

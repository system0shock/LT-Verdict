package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UtcLoadMetricsTest {
    @Test
    fun `UTC cells use start membership retain full latency and expose p95 only with support`() {
        val accumulator =
            UtcLoadMetricsAccumulator(
                listOf(MetricWindow("window", 1_000, 3_000)),
                stepMillis = 1_000,
                existingWindowHistograms = 1,
                config = MetricsConfig(),
            )

        accumulator.record(sample(999, 5_000))
        repeat(20) { accumulator.record(sample(1_000L + it, 8_000, successful = it != 0)) }
        repeat(19) { accumulator.record(sample(2_000L + it, 100 + it.toLong())) }
        val cells = accumulator.finish().windows.getValue("window")

        assertEquals(20, cells[0].sampleCount)
        assertEquals(1, cells[0].errorCount)
        assertEquals(ExactRatio(1, 20), cells[0].errorRate)
        assertEquals(ExactRatio(20_000, 1_000), cells[0].throughputRps)
        assertEquals(8_003, cells[0].responseTimeP95Millis)
        assertEquals(19, cells[1].sampleCount)
        assertNull(cells[1].responseTimeP95Millis)

        accumulator.record(sample(2_999, 999))
        val supported = accumulator.finish().windows.getValue("window")[1]
        assertEquals(20, supported.sampleCount)
        assertEquals(118, supported.responseTimeP95Millis)
    }

    @Test
    fun `empty covered cells are zero throughput with missing rates and latency`() {
        val metrics =
            UtcLoadMetricsAccumulator(
                listOf(MetricWindow("window", 250, 2_250)),
                stepMillis = 1_000,
                existingWindowHistograms = 1,
                config = MetricsConfig(),
            ).finish()
        val cells = metrics.windows.getValue("window")

        assertEquals(listOf(250L, 1_250L), cells.map(UtcLoadCell::fromEpochMillis))
        cells.forEach { cell ->
            assertEquals(0, cell.sampleCount)
            assertEquals(ExactRatio(0, 1_000), cell.throughputRps)
            assertNull(cell.errorRate)
            assertNull(cell.responseTimeP95Millis)
        }
    }

    @Test
    fun `combined existing and diagnostic histogram potential is bounded before collection`() {
        val windows = listOf(MetricWindow("window", 0, 2_000))

        UtcLoadMetricsAccumulator(windows, 1_000, 2, MetricsConfig(maxWindowHistograms = 4))
        val failure =
            assertThrows(MetricsResourceLimitExceeded::class.java) {
                UtcLoadMetricsAccumulator(windows, 1_000, 3, MetricsConfig(maxWindowHistograms = 4))
            }

        assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.message)
    }

    private fun sample(
        start: Long,
        elapsed: Long,
        successful: Boolean = true,
    ) = LoadSample(start, elapsed, "request", emptyList(), SampleKind.JMETER_SAMPLER, successful)
}

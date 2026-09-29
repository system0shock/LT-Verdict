package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WindowMetricsTest {
    @Test
    fun `unsorted samples use start membership and retain full crossing latency`() {
        val target = TransactionIdentity(emptyList(), "target", SampleKind.JMETER_SAMPLER)
        val accumulator =
            WindowMetricsAccumulator(
                listOf(MetricWindow("first", 0, 1_000), MetricWindow("second", 1_000, 2_000)),
                setOf(target),
                MetricsConfig(),
            )

        accumulator.record(sample(1_500, 800, "target"))
        accumulator.record(sample(999, 500, "other"))
        accumulator.record(sample(0, 100, "target"))
        repeat(10_000) { accumulator.record(sample(10, 1, "irrelevant-$it", SampleKind.JMETER_CONTAINER)) }
        val windows = accumulator.finish()

        assertEquals(2, windows.getValue("first").overall.sampleCount)
        assertEquals(
            500,
            windows
                .getValue("first")
                .overall.latency.maxMillis,
        )
        assertEquals(ExactRatio(2_000, 1_000), windows.getValue("first").overall.throughputRps)
        assertEquals(1, windows.getValue("second").overall.sampleCount)
        assertEquals(
            800,
            windows
                .getValue("second")
                .overall.latency.maxMillis,
        )
        assertEquals(ExactRatio(1_000, 1_000), windows.getValue("second").overall.throughputRps)
        assertEquals(listOf(target), windows.getValue("first").transactions.map(TransactionSummary::identity))
        assertEquals(
            1,
            windows
                .getValue("first")
                .transactions
                .single()
                .metrics.sampleCount,
        )
        assertEquals(
            1,
            windows
                .getValue("second")
                .transactions
                .single()
                .metrics.sampleCount,
        )
    }

    @Test
    fun `samples outside windows are ignored and empty retained transactions stay bindable`() {
        val target = TransactionIdentity(emptyList(), "target", SampleKind.GATLING_REQUEST)
        val accumulator =
            WindowMetricsAccumulator(
                listOf(MetricWindow("window", 1_000, 2_000)),
                setOf(target),
                MetricsConfig(),
            )

        accumulator.record(sample(999, 10, "target", SampleKind.GATLING_REQUEST))
        val result = accumulator.finish().getValue("window")

        assertEquals(0, result.overall.sampleCount)
        assertEquals(ExactRatio(0, 1_000), result.overall.throughputRps)
        assertEquals(
            0,
            result.transactions
                .single()
                .metrics.sampleCount,
        )
    }

    @Test
    fun `aggregate window histogram state has an explicit fixed budget`() {
        val target = TransactionIdentity(emptyList(), "target", SampleKind.JMETER_SAMPLER)
        val windows = listOf(MetricWindow("first", 0, 1_000), MetricWindow("second", 1_000, 2_000))

        WindowMetricsAccumulator(windows, setOf(target), MetricsConfig(maxWindowHistograms = 4))
        val failure =
            assertThrows(MetricsResourceLimitExceeded::class.java) {
                WindowMetricsAccumulator(windows, setOf(target), MetricsConfig(maxWindowHistograms = 3))
            }

        assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.message)
    }

    private fun sample(
        start: Long,
        elapsed: Long,
        label: String,
        kind: SampleKind = SampleKind.JMETER_SAMPLER,
    ) = LoadSample(start, elapsed, label, emptyList(), kind, true)
}

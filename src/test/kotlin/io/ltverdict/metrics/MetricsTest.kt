package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import org.HdrHistogram.PackedHistogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class MetricsTest {
    @Test
    fun `leaf and request feed overall while container and group remain exact transactions`() {
        val metrics =
            accumulator()
                .apply {
                    record(sample(0, 10, "leaf", listOf("jmeter"), SampleKind.JMETER_SAMPLER, true))
                    record(sample(1, 20, "controller", listOf("jmeter"), SampleKind.JMETER_CONTAINER, false))
                    record(sample(2, 30, "request", listOf("gatling"), SampleKind.GATLING_REQUEST, false))
                    record(sample(3, 40, "group", listOf("gatling"), SampleKind.GATLING_GROUP, true))
                }.finish()

        assertEquals(2, metrics.overall.sampleCount)
        assertEquals(1, metrics.overall.errorCount)
        assertEquals(4, metrics.transactions.size)
        assertEquals(
            1,
            metrics.transactions
                .single { it.identity.label == "controller" }
                .metrics.sampleCount,
        )
        assertEquals(
            1,
            metrics.transactions
                .single { it.identity.label == "group" }
                .metrics.sampleCount,
        )
    }

    @Test
    fun `transaction identity distinguishes path and kind and results are sorted`() {
        val metrics =
            accumulator()
                .apply {
                    record(sample(0, 1, "same", listOf("z"), SampleKind.JMETER_SAMPLER))
                    record(sample(1, 1, "same", listOf("a"), SampleKind.JMETER_SAMPLER))
                    record(sample(2, 1, "same", listOf("a"), SampleKind.GATLING_GROUP))
                }.finish()

        assertEquals(3, metrics.transactions.size)
        assertEquals(
            listOf(
                Triple(listOf("a"), "same", SampleKind.GATLING_GROUP),
                Triple(listOf("a"), "same", SampleKind.JMETER_SAMPLER),
                Triple(listOf("z"), "same", SampleKind.JMETER_SAMPLER),
            ),
            metrics.transactions.map { Triple(it.identity.groupPath, it.identity.label, it.identity.kind) },
        )
    }

    @Test
    fun `ratios remain exact instead of using rounded display values`() {
        val metrics =
            accumulator(end = 2_000)
                .apply {
                    record(sample(0, 1, "one", emptyList(), SampleKind.JMETER_SAMPLER, true))
                    record(sample(1, 1, "two", emptyList(), SampleKind.JMETER_SAMPLER, false))
                }.finish()
                .overall
        val errorRate = requireNotNull(metrics.errorRate)

        assertEquals(ExactRatio(1, 2), metrics.errorRate)
        assertEquals(ExactRatio(2_000, 2_000), metrics.throughputRps)
        assertEquals(0, errorRate.compareTo(BigDecimal("0.5")))
        assertTrue(errorRate.compareTo(BigDecimal("0.5000001")) < 0)
        assertTrue(metrics.throughputRps.compareTo(BigDecimal("0.9999999")) > 0)
    }

    @Test
    fun `latency summary exposes percentile and maximum milliseconds`() {
        val latency =
            accumulator()
                .apply {
                    record(sample(0, 10, "a", emptyList(), SampleKind.JMETER_SAMPLER))
                    record(sample(1, 20, "b", emptyList(), SampleKind.JMETER_SAMPLER))
                    record(sample(2, 30, "c", emptyList(), SampleKind.JMETER_SAMPLER))
                }.finish()
                .overall.latency

        assertEquals(20, latency.p50Millis)
        assertEquals(30, latency.p95Millis)
        assertEquals(30, latency.p99Millis)
        assertEquals(30, latency.maxMillis)
    }

    @Test
    fun `timestamps must be valid and match the frozen run window`() {
        assertThrows(IllegalArgumentException::class.java) { accumulator(start = 2, end = 1) }
        assertThrows(IllegalArgumentException::class.java) {
            accumulator().record(sample(-1, 1, "bad", emptyList(), SampleKind.JMETER_SAMPLER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            accumulator().record(sample(253_402_300_799_999, 1, "overflow", emptyList(), SampleKind.JMETER_SAMPLER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            sample(Long.MAX_VALUE, 1, "checked-overflow", emptyList(), SampleKind.JMETER_SAMPLER)
        }
        assertThrows(IllegalArgumentException::class.java) {
            accumulator().record(sample(1_000, 1, "outside", emptyList(), SampleKind.JMETER_SAMPLER))
        }
    }

    @Test
    fun `resource ceilings fail closed`() {
        assertResourceLimit {
            accumulator(config = MetricsConfig(highestTrackableValueMillis = 10)).record(
                sample(0, 11, "slow", emptyList(), SampleKind.JMETER_SAMPLER),
            )
        }
        assertResourceLimit {
            accumulator(config = MetricsConfig(maxTransactions = 1)).apply {
                record(sample(0, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER))
                record(sample(1, 1, "b", emptyList(), SampleKind.JMETER_SAMPLER))
            }
        }
        assertResourceLimit {
            accumulator(config = MetricsConfig(maxTransactionIdentityBytes = 1)).record(
                sample(0, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER),
            )
        }
        assertResourceLimit {
            accumulator(config = MetricsConfig(maxTotalTransactionIdentityBytes = 1)).record(
                sample(0, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER),
            )
        }
        val identityBytes = "a".encodeToByteArray().size + SampleKind.JMETER_SAMPLER.name.length + 2
        val repeated = accumulator(config = MetricsConfig(maxTotalTransactionIdentityBytes = identityBytes.toLong()))
        repeated.record(sample(0, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER))
        repeated.record(sample(1, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER))
        assertResourceLimit {
            repeated.record(sample(2, 1, "b", emptyList(), SampleKind.JMETER_SAMPLER))
        }
        assertResourceLimit {
            accumulator(end = 2_000, config = MetricsConfig(maxOneSecondBuckets = 1)).apply {
                record(sample(0, 1, "a", emptyList(), SampleKind.JMETER_SAMPLER))
                record(sample(1_000, 1, "b", emptyList(), SampleKind.JMETER_SAMPLER))
            }
        }
    }

    @Test
    fun `ten thousand sparse buckets finish within the test heap`() {
        val bucketCount = 10_000
        val accumulator =
            MetricsAccumulator(
                0,
                bucketCount * 1_000L,
                MetricsConfig(maxOneSecondBuckets = bucketCount),
            )
        repeat(bucketCount) { index ->
            accumulator.record(sample(index * 1_000L, 42, "same", emptyList(), SampleKind.JMETER_SAMPLER))
        }

        val metrics = accumulator.finish()

        assertEquals(bucketCount, metrics.oneSecondBuckets.size)
        assertEquals(mapOf(10 to 1_000, 30 to 334, 60 to 167), metrics.rollups.mapValues { it.value.size })
        assertEquals(0L, metrics.oneSecondBuckets.first().bucketStartMillis)
        assertEquals(1L, metrics.oneSecondBuckets.first().sampleCount)
        assertEquals(42L, metrics.oneSecondBuckets.first().maxLatencyMillis)
        assertEquals(9_999_000L, metrics.oneSecondBuckets.last().bucketStartMillis)
        assertEquals(1L, metrics.oneSecondBuckets.last().sampleCount)
        assertEquals(42L, metrics.oneSecondBuckets.last().maxLatencyMillis)
    }

    @Test
    fun `accumulators do not share mutable state`() {
        val first = accumulator().apply { record(sample(0, 10, "first", emptyList(), SampleKind.JMETER_SAMPLER)) }
        val second =
            accumulator().apply {
                record(sample(0, 20, "second", emptyList(), SampleKind.JMETER_SAMPLER, false))
                record(sample(1, 30, "second", emptyList(), SampleKind.JMETER_SAMPLER, false))
            }
        val firstResult = first.finish()
        val secondResult = second.finish()

        assertEquals(1, firstResult.overall.sampleCount)
        assertEquals(2, secondResult.overall.sampleCount)
        assertEquals(0, firstResult.overall.errorCount)
        assertEquals(2, secondResult.overall.errorCount)
    }

    @Test
    fun `percentiles never exceed the observed maximum for identical samples`() {
        listOf(2_047L, 2_048L, 4_095L, 4_096L, 60_000L, 86_400_000L).forEach { value ->
            val latency = publishedLatency(List(100) { value })

            assertEquals(LatencySummary(value, value, value, value), latency, "value $value")
        }
    }

    @Test
    fun `cap keeps the invariant p50 p95 p99 max and documents residual rounding`() {
        val closeToMax = publishedLatency(List(95) { 60_000L } + List(5) { 60_010L })
        assertEquals(60_010L, closeToMax.p95Millis)
        assertTrue(closeToMax.p50Millis <= closeToMax.p95Millis)
        assertTrue(closeToMax.p95Millis <= closeToMax.p99Millis)
        assertTrue(closeToMax.p99Millis <= closeToMax.maxMillis)

        // The exact p95 is 60000 but HDR rounds up inside the equivalent range and the maximum is far away.
        val farMax = publishedLatency(List(100) { 60_000L } + listOf(70_000L))
        assertEquals(60_031L, farMax.p95Millis)
        assertEquals(70_000L, farMax.maxMillis)
    }

    @Test
    fun `cap applies at any configured precision`() {
        assertEquals(60_000L, publishedLatency(List(100) { 60_000L }, digits = 4).p95Millis)
        assertEquals(60_000L, publishedLatency(List(100) { 60_000L }, digits = 5).p95Millis)
        assertEquals(86_400_000L, publishedLatency(List(100) { 86_400_000L }, digits = 4).p99Millis)
        assertEquals(86_400_000L, publishedLatency(List(100) { 86_400_000L }, digits = 5).p99Millis)
        // The maximum is far away, so the cap cannot remove the HDR rounding of 60000 to 60001 at four digits.
        assertEquals(60_001L, publishedLatency(List(100) { 60_000L } + listOf(70_000L), digits = 4).p95Millis)
    }

    @Test
    fun `overall and transaction summaries publish the capped percentile`() {
        val metrics =
            accumulator(end = 100_000_000, config = MetricsConfig())
                .apply { repeat(100) { record(sample(it.toLong(), 60_000, "x", emptyList(), SampleKind.JMETER_SAMPLER)) } }
                .finish()

        assertEquals(60_000L, metrics.overall.latency.p95Millis)
        assertEquals(
            60_000L,
            metrics.transactions
                .single()
                .metrics.latency.p99Millis,
        )
    }

    @Test
    fun `single sample percentiles use the HDR value capped at the observed maximum across precisions`() {
        listOf(
            3 to listOf(2_047L, 2_048L, 2_049L),
            4 to listOf(16_383L, 16_384L, 16_385L, 32_767L, 32_768L, 32_769L),
            5 to listOf(131_071L, 131_072L, 131_073L, 262_143L, 262_144L, 262_145L),
        ).forEach { (digits, values) ->
            values.forEach { value ->
                assertCappedLatency(listOf(value), digits)
            }
        }
    }

    @Test
    fun `mixed samples cap HDR upper bound at each precision boundary`() {
        listOf(3 to 2_048L, 4 to 32_768L, 5 to 262_144L).forEach { (digits, boundary) ->
            val values = listOf(boundary - 1, boundary)
            val histogram = PackedHistogram(1, 86_400_000, digits).apply { values.forEach { recordValue(it) } }

            assertTrue(histogram.getValueAtPercentile(95.0) > boundary, "digits $digits")
            assertCappedLatency(values, digits)
        }
    }

    @Test
    fun `merged histogram caps a large latency at its observed maximum`() {
        val config = MetricsConfig()
        val merged = MutableMetrics(config).apply { record(sample(0, 10, "x", emptyList(), SampleKind.JMETER_SAMPLER)) }
        val source = MutableMetrics(config).apply { record(sample(0, 86_400_000, "x", emptyList(), SampleKind.JMETER_SAMPLER)) }
        merged.merge(source)

        assertEquals(LatencySummary(10, 86_400_000, 86_400_000, 86_400_000), merged.summary(100_000_000).latency)
    }

    private fun assertCappedLatency(
        values: List<Long>,
        digits: Int,
    ) {
        val max = values.max()
        val histogram = PackedHistogram(1, 86_400_000, digits).apply { values.forEach { recordValue(it) } }
        val actual = publishedLatency(values, digits)

        assertEquals(max, actual.maxMillis)
        listOf(50.0 to actual.p50Millis, 95.0 to actual.p95Millis, 99.0 to actual.p99Millis).forEach { (percentile, value) ->
            assertEquals(minOf(histogram.getValueAtPercentile(percentile), max), value, "digits $digits p$percentile")
            assertTrue(value <= max, "digits $digits p$percentile")
        }
    }

    private fun publishedLatency(
        values: List<Long>,
        digits: Int = 3,
    ): LatencySummary =
        accumulator(end = 100_000_000, config = MetricsConfig(significantDigits = digits))
            .apply {
                values.forEachIndexed {
                    index,
                    value,
                    ->
                    record(sample(index.toLong() % 1_000, value, "x", emptyList(), SampleKind.JMETER_SAMPLER))
                }
            }.finish()
            .overall.latency

    private fun accumulator(
        start: Long = 0,
        end: Long = 1_000,
        config: MetricsConfig = MetricsConfig(),
    ) = MetricsAccumulator(start, end, config)

    private fun sample(
        start: Long,
        elapsed: Long,
        label: String,
        path: List<String>,
        kind: SampleKind,
        successful: Boolean = true,
    ) = LoadSample(start, elapsed, label, path, kind, successful)

    private fun assertResourceLimit(block: () -> Unit) {
        val error = assertThrows(IllegalStateException::class.java) { block() }
        assertEquals("RESOURCE_LIMIT_EXCEEDED", error.message)
    }
}

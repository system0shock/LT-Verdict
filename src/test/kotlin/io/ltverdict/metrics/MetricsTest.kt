package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
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

package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import org.HdrHistogram.PackedHistogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class NormalizationGoldenTest {
    @Test
    fun `spike drop fixture preserves sparse buckets merge-only rollups and V2 histograms`() {
        val samples = readSpikeDropFixture()
        val runStart = samples.minOf(LoadSample::startedAtEpochMillis)
        val runEnd = samples.maxOf { Math.addExact(it.startedAtEpochMillis, it.elapsedMillis) }
        val accumulator = MetricsAccumulator(runStart, runEnd, MetricsConfig())

        samples.forEach(accumulator::record)
        val result = accumulator.finish()
        val repeated = MetricsAccumulator(runStart, runEnd, MetricsConfig()).also { copy -> samples.forEach(copy::record) }.finish()

        assertEquals(result, repeated)

        assertEquals(listOf(0L, 1_000L), result.oneSecondBuckets.map { it.bucketStartMillis })
        assertBucket(result.oneSecondBuckets[0], sampleCount = 2, maxLatencyMillis = 900)
        assertBucket(result.oneSecondBuckets[1], sampleCount = 1, maxLatencyMillis = 20)

        listOf(10, 30, 60).forEach { seconds ->
            val rollup = result.rollups.getValue(seconds)
            assertEquals(1, rollup.size)
            assertBucket(rollup.single(), sampleCount = 3, maxLatencyMillis = 900)
        }
    }

    private fun assertBucket(
        bucket: NormalizedBucket,
        sampleCount: Long,
        maxLatencyMillis: Long,
    ) {
        assertEquals(sampleCount, bucket.sampleCount)
        assertEquals(0L, bucket.errorCount)
        assertEquals(maxLatencyMillis, bucket.maxLatencyMillis)
        assertEquals(
            setOf("bucket_start_ms", "sample_count", "error_count", "max_latency_ms", "hdr_v2_base64"),
            bucket.toJsonObject().keys,
        )

        val histogram =
            PackedHistogram.decodeFromCompressedByteBuffer(
                ByteBuffer.wrap(Base64.getDecoder().decode(bucket.hdrV2Base64)),
                86_400_000L,
            )
        assertEquals(sampleCount, histogram.totalCount)
        assertEquals(maxLatencyMillis, histogram.maxValue)
    }

    private fun readSpikeDropFixture(): List<LoadSample> =
        Files
            .readAllLines(Path.of("fixtures/slice1/normalization/spike-drop.jtl"))
            .drop(1)
            .map { row ->
                val (timestamp, elapsed, label, success) = row.split(',', limit = 4)
                LoadSample(
                    startedAtEpochMillis = timestamp.toLong(),
                    elapsedMillis = elapsed.toLong(),
                    label = label,
                    groupPath = emptyList(),
                    kind = SampleKind.JMETER_SAMPLER,
                    successful = success.toBooleanStrict(),
                )
            }
}

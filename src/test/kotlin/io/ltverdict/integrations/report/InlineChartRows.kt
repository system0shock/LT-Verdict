package io.ltverdict.integrations.report

import org.HdrHistogram.PackedHistogram
import java.nio.ByteBuffer
import java.util.Base64

/** Rows of a rollup-60s.ndjson file for the chart tests: [count] minute bins from [firstStartMs], one every [stepMinutes] minutes. */
internal object InlineChartRows {
    fun rows(
        count: Int,
        firstStartMs: Long = 0,
        stepMinutes: Int = 1,
        samples: (Int) -> Long = { 60L },
        errors: (Int) -> Long = { 0L },
        p95: (Int) -> Long = { 100L },
    ): String =
        buildString {
            repeat(count) { index ->
                val start = firstStartMs + index.toLong() * stepMinutes * 60_000L
                val n = samples(index)
                val max = p95(index)
                append("""{"bucket_start_ms":$start,"error_count":${errors(index)},"hdr_v2_base64":"${histogram(max, n)}",""")
                append(""""max_latency_ms":$max,"sample_count":$n}""").append('\n')
            }
        }

    fun histogram(
        value: Long,
        count: Long,
    ): String {
        val histogram = PackedHistogram(1, 86_400_000, 3)
        histogram.recordValueWithCount(value, count)
        val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
        val length = histogram.encodeIntoCompressedByteBuffer(buffer)
        return Base64.getEncoder().encodeToString(buffer.array().copyOf(length))
    }
}

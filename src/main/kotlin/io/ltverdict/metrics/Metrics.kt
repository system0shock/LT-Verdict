package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import io.ltverdict.ingest.SampleKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.HdrHistogram.PackedHistogram
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.util.Base64
import java.util.TreeMap

internal data class MetricsConfig(
    val lowestDiscernibleValueMillis: Long = 1,
    val highestTrackableValueMillis: Long = 86_400_000,
    val significantDigits: Int = 3,
    val maxTransactions: Int = 10_000,
    val maxTransactionIdentityBytes: Int = 65_536,
    val maxTotalTransactionIdentityBytes: Long = 67_108_864,
    val maxOneSecondBuckets: Int = 100_000,
)

internal class MetricsResourceLimitExceeded : IllegalStateException("RESOURCE_LIMIT_EXCEEDED")

internal data class ExactRatio(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(numerator >= 0 && denominator > 0) { "INVALID_RATIO" }
    }

    fun compareTo(value: BigDecimal): Int =
        BigDecimal
            .valueOf(numerator)
            .compareTo(value.multiply(BigDecimal.valueOf(denominator)))
}

internal data class LatencySummary(
    val p50Millis: Long,
    val p95Millis: Long,
    val p99Millis: Long,
    val maxMillis: Long,
)

internal data class MetricSummary(
    val sampleCount: Long,
    val errorCount: Long,
    val errorRate: ExactRatio?,
    val throughputRps: ExactRatio,
    val latency: LatencySummary,
)

internal data class TransactionIdentity(
    val groupPath: List<String>,
    val label: String,
    val kind: SampleKind,
)

internal data class TransactionSummary(
    val identity: TransactionIdentity,
    val metrics: MetricSummary,
)

internal data class NormalizedBucket(
    val bucketStartMillis: Long,
    val sampleCount: Long,
    val errorCount: Long,
    val maxLatencyMillis: Long,
    val hdrV2Base64: String,
)

internal data class NormalizedMetrics(
    val overall: MetricSummary,
    val transactions: List<TransactionSummary>,
    val oneSecondBuckets: List<NormalizedBucket>,
    val rollups: Map<Int, List<NormalizedBucket>>,
)

internal fun NormalizedBucket.toJsonObject(): JsonObject =
    buildJsonObject {
        put("bucket_start_ms", bucketStartMillis)
        put("sample_count", sampleCount)
        put("error_count", errorCount)
        put("max_latency_ms", maxLatencyMillis)
        put("hdr_v2_base64", hdrV2Base64)
    }

internal class MetricsAccumulator(
    private val runStartEpochMillis: Long,
    private val runEndEpochMillis: Long,
    private val config: MetricsConfig,
) {
    private val overall: MutableMetrics
    private val transactions = HashMap<TransactionIdentity, MutableMetrics>()
    private val oneSecondBuckets = TreeMap<Long, MutableMetrics>()
    private val runWindowMillis: Long
    private var retainedIdentityBytes = 0L

    init {
        require(
            runStartEpochMillis >= 0 &&
                runEndEpochMillis in runStartEpochMillis..MAX_TIMESTAMP_EPOCH_MILLIS,
        ) { "INVALID_RUN_WINDOW" }
        require(
            config.lowestDiscernibleValueMillis >= 1 &&
                config.highestTrackableValueMillis / 2 >= config.lowestDiscernibleValueMillis &&
                config.significantDigits in 1..5 &&
                config.maxTransactions >= 0 &&
                config.maxTransactionIdentityBytes >= 0 &&
                config.maxTotalTransactionIdentityBytes >= 0 &&
                config.maxOneSecondBuckets >= 0,
        ) { "INVALID_METRICS_CONFIG" }
        runWindowMillis = maxOf(1, runEndEpochMillis - runStartEpochMillis)
        overall = MutableMetrics(config)
    }

    fun record(sample: LoadSample) {
        if (sample.startedAtEpochMillis < runStartEpochMillis || sample.endedAtEpochMillis > runEndEpochMillis) {
            throw IllegalArgumentException("RUN_WINDOW_MISMATCH")
        }
        if (sample.elapsedMillis > config.highestTrackableValueMillis) resourceLimit()

        val identity = TransactionIdentity(sample.groupPath.toList(), sample.label, sample.kind)
        val existingTransaction = transactions[identity]
        if (existingTransaction == null) admitIdentity(identity)

        val contributesOverall = sample.kind == SampleKind.JMETER_SAMPLER || sample.kind == SampleKind.GATLING_REQUEST
        val bucketStartMillis =
            if (contributesOverall) {
                ((sample.startedAtEpochMillis - runStartEpochMillis) / ONE_SECOND_MILLIS) * ONE_SECOND_MILLIS
            } else {
                null
            }
        if (bucketStartMillis != null &&
            !oneSecondBuckets.containsKey(bucketStartMillis) &&
            oneSecondBuckets.size >= config.maxOneSecondBuckets
        ) {
            resourceLimit()
        }

        val transaction = existingTransaction ?: MutableMetrics(config).also { transactions[identity] = it }
        transaction.record(sample)
        if (bucketStartMillis != null) {
            overall.record(sample)
            oneSecondBuckets.getOrPut(bucketStartMillis) { MutableMetrics(config) }.record(sample)
        }
    }

    fun finish(): NormalizedMetrics {
        val transactions =
            transactions.keys
                .sortedWith(TRANSACTION_IDENTITY_COMPARATOR)
                .map { identity -> TransactionSummary(identity, this.transactions.getValue(identity).summary(runWindowMillis)) }
        val oneSecond = oneSecondBuckets.map { (start, metrics) -> metrics.bucket(start) }
        val rollups = ROLLUP_SECONDS.associateWith(::rollup)
        return NormalizedMetrics(overall.summary(runWindowMillis), transactions, oneSecond, rollups)
    }

    private fun admitIdentity(identity: TransactionIdentity) {
        if (transactions.size >= config.maxTransactions) resourceLimit()
        val bytes = identity.byteSize()
        if (bytes > config.maxTransactionIdentityBytes ||
            bytes > config.maxTotalTransactionIdentityBytes - retainedIdentityBytes
        ) {
            resourceLimit()
        }
        retainedIdentityBytes += bytes
    }

    private fun rollup(seconds: Int): List<NormalizedBucket> {
        val widthMillis = seconds * ONE_SECOND_MILLIS
        val merged = TreeMap<Long, MutableMetrics>()
        oneSecondBuckets.forEach { (start, source) ->
            merged.getOrPut((start / widthMillis) * widthMillis) { MutableMetrics(config) }.merge(source)
        }
        return merged.map { (start, metrics) -> metrics.bucket(start) }
    }
}

private class MutableMetrics(
    config: MetricsConfig,
) {
    private val histogram =
        PackedHistogram(
            config.lowestDiscernibleValueMillis,
            config.highestTrackableValueMillis,
            config.significantDigits,
        )
    private var sampleCount = 0L
    private var errorCount = 0L
    private var maxLatencyMillis = 0L

    fun record(sample: LoadSample) {
        histogram.recordValue(sample.elapsedMillis)
        sampleCount = Math.incrementExact(sampleCount)
        if (!sample.successful) errorCount = Math.incrementExact(errorCount)
        maxLatencyMillis = maxOf(maxLatencyMillis, sample.elapsedMillis)
    }

    fun merge(source: MutableMetrics) {
        histogram.add(source.histogram)
        sampleCount = Math.addExact(sampleCount, source.sampleCount)
        errorCount = Math.addExact(errorCount, source.errorCount)
        maxLatencyMillis = maxOf(maxLatencyMillis, source.maxLatencyMillis)
    }

    fun summary(runWindowMillis: Long): MetricSummary =
        MetricSummary(
            sampleCount = sampleCount,
            errorCount = errorCount,
            errorRate = if (sampleCount == 0L) null else ExactRatio(errorCount, sampleCount),
            throughputRps = ExactRatio(Math.multiplyExact(sampleCount, ONE_SECOND_MILLIS), runWindowMillis),
            latency =
                LatencySummary(
                    p50Millis = histogram.getValueAtPercentile(50.0),
                    p95Millis = histogram.getValueAtPercentile(95.0),
                    p99Millis = histogram.getValueAtPercentile(99.0),
                    maxMillis = maxLatencyMillis,
                ),
        )

    fun bucket(startMillis: Long): NormalizedBucket =
        NormalizedBucket(
            bucketStartMillis = startMillis,
            sampleCount = sampleCount,
            errorCount = errorCount,
            maxLatencyMillis = maxLatencyMillis,
            hdrV2Base64 = histogram.compressedV2Base64(),
        )
}

private fun TransactionIdentity.byteSize(): Long {
    var bytes = 0L
    (groupPath + label + kind.name).forEach { component ->
        bytes =
            try {
                Math.addExact(bytes, component.encodeToByteArray().size.toLong() + 1)
            } catch (_: ArithmeticException) {
                resourceLimit()
            }
    }
    return bytes
}

private fun PackedHistogram.compressedV2Base64(): String {
    val buffer = ByteBuffer.allocate(neededByteBufferCapacity)
    val length = encodeIntoCompressedByteBuffer(buffer)
    return Base64.getEncoder().encodeToString(buffer.array().copyOf(length))
}

private fun compareIdentities(
    left: TransactionIdentity,
    right: TransactionIdentity,
): Int {
    for (index in 0 until minOf(left.groupPath.size, right.groupPath.size)) {
        val compared = left.groupPath[index].compareTo(right.groupPath[index])
        if (compared != 0) return compared
    }
    val pathSize = left.groupPath.size.compareTo(right.groupPath.size)
    if (pathSize != 0) return pathSize
    val label = left.label.compareTo(right.label)
    return if (label != 0) label else left.kind.name.compareTo(right.kind.name)
}

private fun resourceLimit(): Nothing = throw MetricsResourceLimitExceeded()

private val TRANSACTION_IDENTITY_COMPARATOR = Comparator(::compareIdentities)
private val ROLLUP_SECONDS = listOf(10, 30, 60)
private const val ONE_SECOND_MILLIS = 1_000L

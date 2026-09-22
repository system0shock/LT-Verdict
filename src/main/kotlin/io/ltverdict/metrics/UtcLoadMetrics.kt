package io.ltverdict.metrics

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind

internal data class UtcLoadCell(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val sampleCount: Long,
    val errorCount: Long,
    val errorRate: ExactRatio?,
    val throughputRps: ExactRatio,
    val responseTimeP95Millis: Long?,
)

internal data class UtcLoadMetrics(
    val windows: Map<String, List<UtcLoadCell>>,
)

internal class UtcLoadMetricsAccumulator(
    windows: List<MetricWindow>,
    private val stepMillis: Long,
    existingWindowHistograms: Int,
    private val config: MetricsConfig,
) {
    private val windows = windows.toList()
    private val cells: List<Array<MutableMetrics?>>

    init {
        require(stepMillis > 0 && existingWindowHistograms >= 0 && config.maxWindowHistograms >= 0) { "INVALID_UTC_LOAD_METRICS" }
        require(
            windows.isNotEmpty() &&
                windows.zipWithNext().all { (left, right) -> left.toEpochMillis <= right.fromEpochMillis } &&
                windows.all {
                    it.fromEpochMillis >= 0 &&
                        it.toEpochMillis > it.fromEpochMillis &&
                        (it.toEpochMillis - it.fromEpochMillis) % stepMillis == 0L
                },
        ) { "INVALID_UTC_LOAD_WINDOWS" }
        var potential = existingWindowHistograms.toLong()
        cells =
            windows.map { window ->
                val count = ((window.toEpochMillis - window.fromEpochMillis) / stepMillis).toInt()
                potential = Math.addExact(potential, count.toLong())
                if (potential > config.maxWindowHistograms) throw MetricsResourceLimitExceeded()
                arrayOfNulls(count)
            }
    }

    fun record(sample: LoadSample) {
        if (sample.kind != SampleKind.JMETER_SAMPLER && sample.kind != SampleKind.GATLING_REQUEST) return
        val windowIndex = membership(sample.startedAtEpochMillis) ?: return
        if (sample.elapsedMillis > config.highestTrackableValueMillis) throw MetricsResourceLimitExceeded()
        val window = windows[windowIndex]
        val cellIndex = ((sample.startedAtEpochMillis - window.fromEpochMillis) / stepMillis).toInt()
        val metrics = cells[windowIndex][cellIndex] ?: MutableMetrics(config).also { cells[windowIndex][cellIndex] = it }
        metrics.record(sample)
    }

    fun finish(checkCancelled: () -> Unit = {}): UtcLoadMetrics =
        UtcLoadMetrics(
            windows
                .mapIndexed { windowIndex, window ->
                    window.id to
                        cells[windowIndex].mapIndexed { cellIndex, mutable ->
                            checkCancelled()
                            val summary = mutable?.summary(stepMillis)
                            val start = Math.addExact(window.fromEpochMillis, Math.multiplyExact(cellIndex.toLong(), stepMillis))
                            UtcLoadCell(
                                fromEpochMillis = start,
                                toEpochMillis = Math.addExact(start, stepMillis),
                                sampleCount = summary?.sampleCount ?: 0,
                                errorCount = summary?.errorCount ?: 0,
                                errorRate = summary?.errorRate,
                                throughputRps = summary?.throughputRps ?: ExactRatio(0, stepMillis),
                                responseTimeP95Millis =
                                    summary
                                        ?.takeIf { it.sampleCount >= MIN_P95_SAMPLES }
                                        ?.latency
                                        ?.p95Millis,
                            )
                        }
                }.toMap(LinkedHashMap()),
        )

    private fun membership(startEpochMillis: Long): Int? {
        var low = 0
        var high = windows.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            val window = windows[middle]
            when {
                startEpochMillis < window.fromEpochMillis -> high = middle - 1
                startEpochMillis >= window.toEpochMillis -> low = middle + 1
                else -> return middle
            }
        }
        return null
    }
}

internal const val MIN_P95_SAMPLES = 20L

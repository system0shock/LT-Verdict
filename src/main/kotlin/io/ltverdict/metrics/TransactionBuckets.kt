package io.ltverdict.metrics

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.parseInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.HdrHistogram.PackedHistogram
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class TransactionBucketSeriesPage(
    val tx: String,
    val buckets: List<JsonElement>,
    val nextFromMillis: Long?,
)

internal data class TransactionBucketsResponse(
    val rollupSeconds: Int,
    val series: List<TransactionBucketSeriesPage>,
)

private val runCacheLocks = ConcurrentHashMap<String, Any>()

/**
 * ADR 0038: lazily builds and caches per-transaction rollup buckets under `runs/<runId>/tx-rollups-v1-<rollup>s.ndjson`,
 * completely outside analysis `identity.json`, `analysis-result.json`, and `manifest.json`.
 */
internal fun readTransactionBuckets(
    input: AcceptedInput,
    rollupSeconds: Int,
    requestedTx: List<String>,
    fromMillis: Long,
    toMillis: Long?,
    limit: Int,
    checkCancelled: () -> Unit = {},
): TransactionBucketsResponse {
    val runDir = input.path.parent?.parent ?: throw IllegalStateException("RUN_DIR_MISSING")
    val cacheFile = runDir.resolve("tx-rollups-v1-${rollupSeconds}s.ndjson")
    ensureTransactionCache(input, runDir, cacheFile, rollupSeconds, checkCancelled)
    return queryTransactionCache(cacheFile, rollupSeconds, requestedTx, fromMillis, toMillis, limit)
}

private fun ensureTransactionCache(
    input: AcceptedInput,
    runDir: Path,
    cacheFile: Path,
    rollupSeconds: Int,
    checkCancelled: () -> Unit,
) {
    if (Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) return
    val lock = runCacheLocks.computeIfAbsent("${input.runId}:$rollupSeconds") { Any() }
    synchronized(lock) {
        if (Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) return
        val widthMillis = rollupSeconds * ONE_SECOND_MS
        var runStart: Long? = null
        var runEnd: Long? = null
        val pass1 =
            parseInput(
                input,
                { sample ->
                    runStart = minOf(runStart ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    runEnd = maxOf(runEnd ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                },
                checkCancelled = checkCancelled,
            )
        val origin = runStart
        if (pass1.validity == RunValidity.INVALID || origin == null || runEnd == null) {
            writeAtomicLines(runDir, cacheFile, emptyList())
            return
        }
        val byTx = LinkedHashMap<String, TreeMap<Long, TxBucketAcc>>()
        parseInput(
            input,
            { sample ->
                if (sample.kind == SampleKind.JMETER_SAMPLER || sample.kind == SampleKind.GATLING_REQUEST) {
                    val tx = sample.label
                    val buckets =
                        byTx[tx] ?: if (byTx.size < MAX_CACHED_TRANSACTIONS) {
                            TreeMap<Long, TxBucketAcc>().also { byTx[tx] = it }
                        } else {
                            null
                        }
                    if (buckets != null) {
                        val offset = maxOf(0L, sample.startedAtEpochMillis - origin)
                        val bucketStart = (offset / widthMillis) * widthMillis
                        val acc =
                            buckets[bucketStart] ?: if (buckets.size < MAX_BUCKETS_PER_TX) {
                                TxBucketAcc().also { buckets[bucketStart] = it }
                            } else {
                                null
                            }
                        acc?.record(sample.elapsedMillis, sample.successful)
                    }
                }
            },
            checkCancelled = checkCancelled,
        )

        val lines = ArrayList<String>()
        for (tx in byTx.keys.sorted()) {
            val buckets = byTx.getValue(tx)
            for ((bucketStart, acc) in buckets) {
                lines += acc.toCacheLine(tx, bucketStart)
            }
        }
        writeAtomicLines(runDir, cacheFile, lines)
    }
}

private fun writeAtomicLines(
    runDir: Path,
    target: Path,
    lines: List<String>,
) {
    val tmp = runDir.resolve(".tx-rollups-${UUID.randomUUID()}.tmp")
    try {
        Files.newBufferedWriter(tmp, UTF_8).use { writer ->
            for (line in lines) {
                writer.write(line)
                writer.newLine()
            }
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

private fun queryTransactionCache(
    cacheFile: Path,
    rollupSeconds: Int,
    requestedTx: List<String>,
    fromMillis: Long,
    toMillis: Long?,
    limit: Int,
): TransactionBucketsResponse {
    val wanted = requestedTx.toSet()
    val bucketsByTx = requestedTx.associateWith { ArrayList<JsonElement>() }
    val nextByTx = HashMap<String, Long>()

    Files.newBufferedReader(cacheFile, UTF_8).useLines { lines ->
        for (line in lines) {
            if (line.isBlank()) continue
            val obj = Json.parseToJsonElement(line).jsonObject
            val tx = obj.getValue("tx").jsonPrimitive.content
            if (tx !in wanted) continue
            if (nextByTx.containsKey(tx)) continue
            val start = obj.getValue("b").jsonPrimitive.long
            if (start < fromMillis) continue
            if (toMillis != null && start >= toMillis) continue
            val list = bucketsByTx.getValue(tx)
            if (list.size == limit) {
                nextByTx[tx] = start
                continue
            }
            list +=
                buildJsonObject {
                    put("bucket_start_ms", start)
                    put("sample_count", obj.getValue("n").jsonPrimitive.long)
                    put("error_count", obj.getValue("e").jsonPrimitive.long)
                    put("p50_latency_ms", obj.getValue("p50").jsonPrimitive.long)
                    put("p95_latency_ms", obj.getValue("p95").jsonPrimitive.long)
                    put("p99_latency_ms", obj.getValue("p99").jsonPrimitive.long)
                    put("max_latency_ms", obj.getValue("max").jsonPrimitive.long)
                }
        }
    }

    val series =
        requestedTx.map { tx ->
            TransactionBucketSeriesPage(
                tx = tx,
                buckets = bucketsByTx.getValue(tx),
                nextFromMillis = nextByTx[tx],
            )
        }
    return TransactionBucketsResponse(rollupSeconds, series)
}

internal fun TransactionBucketsResponse.toJsonObject(): JsonObject =
    buildJsonObject {
        put("rollup_seconds", rollupSeconds)
        put(
            "series",
            JsonArray(
                series.map { page ->
                    buildJsonObject {
                        put("tx", page.tx)
                        put("buckets", JsonArray(page.buckets))
                        put("next_from_ms", page.nextFromMillis?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            ),
        )
    }

private class TxBucketAcc {
    private val histogram = PackedHistogram(1L, MAX_TRACKABLE_LATENCY_MS, 3)
    private var sampleCount = 0L
    private var errorCount = 0L
    private var maxLatency = 0L

    fun record(
        elapsedMillis: Long,
        successful: Boolean,
    ) {
        val clamped = elapsedMillis.coerceIn(0L, MAX_TRACKABLE_LATENCY_MS)
        histogram.recordValue(clamped)
        sampleCount++
        if (!successful) errorCount++
        if (clamped > maxLatency) maxLatency = clamped
    }

    fun toCacheLine(
        tx: String,
        bucketStartMillis: Long,
    ): String {
        val p50 = minOf(histogram.getValueAtPercentile(50.0), maxLatency)
        val p95 = minOf(histogram.getValueAtPercentile(95.0), maxLatency)
        val p99 = minOf(histogram.getValueAtPercentile(99.0), maxLatency)
        return buildJsonObject {
            put("tx", tx)
            put("b", bucketStartMillis)
            put("n", sampleCount)
            put("e", errorCount)
            put("p50", p50)
            put("p95", p95)
            put("p99", p99)
            put("max", maxLatency)
        }.toString()
    }
}

private const val ONE_SECOND_MS = 1_000L
private const val MAX_TRACKABLE_LATENCY_MS = 86_400_000L
private const val MAX_CACHED_TRANSACTIONS = 512
private const val MAX_BUCKETS_PER_TX = 10_000

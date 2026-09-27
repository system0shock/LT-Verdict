package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SampleKind
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.parseInput
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeSet

internal const val RUN_PERIOD_SCHEMA_VERSION = "run-period.v1"
internal const val RUN_PERIOD_RECOGNITION_METHOD = "sample-timestamps.v1"
internal const val RUN_PERIOD_STATUS_RECOGNIZED = "RECOGNIZED"
internal const val RUN_PERIOD_STATUS_INVALID_INPUT = "UNRECOGNIZED_INVALID_INPUT"
internal const val RUN_PERIOD_STATUS_NO_SAMPLES = "UNRECOGNIZED_NO_SAMPLES"

internal data class RunPeriodV1(
    val schemaVersion: String,
    val loadInputSha256: String,
    val recognitionMethod: String,
    val firstSampleEpochMillis: Long,
    val lastSampleEpochMillis: Long,
    val longestIdleGapMillis: Long?,
    val idleGapCount: Int,
    val status: String,
)

internal fun recognizeRunPeriod(
    sourceType: SourceType,
    source: Path,
    loadInputSha256: String,
    maxIdleGapMillis: Long,
): RunPeriodV1 {
    require(maxIdleGapMillis >= ONE_SECOND_MILLIS && maxIdleGapMillis % ONE_SECOND_MILLIS == 0L) { "INVALID_MAX_IDLE_GAP" }
    return try {
        val input =
            AcceptedInput(RECOGNITION_RUN_ID, sourceType, loadInputSha256, Files.size(source), source.fileName.toString(), source)
        var first: Long? = null
        var last: Long? = null
        val occupied = TreeSet<Long>()
        val report =
            parseInput(
                input,
                { sample ->
                    first = minOf(first ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    last = maxOf(last ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                    if (sample.kind == SampleKind.JMETER_SAMPLER || sample.kind == SampleKind.GATLING_REQUEST) {
                        occupied += Math.floorDiv(sample.startedAtEpochMillis, ONE_SECOND_MILLIS)
                    }
                },
            )
        val firstSample = first
        val lastSample = last
        when {
            report.validity == RunValidity.INVALID -> unrecognized(loadInputSha256, RUN_PERIOD_STATUS_INVALID_INPUT)
            firstSample == null || lastSample == null -> unrecognized(loadInputSha256, RUN_PERIOD_STATUS_NO_SAMPLES)
            else -> {
                // Факты простоев не зависят от maxIdleGapMillis: артефакт переиспользуется запросами
                // с разным допуском, поэтому сравнение с допуском выполняется на выводе окна.
                var idleGapCount = 0
                var longestBuckets = 0L
                var previous = -1L
                for (bucket in occupied) {
                    if (previous >= 0 && bucket - previous > 1) {
                        idleGapCount++
                        longestBuckets = maxOf(longestBuckets, bucket - previous - 1)
                    }
                    previous = bucket
                }
                RunPeriodV1(
                    RUN_PERIOD_SCHEMA_VERSION,
                    loadInputSha256,
                    RUN_PERIOD_RECOGNITION_METHOD,
                    firstSample,
                    lastSample,
                    if (idleGapCount == 0) null else longestBuckets * ONE_SECOND_MILLIS,
                    idleGapCount,
                    RUN_PERIOD_STATUS_RECOGNIZED,
                )
            }
        }
    } catch (_: IllegalArgumentException) {
        unrecognized(loadInputSha256, RUN_PERIOD_STATUS_INVALID_INPUT)
    } catch (_: IOException) {
        unrecognized(loadInputSha256, RUN_PERIOD_STATUS_INVALID_INPUT)
    }
}

internal fun runPeriodJson(period: RunPeriodV1): JsonObject =
    buildJsonObject {
        put("schema_version", period.schemaVersion)
        put("load_input_sha256", period.loadInputSha256)
        put("recognition_method", period.recognitionMethod)
        put("first_sample_epoch_millis", period.firstSampleEpochMillis)
        put("last_sample_epoch_millis", period.lastSampleEpochMillis)
        val longest = period.longestIdleGapMillis
        if (longest == null) put("longest_idle_gap_millis", JsonNull) else put("longest_idle_gap_millis", longest)
        put("idle_gap_count", period.idleGapCount)
        put("status", period.status)
    }

internal fun validateRunPeriod(document: JsonObject): JsonObject {
    if (document.keys != RUN_PERIOD_FIELDS) periodInvalid("period fields differ")
    if (document.periodString("schema_version") != RUN_PERIOD_SCHEMA_VERSION) periodInvalid("schema_version differs")
    if (document.periodString("recognition_method") != RUN_PERIOD_RECOGNITION_METHOD) periodInvalid("recognition_method differs")
    if (!RUN_PERIOD_SHA256.matches(document.periodString("load_input_sha256"))) periodInvalid("load_input_sha256 is invalid")
    val first = document.periodLong("first_sample_epoch_millis")
    val last = document.periodLong("last_sample_epoch_millis")
    val longest = document.optionalPeriodLong("longest_idle_gap_millis")
    val count = document.periodLong("idle_gap_count")
    val status = document.periodString("status")
    if (first !in 0..MAX_TIMESTAMP_EPOCH_MILLIS || last !in 0..MAX_TIMESTAMP_EPOCH_MILLIS) periodInvalid("period epochs are out of range")
    if (longest != null && longest !in ONE_SECOND_MILLIS..MAX_TIMESTAMP_EPOCH_MILLIS) periodInvalid("longest idle gap is out of range")
    if (count !in 0..Int.MAX_VALUE.toLong()) periodInvalid("idle gap count is out of range")
    if (status !in RUN_PERIOD_STATUSES) periodInvalid("status is unsupported")
    if (status == RUN_PERIOD_STATUS_RECOGNIZED && last < first) periodInvalid("period ends before it starts")
    if (status != RUN_PERIOD_STATUS_RECOGNIZED && (first != 0L || last != 0L || longest != null || count != 0L)) {
        periodInvalid("unrecognized period carries facts")
    }
    if ((longest != null) != (count >= 1)) periodInvalid("idle gap facts disagree")
    return document
}

private fun unrecognized(
    loadInputSha256: String,
    status: String,
) = RunPeriodV1(RUN_PERIOD_SCHEMA_VERSION, loadInputSha256, RUN_PERIOD_RECOGNITION_METHOD, 0L, 0L, null, 0, status)

private fun JsonObject.periodString(name: String): String {
    val value = this[name] as? JsonPrimitive ?: periodInvalid("$name must be a string")
    if (!value.isString) periodInvalid("$name must be a string")
    return value.content
}

private fun JsonObject.periodLong(name: String): Long {
    val value = this[name] as? JsonPrimitive ?: periodInvalid("$name must be an integer")
    if (value.isString) periodInvalid("$name must be an integer")
    return value.longOrNull ?: periodInvalid("$name must be an integer")
}

private fun JsonObject.optionalPeriodLong(name: String): Long? {
    val value = this[name] ?: periodInvalid("$name is missing")
    if (value is JsonNull) return null
    if (value !is JsonPrimitive || value.isString) periodInvalid("$name must be an integer or null")
    return value.longOrNull ?: periodInvalid("$name must be an integer or null")
}

private fun periodInvalid(message: String): Nothing = throw IllegalArgumentException(message)

private const val ONE_SECOND_MILLIS = 1_000L
private const val RECOGNITION_RUN_ID = "run"
private val RUN_PERIOD_FIELDS =
    setOf(
        "first_sample_epoch_millis",
        "idle_gap_count",
        "last_sample_epoch_millis",
        "load_input_sha256",
        "longest_idle_gap_millis",
        "recognition_method",
        "schema_version",
        "status",
    )
private val RUN_PERIOD_STATUSES = setOf(RUN_PERIOD_STATUS_RECOGNIZED, RUN_PERIOD_STATUS_INVALID_INPUT, RUN_PERIOD_STATUS_NO_SAMPLES)
private val RUN_PERIOD_SHA256 = Regex("[0-9a-f]{64}")

package io.ltverdict.ingest

import kotlinx.serialization.Serializable

internal enum class SampleKind {
    JMETER_SAMPLER,
    JMETER_CONTAINER,
    GATLING_REQUEST,
    GATLING_GROUP,
}

@Serializable
internal enum class RunValidity {
    VALID,
    DEGRADED,
    INVALID,
}

internal data class Diagnostic(
    val code: String,
    val message: String,
    val sourceOffset: Long? = null,
)

internal data class ParseReport(
    val validity: RunValidity,
    val processedBytes: Long,
    val diagnostics: List<Diagnostic>,
)

internal data class LoadSample(
    val startedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val label: String,
    val groupPath: List<String>,
    val kind: SampleKind,
    val successful: Boolean,
    // W2.6: only a failed sample carries them (the response code as the source wrote it; the failure text, or the response
    // message when there is no failure text). A successful sample keeps both null so a long run holds no extra strings.
    val responseCode: String? = null,
    val failureMessage: String? = null,
) {
    val endedAtEpochMillis: Long

    init {
        if (startedAtEpochMillis < 0 || elapsedMillis < 0) invalidTimestamp()
        if (startedAtEpochMillis in TIMESTAMP_UNIT_SUSPECT_RANGE) invalidTimestamp()
        endedAtEpochMillis =
            try {
                Math.addExact(startedAtEpochMillis, elapsedMillis)
            } catch (_: ArithmeticException) {
                invalidTimestamp()
            }
        if (endedAtEpochMillis > MAX_TIMESTAMP_EPOCH_MILLIS) invalidTimestamp()
    }
}

internal const val MAX_TIMESTAMP_EPOCH_MILLIS = 253_402_300_799_999L

// Как epoch-millis диапазон означает 1970-01-12..1973-03-03, чего в результатах нагрузочных
// тестов не бывает; как epoch-seconds он покрывает 2001..5138 год, то есть любую реальную
// запись с `timestamp_format` в секундах.
internal val TIMESTAMP_UNIT_SUSPECT_RANGE = 1_000_000_000L..99_999_999_999L

private fun invalidTimestamp(): Nothing = throw IllegalArgumentException("INVALID_SAMPLE_TIMESTAMP")

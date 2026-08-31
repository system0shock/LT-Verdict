package io.ltverdict.ingest

internal enum class SampleKind {
    JMETER_SAMPLER,
    JMETER_CONTAINER,
    GATLING_REQUEST,
    GATLING_GROUP,
}

internal data class LoadSample(
    val startedAtEpochMillis: Long,
    val elapsedMillis: Long,
    val label: String,
    val groupPath: List<String>,
    val kind: SampleKind,
    val successful: Boolean,
) {
    val endedAtEpochMillis: Long

    init {
        if (startedAtEpochMillis < 0 || elapsedMillis < 0) invalidTimestamp()
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

private fun invalidTimestamp(): Nothing = throw IllegalArgumentException("INVALID_SAMPLE_TIMESTAMP")

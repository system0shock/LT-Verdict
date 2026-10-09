package io.ltverdict.ingest

import java.nio.file.Path

internal data class AcceptedInput(
    val runId: String,
    val sourceType: SourceType,
    val sha256: String,
    val sizeBytes: Long,
    val originalFilename: String,
    val path: Path,
    val acceptedAt: String? = null,
)

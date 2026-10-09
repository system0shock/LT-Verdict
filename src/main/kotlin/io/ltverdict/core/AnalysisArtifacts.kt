package io.ltverdict.core

import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/**
 * What the analysis service and the source analysis take from the storage: the published analyses and the recognized period of
 * a run. The core owns this port so that it does not depend on the storage; the storage implements it.
 */
internal interface AnalysisArtifacts {
    fun readAnalysis(
        runId: String,
        analysisId: String,
    ): StoredAnalysis?

    fun writeAnalysisAtomically(
        runId: String,
        analysisId: String,
        beforePublish: () -> Unit = {},
        writeStagingDirectory: (Path) -> Unit,
    ): Path

    fun readRunPeriod(runId: String): JsonObject?

    fun replaceRunPeriod(
        runId: String,
        period: JsonObject,
    ): JsonObject
}

internal data class StoredArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class StoredAnalysis(
    val path: Path,
    val artifacts: List<StoredArtifact>,
)

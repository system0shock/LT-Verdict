package io.ltverdict.storage

import io.ltverdict.core.AnalysisArtifacts
import io.ltverdict.core.WindowComparisonRequest
import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * The store of a data directory as one object: runs and analyses ([AnalysisStore]), baselines ([BaselineStore]) and releases
 * ([ReleaseStore]) over the same [DataDirectory], so that they share its lock. Callers and tests keep this one interface.
 */
internal class RunBundleStore(
    dataDirectory: DataDirectory,
    clock: Clock = Clock.systemUTC(),
) : AnalysisArtifacts {
    private val analyses = AnalysisStore(dataDirectory, clock)
    private val baselines = BaselineStore(dataDirectory, analyses)
    private val releases = ReleaseStore(dataDirectory)

    fun acceptInput(
        source: InputStream,
        originalFilename: String,
        maxBytes: Long = 4_294_967_296L,
    ): AcceptedInput = analyses.acceptInput(source, originalFilename, maxBytes)

    fun requireInput(runId: String): AcceptedInput = analyses.requireInput(runId)

    fun listRuns(
        afterRunId: String?,
        limit: Int,
    ): RunPage = analyses.listRuns(afterRunId, limit)

    override fun readAnalysis(
        runId: String,
        analysisId: String,
    ): StoredAnalysis? = analyses.readAnalysis(runId, analysisId)

    fun listAnalyses(
        runId: String,
        afterAnalysisId: String?,
        limit: Int,
    ): AnalysisPage = analyses.listAnalyses(runId, afterAnalysisId, limit)

    override fun writeAnalysisAtomically(
        runId: String,
        analysisId: String,
        beforePublish: () -> Unit,
        writeStagingDirectory: (Path) -> Unit,
    ): Path = analyses.writeAnalysisAtomically(runId, analysisId, beforePublish, writeStagingDirectory)

    fun readBaseline(): JsonObject? = baselines.readBaseline()

    fun replaceBaseline(selection: JsonObject): JsonObject = baselines.replaceBaseline(selection)

    fun readBaselineCondition(
        baselineReference: JsonObject,
        currentReference: JsonObject,
        windows: WindowComparisonRequest?,
    ): JsonObject? = baselines.readBaselineCondition(baselineReference, currentReference, windows)

    fun replaceBaselineCondition(condition: JsonObject): JsonObject = baselines.replaceBaselineCondition(condition)

    fun clearBaseline() = baselines.clearBaseline()

    fun listBaselineSlots(): List<BaselineSlot> = baselines.listBaselineSlots()

    fun readBaselineSlotWithCondition(
        series: String?,
        arm: String?,
        currentReference: JsonObject,
        windows: WindowComparisonRequest?,
    ): Pair<BaselineSlot?, JsonObject?> = baselines.readBaselineSlotWithCondition(series, arm, currentReference, windows)

    fun replaceBaselineSlot(
        selection: JsonObject,
        arm: String?,
    ): BaselineSlot = baselines.replaceBaselineSlot(selection, arm)

    fun clearBaselineSlot(
        series: String,
        arm: String?,
    ): Boolean = baselines.clearBaselineSlot(series, arm)

    fun readAnalysisIdentity(
        runId: String,
        analysisId: String,
    ): JsonObject? = analyses.readAnalysisIdentity(runId, analysisId)

    override fun readRunPeriod(runId: String): JsonObject? = analyses.readRunPeriod(runId)

    override fun replaceRunPeriod(
        runId: String,
        period: JsonObject,
    ): JsonObject = analyses.replaceRunPeriod(runId, period)

    fun createRelease(
        draft: JsonObject,
        now: Instant,
        suffix: () -> String = ::randomReleaseSuffix,
    ): JsonObject = releases.createRelease(draft, now, suffix)

    fun readRelease(id: String): JsonObject? = releases.readRelease(id)

    fun replaceRelease(
        id: String,
        update: (JsonObject) -> JsonObject,
    ): JsonObject = releases.replaceRelease(id, update)

    fun deleteRelease(id: String): Boolean = releases.deleteRelease(id)

    fun listReleases(
        series: String?,
        afterReleaseId: String?,
        limit: Int,
    ): ReleasePage = releases.listReleases(series, afterReleaseId, limit)

    fun findReleasesByAnalysis(analysisIds: Set<String>): ReleaseLookup = releases.findReleasesByAnalysis(analysisIds)

    fun analysisExists(
        runId: String,
        analysisId: String,
    ): Boolean = analyses.analysisExists(runId, analysisId)

    fun analysisState(
        runId: String,
        analysisId: String,
    ): String = analyses.analysisState(runId, analysisId)

    fun readAnalysisDocuments(
        runId: String,
        analysisId: String,
    ): Pair<JsonObject, JsonObject>? = analyses.readAnalysisDocuments(runId, analysisId)

    fun readVerifiedAnalysis(
        runId: String,
        analysisId: String,
        maxResultBytes: Int = MAX_VERIFIED_RESULT_BYTES,
        outsideLock: () -> Unit = {},
    ): VerifiedAnalysis? = analyses.readVerifiedAnalysis(runId, analysisId, maxResultBytes, outsideLock)

    fun readPodViewBytes(
        runId: String,
        analysisId: String,
    ): ByteArray? = analyses.readPodViewBytes(runId, analysisId)

    fun readComparisonDocuments(
        runId: String,
        analysisId: String,
    ): ComparisonDocuments? = analyses.readComparisonDocuments(runId, analysisId)

    fun readComparisonHistory(
        limit: Int = 1000,
        byteLimit: Int = 16 * 1024 * 1024,
    ): ComparisonHistory = analyses.readComparisonHistory(limit, byteLimit)
}

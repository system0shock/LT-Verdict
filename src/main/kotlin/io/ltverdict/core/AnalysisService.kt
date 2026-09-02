package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.parseInput
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsResourceLimitExceeded
import io.ltverdict.metrics.NormalizedBucket
import io.ltverdict.metrics.toJsonObject
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal data class AnalysisRequest(
    val input: AcceptedInput,
    val policy: PolicyValidation.Valid?,
    val mode: AnalysisMode = AnalysisMode.STANDARD,
)

internal data class AnalysisOutcome(
    val runId: String,
    val analysisId: String,
    val canonicalResult: ByteArray,
    val analysisDirectory: Path,
)

internal class AnalysisService(
    private val store: RunBundleStore,
    private val engineConfig: EngineConfig,
) {
    fun analyze(
        request: AnalysisRequest,
        processedBytes: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
    ): AnalysisOutcome {
        if (request.mode != AnalysisMode.STANDARD) throw IllegalArgumentException("UNSUPPORTED_ANALYSIS_MODE")
        checkCancelled()

        val identity = analysisIdentity(request.input, request.policy, engineConfig)
        val analysisId = sha256Hex(identity)
        store.readAnalysis(request.input.runId, analysisId)?.let { stored ->
            processedBytes(request.input.sizeBytes)
            return AnalysisOutcome(
                request.input.runId,
                analysisId,
                Files.readAllBytes(stored.path.resolve(RESULT_FILE)),
                stored.path,
            )
        }

        fun invalidOutcome(diagnostics: List<Diagnostic>): AnalysisOutcome {
            processedBytes(request.input.sizeBytes)
            checkCancelled()
            val evaluation = evaluatePolicy(request.policy?.policy, RunValidity.INVALID, null, diagnostics)
            val result = analysisResult(request.input.runId, RunValidity.INVALID, evaluation)
            val directory =
                store.writeAnalysisAtomically(request.input.runId, analysisId) { staging ->
                    checkCancelled()
                    Files.write(staging.resolve(IDENTITY_FILE), identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    Files.write(staging.resolve(RESULT_FILE), result, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
            return AnalysisOutcome(request.input.runId, analysisId, result, directory)
        }

        var firstStart: Long? = null
        var firstEnd: Long? = null
        val first =
            parseInput(
                request.input,
                { sample ->
                    firstStart = minOf(firstStart ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    firstEnd = maxOf(firstEnd ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                },
                { bytes -> processedBytes(minOf(bytes, request.input.sizeBytes) / 2) },
                checkCancelled,
            )

        if (first.validity == RunValidity.INVALID) {
            return invalidOutcome(first.diagnostics)
        }

        val runStart = firstStart ?: error("PARSER_PASS_MISMATCH")
        val runEnd = firstEnd ?: error("PARSER_PASS_MISMATCH")
        val accumulator = MetricsAccumulator(runStart, runEnd, engineConfig.metrics)
        var secondStart: Long? = null
        var secondEnd: Long? = null
        val second =
            try {
                parseInput(
                    request.input,
                    { sample ->
                        secondStart = minOf(secondStart ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                        secondEnd = maxOf(secondEnd ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                        accumulator.record(sample)
                    },
                    { bytes ->
                        val bounded = minOf(bytes, request.input.sizeBytes)
                        processedBytes(request.input.sizeBytes / 2 + (bounded + 1) / 2)
                    },
                    checkCancelled,
                )
            } catch (_: MetricsResourceLimitExceeded) {
                return invalidOutcome(listOf(Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Metric resource limit exceeded")))
            }
        if (second.validity != first.validity ||
            second.diagnostics != first.diagnostics ||
            secondStart != runStart ||
            secondEnd != runEnd
        ) {
            error("PARSER_PASS_MISMATCH")
        }

        val metrics = accumulator.finish()
        val evaluation = evaluatePolicy(request.policy?.policy, first.validity, metrics, first.diagnostics)
        val result = analysisResult(request.input.runId, first.validity, evaluation)
        val run = runMetadata(request.input, runStart, runEnd)
        checkCancelled()
        val directory =
            store.writeAnalysisAtomically(request.input.runId, analysisId) { staging ->
                listOf(
                    IDENTITY_FILE to identity,
                    RUN_FILE to run,
                    RESULT_FILE to result,
                ).forEach { (name, bytes) ->
                    checkCancelled()
                    Files.write(staging.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                writeBuckets(staging.resolve(NORMALIZED_FILE), metrics.oneSecondBuckets, checkCancelled)
                ROLLUPS.forEach { seconds ->
                    writeBuckets(
                        staging.resolve("rollup-${seconds}s.ndjson"),
                        metrics.rollups.getValue(seconds),
                        checkCancelled,
                    )
                }
            }
        return AnalysisOutcome(request.input.runId, analysisId, result, directory)
    }
}

private fun runMetadata(
    input: AcceptedInput,
    runStart: Long,
    runEnd: Long,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "run.v1")
            put("run_id", input.runId)
            put("analysis_mode", AnalysisMode.STANDARD.wireName)
            put("started_at", Instant.ofEpochMilli(runStart).toString())
            put("ended_at", Instant.ofEpochMilli(runEnd).toString())
            put(
                "inputs",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", input.sourceType.wireName)
                            put("path", "inputs/source.bin")
                            put("sha256", input.sha256)
                        },
                    )
                },
            )
        },
    )

private fun writeBuckets(
    path: Path,
    buckets: List<NormalizedBucket>,
    checkCancelled: () -> Unit,
) {
    checkCancelled()
    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).buffered().use { output ->
        buckets.forEach { bucket ->
            checkCancelled()
            output.write(canonicalJson(bucket.toJsonObject()))
            output.write('\n'.code)
        }
    }
}

private val ROLLUPS = listOf(10, 30, 60)
private const val IDENTITY_FILE = "identity.json"
private const val RUN_FILE = "run.json"
private const val RESULT_FILE = "analysis-result.json"
private const val NORMALIZED_FILE = "normalized-1s.ndjson"

package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.parseInput
import io.ltverdict.metrics.MetricWindow
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsResourceLimitExceeded
import io.ltverdict.metrics.NormalizedBucket
import io.ltverdict.metrics.TransactionIdentity
import io.ltverdict.metrics.WindowMetricsAccumulator
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
    val resources: ResourceValidation.Valid? = null,
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
        request.resources?.let { resources ->
            require(resources.snapshot.loadInputSha256 == request.input.sha256) { "RESOURCE_LOAD_HASH_MISMATCH" }
        }

        val identity = analysisIdentity(request.input, request.policy, engineConfig, request.resources)
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
            val resourceBytes = request.resources?.rawBytes()
            val directory =
                store.writeAnalysisAtomically(request.input.runId, analysisId) { staging ->
                    checkCancelled()
                    Files.write(staging.resolve(IDENTITY_FILE), identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    Files.write(staging.resolve(RESULT_FILE), result, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    resourceBytes?.let {
                        Files.write(staging.resolve(RESOURCE_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                }
            return AnalysisOutcome(request.input.runId, analysisId, result, directory)
        }

        var firstStart: Long? = null
        var firstEnd: Long? = null
        val requestedTransactions =
            if (request.resources == null) {
                emptySet()
            } else {
                request.policy
                    ?.policy
                    ?.rules
                    .orEmpty()
                    .mapNotNull { (it.scope as? PolicyScope.Transaction)?.name }
                    .toSet()
            }
        val transactionCandidates = requestedTransactions.associateWith { mutableListOf<TransactionIdentity>() }
        val first =
            parseInput(
                request.input,
                { sample ->
                    firstStart = minOf(firstStart ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    firstEnd = maxOf(firstEnd ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                    transactionCandidates[sample.label]?.let { candidates ->
                        val identity = TransactionIdentity(sample.groupPath.toList(), sample.label, sample.kind)
                        if (identity !in candidates && candidates.size < 2) candidates += identity
                    }
                },
                { bytes -> processedBytes(minOf(bytes, request.input.sizeBytes) / 2) },
                checkCancelled,
            )

        if (first.validity == RunValidity.INVALID) {
            return invalidOutcome(first.diagnostics)
        }

        val runStart = firstStart ?: error("PARSER_PASS_MISMATCH")
        val runEnd = firstEnd ?: error("PARSER_PASS_MISMATCH")
        val resourceWindows =
            request.resources?.let { resolveResourceWindows(it.snapshot, request.input.sha256, runStart, runEnd) }
        val accumulator = MetricsAccumulator(runStart, runEnd, engineConfig.metrics)
        val windowAccumulator =
            try {
                resourceWindows?.let { windows ->
                    WindowMetricsAccumulator(
                        windows.map { MetricWindow(it.id, it.fromEpochMillis, it.toEpochMillis) },
                        transactionCandidates.values.flatten().toSet(),
                        engineConfig.metrics,
                    )
                }
            } catch (_: MetricsResourceLimitExceeded) {
                return invalidOutcome(listOf(Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Metric resource limit exceeded")))
            }
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
                        windowAccumulator?.record(sample)
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
        val evaluation =
            if (request.resources == null) {
                evaluatePolicy(request.policy?.policy, first.validity, metrics, first.diagnostics)
            } else {
                val windows = checkNotNull(resourceWindows)
                val resourceEvaluation =
                    evaluateResources(request.resources.snapshot, windows, checkCancelled).let { evaluated ->
                        evaluated.copy(
                            evidence =
                                listOf(
                                    resourceBindingEvidence(request.resources.snapshot, windows, runStart, runEnd),
                                ) + evaluated.evidence,
                        )
                    }
                evaluateSharedWindowPolicy(
                    request.policy?.policy,
                    first.validity,
                    metrics,
                    checkNotNull(windowAccumulator).finish(checkCancelled),
                    resourceEvaluation,
                    windows,
                    first.diagnostics,
                )
            }
        val result = analysisResult(request.input.runId, first.validity, evaluation)
        val resourceBytes = request.resources?.rawBytes()
        val run = runMetadata(request.input, runStart, runEnd, resourceBytes?.let(::sha256Hex))
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
                resourceBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(RESOURCE_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
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
    resourceSha256: String? = null,
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
                    resourceSha256?.let { sha256 ->
                        add(
                            buildJsonObject {
                                put("type", "resource_snapshot")
                                put("path", RESOURCE_FILE)
                                put("sha256", sha256)
                            },
                        )
                    }
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
private const val RESOURCE_FILE = "resource-snapshot.json"

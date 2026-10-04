package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.parseInput
import io.ltverdict.metrics.MetricWindow
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.MetricsResourceLimitExceeded
import io.ltverdict.metrics.NormalizedBucket
import io.ltverdict.metrics.TransactionIdentity
import io.ltverdict.metrics.UtcLoadMetricsAccumulator
import io.ltverdict.metrics.WindowMetricsAccumulator
import io.ltverdict.metrics.byteSize
import io.ltverdict.metrics.toJsonObject
import io.ltverdict.sources.PostgresAnalysisInput
import io.ltverdict.sources.SourceAcquisition
import io.ltverdict.sources.WindowedSourceRequest
import io.ltverdict.sources.comparePostgresPhases
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

internal data class AnalysisRequest(
    val input: AcceptedInput,
    val policy: PolicyValidation.Valid?,
    val mode: AnalysisMode? = null,
    val resources: ResourceValidation.Valid? = null,
    val diagnostics: DiagnosticValidation.Valid? = null,
    val sourceRequest: WindowedSourceRequest? = null,
    val sourceAcquisition: SourceAcquisition? = null,
    val postgres: PostgresAnalysisInput? = null,
    val capacity: CapacityPlanValidation.Valid? = null,
    val trend: TrendPlanValidation.Valid? = null,
)

internal data class AnalysisOutcome(
    val runId: String,
    val analysisId: String,
    val canonicalResult: ByteArray,
    val analysisDirectory: Path,
)

internal class AnalysisService(
    internal val store: RunBundleStore,
    private val engineConfig: EngineConfig,
) {
    fun analyze(
        request: AnalysisRequest,
        processedBytes: (Long) -> Unit = {},
        checkCancelled: () -> Unit = {},
        beforePublish: () -> Unit = checkCancelled,
    ): AnalysisOutcome {
        val mode =
            when {
                request.capacity == null && request.mode == AnalysisMode.CAPACITY_STEP ->
                    throw IllegalArgumentException("CAPACITY_PLAN_REQUIRED")

                request.capacity != null && request.mode == AnalysisMode.STANDARD ->
                    throw IllegalArgumentException("CAPACITY_MODE_CONFLICT")

                request.capacity != null -> AnalysisMode.CAPACITY_STEP
                else -> AnalysisMode.STANDARD
            }
        checkCancelled()
        require(request.sourceRequest == null) { "SOURCE_ACQUISITION_REQUIRED" }
        request.sourceAcquisition?.let {
            require(request.resources?.semanticSha256 == it.snapshot?.semanticSha256) { "SOURCE_SNAPSHOT_MISMATCH" }
        }
        request.resources?.let { resources ->
            require(resources.snapshot.loadInputSha256 == request.input.sha256) { "RESOURCE_LOAD_HASH_MISMATCH" }
        }
        request.capacity?.let { capacity ->
            val resources = request.resources ?: throw IllegalArgumentException("CAPACITY_RESOURCE_REQUIRED")
            validateCapacityBinding(capacity, request.input.sha256, resources).firstOrNull()?.let {
                throw IllegalArgumentException(it.code)
            }
        }
        request.diagnostics?.let { diagnostics ->
            val resources = request.resources ?: throw IllegalArgumentException("DIAGNOSTIC_RESOURCE_REQUIRED")
            validateDiagnosticBinding(diagnostics, resources).firstOrNull()?.let { throw IllegalArgumentException(it.code) }
        }
        request.trend?.let { trend ->
            val resources = request.resources ?: throw IllegalArgumentException("TREND_RESOURCE_REQUIRED")
            validateTrendBinding(trend, resources).firstOrNull()?.let { throw IllegalArgumentException(it.code) }
        }
        val postgres = request.postgres?.let(::revalidatePostgresInput)

        val acquisitionHash =
            request.sourceAcquisition?.let { acquisition ->
                sha256Hex(
                    canonicalJson(
                        buildJsonObject {
                            put("evidence", acquisition.evidence)
                            if (acquisition.contextEvidence.isNotEmpty()) put("context_evidence", JsonArray(acquisition.contextEvidence))
                            put(
                                "artifacts",
                                buildJsonObject {
                                    acquisition.artifacts.toSortedMap().forEach { (name, bytes) -> put(name, sha256Hex(bytes)) }
                                },
                            )
                        },
                    ),
                )
            }
        val postgresHash = postgres?.let(::postgresInputSha256)
        val identity =
            analysisIdentity(
                request.input,
                request.policy,
                engineConfig,
                request.resources,
                request.diagnostics,
                acquisitionHash,
                postgresHash,
                request.capacity,
                request.trend,
            )
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

        val invalidPostgresContext = postgres?.let { unavailablePostgresContext(it, request.input.sha256) }

        fun invalidOutcome(diagnostics: List<Diagnostic>): AnalysisOutcome {
            processedBytes(request.input.sizeBytes)
            checkCancelled()
            var evaluation = evaluatePolicy(request.policy?.policy, RunValidity.INVALID, null, diagnostics)
            request.diagnostics?.let { plan ->
                val diagnostic = diagnosticUnavailable(plan.plan, "NOT_EVALUATED", "RUN_NOT_VALID")
                evaluation =
                    evaluation.copy(
                        findings = evaluation.findings + diagnostic.findings,
                        evidence =
                            evaluation.evidence + diagnostic.evidence,
                    )
            }
            val trend = request.trend?.let { trendUnavailable(it.plan, "RUN_NOT_VALID") }
            trend?.let {
                evaluation =
                    evaluation.copy(
                        findings = evaluation.findings + it.findings,
                        evidence = evaluation.evidence + it.evidence,
                    )
            }
            request.sourceAcquisition?.let {
                evaluation =
                    evaluation.copy(
                        coverageReasons = (evaluation.coverageReasons + sourceCoverageReasons(it.evidence)).distinct(),
                        evidence = evaluation.evidence + it.evidence + it.contextEvidence,
                    )
            }
            invalidPostgresContext?.let { evaluation = evaluation.copy(evidence = evaluation.evidence + it) }
            val capacity =
                request.capacity?.let {
                    evaluateCapacity(
                        it.plan,
                        checkNotNull(request.resources).snapshot,
                        io.ltverdict.metrics.UtcLoadMetrics(emptyMap()),
                        RunValidity.INVALID,
                        evaluation,
                        checkCancelled,
                    )
                }
            capacity?.let {
                evaluation =
                    evaluation.copy(
                        coverageReasons = (evaluation.coverageReasons + it.coverageReasons).distinct(),
                        evidence = evaluation.evidence + it.evidence,
                    )
            }
            val result = analysisResult(request.input.runId, RunValidity.INVALID, evaluation, mode, capacity)
            val resourceBytes = request.resources?.rawBytes()
            val diagnosticBytes = request.diagnostics?.rawBytes()
            val capacityBytes = capacity?.let { canonicalJson(it.capacityJson) }
            val capacityPlanBytes = request.capacity?.rawBytes()
            val trendBytes = trend?.let { canonicalJson(it.trendJson) }
            val trendPlanBytes = request.trend?.rawBytes()
            val directory =
                store.writeAnalysisAtomically(request.input.runId, analysisId, beforePublish) { staging ->
                    checkCancelled()
                    writeAcquisition(staging, request.sourceAcquisition, checkCancelled)
                    postgres?.let { writePostgres(staging, it, requireNotNull(invalidPostgresContext), checkCancelled) }
                    Files.write(staging.resolve(IDENTITY_FILE), identity, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    request.policy?.let {
                        Files.write(
                            staging.resolve(POLICY_FILE),
                            it.canonicalBytes,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                        )
                    }
                    Files.write(staging.resolve(RESULT_FILE), result, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    resourceBytes?.let {
                        Files.write(staging.resolve(RESOURCE_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                    diagnosticBytes?.let {
                        Files.write(staging.resolve(DIAGNOSTIC_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                    capacityPlanBytes?.let {
                        Files.write(staging.resolve(CAPACITY_PLAN_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                    capacityBytes?.let {
                        Files.write(staging.resolve(CAPACITY_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                    trendPlanBytes?.let {
                        Files.write(staging.resolve(TREND_PLAN_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }
                    trendBytes?.let {
                        Files.write(staging.resolve(TREND_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
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
        var retainedCandidateBytes = 0L
        val first =
            try {
                parseInput(
                    request.input,
                    { sample ->
                        firstStart = minOf(firstStart ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                        firstEnd = maxOf(firstEnd ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                        transactionCandidates[sample.label]?.let { candidates ->
                            if (candidates.size < 2) {
                                val identity = TransactionIdentity(sample.groupPath, sample.label, sample.kind)
                                if (identity in candidates) return@let
                                val bytes = identity.byteSize()
                                if (bytes > engineConfig.metrics.maxTransactionIdentityBytes ||
                                    bytes > engineConfig.metrics.maxTotalTransactionIdentityBytes - retainedCandidateBytes
                                ) {
                                    throw MetricsResourceLimitExceeded()
                                }
                                retainedCandidateBytes += bytes
                                candidates += identity.copy(groupPath = identity.groupPath.toList())
                            }
                        }
                    },
                    { bytes -> processedBytes(minOf(bytes, request.input.sizeBytes) / 2) },
                    checkCancelled,
                )
            } catch (_: MetricsResourceLimitExceeded) {
                return invalidOutcome(listOf(Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Metric resource limit exceeded")))
            }

        if (first.validity == RunValidity.INVALID) {
            return invalidOutcome(first.diagnostics)
        }

        val runStart = firstStart ?: error("PARSER_PASS_MISMATCH")
        val runEnd = firstEnd ?: error("PARSER_PASS_MISMATCH")
        val resourceWindows =
            request.resources?.let { resolveResourceWindows(it.snapshot, request.input.sha256, runStart, runEnd) }
        request.diagnostics?.let { diagnostics ->
            validateDiagnosticResolvedWindows(diagnostics, checkNotNull(resourceWindows)).firstOrNull()?.let {
                throw IllegalArgumentException(it.code)
            }
        }
        val accumulator = MetricsAccumulator(runStart, runEnd, engineConfig.metrics)
        val retainedTransactions = transactionCandidates.values.flatten().toSet()
        val windowAccumulator =
            try {
                resourceWindows?.let { windows ->
                    WindowMetricsAccumulator(
                        windows.map { MetricWindow(it.id, it.fromEpochMillis, it.toEpochMillis) },
                        retainedTransactions,
                        engineConfig.metrics,
                    )
                }
            } catch (_: MetricsResourceLimitExceeded) {
                return invalidOutcome(listOf(Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Metric resource limit exceeded")))
            }
        val resourceWindowHistograms = resourceWindows?.size?.times(retainedTransactions.size + 1) ?: 0
        val capacityWindows =
            request.capacity
                ?.plan
                ?.stages
                ?.map(CapacityStageV1::evaluationWindowId)
                ?.distinct()
                ?.let { ids -> checkNotNull(resourceWindows).filter { it.id in ids } }
                .orEmpty()
        val capacityWindowHistograms =
            capacityWindows.sumOf { ((it.toEpochMillis - it.fromEpochMillis) / 10_000L).toInt() }
        val capacityAccumulator =
            try {
                request.capacity?.let {
                    UtcLoadMetricsAccumulator(
                        capacityWindows.map { MetricWindow(it.id, it.fromEpochMillis, it.toEpochMillis) },
                        10_000L,
                        resourceWindowHistograms,
                        engineConfig.metrics,
                    )
                }
            } catch (_: MetricsResourceLimitExceeded) {
                return invalidOutcome(listOf(Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Metric resource limit exceeded")))
            }
        var diagnosticLimitExceeded = false
        val diagnosticAccumulator =
            try {
                if (request.diagnostics == null) {
                    null
                } else {
                    val windows = checkNotNull(resourceWindows)
                    val selectedWindowIds =
                        request.diagnostics.plan.pairs
                            .flatMap(DiagnosticPairV1::windowIds)
                            .toSet() +
                            request.diagnostics.plan.anomalies
                                .flatMap { listOf(it.referenceWindowId, it.windowId) }
                    UtcLoadMetricsAccumulator(
                        windows.filter { it.id in selectedWindowIds }.map { MetricWindow(it.id, it.fromEpochMillis, it.toEpochMillis) },
                        checkNotNull(request.resources).snapshot.stepMillis,
                        resourceWindowHistograms + capacityWindowHistograms,
                        engineConfig.metrics,
                    )
                }
            } catch (_: MetricsResourceLimitExceeded) {
                diagnosticLimitExceeded = true
                null
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
                        capacityAccumulator?.record(sample)
                        diagnosticAccumulator?.record(sample)
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
        val postgresContext =
            postgres?.let {
                JsonObject(
                    comparePostgresPhases(it.pre, it.post, request.input.sha256, runStart, runEnd) +
                        ("pg_profile_html_sha256" to (it.pgProfileHtml?.let { bytes -> JsonPrimitive(sha256Hex(bytes)) } ?: JsonNull)),
                )
            }

        val metrics = accumulator.finish()
        val finishedWindowMetrics = windowAccumulator?.finish(checkCancelled)
        var evaluation =
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
                    checkNotNull(finishedWindowMetrics),
                    resourceEvaluation,
                    windows,
                    first.diagnostics,
                )
            }
        request.diagnostics?.let { diagnostics ->
            val diagnostic =
                if (diagnosticLimitExceeded) {
                    diagnosticUnavailable(diagnostics.plan, "LIMIT_EXCEEDED", "DIAGNOSTIC_CELL_LIMIT_EXCEEDED")
                } else {
                    evaluateDiagnostics(
                        diagnostics,
                        checkNotNull(request.resources),
                        checkNotNull(resourceWindows),
                        checkNotNull(diagnosticAccumulator).finish(checkCancelled),
                        checkNotNull(finishedWindowMetrics),
                        checkCancelled,
                    )
                }
            evaluation =
                evaluation.copy(
                    findings = evaluation.findings + diagnostic.findings,
                    evidence = evaluation.evidence + diagnostic.evidence,
                )
        }
        val trend =
            request.trend?.let {
                evaluateTrend(it.plan, checkNotNull(request.resources).snapshot, checkNotNull(resourceWindows), checkCancelled)
            }
        trend?.let {
            evaluation =
                evaluation.copy(
                    findings = evaluation.findings + it.findings,
                    evidence = evaluation.evidence + it.evidence,
                )
        }
        request.sourceAcquisition?.let {
            evaluation =
                evaluation.copy(
                    coverageReasons = (evaluation.coverageReasons + sourceCoverageReasons(it.evidence)).distinct(),
                    evidence = evaluation.evidence + it.evidence + it.contextEvidence,
                )
        }
        postgresContext?.let { evaluation = evaluation.copy(evidence = evaluation.evidence + it) }
        val capacity =
            request.capacity?.let {
                evaluateCapacity(
                    it.plan,
                    checkNotNull(request.resources).snapshot,
                    checkNotNull(capacityAccumulator).finish(checkCancelled),
                    first.validity,
                    evaluation,
                    checkCancelled,
                )
            }
        capacity?.let {
            evaluation =
                evaluation.copy(
                    coverageReasons = (evaluation.coverageReasons + it.coverageReasons).distinct(),
                    evidence = evaluation.evidence + it.evidence,
                )
        }
        val result = analysisResult(request.input.runId, first.validity, evaluation, mode, capacity)
        val resourceBytes = request.resources?.rawBytes()
        val diagnosticBytes = request.diagnostics?.rawBytes()
        val capacityPlanBytes = request.capacity?.rawBytes()
        val capacityBytes = capacity?.let { canonicalJson(it.capacityJson) }
        val trendPlanBytes = request.trend?.rawBytes()
        val trendBytes = trend?.let { canonicalJson(it.trendJson) }
        val run =
            runMetadata(
                request.input,
                runStart,
                runEnd,
                analysisId,
                mode,
                resourceBytes?.let(::sha256Hex),
                diagnosticBytes?.let(::sha256Hex),
                capacityPlanBytes?.let(::sha256Hex),
                trendPlanBytes?.let(::sha256Hex),
            )
        checkCancelled()
        val directory =
            store.writeAnalysisAtomically(request.input.runId, analysisId, beforePublish) { staging ->
                writeAcquisition(staging, request.sourceAcquisition, checkCancelled)
                postgres?.let { writePostgres(staging, it, requireNotNull(postgresContext), checkCancelled) }
                listOf(
                    IDENTITY_FILE to identity,
                    RUN_FILE to run,
                    RESULT_FILE to result,
                ).forEach { (name, bytes) ->
                    checkCancelled()
                    Files.write(staging.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                request.policy?.let {
                    checkCancelled()
                    Files.write(staging.resolve(POLICY_FILE), it.canonicalBytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                resourceBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(RESOURCE_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                diagnosticBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(DIAGNOSTIC_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                capacityPlanBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(CAPACITY_PLAN_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                capacityBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(CAPACITY_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                trendPlanBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(TREND_PLAN_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
                trendBytes?.let {
                    checkCancelled()
                    Files.write(staging.resolve(TREND_FILE), it, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
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

private fun revalidatePostgresInput(input: PostgresAnalysisInput): PostgresAnalysisInput =
    readPostgresAnalysisInput(
        pre = input.pre?.toString()?.byteInputStream(),
        post = input.post?.toString()?.byteInputStream(),
        pgProfileHtml = input.pgProfileHtml?.inputStream(),
    )

private fun postgresInputSha256(input: PostgresAnalysisInput): String =
    sha256Hex(
        canonicalJson(
            buildJsonObject {
                input.pre?.let { put("pre_sha256", sha256Hex(canonicalJson(it))) }
                input.post?.let { put("post_sha256", sha256Hex(canonicalJson(it))) }
                input.pgProfileHtml?.let { put("pg_profile_html_sha256", sha256Hex(it)) }
            },
        ),
    )

private fun unavailablePostgresContext(
    input: PostgresAnalysisInput,
    loadInputSha256: String,
): JsonObject {
    val reason = JsonArray(listOf(JsonPrimitive("PG_LOAD_WINDOW_UNAVAILABLE")))
    val profileIds = listOfNotNull(input.pre?.phaseString("profile_id"), input.post?.phaseString("profile_id")).distinct()
    return buildJsonObject {
        put("schema_version", "postgres-context.v1")
        put("type", "postgres_context")
        put("profile_id", profileIds.singleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
        put("load_input_sha256", loadInputSha256)
        put("start_epoch_ms", JsonNull)
        put("end_epoch_ms", JsonNull)
        put("pg_profile_html_sha256", input.pgProfileHtml?.let { JsonPrimitive(sha256Hex(it)) } ?: JsonNull)
        put("pre_sha256", input.pre?.let { JsonPrimitive(sha256Hex(canonicalJson(it))) } ?: JsonNull)
        put("post_sha256", input.post?.let { JsonPrimitive(sha256Hex(canonicalJson(it))) } ?: JsonNull)
        put("status", "DEGRADED")
        put("reasons", reason)
        put("tables", JsonArray(emptyList()))
        put("configuration_changes", JsonArray(emptyList()))
        put(
            "statements",
            buildJsonObject {
                put("status", "DEGRADED")
                put("reasons", reason)
                put("rows", JsonArray(emptyList()))
                put("unmatched_pre", JsonArray(emptyList()))
                put("unmatched_post", JsonArray(emptyList()))
            },
        )
        put(
            "pg_profile",
            buildJsonObject {
                put("status", "DEGRADED")
                put("reasons", reason)
                put("pre_report_sha256", input.pre.reportHash())
                put("post_report_sha256", input.post.reportHash())
            },
        )
    }
}

private fun JsonObject.phaseString(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject?.reportHash() = this?.getValue("pg_profile")?.jsonObject?.getValue("report_sha256") ?: JsonNull

private fun writePostgres(
    staging: Path,
    input: PostgresAnalysisInput,
    context: JsonObject,
    checkCancelled: () -> Unit,
) {
    val artifacts =
        listOfNotNull(
            input.pre?.let { POSTGRES_PRE_FILE to canonicalJson(it) },
            input.post?.let { POSTGRES_POST_FILE to canonicalJson(it) },
            POSTGRES_CONTEXT_FILE to canonicalJson(context),
            input.pgProfileHtml?.let { POSTGRES_PROFILE_FILE to it },
        )
    artifacts.forEach { (name, bytes) ->
        checkCancelled()
        Files.write(staging.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

private fun sourceCoverageReasons(summary: JsonObject): List<String> =
    buildList {
        when ((summary["status"] as? JsonPrimitive)?.contentOrNull) {
            "PARTIAL" -> add("SOURCE_ACQUISITION_PARTIAL")
            "FAILED" -> add("SOURCE_ACQUISITION_FAILED")
            else -> Unit
        }
        if ((summary["cap_exceeded"] as? JsonPrimitive)?.booleanOrNull == true) add("SOURCE_REQUEST_CAP_EXCEEDED")
    }

private fun writeAcquisition(
    staging: Path,
    acquisition: SourceAcquisition?,
    checkCancelled: () -> Unit,
) {
    acquisition?.artifacts?.forEach { (name, bytes) ->
        checkCancelled()
        require(
            name in setOf("source-acquisition.json", "opensearch-errors.json") ||
                Regex("source-response-[0-9]{1,4}\\.json").matches(name) ||
                Regex("opensearch-errors-([1-9]|1[0-6])\\.json").matches(name),
        ) {
            "SOURCE_ARTIFACT_NAME_INVALID"
        }
        Files.write(staging.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }
}

private fun runMetadata(
    input: AcceptedInput,
    runStart: Long,
    runEnd: Long,
    analysisId: String,
    mode: AnalysisMode,
    resourceSha256: String? = null,
    diagnosticSha256: String? = null,
    capacityPlanSha256: String? = null,
    trendPlanSha256: String? = null,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "run.v1")
            put("run_id", input.runId)
            put("analysis_mode", mode.wireName)
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
                                put("path", "analyses/$analysisId/$RESOURCE_FILE")
                                put("sha256", sha256)
                            },
                        )
                    }
                    diagnosticSha256?.let { sha256 ->
                        add(
                            buildJsonObject {
                                put("type", "correlation_plan")
                                put("path", "analyses/$analysisId/$DIAGNOSTIC_FILE")
                                put("sha256", sha256)
                            },
                        )
                    }
                    capacityPlanSha256?.let { sha256 ->
                        add(
                            buildJsonObject {
                                put("type", "capacity_plan")
                                put("path", "analyses/$analysisId/$CAPACITY_PLAN_FILE")
                                put("sha256", sha256)
                            },
                        )
                    }
                    trendPlanSha256?.let { sha256 ->
                        add(
                            buildJsonObject {
                                put("type", "trend_plan")
                                put("path", "analyses/$analysisId/$TREND_PLAN_FILE")
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
private const val POLICY_FILE = "policy.json"
private const val RUN_FILE = "run.json"
private const val RESULT_FILE = "analysis-result.json"
private const val NORMALIZED_FILE = "normalized-1s.ndjson"
private const val RESOURCE_FILE = "resource-snapshot.json"
private const val DIAGNOSTIC_FILE = "correlation-plan.json"
private const val CAPACITY_PLAN_FILE = "capacity-plan.json"
private const val CAPACITY_FILE = "capacity.json"
private const val TREND_PLAN_FILE = "trend-plan.json"
private const val TREND_FILE = "trend.json"
private const val POSTGRES_PRE_FILE = "postgres-pre.json"
private const val POSTGRES_POST_FILE = "postgres-post.json"
private const val POSTGRES_CONTEXT_FILE = "postgres-context.json"
private const val POSTGRES_PROFILE_FILE = "pg-profile.html"

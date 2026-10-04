package io.ltverdict.sources

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.MAX_RESOURCE_CELLS
import io.ltverdict.core.MAX_RESOURCE_RULES
import io.ltverdict.core.MAX_RESOURCE_SERIES
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.RunPeriodReadFailure
import io.ltverdict.core.RunPeriodV1
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.recognizeRunPeriod
import io.ltverdict.core.runPeriodFromJson
import io.ltverdict.core.runPeriodJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateResourceSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class PostgresAnalysisInput(
    val pre: JsonObject? = null,
    val post: JsonObject? = null,
    val pgProfileHtml: ByteArray? = null,
)

internal fun readPostgresAnalysisInput(
    pre: InputStream? = null,
    post: InputStream? = null,
    pgProfileHtml: InputStream? = null,
): PostgresAnalysisInput {
    require(pre != null || post != null || pgProfileHtml != null) { "PG_ANALYSIS_INPUT_MISSING" }
    val validatedPre = pre?.let(::validatePostgresPhase)
    require(validatedPre == null || validatedPre.getValue("phase").jsonPrimitive.content == "pre") {
        "PG_PRE_PHASE_INVALID"
    }
    val validatedPost = post?.let(::validatePostgresPhase)
    require(validatedPost == null || validatedPost.getValue("phase").jsonPrimitive.content == "post") {
        "PG_POST_PHASE_INVALID"
    }
    val html = pgProfileHtml?.let(::readPostgresHtml)
    val declaredReportHash =
        (validatedPost ?: validatedPre)
            ?.getValue("pg_profile")
            ?.jsonObject
            ?.getValue("report_sha256")
            ?.jsonPrimitive
            ?.contentOrNull
    require(html == null || declaredReportHash == null || sha256Hex(html) == declaredReportHash) {
        "PG_PROFILE_HTML_HASH_MISMATCH"
    }
    return PostgresAnalysisInput(validatedPre, validatedPost, html)
}

private fun readPostgresHtml(input: InputStream): ByteArray =
    try {
        val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val remaining = MAX_POSTGRES_HTML_BYTES + 1 - total
            if (remaining <= 0) throw IllegalArgumentException("PG_PROFILE_HTML_TOO_LARGE")
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count == -1) break
            total += count
            if (total > MAX_POSTGRES_HTML_BYTES) throw IllegalArgumentException("PG_PROFILE_HTML_TOO_LARGE")
            output.write(buffer, 0, count)
        }
        output.toByteArray().also(::requirePostgresHtmlUtf8)
    } catch (_: IOException) {
        throw IllegalArgumentException("PG_PROFILE_HTML_READ_ERROR")
    }

private fun requirePostgresHtmlUtf8(bytes: ByteArray) {
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("PG_PROFILE_HTML_INVALID_UTF8")
    }
}

private const val MAX_POSTGRES_HTML_BYTES = 4 * 1024 * 1024

internal fun analyzeWithSources(
    service: AnalysisService,
    request: AnalysisRequest,
    source: PromqlSource?,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
    beforePublish: () -> Unit = checkCancelled,
    cellBudget: Long = MAX_RESOURCE_CELLS,
): AnalysisOutcome {
    val windowed = request.sourceRequest ?: return service.analyze(request, processedBytes, checkCancelled, beforePublish)
    require(request.resources == null && request.diagnostics == null && request.sourceAcquisition == null) { "SOURCE_INPUT_CONFLICT" }
    val configured = requireNotNull(source) { "SOURCE_NOT_CONFIGURED" }
    val selection = resolveWindow(service, request, windowed, configured.profiles, cellBudget, checkCancelled)
    val acquisition = configured.acquire(selection, request.input.sha256, checkCancelled)
    return service.analyze(
        request.copy(sourceRequest = null, resources = acquisition.snapshot, sourceAcquisition = acquisition),
        processedBytes,
        checkCancelled,
        beforePublish,
    )
}

private fun resolveWindow(
    service: AnalysisService,
    request: AnalysisRequest,
    windowed: WindowedSourceRequest,
    profiles: List<SourceProfile>,
    cellBudget: Long,
    checkCancelled: () -> Unit,
): SourceRequest {
    val profileId = windowed.profileIds.first()
    val additional = windowed.profileIds.drop(1)
    val publishesWindow = windowed.schemaVersion == "source-request.v3" || windowed.schemaVersion == "source-request.v4"
    val publishesStep = windowed.schemaVersion == "source-request.v4"

    fun selected(): List<SourceProfile> =
        windowed.profileIds.map { id ->
            profiles.singleOrNull { it.id == id } ?: throw IllegalArgumentException("SOURCE_PROFILE_NOT_FOUND")
        }
    return when (val window = windowed.window) {
        is ExplicitWindow -> {
            val applied =
                if (window.stepAuto) {
                    applyAutoStep(
                        selected(),
                        window.stepMillis,
                        MAX_STEP_MILLIS,
                        cellBudget,
                        explicitGridCells(window.startMillis, window.endMillis),
                    )
                } else {
                    null
                }
            SourceRequest(
                profileId,
                window.startMillis,
                window.endMillis,
                applied?.stepMillis ?: window.stepMillis,
                additional,
                // Байты v1 и v2 остаются неизменными: provenance окна публикует только v3.
                // v4 extends window provenance with step selection.
                if (publishesWindow) {
                    buildJsonObject {
                        put("window_origin", "explicit")
                        if (publishesStep) putStepProvenance(applied)
                    }
                } else {
                    null
                },
            )
        }
        is AutoWindow -> {
            val period = runPeriodFromJson(recognizedPeriod(service, request, window, checkCancelled))
            val applied =
                if (window.stepAuto) {
                    applyAutoStep(
                        selected(),
                        window.stepMillis,
                        minOf(MAX_STEP_MILLIS, window.maxIdleGapMillis),
                        cellBudget,
                        autoGridCells(period, window),
                    )
                } else {
                    null
                }
            val effective = if (applied == null) window else window.copy(stepMillis = applied.stepMillis)
            val derived =
                when (val outcome = deriveAutoWindow(period, effective)) {
                    is AutoWindowOutcome.Derived -> outcome.window
                    // Отказ авто-окна возвращается до внешней выборки: границы теста не угадываются.
                    is AutoWindowOutcome.Refused -> throw IllegalArgumentException(outcome.reasonCode)
                }
            val provenance =
                autoWindowProvenance(period, window, derived).let { base ->
                    if (publishesStep) {
                        JsonObject(
                            base +
                                buildJsonObject {
                                    putStepProvenance(applied)
                                },
                        )
                    } else {
                        base
                    }
                }
            SourceRequest(
                profileId,
                derived.startMillis,
                derived.endMillis,
                derived.stepMillis,
                additional,
                provenance,
                autoDerivedWindow = true,
            )
        }
    }
}

private fun recognizedPeriod(
    service: AnalysisService,
    request: AnalysisRequest,
    window: AutoWindow,
    checkCancelled: () -> Unit,
): JsonObject {
    service.store.readRunPeriod(request.input.runId)?.let { return it }
    val recognized =
        try {
            recognizeRunPeriod(
                request.input.sourceType,
                request.input.path,
                request.input.sha256,
                window.maxIdleGapMillis,
                checkCancelled,
            )
        } catch (_: RunPeriodReadFailure) {
            // Сбой чтения не становится фактом о байтах: артефакт не сохраняется, отказ повторим следующим запуском.
            throw IllegalArgumentException(AUTO_WINDOW_UNAVAILABLE)
        }
    return runPeriodJson(recognized).also { service.store.replaceRunPeriod(request.input.runId, it) }
}

private fun autoWindowProvenance(
    period: RunPeriodV1,
    window: AutoWindow,
    derived: DerivedAutoWindow,
): JsonObject =
    buildJsonObject {
        put("window_origin", "auto")
        put("recognized_start_epoch_ms", period.firstSampleEpochMillis)
        put("recognized_end_epoch_ms", period.lastSampleEpochMillis)
        put("requested_margin_ms", window.marginMillis)
        put("applied_margin_ms", derived.appliedMarginMillis)
        put("max_idle_gap_ms", window.maxIdleGapMillis)
        put("detected_idle_gaps", period.idleGapCount)
        val longest = period.longestIdleGapMillis
        if (longest == null) put("longest_idle_gap_ms", JsonNull) else put("longest_idle_gap_ms", longest)
        put("auto_window_status", "DERIVED")
    }

internal fun readOpenSearchContext(
    input: InputStream,
    loadInputSha256: String,
    resources: ResourceValidation.Valid? = null,
): SourceAcquisition {
    val context = validateOpenSearchArtifact(input, loadInputSha256)
    val status =
        context
            .getValue("coverage")
            .jsonObject
            .getValue("status")
            .jsonPrimitive.content
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("profile_id", context.getValue("profile_id"))
            put("source_kind", "opensearch")
            put("transport", "manual")
            put("status", status)
            put("request_count", 0)
            put("retries", 0)
            put("throttle_wait_ms", 0)
            put("cap_exceeded", false)
            put(
                "queries",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "import")
                            put("status", if (status == "COMPLETE") "SUCCESS" else "PARTIAL")
                        },
                    )
                },
            )
        }
    return SourceAcquisition(
        resources,
        summary,
        mapOf("source-acquisition.json" to canonicalJson(summary), "opensearch-errors.json" to canonicalJson(context)),
        listOf(context),
    )
}

internal fun readOpenSearchContexts(
    inputs: List<ByteArray>,
    loadInputSha256: String,
    resources: ResourceValidation.Valid? = null,
): SourceAcquisition {
    require(inputs.size in 1..16) { "SOURCE_CONTEXT_COUNT_INVALID" }
    require(inputs.sumOf { it.size.toLong() } <= 32L * 1024 * 1024) { "SOURCE_CONTEXT_LIMIT_EXCEEDED" }
    val imported =
        inputs
            .map { readOpenSearchContext(it.inputStream(), loadInputSha256, resources) }
            .sortedBy {
                it.evidence
                    .getValue("profile_id")
                    .jsonPrimitive.content
            }
    require(imported.map { it.evidence.getValue("profile_id") }.distinct().size == imported.size) { "SOURCE_CONTEXT_PROFILE_DUPLICATE" }
    if (imported.size == 1) return imported.single()
    val summary =
        JsonObject(
            imported.first().evidence +
                mapOf(
                    "profile_id" to JsonPrimitive("multiple"),
                    "source_kind" to JsonPrimitive("multiple"),
                    "status" to
                        JsonPrimitive(
                            if (imported.all {
                                    it.evidence
                                        .getValue("status")
                                        .jsonPrimitive.content == "COMPLETE"
                                }
                            ) {
                                "COMPLETE"
                            } else {
                                "PARTIAL"
                            },
                        ),
                    "profiles" to JsonArray(imported.map { it.evidence }),
                    "queries" to
                        JsonArray(
                            imported.map { item ->
                                val id =
                                    item.evidence
                                        .getValue("profile_id")
                                        .jsonPrimitive.content
                                        .replace("%", "%25")
                                        .replace("/", "%2F")
                                JsonObject(
                                    item.evidence
                                        .getValue("queries")
                                        .jsonArray
                                        .single()
                                        .jsonObject + ("id" to JsonPrimitive("$id/import")),
                                )
                            },
                        ),
                ),
        )
    val contexts = imported.flatMap { it.contextEvidence }
    val artifacts = linkedMapOf("source-acquisition.json" to canonicalJson(summary))
    contexts.forEachIndexed { index, context -> artifacts["opensearch-errors-${index + 1}.json"] = canonicalJson(context) }
    return SourceAcquisition(resources, summary, artifacts, contexts)
}

internal fun acquireOpenSearch(
    profile: SourceProfile,
    request: SourceRequest,
    loadInputSha256: String,
    http: SourceHttp,
    checkCancelled: () -> Unit,
): SourceAcquisition {
    val mapping = requireNotNull(profile.openSearch) { "SOURCE_PROFILE_INVALID" }
    val query = buildOpenSearchQuery(mapping, request, profile.governor.timeoutMillis)
    val budget = SourceBudget(profile.governor.maxRequestsPerRun)
    val artifacts = linkedMapOf<String, ByteArray>()
    var context: JsonObject? = null
    var reason: String? = null
    try {
        val body = http.search(profile, query, budget, checkCancelled)
        context = decodeOpenSearchResponse(body, mapping, request, loadInputSha256, profile.baseUrl)
        artifacts["source-response-1.json"] = body
        artifacts["opensearch-errors.json"] = canonicalJson(context)
    } catch (failure: SourceHttpFailure) {
        if (failure.code == "SOURCE_CANCELLED") throw failure
        reason = failure.code
    } catch (failure: IllegalArgumentException) {
        reason = failure.message?.takeIf { it.matches(Regex("OPENSEARCH_[A-Z_]+")) } ?: "OPENSEARCH_RESPONSE_INVALID"
    }
    val status =
        context
            ?.getValue("coverage")
            ?.jsonObject
            ?.getValue("status")
            ?.jsonPrimitive
            ?.content ?: "FAILED"
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("profile_id", profile.id)
            put("source_kind", profile.sourceKind.wireName)
            put("transport", profile.transport.wireName)
            put("status", status)
            put("start_epoch_ms", request.startEpochMillis)
            put("end_epoch_ms", request.endEpochMillis)
            put("step_ms", request.stepMillis)
            put("request_count", budget.requestCount)
            put("retries", budget.retries)
            put("throttle_wait_ms", budget.throttleWaitMillis)
            put("cap_exceeded", budget.capExceeded)
            put(
                "queries",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "errors")
                            put(
                                "status",
                                if (status == "COMPLETE") {
                                    "SUCCESS"
                                } else if (status == "PARTIAL") {
                                    "PARTIAL"
                                } else {
                                    "FAILED"
                                },
                            )
                            put("expression_sha256", sha256Hex(query))
                            reason?.let { put("reason", it) }
                        },
                    )
                },
            )
        }.withWindowProvenance(request.windowProvenance)
    artifacts["source-acquisition.json"] = canonicalJson(summary)
    return SourceAcquisition(null, summary, artifacts, listOfNotNull(context))
}

internal fun acquireMultipleSources(
    profiles: List<SourceProfile>,
    source: PromqlSource,
    request: SourceRequest,
    loadInputSha256: String,
    checkCancelled: () -> Unit,
): SourceAcquisition {
    val ids = (listOf(request.profileId) + request.additionalProfileIds).sorted()
    require(ids.size in 2..16 && ids.distinct().size == ids.size) { "SOURCE_REQUEST_INVALID" }
    val selected = ids.map { id -> profiles.singleOrNull { it.id == id } ?: throw IllegalArgumentException("SOURCE_PROFILE_NOT_FOUND") }
    // Смешанный набор отказывает до первого HTTP-запроса, а не после выборки opensearch-профиля.
    if (selected.any { it.sourceKind != SourceKind.OPENSEARCH }) requireSnapshotGridStep(request.stepMillis)
    val seriesCount = selected.sumOf { it.queries.size }
    require(
        seriesCount <= MAX_RESOURCE_SERIES &&
            seriesCount.toLong() * ((request.endEpochMillis - request.startEpochMillis) / request.stepMillis) <= MAX_RESOURCE_CELLS &&
            selected.sumOf { it.rules.size } <= MAX_RESOURCE_RULES,
    ) { "RESOURCE_LIMIT_EXCEEDED" }

    fun qualified(
        profile: String,
        id: String,
    ): String = qualifiedSeriesId(profile, id)
    selected.forEach { profile ->
        (profile.queries.map { it.id } + profile.rules.map { it.id }).forEach {
            require(qualified(profile.id, it).encodeToByteArray().size <= 128) { "SOURCE_QUALIFIED_ID_TOO_LONG" }
        }
    }
    val series = mutableListOf<kotlinx.serialization.json.JsonElement>()
    val rules = mutableListOf<kotlinx.serialization.json.JsonElement>()
    val summaries = mutableListOf<JsonObject>()
    val contexts = mutableListOf<JsonObject>()
    val artifacts = linkedMapOf<String, ByteArray>()
    var rawBytes = 0L
    var contextBytes = 0L
    var responseIndex = 0
    selected.forEach { profile ->
        checkCancelled()
        val acquired =
            source.acquire(
                // Provenance окна публикует только агрегированная сводка, а не сводки отдельных профилей.
                request.copy(profileId = profile.id, additionalProfileIds = emptyList(), windowProvenance = null),
                loadInputSha256,
                checkCancelled,
            )
        var summary = acquired.evidence
        acquired.snapshot?.let { snapshot ->
            val root = Json.parseToJsonElement(snapshot.rawBytes().decodeToString()).jsonObject
            root.getValue("series").jsonArray.forEach { item ->
                series +=
                    JsonObject(
                        item.jsonObject +
                            (
                                "id" to
                                    JsonPrimitive(
                                        qualified(
                                            profile.id,
                                            item.jsonObject
                                                .getValue("id")
                                                .jsonPrimitive.content,
                                        ),
                                    )
                            ),
                    )
            }
            root.getValue("rules").jsonArray.forEach { item ->
                val rule = item.jsonObject
                rules +=
                    JsonObject(
                        rule +
                            mapOf(
                                "id" to JsonPrimitive(qualified(profile.id, rule.getValue("id").jsonPrimitive.content)),
                                "series_id" to JsonPrimitive(qualified(profile.id, rule.getValue("series_id").jsonPrimitive.content)),
                            ),
                    )
            }
        }
        acquired.artifacts.filterKeys { it.startsWith("source-response-") }.forEach { (_, bytes) ->
            if (rawBytes + bytes.size <= 64L * 1024 * 1024) {
                artifacts["source-response-${++responseIndex}.json"] = bytes
                rawBytes += bytes.size
            } else {
                summary = withSourceLimit(summary, "SOURCE_RAW_ARTIFACT_LIMIT_EXCEEDED", "PARTIAL")
            }
        }
        acquired.contextEvidence.forEach { context ->
            val bytes = canonicalJson(context).size
            if (contextBytes + bytes <= 32L * 1024 * 1024) {
                contexts += context
                contextBytes += bytes
            } else {
                summary = withSourceLimit(summary, "SOURCE_CONTEXT_LIMIT_EXCEEDED", "FAILED")
            }
        }
        summaries += summary
    }
    val expressions =
        JsonArray(
            summaries.map { summary ->
                buildJsonObject {
                    put("profile_id", summary.getValue("profile_id"))
                    put(
                        "queries",
                        JsonArray(
                            summary.getValue("queries").jsonArray.mapNotNull { query ->
                                query.jsonObject["expression_sha256"]?.let { hash ->
                                    buildJsonObject {
                                        put("id", query.jsonObject.getValue("id"))
                                        put("expression_sha256", hash)
                                    }
                                }
                            },
                        ),
                    )
                }
            },
        )
    val provenance =
        buildJsonObject {
            put("source_kind", "multiple")
            put("clock_alignment", "interval_cells")
            put("query_semantics", "interval_ms=${request.stepMillis}; expressions_sha256=${sha256Hex(canonicalJson(expressions))}")
        }

    fun snapshotBytes(values: List<kotlinx.serialization.json.JsonElement>) =
        canonicalJson(
            buildJsonObject {
                put("schema_version", "resource-snapshot.v1")
                put("load_input_sha256", loadInputSha256)
                put("start_epoch_ms", request.startEpochMillis)
                put("step_ms", request.stepMillis)
                put("point_count", (request.endEpochMillis - request.startEpochMillis) / request.stepMillis)
                put("series", JsonArray(values))
                put("rules", JsonArray(rules))
                // Как и в одиночной выборке: авто-окно не объявляется, его обрезает пересечение с прогоном.
                if (!request.autoDerivedWindow) {
                    put(
                        "windows",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("id", "full")
                                    put("from_epoch_ms", request.startEpochMillis)
                                    put("to_epoch_ms", request.endEpochMillis)
                                },
                            )
                        },
                    )
                }
                put("provenance", provenance)
            },
        )
    var snapshot: ResourceValidation.Valid? = null
    if (series.isNotEmpty()) {
        val validation = validateResourceSnapshot(snapshotBytes(series).inputStream())
        snapshot = validation as? ResourceValidation.Valid
        if (snapshot == null) {
            require(
                (validation as ResourceValidation.Invalid).errors.all { it.code == "RESOURCE_LIMIT_EXCEEDED" },
            ) { "SOURCE_SNAPSHOT_INVALID" }
            val missing =
                series.map { item ->
                    JsonObject(
                        item.jsonObject + (
                            "values" to
                                JsonArray(
                                    item.jsonObject
                                        .getValue("values")
                                        .jsonArray
                                        .map { JsonNull },
                                )
                        ),
                    )
                }
            snapshot = validateResourceSnapshot(snapshotBytes(missing).inputStream()) as? ResourceValidation.Valid
                ?: throw IllegalArgumentException("SOURCE_SNAPSHOT_INVALID")
            summaries.indices.filter { selected[it].queries.isNotEmpty() }.forEach { index ->
                summaries[index] = withSourceLimit(summaries[index], "SOURCE_SNAPSHOT_LIMIT_EXCEEDED", "FAILED")
            }
        }
    }
    contexts.forEachIndexed { index, context ->
        artifacts[if (contexts.size == 1) "opensearch-errors.json" else "opensearch-errors-${index + 1}.json"] = canonicalJson(context)
    }

    fun total(field: String): Long =
        summaries.fold(0L) { sum, summary ->
            try {
                Math.addExact(sum, summary.getValue(field).jsonPrimitive.long)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
        }
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("profile_id", "multiple")
            put("source_kind", "multiple")
            put("transport", "multiple")
            put("start_epoch_ms", request.startEpochMillis)
            put("end_epoch_ms", request.endEpochMillis)
            put("step_ms", request.stepMillis)
            val states = summaries.map { it.getValue("status").jsonPrimitive.content }
            put(
                "status",
                when {
                    states.all { it == "COMPLETE" } -> "COMPLETE"
                    states.any { it != "FAILED" } -> "PARTIAL"
                    else -> "FAILED"
                },
            )
            put("profiles", JsonArray(summaries))
            put(
                "queries",
                JsonArray(
                    summaries.flatMap { item ->
                        item.getValue("queries").jsonArray.map { query ->
                            JsonObject(
                                query.jsonObject + (
                                    "id" to
                                        JsonPrimitive(
                                            qualified(
                                                item.getValue("profile_id").jsonPrimitive.content,
                                                query.jsonObject
                                                    .getValue("id")
                                                    .jsonPrimitive.content,
                                            ),
                                        )
                                ),
                            )
                        }
                    },
                ),
            )
            put("request_count", total("request_count"))
            put("retries", total("retries"))
            put("throttle_wait_ms", total("throttle_wait_ms"))
            put("cap_exceeded", summaries.any { it.getValue("cap_exceeded").jsonPrimitive.boolean })
        }.withWindowProvenance(request.windowProvenance)
    artifacts["source-acquisition.json"] = canonicalJson(summary)
    return SourceAcquisition(snapshot, summary, artifacts, contexts)
}

internal fun qualifiedSeriesId(
    profileId: String,
    id: String,
): String = profileId.replace("%", "%25").replace("/", "%2F") + "/" + id

private fun withSourceLimit(
    summary: JsonObject,
    reason: String,
    status: String,
): JsonObject =
    JsonObject(
        summary +
            mapOf(
                "status" to JsonPrimitive(if (summary.getValue("status").jsonPrimitive.content == "FAILED") "FAILED" else status),
                "queries" to
                    JsonArray(
                        (
                            summary.getValue("queries").jsonArray +
                                buildJsonObject {
                                    put("id", "limit:$reason")
                                    put("status", "FAILED")
                                    put("reason", reason)
                                }
                        ).distinct(),
                    ),
            ),
    )

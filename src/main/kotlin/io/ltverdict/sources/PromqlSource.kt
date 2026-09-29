package io.ltverdict.sources

import io.ltverdict.core.MAX_RESOURCE_CELLS
import io.ltverdict.core.RESOURCE_JSON_DEPTH_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_EXPONENT_ABS_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_TOKEN_BYTES_MAX
import io.ltverdict.core.ResourceRuleV1
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateResourceSnapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class SourceAcquisition(
    val snapshot: ResourceValidation.Valid?,
    val evidence: JsonObject,
    val artifacts: Map<String, ByteArray>,
    val contextEvidence: List<JsonObject> = emptyList(),
)

internal class PromqlSource(
    private val profiles: List<SourceProfile>,
    private val http: SourceHttp,
) {
    fun acquire(
        request: SourceRequest,
        loadInputSha256: String,
        checkCancelled: () -> Unit = {},
    ): SourceAcquisition {
        checkCancelled()
        if (request.additionalProfileIds.isNotEmpty()) {
            return acquireMultipleSources(
                profiles,
                this,
                request,
                loadInputSha256,
                checkCancelled,
            )
        }
        val profile = profiles.singleOrNull { it.id == request.profileId } ?: throw IllegalArgumentException("SOURCE_PROFILE_NOT_FOUND")
        if (profile.sourceKind == SourceKind.OPENSEARCH) return acquireOpenSearch(profile, request, loadInputSha256, http, checkCancelled)
        requireSnapshotGridStep(request.stepMillis)
        val pointCount = ((request.endEpochMillis - request.startEpochMillis) / request.stepMillis).toInt()
        require(profile.queries.isNotEmpty()) { "SOURCE_QUERIES_EMPTY" }
        require(profile.queries.size.toLong() * pointCount <= MAX_RESOURCE_CELLS) { "RESOURCE_LIMIT_EXCEEDED" }
        val unavailableSnapshot =
            validatedSnapshot(
                profile,
                request,
                loadInputSha256,
                profile.queries.map { failedSeries(it, pointCount) },
                profile.rules,
            )

        val budget = SourceBudget(profile.governor.maxRequestsPerRun)
        val rawResponses = linkedMapOf<String, ByteArray>()
        val collected = ArrayList<CollectedSeries>(profile.queries.size)
        val queryEvidence = ArrayList<QueryEvidence>(profile.queries.size)
        var rawBytes = 0L

        profile.queries.forEachIndexed { index, query ->
            checkCancelled()
            if (budget.capExceeded) {
                collected += failedSeries(query, pointCount)
                queryEvidence +=
                    QueryEvidence(
                        query.id,
                        "FAILED",
                        "SOURCE_REQUEST_CAP_EXCEEDED",
                        sha256Hex(resolvedExpression(profile, query, request).encodeToByteArray()),
                    )
                return@forEachIndexed
            }
            try {
                val expression = resolvedExpression(profile, query, request)
                val body =
                    http.get(
                        profile,
                        when (profile.sourceKind) {
                            SourceKind.OPENSEARCH -> error("SOURCE_PROFILE_INVALID")
                            SourceKind.INFLUXDB ->
                                mapOf(
                                    "db" to (profile.database ?: throw IllegalArgumentException("SOURCE_PROFILE_INVALID")),
                                    "q" to expression,
                                    "epoch" to "ms",
                                )
                            SourceKind.PROMETHEUS,
                            SourceKind.VICTORIA_METRICS,
                            ->
                                mapOf(
                                    "query" to expression,
                                    "start" to seconds(request.startEpochMillis + request.stepMillis),
                                    "end" to seconds(request.endEpochMillis),
                                    "step" to seconds(request.stepMillis),
                                )
                        },
                        budget,
                        checkCancelled,
                    )
                val decoded =
                    when (profile.sourceKind) {
                        SourceKind.OPENSEARCH -> error("SOURCE_PROFILE_INVALID")
                        SourceKind.INFLUXDB ->
                            decodeInfluxqlResponse(
                                body,
                                query.labels,
                                request.startEpochMillis,
                                request.stepMillis,
                                pointCount,
                            )
                        SourceKind.PROMETHEUS,
                        SourceKind.VICTORIA_METRICS,
                        ->
                            decodePromqlMatrix(
                                body,
                                query.labels,
                                request.startEpochMillis,
                                request.stepMillis,
                                pointCount,
                            )
                    }
                if (rawBytes + body.size > MAX_ACQUISITION_RESPONSE_BYTES) fail("RESOURCE_LIMIT_EXCEEDED")

                val series =
                    CollectedSeries(
                        query,
                        decoded?.labels ?: query.labels,
                        decoded?.values ?: List(pointCount) { null },
                    )
                if (decoded != null) validateSeries(profile, request, loadInputSha256, series)
                collected += series
                val observed = decoded?.values?.count { it != null } ?: 0
                queryEvidence +=
                    QueryEvidence(
                        query.id,
                        when {
                            decoded == null || observed == 0 -> "MISSING"
                            observed < pointCount -> "PARTIAL"
                            else -> "SUCCESS"
                        },
                        when {
                            decoded == null -> "EMPTY_RESULT"
                            observed < pointCount -> "MISSING_SAMPLES"
                            else -> null
                        },
                        sha256Hex(expression.encodeToByteArray()),
                    )
                rawBytes += body.size
                rawResponses["source-response-${index + 1}.json"] = body
            } catch (failure: SourceHttpFailure) {
                if (failure.code == "SOURCE_CANCELLED") throw failure
                collected += failedSeries(query, pointCount)
                queryEvidence +=
                    QueryEvidence(
                        query.id,
                        "FAILED",
                        failure.code,
                        sha256Hex(resolvedExpression(profile, query, request).encodeToByteArray()),
                    )
            } catch (failure: PromqlDecodeFailure) {
                collected += failedSeries(query, pointCount)
                queryEvidence +=
                    QueryEvidence(
                        query.id,
                        "FAILED",
                        failure.code,
                        sha256Hex(resolvedExpression(profile, query, request).encodeToByteArray()),
                    )
            }
        }

        val snapshot =
            try {
                validatedSnapshot(profile, request, loadInputSha256, collected, profile.rules)
            } catch (failure: IllegalArgumentException) {
                if (failure.message != "RESOURCE_LIMIT_EXCEEDED") throw failure
                queryEvidence.replaceAll { query ->
                    if (query.status == "SUCCESS" || query.status == "PARTIAL") {
                        query.copy(status = "FAILED", reason = "SOURCE_SNAPSHOT_LIMIT_EXCEEDED")
                    } else {
                        query
                    }
                }
                unavailableSnapshot
            }
        val evidence = sourceEvidence(profile, request, budget, queryEvidence)
        val artifacts = linkedMapOf("source-acquisition.json" to canonicalJson(evidence)).apply { putAll(rawResponses) }
        return SourceAcquisition(snapshot, evidence, artifacts)
    }
}

internal data class PromqlSeries(
    val labels: Map<String, String>,
    val values: List<BigDecimal?>,
)

// Сетка snapshot требует целых секунд 1..60; отказ выносится до внешних обращений.
internal fun requireSnapshotGridStep(stepMillis: Long) {
    if (stepMillis !in 1_000..60_000 || stepMillis % 1_000L != 0L) throw IllegalArgumentException("SOURCE_REQUEST_INVALID")
}

internal class PromqlDecodeFailure(
    val code: String,
) : IllegalArgumentException(code)

internal fun decodePromqlMatrix(
    body: ByteArray,
    expectedLabels: Map<String, String>,
    startEpochMillis: Long,
    stepMillis: Long,
    pointCount: Int,
): PromqlSeries? {
    return try {
        val text = decodeUtf8(body)
        StrictJsonScanner(
            text,
            RESOURCE_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "PromQL response",
        ) { code, _, _ -> fail(if (code == "MALFORMED_JSON") "MALFORMED_RESPONSE" else code) }.scan()
        val root = Json.parseToJsonElement(text).objectValue()
        if (root.string("status") != "success") fail("PROMQL_QUERY_FAILED")
        root["warnings"]?.let { warnings ->
            if (warnings !is JsonArray) fail("MALFORMED_RESPONSE")
            if (warnings.isNotEmpty()) fail("SOURCE_WARNINGS")
        }
        val data = root.objectValue("data")
        if (data.string("resultType") != "matrix") fail("UNSUPPORTED_RESULT_TYPE")
        val results = data.arrayValue("result")
        if (results.isEmpty()) return null
        if (results.size != 1) fail("AMBIGUOUS_SERIES")

        val result = results.single().objectValue()
        if ("histograms" in result || "histogram" in result) fail("UNSUPPORTED_HISTOGRAM")
        val labels =
            result.objectValue("metric").mapValues { (_, value) ->
                val primitive = value as? JsonPrimitive
                if (primitive == null || !primitive.isString) fail("MALFORMED_RESPONSE")
                primitive.content
            }
        if (expectedLabels.any { (key, value) -> labels[key] != value }) fail("LABEL_MISMATCH")

        val samples = result.arrayValue("values")
        if (samples.size > pointCount) fail("RESOURCE_LIMIT_EXCEEDED")
        val values = arrayOfNulls<BigDecimal>(pointCount)
        val seen = BooleanArray(pointCount)
        val firstBoundary = Math.addExact(startEpochMillis, stepMillis)
        val lastBoundary = Math.addExact(startEpochMillis, Math.multiplyExact(stepMillis, pointCount.toLong()))
        samples.forEach { element ->
            val sample = element as? JsonArray ?: fail("MALFORMED_RESPONSE")
            if (sample.size != 2) fail("MALFORMED_RESPONSE")
            val timestamp = timestampMillis(sample[0])
            if (timestamp !in firstBoundary..lastBoundary || (timestamp - firstBoundary) % stepMillis != 0L) {
                fail("OFF_GRID_TIMESTAMP")
            }
            val index = ((timestamp - firstBoundary) / stepMillis).toInt()
            if (seen[index]) fail("DUPLICATE_TIMESTAMP")
            seen[index] = true
            values[index] = sampleValue(sample[1])
        }
        PromqlSeries(labels, values.toList())
    } catch (failure: PromqlDecodeFailure) {
        throw failure
    } catch (_: SerializationException) {
        fail("MALFORMED_RESPONSE")
    } catch (_: CharacterCodingException) {
        fail("MALFORMED_RESPONSE")
    } catch (_: ArithmeticException) {
        fail("MALFORMED_RESPONSE")
    } catch (_: IllegalArgumentException) {
        fail("MALFORMED_RESPONSE")
    }
}

private data class CollectedSeries(
    val query: SourceQuery,
    val labels: Map<String, String>,
    val values: List<BigDecimal?>,
)

private data class QueryEvidence(
    val id: String,
    val status: String,
    val reason: String?,
    val expressionSha256: String,
)

private fun failedSeries(
    query: SourceQuery,
    pointCount: Int,
) = CollectedSeries(query, query.labels, List(pointCount) { null })

private fun validateSeries(
    profile: SourceProfile,
    request: SourceRequest,
    loadInputSha256: String,
    series: CollectedSeries,
) {
    when (
        val validation =
            validateResourceSnapshot(
                ByteArrayInputStream(snapshotBytes(profile, request, loadInputSha256, listOf(series), emptyList())),
            )
    ) {
        is ResourceValidation.Valid -> Unit
        is ResourceValidation.Invalid -> fail(validation.errors.first().code)
    }
}

private fun validatedSnapshot(
    profile: SourceProfile,
    request: SourceRequest,
    loadInputSha256: String,
    series: List<CollectedSeries>,
    rules: List<ResourceRuleV1>,
): ResourceValidation.Valid =
    when (
        val validation =
            validateResourceSnapshot(
                ByteArrayInputStream(snapshotBytes(profile, request, loadInputSha256, series, rules)),
            )
    ) {
        is ResourceValidation.Valid -> validation
        is ResourceValidation.Invalid -> throw IllegalArgumentException(validation.errors.first().code)
    }

private fun snapshotBytes(
    profile: SourceProfile,
    request: SourceRequest,
    loadInputSha256: String,
    series: List<CollectedSeries>,
    rules: List<ResourceRuleV1>,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "resource-snapshot.v1")
            put("load_input_sha256", loadInputSha256)
            put("start_epoch_ms", request.startEpochMillis)
            put("step_ms", request.stepMillis)
            put("point_count", (request.endEpochMillis - request.startEpochMillis) / request.stepMillis)
            put(
                "series",
                buildJsonArray {
                    series.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.query.id)
                                put("metric", item.query.metric)
                                put("unit", item.query.unit)
                                put("entity", item.query.entity)
                                put("role", item.query.role.wireName)
                                put("aggregation", item.query.aggregation.wireName)
                                put("labels", buildJsonObject { item.labels.forEach { (key, value) -> put(key, value) } })
                                put("values", buildJsonArray { item.values.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
                            },
                        )
                    }
                },
            )
            // Авто-окно шире прогона, поэтому объявленного окна нет: привязка идёт по пересечению с прогоном.
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
            put("rules", buildJsonArray { rules.forEach { add(it.json()) } })
            put(
                "provenance",
                buildJsonObject {
                    put("source_kind", profile.sourceKind.wireName)
                    put(
                        "query_semantics",
                        if (profile.sourceKind == SourceKind.INFLUXDB) {
                            "interval_ms=${request.stepMillis}; sample_at=left_boundary; " +
                                "query_set_sha256=${querySetSha256(profile, request)}"
                        } else {
                            "trailing_window_ms=${request.stepMillis}; sample_at=right_boundary; " +
                                "query_set_sha256=${querySetSha256(profile, request)}"
                        },
                    )
                    put(
                        "clock_alignment",
                        if (profile.sourceKind == SourceKind.INFLUXDB) "left_boundary" else "right_boundary_to_preceding_cell",
                    )
                },
            )
        },
    )

private fun ResourceRuleV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("series_id", seriesId)
        put("unit", unit)
        put("operator", operator.wireName)
        put("threshold", JsonPrimitive(threshold))
        put("min_consecutive_cells", minConsecutiveCells)
        put("effect", effect.wireName)
    }

private fun sourceEvidence(
    profile: SourceProfile,
    request: SourceRequest,
    budget: SourceBudget,
    queries: List<QueryEvidence>,
): JsonObject {
    val status =
        when {
            queries.all { it.status == "SUCCESS" } -> "COMPLETE"
            queries.any { it.status == "SUCCESS" || it.status == "PARTIAL" } -> "PARTIAL"
            else -> "FAILED"
        }
    return buildJsonObject {
        put("id", "source-summary")
        put("type", "source_summary")
        put("status", status)
        put("profile_id", profile.id)
        put("source_kind", profile.sourceKind.wireName)
        put("transport", profile.transport.wireName)
        put("start_epoch_ms", request.startEpochMillis)
        put("end_epoch_ms", request.endEpochMillis)
        put("step_ms", request.stepMillis)
        put(
            "queries",
            buildJsonArray {
                queries.forEach { query ->
                    add(
                        buildJsonObject {
                            put("id", query.id)
                            put("status", query.status)
                            query.reason?.let { put("reason", it) }
                            put("expression_sha256", query.expressionSha256)
                        },
                    )
                }
            },
        )
        put("request_count", budget.requestCount)
        put("retries", budget.retries)
        put("throttle_wait_ms", budget.throttleWaitMillis)
        put("cap_exceeded", budget.capExceeded)
    }.withWindowProvenance(request.windowProvenance)
}

// Provenance окна добавляется только запросами v3; для v1 и v2 сводка возвращается байт-в-байт прежней.
internal fun JsonObject.withWindowProvenance(provenance: JsonObject?): JsonObject =
    if (provenance == null) this else JsonObject(this + provenance)

private fun JsonObject.objectValue(name: String): JsonObject = get(name)?.objectValue() ?: fail("MALFORMED_RESPONSE")

private fun JsonObject.arrayValue(name: String): JsonArray = get(name) as? JsonArray ?: fail("MALFORMED_RESPONSE")

private fun JsonObject.string(name: String): String {
    val value = get(name) as? JsonPrimitive
    if (value == null || !value.isString) fail("MALFORMED_RESPONSE")
    return value.content
}

private fun kotlinx.serialization.json.JsonElement.objectValue(): JsonObject = this as? JsonObject ?: fail("MALFORMED_RESPONSE")

private fun timestampMillis(value: kotlinx.serialization.json.JsonElement): Long {
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive.content in setOf("true", "false", "null")) fail("MALFORMED_RESPONSE")
    val decimal =
        try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            fail("MALFORMED_RESPONSE")
        }
    return try {
        decimal.movePointRight(3).longValueExact()
    } catch (_: ArithmeticException) {
        fail("OFF_GRID_TIMESTAMP")
    }
}

private fun sampleValue(value: kotlinx.serialization.json.JsonElement): BigDecimal? {
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) fail("MALFORMED_RESPONSE")
    if (primitive.content in NONFINITE_VALUES) return null
    if (primitive.content.encodeToByteArray().size > RESOURCE_NUMERIC_TOKEN_BYTES_MAX) fail("RESOURCE_LIMIT_EXCEEDED")
    return try {
        BigDecimal(primitive.content)
    } catch (_: NumberFormatException) {
        fail("MALFORMED_RESPONSE")
    }
}

private fun decodeUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun seconds(epochMillis: Long): String = BigDecimal.valueOf(epochMillis, 3).stripTrailingZeros().toPlainString()

private fun resolvedExpression(
    profile: SourceProfile,
    query: SourceQuery,
    request: SourceRequest,
): String =
    if (profile.sourceKind == SourceKind.INFLUXDB) {
        INFLUX_PLACEHOLDER_PATTERN.replace(query.expression) { match ->
            when (match.value) {
                "\$__start" -> "${request.startEpochMillis}ms"
                "\$__end" -> "${request.endEpochMillis}ms"
                "\$__interval" -> "${request.stepMillis}ms"
                "\$__offset" -> "${request.startEpochMillis % request.stepMillis}ms"
                else -> match.value
            }
        }
    } else {
        query.expression.replace("\$__interval", "${request.stepMillis}ms")
    }

private fun querySetSha256(
    profile: SourceProfile,
    request: SourceRequest,
): String =
    sha256Hex(
        canonicalJson(
            buildJsonArray {
                profile.queries.sortedBy(SourceQuery::id).forEach { query ->
                    add(
                        buildJsonObject {
                            put("id", query.id)
                            put("expression_sha256", sha256Hex(resolvedExpression(profile, query, request).encodeToByteArray()))
                        },
                    )
                }
            },
        ),
    )

private fun fail(code: String): Nothing = throw PromqlDecodeFailure(code)

private const val MAX_ACQUISITION_RESPONSE_BYTES = 64L * 1024 * 1024
private val INFLUX_PLACEHOLDER_PATTERN = Regex("\\${'$'}__[A-Za-z0-9_]+")
private val NONFINITE_VALUES = setOf("NaN", "+Inf", "-Inf", "Inf")

package io.ltverdict.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface DiagnosticValidation {
    class Valid internal constructor(
        internal val plan: DiagnosticPlanV1,
        val sha256: String,
        rawBytes: ByteArray,
    ) : DiagnosticValidation {
        private val sourceBytes = rawBytes.copyOf()

        fun rawBytes(): ByteArray = sourceBytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : DiagnosticValidation
}

internal data class DiagnosticPlanV1(
    val schemaVersion: String,
    val resourceSnapshotSha256: String,
    val pairs: List<DiagnosticPairV1>,
    val anomalies: List<DiagnosticAnomalyV1>,
)

internal data class DiagnosticPairV1(
    val id: String,
    val resourceSeriesId: String,
    val loadMetric: DiagnosticLoadMetric,
    val windowIds: List<String>,
    val expectedSign: DiagnosticExpectedSign,
    val maxLagMillis: Long,
    val minAbsEffect: BigDecimal,
    val minResourceDelta: BigDecimal,
    val minLoadDelta: BigDecimal,
    val controls: List<DiagnosticControlV1>,
    val topologyBasis: String,
    val clockAlignment: DiagnosticClockAlignment,
)

internal data class DiagnosticControlV1(
    val meaning: DiagnosticControlMeaning,
    val seriesId: String?,
) {
    val key: String = seriesId ?: meaning.wireName
}

internal data class DiagnosticAnomalyV1(
    val id: String,
    val signal: DiagnosticSignalV1,
    val referenceWindowId: String,
    val windowId: String,
    val direction: DiagnosticDirection,
    val minAbsDelta: BigDecimal,
    val minDurationMillis: Long,
    val zThreshold: BigDecimal,
)

internal sealed interface DiagnosticSignalV1 {
    data class Resource(
        val seriesId: String,
    ) : DiagnosticSignalV1

    data class Load(
        val metric: DiagnosticLoadMetric,
    ) : DiagnosticSignalV1
}

internal enum class DiagnosticLoadMetric(
    val wireName: String,
    val unit: String,
) {
    RESPONSE_TIME_P95_MS("response_time_p95_ms", "ms"),
    ERROR_RATE("error_rate", "ratio"),
    THROUGHPUT_RPS("throughput_rps", "requests/s"),
}

internal enum class DiagnosticExpectedSign(
    val wireName: String,
) {
    POSITIVE("positive"),
    NEGATIVE("negative"),
    EITHER("either"),
}

internal enum class DiagnosticClockAlignment(
    val wireName: String,
) {
    UNKNOWN("unknown"),
    DECLARED_ALIGNED("declared_aligned"),
}

internal enum class DiagnosticDirection(
    val wireName: String,
) {
    INCREASE("increase"),
    DECREASE("decrease"),
    EITHER("either"),
}

internal enum class DiagnosticControlMeaning(
    val wireName: String,
    val requiredUnit: String?,
) {
    ACHIEVED_RPS("achieved_rps", null),
    TARGET_RPS("target_rps", "requests/s"),
    CONCURRENCY("concurrency", "count"),
    REPLICAS("replicas", "count"),
    REQUEST_MIX("request_mix", "ratio"),
    OTHER("other", null),
}

internal fun validateDiagnosticPlan(
    source: InputStream,
    maxBytes: Int = MAX_DIAGNOSTIC_PLAN_BYTES,
): DiagnosticValidation =
    try {
        require(maxBytes >= 0)
        val raw = readDiagnosticBytes(source, maxBytes)
        val text = decodeDiagnosticUtf8(raw)
        StrictJsonScanner(
            text,
            DIAGNOSTIC_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "diagnostic plan",
            ::diagnosticFail,
        ).scan()
        val plan = parseDiagnosticPlan(Json.parseToJsonElement(text))
        val semantic = canonicalJson(plan.semanticJson())
        DiagnosticValidation.Valid(plan, sha256Hex(semantic), raw)
    } catch (failure: DiagnosticFailure) {
        DiagnosticValidation.Invalid(listOf(failure.error))
    } catch (_: IOException) {
        diagnosticInvalid("DIAGNOSTIC_READ_ERROR", "", "diagnostic plan could not be read")
    } catch (_: SerializationException) {
        diagnosticInvalid("MALFORMED_JSON", "", "diagnostic plan is not valid JSON")
    } catch (_: IllegalArgumentException) {
        diagnosticInvalid("MALFORMED_JSON", "", "diagnostic plan is not valid JSON")
    }

internal fun validateDiagnosticBinding(
    plan: DiagnosticValidation.Valid,
    resources: ResourceValidation.Valid,
): List<PolicyValidationError> {
    if (plan.plan.resourceSnapshotSha256 != resources.semanticSha256) {
        return listOf(
            PolicyValidationError(
                "DIAGNOSTIC_SNAPSHOT_MISMATCH",
                "/resource_snapshot_sha256",
                "diagnostic plan does not match the resource snapshot",
            ),
        )
    }
    val errors = mutableListOf<PolicyValidationError>()
    val snapshot = resources.snapshot
    val series = snapshot.series.associateBy(ResourceSeriesV1::id)
    val explicitWindows = snapshot.windows.associateBy(ResourceWindowV1::id)

    plan.plan.pairs.forEachIndexed { index, pair ->
        val pointer = "/pairs/$index"
        if (explicitWindows.isNotEmpty()) {
            pair.windowIds.forEachIndexed { windowIndex, windowId ->
                if (windowId !in explicitWindows) {
                    errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "$pointer/window_ids/$windowIndex", "window not found")
                }
            }
        }
        if (pair.maxLagMillis % snapshot.stepMillis != 0L || pair.maxLagMillis / snapshot.stepMillis > MAX_DIAGNOSTIC_LAG_CELLS) {
            errors +=
                diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", "$pointer/max_lag_ms", "lag must be aligned and at most ten cells")
        }
        if (pair.resourceSeriesId !in series) {
            errors += diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", "$pointer/resource_series_id", "resource series not found")
        }
        pair.controls.forEachIndexed { controlIndex, control ->
            val controlPointer = "$pointer/controls/$controlIndex/series_id"
            val seriesId = control.seriesId ?: return@forEachIndexed
            val bound = series[seriesId]
            if (bound == null) {
                errors += diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", controlPointer, "control series not found")
                return@forEachIndexed
            }
            val invalidUnit = control.meaning.requiredUnit?.let { it != bound.unit } == true
            val invalidRange =
                when (control.meaning) {
                    DiagnosticControlMeaning.TARGET_RPS,
                    DiagnosticControlMeaning.CONCURRENCY,
                    DiagnosticControlMeaning.REPLICAS,
                    -> bound.values.filterNotNull().any { it.signum() < 0 }

                    DiagnosticControlMeaning.REQUEST_MIX -> bound.values.filterNotNull().any { it < BigDecimal.ZERO || it > BigDecimal.ONE }
                    else -> false
                }
            if (invalidUnit || invalidRange) {
                errors +=
                    diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", controlPointer, "control series has incompatible unit or values")
            }
        }
    }
    plan.plan.anomalies.forEachIndexed { index, anomaly ->
        val pointer = "/anomalies/$index"
        if (anomaly.minDurationMillis % snapshot.stepMillis != 0L) {
            errors +=
                diagnosticBindingError(
                    "DIAGNOSTIC_INVALID_BINDING",
                    "$pointer/min_duration_ms",
                    "duration must be aligned to the resource grid",
                )
        }
        val signal = anomaly.signal
        if (signal is DiagnosticSignalV1.Resource && signal.seriesId !in series) {
            errors += diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", "$pointer/signal/series_id", "resource series not found")
        }
        if (explicitWindows.isNotEmpty()) {
            val reference = explicitWindows[anomaly.referenceWindowId]
            val evaluation = explicitWindows[anomaly.windowId]
            if (reference == null) {
                errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "$pointer/reference_window_id", "window not found")
            }
            if (evaluation == null) {
                errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "$pointer/window_id", "window not found")
            }
            if (reference != null && evaluation != null && windowsOverlap(reference, evaluation)) {
                errors += diagnosticBindingError("DIAGNOSTIC_INVALID_BINDING", pointer, "reference and evaluation windows must be disjoint")
            }
        }
    }
    return errors
}

internal fun validateDiagnosticResolvedWindows(
    plan: DiagnosticValidation.Valid,
    windows: List<ResourceWindowV1>,
): List<PolicyValidationError> {
    val errors = mutableListOf<PolicyValidationError>()
    val byId = windows.associateBy(ResourceWindowV1::id)
    plan.plan.pairs.forEachIndexed { index, pair ->
        pair.windowIds.forEachIndexed { windowIndex, windowId ->
            if (windowId !in byId) {
                errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "/pairs/$index/window_ids/$windowIndex", "window not found")
            }
        }
    }
    plan.plan.anomalies.forEachIndexed { index, anomaly ->
        val reference = byId[anomaly.referenceWindowId]
        val evaluation = byId[anomaly.windowId]
        if (reference == null) {
            errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "/anomalies/$index/reference_window_id", "window not found")
        }
        if (evaluation == null) {
            errors += diagnosticBindingError("DIAGNOSTIC_WINDOW_NOT_FOUND", "/anomalies/$index/window_id", "window not found")
        }
        if (reference != null && evaluation != null && windowsOverlap(reference, evaluation)) {
            errors +=
                diagnosticBindingError(
                    "DIAGNOSTIC_INVALID_BINDING",
                    "/anomalies/$index",
                    "reference and evaluation windows must be disjoint",
                )
        }
    }
    return errors
}

private fun parseDiagnosticPlan(element: JsonElement): DiagnosticPlanV1 {
    val root = element.diagnosticObject("")
    root.rejectDiagnosticUnknown(setOf("schema_version", "resource_snapshot_sha256", "pairs", "anomalies"), "")
    val schema = root.diagnosticString("schema_version", "")
    if (schema != DIAGNOSTIC_SCHEMA_VERSION) {
        diagnosticFail("INVALID_SCHEMA_VERSION", "/schema_version", "expected $DIAGNOSTIC_SCHEMA_VERSION")
    }
    val resourceHash = root.diagnosticString("resource_snapshot_sha256", "")
    if (!DIAGNOSTIC_SHA256.matches(resourceHash)) {
        diagnosticFail("INVALID_SNAPSHOT_HASH", "/resource_snapshot_sha256", "snapshot hash must be lowercase SHA-256")
    }
    val pairs = parseDiagnosticPairs(root.optionalDiagnosticArray("pairs", ""))
    val anomalies = parseDiagnosticAnomalies(root.optionalDiagnosticArray("anomalies", ""))
    if (pairs.isEmpty() && anomalies.isEmpty()) {
        diagnosticFail("EMPTY_DIAGNOSTICS", "", "at least one pair or anomaly is required")
    }
    val ids = HashSet<String>()
    (pairs.map(DiagnosticPairV1::id) + anomalies.map(DiagnosticAnomalyV1::id)).forEach { id ->
        if (!ids.add(id)) diagnosticFail("DUPLICATE_DIAGNOSTIC_ID", "", "diagnostic ids must be unique")
    }
    val definitions = HashSet<DiagnosticPairV1>()
    pairs.forEachIndexed { index, pair ->
        if (!definitions.add(pair.duplicateIdentity())) {
            diagnosticFail("DUPLICATE_PAIR", "/pairs/$index", "pair definition must be unique")
        }
    }
    return DiagnosticPlanV1(schema, resourceHash, pairs.sortedBy(DiagnosticPairV1::id), anomalies.sortedBy(DiagnosticAnomalyV1::id))
}

private fun DiagnosticPairV1.duplicateIdentity(): DiagnosticPairV1 =
    copy(
        id = "",
        minAbsEffect = minAbsEffect.normalized(),
        minResourceDelta = minResourceDelta.normalized(),
        minLoadDelta = minLoadDelta.normalized(),
    )

private fun BigDecimal.normalized(): BigDecimal = if (signum() == 0) BigDecimal.ZERO else stripTrailingZeros()

private fun parseDiagnosticPairs(values: JsonArray): List<DiagnosticPairV1> {
    if (values.size > MAX_DIAGNOSTIC_PAIRS) diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "/pairs", "too many diagnostic pairs")
    var windowCount = 0
    val ids = HashSet<String>()
    return values.mapIndexed { index, element ->
        val pointer = "/pairs/$index"
        val value = element.diagnosticObject(pointer)
        value.rejectDiagnosticUnknown(
            setOf(
                "id",
                "resource_series_id",
                "load_metric",
                "window_ids",
                "expected_sign",
                "max_lag_ms",
                "min_abs_effect",
                "min_resource_delta",
                "min_load_delta",
                "controls",
                "topology_basis",
                "clock_alignment",
            ),
            pointer,
        )
        val id = value.diagnosticIdentifier("id", pointer)
        if (!ids.add(id)) diagnosticFail("DUPLICATE_PAIR_ID", "$pointer/id", "pair id must be unique")
        val resourceSeriesId = value.diagnosticIdentifier("resource_series_id", pointer)
        val loadMetric = value.diagnosticEnum("load_metric", pointer, DiagnosticLoadMetric.entries, DiagnosticLoadMetric::wireName)
        val windows = value.diagnosticArray("window_ids", pointer)
        if (windows.isEmpty()) diagnosticFail("EMPTY_WINDOWS", "$pointer/window_ids", "at least one window is required")
        val windowIds =
            windows.mapIndexed { windowIndex, window ->
                window.diagnosticStringValue("$pointer/window_ids/$windowIndex").also {
                    validateDiagnosticText(it, "$pointer/window_ids/$windowIndex", DIAGNOSTIC_IDENTIFIER_BYTES, "window id")
                }
            }
        if (windowIds.distinct().size != windowIds.size) {
            diagnosticFail("DUPLICATE_WINDOW_ID", "$pointer/window_ids", "window ids must be unique")
        }
        windowCount += windowIds.size
        if (windowCount > MAX_DIAGNOSTIC_PAIR_WINDOWS) {
            diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "$pointer/window_ids", "too many pair windows")
        }
        val expected =
            value.optionalDiagnosticEnum(
                "expected_sign",
                pointer,
                DiagnosticExpectedSign.EITHER,
                DiagnosticExpectedSign.entries,
                DiagnosticExpectedSign::wireName,
            )
        val maxLag = value.optionalDiagnosticLong("max_lag_ms", pointer, 0)
        if (maxLag !in 0..MAX_DIAGNOSTIC_LAG_MILLIS) {
            diagnosticFail("INVALID_RANGE", "$pointer/max_lag_ms", "lag is outside the supported range")
        }
        val minEffect = value.optionalDiagnosticDecimal("min_abs_effect", pointer, DEFAULT_MIN_EFFECT)
        if (minEffect <= BigDecimal.ZERO || minEffect > BigDecimal.ONE) {
            diagnosticFail("INVALID_RANGE", "$pointer/min_abs_effect", "effect must be in (0,1]")
        }
        val resourceDelta = value.diagnosticDecimal("min_resource_delta", pointer)
        if (resourceDelta <= BigDecimal.ZERO) diagnosticFail("INVALID_RANGE", "$pointer/min_resource_delta", "delta must be positive")
        val loadDelta = value.diagnosticDecimal("min_load_delta", pointer)
        if (loadDelta <= BigDecimal.ZERO) diagnosticFail("INVALID_RANGE", "$pointer/min_load_delta", "delta must be positive")
        val controls = parseDiagnosticControls(value.optionalDiagnosticArray("controls", pointer), pointer, resourceSeriesId, loadMetric)
        val topology = value.diagnosticPlainString("topology_basis", pointer, DIAGNOSTIC_TEXT_BYTES)
        val alignment =
            value.optionalDiagnosticEnum(
                "clock_alignment",
                pointer,
                DiagnosticClockAlignment.UNKNOWN,
                DiagnosticClockAlignment.entries,
                DiagnosticClockAlignment::wireName,
            )
        DiagnosticPairV1(
            id,
            resourceSeriesId,
            loadMetric,
            windowIds.sorted(),
            expected,
            maxLag,
            minEffect,
            resourceDelta,
            loadDelta,
            controls.sortedBy(DiagnosticControlV1::key),
            topology,
            alignment,
        )
    }
}

private fun parseDiagnosticControls(
    values: JsonArray,
    pairPointer: String,
    resourceSeriesId: String,
    loadMetric: DiagnosticLoadMetric,
): List<DiagnosticControlV1> {
    if (values.size > MAX_DIAGNOSTIC_CONTROLS) diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "$pairPointer/controls", "too many controls")
    val keys = HashSet<String>()
    val series = HashSet<String>()
    return values.mapIndexed { index, element ->
        val pointer = "$pairPointer/controls/$index"
        val value = element.diagnosticObject(pointer)
        val meaning = value.diagnosticEnum("meaning", pointer, DiagnosticControlMeaning.entries, DiagnosticControlMeaning::wireName)
        val seriesId =
            if (meaning == DiagnosticControlMeaning.ACHIEVED_RPS) {
                value.rejectDiagnosticUnknown(setOf("meaning"), pointer)
                if (loadMetric == DiagnosticLoadMetric.THROUGHPUT_RPS) {
                    diagnosticFail("INVALID_CONTROL", pointer, "load outcome cannot control itself")
                }
                null
            } else {
                value.rejectDiagnosticUnknown(setOf("meaning", "series_id"), pointer)
                value.diagnosticIdentifier("series_id", pointer).also {
                    if (it == resourceSeriesId) diagnosticFail("INVALID_CONTROL", "$pointer/series_id", "source cannot control itself")
                    if (!series.add(it)) diagnosticFail("DUPLICATE_CONTROL", "$pointer/series_id", "control series must be unique")
                }
            }
        val control = DiagnosticControlV1(meaning, seriesId)
        if (!keys.add(control.key)) diagnosticFail("DUPLICATE_CONTROL", pointer, "control must be unique")
        control
    }
}

private fun parseDiagnosticAnomalies(values: JsonArray): List<DiagnosticAnomalyV1> {
    if (values.size > MAX_DIAGNOSTIC_ANOMALIES) diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "/anomalies", "too many anomaly rules")
    val ids = HashSet<String>()
    return values.mapIndexed { index, element ->
        val pointer = "/anomalies/$index"
        val value = element.diagnosticObject(pointer)
        value.rejectDiagnosticUnknown(
            setOf("id", "signal", "reference_window_id", "window_id", "direction", "min_abs_delta", "min_duration_ms", "z_threshold"),
            pointer,
        )
        val id = value.diagnosticIdentifier("id", pointer)
        if (!ids.add(id)) diagnosticFail("DUPLICATE_ANOMALY_ID", "$pointer/id", "anomaly id must be unique")
        val signal = parseDiagnosticSignal(value.diagnosticRequired("signal", pointer), "$pointer/signal")
        val reference = value.diagnosticIdentifier("reference_window_id", pointer)
        val window = value.diagnosticIdentifier("window_id", pointer)
        if (reference == window) diagnosticFail("INVALID_WINDOWS", "$pointer/window_id", "reference and evaluation windows must differ")
        val direction = value.diagnosticEnum("direction", pointer, DiagnosticDirection.entries, DiagnosticDirection::wireName)
        val minDelta = value.diagnosticDecimal("min_abs_delta", pointer)
        if (minDelta <= BigDecimal.ZERO) diagnosticFail("INVALID_RANGE", "$pointer/min_abs_delta", "delta must be positive")
        val duration = value.diagnosticLong("min_duration_ms", pointer)
        if (duration <= 0L) diagnosticFail("INVALID_RANGE", "$pointer/min_duration_ms", "duration must be positive")
        val z = value.optionalDiagnosticDecimal("z_threshold", pointer, DEFAULT_Z_THRESHOLD)
        if (z <= BigDecimal.ZERO || z > MAX_Z_THRESHOLD) {
            diagnosticFail("INVALID_RANGE", "$pointer/z_threshold", "modified-Z threshold must be in (0,100]")
        }
        DiagnosticAnomalyV1(id, signal, reference, window, direction, minDelta, duration, z)
    }
}

private fun parseDiagnosticSignal(
    element: JsonElement,
    pointer: String,
): DiagnosticSignalV1 {
    val value = element.diagnosticObject(pointer)
    value.rejectDiagnosticUnknown(setOf("series_id", "load_metric"), pointer)
    if (value.size != 1) diagnosticFail("INVALID_SIGNAL", pointer, "signal must contain exactly one selector")
    return value["series_id"]?.let {
        DiagnosticSignalV1.Resource(
            it.diagnosticStringValue("$pointer/series_id").also { id ->
                validateDiagnosticText(id, "$pointer/series_id", DIAGNOSTIC_IDENTIFIER_BYTES, "series id")
            },
        )
    } ?: DiagnosticSignalV1.Load(
        value.diagnosticEnum("load_metric", pointer, DiagnosticLoadMetric.entries, DiagnosticLoadMetric::wireName),
    )
}

private fun DiagnosticPlanV1.semanticJson(): JsonObject =
    buildJsonObject {
        put("schema_version", schemaVersion)
        put("resource_snapshot_sha256", resourceSnapshotSha256)
        put("pairs", buildJsonArray { pairs.forEach { add(it.semanticJson()) } })
        put("anomalies", buildJsonArray { anomalies.forEach { add(it.semanticJson()) } })
    }

private fun DiagnosticPairV1.semanticJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("resource_series_id", resourceSeriesId)
        put("load_metric", loadMetric.wireName)
        put("window_ids", buildJsonArray { windowIds.forEach { add(JsonPrimitive(it)) } })
        put("expected_sign", expectedSign.wireName)
        put("max_lag_ms", maxLagMillis)
        put("min_abs_effect", JsonPrimitive(minAbsEffect))
        put("min_resource_delta", JsonPrimitive(minResourceDelta))
        put("min_load_delta", JsonPrimitive(minLoadDelta))
        put("controls", buildJsonArray { controls.forEach { add(it.semanticJson()) } })
        put("topology_basis", topologyBasis)
        put("clock_alignment", clockAlignment.wireName)
    }

private fun DiagnosticControlV1.semanticJson(): JsonObject =
    buildJsonObject {
        put("meaning", meaning.wireName)
        seriesId?.let { put("series_id", it) }
    }

private fun DiagnosticAnomalyV1.semanticJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put(
            "signal",
            buildJsonObject {
                when (val selected = signal) {
                    is DiagnosticSignalV1.Resource -> put("series_id", selected.seriesId)
                    is DiagnosticSignalV1.Load -> put("load_metric", selected.metric.wireName)
                }
            },
        )
        put("reference_window_id", referenceWindowId)
        put("window_id", windowId)
        put("direction", direction.wireName)
        put("min_abs_delta", JsonPrimitive(minAbsDelta))
        put("min_duration_ms", minDurationMillis)
        put("z_threshold", JsonPrimitive(zThreshold))
    }

private fun JsonElement.diagnosticObject(pointer: String): JsonObject =
    this as? JsonObject ?: diagnosticFail("INVALID_TYPE", pointer, "expected object")

private fun JsonObject.diagnosticRequired(
    name: String,
    pointer: String,
): JsonElement = get(name) ?: diagnosticFail("MISSING_FIELD", pointer.diagnosticChild(name), "required field is missing")

private fun JsonObject.diagnosticArray(
    name: String,
    pointer: String,
): JsonArray =
    diagnosticRequired(name, pointer) as? JsonArray
        ?: diagnosticFail("INVALID_TYPE", pointer.diagnosticChild(name), "$name must be an array")

private fun JsonObject.optionalDiagnosticArray(
    name: String,
    pointer: String,
): JsonArray =
    this[name]?.let { it as? JsonArray ?: diagnosticFail("INVALID_TYPE", pointer.diagnosticChild(name), "$name must be an array") }
        ?: JsonArray(emptyList())

private fun JsonObject.diagnosticString(
    name: String,
    pointer: String,
): String = diagnosticRequired(name, pointer).diagnosticStringValue(pointer.diagnosticChild(name))

private fun JsonElement.diagnosticStringValue(pointer: String): String {
    if (this !is JsonPrimitive || !isString) diagnosticFail("INVALID_TYPE", pointer, "value must be a string")
    return content
}

private fun JsonObject.diagnosticIdentifier(
    name: String,
    pointer: String,
): String =
    diagnosticString(name, pointer).also {
        validateDiagnosticText(it, pointer.diagnosticChild(name), DIAGNOSTIC_IDENTIFIER_BYTES, name)
    }

private fun JsonObject.diagnosticPlainString(
    name: String,
    pointer: String,
    maxBytes: Int,
): String = diagnosticString(name, pointer).also { validateDiagnosticText(it, pointer.diagnosticChild(name), maxBytes, name) }

private fun validateDiagnosticText(
    value: String,
    pointer: String,
    maxBytes: Int,
    description: String,
) {
    if (value.isEmpty() || value.any(Char::isISOControl)) {
        diagnosticFail("INVALID_TEXT", pointer, "$description must be non-empty plain text")
    }
    if (value.encodeToByteArray().size > maxBytes) {
        diagnosticFail("RESOURCE_LIMIT_EXCEEDED", pointer, "$description is too long")
    }
}

private fun JsonObject.diagnosticLong(
    name: String,
    pointer: String,
): Long {
    val child = pointer.diagnosticChild(name)
    return try {
        diagnosticDecimal(name, pointer).longValueExact()
    } catch (_: ArithmeticException) {
        diagnosticFail("INVALID_TYPE", child, "$name must be an integer")
    }
}

private fun JsonObject.optionalDiagnosticLong(
    name: String,
    pointer: String,
    default: Long,
): Long = if (name in this) diagnosticLong(name, pointer) else default

private fun JsonObject.diagnosticDecimal(
    name: String,
    pointer: String,
): BigDecimal = diagnosticRequired(name, pointer).diagnosticDecimalValue(pointer.diagnosticChild(name))

private fun JsonObject.optionalDiagnosticDecimal(
    name: String,
    pointer: String,
    default: BigDecimal,
): BigDecimal = if (name in this) diagnosticDecimal(name, pointer) else default

private fun JsonElement.diagnosticDecimalValue(pointer: String): BigDecimal {
    if (this !is JsonPrimitive || isString || this === JsonNull || content in setOf("true", "false")) {
        diagnosticFail("INVALID_TYPE", pointer, "value must be a number")
    }
    val value =
        try {
            BigDecimal(content)
        } catch (_: NumberFormatException) {
            diagnosticFail("INVALID_TYPE", pointer, "value must be a finite number")
        }
    val normalized = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
    if (normalized.abs() > MAX_DIAGNOSTIC_MAGNITUDE ||
        normalized.precision() > RESOURCE_SIGNIFICANT_DIGITS_MAX ||
        maxOf(normalized.scale(), 0) > RESOURCE_FRACTIONAL_DIGITS_MAX
    ) {
        diagnosticFail("RESOURCE_LIMIT_EXCEEDED", pointer, "number exceeds precision or magnitude limits")
    }
    return value
}

private fun <T> JsonObject.diagnosticEnum(
    name: String,
    pointer: String,
    values: Iterable<T>,
    wireName: (T) -> String,
): T {
    val selected = diagnosticString(name, pointer)
    return values.firstOrNull { wireName(it) == selected }
        ?: diagnosticFail("INVALID_VALUE", pointer.diagnosticChild(name), "unsupported value")
}

private fun <T> JsonObject.optionalDiagnosticEnum(
    name: String,
    pointer: String,
    default: T,
    values: Iterable<T>,
    wireName: (T) -> String,
): T = if (name in this) diagnosticEnum(name, pointer, values, wireName) else default

private fun JsonObject.rejectDiagnosticUnknown(
    allowed: Set<String>,
    pointer: String,
) {
    keys.firstOrNull { it !in allowed }?.let { name ->
        diagnosticFail("UNKNOWN_FIELD", pointer.diagnosticChild(name), "unknown field")
    }
}

private fun String.diagnosticChild(token: String): String = "$this/${token.replace("~", "~0").replace("/", "~1")}"

private fun readDiagnosticBytes(
    source: InputStream,
    maxBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val probe = maxBytes.toLong() + 1L - total
        if (probe <= 0L) diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "", "diagnostic plan exceeds $maxBytes bytes")
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), probe).toInt())
        if (count == -1) break
        total += count
        if (total > maxBytes) diagnosticFail("RESOURCE_LIMIT_EXCEEDED", "", "diagnostic plan exceeds $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeDiagnosticUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        diagnosticFail("INVALID_UTF8", "", "diagnostic plan must be valid UTF-8")
    }

private fun windowsOverlap(
    left: ResourceWindowV1,
    right: ResourceWindowV1,
): Boolean = left.fromEpochMillis < right.toEpochMillis && right.fromEpochMillis < left.toEpochMillis

private fun diagnosticBindingError(
    code: String,
    pointer: String,
    message: String,
) = PolicyValidationError(code, pointer, message)

private data class DiagnosticFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun diagnosticFail(
    code: String,
    pointer: String,
    message: String,
): Nothing = throw DiagnosticFailure(PolicyValidationError(code, pointer, message))

private fun diagnosticInvalid(
    code: String,
    pointer: String,
    message: String,
): DiagnosticValidation.Invalid = DiagnosticValidation.Invalid(listOf(PolicyValidationError(code, pointer, message)))

internal const val MAX_DIAGNOSTIC_PLAN_BYTES = 1_048_576
internal const val DIAGNOSTIC_JSON_DEPTH_MAX = 12
internal const val MAX_DIAGNOSTIC_PAIRS = 16
internal const val MAX_DIAGNOSTIC_ANOMALIES = 32
internal const val MAX_DIAGNOSTIC_PAIR_WINDOWS = 128
internal const val MAX_DIAGNOSTIC_CONTROLS = 4
internal const val MAX_DIAGNOSTIC_LAG_MILLIS = 60_000L
internal const val MAX_DIAGNOSTIC_LAG_CELLS = 10L
internal const val MAX_DIAGNOSTIC_EPISODES = 1_000
internal const val MIN_DIAGNOSTIC_P95_SAMPLES = 20L
private const val DIAGNOSTIC_SCHEMA_VERSION = "correlation-plan.v1"
private const val DIAGNOSTIC_IDENTIFIER_BYTES = 128
private const val DIAGNOSTIC_TEXT_BYTES = 512
private val DEFAULT_MIN_EFFECT = BigDecimal("0.3")
private val DEFAULT_Z_THRESHOLD = BigDecimal("3.5")
private val MAX_Z_THRESHOLD = BigDecimal("100")
private val MAX_DIAGNOSTIC_MAGNITUDE = BigDecimal("1000000000000000000")
private val DIAGNOSTIC_SHA256 = Regex("[0-9a-f]{64}")

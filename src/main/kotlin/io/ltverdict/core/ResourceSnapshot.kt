package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
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

internal sealed interface ResourceValidation {
    class Valid internal constructor(
        val snapshot: ResourceSnapshotV1,
        val semanticSha256: String,
        val configSha256: String,
        rawBytes: ByteArray,
    ) : ResourceValidation {
        private val sourceBytes = rawBytes.copyOf()

        fun rawBytes(): ByteArray = sourceBytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : ResourceValidation
}

internal data class ResourceSnapshotV1(
    val schemaVersion: String,
    val loadInputSha256: String,
    val startEpochMillis: Long,
    val stepMillis: Long,
    val pointCount: Int,
    val series: List<ResourceSeriesV1>,
    val windows: List<ResourceWindowV1>,
    val rules: List<ResourceRuleV1>,
    val provenance: ResourceProvenanceV1?,
)

internal data class ResourceSeriesV1(
    val id: String,
    val metric: String,
    val unit: String,
    val entity: String,
    val role: ResourceRole,
    val aggregation: ResourceAggregation,
    val labels: Map<String, String>,
    val values: List<BigDecimal?>,
)

internal data class ResourceWindowV1(
    val id: String,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
)

internal data class ResourceRuleV1(
    val id: String,
    val seriesId: String,
    val unit: String,
    val operator: ResourceOperator,
    val threshold: BigDecimal,
    val minConsecutiveCells: Int,
    val effect: ResourceRuleEffect,
)

internal data class ResourceProvenanceV1(
    val sourceKind: String,
    val querySemantics: String,
    val clockAlignment: String,
)

internal enum class ResourceRole(
    val wireName: String,
) {
    SYSTEM("system"),
    GENERATOR("generator"),
}

internal enum class ResourceAggregation(
    val wireName: String,
) {
    INTERVAL_MEAN("interval_mean"),
    INTERVAL_RATE("interval_rate"),
}

internal enum class ResourceOperator(
    val wireName: String,
) {
    GT("gt"),
    LT("lt"),
}

internal enum class ResourceRuleEffect(
    val wireName: String,
) {
    DIAGNOSTIC("diagnostic"),
    SLA("sla"),
}

internal fun validateResourceSnapshot(
    source: InputStream,
    maxBytes: Int = MAX_RESOURCE_SNAPSHOT_BYTES,
): ResourceValidation =
    try {
        require(maxBytes >= 0)
        val raw = readResourceBytes(source, maxBytes)
        val text = decodeResourceUtf8(raw)
        StrictJsonScanner(
            text,
            RESOURCE_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "resource snapshot",
            ::resourceFail,
        ).scan()
        val snapshot = parseResourceSnapshot(Json.parseToJsonElement(text))
        val semantic = snapshot.semanticJson()
        val config = snapshot.configJson()
        ResourceValidation.Valid(snapshot, sha256Hex(semantic), sha256Hex(config), raw)
    } catch (failure: ResourceFailure) {
        ResourceValidation.Invalid(listOf(failure.error))
    } catch (_: IOException) {
        resourceInvalid("RESOURCE_READ_ERROR", "", "resource snapshot could not be read")
    } catch (_: SerializationException) {
        resourceInvalid("MALFORMED_JSON", "", "resource snapshot is not valid JSON")
    } catch (_: IllegalArgumentException) {
        resourceInvalid("MALFORMED_JSON", "", "resource snapshot is not valid JSON")
    }

internal fun resolveResourceWindows(
    snapshot: ResourceSnapshotV1,
    loadInputSha256: String,
    runStartEpochMillis: Long,
    runEndEpochMillis: Long,
): List<ResourceWindowV1> {
    require(snapshot.loadInputSha256 == loadInputSha256) { "RESOURCE_LOAD_HASH_MISMATCH" }
    require(runStartEpochMillis >= 0 && runEndEpochMillis in runStartEpochMillis..MAX_TIMESTAMP_EPOCH_MILLIS) {
        "INVALID_RUN_WINDOW"
    }
    if (snapshot.windows.isNotEmpty()) {
        require(snapshot.windows.all { it.fromEpochMillis >= runStartEpochMillis && it.toEpochMillis <= runEndEpochMillis }) {
            "RESOURCE_WINDOW_OUTSIDE_RUN"
        }
        return snapshot.windows
    }

    val gridEnd = snapshot.gridEndEpochMillis()
    val firstBoundary = maxOf(runStartEpochMillis, snapshot.startEpochMillis)
    val lastBoundary = minOf(runEndEpochMillis, gridEnd)
    val firstIndex =
        if (firstBoundary <= snapshot.startEpochMillis) {
            0L
        } else {
            val offset = firstBoundary - snapshot.startEpochMillis
            (offset + snapshot.stepMillis - 1) / snapshot.stepMillis
        }
    val endIndex =
        if (lastBoundary <= snapshot.startEpochMillis) {
            0L
        } else {
            (lastBoundary - snapshot.startEpochMillis) / snapshot.stepMillis
        }
    require(firstIndex < endIndex) { "RESOURCE_WINDOW_NO_FULL_CELLS" }
    return listOf(
        ResourceWindowV1(
            "run-intersection",
            Math.addExact(snapshot.startEpochMillis, Math.multiplyExact(firstIndex, snapshot.stepMillis)),
            Math.addExact(snapshot.startEpochMillis, Math.multiplyExact(endIndex, snapshot.stepMillis)),
        ),
    )
}

internal fun resourceBindingEvidence(
    snapshot: ResourceSnapshotV1,
    windows: List<ResourceWindowV1>,
    runStartEpochMillis: Long,
    runEndEpochMillis: Long,
): JsonObject {
    require(windows.isNotEmpty())
    val implicit = snapshot.windows.isEmpty()
    val evaluationStart = windows.minOf(ResourceWindowV1::fromEpochMillis)
    val evaluationEnd = windows.maxOf(ResourceWindowV1::toEpochMillis)
    val intersectionStart = maxOf(runStartEpochMillis, snapshot.startEpochMillis)
    val intersectionEnd = minOf(runEndEpochMillis, snapshot.gridEndEpochMillis())
    val droppedLeadingMillis = if (implicit) evaluationStart - intersectionStart else 0L
    val droppedTrailingMillis = if (implicit) intersectionEnd - evaluationEnd else 0L
    return buildJsonObject {
        put("id", "resource-binding")
        put("type", "resource_binding")
        put("mode", if (implicit) "run_intersection" else "explicit_windows")
        put("snapshot_from_epoch_ms", snapshot.startEpochMillis)
        put("snapshot_to_epoch_ms", snapshot.gridEndEpochMillis())
        put("run_from_epoch_ms", runStartEpochMillis)
        put("run_to_epoch_ms", runEndEpochMillis)
        put("evaluation_from_epoch_ms", evaluationStart)
        put("evaluation_to_epoch_ms", evaluationEnd)
        put("dropped_leading_cells", if (droppedLeadingMillis > 0) 1 else 0)
        put("dropped_leading_millis", droppedLeadingMillis)
        put("dropped_trailing_cells", if (droppedTrailingMillis > 0) 1 else 0)
        put("dropped_trailing_millis", droppedTrailingMillis)
        put("clock_alignment", "not_verified_by_core")
    }
}

private fun parseResourceSnapshot(element: JsonElement): ResourceSnapshotV1 {
    val root = element.resourceObject("")
    root.rejectResourceUnknown(
        setOf(
            "schema_version",
            "load_input_sha256",
            "start_epoch_ms",
            "step_ms",
            "point_count",
            "series",
            "windows",
            "rules",
            "provenance",
        ),
        "",
    )
    val schemaVersion = root.resourceString("schema_version", "")
    if (schemaVersion != "resource-snapshot.v1") {
        resourceFail("INVALID_SCHEMA_VERSION", "/schema_version", "expected resource-snapshot.v1")
    }
    val loadHash = root.resourceString("load_input_sha256", "")
    if (!SHA256.matches(loadHash)) resourceFail("INVALID_LOAD_HASH", "/load_input_sha256", "load hash must be lowercase SHA-256")
    val start = root.resourceLong("start_epoch_ms", "")
    if (start !in 0..MAX_TIMESTAMP_EPOCH_MILLIS) {
        resourceFail("INVALID_GRID", "/start_epoch_ms", "grid start is outside the supported timestamp range")
    }
    val step = root.resourceLong("step_ms", "")
    if (step !in 1_000..60_000 || step % 1_000L != 0L) {
        resourceFail("INVALID_GRID", "/step_ms", "step must be whole seconds from 1 to 60")
    }
    val pointCount = root.resourceInt("point_count", "")
    if (pointCount !in 1..MAX_POINTS_PER_SERIES) {
        resourceFail("RESOURCE_LIMIT_EXCEEDED", "/point_count", "point count exceeds $MAX_POINTS_PER_SERIES")
    }
    val gridEnd =
        try {
            Math.addExact(start, Math.multiplyExact(step, pointCount.toLong()))
        } catch (_: ArithmeticException) {
            resourceFail("INVALID_GRID", "/point_count", "grid end overflows")
        }
    if (gridEnd > MAX_TIMESTAMP_EPOCH_MILLIS) {
        resourceFail("INVALID_GRID", "/point_count", "grid end is outside the supported timestamp range")
    }

    val series = parseSeries(root.resourceArray("series", ""), pointCount)
    val windows = parseWindows(root.optionalResourceArray("windows", ""), start, step, gridEnd)
    val rules = parseRules(root.optionalResourceArray("rules", ""), series)
    val provenance = root["provenance"]?.let { parseProvenance(it, "/provenance") }
    return ResourceSnapshotV1(schemaVersion, loadHash, start, step, pointCount, series, windows, rules, provenance)
}

private fun parseSeries(
    values: JsonArray,
    pointCount: Int,
): List<ResourceSeriesV1> {
    if (values.isEmpty()) resourceFail("EMPTY_SERIES", "/series", "at least one series is required")
    if (values.size > MAX_RESOURCE_SERIES) resourceFail("RESOURCE_LIMIT_EXCEEDED", "/series", "too many series")
    if (values.size.toLong() * pointCount > MAX_RESOURCE_CELLS) {
        resourceFail("RESOURCE_LIMIT_EXCEEDED", "/series", "too many resource cells")
    }
    val ids = HashSet<String>()
    return values
        .mapIndexed { index, element ->
            val pointer = "/series/$index"
            val item = element.resourceObject(pointer)
            item.rejectResourceUnknown(setOf("id", "metric", "unit", "entity", "role", "aggregation", "labels", "values"), pointer)
            val id = item.resourceIdentifier("id", pointer)
            if (!ids.add(id)) resourceFail("DUPLICATE_SERIES_ID", "$pointer/id", "series id must be unique")
            val metric = item.resourceIdentifier("metric", pointer)
            val unit = item.resourceIdentifier("unit", pointer)
            val entity = item.resourceIdentifier("entity", pointer)
            val roleName = item.resourceString("role", pointer)
            val role =
                ResourceRole.entries.find { it.wireName == roleName }
                    ?: resourceFail("UNKNOWN_ROLE", "$pointer/role", "unknown resource role")
            val aggregationName = item.resourceString("aggregation", pointer)
            val aggregation =
                ResourceAggregation.entries.find { it.wireName == aggregationName }
                    ?: resourceFail("UNSUPPORTED_AGGREGATION", "$pointer/aggregation", "unsupported resource aggregation")
            val labels = item["labels"]?.let { parseLabels(it, "$pointer/labels") }.orEmpty()
            val samples = item.resourceArray("values", pointer)
            if (samples.size != pointCount) {
                resourceFail("VALUES_LENGTH_MISMATCH", "$pointer/values", "values length must equal point_count")
            }
            ResourceSeriesV1(
                id,
                metric,
                unit,
                entity,
                role,
                aggregation,
                labels,
                samples.mapIndexed { sampleIndex, sample ->
                    if (sample === JsonNull) null else sample.resourceDecimal("$pointer/values/$sampleIndex")
                },
            )
        }.sortedBy(ResourceSeriesV1::id)
}

private fun parseLabels(
    element: JsonElement,
    pointer: String,
): Map<String, String> {
    val labels = element.resourceObject(pointer)
    if (labels.size > MAX_LABELS) resourceFail("RESOURCE_LIMIT_EXCEEDED", pointer, "too many labels")
    return labels.entries.associate { (key, value) ->
        validateResourceText(key, pointer.resourceChild(key), MAX_LABEL_KEY_BYTES, "label key")
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) {
            resourceFail("INVALID_TYPE", pointer.resourceChild(key), "label value must be a string")
        }
        validateResourceText(primitive.content, pointer.resourceChild(key), MAX_LABEL_VALUE_BYTES, "label value")
        key to primitive.content
    }
}

private fun parseWindows(
    values: JsonArray,
    gridStart: Long,
    step: Long,
    gridEnd: Long,
): List<ResourceWindowV1> {
    if (values.size > MAX_RESOURCE_WINDOWS) resourceFail("RESOURCE_LIMIT_EXCEEDED", "/windows", "too many windows")
    val ids = HashSet<String>()
    val windows =
        values
            .mapIndexed { index, element ->
                val pointer = "/windows/$index"
                val item = element.resourceObject(pointer)
                item.rejectResourceUnknown(setOf("id", "from_epoch_ms", "to_epoch_ms"), pointer)
                val id = item.resourceIdentifier("id", pointer)
                if (!ids.add(id)) resourceFail("DUPLICATE_WINDOW_ID", "$pointer/id", "window id must be unique")
                val from = item.resourceLong("from_epoch_ms", pointer)
                val to = item.resourceLong("to_epoch_ms", pointer)
                if (from >= to) resourceFail("INVALID_WINDOW", pointer, "window must be non-empty")
                if (from < gridStart || to > gridEnd) resourceFail("WINDOW_OUTSIDE_GRID", pointer, "window must be inside the grid")
                if ((from - gridStart) % step !=
                    0L
                ) {
                    resourceFail("WINDOW_NOT_ON_GRID", "$pointer/from_epoch_ms", "window boundary must be on grid")
                }
                if ((to - gridStart) % step !=
                    0L
                ) {
                    resourceFail("WINDOW_NOT_ON_GRID", "$pointer/to_epoch_ms", "window boundary must be on grid")
                }
                ResourceWindowV1(id, from, to)
            }.sortedWith(compareBy(ResourceWindowV1::fromEpochMillis, ResourceWindowV1::toEpochMillis, ResourceWindowV1::id))
    windows.zipWithNext().firstOrNull { (left, right) -> right.fromEpochMillis < left.toEpochMillis }?.let { (_, right) ->
        resourceFail(
            "OVERLAPPING_WINDOWS",
            "/windows/${values.indexOfFirst { it.resourceObject("").resourceString("id", "") == right.id }}",
            "windows must not overlap",
        )
    }
    return windows
}

private fun parseRules(
    values: JsonArray,
    series: List<ResourceSeriesV1>,
): List<ResourceRuleV1> {
    if (values.size > MAX_RESOURCE_RULES) resourceFail("RESOURCE_LIMIT_EXCEEDED", "/rules", "too many resource rules")
    val ids = HashSet<String>()
    val byId = series.associateBy(ResourceSeriesV1::id)
    return values.mapIndexed { index, element ->
        val pointer = "/rules/$index"
        val item = element.resourceObject(pointer)
        item.rejectResourceUnknown(
            setOf("id", "series_id", "unit", "operator", "threshold", "min_consecutive_cells", "effect"),
            pointer,
        )
        val id = item.resourceIdentifier("id", pointer)
        if (!ids.add(id)) resourceFail("DUPLICATE_RULE_ID", "$pointer/id", "rule id must be unique")
        val seriesId = item.resourceIdentifier("series_id", pointer)
        val unit = item.resourceIdentifier("unit", pointer)
        if (byId[seriesId]?.unit?.let { it != unit } == true) {
            resourceFail("RULE_UNIT_MISMATCH", "$pointer/unit", "rule unit differs from series unit")
        }
        val operatorName = item.resourceString("operator", pointer)
        val operator =
            ResourceOperator.entries.find { it.wireName == operatorName }
                ?: resourceFail("UNKNOWN_OPERATOR", "$pointer/operator", "unknown resource operator")
        val threshold = item.resourceDecimal("threshold", pointer)
        val minimum = item.resourceInt("min_consecutive_cells", pointer)
        if (minimum !in 1..MAX_POINTS_PER_SERIES) {
            resourceFail("INVALID_MINIMUM", "$pointer/min_consecutive_cells", "minimum must be positive and bounded")
        }
        val effectName = item.resourceString("effect", pointer)
        val effect =
            ResourceRuleEffect.entries.find { it.wireName == effectName }
                ?: resourceFail("UNKNOWN_EFFECT", "$pointer/effect", "unknown resource rule effect")
        ResourceRuleV1(id, seriesId, unit, operator, threshold, minimum, effect)
    }
}

private fun parseProvenance(
    element: JsonElement,
    pointer: String,
): ResourceProvenanceV1 {
    val value = element.resourceObject(pointer)
    value.rejectResourceUnknown(setOf("source_kind", "query_semantics", "clock_alignment"), pointer)
    return ResourceProvenanceV1(
        value.resourcePlainString("source_kind", pointer, 128),
        value.resourcePlainString("query_semantics", pointer, 512),
        value.resourcePlainString("clock_alignment", pointer, 128),
    )
}

private fun ResourceSnapshotV1.semanticJson(): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", schemaVersion)
            put("load_input_sha256", loadInputSha256)
            put("start_epoch_ms", startEpochMillis)
            put("step_ms", stepMillis)
            put("point_count", pointCount)
            put("series", buildJsonArray { series.forEach { add(it.json()) } })
            put("windows", buildJsonArray { windows.forEach { add(it.json()) } })
            put("rules", buildJsonArray { rules.forEach { add(it.json()) } })
        },
    )

private fun ResourceSnapshotV1.configJson(): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("windows", buildJsonArray { windows.forEach { add(it.json()) } })
            put("rules", buildJsonArray { rules.forEach { add(it.json()) } })
        },
    )

private fun ResourceSeriesV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("metric", metric)
        put("unit", unit)
        put("entity", entity)
        put("role", role.wireName)
        put("aggregation", aggregation.wireName)
        put("labels", buildJsonObject { labels.forEach { (key, value) -> put(key, value) } })
        put("values", buildJsonArray { values.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
    }

private fun ResourceWindowV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("from_epoch_ms", fromEpochMillis)
        put("to_epoch_ms", toEpochMillis)
    }

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

private fun ResourceSnapshotV1.gridEndEpochMillis(): Long =
    Math.addExact(startEpochMillis, Math.multiplyExact(stepMillis, pointCount.toLong()))

private fun JsonElement.resourceObject(pointer: String): JsonObject =
    this as? JsonObject ?: resourceFail("INVALID_TYPE", pointer, "expected object")

private fun JsonObject.resourceRequired(
    name: String,
    pointer: String,
): JsonElement = get(name) ?: resourceFail("MISSING_FIELD", pointer.resourceChild(name), "required field is missing")

private fun JsonObject.resourceArray(
    name: String,
    pointer: String,
): JsonArray =
    resourceRequired(name, pointer) as? JsonArray
        ?: resourceFail("INVALID_TYPE", pointer.resourceChild(name), "$name must be an array")

private fun JsonObject.optionalResourceArray(
    name: String,
    pointer: String,
): JsonArray =
    this[name]?.let { it as? JsonArray ?: resourceFail("INVALID_TYPE", pointer.resourceChild(name), "$name must be an array") }
        ?: JsonArray(emptyList())

private fun JsonObject.resourceString(
    name: String,
    pointer: String,
): String {
    val value = resourceRequired(name, pointer)
    if (value !is JsonPrimitive || !value.isString) {
        resourceFail("INVALID_TYPE", pointer.resourceChild(name), "$name must be a string")
    }
    return value.content
}

private fun JsonObject.resourceIdentifier(
    name: String,
    pointer: String,
): String = resourceString(name, pointer).also { validateResourceText(it, pointer.resourceChild(name), 128, name) }

private fun JsonObject.resourcePlainString(
    name: String,
    pointer: String,
    maxBytes: Int,
): String = resourceString(name, pointer).also { validateResourceText(it, pointer.resourceChild(name), maxBytes, name) }

private fun validateResourceText(
    value: String,
    pointer: String,
    maxBytes: Int,
    description: String,
) {
    if (value.isEmpty() || value.any(Char::isISOControl)) resourceFail("INVALID_TEXT", pointer, "$description must be non-empty plain text")
    if (value.encodeToByteArray().size > maxBytes) resourceFail("RESOURCE_LIMIT_EXCEEDED", pointer, "$description is too long")
}

private fun JsonObject.resourceLong(
    name: String,
    pointer: String,
): Long {
    val child = pointer.resourceChild(name)
    val value = resourceRequired(name, pointer)
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        resourceFail("INVALID_TYPE", child, "$name must be an integer")
    }
    return try {
        validateResourceDecimal(value.content, child).longValueExact()
    } catch (_: ArithmeticException) {
        resourceFail("INVALID_TYPE", child, "$name must be an integer")
    }
}

private fun JsonObject.resourceInt(
    name: String,
    pointer: String,
): Int {
    val value = resourceLong(name, pointer)
    return value.toInt().takeIf { it.toLong() == value }
        ?: resourceFail("INVALID_TYPE", pointer.resourceChild(name), "$name must be an integer")
}

private fun JsonObject.resourceDecimal(
    name: String,
    pointer: String,
): BigDecimal = resourceRequired(name, pointer).resourceDecimal(pointer.resourceChild(name))

private fun JsonElement.resourceDecimal(pointer: String): BigDecimal {
    if (this !is JsonPrimitive || isString || this === JsonNull || content in setOf("true", "false")) {
        resourceFail("INVALID_TYPE", pointer, "value must be a number")
    }
    return validateResourceDecimal(content, pointer)
}

private fun validateResourceDecimal(
    token: String,
    pointer: String,
): BigDecimal {
    val value =
        try {
            BigDecimal(token)
        } catch (_: NumberFormatException) {
            resourceFail("INVALID_TYPE", pointer, "value must be a finite number")
        }
    val normalized = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
    if (normalized.abs() > MAX_RESOURCE_MAGNITUDE ||
        normalized.precision() > RESOURCE_SIGNIFICANT_DIGITS_MAX ||
        maxOf(normalized.scale(), 0) > RESOURCE_FRACTIONAL_DIGITS_MAX
    ) {
        resourceFail("RESOURCE_LIMIT_EXCEEDED", pointer, "resource number exceeds precision or magnitude limits")
    }
    return value
}

private fun JsonObject.rejectResourceUnknown(
    allowed: Set<String>,
    pointer: String,
) {
    keys.firstOrNull { it !in allowed }?.let { name ->
        resourceFail("UNKNOWN_FIELD", pointer.resourceChild(name), "unknown field")
    }
}

private fun String.resourceChild(token: String): String = "$this/${token.replace("~", "~0").replace("/", "~1")}"

private fun readResourceBytes(
    source: InputStream,
    maxBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val remainingProbe = maxBytes.toLong() + 1L - total
        if (remainingProbe <= 0L) resourceFail("RESOURCE_LIMIT_EXCEEDED", "", "resource snapshot exceeds $maxBytes bytes")
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remainingProbe).toInt())
        if (count == -1) break
        total += count
        if (total > maxBytes) resourceFail("RESOURCE_LIMIT_EXCEEDED", "", "resource snapshot exceeds $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeResourceUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        resourceFail("INVALID_UTF8", "", "resource snapshot must be valid UTF-8")
    }

private data class ResourceFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun resourceFail(
    code: String,
    pointer: String,
    message: String,
): Nothing = throw ResourceFailure(PolicyValidationError(code, pointer, message))

private fun resourceInvalid(
    code: String,
    pointer: String,
    message: String,
): ResourceValidation.Invalid = ResourceValidation.Invalid(listOf(PolicyValidationError(code, pointer, message)))

internal const val MAX_RESOURCE_SNAPSHOT_BYTES = 32 * 1024 * 1024
internal const val MAX_RESOURCE_SERIES = 1024
internal const val MAX_POINTS_PER_SERIES = 100_000
internal const val MAX_RESOURCE_CELLS = 1_500_000L
internal const val MAX_RESOURCE_WINDOWS = 64
internal const val MAX_RESOURCE_RULES = 256
internal const val MAX_LABELS = 16
internal const val MAX_LABEL_KEY_BYTES = 128
internal const val MAX_LABEL_VALUE_BYTES = 512
internal const val RESOURCE_JSON_DEPTH_MAX = 12
internal const val RESOURCE_NUMERIC_TOKEN_BYTES_MAX = 64
internal const val RESOURCE_NUMERIC_EXPONENT_ABS_MAX = 64
internal const val RESOURCE_SIGNIFICANT_DIGITS_MAX = 32
internal const val RESOURCE_FRACTIONAL_DIGITS_MAX = 12
private val MAX_RESOURCE_MAGNITUDE = BigDecimal("1000000000000000000")
private val SHA256 = Regex("[0-9a-f]{64}")

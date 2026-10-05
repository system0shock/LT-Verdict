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

internal sealed interface PodViewValidation {
    class Valid internal constructor(
        val view: PodViewV1,
        val canonicalSha256: String,
        rawBytes: ByteArray,
    ) : PodViewValidation {
        private val sourceBytes = rawBytes.copyOf()

        fun rawBytes(): ByteArray = sourceBytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : PodViewValidation
}

internal data class PodViewV1(
    val loadInputSha256: String,
    val resourceSnapshotSha256: String,
    val arm: String?,
    val startEpochMillis: Long,
    val stepMillis: Long,
    val columnCount: Int,
    val coverage: PodViewCoverage,
    val pods: List<PodViewPod>,
    val rows: List<PodViewRow>,
)

internal data class PodViewCoverage(
    val podsObservedTotal: Int,
    val podsIncluded: Int,
    val rowsObservedTotal: Int,
    val rowsIncluded: Int,
    val selection: PodViewSelection,
)

internal data class PodViewSelection(
    val kind: PodViewSelectionKind,
    val metric: String?,
    val limit: Int?,
)

internal enum class PodViewSelectionKind(
    val wireName: String,
) {
    ALL("ALL"),
    WORST_BY_METRIC("WORST_BY_METRIC"),
}

internal data class PodViewPod(
    val pod: String,
    val service: String,
    val containers: List<PodViewContainer>,
)

internal data class PodViewContainer(
    val name: String,
    val role: PodViewContainerRole,
)

internal enum class PodViewContainerRole(
    val wireName: String,
) {
    APP("app"),
    SIDECAR("sidecar"),
    INIT("init"),
}

internal data class PodViewRow(
    val id: String,
    val pod: String,
    val container: String?,
    val metric: String,
    val unit: String,
    val aggregation: ResourceAggregation,
    val values: List<BigDecimal?>,
)

/**
 * Checks and canonicalizes a pod-view.v1 document (ADR 0020, sections 2-3). The core neither truncates nor chooses
 * "worst" pods: an input above any limit is rejected as a whole and the first violation is reported.
 */
internal fun validatePodView(
    source: InputStream,
    maxBytes: Int = MAX_POD_VIEW_BYTES,
): PodViewValidation {
    require(maxBytes >= 0)
    return try {
        val raw = readPodViewBytes(source, maxBytes)
        val text = decodePodViewUtf8(raw)
        StrictJsonScanner(
            text,
            POD_VIEW_JSON_DEPTH_MAX,
            POD_VIEW_SCANNER_TOKEN_BYTES_MAX,
            POD_VIEW_SCANNER_EXPONENT_ABS_MAX,
            "pod view",
            ::podViewScannerFail,
        ).scan()
        val view = parsePodView(Json.parseToJsonElement(text))
        PodViewValidation.Valid(view, sha256Hex(view.canonicalBytes()), raw)
    } catch (failure: PodViewFailure) {
        PodViewValidation.Invalid(listOf(failure.error))
    } catch (_: IOException) {
        podViewInvalid("", "pod view could not be read")
    } catch (_: SerializationException) {
        podViewInvalid("", "pod view is not valid JSON")
    }
}

private fun parsePodView(element: JsonElement): PodViewV1 {
    val root = element.podObject("")
    root.rejectUnknown(
        setOf(
            "schema_version",
            "load_input_sha256",
            "resource_snapshot_sha256",
            "arm",
            "start_epoch_ms",
            "step_ms",
            "column_count",
            "coverage",
            "pods",
            "rows",
        ),
        "",
    )
    if (root.podString("schema_version", "") != "pod-view.v1") podViewFail(INVALID, "/schema_version", "expected pod-view.v1")
    val loadHash = root.podHash("load_input_sha256")
    val snapshotHash = root.podHash("resource_snapshot_sha256")
    val arm = root["arm"]?.let { root.podIdentifier("arm", "") }
    val start = root.podLong("start_epoch_ms", "")
    if (start !in
        0..MAX_TIMESTAMP_EPOCH_MILLIS
    ) {
        podViewFail(INVALID, "/start_epoch_ms", "grid start is outside the supported timestamp range")
    }
    val step = root.podLong("step_ms", "")
    if (step < 1_000 || step % 1_000L != 0L) podViewFail(INVALID, "/step_ms", "step must be a positive whole number of seconds")
    val columns = root.podLong("column_count", "")
    if (columns > MAX_POD_VIEW_COLUMNS) podViewFail(LIMIT, "/column_count", "column count exceeds $MAX_POD_VIEW_COLUMNS")
    if (columns < 1) podViewFail(INVALID, "/column_count", "column count must be positive")
    val gridEnd =
        try {
            Math.addExact(start, Math.multiplyExact(step, columns))
        } catch (_: ArithmeticException) {
            MAX_TIMESTAMP_EPOCH_MILLIS + 1
        }
    if (gridEnd > MAX_TIMESTAMP_EPOCH_MILLIS) podViewFail(INVALID, "/step_ms", "grid end is outside the supported timestamp range")

    val pods = parsePods(root.podArray("pods", ""))
    val rows = parseRows(root.podArray("rows", ""), pods, columns.toInt())
    val coverage = parseCoverage(root.podRequired("coverage", "").podObject("/coverage"))
    checkCoverage(coverage, pods.size, rows.size)

    val services = pods.associate { it.pod to it.service }
    return PodViewV1(
        loadHash,
        snapshotHash,
        arm,
        start,
        step,
        columns.toInt(),
        coverage,
        pods.sortedWith(podOrder).map { pod ->
            pod.copy(containers = pod.containers.sortedWith { left, right -> compareUtf8(left.name, right.name) })
        },
        rows.sortedWith(rowOrder(services)),
    )
}

private fun parseCoverage(item: JsonObject): PodViewCoverage {
    val pointer = "/coverage"
    item.rejectUnknown(setOf("pods_observed_total", "pods_included", "rows_observed_total", "rows_included", "selection"), pointer)
    val podsObserved = item.podCount("pods_observed_total", pointer, MAX_POD_VIEW_OBSERVED_PODS)
    val podsIncluded = item.podCount("pods_included", pointer, Int.MAX_VALUE)
    val rowsObserved = item.podCount("rows_observed_total", pointer, Int.MAX_VALUE)
    val rowsIncluded = item.podCount("rows_included", pointer, Int.MAX_VALUE)
    val selection = item.podRequired("selection", pointer).podObject("$pointer/selection")
    val selectionPointer = "$pointer/selection"
    val kindName = selection.podString("kind", selectionPointer)
    val kind =
        PodViewSelectionKind.entries.find { it.wireName == kindName }
            ?: podViewFail(INVALID, "$selectionPointer/kind", "unknown selection kind")
    val parsed =
        when (kind) {
            PodViewSelectionKind.ALL -> {
                selection.rejectUnknown(setOf("kind"), selectionPointer)
                PodViewSelection(kind, null, null)
            }

            PodViewSelectionKind.WORST_BY_METRIC -> {
                selection.rejectUnknown(setOf("kind", "metric", "limit"), selectionPointer)
                if (selection["metric"] == null || selection["limit"] == null) {
                    podViewFail(INVALID, selectionPointer, "WORST_BY_METRIC requires metric and limit")
                }
                val metric = selection.podIdentifier("metric", selectionPointer)
                val limit = selection.podLong("limit", selectionPointer)
                if (limit !in 1..MAX_POD_VIEW_PODS) podViewFail(INVALID, selectionPointer, "limit must be from 1 to $MAX_POD_VIEW_PODS")
                PodViewSelection(kind, metric, limit.toInt())
            }
        }
    return PodViewCoverage(podsObserved, podsIncluded, rowsObserved, rowsIncluded, parsed)
}

private fun checkCoverage(
    coverage: PodViewCoverage,
    podCount: Int,
    rowCount: Int,
) {
    val pointer = "/coverage"
    if (coverage.podsIncluded != podCount) podViewFail(INVALID, pointer, "pods_included must equal the length of pods")
    if (coverage.rowsIncluded != rowCount) podViewFail(INVALID, pointer, "rows_included must equal the length of rows")
    if (coverage.podsObservedTotal < coverage.podsIncluded) podViewFail(INVALID, pointer, "pods_observed_total is below pods_included")
    if (coverage.rowsObservedTotal < coverage.rowsIncluded) podViewFail(INVALID, pointer, "rows_observed_total is below rows_included")
    if (coverage.selection.kind == PodViewSelectionKind.ALL &&
        (coverage.podsObservedTotal != coverage.podsIncluded || coverage.rowsObservedTotal != coverage.rowsIncluded)
    ) {
        podViewFail(INVALID, pointer, "reduced coverage requires selection WORST_BY_METRIC")
    }
}

private fun parsePods(values: JsonArray): List<PodViewPod> {
    if (values.size > MAX_POD_VIEW_PODS) podViewFail(LIMIT, "/pods", "pod count exceeds $MAX_POD_VIEW_PODS")
    val names = HashSet<String>()
    val pods =
        values.mapIndexed { index, element ->
            val pointer = "/pods/$index"
            val item = element.podObject(pointer)
            item.rejectUnknown(setOf("pod", "service", "containers"), pointer)
            val pod = item.podText("pod", pointer, MAX_POD_NAME_BYTES)
            val service = item.podIdentifier("service", pointer)
            if (!names.add(pod)) podViewFail(DUPLICATE, "$pointer/pod", "pod name must be unique in the file")
            PodViewPod(pod, service, parseContainers(item.podArray("containers", pointer), "$pointer/containers"))
        }
    if (pods.mapTo(HashSet(), PodViewPod::service).size > MAX_POD_VIEW_SERVICES) {
        podViewFail(LIMIT, "/pods", "service count exceeds $MAX_POD_VIEW_SERVICES")
    }
    return pods
}

private fun parseContainers(
    values: JsonArray,
    pointer: String,
): List<PodViewContainer> {
    if (values.size > MAX_POD_VIEW_CONTAINERS) podViewFail(LIMIT, pointer, "container count exceeds $MAX_POD_VIEW_CONTAINERS")
    val names = HashSet<String>()
    return values.mapIndexed { index, element ->
        val child = "$pointer/$index"
        val item = element.podObject(child)
        item.rejectUnknown(setOf("name", "role"), child)
        val name = item.podIdentifier("name", child)
        if (!names.add(name)) podViewFail(DUPLICATE, "$child/name", "container name must be unique inside the pod")
        val roleName = item.podString("role", child)
        val role =
            PodViewContainerRole.entries.find { it.wireName == roleName }
                ?: podViewFail(INVALID, "$child/role", "unknown container role")
        PodViewContainer(name, role)
    }
}

private fun parseRows(
    values: JsonArray,
    pods: List<PodViewPod>,
    columns: Int,
): List<PodViewRow> {
    if (values.size > MAX_POD_VIEW_ROWS) podViewFail(LIMIT, "/rows", "row count exceeds $MAX_POD_VIEW_ROWS")
    val containersByPod = pods.associate { it.pod to it.containers.mapTo(HashSet(), PodViewContainer::name) }
    val ids = HashSet<String>()
    val identities = HashSet<Triple<String, String?, String>>()
    return values.mapIndexed { index, element ->
        val pointer = "/rows/$index"
        val item = element.podObject(pointer)
        item.rejectUnknown(setOf("id", "pod", "container", "metric", "unit", "aggregation", "values"), pointer)
        val id = item.podIdentifier("id", pointer)
        val pod = item.podText("pod", pointer, MAX_POD_NAME_BYTES)
        val container = if (item.podRequired("container", pointer) === JsonNull) null else item.podIdentifier("container", pointer)
        val metric = item.podIdentifier("metric", pointer)
        val unit = item.podIdentifier("unit", pointer)
        val aggregationName = item.podString("aggregation", pointer)
        val aggregation =
            ResourceAggregation.entries.find { it.wireName == aggregationName }
                ?: podViewFail(INVALID, "$pointer/aggregation", "unsupported aggregation")
        val samples = item.podArray("values", pointer)
        if (samples.size != columns) podViewFail(INVALID, "$pointer/values", "values length must equal column_count")
        val cells = samples.mapIndexed { cell, sample -> if (sample === JsonNull) null else sample.podCell("$pointer/values/$cell") }
        val known = containersByPod[pod] ?: podViewFail(UNKNOWN, "$pointer/pod", "row references a pod that is not listed")
        if (container != null &&
            container !in known
        ) {
            podViewFail(UNKNOWN, "$pointer/container", "row references a container that is not listed")
        }
        if (!identities.add(Triple(pod, container, metric))) {
            podViewFail(DUPLICATE, pointer, "row identity (pod, container, metric) must be unique")
        }
        if (!ids.add(id)) podViewFail(DUPLICATE, "$pointer/id", "row id must be unique")
        PodViewRow(id, pod, container, metric, unit, aggregation, cells)
    }
}

private fun rowOrder(services: Map<String, String>): Comparator<PodViewRow> =
    Comparator { left, right ->
        compareUtf8(services.getValue(left.pod), services.getValue(right.pod)).takeIf { it != 0 }
            ?: compareUtf8(left.pod, right.pod).takeIf { it != 0 }
            ?: compareUtf8(left.container, right.container).takeIf { it != 0 }
            ?: compareUtf8(left.metric, right.metric)
    }

private val podOrder: Comparator<PodViewPod> =
    Comparator { left, right ->
        compareUtf8(left.service, right.service).takeIf { it != 0 } ?: compareUtf8(left.pod, right.pod)
    }

private fun compareUtf8(
    left: String?,
    right: String?,
): Int =
    when {
        left == null && right == null -> 0
        left == null -> -1
        right == null -> 1
        else -> java.util.Arrays.compareUnsigned(left.encodeToByteArray(), right.encodeToByteArray())
    }

private fun PodViewV1.canonicalBytes(): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "pod-view.v1")
            put("load_input_sha256", loadInputSha256)
            put("resource_snapshot_sha256", resourceSnapshotSha256)
            if (arm != null) put("arm", arm)
            put("start_epoch_ms", startEpochMillis)
            put("step_ms", stepMillis)
            put("column_count", columnCount)
            put(
                "coverage",
                buildJsonObject {
                    put("pods_observed_total", coverage.podsObservedTotal)
                    put("pods_included", coverage.podsIncluded)
                    put("rows_observed_total", coverage.rowsObservedTotal)
                    put("rows_included", coverage.rowsIncluded)
                    put(
                        "selection",
                        buildJsonObject {
                            put("kind", coverage.selection.kind.wireName)
                            coverage.selection.metric?.let { put("metric", it) }
                            coverage.selection.limit?.let { put("limit", it) }
                        },
                    )
                },
            )
            put(
                "pods",
                buildJsonArray {
                    pods.forEach { pod ->
                        add(
                            buildJsonObject {
                                put("pod", pod.pod)
                                put("service", pod.service)
                                put(
                                    "containers",
                                    buildJsonArray {
                                        pod.containers.forEach {
                                            add(
                                                buildJsonObject {
                                                    put("name", it.name)
                                                    put("role", it.role.wireName)
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
            put("rows", buildJsonArray { rows.forEach { add(it.json()) } })
        },
    )

private fun PodViewRow.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("pod", pod)
        put("container", container?.let(::JsonPrimitive) ?: JsonNull)
        put("metric", metric)
        put("unit", unit)
        put("aggregation", aggregation.wireName)
        put("values", buildJsonArray { values.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
    }

private fun JsonElement.podObject(pointer: String): JsonObject = this as? JsonObject ?: podViewFail(INVALID, pointer, "expected object")

private fun JsonObject.podRequired(
    name: String,
    pointer: String,
): JsonElement = get(name) ?: podViewFail(INVALID, pointer.podChild(name), "required field is missing")

private fun JsonObject.podArray(
    name: String,
    pointer: String,
): JsonArray = podRequired(name, pointer) as? JsonArray ?: podViewFail(INVALID, pointer.podChild(name), "$name must be an array")

private fun JsonObject.podString(
    name: String,
    pointer: String,
): String {
    val value = podRequired(name, pointer)
    if (value !is JsonPrimitive || !value.isString) podViewFail(INVALID, pointer.podChild(name), "$name must be a string")
    return value.content
}

private fun JsonObject.podHash(name: String): String =
    podString(name, "").also {
        if (!SHA256.matches(it)) podViewFail(INVALID, "/$name", "$name must be a lowercase SHA-256")
    }

private fun JsonObject.podIdentifier(
    name: String,
    pointer: String,
): String = podText(name, pointer, MAX_POD_VIEW_IDENTIFIER_BYTES)

private fun JsonObject.podText(
    name: String,
    pointer: String,
    maxBytes: Int,
): String {
    val value = podString(name, pointer)
    val child = pointer.podChild(name)
    if (value.isEmpty() ||
        value.any(Char::isISOControl) ||
        hasLoneSurrogate(value)
    ) {
        podViewFail(INVALID, child, "$name must be non-empty plain text")
    }
    if (value.encodeToByteArray().size > maxBytes) podViewFail(LIMIT, child, "$name exceeds $maxBytes bytes")
    return value
}

private fun hasLoneSurrogate(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
            index += 2
            continue
        }
        if (char.isSurrogate()) return true
        index++
    }
    return false
}

private fun JsonObject.podLong(
    name: String,
    pointer: String,
): Long {
    val child = pointer.podChild(name)
    val value = podRequired(name, pointer)
    if (value !is JsonPrimitive || value.isString || !INTEGER_TOKEN.matches(value.content)) {
        podViewFail(INVALID, child, "$name must be a plain integer")
    }
    return value.content.toLongOrNull() ?: podViewFail(INVALID, child, "$name is outside the supported range")
}

private fun JsonObject.podCount(
    name: String,
    pointer: String,
    max: Int,
): Int {
    val value = podLong(name, pointer)
    if (value !in 0..max) podViewFail(INVALID, pointer.podChild(name), "$name must be from 0 to $max")
    return value.toInt()
}

private fun JsonElement.podCell(pointer: String): BigDecimal {
    if (this !is JsonPrimitive || isString || !DECIMAL_TOKEN.matches(content)) {
        podViewFail(INVALID, pointer, "value must be null or a plain number without exponent")
    }
    if (content.length > POD_VIEW_NUMERIC_TOKEN_BYTES_MAX) {
        podViewFail(INVALID, pointer, "numeric token exceeds $POD_VIEW_NUMERIC_TOKEN_BYTES_MAX bytes")
    }
    return BigDecimal(content)
}

private fun JsonObject.rejectUnknown(
    allowed: Set<String>,
    pointer: String,
) {
    keys.firstOrNull { it !in allowed }?.let { podViewFail(INVALID, pointer.podChild(it), "unknown field") }
}

private fun String.podChild(token: String): String = "$this/${token.replace("~", "~0").replace("/", "~1")}"

private fun readPodViewBytes(
    source: InputStream,
    maxBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes.toLong() + 1L - total).toInt())
        if (count == -1) break
        total += count
        if (total > maxBytes) podViewFail(LIMIT, "", "pod view exceeds $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodePodViewUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        podViewFail(INVALID, "", "pod view must be valid UTF-8")
    }

private data class PodViewFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun podViewFail(
    code: String,
    pointer: String,
    message: String,
): Nothing = throw PodViewFailure(PolicyValidationError(code, pointer, message))

private fun podViewInvalid(
    pointer: String,
    message: String,
): PodViewValidation.Invalid = PodViewValidation.Invalid(listOf(PolicyValidationError(INVALID, pointer, message)))

// The scanner reports every failure with resource-snapshot codes; only the nesting depth is a limit, the rest is form.
private fun podViewScannerFail(
    code: String,
    pointer: String,
    message: String,
): Nothing = podViewFail(if (code == "RESOURCE_LIMIT_EXCEEDED" && "depth" in message) LIMIT else INVALID, pointer, message)

private const val INVALID = "POD_VIEW_INVALID"
private const val LIMIT = "POD_VIEW_LIMIT_EXCEEDED"
private const val DUPLICATE = "POD_VIEW_DUPLICATE_ROW"
private const val UNKNOWN = "POD_VIEW_UNKNOWN_POD"
internal const val MAX_POD_VIEW_BYTES = 12 * 1024 * 1024
internal const val MAX_POD_VIEW_COLUMNS = 240
internal const val MAX_POD_VIEW_PODS = 256
internal const val MAX_POD_VIEW_SERVICES = 64
internal const val MAX_POD_VIEW_CONTAINERS = 4
internal const val MAX_POD_VIEW_ROWS = 2_560
internal const val MAX_POD_VIEW_OBSERVED_PODS = 1_000_000
internal const val MAX_POD_VIEW_IDENTIFIER_BYTES = 128
internal const val MAX_POD_NAME_BYTES = 253
internal const val POD_VIEW_NUMERIC_TOKEN_BYTES_MAX = 12
private const val POD_VIEW_JSON_DEPTH_MAX = 8

// The 12-byte limit applies to cells in values; scalar fields such as a 13-digit start_epoch_ms need a wider scanner bound.
private const val POD_VIEW_SCANNER_TOKEN_BYTES_MAX = 20
private const val POD_VIEW_SCANNER_EXPONENT_ABS_MAX = 64
private val SHA256 = Regex("[0-9a-f]{64}")
private val INTEGER_TOKEN = Regex("-?(0|[1-9][0-9]*)")
private val DECIMAL_TOKEN = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")

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

internal sealed interface TrendPlanValidation {
    class Valid internal constructor(
        internal val plan: TrendPlanV1,
        val semanticSha256: String,
        rawBytes: ByteArray,
    ) : TrendPlanValidation {
        private val bytes = rawBytes.copyOf()

        fun rawBytes(): ByteArray = bytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : TrendPlanValidation
}

internal enum class TrendDirection(
    val wireName: String,
) {
    INCREASE("increase"),
    DECREASE("decrease"),
    EITHER("either"),
}

internal data class TrendMagnitudeGate(
    val minSlopeUnitsPerSecond: BigDecimal,
    val minSplitHalfShiftPct: BigDecimal,
)

internal data class TrendCheckV1(
    val id: String,
    val seriesId: String,
    val windowId: String,
    val direction: TrendDirection,
    val minCells: Int,
    val magnitudeGate: TrendMagnitudeGate,
)

internal data class TrendPlanV1(
    val resourceSnapshotSha256: String,
    val checks: List<TrendCheckV1>,
)

internal fun validateTrendPlan(
    source: InputStream,
    maxBytes: Int = MAX_TREND_PLAN_BYTES,
): TrendPlanValidation =
    try {
        require(maxBytes >= 0)
        val raw = read(source, maxBytes)
        val text = utf8(raw)
        StrictJsonScanner(
            text,
            TREND_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "trend plan",
            ::fail,
        ).scan()
        val plan = parseTrend(Json.parseToJsonElement(text))
        TrendPlanValidation.Valid(plan, sha256Hex(canonicalJson(plan.json())), raw)
    } catch (e: TrendFailure) {
        TrendPlanValidation.Invalid(listOf(e.error))
    } catch (_: IOException) {
        invalid("TREND_READ_ERROR", "", "trend plan could not be read")
    } catch (_: SerializationException) {
        invalid("MALFORMED_JSON", "", "trend plan is not valid JSON")
    } catch (_: IllegalArgumentException) {
        invalid("MALFORMED_JSON", "", "trend plan is not valid JSON")
    }

internal fun validateTrendBinding(
    plan: TrendPlanValidation.Valid,
    resources: ResourceValidation.Valid,
): List<PolicyValidationError> {
    val out = mutableListOf<PolicyValidationError>()
    val parsed = plan.plan
    val snapshot = resources.snapshot
    if (parsed.resourceSnapshotSha256 != resources.semanticSha256) {
        out +=
            PolicyValidationError(
                "TREND_SNAPSHOT_MISMATCH",
                "/resource_snapshot_sha256",
                "trend plan does not match the resource snapshot",
            )
    }
    val series = snapshot.series.associateBy { it.id }
    val windows = snapshot.windows.associateBy { it.id }
    parsed.checks.forEachIndexed { index, check ->
        val pointer = "/checks/$index"
        if (!series.containsKey(check.seriesId)) {
            out += PolicyValidationError("TREND_SERIES_NOT_FOUND", pointer.child("series_id"), "series not found")
        }
        if (!windows.containsKey(check.windowId)) {
            out += PolicyValidationError("TREND_WINDOW_NOT_FOUND", pointer.child("window_id"), "window not found")
        }
    }
    return out
}

private fun parseTrend(element: JsonElement): TrendPlanV1 {
    val root = element.obj("")
    root.closed(setOf("schema_version", "resource_snapshot_sha256", "checks"), "")
    if (root.string("schema_version", "") != TREND_SCHEMA_VERSION) {
        fail("INVALID_SCHEMA_VERSION", "/schema_version", "expected $TREND_SCHEMA_VERSION")
    }
    val snapshot = root.hash("resource_snapshot_sha256", "", "INVALID_SNAPSHOT_HASH")
    return TrendPlanV1(snapshot, checks(root.array("checks", "")))
}

private fun checks(values: JsonArray): List<TrendCheckV1> {
    if (values.size !in 1..MAX_TREND_CHECKS) {
        fail("RESOURCE_LIMIT_EXCEEDED", "/checks", "check count must be between one and $MAX_TREND_CHECKS")
    }
    val ids = hashSetOf<String>()
    return values.mapIndexed { index, element ->
        val pointer = "/checks/$index"
        val value = element.obj(pointer)
        value.closed(setOf("id", "series_id", "window_id", "direction", "min_cells", "magnitude_gate"), pointer)
        val id = value.id("id", pointer)
        if (!ids.add(id)) fail("DUPLICATE_CHECK_ID", pointer.child("id"), "check id must be unique")
        val minCells = value.int("min_cells", pointer)
        if (minCells !in TREND_MIN_CELLS_FLOOR..MAX_POINTS_PER_SERIES) {
            fail(
                "INVALID_RANGE",
                pointer.child("min_cells"),
                "min_cells must be between $TREND_MIN_CELLS_FLOOR and $MAX_POINTS_PER_SERIES",
            )
        }
        TrendCheckV1(
            id,
            value.id("series_id", pointer),
            value.id("window_id", pointer),
            value.enum("direction", pointer, TrendDirection.entries) { it.wireName },
            minCells,
            gate(value.objectField("magnitude_gate", pointer), pointer.child("magnitude_gate")),
        )
    }
}

private fun gate(
    value: JsonObject,
    pointer: String,
): TrendMagnitudeGate {
    value.closed(setOf("min_slope_units_per_second", "min_split_half_shift_pct"), pointer)
    val slope = value.decimal("min_slope_units_per_second", pointer)
    if (slope <= BigDecimal.ZERO) {
        fail("INVALID_RANGE", pointer.child("min_slope_units_per_second"), "slope gate must be positive")
    }
    val percentage = value.decimal("min_split_half_shift_pct", pointer)
    if (percentage <= BigDecimal.ZERO) {
        fail("INVALID_RANGE", pointer.child("min_split_half_shift_pct"), "percentage gate must be positive")
    }
    return TrendMagnitudeGate(slope, percentage)
}

private fun TrendPlanV1.json() =
    buildJsonObject {
        put("schema_version", TREND_SCHEMA_VERSION)
        put("resource_snapshot_sha256", resourceSnapshotSha256)
        put(
            "checks",
            buildJsonArray {
                checks.forEach { check ->
                    add(
                        buildJsonObject {
                            put("id", check.id)
                            put("series_id", check.seriesId)
                            put("window_id", check.windowId)
                            put("direction", check.direction.wireName)
                            put("min_cells", check.minCells)
                            put(
                                "magnitude_gate",
                                buildJsonObject {
                                    put(
                                        "min_slope_units_per_second",
                                        JsonPrimitive(check.magnitudeGate.minSlopeUnitsPerSecond),
                                    )
                                    put("min_split_half_shift_pct", JsonPrimitive(check.magnitudeGate.minSplitHalfShiftPct))
                                },
                            )
                        },
                    )
                }
            },
        )
    }

private fun JsonElement.obj(p: String) = this as? JsonObject ?: fail("INVALID_TYPE", p, "expected object")

private fun JsonObject.objectField(
    n: String,
    p: String,
) = (this[n] ?: fail("MISSING_FIELD", p.child(n), "required field is missing")).obj(p.child(n))

private fun JsonObject.array(
    n: String,
    p: String,
) = (this[n] ?: fail("MISSING_FIELD", p.child(n), "required field is missing")) as? JsonArray
    ?: fail("INVALID_TYPE", p.child(n), "$n must be an array")

private fun JsonObject.string(
    n: String,
    p: String,
) = (this[n] ?: fail("MISSING_FIELD", p.child(n), "required field is missing")).str(p.child(n))

private fun JsonElement.str(p: String): String {
    if (this !is JsonPrimitive || !isString) {
        fail("INVALID_TYPE", p, "value must be a string")
    }
    return content
}

private fun JsonObject.id(
    n: String,
    p: String,
) = string(n, p).also { identifier(it, p.child(n)) }

private fun identifier(
    s: String,
    p: String,
) {
    if (s.isEmpty() || s.any(Char::isISOControl)) {
        fail("INVALID_TEXT", p, "identifier must be non-empty plain text")
    }
    if (s.encodeToByteArray().size > TREND_IDENTIFIER_BYTES) {
        fail("RESOURCE_LIMIT_EXCEEDED", p, "identifier is too long")
    }
}

private fun JsonObject.hash(
    n: String,
    p: String,
    c: String,
) = string(n, p).also {
    if (!TREND_SHA256.matches(it)) fail(c, p.child(n), "hash must be lowercase SHA-256")
}

private fun JsonObject.decimal(
    n: String,
    p: String,
) = (this[n] ?: fail("MISSING_FIELD", p.child(n), "required field is missing")).decimal(p.child(n))

private fun JsonElement.decimal(p: String): BigDecimal {
    if (this !is JsonPrimitive || isString || this === JsonNull || content in setOf("true", "false")) {
        fail("INVALID_TYPE", p, "value must be a number")
    }
    val value =
        try {
            BigDecimal(content)
        } catch (_: NumberFormatException) {
            fail("INVALID_TYPE", p, "value must be finite")
        }
    val normalized = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
    if (normalized.abs() > TREND_MAX_MAGNITUDE ||
        normalized.precision() > RESOURCE_SIGNIFICANT_DIGITS_MAX ||
        maxOf(normalized.scale(), 0) > RESOURCE_FRACTIONAL_DIGITS_MAX
    ) {
        fail("RESOURCE_LIMIT_EXCEEDED", p, "number exceeds precision or magnitude limits")
    }
    return value
}

private fun JsonObject.int(
    n: String,
    p: String,
) = try {
    decimal(n, p).intValueExact()
} catch (_: ArithmeticException) {
    fail("INVALID_TYPE", p.child(n), "$n must be an integer")
}

private fun <T> JsonObject.enum(
    n: String,
    p: String,
    v: Iterable<T>,
    name: (T) -> String,
): T {
    val value = string(n, p)
    return v.firstOrNull { name(it) == value } ?: fail("INVALID_VALUE", p.child(n), "unsupported value")
}

private fun JsonObject.closed(
    allowed: Set<String>,
    p: String,
) {
    keys.firstOrNull { it !in allowed }?.let { fail("UNKNOWN_FIELD", p.child(it), "unknown field") }
}

private fun String.child(t: String) = "$this/${t.replace("~", "~0").replace("/", "~1")}"

private fun read(
    s: InputStream,
    max: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val remaining = max.toLong() + 1L - total
        if (remaining <= 0) fail("RESOURCE_LIMIT_EXCEEDED", "", "trend plan exceeds $max bytes")
        val count = s.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (count < 0) break
        total += count
        if (total > max) fail("RESOURCE_LIMIT_EXCEEDED", "", "trend plan exceeds $max bytes")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun utf8(b: ByteArray) =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        fail("INVALID_UTF8", "", "trend plan must be valid UTF-8")
    }

private data class TrendFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun fail(
    c: String,
    p: String,
    m: String,
): Nothing = throw TrendFailure(PolicyValidationError(c, p, m))

private fun invalid(
    c: String,
    p: String,
    m: String,
) = TrendPlanValidation.Invalid(listOf(PolicyValidationError(c, p, m)))

private const val TREND_SCHEMA_VERSION = "trend-plan.v1"
private const val TREND_IDENTIFIER_BYTES = 128
private val TREND_SHA256 = Regex("[0-9a-f]{64}")
private val TREND_MAX_MAGNITUDE = BigDecimal("1000000000000000000")
internal const val MAX_TREND_PLAN_BYTES = 1_048_576
internal const val TREND_JSON_DEPTH_MAX = 12
internal const val MAX_TREND_CHECKS = 32
internal const val TREND_MIN_CELLS_FLOOR = 30

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

internal sealed interface CapacityPlanValidation {
    class Valid internal constructor(
        internal val plan: CapacityPlanV1,
        val semanticSha256: String,
        rawBytes: ByteArray,
    ) : CapacityPlanValidation {
        private val bytes = rawBytes.copyOf()

        fun rawBytes(): ByteArray = bytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : CapacityPlanValidation
}

internal enum class CapacityLoadAxis(
    val wireName: String,
    val unit: String,
) {
    RPS("rps", "requests/s"),
    CONCURRENCY("concurrency", "count"),
    USERS("users", "count"),
}

internal data class CapacityPlanV1(
    val loadInputSha256: String,
    val resourceSnapshotSha256: String,
    val loadAxis: CapacityLoadAxis,
    val achievedSeriesId: String?,
    val targetToleranceRatio: BigDecimal,
    val requiredCapacity: BigDecimal?,
    val generatorGuardRuleIds: List<String>,
    val stages: List<CapacityStageV1>,
)

internal data class CapacityStageV1(
    val id: String,
    val target: BigDecimal,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val evaluationWindowId: String,
)

internal fun validateCapacityPlan(
    source: InputStream,
    maxBytes: Int = MAX_CAPACITY_PLAN_BYTES,
): CapacityPlanValidation =
    try {
        require(maxBytes >= 0)
        val raw = read(source, maxBytes)
        val text = utf8(raw)
        StrictJsonScanner(
            text,
            CAPACITY_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "capacity plan",
            ::fail,
        ).scan()
        val plan = parse(Json.parseToJsonElement(text))
        CapacityPlanValidation.Valid(plan, sha256Hex(canonicalJson(plan.json())), raw)
    } catch (e: CapacityFailure) {
        CapacityPlanValidation.Invalid(listOf(e.error))
    } catch (_: IOException) {
        invalid("CAPACITY_READ_ERROR", "", "capacity plan could not be read")
    } catch (_: SerializationException) {
        invalid("MALFORMED_JSON", "", "capacity plan is not valid JSON")
    } catch (_: IllegalArgumentException) {
        invalid("MALFORMED_JSON", "", "capacity plan is not valid JSON")
    }

internal fun validateCapacityBinding(
    plan: CapacityPlanValidation.Valid,
    inputSha256: String,
    resources: ResourceValidation.Valid,
): List<PolicyValidationError> {
    val out = mutableListOf<PolicyValidationError>()
    val p = plan.plan
    val s = resources.snapshot

    fun error(
        code: String,
        pointer: String,
        message: String,
    ) {
        out += PolicyValidationError(code, pointer, message)
    }
    if (p.loadInputSha256 !=
        inputSha256
    ) {
        error("CAPACITY_LOAD_HASH_MISMATCH", "/load_input_sha256", "capacity plan does not match the load input")
    }
    if (p.resourceSnapshotSha256 !=
        resources.semanticSha256
    ) {
        error("CAPACITY_SNAPSHOT_MISMATCH", "/resource_snapshot_sha256", "capacity plan does not match the resource snapshot")
    }
    if (10_000L % s.stepMillis != 0L) {
        error("CAPACITY_INCOMPATIBLE_GRID", "", "resource grid must divide ten seconds")
    }
    val series = s.series.associateBy { it.id }
    val windows = s.windows.associateBy { it.id }
    if (p.loadAxis != CapacityLoadAxis.RPS) {
        when (val a = p.achievedSeriesId?.let(series::get)) {
            null -> error("CAPACITY_SERIES_NOT_FOUND", "/achieved_load/achieved_series_id", "achieved series not found")
            else ->
                when {
                    a.unit != p.loadAxis.unit || a.aggregation != ResourceAggregation.INTERVAL_MEAN ->
                        error(
                            "CAPACITY_SERIES_INCOMPATIBLE",
                            "/achieved_load/achieved_series_id",
                            "achieved series has incompatible unit or aggregation",
                        )
                    a.values.filterNotNull().any {
                        it.signum() <
                            0
                    } ->
                        error(
                            "CAPACITY_SERIES_NEGATIVE",
                            "/achieved_load/achieved_series_id",
                            "achieved series contains negative observations",
                        )
                }
        }
    }
    p.generatorGuardRuleIds.forEachIndexed { i, id ->
        when (val rule = s.rules.firstOrNull { it.id == id }) {
            null -> error("CAPACITY_GUARD_NOT_FOUND", "/generator_guard_rule_ids/$i", "guard rule not found")
            else ->
                when {
                    rule.effect !=
                        ResourceRuleEffect.DIAGNOSTIC ->
                        error(
                            "CAPACITY_GUARD_NOT_DIAGNOSTIC",
                            "/generator_guard_rule_ids/$i",
                            "guard rule must be diagnostic",
                        )
                    series[rule.seriesId]?.role !=
                        ResourceRole.GENERATOR ->
                        error(
                            "CAPACITY_GUARD_NOT_GENERATOR",
                            "/generator_guard_rule_ids/$i",
                            "guard rule must use a generator series",
                        )
                }
        }
    }
    p.stages.forEachIndexed { i, stage ->
        val pointer = "/stages/$i"
        val window = windows[stage.evaluationWindowId]
        if (window == null) {
            error("CAPACITY_WINDOW_NOT_FOUND", "$pointer/evaluation_window_id", "evaluation window not found")
        } else if (window.fromEpochMillis <
            stage.fromEpochMillis ||
            window.toEpochMillis > stage.toEpochMillis
        ) {
            error("CAPACITY_STAGE_OUTSIDE_WINDOW", pointer, "evaluation window must be inside its stage")
        }
        if (window != null && (window.fromEpochMillis % 10_000L != 0L || window.toEpochMillis % 10_000L != 0L)) {
            error(
                "CAPACITY_WINDOW_NOT_ON_10S_GRID",
                "$pointer/evaluation_window_id",
                "evaluation window must align to ten-second cells",
            )
        }
    }
    return out
}

private fun parse(element: JsonElement): CapacityPlanV1 {
    val root = element.obj("")
    root.closed(
        setOf(
            "schema_version",
            "load_input_sha256",
            "resource_snapshot_sha256",
            "load_axis",
            "achieved_load",
            "generator_guard_rule_ids",
            "stages",
        ),
        "",
    )
    if (root.string("schema_version", "") !=
        "capacity-plan.v1"
    ) {
        fail("INVALID_SCHEMA_VERSION", "/schema_version", "expected capacity-plan.v1")
    }
    val load = root.hash("load_input_sha256", "", "INVALID_LOAD_HASH")
    val snapshot = root.hash("resource_snapshot_sha256", "", "INVALID_SNAPSHOT_HASH")
    val axis = root.enum("load_axis", "", CapacityLoadAxis.entries) { it.wireName }
    val achieved = root.objectField("achieved_load", "")
    achieved.closed(setOf("statistic", "target_tolerance_ratio", "required_capacity", "achieved_series_id"), "/achieved_load")
    if (achieved.string("statistic", "/achieved_load") !=
        "p05_10s"
    ) {
        fail("INVALID_VALUE", "/achieved_load/statistic", "statistic must be p05_10s")
    }
    val tolerance = achieved.decimal("target_tolerance_ratio", "/achieved_load")
    if (tolerance < BigDecimal.ZERO ||
        tolerance >= BigDecimal.ONE
    ) {
        fail("INVALID_RANGE", "/achieved_load/target_tolerance_ratio", "tolerance must be in [0,1)")
    }
    val required = achieved["required_capacity"]?.decimal("/achieved_load/required_capacity")
    if (required != null &&
        required <= BigDecimal.ZERO
    ) {
        fail("INVALID_RANGE", "/achieved_load/required_capacity", "required capacity must be positive")
    }
    val series =
        achieved["achieved_series_id"]?.str("/achieved_load/achieved_series_id")?.also {
            identifier(it, "/achieved_load/achieved_series_id")
        }
    if ((axis == CapacityLoadAxis.RPS) ==
        (series != null)
    ) {
        fail("INVALID_ACHIEVED_SERIES", "/achieved_load/achieved_series_id", "series is required only for concurrency and users")
    }
    val guards = root.array("generator_guard_rule_ids", "")
    if (guards.size >
        MAX_CAPACITY_GUARDS
    ) {
        fail("RESOURCE_LIMIT_EXCEEDED", "/generator_guard_rule_ids", "too many generator guards")
    }
    val ids =
        guards.mapIndexed {
            i,
            e,
            ->
            e.str("/generator_guard_rule_ids/$i").also { identifier(it, "/generator_guard_rule_ids/$i") }
        }
    if (ids.toSet().size !=
        ids.size
    ) {
        fail("DUPLICATE_GUARD_ID", "/generator_guard_rule_ids", "guard ids must be unique")
    }
    return CapacityPlanV1(load, snapshot, axis, series, tolerance, required, ids, stages(root.array("stages", "")))
}

private fun stages(values: JsonArray): List<CapacityStageV1> {
    if (values.size !in
        1..MAX_CAPACITY_STAGES
    ) {
        fail("RESOURCE_LIMIT_EXCEEDED", "/stages", "stage count must be between one and $MAX_CAPACITY_STAGES")
    }
    val ids = hashSetOf<String>()
    val result =
        values.mapIndexed {
            i,
            e,
            ->
            val pointer = "/stages/$i"
            val o = e.obj(pointer)
            o.closed(setOf("id", "target", "from_epoch_ms", "to_epoch_ms", "evaluation_window_id"), pointer)
            val id = o.id("id", pointer)
            if (!ids.add(id)) fail("DUPLICATE_STAGE_ID", "$pointer/id", "stage id must be unique")
            val target = o.decimal("target", pointer)
            if (target <=
                BigDecimal.ZERO
            ) {
                fail("INVALID_RANGE", "$pointer/target", "target must be positive")
            }
            val from = o.long("from_epoch_ms", pointer)
            val to = o.long("to_epoch_ms", pointer)
            if (from !in
                0..MAX_TIMESTAMP_EPOCH_MILLIS ||
                to !in 0..MAX_TIMESTAMP_EPOCH_MILLIS ||
                from >= to
            ) {
                fail("INVALID_STAGE", pointer, "stage timestamps must be non-empty and supported")
            }
            CapacityStageV1(id, target, from, to, o.id("evaluation_window_id", pointer))
        }
    if (result.sortedBy { it.fromEpochMillis }.zipWithNext().any { (a, b) ->
            b.fromEpochMillis < a.toEpochMillis
        }
    ) {
        fail("OVERLAPPING_STAGES", "/stages", "stages must not overlap")
    }
    return result
}

private fun CapacityPlanV1.json() =
    buildJsonObject {
        put("schema_version", "capacity-plan.v1")
        put("load_input_sha256", loadInputSha256)
        put("resource_snapshot_sha256", resourceSnapshotSha256)
        put("load_axis", loadAxis.wireName)
        put(
            "achieved_load",
            buildJsonObject {
                put("statistic", "p05_10s")
                put("target_tolerance_ratio", JsonPrimitive(targetToleranceRatio))
                requiredCapacity?.let { put("required_capacity", JsonPrimitive(it)) }
                achievedSeriesId?.let { put("achieved_series_id", it) }
            },
        )
        put("generator_guard_rule_ids", buildJsonArray { generatorGuardRuleIds.sorted().forEach { add(JsonPrimitive(it)) } })
        put(
            "stages",
            buildJsonArray {
                stages.forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("target", JsonPrimitive(s.target))
                            put("from_epoch_ms", s.fromEpochMillis)
                            put("to_epoch_ms", s.toEpochMillis)
                            put("evaluation_window_id", s.evaluationWindowId)
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
    if (this !is JsonPrimitive ||
        !isString
    ) {
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
    if (s.isEmpty() ||
        s.any(Char::isISOControl)
    ) {
        fail("INVALID_TEXT", p, "identifier must be non-empty plain text")
    }
    if (s.encodeToByteArray().size >
        128
    ) {
        fail("RESOURCE_LIMIT_EXCEEDED", p, "identifier is too long")
    }
}

private fun JsonObject.hash(
    n: String,
    p: String,
    c: String,
) = string(n, p).also {
    if (!Regex("[0-9a-f]{64}").matches(it))fail(c, p.child(n), "hash must be lowercase SHA-256")
}

private fun JsonObject.decimal(
    n: String,
    p: String,
) = (this[n] ?: fail("MISSING_FIELD", p.child(n), "required field is missing")).decimal(p.child(n))

private fun JsonElement.decimal(p: String): BigDecimal {
    if (this !is JsonPrimitive ||
        isString ||
        this === JsonNull ||
        content in setOf("true", "false")
    ) {
        fail("INVALID_TYPE", p, "value must be a number")
    }
    val d =
        try {
            BigDecimal(content)
        } catch (_: NumberFormatException) {
            fail("INVALID_TYPE", p, "value must be finite")
        }
    val x =
        if (d.signum() ==
            0
        ) {
            BigDecimal.ZERO
        } else {
            d.stripTrailingZeros()
        }
    ;if (x.abs() > BigDecimal("1000000000000000000") ||
        x.precision() > RESOURCE_SIGNIFICANT_DIGITS_MAX ||
        maxOf(x.scale(), 0) > RESOURCE_FRACTIONAL_DIGITS_MAX
    ) {
        fail("RESOURCE_LIMIT_EXCEEDED", p, "number exceeds precision or magnitude limits")
    }
    return d
}

private fun JsonObject.long(
    n: String,
    p: String,
) = try {
    decimal(n, p).longValueExact()
} catch (_: ArithmeticException) {
    fail("INVALID_TYPE", p.child(n), "$n must be an integer")
}

private fun <T> JsonObject.enum(
    n: String,
    p: String,
    v: Iterable<T>,
    name: (T) -> String,
): T {
    val x = string(n, p)
    return v.firstOrNull {
        name(it) ==
            x
    }
        ?: fail("INVALID_VALUE", p.child(n), "unsupported value")
}

private fun JsonObject.closed(
    allowed: Set<String>,
    p: String,
) {
    keys
        .firstOrNull {
            it !in
                allowed
        }?.let { fail("UNKNOWN_FIELD", p.child(it), "unknown field") }
}

private fun String.child(t: String) = "$this/${t.replace("~","~0").replace("/","~1")}"

private fun read(
    s: InputStream,
    max: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val b = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val remaining = max.toLong() + 1L - total
        if (remaining <= 0) fail("RESOURCE_LIMIT_EXCEEDED", "", "capacity plan exceeds $max bytes")
        val n = s.read(b, 0, minOf(b.size.toLong(), remaining).toInt())
        if (n < 0) break
        total += n
        if (total > max) fail("RESOURCE_LIMIT_EXCEEDED", "", "capacity plan exceeds $max bytes")
        out.write(b, 0, n)
    }
    return out.toByteArray()
}

private fun utf8(b: ByteArray) =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(
                CodingErrorAction.REPORT,
            ).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (_: CharacterCodingException) {
        fail("INVALID_UTF8", "", "capacity plan must be valid UTF-8")
    }

private data class CapacityFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun fail(
    c: String,
    p: String,
    m: String,
): Nothing = throw CapacityFailure(PolicyValidationError(c, p, m))

private fun invalid(
    c: String,
    p: String,
    m: String,
) = CapacityPlanValidation.Invalid(listOf(PolicyValidationError(c, p, m)))

internal const val MAX_CAPACITY_PLAN_BYTES = 1_048_576
internal const val CAPACITY_JSON_DEPTH_MAX = 12
internal const val MAX_CAPACITY_STAGES = 64
internal const val MAX_CAPACITY_GUARDS = 256

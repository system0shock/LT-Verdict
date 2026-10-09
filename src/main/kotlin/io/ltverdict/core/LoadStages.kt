package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

// load-stages.v1 (ADR 0030): the person declares the stages of a run as offsets from the run start; the stages with the role
// `steady` become the windows of the shared window policy evaluation, with an empty resource side.

internal sealed interface LoadStagesValidation {
    class Valid internal constructor(
        val stages: List<StageV1>,
        /** SHA-256 of the canonical bytes; the value of `load_stages_sha256` of the identity. */
        val sha256: String,
        canonical: ByteArray,
    ) : LoadStagesValidation {
        private val bytes = canonical.copyOf()

        /** The bytes stored as load-stages.json: [sha256] is their SHA-256. */
        fun canonicalBytes(): ByteArray = bytes.copyOf()
    }

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : LoadStagesValidation
}

internal enum class StageRole(
    val wireName: String,
) {
    STEADY("steady"),
    EXCLUDED("excluded"),
}

internal data class StageV1(
    val id: String,
    val role: StageRole,
    val fromOffsetMillis: Long,
    val toOffsetMillis: Long,
)

internal data class ResolvedStage(
    val id: String,
    val role: StageRole,
    val fromOffsetMillis: Long,
    val toOffsetMillis: Long,
    val fromEpochMillis: Long,
    /** After the clip to the run end for a steady stage. */
    val toEpochMillis: Long,
    val clippedToRunEnd: Boolean,
)

internal data class ResolvedStages(
    val windows: List<ResourceWindowV1>,
    val stages: List<ResolvedStage>,
)

internal fun validateLoadStages(
    source: InputStream,
    maxBytes: Int = MAX_LOAD_STAGES_BYTES,
): LoadStagesValidation =
    try {
        require(maxBytes >= 0)
        val text = stagesUtf8(stagesRead(source, maxBytes))
        StrictJsonScanner(
            text,
            LOAD_STAGES_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "load stages",
            ::stagesFail,
        ).scan()
        val stages = parseStages(Json.parseToJsonElement(text))
        val canonical = canonicalJson(stagesJson(stages))
        LoadStagesValidation.Valid(stages, sha256Hex(canonical), canonical)
    } catch (e: StagesFailure) {
        LoadStagesValidation.Invalid(listOf(e.error))
    } catch (_: IOException) {
        stagesInvalid("STAGES_READ_ERROR", "", "load stages could not be read")
    } catch (_: SerializationException) {
        stagesInvalid("MALFORMED_JSON", "", "load stages are not valid JSON")
    } catch (_: IllegalArgumentException) {
        stagesInvalid("MALFORMED_JSON", "", "load stages are not valid JSON")
    }

/**
 * Offsets are milliseconds from [runStartEpochMillis]. Only a steady stage is checked against the run and becomes a window;
 * its end past [runEndEpochMillis] is clipped to the run end (`clippedToRunEnd`), and a steady stage that starts at or after the
 * run end is outside the run, because a window of zero length has no throughput. An excluded stage is a label, never checked.
 */
internal fun resolveStageWindows(
    stages: List<StageV1>,
    runStartEpochMillis: Long,
    runEndEpochMillis: Long,
): ResolvedStages {
    require(runStartEpochMillis >= 0 && runEndEpochMillis in runStartEpochMillis..MAX_TIMESTAMP_EPOCH_MILLIS) {
        "INVALID_RUN_WINDOW"
    }
    val resolved =
        stages.map { stage ->
            val from = Math.addExact(runStartEpochMillis, stage.fromOffsetMillis)
            val to = Math.addExact(runStartEpochMillis, stage.toOffsetMillis)
            if (stage.role == StageRole.STEADY) {
                require(from < runEndEpochMillis) { "STAGE_OUTSIDE_RUN" }
                ResolvedStage(
                    stage.id,
                    stage.role,
                    stage.fromOffsetMillis,
                    stage.toOffsetMillis,
                    from,
                    minOf(to, runEndEpochMillis),
                    to > runEndEpochMillis,
                )
            } else {
                ResolvedStage(stage.id, stage.role, stage.fromOffsetMillis, stage.toOffsetMillis, from, to, false)
            }
        }
    val windows =
        resolved
            .filter { it.role == StageRole.STEADY }
            .sortedBy(ResolvedStage::fromEpochMillis)
            .map { ResourceWindowV1(it.id, it.fromEpochMillis, it.toEpochMillis) }
    return ResolvedStages(windows, resolved)
}

/**
 * The resource side of the shared window evaluation when there is no resource snapshot. A window has no resource policy
 * (NO_POLICY), so the joint verdict is the business verdict. The window evaluation skips `platform_rules` (they need a
 * snapshot), so here they must not vanish: with an SLA platform rule every window is NO_VERDICT (ADR 0030, R4), and any
 * platform rule adds the coverage reason RESOURCE_SNAPSHOT_REQUIRED.
 */
internal fun stageResourceSide(
    policy: PolicyV1?,
    windows: List<ResourceWindowV1>,
    stageBinding: JsonObject,
): ResourceEvaluation {
    val platform = policy?.platformRules.orEmpty()
    val verdict = if (platform.any { it.effect == ResourceRuleEffect.SLA }) PolicyVerdict.NO_VERDICT else PolicyVerdict.NO_POLICY
    return ResourceEvaluation(
        windows.associateTo(LinkedHashMap()) { it.id to verdict },
        if (platform.isEmpty()) emptyList() else listOf(STAGES_RESOURCE_SNAPSHOT_REQUIRED),
        emptyList(),
        listOf(stageBinding),
    )
}

internal fun stageWindowMetricSummaries(
    windows: List<ResourceWindowV1>,
    metrics: Map<String, NormalizedMetrics>,
): List<JsonObject> = windows.map { windowMetricSummary(it, metrics.getValue(it.id), emptyList()) }

internal fun stageBindingEvidence(
    declarationSha256: String,
    resolved: ResolvedStages,
    runStartEpochMillis: Long,
    runEndEpochMillis: Long,
): JsonObject {
    val evaluated = resolved.windows.sumOf { it.toEpochMillis - it.fromEpochMillis }
    return StageBindingEvidence(
        id = "stage-binding",
        mode = "declared_stages",
        declarationSha256 = declarationSha256,
        runFromEpochMs = runStartEpochMillis,
        runToEpochMs = runEndEpochMillis,
        evaluatedWindowIds = resolved.windows.map(ResourceWindowV1::id),
        evaluatedMillis = evaluated,
        excludedMillis = (runEndEpochMillis - runStartEpochMillis) - evaluated,
        stages =
            resolved.stages.map {
                StageBindingStage(
                    id = it.id,
                    role = it.role.wireName,
                    fromOffsetMs = it.fromOffsetMillis,
                    toOffsetMs = it.toOffsetMillis,
                    fromEpochMs = it.fromEpochMillis,
                    toEpochMs = it.toEpochMillis,
                    clippedToRunEnd = if (it.role == StageRole.STEADY) it.clippedToRunEnd else null,
                )
            },
        verdictScope = "STEADY_WINDOW",
    ).toJson()
}

/** A separate root: the roots of AnalysisItems.kt and DerivedItems.kt are pinned by tests that list their subclasses. */
@Serializable
internal sealed interface StageEvidence {
    val id: String
}

/** Every field is a string or a Long, so the item needs no raw-number serializer. */
internal fun StageEvidence.toJson(): JsonObject = ITEM_JSON.encodeToJsonElement(StageEvidence.serializer(), this).jsonObject

@Serializable
internal data class StageBindingStage(
    val id: String,
    val role: String,
    @SerialName("from_offset_ms") val fromOffsetMs: Long,
    @SerialName("to_offset_ms") val toOffsetMs: Long,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    /** Written only for a steady stage. */
    @SerialName("clipped_to_run_end") val clippedToRunEnd: Boolean? = null,
)

@Serializable
@SerialName("stage_binding")
internal data class StageBindingEvidence(
    override val id: String,
    val mode: String,
    @SerialName("declaration_sha256") val declarationSha256: String,
    @SerialName("run_from_epoch_ms") val runFromEpochMs: Long,
    @SerialName("run_to_epoch_ms") val runToEpochMs: Long,
    @SerialName("evaluated_window_ids") val evaluatedWindowIds: List<String>,
    @SerialName("evaluated_millis") val evaluatedMillis: Long,
    @SerialName("excluded_millis") val excludedMillis: Long,
    val stages: List<StageBindingStage>,
    @SerialName("verdict_scope") val verdictScope: String,
) : StageEvidence

private fun parseStages(element: JsonElement): List<StageV1> {
    val root = element as? JsonObject ?: stagesFail("INVALID_TYPE", "", "expected object")
    root.keys.firstOrNull { it !in setOf("schema_version", "stages") }?.let {
        stagesFail("UNKNOWN_FIELD", "/${it.stagesPointerToken()}", "unknown field")
    }
    if (root.stagesString("schema_version", "") != LOAD_STAGES_SCHEMA_VERSION) {
        stagesFail("INVALID_SCHEMA_VERSION", "/schema_version", "expected $LOAD_STAGES_SCHEMA_VERSION")
    }
    val values = root["stages"] ?: stagesFail("MISSING_FIELD", "/stages", "required field is missing")
    if (values !is JsonArray) stagesFail("INVALID_TYPE", "/stages", "stages must be an array")
    if (values.size > MAX_LOAD_STAGES) {
        stagesFail("RESOURCE_LIMIT_EXCEEDED", "/stages", "stage count must be at most $MAX_LOAD_STAGES")
    }
    val ids = hashSetOf<String>()
    val declared =
        values.mapIndexed { index, value ->
            val pointer = "/stages/$index"
            val stage = value as? JsonObject ?: stagesFail("INVALID_TYPE", pointer, "expected object")
            stage.keys.firstOrNull { it !in STAGE_FIELDS }?.let {
                stagesFail("UNKNOWN_FIELD", "$pointer/${it.stagesPointerToken()}", "unknown field")
            }
            val id = stage.stagesString("id", pointer)
            stagesIdentifier(id, "$pointer/id")
            if (!ids.add(id)) stagesFail("DUPLICATE_STAGE_ID", "$pointer/id", "stage id must be unique")
            val roleName = stage.stagesString("role", pointer)
            val role =
                StageRole.entries.firstOrNull { it.wireName == roleName } ?: stagesFail("UNKNOWN_ROLE", "$pointer/role", "unsupported role")
            val from = stage.stagesOffset("from_offset_ms", pointer)
            val to = stage.stagesOffset("to_offset_ms", pointer)
            if (to <= from) stagesFail("INVALID_STAGE", "$pointer/to_offset_ms", "to_offset_ms must be greater than from_offset_ms")
            index to StageV1(id, role, from, to)
        }
    val sorted = declared.sortedBy { it.second.fromOffsetMillis }
    sorted.zipWithNext().forEach { (previous, next) ->
        if (next.second.fromOffsetMillis < previous.second.toOffsetMillis) {
            stagesFail("OVERLAPPING_STAGES", "/stages/${next.first}/from_offset_ms", "stages must not overlap")
        }
    }
    if (sorted.none { it.second.role == StageRole.STEADY }) {
        stagesFail("STAGES_NO_STEADY", "/stages", "at least one stage must have the role steady")
    }
    return sorted.map { it.second }
}

private fun stagesJson(stages: List<StageV1>): JsonObject =
    buildJsonObject {
        put("schema_version", LOAD_STAGES_SCHEMA_VERSION)
        put(
            "stages",
            buildJsonArray {
                stages.forEach { stage ->
                    add(
                        buildJsonObject {
                            put("id", stage.id)
                            put("role", stage.role.wireName)
                            put("from_offset_ms", stage.fromOffsetMillis)
                            put("to_offset_ms", stage.toOffsetMillis)
                        },
                    )
                }
            },
        )
    }

private fun JsonObject.stagesString(
    name: String,
    pointer: String,
): String {
    val value = this[name] ?: stagesFail("MISSING_FIELD", "$pointer/$name", "required field is missing")
    if (value !is JsonPrimitive || !value.isString) stagesFail("INVALID_TYPE", "$pointer/$name", "value must be a string")
    return value.content
}

/** An integer by value (`1e3` is 1000, `1500.5` is not), then the range; the scanner has already bounded the token. */
private fun JsonObject.stagesOffset(
    name: String,
    pointer: String,
): Long {
    val where = "$pointer/$name"
    val value = this[name] ?: stagesFail("MISSING_FIELD", where, "required field is missing")
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content == "true" || value.content == "false") {
        stagesFail("INVALID_TYPE", where, "value must be a number")
    }
    val number = BigDecimal(value.content).let { if (it.signum() == 0) BigDecimal.ZERO else it.stripTrailingZeros() }
    if (number.scale() > 0) stagesFail("INVALID_TYPE", where, "value must be an integer")
    if (number.signum() < 0 || number > BigDecimal.valueOf(MAX_LOAD_STAGE_OFFSET_MS)) {
        stagesFail("INVALID_STAGE_OFFSET", where, "offset must be between 0 and $MAX_LOAD_STAGE_OFFSET_MS milliseconds")
    }
    return number.longValueExact()
}

private fun stagesIdentifier(
    id: String,
    pointer: String,
) {
    if (id.isEmpty() || id.any(Char::isISOControl)) stagesFail("INVALID_TEXT", pointer, "identifier must be non-empty plain text")
    if (id.encodeToByteArray().size > LOAD_STAGES_IDENTIFIER_BYTES) stagesFail("RESOURCE_LIMIT_EXCEEDED", pointer, "identifier is too long")
}

private fun String.stagesPointerToken() = replace("~", "~0").replace("/", "~1")

private fun stagesRead(
    source: InputStream,
    max: Int,
): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), max.toLong() + 1L - total).toInt())
        if (count < 0) break
        total += count
        if (total > max) stagesFail("RESOURCE_LIMIT_EXCEEDED", "", "load stages exceed $max bytes")
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun stagesUtf8(bytes: ByteArray) =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        stagesFail("INVALID_UTF8", "", "load stages must be valid UTF-8")
    }

private data class StagesFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun stagesFail(
    code: String,
    pointer: String,
    message: String,
): Nothing = throw StagesFailure(PolicyValidationError(code, pointer, message))

private fun stagesInvalid(
    code: String,
    pointer: String,
    message: String,
) = LoadStagesValidation.Invalid(listOf(PolicyValidationError(code, pointer, message)))

private const val LOAD_STAGES_SCHEMA_VERSION = "load-stages.v1"
private const val LOAD_STAGES_IDENTIFIER_BYTES = 128
private const val STAGES_RESOURCE_SNAPSHOT_REQUIRED = "RESOURCE_SNAPSHOT_REQUIRED"
private val STAGE_FIELDS = setOf("id", "role", "from_offset_ms", "to_offset_ms")
internal const val MAX_LOAD_STAGES_BYTES = 65_536
internal const val LOAD_STAGES_JSON_DEPTH_MAX = 8
internal const val MAX_LOAD_STAGES = 16
internal const val MAX_LOAD_STAGE_OFFSET_MS = 604_800_000L

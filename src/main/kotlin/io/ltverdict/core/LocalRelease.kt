package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException

internal const val RELEASE_SCHEMA = "local-release.v1"
internal const val MAX_RELEASE_ANALYSES = 8
internal const val MAX_RELEASE_BYTES = 8 * 1024
internal const val MAX_RELEASE_TEXT_BYTES = 128
internal const val MAX_RELEASE_NOTES_BYTES = 1024
private const val MAX_RELEASE_REASONS = 16
private const val MAX_RELEASE_REASON_BYTES = 64
internal val RELEASE_ID = Regex("[0-9]{15}-[0-9a-f]{8}")
private val RELEASE_SUFFIX = Regex("[0-9a-f]{8}")
private val RELEASE_RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")
private val RELEASE_SHA256 = Regex("[0-9a-f]{64}")
private val RELEASE_INSTANT = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")
internal val RELEASE_PROFILE_FIELDS =
    listOf("scenario_mix", "environment_dataset", "load_model", "targets_stages", "pacing", "generator_limits")
private val RELEASE_FIELDS =
    setOf(
        "schema_version",
        "release_id",
        "series",
        "label",
        "run_id",
        "started_at",
        "analyses",
        "profile",
        "notes",
        "created_at",
        "updated_at",
    )
internal val RELEASE_DRAFT_FIELDS = RELEASE_FIELDS - setOf("release_id", "created_at", "updated_at")
internal val RELEASE_IMMUTABLE_FIELDS = listOf("schema_version", "release_id", "series", "run_id", "started_at", "created_at")
private val RELEASE_ANALYSIS_FIELDS =
    setOf("analysis_id", "arm", "coverage_reasons", "coverage_status", "policy_sha256", "policy_verdict", "run_validity")
private val RELEASE_VERDICTS = setOf("PASS", "FAIL", "NO_POLICY", "NO_VERDICT")
private val RELEASE_VALIDITIES = setOf("VALID", "DEGRADED", "INVALID")
private val RELEASE_COVERAGE = setOf("COMPLETE", "INCOMPLETE")

internal fun normalizeReleaseText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).replace("\r\n", "\n").trim()

private fun invalid(): Nothing = throw IllegalArgumentException("INVALID_RELEASE")

private fun checkedText(
    value: String,
    maxBytes: Int,
    multiline: Boolean,
): String {
    if (value.isEmpty() || value != normalizeReleaseText(value) || value.encodeToByteArray().size > maxBytes) invalid()
    if (value.any { it.isISOControl() && !(multiline && it == '\n') }) invalid()
    return value
}

private fun JsonObject.text(
    name: String,
    maxBytes: Int = MAX_RELEASE_TEXT_BYTES,
    multiline: Boolean = false,
): String {
    val primitive = this[name] as? JsonPrimitive ?: invalid()
    if (!primitive.isString) invalid()
    return checkedText(primitive.content, maxBytes, multiline)
}

private fun JsonObject.textOrNull(
    name: String,
    maxBytes: Int,
    multiline: Boolean = false,
): String? =
    when (val value = this[name]) {
        null -> invalid()
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) checkedText(value.content, maxBytes, multiline) else invalid()
        else -> invalid()
    }

private fun releaseInstant(value: String): Instant {
    if (!RELEASE_INSTANT.matches(value)) invalid()
    return try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        invalid()
    }
}

internal fun releaseStartedAtMillis(startedAt: String): Long {
    val millis = releaseInstant(startedAt).toEpochMilli()
    if (millis !in 0..MAX_TIMESTAMP_EPOCH_MILLIS) invalid()
    return millis
}

internal fun releaseId(
    startedAtMillis: Long,
    suffix: String,
): String {
    require(startedAtMillis in 0..MAX_TIMESTAMP_EPOCH_MILLIS && RELEASE_SUFFIX.matches(suffix)) { "INVALID_RELEASE" }
    return startedAtMillis.toString().padStart(15, '0') + "-" + suffix
}

internal fun validateRelease(record: JsonObject): JsonObject {
    if (record.keys != RELEASE_FIELDS) invalid()
    if (record.text("schema_version", 32) != RELEASE_SCHEMA) invalid()
    val releaseId = record.text("release_id", 24)
    if (!RELEASE_ID.matches(releaseId)) invalid()
    record.text("series")
    record.text("label")
    if (!RELEASE_RUN_ID.matches(record.text("run_id", 160))) invalid()
    val millis = releaseStartedAtMillis(record.text("started_at", 40))
    if (!releaseId.startsWith(millis.toString().padStart(15, '0') + "-")) invalid()
    val analyses = record["analyses"] as? JsonArray ?: invalid()
    if (analyses.size !in 1..MAX_RELEASE_ANALYSES) invalid()
    val items = analyses.map { validateReleaseAnalysis(it as? JsonObject ?: invalid()) }
    if (items.map { it.text("analysis_id", 64) }.toSet().size != items.size) invalid()
    val arms = items.map { it.textOrNull("arm", MAX_RELEASE_TEXT_BYTES) }
    if (items.size > 1 && (arms.any { it == null } || arms.toSet().size != arms.size)) invalid()
    validateReleaseProfile(record["profile"] ?: invalid())
    record.textOrNull("notes", MAX_RELEASE_NOTES_BYTES, multiline = true)
    val created = releaseInstant(record.text("created_at", 40))
    if (releaseInstant(record.text("updated_at", 40)) < created) invalid()
    return record
}

private fun validateReleaseAnalysis(item: JsonObject): JsonObject {
    if (item.keys != RELEASE_ANALYSIS_FIELDS) invalid()
    if (!RELEASE_SHA256.matches(item.text("analysis_id", 64))) invalid()
    item.textOrNull("arm", MAX_RELEASE_TEXT_BYTES)
    if (item.text("policy_verdict", 16) !in RELEASE_VERDICTS) invalid()
    if (item.text("run_validity", 16) !in RELEASE_VALIDITIES) invalid()
    if (item.text("coverage_status", 16) !in RELEASE_COVERAGE) invalid()
    val reasons = item["coverage_reasons"] as? JsonArray ?: invalid()
    if (reasons.size > MAX_RELEASE_REASONS) invalid()
    reasons.forEach { value ->
        val primitive = value as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        checkedText(primitive.content, MAX_RELEASE_REASON_BYTES, multiline = false)
    }
    val policy = item.text("policy_sha256", 64)
    if (policy != "NO_POLICY" && !RELEASE_SHA256.matches(policy)) invalid()
    return item
}

private fun validateReleaseProfile(profile: JsonElement) {
    if (profile == JsonNull) return
    val fields = profile as? JsonObject ?: invalid()
    if (fields.keys != RELEASE_PROFILE_FIELDS.toSet()) invalid()
    if (RELEASE_PROFILE_FIELDS.map { fields.textOrNull(it, MAX_RELEASE_TEXT_BYTES) }.all { it == null }) invalid()
}

internal fun releaseAnalysisFacts(
    analysisId: String,
    result: JsonObject,
    identity: JsonObject,
): JsonObject {
    val coverage = result["analysis_coverage"] as? JsonObject ?: invalid()
    val arm =
        when (val value = identity["resource_arm"]) {
            null -> JsonNull
            is JsonPrimitive -> if (value.isString) value else invalid()
            else -> invalid()
        }
    return validateReleaseAnalysis(
        buildJsonObject {
            put("analysis_id", analysisId)
            put("arm", arm)
            put("coverage_reasons", coverage["reasons"] ?: invalid())
            put("coverage_status", coverage["status"] ?: invalid())
            put("policy_sha256", identity["policy_sha256"] ?: invalid())
            put("policy_verdict", result["policy_verdict"] ?: invalid())
            put("run_validity", result["run_validity"] ?: invalid())
        },
    )
}

internal fun compareReleaseProfiles(
    baseline: JsonObject?,
    current: JsonObject?,
): JsonObject? {
    if (baseline == null || current == null) return null
    val differing = RELEASE_PROFILE_FIELDS.filter { baseline[it] != current[it] }
    return buildJsonObject {
        put("status", if (differing.isEmpty()) "MATCH" else "MISMATCH")
        put("differing_fields", buildJsonArray { differing.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun releaseProfileSummary(profile: JsonObject?): String? =
    profile?.let {
        RELEASE_PROFILE_FIELDS
            .mapNotNull { name -> (profile[name] as? JsonPrimitive)?.takeIf { value -> value.isString }?.let { "$name=${it.content}" } }
            .joinToString("; ")
    }

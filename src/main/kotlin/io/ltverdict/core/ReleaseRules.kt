package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The rules of the release API (ADR 0019): what a request may say, how its text is normalized, what the facts of an analysis
// must satisfy, how a stored release is shown. They take documents and strings, never the storage.

// Normalizes at the border (NFC, line feeds, trim) and refuses what the stored form would refuse; empty text is null.
internal fun releaseTextField(
    raw: String,
    name: String,
    maxBytes: Int,
    multiline: Boolean = false,
): String? {
    val value = normalizeReleaseText(raw)
    if (value.isEmpty()) return null
    if (value.encodeToByteArray().size > maxBytes || value.any { it.isISOControl() && !(multiline && it == '\n') }) {
        ruleMalformed("$name is invalid")
    }
    return value
}

internal fun releaseSeriesParameter(raw: String): String =
    releaseTextField(raw, "series", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("series is invalid")

internal fun releaseAfterParameter(raw: String): String = raw.also { if (!RELEASE_ID.matches(it)) ruleMalformed("after is invalid") }

internal fun requireReleaseId(raw: String?): String = raw?.takeIf { RELEASE_ID.matches(it) } ?: ruleMalformed("Release id is invalid")

/** A creation request, checked field by field (cheap checks first: a malformed body never triggers the expensive reads). */
internal class ReleaseDraftRequest(
    val series: String,
    val label: String,
    val runId: String,
    val analysisIds: List<String>,
    val profile: JsonElement,
    val notes: JsonElement,
)

internal fun parseReleaseCreate(body: JsonObject): ReleaseDraftRequest {
    if (body.keys != RELEASE_POST_FIELDS) ruleMalformed("Release fields are invalid")
    val series =
        releaseTextField(body.baselineString("series"), "series", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("series is required")
    val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("label is required")
    val runId = releaseRunId(body)
    val analysisIds = releaseAnalysisIds(body)
    val profile = releaseProfile(body["profile"])
    val notes = releaseNotes(body["notes"])
    return ReleaseDraftRequest(series, label, runId, analysisIds, profile, notes)
}

/** A replacement request: only the editable fields. */
internal class ReleaseUpdateRequest(
    val label: String,
    val analysisIds: List<String>,
    val profile: JsonElement,
    val notes: JsonElement,
)

internal fun parseReleaseUpdate(body: JsonObject): ReleaseUpdateRequest {
    if (body.keys != RELEASE_PUT_FIELDS) ruleMalformed("Release fields are invalid")
    val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("label is required")
    val analysisIds = releaseAnalysisIds(body)
    val profile = releaseProfile(body["profile"])
    val notes = releaseNotes(body["notes"])
    return ReleaseUpdateRequest(label, analysisIds, profile, notes)
}

private val RELEASE_POST_FIELDS = setOf("series", "label", "run_id", "analyses", "profile", "notes")

private val RELEASE_PUT_FIELDS = setOf("label", "analyses", "profile", "notes")

private val ANALYSIS_ID = Regex("[0-9a-f]{64}")

private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

// An object whose six values are all empty is stored as null, so two empty claims never read as equal profiles.
private fun releaseProfile(element: JsonElement?): JsonElement {
    if (element == null) ruleMalformed("profile is required")
    if (element == JsonNull) return JsonNull
    val fields = element as? JsonObject ?: ruleMalformed("profile is invalid")
    if (fields.keys != RELEASE_PROFILE_FIELDS.toSet()) ruleMalformed("profile fields are invalid")
    val values =
        RELEASE_PROFILE_FIELDS.map { name ->
            when (val value = fields.getValue(name)) {
                JsonNull -> null
                is JsonPrimitive ->
                    if (value.isString) {
                        releaseTextField(value.content, name, MAX_RELEASE_TEXT_BYTES)
                    } else {
                        ruleMalformed("profile is invalid")
                    }
                else -> ruleMalformed("profile is invalid")
            }
        }
    if (values.all { it == null }) return JsonNull
    return JsonObject(RELEASE_PROFILE_FIELDS.zip(values).associate { (name, value) -> name to (value?.let(::JsonPrimitive) ?: JsonNull) })
}

private fun releaseNotes(element: JsonElement?): JsonElement =
    when (element) {
        null -> ruleMalformed("notes is required")
        JsonNull -> JsonNull
        is JsonPrimitive ->
            if (element.isString) {
                releaseTextField(element.content, "notes", MAX_RELEASE_NOTES_BYTES, multiline = true)?.let(::JsonPrimitive) ?: JsonNull
            } else {
                ruleMalformed("notes is invalid")
            }
        else -> ruleMalformed("notes is invalid")
    }

private fun releaseRunId(body: JsonObject): String =
    body.baselineString("run_id").takeIf { RUN_ID.matches(it) } ?: ruleMalformed("run_id is invalid")

private fun releaseAnalysisIds(body: JsonObject): List<String> {
    val values = body["analyses"] as? JsonArray ?: ruleMalformed("analyses must be an array")
    if (values.size !in 1..MAX_RELEASE_ANALYSES) ruleMalformed("analyses must hold 1-$MAX_RELEASE_ANALYSES items")
    val ids =
        values.map { item ->
            val entry = item as? JsonObject ?: ruleMalformed("analysis entry is invalid")
            if (entry.keys != setOf("analysis_id")) ruleMalformed("analysis entry is invalid")
            entry.baselineString("analysis_id").takeIf { ANALYSIS_ID.matches(it) } ?: ruleMalformed("analysis_id is invalid")
        }
    if (ids.toSet().size != ids.size) ruleMalformed("analysis_id values must differ")
    return ids
}

internal fun JsonObject.releaseField(name: String): String = (getValue(name) as JsonPrimitive).content

/**
 * The facts of the analyses of a release, one analysis at a time: [add] is called right after each verified read and keeps only
 * the extracted facts (the parsed documents are not retained), so the peak is one result. [finish] checks the arms across
 * analyses and returns the common start and the facts ordered by analysis id.
 */
internal class ReleaseFactsCollector(
    private val runId: String,
) {
    private var startedAt: String? = null
    private val facts = mutableListOf<JsonObject>()

    fun add(
        analysisId: String,
        run: JsonObject?,
        result: JsonObject,
        identity: JsonObject,
    ) {
        val analysisStart =
            (run?.get("started_at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
                try {
                    releaseStartedAtMillis(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
                ?: ruleUnprocessable("RELEASE_ANALYSIS_NO_RUN_METADATA", "Analysis has no usable run metadata")
        // Both documents must name the requested run: a run.json of another run would carry a foreign chronology.
        if ((result["run_id"] as? JsonPrimitive)?.content != runId ||
            (run["run_id"] as? JsonPrimitive)?.content != runId
        ) {
            ruleUnprocessable("RELEASE_RUN_MISMATCH", "Analysis documents belong to another run")
        }
        if (startedAt != null && startedAt != analysisStart) {
            ruleUnprocessable("RELEASE_STARTED_AT_MISMATCH", "Analyses start at different times")
        }
        startedAt = analysisStart
        facts +=
            try {
                releaseAnalysisFacts(analysisId, result, identity)
            } catch (_: IllegalArgumentException) {
                ruleUnprocessable("RELEASE_FACTS_INVALID", "Analysis facts are unavailable")
            }
    }

    fun finish(): Pair<String, List<JsonObject>> {
        val arms = facts.map { it["arm"] }
        if (facts.size > 1 && (arms.any { it == JsonNull } || arms.toSet().size != arms.size)) {
            ruleUnprocessable("RELEASE_ARM_CONFLICT", "Arms must be distinct and present when a release has several analyses")
        }
        return checkNotNull(startedAt) to facts.sortedBy { (it["analysis_id"] as JsonPrimitive).content }
    }
}

internal fun releaseDraft(
    request: ReleaseDraftRequest,
    startedAt: String,
    analyses: List<JsonObject>,
): JsonObject =
    buildJsonObject {
        put("schema_version", RELEASE_SCHEMA)
        put("series", request.series)
        put("label", request.label)
        put("run_id", request.runId)
        put("started_at", startedAt)
        put("analyses", JsonArray(analyses))
        put("profile", request.profile)
        put("notes", request.notes)
    }

/** A replacement keeps the analyses on the start the release already has. */
internal fun requireReleaseStart(
    existing: JsonObject,
    startedAt: String,
) {
    if (startedAt != existing.releaseField("started_at")) {
        ruleUnprocessable("RELEASE_STARTED_AT_MISMATCH", "Analyses start at a different time than the release")
    }
}

internal fun releaseUpdated(
    current: JsonObject,
    request: ReleaseUpdateRequest,
    analyses: List<JsonObject>,
    stamp: JsonPrimitive,
): JsonObject =
    JsonObject(
        current +
            mapOf(
                "label" to JsonPrimitive(request.label),
                "analyses" to JsonArray(analyses),
                "profile" to request.profile,
                "notes" to request.notes,
                "updated_at" to stamp,
            ),
    )

internal fun releaseStateKeys(record: JsonObject): List<Pair<String, String>> =
    (record.getValue("analyses") as JsonArray).map {
        record.releaseField("run_id") to
            ((it as JsonObject).getValue("analysis_id") as JsonPrimitive).content
    }

internal fun releaseOkStates(record: JsonObject): Map<Pair<String, String>, String> = releaseStateKeys(record).associateWith { "OK" }

// Convenience for the interface, not a decision: the server decides again from the real result when a baseline is selected.
private fun releaseIneligibleReasons(
    analysis: JsonObject,
    state: String,
): List<String> =
    when (state) {
        "OK" ->
            listOfNotNull(
                baselineCandidateRejection(
                    (analysis["policy_verdict"] as JsonPrimitive).content,
                    (analysis["run_validity"] as JsonPrimitive).content,
                    (analysis["coverage_status"] as JsonPrimitive).content,
                    (analysis["coverage_reasons"] as JsonArray).map { (it as JsonPrimitive).content },
                ),
            )
        "MISSING" -> listOf("ANALYSIS_MISSING")
        else -> listOf("ANALYSIS_CORRUPT")
    }

internal fun releaseView(
    record: JsonObject,
    states: Map<Pair<String, String>, String>,
): JsonObject {
    val runId = record.releaseField("run_id")
    val analyses =
        (record.getValue("analyses") as JsonArray).map { item ->
            val analysis = item as JsonObject
            val state = states.getValue(runId to (analysis.getValue("analysis_id") as JsonPrimitive).content)
            val reasons = releaseIneligibleReasons(analysis, state)
            JsonObject(
                analysis +
                    mapOf(
                        "analysis_state" to JsonPrimitive(state),
                        "baseline_eligible" to JsonPrimitive(reasons.isEmpty()),
                        "ineligible_reasons" to JsonArray(reasons.map(::JsonPrimitive)),
                    ),
            )
        }
    val all =
        analyses
            .flatMap {
                (it.getValue("ineligible_reasons") as JsonArray).map { reason ->
                    (reason as JsonPrimitive).content
                }
            }.distinct()
            .sorted()
    return JsonObject(
        record +
            mapOf(
                "analyses" to JsonArray(analyses),
                "baseline_eligible" to JsonPrimitive(all.isEmpty()),
                "ineligible_reasons" to JsonArray(all.map(::JsonPrimitive)),
            ),
    )
}

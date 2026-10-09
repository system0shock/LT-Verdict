package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ltverdict.core.MAX_RELEASE_ANALYSES
import io.ltverdict.core.MAX_RELEASE_NOTES_BYTES
import io.ltverdict.core.MAX_RELEASE_TEXT_BYTES
import io.ltverdict.core.RELEASE_ID
import io.ltverdict.core.RELEASE_PROFILE_FIELDS
import io.ltverdict.core.RELEASE_SCHEMA
import io.ltverdict.core.baselineCandidateRejection
import io.ltverdict.core.normalizeReleaseText
import io.ltverdict.core.releaseAnalysisFacts
import io.ltverdict.core.releaseProfileSummary
import io.ltverdict.core.releaseStartedAtMillis
import io.ltverdict.storage.MAX_RELEASES
import io.ltverdict.storage.ReleasePage
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.time.Instant
import java.time.temporal.ChronoUnit

internal fun Route.releaseRoutes(context: LocalApiContext) {
    get("/api/releases") {
        call.requireOnlyQueries("series", "after", "limit")
        val series =
            call.singleQuery("series")?.let {
                releaseTextField(it, "series", MAX_RELEASE_TEXT_BYTES)
                    ?: malformed("series is invalid")
            }
        val after = call.singleQuery("after")?.also { if (!RELEASE_ID.matches(it)) malformed("after is invalid") }
        val limit = call.intQuery("limit", DEFAULT_RELEASE_LIMIT, 1..MAX_RELEASE_LIMIT)
        val page = releaseOperation { context.store.listReleases(series, after, limit) }
        // Existence only: the full check of a reference belongs to the read by identifier.
        val states =
            releaseOperation {
                page.releases.flatMap(::releaseStateKeys).toSet().associateWith { (runId, analysisId) ->
                    if (context.store.analysisExists(runId, analysisId)) "OK" else "MISSING"
                }
            }
        call.respondJson(releasePageJson(page, states))
    }

    get("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        val id = call.releaseIdParameter()
        val record = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
        val states =
            releaseOperation {
                releaseStateKeys(record).toSet().associateWith { (runId, analysisId) -> context.store.analysisState(runId, analysisId) }
            }
        call.respondJson(releaseView(record, states))
    }

    post("/api/releases") {
        call.requireOnlyQueries()
        call.requireJson()
        val body = receiveBaselineRequest(call, "Release")
        if (body.keys != RELEASE_POST_FIELDS) malformed("Release fields are invalid")
        // Cheap field checks first: a malformed body never triggers the expensive verified reads.
        val series =
            releaseTextField(body.baselineString("series"), "series", MAX_RELEASE_TEXT_BYTES) ?: malformed("series is required")
        val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: malformed("label is required")
        val runId = releaseRunId(body)
        val analysisIds = releaseAnalysisIds(body)
        val profile = releaseProfile(body["profile"])
        val notes = releaseNotes(body["notes"])
        val (startedAt, analyses) = releaseFacts(context.store, runId, analysisIds)
        val draft =
            buildJsonObject {
                put("schema_version", RELEASE_SCHEMA)
                put("series", series)
                put("label", label)
                put("run_id", runId)
                put("started_at", startedAt)
                put("analyses", JsonArray(analyses))
                put("profile", profile)
                put("notes", notes)
            }
        val created = releaseOperation { context.store.createRelease(draft, Instant.now()) }
        call.respondJson(releaseView(created, releaseOkStates(created)), HttpStatusCode.Created)
    }

    put("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        call.requireJson()
        val id = call.releaseIdParameter()
        val body = receiveBaselineRequest(call, "Release")
        if (body.keys != RELEASE_PUT_FIELDS) malformed("Release fields are invalid")
        val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: malformed("label is required")
        val analysisIds = releaseAnalysisIds(body)
        val profile = releaseProfile(body["profile"])
        val notes = releaseNotes(body["notes"])
        val existing = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
        val (startedAt, analyses) = releaseFacts(context.store, existing.releaseField("run_id"), analysisIds)
        if (startedAt != existing.releaseField("started_at")) {
            throw ApiFailure(
                HttpStatusCode.UnprocessableEntity,
                "RELEASE_STARTED_AT_MISMATCH",
                "Analyses start at a different time than the release",
            )
        }
        val stamp = JsonPrimitive(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
        val updated =
            releaseOperation {
                context.store.replaceRelease(id) { current ->
                    JsonObject(
                        current +
                            mapOf(
                                "label" to JsonPrimitive(label),
                                "analyses" to JsonArray(analyses),
                                "profile" to profile,
                                "notes" to notes,
                                "updated_at" to stamp,
                            ),
                    )
                }
            }
        call.respondJson(releaseView(updated, releaseOkStates(updated)))
    }

    delete("/api/releases/{releaseId}") {
        call.requireOnlyQueries()
        val id = call.releaseIdParameter()
        if (!releaseOperation { context.store.deleteRelease(id) }) notFound("Release was not found")
        call.respondJson(buildJsonObject { put("release", JsonNull) })
    }
}

private val RELEASE_POST_FIELDS = setOf("series", "label", "run_id", "analyses", "profile", "notes")

private val RELEASE_PUT_FIELDS = setOf("label", "analyses", "profile", "notes")

private val ANALYSIS_ID = Regex("[0-9a-f]{64}")

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
        malformed("$name is invalid")
    }
    return value
}

// An object whose six values are all empty is stored as null, so two empty claims never read as equal profiles.
private fun releaseProfile(element: JsonElement?): JsonElement {
    if (element == null) malformed("profile is required")
    if (element == JsonNull) return JsonNull
    val fields = element as? JsonObject ?: malformed("profile is invalid")
    if (fields.keys != RELEASE_PROFILE_FIELDS.toSet()) malformed("profile fields are invalid")
    val values =
        RELEASE_PROFILE_FIELDS.map { name ->
            when (val value = fields.getValue(name)) {
                JsonNull -> null
                is JsonPrimitive ->
                    if (value.isString) releaseTextField(value.content, name, MAX_RELEASE_TEXT_BYTES) else malformed("profile is invalid")
                else -> malformed("profile is invalid")
            }
        }
    if (values.all { it == null }) return JsonNull
    return JsonObject(RELEASE_PROFILE_FIELDS.zip(values).associate { (name, value) -> name to (value?.let(::JsonPrimitive) ?: JsonNull) })
}

private fun releaseNotes(element: JsonElement?): JsonElement =
    when (element) {
        null -> malformed("notes is required")
        JsonNull -> JsonNull
        is JsonPrimitive ->
            if (element.isString) {
                releaseTextField(element.content, "notes", MAX_RELEASE_NOTES_BYTES, multiline = true)?.let(::JsonPrimitive) ?: JsonNull
            } else {
                malformed("notes is invalid")
            }
        else -> malformed("notes is invalid")
    }

private fun releaseRunId(body: JsonObject): String =
    body.baselineString("run_id").takeIf { RUN_ID.matches(it) } ?: malformed("run_id is invalid")

private fun releaseAnalysisIds(body: JsonObject): List<String> {
    val values = body["analyses"] as? JsonArray ?: malformed("analyses must be an array")
    if (values.size !in 1..MAX_RELEASE_ANALYSES) malformed("analyses must hold 1-$MAX_RELEASE_ANALYSES items")
    val ids =
        values.map { item ->
            val entry = item as? JsonObject ?: malformed("analysis entry is invalid")
            if (entry.keys != setOf("analysis_id")) malformed("analysis entry is invalid")
            entry.baselineString("analysis_id").takeIf { ANALYSIS_ID.matches(it) } ?: malformed("analysis_id is invalid")
        }
    if (ids.toSet().size != ids.size) malformed("analysis_id values must differ")
    return ids
}

private fun ApplicationCall.releaseIdParameter(): String =
    parameters["releaseId"]?.takeIf { RELEASE_ID.matches(it) } ?: malformed("Release id is invalid")

private fun JsonObject.releaseField(name: String): String = (getValue(name) as JsonPrimitive).content

// Reads one analysis at a time and drops its parsed tree after the facts are copied: the peak is one result.
private suspend fun releaseFacts(
    store: RunBundleStore,
    runId: String,
    analysisIds: List<String>,
): Pair<String, List<JsonObject>> {
    var startedAt: String? = null
    val facts = mutableListOf<JsonObject>()
    for (analysisId in analysisIds) {
        val verified =
            releaseOperation {
                try {
                    store.readVerifiedAnalysis(runId, analysisId) ?: notFound("Analysis was not found")
                } catch (failure: IllegalArgumentException) {
                    if (failure.message == "RESULT_TOO_LARGE") {
                        throw ApiFailure(
                            HttpStatusCode.UnprocessableEntity,
                            "RELEASE_RESULT_TOO_LARGE",
                            "Analysis result exceeds the verification limit",
                        )
                    }
                    throw failure
                }
            }
        val analysisStart =
            (verified.run?.get("started_at") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
                try {
                    releaseStartedAtMillis(it)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
                ?: throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "RELEASE_ANALYSIS_NO_RUN_METADATA",
                    "Analysis has no usable run metadata",
                )
        // Both documents must name the requested run: a run.json of another run would carry a foreign chronology.
        if ((verified.result["run_id"] as? JsonPrimitive)?.content != runId ||
            (verified.run?.get("run_id") as? JsonPrimitive)?.content != runId
        ) {
            throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_RUN_MISMATCH", "Analysis documents belong to another run")
        }
        if (startedAt != null && startedAt != analysisStart) {
            throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_STARTED_AT_MISMATCH", "Analyses start at different times")
        }
        startedAt = analysisStart
        facts +=
            try {
                releaseAnalysisFacts(analysisId, verified.result, verified.identity)
            } catch (_: IllegalArgumentException) {
                throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_FACTS_INVALID", "Analysis facts are unavailable")
            }
    }
    val arms = facts.map { it["arm"] }
    if (facts.size > 1 && (arms.any { it == JsonNull } || arms.toSet().size != arms.size)) {
        throw ApiFailure(
            HttpStatusCode.UnprocessableEntity,
            "RELEASE_ARM_CONFLICT",
            "Arms must be distinct and present when a release has several analyses",
        )
    }
    return checkNotNull(startedAt) to facts.sortedBy { (it["analysis_id"] as JsonPrimitive).content }
}

private fun releaseStateKeys(record: JsonObject): List<Pair<String, String>> =
    (record.getValue("analyses") as JsonArray).map {
        record.releaseField("run_id") to
            ((it as JsonObject).getValue("analysis_id") as JsonPrimitive).content
    }

private fun releaseOkStates(record: JsonObject): Map<Pair<String, String>, String> = releaseStateKeys(record).associateWith { "OK" }

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

private fun releaseView(
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

private fun releasePageJson(
    page: ReleasePage,
    states: Map<Pair<String, String>, String>,
): JsonObject =
    buildJsonObject {
        put("releases", JsonArray(page.releases.map { releaseView(it, states) }))
        put("next_after", page.nextAfter?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "series_summary",
            buildJsonArray {
                page.seriesSummary.forEach { (series, count) ->
                    add(
                        buildJsonObject {
                            put("series", series)
                            put("count", count)
                        },
                    )
                }
            },
        )
        put("corrupt_count", page.corruptCount)
        put(
            "corrupt_names",
            buildJsonArray {
                page.corruptNames.forEach {
                    add(
                        buildJsonObject {
                            put("name", it.name)
                            put("reason", it.reason)
                        },
                    )
                }
            },
        )
    }

// Profile and series are auxiliary for comparison and dynamics (ADR 0019, section 5): a registry the store refuses to scan, and
// an analysis named by several records, leave the release unknown instead of failing the request.
internal fun RunBundleStore.releasesOfAnalyses(analysisIds: Set<String>): Map<String, JsonObject> =
    try {
        findReleasesByAnalysis(analysisIds).byAnalysis
    } catch (failure: IllegalStateException) {
        if (failure.message.orEmpty().startsWith("CORRUPT_RELEASE_REGISTRY")) emptyMap() else throw failure
    } catch (_: IOException) {
        emptyMap()
    } catch (_: DirectoryIteratorException) {
        emptyMap()
    }

internal fun JsonObject?.releaseLabel(): String? = (this?.get("label") as? JsonPrimitive)?.content

internal fun JsonObject?.releaseProfile(): String? = releaseProfileSummary(this?.get("profile") as? JsonObject)

// Maps store failures to the private API codes; messages never carry user text (label, notes, profile).
private suspend fun <T> releaseOperation(action: () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            action()
        } catch (_: NoSuchElementException) {
            notFound("Release or referenced analysis was not found")
        } catch (failure: IllegalArgumentException) {
            when (failure.message) {
                "RELEASE_LIMIT_REACHED" ->
                    throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_LIMIT_REACHED", "Release limit is reached", MAX_RELEASES)
                "RELEASE_ANALYSIS_ALREADY_REGISTERED" ->
                    conflict(
                        "RELEASE_ANALYSIS_ALREADY_REGISTERED",
                        "An analysis already belongs to a release",
                    )
                "RELEASE_TOO_LARGE" -> throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "RELEASE_TOO_LARGE",
                    "Release record exceeds its size limit",
                )
                "RELEASE_CHANGED" -> conflict("RELEASE_CHANGED", "Release was changed concurrently; reload and retry")
                "INVALID_RELEASE" -> throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    "RELEASE_FACTS_INVALID",
                    "Release record is invalid",
                )
                "INVALID_RELEASE_ID", "INVALID_PAGE_LIMIT" -> malformed("Release query is invalid")
                else -> throw failure
            }
        } catch (failure: IllegalStateException) {
            val message = failure.message.orEmpty()
            when {
                message.startsWith("CORRUPT_RELEASE_REGISTRY") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE_REGISTRY", "Release registry is corrupt")
                message.startsWith("CORRUPT_RELEASE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE", "Release record is corrupt")
                message.startsWith("CORRUPT_RUN_BUNDLE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RUN_BUNDLE", "Referenced analysis is corrupt")
                else -> throw failure
            }
        }
    }

private const val DEFAULT_RELEASE_LIMIT = 50

private const val MAX_RELEASE_LIMIT = 100

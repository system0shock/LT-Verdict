package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ltverdict.core.MAX_RELEASE_TEXT_BYTES
import io.ltverdict.core.ReleaseComparisonContext
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineCandidateRejection
import io.ltverdict.core.baselineConditionConfirmation
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.compareAnalyses
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.statisticalBaselineSelection
import io.ltverdict.storage.MAX_BASELINE_CONDITION_FILES
import io.ltverdict.storage.MAX_BASELINE_SLOTS
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.storage.VerifiedAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant

internal fun Route.baselineRoutes(context: LocalApiContext) {
    get("/api/baseline") {
        call.requireOnlyQueries()
        val slots = baselineOperation { context.store.listBaselineSlots() }
        call.respondJson(
            buildJsonObject {
                // `baseline` keeps the legacy file as before slots existed; `baselines` lists every effective slot.
                put("baseline", slots.firstOrNull { it.legacy }?.selection ?: JsonNull)
                put(
                    "baselines",
                    buildJsonArray {
                        slots.forEach { slot ->
                            add(
                                buildJsonObject {
                                    put("series", slot.series)
                                    put("arm", slot.arm?.let(::JsonPrimitive) ?: JsonNull)
                                    put("source", if (slot.legacy) "LEGACY" else "SLOT")
                                    put("baseline", slot.selection)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    post("/api/baseline") {
        call.requireOnlyQueries()
        call.requireJson()
        val request = receiveBaselineRequest(call)
        val selected = selectBaseline(request, context.store)
        val slot =
            baselineOperation {
                val reference = selected.getValue("reference").jsonObject
                val identity =
                    context.store.readAnalysisIdentity(reference.baselineString("run_id"), reference.baselineString("analysis_id"))
                        ?: notFound("Referenced analysis was not found")
                context.store.replaceBaselineSlot(selected, identity.baselineArm())
            }
        call.respondJson(buildJsonObject { put("baseline", slot.selection) })
    }

    delete("/api/baseline") {
        call.requireOnlyQueries("series", "arm")
        val series = call.baselineSeriesQuery()
        val arm = call.singleQuery("arm")?.let { releaseTextField(it, "arm", MAX_RELEASE_TEXT_BYTES) ?: malformed("arm is invalid") }
        if (series == null) {
            if (arm != null) malformed("arm requires series")
            baselineOperation { context.store.clearBaseline() }
        } else {
            baselineOperation { context.store.clearBaselineSlot(series, arm) }
        }
        call.respondJson(buildJsonObject { put("baseline", JsonNull) })
    }
}

internal fun Route.comparisonRoutes(context: LocalApiContext) {
    get("/api/runs/{runId}/analyses/{analysisId}/baseline-conditions") {
        call.requireOnlyQueries("baseline_window", "current_window", "series")
        val windows = call.windowComparisonQuery()
        val current =
            buildJsonObject {
                put("run_id", call.parameters["runId"].orEmpty())
                put("analysis_id", call.parameters["analysisId"].orEmpty())
            }.baselineReference()
        val scope = resolveBaselineScope(call, context.store, current)
        val (slot, conditions) =
            baselineOperation { context.store.readBaselineSlotWithCondition(scope.series, scope.arm, current, windows) }
        val selected = slot?.selection ?: notFound("No baseline is selected")
        context.store.baselineDocuments(selected.getValue("reference").jsonObject)
        context.store.baselineDocuments(current)
        call.respondJson(buildJsonObject { put("conditions", conditions ?: JsonNull) })
    }

    post("/api/runs/{runId}/analyses/{analysisId}/baseline-conditions") {
        call.requireOnlyQueries("baseline_window", "current_window", "series")
        call.requireJson()
        val windows = call.windowComparisonQuery()
        val request = receiveBaselineRequest(call)
        if (request.keys != setOf("decision")) malformed("Baseline condition fields are invalid")
        val decision = request.baselineString("decision")
        if (decision !in BASELINE_CONDITION_DECISIONS) malformed("decision is invalid")
        val current =
            buildJsonObject {
                put("run_id", call.parameters["runId"].orEmpty())
                put("analysis_id", call.parameters["analysisId"].orEmpty())
            }.baselineReference()
        val scope = resolveBaselineScope(call, context.store, current)
        val (slot, _) =
            baselineOperation { context.store.readBaselineSlotWithCondition(scope.series, scope.arm, current, windows) }
        val selected = slot?.selection ?: notFound("No baseline is selected")
        val baselineReference = selected.getValue("reference").jsonObject
        context.store.baselineDocuments(baselineReference)
        context.store.baselineDocuments(current)
        val condition = baselineConditionRecord(baselineReference, current, windows, decision, Instant.now())
        val stored = baselineOperation { context.store.replaceBaselineCondition(condition) }
        call.respondJson(buildJsonObject { put("conditions", stored) })
    }

    get("/api/runs/{runId}/analyses/{analysisId}/comparison") {
        call.requireOnlyQueries("baseline_window", "current_window", "min_change_percent", "min_error_rate_delta", "series")
        val windows = call.windowComparisonQuery()
        val current =
            buildJsonObject {
                put("run_id", call.parameters["runId"].orEmpty())
                put("analysis_id", call.parameters["analysisId"].orEmpty())
            }.baselineReference()
        val scope = resolveBaselineScope(call, context.store, current)
        val (slot, conditions) =
            baselineOperation { context.store.readBaselineSlotWithCondition(scope.series, scope.arm, current, windows) }
        val selected = slot?.selection ?: notFound("No baseline is selected")
        val baselineReference = selected.getValue("reference").jsonObject
        val (baselineResult, baselineIdentity) = context.store.baselineDocuments(baselineReference)
        val (currentResult, currentIdentity) = context.store.baselineDocuments(current)
        val baselineAnalysis = baselineReference.getValue("analysis_id").jsonPrimitive.content
        val currentAnalysis = current.getValue("analysis_id").jsonPrimitive.content
        val releases = withContext(Dispatchers.IO) { context.store.releasesOfAnalyses(setOf(baselineAnalysis, currentAnalysis)) }
        val comparison =
            compareAnalyses(
                selected,
                current,
                baselineResult,
                baselineIdentity,
                currentResult,
                currentIdentity,
                windows,
                conditions?.let(::baselineConditionConfirmation),
                ReleaseComparisonContext(releases[baselineAnalysis], releases[currentAnalysis]),
            )
        call.respondJson(
            buildJsonObject {
                comparison.forEach { (name, value) -> put(name, value) }
                put("conditions", conditions ?: JsonNull)
            },
        )
    }
}

private suspend fun selectBaseline(
    request: JsonObject,
    store: RunBundleStore,
): JsonObject {
    val mode = request.baselineString("mode")
    // The same rule as the `series` query, so that every stored slot can be addressed again.
    val series =
        releaseTextField(request.baselineString("series"), "series", MAX_RELEASE_TEXT_BYTES)
            ?: malformed("Comparison series must contain 1–128 UTF-8 bytes")
    return when (mode) {
        "manual" -> {
            if (request.keys != setOf("mode", "series", "reference")) malformed("Manual baseline fields are invalid")
            val reference = (request["reference"] as? JsonObject)?.baselineReference() ?: malformed("Baseline reference is invalid")
            requireBaselineEligible(listOf(store.verifiedBaselineDocuments(reference)))
            manualBaselineSelection(series, reference)
        }
        "statistical" -> {
            if (request.keys != setOf("mode", "series", "candidates", "comparable")) malformed("Statistical baseline fields are invalid")
            val comparable =
                (request["comparable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: malformed("comparable must be a boolean")
            if (!comparable) baselineIneligible("BASELINE_COMPARABILITY_UNCONFIRMED")
            val values = request["candidates"] as? JsonArray ?: malformed("candidates must be an array")
            if (values.size !in 3..20) baselineIneligible("BASELINE_CANDIDATE_COUNT")
            val references = values.map { (it as? JsonObject)?.baselineReference() ?: malformed("Candidate reference is invalid") }
            if (references.map { it.getValue("run_id") }.toSet().size != references.size) baselineIneligible("BASELINE_DUPLICATE_RUN")
            val documents = references.map { store.verifiedBaselineDocuments(it) }
            requireBaselineEligible(documents)
            try {
                statisticalBaselineSelection(series, references, documents.map { it.result }, documents.map { it.identity })
            } catch (failure: IllegalArgumentException) {
                baselineIneligible(failure.message ?: "BASELINE_CANDIDATE_INVALID")
            }
        }
        else -> malformed("mode must be manual or statistical")
    }
}

internal fun JsonObject.baselineString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed("$name must be a string")

private fun JsonObject.baselineReference(): JsonObject {
    if (keys != setOf("run_id", "analysis_id") ||
        !RUN_ID.matches(baselineString("run_id")) ||
        !Regex("[0-9a-f]{64}").matches(baselineString("analysis_id"))
    ) {
        malformed("Baseline reference is invalid")
    }
    return this
}

private suspend fun RunBundleStore.baselineDocuments(reference: JsonObject): Pair<JsonObject, JsonObject> =
    baselineOperation {
        readAnalysisDocuments(reference.baselineString("run_id"), reference.baselineString("analysis_id"))
            ?: notFound("Referenced analysis was not found")
    }

// Reads the stored result with its SHA-256 and the 64 MiB bound checked (ADR 0019, sections 1 and 6).
private suspend fun RunBundleStore.verifiedBaselineDocuments(reference: JsonObject): VerifiedAnalysis =
    baselineOperation {
        try {
            readVerifiedAnalysis(reference.baselineString("run_id"), reference.baselineString("analysis_id"))
                ?: notFound("Referenced analysis was not found")
        } catch (failure: IllegalArgumentException) {
            if (failure.message == "RESULT_TOO_LARGE") baselineIneligible("BASELINE_CANDIDATE_TOO_LARGE")
            throw failure
        }
    }

// The same rule for both modes, applied in request order before any statistic is computed.
private fun requireBaselineEligible(documents: List<VerifiedAnalysis>) {
    documents.forEach { document ->
        val code = baselineCandidateRejection(document.result) ?: return@forEach
        baselineIneligible(code, if (code == "BASELINE_CANDIDATE_NOT_PASS") document.result.knownVerdict() else null)
    }
}

private fun JsonObject.knownVerdict(): String =
    (this["policy_verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KNOWN_VERDICTS } ?: "UNKNOWN"

private val KNOWN_VERDICTS = setOf("PASS", "FAIL", "NO_POLICY", "NO_VERDICT")

internal class BaselineScope(
    val series: String?,
    val arm: String?,
)

// The series of a baseline query, normalized like release series so that slot keys and release series agree.
private fun ApplicationCall.baselineSeriesQuery(): String? =
    singleQuery("series")?.let { releaseTextField(it, "series", MAX_RELEASE_TEXT_BYTES) ?: malformed("series is invalid") }

private fun JsonObject.baselineArm(): String? =
    when (val arm = this["resource_arm"]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (arm.isString) arm.content else corruptBaseline()
        else -> corruptBaseline()
    }

private fun corruptBaseline(): Nothing =
    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_BASELINE", "Baseline or referenced analysis is corrupt")

/**
 * Picks the baseline slot of an analysis (ADR 0019, section 7): the series of its release, else the `series` query, else the
 * legacy file; the arm comes from its identity. A query that contradicts the release series is refused. A release registry that
 * cannot be read counts as "no release", so a damaged registry does not block comparison.
 */
internal suspend fun resolveBaselineScope(
    call: ApplicationCall,
    store: RunBundleStore,
    current: JsonObject,
): BaselineScope {
    val explicit = call.baselineSeriesQuery()
    val analysisId = current.baselineString("analysis_id")
    val identity =
        baselineOperation { store.readAnalysisIdentity(current.baselineString("run_id"), analysisId) }
            ?: notFound("Referenced analysis was not found")
    val registered =
        baselineOperation { (store.releasesOfAnalyses(setOf(analysisId))[analysisId]?.get("series") as? JsonPrimitive)?.contentOrNull }
    if (explicit != null && registered != null && explicit != registered) {
        throw ApiFailure(HttpStatusCode.UnprocessableEntity, "BASELINE_SERIES_CONFLICT", "Series differs from the release series")
    }
    return BaselineScope(registered ?: explicit, identity.baselineArm())
}

internal suspend fun <T> baselineOperation(action: () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            action()
        } catch (_: NoSuchElementException) {
            notFound("Referenced run or analysis was not found")
        } catch (failure: IllegalArgumentException) {
            when (failure.message) {
                "BASELINE_SLOTS_LIMIT_REACHED" ->
                    throw ApiFailure(
                        HttpStatusCode.UnprocessableEntity,
                        "BASELINE_SLOTS_LIMIT_REACHED",
                        "Baseline slot limit is reached",
                        MAX_BASELINE_SLOTS,
                    )
                "BASELINE_CONDITIONS_LIMIT_REACHED" ->
                    throw ApiFailure(
                        HttpStatusCode.UnprocessableEntity,
                        "BASELINE_CONDITIONS_LIMIT_REACHED",
                        "Baseline condition record limit is reached",
                        MAX_BASELINE_CONDITION_FILES,
                    )
                "BASELINE_ANALYSIS_NOT_FOUND" -> notFound("Referenced analysis was not found")
                else -> throw failure
            }
        } catch (failure: IllegalStateException) {
            if (failure.message?.startsWith("CORRUPT_BASELINE") == true || failure.message?.startsWith("CORRUPT_RUN_BUNDLE") == true) {
                throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_BASELINE", "Baseline or referenced analysis is corrupt")
            }
            throw failure
        }
    }

private fun baselineIneligible(
    code: String,
    verdict: String? = null,
): Nothing =
    throw ApiFailure(
        HttpStatusCode.UnprocessableEntity,
        code,
        "Baseline candidate is unavailable: $code" + (verdict?.let { " (policy_verdict=$it)" } ?: ""),
    )

private fun ApplicationCall.windowComparisonQuery(): WindowComparisonRequest? {
    val baseline = singleQuery("baseline_window")
    val current = singleQuery("current_window")
    if (baseline == null && current == null) {
        if (singleQuery("min_change_percent") != null || singleQuery("min_error_rate_delta") != null) {
            malformed("Materiality thresholds require both windows")
        }
        return null
    }
    if (listOf(baseline, current).any { it == null || it.isBlank() || it.encodeToByteArray().size > 128 || it.any(Char::isISOControl) }) {
        malformed("Both window IDs must contain 1–128 UTF-8 bytes without control characters")
    }
    return WindowComparisonRequest(
        checkNotNull(baseline),
        checkNotNull(current),
        boundedDecimalQuery("min_change_percent", "5", "1000"),
        boundedDecimalQuery("min_error_rate_delta", "0.001", "1"),
    )
}

private fun ApplicationCall.boundedDecimalQuery(
    name: String,
    default: String,
    maximum: String,
): BigDecimal {
    val raw = singleQuery(name) ?: default
    if (raw.length > 64 || !Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?").matches(raw)) malformed("$name is invalid")
    val value = raw.toBigDecimalOrNull() ?: malformed("$name is invalid")
    if (value.precision() > 32 || value.scale() !in -12..12 || value.signum() <= 0 || value > BigDecimal(maximum)) {
        malformed("$name is invalid")
    }
    return value
}

private val BASELINE_CONDITION_DECISIONS = setOf("CONFIRMED", "NOT_CONFIRMED", "UNKNOWN")

package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ltverdict.core.BaselineScope
import io.ltverdict.core.ReleaseComparisonContext
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineArm
import io.ltverdict.core.baselineConditionConfirmation
import io.ltverdict.core.baselineConditionDecision
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.baselineIneligible
import io.ltverdict.core.baselineReference
import io.ltverdict.core.baselineScope
import io.ltverdict.core.baselineSeriesParameter
import io.ltverdict.core.baselineSlotAddress
import io.ltverdict.core.baselineString
import io.ltverdict.core.compareAnalyses
import io.ltverdict.core.planBaselineSelection
import io.ltverdict.core.selectBaseline
import io.ltverdict.core.windowComparisonRequest
import io.ltverdict.storage.MAX_BASELINE_CONDITION_FILES
import io.ltverdict.storage.MAX_BASELINE_SLOTS
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.storage.VerifiedAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        val plan = planBaselineSelection(request)
        val documents = plan.references.map { context.store.verifiedBaselineDocuments(it) }
        val selected = selectBaseline(plan, documents.map { it.result }, documents.map { it.identity })
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
        val (series, arm) = baselineSlotAddress(call.singleQuery("series"), call.singleQuery("arm"))
        if (series == null) {
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
        val windows = call.windowComparison()
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
        val windows = call.windowComparison()
        val request = receiveBaselineRequest(call)
        val decision = baselineConditionDecision(request)
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
        val windows = call.windowComparison()
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

// Reads what the slot of an analysis depends on (ADR 0019, section 7); baselineScope decides. A release registry that cannot be
// read counts as "no release", so a damaged registry does not block comparison.
internal suspend fun resolveBaselineScope(
    call: ApplicationCall,
    store: RunBundleStore,
    current: JsonObject,
): BaselineScope {
    val explicit = call.singleQuery("series")?.let(::baselineSeriesParameter)
    val analysisId = current.baselineString("analysis_id")
    val identity =
        baselineOperation { store.readAnalysisIdentity(current.baselineString("run_id"), analysisId) }
            ?: notFound("Referenced analysis was not found")
    val registered =
        baselineOperation { (store.releasesOfAnalyses(setOf(analysisId))[analysisId]?.get("series") as? JsonPrimitive)?.contentOrNull }
    return baselineScope(explicit, registered, identity)
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

private fun ApplicationCall.windowComparison(): WindowComparisonRequest? =
    windowComparisonRequest(
        singleQuery("baseline_window"),
        singleQuery("current_window"),
        singleQuery("min_change_percent"),
        singleQuery("min_error_rate_delta"),
    )

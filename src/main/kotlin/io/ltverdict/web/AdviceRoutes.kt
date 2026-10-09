package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.ai.AdviceJobStatus
import io.ltverdict.ai.AdviceSubmitResult
import io.ltverdict.ai.validModelSlug
import io.ltverdict.core.StrictJsonScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun Route.adviceRoutes(context: LocalApiContext) {
    get("/api/runs/{runId}/analyses/{analysisId}/advice") {
        call.requireOnlyQueries()
        context.store.requireAnalysis(call)
        val advice =
            withContext(Dispatchers.IO) {
                context.adviceService?.read(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())
            }
        call.respondJson(
            buildJsonObject {
                put("advice", advice?.document ?: JsonNull)
                put(
                    "job",
                    context.adviceJobs?.latest(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty())?.toJson()
                        ?: JsonNull,
                )
            },
        )
    }

    post("/api/runs/{runId}/analyses/{analysisId}/advice") {
        call.requireOnlyQueries()
        call.requireJson()
        val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(ADVICE_BODY_MAX + 1) } }
        if (bytes.size > ADVICE_BODY_MAX) malformed("Advice request exceeds $ADVICE_BODY_MAX bytes")
        // ADR 0023 (CM1, CM4): no transfer consent is asked. The body is empty or a closed object with the deprecated,
        // ignored `confirm_external_transfer:true` and an optional `model_id`; `false`, other keys and duplicate keys are 400.
        val requestedModel = parseAdviceRequest(bytes)
        context.store.requireAnalysis(call)
        val jobs =
            context.adviceJobs ?: throw ApiFailure(HttpStatusCode.ServiceUnavailable, "AI_UNAVAILABLE", "AI runner is not configured")
        // The slug comes only from the configuration: the request names one of its models or none (the default).
        val models = context.aiModels
        if (requestedModel != null && models?.models?.any { it.id == requestedModel } != true) {
            malformed("model_id is not a model of the configuration")
        }
        val selectedModel = requestedModel ?: models?.defaultModel
        when (
            val submitted =
                jobs.submit(call.parameters["runId"].orEmpty(), call.parameters["analysisId"].orEmpty(), selectedModel)
        ) {
            is AdviceSubmitResult.Accepted -> call.respondJson(submitted.status.toJson(), HttpStatusCode.Accepted)
            AdviceSubmitResult.Busy -> throw ApiFailure(HttpStatusCode.Conflict, "AI_BUSY", "An AI task is already running")
        }
    }

    get("/api/advice-jobs/{jobId}") {
        call.requireOnlyQueries()
        val status = context.adviceJobs?.status(call.parameters["jobId"].orEmpty()) ?: notFound("Advice task was not found")
        call.respondJson(status.toJson())
    }

    delete("/api/advice-jobs/{jobId}") {
        call.requireOnlyQueries()
        val status = context.adviceJobs?.cancel(call.parameters["jobId"].orEmpty()) ?: notFound("Advice task was not found")
        call.respondJson(status.toJson())
    }
}

private const val ADVICE_BODY_MAX = 512

/** Closed grammar of the advice request body (ADR 0023, D2); returns the requested `model_id`, if any. */
private fun parseAdviceRequest(bytes: ByteArray): String? {
    val text = bytes.decodeToString(throwOnInvalidSequence = false)
    if (text.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) return null
    val invalid = "Advice request body must be empty or an object with confirm_external_transfer true and model_id"
    StrictJsonScanner(text, 4, 16, 4, "advice request") { _, _, _ -> malformed(invalid) }.scan()
    val root =
        try {
            Json.parseToJsonElement(text) as? JsonObject ?: malformed(invalid)
        } catch (_: SerializationException) {
            malformed(invalid)
        }
    if (root.keys.any { it != "confirm_external_transfer" && it != "model_id" }) malformed(invalid)
    root["confirm_external_transfer"]?.let { confirm ->
        val flag = confirm as? JsonPrimitive
        if (flag == null || flag.isString || flag.booleanOrNull != true) malformed(invalid)
    }
    val model = root["model_id"] ?: return null
    val slug = model as? JsonPrimitive
    if (slug == null || !slug.isString || !validModelSlug(slug.content)) malformed(invalid)
    return slug.content
}

private fun AdviceJobStatus.toJson(): JsonObject =
    buildJsonObject {
        put("job_id", jobId)
        put("run_id", runId)
        put("analysis_id", analysisId)
        put("state", state.name)
        put("reused", reused?.let(::JsonPrimitive) ?: JsonNull)
        put("failure", failure?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("unavailable_reason", unavailableReason?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("model_id", modelId?.let(::JsonPrimitive) ?: JsonNull)
    }

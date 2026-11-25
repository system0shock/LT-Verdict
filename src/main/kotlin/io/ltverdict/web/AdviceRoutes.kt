package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.ai.AdviceFailure
import io.ltverdict.ai.AdviceJobStatus
import io.ltverdict.ai.AdviceRunResult
import io.ltverdict.ai.AdviceSubmitResult
import io.ltverdict.ai.MAX_ADVICE_OUTPUT_BYTES
import io.ltverdict.ai.RUNNER_VERSION_PATTERN
import io.ltverdict.ai.SELF_REPORTED_PROVENANCE_KIND
import io.ltverdict.ai.SelfReportedProvenance
import io.ltverdict.ai.validModelSlug
import io.ltverdict.ai.validSelfReportedRunnerId
import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.canonicalJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.charset.CharacterCodingException

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

    put("/api/runs/{runId}/analyses/{analysisId}/advice") {
        call.requireOnlyQueries()
        call.requireJson()
        val length = call.request.contentLength()
        if (length != null && length > SELF_REPORTED_BODY_MAX) tooLarge("Self-reported advice exceeds its resource limit")
        val bytes =
            withContext(Dispatchers.IO) {
                call.receiveChannel().toInputStream().use { it.readNBytes(SELF_REPORTED_BODY_MAX + 1) }
            }
        if (bytes.size > SELF_REPORTED_BODY_MAX) tooLarge("Self-reported advice exceeds its resource limit")
        val (outputBytes, provenance) = parseSelfReportedAdviceRequest(bytes)
        context.store.requireAnalysis(call)
        val service =
            context.adviceService
                ?: throw ApiFailure(HttpStatusCode.ServiceUnavailable, "AI_UNAVAILABLE", "AI runner is not configured")
        val result =
            withContext(Dispatchers.IO) {
                service.saveSelfReported(
                    call.parameters["runId"].orEmpty(),
                    call.parameters["analysisId"].orEmpty(),
                    outputBytes,
                    provenance,
                )
            }
        when (result) {
            is AdviceRunResult.Saved ->
                call.respondJson(
                    buildJsonObject {
                        put("advice", result.advice.document)
                        put("reused", result.reused)
                    },
                    if (result.reused) HttpStatusCode.OK else HttpStatusCode.Created,
                )
            is AdviceRunResult.Failed ->
                throw ApiFailure(
                    HttpStatusCode.UnprocessableEntity,
                    result.reason.name,
                    selfReportedFailureMessage(result.reason),
                )
            is AdviceRunResult.Unavailable ->
                throw ApiFailure(HttpStatusCode.ServiceUnavailable, "AI_UNAVAILABLE", "AI runner is not configured")
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
private const val SELF_REPORTED_BODY_MAX = MAX_ADVICE_OUTPUT_BYTES + 16_384
private val SELF_REPORTED_REQUEST_PROVENANCE_KEYS =
    setOf("provenance_kind", "runner_id", "runner_version", "model_id", "duration_ms")

private fun parseSelfReportedAdviceRequest(bytes: ByteArray): Pair<ByteArray, SelfReportedProvenance> {
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            malformed("Self-reported advice body must be UTF-8 JSON")
        }
    val invalid = "Self-reported advice body must be a JSON object with output and provenance"
    StrictJsonScanner(text, 16, 64, 16, "self-reported advice") { _, _, _ -> malformed(invalid) }.scan(16_384)
    val root =
        try {
            Json.parseToJsonElement(text) as? JsonObject ?: malformed(invalid)
        } catch (_: SerializationException) {
            malformed(invalid)
        }
    if (root.keys != setOf("output", "provenance")) malformed(invalid)
    val output =
        root["output"] as? JsonObject
            ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "output must be a JSON object")
    val provenance =
        root["provenance"] as? JsonObject
            ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "provenance must be a JSON object")
    if (provenance.keys.any { it !in SELF_REPORTED_REQUEST_PROVENANCE_KEYS }) {
        throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "provenance contains unsupported fields")
    }
    provenance["provenance_kind"]?.let { kind ->
        val primitive = kind as? JsonPrimitive
        if (primitive == null || !primitive.isString || primitive.content != SELF_REPORTED_PROVENANCE_KIND) {
            throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "provenance_kind must be self-reported")
        }
    }
    val runnerId =
        (provenance["runner_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(::validSelfReportedRunnerId)
            ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "runner_id is invalid")
    val modelId =
        (provenance["model_id"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf(::validModelSlug)
            ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "model_id is invalid")
    val runnerVersion =
        provenance["runner_version"]?.let { version ->
            (version as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { RUNNER_VERSION_PATTERN.matches(it) }
                ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "runner_version is invalid")
        }
    val durationMillis =
        provenance["duration_ms"]?.let { duration ->
            val primitive = duration as? JsonPrimitive
            val parsed = if (primitive == null || primitive.isString) null else primitive.longOrNull
            if (parsed == null || parsed !in 0..613_000) {
                throw ApiFailure(HttpStatusCode.UnprocessableEntity, "INVALID_OUTPUT", "duration_ms is invalid")
            }
            parsed
        }
    return canonicalJson(output) to SelfReportedProvenance(runnerId, runnerVersion, modelId, durationMillis)
}

private fun selfReportedFailureMessage(reason: AdviceFailure): String =
    when (reason) {
        AdviceFailure.UNKNOWN_EVIDENCE_REFERENCE -> "Advice references unknown analysis evidence"
        AdviceFailure.OUTPUT_LIMIT -> "Advice output exceeds its size limit"
        AdviceFailure.INPUT_LIMIT -> "Analysis evidence exceeds the advisory input limit"
        AdviceFailure.INVALID_ANALYSIS -> "Analysis result does not match the advisory contract"
        else -> "Self-reported advice does not match the advisory output contract"
    }

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
        put("detail", detail?.let(::JsonPrimitive) ?: JsonNull)
        put("model_id", modelId?.let(::JsonPrimitive) ?: JsonNull)
    }

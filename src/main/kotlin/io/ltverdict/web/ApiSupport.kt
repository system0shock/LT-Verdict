package io.ltverdict.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.isMultipart
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.PolicyValidationError
import io.ltverdict.core.RuleFailure
import io.ltverdict.core.RuleFailureKind
import io.ltverdict.core.StoredAnalysis
import io.ltverdict.core.canonicalJson
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal suspend fun receiveBaselineRequest(
    call: ApplicationCall,
    subject: String = "Baseline",
): JsonObject {
    if ((call.request.contentLength() ?: 0) > MAX_BASELINE_REQUEST_BYTES) tooLarge("$subject request exceeds 16 KiB")
    val bytes = withContext(Dispatchers.IO) { call.receiveChannel().toInputStream().use { it.readNBytes(MAX_BASELINE_REQUEST_BYTES + 1) } }
    if (bytes.size > MAX_BASELINE_REQUEST_BYTES) tooLarge("$subject request exceeds 16 KiB")
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: java.nio.charset.CharacterCodingException) {
            malformed("$subject request must be UTF-8 JSON")
        }
    var depth = 0
    var quoted = false
    var escaped = false
    text.forEach { character ->
        if (quoted) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> quoted = false
            }
        } else {
            when (character) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 8) tooLarge("$subject request JSON depth exceeds 8")
                '}', ']' -> depth -= 1
            }
        }
    }
    return try {
        Json.parseToJsonElement(text) as? JsonObject ?: malformed("$subject request must be an object")
    } catch (_: kotlinx.serialization.SerializationException) {
        malformed("$subject JSON is malformed")
    }
}

internal suspend fun ApplicationCall.respondPolicyValidation(validation: PolicyValidation) {
    when (validation) {
        is PolicyValidation.Valid ->
            respondJson(
                buildJsonObject {
                    put("valid", true)
                    put("policy", Json.parseToJsonElement(validation.canonicalBytes.decodeToString()))
                    put("sha256", validation.sha256)
                },
            )

        is PolicyValidation.Invalid ->
            respondJson(
                buildJsonObject {
                    put("valid", false)
                    put("errors", buildJsonArray { validation.errors.forEach { add(it.toJson()) } })
                },
                HttpStatusCode.UnprocessableEntity,
            )
    }
}

internal fun PolicyValidationError.toJson(): JsonObject =
    buildJsonObject {
        put("code", code)
        put("json_pointer", jsonPointer)
        put("message", message)
    }

internal suspend fun RunBundleStore.requireAnalysis(call: ApplicationCall): StoredAnalysis {
    val runId = call.parameters["runId"] ?: notFound("Run was not found")
    val analysisId = call.parameters["analysisId"] ?: notFound("Analysis was not found")
    return withContext(Dispatchers.IO) {
        try {
            readAnalysis(runId, analysisId) ?: notFound("Analysis was not found")
        } catch (_: NoSuchElementException) {
            notFound("Analysis was not found")
        } catch (_: IllegalArgumentException) {
            notFound("Analysis was not found")
        }
    }
}

internal fun ApplicationCall.requireOnlyQueries(vararg allowed: String) {
    val parameters = request.queryParameters
    if (parameters.names().any { it !in allowed } || parameters.names().any { parameters.getAll(it)?.size != 1 }) {
        malformed("Query parameters are invalid")
    }
}

internal fun ApplicationCall.singleQuery(name: String): String? = request.queryParameters.getAll(name)?.singleOrNull()

internal fun ApplicationCall.intQuery(
    name: String,
    default: Int,
    range: IntRange,
): Int {
    val raw = singleQuery(name) ?: return default
    return raw.toIntOrNull()?.takeIf { it in range } ?: malformed("$name is invalid")
}

internal fun ApplicationCall.longQuery(
    name: String,
    default: Long,
): Long {
    val raw = singleQuery(name) ?: return default
    return raw.toLongOrNull() ?: malformed("$name is invalid")
}

internal fun ApplicationCall.optionalLongQuery(name: String): Long? {
    val raw = singleQuery(name) ?: return null
    return raw.toLongOrNull() ?: malformed("$name is invalid")
}

internal fun ApplicationCall.requireMultipart() {
    if (!request.isMultipart()) unsupportedMedia("multipart/form-data is required")
}

internal fun ApplicationCall.requireJson() {
    if (!request.contentType().match(ContentType.Application.Json)) unsupportedMedia("application/json is required")
}

internal suspend fun ApplicationCall.respondJson(
    body: JsonElement,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondBytes(canonicalJson(body), ContentType.Application.Json, status)

internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
    details: List<PolicyValidationError> = emptyList(),
    limit: Int? = null,
) = respondJson(
    buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
                put("details", JsonArray(details.map { it.toJson() }))
                limit?.let { put("limit", it) }
            },
        )
    },
    status,
)

internal fun malformed(message: String): Nothing = throw ApiFailure(HttpStatusCode.BadRequest, "MALFORMED_REQUEST", message)

internal fun notFound(message: String): Nothing = throw ApiFailure(HttpStatusCode.NotFound, "NOT_FOUND", message)

internal fun conflict(
    code: String,
    message: String,
): Nothing = throw ApiFailure(HttpStatusCode.Conflict, code, message)

internal fun tooLarge(message: String): Nothing = throw ApiFailure(HttpStatusCode.PayloadTooLarge, "RESOURCE_LIMIT_EXCEEDED", message)

internal fun unsupportedMedia(message: String): Nothing =
    throw ApiFailure(HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_MEDIA_TYPE", message)

internal fun unsupportedInput(message: String): Nothing = throw ApiFailure(HttpStatusCode.UnprocessableEntity, "UNSUPPORTED_INPUT", message)

// The core rules raise RuleFailure without any HTTP; the statuses are the three that these rules always answered with.
internal fun RuleFailure.status(): HttpStatusCode =
    when (kind) {
        RuleFailureKind.MALFORMED -> HttpStatusCode.BadRequest
        RuleFailureKind.UNPROCESSABLE -> HttpStatusCode.UnprocessableEntity
        RuleFailureKind.CORRUPT -> HttpStatusCode.InternalServerError
    }

internal class ApiFailure(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val limit: Int? = null,
) : RuntimeException(message)

internal class InvalidPolicy(
    val validation: PolicyValidation.Invalid,
) : RuntimeException()

internal class InvalidResources(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

internal class InvalidDiagnostics(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

internal class InvalidCapacity(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

internal class InvalidTrend(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

internal class InvalidPodView(
    val errors: List<PolicyValidationError>,
) : RuntimeException()

internal const val MAX_UPLOAD_BYTES = 4_294_967_296L

// 16 MiB: source_context, PostgreSQL parts and capture. The resource snapshot has its own limit in the core.
internal const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024

internal const val MAX_BASELINE_REQUEST_BYTES = 16_384

internal val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

internal fun ApplicationCall.requireQueries(
    single: Set<String>,
    repeatable: Set<String>,
) {
    val parameters = request.queryParameters
    if (parameters.names().any { it !in single && it !in repeatable }) malformed("Query parameters are invalid")
    if (parameters.names().any { it in single && parameters.getAll(it)?.size != 1 }) malformed("Query parameters are invalid")
}

internal fun ApplicationCall.optionalIntQuery(name: String): Int? {
    val raw = singleQuery(name) ?: return null
    return raw.toIntOrNull() ?: malformed("$name is invalid")
}

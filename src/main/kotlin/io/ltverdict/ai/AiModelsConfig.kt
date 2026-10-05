package io.ltverdict.ai

import io.ltverdict.core.StrictJsonScanner
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.PrintStream
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class AiModel(
    val id: String,
    val label: String,
)

/** Contents of an `ai-models.v1` file (ADR 0023, D3) or the built-in default. */
internal data class AiModelsConfig(
    val endpointUrl: String,
    val endpointLabel: String?,
    val allowInsecureHttp: Boolean,
    val defaultModel: String,
    val models: List<AiModel>,
) {
    /**
     * The `advisory_ai` value of `GET /api/bootstrap`: the model list only, never the endpoint address or its label
     * (owner decision 2026-10-06: the UI shows no destination line). `endpoint.label` stays a valid, unused file field.
     */
    fun bootstrapJson(): JsonObject =
        buildJsonObject {
            put("default_model_id", defaultModel)
            put(
                "models",
                buildJsonArray {
                    models.forEach { model ->
                        add(
                            buildJsonObject {
                                put("id", model.id)
                                put("label", model.label)
                                put("measured", aiModelMeasured(model.id, endpointUrl))
                            },
                        )
                    }
                },
            )
        }

    companion object {
        val BUILT_IN =
            AiModelsConfig(
                endpointUrl = QwenCode0211.PROVIDER_ENDPOINT,
                endpointLabel = null,
                allowInsecureHttp = false,
                defaultModel = QwenCode0211.MODEL_ID,
                models = listOf(AiModel(QwenCode0211.MODEL_ID, "DeepSeek V4 Flash")),
            )
    }
}

internal sealed interface AiModelsConfigLoad {
    data class Loaded(
        val config: AiModelsConfig,
    ) : AiModelsConfigLoad

    /** [pointer] is a JSON pointer over the closed key set of the format; it never carries file supplied names. */
    data class Invalid(
        val code: String,
        val pointer: String,
    ) : AiModelsConfigLoad
}

internal class AdvisoryAiSetup(
    val runner: AdvisoryRunner,
    val models: AiModelsConfig?,
)

/**
 * `measured` is a property of the pair (slug, endpoint): the slug took part in the ADR 0021 experiment and the
 * endpoint is the built-in one it ran on. The file cannot set it.
 */
internal fun aiModelMeasured(
    slug: String,
    endpointUrl: String,
): Boolean = slug in EXPERIMENT_MODEL_IDS && endpointUrl == QwenCode0211.PROVIDER_ENDPOINT

/** Loads the configuration; an invalid file makes advisory AI unavailable but never aborts the process. */
internal fun advisoryAiSetup(
    environment: Map<String, String> = System.getenv(),
    repositoryRoot: Path? = null,
    stderr: PrintStream = System.err,
): AdvisoryAiSetup =
    when (val load = loadAiModelsConfig(environment)) {
        is AiModelsConfigLoad.Loaded ->
            AdvisoryAiSetup(ModelStudioAdvisoryRunner.fromEnvironment(environment, repositoryRoot, load.config), load.config)

        is AiModelsConfigLoad.Invalid -> {
            stderr.println("MODEL_CONFIG_INVALID $MODELS_FILE_ENVIRONMENT ${load.code} ${load.pointer.ifEmpty { "/" }}")
            AdvisoryAiSetup(UnavailableAdvisoryRunner(AdviceUnavailableReason.MODEL_CONFIG_INVALID), null)
        }
    }

internal fun loadAiModelsConfig(environment: Map<String, String>): AiModelsConfigLoad {
    val configured = environment[MODELS_FILE_ENVIRONMENT] ?: return AiModelsConfigLoad.Loaded(AiModelsConfig.BUILT_IN)
    val path = configured.trim()
    if (path.isEmpty()) return invalid("FILE_PATH_EMPTY", "")
    val file =
        try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            return invalid("FILE_NOT_READABLE", "")
        }
    if (!file.isAbsolute) return invalid("FILE_PATH_NOT_ABSOLUTE", "")
    val bytes =
        try {
            if (!Files.isRegularFile(file)) return invalid("FILE_NOT_READABLE", "")
            Files.newInputStream(file).use { it.readNBytes(MAX_MODELS_FILE_BYTES + 1) }
        } catch (_: IOException) {
            return invalid("FILE_NOT_READABLE", "")
        } catch (_: SecurityException) {
            return invalid("FILE_NOT_READABLE", "")
        }
    val parsed = parseAiModelsConfig(bytes)
    return parsed
}

internal fun parseAiModelsConfig(bytes: ByteArray): AiModelsConfigLoad {
    if (bytes.size > MAX_MODELS_FILE_BYTES) return invalid("RESOURCE_LIMIT_EXCEEDED", "")
    return try {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw ConfigFailure("INVALID_UTF8", "")
            }
        StrictJsonScanner(text, SCAN_DEPTH_MAX, SCAN_NUMBER_BYTES_MAX, SCAN_EXPONENT_MAX, "AI models configuration") { code, pointer, _ ->
            // The scanner reports the pointer of the offending member, which includes a file supplied name.
            throw ConfigFailure(code, if (code == "DUPLICATE_OBJECT_KEY") pointer.substringBeforeLast('/') else pointer)
        }.scan()
        val root =
            try {
                Json.parseToJsonElement(text)
            } catch (_: SerializationException) {
                throw ConfigFailure("MALFORMED_JSON", "")
            }
        AiModelsConfigLoad.Loaded(parseRoot(root))
    } catch (failure: ConfigFailure) {
        AiModelsConfigLoad.Invalid(failure.code, safePointer(failure.pointer))
    }
}

private fun parseRoot(root: JsonElement): AiModelsConfig {
    val document = root as? JsonObject ?: throw ConfigFailure("INVALID_TYPE", "")
    document.rejectUnknown(setOf("schema_version", "endpoint", "default_model", "models"), "")
    if (document.text("schema_version", "") != "ai-models.v1") throw ConfigFailure("INVALID_VALUE", "/schema_version")
    val defaultModel = document.text("default_model", "")
    val models = parseModels(document.field("models", ""))
    if (models.none { it.id == defaultModel }) throw ConfigFailure("INVALID_VALUE", "/default_model")
    val endpoint = document["endpoint"]
    var url = AiModelsConfig.BUILT_IN.endpointUrl
    var label = AiModelsConfig.BUILT_IN.endpointLabel
    var allowInsecureHttp = false
    if (endpoint != null) {
        val member = endpoint as? JsonObject ?: throw ConfigFailure("INVALID_TYPE", "/endpoint")
        member.rejectUnknown(setOf("url", "label", "allow_insecure_http"), "/endpoint")
        val flag = member["allow_insecure_http"]
        if (flag != null) {
            val primitive = flag as? JsonPrimitive
            if (primitive == null || primitive.isString || primitive.content !in setOf("true", "false")) {
                throw ConfigFailure("INVALID_TYPE", "/endpoint/allow_insecure_http")
            }
            allowInsecureHttp = primitive.content == "true"
        }
        url = member.text("url", "/endpoint")
        if (!validEndpointUrl(url, allowInsecureHttp)) throw ConfigFailure("INVALID_URL", "/endpoint/url")
        if (member["label"] != null) {
            val text = member.text("label", "/endpoint")
            if (!validLabel(text)) throw ConfigFailure("INVALID_VALUE", "/endpoint/label")
            label = text
        } else {
            label = null
        }
    }
    return AiModelsConfig(url, label, allowInsecureHttp, defaultModel, models)
}

private fun parseModels(element: JsonElement): List<AiModel> {
    val array = element as? JsonArray ?: throw ConfigFailure("INVALID_TYPE", "/models")
    if (array.size !in 1..MAX_MODELS) throw ConfigFailure("INVALID_VALUE", "/models")
    val seen = HashSet<String>()
    return array.mapIndexed { index, entry ->
        val pointer = "/models/$index"
        val model = entry as? JsonObject ?: throw ConfigFailure("INVALID_TYPE", pointer)
        model.rejectUnknown(setOf("id", "label"), pointer)
        val id = model.text("id", pointer)
        if (!validModelSlug(id)) throw ConfigFailure("INVALID_VALUE", "$pointer/id")
        if (!seen.add(id)) throw ConfigFailure("DUPLICATE_MODEL_ID", "$pointer/id")
        val label = model.text("label", pointer)
        if (!validLabel(label)) throw ConfigFailure("INVALID_VALUE", "$pointer/label")
        AiModel(id, label)
    }
}

internal fun validModelSlug(value: String): Boolean = MODEL_SLUG.matches(value) && ".." !in value && "//" !in value

/**
 * The `endpoint_host` the relay reports for [url] (lower case host, port always present): what the runtime result
 * must equal for the evidence to be recorded as sent to the configured endpoint. Null for an address that is not usable.
 */
internal fun endpointHostOf(url: String): String? =
    try {
        val uri = URI(url)
        val host = uri.host?.lowercase()
        val port =
            if (uri.port != -1) {
                uri.port
            } else if (uri.scheme == "https") {
                443
            } else {
                80
            }
        if (host == null) null else "$host:$port"
    } catch (_: URISyntaxException) {
        null
    }

/**
 * `endpoint_host` of provenance (ADR 0023, D4): the host and port the relay sent the evidence to, lower case, the
 * port always present, no scheme, path or credentials. The same pattern is in `ai-advice.schema.json` and in
 * `advisory_ai_runtime.ps1`.
 */
internal fun validEndpointHost(value: String): Boolean = value.length <= MAX_ENDPOINT_HOST_LENGTH && ENDPOINT_HOST.matches(value)

private fun validLabel(value: String): Boolean {
    if (value.isBlank() || value.codePointCount(0, value.length) !in 1..MAX_LABEL_CODE_POINTS) return false
    return value.codePoints().noneMatch { codePoint ->
        when (Character.getType(codePoint).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            -> true

            else -> false
        }
    }
}

private fun validEndpointUrl(
    url: String,
    allowInsecureHttp: Boolean,
): Boolean {
    if (url.length > MAX_URL_BYTES || url.any { it !in '!'..'~' || it in URL_UNSAFE_CHARACTERS }) return false
    if ('?' in url || '#' in url) return false
    // The launcher and the relay accept only this form; a file must not pass the loader and then fail at run time.
    if (!UPSTREAM_FORM.matches(url)) return false
    val uri =
        try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
    val authority = uri.rawAuthority
    return (uri.scheme == "https" || (uri.scheme == "http" && allowInsecureHttp)) &&
        authority != null &&
        '@' !in authority &&
        !uri.host.isNullOrEmpty() &&
        (uri.port == -1 || uri.port in 1..65_535) &&
        endpointHostOf(url)?.let(::validEndpointHost) == true
}

private class ConfigFailure(
    val code: String,
    val pointer: String,
) : RuntimeException()

private fun invalid(
    code: String,
    pointer: String,
) = AiModelsConfigLoad.Invalid(code, pointer)

/**
 * Keeps only tokens of the closed key set and, right after `models`, a short array index; any other token is a
 * file supplied name and is masked.
 */
private fun safePointer(pointer: String): String {
    var previous = ""
    return pointer.split('/').drop(1).joinToString("") { token ->
        val safe =
            when {
                token in POINTER_TOKENS -> token
                previous == "models" && token.length <= 2 && token.all(Char::isDigit) -> token
                else -> "*"
            }
        previous = safe
        "/$safe"
    }
}

private fun JsonObject.field(
    name: String,
    pointer: String,
): JsonElement = this[name] ?: throw ConfigFailure("MISSING_FIELD", "$pointer/$name")

private fun JsonObject.text(
    name: String,
    pointer: String,
): String {
    val value = field(name, pointer)
    if (value !is JsonPrimitive || !value.isString) throw ConfigFailure("INVALID_TYPE", "$pointer/$name")
    return value.content
}

private fun JsonObject.rejectUnknown(
    allowed: Set<String>,
    pointer: String,
) {
    if (keys.any { it !in allowed }) throw ConfigFailure("UNKNOWN_FIELD", pointer)
}

internal const val MODELS_FILE_ENVIRONMENT = "LT_VERDICT_AI_MODELS_FILE"
private const val MAX_MODELS_FILE_BYTES = 65_536
private const val MAX_MODELS = 32
private const val MAX_LABEL_CODE_POINTS = 80
private const val MAX_URL_BYTES = 512

/** The address travels as a process argument and a container variable; quoting and shell metacharacters are refused. */
private val UPSTREAM_FORM = Regex("https?://(?:\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9.-]+)(?::[0-9]{1,5})?(?:/[^?#]*)?")

internal const val URL_UNSAFE_CHARACTERS = "\"\\`^|<>{}"
private const val SCAN_DEPTH_MAX = 8
private const val SCAN_NUMBER_BYTES_MAX = 64
private const val SCAN_EXPONENT_MAX = 64
internal val MODEL_SLUG = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
private const val MAX_ENDPOINT_HOST_LENGTH = 260
internal val ENDPOINT_HOST =
    Regex(
        "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?){0,126}|" +
            "\\[(?=[0-9a-f:.]*:[0-9a-f:.]*:)(?=[0-9a-f:.]*[0-9a-f])[0-9a-f:.]{2,45}\\])" +
            ":(?:[1-9][0-9]{0,3}|[1-5][0-9]{4}|6[0-4][0-9]{3}|65[0-4][0-9]{2}|655[0-2][0-9]|6553[0-5])",
    )
private val EXPERIMENT_MODEL_IDS = setOf(QwenCode0211.MODEL_ID)
private val POINTER_TOKENS =
    setOf("schema_version", "endpoint", "default_model", "models", "url", "label", "allow_insecure_http", "id")

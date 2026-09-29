package io.ltverdict.integrations.jenkins

import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Duration

internal data class JenkinsAuth(
    val usernameEnvironment: String,
    val apiTokenEnvironment: String,
)

internal data class JenkinsProfile(
    val id: String,
    val controller: URI,
    val jobPath: String,
    val auth: JenkinsAuth,
    val allowInsecureHttp: Boolean = false,
    val parameterNames: Set<String>,
    val sensitiveParameterNames: Set<String>,
    val artifactPaths: Set<String>,
    val correlationParameter: String = "LT_VERDICT_TRIGGER_ATTEMPT_ID",
    val timeout: Duration = Duration.ofSeconds(30),
    val pollInterval: Duration = Duration.ofSeconds(1),
    val reconciliationPolls: Int = 3,
    val maxArtifactBytes: Long = 3L * 1024 * 1024 * 1024,
)

internal data class JenkinsProfileSummary(
    val id: String,
    val controller: String,
    val jobPath: String,
    val parameterNames: Set<String>,
    val artifactPaths: Set<String>,
)

internal data class JenkinsConnections(
    val profiles: List<JenkinsProfile>,
) {
    fun profile(id: String): JenkinsProfile =
        profiles.singleOrNull { it.id == id } ?: throw IllegalArgumentException("JENKINS_PROFILE_NOT_FOUND")

    fun summaries(): List<JenkinsProfileSummary> =
        profiles.map {
            JenkinsProfileSummary(it.id, it.controller.toString(), it.jobPath, it.parameterNames, it.artifactPaths)
        }

    fun workflow(
        id: String,
        dataDirectory: DataDirectory,
        environment: (String) -> String? = System::getenv,
    ): JenkinsWorkflow {
        dataDirectory.requireOpen()
        return JenkinsWorkflow(
            profile(id),
            dataDirectory.root
                .resolve("transport")
                .resolve("jenkins")
                .resolve(id),
            environment,
        )
    }
}

internal fun readJenkinsConnections(source: InputStream): JenkinsConnections =
    try {
        val bytes = source.readNBytes(MAX_CONFIG_BYTES + 1)
        if (bytes.size > MAX_CONFIG_BYTES) configInvalid()
        val text = decodeUtf8(bytes)
        StrictJsonScanner(text, 8, 32, 16, "jenkins config") { _, _, _ -> configInvalid() }.scan()
        parseConnections(Json.parseToJsonElement(text).jsonObject)
    } catch (failure: IllegalArgumentException) {
        if (failure.message == "JENKINS_CONFIG_INVALID") throw failure
        configInvalid()
    } catch (_: SerializationException) {
        configInvalid()
    }

private fun parseConnections(value: JsonObject): JenkinsConnections {
    value.rejectUnknown(setOf("schema_version", "profiles"))
    if (value.string("schema_version") != "jenkins-connections.v1") configInvalid()
    val profiles = value.array("profiles")
    if (profiles.size !in 1..MAX_PROFILES) configInvalid()
    val parsed = profiles.map(::parseProfile)
    if (parsed.map(JenkinsProfile::id).toSet().size != parsed.size) configInvalid()
    return JenkinsConnections(parsed)
}

private fun parseProfile(element: JsonElement): JenkinsProfile {
    val value = element as? JsonObject ?: configInvalid()
    value.rejectUnknown(
        setOf(
            "id",
            "controller",
            "job_path",
            "auth",
            "allow_insecure_http",
            "parameter_names",
            "sensitive_parameter_names",
            "artifact_paths",
            "correlation_parameter",
            "timeout_ms",
            "poll_interval_ms",
            "reconciliation_polls",
            "max_artifact_bytes",
        ),
    )
    val parameters = value.stringSet("parameter_names", MAX_NAMES, PARAMETER_NAME)
    val sensitive = value.stringSet("sensitive_parameter_names", MAX_NAMES, PARAMETER_NAME)
    if (!parameters.containsAll(sensitive)) configInvalid()
    val correlation = value.optionalString("correlation_parameter") ?: "LT_VERDICT_TRIGGER_ATTEMPT_ID"
    if (!PARAMETER_NAME.matches(correlation) || correlation in parameters) configInvalid()
    return JenkinsProfile(
        id = value.validString("id", IDENTIFIER),
        controller = controller(value.string("controller")),
        jobPath = jobPath(value.string("job_path")),
        auth = parseAuth(value.objectValue("auth")),
        allowInsecureHttp = value.optionalBoolean("allow_insecure_http") ?: false,
        parameterNames = parameters,
        sensitiveParameterNames = sensitive,
        artifactPaths = value.stringSet("artifact_paths", MAX_NAMES, SAFE_RELATIVE_PATH).also { if (it.isEmpty()) configInvalid() },
        correlationParameter = correlation,
        timeout = Duration.ofMillis(value.optionalLong("timeout_ms") ?: 30_000L),
        pollInterval = Duration.ofMillis(value.optionalLong("poll_interval_ms") ?: 1_000L),
        reconciliationPolls = (value.optionalLong("reconciliation_polls") ?: 3L).exactInt(),
        maxArtifactBytes = value.optionalLong("max_artifact_bytes") ?: 3L * 1024 * 1024 * 1024,
    ).also(::validateProfile)
}

private fun parseAuth(value: JsonObject): JenkinsAuth {
    value.rejectUnknown(setOf("username_env", "api_token_env"))
    return JenkinsAuth(
        value.validString("username_env", ENVIRONMENT_NAME),
        value.validString("api_token_env", ENVIRONMENT_NAME),
    ).also { if (it.usernameEnvironment == it.apiTokenEnvironment) configInvalid() }
}

internal fun validateProfile(profile: JenkinsProfile) {
    if (!IDENTIFIER.matches(profile.id)) configInvalid()
    if (controller(profile.controller.toString()) != profile.controller) configInvalid()
    if (profile.controller.scheme.equals("http", true) && !profile.allowInsecureHttp) configInvalid()
    if (jobPath(profile.jobPath) != profile.jobPath) configInvalid()
    if (!ENVIRONMENT_NAME.matches(profile.auth.usernameEnvironment) || !ENVIRONMENT_NAME.matches(profile.auth.apiTokenEnvironment)) {
        configInvalid()
    }
    if (profile.auth.usernameEnvironment == profile.auth.apiTokenEnvironment) configInvalid()
    if (profile.parameterNames.size > MAX_NAMES || profile.parameterNames.any { !PARAMETER_NAME.matches(it) }) configInvalid()
    if (!profile.parameterNames.containsAll(profile.sensitiveParameterNames)) configInvalid()
    if (!PARAMETER_NAME.matches(profile.correlationParameter) || profile.correlationParameter in profile.parameterNames) configInvalid()
    if (profile.artifactPaths.isEmpty() ||
        profile.artifactPaths.size > MAX_NAMES ||
        profile.artifactPaths.any { !SAFE_RELATIVE_PATH.matches(it) }
    ) {
        configInvalid()
    }
    if (profile.timeout.toMillis() !in 1..MAX_TIMEOUT_MILLIS || profile.pollInterval.toMillis() !in 0..MAX_POLL_MILLIS) {
        configInvalid()
    }
    if (profile.reconciliationPolls !in 1..MAX_RECONCILIATION_POLLS || profile.maxArtifactBytes !in 1..MAX_ARTIFACT_BYTES) {
        configInvalid()
    }
}

private fun controller(source: String): URI {
    val parsed = tryOrInvalid { URI(source) }
    val scheme = parsed.scheme?.lowercase() ?: configInvalid()
    if (scheme !in setOf("http", "https") || parsed.isOpaque || parsed.host == null || parsed.rawUserInfo != null) configInvalid()
    if (parsed.rawQuery != null || parsed.rawFragment != null || parsed.port == 0 || parsed.port > 65_535) configInvalid()
    if ('%' in parsed.rawPath.orEmpty() ||
        parsed.path
            .orEmpty()
            .split('/')
            .any { it == "." || it == ".." }
    ) {
        configInvalid()
    }
    val path = parsed.path.orEmpty().let { if (it.endsWith('/')) it else "$it/" }
    return tryOrInvalid { URI(scheme, null, parsed.host.lowercase(), parsed.port, path, null, null) }
}

private fun jobPath(value: String): String {
    val segments = value.split('/')
    if (segments.size < 2 || segments.size % 2 != 0 || segments.filterIndexed { index, _ -> index % 2 == 0 }.any { it != "job" }) {
        configInvalid()
    }
    if (segments.filterIndexed { index, _ -> index % 2 == 1 }.any { !SAFE_SEGMENT.matches(it) }) configInvalid()
    return value
}

private fun decodeUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        configInvalid()
    }

private fun JsonObject.rejectUnknown(allowed: Set<String>) {
    if (keys.any { it !in allowed }) configInvalid()
}

private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject ?: configInvalid()

private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: configInvalid()

private fun JsonObject.string(name: String): String {
    val value = this[name] as? JsonPrimitive ?: configInvalid()
    if (!value.isString) configInvalid()
    return value.content
}

private fun JsonObject.optionalString(name: String): String? = if (name in this) string(name) else null

private fun JsonObject.validString(
    name: String,
    pattern: Regex,
): String = string(name).also { if (!pattern.matches(it)) configInvalid() }

private fun JsonObject.optionalLong(name: String): Long? {
    val value = this[name] ?: return null
    if (value !is JsonPrimitive || value.isString) configInvalid()
    return value.longOrNull ?: configInvalid()
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value !is JsonPrimitive || value.isString) configInvalid()
    return value.content.toBooleanStrictOrNull() ?: configInvalid()
}

private fun JsonObject.stringSet(
    name: String,
    maximum: Int,
    pattern: Regex,
): Set<String> {
    val values = array(name)
    if (values.size > maximum) configInvalid()
    val strings = values.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: configInvalid() }
    if (strings.toSet().size != strings.size || strings.any { !pattern.matches(it) }) configInvalid()
    return strings.toSet()
}

private fun Long.exactInt(): Int = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else configInvalid()

private inline fun <T> tryOrInvalid(block: () -> T): T =
    try {
        block()
    } catch (_: Exception) {
        configInvalid()
    }

private fun configInvalid(): Nothing = throw IllegalArgumentException("JENKINS_CONFIG_INVALID")

private const val MAX_CONFIG_BYTES = 1_048_576
private const val MAX_PROFILES = 16
private const val MAX_NAMES = 64
private const val MAX_TIMEOUT_MILLIS = 300_000L
private const val MAX_POLL_MILLIS = 60_000L
private const val MAX_RECONCILIATION_POLLS = 100
private const val MAX_ARTIFACT_BYTES = 4_294_967_296L
private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
private val PARAMETER_NAME = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
private val SAFE_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
private val SAFE_RELATIVE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}(?:/[A-Za-z0-9][A-Za-z0-9._~-]{0,127}){0,15}")

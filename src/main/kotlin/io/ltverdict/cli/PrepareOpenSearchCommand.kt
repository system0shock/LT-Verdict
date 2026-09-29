package io.ltverdict.cli

import io.ltverdict.core.DIAGNOSTIC_JSON_DEPTH_MAX
import io.ltverdict.core.DiagnosticClockAlignment
import io.ltverdict.core.DiagnosticControlMeaning
import io.ltverdict.core.DiagnosticControlV1
import io.ltverdict.core.DiagnosticExpectedSign
import io.ltverdict.core.DiagnosticLoadMetric
import io.ltverdict.core.MAX_DIAGNOSTIC_CONTROLS
import io.ltverdict.core.MAX_DIAGNOSTIC_PAIRS
import io.ltverdict.core.MAX_DIAGNOSTIC_PAIR_WINDOWS
import io.ltverdict.core.MAX_DIAGNOSTIC_PLAN_BYTES
import io.ltverdict.core.OpenSearchCorrelationTemplate
import io.ltverdict.core.RESOURCE_NUMERIC_EXPONENT_ABS_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_TOKEN_BYTES_MAX
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.prepareOpenSearchDiagnostics
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.sources.readOpenSearchContexts
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class OpenSearchPrepareFailure(
    message: String,
) : RuntimeException(message)

internal fun prepareOpenSearchCommand(
    args: List<String>,
    stdout: PrintStream,
): Int {
    if (args.firstOrNull() != "prepare") prepareFail(USAGE)
    val contexts = mutableListOf<Path>()
    var templates: Path? = null
    var resources: Path? = null
    var loadSha256: String? = null
    var outputDirectory: Path? = null
    var index = 1
    while (index < args.size) {
        if (index + 1 >= args.size) prepareFail(USAGE)
        val value = args[index + 1]
        when (args[index]) {
            "--context" -> {
                if (contexts.size == MAX_OPENSEARCH_CONTEXTS) prepareFail("OPENSEARCH_PREPARE_CONTEXT_LIMIT")
                contexts.add(preparePath(value))
            }

            "--templates" -> {
                if (templates != null) prepareFail(USAGE)
                templates = preparePath(value)
            }

            "--resources" -> {
                if (resources != null) prepareFail(USAGE)
                resources = preparePath(value)
            }

            "--load-sha256" -> {
                if (loadSha256 != null) prepareFail(USAGE)
                loadSha256 = value
            }

            "--output-dir" -> {
                if (outputDirectory != null) prepareFail(USAGE)
                outputDirectory = preparePath(value)
            }

            else -> prepareFail(USAGE)
        }
        index += 2
    }
    if (contexts.isEmpty() || templates == null || loadSha256 == null || outputDirectory == null) prepareFail(USAGE)
    if (!SHA256.matches(loadSha256)) prepareFail("OPENSEARCH_PREPARE_LOAD_SHA256_INVALID")

    val contextBytes = contexts.map { readBoundedFile(it, MAX_CONTEXT_BYTES, "OPENSEARCH_PREPARE_CONTEXT_INVALID") }
    if (contextBytes.sumOf { it.size.toLong() } > MAX_CONTEXT_BYTES_TOTAL) prepareFail("OPENSEARCH_PREPARE_CONTEXT_LIMIT")
    val base = resources?.let(::readResources)
    val imported =
        try {
            readOpenSearchContexts(contextBytes, loadSha256, base)
        } catch (failure: IllegalArgumentException) {
            prepareFail(failure.message ?: "OPENSEARCH_PREPARE_CONTEXT_INVALID")
        }
    val declaredTemplates = readTemplates(templates)
    val prepared =
        try {
            prepareOpenSearchDiagnostics(imported.contextEvidence, loadSha256, base, declaredTemplates)
        } catch (failure: IllegalArgumentException) {
            prepareFail(failure.message ?: "OPENSEARCH_PREPARE_INVALID")
        }
    val output = outputDirectory.toAbsolutePath().normalize()
    createNewOutputDirectory(output)
    val resourceOutput = output.resolve(RESOURCE_OUTPUT)
    val diagnosticOutput = output.resolve(DIAGNOSTIC_OUTPUT)
    try {
        Files.write(resourceOutput, prepared.resources.rawBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.write(diagnosticOutput, prepared.diagnostics.rawBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    } catch (failure: IOException) {
        prepareFail("OPENSEARCH_PREPARE_OUTPUT_WRITE_FAILED: ${failure.message ?: "write failed"}")
    }
    stdout.println(
        canonicalJson(
            buildJsonObject {
                put("resource_snapshot", resourceOutput.toString())
                put("correlation_plan", diagnosticOutput.toString())
                put(
                    "series_by_profile",
                    buildJsonObject {
                        prepared.seriesByProfile.toSortedMap().forEach { (profile, series) -> put(profile, series) }
                    },
                )
            },
        ).decodeToString(),
    )
    return 0
}

private fun readResources(path: Path): ResourceValidation.Valid {
    requireRegularFile(path, "OPENSEARCH_PREPARE_RESOURCES_INVALID")
    val validation =
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::validateResourceSnapshot)
        } catch (failure: IOException) {
            prepareFail("OPENSEARCH_PREPARE_RESOURCES_INVALID: ${failure.message ?: "read failed"}")
        }
    return when (validation) {
        is ResourceValidation.Valid -> validation
        is ResourceValidation.Invalid -> prepareFail(validation.errors.firstOrNull()?.code ?: "OPENSEARCH_PREPARE_RESOURCES_INVALID")
    }
}

private fun readTemplates(path: Path): List<OpenSearchCorrelationTemplate> {
    val bytes = readBoundedFile(path, MAX_DIAGNOSTIC_PLAN_BYTES, "OPENSEARCH_PREPARE_TEMPLATES_INVALID")
    val text =
        try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            prepareFail("OPENSEARCH_PREPARE_TEMPLATES_INVALID_UTF8")
        }
    StrictJsonScanner(
        text,
        DIAGNOSTIC_JSON_DEPTH_MAX,
        RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
        RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
        "OpenSearch correlation templates",
    ) { code, pointer, _ ->
        prepareFail("OPENSEARCH_PREPARE_TEMPLATE_$code ${pointer.ifEmpty { "/" }}")
    }.scan()
    val root =
        try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: SerializationException) {
            prepareFail("OPENSEARCH_PREPARE_TEMPLATES_INVALID_JSON")
        } catch (_: IllegalArgumentException) {
            prepareFail("OPENSEARCH_PREPARE_TEMPLATES_INVALID_JSON")
        }
    root.requireKeys(setOf("schema_version", "templates"))
    if (root.string("schema_version") != TEMPLATE_SCHEMA) prepareFail("OPENSEARCH_PREPARE_TEMPLATE_SCHEMA_INVALID")
    val values = root.array("templates")
    if (values.size !in 1..MAX_DIAGNOSTIC_PAIRS) prepareFail("OPENSEARCH_PREPARE_TEMPLATE_COUNT_INVALID")
    return values.mapIndexed { templateIndex, element -> element.template(templateIndex) }
}

private fun JsonElement.template(index: Int): OpenSearchCorrelationTemplate {
    val value = obj("templates/$index")
    value.requireKeys(TEMPLATE_FIELDS)
    val windows = value.array("window_ids")
    if (windows.isEmpty() || windows.size > MAX_DIAGNOSTIC_PAIR_WINDOWS) prepareFail("OPENSEARCH_PREPARE_TEMPLATE_WINDOWS_INVALID")
    val controls = value.array("controls")
    if (controls.size > MAX_DIAGNOSTIC_CONTROLS) prepareFail("OPENSEARCH_PREPARE_TEMPLATE_CONTROLS_INVALID")
    return OpenSearchCorrelationTemplate(
        id = value.string("id"),
        profileId = value.string("profile_id"),
        loadMetric = value.enum("load_metric", DiagnosticLoadMetric.entries, DiagnosticLoadMetric::wireName),
        windowIds = windows.mapIndexed { windowIndex, window -> window.text("templates/$index/window_ids/$windowIndex") },
        expectedSign = value.enum("expected_sign", DiagnosticExpectedSign.entries, DiagnosticExpectedSign::wireName),
        maxLagMillis = value.long("max_lag_ms"),
        minAbsEffect = value.decimal("min_abs_effect"),
        minErrorRateDelta = value.decimal("min_resource_delta"),
        minLoadDelta = value.decimal("min_load_delta"),
        controls = controls.mapIndexed { controlIndex, control -> control.control("templates/$index/controls/$controlIndex") },
        topologyBasis = value.string("topology_basis"),
        clockAlignment = value.enum("clock_alignment", DiagnosticClockAlignment.entries, DiagnosticClockAlignment::wireName),
    )
}

private fun JsonElement.control(pointer: String): DiagnosticControlV1 {
    val value = obj(pointer)
    val meaning = value.enum("meaning", DiagnosticControlMeaning.entries, DiagnosticControlMeaning::wireName)
    return if (meaning == DiagnosticControlMeaning.ACHIEVED_RPS) {
        value.requireKeys(setOf("meaning"))
        DiagnosticControlV1(meaning, null)
    } else {
        value.requireKeys(setOf("meaning", "series_id"))
        DiagnosticControlV1(meaning, value.string("series_id"))
    }
}

private fun createNewOutputDirectory(path: Path) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) prepareFail("OPENSEARCH_PREPARE_OUTPUT_EXISTS")
    val parent = path.parent ?: prepareFail("OPENSEARCH_PREPARE_OUTPUT_PARENT_INVALID")
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) prepareFail("OPENSEARCH_PREPARE_OUTPUT_PARENT_INVALID")
    try {
        Files.createDirectory(path)
    } catch (failure: IOException) {
        prepareFail("OPENSEARCH_PREPARE_OUTPUT_CREATE_FAILED: ${failure.message ?: "create failed"}")
    }
}

private fun readBoundedFile(
    path: Path,
    limit: Int,
    code: String,
): ByteArray {
    requireRegularFile(path, code)
    return try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            input.readNBytes(limit + 1).also { if (it.size > limit) prepareFail("${code}_TOO_LARGE") }
        }
    } catch (failure: IOException) {
        prepareFail("$code: ${failure.message ?: "read failed"}")
    }
}

private fun requireRegularFile(
    path: Path,
    code: String,
) {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) prepareFail(code)
}

private fun preparePath(value: String): Path =
    try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        prepareFail("OPENSEARCH_PREPARE_PATH_INVALID")
    }

private fun JsonObject.requireKeys(expected: Set<String>) {
    if (keys != expected) prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELDS_INVALID")
}

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")

private fun JsonElement.obj(pointer: String): JsonObject =
    this as? JsonObject ?: prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$pointer")

private fun JsonObject.string(name: String): String {
    val value = this[name]
    if (value !is JsonPrimitive || !value.isString || value.content.isEmpty() || value.content.any(Char::isISOControl)) {
        prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")
    }
    return value.content
}

private fun JsonElement.text(pointer: String): String {
    if (this !is JsonPrimitive || !isString || content.isEmpty() || content.any(Char::isISOControl)) {
        prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$pointer")
    }
    return content
}

private fun JsonObject.long(name: String): Long {
    val decimal = decimal(name)
    return try {
        decimal.longValueExact()
    } catch (_: ArithmeticException) {
        prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")
    }
}

private fun JsonObject.decimal(name: String): BigDecimal {
    val value = this[name]
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")
    }
    return value.content.toBigDecimalOrNull() ?: prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")
}

private fun <T> JsonObject.enum(
    name: String,
    values: Iterable<T>,
    wireName: (T) -> String,
): T {
    val selected = string(name)
    return values.firstOrNull { wireName(it) == selected } ?: prepareFail("OPENSEARCH_PREPARE_TEMPLATE_FIELD_INVALID:$name")
}

private fun prepareFail(message: String): Nothing = throw OpenSearchPrepareFailure(message)

private const val TEMPLATE_SCHEMA = "opensearch-correlation-templates.v1"
private const val MAX_OPENSEARCH_CONTEXTS = 16
private const val MAX_CONTEXT_BYTES = 16 * 1024 * 1024
private const val MAX_CONTEXT_BYTES_TOTAL = 32L * 1024 * 1024
private const val RESOURCE_OUTPUT = "resource-snapshot.json"
private const val DIAGNOSTIC_OUTPUT = "correlation-plan.json"
private const val USAGE =
    "Usage: ltv opensearch prepare --context <snapshot.json> [--context <snapshot.json> ...] " +
        "--templates <templates.json> [--resources <snapshot.json>] --load-sha256 <sha256> --output-dir <new-directory>"
private val SHA256 = Regex("[0-9a-f]{64}")
private val TEMPLATE_FIELDS =
    setOf(
        "id",
        "profile_id",
        "load_metric",
        "window_ids",
        "expected_sign",
        "max_lag_ms",
        "min_abs_effect",
        "min_resource_delta",
        "min_load_delta",
        "controls",
        "topology_basis",
        "clock_alignment",
    )

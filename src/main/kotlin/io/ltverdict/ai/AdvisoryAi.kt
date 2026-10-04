package io.ltverdict.ai

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CancellationException

internal const val MAX_ADVISORY_EVIDENCE_BYTES = 262_144
internal const val MAX_ADVICE_OUTPUT_BYTES = 131_072

internal data class AdvisoryEvidence(
    val bytes: ByteArray,
    val sha256: String,
    val references: Set<String>,
)

internal enum class AdviceFailure {
    INPUT_LIMIT,
    OUTPUT_LIMIT,
    INVALID_ANALYSIS,
    INVALID_OUTPUT,
    UNKNOWN_EVIDENCE_REFERENCE,
    TIMEOUT,
    PROCESS_FAILED,
}

internal enum class AdviceUnavailableReason {
    CREDENTIAL_NOT_CONFIGURED,
    DOCKER_UNAVAILABLE,
    RUNTIME_IMAGE_MISSING,
    OS_ISOLATION_NOT_PROVEN,
    RUNNER_ARTIFACT_MISSING,
    RUNNER_ARTIFACT_MISMATCH,
    MODEL_ENDPOINT_UNAVAILABLE,
    MODEL_CONFIG_INVALID,
}

internal class AdviceValidationException(
    val reason: AdviceFailure,
) : IllegalArgumentException(reason.name)

internal object AdvisoryEvidenceBuilder {
    fun build(
        runId: String,
        analysisId: String,
        analysisManifestSha256: String,
        analysisResult: JsonObject,
    ): AdvisoryEvidence {
        if (!SHA256.matches(analysisId) || !SHA256.matches(analysisManifestSha256)) invalidAnalysis()
        if (analysisResult.keys !in ANALYSIS_RESULT_FIELD_SETS ||
            analysisResult.string("schema_version") != "analysis-result.v1" ||
            analysisResult.string("run_id") != runId
        ) {
            invalidAnalysis()
        }
        analysisResult.string("analysis_mode")
        val validity = analysisResult.string("run_validity")
        val verdict = analysisResult.string("policy_verdict")
        val coverage = analysisResult.objectValue("analysis_coverage")
        val findings = analysisResult.array("findings")
        val sourceEvidence = analysisResult.array("evidence")
        val capacity = analysisResult["capacity_summary"]?.let { it as? JsonObject ?: invalidAnalysis() }
        val references = linkedSetOf<String>()

        fun record(
            reference: String,
            value: JsonElement,
        ): JsonObject {
            references += reference
            return buildJsonObject {
                put("ref", reference)
                put("value", sanitize(value))
            }
        }

        val document =
            buildJsonObject {
                put("schema_version", "ai-evidence.v1")
                put(
                    "analysis",
                    buildJsonObject {
                        put("run_id", runId)
                        put("analysis_id", analysisId)
                        put("analysis_manifest_sha256", analysisManifestSha256)
                    },
                )
                put(
                    "facts",
                    buildJsonArray {
                        add(record("analysis-result.json#/run_validity", JsonPrimitive(validity)))
                        add(record("analysis-result.json#/policy_verdict", JsonPrimitive(verdict)))
                        add(record("analysis-result.json#/analysis_coverage", coverage))
                    },
                )
                put(
                    "findings",
                    buildJsonArray {
                        findings.forEachIndexed { index, value ->
                            add(record("analysis-result.json#/findings/$index", value))
                        }
                    },
                )
                put(
                    "evidence",
                    buildJsonArray {
                        sourceEvidence.forEachIndexed { index, value ->
                            add(record("analysis-result.json#/evidence/$index", value))
                        }
                    },
                )
                capacity?.let {
                    put("capacity_summary", record("analysis-result.json#/capacity_summary", it))
                }
            }
        val bytes = canonicalJson(document)
        if (bytes.size > MAX_ADVISORY_EVIDENCE_BYTES) invalid(AdviceFailure.INPUT_LIMIT)
        return AdvisoryEvidence(bytes, sha256Hex(bytes), references)
    }
}

internal object AdviceOutputValidator {
    fun validate(
        bytes: ByteArray,
        allowedReferences: Set<String>,
    ): JsonObject {
        if (bytes.size > MAX_ADVICE_OUTPUT_BYTES) invalid(AdviceFailure.OUTPUT_LIMIT)
        val output =
            try {
                Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: invalid(AdviceFailure.INVALID_OUTPUT)
            } catch (_: SerializationException) {
                invalid(AdviceFailure.INVALID_OUTPUT)
            } catch (_: IllegalArgumentException) {
                invalid(AdviceFailure.INVALID_OUTPUT)
            }
        validateOutput(output, allowedReferences)
        return output
    }

    private fun validateOutput(
        output: JsonObject,
        allowedReferences: Set<String>,
    ) {
        if (output.keys != OUTPUT_FIELDS || output.string("schema_version") != "ai-advice-output.v1") {
            invalid(AdviceFailure.INVALID_OUTPUT)
        }
        output.text("summary", 4096)
        validateRanked(output.array("hypotheses"), 20) { item ->
            if (item.keys != HYPOTHESIS_FIELDS) invalid(AdviceFailure.INVALID_OUTPUT)
            item.text("observation", 4096)
            item.text("possible_explanation", 4096)
            item.text("recommended_check", 4096)
            item.references("evidence_refs", allowedReferences, requireNonEmpty = true)
        }
        validateRanked(output.array("recommendations"), 20) { item ->
            if (item.keys != RECOMMENDATION_FIELDS) invalid(AdviceFailure.INVALID_OUTPUT)
            item.text("action", 4096)
            item.text("rationale", 4096)
            item.references("evidence_refs", allowedReferences, requireNonEmpty = false)
        }
        val caveats = output.array("caveats")
        if (caveats.size > 20) invalid(AdviceFailure.INVALID_OUTPUT)
        val values = caveats.map { it.stringValue(2048) }
        if (values.toSet().size != values.size) invalid(AdviceFailure.INVALID_OUTPUT)
    }

    private fun validateRanked(
        values: JsonArray,
        maximum: Int,
        validate: (JsonObject) -> Unit,
    ) {
        if (values.size > maximum) invalid(AdviceFailure.INVALID_OUTPUT)
        values.forEachIndexed { index, value ->
            val item = value as? JsonObject ?: invalid(AdviceFailure.INVALID_OUTPUT)
            val rank = item["rank"] as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
            if (rank.isString || rank.intOrNull != index + 1) invalid(AdviceFailure.INVALID_OUTPUT)
            validate(item)
        }
    }
}

internal data class RunnerProvenance(
    val runnerId: String,
    val runnerVersion: String,
    val runnerArtifactSha256: String,
    val modelId: String,
    /** Host and port the relay reported as the actual destination of the evidence (ADR 0023, D4). */
    val endpointHost: String,
    val promptVersion: String,
    val promptSha256: String,
    val durationMillis: Long,
    val exitCode: Int,
)

internal fun interface AdvisoryRunner {
    fun invoke(evidence: AdvisoryEvidence): RunnerOutcome
}

internal sealed interface RunnerOutcome {
    data class Success(
        val output: ByteArray,
        val provenance: RunnerProvenance,
    ) : RunnerOutcome

    data class Failed(
        val reason: AdviceFailure,
    ) : RunnerOutcome

    data class Unavailable(
        val reason: AdviceUnavailableReason,
    ) : RunnerOutcome
}

internal sealed interface AdviceRunResult {
    data class Saved(
        val advice: StoredAdvice,
        val reused: Boolean,
    ) : AdviceRunResult

    data class Failed(
        val reason: AdviceFailure,
    ) : AdviceRunResult

    data class Unavailable(
        val reason: AdviceUnavailableReason,
    ) : AdviceRunResult
}

internal class AdvisoryAiService(
    private val runBundles: RunBundleStore,
    private val adviceStore: AiAdviceStore,
    private val runner: AdvisoryRunner,
) {
    fun read(
        runId: String,
        analysisId: String,
    ): StoredAdvice? = adviceStore.read(runId, analysisId)

    fun generate(
        runId: String,
        analysisId: String,
    ): AdviceRunResult {
        adviceStore.read(runId, analysisId)?.let { return AdviceRunResult.Saved(it, reused = true) }
        val analysis = runBundles.readAnalysis(runId, analysisId) ?: throw NoSuchElementException("ANALYSIS_NOT_FOUND")
        val analysisManifestSha256 = sha256Hex(Files.readAllBytes(analysis.path.resolve("manifest.json")))
        val result = runBundles.readAnalysisDocuments(runId, analysisId)?.first ?: throw NoSuchElementException("ANALYSIS_NOT_FOUND")
        val evidence =
            try {
                AdvisoryEvidenceBuilder.build(runId, analysisId, analysisManifestSha256, result)
            } catch (error: AdviceValidationException) {
                return AdviceRunResult.Failed(error.reason)
            }

        val outcome =
            try {
                runner.invoke(evidence)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (_: Exception) {
                RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
            }
        return when (outcome) {
            is RunnerOutcome.Failed -> AdviceRunResult.Failed(outcome.reason)
            is RunnerOutcome.Unavailable -> AdviceRunResult.Unavailable(outcome.reason)
            is RunnerOutcome.Success -> save(runId, analysisId, analysisManifestSha256, evidence, outcome)
        }
    }

    private fun save(
        runId: String,
        analysisId: String,
        analysisManifestSha256: String,
        evidence: AdvisoryEvidence,
        success: RunnerOutcome.Success,
    ): AdviceRunResult {
        val output =
            try {
                AdviceOutputValidator.validate(success.output, evidence.references)
            } catch (error: AdviceValidationException) {
                return AdviceRunResult.Failed(error.reason)
            }
        if (!validProvenance(success.provenance)) return AdviceRunResult.Failed(AdviceFailure.INVALID_OUTPUT)
        val document =
            buildJsonObject {
                put("schema_version", "ai-advice.v1")
                put("advisory", true)
                put("run_id", runId)
                put("analysis_id", analysisId)
                put("analysis_manifest_sha256", analysisManifestSha256)
                put("evidence_input_sha256", evidence.sha256)
                put("output", output)
                put(
                    "provenance",
                    buildJsonObject {
                        put("invocation_id", UUID.randomUUID().toString())
                        put("runner_id", success.provenance.runnerId)
                        put("runner_version", success.provenance.runnerVersion)
                        put("runner_artifact_sha256", success.provenance.runnerArtifactSha256)
                        put("model_id", success.provenance.modelId)
                        put("endpoint_host", success.provenance.endpointHost)
                        put("prompt_version", success.provenance.promptVersion)
                        put("prompt_sha256", success.provenance.promptSha256)
                        put("duration_ms", success.provenance.durationMillis)
                        put("exit_code", success.provenance.exitCode)
                        put("validation", "PASSED")
                    },
                )
            }
        return AdviceRunResult.Saved(
            adviceStore.write(runId, analysisId, analysisManifestSha256, document),
            reused = false,
        )
    }
}

internal fun validateStoredAdvice(
    document: JsonObject,
    runId: String,
    analysisId: String,
    analysisManifestSha256: String,
    evidence: AdvisoryEvidence,
) {
    if (document.keys != ADVICE_FIELDS ||
        document.string("schema_version") != "ai-advice.v1" ||
        document.boolean("advisory") != true ||
        document.string("run_id") != runId ||
        document.string("analysis_id") != analysisId ||
        document.string("analysis_manifest_sha256") != analysisManifestSha256 ||
        document.string("evidence_input_sha256") != evidence.sha256
    ) {
        invalid(AdviceFailure.INVALID_OUTPUT)
    }
    AdviceOutputValidator.validate(canonicalJson(document.objectValue("output")), evidence.references)
    val provenance = document.objectValue("provenance")
    // The model is not compared with the current configuration: the operator may change the file, and advice saved
    // earlier must stay readable (ADR 0023, D4). The slug pattern and the closed key set are checked instead.
    val hasEndpointHost = provenance.keys == PROVENANCE_FIELDS + "endpoint_host"
    if ((provenance.keys != PROVENANCE_FIELDS && !hasEndpointHost) ||
        provenance.string("runner_id") != QwenCode0211.RUNNER_ID ||
        provenance.string("runner_version") != QwenCode0211.RUNNER_VERSION ||
        provenance.string("runner_artifact_sha256") != QwenCode0211.CLI_ENTRY_SHA256 ||
        !validModelSlug(provenance.string("model_id")) ||
        (hasEndpointHost && !validEndpointHost(provenance.string("endpoint_host"))) ||
        // Advice saved before endpoint_host existed could only come from the built-in ModelStudio endpoint and its model.
        (!hasEndpointHost && provenance.string("model_id") != QwenCode0211.MODEL_ID) ||
        provenance.string("prompt_version") != QwenCode0211.PROMPT_VERSION ||
        !SHA256.matches(provenance.string("prompt_sha256")) ||
        provenance.string("validation") != "PASSED" ||
        provenance.long("duration_ms") !in 0..613_000 ||
        provenance.integer("exit_code") != 0
    ) {
        invalid(AdviceFailure.INVALID_OUTPUT)
    }
    try {
        UUID.fromString(provenance.string("invocation_id"))
    } catch (_: IllegalArgumentException) {
        invalid(AdviceFailure.INVALID_OUTPUT)
    }
}

private fun validProvenance(value: RunnerProvenance): Boolean =
    value.runnerId == QwenCode0211.RUNNER_ID &&
        value.runnerVersion == QwenCode0211.RUNNER_VERSION &&
        value.runnerArtifactSha256 == QwenCode0211.CLI_ENTRY_SHA256 &&
        validModelSlug(value.modelId) &&
        validEndpointHost(value.endpointHost) &&
        value.promptVersion == QwenCode0211.PROMPT_VERSION &&
        SHA256.matches(value.promptSha256) &&
        value.durationMillis in 0..613_000 &&
        value.exitCode == 0

private fun sanitize(
    value: JsonElement,
    depth: Int = 0,
): JsonElement {
    if (depth > 32) invalid(AdviceFailure.INPUT_LIMIT)
    return when (value) {
        is JsonObject ->
            buildJsonObject {
                value.forEach { (name, child) ->
                    put(name, if (isSecretKey(name)) JsonPrimitive(REDACTED) else sanitize(child, depth + 1))
                }
            }

        is JsonArray -> buildJsonArray { value.forEach { add(sanitize(it, depth + 1)) } }
        is JsonPrimitive ->
            if (!value.isString) {
                value
            } else {
                val masked = redactInline(value.content)
                when {
                    SECRET_PATTERNS.any { it.containsMatchIn(masked) } -> JsonPrimitive(REDACTED)
                    masked == value.content -> value
                    else -> JsonPrimitive(masked)
                }
            }
    }
}

// Free text (sampler labels, URLs, messages) may embed secrets; mask only the secret part so the label stays usable.
private fun redactInline(text: String): String {
    var result = text
    for (pattern in INLINE_SECRET_PATTERNS) {
        result = pattern.replace(result) { it.groupValues[1] + REDACTED + it.groupValues[2] }
    }
    return result
}

private fun isSecretKey(name: String): Boolean {
    val normalized = name.lowercase().replace('-', '_').replace('.', '_')
    return SECRET_KEYS.any { normalized == it || normalized.endsWith("_$it") }
}

private fun JsonObject.string(name: String): String {
    val value = this[name] as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
    if (!value.isString) invalid(AdviceFailure.INVALID_OUTPUT)
    return value.content
}

private fun JsonObject.text(
    name: String,
    maximumBytes: Int,
): String = string(name).also { if (it.isBlank() || it.encodeToByteArray().size > maximumBytes) invalid(AdviceFailure.INVALID_OUTPUT) }

private fun JsonElement.stringValue(maximumBytes: Int): String {
    val value = this as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
    if (!value.isString || value.content.isBlank() || value.content.encodeToByteArray().size > maximumBytes) {
        invalid(AdviceFailure.INVALID_OUTPUT)
    }
    return value.content
}

private fun JsonObject.objectValue(name: String): JsonObject = this[name] as? JsonObject ?: invalid(AdviceFailure.INVALID_OUTPUT)

private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: invalid(AdviceFailure.INVALID_OUTPUT)

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name] as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
    return if (value.isString) null else value.booleanOrNull
}

private fun JsonObject.long(name: String): Long {
    val value = this[name] as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
    return if (value.isString) invalid(AdviceFailure.INVALID_OUTPUT) else value.longOrNull ?: invalid(AdviceFailure.INVALID_OUTPUT)
}

private fun JsonObject.integer(name: String): Int {
    val value = this[name] as? JsonPrimitive ?: invalid(AdviceFailure.INVALID_OUTPUT)
    return if (value.isString) invalid(AdviceFailure.INVALID_OUTPUT) else value.intOrNull ?: invalid(AdviceFailure.INVALID_OUTPUT)
}

private fun JsonObject.references(
    name: String,
    allowed: Set<String>,
    requireNonEmpty: Boolean,
) {
    val values = array(name)
    if (values.size > 32 || (requireNonEmpty && values.isEmpty())) invalid(AdviceFailure.INVALID_OUTPUT)
    val references = values.map { it.stringValue(256) }
    if (references.toSet().size != references.size) invalid(AdviceFailure.INVALID_OUTPUT)
    if (references.any { it !in allowed }) invalid(AdviceFailure.UNKNOWN_EVIDENCE_REFERENCE)
}

private fun invalidAnalysis(): Nothing = invalid(AdviceFailure.INVALID_ANALYSIS)

private fun invalid(reason: AdviceFailure): Nothing = throw AdviceValidationException(reason)

private const val REDACTED = "[REDACTED]"
private val SHA256 = Regex("[0-9a-f]{64}")
private val ANALYSIS_RESULT_FIELD_SETS =
    setOf(
        setOf("schema_version", "run_id", "analysis_mode", "run_validity", "policy_verdict", "analysis_coverage", "findings", "evidence"),
        setOf(
            "schema_version",
            "run_id",
            "analysis_mode",
            "run_validity",
            "policy_verdict",
            "analysis_coverage",
            "findings",
            "evidence",
            "capacity_summary",
        ),
    )
private val OUTPUT_FIELDS = setOf("schema_version", "summary", "hypotheses", "recommendations", "caveats")
private val HYPOTHESIS_FIELDS = setOf("rank", "observation", "possible_explanation", "recommended_check", "evidence_refs")
private val RECOMMENDATION_FIELDS = setOf("rank", "action", "rationale", "evidence_refs")
private val ADVICE_FIELDS =
    setOf(
        "schema_version",
        "advisory",
        "run_id",
        "analysis_id",
        "analysis_manifest_sha256",
        "evidence_input_sha256",
        "output",
        "provenance",
    )
private val PROVENANCE_FIELDS =
    setOf(
        "invocation_id",
        "runner_id",
        "runner_version",
        "runner_artifact_sha256",
        "model_id",
        "prompt_version",
        "prompt_sha256",
        "duration_ms",
        "exit_code",
        "validation",
    )
private val SECRET_KEYS =
    setOf(
        "api_key",
        "authorization",
        "password",
        "passwd",
        "secret",
        "token",
        "access_token",
        "refresh_token",
        "private_key",
        "cookie",
        "credential",
        "connection_string",
    )
private val SECRET_PATTERNS =
    listOf(
        Regex("\\b(?:sk|pk)-[A-Za-z0-9_-]{12,}\\b"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
    )

// Group 1 is kept, group 2 is the kept tail; the secret value between them becomes REDACTED.
// Quantifiers around the keywords are bounded so a long free-text value cannot cause quadratic matching.
private val INLINE_SECRET_PATTERNS =
    listOf(
        // Cookie / Set-Cookie: the whole value up to the end of the line.
        Regex("(?i)(cookie[\\w.-]{0,32}[\"']?\\s{0,8}[=:]\\s{0,8})[^\\r\\n]+()"),
        // key=value / key: value pairs in headers and query strings (access_token, api-key, JSESSIONID, ...).
        Regex(
            "(?i)((?:password|passwd|pwd|secret|token|api[_-]?key|authorization|session)[\\w.-]{0,32}[\"']?\\s{0,8}[=:]\\s{0,8})" +
                "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|(?:(?:bearer|basic|digest|negotiate|token)\\s+)?[^\\s&;,\"']+)()",
        ),
        Regex("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]{8,}()"),
        Regex("(?i)(https?://)[^\\s/:@]+:[^\\s/@]+(@)"),
        Regex("()\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*()"),
        // Long base64-like token mixing lower case, upper case and digits; plain hex ids and UUIDs do not match.
        Regex("(?<![A-Za-z0-9+_=-])()(?=[A-Za-z0-9+_=-]*[a-z])(?=[A-Za-z0-9+_=-]*[A-Z])(?=[A-Za-z0-9+_=-]*[0-9])[A-Za-z0-9+_=-]{32,}()"),
    )

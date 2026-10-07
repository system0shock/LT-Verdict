package io.ltverdict.ai

import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class ModelStudioAdvisoryRunner private constructor(
    private val repositoryRoot: Path,
    private val qwenPackageRoot: Path,
    private val credentialEnvFile: Path,
    private val hostEnvironment: Map<String, String>,
    private val config: AiModelsConfig,
    private val direct: DirectRunnerSettings? = null,
) : AdvisoryRunner {
    override fun invoke(evidence: AdvisoryEvidence): RunnerOutcome = invoke(evidence, null)

    override fun invoke(
        evidence: AdvisoryEvidence,
        modelId: String?,
    ): RunnerOutcome {
        // The slug and the address come only from the configuration; a slug that is not in it never reaches the launcher.
        val selected = modelId ?: config.defaultModel
        if (config.models.none { it.id == selected }) return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        val script = repositoryRoot.resolve(if (direct != null) DIRECT_RUNTIME_SCRIPT else RUNTIME_SCRIPT)
        val prompt = repositoryRoot.resolve(PROMPT_FILE)
        if (!Files.isRegularFile(script) || !Files.isRegularFile(prompt)) {
            return RunnerOutcome.Unavailable(AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING)
        }
        // The direct runner reads no credential: the CLI uses its own authorization (ADR 0027).
        if (direct == null &&
            (!Files.isRegularFile(credentialEnvFile) || Files.size(credentialEnvFile) !in 1..MAX_CREDENTIAL_FILE_BYTES)
        ) {
            return RunnerOutcome.Unavailable(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED)
        }

        val temporary = Files.createTempDirectory("lt-verdict-ai-")
        try {
            val evidencePath = temporary.resolve("evidence.json")
            val outputPath = temporary.resolve("advice-output.json")
            val resultPath = temporary.resolve("runtime-result.json")
            val cancelPath = temporary.resolve("cancel")
            Files.write(evidencePath, evidence.bytes, StandardOpenOption.CREATE_NEW)

            val process =
                try {
                    ProcessBuilder(command(script, evidencePath, outputPath, resultPath, cancelPath, selected))
                        .directory(repositoryRoot.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .also { builder ->
                            builder.environment().clear()
                            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
                            builder.environment().putAll(hostEnvironmentForChild(hostEnvironment, windows, direct?.environmentNames.orEmpty()))
                        }.start()
                } catch (_: IOException) {
                    return RunnerOutcome.Unavailable(
                        if (direct != null) AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING else AdviceUnavailableReason.DOCKER_UNAVAILABLE,
                    )
                }

            val finished =
                try {
                    process.waitFor(KOTLIN_OUTER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    requestCancellation(cancelPath, process)
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            if (!finished) {
                requestCancellation(cancelPath, process)
                return RunnerOutcome.Failed(AdviceFailure.TIMEOUT)
            }
            return readResult(resultPath, outputPath, process.exitValue(), selected)
        } finally {
            DataDirectory.deleteTree(temporary)
        }
    }

    private fun command(
        script: Path,
        evidence: Path,
        output: Path,
        result: Path,
        cancel: Path,
        modelId: String,
    ): List<String> =
        if (direct != null) directCommand(direct, script, evidence, output, result, cancel, modelId) else dockerCommand(script, evidence, output, result, cancel, modelId)

    private fun directCommand(
        settings: DirectRunnerSettings,
        script: Path,
        evidence: Path,
        output: Path,
        result: Path,
        cancel: Path,
        modelId: String,
    ): List<String> =
        buildList {
            add(settings.bash)
            add(script.toString())
            addAll(listOf("--evidence-path", evidence.toString(), "--output-path", output.toString()))
            addAll(listOf("--result-path", result.toString(), "--cancel-path", cancel.toString()))
            addAll(listOf("--cmd", settings.command, "--sha256", settings.sha256, "--auth-type", settings.authType))
            if (modelId != CLI_DEFAULT_MODEL) addAll(listOf("--model", modelId))
            settings.node?.let { addAll(listOf("--node", it)) }
            settings.cwd?.let { addAll(listOf("--cwd", it)) }
            if (settings.passthrough.isNotEmpty()) addAll(listOf("--passthrough", settings.passthrough.joinToString(",")))
        }

    private fun dockerCommand(
        script: Path,
        evidence: Path,
        output: Path,
        result: Path,
        cancel: Path,
        modelId: String,
    ) = listOf(
        powershellPath(hostEnvironment),
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        script.toString(),
        "-Mode",
        "Live",
        "-EvidencePath",
        evidence.toString(),
        "-OutputPath",
        output.toString(),
        "-ResultPath",
        result.toString(),
        "-CancelPath",
        cancel.toString(),
        "-CredentialEnvFile",
        credentialEnvFile.toString(),
        "-QwenPackageRoot",
        qwenPackageRoot.toString(),
        "-ModelId",
        modelId,
        "-UpstreamUrl",
        config.endpointUrl,
    ) + if (config.allowInsecureHttp) listOf("-AllowInsecureHttp") else emptyList()

    private fun readResult(
        resultPath: Path,
        outputPath: Path,
        processExitCode: Int,
        selectedModel: String,
    ): RunnerOutcome {
        if (!Files.isRegularFile(resultPath) || Files.size(resultPath) > MAX_RUNTIME_RESULT_BYTES) {
            return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        }
        val result =
            try {
                Json.parseToJsonElement(Files.readString(resultPath)) as? JsonObject
                    ?: return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
            } catch (_: SerializationException) {
                return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
            } catch (_: IllegalArgumentException) {
                return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
            }
        if (result.string("schema_version") != "advisory-ai-runtime-result.v1") {
            return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        }
        val duration = result.long("duration_ms") ?: return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        val exitCode = result.integer("exit_code") ?: return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        return when (result.string("status")) {
            "SUCCESS" -> {
                if (direct != null) return directSuccess(result, outputPath, processExitCode, duration, exitCode, direct)
                // The relay's observation of where the evidence went; without it the advice cannot be saved (ADR 0023, D4).
                val endpointHost = result.string("endpoint_host")
                // Requests forwarded to the provider and the hash of the prompt snapshot mounted into the container,
                // both reported by the launcher (ADR 0021, D1 p. 2, D2): the file is not read again after the run.
                val providerRequests = result.integer("provider_request_count")
                val promptSha256 = result.string("prompt_sha256")
                if (processExitCode != 0 ||
                    exitCode != 0 ||
                    duration !in 0..613_000 ||
                    endpointHost == null ||
                    !validEndpointHost(endpointHost) ||
                    endpointHost != endpointHostOf(config.endpointUrl) ||
                    providerRequests == null ||
                    providerRequests !in 1..2 ||
                    promptSha256 == null ||
                    !validSha256(promptSha256) ||
                    result.string("model_id") != selectedModel ||
                    !Files.isRegularFile(outputPath) ||
                    Files.size(outputPath) > MAX_ADVICE_OUTPUT_BYTES
                ) {
                    RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
                } else {
                    RunnerOutcome.Success(
                        Files.readAllBytes(outputPath),
                        RunnerProvenance(
                            runnerId = QwenCode0211.RUNNER_ID,
                            runnerVersion = QwenCode0211.RUNNER_VERSION,
                            runnerArtifactSha256 = QwenCode0211.CLI_ENTRY_SHA256,
                            modelId = selectedModel,
                            endpointHost = endpointHost,
                            promptVersion = QwenCode0211.PROMPT_VERSION,
                            promptSha256 = promptSha256,
                            providerRequests = providerRequests,
                            durationMillis = duration,
                            exitCode = exitCode,
                        ),
                    )
                }
            }

            "FAILED" -> RunnerOutcome.Failed(result.enumValue("failure_code") ?: AdviceFailure.PROCESS_FAILED)
            "UNAVAILABLE" ->
                RunnerOutcome.Unavailable(
                    result.enumValue<AdviceUnavailableReason>("unavailable_reason")
                        ?: AdviceUnavailableReason.OS_ISOLATION_NOT_PROVEN,
                )

            "CANCELLED" -> throw CancellationException("CANCELLED")
            else -> RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        }
    }

    /** ADR 0027: no relay, so no endpoint and no request count; the artifact hash is the operator's pin, the model is what the CLI reported. */
    private fun directSuccess(
        result: JsonObject,
        outputPath: Path,
        processExitCode: Int,
        duration: Long,
        exitCode: Int,
        settings: DirectRunnerSettings,
    ): RunnerOutcome {
        val promptSha256 = result.string("prompt_sha256")
        val model = result.string("model_id")
        val version = result.string("runner_version")
        if (processExitCode != 0 ||
            exitCode != 0 ||
            duration !in 0..613_000 ||
            result.string("endpoint_host") != DIRECT_ENDPOINT_HOST ||
            promptSha256 == null ||
            !validSha256(promptSha256) ||
            model == null ||
            !validModelSlug(model) ||
            version == null ||
            result.string("runner_artifact_sha256") != settings.sha256 ||
            !Files.isRegularFile(outputPath) ||
            Files.size(outputPath) > MAX_ADVICE_OUTPUT_BYTES
        ) {
            return RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED)
        }
        return RunnerOutcome.Success(
            Files.readAllBytes(outputPath),
            RunnerProvenance(
                runnerId = QwenCode0211.RUNNER_ID,
                runnerVersion = version,
                runnerArtifactSha256 = settings.sha256,
                modelId = model,
                endpointHost = DIRECT_ENDPOINT_HOST,
                promptVersion = QwenCode0211.PROMPT_VERSION,
                promptSha256 = promptSha256,
                providerRequests = null,
                durationMillis = duration,
                exitCode = exitCode,
            ),
        )
    }

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            repositoryRoot: Path? = null,
            config: AiModelsConfig = AiModelsConfig.BUILT_IN,
        ): AdvisoryRunner {
            if (environment.containsKey(RUNNER_MODE_ENVIRONMENT) && !directRunnerRequested(environment)) {
                return UnavailableAdvisoryRunner(AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING)
            }
            if (directRunnerRequested(environment)) {
                val settings =
                    DirectRunnerSettings.fromEnvironment(environment)
                        ?: return UnavailableAdvisoryRunner(AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING)
                val root = environment[RUNTIME_ROOT_ENVIRONMENT]?.trim()?.takeIf(String::isNotEmpty)?.pathOrNull()
                    ?: repositoryRoot?.toAbsolutePath()?.normalize()
                    ?: defaultRuntimeRoot()
                return ModelStudioAdvisoryRunner(root, root, root, environment, config, settings)
            }
            val configured = environment[CREDENTIAL_ENVIRONMENT]?.trim()
            if (configured.isNullOrEmpty()) return UnavailableAdvisoryRunner(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED)
            val credential = configured.pathOrNull() ?: return UnavailableAdvisoryRunner(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED)
            val root =
                environment[RUNTIME_ROOT_ENVIRONMENT]?.trim()?.takeIf(String::isNotEmpty)?.pathOrNull()
                    ?: repositoryRoot?.toAbsolutePath()?.normalize()
                    ?: defaultRuntimeRoot()
            val qwen =
                environment[QWEN_ROOT_ENVIRONMENT]?.trim()?.takeIf(String::isNotEmpty)?.pathOrNull()
                    ?: root.resolve(DEFAULT_QWEN_ROOT).normalize()
            return ModelStudioAdvisoryRunner(root, qwen, credential, environment, config)
        }
    }
}

private fun requestCancellation(
    cancelPath: Path,
    process: Process,
) {
    val descendants = process.descendants().use { it.toList() }.toMutableSet()
    runCatching { Files.writeString(cancelPath, "cancel", StandardOpenOption.CREATE_NEW) }
    if (!runCatching { process.waitFor(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
        descendants += process.descendants().use { it.toList() }
        descendants.toList().asReversed().forEach { runCatching { it.destroyForcibly() } }
        process.destroyForcibly()
    }
    descendants.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroyForcibly() } }
    runCatching {
        java.util.concurrent.CompletableFuture
            .allOf(
                process.onExit(),
                *descendants.map(ProcessHandle::onExit).toTypedArray(),
            ).get(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}

internal fun hostEnvironmentForChild(
    host: Map<String, String>,
    windows: Boolean,
    extraNames: Set<String> = emptySet(),
): Map<String, String> =
    buildMap {
        for (name in HOST_ENVIRONMENT_ALLOWLIST + extraNames) {
            val value =
                host[name]
                    ?: if (windows) {
                        host.keys
                            .filter { it.equals(name, ignoreCase = true) }
                            .minOrNull()
                            ?.let(host::get)
                    } else {
                        null
                    }
            if (value != null) put(name, value)
        }
    }

private fun powershellPath(environment: Map<String, String>): String {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return "pwsh"
    val host = hostEnvironmentForChild(environment, windows = true)
    val systemRoot =
        host["SystemRoot"]
            ?: host["WINDIR"]
            ?: "C:\\Windows"
    return Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()
}

private fun String.pathOrNull(): Path? =
    try {
        Path.of(this).toAbsolutePath().normalize()
    } catch (_: IllegalArgumentException) {
        null
    }

private fun defaultRuntimeRoot(): Path {
    val location =
        runCatching {
            Path
                .of(
                    ModelStudioAdvisoryRunner::class.java.protectionDomain.codeSource.location
                        .toURI(),
                ).toAbsolutePath()
                .normalize()
        }.getOrNull()
    return if (location != null && Files.isRegularFile(location) && location.parent?.parent != null) {
        location.parent.parent
    } else {
        Path.of("").toAbsolutePath().normalize()
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private inline fun <reified T : Enum<T>> JsonObject.enumValue(name: String): T? =
    string(name)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

private const val RUNNER_MODE_ENVIRONMENT = "LT_VERDICT_AI_RUNNER_MODE"
private const val DIRECT_RUNTIME_SCRIPT = "tools/advisory_ai_runtime_local.sh"

/** `LT_VERDICT_AI_RUNNER_MODE=local` selects the direct runner (ADR 0027); absent means the container runner. */
internal fun directRunnerRequested(environment: Map<String, String>): Boolean = environment[RUNNER_MODE_ENVIRONMENT]?.trim() == "local"

/**
 * The operator's description of the CLI that the direct runner starts (ADR 0027). The product never reads the values of the
 * passed-through variables: the launcher copies them into the environment of the CLI, which holds its own authorization.
 */
internal class DirectRunnerSettings(
    val command: String,
    val sha256: String,
    val node: String?,
    val cwd: String?,
    val passthrough: List<String>,
    val authType: String,
    val bash: String,
) {
    /** Variables of the host environment that reach the launcher and the CLI beyond the common allowlist. */
    val environmentNames: Set<String> = DIRECT_ENVIRONMENT_NAMES + passthrough

    companion object {
        fun fromEnvironment(environment: Map<String, String>): DirectRunnerSettings? {
            fun value(name: String) = environment[name]?.trim()?.takeIf(String::isNotEmpty)
            val command = value("LT_VERDICT_AI_LOCAL_QWEN_CMD")?.takeIf { runCatching { Path.of(it).isAbsolute }.getOrDefault(false) } ?: return null
            val sha256 = value("LT_VERDICT_AI_LOCAL_QWEN_SHA256")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            val names = value("LT_VERDICT_AI_LOCAL_PASSTHROUGH_ENV")?.split(',')?.map(String::trim).orEmpty()
            if (names.any { !it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) }) return null
            val authType = value("LT_VERDICT_AI_LOCAL_AUTH_TYPE") ?: "openai"
            if (authType !in setOf("openai", "qwen-oauth", "anthropic", "gemini", "vertex-ai")) return null
            return DirectRunnerSettings(
                command,
                sha256,
                value("LT_VERDICT_AI_LOCAL_NODE"),
                value("LT_VERDICT_AI_LOCAL_CWD"),
                names,
                authType,
                value("LT_VERDICT_AI_LOCAL_BASH") ?: "bash",
            )
        }
    }
}

private val DIRECT_ENVIRONMENT_NAMES =
    setOf("HOME", "USERPROFILE", "APPDATA", "USER", "LOGNAME", "LANG", "LC_ALL", "SYSTEMROOT")
private const val CREDENTIAL_ENVIRONMENT = "LT_VERDICT_AI_CREDENTIAL_ENV_FILE"
private const val RUNTIME_ROOT_ENVIRONMENT = "LT_VERDICT_AI_RUNTIME_ROOT"
private const val QWEN_ROOT_ENVIRONMENT = "LT_VERDICT_AI_QWEN_ROOT"
private const val RUNTIME_SCRIPT = "tools/advisory_ai_runtime.ps1"
private const val PROMPT_FILE = "docs/contracts/advice/v1/system-prompt.md"
private const val DEFAULT_QWEN_ROOT = "build/ai-runner/qwen-code-0.21.1/node_modules/@qwen-code/qwen-code"
private const val MAX_CREDENTIAL_FILE_BYTES = 8_192L
private const val MAX_RUNTIME_RESULT_BYTES = 16_384L
private const val KOTLIN_OUTER_TIMEOUT_MILLIS = 620_000L
private const val CLEANUP_TIMEOUT_SECONDS = 20L
private val HOST_ENVIRONMENT_ALLOWLIST =
    setOf(
        "SystemRoot",
        "WINDIR",
        "PATH",
        "PATHEXT",
        "ProgramFiles",
        "ProgramFiles(x86)",
        "ProgramData",
        "LOCALAPPDATA",
        "TEMP",
        "TMP",
        "ComSpec",
    )

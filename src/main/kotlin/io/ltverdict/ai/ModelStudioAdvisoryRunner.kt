package io.ltverdict.ai

import io.ltverdict.core.sha256Hex
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
) : AdvisoryRunner {
    override fun invoke(evidence: AdvisoryEvidence): RunnerOutcome {
        val script = repositoryRoot.resolve(RUNTIME_SCRIPT)
        val prompt = repositoryRoot.resolve(PROMPT_FILE)
        if (!Files.isRegularFile(script) || !Files.isRegularFile(prompt)) {
            return RunnerOutcome.Unavailable(AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING)
        }
        if (!Files.isRegularFile(credentialEnvFile) || Files.size(credentialEnvFile) !in 1..MAX_CREDENTIAL_FILE_BYTES) {
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
                    ProcessBuilder(command(script, evidencePath, outputPath, resultPath, cancelPath))
                        .directory(repositoryRoot.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .also { builder ->
                            builder.environment().clear()
                            hostEnvironment.filterKeys(HOST_ENVIRONMENT_ALLOWLIST::contains).forEach(builder.environment()::put)
                        }.start()
                } catch (_: IOException) {
                    return RunnerOutcome.Unavailable(AdviceUnavailableReason.DOCKER_UNAVAILABLE)
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
            return readResult(resultPath, outputPath, prompt, process.exitValue())
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
    )

    private fun readResult(
        resultPath: Path,
        outputPath: Path,
        promptPath: Path,
        processExitCode: Int,
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
                if (processExitCode != 0 ||
                    exitCode != 0 ||
                    duration !in 0..613_000 ||
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
                            modelId = QwenCode0211.MODEL_ID,
                            promptVersion = QwenCode0211.PROMPT_VERSION,
                            promptSha256 = sha256Hex(Files.readAllBytes(promptPath)),
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

    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            repositoryRoot: Path? = null,
        ): AdvisoryRunner {
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
            return ModelStudioAdvisoryRunner(root, qwen, credential, environment)
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

private fun powershellPath(environment: Map<String, String>): String {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return "pwsh"
    val systemRoot = environment["SystemRoot"] ?: environment["WINDIR"] ?: "C:\\Windows"
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

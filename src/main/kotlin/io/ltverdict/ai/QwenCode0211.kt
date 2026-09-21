package io.ltverdict.ai

internal data class QwenRuntimePaths(
    val home: String,
    val runtime: String,
    val temp: String,
)

internal data class QwenInvocation(
    val argv: List<String>,
    val environment: Map<String, String>,
)

internal object QwenCode0211 {
    const val RUNNER_ID = "gigacode-qwen-code"
    const val RUNNER_VERSION = "0.21.1"
    const val CLI_ENTRY_SHA256 = "1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38"
    const val MODEL_ID = "deepseek-v4-flash-0731"
    const val MODEL_ENDPOINT = "http://modelstudio-relay:18080/v1"
    const val PROVIDER_ENDPOINT = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
    const val PROMPT_VERSION = "advisory-system.v1"

    fun invocation(
        nodePath: String,
        cliEntryPath: String,
        schemaPath: String,
        systemPrompt: String,
        apiKey: String,
        runtimePaths: QwenRuntimePaths,
    ): QwenInvocation {
        require(listOf(nodePath, cliEntryPath, schemaPath, runtimePaths.home, runtimePaths.runtime, runtimePaths.temp).all(::safeArgument))
        require(systemPrompt.isNotBlank() && systemPrompt.encodeToByteArray().size <= 16_384 && '\u0000' !in systemPrompt)
        require(apiKey.isNotBlank() && '\u0000' !in apiKey && '\n' !in apiKey && '\r' !in apiKey)
        return QwenInvocation(
            argv =
                listOf(
                    nodePath,
                    cliEntryPath,
                    "--bare",
                    "--safe-mode",
                    "--auth-type=openai",
                    "--model=$MODEL_ID",
                    "--openai-base-url=$MODEL_ENDPOINT",
                    "--system-prompt=$systemPrompt",
                    "--input-format=text",
                    "--output-format=json",
                    "--json-schema=@$schemaPath",
                    "--exclude-tools=read_file,edit,notebook_edit,run_shell_command",
                    "--max-tool-calls=0",
                    "--max-wall-time=600s",
                    "--approval-mode=default",
                    "--chat-recording=false",
                    "--openai-logging=false",
                    "--telemetry=false",
                ),
            environment =
                linkedMapOf(
                    "HOME" to runtimePaths.home,
                    "QWEN_HOME" to runtimePaths.home,
                    "QWEN_RUNTIME_DIR" to runtimePaths.runtime,
                    "TEMP" to runtimePaths.temp,
                    "TMP" to runtimePaths.temp,
                    "OPENAI_API_KEY" to apiKey,
                    "QWEN_CODE_API_TIMEOUT_MS" to "600000",
                    "QWEN_TELEMETRY_ENABLED" to "0",
                    "QWEN_USAGE_STATISTICS_ENABLED" to "0",
                    "NO_BROWSER" to "1",
                ),
        )
    }

    private fun safeArgument(value: String): Boolean = value.isNotBlank() && '\u0000' !in value && '\n' !in value && '\r' !in value
}

internal class UnavailableAdvisoryRunner(
    private val reason: AdviceUnavailableReason,
) : AdvisoryRunner {
    override fun invoke(evidence: AdvisoryEvidence): RunnerOutcome = RunnerOutcome.Unavailable(reason)
}

package io.ltverdict.ai

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ModelStudioAdvisoryRunnerTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `missing credential configuration is unavailable without starting a process`() {
        val outcome = ModelStudioAdvisoryRunner.fromEnvironment(emptyMap(), tempDir).invoke(EVIDENCE)

        assertEquals(RunnerOutcome.Unavailable(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED), outcome)
    }

    @Test
    fun `windows canonicalizes mixed case host environment names`() {
        assertEquals(mapOf("PATH" to "X"), hostEnvironmentForChild(mapOf("Path" to "X"), windows = true))
    }

    @Test
    fun `windows exact host environment names take precedence`() {
        assertEquals(
            mapOf("PATH" to "A", "SystemRoot" to "R1"),
            hostEnvironmentForChild(
                mapOf("PATH" to "A", "Path" to "B", "SystemRoot" to "R1", "systemroot" to "R2"),
                windows = true,
            ),
        )
    }

    @Test
    fun `windows chooses the first sorted non exact variant`() {
        assertEquals(
            mapOf("PATH" to "B"),
            hostEnvironmentForChild(mapOf("path" to "C", "Path" to "B"), windows = true),
        )
    }

    @Test
    fun `non windows retains only exact host environment names`() {
        assertEquals(
            mapOf("PATH" to "A"),
            hostEnvironmentForChild(mapOf("Path" to "X", "PATH" to "A"), windows = false),
        )
        assertEquals(emptyMap<String, String>(), hostEnvironmentForChild(mapOf("Path" to "X"), windows = false))
    }

    @Test
    fun `host environment drops non allowlist keys on every platform`() {
        val host = mapOf("OPENAI_API_KEY" to "secret", "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to "credential")
        assertEquals(emptyMap<String, String>(), hostEnvironmentForChild(host, windows = true))
        assertEquals(emptyMap<String, String>(), hostEnvironmentForChild(host, windows = false))
    }

    @Test
    fun `host environment excludes source mTLS password`() {
        assertEquals(
            mapOf("PATH" to "p"),
            hostEnvironmentForChild(mapOf("LTV_GRAFANA_MTLS_PASSWORD" to "x", "PATH" to "p"), windows = false),
        )
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `runtime result maps to bounded success without exposing credential value`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        val prompt = Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md")
        Files.writeString(prompt, "bounded prompt")
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=probe-secret-value\n")
        Files.writeString(
            tools.resolve("advisory_ai_runtime.ps1"),
            """
            param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
            ${'$'}ErrorActionPreference = 'Stop'
            Write-Output ('suppressed-host-output' * 2000)
            [IO.File]::WriteAllText((Join-Path ${'$'}PSScriptRoot 'capture.txt'), ${'$'}CredentialEnvFile + "`n" + ((Get-ChildItem Env: | ForEach-Object { ${'$'}_.Name + '=' + ${'$'}_.Value }) -join "`n"))
            [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
            [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0,"provider_request_count":1,"prompt_sha256":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","endpoint_host":"token-plan.ap-southeast-1.maas.aliyuncs.com:443","model_id":"deepseek-v4-flash-0731"}')
            exit 0
            """.trimIndent(),
        )

        val runner =
            ModelStudioAdvisoryRunner.fromEnvironment(
                mapOf(
                    "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString(),
                    "LT_VERDICT_AI_RUNTIME_ROOT" to tempDir.toString(),
                ),
            )
        val success = assertInstanceOf(RunnerOutcome.Success::class.java, runner.invoke(EVIDENCE))
        assertEquals("token-plan.ap-southeast-1.maas.aliyuncs.com:443", success.provenance.endpointHost)

        assertArrayEquals(
            """{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}"""
                .encodeToByteArray(),
            success.output,
        )
        assertEquals(QwenCode0211.MODEL_ID, success.provenance.modelId)
        assertEquals(12, success.provenance.durationMillis)
        // The request count and the prompt hash come from the launcher's result; the prompt file is not read again.
        assertEquals(1, success.provenance.providerRequests)
        assertEquals(PROMPT_SHA256, success.provenance.promptSha256)
        assertEquals(QwenCode0211.PROMPT_VERSION, success.provenance.promptVersion)
        val capture = Files.readString(tools.resolve("capture.txt"))
        assertFalse(capture.contains("probe-secret-value"))
        assertTrue(capture.contains(credential.toString()))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `success without a valid observed endpoint host is a process failure`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        val prompt = Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md")
        Files.writeString(prompt, "bounded prompt")
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=fake\n")
        val script = tools.resolve("advisory_ai_runtime.ps1")
        val runner =
            ModelStudioAdvisoryRunner.fromEnvironment(
                mapOf(
                    "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString(),
                    "LT_VERDICT_AI_RUNTIME_ROOT" to tempDir.toString(),
                ),
            )
        // Absent, null, a number, and values that are not lower case host:port.
        listOf(
            "",
            ",\"endpoint_host\":null",
            ",\"endpoint_host\":443",
            ",\"endpoint_host\":\"\"",
            ",\"endpoint_host\":\"models.example\"",
            ",\"endpoint_host\":\"https://models.example:443\"",
        ).forEach { member ->
            Files.writeString(
                script,
                """
                param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
                [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
                [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0,"provider_request_count":1,"prompt_sha256":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","model_id":"deepseek-v4-flash-0731"$member}')
                exit 0
                """.trimIndent(),
            )

            assertEquals(RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED), runner.invoke(EVIDENCE), member)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `success reports the retry and is a process failure without a valid request count or prompt hash`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        Files.writeString(
            Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md"),
            "bounded prompt",
        )
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=fake\n")
        val script = tools.resolve("advisory_ai_runtime.ps1")
        val runner =
            ModelStudioAdvisoryRunner.fromEnvironment(
                mapOf(
                    "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString(),
                    "LT_VERDICT_AI_RUNTIME_ROOT" to tempDir.toString(),
                ),
            )

        fun fake(members: String) =
            Files.writeString(
                script,
                """
                param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
                [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
                [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0,"endpoint_host":"token-plan.ap-southeast-1.maas.aliyuncs.com:443","model_id":"deepseek-v4-flash-0731"$members}')
                exit 0
                """.trimIndent(),
            )

        fake(",\"provider_request_count\":2,\"prompt_sha256\":\"$PROMPT_SHA256\"")
        val retried = assertInstanceOf(RunnerOutcome.Success::class.java, runner.invoke(EVIDENCE))
        assertEquals(2, retried.provenance.providerRequests)
        assertEquals(PROMPT_SHA256, retried.provenance.promptSha256)

        listOf(
            "",
            ",\"prompt_sha256\":\"$PROMPT_SHA256\"",
            ",\"provider_request_count\":1",
            ",\"provider_request_count\":0,\"prompt_sha256\":\"$PROMPT_SHA256\"",
            ",\"provider_request_count\":3,\"prompt_sha256\":\"$PROMPT_SHA256\"",
            ",\"provider_request_count\":null,\"prompt_sha256\":\"$PROMPT_SHA256\"",
            ",\"provider_request_count\":\"1\",\"prompt_sha256\":\"$PROMPT_SHA256\"",
            ",\"provider_request_count\":1,\"prompt_sha256\":null",
            ",\"provider_request_count\":1,\"prompt_sha256\":\"not a hash\"",
            ",\"provider_request_count\":1,\"prompt_sha256\":\"${PROMPT_SHA256.uppercase()}\"",
        ).forEach { members ->
            fake(members)

            assertEquals(RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED), runner.invoke(EVIDENCE), members)
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `the configured model and address reach the launcher as separate parameters and are checked in its result`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        Files.writeString(
            Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md"),
            "bounded prompt",
        )
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=probe-secret-value\n")
        val script = tools.resolve("advisory_ai_runtime.ps1")
        val capture = tools.resolve("capture.txt")
        val environment =
            mapOf(
                "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString(),
                "LT_VERDICT_AI_RUNTIME_ROOT" to tempDir.toString(),
            )

        fun fake(result: String) =
            Files.writeString(
                script,
                """
                param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
                [IO.File]::WriteAllText((Join-Path ${'$'}PSScriptRoot 'capture.txt'), (@(${'$'}ModelId, ${'$'}UpstreamUrl, ${'$'}AllowInsecureHttp.IsPresent) -join ' '))
                [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
                [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0,"provider_request_count":1,"prompt_sha256":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"$result}')
                exit 0
                """.trimIndent(),
            )

        fun result(
            model: String?,
            host: String?,
        ) = listOfNotNull(model?.let { ",\"model_id\":\"$it\"" }, host?.let { ",\"endpoint_host\":\"$it\"" }).joinToString("")

        val insecure =
            AiModelsConfig(
                endpointUrl = "http://Gw.Internal:8080/v1/chat",
                endpointLabel = null,
                allowInsecureHttp = true,
                defaultModel = "qwen3.8-max",
                models = listOf(AiModel("qwen3.8-max", "Q"), AiModel("org/other:2", "O")),
            )
        val runner = ModelStudioAdvisoryRunner.fromEnvironment(environment, tempDir, insecure)

        fake(result("qwen3.8-max", "gw.internal:8080"))
        val byDefault = assertInstanceOf(RunnerOutcome.Success::class.java, runner.invoke(EVIDENCE))
        assertEquals("qwen3.8-max http://Gw.Internal:8080/v1/chat True", Files.readString(capture))
        assertEquals("qwen3.8-max", byDefault.provenance.modelId)
        assertEquals("gw.internal:8080", byDefault.provenance.endpointHost)

        fake(result("org/other:2", "gw.internal:8080"))
        val chosen = assertInstanceOf(RunnerOutcome.Success::class.java, runner.invoke(EVIDENCE, "org/other:2"))
        assertEquals("org/other:2 http://Gw.Internal:8080/v1/chat True", Files.readString(capture))
        assertEquals("org/other:2", chosen.provenance.modelId)

        // The model and the host the launcher observed must be the ones that were asked for.
        for (mismatch in listOf(
            result("org/other:2", "gw.internal:8080"),
            result(null, "gw.internal:8080"),
            result("qwen3.8-max", "other.example:8080"),
            result("qwen3.8-max", "gw.internal:80"),
            result("qwen3.8-max", null),
        )) {
            fake(mismatch)
            assertEquals(RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED), runner.invoke(EVIDENCE, "qwen3.8-max"), mismatch)
        }

        // A model outside the configuration never reaches the launcher.
        Files.deleteIfExists(capture)
        fake(result("org/unknown:1", "gw.internal:8080"))
        assertEquals(RunnerOutcome.Failed(AdviceFailure.PROCESS_FAILED), runner.invoke(EVIDENCE, "org/unknown:1"))
        assertFalse(Files.exists(capture))

        // https configuration: no insecure switch; the host defaults to port 443.
        val secure = insecure.copy(endpointUrl = "https://Models.Internal.Example/v1/chat", allowInsecureHttp = false)
        fake(result("qwen3.8-max", "models.internal.example:443"))
        assertInstanceOf(
            RunnerOutcome.Success::class.java,
            ModelStudioAdvisoryRunner.fromEnvironment(environment, tempDir, secure).invoke(EVIDENCE),
        )
        assertEquals("qwen3.8-max https://Models.Internal.Example/v1/chat False", Files.readString(capture))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `mixed case host environment names reach the runtime`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        val prompt = Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md")
        Files.writeString(prompt, "bounded prompt")
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=fake\n")
        Files.writeString(
            tools.resolve("advisory_ai_runtime.ps1"),
            """
            param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
            [IO.File]::WriteAllText((Join-Path ${'$'}PSScriptRoot 'capture.txt'), ((Get-ChildItem Env: | ForEach-Object { ${'$'}_.Name + '=' + ${'$'}_.Value }) -join "`n"))
            [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
            [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0,"provider_request_count":1,"prompt_sha256":"a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1","endpoint_host":"token-plan.ap-southeast-1.maas.aliyuncs.com:443","model_id":"deepseek-v4-flash-0731"}')
            exit 0
            """.trimIndent(),
        )
        val pathMarker = tempDir.resolve("missing-path-marker").toString()
        val runner =
            ModelStudioAdvisoryRunner.fromEnvironment(
                mapOf(
                    "LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString(),
                    "LT_VERDICT_AI_RUNTIME_ROOT" to tempDir.toString(),
                    "Path" to pathMarker,
                    "systemroot" to (System.getenv("SystemRoot") ?: "C:\\Windows"),
                ),
            )

        assertInstanceOf(RunnerOutcome.Success::class.java, runner.invoke(EVIDENCE))
        assertTrue(Files.readString(tools.resolve("capture.txt")).contains(pathMarker))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `cancellation terminates an unresponsive runtime and its child`() {
        val tools = Files.createDirectories(tempDir.resolve("tools"))
        val prompt = Files.createDirectories(tempDir.resolve("docs/contracts/advice/v1")).resolve("system-prompt.md")
        Files.writeString(prompt, "bounded prompt")
        val credential = tempDir.resolve("modelstudio.env")
        Files.writeString(credential, "OPENAI_API_KEY=fake\n")
        Files.writeString(tools.resolve("child.ps1"), "Start-Sleep -Seconds 300")
        Files.writeString(
            tools.resolve("advisory_ai_runtime.ps1"),
            """
            param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot,[string]${'$'}ModelId,[string]${'$'}UpstreamUrl,[switch]${'$'}AllowInsecureHttp)
            ${'$'}child = Start-Process -FilePath (Join-Path ${'$'}PSHOME 'powershell.exe') -ArgumentList ('-NoProfile -File "' + (Join-Path ${'$'}PSScriptRoot 'child.ps1') + '"') -WindowStyle Hidden -PassThru
            [IO.File]::WriteAllText((Join-Path ${'$'}PSScriptRoot 'pids.txt'), "${'$'}PID`n$(${'$'}child.Id)")
            Start-Sleep -Seconds 300
            """.trimIndent(),
        )
        val runner =
            ModelStudioAdvisoryRunner.fromEnvironment(
                mapOf("LT_VERDICT_AI_CREDENTIAL_ENV_FILE" to credential.toString()),
                tempDir,
            )
        val thread =
            Thread {
                try {
                    runner.invoke(EVIDENCE)
                } catch (_: InterruptedException) {
                    // Expected cancellation path.
                }
            }
        thread.start()
        val pids = tools.resolve("pids.txt")
        var handles = emptyList<ProcessHandle>()
        try {
            val deadline =
                System.nanoTime() +
                    java.util.concurrent.TimeUnit.SECONDS
                        .toNanos(10)
            while (!Files.exists(pids) && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(Files.exists(pids), "Fake runtime did not start")
            handles = Files.readAllLines(pids).map { ProcessHandle.of(it.toLong()).orElseThrow() }
            thread.interrupt()
            thread.join(30_000)
            assertFalse(thread.isAlive, "Cancellation did not finish")
            assertTrue(handles.none(ProcessHandle::isAlive), "Runtime child survived cancellation")
        } finally {
            thread.interrupt()
            handles.forEach { if (it.isAlive) it.destroyForcibly() }
            thread.join(10_000)
        }
    }

    private companion object {
        const val PROMPT_SHA256 = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        val EVIDENCE =
            AdvisoryEvidence(
                """{"schema_version":"ai-evidence.v1"}""".encodeToByteArray(),
                "a".repeat(64),
                emptySet(),
            )
    }
}

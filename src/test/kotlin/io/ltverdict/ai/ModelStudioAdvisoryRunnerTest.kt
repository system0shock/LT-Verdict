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
            param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot)
            ${'$'}ErrorActionPreference = 'Stop'
            Write-Output ('suppressed-host-output' * 2000)
            [IO.File]::WriteAllText((Join-Path ${'$'}PSScriptRoot 'capture.txt'), ${'$'}CredentialEnvFile + "`n" + ((Get-ChildItem Env: | ForEach-Object { ${'$'}_.Name + '=' + ${'$'}_.Value }) -join "`n"))
            [IO.File]::WriteAllText(${'$'}OutputPath, '{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}')
            [IO.File]::WriteAllText(${'$'}ResultPath, '{"schema_version":"advisory-ai-runtime-result.v1","status":"SUCCESS","duration_ms":12,"exit_code":0}')
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

        assertArrayEquals(
            """{"schema_version":"ai-advice-output.v1","summary":"bounded","hypotheses":[],"recommendations":[],"caveats":[]}"""
                .encodeToByteArray(),
            success.output,
        )
        assertEquals(QwenCode0211.MODEL_ID, success.provenance.modelId)
        assertEquals(12, success.provenance.durationMillis)
        val capture = Files.readString(tools.resolve("capture.txt"))
        assertFalse(capture.contains("probe-secret-value"))
        assertTrue(capture.contains(credential.toString()))
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
            param([string]${'$'}Mode,[string]${'$'}EvidencePath,[string]${'$'}OutputPath,[string]${'$'}ResultPath,[string]${'$'}CancelPath,[string]${'$'}CredentialEnvFile,[string]${'$'}QwenPackageRoot)
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
        val EVIDENCE =
            AdvisoryEvidence(
                """{"schema_version":"ai-evidence.v1"}""".encodeToByteArray(),
                "a".repeat(64),
                emptySet(),
            )
    }
}

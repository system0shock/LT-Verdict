package io.ltverdict.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * ADR 0027, amendment: the real launcher with a fake CLI found by name on the operator's PATH (bash is required).
 * The fake drains stdin, records its arguments and prints the init and result events of the CLI.
 */
class DirectRunnerCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val root = Path.of("").toAbsolutePath().normalize()

    private fun bash(): String {
        val bash = if (windows) "C:\\Program Files\\Git\\bin\\bash.exe".takeIf { Files.exists(Path.of(it)) } else "bash"
        assumeTrue(bash != null, "bash is required")
        return bash!!
    }

    private fun fakeCli(name: String): Path {
        val bin = Files.createDirectories(tempDir.resolve("bin"))
        val record = tempDir.resolve("argv.txt").toString().replace('\\', '/')
        val advice =
            """{"schema_version":"ai-advice-output.v1","summary":"fake","hypotheses":[],"recommendations":[],"caveats":[]}"""
        val result = advice.replace("\"", "\\\"")
        val init = """{"type":"system","subtype":"init","tools":["structured_output"],"mcp_servers":[],"qwen_code_version":"fork-1"}"""
        val event = """{"type":"result","subtype":"success","result":"$result"}"""
        val script =
            listOf(
                "#!/bin/bash",
                "cat >/dev/null",
                "printf '%s\\n' \"\$@\" > '$record'",
                "echo '[$init,$event]'",
            ).joinToString("\n", postfix = "\n")
        val file = bin.resolve(name)
        Files.writeString(file, script)
        file.toFile().setExecutable(true)
        return file
    }

    private fun sha(file: Path) =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    private fun run(
        settings: Map<String, String>,
        path: Path? = tempDir.resolve("bin"),
    ): RunnerOutcome {
        val environment =
            System.getenv() +
                mapOf(
                    "LT_VERDICT_AI_RUNNER_MODE" to "local",
                    "LT_VERDICT_AI_RUNTIME_ROOT" to root.toString(),
                    "LT_VERDICT_AI_LOCAL_BASH" to bash(),
                    "HOME" to tempDir.toString(),
                    "USERPROFILE" to tempDir.toString(),
                ) + (if (path != null) mapOf("PATH" to path.toString() + File.pathSeparator + System.getenv("PATH")) else emptyMap()) +
                settings
        val config = AiModelsConfig(DIRECT_ENDPOINT_HOST, null, false, "m", listOf(AiModel("m", "M")))
        val evidence = AdvisoryEvidence("""{"schema_version":"ai-evidence.v1"}""".encodeToByteArray(), "0".repeat(64), emptySet())
        return ModelStudioAdvisoryRunner.fromEnvironment(environment, root, config).invoke(evidence)
    }

    @Test
    fun `a command name on PATH is resolved and its hash is reported when no pin is set`() {
        val file = fakeCli("gigacode")
        val success = assertInstanceOf(RunnerOutcome.Success::class.java, run(mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to "gigacode")))

        assertEquals(sha(file), success.provenance.runnerArtifactSha256)
        assertEquals("fork-1", success.provenance.runnerVersion)
        assertEquals(DIRECT_ENDPOINT_HOST, success.provenance.endpointHost)
        assertTrue(Files.readAllLines(tempDir.resolve("argv.txt")).containsAll(listOf("--safe-mode", "--model=m", "--bare")))
    }

    @Test
    fun `a pin that matches the resolved file is accepted and one that differs is a mismatch`() {
        val file = fakeCli("gigacode")
        val command = mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to "gigacode")

        val ok = run(command + ("LT_VERDICT_AI_LOCAL_QWEN_SHA256" to sha(file)))
        assertEquals(sha(file), assertInstanceOf(RunnerOutcome.Success::class.java, ok).provenance.runnerArtifactSha256)
        assertEquals(
            RunnerOutcome.Unavailable(AdviceUnavailableReason.RUNNER_ARTIFACT_MISMATCH),
            run(command + ("LT_VERDICT_AI_LOCAL_QWEN_SHA256" to "ab".repeat(32))),
        )
    }

    @Test
    fun `a missing command is unavailable`() {
        fakeCli("gigacode")
        assertEquals(
            RunnerOutcome.Unavailable(AdviceUnavailableReason.RUNNER_ARTIFACT_MISSING),
            run(mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to "no-such-gigacode-command")),
        )
    }

    @Test
    fun `a symlink on PATH is resolved and the target is hashed`() {
        assumeTrue(!windows, "symbolic links need privileges on Windows")
        val target = fakeCli("gigacode-real")
        Files.createSymbolicLink(tempDir.resolve("bin/gigacode"), target)
        val success = assertInstanceOf(RunnerOutcome.Success::class.java, run(mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to "gigacode")))
        assertEquals(sha(target), success.provenance.runnerArtifactSha256)
    }

    @Test
    fun `prefix arguments come before the flags of the CLI`() {
        fakeCli("npx")
        assertInstanceOf(
            RunnerOutcome.Success::class.java,
            run(mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to "npx", "LT_VERDICT_AI_LOCAL_QWEN_PREFIX_ARGS" to "--no-install  gigacode")),
        )
        val argv = Files.readAllLines(tempDir.resolve("argv.txt"))
        assertEquals(listOf("--no-install", "gigacode"), argv.take(2))
        assertTrue(argv.contains("--safe-mode"))
    }

    @Test
    fun `an absolute path still works`() {
        val file = fakeCli("gigacode")
        assertInstanceOf(RunnerOutcome.Success::class.java, run(mapOf("LT_VERDICT_AI_LOCAL_QWEN_CMD" to file.toString()), path = null))
    }
}

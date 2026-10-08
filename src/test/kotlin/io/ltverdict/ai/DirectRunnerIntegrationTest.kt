package io.ltverdict.ai

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * ADR 0027: the direct runner end to end with a real CLI and a local fake provider. The CLI is the operator's artifact, so the
 * test runs only when `LTV_TEST_DIRECT_CLI` names its entry file (a Qwen Code 0.21.x `cli-entry.js`) and a node and a bash exist;
 * the fake provider is injected through the CLI's own `OPENAI_BASE_URL`, nothing is sent anywhere else.
 */
class DirectRunnerIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `a real CLI answers through the direct runner against a local fake provider`() {
        val entry = System.getenv("LTV_TEST_DIRECT_CLI")
        assumeTrue(!entry.isNullOrBlank() && Files.isRegularFile(Path.of(entry)), "LTV_TEST_DIRECT_CLI is not set")
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val bash = if (windows) "C:\\Program Files\\Git\\bin\\bash.exe".takeIf { Files.exists(Path.of(it)) } else "bash"
        assumeTrue(bash != null, "bash is required")
        val sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(entry))).joinToString("") { "%02x".format(it) }

        val advice =
            """{"schema_version":"ai-advice-output.v1","summary":"fake","hypotheses":[],"recommendations":[],"caveats":["fake provider"]}"""
        val arguments = advice.replace("\\", "\\\\").replace("\"", "\\\"")
        val frames =
            listOf(
                """{"id":"f","model":"fake-local-stub","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"structured_output","arguments":"$arguments"}}]}}]}""",
                """{"id":"f","model":"fake-local-stub","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
            )
        val body = (frames.map { "data: $it\n\n" } + "data: [DONE]\n\n").joinToString("").toByteArray()
        var requests = 0
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            requests += 1
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val root = Path.of("").toAbsolutePath().normalize()
            val home = Files.createDirectories(tempDir.resolve("home"))
            val environment =
                System.getenv() +
                    mapOf(
                        "LT_VERDICT_AI_RUNNER_MODE" to "local",
                        "LT_VERDICT_AI_RUNTIME_ROOT" to root.toString(),
                        "LT_VERDICT_AI_LOCAL_QWEN_CMD" to entry,
                        "LT_VERDICT_AI_LOCAL_QWEN_SHA256" to sha,
                        "LT_VERDICT_AI_LOCAL_BASH" to bash!!,
                        "LT_VERDICT_AI_LOCAL_AUTH_TYPE" to "openai",
                        "LT_VERDICT_AI_LOCAL_PASSTHROUGH_ENV" to "OPENAI_BASE_URL,OPENAI_API_KEY",
                        "OPENAI_BASE_URL" to "http://127.0.0.1:${server.address.port}/v1",
                        "OPENAI_API_KEY" to "fake-not-a-key",
                        "HOME" to home.toString(),
                        "USERPROFILE" to home.toString(),
                    )
            val config =
                AiModelsConfig(DIRECT_ENDPOINT_HOST, null, false, "fake-local-stub", listOf(AiModel("fake-local-stub", "Fake")))
            val evidence = AdvisoryEvidence("""{"schema_version":"ai-evidence.v1"}""".encodeToByteArray(), "0".repeat(64), emptySet())
            val outcome = ModelStudioAdvisoryRunner.fromEnvironment(environment, root, config).invoke(evidence)

            val success = assertInstanceOf(RunnerOutcome.Success::class.java, outcome)
            assertEquals(1, requests)
            assertEquals("fake-local-stub", success.provenance.modelId)
            assertEquals(sha, success.provenance.runnerArtifactSha256)
            assertEquals(DIRECT_ENDPOINT_HOST, success.provenance.endpointHost)
            assertNull(success.provenance.providerRequests)
            assertTrue(String(success.output).contains("\"summary\":\"fake\""))

            // The escape hatches: without --bare the CLI reads the user's profile, --safe-mode still keeps the tool set closed;
            // an extra argument reaches the CLI.
            val relaxed =
                environment + ("LT_VERDICT_AI_LOCAL_OMIT_BARE" to "1") +
                    ("LT_VERDICT_AI_LOCAL_EXTRA_ARGS" to "--fallback-model=fake-local-stub")
            val second = ModelStudioAdvisoryRunner.fromEnvironment(relaxed, root, config).invoke(evidence)
            assertInstanceOf(RunnerOutcome.Success::class.java, second)
            assertEquals(2, requests)

            // The CLI by command name through PATH, no pin (ADR 0027, amendment): a wrapper named qwen that starts the real entry file.
            val bin = Files.createDirectories(tempDir.resolve("bin"))
            val wrapper = bin.resolve("qwen")
            Files.writeString(wrapper, "#!/bin/bash\nexec node \"${entry.replace('\\', '/')}\" \"\$@\"\n")
            wrapper.toFile().setExecutable(true)
            val byName =
                (environment - "LT_VERDICT_AI_LOCAL_QWEN_SHA256") +
                    mapOf(
                        "LT_VERDICT_AI_LOCAL_QWEN_CMD" to "qwen",
                        "PATH" to bin.toString() + java.io.File.pathSeparator + System.getenv("PATH"),
                    )
            val wrapperSha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(wrapper)).joinToString("") { "%02x".format(it) }
            for (settings in listOf(emptyMap(), mapOf("LT_VERDICT_AI_LOCAL_OMIT_BARE" to "1"))) {
                val outcome = ModelStudioAdvisoryRunner.fromEnvironment(byName + settings, root, config).invoke(evidence)
                // The hash reported is that of the file found on PATH, here the wrapper.
                assertEquals(wrapperSha, assertInstanceOf(RunnerOutcome.Success::class.java, outcome).provenance.runnerArtifactSha256)
            }
            assertEquals(4, requests)
        } finally {
            server.stop(0)
        }
    }
}

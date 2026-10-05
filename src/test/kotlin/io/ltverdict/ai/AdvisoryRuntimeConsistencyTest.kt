package io.ltverdict.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR 0023, D4, item 3: the model slug and the address rules in Kotlin, the launcher, the relay, the Qwen script and
 * the copy in the acceptance runner must not drift apart.
 */
class AdvisoryRuntimeConsistencyTest {
    private val launcher = text("tools/advisory_ai_runtime.ps1")
    private val relay = text("tools/advisory_ai_runtime_relay.mjs")
    private val qwenScript = text("tools/advisory_ai_runtime_qwen.sh")
    private val acceptance = text("tools/advisory_ai_acceptance_runner.ps1")

    @Test
    fun `the built in model and address are the same in every node`() {
        assertEquals(QwenCode0211.MODEL_ID, group(launcher, """\[string\]\${'$'}ModelId = "([^"]+)""""))
        assertEquals(QwenCode0211.PROVIDER_ENDPOINT, group(launcher, """\[string\]\${'$'}UpstreamUrl = "([^"]+)""""))
        assertEquals(QwenCode0211.MODEL_ID, group(relay, """const BUILT_IN_MODEL = "([^"]+)""""))
        assertEquals(QwenCode0211.PROVIDER_ENDPOINT, group(relay, """const BUILT_IN_UPSTREAM = "([^"]+)""""))
        assertEquals(QwenCode0211.MODEL_ID, group(acceptance, """\${'$'}FixedModel = "([^"]+)""""))
        assertEquals(QwenCode0211.PROVIDER_ENDPOINT, group(acceptance, """\${'$'}FixedEndpoint = "([^"]+)""""))
        assertEquals(AiModelsConfig.BUILT_IN.endpointUrl, QwenCode0211.PROVIDER_ENDPOINT)
        assertEquals(listOf(QwenCode0211.MODEL_ID), AiModelsConfig.BUILT_IN.models.map { it.id })
    }

    @Test
    fun `the slug pattern is the same in Kotlin, the launcher, the relay and the Qwen script`() {
        val slugClass = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"
        assertEquals(slugClass, MODEL_SLUG.pattern)
        assertTrue(launcher.contains("[ValidatePattern('^$slugClass\\z')]"), "launcher")
        assertTrue(relay.contains("const MODEL_SLUG = /^$slugClass$/;"), "relay")
        // The shell cannot use the quantifier: it checks the first character, the alphabet and the length.
        assertTrue(qwenScript.contains("[!A-Za-z0-9]*|*[!A-Za-z0-9._:/-]*|*..*|*//*"), "shell")
        assertTrue(qwenScript.contains("-le 128"), "shell length")
        for (node in listOf(launcher, relay)) {
            assertTrue(node.contains("..") && node.contains("//"), "nodes also refuse '..' and '//'")
        }
        assertTrue(launcher.contains("""${'$'}ModelId.Contains("..") -or ${'$'}ModelId.Contains("//")"""))
        assertTrue(relay.contains("""text.includes("..") || text.includes("//")"""))
    }

    @Test
    fun `the characters that are refused in an address are the same everywhere`() {
        assertEquals("\"\\`^|<>{}", URL_UNSAFE_CHARACTERS)
        assertTrue(launcher.contains("""'["\\`^|<>{}]'"""), "launcher")
        assertTrue(relay.contains("""/["\\`^|<>{}]/"""), "relay")
    }

    @Test
    fun `the host part of the reported endpoint host is the same pattern in Kotlin, the launcher and the relay`() {
        val kotlinHost = ENDPOINT_HOST.pattern.substringBeforeLast(":(?:[1-9]")
        assertTrue(relay.contains("const HOST_NAME = /^$kotlinHost${'$'}/;"), "relay")
        assertTrue(launcher.contains("'^$kotlinHost:(?:"), "launcher")
    }

    @Test
    fun `the address form is the same in the launcher and the relay and the expected host agrees with Kotlin`() {
        val relayForm = group(relay, """const UPSTREAM_FORM = /\^(.+)\${'$'}/;""")
        val launcherForm = group(launcher, """'\^\(\?<scheme>https\?\)://\(\?<host>(.+?)\)\(\?::""")
        assertTrue(relayForm.startsWith("""(https?):\/\/(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+)"""))
        assertEquals("""\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9.-]+""", launcherForm)
        assertEquals("gw.internal:8080", endpointHostOf("http://Gw.Internal:8080/v1/chat"))
        assertEquals("models.internal.example:443", endpointHostOf("https://Models.Internal.Example/v1"))
        assertEquals("gw.internal:80", endpointHostOf("http://gw.internal/v1"))
        assertEquals("[::1]:8443", endpointHostOf("https://[::1]:8443/v1"))
        assertEquals("token-plan.ap-southeast-1.maas.aliyuncs.com:443", endpointHostOf(QwenCode0211.PROVIDER_ENDPOINT))
    }

    private fun text(path: String) = Files.readString(Path.of(path)).replace("\r\n", "\n")

    private fun group(
        source: String,
        pattern: String,
    ): String = Regex(pattern).find(source)?.groupValues?.get(1) ?: error("pattern not found: $pattern")
}

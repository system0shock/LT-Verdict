package io.ltverdict.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class AiModelsConfigTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `the ADR example parses with its endpoint label and models`() {
        val config = loaded(parse(config()))

        assertEquals("https://models.internal.example/v1/chat", config.endpointUrl)
        assertEquals("Gateway", config.endpointLabel)
        assertFalse(config.allowInsecureHttp)
        assertEquals("qwen3.8-max", config.defaultModel)
        assertEquals(listOf(AiModel("qwen3.8-max", "Qwen"), AiModel(BUILT_IN_MODEL, "DeepSeek")), config.models)
    }

    @Test
    fun `a file without endpoint uses the built in endpoint and its label`() {
        val config =
            loaded(parse(config(endpoint = null, models = """[{"id":"$BUILT_IN_MODEL","label":"DeepSeek"}]""", default = BUILT_IN_MODEL)))

        assertEquals(AiModelsConfig.BUILT_IN.endpointUrl, config.endpointUrl)
        assertEquals(AiModelsConfig.BUILT_IN.endpointLabel, config.endpointLabel)
        assertFalse(config.allowInsecureHttp)
    }

    @Test
    fun `an endpoint without label has no label and http is allowed only with the flag`() {
        val bare = loaded(parse(config(endpoint = """{"url":"https://gw.example/v1/chat"}""")))
        assertNull(bare.endpointLabel)
        assertFalse(bare.allowInsecureHttp)

        val insecure = loaded(parse(config(endpoint = """{"url":"http://gw.internal:8080/v1/chat","allow_insecure_http":true}""")))
        assertEquals("http://gw.internal:8080/v1/chat", insecure.endpointUrl)
        assertTrue(insecure.allowInsecureHttp)
        loaded(parse(config(endpoint = """{"url":"https://[::1]:8443/v1/chat"}""")))
    }

    @Test
    fun `unknown fields at any level are rejected and the pointer names the parent only`() {
        assertInvalid("UNKNOWN_FIELD", "", parse(config(extra = ""","api_key":"sk-SECRET-1"""")))
        assertInvalid("UNKNOWN_FIELD", "/endpoint", parse(config(endpoint = """{"url":"https://gw.example/v1","api_key":"x"}""")))
        assertInvalid(
            "UNKNOWN_FIELD",
            "/models/0",
            parse(config(models = """[{"id":"qwen3.8-max","label":"Qwen","measured":true}]""")),
        )
    }

    @Test
    fun `duplicate property names are rejected at every level including escaped spellings`() {
        assertInvalid("DUPLICATE_OBJECT_KEY", "", parse(config(extra = ""","default_model":"$BUILT_IN_MODEL"""")))
        assertInvalid("DUPLICATE_OBJECT_KEY", "", parse(config(extra = ""","default_model":"x"""")))
        assertInvalid(
            "DUPLICATE_OBJECT_KEY",
            "/endpoint",
            parse(config(endpoint = """{"url":"https://a.example/v1","url":"https://b.example/v1"}""")),
        )
        assertInvalid(
            "DUPLICATE_OBJECT_KEY",
            "/models/0",
            parse(config(models = """[{"id":"qwen3.8-max","label":"Qwen","id":"other"}]""")),
        )
    }

    @Test
    fun `structure and types are validated`() {
        assertInvalid("MALFORMED_JSON", "", parse("""{"schema_version":"ai-models.v1","""))
        assertInvalid("MALFORMED_JSON", "", parse(config() + "{}"))
        assertInvalid("INVALID_TYPE", "", parse("[]"))
        assertInvalid("INVALID_VALUE", "/schema_version", parse(config().replace("ai-models.v1", "ai-models.v2")))
        assertInvalid("MISSING_FIELD", "/models", parse("""{"schema_version":"ai-models.v1","default_model":"x"}"""))
        assertInvalid("MISSING_FIELD", "/default_model", parse("""{"schema_version":"ai-models.v1","models":[{"id":"a","label":"A"}]}"""))
        assertInvalid("MISSING_FIELD", "/endpoint/url", parse(config(endpoint = """{"label":"x"}""")))
        assertInvalid(
            "INVALID_TYPE",
            "/endpoint/allow_insecure_http",
            parse(config(endpoint = """{"url":"https://a.example/v1","allow_insecure_http":"true"}""")),
        )
        assertInvalid("INVALID_TYPE", "/models", parse(config(models = "{}")))
        assertInvalid("INVALID_TYPE", "/models/0", parse(config(models = "[1]")))
        assertInvalid("INVALID_TYPE", "/models/0/label", parse(config(models = """[{"id":"qwen3.8-max","label":5}]""")))
        assertInvalid("MALFORMED_JSON", "", parse(Char(0xFEFF) + config()))
    }

    @Test
    fun `model lists are bounded unique and contain the default model`() {
        assertInvalid("INVALID_VALUE", "/models", parse(config(models = "[]")))
        val many = (1..33).joinToString(",", "[", "]") { """{"id":"m$it","label":"M $it"}""" }
        assertInvalid("INVALID_VALUE", "/models", parse(config(models = many, default = "m1")))
        val limit = (1..32).joinToString(",", "[", "]") { """{"id":"m$it","label":"M $it"}""" }
        assertEquals(32, loaded(parse(config(models = limit, default = "m32"))).models.size)
        assertInvalid(
            "DUPLICATE_MODEL_ID",
            "/models/1/id",
            parse(config(models = """[{"id":"a","label":"A"},{"id":"a","label":"B"}]""", default = "a")),
        )
        assertInvalid("INVALID_VALUE", "/default_model", parse(config(default = "missing")))
    }

    @Test
    fun `model slugs follow the closed pattern`() {
        for (slug in listOf("a", "A1", "qwen3.8-max", "deepseek-v4-flash-0731", "org/model:v1.2_x", "a".repeat(128), "0abc", "a.b-c")) {
            loaded(parse(config(models = models(slug), default = slug)))
        }
        for (slug in listOf(
            "",
            "-a",
            ".a",
            "_a",
            "/a",
            ":a",
            "a..b",
            "a//b",
            "a b",
            "a;b",
            "a\$b",
            "a\nb",
            "a\n",
            "a".repeat(129),
            "модель",
            "a|b",
            "a`b",
            "a\\b",
        )) {
            assertInvalid("INVALID_VALUE", "/models/0/id", parse(config(models = models(slug), default = "x")), slug)
        }
    }

    @Test
    fun `labels are one to eighty visible characters`() {
        loaded(parse(config(models = models("a", label = "Модель №1"), default = "a")))
        loaded(parse(config(models = models("a", label = "x".repeat(80)), default = "a")))
        for (label in listOf("", "   ", "x".repeat(81), "a\u0001b", "a\nb", "a\u202Eb", "a\u2028b")) {
            assertInvalid("INVALID_VALUE", "/models/0/label", parse(config(models = models("a", label = label), default = "a")), label)
        }
        assertInvalid("INVALID_VALUE", "/endpoint/label", parse(config(endpoint = """{"url":"https://a.example/v1","label":""}""")))
        assertInvalid("INVALID_VALUE", "/endpoint/label", parse(config(endpoint = """{"url":"https://a.example/v1","label":"a\u0007"}""")))
    }

    @Test
    fun `endpoint url rules`() {
        val rejected =
            listOf(
                "http://gw.example/v1",
                "ftp://gw.example/v1",
                "HTTPS://gw.example/v1",
                "https://user@gw.example/v1",
                "https://user:pass@gw.example/v1",
                "https://@gw.example/v1",
                "https://gw.example/v1?x=1",
                "https://gw.example/v1?",
                "https://gw.example/v1#frag",
                "https://gw.example/v1#",
                "https:///v1",
                "https://",
                "gw.example/v1",
                "/v1/chat",
                "https://gw.example/a b",
                "https://gw.example/é",
                "https://gw.example\\v1",
                "https://gw.example:0/v1",
                "https://gw.example:99999/v1",
                "https://gw_bad.example/v1",
                "https:gw.example",
                "",
                "https://gw.example/" + "a".repeat(500),
            )
        for (url in rejected) {
            assertInvalid("INVALID_URL", "/endpoint/url", parse(config(endpoint = """{"url":${JsonPrimitive(url)}}""")), url)
        }
        assertInvalid(
            "INVALID_URL",
            "/endpoint/url",
            parse(config(endpoint = """{"url":"http://user@gw.internal/v1","allow_insecure_http":true}""")),
        )
        val atLimit = "https://gw.example/" + "a".repeat(512 - "https://gw.example/".length)
        assertEquals(512, atLimit.length)
        loaded(parse(config(endpoint = """{"url":"$atLimit"}""")))
    }

    @Test
    fun `file size and encoding are bounded`() {
        val padded = config().replace("{", "{" + " ".repeat(65_536), ignoreCase = false)
        assertInvalid("RESOURCE_LIMIT_EXCEEDED", "", parseAiModelsConfig(padded.encodeToByteArray()))
        assertInvalid("INVALID_UTF8", "", parseAiModelsConfig(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)))
        loaded(parseAiModelsConfig(config().encodeToByteArray()))
    }

    @Test
    fun `pointers never carry file supplied key names`() {
        val unknown = parse("""{"sk-live-123":1,"schema_version":"ai-models.v1"}""")
        assertInvalid("UNKNOWN_FIELD", "", unknown)

        val malformed = parse("""{"sk-live-123":{"inner-secret":[1,}}""")
        val invalid = assertInstanceOf(AiModelsConfigLoad.Invalid::class.java, malformed)
        assertEquals("MALFORMED_JSON", invalid.code)
        assertFalse(invalid.pointer.contains("sk-live"))
        assertFalse(invalid.pointer.contains("inner-secret"))

        val numeric = assertInstanceOf(AiModelsConfigLoad.Invalid::class.java, parse("""{"123456":[1,]}"""))
        assertEquals("MALFORMED_JSON", numeric.code)
        assertFalse(numeric.pointer.contains("123456"), numeric.pointer)

        val modelIndex =
            assertInstanceOf(AiModelsConfigLoad.Invalid::class.java, parse(config(models = """[{"id":"a","label":"A","id":"b"}]""")))
        assertEquals("/models/0", modelIndex.pointer)

        val nested = parse("""{"schema_version":"ai-models.v1","sk-live-123":{"a":1,"a":2}}""")
        val nestedInvalid = assertInstanceOf(AiModelsConfigLoad.Invalid::class.java, nested)
        assertFalse(nestedInvalid.pointer.contains("sk-live"))
    }

    @Test
    fun `contract examples agree with the loader`() {
        val root = Path.of("docs/contracts/advice/v1/examples/ai-models")
        for ((directory, accepted) in listOf("valid" to true, "invalid" to false, "runtime-only" to false)) {
            val files = Files.list(root.resolve(directory)).use { it.sorted().toList() }
            assertTrue(files.isNotEmpty(), directory)
            for (file in files) {
                val result = parseAiModelsConfig(Files.readAllBytes(file))
                assertEquals(accepted, result is AiModelsConfigLoad.Loaded, "$file -> $result")
            }
        }
    }

    @Test
    fun `without the variable the built in configuration applies`() {
        assertEquals(AiModelsConfigLoad.Loaded(AiModelsConfig.BUILT_IN), loadAiModelsConfig(emptyMap()))
        val builtIn = AiModelsConfig.BUILT_IN
        assertEquals(QwenCode0211.PROVIDER_ENDPOINT, builtIn.endpointUrl)
        assertEquals(QwenCode0211.MODEL_ID, builtIn.defaultModel)
        assertEquals(listOf(QwenCode0211.MODEL_ID), builtIn.models.map { it.id })
        assertEquals("Alibaba ModelStudio (Singapore)", builtIn.endpointLabel)
    }

    @Test
    fun `a set but unusable variable is invalid and never falls back silently`() {
        assertInvalid("FILE_PATH_EMPTY", "", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to "")))
        assertInvalid("FILE_PATH_EMPTY", "", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to "   ")))
        assertInvalid("FILE_PATH_NOT_ABSOLUTE", "", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to "relative/ai-models.json")))
        assertInvalid(
            "FILE_NOT_READABLE",
            "",
            loadAiModelsConfig(
                mapOf(
                    MODELS_FILE_ENVIRONMENT to tempDir.resolve("missing.json").toString(),
                ),
            ),
        )
        assertInvalid("FILE_NOT_READABLE", "", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to tempDir.toString())))
        val huge = Files.write(tempDir.resolve("huge.json"), ByteArray(65_537) { ' '.code.toByte() })
        assertInvalid("RESOURCE_LIMIT_EXCEEDED", "", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to huge.toString())))
    }

    @Test
    fun `a file matching the built in destination loads and keeps measured true`() {
        val file =
            write(
                config(
                    endpoint = """{"url":"${AiModelsConfig.BUILT_IN.endpointUrl}","label":"Gateway"}""",
                    models = """[{"id":"$BUILT_IN_MODEL","label":"Custom label"}]""",
                    default = BUILT_IN_MODEL,
                ),
            )

        val config = loaded(loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to file.toString())))

        assertEquals("Gateway", config.endpointLabel)
        val model =
            config
                .bootstrapJson()
                .getValue("models")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("Custom label", model.getValue("label").jsonPrimitive.content)
        assertTrue(model.getValue("measured").jsonPrimitive.boolean)
    }

    @Test
    fun `before model selection a different endpoint or slug is refused with its own code after structural checks`() {
        val adr = write(config())
        assertInvalid("NOT_YET_SUPPORTED", "/endpoint/url", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to adr.toString())))

        val insecure = write(config(endpoint = """{"url":"http://gw.internal:8080/v1/chat","allow_insecure_http":true}"""))
        assertInvalid("NOT_YET_SUPPORTED", "/endpoint/url", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to insecure.toString())))

        val novelSlug =
            write(
                config(
                    endpoint = """{"url":"${AiModelsConfig.BUILT_IN.endpointUrl}"}""",
                    models = """[{"id":"$BUILT_IN_MODEL","label":"D"},{"id":"qwen3.8-max","label":"Q"}]""",
                    default = BUILT_IN_MODEL,
                ),
            )
        assertInvalid("NOT_YET_SUPPORTED", "/models/1/id", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to novelSlug.toString())))

        val structural = write(config(endpoint = """{"url":"https://user@gw.example/v1"}"""))
        assertInvalid("INVALID_URL", "/endpoint/url", loadAiModelsConfig(mapOf(MODELS_FILE_ENVIRONMENT to structural.toString())))
    }

    @Test
    fun `measured is true only for the experiment slug on the built in endpoint`() {
        assertTrue(aiModelMeasured(BUILT_IN_MODEL, QwenCode0211.PROVIDER_ENDPOINT))
        assertFalse(aiModelMeasured(BUILT_IN_MODEL, "https://gw.internal.example/v1/chat/completions"))
        assertFalse(aiModelMeasured("qwen3.8-max", QwenCode0211.PROVIDER_ENDPOINT))
        assertFalse(aiModelMeasured("qwen3.8-max", "https://gw.internal.example/v1/chat/completions"))
    }

    @Test
    fun `bootstrap value exposes labels and measured but never the endpoint address`() {
        val config =
            AiModelsConfig(
                endpointUrl = "https://gw.internal.example/v1/chat/completions",
                endpointLabel = "Gateway",
                allowInsecureHttp = false,
                defaultModel = "qwen3.8-max",
                models = listOf(AiModel("qwen3.8-max", "Qwen"), AiModel(BUILT_IN_MODEL, "DeepSeek")),
            )

        val json = config.bootstrapJson()

        assertEquals(setOf("default_model_id", "endpoint_label", "models"), json.keys)
        assertEquals("qwen3.8-max", json.getValue("default_model_id").jsonPrimitive.content)
        assertEquals("Gateway", json.getValue("endpoint_label").jsonPrimitive.content)
        val models = json.getValue("models").jsonArray.map { it.jsonObject }
        assertEquals(listOf("qwen3.8-max", BUILT_IN_MODEL), models.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf(false, false), models.map { it.getValue("measured").jsonPrimitive.boolean })
        assertEquals(setOf("id", "label", "measured"), models.first().keys)
        assertFalse(json.toString().contains("gw.internal"))
        assertEquals(JsonNull, config.copy(endpointLabel = null).bootstrapJson().getValue("endpoint_label"))

        val builtIn = AiModelsConfig.BUILT_IN.bootstrapJson()
        assertTrue(
            builtIn
                .getValue("models")
                .jsonArray
                .single()
                .jsonObject
                .getValue("measured")
                .jsonPrimitive.boolean,
        )
        assertFalse(builtIn.toString().contains("https://"))
    }

    @Test
    fun `an invalid file makes advisory AI unavailable before the credential is considered and keeps stderr clean`() {
        val file = write(config(extra = ""","api_key":"sk-SECRET-1""""))
        val stderr = ByteArrayOutputStream()

        val setup = advisoryAiSetup(mapOf(MODELS_FILE_ENVIRONMENT to file.toString()), stderr = PrintStream(stderr, true, "UTF-8"))

        assertNull(setup.models)
        assertEquals(RunnerOutcome.Unavailable(AdviceUnavailableReason.MODEL_CONFIG_INVALID), setup.runner.invoke(EVIDENCE))
        val text = stderr.toString("UTF-8")
        assertTrue(text.contains("MODEL_CONFIG_INVALID"), text)
        assertTrue(text.contains("UNKNOWN_FIELD"), text)
        assertFalse(text.contains("api_key"), text)
        assertFalse(text.contains("sk-SECRET"), text)
        assertFalse(text.contains(file.toString()), text)
    }

    @Test
    fun `a missing file is reported without aborting and a missing credential stays a job time reason`() {
        val stderr = ByteArrayOutputStream()
        val missing =
            advisoryAiSetup(
                mapOf(MODELS_FILE_ENVIRONMENT to tempDir.resolve("none.json").toString()),
                stderr = PrintStream(stderr, true, "UTF-8"),
            )
        assertNull(missing.models)
        assertEquals(RunnerOutcome.Unavailable(AdviceUnavailableReason.MODEL_CONFIG_INVALID), missing.runner.invoke(EVIDENCE))
        assertTrue(stderr.toString("UTF-8").contains("FILE_NOT_READABLE"))

        val quiet = ByteArrayOutputStream()
        val builtIn = advisoryAiSetup(emptyMap(), stderr = PrintStream(quiet, true, "UTF-8"))
        assertEquals(AiModelsConfig.BUILT_IN, builtIn.models)
        assertEquals(RunnerOutcome.Unavailable(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED), builtIn.runner.invoke(EVIDENCE))
        assertEquals("", quiet.toString("UTF-8"))
    }

    private fun parse(text: String) = parseAiModelsConfig(text.encodeToByteArray())

    private fun loaded(result: AiModelsConfigLoad): AiModelsConfig =
        assertInstanceOf(AiModelsConfigLoad.Loaded::class.java, result, result.toString()).config

    private fun assertInvalid(
        code: String,
        pointer: String,
        result: AiModelsConfigLoad,
        context: String = "",
    ) {
        val invalid = assertInstanceOf(AiModelsConfigLoad.Invalid::class.java, result, "$context -> $result")
        assertEquals(code to pointer, invalid.code to invalid.pointer, context)
    }

    private fun write(text: String): Path = Files.writeString(Files.createTempFile(tempDir, "ai-models", ".json"), text)

    private fun config(
        endpoint: String? = """{"url":"https://models.internal.example/v1/chat","label":"Gateway","allow_insecure_http":false}""",
        models: String = """[{"id":"qwen3.8-max","label":"Qwen"},{"id":"$BUILT_IN_MODEL","label":"DeepSeek"}]""",
        default: String = "qwen3.8-max",
        extra: String = "",
    ): String =
        buildString {
            append("""{"schema_version":"ai-models.v1",""")
            if (endpoint != null) append(""""endpoint":$endpoint,""")
            append(""""default_model":${JsonPrimitive(default)},"models":$models""")
            append(extra)
            append("}")
        }

    private fun models(
        id: String,
        label: String = "Label",
    ) = """[{"id":${JsonPrimitive(id)},"label":${JsonPrimitive(label)}}]"""

    private companion object {
        const val BUILT_IN_MODEL = "deepseek-v4-flash-0731"
        val EVIDENCE = AdvisoryEvidence("{}".encodeToByteArray(), "0".repeat(64), emptySet())
    }
}

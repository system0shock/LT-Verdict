package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class PolicyTest {
    @Test
    fun `contract examples keep independent schema and runtime expectations`() {
        val manifest =
            Json
                .parseToJsonElement(Files.readString(Path.of("fixtures/slice1/manifest.json")))
                .jsonObject
        val recorded =
            manifest.getValue("policy_examples").jsonArray.associate { example ->
                val item = example.jsonObject
                item.getValue("path").jsonPrimitive.content to item
            }

        contractExamples.forEach { (path, expected) ->
            val item = recorded.getValue(path)
            assertEquals(expected.schemaValid, item.getValue("schema_valid").jsonPrimitive.boolean, path)
            assertEquals(expected.runtimeValid, item.getValue("runtime_valid").jsonPrimitive.boolean, path)

            when (val result = Files.newInputStream(Path.of(path)).use { validatePolicy(it) }) {
                is PolicyValidation.Valid -> assertTrue(expected.runtimeValid, "$path unexpectedly valid")
                is PolicyValidation.Invalid -> {
                    assertTrue(!expected.runtimeValid, "$path unexpectedly invalid: ${result.errors}")
                    assertEquals(expected.code, result.errors.first().code, path)
                    assertEquals(expected.pointer, result.errors.first().jsonPointer, path)
                }
            }
        }
    }

    @Test
    fun `defaults and rule minimum parse and an old file keeps its shape`() {
        val old = validatePolicy(ByteArrayInputStream(validPolicy().encodeToByteArray())) as PolicyValidation.Valid
        assertEquals(null, old.policy.defaults)
        assertEquals(
            null,
            old.policy.rules
                .single()
                .minSamples,
        )

        val source =
            """{"schema_version":"policy.v1","policy_id":"p","defaults":{"sample_floor":10,"min_samples":50},"rules":[""" +
                """{"id":"r","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"},"min_samples":200}]}"""
        val valid = validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Valid

        assertEquals(PolicyDefaultsV1(sampleFloor = 10, minSamples = 50), valid.policy.defaults)
        assertEquals(
            200L,
            valid.policy.rules
                .single()
                .minSamples,
        )
    }

    @Test
    fun `window ids parse and malformed lists fail at the field`() {
        val valid =
            validatePolicy(
                ByteArrayInputStream(policyJson(ruleExtra = ""","window_ids":["steady-1","steady-2"]""").encodeToByteArray()),
            ) as PolicyValidation.Valid
        assertEquals(
            listOf("steady-1", "steady-2"),
            valid.policy.rules
                .single()
                .windowIds,
        )

        listOf(
            ",\"window_ids\":[]" to "empty",
            ",\"window_ids\":[\"a\",\"a\"]" to "duplicate",
            ",\"window_ids\":[\"\"]" to "empty id",
            ",\"window_ids\":[1]" to "not a string",
            ",\"window_ids\":\"a\"" to "not an array",
            ",\"window_ids\":[\"${"é".repeat(65)}\"]" to "too long",
        ).forEach { (extra, name) ->
            assertInvalid(policyJson(ruleExtra = extra).encodeToByteArray(), "WINDOW_IDS_INVALID", "/rules/0/window_ids", message = name)
        }
    }

    @Test
    fun `sample limits fail closed at their exact fields`() {
        listOf(
            Case("floor zero", policyJson(defaults = """{"sample_floor":0}"""), "/defaults/sample_floor") to "MIN_SAMPLES_OUT_OF_RANGE",
            Case("minimum too large", policyJson(defaults = """{"min_samples":1000001}"""), "/defaults/min_samples") to
                "MIN_SAMPLES_OUT_OF_RANGE",
            Case("fraction", policyJson(defaults = """{"sample_floor":1.5}"""), "/defaults/sample_floor") to "INVALID_TYPE",
            Case("unknown defaults field", policyJson(defaults = """{"x":1}"""), "/defaults/x") to "UNKNOWN_FIELD",
            Case(
                "minimum below explicit floor",
                policyJson(defaults = """{"sample_floor":50,"min_samples":20}"""),
                "/defaults/min_samples",
            ) to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case("floor above the default minimum", policyJson(defaults = """{"sample_floor":150}"""), "/defaults/sample_floor") to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case("rule minimum below the default floor", policyJson(ruleExtra = ""","min_samples":10"""), "/rules/0/min_samples") to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case(
                "throughput has no minimum",
                policyJson(ruleExtra = ""","min_samples":50""", metric = "throughput_rps", operator = "gte"),
                "/rules/0/min_samples",
            ) to "FIELD_NOT_APPLICABLE",
        ).forEach { (case, code) ->
            assertInvalid(case.source.encodeToByteArray(), code, case.pointer, message = case.name)
        }
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(policyJson(defaults = """{"sample_floor":1,"min_samples":1}""").encodeToByteArray()),
            ) is PolicyValidation.Valid,
        )
    }

    @Test
    fun `trust boundary rejects oversized input and malformed UTF-8`() {
        val valid = validPolicy().encodeToByteArray()
        assertInvalid(valid, "RESOURCE_LIMIT_EXCEEDED", "", maxBytes = valid.size - 1)
        assertInvalid(byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28, 0x22, 0x7d), "INVALID_UTF8", "")
    }

    @Test
    fun `lexical scan rejects duplicate keys including escaped equivalents`() {
        val topLevel = validPolicy().replace("\"policy_id\":\"p\"", "\"policy_id\":\"p\",\"\\u0070olicy_id\":\"q\"")
        val nested = validPolicy().replace("\"kind\":\"overall\"", "\"kind\":\"overall\",\"\\u006b\\u0069\\u006e\\u0064\":\"overall\"")

        assertInvalid(topLevel.encodeToByteArray(), "DUPLICATE_OBJECT_KEY", "/policy_id")
        assertInvalid(nested.encodeToByteArray(), "DUPLICATE_OBJECT_KEY", "/rules/0/scope/kind")
    }

    @Test
    fun `resource ceilings fail closed at their exact fields`() {
        val tooManyRules = (0..256).joinToString(",") { rule("r$it") }
        val cases =
            listOf(
                Case("depth", "[".repeat(17) + "0" + "]".repeat(17), ""),
                Case("rules", policyWithRules(tooManyRules), "/rules"),
                Case("policy id", validPolicy(policyId = "é".repeat(65)), "/policy_id"),
                Case("rule id", policyWithRules(rule("é".repeat(65))), "/rules/0/id"),
                Case(
                    "scope name",
                    policyWithRules(rule("r", scope = """{"kind":"transaction","name":"${"a".repeat(4097)}"}""")),
                    "/rules/0/scope/name",
                ),
                Case("numeric token", policyWithRules(rule("r", threshold = "1" + "0".repeat(64))), "/rules/0/threshold"),
                Case("numeric exponent", policyWithRules(rule("r", threshold = "1e65")), "/rules/0/threshold"),
                Case("huge exponent", policyWithRules(rule("r", threshold = "1e2147483647")), "/rules/0/threshold"),
            )
        cases.forEach { case ->
            assertInvalid(case.source.encodeToByteArray(), "RESOURCE_LIMIT_EXCEEDED", case.pointer, message = case.name)
        }
    }

    @Test
    fun `resource ceilings accept their exact boundary`() {
        val valid = validPolicy().encodeToByteArray()
        assertTrue(validatePolicy(ByteArrayInputStream(valid), valid.size) is PolicyValidation.Valid)
        assertEquals("INVALID_TYPE", invalidCode("[".repeat(16) + "0" + "]".repeat(16)))
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(
                    policyWithRules(
                        (0 until 256).joinToString(",") {
                            rule("r$it")
                        },
                    ).encodeToByteArray(),
                ),
            ) is PolicyValidation.Valid,
        )
        assertTrue(validatePolicy(ByteArrayInputStream(validPolicy("a".repeat(128)).encodeToByteArray())) is PolicyValidation.Valid)
        assertTrue(validatePolicy(ByteArrayInputStream(validPolicy("é".repeat(64)).encodeToByteArray())) is PolicyValidation.Valid)
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(
                    policyWithRules(rule("r", scope = """{"kind":"transaction","name":"${"a".repeat(4096)}"}""")).encodeToByteArray(),
                ),
            ) is PolicyValidation.Valid,
        )
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(policyWithRules(rule("r", threshold = "1" + "0".repeat(63))).encodeToByteArray()),
            ) is PolicyValidation.Valid,
        )
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(policyWithRules(rule("r", threshold = "1e64")).encodeToByteArray()),
            ) is PolicyValidation.Valid,
        )
    }

    private fun assertInvalid(
        bytes: ByteArray,
        code: String,
        pointer: String,
        maxBytes: Int = 1_048_576,
        message: String? = null,
    ) {
        val result = validatePolicy(ByteArrayInputStream(bytes), maxBytes) as PolicyValidation.Invalid
        assertEquals(code, result.errors.first().code, message)
        assertEquals(pointer, result.errors.first().jsonPointer, message)
    }

    private fun invalidCode(source: String): String =
        (validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Invalid).errors.first().code

    private fun validPolicy(policyId: String = "p") = policyWithRules(rule("r"), policyId)

    private fun policyWithRules(
        rules: String,
        policyId: String = "p",
    ) = """{"schema_version":"policy.v1","policy_id":"$policyId","rules":[$rules]}"""

    private fun policyJson(
        defaults: String? = null,
        ruleExtra: String = "",
        metric: String = "response_time_p95_ms",
        operator: String = "lte",
    ) = buildString {
        append("""{"schema_version":"policy.v1","policy_id":"p",""")
        if (defaults != null) append(""""defaults":$defaults,""")
        append(""""rules":[{"id":"r","metric":"$metric","operator":"$operator","threshold":1,"scope":{"kind":"overall"}$ruleExtra}]}""")
    }

    private fun rule(
        id: String,
        threshold: String = "100",
        scope: String = """{"kind":"overall"}""",
    ) = """{"id":"$id","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,"scope":$scope}"""

    private data class Case(
        val name: String,
        val source: String,
        val pointer: String,
    )

    private data class Expectation(
        val schemaValid: Boolean,
        val runtimeValid: Boolean,
        val code: String? = null,
        val pointer: String? = null,
    )

    private companion object {
        val contractExamples =
            mapOf(
                "docs/contracts/policy/v1/examples/valid/all-metrics.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/valid/sample-gate.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/valid/window-ids.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/invalid/window-ids-empty.json" to
                    Expectation(false, false, "WINDOW_IDS_INVALID", "/rules/0/window_ids"),
                "docs/contracts/policy/v1/examples/invalid/empty-rules.json" to Expectation(false, false, "EMPTY_RULES", "/rules"),
                "docs/contracts/policy/v1/examples/valid/platform-services.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/valid/platform-gap-tolerance.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/valid/platform-base-profile.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/invalid/platform-tolerance-diagnostic.json" to
                    Expectation(true, false, "FIELD_NOT_APPLICABLE", "/platform_rules/0/max_gap_cells"),
                "docs/contracts/policy/v1/examples/invalid/platform-except-outside-catalog.json" to
                    Expectation(true, false, "INVALID_SCOPE", "/platform_rules/0/scope/except/0"),
                "docs/contracts/policy/v1/examples/invalid/platform-all-services-without-catalog.json" to
                    Expectation(true, false, "INVALID_SCOPE", "/platform_rules/0/scope"),
                "docs/contracts/policy/v1/examples/invalid/platform-coverage-missing.json" to
                    Expectation(true, false, "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
                "docs/contracts/policy/v1/examples/invalid/min-samples-below-floor.json" to
                    Expectation(true, false, "MIN_SAMPLES_BELOW_FLOOR", "/rules/0/min_samples"),
                "docs/contracts/policy/v1/examples/invalid/duplicate-rule-id.json" to
                    Expectation(true, false, "DUPLICATE_RULE_ID", "/rules/1/id"),
                "docs/contracts/policy/v1/examples/invalid/unknown-field.json" to
                    Expectation(false, false, "UNKNOWN_FIELD", "/rules/0/unit"),
                "docs/contracts/policy/v1/examples/invalid/unknown-metric.json" to
                    Expectation(false, false, "UNKNOWN_METRIC", "/rules/0/metric"),
                "docs/contracts/policy/v1/examples/invalid/wrong-operator.json" to
                    Expectation(false, false, "METRIC_OPERATOR_MISMATCH", "/rules/0/operator"),
                "docs/contracts/policy/v1/examples/invalid/error-rate-out-of-range.json" to
                    Expectation(false, false, "THRESHOLD_OUT_OF_RANGE", "/rules/0/threshold"),
            )
    }
}

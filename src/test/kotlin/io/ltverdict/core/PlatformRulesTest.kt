package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class PlatformRulesTest {
    @Test
    fun `platform sections fail closed at their exact fields`() {
        listOf(
            Triple(policyJson(catalog = ""), "INVALID_SCOPE", "/platform_rules/0/scope"),
            Triple(policyJson(cpuScope = """{"kind":"all_services","except":["ghost"]}"""), "INVALID_SCOPE", "/platform_rules/0/scope/except/0"),
            Triple(policyJson(coverage = ""), "MISSING_FIELD", "/platform_coverage"),
            Triple(policyJson(coverScope = """{"kind":"service","services":["orders"]}"""), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverMin = 2), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverThreshold = "1"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverAggregation = "interval_mean"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverExtra = ""","window_ids":["w1"]"""), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(cpuScope = """{"kind":"all_services","except":["orders","payments"]}"""), "INVALID_SCOPE", "/platform_rules/0/scope"),
            Triple(policyJson(cpuThreshold = "0", cpuAggregation = "interval_min"), "PLATFORM_AGGREGATION_OPERATOR_MISMATCH", "/platform_rules/0/aggregation"),
            Triple(policyJson(coverId = "p95"), "DUPLICATE_RULE_ID", "/platform_rules/1/id"),
            Triple(policyJson(extraRuleId = "cpu/orders"), "DUPLICATE_RULE_ID", "/platform_rules/0/id"),
            Triple(policyJson(cpuAggregation = "weekly"), "UNKNOWN_AGGREGATION", "/platform_rules/0/aggregation"),
            Triple(policyJson(cpuExtra = ""","window_ids":[]"""), "WINDOW_IDS_INVALID", "/platform_rules/0/window_ids"),
            Triple(policyJson(cpuId = "c".repeat(125)), "RESOURCE_LIMIT_EXCEEDED", "/platform_rules/0/id"),
            Triple(policyJson(cpuScope = """{"kind":"service","services":[]}"""), "INVALID_SCOPE", "/platform_rules/0/scope/services"),
            Triple(policyJson(cpuScope = """{"kind":"region"}"""), "INVALID_SCOPE", "/platform_rules/0/scope/kind"),
            Triple(policyJson(cpuMinimum = "0"), "INVALID_MINIMUM", "/platform_rules/0/min_consecutive_cells"),
            Triple(policyJson(cpuEffect = "block"), "UNKNOWN_EFFECT", "/platform_rules/0/effect"),
        ).forEach { (source, code, pointer) ->
            val errors = (validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Invalid).errors
            assertEquals(code to pointer, errors.first().code to errors.first().jsonPointer, source)
        }
    }

    @Test
    fun `a complete platform policy parses and resolves its services`() {
        val policy = policy(policyJson(cpuScope = """{"kind":"all_services","except":["payments"]}"""))

        assertEquals(listOf("orders", "payments"), policy.platformServices)
        assertEquals(listOf("orders"), resolveServices(policy.platformRules[0].scope, policy.platformServices))
        assertEquals(listOf("orders", "payments"), resolveServices(policy.platformRules[1].scope, policy.platformServices))
        assertEquals("unavailable", policy.platformCoverage?.signal)
        assertTrue(policy.platformRules.all { it.effect == ResourceRuleEffect.SLA })
    }

    @Test
    fun `coverage for a window needs a coverage rule that includes that window`() {
        val both = policyJson(cpuExtra = ""","window_ids":["w1"]""", coverExtra = ""","window_ids":["w1","w2"]""")
        val missing = policyJson(cpuExtra = ""","window_ids":["w2"]""", coverExtra = ""","window_ids":["w1"]""")

        assertTrue(validatePolicy(ByteArrayInputStream(both.encodeToByteArray())) is PolicyValidation.Valid)
        assertEquals(
            "PLATFORM_COVERAGE_MISSING",
            (validatePolicy(ByteArrayInputStream(missing.encodeToByteArray())) as PolicyValidation.Invalid).errors.first().code,
        )
    }

    @Test
    fun `diagnostic platform rules need no coverage signal`() {
        val source = policyJson(coverage = "", cpuEffect = "diagnostic", coverEffect = "diagnostic")

        assertTrue(validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) is PolicyValidation.Valid)
    }

    @Test
    fun `platform aggregation values in the schema match the runtime enum`() {
        val schema = Json.parseToJsonElement(Files.readString(Path.of("docs/contracts/policy/v1/policy.schema.json"))).jsonObject
        val values =
            schema
                .getValue("\$defs")
                .jsonObject
                .getValue("platform_rule")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("aggregation")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }

        assertEquals(ResourceAggregation.entries.map { it.wireName }.toSet(), values.toSet())
    }

    private fun policy(json: String = policyJson()) = (validatePolicy(ByteArrayInputStream(json.encodeToByteArray())) as PolicyValidation.Valid).policy

    private fun policyJson(
        catalog: String = """"platform_services":["orders","payments"],""",
        coverage: String = """"platform_coverage":{"signal":"unavailable"},""",
        cpuScope: String = """{"kind":"all_services"}""",
        coverScope: String = """{"kind":"all_services"}""",
        coverMin: Int = 1,
        coverId: String = "cover",
        cpuId: String = "cpu",
        cpuAggregation: String = "interval_mean",
        cpuThreshold: String = "0.4",
        cpuMinimum: String = "2",
        cpuExtra: String = "",
        cpuEffect: String = "sla",
        coverEffect: String = "sla",
        coverThreshold: String = "0",
        coverAggregation: String = "interval_max",
        coverExtra: String = "",
        extraRuleId: String? = null,
    ): String {
        val extraRule =
            extraRuleId?.let {
                """,{"id":"$it","metric":"error_rate_ratio","operator":"lte","threshold":1,"scope":{"kind":"overall"}}"""
            }.orEmpty()
        return """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
            catalog +
            coverage +
            """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"}}$extraRule],""" +
            """"platform_rules":[""" +
            """{"id":"$cpuId","signal":"cpu_ratio","scope":$cpuScope,"operator":"gt","threshold":$cpuThreshold,"unit":"ratio",""" +
            """"aggregation":"$cpuAggregation","min_consecutive_cells":$cpuMinimum,"effect":"$cpuEffect"$cpuExtra},""" +
            """{"id":"$coverId","signal":"unavailable","scope":$coverScope,"operator":"gt","threshold":$coverThreshold,"unit":"count",""" +
            """"aggregation":"$coverAggregation","min_consecutive_cells":$coverMin,"effect":"$coverEffect"$coverExtra}]}"""
    }
}

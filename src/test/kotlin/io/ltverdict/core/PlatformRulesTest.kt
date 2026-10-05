package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PlatformRulesTest {
    @Test
    fun `platform sections fail closed at their exact fields`() {
        listOf(
            Triple(policyJson(catalog = ""), "INVALID_SCOPE", "/platform_rules/0/scope"),
            Triple(
                policyJson(cpuScope = """{"kind":"all_services","except":["ghost"]}"""),
                "INVALID_SCOPE",
                "/platform_rules/0/scope/except/0",
            ),
            Triple(policyJson(coverage = ""), "MISSING_FIELD", "/platform_coverage"),
            Triple(
                policyJson(coverScope = """{"kind":"service","services":["orders"]}"""),
                "PLATFORM_COVERAGE_MISSING",
                "/platform_coverage",
            ),
            Triple(policyJson(coverMin = 2), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverThreshold = "1"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverAggregation = "interval_mean"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverExtra = ""","window_ids":["w1"]"""), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(
                policyJson(cpuScope = """{"kind":"all_services","except":["orders","payments"]}"""),
                "INVALID_SCOPE",
                "/platform_rules/0/scope",
            ),
            Triple(
                policyJson(cpuThreshold = "0", cpuAggregation = "interval_min"),
                "PLATFORM_AGGREGATION_OPERATOR_MISMATCH",
                "/platform_rules/0/aggregation",
            ),
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

    @Test
    fun `every expected service gets a check and one violating service fails the window`() {
        val orders = healthy("orders", cpu = listOf("0.5", "0.5", "0.1", "0.1"))
        val snapshot = snapshot(orders + healthy("payments"))

        val result = evaluate(snapshot)

        assertEquals(PolicyVerdict.FAIL, result.windowVerdicts.getValue("steady"))
        assertEquals(
            listOf("cpu/orders" to "FAIL", "cpu/payments" to "PASS", "cover/orders" to "PASS", "cover/payments" to "PASS"),
            result.checks().map { it.str("rule_id") to it.str("status") },
        )
        assertEquals(listOf("orders", "payments", "orders", "payments"), result.checks().map { it.str("service") })
        assertEquals(listOf("cpu", "cpu", "cover", "cover"), result.checks().map { it.str("platform_rule_id") })
    }

    @Test
    fun `a missing duplicate or foreign series never passes silently`() {
        val missing = evaluate(snapshot(healthy("orders")))
        val ambiguous =
            evaluate(
                snapshot(
                    healthy("orders") + healthy("payments") + series("cpu-orders-2", "cpu_ratio", "orders", "ratio", List(4) { "0.1" }),
                ),
            )
        val wrongUnit =
            evaluate(
                snapshot(
                    healthy("orders") +
                        listOf(series("cpu-payments", "cpu_ratio", "payments", "percent", List(4) { "10" }), healthy("payments")[1]),
                ),
            )
        val wrongAggregation =
            evaluate(
                snapshot(
                    healthy("orders") +
                        listOf(
                            series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }, ResourceAggregation.INTERVAL_RATE),
                            healthy("payments")[1],
                        ),
                ),
            )
        val generatorOnly =
            evaluate(
                snapshot(
                    healthy("orders") +
                        listOf(
                            series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }, role = ResourceRole.GENERATOR),
                            healthy("payments")[1],
                        ),
                ),
            )

        listOf(
            missing to "RESOURCE_SERIES_NOT_FOUND",
            ambiguous to "PLATFORM_SERIES_AMBIGUOUS",
            wrongUnit to "PLATFORM_UNIT_MISMATCH",
            wrongAggregation to "PLATFORM_AGGREGATION_MISMATCH",
            generatorOnly to "RESOURCE_SERIES_NOT_FOUND",
        ).forEach { (result, reason) ->
            assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"), reason)
            assertTrue(reason in result.coverageReasons, reason)
        }
    }

    @Test
    fun `except removes a service from one rule while coverage still applies to it`() {
        val policy = policy(policyJson(cpuScope = """{"kind":"all_services","except":["payments"]}"""))
        val snapshot =
            snapshot(
                healthy("orders") +
                    listOf(
                        series(
                            "unavailable-payments",
                            "unavailable",
                            "payments",
                            "count",
                            List(4) { "0" },
                            ResourceAggregation.INTERVAL_MAX,
                        ),
                    ),
            )

        val result = evaluate(snapshot, policy)

        assertEquals(PolicyVerdict.PASS, result.windowVerdicts.getValue("steady"))
        assertEquals(listOf("cpu/orders", "cover/orders", "cover/payments"), result.checks().map { it.str("rule_id") })
    }

    @Test
    fun `a window shorter than the required series cannot pass`() {
        val result = evaluate(snapshot(healthy("orders", cpu = listOf("0.1")) + healthy("payments", cpu = listOf("0.1")), cells = 1))

        assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"))
        assertEquals(
            setOf("cpu/orders", "cpu/payments"),
            result
                .checks()
                .filter { it.str("reason") == "RULE_WINDOW_TOO_SHORT" }
                .map { it.str("rule_id") }
                .toSet(),
        )
    }

    @Test
    fun `a coverage violation on one cell fails the window`() {
        val snapshot =
            snapshot(
                healthy("orders") +
                    listOf(
                        series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }),
                        series(
                            "unavailable-payments",
                            "unavailable",
                            "payments",
                            "count",
                            listOf("0", "1", "0", "0"),
                            ResourceAggregation.INTERVAL_MAX,
                        ),
                    ),
            )

        val result = evaluate(snapshot)

        assertEquals(PolicyVerdict.FAIL, result.windowVerdicts.getValue("steady"))
        assertEquals("FAIL", result.checks().single { it.str("rule_id") == "cover/payments" }.str("status"))
    }

    @Test
    fun `platform rules without a snapshot block the verdict only when they are SLA rules`() {
        val sla = evaluatePolicy(policy(), RunValidity.VALID, metricsWith(100))
        val diagnostic =
            evaluatePolicy(
                policy(policyJson(coverage = "", cpuEffect = "diagnostic", coverEffect = "diagnostic")),
                RunValidity.VALID,
                metricsWith(100),
            )

        assertEquals(PolicyVerdict.NO_VERDICT, sla.verdict)
        assertEquals(listOf("RESOURCE_SNAPSHOT_REQUIRED"), sla.coverageReasons)
        assertEquals(PolicyVerdict.PASS, diagnostic.verdict)
        assertEquals(listOf("RESOURCE_SNAPSHOT_REQUIRED"), diagnostic.coverageReasons)
    }

    @Test
    fun `one owner for SLA thresholds and a bounded number of checks`() {
        val policy = policy()
        val sla = rule("snapshot-sla", ResourceRuleEffect.SLA)
        val diagnostic = rule("snapshot-diagnostic", ResourceRuleEffect.DIAGNOSTIC)

        assertEquals(
            listOf("PLATFORM_RULES_CONFLICT"),
            validatePlatformBinding(policy, snapshot(healthy("orders"), listOf(sla))).map { it.code },
        )
        assertEquals(emptyList<String>(), validatePlatformBinding(policy, snapshot(healthy("orders"), listOf(diagnostic))).map { it.code })
        assertEquals(
            listOf("DUPLICATE_RULE_ID"),
            validatePlatformBinding(
                policy,
                snapshot(healthy("orders"), listOf(rule("cpu/orders", ResourceRuleEffect.DIAGNOSTIC))),
            ).map { it.code },
        )
        val many = List(254) { rule("many-$it", ResourceRuleEffect.DIAGNOSTIC) }
        assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), validatePlatformBinding(policy, snapshot(healthy("orders"), many)).map { it.code })
        val withoutPlatform = PolicyV1("policy.v1", "plain", emptyList())
        assertEquals(
            emptyList<String>(),
            validatePlatformBinding(withoutPlatform, snapshot(healthy("orders"), listOf(sla))).map { it.code },
        )
    }

    @Test
    fun `a series of a service outside the catalog blocks an all_services rule`() {
        val result = evaluate(snapshot(healthy("orders") + healthy("payments") + healthy("ghost-svc")))

        assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"))
        assertTrue("PLATFORM_SERVICE_NOT_IN_CATALOG" in result.coverageReasons)
        assertEquals(
            setOf("cpu/ghost-svc", "cover/ghost-svc"),
            result
                .checks()
                .filter { it.str("reason") == "PLATFORM_SERVICE_NOT_IN_CATALOG" }
                .map { it.str("rule_id") }
                .toSet(),
        )
    }

    @Test
    fun `an unknown window id of a platform rule blocks the verdict`() {
        val policy = policy(policyJson(cpuExtra = ""","window_ids":["ghost"]"""))
        val snapshot = snapshot(healthy("orders") + healthy("payments"))

        val evaluation =
            evaluateSharedWindowPolicy(
                policy,
                RunValidity.VALID,
                metricsWith(100),
                mapOf("steady" to metricsWith(100)),
                evaluate(snapshot, policy),
                snapshot.windows,
            )

        assertEquals(PolicyVerdict.NO_VERDICT, evaluation.verdict)
        assertTrue("RULE_WINDOW_NOT_FOUND" in evaluation.coverageReasons)
        assertEquals(
            listOf("cpu"),
            evaluation.evidence.filter { it.getValue("type").jsonPrimitive.content == "rule_window_check" }.map { it.str("rule_id") },
        )
    }

    private fun series(
        id: String,
        signal: String,
        service: String,
        unit: String,
        values: List<String?>,
        aggregation: ResourceAggregation = ResourceAggregation.INTERVAL_MEAN,
        role: ResourceRole = ResourceRole.SYSTEM,
    ) = ResourceSeriesV1(id, signal, unit, service, role, aggregation, emptyMap(), values.map { it?.let(::BigDecimal) })

    private fun healthy(
        service: String,
        cpu: List<String?> = List(4) { "0.1" },
    ) = listOf(
        series("cpu-$service", "cpu_ratio", service, "ratio", cpu),
        series("unavailable-$service", "unavailable", service, "count", List(cpu.size) { "0" }, ResourceAggregation.INTERVAL_MAX),
    )

    private fun snapshot(
        series: List<ResourceSeriesV1>,
        rules: List<ResourceRuleV1> = emptyList(),
        cells: Int = 4,
    ) = ResourceSnapshotV1(
        "resource-snapshot.v1",
        "0".repeat(64),
        0,
        10_000,
        cells,
        series,
        listOf(ResourceWindowV1("steady", 0, cells * 10_000L)),
        rules,
        null,
    )

    private fun rule(
        id: String,
        effect: ResourceRuleEffect,
    ) = ResourceRuleV1(id, "cpu-orders", "ratio", ResourceOperator.GT, BigDecimal("0.9"), 1, effect)

    private fun evaluate(
        snapshot: ResourceSnapshotV1,
        policy: PolicyV1 = policy(),
    ) = evaluateResources(snapshot, snapshot.windows, platform = expandPlatformRules(policy, snapshot))

    private fun ResourceEvaluation.checks() = evidence.filter { it.getValue("type").jsonPrimitive.content == "resource_policy_check" }

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private fun metricsWith(samples: Long) =
        NormalizedMetrics(
            MetricSummary(samples, 0, ExactRatio(0, samples), ExactRatio(10, 1), LatencySummary(50, 100, 100, 100)),
            emptyList(),
            emptyList(),
            emptyMap(),
        )

    private fun policy(json: String = policyJson()) =
        (validatePolicy(ByteArrayInputStream(json.encodeToByteArray())) as PolicyValidation.Valid).policy

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
            if (extraRuleId == null) {
                ""
            } else {
                """,{"id":"$extraRuleId","metric":"error_rate_ratio","operator":"lte","threshold":1,"scope":{"kind":"overall"}}"""
            }
        return """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
            catalog +
            coverage +
            """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,""" +
            """"scope":{"kind":"overall"}}$extraRule],""" +
            """"platform_rules":[""" +
            """{"id":"$cpuId","signal":"cpu_ratio","scope":$cpuScope,"operator":"gt","threshold":$cpuThreshold,"unit":"ratio",""" +
            """"aggregation":"$cpuAggregation","min_consecutive_cells":$cpuMinimum,"effect":"$cpuEffect"$cpuExtra},""" +
            """{"id":"$coverId","signal":"unavailable","scope":$coverScope,"operator":"gt","threshold":$coverThreshold,"unit":"count",""" +
            """"aggregation":"$coverAggregation","min_consecutive_cells":$coverMin,"effect":"$coverEffect"$coverExtra}]}"""
    }
}

package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1: the typed models write the same bytes as the hand-built JsonObject they replaced. The oracle is
 * [legacyAnalysisIdentity] / [legacyAnalysisResult], a frozen copy of the old builders.
 */
class AnalysisDocumentsEquivalenceTest {
    private val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun input(
        type: SourceType,
        runId: String = "run-${type.wireName}",
    ) = AcceptedInput(runId, type, hash, 1, "input.jtl", Path.of("unused"))

    private fun policy(path: String) =
        assertInstanceOf(
            PolicyValidation.Valid::class.java,
            validatePolicy(ByteArrayInputStream(Files.readAllBytes(Path.of(path)))),
        )

    private fun resources(path: String) =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(Files.readAllBytes(Path.of(path)))),
        )

    private val policies: List<PolicyValidation.Valid?> =
        listOf(
            null,
            policy("fixtures/slice1/identity/policy.canonical.json"),
            policy("fixtures/slice1/policies/pass.json"),
            policy("docs/contracts/policy/v1/examples/valid/platform-services.json"),
            policy("docs/contracts/policy/v1/examples/valid/platform-gap-tolerance.json"),
            policy("docs/contracts/policy/v1/examples/valid/platform-base-profile.json"),
        )

    private val resourceSets: List<ResourceValidation.Valid?> =
        listOf(
            null,
            resources("docs/contracts/resources/v1/examples/valid/basic.json"),
            resources("docs/contracts/resources/v1/examples/valid/arm.json"),
        )

    private val diagnostics =
        DiagnosticValidation.Valid(
            DiagnosticPlanV1("correlation-plan.v1", "0".repeat(64), emptyList(), emptyList()),
            "d".repeat(64),
            byteArrayOf(),
        )
    private val capacity =
        CapacityPlanValidation.Valid(
            CapacityPlanV1("a", "b", CapacityLoadAxis.RPS, null, BigDecimal("0.05"), null, listOf("guard"), emptyList()),
            "c".repeat(64),
            byteArrayOf(),
        )
    private val trend =
        assertInstanceOf(
            TrendPlanValidation.Valid::class.java,
            validateTrendPlan(ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/trend/v1/examples/valid/basic.json")))),
        )
    private val podView =
        validPodView(podViewTestJson(hash, "e".repeat(64), "A", 1_767_225_600_000L, 20_000, 2))

    @Test
    fun `identity bytes equal the pre-refactor builder on the whole input matrix`() {
        val configs = listOf(EngineConfig(), EngineConfig(metrics = MetricsConfig(significantDigits = 2)), EngineConfig("лт-engine-é", "2"))
        var compared = 0
        for (type in SourceType.entries) {
            for (config in configs) {
                for (policy in policies) {
                    for (resource in resourceSets) {
                        for (extras in 0 until 8) {
                            val diag = if (extras and 1 != 0) diagnostics else null
                            val cap = if (extras and 2 != 0) capacity else null
                            val optionalHashes = if (extras and 4 != 0) "f".repeat(64) else null
                            val trendPlan = if (extras and 2 != 0 && extras and 4 != 0) trend else null
                            val pod = if (extras == 7) podView else null
                            val accepted = input(type)
                            val expected =
                                legacyAnalysisIdentity(
                                    accepted,
                                    policy,
                                    config,
                                    resource,
                                    diag,
                                    optionalHashes,
                                    optionalHashes,
                                    cap,
                                    trendPlan,
                                    pod,
                                )
                            val actual =
                                analysisIdentity(
                                    accepted,
                                    policy,
                                    config,
                                    resource,
                                    diag,
                                    optionalHashes,
                                    optionalHashes,
                                    cap,
                                    trendPlan,
                                    pod,
                                )
                            assertArrayEquals(expected, actual, "$type $config ${policy?.sha256} ${resource?.semanticSha256} $extras")
                            assertEquals(sha256Hex(expected), sha256Hex(actual))
                            compared++
                        }
                    }
                }
            }
        }
        assertTrue(compared > 1000, "matrix size $compared")
    }

    @Test
    fun `identity with a run id that needs escaping equals the old builder`() {
        listOf("run-é-\u0001-\u001f-\"\\/- -😀", "x".repeat(300), "").forEach { runId ->
            val accepted = input(SourceType.JMETER_CSV, runId)
            assertArrayEquals(
                legacyAnalysisIdentity(accepted, null, EngineConfig()),
                analysisIdentity(accepted, null, EngineConfig()),
                runId,
            )
        }
    }

    @Test
    fun `result bytes equal the pre-refactor builder for every validity, mode, verdict, coverage and capacity variant`() {
        val findingsSets =
            listOf(
                emptyList(),
                listOf(
                    obj(
                        """{"id":"f1","kind":"policy_violation","observed":1.50,"threshold":300,"nested":{"n":null,"big":12345678901234567890.5,"s":"é\u0001😀"}}""",
                    ),
                    obj("""{"id":"f2","list":[1,2.0,"3",true,false,null,{"a":[]}]}"""),
                ),
            )
        val evidenceSets =
            listOf(
                emptyList(),
                listOf(
                    obj(
                        """{"id":"e1","type":"metric_summary","latency_ms":{"p95":339,"max":1e3,"wide":0.1234567890123456789012345678},"exp":-2.5E+100,"ratio":{"numerator":"1","denominator":"3"}}""",
                    ),
                ),
            )
        val capacityJson =
            obj(
                """{"schema_version":"capacity.v1","status":"EVALUATED","stages":[{"id":"s","value":0.10,"wide":98765432109876543210.123456789,"exp":1e100}]}""",
            )
        var compared = 0
        for (validity in RunValidity.entries) {
            for (mode in AnalysisMode.entries) {
                for (verdict in PolicyVerdict.entries) {
                    for (reasons in listOf(emptyList(), listOf("RESOURCE_DATA_NOT_PROVIDED", "ü"))) {
                        for (findings in findingsSets) {
                            for (evidence in evidenceSets) {
                                for (cap in listOf<CapacityAnalysis?>(
                                    null,
                                    CapacityAnalysis(PolicyVerdict.NO_VERDICT, capacityJson, emptyList(), emptyList()),
                                )) {
                                    val evaluation = PolicyEvaluation(verdict, reasons, findings, evidence)
                                    val expected = legacyAnalysisResult("run-1", validity, evaluation, mode, cap)
                                    val actual = analysisResult("run-1", validity, evaluation, mode, cap)
                                    assertEquals(expected.decodeToString(), actual.decodeToString())
                                    assertArrayEquals(expected, actual)
                                    compared++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(compared > 200, "matrix size $compared")
    }

    @Test
    fun `committed identity and result documents decode strictly and encode back to the same bytes`() {
        val identityFiles =
            listOf(
                "fixtures/slice1/identity/analysis-identity.v1.json",
                "fixtures/slice1/identity/analysis-identity-resources.v1.json",
                "fixtures/slice1/identity/legacy-pre-adr-0016.v1.json",
            ) + goldenFiles("identity.json")
        identityFiles.forEach { file ->
            val bytes = Files.readAllBytes(Path.of(file))
            val document = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisIdentityDocument.serializer(), bytes.decodeToString())
            assertArrayEquals(bytes, encodeAnalysisIdentity(document), file)
        }
        val resultFiles = goldenFiles("analysis-result.json")
        assertTrue(resultFiles.size >= 8)
        resultFiles.forEach { file ->
            val bytes = Files.readAllBytes(Path.of(file))
            val document = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisResultDocument.serializer(), bytes.decodeToString())
            assertArrayEquals(bytes, encodeAnalysisResult(document), file)
        }
    }

    @Test
    fun `a result with a capacity summary round-trips and absence stays absence`() {
        val withCapacity =
            """{"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"analysis_mode":"capacity_step","capacity_summary":{"a":1},"evidence":[],"findings":[],"policy_verdict":"PASS","run_id":"r","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
        val document = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisResultDocument.serializer(), withCapacity)
        assertEquals(withCapacity, encodeAnalysisResult(document).decodeToString())
        val without = withCapacity.replace(""""capacity_summary":{"a":1},""", "")
        val plain = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisResultDocument.serializer(), without)
        assertEquals(null, plain.capacitySummary)
        assertEquals(without, encodeAnalysisResult(plain).decodeToString())
    }

    @Test
    fun `the accepted key set is derived from the model`() {
        val required =
            listOf(
                "schema_version",
                "run_id",
                "analysis_mode",
                "run_validity",
                "policy_verdict",
                "analysis_coverage",
                "findings",
                "evidence",
            )
        val base = JsonObject(required.associateWith { kotlinx.serialization.json.JsonNull })
        assertTrue(hasSupportedAnalysisResultKeys(base))
        assertTrue(hasSupportedAnalysisResultKeys(JsonObject(base + ("capacity_summary" to kotlinx.serialization.json.JsonNull))))
        assertEquals(false, hasSupportedAnalysisResultKeys(JsonObject(base + ("incidents" to kotlinx.serialization.json.JsonNull))))
        assertEquals(false, hasSupportedAnalysisResultKeys(JsonObject(base - "evidence")))
        assertEquals(false, hasSupportedAnalysisResultKeys(JsonObject(base + ("extra" to kotlinx.serialization.json.JsonNull))))
        assertEquals(setOf("analysis-result.v1"), SUPPORTED_ANALYSIS_RESULT_VERSIONS)
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun goldenFiles(name: String): List<String> =
        Files.list(Path.of("fixtures/typed-boundary/golden")).use { dirs ->
            dirs.map { it.resolve(name).toString() }.sorted().toList()
        }
}

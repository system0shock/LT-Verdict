package io.ltverdict.core

import io.ltverdict.ai.AdvisoryEvidenceBuilder
import io.ltverdict.ingest.RunValidity
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class IncidentIdentityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `real staged and resource-window analyses synthesize incidents and re-synthesis from stripped result is byte-identical`() {
        val policy =
            StagedResults.policy(
                """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":190,"window_ids":["steady"],"scope":{"kind":"overall"}}""",
            )
        val stagedOutcome = StagedResults.analyze(tempDir.resolve("staged"), StagedResults.RAMP, policy, StagedResults.RAMP_STEADY_DOWN)

        val resourceOutcome =
            DataDirectory.open(tempDir.resolve("resource-window")).use { directory ->
                val store = RunBundleStore(directory)
                val input = store.acceptInput(ByteArrayInputStream(StagedResults.RAMP), "ramp.jtl")
                val snapshotJson = Files.readString(Path.of("fixtures/stages/cross-version/snapshot-15s.json"))
                val resources = validateResourceSnapshot(ByteArrayInputStream(snapshotJson.encodeToByteArray())) as ResourceValidation.Valid
                val windowPolicy =
                    validatePolicy(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/stages/cross-version/policy-platform.json"))),
                    ) as PolicyValidation.Valid
                AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(input, windowPolicy, resources = resources))
            }

        for ((label, outcome) in listOf("staged" to stagedOutcome, "resource-window" to resourceOutcome)) {
            val resultObj = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val identityObj = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject

            assertEquals(INCIDENT_METHOD, identityObj["incident_method"]?.jsonPrimitive?.content, label)
            val incidents = resultObj["incidents"]?.jsonObject ?: error("$label: missing incidents in analysis result")
            assertEquals("EVALUATED", incidents.getValue("status").jsonPrimitive.content, label)
            assertTrue(incidents.getValue("items").jsonArray.isNotEmpty(), label)

            val strippedResult = JsonObject(resultObj - "incidents")
            val validity = RunValidity.valueOf(strippedResult.getValue("run_validity").jsonPrimitive.content)
            val findings = strippedResult.getValue("findings").jsonArray.map { it.jsonObject }
            val evidence = strippedResult.getValue("evidence").jsonArray.map { it.jsonObject }
            val resynthesized = synthesizeIncidents(validity, findings, evidence)

            assertArrayEquals(canonicalJson(incidents), canonicalJson(resynthesized), label)
        }
    }

    @Test
    fun `golden-incidents matches pre-incident golden when incidents and incident_method are stripped`() {
        val goldenIncidents = Path.of("fixtures/typed-boundary/golden-incidents")
        val goldenPlain = Path.of("fixtures/typed-boundary/golden")
        val cases = Files.list(goldenIncidents).use { it.filter(Files::isDirectory).sorted().toList() }
        assertEquals(8, cases.size)

        for (caseDir in cases) {
            val name = caseDir.fileName.toString()
            val plainDir = goldenPlain.resolve(name)
            val resultObj = Json.parseToJsonElement(Files.readString(caseDir.resolve("analysis-result.json"))).jsonObject
            val identityObj = Json.parseToJsonElement(Files.readString(caseDir.resolve("identity.json"))).jsonObject

            assertEquals(INCIDENT_METHOD, identityObj["incident_method"]?.jsonPrimitive?.content, name)
            assertTrue(resultObj.containsKey("incidents"), name)

            val strippedResult = canonicalJson(JsonObject(resultObj - "incidents"))
            val strippedIdentity = canonicalJson(JsonObject(identityObj - "incident_method"))
            assertArrayEquals(Files.readAllBytes(plainDir.resolve("analysis-result.json")), strippedResult, "$name stripped result")
            assertArrayEquals(Files.readAllBytes(plainDir.resolve("identity.json")), strippedIdentity, "$name stripped identity")
            assertEquals(Files.readString(plainDir.resolve("analysis_id.txt")).trim(), sha256Hex(strippedIdentity), "$name stripped id")
        }
    }

    @Test
    fun `baseline and run comparison comparability keys are unchanged with and without stages`() {
        val plainOutcome =
            StagedResults.analyze(
                tempDir.resolve("plain"),
                StagedResults.RAMP,
                StagedResults.policy(StagedResults.p95(100_000)),
                stages = null,
            )
        val stagedOutcome =
            StagedResults.analyze(
                tempDir.resolve("staged"),
                StagedResults.RAMP,
                StagedResults.policy(StagedResults.p95(100_000, """["steady"]""")),
                stages = StagedResults.RAMP_STEADY_DOWN,
            )

        for ((label, outcome, windowId) in listOf(
            Triple("plain", plainOutcome, null),
            Triple("staged", stagedOutcome, "steady"),
        )) {
            val newResult = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val newIdentity = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject
            val runJson = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("run.json"))).jsonObject
            assertEquals(INCIDENT_METHOD, newIdentity["incident_method"]?.jsonPrimitive?.content, label)
            if (windowId != null) {
                assertTrue(newIdentity.containsKey("load_stages_sha256"), label)
            }

            val oldIdentity = JsonObject(newIdentity - "incident_method")
            val oldResult = JsonObject(newResult - "incidents")
            val oldId = sha256Hex(canonicalJson(oldIdentity))
            assertNotEquals(oldId, outcome.analysisId, "$label analysis_id must differ")

            val oldRef =
                buildJsonObject {
                    put("run_id", outcome.runId)
                    put("analysis_id", oldId)
                }
            val newRef =
                buildJsonObject {
                    put("run_id", outcome.runId)
                    put("analysis_id", outcome.analysisId)
                }

            val baseline =
                compareAnalyses(
                    manualBaselineSelection("release", oldRef),
                    newRef,
                    oldResult,
                    oldIdentity,
                    newResult,
                    newIdentity,
                    windowId?.let { WindowComparisonRequest(it, it) },
                )
            baseline.getValue("metrics").jsonArray.forEach { metric ->
                assertEquals(JsonNull, metric.jsonObject.getValue("reason"), "$label metric ${metric.jsonObject["metric"]}")
            }
            if (windowId != null) {
                val reasons =
                    baseline
                        .getValue("window_comparison")
                        .jsonObject
                        .getValue("reasons")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                assertFalse("INCOMPATIBLE_METRIC_DEFINITION" in reasons, "$label window reasons: $reasons")
            }

            val oldSaved = SavedAnalysisForComparison(oldRef, runJson, oldResult, oldIdentity)
            val newSaved = SavedAnalysisForComparison(newRef, runJson, newResult, newIdentity)
            val dynamics = buildRunDynamics(newSaved, listOf(oldSaved, newSaved), oldRef)
            assertEquals(2, dynamics.getValue("comparable_count").jsonPrimitive.int, "$label dynamics comparable_count")
            assertEquals(0, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int, "$label dynamics excluded")
        }

        val legacyPreAdr0016 =
            Json.parseToJsonElement(Files.readString(Path.of("fixtures/slice1/identity/legacy-pre-adr-0016.v1.json"))).jsonObject
        assertFalse(legacyPreAdr0016.containsKey("incident_method"))
    }

    @Test
    fun `advisory evidence builder accepts all four capacity and incidents combinations without changing output`() {
        val runId = "run-1"
        val analysisId = "a".repeat(64)
        val manifestSha = "b".repeat(64)
        val base =
            Json
                .parseToJsonElement(
                    """{"schema_version":"analysis-result.v1","run_id":"run-1","analysis_mode":"standard","run_validity":"VALID",""" +
                        """"policy_verdict":"FAIL","analysis_coverage":{"status":"COMPLETE","reasons":[]},""" +
                        """"findings":[{"id":"f1","type":"policy_failure"}],"evidence":[{"id":"e1","type":"policy_check"}]}""",
                ).jsonObject
        val capacity = Json.parseToJsonElement("""{"schema_version":"capacity.v1","stages":[]}""").jsonObject
        val incidents =
            Json
                .parseToJsonElement(
                    """{"schema_version":"incident.v1","method":"incident-synthesis.v1","status":"EVALUATED","overview_limit":7,"total_count":0,"omitted_count":0,"items":[]}""",
                ).jsonObject

        val plain = AdvisoryEvidenceBuilder.build(runId, analysisId, manifestSha, base)
        val withIncidents = AdvisoryEvidenceBuilder.build(runId, analysisId, manifestSha, JsonObject(base + ("incidents" to incidents)))
        assertArrayEquals(plain.bytes, withIncidents.bytes)
        assertEquals(plain.sha256, withIncidents.sha256)

        val withCapacity =
            AdvisoryEvidenceBuilder.build(
                runId,
                analysisId,
                manifestSha,
                JsonObject(base + ("capacity_summary" to capacity)),
            )
        val withBoth =
            AdvisoryEvidenceBuilder.build(
                runId,
                analysisId,
                manifestSha,
                JsonObject(base + mapOf("capacity_summary" to capacity, "incidents" to incidents)),
            )
        assertArrayEquals(withCapacity.bytes, withBoth.bytes)
        assertEquals(withCapacity.sha256, withBoth.sha256)
    }

    @Test
    fun `invalid run produces NOT_EVALUATED incidents via AnalysisService`() {
        DataDirectory.open(tempDir.resolve("invalid-data")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream("timeStamp,elapsed,label,success\n".encodeToByteArray()), "empty.jtl")
            val outcome = AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(input, null))
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val incidents = result.getValue("incidents").jsonObject

            assertEquals("INVALID", result.getValue("run_validity").jsonPrimitive.content)
            assertEquals("NOT_EVALUATED", incidents.getValue("status").jsonPrimitive.content)
            assertEquals("RUN_NOT_VALID", incidents.getValue("reason_code").jsonPrimitive.content)
            assertEquals(0, incidents.getValue("total_count").jsonPrimitive.int)
            assertEquals(0, incidents.getValue("omitted_count").jsonPrimitive.int)
            assertTrue(incidents.getValue("items").jsonArray.isEmpty())
        }
    }

    @Test
    fun `capacity_step run includes incidents without stage or capacity items`() {
        DataDirectory.open(tempDir.resolve("capacity-data")).use { directory ->
            val store = RunBundleStore(directory)
            val csvHeader =
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                    "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
            val csv =
                (
                    listOf(csvHeader) +
                        (0 until 30).map { index ->
                            "${1_767_225_600_000L + index * 10_000L},${if (index == 29) 10_000 else 10}," +
                                "steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0"
                        }
                ).joinToString("\n", postfix = "\n")
            val input = store.acceptInput(ByteArrayInputStream(csv.encodeToByteArray()), "capacity.jtl")
            val zeros = List(30) { "0.5" }.joinToString(",")
            val resourceJson =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}",""" +
                    """"start_epoch_ms":1767225600000,"step_ms":10000,"point_count":30,""" +
                    """"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"app-1","role":"system",""" +
                    """"aggregation":"interval_mean","values":[$zeros]}],""" +
                    """"windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000}],""" +
                    """"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":1,""" +
                    """"min_consecutive_cells":1,"effect":"sla"}],""" +
                    """"provenance":{"source_kind":"fixture","query_semantics":"interval mean","clock_alignment":"capacity"}}"""
            val resources = validateResourceSnapshot(ByteArrayInputStream(resourceJson.encodeToByteArray())) as ResourceValidation.Valid
            val planJson =
                """{"schema_version":"capacity-plan.v1","load_input_sha256":"${input.sha256}","resource_snapshot_sha256":"${resources.semanticSha256}","load_axis":"rps","achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0,"required_capacity":1},"generator_guard_rule_ids":[],"stages":[{"id":"steady","target":1,"from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000,"evaluation_window_id":"steady"}]}"""
            val capacity = validateCapacityPlan(ByteArrayInputStream(planJson.encodeToByteArray())) as CapacityPlanValidation.Valid
            val policyJson =
                """{"schema_version":"policy.v1","policy_id":"cap","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,"window_ids":["steady"],"scope":{"kind":"overall"}}]}"""
            val policy = validatePolicy(ByteArrayInputStream(policyJson.encodeToByteArray())) as PolicyValidation.Valid

            val outcome =
                AnalysisService(store, EngineConfig()).analyze(
                    AnalysisRequest(input, policy, resources = resources, capacity = capacity),
                )
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val incidents = result.getValue("incidents").jsonObject

            assertEquals("capacity_step", result.getValue("analysis_mode").jsonPrimitive.content)
            assertTrue(result.containsKey("capacity_summary"))
            assertEquals("EVALUATED", incidents.getValue("status").jsonPrimitive.content)
            val allowedFindingTypes = setOf("policy_failure", "resource_threshold_violation", "anomaly_episode", "resource_trend")
            incidents.getValue("items").jsonArray.forEach { item ->
                val types =
                    item
                        .jsonObject
                        .getValue("finding_types")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                assertTrue(types.all { it in allowedFindingTypes }, "unexpected finding types: $types")
            }
        }
    }
}

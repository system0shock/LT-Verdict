package io.ltverdict.core

import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class DiagnosticIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `diagnostic analysis persists immutable plan and appends deterministic evidence`() =
        withStore { store ->
            val input = store.acceptInput(ByteArrayInputStream(csv()), "diagnostic.jtl")
            val resources = resources(input.sha256)
            val raw = planJson(resources.semanticSha256).encodeToByteArray()
            val diagnostics = plan(raw)
            val service = AnalysisService(store, EngineConfig())

            val outcome = service.analyze(AnalysisRequest(input, policy(), resources = resources, diagnostics = diagnostics))
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val identity = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject
            val run = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("run.json"))).jsonObject
            val diagnosticInput =
                run.getValue("inputs").jsonArray.map { it.jsonObject }.single {
                    it.getValue("type").jsonPrimitive.content == "correlation_plan"
                }

            assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
            assertEquals(
                "COMPLETE",
                result
                    .getValue("analysis_coverage")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(
                listOf("COMPLETE"),
                result
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .filter {
                        it["type"]?.jsonPrimitive?.content == "diagnostic_summary"
                    }.map { it.getValue("status").jsonPrimitive.content },
            )
            assertTrue(
                result.getValue("evidence").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "window_metric_summary" },
            )
            assertEquals(diagnostics.sha256, identity.getValue("diagnostic_plan_sha256").jsonPrimitive.content)
            assertTrue(
                identity.getValue("modules").jsonArray.any {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "load-resource-diagnostics"
                },
            )
            assertEquals("analyses/${outcome.analysisId}/correlation-plan.json", diagnosticInput.getValue("path").jsonPrimitive.content)
            assertEquals(sha256Hex(raw), diagnosticInput.getValue("sha256").jsonPrimitive.content)
            assertArrayEquals(raw, Files.readAllBytes(outcome.analysisDirectory.resolve("correlation-plan.json")))
            assertTrue(
                store.readAnalysis(input.runId, outcome.analysisId)!!.artifacts.any {
                    it.path == "correlation-plan.json" && it.sha256 == sha256Hex(raw)
                },
            )

            val replayRaw = planJson(resources.semanticSha256).replace(":", ": ").encodeToByteArray()
            val replay = service.analyze(AnalysisRequest(input, policy(), resources = resources, diagnostics = plan(replayRaw)))
            assertEquals(outcome.analysisId, replay.analysisId)
            assertArrayEquals(raw, Files.readAllBytes(replay.analysisDirectory.resolve("correlation-plan.json")))
            assertArrayEquals(outcome.canonicalResult, replay.canonicalResult)
        }

    @Test
    fun `diagnostic histogram limit is local and leaves SLA verdict and coverage unchanged`() =
        withStore { store ->
            val input = store.acceptInput(ByteArrayInputStream(csv()), "limit.jtl")
            val resources = resources(input.sha256)
            val service = AnalysisService(store, EngineConfig(metrics = MetricsConfig(maxWindowHistograms = 1)))
            val baseline = service.analyze(AnalysisRequest(input, policy(), resources = resources))
            val limited =
                service.analyze(
                    AnalysisRequest(
                        input,
                        policy(),
                        resources = resources,
                        diagnostics = plan(planJson(resources.semanticSha256).encodeToByteArray()),
                    ),
                )
            val baselineJson = Json.parseToJsonElement(baseline.canonicalResult.decodeToString()).jsonObject
            val limitedJson = Json.parseToJsonElement(limited.canonicalResult.decodeToString()).jsonObject
            val summary =
                limitedJson.getValue("evidence").jsonArray.map { it.jsonObject }.single {
                    it["type"]?.jsonPrimitive?.content == "diagnostic_summary"
                }

            assertEquals(baselineJson.getValue("policy_verdict"), limitedJson.getValue("policy_verdict"))
            assertEquals(baselineJson.getValue("analysis_coverage"), limitedJson.getValue("analysis_coverage"))
            assertEquals("LIMIT_EXCEEDED", summary.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("DIAGNOSTIC_CELL_LIMIT_EXCEEDED"), summary.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
        }

    @Test
    fun `unreferenced resource windows do not consume diagnostic cell budget`() =
        withStore { store ->
            val input = store.acceptInput(ByteArrayInputStream(csv()), "selected-window.jtl")
            val resources = resourcesWithUnreferencedWindow(input.sha256)
            val diagnostics = plan(planJson(resources.semanticSha256).encodeToByteArray())
            val service = AnalysisService(store, EngineConfig(metrics = MetricsConfig(maxWindowHistograms = 3)))

            val outcome = service.analyze(AnalysisRequest(input, policy(), resources = resources, diagnostics = diagnostics))
            val evidence =
                Json
                    .parseToJsonElement(outcome.canonicalResult.decodeToString())
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
            val summary = evidence.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "diagnostic_summary" }

            assertEquals("COMPLETE", summary.getValue("status").jsonPrimitive.content)
            assertEquals(2, evidence.count { it.jsonObject["type"]?.jsonPrimitive?.content == "window_metric_summary" })
        }

    private fun withStore(block: (RunBundleStore) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { block(RunBundleStore(it)) }
    }

    private fun policy(): PolicyValidation.Valid =
        validatePolicy(
            ByteArrayInputStream(
                """{"schema_version":"policy.v1","policy_id":"pass","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}"""
                    .encodeToByteArray(),
            ),
        ) as PolicyValidation.Valid

    private fun resources(loadHash: String): ResourceValidation.Valid {
        val values = List(40) { (it + 1).toString() }.joinToString(",")
        val json =
            """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"$loadHash",
              "start_epoch_ms":1767225600250,
              "step_ms":1000,
              "point_count":40,
              "series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[$values]}],
              "windows":[{"id":"evaluation","from_epoch_ms":1767225600250,"to_epoch_ms":1767225640250}]
            }
            """.trimIndent()
        return validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray())) as ResourceValidation.Valid
    }

    private fun resourcesWithUnreferencedWindow(loadHash: String): ResourceValidation.Valid {
        val values = List(40) { (it + 1).toString() }.joinToString(",")
        val json =
            """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"$loadHash",
              "start_epoch_ms":1767225600250,
              "step_ms":1000,
              "point_count":40,
              "series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[$values]}],
              "windows":[
                {"id":"evaluation","from_epoch_ms":1767225600250,"to_epoch_ms":1767225601250},
                {"id":"unreferenced","from_epoch_ms":1767225601250,"to_epoch_ms":1767225640250}
              ]
            }
            """.trimIndent()
        return validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray())) as ResourceValidation.Valid
    }

    private fun plan(raw: ByteArray): DiagnosticValidation.Valid =
        validateDiagnosticPlan(ByteArrayInputStream(raw)) as DiagnosticValidation.Valid

    private fun planJson(snapshotHash: String): String =
        """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"$snapshotHash","pairs":[{"id":"cpu-latency","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"load host"}]}"""

    private fun csv(): ByteArray {
        val header =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
        val rows =
            buildList {
                repeat(40) { cell ->
                    repeat(20) { sample ->
                        val start = 1_767_225_600_250L + cell * 1_000L + sample
                        add("$start,${cell + 1},request,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0")
                    }
                }
                add("1767225640000,250,last,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0")
            }
        return (listOf(header) + rows).joinToString("\n").encodeToByteArray()
    }
}

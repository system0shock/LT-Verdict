package io.ltverdict.core

import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path

class TwoWindowResourceSlaTest {
    @TempDir
    lateinit var tempDir: Path

    private val start = 1_767_225_600_000L

    @Test
    fun `headroom resource SLA spike does not fail capacity PASS while target-step resource SLA spike fails`() {
        val outcome =
            analyze(
                name = "headroom-spike",
                stage1Cpu = "0.40",
                stage2Cpu = "0.95",
                stage2ElapsedMs = 40,
                includeBusinessRule = true,
            )
        val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
        val capacity = result.getValue("capacity_summary").jsonObject

        assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals("LOWER_BOUND", capacity.getValue("bound_type").jsonPrimitive.content)
        assertEquals("30", capacity.getValue("lower_inclusive").jsonPrimitive.content)
        val resourceFindings =
            result
                .getValue("findings")
                .jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("type").jsonPrimitive.content == "resource_threshold_violation" }
        assertEquals(emptyList<Any>(), resourceFindings)
        val checks =
            result
                .getValue("evidence")
                .jsonArray
                .map { it.jsonObject }
                .filter {
                    it.getValue("type").jsonPrimitive.content == "resource_policy_check" &&
                        it.getValue("effect").jsonPrimitive.content == "sla"
                }
        assertEquals(listOf("s1"), checks.map { it.getValue("window_id").jsonPrimitive.content })
    }

    @Test
    fun `target-step resource SLA spike still fails capacity`() {
        val outcome =
            analyze(
                name = "target-spike",
                stage1Cpu = "0.95",
                stage2Cpu = "0.95",
                stage2ElapsedMs = 40,
                includeBusinessRule = true,
            )
        val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
        val capacity = result.getValue("capacity_summary").jsonObject

        assertEquals("FAIL", result.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals("UPPER_BOUND", capacity.getValue("bound_type").jsonPrimitive.content)
        val resourceFindings =
            result
                .getValue("findings")
                .jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("type").jsonPrimitive.content == "resource_threshold_violation" }
        assertTrue(resourceFindings.any { it.getValue("window_id").jsonPrimitive.content == "s1" })
    }

    @Test
    fun `business SLA violation on headroom still bounds capacity even when resource SLA is scoped to target step`() {
        val outcome =
            analyze(
                name = "headroom-business-fail",
                stage1Cpu = "0.40",
                stage2Cpu = "0.95",
                stage2ElapsedMs = 2_000,
                includeBusinessRule = true,
            )
        val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
        val capacity = result.getValue("capacity_summary").jsonObject

        assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals("BOUNDED", capacity.getValue("bound_type").jsonPrimitive.content)
        assertEquals("20", capacity.getValue("lower_inclusive").jsonPrimitive.content)
        assertEquals("30", capacity.getValue("upper_exclusive").jsonPrimitive.content)
    }

    @Test
    fun `headroom without business SLA rule does not force NO_POLICY when target step has resource SLA`() {
        val outcome =
            analyze(
                name = "resource-sla-only",
                stage1Cpu = "0.40",
                stage2Cpu = "0.95",
                stage2ElapsedMs = 40,
                includeBusinessRule = false,
            )
        val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
        val capacity = result.getValue("capacity_summary").jsonObject

        assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals("LOWER_BOUND", capacity.getValue("bound_type").jsonPrimitive.content)
    }

    private fun analyze(
        name: String,
        stage1Cpu: String,
        stage2Cpu: String,
        stage2ElapsedMs: Int,
        includeBusinessRule: Boolean,
    ): AnalysisOutcome {
        val header =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
        val rows =
            buildList {
                // s1: 300s @ 20 RPS
                repeat(300) { sec ->
                    repeat(20) { i ->
                        add("${start + sec * 1_000L + i * 20L},40,request,200,OK,t,text,true,,0,0,1,1,null,0,0,0")
                    }
                }
                // s2: 300s @ 30 RPS
                repeat(300) { sec ->
                    repeat(30) { i ->
                        add("${start + 300_000L + sec * 1_000L + i * 20L},$stage2ElapsedMs,request,200,OK,t,text,true,,0,0,1,1,null,0,0,0")
                    }
                }
                add("${start + 600_000L},40,tail,200,OK,t,text,true,,0,0,1,1,null,0,0,0")
            }
        val csv = (listOf(header) + rows).joinToString("\n").encodeToByteArray()

        DataDirectory.open(tempDir.resolve(name)).use { dir ->
            val store = RunBundleStore(dir)
            val input = store.acceptInput(ByteArrayInputStream(csv), "$name.jtl")
            val cpuValues = (List(300) { stage1Cpu } + List(300) { stage2Cpu }).joinToString(",")
            val genValues = List(600) { "0.10" }.joinToString(",")
            val resourceJson =
                """
                {"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":$start,"step_ms":1000,"point_count":600,
                 "series":[
                  {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"app","role":"system","aggregation":"interval_mean","labels":{},"values":[$cpuValues]},
                  {"id":"gen","metric":"gen_cpu","unit":"ratio","entity":"gen","role":"generator","aggregation":"interval_mean","labels":{},"values":[$genValues]}],
                 "windows":[
                  {"id":"s1","from_epoch_ms":$start,"to_epoch_ms":${start + 300_000L}},
                  {"id":"s2","from_epoch_ms":${start + 300_000L},"to_epoch_ms":${start + 600_000L}}],
                 "rules":[
                  {"id":"cpu-sla","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.80,"min_consecutive_cells":1,"effect":"sla"},
                  {"id":"gen-cpu","series_id":"gen","unit":"ratio","operator":"gt","threshold":0.90,"min_consecutive_cells":1,"effect":"diagnostic"}]}
                """.trimIndent()
            val resources = validateResourceSnapshot(ByteArrayInputStream(resourceJson.encodeToByteArray())) as ResourceValidation.Valid
            val planJson =
                """
                {"schema_version":"capacity-plan.v1","load_input_sha256":"${input.sha256}","resource_snapshot_sha256":"${resources.semanticSha256}","load_axis":"rps",
                 "achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0.05,"required_capacity":20},"generator_guard_rule_ids":["gen-cpu"],
                 "stages":[
                  {"id":"s1","target":20,"from_epoch_ms":$start,"to_epoch_ms":${start + 300_000L},"evaluation_window_id":"s1"},
                  {"id":"s2","target":30,"from_epoch_ms":${start + 300_000L},"to_epoch_ms":${start + 600_000L},"evaluation_window_id":"s2"}]}
                """.trimIndent()
            val capacity = validateCapacityPlan(ByteArrayInputStream(planJson.encodeToByteArray())) as CapacityPlanValidation.Valid
            val policyJson =
                if (includeBusinessRule) {
                    """{"schema_version":"policy.v1","policy_id":"cap","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":500,"scope":{"kind":"overall"}}]}"""
                } else {
                    """{"schema_version":"policy.v1","policy_id":"cap-empty","rules":[{"id":"p95-s1","metric":"response_time_p95_ms","operator":"lte","threshold":500,"scope":{"kind":"overall"},"window_ids":["s1"]}]}"""
                }
            val policy = validatePolicy(ByteArrayInputStream(policyJson.encodeToByteArray())) as PolicyValidation.Valid
            return AnalysisService(store, EngineConfig()).analyze(
                AnalysisRequest(input, policy, resources = resources, capacity = capacity),
            )
        }
    }
}

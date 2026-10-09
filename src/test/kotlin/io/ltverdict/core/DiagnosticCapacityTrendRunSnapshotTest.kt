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
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 slice 2b, end to end: whole analysis runs with correlation, trend and capacity plans. The bytes of analysis-result.json,
 * identity.json, the capacity and trend artifacts and the analysis_id hash to the values captured from `origin/main` BEFORE the
 * producers were typed (the CLI golden of slice 1 has no run with these plans). Regenerate only on purpose with
 * `LTV_UPDATE_DERIVED_EVIDENCE=1`.
 */
class DiagnosticCapacityTrendRunSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val snapshotFile = Path.of("fixtures/typed-evidence/diagnostic-capacity-trend-runs.sha256")
    private val start = 1_767_225_600_000L
    private val cells = 400

    private fun csv(): ByteArray {
        val header =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
        val rows =
            buildList {
                repeat(cells) { cell ->
                    repeat(20) { sample ->
                        val ok = (cell + sample) % 23 != 0
                        add(
                            "${start + cell * 1_000L + sample},${50 + cell % 17},request,${if (ok) 200 else 500},OK,fixture,text,$ok,,0,0,1,1,null,0,0,0",
                        )
                    }
                }
                add("${start + cells * 1_000L},250,last,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0")
            }
        return (listOf(header) + rows).joinToString("\n").encodeToByteArray()
    }

    private fun resources(loadHash: String): ResourceValidation.Valid {
        val cpu = List(cells) { "${20 + it / 3 + (it * 7) % 5}" }.joinToString(",")
        val target = List(cells) { "20" }.joinToString(",")
        val generator = List(cells) { "0.${it % 5}" }.joinToString(",")
        val json =
            """
            {"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadHash","start_epoch_ms":$start,"step_ms":1000,"point_count":$cells,
             "series":[
              {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","labels":{"zone":"é"},"values":[$cpu]},
              {"id":"gen","metric":"gen_cpu","unit":"ratio","entity":"gen","role":"generator","aggregation":"interval_mean","labels":{"zone":"é"},"values":[$generator]},
              {"id":"target","metric":"target_rps","unit":"requests/s","entity":"host","role":"system","aggregation":"interval_mean","labels":{"zone":"é"},"values":[$target]}],
             "windows":[
              {"id":"reference","from_epoch_ms":$start,"to_epoch_ms":${start + 30_000}},
              {"id":"evaluation","from_epoch_ms":${start + 30_000},"to_epoch_ms":${start + 70_000}},
              {"id":"steady","from_epoch_ms":${start + 70_000},"to_epoch_ms":${start + 370_000}}],
             "rules":[{"id":"gen-cpu","series_id":"gen","unit":"ratio","operator":"gt","threshold":0.9,"min_consecutive_cells":1,"effect":"diagnostic"}]}
            """.trimIndent()
        val validation = validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray()))
        check(validation is ResourceValidation.Valid) { "invalid snapshot: $validation" }
        return validation
    }

    private fun policy(): PolicyValidation.Valid =
        validatePolicy(
            ByteArrayInputStream(
                """{"schema_version":"policy.v1","policy_id":"pass","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}"""
                    .encodeToByteArray(),
            ),
        ) as PolicyValidation.Valid

    private fun diagnosticPlan(snapshot: String): DiagnosticValidation.Valid {
        val json =
            """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"$snapshot","pairs":[
              {"id":"cpu-latency","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation","steady"],"max_lag_ms":2000,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"},{"meaning":"achieved_rps"}]}],
             "anomalies":[{"id":"cpu-episode","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"either","min_abs_delta":1,"min_duration_ms":3000}]}"""
        return validateDiagnosticPlan(ByteArrayInputStream(json.encodeToByteArray())) as DiagnosticValidation.Valid
    }

    private fun trendPlan(snapshot: String): TrendPlanValidation.Valid {
        val json =
            """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$snapshot","checks":[
              {"id":"cpu-growth","series_id":"cpu","window_id":"steady","direction":"increase","min_cells":30,"magnitude_gate":{"min_slope_units_per_second":0.001,"min_split_half_shift_pct":5}},
              {"id":"gen-drift","series_id":"gen","window_id":"steady","direction":"either","min_cells":30,"magnitude_gate":{"min_slope_units_per_second":0.0001,"min_split_half_shift_pct":1}}]}"""
        return validateTrendPlan(ByteArrayInputStream(json.encodeToByteArray())) as TrendPlanValidation.Valid
    }

    private fun capacityPlan(
        load: String,
        snapshot: String,
        target: String,
        tolerance: String,
    ): CapacityPlanValidation.Valid {
        val json =
            """{"schema_version":"capacity-plan.v1","load_input_sha256":"$load","resource_snapshot_sha256":"$snapshot","load_axis":"rps",
              "achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":$tolerance,"required_capacity":19},"generator_guard_rule_ids":["gen-cpu"],
              "stages":[{"id":"steady","target":$target,"from_epoch_ms":${start + 70_000},"to_epoch_ms":${start + 370_000},"evaluation_window_id":"steady"}]}"""
        return validateCapacityPlan(ByteArrayInputStream(json.encodeToByteArray())) as CapacityPlanValidation.Valid
    }

    @Test
    fun `whole runs with correlation, trend and capacity plans equal the pre-typing snapshot`() {
        val lines = mutableListOf<String>()
        val seenTypes = sortedSetOf<String>()

        fun run(
            name: String,
            build: (AcceptedInputRef, ResourceValidation.Valid) -> AnalysisRequestParts,
        ) {
            DataDirectory.open(tempDir.resolve("data-$name")).use { dir ->
                val store = RunBundleStore(dir)
                val input = store.acceptInput(ByteArrayInputStream(csv()), "$name.jtl")
                val resources = resources(input.sha256)
                val parts = build(AcceptedInputRef(input.sha256), resources)
                val outcome =
                    runCatching {
                        AnalysisService(store, EngineConfig()).analyze(
                            AnalysisRequest(
                                input,
                                policy(),
                                resources = resources,
                                diagnostics = parts.diagnostics,
                                capacity = parts.capacity,
                                trend = parts.trend,
                            ),
                        )
                    }
                outcome
                    .onFailure { lines += "$name THROWS ${it::class.simpleName}: ${it.message}" }
                    .onSuccess {
                        val files =
                            listOf("analysis-result.json", "identity.json", "capacity.json", "trend.json").mapNotNull { file ->
                                val path = it.analysisDirectory.resolve(file)
                                if (Files.exists(path)) "$file=${sha256Hex(Files.readAllBytes(path))}" else null
                            }
                        lines += "$name id=${it.analysisId} ${files.joinToString(" ")}"
                        Json
                            .parseToJsonElement(it.canonicalResult.decodeToString())
                            .jsonObject
                            .getValue("evidence")
                            .jsonArray
                            .forEach { item ->
                                seenTypes +=
                                    item.jsonObject
                                        .getValue("type")
                                        .jsonPrimitive.content
                            }
                    }
            }
        }

        run("diagnostic-trend") { _, resources ->
            AnalysisRequestParts(diagnostics = diagnosticPlan(resources.semanticSha256), trend = trendPlan(resources.semanticSha256))
        }
        run("capacity") { input, resources ->
            AnalysisRequestParts(capacity = capacityPlan(input.sha256, resources.semanticSha256, "20", "0.05"))
        }
        run("capacity-wide") { input, resources ->
            AnalysisRequestParts(capacity = capacityPlan(input.sha256, resources.semanticSha256, "20.10", "0.1000"))
        }
        run("all-plans") { input, resources ->
            AnalysisRequestParts(
                diagnostics = diagnosticPlan(resources.semanticSha256),
                capacity = capacityPlan(input.sha256, resources.semanticSha256, "20", "0.05"),
                trend = trendPlan(resources.semanticSha256),
            )
        }

        val expected =
            setOf(
                "correlation_pair",
                "anomaly_check",
                "window_metric_summary",
                "diagnostic_summary",
                "correlation_headline_selection",
                "trend_check",
                "trend_summary",
                "capacity_summary",
                "capacity_knee_diagnostic",
            )
        assertTrue(seenTypes.containsAll(expected), "the runs do not reach: ${expected - seenTypes}")
        assertTrue(lines.none { "THROWS" in it }, lines.joinToString("\n"))

        val actual = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("LTV_UPDATE_DERIVED_EVIDENCE") == "1") Files.writeString(snapshotFile, actual)
        assertEquals(Files.readString(snapshotFile).replace("\r\n", "\n"), actual)
    }

    private data class AcceptedInputRef(
        val sha256: String,
    )

    private data class AnalysisRequestParts(
        val diagnostics: DiagnosticValidation.Valid? = null,
        val capacity: CapacityPlanValidation.Valid? = null,
        val trend: TrendPlanValidation.Valid? = null,
    )
}

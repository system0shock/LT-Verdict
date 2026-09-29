package io.ltverdict.core

import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.UtcLoadCell
import io.ltverdict.metrics.UtcLoadMetrics
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.Random

class DiagnosticAnalysisTest {
    @Test
    fun `rank evidence handles ties monotone direction and a fully explained target`() {
        val increasing = List(40) { BigDecimal.valueOf((it / 2 + 1).toLong()) }
        val decreasing = increasing.reversed()
        val resources =
            resources(
                1_000,
                listOf(window("evaluation", 0, 40_000)),
                mapOf("cpu" to increasing, "reversed" to decreasing, "driver" to increasing, "target" to increasing),
            )
        val plan =
            plan(
                resources,
                pairs =
                    """
                    [
                      {"id":"same","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host"},
                      {"id":"reverse","resource_series_id":"reversed","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"expected_sign":"negative","min_resource_delta":1,"min_load_delta":1,"topology_basis":"host"},
                      {"id":"controlled","resource_series_id":"driver","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}
                    ]
                    """.trimIndent(),
            )
        val load = loadMetrics("evaluation", 0, 1_000, increasing)

        val result = evaluateDiagnostics(plan, resources, resources.snapshot.windows, load, windowMetrics(resources.snapshot.windows))
        val pairs = result.evidence.filter { it.string("type") == "correlation_pair" }.associateBy { it.string("pair_id") }

        assertEquals("1", pairs.getValue("same").string("raw_rho"))
        assertEquals("-1", pairs.getValue("reverse").string("raw_rho"))
        assertEquals(
            "1",
            pairs
                .getValue("same")
                .getValue("lag_profile")
                .jsonArray
                .single()
                .jsonObject
                .string("rho"),
        )
        assertEquals(JsonNull, pairs.getValue("controlled")["partial_rho"])
        assertEquals("INSUFFICIENT_DATA", pairs.getValue("controlled").string("status"))
        assertTrue(pairs.getValue("controlled").strings("reasons").contains("NO_RESIDUAL_VARIATION"))
        assertEquals("NOT_ESTIMATED", pairs.getValue("same").string("uncertainty"))
    }

    @Test
    fun `genuine partial candidate remains raw evidence without a calibrated headline`() {
        val values = List(40) { BigDecimal.valueOf((it + 1).toLong()) }
        val resources =
            resources(
                1_000,
                listOf(window("evaluation", 0, 40_000)),
                mapOf(
                    "cpu" to values,
                    "target" to List(40) { BigDecimal.ONE },
                    "driver" to List(40) { BigDecimal.valueOf((it % 3).toLong()) },
                ),
            )
        val plan =
            plan(
                resources,
                pairs =
                    """[{"id":"partial","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"},{"meaning":"other","series_id":"driver"}]}]""",
            )

        val result =
            evaluateDiagnostics(
                plan,
                resources,
                resources.snapshot.windows,
                loadMetrics("evaluation", 0, 1_000, values),
                windowMetrics(resources.snapshot.windows),
            )
        val pair = result.evidence.single { it.string("type") == "correlation_pair" }
        val selection = result.evidence.single { it.string("type") == "correlation_headline_selection" }

        assertEquals("CANDIDATE", pair.string("status"))
        assertEquals("1", pair.string("raw_rho"))
        assertEquals("1", pair.string("partial_rho"))
        assertEquals("UNAVAILABLE", selection.string("status"))
        assertEquals(listOf("GENUINE_PARTIAL_UNCALIBRATED"), selection.strings("reasons"))
        assertEquals(JsonNull, selection["p_value_b10"])
        assertTrue(result.findings.none { it.string("type") == "correlation_candidate" })
    }

    @Test
    fun `all-null lag profile falls back to zero lag and states abstention`() {
        val values = listOf(BigDecimal.ZERO) + List(30) { BigDecimal.ONE } + listOf(BigDecimal("2"))
        val windows = listOf(window("evaluation", 0, 32_000))
        val resources = resources(1_000, windows, mapOf("cpu" to values))
        val plan =
            plan(
                resources,
                pairs =
                    """[{"id":"flat-anchor","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"max_lag_ms":1000,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host"}]""",
            )

        val pair =
            evaluateDiagnostics(plan, resources, windows, loadMetrics("evaluation", 0, 1_000, values), windowMetrics(windows))
                .evidence
                .single { it.string("type") == "correlation_pair" }

        assertEquals("1", pair.string("raw_rho"))
        assertEquals("0", pair.getValue("best_lag_ms").jsonPrimitive.content)
        assertEquals("1", pair.string("best_lag_rho"))
        assertTrue(pair.getValue("lag_profile").jsonArray.all { it.jsonObject["rho"] == JsonNull })
        assertTrue(pair.strings("reasons").contains("LAG_NOT_EVALUABLE"))
    }

    @Test
    fun `explicit reference anomaly emits one inclusive five-cell episode`() {
        val reference = List(40) { listOf("99", "100", "101") }.flatten().map(::BigDecimal)
        val values = reference + List(5) { BigDecimal("160") }
        val windows = listOf(window("reference", 0, 600_000), window("evaluation", 600_000, 625_000))
        val resources = resources(5_000, windows, mapOf("cpu" to values))
        val plan =
            plan(
                resources,
                anomalies =
                    """[{"id":"cpu-episode","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":15000,"z_threshold":3.5}]""",
            )

        val result = evaluateDiagnostics(plan, resources, windows, emptyLoad(windows, 5_000), windowMetrics(windows))
        val check = result.evidence.single { it.string("type") == "anomaly_check" }
        val episode = result.findings.single { it.string("type") == "anomaly_episode" }

        assertEquals("100", check.string("reference_median"))
        assertEquals(
            25_000,
            episode
                .getValue("duration_ms")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("160", episode.string("observed_min"))
        assertEquals("160", episode.string("observed_max"))
        assertEquals(check.string("id"), episode.string("evidence_id"))
        assertEquals(1, result.findings.size)
    }

    @Test
    fun `evaluation gaps short spikes zero MAD and sign reversal stay explicit`() {
        val reference = List(30) { BigDecimal("100") } + listOf(null)
        val evaluation = listOf("130", "130", null, "130", "130", "70", "70", "70").map { it?.let(::BigDecimal) }
        val values = reference + evaluation
        val windows = listOf(window("reference", 0, 155_000), window("evaluation", 155_000, 195_000))
        val resources = resources(5_000, windows, mapOf("cpu" to values))
        val plan =
            plan(
                resources,
                anomalies =
                    """[{"id":"cpu-episode","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"either","min_abs_delta":20,"min_duration_ms":15000}]""",
            )

        val result = evaluateDiagnostics(plan, resources, windows, emptyLoad(windows, 5_000), windowMetrics(windows))
        val check = result.evidence.single { it.string("type") == "anomaly_check" }
        val episode = result.findings.single { it.string("type") == "anomaly_episode" }

        assertEquals(
            2,
            check
                .getValue("suppressed_short_episodes")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals("decrease", episode.string("direction"))
        assertEquals(
            15_000,
            episode
                .getValue("duration_ms")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(check.strings("reasons").containsAll(listOf("REFERENCE_GAPS", "EVALUATION_GAPS", "ZERO_MAD")))
    }

    @Test
    fun `all missing evaluation cells are insufficient rather than clean no change`() {
        val values: List<BigDecimal?> = List(30) { BigDecimal("100") } + List(5) { null }
        val windows = listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 35_000))
        val resources = resources(1_000, windows, mapOf("cpu" to values))
        val plan =
            plan(
                resources,
                anomalies =
                    """[{"id":"missing","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":1000}]""",
            )

        val result = evaluateDiagnostics(plan, resources, windows, emptyLoad(windows, 1_000), windowMetrics(windows))
        val check = result.evidence.single { it.string("type") == "anomaly_check" }

        assertEquals("INSUFFICIENT_DATA", check.string("status"))
        assertTrue(check.strings("reasons").contains("NO_EVALUATION_OBSERVATIONS"))
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `episode limit stops scanning and returns no partial diagnostic evidence`() {
        val evaluation: List<BigDecimal?> = List(4_001) { if (it % 2 == 0) BigDecimal("160") else BigDecimal("100") }
        val values: List<BigDecimal?> = List(30) { BigDecimal("100") } + evaluation
        val windows = listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 4_031_000))
        val resources = resources(1_000, windows, mapOf("cpu" to values))
        val plan =
            plan(
                resources,
                anomalies =
                    """[{"id":"many","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":1000}]""",
            )
        var cancellationChecks = 0

        val result =
            evaluateDiagnostics(plan, resources, windows, emptyLoad(windows, 1_000), windowMetrics(windows)) {
                cancellationChecks++
            }

        val summary = result.evidence.single()
        assertEquals("diagnostic_summary", summary.string("type"))
        assertEquals("LIMIT_EXCEEDED", summary.string("status"))
        assertEquals(listOf("DIAGNOSTIC_EPISODE_LIMIT_EXCEEDED"), summary.strings("reasons"))
        assertTrue(result.findings.isEmpty())
        assertTrue(cancellationChecks < 3_000, "episode overflow scanned $cancellationChecks cells")
    }

    @Test
    fun `fixed-seed synthetic report records descriptive positive and null rates`() {
        var positiveCandidates = 0
        var nullCandidates = 0
        repeat(20) { seed ->
            val random = Random(seed.toLong())
            val x = List(40) { BigDecimal.valueOf(random.nextInt(10_000).toLong()) }
            val positiveY = x.map { it.add(BigDecimal.valueOf(random.nextInt(3).toLong())) }
            val nullY = List(40) { BigDecimal.valueOf(random.nextInt(10_000).toLong()) }
            if (pairStatus(x, positiveY) == "CANDIDATE") positiveCandidates++
            if (pairStatus(x, nullY) == "CANDIDATE") nullCandidates++
        }

        assertEquals(20, positiveCandidates)
        assertTrue(nullCandidates <= 2, "observed null candidates=$nullCandidates/20; descriptive fixture only")
    }

    private fun pairStatus(
        x: List<BigDecimal>,
        y: List<BigDecimal>,
    ): String {
        val windows = listOf(window("evaluation", 0, 40_000))
        val resources = resources(1_000, windows, mapOf("cpu" to x, "target" to List(40) { BigDecimal.ONE }))
        val plan =
            plan(
                resources,
                pairs =
                    """[{"id":"pair","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_abs_effect":0.8,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}]""",
            )
        return evaluateDiagnostics(plan, resources, windows, loadMetrics("evaluation", 0, 1_000, y), windowMetrics(windows))
            .evidence
            .single { it.string("type") == "correlation_pair" }
            .string("status")
    }

    private fun plan(
        resources: ResourceValidation.Valid,
        pairs: String = "[]",
        anomalies: String = "[]",
    ): DiagnosticValidation.Valid {
        val json =
            """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"${resources.semanticSha256}","pairs":$pairs,"anomalies":$anomalies}"""
        return validateDiagnosticPlan(ByteArrayInputStream(json.encodeToByteArray())) as DiagnosticValidation.Valid
    }

    private fun resources(
        step: Long,
        windows: List<ResourceWindowV1>,
        values: Map<String, List<BigDecimal?>>,
    ): ResourceValidation.Valid {
        val pointCount = values.values.first().size
        require(values.values.all { it.size == pointCount })
        val series =
            values.entries.joinToString(",") { (id, samples) ->
                val unit = if (id == "target") "requests/s" else "ratio"
                val metric = if (id == "target") "target_rps" else "cpu_used"
                val encoded = samples.joinToString(",") { it?.toPlainString() ?: "null" }
                """{"id":"$id","metric":"$metric","unit":"$unit","entity":"host","role":"system","aggregation":"interval_mean","values":[$encoded]}"""
            }
        val encodedWindows =
            windows.joinToString(",") {
                """{"id":"${it.id}","from_epoch_ms":${it.fromEpochMillis},"to_epoch_ms":${it.toEpochMillis}}"""
            }
        val json =
            """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${"0".repeat(
                64,
            )}","start_epoch_ms":0,"step_ms":$step,"point_count":$pointCount,"series":[$series],"windows":[$encodedWindows]}"""
        return validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray())) as ResourceValidation.Valid
    }

    private fun loadMetrics(
        windowId: String,
        start: Long,
        step: Long,
        values: List<BigDecimal>,
    ): UtcLoadMetrics =
        UtcLoadMetrics(
            mapOf(
                windowId to
                    values.mapIndexed { index, value ->
                        UtcLoadCell(
                            fromEpochMillis = start + index * step,
                            toEpochMillis = start + (index + 1) * step,
                            sampleCount = 20,
                            errorCount = 0,
                            errorRate = ExactRatio(0, 20),
                            throughputRps = ExactRatio(20_000, step),
                            responseTimeP95Millis = value.longValueExact(),
                        )
                    },
            ),
        )

    private fun emptyLoad(
        windows: List<ResourceWindowV1>,
        step: Long,
    ): UtcLoadMetrics =
        UtcLoadMetrics(
            windows.associate { window ->
                window.id to
                    generateSequence(window.fromEpochMillis) { it + step }
                        .takeWhile { it < window.toEpochMillis }
                        .map { start -> UtcLoadCell(start, start + step, 0, 0, null, ExactRatio(0, step), null) }
                        .toList()
            },
        )

    private fun windowMetrics(windows: List<ResourceWindowV1>): Map<String, NormalizedMetrics> =
        windows.associate { window ->
            val duration = window.toEpochMillis - window.fromEpochMillis
            window.id to
                NormalizedMetrics(
                    overall = MetricSummary(0, 0, null, ExactRatio(0, duration), LatencySummary(0, 0, 0, 0)),
                    transactions = emptyList(),
                    oneSecondBuckets = emptyList(),
                    rollups = emptyMap(),
                )
        }

    private fun window(
        id: String,
        from: Long,
        to: Long,
    ) = ResourceWindowV1(id, from, to)

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }
}

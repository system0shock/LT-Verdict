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
        // The correlation layer works on first differences (ADR 0022, D4) and keeps control levels: the steps of
        // "driver" equal the control levels, so the control explains the target completely.
        val driver = walk(increasing.drop(1).map { it.toLong() })
        val resources =
            resources(
                1_000,
                listOf(window("evaluation", 0, 40_000)),
                mapOf("cpu" to increasing, "reversed" to decreasing, "driver" to driver, "target" to increasing),
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
        val values = walk(steps(39))
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
        assertEquals("0.05", selection.string("alpha"))
        assertEquals(
            1,
            selection
                .getValue("family_count")
                .jsonPrimitive.content
                .toInt(),
        )
        assertTrue(result.findings.none { it.string("type") == "correlation_candidate" })
    }

    @Test
    fun `two stages are two independent families that share the level`() {
        val windows = listOf(window("w1", 0, 60_000), window("w2", 60_000, 120_000))
        val rising = walk(steps(59))
        val random = Random(3)
        val noise = List(60) { BigDecimal.valueOf(random.nextInt(100) + 1L) }
        val resources = resources(1_000, windows, mapOf("cpu" to rising + noise, "target" to List(120) { BigDecimal.ONE }))
        val plan = plan(resources, pairs = stagePairs("w1", "w2"))
        val noiseOutcome = List(60) { BigDecimal.valueOf(random.nextInt(100) + 1L) }
        val load = mergedLoad(loadMetrics("w1", 0, 1_000, rising), loadMetrics("w2", 60_000, 1_000, noiseOutcome))

        val result = evaluateDiagnostics(plan, resources, windows, load, windowMetrics(windows))
        val selections =
            result.evidence
                .filter {
                    it.string(
                        "type",
                    ) == "correlation_headline_selection"
                }.associateBy { it.string("window_id") }

        assertEquals(setOf("w1", "w2"), selections.keys)
        assertTrue(
            selections.values.none {
                it.strings("reasons").any { reason ->
                    reason.endsWith("_FAMILY_UNSUPPORTED") ||
                        reason.endsWith("_MISMATCH")
                }
            },
        )
        assertEquals("SELECTED", selections.getValue("w1").string("status"))
        assertEquals("NOT_SELECTED", selections.getValue("w2").string("status"))
        selections.values.forEach {
            assertEquals("0.025", it.string("alpha"))
            assertEquals(
                2,
                it
                    .getValue("family_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                1,
                it
                    .getValue("family_hypotheses")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }
        val findings = result.findings.filter { it.string("type") == "correlation_candidate" }
        assertEquals(listOf("w1"), findings.map { it.string("window_id") })
    }

    @Test
    fun `two outcomes in one window are two families`() {
        val windows = listOf(window("evaluation", 0, 60_000))
        val rising = walk(steps(59))
        val resources = resources(1_000, windows, mapOf("cpu" to rising, "target" to List(60) { BigDecimal.ONE }))
        val plan =
            plan(
                resources,
                pairs =
                    """[
                      {"id":"latency","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_abs_effect":0.5,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]},
                      {"id":"errors","resource_series_id":"cpu","load_metric":"error_rate","window_ids":["evaluation"],"min_abs_effect":0.5,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}
                    ]""",
            )

        val result =
            evaluateDiagnostics(plan, resources, windows, loadMetrics("evaluation", 0, 1_000, rising), windowMetrics(windows))
        val selections =
            result.evidence
                .filter {
                    it.string(
                        "type",
                    ) == "correlation_headline_selection"
                }.associateBy { it.string("pair_id") }

        assertEquals("SELECTED", selections.getValue("latency").string("status"))
        assertEquals(listOf("PAIR_NOT_EVALUABLE"), selections.getValue("errors").strings("reasons"))
        selections.values.forEach {
            assertEquals("0.025", it.string("alpha"))
            assertEquals(
                2,
                it
                    .getValue("family_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                1,
                it
                    .getValue("family_hypotheses")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }
    }

    @Test
    fun `family count is declared by the plan so a short stage does not change the level of the others`() {
        val windows = listOf(window("w1", 0, 60_000), window("short", 60_000, 80_000), window("w3", 80_000, 140_000))
        val rising = walk(steps(59))
        val shortRising = walk(steps(19))
        val resources =
            resources(
                1_000,
                windows,
                mapOf("cpu" to rising + shortRising + rising, "target" to List(140) { BigDecimal.ONE }),
            )
        val plan = plan(resources, pairs = stagePairs("w1", "short", "w3"))
        val load =
            mergedLoad(
                loadMetrics("w1", 0, 1_000, rising),
                loadMetrics("short", 60_000, 1_000, shortRising),
                loadMetrics("w3", 80_000, 1_000, rising),
            )

        val result = evaluateDiagnostics(plan, resources, windows, load, windowMetrics(windows))
        val selections =
            result.evidence
                .filter {
                    it.string(
                        "type",
                    ) == "correlation_headline_selection"
                }.associateBy { it.string("window_id") }

        assertEquals("UNAVAILABLE", selections.getValue("short").string("status"))
        listOf("w1", "w3").forEach {
            assertEquals("SELECTED", selections.getValue(it).string("status"))
            assertEquals("0.016666666667", selections.getValue(it).string("alpha"))
        }
        selections.values.forEach {
            assertEquals(
                3,
                it
                    .getValue("family_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }
    }

    private fun stagePairs(vararg windowIds: String): String =
        """[{"id":"cpu-latency","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":[${windowIds.joinToString(
            ",",
        ) {
            "\"$it\""
        }}],"min_abs_effect":0.5,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}]"""

    private fun mergedLoad(vararg parts: UtcLoadMetrics) = UtcLoadMetrics(parts.fold(emptyMap()) { merged, part -> merged + part.windows })

    @Test
    fun `rho and lag are computed on first differences and agree with the oracle`() {
        // tools/correlation_oracle.py (H2): steps d = 17 i mod 41, the outcome steps are d rotated by two cells.
        val d = steps(41)
        val rotated = d.takeLast(2) + d.dropLast(2)

        val result = diagnostics(mapOf("cpu" to walk(d)), walk(rotated), "[${pairJson("rotated", maxLagMs = 4_000)}]")
        val pair = result.pair("rotated")
        val selection = result.selection("rotated")

        assertEquals(0.15, pair.string("raw_rho").toDouble(), 1e-12)
        assertEquals("2000", pair.getValue("best_lag_ms").jsonPrimitive.content)
        assertEquals("1", pair.string("best_lag_rho"))
        assertEquals("33", pair.getValue("lag_used_cells").jsonPrimitive.content)
        assertEquals("CANDIDATE", pair.string("status"))
        assertEquals("mbb-lag-max-holm.v2", selection.string("method"))
        assertEquals("first_difference", selection.string("representation"))
        assertEquals("42", selection.string("source_cells"))
        assertEquals("41", selection.string("analysed_points"))
    }

    @Test
    fun `ties after differencing use average ranks`() {
        val x = List(60) { (it % 3).toLong() }
        val y = List(60) { (it % 3 + if (it % 5 == 0) 1 else 0).toLong() }

        val pair = diagnostics(mapOf("cpu" to walk(x)), walk(y), "[${pairJson("ties", maxLagMs = 2_000)}]").pair("ties")

        assertEquals(0.9109357395385403, pair.string("raw_rho").toDouble(), 1e-12)
        assertEquals("0", pair.getValue("best_lag_ms").jsonPrimitive.content)
        assertEquals(0.9178000303541187, pair.string("best_lag_rho").toDouble(), 1e-12)
    }

    @Test
    fun `a gap breaks the series and no difference is taken across it`() {
        val levels = walk(steps(79))
        val withGap = levels.mapIndexed { index, value -> if (index == 10) null else value }

        val result = diagnostics(mapOf("cpu" to withGap), levels, "[${pairJson("gap")}]")
        val selection = result.selection("gap")

        assertTrue(result.pair("gap").strings("reasons").contains("MISSING_CELLS"))
        assertEquals("79", result.pair("gap").string("paired_cells"))
        assertEquals("69", selection.string("source_cells"))
        assertEquals("68", selection.string("analysed_points"))
        assertEquals("SELECTED", selection.string("status"))
    }

    @Test
    fun `a longest run below thirty one cells is unavailable and not filled`() {
        val levels = walk(steps(60))
        val withGap = levels.mapIndexed { index, value -> if (index == 30) null else value }

        val result = diagnostics(mapOf("cpu" to withGap), levels, "[${pairJson("short")}]")
        val selection = result.selection("short")

        assertEquals("INSUFFICIENT_DATA", result.pair("short").string("status"))
        assertEquals("UNAVAILABLE", selection.string("status"))
        assertEquals(listOf("PAIR_NOT_EVALUABLE"), selection.strings("reasons"))
        assertEquals("30", selection.string("source_cells"))
        assertEquals("29", selection.string("analysed_points"))
    }

    @Test
    fun `plateau and counter resources are not evaluable but stay in the family size`() {
        val signal = walk(steps(60))
        val counter = List(61) { BigDecimal.valueOf(7L + 3L * it) }
        val plateau = List(61) { BigDecimal.valueOf(7) }
        val pairs = "[${pairJson("signal")},${pairJson("counter", "counter")},${pairJson("plateau", "plateau")}]"

        val result = diagnostics(mapOf("cpu" to signal, "counter" to counter, "plateau" to plateau), signal, pairs)

        listOf("counter", "plateau").forEach {
            assertEquals("INSUFFICIENT_DATA", result.pair(it).string("status"))
            assertTrue(result.pair(it).strings("reasons").contains("NO_RANK_VARIATION"))
            assertEquals("UNAVAILABLE", result.selection(it).string("status"))
            assertEquals(listOf("PAIR_NOT_EVALUABLE"), result.selection(it).strings("reasons"))
        }
        val signalSelection = result.selection("signal")
        assertEquals("3", signalSelection.string("family_hypotheses"))
        // The two unavailable hypotheses count as p = 1 in the one Holm of the family.
        assertEquals(
            3 * signalSelection.string("max_p_value").toDouble(),
            signalSelection.string("holm_adjusted_p_value").toDouble(),
            1e-12,
        )
    }

    @Test
    fun `effect threshold follows differences and not levels`() {
        // Both levels climb monotonically (rank correlation of levels 1), the steps are unrelated (0.0271).
        val resource = walk(steps(60).map { it + 1 })
        val outcome = walk(steps(60, 13, 37).map { it + 1 })

        val result = diagnostics(mapOf("cpu" to resource), outcome, "[${pairJson("trend")}]")

        assertEquals("BELOW_EFFECT", result.pair("trend").string("status"))
        assertEquals(0.027106675234430323, result.pair("trend").string("raw_rho").toDouble(), 1e-9)
        assertEquals("false", result.selection("trend").string("selected"))
    }

    @Test
    fun `range gates stay on levels`() {
        // The steps of the resource equal the outcome steps, but its levels move by far less than min_resource_delta.
        val tiny = steps(60).map { BigDecimal.valueOf(it).movePointLeft(5) }
        val resource = tiny.runningFold(BigDecimal.ONE) { level, step -> level + step }

        val result = diagnostics(mapOf("cpu" to resource), walk(steps(60)), "[${pairJson("tiny")}]")

        assertEquals("BELOW_EFFECT", result.pair("tiny").string("status"))
        assertEquals("1", result.pair("tiny").string("raw_rho"))
        assertTrue(result.pair("tiny").strings("reasons").contains("RESOURCE_DELTA_BELOW_MINIMUM"))
        assertEquals("NOT_SELECTED", result.selection("tiny").string("status"))
        assertEquals(listOf("MATERIALITY_NOT_MET"), result.selection("tiny").strings("reasons"))
    }

    @Test
    fun `a varying control stays unavailable because controls are kept on levels`() {
        // A linear ramp has constant differences: differencing the control would drop it as constant (ADR 0022, D2).
        val levels = walk(steps(59))
        val ramp = List(60) { BigDecimal.valueOf(it + 1L) }

        val result = diagnostics(mapOf("cpu" to levels), levels, "[${pairJson("ramp")}]", target = ramp)

        assertEquals(listOf("GENUINE_PARTIAL_UNCALIBRATED"), result.selection("ramp").strings("reasons"))
        assertEquals("UNAVAILABLE", result.selection("ramp").string("status"))
    }

    @Test
    fun `strong common drift does not select any of sixteen hypotheses`() {
        // Regression of the family pilot (ADR 0022, section 2): a shared linear drift selected all 16 on levels.
        fun drifting(seed: Long) = Random(seed).let { random -> List(120) { BigDecimal.valueOf(1_000L + 5L * it + random.nextInt(30)) } }
        val series = (0 until 16).associate { "r$it" to drifting(it + 1L) }
        val pairs = (0 until 16).joinToString(",", "[", "]") { pairJson("h$it", "r$it", minAbsEffect = "0.05") }

        val result = diagnostics(series, drifting(99), pairs)

        (0 until 16).forEach {
            assertEquals("NOT_SELECTED", result.selection("h$it").string("status"))
            assertEquals("16", result.selection("h$it").string("family_hypotheses"))
        }
        assertTrue(result.findings.none { it.string("type") == "correlation_candidate" })
    }

    @Test
    fun `upper limit follows differences`() {
        listOf(1_921, 1_922).forEach { cells ->
            val levels = walk(steps(cells - 1))

            val selection = diagnostics(mapOf("cpu" to levels), levels, "[${pairJson("long")}]").selection("long")

            assertEquals((cells - 1).toString(), selection.string("analysed_points"))
            if (cells == 1_921) {
                assertEquals("SELECTED", selection.string("status"))
            } else {
                assertEquals(listOf("OBSERVATION_COUNT_UNSUPPORTED"), selection.strings("reasons"))
            }
        }
    }

    @Test
    fun `all-null lag profile falls back to zero lag and states abstention`() {
        // Steps 1, 30 zeros, 1: the anchors of lag 1 are constant, so every lag of the profile is null.
        val values = listOf(BigDecimal.ZERO) + List(31) { BigDecimal.ONE } + listOf(BigDecimal("2"))
        val windows = listOf(window("evaluation", 0, 33_000))
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

    @Test
    fun `window metric summary publishes null latency for an empty window and keeps throughput exact`() {
        val windows = listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 35_000))
        val resources = resources(1_000, windows, mapOf("cpu" to List(35) { BigDecimal("100") }))
        val plan = plan(resources, anomalies = ANOMALY_PLAN)
        val metrics = windowMetrics(windows)

        val summaries = windowSummaries(plan, resources, windows, metrics)

        listOf("reference" to 30_000L, "evaluation" to 5_000L).forEach { (id, duration) ->
            val summary = summaries.getValue(id)
            assertEquals(JsonNull, summary["error_rate_ratio"])
            assertEquals("0", summary.getValue("sample_count").jsonPrimitive.content)
            assertEquals("0", summary.getValue("error_count").jsonPrimitive.content)
            assertEquals("0", summary.getValue("throughput_rps").jsonObject.string("numerator"))
            assertEquals(duration.toString(), summary.getValue("throughput_rps").jsonObject.string("denominator"))
            assertEquals(
                mapOf("p50" to JsonNull, "p95" to JsonNull, "p99" to JsonNull, "max" to JsonNull),
                summary.getValue("latency_ms").jsonObject,
            )
        }
    }

    @Test
    fun `window metric summary keeps numeric latency when the window has samples including a real zero`() {
        val windows = listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 35_000))
        val resources = resources(1_000, windows, mapOf("cpu" to List(35) { BigDecimal("100") }))
        val plan = plan(resources, anomalies = ANOMALY_PLAN)
        val metrics =
            mapOf(
                "reference" to windowMetric(MetricSummary(3, 1, ExactRatio(1, 3), ExactRatio(3_000, 30_000), LatencySummary(5, 9, 10, 12))),
                "evaluation" to windowMetric(MetricSummary(1, 0, ExactRatio(0, 1), ExactRatio(1_000, 5_000), LatencySummary(0, 0, 0, 0))),
            )

        val summaries = windowSummaries(plan, resources, windows, metrics)

        assertEquals(
            mapOf("p50" to "5", "p95" to "9", "p99" to "10", "max" to "12"),
            summaries
                .getValue("reference")
                .getValue("latency_ms")
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            mapOf("p50" to "0", "p95" to "0", "p99" to "0", "max" to "0"),
            summaries
                .getValue("evaluation")
                .getValue("latency_ms")
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(
            false,
            summaries
                .getValue("evaluation")
                .getValue("latency_ms")
                .jsonObject.values
                .any { it is JsonNull },
        )
    }

    private fun steps(
        count: Int,
        multiplier: Int = 17,
        modulus: Int = 41,
    ): List<Long> = List(count) { ((it * multiplier) % modulus).toLong() }

    // Levels whose first differences are exactly [steps]: the correlation layer works on differences (ADR 0022, D4).
    private fun walk(
        steps: List<Long>,
        start: Long = 1_000,
    ): List<BigDecimal> = steps.runningFold(start) { level, step -> level + step }.map { BigDecimal.valueOf(it) }

    private fun diagnostics(
        series: Map<String, List<BigDecimal?>>,
        outcome: List<BigDecimal>,
        pairs: String,
        target: List<BigDecimal> = List(outcome.size) { BigDecimal.ONE },
    ): DiagnosticEvaluation {
        val windows = listOf(window("evaluation", 0, outcome.size * 1_000L))
        val resources = resources(1_000, windows, series + ("target" to target))
        return evaluateDiagnostics(
            plan(resources, pairs = pairs),
            resources,
            windows,
            loadMetrics("evaluation", 0, 1_000, outcome),
            windowMetrics(windows),
        )
    }

    private fun pairJson(
        id: String,
        resourceId: String = "cpu",
        maxLagMs: Long = 0,
        minAbsEffect: String = "0.5",
    ) =
        """{"id":"$id","resource_series_id":"$resourceId","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"max_lag_ms":$maxLagMs,"min_abs_effect":$minAbsEffect,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}"""

    private fun DiagnosticEvaluation.pair(id: String) =
        evidence.single {
            it.string("type") == "correlation_pair" &&
                it.string("pair_id") == id
        }

    private fun DiagnosticEvaluation.selection(id: String) =
        evidence.single { it.string("type") == "correlation_headline_selection" && it.string("pair_id") == id }

    private fun windowSummaries(
        plan: DiagnosticValidation.Valid,
        resources: ResourceValidation.Valid,
        windows: List<ResourceWindowV1>,
        metrics: Map<String, NormalizedMetrics>,
    ): Map<String, JsonObject> =
        evaluateDiagnostics(plan, resources, windows, emptyLoad(windows, 1_000), metrics)
            .evidence
            .filter { it.string("type") == "window_metric_summary" }
            .associateBy { it.string("window_id") }

    private fun windowMetric(summary: MetricSummary) = NormalizedMetrics(summary, emptyList(), emptyList(), emptyMap())

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

    private companion object {
        const val ANOMALY_PLAN =
            """[{"id":"missing","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":1000}]"""
    }
}

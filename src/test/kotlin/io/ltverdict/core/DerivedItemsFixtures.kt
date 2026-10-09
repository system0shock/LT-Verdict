package io.ltverdict.core

import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.UtcLoadCell
import io.ltverdict.metrics.UtcLoadMetrics
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.Random

/**
 * Inputs shared by the W2.1 slice 2b snapshot and equivalence tests: diagnostics, capacity and trend cases. A case is a plain
 * value, so the current code and the frozen copy of the old code are fed the very same inputs.
 */
internal object DerivedItemsFixtures {
    // ---------------------------------------------------------------- diagnostics

    data class DiagnosticCase(
        val name: String,
        val plan: DiagnosticValidation.Valid,
        val resources: ResourceValidation.Valid,
        val windows: List<ResourceWindowV1>,
        val load: UtcLoadMetrics,
        val metrics: Map<String, NormalizedMetrics>,
    )

    data class SeriesSpec(
        val id: String,
        val values: List<BigDecimal?>,
        val metric: String = if (id == "target") "target_rps" else "cpu_used",
        val unit: String = if (id == "target") "requests/s" else "ratio",
        val labels: Map<String, String> = emptyMap(),
        val entity: String = "host",
    )

    fun window(
        id: String,
        from: Long,
        to: Long,
    ) = ResourceWindowV1(id, from, to)

    fun steps(
        count: Int,
        multiplier: Int = 17,
        modulus: Int = 41,
    ): List<Long> = List(count) { ((it * multiplier) % modulus).toLong() }

    fun walk(
        steps: List<Long>,
        start: Long = 1_000,
    ): List<BigDecimal> = steps.runningFold(start) { level, step -> level + step }.map { BigDecimal.valueOf(it) }

    private fun quoted(text: String) = JsonPrimitive(text).toString()

    fun resources(
        step: Long,
        windows: List<ResourceWindowV1>,
        series: List<SeriesSpec>,
    ): ResourceValidation.Valid {
        val pointCount = series.first().values.size
        val encodedSeries =
            series.joinToString(",") { spec ->
                val encoded = spec.values.joinToString(",") { it?.toPlainString() ?: "null" }
                val labels = spec.labels.entries.joinToString(",") { (key, value) -> "${quoted(key)}:${quoted(value)}" }
                """{"id":"${spec.id}","metric":"${spec.metric}","unit":"${spec.unit}","entity":${quoted(
                    spec.entity,
                )},"role":"system","aggregation":"interval_mean","labels":{$labels},"values":[$encoded]}"""
            }
        val encodedWindows =
            windows.joinToString(",") { """{"id":"${it.id}","from_epoch_ms":${it.fromEpochMillis},"to_epoch_ms":${it.toEpochMillis}}""" }
        val json =
            """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${"0".repeat(
                64,
            )}","start_epoch_ms":0,"step_ms":$step,"point_count":$pointCount,"series":[$encodedSeries],"windows":[$encodedWindows]}"""
        val validation = validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray()))
        check(validation is ResourceValidation.Valid) { "invalid resource snapshot: $validation in $json" }
        return validation
    }

    fun plan(
        resources: ResourceValidation.Valid,
        pairs: String = "[]",
        anomalies: String = "[]",
    ): DiagnosticValidation.Valid {
        val json =
            """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"${resources.semanticSha256}","pairs":$pairs,"anomalies":$anomalies}"""
        return validateDiagnosticPlan(ByteArrayInputStream(json.encodeToByteArray())) as DiagnosticValidation.Valid
    }

    fun loadCells(
        start: Long,
        step: Long,
        values: List<BigDecimal>,
        errorsEvery: Int = 0,
    ): List<UtcLoadCell> =
        values.mapIndexed { index, value ->
            val errors = if (errorsEvery > 0) (index % errorsEvery).toLong() else 0L
            UtcLoadCell(
                fromEpochMillis = start + index * step,
                toEpochMillis = start + (index + 1) * step,
                sampleCount = 20,
                errorCount = errors,
                errorRate = ExactRatio(errors, 20),
                throughputRps = ExactRatio(20_000 + value.toLong(), step),
                responseTimeP95Millis = value.longValueExact(),
            )
        }

    fun load(vararg parts: Pair<String, List<UtcLoadCell>>) = UtcLoadMetrics(mapOf(*parts))

    fun emptyLoad(
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

    fun emptyMetrics(windows: List<ResourceWindowV1>): Map<String, NormalizedMetrics> =
        windows.associate { window ->
            val duration = window.toEpochMillis - window.fromEpochMillis
            window.id to
                NormalizedMetrics(
                    MetricSummary(0, 0, null, ExactRatio(0, duration), LatencySummary(0, 0, 0, 0)),
                    emptyList(),
                    emptyList(),
                    emptyMap(),
                )
        }

    /** Windows with samples, errors, a real zero latency, a missing error rate and the largest counts. */
    fun richMetrics(windows: List<ResourceWindowV1>): Map<String, NormalizedMetrics> =
        windows.withIndex().associate { (index, window) ->
            val duration = window.toEpochMillis - window.fromEpochMillis
            val summary =
                when (index % 4) {
                    0 -> MetricSummary(120, 7, ExactRatio(7, 120), ExactRatio(120_000, duration), LatencySummary(5, 9, 10, 12))
                    1 -> MetricSummary(1, 0, ExactRatio(0, 1), ExactRatio(1_000, duration), LatencySummary(0, 0, 0, 0))
                    2 -> MetricSummary(0, 0, null, ExactRatio(0, duration), LatencySummary(0, 0, 0, 0))
                    else ->
                        MetricSummary(
                            Long.MAX_VALUE,
                            Long.MAX_VALUE - 1,
                            ExactRatio(Long.MAX_VALUE - 1, Long.MAX_VALUE),
                            ExactRatio(Long.MAX_VALUE, duration),
                            LatencySummary(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
                        )
                }
            window.id to NormalizedMetrics(summary, emptyList(), emptyList(), emptyMap())
        }

    private fun pairJson(
        id: String,
        resourceId: String = "cpu",
        windowIds: List<String> = listOf("evaluation"),
        loadMetric: String = "response_time_p95_ms",
        maxLagMs: Long = 0,
        minAbsEffect: String = "0.5",
        controls: String = """[{"meaning":"target_rps","series_id":"target"}]""",
        extra: String = "",
    ) = """{"id":"$id","resource_series_id":"$resourceId","load_metric":"$loadMetric","window_ids":[${windowIds.joinToString(
        ",",
    ) {
        "\"$it\""
    }}],"max_lag_ms":$maxLagMs,"min_abs_effect":$minAbsEffect,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host é","controls":$controls$extra}"""

    private fun pairCase(
        name: String,
        series: Map<String, List<BigDecimal?>>,
        outcome: List<BigDecimal>,
        pairs: String,
        target: List<BigDecimal> = List(outcome.size) { BigDecimal.ONE },
        labels: Map<String, String> = emptyMap(),
        errorsEvery: Int = 0,
        metrics: Boolean = false,
    ): DiagnosticCase {
        val windows = listOf(window("evaluation", 0, outcome.size * 1_000L))
        val specs = series.map { (id, values) -> SeriesSpec(id, values, labels = labels) } + SeriesSpec("target", target, labels = labels)
        val resources = resources(1_000, windows, specs)
        return DiagnosticCase(
            name,
            plan(resources, pairs = pairs),
            resources,
            windows,
            load("evaluation" to loadCells(0, 1_000, outcome, errorsEvery)),
            if (metrics) richMetrics(windows) else emptyMetrics(windows),
        )
    }

    private fun anomalyCase(
        name: String,
        values: List<BigDecimal?>,
        step: Long,
        windows: List<ResourceWindowV1>,
        anomalies: String,
        labels: Map<String, String> = emptyMap(),
        metrics: Boolean = false,
    ): DiagnosticCase {
        val resources = resources(step, windows, listOf(SeriesSpec("cpu", values, labels = labels)))
        return DiagnosticCase(
            name,
            plan(resources, anomalies = anomalies),
            resources,
            windows,
            emptyLoad(windows, step),
            if (metrics) richMetrics(windows) else emptyMetrics(windows),
        )
    }

    fun diagnosticCases(): List<DiagnosticCase> {
        val cases = mutableListOf<DiagnosticCase>()
        val increasing = List(40) { BigDecimal.valueOf((it / 2 + 1).toLong()) }
        cases +=
            pairCase(
                "ties-same-reverse-controlled",
                mapOf(
                    "cpu" to increasing,
                    "reversed" to increasing.reversed(),
                    "driver" to walk(increasing.drop(1).map { it.toLong() }),
                ),
                increasing,
                "[" +
                    pairJson("same", minAbsEffect = "0.5") + "," +
                    pairJson("reverse", "reversed", extra = ""","expected_sign":"negative"""") + "," +
                    pairJson("controlled", "driver") + "]",
                labels = mapOf("arm" to "A", "é\"k" to "x\\y"),
                metrics = true,
            )
        val rising = walk(steps(39))
        cases +=
            pairCase(
                "partial-two-controls",
                mapOf("cpu" to rising, "driver" to List(40) { BigDecimal.valueOf((it % 3).toLong()) }),
                rising,
                "[" +
                    pairJson(
                        "partial",
                        controls = """[{"meaning":"target_rps","series_id":"target"},{"meaning":"other","series_id":"driver"}]""",
                    ) +
                    "]",
            )
        val d = steps(41)
        cases +=
            pairCase(
                "rotated-lag",
                mapOf("cpu" to walk(d)),
                walk(d.takeLast(2) + d.dropLast(2)),
                "[" + pairJson("rotated", maxLagMs = 4_000, extra = ""","clock_alignment":"declared_aligned"""") + "]",
                errorsEvery = 3,
            )
        cases +=
            pairCase(
                "unknown-clock-lag",
                mapOf("cpu" to walk(d)),
                walk(d.takeLast(2) + d.dropLast(2)),
                "[" + pairJson("unknown", maxLagMs = 2_000) + "," + pairJson("errors", loadMetric = "error_rate") + "," +
                    pairJson("rps", loadMetric = "throughput_rps", controls = "[]") + "]",
                errorsEvery = 4,
            )
        val x = List(60) { (it % 3).toLong() }
        val y = List(60) { (it % 3 + if (it % 5 == 0) 1 else 0).toLong() }
        cases += pairCase("ties-average-ranks", mapOf("cpu" to walk(x)), walk(y), "[" + pairJson("ties", maxLagMs = 2_000) + "]")
        val levels = walk(steps(79))
        cases +=
            pairCase(
                "gap-breaks-series",
                mapOf("cpu" to levels.mapIndexed { index, value -> if (index == 10) null else value }),
                levels,
                "[" + pairJson("gap") + "]",
            )
        val levels60 = walk(steps(60))
        cases +=
            pairCase(
                "short-run-unavailable",
                mapOf("cpu" to levels60.mapIndexed { index, value -> if (index == 30) null else value }),
                levels60,
                "[" + pairJson("short") + "]",
            )
        val signal = walk(steps(60))
        cases +=
            pairCase(
                "counter-and-plateau",
                mapOf(
                    "cpu" to signal,
                    "counter" to List(61) { BigDecimal.valueOf(7L + 3L * it) },
                    "plateau" to List(61) { BigDecimal.valueOf(7) },
                ),
                signal,
                "[" + pairJson("signal") + "," + pairJson("counter", "counter") + "," + pairJson("plateau", "plateau") + "]",
            )
        cases +=
            pairCase(
                "differences-not-levels",
                mapOf("cpu" to walk(steps(60).map { it + 1 })),
                walk(steps(60, 13, 37).map { it + 1 }),
                "[" + pairJson("trend") + "]",
            )
        val tiny = steps(60).map { BigDecimal.valueOf(it).movePointLeft(5) }
        cases +=
            pairCase(
                "range-gates-on-levels",
                mapOf("cpu" to tiny.runningFold(BigDecimal.ONE) { level, step -> level + step }),
                walk(steps(60)),
                "[" + pairJson("tiny") + "]",
            )
        cases +=
            pairCase(
                "varying-control-ramp",
                mapOf("cpu" to walk(steps(59))),
                walk(steps(59)),
                "[" + pairJson("ramp") + "]",
                target = List(60) { BigDecimal.valueOf(it + 1L) },
            )
        val random = Random(7)

        fun drifting() = List(120) { BigDecimal.valueOf(1_000L + 5L * it + random.nextInt(30)) }
        cases +=
            pairCase(
                "common-drift-four-hypotheses",
                (0 until 4).associate { "r$it" to drifting() },
                drifting(),
                (0 until 4).joinToString(",", "[", "]") { pairJson("h$it", "r$it", minAbsEffect = "0.05") },
            )
        val flat = listOf(BigDecimal.ZERO) + List(31) { BigDecimal.ONE } + listOf(BigDecimal("2"))
        cases +=
            pairCase(
                "flat-anchor-all-null-lag",
                mapOf("cpu" to flat),
                flat,
                "[" + pairJson("flat-anchor", maxLagMs = 1000) + "]",
            )
        // achieved_rps as a control: the sensitivity without it is a number, or the raw rho when it is the only control
        val noisy = walk(steps(79, 11, 37))
        cases +=
            pairCase(
                "achieved-rps-controls",
                mapOf("cpu" to levels),
                noisy,
                "[" +
                    pairJson(
                        "achieved-and-target",
                        controls = """[{"meaning":"achieved_rps"},{"meaning":"target_rps","series_id":"target"}]""",
                    ) +
                    "," +
                    pairJson("achieved-only", "cpu", controls = """[{"meaning":"achieved_rps"}]""") + "," +
                    pairJson(
                        "achieved-const",
                        controls = """[{"meaning":"achieved_rps"},{"meaning":"target_rps","series_id":"target"}]""",
                        loadMetric = "error_rate",
                    ) +
                    "]",
                target = List(80) { BigDecimal.valueOf(it % 7L) },
                errorsEvery = 1,
            )
        val twoWindows = listOf(window("w1", 0, 60_000), window("w2", 60_000, 120_000))
        val noise = Random(3)
        val rising60 = walk(steps(59))
        val noisy60 = List(60) { BigDecimal.valueOf(noise.nextInt(100) + 1L) }
        val twoResources =
            resources(1_000, twoWindows, listOf(SeriesSpec("cpu", rising60 + noisy60), SeriesSpec("target", List(120) { BigDecimal.ONE })))
        cases +=
            DiagnosticCase(
                "two-stages-two-families",
                plan(twoResources, pairs = "[" + pairJson("cpu-latency", windowIds = listOf("w1", "w2")) + "]"),
                twoResources,
                twoWindows,
                load(
                    "w1" to loadCells(0, 1_000, rising60),
                    "w2" to loadCells(60_000, 1_000, List(60) { BigDecimal.valueOf(noise.nextInt(100) + 1L) }),
                ),
                richMetrics(twoWindows),
            )
        val shortWindows = listOf(window("w1", 0, 60_000), window("short", 60_000, 80_000), window("w3", 80_000, 140_000))
        val shortResources =
            resources(
                1_000,
                shortWindows,
                listOf(
                    SeriesSpec("cpu", rising60 + walk(steps(19)) + rising60),
                    SeriesSpec(
                        "target",
                        List(140) {
                            BigDecimal.ONE
                        },
                    ),
                ),
            )
        cases +=
            DiagnosticCase(
                "short-stage-keeps-family-count",
                plan(shortResources, pairs = "[" + pairJson("cpu-latency", windowIds = listOf("w1", "short", "w3")) + "]"),
                shortResources,
                shortWindows,
                load(
                    "w1" to loadCells(0, 1_000, rising60),
                    "short" to loadCells(60_000, 1_000, walk(steps(19))),
                    "w3" to loadCells(80_000, 1_000, rising60),
                ),
                richMetrics(shortWindows),
            )

        // anomalies
        val reference = List(40) { listOf("99", "100", "101") }.flatten().map(::BigDecimal)
        cases +=
            anomalyCase(
                "anomaly-episode-inclusive",
                reference + List(5) { BigDecimal("160") },
                5_000,
                listOf(window("reference", 0, 600_000), window("evaluation", 600_000, 625_000)),
                """[{"id":"cpu-episode","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":15000,"z_threshold":3.5}]""",
                labels = mapOf("arm" to "B"),
                metrics = true,
            )
        cases +=
            anomalyCase(
                "anomaly-gaps-short-zero-mad-reversal",
                List(30) { BigDecimal("100") } + listOf(null) +
                    listOf("130", "130", null, "130", "130", "70", "70", "70").map { it?.let(::BigDecimal) },
                5_000,
                listOf(window("reference", 0, 155_000), window("evaluation", 155_000, 195_000)),
                """[{"id":"cpu-episode","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"either","min_abs_delta":20,"min_duration_ms":15000}]""",
            )
        cases +=
            anomalyCase(
                "anomaly-evaluation-missing",
                List(30) { BigDecimal("100") } + List<BigDecimal?>(5) { null },
                1_000,
                listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 35_000)),
                """[{"id":"missing","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":1000}]""",
            )
        cases +=
            anomalyCase(
                "anomaly-reference-too-short",
                List(10) { BigDecimal("100") } + List(10) { BigDecimal("200") },
                1_000,
                listOf(window("reference", 0, 10_000), window("evaluation", 10_000, 20_000)),
                """[{"id":"short-ref","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"decrease","min_abs_delta":1,"min_duration_ms":1000}]""",
            )
        cases +=
            anomalyCase(
                "anomaly-episode-limit",
                List<BigDecimal?>(30) { BigDecimal("100") } + List(4_001) { if (it % 2 == 0) BigDecimal("160") else BigDecimal("100") },
                1_000,
                listOf(window("reference", 0, 30_000), window("evaluation", 30_000, 4_031_000)),
                """[{"id":"many","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":20,"min_duration_ms":1000}]""",
            )
        // a load signal: entity is "overall"
        val loadWindows = listOf(window("reference", 0, 40_000), window("evaluation", 40_000, 60_000))
        val loadResources = resources(1_000, loadWindows, listOf(SeriesSpec("cpu", List(60) { BigDecimal.ONE })))
        cases +=
            DiagnosticCase(
                "anomaly-load-signal",
                plan(
                    loadResources,
                    anomalies =
                        """[{"id":"p95-jump","signal":{"load_metric":"response_time_p95_ms"},"reference_window_id":"reference","window_id":"evaluation","direction":"either","min_abs_delta":50,"min_duration_ms":2000}]""",
                ),
                loadResources,
                loadWindows,
                load(
                    "reference" to loadCells(0, 1_000, List(40) { BigDecimal.valueOf(100L + it % 5) }),
                    "evaluation" to loadCells(40_000, 1_000, List(20) { if (it in 5..14) BigDecimal("400") else BigDecimal("100") }),
                ),
                richMetrics(loadWindows),
            )
        return cases
    }

    // ---------------------------------------------------------------- capacity

    data class CapacityCase(
        val name: String,
        val plan: CapacityPlanV1,
        val resources: ResourceSnapshotV1,
        val load: UtcLoadMetrics,
        val validity: io.ltverdict.ingest.RunValidity,
        val windowPolicy: PolicyEvaluation,
        val windowMetrics: Map<String, NormalizedMetrics>?,
    )

    private fun capacityStage(
        id: String,
        target: BigDecimal,
        from: Long,
        duration: Long = 300_000,
    ) = CapacityStageV1(id, target, from, from + duration, id)

    private fun capacityPlan(
        axis: CapacityLoadAxis,
        required: BigDecimal?,
        stages: List<CapacityStageV1>,
        guards: List<String> = listOf("guard"),
        tolerance: BigDecimal = BigDecimal("0.05"),
    ) = CapacityPlanV1("a", "b", axis, if (axis == CapacityLoadAxis.RPS) null else "achieved", tolerance, required, guards, stages)

    private fun capacityResources(
        axis: CapacityLoadAxis,
        windows: List<ResourceWindowV1>,
        values: List<BigDecimal?> = List(60) { BigDecimal.ONE },
        step: Long = 10_000,
    ): ResourceSnapshotV1 {
        val achieved =
            if (axis == CapacityLoadAxis.RPS) {
                emptyList()
            } else {
                listOf(
                    ResourceSeriesV1(
                        "achieved",
                        "achieved",
                        "count",
                        "load",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        values,
                    ),
                )
            }
        val generator =
            ResourceSeriesV1(
                "generator",
                "cpu",
                "ratio",
                "load",
                ResourceRole.GENERATOR,
                ResourceAggregation.INTERVAL_MEAN,
                emptyMap(),
                List(values.size) { BigDecimal.ZERO },
            )
        val rules =
            listOf(ResourceRuleV1("guard", "generator", "ratio", ResourceOperator.GT, BigDecimal.ONE, 1, ResourceRuleEffect.DIAGNOSTIC))
        return ResourceSnapshotV1("resource-snapshot.v1", "a", 0, step, values.size, achieved + generator, windows, rules, null)
    }

    private fun capacityLoad(vararg windows: Triple<String, Long, List<Int>>) =
        UtcLoadMetrics(
            windows.associate { (id, start, values) ->
                id to
                    values.mapIndexed { index, value ->
                        UtcLoadCell(
                            start + index * 10_000L,
                            start + (index + 1) * 10_000L,
                            value.toLong() * 10,
                            0,
                            null,
                            ExactRatio(value.toLong() * 10_000, 10_000),
                            null,
                        )
                    }
            },
        )

    private fun windowSummary(
        window: String,
        verdict: String,
        businessVerdict: String = verdict,
        sampleCount: Long? = null,
        minSamples: Long? = null,
    ): JsonObject =
        buildJsonObject {
            put("id", "summary-$window")
            put("type", "window_policy_summary")
            put("window_id", window)
            put("business_verdict", businessVerdict)
            put("resource_verdict", "NO_POLICY")
            put("verdict", verdict)
            sampleCount?.let { put("sample_count", it) }
            minSamples?.let { put("min_samples", it) }
        }

    private fun guardCheck(
        window: String,
        status: String,
    ): JsonObject =
        buildJsonObject {
            put("id", "guard-$window")
            put("type", "resource_policy_check")
            put("window_id", window)
            put("rule_id", "guard")
            put("effect", "diagnostic")
            put("status", status)
        }

    private fun policyOf(
        vararg verdicts: Pair<String, String>,
        guards: Map<String, String>? = null,
        extra: List<JsonObject> = emptyList(),
        coverageReasons: List<String> = emptyList(),
    ) = PolicyEvaluation(
        PolicyVerdict.PASS,
        coverageReasons,
        emptyList(),
        verdicts.map { (window, verdict) -> windowSummary(window, verdict) } +
            (guards ?: verdicts.associate { (window) -> window to "PASS" }).map { (window, status) -> guardCheck(window, status) } +
            extra,
    )

    private fun smallRule(
        window: String,
        mode: String = "SMALL_SAMPLE",
    ) = buildJsonObject {
        put("id", "check-$window")
        put("type", "policy_check")
        put("window_id", window)
        put("rule_id", "p95")
        put("status", "PASS")
        put("sample_mode", mode)
    }

    private fun missingTransactionRule(window: String) =
        buildJsonObject {
            put("id", "check-gone-$window")
            put("type", "policy_check")
            put("window_id", window)
            put("rule_id", "gone")
            put("status", "NO_VERDICT")
            put("reason_code", "TRANSACTION_NOT_FOUND")
        }

    private fun stageMetrics(
        ids: List<String>,
        p95: List<Long>,
        samples: Long = 1000,
    ) = ids.indices.associate { index ->
        ids[index] to
            NormalizedMetrics(
                MetricSummary(samples, 0, null, ExactRatio(1, 1), LatencySummary(50, p95[index], p95[index], p95[index])),
                emptyList(),
                emptyList(),
                emptyMap(),
            )
    }

    fun capacityCases(): List<CapacityCase> {
        val valid = io.ltverdict.ingest.RunValidity.VALID
        val cases = mutableListOf<CapacityCase>()
        val oneTwo =
            listOf(
                window("300", 0, 300_000),
                window("350", 300_000, 600_000),
                window("one", 0, 300_000),
                window("two", 300_000, 600_000),
                window("three", 600_000, 900_000),
                window("steady", 0, 300_000),
                window("pass", 0, 300_000),
                window("fail", 300_000, 600_000),
            )
        val rps = capacityResources(CapacityLoadAxis.RPS, oneTwo)
        val rpsPlan =
            capacityPlan(
                CapacityLoadAxis.RPS,
                BigDecimal("300"),
                listOf(capacityStage("300", BigDecimal(300), 0), capacityStage("350", BigDecimal(350), 300_000)),
            )
        val rpsLoad = capacityLoad(Triple("300", 0L, List(30) { 296 }), Triple("350", 300_000L, List(30) { 344 }))
        val passFail = policyOf("300" to "PASS", "350" to "FAIL")
        cases += CapacityCase("rps-bounded", rpsPlan, rps, rpsLoad, valid, passFail, null)
        cases += CapacityCase("rps-bounded-pass", rpsPlan.copy(requiredCapacity = BigDecimal("296")), rps, rpsLoad, valid, passFail, null)
        cases += CapacityCase("rps-bounded-fail", rpsPlan.copy(requiredCapacity = BigDecimal("344")), rps, rpsLoad, valid, passFail, null)
        cases += CapacityCase("rps-no-required", rpsPlan.copy(requiredCapacity = null), rps, rpsLoad, valid, passFail, null)
        cases +=
            CapacityCase(
                "rps-unknown-rule-window",
                rpsPlan.copy(requiredCapacity = BigDecimal("296")),
                rps,
                rpsLoad,
                valid,
                passFail.copy(coverageReasons = listOf("RULE_WINDOW_NOT_FOUND")),
                null,
            )
        cases +=
            CapacityCase(
                "rps-invalid-run",
                rpsPlan,
                rps,
                rpsLoad,
                io.ltverdict.ingest.RunValidity.INVALID,
                passFail,
                stageMetrics(listOf("300", "350"), listOf(50L, 60L)),
            )
        cases += CapacityCase("rps-degraded-run", rpsPlan, rps, rpsLoad, io.ltverdict.ingest.RunValidity.DEGRADED, passFail, null)
        // wide targets and tolerance: the numbers go through the builder as they are
        for ((index, target) in listOf(
            "12345678901234567890.5",
            "0.10",
            "1E+3",
            "-0.0",
            "1E-400",
            "0.1234567890123456789012345678901234567890",
        ).withIndex()) {
            val wideStage = capacityStage("300", BigDecimal(target), 0)
            cases +=
                CapacityCase(
                    "rps-wide-target-$index",
                    capacityPlan(
                        CapacityLoadAxis.RPS,
                        BigDecimal(target),
                        listOf(wideStage, capacityStage("350", BigDecimal("350.00"), 300_000)),
                        tolerance = BigDecimal(target),
                    ),
                    rps,
                    rpsLoad,
                    valid,
                    passFail,
                    null,
                )
        }
        val values = List(30) { BigDecimal.valueOf((it + 1).toLong()) }
        for (axis in listOf(CapacityLoadAxis.RPS, CapacityLoadAxis.CONCURRENCY, CapacityLoadAxis.USERS)) {
            cases +=
                CapacityCase(
                    "axis-${axis.name}-lower-bound",
                    capacityPlan(axis, null, listOf(capacityStage("steady", BigDecimal(2), 0))),
                    capacityResources(axis, oneTwo, values),
                    capacityLoad(Triple("steady", 0L, values.map { it.toInt() })),
                    valid,
                    policyOf("steady" to "PASS"),
                    null,
                )
        }
        val guardedStages = listOf(capacityStage("pass", BigDecimal(300), 0), capacityStage("fail", BigDecimal(350), 300_000))
        val guardedPlan = capacityPlan(CapacityLoadAxis.RPS, BigDecimal("296"), guardedStages)
        val guardedLoad = capacityLoad(Triple("pass", 0L, List(30) { 300 }), Triple("fail", 300_000L, List(30) { 350 }))
        cases += CapacityCase("guards-pass", guardedPlan, rps, guardedLoad, valid, policyOf("pass" to "PASS", "fail" to "FAIL"), null)
        cases +=
            CapacityCase(
                "guard-missing-window",
                guardedPlan,
                rps,
                guardedLoad,
                valid,
                policyOf(
                    "pass" to "PASS",
                    "fail" to "PASS",
                    guards =
                        mapOf(
                            "pass" to "PASS",
                        ),
                ),
                null,
            )
        cases +=
            CapacityCase(
                "guard-failed",
                guardedPlan,
                rps,
                guardedLoad,
                valid,
                policyOf("pass" to "PASS", "fail" to "PASS", guards = mapOf("pass" to "PASS", "fail" to "FAIL")),
                null,
            )
        cases +=
            CapacityCase(
                "gapped-bins",
                guardedPlan,
                rps,
                capacityLoad(Triple("pass", 0L, List(29) { 300 }), Triple("fail", 300_000L, List(30) { 350 })),
                valid,
                policyOf("pass" to "PASS", "fail" to "PASS", guards = mapOf("pass" to "PASS", "fail" to "PASS")),
                null,
            )
        val noSla =
            PolicyEvaluation(
                PolicyVerdict.PASS,
                emptyList(),
                emptyList(),
                listOf(windowSummary("one", "NO_POLICY", sampleCount = 40), guardCheck("one", "PASS")),
            )
        cases +=
            CapacityCase(
                "no-sla",
                capacityPlan(CapacityLoadAxis.RPS, BigDecimal.ONE, listOf(capacityStage("one", BigDecimal(90), 0))),
                rps,
                capacityLoad(Triple("one", 0L, List(30) { 90 })),
                valid,
                noSla,
                null,
            )
        cases +=
            CapacityCase(
                "non-monotonic-target",
                capacityPlan(
                    CapacityLoadAxis.RPS,
                    BigDecimal.ONE,
                    listOf(capacityStage("one", BigDecimal(100), 0), capacityStage("two", BigDecimal(90), 300_000)),
                ),
                rps,
                capacityLoad(Triple("one", 0L, List(30) { 90 }), Triple("two", 300_000L, List(30) { 200 })),
                valid,
                policyOf("one" to "PASS", "two" to "PASS"),
                null,
            )
        val three =
            listOf(
                capacityStage("one", BigDecimal(100), 0),
                capacityStage("two", BigDecimal(200), 300_000),
                capacityStage("three", BigDecimal(300), 600_000),
            )
        val threeLoad =
            capacityLoad(
                Triple("one", 0L, List(30) { 100 }),
                Triple("two", 300_000L, List(30) { 200 }),
                Triple("three", 600_000L, List(30) { 300 }),
            )
        cases +=
            CapacityCase(
                "first-fail-upper-bound",
                capacityPlan(CapacityLoadAxis.RPS, BigDecimal(100), three.take(1)),
                rps,
                threeLoad,
                valid,
                policyOf(
                    "one" to "FAIL",
                ),
                null,
            )
        cases +=
            CapacityCase(
                "inverted-outcome",
                capacityPlan(CapacityLoadAxis.RPS, BigDecimal(100), three),
                rps,
                threeLoad,
                valid,
                policyOf(
                    "one" to "PASS",
                    "two" to "FAIL",
                    "three" to "PASS",
                ),
                null,
            )
        cases +=
            CapacityCase(
                "empty-guards",
                capacityPlan(CapacityLoadAxis.RPS, BigDecimal.ONE, three.take(1), guards = emptyList()),
                rps,
                threeLoad,
                valid,
                policyOf(
                    "one" to "PASS",
                ),
                null,
            )
        cases +=
            CapacityCase(
                "equal-endpoints",
                capacityPlan(
                    CapacityLoadAxis.RPS,
                    BigDecimal(100),
                    listOf(capacityStage("one", BigDecimal(100), 0), capacityStage("two", BigDecimal(101), 300_000)),
                ),
                rps,
                capacityLoad(Triple("one", 0L, List(30) { 100 }), Triple("two", 300_000L, List(30) { 100 })),
                valid,
                policyOf("one" to "PASS", "two" to "FAIL"),
                null,
            )
        val samplesLoad = capacityLoad(Triple("300", 0L, List(30) { 300 }))
        val samplesPlan = capacityPlan(CapacityLoadAxis.RPS, BigDecimal("300"), listOf(capacityStage("300", BigDecimal(300), 0)))
        for ((index, extra) in listOf(
            listOf(windowSummary("300", "PASS", sampleCount = 40, minSamples = 40)),
            listOf(windowSummary("300", "PASS", sampleCount = 40)),
            listOf(windowSummary("300", "PASS", businessVerdict = "NO_POLICY", sampleCount = 99)),
            listOf(windowSummary("300", "PASS", sampleCount = 5_000, minSamples = 100), smallRule("300")),
            listOf(windowSummary("300", "PASS"), missingTransactionRule("300")),
        ).withIndex()) {
            cases +=
                CapacityCase(
                    "sample-gate-$index",
                    samplesPlan,
                    rps,
                    samplesLoad,
                    valid,
                    PolicyEvaluation(PolicyVerdict.PASS, emptyList(), emptyList(), extra + guardCheck("300", "PASS")),
                    null,
                )
        }
        cases +=
            CapacityCase(
                "stage-window-missing",
                capacityPlan(CapacityLoadAxis.RPS, BigDecimal(100), listOf(capacityStage("ghost", BigDecimal(100), 0))),
                rps,
                capacityLoad(Triple("ghost", 0L, List(30) { 100 })),
                valid,
                policyOf("one" to "PASS"),
                null,
            )
        // source-backed axes with gaps and time weighting
        val concurrencyWindows = listOf(window("steady", 0, 310_000))
        cases +=
            CapacityCase(
                "concurrency-gap",
                capacityPlan(CapacityLoadAxis.CONCURRENCY, BigDecimal(100), listOf(capacityStage("steady", BigDecimal(100), 0, 310_000))),
                capacityResources(CapacityLoadAxis.CONCURRENCY, concurrencyWindows, List(31) { if (it == 10) null else BigDecimal("100") }),
                capacityLoad(Triple("steady", 0L, List(31) { 0 })),
                valid,
                policyOf("steady" to "PASS"),
                null,
            )
        val halfStepWindows = listOf(window("steady", 0, 300_000))
        cases +=
            CapacityCase(
                "concurrency-time-weighted",
                capacityPlan(CapacityLoadAxis.CONCURRENCY, BigDecimal(2), listOf(capacityStage("steady", BigDecimal(2), 0))),
                capacityResources(
                    CapacityLoadAxis.CONCURRENCY,
                    halfStepWindows,
                    List(60) {
                        if (it % 2 ==
                            0
                        ) {
                            BigDecimal.ONE
                        } else {
                            BigDecimal("3")
                        }
                    },
                    5_000,
                ),
                capacityLoad(Triple("steady", 0L, List(30) { 0 })),
                valid,
                policyOf("steady" to "PASS"),
                null,
            )
        // knee: detected, not detected, refusals
        val loads = listOf(40, 60, 80, 90, 96, 104)
        val ids = loads.indices.map { "k${it + 1}" }
        val kneeWindows = ids.mapIndexed { index, id -> window(id, index * 300_000L, (index + 1) * 300_000L) }
        val kneeStages = ids.mapIndexed { index, id -> capacityStage(id, BigDecimal(loads[index]), index * 300_000L) }
        val kneeLoad = capacityLoad(*ids.mapIndexed { index, id -> Triple(id, index * 300_000L, List(30) { loads[index] }) }.toTypedArray())
        val kneeResources = capacityResources(CapacityLoadAxis.RPS, kneeWindows, List(180) { BigDecimal.ONE })
        val kneePolicy = policyOf(*ids.map { it to "PASS" }.toTypedArray())
        val kneePlan = capacityPlan(CapacityLoadAxis.RPS, BigDecimal("90"), kneeStages)
        cases +=
            CapacityCase(
                "knee-detected",
                kneePlan,
                kneeResources,
                kneeLoad,
                valid,
                kneePolicy,
                stageMetrics(ids, listOf(52L, 52L, 52L, 53L, 56L, 11_536L)),
            )
        cases +=
            CapacityCase(
                "knee-not-detected",
                kneePlan,
                kneeResources,
                kneeLoad,
                valid,
                kneePolicy,
                stageMetrics(ids, listOf(50L, 51L, 50L, 52L, 51L, 50L)),
            )
        cases +=
            CapacityCase(
                "knee-not-material",
                kneePlan,
                kneeResources,
                kneeLoad,
                valid,
                kneePolicy,
                stageMetrics(ids, listOf(50L, 60L, 70L, 80L, 90L, 100L)),
            )
        cases +=
            CapacityCase(
                "knee-invalid-run",
                kneePlan,
                kneeResources,
                kneeLoad,
                io.ltverdict.ingest.RunValidity.INVALID,
                kneePolicy,
                stageMetrics(ids, listOf(52L, 52L, 52L, 53L, 56L, 11_536L)),
            )
        cases +=
            CapacityCase(
                "knee-missing-stage",
                kneePlan,
                kneeResources,
                kneeLoad,
                valid,
                kneePolicy,
                stageMetrics(ids, listOf(52L, 52L, 52L, 53L, 56L, 11_536L)) - "k3",
            )
        cases +=
            CapacityCase(
                "knee-few-samples",
                kneePlan,
                kneeResources,
                kneeLoad,
                valid,
                kneePolicy,
                stageMetrics(ids, listOf(52L, 52L, 52L, 53L, 56L, 11_536L), samples = 0),
            )
        return cases
    }

    /** Stage lists for the direct knee matrix: ordinary, flat before the jump, wide loads, refusals. */
    fun kneeCases(): List<Triple<String, CapacityLoadAxis, Pair<List<KneePoint>, String?>>> {
        fun points(
            loads: List<String>,
            p95: List<Long>,
        ) = loads.mapIndexed { index, load -> KneePoint("stage-$index", BigDecimal(load), p95[index]) }

        val loads = listOf("40", "60", "80", "90", "96", "104")
        return listOf(
            Triple(
                "demo",
                CapacityLoadAxis.RPS,
                points(listOf("39.8", "59.8", "79.8", "89.7", "95.745", "103.745"), listOf(52L, 52L, 52L, 53L, 56L, 11_536L)) to null,
            ),
            Triple("flat", CapacityLoadAxis.CONCURRENCY, points(loads, listOf(52L, 52L, 52L, 52L, 52L, 11_536L)) to null),
            Triple("ramp", CapacityLoadAxis.USERS, points(loads + "110", listOf(52L, 52L, 52L, 53L, 56L, 400L, 11_536L)) to null),
            Triple("smooth", CapacityLoadAxis.RPS, points(loads, listOf(50L, 51L, 50L, 52L, 51L, 50L)) to null),
            Triple("not-material", CapacityLoadAxis.RPS, points(loads, listOf(50L, 52L, 55L, 60L, 68L, 80L)) to null),
            Triple("few", CapacityLoadAxis.RPS, points(loads.take(4), listOf(52L, 52L, 52L, 53L)) to null),
            Triple(
                "not-increasing",
                CapacityLoadAxis.RPS,
                points(listOf("40", "84", "80", "90", "96", "104"), listOf(52L, 52L, 52L, 53L, 56L, 11_536L)) to null,
            ),
            Triple("refused", CapacityLoadAxis.RPS, emptyList<KneePoint>() to "KNEE_RUN_NOT_VALID"),
            Triple(
                "refused-with-points",
                CapacityLoadAxis.USERS,
                points(loads, listOf(52L, 52L, 52L, 53L, 56L, 11_536L)) to "KNEE_STAGE_DATA_MISSING",
            ),
            Triple(
                "wide-loads",
                CapacityLoadAxis.RPS,
                points(
                    listOf(
                        "12345678901234567890.5",
                        "12345678901234567891.5",
                        "12345678901234567892.25",
                        "12345678901234567893",
                        "12345678901234567894",
                        "12345678901234567895.125",
                    ),
                    listOf(52L, 52L, 52L, 53L, 56L, Long.MAX_VALUE),
                ) to null,
            ),
            Triple(
                "exponent-loads",
                CapacityLoadAxis.RPS,
                points(listOf("1E+1", "2E+1", "3E+1", "4E+1", "5E+1", "6E+1"), listOf(52L, 52L, 52L, 53L, 56L, 11_536L)) to null,
            ),
            Triple(
                "tiny-loads",
                CapacityLoadAxis.RPS,
                points(listOf("0.10", "0.20", "0.30", "0.40", "0.50", "0.60"), listOf(1L, 1L, 1L, 1L, 1L, 1_000L)) to null,
            ),
            Triple("zero-p95", CapacityLoadAxis.RPS, points(loads, listOf(0L, 0L, 0L, 0L, 0L, 0L)) to null),
        )
    }

    // ---------------------------------------------------------------- trend

    data class TrendCase(
        val name: String,
        val plan: TrendPlanV1,
        val snapshot: ResourceSnapshotV1,
        val windows: List<ResourceWindowV1>,
    )

    private const val START = 1_767_225_600_000L

    private fun trendSnapshot(
        values: List<BigDecimal?>,
        windows: List<ResourceWindowV1>,
        unit: String = "percent",
        entity: String = "host",
    ) = ResourceSnapshotV1(
        "resource-snapshot.v1",
        "a".repeat(64),
        START,
        1_000L,
        values.size,
        listOf(
            ResourceSeriesV1(
                "cpu",
                "cpu_utilization",
                unit,
                entity,
                ResourceRole.SYSTEM,
                ResourceAggregation.INTERVAL_MEAN,
                emptyMap(),
                values,
            ),
        ),
        windows,
        emptyList(),
        null,
    )

    private fun trendPlan(
        direction: TrendDirection = TrendDirection.INCREASE,
        minCells: Int = TREND_MIN_CELLS_FLOOR,
        minSlope: String = "0.5",
        minShiftPct: String = "5",
        seriesId: String = "cpu",
        windowId: String = "evaluation",
        id: String = "cpu-growth",
    ) = TrendPlanV1(
        "b".repeat(64),
        listOf(
            TrendCheckV1(id, seriesId, windowId, direction, minCells, TrendMagnitudeGate(BigDecimal(minSlope), BigDecimal(minShiftPct))),
        ),
    )

    fun trendCases(): List<TrendCase> {
        val w40 = listOf(ResourceWindowV1("evaluation", START, START + 40_000L))
        val w60 = listOf(ResourceWindowV1("evaluation", START, START + 60_000L))
        val ramp = List<BigDecimal?>(40) { BigDecimal.valueOf(100L + it) }
        val cases = mutableListOf<TrendCase>()
        cases += TrendCase("increase", trendPlan(), trendSnapshot(ramp, w40), w40)
        cases +=
            TrendCase(
                "decrease",
                trendPlan(direction = TrendDirection.DECREASE),
                trendSnapshot(List(40) { BigDecimal.valueOf(200L - it) }, w40),
                w40,
            )
        cases += TrendCase("decrease-wrong-direction", trendPlan(), trendSnapshot(List(40) { BigDecimal.valueOf(200L - it) }, w40), w40)
        cases += TrendCase("slope-below-minimum", trendPlan(minSlope = "2"), trendSnapshot(ramp, w40), w40)
        cases +=
            TrendCase(
                "terminal-spike",
                trendPlan(direction = TrendDirection.EITHER),
                trendSnapshot(
                    List(39) { BigDecimal(100) } + BigDecimal(1000),
                    w40,
                ),
                w40,
            )
        cases += TrendCase("zero-median", trendPlan(), trendSnapshot(List(40) { BigDecimal.ZERO }, w40), w40)
        cases += TrendCase("one-gap", trendPlan(), trendSnapshot(ramp.toMutableList().also { it[5] = null }, w40), w40)
        cases += TrendCase("min-cells-not-met", trendPlan(minCells = 41), trendSnapshot(ramp, w40), w40)
        cases +=
            TrendCase(
                "sparse-half",
                trendPlan(),
                trendSnapshot(
                    List(60) { index ->
                        if (index <
                            29
                        ) {
                            null
                        } else {
                            BigDecimal.valueOf(100L + index)
                        }
                    },
                    w60,
                ),
                w60,
            )
        cases += TrendCase("empty-window", trendPlan(), trendSnapshot(List<BigDecimal?>(40) { null }, w40), w40)
        cases += TrendCase("unknown-series", trendPlan(seriesId = "absent"), trendSnapshot(ramp, w40), w40)
        cases += TrendCase("unknown-window", trendPlan(windowId = "absent"), trendSnapshot(ramp, w40), w40)
        cases +=
            TrendCase(
                "single-observation",
                trendPlan(minCells = 1),
                trendSnapshot(
                    List(40) {
                        if (it ==
                            20
                        ) {
                            BigDecimal(5)
                        } else {
                            null
                        }
                    },
                    w40,
                ),
                w40,
            )
        cases +=
            TrendCase("unicode-entity", trendPlan(id = "é\u0001\"x"), trendSnapshot(ramp, w40, unit = "%é", entity = "host\u0001é"), w40)
        val sawtooth = List<BigDecimal?>(160) { BigDecimal.valueOf(200L + 20L * (it % 40)) }
        val sawWindows =
            listOf(
                ResourceWindowV1("cycle-boundary", START, START + 120_000L),
                ResourceWindowV1("mid-cycle", START + 20_000L, START + 140_000L),
                ResourceWindowV1("single-cycle", START, START + 40_000L),
            )
        for (window in sawWindows) {
            cases +=
                TrendCase(
                    "sawtooth-${window.id}",
                    trendPlan(direction = TrendDirection.EITHER, windowId = window.id),
                    trendSnapshot(sawtooth, sawWindows),
                    sawWindows,
                )
        }
        cases +=
            TrendCase(
                "several-checks",
                TrendPlanV1(
                    "b".repeat(64),
                    listOf(
                        trendCheck("observed", TrendDirection.INCREASE, "0.5"),
                        trendCheck("not-material", TrendDirection.INCREASE, "50"),
                        trendCheck("absent-series", TrendDirection.INCREASE, "0.5", "absent"),
                        trendCheck("flat", TrendDirection.EITHER, "0"),
                    ),
                ),
                trendSnapshot(ramp, w40),
                w40,
            )
        cases +=
            TrendCase(
                "flat-series",
                TrendPlanV1(
                    "b".repeat(64),
                    listOf(trendCheck("flat", TrendDirection.EITHER, "0")),
                ),
                trendSnapshot(
                    List(40) {
                        BigDecimal(7)
                    },
                    w40,
                ),
                w40,
            )
        // seeded random series: gaps, wide and long decimals
        for (seed in 1..12) {
            val random = Random(seed.toLong())
            val values =
                List<BigDecimal?>(60) { index ->
                    when {
                        random.nextInt(8) == 0 -> null
                        seed % 3 == 0 ->
                            BigDecimal("12345678901234567890.123456789").add(
                                BigDecimal.valueOf(
                                    random.nextInt(1000) + index.toLong(),
                                    3,
                                ),
                            )
                        seed % 3 == 1 ->
                            BigDecimal
                                .valueOf(
                                    random.nextInt(10_000).toLong(),
                                    4,
                                ).add(BigDecimal.valueOf(index.toLong() * seed, 2))
                        else -> BigDecimal.valueOf(1000L - index * 3L + random.nextInt(20))
                    }
                }
            val direction = TrendDirection.entries[seed % 3]
            cases +=
                TrendCase(
                    "random-$seed",
                    trendPlan(direction = direction, minSlope = "0.0001", minShiftPct = "1"),
                    trendSnapshot(values, w60),
                    w60,
                )
        }
        return cases
    }

    private fun trendCheck(
        id: String,
        direction: TrendDirection,
        minSlope: String,
        seriesId: String = "cpu",
    ) = TrendCheckV1(
        id,
        seriesId,
        "evaluation",
        direction,
        TREND_MIN_CELLS_FLOOR,
        TrendMagnitudeGate(BigDecimal(minSlope), BigDecimal("5")),
    )
}

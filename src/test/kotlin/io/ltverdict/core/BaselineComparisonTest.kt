package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BaselineComparisonTest {
    @Test
    fun `window comparison computes latency deltas and duration-normalized rates`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())
        val baseline = windowResult("steady", from = 0, to = 10_000, p95 = 100, samples = 100, errors = 1)
        val current = windowResult("steady", from = 10_000, to = 30_000, p95 = 120, samples = 400, errors = 6)

        val comparison =
            compareAnalyses(
                selection,
                reference('c'),
                baseline,
                identity(),
                current,
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val window = comparison.getValue("window_comparison").jsonObject
        val metrics =
            window.getValue("metrics").jsonArray.associateBy {
                it.jsonObject
                    .getValue("metric")
                    .jsonPrimitive.content
            }

        assertEquals("CANDIDATE", window.getValue("status").jsonPrimitive.content)
        assertEquals(
            100,
            window
                .getValue("baseline_sample_count")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            400,
            window
                .getValue("current_sample_count")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            10_000,
            window
                .getValue("baseline_duration_ms")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            20_000,
            window
                .getValue("current_duration_ms")
                .jsonPrimitive.content
                .toInt(),
        )
        assertWindowMetric(metrics.getValue("response_time_p95_ms").jsonObject, "120", "100", "20", "20", "CANDIDATE", null)
        assertWindowMetric(metrics.getValue("throughput_rps").jsonObject, "20", "10", "10", "100", "CANDIDATE", null)
        assertWindowMetric(metrics.getValue("error_rate_ratio").jsonObject, "0.015", "0.01", "0.005", "50", "CANDIDATE", null)
    }

    @Test
    fun `window comparison preserves the old response when no windows are requested`() {
        val selection = manualBaselineSelection("release", reference('a'))

        val comparison = compareAnalyses(selection, reference('b'), result(), identity(), result(), identity())

        assertEquals(setOf("baseline", "current", "comparability", "metrics"), comparison.keys)
    }

    @Test
    fun `window comparison keeps material observations descriptive without confirmed conditions`() {
        val comparison =
            compareAnalyses(
                manualBaselineSelection("release", reference('a')),
                reference('b'),
                windowResult("steady", 0, 10_000, 100, 100, 0),
                identity(),
                windowResult("steady", 0, 10_000, 120, 100, 0),
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val window = comparison.getValue("window_comparison").jsonObject
        val p95 =
            window
                .getValue("metrics")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("metric")
                        .jsonPrimitive.content ==
                        "response_time_p95_ms"
                }.jsonObject

        assertEquals("DESCRIPTIVE", window.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("CONDITIONS_UNCONFIRMED"), window.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
        assertWindowMetric(p95, "120", "100", "20", "20", "DESCRIPTIVE", "CONDITIONS_UNCONFIRMED")
    }

    @Test
    fun `window comparison handles zero baselines and inclusive error thresholds`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val comparison =
            compareAnalyses(
                statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities()),
                reference('c'),
                windowResult("steady", 0, 10_000, 0, 100, 0),
                identity(),
                windowResult("steady", 0, 10_000, 5, 1_000, 1),
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val metrics =
            comparison.getValue("window_comparison").jsonObject.getValue("metrics").jsonArray.associateBy {
                it.jsonObject
                    .getValue("metric")
                    .jsonPrimitive.content
            }

        assertWindowMetric(metrics.getValue("error_rate_ratio").jsonObject, "0.001", "0", "0.001", null, "CANDIDATE", null)
        assertWindowMetric(metrics.getValue("response_time_p95_ms").jsonObject, "5", "0", "5", null, "DESCRIPTIVE", "ZERO_BASELINE")
    }

    @Test
    fun `window comparison exposes unavailable windows and technical mismatches without changing raw metrics`() {
        val selection = manualBaselineSelection("release", reference('a'))
        val missing =
            compareAnalyses(
                selection,
                reference('b'),
                result(),
                identity(),
                result(),
                identity(),
                WindowComparisonRequest("gone", "steady"),
            )
        val incompatible =
            compareAnalyses(
                selection,
                reference('b'),
                windowResult("steady", 0, 10_000, 100, 100, 0),
                identity(),
                windowResult("steady", 0, 10_000, 100, 100, 0),
                identity("other"),
                WindowComparisonRequest("steady", "steady"),
            )

        assertEquals(
            "NOT_EVALUATED",
            missing
                .getValue("window_comparison")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertEquals(
            "NOT_EVALUATED",
            incompatible
                .getValue("window_comparison")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertEquals(
            "INCOMPATIBLE_METRIC_DEFINITION",
            incompatible
                .getValue("metrics")
                .jsonArray
                .first()
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `window comparison matches resource summaries only when labels match`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val baselineBinding = resourceBinding("cpu", "checkout")
        val currentBinding = resourceBinding("cpu", "checkout")
        val otherBinding = resourceBinding("cpu", "payment")
        val comparison =
            compareAnalyses(
                statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities()),
                reference('c'),
                windowResult(
                    "steady",
                    0,
                    10_000,
                    100,
                    100,
                    0,
                    listOf(baselineBinding, otherBinding),
                    listOf(resourceSummary("steady", baselineBinding, "10", "20")),
                ),
                identity(),
                windowResult(
                    "steady",
                    0,
                    10_000,
                    100,
                    100,
                    0,
                    listOf(currentBinding),
                    listOf(resourceSummary("steady", currentBinding, "12", "24")),
                ),
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val metrics =
            comparison.getValue("window_comparison").jsonObject.getValue("metrics").jsonArray.associateBy {
                it.jsonObject
                    .getValue("metric")
                    .jsonPrimitive.content
            }

        assertWindowMetric(metrics.getValue("cpu_usage_median").jsonObject, "12", "10", "2", "20", "CANDIDATE", null)
        assertWindowMetric(metrics.getValue("cpu_usage_q95").jsonObject, "24", "20", "4", "20", "CANDIDATE", null)
        assertEquals(
            "RESOURCE_BINDING_MISSING",
            metrics
                .getValue("cpu_usage_median:payment")
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `window comparison keeps signed resource percent deltas`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val binding = resourceBinding("cpu", "checkout")
        val comparison =
            compareAnalyses(
                statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities()),
                reference('c'),
                windowResult("steady", 0, 10_000, 100, 100, 0, listOf(binding), listOf(resourceSummary("steady", binding, "-10", "-20"))),
                identity(),
                windowResult("steady", 0, 10_000, 100, 100, 0, listOf(binding), listOf(resourceSummary("steady", binding, "-8", "-16"))),
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val median =
            comparison
                .getValue("window_comparison")
                .jsonObject
                .getValue("metrics")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("metric")
                        .jsonPrimitive.content == "cpu_usage_median"
                }.jsonObject

        assertWindowMetric(median, "-8", "-10", "2", "-20", "CANDIDATE", null)
    }

    @Test
    fun `statistical selection chooses the median ranks regardless of request order`() {
        val candidates =
            listOf(
                candidate('c', result(1_000, throughput = 10, errors = 0)),
                candidate('a', result(100, throughput = 100, errors = 0)),
                candidate('b', result(110, throughput = 90, errors = 0)),
            )

        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())

        assertEquals(reference('b'), selection.getValue("reference"))
        assertEquals("median-rank-v1", selection.getValue("algorithm").jsonPrimitive.content)
        assertEquals(
            listOf(reference('a'), reference('b'), reference('c')),
            selection.getValue("candidates").jsonArray,
        )
        assertEquals(
            listOf(reference('a') to 4, reference('b') to 0, reference('c') to 4),
            selection
                .getValue("scores")
                .jsonArray
                .map { score ->
                    score.jsonObject.getValue("reference") to
                        score.jsonObject
                            .getValue("score")
                            .jsonPrimitive.content
                            .toInt()
                },
        )
    }

    @Test
    fun `statistical selection orders close rational values exactly and breaks full ties by reference`() {
        val exact =
            listOf(
                candidate('a', result(100, throughput = 9_007_199_254_740_992L to 9_007_199_254_740_991L, errors = 0L to 1L)),
                candidate('c', result(100, throughput = 9_007_199_254_740_991L to 9_007_199_254_740_992L, errors = 0L to 1L)),
                candidate('b', result(100, throughput = 1L to 1L, errors = 0L to 1L)),
            )
        val tied = listOf(candidate('c'), candidate('b'), candidate('a'))

        assertEquals(
            reference('b'),
            statisticalBaselineSelection("exact", exact.references(), exact.results(), exact.identities()).getValue("reference"),
        )
        assertEquals(
            reference('a'),
            statisticalBaselineSelection("tie", tied.references(), tied.results(), tied.identities()).getValue("reference"),
        )
    }

    @Test
    fun `statistical ranking keeps metric columns distinct when candidate values coincide`() {
        val candidates =
            listOf(
                candidate('a', result(p95 = 1, throughput = 1, errors = 1L to 2L)),
                candidate('b', result(p95 = 2, throughput = 0, errors = 1L to 2L)),
                candidate('c', result(p95 = 3, throughput = 2, errors = 1L to 2L)),
            )

        val selection = statisticalBaselineSelection("columns", candidates.references(), candidates.results(), candidates.identities())

        assertEquals(reference('a'), selection.getValue("reference"))
    }

    @Test
    fun `statistical ranks treat equivalent rational encodings as ties`() {
        val candidates =
            listOf(
                candidate('a', result(throughput = 1L to 2L)),
                candidate('b', result(throughput = 2L to 4L)),
                candidate('c', result(throughput = 3L to 6L)),
            )

        val selection = statisticalBaselineSelection("ratios", candidates.references(), candidates.results(), candidates.identities())

        assertEquals(
            listOf(0, 0, 0),
            selection.getValue("scores").jsonArray.map {
                it.jsonObject
                    .getValue("score")
                    .jsonPrimitive.content
                    .toInt()
            },
        )
    }

    @Test
    fun `statistical selection rejects every ineligible series instead of filtering candidates`() {
        val valid = listOf(candidate('a'), candidate('b'), candidate('c'))

        assertThrows(IllegalArgumentException::class.java) {
            statisticalBaselineSelection("short", valid.take(2).references(), valid.take(2).results(), valid.take(2).identities())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val duplicateRun = listOf(candidate('a'), candidate('a', analysis = 'd'), candidate('c'))
            statisticalBaselineSelection(
                "duplicate",
                duplicateRun.references(),
                duplicateRun.results(),
                duplicateRun.identities(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val missing = valid.toMutableList().also { it[1] = candidate('b', result(p95 = null)) }
            statisticalBaselineSelection("missing", missing.references(), missing.results(), missing.identities())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val degraded = valid.toMutableList().also { it[1] = candidate('b', result(coverage = "INCOMPLETE")) }
            statisticalBaselineSelection("degraded", degraded.references(), degraded.results(), degraded.identities())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = valid.toMutableList().also { it[1] = candidate('b', result(validity = "INVALID")) }
            statisticalBaselineSelection("invalid", invalid.references(), invalid.results(), invalid.identities())
        }
        assertThrows(IllegalArgumentException::class.java) {
            val mixed = valid.toMutableList().also { it[1] = candidate('b', identity = identity("other")) }
            statisticalBaselineSelection("mixed", mixed.references(), mixed.results(), mixed.identities())
        }
    }

    @Test
    fun `comparison computes exact deltas before rounding and reports missing and zero baseline`() {
        val baseline =
            result(
                p95 = 100,
                p99 = 0,
                throughput = 1L to 6L,
                errors = null,
            )
        val current =
            result(
                p95 = 125,
                p99 = 1,
                throughput = 1L to 3L,
                errors = null,
            )
        val selection = manualBaselineSelection("release", reference('a'))

        val comparison = compareAnalyses(selection, reference('b'), baseline, identity(), current, identity())

        assertEquals("UNCONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
        assertMetric(comparison, 0, "125", "100", "25", "25", null, null)
        assertMetric(comparison, 1, "1", "0", "1", null, null, "ZERO_BASELINE")
        assertMetric(comparison, 2, "0.333333", "0.166667", "0.166667", "100", null, null)
        assertMetric(comparison, 3, null, null, null, null, "MISSING_METRIC", "MISSING_METRIC")
    }

    @Test
    fun `comparison keeps raw values but suppresses deltas for incompatible definitions`() {
        val selection = manualBaselineSelection("release", reference('a'))

        val comparison = compareAnalyses(selection, reference('b'), result(), identity(), result(), identity("other"))

        comparison.getValue("metrics").jsonArray.forEach { metric ->
            val body = metric.jsonObject
            assertEquals("INCOMPATIBLE_METRIC_DEFINITION", body.getValue("reason").jsonPrimitive.content)
            assertEquals(JsonNull, body.getValue("delta"))
            assertEquals(JsonNull, body.getValue("delta_percent"))
            assertEquals("INCOMPATIBLE_METRIC_DEFINITION", body.getValue("percent_reason").jsonPrimitive.content)
        }
    }

    @Test
    fun `statistical candidate comparison carries user confirmation`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())

        val comparison = compareAnalyses(selection, reference('c'), result(), identity(), result(), identity())

        assertEquals("USER_CONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
    }

    private fun assertMetric(
        comparison: JsonObject,
        index: Int,
        current: String?,
        baseline: String?,
        delta: String?,
        percent: String?,
        reason: String?,
        percentReason: String?,
    ) {
        val metric = comparison.getValue("metrics").jsonArray[index].jsonObject
        assertEquals(
            setOf("metric", "unit", "current", "baseline", "delta", "delta_percent", "reason", "percent_reason"),
            metric.keys,
        )
        assertEquals(current, metric.getValue("current").nullableString())
        assertEquals(baseline, metric.getValue("baseline").nullableString())
        assertEquals(delta, metric.getValue("delta").nullableString())
        assertEquals(percent, metric.getValue("delta_percent").nullableString())
        assertEquals(reason, metric.getValue("reason").nullableString())
        assertEquals(percentReason, metric.getValue("percent_reason").nullableString())
    }

    private fun assertWindowMetric(
        metric: JsonObject,
        current: String?,
        baseline: String?,
        delta: String?,
        percent: String?,
        status: String,
        reason: String?,
    ) {
        assertEquals(current, metric.getValue("current").nullableString())
        assertEquals(baseline, metric.getValue("baseline").nullableString())
        assertEquals(delta, metric.getValue("delta").nullableString())
        assertEquals(percent, metric.getValue("delta_percent").nullableString())
        assertEquals(status, metric.getValue("status").jsonPrimitive.content)
        assertEquals(reason, metric.getValue("reason").nullableString())
    }

    private data class Candidate(
        val reference: JsonObject,
        val result: JsonObject,
        val identity: JsonObject,
    )

    private fun candidate(
        run: Char,
        result: JsonObject = result(),
        identity: JsonObject = identity(),
        analysis: Char = run,
    ) = Candidate(reference(run, analysis), result, identity)

    private fun List<Candidate>.references() = map(Candidate::reference)

    private fun List<Candidate>.results() = map(Candidate::result)

    private fun List<Candidate>.identities() = map(Candidate::identity)

    private fun reference(
        run: Char,
        analysis: Char = run,
    ) = buildJsonObject {
        put("run_id", "jmeter_jtl_csv-${run.toString().repeat(64)}")
        put("analysis_id", analysis.toString().repeat(64))
    }

    private fun result(
        p95: Long? = 100,
        p99: Long? = 110,
        throughput: Any? = 100L,
        errors: Any? = 0L,
        validity: String = "VALID",
        coverage: String = "COMPLETE",
    ) = buildJsonObject {
        put("analysis_mode", "standard")
        put("run_validity", validity)
        put("analysis_coverage", buildJsonObject { put("status", coverage) })
        put(
            "evidence",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "metric_summary")
                        put("scope", buildJsonObject { put("kind", "overall") })
                        put(
                            "latency_ms",
                            buildJsonObject {
                                put("p95", p95?.let(::JsonPrimitive) ?: JsonNull)
                                put("p99", p99?.let(::JsonPrimitive) ?: JsonNull)
                            },
                        )
                        put("throughput_rps", ratio(throughput))
                        put("error_rate_ratio", ratio(errors))
                    },
                )
            },
        )
    }

    private fun windowResult(
        windowId: String,
        from: Long,
        to: Long,
        p95: Long,
        samples: Long,
        errors: Long,
        bindings: List<JsonObject> = emptyList(),
        resources: List<JsonObject> = emptyList(),
    ) = buildJsonObject {
        result().forEach { (name, value) -> put(name, value) }
        put(
            "evidence",
            buildJsonArray {
                result().getValue("evidence").jsonArray.forEach(::add)
                add(
                    buildJsonObject {
                        put("id", "window-metric-summary-$windowId")
                        put("type", "window_metric_summary")
                        put("window_id", windowId)
                        put("from_epoch_ms", from)
                        put("to_epoch_ms", to)
                        put("sample_count", samples)
                        put("error_count", errors)
                        put("error_rate_ratio", ratio(errors to samples))
                        put("throughput_rps", ratio(samples * 1_000L to (to - from)))
                        put(
                            "latency_ms",
                            buildJsonObject {
                                put("p50", maxOf(0, p95 - 20))
                                put("p95", p95)
                                put("p99", p95 + 20)
                                put("max", p95 + 40)
                            },
                        )
                        put("resource_bindings", buildJsonArray { bindings.forEach(::add) })
                    },
                )
                resources.forEach(::add)
            },
        )
    }

    private fun resourceBinding(
        seriesId: String,
        stage: String,
    ) = buildJsonObject {
        put("series_id", seriesId)
        put("metric", "cpu_usage")
        put("unit", "ratio")
        put("entity", "api-1")
        put("role", "diagnostic")
        put("aggregation", "mean")
        put("labels", buildJsonObject { put("stage", stage) })
    }

    private fun resourceSummary(
        windowId: String,
        binding: JsonObject,
        median: String,
        q95: String,
    ) = buildJsonObject {
        put("id", "resource-summary-${binding.getValue("series_id").jsonPrimitive.content}-$windowId")
        put("type", "resource_summary")
        listOf("series_id", "metric", "unit", "entity", "role", "aggregation").forEach { field -> put(field, binding.getValue(field)) }
        put("window_id", windowId)
        put(
            "statistics",
            buildJsonObject {
                put("median", median)
                put("q95", q95)
            },
        )
    }

    private fun ratio(value: Any?): kotlinx.serialization.json.JsonElement =
        when (value) {
            null -> JsonNull
            is Int -> ratio(value.toLong())
            is Long -> ratio(value to 1L)
            is Pair<*, *> ->
                buildJsonObject {
                    put("numerator", value.first as Long)
                    put("denominator", value.second as Long)
                }

            else -> error("unsupported test ratio")
        }

    private fun identity(version: String = "same") =
        buildJsonObject {
            put("source_type", "jmeter_jtl_csv")
            put("engine", buildJsonObject { put("version", version) })
            put("parsers", JsonArray(emptyList()))
            put("modules", JsonArray(emptyList()))
            put("input_versions", buildJsonObject {})
            put("outputs", buildJsonObject {})
            put("histogram", buildJsonObject {})
            put("normalization", buildJsonObject {})
            put("limits", buildJsonObject {})
        }

    private fun kotlinx.serialization.json.JsonElement.nullableString(): String? = if (this == JsonNull) null else jsonPrimitive.content
}

package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BaselineComparisonTest {
    @Test
    fun `verdict gates do not change the comparability key`() {
        val withGates = JsonObject(identity() + ("verdict_gates" to buildJsonObject { put("min_samples_floor", "20") }))

        val comparison =
            compareAnalyses(manualBaselineSelection("release", reference('a')), reference('b'), result(), identity(), result(), withGates)

        comparison.getValue("metrics").jsonArray.forEach { assertEquals(JsonNull, it.jsonObject.getValue("reason")) }
    }

    @Test
    fun `analyses of different arms are not comparable and the same arm is`() {
        val selection = manualBaselineSelection("release", reference('a'))

        fun reasons(
            baselineArm: String?,
            currentArm: String?,
        ) = compareAnalyses(
            selection,
            reference('b'),
            windowResult("steady", 0, 10_000, 100, 100, 0),
            identity(arm = baselineArm),
            windowResult("steady", 0, 10_000, 100, 100, 0),
            identity(arm = currentArm),
            WindowComparisonRequest("steady", "steady"),
        ).getValue("window_comparison").jsonObject.reasons()

        assertEquals(listOf("INCOMPATIBLE_METRIC_DEFINITION"), reasons("A", "B"))
        assertEquals(listOf("INCOMPATIBLE_METRIC_DEFINITION"), reasons("A", null))
        assertEquals(listOf("INCOMPATIBLE_METRIC_DEFINITION"), reasons(null, "A"))
        assertFalse("INCOMPATIBLE_METRIC_DEFINITION" in reasons("A", "A"))
        assertFalse("INCOMPATIBLE_METRIC_DEFINITION" in reasons(null, null))
    }

    @Test
    fun `legacy CSV identity is incompatible with a production CSV identity`() {
        val (old, current) = realCsvIdentities()
        val selection = manualBaselineSelection("release", reference('a'))
        val comparison =
            compareAnalyses(
                selection,
                reference('b'),
                windowResult("steady", 0, 10_000, 100, 100, 0),
                old,
                windowResult("steady", 0, 10_000, 120, 100, 0),
                current,
                WindowComparisonRequest("steady", "steady"),
            )
        val window = comparison.getValue("window_comparison").jsonObject

        assertEquals("NOT_EVALUATED", window.getValue("status").jsonPrimitive.content)
        assertTrue("INCOMPATIBLE_METRIC_DEFINITION" in window.reasons())
        assertTrue(
            comparison.getValue("metrics").jsonArray.all {
                it.jsonObject
                    .getValue("reason")
                    .jsonPrimitive.content == "INCOMPATIBLE_METRIC_DEFINITION"
            },
        )
        assertTrue(
            window.getValue("metrics").jsonArray.all {
                it.jsonObject
                    .getValue("reason")
                    .jsonPrimitive.content == "INCOMPATIBLE_METRIC_DEFINITION"
            },
        )
    }

    @Test
    fun `CSV parser version alone separates slice two from new semantics`() {
        val (old, current) = realCsvIdentities()
        val metricsTwo =
            JsonObject(
                old + (
                    "modules" to
                        JsonArray(
                            old.getValue("modules").jsonArray.map { module ->
                                val value = module.jsonObject
                                if (value.getValue("id").jsonPrimitive.content == "metrics") {
                                    JsonObject(value + ("version" to JsonPrimitive("2")))
                                } else {
                                    value
                                }
                            },
                        )
                ),
            )
        val selection = manualBaselineSelection("release", reference('a'))

        val incompatible = compareAnalyses(selection, reference('b'), result(), metricsTwo, result(), current)
        val compatible = compareAnalyses(selection, reference('b'), result(), current, result(), current)

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
        assertEquals(
            JsonNull,
            compatible
                .getValue("metrics")
                .jsonArray
                .first()
                .jsonObject
                .getValue("reason"),
        )
    }

    @Test
    fun `real old and new candidate identities are rejected as mixed semantics`() {
        val (old, current) = realCsvIdentities()
        val candidates = listOf(candidate('a', identity = old), candidate('b', identity = current), candidate('c', identity = current))

        assertEquals(
            "BASELINE_MIXED_SEMANTICS",
            assertThrows(IllegalArgumentException::class.java) {
                statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())
            }.message,
        )
    }

    @Test
    fun `missing baseline metrics take priority over real identity incompatibility`() {
        val (old, current) = realCsvIdentities()
        val comparison =
            compareAnalyses(
                manualBaselineSelection("release", reference('a')),
                reference('b'),
                result(p95 = null, validity = "INVALID"),
                old,
                result(),
                current,
            )

        assertEquals(
            "MISSING_METRIC",
            comparison
                .getValue("metrics")
                .jsonArray
                .first()
                .jsonObject
                .getValue("reason")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `diagnostics module version two versus three separates real identities`() {
        val (_, current) = realCsvIdentities()
        val diagnostic = diagnosticIdentity(current, "3")
        val old = diagnosticIdentity(current, "2")
        val selection = manualBaselineSelection("release", reference('a'))

        val incompatible = compareAnalyses(selection, reference('b'), result(), old, result(), diagnostic)
        val compatible = compareAnalyses(selection, reference('b'), result(), diagnostic, result(), diagnostic)

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
        assertEquals(
            JsonNull,
            compatible
                .getValue("metrics")
                .jsonArray
                .first()
                .jsonObject
                .getValue("reason"),
        )
    }

    private fun diagnosticIdentity(
        base: JsonObject,
        version: String,
    ): JsonObject {
        val input =
            AcceptedInput(
                runId = base.getValue("run_id").jsonPrimitive.content,
                sourceType = SourceType.JMETER_CSV,
                sha256 = base.getValue("input_sha256").jsonPrimitive.content,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val diagnostics =
            DiagnosticValidation.Valid(
                DiagnosticPlanV1("correlation-plan.v1", "0".repeat(64), emptyList(), emptyList()),
                "d".repeat(64),
                byteArrayOf(),
            )
        val identity =
            Json
                .parseToJsonElement(
                    analysisIdentity(input, null, EngineConfig(), diagnostics = diagnostics).decodeToString(),
                ).jsonObject
        return JsonObject(
            identity + (
                "modules" to
                    JsonArray(
                        identity.getValue("modules").jsonArray.map { module ->
                            val value = module.jsonObject
                            if (value.getValue("id").jsonPrimitive.content == "load-resource-diagnostics") {
                                JsonObject(value + ("version" to JsonPrimitive(version)))
                            } else {
                                value
                            }
                        },
                    )
            ),
        )
    }

    private fun realCsvIdentities(): Pair<JsonObject, JsonObject> {
        val old = Json.parseToJsonElement(Files.readString(Path.of("fixtures/slice1/identity/legacy-pre-adr-0016.v1.json"))).jsonObject
        val input =
            AcceptedInput(
                runId = old.getValue("run_id").jsonPrimitive.content,
                sourceType = SourceType.JMETER_CSV,
                sha256 = old.getValue("input_sha256").jsonPrimitive.content,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val current = Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig()).decodeToString()).jsonObject
        return old to current
    }

    @Test
    fun `condition record preserves three states and matches only its exact pair and windows`() {
        val baseline = reference('a')
        val current = reference('b')
        val updatedAt = Instant.parse("2026-09-06T12:34:56Z")

        mapOf(
            "CONFIRMED" to true,
            "NOT_CONFIRMED" to false,
            "UNKNOWN" to null,
        ).forEach { (decision, confirmation) ->
            val record = baselineConditionRecord(baseline, current, null, decision, updatedAt)
            assertEquals(record, validateBaselineCondition(record))
            assertEquals(confirmation, baselineConditionConfirmation(record))
        }

        val windows = WindowComparisonRequest("before", "after")
        val record = baselineConditionRecord(baseline, current, windows, "CONFIRMED", updatedAt)
        assertEquals(
            setOf("schema_version", "baseline", "current", "windows", "decision", "provenance", "updated_at"),
            record.keys,
        )
        assertEquals("local-baseline-conditions.v1", record.getValue("schema_version").jsonPrimitive.content)
        assertEquals("EXPLICIT_LOCAL_ACTION", record.getValue("provenance").jsonPrimitive.content)
        assertEquals("2026-09-06T12:34:56Z", record.getValue("updated_at").jsonPrimitive.content)
        assertTrue(baselineConditionMatches(record, baseline, current, windows))
        assertFalse(baselineConditionMatches(record, baseline, reference('c'), windows))
        assertFalse(baselineConditionMatches(record, baseline, current, WindowComparisonRequest("before", "other")))
        assertFalse(baselineConditionMatches(record, baseline, current, null))

        assertThrows(IllegalArgumentException::class.java) {
            validateBaselineCondition(JsonObject(record + ("updated_at" to JsonPrimitive("not-an-instant"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBaselineCondition(JsonObject(record + ("decision" to JsonPrimitive("MAYBE"))))
        }
    }

    @Test
    fun `explicit comparison context changes interpretation only and does not persist`() {
        val selection = manualBaselineSelection("release", reference('a'))
        val baseline = windowResult("steady", 0, 10_000, 100, 100, 0)
        val current = windowResult("steady", 0, 10_000, 120, 100, 0)

        fun compare(
            confirmed: Boolean? = null,
            currentIdentity: JsonObject = identity(),
        ) = compareAnalyses(
            selection,
            reference('b'),
            baseline,
            identity(),
            current,
            currentIdentity,
            WindowComparisonRequest("steady", "steady"),
            conditionsConfirmed = confirmed,
        )

        val before = compare()
        val confirmed = compare(true)
        assertEquals("USER_CONFIRMED", confirmed.getValue("comparability").jsonPrimitive.content)
        assertEquals(before.getValue("metrics"), confirmed.getValue("metrics"))
        val p95 =
            confirmed
                .getValue("window_comparison")
                .jsonObject
                .getValue("metrics")
                .jsonArray[1]
                .jsonObject
        assertWindowMetric(p95, "120", "100", "20", "20", "CANDIDATE", null)
        assertEquals(before, compare(false))
        assertEquals(before, compare())
        assertEquals(
            "NOT_EVALUATED",
            compare(true, identity("other"))
                .getValue("window_comparison")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
    }

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
                conditionsConfirmed = true,
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

        assertEquals(setOf("baseline", "current", "comparability", "warnings", "metrics"), comparison.keys)
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
                conditionsConfirmed = true,
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
                conditionsConfirmed = true,
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
                conditionsConfirmed = true,
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
    fun `statistical membership never confirms comparability and is reported as a warning`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())

        val member = compareAnalyses(selection, reference('c'), result(), identity(), result(), identity())
        assertEquals("UNCONFIRMED", member.getValue("comparability").jsonPrimitive.content)
        assertEquals(listOf("CURRENT_IN_CANDIDATE_SET"), warnings(member))

        val rejected =
            compareAnalyses(selection, reference('c'), result(), identity(), result(), identity(), conditionsConfirmed = false)
        assertEquals("UNCONFIRMED", rejected.getValue("comparability").jsonPrimitive.content)

        val confirmed =
            compareAnalyses(selection, reference('c'), result(), identity(), result(), identity(), conditionsConfirmed = true)
        assertEquals("USER_CONFIRMED", confirmed.getValue("comparability").jsonPrimitive.content)
        assertEquals(member.getValue("metrics"), confirmed.getValue("metrics"))
        assertEquals(listOf("CURRENT_IN_CANDIDATE_SET"), warnings(confirmed))

        val outsider = compareAnalyses(selection, reference('d'), result(), identity(), result(), identity())
        assertEquals("UNCONFIRMED", outsider.getValue("comparability").jsonPrimitive.content)
        assertEquals(emptyList<String>(), warnings(outsider))
    }

    @Test
    fun `comparison reports a run compared with itself at analysis and run level`() {
        val selection = manualBaselineSelection("release", reference('a'))

        fun warningsFor(current: JsonObject) = warnings(compareAnalyses(selection, current, result(), identity(), result(), identity()))

        assertEquals(listOf("BASELINE_IS_CURRENT_ANALYSIS"), warningsFor(reference('a')))
        assertEquals(listOf("BASELINE_IS_CURRENT_RUN"), warningsFor(reference('a', analysis = 'b')))
        assertEquals(emptyList<String>(), warningsFor(reference('b')))
    }

    @Test
    fun `statistical winner compared with itself reports both the analysis and the candidate set`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())
        val winner = selection.getValue("reference").jsonObject
        val winnerRun = winner.getValue("run_id").jsonPrimitive.content

        val itself = compareAnalyses(selection, winner, result(), identity(), result(), identity())
        assertEquals(listOf("BASELINE_IS_CURRENT_ANALYSIS", "CURRENT_IN_CANDIDATE_SET"), warnings(itself))

        val sameRun =
            buildJsonObject {
                put("run_id", winnerRun)
                put("analysis_id", "d".repeat(64))
            }
        val other = compareAnalyses(selection, sameRun, result(), identity(), result(), identity())
        assertEquals(listOf("BASELINE_IS_CURRENT_RUN", "CURRENT_IN_CANDIDATE_SET"), warnings(other))
    }

    @Test
    fun `another analysis of a candidate run is still a candidate`() {
        val candidates = listOf(candidate('a'), candidate('b'), candidate('c'))
        val selection = statisticalBaselineSelection("release", candidates.references(), candidates.results(), candidates.identities())

        val comparison = compareAnalyses(selection, reference('c', analysis = 'd'), result(), identity(), result(), identity())

        assertEquals(listOf("CURRENT_IN_CANDIDATE_SET"), warnings(comparison))
    }

    @Test
    fun `empty current window makes load rows unavailable instead of a 100 percent change`() {
        val selection = manualBaselineSelection("release", reference('a'))
        listOf(false, true).forEach { nullLatency ->
            val comparison =
                compareAnalyses(
                    selection,
                    reference('b'),
                    windowResult("steady", 0, 10_000, 500, 100, 0),
                    identity(),
                    emptyWindowResult("steady", 0, 10_000, nullLatency),
                    identity(),
                    WindowComparisonRequest("steady", "steady"),
                    conditionsConfirmed = true,
                )
            val window = comparison.getValue("window_comparison").jsonObject

            assertEquals("INSUFFICIENT_DATA", window.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("CURRENT_WINDOW_EMPTY", "INCOMPLETE_METRICS"), window.reasons())
            val rows = window.getValue("metrics").jsonArray.map { it.jsonObject }
            assertEquals(5, rows.size)
            rows.forEach { row ->
                assertEquals("INSUFFICIENT_DATA", row.getValue("status").jsonPrimitive.content)
                assertEquals("EMPTY_WINDOW", row.getValue("reason").jsonPrimitive.content)
                assertEquals(JsonNull, row.getValue("current"))
                assertEquals(JsonNull, row.getValue("delta"))
                assertEquals(JsonNull, row.getValue("delta_percent"))
            }
            assertEquals(
                "500",
                window
                    .row("response_time_p95_ms")
                    .getValue("baseline")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `empty baseline window makes load rows unavailable instead of a numeric delta`() {
        listOf(false, true).forEach { nullLatency ->
            val comparison =
                compareAnalyses(
                    manualBaselineSelection("release", reference('a')),
                    reference('b'),
                    emptyWindowResult("steady", 0, 10_000, nullLatency),
                    identity(),
                    windowResult("steady", 0, 10_000, 500, 100, 0),
                    identity(),
                    WindowComparisonRequest("steady", "steady"),
                    conditionsConfirmed = true,
                )
            val window = comparison.getValue("window_comparison").jsonObject

            assertEquals("INSUFFICIENT_DATA", window.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("BASELINE_WINDOW_EMPTY", "INCOMPLETE_METRICS"), window.reasons())
            val rows = window.getValue("metrics").jsonArray.map { it.jsonObject }
            assertEquals(5, rows.size)
            rows.forEach { row ->
                assertEquals("INSUFFICIENT_DATA", row.getValue("status").jsonPrimitive.content)
                assertEquals("EMPTY_WINDOW", row.getValue("reason").jsonPrimitive.content)
                assertEquals(JsonNull, row.getValue("baseline"))
                assertEquals(JsonNull, row.getValue("delta"))
                assertEquals(JsonNull, row.getValue("delta_percent"))
            }
            assertEquals(
                "500",
                window
                    .row("response_time_p95_ms")
                    .getValue("current")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `two empty windows keep the reason order and stay unconfirmed until decided`() {
        val comparison =
            compareAnalyses(
                manualBaselineSelection("release", reference('a')),
                reference('b'),
                emptyWindowResult("steady", 0, 10_000, nullLatency = false),
                identity(),
                emptyWindowResult("steady", 0, 10_000, nullLatency = true),
                identity(),
                WindowComparisonRequest("steady", "steady"),
            )
        val window = comparison.getValue("window_comparison").jsonObject

        assertEquals("INSUFFICIENT_DATA", window.getValue("status").jsonPrimitive.content)
        assertEquals(
            listOf("CONDITIONS_UNCONFIRMED", "BASELINE_WINDOW_EMPTY", "CURRENT_WINDOW_EMPTY", "INCOMPLETE_METRICS"),
            window.reasons(),
        )
    }

    @Test
    fun `empty window turns resource rows into unavailable observations and keeps resource values visible`() {
        val binding = resourceBinding("cpu", "checkout")
        val otherBinding = resourceBinding("cpu", "payment")
        val baseline =
            windowResult(
                "steady",
                0,
                10_000,
                100,
                100,
                0,
                listOf(binding, otherBinding),
                listOf(resourceSummary("steady", binding, "10", "20")),
            )

        fun window(current: JsonObject) =
            compareAnalyses(
                manualBaselineSelection("release", reference('a')),
                reference('b'),
                baseline,
                identity(),
                current,
                identity(),
                WindowComparisonRequest("steady", "steady"),
                conditionsConfirmed = true,
            ).getValue("window_comparison").jsonObject

        val filled =
            window(
                windowResult("steady", 0, 10_000, 100, 100, 0, listOf(binding), listOf(resourceSummary("steady", binding, "12", "24"))),
            )
        assertWindowMetric(filled.row("cpu_usage_median"), "12", "10", "2", "20", "CANDIDATE", null)

        val empty =
            window(
                emptyWindowResult(
                    "steady",
                    0,
                    10_000,
                    nullLatency = false,
                    bindings = listOf(binding),
                    resources = listOf(resourceSummary("steady", binding, "12", "24")),
                ),
            )
        assertEquals("INSUFFICIENT_DATA", empty.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("CURRENT_WINDOW_EMPTY", "INCOMPLETE_METRICS"), empty.reasons())
        assertWindowMetric(empty.row("cpu_usage_median"), "12", "10", null, null, "INSUFFICIENT_DATA", "EMPTY_WINDOW")
        assertWindowMetric(empty.row("cpu_usage_q95"), "24", "20", null, null, "INSUFFICIENT_DATA", "EMPTY_WINDOW")
        assertWindowMetric(empty.row("cpu_usage_median:payment"), null, null, null, null, "INSUFFICIENT_DATA", "EMPTY_WINDOW")
        empty.getValue("metrics").jsonArray.forEach { row ->
            assertEquals(
                "INSUFFICIENT_DATA",
                row.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "EMPTY_WINDOW",
                row.jsonObject
                    .getValue("reason")
                    .jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `empty window with an incompatible definition or a missing window stays not evaluated`() {
        val selection = manualBaselineSelection("release", reference('a'))
        val empty = emptyWindowResult("steady", 0, 10_000, nullLatency = false)
        val filled = windowResult("steady", 0, 10_000, 100, 100, 0)

        val incompatible =
            compareAnalyses(
                selection,
                reference('b'),
                empty,
                identity(),
                filled,
                identity("other"),
                WindowComparisonRequest("steady", "steady"),
            )
        val missing =
            compareAnalyses(selection, reference('b'), empty, identity(), filled, identity(), WindowComparisonRequest("steady", "gone"))

        val incompatibleWindow = incompatible.getValue("window_comparison").jsonObject
        assertEquals("NOT_EVALUATED", incompatibleWindow.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("INCOMPATIBLE_METRIC_DEFINITION"), incompatibleWindow.reasons())
        val missingWindow = missing.getValue("window_comparison").jsonObject
        assertEquals("NOT_EVALUATED", missingWindow.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("CURRENT_WINDOW_NOT_FOUND"), missingWindow.reasons())
    }

    private fun warnings(comparison: JsonObject): List<String> = comparison.getValue("warnings").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.reasons(): List<String> = getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.row(metric: String): JsonObject =
        getValue("metrics").jsonArray.map { it.jsonObject }.single { it.getValue("metric").jsonPrimitive.content == metric }

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

    private fun emptyWindowResult(
        windowId: String,
        from: Long,
        to: Long,
        nullLatency: Boolean,
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
                        put("sample_count", 0L)
                        put("error_count", 0L)
                        put("error_rate_ratio", JsonNull)
                        put("throughput_rps", ratio(0L to (to - from)))
                        put(
                            "latency_ms",
                            buildJsonObject {
                                listOf("p50", "p95", "p99", "max").forEach { name ->
                                    put(name, if (nullLatency) JsonNull else JsonPrimitive(0L))
                                }
                            },
                        )
                        put("resource_bindings", buildJsonArray { bindings.forEach(::add) })
                    },
                )
                resources.forEach(::add)
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

    private fun identity(
        version: String = "same",
        arm: String? = null,
    ) = buildJsonObject {
        put("source_type", "jmeter_jtl_csv")
        put("engine", buildJsonObject { put("version", version) })
        put("parsers", JsonArray(emptyList()))
        put("modules", JsonArray(emptyList()))
        put("input_versions", buildJsonObject {})
        put("outputs", buildJsonObject {})
        put("histogram", buildJsonObject {})
        put("normalization", buildJsonObject {})
        put("limits", buildJsonObject {})
        arm?.let { put("resource_arm", it) }
    }

    private fun kotlinx.serialization.json.JsonElement.nullableString(): String? = if (this == JsonNull) null else jsonPrimitive.content
}

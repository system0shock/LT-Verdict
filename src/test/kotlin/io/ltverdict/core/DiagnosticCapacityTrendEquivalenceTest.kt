package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * W2.1 slice 2b: every typed builder of the diagnostic, capacity and trend families writes the same JsonObject, and so the same
 * canonical bytes, as the hand-built one it replaced. The oracles are LegacyDiagnosticItems.kt, LegacyCapacityKnee.kt,
 * LegacyCapacityAnalysis.kt and LegacyTrendAnalysis.kt, frozen copies of the old code. The same inputs go to both.
 */
class DiagnosticCapacityTrendEquivalenceTest {
    private var compared = 0

    private fun outcome(item: JsonElement): String =
        runCatching { canonicalJson(item).decodeToString() }.getOrElse { "FAILS: ${it.message}" }

    // A kotlinx encoding of the in-memory item must also stay what it was: it writes a plain JsonPrimitive number through
    // Double ("0.10" becomes 0.1) and an unquoted literal verbatim, although the two are equal.
    private fun kotlinxLeaves(
        element: JsonElement,
        path: String = "",
        into: MutableMap<String, String> = sortedMapOf(),
    ): Map<String, String> {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> kotlinxLeaves(value, "$path/$key", into) }
            is JsonArray -> element.forEachIndexed { index, value -> kotlinxLeaves(value, "$path[$index]", into) }
            is JsonPrimitive ->
                into[path] = runCatching { Json.encodeToString(JsonElement.serializer(), element) }.getOrElse { "FAILS: ${it::class}" }
        }
        return into
    }

    private fun same(
        expected: JsonObject,
        actual: JsonObject,
        label: String,
    ) {
        assertEquals(expected, actual, label)
        assertEquals(outcome(expected), outcome(actual), label)
        assertEquals(kotlinxLeaves(expected), kotlinxLeaves(actual), label)
        compared++
    }

    private fun sameList(
        expected: List<JsonObject>,
        actual: List<JsonObject>,
        label: String,
    ) {
        assertEquals(expected.size, actual.size, label)
        expected.indices.forEach { same(expected[it], actual[it], "$label[$it]") }
    }

    private fun <T> attempt(block: () -> T): Result<T> = runCatching(block)

    private fun <T> sameFailure(
        expected: Result<T>,
        actual: Result<T>,
        label: String,
    ): Boolean {
        assertEquals(expected.isSuccess, actual.isSuccess, "$label: one of them failed: $expected $actual")
        if (expected.isFailure) {
            assertEquals(expected.exceptionOrNull()!!::class, actual.exceptionOrNull()!!::class, label)
            assertEquals(expected.exceptionOrNull()!!.message, actual.exceptionOrNull()!!.message, label)
        }
        return expected.isSuccess
    }

    @Test
    fun `trend evidence, findings and payload equal the old module`() {
        DerivedItemsFixtures.trendCases().forEach { case ->
            val old = attempt { legacyEvaluateTrend(case.plan, case.snapshot, case.windows) }
            val new = attempt { evaluateTrend(case.plan, case.snapshot, case.windows) }
            if (sameFailure(old, new, case.name)) {
                sameList(old.getOrThrow().evidence, new.getOrThrow().evidence, "${case.name} evidence")
                sameList(old.getOrThrow().findings, new.getOrThrow().findings, "${case.name} findings")
                same(old.getOrThrow().trendJson, new.getOrThrow().trendJson, "${case.name} payload")
            }
            for (reason in listOf("RUN_NOT_VALID", "é\u0001\"")) {
                val oldUnavailable = legacyTrendUnavailable(case.plan, reason)
                val newUnavailable = trendUnavailable(case.plan, reason)
                sameList(oldUnavailable.evidence, newUnavailable.evidence, "${case.name} unavailable evidence")
                same(oldUnavailable.trendJson, newUnavailable.trendJson, "${case.name} unavailable payload")
            }
        }
        assertTrue(compared > 100, "compared only $compared items")
    }

    @Test
    fun `capacity evidence and payload equal the old module and the payload is the summary without id and type`() {
        DerivedItemsFixtures.capacityCases().forEach { case ->
            val old =
                attempt {
                    legacyEvaluateCapacity(
                        case.plan,
                        case.resources,
                        case.load,
                        case.validity,
                        case.windowPolicy,
                        case.windowMetrics,
                    )
                }
            val new =
                attempt { evaluateCapacity(case.plan, case.resources, case.load, case.validity, case.windowPolicy, case.windowMetrics) }
            if (sameFailure(old, new, case.name)) {
                val expected = old.getOrThrow()
                val actual = new.getOrThrow()
                sameList(expected.evidence, actual.evidence, "${case.name} evidence")
                same(expected.capacityJson, actual.capacityJson, "${case.name} payload")
                assertEquals(expected.policyVerdict, actual.policyVerdict, case.name)
                assertEquals(expected.coverageReasons, actual.coverageReasons, case.name)
                // the stored artifact is the canonical bytes of the payload
                assertEquals(outcome(expected.capacityJson), outcome(actual.capacityJson), "${case.name} artifact")
                assertEquals(
                    JsonObject(actual.evidence.first().filterKeys { it != "id" && it != "type" }),
                    actual.capacityJson,
                    "${case.name}: payload is the summary evidence without id and type",
                )
            }
        }
    }

    @Test
    fun `knee evidence equals the old builder on stages, wide loads and refusals`() {
        DerivedItemsFixtures.kneeCases().forEach { (name, axis, input) ->
            val old = attempt { legacyCapacityKneeEvidence(axis, input.first, input.second) }
            val new = attempt { capacityKneeEvidence(axis, input.first, input.second) }
            if (sameFailure(old, new, name)) same(old.getOrThrow(), new.getOrThrow(), name)
        }
        assertTrue(compared >= DerivedItemsFixtures.kneeCases().size - 1)
    }

    private val windows = DerivedItemsFixtures.let { listOf(it.window("steady", 0, 40_000), it.window("w-é\u0001😀", 40_000, 60_000)) }

    private fun anomaly(
        id: String,
        reference: String,
        window: String,
    ) = DiagnosticAnomalyV1(
        id,
        DiagnosticSignalV1.Resource("cpu"),
        reference,
        window,
        DiagnosticDirection.EITHER,
        BigDecimal.ONE,
        1_000,
        BigDecimal("3.5"),
    )

    @Test
    fun `anomaly checks equal the old builder`() {
        val decimals =
            listOf(
                null,
                BigDecimal.ZERO,
                BigDecimal("100"),
                BigDecimal("12345678901234567890.5"),
                BigDecimal("-0.0"),
                BigDecimal("1E+5"),
                BigDecimal("0.10"),
            )
        val counts = listOf(0, 1, Int.MAX_VALUE)
        for (id in listOf("anomaly-check-x", "é\u0001\"")) {
            for (status in listOf("CANDIDATE", "NO_MATERIAL_CHANGE", "INSUFFICIENT_DATA")) {
                for (median in decimals) {
                    for (mad in decimals.take(4)) {
                        for (count in counts) {
                            for (reasons in listOf(
                                emptyList(),
                                listOf("ZERO_MAD"),
                                listOf("REFERENCE_GAPS", "REFERENCE_GAPS", "é\u0001"),
                            )) {
                                val rule = anomaly("rule-$count", "ref", "eval")
                                val reference = windows[0]
                                val evaluation = windows[1]
                                same(
                                    legacyAnomalyCheck(
                                        rule,
                                        id,
                                        reference,
                                        evaluation,
                                        status,
                                        median,
                                        mad,
                                        count,
                                        count,
                                        1,
                                        count,
                                        0,
                                        count,
                                        reasons,
                                    ),
                                    anomalyCheck(
                                        rule,
                                        id,
                                        reference,
                                        evaluation,
                                        status,
                                        median,
                                        mad,
                                        count,
                                        count,
                                        1,
                                        count,
                                        0,
                                        count,
                                        reasons,
                                    ),
                                    "$id $status $median $mad $count $reasons",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private val resourceSeries =
        listOf(
            ResourceSeriesV1(
                "z",
                "cpu_used",
                "ratio",
                "host é",
                ResourceRole.SYSTEM,
                ResourceAggregation.INTERVAL_MEAN,
                emptyMap(),
                emptyList(),
            ),
            ResourceSeriesV1(
                "a",
                "mem",
                "bytes",
                "vm\"\\",
                ResourceRole.GENERATOR,
                ResourceAggregation.INTERVAL_MAX,
                mapOf("arm" to "A", "k\"é" to "v\\\u0001", "" to ""),
                emptyList(),
            ),
        )

    @Test
    fun `window metric summaries equal the old builder`() {
        val metrics =
            DerivedItemsFixtures.richMetrics(
                windows + DerivedItemsFixtures.window("third", 60_000, 61_000) + DerivedItemsFixtures.window("fourth", 61_000, 70_000),
            )
        for (window in metrics.keys.map { id ->
            (
                windows + DerivedItemsFixtures.window("third", 60_000, 61_000) +
                    DerivedItemsFixtures.window("fourth", 61_000, 70_000)
            ).first { it.id == id }
        }) {
            for (series in listOf(emptyList(), resourceSeries, resourceSeries.reversed())) {
                same(
                    legacyWindowMetricSummary(window, metrics.getValue(window.id), series),
                    windowMetricSummary(window, metrics.getValue(window.id), series),
                    "${window.id} ${series.size}",
                )
            }
        }
        val empty = DerivedItemsFixtures.emptyMetrics(windows)
        windows.forEach {
            same(
                legacyWindowMetricSummary(it, empty.getValue(it.id), resourceSeries),
                windowMetricSummary(it, empty.getValue(it.id), resourceSeries),
                it.id,
            )
        }
    }

    @Test
    fun `diagnostic summaries equal the old builder`() {
        for (status in listOf("COMPLETE", "LIMIT_EXCEEDED", "NOT_EVALUATED", "é\u0001")) {
            for (count in listOf(0, 3, Int.MAX_VALUE)) {
                for (reasons in listOf(emptyList(), listOf("DIAGNOSTIC_EPISODE_LIMIT_EXCEEDED"), listOf("A", "A", "é"))) {
                    same(
                        legacyDiagnosticSummary(status, count, count, count, count, count, reasons),
                        diagnosticSummary(status, count, count, count, count, count, reasons),
                        "$status $count $reasons",
                    )
                    same(
                        legacyDiagnosticSummary(status, count, 1, 2, 3, 4, reasons, "é\u0001salt"),
                        diagnosticSummary(status, count, 1, 2, 3, 4, reasons, "é\u0001salt"),
                        "$status $count $reasons salted",
                    )
                }
            }
        }
    }

    @Test
    fun `headline selection evidence equals the old builder`() {
        val pValues = listOf(null, 0.0, 0.001, 1.0, 1e-20, 0.05, 0.016666666666666666, 123456789.123456789)
        for (status in CorrelationHeadlineSelectionStatus.entries) {
            for (p in pValues) {
                for (alpha in listOf(0.05, 0.025, 0.016666666666666666, 1e-12)) {
                    for (reasons in listOf(emptyList(), listOf("PAIR_NOT_EVALUABLE"), listOf("A", "é\u0001"))) {
                        val selection =
                            CorrelationHeadlineSelection(
                                "pair-é",
                                "w\u0001",
                                status,
                                3,
                                Int.MAX_VALUE,
                                alpha,
                                p,
                                p?.let { it / 2 },
                                p?.let { it * 3 },
                                p?.let { minOf(1.0, it * 3) },
                                status == CorrelationHeadlineSelectionStatus.SELECTED,
                                reasons,
                            )
                        for (cells in listOf(0 to 0, 42 to 41, Int.MAX_VALUE to Int.MAX_VALUE)) {
                            same(
                                with(selection) { legacyEvidenceOf(cells.first, cells.second) },
                                with(selection) { evidenceOf(cells.first, cells.second) },
                                "$status $p $alpha $reasons $cells",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun CorrelationHeadlineSelection.legacyEvidenceOf(
        sourceCells: Int,
        analysedPoints: Int,
    ) = legacyEvidence(sourceCells, analysedPoints)

    private fun CorrelationHeadlineSelection.evidenceOf(
        sourceCells: Int,
        analysedPoints: Int,
    ) = evidence(sourceCells, analysedPoints)
}

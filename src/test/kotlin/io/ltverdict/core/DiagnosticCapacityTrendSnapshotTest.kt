package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 slice 2b characterization of the diagnostic, capacity and trend producers: the findings, evidence and payloads they write
 * for a wide matrix of inputs hash to the values captured from `origin/main` BEFORE the producers were typed. Regenerate only on
 * purpose with `LTV_UPDATE_DERIVED_EVIDENCE=1` (a change here is a change of analysis-result bytes and of analysis_id).
 */
class DiagnosticCapacityTrendSnapshotTest {
    private val snapshotFile = Path.of("fixtures/typed-evidence/diagnostic-capacity-trend.sha256")
    private val samplesFile = Path.of("fixtures/typed-evidence/samples-diagnostic-capacity-trend.ndjson")

    private val groups = linkedMapOf<String, StringBuilder>()
    private val seenTypes = sortedSetOf<String>()
    private val seenStates = sortedSetOf<String>()
    private val samples = sortedMapOf<String, JsonObject>()

    private fun outcome(element: JsonElement): String =
        runCatching {
            canonicalJson(element).decodeToString()
        }.getOrElse { "FAILS: ${it.message}" }

    // path -> "null" or "value" for every leaf (arrays are walked, their elements share the path)
    private fun states(
        path: String,
        element: JsonElement,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> states("$path.$key", value) }
            is JsonArray -> {
                seenStates += "$path#${if (element.isEmpty()) "empty" else "items"}"
                element.forEach { states("$path[]", it) }
            }
            is JsonNull -> seenStates += "$path=null"
            else -> seenStates += "$path=value"
        }
    }

    private fun record(
        group: String,
        findings: List<JsonObject>,
        evidence: List<JsonObject>,
        extra: JsonObject = JsonObject(emptyMap()),
    ) {
        (findings + evidence).forEach { item ->
            val type = item.getValue("type").jsonPrimitive.content
            seenTypes += type
            states(type, item)
            samples.putIfAbsent("$type:${item.keys.sorted()}", item)
        }
        val text =
            listOf(
                findings.joinToString("\n") { outcome(it) },
                evidence.joinToString("\n") { outcome(it) },
                outcome(extra),
            ).joinToString("\n--\n")
        groups.getOrPut(group) { StringBuilder() }.append(sha256Hex(text.encodeToByteArray())).append('\n')
    }

    private fun recordFailure(
        group: String,
        error: Throwable,
    ) {
        groups
            .getOrPut(group) {
                StringBuilder()
            }.append(sha256Hex("THROWS ${error::class.simpleName}: ${error.message}".encodeToByteArray()))
            .append('\n')
    }

    @Test
    fun `diagnostic, capacity and trend findings and evidence equal the pre-typing snapshot`() {
        DerivedItemsFixtures.diagnosticCases().forEach { case ->
            runCatching { evaluateDiagnostics(case.plan, case.resources, case.windows, case.load, case.metrics) }
                .onSuccess { record("diagnostic.${case.name}", it.findings, it.evidence) }
                .onFailure { recordFailure("diagnostic.${case.name}", it) }
        }
        listOf(
            "COMPLETE" to emptyList<String>(),
            "LIMIT_EXCEEDED" to listOf("DIAGNOSTIC_EPISODE_LIMIT_EXCEEDED"),
            "NOT_EVALUATED" to listOf("é\u0001"),
        ).forEach {
            val case = DerivedItemsFixtures.diagnosticCases().first()
            val unavailable = diagnosticUnavailable(case.plan.plan, it.first, it.second.firstOrNull() ?: "NONE")
            record("diagnostic-unavailable.${it.first}", unavailable.findings, unavailable.evidence)
        }

        DerivedItemsFixtures.capacityCases().forEach { case ->
            runCatching {
                evaluateCapacity(case.plan, case.resources, case.load, case.validity, case.windowPolicy, case.windowMetrics)
            }.onSuccess {
                record(
                    "capacity.${case.name}",
                    emptyList(),
                    it.evidence,
                    JsonObject(
                        mapOf(
                            "verdict" to JsonPrimitive(it.policyVerdict.name),
                            "reasons" to JsonArray(it.coverageReasons.map(::JsonPrimitive)),
                            "capacity" to it.capacityJson,
                        ),
                    ),
                )
            }.onFailure { recordFailure("capacity.${case.name}", it) }
        }
        DerivedItemsFixtures.kneeCases().forEach { (name, axis, input) ->
            runCatching { capacityKneeEvidence(axis, input.first, input.second) }
                .onSuccess { record("knee.$name", emptyList(), listOf(it)) }
                .onFailure { recordFailure("knee.$name", it) }
        }

        DerivedItemsFixtures.trendCases().forEach { case ->
            runCatching { evaluateTrend(case.plan, case.snapshot, case.windows) }
                .onSuccess { record("trend.${case.name}", it.findings, it.evidence, it.trendJson) }
                .onFailure { recordFailure("trend.${case.name}", it) }
            runCatching { trendUnavailable(case.plan, "RUN_NOT_VALID") }
                .onSuccess { record("trend-unavailable.${case.name}", it.findings, it.evidence, it.trendJson) }
                .onFailure { recordFailure("trend-unavailable.${case.name}", it) }
        }

        val expectedTypes =
            setOf(
                "correlation_pair",
                "correlation_candidate",
                "anomaly_episode",
                "anomaly_check",
                "window_metric_summary",
                "diagnostic_summary",
                "correlation_headline_selection",
                "capacity_summary",
                "capacity_knee_diagnostic",
                "trend_check",
                "resource_trend",
                "trend_summary",
            )
        assertTrue(seenTypes.containsAll(expectedTypes), "snapshot does not reach every type: ${expectedTypes - seenTypes}")
        val expectedStates =
            listOf(
                "correlation_pair.raw_rho=null",
                "correlation_pair.raw_rho=value",
                "correlation_pair.partial_rho=null",
                "correlation_pair.partial_rho=value",
                "correlation_pair.best_lag_rho=null",
                "correlation_pair.best_lag_rho=value",
                "correlation_pair.sensitivity_without_achieved_rps=null",
                "correlation_pair.sensitivity_without_achieved_rps=value",
                "correlation_pair.lag_profile#empty",
                "correlation_pair.lag_profile#items",
                "correlation_pair.lag_profile[].rho=null",
                "correlation_pair.lag_profile[].rho=value",
                "correlation_pair.controls_used#empty",
                "correlation_pair.controls_used#items",
                "correlation_pair.controls_dropped#items",
                "correlation_pair.controls_requested#empty",
                "correlation_pair.reasons#empty",
                "correlation_pair.reasons#items",
                "correlation_headline_selection.p_value_b10=null",
                "correlation_headline_selection.p_value_b10=value",
                "correlation_headline_selection.holm_adjusted_p_value=null",
                "correlation_headline_selection.holm_adjusted_p_value=value",
                "correlation_headline_selection.max_p_value=null",
                "correlation_headline_selection.max_p_value=value",
                "correlation_headline_selection.reasons#empty",
                "correlation_headline_selection.reasons#items",
                "correlation_headline_selection.selected=value",
                "anomaly_check.reference_median=null",
                "anomaly_check.reference_median=value",
                "anomaly_check.reference_mad=null",
                "anomaly_check.reference_mad=value",
                "anomaly_check.reasons#empty",
                "anomaly_check.reasons#items",
                "anomaly_episode.reasons#empty",
                "anomaly_episode.reasons#items",
                "window_metric_summary.error_rate_ratio=null",
                "window_metric_summary.error_rate_ratio.numerator=value",
                "window_metric_summary.latency_ms.p50=null",
                "window_metric_summary.latency_ms.p50=value",
                "window_metric_summary.latency_ms.max=null",
                "window_metric_summary.latency_ms.max=value",
                "window_metric_summary.resource_bindings#items",
                "window_metric_summary.resource_bindings[].labels.arm=value",
                "diagnostic_summary.reasons#empty",
                "diagnostic_summary.reasons#items",
                "capacity_summary.lower_inclusive=null",
                "capacity_summary.lower_inclusive=value",
                "capacity_summary.upper_exclusive=null",
                "capacity_summary.upper_exclusive=value",
                "capacity_summary.stages[].achieved=null",
                "capacity_summary.stages[].achieved=value",
                "capacity_summary.stages[].observed_min=null",
                "capacity_summary.stages[].observed_min=value",
                "capacity_summary.stages[].verified_bound_load=null",
                "capacity_summary.stages[].verified_bound_load=value",
                "capacity_summary.stages[].reasons#empty",
                "capacity_summary.stages[].reasons#items",
                "capacity_summary.stages[].evidence_refs#empty",
                "capacity_summary.stages[].evidence_refs#items",
                "capacity_summary.capacity_knee=null",
                "capacity_summary.reasons#empty",
                "capacity_summary.reasons#items",
                "capacity_knee_diagnostic.last_stable_stage_id=null",
                "capacity_knee_diagnostic.last_stable_stage_id=value",
                "capacity_knee_diagnostic.last_stable_load=null",
                "capacity_knee_diagnostic.last_stable_load=value",
                "capacity_knee_diagnostic.first_degraded_load=null",
                "capacity_knee_diagnostic.first_degraded_load=value",
                "capacity_knee_diagnostic.sse_ratio=null",
                "capacity_knee_diagnostic.sse_ratio=value",
                "capacity_knee_diagnostic.excess_factor=null",
                "capacity_knee_diagnostic.excess_factor=value",
                "capacity_knee_diagnostic.reasons#empty",
                "capacity_knee_diagnostic.reasons#items",
                "capacity_knee_diagnostic.points#empty",
                "capacity_knee_diagnostic.points#items",
                "trend_check.metric=null",
                "trend_check.metric=value",
                "trend_check.window_from_epoch_ms=null",
                "trend_check.window_from_epoch_ms=value",
                "trend_check.median=null",
                "trend_check.median=value",
                "trend_check.slope_per_second=null",
                "trend_check.slope_per_second=value",
                "trend_check.split_half_shift=null",
                "trend_check.split_half_shift=value",
                "trend_check.magnitude_gate.required_split_half_shift_units=null",
                "trend_check.magnitude_gate.required_split_half_shift_units=value",
                "trend_check.observed_direction=null",
                "trend_check.observed_direction=value",
                "trend_check.reasons#items",
                "resource_trend.slope_per_second=value",
                "trend_summary.checks_total=value",
            )
        val missing = expectedStates.filterNot { it in seenStates }
        assertTrue(missing.isEmpty(), "snapshot does not reach: $missing")

        val actual = groups.entries.joinToString("") { (group, hashes) -> "$group ${sha256Hex(hashes.toString().encodeToByteArray())}\n" }
        val update = System.getenv("LTV_UPDATE_DERIVED_EVIDENCE") == "1"
        if (update) Files.writeString(snapshotFile, actual)
        assertEquals(Files.readString(snapshotFile).replace("\r\n", "\n"), actual)

        // One real item per type and key set: ui/scripts/verify-generated-types.mjs checks them against the generated TypeScript.
        val sampleLines = samples.values.joinToString("") { outcome(it) + "\n" }
        if (update) Files.writeString(samplesFile, sampleLines)
        assertEquals(Files.readString(samplesFile).replace("\r\n", "\n"), sampleLines)
    }
}

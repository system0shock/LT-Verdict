package io.ltverdict.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

// Typed shape of the findings and evidence items of the diagnostic, capacity and trend families (W2.1, slice 2b); the policy and
// resource families are in AnalysisItems.kt. The rules are those of AnalysisItems.kt: the literal `type` of an item is the
// @SerialName of its class, a field the engine writes as an explicit JSON null is a nullable without a default (ITEM_JSON has
// explicitNulls = true), a decimal is a String, a count is a Long or an Int, never a Double. Every key of these items is
// always written, so no field has a default. A separate pair of roots, because the roots of AnalysisItems.kt are pinned by
// tests that list their subclasses exactly.

@Serializable
internal sealed interface DerivedFinding {
    val id: String
}

@Serializable
internal sealed interface DerivedEvidence {
    val id: String
}

internal fun DerivedFinding.toJson(): JsonObject = ITEM_JSON.encodeToJsonElement(DerivedFinding.serializer(), this).jsonObject

/** The numbers written by [RawDecimalSerializer] are put back as the plain JsonPrimitive the hand-built items had. */
internal fun DerivedEvidence.toJson(): JsonObject =
    restoreNumbers(ITEM_JSON.encodeToJsonElement(DerivedEvidence.serializer(), this)).jsonObject

/**
 * A BigDecimal number as the old builders wrote it: `JsonPrimitive(BigDecimal)`, content `value.toString()` ("1E+3", "0.10").
 * The builder normalises the value first (`BigDecimal(canonicalDecimal(x))`) where it always did. kotlinx would write a number
 * into the tree through Long or Double, so the encoder gets an unquoted literal that keeps the text; [restoreNumbers] then
 * replaces it with the plain primitive, because a marked literal and a plain one are `equals` but a later kotlinx encoding
 * writes them differently.
 */
internal object RawDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("RawDecimal", PrimitiveKind.DOUBLE)

    override fun serialize(
        encoder: Encoder,
        value: BigDecimal,
    ) {
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.toString()))
    }

    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal((decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content)
}

/**
 * Every number that is not a Long is a RawDecimal (the only other numbers are counts); the test is the text, not `longOrNull`,
 * which also accepts an exponent.
 */
private fun restoreNumbers(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { restoreNumbers(it.value) })
        is JsonArray -> JsonArray(element.map(::restoreNumbers))
        is JsonNull -> element
        is JsonPrimitive ->
            if (element.isString || element.content == "true" || element.content == "false" || element.content.toLongOrNull() != null) {
                element
            } else {
                JsonPrimitive(BigDecimal(element.content))
            }
    }

@Serializable
internal data class LagProfileEntry(
    @SerialName("lag_ms") val lagMs: Long,
    val rho: String?,
)

/** An empty window has no latency: every value is an explicit null (not the Long of [LatencyDocument]). */
@Serializable
internal data class NullableLatencyDocument(
    val p50: Long?,
    val p95: Long?,
    val p99: Long?,
    val max: Long?,
)

@Serializable
internal data class WindowResourceBindingDocument(
    @SerialName("series_id") val seriesId: String,
    val metric: String,
    val unit: String,
    val entity: String,
    val role: String,
    val aggregation: String,
    val labels: Map<String, String>,
)

@Serializable
internal data class CapacityStageDocument(
    val id: String,
    @Serializable(with = RawDecimalSerializer::class) val target: BigDecimal,
    @Serializable(with = RawDecimalSerializer::class) val achieved: BigDecimal?,
    @SerialName("achieved_statistic") val achievedStatistic: String,
    @SerialName("observed_min") @Serializable(with = RawDecimalSerializer::class) val observedMin: BigDecimal?,
    @SerialName("observed_max") @Serializable(with = RawDecimalSerializer::class) val observedMax: BigDecimal?,
    @SerialName("complete_bins") val completeBins: Int,
    @SerialName("expected_bins") val expectedBins: Int,
    @SerialName("target_tolerance_ratio") @Serializable(with = RawDecimalSerializer::class) val targetToleranceRatio: BigDecimal,
    @SerialName("verified_bound_load") @Serializable(with = RawDecimalSerializer::class) val verifiedBoundLoad: BigDecimal?,
    val verdict: String,
    val reasons: List<String>,
    @SerialName("evidence_refs") val evidenceRefs: List<String>,
)

@Serializable
internal data class KneePointDocument(
    @SerialName("stage_id") val stageId: String,
    @Serializable(with = RawDecimalSerializer::class) val load: BigDecimal,
    val value: Long,
)

@Serializable
internal data class KneeParametersDocument(
    @SerialName("min_stages") val minStages: Int,
    @SerialName("min_points_before_knee") val minPointsBeforeKnee: Int,
    @SerialName("max_sse_ratio") val maxSseRatio: String,
    @SerialName("min_excess_factor") val minExcessFactor: String,
    @SerialName("noise_multiplier") val noiseMultiplier: Int,
)

@Serializable
internal data class MagnitudeGateDocument(
    @SerialName("min_slope_units_per_second") val minSlopeUnitsPerSecond: String,
    @SerialName("min_split_half_shift_pct") val minSplitHalfShiftPct: String,
    @SerialName("required_split_half_shift_units") val requiredSplitHalfShiftUnits: String?,
)

@Serializable
@SerialName("correlation_candidate")
internal data class CorrelationCandidateFinding(
    override val id: String,
    @SerialName("pair_id") val pairId: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("evidence_id") val evidenceId: String,
    val uncertainty: String,
) : DerivedFinding

@Serializable
@SerialName("anomaly_episode")
internal data class AnomalyEpisodeFinding(
    override val id: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("reference_window_id") val referenceWindowId: String,
    val metric: String,
    val unit: String,
    val entity: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    val direction: String,
    @SerialName("reference_median") val referenceMedian: String,
    @SerialName("reference_mad") val referenceMad: String,
    @SerialName("observed_min") val observedMin: String,
    @SerialName("observed_max") val observedMax: String,
    @SerialName("max_abs_delta") val maxAbsDelta: String,
    @SerialName("evidence_id") val evidenceId: String,
    val reasons: List<String>,
) : DerivedFinding

@Serializable
@SerialName("resource_trend")
internal data class ResourceTrendFinding(
    override val id: String,
    @SerialName("check_id") val checkId: String,
    @SerialName("series_id") val seriesId: String,
    val metric: String,
    val unit: String,
    val entity: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("observed_direction") val observedDirection: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("expected_cells") val expectedCells: Int,
    @SerialName("observed_cells") val observedCells: Int,
    val median: String,
    @SerialName("slope_per_second") val slopePerSecond: String,
    @SerialName("split_half_shift") val splitHalfShift: String,
    val effect: String,
    val uncertainty: String,
    @SerialName("evidence_id") val evidenceId: String,
) : DerivedFinding

@Serializable
@SerialName("correlation_pair")
internal data class CorrelationPairEvidence(
    override val id: String,
    @SerialName("pair_id") val pairId: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("resource_series_id") val resourceSeriesId: String,
    @SerialName("load_metric") val loadMetric: String,
    val entity: String,
    @SerialName("resource_unit") val resourceUnit: String,
    @SerialName("load_unit") val loadUnit: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("expected_cells") val expectedCells: Int,
    @SerialName("paired_cells") val pairedCells: Int,
    @SerialName("lag_used_cells") val lagUsedCells: Int,
    @SerialName("raw_rho") val rawRho: String?,
    @SerialName("partial_rho") val partialRho: String?,
    @SerialName("best_lag_ms") val bestLagMs: Long,
    @SerialName("best_lag_rho") val bestLagRho: String?,
    @SerialName("lag_profile") val lagProfile: List<LagProfileEntry>,
    val status: String,
    @SerialName("controls_requested") val controlsRequested: List<String>,
    @SerialName("controls_used") val controlsUsed: List<String>,
    @SerialName("controls_dropped") val controlsDropped: List<String>,
    @SerialName("sensitivity_without_achieved_rps") val sensitivityWithoutAchievedRps: String?,
    val reasons: List<String>,
    val uncertainty: String,
) : DerivedEvidence

@Serializable
@SerialName("anomaly_check")
internal data class AnomalyCheckEvidence(
    override val id: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("reference_window_id") val referenceWindowId: String,
    val status: String,
    @SerialName("reference_median") val referenceMedian: String?,
    @SerialName("reference_mad") val referenceMad: String?,
    @SerialName("reference_observed_cells") val referenceObservedCells: Int,
    @SerialName("reference_expected_cells") val referenceExpectedCells: Int,
    @SerialName("observed_cells") val observedCells: Int,
    @SerialName("expected_cells") val expectedCells: Int,
    @SerialName("episodes_reported") val episodesReported: Int,
    @SerialName("suppressed_short_episodes") val suppressedShortEpisodes: Int,
    val reasons: List<String>,
) : DerivedEvidence

@Serializable
@SerialName("window_metric_summary")
internal data class WindowMetricSummaryEvidence(
    override val id: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("sample_count") val sampleCount: Long,
    @SerialName("error_count") val errorCount: Long,
    @SerialName("error_rate_ratio") val errorRateRatio: ExactRatioDocument?,
    @SerialName("throughput_rps") val throughputRps: ExactRatioDocument,
    @SerialName("latency_ms") val latencyMs: NullableLatencyDocument,
    @SerialName("resource_bindings") val resourceBindings: List<WindowResourceBindingDocument>,
) : DerivedEvidence

@Serializable
@SerialName("diagnostic_summary")
internal data class DiagnosticSummaryEvidence(
    override val id: String,
    val status: String,
    @SerialName("pairs_tested") val pairsTested: Int,
    @SerialName("pairs_evaluable") val pairsEvaluable: Int,
    @SerialName("anomalies_tested") val anomaliesTested: Int,
    @SerialName("episodes_reported") val episodesReported: Int,
    @SerialName("suppressed_short_episodes") val suppressedShortEpisodes: Int,
    val uncertainty: String,
    val reasons: List<String>,
) : DerivedEvidence

@Serializable
@SerialName("correlation_headline_selection")
internal data class CorrelationHeadlineSelectionEvidence(
    override val id: String,
    @SerialName("pair_id") val pairId: String,
    @SerialName("window_id") val windowId: String,
    val method: String,
    val rng: String,
    val status: String,
    @SerialName("family_hypotheses") val familyHypotheses: Int,
    @SerialName("family_count") val familyCount: Int,
    val representation: String,
    @SerialName("source_cells") val sourceCells: Int,
    @SerialName("analysed_points") val analysedPoints: Int,
    @SerialName("bootstrap_replicates") val bootstrapReplicates: Int,
    @SerialName("block_lengths_cells") val blockLengthsCells: List<Int>,
    val alpha: String,
    @SerialName("p_value_b10") val pValueB10: String?,
    @SerialName("p_value_b20") val pValueB20: String?,
    @SerialName("max_p_value") val maxPValue: String?,
    @SerialName("holm_adjusted_p_value") val holmAdjustedPValue: String?,
    val selected: Boolean,
    val reasons: List<String>,
) : DerivedEvidence

/** The same fields are the `capacity_summary` payload of analysis-result and of capacity.json, without `id` and `type`. */
@Serializable
@SerialName("capacity_summary")
internal data class CapacitySummaryEvidence(
    override val id: String,
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("load_axis") val loadAxis: String,
    val unit: String,
    val stages: List<CapacityStageDocument>,
    @SerialName("bound_type") val boundType: String,
    @SerialName("lower_inclusive") @Serializable(with = RawDecimalSerializer::class) val lowerInclusive: BigDecimal?,
    @SerialName("upper_exclusive") @Serializable(with = RawDecimalSerializer::class) val upperExclusive: BigDecimal?,
    @SerialName("policy_verdict") val policyVerdict: PolicyVerdict,
    val reasons: List<String>,
    /** Always null: the knee detector is a separate evidence item (ADR 0026). */
    @SerialName("capacity_knee") val capacityKnee: String?,
    @SerialName("knee_reason") val kneeReason: String,
) : DerivedEvidence

@Serializable
@SerialName("capacity_knee_diagnostic")
internal data class CapacityKneeDiagnosticEvidence(
    override val id: String,
    val method: String,
    val metric: String,
    @SerialName("load_axis") val loadAxis: String,
    val unit: String,
    val status: String,
    val confidence: String,
    val calibrated: Boolean,
    @SerialName("diagnostic_only") val diagnosticOnly: Boolean,
    @SerialName("last_stable_stage_id") val lastStableStageId: String?,
    @SerialName("last_stable_load") @Serializable(with = RawDecimalSerializer::class) val lastStableLoad: BigDecimal?,
    @SerialName("first_degraded_stage_id") val firstDegradedStageId: String?,
    @SerialName("first_degraded_load") @Serializable(with = RawDecimalSerializer::class) val firstDegradedLoad: BigDecimal?,
    @SerialName("sse_ratio") @Serializable(with = RawDecimalSerializer::class) val sseRatio: BigDecimal?,
    @SerialName("excess_factor") @Serializable(with = RawDecimalSerializer::class) val excessFactor: BigDecimal?,
    val reasons: List<String>,
    val points: List<KneePointDocument>,
    val parameters: KneeParametersDocument,
) : DerivedEvidence

@Serializable
@SerialName("trend_check")
internal data class TrendCheckEvidence(
    override val id: String,
    @SerialName("check_id") val checkId: String,
    @SerialName("series_id") val seriesId: String,
    val metric: String?,
    val unit: String?,
    val entity: String?,
    @SerialName("window_id") val windowId: String,
    @SerialName("window_from_epoch_ms") val windowFromEpochMs: Long?,
    @SerialName("window_to_epoch_ms") val windowToEpochMs: Long?,
    @SerialName("declared_direction") val declaredDirection: String,
    val status: String,
    @SerialName("min_cells") val minCells: Int,
    @SerialName("expected_cells") val expectedCells: Int,
    @SerialName("observed_cells") val observedCells: Int,
    @SerialName("missing_cells") val missingCells: Int,
    @SerialName("longest_gap_cells") val longestGapCells: Int,
    val median: String?,
    @SerialName("slope_per_second") val slopePerSecond: String?,
    @SerialName("split_half_shift") val splitHalfShift: String?,
    @SerialName("magnitude_gate") val magnitudeGate: MagnitudeGateDocument,
    @SerialName("observed_direction") val observedDirection: String?,
    val method: String,
    val uncertainty: String,
    val reasons: List<String>,
) : DerivedEvidence

@Serializable
@SerialName("trend_summary")
internal data class TrendSummaryEvidence(
    override val id: String,
    @SerialName("checks_total") val checksTotal: Int,
    val observed: Int,
    @SerialName("not_material") val notMaterial: Int,
    val insufficient: Int,
    val unavailable: Int,
    val method: String,
    val uncertainty: String,
) : DerivedEvidence

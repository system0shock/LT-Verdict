package io.ltverdict.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Typed shape of the findings and evidence items of analysis-result (W2.1, slice 2). A builder fills one of these classes
// and encodes it to the JsonObject it used to build by hand, so the callers, the stored JSON and canonicalJson are unchanged.
// Rules for these classes: the literal `type` of an item is the @SerialName of its class (the polymorphic discriminator);
// a field the engine omits is `= null` (and is omitted, encodeDefaults = false); a field the engine writes as an explicit
// JSON null is a nullable without a default (explicitNulls = true); a number that is a decimal is a String, a count is a
// Long or Int, never a Double. Only the families that already have classes here are typed; the other items stay JsonObject.

/** Encodes the typed items only; nothing in production decodes with it. Its settings differ from ANALYSIS_DOCUMENT_JSON on purpose. */
internal val ITEM_JSON =
    Json {
        explicitNulls = true
        encodeDefaults = false
        classDiscriminator = "type"
    }

@Serializable
internal sealed interface AnalysisFinding {
    val id: String
}

@Serializable
internal sealed interface AnalysisEvidence {
    val id: String
}

internal fun AnalysisFinding.toJson(): JsonObject = ITEM_JSON.encodeToJsonElement(AnalysisFinding.serializer(), this).jsonObject

internal fun AnalysisEvidence.toJson(): JsonObject = ITEM_JSON.encodeToJsonElement(AnalysisEvidence.serializer(), this).jsonObject

/**
 * A policy threshold is a JsonPrimitive number of any precision ("12345678901234567890.5"). kotlinx would write it into the
 * tree through Long or Double; an unquoted literal keeps the text, as the old `put("threshold", JsonPrimitive(x))` did.
 */
internal object RawNumberSerializer : KSerializer<JsonPrimitive> {
    override val descriptor = PrimitiveSerialDescriptor("RawNumber", PrimitiveKind.DOUBLE)

    override fun serialize(
        encoder: Encoder,
        value: JsonPrimitive,
    ) {
        require(!value.isString) { "a raw number is not a string" }
        (encoder as JsonEncoder).encodeJsonElement(JsonUnquotedLiteral(value.content))
    }

    override fun deserialize(decoder: Decoder): JsonPrimitive = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive
}

@Serializable
internal data class ExactRatioDocument(
    val numerator: Long,
    val denominator: Long,
)

@Serializable
internal data class LatencyDocument(
    val p50: Long,
    val p95: Long,
    val p99: Long,
    val max: Long,
)

/** `kind` is the discriminator; a transaction scope of a rule that found no metric carries only `label`. */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
internal sealed interface MetricScope

@Serializable
@SerialName("overall")
internal data object OverallScope : MetricScope

@Serializable
@SerialName("transaction")
internal data class TransactionScope(
    val label: String,
    @SerialName("group_path") val groupPath: List<String>? = null,
    @SerialName("sample_kind") val sampleKind: String? = null,
) : MetricScope

/** Every key is written, null when the statistic is undefined (the engine never omits one). */
@Serializable
internal data class ResourceStatisticsDocument(
    val min: String?,
    val max: String?,
    val mean: String?,
    val median: String?,
    val q05: String?,
    val q25: String?,
    val q75: String?,
    val q95: String?,
    val iqr: String?,
    val mad: String?,
    @SerialName("sample_standard_deviation") val sampleStandardDeviation: String?,
    @SerialName("slope_per_second") val slopePerSecond: String?,
    @SerialName("split_half_shift") val splitHalfShift: String?,
)

@Serializable
@SerialName("diagnostic")
internal data class DiagnosticFinding(
    override val id: String,
    val code: String,
    @SerialName("evidence_id") val evidenceId: String,
) : AnalysisFinding

@Serializable
@SerialName("policy_failure")
internal data class PolicyFailureFinding(
    override val id: String,
    @SerialName("window_id") val windowId: String? = null,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("evidence_id") val evidenceId: String,
) : AnalysisFinding

@Serializable
@SerialName("resource_threshold_violation")
internal data class ResourceThresholdViolationFinding(
    override val id: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("series_id") val seriesId: String,
    val entity: String,
    val unit: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("cell_count") val cellCount: Int,
    @SerialName("observed_min") val observedMin: String,
    @SerialName("observed_max") val observedMax: String,
    val presumed: Boolean? = null,
    @SerialName("evidence_id") val evidenceId: String,
) : AnalysisFinding

@Serializable
@SerialName("metric_summary")
internal data class MetricSummaryEvidence(
    override val id: String,
    @SerialName("window_id") val windowId: String? = null,
    val scope: MetricScope,
    @SerialName("sample_count") val sampleCount: Long,
    @SerialName("error_count") val errorCount: Long,
    @SerialName("error_rate_ratio") val errorRateRatio: ExactRatioDocument?,
    @SerialName("throughput_rps") val throughputRps: ExactRatioDocument,
    @SerialName("latency_ms") val latencyMs: LatencyDocument,
) : AnalysisEvidence

@Serializable
@SerialName("policy_check")
internal data class PolicyCheckEvidence(
    override val id: String,
    @SerialName("window_id") val windowId: String? = null,
    val scope: MetricScope? = null,
    @SerialName("rule_id") val ruleId: String,
    val metric: String,
    val operator: String,
    @Serializable(with = RawNumberSerializer::class) val threshold: JsonPrimitive,
    val status: String,
    @SerialName("metric_evidence_id") val metricEvidenceId: String? = null,
    val observed: JsonElement? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    @SerialName("sample_count") val sampleCount: Long? = null,
    @SerialName("sample_floor") val sampleFloor: Long? = null,
    @SerialName("min_samples") val minSamples: Long? = null,
    @SerialName("sample_mode") val sampleMode: String? = null,
) : AnalysisEvidence

@Serializable
@SerialName("diagnostic")
internal data class DiagnosticEvidence(
    override val id: String,
    val code: String,
    val message: String,
    @SerialName("source_offset") val sourceOffset: Long? = null,
) : AnalysisEvidence

@Serializable
@SerialName("rule_window_check")
internal data class RuleWindowCheckEvidence(
    override val id: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("window_id") val windowId: String,
    val status: String,
    @SerialName("reason_code") val reasonCode: String,
) : AnalysisEvidence

@Serializable
@SerialName("window_policy_summary")
internal data class WindowPolicySummaryEvidence(
    override val id: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("business_verdict") val businessVerdict: PolicyVerdict,
    @SerialName("resource_verdict") val resourceVerdict: PolicyVerdict,
    val verdict: PolicyVerdict,
    @SerialName("sample_count") val sampleCount: Long,
    @SerialName("min_samples") val minSamples: Long? = null,
) : AnalysisEvidence

@Serializable
@SerialName("resource_summary")
internal data class ResourceSummaryEvidence(
    override val id: String,
    @SerialName("series_id") val seriesId: String,
    val metric: String,
    val unit: String,
    val entity: String,
    val role: String,
    val aggregation: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    @SerialName("expected_cells") val expectedCells: Int,
    @SerialName("observed_cells") val observedCells: Int,
    @SerialName("missing_cells") val missingCells: Int,
    @SerialName("longest_gap_cells") val longestGapCells: Int,
    val statistics: ResourceStatisticsDocument,
    val reasons: List<String>,
) : AnalysisEvidence

@Serializable
@SerialName("resource_policy_check")
internal data class ResourcePolicyCheckEvidence(
    override val id: String,
    @SerialName("window_id") val windowId: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("series_id") val seriesId: String,
    val unit: String,
    val operator: String,
    val threshold: String,
    val effect: String,
    val status: String,
    val reason: String?,
    @SerialName("platform_rule_id") val platformRuleId: String? = null,
    val service: String? = null,
    @SerialName("expected_cells") val expectedCells: Int? = null,
    @SerialName("observed_cells") val observedCells: Int? = null,
    @SerialName("missing_cells") val missingCells: Int? = null,
    @SerialName("longest_gap_cells") val longestGapCells: Int? = null,
) : AnalysisEvidence

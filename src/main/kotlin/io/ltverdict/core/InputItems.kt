package io.ltverdict.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal

// Typed shape of the evidence items that describe the inputs of an analysis: the binding of the resource snapshot to the run and
// what the sources said (W2.1, slice 2c). The rules are those of AnalysisItems.kt and DerivedItems.kt: the literal `type` of an item
// is the @SerialName of its class, a field the engine omits is `= null` (ITEM_JSON has encodeDefaults = false), a required field has
// no default, a decimal of any precision is a BigDecimal written by RawDecimalSerializer, a count is a Long or an Int. A separate
// root, because the roots of AnalysisItems.kt are pinned by tests that list their subclasses exactly.

@Serializable
internal sealed interface InputEvidence {
    val id: String
}

/** The numbers written by [RawDecimalSerializer] are put back as the plain JsonPrimitive the hand-built items had. */
internal fun InputEvidence.toJson(): JsonObject = restoreNumbers(ITEM_JSON.encodeToJsonElement(InputEvidence.serializer(), this)).jsonObject

@Serializable
@SerialName("resource_binding")
internal data class ResourceBindingEvidence(
    override val id: String,
    val mode: String,
    @SerialName("snapshot_from_epoch_ms") val snapshotFromEpochMs: Long,
    @SerialName("snapshot_to_epoch_ms") val snapshotToEpochMs: Long,
    @SerialName("run_from_epoch_ms") val runFromEpochMs: Long,
    @SerialName("run_to_epoch_ms") val runToEpochMs: Long,
    @SerialName("evaluation_from_epoch_ms") val evaluationFromEpochMs: Long,
    @SerialName("evaluation_to_epoch_ms") val evaluationToEpochMs: Long,
    @SerialName("dropped_leading_cells") val droppedLeadingCells: Int,
    @SerialName("dropped_leading_millis") val droppedLeadingMillis: Long,
    @SerialName("dropped_trailing_cells") val droppedTrailingCells: Int,
    @SerialName("dropped_trailing_millis") val droppedTrailingMillis: Long,
    @SerialName("clock_alignment") val clockAlignment: String,
) : InputEvidence

/**
 * One summary for every way a source is read (a Prometheus or InfluxQL profile, an OpenSearch profile, an imported context and
 * several profiles in one snapshot); the sites differ in which optional fields they set. The provenance of the window is merged
 * into the encoded object afterwards (`withWindowProvenance`) and is not part of this class.
 */
@Serializable
@SerialName("source_summary")
internal data class SourceSummaryEvidence(
    override val id: String,
    val status: String,
    @SerialName("profile_id") val profileId: String,
    val arm: String? = null,
    @SerialName("source_kind") val sourceKind: String,
    val transport: String,
    @SerialName("start_epoch_ms") val startEpochMs: Long? = null,
    @SerialName("end_epoch_ms") val endEpochMs: Long? = null,
    @SerialName("step_ms") val stepMs: Long? = null,
    val profiles: List<JsonObject>? = null,
    val queries: List<SourceQueryDocument>,
    @SerialName("rule_spans") val ruleSpans: List<SourceRuleSpanDocument>? = null,
    @SerialName("request_count") val requestCount: Long,
    val retries: Long,
    @SerialName("throttle_wait_ms") val throttleWaitMs: Long,
    @SerialName("cap_exceeded") val capExceeded: Boolean,
) : InputEvidence

/** An imported context has only `id` and `status`; a limit entry has no hash. */
@Serializable
internal data class SourceQueryDocument(
    val id: String,
    val status: String,
    val reason: String? = null,
    @SerialName("expression_sha256") val expressionSha256: String? = null,
)

@Serializable
internal data class SourceRuleSpanDocument(
    @SerialName("rule_id") val ruleId: String,
    @SerialName("declared_span_ms") val declaredSpanMs: Long,
    @SerialName("step_ms") val stepMs: Long,
    val cells: Int,
    @SerialName("effective_span_ms") val effectiveSpanMs: Long,
)

@Serializable
@SerialName("opensearch_errors")
internal data class OpenSearchErrorsEvidence(
    override val id: String,
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("load_input_sha256") val loadInputSha256: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("start_epoch_ms") val startEpochMs: Long,
    @SerialName("end_epoch_ms") val endEpochMs: Long,
    @SerialName("step_ms") val stepMs: Long,
    @SerialName("total_errors") val totalErrors: Long,
    @SerialName("error_rate_per_minute") @Serializable(with = RawDecimalSerializer::class) val errorRatePerMinute: BigDecimal,
    val timeline: List<OpenSearchTimelineCellDocument>,
    val groups: List<OpenSearchGroupDocument>,
    val coverage: OpenSearchCoverageDocument,
) : InputEvidence

@Serializable
internal data class OpenSearchTimelineCellDocument(
    @SerialName("from_epoch_ms") val fromEpochMs: Long,
    @SerialName("to_epoch_ms") val toEpochMs: Long,
    val count: Long,
    @SerialName("rate_per_minute") @Serializable(with = RawDecimalSerializer::class) val ratePerMinute: BigDecimal,
)

@Serializable
internal data class OpenSearchGroupDocument(
    val service: String,
    @SerialName("error_type") val errorType: String,
    val count: Long,
    @SerialName("first_epoch_ms") val firstEpochMs: Long,
    @SerialName("last_epoch_ms") val lastEpochMs: Long,
    val samples: List<OpenSearchSampleDocument>,
)

@Serializable
internal data class OpenSearchSampleDocument(
    @SerialName("timestamp_epoch_ms") val timestampEpochMs: Long,
    val index: String,
    @SerialName("document_id") val documentId: String,
    val message: String,
    @SerialName("message_truncated") val messageTruncated: Boolean,
    @SerialName("source_url") val sourceUrl: String,
)

@Serializable
internal data class OpenSearchCoverageDocument(
    val status: String,
    val reasons: List<String>,
    @SerialName("timed_out") val timedOut: Boolean,
    @SerialName("total_relation") val totalRelation: String,
    val shards: OpenSearchShardsDocument,
    val terms: OpenSearchTermsDocument,
    @SerialName("samples_per_group_limit") val samplesPerGroupLimit: Int,
    @SerialName("sample_message_bytes_max") val sampleMessageBytesMax: Int,
)

@Serializable
internal data class OpenSearchShardsDocument(
    val total: Long,
    val successful: Long,
    val skipped: Long,
    val failed: Long,
)

@Serializable
internal data class OpenSearchTermsDocument(
    @SerialName("group_limit") val groupLimit: Int,
    @SerialName("returned_groups") val returnedGroups: Int,
    @SerialName("sum_other_doc_count") val sumOtherDocCount: Long,
    @SerialName("doc_count_error_upper_bound") val docCountErrorUpperBound: Long,
)

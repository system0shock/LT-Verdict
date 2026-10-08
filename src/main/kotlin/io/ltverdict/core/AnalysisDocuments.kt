package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// Typed shape of the two documents that define an analysis (W2.1). They are encoded to a JsonElement and then written by
// the unchanged canonicalJson, so key order, number formatting and the hash path stay as they were. Rules for these models:
// a numeric-looking value is a String when the document stores a string (identity), never a Double; optional fields are
// `= null` and are omitted when null (explicitNulls = false), so absence stays absence.

/** Encodes the typed documents only; nothing in production decodes with it. */
internal val ANALYSIS_DOCUMENT_JSON =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

internal val SUPPORTED_ANALYSIS_RESULT_VERSIONS = setOf("analysis-result.v1")

@Serializable
internal data class AnalysisResultDocument(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("run_id") val runId: String,
    @SerialName("analysis_mode") val analysisMode: AnalysisMode,
    @SerialName("run_validity") val runValidity: RunValidity,
    @SerialName("policy_verdict") val policyVerdict: PolicyVerdict,
    @SerialName("analysis_coverage") val analysisCoverage: AnalysisCoverageDocument,
    val findings: List<JsonObject>,
    val evidence: List<JsonObject>,
    @SerialName("capacity_summary") val capacitySummary: JsonObject? = null,
)

@Serializable
internal data class AnalysisCoverageDocument(
    val status: AnalysisCoverageStatus,
    val reasons: List<String>,
)

@Serializable
internal enum class AnalysisCoverageStatus { COMPLETE, INCOMPLETE }

@Serializable
internal data class ComponentRef(
    val id: String,
    val version: String,
)

@Serializable
internal data class InputVersionsDocument(
    val source: String,
    val policy: String,
    val resources: String? = null,
    val diagnostics: String? = null,
    val capacity: String? = null,
    val trend: String? = null,
)

@Serializable
internal data class OutputsDocument(
    @SerialName("run_schema") val runSchema: String,
    @SerialName("analysis_result_schema") val analysisResultSchema: String,
    @SerialName("normalized_encoding") val normalizedEncoding: String,
    @SerialName("rollup_encoding") val rollupEncoding: String,
    @SerialName("histogram_encoding") val histogramEncoding: String,
)

@Serializable
internal data class HistogramDocument(
    @SerialName("lowest_discernible_value_ms") val lowestDiscernibleValueMs: String,
    @SerialName("highest_trackable_value_ms") val highestTrackableValueMs: String,
    @SerialName("significant_digits") val significantDigits: String,
)

@Serializable
internal data class NormalizationDocument(
    @SerialName("bucket_millis") val bucketMillis: String,
    @SerialName("rollup_seconds") val rollupSeconds: List<String>,
)

/**
 * `limits` and `verdict_gates` stay string maps: their key set depends on which inputs the analysis has (24 keys for
 * the oldest identity, 42 with resources), and every value is a decimal or flag stored as a string.
 */
@Serializable
internal data class AnalysisIdentityDocument(
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("run_id") val runId: String,
    @SerialName("source_type") val sourceType: String,
    @SerialName("input_sha256") val inputSha256: String,
    @SerialName("policy_sha256") val policySha256: String,
    @SerialName("verdict_gates") val verdictGates: Map<String, String>? = null,
    @SerialName("resource_snapshot_sha256") val resourceSnapshotSha256: String? = null,
    @SerialName("resource_config_sha256") val resourceConfigSha256: String? = null,
    @SerialName("resource_arm") val resourceArm: String? = null,
    @SerialName("diagnostic_plan_sha256") val diagnosticPlanSha256: String? = null,
    @SerialName("source_acquisition_sha256") val sourceAcquisitionSha256: String? = null,
    @SerialName("postgres_input_sha256") val postgresInputSha256: String? = null,
    @SerialName("capacity_plan_sha256") val capacityPlanSha256: String? = null,
    @SerialName("capacity_plan_version") val capacityPlanVersion: String? = null,
    @SerialName("capacity_knee_method") val capacityKneeMethod: String? = null,
    @SerialName("trend_plan_sha256") val trendPlanSha256: String? = null,
    @SerialName("trend_plan_version") val trendPlanVersion: String? = null,
    @SerialName("pod_view_sha256") val podViewSha256: String? = null,
    @SerialName("pod_view_version") val podViewVersion: String? = null,
    val engine: ComponentRef,
    val parsers: List<ComponentRef>,
    val modules: List<ComponentRef>,
    @SerialName("input_versions") val inputVersions: InputVersionsDocument,
    val outputs: OutputsDocument,
    val histogram: HistogramDocument,
    val normalization: NormalizationDocument,
    val limits: Map<String, String>,
)

private val RESULT_FIELDS = AnalysisResultDocument.serializer().descriptor
private val RESULT_REQUIRED_KEYS =
    (0 until RESULT_FIELDS.elementsCount).filterNot(RESULT_FIELDS::isElementOptional).map(RESULT_FIELDS::getElementName).toSet()
private val RESULT_ALLOWED_KEYS = (0 until RESULT_FIELDS.elementsCount).map(RESULT_FIELDS::getElementName).toSet()

/**
 * True when the document has every required field of [AnalysisResultDocument] and no field outside it. Optional fields
 * (today `capacity_summary`; the ADR 0029 `incidents` field when it is added to the model) may each be present or absent.
 */
internal fun hasSupportedAnalysisResultKeys(document: JsonObject): Boolean =
    document.keys.containsAll(RESULT_REQUIRED_KEYS) && RESULT_ALLOWED_KEYS.containsAll(document.keys)

/**
 * `findings`, `evidence` and `capacity_summary` are opaque JSON payloads in this slice. They are spliced into the encoded
 * tree instead of going through the serializer: kotlinx writes a JsonElement number as Long or Double, which would round
 * "12345678901234567890.5" or fail on "1e400". Any new payload field (ADR 0029 `incidents`) must be spliced the same way;
 * AnalysisDocumentsEquivalenceTest compares wide numbers in every payload.
 */
internal fun encodeAnalysisResult(document: AnalysisResultDocument): ByteArray {
    val shell =
        document.copy(
            findings = emptyList(),
            evidence = emptyList(),
            capacitySummary = document.capacitySummary?.let { JsonObject(emptyMap()) },
        )
    val tree = ANALYSIS_DOCUMENT_JSON.encodeToJsonElement(AnalysisResultDocument.serializer(), shell).jsonObject
    val payloads = mutableMapOf<String, JsonElement>("findings" to JsonArray(document.findings), "evidence" to JsonArray(document.evidence))
    document.capacitySummary?.let { payloads["capacity_summary"] = it }
    return canonicalJson(JsonObject(tree + payloads))
}

internal fun encodeAnalysisIdentity(document: AnalysisIdentityDocument): ByteArray =
    canonicalJson(ANALYSIS_DOCUMENT_JSON.encodeToJsonElement(AnalysisIdentityDocument.serializer(), document))

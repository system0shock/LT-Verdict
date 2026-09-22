package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.TIMESTAMP_UNIT_SUSPECT_RANGE
import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class AnalysisMode(
    val wireName: String,
) {
    STANDARD("standard"),
    CAPACITY_STEP("capacity_step"),
}

internal data class EngineConfig(
    val engineId: String = "lt-verdict",
    val engineVersion: String = "1",
    val metrics: MetricsConfig = MetricsConfig(),
)

internal fun analysisIdentity(
    input: AcceptedInput,
    policy: PolicyValidation.Valid?,
    config: EngineConfig,
    resources: ResourceValidation.Valid? = null,
    diagnostics: DiagnosticValidation.Valid? = null,
    sourceAcquisitionSha256: String? = null,
    postgresInputSha256: String? = null,
    capacity: CapacityPlanValidation.Valid? = null,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "analysis-identity.v1")
            put("run_id", input.runId)
            put("source_type", input.sourceType.wireName)
            put("input_sha256", input.sha256)
            put("policy_sha256", policy?.sha256 ?: "NO_POLICY")
            resources?.let {
                put("resource_snapshot_sha256", it.semanticSha256)
                put("resource_config_sha256", it.configSha256)
            }
            diagnostics?.let { put("diagnostic_plan_sha256", it.sha256) }
            sourceAcquisitionSha256?.let { put("source_acquisition_sha256", it) }
            postgresInputSha256?.let { put("postgres_input_sha256", it) }
            capacity?.let {
                put("capacity_plan_sha256", it.semanticSha256)
                put("capacity_plan_version", "capacity-plan.v1")
            }
            put(
                "engine",
                buildJsonObject {
                    put("id", config.engineId)
                    put("version", config.engineVersion)
                },
            )
            put(
                "parsers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", input.sourceType.parserId())
                            put("version", "1")
                        },
                    )
                },
            )
            put(
                "modules",
                buildJsonArray {
                    val modules = mutableListOf("normalization", "metrics", "policy-evaluation")
                    if (resources != null) modules += listOf("resource-statistics", "window-policy-evaluation")
                    if (diagnostics != null) modules += "load-resource-diagnostics"
                    if (capacity != null) modules += "capacity-stage-evaluation"
                    modules.forEach { id ->
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("version", if (id == "load-resource-diagnostics") "2" else "1")
                            },
                        )
                    }
                },
            )
            put(
                "input_versions",
                buildJsonObject {
                    put("source", input.sourceType.inputVersion())
                    put("policy", "policy.v1")
                    if (resources != null) put("resources", "resource-snapshot.v1")
                    if (diagnostics != null) put("diagnostics", "correlation-plan.v1")
                    if (capacity != null) put("capacity", "capacity-plan.v1")
                },
            )
            put(
                "outputs",
                buildJsonObject {
                    put("run_schema", "run.v1")
                    put("analysis_result_schema", "analysis-result.v1")
                    put("normalized_encoding", "normalized-ndjson.v1")
                    put("rollup_encoding", "rollup-ndjson.v1")
                    put("histogram_encoding", "hdr-compressed-v2")
                },
            )
            put(
                "histogram",
                buildJsonObject {
                    put("lowest_discernible_value_ms", config.metrics.lowestDiscernibleValueMillis.toString())
                    put("highest_trackable_value_ms", config.metrics.highestTrackableValueMillis.toString())
                    put("significant_digits", config.metrics.significantDigits.toString())
                },
            )
            put(
                "normalization",
                buildJsonObject {
                    put("bucket_millis", "1000")
                    put("rollup_seconds", buildJsonArray { listOf("10", "30", "60").forEach { add(JsonPrimitive(it)) } })
                },
            )
            put("limits", limits(config.metrics, resources != null, diagnostics != null, capacity != null))
        },
    )

internal fun analysisResult(
    runId: String,
    validity: RunValidity,
    evaluation: PolicyEvaluation,
    mode: AnalysisMode = AnalysisMode.STANDARD,
    capacity: CapacityAnalysis? = null,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "analysis-result.v1")
            put("run_id", runId)
            put("analysis_mode", mode.wireName)
            put("run_validity", validity.name)
            put("policy_verdict", (capacity?.policyVerdict ?: evaluation.verdict).name)
            put(
                "analysis_coverage",
                buildJsonObject {
                    put("status", if (evaluation.coverageReasons.isEmpty()) "COMPLETE" else "INCOMPLETE")
                    put("reasons", buildJsonArray { evaluation.coverageReasons.forEach { add(JsonPrimitive(it)) } })
                },
            )
            put("findings", buildJsonArray { evaluation.findings.forEach(::add) })
            put("evidence", buildJsonArray { evaluation.evidence.forEach(::add) })
            capacity?.let { put("capacity_summary", it.capacityJson) }
        },
    )

private fun limits(
    metrics: MetricsConfig,
    includeResources: Boolean,
    includeDiagnostics: Boolean,
    includeCapacity: Boolean,
) = buildJsonObject {
    put("input_bytes_max", "4294967296")
    put("policy_bytes_max", "1048576")
    put("filename_bytes_max", "255")
    put("csv_columns_max", "64")
    put("text_field_bytes_max", "65536")
    put("text_line_or_binary_blob_bytes_max", "1048576")
    put("label_bytes_max", "4096")
    put("hierarchy_or_xml_depth_max", "64")
    put("transaction_identity_bytes_max", metrics.maxTransactionIdentityBytes.toString())
    put("transaction_identities_max", metrics.maxTransactions.toString())
    put("transaction_identity_total_bytes_max", metrics.maxTotalTransactionIdentityBytes.toString())
    put("non_empty_buckets_max", metrics.maxOneSecondBuckets.toString())
    put("gatling_cache_entries_max", "65536")
    put("gatling_cache_strings_bytes_max", "67108864")
    put("policy_json_depth_max", "16")
    put("policy_rules_max", "256")
    put("policy_identifier_bytes_max", "128")
    put("policy_transaction_scope_bytes_max", "4096")
    put("policy_numeric_token_bytes_max", "64")
    put("policy_numeric_exponent_abs_max", "64")
    put("policy_canonical_decimal_bytes_max", "128")
    put("timestamp_epoch_millis_max", "253402300799999")
    put("timestamp_epoch_millis_unit_suspect_min", TIMESTAMP_UNIT_SUSPECT_RANGE.first.toString())
    put("timestamp_epoch_millis_unit_suspect_max", TIMESTAMP_UNIT_SUSPECT_RANGE.last.toString())
    if (includeResources) {
        put("resource_snapshot_bytes_max", MAX_RESOURCE_SNAPSHOT_BYTES.toString())
        put("resource_json_depth_max", RESOURCE_JSON_DEPTH_MAX.toString())
        put("resource_series_max", MAX_RESOURCE_SERIES.toString())
        put("resource_points_per_series_max", MAX_POINTS_PER_SERIES.toString())
        put("resource_cells_total_max", MAX_RESOURCE_CELLS.toString())
        put("resource_windows_max", MAX_RESOURCE_WINDOWS.toString())
        put("resource_rules_max", MAX_RESOURCE_RULES.toString())
        put("resource_findings_max", RESOURCE_FINDINGS_MAX.toString())
        put("resource_window_histograms_max", metrics.maxWindowHistograms.toString())
        put("resource_identifier_bytes_max", "128")
        put("resource_labels_max", MAX_LABELS.toString())
        put("resource_label_key_bytes_max", MAX_LABEL_KEY_BYTES.toString())
        put("resource_label_value_bytes_max", MAX_LABEL_VALUE_BYTES.toString())
        put("resource_numeric_token_bytes_max", RESOURCE_NUMERIC_TOKEN_BYTES_MAX.toString())
        put("resource_numeric_exponent_abs_max", RESOURCE_NUMERIC_EXPONENT_ABS_MAX.toString())
        put("resource_numeric_magnitude_max", "1000000000000000000")
        put("resource_significant_digits_max", RESOURCE_SIGNIFICANT_DIGITS_MAX.toString())
        put("resource_fractional_digits_max", RESOURCE_FRACTIONAL_DIGITS_MAX.toString())
    }
    if (includeDiagnostics) {
        put("diagnostic_plan_bytes_max", MAX_DIAGNOSTIC_PLAN_BYTES.toString())
        put("diagnostic_json_depth_max", DIAGNOSTIC_JSON_DEPTH_MAX.toString())
        put("diagnostic_pairs_max", MAX_DIAGNOSTIC_PAIRS.toString())
        put("diagnostic_anomalies_max", MAX_DIAGNOSTIC_ANOMALIES.toString())
        put("diagnostic_pair_windows_max", MAX_DIAGNOSTIC_PAIR_WINDOWS.toString())
        put("diagnostic_episodes_max", MAX_DIAGNOSTIC_EPISODES.toString())
        put("diagnostic_p95_samples_min", MIN_DIAGNOSTIC_P95_SAMPLES.toString())
    }
    if (includeCapacity) {
        put("capacity_plan_bytes_max", MAX_CAPACITY_PLAN_BYTES.toString())
        put("capacity_json_depth_max", CAPACITY_JSON_DEPTH_MAX.toString())
        put("capacity_stages_max", MAX_CAPACITY_STAGES.toString())
        put("capacity_guards_max", MAX_CAPACITY_GUARDS.toString())
        put("capacity_bin_millis", "10000")
        put("capacity_minimum_bins", "30")
    }
}

private fun SourceType.parserId(): String =
    when (this) {
        SourceType.JMETER_CSV -> "jmeter-csv"
        SourceType.JMETER_XML -> "jmeter-xml"
        SourceType.GATLING_TEXT -> "gatling-text"
        SourceType.GATLING_BINARY -> "gatling-binary"
    }

private fun SourceType.inputVersion(): String =
    when (this) {
        SourceType.JMETER_CSV -> "jmeter-jtl-csv.v1"
        SourceType.JMETER_XML -> "jmeter-jtl-xml.v1"
        SourceType.GATLING_TEXT -> "gatling-text.v1"
        SourceType.GATLING_BINARY -> "gatling-binary.v1"
    }

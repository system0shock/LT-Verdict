package io.ltverdict.core

import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.TIMESTAMP_UNIT_SUSPECT_RANGE
import io.ltverdict.metrics.MetricsConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class AnalysisMode(
    val wireName: String,
) {
    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("capacity_step")
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
    trend: TrendPlanValidation.Valid? = null,
    podView: PodViewValidation.Valid? = null,
    stages: LoadStagesValidation.Valid? = null,
): ByteArray {
    val modules = mutableListOf("normalization", "metrics", "policy-evaluation")
    if (resources != null) modules += listOf("resource-statistics", "window-policy-evaluation")
    if (diagnostics != null) modules += "load-resource-diagnostics"
    if (capacity != null) modules += "capacity-stage-evaluation"
    if (trend != null) modules += "resource-trend-evaluation"
    // ADR 0030: the window evaluation without a snapshot; a snapshot excludes stages, so the module is listed once.
    if (stages != null) modules += listOf("stage-window-evaluation", "window-policy-evaluation")
    return encodeAnalysisIdentity(
        AnalysisIdentityDocument(
            schemaVersion = "analysis-identity.v1",
            runId = input.runId,
            sourceType = input.sourceType.wireName,
            inputSha256 = input.sha256,
            policySha256 = policy?.sha256 ?: "NO_POLICY",
            verdictGates =
                if (policy != null || capacity != null) {
                    verdictGates(
                        policy != null,
                        capacity != null,
                        policy?.policy?.platformRules?.isNotEmpty() == true,
                        policy?.policy?.platformCoverage != null,
                    )
                } else {
                    null
                },
            resourceSnapshotSha256 = resources?.semanticSha256,
            resourceConfigSha256 = resources?.configSha256,
            resourceArm = resources?.snapshot?.arm,
            diagnosticPlanSha256 = diagnostics?.sha256,
            sourceAcquisitionSha256 = sourceAcquisitionSha256,
            postgresInputSha256 = postgresInputSha256,
            capacityPlanSha256 = capacity?.semanticSha256,
            capacityPlanVersion = capacity?.let { "capacity-plan.v1" },
            // ADR 0026: a top-level binding only, like pod_view: the diagnostic evidence item changes the result bytes, but not
            // modules, input_versions or limits, which are part of the comparability key.
            capacityKneeMethod = capacity?.let { "piecewise-hinge-ln-p95.v1" },
            trendPlanSha256 = trend?.semanticSha256,
            trendPlanVersion = trend?.let { "trend-plan.v1" },
            // ADR 0020, section 5: a top-level binding only. pod-view stays out of modules, input_versions and limits,
            // which are part of the comparability key.
            podViewSha256 = podView?.canonicalSha256,
            podViewVersion = podView?.let { "pod-view.v1" },
            // ADR 0030, R6: the declaration is bound only when there is one. Modules, input_versions and limits carry the stage
            // mode into the comparability key; the hash itself is the conditional link of that key.
            loadStagesSha256 = stages?.sha256,
            loadStagesVersion = stages?.let { "load-stages.v1" },
            engine = ComponentRef(config.engineId, config.engineVersion),
            parsers =
                listOf(ComponentRef(input.sourceType.parserId(), if (input.sourceType == SourceType.JMETER_CSV) "2" else "1")),
            modules =
                modules.map { id ->
                    ComponentRef(
                        id,
                        when (id) {
                            "metrics" -> "2"
                            "load-resource-diagnostics" -> "5"
                            else -> "1"
                        },
                    )
                },
            inputVersions =
                InputVersionsDocument(
                    source = input.sourceType.inputVersion(),
                    policy = "policy.v1",
                    resources = if (resources != null) "resource-snapshot.v1" else null,
                    diagnostics = if (diagnostics != null) "correlation-plan.v1" else null,
                    capacity = if (capacity != null) "capacity-plan.v1" else null,
                    trend = if (trend != null) "trend-plan.v1" else null,
                    stages = if (stages != null) "load-stages.v1" else null,
                ),
            outputs =
                OutputsDocument(
                    runSchema = "run.v1",
                    analysisResultSchema = "analysis-result.v1",
                    normalizedEncoding = "normalized-ndjson.v1",
                    rollupEncoding = "rollup-ndjson.v1",
                    histogramEncoding = "hdr-compressed-v2",
                ),
            histogram =
                HistogramDocument(
                    lowestDiscernibleValueMs = config.metrics.lowestDiscernibleValueMillis.toString(),
                    highestTrackableValueMs = config.metrics.highestTrackableValueMillis.toString(),
                    significantDigits = config.metrics.significantDigits.toString(),
                ),
            normalization = NormalizationDocument(bucketMillis = "1000", rollupSeconds = listOf("10", "30", "60")),
            limits = limits(config.metrics, resources != null, diagnostics != null, capacity != null, trend != null, stages != null),
        ),
    )
}

internal fun analysisResult(
    runId: String,
    validity: RunValidity,
    evaluation: PolicyEvaluation,
    mode: AnalysisMode = AnalysisMode.STANDARD,
    capacity: CapacityAnalysis? = null,
): ByteArray =
    encodeAnalysisResult(
        AnalysisResultDocument(
            schemaVersion = "analysis-result.v1",
            runId = runId,
            analysisMode = mode,
            runValidity = validity,
            policyVerdict = capacity?.policyVerdict ?: evaluation.verdict,
            analysisCoverage =
                AnalysisCoverageDocument(
                    status =
                        if (evaluation.coverageReasons.isEmpty()) AnalysisCoverageStatus.COMPLETE else AnalysisCoverageStatus.INCOMPLETE,
                    reasons = evaluation.coverageReasons,
                ),
            findings = evaluation.findings,
            evidence = evaluation.evidence,
            capacitySummary = capacity?.capacityJson,
        ),
    )

private fun verdictGates(
    hasPolicy: Boolean,
    hasCapacity: Boolean,
    hasPlatformRules: Boolean,
    hasPlatformCoverage: Boolean,
): Map<String, String> =
    buildMap {
        if (hasPolicy) put("min_samples_floor", MIN_SAMPLES_FLOOR.toString())
        put("min_samples_default", MIN_SAMPLES_DEFAULT.toString())
        if (hasPolicy) put("throughput_exempt", "true")
        if (hasCapacity) put("capacity_stage_sample_gate", "true")
        if (hasPlatformRules) {
            put("max_missing_fraction_default", MAX_MISSING_FRACTION_DEFAULT.toPlainString())
            put("max_gap_cells_default", MAX_GAP_CELLS_DEFAULT.toString())
            if (hasPlatformCoverage) {
                put("platform_coverage_max_missing_fraction_default", PLATFORM_COVERAGE_MAX_MISSING_FRACTION_DEFAULT.toPlainString())
                put("platform_coverage_max_gap_cells_default", PLATFORM_COVERAGE_MAX_GAP_CELLS_DEFAULT.toString())
            }
        }
    }

private fun limits(
    metrics: MetricsConfig,
    includeResources: Boolean,
    includeDiagnostics: Boolean,
    includeCapacity: Boolean,
    includeTrend: Boolean,
    includeStages: Boolean,
): Map<String, String> =
    buildMap {
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
        if (includeTrend) {
            put("trend_plan_bytes_max", MAX_TREND_PLAN_BYTES.toString())
            put("trend_json_depth_max", TREND_JSON_DEPTH_MAX.toString())
            put("trend_checks_max", MAX_TREND_CHECKS.toString())
            put("trend_min_cells_floor", TREND_MIN_CELLS_FLOOR.toString())
            put("trend_method", "slope-materiality.v1")
        }
        if (includeStages) {
            put("stages_plan_bytes_max", MAX_LOAD_STAGES_BYTES.toString())
            put("stages_json_depth_max", LOAD_STAGES_JSON_DEPTH_MAX.toString())
            put("stages_max", MAX_LOAD_STAGES.toString())
            put("stages_offset_ms_max", MAX_LOAD_STAGE_OFFSET_MS.toString())
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
        SourceType.JMETER_CSV -> "jmeter-jtl-csv.v2"
        SourceType.JMETER_XML -> "jmeter-jtl-xml.v1"
        SourceType.GATLING_TEXT -> "gatling-text.v1"
        SourceType.GATLING_BINARY -> "gatling-binary.v1"
    }

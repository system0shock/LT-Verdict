package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
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
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "analysis-identity.v1")
            put("run_id", input.runId)
            put("source_type", input.sourceType.wireName)
            put("input_sha256", input.sha256)
            put("policy_sha256", policy?.sha256 ?: "NO_POLICY")
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
                    listOf("normalization", "metrics", "policy-evaluation").forEach { id ->
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("version", "1")
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
            put("limits", limits(config.metrics))
        },
    )

internal fun analysisResult(
    runId: String,
    validity: RunValidity,
    evaluation: PolicyEvaluation,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "analysis-result.v1")
            put("run_id", runId)
            put("analysis_mode", AnalysisMode.STANDARD.wireName)
            put("run_validity", validity.name)
            put("policy_verdict", evaluation.verdict.name)
            put(
                "analysis_coverage",
                buildJsonObject {
                    put("status", if (evaluation.coverageReasons.isEmpty()) "COMPLETE" else "INCOMPLETE")
                    put("reasons", buildJsonArray { evaluation.coverageReasons.forEach { add(JsonPrimitive(it)) } })
                },
            )
            put("findings", buildJsonArray { evaluation.findings.forEach(::add) })
            put("evidence", buildJsonArray { evaluation.evidence.forEach(::add) })
        },
    )

private fun limits(metrics: MetricsConfig) =
    buildJsonObject {
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

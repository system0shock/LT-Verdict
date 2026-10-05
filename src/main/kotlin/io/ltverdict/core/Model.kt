package io.ltverdict.core

import java.math.BigDecimal

internal data class PolicyValidationError(
    val code: String,
    val jsonPointer: String,
    val message: String,
)

internal sealed interface PolicyValidation {
    data class Valid(
        val policy: PolicyV1,
        val canonicalBytes: ByteArray,
        val sha256: String,
    ) : PolicyValidation

    data class Invalid(
        val errors: List<PolicyValidationError>,
    ) : PolicyValidation
}

internal data class PolicyV1(
    val schemaVersion: String,
    val policyId: String,
    val rules: List<PolicyRuleV1>,
    val defaults: PolicyDefaultsV1? = null,
    val platformServices: List<String>? = null,
    val platformRules: List<PlatformRuleV1> = emptyList(),
    val platformCoverage: PlatformCoverageV1? = null,
)

internal data class PlatformRuleV1(
    val id: String,
    val signal: String,
    val scope: PlatformScope,
    val operator: ResourceOperator,
    val threshold: BigDecimal,
    val unit: String,
    val aggregation: ResourceAggregation,
    val minConsecutiveCells: Int,
    val effect: ResourceRuleEffect,
    val windowIds: List<String>? = null,
    val maxMissingFraction: BigDecimal? = null,
    val maxGapCells: Int? = null,
)

internal sealed interface PlatformScope {
    data class Services(
        val names: List<String>,
    ) : PlatformScope

    data class AllServices(
        val except: List<String>,
    ) : PlatformScope
}

internal data class PlatformCoverageV1(
    val signal: String,
)

internal data class PolicyDefaultsV1(
    val sampleFloor: Long? = null,
    val minSamples: Long? = null,
    val maxMissingFraction: BigDecimal? = null,
    val maxGapCells: Int? = null,
)

internal data class PolicyRuleV1(
    val id: String,
    val metric: PolicyMetric,
    val operator: PolicyOperator,
    val threshold: BigDecimal,
    val scope: PolicyScope,
    val minSamples: Long? = null,
    val windowIds: List<String>? = null,
)

internal enum class PolicyMetric(
    val wireName: String,
) {
    RESPONSE_TIME_P95_MS("response_time_p95_ms"),
    RESPONSE_TIME_P99_MS("response_time_p99_ms"),
    ERROR_RATE_RATIO("error_rate_ratio"),
    THROUGHPUT_RPS("throughput_rps"),
}

internal enum class PolicyOperator(
    val wireName: String,
) {
    LTE("lte"),
    GTE("gte"),
}

internal sealed interface PolicyScope {
    data object Overall : PolicyScope

    data class Transaction(
        val name: String,
    ) : PolicyScope
}

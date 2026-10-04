package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

internal data class SavedAnalysisForComparison(
    val reference: JsonObject,
    val run: JsonObject,
    val result: JsonObject,
    val identity: JsonObject,
    val jenkinsBuild: String? = null,
    val commit: String? = null,
    val applicationVersion: String? = null,
    val loadProfile: String? = null,
)

internal fun buildRunDynamics(
    current: SavedAnalysisForComparison,
    saved: List<SavedAnalysisForComparison>,
    baselineReference: JsonObject? = null,
    limit: Int = DEFAULT_DYNAMICS_LIMIT,
): JsonObject {
    require(limit in 1..MAX_DYNAMICS_LIMIT) { "INVALID_DYNAMICS_LIMIT" }
    val currentKey =
        comparisonSemanticKey(current.result, current.identity) ?: throw IllegalArgumentException("INVALID_COMPARISON_IDENTITY")
    val all = (saved + current).distinctBy { it.reference }
    val compatible =
        all
            .filter { comparisonSemanticKey(it.result, it.identity) == currentKey }
            .sortedWith(compareByDescending<SavedAnalysisForComparison> { it.startedAt() }.thenBy { it.reference.toString() })
    val rows = compatible.take(limit)
    val baseline = baselineReference?.let { reference -> compatible.singleOrNull { it.reference == reference } }

    return buildJsonObject {
        put("schema_version", "run-dynamics.v1")
        put("limit", limit)
        put("comparable_count", compatible.size)
        put("excluded_incompatible_count", all.size - compatible.size)
        put("baseline", baselineReference ?: JsonNull)
        put(
            "rows",
            buildJsonArray {
                rows.forEachIndexed { index, item ->
                    val previous = rows.getOrNull(index + 1)
                    add(
                        buildJsonObject {
                            put("reference", item.reference)
                            put("run_date", item.run.string("started_at"))
                            putNullable("jenkins_build", item.jenkinsBuild)
                            putNullable("commit", item.commit)
                            putNullable("application_version", item.applicationVersion)
                            putNullable("load_profile", item.loadProfile)
                            put("verdict", item.result.string("policy_verdict"))
                            put(
                                "metrics",
                                buildJsonArray {
                                    DynamicsMetric.entries.forEach { metric ->
                                        add(
                                            dynamicsMetric(
                                                metric,
                                                item.result.metricValue(metric),
                                                previous?.result?.metricValue(metric),
                                                baseline?.result?.metricValue(metric),
                                                previousMissingReason =
                                                    if (previous == null) {
                                                        if (index + 1 <
                                                            compatible.size
                                                        ) {
                                                            "PREVIOUS_RUN_OUTSIDE_RESULT"
                                                        } else {
                                                            "NO_PREVIOUS_RUN"
                                                        }
                                                    } else {
                                                        null
                                                    },
                                                baselineMissingReason =
                                                    when {
                                                        baselineReference == null -> "BASELINE_NOT_SELECTED"
                                                        baseline == null -> "BASELINE_NOT_FOUND"
                                                        else -> null
                                                    },
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }
}

internal fun compareTransactions(
    baselineResult: JsonObject,
    baselineIdentity: JsonObject,
    currentResult: JsonObject,
    currentIdentity: JsonObject,
    filter: String? = null,
    limit: Int = DEFAULT_TRANSACTION_LIMIT,
): JsonObject {
    require(limit in 1..MAX_TRANSACTION_LIMIT) { "INVALID_TRANSACTION_LIMIT" }
    val normalizedFilter = filter?.trim()?.takeIf(String::isNotEmpty)
    require(normalizedFilter == null || normalizedFilter.encodeToByteArray().size <= MAX_TRANSACTION_FILTER_BYTES) {
        "INVALID_TRANSACTION_FILTER"
    }
    val baseline = baselineResult.transactionMetrics()
    val current = currentResult.transactionMetrics()
    val compatible =
        comparisonSemanticKey(baselineResult, baselineIdentity)?.let { it == comparisonSemanticKey(currentResult, currentIdentity) } == true
    val lowered = normalizedFilter?.lowercase(Locale.ROOT)
    val keys =
        (baseline.keys + current.keys)
            .distinct()
            .sorted()
            .filter { key -> lowered == null || key.searchText().lowercase(Locale.ROOT).contains(lowered) }
    val selected = keys.take(limit)

    return buildJsonObject {
        put("schema_version", "transaction-comparison.v1")
        put("compatible", compatible)
        put("filter", normalizedFilter?.let(::JsonPrimitive) ?: JsonNull)
        put("matched_count", keys.size)
        put("truncated", keys.size > selected.size)
        put(
            "rows",
            buildJsonArray {
                selected.forEach { key ->
                    val baselineMetric = baseline[key]
                    val currentMetric = current[key]
                    add(
                        buildJsonObject {
                            put("scope", key.json())
                            put(
                                "metrics",
                                buildJsonArray {
                                    TransactionMetric.entries.forEach { metric ->
                                        add(
                                            comparisonMetric(
                                                metric.wireName,
                                                metric.unit,
                                                currentMetric?.value(metric),
                                                baselineMetric?.value(metric),
                                                when {
                                                    !compatible -> "INCOMPATIBLE_METRIC_DEFINITION"
                                                    currentMetric == null -> "MISSING_CURRENT_TRANSACTION"
                                                    baselineMetric == null -> "MISSING_BASELINE_TRANSACTION"
                                                    else -> null
                                                },
                                            ),
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }
}

private fun dynamicsMetric(
    metric: DynamicsMetric,
    value: ExactValue?,
    previous: ExactValue?,
    baseline: ExactValue?,
    previousMissingReason: String?,
    baselineMissingReason: String?,
): JsonObject {
    val previousComparison = delta(value, previous, previousMissingReason, "MISSING_CURRENT_METRIC", "MISSING_PREVIOUS_METRIC")
    val baselineComparison = delta(value, baseline, baselineMissingReason, "MISSING_CURRENT_METRIC", "MISSING_BASELINE_METRIC")
    return buildJsonObject {
        put("metric", metric.wireName)
        put("unit", metric.unit)
        putExact("value", value)
        putExact("delta_previous", previousComparison.delta)
        putExact("delta_previous_percent", previousComparison.percent)
        putNullable("previous_reason", previousComparison.reason)
        putExact("delta_baseline", baselineComparison.delta)
        putExact("delta_baseline_percent", baselineComparison.percent)
        putNullable("baseline_reason", baselineComparison.reason)
    }
}

private fun comparisonMetric(
    metric: String,
    unit: String,
    current: ExactValue?,
    baseline: ExactValue?,
    reason: String?,
): JsonObject {
    val comparison = delta(current, baseline, reason, "MISSING_CURRENT_METRIC", "MISSING_BASELINE_METRIC")
    return buildJsonObject {
        put("metric", metric)
        put("unit", unit)
        putExact("current", current)
        putExact("baseline", baseline)
        putExact("delta", comparison.delta)
        putExact("delta_percent", comparison.percent)
        putNullable("reason", comparison.reason)
        putNullable("percent_reason", comparison.percentReason)
    }
}

private fun delta(
    current: ExactValue?,
    baseline: ExactValue?,
    unavailableReason: String?,
    currentMissingReason: String,
    baselineMissingReason: String,
): Delta {
    val reason =
        unavailableReason ?: if (current == null) {
            currentMissingReason
        } else if (baseline == null) {
            baselineMissingReason
        } else {
            null
        }
    if (reason != null) return Delta(null, null, reason, reason)
    val checkedCurrent = checkNotNull(current)
    val checkedBaseline = checkNotNull(baseline)
    val difference = checkedCurrent - checkedBaseline
    return if (checkedBaseline.isZero()) {
        Delta(difference, null, null, "ZERO_BASELINE")
    } else {
        Delta(difference, difference / checkedBaseline * HUNDRED, null, null)
    }
}

private fun SavedAnalysisForComparison.startedAt(): Instant =
    try {
        Instant.parse(run.string("started_at"))
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("INVALID_RUN_DATE")
    }

private fun JsonObject.transactionMetrics(): Map<TransactionKey, JsonObject> =
    (this["evidence"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .filter { it.stringOrNull("type") == "metric_summary" }
        .mapNotNull { metric -> metric.transactionKey()?.let { it to metric } }
        .associate { it }

private fun JsonObject.transactionKey(): TransactionKey? {
    val scope = this["scope"] as? JsonObject ?: return null
    if (scope.stringOrNull("kind") != "transaction") return null
    val path = (scope["group_path"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return null
    return TransactionKey(path, scope.string("label"), scope.string("sample_kind"))
}

private fun JsonObject.metricValue(metric: DynamicsMetric): ExactValue? {
    val overall =
        (this["evidence"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .singleOrNull { evidence ->
                evidence.stringOrNull("type") == "metric_summary" &&
                    (evidence["scope"] as? JsonObject)?.stringOrNull("kind") == "overall"
            } ?: return null
    return when (metric) {
        DynamicsMetric.P95 -> overall.integer("latency_ms", "p95")
        DynamicsMetric.P99 -> overall.integer("latency_ms", "p99")
        DynamicsMetric.THROUGHPUT -> overall.ratio("throughput_rps")
        DynamicsMetric.ERROR_RATE -> overall.ratio("error_rate_ratio")
    }
}

private fun JsonObject.value(metric: TransactionMetric): ExactValue? =
    when (metric) {
        TransactionMetric.SAMPLE_COUNT -> integer("sample_count")
        TransactionMetric.ERROR_COUNT -> integer("error_count")
        TransactionMetric.P50 -> integer("latency_ms", "p50")
        TransactionMetric.P95 -> integer("latency_ms", "p95")
        TransactionMetric.P99 -> integer("latency_ms", "p99")
        TransactionMetric.THROUGHPUT -> ratio("throughput_rps")
        TransactionMetric.ERROR_RATE -> ratio("error_rate_ratio")
    }

private fun JsonObject.integer(
    objectName: String,
    fieldName: String,
): ExactValue? = (this[objectName] as? JsonObject)?.integer(fieldName)

private fun JsonObject.integer(name: String): ExactValue? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content
        .toBigIntegerOrNull()
        ?.takeIf { it.signum() >= 0 }
        ?.let { ExactValue(it, BigInteger.ONE) }
}

private fun JsonObject.ratio(name: String): ExactValue? {
    val value = this[name] as? JsonObject ?: return null
    val numerator = value.integer("numerator")?.numerator ?: return null
    val denominator = value.integer("denominator")?.numerator?.takeIf { it.signum() > 0 } ?: return null
    return ExactValue(numerator, denominator)
}

private fun comparisonSemanticKey(
    result: JsonObject,
    identity: JsonObject,
): List<JsonElement>? {
    val values = mutableListOf(result["analysis_mode"] ?: return null)
    COMPARISON_SEMANTIC_FIELDS.forEach { field -> values += identity[field] ?: return null }
    // ADR 0014, часть 5: плечо входит в ключ условно. Отсутствие у обоих анализов равно равенству, отсутствие у одного - несовместимость.
    values += identity["resource_arm"] ?: JsonNull
    return values
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putExact(
    name: String,
    value: ExactValue?,
) = put(name, value?.format()?.let(::JsonPrimitive) ?: JsonNull)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("INVALID_COMPARISON_DOCUMENT")

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private data class Delta(
    val delta: ExactValue?,
    val percent: ExactValue?,
    val reason: String?,
    val percentReason: String?,
)

private data class ExactValue(
    val numerator: BigInteger,
    val denominator: BigInteger,
) {
    init {
        require(denominator.signum() > 0)
    }

    operator fun minus(other: ExactValue) =
        ExactValue(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)

    operator fun div(other: ExactValue): ExactValue {
        require(!other.isZero())
        return ExactValue(numerator * other.denominator, denominator * other.numerator)
    }

    operator fun times(value: BigInteger) = ExactValue(numerator * value, denominator)

    fun isZero(): Boolean = numerator.signum() == 0

    fun format(): String =
        BigDecimal(numerator)
            .divide(BigDecimal(denominator), DISPLAY_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}

private data class TransactionKey(
    val groupPath: List<String>,
    val label: String,
    val sampleKind: String,
) : Comparable<TransactionKey> {
    override fun compareTo(other: TransactionKey): Int =
        compareValuesBy(this, other, { it.groupPath.joinToString("\u0000") }, TransactionKey::label, TransactionKey::sampleKind)

    fun searchText(): String = (groupPath + label + sampleKind).joinToString("/")

    fun json(): JsonObject =
        buildJsonObject {
            put("kind", "transaction")
            put("group_path", buildJsonArray { groupPath.forEach { add(JsonPrimitive(it)) } })
            put("label", label)
            put("sample_kind", sampleKind)
        }
}

private enum class DynamicsMetric(
    val wireName: String,
    val unit: String,
) {
    P95("response_time_p95_ms", "ms"),
    P99("response_time_p99_ms", "ms"),
    THROUGHPUT("throughput_rps", "requests/second"),
    ERROR_RATE("error_rate_ratio", "ratio"),
}

private enum class TransactionMetric(
    val wireName: String,
    val unit: String,
) {
    SAMPLE_COUNT("sample_count", "count"),
    ERROR_COUNT("error_count", "count"),
    P50("response_time_p50_ms", "ms"),
    P95("response_time_p95_ms", "ms"),
    P99("response_time_p99_ms", "ms"),
    THROUGHPUT("throughput_rps", "requests/second"),
    ERROR_RATE("error_rate_ratio", "ratio"),
}

private const val DEFAULT_DYNAMICS_LIMIT = 10
private const val MAX_DYNAMICS_LIMIT = 100
private const val DEFAULT_TRANSACTION_LIMIT = 100
private const val MAX_TRANSACTION_LIMIT = 200
private const val MAX_TRANSACTION_FILTER_BYTES = 256
private const val DISPLAY_SCALE = 6
private val HUNDRED = BigInteger.valueOf(100)
private val COMPARISON_SEMANTIC_FIELDS =
    listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")

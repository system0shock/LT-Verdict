package io.ltverdict.core

import io.ltverdict.metrics.NormalizedBucket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.HdrHistogram.PackedHistogram
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

internal data class RunLoadSeries(
    val runStartEpochMillis: Long,
    val buckets: List<NormalizedBucket>,
    val runEndEpochMillis: Long? = null,
)

internal data class ComparisonSeriesContext(
    val baseline: RunLoadSeries? = null,
    val current: RunLoadSeries? = null,
)

internal data class WindowComparisonRequest(
    val baselineWindowId: String,
    val currentWindowId: String,
    val minChangePercent: BigDecimal = BigDecimal("5"),
    val minErrorRateDelta: BigDecimal = BigDecimal("0.001"),
)

internal fun baselineConditionRecord(
    baselineReference: JsonObject,
    currentReference: JsonObject,
    windows: WindowComparisonRequest?,
    decision: String,
    updatedAt: Instant,
): JsonObject =
    BaselineCondition(
        conditionBinding(baselineReference, currentReference, windows),
        ConditionDecision.entries.singleOrNull { it.name == decision }
            ?: throw IllegalArgumentException("INVALID_BASELINE_CONDITION"),
        updatedAt,
    ).toJson()

internal fun baselineConditionBinding(
    baselineReference: JsonObject,
    currentReference: JsonObject,
    windows: WindowComparisonRequest?,
): JsonObject = conditionBinding(baselineReference, currentReference, windows).toJson()

internal fun baselineConditionBinding(condition: JsonObject): JsonObject = condition.toBaselineCondition().binding.toJson()

internal fun validateBaselineCondition(condition: JsonObject): JsonObject = condition.toBaselineCondition().toJson()

internal fun baselineConditionMatches(
    condition: JsonObject,
    baselineReference: JsonObject,
    currentReference: JsonObject,
    windows: WindowComparisonRequest?,
): Boolean = condition.toBaselineCondition().binding == conditionBinding(baselineReference, currentReference, windows)

internal fun baselineConditionConfirmation(condition: JsonObject): Boolean? = condition.toBaselineCondition().decision.confirmation

private fun conditionBinding(
    baselineReference: JsonObject,
    currentReference: JsonObject,
    windows: WindowComparisonRequest?,
) = ConditionBinding(
    baselineReference.toReference(),
    currentReference.toReference(),
    windows?.let {
        ConditionWindows(
            requireConditionWindow(it.baselineWindowId),
            requireConditionWindow(it.currentWindowId),
        )
    },
)

internal fun manualBaselineSelection(
    series: String,
    reference: JsonObject,
): JsonObject =
    Selection(
        series = requireSeries(series),
        mode = Mode.MANUAL,
        reference = reference.toReference(),
        algorithm = null,
        candidates = listOf(reference.toReference()),
        scores = emptyList(),
    ).toJson()

// ADR 0019, section 6: one admission rule for both selection modes. The order of the three checks is part of the contract.
// INCOMPLETE coverage caused only by SMALL_SAMPLE is admitted (owner decision 2026-10-04); compareAnalyses warns about it.
internal fun baselineCandidateRejection(
    policyVerdict: String?,
    runValidity: String?,
    coverageStatus: String?,
    coverageReasons: List<String?>,
): String? =
    when {
        runValidity != "VALID" -> "BASELINE_CANDIDATE_INVALID"
        !coverageAdmitted(coverageStatus, coverageReasons) -> "BASELINE_CANDIDATE_INCOMPLETE"
        policyVerdict != "PASS" -> "BASELINE_CANDIDATE_NOT_PASS"
        else -> null
    }

internal fun baselineCandidateRejection(result: JsonObject): String? =
    baselineCandidateRejection(
        result.stringOrNull("policy_verdict"),
        result.stringOrNull("run_validity"),
        result.objectOrNull("analysis_coverage")?.stringOrNull("status"),
        coverageReasons(result),
    )

private fun coverageAdmitted(
    status: String?,
    reasons: List<String?>,
): Boolean = status == "COMPLETE" || (status == "INCOMPLETE" && reasons.isNotEmpty() && reasons.all { it == SMALL_SAMPLE_REASON })

// A reason that is not a string stays in the list as null, so a malformed array never reads as a lone SMALL_SAMPLE.
private fun coverageReasons(result: JsonObject): List<String?> =
    (result.objectOrNull("analysis_coverage")?.get("reasons") as? JsonArray)
        ?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .orEmpty()

private const val SMALL_SAMPLE_REASON = "SMALL_SAMPLE"

internal fun statisticalBaselineSelection(
    series: String,
    references: List<JsonObject>,
    results: List<JsonObject>,
    identities: List<JsonObject>,
): JsonObject {
    require(references.size in MIN_CANDIDATES..MAX_CANDIDATES && results.size == references.size && identities.size == references.size) {
        "BASELINE_CANDIDATE_COUNT"
    }
    val candidates =
        references.indices.map { index ->
            val reference = references[index].toReference()
            val result = results[index]
            baselineCandidateRejection(result)?.let { throw IllegalArgumentException(it) }
            require(
                identities[index]["verdict_gates"] is JsonObject &&
                    identities[index].stringOrNull("policy_sha256")?.let { it != "NO_POLICY" } == true,
            ) { "BASELINE_CANDIDATE_GATES_UNKNOWN" }
            requireNotNull(baseSemanticKey(result, identities[index])) { "BASELINE_CANDIDATE_INVALID_IDENTITY" }
            Candidate(
                reference,
                listOf(
                    requireNotNull(result.metricValue(Metric.P95)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                    requireNotNull(result.metricValue(Metric.THROUGHPUT)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                    requireNotNull(result.metricValue(Metric.ERROR_RATE)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                ),
            )
        }
    require(candidates.map { it.reference.runId }.toSet().size == candidates.size) { "BASELINE_DUPLICATE_RUN" }
    for (i in results.indices) {
        for (j in i + 1 until results.size) {
            require(comparableSemantics(results[i], identities[i], results[j], identities[j])) { "BASELINE_MIXED_SEMANTICS" }
        }
    }

    val center2 = candidates.size + 1
    // ponytail: quadratic ranks are bounded to 20 candidates; sort columns if the cap grows.
    val scores =
        candidates.associate { candidate ->
            candidate.reference to
                candidate.metrics.indices.sumOf { metricIndex ->
                    val value = candidate.metrics[metricIndex]
                    val values = candidates.map { it.metrics[metricIndex] }
                    val rank2 = 2 * values.count { it < value } + values.count { it.compareTo(value) == 0 } + 1
                    abs(rank2 - center2)
                }
        }
    val sortedReferences = candidates.map(Candidate::reference).sorted()
    val sortedScores = sortedReferences.map { Score(it, scores.getValue(it)) }
    val winner = sortedScores.minWith(compareBy<Score>({ it.score }, { it.reference }))
    return Selection(
        series = requireSeries(series),
        mode = Mode.STATISTICAL,
        reference = winner.reference,
        algorithm = ALGORITHM,
        candidates = sortedReferences,
        scores = sortedScores,
    ).toJson()
}

/** Release records (ADR 0019) that hold the baseline and the current analysis; null when none or more than one matches. */
internal data class ReleaseComparisonContext(
    val baseline: JsonObject?,
    val current: JsonObject?,
)

internal fun compareAnalyses(
    selection: JsonObject,
    currentReference: JsonObject,
    baselineResult: JsonObject,
    baselineIdentity: JsonObject,
    currentResult: JsonObject,
    currentIdentity: JsonObject,
    windows: WindowComparisonRequest? = null,
    conditionsConfirmed: Boolean? = null,
    releases: ReleaseComparisonContext? = null,
    series: ComparisonSeriesContext? = null,
): JsonObject {
    val parsedSelection = selection.toSelection()
    val current = currentReference.toReference()
    val confirmed = conditionsConfirmed == true
    val profile =
        releases?.let { context ->
            val compared =
                compareReleaseProfiles(context.baseline?.get("profile") as? JsonObject, context.current?.get("profile") as? JsonObject)
            val baselineId = context.baseline?.get("release_id")
            val currentId = context.current?.get("release_id")
            if (compared == null || baselineId == null || currentId == null) {
                null
            } else {
                JsonObject(compared + mapOf("baseline_release_id" to baselineId, "current_release_id" to currentId))
            }
        }
    val warnings =
        buildList {
            when {
                parsedSelection.reference == current -> add("BASELINE_IS_CURRENT_ANALYSIS")
                parsedSelection.reference.runId == current.runId -> add("BASELINE_IS_CURRENT_RUN")
            }
            if (parsedSelection.mode == Mode.STATISTICAL && parsedSelection.candidates.any { it.runId == current.runId }) {
                add("CURRENT_IN_CANDIDATE_SET")
            }
            if (baselineResult.stringOrNull("policy_verdict") != "PASS") add("BASELINE_NOT_PASS")
            if (SMALL_SAMPLE_REASON in coverageReasons(baselineResult)) add("BASELINE_SMALL_SAMPLE")
            // No series warning: the API selects the baseline slot by the series of the current release (ADR 0019, section 7).
            if (baselineIdentity["policy_sha256"] != currentIdentity["policy_sha256"]) add("POLICY_DIFFERS")
            if ((profile?.get("status") as? JsonPrimitive)?.content == "MISMATCH") add("PROFILE_MISMATCH")
            // ADR 0030, R7: the compared metrics are of the whole run, the verdict of a staged analysis is by its steady window.
            if (hasStageBinding(baselineResult) || hasStageBinding(currentResult)) add("WHOLE_RUN_METRICS_WITH_STAGES")
        }
    val compatible = comparableSemantics(baselineResult, baselineIdentity, currentResult, currentIdentity)
    return buildJsonObject {
        put("baseline", parsedSelection.toJson())
        put("current", current.toJson())
        put(
            "comparability",
            if (confirmed) {
                "USER_CONFIRMED"
            } else {
                "UNCONFIRMED"
            },
        )
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
        put("profile", profile ?: JsonNull)
        put(
            "metrics",
            buildJsonArray {
                Metric.entries.forEach { metric ->
                    add(metricComparison(metric, currentResult.metricValue(metric), baselineResult.metricValue(metric), compatible))
                }
            },
        )
        windows?.let { request ->
            put(
                "window_comparison",
                windowComparison(
                    request,
                    baselineResult,
                    currentResult,
                    compatible,
                    confirmed,
                    series,
                ),
            )
        }
    }
}

private fun windowComparison(
    request: WindowComparisonRequest,
    baselineResult: JsonObject,
    currentResult: JsonObject,
    compatible: Boolean,
    conditionsConfirmed: Boolean,
    series: ComparisonSeriesContext? = null,
): JsonObject {
    val baseline = baselineResult.windowSummary(request.baselineWindowId)
    val current = currentResult.windowSummary(request.currentWindowId)
    val notEvaluatedReasons = mutableListOf<String>()
    if (!compatible) notEvaluatedReasons += "INCOMPATIBLE_METRIC_DEFINITION"
    if (baseline == null) notEvaluatedReasons += "BASELINE_WINDOW_NOT_FOUND"
    if (current == null) notEvaluatedReasons += "CURRENT_WINDOW_NOT_FOUND"
    if (notEvaluatedReasons.isNotEmpty()) {
        return windowComparisonJson("NOT_EVALUATED", request, notEvaluatedReasons, emptyList())
    }
    val baselineWindow = checkNotNull(baseline)
    val currentWindow = checkNotNull(current)
    val baselineNoise = withinWindowStandardErrors(baselineWindow, series?.baseline)
    val currentNoise = withinWindowStandardErrors(currentWindow, series?.current)
    val rows =
        WindowMetric.entries.map { metric ->
            windowMetricComparison(metric, currentWindow, baselineWindow, request, conditionsConfirmed, baselineNoise, currentNoise)
        } + resourceMetricComparisons(baselineResult, currentResult, baselineWindow, currentWindow, request, conditionsConfirmed)
    val status =
        when {
            rows.any { it.status == "CANDIDATE" } -> "CANDIDATE"
            rows.any { it.status == "DESCRIPTIVE" } -> "DESCRIPTIVE"
            rows.any { it.status == "INSUFFICIENT_DATA" } -> "INSUFFICIENT_DATA"
            else -> "NO_MATERIAL_CHANGE"
        }
    val reasons =
        buildList {
            if (!conditionsConfirmed) add("CONDITIONS_UNCONFIRMED")
            if (baselineWindow.sampleCount == 0L) add("BASELINE_WINDOW_EMPTY")
            if (currentWindow.sampleCount == 0L) add("CURRENT_WINDOW_EMPTY")
            if (rows.any { it.status == "INSUFFICIENT_DATA" }) add("INCOMPLETE_METRICS")
        }
    return windowComparisonJson(status, request, reasons, rows, baselineWindow, currentWindow)
}

private fun windowComparisonJson(
    status: String,
    request: WindowComparisonRequest,
    reasons: List<String>,
    rows: List<ComparisonRow>,
    baseline: WindowSummary? = null,
    current: WindowSummary? = null,
): JsonObject =
    buildJsonObject {
        put("status", status)
        put("baseline_window", request.baselineWindowId)
        put("current_window", request.currentWindowId)
        put("min_change_percent", request.minChangePercent.stripTrailingZeros().toPlainString())
        put("min_error_rate_delta", request.minErrorRateDelta.stripTrailingZeros().toPlainString())
        put("baseline_sample_count", baseline?.sampleCount?.let(::JsonPrimitive) ?: JsonNull)
        put("current_sample_count", current?.sampleCount?.let(::JsonPrimitive) ?: JsonNull)
        put("baseline_duration_ms", baseline?.durationMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("current_duration_ms", current?.durationMillis?.let(::JsonPrimitive) ?: JsonNull)
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("metrics", buildJsonArray { rows.forEach { add(it.toJson()) } })
    }

private fun windowMetricComparison(
    metric: WindowMetric,
    current: WindowSummary,
    baseline: WindowSummary,
    request: WindowComparisonRequest,
    conditionsConfirmed: Boolean,
    baselineNoise: Map<WindowMetric, Double>? = null,
    currentNoise: Map<WindowMetric, Double>? = null,
): WindowMetricComparison {
    val currentValue = current.value(metric)
    val baselineValue = baseline.value(metric)
    val reason =
        when {
            current.sampleCount == 0L || baseline.sampleCount == 0L -> "EMPTY_WINDOW"
            currentValue == null || baselineValue == null -> "MISSING_METRIC"
            else -> null
        }
    val delta = if (reason == null) checkNotNull(currentValue) - checkNotNull(baselineValue) else null
    val zeroBaseline = reason == null && checkNotNull(baselineValue).isZero()
    val percent = if (reason == null && !zeroBaseline) checkNotNull(delta) / checkNotNull(baselineValue) * HUNDRED else null
    val exceedsMinThreshold =
        when {
            reason != null || (zeroBaseline && metric != WindowMetric.ERROR_RATE) -> false
            metric == WindowMetric.ERROR_RATE -> checkNotNull(delta).absoluteValue() >= Rational.fromDecimal(request.minErrorRateDelta)
            else -> checkNotNull(percent).absoluteValue() >= Rational.fromDecimal(request.minChangePercent)
        }
    val noiseThreshold = combinedNoiseThreshold(metric, baselineNoise, currentNoise)
    val exceedsNoise = noiseThreshold == null || delta == null || delta.absoluteValue() >= noiseThreshold
    val material = exceedsMinThreshold && exceedsNoise
    val withinNoise = exceedsMinThreshold && !exceedsNoise
    val status =
        when {
            reason != null -> "INSUFFICIENT_DATA"
            zeroBaseline && metric != WindowMetric.ERROR_RATE -> "DESCRIPTIVE"
            material && conditionsConfirmed -> "CANDIDATE"
            material -> "DESCRIPTIVE"
            else -> "NO_MATERIAL_CHANGE"
        }
    val rowReason =
        reason
            ?: if (zeroBaseline &&
                metric != WindowMetric.ERROR_RATE
            ) {
                "ZERO_BASELINE"
            } else if (withinNoise) {
                "WITHIN_NOISE"
            } else if (material &&
                !conditionsConfirmed
            ) {
                "CONDITIONS_UNCONFIRMED"
            } else {
                null
            }
    return WindowMetricComparison(metric, currentValue, baselineValue, delta, percent, status, rowReason, current, baseline)
}

private fun resourceMetricComparisons(
    baselineResult: JsonObject,
    currentResult: JsonObject,
    baselineWindow: WindowSummary,
    currentWindow: WindowSummary,
    request: WindowComparisonRequest,
    conditionsConfirmed: Boolean,
): List<ResourceMetricComparison> {
    val baselineBindings = baselineResult.windowBindings(request.baselineWindowId).groupBy { it }
    val currentBindings = currentResult.windowBindings(request.currentWindowId).groupBy { it }
    return (baselineBindings.keys + currentBindings.keys).toSet().sortedBy { it.resourceName("median", false) }.flatMap { binding ->
        val baselineGroup = baselineBindings[binding].orEmpty()
        val currentGroup = currentBindings[binding].orEmpty()
        val reason =
            when {
                baselineGroup.isEmpty() || currentGroup.isEmpty() -> "RESOURCE_BINDING_MISSING"
                baselineGroup.size != 1 || currentGroup.size != 1 -> "RESOURCE_BINDING_AMBIGUOUS"
                else -> null
            }
        val baseline = if (reason == null) baselineResult.resourceSummary(request.baselineWindowId, binding) else null
        val current = if (reason == null) currentResult.resourceSummary(request.currentWindowId, binding) else null
        listOf("median", "q95").map { statistic ->
            resourceMetricComparison(
                binding,
                statistic,
                current,
                baseline,
                currentWindow,
                baselineWindow,
                request,
                conditionsConfirmed,
                reason,
            )
        }
    }
}

private fun JsonObject.windowBindings(windowId: String): List<JsonObject> =
    windowSummaryEvidence(windowId)
        ?.get("resource_bindings")
        ?.let { it as? JsonArray }
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

private fun JsonObject.resourceSummary(
    windowId: String,
    binding: JsonObject,
): JsonObject? =
    (this["evidence"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.singleOrNull {
            it.stringOrNull("type") == "resource_summary" &&
                it.stringOrNull("window_id") == windowId &&
                RESOURCE_BINDING_FIELDS.all { field -> it[field] == binding[field] }
        }

private fun resourceMetricComparison(
    binding: JsonObject,
    statistic: String,
    currentSummary: JsonObject?,
    baselineSummary: JsonObject?,
    currentWindow: WindowSummary,
    baselineWindow: WindowSummary,
    request: WindowComparisonRequest,
    conditionsConfirmed: Boolean,
    bindingReason: String?,
): ResourceMetricComparison {
    val current = currentSummary?.statisticsValue(statistic)
    val baseline = baselineSummary?.statisticsValue(statistic)
    val empty = baselineWindow.sampleCount == 0L || currentWindow.sampleCount == 0L
    val reason = if (empty) "EMPTY_WINDOW" else bindingReason ?: if (current == null || baseline == null) "MISSING_METRIC" else null
    val delta = if (reason == null) checkNotNull(current) - checkNotNull(baseline) else null
    val zeroBaseline = reason == null && checkNotNull(baseline).isZero()
    val percent = if (reason == null && !zeroBaseline) checkNotNull(delta) / checkNotNull(baseline) * HUNDRED else null
    val material =
        reason == null && !zeroBaseline && checkNotNull(percent).absoluteValue() >= Rational.fromDecimal(request.minChangePercent)
    val status =
        when {
            reason != null -> "INSUFFICIENT_DATA"
            zeroBaseline -> "DESCRIPTIVE"
            material && conditionsConfirmed -> "CANDIDATE"
            material -> "DESCRIPTIVE"
            else -> "NO_MATERIAL_CHANGE"
        }
    return ResourceMetricComparison(
        binding.resourceName(statistic, bindingReason == "RESOURCE_BINDING_MISSING"),
        binding.stringOrNull("unit") ?: "",
        binding.stringOrNull("series_id"),
        binding.stringOrNull("entity"),
        current,
        baseline,
        delta,
        percent,
        status,
        reason ?: if (zeroBaseline) {
            "ZERO_BASELINE"
        } else if (material && !conditionsConfirmed) {
            "CONDITIONS_UNCONFIRMED"
        } else {
            null
        },
        currentSummary?.stringOrNull("id"),
        baselineSummary?.stringOrNull("id"),
        currentWindow,
        baselineWindow,
    )
}

private fun JsonObject.statisticsValue(name: String): Rational? =
    objectOrNull("statistics")
        ?.get(name)
        ?.let { it as? JsonPrimitive }
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?.toBigDecimalOrNull()
        ?.let(Rational::fromDecimal)

private fun JsonObject.resourceName(
    statistic: String,
    disambiguate: Boolean,
): String {
    val name = "${stringOrNull("metric") ?: "resource"}_$statistic"
    val stage = (objectOrNull("labels")?.get("stage") as? JsonPrimitive)?.content
    return if (disambiguate && stage != null) "$name:$stage" else name
}

private fun JsonObject.windowSummary(windowId: String): WindowSummary? {
    val evidence = windowSummaryEvidence(windowId) ?: return null
    val from = evidence.longOrNull("from_epoch_ms") ?: return null
    val to = evidence.longOrNull("to_epoch_ms") ?: return null
    val samples = evidence.longOrNull("sample_count") ?: return null
    if (from >= to || samples < 0) return null
    return WindowSummary(
        evidence.stringOrNull("id") ?: return null,
        from,
        to,
        samples,
        to - from,
        evidence.objectOrNull("latency_ms"),
        evidence.ratioOrNull("throughput_rps"),
        evidence.ratioOrNull("error_rate_ratio"),
    )
}

private fun JsonObject.overallWindowSummary(runSeries: RunLoadSeries?): WindowSummary? {
    val overall =
        (this["evidence"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { evidence ->
                evidence.stringOrNull("type") == "metric_summary" &&
                    evidence.objectOrNull("scope")?.stringOrNull("kind") == "overall"
            } ?: return null
    val samples = overall.longOrNull("sample_count") ?: return null
    if (samples < 0) return null
    val from = runSeries?.runStartEpochMillis ?: 0L
    val to = runSeries?.runEndEpochMillis?.takeIf { it > from } ?: from
    return WindowSummary(
        overall.stringOrNull("id") ?: return null,
        from,
        to,
        samples,
        to - from,
        overall.objectOrNull("latency_ms"),
        overall.ratioOrNull("throughput_rps"),
        overall.ratioOrNull("error_rate_ratio"),
    )
}

private fun JsonObject.windowSummaryEvidence(windowId: String): JsonObject? =
    (this["evidence"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.singleOrNull { it.stringOrNull("type") == "window_metric_summary" && it.stringOrNull("window_id") == windowId }

private fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.content?.toLongOrNull()

private data class WindowSummary(
    val evidenceId: String,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val sampleCount: Long,
    val durationMillis: Long,
    val latency: JsonObject?,
    val throughput: Rational?,
    val errorRate: Rational?,
) {
    fun value(metric: WindowMetric): Rational? {
        if (sampleCount == 0L) return null
        return when (metric) {
            WindowMetric.P50 -> latency?.nonNegativeInteger("p50")
            WindowMetric.P95 -> latency?.nonNegativeInteger("p95")
            WindowMetric.P99 -> latency?.nonNegativeInteger("p99")
            WindowMetric.THROUGHPUT -> throughput
            WindowMetric.ERROR_RATE -> errorRate
        }
    }
}

private sealed interface ComparisonRow {
    val status: String

    fun toJson(): JsonObject
}

private data class WindowMetricComparison(
    val metric: WindowMetric,
    val current: Rational?,
    val baseline: Rational?,
    val delta: Rational?,
    val percent: Rational?,
    override val status: String,
    val reason: String?,
    val currentWindow: WindowSummary,
    val baselineWindow: WindowSummary,
) : ComparisonRow {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("metric", metric.wireName)
            put("unit", metric.unit)
            put("current", current?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("baseline", baseline?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("delta", delta?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("delta_percent", percent?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("percent_reason", (reason ?: if (baseline?.isZero() == true) "ZERO_BASELINE" else null)?.let(::JsonPrimitive) ?: JsonNull)
            put("status", status)
            put("resource_series_id", JsonNull)
            put("entity", JsonNull)
            put("baseline_evidence_id", baselineWindow.evidenceId)
            put("current_evidence_id", currentWindow.evidenceId)
            put("baseline_sample_count", baselineWindow.sampleCount)
            put("current_sample_count", currentWindow.sampleCount)
            put("baseline_duration_ms", baselineWindow.durationMillis)
            put("current_duration_ms", currentWindow.durationMillis)
        }
}

private data class ResourceMetricComparison(
    val metric: String,
    val unit: String,
    val resourceSeriesId: String?,
    val entity: String?,
    val current: Rational?,
    val baseline: Rational?,
    val delta: Rational?,
    val percent: Rational?,
    override val status: String,
    val reason: String?,
    val currentEvidenceId: String?,
    val baselineEvidenceId: String?,
    val currentWindow: WindowSummary,
    val baselineWindow: WindowSummary,
) : ComparisonRow {
    override fun toJson(): JsonObject =
        buildJsonObject {
            put("metric", metric)
            put("unit", unit)
            put("current", current?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("baseline", baseline?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("delta", delta?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("delta_percent", percent?.format()?.let(::JsonPrimitive) ?: JsonNull)
            put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("percent_reason", (reason ?: if (baseline?.isZero() == true) "ZERO_BASELINE" else null)?.let(::JsonPrimitive) ?: JsonNull)
            put("status", status)
            put("resource_series_id", resourceSeriesId?.let(::JsonPrimitive) ?: JsonNull)
            put("entity", entity?.let(::JsonPrimitive) ?: JsonNull)
            put("baseline_evidence_id", baselineEvidenceId?.let(::JsonPrimitive) ?: JsonNull)
            put("current_evidence_id", currentEvidenceId?.let(::JsonPrimitive) ?: JsonNull)
            put("baseline_sample_count", baselineWindow.sampleCount)
            put("current_sample_count", currentWindow.sampleCount)
            put("baseline_duration_ms", baselineWindow.durationMillis)
            put("current_duration_ms", currentWindow.durationMillis)
        }
}

internal fun validateBaselineSelection(selection: JsonObject): JsonObject = selection.toSelection().toJson()

private fun metricComparison(
    metric: Metric,
    current: Rational?,
    baseline: Rational?,
    compatible: Boolean,
): JsonObject {
    val reason =
        when {
            current == null || baseline == null -> "MISSING_METRIC"
            !compatible -> "INCOMPATIBLE_METRIC_DEFINITION"
            else -> null
        }
    val delta = if (reason == null) checkNotNull(current) - checkNotNull(baseline) else null
    val zeroBaseline = reason == null && checkNotNull(baseline).isZero()
    val percent = if (reason == null && !zeroBaseline) checkNotNull(delta) / checkNotNull(baseline) * HUNDRED else null
    return buildJsonObject {
        put("metric", metric.wireName)
        put("unit", metric.unit)
        put("current", current?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("baseline", baseline?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("delta", delta?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("delta_percent", percent?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        put("percent_reason", (reason ?: if (zeroBaseline) "ZERO_BASELINE" else null)?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun JsonObject.metricValue(metric: Metric): Rational? {
    val overall =
        (this["evidence"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { evidence ->
                evidence.stringOrNull("type") == "metric_summary" &&
                    evidence.objectOrNull("scope")?.stringOrNull("kind") == "overall"
            } ?: return null
    return when (metric) {
        Metric.P95 -> overall.objectOrNull("latency_ms")?.nonNegativeInteger("p95")
        Metric.P99 -> overall.objectOrNull("latency_ms")?.nonNegativeInteger("p99")
        Metric.THROUGHPUT -> overall.ratioOrNull("throughput_rps")
        Metric.ERROR_RATE -> overall.ratioOrNull("error_rate_ratio")
    }
}

internal fun hasStageBinding(result: JsonObject): Boolean =
    (result["evidence"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.stringOrNull("type") == "stage_binding" }

internal data class BaselineRegressionFailure(
    val windowId: String?,
    val metric: String,
    val baseline: String,
    val current: String,
    val delta: String,
    val deltaPercent: String?,
)

internal sealed interface BaselineRegressionOutcome {
    data object Pass : BaselineRegressionOutcome

    data class Regressed(
        val failures: List<BaselineRegressionFailure>,
    ) : BaselineRegressionOutcome

    data class NotEvaluated(
        val reason: String,
    ) : BaselineRegressionOutcome
}

internal fun evaluateBaselineRegression(
    baselineResult: JsonObject,
    baselineIdentity: JsonObject,
    currentResult: JsonObject,
    currentIdentity: JsonObject,
    series: ComparisonSeriesContext? = null,
): BaselineRegressionOutcome {
    if (!comparableSemantics(baselineResult, baselineIdentity, currentResult, currentIdentity)) {
        return BaselineRegressionOutcome.NotEvaluated("INCOMPATIBLE_METRIC_DEFINITION")
    }
    val staged = hasStageBinding(baselineResult) || hasStageBinding(currentResult)
    val failures = mutableListOf<BaselineRegressionFailure>()
    if (staged) {
        val windowIds =
            currentResult.stageBindingEvidence()?.stringListOrNull("evaluated_window_ids")?.takeIf { it.isNotEmpty() }
                ?: return BaselineRegressionOutcome.NotEvaluated("MISSING_STAGE_WINDOWS")
        for (windowId in windowIds) {
            val baselineWindow =
                baselineResult.windowSummary(windowId)
                    ?: return BaselineRegressionOutcome.NotEvaluated("BASELINE_WINDOW_NOT_FOUND")
            val currentWindow =
                currentResult.windowSummary(windowId)
                    ?: return BaselineRegressionOutcome.NotEvaluated("CURRENT_WINDOW_NOT_FOUND")
            val outcome =
                evaluateWindowRegression(
                    windowId = windowId,
                    baselineWindow = baselineWindow,
                    currentWindow = currentWindow,
                    request = WindowComparisonRequest(windowId, windowId),
                    series = series,
                )
            when (outcome) {
                is WindowRegressionCheck.NotEvaluated -> return BaselineRegressionOutcome.NotEvaluated(outcome.reason)
                is WindowRegressionCheck.Evaluated -> failures += outcome.failures
            }
        }
    } else {
        val baselineWindow =
            baselineResult.overallWindowSummary(series?.baseline)
                ?: return BaselineRegressionOutcome.NotEvaluated("MISSING_METRIC")
        val currentWindow =
            currentResult.overallWindowSummary(series?.current)
                ?: return BaselineRegressionOutcome.NotEvaluated("MISSING_METRIC")
        val outcome =
            evaluateWindowRegression(
                windowId = null,
                baselineWindow = baselineWindow,
                currentWindow = currentWindow,
                request = WindowComparisonRequest("overall", "overall"),
                series = series,
            )
        when (outcome) {
            is WindowRegressionCheck.NotEvaluated -> return BaselineRegressionOutcome.NotEvaluated(outcome.reason)
            is WindowRegressionCheck.Evaluated -> failures += outcome.failures
        }
    }
    return if (failures.isEmpty()) BaselineRegressionOutcome.Pass else BaselineRegressionOutcome.Regressed(failures)
}

private sealed interface WindowRegressionCheck {
    data class NotEvaluated(
        val reason: String,
    ) : WindowRegressionCheck

    data class Evaluated(
        val failures: List<BaselineRegressionFailure>,
    ) : WindowRegressionCheck
}

private fun evaluateWindowRegression(
    windowId: String?,
    baselineWindow: WindowSummary,
    currentWindow: WindowSummary,
    request: WindowComparisonRequest,
    series: ComparisonSeriesContext?,
): WindowRegressionCheck {
    val baselineNoise = withinWindowStandardErrors(baselineWindow, series?.baseline)
    val currentNoise = withinWindowStandardErrors(currentWindow, series?.current)
    val rows =
        WindowMetric.entries.map { metric ->
            windowMetricComparison(metric, currentWindow, baselineWindow, request, true, baselineNoise, currentNoise)
        }
    if (rows.any { it.status == "INSUFFICIENT_DATA" }) {
        return WindowRegressionCheck.NotEvaluated("INSUFFICIENT_DATA")
    }
    val regressions =
        rows.mapNotNull { row ->
            if (row.status != "CANDIDATE") return@mapNotNull null
            val delta = row.delta ?: return@mapNotNull null
            val regressed =
                when (row.metric) {
                    WindowMetric.P50, WindowMetric.P95, WindowMetric.P99, WindowMetric.ERROR_RATE -> delta.numerator.signum() > 0
                    WindowMetric.THROUGHPUT -> delta.numerator.signum() < 0
                }
            if (!regressed) return@mapNotNull null
            BaselineRegressionFailure(
                windowId = windowId,
                metric = row.metric.wireName,
                baseline = checkNotNull(row.baseline).format(),
                current = checkNotNull(row.current).format(),
                delta = delta.format(),
                deltaPercent = row.percent?.format(),
            )
        }
    return WindowRegressionCheck.Evaluated(regressions)
}

internal fun comparableSemantics(
    baselineResult: JsonObject,
    baselineIdentity: JsonObject,
    currentResult: JsonObject,
    currentIdentity: JsonObject,
    rpsTolerance: BigDecimal = DEFAULT_STAGE_RPS_TOLERANCE,
): Boolean {
    val baseKey = baseSemanticKey(baselineResult, baselineIdentity) ?: return false
    val currentKey = baseSemanticKey(currentResult, currentIdentity) ?: return false
    if (baseKey != currentKey) return false

    val baseRaw = baselineIdentity["load_stages_sha256"]
    val currentRaw = currentIdentity["load_stages_sha256"]
    val baseHash = baselineIdentity.stringOrNull("load_stages_sha256")
    val currentHash = currentIdentity.stringOrNull("load_stages_sha256")
    if ((baseRaw != null && baseRaw !is JsonNull && baseHash == null) ||
        (currentRaw != null && currentRaw !is JsonNull && currentHash == null)
    ) {
        return false
    }
    if (baseHash == currentHash) return true
    if (baseHash == null || currentHash == null) return false

    val baseBinding = baselineResult.stageBindingEvidence() ?: return false
    val currentBinding = currentResult.stageBindingEvidence() ?: return false
    if (baseBinding.stringOrNull("mode") != "declared_stages" ||
        currentBinding.stringOrNull("mode") != "declared_stages"
    ) {
        return false
    }
    if (baseBinding.stringOrNull("declaration_sha256") != baseHash ||
        currentBinding.stringOrNull("declaration_sha256") != currentHash
    ) {
        return false
    }
    val baseWindowIds = baseBinding.stringListOrNull("evaluated_window_ids") ?: return false
    val currentWindowIds = currentBinding.stringListOrNull("evaluated_window_ids") ?: return false
    if (baseWindowIds.isEmpty() || baseWindowIds != currentWindowIds) return false
    val baseStages = baseBinding.stageIdRolesOrNull() ?: return false
    val currentStages = currentBinding.stageIdRolesOrNull() ?: return false
    if (baseStages.isEmpty() || baseStages != currentStages) return false

    val tolerance = Rational.fromDecimal(rpsTolerance)
    for (windowId in baseWindowIds) {
        val baseSummary = baselineResult.windowSummary(windowId) ?: return false
        val currentSummary = currentResult.windowSummary(windowId) ?: return false
        if (baseSummary.sampleCount <= 0L || currentSummary.sampleCount <= 0L) return false
        val baseRps = baseSummary.throughput ?: return false
        val currentRps = currentSummary.throughput ?: return false
        if (baseRps.isZero() || currentRps.isZero()) return false
        val diff = (currentRps - baseRps).absoluteValue()
        val minRps = if (currentRps <= baseRps) currentRps else baseRps
        if (diff > tolerance * minRps) return false
    }
    return true
}

private fun JsonObject.stageBindingEvidence(): JsonObject? =
    (this["evidence"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.singleOrNull { it.stringOrNull("type") == "stage_binding" }

private fun JsonObject.stringListOrNull(name: String): List<String>? {
    val array = this[name] as? JsonArray ?: return null
    val result = ArrayList<String>(array.size)
    for (element in array) {
        val value = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
        result += value
    }
    return result
}

private fun JsonObject.stageIdRolesOrNull(): List<Pair<String, String>>? {
    val array = this["stages"] as? JsonArray ?: return null
    val result = ArrayList<Pair<String, String>>(array.size)
    for (element in array) {
        val obj = element as? JsonObject ?: return null
        val id = obj.stringOrNull("id") ?: return null
        val role = obj.stringOrNull("role") ?: return null
        result += id to role
    }
    return result
}

private fun baseSemanticKey(
    result: JsonObject,
    identity: JsonObject,
): List<JsonElement>? {
    val values = mutableListOf(result["analysis_mode"] ?: return null)
    SEMANTIC_FIELDS.forEach { field -> values += identity[field] ?: return null }
    // ADR 0014, часть 5: плечо входит в ключ условно. Отсутствие у обоих анализов равно равенству, отсутствие у одного - несовместимость.
    values += identity["resource_arm"] ?: JsonNull
    return values
}

private fun combinedNoiseThreshold(
    metric: WindowMetric,
    baselineNoise: Map<WindowMetric, Double>?,
    currentNoise: Map<WindowMetric, Double>?,
): Rational? {
    val baseSe = baselineNoise?.get(metric) ?: return null
    val curSe = currentNoise?.get(metric) ?: return null
    val combinedSe = sqrt(baseSe * baseSe + curSe * curSe)
    if (!combinedSe.isFinite() || combinedSe < 0.0) return null
    return if (combinedSe == 0.0) {
        Rational(BigInteger.ZERO, BigInteger.ONE)
    } else {
        Rational.fromDecimal(BigDecimal.valueOf(NOISE_Z_SIGMA * combinedSe))
    }
}

private data class BlockAggregate(
    val sampleCount: Long,
    val errorCount: Long,
    val maxLatencyMillis: Long,
    val histogram: PackedHistogram,
)

private fun combineBlocks(
    left: BlockAggregate?,
    right: BlockAggregate?,
): BlockAggregate? {
    if (left == null) return right
    if (right == null) return left
    val merged = left.histogram.copy()
    merged.add(right.histogram)
    return BlockAggregate(
        sampleCount = Math.addExact(left.sampleCount, right.sampleCount),
        errorCount = Math.addExact(left.errorCount, right.errorCount),
        maxLatencyMillis = maxOf(left.maxLatencyMillis, right.maxLatencyMillis),
        histogram = merged,
    )
}

private class TwoStackBlockQueue {
    private class Entry(
        val raw: BlockAggregate?,
        val agg: BlockAggregate?,
    )

    private val inStack = ArrayList<Entry>()
    private val outStack = ArrayList<BlockAggregate?>()

    fun push(item: BlockAggregate?) {
        val topAgg = inStack.lastOrNull()?.agg
        inStack.add(Entry(item, combineBlocks(topAgg, item)))
    }

    fun pop() {
        if (outStack.isEmpty()) {
            while (inStack.isNotEmpty()) {
                val entry = inStack.removeAt(inStack.lastIndex)
                val outTop = outStack.lastOrNull()
                outStack.add(combineBlocks(entry.raw, outTop))
            }
        }
        if (outStack.isNotEmpty()) {
            outStack.removeAt(outStack.lastIndex)
        }
    }

    fun aggregate(): BlockAggregate? = combineBlocks(outStack.lastOrNull(), inStack.lastOrNull()?.agg)
}

private fun withinWindowStandardErrors(
    window: WindowSummary,
    series: RunLoadSeries?,
): Map<WindowMetric, Double>? {
    if (series == null || window.sampleCount <= 0L) return null
    val cellCountLong = window.durationMillis / 1_000L
    if (cellCountLong < NOISE_MIN_CELLS.toLong() || cellCountLong > NOISE_MAX_CELLS.toLong()) return null
    val cellCount = cellCountLong.toInt()
    val blockSize = minOf(NOISE_MAX_BLOCK, cellCount / 4)
    if (blockSize < 2 || cellCount <= blockSize) return null

    val cells = arrayOfNulls<BlockAggregate>(cellCount)
    var nonEmptyCells = 0
    for (bucket in series.buckets) {
        if (bucket.sampleCount < 0L || bucket.errorCount < 0L || bucket.errorCount > bucket.sampleCount || bucket.maxLatencyMillis < 0L) {
            return null
        }
        val epochMs = series.runStartEpochMillis + bucket.bucketStartMillis
        if (epochMs < window.fromEpochMillis || epochMs >= window.toEpochMillis) continue
        val idx = ((epochMs - window.fromEpochMillis) / 1_000L).toInt()
        if (idx !in 0 until cellCount) continue
        if (cells[idx] != null) return null
        if (bucket.sampleCount == 0L) continue
        val histogram =
            try {
                PackedHistogram.decodeFromCompressedByteBuffer(
                    ByteBuffer.wrap(Base64.getDecoder().decode(bucket.hdrV2Base64)),
                    MAX_HISTOGRAM_TRACKABLE_MS,
                )
            } catch (_: Exception) {
                return null
            }
        if (histogram.totalCount != bucket.sampleCount) return null
        cells[idx] = BlockAggregate(bucket.sampleCount, bucket.errorCount, bucket.maxLatencyMillis, histogram)
        nonEmptyCells++
    }
    if (nonEmptyCells == 0) return null

    val queue = TwoStackBlockQueue()
    try {
        for (i in 0 until blockSize) {
            queue.push(cells[i])
        }
    } catch (_: RuntimeException) {
        return null
    }

    val throughputValues = DoubleArray(cellCount)
    val p50Values = ArrayList<Double>(cellCount)
    val p95Values = ArrayList<Double>(cellCount)
    val p99Values = ArrayList<Double>(cellCount)
    val errorRateValues = ArrayList<Double>(cellCount)

    for (i in 0 until cellCount) {
        val agg = queue.aggregate()
        val samples = agg?.sampleCount ?: 0L
        throughputValues[i] = samples.toDouble() / blockSize.toDouble()
        if (agg != null && samples > 0L) {
            p50Values.add(minOf(agg.histogram.getValueAtPercentile(50.0), agg.maxLatencyMillis).toDouble())
            p95Values.add(minOf(agg.histogram.getValueAtPercentile(95.0), agg.maxLatencyMillis).toDouble())
            p99Values.add(minOf(agg.histogram.getValueAtPercentile(99.0), agg.maxLatencyMillis).toDouble())
            errorRateValues.add(agg.errorCount.toDouble() / samples.toDouble())
        }
        if (i + 1 < cellCount) {
            try {
                queue.pop()
                queue.push(cells[(i + blockSize) % cellCount])
            } catch (_: RuntimeException) {
                return null
            }
        }
    }

    fun computeSe(
        values: List<Double>,
        requireGtBlock: Boolean,
    ): Double? {
        val n = values.size
        if (n < 2 || (requireGtBlock && n <= blockSize)) return null
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / n.toDouble()
        var sq = 0.0
        for (v in values) {
            val d = v - mean
            sq += d * d
        }
        val varBlocks = sq / n.toDouble()
        val se = sqrt(varBlocks * blockSize.toDouble() / cellCount.toDouble())
        return if (se.isFinite() && se >= 0.0) se else null
    }

    fun computeSeArray(values: DoubleArray): Double? {
        val n = values.size
        if (n < 2) return null
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / n.toDouble()
        var sq = 0.0
        for (v in values) {
            val d = v - mean
            sq += d * d
        }
        val varBlocks = sq / n.toDouble()
        val se = sqrt(varBlocks * blockSize.toDouble() / cellCount.toDouble())
        return if (se.isFinite() && se >= 0.0) se else null
    }

    return buildMap {
        computeSeArray(throughputValues)?.let { put(WindowMetric.THROUGHPUT, it) }
        computeSe(p50Values, requireGtBlock = true)?.let { put(WindowMetric.P50, it) }
        computeSe(p95Values, requireGtBlock = true)?.let { put(WindowMetric.P95, it) }
        computeSe(p99Values, requireGtBlock = true)?.let { put(WindowMetric.P99, it) }
        computeSe(errorRateValues, requireGtBlock = true)?.let { put(WindowMetric.ERROR_RATE, it) }
    }
}

private fun JsonObject.ratioOrNull(name: String): Rational? {
    val ratio = this[name] as? JsonObject ?: return null
    if (ratio.keys != RATIO_FIELDS) return null
    val numerator = ratio.nonNegativeBigInteger("numerator") ?: return null
    val denominator = ratio.nonNegativeBigInteger("denominator")?.takeUnless(BigInteger::equalsZero) ?: return null
    return Rational(numerator, denominator)
}

private fun JsonObject.nonNegativeInteger(name: String): Rational? = nonNegativeBigInteger(name)?.let { Rational(it, BigInteger.ONE) }

private fun JsonObject.nonNegativeBigInteger(name: String): BigInteger? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }
}

private fun JsonObject.toReference(): Reference {
    require(keys == REFERENCE_FIELDS) { "INVALID_BASELINE_REFERENCE" }
    val runId = requiredString("run_id")
    val analysisId = requiredString("analysis_id")
    require(RUN_ID.matches(runId) && SHA256.matches(analysisId)) { "INVALID_BASELINE_REFERENCE" }
    return Reference(runId, analysisId)
}

private fun JsonObject.toBaselineCondition(): BaselineCondition {
    require(keys == BASELINE_CONDITION_FIELDS) { "INVALID_BASELINE_CONDITION" }
    require(conditionString("schema_version") == BASELINE_CONDITION_SCHEMA) { "INVALID_BASELINE_CONDITION" }
    val baseline = (this["baseline"] as? JsonObject)?.toReference() ?: throw IllegalArgumentException("INVALID_BASELINE_CONDITION")
    val current = (this["current"] as? JsonObject)?.toReference() ?: throw IllegalArgumentException("INVALID_BASELINE_CONDITION")
    val windows =
        when (val value = getValue("windows")) {
            JsonNull -> null
            is JsonObject -> {
                require(value.keys == BASELINE_CONDITION_WINDOW_FIELDS) { "INVALID_BASELINE_CONDITION" }
                ConditionWindows(
                    requireConditionWindow(value.conditionString("baseline_window")),
                    requireConditionWindow(value.conditionString("current_window")),
                )
            }
            else -> throw IllegalArgumentException("INVALID_BASELINE_CONDITION")
        }
    val decision =
        ConditionDecision.entries.singleOrNull { it.name == conditionString("decision") }
            ?: throw IllegalArgumentException("INVALID_BASELINE_CONDITION")
    require(conditionString("provenance") == BASELINE_CONDITION_PROVENANCE) { "INVALID_BASELINE_CONDITION" }
    val updatedAt =
        try {
            Instant.parse(conditionString("updated_at"))
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("INVALID_BASELINE_CONDITION")
        }
    return BaselineCondition(ConditionBinding(baseline, current, windows), decision, updatedAt)
}

private fun BaselineCondition.toJson(): JsonObject =
    buildJsonObject {
        put("schema_version", BASELINE_CONDITION_SCHEMA)
        put("baseline", binding.baseline.toJson())
        put("current", binding.current.toJson())
        put("windows", binding.windows?.toJson() ?: JsonNull)
        put("decision", decision.name)
        put("provenance", BASELINE_CONDITION_PROVENANCE)
        put("updated_at", updatedAt.toString())
    }

private fun ConditionBinding.toJson(): JsonObject =
    buildJsonObject {
        put("baseline", baseline.toJson())
        put("current", current.toJson())
        put("windows", windows?.toJson() ?: JsonNull)
    }

private fun ConditionWindows.toJson(): JsonObject =
    buildJsonObject {
        put("baseline_window", baselineWindowId)
        put("current_window", currentWindowId)
    }

private fun JsonObject.conditionString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("INVALID_BASELINE_CONDITION")

private fun requireConditionWindow(value: String): String {
    require(value.isNotBlank() && value.encodeToByteArray().size <= MAX_CONDITION_WINDOW_BYTES && value.none(Char::isISOControl)) {
        "INVALID_BASELINE_CONDITION"
    }
    return value
}

private fun JsonObject.toSelection(): Selection {
    require(keys == SELECTION_FIELDS && requiredString("schema_version") == SCHEMA_VERSION) { "INVALID_BASELINE_SELECTION" }
    val mode = Mode.entries.singleOrNull { it.wireName == requiredString("mode") } ?: error("INVALID_BASELINE_SELECTION")
    val reference = objectOrNull("reference")?.toReference() ?: error("INVALID_BASELINE_SELECTION")
    val candidates =
        (this["candidates"] as? JsonArray)?.map { (it as? JsonObject)?.toReference() ?: error("INVALID_BASELINE_SELECTION") }
            ?: error("INVALID_BASELINE_SELECTION")
    val scores =
        (this["scores"] as? JsonArray)?.map { element ->
            val score = element as? JsonObject ?: error("INVALID_BASELINE_SELECTION")
            require(score.keys == SCORE_FIELDS) { "INVALID_BASELINE_SELECTION" }
            Score(
                score.objectOrNull("reference")?.toReference() ?: error("INVALID_BASELINE_SELECTION"),
                score["score"]
                    ?.jsonPrimitive
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                    ?.takeIf { it >= 0 }
                    ?: error("INVALID_BASELINE_SELECTION"),
            )
        } ?: error("INVALID_BASELINE_SELECTION")
    val algorithm =
        when (val value = getValue("algorithm")) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(value.isString) { "INVALID_BASELINE_SELECTION" }
                value.content
            }

            else -> throw IllegalArgumentException("INVALID_BASELINE_SELECTION")
        }
    val selection = Selection(requireSeries(requiredString("series")), mode, reference, algorithm, candidates, scores)
    when (mode) {
        Mode.MANUAL ->
            require(algorithm == null && candidates == listOf(reference) && scores.isEmpty()) { "INVALID_BASELINE_SELECTION" }

        Mode.STATISTICAL -> {
            require(algorithm == ALGORITHM && candidates.size in MIN_CANDIDATES..MAX_CANDIDATES) { "INVALID_BASELINE_SELECTION" }
            require(candidates == candidates.sorted() && candidates.map(Reference::runId).toSet().size == candidates.size) {
                "INVALID_BASELINE_SELECTION"
            }
            require(scores.map(Score::reference) == candidates) { "INVALID_BASELINE_SELECTION" }
            require(reference == scores.minWith(compareBy<Score>({ it.score }, { it.reference })).reference) {
                "INVALID_BASELINE_SELECTION"
            }
        }
    }
    return selection
}

private fun Selection.toJson(): JsonObject =
    buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("series", series)
        put("mode", mode.wireName)
        put("reference", reference.toJson())
        put("algorithm", algorithm?.let(::JsonPrimitive) ?: JsonNull)
        put("candidates", buildJsonArray { candidates.forEach { add(it.toJson()) } })
        put(
            "scores",
            buildJsonArray {
                scores.forEach { score ->
                    add(
                        buildJsonObject {
                            put("reference", score.reference.toJson())
                            put("score", score.score)
                        },
                    )
                }
            },
        )
    }

private fun Reference.toJson(): JsonObject =
    buildJsonObject {
        put("run_id", runId)
        put("analysis_id", analysisId)
    }

private fun requireSeries(series: String): String {
    require(series.isNotEmpty() && series.encodeToByteArray().size <= MAX_SERIES_BYTES) { "INVALID_BASELINE_SERIES" }
    return series
}

private fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "INVALID_BASELINE_SELECTION" }
    return value.content
}

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private data class Reference(
    val runId: String,
    val analysisId: String,
) : Comparable<Reference> {
    override fun compareTo(other: Reference): Int = compareValuesBy(this, other, Reference::runId, Reference::analysisId)
}

private data class ConditionWindows(
    val baselineWindowId: String,
    val currentWindowId: String,
)

private data class ConditionBinding(
    val baseline: Reference,
    val current: Reference,
    val windows: ConditionWindows?,
)

private data class BaselineCondition(
    val binding: ConditionBinding,
    val decision: ConditionDecision,
    val updatedAt: Instant,
)

private enum class ConditionDecision(
    val confirmation: Boolean?,
) {
    CONFIRMED(true),
    NOT_CONFIRMED(false),
    UNKNOWN(null),
}

private data class Candidate(
    val reference: Reference,
    val metrics: List<Rational>,
)

private data class Score(
    val reference: Reference,
    val score: Int,
)

private data class Selection(
    val series: String,
    val mode: Mode,
    val reference: Reference,
    val algorithm: String?,
    val candidates: List<Reference>,
    val scores: List<Score>,
)

private data class Rational(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<Rational> {
    init {
        require(denominator.signum() > 0)
    }

    override fun compareTo(other: Rational): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)

    operator fun minus(other: Rational) =
        Rational(
            numerator * other.denominator - other.numerator * denominator,
            denominator * other.denominator,
        )

    operator fun div(other: Rational): Rational {
        val quotientNumerator = numerator * other.denominator
        val quotientDenominator = denominator * other.numerator
        return if (quotientDenominator.signum() < 0) {
            Rational(-quotientNumerator, -quotientDenominator)
        } else {
            Rational(quotientNumerator, quotientDenominator)
        }
    }

    operator fun times(value: BigInteger) = Rational(numerator * value, denominator)

    operator fun times(other: Rational) = Rational(numerator * other.numerator, denominator * other.denominator)

    fun isZero(): Boolean = numerator.signum() == 0

    fun absoluteValue(): Rational = Rational(numerator.abs(), denominator)

    fun format(): String =
        BigDecimal(numerator)
            .divide(BigDecimal(denominator), DISPLAY_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()

    companion object {
        fun fromDecimal(value: BigDecimal): Rational =
            if (value.scale() >= 0) {
                Rational(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
            } else {
                Rational(value.unscaledValue() * BigInteger.TEN.pow(-value.scale()), BigInteger.ONE)
            }
    }
}

private enum class Mode(
    val wireName: String,
) {
    MANUAL("manual"),
    STATISTICAL("statistical"),
}

private enum class Metric(
    val wireName: String,
    val unit: String,
) {
    P95("response_time_p95_ms", "ms"),
    P99("response_time_p99_ms", "ms"),
    THROUGHPUT("throughput_rps", "requests/second"),
    ERROR_RATE("error_rate_ratio", "ratio"),
}

private enum class WindowMetric(
    val wireName: String,
    val unit: String,
) {
    P50("response_time_p50_ms", "ms"),
    P95("response_time_p95_ms", "ms"),
    P99("response_time_p99_ms", "ms"),
    THROUGHPUT("throughput_rps", "requests/second"),
    ERROR_RATE("error_rate_ratio", "ratio"),
}

private fun BigInteger.equalsZero(): Boolean = signum() == 0

private const val SCHEMA_VERSION = "local-baseline.v1"
private const val BASELINE_CONDITION_SCHEMA = "local-baseline-conditions.v1"
private const val BASELINE_CONDITION_PROVENANCE = "EXPLICIT_LOCAL_ACTION"
private const val ALGORITHM = "median-rank-v1"
private const val MIN_CANDIDATES = 3
private const val MAX_CANDIDATES = 20
private const val MAX_SERIES_BYTES = 128
private const val MAX_CONDITION_WINDOW_BYTES = 128
private const val DISPLAY_SCALE = 6
private const val NOISE_Z_SIGMA = 3.68
private const val NOISE_MIN_CELLS = 8
private const val NOISE_MAX_CELLS = 100_000
private const val NOISE_MAX_BLOCK = 14
private const val MAX_HISTOGRAM_TRACKABLE_MS = 86_400_000L
internal val DEFAULT_STAGE_RPS_TOLERANCE = BigDecimal("0.05")
private val HUNDRED = BigInteger.valueOf(100)
private val REFERENCE_FIELDS = setOf("run_id", "analysis_id")
private val SCORE_FIELDS = setOf("reference", "score")
private val SELECTION_FIELDS = setOf("schema_version", "series", "mode", "reference", "algorithm", "candidates", "scores")
private val BASELINE_CONDITION_FIELDS = setOf("schema_version", "baseline", "current", "windows", "decision", "provenance", "updated_at")
private val BASELINE_CONDITION_WINDOW_FIELDS = setOf("baseline_window", "current_window")
private val RATIO_FIELDS = setOf("numerator", "denominator")
private val RESOURCE_BINDING_FIELDS = listOf("series_id", "metric", "unit", "entity", "role", "aggregation")
private val SEMANTIC_FIELDS =
    listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")
private val SHA256 = Regex("[0-9a-f]{64}")
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.UtcLoadCell
import io.ltverdict.metrics.UtcLoadMetrics
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal data class CapacityAnalysis(
    val policyVerdict: PolicyVerdict,
    val capacityJson: JsonObject,
    val evidence: List<JsonObject>,
    val coverageReasons: List<String>,
)

internal fun evaluateCapacity(
    plan: CapacityPlanV1,
    resources: ResourceSnapshotV1,
    utcLoad: UtcLoadMetrics,
    validity: RunValidity,
    windowPolicy: PolicyEvaluation,
    windowMetrics: Map<String, NormalizedMetrics>? = null,
    checkCancelled: () -> Unit = {},
): CapacityAnalysis {
    val summaries = windowPolicy.evidence.filter { it.string("type") == "window_policy_summary" }.associateBy { it.string("window_id") }
    val checks = windowPolicy.evidence.filter { it.string("type") == "resource_policy_check" }
    val businessChecks = windowPolicy.evidence.filter { it.string("type") == "policy_check" }
    val series = resources.series.associateBy(ResourceSeriesV1::id)
    val windows = resources.windows.associateBy(ResourceWindowV1::id)
    val evaluations =
        plan.stages.map { stage ->
            checkCancelled()
            evaluateStage(
                plan,
                stage,
                windows[stage.evaluationWindowId],
                summaries[stage.evaluationWindowId],
                checks,
                businessChecks,
                series,
                resources,
                utcLoad,
                validity,
                checkCancelled,
            )
        }
    val bound = bounds(evaluations, validity)
    val unboundRule = RULE_WINDOW_NOT_FOUND in windowPolicy.coverageReasons
    val stageReasons = evaluations.flatMap(StageEvaluation::reasons) + bound.reasons
    val reasons = if (unboundRule) (stageReasons + RULE_WINDOW_NOT_FOUND).distinct() else stageReasons.distinct()
    val stageVerdict = policyVerdict(plan.requiredCapacity, evaluations, bound)
    val blocked = unboundRule && (stageVerdict == PolicyVerdict.PASS || stageVerdict == PolicyVerdict.FAIL)
    val policyVerdict = if (blocked) PolicyVerdict.NO_VERDICT else stageVerdict
    val capacityJson = capacityJson(plan, evaluations, bound, policyVerdict, reasons)
    val summary =
        buildJsonObject {
            put("id", "capacity-summary")
            put("type", "capacity_summary")
            capacityJson.forEach { (name, value) -> put(name, value) }
        }
    // ADR 0026: a diagnostic evidence item after the summary; it does not touch the bounds, the verdict or capacity_summary.
    val knee = windowMetrics?.let { capacityKnee(plan, evaluations, validity, it) }
    return CapacityAnalysis(policyVerdict, capacityJson, listOfNotNull(summary, knee), reasons)
}

private fun capacityKnee(
    plan: CapacityPlanV1,
    evaluations: List<StageEvaluation>,
    validity: RunValidity,
    windowMetrics: Map<String, NormalizedMetrics>,
): JsonObject {
    val points =
        evaluations.map { evaluation ->
            val metrics = windowMetrics[evaluation.stage.evaluationWindowId]?.overall
            if (evaluation.achieved == null ||
                metrics == null ||
                metrics.sampleCount == 0L ||
                "CAPACITY_INSUFFICIENT_SAMPLES" in evaluation.reasons
            ) {
                null
            } else {
                KneePoint(evaluation.stage.id, evaluation.achieved, metrics.latency.p95Millis)
            }
        }
    val refusal =
        when {
            validity != RunValidity.VALID -> "KNEE_RUN_NOT_VALID"
            points.any { it == null } -> "KNEE_STAGE_DATA_MISSING"
            else -> null
        }
    return capacityKneeEvidence(plan.loadAxis, points.filterNotNull(), refusal)
}

private data class StageEvaluation(
    val stage: CapacityStageV1,
    val achieved: BigDecimal?,
    val observedMin: BigDecimal?,
    val observedMax: BigDecimal?,
    val completeBins: Int,
    val expectedBins: Int,
    val verdict: String,
    val verifiedLoad: BigDecimal?,
    val evidenceRefs: List<String>,
    val reasons: List<String>,
)

private data class Bounds(
    val type: String,
    val lower: BigDecimal?,
    val upper: BigDecimal?,
    val reasons: List<String>,
)

private fun evaluateStage(
    plan: CapacityPlanV1,
    stage: CapacityStageV1,
    window: ResourceWindowV1?,
    summary: JsonObject?,
    checks: List<JsonObject>,
    businessChecks: List<JsonObject>,
    series: Map<String, ResourceSeriesV1>,
    resources: ResourceSnapshotV1,
    utcLoad: UtcLoadMetrics,
    validity: RunValidity,
    checkCancelled: () -> Unit,
): StageEvaluation {
    val stageChecks = checks.filter { it.string("window_id") == stage.evaluationWindowId }
    val evidenceRefs = listOfNotNull(summary?.string("id")) + stageChecks.mapNotNull { it.string("id") }
    val bins =
        when (plan.loadAxis) {
            CapacityLoadAxis.RPS -> rpsBins(window, utcLoad.windows[stage.evaluationWindowId], checkCancelled)
            CapacityLoadAxis.CONCURRENCY,
            CapacityLoadAxis.USERS,
            -> sourceBins(window, resources, series[plan.achievedSeriesId], checkCancelled)
        }
    val values = bins.filterNotNull()
    val achieved = values.takeIf { it.size >= CAPACITY_MINIMUM_BINS }?.let(::p05)
    val reasons = mutableListOf<String>()
    if (validity != RunValidity.VALID) reasons += "CAPACITY_RUN_NOT_VALID"
    if (window == null || summary == null) reasons += "CAPACITY_WINDOW_EVIDENCE_MISSING"
    if (values.size < CAPACITY_MINIMUM_BINS) reasons += "CAPACITY_INSUFFICIENT_COMPLETE_BINS"
    if (window != null &&
        (bins.any { it == null } || values.size != ((window.toEpochMillis - window.fromEpochMillis) / CAPACITY_BIN_MILLIS).toInt())
    ) {
        reasons += "CAPACITY_LOAD_GAPS"
    }
    if (achieved == null && values.size >= CAPACITY_MINIMUM_BINS) reasons += "CAPACITY_ACHIEVED_LOAD_MISSING"

    val applicableSla =
        summary != null &&
            (
                summary.string("business_verdict") != "NO_POLICY" ||
                    stageChecks.any { it.string("effect") == "sla" }
            )
    if (summary != null && !applicableSla) reasons += "CAPACITY_SLA_MISSING"

    if (plan.generatorGuardRuleIds.isEmpty()) reasons += "CAPACITY_GUARD_MISSING"
    plan.generatorGuardRuleIds.forEach { id ->
        checkCancelled()
        when (stageChecks.firstOrNull { it.string("rule_id") == id && it.string("effect") == "diagnostic" }?.string("status")) {
            "PASS" -> Unit
            null -> reasons += "CAPACITY_GUARD_MISSING"
            else -> reasons += "CAPACITY_GUARD_FAILED"
        }
    }
    if (achieved != null && achieved < stage.target.multiply(BigDecimal.ONE.subtract(plan.targetToleranceRatio))) {
        reasons += "CAPACITY_TARGET_MISSED"
    }
    val sampleCount = (summary?.get("sample_count") as? JsonPrimitive)?.longOrNull
    val minSamples = (summary?.get("min_samples") as? JsonPrimitive)?.longOrNull ?: MIN_SAMPLES_DEFAULT
    val smallRule =
        businessChecks.any {
            it["window_id"]?.jsonPrimitive?.content == stage.evaluationWindowId &&
                it["sample_mode"]?.jsonPrimitive?.content in setOf("SMALL_SAMPLE", "INSUFFICIENT")
        }
    if ((sampleCount != null && sampleCount < minSamples) || smallRule) reasons += "CAPACITY_INSUFFICIENT_SAMPLES"
    val policyStatus = summary?.string("verdict")
    if (policyStatus == "NO_VERDICT") reasons += "CAPACITY_SLA_NO_VERDICT"
    val verdict =
        when {
            "CAPACITY_SLA_MISSING" in reasons -> "NO_POLICY"
            reasons.isNotEmpty() -> "INDETERMINATE"
            policyStatus == "PASS" -> "PASS"
            policyStatus == "FAIL" -> "FAIL"
            else -> "INDETERMINATE"
        }
    val verified = achieved?.let { minOf(stage.target, it) }
    return StageEvaluation(
        stage,
        achieved,
        values.minOrNull(),
        values.maxOrNull(),
        values.size,
        window?.let { ((it.toEpochMillis - it.fromEpochMillis) / CAPACITY_BIN_MILLIS).toInt() } ?: 0,
        verdict,
        if (verdict == "PASS" || verdict == "FAIL") verified else null,
        evidenceRefs,
        reasons.distinct(),
    )
}

private fun rpsBins(
    window: ResourceWindowV1?,
    cells: List<UtcLoadCell>?,
    checkCancelled: () -> Unit,
): List<BigDecimal?> {
    if (window == null || cells == null) return emptyList()
    val byStart = cells.groupBy(UtcLoadCell::fromEpochMillis)
    val values = mutableListOf<BigDecimal?>()
    var start = window.fromEpochMillis
    while (start < window.toEpochMillis) {
        checkCancelled()
        val cell = byStart[start]?.singleOrNull()
        values +=
            cell
                ?.takeIf { it.toEpochMillis == start + CAPACITY_BIN_MILLIS && it.sampleCount >= 0 }
                ?.let { BigDecimal.valueOf(it.sampleCount).movePointLeft(1) }
        start += CAPACITY_BIN_MILLIS
    }
    if (cells.any {
            it.fromEpochMillis < window.fromEpochMillis ||
                it.fromEpochMillis >= window.toEpochMillis ||
                (it.fromEpochMillis - window.fromEpochMillis) % CAPACITY_BIN_MILLIS != 0L
        }
    ) {
        values += null
    }
    return values
}

private fun sourceBins(
    window: ResourceWindowV1?,
    resources: ResourceSnapshotV1,
    series: ResourceSeriesV1?,
    checkCancelled: () -> Unit,
): List<BigDecimal?> {
    if (window == null || series == null) return emptyList()
    val values = mutableListOf<BigDecimal?>()
    var binStart = window.fromEpochMillis
    while (binStart < window.toEpochMillis) {
        checkCancelled()
        val binEnd = binStart + CAPACITY_BIN_MILLIS
        var total = BigDecimal.ZERO
        var covered = 0L
        var complete = true
        var index = ((binStart - resources.startEpochMillis) / resources.stepMillis).toInt()
        while (index in series.values.indices) {
            checkCancelled()
            val sourceStart = resources.startEpochMillis + index * resources.stepMillis
            val sourceEnd = sourceStart + resources.stepMillis
            if (sourceStart >= binEnd) break
            val value = series.values[index]
            if (sourceStart < binStart || sourceEnd > binEnd || value == null || value.signum() < 0) {
                complete = false
            } else {
                total = total.add(value.multiply(BigDecimal.valueOf(resources.stepMillis)))
            }
            covered += resources.stepMillis
            index++
        }
        values +=
            if (complete && covered == CAPACITY_BIN_MILLIS) total.divide(BigDecimal.valueOf(CAPACITY_BIN_MILLIS), DECIMAL_CONTEXT) else null
        binStart = binEnd
    }
    return values
}

private fun p05(values: List<BigDecimal>): BigDecimal {
    val ordered = values.sorted()
    if (ordered.size == 1) return ordered.single()
    val h = BigDecimal.valueOf((ordered.size - 1).toLong()).multiply(P05)
    val lower = h.setScale(0, RoundingMode.FLOOR).intValueExact()
    return ordered[lower].add(ordered[lower + 1].subtract(ordered[lower]).multiply(h.subtract(BigDecimal.valueOf(lower.toLong()))))
}

private fun bounds(
    evaluations: List<StageEvaluation>,
    validity: RunValidity,
): Bounds {
    val reasons = mutableListOf<String>()
    if (validity != RunValidity.VALID) reasons += "CAPACITY_RUN_NOT_VALID"
    if (evaluations.isEmpty() ||
        evaluations.any { it.verdict == "INDETERMINATE" || it.verdict == "NO_POLICY" }
    ) {
        reasons += "CAPACITY_STAGE_NOT_VERIFIED"
    }
    if (evaluations.zipWithNext().any { (left, right) -> left.stage.target >= right.stage.target }) {
        reasons +=
            "CAPACITY_NON_MONOTONIC_TARGET"
    }
    if (evaluations.zipWithNext().any { (left, right) ->
            left.verifiedLoad != null &&
                right.verifiedLoad != null &&
                left.verifiedLoad > right.verifiedLoad
        }
    ) {
        reasons += "CAPACITY_NON_MONOTONIC_VERIFIED_LOAD"
    }
    var failed = false
    evaluations.forEach {
        if (it.verdict == "FAIL") failed = true
        if (failed && it.verdict == "PASS") reasons += "CAPACITY_NON_MONOTONIC_OUTCOME"
    }
    if (reasons.isNotEmpty()) return Bounds("INDETERMINATE", null, null, reasons.distinct())
    val firstFail = evaluations.firstOrNull { it.verdict == "FAIL" }
    val lastPass = evaluations.lastOrNull { it.verdict == "PASS" }
    if (firstFail != null && lastPass != null && checkNotNull(lastPass.verifiedLoad) >= checkNotNull(firstFail.verifiedLoad)) {
        return Bounds("INDETERMINATE", null, null, listOf("CAPACITY_NON_MONOTONIC_VERIFIED_LOAD"))
    }
    return Bounds(
        when {
            firstFail != null && lastPass != null -> "BOUNDED"
            firstFail != null -> "UPPER_BOUND"
            else -> "LOWER_BOUND"
        },
        lastPass?.verifiedLoad,
        firstFail?.verifiedLoad,
        emptyList(),
    )
}

private fun policyVerdict(
    required: BigDecimal?,
    evaluations: List<StageEvaluation>,
    bounds: Bounds,
): PolicyVerdict =
    when {
        required == null -> PolicyVerdict.NO_POLICY
        evaluations.any { "CAPACITY_SLA_MISSING" in it.reasons } -> PolicyVerdict.NO_POLICY
        bounds.type == "INDETERMINATE" -> PolicyVerdict.NO_VERDICT
        bounds.lower != null && bounds.lower >= required -> PolicyVerdict.PASS
        bounds.upper != null && bounds.upper <= required -> PolicyVerdict.FAIL
        else -> PolicyVerdict.NO_VERDICT
    }

private fun capacityJson(
    plan: CapacityPlanV1,
    evaluations: List<StageEvaluation>,
    bounds: Bounds,
    policyVerdict: PolicyVerdict,
    reasons: List<String>,
): JsonObject =
    buildJsonObject {
        put("schema_version", "capacity.v1")
        put("load_axis", plan.loadAxis.wireName)
        put("unit", plan.loadAxis.unit)
        put("stages", buildJsonArray { evaluations.forEach { add(stageJson(it, plan.targetToleranceRatio)) } })
        put("bound_type", bounds.type)
        putDecimal("lower_inclusive", bounds.lower)
        putDecimal("upper_exclusive", bounds.upper)
        put("policy_verdict", policyVerdict.name)
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("capacity_knee", JsonNull)
        put("knee_reason", "KNEE_DETECTOR_NOT_IMPLEMENTED")
    }

private fun stageJson(
    evaluation: StageEvaluation,
    tolerance: BigDecimal,
): JsonObject =
    buildJsonObject {
        put("id", evaluation.stage.id)
        put("target", JsonPrimitive(evaluation.stage.target))
        putDecimal("achieved", evaluation.achieved)
        put("achieved_statistic", "p05_10s")
        putDecimal("observed_min", evaluation.observedMin)
        putDecimal("observed_max", evaluation.observedMax)
        put("complete_bins", evaluation.completeBins)
        put("expected_bins", evaluation.expectedBins)
        put("target_tolerance_ratio", JsonPrimitive(tolerance))
        putDecimal("verified_bound_load", evaluation.verifiedLoad)
        put("verdict", evaluation.verdict)
        put("reasons", buildJsonArray { evaluation.reasons.forEach { add(JsonPrimitive(it)) } })
        put("evidence_refs", buildJsonArray { evaluation.evidenceRefs.forEach { add(JsonPrimitive(it)) } })
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putDecimal(
    name: String,
    value: BigDecimal?,
) {
    put(name, value?.let { JsonPrimitive(BigDecimal(canonicalDecimal(it))) } ?: JsonNull)
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.content

private const val CAPACITY_BIN_MILLIS = 10_000L
private const val CAPACITY_MINIMUM_BINS = 30
private const val RULE_WINDOW_NOT_FOUND = "RULE_WINDOW_NOT_FOUND"
private val DECIMAL_CONTEXT = MathContext.DECIMAL128
private val P05 = BigDecimal("0.05")

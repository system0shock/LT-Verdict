package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.json.JsonObject

internal fun evaluateSharedWindowPolicy(
    policy: PolicyV1?,
    validity: RunValidity,
    globalMetrics: NormalizedMetrics,
    windowMetrics: Map<String, NormalizedMetrics>,
    resource: ResourceEvaluation,
    windows: List<ResourceWindowV1>,
    diagnostics: List<Diagnostic> = emptyList(),
): PolicyEvaluation {
    val base = evaluatePolicy(null, validity, globalMetrics, diagnostics)
    val findings = (base.findings + resource.findings).toMutableList()
    val evidence = (base.evidence + resource.evidence).toMutableList()
    val reasons = (base.coverageReasons + resource.coverageReasons).toMutableList()
    val windowVerdicts = mutableListOf<PolicyVerdict>()

    windows.forEach { window ->
        val metrics = requireNotNull(windowMetrics[window.id]) { "MISSING_WINDOW_METRICS" }
        var business = evaluatePolicy(policy, validity, metrics, windowId = window.id, includeMetricEvidence = false)
        val applies = policy != null && policy.rules.any { it.windowIds == null || window.id in it.windowIds }
        if (applies && validity == RunValidity.VALID && metrics.overall.sampleCount == 0L) {
            business =
                business.copy(
                    verdict = PolicyVerdict.NO_VERDICT,
                    coverageReasons = (business.coverageReasons + BUSINESS_OBSERVATIONS_NOT_FOUND).distinct(),
                )
        }
        val resourceVerdict = resource.windowVerdicts.getValue(window.id)
        val verdict = jointVerdict(business.verdict, resourceVerdict)
        findings += business.findings
        evidence += business.evidence
        evidence +=
            windowPolicySummary(
                window,
                business.verdict,
                resourceVerdict,
                verdict,
                metrics.overall.sampleCount,
                policy?.defaults?.minSamples,
            )
        reasons += business.coverageReasons
        windowVerdicts += verdict
    }
    val known = windows.map(ResourceWindowV1::id).toSet()
    if (validity == RunValidity.VALID) {
        val declared = policy?.rules.orEmpty().map { it.id to it.windowIds } + policy?.platformRules.orEmpty().map { it.id to it.windowIds }
        for ((ruleId, windowIds) in declared) {
            for (windowId in windowIds.orEmpty()) {
                if (windowId in known) continue
                evidence += ruleWindowCheck(ruleId, windowId)
                reasons += RULE_WINDOW_NOT_FOUND
                windowVerdicts += PolicyVerdict.NO_VERDICT
            }
        }
    }
    return PolicyEvaluation(overallVerdict(windowVerdicts), reasons.distinct(), findings, evidence)
}

internal fun ruleWindowCheck(
    ruleId: String,
    windowId: String,
): JsonObject =
    RuleWindowCheckEvidence(
        id = "rule-window-check-${sha256Hex("$ruleId\u0000$windowId".encodeToByteArray())}",
        ruleId = ruleId,
        windowId = windowId,
        status = "NO_VERDICT",
        reasonCode = RULE_WINDOW_NOT_FOUND,
    ).toJson()

private fun jointVerdict(
    business: PolicyVerdict,
    resource: PolicyVerdict,
): PolicyVerdict =
    when {
        business == PolicyVerdict.NO_VERDICT || resource == PolicyVerdict.NO_VERDICT -> PolicyVerdict.NO_VERDICT
        business == PolicyVerdict.NO_POLICY && resource == PolicyVerdict.NO_POLICY -> PolicyVerdict.NO_POLICY
        business == PolicyVerdict.FAIL || resource == PolicyVerdict.FAIL -> PolicyVerdict.FAIL
        else -> PolicyVerdict.PASS
    }

private fun overallVerdict(windows: List<PolicyVerdict>): PolicyVerdict =
    when {
        windows.any { it == PolicyVerdict.NO_VERDICT } -> PolicyVerdict.NO_VERDICT
        windows.all { it == PolicyVerdict.NO_POLICY } -> PolicyVerdict.NO_POLICY
        windows.any { it == PolicyVerdict.FAIL } -> PolicyVerdict.FAIL
        else -> PolicyVerdict.PASS
    }

internal fun windowPolicySummary(
    window: ResourceWindowV1,
    business: PolicyVerdict,
    resource: PolicyVerdict,
    verdict: PolicyVerdict,
    sampleCount: Long,
    minSamples: Long?,
): JsonObject =
    WindowPolicySummaryEvidence(
        id = "window-policy-summary-${sha256Hex(window.id.encodeToByteArray())}",
        windowId = window.id,
        fromEpochMs = window.fromEpochMillis,
        toEpochMs = window.toEpochMillis,
        businessVerdict = business,
        resourceVerdict = resource,
        verdict = verdict,
        sampleCount = sampleCount,
        minSamples = minSamples,
    ).toJson()

private const val BUSINESS_OBSERVATIONS_NOT_FOUND = "BUSINESS_OBSERVATIONS_NOT_FOUND"
private const val RULE_WINDOW_NOT_FOUND = "RULE_WINDOW_NOT_FOUND"

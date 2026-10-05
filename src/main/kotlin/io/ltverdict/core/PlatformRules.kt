package io.ltverdict.core

internal fun resolveServices(
    scope: PlatformScope,
    catalog: List<String>?,
): List<String> =
    when (scope) {
        is PlatformScope.Services -> scope.names
        is PlatformScope.AllServices -> catalog.orEmpty().filter { it !in scope.except }
    }

internal data class PlatformExpansion(
    val rules: List<ResourceRuleV1>,
    val bindingFailures: Map<String, String>,
) {
    companion object {
        val EMPTY = PlatformExpansion(emptyList(), emptyMap())
    }
}

internal fun expandPlatformRules(
    policy: PolicyV1,
    snapshot: ResourceSnapshotV1,
): PlatformExpansion {
    if (policy.platformRules.isEmpty()) return PlatformExpansion.EMPTY
    val rules = mutableListOf<ResourceRuleV1>()
    val failures = linkedMapOf<String, String>()
    policy.platformRules.forEach { rule ->
        resolveServices(rule.scope, policy.platformServices).forEach { service ->
            val id = "${rule.id}/$service"
            val candidates = snapshot.series.filter { it.metric == rule.signal && it.entity == service && it.role == ResourceRole.SYSTEM }
            val series = candidates.singleOrNull()
            val failure =
                when {
                    candidates.isEmpty() -> "RESOURCE_SERIES_NOT_FOUND"
                    series == null -> "PLATFORM_SERIES_AMBIGUOUS"
                    series.unit != rule.unit -> "PLATFORM_UNIT_MISMATCH"
                    series.aggregation != rule.aggregation -> "PLATFORM_AGGREGATION_MISMATCH"
                    else -> null
                }
            rules += expandedRule(id, series?.id.orEmpty(), rule, service)
            failure?.let { failures[id] = it }
        }
        (rule.scope as? PlatformScope.AllServices)?.let { scope ->
            val known = policy.platformServices.orEmpty().toSet() + scope.except
            snapshot.series
                .filter { it.metric == rule.signal && it.role == ResourceRole.SYSTEM && it.entity !in known }
                .map(ResourceSeriesV1::entity)
                .distinct()
                .forEach { entity ->
                    val id = "${rule.id}/$entity"
                    rules += expandedRule(id, "", rule, entity)
                    failures[id] = "PLATFORM_SERVICE_NOT_IN_CATALOG"
                }
        }
    }
    return PlatformExpansion(rules, failures)
}

private fun expandedRule(
    id: String,
    seriesId: String,
    rule: PlatformRuleV1,
    service: String,
) = ResourceRuleV1(
    id,
    seriesId,
    rule.unit,
    rule.operator,
    rule.threshold,
    rule.minConsecutiveCells,
    rule.effect,
    rule.windowIds,
    PlatformRuleRef(rule.id, service),
)

internal fun validatePlatformBinding(
    policy: PolicyV1,
    snapshot: ResourceSnapshotV1,
): List<PolicyValidationError> {
    if (policy.platformRules.isEmpty()) return emptyList()
    val errors = mutableListOf<PolicyValidationError>()
    if (snapshot.rules.any { it.effect == ResourceRuleEffect.SLA }) {
        errors +=
            PolicyValidationError(
                "PLATFORM_RULES_CONFLICT",
                "/platform_rules",
                "SLA thresholds come from the policy or from the snapshot, not from both",
            )
    }
    val expanded = expandPlatformRules(policy, snapshot).rules
    val taken = snapshot.rules.map(ResourceRuleV1::id).toSet()
    if (expanded.any { it.id in taken }) {
        errors += PolicyValidationError("DUPLICATE_RULE_ID", "/platform_rules", "an expanded platform rule id equals a snapshot rule id")
    }
    if (expanded.size + snapshot.rules.size > MAX_RESOURCE_RULES) {
        errors +=
            PolicyValidationError(
                "RESOURCE_LIMIT_EXCEEDED",
                "/platform_rules",
                "expanded platform checks and snapshot rules exceed $MAX_RESOURCE_RULES",
            )
    }
    return errors
}

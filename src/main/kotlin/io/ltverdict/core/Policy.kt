package io.ltverdict.core

import io.ltverdict.ingest.Diagnostic
import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import io.ltverdict.metrics.TransactionIdentity
import io.ltverdict.metrics.TransactionSummary
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

private const val MAX_POLICY_DEPTH = 16
private const val MAX_POLICY_RULES = 256
private const val MAX_IDENTIFIER_BYTES = 128
private const val MAX_TRANSACTION_SCOPE_BYTES = 4_096
private const val MAX_NUMERIC_TOKEN_BYTES = 64
private const val MAX_ABSOLUTE_EXPONENT = 64
internal const val MIN_SAMPLES_FLOOR = 20L
internal const val MIN_SAMPLES_DEFAULT = 100L
private const val MAX_SAMPLES_BOUND = 1_000_000L

internal enum class PolicyVerdict {
    PASS,
    FAIL,
    NO_POLICY,
    NO_VERDICT,
}

internal data class PolicyEvaluation(
    val verdict: PolicyVerdict,
    val coverageReasons: List<String>,
    val findings: List<JsonObject>,
    val evidence: List<JsonObject>,
)

internal fun validatePolicy(
    source: InputStream,
    maxBytes: Int = 1_048_576,
): PolicyValidation =
    try {
        require(maxBytes >= 0)
        val bytes = readBounded(source, maxBytes)
        val text = decodeUtf8(bytes)
        StrictJsonScanner(
            text,
            MAX_POLICY_DEPTH,
            MAX_NUMERIC_TOKEN_BYTES,
            MAX_ABSOLUTE_EXPONENT,
            "policy",
            ::fail,
        ).scan()
        val element = Json.parseToJsonElement(text)
        val policy = parsePolicy(element)
        val canonical = canonicalJson(element)
        PolicyValidation.Valid(policy, canonical, sha256Hex(canonical))
    } catch (failure: PolicyFailure) {
        PolicyValidation.Invalid(listOf(failure.error))
    } catch (_: IOException) {
        invalid("POLICY_READ_ERROR", "", "policy could not be read")
    } catch (_: SerializationException) {
        invalid("MALFORMED_JSON", "", "policy is not valid JSON")
    } catch (_: IllegalArgumentException) {
        invalid("MALFORMED_JSON", "", "policy is not valid JSON")
    }

internal fun evaluatePolicy(
    policy: PolicyV1?,
    validity: RunValidity,
    metrics: NormalizedMetrics?,
    diagnostics: List<Diagnostic> = emptyList(),
    windowId: String? = null,
    includeMetricEvidence: Boolean = true,
): PolicyEvaluation {
    val orderedDiagnostics = diagnostics.sortedWith(compareBy<Diagnostic> { it.code }.thenBy { it.sourceOffset })
    val findings = orderedDiagnostics.map(::diagnosticFinding).toMutableList()
    val metricEvidence = metrics?.let { metricEvidence(it, windowId) }.orEmpty()
    val evidence = if (includeMetricEvidence) metricEvidence.map(MetricEvidence::json).toMutableList() else mutableListOf()
    evidence += orderedDiagnostics.map(::diagnosticEvidence)
    val reasons = orderedDiagnostics.map(Diagnostic::code).toMutableList()

    if (validity != RunValidity.VALID) {
        return PolicyEvaluation(PolicyVerdict.NO_VERDICT, reasons.distinct(), findings, evidence)
    }
    if (policy == null) return PolicyEvaluation(PolicyVerdict.NO_POLICY, reasons.distinct(), findings, evidence)

    val checks = mutableListOf<JsonObject>()
    val informational = mutableListOf<String>()
    var failed = false
    if (windowId == null && policy.platformRules.isNotEmpty()) {
        if (policy.platformRules.any { it.effect == ResourceRuleEffect.SLA }) {
            reasons += REASON_RESOURCE_SNAPSHOT_REQUIRED
        } else {
            informational += REASON_RESOURCE_SNAPSHOT_REQUIRED
        }
    }
    val applicable = if (windowId == null) policy.rules else policy.rules.filter { it.windowIds == null || windowId in it.windowIds }
    if (applicable.isEmpty()) return PolicyEvaluation(PolicyVerdict.NO_POLICY, reasons.distinct(), findings, evidence)
    applicable.forEach { rule ->
        if (windowId == null && rule.windowIds != null) {
            reasons += REASON_RULE_WINDOW_NOT_FOUND
            checks += policyCheck(rule, null, null, REASON_RULE_WINDOW_NOT_FOUND, null, includeMetricEvidence, null)
            return@forEach
        }
        val binding = bind(rule, metrics, metricEvidence)
        if (binding.reason != null) {
            reasons += binding.reason
            checks += policyCheck(rule, null, null, binding.reason, windowId, includeMetricEvidence, null)
            return@forEach
        }
        val metric = binding.metric ?: error("metric binding is incomplete")
        val gate = sampleGate(policy, rule, metric.summary.sampleCount)
        if (gate.mode == SampleMode.INSUFFICIENT) {
            reasons += REASON_INSUFFICIENT_SAMPLES
            checks += policyCheck(rule, metric, null, REASON_INSUFFICIENT_SAMPLES, windowId, includeMetricEvidence, gate)
            return@forEach
        }
        val observed = observed(rule, metric.summary)
        if (observed == null) {
            reasons += METRIC_NOT_AVAILABLE
            checks += policyCheck(rule, metric, null, METRIC_NOT_AVAILABLE, windowId, includeMetricEvidence, gate)
            return@forEach
        }
        val passed =
            when (rule.operator) {
                PolicyOperator.LTE -> observed.comparison <= 0
                PolicyOperator.GTE -> observed.comparison >= 0
            }
        if (gate.mode == SampleMode.SMALL_SAMPLE) informational += REASON_SMALL_SAMPLE
        checks += policyCheck(rule, metric, observed.json, if (passed) null else POLICY_FAILED, windowId, includeMetricEvidence, gate)
        if (!passed) {
            failed = true
            findings +=
                policyFailure(
                    rule,
                    checks
                        .last()
                        .getValue("id")
                        .jsonPrimitive.content,
                    windowId,
                )
        }
    }
    evidence += checks
    val verdict =
        when {
            reasons.isNotEmpty() -> PolicyVerdict.NO_VERDICT
            failed -> PolicyVerdict.FAIL
            else -> PolicyVerdict.PASS
        }
    return PolicyEvaluation(verdict, (reasons + informational).distinct(), findings, evidence)
}

private enum class SampleMode { FULL, SMALL_SAMPLE, INSUFFICIENT, NOT_GATED }

private data class SampleGate(
    val mode: SampleMode?,
    val sampleCount: Long,
    val floor: Long,
    val minSamples: Long,
)

private fun sampleGate(
    policy: PolicyV1,
    rule: PolicyRuleV1,
    sampleCount: Long,
): SampleGate {
    val floor = policy.defaults?.sampleFloor ?: MIN_SAMPLES_FLOOR
    val minimum = rule.minSamples ?: policy.defaults?.minSamples ?: MIN_SAMPLES_DEFAULT
    val mode =
        when {
            sampleCount == 0L -> null
            rule.metric == PolicyMetric.THROUGHPUT_RPS -> SampleMode.NOT_GATED
            sampleCount < floor -> SampleMode.INSUFFICIENT
            sampleCount < minimum -> SampleMode.SMALL_SAMPLE
            else -> SampleMode.FULL
        }
    return SampleGate(mode, sampleCount, floor, minimum)
}

private data class MetricEvidence(
    val identity: TransactionIdentity?,
    val summary: MetricSummary,
    val id: String,
    val json: JsonObject,
)

private data class Binding(
    val metric: MetricEvidence? = null,
    val reason: String? = null,
)

private data class Observed(
    val json: JsonElement,
    val comparison: Int,
)

private fun metricEvidence(
    metrics: NormalizedMetrics,
    windowId: String?,
): List<MetricEvidence> {
    val overallId = windowId?.let { stableId("metric-summary-window", "$it\u0000overall") } ?: "metric-summary-overall"
    val overall = MetricEvidence(null, metrics.overall, overallId, metricSummary(overallId, null, metrics.overall, windowId))
    val transactions =
        metrics.transactions
            .sortedWith(TRANSACTION_SUMMARY_COMPARATOR)
            .map { transaction ->
                val key = transaction.identity.stableKey()
                val id =
                    if (windowId == null) {
                        stableId("metric-summary", key)
                    } else {
                        stableId("metric-summary-window", "$windowId\u0000$key")
                    }
                MetricEvidence(
                    transaction.identity,
                    transaction.metrics,
                    id,
                    metricSummary(id, transaction.identity, transaction.metrics, windowId),
                )
            }
    return listOf(overall) + transactions
}

private fun bind(
    rule: PolicyRuleV1,
    metrics: NormalizedMetrics?,
    evidence: List<MetricEvidence>,
): Binding {
    if (metrics == null) return Binding(reason = METRIC_NOT_AVAILABLE)
    return when (val scope = rule.scope) {
        PolicyScope.Overall -> Binding(evidence.first())
        is PolicyScope.Transaction -> {
            val matches = evidence.drop(1).filter { it.identity?.label == scope.name }.distinctBy { it.identity }
            when (matches.size) {
                0 -> Binding(reason = "TRANSACTION_NOT_FOUND")
                1 -> Binding(matches.single())
                else -> Binding(reason = "AMBIGUOUS_TRANSACTION")
            }
        }
    }
}

private fun observed(
    rule: PolicyRuleV1,
    summary: MetricSummary,
): Observed? {
    if (summary.sampleCount == 0L) return null
    return when (rule.metric) {
        PolicyMetric.RESPONSE_TIME_P95_MS -> integerObserved(summary.latency.p95Millis, rule.threshold)
        PolicyMetric.RESPONSE_TIME_P99_MS -> integerObserved(summary.latency.p99Millis, rule.threshold)
        PolicyMetric.ERROR_RATE_RATIO -> summary.errorRate?.ratioObserved(rule.threshold)
        PolicyMetric.THROUGHPUT_RPS -> summary.throughputRps.ratioObserved(rule.threshold)
    }
}

private fun integerObserved(
    value: Long,
    threshold: BigDecimal,
) = Observed(JsonPrimitive(value), BigDecimal.valueOf(value).compareTo(threshold))

private fun ExactRatio.ratioObserved(threshold: BigDecimal) =
    Observed(
        ratioJson(this),
        compareTo(threshold),
    )

private fun policyCheck(
    rule: PolicyRuleV1,
    metric: MetricEvidence?,
    observed: JsonElement?,
    reason: String?,
    windowId: String?,
    includeMetricReference: Boolean,
    gate: SampleGate?,
): JsonObject =
    buildJsonObject {
        put("id", windowId?.let { stableId("policy-check-window", "$it\u0000${rule.id}") } ?: stableId("policy-check", rule.id))
        put("type", "policy_check")
        windowId?.let {
            put("window_id", it)
            put("scope", metric?.json?.getValue("scope") ?: rule.scope.json())
        }
        put("rule_id", rule.id)
        put("metric", rule.metric.wireName)
        put("operator", rule.operator.wireName)
        put("threshold", JsonPrimitive(rule.threshold))
        put(
            "status",
            when {
                reason == POLICY_FAILED -> "FAIL"
                observed != null -> "PASS"
                else -> "NO_VERDICT"
            },
        )
        if (metric != null && includeMetricReference) put("metric_evidence_id", metric.id)
        if (observed != null) put("observed", observed)
        if (reason != null && reason != POLICY_FAILED) put("reason_code", reason)
        gate?.mode?.let { mode ->
            put("sample_count", gate.sampleCount)
            if (mode != SampleMode.NOT_GATED) {
                put("sample_floor", gate.floor)
                put("min_samples", gate.minSamples)
            }
            put("sample_mode", mode.name)
        }
    }

private fun PolicyScope.json(): JsonObject =
    buildJsonObject {
        when (this@json) {
            PolicyScope.Overall -> put("kind", "overall")
            is PolicyScope.Transaction -> {
                put("kind", "transaction")
                put("label", name)
            }
        }
    }

private fun metricSummary(
    id: String,
    identity: TransactionIdentity?,
    summary: MetricSummary,
    windowId: String?,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "metric_summary")
        windowId?.let { put("window_id", it) }
        put(
            "scope",
            if (identity == null) {
                buildJsonObject { put("kind", "overall") }
            } else {
                buildJsonObject {
                    put("kind", "transaction")
                    put("group_path", buildJsonArray { identity.groupPath.forEach { add(JsonPrimitive(it)) } })
                    put("label", identity.label)
                    put("sample_kind", identity.kind.name)
                }
            },
        )
        put("sample_count", summary.sampleCount)
        put("error_count", summary.errorCount)
        put("error_rate_ratio", summary.errorRate?.let(::ratioJson) ?: JsonNull)
        put("throughput_rps", ratioJson(summary.throughputRps))
        put(
            "latency_ms",
            buildJsonObject {
                put("p50", summary.latency.p50Millis)
                put("p95", summary.latency.p95Millis)
                put("p99", summary.latency.p99Millis)
                put("max", summary.latency.maxMillis)
            },
        )
    }

private fun ratioJson(value: ExactRatio): JsonObject =
    buildJsonObject {
        put("numerator", value.numerator)
        put("denominator", value.denominator)
    }

private fun diagnosticEvidence(diagnostic: Diagnostic): JsonObject {
    val id = diagnosticId(diagnostic)
    return buildJsonObject {
        put("id", id)
        put("type", "diagnostic")
        put("code", diagnostic.code)
        put("message", diagnostic.message)
        diagnostic.sourceOffset?.let { put("source_offset", it) }
    }
}

private fun diagnosticFinding(diagnostic: Diagnostic): JsonObject =
    buildJsonObject {
        put("id", stableId("diagnostic-finding", diagnosticKey(diagnostic)))
        put("type", "diagnostic")
        put("code", diagnostic.code)
        put("evidence_id", diagnosticId(diagnostic))
    }

private fun policyFailure(
    rule: PolicyRuleV1,
    evidenceId: String,
    windowId: String?,
): JsonObject =
    buildJsonObject {
        put("id", windowId?.let { stableId("policy-failure-window", "$it\u0000${rule.id}") } ?: stableId("policy-failure", rule.id))
        put("type", "policy_failure")
        windowId?.let { put("window_id", it) }
        put("rule_id", rule.id)
        put("evidence_id", evidenceId)
    }

private fun diagnosticId(diagnostic: Diagnostic) = stableId("diagnostic", diagnosticKey(diagnostic))

private fun diagnosticKey(diagnostic: Diagnostic) = "${diagnostic.code}\u0000${diagnostic.sourceOffset ?: ""}"

private fun stableId(
    prefix: String,
    key: String,
) = "$prefix-${sha256Hex(key.encodeToByteArray())}"

private fun TransactionIdentity.stableKey(): String =
    canonicalJson(
        buildJsonObject {
            put("group_path", buildJsonArray { groupPath.forEach { add(JsonPrimitive(it)) } })
            put("label", label)
            put("kind", kind.name)
        },
    ).decodeToString()

private fun compareTransactions(
    left: TransactionSummary,
    right: TransactionSummary,
): Int {
    val leftPath = left.identity.groupPath
    val rightPath = right.identity.groupPath
    for (index in 0 until minOf(leftPath.size, rightPath.size)) {
        val comparison = leftPath[index].compareTo(rightPath[index])
        if (comparison != 0) return comparison
    }
    val pathComparison = leftPath.size.compareTo(rightPath.size)
    if (pathComparison != 0) return pathComparison
    val labelComparison = left.identity.label.compareTo(right.identity.label)
    return if (labelComparison != 0) {
        labelComparison
    } else {
        left.identity.kind.name
            .compareTo(right.identity.kind.name)
    }
}

private val TRANSACTION_SUMMARY_COMPARATOR = Comparator(::compareTransactions)
private const val METRIC_NOT_AVAILABLE = "METRIC_NOT_AVAILABLE"
private const val POLICY_FAILED = "POLICY_FAILED"
private const val REASON_INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES"
private const val REASON_SMALL_SAMPLE = "SMALL_SAMPLE"
private const val REASON_RULE_WINDOW_NOT_FOUND = "RULE_WINDOW_NOT_FOUND"
private const val REASON_RESOURCE_SNAPSHOT_REQUIRED = "RESOURCE_SNAPSHOT_REQUIRED"

private fun readBounded(
    source: InputStream,
    maxBytes: Int,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    val limit = maxBytes.toLong()
    while (true) {
        val remainingProbe = limit + 1L - total
        if (remainingProbe <= 0L) fail("RESOURCE_LIMIT_EXCEEDED", "", "policy exceeds $maxBytes bytes")
        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remainingProbe).toInt())
        if (count == -1) break
        total += count
        if (total > limit) fail("RESOURCE_LIMIT_EXCEEDED", "", "policy exceeds $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun decodeUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        fail("INVALID_UTF8", "", "policy must be valid UTF-8")
    }

private fun parsePolicy(element: JsonElement): PolicyV1 {
    val root = element.objectAt("")
    root.rejectUnknown(
        setOf("schema_version", "policy_id", "rules", "defaults", "platform_services", "platform_rules", "platform_coverage"),
        "",
    )
    val schemaVersion = root.stringAt("schema_version", "")
    if (schemaVersion != "policy.v1") fail("INVALID_SCHEMA_VERSION", "/schema_version", "expected policy.v1")
    val policyId = root.stringAt("policy_id", "")
    validateIdentifier(policyId, "/policy_id")
    val defaults = root["defaults"]?.let { parseDefaults(it, "/defaults") }
    val effectiveFloor = defaults?.sampleFloor ?: MIN_SAMPLES_FLOOR
    defaults?.let { checkDefaultsOrder(it, effectiveFloor) }
    val rulesElement = root.required("rules", "")
    val rulesArray = rulesElement as? JsonArray ?: fail("INVALID_TYPE", "/rules", "rules must be an array")
    if (rulesArray.isEmpty()) fail("EMPTY_RULES", "/rules", "at least one rule is required")
    if (rulesArray.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/rules", "too many rules")

    val ids = HashSet<String>()
    val rules =
        rulesArray.mapIndexed { index, value ->
            val pointer = "/rules/$index"
            val rule = value.objectAt(pointer)
            rule.rejectUnknown(setOf("id", "metric", "operator", "threshold", "scope", "min_samples", "window_ids"), pointer)
            val id = rule.stringAt("id", pointer)
            validateIdentifier(id, "$pointer/id")
            if (!ids.add(id)) fail("DUPLICATE_RULE_ID", "$pointer/id", "rule id must be unique")

            val metricName = rule.stringAt("metric", pointer)
            val metric =
                PolicyMetric.entries.find { it.wireName == metricName }
                    ?: fail("UNKNOWN_METRIC", "$pointer/metric", "unknown metric")
            val operatorName = rule.stringAt("operator", pointer)
            val operator =
                PolicyOperator.entries.find { it.wireName == operatorName }
                    ?: fail("UNKNOWN_OPERATOR", "$pointer/operator", "unknown operator")
            val expectedOperator = if (metric == PolicyMetric.THROUGHPUT_RPS) PolicyOperator.GTE else PolicyOperator.LTE
            if (operator != expectedOperator) {
                fail("METRIC_OPERATOR_MISMATCH", "$pointer/operator", "operator is not valid for metric")
            }
            val threshold = rule.numberAt("threshold", pointer)
            if (threshold.signum() < 0 || (metric == PolicyMetric.ERROR_RATE_RATIO && threshold > BigDecimal.ONE)) {
                fail("THRESHOLD_OUT_OF_RANGE", "$pointer/threshold", "threshold is outside the metric range")
            }
            val scope = parseScope(rule.required("scope", pointer), "$pointer/scope")
            val minSamples = rule.longInRangeAt("min_samples", pointer)
            if (minSamples != null) {
                if (metric == PolicyMetric.THROUGHPUT_RPS) {
                    fail("FIELD_NOT_APPLICABLE", "$pointer/min_samples", "min_samples does not apply to throughput_rps")
                }
                if (minSamples < effectiveFloor) {
                    fail("MIN_SAMPLES_BELOW_FLOOR", "$pointer/min_samples", "min_samples is below the effective sample floor")
                }
            }
            val windowIds = rule.windowIdsAt(pointer)
            PolicyRuleV1(id, metric, operator, threshold, scope, minSamples, windowIds)
        }
    val catalog = root["platform_services"]?.let { parseNames(it, "/platform_services") }
    val platformRules = root["platform_rules"]?.let { parsePlatformRules(it, catalog, ids) }.orEmpty()
    val coverage = parsePlatformCoverage(root["platform_coverage"], platformRules, catalog)
    return PolicyV1(schemaVersion, policyId, rules, defaults, catalog, platformRules, coverage)
}

private const val MAX_PLATFORM_NAMES = 64

private fun parseNames(
    element: JsonElement,
    pointer: String,
): List<String> {
    val array = element as? JsonArray ?: fail("INVALID_TYPE", pointer, "expected an array of names")
    if (array.isEmpty() || array.size > MAX_PLATFORM_NAMES) fail("INVALID_SCOPE", pointer, "expected 1..$MAX_PLATFORM_NAMES names")
    val names =
        array.mapIndexed { index, value ->
            val name =
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: fail("INVALID_TYPE", pointer.child("$index"), "name must be a string")
            validateIdentifier(name, pointer.child("$index"))
            name
        }
    if (names.toSet().size != names.size) fail("INVALID_SCOPE", pointer, "names must be unique")
    return names
}

private fun parsePlatformScope(
    element: JsonElement,
    pointer: String,
    catalog: List<String>?,
): PlatformScope {
    val scope = element.objectAt(pointer)
    return when (scope.stringAt("kind", pointer)) {
        "service" -> {
            scope.rejectUnknown(setOf("kind", "services"), pointer)
            PlatformScope.Services(parseNames(scope.required("services", pointer), "$pointer/services"))
        }

        "all_services" -> {
            scope.rejectUnknown(setOf("kind", "except"), pointer)
            if (catalog == null) fail("INVALID_SCOPE", pointer, "all_services requires platform_services")
            val except =
                scope["except"]
                    ?.let { raw -> if (raw is JsonArray && raw.isEmpty()) emptyList() else parseNames(raw, "$pointer/except") }
                    .orEmpty()
            except.forEachIndexed { index, name ->
                if (name !in catalog) fail("INVALID_SCOPE", "$pointer/except/$index", "except must name a platform service")
            }
            val resolved = PlatformScope.AllServices(except)
            if (resolveServices(resolved, catalog).isEmpty()) fail("INVALID_SCOPE", pointer, "scope is empty after except")
            resolved
        }

        else -> fail("INVALID_SCOPE", "$pointer/kind", "unknown scope kind")
    }
}

private fun parsePlatformRules(
    element: JsonElement,
    catalog: List<String>?,
    ids: MutableSet<String>,
): List<PlatformRuleV1> {
    val array = element as? JsonArray ?: fail("INVALID_TYPE", "/platform_rules", "platform_rules must be an array")
    if (array.isEmpty()) fail("EMPTY_RULES", "/platform_rules", "at least one platform rule is required")
    if (array.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules", "too many platform rules")
    val businessIds = ids.toSet()
    val rules =
        array.mapIndexed { index, value ->
            val pointer = "/platform_rules/$index"
            val item = value.objectAt(pointer)
            item.rejectUnknown(
                setOf("id", "signal", "scope", "operator", "threshold", "unit", "aggregation", "min_consecutive_cells", "effect", "window_ids"),
                pointer,
            )
            val id = item.stringAt("id", pointer)
            validateIdentifier(id, "$pointer/id")
            if (!ids.add(id)) fail("DUPLICATE_RULE_ID", "$pointer/id", "rule id must be unique")
            val signal = item.stringAt("signal", pointer)
            validateIdentifier(signal, "$pointer/signal")
            val scope = parsePlatformScope(item.required("scope", pointer), "$pointer/scope", catalog)
            val operatorName = item.stringAt("operator", pointer)
            val operator =
                ResourceOperator.entries.find { it.wireName == operatorName }
                    ?: fail("UNKNOWN_OPERATOR", "$pointer/operator", "unknown operator")
            val threshold = item.numberAt("threshold", pointer)
            val unit = item.stringAt("unit", pointer)
            validateIdentifier(unit, "$pointer/unit")
            val aggregationName = item.stringAt("aggregation", pointer)
            val aggregation =
                ResourceAggregation.entries.find { it.wireName == aggregationName }
                    ?: fail("UNKNOWN_AGGREGATION", "$pointer/aggregation", "unknown aggregation")
            if (operator == ResourceOperator.GT && threshold.signum() == 0 && aggregation == ResourceAggregation.INTERVAL_MIN) {
                fail("PLATFORM_AGGREGATION_OPERATOR_MISMATCH", "$pointer/aggregation", "gt 0 must not use interval_min")
            }
            val minimum =
                item.longInRangeAt("min_consecutive_cells", pointer, MAX_POINTS_PER_SERIES.toLong(), "INVALID_MINIMUM")
                    ?: fail("MISSING_FIELD", "$pointer/min_consecutive_cells", "required field is missing")
            val effectName = item.stringAt("effect", pointer)
            val effect =
                ResourceRuleEffect.entries.find { it.wireName == effectName }
                    ?: fail("UNKNOWN_EFFECT", "$pointer/effect", "unknown effect")
            PlatformRuleV1(id, signal, scope, operator, threshold, unit, aggregation, minimum.toInt(), effect, item.windowIdsAt(pointer))
        }
    val expanded = HashSet<String>()
    rules.forEachIndexed { index, rule ->
        resolveServices(rule.scope, catalog).forEach { service ->
            val expandedId = "${rule.id}/$service"
            if (expandedId.encodeToByteArray().size > MAX_IDENTIFIER_BYTES) {
                fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules/$index/id", "expanded rule id exceeds 128 UTF-8 bytes")
            }
            if (expandedId in businessIds || !expanded.add(expandedId)) {
                fail("DUPLICATE_RULE_ID", "/platform_rules/$index/id", "expanded rule id collides")
            }
        }
    }
    if (expanded.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules", "too many expanded platform checks")
    return rules
}

private fun parsePlatformCoverage(
    element: JsonElement?,
    rules: List<PlatformRuleV1>,
    catalog: List<String>?,
): PlatformCoverageV1? {
    val coverage =
        element?.let {
            val value = it.objectAt("/platform_coverage")
            value.rejectUnknown(setOf("signal"), "/platform_coverage")
            val signal = value.stringAt("signal", "/platform_coverage")
            validateIdentifier(signal, "/platform_coverage/signal")
            PlatformCoverageV1(signal)
        }
    val sla = rules.filter { it.effect == ResourceRuleEffect.SLA }
    if (sla.isEmpty()) return coverage
    if (coverage == null) fail("MISSING_FIELD", "/platform_coverage", "platform_coverage is required when an SLA platform rule exists")
    val coverers =
        sla.filter {
            it.signal == coverage.signal &&
                it.operator == ResourceOperator.GT &&
                it.threshold.signum() == 0 &&
                it.minConsecutiveCells == 1 &&
                it.aggregation == ResourceAggregation.INTERVAL_MAX
        }
    sla.forEach { rule ->
        resolveServices(rule.scope, catalog).forEach { service ->
            val covered =
                coverers.any { cover ->
                    service in resolveServices(cover.scope, catalog) &&
                        (cover.windowIds == null || rule.windowIds?.let(cover.windowIds::containsAll) == true)
                }
            if (!covered) {
                fail(
                    "PLATFORM_COVERAGE_MISSING",
                    "/platform_coverage",
                    "every service and window of an SLA platform rule needs an SLA coverage rule (gt 0, interval_max, min_consecutive_cells = 1)",
                )
            }
        }
    }
    return coverage
}

private fun parseDefaults(
    element: JsonElement,
    pointer: String,
): PolicyDefaultsV1 {
    val value = element.objectAt(pointer)
    value.rejectUnknown(setOf("sample_floor", "min_samples"), pointer)
    return PolicyDefaultsV1(value.longInRangeAt("sample_floor", pointer), value.longInRangeAt("min_samples", pointer))
}

private fun checkDefaultsOrder(
    defaults: PolicyDefaultsV1,
    effectiveFloor: Long,
) {
    val minimum = defaults.minSamples ?: MIN_SAMPLES_DEFAULT
    if (minimum < effectiveFloor) {
        val field = if (defaults.minSamples != null) "min_samples" else "sample_floor"
        fail("MIN_SAMPLES_BELOW_FLOOR", "/defaults/$field", "default minimum is below the sample floor")
    }
}

private fun JsonObject.longInRangeAt(
    name: String,
    pointer: String,
    max: Long = MAX_SAMPLES_BOUND,
    code: String = "MIN_SAMPLES_OUT_OF_RANGE",
): Long? {
    val value = get(name) ?: return null
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
    }
    val number =
        try {
            BigDecimal(value.content)
        } catch (_: NumberFormatException) {
            fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
        }
    if (number.stripTrailingZeros().scale() > 0) fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
    if (number < BigDecimal.ONE || number > BigDecimal.valueOf(max)) {
        fail(code, pointer.child(name), "$name must be between 1 and $max")
    }
    return number.longValueExact()
}

private fun JsonObject.windowIdsAt(pointer: String): List<String>? {
    val field = pointer.child("window_ids")
    val value = get("window_ids") ?: return null
    val array = value as? JsonArray ?: fail("WINDOW_IDS_INVALID", field, "window_ids must be an array")
    if (array.isEmpty()) fail("WINDOW_IDS_INVALID", field, "window_ids must not be empty")
    val ids =
        array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("WINDOW_IDS_INVALID", field, "window id must be a string")
        }
    if (ids.any { it.isEmpty() || it.encodeToByteArray().size > MAX_IDENTIFIER_BYTES } || ids.toSet().size != ids.size) {
        fail("WINDOW_IDS_INVALID", field, "window ids must be unique, non-empty and at most 128 UTF-8 bytes")
    }
    return ids
}

private fun parseScope(
    element: JsonElement,
    pointer: String,
): PolicyScope {
    val scope = element.objectAt(pointer)
    return when (scope.stringAt("kind", pointer)) {
        "overall" -> {
            scope.rejectUnknown(setOf("kind"), pointer)
            PolicyScope.Overall
        }

        "transaction" -> {
            scope.rejectUnknown(setOf("kind", "name"), pointer)
            val name = scope.stringAt("name", pointer)
            if (name.isEmpty()) fail("EMPTY_IDENTIFIER", "$pointer/name", "transaction name must not be empty")
            if (name.encodeToByteArray().size > MAX_TRANSACTION_SCOPE_BYTES) {
                fail("RESOURCE_LIMIT_EXCEEDED", "$pointer/name", "transaction name exceeds 4096 UTF-8 bytes")
            }
            PolicyScope.Transaction(name)
        }

        else -> fail("INVALID_SCOPE", "$pointer/kind", "unknown scope kind")
    }
}

private fun validateIdentifier(
    value: String,
    pointer: String,
) {
    if (value.isEmpty()) fail("EMPTY_IDENTIFIER", pointer, "identifier must not be empty")
    if (value.encodeToByteArray().size > MAX_IDENTIFIER_BYTES) {
        fail("RESOURCE_LIMIT_EXCEEDED", pointer, "identifier exceeds 128 UTF-8 bytes")
    }
}

private fun JsonElement.objectAt(pointer: String): JsonObject = this as? JsonObject ?: fail("INVALID_TYPE", pointer, "expected object")

private fun JsonObject.required(
    name: String,
    pointer: String,
): JsonElement = get(name) ?: fail("MISSING_FIELD", pointer.child(name), "required field is missing")

private fun JsonObject.stringAt(
    name: String,
    pointer: String,
): String {
    val value = required(name, pointer)
    if (value !is JsonPrimitive || !value.isString) {
        fail("INVALID_TYPE", pointer.child(name), "$name must be a string")
    }
    return value.content
}

private fun JsonObject.numberAt(
    name: String,
    pointer: String,
): BigDecimal {
    val value = required(name, pointer)
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        fail("INVALID_TYPE", pointer.child(name), "$name must be a number")
    }
    return try {
        BigDecimal(value.content)
    } catch (_: NumberFormatException) {
        fail("INVALID_TYPE", pointer.child(name), "$name must be a finite number")
    }
}

private fun JsonObject.rejectUnknown(
    allowed: Set<String>,
    pointer: String,
) {
    keys.firstOrNull { it !in allowed }?.let { name ->
        fail("UNKNOWN_FIELD", pointer.child(name), "unknown field")
    }
}

private fun String.child(token: String): String = "$this/${token.replace("~", "~0").replace("/", "~1")}"

private data class PolicyFailure(
    val error: PolicyValidationError,
) : RuntimeException()

private fun fail(
    code: String,
    pointer: String,
    message: String,
): Nothing = throw PolicyFailure(PolicyValidationError(code, pointer, message))

private fun invalid(
    code: String,
    pointer: String,
    message: String,
): PolicyValidation.Invalid = PolicyValidation.Invalid(listOf(PolicyValidationError(code, pointer, message)))

internal class StrictJsonScanner(
    private val source: String,
    private val maxDepth: Int,
    private val maxNumericTokenBytes: Int,
    private val maxAbsoluteExponent: Int,
    private val subject: String,
    private val failure: (String, String, String) -> Nothing,
) {
    private var offset = 0

    fun scan() {
        skipWhitespace()
        value("", 0)
        skipWhitespace()
        if (offset != source.length) malformed("")
    }

    private fun value(
        pointer: String,
        depth: Int,
    ) {
        if (offset >= source.length) malformed(pointer)
        when (source[offset]) {
            '{' -> objectValue(pointer, depth + 1)
            '[' -> arrayValue(pointer, depth + 1)
            '"' -> stringValue(pointer)
            't' -> literal("true", pointer)
            'f' -> literal("false", pointer)
            'n' -> literal("null", pointer)
            '-', in '0'..'9' -> numberValue(pointer)
            else -> malformed(pointer)
        }
    }

    private fun objectValue(
        pointer: String,
        depth: Int,
    ) {
        checkDepth(depth)
        offset++
        skipWhitespace()
        if (take('}')) return
        val keys = HashSet<String>()
        while (true) {
            if (offset >= source.length || source[offset] != '"') malformed(pointer)
            val key = stringValue(pointer)
            val child = pointer.child(key)
            if (!keys.add(key)) failure("DUPLICATE_OBJECT_KEY", child, "duplicate object key")
            skipWhitespace()
            expect(':', pointer)
            skipWhitespace()
            value(child, depth)
            skipWhitespace()
            if (take('}')) return
            expect(',', pointer)
            skipWhitespace()
        }
    }

    private fun arrayValue(
        pointer: String,
        depth: Int,
    ) {
        checkDepth(depth)
        offset++
        skipWhitespace()
        if (take(']')) return
        var index = 0
        while (true) {
            value(pointer.child(index.toString()), depth)
            index++
            skipWhitespace()
            if (take(']')) return
            expect(',', pointer)
            skipWhitespace()
        }
    }

    private fun stringValue(pointer: String): String {
        expect('"', pointer)
        val result = StringBuilder()
        while (offset < source.length) {
            val character = source[offset++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> result.append(escapedCharacter(pointer))
                character < ' ' -> malformed(pointer)
                else -> result.append(character)
            }
        }
        malformed(pointer)
    }

    private fun escapedCharacter(pointer: String): Char {
        if (offset >= source.length) malformed(pointer)
        return when (val escaped = source[offset++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (offset + 4 > source.length) malformed(pointer)
                val digits = source.substring(offset, offset + 4)
                if (digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) malformed(pointer)
                val value = digits.toInt(16)
                offset += 4
                value.toChar()
            }

            else -> malformed(pointer)
        }
    }

    private fun numberValue(pointer: String) {
        val start = offset
        take('-')
        if (offset >= source.length) malformed(pointer)
        if (take('0')) {
            if (offset < source.length && source[offset] in '0'..'9') malformed(pointer)
        } else {
            if (offset >= source.length || source[offset] !in '1'..'9') malformed(pointer)
            while (offset < source.length && source[offset] in '0'..'9') offset++
        }
        if (take('.')) {
            val fractionStart = offset
            while (offset < source.length && source[offset] in '0'..'9') offset++
            if (offset == fractionStart) malformed(pointer)
        }
        if (offset < source.length && (source[offset] == 'e' || source[offset] == 'E')) {
            offset++
            take('+') || take('-')
            val exponentStart = offset
            var exponent = 0
            while (offset < source.length && source[offset] in '0'..'9') {
                exponent = minOf(maxAbsoluteExponent + 1, exponent * 10 + (source[offset] - '0'))
                offset++
            }
            if (offset == exponentStart) malformed(pointer)
            if (exponent > maxAbsoluteExponent) {
                failure("RESOURCE_LIMIT_EXCEEDED", pointer, "numeric exponent exceeds $maxAbsoluteExponent")
            }
        }
        val token = source.substring(start, offset)
        if (token.length > maxNumericTokenBytes) {
            failure("RESOURCE_LIMIT_EXCEEDED", pointer, "numeric token exceeds $maxNumericTokenBytes bytes")
        }
        try {
            canonicalDecimal(BigDecimal(token))
        } catch (_: IllegalArgumentException) {
            failure("RESOURCE_LIMIT_EXCEEDED", pointer, "canonical decimal exceeds 128 bytes")
        }
    }

    private fun literal(
        expected: String,
        pointer: String,
    ) {
        if (!source.startsWith(expected, offset)) malformed(pointer)
        offset += expected.length
    }

    private fun checkDepth(depth: Int) {
        if (depth > maxDepth) failure("RESOURCE_LIMIT_EXCEEDED", "", "$subject JSON depth exceeds $maxDepth")
    }

    private fun expect(
        expected: Char,
        pointer: String,
    ) {
        if (!take(expected)) malformed(pointer)
    }

    private fun take(expected: Char): Boolean {
        if (offset >= source.length || source[offset] != expected) return false
        offset++
        return true
    }

    private fun skipWhitespace() {
        while (offset < source.length &&
            (source[offset] == ' ' || source[offset] == '\t' || source[offset] == '\r' || source[offset] == '\n')
        ) {
            offset++
        }
    }

    private fun malformed(pointer: String): Nothing = failure("MALFORMED_JSON", pointer, "$subject is not valid JSON")
}

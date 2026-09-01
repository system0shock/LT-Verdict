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
        PolicyLexicalScanner(text).scan()
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
): PolicyEvaluation {
    val orderedDiagnostics = diagnostics.sortedWith(compareBy<Diagnostic> { it.code }.thenBy { it.sourceOffset })
    val findings = orderedDiagnostics.map(::diagnosticFinding).toMutableList()
    val metricEvidence = metrics?.let(::metricEvidence).orEmpty()
    val evidence = metricEvidence.map(MetricEvidence::json).toMutableList()
    evidence += orderedDiagnostics.map(::diagnosticEvidence)
    val reasons = orderedDiagnostics.map(Diagnostic::code).toMutableList()

    if (validity != RunValidity.VALID) {
        return PolicyEvaluation(PolicyVerdict.NO_VERDICT, reasons.distinct(), findings, evidence)
    }
    if (policy == null) return PolicyEvaluation(PolicyVerdict.NO_POLICY, reasons.distinct(), findings, evidence)

    val checks = mutableListOf<JsonObject>()
    var failed = false
    policy.rules.forEach { rule ->
        val binding = bind(rule, metrics, metricEvidence)
        if (binding.reason != null) {
            reasons += binding.reason
            checks += policyCheck(rule, null, null, binding.reason)
            return@forEach
        }
        val metric = binding.metric ?: error("metric binding is incomplete")
        val observed = observed(rule, metric.summary)
        if (observed == null) {
            reasons += METRIC_NOT_AVAILABLE
            checks += policyCheck(rule, metric, null, METRIC_NOT_AVAILABLE)
            return@forEach
        }
        val passed =
            when (rule.operator) {
                PolicyOperator.LTE -> observed.comparison <= 0
                PolicyOperator.GTE -> observed.comparison >= 0
            }
        checks += policyCheck(rule, metric, observed.json, if (passed) null else POLICY_FAILED)
        if (!passed) {
            failed = true
            findings +=
                policyFailure(
                    rule,
                    checks
                        .last()
                        .getValue("id")
                        .jsonPrimitive.content,
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
    return PolicyEvaluation(verdict, reasons.distinct(), findings, evidence)
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

private fun metricEvidence(metrics: NormalizedMetrics): List<MetricEvidence> {
    val overallId = "metric-summary-overall"
    val overall = MetricEvidence(null, metrics.overall, overallId, metricSummary(overallId, null, metrics.overall))
    val transactions =
        metrics.transactions
            .sortedWith(TRANSACTION_SUMMARY_COMPARATOR)
            .map { transaction ->
                val key = transaction.identity.stableKey()
                val id = stableId("metric-summary", key)
                MetricEvidence(transaction.identity, transaction.metrics, id, metricSummary(id, transaction.identity, transaction.metrics))
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
): JsonObject =
    buildJsonObject {
        put("id", stableId("policy-check", rule.id))
        put("type", "policy_check")
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
        if (metric != null) put("metric_evidence_id", metric.id)
        if (observed != null) put("observed", observed)
        if (reason != null && reason != POLICY_FAILED) put("reason_code", reason)
    }

private fun metricSummary(
    id: String,
    identity: TransactionIdentity?,
    summary: MetricSummary,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("type", "metric_summary")
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
): JsonObject =
    buildJsonObject {
        put("id", stableId("policy-failure", rule.id))
        put("type", "policy_failure")
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
    root.rejectUnknown(setOf("schema_version", "policy_id", "rules"), "")
    val schemaVersion = root.stringAt("schema_version", "")
    if (schemaVersion != "policy.v1") fail("INVALID_SCHEMA_VERSION", "/schema_version", "expected policy.v1")
    val policyId = root.stringAt("policy_id", "")
    validateIdentifier(policyId, "/policy_id")
    val rulesElement = root.required("rules", "")
    val rulesArray = rulesElement as? JsonArray ?: fail("INVALID_TYPE", "/rules", "rules must be an array")
    if (rulesArray.isEmpty()) fail("EMPTY_RULES", "/rules", "at least one rule is required")
    if (rulesArray.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/rules", "too many rules")

    val ids = HashSet<String>()
    val rules =
        rulesArray.mapIndexed { index, value ->
            val pointer = "/rules/$index"
            val rule = value.objectAt(pointer)
            rule.rejectUnknown(setOf("id", "metric", "operator", "threshold", "scope"), pointer)
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
            PolicyRuleV1(id, metric, operator, threshold, scope)
        }
    return PolicyV1(schemaVersion, policyId, rules)
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

private class PolicyLexicalScanner(
    private val source: String,
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
            if (!keys.add(key)) fail("DUPLICATE_OBJECT_KEY", child, "duplicate object key")
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
                exponent = minOf(MAX_ABSOLUTE_EXPONENT + 1, exponent * 10 + (source[offset] - '0'))
                offset++
            }
            if (offset == exponentStart) malformed(pointer)
            if (exponent > MAX_ABSOLUTE_EXPONENT) {
                fail("RESOURCE_LIMIT_EXCEEDED", pointer, "numeric exponent exceeds 64")
            }
        }
        val token = source.substring(start, offset)
        if (token.length > MAX_NUMERIC_TOKEN_BYTES) {
            fail("RESOURCE_LIMIT_EXCEEDED", pointer, "numeric token exceeds 64 bytes")
        }
        try {
            canonicalDecimal(BigDecimal(token))
        } catch (_: IllegalArgumentException) {
            fail("RESOURCE_LIMIT_EXCEEDED", pointer, "canonical decimal exceeds 128 bytes")
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
        if (depth > MAX_POLICY_DEPTH) fail("RESOURCE_LIMIT_EXCEEDED", "", "policy JSON depth exceeds 16")
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

    private fun malformed(pointer: String): Nothing = fail("MALFORMED_JSON", pointer, "policy is not valid JSON")
}

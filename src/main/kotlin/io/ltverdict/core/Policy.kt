package io.ltverdict.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.HexFormat

private const val MAX_CANONICAL_DECIMAL_BYTES = 128

internal fun canonicalJson(element: JsonElement): ByteArray = buildString { appendCanonical(element) }.encodeToByteArray()

internal fun canonicalDecimal(value: BigDecimal): String {
    if (value.signum() == 0) return "0"
    val normalized = value.stripTrailingZeros()
    val signBytes = if (normalized.signum() < 0) 1L else 0L
    val plainBytes =
        when {
            normalized.scale() <= 0 -> normalized.precision().toLong() - normalized.scale().toLong() + signBytes
            normalized.precision() > normalized.scale() -> normalized.precision().toLong() + 1L + signBytes
            else -> normalized.scale().toLong() + 2L + signBytes
        }
    require(plainBytes <= MAX_CANONICAL_DECIMAL_BYTES) { "canonical decimal exceeds 128 bytes" }
    return normalized.toPlainString()
}

internal fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun StringBuilder.appendCanonical(element: JsonElement) {
    when (element) {
        is JsonObject -> {
            append('{')
            element.keys.sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                appendQuoted(key)
                append(':')
                appendCanonical(element.getValue(key))
            }
            append('}')
        }

        is JsonArray -> {
            append('[')
            element.forEachIndexed { index, value ->
                if (index > 0) append(',')
                appendCanonical(value)
            }
            append(']')
        }

        JsonNull -> append("null")
        is JsonPrimitive ->
            if (element.isString) {
                appendQuoted(element.content)
            } else if (element.content == "true" || element.content == "false") {
                append(element.content)
            } else {
                append(canonicalDecimal(BigDecimal(element.content)))
            }
    }
}

private fun StringBuilder.appendQuoted(value: String) {
    append(Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value)))
}

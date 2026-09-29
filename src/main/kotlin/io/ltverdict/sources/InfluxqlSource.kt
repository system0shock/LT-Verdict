package io.ltverdict.sources

import io.ltverdict.core.MAX_LABELS
import io.ltverdict.core.MAX_LABEL_KEY_BYTES
import io.ltverdict.core.MAX_LABEL_VALUE_BYTES
import io.ltverdict.core.MAX_POINTS_PER_SERIES
import io.ltverdict.core.RESOURCE_JSON_DEPTH_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_EXPONENT_ABS_MAX
import io.ltverdict.core.RESOURCE_NUMERIC_TOKEN_BYTES_MAX
import io.ltverdict.core.StrictJsonScanner
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun decodeInfluxqlResponse(
    body: ByteArray,
    expectedLabels: Map<String, String>,
    startEpochMillis: Long,
    stepMillis: Long,
    pointCount: Int,
): PromqlSeries? =
    try {
        if (stepMillis <= 0 || pointCount !in 1..MAX_POINTS_PER_SERIES) influxFail("RESOURCE_LIMIT_EXCEEDED")
        val text = decodeInfluxUtf8(body)
        StrictJsonScanner(
            text,
            RESOURCE_JSON_DEPTH_MAX,
            RESOURCE_NUMERIC_TOKEN_BYTES_MAX,
            RESOURCE_NUMERIC_EXPONENT_ABS_MAX,
            "InfluxQL response",
        ) { code, _, _ -> influxFail(if (code == "MALFORMED_JSON") "MALFORMED_RESPONSE" else code) }.scan()
        val root = Json.parseToJsonElement(text).influxObject()
        rejectInfluxFailure(root)
        val results = root.influxArray("results")
        if (results.isEmpty()) influxFail("MALFORMED_RESPONSE")
        if (results.size != 1) influxFail("AMBIGUOUS_STATEMENT")

        val statement = results.single().influxObject()
        rejectInfluxFailure(statement)
        rejectPartial(statement)
        if (statement.influxLong("statement_id") != 0L) influxFail("MALFORMED_RESPONSE")
        val seriesValues = statement["series"] ?: return null
        val seriesArray = seriesValues as? JsonArray ?: influxFail("MALFORMED_RESPONSE")
        if (seriesArray.isEmpty()) return null
        if (seriesArray.size != 1) influxFail("AMBIGUOUS_SERIES")

        val series = seriesArray.single().influxObject()
        rejectPartial(series)
        series.influxText("name", MAX_LABEL_VALUE_BYTES)
        val labels = series["tags"]?.let(::influxLabels).orEmpty()
        if (expectedLabels.any { (key, value) -> labels[key] != value }) influxFail("LABEL_MISMATCH")
        val columns =
            series.influxArray("columns").map { element ->
                val value = element as? JsonPrimitive
                if (value == null || !value.isString) influxFail("MALFORMED_RESPONSE")
                value.content
            }
        if (columns.size != 2 || columns.toSet() != INFLUX_COLUMNS) influxFail("MALFORMED_RESPONSE")
        val timeIndex = columns.indexOf("time")
        val valueIndex = columns.indexOf("value")
        val samples = series.influxArray("values")
        if (samples.size > pointCount) influxFail("RESOURCE_LIMIT_EXCEEDED")

        val values = arrayOfNulls<BigDecimal>(pointCount)
        val seen = BooleanArray(pointCount)
        samples.forEach { element ->
            val sample = element as? JsonArray ?: influxFail("MALFORMED_RESPONSE")
            if (sample.size != columns.size) influxFail("MALFORMED_RESPONSE")
            val timestamp = sample[timeIndex].influxNumber().longValueExact()
            val delta = Math.subtractExact(timestamp, startEpochMillis)
            if (delta < 0 || delta % stepMillis != 0L || delta / stepMillis >= pointCount) {
                influxFail("OFF_GRID_TIMESTAMP")
            }
            val index = (delta / stepMillis).toInt()
            if (seen[index]) influxFail("DUPLICATE_TIMESTAMP")
            seen[index] = true
            values[index] = sample[valueIndex].influxValue()
        }
        PromqlSeries(labels, values.toList())
    } catch (failure: PromqlDecodeFailure) {
        throw failure
    } catch (_: SerializationException) {
        influxFail("MALFORMED_RESPONSE")
    } catch (_: CharacterCodingException) {
        influxFail("MALFORMED_RESPONSE")
    } catch (_: ArithmeticException) {
        influxFail("MALFORMED_RESPONSE")
    } catch (_: IllegalArgumentException) {
        influxFail("MALFORMED_RESPONSE")
    }

private fun rejectInfluxFailure(value: JsonObject) {
    if ("error" in value) influxFail("INFLUXQL_QUERY_FAILED")
    if ("messages" in value) influxFail("SOURCE_WARNINGS")
}

private fun rejectPartial(value: JsonObject) {
    val partial = value["partial"] ?: return
    if (partial !is JsonPrimitive || partial.isString) influxFail("MALFORMED_RESPONSE")
    when (partial.content) {
        "true" -> influxFail("SOURCE_PARTIAL_RESPONSE")
        "false" -> Unit
        else -> influxFail("MALFORMED_RESPONSE")
    }
}

private fun influxLabels(element: JsonElement): Map<String, String> {
    val value = element.influxObject()
    if (value.size > MAX_LABELS) influxFail("RESOURCE_LIMIT_EXCEEDED")
    return value.mapValues { (key, element) ->
        validateInfluxText(key, MAX_LABEL_KEY_BYTES)
        val label = element as? JsonPrimitive
        if (label == null || !label.isString) influxFail("MALFORMED_RESPONSE")
        validateInfluxText(label.content, MAX_LABEL_VALUE_BYTES)
    }
}

private fun JsonObject.influxArray(name: String): JsonArray = get(name) as? JsonArray ?: influxFail("MALFORMED_RESPONSE")

private fun JsonObject.influxLong(name: String): Long = get(name)?.influxNumber()?.longValueExact() ?: influxFail("MALFORMED_RESPONSE")

private fun JsonObject.influxText(
    name: String,
    maxBytes: Int,
): String {
    val primitive = get(name) as? JsonPrimitive
    if (primitive == null || !primitive.isString) influxFail("MALFORMED_RESPONSE")
    return validateInfluxText(primitive.content, maxBytes)
}

private fun JsonElement.influxObject(): JsonObject = this as? JsonObject ?: influxFail("MALFORMED_RESPONSE")

private fun JsonElement.influxNumber(): BigDecimal {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive.isString || primitive === JsonNull || primitive.content in INFLUX_NON_NUMBERS) {
        influxFail("MALFORMED_RESPONSE")
    }
    if (primitive.content.encodeToByteArray().size > RESOURCE_NUMERIC_TOKEN_BYTES_MAX) influxFail("RESOURCE_LIMIT_EXCEEDED")
    return try {
        BigDecimal(primitive.content)
    } catch (_: NumberFormatException) {
        influxFail("MALFORMED_RESPONSE")
    }
}

private fun JsonElement.influxValue(): BigDecimal? = if (this === JsonNull) null else influxNumber()

private fun validateInfluxText(
    value: String,
    maxBytes: Int,
): String {
    if (value.encodeToByteArray().size > maxBytes) influxFail("RESOURCE_LIMIT_EXCEEDED")
    if (value.isEmpty() || value.any(Char::isISOControl)) influxFail("MALFORMED_RESPONSE")
    return value
}

private fun decodeInfluxUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun influxFail(code: String): Nothing = throw PromqlDecodeFailure(code)

private val INFLUX_COLUMNS = setOf("time", "value")
private val INFLUX_NON_NUMBERS = setOf("true", "false", "null")

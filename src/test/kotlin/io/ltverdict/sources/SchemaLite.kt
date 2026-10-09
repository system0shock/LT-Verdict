package io.ltverdict.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

// A small checker for the JSON Schema keywords the probe contracts use (no library is available and none may be added).
// tools/verify_slice0.py checks the examples with the same keyword set; this one checks the real output of the code.
internal object SchemaLite {
    private val directory = Path.of("docs/contracts/sources/probe/v1")

    fun schema(name: String): JsonObject = Json.parseToJsonElement(Files.readString(directory.resolve("$name.schema.json"))) as JsonObject

    fun errors(
        value: JsonElement,
        schema: JsonObject,
        path: String = "$",
    ): List<String> {
        val errors = mutableListOf<String>()
        (schema["type"])?.let { type ->
            val names = if (type is JsonArray) type.map { (it as JsonPrimitive).content } else listOf((type as JsonPrimitive).content)
            if (names.none { isType(value, it) }) return listOf("$path: expected $names")
        }
        schema["const"]?.let { if (it != value) errors += "$path: must equal $it" }
        (schema["enum"] as? JsonArray)?.let { if (value !in it) errors += "$path: not in enum: $value" }
        if (value is JsonPrimitive && value.isString) {
            schema["pattern"]?.let { if (!Regex((it as JsonPrimitive).content).containsMatchIn(value.content)) errors += "$path: pattern" }
            schema["maxLength"]?.let { if (value.content.length > (it as JsonPrimitive).content.toInt()) errors += "$path: too long" }
            schema["minLength"]?.let { if (value.content.length < (it as JsonPrimitive).content.toInt()) errors += "$path: too short" }
        }
        if (value is JsonPrimitive && !value.isString && value !is JsonNull && value.content.toLongOrNull() != null) {
            val number = value.content.toLong()
            schema["minimum"]?.let { if (number < (it as JsonPrimitive).content.toLong()) errors += "$path: below minimum" }
            schema["maximum"]?.let { if (number > (it as JsonPrimitive).content.toLong()) errors += "$path: above maximum" }
        }
        if (value is JsonArray) {
            schema["maxItems"]?.let { if (value.size > (it as JsonPrimitive).content.toInt()) errors += "$path: too many items" }
            schema["minItems"]?.let { if (value.size < (it as JsonPrimitive).content.toInt()) errors += "$path: too few items" }
            if (schema["uniqueItems"]?.let { (it as JsonPrimitive).content == "true" } == true && value.toSet().size != value.size) {
                errors += "$path: duplicates"
            }
            (schema["items"] as? JsonObject)?.let { items ->
                value.forEachIndexed { index, item ->
                    errors +=
                        errors(item, items, "$path[$index]")
                }
            }
        }
        if (value is JsonObject) {
            (schema["required"] as? JsonArray)?.forEach { name ->
                if ((name as JsonPrimitive).content !in
                    value
                ) {
                    errors += "$path: missing $name"
                }
            }
            val properties = schema["properties"] as? JsonObject
            value.forEach { (name, item) ->
                val property = properties?.get(name) as? JsonObject
                if (property != null) {
                    errors += errors(item, property, "$path.$name")
                } else if (schema["additionalProperties"]?.let { (it as JsonPrimitive).content == "false" } == true) {
                    errors += "$path: unknown field $name"
                }
            }
        }
        (schema["allOf"] as? JsonArray)?.forEach { errors += errors(value, it as JsonObject, path) }
        (schema["if"] as? JsonObject)?.let { condition ->
            val branch = if (errors(value, condition, path).isEmpty()) "then" else "else"
            (schema[branch] as? JsonObject)?.let { errors += errors(value, it, path) }
        }
        return errors
    }

    private fun isType(
        value: JsonElement,
        name: String,
    ): Boolean =
        when (name) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "null" -> value is JsonNull
            "string" -> value is JsonPrimitive && value.isString
            "boolean" -> value is JsonPrimitive && !value.isString && (value.content == "true" || value.content == "false")
            "integer" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.content.toLongOrNull() != null
            else -> error("unsupported type $name")
        }
}

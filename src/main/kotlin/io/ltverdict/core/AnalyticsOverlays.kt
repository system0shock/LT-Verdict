package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

internal fun openSearchOverlay(
    run: JsonObject,
    result: JsonObject,
    markerLimit: Int = DEFAULT_MARKER_LIMIT,
): JsonObject {
    require(markerLimit in 1..MAX_MARKER_LIMIT) { "INVALID_OVERLAY_MARKER_LIMIT" }
    val runStart = Instant.parse(run.getValue("started_at").jsonPrimitive.content).toEpochMilli()
    val contexts =
        (result["evidence"] as? JsonArray)
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.stringOrNull("type") == "opensearch_errors" }
            .sortedBy { it.string("profile_id") }
    val allMarkers =
        contexts
            .flatMap { context ->
                val profile = context.string("profile_id")
                (context["groups"] as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .flatMap { group ->
                        (group["samples"] as? JsonArray)
                            .orEmpty()
                            .mapNotNull { it as? JsonObject }
                            .map { sample -> Marker(profile, group, sample) }
                    }
            }.sortedWith(
                compareBy(
                    Marker::profileId,
                    { it.timestamp() },
                    { it.group.string("service") },
                    { it.group.string("error_type") },
                ),
            )
    val reasons =
        buildList {
            if (contexts.isEmpty()) add("OPENSEARCH_NOT_AVAILABLE")
            if (allMarkers.size > markerLimit) add("OPENSEARCH_MARKERS_TRUNCATED")
        }

    return buildJsonObject {
        put("schema_version", "chart-overlays.v1")
        put(
            "series",
            buildJsonArray {
                contexts.forEach { context ->
                    add(
                        buildJsonObject {
                            put("id", "opensearch-errors:${context.string("profile_id")}")
                            put("profile_id", context.string("profile_id"))
                            put("label", "OpenSearch errors")
                            put("unit", "errors/minute")
                            put(
                                "points",
                                buildJsonArray {
                                    (context["timeline"] as? JsonArray)
                                        .orEmpty()
                                        .mapNotNull { it as? JsonObject }
                                        .sortedBy { it.long("from_epoch_ms") }
                                        .forEach { cell ->
                                            add(
                                                buildJsonObject {
                                                    put("from_ms", Math.subtractExact(cell.long("from_epoch_ms"), runStart))
                                                    put("to_ms", Math.subtractExact(cell.long("to_epoch_ms"), runStart))
                                                    put("count", cell["count"] ?: JsonNull)
                                                    put("value", cell["rate_per_minute"] ?: JsonNull)
                                                },
                                            )
                                        }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "markers",
            buildJsonArray {
                allMarkers.take(markerLimit).forEach { marker ->
                    add(
                        buildJsonObject {
                            put("profile_id", marker.profileId)
                            put("at_ms", Math.subtractExact(marker.timestamp(), runStart))
                            put("service", marker.group.string("service"))
                            put("error_type", marker.group.string("error_type"))
                            put("message", marker.sample["message"] ?: JsonNull)
                            put("message_truncated", marker.sample["message_truncated"] ?: JsonNull)
                            put("source_url", marker.sample["source_url"] ?: JsonNull)
                        },
                    )
                }
            },
        )
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
    }
}

private data class Marker(
    val profileId: String,
    val group: JsonObject,
    val sample: JsonObject,
) {
    fun timestamp(): Long = sample.long("timestamp_epoch_ms")
}

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("INVALID_OPENSEARCH_OVERLAY")

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.long(name: String): Long {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString) { "INVALID_OPENSEARCH_OVERLAY" }
    return value.content.toLongOrNull() ?: throw IllegalArgumentException("INVALID_OPENSEARCH_OVERLAY")
}

private const val DEFAULT_MARKER_LIMIT = 200
private const val MAX_MARKER_LIMIT = 1_000

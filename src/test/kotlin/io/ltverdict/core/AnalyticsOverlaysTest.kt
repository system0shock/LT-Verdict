package io.ltverdict.core

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsOverlaysTest {
    @Test
    fun `OpenSearch overlay keeps profiles separate and aligns saved evidence to run-relative time`() {
        val result = result(openSearch("b", 1_002_000), openSearch("a", 1_001_000))

        val overlay = openSearchOverlay(run(), result)
        val series = overlay.getValue("series").jsonArray.map { it.jsonObject }
        val markers = overlay.getValue("markers").jsonArray.map { it.jsonObject }

        assertEquals(listOf("a", "b"), series.map { it.getValue("profile_id").jsonPrimitive.content })
        assertEquals(
            1_000,
            series
                .first()
                .getValue("points")
                .jsonArray
                .single()
                .jsonObject
                .getValue("from_ms")
                .jsonPrimitive.long,
        )
        assertEquals(
            1_100,
            markers
                .first()
                .getValue("at_ms")
                .jsonPrimitive.long,
        )
        assertEquals(
            "Timeout",
            markers
                .first()
                .getValue("error_type")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `OpenSearch overlay bounds markers and reports absent evidence`() {
        val evidence = openSearch("a", 1_001_000, samples = 3)
        val bounded = openSearchOverlay(run(), result(evidence), markerLimit = 2)

        assertEquals(2, bounded.getValue("markers").jsonArray.size)
        assertEquals(listOf("OPENSEARCH_MARKERS_TRUNCATED"), bounded.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })

        val absent = openSearchOverlay(run(), result())
        assertTrue(absent.getValue("series").jsonArray.isEmpty())
        assertEquals(listOf("OPENSEARCH_NOT_AVAILABLE"), absent.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
    }
}

private fun run() =
    buildJsonObject {
        put("schema_version", "run.v1")
        put("started_at", "1970-01-01T00:16:40Z")
    }

private fun result(vararg evidence: kotlinx.serialization.json.JsonObject) =
    buildJsonObject { put("evidence", buildJsonArray { evidence.forEach(::add) }) }

private fun openSearch(
    profile: String,
    from: Long,
    samples: Int = 1,
) = buildJsonObject {
    put("id", "opensearch-errors-$profile")
    put("type", "opensearch_errors")
    put("profile_id", profile)
    put(
        "timeline",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("from_epoch_ms", from)
                    put("to_epoch_ms", from + 1_000)
                    put("count", 2)
                    put("rate_per_minute", "120")
                },
            )
        },
    )
    put(
        "groups",
        buildJsonArray {
            add(
                buildJsonObject {
                    put("service", "api")
                    put("error_type", "Timeout")
                    put(
                        "samples",
                        buildJsonArray {
                            repeat(samples) { index ->
                                add(
                                    buildJsonObject {
                                        put("timestamp_epoch_ms", from + 100 + index)
                                        put("message", "boom-$index")
                                        put("message_truncated", false)
                                        put("source_url", "https://logs.invalid/$index")
                                    },
                                )
                            }
                        },
                    )
                },
            )
        },
    )
}

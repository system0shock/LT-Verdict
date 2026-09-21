package io.ltverdict.core

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricPacksTest {
    @Test
    fun `packs expose only available capabilities and matching threshold findings`() {
        val result =
            buildJsonObject {
                put(
                    "evidence",
                    buildJsonArray {
                        add(summary("heap", "jvm_heap_used"))
                        add(summary("cpu", "openshift_cpu_usage"))
                        add(summary("other", "database_connections"))
                        add(check("heap-check", "heap", "FAIL"))
                        add(check("other-check", "other", "FAIL"))
                    },
                )
                put(
                    "findings",
                    buildJsonArray {
                        add(finding("heap-finding", "heap", "heap-check"))
                        add(finding("other-finding", "other", "other-check"))
                    },
                )
            }

        val packs =
            metricPackAnalysis(result)
                .getValue("packs")
                .jsonArray
                .map {
                    it.jsonObject
                }.associateBy { it.getValue("id").jsonPrimitive.content }
        val jvm = packs.getValue("jvm")
        val openshift = packs.getValue("openshift")

        assertEquals("DEGRADED", jvm.getValue("status").jsonPrimitive.content)
        assertEquals(listOf("heap"), jvm.getValue("available_capabilities").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("heap-finding"), jvm.getValue("finding_refs").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(jvm.toString().contains("other-finding"))
        assertEquals(listOf("cpu"), openshift.getValue("available_capabilities").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `missing packs are skipped and missing observations never become healthy findings`() {
        val absent =
            metricPackAnalysis(
                buildJsonObject {
                    put("evidence", buildJsonArray {})
                    put("findings", buildJsonArray {})
                },
            )
        assertTrue(
            absent.getValue("packs").jsonArray.all {
                it.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content == "SKIPPED"
            },
        )

        val gaps =
            metricPackAnalysis(
                buildJsonObject {
                    put("evidence", buildJsonArray { add(summary("heap", "jvm_heap_used", reasons = listOf("NO_OBSERVATIONS"))) })
                    put("findings", buildJsonArray {})
                },
            ).getValue("packs").jsonArray.first().jsonObject
        assertEquals("DEGRADED", gaps.getValue("status").jsonPrimitive.content)
        assertTrue(gaps.getValue("finding_refs").jsonArray.isEmpty())
    }
}

private fun summary(
    series: String,
    metric: String,
    reasons: List<String> = emptyList(),
) = buildJsonObject {
    put("id", "summary-$series")
    put("type", "resource_summary")
    put("series_id", series)
    put("metric", metric)
    put("reasons", buildJsonArray { reasons.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
}

private fun check(
    id: String,
    series: String,
    status: String,
) = buildJsonObject {
    put("id", id)
    put("type", "resource_policy_check")
    put("series_id", series)
    put("status", status)
}

private fun finding(
    id: String,
    series: String,
    evidence: String,
) = buildJsonObject {
    put("id", id)
    put("type", "resource_threshold_violation")
    put("series_id", series)
    put("evidence_id", evidence)
}

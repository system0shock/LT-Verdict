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
    fun `limit ratio signals of the platform base profile are recognised capabilities`() {
        val result =
            buildJsonObject {
                put(
                    "evidence",
                    buildJsonArray {
                        add(summary("cpu-ratio", "openshift_container_cpu_limit_ratio"))
                        add(summary("mem-ratio", "openshift_container_memory_limit_ratio"))
                    },
                )
                put("findings", buildJsonArray {})
            }

        val openshift =
            metricPackAnalysis(result)
                .getValue("packs")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("id").jsonPrimitive.content == "openshift" }

        assertEquals(
            listOf("cpu_limit_ratio", "memory_limit_ratio"),
            openshift.getValue("available_capabilities").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("cpu-ratio", "mem-ratio"),
            openshift
                .getValue("series_ids")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .sorted(),
        )
    }

    @Test
    fun `sidecar signals of the platform profiles are recognised capabilities`() {
        val result =
            buildJsonObject {
                put(
                    "evidence",
                    buildJsonArray {
                        add(summary("sidecar-mem", "openshift_sidecar_memory_limit_ratio"))
                        add(summary("sidecar-throttling", "openshift_sidecar_cpu_throttling"))
                    },
                )
                put("findings", buildJsonArray {})
            }

        val openshift =
            metricPackAnalysis(result)
                .getValue("packs")
                .jsonArray
                .map { it.jsonObject }
                .first { it.getValue("id").jsonPrimitive.content == "openshift" }

        assertEquals(
            listOf("sidecar_memory_limit_ratio", "sidecar_cpu_throttling"),
            openshift.getValue("available_capabilities").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("sidecar-mem", "sidecar-throttling"),
            openshift
                .getValue("series_ids")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .sorted(),
        )
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

    @Test
    fun `postgresql pack recognises postgres_exporter capabilities and becomes SUCCESS when all are present`() {
        val result =
            buildJsonObject {
                put(
                    "evidence",
                    buildJsonArray {
                        add(summary("pg-conn", "pg_connections_utilization"))
                        add(summary("pg-idle", "pg_idle_in_transaction"))
                        add(summary("pg-cache", "pg_cache_hit_ratio"))
                        add(summary("pg-commit", "pg_xact_commit_rate"))
                        add(summary("pg-rollback", "pg_xact_rollback_rate"))
                        add(summary("pg-deadlocks", "pg_deadlocks_rate"))
                        add(summary("pg-temp", "pg_temp_bytes_rate"))
                        add(check("pg-conn-check", "pg-conn", "FAIL"))
                    },
                )
                put("findings", buildJsonArray { add(finding("pg-conn-finding", "pg-conn", "pg-conn-check")) })
            }

        val pg =
            metricPackAnalysis(result)
                .getValue("packs")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("id").jsonPrimitive.content == "postgresql" }

        assertEquals("SUCCESS", pg.getValue("status").jsonPrimitive.content)
        assertEquals(
            listOf("connections", "idle_in_transaction", "cache_hit", "transactions", "deadlocks", "temp_bytes"),
            pg.getValue("available_capabilities").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(pg.getValue("missing_capabilities").jsonArray.isEmpty())
        assertEquals(listOf("pg-conn-finding"), pg.getValue("finding_refs").jsonArray.map { it.jsonPrimitive.content })
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

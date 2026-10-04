package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun metricPackAnalysis(result: JsonObject): JsonObject {
    val evidence = (result["evidence"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val findings = (result["findings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return buildJsonObject {
        put("schema_version", "metric-packs.v1")
        put("packs", buildJsonArray { PACKS.forEach { add(it.analyze(evidence, findings)) } })
    }
}

private fun MetricPack.analyze(
    evidence: List<JsonObject>,
    findings: List<JsonObject>,
): JsonObject {
    val summaries = evidence.filter { it.stringOrNull("type") == "resource_summary" }
    val seriesCapabilities =
        summaries
            .mapNotNull { summary ->
                val capability = capabilityFor(summary.stringOrNull("metric") ?: return@mapNotNull null) ?: return@mapNotNull null
                summary.string("series_id") to capability
            }.toMap()
    val available = capabilities.map(PackCapability::id).filter(seriesCapabilities.values.toSet()::contains)
    val missing = capabilities.map(PackCapability::id).filterNot(seriesCapabilities.values.toSet()::contains)
    val seriesIds = seriesCapabilities.keys
    val failedChecks =
        evidence
            .filter {
                it.stringOrNull("type") == "resource_policy_check" &&
                    it.stringOrNull("status") == "FAIL" &&
                    it.stringOrNull("series_id")?.let(seriesIds::contains) == true
            }.mapNotNull { it.stringOrNull("id") }
            .toSet()
    val findingRefs =
        findings
            .filter {
                it.stringOrNull("type") == "resource_threshold_violation" &&
                    it.stringOrNull("series_id")?.let(seriesIds::contains) == true &&
                    it.stringOrNull("evidence_id")?.let(failedChecks::contains) == true
            }.mapNotNull { it.stringOrNull("id") }
            .sorted()
    val dataReasons =
        summaries
            .filter { it.stringOrNull("series_id")?.let(seriesIds::contains) == true }
            .flatMap { summary ->
                (summary["reasons"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
            }.distinct()
            .sorted()
    val status =
        when {
            available.isEmpty() -> "SKIPPED"
            missing.isNotEmpty() || dataReasons.isNotEmpty() -> "DEGRADED"
            else -> "SUCCESS"
        }
    val reasons =
        when (status) {
            "SKIPPED" -> listOf("PACK_CAPABILITIES_NOT_AVAILABLE")
            else -> dataReasons + missing.map { "MISSING_CAPABILITY:$it" }
        }
    return buildJsonObject {
        put("id", id)
        put("status", status)
        put("available_capabilities", available.jsonArray())
        put("missing_capabilities", missing.jsonArray())
        put("series_ids", seriesIds.sorted().jsonArray())
        put("finding_refs", findingRefs.jsonArray())
        put("reasons", reasons.jsonArray())
    }
}

private fun MetricPack.capabilityFor(metric: String): String? = capabilities.firstOrNull { metric in it.metrics }?.id

private fun List<String>.jsonArray(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

private fun JsonObject.string(name: String): String = stringOrNull(name) ?: throw IllegalArgumentException("INVALID_METRIC_PACK_EVIDENCE")

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private data class MetricPack(
    val id: String,
    val capabilities: List<PackCapability>,
)

private data class PackCapability(
    val id: String,
    val metrics: Set<String>,
)

private val PACKS =
    listOf(
        MetricPack(
            "jvm",
            listOf(
                PackCapability("heap", setOf("jvm_heap_used", "jvm_heap_headroom")),
                PackCapability("non_heap", setOf("jvm_non_heap_used")),
                PackCapability("old_generation", setOf("jvm_old_gen_used")),
                PackCapability("gc", setOf("jvm_gc_count", "jvm_gc_pause", "jvm_gc_time", "jvm_gc_frequency")),
                PackCapability("allocation_rate", setOf("jvm_allocation_rate")),
                PackCapability("threads", setOf("jvm_thread_count")),
                PackCapability("process_cpu", setOf("jvm_process_cpu")),
                PackCapability("class_loading", setOf("jvm_class_loading")),
                PackCapability("pool_saturation", setOf("jvm_pool_saturation")),
                PackCapability("deadlocks", setOf("jvm_deadlocks")),
            ),
        ),
        MetricPack(
            "openshift",
            listOf(
                PackCapability("cpu", setOf("openshift_cpu_usage", "openshift_cpu_request", "openshift_cpu_limit")),
                PackCapability("cpu_throttling", setOf("openshift_cpu_throttling")),
                PackCapability("memory", setOf("openshift_memory_working_set", "openshift_memory_request", "openshift_memory_limit")),
                PackCapability("oom", setOf("openshift_oom")),
                PackCapability("restarts", setOf("openshift_restarts")),
                PackCapability("readiness", setOf("openshift_readiness", "openshift_unavailable_replicas")),
                PackCapability("replicas", setOf("openshift_replica_count", "openshift_pod_count")),
                PackCapability("pod_imbalance", setOf("openshift_pod_imbalance")),
                PackCapability("network", setOf("openshift_network")),
                PackCapability("filesystem", setOf("openshift_filesystem")),
                PackCapability("cpu_limit_ratio", setOf("openshift_container_cpu_limit_ratio")),
                PackCapability("memory_limit_ratio", setOf("openshift_container_memory_limit_ratio")),
            ),
        ),
    )

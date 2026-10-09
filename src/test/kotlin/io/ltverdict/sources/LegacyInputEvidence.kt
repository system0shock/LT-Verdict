package io.ltverdict.sources

import io.ltverdict.core.ResourceSnapshotV1
import io.ltverdict.core.ResourceWindowV1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Frozen copies of the hand-built resource_binding and source_summary builders as they were before W2.1 slice 2c typed them
// (origin/main 7374b9b). The equivalence test compares the typed builders with these on a wide matrix. Do not "fix" them.

internal fun legacyResourceBindingEvidence(
    snapshot: ResourceSnapshotV1,
    windows: List<ResourceWindowV1>,
    runStartEpochMillis: Long,
    runEndEpochMillis: Long,
): JsonObject {
    require(windows.isNotEmpty())
    val implicit = snapshot.windows.isEmpty()
    val evaluationStart = windows.minOf(ResourceWindowV1::fromEpochMillis)
    val evaluationEnd = windows.maxOf(ResourceWindowV1::toEpochMillis)
    val intersectionStart = maxOf(runStartEpochMillis, snapshot.startEpochMillis)
    val intersectionEnd = minOf(runEndEpochMillis, snapshot.gridEndEpochMillis())
    val droppedLeadingMillis = if (implicit) evaluationStart - intersectionStart else 0L
    val droppedTrailingMillis = if (implicit) intersectionEnd - evaluationEnd else 0L
    return buildJsonObject {
        put("id", "resource-binding")
        put("type", "resource_binding")
        put("mode", if (implicit) "run_intersection" else "explicit_windows")
        put("snapshot_from_epoch_ms", snapshot.startEpochMillis)
        put("snapshot_to_epoch_ms", snapshot.gridEndEpochMillis())
        put("run_from_epoch_ms", runStartEpochMillis)
        put("run_to_epoch_ms", runEndEpochMillis)
        put("evaluation_from_epoch_ms", evaluationStart)
        put("evaluation_to_epoch_ms", evaluationEnd)
        put("dropped_leading_cells", if (droppedLeadingMillis > 0) 1 else 0)
        put("dropped_leading_millis", droppedLeadingMillis)
        put("dropped_trailing_cells", if (droppedTrailingMillis > 0) 1 else 0)
        put("dropped_trailing_millis", droppedTrailingMillis)
        put("clock_alignment", "not_verified_by_core")
    }
}

internal fun legacySourceEvidence(
    profile: SourceProfile,
    request: SourceRequest,
    budget: SourceBudget,
    queries: List<QueryEvidence>,
): JsonObject {
    val status =
        when {
            queries.all { it.status == "SUCCESS" } -> "COMPLETE"
            queries.any { it.status == "SUCCESS" || it.status == "PARTIAL" } -> "PARTIAL"
            else -> "FAILED"
        }
    val summary =
        buildJsonObject {
            put("id", "source-summary")
            put("type", "source_summary")
            put("status", status)
            put("profile_id", profile.id)
            profile.arm?.let { put("arm", it) }
            put("source_kind", profile.sourceKind.wireName)
            put("transport", profile.transport.wireName)
            put("start_epoch_ms", request.startEpochMillis)
            put("end_epoch_ms", request.endEpochMillis)
            put("step_ms", request.stepMillis)
            put(
                "queries",
                buildJsonArray {
                    queries.forEach { query ->
                        add(
                            buildJsonObject {
                                put("id", query.id)
                                put("status", query.status)
                                query.reason?.let { put("reason", it) }
                                put("expression_sha256", query.expressionSha256)
                            },
                        )
                    }
                },
            )
            if (profile.ruleSpansMillis.isNotEmpty()) {
                put(
                    "rule_spans",
                    buildJsonArray {
                        profile.rules.filter { it.id in profile.ruleSpansMillis }.forEach { rule ->
                            val span = profile.ruleSpansMillis.getValue(rule.id)
                            val cells = spanToCells(span, request.stepMillis)
                            add(
                                buildJsonObject {
                                    put("rule_id", rule.id)
                                    put("declared_span_ms", span)
                                    put("step_ms", request.stepMillis)
                                    put("cells", cells)
                                    put("effective_span_ms", cells.toLong() * request.stepMillis)
                                },
                            )
                        }
                    },
                )
            }
            put("request_count", budget.requestCount)
            put("retries", budget.retries)
            put("throttle_wait_ms", budget.throttleWaitMillis)
            put("cap_exceeded", budget.capExceeded)
        }
    return request.windowProvenance?.let { JsonObject(summary + it) } ?: summary
}

private fun ResourceSnapshotV1.gridEndEpochMillis(): Long =
    Math.addExact(startEpochMillis, Math.multiplyExact(stepMillis, pointCount.toLong()))

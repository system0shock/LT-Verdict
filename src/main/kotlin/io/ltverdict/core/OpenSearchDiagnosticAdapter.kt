package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

internal data class OpenSearchCorrelationTemplate(
    val id: String,
    val profileId: String,
    val loadMetric: DiagnosticLoadMetric,
    val windowIds: List<String>,
    val expectedSign: DiagnosticExpectedSign,
    val maxLagMillis: Long,
    val minAbsEffect: BigDecimal,
    val minErrorRateDelta: BigDecimal,
    val minLoadDelta: BigDecimal,
    val controls: List<DiagnosticControlV1>,
    val topologyBasis: String,
    val clockAlignment: DiagnosticClockAlignment,
)

internal data class OpenSearchDiagnosticInputs(
    val resources: ResourceValidation.Valid,
    val diagnostics: DiagnosticValidation.Valid,
    val seriesByProfile: Map<String, String>,
)

internal fun prepareOpenSearchDiagnostics(
    contexts: List<JsonObject>,
    loadInputSha256: String,
    base: ResourceValidation.Valid?,
    templates: List<OpenSearchCorrelationTemplate>,
): OpenSearchDiagnosticInputs {
    require(contexts.size in 1..MAX_OPENSEARCH_DIAGNOSTIC_CONTEXTS) { "OPENSEARCH_DIAGNOSTIC_CONTEXT_COUNT" }
    require(templates.size in 1..MAX_DIAGNOSTIC_PAIRS) { "OPENSEARCH_DIAGNOSTIC_TEMPLATE_COUNT" }
    require(
        templates.map(OpenSearchCorrelationTemplate::id).distinct().size == templates.size,
    ) { "OPENSEARCH_DIAGNOSTIC_TEMPLATE_DUPLICATE" }
    val byProfile = contexts.associateBy { it.requiredString("profile_id") }
    require(byProfile.size == contexts.size) { "OPENSEARCH_DIAGNOSTIC_PROFILE_DUPLICATE" }
    val selectedProfiles = templates.map(OpenSearchCorrelationTemplate::profileId).distinct().sorted()
    val grids =
        selectedProfiles.associateWith { profile ->
            val context = byProfile[profile] ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_PROFILE_NOT_FOUND")
            context.toGrid(loadInputSha256)
        }
    val referenceGrid = grids.values.first()
    require(grids.values.all { it.sameGrid(referenceGrid) }) { "OPENSEARCH_DIAGNOSTIC_GRID_MISMATCH" }
    base?.snapshot?.let { snapshot ->
        require(snapshot.loadInputSha256 == loadInputSha256) { "OPENSEARCH_DIAGNOSTIC_LOAD_HASH_MISMATCH" }
        require(
            snapshot.startEpochMillis == referenceGrid.startEpochMillis &&
                snapshot.stepMillis == referenceGrid.stepMillis &&
                snapshot.pointCount == referenceGrid.values.size,
        ) { "OPENSEARCH_DIAGNOSTIC_GRID_MISMATCH" }
    }
    validateWindows(base?.snapshot?.windows.orEmpty(), templates)

    val seriesByProfile = selectedProfiles.associateWith(::openSearchSeriesId)
    val addedSeries =
        selectedProfiles.map { profile ->
            ResourceSeriesV1(
                id = seriesByProfile.getValue(profile),
                metric = "opensearch_error_rate",
                unit = "errors/minute",
                entity = profile,
                role = ResourceRole.SYSTEM,
                aggregation = ResourceAggregation.INTERVAL_RATE,
                labels = mapOf("profile_id" to profile),
                values = grids.getValue(profile).values,
            )
        }
    val baseSnapshot = base?.snapshot
    val snapshot =
        ResourceSnapshotV1(
            schemaVersion = "resource-snapshot.v1",
            loadInputSha256 = loadInputSha256,
            startEpochMillis = baseSnapshot?.startEpochMillis ?: referenceGrid.startEpochMillis,
            stepMillis = baseSnapshot?.stepMillis ?: referenceGrid.stepMillis,
            pointCount = baseSnapshot?.pointCount ?: referenceGrid.values.size,
            series = baseSnapshot?.series.orEmpty() + addedSeries,
            windows = baseSnapshot?.windows.orEmpty(),
            rules = baseSnapshot?.rules.orEmpty(),
            provenance =
                if (baseSnapshot == null) {
                    ResourceProvenanceV1(
                        sourceKind = "derived-opensearch-diagnostics",
                        querySemantics = "saved OpenSearch error-rate timeline",
                        clockAlignment = "exact grid; no resampling or interpolation",
                    )
                } else {
                    ResourceProvenanceV1(
                        sourceKind = "combined-offline-resources-and-opensearch",
                        querySemantics = "existing validated resource series plus saved OpenSearch error-rate timeline",
                        clockAlignment = "existing grid preserved; OpenSearch exact grid; no resampling or interpolation",
                    )
                },
        )
    val resources =
        validateResourceSnapshot(canonicalJson(snapshot.json()).inputStream()) as? ResourceValidation.Valid
            ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_RESOURCE_INVALID")
    val diagnosticJson = diagnosticPlanJson(resources.semanticSha256, templates, seriesByProfile)
    val diagnostics =
        validateDiagnosticPlan(canonicalJson(diagnosticJson).inputStream()) as? DiagnosticValidation.Valid
            ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_TEMPLATE_INVALID")
    require(validateDiagnosticBinding(diagnostics, resources).isEmpty()) { "OPENSEARCH_DIAGNOSTIC_BINDING_INVALID" }
    return OpenSearchDiagnosticInputs(resources, diagnostics, seriesByProfile)
}

private fun JsonObject.toGrid(loadInputSha256: String): OpenSearchGrid {
    require(requiredString("type") == "opensearch_errors") { "OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID" }
    require(requiredString("load_input_sha256") == loadInputSha256) { "OPENSEARCH_DIAGNOSTIC_LOAD_HASH_MISMATCH" }
    val start = requiredLong("start_epoch_ms")
    val end = requiredLong("end_epoch_ms")
    val step = requiredLong("step_ms")
    val duration =
        try {
            Math.subtractExact(end, start)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_GRID_INVALID")
        }
    require(step > 0 && duration > 0 && duration % step == 0L) { "OPENSEARCH_DIAGNOSTIC_GRID_INVALID" }
    val pointCount = duration / step
    require(pointCount in 1..MAX_OPENSEARCH_DIAGNOSTIC_POINTS.toLong()) { "OPENSEARCH_DIAGNOSTIC_GRID_INVALID" }
    val count = pointCount.toInt()
    val timeline = this["timeline"] as? JsonArray ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")
    require(timeline.size == count) { "OPENSEARCH_DIAGNOSTIC_GRID_INVALID" }
    val values =
        timeline.mapIndexed { index, element ->
            val cell = element as? JsonObject ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")
            val expectedFrom = Math.addExact(start, Math.multiplyExact(index.toLong(), step))
            require(
                cell.requiredLong("from_epoch_ms") == expectedFrom && cell.requiredLong("to_epoch_ms") == Math.addExact(expectedFrom, step),
            ) {
                "OPENSEARCH_DIAGNOSTIC_GRID_INVALID"
            }
            cell.requiredDecimal("rate_per_minute")
        }
    return OpenSearchGrid(start, step, values)
}

private fun validateWindows(
    windows: List<ResourceWindowV1>,
    templates: List<OpenSearchCorrelationTemplate>,
) {
    val available = if (windows.isEmpty()) setOf("run-intersection") else windows.map(ResourceWindowV1::id).toSet()
    require(templates.all { it.windowIds.isNotEmpty() && it.windowIds.all(available::contains) }) {
        "OPENSEARCH_DIAGNOSTIC_WINDOW_NOT_FOUND"
    }
}

private fun diagnosticPlanJson(
    resourceSha256: String,
    templates: List<OpenSearchCorrelationTemplate>,
    seriesByProfile: Map<String, String>,
): JsonObject =
    buildJsonObject {
        put("schema_version", "correlation-plan.v1")
        put("resource_snapshot_sha256", resourceSha256)
        put(
            "pairs",
            buildJsonArray {
                templates.sortedBy(OpenSearchCorrelationTemplate::id).forEach { template ->
                    add(
                        buildJsonObject {
                            put("id", template.id)
                            put("resource_series_id", seriesByProfile.getValue(template.profileId))
                            put("load_metric", template.loadMetric.wireName)
                            put("window_ids", buildJsonArray { template.windowIds.forEach { add(JsonPrimitive(it)) } })
                            put("expected_sign", template.expectedSign.wireName)
                            put("max_lag_ms", template.maxLagMillis)
                            put("min_abs_effect", JsonPrimitive(template.minAbsEffect))
                            put("min_resource_delta", JsonPrimitive(template.minErrorRateDelta))
                            put("min_load_delta", JsonPrimitive(template.minLoadDelta))
                            put(
                                "controls",
                                buildJsonArray {
                                    template.controls.forEach { control ->
                                        add(
                                            buildJsonObject {
                                                put("meaning", control.meaning.wireName)
                                                control.seriesId?.let { put("series_id", it) }
                                            },
                                        )
                                    }
                                },
                            )
                            put("topology_basis", template.topologyBasis)
                            put("clock_alignment", template.clockAlignment.wireName)
                        },
                    )
                }
            },
        )
        put("anomalies", buildJsonArray {})
    }

private fun ResourceSnapshotV1.json(): JsonObject =
    buildJsonObject {
        put("schema_version", schemaVersion)
        put("load_input_sha256", loadInputSha256)
        put("start_epoch_ms", startEpochMillis)
        put("step_ms", stepMillis)
        put("point_count", pointCount)
        put("series", buildJsonArray { series.forEach { add(it.json()) } })
        put("windows", buildJsonArray { windows.forEach { add(it.json()) } })
        put("rules", buildJsonArray { rules.forEach { add(it.json()) } })
        provenance?.let { put("provenance", it.json()) }
    }

private fun ResourceSeriesV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("metric", metric)
        put("unit", unit)
        put("entity", entity)
        put("role", role.wireName)
        put("aggregation", aggregation.wireName)
        put("labels", buildJsonObject { labels.forEach { (key, value) -> put(key, value) } })
        put("values", buildJsonArray { values.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
    }

private fun ResourceWindowV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("from_epoch_ms", fromEpochMillis)
        put("to_epoch_ms", toEpochMillis)
    }

private fun ResourceRuleV1.json(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("series_id", seriesId)
        put("unit", unit)
        put("operator", operator.wireName)
        put("threshold", JsonPrimitive(threshold))
        put("min_consecutive_cells", minConsecutiveCells)
        put("effect", effect.wireName)
    }

private fun ResourceProvenanceV1.json(): JsonObject =
    buildJsonObject {
        put("source_kind", sourceKind)
        put("query_semantics", querySemantics)
        put("clock_alignment", clockAlignment)
    }

private fun openSearchSeriesId(profileId: String): String =
    "opensearch-error-rate-${sha256Hex(profileId.encodeToByteArray()).take(OPENSEARCH_PROFILE_HASH_LENGTH)}"

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")

private fun JsonObject.requiredLong(name: String): Long {
    val value = this[name] as? JsonPrimitive
    require(value != null && !value.isString) { "OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID" }
    return value.content.toLongOrNull() ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")
}

private fun JsonObject.requiredDecimal(name: String): BigDecimal {
    val value = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")
    require(!value.isString) { "OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID" }
    return value.content.toBigDecimalOrNull() ?: throw IllegalArgumentException("OPENSEARCH_DIAGNOSTIC_CONTEXT_INVALID")
}

private data class OpenSearchGrid(
    val startEpochMillis: Long,
    val stepMillis: Long,
    val values: List<BigDecimal>,
) {
    fun sameGrid(other: OpenSearchGrid): Boolean =
        startEpochMillis == other.startEpochMillis && stepMillis == other.stepMillis && values.size == other.values.size
}

private const val MAX_OPENSEARCH_DIAGNOSTIC_CONTEXTS = 16
private const val MAX_OPENSEARCH_DIAGNOSTIC_POINTS = 100_000
private const val OPENSEARCH_PROFILE_HASH_LENGTH = 24

package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

// Read side of pod-view.v1 (ADR 0020, section 6): pure functions over a validated artifact, no I/O.
internal const val MAX_POD_VIEW_PAGE_CELLS = 100_000
internal const val DEFAULT_POD_VIEW_PAGE_ROWS = 64
internal const val MAX_POD_VIEW_PAGE_ROWS = 256

internal class PodViewQueryException(
    val kind: Kind,
    message: String,
) : IllegalArgumentException(message) {
    enum class Kind { INVALID_QUERY, INVALID_CURSOR, SERVICE_NOT_FOUND }
}

internal fun podViewMetadataJson(
    view: PodViewV1,
    podViewSha256: String,
): JsonObject {
    val serviceOfPod = view.pods.associate { it.pod to it.service }
    val rowsByService = view.rows.groupingBy { serviceOfPod.getValue(it.pod) }.eachCount()
    return buildJsonObject {
        put("schema_version", "pod-view.v1")
        put("arm", view.arm?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "grid",
            buildJsonObject {
                put("start_epoch_ms", view.startEpochMillis)
                put("step_ms", view.stepMillis)
                put("column_count", view.columnCount)
            },
        )
        put(
            "coverage",
            buildJsonObject {
                put("pods_observed_total", view.coverage.podsObservedTotal)
                put("pods_included", view.coverage.podsIncluded)
                put("rows_observed_total", view.coverage.rowsObservedTotal)
                put("rows_included", view.coverage.rowsIncluded)
                put(
                    "selection",
                    buildJsonObject {
                        put("kind", view.coverage.selection.kind.wireName)
                        view.coverage.selection.metric
                            ?.let { put("metric", it) }
                        view.coverage.selection.limit
                            ?.let { put("limit", it) }
                    },
                )
            },
        )
        // view.pods is in (service, pod) byte order, so the services come out in canonical order.
        put(
            "services",
            buildJsonArray {
                view.pods.groupBy { it.service }.forEach { (service, pods) ->
                    add(
                        buildJsonObject {
                            put("service", service)
                            put("pods", pods.size)
                            put("rows", rowsByService[service] ?: 0)
                        },
                    )
                }
            },
        )
        put("pod_view_sha256", podViewSha256)
        put("resource_snapshot_sha256", view.resourceSnapshotSha256)
    }
}

/**
 * One page of rows of a service (optionally one metric) in canonical order, cut to a window of whole columns. [limit] must
 * already be within 1..[MAX_POD_VIEW_PAGE_ROWS]. The cell cap is checked against the requested [limit], not the rows found,
 * so no filter can step around it; a page above it is refused, never truncated.
 */
internal fun podViewValuesJson(
    view: PodViewV1,
    podViewSha256: String,
    service: String,
    metric: String?,
    fromMs: Long?,
    toMs: Long?,
    limit: Int,
    after: String?,
    maxCells: Int = MAX_POD_VIEW_PAGE_CELLS,
): JsonObject {
    val pods = view.pods.filter { it.service == service }.mapTo(HashSet(), PodViewPod::pod)
    if (pods.isEmpty()) throw PodViewQueryException(PodViewQueryException.Kind.SERVICE_NOT_FOUND, "Service was not found")

    val gridStart = view.startEpochMillis
    val step = view.stepMillis
    val gridEnd = gridStart + step * view.columnCount
    val from = fromMs ?: gridStart
    val to = toMs ?: gridEnd
    if (from < gridStart || from >= gridEnd || (from - gridStart) % step != 0L) badQuery("from_ms must be the start of a column")
    if (to <= from || to > gridEnd || (to - gridStart) % step != 0L) badQuery("to_ms must be the end of a column after from_ms")
    val firstColumn = ((from - gridStart) / step).toInt()
    val endColumn = ((to - gridStart) / step).toInt()
    if (limit.toLong() * (endColumn - firstColumn) > maxCells) badQuery("Page exceeds $maxCells cells; narrow the selection")

    val selected = view.rows.filter { it.pod in pods && (metric == null || it.metric == metric) }
    val start =
        if (after == null) {
            0
        } else {
            selected.indexOfFirst { it.id == after }.also {
                if (it < 0) throw PodViewQueryException(PodViewQueryException.Kind.INVALID_CURSOR, "after is not a row of this selection")
            } + 1
        }
    val page = selected.drop(start).take(limit)
    val more = start + page.size < selected.size
    return buildJsonObject {
        put("schema_version", "pod-view.v1")
        put("pod_view_sha256", podViewSha256)
        put("numeric_encoding", "ieee754-double")
        put("service", service)
        put(
            "grid",
            buildJsonObject {
                put("start_epoch_ms", gridStart)
                put("step_ms", step)
                put("from_ms", from)
                put("to_ms", to)
                put("column_count", endColumn - firstColumn)
            },
        )
        put(
            "rows",
            buildJsonArray {
                page.forEach { row ->
                    add(
                        buildJsonObject {
                            put("id", row.id)
                            put("pod", row.pod)
                            put("container", row.container?.let(::JsonPrimitive) ?: JsonNull)
                            put("metric", row.metric)
                            put("unit", row.unit)
                            put("aggregation", row.aggregation.wireName)
                            put(
                                "values",
                                buildJsonArray {
                                    row.values.subList(firstColumn, endColumn).forEach {
                                        add(
                                            it?.toResponseDouble()?.let(::JsonPrimitive) ?: JsonNull,
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put("next_after", if (more) JsonPrimitive(page.last().id) else JsonNull)
    }
}

private fun badQuery(message: String): Nothing = throw PodViewQueryException(PodViewQueryException.Kind.INVALID_QUERY, message)

private fun BigDecimal.toResponseDouble(): Double = java.lang.Double.parseDouble(toPlainString())

package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal const val MAX_VALUES_CELLS = 100_000
internal const val MAX_VALUES_SERIES = 32
internal const val MAX_CATALOG_PAGE = 256
internal const val DEFAULT_VALUES_CELLS = 2_000
internal val SERIES_MEAN_CONTEXT = MathContext(34, RoundingMode.HALF_EVEN)

internal enum class SeriesReducer(
    val wireName: String,
) {
    MEAN("mean"),
    MAX("max"),
    MIN("min"),
}

internal fun reducerFor(aggregation: ResourceAggregation): SeriesReducer =
    when (aggregation) {
        ResourceAggregation.INTERVAL_MEAN, ResourceAggregation.INTERVAL_RATE -> SeriesReducer.MEAN
        ResourceAggregation.INTERVAL_MAX -> SeriesReducer.MAX
        ResourceAggregation.INTERVAL_MIN -> SeriesReducer.MIN
    }

internal data class CoarseCell(
    val value: Double?,
    val observed: Int,
)

private fun BigDecimal.toResponseDouble(): Double = java.lang.Double.parseDouble(toPlainString())

internal fun coarsen(
    values: List<BigDecimal?>,
    ratio: Int,
    firstCell: Int,
    cellCount: Int,
    reducer: SeriesReducer,
): List<CoarseCell> =
    List(cellCount) { offset ->
        val from = (firstCell + offset).toLong() * ratio
        val to = minOf(from + ratio, values.size.toLong())
        var count = 0
        var sum = BigDecimal.ZERO
        var best: BigDecimal? = null
        for (index in from.toInt() until to.toInt()) {
            val value = values[index] ?: continue
            count += 1
            when (reducer) {
                SeriesReducer.MEAN -> sum = sum.add(value)
                SeriesReducer.MAX -> if (best == null || value > best) best = value
                SeriesReducer.MIN -> if (best == null || value < best) best = value
            }
        }
        val reduced =
            when {
                count == 0 -> null
                reducer == SeriesReducer.MEAN -> sum.divide(BigDecimal(count), SERIES_MEAN_CONTEXT)
                else -> best
            }
        CoarseCell(reduced?.toResponseDouble(), count)
    }

internal class SeriesQueryException(
    val tooLarge: Boolean,
    message: String,
) : IllegalArgumentException(message)

internal data class SeriesGrid(
    val startMs: Long,
    val stepMs: Long,
    val pointCount: Int,
) {
    val endMs: Long get() = startMs + stepMs * pointCount
}

internal data class ValuesPlan(
    val stepMs: Long,
    val ratio: Int,
    val firstCell: Int,
    val cellCount: Int,
    val nextFromMs: Long?,
    val sourceCellsPerCell: Int,
    val lastCellSourceCells: Int,
)

private fun badQuery(message: String): Nothing = throw SeriesQueryException(false, message)

internal fun planValuesPage(
    grid: SeriesGrid,
    stepMs: Long?,
    fromMs: Long?,
    toMs: Long?,
    limit: Int?,
    seriesCount: Int,
): ValuesPlan {
    val step = stepMs ?: grid.stepMs
    if (step < grid.stepMs || step % grid.stepMs != 0L) badQuery("step_ms must be a multiple of the snapshot step")
    val ratio = minOf(step / grid.stepMs, grid.pointCount.toLong()).toInt()
    val totalCells = (grid.pointCount + ratio - 1) / ratio
    val from = fromMs ?: grid.startMs
    if (from < grid.startMs || from >= grid.endMs) badQuery("from_ms is outside the grid")
    val firstOffset = from - grid.startMs
    if (firstOffset % step != 0L) badQuery("from_ms must be the start of a cell")
    val firstCell = (firstOffset / step).toInt()
    val to = toMs ?: grid.endMs
    if (to <= from || to > grid.endMs) badQuery("to_ms is outside the grid")
    val endCell =
        when {
            to == grid.endMs -> totalCells
            (to - grid.startMs) % step == 0L -> ((to - grid.startMs) / step).toInt()
            else -> badQuery("to_ms must be the start of a cell or the end of the grid")
        }
    if (endCell <= firstCell) badQuery("the requested range is empty")
    val perSeries = limit ?: minOf(DEFAULT_VALUES_CELLS, MAX_VALUES_CELLS / seriesCount)
    if (perSeries !in 1..MAX_VALUES_CELLS) badQuery("limit is invalid")
    if (perSeries.toLong() * seriesCount > MAX_VALUES_CELLS) {
        throw SeriesQueryException(true, "series times limit exceeds $MAX_VALUES_CELLS cells")
    }
    val cellCount = minOf(endCell - firstCell, perSeries)
    val last = firstCell + cellCount - 1
    return ValuesPlan(
        step,
        ratio,
        firstCell,
        cellCount,
        if (firstCell + cellCount < endCell) grid.startMs + (firstCell + cellCount).toLong() * step else null,
        ratio,
        minOf(ratio, grid.pointCount - last * ratio),
    )
}

internal fun catalogJson(
    snapshot: ResourceSnapshotV1,
    semanticSha256: String,
    after: String?,
    limit: Int,
): JsonObject {
    val remaining = snapshot.series.filter { after == null || it.id.compareTo(after) > 0 }
    val page = remaining.take(limit)
    return buildJsonObject {
        put("schema_version", "resource-series.v1")
        put("kind", "catalog")
        put("resource_snapshot_sha256", semanticSha256)
        put("numeric_encoding", "ieee754-double")
        put(
            "grid",
            buildJsonObject {
                put("start_epoch_ms", snapshot.startEpochMillis)
                put("step_ms", snapshot.stepMillis)
                put("point_count", snapshot.pointCount)
            },
        )
        put(
            "windows",
            buildJsonArray {
                snapshot.windows.forEach { window ->
                    add(
                        buildJsonObject {
                            put("id", window.id)
                            put("from_epoch_ms", window.fromEpochMillis)
                            put("to_epoch_ms", window.toEpochMillis)
                        },
                    )
                }
            },
        )
        put(
            "series",
            buildJsonArray {
                page.forEach { series ->
                    add(
                        buildJsonObject {
                            put("id", series.id)
                            put("metric", series.metric)
                            put("unit", series.unit)
                            put("entity", series.entity)
                            put("role", series.role.wireName)
                            put("aggregation", series.aggregation.wireName)
                            put("reducer", reducerFor(series.aggregation).wireName)
                            put("labels", buildJsonObject { series.labels.forEach { (key, value) -> put(key, value) } })
                            put("observed_cells", series.values.count { it != null })
                        },
                    )
                }
            },
        )
        put("next_after", if (remaining.size > page.size) JsonPrimitive(page.last().id) else JsonNull)
    }
}

internal fun valuesJson(
    snapshot: ResourceSnapshotV1,
    semanticSha256: String,
    seriesIds: List<String>,
    plan: ValuesPlan,
): JsonObject {
    val byId = snapshot.series.associateBy(ResourceSeriesV1::id)
    return buildJsonObject {
        put("schema_version", "resource-series.v1")
        put("kind", "values")
        put("resource_snapshot_sha256", semanticSha256)
        put("numeric_encoding", "ieee754-double")
        put(
            "grid",
            buildJsonObject {
                put("start_epoch_ms", snapshot.startEpochMillis)
                put("source_step_ms", snapshot.stepMillis)
                put("step_ms", plan.stepMs)
                put("first_cell_start_ms", snapshot.startEpochMillis + plan.firstCell.toLong() * plan.stepMs)
                put("cell_count", plan.cellCount)
                put("source_cells_per_cell", plan.sourceCellsPerCell)
                put("last_cell_source_cells", plan.lastCellSourceCells)
            },
        )
        put(
            "series",
            buildJsonArray {
                seriesIds.forEach { id ->
                    val series = checkNotNull(byId[id])
                    val reducer = reducerFor(series.aggregation)
                    val cells = coarsen(series.values, plan.ratio, plan.firstCell, plan.cellCount, reducer)
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("aggregation", series.aggregation.wireName)
                            put("reducer", reducer.wireName)
                            put("values", JsonArray(cells.map { it.value?.let { value -> JsonPrimitive(value) } ?: JsonNull }))
                            if (plan.ratio > 1) put("observed", JsonArray(cells.map { JsonPrimitive(it.observed) }))
                        },
                    )
                }
            },
        )
        put("next_from_ms", plan.nextFromMs?.let(::JsonPrimitive) ?: JsonNull)
    }
}

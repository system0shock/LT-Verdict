package io.ltverdict.sources

import io.ltverdict.core.MAX_POINTS_PER_SERIES
import io.ltverdict.core.MAX_RESOURCE_SERIES
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRuleV1
import io.ltverdict.core.RunPeriodV1
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val AUTO_STEP_UNSATISFIABLE = "AUTO_STEP_UNSATISFIABLE"
internal const val AUTO_STEP_SCRAPE_INTERVAL_REQUIRED = "AUTO_STEP_SCRAPE_INTERVAL_REQUIRED"
internal const val AUTO_STEP_BELOW_SCRAPE_INTERVAL = "AUTO_STEP_BELOW_SCRAPE_INTERVAL"
internal const val AUTO_STEP_QUERY_NOT_INTERVAL_BOUND = "AUTO_STEP_QUERY_NOT_INTERVAL_BOUND"
internal const val AUTO_STEP_AGGREGATION_MISMATCH = "AUTO_STEP_AGGREGATION_MISMATCH"
internal const val AUTO_STEP_RULE_IN_CELLS = "AUTO_STEP_RULE_IN_CELLS"
internal const val RESOLUTION_REDUCED = "RESOLUTION_REDUCED"

// The snapshot grid permits whole-second steps from 1 to 60 seconds.
internal const val MAX_STEP_MILLIS = 60_000L

internal class SourcePlanRefusal(
    val code: String,
    val text: String,
) : IllegalArgumentException(code)

internal data class StepChoice(
    val stepMillis: Long,
    val cellsPerSeries: Long,
)

/** Find the first whole-second step that fits the series and cell limits. */
internal fun planStep(
    seriesCount: Int,
    cellBudget: Long,
    requestedMillis: Long,
    ceilingMillis: Long,
    gridCells: (Long) -> Long?,
): StepChoice {
    var fewestCells: Long? = null
    var step = requestedMillis
    while (step <= ceilingMillis) {
        val cells = gridCells(step)
        if (cells != null) {
            if (seriesCount.toLong() * cells <= cellBudget) return StepChoice(step, cells)
            fewestCells = minOf(fewestCells ?: cells, cells)
        }
        step += 1_000L
    }
    val detail =
        fewestCells?.let { "the largest supported series count for this period is ${minOf(cellBudget / it, MAX_RESOURCE_SERIES.toLong())}" }
            ?: "no step in the allowed range fits this window"
    throw SourcePlanRefusal(
        AUTO_STEP_UNSATISFIABLE,
        "No step from ${requestedMillis / 1_000} s to ${ceilingMillis / 1_000} s keeps " +
            "$seriesCount series within $cellBudget cells; $detail",
    )
}

/** Explicit windows require a step that divides the whole window. */
internal fun explicitGridCells(
    startMillis: Long,
    endMillis: Long,
): (Long) -> Long? =
    { step ->
        val span = endMillis - startMillis
        if (span % step == 0L && span / step in 1..MAX_POINTS_PER_SERIES.toLong()) span / step else null
    }

/** Use the same absolute grid and span limit as window derivation. */
internal fun autoGridCells(
    period: RunPeriodV1,
    auto: AutoWindow,
): (Long) -> Long? =
    { step ->
        when (val outcome = deriveAutoWindow(period, auto.copy(stepMillis = step))) {
            is AutoWindowOutcome.Derived -> (outcome.window.endMillis - outcome.window.startMillis) / step
            is AutoWindowOutcome.Refused ->
                if (outcome.reasonCode == AUTO_WINDOW_SPAN_UNSUPPORTED) null else throw IllegalArgumentException(outcome.reasonCode)
        }
    }

internal data class ReducedSeries(
    val id: String,
    val aggregation: ResourceAggregation,
)

internal data class AppliedStep(
    val requestedMillis: Long,
    val stepMillis: Long,
    val cellsPerSeries: Long,
    val seriesCount: Int,
    val cellBudget: Long,
    val reduced: List<ReducedSeries>,
)

/** Check the scrape floor, select a step, and validate coarsening before collection. */
internal fun applyAutoStep(
    selected: List<SourceProfile>,
    requestedMillis: Long,
    ceilingMillis: Long,
    cellBudget: Long,
    gridCells: (Long) -> Long?,
): AppliedStep {
    val seriesCount = selected.sumOf { it.queries.size }
    if (seriesCount == 0) return AppliedStep(requestedMillis, requestedMillis, gridCells(requestedMillis) ?: 0L, 0, cellBudget, emptyList())
    val withQueries = selected.filter { it.queries.isNotEmpty() }
    withQueries.firstOrNull { it.scrapeIntervalMillis == null }?.let {
        throw SourcePlanRefusal(
            AUTO_STEP_SCRAPE_INTERVAL_REQUIRED,
            "Profile ${it.id} does not declare scrape_interval_ms (source-connections.v3); declare it or use step_mode fixed",
        )
    }
    val floor = withQueries.maxOf { checkNotNull(it.scrapeIntervalMillis) }
    if (requestedMillis < floor) {
        throw SourcePlanRefusal(
            AUTO_STEP_BELOW_SCRAPE_INTERVAL,
            "Requested step $requestedMillis ms is below the scrape interval $floor ms; raise the step or use step_mode fixed",
        )
    }
    val choice = planStep(seriesCount, cellBudget, requestedMillis, ceilingMillis, gridCells)
    val reduced = if (choice.stepMillis > requestedMillis) checkCoarsening(selected) else emptyList()
    return AppliedStep(requestedMillis, choice.stepMillis, choice.cellsPerSeries, seriesCount, cellBudget, reduced)
}

private fun checkCoarsening(selected: List<SourceProfile>): List<ReducedSeries> {
    val reduced = mutableListOf<ReducedSeries>()
    val qualify = selected.size > 1
    selected.forEach { profile ->
        profile.queries.forEach { query ->
            val bound =
                if (profile.sourceKind == SourceKind.INFLUXDB) {
                    usesOnlyIntervalGrouping(query.expression)
                } else {
                    usesOnlyIntervalRanges(query.expression)
                }
            if (!bound) {
                throw SourcePlanRefusal(
                    AUTO_STEP_QUERY_NOT_INTERVAL_BOUND,
                    "Query ${query.id} of profile ${profile.id} uses a time window other than \$__interval; " +
                        "a coarser step would change its meaning",
                )
            }
        }
        profile.queries.forEach { query ->
            val rules = profile.rules.filter { it.seriesId == query.id }
            rules.forEach { rule ->
                if (rule.id !in profile.ruleSpansMillis) {
                    throw SourcePlanRefusal(
                        AUTO_STEP_RULE_IN_CELLS,
                        "Rule ${rule.id} on series ${query.id} of profile ${profile.id} is declared in cells; " +
                            "declare min_consecutive_span_ms (source-connections.v3) to use a coarser step",
                    )
                }
                if (query.aggregation !in survivingAggregations(rule)) {
                    throw SourcePlanRefusal(
                        AUTO_STEP_AGGREGATION_MISMATCH,
                        "Rule ${rule.id} on series ${query.id} of profile ${profile.id} needs " +
                            "${peakAggregation(rule.operator).wireName} to survive a coarser step; " +
                            "the series uses ${query.aggregation.wireName}",
                    )
                }
            }
            val peak = rules.isNotEmpty() && rules.all { query.aggregation == peakAggregation(it.operator) }
            if (!peak) reduced += ReducedSeries(if (qualify) qualifiedSeriesId(profile.id, query.id) else query.id, query.aggregation)
        }
    }
    return reduced
}

// gt sees a peak in the interval maximum; lt sees a trough in the interval minimum.
private fun peakAggregation(operator: ResourceOperator): ResourceAggregation =
    if (operator == ResourceOperator.GT) ResourceAggregation.INTERVAL_MAX else ResourceAggregation.INTERVAL_MIN

private fun survivingAggregations(rule: ResourceRuleV1): Set<ResourceAggregation> = setOf(peakAggregation(rule.operator))

/** Add v4 step selection fields to source_summary. */
internal fun JsonObjectBuilder.putStepProvenance(applied: AppliedStep?) {
    if (applied == null) {
        put("step_origin", "explicit")
        return
    }
    put("step_origin", "auto")
    put("requested_step_ms", applied.requestedMillis)
    put("series_count", applied.seriesCount)
    put("cell_budget", applied.cellBudget)
    put("cells_per_series", applied.cellsPerSeries)
    if (applied.reduced.isNotEmpty()) {
        put(
            "warnings",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("code", RESOLUTION_REDUCED)
                        put("requested_step_ms", applied.requestedMillis)
                        put("applied_step_ms", applied.stepMillis)
                        put(
                            "series",
                            buildJsonArray {
                                applied.reduced.forEach { series ->
                                    add(
                                        buildJsonObject {
                                            put("id", series.id)
                                            put("aggregation", series.aggregation.wireName)
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
}

internal fun IllegalArgumentException.cliMessage(): String =
    (this as? SourcePlanRefusal)?.let { "${it.code}: ${it.text}" } ?: message ?: "INVALID_INPUT"

/** Replace quoted literals with an empty literal in one linear pass; null when a literal is not terminated (fail-closed). */
private fun withoutLiterals(expression: String): String? {
    val bare = StringBuilder(expression.length)
    var index = 0
    while (index < expression.length) {
        val quote = expression[index]
        if (quote == '"' || quote == '\'' || quote == '`') {
            index++
            while (index < expression.length && expression[index] != quote) {
                index += if (quote != '`' && expression[index] == '\\') 2 else 1
            }
            if (index >= expression.length) return null
            bare.append(quote).append(quote)
        } else {
            bare.append(quote)
        }
        index++
    }
    return bare.toString()
}

private val RANGE_SELECTOR = Regex("""\[([^\[\]]*)]""")

/** Require every range selector to use the interval after removing literals. */
internal fun usesOnlyIntervalRanges(expression: String): Boolean {
    val bare = withoutLiterals(expression) ?: return false
    val ranges = RANGE_SELECTOR.findAll(bare).map { it.groupValues[1].trim() }.toList()
    return ranges.isNotEmpty() && ranges.all { it == "\$__interval" }
}

private val INFLUX_SELECT_KEYWORD = Regex("""(?i)\bSELECT\b""")
private val INFLUX_TIME_GROUPING = Regex("""(?i)\btime\s*\(\s*([^,)\s]*)""")

/** Require one InfluxQL SELECT with interval-bound time groupings. */
internal fun usesOnlyIntervalGrouping(expression: String): Boolean {
    val bare = withoutLiterals(expression) ?: return false
    val groupings = INFLUX_TIME_GROUPING.findAll(bare).map { it.groupValues[1] }.toList()
    return INFLUX_SELECT_KEYWORD.findAll(bare).count() == 1 && groupings.isNotEmpty() && groupings.all { it == "\$__interval" }
}

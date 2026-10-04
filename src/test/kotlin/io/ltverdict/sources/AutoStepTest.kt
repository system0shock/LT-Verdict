package io.ltverdict.sources

import io.ltverdict.core.MAX_RESOURCE_CELLS
import io.ltverdict.core.RUN_PERIOD_RECOGNITION_METHOD
import io.ltverdict.core.RUN_PERIOD_SCHEMA_VERSION
import io.ltverdict.core.RUN_PERIOD_STATUS_INVALID_INPUT
import io.ltverdict.core.RUN_PERIOD_STATUS_RECOGNIZED
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceRuleEffect
import io.ltverdict.core.ResourceRuleV1
import io.ltverdict.core.RunPeriodV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI

class AutoStepTest {
    @Test
    fun `an explicit window inside the budget keeps the requested step`() {
        assertEquals(StepChoice(15_000L, 1_920L), planStep(512, MAX_RESOURCE_CELLS, 15_000L, 60_000L, explicitGridCells(0L, EIGHT_HOURS)))
    }

    @Test
    fun `the step never drops below the requested one`() {
        assertEquals(StepChoice(30_000L, 960L), planStep(1, MAX_RESOURCE_CELLS, 30_000L, 60_000L, explicitGridCells(0L, EIGHT_HOURS)))
    }

    @Test
    fun `an eight hour explicit window with 1024 series coarsens to 20 seconds`() {
        // 15, 16, and 18 seconds exceed the budget; 17 and 19 do not divide the window.
        assertEquals(StepChoice(20_000L, 1_440L), planStep(1_024, MAX_RESOURCE_CELLS, 15_000L, 60_000L, explicitGridCells(0L, EIGHT_HOURS)))
    }

    @Test
    fun `a 24 hour window needs the 60 second ceiling and 25 hours are refused with the largest supported series count`() {
        assertEquals(StepChoice(60_000L, 1_440L), planStep(1_024, MAX_RESOURCE_CELLS, 15_000L, 60_000L, explicitGridCells(0L, 86_400_000L)))

        val refusal =
            assertThrows(SourcePlanRefusal::class.java) {
                planStep(1_024, MAX_RESOURCE_CELLS, 15_000L, 60_000L, explicitGridCells(0L, 90_000_000L))
            }
        assertEquals(AUTO_STEP_UNSATISFIABLE, refusal.code)
        assertEquals(AUTO_STEP_UNSATISFIABLE, refusal.message)
        assertTrue("1000" in refusal.text, refusal.text)
    }

    @Test
    fun `a derived window counts the cells of every candidate on the absolute grid`() {
        val period = period(1L, 40_001L)

        assertEquals(3L, autoGridCells(period, AutoWindow(0L, 60_000L, 20_000L, stepAuto = true))(20_000L))
        assertEquals(1_440L, autoGridCells(period(T0, T0 + EIGHT_HOURS), AutoWindow(0L, 1_800_000L, 15_000L, stepAuto = true))(20_000L))
    }

    @Test
    fun `a derived eight hour window with 1024 series coarsens to 20 seconds`() {
        val auto = AutoWindow(0L, 1_800_000L, 15_000L, stepAuto = true)

        val choice = planStep(1_024, MAX_RESOURCE_CELLS, 15_000L, 60_000L, autoGridCells(period(T0, T0 + EIGHT_HOURS), auto))

        assertEquals(StepChoice(20_000L, 1_440L), choice)
    }

    @Test
    fun `the idle gap caps the candidates for a derived window`() {
        val auto = AutoWindow(0L, 15_000L, 15_000L, stepAuto = true)

        val refusal =
            assertThrows(SourcePlanRefusal::class.java) {
                planStep(
                    1_024,
                    MAX_RESOURCE_CELLS,
                    15_000L,
                    minOf(60_000L, auto.maxIdleGapMillis),
                    autoGridCells(
                        period(
                            T0,
                            T0 + EIGHT_HOURS,
                        ),
                        auto,
                    ),
                )
            }
        assertEquals(AUTO_STEP_UNSATISFIABLE, refusal.code)
    }

    @Test
    fun `a period beyond 100000 cells at the requested step moves to the next step instead of refusing`() {
        val auto = AutoWindow(0L, 60_000L, 1_000L, stepAuto = true)

        val choice = planStep(1, MAX_RESOURCE_CELLS, 1_000L, 60_000L, autoGridCells(period(T0, T0 + 108_000_000L), auto))

        assertEquals(StepChoice(2_000L, 54_000L), choice)
    }

    @Test
    fun `an explicit window beyond 100000 cells at the requested step moves to the next dividing step`() {
        assertEquals(StepChoice(2_000L, 54_000L), planStep(1, MAX_RESOURCE_CELLS, 1_000L, 60_000L, explicitGridCells(0L, 108_000_000L)))
    }

    @Test
    fun `refusals of the window derivation are not hidden behind the step search`() {
        val auto = AutoWindow(0L, 60_000L, 15_000L, stepAuto = true)
        val unrecognized = period(0L, 0L, status = RUN_PERIOD_STATUS_INVALID_INPUT)

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                planStep(1, MAX_RESOURCE_CELLS, 15_000L, 60_000L, autoGridCells(unrecognized, auto))
            }
        assertEquals(AUTO_WINDOW_UNAVAILABLE, failure.message)
    }

    @Test
    fun `derived window permits margins and idle gaps that do not divide the candidate step`() {
        val auto = AutoWindow(1_001L, 60_001L, 15_000L, stepAuto = true)

        assertEquals(3L, autoGridCells(period(15_001L, 40_001L), auto)(20_000L))
    }

    @Test
    fun `a profile without a scrape interval refuses autostep and the floor is the maximum over selected profiles`() {
        val missing = assertThrows(SourcePlanRefusal::class.java) { apply(listOf(profile(scrape = null))) }
        assertEquals(AUTO_STEP_SCRAPE_INTERVAL_REQUIRED, missing.code)

        val below =
            assertThrows(SourcePlanRefusal::class.java) { apply(listOf(profile("a", scrape = 15_000L), profile("b", scrape = 30_000L))) }
        assertEquals(AUTO_STEP_BELOW_SCRAPE_INTERVAL, below.code)

        // OpenSearch has no metric queries and does not set a scrape floor.
        val errors = profile("errors", queries = emptyList(), scrape = null, kind = SourceKind.OPENSEARCH)
        assertEquals(15_000L, apply(listOf(profile("a", scrape = 15_000L), errors)).stepMillis)
    }

    @Test
    fun `an opensearch only selection keeps the requested step`() {
        val errors = profile("errors", queries = emptyList(), scrape = null, kind = SourceKind.OPENSEARCH)

        val applied = apply(listOf(errors))

        assertEquals(15_000L, applied.stepMillis)
        assertEquals(0, applied.seriesCount)
        assertEquals(emptyList<ReducedSeries>(), applied.reduced)
    }

    @Test
    fun `coarsening lists every series and keeps qualified identifiers for several profiles`() {
        val first = profile("a/b", queries = listOf(query("q1"), query("q2")))
        val second = profile("c%d", queries = listOf(query("q1", ResourceAggregation.INTERVAL_MAX)))

        // For three series in one hour, 18 seconds is the first step within 600 cells.
        val applied = apply(listOf(first, second), cellBudget = 600L)

        assertEquals(18_000L, applied.stepMillis)
        assertEquals(15_000L, applied.requestedMillis)
        assertEquals(
            listOf(
                ReducedSeries("a%2Fb/q1", ResourceAggregation.INTERVAL_MEAN),
                ReducedSeries("a%2Fb/q2", ResourceAggregation.INTERVAL_MEAN),
                ReducedSeries("c%25d/q1", ResourceAggregation.INTERVAL_MAX),
            ),
            applied.reduced,
        )
    }

    @Test
    fun `a single profile keeps unqualified identifiers and an unchanged step publishes no reduced series`() {
        val single = profile("p", queries = listOf(query("q1"), query("q2")))

        assertEquals(
            listOf(ReducedSeries("q1", ResourceAggregation.INTERVAL_MEAN), ReducedSeries("q2", ResourceAggregation.INTERVAL_MEAN)),
            apply(listOf(single), cellBudget = 400L).reduced,
        )
        assertEquals(emptyList<ReducedSeries>(), apply(listOf(single)).reduced)
    }

    @Test
    fun `a query bound to something other than the interval is refused only when the step is coarsened`() {
        val mixed = profile(queries = listOf(query("q1", expression = "rate(x[1m]) + avg_over_time(y[${'$'}__interval])"), query("q2")))
        val refusal = assertThrows(SourcePlanRefusal::class.java) { apply(listOf(mixed), cellBudget = 400L) }

        assertEquals(AUTO_STEP_QUERY_NOT_INTERVAL_BOUND, refusal.code)
        assertTrue("q1" in refusal.text)
        assertEquals(15_000L, apply(listOf(mixed)).stepMillis)
    }

    @Test
    fun `an influx query with a nested fixed window is refused when coarsened and a plain one is not`() {
        val plain =
            "SELECT mean(\"cpu\") AS \"value\" FROM \"host\" WHERE time >= ${'$'}__start AND time < ${'$'}__end " +
                "GROUP BY time(${'$'}__interval, ${'$'}__offset) fill(null)"
        val nested =
            "SELECT mean(\"v\") AS \"value\" FROM (SELECT max(\"cpu\") AS \"v\" FROM \"host\" GROUP BY time(5m)) " +
                "WHERE time >= ${'$'}__start AND time < ${'$'}__end GROUP BY time(${'$'}__interval) fill(null)"

        assertTrue(usesOnlyIntervalGrouping(plain))
        assertFalse(usesOnlyIntervalGrouping(nested))
        val refusal =
            assertThrows(SourcePlanRefusal::class.java) {
                apply(
                    listOf(
                        profile(
                            kind = SourceKind.INFLUXDB,
                            queries = listOf(query("q1", expression = nested), query("q2", expression = plain)),
                        ),
                    ),
                    cellBudget = 400L,
                )
            }
        assertEquals(AUTO_STEP_QUERY_NOT_INTERVAL_BOUND, refusal.code)
        assertEquals(
            18_000L,
            apply(
                listOf(
                    profile(kind = SourceKind.INFLUXDB, queries = listOf(query("q1", expression = plain), query("q2", expression = plain))),
                ),
                cellBudget = 400L,
            ).stepMillis,
        )
    }

    @Test
    fun `range selector detection ignores brackets inside string literals`() {
        assertTrue(usesOnlyIntervalRanges("rate(x[${'$'}__interval])"))
        assertTrue(usesOnlyIntervalRanges("""avg_over_time(x{pod=~"a[0-9]"}[${'$'}__interval])"""))
        assertFalse(usesOnlyIntervalRanges("rate(x[1m])"))
        assertFalse(usesOnlyIntervalRanges("rate(x[1m]) + avg_over_time(y[${'$'}__interval])"))
        assertFalse(usesOnlyIntervalRanges("max_over_time(rate(x[1m])[${'$'}__interval:15s])"))
    }

    @Test
    fun `coarsening is refused while a selected profile declares any rule`() {
        val rule = ResourceRuleV1("r", "q1", "ratio", ResourceOperator.GT, BigDecimal("0.8"), 1, ResourceRuleEffect.SLA)

        val refusal =
            assertThrows(SourcePlanRefusal::class.java) {
                apply(
                    listOf(profile(queries = listOf(query("q1", ResourceAggregation.INTERVAL_MAX), query("q2")), rules = listOf(rule))),
                    cellBudget = 400L,
                )
            }

        assertEquals(AUTO_STEP_AGGREGATION_MISMATCH, refusal.code)
        assertEquals(15_000L, apply(listOf(profile(queries = listOf(query("q1"), query("q2")), rules = listOf(rule)))).stepMillis)
    }

    private fun apply(
        selected: List<SourceProfile>,
        cellBudget: Long = MAX_RESOURCE_CELLS,
    ): AppliedStep = applyAutoStep(selected, 15_000L, 60_000L, cellBudget, explicitGridCells(0L, 3_600_000L))

    private fun profile(
        id: String = "p",
        queries: List<SourceQuery> = listOf(query("q1")),
        rules: List<ResourceRuleV1> = emptyList(),
        scrape: Long? = 15_000L,
        kind: SourceKind = SourceKind.PROMETHEUS,
    ) = SourceProfile(
        id,
        kind,
        SourceTransport.DIRECT,
        URI.create("http://127.0.0.1:1"),
        null,
        queries = queries,
        rules = rules,
        scrapeIntervalMillis = scrape,
    )

    private fun query(
        id: String,
        aggregation: ResourceAggregation = ResourceAggregation.INTERVAL_MEAN,
        expression: String = "avg_over_time(x[${'$'}__interval])",
    ) = SourceQuery(id, expression, id, "ratio", "e", ResourceRole.SYSTEM, aggregation, emptyMap())

    private fun period(
        first: Long,
        last: Long,
        status: String = RUN_PERIOD_STATUS_RECOGNIZED,
    ): RunPeriodV1 = RunPeriodV1(RUN_PERIOD_SCHEMA_VERSION, "a".repeat(64), RUN_PERIOD_RECOGNITION_METHOD, first, last, null, 0, status)

    private companion object {
        const val EIGHT_HOURS = 28_800_000L
        const val T0 = 1_767_225_600_000L
    }
}

package io.ltverdict.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class TrendAnalysisTest {
    @Test
    fun `a material increase inside the declared window is observed as a diagnostic finding`() {
        val analysis = evaluateTrend(plan(), snapshot(ramp()), WINDOWS)

        val evidence = singleCheck(analysis)
        assertEquals("TREND_OBSERVED", evidence.string("status"))
        assertEquals("increase", evidence.string("observed_direction"))
        assertEquals("1", evidence.string("slope_per_second"))
        assertEquals("20", evidence.string("split_half_shift"))
        assertEquals("119.5", evidence.string("median"))
        assertEquals("5.975", evidence.gate().string("required_split_half_shift_units"))
        assertEquals(listOf("STATIONARITY_NOT_EVALUATED"), evidence.reasons())
        assertEquals("NOT_ESTIMATED", evidence.string("uncertainty"))
        assertEquals("slope-materiality.v1", evidence.string("method"))
        assertEquals("40", evidence.string("expected_cells"))
        assertEquals("40", evidence.string("observed_cells"))
        assertEquals("0", evidence.string("missing_cells"))

        val finding = analysis.findings.single()
        assertEquals("resource_trend", finding.string("type"))
        assertEquals("diagnostic", finding.string("effect"))
        assertEquals("increase", finding.string("observed_direction"))
        assertEquals(evidence.string("id"), finding.string("evidence_id"))
        assertEquals(START.toString(), finding.string("from_epoch_ms"))
        assertEquals((START + 40_000).toString(), finding.string("to_epoch_ms"))
        assertEquals("1", finding.string("slope_per_second"))

        assertEquals("trend.v1", analysis.trendJson.string("schema_version"))
        assertEquals("1", analysis.trendJson.string("observed"))
        assertEquals("1", summary(analysis).string("observed"))
        assertEquals("0", summary(analysis).string("not_material"))
    }

    @Test
    fun `a material decrease is observed only in its declared direction`() {
        val values = List(40) { BigDecimal.valueOf(200L - it) }

        val decreasing = evaluateTrend(plan(direction = TrendDirection.DECREASE), snapshot(values), WINDOWS)
        val evidence = singleCheck(decreasing)
        assertEquals("TREND_OBSERVED", evidence.string("status"))
        assertEquals("decrease", evidence.string("observed_direction"))
        assertEquals("-1", evidence.string("slope_per_second"))
        assertEquals("-20", evidence.string("split_half_shift"))
        assertEquals("180.5", evidence.string("median"))
        assertEquals("9.025", evidence.gate().string("required_split_half_shift_units"))
        assertEquals(1, decreasing.findings.size)

        val wrongDirection = evaluateTrend(plan(direction = TrendDirection.INCREASE), snapshot(values), WINDOWS)
        assertEquals("NO_MATERIAL_TREND", singleCheck(wrongDirection).string("status"))
        assertEquals(listOf("TREND_DIRECTION_MISMATCH"), singleCheck(wrongDirection).reasons())
        assertTrue(wrongDirection.findings.isEmpty())
    }

    @Test
    fun `a slope below the declared magnitude stays a non-material observation`() {
        val analysis = evaluateTrend(plan(minSlope = "2"), snapshot(ramp()), WINDOWS)

        assertEquals("NO_MATERIAL_TREND", singleCheck(analysis).string("status"))
        assertEquals(listOf("TREND_SLOPE_BELOW_MINIMUM"), singleCheck(analysis).reasons())
        assertEquals("increase", singleCheck(analysis).string("observed_direction"))
        assertTrue(analysis.findings.isEmpty())
        assertEquals("0", analysis.trendJson.string("observed"))
        assertEquals("1", summary(analysis).string("not_material"))
    }

    @Test
    fun `a terminal spike moves the slope without moving the halves and is not observed`() {
        val values = List(39) { BigDecimal.valueOf(100L) } + listOf(BigDecimal.valueOf(1000L))

        val analysis = evaluateTrend(plan(direction = TrendDirection.EITHER), snapshot(values), WINDOWS)

        val evidence = singleCheck(analysis)
        assertEquals("NO_MATERIAL_TREND", evidence.string("status"))
        assertEquals("increase", evidence.string("observed_direction"))
        assertEquals("100", evidence.string("median"))
        assertEquals("0", evidence.string("split_half_shift"))
        assertEquals("5", evidence.gate().string("required_split_half_shift_units"))
        assertEquals(listOf("TREND_DIRECTION_DISAGREEMENT", "TREND_SHIFT_BELOW_MINIMUM"), evidence.reasons())
        assertTrue(analysis.findings.isEmpty())
    }

    @Test
    fun `a zero median removes the percentage scale and abstains`() {
        val analysis = evaluateTrend(plan(), snapshot(List(40) { BigDecimal.ZERO }), WINDOWS)

        assertEquals("UNAVAILABLE", singleCheck(analysis).string("status"))
        assertEquals(listOf("TREND_MEDIAN_ZERO"), singleCheck(analysis).reasons())
        assertEquals("0", singleCheck(analysis).string("slope_per_second"))
        assertTrue(analysis.findings.isEmpty())
        assertEquals("1", summary(analysis).string("unavailable"))
    }

    @Test
    fun `missing cells keep grid time and are reported without blocking the observation`() {
        val values = ramp().toMutableList()
        values[5] = null

        val analysis = evaluateTrend(plan(), snapshot(values), WINDOWS)

        val evidence = singleCheck(analysis)
        assertEquals("TREND_OBSERVED", evidence.string("status"))
        assertEquals("40", evidence.string("expected_cells"))
        assertEquals("39", evidence.string("observed_cells"))
        assertEquals("1", evidence.string("missing_cells"))
        assertEquals("1", evidence.string("longest_gap_cells"))
        assertEquals(listOf("RESOURCE_GAPS", "STATIONARITY_NOT_EVALUATED"), evidence.reasons())
        assertEquals(1, analysis.findings.size)
    }

    @Test
    fun `fewer observed cells than the declared minimum abstains`() {
        val analysis = evaluateTrend(plan(minCells = 41), snapshot(ramp()), WINDOWS)

        assertEquals("INSUFFICIENT_CELLS", singleCheck(analysis).string("status"))
        assertEquals(listOf("TREND_MIN_CELLS_NOT_MET"), singleCheck(analysis).reasons())
        assertEquals("40", singleCheck(analysis).string("observed_cells"))
        assertTrue(analysis.findings.isEmpty())
        assertEquals("1", summary(analysis).string("insufficient"))
    }

    @Test
    fun `a sparse half abstains even when the whole window meets min_cells`() {
        // 60 ячеек, середина 30; в первой половине одна точка, во второй 30, всего 31 >= 30
        val values = List<BigDecimal?>(60) { index -> if (index < 29) null else BigDecimal.valueOf(100L + index) }

        val analysis = evaluateTrend(plan(), snapshot(values, WINDOWS_60), WINDOWS_60)

        val evidence = singleCheck(analysis)
        assertEquals("INSUFFICIENT_CELLS", evidence.string("status"))
        assertEquals(listOf("RESOURCE_GAPS", "TREND_HALF_CELLS_NOT_MET"), evidence.reasons())
        assertEquals("31", evidence.string("observed_cells"))
        assertTrue(analysis.findings.isEmpty())
    }

    @Test
    fun `a half at exactly half of min_cells is enough and one fewer is not`() {
        // min_cells = 30 -> порог половины 15. Первая половина: 15 точек (проходит) и 14 (отказ).
        fun window(firstHalfObserved: Int) =
            List<BigDecimal?>(60) { index ->
                when {
                    index < 30 && index >= 30 - firstHalfObserved -> BigDecimal.valueOf(100L + index)
                    index >= 30 -> BigDecimal.valueOf(100L + index)
                    else -> null
                }
            }

        val enough = evaluateTrend(plan(), snapshot(window(15), WINDOWS_60), WINDOWS_60)
        assertEquals("TREND_OBSERVED", singleCheck(enough).string("status"))

        val notEnough = evaluateTrend(plan(), snapshot(window(14), WINDOWS_60), WINDOWS_60)
        assertEquals("INSUFFICIENT_CELLS", singleCheck(notEnough).string("status"))
        assertEquals(listOf("RESOURCE_GAPS", "TREND_HALF_CELLS_NOT_MET"), singleCheck(notEnough).reasons())
    }

    @Test
    fun `the half threshold follows a min_cells above the floor and is symmetric`() {
        // min_cells = 60 -> порог половины 30.
        // 80 ячеек, середина 40: первая половина 40, вторая 11, всего 51 < 60 -> срабатывает общий минимум
        val values = List<BigDecimal?>(80) { index -> if (index >= 40 + 11) null else BigDecimal.valueOf(100L + index) }
        val total = evaluateTrend(plan(minCells = 60), snapshot(values, WINDOWS_80), WINDOWS_80)
        assertEquals(listOf("RESOURCE_GAPS", "TREND_MIN_CELLS_NOT_MET"), singleCheck(total).reasons())

        // 100 ячеек, середина 50; вторая половина 29 наблюдённых, первая 50, всего 79 >= 60
        val sparseSecond = List<BigDecimal?>(100) { index -> if (index >= 50 + 29) null else BigDecimal.valueOf(100L + index) }
        val half = evaluateTrend(plan(minCells = 60), snapshot(sparseSecond, WINDOWS_100), WINDOWS_100)
        assertEquals("INSUFFICIENT_CELLS", singleCheck(half).string("status"))
        assertEquals(listOf("RESOURCE_GAPS", "TREND_HALF_CELLS_NOT_MET"), singleCheck(half).reasons())
    }

    @Test
    fun `sawtooth restarts are not distinguished from a trend`() {
        // Характеризация, а не желаемое поведение: 3 цикла по 40 ячеек, каждый линейно от 200 до 980, без роста огибающей.
        val values = List<BigDecimal?>(160) { index -> BigDecimal.valueOf(200L + 20L * (index % 40)) }
        val windows =
            listOf(
                ResourceWindowV1("cycle-boundary", START, START + 120_000L),
                ResourceWindowV1("mid-cycle", START + 20_000L, START + 140_000L),
                ResourceWindowV1("single-cycle", START, START + 40_000L),
            )
        // окно: (направление, slope_per_second, split_half_shift); медиана везде 590, порог сдвига 5 % = 29,5
        val expected =
            mapOf(
                "cycle-boundary" to Triple("increase", "2.2209875685811514688520036113619", "200"),
                "mid-cycle" to Triple("decrease", "-1.112577262309882630738245711507744", "-200"),
                "single-cycle" to Triple("increase", "20", "400"),
            )
        for (window in windows) {
            val analysis = evaluateTrend(plan(direction = TrendDirection.EITHER, windowId = window.id), snapshot(values, windows), windows)
            val evidence = singleCheck(analysis)
            val (direction, slope, shift) = expected.getValue(window.id)
            assertEquals("TREND_OBSERVED", evidence.string("status"), window.id)
            assertEquals(direction, evidence.string("observed_direction"), window.id)
            assertEquals(slope, evidence.string("slope_per_second"), window.id)
            assertEquals(shift, evidence.string("split_half_shift"), window.id)
            assertEquals("590", evidence.string("median"), window.id)
            assertEquals(listOf("STATIONARITY_NOT_EVALUATED"), evidence.reasons(), window.id)
            assertEquals(1, analysis.findings.size, window.id)
        }
    }

    @Test
    fun `an empty window reports no observations instead of a flat trend`() {
        val analysis = evaluateTrend(plan(), snapshot(List<BigDecimal?>(40) { null }), WINDOWS)

        assertEquals("INSUFFICIENT_CELLS", singleCheck(analysis).string("status"))
        assertEquals(listOf("RESOURCE_GAPS", "NO_OBSERVATIONS"), singleCheck(analysis).reasons())
        assertEquals("0", singleCheck(analysis).string("observed_cells"))
        assertEquals("40", singleCheck(analysis).string("missing_cells"))
        assertEquals("null", singleCheck(analysis).getValue("slope_per_second").toString())
        assertTrue(analysis.findings.isEmpty())
    }

    @Test
    fun `an unknown series or window abstains with an exact reason`() {
        val missingSeries = evaluateTrend(plan(seriesId = "absent"), snapshot(ramp()), WINDOWS)
        assertEquals("UNAVAILABLE", singleCheck(missingSeries).string("status"))
        assertEquals(listOf("TREND_SERIES_NOT_FOUND"), singleCheck(missingSeries).reasons())
        assertEquals("null", singleCheck(missingSeries).getValue("metric").toString())

        val missingWindow = evaluateTrend(plan(windowId = "absent"), snapshot(ramp()), WINDOWS)
        assertEquals("UNAVAILABLE", singleCheck(missingWindow).string("status"))
        assertEquals(listOf("TREND_WINDOW_NOT_FOUND"), singleCheck(missingWindow).reasons())
        assertEquals("null", singleCheck(missingWindow).getValue("window_from_epoch_ms").toString())
    }

    @Test
    fun `an invalid run marks every declared check unavailable without findings`() {
        val analysis = trendUnavailable(plan(), "RUN_NOT_VALID")

        assertEquals("UNAVAILABLE", singleCheck(analysis).string("status"))
        assertEquals(listOf("RUN_NOT_VALID"), singleCheck(analysis).reasons())
        assertTrue(analysis.findings.isEmpty())
        assertEquals("1", summary(analysis).string("unavailable"))
        assertEquals("1", analysis.trendJson.string("checks_total"))
    }

    @Test
    fun `several checks are evaluated independently and counted in one summary`() {
        val multiple =
            TrendPlanV1(
                "b".repeat(64),
                listOf(
                    trendCheck("observed", TrendDirection.INCREASE, "0.5"),
                    trendCheck("not-material", TrendDirection.INCREASE, "50"),
                    trendCheck("absent-series", TrendDirection.INCREASE, "0.5", "absent"),
                ),
            )

        val analysis = evaluateTrend(multiple, snapshot(ramp()), WINDOWS)

        assertEquals(3, analysis.evidence.count { it.string("type") == "trend_check" })
        assertEquals(1, analysis.findings.size)
        assertEquals("3", summary(analysis).string("checks_total"))
        assertEquals("1", summary(analysis).string("observed"))
        assertEquals("1", summary(analysis).string("not_material"))
        assertEquals("1", summary(analysis).string("unavailable"))
        assertEquals("3", analysis.trendJson.string("checks_total"))
    }

    @Test
    fun `cancellation is checked while walking the window cells`() {
        var calls = 0

        val error =
            assertThrows(IllegalStateException::class.java) {
                evaluateTrend(plan(), snapshot(ramp()), WINDOWS) {
                    if (++calls > 5) throw IllegalStateException("cancelled")
                }
            }

        assertEquals("cancelled", error.message)
    }

    private fun trendCheck(
        id: String,
        direction: TrendDirection,
        minSlope: String,
        seriesId: String = "cpu",
    ) = TrendCheckV1(
        id,
        seriesId,
        "evaluation",
        direction,
        TREND_MIN_CELLS_FLOOR,
        TrendMagnitudeGate(BigDecimal(minSlope), BigDecimal("5")),
    )

    private fun plan(
        direction: TrendDirection = TrendDirection.INCREASE,
        minCells: Int = TREND_MIN_CELLS_FLOOR,
        minSlope: String = "0.5",
        minShiftPct: String = "5",
        seriesId: String = "cpu",
        windowId: String = "evaluation",
    ) = TrendPlanV1(
        "b".repeat(64),
        listOf(
            TrendCheckV1(
                "cpu-growth",
                seriesId,
                windowId,
                direction,
                minCells,
                TrendMagnitudeGate(BigDecimal(minSlope), BigDecimal(minShiftPct)),
            ),
        ),
    )

    private fun ramp(): List<BigDecimal?> = List(40) { BigDecimal.valueOf(100L + it) }

    private fun snapshot(
        values: List<BigDecimal?>,
        windows: List<ResourceWindowV1> = WINDOWS,
    ): ResourceSnapshotV1 =
        ResourceSnapshotV1(
            "resource-snapshot.v1",
            "a".repeat(64),
            START,
            1_000L,
            values.size,
            listOf(
                ResourceSeriesV1(
                    "cpu",
                    "cpu_utilization",
                    "percent",
                    "host",
                    ResourceRole.SYSTEM,
                    ResourceAggregation.INTERVAL_MEAN,
                    emptyMap(),
                    values,
                ),
            ),
            windows,
            emptyList(),
            null,
        )

    private fun singleCheck(analysis: TrendAnalysis): JsonObject = analysis.evidence.single { it.string("type") == "trend_check" }

    private fun summary(analysis: TrendAnalysis): JsonObject = analysis.evidence.single { it.string("type") == "trend_summary" }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.gate(): JsonObject = getValue("magnitude_gate") as JsonObject

    private fun JsonObject.reasons(): List<String> = getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        const val START = 1_767_225_600_000L
        val WINDOWS = listOf(ResourceWindowV1("evaluation", START, START + 40_000L))
        val WINDOWS_60 = listOf(ResourceWindowV1("evaluation", START, START + 60_000L))
        val WINDOWS_80 = listOf(ResourceWindowV1("evaluation", START, START + 80_000L))
        val WINDOWS_100 = listOf(ResourceWindowV1("evaluation", START, START + 100_000L))
    }
}

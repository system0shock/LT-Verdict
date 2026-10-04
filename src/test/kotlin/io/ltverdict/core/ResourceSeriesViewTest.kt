package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ResourceSeriesViewTest {
    @Test
    fun `mean of 1 1 2 is a 34 digit half-even decimal not an exception`() {
        assertEquals(
            CoarseCell(1.3333333333333333, 3),
            coarsen(listOf(BigDecimal.ONE, BigDecimal.ONE, BigDecimal(2)), 3, 0, 1, SeriesReducer.MEAN).single(),
        )
    }

    @Test
    fun `mean reduces only observed cells and an empty cell is null with zero observed`() {
        val values = listOf(BigDecimal("0.5"), null, BigDecimal("1.5"), null, null, null)
        assertEquals(listOf(CoarseCell(1.0, 2), CoarseCell(null, 0)), coarsen(values, 3, 0, 2, SeriesReducer.MEAN))
    }

    @Test
    fun `max and min are exact for 32 digit values and the last cell may be partial`() {
        val big = BigDecimal("99999999999999999.999999999999")
        val values = listOf(big, BigDecimal("3"), BigDecimal("-2"), BigDecimal("7"), BigDecimal("1"))
        assertEquals(
            listOf(CoarseCell(1.0E17, 2), CoarseCell(7.0, 2), CoarseCell(1.0, 1)),
            coarsen(values, 2, 0, 3, SeriesReducer.MAX),
        )
        assertEquals(CoarseCell(-2.0, 2), coarsen(values, 2, 1, 1, SeriesReducer.MIN).single())
    }

    @Test
    fun `plan keeps the source step by default and returns the whole grid`() {
        assertEquals(ValuesPlan(15_000, 1, 0, 50, null, 1, 1), planValuesPage(SeriesGrid(1_000, 15_000, 50), null, null, null, null, 2))
    }

    @Test
    fun `plan rejects a step that is not a multiple of the snapshot step and boundaries off the coarse grid`() {
        val grid = SeriesGrid(1_000, 17_000, 50)
        val queries: List<Triple<Long, Long?, Long?>> =
            listOf(
                Triple(20_000L, null, null),
                Triple(34_000L, 2_000L, null),
                Triple(34_000L, null, 36_000L),
                Triple(34_000L, 35_000L, 35_000L),
                Triple(0L, null, null),
            )
        queries.forEach { (step, from, to) ->
            assertFalse(assertThrows(SeriesQueryException::class.java) { planValuesPage(grid, step, from, to, null, 1) }.tooLarge)
        }
    }

    @Test
    fun `plan accepts the grid end as a boundary and reports the partial last cell`() {
        assertEquals(
            ValuesPlan(45_000, 3, 0, 17, null, 3, 2),
            planValuesPage(SeriesGrid(0, 15_000, 50), 45_000, 0, 750_000, null, 1),
        )
    }

    @Test
    fun `a step larger than the grid is one partial cell and an overflowing boundary is rejected`() {
        val grid = SeriesGrid(253_402_300_700_000L, 1_000, 50)
        assertEquals(ValuesPlan(900_000, 50, 0, 1, null, 50, 50), planValuesPage(grid, 900_000, null, null, null, 1))
        listOf(Long.MIN_VALUE + 1, -9_223_370_525_796_050_616L, grid.startMs - 1).forEach { to ->
            assertFalse(assertThrows(SeriesQueryException::class.java) { planValuesPage(grid, null, null, to, null, 1) }.tooLarge)
        }
        assertFalse(assertThrows(SeriesQueryException::class.java) { planValuesPage(grid, null, grid.endMs, null, null, 1) }.tooLarge)
    }

    @Test
    fun `plan pages by limit and rejects series times limit above the cell cap as too large`() {
        val first = planValuesPage(SeriesGrid(0, 15_000, 50), null, null, null, 20, 1)
        assertEquals(300_000L, first.nextFromMs)
        assertEquals(20, first.cellCount)
        assertTrue(
            assertThrows(SeriesQueryException::class.java) {
                planValuesPage(SeriesGrid(0, 1_000, 100_000), null, null, null, 3_200, 32)
            }.tooLarge,
        )
        assertFalse(
            assertThrows(SeriesQueryException::class.java) { planValuesPage(SeriesGrid(0, 1_000, 10), null, null, null, 0, 1) }.tooLarge,
        )
    }

    @Test
    fun `the reducer follows interval max and interval min`() {
        assertEquals(SeriesReducer.MEAN, reducerFor(ResourceAggregation.INTERVAL_MEAN))
        assertEquals(SeriesReducer.MEAN, reducerFor(ResourceAggregation.INTERVAL_RATE))
        assertEquals(SeriesReducer.MAX, reducerFor(ResourceAggregation.INTERVAL_MAX))
        assertEquals(SeriesReducer.MIN, reducerFor(ResourceAggregation.INTERVAL_MIN))
    }

    @Test
    fun `catalog uses strict after ordering and counts observed cells`() {
        val snapshot = snapshot()
        val first = catalogJson(snapshot, "hash", null, 1)
        assertEquals("b", first.getValue("next_after").jsonPrimitive.content)
        val entry =
            first
                .getValue("series")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("b", entry.getValue("id").jsonPrimitive.content)
        assertEquals("mean", entry.getValue("reducer").jsonPrimitive.content)
        assertEquals(
            2,
            entry
                .getValue("observed_cells")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            "node",
            entry
                .getValue("labels")
                .jsonObject
                .getValue("host")
                .jsonPrimitive.content,
        )
        assertEquals(
            "steady",
            first
                .getValue("windows")
                .jsonArray
                .single()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        val second = catalogJson(snapshot, "hash", "c", 1)
        assertEquals(
            "d",
            second
                .getValue("series")
                .jsonArray
                .single()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        assertEquals(JsonNull, second.getValue("next_after"))
    }

    @Test
    fun `values preserve request order and emit observed only for coarse cells`() {
        val snapshot = snapshot()
        val direct = valuesJson(snapshot, "hash", listOf("d", "b"), planValuesPage(SeriesGrid(0, 1_000, 3), null, null, null, null, 2))
        val directSeries = direct.getValue("series").jsonArray
        assertEquals(
            "d",
            directSeries
                .first()
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        assertFalse(directSeries.first().jsonObject.containsKey("observed"))
        assertEquals(
            JsonNull,
            directSeries
                .first()
                .jsonObject
                .getValue("values")
                .jsonArray[1],
        )
        val coarse = valuesJson(snapshot, "hash", listOf("b"), planValuesPage(SeriesGrid(0, 1_000, 3), 2_000, null, null, null, 1))
        assertEquals(
            "2",
            coarse
                .getValue("series")
                .jsonArray
                .single()
                .jsonObject
                .getValue("observed")
                .jsonArray[0]
                .jsonPrimitive.content,
        )
        assertEquals(
            "1",
            coarse
                .getValue("grid")
                .jsonObject
                .getValue("last_cell_source_cells")
                .jsonPrimitive.content,
        )
    }

    private fun snapshot(): ResourceSnapshotV1 =
        ResourceSnapshotV1(
            "resource-snapshot.v1",
            "0".repeat(64),
            0,
            1_000,
            3,
            listOf(
                series("b", listOf(BigDecimal.ONE, BigDecimal(3), null)),
                series("d", listOf(BigDecimal(2), null, BigDecimal(4))),
            ),
            listOf(ResourceWindowV1("steady", 0, 3_000)),
            emptyList(),
            null,
        )

    private fun series(
        id: String,
        values: List<BigDecimal?>,
    ): ResourceSeriesV1 =
        ResourceSeriesV1(id, "cpu", "ratio", "vm", ResourceRole.SYSTEM, ResourceAggregation.INTERVAL_MEAN, mapOf("host" to "node"), values)
}

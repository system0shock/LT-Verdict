package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** Plan W4.1, tasks 2 and 3: the method rps-plateau-segmentation.v1 (ADR 0036, D3-D7). */
class StageProposalTest {
    private val anchor: LoadSecondSeries by lazy {
        val read = readLoadSecondSeries(SourceType.JMETER_CSV, Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))
        check(read is LoadSecondSeriesRead.Series) { "$read" }
        read.series
    }

    @Test
    fun `the anchor fixture uses five second cells`() {
        assertEquals(5, chooseCellSeconds(anchor))
        assertArrayEquals(LongArray(8) { 10 } + LongArray(12) { 50 } + LongArray(3) { 10 }, cellCounts(anchor, 5))
    }

    @Test
    fun `the cell ladder respects the cell limit`() {
        assertEquals(60, chooseCellSeconds(flatSeries(86_400, 1000)))
        assertEquals(300, chooseCellSeconds(flatSeries(604_800, 1000)))
    }

    @Test
    fun `a slow run has no cell width`() {
        val counts = LongArray(3600) { if (it % 10 == 0) 1L else 0L }

        assertNull(chooseCellSeconds(LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + 3_599_100, counts, false)))
    }

    @Test
    fun `noise is the robust spread of first differences`() {
        assertEquals(1.0, noiseCounts(longArrayOf(50, 50, 50, 90, 90, 90)))
        assertEquals(1.4826 * 2 / Math.sqrt(2.0), noiseCounts(longArrayOf(100, 94, 92, 92, 94, 100)), 1e-9)
        assertEquals(2.0967, noiseCounts(longArrayOf(100, 94, 92, 92, 94, 100)), 1e-4)
    }

    @Test
    fun `segmentation finds the anchor steps`() {
        assertEquals(listOf(0 until 8, 8 until 20, 20 until 23), segmentCounts(cellCounts(anchor, 5), 1.0))
    }

    @Test
    fun `a straight ramp is one segment`() {
        assertEquals(listOf(0 until 200), segmentCounts(LongArray(200) { 2L * it }, 1.0))
    }

    @Test
    fun `segments are never shorter than three cells`() {
        val counts = LongArray(20) { 50 } + LongArray(2) { 90 } + LongArray(20) { 50 }

        val segments = segmentCounts(counts, noiseCounts(counts))

        assertTrue(segments.all { it.last - it.first + 1 >= 3 }, "$segments")
        assertEquals(0, segments.first().first)
        assertEquals(counts.size, segments.last().last + 1)
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
    }

    private fun flatSeries(
        seconds: Int,
        rps: Long,
    ) = LoadSecondSeries(DEFAULT_T0, DEFAULT_T0 + seconds * 1000L, LongArray(seconds + 1) { if (it < seconds) rps else 0L }, false)
}

package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Plan W4.1, task 1: the per-second request series of a stage proposal (ADR 0036, D2). */
class StageProposalSeriesTest {
    @TempDir
    lateinit var tempDir: Path

    private val t0 = DEFAULT_T0
    private val small = StageProfile("small", listOf(RatePiece(40, 5.0, 5.0)))

    @Test
    fun `the run start is the earliest sample of any kind`() {
        val container = small.writeJtl(tempDir.resolve("container.jtl"), t0, listOf(containerRow(t0 - 30_000)))
        val setUp = small.writeJtl(tempDir.resolve("setup.jtl"), t0, listOf(requestRow(t0 - 30_000, "setUp", 50)))

        val withContainer = series(container)
        val withSetUp = series(setUp)

        assertEquals(t0 - 30_000, withContainer.runStartEpochMillis)
        assertEquals(t0 + 39_800 + ELAPSED_MILLIS, withContainer.runEndEpochMillis)
        assertFalse(withContainer.degraded)
        assertEquals(List(30) { 0L }, withContainer.requestsPerSecond.take(30))
        assertEquals(5L, withContainer.requestsPerSecond[30])
        assertEquals(t0 - 30_000, withSetUp.runStartEpochMillis)
        assertEquals(1L, withSetUp.requestsPerSecond[0])
        assertEquals(List(29) { 0L }, withSetUp.requestsPerSecond.drop(1).take(29))
    }

    @Test
    fun `requests are counted by start in seconds from the run start`() {
        val jtl = jtl("three.jtl", requestRow(t0), requestRow(t0 + 999), requestRow(t0 + 1000))

        val read = series(jtl)

        assertEquals(t0, read.runStartEpochMillis)
        assertEquals(t0 + 1100, read.runEndEpochMillis)
        assertArrayEquals(longArrayOf(2, 1), read.requestsPerSecond)
    }

    @Test
    fun `row order does not change the series`() {
        val rows = small.startOffsetsMillis().map { requestRow(t0 + it) }
        val forward = series(jtl("forward.jtl", *rows.toTypedArray()))
        val backward = series(jtl("backward.jtl", *rows.reversed().toTypedArray()))

        assertEquals(forward.runStartEpochMillis, backward.runStartEpochMillis)
        assertEquals(forward.runEndEpochMillis, backward.runEndEpochMillis)
        assertArrayEquals(forward.requestsPerSecond, backward.requestsPerSecond)
        assertArrayEquals(small.series(t0).requestsPerSecond, forward.requestsPerSecond)
    }

    @Test
    fun `an invalid input is refused`() {
        val jtl = jtl("invalid.jtl", requestRow(t0), requestRow(t0 + 10).replaceFirst("${t0 + 10}", "soon"), requestRow(t0 + 20))

        assertEquals(LoadSecondSeriesRead.Refused("INPUT_INVALID"), readLoadSecondSeries(SourceType.JMETER_CSV, jtl))
    }

    @Test
    fun `a file without requests is refused`() {
        // Ruling (plan W4.1, task 1): a JTL of container rows only is not recognised as containers (JtlCsvParser marks parents
        // only when the file mixes parent and other rows), so the refusal is checked on a Gatling log with group rows only.
        val log =
            Files.writeString(
                tempDir.resolve("groups.log"),
                "RUN\tsim.Simulation\tsim\t$t0\t\t3.10.5\n" +
                    "GROUP\tcheckout\t${t0 + 100}\t${t0 + 300}\t150\tOK\n" +
                    "GROUP\tcheckout\t${t0 + 400}\t${t0 + 600}\t150\tOK\n",
            )

        assertEquals(LoadSecondSeriesRead.Refused("NO_REQUEST_SAMPLES"), readLoadSecondSeries(SourceType.GATLING_TEXT, log))
    }

    @Test
    fun `a run of exactly seven days is read and one millisecond more is refused`() {
        val week = 604_800_000L
        val exact = jtl("week.jtl", requestRow(t0), requestRow(t0 + week - ELAPSED_MILLIS))
        val longer = jtl("longer.jtl", requestRow(t0), requestRow(t0 + week - ELAPSED_MILLIS + 1))

        val read = series(exact)

        assertEquals(604_801, read.requestsPerSecond.size)
        assertEquals(1L, read.requestsPerSecond[0])
        assertEquals(1L, read.requestsPerSecond[604_799])
        assertEquals(LoadSecondSeriesRead.Refused("RUN_TOO_LONG"), readLoadSecondSeries(SourceType.JMETER_CSV, longer))
    }

    private fun jtl(
        name: String,
        vararg rows: String,
    ): Path = Files.writeString(tempDir.resolve(name), (listOf(JTL_HEADER) + rows).joinToString("\n", postfix = "\n"))

    private fun series(path: Path): LoadSecondSeries {
        val read = readLoadSecondSeries(SourceType.JMETER_CSV, path)
        check(read is LoadSecondSeriesRead.Series) { "expected a series, got $read" }
        return read.series
    }
}

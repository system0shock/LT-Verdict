package io.ltverdict.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.floor

// Synthetic load profiles for the stage proposal (plan W4.1, section «Фикстуры»): a piecewise-linear rate in rps, turned into
// whole requests per second by flooring the cumulative integral, and written as a 17-column JMeter CSV like
// fixtures/stages/ramp-steady-rampdown.jtl.

internal data class RatePiece(
    val seconds: Int,
    val fromRps: Double,
    val toRps: Double,
)

internal data class StageProfile(
    val name: String,
    val pieces: List<RatePiece>,
    val jitter: Double = 0.0,
    val seed: Long = 1L,
) {
    val seconds: Int get() = pieces.sumOf { it.seconds }

    /** Requests started in generator second `s` (generator time, not the run start). */
    fun requestsPerSecond(): LongArray {
        val counts = LongArray(seconds)
        var random = seed
        var cumulative = 0.0
        var second = 0
        for (piece in pieces) {
            for (local in 0 until piece.seconds) {
                val from = piece.fromRps + (piece.toRps - piece.fromRps) * local / piece.seconds
                val to = piece.fromRps + (piece.toRps - piece.fromRps) * (local + 1) / piece.seconds
                var expected = (from + to) / 2.0
                if (jitter > 0.0) {
                    random = random xor (random shl 13)
                    random = random xor (random ushr 7)
                    random = random xor (random shl 17)
                    val u = (random ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
                    expected *= 1.0 + jitter * u
                }
                val before = floor(cumulative).toLong()
                cumulative += expected
                counts[second] = floor(cumulative).toLong() - before
                second++
            }
        }
        return counts
    }

    /** Start offsets of every request in milliseconds of generator time, evenly spread inside each second. */
    fun startOffsetsMillis(): List<Long> {
        val counts = requestsPerSecond()
        val starts = ArrayList<Long>()
        counts.forEachIndexed { second, count ->
            for (i in 0 until count) starts += second * 1000L + (1000L * i) / count
        }
        return starts
    }

    /** Generator time of the first request: the run start of the written JTL is `t0` plus this. */
    fun firstRequestOffsetMillis(): Long = startOffsetsMillis().first()

    /** The series [readLoadSecondSeries] builds from [writeJtl] without the file. */
    fun series(t0: Long = DEFAULT_T0): LoadSecondSeries {
        val starts = startOffsetsMillis()
        val first = starts.first()
        val end = starts.maxOf { it + ELAPSED_MILLIS }
        val counts = LongArray(((end - first) / 1000L).toInt() + 1)
        starts.forEach { counts[((it - first) / 1000L).toInt()]++ }
        return LoadSecondSeries(t0 + first, t0 + end, counts, false)
    }

    fun writeJtl(
        path: Path,
        t0: Long = DEFAULT_T0,
        extraRows: List<String> = emptyList(),
    ): Path {
        val text = StringBuilder(JTL_HEADER).append('\n')
        extraRows.forEach { text.append(it).append('\n') }
        startOffsetsMillis().forEach { text.append(requestRow(t0 + it)).append('\n') }
        Files.writeString(path, text)
        return path
    }

    /** Plateaus of the generator (flat pieces with load) as offsets in milliseconds of generator time. */
    fun truthSteady(): List<LongRange> {
        val plateaus = ArrayList<LongRange>()
        var at = 0L
        for (piece in pieces) {
            val end = at + piece.seconds * 1000L
            if (piece.fromRps == piece.toRps && piece.fromRps > 0.0) plateaus += at until end
            at = end
        }
        return plateaus
    }

    fun trueRpsAt(offsetMillis: Long): Double {
        var at = 0L
        for (piece in pieces) {
            val end = at + piece.seconds * 1000L
            if (offsetMillis < end) return piece.fromRps + (piece.toRps - piece.fromRps) * (offsetMillis - at) / (end - at)
            at = end
        }
        return pieces.last().toRps
    }
}

internal const val DEFAULT_T0 = 1_767_225_600_000L
internal const val ELAPSED_MILLIS = 100L
internal const val JTL_HEADER =
    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes," +
        "grpThreads,allThreads,URL,Latency,IdleTime,Connect"

internal fun requestRow(
    start: Long,
    label: String = "GET /items",
    elapsed: Long = ELAPSED_MILLIS,
) = "$start,$elapsed,$label,200,OK,stages 1-1,text,true,,128,64,1,1,http://example.test/items,50,0,5"

/** A JMeter transaction controller parent row (fixtures/slice1/jmeter/csv-tc-parent-subresults-true-5.6.3). */
internal fun containerRow(start: Long) =
    "$start,100,TC-parent,200,\"Number of samples in transaction : 1, number of failing samples : 0\",stages 1-1,,true,,128,64,1,1,null,0,0,5"

private fun flat(
    seconds: Int,
    rps: Double,
) = RatePiece(seconds, rps, rps)

private fun ramp(
    seconds: Int,
    from: Double,
    to: Double,
) = RatePiece(seconds, from, to)

internal object StageProfiles {
    val f1 = StageProfile("F1 ramp steady ramp-down", listOf(ramp(120, 0.0, 100.0), flat(600, 100.0), ramp(60, 100.0, 0.0)))
    val f2 =
        StageProfile(
            "F2 capacity steps",
            buildList {
                add(ramp(30, 0.0, 20.0))
                for (level in 1..5) {
                    if (level > 1) add(ramp(30, 20.0 * (level - 1), 20.0 * level))
                    add(flat(300, 20.0 * level))
                }
                add(ramp(30, 100.0, 0.0))
            },
        )
    val f3 =
        StageProfile(
            "F3 soak with a mid-run drop",
            listOf(ramp(60, 0.0, 50.0), flat(2340, 50.0), flat(1200, 35.0), ramp(60, 35.0, 0.0)),
        )
    val f4 = StageProfile("F4 continuous ramp", listOf(ramp(1200, 0.0, 200.0)))
    val f5 = f1.copy(name = "F5 noisy plateau", jitter = 0.15, seed = 42L)
    val f6 = StageProfile("F6 very short run", listOf(flat(45, 50.0)))
    val f6b = StageProfile("F6b short but valid run", listOf(ramp(20, 0.0, 60.0), flat(50, 60.0), ramp(20, 60.0, 0.0)))
    val f8 =
        StageProfile(
            "F8 two tests in one file",
            listOf(ramp(60, 0.0, 100.0), flat(300, 100.0), flat(60, 0.0), flat(300, 100.0), ramp(60, 100.0, 0.0)),
        )
    val f9 = StageProfile("F9 steady to the end", listOf(ramp(60, 0.0, 80.0), flat(600, 80.0)))
    val f10 = StageProfile("F10 long decline at the end", listOf(ramp(60, 0.0, 100.0), flat(600, 100.0), ramp(600, 100.0, 0.0)))
    val f11 =
        StageProfile(
            "F11 too many steps",
            buildList {
                add(ramp(10, 0.0, 10.0))
                for (level in 1..16) {
                    if (level > 1) add(ramp(10, 10.0 * (level - 1), 10.0 * level))
                    add(flat(60, 10.0 * level))
                }
                add(ramp(10, 160.0, 0.0))
            },
        )
    val f12 =
        StageProfile(
            "F12 warm-up below half",
            listOf(flat(120, 30.0), ramp(30, 30.0, 100.0), flat(1200, 100.0), ramp(60, 100.0, 0.0)),
        )
}

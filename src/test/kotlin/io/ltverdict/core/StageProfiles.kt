package io.ltverdict.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.exp
import kotlin.math.floor

// Synthetic load profiles for the stage proposal (plan W4.1, section «Фикстуры»): a piecewise-linear rate in rps, turned into
// whole requests per second by flooring the cumulative integral, and written as a 17-column JMeter CSV like
// fixtures/stages/ramp-steady-rampdown.jtl.

internal data class RatePiece(
    val seconds: Int,
    val fromRps: Double,
    val toRps: Double,
    /** `elapsed` of every request of the piece, written to the JTL. */
    val latencyMs: Long = ELAPSED_MILLIS,
    /** Share of failed requests, every k-th request of the piece fails deterministically. */
    val errorRatio: Double = 0.0,
)

/** One generated request: start in generator time, `elapsed`, `success`. */
internal data class GeneratedRequest(
    val offsetMillis: Long,
    val elapsedMillis: Long,
    val success: Boolean,
)

internal data class StageProfile(
    val name: String,
    val pieces: List<RatePiece>,
    val jitter: Double = 0.0,
    val seed: Long = 1L,
    /** When set, the count of each second is a Poisson draw around the expected count (deterministic xorshift64). */
    val poissonSeed: Long? = null,
) {
    val seconds: Int get() = pieces.sumOf { it.seconds }

    /** Requests started in generator second `s` (generator time, not the run start). */
    fun requestsPerSecond(): LongArray {
        val counts = LongArray(seconds)
        var random = poissonSeed ?: seed
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
                if (poissonSeed != null) {
                    random = random xor (random shl 13)
                    random = random xor (random ushr 7)
                    random = random xor (random shl 17)
                    val u = (random ushr 11).toDouble() / (1L shl 53).toDouble()
                    var k = 0L
                    var p = exp(-expected)
                    var cumulativeP = p
                    while (u > cumulativeP && k < 100_000) {
                        k++
                        p *= expected / k
                        cumulativeP += p
                    }
                    counts[second] = k
                } else {
                    val before = floor(cumulative).toLong()
                    cumulative += expected
                    counts[second] = floor(cumulative).toLong() - before
                }
                second++
            }
        }
        return counts
    }

    /** Every request, evenly spread inside its second, with the latency and the failures of its piece. */
    fun requests(): List<GeneratedRequest> {
        val counts = requestsPerSecond()
        val requests = ArrayList<GeneratedRequest>()
        var second = 0
        for (piece in pieces) {
            var index = 0L
            for (local in 0 until piece.seconds) {
                val count = counts[second]
                for (i in 0 until count) {
                    val failed = floor((index + 1) * piece.errorRatio) > floor(index * piece.errorRatio)
                    requests += GeneratedRequest(second * 1000L + (1000L * i) / count, piece.latencyMs, !failed)
                    index++
                }
                second++
            }
        }
        return requests
    }

    /** Start offsets of every request in milliseconds of generator time, evenly spread inside each second. */
    fun startOffsetsMillis(): List<Long> = requests().map { it.offsetMillis }

    /** Generator time of the first request: the run start of the written JTL is `t0` plus this. */
    fun firstRequestOffsetMillis(): Long = startOffsetsMillis().first()

    /** The series [readLoadSecondSeries] builds from [writeJtl] without the file. */
    fun series(t0: Long = DEFAULT_T0): LoadSecondSeries {
        val requests = requests()
        val first = requests.first().offsetMillis
        val end = requests.maxOf { it.offsetMillis + it.elapsedMillis }
        val counts = LongArray(((end - first) / 1000L).toInt() + 1)
        val failures = LongArray(counts.size)
        val elapsed = LongArray(counts.size)
        requests.forEach {
            val second = ((it.offsetMillis - first) / 1000L).toInt()
            counts[second]++
            if (!it.success) failures[second]++
            elapsed[second] += it.elapsedMillis
        }
        return LoadSecondSeries(t0 + first, t0 + end, counts, false, failures, elapsed)
    }

    fun writeJtl(
        path: Path,
        t0: Long = DEFAULT_T0,
        extraRows: List<String> = emptyList(),
    ): Path {
        val text = StringBuilder(JTL_HEADER).append('\n')
        extraRows.forEach { text.append(it).append('\n') }
        requests().forEach {
            val row = requestRow(t0 + it.offsetMillis, elapsed = it.elapsedMillis)
            text
                .append(
                    if (it.success) row else row.replace(",200,OK,stages 1-1,text,true,", ",500,Error,stages 1-1,text,false,"),
                ).append('\n')
        }
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

    /** F13: the 8-hour shape of the coordinator (2026-11-19): two plateaus, a 30-minute decline, a 3-hour soak whose rate drifts down. */
    val f13 = soak(0.14)

    /** F13 with a soak of the given total drift (relative to its mean level 21.5 rps). */
    fun soak(drift: Double) =
        StageProfile(
            "F13 soak after a decline, drift $drift",
            listOf(
                ramp(1800, 0.0, 24.0),
                flat(7200, 24.0),
                ramp(1800, 24.0, 36.0),
                flat(5400, 36.0),
                ramp(1800, 36.0, 21.5 * (1 + drift / 2)),
                ramp(10800, 21.5 * (1 + drift / 2), 21.5 * (1 - drift / 2)),
            ),
        )

    // Second review round (coordinator 2026-11-19): Poisson counts, latency and failures per piece.

    /** Poisson variant of a noise-free profile. */
    fun poisson(
        profile: StageProfile,
        seed: Long = 11L,
    ) = profile.copy(name = "${profile.name}, Poisson seed $seed", poissonSeed = seed)

    /** R1: a plateau, then a slow collapse with latency 1500 ms and 10 % errors and no stop phase. */
    fun collapse(
        fallSeconds: Int,
        toRps: Double,
    ) = StageProfile(
        "R1 collapse ${fallSeconds}s to $toRps",
        listOf(ramp(120, 0.0, 100.0), flat(3600, 100.0), RatePiece(fallSeconds, 100.0, toRps, 1500, 0.10)),
        poissonSeed = 11L,
    )

    /** R1: a 4-hour soak at 30 rps, then a 3000 s collapse to 10 rps. */
    val soakCollapse =
        StageProfile(
            "R1 soak then collapse",
            listOf(ramp(120, 0.0, 30.0), flat(14_400, 30.0), RatePiece(3000, 30.0, 10.0, 1500, 0.10)),
            poissonSeed = 11L,
        )

    /** R1: a plateau, then a 10-minute hang at 1 rps. */
    val hang =
        StageProfile(
            "R1 hang",
            listOf(ramp(120, 0.0, 100.0), flat(1800, 100.0), RatePiece(600, 1.0, 1.0, 1500, 0.10)),
            poissonSeed = 11L,
        )

    /** R2: five plateaus at 100 rps, each followed by a 120 s fall to 60 rps at 2000 ms, then a last plateau. */
    val equalDips =
        StageProfile(
            "R2 equal plateaus with falls",
            buildList {
                repeat(5) {
                    add(flat(180, 100.0))
                    add(RatePiece(120, 100.0, 60.0, 2000))
                }
                add(flat(180, 100.0))
            },
            poissonSeed = 11L,
        )

    /** R2: two equal 900 s plateaus with a 240 s dip between them. */
    fun twoEqual(seed: Long) =
        StageProfile(
            "R2 two equal plateaus, seed $seed",
            listOf(flat(900, 100.0), ramp(120, 100.0, 60.0), ramp(120, 60.0, 100.0), flat(900, 100.0)),
            poissonSeed = seed,
        )

    /** R3: a plateau, then a 180 s rise to 200 rps at 3000 ms, run aborted. */
    val riseAbort =
        StageProfile(
            "R3 rise after the plateau",
            listOf(ramp(60, 0.0, 100.0), flat(600, 100.0), RatePiece(180, 100.0, 200.0, 3000)),
            poissonSeed = 11L,
        )

    /** R3: a plateau, then a 2400 s creep from 100 to 125 rps. */
    val creep =
        StageProfile(
            "R3 creep",
            listOf(ramp(60, 0.0, 100.0), flat(1200, 100.0), ramp(2400, 100.0, 125.0)),
            poissonSeed = 11L,
        )

    /** R4: 1800 s at 100 rps, then an abrupt step down to a 120 s plateau at 60 rps with latency 1500 ms. */
    val stepDownAbrupt =
        StageProfile(
            "R4 abrupt step down",
            listOf(ramp(60, 0.0, 100.0), flat(1800, 100.0), RatePiece(120, 60.0, 60.0, 1500), ramp(60, 60.0, 0.0)),
            poissonSeed = 11L,
        )

    /** R4: the same with a 120 s fall between the levels. */
    val stepDown120 =
        StageProfile(
            "R4 step down over 120 s",
            listOf(ramp(60, 0.0, 100.0), flat(1800, 100.0), ramp(120, 100.0, 60.0), RatePiece(120, 60.0, 60.0, 1500), ramp(60, 60.0, 0.0)),
            poissonSeed = 11L,
        )

    /** R4: up, down, up with abrupt edges. */
    val upDownUp =
        StageProfile(
            "R4 up down up",
            listOf(ramp(60, 0.0, 100.0), flat(600, 100.0), flat(600, 60.0), flat(600, 100.0), ramp(60, 100.0, 0.0)),
            poissonSeed = 11L,
        )

    /** R2: a step down before the highest step: 100, then 60, then 120 rps. */
    val downThenUp =
        StageProfile(
            "R2 down then up",
            listOf(ramp(60, 0.0, 100.0), flat(600, 100.0), flat(600, 60.0), flat(600, 120.0), ramp(60, 120.0, 0.0)),
            poissonSeed = 11L,
        )

    /** R5: long low-rate sections around a 100 rps plateau. */
    val lowRateSections =
        StageProfile(
            "R5 low-rate sections",
            listOf(flat(1800, 5.0), ramp(60, 5.0, 100.0), flat(1800, 100.0), flat(1800, 5.0)),
            poissonSeed = 11L,
        )

    /** R6: a warm-up at 40 % for 15 minutes before a 1000 s plateau. */
    val warmUp40 =
        StageProfile(
            "R6 warm-up at 40 %",
            listOf(flat(900, 40.0), ramp(60, 40.0, 100.0), flat(1000, 100.0), ramp(60, 100.0, 0.0)),
            poissonSeed = 11L,
        )
}

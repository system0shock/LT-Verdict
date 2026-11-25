package io.ltverdict.core

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

class CapacityKneeCalibrationTest {
    @Test
    fun `calibrated knee detector achieves high recall and low false positive rate on seeded hinge, plateau and null corpus`() {
        val rng = Random(20261125L)
        val trueCurves = mutableListOf<Pair<List<KneePoint>, Int>>()
        val nullCurves = mutableListOf<List<KneePoint>>()

        // 80 hinge & ramp curves across 5..10 stages
        repeat(80) { index ->
            val stages = listOf(5, 6, 7, 8, 10)[index % 5]
            val knot = 2 + rng.nextInt(stages - 3)
            val base = 30.0 + rng.nextDouble() * 90.0
            val slope = rng.nextDouble() * 0.002
            val jump = 2.8 + rng.nextDouble() * 35.0
            val cv = 0.01 + rng.nextDouble() * 0.06
            val points =
                (0 until stages).map { i ->
                    val load = 20.0 + i * 20.0
                    var meanY = base * exp(slope * load)
                    if (i > knot) {
                        val frac = (i - knot).toDouble() / (stages - 1 - knot).coerceAtLeast(1)
                        meanY *= Math.pow(jump, frac)
                        if (jump < 5.0) meanY *= 1.5
                    }
                    val noisy = (meanY * exp(rng.nextGaussian() * cv)).roundToLong().coerceAtLeast(1L)
                    KneePoint("s-$i", BigDecimal.valueOf(load), noisy)
                }
            trueCurves += points to knot
        }

        // 30 plateau curves (top plateau & middle plateau)
        repeat(30) { index ->
            val stages = listOf(5, 6, 7)[index % 3]
            val topPlateau = index % 2 == 0
            val knot = if (topPlateau) stages - 2 else 3
            val loads =
                if (topPlateau) {
                    List(stages - 1) { 20.0 + it * 20.0 } + listOf(20.0 + (stages - 2) * 20.0)
                } else {
                    listOf(20.0, 40.0, 40.0, 60.0) + List(stages - 4) { 80.0 + it * 20.0 }
                }
            val base = 40.0 + rng.nextDouble() * 40.0
            val jump = 4.0 + rng.nextDouble() * 25.0
            val cv = 0.01 + rng.nextDouble() * 0.04
            val points =
                loads.mapIndexed { i, load ->
                    val factor = if (i > knot) Math.pow(jump, (i - knot).toDouble()) else 1.0
                    val noisy = (base * factor * exp(rng.nextGaussian() * cv)).roundToLong().coerceAtLeast(1L)
                    KneePoint("p-$i", BigDecimal.valueOf(load), noisy)
                }
            trueCurves += points to knot
        }

        // 100 primary null curves (flat noisy, linear drift, mild exponential, plateau without break)
        repeat(100) { index ->
            val stages = listOf(5, 6, 7, 8)[index % 4]
            val kind = index % 4
            val loads =
                if (kind == 3) {
                    List(stages - 1) { 20.0 + it * 20.0 } + listOf(20.0 + (stages - 2) * 20.0)
                } else {
                    List(stages) { 20.0 + it * 20.0 }
                }
            val base = 40.0 + rng.nextDouble() * 60.0
            val cv = 0.02 + rng.nextDouble() * 0.08
            nullCurves +=
                loads.mapIndexed { i, load ->
                    val meanY =
                        when (kind) {
                            0 -> base
                            1 -> base * (1.0 + 0.01 * (load - 20.0))
                            2 -> base * exp(0.004 * (load - 20.0))
                            else -> base * (1.0 + 0.003 * (load - 20.0))
                        }
                    val noisy = (meanY * exp(rng.nextGaussian() * cv)).roundToLong().coerceAtLeast(1L)
                    KneePoint("n-$i", BigDecimal.valueOf(load), noisy)
                }
        }

        // 20 middle-plateau-break curves where degradation begins on the second stage of an interior plateau
        repeat(20) { index ->
            val stages = listOf(5, 6, 7)[index % 3]
            val knot = 2 + rng.nextInt(stages - 3)
            val loads =
                List(stages) { i ->
                    when {
                        i <= knot -> 20.0 + i * 20.0
                        else -> 20.0 + (i - 1) * 20.0
                    }
                }
            val base = 40.0 + rng.nextDouble() * 40.0
            val jump = 4.0 + rng.nextDouble() * 25.0
            val cv = 0.01 + rng.nextDouble() * 0.04
            val points =
                loads.mapIndexed { i, load ->
                    val factor = if (i > knot) Math.pow(jump, (i - knot).toDouble()) else 1.0
                    val noisy = (base * factor * exp(rng.nextGaussian() * cv)).roundToLong().coerceAtLeast(1L)
                    KneePoint("mp-$i", BigDecimal.valueOf(load), noisy)
                }
            trueCurves += points to knot
        }

        // 200 independent held-out null curves (flat, linear, mild exponential, top plateau without break, near-zero-variance prefix)
        val heldOutRng = Random(20261126L)
        val heldOutNullCurves = mutableListOf<List<KneePoint>>()
        repeat(200) { index ->
            val stages = listOf(5, 6, 7, 8)[index % 4]
            val kind = index % 5
            val loads =
                when (kind) {
                    3 -> List(stages - 1) { 20.0 + it * 20.0 } + listOf(20.0 + (stages - 2) * 20.0)
                    4 -> listOf(20.0, 20.0, 20.1) + List(stages - 3) { 40.0 + it * 20.0 }
                    else -> List(stages) { 20.0 + it * 20.0 }
                }
            val base = 40.0 + heldOutRng.nextDouble() * 60.0
            val cv = 0.03 + heldOutRng.nextDouble() * 0.09
            heldOutNullCurves +=
                loads.mapIndexed { i, load ->
                    val meanY =
                        when (kind) {
                            0, 4 -> base
                            1 -> base * (1.0 + 0.01 * (load - 20.0))
                            2 -> base * exp(0.004 * (load - 20.0))
                            else -> base * (1.0 + 0.003 * (load - 20.0))
                        }
                    val noisy = (meanY * exp(heldOutRng.nextGaussian() * cv)).roundToLong().coerceAtLeast(1L)
                    KneePoint("h-$i", BigDecimal.valueOf(load), noisy)
                }
        }

        assertEquals(130, trueCurves.size)
        assertEquals(100, nullCurves.size)
        assertEquals(200, heldOutNullCurves.size)

        var detected = 0
        var exact = 0
        var withinOne = 0
        trueCurves.forEach { (points, expectedKnot) ->
            val result = evaluateCapacityKnee(CapacityLoadAxis.RPS, points, null)
            val status =
                result.evidence
                    .getValue("status")
                    .jsonPrimitive.content
            if (status == "DETECTED") {
                detected++
                val stableId =
                    result.evidence
                        .getValue("last_stable_stage_id")
                        .jsonPrimitive.content
                val gotKnot = points.indexOfFirst { it.stageId == stableId }
                if (gotKnot == expectedKnot) exact++
                if (abs(gotKnot - expectedKnot) <= 1) withinOne++
                assertEquals("KNEE_DETECTED", result.reason)
            }
        }

        var falsePositives = 0
        nullCurves.forEach { points ->
            val result = evaluateCapacityKnee(CapacityLoadAxis.RPS, points, null)
            val status =
                result.evidence
                    .getValue("status")
                    .jsonPrimitive.content
            if (status == "DETECTED") {
                falsePositives++
            }
        }

        var heldOutFalsePositives = 0
        heldOutNullCurves.forEach { points ->
            val result = evaluateCapacityKnee(CapacityLoadAxis.RPS, points, null)
            val status =
                result.evidence
                    .getValue("status")
                    .jsonPrimitive.content
            if (status == "DETECTED") {
                heldOutFalsePositives++
            }
        }

        val recall = detected.toDouble() / trueCurves.size
        val exactAcc = exact.toDouble() / trueCurves.size
        val withinOneAcc = withinOne.toDouble() / trueCurves.size
        val fpr = falsePositives.toDouble() / nullCurves.size
        val heldOutFpr = heldOutFalsePositives.toDouble() / heldOutNullCurves.size

        assertTrue(recall >= 0.95, "recall=$recall ($detected/${trueCurves.size})")
        assertTrue(exactAcc >= 0.90, "exact=$exactAcc ($exact/${trueCurves.size})")
        assertTrue(withinOneAcc >= 0.95, "withinOne=$withinOneAcc ($withinOne/${trueCurves.size})")
        assertTrue(fpr <= 0.03, "fpr=$fpr ($falsePositives/${nullCurves.size})")
        assertTrue(heldOutFpr <= 0.01, "heldOutFpr=$heldOutFpr ($heldOutFalsePositives/${heldOutNullCurves.size})")
        assertEquals(125, detected, "detected=$detected")
        assertEquals(125, exact, "exact=$exact")
        assertEquals(125, withinOne, "withinOne=$withinOne")
        assertEquals(0, falsePositives, "falsePositives=$falsePositives")
        assertEquals(0, heldOutFalsePositives, "heldOutFalsePositives=$heldOutFalsePositives")
    }
}

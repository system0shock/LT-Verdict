package io.ltverdict.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CapacityKneeTest {
    // Stage p95 of the demo scenario 2 (40..104 RPS): flat at 52-56 ms, then 11.5 s on the last stage.
    private val demo = listOf(39.8 to 52L, 59.8 to 52L, 79.8 to 52L, 89.7 to 53L, 95.745 to 56L, 103.745 to 11_536L)

    @Test
    fun `the demo capacity run puts the knee between the last normal stage and the first degraded one`() {
        val knee = detect(demo)

        assertEquals("DETECTED", knee.string("status"))
        assertEquals("stage-4", knee.string("last_stable_stage_id"))
        assertEquals("stage-5", knee.string("first_degraded_stage_id"))
        assertEquals("95.745", knee.string("last_stable_load"))
        assertEquals("103.745", knee.string("first_degraded_load"))
        assertEquals("HIGH", knee.string("confidence"))
        assertEquals(JsonPrimitive(true), knee.getValue("calibrated"))
        assertEquals("capacity_knee_diagnostic", knee.string("type"))
        assertEquals(emptyList<String>(), reasons(knee))
        assertEquals(6, knee.getValue("points").jsonArray.size)
    }

    @Test
    fun `a ramp of degraded stages is still located before its first stage`() {
        val knee = detect(listOf(40.0 to 52L, 60.0 to 52L, 80.0 to 52L, 90.0 to 53L, 96.0 to 56L, 104.0 to 400L, 110.0 to 11_536L))

        assertEquals("DETECTED", knee.string("status"))
        assertEquals("stage-4", knee.string("last_stable_stage_id"))
        assertEquals("stage-5", knee.string("first_degraded_stage_id"))
    }

    @Test
    fun `exactly flat data before the jump is detected and serialises canonically`() {
        val knee = detect(listOf(40.0 to 52L, 60.0 to 52L, 80.0 to 52L, 90.0 to 52L, 96.0 to 52L, 104.0 to 11_536L))

        assertEquals("DETECTED", knee.string("status"))
        assertEquals("stage-4", knee.string("last_stable_stage_id"))
        assertEquals("0", knee.string("sse_ratio"))
        val text = canonicalJson(knee).decodeToString()
        assertTrue(!text.contains("0.000000") && !text.contains("E-"), text)
        assertTrue(knee.string("excess_factor").let { !it.endsWith("0") || !it.contains('.') }, knee.string("excess_factor"))
    }

    @Test
    fun `smooth data is refused rather than guessed`() {
        fun refusal(values: List<Long>) = reasons(detect(listOf(40.0, 60.0, 80.0, 90.0, 96.0, 104.0).zip(values)))

        assertEquals(listOf("KNEE_NO_BREAK"), refusal(listOf(50, 51, 50, 52, 51, 50)))
        assertEquals(listOf("KNEE_NO_BREAK"), refusal(listOf(52, 55, 51, 56, 50, 54)))
        assertEquals(listOf("KNEE_BREAK_NOT_MATERIAL"), refusal(listOf(50, 60, 70, 80, 90, 100)))
        assertEquals(listOf("KNEE_BREAK_NOT_MATERIAL"), refusal(listOf(50, 52, 55, 60, 68, 80)))
        val refused = detect(listOf(40.0, 60.0, 80.0, 90.0, 96.0, 104.0).zip(listOf<Long>(50, 51, 50, 52, 51, 50)))
        assertEquals("NOT_DETECTED", refused.string("status"))
        assertEquals(JsonPrimitive(null as String?), refused.getValue("last_stable_stage_id"))
        assertEquals("NONE", refused.string("confidence"))
        assertEquals(JsonPrimitive(true), refused.getValue("calibrated"))
    }

    @Test
    fun `too few stages, non increasing load and caller refusals are explicit`() {
        assertEquals(listOf("KNEE_TOO_FEW_STAGES"), reasons(detect(demo.take(4))))
        assertEquals(
            listOf("KNEE_LOAD_NOT_INCREASING"),
            reasons(detect(listOf(40.0 to 52L, 84.0 to 52L, 80.0 to 52L, 90.0 to 53L, 96.0 to 56L, 104.0 to 11_536L))),
        )
        assertEquals(
            listOf("KNEE_LOAD_NOT_INCREASING"),
            reasons(detect(listOf(50.0 to 50L, 50.0 to 51L, 50.0 to 52L, 50.0 to 500L, 50.0 to 1000L))),
        )
        val refused = capacityKneeEvidence(CapacityLoadAxis.RPS, emptyList(), "KNEE_RUN_NOT_VALID")
        assertEquals(listOf("KNEE_RUN_NOT_VALID"), reasons(refused))
        assertEquals("NOT_DETECTED", refused.string("status"))
    }

    @Test
    fun `load plateaus in the middle, at the start, or at the top are supported without NaN`() {
        val middlePlateau = detect(listOf(40.0 to 52L, 60.0 to 52L, 60.0 to 53L, 80.0 to 54L, 96.0 to 56L, 104.0 to 11_536L))
        assertEquals("DETECTED", middlePlateau.string("status"))
        assertEquals("stage-4", middlePlateau.string("last_stable_stage_id"))

        val leftZeroVariance = detect(listOf(40.0 to 52L, 40.0 to 52L, 40.0 to 52L, 60.0 to 400L, 80.0 to 11_536L))
        assertEquals("DETECTED", leftZeroVariance.string("status"))
        assertEquals("stage-3", leftZeroVariance.string("last_stable_stage_id"))

        val topPlateau = detect(listOf(40.0 to 52L, 60.0 to 52L, 80.0 to 52L, 96.0 to 56L, 96.0 to 11_536L))
        assertEquals("DETECTED", topPlateau.string("status"))
        assertEquals("stage-3", topPlateau.string("last_stable_stage_id"))
        assertEquals("stage-4", topPlateau.string("first_degraded_stage_id"))
        assertEquals("96", topPlateau.string("last_stable_load"))
    }

    @Test
    fun `near-zero-variance left branches and negative slopes do not overflow or raise false positives`() {
        val realStep = detect(listOf(100.0 to 100L, 100.0 to 100L, 100.1 to 50L, 500.0 to 500L, 1000.0 to 5000L))
        assertEquals("DETECTED", realStep.string("status"))
        assertEquals("stage-2", realStep.string("last_stable_stage_id"))
        assertEquals("stage-3", realStep.string("first_degraded_stage_id"))

        val tinyDeltaCrash = detect(listOf(10.0 to 100L, 10.001 to 70L, 10.002 to 50L, 50.0 to 500L, 100.0 to 5000L))
        assertEquals("DETECTED", tinyDeltaCrash.string("status"))
        assertEquals("stage-2", tinyDeltaCrash.string("last_stable_stage_id"))

        val flatWithDip = detect(listOf(20.0 to 52L, 20.0 to 52L, 20.1 to 50L, 40.0 to 52L, 60.0 to 52L))
        assertEquals("NOT_DETECTED", flatWithDip.string("status"))
        assertEquals(listOf("KNEE_BREAK_NOT_MATERIAL"), reasons(flatWithDip))

        val uShape = detect(listOf(10.0 to 100L, 20.0 to 80L, 30.0 to 60L, 40.0 to 100L, 50.0 to 100L))
        assertEquals("NOT_DETECTED", uShape.string("status"))
    }

    @Test
    fun `middle-plateau jumps and equal-load tie-breaks locate the degraded stage accurately`() {
        val midPlateauAt3 = detect(listOf(40.0 to 50L, 60.0 to 50L, 80.0 to 50L, 80.0 to 5000L, 100.0 to 10_000L))
        assertEquals("DETECTED", midPlateauAt3.string("status"))
        assertEquals("stage-2", midPlateauAt3.string("last_stable_stage_id"))
        assertEquals("stage-3", midPlateauAt3.string("first_degraded_stage_id"))
        assertEquals("80", midPlateauAt3.string("last_stable_load"))
        assertEquals("80", midPlateauAt3.string("first_degraded_load"))

        val midPlateauAt4 = detect(listOf(40.0 to 50L, 60.0 to 50L, 80.0 to 50L, 80.0 to 50L, 100.0 to 5000L))
        assertEquals("DETECTED", midPlateauAt4.string("status"))
        assertEquals("stage-3", midPlateauAt4.string("last_stable_stage_id"))
        assertEquals("stage-4", midPlateauAt4.string("first_degraded_stage_id"))
        assertEquals("80", midPlateauAt4.string("last_stable_load"))
        assertEquals("100", midPlateauAt4.string("first_degraded_load"))

        val startPlateau4 = detect(listOf(40.0 to 50L, 40.0 to 50L, 40.0 to 50L, 40.0 to 50L, 100.0 to 5000L))
        assertEquals("NOT_DETECTED", startPlateau4.string("status"))
        assertEquals(listOf("KNEE_NO_BREAK"), reasons(startPlateau4))
    }

    @Test
    fun `the result is deterministic and carries its method parameters`() {
        val first = canonicalJson(detect(demo))
        val second = canonicalJson(detect(demo))

        assertArrayEquals(first, second)
        val knee = detect(demo)
        assertEquals("piecewise-hinge-ln-p95.v2", knee.string("method"))
        assertTrue(knee.containsKey("parameters"))
    }

    private fun detect(points: List<Pair<Double, Long>>): JsonObject =
        capacityKneeEvidence(
            CapacityLoadAxis.RPS,
            points.mapIndexed { index, (load, p95) -> KneePoint("stage-$index", BigDecimal.valueOf(load), p95) },
            null,
        )

    private fun reasons(knee: JsonObject) = knee.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
}

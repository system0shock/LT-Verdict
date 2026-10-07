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
        assertEquals("UNCALIBRATED", knee.string("confidence"))
        assertEquals(JsonPrimitive(false), knee.getValue("calibrated"))
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
    fun `smooth data is refused rather than guessed`() {
        fun refusal(values: List<Long>) = reasons(detect(listOf(40.0, 60.0, 80.0, 90.0, 96.0, 104.0).zip(values)))

        assertEquals(listOf("KNEE_NO_BREAK"), refusal(listOf(50, 51, 50, 52, 51, 50)))
        assertEquals(listOf("KNEE_NO_BREAK"), refusal(listOf(52, 55, 51, 56, 50, 54)))
        assertEquals(listOf("KNEE_BREAK_NOT_MATERIAL"), refusal(listOf(50, 60, 70, 80, 90, 100)))
        assertEquals(listOf("KNEE_BREAK_NOT_MATERIAL"), refusal(listOf(50, 52, 55, 60, 68, 80)))
        val refused = detect(listOf(40.0, 60.0, 80.0, 90.0, 96.0, 104.0).zip(listOf<Long>(50, 51, 50, 52, 51, 50)))
        assertEquals("NOT_DETECTED", refused.string("status"))
        assertEquals(JsonPrimitive(null as String?), refused.getValue("last_stable_stage_id"))
        assertEquals("UNCALIBRATED", refused.string("confidence"))
    }

    @Test
    fun `too few stages, non increasing load and caller refusals are explicit`() {
        assertEquals(listOf("KNEE_TOO_FEW_STAGES"), reasons(detect(demo.take(4))))
        assertEquals(
            listOf("KNEE_LOAD_NOT_INCREASING"),
            reasons(detect(listOf(40.0 to 52L, 84.0 to 52L, 80.0 to 52L, 90.0 to 53L, 96.0 to 56L, 104.0 to 11_536L))),
        )
        val refused = capacityKneeEvidence(CapacityLoadAxis.RPS, emptyList(), "KNEE_RUN_NOT_VALID")
        assertEquals(listOf("KNEE_RUN_NOT_VALID"), reasons(refused))
        assertEquals("NOT_DETECTED", refused.string("status"))
    }

    @Test
    fun `the result is deterministic and carries its method parameters`() {
        val first = canonicalJson(detect(demo))
        val second = canonicalJson(detect(demo))

        assertArrayEquals(first, second)
        val knee = detect(demo)
        assertEquals("piecewise-hinge-ln-p95.v1", knee.string("method"))
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

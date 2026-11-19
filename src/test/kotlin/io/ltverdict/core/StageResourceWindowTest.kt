package io.ltverdict.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0037, D2 and D5: the resource side of a steady stage is the full cells of the snapshot grid inside the stage. */
class StageResourceWindowTest {
    @Test
    fun `inner full cells of a steady stage`() {
        val resolved = resolveStageResourceWindows(grid(), listOf(window("steady", 40_000, 100_000)))

        assertEquals(listOf(window("steady", 45_000, 90_000)), resolved.windows)
        assertFalse(resolved.partial)
    }

    @Test
    fun `a stage on the grid keeps its bounds`() {
        val resolved = resolveStageResourceWindows(grid(), listOf(window("steady", 45_000, 90_000)))

        assertEquals(listOf(window("steady", 45_000, 90_000)), resolved.windows)
        assertFalse(resolved.partial)
    }

    @Test
    fun `a stage shorter than one full cell is refused`() {
        listOf(window("short", 16_000, 29_000), window("after", 130_000, 140_000), window("before", -40_000, -10_000)).forEach {
            val failure = assertThrows(IllegalArgumentException::class.java) { resolveStageResourceWindows(grid(), listOf(it)) }
            assertEquals("STAGE_WINDOW_NO_FULL_CELLS", failure.message, it.id)
        }
    }

    @Test
    fun `end drift past the grid end within one step is not partial`() {
        val resolved = resolveStageResourceWindows(grid(), listOf(window("steady", 45_000, 119_800)))

        // 119800 - 105000 < 15000: only the incomplete last cell is dropped
        assertEquals(listOf(window("steady", 45_000, 105_000)), resolved.windows)
        assertFalse(resolved.partial)
    }

    @Test
    fun `a grid that starts inside the stage is partial`() {
        val resolved = resolveStageResourceWindows(grid(start = T0 + 60_000), listOf(window("steady", 40_000, 100_000)))

        assertEquals(listOf(window("steady", 60_000, 90_000)), resolved.windows)
        assertTrue(resolved.partial)
    }

    @Test
    fun `a grid that ends inside the stage is partial`() {
        val resolved = resolveStageResourceWindows(grid(points = 5), listOf(window("steady", 40_000, 100_000)))

        // the grid ends at 75000: 100000 - 75000 >= 15000
        assertEquals(listOf(window("steady", 45_000, 75_000)), resolved.windows)
        assertTrue(resolved.partial)
    }

    @Test
    fun `touching steady stages do not share a cell`() {
        val resolved =
            resolveStageResourceWindows(grid(), listOf(window("steady-a", 40_000, 70_000), window("steady-b", 70_000, 100_000)))

        assertEquals(listOf(window("steady-a", 45_000, 60_000), window("steady-b", 75_000, 90_000)), resolved.windows)
        assertFalse(resolved.partial)
    }

    @Test
    fun `the stage binding carries resource bounds only with a snapshot`() {
        val declared =
            assertInstanceOf(
                LoadStagesValidation.Valid::class.java,
                validateLoadStages(ByteArrayInputStream(Files.readAllBytes(Path.of(RAMP_STEADY_DOWN)))),
            )
        val resolved = resolveStageWindows(declared.stages, T0, T0 + 119_800)

        val without = stageBindingEvidence(declared.sha256, resolved, T0, T0 + 119_800)
        assertEquals(
            Files.readString(Path.of("fixtures/stages/stage-binding.sample.json")).replace("\r\n", "\n"),
            canonicalJson(without).decodeToString(),
        )

        val resourceWindows = resolveStageResourceWindows(grid(), resolved.windows).windows
        val with = stageBindingEvidence(declared.sha256, resolved, T0, T0 + 119_800, resourceWindows)
        val stages = with.getValue("stages").jsonArray.map { it.jsonObject }
        val steady = stages.single { it.str("id") == "steady" }
        assertEquals((T0 + 45_000).toString(), steady.str("resource_from_epoch_ms"))
        assertEquals((T0 + 90_000).toString(), steady.str("resource_to_epoch_ms"))
        stages.filter { it.str("role") == "excluded" }.forEach {
            assertNull(it["resource_from_epoch_ms"], it.str("id"))
            assertNull(it["resource_to_epoch_ms"], it.str("id"))
        }
        // nothing else of the binding changes
        assertEquals(
            without.keys,
            with.keys,
        )
        assertEquals(JsonObject(without - "stages"), JsonObject(with - "stages"))
    }

    @Test
    fun `the resource binding says declared_stages`() {
        val snapshot = grid()
        val windows = listOf(window("steady-a", 45_000, 60_000), window("steady-b", 75_000, 90_000))

        val binding = resourceBindingEvidence(snapshot, windows, T0, T0 + 119_800, declaredStages = true)

        assertEquals("declared_stages", binding.str("mode"))
        assertEquals((T0 + 45_000).toString(), binding.str("evaluation_from_epoch_ms"))
        assertEquals((T0 + 90_000).toString(), binding.str("evaluation_to_epoch_ms"))
        listOf("dropped_leading_cells", "dropped_leading_millis", "dropped_trailing_cells", "dropped_trailing_millis").forEach {
            assertEquals("0", binding.str(it), it)
        }
        assertEquals("not_verified_by_core", binding.str("clock_alignment"))
        // without the flag a snapshot without windows is still run_intersection
        assertEquals("run_intersection", resourceBindingEvidence(snapshot, windows, T0, T0 + 119_800).str("mode"))
    }

    private fun grid(
        start: Long = T0,
        points: Int = 8,
    ): ResourceSnapshotV1 {
        val values = List(points) { "0.2" }.joinToString(",")
        val json =
            """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${"0".repeat(64)}",""" +
                """"start_epoch_ms":$start,"step_ms":15000,"point_count":$points,"series":[""" +
                """{"id":"cpu","metric":"cpu","unit":"ratio","entity":"orders","role":"system","aggregation":"interval_mean",""" +
                """"values":[$values]}],""" +
                """"provenance":{"source_kind":"fixture","query_semantics":"interval","clock_alignment":"unknown"}}"""
        return assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray())),
        ).snapshot
    }

    private fun window(
        id: String,
        from: Long,
        to: Long,
    ) = ResourceWindowV1(id, T0 + from, T0 + to)

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private companion object {
        const val T0 = 1_767_225_600_000L
        const val RAMP_STEADY_DOWN = "docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"
    }
}

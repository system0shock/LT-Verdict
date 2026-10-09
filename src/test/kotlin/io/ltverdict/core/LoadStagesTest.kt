package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class LoadStagesTest {
    @Test
    fun `contract valid examples are accepted with their stages in order`() {
        val ramp = valid(Files.readAllBytes(Path.of("$EXAMPLES/valid/ramp-steady-down.json")))
        val only = valid(Files.readAllBytes(Path.of("$EXAMPLES/valid/steady-only.json")))
        val two = valid(Files.readAllBytes(Path.of("$EXAMPLES/valid/two-steady.json")))

        assertEquals(listOf("ramp-up", "steady", "ramp-down"), ramp.stages.map(StageV1::id))
        assertEquals(listOf(StageRole.EXCLUDED, StageRole.STEADY, StageRole.EXCLUDED), ramp.stages.map(StageV1::role))
        assertEquals(40_000L, ramp.stages[1].fromOffsetMillis)
        assertEquals(100_000L, ramp.stages[1].toOffsetMillis)
        assertEquals(listOf("steady"), only.stages.map(StageV1::id))
        assertEquals(listOf("steady-a", "spike", "steady-b"), two.stages.map(StageV1::id))
    }

    @Test
    fun `every contract invalid example is rejected with the documented code`() {
        val expected =
            mapOf(
                "no-steady" to "STAGES_NO_STEADY",
                "overlap" to "OVERLAPPING_STAGES",
                "duplicate-id" to "DUPLICATE_STAGE_ID",
                "empty-window" to "INVALID_STAGE",
                "negative-offset" to "INVALID_STAGE_OFFSET",
                "fractional-offset" to "INVALID_TYPE",
                "role-auto" to "UNKNOWN_ROLE",
                "unknown-field" to "UNKNOWN_FIELD",
                "too-many-stages" to "RESOURCE_LIMIT_EXCEEDED",
                "wrong-version" to "INVALID_SCHEMA_VERSION",
            )

        val files =
            Files
                .list(
                    Path.of("$EXAMPLES/invalid"),
                ).use { paths -> paths.map { it.fileName.toString().removeSuffix(".json") }.toList() }
        assertEquals(expected.keys, files.toSet())
        expected.forEach { (name, code) ->
            assertEquals(code, firstError(Files.readAllBytes(Path.of("$EXAMPLES/invalid/$name.json"))).code, name)
        }
    }

    @Test
    fun `error pointers name the offending field`() {
        assertEquals(
            "/stages/1/from_offset_ms",
            firstError(declaration(stage("a", "excluded", 0, 40_000), stage("b", "steady", 30_000, 100_000))).jsonPointer,
        )
        assertEquals("/stages/1/id", firstError(declaration(stage("a", "steady", 0, 10), stage("a", "steady", 20, 30))).jsonPointer)
        assertEquals("/stages/0/role", firstError(declaration(stage("a", "auto", 0, 10))).jsonPointer)
        assertEquals("/stages/0/to_offset_ms", firstError(declaration(stage("a", "steady", 10, 10))).jsonPointer)
        assertEquals("/detect", firstError(Files.readAllBytes(Path.of("$EXAMPLES/invalid/unknown-field.json"))).jsonPointer)
        assertEquals("/stages", firstError(declaration()).jsonPointer)
    }

    @Test
    fun `the hash and the canonical bytes ignore key order, stage order and number spelling`() {
        val base = valid(declaration(stage("ramp", "excluded", 0, 40_000), stage("steady", "steady", 40_000, 100_000)))
        val reordered =
            valid(
                """{"stages":[{"to_offset_ms":100000,"role":"steady","id":"steady","from_offset_ms":40000},""" +
                    """{"to_offset_ms":40000,"id":"ramp","from_offset_ms":0,"role":"excluded"}],"schema_version":"load-stages.v1"}""",
            )
        val respelled = valid(declaration(stage("ramp", "excluded", 0, "4e4"), stage("steady", "steady", 40_000, "100000.0")))
        val shifted = valid(declaration(stage("ramp", "excluded", 0, 40_000), stage("steady", "steady", 40_000, 100_001)))

        assertEquals(base.sha256, reordered.sha256)
        assertEquals(base.sha256, respelled.sha256)
        assertNotEquals(base.sha256, shifted.sha256)
        assertArrayEquals(base.canonicalBytes(), reordered.canonicalBytes())
        assertEquals(sha256Hex(base.canonicalBytes()), base.sha256)
        assertEquals(listOf("ramp", "steady"), reordered.stages.map(StageV1::id))
    }

    @Test
    fun `canonical bytes are a copy`() {
        val valid = valid(declaration(stage("steady", "steady", 0, 10)))
        val exposed = valid.canonicalBytes()
        exposed[0] = '!'.code.toByte()

        assertEquals('{'.code.toByte(), valid.canonicalBytes()[0])
    }

    @Test
    fun `touching stages and gaps are allowed, overlap is not, also when declared out of order`() {
        valid(declaration(stage("a", "excluded", 0, 10), stage("b", "steady", 10, 20), stage("c", "excluded", 30, 40)))
        valid(declaration(stage("b", "steady", 10, 20), stage("a", "excluded", 0, 10)))

        assertEquals("OVERLAPPING_STAGES", code(declaration(stage("b", "steady", 10, 20), stage("a", "excluded", 0, 11))))
        assertEquals("OVERLAPPING_STAGES", code(declaration(stage("a", "steady", 0, 20), stage("b", "excluded", 0, 5))))
    }

    @Test
    fun `stage count is bounded to sixteen and at least one stage must be steady`() {
        fun many(count: Int) =
            declaration(*(0 until count).map { stage("s$it", if (it == 0) "steady" else "excluded", it * 10, it * 10 + 10) }.toTypedArray())

        valid(many(MAX_LOAD_STAGES))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(many(MAX_LOAD_STAGES + 1)))
        assertEquals("STAGES_NO_STEADY", code(declaration()))
    }

    @Test
    fun `an id is bounded to 128 UTF-8 bytes and has no control characters`() {
        valid(declaration(stage("a".repeat(128), "steady", 0, 10)))
        valid(declaration(stage("я".repeat(64), "steady", 0, 10)))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(declaration(stage("a".repeat(129), "steady", 0, 10))))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(declaration(stage("я".repeat(65), "steady", 0, 10))))
        assertEquals("INVALID_TEXT", code(declaration(stage("", "steady", 0, 10))))
        assertEquals("INVALID_TEXT", code(declaration(stage("a\\u0007b", "steady", 0, 10))))
    }

    @Test
    fun `offsets are integers by value inside the range, wide numbers stop in the scanner`() {
        valid(declaration(stage("a", "steady", 0, MAX_LOAD_STAGE_OFFSET_MS)))
        assertEquals("INVALID_STAGE_OFFSET", code(declaration(stage("a", "steady", 0, MAX_LOAD_STAGE_OFFSET_MS + 1))))
        assertEquals("INVALID_STAGE_OFFSET", code(declaration(stage("a", "steady", 0, "1e20"))))
        assertEquals("INVALID_STAGE_OFFSET", code(declaration(stage("a", "steady", "-1e0", 10))))
        assertEquals("INVALID_TYPE", code(declaration(stage("a", "steady", 0, "1500.5"))))
        assertEquals("INVALID_TYPE", code(declaration(stage("a", "steady", 0, "\"10\""))))
        assertEquals("INVALID_TYPE", code(declaration(stage("a", "steady", 0, "null"))))
        assertEquals("INVALID_TYPE", code(declaration(stage("a", "steady", 0, "true"))))
        assertEquals("MISSING_FIELD", code(declaration("""{"id":"a","role":"steady","from_offset_ms":0}""")))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(declaration(stage("a", "steady", 0, "1e400"))))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", code(declaration(stage("a", "steady", 0, "1" + "0".repeat(70)))))
        assertEquals(valid(declaration(stage("a", "steady", 0, 1000))).sha256, valid(declaration(stage("a", "steady", 0, "1e3"))).sha256)
    }

    @Test
    fun `malformed, duplicate-key and oversized input is rejected`() {
        assertEquals("MALFORMED_JSON", code("{"))
        assertEquals("MALFORMED_JSON", code(""))
        assertEquals("INVALID_TYPE", code("[]"))
        assertEquals("MISSING_FIELD", code("{}"))
        assertEquals("DUPLICATE_OBJECT_KEY", code("""{"schema_version":"load-stages.v1","schema_version":"load-stages.v1","stages":[]}"""))
        assertEquals("INVALID_UTF8", firstError(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)).code)
        assertEquals(
            "RESOURCE_LIMIT_EXCEEDED",
            code(declaration(stage("a", "steady", 0, 10)) + " ".repeat(MAX_LOAD_STAGES_BYTES)),
        )
        assertEquals(
            "RESOURCE_LIMIT_EXCEEDED",
            code("""{"schema_version":"load-stages.v1","stages":[[[[[[[[[[1]]]]]]]]]]}"""),
        )
    }

    @Test
    fun `resolveStageWindows turns steady stages into windows and ignores the rest`() {
        val stages = valid(Files.readAllBytes(Path.of("$EXAMPLES/valid/ramp-steady-down.json"))).stages

        val resolved = resolveStageWindows(stages, 1_000_000L, 1_119_800L)

        assertEquals(listOf(ResourceWindowV1("steady", 1_040_000L, 1_100_000L)), resolved.windows)
        assertEquals(listOf(1_000_000L, 1_040_000L, 1_100_000L), resolved.stages.map(ResolvedStage::fromEpochMillis))
        assertEquals(listOf(false, false, false), resolved.stages.map(ResolvedStage::clippedToRunEnd))
    }

    @Test
    fun `a steady stage past the run end is clipped, an excluded one is not checked`() {
        val clipped = valid(declaration(stage("ramp", "excluded", 0, 400), stage("steady", "steady", 400, 5_000))).stages
        val resolved = resolveStageWindows(clipped, 1_000L, 1_900L)

        assertEquals(listOf(ResourceWindowV1("steady", 1_400L, 1_900L)), resolved.windows)
        assertEquals(listOf(false, true), resolved.stages.map(ResolvedStage::clippedToRunEnd))
        assertEquals(1_900L, resolved.stages[1].toEpochMillis)

        val excludedOutside = valid(declaration(stage("steady", "steady", 0, 500), stage("down", "excluded", 500, 20_000))).stages
        val tail = resolveStageWindows(excludedOutside, 1_000L, 1_900L)
        assertEquals(20_000L + 1_000L, tail.stages[1].toEpochMillis)
        assertEquals(false, tail.stages[1].clippedToRunEnd)
    }

    @Test
    fun `a steady stage that starts at or after the run end is outside the run`() {
        fun resolve(from: Int) = resolveStageWindows(valid(declaration(stage("steady", "steady", from, 2_000))).stages, 1_000L, 1_900L)

        // from == run end would be an empty window (throughput over zero milliseconds), so it is outside the run too
        assertEquals("STAGE_OUTSIDE_RUN", assertThrows(IllegalArgumentException::class.java) { resolve(900) }.message)
        assertEquals("STAGE_OUTSIDE_RUN", assertThrows(IllegalArgumentException::class.java) { resolve(901) }.message)
        assertEquals(listOf(ResourceWindowV1("steady", 1_899L, 1_900L)), resolve(899).windows)
    }

    private fun valid(raw: ByteArray) =
        assertInstanceOf(LoadStagesValidation.Valid::class.java, validateLoadStages(ByteArrayInputStream(raw)))

    private fun valid(text: String) = valid(text.encodeToByteArray())

    private fun firstError(raw: ByteArray): PolicyValidationError {
        val invalid = assertInstanceOf(LoadStagesValidation.Invalid::class.java, validateLoadStages(ByteArrayInputStream(raw)))
        assertTrue(invalid.errors.isNotEmpty())
        return invalid.errors.first()
    }

    private fun firstError(text: String) = firstError(text.encodeToByteArray())

    private fun code(text: String) = firstError(text).code
}

private const val EXAMPLES = "docs/contracts/stages/v1/examples"

private fun stage(
    id: String,
    role: String,
    from: Any,
    to: Any,
) = """{"id":"$id","role":"$role","from_offset_ms":$from,"to_offset_ms":$to}"""

private fun declaration(vararg stages: String) = """{"schema_version":"load-stages.v1","stages":[${stages.joinToString(",")}]}"""

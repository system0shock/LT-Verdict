package io.ltverdict.core

import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ResourceSnapshotTest {
    @Test
    fun `valid snapshot is bounded canonical and keeps immutable raw bytes`() {
        val raw = snapshot(provenance = "known-offset").encodeToByteArray()
        val valid = valid(raw)
        val sameSemantics = valid(snapshot(provenance = "unknown").encodeToByteArray())

        assertEquals(listOf("cpu", "memory"), valid.snapshot.series.map(ResourceSeriesV1::id))
        assertEquals(HASH, valid.snapshot.loadInputSha256)
        assertEquals(valid.semanticSha256, sameSemantics.semanticSha256)
        assertEquals(valid.configSha256, sameSemantics.configSha256)
        assertNotEquals(sha256Hex(raw), valid.semanticSha256)
        val exposed = valid.rawBytes()
        exposed[0] = 0
        assertArrayEquals(raw, valid.rawBytes())
    }

    @Test
    fun `contract example is accepted by the runtime validator`() {
        val bytes = Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))

        val valid = valid(bytes)

        assertEquals("resource-snapshot.v1", valid.snapshot.schemaVersion)
        assertEquals(2, valid.snapshot.series.size)
    }

    @Test
    fun `strict boundary rejects duplicate fields unsupported aggregation and malformed bounds`() {
        val cases =
            listOf(
                Case(
                    snapshot().replace("\"step_ms\":10000", "\"step_ms\":10000,\"\\u0073tep_ms\":10000"),
                    "DUPLICATE_OBJECT_KEY",
                    "/step_ms",
                ),
                Case(snapshot().replace("\"series\":[", "\"unexpected\":true,\"series\":["), "UNKNOWN_FIELD", "/unexpected"),
                Case(snapshot().replace("interval_mean", "instant"), "UNSUPPORTED_AGGREGATION", "/series/0/aggregation"),
                Case(snapshot().replace("\"to_epoch_ms\":40000", "\"to_epoch_ms\":35000"), "WINDOW_NOT_ON_GRID", "/windows/0/to_epoch_ms"),
                Case(snapshot().replace("\"from_epoch_ms\":20000", "\"from_epoch_ms\":40000"), "INVALID_WINDOW", "/windows/0"),
                Case(
                    snapshot().replace("\"unit\":\"ratio\",\"operator\"", "\"unit\":\"bytes\",\"operator\""),
                    "RULE_UNIT_MISMATCH",
                    "/rules/0/unit",
                ),
            )

        cases.forEach { case -> assertInvalid(case.json, case.code, case.pointer) }
    }

    @Test
    fun `numeric and collection ceilings fail closed`() {
        val tooPrecise = snapshot().replace("0.7,null,0.9,0.95", "0.1234567890123,null,0.9,0.95")
        val tooManyPoints = snapshot().replace("\"point_count\":4", "\"point_count\":100001")
        val overMagnitude = snapshot().replace("0.7,null,0.9,0.95", "1000000000000000001,null,0.9,0.95")

        assertInvalid(tooPrecise, "RESOURCE_LIMIT_EXCEEDED", "/series/1/values/0")
        assertInvalid(tooManyPoints, "RESOURCE_LIMIT_EXCEEDED", "/point_count")
        assertInvalid(overMagnitude, "RESOURCE_LIMIT_EXCEEDED", "/series/1/values/0")
        assertInvalid(snapshot(), "RESOURCE_LIMIT_EXCEEDED", "", maxBytes = snapshot().encodeToByteArray().size - 1)
    }

    @Test
    fun `binding checks hash and run bounds and derives full-cell intersection`() {
        val explicit = valid(snapshot().encodeToByteArray()).snapshot
        val mismatch =
            assertThrows(IllegalArgumentException::class.java) {
                resolveResourceWindows(explicit, "b".repeat(64), 10_000, 50_000)
            }
        val outside =
            assertThrows(IllegalArgumentException::class.java) {
                resolveResourceWindows(explicit, HASH, 25_000, 50_000)
            }
        val implicit = valid(snapshot(windows = "").encodeToByteArray()).snapshot

        assertEquals("RESOURCE_LOAD_HASH_MISMATCH", mismatch.message)
        assertEquals("RESOURCE_WINDOW_OUTSIDE_RUN", outside.message)
        assertEquals(
            listOf(ResourceWindowV1("run-intersection", 20_000, 40_000)),
            resolveResourceWindows(implicit, HASH, 15_001, 49_999),
        )
    }

    @Test
    fun `binding evidence exposes implicit dropped edges without trusting provenance`() {
        val snapshot = valid(snapshot(provenance = "known-offset", windows = "").encodeToByteArray()).snapshot
        val windows = resolveResourceWindows(snapshot, HASH, 15_001, 49_999)

        val evidence = resourceBindingEvidence(snapshot, windows, 15_001, 49_999)

        assertEquals("resource_binding", evidence.getValue("type").jsonPrimitive.content)
        assertEquals("run_intersection", evidence.getValue("mode").jsonPrimitive.content)
        assertEquals(
            20_000,
            evidence
                .getValue("evaluation_from_epoch_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            40_000,
            evidence
                .getValue("evaluation_to_epoch_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            1,
            evidence
                .getValue("dropped_leading_cells")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            4_999,
            evidence
                .getValue("dropped_leading_millis")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            1,
            evidence
                .getValue("dropped_trailing_cells")
                .jsonPrimitive.content
                .toInt(),
        )
        assertEquals(
            9_999,
            evidence
                .getValue("dropped_trailing_millis")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals("not_verified_by_core", evidence.getValue("clock_alignment").jsonPrimitive.content)
    }

    private fun valid(bytes: ByteArray): ResourceValidation.Valid =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(bytes)),
        )

    private fun assertInvalid(
        json: String,
        code: String,
        pointer: String,
        maxBytes: Int = 16 * 1024 * 1024,
    ) {
        val invalid =
            assertInstanceOf(
                ResourceValidation.Invalid::class.java,
                validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray()), maxBytes),
            )
        assertEquals(code, invalid.errors.first().code)
        assertEquals(pointer, invalid.errors.first().jsonPointer)
    }

    private fun snapshot(
        provenance: String = "unknown",
        windows: String = """"windows":[{"id":"steady","from_epoch_ms":20000,"to_epoch_ms":40000}],""",
    ): String =
        """
        {
          "schema_version":"resource-snapshot.v1",
          "load_input_sha256":"$HASH",
          "start_epoch_ms":10000,
          "step_ms":10000,
          "point_count":4,
          "series":[
            {"id":"memory","metric":"memory_used","unit":"bytes","entity":"host-a","role":"system","aggregation":"interval_mean","labels":{"zone":"test"},"values":[10,11,12,13]},
            {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host-a","role":"system","aggregation":"interval_mean","values":[0.7,null,0.9,0.95]}
          ],
          $windows
          "rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,"min_consecutive_cells":2,"effect":"sla"}],
          "provenance":{"source_kind":"fixture","query_semantics":"interval means","clock_alignment":"$provenance"}
        }
        """.trimIndent()

    private data class Case(
        val json: String,
        val code: String,
        val pointer: String,
    )

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

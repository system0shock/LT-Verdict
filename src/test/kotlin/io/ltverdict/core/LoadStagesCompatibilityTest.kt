package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0030, AC4 and AC5: a run without stages is unchanged, a run with stages is bound and compared by its declaration. */
class LoadStagesCompatibilityTest {
    @TempDir
    lateinit var tempDir: Path

    private val inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val input =
        AcceptedInput(
            runId = "jmeter_jtl_csv-$inputHash",
            sourceType = SourceType.JMETER_CSV,
            sha256 = inputHash,
            sizeBytes = 1,
            originalFilename = "input.jtl",
            path = Path.of("unused"),
        )
    private val declared = stages(Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")))

    @Test
    fun `without stages the identity keeps its committed bytes and has no stage keys`() {
        val policy =
            validatePolicy(
                ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/slice1/identity/policy.canonical.json"))),
            ) as PolicyValidation.Valid
        val actual = analysisIdentity(input, policy, EngineConfig())
        val document = Json.parseToJsonElement(actual.decodeToString()).jsonObject

        assertArrayEquals(Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity.v1.json")), actual)
        assertFalse(document.keys.any { it.startsWith("load_stages_") })
        assertFalse(document.getValue("input_versions").jsonObject.containsKey("stages"))
        assertFalse(
            document
                .getValue("limits")
                .jsonObject.keys
                .any { it.startsWith("stages_") },
        )
        assertTrue(
            document.getValue("modules").jsonArray.none {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content ==
                    "stage-window-evaluation"
            },
        )
    }

    @Test
    fun `with stages the identity carries the declaration, the modules, the input version and the four limits`() {
        val document = Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig(), stages = declared).decodeToString()).jsonObject

        assertEquals(declared.sha256, document.getValue("load_stages_sha256").jsonPrimitive.content)
        assertEquals("load-stages.v1", document.getValue("load_stages_version").jsonPrimitive.content)
        assertEquals(
            "load-stages.v1",
            document
                .getValue("input_versions")
                .jsonObject
                .getValue("stages")
                .jsonPrimitive.content,
        )
        val modules =
            document.getValue("modules").jsonArray.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }
        assertEquals(1, modules.count { it == "stage-window-evaluation" })
        assertEquals(1, modules.count { it == "window-policy-evaluation" })
        assertEquals(
            mapOf(
                "stages_plan_bytes_max" to "65536",
                "stages_json_depth_max" to "8",
                "stages_max" to "16",
                "stages_offset_ms_max" to "604800000",
            ),
            document
                .getValue("limits")
                .jsonObject
                .filterKeys { it.startsWith("stages_") }
                .mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `the same declaration gives the same identity and another declaration another one`() {
        val respelled =
            stages(Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")).replace("40000", "4e4"))
        val shifted =
            stages(Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")).replace("100000,", "100001,"))

        val base = analysisIdentity(input, null, EngineConfig(), stages = declared)

        assertArrayEquals(base, analysisIdentity(input, null, EngineConfig(), stages = respelled))
        assertNotEquals(sha256Hex(base), sha256Hex(analysisIdentity(input, null, EngineConfig(), stages = shifted)))
        assertNotEquals(sha256Hex(base), sha256Hex(analysisIdentity(input, null, EngineConfig())))
    }

    @Test
    fun `a staged analysis is comparable only with the same declaration`() =
        withService { store, service ->
            val data = accept(store, Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl")))
            val policy = policy()
            val plain = saved(service.analyze(AnalysisRequest(data, policy)))
            val first = saved(service.analyze(AnalysisRequest(data, policy, stages = declared)), 'b')
            val sameDeclaration = saved(service.analyze(AnalysisRequest(data, policy, stages = stages(RAMP_STEADY_DOWN_SPACED))), 'c')
            val otherDeclaration =
                saved(
                    service.analyze(AnalysisRequest(data, policy, stages = stages(RAMP_STEADY_DOWN.replace("100000,", "100001,")))),
                    'd',
                )

            assertTrue(compatible(first, sameDeclaration))
            assertFalse(compatible(first, otherDeclaration))
            assertFalse(compatible(first, plain))
            assertFalse(compatible(plain, first))
        }

    @Test
    fun `the declaration hash alone separates two analyses whose modules, versions and limits are equal`() =
        withService { store, service ->
            val data = accept(store, Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl")))
            val plain = saved(service.analyze(AnalysisRequest(data, policy())))
            val staged = saved(service.analyze(AnalysisRequest(data, policy(), stages = declared)))
            val rebound = staged.copy(identity = JsonObject(staged.identity + ("load_stages_sha256" to JsonPrimitive("f".repeat(64)))))
            val bound = plain.copy(identity = JsonObject(plain.identity + ("load_stages_sha256" to JsonPrimitive(declared.sha256))))

            assertFalse(compatible(staged, rebound))
            assertFalse(compatible(plain, bound))
            assertTrue(compatible(bound, bound.copy(reference = reference('e'))))
            assertTrue(compatible(plain, plain.copy(reference = reference('e'))))
        }

    @Test
    fun `the dynamics, the transaction comparison and the statistical baseline use the same key`() =
        withService { store, service ->
            val data = accept(store, Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl")))
            val plain = saved(service.analyze(AnalysisRequest(data, policy())))
            val staged = saved(service.analyze(AnalysisRequest(data, policy(), stages = declared)), 'b')
            val stagedToo = staged.copy(reference = reference('c'))

            val dynamics = buildRunDynamics(staged, listOf(plain, stagedToo))
            assertEquals(
                2,
                dynamics
                    .getValue("comparable_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                1,
                dynamics
                    .getValue("excluded_incompatible_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(
                JsonPrimitive(true),
                compareTransactions(plain.result, plain.identity, plain.result, plain.identity).getValue("compatible"),
            )
            assertEquals(
                JsonPrimitive(false),
                compareTransactions(plain.result, plain.identity, staged.result, staged.identity).getValue("compatible"),
            )

            fun selection(vararg items: SavedAnalysisForComparison) =
                statisticalBaselineSelection(
                    "series",
                    items.map { it.reference },
                    items.map { it.result },
                    items.map { it.identity },
                )

            val mixed =
                assertThrows(IllegalArgumentException::class.java) {
                    selection(staged, plain.copy(reference = reference('e')), stagedToo.copy(reference = reference('f')))
                }
            assertEquals("BASELINE_MIXED_SEMANTICS", mixed.message)
            selection(staged, stagedToo, staged.copy(reference = reference('f')))
        }

    @Test
    fun `two staged analyses that differ only by the declaration hash are separated by every comparison`() =
        withService { store, service ->
            val data = accept(store, Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl")))
            val staged = saved(service.analyze(AnalysisRequest(data, policy(), stages = declared)), 'b')
            val sameHash = staged.copy(reference = reference('c'))
            val otherHash =
                staged.copy(
                    reference = reference('d'),
                    identity = JsonObject(staged.identity + ("load_stages_sha256" to JsonPrimitive("f".repeat(64)))),
                )

            val dynamics = buildRunDynamics(staged, listOf(sameHash, otherHash))
            assertEquals("2", dynamics.getValue("comparable_count").jsonPrimitive.content)
            assertEquals("1", dynamics.getValue("excluded_incompatible_count").jsonPrimitive.content)
            assertEquals(
                JsonPrimitive(true),
                compareTransactions(staged.result, staged.identity, sameHash.result, sameHash.identity).getValue("compatible"),
            )
            assertEquals(
                JsonPrimitive(false),
                compareTransactions(staged.result, staged.identity, otherHash.result, otherHash.identity).getValue("compatible"),
            )
            val mixed =
                assertThrows(IllegalArgumentException::class.java) {
                    val items = listOf(staged, sameHash.copy(reference = reference('e')), otherHash)
                    statisticalBaselineSelection("series", items.map { it.reference }, items.map { it.result }, items.map { it.identity })
                }
            assertEquals("BASELINE_MIXED_SEMANTICS", mixed.message)
        }

    @Test
    fun `a run without stages gives the same analysis id whether stages are absent or never offered`() =
        withService { store, service ->
            val data = accept(store, Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl")))

            val first = service.analyze(AnalysisRequest(data, policy()))
            val again = service.analyze(AnalysisRequest(data, policy(), stages = null))

            assertEquals(first.analysisId, again.analysisId)
            assertEquals(first.analysisDirectory, again.analysisDirectory)
            assertFalse(Files.exists(first.analysisDirectory.resolve("load-stages.json")))
            val document = Json.parseToJsonElement(Files.readString(first.analysisDirectory.resolve("identity.json"))).jsonObject
            assertFalse(document.keys.any { it.startsWith("load_stages_") })
        }

    private fun compatible(
        baseline: SavedAnalysisForComparison,
        current: SavedAnalysisForComparison,
    ): Boolean {
        val reason =
            compareAnalyses(
                manualBaselineSelection("release", baseline.reference),
                current.reference,
                baseline.result,
                baseline.identity,
                current.result,
                current.identity,
            ).getValue("metrics")
                .jsonArray
                .first()
                .jsonObject
                .getValue("reason")
        return reason == JsonNull
    }

    private fun saved(
        outcome: AnalysisOutcome,
        suffix: Char = 'a',
    ) = SavedAnalysisForComparison(
        reference = reference(suffix),
        run = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("run.json"))).jsonObject,
        result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject,
        identity = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject,
    )

    private fun reference(suffix: Char): JsonObject =
        buildJsonObject {
            put("run_id", "jmeter_jtl_csv-${suffix.toString().repeat(64)}")
            put("analysis_id", suffix.toString().repeat(64))
        }

    private fun withService(block: (RunBundleStore, AnalysisService) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()))
        }
    }

    private fun accept(
        store: RunBundleStore,
        bytes: ByteArray,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(bytes), "input.jtl")

    private fun stages(json: String) =
        assertInstanceOf(LoadStagesValidation.Valid::class.java, validateLoadStages(ByteArrayInputStream(json.encodeToByteArray())))

    private fun policy() =
        assertInstanceOf(
            PolicyValidation.Valid::class.java,
            validatePolicy(
                ByteArrayInputStream(
                    (
                        """{"schema_version":"policy.v1","policy_id":"pass","defaults":{"sample_floor":1,"min_samples":1},""" +
                            """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,""" +
                            """"scope":{"kind":"overall"}}]}"""
                    ).encodeToByteArray(),
                ),
            ),
        )
}

private val RAMP_STEADY_DOWN = Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"))
private val RAMP_STEADY_DOWN_SPACED = RAMP_STEADY_DOWN.replace("\"stages\": [", "\"stages\":\n[")

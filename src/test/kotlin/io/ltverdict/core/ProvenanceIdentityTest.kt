package io.ltverdict.core

import io.ltverdict.cli.EXIT_INVALID_INPUT
import io.ltverdict.cli.EXIT_OK
import io.ltverdict.cli.EXIT_USAGE
import io.ltverdict.cli.runCli
import io.ltverdict.ingest.AcceptedInput
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class ProvenanceIdentityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `omitted or blank provenance produces byte-identical identity and analysis_id`() {
        val input =
            AcceptedInput(
                runId = "jmeter_jtl_csv-${"a".repeat(64)}",
                sourceType = SourceType.JMETER_CSV,
                sha256 = "a".repeat(64),
                sizeBytes = 123,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val baseBytes = analysisIdentity(input, null, EngineConfig())
        val nullBytes = analysisIdentity(input, null, EngineConfig(), jenkinsBuild = null, commit = null)
        val blankBytes = analysisIdentity(input, null, EngineConfig(), jenkinsBuild = "   ", commit = "\t ")

        assertArrayEquals(baseBytes, nullBytes)
        assertArrayEquals(baseBytes, blankBytes)
        assertEquals(sha256Hex(baseBytes), sha256Hex(blankBytes))
        val parsed = Json.parseToJsonElement(baseBytes.decodeToString()).jsonObject
        assertFalse("jenkins_build" in parsed)
        assertFalse("commit" in parsed)
    }

    @Test
    fun `provenance values are trimmed, NFC normalized, bound into identity and validated`() {
        val input =
            AcceptedInput(
                runId = "jmeter_jtl_csv-${"b".repeat(64)}",
                sourceType = SourceType.JMETER_CSV,
                sha256 = "b".repeat(64),
                sizeBytes = 123,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val baseBytes = analysisIdentity(input, null, EngineConfig())
        val taggedBytes =
            analysisIdentity(
                input,
                null,
                EngineConfig(),
                jenkinsBuild = "  build-42  ",
                commit = " 0123abcd ",
            )

        assertNotEquals(sha256Hex(baseBytes), sha256Hex(taggedBytes))
        val parsed = Json.parseToJsonElement(taggedBytes.decodeToString()).jsonObject
        assertEquals(JsonPrimitive("build-42"), parsed["jenkins_build"])
        assertEquals(JsonPrimitive("0123abcd"), parsed["commit"])

        assertNull(normalizeProvenanceText("   ", "commit"))
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProvenanceText("bad\nline", "jenkins_build")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProvenanceText("bad\u0000null", "commit")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProvenanceText("x".repeat(MAX_PROVENANCE_TEXT_BYTES + 1), "commit")
        }
    }

    @Test
    fun `provenance fields do not alter BaselineComparison or RunComparison comparability keys`() {
        val jtl = Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")
        val passPolicy =
            validatePolicy(
                (
                    """{"schema_version":"policy.v1","policy_id":"pass","defaults":{"sample_floor":1,"min_samples":1},""" +
                        """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte",""" +
                        """"threshold":100000,"scope":{"kind":"overall"}}]}"""
                ).byteInputStream(),
            ) as PolicyValidation.Valid
        DataDirectory.open(tempDir.resolve("compat-data")).use { directory ->
            val store = RunBundleStore(directory)
            val service = AnalysisService(store, EngineConfig())
            val input = Files.newInputStream(jtl).use { store.acceptInput(it, "input.jtl") }

            val untaggedOutcome = service.analyze(AnalysisRequest(input, policy = passPolicy))
            val taggedOutcome =
                service.analyze(
                    AnalysisRequest(
                        input,
                        policy = passPolicy,
                        jenkinsBuild = "build-42",
                        commit = "deadbeef",
                    ),
                )
            assertNotEquals(untaggedOutcome.analysisId, taggedOutcome.analysisId)

            val untaggedDocs = checkNotNull(store.readComparisonDocuments(untaggedOutcome.runId, untaggedOutcome.analysisId))
            val taggedDocs = checkNotNull(store.readComparisonDocuments(taggedOutcome.runId, taggedOutcome.analysisId))

            val untaggedRef =
                buildJsonObject {
                    put("run_id", untaggedOutcome.runId)
                    put("analysis_id", untaggedOutcome.analysisId)
                }
            val baselineSelection =
                selectBaseline(
                    planBaselineSelection(
                        buildJsonObject {
                            put("mode", "manual")
                            put("series", "default")
                            put("reference", untaggedRef)
                        },
                    ),
                    listOf(untaggedDocs.result),
                    listOf(untaggedDocs.identity),
                )
            val currentRef =
                buildJsonObject {
                    put("run_id", taggedOutcome.runId)
                    put("analysis_id", taggedOutcome.analysisId)
                }
            val comparison =
                compareAnalyses(
                    baselineSelection,
                    currentRef,
                    untaggedDocs.result,
                    untaggedDocs.identity,
                    taggedDocs.result,
                    taggedDocs.identity,
                )
            val metrics = comparison.getValue("metrics").jsonArray
            assertTrue(metrics.isNotEmpty())
            metrics.forEach { metric ->
                assertEquals(JsonNull, metric.jsonObject["reason"])
                assertNotEquals(JsonNull, metric.jsonObject["delta"])
            }

            val untaggedSaved =
                SavedAnalysisForComparison(
                    baselineSelection.getValue("reference").jsonObject,
                    checkNotNull(untaggedDocs.run),
                    untaggedDocs.result,
                    untaggedDocs.identity,
                    jenkinsBuild = null,
                    commit = null,
                )
            val taggedSaved =
                SavedAnalysisForComparison(
                    currentRef,
                    checkNotNull(taggedDocs.run),
                    taggedDocs.result,
                    taggedDocs.identity,
                    jenkinsBuild = "build-42",
                    commit = "deadbeef",
                )
            val dynamics = buildRunDynamics(taggedSaved, listOf(untaggedSaved, taggedSaved), untaggedSaved.reference)
            assertEquals(0, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int)
            assertEquals(2, dynamics.getValue("comparable_count").jsonPrimitive.int)
            val rows = dynamics.getValue("rows").jsonArray
            assertEquals(JsonNull, rows[0].jsonObject["jenkins_build"])
            assertEquals(JsonPrimitive("build-42"), rows[1].jsonObject["jenkins_build"])
            assertEquals(JsonPrimitive("deadbeef"), rows[1].jsonObject["commit"])
        }
    }

    @Test
    fun `cli analyze persists jenkins_build and commit in identity and rejects invalid flags`() {
        val dataDir = tempDir.resolve("cli-data")
        val jtl = Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl").toString()
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val exit =
            runCli(
                arrayOf(
                    "analyze",
                    jtl,
                    "--jenkins-build",
                    "42",
                    "--commit",
                    "abc1234",
                    "--data-dir",
                    dataDir.toString(),
                ),
                PrintStream(stdout),
                PrintStream(stderr),
            )
        assertEquals(EXIT_OK, exit)
        val resultJson = Json.parseToJsonElement(stdout.toString(Charsets.UTF_8)).jsonObject
        val runId = resultJson.getValue("run_id").jsonPrimitive.content
        val analysisIdLine = stderr.toString(Charsets.UTF_8).lineSequence().first { it.startsWith("analysis_id=") }
        val analysisId = analysisIdLine.substringAfter("analysis_id=").substringBefore(' ')

        DataDirectory.open(dataDir).use { directory ->
            val identity = checkNotNull(RunBundleStore(directory).readAnalysisIdentity(runId, analysisId))
            assertEquals(JsonPrimitive("42"), identity["jenkins_build"])
            assertEquals(JsonPrimitive("abc1234"), identity["commit"])
        }

        val duplicateExit =
            runCli(
                arrayOf(
                    "analyze",
                    jtl,
                    "--jenkins-build",
                    "42",
                    "--jenkins-build",
                    "43",
                    "--data-dir",
                    dataDir.toString(),
                ),
                PrintStream(ByteArrayOutputStream()),
                PrintStream(ByteArrayOutputStream()),
            )
        assertEquals(EXIT_USAGE, duplicateExit)

        val invalidControlExit =
            runCli(
                arrayOf(
                    "analyze",
                    jtl,
                    "--commit",
                    "bad\nref",
                    "--data-dir",
                    dataDir.toString(),
                ),
                PrintStream(ByteArrayOutputStream()),
                PrintStream(ByteArrayOutputStream()),
            )
        assertEquals(EXIT_INVALID_INPUT, invalidControlExit)

        val oversizedExit =
            runCli(
                arrayOf(
                    "analyze",
                    jtl,
                    "--jenkins-build",
                    "b".repeat(129),
                    "--data-dir",
                    dataDir.toString(),
                ),
                PrintStream(ByteArrayOutputStream()),
                PrintStream(ByteArrayOutputStream()),
            )
        assertEquals(EXIT_INVALID_INPUT, oversizedExit)
    }

    @Test
    fun `RunValidity enum stays intact for policy evaluation`() {
        assertEquals(RunValidity.VALID.name, "VALID")
        assertTrue(MAX_PROVENANCE_TEXT_BYTES == 128)
    }
}

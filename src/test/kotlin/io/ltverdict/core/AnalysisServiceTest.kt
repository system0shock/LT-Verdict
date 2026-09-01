package io.ltverdict.core

import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class AnalysisServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `standard analysis uses the final two-pass window and commits the complete bundle`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "out-of-order.jtl")
            val progress = mutableListOf<Long>()

            val outcome = service.analyze(AnalysisRequest(input, passPolicy()), progress::add)

            assertEquals(input.runId, outcome.runId)
            assertTrue(Regex("[0-9a-f]{64}").matches(outcome.analysisId))
            assertEquals(outcome.analysisId, outcome.analysisDirectory.fileName.toString())
            assertArrayEquals(outcome.canonicalResult, Files.readAllBytes(outcome.analysisDirectory.resolve("analysis-result.json")))
            assertEquals("VALID", result(outcome, "run_validity"))
            assertEquals("PASS", result(outcome, "policy_verdict"))
            assertMonotonicProgress(progress, input.sizeBytes)

            assertEquals(
                expectedRun(input, "2026-01-01T00:00:00Z", "2026-01-01T00:00:01.020Z"),
                Files.readString(outcome.analysisDirectory.resolve("run.json")),
            )
            val buckets = Files.readAllLines(outcome.analysisDirectory.resolve("normalized-1s.ndjson"))
            assertEquals(
                listOf("0", "1000"),
                buckets.map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("bucket_start_ms")
                        .jsonPrimitive.content
                },
            )

            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            assertEquals(COMPLETE_ARTIFACTS, stored.artifacts.map { it.path }.toSet())
            assertTrue(Files.isRegularFile(stored.path.resolve("manifest.json")))
            assertTrue(stored.artifacts.all { it.sizeBytes > 0 && Regex("[0-9a-f]{64}").matches(it.sha256) })

            val identity = Json.parseToJsonElement(Files.readString(stored.path.resolve("identity.json"))).jsonObject
            assertEquals(
                "lt-verdict",
                identity
                    .getValue("engine")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "1",
                identity
                    .getValue("engine")
                    .jsonObject
                    .getValue("version")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "86400000",
                identity
                    .getValue("histogram")
                    .jsonObject
                    .getValue("highest_trackable_value_ms")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `identical request is byte stable and a new policy cannot change the old analysis`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "input.jtl")
            val first = service.analyze(AnalysisRequest(input, passPolicy()))
            val firstSnapshot = snapshot(first)

            val repeated = service.analyze(AnalysisRequest(input, passPolicy()))
            assertEquals(first.analysisId, repeated.analysisId)
            assertEquals(first.analysisDirectory, repeated.analysisDirectory)
            assertArrayEquals(first.canonicalResult, repeated.canonicalResult)
            assertSnapshotEquals(firstSnapshot, snapshot(repeated))

            val changed = service.analyze(AnalysisRequest(input, policyFromFile(FAIL_POLICY)))
            assertNotEquals(first.analysisId, changed.analysisId)
            assertEquals("FAIL", result(changed, "policy_verdict"))
            assertSnapshotEquals(firstSnapshot, snapshot(first))
            assertTrue(store.readAnalysis(input.runId, first.analysisId) != null)
            assertTrue(store.readAnalysis(input.runId, changed.analysisId) != null)
        }

    @Test
    fun `degraded binary keeps complete samples but cannot produce a verdict`() =
        withService { store, service ->
            val complete = Files.readAllBytes(Path.of(GATLING_BINARY_FIXTURE))
            val input = accept(store, complete + byteArrayOf(2, 0), "simulation.log")

            val outcome = service.analyze(AnalysisRequest(input, passPolicy()))

            assertEquals("DEGRADED", result(outcome, "run_validity"))
            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            assertEquals(
                expectedRun(input, "2026-08-31T21:36:13.295Z", "2026-08-31T21:36:13.436Z"),
                Files.readString(outcome.analysisDirectory.resolve("run.json")),
            )
            assertEquals(
                COMPLETE_ARTIFACTS,
                store
                    .readAnalysis(input.runId, outcome.analysisId)!!
                    .artifacts
                    .map { it.path }
                    .toSet(),
            )
        }

    @Test
    fun `invalid input commits only identity and no-verdict result`() =
        withService { store, service ->
            val header = OUT_OF_ORDER_CSV.lineSequence().first()
            val input = accept(store, "$header\nmalformed\n".encodeToByteArray(), "invalid.jtl")

            val outcome = service.analyze(AnalysisRequest(input, null))

            assertEquals("INVALID", result(outcome, "run_validity"))
            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            assertEquals(setOf("analysis-result.json", "identity.json"), stored.artifacts.map { it.path }.toSet())
            assertFalse(Files.exists(stored.path.resolve("run.json")))
            assertFalse(Files.exists(stored.path.resolve("normalized-1s.ndjson")))
        }

    @Test
    fun `capacity mode is rejected before an analysis directory exists`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "capacity.jtl")
            val analyses =
                input.path.parent.parent
                    .resolve("analyses")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, null, AnalysisMode.CAPACITY_STEP))
                }

            assertEquals("UNSUPPORTED_ANALYSIS_MODE", failure.message)
            assertFalse(Files.exists(analyses))
        }

    private fun withService(block: (RunBundleStore, AnalysisService) -> Unit) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()))
        }
    }

    private fun accept(
        store: RunBundleStore,
        bytes: ByteArray,
        name: String,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(bytes), name)

    private fun passPolicy(): PolicyValidation.Valid =
        policy(
            """{"schema_version":"policy.v1","policy_id":"pass","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}""",
        )

    private fun policyFromFile(path: String): PolicyValidation.Valid =
        Files.newInputStream(Path.of(path)).use { source ->
            assertInstanceOf(PolicyValidation.Valid::class.java, validatePolicy(source))
        }

    private fun policy(json: String): PolicyValidation.Valid =
        assertInstanceOf(
            PolicyValidation.Valid::class.java,
            validatePolicy(ByteArrayInputStream(json.encodeToByteArray())),
        )

    private fun result(
        outcome: AnalysisOutcome,
        field: String,
    ): String =
        Json
            .parseToJsonElement(outcome.canonicalResult.decodeToString())
            .jsonObject
            .getValue(field)
            .jsonPrimitive
            .content

    private fun expectedRun(
        input: AcceptedInput,
        startedAt: String,
        endedAt: String,
    ): String =
        """{"analysis_mode":"standard","ended_at":"$endedAt","inputs":[{"path":"inputs/source.bin","sha256":"${input.sha256}","type":"${input.sourceType.wireName}"}],"run_id":"${input.runId}","schema_version":"run.v1","started_at":"$startedAt"}"""

    private fun assertMonotonicProgress(
        values: List<Long>,
        final: Long,
    ) {
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it in 0..final })
        assertTrue(values.zipWithNext().all { (left, right) -> left <= right })
        assertEquals(final, values.last())
    }

    private fun snapshot(outcome: AnalysisOutcome): Map<String, ByteArray> =
        (COMPLETE_ARTIFACTS + "manifest.json").associateWith { name ->
            Files.readAllBytes(outcome.analysisDirectory.resolve(name))
        }

    private fun assertSnapshotEquals(
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual.getValue(name), name) }
    }

    private companion object {
        const val FAIL_POLICY = "fixtures/slice1/policies/fail.json"
        const val GATLING_BINARY_FIXTURE = "fixtures/slice1/gatling/binary-3.13.5/simulation.log"

        val COMPLETE_ARTIFACTS =
            setOf(
                "analysis-result.json",
                "identity.json",
                "normalized-1s.ndjson",
                "rollup-10s.ndjson",
                "rollup-30s.ndjson",
                "rollup-60s.ndjson",
                "run.json",
            )

        val OUT_OF_ORDER_CSV =
            """
            timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
            1767225601000,20,steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            1767225600000,900,spike,500,Error,fixture,text,false,,0,0,1,1,null,0,0,0
            1767225600010,850,spike,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            """.trimIndent()
    }
}

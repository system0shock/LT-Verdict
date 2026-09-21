package io.ltverdict.storage

import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.sha256Hex
import io.ltverdict.ingest.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class RunBundleStoreTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `history verifies bounded saved documents without rereading raw input`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "history.jtl")
            val identity = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val saved =
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.write(staging.resolve("identity.json"), identity)
                    Files.write(staging.resolve("analysis-result.json"), identity)
                }
            assertTrue(store.readComparisonHistory(byteLimit = 1).truncated)
            assertTrue(store.readComparisonHistory(byteLimit = 1).entries.isEmpty())
            // History is a view of saved facts; opening/replaying an analysis still verifies the raw file.
            Files.writeString(input.path, "changed source")
            val history = store.readComparisonHistory()
            assertEquals(1, history.entries.size)
            assertFalse(history.truncated)
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
            Files.writeString(saved.resolve("analysis-result.json"), "{}")
            assertThrows(IllegalStateException::class.java) { store.readComparisonHistory() }
        }

    @Test
    fun `accept is streaming content-addressed and idempotent`() =
        withStore { store, root ->
            val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
            val accepted = store.acceptInput(ByteArrayInputStream(bytes), "results.csv", bytes.size.toLong())

            assertEquals(SourceType.JMETER_CSV, accepted.sourceType)
            assertEquals("jmeter_jtl_csv-${accepted.sha256}", accepted.runId)
            assertEquals(bytes.size.toLong(), accepted.sizeBytes)
            assertEquals(root.resolve("runs").resolve(accepted.runId).resolve("inputs/source.bin"), accepted.path)
            assertArrayEquals(bytes, Files.readAllBytes(accepted.path))
            assertTrue(
                Files.isRegularFile(
                    accepted.path.parent.parent
                        .resolve("source.json"),
                ),
            )
            assertFalse(
                Files.exists(
                    accepted.path.parent.parent
                        .resolve("results.csv"),
                ),
            )
            assertEquals(accepted, store.acceptInput(ByteArrayInputStream(bytes), "renamed.xml"))
            assertEquals(accepted, store.requireInput(accepted.runId))
            assertStagingEmpty(root)

            Files.writeString(accepted.path, "tamper", StandardOpenOption.APPEND)
            assertThrows(IllegalStateException::class.java) { store.requireInput(accepted.runId) }
            assertThrows(IllegalStateException::class.java) {
                store.acceptInput(ByteArrayInputStream(bytes), "results.csv")
            }
            assertStagingEmpty(root)
        }

    @Test
    fun `invalid input and metadata leave no owned staging residue`() =
        withStore { store, root ->
            val csv = Files.readAllBytes(Path.of(CSV_FIXTURE))
            val invalidNames = listOf("", "../escape.jtl", "CON", "bad\u0000name", "a".repeat(256))
            invalidNames.forEach { name ->
                assertThrows(IllegalArgumentException::class.java) {
                    store.acceptInput(ByteArrayInputStream(csv), name)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.acceptInput(ByteArrayInputStream(byteArrayOf()), "empty.jtl")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.acceptInput(ByteArrayInputStream("unknown".encodeToByteArray()), "unknown.jtl")
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.acceptInput(ByteArrayInputStream(csv), "too-big.jtl", csv.size.toLong() - 1)
            }
            val atLimit = store.acceptInput(ByteArrayInputStream(csv), "exact.jtl", csv.size.toLong())
            assertTrue(atLimit.runId.startsWith("jmeter_jtl_csv-"))

            val xml = store.acceptInput(Files.newInputStream(Path.of(XML_FIXTURE)), "wrong.csv")
            assertTrue(xml.runId.startsWith("jmeter_jtl_xml-"))
            assertStagingEmpty(root)
        }

    @Test
    fun `run listing is sorted cursor stable and validates limits`() =
        withStore { store, _ ->
            repeat(5) { index ->
                val csv = csvWithTimestamp(1_700_000_000_000L + index)
                store.acceptInput(ByteArrayInputStream(csv), "run-$index.jtl")
            }

            val first = store.listRuns(null, 2)
            val second = store.listRuns(first.nextAfter, 2)
            val third = store.listRuns(second.nextAfter, 2)
            val ids = first.runs + second.runs + third.runs
            assertEquals(ids.map { it.runId }.sorted(), ids.map { it.runId })
            assertEquals(5, ids.size)
            assertTrue(first.nextAfter != null && second.nextAfter != null)
            assertEquals(null, third.nextAfter)
            assertThrows(IllegalArgumentException::class.java) { store.listRuns(null, 0) }
            assertThrows(IllegalArgumentException::class.java) { store.listRuns(null, 101) }
        }

    @Test
    fun `analysis listing is sorted cursor stable and validates published summaries`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")

            fun publish(
                suffix: String,
                policy: String,
            ): String {
                val identity = "{\"policy_sha256\":\"$policy\",\"run_id\":\"${input.runId}\",\"suffix\":\"$suffix\"}".encodeToByteArray()
                val analysisId = sha256Hex(identity)
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.write(staging.resolve("identity.json"), identity)
                    Files.writeString(
                        staging.resolve("analysis-result.json"),
                        "{\"policy_verdict\":\"PASS\",\"run_validity\":\"VALID\"}",
                    )
                }
                return analysisId
            }

            val firstId = publish("a", "a".repeat(64))
            val secondId = publish("b", "b".repeat(64))
            val first = store.listAnalyses(input.runId, null, 1)
            val second = store.listAnalyses(input.runId, first.nextAfter, 1)

            assertEquals(
                listOf(firstId, secondId).sorted(),
                listOf(first.analyses.single().analysisId, second.analyses.single().analysisId),
            )
            assertEquals(first.analyses.single().analysisId, first.nextAfter)
            assertEquals(null, second.nextAfter)
            assertEquals("PASS", first.analyses.single().policyVerdict)
            assertEquals("VALID", first.analyses.single().runValidity)
            assertThrows(IllegalArgumentException::class.java) { store.listAnalyses(input.runId, null, 0) }
            assertThrows(NoSuchElementException::class.java) { store.listAnalyses("jmeter_jtl_csv-${"0".repeat(64)}", null, 1) }
        }

    @Test
    fun `analysis publish is atomic verified and cleans failures`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val identity = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val target =
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.write(staging.resolve("identity.json"), identity)
                    Files.createDirectories(staging.resolve("nested"))
                    Files.writeString(staging.resolve("nested/result.json"), "{\"ok\":true}")
                }

            assertTrue(Files.isRegularFile(target.resolve("manifest.json")))
            assertEquals(target, store.readAnalysis(input.runId, analysisId)?.path)
            var called = false
            assertEquals(
                target,
                store.writeAnalysisAtomically(input.runId, analysisId) { called = true },
            )
            assertFalse(called)

            val failedId = "b".repeat(64)
            assertThrows(IllegalStateException::class.java) {
                store.writeAnalysisAtomically(input.runId, failedId) { staging ->
                    Files.writeString(staging.resolve("partial"), "partial")
                    error("writer failed")
                }
            }
            assertFalse(Files.exists(target.parent.resolve(failedId)))
            val collisionId = "c".repeat(64)
            val collision = target.parent.resolve(collisionId)
            assertThrows(IllegalStateException::class.java) {
                store.writeAnalysisAtomically(input.runId, collisionId) { staging ->
                    Files.writeString(staging.resolve("complete"), "complete")
                    Files.createFile(collision)
                }
            }
            assertTrue(Files.isRegularFile(collision))

            val transplantedId = "d".repeat(64)
            store.writeAnalysisAtomically(input.runId, transplantedId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
            }
            assertThrows(IllegalStateException::class.java) {
                store.readAnalysis(input.runId, transplantedId)
            }

            val otherInput = store.acceptInput(ByteArrayInputStream(csvWithTimestamp(1_700_000_000_999L)), "other.jtl")
            store.writeAnalysisAtomically(otherInput.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
            }
            assertThrows(IllegalStateException::class.java) {
                store.readAnalysis(otherInput.runId, analysisId)
            }

            assertThrows(IllegalArgumentException::class.java) {
                store.writeAnalysisAtomically(input.runId, "../escape") { }
            }
            assertStagingEmpty(root)

            Files.writeString(target.resolve("identity.json"), "tampered")
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
        }

    @Test
    fun `baseline replacement is atomic validated and survives data directory reopen`() {
        val root = tempDir.resolve("baseline-reopen")
        val first = manualBaselineSelection("first", reference('a', 'a'))
        val second = manualBaselineSelection("second", reference('b', 'b'))
        val baselinePath = root.resolve("baseline.json")

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            assertEquals(null, store.readBaseline())
            assertEquals(first, store.replaceBaseline(first))
            assertArrayEquals(canonicalJson(first), Files.readAllBytes(baselinePath))
            val beforeFailure = Files.readAllBytes(baselinePath)

            assertThrows(IllegalArgumentException::class.java) {
                store.replaceBaseline(JsonObject(second - "series"))
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.replaceBaseline(JsonObject(first + ("algorithm" to JsonPrimitive(1))))
            }
            assertArrayEquals(beforeFailure, Files.readAllBytes(baselinePath))
            assertEquals(second, store.replaceBaseline(second))
            assertStagingEmpty(root)
        }

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            assertEquals(second, store.readBaseline())
            store.clearBaseline()
            assertEquals(null, store.readBaseline())
            assertFalse(Files.exists(baselinePath))
        }
    }

    @Test
    fun `baseline read rejects corrupt and oversized private state`() {
        val root = tempDir.resolve("baseline-corrupt")
        DataDirectory.open(root).use { directory ->
            Files.writeString(root.resolve("baseline.json"), "{}")
            assertThrows(IllegalStateException::class.java) { RunBundleStore(directory).readBaseline() }
        }
        Files.write(root.resolve("baseline.json"), ByteArray(32 * 1024 + 1))
        DataDirectory.open(root).use { directory ->
            assertThrows(IllegalStateException::class.java) { RunBundleStore(directory).readBaseline() }
        }
    }

    @Test
    fun `baseline conditions persist per exact binding and explicit clear removes them`() {
        val root = tempDir.resolve("baseline-conditions-reopen")
        val baseline = reference('a', 'a')
        val firstCurrent = reference('b', 'b')
        val secondCurrent = reference('c', 'c')
        val first = baselineConditionRecord(baseline, firstCurrent, null, "CONFIRMED", Instant.parse("2026-09-06T10:00:00Z"))
        val windows = WindowComparisonRequest("before", "after")
        val second = baselineConditionRecord(baseline, secondCurrent, windows, "NOT_CONFIRMED", Instant.parse("2026-09-06T11:00:00Z"))

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            store.replaceBaseline(manualBaselineSelection("release", baseline))
            assertEquals(first, store.replaceBaselineCondition(first))
            assertEquals(second, store.replaceBaselineCondition(second))
            assertEquals(first, store.readBaselineCondition(baseline, firstCurrent, null))
            assertEquals(second, store.readBaselineCondition(baseline, secondCurrent, windows))
            assertEquals(null, store.readBaselineCondition(baseline, reference('d', 'd'), null))
            assertEquals(null, store.readBaselineCondition(baseline, secondCurrent, WindowComparisonRequest("before", "other")))
            assertEquals(2L, Files.list(root.resolve("baseline-conditions")).use { it.count() })
        }

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            assertEquals(first, store.readBaselineCondition(baseline, firstCurrent, null))
            assertEquals(second, store.readBaselineCondition(baseline, secondCurrent, windows))
            store.clearBaseline()
            assertEquals(null, store.readBaselineCondition(baseline, firstCurrent, null))
            assertFalse(Files.exists(root.resolve("baseline-conditions")))
        }
    }

    @Test
    fun `baseline condition read rejects corrupt oversized and special keyed state`() {
        val root = tempDir.resolve("baseline-conditions-corrupt")
        val baseline = reference('a', 'a')
        val current = reference('b', 'b')
        val record = baselineConditionRecord(baseline, current, null, "UNKNOWN", Instant.parse("2026-09-06T12:00:00Z"))

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            store.replaceBaselineCondition(record)
            val path = Files.list(root.resolve("baseline-conditions")).use { it.findFirst().orElseThrow() }

            Files.writeString(path, "{}", StandardOpenOption.TRUNCATE_EXISTING)
            assertThrows(IllegalStateException::class.java) { store.readBaselineCondition(baseline, current, null) }

            Files.write(path, ByteArray(4 * 1024 + 1), StandardOpenOption.TRUNCATE_EXISTING)
            assertThrows(IllegalStateException::class.java) { store.readBaselineCondition(baseline, current, null) }

            Files.delete(path)
            Files.createDirectory(path)
            assertThrows(IllegalStateException::class.java) { store.readBaselineCondition(baseline, current, null) }
        }
    }

    @Test
    fun `analysis documents are returned only after bundle verification`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val identity = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val result = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.write(staging.resolve("analysis-result.json"), result)
            }

            val documents = store.readAnalysisDocuments(input.runId, analysisId) ?: error("missing analysis")

            assertEquals(Json.parseToJsonElement(result.decodeToString()).jsonObject, documents.first)
            assertEquals(Json.parseToJsonElement(identity.decodeToString()).jsonObject, documents.second)
        }

    private fun withStore(block: (RunBundleStore, Path) -> Unit) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory -> block(RunBundleStore(directory), directory.root) }
    }

    private fun assertStagingEmpty(root: Path) {
        Files.list(root.resolve(".staging")).use { assertEquals(0L, it.count()) }
    }

    private fun csvWithTimestamp(timestamp: Long): ByteArray {
        val lines = Files.readAllLines(Path.of(CSV_FIXTURE))
        return (lines.first() + "\n" + lines[1].replaceBefore(',', timestamp.toString()) + "\n").encodeToByteArray()
    }

    private fun reference(
        run: Char,
        analysis: Char,
    ) = buildJsonObject {
        put("run_id", "jmeter_jtl_csv-${run.toString().repeat(64)}")
        put("analysis_id", analysis.toString().repeat(64))
    }

    private companion object {
        const val CSV_FIXTURE = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
        const val XML_FIXTURE = "fixtures/slice1/jmeter/xml-5.6.3/input.xml"
    }
}

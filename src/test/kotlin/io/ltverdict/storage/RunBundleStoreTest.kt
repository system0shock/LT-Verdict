package io.ltverdict.storage

import io.ltverdict.core.sha256Hex
import io.ltverdict.ingest.SourceType
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

class RunBundleStoreTest {
    @TempDir
    lateinit var tempDir: Path

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
            fun publish(suffix: String, policy: String): String {
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

            assertEquals(listOf(firstId, secondId).sorted(), listOf(first.analyses.single().analysisId, second.analyses.single().analysisId))
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

    private companion object {
        const val CSV_FIXTURE = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
        const val XML_FIXTURE = "fixtures/slice1/jmeter/xml-5.6.3/input.xml"
    }
}

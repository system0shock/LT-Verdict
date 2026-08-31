package io.ltverdict.storage

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
    fun `analysis publish is atomic verified and cleans failures`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val analysisId = "a".repeat(64)
            val target =
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.writeString(staging.resolve("identity.json"), "{}")
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

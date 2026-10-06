package io.ltverdict.storage

import io.ltverdict.core.MAX_RELEASE_ANALYSES
import io.ltverdict.core.MAX_RELEASE_BYTES
import io.ltverdict.core.MAX_RELEASE_NOTES_BYTES
import io.ltverdict.core.MAX_RELEASE_TEXT_BYTES
import io.ltverdict.core.RELEASE_PROFILE_FIELDS
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.recognizeRunPeriod
import io.ltverdict.core.releaseId
import io.ltverdict.core.runPeriodJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateRelease
import io.ltverdict.ingest.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

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
            // History is a view of saved facts; opening/replaying an analysis checks only the raw file size (not its hash).
            Files.writeString(input.path, "changed source")
            val history = store.readComparisonHistory()
            assertEquals(1, history.entries.size)
            assertFalse(history.truncated)
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
            Files.writeString(saved.resolve("analysis-result.json"), "{}")
            assertThrows(IllegalStateException::class.java) { store.readComparisonHistory() }
        }

    @Test
    fun `analysis list omits policy id when stored policy exceeds the read limit`() =
        withStore { store, _ ->
            val input = Files.newInputStream(Path.of(CSV_FIXTURE)).use { store.acceptInput(it, "large-policy.jtl") }
            val prefix = """{"policy_id":"oversized"}"""
            val policy = (prefix + " ".repeat(1_048_577 - prefix.length)).encodeToByteArray()
            val identity = """{"policy_sha256":"${sha256Hex(policy)}","run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.writeString(staging.resolve("analysis-result.json"), """{"policy_verdict":"PASS","run_validity":"VALID"}""")
                Files.write(staging.resolve("policy.json"), policy)
            }

            val page = store.listAnalyses(input.runId, null, 10)
            assertEquals(analysisId, page.analyses.single().analysisId)
            assertEquals(null, page.analyses.single().policyId)
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
    fun `a slow upload does not block store reads`() =
        withStore { store, root ->
            val existing = store.acceptInput(ByteArrayInputStream(csvWithTimestamp(1_700_000_000_000L)), "existing.jtl")
            val bytes = csvWithTimestamp(1_700_000_000_001L)
            val copying = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val upload =
                    executor.submit<AcceptedInput> {
                        store.acceptInput(
                            object : ByteArrayInputStream(bytes) {
                                private var paused = false

                                override fun read(
                                    buffer: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ): Int {
                                    if (!paused) {
                                        paused = true
                                        val count = super.read(buffer, offset, 1)
                                        copying.countDown()
                                        check(release.await(30, TimeUnit.SECONDS))
                                        return count
                                    }
                                    return super.read(buffer, offset, length)
                                }
                            },
                            "slow.jtl",
                        )
                    }
                assertTrue(copying.await(5, TimeUnit.SECONDS))
                val listed = executor.submit<RunPage> { store.listRuns(null, 10) }
                val required = executor.submit<AcceptedInput> { store.requireInput(existing.runId) }
                assertEquals(listOf(existing.runId), listed.get(5, TimeUnit.SECONDS).runs.map { it.runId })
                assertEquals(existing, required.get(5, TimeUnit.SECONDS))
                release.countDown()
                val uploaded = upload.get(10, TimeUnit.SECONDS)
                assertEquals(
                    setOf(existing.runId, uploaded.runId),
                    store
                        .listRuns(null, 10)
                        .runs
                        .map { it.runId }
                        .toSet(),
                )
                assertStagingEmpty(root)
            } finally {
                copying.countDown()
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `a slow analysis writer does not block store reads`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val existingIdentity =
                """{"policy_sha256":"${"a".repeat(64)}","run_id":"${input.runId}","version":1}""".encodeToByteArray()
            val existingId = sha256Hex(existingIdentity)
            store.writeAnalysisAtomically(input.runId, existingId) { staging ->
                Files.write(staging.resolve("identity.json"), existingIdentity)
                Files.writeString(staging.resolve("analysis-result.json"), """{"policy_verdict":"PASS","run_validity":"VALID"}""")
            }
            val identity = """{"run_id":"${input.runId}","version":2}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val writing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val writer =
                    executor.submit<Path> {
                        store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                            writing.countDown()
                            check(release.await(30, TimeUnit.SECONDS))
                            Files.write(staging.resolve("identity.json"), identity)
                            Files.writeString(
                                staging.resolve("analysis-result.json"),
                                """{"policy_verdict":"PASS","run_validity":"VALID"}""",
                            )
                        }
                    }
                assertTrue(writing.await(5, TimeUnit.SECONDS))
                val listedRuns = executor.submit<RunPage> { store.listRuns(null, 10) }
                val read = executor.submit<StoredAnalysis?> { store.readAnalysis(input.runId, existingId) }
                val listedAnalyses = executor.submit<AnalysisPage> { store.listAnalyses(input.runId, null, 10) }
                assertEquals(listOf(input.runId), listedRuns.get(5, TimeUnit.SECONDS).runs.map { it.runId })
                assertEquals(
                    existingId,
                    read
                        .get(5, TimeUnit.SECONDS)
                        ?.path
                        ?.fileName
                        ?.toString(),
                )
                assertEquals(listOf(existingId), listedAnalyses.get(5, TimeUnit.SECONDS).analyses.map { it.analysisId })
                release.countDown()
                assertEquals(analysisId, writer.get(10, TimeUnit.SECONDS).fileName.toString())
                assertEquals(
                    analysisId,
                    store
                        .readAnalysis(input.runId, analysisId)
                        ?.path
                        ?.fileName
                        ?.toString(),
                )
                assertStagingEmpty(root)
            } finally {
                writing.countDown()
                release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `two identical uploads stage concurrently and converge on one run`() =
        withStore { store, root ->
            val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val uploads =
                    (1..2).map {
                        executor.submit<AcceptedInput> {
                            store.acceptInput(
                                object : ByteArrayInputStream(bytes) {
                                    private var first = true

                                    override fun read(
                                        buffer: ByteArray,
                                        offset: Int,
                                        length: Int,
                                    ): Int {
                                        if (first) {
                                            first = false
                                            barrier.await(5, TimeUnit.SECONDS)
                                        }
                                        return super.read(buffer, offset, length)
                                    }
                                },
                                "same.jtl",
                            )
                        }
                    }
                val accepted = uploads.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(accepted[0], accepted[1])
                assertEquals(1L, Files.list(root.resolve("runs")).use { it.count() })
                assertArrayEquals(bytes, Files.readAllBytes(accepted[0].path))
                assertStagingEmpty(root)
            } finally {
                barrier.reset()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `two identical analysis writes converge on one analysis`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val identity = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val barrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val writes =
                    (1..2).map {
                        executor.submit<Path> {
                            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                                barrier.await(5, TimeUnit.SECONDS)
                                Files.write(staging.resolve("identity.json"), identity)
                                Files.writeString(
                                    staging.resolve("analysis-result.json"),
                                    """{"policy_verdict":"PASS","run_validity":"VALID"}""",
                                )
                            }
                        }
                    }
                val paths = writes.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(paths[0], paths[1])
                assertEquals(1L, Files.list(paths[0].parent).use { it.count() })
                assertEquals(paths[0], store.readAnalysis(input.runId, analysisId)?.path)
                assertStagingEmpty(root)
            } finally {
                barrier.reset()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `failed upload clears staging after partial copy`() =
        withStore { store, root ->
            val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
            assertThrows(IOException::class.java) {
                store.acceptInput(
                    object : ByteArrayInputStream(bytes) {
                        private var first = true

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            if (first) {
                                first = false
                                return super.read(buffer, offset, 1)
                            }
                            throw IOException("copy failed")
                        }
                    },
                    "failed.jtl",
                )
            }
            assertEquals(0L, Files.list(root.resolve("runs")).use { it.count() })
            assertStagingEmpty(root)
        }

    @Test
    fun `reads do not rehash stored input so same-length substitution is intentionally not detected`() =
        withStore { store, root ->
            val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
            val accepted = store.acceptInput(ByteArrayInputStream(bytes), "results.csv")

            // Trusted local contour: run_id = source type + hash at accept time; reads check existence and size only.
            val substituted = bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            Files.write(accepted.path, substituted)
            assertEquals(accepted, store.requireInput(accepted.runId))
            assertEquals(listOf(accepted.runId), store.listRuns(null, 10).runs.map { it.runId })
            assertTrue(store.listAnalyses(accepted.runId, null, 10).analyses.isEmpty())
            // Re-accepting the original bytes still compares them byte-for-byte with the stored file.
            assertThrows(IllegalStateException::class.java) { store.acceptInput(ByteArrayInputStream(bytes), "again.csv") }

            // The cheap size check remains.
            Files.write(accepted.path, bytes + 0)
            assertThrows(IllegalStateException::class.java) { store.requireInput(accepted.runId) }
            assertThrows(IllegalStateException::class.java) { store.listRuns(null, 10) }
            Files.write(accepted.path, bytes)
            assertEquals(accepted, store.requireInput(accepted.runId))
            assertStagingEmpty(root)
        }

    @Test
    fun `reading a saved analysis does not rehash artifacts but still checks paths and sizes`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val identity = """{"policy_sha256":"${"a".repeat(64)}","run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val saved =
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.write(staging.resolve("identity.json"), identity)
                    Files.writeString(staging.resolve("analysis-result.json"), """{"policy_verdict":"PASS","run_validity":"VALID"}""")
                    Files.writeString(staging.resolve("normalized-1s.ndjson"), "0123456789")
                }
            val artifact = saved.resolve("normalized-1s.ndjson")

            Files.writeString(artifact, "9876543210")
            assertEquals(saved, store.readAnalysis(input.runId, analysisId)?.path)
            assertEquals(1, store.listAnalyses(input.runId, null, 10).analyses.size)

            Files.writeString(artifact, "short")
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
            Files.writeString(artifact, "0123456789")
            Files.writeString(saved.resolve("extra.json"), "{}")
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
            Files.delete(saved.resolve("extra.json"))
            Files.delete(artifact)
            assertThrows(IllegalStateException::class.java) { store.readAnalysis(input.runId, analysisId) }
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
    fun `run listing is newest first with run id ties, cursor stable and validates limits`() {
        val clock = SettableClock(Instant.parse("2026-10-07T12:00:00Z"))
        withStore(clock) { store, _ ->
            // Four distinct instants plus a tie: runs 3 and 4 are accepted in the same millisecond.
            val accepted =
                listOf(0L, 1_000L, 2_000L, 3_000L, 3_000L).mapIndexed { index, offset ->
                    clock.instant = Instant.parse("2026-10-07T12:00:00Z").plusMillis(offset)
                    store.acceptInput(ByteArrayInputStream(csvWithTimestamp(1_700_000_000_000L + index)), "load.jtl")
                }
            assertEquals(
                listOf("2026-10-07T12:00:00.000Z", "2026-10-07T12:00:01.000Z", "2026-10-07T12:00:02.000Z"),
                accepted.take(3).map {
                    it.acceptedAt
                },
            )
            val expected = accepted.sortedWith(compareByDescending<AcceptedInput> { it.acceptedAt }.thenBy { it.runId }).map { it.runId }

            val first = store.listRuns(null, 2)
            val second = store.listRuns(first.nextAfter, 2)
            val third = store.listRuns(second.nextAfter, 2)
            val ids = first.runs + second.runs + third.runs
            assertEquals(expected, ids.map { it.runId })
            assertEquals(accepted.associate { it.runId to it.acceptedAt }, ids.associate { it.runId to it.acceptedAt })
            assertTrue(first.nextAfter != null && second.nextAfter != null)
            assertEquals(null, third.nextAfter)
            assertThrows(IllegalArgumentException::class.java) { store.listRuns(null, 0) }
            assertThrows(IllegalArgumentException::class.java) { store.listRuns(null, 101) }
            // A well-formed cursor that names no stored run cannot be placed in the order.
            assertThrows(IllegalArgumentException::class.java) { store.listRuns("jmeter_jtl_csv-${"f".repeat(64)}", 2) }
        }
    }

    @Test
    fun `runs stored before accepted_at existed stay readable and follow the timestamped ones`() {
        val clock = SettableClock(Instant.parse("2026-10-07T12:00:00Z"))
        withStore(clock) { store, root ->
            val legacy =
                (0..2).map { index ->
                    val input = store.acceptInput(ByteArrayInputStream(csvWithTimestamp(1_700_000_000_000L + index)), "legacy-$index.jtl")
                    // The exact bytes older versions wrote: five keys, no accepted_at.
                    Files.write(
                        root.resolve("runs").resolve(input.runId).resolve("source.json"),
                        canonicalJson(
                            buildJsonObject {
                                put("original_filename", input.originalFilename)
                                put("run_id", input.runId)
                                put("sha256", input.sha256)
                                put("size_bytes", input.sizeBytes)
                                put("source_type", input.sourceType.wireName)
                            },
                        ),
                    )
                    input.runId
                }
            assertTrue(legacy.all { store.requireInput(it).acceptedAt == null })
            clock.instant = Instant.parse("2026-10-07T12:00:05Z")
            val fresh = store.acceptInput(ByteArrayInputStream(csvWithTimestamp(1_700_000_000_099L)), "fresh.jtl").runId

            val expected = listOf(fresh) + legacy.sorted()
            // One run per page crosses the boundary between timestamped and legacy runs.
            val pages = mutableListOf<String>()
            var after: String? = null
            do {
                val page = store.listRuns(after, 1)
                pages += page.runs.map { it.runId }
                after = page.nextAfter
            } while (after != null)
            assertEquals(expected, pages)
            assertEquals(
                null,
                store
                    .listRuns(null, 10)
                    .runs
                    .last()
                    .acceptedAt,
            )
            assertEquals(
                "2026-10-07T12:00:05.000Z",
                store
                    .listRuns(null, 10)
                    .runs
                    .first()
                    .acceptedAt,
            )
        }
    }

    @Test
    fun `accepting the same bytes again keeps the first accepted_at and never changes identity`() {
        val clock = SettableClock(Instant.parse("2026-10-07T12:00:00Z"))
        withStore(clock) { store, root ->
            val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
            val first = store.acceptInput(ByteArrayInputStream(bytes), "load.jtl")
            val sourceJson = root.resolve("runs").resolve(first.runId).resolve("source.json")
            val storedBytes = Files.readAllBytes(sourceJson)

            clock.instant = Instant.parse("2026-10-08T08:30:00Z")
            val again = store.acceptInput(ByteArrayInputStream(bytes), "renamed.jtl")

            assertEquals(first, again)
            assertEquals("2026-10-07T12:00:00.000Z", again.acceptedAt)
            assertArrayEquals(storedBytes, Files.readAllBytes(sourceJson))
            assertEquals(listOf(first.runId), store.listRuns(null, 10).runs.map { it.runId })
        }
        // A different acceptance instant, in a separate data directory, yields the same run_id.
        val otherClock = SettableClock(Instant.parse("2031-01-01T00:00:00Z"))
        withStore(otherClock) { store, _ ->
            val elsewhere = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "load.jtl")
            assertEquals("2031-01-01T00:00:00.000Z", elsewhere.acceptedAt)
            withStore(clock) { other, _ ->
                assertEquals(elsewhere.runId, other.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "load.jtl").runId)
            }
        }
    }

    @Test
    fun `a malformed accepted_at is reported as corrupt storage`() {
        val clock = SettableClock(Instant.parse("2026-10-07T12:00:00Z"))
        withStore(clock) { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "load.jtl")
            val sourceJson = root.resolve("runs").resolve(input.runId).resolve("source.json")
            val good = Files.readString(sourceJson)
            listOf(
                "2026-10-07T12:00:00Z",
                "2026-10-07T12:00:00.5Z",
                "yesterday",
                "2026-10-07 12:00:00.000",
                "2026-13-40T12:00:00.000Z",
            ).forEach { bad ->
                Files.writeString(sourceJson, good.replace("2026-10-07T12:00:00.000Z", bad))
                assertThrows(IllegalStateException::class.java) { store.requireInput(input.runId) }
                assertThrows(IllegalStateException::class.java) { store.listRuns(null, 10) }
            }
            Files.writeString(sourceJson, good)
            assertEquals("2026-10-07T12:00:00.000Z", store.requireInput(input.runId).acceptedAt)
        }
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
    fun `slot key depends on series and arm and nothing else`() =
        withStore { store, root ->
            val input = slotInput(store)
            val plain = saveAnalysis(store, input, "plain")
            val other = saveAnalysis(store, input, "other")
            val blue = saveAnalysis(store, input, "blue", arm = "blue")
            val literal = saveAnalysis(store, input, "literal", arm = "null")
            val slots = root.resolve("baselines")

            val first = slotSelection(input, "a", plain)
            store.replaceBaselineSlot(first, null)
            store.replaceBaselineSlot(slotSelection(input, "a", blue), "blue")
            store.replaceBaselineSlot(slotSelection(input, "b", plain), null)
            assertEquals(
                setOf(slotKey("a", null), slotKey("a", "blue"), slotKey("b", null)).map { "$it.json" }.toSet(),
                fileNames(slots),
            )
            val path = slots.resolve("${slotKey("a", null)}.json")
            assertArrayEquals(canonicalJson(first), Files.readAllBytes(path))

            val replacement = slotSelection(input, "a", other)
            store.replaceBaselineSlot(replacement, null)
            assertEquals(3, fileNames(slots).size)
            assertArrayEquals(canonicalJson(replacement), Files.readAllBytes(path))
            assertTrue(Files.size(path) <= 32 * 1024)

            store.replaceBaselineSlot(slotSelection(input, "a", literal), "null")
            assertEquals(4, fileNames(slots).size)
            assertTrue(slotKey("a", null) != slotKey("a", "null"))
            assertStagingEmpty(root)
        }

    @Test
    fun `legacy file is a slot until its key is written`() =
        withStore { store, root ->
            val input = slotInput(store)
            val plain = saveAnalysis(store, input, "plain")
            val other = saveAnalysis(store, input, "other")
            val legacy = slotSelection(input, "legacy-series", plain)
            store.replaceBaseline(legacy)
            assertEquals(listOf(BaselineSlot("legacy-series", null, legacy, true)), store.listBaselineSlots())

            val unrelated = slotSelection(input, "other-series", other)
            store.replaceBaselineSlot(unrelated, null)
            assertTrue(Files.exists(root.resolve("baseline.json")))
            assertEquals(
                listOf(
                    BaselineSlot("legacy-series", null, legacy, true),
                    BaselineSlot("other-series", null, unrelated, false),
                ),
                store.listBaselineSlots(),
            )

            val winner = slotSelection(input, "legacy-series", other)
            store.replaceBaselineSlot(winner, null)
            assertFalse(Files.exists(root.resolve("baseline.json")))
            assertEquals(
                listOf(
                    BaselineSlot("legacy-series", null, winner, false),
                    BaselineSlot("other-series", null, unrelated, false),
                ),
                store.listBaselineSlots(),
            )
            assertStagingEmpty(root)
        }

    @Test
    fun `a shadowed legacy file loses to the slot of the same key`() =
        withStore { store, root ->
            val input = slotInput(store)
            val plain = saveAnalysis(store, input, "plain")
            val other = saveAnalysis(store, input, "other")
            val third = saveAnalysis(store, input, "third")
            val legacy = slotSelection(input, "s", plain)
            val slot = slotSelection(input, "s", other)
            val shadow = {
                store.replaceBaseline(legacy)
                Files.createDirectories(root.resolve("baselines"))
                Files.write(root.resolve("baselines/${slotKey("s", null)}.json"), canonicalJson(slot))
            }
            shadow()
            assertEquals(listOf(BaselineSlot("s", null, slot, false)), store.listBaselineSlots())
            assertTrue(Files.exists(root.resolve("baseline.json")))
            // The series-less read stays on the legacy file, exactly as before slots existed.
            assertEquals(legacy, store.readBaselineSlotWithCondition(null, null, reference('c', 'c'), null).first?.selection)

            store.replaceBaselineSlot(slotSelection(input, "s", third), null)
            assertFalse(Files.exists(root.resolve("baseline.json")))

            Files.delete(root.resolve("baselines/${slotKey("s", null)}.json"))
            shadow()
            assertTrue(store.clearBaselineSlot("s", null))
            assertFalse(Files.exists(root.resolve("baseline.json")))
            assertEquals(emptyList<BaselineSlot>(), store.listBaselineSlots())
            assertFalse(store.clearBaselineSlot("s", null))
            assertStagingEmpty(root)
        }

    @Test
    fun `slot limit counts effective keys`() =
        withStore { store, root ->
            val input = slotInput(store)
            val analyses = (0 until MAX_BASELINE_SLOTS + 2).map { saveAnalysis(store, input, "n$it") }
            repeat(MAX_BASELINE_SLOTS) { store.replaceBaselineSlot(slotSelection(input, "series-$it", analyses[it]), null) }
            assertEquals(MAX_BASELINE_SLOTS, store.listBaselineSlots().size)

            val refused =
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceBaselineSlot(slotSelection(input, "extra", analyses[MAX_BASELINE_SLOTS]), null)
                }
            assertEquals("BASELINE_SLOTS_LIMIT_REACHED", refused.message)
            store.replaceBaselineSlot(slotSelection(input, "series-0", analyses[MAX_BASELINE_SLOTS + 1]), null)
            assertEquals(MAX_BASELINE_SLOTS, store.listBaselineSlots().size)

            // The old writer has no limit: a legacy file of another key is the 65th effective slot.
            store.replaceBaseline(slotSelection(input, "legacy-extra", analyses[MAX_BASELINE_SLOTS]))
            assertEquals(MAX_BASELINE_SLOTS + 1, store.listBaselineSlots().size)
            assertThrows(IllegalArgumentException::class.java) {
                store.replaceBaselineSlot(slotSelection(input, "extra", analyses[MAX_BASELINE_SLOTS]), null)
            }
            store.replaceBaselineSlot(slotSelection(input, "series-1", analyses[MAX_BASELINE_SLOTS + 1]), null)

            // A legacy file shadowed by a slot of the same key adds nothing.
            store.replaceBaseline(slotSelection(input, "series-2", analyses[MAX_BASELINE_SLOTS]))
            assertEquals(MAX_BASELINE_SLOTS, store.listBaselineSlots().size)
            store.replaceBaselineSlot(slotSelection(input, "series-3", analyses[MAX_BASELINE_SLOTS + 1]), null)
            assertEquals(MAX_BASELINE_SLOTS, fileNames(root.resolve("baselines")).size)
            assertStagingEmpty(root)
        }

    @Test
    fun `the slot directory rejects foreign entries and too many elements`() =
        withStore { store, root ->
            val input = slotInput(store)
            store.replaceBaselineSlot(slotSelection(input, "s", saveAnalysis(store, input, "a")), null)
            val slots = root.resolve("baselines")
            val corrupt = {
                assertTrue(
                    assertThrows(IllegalStateException::class.java) { store.listBaselineSlots() }.message!!.startsWith("CORRUPT_BASELINE"),
                )
            }

            Files.writeString(slots.resolve("notes.txt"), "x")
            corrupt()
            Files.delete(slots.resolve("notes.txt"))

            Files.createDirectory(slots.resolve("${"d".repeat(64)}.json"))
            corrupt()
            Files.delete(slots.resolve("${"d".repeat(64)}.json"))
            assertEquals(1, store.listBaselineSlots().size)

            (0 until MAX_BASELINE_SLOTS + 2).forEach { Files.writeString(slots.resolve("%064x.json".format(it + 1)), "{}") }
            corrupt()
        }

    @Test
    fun `slot arm is rederived from the baseline analysis identity`() =
        withStore { store, root ->
            val input = slotInput(store)
            val plain = saveAnalysis(store, input, "plain")
            val blue = saveAnalysis(store, input, "blue", arm = "blue")
            val nullArm = saveAnalysis(store, input, "null-arm", extraIdentity = mapOf("resource_arm" to JsonNull))
            val badArm = saveAnalysis(store, input, "bad-arm", extraIdentity = mapOf("resource_arm" to JsonPrimitive(5)))

            store.replaceBaselineSlot(slotSelection(input, "s", blue), "blue")
            assertEquals("blue", store.listBaselineSlots().single().arm)
            // JSON null and an absent field are the same arm.
            store.replaceBaselineSlot(slotSelection(input, "n", nullArm), null)
            assertNull(store.listBaselineSlots().single { it.series == "n" }.arm)

            // A write names the arm of the analysis or fails before it touches the directory.
            assertEquals(
                "BASELINE_ARM_MISMATCH",
                assertThrows(
                    IllegalArgumentException::class.java,
                ) { store.replaceBaselineSlot(slotSelection(input, "x", blue), null) }.message,
            )
            assertThrows(IllegalArgumentException::class.java) { store.replaceBaselineSlot(slotSelection(input, "x", plain), "blue") }
            assertEquals(
                "BASELINE_ANALYSIS_NOT_FOUND",
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceBaselineSlot(manualBaselineSelection("x", reference('a', 'a')), null)
                }.message,
            )
            assertTrue(
                assertThrows(IllegalStateException::class.java) { store.replaceBaselineSlot(slotSelection(input, "x", badArm), null) }
                    .message!!
                    .startsWith("CORRUPT_BASELINE"),
            )
            assertEquals(2, store.listBaselineSlots().size)

            // A slot file whose name is not the key of (series, arm of the identity) is corrupt, and so is a missing analysis.
            val slots = root.resolve("baselines")
            val named = slots.resolve("${slotKey("s", "blue")}.json")
            val renamed = slots.resolve("${slotKey("s", null)}.json")
            Files.move(named, renamed)
            assertTrue(
                assertThrows(IllegalStateException::class.java) { store.listBaselineSlots() }.message!!.startsWith("CORRUPT_BASELINE"),
            )
            Files.move(renamed, named)
            assertEquals(2, store.listBaselineSlots().size)
            DataDirectory.deleteTree(root.resolve("runs/${input.runId}/analyses/$blue"))
            assertTrue(
                assertThrows(IllegalStateException::class.java) { store.listBaselineSlots() }.message!!.startsWith("CORRUPT_BASELINE"),
            )
        }

    @Test
    fun `identity read is small and verified`() =
        withStore { store, root ->
            val input = slotInput(store)
            val analysisId = saveAnalysis(store, input, "i", arm = "blue")
            assertEquals("blue", (store.readAnalysisIdentity(input.runId, analysisId)!!["resource_arm"] as JsonPrimitive).content)
            assertNull(store.readAnalysisIdentity(input.runId, "f".repeat(64)))
            assertNull(store.readAnalysisIdentity("jmeter_jtl_csv-${"e".repeat(64)}", analysisId))
            assertThrows(IllegalArgumentException::class.java) { store.readAnalysisIdentity("x", analysisId) }
            assertThrows(IllegalArgumentException::class.java) { store.readAnalysisIdentity(input.runId, "x") }

            val analysis = root.resolve("runs/${input.runId}/analyses/$analysisId")
            Files.writeString(analysis.resolve("analysis-result.json"), "not even json")
            assertNotNull(store.readAnalysisIdentity(input.runId, analysisId))

            val identityPath = analysis.resolve("identity.json")
            val tampered = Files.readString(identityPath).replace("blue", "teal")
            Files.writeString(identityPath, tampered)
            assertTrue(
                assertThrows(IllegalStateException::class.java) { store.readAnalysisIdentity(input.runId, analysisId) }
                    .message!!
                    .startsWith("CORRUPT_RUN_BUNDLE"),
            )
        }

    @Test
    fun `slots survive reopen and writes leave no staging residue`() {
        val root = tempDir.resolve("slots-reopen")
        lateinit var first: JsonObject
        lateinit var second: JsonObject
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            val input = slotInput(store)
            first = slotSelection(input, "first", saveAnalysis(store, input, "one"))
            second = slotSelection(input, "second", saveAnalysis(store, input, "two"))
            store.replaceBaselineSlot(first, null)
            store.replaceBaselineSlot(second, null)
            assertThrows(IllegalArgumentException::class.java) { store.replaceBaselineSlot(JsonObject(second - "series"), null) }
            assertThrows(IllegalArgumentException::class.java) { store.replaceBaselineSlot(second, "blue") }
            assertStagingEmpty(root)
            assertTrue(store.clearBaselineSlot("second", null))
            assertStagingEmpty(root)
        }
        DataDirectory.open(root).use { directory ->
            assertEquals(listOf(first), RunBundleStore(directory).listBaselineSlots().map { it.selection })
        }
    }

    @Test
    fun `slot read selects by series and arm and carries the condition of the pair`() =
        withStore { store, _ ->
            val input = slotInput(store)
            val plain = saveAnalysis(store, input, "plain")
            val blue = saveAnalysis(store, input, "blue", arm = "blue")
            val legacy = saveAnalysis(store, input, "legacy")
            val current = reference(input, saveAnalysis(store, input, "current"))
            val plainSelection = slotSelection(input, "S", plain)
            val blueSelection = slotSelection(input, "S", blue)
            store.replaceBaselineSlot(plainSelection, null)
            store.replaceBaselineSlot(blueSelection, "blue")
            val legacySelection = slotSelection(input, "L", legacy)
            store.replaceBaseline(legacySelection)
            val record = baselineConditionRecord(reference(input, plain), current, null, "CONFIRMED", Instant.parse("2026-09-06T10:00:00Z"))
            store.replaceBaselineCondition(record)

            val (slot, condition) = store.readBaselineSlotWithCondition("S", null, current, null)
            assertEquals(plainSelection, slot?.selection)
            assertEquals(record, condition)
            val (blueSlot, blueCondition) = store.readBaselineSlotWithCondition("S", "blue", current, null)
            assertEquals(blueSelection, blueSlot?.selection)
            assertNull(blueCondition)
            assertEquals(null to null, store.readBaselineSlotWithCondition("missing", null, current, null))
            assertEquals(null to null, store.readBaselineSlotWithCondition("S", "red", current, null))
            val (legacySlot, legacyCondition) = store.readBaselineSlotWithCondition(null, null, current, null)
            assertEquals(BaselineSlot("L", null, legacySelection, true), legacySlot)
            assertNull(legacyCondition)
        }

    @Test
    fun `scoped delete keeps conditions used by another slot`() =
        withStore { store, _ ->
            val input = slotInput(store)
            val shared = saveAnalysis(store, input, "shared")
            val alone = saveAnalysis(store, input, "alone")
            val current = reference(input, saveAnalysis(store, input, "current"))
            store.replaceBaselineSlot(slotSelection(input, "A", shared), null)
            store.replaceBaselineSlot(slotSelection(input, "B", shared), null)
            store.replaceBaselineSlot(slotSelection(input, "C", alone), null)
            val at = Instant.parse("2026-09-06T10:00:00Z")
            val sharedRecord = baselineConditionRecord(reference(input, shared), current, null, "CONFIRMED", at)
            val aloneRecord = baselineConditionRecord(reference(input, alone), current, null, "NOT_CONFIRMED", at)
            store.replaceBaselineCondition(sharedRecord)
            store.replaceBaselineCondition(aloneRecord)

            assertTrue(store.clearBaselineSlot("A", null))
            assertEquals(sharedRecord, store.readBaselineCondition(reference(input, shared), current, null))
            assertEquals(aloneRecord, store.readBaselineCondition(reference(input, alone), current, null))
            assertTrue(store.clearBaselineSlot("B", null))
            assertNull(store.readBaselineCondition(reference(input, shared), current, null))
            assertEquals(aloneRecord, store.readBaselineCondition(reference(input, alone), current, null))
            assertEquals(listOf("C"), store.listBaselineSlots().map { it.series })
        }

    @Test
    fun `condition directory is bounded`() =
        withStore { store, root ->
            val input = slotInput(store)
            val baseline = saveAnalysis(store, input, "baseline")
            val current = reference(input, saveAnalysis(store, input, "current"))
            val otherCurrent = reference(input, saveAnalysis(store, input, "other-current"))
            val at = Instant.parse("2026-09-06T10:00:00Z")
            val baselineReference = reference(input, baseline)
            store.replaceBaselineCondition(baselineConditionRecord(baselineReference, current, null, "CONFIRMED", at))
            val directory = root.resolve("baseline-conditions")
            (1 until MAX_BASELINE_CONDITION_FILES).forEach { Files.writeString(directory.resolve("%064x.json".format(it)), "{}") }
            assertEquals(MAX_BASELINE_CONDITION_FILES, fileNames(directory).size)

            val refused =
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceBaselineCondition(baselineConditionRecord(baselineReference, otherCurrent, null, "CONFIRMED", at))
                }
            assertEquals("BASELINE_CONDITIONS_LIMIT_REACHED", refused.message)
            val replacement = baselineConditionRecord(baselineReference, current, null, "NOT_CONFIRMED", at)
            assertEquals(replacement, store.replaceBaselineCondition(replacement))

            // The scan of a scoped delete is bounded as well and deletes nothing when it gives up.
            store.replaceBaselineSlot(slotSelection(input, "S", baseline), null)
            Files.writeString(directory.resolve("%064x.json".format(MAX_BASELINE_CONDITION_FILES + 10)), "{}")
            assertTrue(
                assertThrows(
                    IllegalStateException::class.java,
                ) { store.clearBaselineSlot("S", null) }.message!!.startsWith("CORRUPT_BASELINE"),
            )
            assertEquals(1, store.listBaselineSlots().size)
            assertEquals(replacement, store.readBaselineCondition(baselineReference, current, null))

            // The refusal comes before the legacy file of the key is removed, never after.
            store.replaceBaseline(slotSelection(input, "L", saveAnalysis(store, input, "legacy-only")))
            assertTrue(
                assertThrows(
                    IllegalStateException::class.java,
                ) { store.clearBaselineSlot("L", null) }.message!!.startsWith("CORRUPT_BASELINE"),
            )
            assertTrue(Files.exists(root.resolve("baseline.json")))
            assertEquals(2, store.listBaselineSlots().size)
        }

    @Test
    fun `legacy clear keeps slots and shared conditions`() =
        withStore { store, root ->
            val input = slotInput(store)
            val shared = saveAnalysis(store, input, "shared")
            val slotOnly = saveAnalysis(store, input, "slot-only")
            val legacyOnly = saveAnalysis(store, input, "legacy-only")
            val current = reference(input, saveAnalysis(store, input, "current"))
            val at = Instant.parse("2026-09-06T10:00:00Z")
            val sharedRecord = baselineConditionRecord(reference(input, shared), current, null, "CONFIRMED", at)
            val slotRecord = baselineConditionRecord(reference(input, slotOnly), current, null, "CONFIRMED", at)
            val legacyRecord = baselineConditionRecord(reference(input, legacyOnly), current, null, "CONFIRMED", at)
            store.replaceBaselineSlot(slotSelection(input, "slot", shared), null)
            store.replaceBaselineSlot(slotSelection(input, "other", slotOnly), null)
            listOf(sharedRecord, slotRecord, legacyRecord).forEach(store::replaceBaselineCondition)

            // The legacy file refers to the analysis a slot also uses: its record stays, the slots stay.
            store.replaceBaseline(slotSelection(input, "legacy", shared))
            store.clearBaseline()
            assertFalse(Files.exists(root.resolve("baseline.json")))
            assertEquals(2, store.listBaselineSlots().size)
            assertEquals(sharedRecord, store.readBaselineCondition(reference(input, shared), current, null))
            assertEquals(slotRecord, store.readBaselineCondition(reference(input, slotOnly), current, null))

            // A legacy file of its own analysis takes only its own record with it.
            store.replaceBaseline(slotSelection(input, "legacy", legacyOnly))
            store.clearBaseline()
            assertNull(store.readBaselineCondition(reference(input, legacyOnly), current, null))
            assertEquals(sharedRecord, store.readBaselineCondition(reference(input, shared), current, null))
            assertEquals(slotRecord, store.readBaselineCondition(reference(input, slotOnly), current, null))
            assertEquals(2, store.listBaselineSlots().size)
            assertStagingEmpty(root)
        }

    @Test
    fun `slot scan stays short`() =
        withStore { store, _ ->
            val input = slotInput(store)
            val analyses = (0..MAX_BASELINE_SLOTS).map { saveAnalysis(store, input, "scan$it") }
            repeat(MAX_BASELINE_SLOTS) { store.replaceBaselineSlot(slotSelection(input, "series-$it", analyses[it]), null) }
            store.replaceBaseline(slotSelection(input, "legacy", analyses[MAX_BASELINE_SLOTS]))
            val started = System.nanoTime()
            assertEquals(MAX_BASELINE_SLOTS + 1, store.listBaselineSlots().size)
            val scanMillis = (System.nanoTime() - started) / 1_000_000

            // The scan holds the store lock, so a concurrent read cannot run during it; what matters is how long it waits for it.
            var slowestRead = 0L
            val scanner =
                Thread {
                    repeat(20) {
                        store.listBaselineSlots()
                        Thread.sleep(5)
                    }
                }
            scanner.start()
            while (scanner.isAlive) {
                val readStarted = System.nanoTime()
                store.listRuns(null, 1)
                slowestRead = maxOf(slowestRead, (System.nanoTime() - readStarted) / 1_000_000)
            }
            scanner.join()
            println("baseline slot scan of ${MAX_BASELINE_SLOTS + 1} slots: $scanMillis ms, slowest listRuns during scans $slowestRead ms")
            assertTrue(scanMillis < 5_000 && slowestRead < 5_000)
        }

    @Test
    fun `run period round trips canonically and survives reopen`() {
        val root = tempDir.resolve("run-period-reopen")
        lateinit var runId: String
        lateinit var period: JsonObject

        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            val accepted = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "period.jtl")
            runId = accepted.runId
            assertEquals(null, store.readRunPeriod(runId))

            val recognized = recognizeRunPeriod(accepted.sourceType, accepted.path, accepted.sha256, 60_000)
            period = runPeriodJson(recognized)
            assertEquals(period, store.replaceRunPeriod(runId, period))

            val path = root.resolve("runs").resolve(runId).resolve("run-period.json")
            assertArrayEquals(canonicalJson(period), Files.readAllBytes(path))
            assertEquals(period, store.readRunPeriod(runId))
            assertEquals(period, store.readRunPeriod(runId))
            assertStagingEmpty(root)
        }

        DataDirectory.open(root).use { directory ->
            assertEquals(period, RunBundleStore(directory).readRunPeriod(runId))
        }
    }

    @Test
    fun `replaceRunPeriod overwrites valid v1 with v2`() =
        withStore { store, root ->
            val accepted = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "period.jtl")
            val old = periodJson(accepted.sha256)
            val updated = JsonObject(old + ("recognition_method" to JsonPrimitive("sample-timestamps.v2")))

            assertEquals(old, store.replaceRunPeriod(accepted.runId, old))
            assertEquals(updated, store.replaceRunPeriod(accepted.runId, updated))
            assertEquals(updated, store.readRunPeriod(accepted.runId))
            assertArrayEquals(
                canonicalJson(updated),
                Files.readAllBytes(root.resolve("runs").resolve(accepted.runId).resolve("run-period.json")),
            )
        }

    @Test
    fun `run period write refuses documents bound to other input bytes`() =
        withStore { store, root ->
            val accepted = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "period.jtl")

            assertThrows(IllegalArgumentException::class.java) {
                store.replaceRunPeriod(accepted.runId, periodJson("b".repeat(64)))
            }

            assertFalse(Files.exists(root.resolve("runs").resolve(accepted.runId).resolve("run-period.json")))
            assertStagingEmpty(root)
        }

    @Test
    fun `run period read rejects corrupt oversized and rebound private state`() =
        withStore { store, root ->
            val accepted = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "period.jtl")
            val period = periodJson(accepted.sha256)
            store.replaceRunPeriod(accepted.runId, period)
            val path = root.resolve("runs").resolve(accepted.runId).resolve("run-period.json")

            fun assertCorrupt() {
                val failure = assertThrows(IllegalStateException::class.java) { store.readRunPeriod(accepted.runId) }
                assertTrue((failure.message ?: "").startsWith("CORRUPT_RUN_PERIOD"))
            }

            Files.writeString(path, canonicalJson(period).decodeToString() + " ", StandardOpenOption.TRUNCATE_EXISTING)
            assertCorrupt()

            val withToken = JsonObject(period + ("token" to JsonPrimitive("not-allowed")))
            Files.writeString(path, canonicalJson(withToken).decodeToString(), StandardOpenOption.TRUNCATE_EXISTING)
            assertCorrupt()

            Files.write(path, ByteArray(4 * 1024 + 1), StandardOpenOption.TRUNCATE_EXISTING)
            assertCorrupt()

            Files.writeString(path, canonicalJson(periodJson("c".repeat(64))).decodeToString(), StandardOpenOption.TRUNCATE_EXISTING)
            assertCorrupt()
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

    @Test
    fun `pod view bytes are verified against the manifest and the identity when read`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val podView = """{"canonical":"pod-view-bytes"}""".encodeToByteArray()
            val saved = savePodViewAnalysis(store, input.runId, podView, identityHash = sha256Hex(podView))
            val analysisId = saved.fileName.toString()

            assertArrayEquals(podView, store.readPodViewBytes(input.runId, analysisId))
            assertTrue(store.readAnalysis(input.runId, analysisId)!!.artifacts.any { it.path == "pod-view.json" })

            // Same size, other bytes: ordinary reads compare sizes only, the pod-view read compares the manifest hash.
            Files.write(saved.resolve("pod-view.json"), """{"canonical":"pod-view-BYTES"}""".encodeToByteArray())
            assertEquals(saved, store.readAnalysis(input.runId, analysisId)?.path)
            val failure = assertThrows(IllegalStateException::class.java) { store.readPodViewBytes(input.runId, analysisId) }
            assertTrue((failure.message ?: "").startsWith("CORRUPT_RUN_BUNDLE"))
        }

    @Test
    fun `pod view read refuses a file that the identity does not bind and an identity binding without a file`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val podView = """{"canonical":"pod-view-bytes"}""".encodeToByteArray()

            val unbound = savePodViewAnalysis(store, input.runId, podView, identityHash = null)
            val another = savePodViewAnalysis(store, input.runId, podView, identityHash = "a".repeat(64))
            val missing = savePodViewAnalysis(store, input.runId, null, identityHash = sha256Hex(podView))

            listOf(unbound, another, missing).forEach { saved ->
                val failure =
                    assertThrows(IllegalStateException::class.java) { store.readPodViewBytes(input.runId, saved.fileName.toString()) }
                assertTrue((failure.message ?: "").startsWith("CORRUPT_RUN_BUNDLE"), saved.toString())
            }
        }

    @Test
    fun `an analysis without a pod view reads as absent`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "input.jtl")
            val saved = savePodViewAnalysis(store, input.runId, null, identityHash = null)

            assertNull(store.readPodViewBytes(input.runId, saved.fileName.toString()))
            assertNull(store.readPodViewBytes(input.runId, "b".repeat(64)))
        }

    private fun savePodViewAnalysis(
        store: RunBundleStore,
        runId: String,
        podView: ByteArray?,
        identityHash: String?,
    ): Path {
        val fields =
            listOfNotNull(
                identityHash?.let { """"pod_view_sha256":"$it","pod_view_version":"pod-view.v1"""" },
                """"run_id":"$runId"""",
                """"salt":"${System.nanoTime()}"""",
            ).joinToString(",", "{", "}")
        val identity = fields.encodeToByteArray()
        return store.writeAnalysisAtomically(runId, sha256Hex(identity)) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            Files.write(staging.resolve("analysis-result.json"), """{"run_id":"$runId"}""".encodeToByteArray())
            podView?.let { Files.write(staging.resolve("pod-view.json"), it) }
        }
    }

    @Test
    fun `verified read returns the documents and detects a same-size substitution of the result`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "verified.jtl")
            val analysisId = saveAnalysis(store, input, "x", verdict = "FAIL")
            val verified = store.readVerifiedAnalysis(input.runId, analysisId) ?: error("missing analysis")
            assertEquals("FAIL", (verified.result["policy_verdict"] as JsonPrimitive).content)
            assertEquals("2026-01-01T00:00:00Z", (verified.run?.get("started_at") as JsonPrimitive).content)

            val resultPath = root.resolve("runs/${input.runId}/analyses/$analysisId/analysis-result.json")
            val tampered = Files.readString(resultPath).replace("\"FAIL\"", "\"PASS\"")
            assertEquals(Files.size(resultPath), tampered.encodeToByteArray().size.toLong())
            Files.writeString(resultPath, tampered)

            // The ordinary read checks only path and size and so accepts the substitution; the verified read must not.
            assertEquals("PASS", (store.readAnalysisDocuments(input.runId, analysisId)!!.first["policy_verdict"] as JsonPrimitive).content)
            val failure = assertThrows(IllegalStateException::class.java) { store.readVerifiedAnalysis(input.runId, analysisId) }
            assertTrue(failure.message!!.startsWith("CORRUPT_RUN_BUNDLE"))
        }

    @Test
    fun `verified read enforces the result size bound at the exact byte`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "bound.jtl")
            val analysisId = saveAnalysis(store, input, "y")
            val size = Files.size(root.resolve("runs/${input.runId}/analyses/$analysisId/analysis-result.json")).toInt()

            assertTrue(store.readVerifiedAnalysis(input.runId, analysisId, maxResultBytes = size) != null)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    store.readVerifiedAnalysis(input.runId, analysisId, maxResultBytes = size - 1)
                }
            assertEquals("RESULT_TOO_LARGE", failure.message)
            assertEquals(null, store.readVerifiedAnalysis(input.runId, "f".repeat(64)))
        }

    @Test
    @Timeout(30)
    fun `verified read does not hold the store lock while it reads and hashes`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "lock.jtl")
            val analysisId = saveAnalysis(store, input, "z")
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val reader =
                    executor.submit {
                        store.readVerifiedAnalysis(input.runId, analysisId, outsideLock = {
                            inside.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        })
                    }
                assertTrue(inside.await(10, TimeUnit.SECONDS))
                // The callback runs after the manifest check and before the heavy read and hash: if the mutex were still held
                // here, the store operations below would not answer.
                assertEquals(1, store.listRuns(null, 10).runs.size)
                assertEquals(1, store.listAnalyses(input.runId, null, 10).analyses.size)
                release.countDown()
                reader.get(10, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }

    private fun releaseAnalyses(
        analysisIds: List<String>,
        arms: List<String?> = analysisIds.map { null },
    ): JsonArray =
        JsonArray(
            analysisIds.mapIndexed { index, id ->
                buildJsonObject {
                    put("analysis_id", id)
                    put("arm", arms[index]?.let(::JsonPrimitive) ?: JsonNull)
                    put("coverage_reasons", JsonArray(emptyList()))
                    put("coverage_status", "COMPLETE")
                    put("policy_sha256", "a".repeat(64))
                    put("policy_verdict", "PASS")
                    put("run_validity", "VALID")
                }
            },
        )

    private fun releaseDraft(
        runId: String,
        analysisIds: List<String>,
        series: String = "checkout",
        label: String = "1.0.0",
        startedAt: String = "2026-01-01T00:00:00Z",
        arms: List<String?> = analysisIds.map { null },
    ): JsonObject =
        buildJsonObject {
            put("schema_version", "local-release.v1")
            put("series", series)
            put("label", label)
            put("run_id", runId)
            put("started_at", startedAt)
            put("analyses", releaseAnalyses(analysisIds, arms))
            put("profile", JsonNull)
            put("notes", JsonNull)
        }

    private fun field(
        record: JsonObject,
        name: String = "release_id",
    ) = (record[name] as JsonPrimitive).content

    /** Writes valid canonical records straight into the registry directory, bypassing the store. */
    private fun seedReleases(
        root: Path,
        count: Int,
        firstStamp: Long = 1_767_225_600_000L,
    ) {
        val directory = Files.createDirectories(root.resolve("releases"))
        repeat(count) { index ->
            val millis = firstStamp + index
            val id = releaseId(millis, "%08x".format(index))
            val analysis = "%064x".format(index + 1)
            val record =
                JsonObject(
                    releaseDraft(SEED_RUN_ID, listOf(analysis), startedAt = Instant.ofEpochMilli(millis).toString()) +
                        mapOf(
                            "release_id" to JsonPrimitive(id),
                            "created_at" to JsonPrimitive("2026-01-02T00:00:00Z"),
                            "updated_at" to JsonPrimitive("2026-01-02T00:00:00Z"),
                        ),
                )
            Files.write(directory.resolve("$id.json"), canonicalJson(validateRelease(record)))
        }
    }

    private fun freshAnalysis(n: Int) = "%064x".format(0xf000_0000L + n)

    @Test
    fun `release create writes one canonical record outside the bundle and survives reopen`() {
        val root = tempDir.resolve("release-create")
        val created =
            DataDirectory.open(root).use { directory ->
                val store = RunBundleStore(directory)
                val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "r.jtl")
                val analysisId = saveAnalysis(store, input, "a")
                val record =
                    store.createRelease(
                        releaseDraft(input.runId, listOf(analysisId)),
                        Instant.parse("2026-01-02T03:04:05.006789Z"),
                    ) { "0123abcd" }

                assertEquals("001767225600000-0123abcd", field(record))
                assertEquals("2026-01-02T03:04:05.006Z", field(record, "created_at"))
                assertEquals("2026-01-02T03:04:05.006Z", field(record, "updated_at"))
                val path = root.resolve("releases/001767225600000-0123abcd.json")
                assertArrayEquals(canonicalJson(record), Files.readAllBytes(path))
                assertStagingEmpty(root)
                // the analysis bundle is untouched: identity, result, run metadata and the manifest
                Files.list(root.resolve("runs/${input.runId}/analyses/$analysisId")).use { assertEquals(4L, it.count()) }
                record
            }
        DataDirectory.open(root).use { directory ->
            assertEquals(created, RunBundleStore(directory).readRelease("001767225600000-0123abcd"))
        }
    }

    @Test
    fun `one analysis belongs to at most one release and a replacement excludes its own record`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "u.jtl")
            val first = saveAnalysis(store, input, "1")
            val second = saveAnalysis(store, input, "2")
            val a = store.createRelease(releaseDraft(input.runId, listOf(first)), RELEASE_NOW) { "00000001" }
            store.createRelease(releaseDraft(input.runId, listOf(second), label = "other"), RELEASE_NOW) { "00000002" }
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    store.createRelease(releaseDraft(input.runId, listOf(first), label = "dup"), RELEASE_NOW) { "00000003" }
                }
            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", failure.message)
            assertEquals(2, store.listReleases(null, null, 100).releases.size)

            // the record's own analysis is excluded from the uniqueness check: renaming passes
            val id = field(a)
            store.replaceRelease(id) { JsonObject(it + ("label" to JsonPrimitive("renamed"))) }
            assertEquals("renamed", field(store.readRelease(id)!!, "label"))
            // taking the analysis of another release is refused and leaves the file byte-identical
            val path = root.resolve("releases/$id.json")
            val before = Files.readAllBytes(path)
            val refused =
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceRelease(id) { current -> JsonObject(current + ("analyses" to releaseAnalyses(listOf(second)))) }
                }
            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", refused.message)
            assertArrayEquals(before, Files.readAllBytes(path))
            assertStagingEmpty(root)
        }

    @Test
    @Timeout(60)
    fun `concurrent creates of the same analysis produce exactly one record`() =
        withStore { store, root ->
            repeat(20) { round ->
                val analysis = freshAnalysis(round)
                val barrier = CyclicBarrier(2)
                val executor = Executors.newFixedThreadPool(2)
                try {
                    val futures =
                        (1..2).map { worker ->
                            executor.submit<String> {
                                barrier.await(10, TimeUnit.SECONDS)
                                try {
                                    store.createRelease(releaseDraft(SEED_RUN_ID, listOf(analysis), label = "w$worker"), RELEASE_NOW) {
                                        "%08x".format(round * 2 + worker)
                                    }
                                    "created"
                                } catch (failure: IllegalArgumentException) {
                                    failure.message!!
                                }
                            }
                        }
                    val outcomes = futures.map { it.get(20, TimeUnit.SECONDS) }
                    assertEquals(listOf("RELEASE_ANALYSIS_ALREADY_REGISTERED", "created"), outcomes.sorted())
                } finally {
                    executor.shutdownNow()
                }
            }
            assertEquals(20, store.listReleases(null, null, 100).releases.size)
            assertStagingEmpty(root)
        }

    @Test
    fun `registry limit counts every directory entry and never truncates`() =
        withStore { store, root ->
            seedReleases(root, MAX_RELEASES - 1)
            val last = store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1))), RELEASE_NOW) { "ffffffff" }
            assertEquals(
                MAX_RELEASES,
                store
                    .listReleases(null, null, 100)
                    .seriesSummary
                    .single()
                    .second,
            )
            val full =
                assertThrows(IllegalArgumentException::class.java) {
                    store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(2))), RELEASE_NOW) { "fffffffe" }
                }
            assertEquals("RELEASE_LIMIT_REACHED", full.message)

            // a foreign file is an element too: 1 001 entries make the registry corrupt and nothing is cut off
            Files.writeString(root.resolve("releases/x.txt"), "x")
            val listing = assertThrows(IllegalStateException::class.java) { store.listReleases(null, null, 100) }
            assertTrue(listing.message!!.startsWith("CORRUPT_RELEASE_REGISTRY"))
            val create =
                assertThrows(IllegalStateException::class.java) {
                    store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(3))), RELEASE_NOW) { "fffffffd" }
                }
            assertTrue(create.message!!.startsWith("CORRUPT_RELEASE_REGISTRY"))
            Files.delete(root.resolve("releases/x.txt"))
            val page = store.listReleases(null, null, 100)
            assertEquals(MAX_RELEASES, page.seriesSummary.single().second)
            assertEquals(0, page.corruptCount)
            assertEquals(last, store.readRelease(field(last)))
            assertStagingEmpty(root)
        }

    @Test
    fun `list is newest first with an exclusive cursor and complete series summary`() =
        withStore { store, _ ->
            val ids =
                (0 until 5).map { n ->
                    val series = if (n % 2 == 0) "alpha" else "beta"
                    val draft =
                        releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(n)), series = series, startedAt = "2026-01-0${n + 1}T00:00:00Z")
                    field(store.createRelease(draft, RELEASE_NOW) { "0000000$n" })
                }
            val newestFirst = ids.sortedDescending()
            val first = store.listReleases(null, null, 2)
            assertEquals(newestFirst.take(2), first.releases.map { field(it) })
            assertEquals(newestFirst[1], first.nextAfter)
            val second = store.listReleases(null, first.nextAfter, 2)
            assertEquals(newestFirst.slice(2..3), second.releases.map { field(it) })
            val third = store.listReleases(null, second.nextAfter, 2)
            assertEquals(newestFirst.slice(4..4), third.releases.map { field(it) })
            assertNull(third.nextAfter)
            assertEquals(listOf("alpha" to 3, "beta" to 2), first.seriesSummary)

            val beta = store.listReleases("beta", null, 1)
            assertEquals(listOf("alpha" to 3, "beta" to 2), beta.seriesSummary)
            assertEquals(1, beta.releases.size)
            assertTrue(beta.releases.all { field(it, "series") == "beta" })
            assertNotNull(beta.nextAfter)
            // a cursor that names no record still works by comparison
            assertEquals(newestFirst, store.listReleases(null, "999999999999999-ffffffff", 100).releases.map { field(it) })
            assertTrue(store.listReleases(null, "000000000000000-00000000", 100).releases.isEmpty())
            assertThrows(IllegalArgumentException::class.java) { store.listReleases(null, "garbage", 10) }
            assertThrows(IllegalArgumentException::class.java) { store.listReleases(null, null, 0) }
            assertThrows(IllegalArgumentException::class.java) { store.listReleases(null, null, 101) }
        }

    @Test
    fun `list reports unreadable entries without hiding the valid ones`() =
        withStore { store, root ->
            seedReleases(root, 2)
            val directory = root.resolve("releases")
            val good = Files.readAllBytes(Files.list(directory).use { it.toList() }.first())
            Files.writeString(directory.resolve("000000000000001-00000001.json"), "not json")
            Files.write(
                directory.resolve("000000000000002-00000002.json"),
                good.decodeToString().replace("local-release.v1", "local-release.v2").encodeToByteArray(),
            )
            Files.writeString(directory.resolve("notes.txt"), "stray")
            Files.write(directory.resolve("000000000000003-00000003.json"), ByteArray(MAX_RELEASE_BYTES + 1) { ' '.code.toByte() })
            // a valid record stored under another record's name is damaged, not accepted
            Files.write(directory.resolve("000000000000004-00000004.json"), good)
            var expectedCorrupt = 5
            try {
                Files.createSymbolicLink(directory.resolve("000000000000005-00000005.json"), directory.resolve("notes.txt"))
                expectedCorrupt++
            } catch (_: IOException) {
                // symbolic links need a privilege on Windows
            } catch (_: UnsupportedOperationException) {
                // and are unavailable on some file systems
            }

            val page = store.listReleases(null, null, 100)
            assertEquals(2, page.releases.size)
            assertEquals(expectedCorrupt, page.corruptCount)
            val reasons = page.corruptNames.associate { it.name to it.reason }
            assertEquals("CORRUPT", reasons["000000000000001-00000001.json"])
            assertEquals("UNSUPPORTED_VERSION", reasons["000000000000002-00000002.json"])
            assertEquals("UNSAFE_ENTRY", reasons["notes.txt"])
            assertEquals("TOO_LARGE", reasons["000000000000003-00000003.json"])
            assertEquals("CORRUPT", reasons["000000000000004-00000004.json"])

            repeat(25) { Files.writeString(directory.resolve("000000000100000-%08x.json".format(it)), "bad") }
            val many = store.listReleases(null, null, 100)
            assertEquals(2, many.releases.size)
            assertEquals(expectedCorrupt + 25, many.corruptCount)
            assertEquals(20, many.corruptNames.size)
        }

    @Test
    fun `an unreadable entry is one damaged element`() =
        withStore { store, root ->
            seedReleases(root, 1)
            // a directory under a record name cannot be read as a file
            Files.createDirectory(root.resolve("releases/000000000000009-00000009.json"))
            val page = store.listReleases(null, null, 100)
            assertEquals(1, page.releases.size)
            assertEquals(1, page.corruptCount)
            assertEquals("UNSAFE_ENTRY", page.corruptNames.single().reason)
            assertThrows(IllegalStateException::class.java) { store.deleteRelease("000000000000009-00000009") }
        }

    @Test
    fun `read of a corrupt record fails closed and delete removes it without parsing`() =
        withStore { store, root ->
            seedReleases(root, 1)
            val damaged = root.resolve("releases/000000000000001-00000001.json")
            Files.writeString(damaged, "not json")
            val failure = assertThrows(IllegalStateException::class.java) { store.readRelease("000000000000001-00000001") }
            assertTrue(failure.message!!.startsWith("CORRUPT_RELEASE"))
            assertNull(store.readRelease("000000000000008-00000008"))

            listOf("../x", "", "0".repeat(15), "000000000000001-0000000G", "000000000000001-00000001.json").forEach {
                val refused = assertThrows(IllegalArgumentException::class.java) { store.deleteRelease(it) }
                assertEquals("INVALID_RELEASE_ID", refused.message)
            }
            assertTrue(Files.exists(damaged))
            assertTrue(store.deleteRelease("000000000000001-00000001"))
            assertFalse(Files.exists(damaged))
            assertFalse(store.deleteRelease("000000000000001-00000001"))
            assertFalse(store.deleteRelease("000000000000007-00000007"))
            assertEquals(1, store.listReleases(null, null, 100).releases.size)
        }

    @Test
    fun `replace keeps immutable fields and refuses an oversize record`() =
        withStore { store, root ->
            val created = store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1))), RELEASE_NOW) { "00000001" }
            val id = field(created)
            val path = root.resolve("releases/$id.json")
            val before = Files.readAllBytes(path)
            listOf(
                "series" to JsonPrimitive("other"),
                "run_id" to JsonPrimitive("gatling_text-${"1".repeat(64)}"),
                "started_at" to JsonPrimitive("2026-01-01T00:00:01Z"),
                "created_at" to JsonPrimitive("2026-01-01T00:00:00Z"),
                "release_id" to JsonPrimitive("001767225600000-00000009"),
                "schema_version" to JsonPrimitive("local-release.v2"),
            ).forEach { (name, value) ->
                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        store.replaceRelease(id) { current -> JsonObject(current + (name to value)) }
                    }
                assertEquals("INVALID_RELEASE", failure.message)
                assertArrayEquals(before, Files.readAllBytes(path))
            }

            // quotes are escaped in the canonical form, so a record that passes every field limit can still exceed 8 KiB
            val quotes = "\"".repeat(MAX_RELEASE_TEXT_BYTES - 1)
            val arms = (0 until MAX_RELEASE_ANALYSES).map { "$quotes$it" }
            val big =
                JsonObject(
                    created +
                        mapOf(
                            "analyses" to releaseAnalyses((0 until MAX_RELEASE_ANALYSES).map { freshAnalysis(100 + it) }, arms),
                            "profile" to JsonObject(RELEASE_PROFILE_FIELDS.associateWith { JsonPrimitive(quotes + "p") }),
                            "notes" to JsonPrimitive("\"".repeat(MAX_RELEASE_NOTES_BYTES)),
                            "label" to JsonPrimitive(quotes + "l"),
                        ),
                )
            assertTrue(canonicalJson(validateRelease(big)).size > MAX_RELEASE_BYTES)
            val oversize = assertThrows(IllegalArgumentException::class.java) { store.replaceRelease(id) { big } }
            assertEquals("RELEASE_TOO_LARGE", oversize.message)
            assertArrayEquals(before, Files.readAllBytes(path))
            val draft = JsonObject(big - setOf("release_id", "created_at", "updated_at"))
            val createBig = assertThrows(IllegalArgumentException::class.java) { store.createRelease(draft, RELEASE_NOW) { "00000002" } }
            assertEquals("RELEASE_TOO_LARGE", createBig.message)
            assertEquals(1, store.listReleases(null, null, 100).releases.size)

            assertThrows(NoSuchElementException::class.java) { store.replaceRelease("000000000000001-00000001") { it } }
            assertThrows(IllegalArgumentException::class.java) { store.replaceRelease("garbage") { it } }
            // an update that throws leaves the file intact
            assertThrows(IllegalStateException::class.java) { store.replaceRelease(id) { error("boom") } }
            assertArrayEquals(before, Files.readAllBytes(path))
            assertStagingEmpty(root)
        }

    @Test
    fun `find by analysis resolves unique ids and reports duplicates as ambiguous`() =
        withStore { store, root ->
            val one = store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1))), RELEASE_NOW) { "00000001" }
            store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(2)), label = "two"), RELEASE_NOW) { "00000002" }
            val found = store.findReleasesByAnalysis(setOf(freshAnalysis(1), freshAnalysis(2), freshAnalysis(3)))
            assertEquals(setOf(freshAnalysis(1), freshAnalysis(2)), found.byAnalysis.keys)
            assertEquals(one, found.byAnalysis.getValue(freshAnalysis(1)))
            assertTrue(found.ambiguous.isEmpty())

            // a hand-copied file puts one analysis into two valid records
            val copy = canonicalJson(JsonObject(one + ("release_id" to JsonPrimitive("001767225600000-00000009"))))
            Files.write(root.resolve("releases/001767225600000-00000009.json"), copy)
            val again = store.findReleasesByAnalysis(setOf(freshAnalysis(1), freshAnalysis(2)))
            assertEquals(setOf(freshAnalysis(1)), again.ambiguous)
            assertEquals(setOf(freshAnalysis(2)), again.byAnalysis.keys)
            assertEquals(3, store.listReleases(null, null, 100).releases.size)
            val refused =
                assertThrows(IllegalArgumentException::class.java) {
                    store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1)), label = "again"), RELEASE_NOW) { "00000003" }
                }
            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", refused.message)
        }

    @Test
    fun `release id retries the suffix and gives up after eight collisions`() =
        withStore { store, root ->
            store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1))), RELEASE_NOW) { "00000001" }
            var calls = 0
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(2)), label = "x"), RELEASE_NOW) {
                        calls++
                        "00000001"
                    }
                }
            assertEquals("RELEASE_ID_COLLISION", failure.message)
            assertEquals(8, calls)
            assertStagingEmpty(root)

            val suffixes = ArrayDeque(listOf("00000001", "00000002"))
            val created =
                store.createRelease(
                    releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(2)), label = "x"),
                    RELEASE_NOW,
                ) { suffixes.removeFirst() }
            assertEquals("001767225600000-00000002", field(created))
        }

    @Test
    fun `registry scan stays fast for a full directory`() =
        withStore { store, root ->
            seedReleases(root, MAX_RELEASES)
            val listStarted = System.nanoTime()
            val page = store.listReleases(null, null, 100)
            val listMillis = (System.nanoTime() - listStarted) / 1_000_000
            val findStarted = System.nanoTime()
            val found = store.findReleasesByAnalysis(setOf("%064x".format(1), "%064x".format(MAX_RELEASES)))
            val findMillis = (System.nanoTime() - findStarted) / 1_000_000
            println("release registry scan of $MAX_RELEASES records: list $listMillis ms, find $findMillis ms")
            assertEquals(100, page.releases.size)
            assertEquals(2, found.byAnalysis.size)
            assertTrue(listMillis < 5_000 && findMillis < 5_000)
        }

    @Test
    fun `model based registry operations agree with an in-memory model`() =
        withStore { store, _ ->
            val random = Random(42)
            val analyses = (0 until 8).map { freshAnalysis(it) }
            val model = mutableMapOf<String, JsonObject>()
            var counter = 0

            fun registeredElsewhere(
                analysis: String,
                except: String?,
            ) = model.any { (key, record) ->
                key != except &&
                    (record["analyses"] as JsonArray).any { ((it as JsonObject)["analysis_id"] as JsonPrimitive).content == analysis }
            }

            fun anyKey(unknown: String) =
                if (model.isEmpty() || random.nextInt(6) == 0) unknown else model.keys.elementAt(random.nextInt(model.size))
            repeat(200) {
                when (random.nextInt(4)) {
                    0 -> {
                        val analysis = analyses[random.nextInt(analyses.size)]
                        val startedAt = Instant.ofEpochSecond(1_767_225_600L + random.nextInt(1000)).toString()
                        val draft = releaseDraft(SEED_RUN_ID, listOf(analysis), series = "s${random.nextInt(3)}", startedAt = startedAt)
                        if (registeredElsewhere(analysis, null)) {
                            val failure =
                                assertThrows(
                                    IllegalArgumentException::class.java,
                                ) { store.createRelease(draft, RELEASE_NOW) { "%08x".format(counter++) } }
                            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", failure.message)
                        } else {
                            val record = store.createRelease(draft, RELEASE_NOW) { "%08x".format(counter++) }
                            model[field(record)] = record
                        }
                    }
                    1 -> {
                        val key = anyKey("000000000000001-00000001")
                        val analysis = analyses[random.nextInt(analyses.size)]
                        val label = JsonPrimitive("l$counter")
                        val change = { current: JsonObject ->
                            JsonObject(
                                current + ("analyses" to releaseAnalyses(listOf(analysis))) + ("label" to label),
                            )
                        }
                        if (!model.containsKey(key)) {
                            assertThrows(NoSuchElementException::class.java) { store.replaceRelease(key, change) }
                        } else if (registeredElsewhere(analysis, key)) {
                            val failure = assertThrows(IllegalArgumentException::class.java) { store.replaceRelease(key, change) }
                            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", failure.message)
                        } else {
                            model[key] = store.replaceRelease(key, change)
                        }
                    }
                    2 -> {
                        val key = anyKey("000000000000002-00000002")
                        assertEquals(model.remove(key) != null, store.deleteRelease(key))
                    }
                    else -> Unit
                }
                val expected = model.values.sortedByDescending { field(it) }
                val collected = mutableListOf<JsonObject>()
                var after: String? = null
                do {
                    val page = store.listReleases(null, after, 3)
                    collected += page.releases
                    after = page.nextAfter
                } while (after != null)
                assertEquals(expected, collected)
                val summary =
                    model.values
                        .groupingBy { field(it, "series") }
                        .eachCount()
                        .toList()
                        .sortedBy { it.first }
                assertEquals(summary, store.listReleases(null, null, 1).seriesSummary)
                assertTrue(collected.size <= MAX_RELEASES)
                val ids =
                    collected.flatMap {
                        (it["analyses"] as JsonArray).map { item ->
                            ((item as JsonObject)["analysis_id"] as JsonPrimitive).content
                        }
                    }
                assertEquals(ids.size, ids.toSet().size)
            }
        }

    @Test
    @Timeout(30)
    fun `concurrent replacement is detected`() =
        withStore { store, root ->
            val created = store.createRelease(releaseDraft(SEED_RUN_ID, listOf(freshAnalysis(1))), RELEASE_NOW) { "00000001" }
            val id = field(created)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceRelease(id) { current ->
                        // the nested replacement runs outside the store lock and wins the race
                        store.replaceRelease(id) { inner -> JsonObject(inner + ("label" to JsonPrimitive("second"))) }
                        JsonObject(current + ("label" to JsonPrimitive("first")))
                    }
                }
            assertEquals("RELEASE_CHANGED", failure.message)
            assertEquals("second", field(store.readRelease(id)!!, "label"))
            assertStagingEmpty(root)
        }

    @Test
    fun `analysis state is existence in the list and a full check in the single read`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "state.jtl")
            val analysisId = saveAnalysis(store, input, "s")
            assertTrue(store.analysisExists(input.runId, analysisId))
            assertFalse(store.analysisExists(input.runId, "f".repeat(64)))
            assertFalse(store.analysisExists(SEED_RUN_ID, analysisId))
            assertEquals("OK", store.analysisState(input.runId, analysisId))
            assertEquals("MISSING", store.analysisState(input.runId, "f".repeat(64)))
            assertEquals("MISSING", store.analysisState(SEED_RUN_ID, analysisId))

            val result = root.resolve("runs/${input.runId}/analyses/$analysisId/analysis-result.json")
            Files.write(result, Files.readAllBytes(result) + byteArrayOf(' '.code.toByte()))
            assertEquals("CORRUPT", store.analysisState(input.runId, analysisId))
            // existence is deliberately cheap: it does not read the artifacts
            assertTrue(store.analysisExists(input.runId, analysisId))

            val analyses = root.resolve("runs/${input.runId}/analyses")
            val moved = root.resolve("moved-analyses")
            Files.move(analyses, moved)
            try {
                Files.createSymbolicLink(analyses, moved)
                assertFalse(store.analysisExists(input.runId, analysisId))
            } catch (_: IOException) {
                // symbolic links need a privilege on Windows
            } catch (_: UnsupportedOperationException) {
                // and are unavailable on some file systems
            }
        }

    private fun saveAnalysis(
        store: RunBundleStore,
        input: AcceptedInput,
        tag: String,
        verdict: String = "PASS",
        startedAt: String? = "2026-01-01T00:00:00Z",
        arm: String? = null,
        extraIdentity: Map<String, JsonElement> = emptyMap(),
    ): String {
        val identity =
            canonicalJson(
                buildJsonObject {
                    put("run_id", input.runId)
                    put("policy_sha256", "a".repeat(64))
                    put("tag", tag)
                    arm?.let { put("resource_arm", it) }
                    extraIdentity.forEach { (name, value) -> put(name, value) }
                },
            )
        val result =
            canonicalJson(
                buildJsonObject {
                    put("run_id", input.runId)
                    put("run_validity", "VALID")
                    put("policy_verdict", verdict)
                },
            )
        val analysisId = sha256Hex(identity)
        store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            Files.write(staging.resolve("analysis-result.json"), result)
            if (startedAt != null) {
                Files.write(
                    staging.resolve("run.json"),
                    canonicalJson(
                        buildJsonObject {
                            put("run_id", input.runId)
                            put("started_at", startedAt)
                        },
                    ),
                )
            }
        }
        return analysisId
    }

    private fun withStore(block: (RunBundleStore, Path) -> Unit) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory -> block(RunBundleStore(directory), directory.root) }
    }

    private fun withStore(
        clock: Clock,
        block: (RunBundleStore, Path) -> Unit,
    ) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory -> block(RunBundleStore(directory, clock), directory.root) }
    }

    private class SettableClock(
        var instant: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
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

    private fun reference(
        input: AcceptedInput,
        analysisId: String,
    ) = buildJsonObject {
        put("run_id", input.runId)
        put("analysis_id", analysisId)
    }

    private fun slotInput(store: RunBundleStore): AcceptedInput = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "slots.jtl")

    private fun slotSelection(
        input: AcceptedInput,
        series: String,
        analysisId: String,
    ): JsonObject = manualBaselineSelection(series, reference(input, analysisId))

    // The key is spelled out here on purpose: the test must not share the production function it checks.
    private fun slotKey(
        series: String,
        arm: String?,
    ): String =
        sha256Hex(
            canonicalJson(
                buildJsonObject {
                    put("arm", arm?.let(::JsonPrimitive) ?: JsonNull)
                    put("series", series)
                },
            ),
        )

    private fun fileNames(directory: Path): Set<String> =
        Files.list(directory).use { entries ->
            entries
                .map {
                    it.fileName.toString()
                }.toList()
                .toSet()
        }

    private fun periodJson(sha256: String): JsonObject =
        buildJsonObject {
            put("schema_version", "run-period.v1")
            put("load_input_sha256", sha256)
            put("recognition_method", "sample-timestamps.v1")
            put("first_sample_epoch_millis", 1_767_225_600_000L)
            put("last_sample_epoch_millis", 1_767_225_660_000L)
            put("longest_idle_gap_millis", JsonNull)
            put("idle_gap_count", 0)
            put("status", "RECOGNIZED")
        }

    private companion object {
        const val CSV_FIXTURE = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
        const val XML_FIXTURE = "fixtures/slice1/jmeter/xml-5.6.3/input.xml"
        const val SEED_RUN_ID = "jmeter_jtl_csv-0000000000000000000000000000000000000000000000000000000000000000"
        val RELEASE_NOW: Instant = Instant.parse("2026-01-02T00:00:00Z")
    }
}

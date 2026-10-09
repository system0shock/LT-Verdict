package io.ltverdict.storage

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * W2.2 PR 3: where the store holds the one data-directory lock and where it does not. Written on the unsplit store; the split
 * must keep every one of these, because all areas of the store share the lock of the same [DataDirectory].
 */
@Timeout(60)
class StoreLockingTest {
    @TempDir
    lateinit var tempDir: Path

    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    private fun analysisBytes(
        runId: String,
        tag: String,
    ): Pair<String, ByteArray> {
        val identity =
            canonicalJson(
                buildJsonObject {
                    put("run_id", runId)
                    put("policy_sha256", "a".repeat(64))
                    put("tag", tag)
                },
            )
        return sha256Hex(identity) to identity
    }

    @Test
    fun `beforePublish runs under the lock of the data directory and every area waits for it`() {
        DataDirectory.open(tempDir.resolve("publish")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV)), "lock.jtl")
            val (analysisId, identity) = analysisBytes(input.runId, "x")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer =
                CompletableFuture.runAsync({
                    store.writeAnalysisAtomically(input.runId, analysisId, {
                        entered.countDown()
                        release.await()
                    }) { staging ->
                        Files.write(staging.resolve("identity.json"), identity)
                    }
                }, pool)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // one read of every area of the store: none may finish while the publication holds the lock
            val reads =
                listOf<() -> Any?>(
                    { store.requireInput(input.runId) },
                    { store.readBaseline() },
                    { store.listReleases(null, null, 10) },
                    { store.listBaselineSlots() },
                ).map { read -> CompletableFuture.supplyAsync({ read() }, pool) }
            Thread.sleep(300)
            assertTrue(reads.none { it.isDone }, "a read finished under the publication lock")
            release.countDown()
            reads.forEach { it.get(10, TimeUnit.SECONDS) }
            writer.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the update callback of a release replacement runs outside the lock`() {
        DataDirectory.open(tempDir.resolve("update")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV)), "lock.jtl")
            val draft =
                buildJsonObject {
                    put("schema_version", "local-release.v1")
                    put("series", "s")
                    put("label", "l")
                    put("run_id", input.runId)
                    put("started_at", "2026-01-01T00:00:00Z")
                    put(
                        "analyses",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("analysis_id", "a".repeat(64))
                                    put("arm", JsonNull)
                                    put("coverage_reasons", JsonArray(emptyList()))
                                    put("coverage_status", "COMPLETE")
                                    put("policy_sha256", "a".repeat(64))
                                    put("policy_verdict", "PASS")
                                    put("run_validity", "VALID")
                                },
                            ),
                        ),
                    )
                    put("profile", JsonNull)
                    put("notes", JsonNull)
                }
            val id =
                (
                    store.createRelease(draft, Instant.parse("2026-01-02T00:00:00Z")) {
                        "0123abcd"
                    }["release_id"] as kotlinx.serialization.json.JsonPrimitive
                ).content
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val replacement =
                CompletableFuture.supplyAsync({
                    store.replaceRelease(id) {
                        entered.countDown()
                        release.await()
                        it
                    }
                }, pool)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // the other areas answer while the callback waits
            assertEquals(input.runId, store.requireInput(input.runId).runId)
            assertEquals(id, (store.readRelease(id)!!["release_id"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(null, store.readBaseline())
            assertFalse(replacement.isDone)
            release.countDown()
            replacement.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `an analysis writer that outlives the data directory fails at publication and leaves no residue`() {
        val root = tempDir.resolve("close")
        val directory = DataDirectory.open(root)
        val store = RunBundleStore(directory)
        val input = store.acceptInput(Files.newInputStream(Path.of(CSV)), "lock.jtl")
        val (analysisId, identity) = analysisBytes(input.runId, "closing")
        val staged = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val writer =
            CompletableFuture.supplyAsync({
                try {
                    store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                        Files.write(staging.resolve("identity.json"), identity)
                        staged.countDown()
                        closed.await()
                    }
                    "published"
                } catch (failure: IllegalStateException) {
                    failure.message
                }
            }, pool)
        assertTrue(staged.await(10, TimeUnit.SECONDS))
        directory.close()
        closed.countDown()
        assertEquals("DATA_DIR_CLOSED", writer.get(10, TimeUnit.SECONDS))
        assertFalse(
            Files.exists(
                root
                    .resolve("runs")
                    .resolve(input.runId)
                    .resolve("analyses")
                    .resolve(analysisId),
            ),
        )
        assertThrows(IllegalStateException::class.java) { store.requireInput(input.runId) }
        DataDirectory.open(root).use { reopened ->
            Files.newDirectoryStream(reopened.staging).use { assertEquals(emptyList<Path>(), it.toList()) }
        }
    }

    @Test
    fun `a read waits for the lock of another area in the same directory and not for a different directory`() {
        DataDirectory.open(tempDir.resolve("one")).use { one ->
            DataDirectory.open(tempDir.resolve("two")).use { two ->
                val first = RunBundleStore(one)
                val second = RunBundleStore(two)
                val input = first.acceptInput(Files.newInputStream(Path.of(CSV)), "lock.jtl")
                val (analysisId, identity) = analysisBytes(input.runId, "y")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val writer =
                    CompletableFuture.runAsync({
                        first.writeAnalysisAtomically(input.runId, analysisId, {
                            entered.countDown()
                            release.await()
                        }) { staging -> Files.write(staging.resolve("identity.json"), identity) }
                    }, pool)
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                // another directory has its own lock
                assertEquals(null, second.readBaseline())
                val blocked = CompletableFuture.supplyAsync({ first.readBaseline() }, pool)
                assertThrows(TimeoutException::class.java) { blocked.get(300, TimeUnit.MILLISECONDS) }
                release.countDown()
                blocked.get(10, TimeUnit.SECONDS)
                writer.get(10, TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val CSV = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
    }
}

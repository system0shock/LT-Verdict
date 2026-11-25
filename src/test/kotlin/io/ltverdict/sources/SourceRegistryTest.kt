package io.ltverdict.sources

import io.ltverdict.core.sha256Hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class SourceRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `null path initializes empty state and reload returns NotConfigured`() {
        val registry = SourceRegistry(null)
        val state = registry.current()
        assertTrue(state.profiles.isEmpty())
        assertTrue(state.postgres.isEmpty())
        assertNull(state.http)
        assertNull(state.promql)
        assertNull(state.revision)
        assertEquals(ReloadResult.NotConfigured, registry.reload())
    }

    @Test
    fun `atomic swap exposes consistent generation across concurrent reader and writer`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val registry = SourceRegistry(file, clock = clock)
        val initial = registry.current()
        assertEquals(listOf("p1"), initial.profiles.map { it.id })
        assertEquals(sha256Hex(Files.readAllBytes(file)), initial.revision)

        val stop = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(2)
        val readerFuture =
            pool.submit {
                try {
                    while (!stop.get()) {
                        val snapshot = registry.current()
                        val ids = snapshot.profiles.map { it.id }
                        assertTrue(ids == listOf("p1") || ids == listOf("p2"), "unexpected profiles: $ids")
                        assertEquals(snapshot.profiles, snapshot.promql?.profiles)
                        assertNotNull(snapshot.http)
                        assertNotNull(snapshot.revision)
                    }
                } catch (failure: Throwable) {
                    error.compareAndSet(null, failure)
                }
            }
        val writerFuture =
            pool.submit {
                try {
                    for (step in 1..20) {
                        val id = if (step % 2 == 1) "p2" else "p1"
                        val tmp = tempDir.resolve("tmp-$step.json")
                        Files.writeString(tmp, connectionsJson(id, "http://127.0.0.1:${9001 + step}"))
                        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        clock.advanceMillis(5_000)
                        val result = registry.reload()
                        assertTrue(result is ReloadResult.Success, "step $step failed: $result")
                    }
                } catch (failure: Throwable) {
                    error.compareAndSet(null, failure)
                } finally {
                    stop.set(true)
                }
            }
        writerFuture.get(15, TimeUnit.SECONDS)
        readerFuture.get(15, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertNull(error.get())
    }

    @Test
    fun `invalid config preserves old state and does not trigger cooldown`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val registry = SourceRegistry(file, clock = clock)
        val before = registry.current()

        Files.writeString(file, """{"schema_version":"source-connections.v1","connections":[],"extra":1}""")
        val invalid = registry.reload()
        assertTrue(invalid is ReloadResult.Invalid)
        assertEquals("SOURCE_CONFIG_INVALID", (invalid as ReloadResult.Invalid).code)
        assertEquals(before.revision, registry.current().revision)
        assertEquals(listOf("p1"), registry.current().profiles.map { it.id })

        // Immediately fixing the file without advancing the clock succeeds because the invalid attempt did not update lastReloadAt.
        Files.writeString(file, connectionsJson("p2", "http://127.0.0.1:9002"))
        val ok = registry.reload()
        assertTrue(ok is ReloadResult.Success, "expected immediate reload after invalid to succeed: $ok")
        assertEquals(listOf("p2"), registry.current().profiles.map { it.id })
    }

    @Test
    fun `concurrent reload returns Busy via tryLock`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstInit = AtomicBoolean(true)
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val registry =
            SourceRegistry(
                file,
                load = { bytes ->
                    if (!firstInit.getAndSet(false)) {
                        entered.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                    readSourceConnections(bytes.inputStream())
                },
                clock = clock,
            )
        clock.advanceMillis(5_000)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = pool.submit<ReloadResult> { registry.reload() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(ReloadResult.Busy, registry.reload())
            release.countDown()
            assertTrue(first.get(5, TimeUnit.SECONDS) is ReloadResult.Success)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `cooldown allows reload after 5 seconds and computes added removed changed diffs`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val registry = SourceRegistry(file, clock = clock)

        Files.writeString(
            file,
            """{"schema_version":"source-connections.v2","connections":[
              {"id":"p1","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:9002",
               "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
                 "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]},
              {"id":"pg1","source_kind":"postgresql","source_database_id":"db","host":"127.0.0.1","port":5432,
               "database":"app","username_env":"PG_U","password_env":"PG_P"}
            ]}""",
        )
        val first = registry.reload() as ReloadResult.Success
        assertEquals(listOf("pg1"), first.added)
        assertEquals(emptyList<String>(), first.removed)
        assertEquals(listOf("p1"), first.changed)

        clock.advanceMillis(4_999)
        assertEquals(ReloadResult.TooFrequent, registry.reload())

        clock.advanceMillis(1)
        Files.writeString(file, connectionsJson("p3", "http://127.0.0.1:9003"))
        val second = registry.reload() as ReloadResult.Success
        assertEquals(listOf("p3"), second.added)
        assertEquals(listOf("p1", "pg1"), second.removed)
        assertEquals(emptyList<String>(), second.changed)
    }

    @Test
    fun `rejects directory, missing file, oversized file and symlink`() {
        val dir = Files.createDirectory(tempDir.resolve("dir"))
        assertThrows(SourceInputFailure::class.java) { SourceRegistry(dir) }

        val valid = writeConnections("p1", "http://127.0.0.1:9001")
        val registry = SourceRegistry(valid)

        Files.delete(valid)
        val missing = registry.reload() as ReloadResult.Invalid
        assertEquals("SOURCE_FILE_INVALID", missing.code)

        Files.write(valid, ByteArray(MAX_SOURCE_CONFIG_BYTES + 1) { ' '.code.toByte() })
        val oversized = registry.reload() as ReloadResult.Invalid
        assertEquals("SOURCE_CONFIG_TOO_LARGE", oversized.code)

        val link = tempDir.resolve("link.json")
        val symlinkCreated =
            runCatching {
                Files.createSymbolicLink(link, valid)
                true
            }.getOrDefault(false)
        if (symlinkCreated) {
            val thrown = assertThrows(SourceInputFailure::class.java) { SourceRegistry(link) }
            assertEquals("SOURCE_FILE_INVALID", thrown.code)
        }
    }

    @Test
    fun `single buffer read keeps revision aligned with parsed bytes even if file changes during parse`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val originalBytes = Files.readAllBytes(file)
        val expectedRevision = sha256Hex(originalBytes)
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val firstInit = AtomicBoolean(true)
        val registry =
            SourceRegistry(
                file,
                load = { bytes ->
                    if (!firstInit.getAndSet(false)) {
                        Files.writeString(file, connectionsJson("p-overwritten", "http://127.0.0.1:9999"))
                    }
                    readSourceConnections(bytes.inputStream())
                },
                clock = clock,
            )
        clock.advanceMillis(5_000)
        val reloaded = registry.reload() as ReloadResult.Success
        assertEquals(expectedRevision, reloaded.state.revision)
        assertEquals(listOf("p1"), reloaded.state.profiles.map { it.id })
        assertNotEquals(sha256Hex(Files.readAllBytes(file)), reloaded.state.revision)
    }

    @Test
    fun `old SourceHttp rejects profile from new generation while old generation remains isolated`() {
        val file = writeConnections("p1", "http://127.0.0.1:9001")
        val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
        val registry = SourceRegistry(file, clock = clock)
        val oldState = registry.current()

        Files.writeString(file, connectionsJson("p1", "http://127.0.0.1:9002"))
        clock.advanceMillis(5_000)
        val newState = (registry.reload() as ReloadResult.Success).state

        val thrown =
            assertThrows(SourceHttpFailure::class.java) {
                oldState.http!!.get(newState.profiles.single(), emptyMap(), SourceBudget())
            }
        assertEquals("SOURCE_PROFILE_NOT_CONFIGURED", thrown.code)
    }

    private fun writeConnections(
        id: String,
        baseUrl: String,
    ): Path =
        tempDir.resolve("connections.json").also {
            Files.writeString(it, connectionsJson(id, baseUrl))
        }

    private fun connectionsJson(
        id: String,
        baseUrl: String,
    ): String =
        """{"schema_version":"source-connections.v1","connections":[{
          "id":"$id","source_kind":"prometheus","transport":"direct","base_url":"$baseUrl",
          "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
            "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}]}"""

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = current

        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }
}

package io.ltverdict.sources

import io.ltverdict.core.sha256Hex
import io.ltverdict.core.utf8Compare
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

// W3.1, ADR 0033 (P9): atomic profile reload from the `--connections` file configured at startup.
// Every operation reads `current()` once and uses the paired (profiles, postgres, http, promql) generation.

internal class SourceState(
    val profiles: List<SourceProfile>,
    val postgres: List<PostgresProfile>,
    val http: SourceHttp?,
    val promql: PromqlSource?,
    val revision: String?,
) {
    fun allProfileIds(): List<String> = (profiles.map { it.id } + postgres.map { it.id }).sortedWith(::utf8Compare)
}

internal sealed interface ReloadResult {
    data class Success(
        val state: SourceState,
        val added: List<String>,
        val removed: List<String>,
        val changed: List<String>,
    ) : ReloadResult

    data object NotConfigured : ReloadResult

    data object Busy : ReloadResult

    data object TooFrequent : ReloadResult

    data class Invalid(
        val code: String,
        val jsonPointer: String? = null,
        val detail: String? = null,
    ) : ReloadResult
}

internal class SourceRegistry(
    path: Path?,
    private val load: (ByteArray) -> SourceConnections = { readSourceConnections(it.inputStream()) },
    private val clock: Clock = Clock.systemUTC(),
    private val systemProperty: (String) -> String? = System::getProperty,
    private val environment: (String) -> String? = System::getenv,
) {
    private val path: Path? = path?.toAbsolutePath()?.normalize()
    private val reloadLock = ReentrantLock()
    private var lastReloadAt: Instant? = null
    private val stateRef: AtomicReference<SourceState>

    init {
        val initial =
            if (this.path == null) {
                SourceState(emptyList(), emptyList(), null, null, null)
            } else {
                when (val loaded = loadFromDisk(this.path)) {
                    is DiskLoad.Loaded -> loaded.state
                    is DiskLoad.Failed -> throw SourceInputFailure(loaded.code, loaded.jsonPointer, loaded.detail)
                }
            }
        stateRef = AtomicReference(initial)
    }

    fun current(): SourceState = stateRef.get()

    fun reload(): ReloadResult {
        val target = path ?: return ReloadResult.NotConfigured
        if (!reloadLock.tryLock()) return ReloadResult.Busy
        return try {
            val now = clock.instant()
            val last = lastReloadAt
            if (last != null && Duration.between(last, now) < RELOAD_COOLDOWN) {
                return ReloadResult.TooFrequent
            }
            when (val loaded = loadFromDisk(target)) {
                is DiskLoad.Failed -> ReloadResult.Invalid(loaded.code, loaded.jsonPointer, loaded.detail)
                is DiskLoad.Loaded -> {
                    val previous = stateRef.getAndSet(loaded.state)
                    lastReloadAt = now
                    val (added, removed, changed) = diff(previous, loaded.state)
                    ReloadResult.Success(loaded.state, added, removed, changed)
                }
            }
        } finally {
            reloadLock.unlock()
        }
    }

    private sealed interface DiskLoad {
        data class Loaded(
            val state: SourceState,
        ) : DiskLoad

        data class Failed(
            val code: String,
            val jsonPointer: String? = null,
            val detail: String? = null,
        ) : DiskLoad
    }

    private fun loadFromDisk(target: Path): DiskLoad {
        val bytes =
            try {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    return DiskLoad.Failed("SOURCE_FILE_INVALID")
                }
                Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS).use { stream ->
                    stream.readNBytes(MAX_SOURCE_CONFIG_BYTES + 1).also { raw ->
                        if (raw.size > MAX_SOURCE_CONFIG_BYTES) return DiskLoad.Failed("SOURCE_CONFIG_TOO_LARGE")
                    }
                }
            } catch (_: IOException) {
                return DiskLoad.Failed("SOURCE_READ_ERROR")
            }
        val revision = sha256Hex(bytes)
        val connections =
            try {
                load(bytes)
            } catch (failure: SourceInputFailure) {
                return DiskLoad.Failed(failure.code, failure.jsonPointer, failure.detail)
            } catch (_: IllegalArgumentException) {
                return DiskLoad.Failed("SOURCE_CONFIG_INVALID")
            }
        return try {
            val http = if (connections.http.isEmpty()) null else SourceHttp(connections.http, systemProperty, environment)
            // Keep an empty PromqlSource when `--connections` is configured with PostgreSQL-only profiles so queued
            // HTTP jobs fail cleanly with SOURCE_PROFILE_NOT_FOUND rather than SOURCE_NOT_CONFIGURED.
            val promql =
                http?.let { PromqlSource(connections.http, it) }
                    ?: PromqlSource(emptyList(), SourceHttp(emptyList(), systemProperty, environment))
            DiskLoad.Loaded(SourceState(connections.http, connections.postgres, http, promql, revision))
        } catch (failure: SourceHttpFailure) {
            DiskLoad.Failed(failure.code)
        } catch (_: IllegalArgumentException) {
            DiskLoad.Failed("SOURCE_CONFIG_INVALID")
        }
    }

    private fun diff(
        previous: SourceState,
        next: SourceState,
    ): Triple<List<String>, List<String>, List<String>> {
        val prevMap = previous.profiles.associateBy { it.id } + previous.postgres.associateBy { it.id }
        val nextMap = next.profiles.associateBy { it.id } + next.postgres.associateBy { it.id }
        val added = (nextMap.keys - prevMap.keys).sortedWith(::utf8Compare)
        val removed = (prevMap.keys - nextMap.keys).sortedWith(::utf8Compare)
        val changed =
            prevMap.keys
                .intersect(nextMap.keys)
                .filter { prevMap.getValue(it) != nextMap.getValue(it) }
                .sortedWith(::utf8Compare)
        return Triple(added, removed, changed)
    }

    private companion object {
        val RELOAD_COOLDOWN: Duration = Duration.ofSeconds(5)
    }
}

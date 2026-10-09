package io.ltverdict.storage

import io.ltverdict.core.MAX_RELEASE_BYTES
import io.ltverdict.core.RELEASE_DRAFT_FIELDS
import io.ltverdict.core.RELEASE_ID
import io.ltverdict.core.RELEASE_IMMUTABLE_FIELDS
import io.ltverdict.core.RELEASE_SCHEMA
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.releaseId
import io.ltverdict.core.releaseStartedAtMillis
import io.ltverdict.core.validateRelease
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

internal data class ReleaseCorruptName(
    val name: String,
    val reason: String,
)

internal data class ReleasePage(
    val releases: List<JsonObject>,
    val nextAfter: String?,
    val seriesSummary: List<Pair<String, Int>>,
    val corruptCount: Int,
    val corruptNames: List<ReleaseCorruptName>,
)

internal data class ReleaseLookup(
    val byAnalysis: Map<String, JsonObject>,
    val ambiguous: Set<String>,
)

internal class ReleaseStore(
    private val dataDirectory: DataDirectory,
) {
    fun createRelease(
        draft: JsonObject,
        now: Instant,
        suffix: () -> String = ::randomReleaseSuffix,
    ): JsonObject {
        require(draft.keys == RELEASE_DRAFT_FIELDS) { "INVALID_RELEASE" }
        val startedAt =
            (draft["started_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("INVALID_RELEASE")
        val millis = releaseStartedAtMillis(startedAt)
        val stamp = JsonPrimitive(now.truncatedTo(ChronoUnit.MILLIS).toString())
        repeat(RELEASE_ID_ATTEMPTS) {
            val id = releaseId(millis, suffix())
            val record = JsonObject(draft + mapOf("release_id" to JsonPrimitive(id), "created_at" to stamp, "updated_at" to stamp))
            // Validation, canonical bytes, the size check and the forced staged write run outside the mutex
            // (ADR 0002, addendum 2026-10-01); the mutex keeps the checks and the publication only.
            val prepared = prepareRelease(record)
            try {
                val registered = releaseAnalysisIds(prepared.record)
                val published =
                    synchronized(dataDirectory.operationLock) {
                        dataDirectory.requireOpen()
                        val directory = ensureReleasesDirectory()
                        val scan = scanReleasesUnlocked()
                        if (scan.total >= MAX_RELEASES) throw IllegalArgumentException("RELEASE_LIMIT_REACHED")
                        if (scan.valid.any { existing -> releaseAnalysisIds(existing).any(registered::contains) }) {
                            throw IllegalArgumentException("RELEASE_ANALYSIS_ALREADY_REGISTERED")
                        }
                        val target = directory.resolve("$id.json")
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            false
                        } else {
                            Files.move(prepared.staged, target, StandardCopyOption.ATOMIC_MOVE)
                            forceDirectory(directory)
                            true
                        }
                    }
                if (published) return prepared.record
            } finally {
                prepared.discard()
            }
        }
        throw IllegalStateException("RELEASE_ID_COLLISION")
    }

    fun readRelease(id: String): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireReleaseId(id)
            val target = releaseTargetOrNull(id) ?: return@synchronized null
            when (val entry = readReleaseEntry(target)) {
                is ReleaseEntry.Valid -> entry.record
                is ReleaseEntry.Rejected -> corruptRelease("record ${entry.reason}")
            }
        }

    fun replaceRelease(
        id: String,
        update: (JsonObject) -> JsonObject,
    ): JsonObject {
        requireReleaseId(id)
        val (existing, existingBytes) =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                val target = releaseTargetOrNull(id) ?: throw NoSuchElementException("RELEASE_NOT_FOUND")
                val entry = readReleaseEntry(target) as? ReleaseEntry.Valid ?: corruptRelease("record cannot be replaced")
                entry.record to Files.readAllBytes(target)
            }
        val next = update(existing)
        if (RELEASE_IMMUTABLE_FIELDS.any { next[it] != existing[it] }) throw IllegalArgumentException("INVALID_RELEASE")
        val prepared = prepareRelease(next)
        try {
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                val target = releaseTargetOrNull(id) ?: throw NoSuchElementException("RELEASE_NOT_FOUND")
                // Optimistic check: the record was neither replaced nor removed while the new bytes were written.
                if (Files.size(target) != existingBytes.size.toLong() || !Files.readAllBytes(target).contentEquals(existingBytes)) {
                    throw IllegalArgumentException("RELEASE_CHANGED")
                }
                val registered = releaseAnalysisIds(prepared.record)
                val others = scanReleasesUnlocked().valid.filter { (it["release_id"] as JsonPrimitive).content != id }
                if (others.any { other -> releaseAnalysisIds(other).any(registered::contains) }) {
                    throw IllegalArgumentException("RELEASE_ANALYSIS_ALREADY_REGISTERED")
                }
                Files.move(prepared.staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(target.parent)
            }
            return prepared.record
        } finally {
            prepared.discard()
        }
    }

    fun deleteRelease(id: String): Boolean =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireReleaseId(id)
            val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@synchronized false
            requireReleasesDirectory(directory)
            val target = directory.resolve("$id.json")
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@synchronized false
            // A damaged record is removed by its safe identifier without parsing; a directory or a special file is not ours.
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                corruptReleaseRegistry("unsafe entry $id")
            }
            Files.delete(target)
            forceDirectory(directory)
            true
        }

    fun listReleases(
        series: String?,
        afterReleaseId: String?,
        limit: Int,
    ): ReleasePage =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            require(limit in 1..100) { "INVALID_PAGE_LIMIT" }
            if (afterReleaseId != null) requireReleaseId(afterReleaseId)
            val scan = scanReleasesUnlocked()
            val summary =
                scan.valid
                    .groupingBy { (it["series"] as JsonPrimitive).content }
                    .eachCount()
                    .toList()
                    .sortedBy { it.first }
            val selected =
                scan.valid
                    .filter { series == null || (it["series"] as JsonPrimitive).content == series }
                    .filter { afterReleaseId == null || (it["release_id"] as JsonPrimitive).content < afterReleaseId }
            val page = selected.take(limit)
            ReleasePage(
                page,
                if (selected.size > limit) (page.last()["release_id"] as JsonPrimitive).content else null,
                summary,
                scan.corrupt.size,
                scan.corrupt.take(RELEASE_CORRUPT_NAMES),
            )
        }

    fun findReleasesByAnalysis(analysisIds: Set<String>): ReleaseLookup =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val found = mutableMapOf<String, MutableList<JsonObject>>()
            scanReleasesUnlocked().valid.forEach { record ->
                releaseAnalysisIds(record).filter { it in analysisIds }.forEach { found.getOrPut(it) { mutableListOf() } += record }
            }
            ReleaseLookup(
                found.filterValues { it.size == 1 }.mapValues { it.value.single() },
                found.filterValues { it.size > 1 }.keys,
            )
        }

    private sealed interface ReleaseEntry {
        data class Valid(
            val record: JsonObject,
        ) : ReleaseEntry

        data class Rejected(
            val reason: String,
        ) : ReleaseEntry
    }

    private data class ReleaseScan(
        val valid: List<JsonObject>,
        val corrupt: List<ReleaseCorruptName>,
        val total: Int,
    )

    private class PreparedRelease(
        val record: JsonObject,
        val staging: Path,
        val staged: Path,
    ) {
        fun discard() = DataDirectory.deleteTree(staging)
    }

    private fun ensureReleasesDirectory(): Path {
        val target = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return requireReleasesDirectory(target)
        Files.createDirectory(target)
        forceDirectory(dataDirectory.root)
        return target
    }

    private fun releaseTargetOrNull(id: String): Path? {
        val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        requireReleasesDirectory(directory)
        return directory.resolve("$id.json").takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    }

    private fun scanReleasesUnlocked(): ReleaseScan {
        val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return ReleaseScan(emptyList(), emptyList(), 0)
        requireReleasesDirectory(directory)
        val valid = mutableListOf<JsonObject>()
        val corrupt = mutableListOf<ReleaseCorruptName>()
        var total = 0
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                // Every directory element counts, damaged or foreign ones included; nothing is truncated silently.
                if (++total > MAX_RELEASES) corruptReleaseRegistry("more than $MAX_RELEASES entries")
                when (val outcome = readReleaseEntry(entry)) {
                    is ReleaseEntry.Valid -> valid += outcome.record
                    is ReleaseEntry.Rejected -> corrupt += ReleaseCorruptName(entry.fileName.toString().take(128), outcome.reason)
                }
            }
        }
        return ReleaseScan(
            valid.sortedByDescending { (it["release_id"] as JsonPrimitive).content },
            corrupt.sortedBy { it.name },
            total,
        )
    }

    private fun readReleaseEntry(entry: Path): ReleaseEntry {
        val name = entry.fileName.toString()
        if (!RELEASE_FILE.matches(name) || Files.isSymbolicLink(entry) || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return ReleaseEntry.Rejected("UNSAFE_ENTRY")
        }
        // An unreadable or concurrently replaced element is one damaged entry, never a failure of the whole listing.
        val bytes =
            try {
                if (Files.size(entry) > MAX_RELEASE_BYTES) return ReleaseEntry.Rejected("TOO_LARGE")
                Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_RELEASE_BYTES + 1) }
            } catch (_: IOException) {
                return ReleaseEntry.Rejected("UNSAFE_ENTRY")
            }
        if (bytes.size > MAX_RELEASE_BYTES) return ReleaseEntry.Rejected("TOO_LARGE")
        val document =
            try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            } catch (_: SerializationException) {
                return ReleaseEntry.Rejected("CORRUPT")
            } catch (_: IllegalArgumentException) {
                return ReleaseEntry.Rejected("CORRUPT")
            } catch (_: CharacterCodingException) {
                return ReleaseEntry.Rejected("CORRUPT")
            }
        val version = (document["schema_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version != null && version != RELEASE_SCHEMA) return ReleaseEntry.Rejected("UNSUPPORTED_VERSION")
        val validated =
            try {
                validateRelease(document)
            } catch (_: RuntimeException) {
                return ReleaseEntry.Rejected("CORRUPT")
            }
        if (!bytes.contentEquals(canonicalJson(validated)) || "${(validated["release_id"] as JsonPrimitive).content}.json" != name) {
            return ReleaseEntry.Rejected("CORRUPT")
        }
        return ReleaseEntry.Valid(validated)
    }

    private fun prepareRelease(record: JsonObject): PreparedRelease {
        val validated = validateRelease(record)
        val bytes = canonicalJson(validated)
        if (bytes.size > MAX_RELEASE_BYTES) throw IllegalArgumentException("RELEASE_TOO_LARGE")
        val staging =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                requireOwnedDirectory(dataDirectory.staging)
                Files.createDirectory(dataDirectory.staging.resolve(UUID.randomUUID().toString()))
            }
        try {
            val staged = staging.resolve("${(validated["release_id"] as JsonPrimitive).content}.json")
            writeForced(staged, bytes)
            forceDirectory(staging)
            return PreparedRelease(validated, staging, staged)
        } catch (failure: Throwable) {
            DataDirectory.deleteTree(staging)
            throw failure
        }
    }
}

private fun releaseAnalysisIds(record: JsonObject): List<String> =
    (record["analyses"] as JsonArray).map { ((it as JsonObject)["analysis_id"] as JsonPrimitive).content }

internal fun randomReleaseSuffix(): String = HexFormat.of().formatHex(ByteArray(4).also(RELEASE_RANDOM::nextBytes))

private fun requireReleaseId(id: String) {
    require(RELEASE_ID.matches(id)) { "INVALID_RELEASE_ID" }
}

private fun requireReleasesDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptReleaseRegistry("unsafe releases directory")
    }
    return path
}

private fun corruptRelease(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE: $message")

private fun corruptReleaseRegistry(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE_REGISTRY: $message")

private const val RELEASES_DIRECTORY = "releases"

private const val RELEASE_ID_ATTEMPTS = 8

private const val RELEASE_CORRUPT_NAMES = 20

internal const val MAX_RELEASES = 1_000

private val RELEASE_FILE = Regex("[0-9]{15}-[0-9a-f]{8}\\.json")

private val RELEASE_RANDOM = java.security.SecureRandom()

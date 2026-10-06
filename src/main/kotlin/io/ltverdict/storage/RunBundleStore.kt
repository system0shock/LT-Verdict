package io.ltverdict.storage

import io.ltverdict.core.MAX_POD_VIEW_BYTES
import io.ltverdict.core.MAX_RELEASE_BYTES
import io.ltverdict.core.RELEASE_DRAFT_FIELDS
import io.ltverdict.core.RELEASE_ID
import io.ltverdict.core.RELEASE_IMMUTABLE_FIELDS
import io.ltverdict.core.RELEASE_SCHEMA
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionBinding
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.releaseId
import io.ltverdict.core.releaseStartedAtMillis
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateBaselineCondition
import io.ltverdict.core.validateBaselineSelection
import io.ltverdict.core.validateRelease
import io.ltverdict.core.validateRunPeriod
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.detectSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.PriorityQueue
import java.util.UUID

internal data class AcceptedInput(
    val runId: String,
    val sourceType: SourceType,
    val sha256: String,
    val sizeBytes: Long,
    val originalFilename: String,
    val path: Path,
)

internal data class RunSummary(
    val runId: String,
    val sourceType: SourceType,
    val sha256: String,
    val sizeBytes: Long,
    val originalFilename: String,
)

internal data class RunPage(
    val runs: List<RunSummary>,
    val nextAfter: String?,
)

internal data class AnalysisSummary(
    val analysisId: String,
    val policySha256: String,
    val policyVerdict: String,
    val runValidity: String,
    val policyId: String? = null,
    val resourceArm: String? = null,
    val resourceSnapshotSha256: String? = null,
)

internal data class AnalysisPage(
    val analyses: List<AnalysisSummary>,
    val nextAfter: String?,
)

internal data class StoredArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class StoredAnalysis(
    val path: Path,
    val artifacts: List<StoredArtifact>,
)

internal data class VerifiedAnalysis(
    val result: JsonObject,
    val identity: JsonObject,
    val run: JsonObject?,
)

internal data class ComparisonDocuments(
    val run: JsonObject?,
    val result: JsonObject,
    val identity: JsonObject,
)

internal data class ComparisonHistoryEntry(
    val runId: String,
    val analysisId: String,
    val documents: ComparisonDocuments,
)

internal data class ComparisonHistory(
    val entries: List<ComparisonHistoryEntry>,
    val truncated: Boolean,
)

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

/** One active baseline: its scope is the pair (series, arm); [legacy] marks the file `baseline.json` (ADR 0019, section 7). */
internal data class BaselineSlot(
    val series: String,
    val arm: String?,
    val selection: JsonObject,
    val legacy: Boolean,
) {
    fun key(): String = baselineSlotKey(series, arm)
}

internal class RunBundleStore(
    private val dataDirectory: DataDirectory,
) {
    fun acceptInput(
        source: InputStream,
        originalFilename: String,
        maxBytes: Long = 4_294_967_296L,
    ): AcceptedInput {
        val staging =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                requireOwnedDirectory(dataDirectory.staging)
                requireOwnedDirectory(dataDirectory.runs)
                require(maxBytes >= 0) { "INVALID_SIZE_LIMIT" }
                require(isSafeFilename(originalFilename)) { "UNSAFE_FILENAME" }
                val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
                Files.createDirectory(staging)
                staging
            }
        try {
            val inputs = Files.createDirectory(staging.resolve("inputs"))
            val stagedSource = inputs.resolve("source.bin")
            val (sizeBytes, sha256) = copyInput(source, stagedSource, maxBytes)
            if (sizeBytes == 0L) throw IllegalArgumentException("EMPTY_INPUT")
            val sourceType = detectSource(stagedSource)
            val runId = "${sourceType.wireName}-$sha256"
            val target = dataDirectory.runs.resolve(runId)
            val accepted = AcceptedInput(runId, sourceType, sha256, sizeBytes, originalFilename, target.resolve("inputs/source.bin"))
            writeForced(staging.resolve("source.json"), sourceMetadata(accepted))
            forceDirectory(inputs)
            forceDirectory(staging)

            val existing =
                synchronized(dataDirectory.operationLock) {
                    dataDirectory.requireOpen()
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                        forceDirectory(dataDirectory.runs)
                        return requireInputUnlocked(runId)
                    }
                    requireInputUnlocked(runId)
                }
            if (Files.mismatch(stagedSource, existing.path) != -1L) corrupt("existing input bytes differ")
            return existing
        } finally {
            DataDirectory.deleteTree(staging)
        }
    }

    fun requireInput(runId: String): AcceptedInput =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireInputUnlocked(runId)
        }

    fun listRuns(
        afterRunId: String?,
        limit: Int,
    ): RunPage =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireOwnedDirectory(dataDirectory.runs)
            require(limit in 1..100) { "INVALID_PAGE_LIMIT" }
            if (afterRunId != null) requireRunId(afterRunId)

            val names = PriorityQueue<String>(limit + 1, reverseOrder())
            Files.newDirectoryStream(dataDirectory.runs).use { entries ->
                entries.forEach { path ->
                    val name = path.fileName.toString()
                    if (RUN_ID.matches(name) &&
                        (afterRunId == null || name > afterRunId) &&
                        !Files.isSymbolicLink(path) &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    ) {
                        names.add(name)
                        if (names.size > limit + 1) names.remove()
                    }
                }
            }
            val selected = names.toList().sorted()
            val returned = selected.take(limit).map { requireInputUnlocked(it).toSummary() }
            RunPage(returned, if (selected.size > limit) returned.last().runId else null)
        }

    fun readAnalysis(
        runId: String,
        analysisId: String,
    ): StoredAnalysis? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            readAnalysisUnlocked(runId, analysisId)
        }

    fun listAnalyses(
        runId: String,
        afterAnalysisId: String?,
        limit: Int,
    ): AnalysisPage {
        val (page, policyFiles) =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                require(limit in 1..100) { "INVALID_PAGE_LIMIT" }
                if (afterAnalysisId != null) requireAnalysisId(afterAnalysisId)
                requireInputUnlocked(runId)
                val analyses = dataDirectory.runs.resolve(runId).resolve("analyses")
                if (!Files.exists(analyses, LinkOption.NOFOLLOW_LINKS)) {
                    return@synchronized AnalysisPage(emptyList(), null) to emptyList<Pair<Path, Long>?>()
                }
                requireOwnedDirectory(analyses)

                val names = PriorityQueue<String>(limit + 1, reverseOrder())
                Files.newDirectoryStream(analyses).use { entries ->
                    entries.forEach { path ->
                        val name = path.fileName.toString()
                        if (SHA256.matches(name) &&
                            (afterAnalysisId == null || name > afterAnalysisId) &&
                            !Files.isSymbolicLink(path) &&
                            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        ) {
                            names.add(name)
                            if (names.size > limit + 1) names.remove()
                        }
                    }
                }
                val selected = names.toList().sorted()
                val returned =
                    selected.take(limit).map { analysisId ->
                        val stored =
                            readAnalysisUnlocked(runId, analysisId)
                                ?: corrupt("listed analysis disappeared")
                        val identity =
                            parseObject(
                                Files.readAllBytes(requireOwnedFile(stored.path.resolve("identity.json"))),
                                "analysis identity",
                            )
                        val result =
                            parseObject(
                                Files.readAllBytes(requireOwnedFile(stored.path.resolve("analysis-result.json"))),
                                "analysis result",
                            )
                        val summary =
                            AnalysisSummary(
                                analysisId,
                                identity.string("policy_sha256"),
                                result.string("policy_verdict"),
                                result.string("run_validity"),
                                resourceArm = identity.optionalString("resource_arm"),
                                resourceSnapshotSha256 = identity.optionalString("resource_snapshot_sha256"),
                            )
                        val policy =
                            stored.artifacts.find { it.path == POLICY_FILE }?.let { artifact ->
                                try {
                                    requireOwnedFile(stored.path.resolve(POLICY_FILE)) to artifact.sizeBytes
                                } catch (_: IllegalStateException) {
                                    null
                                }
                            }
                        summary to policy
                    }
                AnalysisPage(returned.map { it.first }, if (selected.size > limit) returned.last().first.analysisId else null) to
                    returned.map { it.second }
            }
        return page.copy(
            analyses =
                page.analyses.mapIndexed { index, summary ->
                    summary.copy(policyId = readPolicyId(policyFiles[index], summary.policySha256))
                },
        )
    }

    private fun readPolicyId(
        policyFile: Pair<Path, Long>?,
        policySha256: String,
    ): String? {
        if (policyFile == null || policySha256 == "NO_POLICY" || policyFile.second > MAX_POLICY_BYTES) return null
        return try {
            val path = requireOwnedFile(policyFile.first)
            val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_POLICY_BYTES + 1) }
            if (bytes.size > MAX_POLICY_BYTES || bytes.size.toLong() != policyFile.second || sha256Hex(bytes) != policySha256) {
                return null
            }
            val policy = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val id = policy["policy_id"] as? JsonPrimitive
            id?.takeIf { it.isString && it.content.isNotEmpty() && it.content.encodeToByteArray().size <= 128 }?.content
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun writeAnalysisAtomically(
        runId: String,
        analysisId: String,
        beforePublish: () -> Unit = {},
        writeStagingDirectory: (Path) -> Unit,
    ): Path {
        val (analyses, staging) =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                requireInputUnlocked(runId)
                requireAnalysisId(analysisId)
                requireOwnedDirectory(dataDirectory.staging)
                val analyses = ensureOwnedDirectory(dataDirectory.runs.resolve(runId).resolve("analyses"))
                val target = analyses.resolve(analysisId)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return readAnalysisUnlocked(runId, analysisId)?.path ?: corrupt("analysis is incomplete")
                }

                val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
                Files.createDirectory(staging)
                analyses to staging
            }
        val target = analyses.resolve(analysisId)
        try {
            writeStagingDirectory(staging)
            val artifacts = inspectStagedArtifacts(staging)
            writeForced(staging.resolve("manifest.json"), analysisManifest(artifacts))
            forceDirectory(staging)
            return synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    return@synchronized readAnalysisUnlocked(runId, analysisId)?.path ?: corrupt("analysis is incomplete")
                }
                beforePublish()
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(analyses)
                target
            }
        } finally {
            DataDirectory.deleteTree(staging)
        }
    }

    fun readBaseline(): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            readBaselineUnlocked()
        }

    fun replaceBaseline(selection: JsonObject): JsonObject =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val validated = validateBaselineSelection(selection)
            val bytes = canonicalJson(validated)
            check(bytes.size <= MAX_BASELINE_BYTES)
            requireOwnedDirectory(dataDirectory.staging)
            val target = dataDirectory.root.resolve(BASELINE_FILE)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireBaselineFile(target)
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                val staged = staging.resolve(BASELINE_FILE)
                writeForced(staged, bytes)
                forceDirectory(staging)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(dataDirectory.root)
                validated
            } finally {
                DataDirectory.deleteTree(staging)
            }
        }

    fun readBaselineCondition(
        baselineReference: JsonObject,
        currentReference: JsonObject,
        windows: WindowComparisonRequest?,
    ): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            readBaselineConditionUnlocked(baselineConditionBinding(baselineReference, currentReference, windows))
        }

    fun replaceBaselineCondition(condition: JsonObject): JsonObject =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val validated = validateBaselineCondition(condition)
            val bytes = canonicalJson(validated)
            check(bytes.size <= MAX_BASELINE_CONDITION_BYTES)
            requireOwnedDirectory(dataDirectory.staging)
            val directoryTarget = dataDirectory.root.resolve(BASELINE_CONDITIONS_DIRECTORY)
            val directory =
                if (Files.exists(directoryTarget, LinkOption.NOFOLLOW_LINKS)) {
                    requireBaselineConditionsDirectory(directoryTarget)
                } else {
                    Files.createDirectory(directoryTarget)
                    forceDirectory(dataDirectory.root)
                    directoryTarget
                }
            val target = baselineConditionPath(directory, baselineConditionBinding(validated))
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireBaselineConditionFile(target)
            } else if (Files.newDirectoryStream(directory).use { it.take(MAX_BASELINE_CONDITION_FILES).count() } >=
                MAX_BASELINE_CONDITION_FILES
            ) {
                // A scoped delete reads every record under the lock, so the directory is bounded (ADR 0019, section 7).
                throw IllegalArgumentException("BASELINE_CONDITIONS_LIMIT_REACHED")
            }
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                val staged = staging.resolve(target.fileName.toString())
                writeForced(staged, bytes)
                forceDirectory(staging)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(directory)
                validated
            } finally {
                DataDirectory.deleteTree(staging)
            }
        }

    /**
     * Clears the legacy file `baseline.json` and only the condition records of its selection that no slot file uses
     * (ADR 0019, section 7). Slots are not touched; a damaged legacy file is removed without its records.
     */
    fun clearBaseline() {
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val target = dataDirectory.root.resolve(BASELINE_FILE)
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
            requireBaselineFile(target)
            val reference =
                try {
                    readBaselineUnlocked()?.baselineReference()
                } catch (_: IllegalStateException) {
                    null
                }
            if (reference != null && slotFilesUnlocked().none { (_, selection) -> selection.baselineReference() == reference }) {
                baselineConditionDeletionUnlocked(setOf(reference))()
            }
            Files.delete(target)
            forceDirectory(dataDirectory.root)
        }
    }

    /** Effective slots: the files of `<data>/baselines` plus the legacy file unless a slot of its key shadows it. */
    fun listBaselineSlots(): List<BaselineSlot> =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            slotStateUnlocked().effective.sortedWith(compareBy({ it.series }, { it.arm.orEmpty() }, { it.legacy }))
        }

    /**
     * Selects a slot, reads its reference and the condition record of the pair in one operation under one lock.
     * `series == null` reads only the legacy file, as before slots existed; otherwise the slot of (series, arm) is read.
     */
    fun readBaselineSlotWithCondition(
        series: String?,
        arm: String?,
        currentReference: JsonObject,
        windows: WindowComparisonRequest?,
    ): Pair<BaselineSlot?, JsonObject?> =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val found =
                if (series == null) {
                    readBaselineUnlocked()?.let { slotOf(it, legacy = true) }
                } else {
                    slotStateUnlocked().effective.firstOrNull { it.series == series && it.arm == arm }
                }
            val slot = found ?: return@synchronized null to null
            slot to readBaselineConditionUnlocked(baselineConditionBinding(slot.selection.baselineReference(), currentReference, windows))
        }

    /**
     * Writes the slot of (selection.series, [arm]) and then removes the legacy file of the same key. [arm] must be the arm of
     * the baseline analysis identity: a slot whose name does not match its identity cannot be read back.
     */
    fun replaceBaselineSlot(
        selection: JsonObject,
        arm: String?,
    ): BaselineSlot =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val validated = validateBaselineSelection(selection)
            val bytes = canonicalJson(validated)
            check(bytes.size <= MAX_BASELINE_BYTES)
            requireOwnedDirectory(dataDirectory.staging)
            val reference = validated.baselineReference()
            val identity =
                readIdentityUnlocked(reference.string("run_id"), reference.string("analysis_id"))
                    ?: throw IllegalArgumentException("BASELINE_ANALYSIS_NOT_FOUND")
            require(identityArm(identity) == arm) { "BASELINE_ARM_MISMATCH" }
            val slot = BaselineSlot((validated["series"] as JsonPrimitive).content, arm, validated, legacy = false)
            val state = slotStateUnlocked()
            if (state.effective.none { it.key() == slot.key() } && state.effective.size >= MAX_BASELINE_SLOTS) {
                throw IllegalArgumentException("BASELINE_SLOTS_LIMIT_REACHED")
            }
            val directory = ensureBaselineSlotsDirectory()
            val target = directory.resolve("${slot.key()}.json")
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                val staged = staging.resolve(target.fileName.toString())
                writeForced(staged, bytes)
                forceDirectory(staging)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(directory)
            } finally {
                DataDirectory.deleteTree(staging)
            }
            // The slot wins as soon as it is published; the legacy file of its key is removed only afterwards.
            if (state.legacy?.key() == slot.key()) {
                Files.delete(dataDirectory.root.resolve(BASELINE_FILE))
                forceDirectory(dataDirectory.root)
            }
            slot
        }

    /**
     * Removes the slot of (series, arm) and the legacy file of the same key, the legacy file first so that a stop in
     * between cannot bring the removed baseline back. Condition records go only if their baseline reference belonged to
     * what is removed and no other effective slot uses it. Returns false when there was nothing to remove.
     */
    fun clearBaselineSlot(
        series: String,
        arm: String?,
    ): Boolean =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val state = slotStateUnlocked()
            val key = baselineSlotKey(series, arm)
            val legacy = state.legacy?.takeIf { it.key() == key }
            val file = state.files.firstOrNull { it.key() == key }
            if (legacy == null && file == null) return@synchronized false
            val usedElsewhere =
                state.effective
                    .filter { it.key() != key }
                    .map { it.selection.baselineReference() }
                    .toSet()
            val removed = listOfNotNull(legacy, file).map { it.selection.baselineReference() }.toSet() - usedElsewhere
            // The condition directory is read and checked first: a refusal there must not leave the legacy file removed.
            val deleteConditions = baselineConditionDeletionUnlocked(removed)
            if (legacy != null) {
                Files.delete(dataDirectory.root.resolve(BASELINE_FILE))
                forceDirectory(dataDirectory.root)
            }
            deleteConditions()
            if (file != null) {
                Files.delete(dataDirectory.root.resolve(BASELINE_SLOTS_DIRECTORY).resolve("$key.json"))
                forceDirectory(dataDirectory.root.resolve(BASELINE_SLOTS_DIRECTORY))
            }
            true
        }

    /** Identity of a saved analysis (ADR 0019, section 7): small, hashed against `analysis_id`, no result or manifest read. */
    fun readAnalysisIdentity(
        runId: String,
        analysisId: String,
    ): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            readIdentityUnlocked(runId, analysisId)
        }

    fun readRunPeriod(runId: String): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            readRunPeriodUnlocked(requireInputUnlocked(runId))
        }

    fun replaceRunPeriod(
        runId: String,
        period: JsonObject,
    ): JsonObject =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val accepted = requireInputUnlocked(runId)
            val validated = validateRunPeriod(period)
            require(validated.string("load_input_sha256") == accepted.sha256) { "RUN_PERIOD_INPUT_MISMATCH" }
            val bytes = canonicalJson(validated)
            check(bytes.size <= MAX_RUN_PERIOD_BYTES)
            requireOwnedDirectory(dataDirectory.staging)
            val run = dataDirectory.runs.resolve(runId)
            val target = run.resolve(RUN_PERIOD_FILE)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireRunPeriodFile(target)
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                val staged = staging.resolve(RUN_PERIOD_FILE)
                writeForced(staged, bytes)
                forceDirectory(staging)
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(run)
                validated
            } finally {
                DataDirectory.deleteTree(staging)
            }
        }

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

    /** Cheap existence of a saved analysis for list views: every ancestor is checked without following links. */
    fun analysisExists(
        runId: String,
        analysisId: String,
    ): Boolean =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireRunId(runId)
            requireAnalysisId(analysisId)
            val run = dataDirectory.runs.resolve(runId)
            val analyses = run.resolve("analyses")
            val analysis = analyses.resolve(analysisId)
            listOf(dataDirectory.runs, run, analyses, analysis).all { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) } &&
                Files.isRegularFile(analysis.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)
        }

    /** Full check of a saved analysis (manifest, identity, artifact paths and sizes): OK, MISSING or CORRUPT. */
    fun analysisState(
        runId: String,
        analysisId: String,
    ): String =
        try {
            if (readAnalysis(runId, analysisId) != null) "OK" else "MISSING"
        } catch (_: NoSuchElementException) {
            "MISSING"
        } catch (_: IllegalStateException) {
            "CORRUPT"
        }

    fun readAnalysisDocuments(
        runId: String,
        analysisId: String,
    ): Pair<JsonObject, JsonObject>? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val stored = readAnalysisUnlocked(runId, analysisId) ?: return@synchronized null
            val result = parseObject(Files.readAllBytes(requireOwnedFile(stored.path.resolve(RESULT_FILE))), "analysis result")
            val identity = parseObject(Files.readAllBytes(requireOwnedFile(stored.path.resolve(IDENTITY_FILE))), "analysis identity")
            result to identity
        }

    /**
     * Reads the result, identity and run metadata of an analysis with their SHA-256 checked against the manifest and the
     * result bounded by [maxResultBytes] (ADR 0019, section 1). Ordinary reads compare paths and sizes only, so a same-size
     * substitution of the result would pass them. Published analyses are immutable and this store never deletes them
     * (ADR 0002, addendum 2026-10-01), so only the manifest check runs under the lock; the heavy read and hash run outside it.
     * [outsideLock] runs after the lock is released and before the heavy read; it exists for the lock test.
     */
    fun readVerifiedAnalysis(
        runId: String,
        analysisId: String,
        maxResultBytes: Int = MAX_VERIFIED_RESULT_BYTES,
        outsideLock: () -> Unit = {},
    ): VerifiedAnalysis? {
        require(maxResultBytes in 1..MAX_VERIFIED_RESULT_BYTES)
        val stored =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                readAnalysisUnlocked(runId, analysisId)
            } ?: return null
        outsideLock()
        val result = stored.verifiedBytes(RESULT_FILE, maxResultBytes, tooLarge = true)
        val identity = stored.verifiedBytes(IDENTITY_FILE, MAX_VERIFIED_DOCUMENT_BYTES, tooLarge = false)
        val run =
            if (stored.artifacts.any { it.path == "run.json" }) {
                stored.verifiedBytes("run.json", MAX_VERIFIED_DOCUMENT_BYTES, tooLarge = false)
            } else {
                null
            }
        return VerifiedAnalysis(
            parseObject(result, "analysis result"),
            parseObject(identity, "analysis identity"),
            run?.let { parseObject(it, "run metadata") },
        )
    }

    /**
     * Returns the stored pod-view.json (ADR 0020, section 5), or null when the analysis has none. Ordinary reads compare
     * artifact paths and sizes only, so this read also hashes the file: its SHA-256 must equal both the manifest entry
     * and the identity's pod_view_sha256, which catches a same-size substitution. A binding without a file, or a file
     * without a binding, is corruption.
     */
    fun readPodViewBytes(
        runId: String,
        analysisId: String,
    ): ByteArray? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val stored = readAnalysisUnlocked(runId, analysisId) ?: return@synchronized null
            val identity = parseObject(Files.readAllBytes(requireOwnedFile(stored.path.resolve(IDENTITY_FILE))), "analysis identity")
            val bound = if (identity.containsKey("pod_view_sha256")) identity.string("pod_view_sha256") else null
            val artifact = stored.artifacts.find { it.path == POD_VIEW_FILE }
            if (artifact == null && bound == null) return@synchronized null
            if (artifact == null || artifact.sha256 != bound) corrupt("pod view differs from the analysis identity")
            if (artifact.sizeBytes > MAX_POD_VIEW_BYTES) corrupt("pod view exceeds its size limit")
            val bytes =
                Files.newInputStream(requireOwnedFile(stored.path.resolve(POD_VIEW_FILE)), LinkOption.NOFOLLOW_LINKS).use {
                    it.readNBytes(MAX_POD_VIEW_BYTES + 1)
                }
            if (bytes.size.toLong() != artifact.sizeBytes || sha256Hex(bytes) != artifact.sha256) corrupt("pod view bytes differ")
            bytes
        }

    fun readComparisonDocuments(
        runId: String,
        analysisId: String,
    ): ComparisonDocuments? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val stored = readAnalysisUnlocked(runId, analysisId) ?: return@synchronized null
            val run = stored.path.resolve("run.json")
            ComparisonDocuments(
                run =
                    if (stored.artifacts.any { it.path == "run.json" }) {
                        parseObject(Files.readAllBytes(requireOwnedFile(run)), "run metadata")
                    } else {
                        null
                    },
                result = parseObject(Files.readAllBytes(requireOwnedFile(stored.path.resolve(RESULT_FILE))), "analysis result"),
                identity = parseObject(Files.readAllBytes(requireOwnedFile(stored.path.resolve(IDENTITY_FILE))), "analysis identity"),
            )
        }

    // History displays saved facts: hash-verify only the (bounded) documents consumed; raw inputs and other artifacts are never read.
    // ponytail: bounded directory scan; add an index only if histories routinely exceed these limits.
    fun readComparisonHistory(
        limit: Int = 1000,
        byteLimit: Int = 16 * 1024 * 1024,
    ): ComparisonHistory =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            require(limit in 1..1000 && byteLimit in 1..16 * 1024 * 1024)
            requireOwnedDirectory(dataDirectory.runs)
            val entries = mutableListOf<ComparisonHistoryEntry>()
            var remaining = byteLimit
            var inspected = 0
            var truncated = false

            fun bounded(
                path: Path,
                maximum: Int,
            ): ByteArray? {
                val size = Files.size(requireOwnedFile(path))
                if (size > maximum || size > remaining) {
                    truncated = true
                    return null
                }
                val bytes = Files.newInputStream(path).use { it.readNBytes(minOf(maximum, remaining) + 1) }
                if (bytes.size > maximum || bytes.size > remaining) {
                    truncated = true
                    return null
                }
                remaining -= bytes.size
                return bytes
            }
            Files.newDirectoryStream(dataDirectory.runs).use { runs ->
                for (run in runs) {
                    if (++inspected > 4096) {
                        truncated = true
                        break
                    }
                    val runId = run.fileName.toString()
                    if (!RUN_ID.matches(runId)) continue
                    requireOwnedDirectory(run)
                    val analyses = run.resolve("analyses")
                    if (!Files.exists(analyses, LinkOption.NOFOLLOW_LINKS)) continue
                    requireOwnedDirectory(analyses)
                    Files.newDirectoryStream(analyses).use { saved ->
                        for (analysis in saved) {
                            if (++inspected > 4096 || entries.size == limit) {
                                truncated = true
                                break
                            }
                            val analysisId = analysis.fileName.toString()
                            if (!SHA256.matches(analysisId)) continue
                            requireOwnedDirectory(analysis)
                            val manifestBytes = bounded(analysis.resolve("manifest.json"), 256 * 1024) ?: break
                            val manifest = parseObject(manifestBytes, "history manifest")
                            if (manifest.keys != MANIFEST_FIELDS ||
                                manifest.string("schema_version") != "analysis-manifest.v1"
                            ) {
                                corrupt("history manifest differs")
                            }
                            val artifacts =
                                (manifest["artifacts"] as? JsonArray ?: corrupt("history artifacts missing")).map { value ->
                                    val item = value as? JsonObject ?: corrupt("history artifact invalid")
                                    if (item.keys != ARTIFACT_FIELDS) corrupt("history artifact fields differ")
                                    StoredArtifact(item.string("path"), item.long("size_bytes"), item.string("sha256"))
                                }
                            if (artifacts.map { it.path }.distinct().size != artifacts.size ||
                                !manifestBytes.contentEquals(analysisManifest(artifacts.sortedBy { it.path }))
                            ) {
                                corrupt("history manifest is not canonical")
                            }
                            val documents = mutableMapOf<String, JsonObject>()
                            for (name in listOf(IDENTITY_FILE, RESULT_FILE, "run.json")) {
                                val artifact = artifacts.singleOrNull { it.path == name }
                                if (artifact == null) {
                                    if (name != "run.json") corrupt("history document missing")
                                    continue
                                }
                                val bytes = bounded(analysis.resolve(name), 8 * 1024 * 1024) ?: break
                                if (bytes.size.toLong() != artifact.sizeBytes ||
                                    sha256Hex(bytes) != artifact.sha256
                                ) {
                                    corrupt("history document differs")
                                }
                                if (name == IDENTITY_FILE && sha256Hex(bytes) != analysisId) corrupt("history identity differs")
                                documents[name] = parseObject(bytes, "history document")
                            }
                            if (truncated) break
                            val identity = documents.getValue(IDENTITY_FILE)
                            val result = documents.getValue(RESULT_FILE)
                            if (identity.string("run_id") != runId ||
                                result.string("run_id") != runId
                            ) {
                                corrupt("history run binding differs")
                            }
                            entries +=
                                ComparisonHistoryEntry(runId, analysisId, ComparisonDocuments(documents["run.json"], result, identity))
                        }
                    }
                    if (truncated) break
                }
            }
            ComparisonHistory(entries, truncated)
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

    private fun readBaselineUnlocked(): JsonObject? {
        val target = dataDirectory.root.resolve(BASELINE_FILE)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        return readSelectionFile(requireBaselineFile(target))
    }

    private class SlotState(
        val files: List<BaselineSlot>,
        val legacy: BaselineSlot?,
    ) {
        // A legacy file loses to the slot file of its own key and is then not a slot at all.
        val effective: List<BaselineSlot> =
            if (legacy == null || files.any { it.key() == legacy.key() }) files else files + legacy
    }

    private fun slotStateUnlocked(): SlotState {
        val files =
            slotFilesUnlocked().map { (name, selection) ->
                slotOf(selection, legacy = false).also {
                    if ("${it.key()}.json" != name) corruptBaseline("slot file name differs from its key")
                }
            }
        return SlotState(files, readBaselineUnlocked()?.let { slotOf(it, legacy = true) })
    }

    private fun slotOf(
        selection: JsonObject,
        legacy: Boolean,
    ): BaselineSlot {
        val reference = selection.baselineReference()
        val identity =
            readIdentityUnlocked(reference.string("run_id"), reference.string("analysis_id"))
                ?: corruptBaseline("baseline analysis is missing")
        return BaselineSlot((selection["series"] as JsonPrimitive).content, identityArm(identity), selection, legacy)
    }

    // The arm is `resource_arm` of the identity; an absent field and JSON null are the same arm (ADR 0014, ADR 0019, section 7).
    private fun identityArm(identity: JsonObject): String? =
        when (val arm = identity["resource_arm"]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (arm.isString) arm.content else corruptBaseline("resource_arm must be a string")
            else -> corruptBaseline("resource_arm must be a string")
        }

    private fun slotFilesUnlocked(): List<Pair<String, JsonObject>> {
        val directory = dataDirectory.root.resolve(BASELINE_SLOTS_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        requireBaselineSlotsDirectory(directory)
        // One more than the limit: the old writer may leave a legacy file of another key beside a full directory.
        val entries = mutableListOf<Path>()
        Files.newDirectoryStream(directory).use { stream ->
            for (entry in stream) {
                if (entries.size == MAX_BASELINE_SLOTS + 1) corruptBaseline("more than ${MAX_BASELINE_SLOTS + 1} slot files")
                entries.add(entry)
            }
        }
        return entries.map { it.fileName.toString() to readSelectionFile(requireBaselineSlotFile(it)) }
    }

    private fun ensureBaselineSlotsDirectory(): Path {
        val target = dataDirectory.root.resolve(BASELINE_SLOTS_DIRECTORY)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return requireBaselineSlotsDirectory(target)
        Files.createDirectory(target)
        forceDirectory(dataDirectory.root)
        return target
    }

    /** Small and checked: the file is at most 8 MiB, its SHA-256 is the `analysis_id`, its `run_id` is the run's. */
    private fun readIdentityUnlocked(
        runId: String,
        analysisId: String,
    ): JsonObject? {
        requireRunId(runId)
        requireAnalysisId(analysisId)
        requireOwnedDirectory(dataDirectory.runs)
        val run = dataDirectory.runs.resolve(runId)
        val analyses = run.resolve("analyses")
        val analysis = analyses.resolve(analysisId)
        for (directory in listOf(run, analyses, analysis)) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
            requireOwnedDirectory(directory)
        }
        val path = requireOwnedFile(analysis.resolve(IDENTITY_FILE))
        if (Files.size(path) > MAX_VERIFIED_DOCUMENT_BYTES) corrupt("analysis identity exceeds $MAX_VERIFIED_DOCUMENT_BYTES bytes")
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_VERIFIED_DOCUMENT_BYTES + 1) }
        if (bytes.size > MAX_VERIFIED_DOCUMENT_BYTES || sha256Hex(bytes) != analysisId) corrupt("analysis identity differs")
        val identity = parseObject(bytes, "analysis identity")
        if (identity.string("run_id") != runId) corrupt("analysis run identity differs")
        return identity
    }

    /**
     * Reads the condition directory now and returns the deletion of the records whose baseline reference is in [references].
     * The directory is read in full but bounded, and a failure of the read happens here, before anything is deleted; a
     * file that cannot be read as a record of its own key stays where it is. An emptied directory is removed.
     */
    private fun baselineConditionDeletionUnlocked(references: Set<JsonObject>): () -> Unit {
        if (references.isEmpty()) return {}
        val directoryTarget = dataDirectory.root.resolve(BASELINE_CONDITIONS_DIRECTORY)
        if (!Files.exists(directoryTarget, LinkOption.NOFOLLOW_LINKS)) return {}
        val directory = requireBaselineConditionsDirectory(directoryTarget)
        val entries = mutableListOf<Path>()
        Files.newDirectoryStream(directory).use { stream ->
            for (entry in stream) {
                if (entries.size == MAX_BASELINE_CONDITION_FILES) corruptBaseline("too many condition records")
                entries.add(requireBaselineConditionFile(entry))
            }
        }
        val doomed =
            entries.filter { path ->
                try {
                    val record = readConditionRecord(path)
                    baselineConditionPath(directory, baselineConditionBinding(record)) == path &&
                        (record["baseline"] as JsonObject) in references
                } catch (_: IllegalStateException) {
                    false
                }
            }
        return {
            doomed.forEach(Files::delete)
            if (doomed.isNotEmpty()) {
                forceDirectory(directory)
                if (doomed.size == entries.size) {
                    Files.delete(directory)
                    forceDirectory(dataDirectory.root)
                }
            }
        }
    }

    private fun readSelectionFile(path: Path): JsonObject {
        if (Files.size(path) > MAX_BASELINE_BYTES) corruptBaseline("selection exceeds 32 KiB")
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BASELINE_BYTES + 1) }
        if (bytes.size > MAX_BASELINE_BYTES) corruptBaseline("selection exceeds 32 KiB")
        val selection =
            try {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } catch (_: SerializationException) {
                corruptBaseline("selection JSON is invalid")
            } catch (_: IllegalArgumentException) {
                corruptBaseline("selection JSON is invalid")
            }
        val validated =
            try {
                validateBaselineSelection(selection)
            } catch (_: RuntimeException) {
                corruptBaseline("selection contract is invalid")
            }
        if (!bytes.contentEquals(canonicalJson(validated))) corruptBaseline("selection is not canonical")
        return validated
    }

    private fun readBaselineConditionUnlocked(binding: JsonObject): JsonObject? {
        val directoryTarget = dataDirectory.root.resolve(BASELINE_CONDITIONS_DIRECTORY)
        if (!Files.exists(directoryTarget, LinkOption.NOFOLLOW_LINKS)) return null
        val directory = requireBaselineConditionsDirectory(directoryTarget)
        val target = baselineConditionPath(directory, binding)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val validated = readConditionRecord(requireBaselineConditionFile(target))
        if (baselineConditionBinding(validated) != binding) corruptBaseline("condition record binding differs from its key")
        return validated
    }

    private fun readConditionRecord(path: Path): JsonObject {
        if (Files.size(path) > MAX_BASELINE_CONDITION_BYTES) corruptBaseline("condition record exceeds 4 KiB")
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BASELINE_CONDITION_BYTES + 1) }
        if (bytes.size > MAX_BASELINE_CONDITION_BYTES) corruptBaseline("condition record exceeds 4 KiB")
        val condition =
            try {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } catch (_: SerializationException) {
                corruptBaseline("condition record JSON is invalid")
            } catch (_: IllegalArgumentException) {
                corruptBaseline("condition record JSON is invalid")
            }
        val validated =
            try {
                validateBaselineCondition(condition)
            } catch (_: RuntimeException) {
                corruptBaseline("condition record contract is invalid")
            }
        if (!bytes.contentEquals(canonicalJson(validated))) corruptBaseline("condition record is not canonical")
        return validated
    }

    private fun readRunPeriodUnlocked(accepted: AcceptedInput): JsonObject? {
        val target = dataDirectory.runs.resolve(accepted.runId).resolve(RUN_PERIOD_FILE)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val path = requireRunPeriodFile(target)
        if (Files.size(path) > MAX_RUN_PERIOD_BYTES) corruptRunPeriod("period exceeds 4 KiB")
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_RUN_PERIOD_BYTES + 1) }
        if (bytes.size > MAX_RUN_PERIOD_BYTES) corruptRunPeriod("period exceeds 4 KiB")
        val document =
            try {
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } catch (_: SerializationException) {
                corruptRunPeriod("period JSON is invalid")
            } catch (_: IllegalArgumentException) {
                corruptRunPeriod("period JSON is invalid")
            }
        val validated =
            try {
                validateRunPeriod(document)
            } catch (_: RuntimeException) {
                corruptRunPeriod("period contract is invalid")
            }
        if (!bytes.contentEquals(canonicalJson(validated))) corruptRunPeriod("period is not canonical")
        if (validated.string("load_input_sha256") != accepted.sha256) corruptRunPeriod("period refers to different input bytes")
        return validated
    }

    private fun requireInputUnlocked(runId: String): AcceptedInput {
        requireRunId(runId)
        requireOwnedDirectory(dataDirectory.runs)
        val run = dataDirectory.runs.resolve(runId)
        if (!Files.exists(run, LinkOption.NOFOLLOW_LINKS)) throw NoSuchElementException("RUN_NOT_FOUND")
        requireOwnedDirectory(run)
        val inputs = requireOwnedDirectory(run.resolve("inputs"))
        val source = requireOwnedFile(inputs.resolve("source.bin"))
        val metadataPath = requireOwnedFile(run.resolve("source.json"))
        val metadataBytes = Files.readAllBytes(metadataPath)
        val metadata = parseObject(metadataBytes, "source metadata")
        if (metadata.keys != SOURCE_FIELDS) corrupt("source metadata fields differ")

        val storedRunId = metadata.string("run_id")
        val wireType = metadata.string("source_type")
        val sourceType = SourceType.entries.find { it.wireName == wireType } ?: corrupt("unknown source type")
        val sha256 = metadata.string("sha256")
        val sizeBytes = metadata.long("size_bytes")
        val originalFilename = metadata.string("original_filename")
        if (storedRunId != runId || runId != "${sourceType.wireName}-$sha256") corrupt("run identity differs")
        if (!SHA256.matches(sha256) || sizeBytes < 1 || !isSafeFilename(originalFilename)) corrupt("source metadata is invalid")
        // Trusted local contour: content is hashed once at accept time (run_id); reads check size only.
        if (Files.size(source) != sizeBytes) corrupt("source size differs")

        val accepted = AcceptedInput(runId, sourceType, sha256, sizeBytes, originalFilename, source)
        if (!metadataBytes.contentEquals(sourceMetadata(accepted))) corrupt("source metadata is not canonical")
        return accepted
    }

    private fun readAnalysisUnlocked(
        runId: String,
        analysisId: String,
    ): StoredAnalysis? {
        requireRunId(runId)
        requireAnalysisId(analysisId)
        requireInputUnlocked(runId)
        val run = dataDirectory.runs.resolve(runId)
        val analyses = run.resolve("analyses")
        if (!Files.exists(analyses, LinkOption.NOFOLLOW_LINKS)) return null
        requireOwnedDirectory(analyses)
        val analysis = analyses.resolve(analysisId)
        if (!Files.exists(analysis, LinkOption.NOFOLLOW_LINKS)) return null
        requireOwnedDirectory(analysis)
        val manifestPath = requireOwnedFile(analysis.resolve("manifest.json"))
        val manifestBytes = Files.readAllBytes(manifestPath)
        val manifest = parseObject(manifestBytes, "analysis manifest")
        if (manifest.keys != MANIFEST_FIELDS || manifest.string("schema_version") != "analysis-manifest.v1") {
            corrupt("analysis manifest fields differ")
        }
        val entries = manifest["artifacts"] as? JsonArray ?: corrupt("analysis artifacts must be an array")
        val artifacts =
            entries.map { value ->
                val entry = value as? JsonObject ?: corrupt("analysis artifact must be an object")
                if (entry.keys != ARTIFACT_FIELDS) corrupt("analysis artifact fields differ")
                StoredArtifact(entry.string("path"), entry.long("size_bytes"), entry.string("sha256"))
            }
        if (artifacts.map { it.path }.toSet().size != artifacts.size) corrupt("duplicate analysis artifact")
        val sortedArtifacts = artifacts.sortedBy { it.path }
        if (!manifestBytes.contentEquals(analysisManifest(sortedArtifacts))) corrupt("analysis manifest is not canonical")

        val identityPath = requireOwnedFile(analysis.resolve("identity.json"))
        if (sha256(identityPath) != analysisId) corrupt("analysis identity differs")
        if (parseObject(Files.readAllBytes(identityPath), "analysis identity").string("run_id") != runId) {
            corrupt("analysis run identity differs")
        }
        // Artifact bytes are hashed when written (manifest); reads compare paths and sizes only.
        val actual = inspectPublishedArtifacts(analysis)
        if (actual != sortedArtifacts.map { it.path to it.sizeBytes }) corrupt("analysis artifacts differ")
        return StoredAnalysis(analysis, artifacts)
    }

    private fun inspectStagedArtifacts(staging: Path): List<StoredArtifact> {
        requireOwnedDirectory(staging)
        val artifacts = mutableListOf<StoredArtifact>()
        Files.walk(staging).use { paths ->
            paths.forEach { path ->
                if (path == staging) return@forEach
                if (Files.isSymbolicLink(path)) corrupt("analysis contains a symbolic link")
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    forceDirectory(path)
                    return@forEach
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) corrupt("analysis contains a special file")
                val relative = staging.relativize(path).invariantPath()
                if (relative == "manifest.json") corrupt("analysis writer must not create manifest.json")
                forceFile(path)
                artifacts += StoredArtifact(relative, Files.size(path), sha256(path))
            }
        }
        return artifacts.sortedBy { it.path }
    }

    private fun inspectPublishedArtifacts(analysis: Path): List<Pair<String, Long>> {
        val artifacts = mutableListOf<Pair<String, Long>>()
        Files.walk(analysis).use { paths ->
            paths.forEach { path ->
                if (path == analysis) return@forEach
                if (Files.isSymbolicLink(path)) corrupt("analysis contains a symbolic link")
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) corrupt("analysis contains a special file")
                val relative = analysis.relativize(path).invariantPath()
                if (relative != "manifest.json") {
                    artifacts += relative to Files.size(path)
                }
            }
        }
        return artifacts.sortedBy { it.first }
    }
}

private fun copyInput(
    source: InputStream,
    target: Path,
    maxBytes: Long,
): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val remaining = maxBytes - total
            val request = if (remaining >= buffer.size) buffer.size else (remaining + 1).toInt()
            val count = source.read(buffer, 0, request)
            if (count == -1) break
            if (count > remaining) throw IllegalArgumentException("RESOURCE_LIMIT_EXCEEDED")
            channel.writeFully(ByteBuffer.wrap(buffer, 0, count))
            digest.update(buffer, 0, count)
            total += count
        }
        channel.force(true)
    }
    return total to HexFormat.of().formatHex(digest.digest())
}

private fun sourceMetadata(input: AcceptedInput): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("original_filename", input.originalFilename)
            put("run_id", input.runId)
            put("sha256", input.sha256)
            put("size_bytes", input.sizeBytes)
            put("source_type", input.sourceType.wireName)
        },
    )

private fun analysisManifest(artifacts: List<StoredArtifact>): ByteArray =
    canonicalJson(
        buildJsonObject {
            put(
                "artifacts",
                buildJsonArray {
                    artifacts.forEach { artifact ->
                        add(
                            buildJsonObject {
                                put("path", artifact.path)
                                put("sha256", artifact.sha256)
                                put("size_bytes", artifact.sizeBytes)
                            },
                        )
                    }
                },
            )
            put("schema_version", "analysis-manifest.v1")
        },
    )

private fun writeForced(
    path: Path,
    bytes: ByteArray,
) {
    FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        channel.writeFully(ByteBuffer.wrap(bytes))
        channel.force(true)
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
}

private fun forceDirectory(path: Path) {
    try {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is optional where the JDK does not expose it.
    } catch (error: IOException) {
        if (!IS_WINDOWS) throw error
    }
}

private fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}

private fun parseObject(
    bytes: ByteArray,
    description: String,
): JsonObject =
    try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (_: SerializationException) {
        corrupt("invalid $description")
    } catch (_: IllegalArgumentException) {
        corrupt("invalid $description")
    }

private fun JsonObject.string(name: String): String {
    val value = this[name] as? JsonPrimitive ?: corrupt("$name must be a string")
    if (!value.isString) corrupt("$name must be a string")
    return value.content
}

private fun JsonObject.optionalString(name: String): String? = if (name in this) string(name) else null

private fun JsonObject.long(name: String): Long {
    val value = this[name] as? JsonPrimitive ?: corrupt("$name must be an integer")
    if (value.isString) corrupt("$name must be an integer")
    return value.longOrNull ?: corrupt("$name must be an integer")
}

private fun AcceptedInput.toSummary() = RunSummary(runId, sourceType, sha256, sizeBytes, originalFilename)

private fun ensureOwnedDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path)) corrupt("symbolic link at $path")
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) corrupt("expected directory at $path")
    } else {
        Files.createDirectory(path)
    }
    if (Files.isSymbolicLink(path)) corrupt("symbolic link at $path")
    return path
}

private fun requireOwnedDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) corrupt("unsafe directory at $path")
    return path
}

private fun StoredAnalysis.verifiedBytes(
    name: String,
    maximum: Int,
    tooLarge: Boolean,
): ByteArray {
    val artifact = artifacts.singleOrNull { it.path == name } ?: corrupt("analysis artifact $name is missing")
    if (artifact.sizeBytes > maximum) {
        if (tooLarge) throw IllegalArgumentException("RESULT_TOO_LARGE")
        corrupt("analysis artifact $name exceeds $maximum bytes")
    }
    val bytes = Files.newInputStream(requireOwnedFile(path.resolve(name))).use { it.readNBytes(artifact.sizeBytes.toInt() + 1) }
    if (bytes.size.toLong() != artifact.sizeBytes || sha256Hex(bytes) != artifact.sha256) {
        corrupt("analysis artifact $name differs from its manifest")
    }
    return bytes
}

private fun requireOwnedFile(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) corrupt("unsafe file at $path")
    return path
}

private fun requireBaselineFile(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptBaseline("unsafe file at $path")
    }
    return path
}

private fun requireBaselineSlotsDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptBaseline("unsafe slots directory at $path")
    }
    return path
}

private fun requireBaselineSlotFile(path: Path): Path {
    if (!BASELINE_SLOT_FILE.matches(path.fileName.toString()) ||
        Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    ) {
        corruptBaseline("unsafe slot file at $path")
    }
    return path
}

private fun JsonObject.baselineReference(): JsonObject = this["reference"] as JsonObject

/** The key of a slot: SHA-256 of the canonical `{arm, series}`; `arm` is a string or null (ADR 0019, section 7). */
private fun baselineSlotKey(
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

private fun requireBaselineConditionsDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptBaseline("unsafe conditions directory at $path")
    }
    return path
}

private fun requireBaselineConditionFile(path: Path): Path {
    if (!BASELINE_CONDITION_FILE.matches(path.fileName.toString()) ||
        Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    ) {
        corruptBaseline("unsafe condition record at $path")
    }
    return path
}

private fun requireRunPeriodFile(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptRunPeriod("unsafe file at $path")
    }
    return path
}

private fun baselineConditionPath(
    directory: Path,
    binding: JsonObject,
): Path = directory.resolve("${sha256Hex(canonicalJson(binding))}.json")

private fun requireRunId(runId: String) {
    require(RUN_ID.matches(runId)) { "INVALID_RUN_ID" }
}

private fun requireAnalysisId(analysisId: String) {
    require(SHA256.matches(analysisId)) { "INVALID_ANALYSIS_ID" }
}

private fun isSafeFilename(name: String): Boolean {
    if (name.isEmpty() || name.encodeToByteArray().size > 255 || name == "." || name == "..") return false
    if (name.any { it < ' ' || it in "<>:\"/\\|?*" }) return false
    val stem = name.trimEnd(' ', '.').substringBefore('.').uppercase()
    return stem !in RESERVED_NAMES && !stem.matches(Regex("(?:COM|LPT)[1-9]"))
}

private fun Path.invariantPath(): String =
    joinToString("/") { it.toString() }.also { relative ->
        if (relative.isEmpty() || relative.contains('\\')) corrupt("unsafe artifact path")
        val path = Path.of(relative)
        if (path.isAbsolute || path.normalize() != path || path.any { it.toString() == ".." }) corrupt("unsafe artifact path")
    }

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            digest.update(buffer, 0, count)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

private fun releaseAnalysisIds(record: JsonObject): List<String> =
    (record["analyses"] as JsonArray).map { ((it as JsonObject)["analysis_id"] as JsonPrimitive).content }

private fun randomReleaseSuffix(): String = HexFormat.of().formatHex(ByteArray(4).also(RELEASE_RANDOM::nextBytes))

private fun requireReleaseId(id: String) {
    require(RELEASE_ID.matches(id)) { "INVALID_RELEASE_ID" }
}

private fun requireReleasesDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        corruptReleaseRegistry("unsafe releases directory")
    }
    return path
}

private fun corrupt(message: String): Nothing = throw IllegalStateException("CORRUPT_RUN_BUNDLE: $message")

private fun corruptBaseline(message: String): Nothing = throw IllegalStateException("CORRUPT_BASELINE: $message")

private fun corruptRunPeriod(message: String): Nothing = throw IllegalStateException("CORRUPT_RUN_PERIOD: $message")

private fun corruptRelease(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE: $message")

private fun corruptReleaseRegistry(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE_REGISTRY: $message")

private const val RELEASES_DIRECTORY = "releases"
private const val RELEASE_ID_ATTEMPTS = 8
private const val RELEASE_CORRUPT_NAMES = 20
internal const val MAX_RELEASES = 1_000
private val RELEASE_FILE = Regex("[0-9]{15}-[0-9a-f]{8}\\.json")
private val RELEASE_RANDOM = java.security.SecureRandom()
private const val BASELINE_FILE = "baseline.json"
private const val BASELINE_SLOTS_DIRECTORY = "baselines"
internal const val MAX_BASELINE_SLOTS = 64
internal const val MAX_BASELINE_CONDITION_FILES = 4_096
private const val BASELINE_CONDITIONS_DIRECTORY = "baseline-conditions"
private const val RUN_PERIOD_FILE = "run-period.json"
private const val RESULT_FILE = "analysis-result.json"
private const val IDENTITY_FILE = "identity.json"
private const val POD_VIEW_FILE = "pod-view.json"
private const val POLICY_FILE = "policy.json"
private const val MAX_POLICY_BYTES = 1_048_576
internal const val MAX_VERIFIED_RESULT_BYTES = 64 * 1024 * 1024
private const val MAX_VERIFIED_DOCUMENT_BYTES = 8 * 1024 * 1024
private const val MAX_BASELINE_BYTES = 32 * 1024
private const val MAX_BASELINE_CONDITION_BYTES = 4 * 1024
private const val MAX_RUN_PERIOD_BYTES = 4 * 1024
private val BASELINE_CONDITION_FILE = Regex("[0-9a-f]{64}\\.json")
private val BASELINE_SLOT_FILE = Regex("[0-9a-f]{64}\\.json")
private val SHA256 = Regex("[0-9a-f]{64}")
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")
private val SOURCE_FIELDS = setOf("original_filename", "run_id", "sha256", "size_bytes", "source_type")
private val MANIFEST_FIELDS = setOf("artifacts", "schema_version")
private val ARTIFACT_FIELDS = setOf("path", "sha256", "size_bytes")
private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL")
private val IS_WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

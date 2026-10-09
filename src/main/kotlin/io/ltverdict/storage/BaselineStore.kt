package io.ltverdict.storage

import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionBinding
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateBaselineCondition
import io.ltverdict.core.validateBaselineSelection
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** One active baseline: its scope is the pair (series, arm); [legacy] marks the file `baseline.json` (ADR 0019, section 7). */
internal data class BaselineSlot(
    val series: String,
    val arm: String?,
    val selection: JsonObject,
    val legacy: Boolean,
) {
    fun key(): String = baselineSlotKey(series, arm)
}

internal class BaselineStore(
    private val dataDirectory: DataDirectory,
    private val analyses: AnalysisStore,
) {
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
                analyses.readIdentityUnlocked(reference.string("run_id"), reference.string("analysis_id"))
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
            analyses.readIdentityUnlocked(reference.string("run_id"), reference.string("analysis_id"))
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

private fun baselineConditionPath(
    directory: Path,
    binding: JsonObject,
): Path = directory.resolve("${sha256Hex(canonicalJson(binding))}.json")

private fun corruptBaseline(message: String): Nothing = throw IllegalStateException("CORRUPT_BASELINE: $message")

private const val BASELINE_FILE = "baseline.json"

private const val BASELINE_SLOTS_DIRECTORY = "baselines"

internal const val MAX_BASELINE_SLOTS = 64

internal const val MAX_BASELINE_CONDITION_FILES = 4_096

private const val BASELINE_CONDITIONS_DIRECTORY = "baseline-conditions"

private const val MAX_BASELINE_BYTES = 32 * 1024

private const val MAX_BASELINE_CONDITION_BYTES = 4 * 1024

private val BASELINE_CONDITION_FILE = Regex("[0-9a-f]{64}\\.json")

private val BASELINE_SLOT_FILE = Regex("[0-9a-f]{64}\\.json")

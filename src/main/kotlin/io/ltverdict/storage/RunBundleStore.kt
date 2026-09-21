package io.ltverdict.storage

import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionBinding
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateBaselineCondition
import io.ltverdict.core.validateBaselineSelection
import io.ltverdict.ingest.SourceType
import io.ltverdict.ingest.detectSource
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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

internal class RunBundleStore(
    private val dataDirectory: DataDirectory,
) {
    fun acceptInput(
        source: InputStream,
        originalFilename: String,
        maxBytes: Long = 4_294_967_296L,
    ): AcceptedInput =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireOwnedDirectory(dataDirectory.staging)
            requireOwnedDirectory(dataDirectory.runs)
            require(maxBytes >= 0) { "INVALID_SIZE_LIMIT" }
            require(isSafeFilename(originalFilename)) { "UNSAFE_FILENAME" }
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
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

                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    val existing = requireInputUnlocked(runId)
                    if (Files.mismatch(stagedSource, existing.path) != -1L) corrupt("existing input bytes differ")
                    return@synchronized existing
                }

                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(dataDirectory.runs)
                requireInputUnlocked(runId)
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
    ): AnalysisPage =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            require(limit in 1..100) { "INVALID_PAGE_LIMIT" }
            if (afterAnalysisId != null) requireAnalysisId(afterAnalysisId)
            requireInputUnlocked(runId)
            val analyses = dataDirectory.runs.resolve(runId).resolve("analyses")
            if (!Files.exists(analyses, LinkOption.NOFOLLOW_LINKS)) return@synchronized AnalysisPage(emptyList(), null)
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
                    val stored = readAnalysisUnlocked(runId, analysisId) ?: corrupt("listed analysis disappeared")
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
                    AnalysisSummary(
                        analysisId,
                        identity.string("policy_sha256"),
                        result.string("policy_verdict"),
                        result.string("run_validity"),
                    )
                }
            AnalysisPage(returned, if (selected.size > limit) returned.last().analysisId else null)
        }

    fun writeAnalysisAtomically(
        runId: String,
        analysisId: String,
        writeStagingDirectory: (Path) -> Unit,
    ): Path =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireInputUnlocked(runId)
            requireAnalysisId(analysisId)
            requireOwnedDirectory(dataDirectory.staging)
            val analyses = ensureOwnedDirectory(dataDirectory.runs.resolve(runId).resolve("analyses"))
            val target = analyses.resolve(analysisId)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return@synchronized readAnalysisUnlocked(runId, analysisId)?.path ?: corrupt("analysis is incomplete")
            }

            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                writeStagingDirectory(staging)
                val artifacts = inspectStagedArtifacts(staging)
                writeForced(staging.resolve("manifest.json"), analysisManifest(artifacts))
                forceDirectory(staging)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) corrupt("analysis target appeared during publish")
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(analyses)
                target
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
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireBaselineConditionFile(target)
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

    fun clearBaseline() {
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val target = dataDirectory.root.resolve(BASELINE_FILE)
            val baseline = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) requireBaselineFile(target) else null
            val conditionsTarget = dataDirectory.root.resolve(BASELINE_CONDITIONS_DIRECTORY)
            val conditionsDirectory =
                if (Files.exists(conditionsTarget, LinkOption.NOFOLLOW_LINKS)) {
                    requireBaselineConditionsDirectory(conditionsTarget)
                } else {
                    null
                }
            val conditionFiles =
                conditionsDirectory
                    ?.let { directory ->
                        Files.list(directory).use { paths -> paths.toList().map(::requireBaselineConditionFile) }
                    }.orEmpty()
            if (baseline == null && conditionsDirectory == null) return
            conditionFiles.forEach(Files::delete)
            conditionsDirectory?.let {
                forceDirectory(it)
                Files.delete(it)
            }
            baseline?.let(Files::delete)
            forceDirectory(dataDirectory.root)
        }
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

    // History displays saved facts: verify the documents consumed, not every raw input again.
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

    private fun readBaselineUnlocked(): JsonObject? {
        val target = dataDirectory.root.resolve(BASELINE_FILE)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        val path = requireBaselineFile(target)
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
        val path = requireBaselineConditionFile(target)
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
        if (baselineConditionBinding(validated) != binding) corruptBaseline("condition record binding differs from its key")
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
        if (Files.size(source) != sizeBytes || sha256(source) != sha256) corrupt("source bytes differ")

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
        val actual = inspectPublishedArtifacts(analysis)
        if (actual != sortedArtifacts) corrupt("analysis artifacts differ")
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

    private fun inspectPublishedArtifacts(analysis: Path): List<StoredArtifact> {
        val artifacts = mutableListOf<StoredArtifact>()
        Files.walk(analysis).use { paths ->
            paths.forEach { path ->
                if (path == analysis) return@forEach
                if (Files.isSymbolicLink(path)) corrupt("analysis contains a symbolic link")
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) corrupt("analysis contains a special file")
                val relative = analysis.relativize(path).invariantPath()
                if (relative != "manifest.json") {
                    artifacts += StoredArtifact(relative, Files.size(path), sha256(path))
                }
            }
        }
        return artifacts.sortedBy { it.path }
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

private fun corrupt(message: String): Nothing = throw IllegalStateException("CORRUPT_RUN_BUNDLE: $message")

private fun corruptBaseline(message: String): Nothing = throw IllegalStateException("CORRUPT_BASELINE: $message")

private const val BASELINE_FILE = "baseline.json"
private const val BASELINE_CONDITIONS_DIRECTORY = "baseline-conditions"
private const val RESULT_FILE = "analysis-result.json"
private const val IDENTITY_FILE = "identity.json"
private const val MAX_BASELINE_BYTES = 32 * 1024
private const val MAX_BASELINE_CONDITION_BYTES = 4 * 1024
private val BASELINE_CONDITION_FILE = Regex("[0-9a-f]{64}\\.json")
private val SHA256 = Regex("[0-9a-f]{64}")
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")
private val SOURCE_FIELDS = setOf("original_filename", "run_id", "sha256", "size_bytes", "source_type")
private val MANIFEST_FIELDS = setOf("artifacts", "schema_version")
private val ARTIFACT_FIELDS = setOf("path", "sha256", "size_bytes")
private val RESERVED_NAMES = setOf("CON", "PRN", "AUX", "NUL")
private val IS_WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

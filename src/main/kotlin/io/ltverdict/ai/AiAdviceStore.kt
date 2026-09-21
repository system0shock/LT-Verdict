package io.ltverdict.ai

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

internal data class StoredAdvice(
    val path: Path,
    val document: JsonObject,
)

internal class AiAdviceStore(
    private val dataDirectory: DataDirectory,
    private val runBundles: RunBundleStore,
) {
    fun write(
        runId: String,
        analysisId: String,
        analysisManifestSha256: String,
        document: JsonObject,
    ): StoredAdvice =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val analysis = runBundles.readAnalysis(runId, analysisId) ?: throw NoSuchElementException("ANALYSIS_NOT_FOUND")
            val actualAnalysisManifestSha256 = sha256Hex(Files.readAllBytes(analysis.path.resolve("manifest.json")))
            require(actualAnalysisManifestSha256 == analysisManifestSha256) { "ANALYSIS_BINDING_MISMATCH" }
            validateAgainstAnalysis(document, runId, analysisId, actualAnalysisManifestSha256)

            val root = ensureOwnedDirectory(dataDirectory.runs.resolve(runId).resolve(ADVICE_DIRECTORY))
            val target = root.resolve(analysisId)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return@synchronized readUnlocked(runId, analysisId, actualAnalysisManifestSha256)
                    ?: corrupt("advice disappeared")
            }

            requireOwnedDirectory(dataDirectory.staging)
            val staging = dataDirectory.staging.resolve(UUID.randomUUID().toString())
            Files.createDirectory(staging)
            try {
                val bytes = canonicalJson(document)
                if (bytes.size > MAX_STORED_ADVICE_BYTES) throw AdviceValidationException(AdviceFailure.OUTPUT_LIMIT)
                writeForced(staging.resolve(ADVICE_FILE), bytes)
                writeForced(
                    staging.resolve(MANIFEST_FILE),
                    adviceManifest(runId, analysisId, actualAnalysisManifestSha256, bytes),
                )
                forceDirectory(staging)
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) corrupt("advice target appeared during publish")
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                forceDirectory(root)
            } finally {
                DataDirectory.deleteTree(staging)
            }
            readUnlocked(runId, analysisId, actualAnalysisManifestSha256) ?: corrupt("published advice disappeared")
        }

    fun read(
        runId: String,
        analysisId: String,
    ): StoredAdvice? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val analysis = runBundles.readAnalysis(runId, analysisId) ?: throw NoSuchElementException("ANALYSIS_NOT_FOUND")
            val manifestSha256 = sha256Hex(Files.readAllBytes(analysis.path.resolve("manifest.json")))
            readUnlocked(runId, analysisId, manifestSha256)
        }

    private fun readUnlocked(
        runId: String,
        analysisId: String,
        analysisManifestSha256: String,
    ): StoredAdvice? {
        val root = dataDirectory.runs.resolve(runId).resolve(ADVICE_DIRECTORY)
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return null
        requireOwnedDirectory(root)
        val target = root.resolve(analysisId)
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
        requireOwnedDirectory(target)
        val names = Files.list(target).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
        if (names != setOf(ADVICE_FILE, MANIFEST_FILE)) corrupt("advice artifacts differ")

        val documentPath = requireOwnedFile(target.resolve(ADVICE_FILE))
        val manifestPath = requireOwnedFile(target.resolve(MANIFEST_FILE))
        if (Files.size(documentPath) > MAX_STORED_ADVICE_BYTES) corrupt("advice exceeds limit")
        if (Files.size(manifestPath) > MAX_ADVICE_MANIFEST_BYTES) corrupt("advice manifest exceeds limit")
        val bytes = Files.readAllBytes(documentPath)
        val expectedManifest = adviceManifest(runId, analysisId, analysisManifestSha256, bytes)
        if (!Files.readAllBytes(manifestPath).contentEquals(expectedManifest)) corrupt("advice manifest differs")
        val document = parseObject(bytes, "advice")
        validateAgainstAnalysis(document, runId, analysisId, analysisManifestSha256)
        if (!bytes.contentEquals(canonicalJson(document))) corrupt("advice is not canonical")
        return StoredAdvice(target, document)
    }

    private fun validateAgainstAnalysis(
        document: JsonObject,
        runId: String,
        analysisId: String,
        analysisManifestSha256: String,
    ) {
        val result = runBundles.readAnalysisDocuments(runId, analysisId)?.first ?: throw NoSuchElementException("ANALYSIS_NOT_FOUND")
        val evidence = AdvisoryEvidenceBuilder.build(runId, analysisId, analysisManifestSha256, result)
        try {
            validateStoredAdvice(document, runId, analysisId, analysisManifestSha256, evidence)
        } catch (_: AdviceValidationException) {
            corrupt("advice contract differs")
        }
    }
}

private fun adviceManifest(
    runId: String,
    analysisId: String,
    analysisManifestSha256: String,
    advice: ByteArray,
): ByteArray =
    canonicalJson(
        buildJsonObject {
            put("schema_version", "ai-advice-manifest.v1")
            put("run_id", runId)
            put("analysis_id", analysisId)
            put("analysis_manifest_sha256", analysisManifestSha256)
            put(
                "artifact",
                buildJsonObject {
                    put("path", ADVICE_FILE)
                    put("size_bytes", advice.size)
                    put("sha256", sha256Hex(advice))
                },
            )
        },
    )

private fun writeForced(
    path: Path,
    bytes: ByteArray,
) {
    FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
    }
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

private fun corrupt(message: String): Nothing = throw IllegalStateException("CORRUPT_AI_ADVICE: $message")

private const val ADVICE_DIRECTORY = "advice"
private const val ADVICE_FILE = "ai-advice.json"
private const val MANIFEST_FILE = "manifest.json"
private const val MAX_STORED_ADVICE_BYTES = 160 * 1024
private const val MAX_ADVICE_MANIFEST_BYTES = 16 * 1024
private val IS_WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

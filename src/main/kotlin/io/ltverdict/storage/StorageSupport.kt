package io.ltverdict.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// The tests of the storage name these types in this package; their definitions live where the core and the ingest can reach them.
internal typealias AcceptedInput = io.ltverdict.ingest.AcceptedInput
internal typealias StoredAnalysis = io.ltverdict.core.StoredAnalysis
internal typealias StoredArtifact = io.ltverdict.core.StoredArtifact

internal fun writeForced(
    path: Path,
    bytes: ByteArray,
) {
    FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
        channel.writeFully(ByteBuffer.wrap(bytes))
        channel.force(true)
    }
}

internal fun forceDirectory(path: Path) {
    try {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is optional where the JDK does not expose it.
    } catch (error: IOException) {
        if (!IS_WINDOWS) throw error
    }
}

internal fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}

internal fun JsonObject.string(name: String): String {
    val value = this[name] as? JsonPrimitive ?: corrupt("$name must be a string")
    if (!value.isString) corrupt("$name must be a string")
    return value.content
}

internal fun requireOwnedDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) corrupt("unsafe directory at $path")
    return path
}

internal fun corrupt(message: String): Nothing = throw IllegalStateException("CORRUPT_RUN_BUNDLE: $message")

internal val IS_WINDOWS = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

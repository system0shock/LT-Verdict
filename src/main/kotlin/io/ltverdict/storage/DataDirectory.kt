package io.ltverdict.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.UUID

internal class DataDirectory private constructor(
    val root: Path,
    internal val staging: Path,
    internal val runs: Path,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    internal val operationLock = Any()
    private var closed = false

    internal fun requireOpen() {
        check(!closed) { "DATA_DIR_CLOSED" }
    }

    override fun close() {
        synchronized(operationLock) {
            if (closed) return
            closed = true
            try {
                lock.release()
            } finally {
                lockChannel.close()
            }
        }
    }

    internal companion object {
        const val LOCK_REGION_OFFSET = 4096L

        fun open(
            root: Path,
            commandKind: String = "cli",
        ): DataDirectory {
            val normalized = root.toAbsolutePath().normalize()
            rejectSymlinkComponents(normalized)
            Files.createDirectories(normalized)
            rejectSymlinkComponents(normalized)

            val lockPath = normalized.resolve(".ltv.lock")
            rejectOwnedSymlink(lockPath)
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                unsafePath()
            }

            val channel =
                FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                )
            var acquired: FileLock? = null
            try {
                rejectOwnedSymlink(lockPath)
                acquired =
                    try {
                        // Older binaries lock 0..MAX, overlapping this region and preserving mutual exclusion.
                        channel.tryLock(LOCK_REGION_OFFSET, 1L, false)
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                if (acquired == null) throw IllegalStateException(busyMessage(lockPath))

                val metadata = "pid=${ProcessHandle.current().pid()}\ncommand=$commandKind\nstarted=${Instant.now()}\n"
                val owner = ByteBuffer.wrap(metadata.toByteArray(Charsets.UTF_8))
                channel.truncate(0)
                while (owner.hasRemaining()) channel.write(owner)
                channel.force(true)

                val staging = ensureOwnedDirectory(normalized.resolve(".staging"))
                val runs = ensureOwnedDirectory(normalized.resolve("runs"))
                val realRoot = normalized.toRealPath()
                if (staging.toRealPath().parent != realRoot || runs.toRealPath().parent != realRoot) unsafePath()
                cleanupStaging(staging)
                return DataDirectory(normalized, staging, runs, channel, acquired)
            } catch (error: Exception) {
                try {
                    acquired?.release()
                } finally {
                    channel.close()
                }
                throw error
            }
        }

        private fun busyMessage(lockPath: Path): String {
            val detail =
                runCatching {
                    val content =
                        FileChannel.open(lockPath, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                            if (channel.size() > 512) return@runCatching null
                            val buffer = ByteBuffer.allocate(512)
                            while (buffer.hasRemaining() && channel.read(buffer) > 0) { }
                            String(buffer.array(), 0, buffer.position(), Charsets.UTF_8)
                        }
                    val match =
                        Regex("pid=([1-9][0-9]*)\ncommand=(cli|server)\nstarted=([^\r\n]+)\n").matchEntire(content)
                            ?: return@runCatching null
                    val pid = match.groupValues[1].toLong()
                    val kind = match.groupValues[2]
                    val started = Instant.parse(match.groupValues[3])
                    "held by pid $pid ($kind) since $started; stop that process or use another --data-dir"
                }.getOrNull()
            return if (detail == null) "DATA_DIR_BUSY" else "DATA_DIR_BUSY\n$detail"
        }

        private fun ensureOwnedDirectory(path: Path): Path {
            rejectOwnedSymlink(path)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) unsafePath()
            } else {
                Files.createDirectory(path)
            }
            rejectOwnedSymlink(path)
            return path
        }

        private fun cleanupStaging(staging: Path) {
            Files.newDirectoryStream(staging).use { entries ->
                entries.forEach { child ->
                    if (!Files.isSymbolicLink(child) && isOwnedUuid(child.fileName.toString())) {
                        deleteTree(child)
                    }
                }
            }
        }

        internal fun deleteTree(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        error: IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }

        private fun isOwnedUuid(name: String): Boolean =
            try {
                UUID.fromString(name).toString() == name
            } catch (_: IllegalArgumentException) {
                false
            }

        private fun rejectSymlinkComponents(path: Path) {
            var current = path.root
            path.forEach { component ->
                current = current.resolve(component)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) unsafePath()
            }
        }

        private fun rejectOwnedSymlink(path: Path) {
            if (Files.isSymbolicLink(path)) unsafePath()
        }

        private fun unsafePath(): Nothing = throw IllegalArgumentException("UNSAFE_DATA_PATH")
    }
}

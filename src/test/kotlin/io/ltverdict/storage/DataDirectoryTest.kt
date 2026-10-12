package io.ltverdict.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class DataDirectoryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquired lock records owner pid command and ISO start time`() {
        val root = tempDir.resolve("owner")
        val before = Instant.now()
        DataDirectory.open(root, "server").use { }
        val metadata = Files.readString(root.resolve(".ltv.lock")).lines()
        assertEquals("pid=${ProcessHandle.current().pid()}", metadata[0])
        assertEquals("command=server", metadata[1])
        val started = Instant.parse(metadata[2].removePrefix("started="))
        assertFalse(started.isBefore(before))
        assertFalse(started.isAfter(Instant.now()))
    }

    @Test
    fun `second acquire reports owner without replacing lock content`() {
        val root = tempDir.resolve("busy-owner")
        DataDirectory.open(root).use {
            val failure = assertThrows(IllegalStateException::class.java) { DataDirectory.open(root) }
            assertEquals("DATA_DIR_BUSY", failure.message!!.lineSequence().first())
            assertTrue(failure.message!!.contains("held by pid ${ProcessHandle.current().pid()} (cli) since "))
            assertTrue(failure.message!!.contains("stop that process or use another --data-dir"))
        }
        assertTrue(Files.readString(root.resolve(".ltv.lock")).contains("pid=${ProcessHandle.current().pid()}"))
        DataDirectory.open(root).close()
    }

    @Test
    fun `empty or garbage lock metadata yields bare DATA_DIR_BUSY`() {
        val root = Files.createDirectories(tempDir.resolve("garbage-owner"))
        val lockPath = root.resolve(".ltv.lock")
        listOf(
            "",
            "garbage",
            "pid=123\ncommand=server\nstarted=invalid\n",
            "pid=0\ncommand=cli\nstarted=2026-10-12T10:00:00Z\n",
            "pid=999999999999999999999999\ncommand=server\nstarted=2026-10-12T10:00:00Z\n",
            "pid=123\ncommand=server\nstarted=2026-10-12T10:00:00Z\n" + "x".repeat(512),
        ).forEach { content ->
            Files.writeString(lockPath, content)
            FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
                channel.lock(DataDirectory.LOCK_REGION_OFFSET, 1L, false).use {
                    val failure = assertThrows(IllegalStateException::class.java) { DataDirectory.open(root) }
                    assertEquals("DATA_DIR_BUSY", failure.message)
                }
            }
            assertEquals(content, Files.readString(lockPath))
        }
    }

    @Test
    fun `startup removes only owned stale UUID entries without following links`() {
        val root = tempDir.resolve("data")
        val staging = Files.createDirectories(root.resolve(".staging"))
        val stale = Files.createDirectories(staging.resolve(UUID.randomUUID().toString()))
        Files.writeString(stale.resolve("partial.bin"), "partial")
        val named = Files.createDirectories(staging.resolve("keep-me"))
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        Files.writeString(outside.resolve("keep.txt"), "keep")
        val link = staging.resolve(UUID.randomUUID().toString())
        val symlinkCreated = createSymlink(link, outside)

        DataDirectory.open(root).use { directory ->
            assertEqualsNormalized(root, directory.root)
            assertTrue(Files.isRegularFile(root.resolve(".ltv.lock")))
        }

        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(named))
        assertTrue(Files.exists(outside.resolve("keep.txt")))
        if (symlinkCreated) assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `supplied and app-owned symlinks are rejected`() {
        val outside = Files.createDirectories(tempDir.resolve("outside"))
        val supplied = tempDir.resolve("linked-root")
        assumeTrue(createSymlink(supplied, outside), "symbolic links are unavailable")
        assertThrows(IllegalArgumentException::class.java) { DataDirectory.open(supplied) }

        listOf(".ltv.lock", ".staging", "runs").forEachIndexed { index, name ->
            val root = Files.createDirectories(tempDir.resolve("data-$index"))
            val target = if (name == ".ltv.lock") Files.createFile(tempDir.resolve("lock-target-$index")) else outside
            Files.createSymbolicLink(root.resolve(name), target)
            assertThrows(IllegalArgumentException::class.java) { DataDirectory.open(root) }
        }
    }

    @Test
    fun `second JVM gets DATA_DIR_BUSY before staging cleanup`() {
        val root = tempDir.resolve("locked")
        DataDirectory.open(root).use {
            val survivor = Files.createDirectories(root.resolve(".staging").resolve(UUID.randomUUID().toString()))
            val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
            val process =
                ProcessBuilder(
                    java.toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    DataDirectoryLockProbe::class.java.name,
                    root.toString(),
                ).redirectErrorStream(true).start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().readText()

            assertTrue(finished, "lock probe timed out")
            assertTrue(output.contains("DATA_DIR_BUSY"), output)
            assertTrue(output.contains("held by pid ${ProcessHandle.current().pid()} (cli) since "), output)
            assertTrue(Files.exists(survivor), "losing process mutated staging")
        }
    }

    private fun createSymlink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: Exception) {
            false
        }

    private fun assertEqualsNormalized(
        expected: Path,
        actual: Path,
    ) = assertTrue(actual == expected.toAbsolutePath().normalize(), "$actual != $expected")

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}

internal object DataDirectoryLockProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            DataDirectory.open(Path.of(args.single())).use { }
            println("LOCK_ACQUIRED")
        } catch (error: Exception) {
            println(error.message)
        }
    }
}

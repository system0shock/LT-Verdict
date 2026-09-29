package io.ltverdict.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class DataDirectoryTest {
    @TempDir
    lateinit var tempDir: Path

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

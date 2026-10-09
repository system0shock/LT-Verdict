package io.ltverdict.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.2 PR 3: the packages depend in one direction, storage -> core -> ingest. A reference to a higher package (an import or a
 * fully qualified name) in a lower one brings the cycle back.
 */
class PackageDependencyTest {
    private val sources = Path.of("src/main/kotlin/io/ltverdict")

    private fun references(
        pkg: String,
        forbidden: String,
    ): List<String> =
        Files.walk(sources.resolve(pkg)).use { paths ->
            paths
                .filter { it.toString().endsWith(".kt") }
                .toList()
                .filter { Files.readString(it).contains(Regex("io[.]ltverdict[.]$forbidden\\b")) }
                .map { sources.relativize(it).toString().replace("\\", "/") }
                .sorted()
        }

    @Test
    fun `the core does not reach into the storage`() {
        assertEquals(emptyList<String>(), references("core", "storage"))
    }

    @Test
    fun `the ingest reaches neither the storage nor the core`() {
        assertEquals(emptyList<String>(), references("ingest", "storage"))
        assertEquals(emptyList<String>(), references("ingest", "core"))
    }
}

package io.ltverdict.core

import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** W2.1: a committed bundle of the current version is read through the store and by the typed document models. */
class TypedBoundaryBundleTest {
    @TempDir
    lateinit var tempDir: Path

    private val bundle = Path.of("fixtures/typed-boundary/bundle-v1")

    @Test
    fun `the committed current-version bundle verifies and decodes into the typed documents`() {
        val analysisId = Files.readString(Path.of("fixtures/typed-boundary/golden/csv-pass/analysis_id.txt")).trim()
        val runId = "jmeter_jtl_csv-6f9e658876e83eda9a3727dbb32612a2f54800ee34ea22641deeab6781f82601"
        val data = tempDir.resolve("data")
        val runDir = data.resolve("runs/$runId")
        copyTree(bundle.resolve("analysis"), runDir.resolve("analyses/$analysisId"))
        copyTree(bundle.resolve("inputs"), runDir.resolve("inputs"))
        Files.copy(bundle.resolve("source.json"), runDir.resolve("source.json"))
        val analysisDir = data.resolve("runs/$runId/analyses/$analysisId")

        val verified =
            DataDirectory.open(data).use { directory ->
                checkNotNull(RunBundleStore(directory).readVerifiedAnalysis(runId, analysisId))
            }

        val resultBytes = Files.readAllBytes(analysisDir.resolve("analysis-result.json"))
        val identityBytes = Files.readAllBytes(analysisDir.resolve("identity.json"))
        assertEquals(analysisId, sha256Hex(identityBytes))
        assertArrayEquals(canonicalJson(verified.result), resultBytes)
        assertArrayEquals(canonicalJson(verified.identity), identityBytes)

        val result = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisResultDocument.serializer(), resultBytes.decodeToString())
        assertEquals(runId, result.runId)
        assertEquals("analysis-result.v1", result.schemaVersion)
        assertEquals(AnalysisMode.STANDARD, result.analysisMode)
        assertArrayEquals(resultBytes, encodeAnalysisResult(result))

        val identity = ANALYSIS_DOCUMENT_JSON.decodeFromString(AnalysisIdentityDocument.serializer(), identityBytes.decodeToString())
        assertEquals(runId, identity.runId)
        assertEquals("analysis-identity.v1", identity.schemaVersion)
        assertEquals("analysis-result.v1", identity.outputs.analysisResultSchema)
        assertArrayEquals(identityBytes, encodeAnalysisIdentity(identity))
    }

    private fun copyTree(
        from: Path,
        to: Path,
    ) {
        Files.walk(from).use { paths ->
            paths.forEach { path ->
                val target = to.resolve(from.relativize(path).toString())
                if (Files.isDirectory(
                        path,
                    )
                ) {
                    Files.createDirectories(target)
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

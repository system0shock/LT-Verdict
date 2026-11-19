package io.ltverdict.core

import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR 0037, D6: the bytes of identity.json and analysis-result.json of the existing modes (stages only, snapshot only,
 * snapshot with platform rules) were pinned on the code before stages could meet a snapshot (plan, task 1).
 */
class StageResourceCrossVersionTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `existing modes keep their committed identity and result bytes`() {
        MODES.forEach { mode ->
            val (identity, result) = analyzeStored(JTL, mode.policy, mode.snapshot, mode.stages)

            assertArrayEquals(Files.readAllBytes(CROSS_VERSION.resolve("${mode.name}/identity.json")), identity, mode.name)
            assertArrayEquals(Files.readAllBytes(CROSS_VERSION.resolve("${mode.name}/analysis-result.json")), result, mode.name)
        }
    }

    /** Identity and result bytes of one analysis in a fresh data directory. */
    private fun analyzeStored(
        jtl: Path,
        policy: Path?,
        snapshot: Path?,
        stages: Path?,
    ): Pair<ByteArray, ByteArray> =
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(Files.readAllBytes(jtl)), "input.jtl")
            val outcome =
                AnalysisService(store, EngineConfig()).analyze(
                    AnalysisRequest(
                        input,
                        policy?.let {
                            assertInstanceOf(PolicyValidation.Valid::class.java, validatePolicy(ByteArrayInputStream(Files.readAllBytes(it))))
                        },
                        resources =
                            snapshot?.let {
                                assertInstanceOf(
                                    ResourceValidation.Valid::class.java,
                                    validateResourceSnapshot(ByteArrayInputStream(Files.readAllBytes(it))),
                                )
                            },
                        stages =
                            stages?.let {
                                assertInstanceOf(
                                    LoadStagesValidation.Valid::class.java,
                                    validateLoadStages(ByteArrayInputStream(Files.readAllBytes(it))),
                                )
                            },
                    ),
                )
            Files.readAllBytes(outcome.analysisDirectory.resolve("identity.json")) to
                Files.readAllBytes(outcome.analysisDirectory.resolve("analysis-result.json"))
        }

    private data class Mode(
        val name: String,
        val policy: Path?,
        val snapshot: Path?,
        val stages: Path?,
    )

    private companion object {
        val CROSS_VERSION: Path = Path.of("fixtures/stages/cross-version")
        val JTL: Path = Path.of("fixtures/stages/ramp-steady-rampdown.jtl")
        val SLICE1_POLICY: Path = Path.of("fixtures/slice1/identity/policy.canonical.json")
        val SNAPSHOT: Path = CROSS_VERSION.resolve("snapshot-15s.json")
        val STAGES: Path = Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")
        val MODES =
            listOf(
                Mode("stages-only", SLICE1_POLICY, null, STAGES),
                Mode("snapshot-only", SLICE1_POLICY, SNAPSHOT, null),
                Mode("snapshot-platform", CROSS_VERSION.resolve("policy-platform.json"), SNAPSHOT, null),
            )
    }
}

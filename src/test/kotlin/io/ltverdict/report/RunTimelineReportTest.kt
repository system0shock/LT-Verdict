package io.ltverdict.report

import io.ltverdict.core.StagedResults
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * W2.6 PR 2: the HTML report with the run block and the inline chart. The first test pins the bytes of the report WITHOUT the block
 * (captured from origin/main before the change), so the proof of "nothing else moved" does not depend on the golden files, which this PR
 * regenerates on purpose.
 */
class RunTimelineReportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `report without a timeline keeps the bytes captured before the change`() {
        val expected =
            mapOf(
                "ramp-pass" to "8f84be2b1e4221c0f58c9aa599ae480247698732e70934df6f9cebe64de606ca",
                "ramp-fail" to "c29bd47969f0934f918f3f4bd2638524c152410cba2a42379cd36da3489db88c",
                "csv-no-policy" to "6294430839c5060555a93371c1a34dacbca1a1504681715c80ca113aaf1eef95",
            )
        val inputs =
            mapOf(
                "ramp-pass" to Pair(StagedResults.RAMP, StagedResults.policy(StagedResults.p95(100_000))),
                "ramp-fail" to Pair(StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250))),
                "csv-no-policy" to Pair(Files.readAllBytes(Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")), null),
            )
        val actual =
            inputs.mapValues { (name, input) ->
                val outcome = StagedResults.analyze(tempDir.resolve(name), input.first, input.second)
                sha256(renderHtmlReport(outcome.canonicalResult, "fixed", readErrorGroupsFile(outcome.analysisDirectory)))
            }
        assertEquals(expected, actual)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

package io.ltverdict.report

import io.ltverdict.cli.junitXml
import io.ltverdict.cli.summaryJson
import io.ltverdict.cli.summaryText
import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.renderConfluenceReport
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR 0030, PR C: a run without stages keeps the bytes of every text artifact. The goldens in fixtures/stages/plain-reports were
 * captured from origin/main BEFORE the visibility change (LTV_UPDATE_PLAIN_REPORTS=1 on that tree); regenerate only on purpose.
 */
class PlainReportsGoldenTest {
    @TempDir
    lateinit var tempDir: Path

    private val root = Path.of("fixtures/stages/plain-reports")

    private data class Scenario(
        val name: String,
        val input: ByteArray,
        val policy: String?,
    )

    private val scenarios =
        listOf(
            Scenario("ramp-pass", StagedResults.RAMP, StagedResults.policy(StagedResults.p95(100_000))),
            Scenario("ramp-fail", StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250))),
            Scenario("csv-no-policy", Files.readAllBytes(Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")), null),
        )

    @Test
    fun `reports and summaries of a run without stages equal the goldens captured before the change`() {
        val update = System.getenv("LTV_UPDATE_PLAIN_REPORTS") == "1"
        scenarios.forEach { scenario ->
            val outcome = StagedResults.analyze(tempDir.resolve(scenario.name), scenario.input, scenario.policy)
            val result = outcome.canonicalResult
            val exit =
                when {
                    scenario.policy == null || scenario.name == "ramp-pass" -> 0
                    else -> 2
                }
            val files =
                mapOf(
                    "report.html" to renderHtmlReport(result, "fixed"),
                    "report.adoc" to renderAsciiDocReport(result, "fixed"),
                    "report.confluence" to renderConfluenceReport(result, "fixed"),
                    "summary.txt" to summaryText("fixed", exit, result),
                    "summary.json" to summaryJson("fixed", result),
                    "junit.xml" to junitXml(result),
                )
            files.forEach { (name, bytes) ->
                val golden = root.resolve(scenario.name).resolve(name)
                if (update) {
                    Files.createDirectories(golden.parent)
                    Files.write(golden, bytes)
                }
                assertTrue(Files.exists(golden), "missing golden $golden")
                assertArrayEquals(Files.readAllBytes(golden), bytes, "${scenario.name}/$name differs from the golden")
            }
        }
    }
}

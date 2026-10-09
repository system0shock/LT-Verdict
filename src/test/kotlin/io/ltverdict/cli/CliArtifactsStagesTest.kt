package io.ltverdict.cli

import io.ltverdict.core.StagedResults
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** ADR 0030, PR C: summary.txt, cli-summary.v1 and junit.xml of a staged run; a run without stages keeps its bytes. */
class CliArtifactsStagesTest {
    @TempDir
    lateinit var tempDir: Path

    private val scope = "scope=steady_window window_ids=steady excluded_ms=59800"

    private fun staged(
        policy: String? = StagedResults.policy(StagedResults.p95(250)),
        stages: String = StagedResults.RAMP_STEADY_DOWN,
    ) = StagedResults.analyze(tempDir, policy = policy, stages = stages)

    @Test
    fun `summary text names the scope, the window and marks the whole run line as reference`() {
        val outcome = staged()

        val text = summaryText(outcome.analysisId, 0, outcome.canonicalResult).decodeToString()
        val lines = text.lines()

        assertEquals("scope: steady window (steady), excluded 59800 ms", lines.single { it.startsWith("scope:") })
        assertEquals(
            "samples: 720  errors: 0  p95_ms: 821  p99_ms: 836  whole_run (reference only)",
            lines.single { it.startsWith("samples:") },
        )
        assertEquals(
            "window[steady]: samples: 600  errors: 0  p95_ms: 194  p99_ms: 198  rps: 10",
            lines.single { it.startsWith("window[") },
        )
        assertTrue(lines.indexOf("exit_code: 0") < lines.indexOf(lines.single { it.startsWith("scope:") }))
    }

    @Test
    fun `two steady windows give two window lines in the order of the result`() {
        val outcome = staged(StagedResults.policy(StagedResults.p95(1000)), StagedResults.TWO_STEADY)

        val text = summaryText(outcome.analysisId, 0, outcome.canonicalResult).decodeToString()

        assertTrue(text.contains("scope: steady window (steady-a, steady-b), excluded "), text)
        assertEquals(
            listOf("window[steady-a]:", "window[steady-b]:"),
            text
                .lines()
                .filter {
                    it.startsWith("window[")
                }.map { it.substringBefore(" ") },
        )
    }

    @Test
    fun `summary json adds windows and excluded_ms and keeps the whole run as overall`() {
        val outcome = staged(StagedResults.policy(StagedResults.p95(1000)), StagedResults.TWO_STEADY)

        val json = Json.parseToJsonElement(summaryJson(outcome.analysisId, outcome.canonicalResult).decodeToString()).jsonObject
        val windows = json.getValue("windows").jsonArray.map { it.jsonObject }

        assertEquals(listOf("steady-a", "steady-b"), windows.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(
            setOf("id", "from_epoch_ms", "to_epoch_ms", "samples", "errors", "error_rate", "p50", "p95", "p99", "max", "rps"),
            windows.first().keys,
        )
        assertEquals(
            "720",
            json
                .getValue("overall")
                .jsonObject
                .getValue("samples")
                .jsonPrimitive.content,
        )
        assertTrue(
            json
                .getValue("excluded_ms")
                .jsonPrimitive.content
                .toLong() > 0,
        )
        val single = Json.parseToJsonElement(summaryJson("a", staged().canonicalResult).decodeToString()).jsonObject
        assertEquals(
            "194",
            single
                .getValue("windows")
                .jsonArray
                .single()
                .jsonObject
                .getValue("p95")
                .jsonPrimitive.content,
        )
        assertEquals(
            "10",
            single
                .getValue("windows")
                .jsonArray
                .single()
                .jsonObject
                .getValue("rps")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `junit puts the scope in the message of a failed or unresolved gate and in system-out of a passed one`() {
        val fail = staged(StagedResults.policy(StagedResults.p95(190, """["steady"]""")))
        val unresolved = staged(StagedResults.policy(StagedResults.p95(250, """["ramp-up"]""")))
        val pass = staged()
        val noPolicy = staged(policy = null)

        val failed = junitXml(fail.canonicalResult).decodeToString()
        val noVerdict = junitXml(unresolved.canonicalResult).decodeToString()
        val passed = junitXml(pass.canonicalResult).decodeToString()
        val none = junitXml(noPolicy.canonicalResult).decodeToString()

        assertTrue(failed.contains("policy_verdict=FAIL run_validity=VALID reasons= $scope"), failed)
        assertTrue(noVerdict.contains("policy_verdict=NO_VERDICT run_validity=VALID reasons=RULE_WINDOW_NOT_FOUND $scope"), noVerdict)
        assertTrue(
            passed.contains("<testcase classname=\"lt-verdict.gate\" name=\"gate\" time=\"0\"><system-out>$scope</system-out></testcase>"),
            passed,
        )
        assertTrue(none.contains("<system-out>$scope</system-out>"), none)
        assertFalse(passed.contains("policy_verdict="))
    }

    @Test
    fun `without the stage items every artifact keeps its bytes and has no stage token`() {
        val outcome = staged()
        val stripped = StagedResults.withoutStageItems(outcome.canonicalResult)

        val text = summaryText("x", 0, stripped).decodeToString()
        val json = summaryJson("x", stripped).decodeToString()
        val xml = junitXml(stripped).decodeToString()

        listOf("scope", "window[", "whole_run").forEach { assertFalse(text.contains(it), it) }
        listOf("windows", "excluded_ms").forEach { assertFalse(json.contains(it), it) }
        listOf("scope=", "system-out").forEach { assertFalse(xml.contains(it), it) }
        val plain = StagedResults.analyze(tempDir, policy = StagedResults.policy(StagedResults.p95(250)))
        assertFalse(summaryText("x", 0, plain.canonicalResult).decodeToString().contains("scope"))
    }
}

package io.ltverdict.report

import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.renderConfluenceReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** W2.4: the reports say how much of the run lies outside the verdict window and mark throughput rules evaluated on a window. */
class WindowHeadingsTest {
    @TempDir
    lateinit var tempDir: Path

    private val label = "Вне окна вердикта (разгон, остановка, простои и прочее)"

    private fun parse(document: String): JsonObject = Json.parseToJsonElement(document).jsonObject

    private fun resourceWindows(
        runFrom: Long,
        runTo: Long,
        vararg windows: Pair<Long, Long>,
    ): String {
        val binding = """{"id":"resource-binding","type":"resource_binding","run_from_epoch_ms":$runFrom,"run_to_epoch_ms":$runTo}"""
        val summaries =
            windows.mapIndexed { index, (from, to) ->
                """{"id":"w$index","type":"window_policy_summary","window_id":"w$index","from_epoch_ms":$from,"to_epoch_ms":$to}"""
            }
        return """{"run_id":"r","run_validity":"VALID","policy_verdict":"PASS","evidence":[${(
            listOf(
                binding,
            ) + summaries
        ).joinToString(",")}],"findings":[]}"""
    }

    @Test
    fun `stage mode shares the excluded time of the run from stage_binding`() {
        val outcome =
            StagedResults.analyze(
                tempDir,
                policy = StagedResults.policy(StagedResults.p95(250)),
                stages = StagedResults.RAMP_STEADY_DOWN,
            )

        val share = checkNotNull(windowShareText(Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject))

        assertEquals("49,9 % прогона (59,8 с из 2 мин)", share)
    }

    @Test
    fun `a clipped steady stage and two windows with a gap are shared from the evaluated time only`() {
        val tail =
            """{"schema_version":"load-stages.v1","stages":""" +
                """[{"id":"tail","role":"steady","from_offset_ms":100000,"to_offset_ms":130000}]}"""

        fun share(stages: String) =
            windowShareText(
                Json
                    .parseToJsonElement(
                        StagedResults
                            .analyze(tempDir, policy = StagedResults.policy(StagedResults.p95(1000)), stages = stages)
                            .canonicalResult
                            .decodeToString(),
                    ).jsonObject,
            )

        assertEquals("83,5 % прогона (1,67 мин из 2 мин)", share(tail))
        assertEquals("41,6 % прогона (49,8 с из 2 мин)", share(StagedResults.TWO_STEADY))
    }

    @Test
    fun `resource window mode takes the run bounds from resource_binding and the windows from window_policy_summary`() {
        val result = parse(resourceWindows(0, 120_000, 30_000L to 60_000L, 70_000L to 100_000L))

        assertEquals("50,0 % прогона (1 мин из 2 мин)", windowShareText(result))
    }

    @Test
    fun `a window that covers the run has a zero share and a sliver is not shown as zero`() {
        assertEquals("0,0 % прогона (0 с из 1 мин)", windowShareText(parse(resourceWindows(0, 60_000, 0L to 60_000L))))
        assertEquals("меньше 0,1 % прогона (0,4 с из 16,67 мин)", windowShareText(parse(resourceWindows(0, 1_000_000, 0L to 999_600L))))
    }

    @Test
    fun `there is no share without a window or without a run`() {
        assertNull(windowShareText(parse("""{"evidence":[]}""")))
        assertNull(windowShareText(parse(resourceWindows(0, 60_000))))
        assertNull(windowShareText(parse(resourceWindows(5, 5, 5L to 5L))))
        assertNull(windowShareText(parse(resourceWindows(0, 250_000_000_000_004, 0L to 125_000_000_000_002))))
    }

    @Test
    fun `all three reports carry the share for a windowed run and none for a plain run`() {
        val windowed = resourceWindows(0, 120_000, 30_000L to 90_000L).encodeToByteArray()
        val plain = """{"run_id":"r","run_validity":"VALID","policy_verdict":"PASS","evidence":[],"findings":[]}""".encodeToByteArray()

        val html = renderHtmlReport(windowed, "a").decodeToString()
        val ascii = renderAsciiDocReport(windowed, "a").decodeToString()
        val confluence = renderConfluenceReport(windowed, "a").decodeToString()

        assertTrue(html.contains("<dt lang=\"ru\">$label</dt><dd lang=\"ru\">50,0 % прогона (1 мин из 2 мин)</dd>"), html)
        assertTrue(ascii.contains("$label\n[subs=specialchars]\n----\n50,0 % прогона (1 мин из 2 мин)\n----\n"), ascii)
        assertTrue(confluence.contains("<th>$label</th><td>50,0 % прогона (1 мин из 2 мин)</td>"), confluence)
        listOf(
            renderHtmlReport(plain, "a").decodeToString(),
            renderAsciiDocReport(plain, "a").decodeToString(),
            renderConfluenceReport(plain, "a").decodeToString(),
        ).forEach { assertFalse(it.contains("Вне окна вердикта"), it) }
    }

    @Test
    fun `a staged run is shown with the share and the same run without the stage items is not`() {
        val outcome =
            StagedResults.analyze(
                tempDir,
                policy = StagedResults.policy(StagedResults.p95(250)),
                stages = StagedResults.RAMP_STEADY_DOWN,
            )

        val report = renderHtmlReport(outcome.canonicalResult, outcome.analysisId).decodeToString()
        val stripped = renderHtmlReport(StagedResults.withoutStageItems(outcome.canonicalResult), "x").decodeToString()

        assertTrue(report.contains("<dd lang=\"ru\">49,9 % прогона (59,8 с из 2 мин)</dd>"), report)
        assertFalse(stripped.contains("Вне окна вердикта"))
    }

    private fun throughputCheck(
        windowId: String?,
        metric: String = "throughput_rps",
        status: String = "FAIL",
    ): String {
        val window = windowId?.let { """"window_id":"$it",""" } ?: ""
        return """{"id":"c1","type":"policy_check","rule_id":"rps","metric":"$metric","operator":"gte",""" +
            """"threshold":{"numerator":100,"denominator":1},""" +
            """"observed":{"numerator":50,"denominator":1},$window"scope":{"kind":"overall"},"status":"$status"}"""
    }

    private fun checksReport(vararg checks: String) =
        renderHtmlReport(
            """{"run_id":"r","run_validity":"VALID","policy_verdict":"FAIL","evidence":[${checks.joinToString(
                ",",
            )}],"findings":[],"analysis_coverage":{"status":"COMPLETE","reasons":[]}}""".encodeToByteArray(),
            "a",
        ).decodeToString()

    @Test
    fun `a throughput rule evaluated on a window is marked in the rule table and in the failure text`() {
        val report = checksReport(throughputCheck("steady"))

        assertEquals(2, Regex(Regex.escape("пропускная способность (throughput на окне)")).findAll(report).count(), report)
    }

    @Test
    fun `a throughput rule on the whole run and the other metrics on a window are not marked`() {
        val report =
            checksReport(
                throughputCheck(null),
                throughputCheck("steady", metric = "response_time_p95_ms"),
            )

        assertFalse(report.contains("throughput на окне"), report)
        assertTrue(report.contains("пропускная способность"), report)
    }
}

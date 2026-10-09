package io.ltverdict.report

import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.renderConfluenceReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0030, PR C: the HTML, AsciiDoc and Confluence reports say aloud that the verdict is by the steady window. */
class StageReportsTest {
    @TempDir
    lateinit var tempDir: Path

    private val decided = "Вердикт посчитан по окну steady, разгон исключён"

    private fun staged(
        policy: String? = StagedResults.policy(StagedResults.p95(250)),
        stages: String = StagedResults.RAMP_STEADY_DOWN,
        input: ByteArray = StagedResults.RAMP,
    ) = StagedResults.analyze(tempDir, input, policy, stages)

    private fun html(result: io.ltverdict.core.AnalysisOutcome) =
        renderHtmlReport(result.canonicalResult, result.analysisId).decodeToString()

    @Test
    fun `a decided verdict names the steady window in the verdict list, the block and the stage table`() {
        listOf(250 to "PASS", 190 to "FAIL").forEach { (threshold, verdict) ->
            val outcome = staged(StagedResults.policy(StagedResults.p95(threshold, """["steady"]""")))

            val report = html(outcome)

            assertTrue(
                report.contains("<dt>Вердикт политики</dt><dd>$verdict</dd><dt lang=\"ru\">Область вердикта</dt><dd lang=\"ru\">"),
                report,
            )
            assertTrue(report.contains("<p><strong>$decided</strong></p>"), report)
            assertTrue(
                report.contains("Окно вердикта: steady, 1 мин, 2026-01-01 00:00:40 UTC – 2026-01-01 00:01:40 UTC. Исключено: 59,8 с."),
                report,
            )
            assertTrue(report.contains("<p>Оценено: 1 мин, исключено: 59,8 с.</p>"), report)
            assertTrue(report.contains("Метрики по всему прогону справочные"), report)
            assertTrue(
                report.contains("<th scope=\"col\">Стадия</th><th scope=\"col\">Роль</th><th scope=\"col\">Смещения, мс</th>"),
                report,
            )
            assertTrue(report.contains("<td>ramp-up</td><td>excluded</td><td>0 – 40000</td>"), report)
            assertTrue(report.contains("<td>steady</td><td>steady</td><td>40000 – 100000</td>"), report)
            assertTrue(report.contains("1767225640000 – 1767225700000"), report)
            assertTrue(report.contains("<td>ramp-down</td><td>excluded</td><td>100000 – 120000</td>"), report)
        }
    }

    @Test
    fun `without a verdict the report does not claim one`() {
        val noPolicy = html(staged(policy = null))
        val bound = html(staged(StagedResults.policy(StagedResults.p95(250, """["ramp-up"]"""))))
        val degraded =
            html(
                StagedResults.analyze(
                    tempDir,
                    Files.readAllBytes(Path.of("fixtures/slice1/gatling/binary-3.13.5/simulation.log")) + byteArrayOf(2, 0),
                    StagedResults.policy(StagedResults.p95(250)),
                    """{"schema_version":"load-stages.v1","stages":[{"id":"steady","role":"steady","from_offset_ms":0,"to_offset_ms":100}]}""",
                ),
            )

        listOf(noPolicy to "NO_POLICY", bound to "NO_VERDICT", degraded to "NO_VERDICT").forEach { (report, verdict) ->
            assertFalse(report.contains("Вердикт посчитан"), verdict)
            assertTrue(report.contains("Окно steady задано (steady), разгон исключён из метрик окна; вердикт: $verdict"), report)
            assertTrue(report.contains("Область вердикта"), report)
        }
    }

    @Test
    fun `an early foreign sample moves the windows and the table shows the actual epoch bounds`() {
        val setUp = "1767225570000,50,setUp,200,OK,setUp 1-1,text,true,,128,64,1,1,http://example.test/setup,25,0,5\n"
        val text = StagedResults.RAMP.decodeToString()
        val shifted = (text.lineSequence().first() + "\n" + setUp + text.lineSequence().drop(1).joinToString("\n")).encodeToByteArray()

        val report = html(staged(input = shifted))

        assertTrue(report.contains("1767225610000 – 1767225670000"), report)
        assertFalse(report.contains("1767225640000 – 1767225700000"), report)
    }

    @Test
    fun `a clipped steady stage is marked in the table`() {
        val tail =
            """{"schema_version":"load-stages.v1","stages":""" +
                """[{"id":"tail","role":"steady","from_offset_ms":100000,"to_offset_ms":130000}]}"""

        val report = html(staged(stages = tail))

        assertTrue(report.contains("<td>да</td>"), report)
        assertTrue(report.contains("<td>tail</td><td>steady</td><td>100000 – 130000</td>"), report)
    }

    @Test
    fun `two steady windows are both named`() {
        val report = html(staged(StagedResults.policy(StagedResults.p95(1000)), StagedResults.TWO_STEADY))

        assertTrue(report.contains("Окно вердикта: steady-a, 30 с, "), report)
        assertTrue(report.contains("; steady-b, 40 с, "), report)
    }

    @Test
    fun `a result without stages has no notice and a result stripped of the stage items renders like a run without stages`() {
        val plain = StagedResults.analyze(tempDir, policy = StagedResults.policy(StagedResults.p95(250)))
        val outcome = staged()
        val stripped = StagedResults.withoutStageItems(outcome.canonicalResult)

        val plainReport = html(plain)

        listOf("Область вердикта", "Вердикт посчитан", "Окно steady задано", "Стадия</th>", "разгон исключён").forEach {
            assertFalse(plainReport.contains(it), it)
        }
        assertFalse(renderHtmlReport(stripped, "x").decodeToString().contains("Область вердикта"))
        assertFalse(renderAsciiDocReport(stripped, "x").decodeToString().contains("Область вердикта"))
        assertFalse(renderConfluenceReport(stripped, "x").decodeToString().contains("разгон"))
    }

    @Test
    fun `a stage id is free text, so AsciiDoc keeps it in a literal block and the other formats escape it`() {
        val id = "image:x.png[]{attr}<b>&"
        val stages =
            """{"schema_version":"load-stages.v1","stages":""" +
                """[{"id":"$id","role":"steady","from_offset_ms":40000,"to_offset_ms":100000}]}"""
        val outcome = staged(stages = stages)

        val ascii = renderAsciiDocReport(outcome.canonicalResult, outcome.analysisId).decodeToString()
        val confluence = renderConfluenceReport(outcome.canonicalResult, outcome.analysisId).decodeToString()
        val report = html(outcome)

        val block = ascii.substringAfter("Область вердикта\n[subs=specialchars]\n----\n").substringBefore("\n----\n")
        assertTrue(block.startsWith("Вердикт посчитан по окну steady"), block)
        assertTrue(block.contains(id), block)
        assertFalse(ascii.lines().any { it.startsWith("NOTE:") || it.startsWith("image:") })
        assertTrue(confluence.contains("image:x.png[]{attr}&lt;b&gt;&amp;"), confluence)
        assertTrue(report.contains("image:x.png[]{attr}&lt;b&gt;&amp;"), report)
        assertFalse(report.contains("<b>"))
    }

    @Test
    fun `long durations group the thousands like the UI`() {
        val binding =
            """{"type":"stage_binding","evaluated_window_ids":["steady"],"evaluated_millis":120000000,"excluded_millis":60000000,""" +
                """"stages":[{"id":"steady","role":"steady","from_offset_ms":0,"to_offset_ms":120000000,""" +
                """"from_epoch_ms":0,"to_epoch_ms":120000000}]}"""
        val document = """{"policy_verdict":"PASS","run_validity":"VALID","evidence":[$binding]}"""
        val result =
            kotlinx.serialization.json.Json
                .parseToJsonElement(document) as kotlinx.serialization.json.JsonObject

        val notice = checkNotNull(stageNotice(result))

        assertTrue(notice.detail.contains("steady, 2\u00A0000 мин, "), notice.detail)
        assertTrue(notice.detail.endsWith("Исключено: 1\u00A0000 мин."), notice.detail)
        assertEquals("Оценено: 2\u00A0000 мин, исключено: 1\u00A0000 мин.", notice.totals)
    }

    @Test
    fun `AsciiDoc and Confluence carry the same phrase as one note`() {
        val outcome = staged()
        val noPolicy = staged(policy = null)

        val ascii = renderAsciiDocReport(outcome.canonicalResult, outcome.analysisId).decodeToString()
        val confluence = renderConfluenceReport(outcome.canonicalResult, outcome.analysisId).decodeToString()
        val neutral = renderAsciiDocReport(noPolicy.canonicalResult, noPolicy.analysisId).decodeToString()

        assertTrue(
            ascii.contains("Область вердикта\n[subs=specialchars]\n----\n$decided. Окно вердикта: steady, 1 мин, 2026-01-01 00:00:40 UTC"),
            ascii,
        )
        assertTrue(confluence.contains("<p>$decided. Окно вердикта: steady, 1 мин, "), confluence)
        assertEquals(1, Regex("Вердикт посчитан").findAll(ascii).count())
        assertTrue(
            neutral.contains("----\nОкно steady задано (steady), разгон исключён из метрик окна; вердикт: NO_POLICY. Окно вердикта:"),
            neutral,
        )
        assertFalse(neutral.contains("Вердикт посчитан"))
    }
}

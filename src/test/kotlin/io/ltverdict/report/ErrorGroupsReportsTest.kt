package io.ltverdict.report

import io.ltverdict.cli.summaryJson
import io.ltverdict.cli.summaryText
import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.renderConfluenceReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/** W2.6: the top errors are in the HTML, AsciiDoc and Confluence reports and in the CLI summaries; hostile text stays text. */
class ErrorGroupsReportsTest {
    @TempDir
    lateinit var tempDir: Path

    private fun analyze(csv: String): AnalysisOutcome = StagedResults.analyze(tempDir, csv.encodeToByteArray())

    private fun groups(outcome: AnalysisOutcome): ByteArray? = readErrorGroupsFile(outcome.analysisDirectory)

    @Test
    fun `html lists the top errors with code message transaction and share and escapes hostile text`() {
        val outcome = analyze(HOSTILE_CSV)

        val html = renderHtmlReport(outcome.canonicalResult, "fixed", groups(outcome)).decodeToString()

        assertTrue(html.contains("<h2>Ошибки</h2>"), html)
        assertTrue(html.contains("<th scope=\"col\">Ошибок</th><th scope=\"col\">Доля</th><th scope=\"col\">Код ответа</th>"), html)
        assertTrue(html.contains("<td>2</td><td>40,0 %</td><td>503</td><td>Service Unavailable</td><td>login</td>"), html)
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; ]]&gt; &quot;q&quot;"), html)
        assertFalse(html.contains("<script>"), html)
        assertFalse(html.contains("‮"), html)
        assertTrue(html.contains("line1 line2"), html)
        assertTrue(html.contains("Ошибки за весь прогон"), html)
    }

    @Test
    fun `html says the breakdown is unavailable for an analysis without the artifact and omits the section without errors`() {
        val withErrors = analyze(HOSTILE_CSV)
        val clean = analyze(CLEAN_CSV)

        val old = renderHtmlReport(withErrors.canonicalResult, "fixed", null).decodeToString()
        val none = renderHtmlReport(clean.canonicalResult, "fixed", groups(clean)).decodeToString()

        assertTrue(old.contains("<h2>Ошибки</h2>"), old)
        assertTrue(old.contains("Разбивка ошибок недоступна"), old)
        assertFalse(none.contains("<h2>Ошибки</h2>"), none)
        assertNull(groups(clean))
    }

    @Test
    fun `a damaged or foreign artifact is treated as unavailable`() {
        val outcome = analyze(HOSTILE_CSV)
        val good = checkNotNull(groups(outcome)).decodeToString()
        val damaged =
            listOf(
                "{", "[]", good.replace("error-groups.v1", "error-groups.v9"),
                good.replace("\"count\":2", "\"count\":-2"), "[".repeat(50) + "]".repeat(50),
            )

        damaged.forEach { text ->
            val html = renderHtmlReport(outcome.canonicalResult, "fixed", text.encodeToByteArray()).decodeToString()
            assertTrue(html.contains("Разбивка ошибок недоступна"), text.take(40))
        }
        val foreign = good.replace(Regex("\"run_id\":\"[^\"]*\""), "\"run_id\":\"other\"")
        assertTrue(renderHtmlReport(outcome.canonicalResult, "fixed", foreign.encodeToByteArray()).decodeToString().contains("недоступна"))
    }

    @Test
    fun `text read from the artifact is cleaned again before it is shown`() {
        val outcome = analyze(HOSTILE_CSV)
        val tampered =
            checkNotNull(groups(outcome))
                .decodeToString()
                .replace("Service Unavailable", "A\\u0000B\\u202EC\\nD<b>")

        val html = renderHtmlReport(outcome.canonicalResult, "fixed", tampered.encodeToByteArray()).decodeToString()

        assertTrue(html.contains("A B C D&lt;b&gt;"), html)
        assertFalse(html.contains("\u0000") || html.contains("‮"), html)
    }

    @Test
    fun `asciidoc puts each group on one line of a literal block that hostile text cannot close`() {
        val outcome = analyze(HOSTILE_CSV)

        val adoc = renderAsciiDocReport(outcome.canonicalResult, "fixed", groups(outcome)).decodeToString()
        val section = adoc.substringAfter("== Error groups\n").substringBefore("\n== ")

        assertTrue(section.contains("2 | 40,0 % | 503 | Service Unavailable | login"), section)
        assertTrue(section.contains("1 | 20,0 % | 502 | ---- | search"), section)
        val fences = section.lines().count { it == "----" }
        assertEquals(2, fences, section)
        assertFalse(section.contains("‮"), section)
    }

    @Test
    fun `confluence group table is well formed XML and contains no live markup from messages`() {
        val outcome = analyze(HOSTILE_CSV)

        val xhtml = renderConfluenceReport(outcome.canonicalResult, "fixed", groups(outcome)).decodeToString()

        assertTrue(xhtml.contains("<h2>Error groups</h2>"), xhtml)
        assertTrue(xhtml.contains("<td>Service Unavailable</td>"), xhtml)
        assertFalse(xhtml.contains("<script>"), xhtml)
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.parse(ByteArrayInputStream("<root>$xhtml</root>".encodeToByteArray()))
    }

    @Test
    fun `a staged run says the errors are of the whole run`() {
        val csv =
            StagedResults.RAMP.decodeToString().lines().mapIndexed { index, line ->
                if (index == 3) line.replace("200,OK,stages 1-1,text,true", "503,Busy,stages 1-1,text,false") else line
            }.joinToString("\n")
        val outcome = StagedResults.analyze(tempDir, csv.encodeToByteArray(), null, StagedResults.RAMP_STEADY_DOWN)

        val html = renderHtmlReport(outcome.canonicalResult, "fixed", groups(outcome)).decodeToString()

        assertTrue(html.contains("<td>503</td><td>Busy</td>"), html)
        assertTrue(html.contains("Ошибки за весь прогон; при вердикте по окну steady это справочная величина."), html)
    }

    @Test
    fun `cli summaries carry the top five groups`() {
        val outcome = analyze(HOSTILE_CSV)
        val result = outcome.canonicalResult

        val text = summaryText("fixed", 0, result, groups(outcome)).decodeToString()
        val json = Json.parseToJsonElement(summaryJson("fixed", result, groups(outcome)).decodeToString()).jsonObject
        val block = json.getValue("error_groups").jsonObject

        assertTrue(text.contains("top errors (whole run, 5 total):\n  2 503 Service Unavailable [login]\n"), text)
        assertEquals("whole_run", block.getValue("scope").jsonPrimitive.content)
        assertEquals("5", block.getValue("total_error_count").jsonPrimitive.content)
        assertEquals("503", block.getValue("groups").jsonArray.first().jsonObject.getValue("response_code").jsonPrimitive.content)
        assertTrue(block.getValue("groups").jsonArray.size <= 5)
    }

    @Test
    fun `cli summaries of a run without the artifact are the same as before`() {
        val clean = analyze(CLEAN_CSV)
        val result = clean.canonicalResult

        assertEquals(summaryText("fixed", 0, result).decodeToString(), summaryText("fixed", 0, result, null).decodeToString())
        assertFalse(summaryText("fixed", 0, result).decodeToString().contains("top errors"))
        assertFalse(summaryJson("fixed", result, null).decodeToString().contains("error_groups"))
    }

    private companion object {
        const val HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
        const val TAIL = ",0,0,1,1,,0,0,0"

        val CLEAN_CSV = listOf(HEADER, "1767225600000,100,login,200,OK,t,text,true,$TAIL").joinToString("\n", postfix = "\n")

        val HOSTILE_CSV =
            listOf(
                HEADER,
                "1767225600000,100,login,200,OK,t,text,true,$TAIL",
                "1767225600100,120,login,503,Service Unavailable,t,text,false,$TAIL",
                "1767225600200,90,login,503,Service Unavailable,t,text,false,$TAIL",
                "1767225600300,80,search,500,Err,t,text,false,\"<script>alert(1)</script> & ]]> \"\"q\"\"\"$TAIL",
                "1767225600400,70,search,502,Err,t,text,false,\"----\"$TAIL",
                "1767225600500,70,pay,500,Err,t,text,false,\"line1\nline2‮\"$TAIL",
            ).joinToString("\n", postfix = "\n")
    }
}

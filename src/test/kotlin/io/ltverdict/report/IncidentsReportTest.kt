package io.ltverdict.report

import io.ltverdict.core.INCIDENT_OVERVIEW_LIMIT
import io.ltverdict.core.INCIDENT_STORED_MAX
import io.ltverdict.core.StagedResults
import io.ltverdict.core.synthesizeIncidents
import io.ltverdict.ingest.RunValidity
import io.ltverdict.integrations.report.renderConfluenceReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class IncidentsReportTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `results without incidents or with null incidents remain byte identical and omit the section`() {
        val outcome = StagedResults.analyze(tempDir.resolve("plain"), StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250)))
        val without = StagedResults.withoutIncidents(outcome.canonicalResult)
        val withNull =
            JsonObject(
                Json.parseToJsonElement(without.decodeToString()).jsonObject + ("incidents" to JsonNull),
            ).toString().encodeToByteArray()

        val htmlWithout = renderHtmlReport(without, "fixed")
        val htmlNull = renderHtmlReport(withNull, "fixed")
        val textWithout = htmlWithout.decodeToString()
        val textNull = htmlNull.decodeToString()

        assertFalse(textWithout.contains("<h2>Инциденты</h2>"))
        assertFalse(textNull.contains("<h2>Инциденты</h2>"))
        assertEquals(
            textWithout.substringBefore("<h2>Приложение</h2>"),
            textNull.substringBefore("<h2>Приложение</h2>"),
        )
    }

    @Test
    fun `incidents section is placed after overall summary and before error groups and tables`() {
        val outcome =
            StagedResults.analyze(
                tempDir.resolve("staged"),
                StagedResults.RAMP,
                StagedResults.policy(StagedResults.p95(190, """["steady"]""")),
                StagedResults.RAMP_STEADY_DOWN,
            )
        val html = renderHtmlReport(outcome.canonicalResult, "fixed", readErrorGroupsFile(outcome.analysisDirectory)).decodeToString()
        val headings = Regex("<h2>(.*?)</h2>").findAll(html).map { it.groupValues[1] }.toList()

        val overallIndex = headings.indexOf("Итого по прогону")
        val incidentsIndex = headings.indexOf("Инциденты")
        val rulesIndex = headings.indexOf("Правила")
        assertTrue(overallIndex >= 0, headings.toString())
        assertEquals(overallIndex + 1, incidentsIndex, headings.toString())
        assertTrue(incidentsIndex < rulesIndex, headings.toString())
        assertTrue(html.indexOf("<h2>Инциденты</h2>") < html.indexOf("<h2>Транзакции</h2>"))
        assertTrue(html.indexOf("<h2>Инциденты</h2>") < html.indexOf("<h2>Приложение</h2>"))
    }

    @Test
    fun `NOT_EVALUATED and empty EVALUATED states render explicit Russian messages`() {
        val notEvaluated = synthesizeIncidents(RunValidity.INVALID, emptyList(), emptyList())
        val emptyEvaluated = synthesizeIncidents(RunValidity.VALID, emptyList(), emptyList())

        val htmlInvalid = renderHtmlReport(resultWithIncidents(notEvaluated, validity = "INVALID"), "fixed").decodeToString()
        val htmlEmpty = renderHtmlReport(resultWithIncidents(emptyEvaluated), "fixed").decodeToString()

        assertTrue(htmlInvalid.contains("<h2>Инциденты</h2>"))
        assertTrue(htmlInvalid.contains("Синтез инцидентов не выполнялся: прогон непригоден (<code>RUN_NOT_VALID</code>)."))
        assertTrue(htmlEmpty.contains("<h2>Инциденты</h2>"))
        assertTrue(htmlEmpty.contains("Инцидентов не найдено; пустой список не означает PASS."))
    }

    @Test
    fun `all stored incidents up to 64 are shown with limits, plain code ids, collapsed details and intervals`() {
        val findings = mutableListOf<JsonObject>()
        val evidence = mutableListOf<JsonObject>()
        evidence +=
            obj(
                "type" to "window_policy_summary",
                "id" to "wps-steady",
                "window_id" to "steady",
                "from_epoch_ms" to 1_767_225_600_000L,
                "to_epoch_ms" to 1_767_225_660_000L,
            )
        evidence += obj("type" to "resource_binding", "id" to "rb-1")

        // 4 transactions + 66 resources -> 64 stored, 6 omitted; top 4 transactions + overlapping resources yield both SAME_WINDOW and INTERVAL_OVERLAP.
        repeat(4) { index ->
            val evId = "pc-%02d".format(index)
            evidence +=
                obj(
                    "type" to "policy_check",
                    "id" to evId,
                    "window_id" to "steady",
                    "status" to "FAIL",
                    "scope" to obj("kind" to "transaction", "label" to "tx-%02d".format(index)),
                )
            findings += obj("type" to "policy_failure", "id" to "pf-%02d".format(index), "window_id" to "steady", "evidence_id" to evId)
        }
        repeat(66) { index ->
            val evId = "rc-%02d".format(index)
            evidence +=
                obj(
                    "type" to "resource_policy_check",
                    "id" to evId,
                    "window_id" to "steady",
                    "effect" to "sla",
                    "status" to "FAIL",
                )
            findings +=
                obj(
                    "type" to "resource_threshold_violation",
                    "id" to "rf-%02d".format(index),
                    "window_id" to "steady",
                    "entity" to "pod-%02d".format(index),
                    "from_epoch_ms" to 1_767_225_610_000L,
                    "to_epoch_ms" to 1_767_225_620_000L,
                    "evidence_id" to evId,
                )
        }
        repeat(20) { extra ->
            val evId = "pc-00-extra-%02d".format(extra)
            evidence +=
                obj(
                    "type" to "policy_check",
                    "id" to evId,
                    "window_id" to "steady",
                    "status" to "FAIL",
                    "scope" to obj("kind" to "transaction", "label" to "tx-00"),
                )
            findings +=
                obj(
                    "type" to "policy_failure",
                    "id" to "pf-00-extra-%02d".format(extra),
                    "window_id" to "steady",
                    "evidence_id" to evId,
                )
        }

        val incidents = synthesizeIncidents(RunValidity.VALID, findings, evidence)
        val html = renderHtmlReport(resultWithIncidents(incidents, findings, evidence), "fixed").decodeToString()
        val section = html.substringAfter("<h2>Инциденты</h2>").substringBefore("</section>")

        assertTrue(section.contains("Найдено: 70, сохранено в отчёте: $INCIDENT_STORED_MAX. Не сохранено сверх лимита 64: 6."))
        assertEquals(INCIDENT_STORED_MAX, Regex("<tr>").findAll(section).count() - 1)
        assertEquals(INCIDENT_OVERVIEW_LIMIT, Regex("\\(в обзоре\\)").findAll(section).count())
        assertTrue(section.contains("<td>64</td>"))
        assertTrue(section.contains("часть идентификаторов не показана (лимит списка)"))
        assertTrue(section.contains("2026-01-01 00:00:00 UTC – 2026-01-01 00:01:00 UTC (окно)"))
        assertTrue(section.contains("2026-01-01 00:00:10 UTC – 2026-01-01 00:00:20 UTC (находки)"))
        assertTrue(section.contains("Совпадения:"))
        assertTrue(section.contains("одно окно"))
        assertTrue(section.contains("пересечение интервалов"))
        assertFalse(section.contains("<a href=\"#"))
        assertFalse(section.contains("<details open"))
        assertFalse(section.contains("lang=\"en\""))
        assertTrue(section.contains("<details><summary>Отрицательные свидетельства ("))
        assertTrue(section.contains("<details><summary>Следующие проверки ("))

        // UNKNOWN interval case
        val unknownFindings = listOf(obj("type" to "policy_failure", "id" to "pf-unk", "evidence_id" to "pc-unk"))
        val unknownEvidence =
            listOf(
                obj(
                    "type" to "policy_check",
                    "id" to "pc-unk",
                    "status" to "FAIL",
                    "scope" to obj("kind" to "overall"),
                ),
            )
        val unknownIncidents = synthesizeIncidents(RunValidity.VALID, unknownFindings, unknownEvidence)
        val unknownHtml =
            renderHtmlReport(
                resultWithIncidents(unknownIncidents, unknownFindings, unknownEvidence),
                "fixed",
            ).decodeToString()
        assertTrue(unknownHtml.substringAfter("<h2>Инциденты</h2>").substringBefore("</section>").contains("время нарушения не определено"))
    }

    @Test
    fun `untrusted strings are cleaned and escaped, CSP hash matches and headings are Russian`() {
        val hostileLabel = "<script>alert(1)</script>\u0000\n\u202E" + "я".repeat(2500)
        val hostileEntity = "<img onerror=alert(1)>\t\u0007host"
        val findings =
            listOf(
                obj("type" to "policy_failure", "id" to "pf<1>", "window_id" to "w\u00001", "evidence_id" to "pc<1>"),
                obj(
                    "type" to "resource_threshold_violation",
                    "id" to "rf<1>",
                    "window_id" to "w\u00001",
                    "entity" to hostileEntity,
                    "from_epoch_ms" to 1_767_225_600_000L,
                    "to_epoch_ms" to 1_767_225_610_000L,
                    "evidence_id" to "rc<1>",
                ),
            )
        val scopeTx =
            obj(
                "kind" to "transaction",
                "group_path" to JsonArray(listOf(JsonPrimitive("<b>grp</b>"))),
                "label" to hostileLabel,
            )
        val evidence =
            listOf(
                obj(
                    "type" to "window_policy_summary",
                    "id" to "wps-1",
                    "window_id" to "w\u00001",
                    "from_epoch_ms" to 1_767_225_600_000L,
                    "to_epoch_ms" to 1_767_225_660_000L,
                ),
                obj(
                    "type" to "policy_check",
                    "id" to "pc<1>",
                    "window_id" to "w\u00001",
                    "status" to "FAIL",
                    "scope" to scopeTx,
                ),
                obj("type" to "resource_policy_check", "id" to "rc<1>", "window_id" to "w\u00001", "effect" to "sla", "status" to "FAIL"),
            )

        val incidents = synthesizeIncidents(RunValidity.VALID, findings, evidence)
        val html = renderHtmlReport(resultWithIncidents(incidents, findings, evidence), "fixed").decodeToString()
        val human = html.substringBefore("<h2>Приложение</h2>")
        val incidentsHtml = html.substringAfter("<h2>Инциденты</h2>").substringBefore("</section>")

        assertFalse(html.contains("<script"))
        assertFalse(Regex("<[^>]*\\sstyle=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(Regex("<[^>]*\\son[a-z]+=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse(human.contains("lang=\"en\""))
        assertFalse(incidentsHtml.contains('\u0000'))
        assertFalse(incidentsHtml.contains('\u202E'))
        assertTrue(incidentsHtml.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertTrue(incidentsHtml.contains("&lt;img onerror=alert(1)&gt;"))
        assertTrue(incidentsHtml.contains("&lt;b&gt;grp&lt;/b&gt;"))

        val style = html.substringAfter("<style>").substringBefore("</style>")
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(style.encodeToByteArray()))
        assertTrue(html.contains("style-src 'sha256-$hash'"))

        val levels = Regex("<h([1-6])[ >]").findAll(html).map { it.groupValues[1].toInt() }.toList()
        levels.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 1, "h$a then h$b") }

        val allowed = Regex("LT Verdict|baseline|JSON|RPS|evidence")
        val owned =
            Regex("<h2>(.*?)</h2>").findAll(human).map { it.groupValues[1] } +
                Regex("<summary>(.*?)</summary>").findAll(human).map { it.groupValues[1] }
        owned.forEach { assertFalse(it.replace(allowed, "").contains(Regex("[A-Za-z]")), it) }
    }

    @Test
    fun `report incident constants contain no causal wording from verify_slice0`() {
        val source = Files.readString(Path.of("tools/verify_slice0.py"))
        val block = source.substringAfter("CAUSAL_WORDING = re.compile(").substringBefore("re.IGNORECASE")
        val pattern = Regex("r\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block).joinToString("") { it.groupValues[1] }
        val causal = Regex(pattern, RegexOption.IGNORE_CASE)

        assertTrue(INCIDENT_REPORT_TEXTS.isNotEmpty())
        for (text in INCIDENT_REPORT_TEXTS) {
            assertNull(causal.find(text), "Causal match in: $text")
        }
    }

    @Test
    fun `asciidoc and confluence reports do not add an incidents section`() {
        val outcome =
            StagedResults.analyze(
                tempDir.resolve("formats"),
                StagedResults.RAMP,
                StagedResults.policy(StagedResults.p95(250)),
            )
        val groups = readErrorGroupsFile(outcome.analysisDirectory)
        val adoc = renderAsciiDocReport(outcome.canonicalResult, "fixed", groups).decodeToString()
        val confluence = renderConfluenceReport(outcome.canonicalResult, "fixed", groups).decodeToString()

        assertFalse(adoc.contains("== Инциденты"))
        assertFalse(confluence.contains("<h2>Инциденты</h2>"))
    }

    @Test
    fun `real engine analysis with incidents renders deterministically`() {
        val outcome =
            StagedResults.analyze(
                tempDir.resolve("real"),
                StagedResults.RAMP,
                StagedResults.policy(StagedResults.p95(190, """["steady"]""")),
                StagedResults.RAMP_STEADY_DOWN,
            )
        val first = renderHtmlReport(outcome.canonicalResult, "fixed", readErrorGroupsFile(outcome.analysisDirectory))
        val second = renderHtmlReport(outcome.canonicalResult, "fixed", readErrorGroupsFile(outcome.analysisDirectory))
        assertArrayEquals(first, second)

        val html = first.decodeToString()
        val section = html.substringAfter("<h2>Инциденты</h2>").substringBefore("</section>")
        assertTrue(
            section.contains("Нарушение SLA: весь прогон в окне steady") ||
                section.contains("Нарушение SLA: весь прогон в окне <code>steady</code>"),
        )
        assertTrue(section.contains("Высокий"))
        assertTrue(section.contains("<code>steady</code>"))
    }

    private fun resultWithIncidents(
        incidents: JsonObject,
        findings: List<JsonObject> = emptyList(),
        evidence: List<JsonObject> = emptyList(),
        validity: String = "VALID",
    ): ByteArray =
        JsonObject(
            mapOf(
                "schema_version" to JsonPrimitive("analysis-result.v1"),
                "run_id" to JsonPrimitive("run-1"),
                "analysis_mode" to JsonPrimitive("standard"),
                "run_validity" to JsonPrimitive(validity),
                "policy_verdict" to JsonPrimitive("FAIL"),
                "analysis_coverage" to obj("status" to "COMPLETE", "reasons" to JsonArray(emptyList())),
                "findings" to JsonArray(findings),
                "evidence" to JsonArray(evidence),
                "incidents" to incidents,
            ),
        ).toString().encodeToByteArray()

    private fun obj(vararg entries: Pair<String, Any?>): JsonObject =
        JsonObject(
            entries.associate { (k, v) ->
                k to
                    when (v) {
                        null -> JsonNull
                        is JsonElement -> v
                        is String -> JsonPrimitive(v)
                        is Number -> JsonPrimitive(v)
                        is Boolean -> JsonPrimitive(v)
                        else -> error("unsupported value $v")
                    }
            },
        )
}

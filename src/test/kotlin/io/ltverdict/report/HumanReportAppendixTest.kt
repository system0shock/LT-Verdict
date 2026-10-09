package io.ltverdict.report

import io.ltverdict.core.StagedResults
import io.ltverdict.integrations.report.readRunTimeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

/**
 * W2.6 PR 3: Russian headings and the machine tail in the collapsed "Приложение". The snapshots in
 * fixtures/stages/report-html-before-ru-appendix were captured from origin/main BEFORE the change; they prove that the data did not move.
 */
class HumanReportAppendixTest {
    @TempDir
    lateinit var tempDir: Path

    private val legacyRoot = Path.of("fixtures/stages/report-html-before-ru-appendix")

    private val baseline =
        Json
            .parseToJsonElement(
                """{"baseline":{"run_id":"jmeter_jtl_csv-${"a".repeat(64)}","analysis_id":"${"b".repeat(64)}"},"comparability":"UNCONFIRMED",
                "scope":"whole_run","warnings":[],"metrics":[{"metric":"response_time_p95_ms","unit":"ms","baseline":"100","current":"125",
                "delta":"25","delta_percent":"25","reason":null,"percent_reason":null}]}""",
            ).jsonObject

    private val rich =
        """{"analysis_coverage":{"reasons":["why"],"status":"INCOMPLETE"},"evidence":[
        {"type":"metric_summary","id":"metric-summary-overall","scope":{"kind":"overall"},"sample_count":300,"error_count":3,
        "error_rate_ratio":{"numerator":3,"denominator":300},"throughput_rps":{"numerator":300,"denominator":60},
        "latency_ms":{"p50":50,"p95":180,"p99":240,"max":900}},
        {"type":"metric_summary","id":"metric-tx","scope":{"kind":"transaction","label":"GET /items","group_path":["Shop"],"sample_kind":"JMETER_SAMPLER"},
        "sample_count":120,"error_count":3,"error_rate_ratio":{"numerator":3,"denominator":120},"throughput_rps":{"numerator":120,"denominator":60},
        "latency_ms":{"p50":60,"p95":200,"p99":260,"max":900}},
        {"type":"policy_check","id":"check-1","rule_id":"items-p95","metric":"response_time_p95_ms","metric_evidence_id":"metric-tx",
        "operator":"lte","threshold":150,"observed":200,"status":"FAIL","sample_mode":"FULL","sample_count":120,"min_samples":30},
        {"type":"resource_binding","mode":"explicit_windows","clock_alignment":"not_verified_by_core","dropped_leading_cells":1},
        {"type":"resource_summary","id":"resource-1","series_id":"db-busy","metric":"busy","unit":"ratio","entity":"db-1","role":"database",
        "aggregation":"interval_mean","window_id":"evaluation","from_epoch_ms":0,"to_epoch_ms":4000,"expected_cells":4,"observed_cells":4,
        "missing_cells":0,"longest_gap_cells":0,"statistics":{"max":"0.99"},"reasons":[]},
        {"type":"window_policy_summary","id":"window-1","window_id":"evaluation","business_verdict":"FAIL","resource_verdict":"FAIL","verdict":"FAIL",
        "from_epoch_ms":0,"to_epoch_ms":4000},
        {"type":"resource_policy_check","id":"resource-check-1","effect":"diagnostic","operator":"gt","rule_id":"db-saturated","series_id":"db-busy",
        "status":"FAIL","threshold":"0.9","unit":"ratio","window_id":"evaluation"},
        {"type":"source_summary","id":"source-1","source":"prometheus"},
        {"type":"diagnostic_summary","id":"diag-1","uncertainty":"NOT_ESTIMATED"},
        {"type":"correlation_pair","id":"corr-1","pair_id":"rps~busy","raw_rho":"0.8","partial_rho":null},
        {"type":"anomaly_check","id":"anomaly-1","status":"NO_MATERIAL_CHANGE"},
        {"type":"window_metric_summary","id":"w-1","window_id":"evaluation","sample_count":10,"error_count":0,"error_rate_ratio":null,
        "throughput_rps":{"numerator":10,"denominator":5000},"latency_ms":{"p50":1,"p95":2,"p99":3,"max":4}}],
        "findings":[{"id":"finding-1","type":"policy_failure","rule_id":"items-p95","evidence_id":"check-1"}],
        "policy_verdict":"FAIL","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
            .replace("\n", "")
            .replace(Regex("\\s{2,}"), "")

    private fun scenarios(): Map<String, String> {
        val csv = Files.readAllBytes(Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl"))
        fun plain(
            name: String,
            input: ByteArray,
            policy: String?,
            stages: String? = null,
            comparison: JsonObject? = null,
        ): String {
            val outcome = StagedResults.analyze(tempDir.resolve(name), input, policy, stages)
            val groups = readErrorGroupsFile(outcome.analysisDirectory)
            val timeline = readRunTimeline(outcome.analysisDirectory)
            return renderHtmlReport(outcome.canonicalResult, "fixed", groups, comparison, timeline).decodeToString()
        }
        return linkedMapOf(
            "ramp-pass" to plain("ramp-pass", StagedResults.RAMP, StagedResults.policy(StagedResults.p95(100_000))),
            "ramp-fail" to plain("ramp-fail", StagedResults.RAMP, StagedResults.policy(StagedResults.p95(250))),
            "csv-no-policy" to plain("csv-no-policy", csv, null),
            "csv-baseline" to plain("csv-baseline", csv, null, comparison = baseline),
            "staged-fail-baseline" to
                plain(
                    "staged",
                    StagedResults.RAMP,
                    StagedResults.policy(StagedResults.p95(190, """["steady"]""")),
                    StagedResults.RAMP_STEADY_DOWN,
                    baseline,
                ),
            "rich" to renderHtmlReport(rich.encodeToByteArray(), "analysis-1").decodeToString(),
        )
    }

    private val sections = Regex("<h2>(.*?)</h2>")

    private fun h2(html: String) = sections.findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `the title and the h1 are Russian and the sections follow the order of a person reading`() {
        val all = scenarios()
        all.values.forEach { html ->
            assertTrue(html.contains("<html lang=\"ru\">"))
            assertTrue(html.contains("<title>Отчёт LT Verdict</title>"))
            assertTrue(html.contains("<h1>Отчёт LT Verdict</h1>"))
            assertEquals(1, Regex("<h1[ >]").findAll(html).count())
        }
        assertEquals(
            listOf(
                "Вердикт и причины", "Прогон", "Ошибки", "Правила", "Транзакции", "Изменения относительно baseline", "Ограничения",
                "Приложение",
            ),
            h2(all.getValue("csv-baseline")),
        )
        assertEquals(
            listOf(
                "Область вердикта", "Вердикт и причины", "Прогон", "Правила", "Транзакции", "Изменения относительно baseline", "Ограничения",
                "Приложение",
            ),
            h2(all.getValue("staged-fail-baseline")),
        )
        assertEquals(
            listOf("Вердикт и причины", "Диагностика ресурсов", "Ошибки", "Правила", "Транзакции", "Ограничения", "Приложение"),
            h2(all.getValue("rich")),
        )
    }

    @Test
    fun `the appendix holds every machine block as a closed details with a Russian summary and nothing machine stays outside`() {
        val rich = scenarios().getValue("rich")
        val appendix = rich.substringAfter("<h2>Приложение</h2>")
        val summaries = Regex("<details><summary>(.*?)</summary>").findAll(appendix).map { it.groupValues[1] }.toList()

        assertEquals(
            listOf(
                "Общие метрики и метрики транзакций", "Проверки правил, исходные данные", "Привязка ресурсов", "Сводки по ресурсам",
                "Итоги правил по окнам", "Проверки правил по ресурсам", "Получение источников", "Диагностический анализ", "Корреляции",
                "Проверки аномалий", "Метрики по окнам", "Находки", "Идентификаторы evidence", "Канонический JSON",
            ),
            summaries,
        )
        assertEquals(summaries.size, Regex("<details[ >]").findAll(rich).count())
        assertFalse(rich.contains("<details open"))
        assertFalse(rich.contains("<details lang"))
        val human = rich.substringBefore("<h2>Приложение</h2>")
        assertFalse(human.contains("lang=\"en\""), human)
        assertFalse(human.contains("analysis-result.v1"))
        assertFalse(human.contains("<pre>"))
        assertTrue(appendix.contains("<pre>"))
    }

    @Test
    fun `headings never skip a level and every renderer-owned title is Russian`() {
        val allowed = Regex("LT Verdict|baseline|JSON|RPS|evidence")
        scenarios().forEach { (name, html) ->
            val levels = Regex("<h([1-6])[ >]").findAll(html).map { it.groupValues[1].toInt() }.toList()
            levels.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 1, "$name: h$a then h$b") }
            val owned =
                h2(html) + Regex("<summary>(.*?)</summary>").findAll(html).map { it.groupValues[1] } +
                    Regex("<h1>(.*?)</h1>").findAll(html).map { it.groupValues[1] } +
                    Regex("<dt[^>]*>(.*?)</dt>").findAll(html).map { it.groupValues[1] }
            owned.forEach { assertFalse(it.replace(allowed, "").contains(Regex("[A-Za-z]")), "$name: $it") }
        }
    }

    @Test
    fun `the page stays offline - no script, no inline style attribute, the policy hash is made from the style as sent`() {
        scenarios().forEach { (name, html) ->
            assertFalse(html.contains("<script"), name)
            assertFalse(Regex("\\sstyle=").containsMatchIn(html), name)
            assertFalse(Regex("\\son[a-z]+=").containsMatchIn(html), name)
            val style = html.substringAfter("<style>").substringBefore("</style>")
            val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(style.encodeToByteArray()))
            assertTrue(html.contains("style-src 'sha256-$hash'"), name)
            assertTrue(style.contains("summary:focus-visible"), name)
        }
        assertTrue(scenarios().getValue("ramp-pass").substringAfter("<style>").substringBefore("</style>").contains("load-chart"))
    }

    private fun text(fragment: String) = fragment.replace(Regex("<[^>]+>"), "")

    private fun records(html: String): List<String> {
        val body = html.substringAfter("<main>").substringBefore("</main>")
        val out = mutableListOf<String>()
        Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { row ->
            out += "tr:" + Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).joinToString("|") {
                text(it.groupValues[1])
            }
        }
        Regex("<dt[^>]*>(.*?)</dt><dd[^>]*>(.*?)</dd>").findAll(body).forEach { out += "dl:${text(it.groupValues[1])}=${text(it.groupValues[2])}" }
        listOf("li", "p", "code", "pre", "h3").forEach { tag ->
            Regex("<$tag[ >](.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { out += "$tag:${text(it.groupValues[1])}" }
        }
        Regex("<figure>(.*?)</figure>", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { out += "figure:${it.groupValues[1]}" }
        return out
    }

    private val renames =
        listOf(
            "dl:Run=" to "dl:Идентификатор прогона=",
            "dl:Analysis=" to "dl:Идентификатор анализа=",
            "dl:Run validity=" to "dl:Валидность прогона=",
            "dl:Policy verdict=" to "dl:Вердикт политики=",
            "dl:Coverage=" to "dl:Покрытие данных=",
            "Правила по ресурсам перечислены ниже в разделе «Resource policy checks»." to
                "Правила по ресурсам перечислены в приложении, блок «Проверки правил по ресурсам».",
        )

    private fun renamed(record: String): String = renames.fold(record) { text, (old, new) -> text.replace(old, new) }

    private val addedByThisChange =
        setOf(
            "p:Сырые данные анализа для сверки и обработки программами; расшифровка есть в разделах выше. Блоки свёрнуты: раскройте нужный.",
        )

    private val sectionBlock = Regex("<section[^>]*><h2>(.*?)</h2>(.*?)</section>", RegexOption.DOT_MATCHES_ALL)
    private val detailsBlock = Regex("<details><summary>(.*?)</summary><div lang=\"en\">(.*?)</div></details>", RegexOption.DOT_MATCHES_ALL)

    private val appendixTitles =
        mapOf(
            "Overall and transaction metrics" to "Общие метрики и метрики транзакций",
            "Policy checks" to "Проверки правил, исходные данные",
            "Resource binding" to "Привязка ресурсов",
            "Resource summaries" to "Сводки по ресурсам",
            "Window policy outcomes" to "Итоги правил по окнам",
            "Resource policy checks" to "Проверки правил по ресурсам",
            "Source acquisition" to "Получение источников",
            "Diagnostic analysis" to "Диагностический анализ",
            "Correlations" to "Корреляции",
            "Anomaly checks" to "Проверки аномалий",
            "Window metrics" to "Метрики по окнам",
            "Findings" to "Находки",
            "Evidence IDs" to "Идентификаторы evidence",
            "Canonical JSON" to "Канонический JSON",
        )

    /** Title and raw body of every block; the order and the wrappers may change, a body may neither change nor move under another title. */
    private fun bodies(html: String): List<String> =
        (
            sectionBlock.findAll(html).filter { it.groupValues[1] != "Приложение" }.map { (appendixTitles[it.groupValues[1]] ?: it.groupValues[1]) + " " + it.groupValues[2] } +
                detailsBlock.findAll(html).map { it.groupValues[1] + " " + it.groupValues[2] }
        ).toList()

    private val headerRenames =
        renames.map { (old, new) -> if (old.startsWith("dl:")) "<dt>${old.removePrefix("dl:").removeSuffix("=")}</dt>" to "<dt>${new.removePrefix("dl:").removeSuffix("=")}</dt>" else old to new } +
            listOf("<h1 lang=\"en\">LT Verdict report</h1>" to "<h1>Отчёт LT Verdict</h1>", "<dl lang=\"en\">" to "<dl>")

    @Test
    fun `every block body is byte for byte the body captured before the change and nothing else was added to the page`() {
        scenarios().forEach { (name, html) ->
            val old = Files.readString(legacyRoot.resolve("$name.html"))
            assertTrue(bodies(html).size >= 7, name)
            assertEquals(bodies(old).map(::renamed).sorted(), bodies(html).sorted(), name)
            val oldRest = headerRenames.fold(sectionBlock.replace(old.substringAfter("<main>"), "")) { text, (from, to) -> text.replace(from, to) }
            assertEquals(oldRest, sectionBlock.replace(html.substringAfter("<main>"), ""), name)
        }
    }

    @Test
    fun `numbers, cells, verdicts and codes are the same as in the report captured before the change`() {
        scenarios().forEach { (name, html) ->
            val old = Files.readString(legacyRoot.resolve("$name.html"))
            val expected = records(old).map(::renamed).sorted()
            val actual = records(html).filterNot { it in addedByThisChange }.sorted()
            assertEquals(expected, actual, name)
        }
    }
}

package io.ltverdict.report

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.time.Duration

class HtmlReportTest {
    @Test
    fun `diagnostic sections expose uncertainty and escape evidence`() {
        val result =
            """
            {"evidence":[{"id":"d","type":"diagnostic_summary","uncertainty":"NOT_ESTIMATED"},
            {"id":"p","type":"correlation_pair","pair_id":"<script>bad</script>","raw_rho":"0.8","partial_rho":null},
            {"id":"a","type":"anomaly_check","status":"NO_MATERIAL_CHANGE"}],"findings":[]}
            """.trimIndent()
        val html = renderHtmlReport(result.encodeToByteArray(), "a").decodeToString()
        assertTrue(html.contains("<h2>Diagnostic analysis</h2>"))
        assertTrue(html.contains("<h2>Correlations</h2>"))
        assertTrue(html.contains("<h2>Anomaly checks</h2>"))
        assertTrue(html.contains("NOT_ESTIMATED"))
        assertTrue(html.contains("&lt;script&gt;bad&lt;/script&gt;"))
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `renders statuses metrics checks findings evidence and escapes acquired text`() {
        val html =
            render(
                """{"analysis_coverage":{"reasons":["why"],"status":"INCOMPLETE"},"evidence":[{"error_count":1,"error_rate_ratio":{"denominator":3,"numerator":1},"id":"metric-1","latency_ms":{"max":9,"p50":5,"p95":8,"p99":9},"sample_count":12345678901234567890,"scope":{"kind":"transaction","label":"</pre><script>alert(1)</script>&\"'"},"throughput_rps":{"denominator":3,"numerator":10},"type":"metric_summary"},{"id":"check-1","metric":"error_rate_ratio","observed":{"denominator":3,"numerator":1},"operator":"lte","rule_id":"rule-1","status":"FAIL","threshold":0.5,"type":"policy_check"}],"findings":[{"evidence_id":"check-1","id":"finding-1","rule_id":"rule-1","type":"policy_failure"}],"policy_verdict":"FAIL","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("<html lang=\"ru\">"))
        assertTrue(html.contains("<section lang=\"en\"><h2>Overall and transaction metrics</h2>"))
        assertTrue(html.contains("run-1"))
        assertTrue(html.contains("analysis-1"))
        assertTrue(html.contains("INCOMPLETE"))
        assertTrue(html.contains("12345678901234567890"))
        assertTrue(html.contains("10 / 3 rps"))
        assertTrue(html.contains("&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;&quot;&#39;"))
        assertFalse(html.contains("<script>alert(1)</script>"))
        assertTrue(html.contains("Content-Security-Policy"))
        assertTrue(html.contains("style-src 'sha256-"))
        assertFalse(html.contains("<script"))
    }

    @Test
    fun `renders no policy result with missing metrics as unavailable`() {
        val html =
            render(
                """{"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[],"findings":[],"policy_verdict":"NO_POLICY","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.contains("NO_POLICY"))
        assertTrue(html.contains("Overall and transaction metrics</h2><p>unavailable</p>"))
        assertFalse(html.contains("Samples: 0"))
        assertFalse(html.contains("Resource summaries"))
    }

    @Test
    fun `renders typed resource and window evidence with unavailable statistics safely`() {
        val html =
            render(
                """{"analysis_coverage":{"status":"COMPLETE"},"evidence":[{"clock_alignment":"not_verified_by_core","dropped_leading_cells":1,"mode":"explicit_windows","type":"resource_binding"},{"aggregation":"interval_mean","entity":"node-1","expected_cells":4,"from_epoch_ms":0,"id":"resource-1","longest_gap_cells":2,"metric":"cpu</p><script>alert(1)</script>","missing_cells":2,"observed_cells":2,"reasons":["NO_OBSERVATIONS"],"role":"system","series_id":"cpu-1","statistics":null,"to_epoch_ms":4000,"type":"resource_summary","unit":"ratio","window_id":"evaluation"},{"business_verdict":"FAIL","from_epoch_ms":0,"id":"window-1","resource_verdict":"NO_VERDICT","to_epoch_ms":4000,"type":"window_policy_summary","verdict":"NO_VERDICT","window_id":"evaluation"},{"effect":"sla","id":"resource-check-1","operator":"gt","reason":"missing <cells>","rule_id":"cpu-rule","series_id":"cpu-1","status":"NO_VERDICT","threshold":"0.8","type":"resource_policy_check","unit":"ratio","window_id":"evaluation"},{"id":"business-check-1","metric":"latency","status":"FAIL","type":"policy_check","window_id":"evaluation"}],"findings":[],"policy_verdict":"NO_VERDICT","run_id":"run-1","run_validity":"VALID"}"""
                    .encodeToByteArray(),
                "analysis-1",
            ).decodeToString()

        assertTrue(html.contains("Resource summaries"))
        assertTrue(html.contains("dropped_leading_cells: 1"))
        assertTrue(html.contains("Window policy outcomes"))
        assertTrue(html.contains("Resource policy checks"))
        assertTrue(html.contains("Statistics: unavailable"))
        assertTrue(html.contains("window_id: evaluation"))
        assertTrue(html.contains("&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;"))
        assertFalse(html.contains("<script>alert(1)</script>"))
    }

    @Test
    fun `window metrics show a null latency for an empty window and keep the older zero form readable`() {
        val result =
            """
            {"evidence":[
            {"id":"w-empty","type":"window_metric_summary","window_id":"empty","sample_count":0,"error_count":0,"error_rate_ratio":null,"throughput_rps":{"numerator":0,"denominator":5000},"latency_ms":{"p50": null,"p95": null,"p99": null,"max": null}},
            {"id":"w-old","type":"window_metric_summary","window_id":"old","sample_count":0,"error_count":0,"error_rate_ratio":null,"throughput_rps":{"numerator":0,"denominator":5000},"latency_ms":{"p50": 0,"p95": 0,"p99": 0,"max": 0}},
            {"id":"w-zero","type":"window_metric_summary","window_id":"zero","sample_count":1,"error_count":0,"error_rate_ratio":{"numerator":0,"denominator":1},"throughput_rps":{"numerator":1000,"denominator":5000},"latency_ms":{"p50": 0,"p95": 0,"p99": 0,"max": 0}}],"findings":[]}
            """.trimIndent()
        val html = render(result.encodeToByteArray(), "a").decodeToString()

        assertTrue(html.contains("<h2>Window metrics</h2>"))
        assertTrue(html.contains("latency_ms: {&quot;p50&quot;:null,&quot;p95&quot;:null,&quot;p99&quot;:null,&quot;max&quot;:null}"))
        assertTrue(html.contains("latency_ms: {&quot;p50&quot;:0,&quot;p95&quot;:0,&quot;p99&quot;:0,&quot;max&quot;:0}"))
        assertTrue(html.contains("error_rate_ratio: null"))
    }

    @Test
    fun `a failing rule shows threshold measured value status and the reason in words`() {
        val html = page("FAIL", listOf(check("checkout-p95", "FAIL", threshold = "2000", observed = "2340")))

        assertTrue(html.contains("checkout-p95"))
        assertTrue(Regex("2 000 мс").containsMatchIn(html))
        assertTrue(Regex("2 340 мс").containsMatchIn(html))
        assertTrue(html.contains("не более"))
        assertTrue(html.contains("Нарушение"))
        assertTrue(html.contains("<th scope=\"col\">Порог"))
        assertTrue(html.contains("Нарушено правил: 1 из 1"))
    }

    @Test
    fun `the verdict block comes before the metrics and names the no-policy limitation`() {
        val html = page("NO_POLICY")

        assertTrue(html.indexOf("Вердикт") < html.indexOf("Overall and transaction metrics"))
        assertTrue(html.indexOf("Ограничения") < html.indexOf("Overall and transaction metrics"))
        assertTrue(html.contains("Правила не заданы"))
    }

    @Test
    fun `a rule with no observed value and an unknown reason code stays readable`() {
        val html = page("NO_VERDICT", listOf(check("r", "NO_VERDICT", observed = null, reason = "FUTURE_CODE")))

        assertTrue(html.contains("FUTURE_CODE"))
        assertTrue(html.contains("Причина без расшифровки в этой версии отчёта."))
        assertTrue(html.contains("нет данных"))
    }

    @Test
    fun `reason codes of the result and the coverage are explained in words`() {
        val html =
            page(
                "NO_VERDICT",
                listOf(check("r", "NO_VERDICT", observed = null, reason = "METRIC_NOT_AVAILABLE")),
                coverageStatus = "INCOMPLETE",
                coverageReasons = listOf("SOURCE_ACQUISITION_PARTIAL"),
            )

        assertTrue(html.contains("Онлайн-источник вернул данные не по всем запросам."))
        assertTrue(html.contains("Для правила нет данных"))
        assertTrue(html.contains("Покрытие данных неполное"))
    }

    @Test
    fun `hostile transaction names and rule ids are escaped and the page stays offline`() {
        val html =
            page(
                "FAIL",
                listOf(
                    check(
                        "<img src=x onerror=alert(2)>",
                        "FAIL",
                        scope = """{"kind":"transaction","label":${q("</td><script>alert(1)</script>&\"'")}}""",
                    ),
                ),
                listOf(tx(0, label = "</td><script>alert(1)</script>&\"'")),
            )

        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;/td&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;&quot;&#39;"))
        assertTrue(html.contains("default-src 'none'"))
        assertFalse(html.contains("style=\""))
    }

    @Test
    fun `thousands of transactions are cut to the top rows with a remainder line`() {
        val html = page("NO_POLICY", emptyList(), (0 until 5000).map { tx(it) })
        val table = html.substringAfter("<h2>Транзакции</h2>").substringBefore("</table>")

        assertTrue(table.contains("и ещё 4800"))
        assertEquals(200 + 1, Regex("<tr>").findAll(table).count() - 1)
        assertTrue(html.length < 5_000_000)
    }

    @Test
    fun `transactions are ordered by impact and equal labels in different groups stay distinct`() {
        val html =
            page(
                "FAIL",
                listOf(check("slow", "FAIL", scope = """{"kind":"transaction","label":"pay","group_path":["B"]}""")),
                listOf(
                    tx(1, label = "pay", groupPath = listOf("A"), errors = 9),
                    tx(2, label = "pay", groupPath = listOf("B"), errors = 1),
                    tx(3, label = "view", errors = 5),
                ),
            )
        val table = html.substringAfter("<h2>Транзакции</h2>").substringBefore("</table>")

        assertTrue(table.indexOf("B / pay") < table.indexOf("A / pay"))
        assertTrue(table.indexOf("A / pay") < table.indexOf("view"))
        assertTrue(table.contains("Нарушение"))
    }

    @Test
    fun `defensive - a null latency or error rate in a transaction row prints as no data, not as null or NaN`() {
        val html =
            page("NO_POLICY", emptyList(), listOf(tx(0, latency = """{"p50":null,"p95":null,"p99":null,"max":null}""", errorRate = "null")))
        val rows = html.substringAfter("<h2>Транзакции</h2>").substringBefore("</table>")

        assertTrue(rows.contains("нет данных"))
        assertFalse(rows.contains("NaN"))
        assertFalse(rows.contains(">null<"))
    }

    @Test
    fun `an exact ratio that does not divide evenly and a zero denominator do not break the report`() {
        val html =
            page(
                "PASS",
                listOf(
                    check(
                        "third",
                        "PASS",
                        metric = "error_rate_ratio",
                        threshold = "0.5",
                        observed = """{"numerator":1,"denominator":3}""",
                    ),
                    check(
                        "zero",
                        "NO_VERDICT",
                        metric = "error_rate_ratio",
                        threshold = "0.5",
                        observed = """{"numerator":1,"denominator":0}""",
                    ),
                    check(
                        "rps",
                        "PASS",
                        metric = "throughput_rps",
                        threshold = "10",
                        operator = "gte",
                        observed = """{"numerator":25000,"denominator":2000}""",
                    ),
                ),
            )

        assertTrue(Regex("33,33 %").containsMatchIn(html))
        assertTrue(Regex("50 %").containsMatchIn(html))
        assertTrue(Regex("12,5 RPS").containsMatchIn(html))
        assertTrue(html.contains("не менее"))
        assertTrue(html.substringAfter(">zero<").substringBefore("</tr>").contains("нет данных"))
    }

    @Test
    fun `the window metric list keeps printing a null latency of an empty window as null as before`() {
        val html =
            render(
                """{"evidence":[{"id":"w","type":"window_metric_summary","window_id":"empty","latency_ms":{"p50":null,"p95":null,"p99":null,"max":null}}],"findings":[]}"""
                    .encodeToByteArray(),
                "a",
            ).decodeToString()

        assertTrue(html.contains("&quot;p95&quot;:null"))
    }

    @Test
    fun `rounding does not turn a violation into equality`() {
        val html = page("FAIL", listOf(check("r", "FAIL", threshold = "2000", observed = "2000.004")))
        val row = html.substringAfter(">r<").substringBefore("</tr>")

        assertTrue(Regex("2 000,004").containsMatchIn(row))
    }

    @Test
    fun `an absurd exponent or a null reason in the result neither hangs nor prints null`() {
        val html =
            page(
                "NO_VERDICT",
                listOf(check("huge", "NO_VERDICT", threshold = "1e100000000", observed = "1e-100000000")),
                coverageStatus = "INCOMPLETE",
                rawCoverageReasons = "null,5",
            )

        assertTrue(html.substringAfter(">huge<").substringBefore("</tr>").contains("нет данных"))
        assertFalse(html.contains("<code>null</code>"))
        assertFalse(html.contains("<code>5</code>"))
    }

    @Test
    fun `an unknown metric is printed without an invented unit`() {
        val html = page("PASS", listOf(check("custom", "PASS", metric = "custom_metric", threshold = "5", observed = "3")))
        val row = html.substringAfter(">custom<").substringBefore("</tr>")

        assertTrue(row.contains("custom_metric"))
        assertFalse(row.contains("мс"))
    }

    @Test
    fun `many transactions with many windowed checks are matched without a full scan per row`() {
        val checks =
            (0 until 3000).map {
                val scope = """{"kind":"transaction","label":"tx-$it","group_path":[]}"""
                check("w-$it", if (it == 2999) "FAIL" else "PASS", scope = scope, windowId = "w")
            }
        val html =
            assertTimeoutPreemptively(Duration.ofSeconds(20), ThrowingSupplier { page("FAIL", checks, (0 until 3000).map { tx(it) }) })
        val table = html.substringAfter("<h2>Транзакции</h2>").substringBefore("</table>")

        assertTrue(table.indexOf("tx-2999") < table.indexOf("tx-0<"))
    }

    @Test
    fun `a small sample is marked in the rule row`() {
        val html =
            page(
                "PASS",
                listOf(
                    check("r", "PASS", sample = """"sample_count":30,"sample_floor":20,"min_samples":50,"sample_mode":"SMALL_SAMPLE""""),
                ),
            )

        assertTrue(html.substringAfter(">r<").substringBefore("</tr>").contains("30 из 50 · малая выборка"))
    }

    @Test
    fun `the diagnostics limitation appears only when the result has diagnostic evidence`() {
        val without = page("NO_POLICY")
        val with =
            render(
                """{"evidence":[{"id":"d","type":"diagnostic_summary"}],"findings":[],"policy_verdict":"PASS","run_validity":"VALID","analysis_coverage":{"status":"COMPLETE","reasons":[]}}"""
                    .encodeToByteArray(),
                "a",
            ).decodeToString()

        assertFalse(without.contains("не доказывает причину"))
        assertTrue(with.contains("не доказывает причину"))
    }

    @Test
    fun `the report keeps its English title while the new blocks are Russian`() {
        val html = page("NO_POLICY")

        assertTrue(html.contains("<h1 lang=\"en\">LT Verdict report</h1>"))
        assertTrue(html.contains("<h2>Вердикт и причины</h2>"))
        assertTrue(html.contains("<h2>Правила</h2>"))
        assertTrue(html.contains("<section lang=\"en\"><h2>Canonical JSON</h2>"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<form"))
        assertFalse(html.contains("<base"))
    }

    @Test
    fun `a failed diagnostic resource rule appears in the Russian part and the limitations are not empty`() {
        val html = render(diagnosticResult("PASS"), "a").decodeToString()
        val russian = html.substringBefore("<section lang=\"en\"><h2>Overall and transaction metrics</h2>")
        val diagnostics = russian.substringAfter("<h2>Диагностика ресурсов</h2>").substringBefore("</section>")

        assertTrue(diagnostics.contains("db-saturated"))
        assertTrue(diagnostics.contains("ряд db-busy (db-1)"))
        assertTrue(diagnostics.contains("значение выше порога 0.9 ratio: наблюдалось 0.95–0.99"))
        assertTrue(diagnostics.contains("2026-09-21 14:20:00 UTC – 2026-09-21 14:21:00 UTC"))
        assertTrue(diagnostics.contains("интервалов нарушения: 2"))
        assertFalse(diagnostics.contains("fine-rule"))
        assertFalse(russian.contains("Ограничений, отмеченных в результате, нет"))
        assertTrue(russian.substringAfter("<h2>Ограничения</h2>").contains("не доказывает причину"))
    }

    @Test
    fun `a diagnostic rule that passed adds neither a diagnostics block nor a limitation`() {
        val html = render(diagnosticResult("PASS", failedStatus = "PASS"), "a").decodeToString()

        assertFalse(html.contains("Диагностика ресурсов"))
        assertTrue(html.contains("Ограничений, отмеченных в результате, нет"))
    }

    @Test
    fun `a failed verdict lists the violated rules as its reasons`() {
        val html =
            page(
                "FAIL",
                listOf(
                    check("checkout-p95", "FAIL", threshold = "2000", observed = "2340"),
                    check("fine", "PASS"),
                ),
            )
        val reasons = html.substringAfter("<h3>Причины</h3>").substringBefore("</section>")

        assertFalse(reasons.contains("Причины в результате не указаны"))
        assertTrue(reasons.contains("<code>checkout-p95</code>"))
        assertTrue(Regex("2.340.мс при пороге ≤ 2.000.мс").containsMatchIn(reasons))
        assertFalse(reasons.contains("fine"))
    }

    @Test
    fun `a failed resource sla rule is a reason of the failed verdict`() {
        val html = render(diagnosticResult("FAIL", effect = "sla"), "a").decodeToString()
        val reasons = html.substringAfter("<h3>Причины</h3>").substringBefore("</section>")

        assertTrue(reasons.contains("<code>db-saturated</code>"))
        assertTrue(reasons.contains("ряд db-busy (db-1)"))
        assertFalse(reasons.contains("Причины в результате не указаны"))
    }

    @Test
    fun `a passed capacity result counts stages and does not count failed rules under a confirmed headline`() {
        val html = render(capacityResult("PASS"), "a").decodeToString()
        val verdict = html.substringAfter("<h2>Вердикт и причины</h2>").substringBefore("<h3>Причины</h3>")

        assertTrue(verdict.contains("Ёмкость подтверждена"))
        assertTrue(verdict.contains("Нарушено ступеней: 1 из 3"))
        assertTrue(verdict.contains("Граница ёмкости: BOUNDED [95.745, 103.745) rps"))
        assertFalse(verdict.contains("Нарушено правил"))
    }

    @Test
    fun `a failed capacity result names the failed stages as its reason`() {
        val html = render(capacityResult("FAIL"), "a").decodeToString()
        val reasons = html.substringAfter("<h3>Причины</h3>").substringBefore("</section>")

        assertFalse(reasons.contains("Причины в результате не указаны"))
        assertTrue(reasons.contains("s3"))
    }

    @Test
    fun `an indeterminate capacity result explains its reason codes`() {
        val html = render(capacityResult("NO_VERDICT", reasons = """["CAPACITY_STAGE_NOT_VERIFIED"]"""), "a").decodeToString()
        val reasons = html.substringAfter("<h3>Причины</h3>").substringBefore("</section>")

        assertTrue(reasons.contains("<code>CAPACITY_STAGE_NOT_VERIFIED</code>"))
        assertFalse(reasons.contains("Причины в результате не указаны"))
    }

    private fun diagnosticResult(
        verdict: String,
        effect: String = "diagnostic",
        failedStatus: String = "FAIL",
    ): ByteArray {
        val text =
            """
            {"analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[
            {"id":"c1","type":"resource_policy_check","effect":"$effect","rule_id":"db-saturated","series_id":"db-busy","unit":"ratio","operator":"gt","threshold":"0.9","window_id":"w","status":"$failedStatus","reason":null},
            {"id":"c2","type":"resource_policy_check","effect":"diagnostic","rule_id":"fine-rule","series_id":"cpu","unit":"ratio","operator":"gt","threshold":"0.9","window_id":"w","status":"PASS","reason":null}],
            "findings":[
            {"id":"f1","type":"resource_threshold_violation","rule_id":"db-saturated","window_id":"w","series_id":"db-busy","entity":"db-1","unit":"ratio","from_epoch_ms":1790000400000,"to_epoch_ms":1790000460000,"cell_count":6,"observed_min":"0.95","observed_max":"0.99","evidence_id":"c1"},
            {"id":"f2","type":"resource_threshold_violation","rule_id":"db-saturated","window_id":"w","series_id":"db-busy","entity":"db-1","unit":"ratio","from_epoch_ms":1790000700000,"to_epoch_ms":1790000760000,"cell_count":6,"observed_min":"0.95","observed_max":"0.99","evidence_id":"c1"}],
            "policy_verdict":"$verdict","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}
            """.trimIndent()
        return text.encodeToByteArray()
    }

    private fun capacityResult(
        verdict: String,
        reasons: String = "[]",
    ): ByteArray {
        val stages =
            listOf("s1" to "PASS", "s2" to "PASS", "s3" to "FAIL").joinToString(",") { (id, stageVerdict) ->
                """{"id":"$id","verdict":"$stageVerdict","reasons":[]}"""
            }
        val rules = (1..12).joinToString(",") { check("r$it", if (it == 1) "FAIL" else "PASS") }
        val text =
            """
            {"analysis_mode":"capacity_step","analysis_coverage":{"reasons":[],"status":"COMPLETE"},"evidence":[$rules],"findings":[],
            "capacity_summary":{"stages":[$stages],"bound_type":"BOUNDED","lower_inclusive":95.745,"upper_exclusive":103.745,"unit":"rps","reasons":$reasons},
            "policy_verdict":"$verdict","run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}
            """.trimIndent()
        return text.encodeToByteArray()
    }

    private fun q(value: String): String = JsonPrimitive(value).toString()

    private fun check(
        ruleId: String,
        status: String,
        metric: String = "response_time_p95_ms",
        operator: String = "lte",
        threshold: String = "2000",
        observed: String? = "2340",
        reason: String? = null,
        scope: String? = null,
        sample: String? = null,
        windowId: String? = null,
    ): String =
        buildString {
            append(
                """{"id":${q("check-$ruleId")},"type":"policy_check","rule_id":${q(ruleId)},"metric":"$metric","operator":"$operator",""",
            )
            append(""""threshold":$threshold,"status":"$status"""")
            if (observed != null) append(""","observed":$observed""")
            if (reason != null) append(""","reason_code":${q(reason)}""")
            if (scope != null) append(""","scope":$scope""")
            if (windowId != null) append(""","window_id":${q(windowId)}""")
            if (sample != null) append(",$sample")
            append("}")
        }

    private fun tx(
        index: Int,
        label: String = "tx-$index",
        groupPath: List<String> = emptyList(),
        errors: Int = 0,
        latency: String = """{"p50":5,"p95":8,"p99":9,"max":9}""",
        errorRate: String = """{"numerator":$errors,"denominator":10}""",
    ): String =
        """{"id":"m-$index","type":"metric_summary","scope":{"kind":"transaction","label":${q(
            label,
        )},"group_path":[${groupPath.joinToString(",") { q(it) }}]},""" +
            """"sample_count":10,"error_count":$errors,"error_rate_ratio":$errorRate,"throughput_rps":{"numerator":10,"denominator":10},"latency_ms":$latency}"""

    private fun page(
        verdict: String,
        checks: List<String> = emptyList(),
        metrics: List<String> = emptyList(),
        coverageStatus: String = "COMPLETE",
        coverageReasons: List<String> = emptyList(),
        rawCoverageReasons: String = coverageReasons.joinToString(",") { q(it) },
    ): String {
        val evidence = (metrics + checks).joinToString(",")
        val coverage = """{"reasons":[$rawCoverageReasons],"status":"$coverageStatus"}"""
        val result =
            """{"analysis_coverage":$coverage,"evidence":[$evidence],"findings":[],"policy_verdict":"$verdict",""" +
                """"run_id":"run-1","run_validity":"VALID","schema_version":"analysis-result.v1"}"""
        return render(result.encodeToByteArray(), "analysis-1").decodeToString()
    }

    private fun render(
        resultBytes: ByteArray,
        analysisId: String,
    ): ByteArray = renderHtmlReport(resultBytes, analysisId)
}

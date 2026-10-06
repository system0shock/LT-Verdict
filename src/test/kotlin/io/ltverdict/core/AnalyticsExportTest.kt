package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsExportTest {
    @Test
    fun `renders bounded dynamics rows in all required comparison formats with escaping and NA reasons`() {
        val dynamics = dynamics()

        val html =
            renderRunDynamicsExport(
                dynamics,
                AnalyticsExportFormat.HTML,
                historyScanTruncated = true,
                historyScanLimit = 1_000,
            ).decodeToString()
        val asciiDoc =
            renderRunDynamicsExport(
                dynamics,
                AnalyticsExportFormat.ASCIIDOC,
                historyScanTruncated = true,
                historyScanLimit = 1_000,
            ).decodeToString()
        val confluence =
            renderRunDynamicsExport(
                dynamics,
                AnalyticsExportFormat.CONFLUENCE,
                historyScanTruncated = true,
                historyScanLimit = 1_000,
            ).decodeToString()

        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("N/A (NO_PREVIOUS_RUN)"))
        assertTrue(html.contains("build&lt;unsafe&gt;"))
        assertFalse(html.contains("build<unsafe>"))
        assertTrue(asciiDoc.contains("[subs=specialchars]"))
        assertTrue(asciiDoc.contains("profile\\|one"))
        assertTrue(asciiDoc.contains("include::secret[]"))
        assertFalse(Regex("(?m)^(?:include|ifdef|endif)::").containsMatchIn(asciiDoc))
        assertTrue(asciiDoc.contains("20 (20%)"))
        assertTrue(confluence.contains("build&lt;unsafe&gt;"))
        assertFalse(confluence.contains("build<unsafe>"))
        listOf(html, asciiDoc, confluence).forEach {
            assertTrue(it.contains("not recalculated after row selection"))
            assertTrue(it.contains("history scan stopped at configured bounds", ignoreCase = true))
        }
    }

    @Test
    fun `a release label cannot break out of its cell in any export format`() {
        fun render(
            label: String,
            format: AnalyticsExportFormat,
        ) = renderRunDynamicsExport(dynamics(applicationVersion = label), format).decodeToString()

        val markup = "<img src=x onerror=alert(1)>"
        listOf(AnalyticsExportFormat.HTML, AnalyticsExportFormat.CONFLUENCE).forEach { format ->
            val text = render(markup, format)
            assertFalse(text.contains("<img"), format.name)
            assertTrue(text.contains("&lt;img src=x onerror=alert(1)&gt;"), format.name)
        }
        val asciiMarkup = render(markup, AnalyticsExportFormat.ASCIIDOC)
        assertTrue(asciiMarkup.contains("[subs=specialchars]"))
        assertTrue(asciiMarkup.contains("\"$markup\""))

        // a pipe stays inside the table cell, a run of dashes is fenced by a longer run
        assertTrue(render("a|b", AnalyticsExportFormat.ASCIIDOC).contains("a\\|b"))
        assertTrue(render("x----y", AnalyticsExportFormat.ASCIIDOC).contains("-----\n\"x----y\"\n-----\n"))
        assertTrue(render("a|b", AnalyticsExportFormat.HTML).contains("<td>a|b</td>"))

        // a line feed becomes an escape inside the AsciiDoc cell, so no line of the cell can start a directive
        val multiline = "first\ninclude::secret[]\n----\nlast"
        val asciiDoc = render(multiline, AnalyticsExportFormat.ASCIIDOC)
        assertFalse(asciiDoc.contains(multiline))
        assertTrue(asciiDoc.contains("first\\ninclude::secret[]\\n----\\nlast"))
        assertFalse(Regex("(?m)^(?:include|ifdef|endif)::").containsMatchIn(asciiDoc))
    }
}

private fun dynamics(applicationVersion: String = "1.0"): JsonObject =
    buildJsonObject {
        put("schema_version", "run-dynamics.v1")
        put("comparable_count", 2)
        put("excluded_incompatible_count", 1)
        put(
            "rows",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "reference",
                            buildJsonObject {
                                put("run_id", "jmeter_jtl_csv-${"a".repeat(64)}")
                                put("analysis_id", "b".repeat(64))
                            },
                        )
                        put("run_date", "2026-09-22T10:00:00Z")
                        put("jenkins_build", "build<unsafe>")
                        put("commit", "safe\ninclude::secret[]")
                        put("application_version", applicationVersion)
                        put("load_profile", "profile|one")
                        put("verdict", "PASS")
                        put(
                            "metrics",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("metric", "response_time_p95_ms")
                                        put("unit", "ms")
                                        put("value", "120")
                                        put("delta_previous", JsonNull)
                                        put("delta_previous_percent", JsonNull)
                                        put("previous_reason", "NO_PREVIOUS_RUN")
                                        put("delta_baseline", "20")
                                        put("delta_baseline_percent", "20")
                                        put("baseline_reason", JsonNull)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

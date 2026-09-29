package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
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
}

private fun dynamics() =
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
                        put("application_version", "1.0")
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

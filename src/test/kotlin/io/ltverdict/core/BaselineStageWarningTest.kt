package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** ADR 0030, R7: the comparison with a baseline warns when the metrics it compares are whole-run and a side has stages. */
class BaselineStageWarningTest {
    private val identity =
        buildJsonObject {
            put("source_type", "jmeter_jtl_csv")
            put("engine", buildJsonObject { put("id", "e") })
            put("parsers", JsonArray(emptyList()))
            put("modules", JsonArray(emptyList()))
            put("input_versions", buildJsonObject {})
            put("outputs", buildJsonObject {})
            put("histogram", buildJsonObject {})
            put("normalization", buildJsonObject {})
            put("limits", buildJsonObject {})
        }

    private fun result(staged: Boolean) =
        buildJsonObject {
            put("analysis_mode", "standard")
            put("run_validity", "VALID")
            put("policy_verdict", "PASS")
            put(
                "analysis_coverage",
                buildJsonObject {
                    put("status", "COMPLETE")
                    put("reasons", JsonArray(emptyList()))
                },
            )
            put(
                "evidence",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "metric_summary")
                            put("scope", buildJsonObject { put("kind", "overall") })
                            put(
                                "latency_ms",
                                buildJsonObject {
                                    put("p95", 100)
                                    put("p99", 110)
                                },
                            )
                            put(
                                "throughput_rps",
                                buildJsonObject {
                                    put("numerator", 100)
                                    put("denominator", 1)
                                },
                            )
                            put(
                                "error_rate_ratio",
                                buildJsonObject {
                                    put("numerator", 0)
                                    put("denominator", 1)
                                },
                            )
                        },
                    )
                    if (staged) {
                        add(
                            buildJsonObject {
                                put("id", "stage-binding")
                                put("type", "stage_binding")
                            },
                        )
                    }
                },
            )
        }

    private fun reference(letter: Char): JsonObject =
        buildJsonObject {
            put("run_id", "jmeter_jtl_csv-${letter.toString().repeat(64)}")
            put("analysis_id", letter.toString().repeat(64))
        }

    private fun warnings(
        baselineStaged: Boolean,
        currentStaged: Boolean,
    ): List<String> =
        compareAnalyses(
            manualBaselineSelection("release", reference('a')),
            reference('b'),
            result(baselineStaged),
            identity,
            result(currentStaged),
            identity,
        ).getValue("warnings").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `a stage binding on either side adds the whole run warning, none adds nothing`() {
        assertEquals(emptyList<String>(), warnings(baselineStaged = false, currentStaged = false))
        assertEquals(listOf("WHOLE_RUN_METRICS_WITH_STAGES"), warnings(baselineStaged = false, currentStaged = true))
        assertEquals(listOf("WHOLE_RUN_METRICS_WITH_STAGES"), warnings(baselineStaged = true, currentStaged = false))
        assertEquals(listOf("WHOLE_RUN_METRICS_WITH_STAGES"), warnings(baselineStaged = true, currentStaged = true))
    }
}

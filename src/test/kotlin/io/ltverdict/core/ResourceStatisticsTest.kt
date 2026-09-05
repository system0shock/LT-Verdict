package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ResourceStatisticsTest {
    @Test
    fun `type seven statistics match the accepted literals`() {
        val result = evaluateResources(snapshot(values = listOf("0", "1", "2", "3")), WINDOWS)
        val summary = result.evidence.single { it.string("type") == "resource_summary" }
        val statistics = summary.getValue("statistics").jsonObject

        assertEquals("1.5", statistics.string("mean"))
        assertEquals("1.5", statistics.string("median"))
        assertEquals("0.15", statistics.string("q05"))
        assertEquals("0.75", statistics.string("q25"))
        assertEquals("2.25", statistics.string("q75"))
        assertEquals("2.85", statistics.string("q95"))
        assertEquals("1.5", statistics.string("iqr"))
        assertEquals("1", statistics.string("mad"))
        assertEquals("1.290994448735805628393088466594133", statistics.string("sample_standard_deviation"))
        assertEquals("0.1", statistics.string("slope_per_second"))
        assertEquals("2", statistics.string("split_half_shift"))
        assertEquals(4, summary.int("expected_cells"))
        assertEquals(4, summary.int("observed_cells"))
        assertEquals(0, summary.int("missing_cells"))
        assertEquals(0, summary.int("longest_gap_cells"))
    }

    @Test
    fun `gaps keep grid time and unsupported small samples remain null`() {
        val gapped = evaluateResources(snapshot(values = listOf("1", null, "3", null)), WINDOWS)
        val summary = gapped.evidence.single { it.string("type") == "resource_summary" }
        val statistics = summary.getValue("statistics").jsonObject
        val empty = evaluateResources(snapshot(values = listOf(null, null, null, null)), WINDOWS)
        val emptySummary = empty.evidence.single { it.string("type") == "resource_summary" }

        assertEquals("0.1", statistics.string("slope_per_second"))
        assertEquals("2", statistics.string("split_half_shift"))
        assertEquals(2, summary.int("observed_cells"))
        assertEquals(2, summary.int("missing_cells"))
        assertEquals(1, summary.int("longest_gap_cells"))
        assertEquals(listOf("RESOURCE_GAPS"), summary.array("reasons"))
        assertEquals(listOf("RESOURCE_GAPS"), gapped.coverageReasons)
        assertEquals(PolicyVerdict.NO_POLICY, gapped.windowVerdicts.getValue("steady"))
        assertEquals(listOf("RESOURCE_GAPS", "NO_OBSERVATIONS"), emptySummary.array("reasons"))
        emptySummary
            .getValue("statistics")
            .jsonObject.values
            .forEach { assertEquals(JsonNull, it) }

        val single = evaluateResources(snapshot(values = listOf("1", null, null, null)), WINDOWS)
        val singleSummary = single.evidence.single { it.string("type") == "resource_summary" }
        val singleStatistics = singleSummary.getValue("statistics").jsonObject
        assertEquals(listOf("RESOURCE_GAPS", "INSUFFICIENT_OBSERVATIONS"), singleSummary.array("reasons"))
        assertEquals(JsonNull, singleStatistics["sample_standard_deviation"])
        assertEquals(JsonNull, singleStatistics["slope_per_second"])
        assertEquals(JsonNull, singleStatistics["split_half_shift"])
    }

    @Test
    fun `large offsets keep small variance and slope`() {
        val result =
            evaluateResources(
                snapshot(
                    values =
                        listOf(
                            "999999999999999900",
                            "999999999999999901",
                            "999999999999999902",
                            "999999999999999903",
                        ),
                ),
                WINDOWS,
            )
        val statistics =
            result.evidence
                .single { it.string("type") == "resource_summary" }
                .getValue("statistics")
                .jsonObject

        assertEquals("1.290994448735805628393088466594133", statistics.string("sample_standard_deviation"))
        assertEquals("0.1", statistics.string("slope_per_second"))
    }

    @Test
    fun `gap splits threshold runs while observed violations remain evidence`() {
        val result =
            evaluateResources(
                snapshot(
                    values = listOf("0.9", null, "0.9", "0.95"),
                    rules = listOf(rule("high", ResourceRuleEffect.SLA)),
                ),
                WINDOWS,
            )
        val check = result.evidence.single { it.string("type") == "resource_policy_check" }
        val finding = result.findings.single()

        assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"))
        assertEquals("NO_VERDICT", check.string("status"))
        assertEquals("MISSING_RESOURCE_CELLS", check.string("reason"))
        assertEquals(2, finding.int("cell_count"))
        assertEquals(20_000L, finding.long("from_epoch_ms"))
        assertEquals(40_000L, finding.long("to_epoch_ms"))
        assertEquals("0.9", finding.string("observed_min"))
        assertEquals("0.95", finding.string("observed_max"))
    }

    @Test
    fun `sla pass fail missing and diagnostic-only verdicts are distinct`() {
        val pass =
            evaluateResources(
                snapshot(values = listOf("0.7", "0.7", "0.7", "0.7"), rules = listOf(rule("pass", ResourceRuleEffect.SLA))),
                WINDOWS,
            )
        val fail =
            evaluateResources(
                snapshot(values = listOf("0.9", "0.9", "0.7", "0.7"), rules = listOf(rule("fail", ResourceRuleEffect.SLA))),
                WINDOWS,
            )
        val missing =
            evaluateResources(
                snapshot(
                    values = listOf("0.7", "0.7", "0.7", "0.7"),
                    rules = listOf(rule("missing", ResourceRuleEffect.SLA, seriesId = "absent")),
                ),
                WINDOWS,
            )
        val diagnostic =
            evaluateResources(
                snapshot(
                    values = listOf("0.9", "0.9", "0.7", "0.7"),
                    rules = listOf(rule("diagnostic", ResourceRuleEffect.DIAGNOSTIC)),
                ),
                WINDOWS,
            )
        val noPolicy = evaluateResources(snapshot(values = listOf("0.7", "0.7", "0.7", "0.7")), WINDOWS)

        assertEquals(PolicyVerdict.PASS, pass.windowVerdicts.getValue("steady"))
        assertEquals(PolicyVerdict.FAIL, fail.windowVerdicts.getValue("steady"))
        assertEquals(PolicyVerdict.NO_VERDICT, missing.windowVerdicts.getValue("steady"))
        assertEquals(PolicyVerdict.NO_POLICY, diagnostic.windowVerdicts.getValue("steady"))
        assertEquals(1, diagnostic.findings.size)
        assertEquals(PolicyVerdict.NO_POLICY, noPolicy.windowVerdicts.getValue("steady"))
    }

    @Test
    fun `threshold findings fail closed at the bounded artifact limit`() {
        val values = List(20_002) { index -> if (index % 2 == 0) BigDecimal.ONE else BigDecimal.ZERO }
        val snapshot =
            ResourceSnapshotV1(
                "resource-snapshot.v1",
                "0".repeat(64),
                0,
                1_000,
                values.size,
                listOf(
                    ResourceSeriesV1(
                        "cpu",
                        "cpu_used",
                        "ratio",
                        "host-a",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        values,
                    ),
                ),
                listOf(ResourceWindowV1("window", 0, values.size * 1_000L)),
                listOf(
                    ResourceRuleV1(
                        "spikes",
                        "cpu",
                        "ratio",
                        ResourceOperator.GT,
                        BigDecimal("0.5"),
                        1,
                        ResourceRuleEffect.DIAGNOSTIC,
                    ),
                ),
                null,
            )

        val failure =
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
                evaluateResources(snapshot, snapshot.windows)
            }

        assertEquals("RESOURCE_FINDINGS_LIMIT_EXCEEDED", failure.message)
    }

    private fun snapshot(
        values: List<String?>,
        rules: List<ResourceRuleV1> = emptyList(),
    ) = ResourceSnapshotV1(
        schemaVersion = "resource-snapshot.v1",
        loadInputSha256 = "0".repeat(64),
        startEpochMillis = 0,
        stepMillis = 10_000,
        pointCount = 4,
        series =
            listOf(
                ResourceSeriesV1(
                    id = "cpu",
                    metric = "cpu_used",
                    unit = "ratio",
                    entity = "host-a",
                    role = ResourceRole.SYSTEM,
                    aggregation = ResourceAggregation.INTERVAL_MEAN,
                    labels = emptyMap(),
                    values = values.map { it?.let(::BigDecimal) },
                ),
            ),
        windows = WINDOWS,
        rules = rules,
        provenance = null,
    )

    private fun rule(
        id: String,
        effect: ResourceRuleEffect,
        seriesId: String = "cpu",
    ) = ResourceRuleV1(id, seriesId, "ratio", ResourceOperator.GT, BigDecimal("0.8"), 2, effect)

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.content.toInt()

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    private fun JsonObject.array(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private companion object {
        val WINDOWS = listOf(ResourceWindowV1("steady", 0, 40_000))
    }
}

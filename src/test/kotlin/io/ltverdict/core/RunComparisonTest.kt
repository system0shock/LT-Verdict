package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class RunComparisonTest {
    @Test
    fun `legacy CSV analysis is excluded from dynamics of the new CSV identity`() {
        val oldIdentity =
            Json
                .parseToJsonElement(
                    Files.readString(Path.of("fixtures/slice1/identity/legacy-pre-adr-0016.v1.json")),
                ).jsonObject
        val input =
            AcceptedInput(
                runId = oldIdentity.getValue("run_id").jsonPrimitive.content,
                sourceType = SourceType.JMETER_CSV,
                sha256 = oldIdentity.getValue("input_sha256").jsonPrimitive.content,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val newIdentity = Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig()).decodeToString()).jsonObject
        val old = saved('a', "2026-09-01T10:00:00Z", 100).copy(identity = oldIdentity)
        val current = saved('b', "2026-09-02T10:00:00Z", 120).copy(identity = newIdentity)

        val dynamics = buildRunDynamics(current, listOf(old, current), current.reference)

        assertEquals(1, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int)
        assertEquals(1, dynamics.getValue("comparable_count").jsonPrimitive.int)
        assertEquals(listOf(current.reference), dynamics.getValue("rows").jsonArray.map { it.jsonObject.getValue("reference") })
    }

    @Test
    fun `dynamics keeps only analyses of the same arm`() {
        fun savedWithArm(
            suffix: Char,
            day: Int,
            arm: String?,
        ) = saved(suffix, "2026-09-0${day}T10:00:00Z", 100).copy(identity = identity(arm = arm))

        val current = savedWithArm('c', 3, "A")
        val sameArm = savedWithArm('a', 1, "A")
        val otherArm = savedWithArm('b', 2, "B")
        val noArm = savedWithArm('d', 4, null)

        val dynamics = buildRunDynamics(current, listOf(sameArm, otherArm, noArm, current), current.reference)
        assertEquals(2, dynamics.getValue("comparable_count").jsonPrimitive.int)
        assertEquals(2, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int)

        val withoutArm = buildRunDynamics(noArm, listOf(sameArm, otherArm, noArm), noArm.reference)
        assertEquals(1, withoutArm.getValue("comparable_count").jsonPrimitive.int)
        assertEquals(2, withoutArm.getValue("excluded_incompatible_count").jsonPrimitive.int)
    }

    @Test
    fun `diagnostics module version two analysis is excluded from dynamics of version three`() {
        val base =
            Json
                .parseToJsonElement(
                    Files.readString(Path.of("fixtures/slice1/identity/legacy-pre-adr-0016.v1.json")),
                ).jsonObject
        val input =
            AcceptedInput(
                runId = base.getValue("run_id").jsonPrimitive.content,
                sourceType = SourceType.JMETER_CSV,
                sha256 = base.getValue("input_sha256").jsonPrimitive.content,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val diagnostics =
            DiagnosticValidation.Valid(
                DiagnosticPlanV1("correlation-plan.v1", "0".repeat(64), emptyList(), emptyList()),
                "d".repeat(64),
                byteArrayOf(),
            )
        val current =
            Json
                .parseToJsonElement(
                    analysisIdentity(input, null, EngineConfig(), diagnostics = diagnostics).decodeToString(),
                ).jsonObject
        val old =
            JsonObject(
                current + (
                    "modules" to
                        JsonArray(
                            current.getValue("modules").jsonArray.map { module ->
                                val value = module.jsonObject
                                if (value.getValue("id").jsonPrimitive.content == "load-resource-diagnostics") {
                                    JsonObject(value + ("version" to JsonPrimitive("2")))
                                } else {
                                    value
                                }
                            },
                        )
                ),
            )
        val previous = saved('a', "2026-09-01T10:00:00Z", 100).copy(identity = old)
        val newest = saved('b', "2026-09-02T10:00:00Z", 120).copy(identity = current)

        val dynamics = buildRunDynamics(newest, listOf(previous, newest), newest.reference)

        assertEquals(1, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int)
        assertEquals(1, dynamics.getValue("comparable_count").jsonPrimitive.int)
    }

    @Test
    fun `dynamics keeps the newest ten exact-compatible local analyses and computes deltas`() {
        val saved =
            (0..11).map { index ->
                saved(
                    suffix = ('a'.code + index).toChar(),
                    startedAt = "2026-09-${(index + 1).toString().padStart(2, '0')}T10:00:00Z",
                    p95 = 100L + index * 10,
                    semantics = if (index == 0) "other" else "same",
                )
            }
        val current = saved.last()
        val baseline = saved[1].reference

        val dynamics = buildRunDynamics(current, saved.reversed(), baseline)
        val rows = dynamics.getValue("rows").jsonArray.map { it.jsonObject }

        assertEquals(10, rows.size)
        assertEquals(current.reference, rows.first().getValue("reference"))
        assertEquals(saved[2].reference, rows.last().getValue("reference"))
        assertEquals(11, dynamics.getValue("comparable_count").jsonPrimitive.int)
        assertEquals(1, dynamics.getValue("excluded_incompatible_count").jsonPrimitive.int)
        val newestP95 = rows.first().metric("response_time_p95_ms")
        assertEquals("210", newestP95.getValue("value").jsonPrimitive.content)
        assertEquals("10", newestP95.getValue("delta_previous").jsonPrimitive.content)
        assertEquals("100", newestP95.getValue("delta_baseline").jsonPrimitive.content)
        assertEquals(JsonNull, rows.last().metric("response_time_p95_ms").getValue("delta_previous"))
        assertEquals(
            "PREVIOUS_RUN_OUTSIDE_RESULT",
            rows
                .last()
                .metric("response_time_p95_ms")
                .getValue("previous_reason")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `dynamics reports unavailable baseline metrics without inventing zero`() {
        val baseline = saved('a', "2026-09-01T10:00:00Z", p95 = null)
        val current = saved('b', "2026-09-02T10:00:00Z", p95 = 120)

        val row =
            buildRunDynamics(current, listOf(baseline, current), baseline.reference)
                .getValue("rows")
                .jsonArray
                .first()
                .jsonObject
                .metric("response_time_p95_ms")

        assertEquals(JsonNull, row.getValue("delta_baseline"))
        assertEquals("MISSING_BASELINE_METRIC", row.getValue("baseline_reason").jsonPrimitive.content)
    }

    @Test
    fun `transaction comparison matches full scope and bounds filtered rows`() {
        val baseline =
            result(
                overallP95 = 100,
                transactions =
                    listOf(
                        transaction(listOf("checkout"), "submit", "REQUEST", 100, 10, 1),
                        transaction(listOf("payment"), "submit", "REQUEST", 200, 20, 2),
                        transaction(listOf("payment"), "confirm", "GROUP", 300, 30, 3),
                    ),
            )
        val current =
            result(
                overallP95 = 110,
                transactions =
                    listOf(
                        transaction(listOf("checkout"), "submit", "REQUEST", 120, 10, 1),
                        transaction(listOf("payment"), "submit", "REQUEST", 220, 20, 2),
                        transaction(listOf("payment"), "confirm", "GROUP", 330, 30, 3),
                    ),
            )

        val comparison = compareTransactions(baseline, identity(), current, identity(), filter = "submit", limit = 1)
        val row =
            comparison
                .getValue("rows")
                .jsonArray
                .single()
                .jsonObject

        assertEquals(2, comparison.getValue("matched_count").jsonPrimitive.int)
        assertTrue(comparison.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(
            "checkout",
            row
                .getValue("scope")
                .jsonObject
                .getValue("group_path")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals(
            "20",
            row
                .metric("response_time_p95_ms")
                .getValue("delta")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `transaction comparison exposes missing sides and incompatible semantics`() {
        val baseline = result(transactions = listOf(transaction(emptyList(), "only-baseline", "REQUEST", 100, 10, 0)))
        val current = result(transactions = listOf(transaction(emptyList(), "only-current", "REQUEST", 100, 10, 0)))
        val missingRows = compareTransactions(baseline, identity(), current, identity()).getValue("rows").jsonArray.map { it.jsonObject }

        assertEquals(2, missingRows.size)
        assertTrue(
            missingRows.any {
                it
                    .metric("response_time_p95_ms")
                    .getValue("reason")
                    .jsonPrimitive.content ==
                    "MISSING_CURRENT_TRANSACTION"
            },
        )
        assertTrue(
            missingRows.any {
                it
                    .metric("response_time_p95_ms")
                    .getValue("reason")
                    .jsonPrimitive.content ==
                    "MISSING_BASELINE_TRANSACTION"
            },
        )

        val incompatible = compareTransactions(baseline, identity("a"), baseline, identity("b"))
        assertFalse(incompatible.getValue("compatible").jsonPrimitive.boolean)
        assertEquals(
            "INCOMPATIBLE_METRIC_DEFINITION",
            incompatible
                .getValue(
                    "rows",
                ).jsonArray
                .single()
                .jsonObject
                .metric("response_time_p95_ms")
                .getValue("reason")
                .jsonPrimitive.content,
        )
    }
}

private fun JsonObject.metric(name: String): JsonObject =
    getValue("metrics")
        .jsonArray
        .single {
            it.jsonObject
                .getValue("metric")
                .jsonPrimitive.content == name
        }.jsonObject

private fun saved(
    suffix: Char,
    startedAt: String,
    p95: Long?,
    semantics: String = "same",
): SavedAnalysisForComparison =
    SavedAnalysisForComparison(
        reference = reference(suffix),
        run =
            buildJsonObject {
                put("schema_version", "run.v1")
                put("run_id", "jmeter_jtl_csv-${suffix.toString().repeat(64)}")
                put("analysis_mode", "standard")
                put("started_at", startedAt)
                put("ended_at", startedAt)
                put("inputs", buildJsonArray {})
            },
        result = result(overallP95 = p95),
        identity = identity(semantics),
        jenkinsBuild = "build-$suffix",
        commit = "commit-$suffix",
        applicationVersion = "1.$suffix",
        loadProfile = "steady",
    )

private fun reference(suffix: Char): JsonObject =
    buildJsonObject {
        put("run_id", "jmeter_jtl_csv-${suffix.toString().repeat(64)}")
        put("analysis_id", suffix.toString().repeat(64))
    }

private fun identity(
    semantics: String = "same",
    arm: String? = null,
): JsonObject =
    buildJsonObject {
        put("source_type", "jmeter_jtl_csv")
        put("engine", buildJsonObject { put("id", semantics) })
        put("parsers", JsonArray(emptyList()))
        put("modules", JsonArray(emptyList()))
        put("input_versions", buildJsonObject {})
        put("outputs", buildJsonObject {})
        put("histogram", buildJsonObject {})
        put("normalization", buildJsonObject {})
        put("limits", buildJsonObject {})
        arm?.let { put("resource_arm", it) }
    }

private fun result(
    overallP95: Long? = 100,
    transactions: List<JsonObject> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("analysis_mode", "standard")
        put("run_validity", "VALID")
        put("policy_verdict", "PASS")
        put(
            "evidence",
            buildJsonArray {
                add(metricSummary(buildJsonObject { put("kind", "overall") }, overallP95, 100, 1))
                transactions.forEach(::add)
            },
        )
    }

private fun transaction(
    groupPath: List<String>,
    label: String,
    sampleKind: String,
    p95: Long,
    samples: Long,
    errors: Long,
): JsonObject =
    metricSummary(
        buildJsonObject {
            put("kind", "transaction")
            put("group_path", buildJsonArray { groupPath.forEach { add(JsonPrimitive(it)) } })
            put("label", label)
            put("sample_kind", sampleKind)
        },
        p95,
        samples,
        errors,
    )

private fun metricSummary(
    scope: JsonObject,
    p95: Long?,
    samples: Long,
    errors: Long,
): JsonObject =
    buildJsonObject {
        put("id", "metric-${scope.hashCode()}")
        put("type", "metric_summary")
        put("scope", scope)
        put("sample_count", samples)
        put("error_count", errors)
        put("error_rate_ratio", ratio(errors, samples))
        put("throughput_rps", ratio(samples, 10))
        put(
            "latency_ms",
            buildJsonObject {
                put("p50", p95?.minus(20)?.coerceAtLeast(0)?.let(::JsonPrimitive) ?: JsonNull)
                put("p95", p95?.let(::JsonPrimitive) ?: JsonNull)
                put("p99", p95?.plus(10)?.let(::JsonPrimitive) ?: JsonNull)
                put("max", p95?.plus(20)?.let(::JsonPrimitive) ?: JsonNull)
            },
        )
    }

private fun ratio(
    numerator: Long,
    denominator: Long,
): JsonObject =
    buildJsonObject {
        put("numerator", numerator)
        put("denominator", denominator)
    }

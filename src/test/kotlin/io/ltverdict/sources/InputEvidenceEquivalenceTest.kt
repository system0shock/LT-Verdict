package io.ltverdict.sources

import io.ltverdict.core.ITEM_JSON
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRuleEffect
import io.ltverdict.core.ResourceRuleV1
import io.ltverdict.core.ResourceSnapshotV1
import io.ltverdict.core.ResourceWindowV1
import io.ltverdict.core.SourceQueryDocument
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.resourceBindingEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.URI
import kotlin.random.Random

/**
 * W2.1 slice 2c: the typed resource_binding and source_summary builders equal the hand-built ones they replaced (the frozen copies
 * in LegacyInputEvidence.kt) on a wide matrix: canonical bytes, `JsonObject ==`, every leaf as kotlinx writes it, and the outcome of
 * an input that fails. The producers that cannot be called on their own (the summaries inside the acquisition functions, the
 * opensearch_errors artifact) are covered by the snapshots taken before the change.
 */
class InputEvidenceEquivalenceTest {
    private fun leaves(
        path: String,
        element: JsonElement,
        out: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> leaves("$path.$key", value, out) }
            is JsonArray -> element.forEachIndexed { index, value -> leaves("$path[$index]", value, out) }
            else -> out += "$path=${Json.encodeToString(JsonElement.serializer(), element)}"
        }
    }

    private fun assertSame(
        label: String,
        old: Result<JsonObject>,
        new: Result<JsonObject>,
    ) {
        if (old.isFailure || new.isFailure) {
            assertEquals(
                old.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" },
                new.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" },
                "$label: the outcome of a failing input changed",
            )
            return
        }
        val before = old.getOrThrow()
        val after = new.getOrThrow()
        assertEquals(before, after, "$label: JsonObject")
        assertEquals(
            runCatching { canonicalJson(before).decodeToString() }.getOrElse { "FAILS: ${it.message}" },
            runCatching { canonicalJson(after).decodeToString() }.getOrElse { "FAILS: ${it.message}" },
            "$label: canonical bytes",
        )
        val oldLeaves = mutableListOf<String>().also { leaves("", before, it) }.sorted()
        val newLeaves = mutableListOf<String>().also { leaves("", after, it) }.sorted()
        assertEquals(oldLeaves, newLeaves, "$label: leaves")
    }

    @Test
    fun `resource binding equals the hand-built one on the matrix and on seeded random inputs`() {
        val cases = ScriptedSource.bindingCases().toMutableList()
        val random = Random(20261009)
        val extremes = longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, 0, -1, 1, 1_767_225_600_000, Long.MAX_VALUE / 2)

        fun pick(): Long =
            if (random.nextInt(4) ==
                0
            ) {
                extremes[random.nextInt(extremes.size)]
            } else {
                random.nextLong(-100_000, 3_000_000_000_000)
            }
        repeat(300) { index ->
            val explicit = random.nextBoolean()
            val windows = List(random.nextInt(0, 3)) { ResourceWindowV1("w$it", pick(), pick()) }
            val snapshot =
                ResourceSnapshotV1(
                    "resource-snapshot.v1",
                    ScriptedSource.HASH,
                    pick(),
                    random.nextLong(1, 120_000),
                    random.nextInt(0, 100_000),
                    emptyList(),
                    if (explicit) windows else emptyList(),
                    emptyList(),
                    null,
                )
            cases += ScriptedSource.Companion.BindingCase("random-$index", snapshot, windows, pick(), pick())
        }
        var successes = 0
        var failures = 0
        cases.forEach { case ->
            val old = runCatching { legacyResourceBindingEvidence(case.snapshot, case.windows, case.runStart, case.runEnd) }
            val new = runCatching { resourceBindingEvidence(case.snapshot, case.windows, case.runStart, case.runEnd) }
            if (old.isSuccess) successes++ else failures++
            assertSame("binding ${case.name}", old, new)
        }
        assertTrue(successes > 50 && failures > 5, "the matrix must reach both outcomes ($successes ok, $failures failing)")
    }

    private fun rule(id: String) = ResourceRuleV1(id, "cpu", "ratio", ResourceOperator.GT, BigDecimal("0.8"), 1, ResourceRuleEffect.SLA)

    private fun budget(
        max: Int?,
        attempts: Int,
        retries: Int,
        waits: List<Long>,
    ): SourceBudget =
        SourceBudget(max).also { budget ->
            repeat(attempts) { budget.reserveAttempt(false) }
            repeat(retries) { budget.reserveAttempt(true) }
            waits.forEach(budget::addThrottleWait)
        }

    @Test
    fun `source evidence of a profile equals the hand-built one on the matrix`() {
        val provenances =
            listOf(
                null,
                buildJsonObject { put("window_origin", "explicit") },
                buildJsonObject {
                    put("window_origin", "auto")
                    put("longest_idle_gap_ms", JsonNull)
                    put("detected_idle_gaps", 2)
                    put("step_origin", "auto")
                },
            )
        val queryLists =
            listOf(
                emptyList(),
                listOf(QueryEvidence("cpu", "SUCCESS", null, "a".repeat(64))),
                listOf(
                    QueryEvidence("cpu", "PARTIAL", "MISSING_SAMPLES", "b".repeat(64)),
                    QueryEvidence("mem", "MISSING", "EMPTY_RESULT", "c".repeat(64)),
                ),
                listOf(
                    QueryEvidence("cpu", "SUCCESS", null, "a".repeat(64)),
                    QueryEvidence("é\u0001", "FAILED", "SOURCE_HTTP_503", "d".repeat(64)),
                ),
                listOf(QueryEvidence("cpu", "FAILED", "X", "e".repeat(64)), QueryEvidence("mem", "FAILED", null, "f".repeat(64))),
            )
        val budgets =
            listOf(
                budget(null, 0, 0, emptyList()),
                budget(null, 3, 1, listOf(0, 5)),
                budget(2, 5, 0, listOf(Long.MAX_VALUE, Long.MAX_VALUE)),
                budget(0, 1, 0, listOf(-1)),
                budget(null, 70_000, 69_999, listOf(1_000_000_007)),
            )
        val rules = listOf("r1", "r2", "r3").map(::rule)
        val spans =
            listOf(
                emptyMap(),
                mapOf("r1" to 61_000L),
                mapOf("r2" to 1L, "r3" to 2_500L),
                mapOf("unknown" to 5_000L),
                mapOf(
                    "r1" to Long.MAX_VALUE,
                ),
            )
        var count = 0
        for (kind in SourceKind.entries) {
            for (transport in SourceTransport.entries) {
                for (arm in listOf(null, "blue", "é")) {
                    for (span in spans) {
                        val profile =
                            SourceProfile(
                                "p/é%",
                                kind,
                                transport,
                                URI.create("http://127.0.0.1:1"),
                                null,
                                queries = emptyList(),
                                rules = rules,
                                ruleSpansMillis = span,
                                arm = arm,
                            )
                        for (step in listOf(1_000L, 500L, 7_000L)) {
                            for ((index, provenance) in provenances.withIndex()) {
                                val request =
                                    SourceRequest(
                                        "p/é%",
                                        1_767_225_600_000,
                                        1_767_225_600_000 + 10 * step,
                                        step,
                                        windowProvenance = provenance,
                                    )
                                val queries = queryLists[(count + index) % queryLists.size]
                                val budget = budgets[(count / 3 + index) % budgets.size]
                                assertSame(
                                    "source evidence #$count $kind/$transport/$arm/$span/$step",
                                    runCatching { legacySourceEvidence(profile, request, budget, queries) },
                                    runCatching { sourceEvidence(profile, request, budget, queries) },
                                )
                                count++
                            }
                        }
                    }
                }
            }
        }
        // every query list, budget and provenance meets every other at least once through the rotating indices above
        assertTrue(count > 500, "count $count")
        // the exact request that overflows a span conversion fails the same way in both
        val overflow = SourceRequest("p", 0, Long.MAX_VALUE, Long.MAX_VALUE, windowProvenance = null)
        val profile =
            SourceProfile(
                "p",
                SourceKind.PROMETHEUS,
                SourceTransport.DIRECT,
                URI.create("http://127.0.0.1:1"),
                null,
                queries = emptyList(),
                rules = rules,
                ruleSpansMillis =
                    mapOf(
                        "r1" to Long.MAX_VALUE,
                    ),
            )
        assertSame(
            "overflow",
            runCatching { legacySourceEvidence(profile, overflow, budgets[0], queryLists[1]) },
            runCatching { sourceEvidence(profile, overflow, budgets[0], queryLists[1]) },
        )
    }

    @Test
    fun `the query entries of a profile summary round-trip through the typed document, and an unknown key is refused`() {
        val shapes =
            listOf(
                buildJsonObject {
                    put("id", "import")
                    put("status", "SUCCESS")
                },
                buildJsonObject {
                    put("id", "cpu")
                    put("status", "FAILED")
                    put("reason", "SOURCE_HTTP_503")
                    put("expression_sha256", "a".repeat(64))
                },
                buildJsonObject {
                    put("id", "cpu")
                    put("status", "SUCCESS")
                    put("expression_sha256", "a".repeat(64))
                },
                buildJsonObject {
                    put("id", "limit:SOURCE_RAW_ARTIFACT_LIMIT_EXCEEDED")
                    put("status", "FAILED")
                    put("reason", "SOURCE_RAW_ARTIFACT_LIMIT_EXCEEDED")
                },
            )
        shapes.forEach { shape ->
            val document = shape.toQueryDocument("qualified/é")
            val encoded = ITEM_JSON.encodeToJsonElement(SourceQueryDocument.serializer(), document)
            assertEquals(JsonObject(shape + ("id" to JsonPrimitive("qualified/é"))), encoded)
            assertEquals(
                canonicalJson(JsonObject(shape + ("id" to JsonPrimitive("qualified/é")))).decodeToString(),
                canonicalJson(encoded).decodeToString(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            buildJsonObject {
                put("id", "x")
                put("status", "SUCCESS")
                put("extra", "kept silently")
            }.toQueryDocument("x")
        }
        // an explicit null or a number would be turned into text by the typed document: refused as well
        listOf<JsonElement>(JsonNull, JsonPrimitive(5)).forEach { odd ->
            assertThrows(IllegalStateException::class.java) {
                JsonObject(mapOf("id" to JsonPrimitive("x"), "status" to JsonPrimitive("SUCCESS"), "reason" to odd)).toQueryDocument("x")
            }
        }
    }
}

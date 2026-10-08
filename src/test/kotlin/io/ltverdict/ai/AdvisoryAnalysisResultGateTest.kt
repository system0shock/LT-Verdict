package io.ltverdict.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * W2.1: the gate in front of AdvisoryEvidenceBuilder (accepted key set, schema_version, run_id) now takes its key set from the
 * typed model and its versions from a set. Every document must get the same outcome, with the same failure code, as under the
 * hand-written rules that this test freezes in [legacyGate].
 */
class AdvisoryAnalysisResultGateTest {
    private val runId = "run-1"
    private val sha = "a".repeat(64)

    private val base: Map<String, JsonElement> =
        Json
            .parseToJsonElement(
                """{"schema_version":"analysis-result.v1","run_id":"run-1","analysis_mode":"standard","run_validity":"VALID",""" +
                    """"policy_verdict":"PASS","analysis_coverage":{"status":"COMPLETE","reasons":[]},"findings":[],"evidence":[]}""",
            ).jsonObject

    private val optional: Map<String, JsonElement> = mapOf("capacity_summary" to Json.parseToJsonElement("""{"stages":[]}"""))

    /** Frozen rules of AdvisoryEvidenceBuilder.build before W2.1; null means the gate accepts. */
    private fun legacyGate(document: JsonObject): AdviceFailure? {
        val sets =
            setOf(
                base.keys,
                base.keys + "capacity_summary",
            )
        if (document.keys !in sets) return AdviceFailure.INVALID_ANALYSIS

        fun string(name: String): String {
            val value = document[name] as? JsonPrimitive ?: throw GateFailure(AdviceFailure.INVALID_OUTPUT)
            if (!value.isString) throw GateFailure(AdviceFailure.INVALID_OUTPUT)
            return value.content
        }
        return try {
            if (string("schema_version") != "analysis-result.v1" || string("run_id") != runId) AdviceFailure.INVALID_ANALYSIS else null
        } catch (failure: GateFailure) {
            failure.reason
        }
    }

    private class GateFailure(
        val reason: AdviceFailure,
    ) : RuntimeException()

    private fun outcome(document: JsonObject): AdviceFailure? =
        try {
            AdvisoryEvidenceBuilder.build(runId, sha, sha, document)
            null
        } catch (failure: AdviceValidationException) {
            failure.reason
        }

    @Test
    fun `every key subset and schema_version and run_id variant gets the old outcome and failure code`() {
        val universe = base + optional + mapOf("incidents" to JsonObject(emptyMap()), "extra" to JsonObject(emptyMap()))
        val names = universe.keys.toList()
        val variants =
            listOf<Pair<String, JsonElement?>>(
                "schema_version" to null,
                "schema_version" to JsonPrimitive("analysis-result.v2"),
                "schema_version" to JsonPrimitive(""),
                "schema_version" to JsonPrimitive(1),
                "schema_version" to JsonNull,
                "run_id" to JsonPrimitive("other"),
                "run_id" to JsonPrimitive(7),
            )
        var accepted = 0
        var checked = 0
        for (mask in 0 until (1 shl names.size)) {
            val document = LinkedHashMap<String, JsonElement>()
            names.forEachIndexed { index, name -> if (mask and (1 shl index) != 0) document[name] = universe.getValue(name) }
            variants.forEach { (field, value) ->
                val candidate = LinkedHashMap(document)
                if (value != null && field in candidate) candidate[field] = value
                if (value != null && field !in candidate) return@forEach
                val json = JsonObject(candidate)
                val expected = legacyGate(json)
                val actual = outcome(json)
                if (expected != null) {
                    assertEquals(expected, actual, "gate outcome for ${candidate.keys} $field=$value")
                } else {
                    // Past the gate the unchanged code runs on well-formed values, so the document is built.
                    assertEquals(null, actual, "accepted ${candidate.keys}")
                    accepted++
                }
                checked++
            }
        }
        assertTrue(accepted >= 2 && checked > 1000, "accepted=$accepted checked=$checked")
    }

    @Test
    fun `an explicit null capacity summary is still refused after the key check`() {
        val document = JsonObject(base + ("capacity_summary" to JsonNull))
        assertEquals(AdviceFailure.INVALID_ANALYSIS, outcome(document))
    }

    @Test
    fun `an old result without capacity summary and one with it are both read`() {
        assertEquals(null, outcome(JsonObject(base)))
        assertEquals(null, outcome(JsonObject(base + optional)))
    }
}

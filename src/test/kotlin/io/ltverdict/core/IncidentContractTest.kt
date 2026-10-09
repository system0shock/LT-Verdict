package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

// W3.7 PR 1, criteria 5 and 12 of ADR 0029. The JSON Schema keywords of incident.schema.json are enforced by the independent verifier
// tools/verify_slice0.py (the CI job "Slice 0 contracts" and tools/test_incident_fixtures.py, which also run the semantic rules over the
// golden outputs of the core). The Gradle job has no Python, so this test checks what it can from the Kotlin side: the files of the
// contract, the identifiers (the same canonical JSON as Python), and that the core output uses exactly the vocabulary of the schema.

class IncidentContractTest {
    private val schema = parse(Path.of("docs/contracts/incident/v1/incident.schema.json"))

    private fun parse(path: Path): JsonObject = Json.parseToJsonElement(Files.readString(path)).jsonObject

    private fun examples(kind: String): List<Pair<String, JsonObject>> =
        Files.list(Path.of("docs/contracts/incident/v1/examples/$kind")).use { stream ->
            stream.sorted().toList().map { it.fileName.toString() to parse(it) }
        }

    @Test
    fun `the examples exist and the identifiers of the valid ones are the SHA-256 of their canonical grouping key`() {
        assertTrue(examples("valid").size >= 8)
        assertTrue(examples("invalid").size >= 30)
        for ((name, document) in examples("valid")) {
            for (item in document.getValue("items").jsonArray.map { it.jsonObject }) {
                val key = item.getValue("grouping").jsonObject.getValue("key")
                assertEquals("incident-" + sha256Hex(canonicalJson(key)), item.getValue("id").jsonPrimitive.content, name)
            }
        }
    }

    @Test
    fun `the result schema refers to the incident schema by its id and does not require the field`() {
        val result = parse(Path.of("docs/contracts/result/v1/analysis-result.schema.json"))
        val id = schema.getValue("\$id").jsonPrimitive.content
        val property =
            result
                .getValue("properties")
                .jsonObject
                .getValue("incidents")
                .jsonObject

        assertEquals(setOf("\$ref"), property.keys)
        assertEquals(id, property.getValue("\$ref").jsonPrimitive.content)
        assertFalse(result.getValue("required").jsonArray.any { it.jsonPrimitive.content == "incidents" })
        assertEquals(
            false,
            result
                .getValue("additionalProperties")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun `the core output uses exactly the fields and the vocabulary of the schema`() {
        val top = schema.getValue("properties").jsonObject
        val definitions = schema.getValue("\$defs").jsonObject
        val incident =
            definitions
                .getValue("incident")
                .jsonObject
                .getValue("properties")
                .jsonObject
        val negative =
            definitions
                .getValue("negativeEvidence")
                .jsonObject
                .getValue("properties")
                .jsonObject
        val next =
            definitions
                .getValue("nextCheck")
                .jsonObject
                .getValue("properties")
                .jsonObject
        val coincidence =
            definitions
                .getValue("coincidence")
                .jsonObject
                .getValue("properties")
                .jsonObject
        val required =
            definitions
                .getValue("incident")
                .jsonObject
                .getValue("required")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        val priorities = enumOf(incident.getValue("priority"))
        val bases = enumOf(incident.getValue("interval_basis"))
        val checks = enumOf(negative.getValue("check"))
        val outcomes = enumOf(negative.getValue("outcome"))
        val nextChecks = enumOf(next.getValue("check"))
        val coincidenceBases = enumOf(coincidence.getValue("basis"))
        var items = 0

        for (case in Files
            .list(
                Path.of("fixtures/incidents"),
            ).use { stream -> stream.filter { Files.isDirectory(it) }.sorted().toList() }) {
            val input = parse(case.resolve("input.json"))
            val document =
                synthesizeIncidents(
                    RunValidity.valueOf(input.getValue("run_validity").jsonPrimitive.content),
                    input.getValue("findings").jsonArray.map { it.jsonObject },
                    input.getValue("evidence").jsonArray.map { it.jsonObject },
                )
            assertTrue(top.keys.containsAll(document.keys), case.toString())
            assertEquals(
                setOf("schema_version", "method", "status", "overview_limit", "total_count", "omitted_count", "items"),
                document.keys - "reason_code",
            )
            for (item in document.getValue("items").jsonArray.map { it.jsonObject }) {
                items++
                assertEquals(incident.keys, item.keys, case.toString())
                assertEquals(required, item.keys)
                assertTrue(item.getValue("priority").jsonPrimitive.content in priorities)
                assertTrue(item.getValue("interval_basis").jsonPrimitive.content in bases)
                for (entry in item.getValue("negative_evidence").jsonArray.map { it.jsonObject }) {
                    assertTrue(negative.keys.containsAll(entry.keys))
                    assertTrue(entry.getValue("check").jsonPrimitive.content in checks)
                    assertTrue(entry.getValue("outcome").jsonPrimitive.content in outcomes)
                }
                for (entry in item.getValue("next_checks").jsonArray.map { it.jsonObject }) {
                    assertEquals(next.keys, entry.keys)
                    assertTrue(entry.getValue("check").jsonPrimitive.content in nextChecks)
                }
                for (entry in item.getValue("coincident_with").jsonArray.map { it.jsonObject }) {
                    assertEquals(coincidence.keys, entry.keys)
                    assertTrue(entry.getValue("basis").jsonPrimitive.content in coincidenceBases)
                }
                // no field that states a cause, a confidence or an impact (ADR 0029, R7)
                assertTrue(listOf("confidence", "candidate_subsystem", "impact").none { it in item.keys })
                assertTrue(
                    item
                        .getValue("id")
                        .jsonPrimitive.content
                        .matches(Regex("incident-[0-9a-f]{64}")),
                )
                val interval = item.getValue("interval")
                val basis = item.getValue("interval_basis").jsonPrimitive.content
                assertEquals(basis == "UNKNOWN", interval == JsonNull)
                assertEquals(
                    false,
                    item
                        .getValue("rank")
                        .jsonPrimitive.content
                        .toInt() > 64,
                )
            }
        }
        assertTrue(items > 100, "the fixtures must exercise the schema: $items incidents")
    }

    @Test
    fun `the invalid examples are rejected by the verifier of the contract, not by chance`() {
        // verify_slice0.py asserts this in CI ("Slice 0 contracts"); here the names are checked against the files so none is lost.
        val names = examples("invalid").map { it.first }
        assertTrue("causal-title.json" in names && "id-is-not-grouping-key-hash.json" in names && "unknown-field-confidence.json" in names)
        for ((name, document) in examples("invalid")) {
            assertTrue(document is JsonObject && document.containsKey("items"), name)
        }
    }

    private fun enumOf(property: JsonElement): Set<String> =
        (property as JsonObject).getValue("enum").let { (it as JsonArray).map { value -> (value as JsonPrimitive).content }.toSet() }
}

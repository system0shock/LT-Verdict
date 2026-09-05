package io.ltverdict.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class FixtureManifestTest {
    private val root = Path.of("").toAbsolutePath().normalize()

    @Test
    fun `manifest owns the complete independently produced corpus`() {
        val manifestPath = root.resolve("fixtures/slice1/manifest.json")
        assertTrue(Files.isRegularFile(manifestPath), "missing fixture manifest")

        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        assertEquals("fixture-manifest.v1", manifest.getValue("schema_version").jsonPrimitive.content)

        val artifacts = manifest.getValue("artifacts").jsonArray.map { it.jsonObject }
        val artifactsByPath = artifacts.associateBy { it.getValue("path").jsonPrimitive.content }
        assertEquals(artifacts.size, artifactsByPath.size, "duplicate artifact path")
        assertEquals(requiredArtifacts, artifactsByPath.keys)

        artifactsByPath.forEach { (relativePath, artifact) ->
            val file = root.resolve(Path.of(relativePath)).normalize()
            assertTrue(file.startsWith(root), "artifact escapes repository: $relativePath")
            assertTrue(Files.isRegularFile(file), "missing artifact: $relativePath")

            val expectedHash = artifact.getValue("sha256").jsonPrimitive.content
            assertTrue(expectedHash.matches(Regex("[0-9a-f]{64}")), "invalid SHA-256: $relativePath")
            assertEquals(expectedHash, sha256(file), "SHA-256 mismatch: $relativePath")
        }

        assertPolicyExamples(manifest)
        assertParserCases(manifest)
    }

    private fun assertPolicyExamples(manifest: JsonObject) {
        val examples = manifest.getValue("policy_examples").jsonArray.map { it.jsonObject }
        val examplesByPath = examples.associateBy { it.getValue("path").jsonPrimitive.content }
        assertEquals(examples.size, examplesByPath.size, "duplicate policy example path")
        assertEquals(policyExamples, examplesByPath.keys)

        examplesByPath.forEach { (path, example) ->
            val schemaValid = example.getValue("schema_valid").jsonPrimitive.boolean
            val runtimeValid = example.getValue("runtime_valid").jsonPrimitive.boolean
            if (path.endsWith("valid/all-metrics.json")) {
                assertTrue(schemaValid && runtimeValid, path)
            } else {
                assertFalse(runtimeValid, path)
                assertTrue(
                    example
                        .getValue("expected_diagnostic")
                        .jsonPrimitive.content
                        .isNotBlank(),
                    "missing runtime diagnostic: $path",
                )
            }
        }

        val duplicate =
            examplesByPath.getValue(
                "docs/contracts/policy/v1/examples/invalid/duplicate-rule-id.json",
            )
        assertTrue(duplicate.getValue("schema_valid").jsonPrimitive.boolean)
        assertFalse(duplicate.getValue("runtime_valid").jsonPrimitive.boolean)
    }

    private fun assertParserCases(manifest: JsonObject) {
        val cases = manifest.getValue("parser_cases").jsonArray.map { it.jsonObject }
        val casesById = cases.associateBy { it.getValue("id").jsonPrimitive.content }
        assertEquals(cases.size, casesById.size, "duplicate parser case id")
        assertEquals(parserCases.keys, casesById.keys)

        casesById.forEach { (id, case) ->
            val (expectedInput, expectedOracle) = parserCases.getValue(id)
            assertEquals(expectedInput, case.getValue("input").jsonPrimitive.content)
            assertEquals(expectedOracle, case.getValue("oracle").jsonPrimitive.content)

            val producer = case.getValue("producer").jsonObject
            listOf("name", "version", "release_tag", "generation_command").forEach { field ->
                assertTrue(
                    producer
                        .getValue(field)
                        .jsonPrimitive.content
                        .isNotBlank(),
                    "$id: $field",
                )
            }
            assertFalse(
                producer
                    .getValue("name")
                    .jsonPrimitive.content
                    .contains("LT Verdict", ignoreCase = true),
            )

            val oracle = Json.parseToJsonElement(Files.readString(root.resolve(expectedOracle))).jsonObject
            val expected = case.getValue("expected").jsonObject
            assertEquals(expected, oracle, "$id: manifest expectation differs from oracle")
            assertOracle(expected, id)
        }
    }

    private fun assertOracle(
        oracle: JsonObject,
        id: String,
    ) {
        assertSummary(oracle.getValue("overall").jsonObject, "$id overall")
        val transactions = oracle.getValue("transactions").jsonArray
        assertTrue(transactions.isNotEmpty(), "$id: no exact transaction summaries")
        transactions.forEachIndexed { index, element ->
            val transaction = element.jsonObject
            transaction.getValue("group_path").jsonArray.forEach {
                assertTrue(it.jsonPrimitive.content.isNotBlank(), "$id transaction $index: blank group path")
            }
            assertTrue(
                transaction
                    .getValue("label")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            assertTrue(
                transaction
                    .getValue("kind")
                    .jsonPrimitive.content
                    .isNotBlank(),
            )
            assertSummary(transaction, "$id transaction $index")
        }
    }

    private fun assertSummary(
        summary: JsonObject,
        context: String,
    ) {
        val sampleCount = summary.getValue("sample_count").jsonPrimitive.long
        val errorCount = summary.getValue("error_count").jsonPrimitive.long
        assertTrue(sampleCount >= 0, "$context: negative sample count")
        assertTrue(errorCount in 0..sampleCount, "$context: invalid error count")

        val latency = summary.getValue("latency_ms").jsonObject
        assertEquals(setOf("p50", "p95", "p99", "max"), latency.keys, "$context: latency metrics")
        val values = listOf("p50", "p95", "p99", "max").map { latency.getValue(it).jsonPrimitive.long }
        assertTrue(values.all { it >= 0 }, "$context: negative latency")
        assertTrue(values.zipWithNext().all { (left, right) -> left <= right }, "$context: unordered percentiles")
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        val policyExamples =
            setOf(
                "docs/contracts/policy/v1/examples/valid/all-metrics.json",
                "docs/contracts/policy/v1/examples/invalid/empty-rules.json",
                "docs/contracts/policy/v1/examples/invalid/duplicate-rule-id.json",
                "docs/contracts/policy/v1/examples/invalid/unknown-field.json",
                "docs/contracts/policy/v1/examples/invalid/unknown-metric.json",
                "docs/contracts/policy/v1/examples/invalid/wrong-operator.json",
                "docs/contracts/policy/v1/examples/invalid/error-rate-out-of-range.json",
            )

        val parserCases =
            mapOf(
                "jmeter-csv-5.6.3" to
                    Pair(
                        "fixtures/slice1/jmeter/csv-5.6.3/input.jtl",
                        "fixtures/slice1/jmeter/csv-5.6.3/oracle.json",
                    ),
                "jmeter-xml-5.6.3" to
                    Pair(
                        "fixtures/slice1/jmeter/xml-5.6.3/input.xml",
                        "fixtures/slice1/jmeter/xml-5.6.3/oracle.json",
                    ),
                "gatling-text-3.9.5" to
                    Pair(
                        "fixtures/slice1/gatling/text-3.9.5/simulation.log",
                        "fixtures/slice1/gatling/text-3.9.5/oracle.json",
                    ),
                "gatling-text-3.12.0" to
                    Pair(
                        "fixtures/slice1/gatling/text-3.12.0/simulation.log",
                        "fixtures/slice1/gatling/text-3.12.0/oracle.json",
                    ),
                "gatling-binary-3.13.5" to
                    Pair(
                        "fixtures/slice1/gatling/binary-3.13.5/simulation.log",
                        "fixtures/slice1/gatling/binary-3.13.5/oracle.json",
                    ),
                "gatling-binary-3.15.1" to
                    Pair(
                        "fixtures/slice1/gatling/binary-3.15.1/simulation.log",
                        "fixtures/slice1/gatling/binary-3.15.1/oracle.json",
                    ),
            )

        val requiredArtifacts =
            policyExamples +
                parserCases.values.flatMap { listOf(it.first, it.second) } +
                setOf(
                    "docs/contracts/policy/v1/policy.schema.json",
                    "fixtures/slice1/normalization/spike-drop.jtl",
                    "fixtures/slice1/policies/pass.json",
                    "fixtures/slice1/policies/fail.json",
                    "fixtures/slice1/policies/missing-transaction.json",
                    "fixtures/slice1/security/dtd.xml",
                    "fixtures/slice1/security/xxe.xml",
                    "fixtures/slice1/security/entity-expansion.xml",
                    "fixtures/slice1/security/html-label.jtl",
                    "fixtures/slice1/identity/policy.canonical.json",
                    "fixtures/slice1/identity/analysis-identity.v1.json",
                    "fixtures/slice1/identity/analysis-identity.sha256",
                )
    }
}

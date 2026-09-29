package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class CanonicalJsonTest {
    @Test
    fun `canonical JSON sorts keys, preserves arrays and normalizes decimals`() {
        val source = Json.parseToJsonElement("""{"z":600.0,"a":[6e2,-0.0,0.0100]}""")

        assertEquals("""{"a":[600,0,0.01],"z":600}""", canonicalJson(source).decodeToString())
        assertEquals("600", canonicalDecimal(BigDecimal("6e2")))
        assertEquals("0", canonicalDecimal(BigDecimal("-0.000")))
        assertEquals("1" + "0".repeat(127), canonicalDecimal(BigDecimal("1e127")))
        assertThrows(IllegalArgumentException::class.java) {
            canonicalDecimal(BigDecimal("1e128"))
        }
    }

    @Test
    fun `analysis identity matches the committed canonical golden`() {
        val identityPath = Path.of("fixtures/slice1/identity/analysis-identity.v1.json")
        val policyPath = Path.of("fixtures/slice1/identity/policy.canonical.json")
        val expectedCanonical = Files.readString(identityPath).trimEnd().encodeToByteArray()
        val expectedHash = Files.readString(Path.of("fixtures/slice1/identity/analysis-identity.sha256")).trim()
        val element = Json.parseToJsonElement(Files.readString(identityPath))
        val policy = validPolicy(Files.readString(policyPath))

        val actualCanonical = canonicalJson(element)

        assertArrayEquals(expectedCanonical, actualCanonical)
        assertEquals(expectedHash, sha256Hex(actualCanonical))
        assertArrayEquals(Files.readString(policyPath).trimEnd().encodeToByteArray(), policy.canonicalBytes)
        assertEquals(
            element.jsonObject
                .getValue("policy_sha256")
                .jsonPrimitive.content,
            policy.sha256,
        )
    }

    @Test
    fun `numeric spellings share a policy hash while rule order remains significant`() {
        val spellings = listOf("6e2", "600.0", "600")
        val hashes = spellings.map { validPolicy(policyJson(it)).sha256 }
        assertEquals(1, hashes.toSet().size)

        val first = validPolicy(twoRulePolicy("first", "second")).sha256
        val reordered = validPolicy(twoRulePolicy("second", "first")).sha256
        assertNotEquals(first, reordered)
    }

    private fun validPolicy(source: String): PolicyValidation.Valid {
        val result = validatePolicy(ByteArrayInputStream(source.encodeToByteArray()))
        return result as PolicyValidation.Valid
    }

    private fun policyJson(threshold: String) =
        """{"schema_version":"policy.v1","policy_id":"p","rules":[{"id":"r","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,"scope":{"kind":"overall"}}]}"""

    private fun twoRulePolicy(
        first: String,
        second: String,
    ) = """{"schema_version":"policy.v1","policy_id":"p","rules":[${rule(first)},${rule(second)}]}"""

    private fun rule(id: String) =
        """{"id":"$id","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"}}"""
}

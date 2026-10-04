package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ResourceArmIdentityTest {
    private val inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val input =
        AcceptedInput(
            runId = "jmeter_jtl_csv-$inputHash",
            sourceType = SourceType.JMETER_CSV,
            sha256 = inputHash,
            sizeBytes = 1,
            originalFilename = "input.jtl",
            path = Path.of("unused"),
        )

    private fun resources(path: String) =
        validateResourceSnapshot(ByteArrayInputStream(Files.readAllBytes(Path.of(path)))) as ResourceValidation.Valid

    private fun identity(path: String) =
        Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig(), resources = resources(path)).decodeToString()).jsonObject

    @Test
    fun `resource_arm is written only for a snapshot with an arm`() {
        val with = identity("docs/contracts/resources/v1/examples/valid/arm.json")
        val without = identity("docs/contracts/resources/v1/examples/valid/basic.json")

        assertEquals("A", with.getValue("resource_arm").jsonPrimitive.content)
        assertFalse(without.containsKey("resource_arm"))
    }

    @Test
    fun `the identity of a snapshot without an arm keeps its committed bytes`() {
        val expected = Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity-resources.v1.json"))
        val policy =
            validatePolicy(
                ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/slice1/identity/policy.canonical.json"))),
            ) as PolicyValidation.Valid
        val actual =
            analysisIdentity(input, policy, EngineConfig(), resources = resources("docs/contracts/resources/v1/examples/valid/basic.json"))

        assertArrayEquals(expected, actual)
    }
}

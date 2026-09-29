package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class AnalysisResultGoldenTest {
    @Test
    fun `analysis identity matches the committed bytes and hash`() {
        val inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val input =
            AcceptedInput(
                runId = "jmeter_jtl_csv-$inputHash",
                sourceType = SourceType.JMETER_CSV,
                sha256 = inputHash,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val policy =
            validatePolicy(
                ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/slice1/identity/policy.canonical.json"))),
            ) as PolicyValidation.Valid
        val expected = Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity.v1.json"))
        val expectedHash =
            Files
                .readString(Path.of("fixtures/slice1/identity/analysis-identity.sha256"))
                .trim()

        val actual = analysisIdentity(input, policy, EngineConfig())

        assertArrayEquals(expected, actual)
        assertEquals(expectedHash, sha256Hex(actual))
    }

    @Test
    fun `analysis result is canonical typed and byte identical`() {
        val evaluation =
            PolicyEvaluation(
                verdict = PolicyVerdict.FAIL,
                coverageReasons = listOf("TRANSACTION_NOT_FOUND"),
                findings =
                    listOf(
                        Json.parseToJsonElement("""{"type":"policy_failure","id":"finding:rule-b"}""").jsonObject,
                    ),
                evidence =
                    listOf(
                        Json.parseToJsonElement("""{"type":"metric_summary","id":"metric:z"}""").jsonObject,
                        Json.parseToJsonElement("""{"type":"policy_check","id":"check:rule-b"}""").jsonObject,
                    ),
            )
        val expected =
            (
                """{"analysis_coverage":{"reasons":["TRANSACTION_NOT_FOUND"],"status":"INCOMPLETE"},""" +
                    """"analysis_mode":"standard","evidence":[{"id":"metric:z","type":"metric_summary"},""" +
                    """{"id":"check:rule-b","type":"policy_check"}],""" +
                    """"findings":[{"id":"finding:rule-b","type":"policy_failure"}],""" +
                    """"policy_verdict":"FAIL","run_id":"run-1","run_validity":"VALID",""" +
                    """"schema_version":"analysis-result.v1"}"""
            ).encodeToByteArray()

        val first = analysisResult("run-1", RunValidity.VALID, evaluation)
        val second = analysisResult("run-1", RunValidity.VALID, evaluation)
        val result = Json.parseToJsonElement(first.decodeToString()).jsonObject

        assertArrayEquals(expected, first)
        assertArrayEquals(first, second)
        assertEquals(
            setOf(
                "schema_version",
                "run_id",
                "analysis_mode",
                "run_validity",
                "policy_verdict",
                "analysis_coverage",
                "findings",
                "evidence",
            ),
            result.keys,
        )
        assertEquals(
            listOf("metric_summary", "policy_check"),
            result.getValue("evidence").jsonArray.map {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content
            },
        )
        assertEquals(
            listOf("metric:z", "check:rule-b"),
            result.getValue("evidence").jsonArray.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            },
        )
    }
}

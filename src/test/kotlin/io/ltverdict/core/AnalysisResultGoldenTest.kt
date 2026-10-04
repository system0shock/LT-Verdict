package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.SourceType
import io.ltverdict.metrics.MetricsConfig
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
    fun `verdict gates exist only with a policy`() {
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

        val withPolicy = Json.parseToJsonElement(analysisIdentity(input, policy, EngineConfig()).decodeToString()).jsonObject
        val withoutPolicy = Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig()).decodeToString()).jsonObject

        assertEquals(
            mapOf("min_samples_default" to "100", "min_samples_floor" to "20", "throughput_exempt" to "true"),
            withPolicy.getValue("verdict_gates").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(null, withoutPolicy["verdict_gates"])
    }

    @Test
    fun `histogram precision is part of the identity and changes the analysis id`() {
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

        val identities =
            listOf(3, 4, 5).associateWith { digits ->
                analysisIdentity(input, null, EngineConfig(metrics = MetricsConfig(significantDigits = digits)))
            }

        listOf(3, 4, 5).forEach { digits ->
            val histogram =
                Json
                    .parseToJsonElement(identities.getValue(digits).decodeToString())
                    .jsonObject
                    .getValue("histogram")
                    .jsonObject
            assertEquals(digits.toString(), histogram.getValue("significant_digits").jsonPrimitive.content)
        }
        assertEquals(
            3,
            identities.values
                .map { sha256Hex(it) }
                .toSet()
                .size,
        )
        assertArrayEquals(analysisIdentity(input, null, EngineConfig()), identities.getValue(3))
    }

    @Test
    fun `analysis identity with a snapshot pins the resource limits`() {
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
        val resources =
            validateResourceSnapshot(
                ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))),
            ) as ResourceValidation.Valid
        val expected = Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity-resources.v1.json"))
        val expectedHash =
            Files
                .readString(Path.of("fixtures/slice1/identity/analysis-identity-resources.sha256"))
                .trim()

        val actual = analysisIdentity(input, policy, EngineConfig(), resources = resources)

        assertArrayEquals(expected, actual)
        assertEquals(expectedHash, sha256Hex(actual))
        val limits =
            Json
                .parseToJsonElement(actual.decodeToString())
                .jsonObject
                .getValue("limits")
                .jsonObject
        assertEquals("1024", limits.getValue("resource_series_max").jsonPrimitive.content)
        assertEquals("1500000", limits.getValue("resource_cells_total_max").jsonPrimitive.content)
        assertEquals("33554432", limits.getValue("resource_snapshot_bytes_max").jsonPrimitive.content)
        assertEquals("100000", limits.getValue("resource_points_per_series_max").jsonPrimitive.content)
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

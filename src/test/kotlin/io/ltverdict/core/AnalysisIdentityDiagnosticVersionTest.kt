package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.metrics.MIN_P95_SAMPLES
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AnalysisIdentityDiagnosticVersionTest {
    @Test
    fun `only enabled diagnostic module advances to version four`() {
        assertEquals(
            listOf("normalization" to "1", "metrics" to "2", "policy-evaluation" to "1"),
            modules(analysisIdentity(input(), null, EngineConfig())),
        )
        assertEquals(
            listOf(
                "normalization" to "1",
                "metrics" to "2",
                "policy-evaluation" to "1",
                "load-resource-diagnostics" to "4",
            ),
            modules(analysisIdentity(input(), null, EngineConfig(), diagnostics = diagnostics())),
        )
    }

    @Test
    fun `identity publishes the p95 support threshold applied by the metric layer`() {
        val identity = analysisIdentity(input(), null, EngineConfig(), diagnostics = diagnostics())

        val limits =
            Json
                .parseToJsonElement(identity.decodeToString())
                .jsonObject
                .getValue("limits")
                .jsonObject

        assertEquals(MIN_P95_SAMPLES.toString(), limits.getValue("diagnostic_p95_samples_min").jsonPrimitive.content)
    }

    @Test
    fun `CSV identity advances its parser and source version while other sources remain at one`() {
        val cases =
            listOf(
                Triple(SourceType.JMETER_CSV, "jmeter-csv" to "2", "jmeter-jtl-csv.v2"),
                Triple(SourceType.JMETER_XML, "jmeter-xml" to "1", "jmeter-jtl-xml.v1"),
                Triple(SourceType.GATLING_TEXT, "gatling-text" to "1", "gatling-text.v1"),
                Triple(SourceType.GATLING_BINARY, "gatling-binary" to "1", "gatling-binary.v1"),
            )
        cases.forEach { (type, parser, sourceVersion) ->
            val identity =
                Json.parseToJsonElement(analysisIdentity(input(type), null, EngineConfig()).decodeToString()).jsonObject
            val parsers = identity.getValue("parsers").jsonArray.map { it.jsonObject }

            assertEquals(
                listOf(parser),
                parsers.map {
                    it.getValue("id").jsonPrimitive.content to
                        it.getValue("version").jsonPrimitive.content
                },
            )
            assertEquals(
                sourceVersion,
                identity
                    .getValue("input_versions")
                    .jsonObject
                    .getValue("source")
                    .jsonPrimitive.content,
            )
        }
    }

    private fun input(type: SourceType = SourceType.JMETER_CSV) =
        AcceptedInput(
            runId = "identity",
            sourceType = type,
            sha256 = "a".repeat(64),
            sizeBytes = 1,
            originalFilename = "input.jtl",
            path = Path.of("unused"),
        )

    private fun diagnostics() =
        DiagnosticValidation.Valid(
            DiagnosticPlanV1("correlation-plan.v1", "0".repeat(64), emptyList(), emptyList()),
            "d".repeat(64),
            byteArrayOf(),
        )

    private fun modules(identity: ByteArray): List<Pair<String, String>> =
        Json
            .parseToJsonElement(identity.decodeToString())
            .jsonObject
            .getValue("modules")
            .jsonArray
            .map { module ->
                val value = module.jsonObject
                value.getValue("id").jsonPrimitive.content to value.getValue("version").jsonPrimitive.content
            }
}

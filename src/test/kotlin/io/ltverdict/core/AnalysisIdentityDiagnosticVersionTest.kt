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
    fun `only enabled diagnostic module advances to version three`() {
        assertEquals(
            listOf("normalization" to "1", "metrics" to "2", "policy-evaluation" to "1"),
            modules(analysisIdentity(input(), null, EngineConfig())),
        )
        assertEquals(
            listOf(
                "normalization" to "1",
                "metrics" to "2",
                "policy-evaluation" to "1",
                "load-resource-diagnostics" to "3",
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

    private fun input() =
        AcceptedInput(
            runId = "identity",
            sourceType = SourceType.JMETER_CSV,
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

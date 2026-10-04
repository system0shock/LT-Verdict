package io.ltverdict.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OpenSearchDiagnosticArmTest {
    private val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `added opensearch series keep the arm of the base snapshot`() {
        val base =
            validateResourceSnapshot(
                """
                {"schema_version":"resource-snapshot.v1","load_input_sha256":"$hash","start_epoch_ms":0,"step_ms":1000,"point_count":3,
                 "series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host-a","role":"system","aggregation":"interval_mean",
                 "labels":{"arm":"A"},"values":[0.1,0.2,0.3]}]}
                """.trimIndent().byteInputStream(),
            ) as ResourceValidation.Valid
        val context =
            buildJsonObject {
                put("type", "opensearch_errors")
                put("load_input_sha256", hash)
                put("profile_id", "logs")
                put("start_epoch_ms", 0)
                put("end_epoch_ms", 3_000)
                put("step_ms", 1_000)
                put(
                    "timeline",
                    buildJsonArray {
                        listOf("0", "60", "120").forEachIndexed { index, rate ->
                            add(
                                buildJsonObject {
                                    put("from_epoch_ms", index * 1_000)
                                    put("to_epoch_ms", (index + 1) * 1_000)
                                    put("count", index)
                                    put("rate_per_minute", JsonPrimitive(BigDecimal(rate)))
                                },
                            )
                        }
                    },
                )
            }
        val template =
            OpenSearchCorrelationTemplate(
                id = "errors-vs-load",
                profileId = "logs",
                loadMetric = DiagnosticLoadMetric.ERROR_RATE,
                windowIds = listOf("run-intersection"),
                expectedSign = DiagnosticExpectedSign.POSITIVE,
                maxLagMillis = 1_000,
                minAbsEffect = BigDecimal("0.3"),
                minErrorRateDelta = BigDecimal("1"),
                minLoadDelta = BigDecimal("0.001"),
                controls = listOf(DiagnosticControlV1(DiagnosticControlMeaning.ACHIEVED_RPS, null)),
                topologyBasis = "declared OpenSearch errors to load error rate",
                clockAlignment = DiagnosticClockAlignment.DECLARED_ALIGNED,
            )

        val inputs = prepareOpenSearchDiagnostics(listOf(context), hash, base, listOf(template))

        assertEquals("A", inputs.resources.snapshot.arm)
        assertEquals(
            setOf("A"),
            inputs.resources.snapshot.series
                .map { it.labels["arm"] }
                .toSet(),
        )
    }
}

package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class OpenSearchDiagnosticAdapterTest {
    @Test
    fun `explicit template converts saved error rates into the existing bounded diagnostic contracts`() {
        val inputs =
            prepareOpenSearchDiagnostics(
                contexts = listOf(context("logs", step = 1_000)),
                loadInputSha256 = HASH,
                base = null,
                templates = listOf(template("logs")),
            )

        assertEquals(1, inputs.resources.snapshot.series.size)
        val series =
            inputs.resources.snapshot.series
                .single()
        assertEquals(inputs.seriesByProfile.getValue("logs"), series.id)
        assertEquals("opensearch_error_rate", series.metric)
        assertEquals("errors/minute", series.unit)
        assertEquals(listOf("0", "60", "120"), series.values.map { it?.toPlainString() })
        assertEquals(
            series.id,
            inputs.diagnostics.plan.pairs
                .single()
                .resourceSeriesId,
        )
        assertEquals(
            DiagnosticLoadMetric.ERROR_RATE,
            inputs.diagnostics.plan.pairs
                .single()
                .loadMetric,
        )
        assertTrue(validateDiagnosticBinding(inputs.diagnostics, inputs.resources).isEmpty())
    }

    @Test
    fun `adapter rejects grid mismatch instead of resampling or interpolating`() {
        val base =
            validateResourceSnapshot(
                snapshot(step = 2_000).toString().byteInputStream(),
            ) as ResourceValidation.Valid

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                prepareOpenSearchDiagnostics(listOf(context("logs", step = 1_000)), HASH, base, listOf(template("logs")))
            }

        assertEquals("OPENSEARCH_DIAGNOSTIC_GRID_MISMATCH", failure.message)
    }
}

private fun template(profile: String) =
    OpenSearchCorrelationTemplate(
        id = "errors-vs-load",
        profileId = profile,
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

private fun context(
    profile: String,
    step: Long,
) = buildJsonObject {
    put("type", "opensearch_errors")
    put("load_input_sha256", HASH)
    put("profile_id", profile)
    put("start_epoch_ms", 0)
    put("end_epoch_ms", step * 3)
    put("step_ms", step)
    put(
        "timeline",
        buildJsonArray {
            listOf("0", "60", "120").forEachIndexed { index, rate ->
                add(
                    buildJsonObject {
                        put("from_epoch_ms", index * step)
                        put("to_epoch_ms", (index + 1) * step)
                        put("count", index)
                        put("rate_per_minute", kotlinx.serialization.json.JsonPrimitive(BigDecimal(rate)))
                    },
                )
            }
        },
    )
}

private fun snapshot(step: Long): JsonObject =
    buildJsonObject {
        put("schema_version", "resource-snapshot.v1")
        put("load_input_sha256", HASH)
        put("start_epoch_ms", 0)
        put("step_ms", step)
        put("point_count", 3)
        put(
            "series",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "cpu")
                        put("metric", "cpu")
                        put("unit", "cores")
                        put("entity", "app")
                        put("role", "system")
                        put("aggregation", "interval_mean")
                        put("values", JsonArray(listOf(1, 1, 1).map { kotlinx.serialization.json.JsonPrimitive(it) }))
                    },
                ),
            ),
        )
    }

private const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

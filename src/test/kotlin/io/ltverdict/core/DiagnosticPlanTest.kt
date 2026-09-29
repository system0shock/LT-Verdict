package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class DiagnosticPlanTest {
    @Test
    fun `valid plan applies defaults hashes semantics and protects source bytes`() {
        val raw =
            planJson(
                pairs =
                    """[{"id":"cpu-latency","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["evaluation"],"min_resource_delta":0.1,"min_load_delta":20,"topology_basis":"load host"}]""",
            ).encodeToByteArray()

        val valid = validPlan(raw)
        val pair = valid.plan.pairs.single()
        val exposed = valid.rawBytes()
        exposed[0] = '!'.code.toByte()

        assertEquals(DiagnosticExpectedSign.EITHER, pair.expectedSign)
        assertEquals(0, pair.maxLagMillis)
        assertEquals("0.3", canonicalDecimal(pair.minAbsEffect))
        assertEquals(DiagnosticClockAlignment.UNKNOWN, pair.clockAlignment)
        assertArrayEquals(raw, valid.rawBytes())
        assertEquals(64, valid.sha256.length)

        val explicitDefaults =
            validPlan(
                planJson(
                    pairs =
                        """[{"topology_basis":"load host","min_load_delta":20.0,"min_resource_delta":1e-1,"window_ids":["evaluation"],"load_metric":"response_time_p95_ms","resource_series_id":"cpu","id":"cpu-latency","expected_sign":"either","max_lag_ms":0,"min_abs_effect":0.30,"controls":[],"clock_alignment":"unknown"}]""",
                    anomalies = "[]",
                ).encodeToByteArray(),
            )
        assertEquals(valid.sha256, explicitDefaults.sha256)
        assertNotEquals(sha256Hex(raw), valid.sha256)
    }

    @Test
    fun `strict validator rejects malicious and silently droppable input`() {
        val cases =
            listOf(
                "duplicate key" to
                    """{"schema_version":"correlation-plan.v1","schema_version":"correlation-plan.v1","resource_snapshot_sha256":"${"a".repeat(
                        64,
                    )}","pairs":[],"anomalies":[]}""",
                "unknown field" to planJson(extra = ",\"ignored\":true"),
                "empty plan" to planJson(),
                "duplicate windows" to
                    planJson(
                        pairs =
                            """[{"id":"p","resource_series_id":"cpu","load_metric":"throughput_rps","window_ids":["evaluation","evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host"}]""",
                    ),
                "source as control" to
                    planJson(
                        pairs =
                            """[{"id":"p","resource_series_id":"cpu","load_metric":"throughput_rps","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"other","series_id":"cpu"}]}]""",
                    ),
                "throughput as achieved control" to
                    planJson(
                        pairs =
                            """[{"id":"p","resource_series_id":"cpu","load_metric":"throughput_rps","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"achieved_rps"}]}]""",
                    ),
                "ambiguous signal" to
                    planJson(
                        anomalies =
                            """[{"id":"a","signal":{"series_id":"cpu","load_metric":"error_rate"},"reference_window_id":"reference","window_id":"evaluation","direction":"either","min_abs_delta":1,"min_duration_ms":1000}]""",
                    ),
                "unbounded coefficient" to
                    planJson(
                        pairs =
                            """[{"id":"p","resource_series_id":"cpu","load_metric":"error_rate","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1,"min_abs_effect":1.1,"topology_basis":"host"}]""",
                    ),
                "fractional duration" to
                    planJson(
                        anomalies =
                            """[{"id":"a","signal":{"series_id":"cpu"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":1,"min_duration_ms":1.5}]""",
                    ),
            )

        cases.forEach { (label, json) ->
            val invalid =
                assertInstanceOf(
                    DiagnosticValidation.Invalid::class.java,
                    validateDiagnosticPlan(ByteArrayInputStream(json.encodeToByteArray())),
                    label,
                )
            assertEquals(1, invalid.errors.size, label)
        }
    }

    @Test
    fun `duplicate pair semantics ignore decimal scale and lexical spelling`() {
        val json =
            planJson(
                pairs =
                    """
                    [
                      {"id":"first","resource_series_id":"cpu","load_metric":"error_rate","window_ids":["evaluation"],"min_resource_delta":1,"min_load_delta":1.0,"topology_basis":"host"},
                      {"id":"second","resource_series_id":"cpu","load_metric":"error_rate","window_ids":["evaluation"],"min_resource_delta":1.0,"min_load_delta":1,"topology_basis":"host"}
                    ]
                    """.trimIndent(),
            )

        val invalid =
            assertInstanceOf(
                DiagnosticValidation.Invalid::class.java,
                validateDiagnosticPlan(ByteArrayInputStream(json.encodeToByteArray())),
            )

        assertEquals("DUPLICATE_PAIR", invalid.errors.single().code)
    }

    @Test
    fun `binding reports stable codes for snapshot series semantics grid and windows`() {
        val snapshot =
            resource(
                """
                [
                  {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[1,2,3,4]},
                  {"id":"target","metric":"target","unit":"count","entity":"load","role":"generator","aggregation":"interval_mean","values":[1,2,3,4]}
                ]
                """.trimIndent(),
            )
        val plan =
            validPlan(
                planJson(
                    snapshotHash = snapshot.semanticSha256,
                    pairs =
                        """[{"id":"p","resource_series_id":"cpu","load_metric":"response_time_p95_ms","window_ids":["missing"],"max_lag_ms":11000,"min_resource_delta":1,"min_load_delta":1,"topology_basis":"host","controls":[{"meaning":"target_rps","series_id":"target"}]}]""",
                    anomalies =
                        """[{"id":"a","signal":{"series_id":"missing"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":1,"min_duration_ms":1500}]""",
                ).encodeToByteArray(),
            )

        val errors = validateDiagnosticBinding(plan, snapshot)

        assertEquals(
            listOf(
                "DIAGNOSTIC_WINDOW_NOT_FOUND",
                "DIAGNOSTIC_INVALID_BINDING",
                "DIAGNOSTIC_INVALID_BINDING",
                "DIAGNOSTIC_INVALID_BINDING",
                "DIAGNOSTIC_INVALID_BINDING",
            ),
            errors.map(PolicyValidationError::code),
        )
        assertEquals(
            listOf(
                "/pairs/0/window_ids/0",
                "/pairs/0/max_lag_ms",
                "/pairs/0/controls/0/series_id",
                "/anomalies/0/min_duration_ms",
                "/anomalies/0/signal/series_id",
            ),
            errors.map(PolicyValidationError::jsonPointer),
        )

        val mismatch =
            validPlan(
                planJson(
                    snapshotHash = "b".repeat(64),
                    anomalies =
                        """[{"id":"a","signal":{"load_metric":"error_rate"},"reference_window_id":"reference","window_id":"evaluation","direction":"increase","min_abs_delta":0.1,"min_duration_ms":1000}]""",
                ).encodeToByteArray(),
            )
        assertEquals("DIAGNOSTIC_SNAPSHOT_MISMATCH", validateDiagnosticBinding(mismatch, snapshot).single().code)
    }

    private fun validPlan(raw: ByteArray): DiagnosticValidation.Valid =
        assertInstanceOf(
            DiagnosticValidation.Valid::class.java,
            validateDiagnosticPlan(ByteArrayInputStream(raw)),
        )

    private fun resource(series: String): ResourceValidation.Valid {
        val json =
            """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"${"0".repeat(64)}",
              "start_epoch_ms":0,
              "step_ms":1000,
              "point_count":4,
              "series":$series,
              "windows":[
                {"id":"reference","from_epoch_ms":0,"to_epoch_ms":2000},
                {"id":"evaluation","from_epoch_ms":2000,"to_epoch_ms":4000}
              ]
            }
            """.trimIndent()
        return assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(json.encodeToByteArray())),
        )
    }

    private fun planJson(
        snapshotHash: String = "a".repeat(64),
        pairs: String = "[]",
        anomalies: String = "[]",
        extra: String = "",
    ): String =
        """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"$snapshotHash","pairs":$pairs,"anomalies":$anomalies$extra}"""
}

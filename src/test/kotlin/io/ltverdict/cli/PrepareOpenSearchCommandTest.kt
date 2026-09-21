package io.ltverdict.cli

import io.ltverdict.core.DiagnosticValidation
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.validateDiagnosticBinding
import io.ltverdict.core.validateDiagnosticPlan
import io.ltverdict.core.validateResourceSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class PrepareOpenSearchCommandTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `offline command writes existing validated resource and diagnostic contracts`() {
        val context = tempDir.resolve("opensearch.json")
        val templates = tempDir.resolve("templates.json")
        val output = tempDir.resolve("prepared")
        Files.writeString(context, CONTEXT)
        Files.writeString(templates, TEMPLATES)
        val bytes = ByteArrayOutputStream()

        val exit =
            prepareOpenSearchCommand(
                listOf(
                    "prepare",
                    "--context",
                    context.toString(),
                    "--templates",
                    templates.toString(),
                    "--load-sha256",
                    HASH,
                    "--output-dir",
                    output.toString(),
                ),
                PrintStream(bytes, true, Charsets.UTF_8),
            )

        assertEquals(0, exit)
        val resources =
            Files
                .newInputStream(
                    output.resolve("resource-snapshot.json"),
                ).use(::validateResourceSnapshot) as ResourceValidation.Valid
        val diagnostics =
            Files
                .newInputStream(
                    output.resolve("correlation-plan.json"),
                ).use(::validateDiagnosticPlan) as DiagnosticValidation.Valid
        assertEquals(
            listOf("60", "120"),
            resources.snapshot.series
                .single()
                .values
                .map { it?.toPlainString() },
        )
        assertTrue(validateDiagnosticBinding(diagnostics, resources).isEmpty())
        assertTrue(bytes.toString(Charsets.UTF_8).contains("opensearch-error-rate-"))

        val failure =
            assertThrows(OpenSearchPrepareFailure::class.java) {
                prepareOpenSearchCommand(
                    listOf(
                        "prepare",
                        "--context",
                        context.toString(),
                        "--templates",
                        templates.toString(),
                        "--load-sha256",
                        HASH,
                        "--output-dir",
                        output.toString(),
                    ),
                    PrintStream(ByteArrayOutputStream()),
                )
            }
        assertEquals("OPENSEARCH_PREPARE_OUTPUT_EXISTS", failure.message)
    }
}

private const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
private val CONTEXT =
    """
    {
      "schema_version":"opensearch-errors.v1",
      "id":"opensearch-errors:errors",
      "type":"opensearch_errors",
      "load_input_sha256":"$HASH",
      "profile_id":"errors",
      "start_epoch_ms":1000,
      "end_epoch_ms":3000,
      "step_ms":1000,
      "total_errors":3,
      "error_rate_per_minute":90,
      "timeline":[
        {"from_epoch_ms":1000,"to_epoch_ms":2000,"count":1,"rate_per_minute":60},
        {"from_epoch_ms":2000,"to_epoch_ms":3000,"count":2,"rate_per_minute":120}
      ],
      "groups":[
        {"service":"api","error_type":"Timeout","count":3,"first_epoch_ms":1100,"last_epoch_ms":2900,"samples":[]}
      ],
      "coverage":{
        "status":"COMPLETE","reasons":[],"timed_out":false,"total_relation":"eq",
        "shards":{"total":1,"successful":1,"skipped":0,"failed":0},
        "terms":{"group_limit":50,"returned_groups":1,"sum_other_doc_count":0,"doc_count_error_upper_bound":0},
        "samples_per_group_limit":0,"sample_message_bytes_max":4096
      }
    }
    """.trimIndent()
private val TEMPLATES =
    """
    {
      "schema_version":"opensearch-correlation-templates.v1",
      "templates":[{
        "id":"errors-vs-p95",
        "profile_id":"errors",
        "load_metric":"response_time_p95_ms",
        "window_ids":["run-intersection"],
        "expected_sign":"positive",
        "max_lag_ms":1000,
        "min_abs_effect":0.3,
        "min_resource_delta":1,
        "min_load_delta":5,
        "controls":[{"meaning":"achieved_rps"}],
        "topology_basis":"declared errors to load latency",
        "clock_alignment":"declared_aligned"
      }]
    }
    """.trimIndent()

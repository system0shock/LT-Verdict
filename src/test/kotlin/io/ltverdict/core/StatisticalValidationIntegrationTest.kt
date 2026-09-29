package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StatisticalValidationIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `configured runner rejects a missing corpus directory`() {
        assertThrows(IllegalArgumentException::class.java) {
            StatisticalValidationRunner.paths(
                mapOf(
                    "LTV_STATS_CORPUS" to "missing-corpus",
                    "LTV_STATS_ACTUAL" to
                        Path
                            .of("actual.jsonl")
                            .toAbsolutePath()
                            .toString(),
                ),
            )
        }
    }

    @Test
    fun `DEBUG corpus runner writes raw resource output`() {
        val corpus = tempDir.resolve("corpus")
        val inputs = Files.createDirectories(corpus.resolve("inputs"))
        val input = inputs.resolve("debug.json")
        Files.writeString(
            input,
            """
            {
              "operation":"resource",
              "resources":{
                "schema_version":"resource-snapshot.v1",
                "load_input_sha256":"${"0".repeat(64)}",
                "start_epoch_ms":0,"step_ms":1000,"point_count":2,
                "series":[{
                  "id":"cpu","metric":"cpu","unit":"ratio","entity":"debug",
                  "role":"system","aggregation":"interval_mean","values":[1,2]
                }],
                "windows":[{"id":"debug","from_epoch_ms":0,"to_epoch_ms":2000}]
              }
            }
            """.trimIndent(),
        )
        Files.writeString(
            corpus.resolve("manifest.json"),
            """
            {
              "schema_version":"stats-cases.v1",
              "cases":[
                {
                  "id":"DEBUG_RESOURCE",
                  "inputs":{
                    "path":"inputs/debug.json",
                    "sha256":"${sha256Hex(Files.readAllBytes(input))}"
                  },
                  "expected":"must-not-be-read"
                }
              ]
            }
            """.trimIndent(),
        )
        val actual = tempDir.resolve("actual.jsonl")

        val paths =
            requireNotNull(
                StatisticalValidationRunner.paths(
                    mapOf(
                        "LTV_STATS_CORPUS" to corpus.toString(),
                        "LTV_STATS_ACTUAL" to actual.toString(),
                    ),
                ),
            )
        StatisticalValidationRunner.run(paths)

        assertTrue(Files.readString(actual).contains("resource_summaries"))
    }
}

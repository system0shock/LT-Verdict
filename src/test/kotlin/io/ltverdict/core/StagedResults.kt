package io.ltverdict.core

import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Real analysis results on fixtures/stages/ramp-steady-rampdown.jtl for the tests of the reports (ADR 0030, PR C). */
internal object StagedResults {
    val RAMP_STEADY_DOWN: String = Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"))
    val TWO_STEADY: String = Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/two-steady.json"))
    val RAMP: ByteArray = Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))

    fun policy(
        vararg rules: String,
        id: String = "stages",
    ): String =
        """{"schema_version":"policy.v1","policy_id":"$id","defaults":{"sample_floor":1,"min_samples":1},"rules":[${rules.joinToString(
            ",",
        )}]}"""

    fun p95(
        threshold: Int,
        windowIds: String? = null,
    ): String =
        """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,""" +
            (windowIds?.let { """"window_ids":$it,""" } ?: "") +
            """"scope":{"kind":"overall"}}"""

    /** The canonical result of one analysis; [stages] null analyses the run as a whole. */
    fun analyze(
        root: Path,
        input: ByteArray = RAMP,
        policy: String? = null,
        stages: String? = null,
    ): AnalysisOutcome =
        DataDirectory.open(root.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            val accepted = store.acceptInput(ByteArrayInputStream(input), "input.jtl")
            val declared =
                stages?.let {
                    assertInstanceOf(
                        LoadStagesValidation.Valid::class.java,
                        validateLoadStages(ByteArrayInputStream(it.encodeToByteArray())),
                    )
                }
            val rules =
                policy?.let {
                    assertInstanceOf(PolicyValidation.Valid::class.java, validatePolicy(ByteArrayInputStream(it.encodeToByteArray())))
                }
            AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(accepted, rules, stages = declared))
        }

    /** The same result without the two items a stage declaration adds: what a run without stages would carry. */
    fun withoutStageItems(result: ByteArray): ByteArray {
        val root = Json.parseToJsonElement(result.decodeToString()).jsonObject
        val evidence =
            root.getValue("evidence").jsonArray.filterNot {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content in setOf("stage_binding", "window_metric_summary")
            }
        return canonicalJson(JsonObject(root + ("evidence" to JsonArray(evidence))))
    }
}

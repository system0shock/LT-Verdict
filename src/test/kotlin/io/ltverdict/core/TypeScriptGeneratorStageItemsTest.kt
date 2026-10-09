package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The stage_binding item (ADR 0030) is a family of its own; its UI type is generated and checked against a real item. */
class TypeScriptGeneratorStageItemsTest {
    @TempDir
    lateinit var tempDir: Path

    private val file = Path.of("ui/src/types.stage-items.generated.ts")
    private val sample = Path.of("fixtures/stages/stage-binding.sample.json")

    @Test
    fun `ui types stage items generated file equals the output of the generator`() {
        val generated =
            TypeScriptGenerator.generate(
                listOf(StageEvidence.serializer()),
                "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/LoadStages.kt.\n" +
                    "// Do not edit. Regenerate with\n" +
                    "// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorStageItemsTest\n" +
                    "// Describes the stage_binding evidence item of a run with declared load stages (ADR 0030); it is not a validator.\n",
                mapOf("StageEvidence" to "Evidence"),
            )
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.writeString(file, generated)
        assertEquals(
            generated,
            Files.readString(file).replace("\r\n", "\n"),
            "ui/src/types.stage-items.generated.ts is stale; regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }

    @Test
    fun `the sample item that the UI checks is the stage binding the engine writes`() {
        val outcome = StagedResults.analyze(tempDir, stages = StagedResults.RAMP_STEADY_DOWN)
        val binding =
            Json
                .parseToJsonElement(outcome.canonicalResult.decodeToString())
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("type")
                        .jsonPrimitive.content == "stage_binding"
                }
        val actual = canonicalJson(binding)
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.write(sample, actual)
        assertEquals(
            Files.readString(sample).replace("\r\n", "\n"),
            actual.decodeToString(),
            "regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }
}

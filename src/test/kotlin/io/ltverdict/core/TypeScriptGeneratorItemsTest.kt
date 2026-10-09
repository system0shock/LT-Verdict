package io.ltverdict.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TypeScriptGeneratorItemsTest {
    @OptIn(ExperimentalSerializationApi::class)
    @JsonClassDiscriminator("kind")
    @Serializable
    private sealed interface Shape

    @Serializable
    @SerialName("unit_square")
    private data object UnitSquare : Shape

    @Serializable
    @SerialName("rect")
    private data class Rect(
        val width: Long,
        val label: String? = null,
        val note: String?,
    ) : Shape

    @Serializable
    private sealed interface AnalysisSample

    @Serializable
    @SerialName("plain_one")
    private data class PlainOne(
        val shape: Shape,
    ) : AnalysisSample

    @Test
    fun `a sealed hierarchy is a union of interfaces tagged with the discriminator`() {
        val generated = TypeScriptGenerator.generate(listOf(AnalysisSample.serializer()))

        listOf(
            "export type AnalysisSample = PlainOneSample",
            "export interface PlainOneSample {\n  type: 'plain_one'\n  shape: Shape\n}",
            "export type Shape = RectShape | UnitSquareShape",
            "export interface UnitSquareShape {\n  kind: 'unit_square'\n}",
            "export interface RectShape {\n  kind: 'rect'\n  width: number\n  label?: string\n  note: string | null\n}",
        ).forEach { expected -> assertEquals(true, generated.contains(expected), "missing `$expected` in\n$generated") }
    }

    @Test
    fun `ui types items generated file equals the output of the generator`() {
        val generated =
            TypeScriptGenerator.generate(
                listOf(AnalysisFinding.serializer(), AnalysisEvidence.serializer()),
                "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/AnalysisItems.kt.\n" +
                    "// Do not edit. Regenerate with\n" +
                    "// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorItemsTest\n" +
                    "// Describes the findings and evidence items the engine writes (optional fields are omitted, never null);\n" +
                    "// it is not a validator. Only the families already typed in Kotlin are here.\n",
            )
        val file = Path.of("ui/src/types.items.generated.ts")
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.writeString(file, generated)
        assertEquals(
            generated,
            Files.readString(file).replace("\r\n", "\n"),
            "ui/src/types.items.generated.ts is stale; regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }
}

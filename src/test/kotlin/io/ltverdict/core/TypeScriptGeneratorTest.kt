package io.ltverdict.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TypeScriptGeneratorTest {
    @Serializable
    private enum class Level {
        @SerialName("low")
        LOW,

        @SerialName("HIGH")
        HIGH,
    }

    @Serializable
    private data class Inner(
        val tags: List<String>,
    )

    @Serializable
    private data class Sample(
        val level: Level,
        val count: Long,
        val ratio: Int,
        val flag: Boolean,
        val nullable: String?,
        val optional: String? = null,
        val limits: Map<String, String>,
        val payload: JsonObject,
        val payloads: List<JsonObject>,
        val optionalPayload: JsonObject? = null,
        val inner: Inner,
    )

    @Test
    fun `descriptor mapping covers required optional nullable enum map and opaque payload fields`() {
        val generated = TypeScriptGenerator.generate(listOf(Sample.serializer()))

        listOf(
            "export type Level = 'low' | 'HIGH'",
            "  level: Level",
            "  count: number",
            "  ratio: number",
            "  flag: boolean",
            "  nullable: string | null",
            "  optional?: string",
            "  limits: Record<string, string>",
            "  payload: Record<string, unknown>",
            "  payloads: Array<Record<string, unknown>>",
            "  optionalPayload?: Record<string, unknown>",
            "  inner: Inner",
            "export interface Inner {\n  tags: Array<string>\n}",
        ).forEach { expected -> assertEquals(true, generated.contains(expected), "missing `$expected` in\n$generated") }
    }

    @Test
    fun `ui types generated file equals the output of the generator`() {
        val generated =
            TypeScriptGenerator.generate(listOf(AnalysisResultDocument.serializer(), AnalysisIdentityDocument.serializer()))
        val file = Path.of("ui/src/types.generated.ts")
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.writeString(file, generated)
        assertEquals(
            generated,
            Files.readString(file).replace("\r\n", "\n"),
            "ui/src/types.generated.ts is stale; regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }
}

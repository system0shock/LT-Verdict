package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TypeScriptGeneratorInputItemsTest {
    private val inputFile = Path.of("ui/src/types.input-items.generated.ts")
    private val others =
        listOf(
            Path.of("ui/src/types.items.generated.ts"),
            Path.of("ui/src/types.derived-items.generated.ts"),
            Path.of("ui/src/types.stage-items.generated.ts"),
        )

    private fun generate() =
        TypeScriptGenerator.generate(
            listOf(InputEvidence.serializer()),
            "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/InputItems.kt.\n" +
                "// Do not edit. Regenerate with\n" +
                "// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorInputItemsTest\n" +
                "// Describes the resource_binding, source_summary and opensearch_errors evidence items the engine writes (an optional\n" +
                "// key is omitted, never null); the window provenance that a source_summary may carry is merged after the typed\n" +
                "// item and is not described here. It is not a validator.\n",
            mapOf("InputEvidence" to "Evidence"),
        )

    @Test
    fun `ui types input items generated file equals the output of the generator`() {
        val generated = generate()
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.writeString(inputFile, generated)
        assertEquals(
            generated,
            Files.readString(inputFile).replace("\r\n", "\n"),
            "ui/src/types.input-items.generated.ts is stale; regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }

    // The generator skips a name it has already declared, so a clash would be silently blessed by regeneration: check the files by
    // hand. A shared nested declaration must be the same text; no item interface may be declared in two files.
    @Test
    fun `the generated item files share only identical nested declarations`() {
        fun declarations(path: Path): Map<String, String> =
            Regex("""(?ms)^export (?:interface|type) (\w+)\b.*?(?=^export |\z)""")
                .findAll(Files.readString(path).replace("\r\n", "\n"))
                .associate { it.groupValues[1] to it.value.trim() }

        val input = declarations(inputFile)
        others.forEach { other ->
            val declared = declarations(other)
            val shared = input.keys intersect declared.keys
            shared.forEach {
                assertEquals(
                    declared.getValue(it),
                    input.getValue(it),
                    "declaration $it differs between $other and $inputFile",
                )
            }
            val clashing = shared.filter { it.endsWith("Evidence") || it.endsWith("Finding") }
            assertTrue(clashing.isEmpty(), "item interfaces declared in $other and $inputFile: $clashing")
        }
        assertTrue("ResourceBindingEvidence" in input && "SourceSummaryEvidence" in input && "OpensearchErrorsEvidence" in input)
    }
}

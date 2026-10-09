package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TypeScriptGeneratorDerivedItemsTest {
    private val itemsFile = Path.of("ui/src/types.items.generated.ts")
    private val derivedFile = Path.of("ui/src/types.derived-items.generated.ts")

    private fun generate() =
        TypeScriptGenerator.generate(
            listOf(DerivedFinding.serializer(), DerivedEvidence.serializer()),
            "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/DerivedItems.kt.\n" +
                "// Do not edit. Regenerate with\n" +
                "// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorDerivedItemsTest\n" +
                "// Describes the diagnostic, capacity and trend findings and evidence items the engine writes (a field is never\n" +
                "// omitted: an absent value is an explicit null); it is not a validator. The policy and resource items are in\n" +
                "// types.items.generated.ts.\n",
            mapOf("DerivedFinding" to "Finding", "DerivedEvidence" to "Evidence"),
        )

    @Test
    fun `ui types derived items generated file equals the output of the generator`() {
        val generated = generate()
        if (System.getenv("LTV_UPDATE_GENERATED_TYPES") == "1") Files.writeString(derivedFile, generated)
        assertEquals(
            generated,
            Files.readString(derivedFile).replace("\r\n", "\n"),
            "ui/src/types.derived-items.generated.ts is stale; regenerate with LTV_UPDATE_GENERATED_TYPES=1",
        )
    }

    // The generator skips a name it has already declared, so a clash would be silently blessed by regeneration: check the two
    // files by hand. A shared nested declaration must be the same text; no item interface may be declared in both.
    @Test
    fun `the two generated item files share only identical nested declarations`() {
        fun declarations(path: Path): Map<String, String> =
            Regex("""(?ms)^export (?:interface|type) (\w+)\b.*?(?=^export |\z)""")
                .findAll(Files.readString(path).replace("\r\n", "\n"))
                .associate { it.groupValues[1] to it.value.trim() }

        val items = declarations(itemsFile)
        val derived = declarations(derivedFile)
        val shared = items.keys intersect derived.keys
        shared.forEach { assertEquals(items.getValue(it), derived.getValue(it), "declaration $it differs between the two files") }
        val roots = setOf("AnalysisEvidence", "AnalysisFinding", "DerivedEvidence", "DerivedFinding")
        val clashing = shared.filter { it.endsWith("Evidence") || it.endsWith("Finding") || it in roots }
        assertTrue(clashing.isEmpty(), "item interfaces declared in both files: $clashing")
        assertTrue("CorrelationPairEvidence" in derived && "CapacitySummaryEvidence" in derived && "ResourceTrendFinding" in derived)
    }
}

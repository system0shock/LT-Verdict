package io.ltverdict.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames

/**
 * Walks the SerialDescriptor of the typed models and writes TypeScript declarations of the JSON the engine writes.
 * Test-only: it adds no production dependency. Mapping rules:
 *  - a property with a default value (`= null`) is `name?: T` (the engine omits it, never writes null);
 *  - a nullable property without a default is `name: T | null`;
 *  - an enum is a union of its serialized names, a Map<String, V> is Record<string, V>;
 *  - JsonObject / JsonElement are opaque payloads: Record<string, unknown> / unknown.
 */
internal object TypeScriptGenerator {
    private const val HEADER =
        "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/AnalysisDocuments.kt.\n" +
            "// Do not edit. Regenerate: LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorTest\n" +
            "// Describes the documents the engine writes (optional fields are omitted, never null); it is not a validator.\n"

    fun generate(roots: List<KSerializer<*>>): String {
        val declarations = linkedMapOf<String, String>()
        roots.forEach { declare(it.descriptor, declarations) }
        return HEADER + "\n" + declarations.values.joinToString("\n\n") + "\n"
    }

    private fun declare(
        descriptor: SerialDescriptor,
        declarations: MutableMap<String, String>,
    ) {
        val name = typeName(descriptor)
        if (name in declarations) return
        when (descriptor.kind) {
            SerialKind.ENUM -> {
                declarations[name] = "export type $name = " + descriptor.elementNames.joinToString(" | ") { "'$it'" }
            }

            StructureKind.CLASS -> {
                declarations[name] = "" // reserve the position: nested declarations follow their user
                val fields =
                    (0 until descriptor.elementsCount).map { index ->
                        val child = descriptor.getElementDescriptor(index)
                        collect(child, declarations)
                        val optional = descriptor.isElementOptional(index)
                        val type = tsType(child)
                        val rendered =
                            when {
                                optional -> "  ${descriptor.getElementName(index)}?: ${type.removeSuffix(" | null")}"
                                else -> "  ${descriptor.getElementName(index)}: $type"
                            }
                        rendered
                    }
                declarations[name] = "export interface $name {\n" + fields.joinToString("\n") + "\n}"
            }

            else -> error("unsupported root kind ${descriptor.kind} for $name")
        }
    }

    private fun collect(
        descriptor: SerialDescriptor,
        declarations: MutableMap<String, String>,
    ) {
        when (descriptor.kind) {
            SerialKind.ENUM, StructureKind.CLASS -> declare(descriptor, declarations)
            StructureKind.LIST -> collect(descriptor.getElementDescriptor(0), declarations)
            StructureKind.MAP -> collect(descriptor.getElementDescriptor(1), declarations)
            else -> Unit
        }
    }

    private fun tsType(descriptor: SerialDescriptor): String {
        val base =
            when {
                descriptor.serialName.removeSuffix("?") == JSON_OBJECT -> "Record<string, unknown>"
                descriptor.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.Json") -> "unknown"
                else ->
                    when (val kind = descriptor.kind) {
                        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
                        PrimitiveKind.BOOLEAN -> "boolean"
                        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
                        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
                        -> "number"

                        SerialKind.ENUM, StructureKind.CLASS -> typeName(descriptor)
                        StructureKind.LIST -> "Array<${tsType(descriptor.getElementDescriptor(0))}>"
                        StructureKind.MAP -> {
                            val key = tsType(descriptor.getElementDescriptor(0))
                            require(key == "string") { "map keys must be strings" }
                            "Record<string, ${tsType(descriptor.getElementDescriptor(1))}>"
                        }

                        else -> error("unsupported kind $kind in ${descriptor.serialName}")
                    }
            }
        return if (descriptor.isNullable) "$base | null" else base
    }

    private fun typeName(descriptor: SerialDescriptor): String = descriptor.serialName.removeSuffix("?").substringAfterLast('.')

    private const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
}

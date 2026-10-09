package io.ltverdict.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Walks the SerialDescriptor of the typed models and writes TypeScript declarations of the JSON the engine writes.
 * Test-only: it adds no production dependency. Mapping rules:
 *  - a property with a default value (`= null`) is `name?: T` (the engine omits it, never writes null);
 *  - a nullable property without a default is `name: T | null`;
 *  - an enum is a union of its serialized names, a Map<String, V> is Record<string, V>;
 *  - JsonObject / JsonElement are opaque payloads: Record<string, unknown> / unknown;
 *  - a sealed hierarchy is a union of its subclasses; a subclass is an interface named PascalCase(@SerialName) + the root name
 *    without its "Analysis" prefix (`policy_check` in AnalysisEvidence is PolicyCheckEvidence) whose first field is the
 *    discriminator (`type`, or the @JsonClassDiscriminator of the root) typed with the @SerialName literal.
 */
internal object TypeScriptGenerator {
    private const val HEADER =
        "// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/AnalysisDocuments.kt.\n" +
            "// Do not edit. Regenerate: LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorTest\n" +
            "// Describes the documents the engine writes (optional fields are omitted, never null); it is not a validator.\n"

    fun generate(
        roots: List<KSerializer<*>>,
        header: String = HEADER,
    ): String {
        val declarations = linkedMapOf<String, String>()
        roots.forEach { declare(it.descriptor, declarations) }
        return header + "\n" + declarations.values.joinToString("\n\n") + "\n"
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

            StructureKind.CLASS -> declareInterface(descriptor, name, null, declarations)

            PolymorphicKind.SEALED -> {
                declarations[name] = "" // reserve the position: the subclasses follow their union
                val discriminator = discriminatorOf(descriptor)
                val suffix = name.removePrefix("Analysis")
                val subclasses = descriptor.getElementDescriptor(1).elementDescriptors.toList()
                val names = subclasses.map { pascal(it.serialName) + suffix }
                subclasses.zip(names).forEach { (subclass, subName) ->
                    declareInterface(subclass, subName, discriminator to subclass.serialName, declarations)
                }
                declarations[name] = "export type $name = " + names.joinToString(" | ")
            }

            else -> error("unsupported root kind ${descriptor.kind} for $name")
        }
    }

    private fun declareInterface(
        descriptor: SerialDescriptor,
        name: String,
        tag: Pair<String, String>?,
        declarations: MutableMap<String, String>,
    ) {
        declarations[name] = "" // reserve the position: nested declarations follow their user
        val fields =
            (0 until descriptor.elementsCount).map { index ->
                val child = descriptor.getElementDescriptor(index)
                collect(child, declarations)
                val optional = descriptor.isElementOptional(index)
                val type = tsType(child)
                when {
                    optional -> "  ${descriptor.getElementName(index)}?: ${type.removeSuffix(" | null")}"
                    else -> "  ${descriptor.getElementName(index)}: $type"
                }
            }
        val tagged = listOfNotNull(tag?.let { (key, value) -> "  $key: '$value'" }) + fields
        declarations[name] = "export interface $name {\n" + tagged.joinToString("\n") + "\n}"
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun discriminatorOf(descriptor: SerialDescriptor): String =
        descriptor.annotations
            .filterIsInstance<JsonClassDiscriminator>()
            .firstOrNull()
            ?.discriminator ?: "type"

    private fun pascal(wireName: String): String =
        wireName.split('_', '-').filter { it.isNotEmpty() }.joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    private fun collect(
        descriptor: SerialDescriptor,
        declarations: MutableMap<String, String>,
    ) {
        if (descriptor.serialName.removeSuffix("?").startsWith(JSON_PREFIX)) return // opaque payloads have no declaration
        when (descriptor.kind) {
            SerialKind.ENUM, StructureKind.CLASS, PolymorphicKind.SEALED -> declare(descriptor, declarations)
            StructureKind.LIST -> collect(descriptor.getElementDescriptor(0), declarations)
            StructureKind.MAP -> collect(descriptor.getElementDescriptor(1), declarations)
            else -> Unit
        }
    }

    private fun tsType(descriptor: SerialDescriptor): String {
        val base =
            when {
                descriptor.serialName.removeSuffix("?") == JSON_OBJECT -> "Record<string, unknown>"
                descriptor.serialName.removeSuffix("?").startsWith(JSON_PREFIX) -> "unknown"
                else ->
                    when (val kind = descriptor.kind) {
                        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "string"
                        PrimitiveKind.BOOLEAN -> "boolean"
                        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG,
                        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE,
                        -> "number"

                        SerialKind.ENUM, StructureKind.CLASS, PolymorphicKind.SEALED -> typeName(descriptor)
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
    private const val JSON_PREFIX = "kotlinx.serialization.json.Json"
}

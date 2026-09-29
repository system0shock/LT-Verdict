@file:Suppress("ktlint:standard:filename")

package io.ltverdict.ingest

import io.ltverdict.storage.AcceptedInput
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException

internal enum class SourceType(
    val wireName: String,
) {
    JMETER_CSV("jmeter_jtl_csv"),
    JMETER_XML("jmeter_jtl_xml"),
    GATLING_TEXT("gatling_text"),
    GATLING_BINARY("gatling_binary"),
}

internal fun detectSource(path: Path): SourceType {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) unsupported()
    val prefix = Files.newInputStream(path).use { it.readNBytes(MAX_PREFIX_BYTES) }
    if (prefix.isEmpty()) unsupported()

    binaryVersion(prefix)?.let { version ->
        if (isSupportedBinary(version)) return SourceType.GATLING_BINARY
        unsupported()
    }

    val text = prefix.toString(StandardCharsets.ISO_8859_1)
    val firstLine = text.lineSequence().first().removeSuffix("\r")
    if (isJmeterCsvHeader(firstLine)) return SourceType.JMETER_CSV
    if (isJmeterXml(prefix)) return SourceType.JMETER_XML
    if (isSupportedTextRun(firstLine)) return SourceType.GATLING_TEXT
    if (firstLine.startsWith("ASSERTION\t")) {
        val run =
            text
                .lineSequence()
                .map { it.removeSuffix("\r") }
                .dropWhile { it.startsWith("ASSERTION\t") }
                .firstOrNull()
        if (run != null && isSupportedTextRun(run)) return SourceType.GATLING_TEXT
    }
    unsupported()
}

internal fun parseInput(
    input: AcceptedInput,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport =
    when (input.sourceType) {
        SourceType.JMETER_CSV -> parseJtlCsv(input.path, emit, processedBytes, checkCancelled)
        SourceType.JMETER_XML -> parseJtlXml(input.path, emit, processedBytes, checkCancelled)
        SourceType.GATLING_TEXT -> parseGatlingText(input.path, emit, processedBytes, checkCancelled)
        SourceType.GATLING_BINARY -> parseGatlingBinary(input.path, emit, processedBytes, checkCancelled)
    }

private fun binaryVersion(prefix: ByteArray): String? {
    val buffer = ByteBuffer.wrap(prefix)
    if (!buffer.hasRemaining() || buffer.get().toInt() != GATLING_RUN_HEADER) return null
    val version = buffer.readGatlingString(MAX_VERSION_BYTES) ?: return null
    if (version.isEmpty()) return null
    if (version.any { it.code !in 0x20..0x7e }) return null
    val simulationClass = buffer.readGatlingString(MAX_PREFIX_BYTES) ?: return null
    if (simulationClass.isEmpty() || buffer.remaining() < Long.SIZE_BYTES) return null
    if (buffer.long <= 0) return null
    return version
}

private fun ByteBuffer.readGatlingString(maxBytes: Int): String? {
    if (remaining() < Int.SIZE_BYTES) return null
    val length = int
    if (length !in 0..maxBytes) return null
    if (length == 0) return ""
    if (remaining() < length + 1) return null
    val bytes = ByteArray(length)
    get(bytes)
    val coder = get().toInt()
    if (coder !in 0..1 || (coder == 1 && length % 2 != 0)) return null
    return String(bytes, if (coder == 0) StandardCharsets.ISO_8859_1 else StandardCharsets.UTF_16LE)
}

private fun isSupportedBinary(version: String): Boolean {
    val tokens = version.split('.')
    if (tokens.size != 3) return false
    val parts = tokens.map { it.toIntOrNull() ?: return false }
    if (parts.joinToString(".") != version) return false
    if (parts.size != 3 || parts[0] != 3) return false
    return parts[1] in 13..14 || (parts[1] == 15 && parts[2] in 0..1)
}

private fun isSupportedTextRun(line: String): Boolean {
    val fields = line.split('\t')
    if (fields.size != 6 || fields.first() != "RUN") return false
    val version = fields.last().trim()
    val tokens = version.split('.')
    if (tokens.size != 3) return false
    val parts = tokens.map { it.toIntOrNull() ?: return false }
    return parts.joinToString(".") == version && parts[0] == 3 && parts[1] in 9..12
}

private fun isJmeterCsvHeader(line: String): Boolean {
    val columns = line.split(',')
    return JMETER_CSV_REQUIRED_HEADERS.all { required -> columns.count { it == required } == 1 }
}

private fun isJmeterXml(prefix: ByteArray): Boolean =
    try {
        val factory = XMLInputFactory.newDefaultFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        ByteArrayInputStream(prefix).use { input ->
            val reader = factory.createXMLStreamReader(input)
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        return reader.localName == "testResults"
                    }
                }
                false
            } finally {
                reader.close()
            }
        }
    } catch (_: XMLStreamException) {
        false
    }

private fun unsupported(): Nothing = throw IllegalArgumentException("UNSUPPORTED_INPUT")

private const val MAX_PREFIX_BYTES = 4_096
private const val MAX_VERSION_BYTES = 32
private const val GATLING_RUN_HEADER = 0
private val JMETER_CSV_REQUIRED_HEADERS = listOf("timeStamp", "elapsed", "label", "success")

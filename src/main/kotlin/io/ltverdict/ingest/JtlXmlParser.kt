package io.ltverdict.ingest

import java.io.FilterInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLResolver
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

internal fun parseJtlXml(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport {
    checkCancelled()
    var bytesRead = 0L

    return try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val counted =
                object : FilterInputStream(input) {
                    override fun read(): Int = super.read().also { if (it >= 0) report(1) }

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = super.read(buffer, offset, length).also { if (it > 0) report(it) }

                    private fun report(count: Int) {
                        bytesRead += count
                        processedBytes(bytesRead)
                    }
                }
            val reader = secureXmlInputFactory().createXMLStreamReader(counted)

            try {
                parseXmlEvents(reader, emit, checkCancelled)
                ParseReport(RunValidity.VALID, bytesRead, emptyList())
            } finally {
                reader.close()
            }
        }
    } catch (failure: InvalidXml) {
        invalidXmlReport(failure.code, bytesRead)
    } catch (_: XMLStreamException) {
        invalidXmlReport("MALFORMED_JMETER_XML", bytesRead)
    }
}

private fun parseXmlEvents(
    reader: XMLStreamReader,
    emit: (LoadSample) -> Unit,
    checkCancelled: () -> Unit,
) {
    val samples = ArrayDeque<XmlSample>()
    var depth = 0
    var rootSeen = false
    var sampleCount = 0L

    while (reader.hasNext()) {
        checkCancelled()
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> {
                depth++
                if (depth > MAX_XML_DEPTH) invalidXml("RESOURCE_LIMIT_EXCEEDED")
                if (!rootSeen) {
                    if (depth != 1 || reader.localName != "testResults") invalidXml("MALFORMED_JMETER_XML")
                    rootSeen = true
                }
                if (reader.localName.isSampleElement()) {
                    samples.peekLast()?.hasSampleChild = true
                    val path = samples.map { it.sample.label }
                    samples.addLast(XmlSample(reader.readSample(path)))
                }
            }

            XMLStreamConstants.END_ELEMENT -> {
                if (reader.localName.isSampleElement()) {
                    val completed = samples.pollLast() ?: invalidXml("MALFORMED_JMETER_XML")
                    emit(
                        completed.sample.copy(
                            kind =
                                if (completed.hasSampleChild) {
                                    SampleKind.JMETER_CONTAINER
                                } else {
                                    SampleKind.JMETER_SAMPLER
                                },
                        ),
                    )
                    sampleCount++
                }
                depth--
            }

            XMLStreamConstants.DTD,
            XMLStreamConstants.ENTITY_REFERENCE,
            -> invalidXml("UNSAFE_XML")
        }
    }
    if (!rootSeen || depth != 0 || samples.isNotEmpty() || sampleCount == 0L) invalidXml("EMPTY_INPUT")
}

private fun XMLStreamReader.readSample(groupPath: List<String>): LoadSample {
    val timestamp = requiredAttribute("ts").nonNegativeLong()
    val elapsed = requiredAttribute("t").nonNegativeLong()
    val label = requiredAttribute("lb")
    if (label.toByteArray(StandardCharsets.UTF_8).size > MAX_XML_LABEL_BYTES) invalidXml("RESOURCE_LIMIT_EXCEEDED")
    val successful = requiredAttribute("s").strictBoolean()

    return try {
        LoadSample(timestamp, elapsed, label, groupPath, SampleKind.JMETER_SAMPLER, successful)
    } catch (_: IllegalArgumentException) {
        invalidXml("INVALID_SAMPLE_TIMESTAMP")
    }
}

private fun XMLStreamReader.requiredAttribute(name: String): String = getAttributeValue(null, name) ?: invalidXml("MALFORMED_JMETER_XML")

private fun String.nonNegativeLong(): Long =
    takeIf { isNotEmpty() && all { character -> character in '0'..'9' } }
        ?.toLongOrNull()
        ?: invalidXml("MALFORMED_JMETER_XML")

private fun String.strictBoolean(): Boolean =
    when {
        equals("true", ignoreCase = true) -> true
        equals("false", ignoreCase = true) -> false
        else -> invalidXml("MALFORMED_JMETER_XML")
    }

private fun String.isSampleElement(): Boolean = this == "sample" || this == "httpSample"

private fun secureXmlInputFactory(): XMLInputFactory =
    XMLInputFactory.newDefaultFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        xmlResolver = XMLResolver { _, _, _, _ -> throw XMLStreamException("External XML access is disabled") }
    }

private fun invalidXmlReport(
    code: String,
    bytesRead: Long,
) = ParseReport(
    validity = RunValidity.INVALID,
    processedBytes = bytesRead,
    diagnostics = listOf(Diagnostic(code, "JMeter XML input is invalid", bytesRead)),
)

private fun invalidXml(code: String): Nothing = throw InvalidXml(code)

private data class XmlSample(
    val sample: LoadSample,
    var hasSampleChild: Boolean = false,
)

private class InvalidXml(
    val code: String,
) : RuntimeException()

private const val MAX_XML_DEPTH = 64
private const val MAX_XML_LABEL_BYTES = 4_096

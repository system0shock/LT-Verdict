package io.ltverdict.ingest

import com.univocity.parsers.common.TextParsingException
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import com.univocity.parsers.csv.UnescapedQuoteHandling
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun parseJtlCsv(
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
                    private var lineBytes = 0

                    override fun read(): Int = super.read().also { if (it >= 0) report(it) }

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int =
                        super.read(buffer, offset, length).also { count ->
                            if (count > 0) {
                                for (index in offset until offset + count) report(buffer[index].toInt() and 0xff, false)
                                processedBytes(bytesRead)
                            }
                        }

                    private fun report(
                        value: Int,
                        notify: Boolean = true,
                    ) {
                        bytesRead++
                        if (value == '\r'.code || value == '\n'.code) {
                            lineBytes = 0
                        } else if (lineBytes++ == MAX_LINE_BYTES) {
                            invalidCsv("RESOURCE_LIMIT_EXCEEDED")
                        }
                        if (notify) processedBytes(bytesRead)
                    }
                }
            val decoder =
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            val parser = CsvParser(csvSettings())

            parser.beginParsing(RejectUnclosedQuoteReader(InputStreamReader(counted, decoder)))
            try {
                val header = parser.parseNext() ?: invalidCsv("EMPTY_INPUT")
                header.forEach(::checkCsvField)
                val columns = requiredColumns(header)
                var hasRows = false

                while (true) {
                    checkCancelled()
                    val row = parser.parseNext() ?: break
                    if (row.size != header.size) invalidCsv("MALFORMED_JMETER_CSV")
                    row.forEach(::checkCsvField)

                    val label = row[columns.getValue("label")]
                    if (label.utf8Size() > MAX_LABEL_BYTES) invalidCsv("RESOURCE_LIMIT_EXCEEDED")
                    val startedAt = row[columns.getValue("timeStamp")].nonNegativeLong()
                    val elapsed = row[columns.getValue("elapsed")].nonNegativeLong()
                    val successful = row[columns.getValue("success")].strictBoolean()
                    val sample =
                        try {
                            LoadSample(
                                startedAtEpochMillis = startedAt,
                                elapsedMillis = elapsed,
                                label = label,
                                groupPath = emptyList(),
                                kind = SampleKind.JMETER_SAMPLER,
                                successful = successful,
                            )
                        } catch (_: IllegalArgumentException) {
                            invalidCsv("INVALID_SAMPLE_TIMESTAMP")
                        }
                    emit(sample)
                    hasRows = true
                }
                if (!hasRows) invalidCsv("EMPTY_INPUT")
                ParseReport(RunValidity.VALID, bytesRead, emptyList())
            } finally {
                parser.stopParsing()
            }
        }
    } catch (failure: InvalidCsv) {
        invalidCsvReport(failure.code, bytesRead)
    } catch (failure: TextParsingException) {
        val invalid = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<InvalidCsv>().firstOrNull()
        invalidCsvReport(invalid?.code ?: "MALFORMED_JMETER_CSV", bytesRead)
    }
}

private fun csvSettings() =
    CsvParserSettings().apply {
        isLineSeparatorDetectionEnabled = true
        ignoreLeadingWhitespaces = false
        ignoreTrailingWhitespaces = false
        skipEmptyLines = false
        maxColumns = MAX_COLUMNS
        maxCharsPerColumn = MAX_FIELD_BYTES
        isNormalizeLineEndingsWithinQuotes = false
        nullValue = ""
        emptyValue = ""
        unescapedQuoteHandling = UnescapedQuoteHandling.RAISE_ERROR
    }

private fun requiredColumns(header: Array<String>): Map<String, Int> =
    REQUIRED_HEADERS.associateWith { required ->
        header.indices.filter { header[it] == required }.singleOrNull()
            ?: invalidCsv("INVALID_JMETER_CSV_HEADER")
    }

private fun checkCsvField(value: String) {
    if (value.utf8Size() > MAX_FIELD_BYTES) invalidCsv("RESOURCE_LIMIT_EXCEEDED")
}

private fun String.nonNegativeLong(): Long =
    takeIf { isNotEmpty() && all { character -> character in '0'..'9' } }
        ?.toLongOrNull()
        ?: invalidCsv("MALFORMED_JMETER_CSV")

private fun String.strictBoolean(): Boolean =
    when {
        equals("true", ignoreCase = true) -> true
        equals("false", ignoreCase = true) -> false
        else -> invalidCsv("MALFORMED_JMETER_CSV")
    }

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun invalidCsvReport(
    code: String,
    bytesRead: Long,
) = ParseReport(
    validity = RunValidity.INVALID,
    processedBytes = bytesRead,
    diagnostics = listOf(Diagnostic(code, "JMeter CSV input is invalid", bytesRead)),
)

private fun invalidCsv(code: String): Nothing = throw InvalidCsv(code)

private class InvalidCsv(
    val code: String,
) : RuntimeException()

private class RejectUnclosedQuoteReader(
    private val delegate: Reader,
) : Reader() {
    private var oddQuoteCount = false

    override fun read(
        buffer: CharArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = delegate.read(buffer, offset, length)
        if (count == -1) {
            if (oddQuoteCount) throw IOException("Unclosed quoted CSV field")
            return -1
        }
        for (index in offset until offset + count) {
            if (buffer[index] == '\"') oddQuoteCount = !oddQuoteCount
        }
        return count
    }

    override fun close() = delegate.close()
}

private val REQUIRED_HEADERS = listOf("timeStamp", "elapsed", "label", "success")
private const val MAX_COLUMNS = 64
private const val MAX_FIELD_BYTES = 65_536
private const val MAX_LABEL_BYTES = 4_096
private const val MAX_LINE_BYTES = 1_048_576

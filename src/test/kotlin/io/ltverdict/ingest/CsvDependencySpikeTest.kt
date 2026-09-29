package io.ltverdict.ingest

import com.univocity.parsers.common.TextParsingException
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import com.univocity.parsers.csv.UnescapedQuoteHandling
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.Reader
import java.io.StringReader

class CsvDependencySpikeTest {
    @Test
    fun `quoted commas escaped quotes and embedded newlines remain one record`() {
        val rows = parseAll("name,comment\n\"Doe, Jane\",\"say \"\"hi\"\"\nand bye\"\n")

        assertEquals(
            listOf(listOf("name", "comment"), listOf("Doe, Jane", "say \"hi\"\nand bye")),
            rows.map { it.toList() },
        )
    }

    @Test
    fun `LF and CRLF preserve identical untrimmed fields`() {
        val lf = parseAll("  alpha , beta \n")
        val crlf = parseAll("  alpha , beta \r\n")

        assertEquals(listOf(listOf("  alpha ", " beta ")), lf.map { it.toList() })
        assertEquals(lf.map { it.toList() }, crlf.map { it.toList() })
    }

    @Test
    fun `an unclosed quote fails instead of producing a partial record`() {
        assertThrows(TextParsingException::class.java) {
            parseAll("first,second\n\"unterminated,field")
        }
    }

    @Test
    fun `column and field limits reject overflow`() {
        assertThrows(TextParsingException::class.java) {
            parseAll(List(65) { "field" }.joinToString(","))
        }
        assertThrows(TextParsingException::class.java) {
            parseAll("a".repeat(65_537))
        }
    }

    @Test
    fun `one million streamed rows finish under the bounded heap and time limit`() {
        val csv = CsvParser(settings())
        val startNanos = System.nanoTime()
        var rows = 0

        csv.beginParsing(RejectUnclosedQuoteReader(MillionRowsReader()))
        while (csv.parseNext() != null) {
            rows++
        }
        csv.stopParsing()

        assertEquals(1_000_000, rows)
        assertTrue(System.nanoTime() - startNanos < 60_000_000_000L)
    }

    private fun parser(): CsvParser = CsvParser(settings())

    private fun parseAll(input: String) = parser().parseAll(RejectUnclosedQuoteReader(StringReader(input)))

    private fun settings() =
        CsvParserSettings().apply {
            isLineSeparatorDetectionEnabled = true
            ignoreLeadingWhitespaces = false
            ignoreTrailingWhitespaces = false
            maxColumns = 64
            maxCharsPerColumn = 65_536
            unescapedQuoteHandling = UnescapedQuoteHandling.RAISE_ERROR
        }

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

    private class MillionRowsReader : Reader() {
        private var remainingRows = 1_000_000
        private var position = 0

        override fun read(
            buffer: CharArray,
            offset: Int,
            length: Int,
        ): Int {
            var written = 0
            while (written < length && remainingRows > 0) {
                buffer[offset + written++] = "1,2\n"[position++]
                if (position == 4) {
                    position = 0
                    remainingRows--
                }
            }
            return if (written == 0) -1 else written
        }

        override fun close() = Unit
    }
}

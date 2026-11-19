package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class JtlCsvParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `quoted UTF-8 comma and newline labels are exact for LF and CRLF`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            val label = "Привет, \"мир\"${lineEnding}вторая строка"
            val file =
                csv(
                    "timeStamp,elapsed,label,success",
                    "1,2,\"${label.replace("\"", "\"\"")}\",true",
                    lineEnding = lineEnding,
                )
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(listOf(label), samples.map { it.label })
        }
    }

    @Test
    fun `optional URL variants remain flat JMeter samplers`() {
        val variants =
            listOf(
                "timeStamp,elapsed,label,success" to "1,2,no-url,true",
                "timeStamp,elapsed,label,success,URL" to "1,2,empty-url,true,",
                "timeStamp,elapsed,label,success,URL" to "1,2,with-url,true,https://example.invalid/a",
            )

        variants.forEach { (header, row) ->
            val samples = mutableListOf<LoadSample>()
            val report = parseJtlCsv(csv(header, row), samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(SampleKind.JMETER_SAMPLER, samples.single().kind)
            assertEquals(emptyList<String>(), samples.single().groupPath)
        }
    }

    @Test
    fun `missing or duplicate required headers are invalid with diagnostics`() {
        assertInvalid(csv("timeStamp,elapsed,label", "1,2,label"))
        assertInvalid(csv("timeStamp,elapsed,label,label,success", "1,2,a,b,true"))
    }

    @Test
    fun `malformed quote number and boolean are invalid with diagnostics`() {
        listOf(
            "1,2,\"unterminated,true",
            "not-a-number,2,label,true",
            "1,not-a-number,label,true",
            "1,2,label,yes",
        ).forEach { row -> assertInvalid(csv("timeStamp,elapsed,label,success", row)) }
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,ok,true", "", "3,4,also-ok,true"))
    }

    @Test
    fun `input is read on the caller thread so a read failure cannot be missed`() {
        val file = csv("timeStamp,elapsed,label,success", *Array(200_000) { "$it,2,a-fairly-long-request-label-$it,true" })
        val readers = mutableSetOf<Thread>()

        val report = parseJtlCsv(file, {}, { readers += Thread.currentThread() })

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(setOf(Thread.currentThread()), readers)
    }

    @Test
    fun `epoch-seconds timestamps are rejected as a unit error`() {
        val report = parseJtlCsv(csv("timeStamp,elapsed,label,success", "1767225600,200,request,true"), {})

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("INVALID_SAMPLE_TIMESTAMP"), report.diagnostics.map { it.code })
    }

    @Test
    fun `timestamps outside the unit-suspect range stay valid`() {
        listOf(TIMESTAMP_UNIT_SUSPECT_RANGE.first - 1, TIMESTAMP_UNIT_SUSPECT_RANGE.last + 1).forEach { timestamp ->
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(csv("timeStamp,elapsed,label,success", "$timestamp,200,request,true"), samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(timestamp, samples.single().startedAtEpochMillis)
        }
    }

    @Test
    fun `CSV resource limits are invalid with diagnostics`() {
        val header = (listOf("timeStamp", "elapsed", "label", "success") + List(61) { "extra$it" }).joinToString(",")
        val row = (listOf("1", "2", "label", "true") + List(61) { "x" }).joinToString(",")
        assertInvalid(csv(header, row))
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,${"a".repeat(65_537)},true"))
        assertInvalid(csv("timeStamp,elapsed,label,success", "1,2,${"я".repeat(2_049)},true"))
    }

    @Test
    fun `physical text line over 1 MiB is a resource limit`() {
        val header = (listOf("timeStamp", "elapsed", "label", "success") + List(16) { "extra$it" }).joinToString(",")
        val row =
            (
                listOf("1", "2", "label", "true") +
                    List(15) { "x".repeat(65_535) } +
                    "x".repeat(65_522)
            ).joinToString(",")
        assertEquals(1_048_577, row.encodeToByteArray().size)

        val report = parseJtlCsv(csv(header, row), {})

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), report.diagnostics.map { it.code })
    }

    @Test
    fun `successful parse reports monotonic progress ending at file size`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", "3,4,two,false")
        val progress = mutableListOf<Long>()

        val report = parseJtlCsv(file, {}, progress::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(Files.size(file), progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous <= next })
    }

    @Test
    fun `cancellation exception propagates`() {
        val cancelled = IllegalStateException("cancelled")

        val error =
            assertThrows(IllegalStateException::class.java) {
                parseJtlCsv(csv("timeStamp,elapsed,label,success", "1,2,label,true"), {}, checkCancelled = { throw cancelled })
            }

        assertSame(cancelled, error)
    }

    @Test
    fun `mixed JMeter CSV classifies exact empty-dataType parents and keeps paths flat`() {
        val samples = mutableListOf<LoadSample>()
        val report = parseJtlCsv(csv(JMETER_HEADER, jmeterRow("parent", PARENT_MESSAGE, ""), jmeterRow("child")), samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(listOf(SampleKind.JMETER_CONTAINER, SampleKind.JMETER_SAMPLER), samples.map { it.kind })
        assertTrue(samples.all { it.groupPath.isEmpty() })
    }

    @Test
    fun `only parent rows and files without parents remain samplers`() {
        listOf(
            listOf(jmeterRow("first", PARENT_MESSAGE, ""), jmeterRow("second", PARENT_MESSAGE, "")),
            listOf(jmeterRow("first"), jmeterRow("second")),
        ).forEach { rows ->
            val samples = mutableListOf<LoadSample>()
            val report = parseJtlCsv(csv(JMETER_HEADER, *rows.toTypedArray()), samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertTrue(samples.all { it.kind == SampleKind.JMETER_SAMPLER && it.groupPath.isEmpty() })
        }
    }

    @Test
    fun `exact message with text dataType stays sampler but empty dataType lookalike becomes container`() {
        val samples = mutableListOf<LoadSample>()
        val report =
            parseJtlCsv(
                csv(
                    JMETER_HEADER,
                    jmeterRow("parent", PARENT_MESSAGE, ""),
                    jmeterRow("text", PARENT_MESSAGE, "text"),
                    jmeterRow("lookalike", PARENT_MESSAGE, ""),
                    jmeterRow("child"),
                ),
                samples::add,
            )

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(
            listOf(SampleKind.JMETER_CONTAINER, SampleKind.JMETER_SAMPLER, SampleKind.JMETER_CONTAINER, SampleKind.JMETER_SAMPLER),
            samples.map { it.kind },
        )
    }

    @Test
    fun `parent message requires exact case spacing ASCII digits and whole string`() {
        val nonMatches =
            listOf(
                PARENT_MESSAGE.lowercase(),
                " $PARENT_MESSAGE",
                "$PARENT_MESSAGE ",
                "Number of samples in transaction: 2, number of failing samples : 0",
                "Number of samples in transaction :2, number of failing samples : 0",
                "Number of samples in transaction : 2, number of failing samples: 0",
                "$PARENT_MESSAGE trailing",
                "prefix $PARENT_MESSAGE",
                "Number of samples in transaction : \u0663, number of failing samples : 0",
                "Number of samples in transaction : , number of failing samples : 0",
                "Number of samples in transaction : 2, number of failing samples : ",
            )
        val samples = mutableListOf<LoadSample>()
        val rows =
            listOf(jmeterRow("parent", "Number of samples in transaction : 123, number of failing samples : 45", "")) +
                nonMatches.mapIndexed { index, message -> jmeterRow("other$index", message, "") } +
                jmeterRow("child")
        val report = parseJtlCsv(csv(JMETER_HEADER, *rows.toTypedArray()), samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(SampleKind.JMETER_CONTAINER, samples.first().kind)
        assertTrue(samples.drop(1).all { it.kind == SampleKind.JMETER_SAMPLER })
    }

    @Test
    fun `missing optional parent columns leave every row a valid sampler`() {
        listOf("responseMessage", "dataType").forEach { missing ->
            val header = JMETER_HEADER.split(',').filterNot { it == missing }
            val fields = jmeterFields("parent", PARENT_MESSAGE, "").toMutableList()
            if (missing == "responseMessage") fields[8] = PARENT_MESSAGE
            val parent = fields.filterIndexed { index, _ -> index != JMETER_HEADER.split(',').indexOf(missing) }
            val child = jmeterFields("child").filterIndexed { index, _ -> index != JMETER_HEADER.split(',').indexOf(missing) }
            val samples = mutableListOf<LoadSample>()
            val report = parseJtlCsv(csv(header.joinToString(","), csvFields(parent), csvFields(child)), samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(emptyList<Diagnostic>(), report.diagnostics)
            assertTrue(samples.all { it.kind == SampleKind.JMETER_SAMPLER })
        }
    }

    @Test
    fun `duplicate optional parent columns invalidate the header even if the other is absent`() {
        listOf("responseMessage" to "dataType", "dataType" to "responseMessage").forEach { (duplicate, missing) ->
            val columns = JMETER_HEADER.split(',').filterNot { it == missing } + duplicate
            val report = parseJtlCsv(csv(columns.joinToString(","), List(columns.size) { "" }.joinToString(",")), {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("INVALID_JMETER_CSV_HEADER"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `parent preview reports bounded complete progress and checks cancellation`() {
        val rows = listOf(jmeterRow("first", PARENT_MESSAGE, ""), jmeterRow("second", PARENT_MESSAGE, ""))
        val file = csv(JMETER_HEADER, *rows.toTypedArray())
        val progress = mutableListOf<Long>()
        var cancellationChecks = 0

        val report = parseJtlCsv(file, {}, progress::add, { cancellationChecks++ })

        assertEquals(RunValidity.VALID, report.validity)
        assertTrue(progress.all { it <= Files.size(file) })
        assertEquals(Files.size(file), progress.last())
        assertEquals(Files.size(file), report.processedBytes)
        assertTrue(cancellationChecks > rows.size + 1)
    }

    @Test
    fun `parent literal straddling the raw scan buffer boundary is still detected`() {
        val parent = jmeterRow("parent", PARENT_MESSAGE, "")
        val messageOffset = parent.indexOf("Number of")
        val children = mutableListOf<String>()
        var size = JMETER_HEADER.length + 1
        while (size + jmeterRow("x").length + 1 + messageOffset <= 65_526) {
            children += jmeterRow("x")
            size += children.last().length + 1
        }
        children[0] = jmeterRow("x".repeat(1 + 65_526 - (size + messageOffset)))
        val file = csv(JMETER_HEADER, *children.toTypedArray(), parent, jmeterRow("last"))
        assertEquals(65_526, Files.readString(file).indexOf("Number of"))
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(1, samples.count { it.kind == SampleKind.JMETER_CONTAINER })
        assertEquals("parent", samples.single { it.kind == SampleKind.JMETER_CONTAINER }.label)
    }

    @Test
    fun `malformed rows after preview decision remain invalid`() {
        listOf(
            listOf(jmeterRow("parent", PARENT_MESSAGE, ""), jmeterRow("child"), "malformed"),
            listOf(jmeterRow("parent", PARENT_MESSAGE, ""), "malformed"),
        ).forEach { rows ->
            val report = parseJtlCsv(csv(JMETER_HEADER, *rows.toTypedArray()), {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `truncated tail without newline keeps preceding rows and reports exact UTF-8 bytes`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", "3,4,two,false", lineEnding = lineEnding)
            val prefixBytes = Files.size(file)
            val tail = "5,6,Привет😀"
            Files.writeString(file, Files.readString(file) + tail)
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(listOf("one", "two"), samples.map { it.label })
            assertEquals(Files.size(file), report.processedBytes)
            assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code })
            assertEquals(prefixBytes, report.diagnostics.single().sourceOffset)
            assertTrue(
                report.diagnostics
                    .single()
                    .message
                    .contains("discarded_bytes=${tail.encodeToByteArray().size}"),
            )
        }
    }

    @Test
    fun `complete last record without newline is valid without warnings`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", lineEnding = lineEnding)
            Files.writeString(file, Files.readString(file).removeSuffix(lineEnding))
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(listOf("one"), samples.map { it.label })
            assertEquals(emptyList<Diagnostic>(), report.diagnostics)
        }
    }

    @Test
    fun `open quoted tail without newline is discarded including embedded newlines`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            listOf("3,4,\"Привет", "3,4,\"Привет${lineEnding}мир😀", "3,4,label,\"tru").forEach { tail ->
                val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", lineEnding = lineEnding)
                val prefixBytes = Files.size(file)
                Files.writeString(file, Files.readString(file) + tail)
                val samples = mutableListOf<LoadSample>()

                val report = parseJtlCsv(file, samples::add)

                assertEquals(RunValidity.VALID, report.validity)
                assertEquals(listOf("one"), samples.map { it.label })
                assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code })
                assertEquals(prefixBytes, report.diagnostics.single().sourceOffset)
                assertTrue(
                    report.diagnostics
                        .single()
                        .message
                        .contains("discarded_bytes=${tail.encodeToByteArray().size}"),
                )
            }
        }
    }

    @Test
    fun `bad record with newline or in the middle remains malformed`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            listOf("3,4,short", "3,4,\"open", "bad,4,label,true", "3,4,label,yes", "3,4,label,true,extra").forEach { bad ->
                listOf(listOf("1,2,one,true", bad), listOf("1,2,one,true", bad, "5,6,last,true")).forEach { rows ->
                    val report = parseJtlCsv(csv("timeStamp,elapsed,label,success", *rows.toTypedArray(), lineEnding = lineEnding), {})

                    assertEquals(RunValidity.INVALID, report.validity)
                    assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
                }
            }
        }
    }

    @Test
    fun `complete corrupt last record without newline remains malformed`() {
        listOf(
            "bad,4,label,true",
            "3,4,label,yes",
            "3,4,label,t",
            "3,4,label,true,extra",
            "3,4,label,true,\"open",
            "3,4,la\"bel,true",
        ).forEach { tail ->
            val file = csv("timeStamp,elapsed,label,success", "1,2,one,true")
            Files.writeString(file, Files.readString(file) + tail)

            val report = parseJtlCsv(file, {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `truncated tail with corrupt completed required fields remains invalid`() {
        listOf(
            "bad,4,label,true" to "MALFORMED_JMETER_CSV",
            "3,bad,label,true" to "MALFORMED_JMETER_CSV",
            "3,4,label,yes," to "MALFORMED_JMETER_CSV",
            "bad,4,\"open" to "MALFORMED_JMETER_CSV",
            "3,4,label,yes,\"open" to "MALFORMED_JMETER_CSV",
            "1767225600,4,label,true" to "INVALID_SAMPLE_TIMESTAMP",
            "253402300799999,1,label,true" to "INVALID_SAMPLE_TIMESTAMP",
        ).forEach { (tail, code) ->
            val file = csv("timeStamp,elapsed,label,success,extra,extra2", "1,2,one,true,,")
            Files.writeString(file, Files.readString(file) + tail)

            val report = parseJtlCsv(file, {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf(code), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `truncated tail validates elapsed range when timestamp column is still missing`() {
        listOf("253402300800000", "9223372036854775807").forEach { tail ->
            val file = csv("elapsed,timeStamp,label,success,extra", "2,1,one,true,")
            Files.writeString(file, Files.readString(file) + tail + ",")

            val report = parseJtlCsv(file, {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("INVALID_SAMPLE_TIMESTAMP"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `open quote in the header remains malformed without a final newline`() {
        val file = csv("timeStamp,elapsed,label,\"success")
        Files.writeString(file, Files.readString(file).removeSuffix("\n"))

        val report = parseJtlCsv(file, {})

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
    }

    @Test
    fun `CRLF tail with only its final LF missing counts the remaining CR`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,one,true", lineEnding = "\r\n")
        val tail = "3,4,short\r"
        val prefixBytes = Files.size(file)
        Files.writeString(file, Files.readString(file) + tail)

        val report = parseJtlCsv(file, {})

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(prefixBytes, report.diagnostics.single().sourceOffset)
        assertTrue(
            report.diagnostics
                .single()
                .message
                .contains("discarded_bytes=${tail.encodeToByteArray().size}"),
        )
    }

    @Test
    fun `truncated tail does not bypass field and label resource limits`() {
        listOf("3,4,${"я".repeat(2_049)}", "3,4,\"${"a".repeat(4_097)}", "3,4,label,true,${"я".repeat(32_769)}").forEach { tail ->
            val file = csv("timeStamp,elapsed,label,success,extra,extra2", "1,2,one,true,,")
            Files.writeString(file, Files.readString(file) + tail)

            val report = parseJtlCsv(file, {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `header followed only by a truncated tail is empty input`() {
        listOf("1,2,short", "1,2,\"open").forEach { tail ->
            val file = csv("timeStamp,elapsed,label,success")
            Files.writeString(file, Files.readString(file) + tail)

            val report = parseJtlCsv(file, {})

            assertEquals(RunValidity.INVALID, report.validity)
            assertEquals(listOf("EMPTY_INPUT"), report.diagnostics.map { it.code })
        }
    }

    @Test
    fun `parent preview excludes truncated tails and preserves completed mixed parents`() {
        listOf("\n", "\r\n").forEach { lineEnding ->
            listOf("3,4,short", "3,4,\"open", jmeterRow("partial-child").substringBeforeLast(',')).forEach { tail ->
                listOf(listOf(jmeterRow("parent", PARENT_MESSAGE, "")), listOf(jmeterRow("parent", PARENT_MESSAGE, ""), jmeterRow("child")))
                    .forEach { rows ->
                        val file = csv(JMETER_HEADER, *rows.toTypedArray(), lineEnding = lineEnding)
                        Files.writeString(file, Files.readString(file) + tail)
                        val samples = mutableListOf<LoadSample>()

                        val report = parseJtlCsv(file, samples::add)

                        assertEquals(RunValidity.VALID, report.validity)
                        assertEquals(rows.size, samples.size)
                        assertEquals(if (rows.size == 1) SampleKind.JMETER_SAMPLER else SampleKind.JMETER_CONTAINER, samples.first().kind)
                        assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code })
                        assertTrue(
                            report.diagnostics
                                .single()
                                .message
                                .contains("discarded_bytes=${tail.encodeToByteArray().size}"),
                        )
                    }
            }
        }
    }

    @Test
    fun `truncated tail skips the last present required field`() {
        listOf(
            Triple("timeStamp,elapsed,label,success", "3,", 2),
            Triple("timeStamp,elapsed,label,success,bytes", "3,4,a,tr", 8),
            Triple("timeStamp,elapsed,label,success", "1767225600", 10),
            Triple("timeStamp,elapsed,label,success", "bad", 3),
            Triple("timeStamp,elapsed,label,success", "3,4,label,\"t", 12),
            Triple("timeStamp,elapsed,label,success,bytes", "3,4,a,yes", 9),
        ).forEach { (header, tail, discardedBytes) ->
            val file = csv(header, if (header.endsWith(",bytes")) "1,2,label,true,0" else "1,2,label,true")
            val prefixBytes = Files.size(file)
            Files.writeString(file, Files.readString(file) + tail)
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity, tail)
            assertEquals(listOf("label"), samples.map { it.label }, tail)
            assertEquals(Files.size(file), report.processedBytes, tail)
            assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code }, tail)
            assertEquals(prefixBytes, report.diagnostics.single().sourceOffset, tail)
            assertTrue(
                report.diagnostics
                    .single()
                    .message
                    .endsWith("discarded_bytes=$discardedBytes"),
                tail,
            )
        }
    }

    @Test
    fun `timestamp error precedes malformed next record`() {
        val file = csv("timeStamp,elapsed,label,success", "1767225600,2,label,true", "3,4,la\"bel,true")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("INVALID_SAMPLE_TIMESTAMP"), report.diagnostics.map { it.code })
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(Files.size(file), report.diagnostics.single().sourceOffset)
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `label resource limit precedes malformed next record`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,${"a".repeat(4_097)},true", "3,4,la\"bel,true")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), report.diagnostics.map { it.code })
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(Files.size(file), report.diagnostics.single().sourceOffset)
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `valid record is emitted before malformed next record`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,label,true", "3,4,la\"bel,true")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
        assertEquals(Files.size(file), report.processedBytes)
        assertEquals(Files.size(file), report.diagnostics.single().sourceOffset)
        assertEquals(listOf("label"), samples.map { it.label })
    }

    @Test
    fun `CR only separators preserve exact truncated tail offset`() {
        val file = csv("timeStamp,elapsed,label,success", "1,2,label,true", lineEnding = "\r")
        Files.writeString(file, Files.readString(file) + "3,4,short")
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(listOf("label"), samples.map { it.label })
        assertEquals(56L, report.processedBytes)
        assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code })
        assertEquals(47L, report.diagnostics.single().sourceOffset)
        assertTrue(
            report.diagnostics
                .single()
                .message
                .endsWith("discarded_bytes=9"),
        )
    }

    @Test
    fun `truncated quoted field allows whitespace after closing quote`() {
        listOf(" ", "\t").forEach { whitespace ->
            val file = csv("timeStamp,elapsed,label,success", "1,2,label,true")
            val prefixBytes = Files.size(file)
            Files.writeString(file, Files.readString(file) + "3,4,\"b\"$whitespace")
            val samples = mutableListOf<LoadSample>()

            val report = parseJtlCsv(file, samples::add)

            assertEquals(RunValidity.VALID, report.validity)
            assertEquals(listOf("label"), samples.map { it.label })
            assertEquals(listOf("JMETER_CSV_TRUNCATED_TAIL"), report.diagnostics.map { it.code })
            assertEquals(prefixBytes, report.diagnostics.single().sourceOffset)
            assertTrue(
                report.diagnostics
                    .single()
                    .message
                    .endsWith("discarded_bytes=8"),
            )
        }
    }

    @Test
    fun `CR only complete records retain origin quote failure`() {
        val file =
            csv(
                "timeStamp,elapsed,label,success",
                "3,4,la\"bel,true",
                "3,4,\"multiline\nlabel\",false",
                lineEnding = "\r",
            )
        val samples = mutableListOf<LoadSample>()

        val report = parseJtlCsv(file, samples::add)

        assertEquals(RunValidity.INVALID, report.validity)
        assertEquals(listOf("MALFORMED_JMETER_CSV"), report.diagnostics.map { it.code })
        assertEquals(76L, report.processedBytes)
        assertEquals(76L, report.diagnostics.single().sourceOffset)
        assertEquals(listOf("la\"bel"), samples.map { it.label })
    }

    private fun assertInvalid(file: Path) {
        val report = parseJtlCsv(file, {})
        assertEquals(RunValidity.INVALID, report.validity)
        assertTrue(report.diagnostics.isNotEmpty())
    }

    private fun csv(
        header: String,
        vararg rows: String,
        lineEnding: String = "\n",
    ): Path {
        val file = tempDir.resolve("input-${System.nanoTime()}.jtl")
        Files.writeString(file, (listOf(header) + rows).joinToString(lineEnding, postfix = lineEnding))
        return file
    }

    private fun jmeterFields(
        label: String,
        message: String = "OK",
        dataType: String = "text",
    ) = listOf("1767225600000", "100", label, "200", message, "thread", dataType, "true", "", "0", "0", "1", "1", "", "0", "0", "0")

    private fun csvFields(fields: List<String>): String = fields.joinToString(",") { value -> if (',' in value) "\"$value\"" else value }

    private fun jmeterRow(
        label: String,
        message: String = "OK",
        dataType: String = "text",
    ): String = csvFields(jmeterFields(label, message, dataType))

    private companion object {
        const val JMETER_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"
        const val PARENT_MESSAGE = "Number of samples in transaction : 2, number of failing samples : 0"
    }
}

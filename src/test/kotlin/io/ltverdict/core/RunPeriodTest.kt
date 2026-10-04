package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RunPeriodTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `jtl csv recognition reports the sample period and idle gaps between occupied seconds`() {
        val start = 1_767_225_600_000L
        val contiguous =
            csv(
                "timeStamp,elapsed,label,success",
                "$start,500,one,true",
                "${start + 1_000},500,two,true",
                "${start + 2_000},500,three,true",
            )

        val period = recognizeRunPeriod(SourceType.JMETER_CSV, contiguous, "a".repeat(64), 60_000)

        assertEquals(RUN_PERIOD_STATUS_RECOGNIZED, period.status)
        assertEquals("sample-timestamps.v2", period.recognitionMethod)
        assertEquals(start, period.firstSampleEpochMillis)
        assertEquals(start + 2_500, period.lastSampleEpochMillis)
        assertEquals(null, period.longestIdleGapMillis)
        assertEquals(0, period.idleGapCount)

        val idle =
            csv(
                "timeStamp,elapsed,label,success",
                "$start,500,one,true",
                "${start + 1_000},500,two,true",
                "${start + 2_000},500,three,true",
                "${start + 300_000},500,four,true",
            )

        val gapped = recognizeRunPeriod(SourceType.JMETER_CSV, idle, "a".repeat(64), 60_000)

        assertEquals(RUN_PERIOD_STATUS_RECOGNIZED, gapped.status)
        assertEquals(start, gapped.firstSampleEpochMillis)
        assertEquals(start + 300_500, gapped.lastSampleEpochMillis)
        assertEquals(1, gapped.idleGapCount)
        assertEquals(297_000L, gapped.longestIdleGapMillis)
        // Факты не зависят от допуска: тот же вход с другим max_idle_gap_ms даёт байт-идентичный результат.
        assertEquals(gapped, recognizeRunPeriod(SourceType.JMETER_CSV, idle, "a".repeat(64), 600_000))
    }

    @Test
    fun `gatling text recognition mirrors the sample period semantics`() {
        val start = 1_767_225_600_000L
        val log =
            text(
                run(),
                "REQUEST\t\tone\t$start\t${start + 500}\tOK\t ",
                "REQUEST\t\ttwo\t${start + 60_000}\t${start + 60_500}\tOK\t ",
            )

        val period = recognizeRunPeriod(SourceType.GATLING_TEXT, log, "a".repeat(64), 30_000)

        assertEquals(RUN_PERIOD_STATUS_RECOGNIZED, period.status)
        assertEquals("sample-timestamps.v1", period.recognitionMethod)
        assertEquals(start, period.firstSampleEpochMillis)
        assertEquals(start + 60_500, period.lastSampleEpochMillis)
        assertEquals(1, period.idleGapCount)
        assertEquals(59_000L, period.longestIdleGapMillis)
    }

    @Test
    fun `gatling group records fill the span but not the idle gap buckets`() {
        val start = 1_767_225_600_000L
        val log =
            text(
                run(),
                "REQUEST\t\tone\t$start\t${start + 500}\tOK\t ",
                "GROUP\tscene\t$start\t${start + 300_000}\t200\tOK",
                "REQUEST\t\ttwo\t${start + 300_000}\t${start + 300_500}\tOK\t ",
            )

        val period = recognizeRunPeriod(SourceType.GATLING_TEXT, log, "a".repeat(64), 60_000)

        // Span считается по всем kind: min(start) = start, max(end) = start + 300500.
        assertEquals(start, period.firstSampleEpochMillis)
        assertEquals(start + 300_500, period.lastSampleEpochMillis)
        // Бакеты занимают только JMETER_SAMPLER и GATLING_REQUEST: start/1000 = 1767225600 и
        // (start + 300000)/1000 = 1767225900, разница 300 > 1, поэтому простой один и
        // longest = (300 - 1) * 1000 = 299000 ms. Без фильтра kind GROUP заполнил бы все 300 бакетов
        // между ними, и idleGapCount стал бы равен 0.
        assertEquals(1, period.idleGapCount)
        assertEquals(299_000L, period.longestIdleGapMillis)
    }

    @Test
    fun `period bounds ignore the row order of the load file`() {
        val start = 1_767_225_600_000L
        val unordered =
            csv(
                "timeStamp,elapsed,label,success",
                "${start + 60_000},500,late,true",
                "$start,500,early,true",
            )

        val period = recognizeRunPeriod(SourceType.JMETER_CSV, unordered, "a".repeat(64), 60_000)

        assertEquals(RUN_PERIOD_STATUS_RECOGNIZED, period.status)
        assertEquals(start, period.firstSampleEpochMillis)
        assertEquals(start + 60_500, period.lastSampleEpochMillis)
    }

    @Test
    fun `a read failure is not recorded as a fact about the load bytes`() {
        val missing = tempDir.resolve("absent.jtl")

        val failure =
            assertThrows(RunPeriodReadFailure::class.java) {
                recognizeRunPeriod(SourceType.JMETER_CSV, missing, "a".repeat(64), 60_000)
            }

        // Период не возвращается, поэтому вызывающий код не сохраняет артефакт: сбой чтения повторим.
        assertEquals("RUN_PERIOD_READ_FAILURE", failure.message)
    }

    @Test
    fun `invalid input is unrecognized without guessing a period`() {
        val malformed = csv("timeStamp,elapsed,label,success", "not-a-number,2,label,true")

        val period = recognizeRunPeriod(SourceType.JMETER_CSV, malformed, "a".repeat(64), 60_000)

        assertEquals(RUN_PERIOD_STATUS_INVALID_INPUT, period.status)
        assertEquals("sample-timestamps.v2", period.recognitionMethod)
        assertEquals(0L, period.firstSampleEpochMillis)
        assertEquals(0L, period.lastSampleEpochMillis)
        assertEquals(null, period.longestIdleGapMillis)
        assertEquals(0, period.idleGapCount)
        assertEquals("a".repeat(64), period.loadInputSha256)

        // Пустой вход (нет строк) все парсеры отдают как INVALID; NO_SAMPLES остаётся защитным статусом.
        val empty = csv("timeStamp,elapsed,label,success")
        assertEquals(RUN_PERIOD_STATUS_INVALID_INPUT, recognizeRunPeriod(SourceType.JMETER_CSV, empty, "a".repeat(64), 60_000).status)

        val garbage = text("FUTURE\tvalue")
        assertEquals(RUN_PERIOD_STATUS_INVALID_INPUT, recognizeRunPeriod(SourceType.GATLING_TEXT, garbage, "a".repeat(64), 60_000).status)
    }

    @Test
    fun `recognition validates the caller gap contract`() {
        val file = csv("timeStamp,elapsed,label,success", "1767225600000,500,one,true")

        listOf(500L, 1_500L, 0L, -1_000L).forEach { invalid ->
            assertEquals(
                "INVALID_MAX_IDLE_GAP",
                assertThrows(IllegalArgumentException::class.java) {
                    recognizeRunPeriod(SourceType.JMETER_CSV, file, "a".repeat(64), invalid)
                }.message,
            )
        }
    }

    @Test
    fun `published run period examples match the contract validator`() {
        val valid = example("valid/basic")
        assertEquals(valid, validateRunPeriod(valid))
        val csv = example("valid/jmeter-csv")
        assertEquals(csv, validateRunPeriod(csv))
        assertEquals("sample-timestamps.v2", runPeriodFromJson(csv).recognitionMethod)
        val unsupported = JsonObject(csv + ("recognition_method" to JsonPrimitive("sample-timestamps.v3")))
        assertThrows(IllegalArgumentException::class.java) { validateRunPeriod(unsupported) }
        assertThrows(IllegalArgumentException::class.java) {
            runPeriodFromJson(unsupported)
        }
        val invalid = example("invalid/unknown-field")
        assertThrows(IllegalArgumentException::class.java) { validateRunPeriod(invalid) }
        val disagreeing = JsonObject(valid + ("idle_gap_count" to JsonPrimitive(1)))
        assertThrows(IllegalArgumentException::class.java) { validateRunPeriod(disagreeing) }
    }

    @Test
    fun `JMeter XML recognition retains method version one`() {
        val period =
            recognizeRunPeriod(SourceType.JMETER_XML, Path.of("fixtures/slice1/jmeter/xml-5.6.3/input.xml"), "a".repeat(64), 60_000)

        assertEquals("sample-timestamps.v1", period.recognitionMethod)
    }

    @Test
    fun `JMeter CSV parent rows extend bounds but do not occupy idle buckets`() {
        val start = 1_767_225_600_000L
        val header =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"

        fun row(
            timestamp: Long,
            label: String,
            message: String,
            dataType: String,
            elapsed: Long,
        ) = listOf(
            timestamp.toString(),
            elapsed.toString(),
            label,
            "200",
            "\"$message\"",
            "thread",
            dataType,
            "true",
            "",
            "0",
            "0",
            "1",
            "1",
            "",
            "0",
            "0",
            "0",
        ).joinToString(",")
        val file =
            csv(
                header,
                row(start, "child-one", "OK", "text", 100),
                row(start + 2_000, "parent", "Number of samples in transaction : 2, number of failing samples : 0", "", 4_000),
                row(start + 5_000, "child-two", "OK", "text", 100),
            )

        val period = recognizeRunPeriod(SourceType.JMETER_CSV, file, "a".repeat(64), 60_000)

        assertEquals(RUN_PERIOD_STATUS_RECOGNIZED, period.status)
        assertEquals("sample-timestamps.v2", period.recognitionMethod)
        assertEquals(start, period.firstSampleEpochMillis)
        assertEquals(start + 6_000, period.lastSampleEpochMillis)
        assertEquals(1, period.idleGapCount)
        assertEquals(4_000L, period.longestIdleGapMillis)
    }

    private fun example(name: String) =
        Json
            .parseToJsonElement(Files.readString(Path.of("docs/contracts/run-period/v1/examples/$name.json")))
            .jsonObject

    private fun csv(
        header: String,
        vararg rows: String,
    ): Path =
        tempDir
            .resolve("input-${System.nanoTime()}.jtl")
            .also { Files.writeString(it, (listOf(header) + rows).joinToString("\n", postfix = "\n")) }

    private fun text(vararg lines: String): Path =
        tempDir
            .resolve("log-${System.nanoTime()}.log")
            .also { Files.writeString(it, lines.joinToString("\n", postfix = "\n")) }

    private fun run(): String = "RUN\tfixture.FixtureSimulation\tfixturesimulation\t1\t \t3.12.0"
}

package io.ltverdict.ingest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class GatlingBinaryParserTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `official 3_13_5 and 3_15_1 fixtures decode exact samples`() {
        val cases =
            mapOf(
                "3.13.5" to
                    listOf(
                        sample(
                            1_788_212_173_310L,
                            103,
                            "catalog",
                            listOf("checkout"),
                            SampleKind.GATLING_REQUEST,
                            true,
                        ),
                        sample(1_788_212_173_426L, 3, "catalog", listOf("checkout"), SampleKind.GATLING_REQUEST, true),
                        sample(1_788_212_173_430L, 2, "missing", listOf("checkout"), SampleKind.GATLING_REQUEST, false),
                        sample(1_788_212_173_434L, 2, "catalog", listOf("checkout"), SampleKind.GATLING_REQUEST, true),
                        sample(1_788_212_173_295L, 141, "checkout", emptyList(), SampleKind.GATLING_GROUP, false),
                    ),
                "3.15.1" to
                    listOf(
                        sample(1_788_212_198_063L, 78, "catalog", listOf("checkout"), SampleKind.GATLING_REQUEST, true),
                        sample(1_788_212_198_149L, 4, "catalog", listOf("checkout"), SampleKind.GATLING_REQUEST, true),
                        sample(1_788_212_198_153L, 2, "missing", listOf("checkout"), SampleKind.GATLING_REQUEST, false),
                        sample(1_788_212_198_156L, 2, "catalog", listOf("checkout"), SampleKind.GATLING_REQUEST, true),
                        sample(1_788_212_198_048L, 110, "checkout", emptyList(), SampleKind.GATLING_GROUP, false),
                    ),
            )

        cases.forEach { (version, expected) ->
            val path = Path.of("fixtures/slice1/gatling/binary-$version/simulation.log")
            val samples = mutableListOf<LoadSample>()

            val report = parseGatlingBinary(path, samples::add)

            assertEquals(RunValidity.VALID, report.validity, version)
            assertEquals(emptyList<Diagnostic>(), report.diagnostics, version)
            assertEquals(Files.size(path), report.processedBytes, version)
            assertEquals(expected, samples, version)
        }
    }

    @Test
    fun `Run is first and version gate is fail closed`() {
        assertInvalid(binary("request-first.log") { flatRequest() })

        listOf("3.13.0", "3.13.42", "3.14.0", "3.14.42", "3.15.0", "3.15.1").forEach { version ->
            val report = parseGatlingBinary(validLog(version), {})
            assertEquals(RunValidity.VALID, report.validity, version)
        }

        listOf("3.12.9", "3.15.2", "4.0.0", "3.15.1-SNAPSHOT").forEach { version ->
            assertInvalid(validLog(version), version)
        }

        val utf16Version =
            binary("utf16-version.log") {
                run(versionCoder = UTF16)
                flatRequest()
            }
        assertEquals(RunValidity.VALID, parseGatlingBinary(utf16Version, {}).validity)
        assertEquals(SourceType.GATLING_BINARY, detectSource(utf16Version))
    }

    @Test
    fun `LATIN1 UTF16 cache references and timestamp deltas are exact`() {
        val path =
            binary("compact-strings.log") {
                run(start = 1_000, assertions = listOf(byteArrayOf(1, 2, 3)))
                user(start = true, timestampDelta = 1)
                writeByte(REQUEST)
                writeInt(1)
                cachedMiss(1, "café\r\n\t", LATIN1)
                cachedMiss(2, "Привет", UTF16)
                writeInt(7)
                writeInt(12)
                writeBoolean(true)
                cachedMiss(3, "")
                error(4, "ошибка", timestampDelta = 12)
                writeByte(REQUEST)
                writeInt(1)
                cachedHit(1)
                cachedHit(2)
                writeInt(13)
                writeInt(15)
                writeBoolean(false)
                cachedHit(3)
                user(start = false, timestampDelta = 20)
            }
        val samples = mutableListOf<LoadSample>()

        val report = parseGatlingBinary(path, samples::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(
            listOf(
                sample(1_007, 5, "Привет", listOf("café   "), SampleKind.GATLING_REQUEST, true),
                sample(1_013, 2, "Привет", listOf("café   "), SampleKind.GATLING_REQUEST, false),
            ),
            samples,
        )
    }

    @Test
    fun `unknown header coder and cache reference are invalid`() {
        assertInvalid(
            binary("unknown-header.log") {
                run()
                writeByte(99)
            },
        )
        assertInvalid(
            binary("unknown-coder.log") {
                runPrefix(versionCoder = 2)
                writeInt(1)
                compactString("scenario")
                writeInt(0)
                flatRequest()
            },
        )
        assertInvalid(
            binary("unknown-cache.log") {
                run()
                writeByte(REQUEST)
                writeInt(0)
                cachedHit(999)
                writeInt(1)
                writeInt(2)
                writeBoolean(true)
                cachedMiss(1, "")
            },
        )
    }

    @Test
    fun `negative lengths are invalid`() {
        val malformed =
            listOf<DataOutputStream.() -> Unit>(
                {
                    writeByte(RUN)
                    writeInt(-1)
                },
                {
                    runPrefix()
                    writeInt(-1)
                },
                {
                    runPrefix()
                    writeInt(0)
                    writeInt(-1)
                },
                {
                    runPrefix()
                    writeInt(0)
                    writeInt(1)
                    writeInt(-1)
                },
                {
                    run()
                    writeByte(REQUEST)
                    writeInt(-1)
                },
            )

        malformed.forEachIndexed { index, write ->
            assertInvalid(binary("negative-$index.log", write))
        }
    }

    @Test
    fun `bounded string label hierarchy and blob reject max plus one`() {
        val oversizedField =
            binary("oversized-field.log") {
                runPrefix(description = "x".repeat(MAX_FIELD_BYTES + 1))
                writeInt(1)
                compactString("scenario")
                writeInt(0)
                flatRequest()
            }
        val oversizedLabel =
            binary("oversized-label.log") {
                run()
                flatRequest(name = "x".repeat(MAX_LABEL_BYTES + 1))
            }
        val oversizedHierarchy =
            binary("oversized-hierarchy.log") {
                run()
                writeByte(REQUEST)
                writeInt(MAX_HIERARCHY_DEPTH + 1)
                repeat(MAX_HIERARCHY_DEPTH + 1) { cachedMiss(it + 1, "g$it") }
                cachedMiss(MAX_HIERARCHY_DEPTH + 2, "request")
                writeInt(1)
                writeInt(2)
                writeBoolean(true)
                cachedMiss(MAX_HIERARCHY_DEPTH + 3, "")
            }
        val oversizedBlob =
            binary("oversized-blob.log") {
                runPrefix()
                writeInt(1)
                compactString("scenario")
                writeInt(1)
                writeInt(MAX_BINARY_BLOB_BYTES + 1)
                write(ByteArray(MAX_BINARY_BLOB_BYTES + 1))
                flatRequest()
            }

        listOf(oversizedField, oversizedLabel, oversizedHierarchy, oversizedBlob).forEach(::assertResourceLimit)
    }

    @Test
    fun `timestamp overflow range and negative elapsed are invalid`() {
        val paths =
            listOf(
                binary("timestamp-overflow.log") {
                    run(start = Long.MAX_VALUE)
                    flatRequest(startDelta = 1, endDelta = 2)
                },
                binary("timestamp-range.log") {
                    run(start = MAX_TIMESTAMP_EPOCH_MILLIS)
                    flatRequest(startDelta = 0, endDelta = 1)
                },
                binary("negative-elapsed.log") {
                    run()
                    flatRequest(startDelta = 2, endDelta = 1)
                },
            )

        paths.forEach { path ->
            val report = assertInvalid(path)
            assertTrue(report.diagnostics.any { it.code == "INVALID_SAMPLE_TIMESTAMP" })
        }
    }

    @Test
    fun `EOF boundary is valid and truncation validity depends on emitted samples`() {
        val complete =
            binaryBytes {
                run()
                flatRequest()
            }
        val boundarySamples = mutableListOf<LoadSample>()
        val boundary = parseGatlingBinary(writeFile("boundary.log", complete), boundarySamples::add)
        assertEquals(RunValidity.VALID, boundary.validity)
        assertEquals(1, boundarySamples.size)

        val beforeFirst =
            binary("truncated-before-first.log") {
                run()
                writeByte(REQUEST)
                writeInt(0)
                cachedMiss(1, "request")
                writeInt(1)
            }
        val beforeSamples = mutableListOf<LoadSample>()
        val before = parseGatlingBinary(beforeFirst, beforeSamples::add)
        assertEquals(RunValidity.INVALID, before.validity)
        assertTrue(before.diagnostics.isNotEmpty())
        assertTrue(beforeSamples.isEmpty())

        val afterFirst =
            binary("truncated-after-first.log") {
                run()
                flatRequest(cacheBase = 1)
                writeByte(REQUEST)
                writeInt(0)
                cachedMiss(3, "second")
                writeInt(3)
            }
        val afterSamples = mutableListOf<LoadSample>()
        val after = parseGatlingBinary(afterFirst, afterSamples::add)
        assertEquals(RunValidity.DEGRADED, after.validity)
        assertTrue(after.diagnostics.isNotEmpty())
        assertEquals(1, afterSamples.size)
    }

    @Test
    fun `progress is monotonic to file size and cancellation propagates`() {
        val path = validLog("3.15.1")
        val progress = mutableListOf<Long>()

        val report = parseGatlingBinary(path, {}, progress::add)

        assertEquals(RunValidity.VALID, report.validity)
        assertEquals(Files.size(path), report.processedBytes)
        assertEquals(Files.size(path), progress.last())
        assertTrue(progress.zipWithNext().all { (previous, next) -> previous <= next })

        val cancelled = IllegalStateException("cancelled")
        val failure =
            assertThrows(IllegalStateException::class.java) {
                parseGatlingBinary(path, {}, checkCancelled = { throw cancelled })
            }
        assertSame(cancelled, failure)
    }

    @Test
    fun `EOFException from callbacks propagates unchanged`() {
        val path = validLog("3.15.1")

        listOf<(EOFException) -> Unit>(
            { failure -> parseGatlingBinary(path, { throw failure }) },
            { failure -> parseGatlingBinary(path, {}, { throw failure }) },
            { failure ->
                var checks = 0
                parseGatlingBinary(path, {}, checkCancelled = { if (++checks == 2) throw failure })
            },
        ).forEach { invoke ->
            val expected = EOFException("callback")
            assertSame(expected, assertThrows(EOFException::class.java) { invoke(expected) })
        }
    }

    private fun validLog(version: String): Path =
        binary("$version-${System.nanoTime()}.log") {
            run(version)
            flatRequest()
        }

    private fun assertInvalid(
        path: Path,
        message: String? = null,
    ): ParseReport {
        val report = parseGatlingBinary(path, {})
        assertEquals(RunValidity.INVALID, report.validity, message)
        assertTrue(report.diagnostics.isNotEmpty(), message)
        return report
    }

    private fun assertResourceLimit(path: Path) {
        val report = assertInvalid(path)
        assertTrue(report.diagnostics.any { it.code == "RESOURCE_LIMIT_EXCEEDED" }, path.fileName.toString())
    }

    private fun sample(
        startedAt: Long,
        elapsed: Long,
        label: String,
        groupPath: List<String>,
        kind: SampleKind,
        successful: Boolean,
    ) = LoadSample(startedAt, elapsed, label, groupPath, kind, successful)

    private fun binary(
        name: String,
        body: DataOutputStream.() -> Unit,
    ): Path = writeFile(name, binaryBytes(body))

    private fun binaryBytes(body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> body(output) }
        return bytes.toByteArray()
    }

    private fun writeFile(
        name: String,
        bytes: ByteArray,
    ): Path = tempDir.resolve(name).also { Files.write(it, bytes) }

    /*
     * Official v3.13.5/v3.15.1 record table; primitives are big-endian.
     *
     * | tag | body |
     * | 0 Run | string version, string simulationClass, i64 start, string description,
     * |       | i32 scenarioCount + strings, i32 assertionCount + (i32 length + bytes) |
     * | 1 Request | i32 groupCount + cached strings, cached name, i32 startDelta,
     * |           | i32 endDelta, boolean success, cached message |
     * | 2 User | i32 scenarioIndex, boolean start, i32 timestampDelta |
     * | 3 Group | i32 groupCount + cached strings, i32 startDelta, i32 endDelta,
     * |         | i32 cumulatedResponseTime, boolean success |
     * | 4 Error | cached message, i32 timestampDelta |
     *
     * string = i32 byteLength + bytes + coder for non-empty values (0 LATIN1, 1 JDK UTF16).
     * cached string = i32 positive miss index + string, or the negative index for a hit.
     */
    private fun DataOutputStream.run(
        version: String = "3.15.1",
        start: Long = 1_000,
        assertions: List<ByteArray> = emptyList(),
        versionCoder: Int = LATIN1,
    ) {
        runPrefix(version, start, versionCoder = versionCoder)
        writeInt(1)
        compactString("scenario")
        writeInt(assertions.size)
        assertions.forEach { blob ->
            writeInt(blob.size)
            write(blob)
        }
    }

    private fun DataOutputStream.runPrefix(
        version: String = "3.15.1",
        start: Long = 1_000,
        description: String = "",
        versionCoder: Int = LATIN1,
    ) {
        writeByte(RUN)
        compactString(version, versionCoder)
        compactString("fixture.Simulation")
        writeLong(start)
        compactString(description)
    }

    private fun DataOutputStream.flatRequest(
        name: String = "request",
        startDelta: Int = 1,
        endDelta: Int = 2,
        successful: Boolean = true,
        cacheBase: Int = 1,
    ) {
        writeByte(REQUEST)
        writeInt(0)
        cachedMiss(cacheBase, name)
        writeInt(startDelta)
        writeInt(endDelta)
        writeBoolean(successful)
        cachedMiss(cacheBase + 1, "")
    }

    private fun DataOutputStream.user(
        start: Boolean,
        timestampDelta: Int,
    ) {
        writeByte(USER)
        writeInt(0)
        writeBoolean(start)
        writeInt(timestampDelta)
    }

    private fun DataOutputStream.error(
        cacheIndex: Int,
        message: String,
        timestampDelta: Int,
    ) {
        writeByte(ERROR)
        cachedMiss(cacheIndex, message)
        writeInt(timestampDelta)
    }

    private fun DataOutputStream.cachedMiss(
        index: Int,
        value: String,
        coder: Int = if (value.all { it.code <= 0xff }) LATIN1 else UTF16,
    ) {
        writeInt(index)
        compactString(value, coder)
    }

    private fun DataOutputStream.cachedHit(index: Int) = writeInt(-index)

    private fun DataOutputStream.compactString(
        value: String,
        coder: Int = if (value.all { it.code <= 0xff }) LATIN1 else UTF16,
    ) {
        val bytes =
            when (coder) {
                UTF16 -> value.toByteArray(StandardCharsets.UTF_16LE)
                else -> value.toByteArray(StandardCharsets.ISO_8859_1)
            }
        writeInt(bytes.size)
        if (bytes.isNotEmpty()) {
            write(bytes)
            writeByte(coder)
        }
    }

    private companion object {
        const val RUN = 0
        const val REQUEST = 1
        const val USER = 2
        const val ERROR = 4
        const val LATIN1 = 0
        const val UTF16 = 1
        const val MAX_FIELD_BYTES = 65_536
        const val MAX_LABEL_BYTES = 4_096
        const val MAX_HIERARCHY_DEPTH = 64
        const val MAX_BINARY_BLOB_BYTES = 1_048_576
    }
}

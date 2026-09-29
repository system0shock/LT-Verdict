package io.ltverdict.ingest

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun parseGatlingBinary(
    path: Path,
    emit: (LoadSample) -> Unit,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): ParseReport {
    checkCancelled()
    var bytesRead = 0L
    var samplesSeen = false

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
                        preserveCallbackEof { processedBytes(bytesRead) }
                    }
                }
            val data = DataInputStream(BufferedInputStream(counted))
            val reader = GatlingBinaryReader(data, checkCancelled)

            val firstHeader = data.read()
            if (firstHeader == -1) invalidBinary("EMPTY_INPUT")
            if (firstHeader != GATLING_RUN) invalidBinary("MALFORMED_GATLING_BINARY")
            reader.readRun()

            while (true) {
                preserveCallbackEof(checkCancelled)
                val header = data.read()
                if (header == -1) break
                reader.lastHeader = header
                reader.readRecord { sample ->
                    preserveCallbackEof { emit(sample) }
                    samplesSeen = true
                }
            }
            if (!samplesSeen) invalidBinary("EMPTY_INPUT")
            ParseReport(RunValidity.VALID, bytesRead, emptyList())
        }
    } catch (failure: CallbackEof) {
        throw failure.original
    } catch (_: EOFException) {
        binaryReport(
            if (samplesSeen) RunValidity.DEGRADED else RunValidity.INVALID,
            "TRUNCATED_GATLING_BINARY",
            bytesRead,
        )
    } catch (failure: InvalidBinary) {
        binaryReport(RunValidity.INVALID, failure.code, bytesRead)
    }
}

private class GatlingBinaryReader(
    private val input: DataInputStream,
    cancellationCheck: () -> Unit,
) {
    private val checkCancelled = { preserveCallbackEof(cancellationCheck) }
    var lastHeader: Int = GATLING_RUN
    private var runStart = 0L
    private var scenarioCount = 0
    private val strings = HashMap<Int, String>()
    private var cachedStringBytes = 0L

    fun readRun() {
        val version = readString(MAX_BINARY_VERSION_BYTES)
        if (!isSupportedBinaryVersion(version)) invalidBinary("UNSUPPORTED_GATLING_BINARY")
        readString(MAX_BINARY_FIELD_BYTES)
        runStart = input.readLong()
        if (runStart !in 0..MAX_TIMESTAMP_EPOCH_MILLIS) invalidBinary("INVALID_SAMPLE_TIMESTAMP")
        readString(MAX_BINARY_FIELD_BYTES)

        scenarioCount = readCount(MAX_BINARY_LIST_ITEMS)
        repeat(scenarioCount) {
            checkCancelled()
            readString(MAX_BINARY_FIELD_BYTES)
        }
        repeat(readCount(MAX_BINARY_LIST_ITEMS)) {
            checkCancelled()
            val length = readLength(MAX_BINARY_BLOB_BYTES)
            input.readFully(ByteArray(length))
        }
    }

    fun readRecord(emit: (LoadSample) -> Unit) {
        when (lastHeader) {
            GATLING_REQUEST -> emit(readRequest())
            GATLING_USER -> readUser()
            GATLING_GROUP -> emit(readGroup())
            GATLING_ERROR -> readError()
            else -> invalidBinary("MALFORMED_GATLING_BINARY")
        }
    }

    private fun readRequest(): LoadSample {
        val groupPath = readCachedPath(allowEmpty = true)
        val label = readLabel()
        val startedAt = readTimestamp()
        val endedAt = readTimestamp()
        if (endedAt < startedAt) invalidBinary("INVALID_SAMPLE_TIMESTAMP")
        val successful = readBoolean()
        readCachedString()
        return sample(startedAt, endedAt, label, groupPath, SampleKind.GATLING_REQUEST, successful)
    }

    private fun readGroup(): LoadSample {
        val hierarchy = readCachedPath(allowEmpty = false)
        val startedAt = readTimestamp()
        val endedAt = readTimestamp()
        if (endedAt < startedAt) invalidBinary("INVALID_SAMPLE_TIMESTAMP")
        if (input.readInt() < 0) invalidBinary("MALFORMED_GATLING_BINARY")
        val successful = readBoolean()
        return sample(
            startedAt,
            endedAt,
            hierarchy.last(),
            hierarchy.dropLast(1),
            SampleKind.GATLING_GROUP,
            successful,
        )
    }

    private fun readUser() {
        val scenarioIndex = input.readInt()
        if (scenarioIndex !in 0 until scenarioCount) invalidBinary("MALFORMED_GATLING_BINARY")
        readBoolean()
        readTimestamp()
    }

    private fun readError() {
        readCachedString()
        readTimestamp()
    }

    private fun readCachedPath(allowEmpty: Boolean): List<String> {
        val count = readCount(MAX_BINARY_HIERARCHY_DEPTH)
        if (!allowEmpty && count == 0) invalidBinary("MALFORMED_GATLING_BINARY")
        return List(count) {
            checkCancelled()
            readLabel()
        }
    }

    private fun readLabel(): String =
        readCachedString().also {
            if (it.toByteArray(StandardCharsets.UTF_8).size > MAX_BINARY_LABEL_BYTES) {
                invalidBinary("RESOURCE_LIMIT_EXCEEDED")
            }
        }

    private fun readCachedString(): String {
        val index = input.readInt()
        if (index == 0 || index == Int.MIN_VALUE) invalidBinary("MALFORMED_GATLING_BINARY")
        if (index < 0) return strings[-index] ?: invalidBinary("MALFORMED_GATLING_BINARY")
        if (index > MAX_BINARY_CACHE_ENTRIES) invalidBinary("RESOURCE_LIMIT_EXCEEDED")
        if (strings.containsKey(index)) invalidBinary("MALFORMED_GATLING_BINARY")

        val value =
            readString(MAX_BINARY_FIELD_BYTES)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
        cachedStringBytes += value.toByteArray(StandardCharsets.UTF_8).size
        if (cachedStringBytes > MAX_BINARY_CACHE_BYTES) invalidBinary("RESOURCE_LIMIT_EXCEEDED")
        strings[index] = value
        return value
    }

    private fun readString(maxBytes: Int): String {
        val length = readLength(maxBytes)
        if (length == 0) return ""
        val bytes = ByteArray(length)
        input.readFully(bytes)
        val coder = input.readUnsignedByte()
        return when (coder) {
            LATIN1 -> String(bytes, StandardCharsets.ISO_8859_1)
            UTF16 -> {
                if (length % 2 != 0) invalidBinary("MALFORMED_GATLING_BINARY")
                try {
                    StandardCharsets.UTF_16LE
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    invalidBinary("MALFORMED_GATLING_BINARY")
                }
            }

            else -> invalidBinary("MALFORMED_GATLING_BINARY")
        }
    }

    private fun readTimestamp(): Long {
        val delta = input.readInt()
        if (delta < 0) invalidBinary("INVALID_SAMPLE_TIMESTAMP")
        val timestamp =
            try {
                Math.addExact(runStart, delta.toLong())
            } catch (_: ArithmeticException) {
                invalidBinary("INVALID_SAMPLE_TIMESTAMP")
            }
        if (timestamp > MAX_TIMESTAMP_EPOCH_MILLIS) invalidBinary("INVALID_SAMPLE_TIMESTAMP")
        return timestamp
    }

    private fun readBoolean(): Boolean =
        when (input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> invalidBinary("MALFORMED_GATLING_BINARY")
        }

    private fun readLength(max: Int): Int =
        input.readInt().also {
            if (it < 0) invalidBinary("MALFORMED_GATLING_BINARY")
            if (it > max) invalidBinary("RESOURCE_LIMIT_EXCEEDED")
        }

    private fun readCount(max: Int): Int = readLength(max)
}

private fun sample(
    startedAt: Long,
    endedAt: Long,
    label: String,
    groupPath: List<String>,
    kind: SampleKind,
    successful: Boolean,
): LoadSample =
    try {
        LoadSample(startedAt, endedAt - startedAt, label, groupPath, kind, successful)
    } catch (_: IllegalArgumentException) {
        invalidBinary("INVALID_SAMPLE_TIMESTAMP")
    }

private fun isSupportedBinaryVersion(version: String): Boolean {
    val parts = version.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || it.any { character -> character !in '0'..'9' } }) return false
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    return numbers.joinToString(".") == version &&
        numbers[0] == 3 &&
        (numbers[1] in 13..14 || numbers[1] == 15 && numbers[2] in 0..1)
}

private fun binaryReport(
    validity: RunValidity,
    code: String,
    bytesRead: Long,
) = ParseReport(
    validity = validity,
    processedBytes = bytesRead,
    diagnostics = listOf(Diagnostic(code, "Gatling binary input is invalid", bytesRead)),
)

private inline fun preserveCallbackEof(callback: () -> Unit) {
    try {
        callback()
    } catch (failure: EOFException) {
        throw CallbackEof(failure)
    }
}

private fun invalidBinary(code: String): Nothing = throw InvalidBinary(code)

private class CallbackEof(
    val original: EOFException,
) : RuntimeException(original)

private class InvalidBinary(
    val code: String,
) : RuntimeException()

private const val GATLING_RUN = 0
private const val GATLING_REQUEST = 1
private const val GATLING_USER = 2
private const val GATLING_GROUP = 3
private const val GATLING_ERROR = 4
private const val LATIN1 = 0
private const val UTF16 = 1
private const val MAX_BINARY_VERSION_BYTES = 32
private const val MAX_BINARY_FIELD_BYTES = 65_536
private const val MAX_BINARY_LABEL_BYTES = 4_096
private const val MAX_BINARY_HIERARCHY_DEPTH = 64
private const val MAX_BINARY_LIST_ITEMS = 65_536
private const val MAX_BINARY_BLOB_BYTES = 1_048_576
private const val MAX_BINARY_CACHE_ENTRIES = 65_536
private const val MAX_BINARY_CACHE_BYTES = 67_108_864L

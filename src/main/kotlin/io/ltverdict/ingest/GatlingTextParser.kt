package io.ltverdict.ingest

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64

internal fun parseGatlingText(
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
            val buffered = BufferedInputStream(counted)
            var runSeen = false
            var samplesSeen = false

            while (true) {
                checkCancelled()
                val bytes = readBoundedLine(buffered, checkCancelled) ?: break
                val line = decodeUtf8(bytes)
                val fields = splitTabs(line)
                fields.forEach { if (it.encodeToByteArray().size > MAX_TEXT_FIELD_BYTES) invalidText("RESOURCE_LIMIT_EXCEEDED") }

                when (fields.firstOrNull()) {
                    "ASSERTION" -> {
                        if (runSeen || fields.size != 2) invalidText("MALFORMED_GATLING_TEXT")
                        try {
                            Base64.getDecoder().decode(fields[1])
                        } catch (_: IllegalArgumentException) {
                            invalidText("MALFORMED_GATLING_TEXT")
                        }
                    }

                    "RUN" -> {
                        if (runSeen || fields.size != 6 || !isSupportedTextVersion(fields[5])) {
                            invalidText("UNSUPPORTED_GATLING_TEXT")
                        }
                        fields[3].epochMillis()
                        runSeen = true
                    }

                    "USER" -> {
                        requireRun(runSeen)
                        if (fields.size != 4 || fields[2] != "START" && fields[2] != "END") invalidText("MALFORMED_GATLING_TEXT")
                        fields[3].epochMillis()
                    }

                    "REQUEST" -> {
                        requireRun(runSeen)
                        if (fields.size != 7) invalidText("MALFORMED_GATLING_TEXT")
                        val groups = fields[1].groups(allowEmpty = true)
                        emit(textSample(fields, groups, fields[2], SampleKind.GATLING_REQUEST))
                        samplesSeen = true
                    }

                    "GROUP" -> {
                        requireRun(runSeen)
                        if (fields.size != 6) invalidText("MALFORMED_GATLING_TEXT")
                        val groups = fields[1].groups(allowEmpty = false)
                        fields[4].nonNegativeInt()
                        emit(textSample(fields, groups.dropLast(1), groups.last(), SampleKind.GATLING_GROUP))
                        samplesSeen = true
                    }

                    "ERROR" -> {
                        requireRun(runSeen)
                        if (fields.size != 3) invalidText("MALFORMED_GATLING_TEXT")
                        fields[2].epochMillis()
                    }

                    else -> invalidText("MALFORMED_GATLING_TEXT")
                }
            }
            if (!runSeen || !samplesSeen) invalidText("EMPTY_INPUT")
            ParseReport(RunValidity.VALID, bytesRead, emptyList())
        }
    } catch (failure: InvalidText) {
        invalidTextReport(failure.code, bytesRead)
    } catch (_: CharacterCodingException) {
        invalidTextReport("MALFORMED_GATLING_TEXT", bytesRead)
    }
}

private fun textSample(
    fields: List<String>,
    groupPath: List<String>,
    label: String,
    kind: SampleKind,
): LoadSample {
    if (label.encodeToByteArray().size > MAX_GATLING_LABEL_BYTES) invalidText("RESOURCE_LIMIT_EXCEEDED")
    val timestampOffset = if (kind == SampleKind.GATLING_REQUEST) 1 else 0
    val startedAt = fields[2 + timestampOffset].epochMillis()
    val endedAt = fields[3 + timestampOffset].epochMillis()
    if (endedAt < startedAt) invalidText("INVALID_SAMPLE_TIMESTAMP")
    val successful =
        when (fields[5]) {
            "OK" -> true
            "KO" -> false
            else -> invalidText("MALFORMED_GATLING_TEXT")
        }
    return try {
        LoadSample(startedAt, endedAt - startedAt, label, groupPath, kind, successful)
    } catch (_: IllegalArgumentException) {
        invalidText("INVALID_SAMPLE_TIMESTAMP")
    }
}

private fun readBoundedLine(
    input: BufferedInputStream,
    checkCancelled: () -> Unit,
): ByteArray? {
    val line = ByteArrayOutputStream()
    while (true) {
        if (line.size() % DEFAULT_BUFFER_SIZE == 0) checkCancelled()
        val value = input.read()
        if (value == -1 || value == '\n'.code) {
            if (value == -1 && line.size() == 0) return null
            val bytes = line.toByteArray()
            return if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
        }
        if (line.size() == MAX_TEXT_LINE_BYTES) invalidText("RESOURCE_LIMIT_EXCEEDED")
        line.write(value)
    }
}

private fun decodeUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun splitTabs(value: String): List<String> =
    buildList {
        var start = 0
        value.forEachIndexed { index, character ->
            if (character == '\t') {
                add(value.substring(start, index))
                start = index + 1
            }
        }
        add(value.substring(start))
    }

private fun String.groups(allowEmpty: Boolean): List<String> {
    if (isEmpty()) return if (allowEmpty) emptyList() else invalidText("MALFORMED_GATLING_TEXT")
    val groups = split(',')
    if (groups.size > MAX_GATLING_DEPTH || groups.any(String::isEmpty)) invalidText("RESOURCE_LIMIT_EXCEEDED")
    groups.forEach { if (it.encodeToByteArray().size > MAX_GATLING_LABEL_BYTES) invalidText("RESOURCE_LIMIT_EXCEEDED") }
    return groups
}

private fun String.epochMillis(): Long =
    nonNegativeLong().also {
        if (it > MAX_TIMESTAMP_EPOCH_MILLIS) invalidText("INVALID_SAMPLE_TIMESTAMP")
    }

private fun String.nonNegativeLong(): Long =
    takeIf { isNotEmpty() && all { character -> character in '0'..'9' } }
        ?.toLongOrNull()
        ?: invalidText("MALFORMED_GATLING_TEXT")

private fun String.nonNegativeInt(): Int =
    nonNegativeLong().takeIf { it <= Int.MAX_VALUE }?.toInt()
        ?: invalidText("MALFORMED_GATLING_TEXT")

private fun isSupportedTextVersion(version: String): Boolean {
    val parts = version.split('.')
    if (parts.size != 3 || parts.any { it.isEmpty() || it.any { character -> character !in '0'..'9' } }) return false
    val numbers = parts.map { it.toIntOrNull() ?: return false }
    return numbers.joinToString(".") == version && numbers[0] == 3 && numbers[1] in 9..12
}

private fun requireRun(runSeen: Boolean) {
    if (!runSeen) invalidText("MALFORMED_GATLING_TEXT")
}

private fun invalidTextReport(
    code: String,
    bytesRead: Long,
) = ParseReport(
    validity = RunValidity.INVALID,
    processedBytes = bytesRead,
    diagnostics = listOf(Diagnostic(code, "Gatling text input is invalid", bytesRead)),
)

private fun invalidText(code: String): Nothing = throw InvalidText(code)

private class InvalidText(
    val code: String,
) : RuntimeException()

private const val MAX_TEXT_LINE_BYTES = 1_048_576
private const val MAX_TEXT_FIELD_BYTES = 65_536
private const val MAX_GATLING_LABEL_BYTES = 4_096
private const val MAX_GATLING_DEPTH = 64

package io.ltverdict.core

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// W2.6 / ADR 0031: the breakdown of failed samples by (transaction, response code, failure message) for the whole run. It is written
// as its own artifact of the analysis directory: it is a function of the input only and enters neither the identity nor the result.

internal const val ERROR_GROUPS_FILE = "error-groups.json"

internal const val TRACKED_GROUPS_MAX = 2048
internal const val TRACKED_BYTES_MAX = 16L * 1024 * 1024
internal const val ERROR_GROUPS_LISTED_MAX = 20
internal const val ERROR_CODE_CHARS_MAX = 64
internal const val ERROR_MESSAGE_CHARS_MAX = 200

internal class ErrorGroupAccumulator(
    private val runId: String,
    private val trackedGroupsMax: Int = TRACKED_GROUPS_MAX,
    private val trackedBytesMax: Long = TRACKED_BYTES_MAX,
) {
    private class Counter(
        var count: Long,
        var fromEpochMillis: Long,
        var toEpochMillis: Long,
    )

    private data class Key(
        val groupPath: List<String>,
        val label: String,
        val kind: SampleKind,
        val code: String?,
        val message: String?,
        val messageTruncated: Boolean,
    )

    private val groups = HashMap<Key, Counter>()
    private var retainedBytes = 0L
    private var total = 0L
    private var untracked = 0L

    /** Counts a failed sample of the kinds that make up the overall error count; containers repeat their children and are skipped. */
    fun record(sample: LoadSample) {
        if (sample.successful) return
        if (sample.kind != SampleKind.JMETER_SAMPLER && sample.kind != SampleKind.GATLING_REQUEST) return
        total++
        val code = sample.responseCode?.let { cleanErrorText(it, ERROR_CODE_CHARS_MAX).first }
        val (message, truncated) = sample.failureMessage?.let { cleanErrorText(it, ERROR_MESSAGE_CHARS_MAX) } ?: (null to false)
        val key = Key(sample.groupPath.toList(), sample.label, sample.kind, code, message, truncated)
        val existing = groups[key]
        if (existing != null) {
            existing.count++
            existing.fromEpochMillis = minOf(existing.fromEpochMillis, sample.startedAtEpochMillis)
            existing.toEpochMillis = maxOf(existing.toEpochMillis, sample.endedAtEpochMillis)
            return
        }
        val bytes = key.byteSize()
        if (groups.size >= trackedGroupsMax || bytes > trackedBytesMax - retainedBytes) {
            untracked++
            return
        }
        retainedBytes += bytes
        groups[key] = Counter(1, sample.startedAtEpochMillis, sample.endedAtEpochMillis)
    }

    /** The canonical bytes of error-groups.v1, or null when no sampler failed. */
    fun finish(): ByteArray? {
        if (total == 0L) return null
        val ordered =
            groups.entries.sortedWith(
                compareByDescending<Map.Entry<Key, Counter>> { it.value.count }
                    .thenComparator { left, right -> compareKeys(left.key, right.key) },
            )
        val listed = ordered.take(ERROR_GROUPS_LISTED_MAX)
        val omitted = ordered.drop(ERROR_GROUPS_LISTED_MAX)
        return canonicalJson(
            buildJsonObject {
                put("schema_version", "error-groups.v1")
                put("run_id", runId)
                put("scope_note", "whole_run")
                put("total_error_count", total)
                put("tracked_error_count", total - untracked)
                put("untracked_error_count", untracked)
                put("tracked_group_count", groups.size.toLong())
                put("omitted_group_count", omitted.size.toLong())
                put("omitted_error_count", omitted.sumOf { it.value.count })
                put(
                    "limits",
                    buildJsonObject {
                        put("groups_max", ERROR_GROUPS_LISTED_MAX.toLong())
                        put("tracked_groups_max", trackedGroupsMax.toLong())
                        put("code_chars_max", ERROR_CODE_CHARS_MAX.toLong())
                        put("message_chars_max", ERROR_MESSAGE_CHARS_MAX.toLong())
                    },
                )
                put("groups", JsonArray(listed.map { (key, counter) -> groupJson(key, counter) }))
            },
        )
    }

    private fun groupJson(
        key: Key,
        counter: Counter,
    ): JsonObject {
        val scope =
            buildJsonObject {
                put("kind", "transaction")
                put("label", key.label)
                put("group_path", JsonArray(key.groupPath.map(::JsonPrimitive)))
                put("sample_kind", key.kind.name)
            }
        val identity =
            buildJsonObject {
                put("scope", scope)
                put("response_code", key.code?.let(::JsonPrimitive) ?: JsonNull)
                put("message", key.message?.let(::JsonPrimitive) ?: JsonNull)
            }
        return buildJsonObject {
            put("id", "errgrp-" + sha256Hex(canonicalJson(identity)))
            put("scope", scope)
            put("response_code", key.code?.let(::JsonPrimitive) ?: JsonNull)
            put("message", key.message?.let(::JsonPrimitive) ?: JsonNull)
            put("message_truncated", key.messageTruncated)
            put("count", counter.count)
            put("from_epoch_ms", counter.fromEpochMillis)
            put("to_epoch_ms", counter.toEpochMillis)
        }
    }

    private fun Key.byteSize(): Long =
        (groupPath + label + kind.name + (code ?: "") + (message ?: "")).sumOf { it.encodeToByteArray().size.toLong() + 1 } + KEY_OVERHEAD_BYTES

    private fun compareKeys(
        left: Key,
        right: Key,
    ): Int {
        val leftParts = left.parts()
        val rightParts = right.parts()
        for (index in 0 until minOf(leftParts.size, rightParts.size)) {
            val compared = compareUtf8(leftParts[index], rightParts[index])
            if (compared != 0) return compared
        }
        return leftParts.size.compareTo(rightParts.size)
    }

    // The path first, then the label, kind, code and message; a missing code or message sorts before any text.
    private fun Key.parts(): List<String> = groupPath + ("\u0000" + label) + kind.name + (code ?: "\u0000") + (message ?: "\u0000")

    private fun compareUtf8(
        left: String,
        right: String,
    ): Int {
        val leftBytes = left.encodeToByteArray()
        val rightBytes = right.encodeToByteArray()
        for (index in 0 until minOf(leftBytes.size, rightBytes.size)) {
            val compared = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
            if (compared != 0) return compared
        }
        return leftBytes.size.compareTo(rightBytes.size)
    }

    private companion object {
        const val KEY_OVERHEAD_BYTES = 96L
    }
}

/**
 * Failure text is untrusted: the result is single-line text without control, format (bidi, zero-width), line/paragraph separator,
 * unassigned or lone-surrogate characters (each such run becomes one space), trimmed, at most [maxCodePoints] code points followed
 * by an ellipsis when the text was cut. Null for text that is empty after cleaning. The flag says the text was cut.
 */
internal fun cleanErrorText(
    raw: String,
    maxCodePoints: Int,
): Pair<String?, Boolean> {
    val out = StringBuilder()
    var count = 0
    var pendingSpace = false
    var truncated = false
    var index = 0
    while (index < raw.length) {
        val codePoint = raw.codePointAt(index)
        val width = Character.charCount(codePoint)
        index += width
        val separator =
            Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint) ||
                Character.isISOControl(codePoint) ||
                (width == 1 && Character.isSurrogate(codePoint.toChar())) ||
                when (Character.getType(codePoint).toByte()) {
                    Character.FORMAT,
                    Character.LINE_SEPARATOR,
                    Character.PARAGRAPH_SEPARATOR,
                    Character.UNASSIGNED,
                    Character.SURROGATE,
                    -> true
                    else -> false
                }
        if (separator) {
            if (count > 0) pendingSpace = true
            continue
        }
        if (count + (if (pendingSpace) 2 else 1) > maxCodePoints) {
            truncated = true
            break
        }
        if (pendingSpace) {
            out.append(' ')
            count++
            pendingSpace = false
        }
        out.appendCodePoint(codePoint)
        count++
    }
    if (out.isEmpty()) return null to false
    if (truncated) out.append('…')
    return out.toString() to truncated
}

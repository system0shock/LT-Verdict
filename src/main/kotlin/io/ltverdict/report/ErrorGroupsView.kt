package io.ltverdict.report

import io.ltverdict.core.ERROR_CODE_CHARS_MAX
import io.ltverdict.core.ERROR_GROUPS_FILE
import io.ltverdict.core.ERROR_GROUPS_LISTED_MAX
import io.ltverdict.core.ERROR_MESSAGE_CHARS_MAX
import io.ltverdict.core.cleanErrorText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// W2.6 / ADR 0031: the words and rows of the error breakdown, made at the moment of rendering from the error-groups.json artifact of
// the analysis directory. One reader for the HTML report, AsciiDoc, Confluence and the CLI summary. The artifact is untrusted data:
// every text is cleaned again here and the file is bounded before it is parsed.

private const val MAX_ERROR_GROUPS_BYTES = 1_048_576L
private const val MAX_ERROR_GROUPS_DEPTH = 8
private const val MAX_TRANSACTION_CHARS = 300

/** What a column shows for a group without a code or without a message. */
internal const val NONE = "—"

internal class ErrorGroupRow(
    val count: Long,
    /** Percent of all errors of the run, one decimal. */
    val share: String,
    val code: String,
    val message: String,
    val transaction: String,
    /** The machine values for the JSON summary: the code and the message cleaned, the scope as the artifact has it. */
    val codeValue: String?,
    val messageValue: String?,
    val label: String,
    val groupPath: List<String>,
    val sampleKind: String?,
)

internal class ErrorGroupsView(
    val total: Long,
    val rows: List<ErrorGroupRow>,
    /** Words about scope and about what is not listed; empty rows mean the breakdown is unavailable. */
    val notes: List<String>,
)

/** The bytes of error-groups.json of an analysis directory, or null when there is none or it is not a plain, small file. */
internal fun readErrorGroupsFile(analysisDirectory: Path): ByteArray? {
    val file = analysisDirectory.resolve(ERROR_GROUPS_FILE)
    return try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_ERROR_GROUPS_BYTES) {
            null
        } else {
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_ERROR_GROUPS_BYTES.toInt() + 1) }
                .takeIf { it.size <= MAX_ERROR_GROUPS_BYTES }
        }
    } catch (_: java.io.IOException) {
        null
    }
}

/**
 * The breakdown of the errors of a result: rows when [errorGroups] is a valid artifact of this run, the unavailable sentence when the
 * result has errors but no valid artifact (an analysis created before the breakdown existed), null when there is nothing to say.
 */
internal fun errorGroupsView(
    result: JsonObject,
    errorGroups: ByteArray?,
): ErrorGroupsView? {
    val overallErrors = overallErrorCount(result)
    val parsed = errorGroups?.let { parseErrorGroups(it, (result["run_id"] as? JsonPrimitive)?.content, stageNotice(result) != null) }
    if (parsed == null) {
        return if (overallErrors != null && overallErrors > 0) {
            ErrorGroupsView(overallErrors, emptyList(), listOf("Разбивка ошибок недоступна: анализ создан до появления разбивки или её файл повреждён."))
        } else {
            null
        }
    }
    return parsed
}

private fun overallErrorCount(result: JsonObject): Long? =
    (result["evidence"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull {
            (it["type"] as? JsonPrimitive)?.content == "metric_summary" &&
                (it["scope"] as? JsonObject)?.get("kind")?.let { kind -> (kind as? JsonPrimitive)?.content } == "overall" &&
                (it["window_id"] == null || it["window_id"] is JsonNull)
        }?.get("error_count")
        ?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() }

private fun parseErrorGroups(
    bytes: ByteArray,
    runId: String?,
    staged: Boolean,
): ErrorGroupsView? =
    try {
        if (jsonDepth(bytes) > MAX_ERROR_GROUPS_DEPTH) return null
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
        if (root.text("schema_version") != "error-groups.v1" || root.text("run_id") != runId) return null
        val total = root.count("total_error_count") ?: return null
        val untracked = root.count("untracked_error_count") ?: return null
        val omittedGroups = root.count("omitted_group_count") ?: return null
        val omittedErrors = root.count("omitted_error_count") ?: return null
        val groups = (root["groups"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
        if (groups.size > ERROR_GROUPS_LISTED_MAX || total == 0L) return null
        val rows =
            groups.map { group ->
                val count = group.count("count") ?: return null
                val scope = group["scope"] as? JsonObject ?: return null
                val path = (scope["group_path"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { v -> v is JsonNull }?.content }
                val label = scope.text("label") ?: return null
                val code = group.text("response_code")?.let { cleanErrorText(it, ERROR_CODE_CHARS_MAX + 1).first }
                val message = group.text("message")?.let { cleanErrorText(it, ERROR_MESSAGE_CHARS_MAX + 1).first }
                ErrorGroupRow(
                    count = count,
                    share = percent(count, total),
                    code = code ?: NONE,
                    message = message ?: NONE,
                    transaction = cleanErrorText((path + label).joinToString(" / "), MAX_TRANSACTION_CHARS).first ?: NONE,
                    codeValue = code,
                    messageValue = message,
                    label = label,
                    groupPath = path,
                    sampleKind = scope.text("sample_kind"),
                )
            }
        val notes =
            mutableListOf(
                if (staged) "Ошибки за весь прогон; при вердикте по окну steady это справочная величина." else "Ошибки за весь прогон.",
            )
        notes += "Показано групп: ${rows.size}, всего ошибок: $total."
        if (omittedGroups > 0) notes += "Ещё $omittedGroups групп ($omittedErrors ошибок) не показаны."
        if (untracked > 0) notes += "Ещё $untracked ошибок не разложены по группам: различных групп слишком много (лимит учёта)."
        ErrorGroupsView(total, rows, notes)
    } catch (_: RuntimeException) {
        null
    }

private fun percent(
    count: Long,
    total: Long,
): String =
    BigDecimal(count).multiply(BigDecimal(100)).divide(BigDecimal(total), 1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " %"

private fun jsonDepth(bytes: ByteArray): Int {
    var depth = 0
    var maximum = 0
    var inString = false
    var escaped = false
    for (value in bytes) {
        val char = value.toInt().toChar()
        when {
            inString -> if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
            char == '"' -> inString = true
            char == '{' || char == '[' -> maximum = maxOf(maximum, ++depth)
            char == '}' || char == ']' -> depth--
        }
    }
    return maximum
}

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.count(name: String): Long? = text(name)?.toLongOrNull()?.takeIf { it >= 0 }

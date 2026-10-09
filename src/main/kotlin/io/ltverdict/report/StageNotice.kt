package io.ltverdict.report

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ADR 0030, R10: the words that say a verdict is by the steady window are made here from the stage_binding item of the result at
// the moment of rendering; the result does not store them, so the wording can change without a new analysis_id. One function for
// the HTML report, AsciiDoc and Confluence (the summary.txt and junit.xml tokens are fixed English keys built in CliArtifacts.kt).

internal const val STAGE_DECIDED_PHRASE = "Вердикт посчитан по окну steady, разгон исключён"

internal const val STAGE_REFERENCE_NOTE = "Метрики по всему прогону справочные: они включают разгон и остановку и не определяют вердикт."

internal data class StageRow(
    val id: String,
    val role: String,
    val offsets: String,
    val utc: String,
    val epoch: String,
    val clipped: String,
)

internal class StageNotice(
    /** The fixed phrase for a decided verdict, the neutral sentence otherwise. */
    val phrase: String,
    /** The window, its bounds and the excluded time. */
    val detail: String,
    /** A short scope for a list of facts. */
    val scope: String,
    /** Evaluated and excluded time of the whole run. */
    val totals: String,
    val stages: List<StageRow>,
)

/** Null when the result has no stage_binding (a run without stages) or the item cannot be read. */
internal fun stageNotice(result: JsonObject): StageNotice? {
    val binding =
        (result["evidence"] as? JsonArray)
            .orEmpty()
            .firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { item -> item.text("type") == "stage_binding" } }
            ?: return null
    val ids = (binding["evaluated_window_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
    val excluded = binding.long("excluded_millis") ?: return null
    val evaluated = binding.long("evaluated_millis") ?: return null
    val stages = (binding["stages"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val rows =
        stages.map { stage ->
            val from = stage.long("from_epoch_ms") ?: return null
            val to = stage.long("to_epoch_ms") ?: return null
            StageRow(
                stage.text("id") ?: return null,
                stage.text("role") ?: return null,
                "${stage.long("from_offset_ms") ?: return null} – ${stage.long("to_offset_ms") ?: return null}",
                "${utcText(from)} – ${utcText(to)}",
                "$from – $to",
                if (stage.text("clipped_to_run_end") == "true") "да" else "—",
            )
        }
    val windows = mutableListOf<String>()
    for (stage in stages.filter { it.text("role") == "steady" }) {
        val from = stage.long("from_epoch_ms") ?: return null
        val to = stage.long("to_epoch_ms") ?: return null
        windows += "${stage.text("id")}, ${durationText(to - from)}, ${utcText(from)} – ${utcText(to)}"
    }
    val verdict = result.text("policy_verdict") ?: "—"
    val decided = result.text("run_validity") == "VALID" && (verdict == "PASS" || verdict == "FAIL")
    return StageNotice(
        phrase =
            if (decided) {
                STAGE_DECIDED_PHRASE
            } else {
                "Окно steady задано (${ids.joinToString(", ")}), разгон исключён из метрик окна; вердикт: $verdict"
            },
        detail = "Окно вердикта: ${windows.joinToString("; ")}. Исключено: ${durationText(excluded)}.",
        scope = "окно steady (${ids.joinToString(", ")}), разгон исключён",
        totals = "Оценено: ${durationText(evaluated)}, исключено: ${durationText(excluded)}.",
        stages = rows,
    )
}

// The same text as formatDuration of the UI: seconds below a minute, minutes above, up to two digits, a decimal comma.
private fun durationText(milliseconds: Long): String {
    val seconds = milliseconds < 60_000
    val number =
        BigDecimal(milliseconds)
            .divide(BigDecimal(if (seconds) 1_000 else 60_000), 2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    val whole =
        number
            .substringBefore('.')
            .reversed()
            .chunked(3)
            .joinToString("\u00A0")
            .reversed()
    val fraction = number.substringAfter('.', "").let { if (it.isEmpty()) "" else ",$it" }
    return "$whole$fraction ${if (seconds) "с" else "мин"}"
}

private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

private fun utcText(epochMillis: Long): String = "${UTC_FORMAT.format(Instant.ofEpochMilli(epochMillis))} UTC"

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.long(name: String): Long? = text(name)?.toLongOrNull()

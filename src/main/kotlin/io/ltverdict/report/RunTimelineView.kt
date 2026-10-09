package io.ltverdict.report

import java.time.Instant

// W2.6 PR 2: what the HTML report says about the run itself. Made at the moment of rendering from files that already lie in the analysis
// directory (run.json, normalized-1s.ndjson, rollup-60s.ndjson); nothing of it enters analysis-result or identity.

internal class RunTimeline(
    /** UTC start and end of the run (run.json); both null when the document is missing, unreadable or inconsistent. */
    val startedAt: Instant?,
    val endedAt: Instant?,
    /** The busiest second of the whole run: requests started in it, and the UTC second (null without run.json). */
    val peakRps: Long?,
    val peakAt: Instant?,
    /** The inline load chart and the page rules it needs; both null when the chart is unavailable. */
    val chartSvg: String?,
    val chartCss: String?,
)

/** "1 ч 2 мин 3,4 с": the duration rounded to 0.1 s, zero units above the first non-zero one omitted, seconds always shown. */
internal fun formatRunDuration(millis: Long): String {
    val tenths = (millis + 50) / 100
    val hours = tenths / 36_000
    val minutes = tenths / 600 % 60
    val seconds = tenths % 600
    val secondsText = "${seconds / 10}" + if (seconds % 10 == 0L) "" else ",${seconds % 10}"
    return when {
        hours > 0 -> "$hours ч $minutes мин $secondsText с"
        minutes > 0 -> "$minutes мин $secondsText с"
        else -> "$secondsText с"
    }
}

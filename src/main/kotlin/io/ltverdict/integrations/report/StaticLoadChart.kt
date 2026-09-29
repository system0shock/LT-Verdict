package io.ltverdict.integrations.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.HdrHistogram.PackedHistogram
import java.io.IOException
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.Locale

internal fun renderSavedLoadChart(
    path: Path,
    rollup: Int = 60,
): ByteArray {
    validateRollup(rollup)
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return unavailableChart("No saved load buckets")
    val rows = ArrayList<JsonObject>(MAX_BUCKETS)
    var truncated = false
    try {
        Files.newBufferedReader(path, UTF_8).use { reader ->
            repeat(MAX_BUCKETS + 1) { index ->
                val line = reader.boundedLine() ?: return@use
                if (index == MAX_BUCKETS) {
                    truncated = true
                    return@use
                }
                rows += Json.parseToJsonElement(line).jsonObject
            }
        }
    } catch (_: IOException) {
        return unavailableChart("Saved load buckets cannot be read")
    } catch (failure: IllegalArgumentException) {
        if (failure.message == INVALID_BUCKETS) throw failure
        invalidBuckets()
    }
    return renderLoadChart(rows, rollup, truncated)
}

internal fun renderLoadChart(
    buckets: List<JsonObject>,
    rollup: Int,
    truncated: Boolean = false,
): ByteArray {
    validateRollup(rollup)
    if (buckets.isEmpty()) return unavailableChart("No saved load buckets")
    val data =
        try {
            buckets.take(MAX_BUCKETS).map(::decodeBucket).sortedBy(Bucket::start)
        } catch (failure: RuntimeException) {
            if (failure.message == INVALID_BUCKETS) throw failure
            invalidBuckets()
        }
    if (data.zipWithNext().any { (left, right) -> left.start >= right.start }) invalidBuckets()
    val first = data.first().start
    val rollupMillis = rollup * 1_000L
    val span = maxOf(data.last().start - first, rollupMillis)
    val limited = truncated || buckets.size > MAX_BUCKETS
    val charts =
        listOf(
            Chart("rps", "Requests per second", "RPS") { it.samples.toDouble() / rollup },
            Chart("errors", "Errors per bin", "count/bin") { it.errors.toDouble() },
            Chart("p95", "P95 latency", "ms") { it.p95.toDouble() },
        )
    val panels = charts.mapIndexed { index, chart -> panel(chart, index, data, first, span, rollupMillis) }.joinToString("")
    val note =
        if (limited) {
            "<text class=\"warning\" x=\"507\" y=\"648\" text-anchor=\"middle\">First 500 bins shown</text>"
        } else {
            ""
        }
    return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 660"
role="img" aria-labelledby="load-chart-title load-chart-description"
data-bins="${data.size}" data-rollup-seconds="$rollup" data-truncated="$limited">
<title id="load-chart-title">LT Verdict load chart</title>
<desc id="load-chart-description">Relative time on a common timeline; gaps are not interpolated.</desc>$STYLE
<text class="heading" x="84" y="30">Load test metrics</text>
<text class="meta" x="930" y="30" text-anchor="end">Rollup: ${rollup}s</text>$panels
<text class="label" x="507" y="624" text-anchor="middle">Time from run start</text>$note
</svg>""".encodeToByteArray()
}

private fun decodeBucket(source: JsonObject): Bucket {
    val start = source.long("bucket_start_ms")
    val samples = source.long("sample_count")
    val errors = source.long("error_count")
    val max = source.long("max_latency_ms")
    val encoded = source["hdr_v2_base64"]?.jsonPrimitive?.content ?: invalidBuckets()
    if (start !in 0..MAX_TIMESTAMP || samples < 0 || errors !in 0..samples || max < 0 || encoded.length !in 1..MAX_HISTOGRAM_CHARS) {
        invalidBuckets()
    }
    val histogram =
        PackedHistogram.decodeFromCompressedByteBuffer(
            ByteBuffer.wrap(Base64.getDecoder().decode(encoded)),
            MAX_LATENCY,
        )
    if (histogram.totalCount != samples || !histogram.valuesAreEquivalent(max, histogram.maxValue)) invalidBuckets()
    return Bucket(start, samples, errors, histogram.getValueAtPercentile(95.0))
}

private fun panel(
    chart: Chart,
    index: Int,
    data: List<Bucket>,
    first: Long,
    span: Long,
    rollupMillis: Long,
): String {
    val top = 74.0 + index * 184.0
    val bottom = top + 120.0
    val maximum = maxOf(1.0, data.maxOf(chart.value))
    val segments = mutableListOf<MutableList<Bucket>>()
    data.forEach { bucket ->
        if (segments.isEmpty() || bucket.start > segments.last().last().start + rollupMillis) segments.add(mutableListOf())
        segments.last().add(bucket)
    }
    val lines =
        segments.joinToString("") { segment ->
            val points =
                segment.joinToString(" ") { bucket ->
                    val x = 84.0 + (bucket.start - first).toDouble() / span * 846.0
                    val y = top + (1 - chart.value(bucket) / maximum) * 120.0
                    "${x.number()},${y.number()}"
                }
            val dot =
                if (segment.size == 1) {
                    points.split(',').let { "<circle class=\"point series-${chart.id}\" cx=\"${it[0]}\" cy=\"${it[1]}\" r=\"3\"/>" }
                } else {
                    ""
                }
            "<polyline class=\"series series-${chart.id}\" points=\"$points\"/>$dot"
        }
    return """<g aria-label="${chart.label.xml()}">
<text class="label" x="84" y="${(top - 12).number()}">${chart.label.xml()}</text>
<text class="meta" x="930" y="${(top - 12).number()}" text-anchor="end">max ${maximum.number()} ${chart.unit.xml()}</text>
<line class="axis" x1="84" y1="${top.number()}" x2="84" y2="${bottom.number()}"/>
<line class="axis" x1="84" y1="${bottom.number()}" x2="930" y2="${bottom.number()}"/>$lines<text class="meta" x="84" y="${(bottom + 18).number()}">0 s</text>
<text class="meta" x="930" y="${(bottom + 18).number()}" text-anchor="end">${(span / 1_000.0).number()} s</text>
</g>"""
}

private fun Reader.boundedLine(): String? {
    val result = StringBuilder()
    while (result.length <= MAX_ROW_CHARS) {
        val next = read()
        if (next < 0) return if (result.isEmpty()) null else result.toString()
        if (next == '\n'.code) return result.toString().removeSuffix("\r")
        result.append(next.toChar())
    }
    invalidBuckets()
}

private fun unavailableChart(message: String) =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 960 180" role="img">
<title>Load chart unavailable</title>$STYLE
<text class="heading" x="480" y="80" text-anchor="middle">Load chart unavailable</text>
<text class="meta" x="480" y="112" text-anchor="middle">${message.xml()}</text>
</svg>""".encodeToByteArray()

private fun JsonObject.long(name: String): Long = this[name]?.jsonPrimitive?.long ?: invalidBuckets()

private fun Double.number() = String.format(Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')

private fun String.xml() =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun validateRollup(rollup: Int) = require(rollup in setOf(1, 10, 30, 60)) { "ROLLUP_INVALID" }

private fun invalidBuckets(): Nothing = throw IllegalArgumentException(INVALID_BUCKETS)

private data class Bucket(
    val start: Long,
    val samples: Long,
    val errors: Long,
    val p95: Long,
)

private data class Chart(
    val id: String,
    val label: String,
    val unit: String,
    val value: (Bucket) -> Double,
)

private const val MAX_BUCKETS = 500
private const val MAX_ROW_CHARS = 524_288
private const val MAX_HISTOGRAM_CHARS = 262_144
private const val MAX_TIMESTAMP = 253_402_300_799_999L
private const val MAX_LATENCY = 86_400_000L
private const val INVALID_BUCKETS = "SAVED_BUCKETS_INVALID"
private const val STYLE =
    "<style>text{font-family:system-ui,sans-serif;fill:#182033}" +
        ".heading{font-size:22px;font-weight:700}" +
        ".label{font-size:15px;font-weight:600}" +
        ".meta{font-size:12px;fill:#556070}" +
        ".warning{font-size:12px;fill:#8a4b00}" +
        ".axis{stroke:#8a94a6}" +
        ".series-rps{stroke:#1769aa}" +
        ".series-errors{stroke:#ba2d0b}" +
        ".series-p95{stroke:#6f42c1}" +
        ".series{fill:none;stroke-width:2}" +
        ".point{stroke:none}" +
        ".point.series-rps{fill:#1769aa}" +
        ".point.series-errors{fill:#ba2d0b}" +
        ".point.series-p95{fill:#6f42c1}" +
        "</style>"

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

/** The load chart for the HTML report: markup without a style element, and the page rules (scoped to the chart) it needs. */
internal class InlineLoadChart(
    val svg: String,
    val css: String,
)

/**
 * W2.6 PR 2: the minute chart for the body of the HTML report, or null when the saved buckets are missing or unusable (the report then
 * says so). Reads up to [INLINE_MAX_BUCKETS] rows of [path] without following a link and draws at most [INLINE_MAX_POINTS] points per
 * panel: neighbouring minutes are summed (requests, errors) or maximised (p95) in groups of one size, counted from the first minute.
 */
internal fun renderInlineLoadChart(path: Path): InlineLoadChart? {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
    val data = ArrayList<Bucket>()
    var truncated = false
    var bytes = 0L
    try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).bufferedReader(UTF_8).use { reader ->
            while (true) {
                val line = reader.boundedLine() ?: break
                if (data.size == INLINE_MAX_BUCKETS) {
                    truncated = true
                    break
                }
                bytes += line.toByteArray(UTF_8).size + 1L
                if (bytes > INLINE_MAX_BYTES) return null
                val bucket = decodeBucket(Json.parseToJsonElement(line).jsonObject)
                // The analysis writes whole minutes; anything else would defeat the bound on the number of points.
                if (bucket.start % MINUTE_MILLIS != 0L) return null
                data += bucket
            }
        }
    } catch (_: Exception) {
        // IOException, RuntimeException and the DataFormatException of a histogram whose compressed bytes are damaged.
        return null
    }
    if (data.isEmpty()) return null
    data.sortBy(Bucket::start)
    if (data.zipWithNext().any { (left, right) -> left.start >= right.start }) return null
    return try {
        InlineLoadChart(drawLoadChart(data, 60, truncated, inline = true).decodeToString(), chartRules(INLINE_SCOPE) + INLINE_FRAME)
    } catch (_: ArithmeticException) {
        null
    }
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
    return drawLoadChart(data, rollup, truncated || buckets.size > MAX_BUCKETS, inline = false)
}

private fun drawLoadChart(
    data: List<Bucket>,
    rollup: Int,
    limited: Boolean,
    inline: Boolean,
): ByteArray {
    val text = if (inline) RUSSIAN else ENGLISH
    val rollupMillis = rollup * 1_000L
    val groupBins = if (inline) groupSize(data, rollupMillis) else 1
    val step = rollupMillis * groupBins
    val points = if (groupBins == 1) data else group(data, step)
    val first = points.first().start
    val span = maxOf(points.last().start - first, step)
    val p95Label = if (groupBins == 1) text.p95 else "${text.p95} (${text.p95Group})"
    val charts =
        listOf(
            Chart("rps", text.rps, text.rpsUnit) { it.samples.toDouble() / (rollup * groupBins) },
            Chart("errors", text.errors, text.errorsUnit) { it.errors.toDouble() },
            Chart("p95", p95Label, text.p95Unit) { it.p95.toDouble() },
        )
    val panels = charts.mapIndexed { index, chart -> panel(chart, index, points, first, span, step, text) }.joinToString("")
    val notes =
        buildList {
            if (inline && groupBins > 1) add(text.grouped(groupBins))
            if (limited) add(if (inline) text.cut(INLINE_MAX_BUCKETS) else "First 500 bins shown")
        }
    val note =
        notes
            .mapIndexed { index, line ->
                val y = if (notes.size == 1) 648 else 640 + index * 16
                "<text class=\"warning\" x=\"507\" y=\"$y\" text-anchor=\"middle\">${line.xml()}</text>"
            }.joinToString("")
    val frame = if (inline) " class=\"load-chart\"" else ""
    val inlineData = if (inline) " data-group-bins=\"$groupBins\" data-source-bins=\"${data.size}\"" else ""
    val meta = if (groupBins == 1) text.rollup(rollup) else text.rollupGroup(groupBins)
    val description = if (groupBins == 1) text.description else "${text.description} ${text.grouped(groupBins)}"
    return """<svg xmlns="http://www.w3.org/2000/svg"$frame viewBox="0 0 960 660"
role="img" aria-labelledby="load-chart-title load-chart-description"
data-bins="${points.size}" data-rollup-seconds="$rollup" data-truncated="$limited"$inlineData>
<title id="load-chart-title">${text.title.xml()}</title>
<desc id="load-chart-description">${description.xml()}</desc>${if (inline) "" else STYLE}
<text class="heading" x="84" y="30">${text.heading.xml()}</text>
<text class="meta" x="930" y="30" text-anchor="end">${meta.xml()}</text>$panels
<text class="label" x="507" y="624" text-anchor="middle">${text.time.xml()}</text>$note
</svg>""".encodeToByteArray()
}

/** The number of minute bins in one drawn point: the inclusive span of the data over [INLINE_MAX_POINTS], rounded up. */
private fun groupSize(
    data: List<Bucket>,
    rollupMillis: Long,
): Int {
    val bins = (data.last().start - data.first().start) / rollupMillis + 1
    return ((bins + INLINE_MAX_POINTS - 1) / INLINE_MAX_POINTS).toInt()
}

/** Groups of [width] milliseconds counted from the first bin: requests and errors summed, the largest p95 kept. */
private fun group(
    data: List<Bucket>,
    width: Long,
): List<Bucket> {
    val first = data.first().start
    val groups = LinkedHashMap<Long, Bucket>()
    data.forEach { bucket ->
        val index = (bucket.start - first) / width
        val seen = groups[index]
        groups[index] =
            if (seen == null) {
                Bucket(first + index * width, bucket.samples, bucket.errors, bucket.p95)
            } else {
                Bucket(
                    seen.start,
                    Math.addExact(seen.samples, bucket.samples),
                    Math.addExact(seen.errors, bucket.errors),
                    maxOf(seen.p95, bucket.p95),
                )
            }
    }
    return groups.values.toList()
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
    return Bucket(start, samples, errors, minOf(histogram.getValueAtPercentile(95.0), max))
}

private fun panel(
    chart: Chart,
    index: Int,
    data: List<Bucket>,
    first: Long,
    span: Long,
    rollupMillis: Long,
    text: ChartText,
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
<text class="meta" x="930" y="${(top - 12).number()}" text-anchor="end">${text.max} ${maximum.number()} ${chart.unit.xml()}</text>
<line class="axis" x1="84" y1="${top.number()}" x2="84" y2="${bottom.number()}"/>
<line class="axis" x1="84" y1="${bottom.number()}" x2="930" y2="${bottom.number()}"/>$lines<text class="meta" x="84" y="${(bottom + 18).number()}">0${text.seconds}</text>
<text class="meta" x="930" y="${(bottom + 18).number()}" text-anchor="end">${(span / 1_000.0).number()}${text.seconds}</text>
</g>"""
}

internal fun Reader.boundedLine(): String? {
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
private const val MAX_HISTOGRAM_CHARS = 393_216
private const val MAX_TIMESTAMP = 253_402_300_799_999L
private const val MAX_LATENCY = 86_400_000L
private const val INVALID_BUCKETS = "SAVED_BUCKETS_INVALID"
private const val INLINE_MAX_BUCKETS = 10_080
private const val INLINE_MAX_POINTS = 240
private const val INLINE_MAX_BYTES = 268_435_456L
private const val MINUTE_MILLIS = 60_000L
private const val INLINE_SCOPE = "svg.load-chart "
private const val INLINE_FRAME = "svg.load-chart{display:block;max-width:100%;height:auto}"

private fun chartRules(scope: String): String =
    listOf(
        "text" to "font-family:system-ui,sans-serif;fill:#182033",
        ".heading" to "font-size:22px;font-weight:700",
        ".label" to "font-size:15px;font-weight:600",
        ".meta" to "font-size:12px;fill:#556070",
        ".warning" to "font-size:12px;fill:#8a4b00",
        ".axis" to "stroke:#8a94a6",
        ".series-rps" to "stroke:#1769aa",
        ".series-errors" to "stroke:#ba2d0b",
        ".series-p95" to "stroke:#6f42c1",
        ".series" to "fill:none;stroke-width:2",
        ".point" to "stroke:none",
        ".point.series-rps" to "fill:#1769aa",
        ".point.series-errors" to "fill:#ba2d0b",
        ".point.series-p95" to "fill:#6f42c1",
    ).joinToString("") { (selector, body) -> "$scope$selector{$body}" }

private val STYLE = "<style>${chartRules("")}</style>"

/** The words of a chart: the file chart.svg keeps its English, the report speaks Russian. */
private class ChartText(
    val title: String,
    val description: String,
    val heading: String,
    val time: String,
    val rps: String,
    val rpsUnit: String,
    val errors: String,
    val errorsUnit: String,
    val p95: String,
    val p95Unit: String,
    val p95Group: String,
    val max: String,
    val seconds: String,
    val rollup: (Int) -> String,
    val rollupGroup: (Int) -> String,
    val grouped: (Int) -> String,
    val cut: (Int) -> String,
)

private val ENGLISH =
    ChartText(
        title = "LT Verdict load chart",
        description = "Relative time on a common timeline; gaps are not interpolated.",
        heading = "Load test metrics",
        time = "Time from run start",
        rps = "Requests per second",
        rpsUnit = "RPS",
        errors = "Errors per bin",
        errorsUnit = "count/bin",
        p95 = "P95 latency",
        p95Unit = "ms",
        p95Group = "",
        max = "max",
        seconds = " s",
        rollup = { "Rollup: ${it}s" },
        rollupGroup = { "" },
        grouped = { "" },
        cut = { "" },
    )

private val RUSSIAN =
    ChartText(
        title = "График нагрузки LT Verdict",
        description = "Время от начала прогона на общей шкале; пропуски не интерполируются.",
        heading = "Метрики нагрузки",
        time = "Время от начала прогона",
        rps = "Запросов в секунду",
        rpsUnit = "запр./с",
        errors = "Ошибок на точку",
        errorsUnit = "шт.",
        p95 = "p95 отклика",
        p95Unit = "мс",
        p95Group = "максимум p95 минуты в группе",
        max = "макс.",
        seconds = " с",
        rollup = { "Шаг: $it с" },
        rollupGroup = { "Шаг: $it мин" },
        grouped = { "Точка = $it мин: запросы и ошибки суммируются, разрыв короче точки не виден." },
        cut = { "Показаны первые $it минутных бинов." },
    )

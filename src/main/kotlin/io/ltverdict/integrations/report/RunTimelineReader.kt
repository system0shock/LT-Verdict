package io.ltverdict.integrations.report

import io.ltverdict.report.RunTimeline
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

// W2.6 PR 2: the facts the HTML report shows about a run, read from files of the analysis directory that the analysis already wrote
// (nothing is stored for them and nothing of them is part of identity or result). The files are untrusted data: opened without following
// links, bounded, and every part that cannot be read or does not hold together is null, never an error of the report.

private const val MAX_RUN_FILE_BYTES = 65_536L
private const val MAX_SECOND_ROWS = 100_000
private const val MAX_SECOND_CHARACTERS = 268_435_456L
private const val SECOND_MILLIS = 1_000L

/** The run block of an analysis directory, or null when none of its parts is available. */
internal fun readRunTimeline(analysisDirectory: Path): RunTimeline? {
    val times = readRunTimes(analysisDirectory.resolve("run.json"))
    val peak = readPeakSecond(analysisDirectory.resolve("normalized-1s.ndjson"))
    val chart = renderInlineLoadChart(analysisDirectory.resolve("rollup-60s.ndjson"))
    if (times == null && peak == null && chart == null) return null
    val peakAt = if (times != null && peak != null) runCatching { times.first.plusMillis(peak.first) }.getOrNull() else null
    return RunTimeline(times?.first, times?.second, peak?.second, peakAt, chart?.svg, chart?.css)
}

/** `started_at` and `ended_at` of run.json when both are instants and the end is not before the start. */
private fun readRunTimes(file: Path): Pair<Instant, Instant>? =
    try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_RUN_FILE_BYTES) {
            null
        } else {
            val run =
                Json.parseToJsonElement(
                    String(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { it.readAllBytes() }, UTF_8),
                )
            val started = Instant.parse(run.jsonObject.text("started_at"))
            val ended = Instant.parse(run.jsonObject.text("ended_at"))
            if (ended.isBefore(started)) null else started to ended
        }
    } catch (_: Exception) {
        null
    }

/**
 * The busiest one-second bucket of normalized-1s.ndjson as (offset from the run start in ms, samples): the earliest one on a tie. The
 * rows must be what the analysis writes (a whole second, strictly increasing, a non-negative integer count), otherwise null; the file is
 * read as a stream and its histograms are not decoded.
 */
private fun readPeakSecond(file: Path): Pair<Long, Long>? =
    try {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            null
        } else {
            var peak: Pair<Long, Long>? = null
            var previous = -1L
            var rows = 0
            var characters = 0L
            var valid = true
            Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).bufferedReader(UTF_8).use { reader ->
                while (valid) {
                    val line = reader.boundedLine() ?: break
                    characters += line.length
                    rows++
                    val row = Json.parseToJsonElement(line).jsonObject
                    val offset = row.integer("bucket_start_ms")
                    val count = row.integer("sample_count")
                    if (rows > MAX_SECOND_ROWS ||
                        characters > MAX_SECOND_CHARACTERS ||
                        offset == null ||
                        count == null ||
                        offset <= previous ||
                        offset % SECOND_MILLIS != 0L ||
                        count < 0
                    ) {
                        valid = false
                    } else {
                        previous = offset
                        if (peak.let { it == null || count > it.second }) peak = offset to count
                    }
                }
            }
            if (valid) peak else null
        }
    } catch (_: Exception) {
        null
    }

private fun JsonObject.text(name: String): String = (getValue(name) as JsonPrimitive).also { check(it.isString) }.content

private fun JsonObject.integer(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

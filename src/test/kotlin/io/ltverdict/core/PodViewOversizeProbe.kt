package io.ltverdict.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration

/**
 * Runs in a child JVM with a small heap (see PodViewTest): validates a pod view that is within 12 MiB but holds millions of
 * values, streamed so that the probe itself keeps no copy of the file. Prints "<code> <pointer>" or "OOM".
 */
internal object PodViewOversizeProbe {
    private const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private const val THOUSAND_CELLS = "0,0,0,0,0,0,0,0,0,0,"

    @JvmStatic
    fun main(args: Array<String>) {
        val parts =
            when (args.single()) {
                // One row of 6.289 million values, column_count equal to the length.
                "one-wide-row" -> sequenceOf(header(1, 6_289_001), rowStart(0)) + cells(6_289) + sequenceOf("0]}]}")
                // 2 560 rows of 2 000 values each: every array is short, the total is not.
                "many-rows" ->
                    sequenceOf(header(2_560, 240)) +
                        (0 until 2_560).asSequence().map { rowStart(it) + "0,".repeat(1_999) + "0]}" + (if (it < 2_559) "," else "") } +
                        sequenceOf("]}")
                else -> error("unknown shape ${args.single()}")
            }
        val source = streamOf(parts.iterator())
        try {
            val result = validatePodView(source)
            val error = (result as PodViewValidation.Invalid).errors.single()
            println("${error.code} ${error.jsonPointer}")
        } catch (_: OutOfMemoryError) {
            println("OOM")
        }
    }

    private fun cells(thousands: Int): Sequence<String> = (0 until thousands).asSequence().map { THOUSAND_CELLS.repeat(100) }

    private fun streamOf(parts: Iterator<String>): InputStream =
        SequenceInputStream(
            object : Enumeration<InputStream> {
                override fun hasMoreElements(): Boolean = parts.hasNext()

                override fun nextElement(): InputStream = ByteArrayInputStream(parts.next().encodeToByteArray())
            },
        )

    private fun header(
        rows: Int,
        columns: Int,
    ): String =
        "{\"schema_version\":\"pod-view.v1\",\"load_input_sha256\":\"$HASH\",\"resource_snapshot_sha256\":\"$HASH\"," +
            "\"start_epoch_ms\":1767225600000,\"step_ms\":60000,\"column_count\":$columns," +
            "\"coverage\":{\"pods_observed_total\":1,\"pods_included\":1,\"rows_observed_total\":$rows,\"rows_included\":$rows," +
            "\"selection\":{\"kind\":\"ALL\"}},\"pods\":[{\"pod\":\"pod-0\",\"service\":\"svc\"," +
            "\"containers\":[{\"name\":\"c0\",\"role\":\"app\"}]}],\"rows\":["

    private fun rowStart(index: Int): String =
        "{\"id\":\"r$index\",\"pod\":\"pod-0\",\"container\":\"c0\",\"metric\":\"m$index\",\"unit\":\"ratio\"," +
            "\"aggregation\":\"interval_mean\",\"values\":["
}

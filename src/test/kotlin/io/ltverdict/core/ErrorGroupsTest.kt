package io.ltverdict.core

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.SampleKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ErrorGroupsTest {
    @Test
    fun `no failed sampler gives no artifact`() {
        val accumulator = ErrorGroupAccumulator("run")
        accumulator.record(sample("a", successful = true))
        accumulator.record(sample("c", successful = false, kind = SampleKind.JMETER_CONTAINER, code = "500", message = "parent"))

        assertNull(accumulator.finish())
    }

    @Test
    fun `groups by transaction code and message with counts order and times`() {
        val accumulator = ErrorGroupAccumulator("run-1")
        repeat(3) { accumulator.record(sample("login", start = 1_000L + it * 10, code = "503", message = "Service Unavailable")) }
        accumulator.record(sample("login", start = 2_000, code = "500", message = "boom"))
        repeat(3) { accumulator.record(sample("search", start = 3_000L + it, code = "200", message = "Test failed")) }
        accumulator.record(sample("search", start = 4_000, code = null, message = null, path = listOf("flow")))
        accumulator.record(sample("ok", successful = true))
        accumulator.record(sample("child", successful = false, kind = SampleKind.JMETER_CONTAINER, code = "500", message = "x"))

        val result = parse(accumulator.finish())
        val groups = result.getValue("groups").jsonArray.map { it.jsonObject }

        assertEquals("error-groups.v1", result.text("schema_version"))
        assertEquals("run-1", result.text("run_id"))
        assertEquals("whole_run", result.text("scope_note"))
        assertEquals("8", result.text("total_error_count"))
        assertEquals("8", result.text("tracked_error_count"))
        assertEquals("0", result.text("untracked_error_count"))
        assertEquals("0", result.text("omitted_group_count"))
        assertEquals(
            listOf("login|503|Service Unavailable|3", "search|200|Test failed|3", "login|500|boom|1", "search|null|null|1"),
            groups.map {
                "${it.getValue(
                    "scope",
                ).jsonObject.text("label")}|${it.text("response_code")}|${it.text("message")}|${it.text("count")}"
            },
        )
        val first = groups.first()
        assertEquals("1000", first.text("from_epoch_ms"))
        assertEquals("1021", first.text("to_epoch_ms"))
        assertEquals("transaction", first.getValue("scope").jsonObject.text("kind"))
        assertEquals("JMETER_SAMPLER", first.getValue("scope").jsonObject.text("sample_kind"))
        assertEquals(JsonArray(emptyList()), first.getValue("scope").jsonObject.getValue("group_path"))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("flow"))),
            groups
                .last()
                .getValue("scope")
                .jsonObject
                .getValue("group_path"),
        )
        assertTrue(first.text("id")!!.matches(Regex("errgrp-[0-9a-f]{64}")))
        assertEquals(groups.size, groups.map { it.text("id") }.toSet().size)
        assertEquals(JsonNull, groups.last().getValue("response_code"))
    }

    @Test
    fun `same input in the same order gives the same bytes`() {
        fun build(): ByteArray? {
            val accumulator = ErrorGroupAccumulator("run")
            (1..50).forEach { accumulator.record(sample("t${it % 7}", start = it.toLong(), code = "5${it % 3}0", message = "m${it % 5}")) }
            return accumulator.finish()
        }

        assertArrayEquals(build(), build())
    }

    @Test
    fun `text is cleaned and bounded`() {
        val accumulator = ErrorGroupAccumulator("run")
        val dirty = "a\u0000b\r\nc\td‮e​f\uD800g   h"
        accumulator.record(sample("t", code = "x".repeat(100), message = dirty))
        accumulator.record(sample("t", code = " 200 ", message = "m".repeat(10_000)))
        accumulator.record(sample("t", code = "\u0001", message = "   "))

        val groups = parse(accumulator.finish()).getValue("groups").jsonArray.map { it.jsonObject }
        val byCode = groups.associateBy { it.text("response_code") }
        val cut = byCode.getValue("x".repeat(64) + "…")

        assertEquals("a b c d e f g h", cut.text("message"))
        assertFalse(
            cut
                .getValue("message_truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        val long = byCode.getValue("200")
        assertEquals("m".repeat(200) + "…", long.text("message"))
        assertTrue(
            long
                .getValue("message_truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        val empty = groups.single { it.getValue("response_code") == JsonNull }
        assertEquals(JsonNull, empty.getValue("message"))
        assertEquals(3, groups.size)
    }

    @Test
    fun `cleaning keeps valid surrogate pairs and is idempotent`() {
        val (once, truncated) = cleanErrorText("ok 😀 " + "z".repeat(300), 20)
        assertEquals("ok 😀 " + "z".repeat(15) + "…", once)
        assertTrue(truncated)
        assertEquals(once to false, cleanErrorText(checkNotNull(once), 21))
    }

    @Test
    fun `more distinct keys than the tracking limit go to the untracked count and only the top 20 are listed`() {
        val accumulator = ErrorGroupAccumulator("run")
        repeat(3000) { accumulator.record(sample("t", start = it.toLong(), code = "500", message = "message $it")) }
        repeat(5) { accumulator.record(sample("t", start = 9_000L + it, code = "500", message = "message 7")) }

        val result = parse(accumulator.finish())

        assertEquals("3005", result.text("total_error_count"))
        assertEquals("2053", result.text("tracked_error_count"))
        assertEquals("952", result.text("untracked_error_count"))
        assertEquals(20, result.getValue("groups").jsonArray.size)
        assertEquals("2028", result.text("omitted_group_count"))
        val shown = result.getValue("groups").jsonArray.sumOf { it.jsonObject.text("count")!!.toLong() }
        assertEquals(3005L, shown + result.text("omitted_error_count")!!.toLong() + result.text("untracked_error_count")!!.toLong())
        assertEquals(
            "6",
            result
                .getValue("groups")
                .jsonArray
                .first()
                .jsonObject
                .text("count"),
        )
    }

    @Test
    fun `the same cleaned text cut and uncut is one group and the flag is any`() {
        val accumulator = ErrorGroupAccumulator("run")
        accumulator.record(sample("t", message = "a".repeat(199) + " b"))
        accumulator.record(sample("t", message = "a".repeat(199) + "…"))

        val groups = parse(accumulator.finish()).getValue("groups").jsonArray.map { it.jsonObject }

        assertEquals(1, groups.size)
        assertEquals("2", groups.single().text("count"))
        assertTrue(
            groups
                .single()
                .getValue("message_truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
    }

    @Test
    fun `equal counts are ordered by label before group path`() {
        val accumulator = ErrorGroupAccumulator("run")
        accumulator.record(sample("z", path = listOf("a")))
        accumulator.record(sample("a", path = listOf("z")))

        val labels =
            parse(
                accumulator.finish(),
            ).getValue("groups").jsonArray.map {
                it.jsonObject
                    .getValue("scope")
                    .jsonObject
                    .text("label")
            }

        assertEquals(listOf("a", "z"), labels)
    }

    @Test
    fun `the file stays below the size the reader accepts and the unlisted groups are counted as omitted`() {
        val accumulator = ErrorGroupAccumulator("run")
        val path = List(14) { "p".repeat(4_096 - 1) + it.toString(16) }
        repeat(20) { accumulator.record(sample("t$it", start = it.toLong(), path = path)) }

        val bytes = checkNotNull(accumulator.finish())
        val result = parse(bytes)

        assertTrue(bytes.size < 1_048_576, bytes.size.toString())
        assertTrue(result.getValue("groups").jsonArray.size < 20)
        val listed = result.getValue("groups").jsonArray.sumOf { it.jsonObject.text("count")!!.toLong() }
        assertEquals(20L, listed + result.text("omitted_error_count")!!.toLong() + result.text("untracked_error_count")!!.toLong())
        assertEquals(20L - result.getValue("groups").jsonArray.size, result.text("omitted_group_count")!!.toLong())
    }

    @Test
    fun `the byte budget of tracked keys also stops tracking`() {
        val accumulator = ErrorGroupAccumulator("run", trackedBytesMax = 1_000)
        repeat(10) { accumulator.record(sample("t", start = it.toLong(), code = "500", message = "m".repeat(150) + it)) }

        val result = parse(accumulator.finish())

        assertEquals("10", result.text("total_error_count"))
        assertTrue(result.text("untracked_error_count")!!.toLong() > 0)
        assertEquals(10L, result.text("tracked_error_count")!!.toLong() + result.text("untracked_error_count")!!.toLong())
    }

    private fun sample(
        label: String,
        start: Long = 1_000,
        successful: Boolean = false,
        kind: SampleKind = SampleKind.JMETER_SAMPLER,
        code: String? = "500",
        message: String? = "m",
        path: List<String> = emptyList(),
    ) = LoadSample(
        start,
        1,
        label,
        path,
        kind,
        successful,
        responseCode = if (successful) null else code,
        failureMessage = if (successful) null else message,
    )

    private fun parse(bytes: ByteArray?): JsonObject = Json.parseToJsonElement(checkNotNull(bytes).decodeToString()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}

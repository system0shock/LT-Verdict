package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LocalReleaseTest {
    private val runId = "jmeter_jtl_csv-${"c".repeat(64)}"

    private fun analysis(
        id: Char = 'a',
        arm: String? = null,
        verdict: String = "PASS",
        reasons: List<String> = emptyList(),
    ) = buildJsonObject {
        put("analysis_id", id.toString().repeat(64))
        put("arm", arm?.let(::JsonPrimitive) ?: JsonNull)
        put("coverage_reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("coverage_status", if (reasons.isEmpty()) "COMPLETE" else "INCOMPLETE")
        put("policy_sha256", "b".repeat(64))
        put("policy_verdict", verdict)
        put("run_validity", "VALID")
    }

    private fun release(
        analyses: List<JsonObject> = listOf(analysis()),
        profile: JsonElement = JsonNull,
        notes: JsonElement = JsonNull,
        label: String = "1.2.3",
        series: String = "checkout",
    ) = buildJsonObject {
        put("schema_version", "local-release.v1")
        put("release_id", "001767225600000-0123abcd")
        put("series", series)
        put("label", label)
        put("run_id", runId)
        put("started_at", "2026-01-01T00:00:00Z")
        put("analyses", JsonArray(analyses))
        put("profile", profile)
        put("notes", notes)
        put("created_at", "2026-01-02T03:04:05.006Z")
        put("updated_at", "2026-01-02T03:04:05.006Z")
    }

    private fun profile(vararg fields: Pair<String, String?>) =
        buildJsonObject {
            RELEASE_PROFILE_FIELDS.forEach { name ->
                val value = fields.toMap()[name]
                put(name, value?.let(::JsonPrimitive) ?: JsonNull)
            }
        }

    private fun JsonObject.with(
        name: String,
        value: JsonElement,
    ) = JsonObject(this + (name to value))

    private fun assertInvalid(record: JsonObject) {
        val failure = assertThrows(IllegalArgumentException::class.java) { validateRelease(record) }
        assertEquals("INVALID_RELEASE", failure.message)
    }

    @Test
    fun `canonical record bytes are pinned`() {
        val expected =
            """{"analyses":[{"analysis_id":"${"a".repeat(64)}","arm":null,"coverage_reasons":[],"coverage_status":"COMPLETE",""" +
                """"policy_sha256":"${"b".repeat(
                    64,
                )}","policy_verdict":"PASS","run_validity":"VALID"}],"created_at":"2026-01-02T03:04:05.006Z",""" +
                """"label":"1.2.3","notes":null,"profile":null,"release_id":"001767225600000-0123abcd","run_id":"$runId",""" +
                """"schema_version":"local-release.v1","series":"checkout","started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T03:04:05.006Z"}"""
        assertArrayEquals(expected.encodeToByteArray(), canonicalJson(validateRelease(release())))
    }

    @Test
    fun `release id carries the test start and orders chronologically`() {
        assertEquals("000000000000000-00000000", releaseId(0, "00000000"))
        assertEquals("001767225600000-0123abcd", releaseId(releaseStartedAtMillis("2026-01-01T00:00:00Z"), "0123abcd"))
        assertEquals("253402300799999-ffffffff", releaseId(MAX_TIMESTAMP_EPOCH_MILLIS, "ffffffff"))
        assertEquals(1_767_225_600_500L, releaseStartedAtMillis("2026-01-01T00:00:00.500Z"))
        assertTrue(releaseId(1_767_225_600_000L, "00000000") != releaseId(releaseStartedAtMillis("2026-01-01T00:00:00.500Z"), "00000000"))
        assertEquals(15, releaseId(0, "00000000").substringBefore('-').length)
        assertEquals(15, releaseId(MAX_TIMESTAMP_EPOCH_MILLIS, "00000000").substringBefore('-').length)
        listOf(
            "2026-01-01T00:00:00",
            "garbage",
            "1969-12-31T23:59:59Z",
            "+99999-01-01T00:00:00Z",
            "+999999999-01-01T00:00:00Z",
            "2026-01-01T00:00:00+01:00",
            "",
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) { releaseStartedAtMillis(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { releaseId(0, "XYZ") }
        assertThrows(IllegalArgumentException::class.java) { releaseId(-1, "00000000") }
    }

    @Test
    fun `validator rejects every structural defect`() {
        val base = release()
        val twoArms = listOf(analysis('a', arm = "blue"), analysis('b', arm = "green"))
        listOf(
            base.with("extra_field", JsonPrimitive("x")),
            JsonObject(base - "notes"),
            base.with("schema_version", JsonPrimitive("local-release.v2")),
            base.with("release_id", JsonPrimitive("1767225600000-0123abcd")),
            base.with("release_id", JsonPrimitive("001767225600001-0123abcd")),
            base.with("release_id", JsonPrimitive("001767225600000-0123ABCD")),
            base.with("run_id", JsonPrimitive("unknown-${"c".repeat(64)}")),
            base.with("started_at", JsonPrimitive("2026-01-01")),
            base.with("analyses", JsonArray(emptyList())),
            base.with("analyses", JsonArray((0..MAX_RELEASE_ANALYSES).map { analysis("0123456789abcdef"[it], arm = "arm$it") })),
            base.with("analyses", JsonArray(listOf(analysis('a'), analysis('a', arm = "x")))),
            base.with("analyses", JsonArray(listOf(analysis('a'), analysis('b')))),
            base.with("analyses", JsonArray(listOf(analysis('a'), analysis('b', arm = "x")))),
            base.with("analyses", JsonArray(listOf(analysis('a', arm = "x"), analysis('b', arm = "x")))),
            base.with("analyses", JsonArray(listOf(analysis().with("policy_verdict", JsonPrimitive("MAYBE"))))),
            base.with("analyses", JsonArray(listOf(analysis().with("coverage_status", JsonPrimitive("PARTIAL"))))),
            base.with("analyses", JsonArray(listOf(analysis().with("run_validity", JsonPrimitive("OK"))))),
            base.with("analyses", JsonArray(listOf(analysis().with("policy_sha256", JsonPrimitive("abc"))))),
            base.with("analyses", JsonArray(listOf(analysis().with("policy_sha256", JsonPrimitive("A".repeat(64)))))),
            base.with("analyses", JsonArray(listOf(analysis(reasons = (1..17).map { "R$it" })))),
            base.with("analyses", JsonArray(listOf(analysis().with("extra_field", JsonNull)))),
            base.with("updated_at", JsonPrimitive("2026-01-02T03:04:05.005Z")),
            base.with("profile", JsonObject(emptyMap())),
            base.with("profile", profile("scenario_mix" to "a").with("extra_field", JsonNull)),
            base.with("profile", profile()),
            base.with("profile", profile("scenario_mix" to "")),
            base.with("notes", JsonPrimitive("")),
            base.with("label", JsonPrimitive("")),
            base.with("series", JsonNull),
        ).forEach(::assertInvalid)
        assertInvalid(base.with("analyses", JsonArray(twoArms)).with("analyses", JsonArray(twoArms + analysis('c', arm = "blue"))))
    }

    @Test
    fun `validator accepts the documented boundaries`() {
        val long = "я".repeat(MAX_RELEASE_TEXT_BYTES / 2)
        assertEquals(MAX_RELEASE_TEXT_BYTES, long.encodeToByteArray().size)
        validateRelease(release(label = long, series = long, profile = profile("scenario_mix" to long, "pacing" to long)))
        val notes = "п\n".repeat(MAX_RELEASE_NOTES_BYTES / 3) + "a"
        assertEquals(MAX_RELEASE_NOTES_BYTES, notes.encodeToByteArray().size)
        validateRelease(release(notes = JsonPrimitive(notes)))
        val arms = (0 until MAX_RELEASE_ANALYSES).map { analysis("0123456789abcdef"[it], arm = "arm$it") }
        validateRelease(release(analyses = arms))
        validateRelease(release(analyses = listOf(analysis(reasons = (1..16).map { "R$it" }))))
        validateRelease(release(profile = profile("generator_limits" to "x")))
    }

    @Test
    fun `validator rejects text that is not in its normalized form`() {
        assertInvalid(release(label = "1.2.3 "))
        assertInvalid(release(label = "ё"))
        assertInvalid(release(label = "a\u0007b"))
        assertInvalid(release(label = "a\nb"))
        assertInvalid(release(notes = JsonPrimitive("a\r\nb")))
        assertInvalid(release(notes = JsonPrimitive("a\u0007b")))
        assertInvalid(release(series = "x".repeat(MAX_RELEASE_TEXT_BYTES + 1)))
        assertInvalid(release(label = "я".repeat(MAX_RELEASE_TEXT_BYTES / 2) + "я"))
        assertInvalid(release(notes = JsonPrimitive("x".repeat(MAX_RELEASE_NOTES_BYTES + 1))))
        assertInvalid(release(profile = profile("pacing" to "p\u0000")))
    }

    @Test
    fun `normalization makes composed and decomposed profile values equal`() {
        assertEquals("é", normalizeReleaseText("é"))
        assertEquals("a\nb", normalizeReleaseText("  a\r\nb  "))
        assertEquals("plain ASCII 123", normalizeReleaseText("plain ASCII 123"))
        val composed = profile("scenario_mix" to normalizeReleaseText("é"))
        val decomposed = profile("scenario_mix" to normalizeReleaseText("é"))
        assertEquals("MATCH", (compareReleaseProfiles(composed, decomposed)!!["status"] as JsonPrimitive).content)
    }

    @Test
    fun `profile comparison`() {
        val left = profile("scenario_mix" to "a", "pacing" to "p1", "load_model" to "open")
        assertNull(compareReleaseProfiles(null, left))
        assertNull(compareReleaseProfiles(left, null))
        assertNull(compareReleaseProfiles(null, null))
        assertEquals(
            buildJsonObject {
                put("status", "MATCH")
                put("differing_fields", buildJsonArray {})
            },
            compareReleaseProfiles(left, left),
        )
        val right = profile("scenario_mix" to "b", "pacing" to "p2", "load_model" to "open")
        assertEquals(
            buildJsonObject {
                put("status", "MISMATCH")
                put("differing_fields", buildJsonArray { listOf("scenario_mix", "pacing").forEach { add(JsonPrimitive(it)) } })
            },
            compareReleaseProfiles(left, right),
        )
        // a null field differs from a filled one
        assertEquals(
            listOf("pacing"),
            (compareReleaseProfiles(left, profile("scenario_mix" to "a", "load_model" to "open"))!!["differing_fields"] as JsonArray)
                .map { (it as JsonPrimitive).content },
        )
        assertEquals("scenario_mix=a; load_model=open; pacing=p1", releaseProfileSummary(left))
        assertNull(releaseProfileSummary(null))
    }

    @Test
    fun `facts are copied from the result and identity documents`() {
        val result =
            buildJsonObject {
                put("run_validity", "VALID")
                put("policy_verdict", "PASS")
                put(
                    "analysis_coverage",
                    buildJsonObject {
                        put("status", "INCOMPLETE")
                        put("reasons", buildJsonArray { add(JsonPrimitive("SMALL_SAMPLE")) })
                    },
                )
            }
        val identity =
            buildJsonObject {
                put("policy_sha256", "d".repeat(64))
                put("resource_arm", "blue")
            }
        val facts = releaseAnalysisFacts("e".repeat(64), result, identity)
        assertEquals(
            buildJsonObject {
                put("analysis_id", "e".repeat(64))
                put("arm", "blue")
                put("coverage_reasons", buildJsonArray { add(JsonPrimitive("SMALL_SAMPLE")) })
                put("coverage_status", "INCOMPLETE")
                put("policy_sha256", "d".repeat(64))
                put("policy_verdict", "PASS")
                put("run_validity", "VALID")
            },
            facts,
        )
        assertEquals(JsonNull, releaseAnalysisFacts("e".repeat(64), result, JsonObject(identity - "resource_arm"))["arm"])
        listOf(
            result - "policy_verdict",
            result - "analysis_coverage",
            result - "run_validity",
            result.with("analysis_coverage", buildJsonObject { put("status", "COMPLETE") }),
            result.with("analysis_coverage", buildJsonObject { put("reasons", buildJsonArray {}) }),
        ).forEach { broken ->
            assertThrows(IllegalArgumentException::class.java) { releaseAnalysisFacts("e".repeat(64), JsonObject(broken), identity) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            releaseAnalysisFacts("e".repeat(64), result, identity.with("resource_arm", JsonPrimitive(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            releaseAnalysisFacts("e".repeat(64), result, JsonObject(identity - "policy_sha256"))
        }
    }

    @Test
    fun `the contract examples agree with the validator`() {
        val directory = Path.of("docs/contracts/release/v1/examples")
        Files.list(directory.resolve("valid")).use { stream ->
            val files = stream.toList()
            assertTrue(files.isNotEmpty())
            files.forEach { validateRelease(Json.parseToJsonElement(Files.readString(it)).jsonObject) }
        }
        Files.list(directory.resolve("invalid")).use { stream ->
            val files = stream.toList()
            assertTrue(files.isNotEmpty())
            files.forEach {
                val document = Json.parseToJsonElement(Files.readString(it)).jsonObject
                assertThrows(IllegalArgumentException::class.java, { validateRelease(document) }, it.fileName.toString())
            }
        }
    }
}

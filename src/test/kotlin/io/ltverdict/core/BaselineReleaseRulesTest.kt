package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The baseline and release rules on their own: no storage, no HTTP. The HTTP answers are fixed by the route snapshots. */
class BaselineReleaseRulesTest {
    private val run = "jmeter_jtl_csv-${"1".repeat(64)}"
    private val analysis = "a".repeat(64)

    private fun failure(block: () -> Unit): RuleFailure = assertThrows(RuleFailure::class.java) { block() }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun reference(
        runId: String = run,
        analysisId: String = analysis,
    ) = """{"run_id":"$runId","analysis_id":"$analysisId"}"""

    @Test
    fun `a selection plan is checked in the order of the request and raises the three kinds`() {
        val missingMode = failure { planBaselineSelection(obj("""{"series":"s"}""")) }
        assertEquals(RuleFailureKind.MALFORMED, missingMode.kind)
        assertEquals("MALFORMED_REQUEST", missingMode.code)
        assertEquals("mode must be a string", missingMode.message)

        val few = failure { planBaselineSelection(obj("""{"mode":"statistical","series":"s","candidates":[],"comparable":true}""")) }
        assertEquals(RuleFailureKind.UNPROCESSABLE, few.kind)
        assertEquals("BASELINE_CANDIDATE_COUNT", few.code)

        val unconfirmed =
            failure { planBaselineSelection(obj("""{"mode":"statistical","series":"s","candidates":7,"comparable":false}""")) }
        assertEquals("BASELINE_COMPARABILITY_UNCONFIRMED", unconfirmed.code)

        val same = reference()
        val duplicate =
            failure {
                planBaselineSelection(
                    obj("""{"mode":"statistical","series":"s","candidates":[$same,$same,$same],"comparable":true}"""),
                )
            }
        assertEquals("BASELINE_DUPLICATE_RUN", duplicate.code)

        val manual = planBaselineSelection(obj("""{"mode":"manual","series":"  s  ","reference":${reference()}}"""))
        assertTrue(manual.manual)
        assertEquals("s", manual.series)
        assertEquals(1, manual.references.size)
    }

    @Test
    fun `a candidate that is not PASS is refused with the known verdict in the message`() {
        val plan = planBaselineSelection(obj("""{"mode":"manual","series":"s","reference":${reference()}}"""))

        fun result(verdict: String?) =
            buildJsonObject {
                put("run_validity", "VALID")
                if (verdict != null) put("policy_verdict", verdict)
                put("analysis_coverage", buildJsonObject { put("status", "COMPLETE") })
            }
        val fail = failure { selectBaseline(plan, listOf(result("FAIL")), listOf(buildJsonObject { })) }
        assertEquals("BASELINE_CANDIDATE_NOT_PASS", fail.code)
        assertEquals("Baseline candidate is unavailable: BASELINE_CANDIDATE_NOT_PASS (policy_verdict=FAIL)", fail.message)
        val odd = failure { selectBaseline(plan, listOf(result("SURPRISE")), listOf(buildJsonObject { })) }
        assertTrue(odd.message.endsWith("(policy_verdict=UNKNOWN)"))
    }

    @Test
    fun `the slot of an analysis follows the release series and refuses a contradicting query`() {
        val identity = buildJsonObject { put("resource_arm", "blue") }
        assertEquals("blue", baselineScope(null, "rel", identity).arm)
        assertEquals("rel", baselineScope("rel", "rel", identity).series)
        assertEquals("q", baselineScope("q", null, identity).series)
        assertNull(baselineScope(null, null, buildJsonObject { }).series)
        assertEquals("BASELINE_SERIES_CONFLICT", failure { baselineScope("q", "rel", identity) }.code)
        val corrupt = failure { baselineScope(null, null, buildJsonObject { put("resource_arm", 5) }) }
        assertEquals(RuleFailureKind.CORRUPT, corrupt.kind)
        assertEquals("CORRUPT_BASELINE", corrupt.code)
        // the conflict is decided before the arm is read
        assertEquals("BASELINE_SERIES_CONFLICT", failure { baselineScope("q", "rel", buildJsonObject { put("resource_arm", 5) }) }.code)
    }

    @Test
    fun `a slot address needs a series for an arm and both are normalized`() {
        assertEquals(null to null, baselineSlotAddress(null, null))
        assertEquals("s" to "blue", baselineSlotAddress("  s ", " blue "))
        assertEquals("arm requires series", failure { baselineSlotAddress(null, "blue") }.message)
        assertEquals("arm is invalid", failure { baselineSlotAddress("s", "  ") }.message)
        assertEquals("series is invalid", failure { baselineSlotAddress("  ", null) }.message)
    }

    @Test
    fun `window comparison needs both windows and bounded thresholds`() {
        assertNull(windowComparisonRequest(null, null, null, null))
        assertEquals("Materiality thresholds require both windows", failure { windowComparisonRequest(null, null, "5", null) }.message)
        assertTrue(failure { windowComparisonRequest("a", null, null, null) }.message.startsWith("Both window IDs"))
        val request = windowComparisonRequest("a", "b", null, "0.5")!!
        assertEquals("5", request.minChangePercent.toPlainString())
        assertEquals("0.5", request.minErrorRateDelta.toPlainString())
        assertEquals("min_change_percent is invalid", failure { windowComparisonRequest("a", "b", "1001", null) }.message)
        assertEquals("min_error_rate_delta is invalid", failure { windowComparisonRequest("a", "b", null, "1.1") }.message)
        assertEquals("min_change_percent is invalid", failure { windowComparisonRequest("a", "b", "0", null) }.message)
    }

    @Test
    fun `a release request is normalized and checked field by field in a fixed order`() {
        fun body(
            label: String = "1.0",
            profile: String = "null",
        ) = obj(
            """{"series":" checkout ","label":"$label","run_id":"$run","analyses":[{"analysis_id":"$analysis"}],"profile":$profile,"notes":" a\r\nb "}""",
        )
        val request = parseReleaseCreate(body())
        assertEquals("checkout", request.series)
        assertEquals(JsonPrimitive("a\nb"), request.notes)
        assertEquals(JsonNull, request.profile)
        assertEquals("label is required", failure { parseReleaseCreate(body(label = "  ")) }.message)
        val allEmpty = RELEASE_PROFILE_FIELDS.joinToString(",", "{", "}") { "\"$it\":\"  \"" }
        assertEquals(JsonNull, parseReleaseCreate(body(profile = allEmpty)).profile)
        assertEquals("profile fields are invalid", failure { parseReleaseCreate(body(profile = """{"x":"y"}""")) }.message)
        // the label is checked before the run, the run before the profile
        val both = obj("""{"series":"s","label":"l","run_id":"x","analyses":1,"profile":2,"notes":null}""")
        assertEquals("run_id is invalid", failure { parseReleaseCreate(both) }.message)
        assertEquals("Release fields are invalid", failure { parseReleaseUpdate(body()) }.message)
        assertEquals("Release id is invalid", failure { requireReleaseId("zz") }.message)
        assertEquals("after is invalid", failure { releaseAfterParameter("zz") }.message)
    }

    private fun runDocument(
        runId: String,
        startedAt: String,
    ) = buildJsonObject {
        put("run_id", runId)
        put("started_at", startedAt)
    }

    private fun result(
        runId: String,
        verdict: String = "PASS",
    ) = buildJsonObject {
        put("run_id", runId)
        put("run_validity", "VALID")
        put("policy_verdict", verdict)
        put(
            "analysis_coverage",
            buildJsonObject {
                put("status", "COMPLETE")
                put("reasons", JsonArray(emptyList()))
            },
        )
    }

    private fun identity(
        arm: String?,
        tag: String,
    ) = buildJsonObject {
        put("tag", tag)
        put("policy_sha256", "a".repeat(64))
        if (arm != null) put("resource_arm", arm)
    }

    @Test
    fun `the facts of the analyses are checked one analysis at a time and the arms after all of them`() {
        val collector = ReleaseFactsCollector(run)
        collector.add("b".repeat(64), runDocument(run, "2026-01-01T00:00:00Z"), result(run), identity("blue", "b"))
        collector.add("a".repeat(64), runDocument(run, "2026-01-01T00:00:00Z"), result(run), identity("green", "a"))
        val (startedAt, facts) = collector.finish()
        assertEquals("2026-01-01T00:00:00Z", startedAt)
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), facts.map { it.getValue("analysis_id").jsonPrimitive.content })

        val noMetadata = failure { ReleaseFactsCollector(run).add(analysis, null, result(run), identity(null, "x")) }
        assertEquals("RELEASE_ANALYSIS_NO_RUN_METADATA", noMetadata.code)
        val foreign =
            failure {
                ReleaseFactsCollector(
                    run,
                ).add(analysis, runDocument(run, "2026-01-01T00:00:00Z"), result("other"), identity(null, "x"))
            }
        assertEquals("RELEASE_RUN_MISMATCH", foreign.code)
        val shifted = ReleaseFactsCollector(run)
        shifted.add("a".repeat(64), runDocument(run, "2026-01-01T00:00:00Z"), result(run), identity("blue", "a"))
        val differs = failure { shifted.add("b".repeat(64), runDocument(run, "2026-01-01T00:00:01Z"), result(run), identity("green", "b")) }
        assertEquals("RELEASE_STARTED_AT_MISMATCH", differs.code)

        val unmarked = ReleaseFactsCollector(run)
        unmarked.add("a".repeat(64), runDocument(run, "2026-01-01T00:00:00Z"), result(run), identity(null, "a"))
        unmarked.add("b".repeat(64), runDocument(run, "2026-01-01T00:00:00Z"), result(run), identity(null, "b"))
        assertEquals("RELEASE_ARM_CONFLICT", failure { unmarked.finish() }.code)
    }

    @Test
    fun `a stored release is shown with the state and the eligibility of each analysis`() {
        val record =
            buildJsonObject {
                put("run_id", run)
                put(
                    "analyses",
                    JsonArray(
                        listOf(analysis, "b".repeat(64)).map {
                            buildJsonObject {
                                put("analysis_id", it)
                                put("policy_verdict", "PASS")
                                put("run_validity", "VALID")
                                put("coverage_status", "COMPLETE")
                                put("coverage_reasons", JsonArray(emptyList()))
                            }
                        },
                    ),
                )
            }
        val states = mapOf((run to analysis) to "OK", (run to "b".repeat(64)) to "MISSING")
        val view = releaseView(record, states)
        assertEquals(JsonPrimitive(false), view["baseline_eligible"])
        assertEquals(listOf("ANALYSIS_MISSING"), view.getValue("ineligible_reasons").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), view.getValue("analyses").jsonArray[0].jsonObject["baseline_eligible"])
        assertEquals(releaseStateKeys(record).toSet(), releaseOkStates(record).keys)
    }

    @Test
    fun `a replacement keeps the start of the release and touches only the editable fields`() {
        val existing =
            buildJsonObject {
                put("started_at", "2026-01-01T00:00:00Z")
                put("series", "s")
                put("label", "old")
            }
        requireReleaseStart(existing, "2026-01-01T00:00:00Z")
        assertEquals(
            "Analyses start at a different time than the release",
            failure {
                requireReleaseStart(existing, "2026-01-01T00:00:01Z")
            }.message,
        )
        val request = parseReleaseUpdate(obj("""{"label":"new","analyses":[{"analysis_id":"$analysis"}],"profile":null,"notes":null}"""))
        val updated = releaseUpdated(existing, request, emptyList(), JsonPrimitive("t"))
        assertEquals(JsonPrimitive("new"), updated["label"])
        assertEquals(JsonPrimitive("s"), updated["series"])
        assertEquals(JsonPrimitive("t"), updated["updated_at"])
    }
}

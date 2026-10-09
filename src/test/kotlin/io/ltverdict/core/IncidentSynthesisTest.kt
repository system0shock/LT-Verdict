package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.name

// W3.7 PR 1 (ADR 0029): the pure function synthesizeIncidents. Nothing here goes through AnalysisService: the function is not wired in.
// Fixtures live in fixtures/incidents/<case>/{input,expected}.json. A `contract-<name>` case is compared with the hand-written example
// docs/contracts/incident/v1/examples/valid/<name>.json (independent of this code); every other expected.json is written by this test
// with LTV_UPDATE_INCIDENTS=1 (inputs of the generated cases too) and is checked by the independent Python verifier
// (tools/test_incident_fixtures.py). A case named `names-*` carries user-chosen names and is exempt from the rendered-wording check.

class IncidentSynthesisTest {
    // ---------------------------------------------------------------------------------------------------------- fixtures

    @Test
    fun `every fixture reproduces its expected document, twice and under permutation of the input arrays`() {
        val update = System.getenv("LTV_UPDATE_INCIDENTS") == "1"
        if (update) writeGeneratedInputs()
        val cases = fixtureCases()
        assertTrue(cases.size >= 14, "fixture cases: ${cases.map { it.name }}")
        for (case in cases) {
            val input = Json.parseToJsonElement(Files.readString(case.resolve("input.json"))).jsonObject
            val validity = RunValidity.valueOf(input.getValue("run_validity").jsonPrimitive.content)
            val findings = input.getValue("findings").jsonArray.map { it.jsonObject }
            val evidence = input.getValue("evidence").jsonArray.map { it.jsonObject }
            val actual = synthesizeIncidents(validity, findings, evidence)
            if (update && !case.name.startsWith("contract-")) {
                Files.writeString(case.resolve("expected.json"), PRETTY.encodeToString(JsonElement.serializer(), actual) + "\n")
            }
            val expected = Json.parseToJsonElement(Files.readString(expectedPath(case))).jsonObject
            assertEquals(String(canonicalJson(expected)), String(canonicalJson(actual)), "case ${case.name}")
            assertEquals(
                String(canonicalJson(actual)),
                String(canonicalJson(synthesizeIncidents(validity, findings, evidence))),
                "twice ${case.name}",
            )
            val random = Random(case.name.hashCode().toLong())
            repeat(3) {
                val shuffled = synthesizeIncidents(validity, findings.shuffled(random), evidence.shuffled(random))
                assertEquals(String(canonicalJson(actual)), String(canonicalJson(shuffled)), "permutation ${case.name}")
            }
        }
    }

    @Test
    fun `every valid contract example has a fixture that reproduces it`() {
        val examples =
            Files.list(Path.of("docs/contracts/incident/v1/examples/valid")).use { stream ->
                stream.map { it.name.removeSuffix(".json") }.toList().sorted()
            }
        val cases =
            fixtureCases()
                .map { it.name }
                .filter { it.startsWith("contract-") }
                .map { it.removePrefix("contract-") }
                .sorted()
        // overlapping-resource-incidents shows the window-wide checks (NO_ANOMALY_EPISODES, CHECKS_NOT_EVALUATED) on one incident each:
        // no single input gives that output under the window rules of ADR 0029, so it stays a validator example only (reported in the PR).
        assertEquals(examples - "overlapping-resource-incidents", cases)
    }

    // ------------------------------------------------------------------------------------------ status, shape, explicit nulls

    @Test
    fun `results written by the core are read as they are, a run without windows gives an UNKNOWN interval`() {
        val golden = Path.of("fixtures/typed-boundary/golden")
        val directories = Files.list(golden).use { stream -> stream.filter { Files.isDirectory(it) }.sorted().toList() }
        assertEquals(8, directories.size)
        for (directory in directories) {
            val result = Json.parseToJsonElement(Files.readString(directory.resolve("analysis-result.json"))).jsonObject
            val findings = result.getValue("findings").jsonArray.map { it.jsonObject }
            val evidence = result.getValue("evidence").jsonArray.map { it.jsonObject }
            val document = synthesizeIncidents(RunValidity.valueOf(result.str("run_validity")), findings, evidence)

            assertEquals("EVALUATED", document.str("status"), directory.name)
            assertEquals(
                findings.count {
                    it.str("type") == "policy_failure"
                },
                document.items().sumOf { it.getValue("finding_count").jsonPrimitive.int },
                directory.name,
            )
            document.assertReferencesResolve(findings, evidence)
        }
        val failing = Json.parseToJsonElement(Files.readString(golden.resolve("csv-fail/analysis-result.json"))).jsonObject
        val item =
            synthesizeIncidents(
                RunValidity.VALID,
                failing.getValue("findings").jsonArray.map { it.jsonObject },
                failing.getValue("evidence").jsonArray.map { it.jsonObject },
            ).items().single()

        assertEquals("UNKNOWN", item.str("interval_basis"))
        assertEquals(JsonNull, item["window_id"])
        assertEquals("overall", item.getValue("scope").jsonObject.str("kind"))
        assertEquals("Нарушение SLA: весь прогон", item.str("title"))
        assertEquals("metric-summary-overall", item.ids("evidence_ids").first())
        assertEquals(
            listOf("COMPARE_WITH_BASELINE", "PROVIDE_RESOURCE_SNAPSHOT"),
            item.getValue("next_checks").jsonArray.map {
                it.jsonObject.str("check")
            },
        )
    }

    @Test
    fun `an invalid run is not evaluated and has no items`() {
        val case = Case().apply { tx("1", "steady", "login") }
        val document = case.run(RunValidity.INVALID)

        assertEquals("incident.v1", document.str("schema_version"))
        assertEquals("incident-synthesis.v1", document.str("method"))
        assertEquals("NOT_EVALUATED", document.str("status"))
        assertEquals("RUN_NOT_VALID", document.str("reason_code"))
        assertEquals(7, document.getValue("overview_limit").jsonPrimitive.int)
        assertEquals(0, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(0, document.getValue("omitted_count").jsonPrimitive.int)
        assertEquals(0, document.getValue("items").jsonArray.size)
    }

    @Test
    fun `no findings give an evaluated document with empty items and no reason_code`() {
        val document = Case().run()

        assertEquals("EVALUATED", document.str("status"))
        assertFalse(document.containsKey("reason_code"))
        assertEquals(0, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(0, document.getValue("items").jsonArray.size)
    }

    @Test
    fun `a run without a window gives UNKNOWN intervals and explicit nulls`() {
        val case = Case().apply { txWithoutWindow("1", "login") }
        val item = case.run().items().single()

        assertEquals(JsonNull, item["window_id"])
        assertEquals(JsonNull, item["interval"])
        assertEquals("UNKNOWN", item.str("interval_basis"))
        assertEquals(JsonNull, item.getValue("priority_key").jsonObject["first_epoch_ms"])
        val key =
            item
                .getValue("grouping")
                .jsonObject
                .getValue("key")
                .jsonObject
        assertEquals(JsonNull, key["window_id"])
        assertEquals(JsonNull, key["cluster_from_epoch_ms"])
        assertTrue(key.containsKey("cluster_from_epoch_ms"))
        assertEquals("Нарушение SLA: транзакция login", item.str("title"))
        assertEquals(
            "За весь прогон нарушено правил policy: 1. Область: транзакция login. Время нарушения не определено.",
            item.str("summary"),
        )
    }

    @Test
    fun `a run without a snapshot but with a window gives a WINDOW interval`() {
        val case = Case().apply { tx("1", "steady", "login") }
        val item = case.run().items().single()

        assertEquals("WINDOW", item.str("interval_basis"))
        assertEquals(
            1_000L,
            item
                .getValue("interval")
                .jsonObject
                .getValue("from_epoch_ms")
                .jsonPrimitive.long,
        )
        assertEquals(
            2_000L,
            item
                .getValue("interval")
                .jsonObject
                .getValue("to_epoch_ms")
                .jsonPrimitive.long,
        )
        val checks = item.getValue("negative_evidence").jsonArray.map { it.jsonObject.str("check") }
        assertEquals(listOf("RESOURCE_DATA_NOT_PROVIDED"), checks)
    }

    @Test
    fun `the id is the SHA-256 of the canonical grouping key`() {
        val case =
            Case().apply {
                tx("1", "steady", "login")
                res("2", "steady", "host-a", 1_100, 1_200)
                txWithoutWindow("3", "search")
            }
        for (item in case.run().items()) {
            val key = item.getValue("grouping").jsonObject.getValue("key")
            assertEquals("incident-" + sha256Hex(canonicalJson(key)), item.str("id"))
            assertEquals(key.jsonObject["scope"], item["scope"])
            assertEquals(key.jsonObject["window_id"], item["window_id"])
        }
    }

    @Test
    fun `unknown finding types never become incidents`() {
        val case =
            Case().apply {
                findings += obj("type" to "correlation_candidate", "id" to "cc-1", "window_id" to "steady", "evidence_id" to "pair-1")
                findings += obj("type" to "diagnostic", "id" to "d-1", "code" to "X", "evidence_id" to "diag-1")
                findings += obj("type" to "capacity_stage_violation", "id" to "cap-1", "window_id" to "steady", "evidence_id" to "cap-e")
                evidence += obj("type" to "correlation_pair", "id" to "pair-1", "window_id" to "steady")
                evidence += obj("type" to "diagnostic", "id" to "diag-1", "code" to "X", "message" to "m")
                evidence += obj("type" to "capacity_knee_diagnostic", "id" to "cap-e")
            }
        val document = case.run()

        assertEquals("EVALUATED", document.str("status"))
        assertEquals(0, document.getValue("total_count").jsonPrimitive.int)
    }

    // ------------------------------------------------------------------------------------------------ limits (criterion 2)

    @Test
    fun `twenty groups give exactly seven overview incidents with contiguous ranks`() {
        val document = Case().apply { repeat(20) { tx("t$it", "steady", "tx-%02d".format(it)) } }.run()
        val items = document.items()

        assertEquals(20, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(0, document.getValue("omitted_count").jsonPrimitive.int)
        assertEquals(20, items.size)
        assertEquals((1..20).toList(), items.map { it.getValue("rank").jsonPrimitive.int })
        assertEquals(List(7) { true } + List(13) { false }, items.map { it.getValue("in_overview").jsonPrimitive.boolean })
    }

    @Test
    fun `more than sixty four groups store sixty four and count the rest`() {
        val document = Case().apply { repeat(70) { tx("t$it", "steady", "tx-%02d".format(it)) } }.run()

        assertEquals(70, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(6, document.getValue("omitted_count").jsonPrimitive.int)
        assertEquals(64, document.items().size)
        assertEquals(7, document.items().count { it.getValue("in_overview").jsonPrimitive.boolean })
    }

    @Test
    fun `exactly sixty four groups omit nothing`() {
        val document = Case().apply { repeat(64) { tx("t$it", "steady", "tx-%02d".format(it)) } }.run()

        assertEquals(64, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(0, document.getValue("omitted_count").jsonPrimitive.int)
        assertEquals(64, document.items().size)
    }

    // ---------------------------------------------------------------------------------------- references (criterion 4)

    @Test
    fun `every referenced id resolves and every incident has a finding and evidence`() {
        val case =
            Case().apply {
                tx("1", "steady", "login")
                tx("2", "steady", "login")
                res("3", "steady", "host-a", 1_100, 1_200)
                txWithoutWindow("4", "search")
                anomaly("5", "steady", "host-b", 1_300, 1_400)
                trend("6", "steady", "host-b", 1_350, 1_450)
                evidence += obj("type" to "policy_check", "id" to "pc-ok", "window_id" to "steady", "status" to "PASS")
                evidence += anomalyCheck("ac-ok", "steady", "NO_MATERIAL_CHANGE")
                evidence += trendCheck("tc-ok", "steady", "NO_MATERIAL_TREND")
                evidence += resourceCheck("rpc-ok", "steady", "s-x", "NO_VERDICT")
            }
        case.run().assertReferencesResolve(case.findings, case.evidence)
    }

    @Test
    fun `a repeated id in findings or evidence fails before the synthesis`() {
        val dupFindings = Case().apply { tx("1", "steady", "login") }
        dupFindings.findings += dupFindings.findings.first()
        assertThrows(IllegalArgumentException::class.java) { dupFindings.run() }
        val dupEvidence = Case().apply { tx("1", "steady", "login") }
        dupEvidence.evidence += dupEvidence.evidence.first()
        assertThrows(IllegalArgumentException::class.java) { dupEvidence.run() }
    }

    @Test
    fun `atoms without support are dropped and the rest is unaffected`() {
        val case = Case().apply { tx("ok", "steady", "login") }
        // evidence_id does not resolve
        case.findings +=
            obj("type" to "policy_failure", "id" to "pf-orphan", "window_id" to "steady", "rule_id" to "r", "evidence_id" to "missing")
        // policy_check without scope and without metric_evidence_id
        case.findings +=
            obj("type" to "policy_failure", "id" to "pf-noscope", "window_id" to "steady", "rule_id" to "r", "evidence_id" to "pc-noscope")
        case.evidence += obj("type" to "policy_check", "id" to "pc-noscope", "window_id" to "steady", "status" to "FAIL")
        // window without a window_policy_summary
        case.findings +=
            obj("type" to "policy_failure", "id" to "pf-nowindow", "window_id" to "gone", "rule_id" to "r", "evidence_id" to "pc-nowindow")
        case.evidence +=
            obj(
                "type" to "policy_check",
                "id" to "pc-nowindow",
                "window_id" to "gone",
                "status" to "FAIL",
                "scope" to obj("kind" to "overall"),
            )
        // resource findings with a degenerate, reversed, negative or unreadable interval, without a window or without an entity
        case.res("r1", "steady", "host-a", 1_500, 1_500)
        case.res("r2", "steady", "host-a", 1_600, 1_550)
        case.res("r3", "steady", "host-a", -5, 10)
        case.findings +=
            obj(
                "type" to "resource_threshold_violation",
                "id" to "f-r4",
                "entity" to "host-a",
                "from_epoch_ms" to "x",
                "to_epoch_ms" to 5,
                "window_id" to "steady",
                "evidence_id" to "rpc-r4",
            )
        case.evidence +=
            obj(
                "type" to "resource_policy_check",
                "id" to "rpc-r4",
                "window_id" to "steady",
                "series_id" to "s",
                "effect" to "sla",
                "status" to "FAIL",
            )
        case.findings +=
            obj(
                "type" to "resource_threshold_violation",
                "id" to "f-r5",
                "entity" to "host-a",
                "from_epoch_ms" to 1,
                "to_epoch_ms" to 5,
                "evidence_id" to "rpc-r5",
            )
        case.evidence += obj("type" to "resource_policy_check", "id" to "rpc-r5", "series_id" to "s", "effect" to "sla", "status" to "FAIL")
        case.findings +=
            obj(
                "type" to "resource_threshold_violation",
                "id" to "f-r6",
                "window_id" to "steady",
                "from_epoch_ms" to 1,
                "to_epoch_ms" to 5,
                "evidence_id" to "rpc-r5",
            )

        val document = case.run()

        assertEquals(1, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(listOf("pf-ok"), document.items().single().ids("finding_ids"))
        document.assertReferencesResolve(case.findings, case.evidence)
    }

    @Test
    fun `a manual result without scope gives an evaluated document with empty items`() {
        val findings = listOf(obj("type" to "policy_failure", "id" to "pf-1", "rule_id" to "r", "evidence_id" to "pc-1"))
        val evidence = listOf(obj("type" to "policy_check", "id" to "pc-1", "status" to "FAIL"))
        val document = synthesizeIncidents(RunValidity.VALID, findings, evidence)

        assertEquals("EVALUATED", document.str("status"))
        assertEquals(0, document.items().size)
    }

    @Test
    fun `the evidence of a finding without a window is read through metric_evidence_id`() {
        val case = Case().apply { txWithoutWindow("1", "login") }
        val item = case.run().items().single()

        assertEquals(listOf("metric-1", "pc-1"), item.ids("evidence_ids"))
        assertEquals("login", item.getValue("scope").jsonObject.str("label"))
    }

    @Test
    fun `finding and evidence lists are cut at sixteen and twenty four and flagged`() {
        val case = Case().apply { repeat(17) { tx("%02d".format(it), "steady", "login") } }
        val item = case.run().items().single()

        assertEquals(17, item.getValue("finding_count").jsonPrimitive.int)
        assertEquals(16, item.getValue("finding_ids").jsonArray.size)
        assertEquals(17, item.getValue("evidence_ids").jsonArray.size)
        assertTrue(item.getValue("refs_truncated").jsonPrimitive.boolean)

        val many = Case().apply { repeat(25) { res("%02d".format(it), "steady", "host-a", 1_000L + it, 1_000L + it + 1) } }
        val resource = many.run().items().single()
        assertEquals(24, resource.getValue("evidence_ids").jsonArray.size)
        assertEquals(16, resource.getValue("finding_ids").jsonArray.size)
        assertTrue(resource.getValue("refs_truncated").jsonPrimitive.boolean)

        val exact =
            Case()
                .apply { repeat(16) { tx("%02d".format(it), "steady", "login") } }
                .run()
                .items()
                .single()
        assertFalse(exact.getValue("refs_truncated").jsonPrimitive.boolean)
    }

    // -------------------------------------------------------------------------------- clusters and ordering (criterion 10)

    private fun clusters(vararg intervals: Pair<Long, Long>): List<Pair<Long, Long>> {
        val case =
            Case().apply {
                intervals.forEachIndexed { index, (from, to) -> res("$index", "steady", "host-a", from, to) }
            }
        return case
            .run()
            .items()
            .map {
                val interval = it.getValue("interval").jsonObject
                interval.getValue("from_epoch_ms").jsonPrimitive.long to interval.getValue("to_epoch_ms").jsonPrimitive.long
            }.sortedBy { it.first }
    }

    @Test
    fun `touching intervals form one cluster`() = assertEquals(listOf(1_000L to 1_020L), clusters(1_000L to 1_010L, 1_010L to 1_020L))

    @Test
    fun `overlapping intervals form one cluster`() = assertEquals(listOf(1_000L to 1_015L), clusters(1_000L to 1_010L, 1_005L to 1_015L))

    @Test
    fun `a nested interval joins its cluster`() = assertEquals(listOf(1_000L to 1_030L), clusters(1_000L to 1_030L, 1_010L to 1_020L))

    @Test
    fun `a gap separates clusters`() =
        assertEquals(listOf(1_000L to 1_010L, 1_011L to 1_020L), clusters(1_000L to 1_010L, 1_011L to 1_020L))

    @Test
    fun `a chain of three touching intervals is one cluster`() =
        assertEquals(listOf(1_000L to 1_030L), clusters(1_000L to 1_010L, 1_010L to 1_020L, 1_020L to 1_030L))

    @Test
    fun `a bridging finding joins two clusters into one`() {
        assertEquals(listOf(1_000L to 1_010L, 1_020L to 1_030L), clusters(1_000L to 1_010L, 1_020L to 1_030L))
        assertEquals(listOf(1_000L to 1_030L), clusters(1_000L to 1_010L, 1_020L to 1_030L, 1_008L to 1_022L))
    }

    @Test
    fun `the cluster id depends on the earliest atom and on nothing else`() {
        val first =
            Case()
                .apply { res("a", "steady", "host-a", 1_010, 1_020) }
                .run()
                .items()
                .single()
        val second =
            Case()
                .apply {
                    res("a", "steady", "host-a", 1_010, 1_020)
                    res("b", "steady", "host-a", 1_000, 1_011)
                }.run()
                .items()
                .single()

        assertNotEquals(first.str("id"), second.str("id"))
        val key =
            second
                .getValue("grouping")
                .jsonObject
                .getValue("key")
                .jsonObject
        assertEquals(1_000L, key.getValue("cluster_from_epoch_ms").jsonPrimitive.long)
    }

    @Test
    fun `different windows and different entities are never merged`() {
        val case =
            Case().apply {
                window("other", 3_000, 4_000)
                res("a", "steady", "host-a", 1_000, 1_010)
                res("b", "other", "host-a", 1_000, 1_010)
                res("c", "steady", "host-b", 1_000, 1_010)
                tx("d", "steady", "login")
                tx("e", "other", "login")
            }
        val items = case.run().items()

        assertEquals(5, items.size)
        assertEquals(setOf("steady", "other"), items.map { it.str("window_id") }.toSet())
    }

    @Test
    fun `one transaction in one window is one incident for all its metrics`() {
        val case =
            Case().apply {
                tx("p95", "steady", "login")
                tx("err", "steady", "login")
                tx("other", "steady", "search")
            }
        val items = case.run().items()

        assertEquals(2, items.size)
        assertEquals(listOf(2, 1), items.map { it.getValue("finding_count").jsonPrimitive.int })
    }

    @Test
    fun `scope is copied as is, including the empty group path, and extra fields are dropped`() {
        val scope =
            obj(
                "kind" to "transaction",
                "label" to "GET /a",
                "group_path" to listOf<Any?>(),
                "sample_kind" to "JMETER_SAMPLER",
                "junk" to 1,
            )
        val case = Case()
        case.findings += obj("type" to "policy_failure", "id" to "pf-1", "window_id" to "steady", "rule_id" to "r", "evidence_id" to "pc-1")
        case.evidence += obj("type" to "policy_check", "id" to "pc-1", "window_id" to "steady", "status" to "FAIL", "scope" to scope)
        val item = case.run().items().single()

        assertEquals(
            obj("kind" to "transaction", "label" to "GET /a", "group_path" to listOf<Any?>(), "sample_kind" to "JMETER_SAMPLER"),
            item["scope"],
        )
    }

    @Test
    fun `priority orders by tier, finding count, start and then the id`() {
        val case =
            Case().apply {
                tx("a", "steady", "t-one")
                tx("b1", "steady", "t-two")
                tx("b2", "steady", "t-two")
                res("c", "steady", "host-a", 1_100, 1_200, effect = "sla")
                res("d", "steady", "host-b", 1_050, 1_200, effect = "diagnostic")
                res("e", "steady", "host-c", 1_040, 1_200, presumed = true)
                anomaly("f", "steady", "host-d", 1_030, 1_200)
                trend("g", "steady", "host-e", 1_020, 1_200)
            }
        val items = case.run().items()

        // tier 1: t-two (2 findings) before t-one (1); tier 2: host-a; tier 3: host-c (start 1_040) before host-b (1_050); then 4 and 5
        assertEquals(listOf("t-two", "t-one", "host-a", "host-c", "host-b", "host-d", "host-e"), items.map { it.name() })
        assertEquals(
            listOf(1, 1, 2, 3, 3, 4, 5),
            items.map {
                it
                    .getValue("priority_key")
                    .jsonObject
                    .getValue("tier")
                    .jsonPrimitive.int
            },
        )
        assertEquals(listOf("HIGH", "HIGH", "HIGH", "MEDIUM", "MEDIUM", "MEDIUM", "LOW"), items.map { it.str("priority") })
    }

    @Test
    fun `a tie in tier, count and start is broken by the id`() {
        val case = Case().apply { repeat(12) { tx("t$it", "steady", "tx-$it") } }
        val ids = case.run().items().map { it.str("id") }

        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `bytes of UTF-8 decide, not the units of UTF-16`() {
        val bmp = "～" // U+FF5E: EF BD 9E in UTF-8, 0xFF5E in UTF-16
        val astral = "😀" // U+1F600: F0 9F 98 80 in UTF-8, D83D DE00 in UTF-16
        assertTrue(utf8Compare(bmp, astral) < 0)
        assertTrue(bmp.compareTo(astral) > 0)
        assertEquals(0, utf8Compare("a$astral", "a$astral"))
        assertTrue(utf8Compare("a", "ab") < 0)

        val case = Case()
        for ((index, id) in listOf("f-$astral", "f-$bmp", "f-z").withIndex()) {
            case.findings +=
                obj("type" to "policy_failure", "id" to id, "window_id" to "steady", "rule_id" to "r", "evidence_id" to "e$index-$astral")
            case.evidence +=
                obj(
                    "type" to "policy_check",
                    "id" to "e$index-$astral",
                    "window_id" to "steady",
                    "status" to "FAIL",
                    "scope" to obj("kind" to "overall"),
                )
        }
        case.evidence +=
            obj(
                "type" to "policy_check",
                "id" to "e-$bmp",
                "window_id" to "steady",
                "status" to "FAIL",
                "scope" to obj("kind" to "overall"),
            )
        case.findings +=
            obj("type" to "policy_failure", "id" to "f-$bmp-2", "window_id" to "steady", "rule_id" to "r", "evidence_id" to "e-$bmp")
        val item = case.run().items().single()

        val findingIds = item.ids("finding_ids")
        assertEquals(findingIds.sortedWith(::utf8Compare), findingIds)
        assertEquals(listOf("f-z", "f-$bmp", "f-$bmp-2", "f-$astral"), findingIds)
        val evidenceIds = item.ids("evidence_ids")
        assertEquals(evidenceIds.sortedWith(::utf8Compare), evidenceIds)
        assertEquals("e-$bmp", evidenceIds.first { it.startsWith("e-～") })
        assertTrue(evidenceIds.indexOf("e-$bmp") < evidenceIds.indexOf("e0-$astral"))
    }

    @Test
    fun `entities with non-ASCII names are grouped by the full name`() {
        val case = Case()
        case.res("a", "steady", "хост-😀", 1_000, 1_010)
        case.res("b", "steady", "хост-～", 1_000, 1_010)
        val entities =
            case
                .run()
                .items()
                .map { it.getValue("scope").jsonObject.str("entity") }
                .toSet()

        assertEquals(setOf("хост-😀", "хост-～"), entities)
    }

    // ----------------------------------------------------------------------------- names, templates (criteria 3 and 10)

    @Test
    fun `the displayed name replaces control characters and is cut at sixty four code points`() {
        assertEquals("a b c d", incidentDisplayName("a\tb\u0000c\u009Fd"))
        assertEquals("a b", incidentDisplayName("a\u007Fb"))
        val exact = "x".repeat(64)
        assertEquals(exact, incidentDisplayName(exact))
        assertEquals("x".repeat(63) + "…", incidentDisplayName("x".repeat(65)))
        val astral = "😀"
        assertEquals(astral.repeat(63) + "…", incidentDisplayName(astral.repeat(70)))
        assertEquals(astral.repeat(64), incidentDisplayName(astral.repeat(64)))
    }

    @Test
    fun `long names, controls and astral characters give a clean text and keep the full name in scope`() {
        val label = "tab\tname-" + "😀".repeat(80) + "-\u0001end"
        val case = Case()
        case.tx("1", "steady", label)
        case.res("2", "steady", "e".repeat(128), 1_000, 1_100)
        val items = case.run().items()
        val transaction = items.first { it.getValue("scope").jsonObject.str("kind") == "transaction" }

        assertEquals(label, transaction.getValue("scope").jsonObject.str("label"))
        assertEquals(
            label,
            transaction
                .getValue("grouping")
                .jsonObject
                .getValue("key")
                .jsonObject
                .getValue("scope")
                .jsonObject
                .str("label"),
        )
        assertEquals("Нарушение SLA: транзакция ${incidentDisplayName(label)} в окне steady", transaction.str("title"))
        for (item in items) {
            for (text in item.renderedTexts()) {
                assertTrue(text.codePointCount(0, text.length) in 1..400, text)
                assertTrue(text.none { it in '\u0000'..'\u001F' || it in '\u007F'..'\u009F' }, text)
            }
        }
    }

    @Test
    fun `a label of exactly 4096 bytes is kept whole`() {
        val label = "я".repeat(2048)
        assertEquals(4096, label.encodeToByteArray().size)
        val case = Case()
        case.tx("1", "steady", label)
        val item = case.run().items().single()

        assertEquals(label, item.getValue("scope").jsonObject.str("label"))
    }

    @Test
    fun `a name that looks like a placeholder is not substituted twice`() {
        val case = Case()
        case.tx("1", "steady", "{n}{window_id}")
        val item = case.run().items().single()

        assertEquals("Нарушение SLA: транзакция {n}{window_id} в окне steady", item.str("title"))
        assertEquals("В окне steady нарушено правил policy: 1. Область: транзакция {n}{window_id}.", item.str("summary"))
    }

    @Test
    fun `the load signal uses the load wording`() {
        val case = Case().apply { anomaly("1", "steady", "overall", 1_100, 1_200) }
        val item = case.run().items().single()

        assertEquals("Сигналы нагрузки в окне steady", item.str("title"))
        assertEquals(
            "На интервале по общей нагрузке найдено находок: 1; их интервалы перекрываются или соприкасаются.",
            item.str("summary"),
        )
        assertEquals(
            "Открыть ряды нагрузки за интервал находок.",
            item
                .getValue("next_checks")
                .jsonArray
                .first()
                .jsonObject
                .str("text"),
        )
    }

    @Test
    fun `template texts state no cause and the list matches the verifier`() {
        assertTrue(INCIDENT_TEXT_TEMPLATES.size >= 18, "templates: ${INCIDENT_TEXT_TEMPLATES.size}")
        for (template in INCIDENT_TEXT_TEMPLATES) assertNull(CAUSAL.find(template), template)

        val source = Files.readString(Path.of("tools/verify_slice0.py"))
        val block = source.substringAfter("CAUSAL_WORDING = re.compile(").substringBefore("re.IGNORECASE")
        val pattern = Regex("r\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(block).joinToString("") { it.groupValues[1] }
        assertEquals(CAUSAL_PATTERN, pattern, "CAUSAL_WORDING in tools/verify_slice0.py differs from the list of this test")
        for (word in FORBIDDEN_SAMPLES) assertTrue(CAUSAL.containsMatchIn(word), word)
        assertNull(CAUSAL.find("Нарушение SLA: транзакция login"))
    }

    @Test
    fun `every rendered text of the neutral fixtures states no cause`() {
        for (case in fixtureCases().filterNot { it.name.startsWith("names-") }) {
            val expected = Json.parseToJsonElement(Files.readString(expectedPath(case))).jsonObject
            for (item in expected.items()) {
                for (text in item.renderedTexts()) assertNull(CAUSAL.find(text), "${case.name}: $text")
            }
        }
    }

    @Test
    fun `a name with a causal word does not break the synthesis`() {
        val case = Case()
        case.tx("1", "steady", "cause-list")
        case.res("2", "steady", "because-host", 1_000, 1_100)
        assertEquals(2, case.run().items().size)
    }

    // ------------------------------------------------------------------------------------ negative evidence (criterion 11)

    private fun JsonObject.negative(check: String): JsonObject? =
        getValue("negative_evidence").jsonArray.map { it.jsonObject }.firstOrNull {
            it.str("check") ==
                check
        }

    private fun JsonObject.checks(): List<String> = getValue("negative_evidence").jsonArray.map { it.jsonObject.str("check") }

    @Test
    fun `NOT_CONFIRMED and NOT_EVALUATED are separate outcomes with their own fields`() {
        val case =
            Case().apply {
                tx("1", "steady", "login")
                res("2", "steady", "host-a", 1_100, 1_200)
                evidence += obj("type" to "resource_binding", "id" to "binding")
                evidence += obj("type" to "policy_check", "id" to "pc-ok-1", "window_id" to "steady", "status" to "PASS")
                evidence += obj("type" to "policy_check", "id" to "pc-ok-2", "window_id" to "steady", "status" to "PASS")
                evidence +=
                    obj(
                        "type" to "policy_check",
                        "id" to "pc-nv",
                        "window_id" to "steady",
                        "status" to "NO_VERDICT",
                        "reason_code" to "INSUFFICIENT_SAMPLES",
                    )
                evidence += resourceCheck("rpc-ok", "steady", "s-b", "PASS")
                evidence += resourceSummary("rs-b", "steady", "s-b", "host-b", "system")
                evidence += resourceCheck("rpc-gen", "steady", "s-g", "PASS")
                evidence += resourceSummary("rs-g", "steady", "s-g", "gen-1", "generator")
                evidence += anomalyCheck("ac-1", "steady", "NO_MATERIAL_CHANGE")
                evidence += trendCheck("tc-1", "steady", "NO_MATERIAL_TREND")
                evidence += trendCheck("tc-2", "steady", "INSUFFICIENT_CELLS", listOf("MISSING_CELLS"))
                evidence +=
                    obj(
                        "type" to "rule_window_check",
                        "id" to "rwc-1",
                        "window_id" to "steady",
                        "status" to "NO_VERDICT",
                        "reason_code" to "A_CODE",
                        "rule_id" to "r",
                    )
            }
        val items = case.run().items()
        val transaction = items.first { it.family() == "TRANSACTION" }
        val resource = items.first { it.family() == "RESOURCE" }

        assertEquals(
            listOf(
                "OTHER_POLICY_CHECKS_PASSED",
                "GENERATOR_RESOURCES_WITHIN_LIMITS",
                "NO_ANOMALY_EPISODES",
                "NO_MATERIAL_TREND",
                "CHECKS_NOT_EVALUATED",
            ),
            transaction.checks(),
        )
        val other = transaction.negative("OTHER_POLICY_CHECKS_PASSED")!!
        assertEquals("NOT_CONFIRMED", other.str("outcome"))
        assertFalse(other.containsKey("reason_code"))
        assertEquals(listOf("pc-ok-1", "pc-ok-2"), other.ids("evidence_ids"))
        assertEquals("Остальные проверки policy в окне steady выполнены: 2.", other.str("text"))
        assertEquals(
            "Правила ресурсов нагрузочного генератора в окне steady не нарушены: проверок 1.",
            transaction.negative("GENERATOR_RESOURCES_WITHIN_LIMITS")!!.str("text"),
        )
        assertEquals("Проверки отклонений в окне steady не нашли эпизодов: 1.", transaction.negative("NO_ANOMALY_EPISODES")!!.str("text"))
        assertEquals(
            "Проверки трендов в окне steady не нашли существенного тренда: 1.",
            transaction.negative("NO_MATERIAL_TREND")!!.str("text"),
        )
        val notEvaluated = transaction.negative("CHECKS_NOT_EVALUATED")!!
        assertEquals("NOT_EVALUATED", notEvaluated.str("outcome"))
        assertEquals("A_CODE", notEvaluated.str("reason_code"))
        assertEquals(listOf("pc-nv", "rwc-1", "tc-2"), notEvaluated.ids("evidence_ids"))
        assertEquals("Часть проверок в окне steady не выполнена: 3; код: A_CODE.", notEvaluated.str("text"))

        assertEquals(
            listOf("RESOURCE_RULES_WITHIN_LIMITS", "NO_ANOMALY_EPISODES", "NO_MATERIAL_TREND", "CHECKS_NOT_EVALUATED"),
            resource.checks(),
        )
        assertEquals(listOf("rpc-ok"), resource.negative("RESOURCE_RULES_WITHIN_LIMITS")!!.ids("evidence_ids"))
        assertEquals(
            "Правила ресурсов в окне steady не нарушены: проверок 1.",
            resource.negative("RESOURCE_RULES_WITHIN_LIMITS")!!.str("text"),
        )
        for (item in items) {
            for (entry in item.getValue("negative_evidence").jsonArray.map { it.jsonObject }) {
                if (entry.str("outcome") == "NOT_CONFIRMED") assertTrue(entry.getValue("evidence_ids").jsonArray.isNotEmpty())
            }
        }
    }

    @Test
    fun `a resource incident leaves out the checks of its own entity and of an unknown entity`() {
        val case =
            Case().apply {
                res("1", "steady", "host-a", 1_100, 1_200)
                evidence += obj("type" to "resource_binding", "id" to "binding")
                evidence += resourceCheck("rpc-own", "steady", "s-own", "PASS")
                evidence += resourceSummary("rs-own", "steady", "s-own", "host-a", "system")
                evidence += resourceCheck("rpc-unknown", "steady", "s-unknown", "PASS")
                evidence += resourceCheck("rpc-other", "steady", "s-other", "PASS")
                evidence += resourceSummary("rs-other", "steady", "s-other", "host-b", "system")
            }
        val entry =
            case
                .run()
                .items()
                .single()
                .negative("RESOURCE_RULES_WITHIN_LIMITS")!!

        assertEquals(listOf("rpc-other"), entry.ids("evidence_ids"))
    }

    @Test
    fun `the code of the checks without a verdict is the least by bytes and a check without a code gives its status`() {
        val case =
            Case().apply {
                tx("1", "steady", "login")
                evidence += obj("type" to "resource_binding", "id" to "binding")
                evidence += anomalyCheck("ac-1", "steady", "INSUFFICIENT_DATA", listOf("ZERO_MAD", "NO_EVALUATION_OBSERVATIONS"))
                evidence += trendCheck("tc-1", "steady", "UNAVAILABLE", emptyList())
                evidence += resourceCheck("rpc-1", "steady", "s", "NO_VERDICT", reason = null)
            }
        val entry =
            case
                .run()
                .items()
                .single()
                .negative("CHECKS_NOT_EVALUATED")!!

        assertEquals("NO_EVALUATION_OBSERVATIONS", entry.str("reason_code"))
        assertEquals(3, entry.getValue("evidence_ids").jsonArray.size)
        val onlyStatus =
            Case()
                .apply {
                    tx("1", "steady", "login")
                    evidence += obj("type" to "resource_binding", "id" to "binding")
                    evidence += resourceCheck("rpc-1", "steady", "s", "NO_VERDICT", reason = null)
                }.run()
                .items()
                .single()
                .negative("CHECKS_NOT_EVALUATED")!!
        assertEquals("NO_VERDICT", onlyStatus.str("reason_code"))
    }

    @Test
    fun `without a resource_binding the resource data is reported as not provided, with a fixed reason`() {
        val case = Case().apply { tx("1", "steady", "login") }
        val entry =
            case
                .run()
                .items()
                .single()
                .negative("RESOURCE_DATA_NOT_PROVIDED")!!

        assertEquals("NOT_EVALUATED", entry.str("outcome"))
        assertEquals("RESOURCE_SNAPSHOT_NOT_PROVIDED", entry.str("reason_code"))
        assertEquals(0, entry.getValue("evidence_ids").jsonArray.size)
        assertEquals("Снимок ресурсов не передан: ресурсные проверки не выполнялись.", entry.str("text"))

        val bound = Case().apply { tx("1", "steady", "login") }
        bound.evidence += obj("type" to "resource_binding", "id" to "binding")
        assertNull(
            bound
                .run()
                .items()
                .single()
                .negative("RESOURCE_DATA_NOT_PROVIDED"),
        )
    }

    @Test
    fun `a run without a window reports only the policy and the snapshot checks`() {
        val case = Case().apply { txWithoutWindow("1", "login") }
        case.evidence += obj("type" to "policy_check", "id" to "pc-ok", "status" to "PASS")
        case.evidence += obj("type" to "policy_check", "id" to "pc-win", "window_id" to "steady", "status" to "PASS")
        case.evidence += anomalyCheck("ac-1", "steady", "NO_MATERIAL_CHANGE")
        val item = case.run().items().single()

        assertEquals(listOf("OTHER_POLICY_CHECKS_PASSED", "RESOURCE_DATA_NOT_PROVIDED"), item.checks())
        assertEquals("Остальные проверки policy выполнены: 1.", item.negative("OTHER_POLICY_CHECKS_PASSED")!!.str("text"))
        assertEquals(listOf("pc-ok"), item.negative("OTHER_POLICY_CHECKS_PASSED")!!.ids("evidence_ids"))
    }

    @Test
    fun `a degraded run marks the resource incidents with POLICY_NOT_EVALUATED`() {
        val case = Case().apply { res("1", "steady", "host-a", 1_100, 1_200) }
        case.evidence += obj("type" to "resource_binding", "id" to "binding")
        val item = case.run(RunValidity.DEGRADED).items().single()
        val entry = item.negative("POLICY_NOT_EVALUATED")!!

        assertEquals("NOT_EVALUATED", entry.str("outcome"))
        assertEquals("RUN_DEGRADED", entry.str("reason_code"))
        assertEquals(0, entry.getValue("evidence_ids").jsonArray.size)
        assertEquals("Правила policy по транзакциям не проверялись: файл нагрузки разобран не полностью.", entry.str("text"))
        assertEquals("POLICY_NOT_EVALUATED", item.checks().first())
        assertNull(
            case
                .run(RunValidity.VALID)
                .items()
                .single()
                .negative("POLICY_NOT_EVALUATED"),
        )
    }

    @Test
    fun `the evidence of a negative check is cut at eight and the count is the full count`() {
        val case = Case().apply { tx("1", "steady", "login") }
        repeat(11) {
            case.evidence +=
                obj("type" to "policy_check", "id" to "ok-%02d".format(it), "window_id" to "steady", "status" to "PASS")
        }
        val entry =
            case
                .run()
                .items()
                .single()
                .negative("OTHER_POLICY_CHECKS_PASSED")!!

        assertEquals(8, entry.getValue("evidence_ids").jsonArray.size)
        assertEquals("ok-00", entry.ids("evidence_ids").first())
        assertEquals("Остальные проверки policy в окне steady выполнены: 11.", entry.str("text"))
    }

    @Test
    fun `checks of another window are not used`() {
        val case = Case().apply { tx("1", "steady", "login") }
        case.evidence += obj("type" to "policy_check", "id" to "pc-other", "window_id" to "other", "status" to "PASS")
        case.evidence += anomalyCheck("ac-other", "other", "NO_MATERIAL_CHANGE")

        assertNull(
            case
                .run()
                .items()
                .single()
                .negative("OTHER_POLICY_CHECKS_PASSED"),
        )
        assertNull(
            case
                .run()
                .items()
                .single()
                .negative("NO_ANOMALY_EPISODES"),
        )
    }

    // --------------------------------------------------------------------------------------------- next checks and links

    @Test
    fun `next checks follow the table`() {
        val case =
            Case().apply {
                tx("1", "steady", "login")
                res("2", "steady", "host-a", 1_100, 1_200)
            }
        case.evidence += trendCheck("tc-1", "steady", "UNAVAILABLE", listOf("TREND_SERIES_NOT_FOUND"))
        val items = case.run().items()
        val transaction = items.first { it.family() == "TRANSACTION" }
        val resource = items.first { it.family() == "RESOURCE" }

        fun JsonObject.nextChecks() = getValue("next_checks").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("COMPARE_WITH_BASELINE", "OPEN_SAME_WINDOW_SIGNALS", "PROVIDE_RESOURCE_SNAPSHOT", "COMPLETE_NOT_EVALUATED_CHECKS"),
            transaction.nextChecks().map { it.str("check") },
        )
        assertEquals(
            listOf("OPEN_RESOURCE_SERIES", "OPEN_SAME_WINDOW_SIGNALS", "PROVIDE_RESOURCE_SNAPSHOT", "COMPLETE_NOT_EVALUATED_CHECKS"),
            resource.nextChecks().map { it.str("check") },
        )
        val compare = transaction.nextChecks()[0]
        assertEquals("Сравнить метрики транзакции login с baseline.", compare.str("text"))
        assertEquals(listOf("pc-1"), compare.ids("evidence_ids"))
        assertEquals("Открыть сигналы окна steady, совпавшие по времени: 1.", transaction.nextChecks()[1].str("text"))
        assertEquals(
            0,
            transaction
                .nextChecks()[1]
                .getValue("evidence_ids")
                .jsonArray.size,
        )
        assertEquals(
            "Передать снимок ресурсов, чтобы проверить ресурсные сигналы на том же интервале.",
            transaction.nextChecks()[2].str("text"),
        )
        val complete = transaction.nextChecks()[3]
        assertEquals("Устранить недостаток данных для проверок, помеченных как не выполненные.", complete.str("text"))
        assertEquals(listOf("tc-1"), complete.ids("evidence_ids"))
        assertEquals("Открыть ряды сущности host-a за интервал находок.", resource.nextChecks()[0].str("text"))
        assertEquals(listOf("rpc-2"), resource.nextChecks()[0].ids("evidence_ids"))
    }

    private fun JsonObject.links(): List<Pair<String, String>> =
        getValue("coincident_with").jsonArray.map { it.jsonObject.str("incident_id") to it.jsonObject.str("basis") }

    @Test
    fun `links follow the rules of the ADR`() {
        val case =
            Case().apply {
                window("other", 3_000, 4_000)
                tx("t1", "steady", "login")
                tx("t2", "steady", "search")
                res("a", "steady", "host-a", 1_000, 1_100)
                res("a2", "steady", "host-a", 1_500, 1_600)
                res("b", "steady", "host-b", 1_050, 1_150)
                res("c", "steady", "host-c", 1_100, 1_200)
                res("d", "other", "host-a", 3_000, 3_100)
                txWithoutWindow("n", "nowin")
            }
        val items = case.run().items()
        assertEquals(8, items.size)
        val nameOf = items.associate { it.str("id") to it.name() }

        fun linksOf(item: JsonObject) = item.links().associate { (id, basis) -> nameOf.getValue(id) to basis }

        fun start(item: JsonObject) =
            item
                .getValue("interval")
                .jsonObject
                .getValue("from_epoch_ms")
                .jsonPrimitive.long
        val hostA = items.first { it.name() == "host-a" && it.str("window_id") == "steady" && start(it) == 1_000L }
        val laterHostA = items.first { it.name() == "host-a" && it.str("window_id") == "steady" && start(it) == 1_500L }

        // host-a [1000,1100) overlaps host-b [1050,1150) but only touches host-c [1100,1200); both transactions of the window are linked
        assertEquals(mapOf("host-b" to "INTERVAL_OVERLAP", "login" to "SAME_WINDOW", "search" to "SAME_WINDOW"), linksOf(hostA))
        // the same entity in two clusters of one window is not linked, and a cluster far away overlaps nothing
        assertEquals(mapOf("login" to "SAME_WINDOW", "search" to "SAME_WINDOW"), linksOf(laterHostA))
        // a transaction links to the resource incidents of its window only, as SAME_WINDOW, never to another transaction
        val login = items.first { it.name() == "login" }
        assertEquals(setOf("host-a", "host-b", "host-c"), linksOf(login).keys)
        assertTrue(linksOf(login).values.all { it == "SAME_WINDOW" })
        // another window does not mix with this one, and a run without a window has no links
        assertEquals(emptyMap<String, String>(), linksOf(items.first { it.str("window_id") == "other" && it.name() == "host-a" }))
        assertEquals(emptyMap<String, String>(), linksOf(items.first { it.name() == "nowin" }))
        for (item in items) assertFalse(item.links().any { it.first == item.str("id") })
    }

    @Test
    fun `links are the first five eligible incidents by rank and refer to stored incidents only`() {
        val case = Case()
        repeat(8) { case.res("r$it", "steady", "host-$it", 1_000, 1_100) }
        case.tx("t", "steady", "login")
        val items = case.run().items()
        val ids = items.map { it.str("id") }
        val transaction = items.first { it.family() == "TRANSACTION" }

        assertEquals(5, transaction.links().size)
        assertEquals(ids.filter { it != transaction.str("id") }.take(5), transaction.links().map { it.first })
        for (item in items) assertTrue(ids.containsAll(item.links().map { it.first }))

        val big = Case()
        repeat(70) { big.res("r$it", "steady", "host-%02d".format(it), 1_000, 1_100) }
        val stored = big.run().items()
        val storedIds = stored.map { it.str("id") }.toSet()
        assertEquals(64, stored.size)
        for (item in stored) assertTrue(storedIds.containsAll(item.links().map { it.first }))
    }

    // -------------------------------------------------------------------------------------------------------- tiers

    @Test
    fun `tiers follow the table`() {
        fun tier(build: Case.() -> Unit): Int =
            Case()
                .apply(build)
                .run()
                .items()
                .single()
                .getValue("priority_key")
                .jsonObject
                .getValue("tier")
                .jsonPrimitive.int
        assertEquals(1, tier { tx("1", "steady", "login") })
        assertEquals(2, tier { res("1", "steady", "host-a", 1_100, 1_200, effect = "sla") })
        assertEquals(3, tier { res("1", "steady", "host-a", 1_100, 1_200, effect = "diagnostic") })
        assertEquals(3, tier { res("1", "steady", "host-a", 1_100, 1_200, effect = "sla", presumed = true) })
        assertEquals(4, tier { anomaly("1", "steady", "host-a", 1_100, 1_200) })
        assertEquals(5, tier { trend("1", "steady", "host-a", 1_100, 1_200) })
        // the lowest tier of a cluster decides
        val mixed =
            Case()
                .apply {
                    trend("1", "steady", "host-a", 1_100, 1_200)
                    res("2", "steady", "host-a", 1_150, 1_250)
                }.run()
                .items()
                .single()
        assertEquals(
            2,
            mixed
                .getValue("priority_key")
                .jsonObject
                .getValue("tier")
                .jsonPrimitive.int,
        )
        assertEquals(listOf("resource_threshold_violation", "resource_trend"), mixed.ids("finding_types"))
    }

    // ------------------------------------------------------------------------------------------ size (criterion 13)

    @Test
    fun `sixty four incidents with the longest names stay far below the storage limit`() {
        val case = Case()
        repeat(64) { case.tx("t$it", "steady", "%04d".format(it) + "я".repeat(2046)) }
        val document = case.run()
        val size = canonicalJson(document).size

        assertEquals(64, document.items().size)
        println("incident document with 64 incidents and 4096-byte labels: $size bytes")
        assertTrue(size < 64 * 1024 * 1024, "size $size")
    }

    @Test
    fun `ten thousand findings are synthesized and the time is reported`() {
        val case = Case().apply { window("steady", 1_000_000, 9_000_000) }
        repeat(10_000) {
            // 500 entities with 20 findings each, whose intervals overlap in a chain: one cluster per entity
            val from = 1_000_000L + (it / 500) * 100L
            case.res("r$it", "steady", "host-%03d".format(it % 500), from, from + 150L)
        }
        val started = System.nanoTime()
        val document = case.run()
        val millis = (System.nanoTime() - started) / 1_000_000
        println("synthesis of 10000 findings: $millis ms, ${document.getValue("total_count").jsonPrimitive.int} incidents")

        assertEquals(500, document.getValue("total_count").jsonPrimitive.int)
        assertEquals(64, document.items().size)
        assertEquals(436, document.getValue("omitted_count").jsonPrimitive.int)
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private class Case {
        val findings = mutableListOf<JsonObject>()
        val evidence = mutableListOf<JsonObject>()

        init {
            window("steady", 1_000, 2_000)
        }

        fun window(
            id: String,
            from: Long,
            to: Long,
        ) {
            evidence.removeAll { it.str("id") == "wps-$id" }
            evidence +=
                obj(
                    "type" to "window_policy_summary",
                    "id" to "wps-$id",
                    "window_id" to id,
                    "from_epoch_ms" to from,
                    "to_epoch_ms" to to,
                    "verdict" to "FAIL",
                )
        }

        fun tx(
            n: String,
            window: String,
            label: String,
        ) {
            findings += obj("type" to "policy_failure", "id" to "pf-$n", "window_id" to window, "rule_id" to "r", "evidence_id" to "pc-$n")
            evidence +=
                obj(
                    "type" to "policy_check",
                    "id" to "pc-$n",
                    "window_id" to window,
                    "rule_id" to "r",
                    "status" to "FAIL",
                    "scope" to obj("kind" to "transaction", "label" to label),
                )
        }

        fun res(
            n: String,
            window: String,
            entity: String,
            from: Long,
            to: Long,
            effect: String = "sla",
            presumed: Boolean = false,
        ) {
            val finding =
                mutableMapOf<String, JsonElement>(
                    "type" to JsonPrimitive("resource_threshold_violation"),
                    "id" to JsonPrimitive("f-$n"),
                    "window_id" to JsonPrimitive(window),
                    "rule_id" to JsonPrimitive("r"),
                    "series_id" to JsonPrimitive("s-$n"),
                    "entity" to JsonPrimitive(entity),
                    "from_epoch_ms" to JsonPrimitive(from),
                    "to_epoch_ms" to JsonPrimitive(to),
                    "evidence_id" to JsonPrimitive("rpc-$n"),
                )
            if (presumed) finding["presumed"] = JsonPrimitive(true)
            findings += JsonObject(finding)
            evidence += resourceCheck("rpc-$n", window, "s-$n", "FAIL", effect)
        }

        fun anomaly(
            n: String,
            window: String,
            entity: String,
            from: Long,
            to: Long,
        ) {
            findings +=
                obj(
                    "type" to "anomaly_episode",
                    "id" to "f-$n",
                    "window_id" to window,
                    "entity" to entity,
                    "from_epoch_ms" to from,
                    "to_epoch_ms" to to,
                    "evidence_id" to "ac-$n",
                )
            evidence += anomalyCheck("ac-$n", window, "EPISODES_FOUND")
        }

        fun trend(
            n: String,
            window: String,
            entity: String,
            from: Long,
            to: Long,
        ) {
            findings +=
                obj(
                    "type" to "resource_trend",
                    "id" to "f-$n",
                    "window_id" to window,
                    "entity" to entity,
                    "from_epoch_ms" to from,
                    "to_epoch_ms" to to,
                    "evidence_id" to "tc-$n",
                )
            evidence += trendCheck("tc-$n", window, "MATERIAL_TREND_OBSERVED")
        }

        fun txWithoutWindow(
            n: String,
            label: String,
        ) {
            findings += obj("type" to "policy_failure", "id" to "pf-$n", "rule_id" to "r", "evidence_id" to "pc-$n")
            evidence +=
                obj("type" to "policy_check", "id" to "pc-$n", "rule_id" to "r", "status" to "FAIL", "metric_evidence_id" to "metric-$n")
            evidence += obj("type" to "metric_summary", "id" to "metric-$n", "scope" to obj("kind" to "transaction", "label" to label))
        }

        fun run(validity: RunValidity = RunValidity.VALID): JsonObject = synthesizeIncidents(validity, findings.toList(), evidence.toList())
    }

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        // The list of ADR 0029 (tests, criterion 3), written here independently of tools/verify_slice0.py; the test compares both.
        private const val CAUSAL_PATTERN =
            "из-за|вследствие|в результате|потому|поэтому|причин|виновн|вызва|вызыв|прив[её]л|привод|привед|привест|" +
                "обусловл|корнев|следстви|благодаря|ответственн|влия|так как|ввиду|в связи с|объясн|" +
                "\\bbecause\\b|\\bcaus\\w*|\\bdue to\\b|\\bowing to\\b|\\broot cause\\b|\\bleads? to\\b|\\bled to\\b|" +
                "\\bresult(?:s|ed|ing)? (?:of|in|from)\\b|\\bresponsib\\w*|\\bculprit\\b|\\bblame\\b|\\btrigger\\w*|\\bexplain\\w*"
        private val CAUSAL = Regex(CAUSAL_PATTERN, RegexOption.IGNORE_CASE)
        private val FORBIDDEN_SAMPLES =
            listOf(
                "из-за",
                "вследствие",
                "в результате",
                "потому",
                "поэтому",
                "причина",
                "виновник",
                "вызвало",
                "вызывает",
                "привод",
                "привело",
                "привести",
                "привёл",
                "обусловлено",
                "корневая",
                "следствие",
                "благодаря",
                "ответственный",
                "влияние",
                "так как",
                "ввиду",
                "в связи с",
                "объясняет",
                "because",
                "cause",
                "caused",
                "causes",
                "due to",
                "owing to",
                "root cause",
                "lead to",
                "leads to",
                "led to",
                "result of",
                "results in",
                "resulted from",
                "resulting in",
                "responsible",
                "responsibility",
                "culprit",
                "blame",
                "trigger",
                "triggered",
                "explain",
                "explains",
            )

        private val FIXTURES: Path = Path.of("fixtures/incidents")

        private fun fixtureCases(): List<Path> =
            Files.list(FIXTURES).use { stream -> stream.filter { Files.isDirectory(it) }.sorted().toList() }

        private fun expectedPath(case: Path): Path =
            if (case.name.startsWith("contract-")) {
                Path.of("docs/contracts/incident/v1/examples/valid/${case.name.removePrefix("contract-")}.json")
            } else {
                case.resolve("expected.json")
            }

        private fun el(value: Any?): JsonElement =
            when (value) {
                null -> JsonNull
                is JsonElement -> value
                is String -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                is List<*> -> JsonArray(value.map { el(it) })
                else -> error("unsupported ${value::class}")
            }

        fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { it.first to el(it.second) })

        fun resourceCheck(
            id: String,
            window: String,
            series: String,
            status: String,
            effect: String = "sla",
            reason: String? = null,
        ): JsonObject =
            obj(
                "type" to "resource_policy_check",
                "id" to id,
                "window_id" to window,
                "rule_id" to "r",
                "series_id" to series,
                "effect" to effect,
                "status" to status,
                "reason" to reason,
            )

        fun resourceSummary(
            id: String,
            window: String,
            series: String,
            entity: String,
            role: String,
        ): JsonObject =
            obj(
                "type" to "resource_summary",
                "id" to id,
                "window_id" to window,
                "series_id" to series,
                "entity" to entity,
                "role" to role,
            )

        fun anomalyCheck(
            id: String,
            window: String,
            status: String,
            reasons: List<String> = emptyList(),
        ): JsonObject = obj("type" to "anomaly_check", "id" to id, "window_id" to window, "status" to status, "reasons" to reasons)

        fun trendCheck(
            id: String,
            window: String,
            status: String,
            reasons: List<String> = emptyList(),
        ): JsonObject = obj("type" to "trend_check", "id" to id, "window_id" to window, "status" to status, "reasons" to reasons)

        private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

        private fun JsonObject.ids(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

        private fun JsonObject.items(): List<JsonObject> = getValue("items").jsonArray.map { it.jsonObject }

        private fun JsonObject.family(): String =
            getValue("grouping")
                .jsonObject
                .getValue("key")
                .jsonObject
                .str("family")

        private fun JsonObject.name(): String =
            getValue("scope")
                .jsonObject
                .let { scope -> scope["label"] ?: scope["entity"] }!!
                .jsonPrimitive.content

        private fun JsonObject.renderedTexts(): List<String> =
            listOf(str("title"), str("summary")) +
                getValue("negative_evidence").jsonArray.map { it.jsonObject.str("text") } +
                getValue("next_checks").jsonArray.map { it.jsonObject.str("text") }

        private fun JsonObject.assertReferencesResolve(
            findings: List<JsonObject>,
            evidence: List<JsonObject>,
        ) {
            val findingIds = findings.map { it.str("id") }.toSet()
            val evidenceIds = evidence.map { it.str("id") }.toSet()
            for (item in items()) {
                assertTrue(item.getValue("finding_ids").jsonArray.isNotEmpty())
                assertTrue(item.getValue("evidence_ids").jsonArray.isNotEmpty())
                item.ids("finding_ids").forEach { assertTrue(it in findingIds, it) }
                item.ids("evidence_ids").forEach { assertTrue(it in evidenceIds, it) }
                for (entry in item.getValue("negative_evidence").jsonArray + item.getValue("next_checks").jsonArray) {
                    entry.jsonObject.ids("evidence_ids").forEach { assertTrue(it in evidenceIds, it) }
                }
            }
        }

        // ----------------------------------------------------------------------------- generated inputs (LTV_UPDATE_INCIDENTS=1)

        private fun generatedCases(): Map<String, Pair<RunValidity, Case>> {
            val cases = linkedMapOf<String, Pair<RunValidity, Case>>()
            for ((name, groups) in listOf("limit-twenty-groups" to 20, "limit-seventy-groups" to 70)) {
                val case = Case()
                repeat(groups) { index ->
                    if (index % 2 == 0) {
                        case.tx("t$index", "steady", "tx-%02d".format(index))
                    } else {
                        case.res("r$index", "steady", "host-%02d".format(index), 1_000L + index, 1_100L + index)
                    }
                }
                case.evidence += obj("type" to "resource_binding", "id" to "resource-binding")
                cases[name] = RunValidity.VALID to case
            }
            cases["names-long-and-hostile"] =
                RunValidity.VALID to
                Case().apply {
                    tx("long", "steady", "я".repeat(2048))
                    tx("hostile", "steady", "tab\tname-" + "😀".repeat(80) + "-\u0001end")
                    tx("cause", "steady", "cause-list")
                    tx("braces", "steady", "{n}{window_id}")
                    res("astral", "steady", "хост-😀", 1_000, 1_100)
                    res("because", "steady", "because-host", 1_000, 1_100)
                    res("wide", "steady", "e".repeat(128), 1_200, 1_300)
                    evidence += obj("type" to "resource_binding", "id" to "resource-binding")
                }
            cases["window-without-snapshot"] =
                RunValidity.VALID to
                Case().apply {
                    window("steady", 1_767_225_610_000, 1_767_225_640_000)
                    tx("p95", "steady", "create-payment")
                    tx("err", "steady", "create-payment")
                    tx("other", "steady", "search")
                    evidence +=
                        obj(
                            "type" to "policy_check",
                            "id" to "pc-ok-1",
                            "window_id" to "steady",
                            "status" to "PASS",
                            "scope" to obj("kind" to "overall"),
                        )
                }
            cases["clusters-and-links"] =
                RunValidity.VALID to
                Case().apply {
                    res("a1", "steady", "host-a", 1_000, 1_010)
                    res("a2", "steady", "host-a", 1_010, 1_020)
                    res("a3", "steady", "host-a", 1_020, 1_030)
                    res("a4", "steady", "host-a", 1_100, 1_110, effect = "diagnostic")
                    res("b1", "steady", "host-b", 1_005, 1_015, presumed = true)
                    anomaly("b2", "steady", "host-b", 1_050, 1_060)
                    trend("c1", "steady", "overall", 1_000, 1_900)
                    tx("t1", "steady", "login")
                    evidence += obj("type" to "resource_binding", "id" to "resource-binding")
                    evidence += resourceCheck("rpc-ok-d", "steady", "s-d", "PASS")
                    evidence += resourceSummary("rs-d", "steady", "s-d", "host-d", "system")
                    evidence += resourceCheck("rpc-ok-g", "steady", "s-g", "PASS")
                    evidence += resourceSummary("rs-g", "steady", "s-g", "gen-1", "generator")
                    evidence += resourceCheck("rpc-nv", "steady", "s-n", "NO_VERDICT", reason = "MISSING_RESOURCE_CELLS")
                    evidence += anomalyCheck("ac-ok", "steady", "NO_MATERIAL_CHANGE")
                    evidence += trendCheck("tc-ok", "steady", "NO_MATERIAL_TREND")
                }
            cases["overlapping-resources"] =
                RunValidity.VALID to
                Case().apply {
                    res("a1", "steady", "host-a", 1_000, 1_100)
                    res("a2", "steady", "host-a", 1_080, 1_120)
                    res("b1", "steady", "host-b", 1_050, 1_150)
                    evidence += obj("type" to "resource_binding", "id" to "resource-binding")
                    evidence += resourceCheck("rpc-ok-c", "steady", "s-c", "PASS")
                    evidence += resourceSummary("rs-c", "steady", "s-c", "host-c", "system")
                    evidence += resourceCheck("rpc-nv", "steady", "s-n", "NO_VERDICT", reason = "MISSING_RESOURCE_CELLS")
                    evidence += anomalyCheck("ac-ok", "steady", "NO_MATERIAL_CHANGE")
                }
            cases["capacity-step-mode"] =
                RunValidity.VALID to
                Case().apply {
                    tx("1", "steady", "login")
                    findings +=
                        obj("type" to "capacity_stage_violation", "id" to "cap-1", "window_id" to "stage-1", "evidence_id" to "cap-e1")
                    evidence += obj("type" to "capacity_knee_diagnostic", "id" to "cap-e1", "window_id" to "stage-1")
                    findings += obj("type" to "correlation_candidate", "id" to "cc-1", "window_id" to "steady", "evidence_id" to "pair-1")
                    evidence += obj("type" to "correlation_pair", "id" to "pair-1", "window_id" to "steady")
                    evidence += obj("type" to "resource_binding", "id" to "resource-binding")
                }
            return cases
        }

        private fun writeGeneratedInputs() {
            for ((name, run) in generatedCases()) {
                val (validity, case) = run
                val directory = FIXTURES.resolve(name)
                Files.createDirectories(directory)
                val input =
                    JsonObject(
                        mapOf(
                            "run_validity" to JsonPrimitive(validity.name),
                            "findings" to JsonArray(case.findings),
                            "evidence" to JsonArray(case.evidence),
                        ),
                    )
                Files.writeString(directory.resolve("input.json"), PRETTY.encodeToString(JsonElement.serializer(), input) + "\n")
            }
        }
    }
}

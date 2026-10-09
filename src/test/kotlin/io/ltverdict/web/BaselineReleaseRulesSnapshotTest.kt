package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.MAX_VERIFIED_RESULT_BYTES
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * W2.2 PR 2 characterization of the baseline and release RULES (request parsing, normalization, eligibility, facts, views and
 * the failures they raise). The route snapshot of [LocalApiSnapshotTest] fixes the routes; this one fixes what the rules answer
 * for requests that the route sweep does not send. The expected lines were captured from `origin/main` before the rules moved
 * to the core. Regenerate only on purpose with `LTV_UPDATE_RULES_SNAPSHOT=1` (a change here is a change of the HTTP contract).
 */
class BaselineReleaseRulesSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val snapshotFile = Path.of("fixtures/http-layer/rules.txt")

    @Test
    fun `baseline and release rules answer as before they moved to the core`() {
        val lines = mutableListOf<String>()
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    Sweep(server.origin, store, lines).run()
                }
            }
        }
        val text = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("LTV_UPDATE_RULES_SNAPSHOT") == "1") Files.writeString(snapshotFile, text)
        val expected = Files.readString(snapshotFile).replace("\r\n", "\n").lines()
        val actual = text.lines()
        assertEquals(
            emptyList<Pair<String, String>>(),
            expected.zip(actual).filter { (a, b) ->
                a != b
            },
            "answers differ from the snapshot",
        )
        assertEquals(expected.size, actual.size)
    }

    private class Sweep(
        origin: String,
        private val store: RunBundleStore,
        private val lines: MutableList<String>,
    ) {
        private val api = Client(origin)
        private val json = "application/json"

        fun run() {
            api.bootstrap()
            val runs = (0 until 6).map { index -> accept("rules-$index", 100 + index * 10) }
            val pass = runs.map { it to analyze(it, PERMISSIVE) }
            val fail = runs[0] to analyze(runs[0], FAILING)
            val noPolicy = runs[1] to analyze(runs[1], null)
            val small = runs[2] to analyze(runs[2], SMALL_SAMPLE)
            baselineSelection(pass, fail, noPolicy, small)
            baselineQueries(pass)
            releases(runs[0])
        }

        private fun baselineSelection(
            pass: List<Pair<String, String>>,
            fail: Pair<String, String>,
            noPolicy: Pair<String, String>,
            small: Pair<String, String>,
        ) {
            fun manual(
                reference: String,
                series: String = "release",
            ) = """{"mode":"manual","series":${JsonPrimitive(series)},"reference":$reference}"""

            fun ref(pair: Pair<String, String>) = """{"run_id":"${pair.first}","analysis_id":"${pair.second}"}"""

            fun statistical(
                candidates: List<String>,
                comparable: String = "true",
                series: String = "release",
            ) = """{"mode":"statistical","series":${JsonPrimitive(
                series,
            )},"candidates":[${candidates.joinToString(",")}],"comparable":$comparable}"""

            val bodies = linkedMapOf<String, String>()
            bodies["no mode"] = """{"series":"release"}"""
            bodies["mode number"] = """{"mode":1,"series":"release"}"""
            bodies["unknown mode"] = """{"mode":"other","series":"release"}"""
            bodies["series blank"] = manual(ref(pass[0]), series = "   ")
            bodies["series 129 bytes"] = manual(ref(pass[0]), series = "s".repeat(129))
            bodies["series control"] = manual(ref(pass[0]), series = "a\u0007b")
            bodies["series missing"] = """{"mode":"manual","reference":${ref(pass[0])}}"""
            bodies["manual extra key"] = """{"mode":"manual","series":"r","reference":${ref(pass[0])},"x":1}"""
            bodies["manual reference not object"] = manual("1")
            bodies["manual reference extra key"] = manual("""{"run_id":"${pass[0].first}","analysis_id":"${pass[0].second}","x":1}""")
            bodies["manual reference bad run"] = manual("""{"run_id":"run","analysis_id":"${pass[0].second}"}""")
            bodies["manual reference bad analysis"] = manual("""{"run_id":"${pass[0].first}","analysis_id":"${"A".repeat(64)}"}""")
            bodies["manual unknown analysis"] = manual("""{"run_id":"${pass[0].first}","analysis_id":"${"b".repeat(64)}"}""")
            bodies["manual unknown run"] = manual("""{"run_id":"jmeter_jtl_csv-${"9".repeat(64)}","analysis_id":"${"b".repeat(64)}"}""")
            bodies["manual FAIL"] = manual(ref(fail))
            bodies["manual NO_POLICY"] = manual(ref(noPolicy))
            bodies["statistical extra key"] = """{"mode":"statistical","series":"r","candidates":[],"comparable":true,"x":1}"""
            bodies["statistical comparable string"] = statistical(emptyList(), "\"true\"")
            bodies["statistical comparable null"] = statistical(emptyList(), "null")
            bodies["statistical comparable false"] = statistical(emptyList(), "false")
            bodies["statistical candidates object"] = """{"mode":"statistical","series":"r","candidates":{},"comparable":true}"""
            bodies["statistical no candidates"] = statistical(emptyList())
            bodies["statistical two"] = statistical(pass.take(2).map(::ref))
            bodies["statistical 21 equal"] = statistical(List(21) { ref(pass[0]) })
            bodies["statistical 3 with a number"] = statistical(listOf(ref(pass[0]), ref(pass[1]), "7"))
            bodies["statistical 3 with a bad reference"] = statistical(listOf(ref(pass[0]), ref(pass[1]), """{"run_id":"x"}"""))
            bodies["statistical duplicate run"] = statistical(listOf(ref(pass[0]), ref(pass[1]), ref(fail)))
            bodies["statistical unknown candidate"] =
                statistical(listOf(ref(pass[0]), ref(pass[1]), """{"run_id":"${pass[2].first}","analysis_id":"${"c".repeat(64)}"}"""))
            bodies["statistical FAIL last"] = statistical(listOf(ref(pass[1]), ref(pass[2]), ref(fail.first to fail.second)))
            bodies["statistical NO_POLICY first"] = statistical(listOf(ref(noPolicy), ref(pass[2]), ref(pass[3])))
            val unknownOnRun3 = """{"run_id":"${pass[3].first}","analysis_id":"${"d".repeat(64)}"}"""
            bodies["statistical FAIL, NO_POLICY, PASS"] = statistical(listOf(ref(fail), ref(noPolicy), ref(pass[3])))
            bodies["statistical NO_POLICY, FAIL, PASS"] = statistical(listOf(ref(noPolicy), ref(fail), ref(pass[3])))
            bodies["statistical FAIL, NO_POLICY, unknown"] = statistical(listOf(ref(fail), ref(noPolicy), unknownOnRun3))
            bodies["statistical unknown, FAIL, NO_POLICY"] = statistical(listOf(unknownOnRun3, ref(fail), ref(noPolicy)))
            bodies["statistical duplicate run and FAIL"] = statistical(listOf(ref(fail), ref(pass[0]), ref(noPolicy)))
            for ((name, body) in bodies) rec("POST /api/baseline: $name", api.post("/api/baseline", json, body.toByteArray()))
            val badArm = synthetic(pass[4].first, "bad-arm", "2026-01-01T00:00:00Z", "PASS", null, rawArm = JsonPrimitive(5))
            val nullArm = synthetic(pass[4].first, "null-arm", "2026-01-01T00:00:00Z", "PASS", null, rawArm = JsonNull)
            rec(
                "POST /api/baseline: manual with a number as arm",
                api.post("/api/baseline", json, manual(ref(pass[4].first to badArm)).toByteArray()),
            )
            rec(
                "POST /api/baseline: manual with a null arm",
                api.post("/api/baseline", json, manual(ref(pass[4].first to nullArm), "null-arm").toByteArray()),
            )
            val badArmPath = "/api/runs/${pass[4].first}/analyses/$badArm"
            rec("GET comparison: number as arm", api.get("$badArmPath/comparison?series=release"))
            rec("GET baseline-conditions: number as arm", api.get("$badArmPath/baseline-conditions?series=release"))
            rec("POST /api/baseline: wrong content type", api.post("/api/baseline", "text/plain", "{}".toByteArray()))
            rec("POST /api/baseline: array", api.post("/api/baseline", json, "[]".toByteArray()))
            rec("POST /api/baseline: depth 9", api.post("/api/baseline", json, ("[".repeat(9) + "]".repeat(9)).toByteArray()))
            rec("POST /api/baseline: invalid UTF-8", api.post("/api/baseline", json, byteArrayOf(0x7b, 0xc3.toByte(), 0x7d)))
            rec("POST /api/baseline: 16 KiB + 1", api.post("/api/baseline", json, ByteArray(16_385) { ' '.code.toByte() }))

            // oversized result: refused as a candidate in either mode
            val bigRun = pass[3].first
            val identity = """{"run_id":"$bigRun","tag":"too-large"}""".toByteArray()
            val big = sha256Hex(identity)
            store.writeAnalysisAtomically(bigRun, big) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                RandomAccessFile(
                    staging.resolve("analysis-result.json").toFile(),
                    "rw",
                ).use { it.setLength(MAX_VERIFIED_RESULT_BYTES + 1L) }
            }
            rec("POST /api/baseline: manual too large", api.post("/api/baseline", json, manual(ref(bigRun to big)).toByteArray()))
            rec(
                "POST /api/baseline: statistical too large",
                api.post(
                    "/api/baseline",
                    json,
                    statistical(
                        listOf(
                            ref(pass[0]),
                            ref(pass[1]),
                            ref(
                                bigRun to big,
                            ),
                        ),
                    ).toByteArray(),
                ),
            )

            // successful selections, then what the stored selection answers
            rec(
                "POST /api/baseline: manual padded series",
                api.post("/api/baseline", json, manual(ref(pass[0]), series = "  reĺ  ").toByteArray()),
            )
            rec("GET /api/baseline: after manual", api.get("/api/baseline"))
            rec(
                "POST /api/baseline: manual small sample",
                api.post("/api/baseline", json, manual(ref(small), series = "small").toByteArray()),
            )
            for (count in listOf(3, 4, 6)) {
                rec(
                    "POST /api/baseline: statistical $count",
                    api.post("/api/baseline", json, statistical(pass.take(count).map(::ref), series = "stat-$count").toByteArray()),
                )
            }
            rec(
                "POST /api/baseline: statistical with small sample",
                api.post(
                    "/api/baseline",
                    json,
                    statistical(listOf(ref(pass[0]), ref(pass[1]), ref(small)), series = "stat-small").toByteArray(),
                ),
            )
            rec(
                "POST /api/baseline: statistical reversed",
                api.post("/api/baseline", json, statistical(pass.take(4).reversed().map(::ref), series = "stat-rev").toByteArray()),
            )
            rec("GET /api/baseline: all slots", api.get("/api/baseline"))
        }

        private fun baselineQueries(pass: List<Pair<String, String>>) {
            val analysis = "/api/runs/${pass[5].first}/analyses/${pass[5].second}"
            for (series in listOf("stat-3", "%20stat-3%20", "stat-4", "release", "missing")) {
                rec("GET comparison?series=$series", api.get("$analysis/comparison?series=$series"))
                rec("GET baseline-conditions?series=$series", api.get("$analysis/baseline-conditions?series=$series"))
            }
            rec("GET comparison (no series)", api.get(analysis + "/comparison"))
            rec("GET comparison (control series)", api.get("$analysis/comparison?series=a%07b"))
            rec("GET comparison (long series)", api.get("$analysis/comparison?series=" + "s".repeat(129)))
            val windows = "baseline_window=a&current_window=b&series=stat-3"
            val queries =
                listOf(
                    "only baseline window" to "baseline_window=a&series=stat-3",
                    "only current window" to "current_window=b&series=stat-3",
                    "thresholds without windows" to "min_change_percent=5&series=stat-3",
                    "error threshold without windows" to "min_error_rate_delta=0.1&series=stat-3",
                    "blank window" to "baseline_window=%20&current_window=b&series=stat-3",
                    "long window" to "baseline_window=${"w".repeat(129)}&current_window=b&series=stat-3",
                    "control window" to "baseline_window=a%07&current_window=b&series=stat-3",
                    "defaults" to windows,
                    "change 0" to "$windows&min_change_percent=0",
                    "change 1000" to "$windows&min_change_percent=1000",
                    "change 1001" to "$windows&min_change_percent=1001",
                    "change 12.5" to "$windows&min_change_percent=12.5",
                    "change 1e2" to "$windows&min_change_percent=1e2",
                    "change 1e999" to "$windows&min_change_percent=1e999",
                    "change abc" to "$windows&min_change_percent=abc",
                    "change negative" to "$windows&min_change_percent=-1",
                    "change long" to "$windows&min_change_percent=" + "1".repeat(65),
                    "change precision 33" to "$windows&min_change_percent=" + "1".repeat(33),
                    "error 1" to "$windows&min_error_rate_delta=1",
                    "error 1.1" to "$windows&min_error_rate_delta=1.1",
                    "error 0.0005" to "$windows&min_error_rate_delta=0.0005",
                    "error scale 13" to "$windows&min_error_rate_delta=0.0000000000001",
                )
            for ((name, query) in queries) {
                rec("GET comparison: $name", api.get("$analysis/comparison?$query"))
                rec(
                    "GET baseline-conditions: $name",
                    api.get("$analysis/baseline-conditions?${query.replace(Regex("&?min_[a-z_]+=[^&]*"), "")}"),
                )
            }
            for (
            (name, body) in
            listOf(
                "CONFIRMED" to """{"decision":"CONFIRMED"}""",
                "NOT_CONFIRMED" to """{"decision":"NOT_CONFIRMED"}""",
                "UNKNOWN" to """{"decision":"UNKNOWN"}""",
                "lowercase" to """{"decision":"confirmed"}""",
                "number" to """{"decision":1}""",
                "extra key" to """{"decision":"CONFIRMED","x":1}""",
                "no decision" to "{}",
            )
            ) {
                rec("POST baseline-conditions: $name", api.post("$analysis/baseline-conditions?series=stat-3", json, body.toByteArray()))
            }
            rec(
                "POST baseline-conditions: windows",
                api.post("$analysis/baseline-conditions?$windows", json, """{"decision":"CONFIRMED"}""".toByteArray()),
            )
            rec("GET baseline-conditions: stored", api.get("$analysis/baseline-conditions?series=stat-3"))
            rec("GET comparison: confirmed", api.get("$analysis/comparison?series=stat-3"))
            rec("GET comparison: windows after decisions", api.get("$analysis/comparison?$windows"))

            // the series of a release wins over the query, a contradicting query is refused
            val synthetic = synthetic(pass[5].first, "release-member", "2026-01-02T00:00:00Z", "PASS", null)
            val created = createRelease("/api/releases", json, releaseBody(pass[5].first, listOf(synthetic), series = "stat-3"))
            rec("POST /api/releases: series of a baseline", created)
            val member = "/api/runs/${pass[5].first}/analyses/$synthetic"
            rec("GET comparison: query contradicts release series", api.get("$member/comparison?series=stat-4"))
            rec("GET comparison: release series without query", api.get("$member/comparison"))
            rec("GET baseline-conditions: query contradicts release series", api.get("$member/baseline-conditions?series=stat-4"))

            for (
            (name, query) in
            listOf(
                "arm without series" to "arm=x",
                "blank arm" to "series=stat-4&arm=%20%20",
                "control arm" to "series=stat-4&arm=a%07",
                "long arm" to "series=stat-4&arm=" + "a".repeat(129),
                "blank series" to "series=%20",
                "unknown slot" to "series=nothing",
                "padded series" to "series=%20stat-4%20",
                "repeated series" to "series=a&series=b",
                "unknown parameter" to "x=1",
                "arm that does not match" to "series=stat-6&arm=blue",
            )
            ) {
                rec("DELETE /api/baseline: $name", api.delete("/api/baseline?$query"))
            }
            rec("GET /api/baseline: after deletes", api.get("/api/baseline"))
            rec("DELETE /api/baseline: legacy", api.delete("/api/baseline"))
            rec("GET /api/baseline: final", api.get("/api/baseline"))
        }

        private fun releases(runId: String) {
            val a = synthetic(runId, "a", "2026-01-01T00:00:00Z", "PASS", null)
            val b = synthetic(runId, "b", "2026-01-01T00:00:00Z", "FAIL", null)
            val c = synthetic(runId, "c", "2026-01-01T00:00:00Z", "NO_VERDICT", "blue")
            // Release ids start with the start of the run: releases that are kept get distinct starts, so the listing order is stable.
            val f1 = synthetic(runId, "f1", "2026-01-01T00:00:01Z", "FAIL", null)
            val armA = synthetic(runId, "armA", "2026-01-01T00:00:02Z", "PASS", "green")
            val armB = synthetic(runId, "armB", "2026-01-01T00:00:02Z", "PASS", "blue")
            val a2 = synthetic(runId, "a2", "2026-01-01T00:00:00Z", "PASS", null)
            val blue = synthetic(runId, "blue", "2026-01-01T00:00:00Z", "PASS", "blue")
            val blue2 = synthetic(runId, "blue2", "2026-01-01T00:00:00Z", "PASS", "blue")
            val noMeta = synthetic(runId, "no-meta", null, "PASS", null)
            val otherStart = synthetic(runId, "other-start", "2026-01-01T00:00:01Z", "PASS", null)
            val badStart = synthetic(runId, "bad-start", "yesterday", "PASS", null)
            val foreign =
                synthetic(runId, "foreign", "2026-01-01T00:00:00Z", "PASS", null, resultRunId = "jmeter_jtl_csv-${"3".repeat(64)}")
            val foreignMeta =
                synthetic(runId, "foreign-meta", "2026-01-01T00:00:00Z", "PASS", null, metadataRunId = "jmeter_jtl_csv-${"4".repeat(64)}")
            val gone = synthetic(runId, "gone", "2026-01-01T00:00:03Z", "PASS", "gone")
            val damaged = synthetic(runId, "damaged", "2026-01-01T00:00:04Z", "PASS", "damaged")
            val badFacts = synthetic(runId, "bad-facts", "2026-01-01T00:00:00Z", "PASS", null, coverageStatus = null)

            val good = Json.parseToJsonElement(String(releaseBody(runId, listOf(a)), UTF_8)).jsonObject
            val bodies = linkedMapOf<String, JsonObject>()
            bodies["extra key"] = JsonObject(good + ("extra" to JsonNull))
            bodies["missing notes"] = JsonObject(good - "notes")
            bodies["missing profile"] = JsonObject(good - "profile")
            bodies["series number"] = JsonObject(good + ("series" to JsonPrimitive(1)))
            bodies["series blank"] = JsonObject(good + ("series" to JsonPrimitive("  ")))
            bodies["series control"] = JsonObject(good + ("series" to JsonPrimitive("a\u0007")))
            bodies["label blank"] = JsonObject(good + ("label" to JsonPrimitive(" \n ")))
            bodies["label 129 bytes"] = JsonObject(good + ("label" to JsonPrimitive("x".repeat(129))))
            bodies["label 128 multibyte"] =
                JsonObject(good + ("label" to JsonPrimitive("é".repeat(64))) + ("analyses" to analyses(listOf("f".repeat(64)))))
            bodies["label 129 multibyte"] = JsonObject(good + ("label" to JsonPrimitive("é".repeat(65))))
            bodies["label newline"] = JsonObject(good + ("label" to JsonPrimitive("a\nb")))
            bodies["run id bad"] = JsonObject(good + ("run_id" to JsonPrimitive("run")))
            bodies["run id number"] = JsonObject(good + ("run_id" to JsonPrimitive(1)))
            bodies["analyses object"] = JsonObject(good + ("analyses" to buildJsonObject { }))
            bodies["analyses empty"] = JsonObject(good + ("analyses" to JsonArray(emptyList())))
            bodies["analyses nine"] = JsonObject(good + ("analyses" to analyses((1..9).map { "%064x".format(it) })))
            bodies["analyses eight"] = JsonObject(good + ("analyses" to analyses((1..8).map { "%064x".format(it) })))
            bodies["analyses duplicate"] = JsonObject(good + ("analyses" to analyses(listOf("a".repeat(64), "a".repeat(64)))))
            bodies["analyses entry string"] = JsonObject(good + ("analyses" to JsonArray(listOf(JsonPrimitive("x")))))
            bodies["analyses entry extra"] =
                JsonObject(
                    good + (
                        "analyses" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("analysis_id", "a".repeat(64))
                                        put("arm", "x")
                                    },
                                ),
                            )
                    ),
                )
            bodies["analyses entry bad id"] = JsonObject(good + ("analyses" to analyses(listOf("A".repeat(64)))))
            bodies["profile string"] = JsonObject(good + ("profile" to JsonPrimitive("x")))
            bodies["profile extra key"] = JsonObject(good + ("profile" to buildJsonObject { put("x", "y") }))
            bodies["profile number value"] = JsonObject(good + ("profile" to profile("scenario_mix" to JsonPrimitive(1))))
            bodies["profile object value"] = JsonObject(good + ("profile" to profile("scenario_mix" to buildJsonObject { })))
            bodies["profile control value"] = JsonObject(good + ("profile" to profile("scenario_mix" to JsonPrimitive("a\u0007"))))
            bodies["profile long value"] = JsonObject(good + ("profile" to profile("scenario_mix" to JsonPrimitive("v".repeat(129)))))
            bodies["notes number"] = JsonObject(good + ("notes" to JsonPrimitive(1)))
            bodies["notes object"] = JsonObject(good + ("notes" to buildJsonObject { }))
            bodies["notes control"] = JsonObject(good + ("notes" to JsonPrimitive("a\u0007")))
            bodies["notes 1025 bytes"] = JsonObject(good + ("notes" to JsonPrimitive("n".repeat(1025))))
            bodies["unknown analysis"] = JsonObject(good + ("analyses" to analyses(listOf("f".repeat(64)))))
            for ((name, body) in bodies) {
                rec(
                    "POST /api/releases invalid: $name",
                    api.post("/api/releases", json, body.toString().toByteArray()),
                )
            }
            rec("POST /api/releases: wrong content type", api.post("/api/releases", "text/plain", good.toString().toByteArray()))
            rec("POST /api/releases: 16 KiB + 1", api.post("/api/releases", json, ByteArray(16_385) { ' '.code.toByte() }))
            rec("POST /api/releases: array", api.post("/api/releases", json, "[]".toByteArray()))

            // facts of the analyses
            val facts =
                linkedMapOf(
                    "no run metadata" to listOf(noMeta),
                    "bad start" to listOf(badStart),
                    "start differs" to listOf(a, otherStart),
                    "foreign result" to listOf(foreign),
                    "foreign metadata" to listOf(foreignMeta),
                    "two without arm" to listOf(a, b),
                    "repeated arm" to listOf(blue, blue2),
                    "unmarked next to marked" to listOf(blue, a),
                    "invalid facts" to listOf(badFacts),
                )
            for ((name, ids) in facts) rec("POST /api/releases facts: $name", api.post("/api/releases", json, releaseBody(runId, ids)))

            val tampered = Path.of(store.readAnalysis(runId, c)!!.path.toString(), "analysis-result.json")
            Files.write(
                tampered,
                Files
                    .readAllBytes(tampered)
                    .decodeToString()
                    .replace("\"NO_VERDICT\"", "\"PASS\"")
                    .toByteArray(),
            )
            rec("POST /api/releases facts: tampered", api.post("/api/releases", json, releaseBody(runId, listOf(c))))
            val identity = """{"run_id":"$runId","tag":"too-large"}""".toByteArray()
            val oversized = sha256Hex(identity)
            store.writeAnalysisAtomically(runId, oversized) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                RandomAccessFile(
                    staging.resolve("analysis-result.json").toFile(),
                    "rw",
                ).use { it.setLength(MAX_VERIFIED_RESULT_BYTES + 1L) }
            }
            rec("POST /api/releases facts: oversized", api.post("/api/releases", json, releaseBody(runId, listOf(oversized))))
            rec(
                "POST /api/releases facts: unknown run",
                api.post("/api/releases", json, releaseBody("jmeter_jtl_csv-${"2".repeat(64)}", listOf(a))),
            )

            rec(
                "POST /api/releases facts: bad metadata then unknown",
                api.post("/api/releases", json, releaseBody(runId, listOf(noMeta, "f".repeat(64)))),
            )
            rec(
                "POST /api/releases facts: unknown then bad metadata",
                api.post("/api/releases", json, releaseBody(runId, listOf("f".repeat(64), noMeta))),
            )
            rec(
                "POST /api/releases facts: ok, foreign, unknown",
                api.post("/api/releases", json, releaseBody(runId, listOf(a, foreign, "f".repeat(64)))),
            )
            rec(
                "POST /api/releases facts: start differs then unknown",
                api.post("/api/releases", json, releaseBody(runId, listOf(a, otherStart, "f".repeat(64)))),
            )
            rec(
                "POST /api/releases facts: arm conflict is checked after all reads",
                api.post("/api/releases", json, releaseBody(runId, listOf(a, b, "f".repeat(64)))),
            )
            rec(
                "POST /api/releases: malformed field and unknown run",
                api.post("/api/releases", json, releaseBody("jmeter_jtl_csv-${"2".repeat(64)}", listOf(a), label = "a\u0007")),
            )

            // creation, normalization and the view
            val profileBody =
                profile(
                    "scenario_mix" to JsonPrimitive("  1.2  "),
                    "environment_dataset" to JsonPrimitive("é"),
                    "load_model" to JsonNull,
                )
            val created = linkedMapOf<String, HttpResponse<String>>()
            created["plain"] =
                createRelease("/api/releases", json, releaseBody(runId, listOf(a), series = "checkout", label = "  1.0  "))
            created["fail"] =
                createRelease("/api/releases", json, releaseBody(runId, listOf(f1), series = "checkout", label = "2.0"))
            created["two arms"] =
                createRelease("/api/releases", json, releaseBody(runId, listOf(armA, armB), series = "arms", label = "arms"))
            created["profile and notes"] =
                createRelease(
                    "/api/releases",
                    json,
                    releaseBody(
                        runId,
                        listOf(gone),
                        series = "  Seŕies  ",
                        label = "profiled",
                        profile = profileBody,
                        notes = JsonPrimitive("  line1\r\nline2\n  "),
                    ),
                )
            created["empty profile"] =
                createRelease(
                    "/api/releases",
                    json,
                    releaseBody(
                        runId,
                        listOf(damaged),
                        series = "empty",
                        label = "empty",
                        profile = profile("load_model" to JsonPrimitive("  ")),
                        notes = JsonPrimitive("  "),
                    ),
                )
            for ((name, response) in created) rec("POST /api/releases: $name", response)
            rec(
                "POST /api/releases: analysis already registered",
                api.post("/api/releases", json, releaseBody(runId, listOf(a), label = "again")),
            )
            val ids =
                created
                    .mapNotNull { (name, r) ->
                        (r.jsonObject()["release_id"] as? JsonPrimitive)?.content?.let { name to it }
                    }.toMap()

            // vanished and damaged analyses
            store
                .readAnalysis(runId, gone)!!
                .path
                .toFile()
                .deleteRecursively()
            val damagedResult = store.readAnalysis(runId, damaged)!!.path.resolve("analysis-result.json")
            Files.write(damagedResult, Files.readAllBytes(damagedResult) + byteArrayOf(' '.code.toByte()))
            rec("GET /api/releases", api.get("/api/releases"))
            rec("GET /api/releases?series=checkout&limit=1", api.get("/api/releases?series=checkout&limit=1"))
            rec("GET /api/releases?series=%20checkout%20", api.get("/api/releases?series=%20checkout%20"))
            for (query in listOf(
                "series=a%07",
                "series=%20",
                "after=zz",
                "after=000000000000000-00000000",
                "limit=0",
                "limit=101",
                "x=1",
                "series=a&series=b",
            )) {
                rec("GET /api/releases?$query", api.get("/api/releases?$query"))
            }
            for ((name, id) in ids) rec("GET /api/releases/{id}: $name", api.get("/api/releases/$id"))
            for (id in listOf(
                "zz",
                "000000000000000-00000000",
                "0".repeat(15) + "-" + "0".repeat(8),
            )) {
                rec("GET /api/releases/$id", api.get("/api/releases/$id"))
            }
            rec("GET /api/releases/{id}?x=1", api.get("/api/releases/${ids.getValue("plain")}?x=1"))

            // replacement
            val plain = ids.getValue("plain")
            val replace = { id: String, body: JsonObject -> api.put("/api/releases/$id", json, body.toString().toByteArray()) }

            fun update(
                ids: List<String>,
                label: String = "1.0b",
                profile: JsonElement = JsonNull,
                notes: JsonElement = JsonNull,
            ) = buildJsonObject {
                put("label", label)
                put("analyses", analyses(ids))
                put("profile", profile)
                put("notes", notes)
            }
            val before = api.get("/api/releases/$plain").jsonObject()
            val firstUpdate = replace(plain, update(listOf(a), "  1.0b  ", notes = JsonPrimitive("n\r\nm")))
            rec("PUT /api/releases: label and notes", firstUpdate)
            val after = firstUpdate.jsonObject()
            for (key in listOf("release_id", "series", "run_id", "started_at", "created_at", "schema_version")) {
                check(before[key] == after[key]) { "$key changed: ${before[key]} -> ${after[key]}" }
            }
            check(after["label"] == JsonPrimitive("1.0b") && after["notes"] == JsonPrimitive("n\nm")) { "label and notes: $after" }
            check(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}(?:[.][0-9]{3})?Z").matches((after["updated_at"] as JsonPrimitive).content)) {
                "updated_at: $after"
            }
            check((after["started_at"] as JsonPrimitive).content == "2026-01-01T00:00:00Z") { "started_at: $after" }
            rec(
                "PUT /api/releases: malformed body and unknown id",
                replace("0".repeat(15) + "-" + "0".repeat(8), JsonObject(update(listOf(a)) - "notes")),
            )
            rec(
                "PUT /api/releases: unknown analysis and unknown id",
                replace("0".repeat(15) + "-" + "0".repeat(8), update(listOf("f".repeat(64)))),
            )
            rec("PUT /api/releases: unknown analysis", replace(plain, update(listOf("f".repeat(64)))))
            rec("PUT /api/releases: bad metadata then unknown", replace(plain, update(listOf(noMeta, "f".repeat(64)))))
            rec("PUT /api/releases: profile", replace(plain, update(listOf(a), profile = profile("scenario_mix" to JsonPrimitive("2")))))
            rec("PUT /api/releases: other analysis, same start", replace(plain, update(listOf(a2))))
            rec("PUT /api/releases: registered elsewhere", replace(plain, update(listOf(f1))))
            rec("PUT /api/releases: start differs", replace(plain, update(listOf(otherStart))))
            rec("PUT /api/releases: two without arms", replace(plain, update(listOf(a, b))))
            rec("PUT /api/releases: no metadata", replace(plain, update(listOf(noMeta))))
            rec("PUT /api/releases: extra key", replace(plain, JsonObject(update(listOf(a)) + ("series" to JsonPrimitive("x")))))
            rec("PUT /api/releases: missing key", replace(plain, JsonObject(update(listOf(a)) - "notes")))
            rec("PUT /api/releases: label blank", replace(plain, update(listOf(a), label = " ")))
            rec("PUT /api/releases: bad analyses", replace(plain, JsonObject(update(listOf(a)) + ("analyses" to JsonPrimitive(1)))))
            rec("PUT /api/releases: bad profile", replace(plain, JsonObject(update(listOf(a)) + ("profile" to JsonPrimitive(1)))))
            rec("PUT /api/releases: bad notes", replace(plain, JsonObject(update(listOf(a)) + ("notes" to JsonPrimitive(1)))))
            rec("PUT /api/releases: unknown id", replace("0".repeat(15) + "-" + "0".repeat(8), update(listOf(a))))
            rec("PUT /api/releases: invalid id", replace("zz", update(listOf(a))))
            rec("PUT /api/releases: wrong content type", api.put("/api/releases/$plain", "text/plain", "{}".toByteArray()))
            rec("GET /api/releases/{id}: after replacements", api.get("/api/releases/$plain"))
            for (id in ids.values) rec("DELETE /api/releases/{id}", api.delete("/api/releases/$id"))
            rec("DELETE /api/releases/{id}: invalid", api.delete("/api/releases/zz"))
            rec("GET /api/releases: empty", api.get("/api/releases"))
        }

        // Release ids start with the creation millisecond: a pause keeps the listing order the same on every run.
        private fun createRelease(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> = api.post(path, contentType, body).also { Thread.sleep(5) }

        private fun profile(vararg pairs: Pair<String, JsonElement>): JsonObject {
            val fields = listOf("scenario_mix", "environment_dataset", "load_model", "targets_stages", "pacing", "generator_limits")
            return JsonObject(fields.associateWith { name -> pairs.toMap()[name] ?: JsonNull })
        }

        private fun analyses(ids: List<String>) = JsonArray(ids.map { buildJsonObject { put("analysis_id", it) } })

        private fun releaseBody(
            runId: String,
            ids: List<String>,
            series: String = "checkout",
            label: String = "1.0",
            profile: JsonElement = JsonNull,
            notes: JsonElement = JsonNull,
        ): ByteArray =
            buildJsonObject {
                put("series", series)
                put("label", label)
                put("run_id", runId)
                put("analyses", analyses(ids))
                put("profile", profile)
                put("notes", notes)
            }.toString().toByteArray()

        private fun accept(
            name: String,
            elapsed: Int,
        ): String {
            val load = "timeStamp,elapsed,label,success\n1767225600000,$elapsed,checkout,true\n"
            return store.acceptInput(ByteArrayInputStream(load.toByteArray()), "$name.jtl").runId
        }

        private fun analyze(
            runId: String,
            policy: ByteArray?,
        ): String {
            val parts = mutableListOf(Part("run_id", runId.toByteArray()))
            if (policy != null) parts += Part("policy", policy, "policy.json", "application/json")
            val job =
                api
                    .multipart("/api/jobs", parts)
                    .jsonObject()
                    .getValue("job_id")
                    .jsonPrimitive.content
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val status = api.get("/api/jobs/$job").jsonObject()
                when (status.getValue("state").jsonPrimitive.content) {
                    "COMPLETE" -> return status.getValue("analysis_id").jsonPrimitive.content
                    "FAILED", "CANCELLED" -> fail("job ended: $status")
                }
                LockSupport.parkNanos(1_000_000)
            }
            return fail("job did not complete")
        }

        private fun synthetic(
            runId: String,
            tag: String,
            startedAt: String?,
            verdict: String,
            arm: String?,
            rawArm: JsonElement? = null,
            resultRunId: String = runId,
            metadataRunId: String = runId,
            coverageStatus: String? = "COMPLETE",
        ): String {
            val identity =
                canonicalJson(
                    buildJsonObject {
                        put("run_id", runId)
                        put("tag", tag)
                        put("policy_sha256", "a".repeat(64))
                        if (arm != null) put("resource_arm", arm)
                        if (rawArm != null) put("resource_arm", rawArm)
                    },
                )
            val analysisId = sha256Hex(identity)
            store.writeAnalysisAtomically(runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.write(
                    staging.resolve("analysis-result.json"),
                    canonicalJson(
                        buildJsonObject {
                            put("schema_version", "analysis-result.v1")
                            put("run_id", resultRunId)
                            put("run_validity", "VALID")
                            put("policy_verdict", verdict)
                            if (coverageStatus != null) {
                                put(
                                    "analysis_coverage",
                                    buildJsonObject {
                                        put("status", coverageStatus)
                                        put("reasons", JsonArray(emptyList()))
                                    },
                                )
                            }
                        },
                    ),
                )
                if (startedAt != null) {
                    Files.write(
                        staging.resolve("run.json"),
                        canonicalJson(
                            buildJsonObject {
                                put("run_id", metadataRunId)
                                put("started_at", startedAt)
                            },
                        ),
                    )
                }
            }
            return analysisId
        }

        private fun rec(
            label: String,
            response: HttpResponse<String>,
        ) {
            val body = normalize(response.body())
            val shown = if (body.length <= 700) body else "len=${body.length} sha256=${sha256Hex(body.toByteArray())}"
            lines += "$label | ${response.statusCode()} | $shown"
        }

        private fun normalize(text: String): String =
            text
                .replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "<uuid>")
                .replace(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?Z"), "<ts>")
                .replace(Regex("[0-9]{15}-[0-9a-f]{8}"), "<release_id>")
                .replace(Regex("\"csrf_token\":\"[0-9a-f]{64}\""), "\"csrf_token\":\"<csrf>\"")

        private companion object {
            val PERMISSIVE =
                """{"schema_version":"policy.v1","policy_id":"permissive","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
                    .toByteArray()
            val FAILING =
                """{"schema_version":"policy.v1","policy_id":"failing","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1,"scope":{"kind":"overall"}}]}"""
                    .toByteArray()
            val SMALL_SAMPLE =
                """{"schema_version":"policy.v1","policy_id":"small-sample","defaults":{"sample_floor":1,"min_samples":1000000},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
                    .toByteArray()
        }
    }

    private class Part(
        val name: String,
        val bytes: ByteArray,
        val filename: String? = null,
        val contentType: String? = null,
    )

    private class Client(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private var cookie = ""
        private var csrf = ""

        fun bootstrap() {
            val response = get("/api/bootstrap")
            cookie =
                response
                    .headers()
                    .firstValue("set-cookie")
                    .orElseThrow()
                    .substringBefore(';')
            csrf =
                Json
                    .parseToJsonElement(response.body())
                    .jsonObject
                    .getValue("csrf_token")
                    .jsonPrimitive.content
        }

        fun get(path: String): HttpResponse<String> = send(request(path).GET())

        fun delete(path: String): HttpResponse<String> = send(auth(request(path)).DELETE())

        fun post(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(auth(request(path)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun put(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(auth(request(path)).header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun multipart(
            path: String,
            parts: List<Part>,
        ): HttpResponse<String> {
            val boundary = "ltv-rules-boundary"
            val body = java.io.ByteArrayOutputStream()

            fun text(value: String) = body.write(value.toByteArray(UTF_8))
            parts.forEach { part ->
                text("--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"")
                part.filename?.let { text("; filename=\"$it\"") }
                text("\r\n")
                part.contentType?.let { text("Content-Type: $it\r\n") }
                text("\r\n")
                body.write(part.bytes)
                text("\r\n")
            }
            text("--$boundary--\r\n")
            return post(path, "multipart/form-data; boundary=$boundary", body.toByteArray())
        }

        private fun request(path: String): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI.create("$origin$path"))
                .timeout(Duration.ofSeconds(30))
                .apply { if (cookie.isNotEmpty()) header("Cookie", cookie) }

        private fun auth(request: HttpRequest.Builder): HttpRequest.Builder = request.header("Origin", origin).header("X-LTV-CSRF", csrf)

        private fun send(request: HttpRequest.Builder): HttpResponse<String> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    }
}

private fun HttpResponse<String>.jsonObject(): JsonObject = Json.parseToJsonElement(body()).jsonObject

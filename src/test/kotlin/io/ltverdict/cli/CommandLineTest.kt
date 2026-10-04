package io.ltverdict.cli

import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validatePolicy
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.sources.ONLINE_LOAD
import io.ltverdict.sources.ONLINE_SOURCE_REQUEST
import io.ltverdict.sources.ONLINE_SOURCE_REQUEST_V3_AUTO
import io.ltverdict.sources.OnlineSourceFixture
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.sources.readSourceRequest
import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CommandLineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `documented source files use the runtime contract`() {
        val profiles = Files.newInputStream(Path.of("docs/contracts/sources/v1/connections.example.json")).use(::readSourceProfiles)
        val request = Files.newInputStream(Path.of("docs/contracts/sources/v1/request.example.json")).use(::readSourceRequest)
        assertEquals(profiles.single().id, request.profileId)
    }

    @Test
    fun `CLI imports PostgreSQL pre and inert HTML and rejects capture without credentials`() {
        val input = tempDir.resolve("postgres.jtl")
        Files.writeString(input, ONLINE_LOAD)
        val pre = Path.of("docs/contracts/sources/v1/postgres-phase.example.json")
        val html = tempDir.resolve("report.html")
        Files.writeString(html, "<script>throw new Error('not executable')</script>")
        val result =
            run(
                "analyze",
                input.toString(),
                "--postgres-pre",
                pre.toString(),
                "--pg-profile-html",
                html.toString(),
                "--data-dir",
                tempDir.resolve("postgres-data").toString(),
            )
        assertEquals(0, result.exitCode, result.stderr)
        val context =
            Json
                .parseToJsonElement(result.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single { it.jsonObject["type"]?.jsonPrimitive?.content == "postgres_context" }
                .jsonObject
        assertTrue(context.getValue("reasons").jsonArray.any { it.jsonPrimitive.content == "PG_POST_CAPTURE_MISSING" })
        val config = tempDir.resolve("postgres-connections.json")
        Files.writeString(
            config,
            """{"schema_version":"source-connections.v2","connections":[{
          "id":"pg","source_kind":"postgresql","source_database_id":"test","host":"127.0.0.1",
          "database":"test","username_env":"LTV_TEST_MISSING_PG_USER_671DB","password_env":"LTV_TEST_MISSING_PG_PASSWORD_671DB"}]}""",
        )
        val capture = run("source", "pre", "--connections", config.toString(), "--profile", "pg")
        assertEquals(4, capture.exitCode)
        assertEquals("PG_CREDENTIALS_MISSING", capture.stderr.trim())
        assertEquals("", capture.stdout)
        val overwrite = run("source", "pre", "--connections", config.toString(), "--profile", "pg", "--pg-profile-html", html.toString())
        assertEquals(4, overwrite.exitCode)
        assertEquals("PG_REPORT_EXISTS", overwrite.stderr.trim())
        assertEquals("<script>throw new Error('not executable')</script>", Files.readString(html))
    }

    @Test
    fun `CLI acquires OpenSearch errors without fabricating resource metrics`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var method = ""
        var path = ""
        var query = ""
        var body = ""
        server.createContext("/") { exchange ->
            method = exchange.requestMethod
            path = exchange.requestURI.path
            query = exchange.requestURI.query
            body = exchange.requestBody.readAllBytes().decodeToString()
            val response =
                """{"timed_out":false,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                "hits":{"total":{"value":3,"relation":"eq"}},"aggregations":{
                "timeline":{"buckets":[{"key":1000,"doc_count":1},{"key":2000,"doc_count":2}]},
                "groups":{"sum_other_doc_count":0,"doc_count_error_upper_bound":0,"buckets":[
                {"key":["api","Timeout"],"doc_count":3,"first_at":{"value":1000},"last_at":{"value":2500}}]}}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        server.start()
        try {
            val input = tempDir.resolve("errors.jtl")
            Files.writeString(input, ONLINE_LOAD.replace("1767225600000,1000", "1000,2000"))
            val profiles = tempDir.resolve("errors-connections.json")
            Files.writeString(
                profiles,
                """{"schema_version":"source-connections.v1","connections":[{
                "id":"errors","source_kind":"opensearch","transport":"direct",
                "base_url":"http://127.0.0.1:${server.address.port}",
                "governor":{"requests_per_second":1000},
                "opensearch":{"indices":["logs-*"],"timestamp_field":"@timestamp","service_field":"service",
                "error_type_field":"error.type","message_field":"message","samples_per_group":0}}]}""",
            )
            val selection = tempDir.resolve("errors-request.json")
            Files.writeString(
                selection,
                """{"schema_version":"source-request.v1","profile_id":"errors",
                "start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000}""",
            )
            val data = tempDir.resolve("errors-data")
            val result =
                run(
                    "analyze",
                    input.toString(),
                    "--connections",
                    profiles.toString(),
                    "--source",
                    selection.toString(),
                    "--data-dir",
                    data.toString(),
                )
            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("POST", method)
            assertEquals("/logs-*/_search", path)
            assertTrue(query.contains("allow_no_indices=false"))
            assertEquals(
                "0",
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("size")
                    .jsonPrimitive.content,
            )
            val context =
                Json
                    .parseToJsonElement(result.stdout)
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .single { it.jsonObject["type"]?.jsonPrimitive?.content == "opensearch_errors" }
            assertEquals(
                "3",
                context.jsonObject
                    .getValue("total_errors")
                    .jsonPrimitive.content,
            )
            val saved =
                Files
                    .list(data.resolve("runs/${result.stdout.json("run_id")}/analyses"))
                    .use { it.findFirst().orElseThrow() }
            assertTrue(Files.isRegularFile(saved.resolve("opensearch-errors.json")))
            assertTrue(Files.isRegularFile(saved.resolve("source-response-1.json")))
            assertFalse(Files.exists(saved.resolve("resource-snapshot.json")))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `CLI imports OpenSearch context without a resource snapshot and replays saved facts`() {
        val load = ONLINE_LOAD.replace("1767225600000,1000", "1000,2000")
        val input = tempDir.resolve("context.jtl")
        Files.writeString(input, load)
        val context = tempDir.resolve("errors.json")
        Files.writeString(
            context,
            Files
                .readString(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))
                .replace("a".repeat(64), sha256Hex(load.encodeToByteArray())),
        )
        val data = tempDir.resolve("context-data")
        val imported = run("analyze", input.toString(), "--source-context", context.toString(), "--data-dir", data.toString())
        assertEquals(0, imported.exitCode, imported.stderr)
        val evidence =
            Json
                .parseToJsonElement(imported.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single { it.jsonObject["type"]?.jsonPrimitive?.content == "opensearch_errors" }
        assertEquals(
            "3",
            evidence.jsonObject
                .getValue("total_errors")
                .jsonPrimitive.content,
        )
        assertEquals(
            "90",
            evidence.jsonObject
                .getValue("error_rate_per_minute")
                .jsonPrimitive.content,
        )
        val analyses = data.resolve("runs/${imported.stdout.json("run_id")}/analyses")
        val saved = Files.list(analyses).use { it.findFirst().orElseThrow() }
        assertFalse(Files.exists(saved.resolve("resource-snapshot.json")))
        val replay =
            run(
                "analyze",
                input.toString(),
                "--source-context",
                saved.resolve("opensearch-errors.json").toString(),
                "--data-dir",
                tempDir.resolve("context-replay").toString(),
            )
        assertEquals(0, replay.exitCode, replay.stderr)
        assertEquals(
            evidence,
            Json
                .parseToJsonElement(replay.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .single { it.jsonObject["type"]?.jsonPrimitive?.content == "opensearch_errors" },
        )
    }

    @Test
    fun `CLI accepts repeated source context files`() {
        val input = tempDir.resolve("multiple.jtl")
        Files.writeString(input, ONLINE_LOAD)
        val original =
            Files
                .readString(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))
                .replace("a".repeat(64), sha256Hex(ONLINE_LOAD.encodeToByteArray()))
        val profile =
            Json
                .parseToJsonElement(original)
                .jsonObject
                .getValue("profile_id")
                .jsonPrimitive.content
        val files =
            listOf("a-errors", "b-errors").map { id ->
                tempDir.resolve("$id.json").also { Files.writeString(it, original.replace("\"$profile\"", "\"$id\"")) }
            }
        val result =
            run(
                "analyze",
                input.toString(),
                "--source-context",
                files[1].toString(),
                "--source-context",
                files[0].toString(),
                "--data-dir",
                tempDir.resolve("multiple-data").toString(),
            )
        assertEquals(0, result.exitCode, result.stderr)
        val contexts =
            Json
                .parseToJsonElement(result.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "opensearch_errors" }
        assertEquals(
            listOf("a-errors", "b-errors"),
            contexts.map {
                it.jsonObject
                    .getValue("profile_id")
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun `CLI acquires resources and replays saved snapshot offline`() {
        OnlineSourceFixture().use { fixture ->
            val profiles = tempDir.resolve("connections.json")
            val selection = tempDir.resolve("source.json")
            val input = tempDir.resolve("online.jtl")
            Files.writeString(profiles, fixture.profilesJson())
            Files.writeString(selection, ONLINE_SOURCE_REQUEST)
            Files.writeString(input, ONLINE_LOAD)
            val data = tempDir.resolve("online-data")
            val online =
                run(
                    "analyze",
                    input.toString(),
                    "--connections",
                    profiles.toString(),
                    "--source",
                    selection.toString(),
                    "--data-dir",
                    data.toString(),
                )
            assertEquals(2, online.exitCode, online.stderr)
            assertTrue(
                Json.parseToJsonElement(online.stdout).jsonObject.getValue("evidence").jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content ==
                        "source_summary"
                },
            )
            val analyses = data.resolve("runs/${online.stdout.json("run_id")}/analyses")
            val saved = Files.list(analyses).use { it.findFirst().orElseThrow() }
            assertTrue(Files.isRegularFile(saved.resolve("source-acquisition.json")))
            val replay =
                run(
                    "analyze",
                    input.toString(),
                    "--resources",
                    saved.resolve("resource-snapshot.json").toString(),
                    "--data-dir",
                    data.toString(),
                )
            assertEquals(online.exitCode, replay.exitCode, replay.stderr)
            assertEquals(1, fixture.requests.get())
            assertFalse(
                Json.parseToJsonElement(replay.stdout).jsonObject.getValue("evidence").jsonArray.any {
                    it.jsonObject["type"]?.jsonPrimitive?.content ==
                        "source_summary"
                },
            )
        }
    }

    @Test
    fun `CLI derives the v3 auto window and refuses a malformed v3 document before acquisition`() {
        OnlineSourceFixture().use { fixture ->
            val profiles = tempDir.resolve("auto-connections.json")
            val selection = tempDir.resolve("auto-source.json")
            val input = tempDir.resolve("auto.jtl")
            val data = tempDir.resolve("auto-data")
            Files.writeString(profiles, fixture.profilesJson())
            Files.writeString(selection, ONLINE_SOURCE_REQUEST_V3_AUTO)
            Files.writeString(input, ONLINE_LOAD)

            val online =
                run(
                    "analyze",
                    input.toString(),
                    "--connections",
                    profiles.toString(),
                    "--source",
                    selection.toString(),
                    "--data-dir",
                    data.toString(),
                )

            assertEquals(2, online.exitCode, online.stderr)
            assertEquals(1, fixture.requests.get())
            val summary =
                Json
                    .parseToJsonElement(online.stdout)
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it["type"]?.jsonPrimitive?.content == "source_summary" }
            assertEquals("auto", summary.getValue("window_origin").jsonPrimitive.content)
            assertEquals("DERIVED", summary.getValue("auto_window_status").jsonPrimitive.content)
            assertTrue(Files.exists(data.resolve("runs/${online.stdout.json("run_id")}").resolve("run-period.json")))

            Files.writeString(selection, ONLINE_SOURCE_REQUEST_V3_AUTO.replace("\"margin_ms\":0", "\"margin_ms\":1500"))
            val malformed =
                run(
                    "analyze",
                    input.toString(),
                    "--connections",
                    profiles.toString(),
                    "--source",
                    selection.toString(),
                    "--data-dir",
                    data.toString(),
                )
            assertError(malformed, 4, "malformed v3 source request")
            assertTrue(malformed.stderr.contains("SOURCE_REQUEST_INVALID"), malformed.stderr)
            assertEquals(1, fixture.requests.get())
        }
    }

    @Test
    fun `usage rejects exact syntax unknown flags and duplicate flags without stdout`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl")
        val dataDir = tempDir.resolve("data")
        val cases =
            listOf(
                emptyArray(),
                arrayOf("unknown"),
                arrayOf("analyze"),
                arrayOf("analyze", "--data-dir", dataDir.toString(), input.toString()),
                arrayOf("analyze", input.toString(), "--unknown"),
                arrayOf("analyze", input.toString(), "--data-dir", dataDir.toString(), "--data-dir", dataDir.toString()),
                arrayOf(
                    "analyze",
                    input.toString(),
                    "--policy",
                    fixture("policies/pass.json").toString(),
                    "--policy",
                    fixture("policies/pass.json").toString(),
                ),
                arrayOf("policy"),
                arrayOf("policy", "validate"),
                arrayOf("policy", "validate", fixture("policies/pass.json").toString(), "--data-dir", dataDir.toString()),
            )

        cases.forEach { args -> assertError(run(*args), 64, args.joinToString(" ")) }
    }

    @Test
    fun `histogram precision accepts three to five digits only`() {
        assertEquals(listOf(3, 4, 5), listOf("3", "4", "5").map { histogramSignificantDigits(it) })
        listOf("2", "6", "1", "0", "-3", "abc", "", "3.5", "99999999999").forEach {
            assertEquals(null, histogramSignificantDigits(it), it)
        }
    }

    @Test
    fun `histogram precision flag is validated by analyze and ui before any work`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val dataDir = tempDir.resolve("precision-usage").toString()
        listOf("2", "6", "abc", "").forEach { value ->
            assertError(
                run("analyze", input, "--histogram-significant-digits", value, "--data-dir", dataDir),
                64,
                "analyze $value",
            )
            assertError(run("ui", "--histogram-significant-digits", value, "--data-dir", dataDir), 64, "ui $value")
        }
        assertError(run("analyze", input, "--histogram-significant-digits"), 64, "analyze missing value")
        assertError(run("ui", "--histogram-significant-digits"), 64, "ui missing value")
        assertError(
            run("analyze", input, "--histogram-significant-digits", "4", "--histogram-significant-digits", "4", "--data-dir", dataDir),
            64,
            "analyze duplicate",
        )
        assertError(
            run("ui", "--histogram-significant-digits", "4", "--histogram-significant-digits", "4", "--data-dir", dataDir),
            64,
            "ui duplicate",
        )
        assertTrue(!Files.exists(tempDir.resolve("precision-usage")), "usage failures must not create the data directory")
    }

    @Test
    fun `analyze records the histogram precision in the identity and the analysis id`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val dataDir = tempDir.resolve("precision-data")
        val runs = mutableMapOf<String, String>()
        listOf(null, "3", "4", "5").forEach { digits ->
            val extra = digits?.let { arrayOf("--histogram-significant-digits", it) } ?: emptyArray()
            val result = run("analyze", input, *extra, "--data-dir", dataDir.toString())
            assertEquals(0, result.exitCode, result.stderr)
            runs[digits ?: "default"] = result.stdout.json("run_id")
        }
        val runId = runs.getValue("default")
        val analyses = dataDir.resolve("runs").resolve(runId).resolve("analyses")
        val byDigits =
            Files.list(analyses).use { stream ->
                stream.toList().associate { analysis ->
                    val identity = Json.parseToJsonElement(Files.readString(analysis.resolve("identity.json"))).jsonObject
                    identity
                        .getValue("histogram")
                        .jsonObject
                        .getValue("significant_digits")
                        .jsonPrimitive.content to
                        analysis.fileName.toString()
                }
            }

        assertEquals(setOf("3", "4", "5"), byDigits.keys)
        assertEquals(3, byDigits.values.toSet().size)
        assertEquals(3, Files.list(analyses).use { it.count() }.toInt())
    }

    @Test
    fun `analyze maps pass fail degraded and invalid outcomes to exit codes`() {
        val malformed = tempDir.resolve("malformed.jtl")
        Files.writeString(malformed, Files.readAllLines(fixture("jmeter/csv-5.6.3/input.jtl")).first() + "\nmalformed\n")
        val degraded = tempDir.resolve("truncated.log")
        Files.write(degraded, Files.readAllBytes(fixture("gatling/binary-3.13.5/simulation.log")) + byteArrayOf(2, 0))
        val cases =
            listOf(
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), fixture("policies/pass.json"), 0, "PASS"),
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), null, 0, "NO_POLICY"),
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), fixture("policies/fail.json"), 2, "FAIL"),
                AnalyzeCase(degraded, fixture("policies/pass.json"), 3, "NO_VERDICT"),
                AnalyzeCase(malformed, null, 4, "NO_VERDICT"),
            )

        cases.forEachIndexed { index, case ->
            val result =
                run(
                    "analyze",
                    case.input.toString(),
                    *(case.policy?.let { arrayOf("--policy", it.toString()) } ?: emptyArray()),
                    "--data-dir",
                    tempDir.resolve("data-$index").toString(),
                )

            assertEquals(case.exitCode, result.exitCode, case.input.toString())
            assertTrue(result.stderr.isEmpty(), result.stderr)
            assertEquals(case.policyVerdict, result.stdout.json("policy_verdict"))
        }
    }

    @Test
    fun `small samples keep the pass and fail exit codes and label the result`() {
        val input = fixture("jmeter/xml-5.6.3/input.xml")

        fun policy(
            name: String,
            threshold: String,
            defaults: String = "",
        ): Path =
            tempDir.resolve("$name.json").also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"$name",$defaults"rules":[""" +
                        """{"id":"errors","metric":"error_rate_ratio","operator":"lte","threshold":$threshold,"scope":{"kind":"overall"}}]}""",
                )
            }

        fun analyze(
            name: String,
            policy: Path,
        ): Pair<Int, kotlinx.serialization.json.JsonObject> {
            val result =
                run(
                    "analyze",
                    input.toString(),
                    "--policy",
                    policy.toString(),
                    "--data-dir",
                    tempDir.resolve("data-$name").toString(),
                )
            return result.exitCode to Json.parseToJsonElement(result.stdout).jsonObject
        }

        val small = "\"defaults\":{\"sample_floor\":1,\"min_samples\":5},"
        val (blockedCode, blocked) = analyze("blocked", policy("blocked", "0.5"))
        val (passCode, pass) = analyze("pass", policy("pass", "0.5", small))
        val (failCode, fail) = analyze("fail", policy("fail", "0.1", small))
        val (fullCode, full) = analyze("full", policy("full", "0.5", "\"defaults\":{\"sample_floor\":1,\"min_samples\":3},"))

        assertEquals(3, blockedCode)
        assertEquals("NO_VERDICT", blocked.getValue("policy_verdict").jsonPrimitive.content)
        assertTrue(
            blocked.getValue("analysis_coverage").jsonObject.getValue("reasons").jsonArray.any {
                it.jsonPrimitive.content == "INSUFFICIENT_SAMPLES"
            },
        )
        assertEquals(0, passCode)
        assertEquals("PASS", pass.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals(
            listOf("SMALL_SAMPLE"),
            pass.getValue("analysis_coverage").jsonObject.getValue("reasons").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "SMALL_SAMPLE",
            pass.getValue("evidence").jsonArray
                .single { it.jsonObject["type"]?.jsonPrimitive?.content == "policy_check" }
                .jsonObject.getValue("sample_mode").jsonPrimitive.content,
        )
        assertEquals(2, failCode)
        assertEquals("FAIL", fail.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals(0, fullCode)
        assertEquals("COMPLETE", full.getValue("analysis_coverage").jsonObject.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `policy validation separates normalized output from invalid diagnostics`() {
        val valid = fixture("policies/pass.json")
        val invalid = tempDir.resolve("invalid-policy.json")
        Files.writeString(invalid, "{\"schema_version\":\"policy.v1\",\"rules\":[]}")

        val validResult = run("policy", "validate", valid.toString())
        assertEquals(0, validResult.exitCode)
        assertTrue(validResult.stderr.isEmpty(), validResult.stderr)
        assertEquals(validCanonical(valid), validResult.stdout.trim())

        assertError(run("policy", "validate", invalid.toString()), 5, "invalid policy")
    }

    @Test
    fun `resources rejects missing malformed and duplicate inputs without analysis output`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val snapshot = tempDir.resolve("resources.json")
        Files.writeString(snapshot, "{}")
        assertError(run("analyze", input, "--resources", snapshot.toString()), 4, "invalid resources")
        assertError(run("analyze", input, "--resources", tempDir.resolve("missing.json").toString()), 4, "missing resources")
        assertError(
            run("analyze", input, "--resources", snapshot.toString(), "--resources", snapshot.toString()),
            64,
            "duplicate resources flag",
        )
    }

    @Test
    fun `correlation rejects malformed missing and duplicate plan inputs`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val plan = tempDir.resolve("correlation.json")
        Files.writeString(plan, "{}")
        assertError(run("analyze", input, "--correlation", plan.toString()), 4, "invalid correlation plan")
        assertError(run("analyze", input, "--correlation", tempDir.resolve("missing.json").toString()), 4, "missing plan")
        assertError(run("analyze", input, "--correlation", plan.toString(), "--correlation", plan.toString()), 64, "duplicate flag")
    }

    @Test
    fun `capacity rejects malformed missing duplicate and resource-less plans`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val plan = tempDir.resolve("capacity.json")
        Files.writeString(plan, "{}")
        assertError(run("analyze", input, "--capacity", plan.toString()), 4, "invalid capacity plan")
        assertError(run("analyze", input, "--capacity", tempDir.resolve("missing.json").toString()), 4, "missing capacity plan")
        assertError(
            run("analyze", input, "--capacity", plan.toString(), "--capacity", plan.toString()),
            64,
            "duplicate capacity flag",
        )

        Files.writeString(plan, validCapacityPlan())
        assertError(run("analyze", input, "--capacity", plan.toString()), 4, "capacity plan needs resources")
    }

    @Test
    fun `CLI persists bounded capacity facts for two real stages`() {
        val input = tempDir.resolve("capacity.jtl")
        Files.writeString(input, capacityLoad())
        val resources = tempDir.resolve("resources.json")
        val dataDir = tempDir.resolve("capacity-data")
        val inputHash = sha256Hex(Files.readAllBytes(input))
        Files.writeString(resources, capacityResources(inputHash))
        val validation = Files.newInputStream(resources).use(::validateResourceSnapshot)
        assertTrue(validation is ResourceValidation.Valid, validation.toString())
        val resourceHash = (validation as ResourceValidation.Valid).semanticSha256
        val plan = tempDir.resolve("capacity.json")
        Files.writeString(plan, capacityPlan(inputHash, resourceHash))

        val first =
            run(
                "analyze",
                input.toString(),
                "--policy",
                fixture("policies/pass.json").toString(),
                "--resources",
                resources.toString(),
                "--capacity",
                plan.toString(),
                "--data-dir",
                dataDir.toString(),
            )
        assertEquals(3, first.exitCode, first.stderr)
        val result = Json.parseToJsonElement(first.stdout).jsonObject
        assertEquals("capacity_step", result.getValue("analysis_mode").jsonPrimitive.content)
        assertEquals("NO_VERDICT", result.getValue("policy_verdict").jsonPrimitive.content, first.stdout)
        val capacity = result.getValue("capacity_summary").jsonObject
        assertEquals("BOUNDED", capacity.getValue("bound_type").jsonPrimitive.content)
        assertEquals("296", capacity.getValue("lower_inclusive").jsonPrimitive.content)
        assertEquals("344", capacity.getValue("upper_exclusive").jsonPrimitive.content)
        val analysis =
            Files
                .list(dataDir.resolve("runs").resolve(result.getValue("run_id").jsonPrimitive.content).resolve("analyses"))
                .use { it.findFirst().orElseThrow() }
        val beforePlan = Files.readAllBytes(analysis.resolve("capacity-plan.json"))
        val beforeCapacity = Files.readAllBytes(analysis.resolve("capacity.json"))

        val replay =
            run(
                "analyze",
                input.toString(),
                "--policy",
                fixture("policies/pass.json").toString(),
                "--resources",
                resources.toString(),
                "--capacity",
                plan.toString(),
                "--data-dir",
                dataDir.toString(),
            )
        assertEquals(3, replay.exitCode, replay.stderr)
        assertEquals(first.stdout, replay.stdout)
        assertEquals(beforePlan.toList(), Files.readAllBytes(analysis.resolve("capacity-plan.json")).toList())
        assertEquals(beforeCapacity.toList(), Files.readAllBytes(analysis.resolve("capacity.json")).toList())
    }

    @Test
    fun `analyze rejects nonregular and symlink input before writing stdout`() {
        assertError(
            run("analyze", tempDir.toString(), "--data-dir", tempDir.resolve("directory-data").toString()),
            4,
            "directory input",
        )

        val link = tempDir.resolve("input-link.jtl")
        assumeTrue(createSymlink(link, fixture("jmeter/csv-5.6.3/input.jtl")), "symbolic links are unavailable")
        assertError(run("analyze", link.toString(), "--data-dir", tempDir.resolve("link-data").toString()), 4, "symlink input")
    }

    @Test
    fun `analyze reports data directory busy without partial stdout`() {
        val dataDir = tempDir.resolve("locked")
        DataDirectory.open(dataDir).use {
            assertError(
                run("analyze", fixture("jmeter/csv-5.6.3/input.jtl").toString(), "--data-dir", dataDir.toString()),
                6,
                "DATA_DIR_BUSY",
            )
        }
    }

    @Test
    fun `report returns stored JSON bytes without changing the analysis`() {
        val dataDir = tempDir.resolve("report-data")
        val analyzed = run("analyze", fixture("jmeter/xml-5.6.3/input.xml").toString(), "--data-dir", dataDir.toString())
        assertEquals(0, analyzed.exitCode)
        val runId = analyzed.stdout.json("run_id")
        val analyses = dataDir.resolve("runs").resolve(runId).resolve("analyses")
        val analysisId =
            Files.list(analyses).use {
                it
                    .findFirst()
                    .orElseThrow()
                    .fileName
                    .toString()
            }
        val result = analyses.resolve(analysisId).resolve("analysis-result.json")
        val before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(result))

        val exported = run("report", runId, analysisId, "--format", "json", "--data-dir", dataDir.toString())

        assertEquals(0, exported.exitCode)
        assertEquals(Files.readAllBytes(result).decodeToString(), exported.stdout)
        assertEquals(before.toList(), MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(result)).toList())
    }

    @Test
    fun `report renders saved analysis as AsciiDoc without changing the analysis`() {
        val saved = savedAnalysis("asciidoc-report")
        val before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(saved.result))

        val exported = run("report", saved.runId, saved.analysisId, "--format", "asciidoc", "--data-dir", saved.dataDir.toString())

        assertEquals(0, exported.exitCode)
        assertTrue(exported.stderr.isEmpty(), exported.stderr)
        assertTrue(exported.stdout.startsWith("= LT Verdict report\n:!webfonts:\n"))
        assertTrue(exported.stdout.contains("Run ID\n[subs=specialchars]\n----\n\"${saved.runId}\"\n----"))
        assertTrue(exported.stdout.contains("Analysis ID\n[subs=specialchars]\n----\n\"${saved.analysisId}\"\n----"))
        assertEquals(before.toList(), MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(saved.result)).toList())
    }

    @Test
    fun `report validates syntax and maps missing busy corrupt and fail results without partial stdout`() {
        val saved = savedAnalysis("report-boundaries")
        listOf(
            arrayOf("report"),
            arrayOf("report", saved.runId, saved.analysisId, "--format", "xml"),
            arrayOf("report", saved.runId, saved.analysisId, "--format", "json", "--format", "html"),
            arrayOf("report", saved.runId, saved.analysisId, "--unknown", "json"),
        ).forEach { args -> assertError(run(*args), 64, args.joinToString(" ")) }
        assertError(
            run("report", "jmeter_jtl_csv-${"0".repeat(64)}", saved.analysisId, "--format", "json", "--data-dir", saved.dataDir.toString()),
            4,
            "unknown run",
        )
        assertError(
            run("report", saved.runId, "0".repeat(64), "--format", "json", "--data-dir", saved.dataDir.toString()),
            4,
            "unknown analysis",
        )
        DataDirectory.open(saved.dataDir).use {
            assertError(
                run("report", saved.runId, saved.analysisId, "--format", "json", "--data-dir", saved.dataDir.toString()),
                6,
                "busy data directory",
            )
        }
        Files.writeString(saved.result, "{}")
        assertError(
            run("report", saved.runId, saved.analysisId, "--format", "json", "--data-dir", saved.dataDir.toString()),
            70,
            "corrupt analysis",
        )

        val failed = savedAnalysis("failed-report", fixture("policies/fail.json"))
        val exported = run("report", failed.runId, failed.analysisId, "--format", "html", "--data-dir", failed.dataDir.toString())
        assertEquals(0, exported.exitCode)
        assertTrue(exported.stderr.isEmpty(), exported.stderr)
        assertTrue(exported.stdout.startsWith("<!doctype html>"))
    }

    private fun run(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf(*args), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8))
        return CliResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }

    @Test
    fun `trend rejects malformed missing duplicate and resource-less plans`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl").toString()
        val plan = tempDir.resolve("trend.json")
        Files.writeString(plan, "{}")
        assertError(run("analyze", input, "--trend", plan.toString()), 4, "invalid trend plan")
        assertError(run("analyze", input, "--trend", tempDir.resolve("missing.json").toString()), 4, "missing trend plan")
        assertError(run("analyze", input, "--trend", plan.toString(), "--trend", plan.toString()), 64, "duplicate trend flag")

        Files.writeString(plan, trendPlan("b".repeat(64)))
        assertError(run("analyze", input, "--trend", plan.toString()), 4, "trend plan needs resources")
    }

    @Test
    fun `CLI persists trend facts and replays them byte identically`() {
        val input = tempDir.resolve("trend.jtl")
        Files.writeString(input, trendLoad())
        val resources = tempDir.resolve("trend-resources.json")
        val dataDir = tempDir.resolve("trend-data")
        val inputHash = sha256Hex(Files.readAllBytes(input))
        Files.writeString(resources, trendResources(inputHash))
        val validation = Files.newInputStream(resources).use(::validateResourceSnapshot)
        assertTrue(validation is ResourceValidation.Valid, validation.toString())
        val plan = tempDir.resolve("trend.json")
        Files.writeString(plan, trendPlan((validation as ResourceValidation.Valid).semanticSha256))
        val args =
            arrayOf(
                "analyze",
                input.toString(),
                "--policy",
                fixture("policies/pass.json").toString(),
                "--resources",
                resources.toString(),
                "--trend",
                plan.toString(),
                "--data-dir",
                dataDir.toString(),
            )

        val first = run(*args)
        assertEquals(0, first.exitCode, first.stderr)
        val result = Json.parseToJsonElement(first.stdout).jsonObject
        assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content, first.stdout)
        assertEquals(
            "COMPLETE",
            result
                .getValue("analysis_coverage")
                .jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        val evidence = result.getValue("evidence").jsonArray.map { it.jsonObject }
        assertEquals(
            "TREND_OBSERVED",
            evidence
                .single { it.getValue("type").jsonPrimitive.content == "trend_check" }
                .getValue("status")
                .jsonPrimitive
                .content,
        )
        val finding =
            result
                .getValue("findings")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("type").jsonPrimitive.content == "resource_trend" }
        assertEquals("diagnostic", finding.getValue("effect").jsonPrimitive.content)
        assertEquals("NOT_ESTIMATED", finding.getValue("uncertainty").jsonPrimitive.content)

        val analysis =
            Files
                .list(dataDir.resolve("runs").resolve(result.getValue("run_id").jsonPrimitive.content).resolve("analyses"))
                .use { it.findFirst().orElseThrow() }
        assertArrayEquals(Files.readAllBytes(plan), Files.readAllBytes(analysis.resolve("trend-plan.json")))
        assertEquals(
            "trend.v1",
            Json
                .parseToJsonElement(Files.readAllBytes(analysis.resolve("trend.json")).decodeToString())
                .jsonObject
                .getValue("schema_version")
                .jsonPrimitive
                .content,
        )

        val replay = run(*args)
        assertEquals(0, replay.exitCode, replay.stderr)
        assertEquals(first.stdout, replay.stdout)
    }

    private fun assertError(
        result: CliResult,
        expectedExitCode: Int,
        message: String,
    ) {
        assertEquals(expectedExitCode, result.exitCode, message)
        assertTrue(result.stdout.isEmpty(), "unexpected stdout for $message: ${result.stdout}")
        assertFalse(result.stderr.isEmpty(), "missing stderr for $message")
    }

    private fun fixture(path: String): Path = Path.of("fixtures/slice1").resolve(path)

    private fun validCapacityPlan(): String {
        val inputHash = "a".repeat(64)
        val resourceHash = "b".repeat(64)
        val plan =
            """{"schema_version":"capacity-plan.v1","load_input_sha256":"$inputHash","resource_snapshot_sha256":"$resourceHash","load_axis":"rps","achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0},"generator_guard_rule_ids":[],"stages":[{"id":"stage-1","target":1,"from_epoch_ms":0,"to_epoch_ms":10000,"evaluation_window_id":"steady"}]}"""
        return plan
    }

    private fun capacityLoad(): String =
        buildString {
            append(
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                    "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n",
            )
            repeat(600) { second ->
                val starts = if (second < 300) 296 else 344
                repeat(starts) { index ->
                    val offset = if (second == 599 && index == starts - 1) 999 else index
                    append("${1767225600000L + second * 1000L + offset},1,steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0\n")
                }
            }
        }

    private fun capacityResources(inputHash: String): String {
        val cpu = List(30) { "0.5" } + List(30) { "1.5" }
        val generator = List(60) { "1.5" }
        val cpuValues = cpu.joinToString(",")
        val generatorValues = generator.joinToString(",")
        return """
            {"schema_version":"resource-snapshot.v1","load_input_sha256":"$inputHash",
             "start_epoch_ms":1767225600000,"step_ms":10000,"point_count":60,
             "series":[
               {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system",
                "aggregation":"interval_mean","values":[$cpuValues]},
               {"id":"generator","metric":"cpu_used","unit":"ratio","entity":"load-generator",
                "role":"generator","aggregation":"interval_mean","values":[$generatorValues]}],
             "windows":[
               {"id":"stage-300","from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000},
               {"id":"stage-350","from_epoch_ms":1767225900000,"to_epoch_ms":1767226200000}],
             "rules":[
               {"id":"cpu-sla","series_id":"cpu","unit":"ratio","operator":"gt","threshold":1,
                "min_consecutive_cells":1,"effect":"sla"},
               {"id":"generator-ok","series_id":"generator","unit":"ratio","operator":"lt","threshold":1,
                "min_consecutive_cells":1,"effect":"diagnostic"}]}
            """.trimIndent()
    }

    private fun capacityPlan(
        inputHash: String,
        resourceHash: String,
    ): String =
        """{"schema_version":"capacity-plan.v1","load_input_sha256":"$inputHash","resource_snapshot_sha256":"$resourceHash","load_axis":"rps","achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0.02,"required_capacity":300},"generator_guard_rule_ids":["generator-ok"],"stages":[{"id":"stage-300","target":300,"from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000,"evaluation_window_id":"stage-300"},{"id":"stage-350","target":350,"from_epoch_ms":1767225900000,"to_epoch_ms":1767226200000,"evaluation_window_id":"stage-350"}]}"""

    private fun trendLoad(): String =
        buildString {
            append("timeStamp,elapsed,label,success\n")
            repeat(40) { second ->
                append("${1767225600000L + second * 1000L},1,steady,true\n")
            }
        }

    private fun trendResources(inputHash: String): String {
        val values = List(40) { (100 + it).toString() }.joinToString(",")
        return """
            {"schema_version":"resource-snapshot.v1","load_input_sha256":"$inputHash",
             "start_epoch_ms":1767225600000,"step_ms":1000,"point_count":40,
             "series":[
               {"id":"cpu","metric":"cpu_used","unit":"percent","entity":"host","role":"system",
                "aggregation":"interval_mean","values":[$values]}],
             "windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225639000}],
             "rules":[
               {"id":"cpu-diagnostic","series_id":"cpu","unit":"percent","operator":"gt","threshold":1000,
                "min_consecutive_cells":1,"effect":"diagnostic"}]}
            """.trimIndent()
    }

    private fun trendPlan(resourceHash: String): String =
        """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$resourceHash","checks":[""" +
            """{"id":"cpu-growth","series_id":"cpu","window_id":"steady","direction":"increase","min_cells":30,""" +
            """"magnitude_gate":{"min_slope_units_per_second":0.5,"min_split_half_shift_pct":5}}]}"""

    private fun savedAnalysis(
        name: String,
        policy: Path? = null,
    ): SavedAnalysis {
        val dataDir = tempDir.resolve(name)
        val analyzed =
            run(
                "analyze",
                fixture("jmeter/xml-5.6.3/input.xml").toString(),
                *(policy?.let { arrayOf("--policy", it.toString()) } ?: emptyArray()),
                "--data-dir",
                dataDir.toString(),
            )
        assertTrue(analyzed.exitCode in setOf(0, 2), analyzed.stderr)
        val runId = analyzed.stdout.json("run_id")
        val analysisId =
            Files.list(dataDir.resolve("runs").resolve(runId).resolve("analyses")).use {
                it
                    .findFirst()
                    .orElseThrow()
                    .fileName
                    .toString()
            }
        return SavedAnalysis(
            dataDir,
            runId,
            analysisId,
            dataDir
                .resolve("runs")
                .resolve(runId)
                .resolve("analyses")
                .resolve(analysisId)
                .resolve("analysis-result.json"),
        )
    }

    private fun validCanonical(path: Path): String =
        (Files.newInputStream(path).use(::validatePolicy) as PolicyValidation.Valid).canonicalBytes.decodeToString()

    private fun String.json(field: String): String =
        Json
            .parseToJsonElement(trim())
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

    private fun createSymlink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: Exception) {
            false
        }

    private data class AnalyzeCase(
        val input: Path,
        val policy: Path?,
        val exitCode: Int,
        val policyVerdict: String,
    )

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class SavedAnalysis(
        val dataDir: Path,
        val runId: String,
        val analysisId: String,
        val result: Path,
    )
}

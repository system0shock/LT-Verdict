package io.ltverdict.cli

import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validatePolicy
import io.ltverdict.sources.ONLINE_LOAD
import io.ltverdict.sources.ONLINE_SOURCE_REQUEST
import io.ltverdict.sources.OnlineSourceFixture
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.sources.readSourceRequest
import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

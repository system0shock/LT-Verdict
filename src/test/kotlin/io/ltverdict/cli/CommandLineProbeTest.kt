package io.ltverdict.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.sources.ONLINE_LOAD
import io.ltverdict.sources.SchemaLite
import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class CommandLineProbeTest {
    @TempDir
    lateinit var tempDir: Path

    private val end = 1_767_225_300_000L

    @Test
    fun `validate is offline, prints a failed check without failing the command and hides names and addresses`() =
        withServer { server ->
            val connections = connections(server, auth = """"auth":{"type":"bearer","token_env":"LTV_PROBE_UNSET_ENV_1"},""")
            val text = run("source", "validate", "--connections", connections.toString())
            assertEquals(0, text.exitCode, text.stderr)
            assertTrue(text.stdout.contains("SOURCE_AUTH_UNAVAILABLE"), text.stdout)
            assertTrue(text.stdout.contains("FAIL"), text.stdout)
            val json = run("source", "validate", "--connections", connections.toString(), "--format", "json")
            assertEquals(0, json.exitCode, json.stderr)
            val document = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(document, SchemaLite.schema("source-check")), json.stdout)
            val profile =
                document
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("main", profile.getValue("profile_id").jsonPrimitive.content)
            listOf(text.stdout, json.stdout).forEach {
                assertFalse(it.contains("127.0.0.1"), it)
                assertFalse(it.contains("LTV_PROBE_UNSET_ENV_1"), it)
            }
            assertEquals(0, server.requests.get(), "validate must not open a connection")
        }

    @Test
    fun `validate refuses an unknown profile, a broken file and a network flag`() =
        withServer { server ->
            val connections = connections(server)
            val unknown = run("source", "validate", "--connections", connections.toString(), "--profile", "nope")
            assertEquals(4, unknown.exitCode)
            assertTrue(unknown.stderr.contains("SOURCE_PROFILE_NOT_FOUND"), unknown.stderr)
            val broken = tempDir.resolve("broken.json").also { Files.writeString(it, "{}") }
            assertEquals(4, run("source", "validate", "--connections", broken.toString()).exitCode)
            assertEquals(64, run("source", "validate", "--connections", connections.toString(), "--online").exitCode)
        }

    @Test
    fun `probe prints the series of a saved query, exits 0 and leaves the directory untouched`() =
        withServer { server ->
            server.handler = { it.respond(200, matrix(3)) }
            val connections = connections(server)
            val before = Files.list(tempDir).use { it.map(Path::toString).toList().sorted() }
            val json = probe(connections, "--format", "json")
            assertEquals(0, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals("source-probe.v1", report.string("schema_version"))
            assertEquals("3", report.string("series_count"))
            assertEquals("AMBIGUOUS_SERIES", report.getValue("decoder_check").jsonObject.string("code"))
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("source-probe")), json.stdout)
            val text = probe(connections)
            assertEquals(0, text.exitCode, text.stderr)
            assertTrue(text.stdout.contains("AMBIGUOUS_SERIES") && text.stdout.contains("3"), text.stdout)
            listOf(json.stdout, text.stdout).forEach { assertFalse(it.contains("127.0.0.1"), it) }
            assertEquals(before, Files.list(tempDir).use { it.map(Path::toString).toList().sorted() })
        }

    @Test
    fun `a failed probe exits 7 and refused input exits 4`() =
        withServer { server ->
            server.handler = { it.respond(503, "down") }
            val connections = connections(server)
            val failed = probe(connections, "--format", "json")
            assertEquals(7, failed.exitCode, failed.stderr)
            assertEquals("SOURCE_HTTP_5XX", Json.parseToJsonElement(failed.stdout).jsonObject.string("code"))
            val before = server.requests.get()
            val missingQuery = run("source", "probe", "--connections", connections.toString(), "--profile", "main", "--query-id", "nope")
            assertEquals(4, missingQuery.exitCode)
            assertTrue(missingQuery.stderr.contains("QUERY_NOT_FOUND"), missingQuery.stderr)
            val missingProfile = run("source", "probe", "--connections", connections.toString(), "--profile", "nope", "--query-id", "cpu")
            assertTrue(missingProfile.stderr.contains("SOURCE_PROFILE_NOT_FOUND"), missingProfile.stderr)
            val window = probe(connections, "--window-ms", "3600001")
            assertEquals(4, window.exitCode)
            assertTrue(window.stderr.contains("SOURCE_REQUEST_INVALID"), window.stderr)
            assertEquals(before, server.requests.get(), "refused input makes no request")
        }

    @Test
    fun `an OpenSearch profile is refused until its check exists`() =
        withServer { server ->
            val file =
                tempDir.resolve("opensearch.json").also {
                    Files.writeString(
                        it,
                        """{"schema_version":"source-connections.v1","connections":[{"id":"logs","source_kind":"opensearch",
                        "transport":"direct",
                        "base_url":"http://127.0.0.1:${server.port}","opensearch":{"indices":["logs-*"],"timestamp_field":"@timestamp",
                        "service_field":"service","error_type_field":"type","message_field":"message"}}]}""",
                    )
                }
            val result = run("source", "probe", "--connections", file.toString(), "--profile", "logs", "--query-id", "cpu")
            assertEquals(4, result.exitCode)
            assertTrue(result.stderr.contains("INVALID_PROBE"), result.stderr)
        }

    @Test
    fun `usage errors exit 64 and ad hoc expressions are not offered`() =
        withServer { server ->
            val connections = connections(server)
            val base = arrayOf("source", "probe", "--connections", connections.toString(), "--profile", "main", "--query-id", "cpu")
            assertEquals(64, run("source", "probe").exitCode)
            assertEquals(64, run("source", "probe", "--connections", connections.toString()).exitCode)
            val adHoc = run(*base, "--expression", "up")
            assertEquals(64, adHoc.exitCode)
            assertEquals(64, run(*base, "--expression-file", connections.toString()).exitCode)
            assertEquals(64, run(*base, "--window-ms", "abc").exitCode)
            assertEquals(64, run(*base, "--window-ms", "60000", "--window-ms", "60000").exitCode)
            assertEquals(64, run(*base, "--format", "xml").exitCode)
            assertEquals(64, run("source", "hash").exitCode)
            assertEquals(64, run("source", "hash", "a.json", "b.json").exitCode)
            val help = run("--help")
            listOf("ltv source validate", "ltv source probe", "ltv source hash").forEach { assertTrue(help.stdout.contains(it), it) }
            assertFalse(help.stdout.contains("--expression"))
            assertEquals(0, server.requests.get())
        }

    @Test
    fun `no output carries the credential value even when a label repeats it`() =
        withServer { server ->
            val name = listOf("USERNAME", "USER", "LOGNAME", "HOME").firstOrNull { (System.getenv(it) ?: "").length >= 3 }
            assumeTrue(name != null, "no suitable environment variable")
            val secret = System.getenv(name!!)
            val label = JsonPrimitive("x$secret" + "y").toString()
            server.handler = {
                it.respond(
                    200,
                    """{"status":"success","data":{"resultType":"matrix",
                    "result":[{"metric":{"instance":"host","leak":$label},"values":[]}]}}""",
                )
            }
            val connections = connections(server, auth = """"auth":{"type":"bearer","token_env":"$name"},""")
            listOf("json", "text").forEach { format ->
                val result = probe(connections, "--format", format)
                assertEquals(0, result.exitCode, result.stderr)
                assertFalse(result.stdout.contains(secret), "$format output repeats the credential")
                assertTrue(result.stdout.contains("***"), result.stdout)
            }
        }

    @Test
    fun `hash gives the identity value from a snapshot file and from a saved analysis`() {
        val input = tempDir.resolve("load.jtl").also { Files.writeString(it, ONLINE_LOAD) }
        val loadSha = io.ltverdict.core.sha256Hex(Files.readAllBytes(input))
        val snapshot = tempDir.resolve("resources.json").also { Files.writeString(it, snapshotJson(loadSha)) }
        val data = tempDir.resolve("data")
        val analysis = run("analyze", input.toString(), "--resources", snapshot.toString(), "--data-dir", data.toString())
        val ids = Regex("analysis_id=([0-9a-f]{64}) run_id=(\\S+)").find(analysis.stderr) ?: error(analysis.stderr)
        val identity =
            Json
                .parseToJsonElement(
                    Files.readString(data.resolve("runs/${ids.groupValues[2]}/analyses/${ids.groupValues[1]}/identity.json")),
                ).jsonObject
        val expected = identity.string("resource_snapshot_sha256")

        val file = run("source", "hash", snapshot.toString())
        assertEquals(0, file.exitCode, file.stderr)
        val fromFile = Json.parseToJsonElement(file.stdout).jsonObject
        assertEquals(emptyList<String>(), SchemaLite.errors(fromFile, SchemaLite.schema("resource-hash")), file.stdout)
        assertEquals("resource-hash.v1", fromFile.string("schema_version"))
        assertEquals(expected, fromFile.string("semantic_sha256"))
        assertEquals(identity.string("resource_config_sha256"), fromFile.string("config_sha256"))
        assertEquals(loadSha, fromFile.string("load_input_sha256"))
        assertEquals("1", fromFile.string("series_count"))

        val saved = run("source", "hash", "--run", ids.groupValues[2], "--analysis", ids.groupValues[1], "--data-dir", data.toString())
        assertEquals(0, saved.exitCode, saved.stderr)
        val fromSaved = Json.parseToJsonElement(saved.stdout).jsonObject
        assertEquals(emptyList<String>(), SchemaLite.errors(fromSaved, SchemaLite.schema("resource-hash")), saved.stdout)
        assertEquals(expected, fromSaved.string("semantic_sha256"))
        assertEquals(loadSha, fromSaved.string("load_input_sha256"))
        assertEquals(JsonNull, fromSaved.getValue("series_count"))

        val unknown = run("source", "hash", "--run", ids.groupValues[2], "--analysis", "0".repeat(64), "--data-dir", data.toString())
        assertEquals(4, unknown.exitCode)
        assertTrue(unknown.stderr.contains("ANALYSIS_NOT_FOUND"), unknown.stderr)
        val busy = DataDirectory.open(data)
        try {
            val locked = run("source", "hash", "--run", ids.groupValues[2], "--analysis", ids.groupValues[1], "--data-dir", data.toString())
            assertEquals(6, locked.exitCode)
        } finally {
            busy.close()
        }
        val bad = run("source", "hash", tempDir.resolve("missing.json").toString())
        assertEquals(4, bad.exitCode)

        val plain = run("analyze", input.toString(), "--data-dir", data.toString())
        val plainIds = Regex("analysis_id=([0-9a-f]{64}) run_id=(\\S+)").find(plain.stderr) ?: error(plain.stderr)
        val none =
            run("source", "hash", "--run", plainIds.groupValues[2], "--analysis", plainIds.groupValues[1], "--data-dir", data.toString())
        assertEquals(4, none.exitCode)
        assertTrue(none.stderr.contains("RESOURCE_SNAPSHOT_NOT_PROVIDED"), none.stderr)

        val result = data.resolve("runs/${ids.groupValues[2]}/analyses/${ids.groupValues[1]}/analysis-result.json")
        Files.write(result, Files.readAllBytes(result) + " ".toByteArray())
        val corrupt = run("source", "hash", "--run", ids.groupValues[2], "--analysis", ids.groupValues[1], "--data-dir", data.toString())
        assertEquals(4, corrupt.exitCode, corrupt.stderr)
        assertTrue(corrupt.stderr.contains("ANALYSIS_CORRUPT"), corrupt.stderr)
    }

    private fun snapshotJson(loadSha: String) =
        """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadSha","start_epoch_ms":1767225600000,""" +
            """"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm",""" +
            """"role":"system","aggregation":"interval_mean","values":[0.1,0.9]}],""" +
            """"windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225601000}],""" +
            """"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,""" +
            """"min_consecutive_cells":1,"effect":"sla"}]}"""

    private fun matrix(series: Int): String =
        """{"status":"success","data":{"resultType":"matrix","result":[""" +
            (1..series).joinToString(",") { """{"metric":{"instance":"host","pod":"api-$it"},"values":[[${end / 1000 - 285},"1"]]}""" } +
            "]}}"

    private fun probe(
        connections: Path,
        vararg extra: String,
    ) = run(
        "source",
        "probe",
        "--connections",
        connections.toString(),
        "--profile",
        "main",
        "--query-id",
        "cpu",
        "--end-epoch-ms",
        end.toString(),
        *extra,
    )

    private fun connections(
        server: Server,
        auth: String = "",
    ): Path =
        tempDir.resolve("connections.json").also {
            Files.writeString(
                it,
                """{"schema_version":"source-connections.v1","connections":[{
                  "id":"main","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:${server.port}",
                  "allow_insecure_http":true,$auth
                  "governor":{"requests_per_second":100,"timeout_ms":2000,"max_attempts":1},
                  "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
                    "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}]}""",
            )
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class Server(
        val port: Int,
    ) {
        val requests = AtomicInteger()

        @Volatile
        var handler: (HttpExchange) -> Unit = {}
    }

    private inline fun withServer(block: (Server) -> Unit) {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.executor = Executors.newCachedThreadPool { task -> Thread(task, "probe-cli-test").apply { isDaemon = true } }
        val server = Server(http.address.port)
        http.createContext("/") { exchange ->
            server.requests.incrementAndGet()
            server.handler(exchange)
        }
        http.start()
        try {
            block(server)
        } finally {
            http.stop(0)
        }
    }

    private class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(vararg args: String): Result {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf(*args), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8))
        return Result(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }
}

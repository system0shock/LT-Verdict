package io.ltverdict.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.sources.ProbeLimits
import io.ltverdict.sources.SchemaLite
import io.ltverdict.sources.TestPki
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

class CommandLineDoctorTest {
    @TempDir
    lateinit var tempDir: Path

    private val endEpochMillis = 1_767_225_300_000L

    @Test
    fun `offline by default makes zero network requests and reports NOT_PROBED`() =
        withServer { server ->
            val connections = connections(server, auth = """"auth":{"type":"none"},""")
            val text = run("doctor", "--connections", connections.toString())
            assertEquals(0, text.exitCode, text.stderr)
            assertTrue(text.stdout.contains("сеть не проверялась: нужен --online"), text.stdout)
            assertTrue(text.stdout.contains("NOT_PROBED"), text.stdout)

            val json = run("doctor", "--connections", connections.toString(), "--format", "json")
            assertEquals(0, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("doctor-report.v1", report.string("schema_version"))
            assertEquals("false", report.getValue("online").jsonPrimitive.content)
            assertEquals("NOT_PROBED", report.string("resource_side"))
            val profile =
                report
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("NOT_PROBED", profile.string("resource_side"))
            assertFalse("online" in profile)
            assertEquals(0, server.requests.get(), "offline doctor must not open a connection")
        }

    @Test
    fun `offline missing credentials exits 7, reports UNAVAILABLE and hides env name`() =
        withServer { server ->
            val connections = connections(server, auth = """"auth":{"type":"bearer","token_env":"LTV_DOC_MISSING_ENV_1"},""")
            val text = run("doctor", "--connections", connections.toString())
            assertEquals(7, text.exitCode, text.stderr)
            assertTrue(text.stdout.contains("FAIL SOURCE_AUTH_UNAVAILABLE"), text.stdout)
            assertTrue(text.stdout.contains("UNAVAILABLE"), text.stdout)

            val json = run("doctor", "--connections", connections.toString(), "--format", "json")
            assertEquals(7, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("UNAVAILABLE", report.string("resource_side"))
            assertEquals("1", report.getValue("summary").jsonObject.string("fail"))
            listOf(text.stdout, text.stderr, json.stdout, json.stderr).forEach {
                assertFalse(it.contains("LTV_DOC_MISSING_ENV_1"), it)
                assertFalse(it.contains("127.0.0.1"), it)
            }
            assertEquals(0, server.requests.get())
        }

    @Test
    fun `offline expired client certificate reports SOURCE_TLS_CLIENT_CERT_EXPIRED and hides paths and passwords`() {
        val tlsFile = tempDir.resolve("tls-connections.json")
        val caPath = TestPki.ca1Pem.toString().replace("\\", "\\\\")
        val clientPath = TestPki.clientExpired.toString().replace("\\", "\\\\")
        Files.writeString(
            tlsFile,
            """{"schema_version":"source-connections.v1","connections":[{
              "id":"mtls","source_kind":"prometheus","transport":"direct","base_url":"https://127.0.0.1:9",
              "auth":{"type":"none"},
              "tls":{"ca_file":"$caPath","client_keystore_file":"$clientPath","client_keystore_password_env":"LTV_DOC_PKI_PASS"},
              "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
                "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}]}""",
        )
        val result =
            runDoctor(
                listOf("--connections", tlsFile.toString(), "--format", "json"),
                environment = { name -> if (name == "LTV_DOC_PKI_PASS") TestPki.password else null },
            )
        assertEquals(7, result.exitCode, result.stderr)
        val report = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), result.stdout)
        assertEquals("UNAVAILABLE", report.string("resource_side"))
        val checks =
            report
                .getValue("profiles")
                .jsonArray
                .single()
                .jsonObject
                .getValue("checks")
                .jsonArray
                .map { it.jsonObject }
        val tlsCheck = checks.single { it.string("id") == "tls" }
        assertEquals("FAIL", tlsCheck.string("status"))
        assertEquals("SOURCE_TLS_CLIENT_CERT_EXPIRED", tlsCheck.string("code"))
        listOf(result.stdout, result.stderr).forEach {
            assertFalse(it.contains("LTV_DOC_PKI_PASS"), it)
            assertFalse(it.contains(TestPki.password), it)
            assertFalse(it.contains(TestPki.clientExpired.toString()), it)
            assertFalse(it.contains("127.0.0.1"), it)
        }
    }

    @Test
    fun `online probes queries sequentially and reports READY when all accepted`() =
        withServer { server ->
            server.handler = { exchange ->
                val endParam =
                    exchange.requestURI.rawQuery
                        .orEmpty()
                        .split('&')
                        .firstOrNull { it.startsWith("end=") }
                        ?.substringAfter("end=")
                        ?.toDoubleOrNull()
                        ?.toLong()
                        ?: (endEpochMillis / 1000)
                exchange.respond(
                    200,
                    """{"status":"success","data":{"resultType":"matrix","result":[""" +
                        """{"metric":{"instance":"host","pod":"api-1"},"values":[[${endParam - 285},"1"]]}]}}""",
                )
            }
            val connections = connections(server, auth = """"auth":{"type":"none"},""")

            val text = run("doctor", "--connections", connections.toString(), "--online")
            assertEquals(0, text.exitCode, text.stderr)
            assertTrue(text.stdout.contains("series=1"), text.stdout)
            assertTrue(text.stdout.contains("decoder=accepted"), text.stdout)
            assertTrue(text.stdout.contains("READY"), text.stdout)

            val json =
                runDoctor(
                    listOf("--connections", connections.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(0, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("true", report.getValue("online").jsonPrimitive.content)
            assertEquals("READY", report.string("resource_side"))
            val profile =
                report
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("READY", profile.string("resource_side"))
            val query =
                profile
                    .getValue("online")
                    .jsonObject
                    .getValue("queries")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("cpu", query.string("query_id"))
            assertEquals("OK", query.string("status"))
            assertEquals(JsonNull, query.getValue("code"))
            assertEquals("1", query.string("series_count"))
            assertEquals("1", query.string("observed_cells"))
            assertEquals("20", query.string("expected_cells"))
            assertEquals("true", query.getValue("decoder_check").jsonObject.string("accepted"))
        }

    @Test
    fun `online mixed accepted and ambiguous series reports DEGRADED and exits 7`() =
        withServer { server ->
            server.handler = { exchange ->
                val rawQuery = exchange.requestURI.rawQuery.orEmpty()
                if (rawQuery.contains("mem")) exchange.respond(200, matrix(3)) else exchange.respond(200, matrix(1))
            }
            val connections =
                connections(
                    server,
                    auth = """"auth":{"type":"none"},""",
                    queries = listOf("cpu", "mem"),
                )
            val json =
                runDoctor(
                    listOf("--connections", connections.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(7, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("DEGRADED", report.string("resource_side"))
            val queries =
                report
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("online")
                    .jsonObject
                    .getValue("queries")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals("OK", queries[0].string("status"))
            assertEquals("FAIL", queries[1].string("status"))
            assertEquals("AMBIGUOUS_SERIES", queries[1].string("code"))
            assertEquals(JsonNull, queries[1].getValue("observed_cells"))
        }

    @Test
    fun `online all queries failed reports UNAVAILABLE and exits 7`() =
        withServer { server ->
            server.handler = { it.respond(503, """{"error":"DOWN"}""") }
            val connections =
                connections(
                    server,
                    auth = """"auth":{"type":"none"},""",
                    queries = listOf("cpu", "mem"),
                )
            val json =
                runDoctor(
                    listOf("--connections", connections.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(7, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("UNAVAILABLE", report.string("resource_side"))
            val queries =
                report
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("online")
                    .jsonObject
                    .getValue("queries")
                    .jsonArray
                    .map { it.jsonObject }
            assertTrue(queries.all { it.string("status") == "FAIL" && it.string("code") == "SOURCE_HTTP_5XX" })
        }

    @Test
    fun `online empty result maps to WARN, DEGRADED and exit 0`() =
        withServer { server ->
            server.handler = { it.respond(200, matrix(0)) }
            val connections = connections(server, auth = """"auth":{"type":"none"},""")
            val json =
                runDoctor(
                    listOf("--connections", connections.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(0, json.exitCode, json.stderr)
            val report = Json.parseToJsonElement(json.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("doctor-report")), json.stdout)
            assertEquals("DEGRADED", report.string("resource_side"))
            val query =
                report
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("online")
                    .jsonObject
                    .getValue("queries")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("WARN", query.string("status"))
            assertEquals("EMPTY_RESULT", query.string("code"))
            assertEquals("0", query.string("series_count"))
            assertEquals("0", query.string("observed_cells"))
        }

    @Test
    fun `online respects maxRequestsPerRun cap and marks excess queries SKIPPED`() =
        withServer { server ->
            server.handler = { it.respond(200, matrix(1)) }
            val cappedOne =
                connections(
                    server,
                    auth = """"auth":{"type":"none"},""",
                    queries = listOf("cpu", "mem", "disk"),
                    maxRequestsPerRun = 1,
                )
            val jsonOne =
                runDoctor(
                    listOf("--connections", cappedOne.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(0, jsonOne.exitCode, jsonOne.stderr)
            assertEquals(1, server.requests.get())
            val reportOne = Json.parseToJsonElement(jsonOne.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(reportOne, SchemaLite.schema("doctor-report")), jsonOne.stdout)
            assertEquals("DEGRADED", reportOne.string("resource_side"))
            val queriesOne =
                reportOne
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("online")
                    .jsonObject
                    .getValue("queries")
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf("OK", "SKIPPED", "SKIPPED"), queriesOne.map { it.string("status") })
            assertEquals("SOURCE_REQUEST_CAP_EXCEEDED", queriesOne[1].string("code"))

            server.requests.set(0)
            val cappedZero =
                connections(
                    server,
                    auth = """"auth":{"type":"none"},""",
                    queries = listOf("cpu", "mem"),
                    maxRequestsPerRun = 0,
                )
            val jsonZero =
                runDoctor(
                    listOf("--connections", cappedZero.toString(), "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(0, jsonZero.exitCode, jsonZero.stderr)
            assertEquals(0, server.requests.get())
            val reportZero = Json.parseToJsonElement(jsonZero.stdout).jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(reportZero, SchemaLite.schema("doctor-report")), jsonZero.stdout)
            assertEquals("DEGRADED", reportZero.string("resource_side"))
        }

    @Test
    fun `security P5 P6 P7 hides secrets, headers, response bodies, env names and base urls`() =
        withServer { server ->
            server.handler = { exchange ->
                exchange.responseHeaders.add("Set-Cookie", "SESSION=SECRET-COOKIE")
                exchange.respond(401, """{"error":"SECRET-BODY"}""")
            }
            val connections = connections(server, auth = """"auth":{"type":"bearer","token_env":"LTV_DOC_SECRET_ENV"},""")
            val env = { name: String -> if (name == "LTV_DOC_SECRET_ENV") "SECRET-TOKEN-123" else null }
            val text = runDoctor(listOf("--connections", connections.toString(), "--online"), environment = env)
            val json = runDoctor(listOf("--connections", connections.toString(), "--online", "--format", "json"), environment = env)
            assertEquals(7, text.exitCode)
            assertEquals(7, json.exitCode)
            listOf(text.stdout, text.stderr, json.stdout, json.stderr).forEach { output ->
                assertFalse(output.contains("SECRET-BODY"), output)
                assertFalse(output.contains("SECRET-COOKIE"), output)
                assertFalse(output.contains("SECRET-TOKEN-123"), output)
                assertFalse(output.contains("LTV_DOC_SECRET_ENV"), output)
                assertFalse(output.contains("127.0.0.1"), output)
            }
        }

    @Test
    fun `profile filtering, broken files, unknown flags and help usage`() =
        withServer { server ->
            server.handler = { it.respond(200, matrix(1)) }
            val file = tempDir.resolve("multi.json")
            Files.writeString(
                file,
                """{"schema_version":"source-connections.v2","connections":[
                  {"id":"alpha","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:${server.port}",
                   "allow_insecure_http":true,"auth":{"type":"none"},
                   "governor":{"requests_per_second":100,"timeout_ms":2000,"max_attempts":1},
                   "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
                     "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]},
                  {"id":"beta","source_kind":"postgresql","source_database_id":"pg-db","host":"127.0.0.1","port":5432,"database":"app",
                   "username_env":"LTV_PG_USER","password_env":"LTV_PG_PASS"}
                ]}""",
            )
            val filtered =
                runDoctor(
                    listOf("--connections", file.toString(), "--profile", "alpha", "--online", "--format", "json"),
                    nowMillis = { endEpochMillis },
                )
            assertEquals(0, filtered.exitCode, filtered.stderr)
            val report = Json.parseToJsonElement(filtered.stdout).jsonObject
            assertEquals("2", report.getValue("connections").jsonObject.string("profile_count"))
            assertEquals(
                listOf("alpha"),
                report
                    .getValue("profiles")
                    .jsonArray
                    .map { it.jsonObject.string("profile_id") },
            )
            assertEquals("READY", report.string("resource_side"))

            val unknownProfile = run("doctor", "--connections", file.toString(), "--profile", "missing")
            assertEquals(4, unknownProfile.exitCode)
            assertTrue(unknownProfile.stderr.contains("SOURCE_PROFILE_NOT_FOUND"), unknownProfile.stderr)

            val broken = tempDir.resolve("broken.json").also { Files.writeString(it, "{}") }
            assertEquals(4, run("doctor", "--connections", broken.toString()).exitCode)
            assertEquals(4, run("doctor", "--connections", tempDir.resolve("missing.json").toString()).exitCode)

            assertEquals(64, run("doctor").exitCode)
            assertEquals(64, run("doctor", "--connections", file.toString(), "--expression", "up").exitCode)
            assertEquals(64, run("doctor", "--connections", file.toString(), "--format", "xml").exitCode)
            assertEquals(64, run("doctor", "--connections", file.toString(), "--online", "--online").exitCode)

            val help = run("--help")
            assertEquals(0, help.exitCode)
            assertTrue(help.stdout.contains("ltv doctor"), help.stdout)
        }

    private fun connections(
        server: Server,
        auth: String,
        queries: List<String> = listOf("cpu"),
        maxRequestsPerRun: Int? = null,
    ): Path =
        tempDir.resolve("connections-${System.nanoTime()}.json").also {
            val cap = maxRequestsPerRun?.let { value -> ""","max_requests_per_run":$value""" }.orEmpty()
            val queryJson =
                queries.joinToString(",") { q ->
                    """{"id":"$q","expression":"avg_over_time($q[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
                      "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}"""
                }
            Files.writeString(
                it,
                """{"schema_version":"source-connections.v1","connections":[{
                  "id":"main","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:${server.port}",
                  "allow_insecure_http":true,$auth
                  "governor":{"requests_per_second":100,"timeout_ms":2000,"max_attempts":1$cap},
                  "queries":[$queryJson]}]}""",
            )
        }

    private fun matrix(series: Int): String {
        val entries =
            (1..series).joinToString(",") {
                """{"metric":{"instance":"host","pod":"api-$it"},"values":[[${endEpochMillis / 1000 - 285},"1"]]}"""
            }
        return """{"status":"success","data":{"resultType":"matrix","result":[$entries]}}"""
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
        http.executor = Executors.newCachedThreadPool { task -> Thread(task, "doctor-cli-test").apply { isDaemon = true } }
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

    private fun runDoctor(
        args: List<String>,
        environment: (String) -> String? = System::getenv,
        systemProperty: (String) -> String? = System::getProperty,
        nowMillis: () -> Long = System::currentTimeMillis,
        limits: ProbeLimits = ProbeLimits(),
    ): Result {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode =
            try {
                doctorCommand(args, PrintStream(stdout, true, UTF_8), environment, systemProperty, nowMillis, limits)
            } catch (failure: CliFailure) {
                PrintStream(stderr, true, UTF_8).println(failure.message)
                failure.exitCode
            }
        return Result(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }
}

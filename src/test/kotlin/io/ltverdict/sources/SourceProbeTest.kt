package io.ltverdict.sources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class SourceProbeTest {
    private val end = 1_767_225_300_000L
    private val start = end - 300_000L
    private val step = 15_000L

    @Test
    fun `three series are counted with labels and the production decoder still refuses them`() =
        withStub { stub ->
            val body = promMatrix((1..3).map { mapOf("instance" to "host", "pod" to "api-$it") })
            stub.handler = { it.respond(200, body) }
            val report = probe(profile(stub.baseUrl))
            assertEquals("OK", report.string("status"))
            assertEquals("3", report.string("series_count"))
            assertEquals("AMBIGUOUS_SERIES", report.getValue("decoder_check").jsonObject.string("code"))
            assertEquals("false", report.getValue("decoder_check").jsonObject.string("accepted"))
            assertEquals(3, report.getValue("series").jsonArray.size)
            assertEquals(
                "api-1",
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("labels")
                    .jsonObject
                    .string("pod"),
            )
            assertEquals(
                "20",
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .string("observed_cells"),
            )
            assertEquals(
                "20",
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .string("expected_cells"),
            )
            assertEquals("profile_query", report.string("mode"))
            assertEquals("cpu", report.string("query_id"))
            val failure =
                assertThrows(PromqlDecodeFailure::class.java) { decodePromqlMatrix(body.encodeToByteArray(), emptyMap(), start, step, 20) }
            assertEquals("AMBIGUOUS_SERIES", failure.code)
        }

    @Test
    fun `one matching series is accepted by the production decoder`() =
        withStub { stub ->
            stub.handler = { it.respond(200, promMatrix(listOf(mapOf("instance" to "host")))) }
            val report = probe(profile(stub.baseUrl))
            assertEquals("1", report.string("series_count"))
            assertEquals("true", report.getValue("decoder_check").jsonObject.string("accepted"))
            assertEquals(JsonNull, report.getValue("decoder_check").jsonObject.getValue("code"))
        }

    @Test
    fun `an empty result and a foreign label are reported by the decoder check`() =
        withStub { stub ->
            stub.handler = { it.respond(200, promMatrix(emptyList())) }
            val empty = probe(profile(stub.baseUrl))
            assertEquals("OK", empty.string("status"))
            assertEquals("0", empty.string("series_count"))
            assertEquals("EMPTY_RESULT", empty.getValue("decoder_check").jsonObject.string("code"))
            stub.handler = { it.respond(200, promMatrix(listOf(mapOf("instance" to "other")))) }
            val foreign = probe(profile(stub.baseUrl))
            assertEquals("LABEL_MISMATCH", foreign.getValue("decoder_check").jsonObject.string("code"))
        }

    @Test
    fun `source warnings stay a warning and unsupported shapes fail`() =
        withStub { stub ->
            stub.handler = {
                it.respond(
                    200,
                    """{"status":"success","warnings":["w"],"data":{"resultType":"matrix","result":[]}}""",
                )
            }
            val warned = probe(profile(stub.baseUrl))
            assertEquals("OK", warned.string("status"))
            assertEquals(listOf("SOURCE_WARNINGS"), warned.getValue("warnings").jsonArray.map { it.jsonPrimitive.content })
            stub.handler = { it.respond(200, """{"status":"success","data":{"resultType":"vector","result":[]}}""") }
            val vector = probe(profile(stub.baseUrl))
            assertEquals("FAILED", vector.string("status"))
            assertEquals("UNSUPPORTED_RESULT_TYPE", vector.string("code"))
            assertEquals(JsonNull, vector.getValue("decoder_check"))
            assertEquals(JsonNull, vector.getValue("series_count"))
            stub.handler = {
                it.respond(200, """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"histograms":[]}]}}""")
            }
            assertEquals("UNSUPPORTED_HISTOGRAM", probe(profile(stub.baseUrl)).string("code"))
            stub.handler = { it.respond(200, """{"status":"error","errorType":"bad_data","error":"SECRET-PARSER-TEXT"}""") }
            val failed = probe(profile(stub.baseUrl))
            assertEquals("PROMQL_QUERY_FAILED", failed.string("code"))
            assertFalse(failed.toString().contains("SECRET-PARSER-TEXT"))
            stub.handler = { it.respond(200, "not json") }
            assertEquals("MALFORMED_RESPONSE", probe(profile(stub.baseUrl)).string("code"))
        }

    @Test
    fun `InfluxQL results are probed through the same document`() =
        withStub { stub ->
            fun influx(series: Int) =
                """{"results":[{"statement_id":0,"series":[""" +
                    (1..series).joinToString(",") {
                        """{"name":"m","tags":{"host":"h$it"},"columns":["time","value"],"values":[[$start,1.5],[${start + step},null]]}"""
                    } + "]}]}"
            val influxProfile = profile(stub.baseUrl, kind = SourceKind.INFLUXDB)
            stub.handler = { it.respond(200, influx(2)) }
            val two = probe(influxProfile)
            assertEquals("2", two.string("series_count"))
            assertEquals(
                "1",
                two
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .string("observed_cells"),
            )
            assertEquals("AMBIGUOUS_SERIES", two.getValue("decoder_check").jsonObject.string("code"))
            assertTrue(stub.requests.single().startsWith("/query?"), stub.requests.toString())
            stub.handler = { it.respond(200, """{"results":[{"statement_id":0,"error":"SECRET-INFLUX"}]}""") }
            val failed = probe(influxProfile)
            assertEquals("INFLUXQL_QUERY_FAILED", failed.string("code"))
            assertFalse(failed.toString().contains("SECRET-INFLUX"))
            stub.handler = { it.respond(200, """{"results":[{"statement_id":0}]}""") }
            assertEquals("0", probe(influxProfile).string("series_count"))
        }

    @Test
    fun `the request equals the production request for the same window`() =
        withStub { stub ->
            stub.handler = { it.respond(200, promMatrix(listOf(mapOf("instance" to "host")))) }
            val sourceProfile = profile(stub.baseUrl)
            probe(sourceProfile)
            val http = SourceHttp(listOf(sourceProfile))
            PromqlSource(listOf(sourceProfile), http).acquire(SourceRequest(sourceProfile.id, start, end, step), "0".repeat(64))
            assertEquals(2, stub.requests.size)
            assertEquals(stub.requests[1], stub.requests[0])
        }

    @Test
    fun `output is limited and cleaned`() =
        withStub { stub ->
            val manySeries = (1..25).map { mapOf("instance" to "host", "pod" to "p$it") }
            stub.handler = { it.respond(200, promMatrix(manySeries)) }
            val many = probe(profile(stub.baseUrl))
            assertEquals("25", many.string("series_count"))
            assertEquals(20, many.getValue("series").jsonArray.size)
            assertEquals("true", many.string("series_truncated"))
            val pod =
                many
                    .getValue("label_keys")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.string("key") == "pod" }
            assertEquals("25", pod.string("distinct_values"))
            assertEquals(5, pod.getValue("sample_values").jsonArray.size)
            assertEquals("true", pod.string("truncated"))

            val manyKeys = listOf((1..20).associate { "k%02d".format(it) to "v" })
            stub.handler = { it.respond(200, promMatrix(manyKeys)) }
            val keys = probe(profile(stub.baseUrl))
            assertEquals(16, keys.getValue("label_keys").jsonArray.size)
            assertEquals("true", keys.string("label_keys_truncated"))

            val dirty = listOf(mapOf("instance" to "host", "note" to "x".repeat(300), "ctl" to "a\u0000b‮c\nd"))
            stub.handler = { it.respond(200, promMatrix(dirty)) }
            val labels =
                probe(profile(stub.baseUrl))
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("labels")
                    .jsonObject
            assertTrue(labels.string("note").encodeToByteArray().size <= 256)
            assertEquals("a b c d", labels.string("ctl"))
        }

    @Test
    fun `secrets are masked before truncation and counted`() =
        withStub { stub ->
            val token = "s3cr3t-token-value-0123456789"
            val sourceProfile = profile(stub.baseUrl, auth = SourceAuth.Bearer("PROBE_TOKEN"))
            val labels =
                mapOf(
                    "instance" to "host",
                    "api_token" to "plain",
                    "equal" to token,
                    "contains" to "x".repeat(200) + token + "tail",
                    "bearer" to "Bearer abc",
                    "pem" to "-----BEGIN PRIVATE KEY-----",
                    "jwt" to "eyJhbGciOi",
                    token to "value",
                    "safe" to "visible",
                )
            stub.handler = { it.respond(200, promMatrix(listOf(labels))) }
            val report = probe(sourceProfile, env = { if (it == "PROBE_TOKEN") token else null })
            val shown =
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("labels")
                    .jsonObject
            assertEquals("visible", shown.string("safe"))
            listOf("api_token", "equal", "contains", "bearer", "pem", "jwt").forEach { assertEquals("***", shown.string(it), it) }
            assertFalse(report.toString().contains(token))
            assertFalse(report.toString().contains("PROBE_TOKEN"))
            assertTrue(report.string("redacted_count").toInt() >= 7, report.string("redacted_count"))
        }

    @Test
    fun `labels never repeat the profile address, the variable names or a cut string above the limits`() =
        withStub { stub ->
            val sourceProfile = profile(stub.baseUrl, auth = SourceAuth.Bearer("PROBE_TOKEN"))
            val labels =
                mapOf(
                    "instance" to "host",
                    "endpoint" to "scraped from ${stub.baseUrl}/metrics",
                    "note" to "see PROBE_TOKEN",
                    "long" to "x".repeat(300),
                    "emoji" to String(Character.toChars(0x1F600)).repeat(100),
                )
            stub.handler = { it.respond(200, promMatrix(listOf(labels))) }
            val report = probe(sourceProfile, env = { "an-unrelated-secret" })
            val shown =
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("labels")
                    .jsonObject
            assertEquals("***", shown.string("endpoint"))
            assertEquals("***", shown.string("note"))
            listOf(shown.string("long"), shown.string("emoji")).forEach {
                assertTrue(it.codePointCount(0, it.length) <= 128, it.length.toString())
                assertTrue(it.encodeToByteArray().size <= 256, it.length.toString())
            }
            listOf(stub.baseUrl.toString(), "PROBE_TOKEN", "127.0.0.1").forEach { assertFalse(report.toString().contains(it), it) }
        }

    @Test
    fun `observed cells count only finite numbers`() =
        withStub { stub ->
            val body =
                """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"host"},"values":[""" +
                    """[1,"1"],[2,"2.5"],[3,"NaN"],[4,"garbage"],[5,"+Inf"],[6,"true"],[7,1],[8,null]]}]}}"""
            stub.handler = { it.respond(200, body) }
            assertEquals(
                "2",
                probe(profile(stub.baseUrl))
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .string("observed_cells"),
            )
            val rows =
                """[[$start,1],[${start + step},"NaN"],[${start + 2 * step},true],[${start + 3 * step},null],[${start + 4 * step},2.5]]"""
            stub.handler = {
                it.respond(
                    200,
                    """{"results":[{"statement_id":0,"series":[{"name":"m","tags":{},"columns":["time","value"],"values":$rows}]}]}""",
                )
            }
            val report = probe(profile(stub.baseUrl, kind = SourceKind.INFLUXDB))
            assertEquals(
                "2",
                report
                    .getValue("series")
                    .jsonArray[0]
                    .jsonObject
                    .string("observed_cells"),
            )
        }

    @Test
    fun `a window end that cannot hold the window is refused without a request`() =
        withStub { stub ->
            val sourceProfile = profile(stub.baseUrl)
            listOf(Long.MIN_VALUE, -1L, 299_999L, Long.MAX_VALUE).forEach { endMillis ->
                val failure =
                    assertThrows(ProbeInputFailure::class.java) { probe(sourceProfile, ProbeSpec("cpu", endEpochMillis = endMillis)) }
                assertEquals("SOURCE_REQUEST_INVALID", failure.code, endMillis.toString())
            }
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `an authorization failure reveals no body header or address`() =
        withStub { stub ->
            val token = "t0ken-value-987654321"
            stub.handler = {
                it.responseHeaders.add("Set-Cookie", "session=SECRET-COOKIE")
                it.respond(401, """{"error":"SECRET-BODY"}""")
            }
            val sourceProfile = profile(stub.baseUrl, auth = SourceAuth.Bearer("PROBE_TOKEN"))
            val report = probe(sourceProfile, env = { token })
            assertEquals("FAILED", report.string("status"))
            assertEquals("SOURCE_HTTP_AUTH", report.string("code"))
            assertEquals("401", report.string("http_status"))
            val text = report.toString()
            listOf("SECRET-BODY", "SECRET-COOKIE", token, "PROBE_TOKEN", stub.baseUrl.toString(), "127.0.0.1", "Authorization").forEach {
                assertFalse(text.contains(it), it)
            }
            assertEquals("1", report.string("request_count"))
        }

    @Test
    fun `missing credentials fail before any request`() =
        withStub { stub ->
            val report = probe(profile(stub.baseUrl, auth = SourceAuth.Bearer("PROBE_TOKEN")), env = { null })
            assertEquals("SOURCE_AUTH_UNAVAILABLE", report.string("code"))
            assertEquals("0", report.string("request_count"))
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `server errors are not retried and limits are enforced`() =
        withStub { stub ->
            val retrying = profile(stub.baseUrl, governor = SourceGovernor(1000.0, 1, 1, 5_000, 3, true, null))
            stub.handler = { it.respond(503, "down") }
            val unavailable = probe(retrying)
            assertEquals("SOURCE_HTTP_5XX", unavailable.string("code"))
            assertEquals("503", unavailable.string("http_status"))
            assertEquals(1, stub.requests.size)
            stub.handler = { it.respond(429, "slow", mapOf("Retry-After" to "1")) }
            assertEquals("SOURCE_HTTP_429", probe(retrying).string("code"))
            assertEquals(2, stub.requests.size)

            stub.handler = { exchange ->
                exchange.sendResponseHeaders(200, 5L * 1024 * 1024)
                exchange.responseBody.use { out -> repeat(5 * 1024) { out.write(ByteArray(1024) { ' '.code.toByte() }) } }
            }
            assertEquals("SOURCE_RESPONSE_TOO_LARGE", probe(retrying).string("code"))

            stub.handler = { Thread.sleep(2_000) }
            val started = System.nanoTime()
            val timedOut = probe(retrying, limits = ProbeLimits(totalTimeoutMillis = 400))
            assertEquals("SOURCE_TIMEOUT", timedOut.string("code"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_900, "the total deadline ends the probe")
        }

    @Test
    fun `waiting for the limiter counts toward the total deadline`() =
        withStub { stub ->
            stub.handler = { it.respond(200, promMatrix(emptyList())) }
            val slow = profile(stub.baseUrl, governor = SourceGovernor(0.01, 1, 1, 5_000, 1, true, null))
            val started = System.nanoTime()
            val report = probe(slow, limits = ProbeLimits(totalTimeoutMillis = 300))
            assertEquals("SOURCE_TIMEOUT", report.string("code"))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_500)
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `invalid input is refused before the network`() =
        withStub { stub ->
            val sourceProfile = profile(stub.baseUrl)

            fun code(
                spec: ProbeSpec,
                target: SourceProfile = sourceProfile,
            ) = assertThrows(ProbeInputFailure::class.java) { probe(target, spec) }.code
            assertEquals("QUERY_NOT_FOUND", code(ProbeSpec("nope")))
            assertEquals("SOURCE_REQUEST_INVALID", code(ProbeSpec("cpu", windowMillis = 3_600_001)))
            assertEquals("SOURCE_REQUEST_INVALID", code(ProbeSpec("cpu", stepMillis = 1_500)))
            assertEquals("SOURCE_REQUEST_INVALID", code(ProbeSpec("cpu", windowMillis = 3_600_000, stepMillis = 10_000)))
            assertEquals("SOURCE_REQUEST_INVALID", code(ProbeSpec("cpu", windowMillis = 100_000, stepMillis = 30_000)))
            val openSearch = sourceProfile.copy(sourceKind = SourceKind.OPENSEARCH)
            assertEquals(
                "INVALID_PROBE",
                assertThrows(
                    ProbeInputFailure::class.java,
                ) { probeSource(openSearch, ProbeSpec("cpu"), SourceHttp(listOf(sourceProfile))) }.code,
            )
            assertTrue(stub.requests.isEmpty())
        }

    @Test
    fun `default window ends on the step grid at the injected time`() =
        withStub { stub ->
            stub.handler = { it.respond(200, promMatrix(emptyList())) }
            val window = probe(profile(stub.baseUrl), nowMillis = { end + 7_000 }).getValue("window").jsonObject
            assertEquals(end.toString(), window.string("end_epoch_ms"))
            assertEquals(start.toString(), window.string("start_epoch_ms"))
            assertEquals("20", window.string("cells"))
            assertEquals("15000", window.string("step_ms"))
        }

    @Test
    fun `reports conform to the published schema`() =
        withStub { stub ->
            val schema = SchemaLite.schema("source-probe")

            fun conforms(report: JsonObject) = assertEquals(emptyList<String>(), SchemaLite.errors(report, schema), report.toString())
            stub.handler = { it.respond(200, promMatrix((1..25).map { n -> mapOf("instance" to "host", "pod" to "p$n") })) }
            conforms(probe(profile(stub.baseUrl)))
            stub.handler = { it.respond(200, promMatrix(emptyList())) }
            conforms(probe(profile(stub.baseUrl)))
            stub.handler = { it.respond(401, "no") }
            conforms(probe(profile(stub.baseUrl)))
            stub.handler = { it.respond(200, "not json") }
            conforms(probe(profile(stub.baseUrl)))
            conforms(probe(profile(stub.baseUrl, auth = SourceAuth.Bearer("PROBE_TOKEN")), env = { null }))
            val surplus = (1..241).joinToString(",") { """[$it,"1"]""" }
            stub.handler = {
                it.respond(
                    200,
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"host"},"values":[$surplus]}]}}""",
                )
            }
            conforms(probe(profile(stub.baseUrl)))
        }

    private fun probe(
        sourceProfile: SourceProfile,
        spec: ProbeSpec = ProbeSpec("cpu"),
        env: (String) -> String? = { null },
        nowMillis: () -> Long = { end },
        limits: ProbeLimits = ProbeLimits(),
    ): JsonObject = probeSource(sourceProfile, spec, SourceHttp(listOf(sourceProfile), environment = env), env, nowMillis, limits)

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun promMatrix(series: List<Map<String, String>>): String =
        """{"status":"success","data":{"resultType":"matrix","result":[""" +
            series.joinToString(",") { metric ->
                val metricJson = metric.entries.joinToString(",") { (k, v) -> "${JsonPrimitive(k)}:${JsonPrimitive(v)}" }
                val values = (1..20).joinToString(",") { """[${(start + it * step) / 1000},"1"]""" }
                """{"metric":{$metricJson},"values":[$values]}"""
            } + "]}}"

    private fun profile(
        baseUrl: URI,
        kind: SourceKind = SourceKind.PROMETHEUS,
        auth: SourceAuth = SourceAuth.None,
        governor: SourceGovernor = SourceGovernor(1000.0, 1, 1, 5_000, 1, true, null),
    ): SourceProfile =
        SourceProfile(
            "main",
            kind,
            SourceTransport.DIRECT,
            baseUrl,
            null,
            auth,
            true,
            governor,
            listOf(
                SourceQuery(
                    "cpu",
                    if (kind == SourceKind.INFLUXDB) {
                        "SELECT mean(\"value\") AS \"value\" FROM \"m\" WHERE time >= \$__start AND time < \$__end " +
                            "GROUP BY time(\$__interval), \"host\""
                    } else {
                        "avg_over_time(cpu[\$__interval])"
                    },
                    "cpu_used",
                    "ratio",
                    "host",
                    ResourceRole.SYSTEM,
                    ResourceAggregation.INTERVAL_MEAN,
                    if (kind == SourceKind.INFLUXDB) emptyMap() else mapOf("instance" to "host"),
                ),
            ),
            database = if (kind == SourceKind.INFLUXDB) "db" else null,
        )

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.encodeToByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class Stub(
        val server: HttpServer,
    ) {
        val requests = CopyOnWriteArrayList<String>()

        @Volatile
        var handler: (HttpExchange) -> Unit = {}
        val baseUrl: URI = URI("http://127.0.0.1:${server.address.port}")

        init {
            server.createContext("/") { exchange ->
                requests += exchange.requestURI.toString()
                handler(exchange)
            }
        }
    }

    private inline fun withStub(block: (Stub) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool { task -> Thread(task, "probe-test").apply { isDaemon = true } }
        server.start()
        try {
            block(Stub(server))
        } finally {
            server.stop(0)
        }
    }
}

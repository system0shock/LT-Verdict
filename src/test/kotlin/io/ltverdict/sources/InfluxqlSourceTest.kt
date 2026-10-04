package io.ltverdict.sources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceRuleEffect
import io.ltverdict.core.ResourceRuleV1
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InfluxqlSourceTest {
    @Test
    fun `left-boundary samples map to cells and preserve gaps`() {
        val decoded =
            decodeInfluxqlResponse(
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a"},"columns":["time","value"],"values":[[1000,0.8],[3000,0.9]]}]}]}"""
                    .encodeToByteArray(),
                mapOf("host" to "a"),
                1_000,
                1_000,
                3,
            )!!

        assertEquals(mapOf("host" to "a"), decoded.labels)
        assertEquals(listOf(BigDecimal("0.8"), null, BigDecimal("0.9")), decoded.values)
    }

    @Test
    fun `column order does not change time and value meaning`() {
        val decoded =
            decodeInfluxqlResponse(
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a","zone":"test"},"columns":["value","time"],"values":[[0.75,2000]]}]}]}"""
                    .encodeToByteArray(),
                mapOf("host" to "a"),
                1_000,
                1_000,
                2,
            )!!

        assertEquals(mapOf("host" to "a", "zone" to "test"), decoded.labels)
        assertEquals(listOf(null, BigDecimal("0.75")), decoded.values)
    }

    @Test
    fun `zero series is a successful missing result`() {
        assertNull(
            decodeInfluxqlResponse(
                """{"results":[{"statement_id":0}]}""".encodeToByteArray(),
                emptyMap(),
                1_000,
                1_000,
                1,
            ),
        )
        assertNull(
            decodeInfluxqlResponse(
                """{"results":[{"statement_id":0,"series":[]}]}""".encodeToByteArray(),
                emptyMap(),
                1_000,
                1_000,
                1,
            ),
        )
    }

    @Test
    fun `unsafe ambiguous and malformed responses fail with stable codes`() {
        val series =
            """{"name":"host","tags":{"host":"a"},"columns":["time","value"],"values":[[1000,0.8]]}"""
        val cases =
            listOf(
                Case("{", "MALFORMED_RESPONSE"),
                Case("""{"results":[]}""", "MALFORMED_RESPONSE"),
                Case(
                    """{"results":[{"statement_id":0},{"statement_id":1}]}""",
                    "AMBIGUOUS_STATEMENT",
                ),
                Case("""{"results":[{"statement_id":0,"error":"top-secret"}]}""", "INFLUXQL_QUERY_FAILED"),
                Case("""{"results":[{"statement_id":0,"messages":[]}]}""", "SOURCE_WARNINGS"),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.dropLast(1)},"partial":true}]}]}""",
                    "SOURCE_PARTIAL_RESPONSE",
                ),
                Case("""{"results":[{"statement_id":0,"series":[$series,$series]}]}""", "AMBIGUOUS_SERIES"),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("[1000,0.8]", "[1000,0.8],[1000.0,0.9]")}]}]}""",
                    "DUPLICATE_TIMESTAMP",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("1000", "1500")}]}]}""",
                    "OFF_GRID_TIMESTAMP",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("[\"time\",\"value\"]", "[\"time\",\"time\"]")}]}]}""",
                    "MALFORMED_RESPONSE",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("[1000,0.8]", "[1000]")}]}]}""",
                    "MALFORMED_RESPONSE",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("\"a\"", "\"b\"")}]}]}""",
                    "LABEL_MISMATCH",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("1000", "\"1000\"")}]}]}""",
                    "MALFORMED_RESPONSE",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("0.8", "\"0.8\"")}]}]}""",
                    "MALFORMED_RESPONSE",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("1000", "1e65")}]}]}""",
                    "RESOURCE_LIMIT_EXCEEDED",
                ),
                Case(
                    """{"results":[{"statement_id":0,"series":[${series.replace("0.8", "${"1".repeat(65)}")}]}]}""",
                    "RESOURCE_LIMIT_EXCEEDED",
                ),
                Case("""{"results":[{"statement_id":0}],"results":[{"statement_id":0}]}""", "DUPLICATE_OBJECT_KEY"),
                Case(
                    """{"results":[{"statement_id":0}],"deep":${"[".repeat(13)}0${"]".repeat(13)}}""",
                    "RESOURCE_LIMIT_EXCEEDED",
                ),
            )

        cases.forEach { case ->
            val failure =
                assertThrows(
                    PromqlDecodeFailure::class.java,
                    {
                        decodeInfluxqlResponse(
                            case.json.encodeToByteArray(),
                            mapOf("host" to "a"),
                            1_000,
                            1_000,
                            3,
                        )
                    },
                    case.code,
                )
            assertEquals(case.code, failure.code, case.code)
            assertEquals(case.code, failure.message, case.code)
        }

        val invalidUtf8 =
            assertThrows(PromqlDecodeFailure::class.java) {
                decodeInfluxqlResponse(byteArrayOf(0xc3.toByte()), emptyMap(), 1_000, 1_000, 1)
            }
        assertEquals("MALFORMED_RESPONSE", invalidUtf8.code)
    }

    @Test
    fun `tag and cell limits are enforced before snapshot construction`() {
        val tooManyTags = (0..16).joinToString(",", "{", "}") { index -> "\"tag$index\":\"value\"" }
        val tooLongKey = "k".repeat(129)
        val tooLongValue = "v".repeat(513)
        val cases =
            listOf(
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":$tooManyTags,"columns":["time","value"],"values":[]}]}]}""",
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"$tooLongKey":"value"},"columns":["time","value"],"values":[]}]}]}""",
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"$tooLongValue"},"columns":["time","value"],"values":[]}]}]}""",
                """{"results":[{"statement_id":0,"series":[{"name":"host","columns":["time","value"],"values":[[1000,1],[2000,2]]}]}]}""",
            )

        cases.forEach { json ->
            val failure =
                assertThrows(PromqlDecodeFailure::class.java) {
                    decodeInfluxqlResponse(json.encodeToByteArray(), emptyMap(), 1_000, 1_000, 1)
                }
            assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.code)
        }
    }

    @Test
    fun `collector sends resolved GET and persists accepted left-boundary response`() =
        withServer { server, baseUrl ->
            val method = AtomicReference<String>()
            val path = AtomicReference<String>()
            val parameters = AtomicReference<Map<String, String>>()
            val authorization = AtomicReference<String?>()
            val body =
                """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a"},"columns":["time","value"],"values":[[1500,0.8],[3500,0.9]]}]}]}"""
                    .encodeToByteArray()
            server.createContext("/") { exchange ->
                method.set(exchange.requestMethod)
                path.set(exchange.requestURI.rawPath)
                parameters.set(queryParameters(exchange.requestURI.rawQuery))
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                exchange.respond(200, body)
            }
            val profile =
                profile(
                    baseUrl,
                    auth = SourceAuth.Token("INFLUX_TOKEN"),
                )

            val acquisition =
                PromqlSource(listOf(profile), SourceHttp(listOf(profile)) { "top-secret-token" }).acquire(
                    SourceRequest("influx", 1_500, 4_500, 1_000),
                    HASH,
                )

            assertEquals("GET", method.get())
            assertEquals("/query", path.get())
            assertEquals(
                mapOf(
                    "db" to "metrics",
                    "epoch" to "ms",
                    "q" to
                        "SELECT mean(\"cpu\") AS \"value\" FROM \"host\" WHERE time >= 1500ms AND time < 4500ms " +
                        "GROUP BY time(1000ms, 500ms) fill(null)",
                ),
                parameters.get(),
            )
            assertEquals("Token top-secret-token", authorization.get())
            val series = requireNotNull(acquisition.snapshot).snapshot.series.single()
            assertEquals(listOf(BigDecimal("0.8"), null, BigDecimal("0.9")), series.values)
            assertEquals(mapOf("host" to "a"), series.labels)
            val provenance = requireNotNull(requireNotNull(acquisition.snapshot).snapshot.provenance)
            assertEquals("influxdb", provenance.sourceKind)
            assertEquals("left_boundary", provenance.clockAlignment)
            assertTrue(provenance.querySemantics.contains("sample_at=left_boundary"))
            assertEquals("PARTIAL" to "MISSING_SAMPLES", acquisition.evidence.queryStatus("cpu"))
            assertEquals("influxdb", acquisition.evidence.string("source_kind"))
            assertEquals(setOf("source-acquisition.json", "source-response-1.json"), acquisition.artifacts.keys)
            assertArrayEquals(body, acquisition.artifacts.getValue("source-response-1.json"))
            val summary = acquisition.artifacts.getValue("source-acquisition.json").decodeToString()
            assertFalse(summary.contains("top-secret-token"))
            assertFalse(summary.contains("INFLUX_TOKEN"))
        }

    @Test
    fun `grafana influxdb profile uses the fixed query route`() =
        withServer { server, baseUrl ->
            val path = AtomicReference<String>()
            server.createContext("/") { exchange ->
                path.set(exchange.requestURI.rawPath)
                exchange.respond(200, """{"results":[{"statement_id":0}]}""".encodeToByteArray())
            }
            val profile =
                profile(
                    baseUrl.resolve("/grafana"),
                    transport = SourceTransport.GRAFANA_PROXY,
                    datasourceUid = "influx-main",
                )

            PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(
                SourceRequest("influx", 1_000, 2_000, 1_000),
                HASH,
            )

            assertEquals("/grafana/api/datasources/proxy/uid/influx-main/query", path.get())
            assertFalse(path.get().contains("write"))
        }

    @Test
    fun `authentication and partial failures keep SLA series all null`() =
        withServer { server, baseUrl ->
            val attempt = AtomicInteger()
            server.createContext("/query") { exchange ->
                if (attempt.incrementAndGet() == 1) {
                    exchange.respond(401, "top-secret".encodeToByteArray())
                } else {
                    exchange.respond(
                        200,
                        """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a"},"columns":["time","value"],"values":[[1000,1]],"partial":true}]}]}"""
                            .encodeToByteArray(),
                    )
                }
            }
            val rule =
                ResourceRuleV1(
                    "cpu-high",
                    "cpu",
                    "ratio",
                    ResourceOperator.GT,
                    BigDecimal("0.8"),
                    1,
                    ResourceRuleEffect.SLA,
                )
            val profile =
                profile(
                    baseUrl,
                    queries = listOf(query("cpu"), query("memory")),
                    rules = listOf(rule),
                )

            val acquisition =
                PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(
                    SourceRequest("influx", 1_000, 3_000, 1_000),
                    HASH,
                )

            assertTrue(requireNotNull(acquisition.snapshot).snapshot.series.all { item -> item.values.all { it == null } })
            assertEquals(listOf(rule), requireNotNull(acquisition.snapshot).snapshot.rules)
            assertEquals("FAILED" to "SOURCE_HTTP_AUTH", acquisition.evidence.queryStatus("cpu"))
            assertEquals("FAILED" to "SOURCE_PARTIAL_RESPONSE", acquisition.evidence.queryStatus("memory"))
            assertEquals("FAILED", acquisition.evidence.string("status"))
            assertEquals(setOf("source-acquisition.json"), acquisition.artifacts.keys)
            assertFalse(
                acquisition.artifacts
                    .getValue("source-acquisition.json")
                    .decodeToString()
                    .contains("top-secret"),
            )
        }

    @Test
    fun `an armed influxdb profile labels empty results and rejects a conflicting arm tag`() =
        withServer { server, baseUrl ->
            val bodies =
                java.util.ArrayDeque(
                    listOf(
                        """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a","arm":"A"},"columns":["time","value"],"values":[[1500,0.8]]}]}]}""",
                        """{"results":[{"statement_id":0,"series":[{"name":"host","tags":{"host":"a","arm":"B"},"columns":["time","value"],"values":[[1500,0.8]]}]}]}""",
                        """{"results":[{"statement_id":0}]}""",
                    ),
                )
            server.createContext("/") { exchange -> exchange.respond(200, bodies.removeFirst().encodeToByteArray()) }
            val profile = profile(baseUrl, queries = listOf(query("same"), query("other"), query("empty"))).copy(arm = "A")

            val acquisition =
                PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(SourceRequest("influx", 1_500, 2_500, 1_000), HASH)

            val series = requireNotNull(acquisition.snapshot).snapshot.series.associateBy { it.id }
            assertEquals("SUCCESS" to null, acquisition.evidence.queryStatus("same"))
            assertEquals("FAILED" to "LABEL_MISMATCH", acquisition.evidence.queryStatus("other"))
            assertEquals("MISSING" to "EMPTY_RESULT", acquisition.evidence.queryStatus("empty"))
            assertEquals(listOf(BigDecimal("0.8")), series.getValue("same").values)
            assertEquals(listOf<BigDecimal?>(null), series.getValue("other").values)
            assertTrue(series.values.all { it.labels["arm"] == "A" })
            assertEquals("A", acquisition.evidence.string("arm"))
        }

    private fun profile(
        baseUrl: URI,
        transport: SourceTransport = SourceTransport.DIRECT,
        datasourceUid: String? = null,
        auth: SourceAuth = SourceAuth.None,
        queries: List<SourceQuery> = listOf(query("cpu")),
        rules: List<ResourceRuleV1> = emptyList(),
    ) = SourceProfile(
        id = "influx",
        sourceKind = SourceKind.INFLUXDB,
        transport = transport,
        baseUrl = baseUrl,
        datasourceUid = datasourceUid,
        auth = auth,
        allowInsecureHttp = true,
        governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 2_000, maxAttempts = 1),
        queries = queries,
        rules = rules,
        database = "metrics",
    )

    private fun query(id: String): SourceQuery =
        SourceQuery(
            id = id,
            expression = EXPRESSION.replace("cpu", id),
            metric = id,
            unit = "ratio",
            entity = "host-a",
            role = ResourceRole.SYSTEM,
            aggregation = ResourceAggregation.INTERVAL_MEAN,
            labels = mapOf("host" to "a"),
        )

    private fun queryParameters(raw: String): Map<String, String> =
        raw.split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), StandardCharsets.UTF_8)
        }

    private inline fun withServer(block: (HttpServer, URI) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
        try {
            block(server, URI("http://localhost:${server.address.port}"))
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray,
    ) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun kotlinx.serialization.json.JsonObject.queryStatus(id: String): Pair<String, String?> {
        val query =
            getValue("queries")
                .jsonArray
                .map { it as kotlinx.serialization.json.JsonObject }
                .single { it.string("id") == id }
        return query.string("status") to query["reason"]?.jsonPrimitive?.content
    }

    private data class Case(
        val json: String,
        val code: String,
    )

    private companion object {
        const val EXPRESSION =
            "SELECT mean(\"cpu\") AS \"value\" FROM \"host\" WHERE time >= \$__start AND time < \$__end " +
                "GROUP BY time(\$__interval, \$__offset) fill(null)"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

package io.ltverdict.sources

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
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class PromqlSourceTest {
    @Test
    fun `matrix samples on right boundaries map to preceding cells and keep returned labels`() {
        val decoded =
            decodePromqlMatrix(
                matrix(
                    """{"__name__":"cpu_usage","instance":"node-a","zone":"test"}""",
                    """[[1,"1.25"],[2,"2.5"]]""",
                ).encodeToByteArray(),
                mapOf("instance" to "node-a"),
                startEpochMillis = 0,
                stepMillis = 1_000,
                pointCount = 2,
            )!!

        assertEquals(
            mapOf("__name__" to "cpu_usage", "instance" to "node-a", "zone" to "test"),
            decoded.labels,
        )
        assertEquals(listOf(BigDecimal("1.25"), BigDecimal("2.5")), decoded.values)
    }

    @Test
    fun `missing samples and nonfinite values remain null`() {
        val decoded =
            decodePromqlMatrix(
                matrix(
                    """{"instance":"node-a"}""",
                    """[[1,"NaN"],[2,"+Inf"],[3,"-Inf"],[5,"5"]]""",
                ).encodeToByteArray(),
                mapOf("instance" to "node-a"),
                startEpochMillis = 0,
                stepMillis = 1_000,
                pointCount = 5,
            )!!

        assertEquals(listOf(null, null, null, null, BigDecimal("5")), decoded.values)
        assertNull(
            decodePromqlMatrix(
                """{"status":"success","data":{"resultType":"matrix","result":[]}}""".encodeToByteArray(),
                emptyMap(),
                0,
                1_000,
                1,
            ),
        )
    }

    @Test
    fun `unsafe or ambiguous matrix shapes fail with stable codes`() {
        val cases =
            listOf(
                Case(
                    matrix("{}", """[[1,"1"],[1.0,"2"]]"""),
                    "DUPLICATE_TIMESTAMP",
                    pointCount = 2,
                ),
                Case(matrix("{}", """[[1.5,"1"]]"""), "OFF_GRID_TIMESTAMP", pointCount = 2),
                Case(
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[]},{"metric":{},"values":[]}]}}""",
                    "AMBIGUOUS_SERIES",
                ),
                Case(matrix("""{"instance":"node-b"}""", "[]"), "LABEL_MISMATCH", labels = mapOf("instance" to "node-a")),
                Case(
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[],"histograms":[[1,{"count":"1","sum":"2","buckets":[]}]]}]}}""",
                    "UNSUPPORTED_HISTOGRAM",
                ),
                Case(matrix("{}", """[[1,"1"],[2,"2"]]"""), "RESOURCE_LIMIT_EXCEEDED"),
                Case("""{"status":"success","data":{"resultType":"vector","result":[]}}""", "UNSUPPORTED_RESULT_TYPE"),
                Case("""{"status":"error","errorType":"bad_data","error":"secret"}""", "PROMQL_QUERY_FAILED"),
                Case(
                    """{"status":"success","warnings":["partial data: secret"],"data":{"resultType":"matrix","result":[]}}""",
                    "SOURCE_WARNINGS",
                ),
                Case("{", "MALFORMED_RESPONSE"),
                Case(
                    """{"status":"success","status":"success","data":{"resultType":"matrix","result":[]}}""",
                    "DUPLICATE_OBJECT_KEY",
                ),
                Case(
                    """{"status":"success","data":{"resultType":"matrix","result":[]},"deep":${"[".repeat(13)}0${"]".repeat(13)}}""",
                    "RESOURCE_LIMIT_EXCEEDED",
                ),
                Case(matrix("{}", """[[1e65,"1"]]"""), "RESOURCE_LIMIT_EXCEEDED"),
                Case(matrix("{}", """[[1,"${"1".repeat(65)}"]]"""), "RESOURCE_LIMIT_EXCEEDED"),
            )

        cases.forEach { case ->
            val failure =
                assertThrows(
                    PromqlDecodeFailure::class.java,
                    {
                        decodePromqlMatrix(
                            case.json.encodeToByteArray(),
                            case.labels,
                            startEpochMillis = 0,
                            stepMillis = 1_000,
                            pointCount = case.pointCount,
                        )
                    },
                    case.code,
                )
            assertEquals(case.code, failure.code, case.code)
            assertEquals(case.code, failure.message, case.code)
        }
    }

    @Test
    fun `collector uses right-bound query persists only accepted responses and keeps failed SLA series`() {
        val good = matrix("""{"instance":"node-a","zone":"test"}""", """[[1,"1"]]""").encodeToByteArray()
        val warningResponse =
            """{"status":"success","warnings":["http://user:password@example.invalid"],"data":{"resultType":"matrix","result":[{"metric":{"instance":"node-a"},"values":[[1,"100"]]}]}}"""
                .encodeToByteArray()
        val tooManyLabels =
            matrix(
                """{"instance":"node-a","l01":"1","l02":"2","l03":"3","l04":"4","l05":"5","l06":"6","l07":"7","l08":"8","l09":"9","l10":"10","l11":"11","l12":"12","l13":"13","l14":"14","l15":"15","l16":"16"}""",
                """[[1,"3"]]""",
            ).encodeToByteArray()
        val responses = ArrayDeque(listOf(good, warningResponse, tooManyLabels))
        val requests = mutableListOf<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/query_range") { exchange ->
            requests += queryParameters(exchange.requestURI.rawQuery)
            val body = responses.removeFirst()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val rule =
                ResourceRuleV1(
                    "memory-sla",
                    "memory",
                    "bytes",
                    ResourceOperator.GT,
                    BigDecimal("100"),
                    1,
                    ResourceRuleEffect.SLA,
                )
            val profile =
                SourceProfile(
                    id = "local",
                    sourceKind = SourceKind.PROMETHEUS,
                    transport = SourceTransport.DIRECT,
                    baseUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                    datasourceUid = null,
                    allowInsecureHttp = true,
                    governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 2_000, maxAttempts = 1),
                    queries =
                        listOf(
                            query("cpu", "rate(cpu_seconds_total[\$__interval])", "ratio", ResourceAggregation.INTERVAL_RATE),
                            query("memory", "memory_bytes[\$__interval]", "bytes", ResourceAggregation.INTERVAL_MEAN),
                            query("labels", "label_metric[\$__interval]", "count", ResourceAggregation.INTERVAL_MEAN),
                        ),
                    rules = listOf(rule),
                )

            val acquisition =
                PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(
                    SourceRequest("local", 0, 2_000, 1_000),
                    HASH,
                )

            assertEquals(
                mapOf(
                    "query" to "rate(cpu_seconds_total[1000ms])",
                    "start" to "1",
                    "end" to "2",
                    "step" to "1",
                ),
                requests.first(),
            )
            val series =
                requireNotNull(acquisition.snapshot)
                    .snapshot.series
                    .associateBy { it.id }
            assertEquals(listOf(BigDecimal("1"), null), series.getValue("cpu").values)
            assertEquals(listOf(null, null), series.getValue("memory").values)
            assertEquals(listOf(null, null), series.getValue("labels").values)
            assertEquals(listOf(rule), requireNotNull(acquisition.snapshot).snapshot.rules)
            assertEquals(
                "full",
                requireNotNull(acquisition.snapshot)
                    .snapshot.windows
                    .single()
                    .id,
            )
            assertEquals("PARTIAL", acquisition.evidence.string("status"))
            assertEquals("source_summary", acquisition.evidence.string("type"))
            assertEquals(
                0L,
                acquisition.evidence
                    .getValue("start_epoch_ms")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                2_000L,
                acquisition.evidence
                    .getValue("end_epoch_ms")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals(
                1_000L,
                acquisition.evidence
                    .getValue("step_ms")
                    .jsonPrimitive.content
                    .toLong(),
            )
            assertEquals("PARTIAL" to "MISSING_SAMPLES", acquisition.evidence.queryStatus("cpu"))
            assertEquals("FAILED" to "SOURCE_WARNINGS", acquisition.evidence.queryStatus("memory"))
            assertEquals("FAILED" to "RESOURCE_LIMIT_EXCEEDED", acquisition.evidence.queryStatus("labels"))
            assertEquals(
                64,
                acquisition.evidence
                    .query("cpu")
                    .string("expression_sha256")
                    .length,
            )
            assertEquals(
                3,
                acquisition.evidence
                    .getValue("request_count")
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(setOf("source-acquisition.json", "source-response-1.json"), acquisition.artifacts.keys)
            assertArrayEquals(good, acquisition.artifacts.getValue("source-response-1.json"))
            val summary = acquisition.artifacts.getValue("source-acquisition.json").decodeToString()
            assertFalse(summary.contains("password"))
            assertFalse(summary.contains(server.address.port.toString()))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `shared snapshot limits fail before any source request`() {
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/query_range") { exchange ->
            requestCount.incrementAndGet()
            val body = matrix("""{"instance":"node-a"}""", """[[61,"1"]]""").encodeToByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val profile =
                SourceProfile(
                    id = "local",
                    sourceKind = SourceKind.PROMETHEUS,
                    transport = SourceTransport.DIRECT,
                    baseUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                    datasourceUid = null,
                    allowInsecureHttp = true,
                    governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 2_000, maxAttempts = 1),
                    queries = listOf(query("cpu", "cpu[\$__interval]", "ratio", ResourceAggregation.INTERVAL_MEAN)),
                )

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(
                        SourceRequest("local", 0, 61_000, 61_000),
                        HASH,
                    )
                }

            assertEquals("INVALID_GRID", failure.message)
            assertEquals(0, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `aggregate snapshot byte limit degrades the source and retains raw artifacts`() {
        val labels =
            (0 until 16).joinToString(",", "{", "}") { index ->
                "\"${index.toString().padEnd(128, 'k')}\":\"${"v".repeat(512)}\""
            }
        val samples = (1..15_625).joinToString(",", "[", "]") { "[$it,\"-999999999999999999.999999999999\"]" }
        val body = matrix(labels, samples).encodeToByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/query_range") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val profile =
                SourceProfile(
                    id = "local",
                    sourceKind = SourceKind.PROMETHEUS,
                    transport = SourceTransport.DIRECT,
                    baseUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                    datasourceUid = null,
                    governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 2_000, maxAttempts = 1),
                    queries =
                        (0 until 32).map {
                            query(
                                "cpu-$it",
                                "avg_over_time(cpu[\$__interval])",
                                "ratio",
                                ResourceAggregation.INTERVAL_MEAN,
                            ).copy(labels = emptyMap())
                        },
                )
            val acquisition =
                PromqlSource(listOf(profile), SourceHttp(listOf(profile))).acquire(
                    SourceRequest("local", 0, 15_625_000, 1_000),
                    HASH,
                )
            assertEquals("FAILED", acquisition.evidence.string("status"))
            assertEquals("SOURCE_SNAPSHOT_LIMIT_EXCEEDED", acquisition.evidence.queryStatus("cpu-0").second)
            assertTrue(
                requireNotNull(acquisition.snapshot)
                    .snapshot.series
                    .all { series -> series.values.all { it == null } },
            )
            assertEquals(33, acquisition.artifacts.size)
            assertArrayEquals(body, acquisition.artifacts.getValue("source-response-32.json"))
        } finally {
            server.stop(0)
        }
    }

    private fun matrix(
        metric: String,
        values: String,
    ): String = """{"status":"success","data":{"resultType":"matrix","result":[{"metric":$metric,"values":$values}]}}"""

    private fun query(
        id: String,
        expression: String,
        unit: String,
        aggregation: ResourceAggregation,
    ) = SourceQuery(
        id = id,
        expression = expression,
        metric = id,
        unit = unit,
        entity = "node-a",
        role = ResourceRole.SYSTEM,
        aggregation = aggregation,
        labels = mapOf("instance" to "node-a"),
    )

    private fun queryParameters(raw: String): Map<String, String> =
        raw.split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), StandardCharsets.UTF_8)
        }

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun kotlinx.serialization.json.JsonObject.queryStatus(id: String): Pair<String, String?> {
        val query = query(id)
        return query.string("status") to query["reason"]?.jsonPrimitive?.content
    }

    private fun kotlinx.serialization.json.JsonObject.query(id: String): kotlinx.serialization.json.JsonObject =
        getValue("queries").jsonArray.map { it as kotlinx.serialization.json.JsonObject }.single { it.string("id") == id }

    private data class Case(
        val json: String,
        val code: String,
        val labels: Map<String, String> = emptyMap(),
        val pointCount: Int = 1,
    )

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}

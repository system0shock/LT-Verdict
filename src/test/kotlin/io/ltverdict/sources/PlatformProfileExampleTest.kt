package io.ltverdict.sources

import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.metricPackAnalysis
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class PlatformProfileExampleTest {
    private val example = Path.of("docs/contracts/sources/v1/platform-openshift-connections.example.json")

    @Test
    fun `the example parses and carries unique binding keys with a folded expression per query`() {
        val profiles = Files.newInputStream(example).use(::readSourceProfiles)
        val queries = profiles.flatMap { it.queries }

        assertTrue(profiles.size in 1..16)
        assertTrue(profiles.all { it.queries.size in 1..64 })
        assertTrue(profiles.all { it.rules.isEmpty() }, "platform rules live in the policy, not in the profile")
        assertEquals(queries.size, queries.map { Triple(it.metric, it.entity, it.role) }.toSet().size)
        queries.forEach { query ->
            assertEquals(ResourceRole.SYSTEM, query.role)
            assertEquals(mapOf("namespace" to "shop"), query.labels)
            assertTrue("\$__interval" in query.expression)
            assertTrue(
                listOf("max by (namespace)", "(sum by (namespace)", "(max by (namespace)").any(query.expression::startsWith),
                "the outermost operator must fold pods and containers: ${query.id}",
            )
        }
    }

    @Test
    fun `the peak example labels interval_max exactly where the expression takes a maximum`() {
        val peak = Path.of("docs/contracts/sources/v1/platform-openshift-peak-connections.example.json")
        val queries = Files.newInputStream(peak).use(::readSourceProfiles).flatMap { it.queries }

        assertTrue(queries.any { it.aggregation == ResourceAggregation.INTERVAL_MAX })
        queries.forEach { query ->
            assertEquals(query.aggregation == ResourceAggregation.INTERVAL_MAX, "max_over_time" in query.expression, query.id)
        }
    }

    @Test
    fun `the metric pack recognises every metric of the example`() {
        val profiles = Files.newInputStream(example).use(::readSourceProfiles)
        val metrics =
            profiles
                .flatMap { it.queries }
                .map { it.metric }
                .toSet()
                .sorted()
        val result =
            buildJsonObject {
                put(
                    "evidence",
                    buildJsonArray {
                        metrics.forEachIndexed { index, metric ->
                            add(
                                buildJsonObject {
                                    put("id", "summary-$index")
                                    put("type", "resource_summary")
                                    put("series_id", "s$index")
                                    put("metric", metric)
                                    put("reasons", buildJsonArray {})
                                },
                            )
                        }
                    },
                )
                put("findings", buildJsonArray {})
            }

        val recognised =
            metricPackAnalysis(result)
                .getValue("packs")
                .jsonArray
                .flatMap { it.jsonObject.getValue("series_ids").jsonArray }
                .map { it.jsonPrimitive.content }
                .toSet()

        assertEquals(metrics.indices.map { "s$it" }.toSet(), recognised)
    }

    @Test
    fun `one series per query becomes a snapshot and an unfolded response is refused`() {
        val folded =
            """{"status":"success","data":{"resultType":"matrix","result":[""" +
                """{"metric":{"namespace":"shop"},"values":[[1767225601,"0.5"]]}]}}"""
        val unfolded = """{"status":"success","data":{"resultType":"matrix","result":[
            {"metric":{"namespace":"shop","pod":"a1"},"values":[[1767225601,"0.5"]]},
            {"metric":{"namespace":"shop","pod":"a2"},"values":[[1767225601,"0.6"]]}]}}"""
        val profiles = Files.newInputStream(example).use(::readSourceProfiles)
        val queries = profiles.flatMap { it.queries }

        fun acquire(body: String): SourceAcquisition {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/v1/query_range") { exchange ->
                val bytes = body.encodeToByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val local =
                    profiles.map {
                        it.copy(
                            baseUrl = URI("http://127.0.0.1:${server.address.port}"),
                            governor = it.governor.copy(requestsPerSecond = 1_000.0, burst = 1_000),
                        )
                    }
                val request =
                    SourceRequest(
                        local.first().id,
                        1767225600000,
                        1767225601000,
                        1000,
                        additionalProfileIds = local.drop(1).map { it.id },
                    )
                return PromqlSource(local, SourceHttp(local)).acquire(request, "a".repeat(64))
            } finally {
                server.stop(0)
            }
        }

        val snapshot = requireNotNull(acquire(folded).snapshot).snapshot
        assertEquals(queries.size, snapshot.series.size)
        assertEquals(queries.map { it.entity }.toSet(), snapshot.series.map { it.entity }.toSet())

        val refused = acquire(unfolded)
        assertTrue(refused.evidence.toString().contains("AMBIGUOUS_SERIES"))
    }
}

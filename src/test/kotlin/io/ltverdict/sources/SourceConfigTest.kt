package io.ltverdict.sources

import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceRuleEffect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SourceConfigTest {
    @Test
    fun `v2 connections accept PostgreSQL without fabricating an HTTP profile`() {
        val config = """{"schema_version":"source-connections.v2","connections":[{
            "id":"pg","source_kind":"postgresql","source_database_id":"orders-test",
            "host":"db.example","database":"orders","username_env":"PG_USER","password_env":"PG_PASSWORD",
            "tables":[{"schema":"public","table":"orders","columns":["id","state"],"key":["id"]}]}]}"""
        assertTrue(readSourceProfiles(config.byteInputStream()).isEmpty())
        val postgres = readSourceConnections(config.byteInputStream()).postgres.single()
        assertEquals("orders-test", postgres.sourceDatabaseId)
        assertEquals(5432, postgres.port)
        assertEquals(listOf("id"), postgres.tables.single().key)
        listOf(
            config.replace("source-connections.v2", "source-connections.v1"),
            config.replace("\"host\":\"db.example\"", "\"host\":\"jdbc:postgresql://db\""),
            config.replace("\"database\":", "\"sql\":\"SELECT 1\",\"database\":"),
            config.replace("\"database\":", "\"port\":0,\"database\":"),
            config.replace("\"username_env\":\"PG_USER\"", "\"username_env\":\"literal user\""),
        ).forEach { invalid ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) {
                    readSourceProfiles(invalid.byteInputStream())
                }.message,
            )
        }
    }

    @Test
    fun `v2 source selection normalizes profiles and rejects duplicates or oversized sets`() {
        val request = """{"schema_version":"source-request.v2","profile_ids":["z","a"],
            "start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000}"""
        assertEquals("a", readSourceRequest(request.byteInputStream()).profileId)
        listOf(
            request.replace("[\"z\",\"a\"]", "[\"a\",\"a\"]"),
            request.replace("[\"z\",\"a\"]", "[]"),
            request.replace("[\"z\",\"a\"]", (1..17).joinToString(",", "[", "]") { "\"p$it\"" }),
            request.replace("[\"z\",\"a\"]", "[1]"),
            request.replace("\"profile_ids\":", "\"profile_id\":\"z\",\"profile_ids\":"),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { readSourceRequest(invalid.byteInputStream()) }
        }
    }

    @Test
    fun `OpenSearch profile accepts bounded mapping and rejects metric fields or proxy transport`() {
        val example =
            java.nio.file.Files
                .readString(
                    java.nio.file.Path
                        .of("docs/contracts/sources/v1/opensearch-connections.example.json"),
                )
        val profile = readSourceProfiles(example.byteInputStream()).single()
        assertEquals(SourceKind.OPENSEARCH, profile.sourceKind)
        assertEquals(listOf("application-errors-*"), requireNotNull(profile.openSearch).indices)
        assertTrue(profile.queries.isEmpty())
        listOf(
            example.replace("\"direct\"", "\"grafana_proxy\""),
            example.replace("\"opensearch\": {", "\"queries\":[],\"opensearch\": {"),
            example.replace("\"opensearch\": {", "\"rules\":[],\"opensearch\": {"),
            example.replace("\"opensearch\": {", "\"database\":\"db\",\"opensearch\": {"),
            example.replace("\"opensearch\": {", "\"datasource_uid\":\"uid\",\"opensearch\": {"),
            example.replace("\"samples_per_group\": 2", "\"samples_per_group\": 6"),
            example.replace("\"application-errors-*\"", "\"**\""),
            example.replace("\"source_kind\": \"opensearch\"", "\"source_kind\": \"prometheus\""),
        ).forEach { invalid ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) {
                    readSourceProfiles(invalid.byteInputStream())
                }.message,
            )
        }
    }

    @Test
    fun `influxdb profile accepts database and required time placeholders`() {
        val profile =
            readSourceProfiles(
                """
                {
                  "schema_version":"source-connections.v1",
                  "connections":[{
                    "id":"influx-main",
                    "source_kind":"influxdb",
                    "transport":"direct",
                    "base_url":"https://influx.example.test",
                    "database":"metrics",
                    "auth":{"type":"token","token_env":"INFLUX_TOKEN"},
                    "queries":[{
                      "id":"cpu",
                      "expression":"SELECT mean(\"cpu\") AS \"value\" FROM \"host\" WHERE time >= ${'$'}__start AND time < ${'$'}__end GROUP BY time(${'$'}__interval, ${'$'}__offset) fill(null)",
                      "metric":"cpu_used",
                      "unit":"ratio",
                      "entity":"host-a",
                      "role":"system",
                      "aggregation":"interval_mean"
                    }]
                  }]
                }
                """.trimIndent().byteInputStream(),
            ).single()

        assertEquals(SourceKind.INFLUXDB, profile.sourceKind)
        assertEquals("metrics", profile.database)
        assertEquals(SourceAuth.Token("INFLUX_TOKEN"), profile.auth)
    }

    @Test
    fun `database is required for influxdb and forbidden for promql profiles`() {
        val missingDatabase = influxConnections().replace("\"database\":\"metrics\",", "")
        val emptyDatabase = influxConnections().replace("\"database\":\"metrics\"", "\"database\":\"\"")
        val oversizedDatabase = influxConnections().replace("\"metrics\"", "\"${"d".repeat(129)}\"")
        val promqlDatabase =
            """{"schema_version":"source-connections.v1","connections":[${
                minimalConnection("prom-with-database").replace("\"queries\":", "\"database\":\"metrics\",\"queries\":")
            }]}"""

        listOf(missingDatabase, emptyDatabase, oversizedDatabase, promqlDatabase).forEach { json ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(json.byteInputStream()) }.message,
            )
        }
    }

    @Test
    fun `influxdb queries require window placeholders and reject unsafe statements`() {
        val invalid =
            listOf(
                influxConnections().replace("${'$'}__start", "1000ms"),
                influxConnections().replace("${'$'}__end", "2000ms"),
                influxConnections().replace("${'$'}__interval", "1000ms"),
                influxConnections().replace("${'$'}__start", "${'$'}__start_extra"),
                influxConnections().replace("${'$'}__end", "${'$'}__end_extra"),
                influxConnections().replace("${'$'}__interval", "${'$'}__interval_extra"),
                influxConnections().replace("${'$'}__offset", "${'$'}__offset_extra"),
                influxConnections().replace(" fill(null)", "; SELECT value FROM other"),
                influxConnections().replace("SELECT mean", "SELECT mean INTO archive"),
                influxConnections().replace(" fill(null)", " -- unsafe"),
                influxConnections().replace(" fill(null)", " /* unsafe */"),
                influxConnections().replace(" fill(null)", " ${'$'}__unknown"),
                influxConnections().replace("fill(null)", "fill(0)"),
                influxConnections().replace("fill(null)", "fill(previous)"),
                influxConnections().replace("fill(null)", "fill(linear)"),
                influxConnections().replace("fill(null)", "fill(999)"),
                influxConnections().replace("fill(null)", "fill()"),
                influxConnections().replace("SELECT mean", "DELETE mean"),
                influxConnections().replace(" AS \\\"value\\\"", ""),
            )

        invalid.forEach { json ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(json.byteInputStream()) }.message,
            )
        }
    }

    @Test
    fun `gap preserving fill modes stay valid`() {
        listOf("fill(null)", "fill(none)", "FILL( None )").forEach { fill ->
            val json = influxConnections().replace("fill(null)", fill)

            assertEquals(1, readSourceProfiles(json.byteInputStream()).size)
        }
    }

    @Test
    fun `token credentials over HTTP require explicit opt in`() {
        val insecure = influxConnections().replace("https://influx.example.test", "http://influx.example.test")
        val optedIn = insecure.replace("\"database\":\"metrics\",", "\"database\":\"metrics\",\"allow_insecure_http\":true,")

        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(insecure.byteInputStream()) }.message,
        )
        assertEquals(SourceAuth.Token("INFLUX_TOKEN"), readSourceProfiles(optedIn.byteInputStream()).single().auth)
    }

    @Test
    fun `profiles parse strict bounded query rule auth and governor contracts`() {
        val profiles = readSourceProfiles(validConnections().byteInputStream())

        assertEquals(2, profiles.size)
        val direct = profiles[0]
        assertEquals("prom-main", direct.id)
        assertEquals(SourceKind.PROMETHEUS, direct.sourceKind)
        assertEquals(SourceTransport.DIRECT, direct.transport)
        assertEquals("https://example.test:8443/tenant/", direct.baseUrl.toString())
        assertNull(direct.datasourceUid)
        assertEquals(SourceAuth.Bearer("PROM_TOKEN"), direct.auth)
        assertEquals(SourceGovernor(0.25, 2, 3, 1_500, 2, false, 7), direct.governor)
        assertEquals(
            SourceQuery(
                "requests",
                "rate(http_requests_total[${'$'}__interval])",
                "requests",
                "requests_per_second",
                "api",
                ResourceRole.SYSTEM,
                ResourceAggregation.INTERVAL_RATE,
                mapOf("job" to "api"),
            ),
            direct.queries.single(),
        )
        assertEquals("requests", direct.rules.single().seriesId)
        assertEquals(BigDecimal("10.5"), direct.rules.single().threshold)
        assertEquals(ResourceOperator.GT, direct.rules.single().operator)
        assertEquals(ResourceRuleEffect.SLA, direct.rules.single().effect)

        val proxy = profiles[1]
        assertEquals(SourceKind.VICTORIA_METRICS, proxy.sourceKind)
        assertEquals(SourceTransport.GRAFANA_PROXY, proxy.transport)
        assertEquals("vm-main", proxy.datasourceUid)
        assertEquals(SourceAuth.None, proxy.auth)
        assertEquals(SourceGovernor(), proxy.governor)
        assertTrue(proxy.rules.isEmpty())
    }

    @Test
    fun `profiles reject unsafe urls plaintext credentials and invalid env references without echoing input`() {
        val cases =
            listOf(
                validConnections().replace("https://EXAMPLE.test:8443/tenant/", "file:///tmp/top-secret"),
                validConnections().replace("https://EXAMPLE.test:8443/tenant/", "https://user:top-secret@example.test/"),
                validConnections().replace("https://EXAMPLE.test:8443/tenant/", "https://example.test/#top-secret"),
                validConnections()
                    .replace("https://EXAMPLE.test:8443/tenant/", "http://example.test/")
                    .replace("\"allow_insecure_http\":true", "\"allow_insecure_http\":false"),
                validConnections().replace("https://EXAMPLE.test:8443/tenant/", "https://example.test:0/"),
                validConnections().replace("https://EXAMPLE.test:8443/tenant/", "https://example.test:99999/"),
                validConnections().replace("PROM_TOKEN", "INVALID-TOP-SECRET"),
                validConnections().replace("\"token_env\":\"PROM_TOKEN\"", "\"token\":\"top-secret\""),
            )

        cases.forEach { json ->
            val failure = assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(json.byteInputStream()) }
            assertEquals("SOURCE_CONFIG_INVALID", failure.message)
            assertTrue("top-secret" !in failure.toString())
        }
    }

    @Test
    fun `profiles reject duplicate ids excessive connections and query mappings outside the declared contract`() {
        val duplicateIds = validConnections().replace("\"id\":\"vm-proxy\"", "\"id\":\"prom-main\"")
        val noInterval = validConnections().replace("rate(http_requests_total[${'$'}__interval])", "http_requests_total")
        val tooMany =
            """{"schema_version":"source-connections.v1","connections":[${
                List(17) { index -> minimalConnection("p$index") }.joinToString(",")
            }]}"""

        listOf(duplicateIds, noInterval, tooMany).forEach { json ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(json.byteInputStream()) }.message,
            )
        }
        assertEquals(
            "SOURCE_CONFIG_TOO_LARGE",
            assertThrows(IllegalArgumentException::class.java) {
                readSourceProfiles(ByteArray(1024 * 1024 + 1).inputStream())
            }.message,
        )
    }

    @Test
    fun `source request accepts an even bounded grid and rejects malformed or excessive grids`() {
        val request =
            readSourceRequest(
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000}"""
                    .byteInputStream(),
            )

        assertEquals(SourceRequest("prom-main", 1_000, 3_000, 1_000), request)

        val invalid =
            listOf(
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":1000,"end_epoch_ms":2500,"step_ms":1000}""",
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":999}""",
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":0,"end_epoch_ms":100001000,"step_ms":1000}""",
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000,"unknown":"top-secret"}""",
            )
        invalid.forEach { json ->
            val failure = assertThrows(IllegalArgumentException::class.java) { readSourceRequest(json.byteInputStream()) }
            assertEquals("SOURCE_REQUEST_INVALID", failure.message)
            assertTrue("top-secret" !in failure.toString())
        }

        assertEquals(
            "SOURCE_REQUEST_TOO_LARGE",
            assertThrows(IllegalArgumentException::class.java) {
                readSourceRequest(ByteArray(16 * 1024 + 1).inputStream())
            }.message,
        )
    }

    private fun validConnections(): String =
        """
        {
          "schema_version":"source-connections.v1",
          "connections":[
            {
              "id":"prom-main",
              "source_kind":"prometheus",
              "transport":"direct",
              "base_url":"https://EXAMPLE.test:8443/tenant/",
              "auth":{"type":"bearer","token_env":"PROM_TOKEN"},
              "allow_insecure_http":true,
              "governor":{
                "requests_per_second":0.25,
                "burst":2,
                "max_concurrent":3,
                "timeout_ms":1500,
                "max_requests_per_run":7,
                "max_attempts":2,
                "honor_retry_after":false
              },
              "queries":[{
                "id":"requests",
                "expression":"rate(http_requests_total[${'$'}__interval])",
                "metric":"requests",
                "unit":"requests_per_second",
                "entity":"api",
                "role":"system",
                "aggregation":"interval_rate",
                "labels":{"job":"api"}
              }],
              "rules":[{
                "id":"requests-high",
                "series_id":"requests",
                "unit":"requests_per_second",
                "operator":"gt",
                "threshold":10.5,
                "min_consecutive_cells":2,
                "effect":"sla"
              }]
            },
            {
              "id":"vm-proxy",
              "source_kind":"victoria_metrics",
              "transport":"grafana_proxy",
              "base_url":"https://grafana.example.test/base",
              "datasource_uid":"vm-main",
              "queries":[{
                "id":"cpu",
                "expression":"avg_over_time(cpu[${'$'}__interval])",
                "metric":"cpu_used",
                "unit":"ratio",
                "entity":"host-a",
                "role":"system",
                "aggregation":"interval_mean"
              }]
            }
          ]
        }
        """.trimIndent()

    private fun minimalConnection(id: String): String =
        """{"id":"$id","source_kind":"prometheus","transport":"direct","base_url":"https://example.test","queries":[{"id":"q","expression":"rate(x[${'$'}__interval])","metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_rate"}]}"""

    private fun influxConnections(): String =
        """
        {
          "schema_version":"source-connections.v1",
          "connections":[{
            "id":"influx-main",
            "source_kind":"influxdb",
            "transport":"direct",
            "base_url":"https://influx.example.test",
            "database":"metrics",
            "auth":{"type":"token","token_env":"INFLUX_TOKEN"},
            "queries":[{
              "id":"cpu",
              "expression":"SELECT mean(\"cpu\") AS \"value\" FROM \"host\" WHERE time >= ${'$'}__start AND time < ${'$'}__end GROUP BY time(${'$'}__interval, ${'$'}__offset) fill(null)",
              "metric":"cpu_used",
              "unit":"ratio",
              "entity":"host-a",
              "role":"system",
              "aggregation":"interval_mean"
            }]
          }]
        }
        """.trimIndent()
}

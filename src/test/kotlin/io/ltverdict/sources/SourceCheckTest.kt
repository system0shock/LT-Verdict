package io.ltverdict.sources

import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path

class SourceCheckTest {
    // Nothing listens on this address: an offline check must never try to connect.
    private val base = URI("http://127.0.0.1:9")

    @Test
    fun `a complete profile passes every offline check without touching the network`() {
        val report = check(listOf(profile()), env = { "token-value-123456" })
        val entry =
            report
                .getValue("profiles")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("source-check.v1", report.string("schema_version"))
        assertEquals("main", entry.string("profile_id"))
        assertEquals("1", entry.string("query_count"))
        assertEquals(
            mapOf("config" to "OK", "url" to "OK", "credentials" to "OK", "tls" to "SKIPPED", "rules" to "OK", "governor" to "OK"),
            checks(entry),
        )
        assertEquals("bearer", check(entry, "credentials").string("auth_kind"))
    }

    @Test
    fun `missing and malformed credentials fail with a code and no names`() {
        val unavailable = check(listOf(profile()), env = { null })
        assertEquals(
            "SOURCE_AUTH_UNAVAILABLE",
            check(
                unavailable
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject,
                "credentials",
            ).string("code"),
        )
        val invalid = check(listOf(profile()), env = { "bad\u0007value" })
        assertEquals(
            "SOURCE_AUTH_INVALID",
            check(
                invalid
                    .getValue("profiles")
                    .jsonArray
                    .single()
                    .jsonObject,
                "credentials",
            ).string("code"),
        )
        listOf(unavailable, invalid).forEach {
            assertFalse(it.toString().contains("PROBE_TOKEN"))
            assertFalse(it.toString().contains("127.0.0.1"))
        }
    }

    @Test
    fun `a broken address, a strict governor and a bad TLS file are named`() {
        val broken = profile(id = "broken").copy(baseUrl = URI("ftp://example.invalid"))
        val strict = profile(id = "strict").copy(governor = SourceGovernor(requestsPerSecond = 0.05), queries = List(21) { query("q$it") })
        val tls = profile(id = "tls").copy(tls = SourceTls(Path.of("/nonexistent/ca.pem"), null, null))
        val report = check(listOf(broken, strict, tls), env = { "x".repeat(8) })
        val byId =
            report
                .getValue("profiles")
                .jsonArray
                .map { it.jsonObject }
                .associateBy { it.string("profile_id") }
        assertEquals("SOURCE_PROFILE_INVALID", check(byId.getValue("broken"), "url").string("code"))
        assertEquals("WARN", check(byId.getValue("strict"), "governor").string("status"))
        assertEquals("GOVERNOR_STRICT", check(byId.getValue("strict"), "governor").string("code"))
        assertEquals("SOURCE_TLS_CONFIG_INVALID", check(byId.getValue("tls"), "tls").string("code"))
        assertFalse(report.toString().contains("nonexistent"))
    }

    @Test
    fun `an expired client certificate is reported`() {
        val tls = SourceTls(TestPki.ca1Pem, TestPki.clientExpired, "PKI_PASSWORD")
        val report =
            check(listOf(profile().copy(baseUrl = URI("https://127.0.0.1:9"), tls = tls)), env = {
                if (it ==
                    "PKI_PASSWORD"
                ) {
                    TestPki.password
                } else {
                    "t"
                }
            })
        val entry =
            report
                .getValue("profiles")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("SOURCE_TLS_CLIENT_CERT_EXPIRED", check(entry, "tls").string("code"))
    }

    @Test
    fun `a selected profile limits the report and postgres profiles are listed`() {
        val postgres = PostgresProfile("pg", "db", "127.0.0.1", 5432, "app", "PG_USER", "PG_PASSWORD")
        val report = check(listOf(profile(), profile(id = "other")), listOf(postgres), env = { null })
        assertEquals(listOf("main", "other", "pg"), report.getValue("profiles").jsonArray.map { it.jsonObject.string("profile_id") })
        val pg =
            report
                .getValue("profiles")
                .jsonArray
                .map { it.jsonObject }
                .single { it.string("profile_id") == "pg" }
        assertEquals("postgresql", pg.string("source_kind"))
        assertEquals(JsonNull, pg.getValue("transport"))
        assertEquals("SOURCE_AUTH_UNAVAILABLE", check(pg, "credentials").string("code"))
        val only = check(listOf(profile(), profile(id = "other")), only = "other", env = { null })
        assertEquals(listOf("other"), only.getValue("profiles").jsonArray.map { it.jsonObject.string("profile_id") })
    }

    @Test
    fun `the report conforms to the published schema`() {
        val postgres = PostgresProfile("pg", "db", "127.0.0.1", 5432, "app", "PG_USER", "PG_PASSWORD")
        val report =
            check(
                listOf(
                    profile(),
                    profile(id = "strict").copy(
                        governor = SourceGovernor(requestsPerSecond = 0.05),
                        queries =
                            List(21) {
                                query("q$it")
                            },
                    ),
                ),
                listOf(postgres),
                env = { null },
            )
        assertEquals(emptyList<String>(), SchemaLite.errors(report, SchemaLite.schema("source-check")), report.toString())
    }

    private fun check(
        http: List<SourceProfile>,
        postgres: List<PostgresProfile> = emptyList(),
        only: String? = null,
        env: (String) -> String?,
    ): JsonObject = checkSourceProfiles(SourceConnections(http, postgres), only, env) { null }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun checks(entry: JsonObject): Map<String, String> =
        entry.getValue("checks").jsonArray.associate { it.jsonObject.string("id") to it.jsonObject.string("status") }

    private fun check(
        entry: JsonObject,
        id: String,
    ): JsonObject =
        entry
            .getValue("checks")
            .jsonArray
            .map { it.jsonObject }
            .single { it.string("id") == id }

    private fun query(id: String) =
        SourceQuery(
            id,
            "avg_over_time(cpu[\$__interval])",
            "cpu_used",
            "ratio",
            "host",
            ResourceRole.SYSTEM,
            ResourceAggregation.INTERVAL_MEAN,
            emptyMap(),
        )

    private fun profile(id: String = "main") =
        SourceProfile(
            id,
            SourceKind.PROMETHEUS,
            SourceTransport.DIRECT,
            base,
            null,
            SourceAuth.Bearer("PROBE_TOKEN"),
            true,
            SourceGovernor(),
            listOf(query("cpu")),
        )
}

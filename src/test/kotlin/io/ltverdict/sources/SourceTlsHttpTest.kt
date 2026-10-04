package io.ltverdict.sources

import io.ltverdict.integrations.grafana.GrafanaPanelRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceTlsHttpTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `trusted server accepts the configured client certificate`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, tls(TestPki.client1))
            assertArrayEquals(TestPki.RESPONSE.encodeToByteArray(), http(profile).get(profile, emptyMap(), SourceBudget()))
            assertEquals(1, server.subjects.size)
            assertTrue(server.subjects.single().contains("client-one"))
        }
    }

    @Test
    fun `profiles in one SourceHttp present separate client certificates`() {
        TestPki.server().use { first ->
            TestPki.server().use { second ->
                val one = TestPki.profile(first.port, tls(TestPki.client1), id = "one")
                val two = TestPki.profile(second.port, tls(TestPki.client2), id = "two")
                val http = http(one, two)
                http.get(one, emptyMap(), SourceBudget())
                http.get(two, emptyMap(), SourceBudget())
                assertEquals(1, first.subjects.size)
                assertEquals(1, second.subjects.size)
                assertTrue(first.subjects.single().contains("client-one"))
                assertTrue(second.subjects.single().contains("client-two"))
            }
        }
    }

    @Test
    fun `server rejects a profile without a client certificate`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, SourceTls(TestPki.ca1Pem, null, null))
            val failure = assertFailure(profile)
            // Observed on JDK 21 with TLS 1.3: the server drops the connection after the client handshake and the client sees
            // IOException "header parser received no bytes" (EOF, no SSLException), i.e. SOURCE_HTTP_ERROR; ADR 0025 D6 does not
            // promise the TLS code here and no message heuristics are used. Both outcomes are accepted.
            assertTrue(failure.code in setOf("SOURCE_TLS_HANDSHAKE_FAILED", "SOURCE_HTTP_ERROR"))
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `server rejects a client certificate from another CA`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, tls(TestPki.clientOther))
            val failure = assertFailure(profile)
            // Observed on JDK 21 with TLS 1.3: the server drops the connection after the client handshake and the client sees
            // IOException "header parser received no bytes" (EOF, no SSLException), i.e. SOURCE_HTTP_ERROR; ADR 0025 D6 does not
            // promise the TLS code here and no message heuristics are used. Both outcomes are accepted.
            assertTrue(failure.code in setOf("SOURCE_TLS_HANDSHAKE_FAILED", "SOURCE_HTTP_ERROR"))
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `untrusted server certificate is a TLS handshake failure`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, SourceTls(null, TestPki.client1, PASSWORD_ENV))
            assertEquals("SOURCE_TLS_HANDSHAKE_FAILED", assertFailure(profile).code)
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `server certificate without the requested IP SAN is a TLS handshake failure`() {
        TestPki.server(TestPki.wrongSanKeystore).use { server ->
            val profile = TestPki.profile(server.port, tls(TestPki.client1))
            assertEquals("SOURCE_TLS_HANDSHAKE_FAILED", assertFailure(profile).code)
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `expired and not yet valid client certificates fail before a request`() {
        TestPki.server().use { server ->
            assertEquals("SOURCE_TLS_CLIENT_CERT_EXPIRED", assertFailure(TestPki.profile(server.port, tls(TestPki.clientExpired))).code)
            assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(TestPki.profile(server.port, tls(TestPki.clientNotYet))).code)
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `invalid TLS material and environment fail with configuration code`() {
        TestPki.server().use { server ->
            val missing = directory.resolve("missing")
            val oversized = Files.write(directory.resolve("oversized"), ByteArray(1_048_577))
            val tlsCases =
                linkedMapOf(
                    "missing CA" to SourceTls(missing, TestPki.client1, PASSWORD_ENV),
                    "missing keystore" to SourceTls(TestPki.ca1Pem, missing, PASSWORD_ENV),
                    "JKS keystore" to tls(TestPki.jks),
                    "empty keystore" to tls(TestPki.empty),
                    "garbage keystore" to tls(TestPki.garbage),
                    "garbage CA" to SourceTls(TestPki.garbage, TestPki.client1, PASSWORD_ENV),
                    "no key entry" to tls(TestPki.noKey),
                    "directory as CA" to SourceTls(directory, TestPki.client1, PASSWORD_ENV),
                    "oversized keystore" to tls(oversized),
                    "oversized CA" to SourceTls(oversized, TestPki.client1, PASSWORD_ENV),
                )
            tlsCases.forEach { (case, tls) ->
                assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(TestPki.profile(server.port, tls)).code, case)
            }
            val profile = TestPki.profile(server.port, tls(TestPki.client1))
            assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(profile) { TestPki.password + "-wrong" }.code)
            assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(profile) { null }.code)
            assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(profile) { "" }.code)
            assertTrue(server.subjects.isEmpty())
        }
    }

    @Test
    fun `global hostname verification bypass is refused unless false`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, tls(TestPki.client1))
            for (value in listOf("", "true")) {
                val http =
                    SourceHttp(listOf(profile), { name -> if (name == HOSTNAME_PROPERTY) value else null }) { name ->
                        if (name == PASSWORD_ENV) TestPki.password else null
                    }
                assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(http, profile).code)
            }
            val http =
                SourceHttp(listOf(profile), { name -> if (name == HOSTNAME_PROPERTY) "false" else null }) { name ->
                    if (name == PASSWORD_ENV) TestPki.password else null
                }
            assertArrayEquals(TestPki.RESPONSE.encodeToByteArray(), http.get(profile, emptyMap(), SourceBudget()))
            assertEquals(1, server.subjects.size)
        }
    }

    @Test
    fun `TLS material is loaded lazily and a failed load is not cached`() {
        TestPki.server().use { server ->
            val ca = directory.resolve("late-ca.pem")
            val key = directory.resolve("late-client.p12")
            val profile = TestPki.profile(server.port, SourceTls(ca, key, PASSWORD_ENV))
            val http = http(profile)
            assertEquals("SOURCE_TLS_CONFIG_INVALID", assertFailure(http, profile).code)
            Files.copy(TestPki.ca1Pem, ca)
            Files.copy(TestPki.client1, key)
            assertArrayEquals(TestPki.RESPONSE.encodeToByteArray(), http.get(profile, emptyMap(), SourceBudget()))
            assertEquals(1, server.subjects.size)
        }
    }

    @Test
    fun `TLS failure reaches source summary while a plain profile still works`() {
        OnlineSourceFixture().use { plainServer ->
            val missing = directory.resolve("missing-client.p12")
            val secure = TestPki.profile(1, SourceTls(TestPki.ca1Pem, missing, PASSWORD_ENV), id = "secure")
            val plain = readSourceProfiles(plainServer.profilesJson().byteInputStream()).single().copy(id = "plain")
            val http = http(secure, plain)
            val source = PromqlSource(listOf(secure, plain), http)
            val request = SourceRequest("secure", 1767225600000, 1767225601000, 1000)
            val failed = source.acquire(request, "a".repeat(64))
            assertEquals(
                "source_summary",
                failed.evidence
                    .getValue("type")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "FAILED",
                failed.evidence
                    .query()
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "SOURCE_TLS_CONFIG_INVALID",
                failed.evidence
                    .query()
                    .getValue("reason")
                    .jsonPrimitive.content,
            )
            val working = source.acquire(request.copy(profileId = "plain"), "a".repeat(64))
            assertTrue(
                working.evidence
                    .query()
                    .getValue("status")
                    .jsonPrimitive.content != "FAILED",
            )
            assertEquals(1, plainServer.requests.get())
        }
    }

    @Test
    fun `TLS failure does not expose password or keystore path`() {
        val key = TestPki.client1
        val profile = TestPki.profile(1, SourceTls(TestPki.ca1Pem, key, PASSWORD_ENV))
        val http = SourceHttp(listOf(profile)) { name -> if (name == PASSWORD_ENV) TestPki.password + "-wrong" else null }
        val failure = assertFailure(http, profile)
        val acquisition =
            PromqlSource(listOf(profile), http)
                .acquire(SourceRequest(profile.id, 1767225600000, 1767225601000, 1000), "a".repeat(64))
        val exposed =
            listOfNotNull(failure.toString(), failure.message, acquisition.evidence.toString(), profile.toString()) +
                acquisition.artifacts.values.map { it.decodeToString() }
        assertTrue(exposed.none { TestPki.password in it })
        assertTrue(exposed.filterNot { it == profile.toString() }.none { key.toString() in it })
    }

    @Test
    fun `Grafana panel render uses the same mTLS profile`() {
        TestPki.server().use { server ->
            val profile = TestPki.profile(server.port, tls(TestPki.client1), SourceTransport.GRAFANA_PROXY)
            val body = http(profile).getGrafanaPanel(profile, GrafanaPanelRequest("dash", 1, 1000, 2000))
            assertArrayEquals(TestPki.RESPONSE.encodeToByteArray(), body)
            assertEquals(1, server.subjects.size)
            assertTrue(server.subjects.single().contains("client-one"))
        }
    }

    private fun tls(key: Path): SourceTls = SourceTls(TestPki.ca1Pem, key, PASSWORD_ENV)

    private fun http(vararg profiles: SourceProfile): SourceHttp =
        SourceHttp(profiles.toList()) { name -> if (name == PASSWORD_ENV) TestPki.password else null }

    private fun assertFailure(
        profile: SourceProfile,
        environment: (String) -> String? = { name -> if (name == PASSWORD_ENV) TestPki.password else null },
    ): SourceHttpFailure = assertFailure(SourceHttp(listOf(profile), environment = environment), profile)

    private fun assertFailure(
        http: SourceHttp,
        profile: SourceProfile,
    ): SourceHttpFailure = assertThrows(SourceHttpFailure::class.java) { http.get(profile, emptyMap(), SourceBudget()) }

    private fun kotlinx.serialization.json.JsonObject.query() = getValue("queries").jsonArray.single().jsonObject

    @Test
    fun `cached client chain is re-checked against the clock on every request`() {
        val material =
            sourceTlsMaterial(
                tls(TestPki.client1),
                { name -> if (name == PASSWORD_ENV) TestPki.password else null },
                { null },
            )
        material.checkClientValidity()
        val failure =
            assertThrows(SourceHttpFailure::class.java) {
                material.checkClientValidity {
                    java.time.Instant
                        .now()
                        .plus(java.time.Duration.ofDays(36_500))
                }
            }
        assertEquals("SOURCE_TLS_CLIENT_CERT_EXPIRED", failure.code)
    }

    private companion object {
        const val PASSWORD_ENV = "LTV_GRAFANA_MTLS_PASSWORD"
        const val HOSTNAME_PROPERTY = "jdk.internal.httpclient.disableHostnameVerification"
    }
}

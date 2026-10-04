package io.ltverdict.sources

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SourceTlsConfigTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `tls paths and password environment parse in every document version and transport`() {
        val ca = directory.resolve("ca.pem")
        val key = directory.resolve("client.p12")
        for (version in 1..3) {
            for (transport in listOf("direct", "grafana_proxy")) {
                val profile =
                    readSourceProfiles(
                        connections(
                            version,
                            transport,
                            """"ca_file":${json(
                                ca,
                            )},"client_keystore_file":${json(key)},"client_keystore_password_env":"LTV_TLS_PASSWORD"""",
                        ).byteInputStream(),
                    ).single()
                assertEquals(SourceTls(ca, key, "LTV_TLS_PASSWORD"), profile.tls)
            }
        }
    }

    @Test
    fun `ca only and keystore with environment are independent valid forms`() {
        val ca = directory.resolve("ca.pem")
        val key = directory.resolve("client.p12")
        val caOnly = readSourceProfiles(connections(1, "direct", """"ca_file":${json(ca)}""").byteInputStream()).single()
        val keyOnly =
            readSourceProfiles(
                connections(1, "direct", """"client_keystore_file":${json(key)},"client_keystore_password_env":"LTV_TLS_PASSWORD"""")
                    .byteInputStream(),
            ).single()
        assertEquals(SourceTls(ca, null, null), caOnly.tls)
        assertEquals(SourceTls(null, key, "LTV_TLS_PASSWORD"), keyOnly.tls)
        assertNull(readSourceProfiles(connections(1, "direct", null).byteInputStream()).single().tls)
    }

    @Test
    fun `invalid tls structure and paths fail at config parsing`() {
        val ca = directory.resolve("ca.pem")
        val key = directory.resolve("client.p12")
        val invalid =
            linkedMapOf(
                "empty object" to connections(1, "direct", ""),
                "http base url" to connections(1, "direct", """"ca_file":${json(ca)}""", baseUrl = "http://localhost"),
                "unknown nested field" to connections(1, "direct", """"ca_file":${json(ca)},"extra":true"""),
                "keystore without env" to connections(1, "direct", """"client_keystore_file":${json(key)}"""),
                "env without keystore" to connections(1, "direct", """"client_keystore_password_env":"LTV_TLS_PASSWORD""""),
                "leading digit env" to
                    connections(1, "direct", """"client_keystore_file":${json(key)},"client_keystore_password_env":"9BAD_NAME"""),
                "space in env" to
                    connections(1, "direct", """"client_keystore_file":${json(key)},"client_keystore_password_env":"BAD NAME"""),
                "relative path" to connections(1, "direct", """"ca_file":"relative/ca.pem""""),
                "parent segment" to connections(1, "direct", """"ca_file":${json(directory.resolve("part/../ca.pem"))}"""),
                "tilde" to connections(1, "direct", """"ca_file":"~/ca.pem""""),
                "slash UNC" to connections(1, "direct", """"ca_file":${json("//server/share/ca.pem")}"""),
                "backslash UNC" to connections(1, "direct", """"ca_file":${json("\\\\server\\share\\ca.pem")}"""),
                "file URL" to connections(1, "direct", """"ca_file":${json(ca.toUri().toString())}"""),
                "oversized path" to connections(1, "direct", """"ca_file":${json(directory.resolve("x".repeat(1100)))}"""),
                "control in path" to connections(1, "direct", """"ca_file":${json("$directory/bad\u0001")}"""),
                "non object" to connections(1, "direct", null).replace("\"queries\":", "\"tls\":[],\"queries\":"),
                "non string path" to connections(1, "direct", """"ca_file":123"""),
            )
        invalid.forEach { (case, text) ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java, { readSourceConnections(text.byteInputStream()) }, case).message,
                case,
            )
        }
    }

    @Test
    fun `postgresql profile rejects tls`() {
        val text =
            """{"schema_version":"source-connections.v2","connections":[{"id":"pg","source_kind":"postgresql",
            "source_database_id":"db","host":"localhost","database":"db","username_env":"PG_USER",
            "password_env":"PG_PASSWORD","tls":{"ca_file":${json(directory.resolve("ca.pem"))}}}]}"""
        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceConnections(text.byteInputStream()) }.message,
        )
    }

    @Test
    fun `published mtls connections example parses with a local directory`() {
        val text = Files.readString(Path.of("docs/contracts/sources/v1/mtls-connections.example.json"))
        val prefix = json(directory.toString() + File.separator).removeSurrounding("\"")
        val local = text.replace("/etc/ltv/tls/", prefix)
        val profiles = readSourceProfiles(local.byteInputStream())
        assertNotNull(profiles.single { it.transport == SourceTransport.GRAFANA_PROXY }.tls)
    }

    private fun connections(
        version: Int,
        transport: String,
        tlsBody: String?,
        baseUrl: String = "https://127.0.0.1:8443",
    ): String {
        val uid = if (transport == "grafana_proxy") "\"datasource_uid\":\"prom\"," else ""
        val tls = tlsBody?.let { "\"tls\":{$it}," }.orEmpty()
        return """{"schema_version":"source-connections.v$version","connections":[{
            "id":"p","source_kind":"prometheus","transport":"$transport","base_url":"$baseUrl",$uid$tls
            "queries":[{"id":"q","expression":"rate(x[${'$'}__interval])","metric":"x","unit":"ratio",
            "entity":"host","role":"system","aggregation":"interval_rate"}]}]}"""
    }

    private fun json(value: Path): String = json(value.toString())

    private fun json(value: String): String = JsonPrimitive(value).toString()
}

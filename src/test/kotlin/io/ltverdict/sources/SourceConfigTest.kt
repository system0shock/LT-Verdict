package io.ltverdict.sources

import io.ltverdict.core.RUN_PERIOD_RECOGNITION_METHOD
import io.ltverdict.core.RUN_PERIOD_SCHEMA_VERSION
import io.ltverdict.core.RUN_PERIOD_STATUS_INVALID_INPUT
import io.ltverdict.core.RUN_PERIOD_STATUS_RECOGNIZED
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceRuleEffect
import io.ltverdict.core.RunPeriodV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

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
    fun `profile queries accept interval max and interval min`() {
        listOf("interval_max", "interval_min").forEach { name ->
            val config =
                """{"schema_version":"source-connections.v1","connections":[${minimalConnection("p").replace("interval_rate", name)}]}"""

            val profile = readSourceProfiles(config.byteInputStream()).single()

            assertEquals(
                name,
                profile.queries
                    .single()
                    .aggregation.wireName,
            )
        }
    }

    @Test
    fun `profile accepts 64 queries`() {
        val queries =
            (1..64).joinToString(",") { index ->
                """{"id":"q$index","expression":"rate(x[${'$'}__interval])","metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_rate"}"""
            }
        val config =
            """{"schema_version":"source-connections.v1","connections":[{"id":"p1","source_kind":"prometheus","transport":"direct","base_url":"https://example.test","queries":[$queries]}]}"""

        assertEquals(64, readSourceProfiles(config.byteInputStream()).single().queries.size)
    }

    @Test
    fun `profile rejects 65 queries`() {
        val queries =
            (1..65).joinToString(",") { index ->
                """{"id":"q$index","expression":"rate(x[${'$'}__interval])","metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_rate"}"""
            }
        val config =
            """{"schema_version":"source-connections.v1","connections":[{"id":"p1","source_kind":"prometheus","transport":"direct","base_url":"https://example.test","queries":[$queries]}]}"""

        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(config.byteInputStream()) }.message,
        )
    }

    @Test
    fun `16 profiles with 64 queries parse and 17 profiles reject`() {
        val queries =
            (1..64).joinToString(",") { index ->
                """{"id":"q$index","expression":"rate(x[${'$'}__interval])","metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_rate"}"""
            }
        val profile =
            """{"id":"p1","source_kind":"prometheus","transport":"direct","base_url":"https://example.test","queries":[$queries]}"""
        val profiles16 = (1..16).joinToString(",") { index -> profile.replace("\"id\":\"p1\"", "\"id\":\"p$index\"") }
        val profiles17 = (1..17).joinToString(",") { index -> profile.replace("\"id\":\"p1\"", "\"id\":\"p$index\"") }
        val config16 = """{"schema_version":"source-connections.v1","connections":[$profiles16]}"""
        val config17 = """{"schema_version":"source-connections.v1","connections":[$profiles17]}"""

        val parsed = readSourceProfiles(config16.byteInputStream())
        assertEquals(16, parsed.size)
        assertEquals(1_024, parsed.sumOf { it.queries.size })
        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(config17.byteInputStream()) }.message,
        )
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
        assertEquals(
            SourceRequest("prom-main", 0, 120_000, 60_000),
            readSourceRequest(
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":0,"end_epoch_ms":120000,"step_ms":60000}"""
                    .byteInputStream(),
            ),
        )
        // v1/v2 сохраняют прежнее правило step >= 1000: целые секунды 1..60 требует acquire по source_kind профиля.
        assertEquals(
            SourceRequest("prom-main", 0, 3_000, 1_500),
            readSourceRequest(
                """{"schema_version":"source-request.v1","profile_id":"prom-main","start_epoch_ms":0,"end_epoch_ms":3000,"step_ms":1500}"""
                    .byteInputStream(),
            ),
        )

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

    @Test
    fun `v3 auto window normalizes profiles and keeps the declared grid tolerance`() {
        val request = readWindowedSourceRequest(v3AutoRequest().byteInputStream())

        assertEquals(listOf("errors", "metrics"), request.profileIds)
        assertEquals(AutoWindow(60_000L, 1_800_000L, 15_000L), request.window)
        assertEquals("source-request.v3", request.schemaVersion)
    }

    @Test
    fun `v3 explicit window keeps the declared epoch bounds`() {
        val request = readWindowedSourceRequest(v3ExplicitRequest().byteInputStream())

        assertEquals(listOf("metrics"), request.profileIds)
        assertEquals(ExplicitWindow(1_767_225_600_000L, 1_767_225_660_000L, 1_000L), request.window)
        assertEquals("source-request.v3", request.schemaVersion)
    }

    @Test
    fun `v3 rejects unknown fields at both levels and window values the derivation cannot use`() {
        val auto = v3AutoRequest()
        val invalid =
            listOf(
                auto.replace("\"profile_ids\":", "\"url\":\"http://unconfigured\",\"profile_ids\":"),
                auto.replace("\"window\":{", "\"window\":{\"origin_url\":\"http://unconfigured\","),
                v3ExplicitRequest().replace("\"window\":{", "\"window\":{\"origin_url\":\"http://unconfigured\","),
                auto.replace("\"origin\":\"auto\"", "\"origin\":\"derived\""),
                """{"schema_version":"source-request.v3","profile_ids":["metrics"]}""",
                Files.readString(Path.of("docs/contracts/sources/v3/examples/invalid/margin-not-aligned.json")),
                auto.replace("\"margin_ms\":60000", "\"margin_ms\":3600001"),
                auto.replace("\"margin_ms\":60000", "\"margin_ms\":-60000"),
                auto.replace("\"max_idle_gap_ms\":1800000", "\"max_idle_gap_ms\":1800001"),
                auto.replace("\"max_idle_gap_ms\":1800000", "\"max_idle_gap_ms\":14000"),
                auto.replace("\"step_ms\":15000", "\"step_ms\":61000"),
                auto.replace("\"step_ms\":15000", "\"step_ms\":1500"),
                auto.replace("source-request.v3", "source-request.v5"),
            )

        invalid.forEach { json ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) { readWindowedSourceRequest(json.byteInputStream()) }
            assertEquals("SOURCE_REQUEST_INVALID", failure.message)
            assertTrue("unconfigured" !in failure.toString())
        }
    }

    @Test
    fun `v1 and v2 documents keep an explicit window through the windowed reader`() {
        val v1 =
            """{"schema_version":"source-request.v1","profile_id":"prom-main",
            "start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000}"""
        val v2 =
            """{"schema_version":"source-request.v2","profile_ids":["z","a"],
            "start_epoch_ms":1000,"end_epoch_ms":3000,"step_ms":1000}"""

        assertEquals(
            WindowedSourceRequest("source-request.v1", listOf("prom-main"), ExplicitWindow(1_000L, 3_000L, 1_000L)),
            readWindowedSourceRequest(v1.byteInputStream()),
        )
        assertEquals(
            WindowedSourceRequest("source-request.v2", listOf("a", "z"), ExplicitWindow(1_000L, 3_000L, 1_000L)),
            readWindowedSourceRequest(v2.byteInputStream()),
        )
        listOf(v1, v2).forEach { document ->
            val legacy = readSourceRequest(document.byteInputStream())
            val windowed = readWindowedSourceRequest(document.byteInputStream())
            assertEquals(listOf(legacy.profileId) + legacy.additionalProfileIds, windowed.profileIds)
            assertEquals(ExplicitWindow(legacy.startEpochMillis, legacy.endEpochMillis, legacy.stepMillis), windowed.window)
        }
        // v3 остаётся недоступен существующей точке входа, пока вызывающий код не переключён.
        assertEquals(
            "SOURCE_REQUEST_INVALID",
            assertThrows(IllegalArgumentException::class.java) {
                readSourceRequest(v3AutoRequest().byteInputStream())
            }.message,
        )
    }

    @Test
    fun `published request examples agree with the reader of their own version`() {
        // Каждый опубликованный пример обязан проходить тот reader, для которого схема его объявляет валидным.
        listOf(
            "docs/contracts/sources/v1/request.example.json",
            "docs/contracts/sources/v1/multiple-request.example.json",
            "docs/contracts/sources/v2/examples/valid/two-profiles.json",
        ).forEach { path ->
            val document = example(path)
            val legacy = readSourceRequest(document.byteInputStream())
            val windowed = readWindowedSourceRequest(document.byteInputStream())
            assertEquals(listOf(legacy.profileId) + legacy.additionalProfileIds, windowed.profileIds)
            assertEquals(ExplicitWindow(legacy.startEpochMillis, legacy.endEpochMillis, legacy.stepMillis), windowed.window)
        }
        assertEquals(
            WindowedSourceRequest("source-request.v3", listOf("errors", "metrics"), AutoWindow(60_000L, 1_800_000L, 15_000L)),
            readWindowedSourceRequest(example("docs/contracts/sources/v3/examples/valid/auto-window.json").byteInputStream()),
        )
        assertEquals(
            WindowedSourceRequest(
                "source-request.v3",
                listOf("metrics"),
                ExplicitWindow(1_767_225_600_000L, 1_767_225_660_000L, 1_000L),
            ),
            readWindowedSourceRequest(example("docs/contracts/sources/v3/examples/valid/explicit-window.json").byteInputStream()),
        )
        listOf(
            "docs/contracts/sources/v2/examples/invalid/unknown-field.json",
            "docs/contracts/sources/v2/examples/invalid/duplicate-profile.json",
        ).forEach { path ->
            val document = example(path)
            assertEquals(
                "SOURCE_REQUEST_INVALID",
                assertThrows(IllegalArgumentException::class.java) { readSourceRequest(document.byteInputStream()) }.message,
            )
            assertEquals(
                "SOURCE_REQUEST_INVALID",
                assertThrows(IllegalArgumentException::class.java) { readWindowedSourceRequest(document.byteInputStream()) }.message,
            )
        }
        listOf(
            "docs/contracts/sources/v3/examples/invalid/unknown-field.json",
            "docs/contracts/sources/v3/examples/invalid/margin-not-aligned.json",
        ).forEach { path ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    readWindowedSourceRequest(example(path).byteInputStream())
                }
            assertEquals("SOURCE_REQUEST_INVALID", failure.message)
            assertTrue("unconfigured" !in failure.toString())
        }
    }

    @Test
    fun `auto window expands the recognized period by the margin it can guarantee`() {
        val window = derived(period(1_767_268_807_400L, 1_767_270_712_100L), AutoWindow(60_000L, 1_800_000L, 15_000L))

        assertEquals(1_767_268_740_000L, window.startMillis)
        assertEquals(1_767_270_780_000L, window.endMillis)
        assertEquals(15_000L, window.stepMillis)
        assertEquals(67_400L, window.appliedMarginMillis)
        assertEquals(67_400L, 1_767_268_807_400L - window.startMillis)
        assertEquals(67_900L, window.endMillis - 1_767_270_712_100L)
    }

    @Test
    fun `auto window clamps the lower bound to zero and reports the smaller applied margin`() {
        val window = derived(period(30_000L, 90_000L), AutoWindow(60_000L, 1_800_000L, 15_000L))

        assertEquals(0L, window.startMillis)
        assertEquals(150_000L, window.endMillis)
        assertEquals(30_000L, window.appliedMarginMillis)
    }

    @Test
    fun `auto window aligns to the absolute epoch grid instead of the period`() {
        val window = derived(period(1_767_225_603_500L, 1_767_225_610_500L), AutoWindow(0L, 15_000L, 7_000L))

        assertEquals(Math.floorDiv(1_767_225_603_500L, 7_000L) * 7_000L, window.startMillis)
        assertEquals(1_767_225_600_000L, window.startMillis)
        assertEquals(1_767_225_614_000L, window.endMillis)
    }

    @Test
    fun `auto window refuses instead of guessing test boundaries`() {
        val auto = AutoWindow(60_000L, 60_000L, 15_000L)

        assertEquals(
            AutoWindowOutcome.Refused(AUTO_WINDOW_UNAVAILABLE),
            deriveAutoWindow(period(0L, 0L, status = RUN_PERIOD_STATUS_INVALID_INPUT), auto),
        )
        assertEquals(
            AutoWindowOutcome.Refused(AUTO_WINDOW_MULTI_TEST_SUSPECTED),
            deriveAutoWindow(period(1_767_225_600_000L, 1_767_229_200_000L, 120_000L, 1), auto),
        )
        // Простой, равный допуску, остаётся одним прогоном: отказывает только строго более длинный простой.
        assertEquals(
            DerivedAutoWindow(1_767_225_540_000L, 1_767_229_260_000L, 15_000L, 60_000L),
            derived(period(1_767_225_600_000L, 1_767_229_200_000L, 60_000L, 1), auto),
        )
        // Период длиннее 100 000 ячеек секундного шага не выбирается: требуется явное окно.
        assertEquals(
            AutoWindowOutcome.Refused(AUTO_WINDOW_SPAN_UNSUPPORTED),
            deriveAutoWindow(period(1_767_225_600_000L, 1_767_325_601_000L), AutoWindow(0L, 60_000L, 1_000L)),
        )
    }

    @Test
    fun `v4 declares the step mode and relaxes the grid multiples only for auto`() {
        val auto = readWindowedSourceRequest(v4AutoRequest(stepMode = "auto", margin = 50_000, gap = 50_000).byteInputStream())
        val fixed = readWindowedSourceRequest(v4AutoRequest(stepMode = "fixed").byteInputStream())
        val explicit = readWindowedSourceRequest(v4ExplicitRequest("auto").byteInputStream())

        assertEquals("source-request.v4", auto.schemaVersion)
        assertEquals(AutoWindow(50_000L, 50_000L, 15_000L, stepAuto = true), auto.window)
        assertEquals(AutoWindow(60_000L, 1_800_000L, 15_000L, stepAuto = false), fixed.window)
        assertEquals(ExplicitWindow(1_767_225_600_000L, 1_767_225_660_000L, 1_000L, stepAuto = true), explicit.window)
    }

    @Test
    fun `v4 requires its step mode and keeps the v3 rules for fixed`() {
        val invalid =
            listOf(
                v4AutoRequest(stepMode = null),
                v4AutoRequest(stepMode = "adaptive"),
                v4AutoRequest(stepMode = "fixed", margin = 50_000),
                v4AutoRequest(stepMode = "fixed", gap = 1_790_000),
                v4AutoRequest(stepMode = "auto", gap = 14_000),
                v4AutoRequest(stepMode = "auto", gap = 1_800_500),
                v4AutoRequest(stepMode = "fixed", gap = 1_800_500),
                v4AutoRequest(stepMode = "auto", step = 61_000),
                v4AutoRequest(stepMode = "auto").replace("\"profile_ids\":", "\"url\":\"http://unconfigured\",\"profile_ids\":"),
                v4ExplicitRequest("auto").replace(",\"step_mode\":\"auto\"", ""),
                v4ExplicitRequest("auto").replace("\"step_ms\":1000", "\"step_ms\":7000"),
            )

        invalid.forEach { json ->
            val failure = assertThrows(IllegalArgumentException::class.java) { readWindowedSourceRequest(json.byteInputStream()) }
            assertEquals("SOURCE_REQUEST_INVALID", failure.message)
            assertTrue("unconfigured" !in failure.toString())
        }
    }

    @Test
    fun `v4 explicit auto may exceed 100000 cells at the declared step while fixed may not`() {
        val thirtyHours = v4ExplicitRequest("auto").replace("1767225660000", "1767333600000")

        val auto = readWindowedSourceRequest(thirtyHours.byteInputStream())

        assertEquals(ExplicitWindow(1_767_225_600_000L, 1_767_333_600_000L, 1_000L, stepAuto = true), auto.window)
        assertEquals(
            "SOURCE_REQUEST_INVALID",
            assertThrows(IllegalArgumentException::class.java) {
                readWindowedSourceRequest(thirtyHours.replace("\"step_mode\":\"auto\"", "\"step_mode\":\"fixed\"").byteInputStream())
            }.message,
        )
    }

    @Test
    fun `published v4 examples agree with the reader`() {
        assertEquals(
            WindowedSourceRequest(
                "source-request.v4",
                listOf("errors", "metrics"),
                AutoWindow(45_000L, 1_800_000L, 15_000L, stepAuto = true),
            ),
            readWindowedSourceRequest(example("docs/contracts/sources/v4/examples/valid/auto-window-auto-step.json").byteInputStream()),
        )
        assertEquals(
            ExplicitWindow(1_767_225_600_000L, 1_767_225_660_000L, 1_000L, stepAuto = false),
            readWindowedSourceRequest(
                example("docs/contracts/sources/v4/examples/valid/explicit-window-fixed.json").byteInputStream(),
            ).window,
        )
        listOf(
            "docs/contracts/sources/v4/examples/invalid/step-mode-missing.json",
            "docs/contracts/sources/v4/examples/invalid/unknown-field.json",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { readWindowedSourceRequest(example(path).byteInputStream()) }
        }
    }

    @Test
    fun `connections v3 declare the scrape interval and older versions reject it`() {
        val v3 = autostepConnections("source-connections.v3", """"scrape_interval_ms":15000,""")

        assertEquals(15_000L, readSourceProfiles(v3.byteInputStream()).single().scrapeIntervalMillis)
        assertEquals(
            null,
            readSourceProfiles(autostepConnections("source-connections.v3", "").byteInputStream()).single().scrapeIntervalMillis,
        )
        listOf("source-connections.v1", "source-connections.v2").forEach { version ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    readSourceProfiles(autostepConnections(version, """"scrape_interval_ms":15000,""").byteInputStream())
                }
            assertEquals("SOURCE_CONFIG_INVALID", failure.message)
        }
        listOf("500", "1500", "3601000", "\"15\"", "0").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                readSourceProfiles(autostepConnections("source-connections.v3", """"scrape_interval_ms":$value,""").byteInputStream())
            }
        }
    }

    @Test
    fun `connections v3 declare non negative events and older versions reject the field`() {
        fun connections(
            version: String,
            flag: String,
        ) = """{"schema_version":"$version","connections":[{"id":"p","source_kind":"prometheus","transport":"direct",
            "base_url":"https://example.test","queries":[{"id":"oom","expression":"increase(x[${'$'}__interval])",
            "metric":"x","unit":"events","entity":"e","role":"system","aggregation":"interval_rate"$flag}]}]}"""

        assertEquals(
            true,
            readSourceProfiles(connections("source-connections.v3", ",\"non_negative_events\":true").byteInputStream())
                .single()
                .queries
                .single()
                .nonNegativeEvents,
        )
        assertEquals(
            false,
            readSourceProfiles(connections("source-connections.v3", "").byteInputStream())
                .single()
                .queries
                .single()
                .nonNegativeEvents,
        )
        listOf("source-connections.v1", "source-connections.v2").forEach { version ->
            assertThrows(IllegalArgumentException::class.java) {
                readSourceProfiles(connections(version, ",\"non_negative_events\":true").byteInputStream())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readSourceProfiles(connections("source-connections.v3", ",\"non_negative_events\":\"true\"").byteInputStream())
        }
    }

    @Test
    fun `an event series with gt up to zero and interval min is rejected at any step`() {
        fun profile(
            aggregation: String,
            threshold: String,
            flag: String,
        ) = """{"schema_version":"source-connections.v3","connections":[{"id":"p","source_kind":"prometheus","transport":"direct",
            "base_url":"https://example.test","queries":[{"id":"oom","expression":"min_over_time(x[${'$'}__interval])",
            "metric":"x","unit":"events","entity":"e","role":"system","aggregation":"$aggregation"$flag}],
            "rules":[{"id":"r","series_id":"oom","unit":"events","operator":"gt","threshold":$threshold,
            "min_consecutive_cells":1,"effect":"sla"}]}]}"""

        listOf("0", "-1").forEach { threshold ->
            assertEquals(
                "SOURCE_CONFIG_INVALID",
                assertThrows(IllegalArgumentException::class.java) {
                    readSourceProfiles(profile("interval_min", threshold, ",\"non_negative_events\":true").byteInputStream())
                }.message,
            )
        }
        readSourceProfiles(profile("interval_min", "0", "").byteInputStream())
        readSourceProfiles(profile("interval_min", "5", ",\"non_negative_events\":true").byteInputStream())
    }

    @Test
    fun `connections v3 declare a rule by duration and exactly one of cells or duration`() {
        val declared =
            """{"id":"r","series_id":"q","unit":"ratio","operator":"gt",
            "threshold":0.8,"min_consecutive_span_ms":60000,"effect":"sla"}"""

        val profile = readSourceProfiles(spanConnections(declared, "source-connections.v3").byteInputStream()).single()

        assertEquals(mapOf("r" to 60_000L), profile.ruleSpansMillis)
        assertEquals(1, profile.rules.single().minConsecutiveCells)
        val both = declared.replace("\"effect\"", "\"min_consecutive_cells\":2,\"effect\"")
        val neither = declared.replace("\"min_consecutive_span_ms\":60000,", "")
        listOf(both, neither, declared.replace("60000", "0"), declared.replace("60000", "86400001")).forEach { rule ->
            assertThrows(IllegalArgumentException::class.java) {
                readSourceProfiles(spanConnections(rule, "source-connections.v3").byteInputStream())
            }
        }
        listOf("source-connections.v1", "source-connections.v2").forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(spanConnections(declared, version).byteInputStream()) }
        }
    }

    @Test
    fun `a duration is converted to whole cells rounding up and never below the declared length`() {
        assertEquals(3, spanToCells(60_000L, 20_000L))
        assertEquals(4, spanToCells(61_000L, 20_000L))
        assertEquals(1, spanToCells(1_000L, 60_000L))
        val rule =
            """{"id":"r","series_id":"q","unit":"ratio","operator":"gt",
            "threshold":0.8,"min_consecutive_span_ms":61000,"effect":"sla"}"""
        val profile = readSourceProfiles(spanConnections(rule, "source-connections.v3").byteInputStream()).single()

        assertEquals(4, profile.rulesAt(20_000L).single().minConsecutiveCells)
        assertEquals(61, profile.rulesAt(1_000L).single().minConsecutiveCells)
    }

    @Test
    fun `the published autostep connections example is accepted`() {
        val profile = readSourceProfiles(example("docs/contracts/sources/v1/autostep-connections.example.json").byteInputStream()).single()

        assertEquals(15_000L, profile.scrapeIntervalMillis)
        assertEquals(mapOf("memory-limit-high" to 60_000L), profile.ruleSpansMillis)
    }

    @Test
    fun `v3 connections retain PostgreSQL profiles and reject their scrape interval`() {
        val v3 =
            example("docs/contracts/sources/v1/postgresql-connections.example.json")
                .replace("source-connections.v2", "source-connections.v3")

        assertEquals("load-test-db", readSourceConnections(v3.byteInputStream()).postgres.single().sourceDatabaseId)
        val invalid = v3.replace("\"source_kind\": \"postgresql\",", "\"source_kind\": \"postgresql\", \"scrape_interval_ms\": 15000,")
        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceConnections(invalid.byteInputStream()) }.message,
        )
    }

    @Test
    fun `v3 OpenSearch rejects a scrape interval`() {
        val v3 =
            example("docs/contracts/sources/v1/opensearch-connections.example.json")
                .replace("source-connections.v1", "source-connections.v3")
                .replace("\"source_kind\": \"opensearch\",", "\"source_kind\": \"opensearch\", \"scrape_interval_ms\": 15000,")

        assertEquals(
            "SOURCE_CONFIG_INVALID",
            assertThrows(IllegalArgumentException::class.java) { readSourceProfiles(v3.byteInputStream()) }.message,
        )
    }

    private fun v4AutoRequest(
        stepMode: String? = "auto",
        step: Long = 15_000,
        margin: Long = 60_000,
        gap: Long = 1_800_000,
    ): String {
        val mode = stepMode?.let { ",\"step_mode\":\"$it\"" }.orEmpty()
        return """{"schema_version":"source-request.v4","profile_ids":["metrics","errors"],
        "window":{"origin":"auto","step_ms":$step$mode,"margin_ms":$margin,"max_idle_gap_ms":$gap}}"""
    }

    private fun v4ExplicitRequest(stepMode: String): String =
        """{"schema_version":"source-request.v4","profile_ids":["metrics"],
        "window":{"origin":"explicit","start_epoch_ms":1767225600000,"end_epoch_ms":1767225660000,"step_ms":1000,"step_mode":"$stepMode"}}"""

    private fun spanConnections(
        rule: String,
        version: String,
    ): String =
        """{"schema_version":"$version","connections":[{"id":"p","source_kind":"prometheus","transport":"direct",
        "base_url":"https://example.test","queries":[{"id":"q","expression":"max_over_time(x[${'$'}__interval])",
        "metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_max"}],"rules":[$rule]}]}"""

    private fun autostepConnections(
        version: String,
        profileExtra: String,
    ): String =
        """{"schema_version":"$version","connections":[{"id":"p","source_kind":"prometheus","transport":"direct",
        "base_url":"https://example.test",$profileExtra"queries":[{"id":"q","expression":"rate(x[${'$'}__interval])",
        "metric":"x","unit":"ratio","entity":"e","role":"system","aggregation":"interval_rate"}]}]}"""

    private fun example(path: String): String = Files.readString(Path.of(path))

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

    private fun v3AutoRequest(): String =
        """{"schema_version":"source-request.v3","profile_ids":["metrics","errors"],
        "window":{"origin":"auto","step_ms":15000,"margin_ms":60000,"max_idle_gap_ms":1800000}}"""

    private fun v3ExplicitRequest(): String =
        """{"schema_version":"source-request.v3","profile_ids":["metrics"],
        "window":{"origin":"explicit","start_epoch_ms":1767225600000,"end_epoch_ms":1767225660000,"step_ms":1000}}"""

    private fun period(
        first: Long,
        last: Long,
        longestIdleGapMillis: Long? = null,
        idleGapCount: Int = 0,
        status: String = RUN_PERIOD_STATUS_RECOGNIZED,
    ): RunPeriodV1 =
        RunPeriodV1(
            RUN_PERIOD_SCHEMA_VERSION,
            "a".repeat(64),
            RUN_PERIOD_RECOGNITION_METHOD,
            first,
            last,
            longestIdleGapMillis,
            idleGapCount,
            status,
        )

    private fun derived(
        period: RunPeriodV1,
        auto: AutoWindow,
    ): DerivedAutoWindow = (deriveAutoWindow(period, auto) as AutoWindowOutcome.Derived).window
}

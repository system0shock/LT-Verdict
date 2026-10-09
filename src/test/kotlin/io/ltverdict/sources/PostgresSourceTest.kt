package io.ltverdict.sources

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.opentest4j.AssertionFailedError
import org.opentest4j.TestAbortedException
import java.io.ByteArrayInputStream
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Savepoint
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant
import java.util.Collections
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.logging.Logger

class PostgresSourceTest {
    @Test
    fun `capture rejects an invalid profile before reading credentials`() {
        val valid = profile()
        val invalid =
            listOf(
                valid.copy(host = "https://db.example"),
                valid.copy(host = ":::"),
                valid.copy(port = 0),
                valid.copy(database = ""),
                valid.copy(tables = List(17) { table("events_$it") }),
                valid.copy(tables = listOf(table("events").copy(schema = "bad\u0000schema"))),
                valid.copy(tables = listOf(table("events").copy(key = listOf("missing")))),
                valid.copy(tables = listOf(table("events").copy(rowLimit = 10_001))),
                valid.copy(tables = listOf(table("events").copy(byteLimit = 1_048_577))),
                valid.copy(pgProfileServerId = 1),
            )

        invalid.forEachIndexed { index, candidate ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    capturePostgresPhase(candidate, environment = { error("credentials read for case $index") })
                }

            assertEquals("PG_PROFILE_INVALID", failure.message, "case $index")
        }
    }

    @Test
    fun `capture reports missing credentials without exposing their names`() {
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                capturePostgresPhase(profile(), environment = { null })
            }

        assertEquals("PG_CREDENTIALS_MISSING", failure.message)
    }

    @Test
    fun `capture emits a validated bounded phase over fixed read only JDBC`() =
        withDriver(JdbcFixture()) { driver ->
            val captured = capturePostgresPhase(profile(), environment = ::credentials)
            val phase = captured.phase
            val table = phase.arr("tables").single().jsonObject

            assertEquals("postgres-phase.v1", phase.string("schema_version"))
            assertEquals("pre", phase.string("phase"))
            assertEquals("orders", phase.string("profile_id"))
            assertEquals("orders-test", phase.string("source_database_id"))
            assertEquals(JsonNull, phase.getValue("pre_sha256"))
            assertEquals("15.5", phase.string("server_version"))
            assertEquals("4096", phase.obj("configuration").string("work_mem"))
            assertEquals("999", phase.obj("configuration").string("lt_verdict.excluded_statement_userid"))
            assertEquals("COMPLETE", table.string("status"))
            assertEquals(2L, table.long("row_count"))
            assertEquals(
                "1",
                table
                    .arr("rows")[0]
                    .jsonArray[0]
                    .jsonPrimitive.content,
            )
            assertEquals(JsonNull, table.arr("rows")[1].jsonArray[1])
            assertEquals("DEGRADED", phase.obj("statements").string("status"))
            assertEquals("PG_STATEMENTS_UNAVAILABLE", phase.obj("statements").string("reason"))
            assertEquals("PG_PROFILE_UNAVAILABLE", phase.obj("pg_profile").string("reason"))
            assertEquals(null, captured.pgProfileHtml)
            assertEquals(phase, validatePostgresPhase(ByteArrayInputStream(canonicalJson(phase))))

            assertEquals("jdbc:postgresql://db.example:5432/orders%20db", driver.urls.single())
            assertEquals("reader", driver.connectionProperties.single().getProperty("user"))
            assertEquals("top-secret", driver.connectionProperties.single().getProperty("password"))
            assertEquals("verify-full", driver.connectionProperties.single().getProperty("sslmode"))
            assertEquals("30", driver.connectionProperties.single().getProperty("connectTimeout"))
            assertEquals("30", driver.connectionProperties.single().getProperty("socketTimeout"))
            assertEquals("16777216", driver.connectionProperties.single().getProperty("maxResultBuffer"))
            assertEquals("true", driver.connectionProperties.single().getProperty("readOnly"))
            assertEquals("always", driver.connectionProperties.single().getProperty("readOnlyMode"))
            assertTrue(driver.readOnlyValues.single())
            assertFalse(driver.autoCommitValues.single())
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, driver.isolationValues.single())
            assertTrue(driver.executedSql.contains("SET TRANSACTION READ ONLY"))
            assertTrue(driver.executedSql.contains("SET LOCAL lock_timeout = '5s'"))
            assertTrue(driver.executedSql.contains("SET LOCAL statement_timeout = '30s'"))
            assertTrue(driver.executedSql.contains("SET LOCAL TIME ZONE 'UTC'"))
            assertTrue(driver.executedSql.contains("SET LOCAL DateStyle = 'ISO, YMD'"))
            assertTrue(driver.queryTimeouts.all { it == 30 })
            assertTrue(driver.queries.any { it.sql.contains("pg_catalog.pg_attribute") })
            val tableQuery = driver.queries.single { it.sql.contains("ORDER BY \"id\"") }
            assertTrue(tableQuery.sql.contains("pg_catalog.substr(pg_catalog.convert_to(\"id\"::text, 'UTF8'), 1, 65537)"))
            assertTrue(tableQuery.sql.contains("LIMIT 10001"))
            assertEquals(10_001, tableQuery.maxRows)
            assertEquals(1, tableQuery.fetchSize)
            val persisted = canonicalJson(phase).decodeToString()
            assertFalse(persisted.contains("top-secret"))
            assertFalse(driver.urls.single().contains("reader"))
            assertFalse(driver.urls.single().contains("top-secret"))
        }

    @Test
    fun `statement snapshots bracket table reads and post binds canonical pre`() =
        withDriver(
            JdbcFixture(
                extensions = listOf(listOf("pg_stat_statements", "1.10", "metrics")),
                statementRows =
                    listOf(
                        listOf("1", "10", "42", true, 3L, BigDecimal("2.5"), 4L, 5L, 6L, 7L),
                    ),
            ),
        ) { driver ->
            val pre = capturePostgresPhase(profile(), environment = ::credentials).phase
            val preTableIndex = driver.queries.indexOfFirst { it.sql.contains("pg_catalog.convert_to") }
            val preStatementIndex = driver.queries.indexOfFirst { it.sql.contains(".\"pg_stat_statements\"") }

            assertTrue(preStatementIndex > preTableIndex)
            assertEquals("COMPLETE", pre.obj("statements").string("status"))
            assertEquals("2026-01-01T00:00:00Z", pre.obj("statements").string("stats_reset"))
            assertEquals(
                "42",
                pre
                    .obj("statements")
                    .arr("rows")
                    .single()
                    .jsonObject
                    .string("queryid"),
            )

            driver.queries.clear()
            val post = capturePostgresPhase(profile(), pre = pre, post = true, environment = ::credentials).phase
            val postTableIndex = driver.queries.indexOfFirst { it.sql.contains("pg_catalog.convert_to") }
            val postStatementIndex = driver.queries.indexOfFirst { it.sql.contains(".\"pg_stat_statements\"") }

            assertTrue(postStatementIndex in 0 until postTableIndex)
            assertEquals("post", post.string("phase"))
            assertEquals(sha256Hex(canonicalJson(pre)), post.string("pre_sha256"))
            assertTrue(
                driver.queries.any {
                    it.sql.contains("WHERE dbid =") &&
                        it.sql.contains("AND userid <> ?::oid") &&
                        it.sql.contains("current_database()") &&
                        it.sql.contains("ORDER BY dbid, userid, queryid, toplevel") &&
                        it.sql.contains("LIMIT 10001")
                },
            )
            val statementQuery = driver.queries.single { it.sql.contains(".\"pg_stat_statements\"") }
            assertEquals(999L, statementQuery.parameters[1])
            assertFalse(driver.allSql().contains("pg_stat_statements_reset("))
        }

    @Test
    fun `capture role traffic is excluded while other user statement deltas remain`() =
        withDriver(
            JdbcFixture(
                captureUserId = "10",
                extensions = listOf(listOf("pg_stat_statements", "1.10", "metrics")),
                statementRows =
                    listOf(
                        statementRow(userid = "10", queryid = "100", calls = 1L),
                        statementRow(userid = "20", queryid = "200", calls = 5L),
                    ),
            ),
        ) { driver ->
            val pre = capturePostgresPhase(profile(), environment = ::credentials).phase
            val runStart = pre.long("capture_ended_epoch_ms")
            driver.statementRows =
                listOf(
                    statementRow(userid = "10", queryid = "100", calls = 50L),
                    statementRow(userid = "20", queryid = "200", calls = 7L),
                )
            val post = capturePostgresPhase(profile(), pre = pre, post = true, environment = ::credentials).phase
            val context =
                comparePostgresPhases(
                    pre,
                    post,
                    "a".repeat(64),
                    runStart,
                    post.long("capture_started_epoch_ms"),
                )
            val deltas = context.obj("statements").arr("rows").map { it.jsonObject }

            assertEquals("10", pre.obj("configuration").string("lt_verdict.excluded_statement_userid"))
            assertEquals(listOf("20"), pre.obj("statements").arr("rows").map { it.jsonObject.string("userid") })
            assertEquals(listOf("20"), post.obj("statements").arr("rows").map { it.jsonObject.string("userid") })
            assertEquals(1, deltas.size)
            assertEquals("20", deltas.single().string("userid"))
            assertEquals(2L, deltas.single().long("calls"))
        }

    @Test
    fun `keyless and failed table modules preserve later statement capture`() =
        withDriver(
            JdbcFixture(
                extensions = listOf(listOf("pg_stat_statements", "1.10", "metrics")),
                tableCount = 9L,
            ),
        ) { driver ->
            val keyless = capturePostgresPhase(profile(table = table("events").copy(key = emptyList())), environment = ::credentials)
            val keylessTable =
                keyless.phase
                    .arr("tables")
                    .single()
                    .jsonObject

            assertEquals("DEGRADED", keylessTable.string("status"))
            assertEquals("PG_TABLE_NO_STABLE_KEY", keylessTable.string("reason"))
            assertEquals(9L, keylessTable.long("row_count"))
            assertTrue(keylessTable.arr("rows").isEmpty())
            assertTrue(driver.queries.any { it.sql.startsWith("SELECT count(*)::bigint FROM") })
            assertFalse(driver.queries.any { it.sql.contains("pg_catalog.convert_to") })

            driver.queries.clear()
            driver.failTableSelect = true
            val failed = capturePostgresPhase(profile(), environment = ::credentials).phase
            val failedTable = failed.arr("tables").single().jsonObject

            assertEquals("FAILED", failedTable.string("status"))
            assertEquals("PG_TABLE_QUERY_FAILED", failedTable.string("reason"))
            assertEquals("COMPLETE", failed.obj("statements").string("status"))
            assertTrue(driver.savepointRollbacks > 0)
        }

    @Test
    fun `row and HTML limits degrade modules before oversized values persist`() =
        withDriver(
            JdbcFixture(
                tableRows = listOf(listOf("1", "x".repeat(100))),
                extensions = listOf(listOf("pg_profile", "4.8", "profile")),
                reportBytes = ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() },
            ),
        ) { driver ->
            val boundedProfile =
                profile(
                    table = table("events").copy(byteLimit = 32),
                    pgProfileServerId = 7,
                    pgProfileStartSampleId = 10,
                    pgProfileEndSampleId = 11,
                )
            val captured = capturePostgresPhase(boundedProfile, environment = ::credentials)
            val table =
                captured.phase
                    .arr("tables")
                    .single()
                    .jsonObject
            val pgProfile = captured.phase.obj("pg_profile")

            assertEquals("DEGRADED", table.string("status"))
            assertEquals("PG_TABLE_TRUNCATED", table.string("reason"))
            assertTrue(table.arr("rows").isEmpty())
            assertEquals(JsonNull, table.getValue("row_count"))
            assertEquals("DEGRADED", pgProfile.string("status"))
            assertEquals("PG_PROFILE_REPORT_TOO_LARGE", pgProfile.string("reason"))
            assertEquals(JsonNull, pgProfile.getValue("report_sha256"))
            assertEquals(null, captured.pgProfileHtml)
            val reportQuery = driver.queries.single { it.sql.contains(".\"get_report\"") }
            assertEquals(listOf(7, 10, 11), reportQuery.parameters.values.toList())
            assertTrue(reportQuery.sql.contains("(?::integer, ?::integer, ?::integer)"))
            assertTrue(reportQuery.sql.contains("pg_catalog.substr(pg_catalog.convert_to("))
            assertTrue(reportQuery.sql.contains(", 'UTF8'), 1, 4194305)"))
        }

    @Test
    fun `oversized table cells use a one row bounded wire projection`() =
        withDriver(
            JdbcFixture(tableRows = listOf(listOf("1", "x".repeat(65_538)))),
        ) { driver ->
            val table =
                capturePostgresPhase(profile(), environment = ::credentials)
                    .phase
                    .arr("tables")
                    .single()
                    .jsonObject
            val query = driver.queries.single { it.sql.contains("ORDER BY \"id\"") }

            assertEquals("DEGRADED", table.string("status"))
            assertEquals("PG_TABLE_CELL_TOO_LARGE", table.string("reason"))
            assertTrue(table.arr("rows").isEmpty())
            assertEquals(1, query.fetchSize)
            assertTrue(query.sql.contains("pg_catalog.substr(pg_catalog.convert_to(\"state\"::text, 'UTF8'), 1, 65537)"))
        }

    @Test
    fun `pg profile report is inert bytes with explicit unknown reset coverage`() =
        withDriver(
            JdbcFixture(
                extensions = listOf(listOf("pg_profile", "4.8", "profile")),
                reportBytes = "<html><body>report</body></html>".encodeToByteArray(),
            ),
        ) { _ ->
            val captured =
                capturePostgresPhase(
                    profile(
                        pgProfileServerId = 7,
                        pgProfileStartSampleId = 10,
                        pgProfileEndSampleId = 11,
                    ),
                    environment = ::credentials,
                )
            val pgProfile = captured.phase.obj("pg_profile")

            assertArrayEquals("<html><body>report</body></html>".encodeToByteArray(), captured.pgProfileHtml)
            assertEquals("DEGRADED", pgProfile.string("status"))
            assertEquals("PG_PROFILE_RESET_UNKNOWN", pgProfile.string("reason"))
            assertEquals("4.8", pgProfile.string("extension_version"))
            assertEquals(JsonNull, pgProfile.getValue("statements_reset"))
            assertEquals(7L, pgProfile.long("server_id"))
            assertEquals(sha256Hex(requireNotNull(captured.pgProfileHtml)), pgProfile.string("report_sha256"))
        }

    @Test
    fun `read only enforcement and cancellation expose only stable failures and close resources`() {
        withDriver(JdbcFixture(transactionReadOnly = false)) { _ ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    capturePostgresPhase(profile(), environment = ::credentials)
                }
            assertEquals("PG_READ_ONLY_REQUIRED", failure.message)
        }

        withDriver(JdbcFixture()) { driver ->
            assertThrows(CancellationException::class.java) {
                capturePostgresPhase(
                    profile(),
                    environment = ::credentials,
                    checkCancelled = {
                        if (driver.openResultSets > 0) throw CancellationException("stop")
                    },
                )
            }
            assertTrue(driver.closedResultSets > 0)
            assertTrue(driver.closedStatements > 0)
            assertTrue(driver.closedConnections > 0)
        }
    }

    @Test
    fun `strict PostgreSQL gate is on only for the value 1 and rejects other values`() {
        assertFalse(strictPostgresRequired { null })
        assertFalse(strictPostgresRequired { "" })
        assertTrue(strictPostgresRequired { name -> if (name == "LTV_REQUIRE_POSTGRES") "1" else null })
        listOf("true", "0", "1 ", "yes").forEach { value ->
            assertThrows(AssertionFailedError::class.java) { strictPostgresRequired { value } }
        }
    }

    @Test
    fun `strict PostgreSQL gate fails an unmet condition while the default gate skips it`() {
        assumeIntegration(true, "met", strict = true)
        assumeIntegration(true, "met", strict = false)
        val failure =
            assertThrows(AssertionFailedError::class.java) { assumeIntegration(false, "UNVERIFIED: not configured", strict = true) }
        assertTrue(failure.message.orEmpty().contains("UNVERIFIED: not configured"))
        assertThrows(TestAbortedException::class.java) { assumeIntegration(false, "UNVERIFIED: not configured", strict = false) }
    }

    @Test
    fun `dedicated PostgreSQL captures exact synthetic table and statement delta`() {
        assumeIntegration(
            System.getenv("LT_VERDICT_PG_IT_DEDICATED") == "true",
            "UNVERIFIED: set LT_VERDICT_PG_IT_DEDICATED=true for the opt-in real PostgreSQL gate",
        )
        val host = integrationEnvironment("LT_VERDICT_PG_IT_HOST")
        val port = integrationEnvironment("LT_VERDICT_PG_IT_PORT").toIntOrNull()
        assumeIntegration(port != null, "UNVERIFIED: LT_VERDICT_PG_IT_PORT must be an integer")
        val database = integrationEnvironment("LT_VERDICT_PG_IT_DATABASE")
        val adminUser = integrationEnvironment("LT_VERDICT_PG_IT_ADMIN_USER")
        val adminPassword = integrationEnvironment("LT_VERDICT_PG_IT_ADMIN_PASSWORD")
        val captureUser = integrationEnvironment("LT_VERDICT_PG_IT_CAPTURE_USER")
        integrationEnvironment("LT_VERDICT_PG_IT_CAPTURE_PASSWORD")
        assumeIntegration(adminUser != captureUser, "UNVERIFIED: capture role must be separate from the admin role")
        val allowInsecure = System.getenv("LT_VERDICT_PG_IT_ALLOW_INSECURE") == "true"
        val schema = "lt_verdict_it_${UUID.randomUUID().toString().replace("-", "")}"
        val integrationProfile =
            PostgresProfile(
                id = "postgres-real-integration",
                sourceDatabaseId = "postgres-dedicated-test",
                host = host,
                port = requireNotNull(port),
                database = database,
                usernameEnv = "LT_VERDICT_PG_IT_CAPTURE_USER",
                passwordEnv = "LT_VERDICT_PG_IT_CAPTURE_PASSWORD",
                allowInsecure = allowInsecure,
                tables =
                    listOf(
                        PostgresTableProfile(
                            schema = schema,
                            table = "events",
                            columns = listOf("id", "state"),
                            key = listOf("id"),
                            rowLimit = 100,
                        ),
                        PostgresTableProfile(
                            schema = schema,
                            table = "oversized_values",
                            columns = listOf("id", "payload"),
                            key = listOf("id"),
                            rowLimit = 10,
                        ),
                    ),
            )
        validatePostgresProfile(integrationProfile)
        val adminProperties =
            Properties().apply {
                setProperty("user", adminUser)
                setProperty("password", adminPassword)
                setProperty("sslmode", if (allowInsecure) "disable" else "verify-full")
                setProperty("connectTimeout", "30")
                setProperty("socketTimeout", "30")
            }
        val hostPart = if (':' in host) "[$host]" else host
        val adminUrl = "jdbc:postgresql://$hostPart:$port/${integrationDatabasePath(database)}"

        DriverManager.getConnection(adminUrl, adminProperties).use { admin ->
            var schemaCreated = false
            try {
                assertEquals(database, admin.singleString("SELECT pg_catalog.current_database()"))
                val pgssSchema =
                    admin.singleString(
                        "SELECT n.nspname FROM pg_catalog.pg_extension e " +
                            "JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace " +
                            "WHERE e.extname = 'pg_stat_statements'",
                    )
                assumeIntegration(pgssSchema != null, "UNVERIFIED: pg_stat_statements is not installed")
                admin.createStatement().use { statement ->
                    statement.queryTimeout = 30
                    statement.execute("CREATE SCHEMA ${testIdentifier(schema)}")
                    schemaCreated = true
                    statement.execute(
                        "CREATE TABLE ${testIdentifier(schema)}.\"events\" " +
                            "(\"id\" bigint PRIMARY KEY, \"state\" text NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE ${testIdentifier(schema)}.\"oversized_values\" " +
                            "(\"id\" bigint PRIMARY KEY, \"payload\" text NOT NULL)",
                    )
                    statement.execute("GRANT USAGE ON SCHEMA ${testIdentifier(schema)} TO ${testIdentifier(captureUser)}")
                    statement.execute(
                        "GRANT SELECT ON ${testIdentifier(schema)}.\"events\" TO ${testIdentifier(captureUser)}",
                    )
                    statement.execute(
                        "GRANT SELECT ON ${testIdentifier(schema)}.\"oversized_values\" TO ${testIdentifier(captureUser)}",
                    )
                    statement.execute(
                        "INSERT INTO ${testIdentifier(schema)}.\"events\" (\"id\", \"state\") VALUES (1, 'before')",
                    )
                    statement.execute(
                        "INSERT INTO ${testIdentifier(schema)}.\"oversized_values\" (\"id\", \"payload\") " +
                            "VALUES (1, pg_catalog.repeat('x', 16777217))",
                    )
                }

                val pre = capturePostgresPhase(integrationProfile).phase
                val runStart = pre.long("capture_ended_epoch_ms")
                admin.createStatement().use { statement ->
                    statement.queryTimeout = 30
                    statement.execute(
                        "INSERT INTO ${testIdentifier(schema)}.\"events\" (\"id\", \"state\") VALUES (2, 'after')",
                    )
                }
                val runEnd = System.currentTimeMillis()
                val post = capturePostgresPhase(integrationProfile, pre = pre, post = true).phase
                val statementKey = admin.workloadStatementKey(requireNotNull(pgssSchema), schema)
                val context = comparePostgresPhases(pre, post, "a".repeat(64), runStart, runEnd)
                assertEquals("COMPLETE", pre.obj("statements").string("status"), pre.obj("statements").toString())
                assertEquals("COMPLETE", post.obj("statements").string("status"), post.obj("statements").toString())
                assertTrue(context.obj("statements").arr("rows").isNotEmpty(), context.obj("statements").toString())
                val tableDelta = context.arr("tables").map { it.jsonObject }.single { it.string("table") == "events" }
                val oversizedPre = pre.arr("tables").map { it.jsonObject }.single { it.string("table") == "oversized_values" }
                val oversizedPost = post.arr("tables").map { it.jsonObject }.single { it.string("table") == "oversized_values" }
                val excludedUserId = pre.obj("configuration").string("lt_verdict.excluded_statement_userid")
                val statementDelta =
                    context
                        .obj("statements")
                        .arr("rows")
                        .map { it.jsonObject }
                        .single {
                            it.string("dbid") == statementKey.dbid &&
                                it.string("userid") == statementKey.userid &&
                                it.string("queryid") == statementKey.queryid &&
                                it
                                    .getValue("toplevel")
                                    .jsonPrimitive.content
                                    .toBoolean() == statementKey.toplevel
                        }

                assertEquals(1L, tableDelta.long("inserted"))
                assertEquals(0L, tableDelta.long("deleted"))
                assertEquals(0L, tableDelta.long("updated"))
                assertEquals("PG_TABLE_CELL_TOO_LARGE", oversizedPre.string("reason"))
                assertEquals("PG_TABLE_CELL_TOO_LARGE", oversizedPost.string("reason"))
                assertTrue(pre.obj("statements").arr("rows").none { it.jsonObject.string("userid") == excludedUserId })
                assertTrue(post.obj("statements").arr("rows").none { it.jsonObject.string("userid") == excludedUserId })
                assertFalse(statementKey.userid == excludedUserId)
                assertEquals(1L, statementDelta.long("calls"))
                assertEquals(SETTING_NAMES_FOR_TEST, pre.obj("configuration").keys)
            } finally {
                if (schemaCreated) {
                    admin.createStatement().use { statement ->
                        statement.queryTimeout = 30
                        statement.execute("DROP SCHEMA ${testIdentifier(schema)} CASCADE")
                    }
                }
            }
        }
    }

    private fun profile(
        table: PostgresTableProfile = table("events"),
        pgProfileServerId: Int? = null,
        pgProfileStartSampleId: Int? = null,
        pgProfileEndSampleId: Int? = null,
    ): PostgresProfile =
        PostgresProfile(
            id = "orders",
            sourceDatabaseId = "orders-test",
            host = "db.example",
            database = "orders db",
            usernameEnv = "PG_TEST_USER",
            passwordEnv = "PG_TEST_PASSWORD",
            tables = listOf(table),
            pgProfileServerId = pgProfileServerId,
            pgProfileStartSampleId = pgProfileStartSampleId,
            pgProfileEndSampleId = pgProfileEndSampleId,
        )

    private fun table(name: String): PostgresTableProfile =
        PostgresTableProfile(
            schema = "public",
            table = name,
            columns = listOf("id", "state"),
            key = listOf("id"),
        )
}

private data class RecordedQuery(
    val sql: String,
    val parameters: Map<Int, Any?>,
    val maxRows: Int,
    val fetchSize: Int,
)

private data class IntegrationStatementKey(
    val dbid: String,
    val userid: String,
    val queryid: String,
    val toplevel: Boolean,
)

private class JdbcFixture(
    val transactionReadOnly: Boolean = true,
    val captureUserId: String = "999",
    val tableRows: List<List<Any?>> = listOf(listOf("1", "new"), listOf("2", null)),
    val extensions: List<List<Any?>> = emptyList(),
    var statementRows: List<List<Any?>> = emptyList(),
    val tableCount: Long = tableRows.size.toLong(),
    val reportBytes: ByteArray? = null,
) : Driver {
    val urls = mutableListOf<String>()
    val connectionProperties = mutableListOf<Properties>()
    val readOnlyValues = mutableListOf<Boolean>()
    val autoCommitValues = mutableListOf<Boolean>()
    val isolationValues = mutableListOf<Int>()
    val executedSql = mutableListOf<String>()
    val queries = mutableListOf<RecordedQuery>()
    val queryTimeouts = mutableListOf<Int>()
    var failTableSelect = false
    var savepointRollbacks = 0
    var openResultSets = 0
    var closedResultSets = 0
    var closedStatements = 0
    var closedConnections = 0
    private var savepointId = 0

    override fun connect(
        url: String?,
        info: Properties?,
    ): Connection? {
        if (!acceptsURL(url)) return null
        urls += requireNotNull(url)
        connectionProperties += Properties().apply { putAll(requireNotNull(info)) }
        return connection()
    }

    override fun acceptsURL(url: String?): Boolean = url?.startsWith("jdbc:postgresql://db.example:") == true

    override fun getPropertyInfo(
        url: String?,
        info: Properties?,
    ): Array<DriverPropertyInfo> = emptyArray()

    override fun getMajorVersion(): Int = 1

    override fun getMinorVersion(): Int = 0

    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger = Logger.getGlobal()

    fun allSql(): String = (executedSql + queries.map(RecordedQuery::sql)).joinToString("\n")

    private fun connection(): Connection =
        jdbcProxy(Connection::class.java) { method, arguments ->
            when (method.name) {
                "setReadOnly" -> readOnlyValues += arguments[0] as Boolean
                "setAutoCommit" -> autoCommitValues += arguments[0] as Boolean
                "setTransactionIsolation" -> isolationValues += arguments[0] as Int
                "createStatement" -> statement()
                "prepareStatement" -> statement(arguments[0] as String)
                "setSavepoint" -> TestSavepoint(++savepointId)
                "releaseSavepoint" -> Unit
                "rollback" -> if (arguments.isNotEmpty()) savepointRollbacks++
                "close" -> closedConnections++
                "isClosed" -> closedConnections > 0
                "getAutoCommit" -> false
                "isReadOnly" -> readOnlyValues.lastOrNull() ?: false
                "getTransactionIsolation" -> isolationValues.lastOrNull() ?: Connection.TRANSACTION_READ_COMMITTED
                "isValid" -> true
                "isWrapperFor" -> false
                "unwrap" -> null
                else -> defaultJdbcValue(method.returnType)
            }
        }

    private fun statement(sql: String? = null): Statement {
        val parameters = linkedMapOf<Int, Any?>()
        var maxRows = 0
        var fetchSize = 0
        var closed = false
        val type = if (sql == null) Statement::class.java else PreparedStatement::class.java
        return jdbcProxy(type) { method, arguments ->
            when (method.name) {
                "setQueryTimeout" -> queryTimeouts += arguments[0] as Int
                "setFetchSize" -> fetchSize = arguments[0] as Int
                "setMaxRows" -> maxRows = arguments[0] as Int
                "setInt", "setLong", "setString", "setObject" -> parameters[arguments[0] as Int] = arguments[1]
                "clearParameters" -> parameters.clear()
                "execute" -> {
                    executedSql += (arguments.firstOrNull() as? String ?: requireNotNull(sql))
                    false
                }

                "executeQuery" -> {
                    val query = arguments.firstOrNull() as? String ?: requireNotNull(sql)
                    val snapshot = parameters.toMap()
                    queries += RecordedQuery(query, snapshot, maxRows, fetchSize)
                    resultSet(rowsFor(query, snapshot))
                }

                "close" ->
                    if (!closed) {
                        closed = true
                        closedStatements++
                    }

                "isClosed" -> closed
                "getConnection" -> null
                "isWrapperFor" -> false
                "unwrap" -> null
                else -> defaultJdbcValue(method.returnType)
            }
        }
    }

    private fun resultSet(rows: List<List<Any?>>): ResultSet {
        var rowIndex = -1
        var lastWasNull = false
        var closed = false
        openResultSets++
        return jdbcProxy(ResultSet::class.java) { method, arguments ->
            fun value(): Any? {
                val column = (arguments[0] as Int) - 1
                return rows[rowIndex][column].also { lastWasNull = it == null }
            }

            when (method.name) {
                "next" -> (++rowIndex) < rows.size
                "getString" -> value()?.toString()
                "getLong" ->
                    when (val raw = value()) {
                        null -> 0L
                        is Number -> raw.toLong()
                        else -> raw.toString().toLong()
                    }

                "getInt" ->
                    when (val raw = value()) {
                        null -> 0
                        is Number -> raw.toInt()
                        else -> raw.toString().toInt()
                    }

                "getBoolean" ->
                    when (val raw = value()) {
                        null -> false
                        is Boolean -> raw
                        else -> raw.toString().toBooleanStrict()
                    }

                "getBigDecimal" ->
                    when (val raw = value()) {
                        null -> null
                        is BigDecimal -> raw
                        else -> BigDecimal(raw.toString())
                    }

                "getTimestamp" ->
                    when (val raw = value()) {
                        null -> null
                        is Timestamp -> raw
                        is Instant -> Timestamp.from(raw)
                        else -> Timestamp.from(Instant.parse(raw.toString()))
                    }

                "getBinaryStream" ->
                    value()?.let { raw ->
                        ByteArrayInputStream(if (raw is ByteArray) raw else raw.toString().encodeToByteArray())
                    }

                "wasNull" -> lastWasNull
                "close" ->
                    if (!closed) {
                        closed = true
                        openResultSets--
                        closedResultSets++
                    }

                "isClosed" -> closed
                "isWrapperFor" -> false
                "unwrap" -> null
                else -> defaultJdbcValue(method.returnType)
            }
        }
    }

    private fun rowsFor(
        sql: String,
        parameters: Map<Int, Any?>,
    ): List<List<Any?>> =
        when {
            sql.contains("current_setting('server_version')") ->
                listOf(listOf("15.5", "orders db", if (transactionReadOnly) "on" else "off", captureUserId))

            sql.contains("pg_catalog.pg_settings") ->
                listOf(
                    listOf("effective_cache_size", "524288"),
                    listOf("max_connections", "100"),
                    listOf("shared_buffers", "16384"),
                    listOf("track_io_timing", "on"),
                    listOf("work_mem", "4096"),
                )

            sql.contains("pg_catalog.pg_extension") -> extensions
            sql.contains("pg_catalog.pg_attribute") -> listOf(listOf("id", "bigint"), listOf("state", "text"))
            sql.startsWith("SELECT count(*)::bigint FROM") -> listOf(listOf(tableCount))
            sql.contains(".\"get_report\"") -> listOf(listOf(reportBytes ?: ByteArray(0)))
            sql.contains("pg_catalog.convert_to") -> {
                if (failTableSelect) throw SQLException("fixture table failure password=not-a-secret")
                tableRows
            }

            sql.contains(".\"pg_stat_statements_info\"") ->
                listOf(listOf(Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), 0L))

            sql.contains(".\"pg_stat_statements\"") ->
                if (sql.contains("userid <> ?::oid")) {
                    statementRows.filterNot { row -> row[1].toString() == parameters[1].toString() }
                } else {
                    statementRows
                }
            sql.contains("current_setting('pg_profile.topn', true)") -> listOf(listOf("20"))
            else -> throw SQLException("unexpected fixture SQL: $sql; params=$parameters")
        }
}

private data class TestSavepoint(
    private val value: Int,
) : Savepoint {
    override fun getSavepointId(): Int = value

    override fun getSavepointName(): String = "pg_$value"
}

private fun <T> withDriver(
    driver: JdbcFixture,
    block: (JdbcFixture) -> T,
): T =
    synchronized(DriverManager::class.java) {
        val original = Collections.list(DriverManager.getDrivers())
        original.forEach(DriverManager::deregisterDriver)
        DriverManager.registerDriver(driver)
        try {
            block(driver)
        } finally {
            DriverManager.deregisterDriver(driver)
            original.forEach(DriverManager::registerDriver)
        }
    }

private fun credentials(name: String): String? =
    when (name) {
        "PG_TEST_USER" -> "reader"
        "PG_TEST_PASSWORD" -> "top-secret"
        else -> null
    }

private fun statementRow(
    userid: String,
    queryid: String,
    calls: Long,
): List<Any?> = listOf("1", userid, queryid, true, calls, BigDecimal.valueOf(calls), calls, calls, calls, calls)

private fun integrationEnvironment(name: String): String {
    val value = System.getenv(name)
    assumeIntegration(!value.isNullOrBlank(), "UNVERIFIED: $name is not configured")
    return requireNotNull(value)
}

private const val REQUIRE_POSTGRES_ENV = "LTV_REQUIRE_POSTGRES"

// Strict gate: with LTV_REQUIRE_POSTGRES=1 a missing PostgreSQL setup fails the test instead of skipping it.
// Any other non-empty value fails too, so a typo cannot switch the gate off silently.
private fun strictPostgresRequired(environment: (String) -> String? = System::getenv): Boolean =
    when (val value = environment(REQUIRE_POSTGRES_ENV)) {
        null, "" -> false
        "1" -> true
        else -> fail("$REQUIRE_POSTGRES_ENV must be unset, empty or 1, got '$value'")
    }

private fun assumeIntegration(
    condition: Boolean,
    message: String,
    strict: Boolean = strictPostgresRequired(),
) {
    if (!condition && strict) fail("$message ($REQUIRE_POSTGRES_ENV=1 requires a configured PostgreSQL)")
    assumeTrue(condition, message)
}

private fun integrationDatabasePath(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { raw ->
            val byte = raw.toInt() and 0xff
            if ((byte in 'a'.code..'z'.code) ||
                (byte in 'A'.code..'Z'.code) ||
                (byte in '0'.code..'9'.code) ||
                byte == '-'.code ||
                byte == '.'.code ||
                byte == '_'.code ||
                byte == '~'.code
            ) {
                append(byte.toChar())
            } else {
                append("%%%02X".format(byte))
            }
        }
    }

private fun testIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun Connection.singleString(sql: String): String? =
    createStatement().use { statement ->
        statement.queryTimeout = 30
        statement.executeQuery(sql).use { rows ->
            if (!rows.next()) return null
            val value = rows.getString(1)
            check(!rows.next())
            value
        }
    }

private fun Connection.workloadStatementKey(
    extensionSchema: String,
    testSchema: String,
): IntegrationStatementKey =
    prepareStatement(
        "SELECT dbid::text, userid::text, queryid::text, toplevel " +
            "FROM ${testIdentifier(extensionSchema)}.\"pg_stat_statements\" " +
            "WHERE userid = (SELECT usesysid FROM pg_catalog.pg_user WHERE usename = current_user) " +
            "AND query LIKE 'INSERT INTO%' AND pg_catalog.strpos(query, ?) > 0 " +
            "ORDER BY calls DESC LIMIT 1",
    ).use { statement ->
        statement.queryTimeout = 30
        statement.setString(1, testSchema)
        statement.executeQuery().use { rows ->
            check(rows.next()) { "synthetic INSERT was not visible in pg_stat_statements" }
            val key =
                IntegrationStatementKey(
                    dbid = requireNotNull(rows.getString(1)),
                    userid = requireNotNull(rows.getString(2)),
                    queryid = requireNotNull(rows.getString(3)),
                    toplevel = rows.getBoolean(4),
                )
            check(!rows.next())
            key
        }
    }

private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.arr(name: String) = getValue(name).jsonArray

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

private val SETTING_NAMES_FOR_TEST =
    setOf(
        "shared_buffers",
        "work_mem",
        "max_connections",
        "effective_cache_size",
        "track_io_timing",
        "lt_verdict.excluded_statement_userid",
    )

@Suppress("UNCHECKED_CAST")
private fun <T> jdbcProxy(
    type: Class<T>,
    handler: (Method, Array<out Any?>) -> Any?,
): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
        handler(method, arguments ?: emptyArray())
    } as T

private fun defaultJdbcValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

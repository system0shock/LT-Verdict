package io.ltverdict.sources

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties

internal data class PostgresTableProfile(
    val schema: String,
    val table: String,
    val columns: List<String>,
    val key: List<String> = emptyList(),
    val rowLimit: Int = MAX_TABLE_ROWS,
    val byteLimit: Int = MAX_TABLE_BYTES,
)

internal data class PostgresProfile(
    val id: String,
    val sourceDatabaseId: String,
    val host: String,
    val port: Int = 5432,
    val database: String,
    val usernameEnv: String,
    val passwordEnv: String,
    val allowInsecure: Boolean = false,
    val tables: List<PostgresTableProfile> = emptyList(),
    val pgProfileServerId: Int? = null,
    val pgProfileStartSampleId: Int? = null,
    val pgProfileEndSampleId: Int? = null,
)

internal data class PostgresCapturedPhase(
    val phase: JsonObject,
    val pgProfileHtml: ByteArray?,
)

internal fun capturePostgresPhase(
    profile: PostgresProfile,
    pre: JsonObject? = null,
    post: Boolean = false,
    environment: (String) -> String? = System::getenv,
    checkCancelled: () -> Unit = {},
): PostgresCapturedPhase {
    validatePostgresProfile(profile)
    if (!post && pre != null) postgresFailure("PG_PRE_UNEXPECTED")
    val validatedPre = pre?.let { validatePostgresPhase(ByteArrayInputStream(canonicalJson(it))) }
    checkCancelled()
    val username = readCredential(profile.usernameEnv, environment)
    val password = readCredential(profile.passwordEnv, environment)
    val connection =
        try {
            DriverManager.getConnection(
                jdbcUrl(profile),
                connectionProperties(profile, username, password),
            )
        } catch (_: SQLException) {
            postgresFailure("PG_CONNECTION_FAILED")
        }

    return try {
        connection.use {
            captureOnConnection(profile, validatedPre, post, it, checkCancelled)
        }
    } catch (_: SQLException) {
        postgresFailure("PG_CAPTURE_FAILED")
    }
}

private fun captureOnConnection(
    profile: PostgresProfile,
    pre: JsonObject?,
    post: Boolean,
    connection: Connection,
    checkCancelled: () -> Unit,
): PostgresCapturedPhase {
    val started = System.currentTimeMillis()
    configureConnection(connection, checkCancelled)
    val core = captureCore(connection, checkCancelled)
    if (core.database != profile.database) postgresFailure("PG_DATABASE_MISMATCH")
    if (!core.readOnly) postgresFailure("PG_READ_ONLY_REQUIRED")
    val configuration =
        captureConfiguration(connection, checkCancelled) +
            (EXCLUDED_STATEMENT_USERID to core.currentUserId.toString())
    val extensions = captureExtensions(connection, checkCancelled)
    var statements: JsonObject? = null
    if (post) {
        statements = captureStatements(connection, extensions[PG_STAT_STATEMENTS], core.currentUserId, checkCancelled)
    }
    val tables =
        profile.tables
            .sortedWith(compareBy(PostgresTableProfile::schema, PostgresTableProfile::table))
            .map { captureTable(connection, it, checkCancelled) }
    val pgProfile = capturePgProfile(connection, profile, extensions[PG_PROFILE], checkCancelled)
    if (!post) {
        statements = captureStatements(connection, extensions[PG_STAT_STATEMENTS], core.currentUserId, checkCancelled)
    }
    checkCancelled()
    val ended = maxOf(started, System.currentTimeMillis())
    connection.rollback()
    val phase =
        buildJsonObject {
            put("schema_version", "postgres-phase.v1")
            put("phase", if (post) "post" else "pre")
            put("profile_id", profile.id)
            put("source_database_id", profile.sourceDatabaseId)
            put("profile_revision_sha256", profileRevision(profile))
            putNullableString("pre_sha256", pre?.let { sha256Hex(canonicalJson(it)) })
            put("capture_started_epoch_ms", started)
            put("capture_ended_epoch_ms", ended)
            put("server_version", core.serverVersion)
            put("configuration", JsonObject(configuration.toSortedMap().mapValues { JsonPrimitive(it.value) }))
            put("tables", JsonArray(tables))
            put("statements", requireNotNull(statements))
            put("pg_profile", pgProfile.json)
        }
    val validated = validatePostgresPhase(ByteArrayInputStream(canonicalJson(phase)))
    return PostgresCapturedPhase(validated, pgProfile.html?.copyOf())
}

private fun configureConnection(
    connection: Connection,
    checkCancelled: () -> Unit,
) {
    connection.isReadOnly = true
    connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
    connection.autoCommit = false
    connection.createStatement().use { statement ->
        configureStatement(statement)
        SESSION_SQL.forEach { sql ->
            checkCancelled()
            statement.execute(sql)
        }
    }
}

private fun captureCore(
    connection: Connection,
    checkCancelled: () -> Unit,
): CoreCapture =
    query(connection, CORE_SQL, checkCancelled) { rows ->
        if (!next(rows, checkCancelled)) postgresFailure("PG_CAPTURE_FAILED")
        val serverVersion = rows.getString(1) ?: postgresFailure("PG_CAPTURE_FAILED")
        val database = rows.getString(2) ?: postgresFailure("PG_CAPTURE_FAILED")
        val readOnly = rows.getString(3) == "on"
        val currentUserId = rows.requiredLong(4)
        if (next(rows, checkCancelled) ||
            !serverVersion.validText(128) ||
            !database.validText(63) ||
            currentUserId <= 0
        ) {
            postgresFailure("PG_CAPTURE_FAILED")
        }
        CoreCapture(serverVersion, database, readOnly, currentUserId)
    }

private fun captureConfiguration(
    connection: Connection,
    checkCancelled: () -> Unit,
): Map<String, String> =
    query(connection, SETTINGS_SQL, checkCancelled) { rows ->
        buildMap {
            while (next(rows, checkCancelled)) {
                val name = rows.getString(1) ?: postgresFailure("PG_CAPTURE_FAILED")
                val value = rows.getString(2) ?: postgresFailure("PG_CAPTURE_FAILED")
                if (name !in SETTING_NAMES || !value.validText(4096, allowEmpty = true) || put(name, value) != null) {
                    postgresFailure("PG_CAPTURE_FAILED")
                }
            }
        }.also { if (it.keys != SETTING_NAMES) postgresFailure("PG_CAPTURE_FAILED") }
    }

private fun captureExtensions(
    connection: Connection,
    checkCancelled: () -> Unit,
): Map<String, ExtensionCapture> =
    query(connection, EXTENSIONS_SQL, checkCancelled) { rows ->
        buildMap {
            while (next(rows, checkCancelled)) {
                val name = rows.getString(1) ?: postgresFailure("PG_CAPTURE_FAILED")
                val version = rows.getString(2) ?: postgresFailure("PG_CAPTURE_FAILED")
                val schema = rows.getString(3) ?: postgresFailure("PG_CAPTURE_FAILED")
                if (name !in EXTENSION_NAMES ||
                    !version.validText(128) ||
                    !schema.validIdentifier() ||
                    put(name, ExtensionCapture(version, schema)) != null
                ) {
                    postgresFailure("PG_CAPTURE_FAILED")
                }
            }
        }
    }

private fun captureTable(
    connection: Connection,
    profile: PostgresTableProfile,
    checkCancelled: () -> Unit,
): JsonObject {
    val savepoint = connection.setSavepoint()
    return try {
        val result = captureTableModule(connection, profile, checkCancelled)
        connection.releaseSavepoint(savepoint)
        result
    } catch (_: SQLException) {
        connection.rollback(savepoint)
        tableJson(profile, unknownTypes(profile), "FAILED", "PG_TABLE_QUERY_FAILED", null, emptyList())
    } catch (_: InvalidModuleData) {
        connection.rollback(savepoint)
        tableJson(profile, unknownTypes(profile), "FAILED", "PG_TABLE_INVALID_DATA", null, emptyList())
    }
}

private fun captureTableModule(
    connection: Connection,
    profile: PostgresTableProfile,
    checkCancelled: () -> Unit,
): JsonObject {
    val columnTypes =
        captureColumnTypes(connection, profile, checkCancelled)
            ?: return tableJson(
                profile,
                unknownTypes(profile),
                "FAILED",
                "PG_TABLE_SCHEMA_MISMATCH",
                null,
                emptyList(),
            )
    if (profile.key.isEmpty()) {
        val count =
            query(connection, "SELECT count(*)::bigint FROM ${qualified(profile)}", checkCancelled) { rows ->
                if (!next(rows, checkCancelled)) throw InvalidModuleData()
                val value = rows.requiredLong(1)
                if (value < 0 || next(rows, checkCancelled)) throw InvalidModuleData()
                value
            }
        return tableJson(profile, columnTypes, "DEGRADED", "PG_TABLE_NO_STABLE_KEY", count, emptyList())
    }

    val capturedRows = mutableListOf<JsonArray>()
    val keys = HashSet<List<String?>>()
    var serializedBytes = 2
    var reason: String? = null
    query(
        connection,
        keyedTableSql(profile),
        checkCancelled,
        maxRows = profile.rowLimit + 1,
        fetchSize = TABLE_FETCH_SIZE,
    ) { rows ->
        while (next(rows, checkCancelled)) {
            if (capturedRows.size == profile.rowLimit) {
                reason = "PG_TABLE_TRUNCATED"
                break
            }
            val values =
                try {
                    profile.columns.indices.map { index -> readCell(rows, index + 1, checkCancelled) }
                } catch (_: CellTooLarge) {
                    reason = "PG_TABLE_CELL_TOO_LARGE"
                    break
                }
            val row = values.jsonRow()
            val rowBytes = canonicalJson(row).size + if (capturedRows.isEmpty()) 0 else 1
            if (serializedBytes + rowBytes > profile.byteLimit) {
                reason = "PG_TABLE_TRUNCATED"
                break
            }
            serializedBytes += rowBytes
            capturedRows += row
            val key = profile.key.map { values[profile.columns.indexOf(it)] }
            if (key.any { it == null } || !keys.add(key)) reason = reason ?: "PG_TABLE_KEY_INVALID"
        }
    }
    val rowCount = if (reason == null || reason == "PG_TABLE_KEY_INVALID") capturedRows.size.toLong() else null
    return tableJson(
        profile,
        columnTypes,
        if (reason == null) "COMPLETE" else "DEGRADED",
        reason,
        rowCount,
        capturedRows,
    )
}

private fun captureColumnTypes(
    connection: Connection,
    profile: PostgresTableProfile,
    checkCancelled: () -> Unit,
): List<String>? {
    val placeholders = List(profile.columns.size) { "?" }.joinToString(",")
    val sql = "$TABLE_METADATA_SQL AND a.attname IN ($placeholders) ORDER BY a.attnum"
    val byName =
        preparedQuery(
            connection,
            sql,
            checkCancelled,
            bind = { statement ->
                statement.setString(1, profile.schema)
                statement.setString(2, profile.table)
                profile.columns.forEachIndexed { index, column -> statement.setString(index + 3, column) }
            },
        ) { rows ->
            buildMap {
                while (next(rows, checkCancelled)) {
                    val name = rows.getString(1) ?: throw InvalidModuleData()
                    val type = rows.getString(2) ?: throw InvalidModuleData()
                    if (name !in profile.columns || !type.validText(128) || put(name, type) != null) {
                        throw InvalidModuleData()
                    }
                }
            }
        }
    return profile.columns.map { byName[it] ?: return null }
}

private fun unknownTypes(profile: PostgresTableProfile): List<String> = List(profile.columns.size) { "unknown" }

private fun captureStatements(
    connection: Connection,
    extension: ExtensionCapture?,
    excludedUserId: Long,
    checkCancelled: () -> Unit,
): JsonObject {
    if (extension == null) {
        return statementsJson("DEGRADED", "PG_STATEMENTS_UNAVAILABLE", null, null, null, emptyList())
    }
    val savepoint = connection.setSavepoint()
    return try {
        val result = captureStatementsModule(connection, extension, excludedUserId, checkCancelled)
        connection.releaseSavepoint(savepoint)
        result
    } catch (_: SQLException) {
        connection.rollback(savepoint)
        statementsJson("DEGRADED", "PG_STATEMENTS_QUERY_FAILED", extension.version, null, null, emptyList())
    } catch (_: InvalidModuleData) {
        connection.rollback(savepoint)
        statementsJson("DEGRADED", "PG_STATEMENTS_INVALID_DATA", extension.version, null, null, emptyList())
    }
}

private fun captureStatementsModule(
    connection: Connection,
    extension: ExtensionCapture,
    excludedUserId: Long,
    checkCancelled: () -> Unit,
): JsonObject {
    val schema = quoteIdentifier(extension.schema)
    val info =
        query(
            connection,
            "SELECT stats_reset, dealloc FROM $schema.\"pg_stat_statements_info\"",
            checkCancelled,
        ) { rows ->
            if (!next(rows, checkCancelled)) throw InvalidModuleData()
            val reset = rows.getTimestamp(1)?.toInstant()?.toString()
            val dealloc = rows.nullableLong(2)
            if (next(rows, checkCancelled) || dealloc?.let { it < 0 } == true) throw InvalidModuleData()
            StatementInfo(reset, dealloc)
        }
    val capturedRows = mutableListOf<JsonObject>()
    val keys = HashSet<List<Any?>>()
    var reason = if (info.statsReset == null || info.dealloc == null) "PG_STATEMENTS_RESET_UNKNOWN" else null
    preparedQuery(
        connection,
        statementSql(schema),
        checkCancelled,
        maxRows = MAX_STATEMENT_ROWS + 1,
        bind = { statement -> statement.setLong(1, excludedUserId) },
    ) { rows ->
        while (next(rows, checkCancelled)) {
            if (capturedRows.size == MAX_STATEMENT_ROWS) {
                reason = "PG_STATEMENTS_TRUNCATED"
                break
            }
            val dbid = rows.getString(1)?.also(::requireIntegerString) ?: throw InvalidModuleData()
            val userid = rows.getString(2)?.also(::requireIntegerString) ?: throw InvalidModuleData()
            val queryid = rows.getString(3)?.also(::requireIntegerString)
            val toplevel = rows.requiredBoolean(4)
            if (!keys.add(listOf(dbid, userid, queryid, toplevel))) throw InvalidModuleData()
            if (queryid == null) reason = reason ?: "PG_STATEMENTS_QUERY_ID_UNAVAILABLE"
            capturedRows +=
                buildJsonObject {
                    put("dbid", dbid)
                    put("userid", userid)
                    putNullableString("queryid", queryid)
                    put("toplevel", toplevel)
                    put("calls", rows.requiredNonnegativeLong(5))
                    put("total_exec_time", rows.requiredDecimal(6))
                    put("rows", rows.requiredNonnegativeLong(7))
                    put("shared_blks_hit", rows.requiredNonnegativeLong(8))
                    put("shared_blks_read", rows.requiredNonnegativeLong(9))
                    put("temp_blks_written", rows.requiredNonnegativeLong(10))
                }
        }
    }
    return statementsJson(
        if (reason == null) "COMPLETE" else "DEGRADED",
        reason,
        extension.version,
        info.statsReset,
        info.dealloc,
        capturedRows,
    )
}

private fun capturePgProfile(
    connection: Connection,
    profile: PostgresProfile,
    extension: ExtensionCapture?,
    checkCancelled: () -> Unit,
): PgProfileCapture {
    if (extension == null) return pgProfileJson("PG_PROFILE_UNAVAILABLE", null, profile, null)
    val savepoint = connection.setSavepoint()
    return try {
        val setting =
            query(connection, "SELECT current_setting('pg_profile.topn', true)", checkCancelled) { rows ->
                if (!next(rows, checkCancelled)) throw InvalidModuleData()
                val value = rows.getString(1)
                if (next(rows, checkCancelled)) throw InvalidModuleData()
                value
            }
        if (profile.pgProfileServerId == null) {
            connection.releaseSavepoint(savepoint)
            return pgProfileJson("PG_PROFILE_REPORT_NOT_CONFIGURED", extension.version, profile, null)
        }
        val schema = quoteIdentifier(extension.schema)
        val html =
            preparedQuery(
                connection,
                "SELECT pg_catalog.substr(" +
                    "pg_catalog.convert_to($schema.\"get_report\"(?::integer, ?::integer, ?::integer), 'UTF8'), " +
                    "1, ${MAX_REPORT_BYTES + 1})",
                checkCancelled,
                bind = { statement ->
                    statement.setInt(1, profile.pgProfileServerId)
                    statement.setInt(2, requireNotNull(profile.pgProfileStartSampleId))
                    statement.setInt(3, requireNotNull(profile.pgProfileEndSampleId))
                },
            ) { rows ->
                if (!next(rows, checkCancelled)) throw InvalidModuleData()
                val stream = rows.getBinaryStream(1) ?: throw InvalidModuleData()
                val bytes = stream.use { readBounded(it, MAX_REPORT_BYTES, checkCancelled) }
                if (next(rows, checkCancelled)) throw InvalidModuleData()
                requireUtf8(bytes)
                bytes
            }
        connection.releaseSavepoint(savepoint)
        pgProfileJson(
            if (setting == null) "PG_PROFILE_SETTING_UNAVAILABLE" else "PG_PROFILE_RESET_UNKNOWN",
            extension.version,
            profile,
            html,
        )
    } catch (_: TooLarge) {
        connection.rollback(savepoint)
        pgProfileJson("PG_PROFILE_REPORT_TOO_LARGE", extension.version, profile, null)
    } catch (_: CharacterCodingException) {
        connection.rollback(savepoint)
        pgProfileJson("PG_PROFILE_REPORT_INVALID_UTF8", extension.version, profile, null)
    } catch (_: SQLException) {
        connection.rollback(savepoint)
        pgProfileJson("PG_PROFILE_REPORT_UNAVAILABLE", extension.version, profile, null)
    } catch (_: InvalidModuleData) {
        connection.rollback(savepoint)
        pgProfileJson("PG_PROFILE_REPORT_UNAVAILABLE", extension.version, profile, null)
    }
}

private fun profileRevision(profile: PostgresProfile): String =
    sha256Hex(
        canonicalJson(
            buildJsonObject {
                put("id", profile.id)
                put("source_database_id", profile.sourceDatabaseId)
                put("host", profile.host)
                put("port", profile.port)
                put("database", profile.database)
                put("username_env", profile.usernameEnv)
                put("password_env", profile.passwordEnv)
                put("allow_insecure", profile.allowInsecure)
                put(
                    "tables",
                    buildJsonArray {
                        profile.tables
                            .sortedWith(compareBy(PostgresTableProfile::schema, PostgresTableProfile::table))
                            .forEach { table ->
                                add(
                                    buildJsonObject {
                                        put("schema", table.schema)
                                        put("table", table.table)
                                        put("columns", table.columns.jsonStrings())
                                        put("key", table.key.jsonStrings())
                                        put("row_limit", table.rowLimit)
                                        put("byte_limit", table.byteLimit)
                                    },
                                )
                            }
                    },
                )
                putNullableInt("pg_profile_server_id", profile.pgProfileServerId)
                putNullableInt("pg_profile_start_sample_id", profile.pgProfileStartSampleId)
                putNullableInt("pg_profile_end_sample_id", profile.pgProfileEndSampleId)
            },
        ),
    )

private fun tableJson(
    profile: PostgresTableProfile,
    columnTypes: List<String>,
    status: String,
    reason: String?,
    rowCount: Long?,
    rows: List<JsonArray>,
): JsonObject =
    buildJsonObject {
        put("schema", profile.schema)
        put("table", profile.table)
        put("columns", profile.columns.jsonStrings())
        put("column_types", columnTypes.jsonStrings())
        put("stable_key", profile.key.jsonStrings())
        put("row_limit", profile.rowLimit)
        put("byte_limit", profile.byteLimit)
        put("status", status)
        putNullableString("reason", reason)
        putNullableLong("row_count", rowCount)
        put("rows", JsonArray(rows))
    }

private fun statementsJson(
    status: String,
    reason: String?,
    extensionVersion: String?,
    statsReset: String?,
    dealloc: Long?,
    rows: List<JsonObject>,
): JsonObject =
    buildJsonObject {
        put("status", status)
        putNullableString("reason", reason)
        putNullableString("extension_version", extensionVersion)
        putNullableString("stats_reset", statsReset)
        putNullableLong("dealloc", dealloc)
        put("row_limit", MAX_STATEMENT_ROWS)
        put("rows", JsonArray(rows))
    }

private fun pgProfileJson(
    reason: String,
    extensionVersion: String?,
    profile: PostgresProfile,
    html: ByteArray?,
): PgProfileCapture =
    PgProfileCapture(
        buildJsonObject {
            put("status", "DEGRADED")
            put("reason", reason)
            putNullableString("extension_version", extensionVersion)
            put("statements_reset", JsonNull)
            putNullableLong("server_id", profile.pgProfileServerId?.toLong())
            putNullableLong("start_sample_id", profile.pgProfileStartSampleId?.toLong())
            putNullableLong("end_sample_id", profile.pgProfileEndSampleId?.toLong())
            putNullableString("report_sha256", html?.let(::sha256Hex))
        },
        html,
    )

private inline fun <T> query(
    connection: Connection,
    sql: String,
    checkCancelled: () -> Unit,
    maxRows: Int = 0,
    fetchSize: Int = FETCH_SIZE,
    read: (ResultSet) -> T,
): T =
    connection.createStatement().use { statement ->
        configureStatement(statement, maxRows, fetchSize)
        checkCancelled()
        statement.executeQuery(sql).use { rows ->
            checkCancelled()
            read(rows)
        }
    }

private inline fun <T> preparedQuery(
    connection: Connection,
    sql: String,
    checkCancelled: () -> Unit,
    maxRows: Int = 0,
    fetchSize: Int = FETCH_SIZE,
    bind: (PreparedStatement) -> Unit,
    read: (ResultSet) -> T,
): T =
    connection.prepareStatement(sql).use { statement ->
        configureStatement(statement, maxRows, fetchSize)
        bind(statement)
        checkCancelled()
        statement.executeQuery().use { rows ->
            checkCancelled()
            read(rows)
        }
    }

private fun configureStatement(
    statement: Statement,
    maxRows: Int = 0,
    fetchSize: Int = FETCH_SIZE,
) {
    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
    if (maxRows > 0) {
        statement.maxRows = maxRows
        statement.fetchSize = minOf(maxRows, fetchSize)
    }
}

private fun next(
    rows: ResultSet,
    checkCancelled: () -> Unit,
): Boolean {
    checkCancelled()
    return rows.next()
}

private fun readCell(
    rows: ResultSet,
    column: Int,
    checkCancelled: () -> Unit,
): String? {
    val stream = rows.getBinaryStream(column)
    if (stream == null) {
        if (!rows.wasNull()) throw InvalidModuleData()
        return null
    }
    val bytes =
        try {
            stream.use { readBounded(it, MAX_CELL_BYTES, checkCancelled) }
        } catch (_: TooLarge) {
            throw CellTooLarge()
        }
    return try {
        requireUtf8(bytes)
    } catch (_: CharacterCodingException) {
        throw InvalidModuleData()
    }
}

private fun readBounded(
    input: InputStream,
    limit: Int,
    checkCancelled: () -> Unit,
): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        checkCancelled()
        val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - total))
        if (count == -1) return output.toByteArray()
        total += count
        if (total > limit) throw TooLarge()
        output.write(buffer, 0, count)
    }
}

@Throws(CharacterCodingException::class)
private fun requireUtf8(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun ResultSet.requiredLong(column: Int): Long {
    val value = getLong(column)
    if (wasNull()) throw InvalidModuleData()
    return value
}

private fun ResultSet.nullableLong(column: Int): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun ResultSet.requiredNonnegativeLong(column: Int): Long = requiredLong(column).also { if (it < 0) throw InvalidModuleData() }

private fun ResultSet.requiredBoolean(column: Int): Boolean {
    val value = getBoolean(column)
    if (wasNull()) throw InvalidModuleData()
    return value
}

private fun ResultSet.requiredDecimal(column: Int): BigDecimal {
    val value = getBigDecimal(column) ?: throw InvalidModuleData()
    if (value.signum() < 0 || value > MAX_DECIMAL) throw InvalidModuleData()
    return value.setScale(minOf(12, maxOf(0, value.scale())), RoundingMode.HALF_UP).stripTrailingZeros()
}

internal fun validatePostgresProfile(profile: PostgresProfile) {
    val reportIds = listOf(profile.pgProfileServerId, profile.pgProfileStartSampleId, profile.pgProfileEndSampleId)
    if (!profile.id.validText(128) ||
        !profile.sourceDatabaseId.validText(128) ||
        profile.sourceDatabaseId.startsWith("jdbc:", ignoreCase = true) ||
        "://" in profile.sourceDatabaseId ||
        !profile.host.validHost() ||
        profile.port !in 1..65_535 ||
        !profile.database.validText(63) ||
        !ENVIRONMENT_NAME.matches(profile.usernameEnv) ||
        !ENVIRONMENT_NAME.matches(profile.passwordEnv) ||
        profile.tables.size > MAX_TABLES ||
        profile.tables
            .map { it.schema to it.table }
            .toSet()
            .size != profile.tables.size ||
        reportIds.any { it == null } != reportIds.all { it == null } ||
        reportIds.filterNotNull().any { it <= 0 } ||
        (
            profile.pgProfileStartSampleId != null &&
                profile.pgProfileEndSampleId != null &&
                profile.pgProfileStartSampleId >= profile.pgProfileEndSampleId
        ) ||
        profile.tables.any { !it.valid() }
    ) {
        postgresFailure("PG_PROFILE_INVALID")
    }
}

private fun PostgresTableProfile.valid(): Boolean =
    schema.validIdentifier() &&
        table.validIdentifier() &&
        columns.isNotEmpty() &&
        columns.size <= MAX_COLUMNS &&
        columns.toSet().size == columns.size &&
        columns.all(String::validIdentifier) &&
        key.toSet().size == key.size &&
        key.all { it in columns } &&
        rowLimit in 1..MAX_TABLE_ROWS &&
        byteLimit in 2..MAX_TABLE_BYTES

private fun readCredential(
    name: String,
    environment: (String) -> String?,
): String {
    val value =
        try {
            environment(name)
        } catch (_: RuntimeException) {
            null
        }
    if (value.isNullOrEmpty()) postgresFailure("PG_CREDENTIALS_MISSING")
    if (value.encodeToByteArray().size > 4096) postgresFailure("PG_CREDENTIALS_INVALID")
    return value
}

private fun connectionProperties(
    profile: PostgresProfile,
    username: String,
    password: String,
): Properties =
    Properties().apply {
        setProperty("user", username)
        setProperty("password", password)
        setProperty("sslmode", if (profile.allowInsecure) "disable" else "verify-full")
        setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS.toString())
        setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS.toString())
        setProperty("maxResultBuffer", MAX_RESULT_BUFFER_BYTES.toString())
        setProperty("readOnly", "true")
        setProperty("readOnlyMode", "always")
        setProperty("ApplicationName", "lt-verdict")
    }

private fun jdbcUrl(profile: PostgresProfile): String {
    val host = if (':' in profile.host) "[${profile.host}]" else profile.host
    return "jdbc:postgresql://$host:${profile.port}/${encodePathSegment(profile.database)}"
}

private fun encodePathSegment(value: String): String =
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
                append('%')
                append(HEX[byte ushr 4])
                append(HEX[byte and 0x0f])
            }
        }
    }

private fun keyedTableSql(profile: PostgresTableProfile): String {
    val columns =
        profile.columns.joinToString(", ") { column ->
            "pg_catalog.substr(" +
                "pg_catalog.convert_to(${quoteIdentifier(column)}::text, 'UTF8'), 1, ${MAX_CELL_BYTES + 1})"
        }
    val order = profile.key.joinToString(", ", transform = ::quoteIdentifier)
    return "SELECT $columns FROM ${qualified(profile)} ORDER BY $order LIMIT ${profile.rowLimit + 1}"
}

private fun statementSql(schema: String): String =
    """
    SELECT dbid::text, userid::text, queryid::text, toplevel, calls,
           total_exec_time, rows, shared_blks_hit, shared_blks_read, temp_blks_written
    FROM $schema."pg_stat_statements"
    WHERE dbid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = pg_catalog.current_database())
      AND userid <> ?::oid
    ORDER BY dbid, userid, queryid, toplevel
    LIMIT 10001
    """.trimIndent()

private fun qualified(profile: PostgresTableProfile): String = "${quoteIdentifier(profile.schema)}.${quoteIdentifier(profile.table)}"

private fun quoteIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun String.validIdentifier(): Boolean = validText(63)

private fun String.validText(
    maxBytes: Int,
    allowEmpty: Boolean = false,
): Boolean =
    (allowEmpty || isNotEmpty()) &&
        encodeToByteArray().size <= maxBytes &&
        none(Char::isISOControl)

private fun String.validHost(): Boolean =
    length <= 253 &&
        (HOST_NAME.matches(this) || isIpv6Literal())

private fun String.isIpv6Literal(): Boolean =
    ':' in this &&
        IPV6_LITERAL.matches(this) &&
        try {
            InetAddress.getByName(this) is Inet6Address
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: UnknownHostException) {
            false
        }

private fun requireIntegerString(value: String) {
    if (!INTEGER_STRING.matches(value) || value.encodeToByteArray().size > 128) throw InvalidModuleData()
}

private fun List<String>.jsonStrings(): JsonArray = buildJsonArray { this@jsonStrings.forEach { add(it) } }

private fun List<String?>.jsonRow(): JsonArray = buildJsonArray { this@jsonRow.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } }

private fun JsonObjectBuilder.putNullableString(
    name: String,
    value: String?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullableLong(
    name: String,
    value: Long?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObjectBuilder.putNullableInt(
    name: String,
    value: Int?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun postgresFailure(code: String): Nothing = throw IllegalArgumentException(code)

private class InvalidModuleData : RuntimeException()

private class TooLarge : RuntimeException()

private class CellTooLarge : RuntimeException()

private data class CoreCapture(
    val serverVersion: String,
    val database: String,
    val readOnly: Boolean,
    val currentUserId: Long,
)

private data class ExtensionCapture(
    val version: String,
    val schema: String,
)

private data class StatementInfo(
    val statsReset: String?,
    val dealloc: Long?,
)

private data class PgProfileCapture(
    val json: JsonObject,
    val html: ByteArray?,
)

private const val PG_STAT_STATEMENTS = "pg_stat_statements"
private const val PG_PROFILE = "pg_profile"
private const val CONNECT_TIMEOUT_SECONDS = 30
private const val SOCKET_TIMEOUT_SECONDS = 30
private const val QUERY_TIMEOUT_SECONDS = 30
private const val FETCH_SIZE = 256

// ponytail: fetch one table row to bound prefetch; raise only after measured latency with a byte-aware batch.
private const val TABLE_FETCH_SIZE = 1
private const val MAX_TABLES = 16
private const val MAX_COLUMNS = 128
private const val MAX_TABLE_ROWS = 10_000
private const val MAX_TABLE_BYTES = 1_048_576
private const val MAX_STATEMENT_ROWS = 10_000
private const val MAX_CELL_BYTES = 65_536
private const val MAX_REPORT_BYTES = 4 * 1024 * 1024
private const val MAX_RESULT_BUFFER_BYTES = 16 * 1024 * 1024
private const val EXCLUDED_STATEMENT_USERID = "lt_verdict.excluded_statement_userid"
private val MAX_DECIMAL = BigDecimal("1000000000000000000")
private const val HEX = "0123456789ABCDEF"
private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
private val HOST_NAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*")
private val IPV6_LITERAL = Regex("[0-9A-Fa-f:]+")
private val INTEGER_STRING = Regex("0|-?[1-9][0-9]*")
private val EXTENSION_NAMES = setOf(PG_STAT_STATEMENTS, PG_PROFILE)
private val SETTING_NAMES =
    setOf("shared_buffers", "work_mem", "max_connections", "effective_cache_size", "track_io_timing")
private val SESSION_SQL =
    listOf(
        "SET TRANSACTION READ ONLY",
        "SET LOCAL lock_timeout = '5s'",
        "SET LOCAL statement_timeout = '30s'",
        "SET LOCAL TIME ZONE 'UTC'",
        "SET LOCAL DateStyle = 'ISO, YMD'",
    )
private const val CORE_SQL =
    "SELECT current_setting('server_version'), pg_catalog.current_database(), current_setting('transaction_read_only'), " +
        "(SELECT usesysid::bigint FROM pg_catalog.pg_user WHERE usename = current_user)"
private const val SETTINGS_SQL =
    "SELECT name, setting FROM pg_catalog.pg_settings " +
        "WHERE name IN ('effective_cache_size','max_connections','shared_buffers','track_io_timing','work_mem') ORDER BY name"
private const val EXTENSIONS_SQL =
    "SELECT e.extname, e.extversion, n.nspname FROM pg_catalog.pg_extension e " +
        "JOIN pg_catalog.pg_namespace n ON n.oid = e.extnamespace " +
        "WHERE e.extname IN ('pg_profile','pg_stat_statements') ORDER BY e.extname"
private const val TABLE_METADATA_SQL =
    "SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod) " +
        "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace " +
        "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid " +
        "WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r','p') " +
        "AND a.attnum > 0 AND NOT a.attisdropped"

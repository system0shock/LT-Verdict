package io.ltverdict.sources

import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.canonicalDecimal
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun validatePostgresPhase(input: InputStream): JsonObject = readPostgresPhase(input).json()

internal fun comparePostgresPhases(
    pre: JsonObject?,
    post: JsonObject?,
    loadInputSha256: String,
    startEpochMillis: Long,
    endEpochMillis: Long,
): JsonObject {
    if (!SHA256.matches(loadInputSha256) ||
        startEpochMillis !in 0..MAX_TIMESTAMP_EPOCH_MILLIS ||
        endEpochMillis !in startEpochMillis..MAX_TIMESTAMP_EPOCH_MILLIS
    ) {
        throw IllegalArgumentException("PG_CONTEXT_INVALID")
    }

    val validatedPre = pre?.let(::revalidatePostgresPhase)
    val validatedPost = post?.let(::revalidatePostgresPhase)
    val preSha256 = validatedPre?.let { sha256Hex(canonicalJson(it.json())) }
    val postSha256 = validatedPost?.let { sha256Hex(canonicalJson(it.json())) }
    val bindingReasons =
        bindingReasons(validatedPre, validatedPost, preSha256, startEpochMillis, endEpochMillis)
    val tables = compareTables(validatedPre, validatedPost, bindingReasons)
    val statements = compareStatements(validatedPre, validatedPost, bindingReasons)
    val pgProfile = comparePgProfile(validatedPre, validatedPost, bindingReasons)
    val reasons =
        (bindingReasons + tables.reasons + statements.reasons + pgProfile.reasons)
            .distinct()
            .sorted()

    return buildJsonObject {
        put("schema_version", "postgres-context.v1")
        put("type", "postgres_context")
        putNullableString("profile_id", sharedProfileId(validatedPre, validatedPost))
        put("load_input_sha256", loadInputSha256)
        put("start_epoch_ms", startEpochMillis)
        put("end_epoch_ms", endEpochMillis)
        putNullableString("pre_sha256", preSha256)
        putNullableString("post_sha256", postSha256)
        put("status", if (reasons.isEmpty()) "COMPLETE" else "DEGRADED")
        put("reasons", reasons.jsonStrings())
        put(
            "configuration_changes",
            buildJsonArray {
                if (bindingReasons.isEmpty() && validatedPre != null && validatedPost != null) {
                    (validatedPre.configuration.keys + validatedPost.configuration.keys).sorted().forEach { name ->
                        val before = validatedPre.configuration[name]
                        val after = validatedPost.configuration[name]
                        if (before != after) {
                            add(
                                buildJsonObject {
                                    put("name", name)
                                    putNullableString("pre", before)
                                    putNullableString("post", after)
                                },
                            )
                        }
                    }
                }
            },
        )
        put("tables", JsonArray(tables.values))
        put("statements", statements.json)
        put("pg_profile", pgProfile.json)
    }
}

private fun readPostgresPhase(input: InputStream): PostgresPhase =
    try {
        val bytes = readPhaseBytes(input)
        val text = decodePhaseUtf8(bytes)
        StrictJsonScanner(
            text,
            MAX_JSON_DEPTH,
            MAX_NUMERIC_TOKEN_BYTES,
            MAX_NUMERIC_EXPONENT,
            "PostgreSQL phase",
        ) { _, _, _ -> phaseInvalid() }.scan()
        parsePostgresPhase(Json.parseToJsonElement(text))
    } catch (failure: PostgresPhaseFailure) {
        throw IllegalArgumentException(failure.code)
    } catch (_: IOException) {
        throw IllegalArgumentException("PG_PHASE_READ_ERROR")
    } catch (_: SerializationException) {
        throw IllegalArgumentException("PG_PHASE_INVALID")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("PG_PHASE_INVALID")
    }

private fun revalidatePostgresPhase(value: JsonObject): PostgresPhase =
    readPostgresPhase(value.toString().encodeToByteArray().inputStream())

private fun readPhaseBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val remaining = MAX_PHASE_BYTES + 1 - total
        if (remaining <= 0) phaseFailure("PG_PHASE_TOO_LARGE")
        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (count == -1) return output.toByteArray()
        total += count
        if (total > MAX_PHASE_BYTES) phaseFailure("PG_PHASE_TOO_LARGE")
        output.write(buffer, 0, count)
    }
}

private fun decodePhaseUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        phaseFailure("PG_PHASE_INVALID_UTF8")
    }

private fun parsePostgresPhase(element: JsonElement): PostgresPhase {
    val root = element.phaseObject()
    root.rejectUnknown(ROOT_FIELDS)
    if (root.phaseString("schema_version") != "postgres-phase.v1") phaseInvalid()
    val phase = root.phaseString("phase")
    if (phase != "pre" && phase != "post") phaseInvalid()
    val profileId = root.phaseIdentifier("profile_id")
    val databaseId = root.phaseIdentifier("source_database_id")
    if (databaseId.startsWith("jdbc:", ignoreCase = true) || "://" in databaseId) phaseInvalid()
    val revision = root.phaseString("profile_revision_sha256").also(::requireSha256)
    val preSha256 = root.nullableString("pre_sha256")?.also(::requireSha256)
    if (phase == "pre" && preSha256 != null) phaseInvalid()
    val captureStarted = root.phaseLong("capture_started_epoch_ms")
    val captureEnded = root.phaseLong("capture_ended_epoch_ms")
    if (captureStarted !in 0..MAX_TIMESTAMP_EPOCH_MILLIS ||
        captureEnded !in captureStarted..MAX_TIMESTAMP_EPOCH_MILLIS
    ) {
        phaseInvalid()
    }
    val serverVersion = root.phaseString("server_version").also { validateText(it, MAX_IDENTIFIER_BYTES, false) }
    val configuration = parseConfiguration(root.required("configuration"))
    val tables = parseTables(root.phaseArray("tables"))
    val statements = parseStatements(root.required("statements"))
    val pgProfile = parsePgProfile(root.required("pg_profile"))
    return PostgresPhase(
        phase,
        profileId,
        databaseId,
        revision,
        preSha256,
        captureStarted,
        captureEnded,
        serverVersion,
        configuration,
        tables,
        statements,
        pgProfile,
    )
}

private fun parseConfiguration(element: JsonElement): Map<String, String> {
    val configuration = element.phaseObject()
    if (configuration.size > MAX_CONFIGURATION_ENTRIES) phaseInvalid()
    return configuration.entries
        .associate { (key, rawValue) ->
            validateText(key, MAX_IDENTIFIER_BYTES, false)
            val value = rawValue.phaseString()
            validateText(value, MAX_CONFIGURATION_VALUE_BYTES, true)
            key to value
        }.toSortedMap()
}

private fun parseTables(values: JsonArray): List<PostgresTable> {
    if (values.size > MAX_TABLES) phaseInvalid()
    val identities = HashSet<TableIdentity>()
    return values
        .map { value ->
            parseTable(value).also { table ->
                if (!identities.add(table.identity)) phaseInvalid()
            }
        }.sortedWith(compareBy({ it.schema }, { it.table }))
}

private fun parseTable(element: JsonElement): PostgresTable {
    val value = element.phaseObject()
    value.rejectUnknown(TABLE_FIELDS)
    val schema = value.phaseIdentifier("schema")
    val table = value.phaseIdentifier("table")
    val columns = value.phaseArray("columns").identifiers()
    if (columns.isEmpty() || columns.size > MAX_COLUMNS || columns.toSet().size != columns.size) phaseInvalid()
    val columnTypes =
        value.phaseArray("column_types").map { item ->
            item.phaseString().also { validateText(it, MAX_IDENTIFIER_BYTES, false) }
        }
    if (columnTypes.size != columns.size) phaseInvalid()
    val stableKey = value.phaseArray("stable_key").identifiers()
    if (stableKey.size > columns.size ||
        stableKey.toSet().size != stableKey.size ||
        stableKey.any { it !in columns }
    ) {
        phaseInvalid()
    }
    val rowLimit = value.phaseInt("row_limit")
    val byteLimit = value.phaseInt("byte_limit")
    if (rowLimit !in 1..MAX_TABLE_ROWS || byteLimit !in 1..MAX_TABLE_BYTES) phaseInvalid()
    val status = value.phaseStatus()
    val reason = value.nullableReason()
    validateStatusReason(status, reason)
    val rowCount = value.nullableLong("row_count")
    if (rowCount != null && rowCount < 0) phaseInvalid()
    val rawRows = value.phaseArray("rows")
    if (rawRows.size > rowLimit) phaseInvalid()
    val rows =
        rawRows.map { rawRow ->
            val row = rawRow.phaseArray()
            if (row.size != columns.size) phaseInvalid()
            row.map { cell ->
                when (cell) {
                    JsonNull -> null
                    is JsonPrimitive ->
                        if (cell.isString) {
                            cell.content.also {
                                if (it.encodeToByteArray().size > MAX_CELL_BYTES) phaseInvalid()
                            }
                        } else {
                            phaseInvalid()
                        }

                    else -> phaseInvalid()
                }
            }
        }
    if (canonicalJson(rows.jsonRows()).size > byteLimit) phaseInvalid()
    if (status == ModuleStatus.COMPLETE && rowCount != rows.size.toLong()) phaseInvalid()
    if (status != ModuleStatus.COMPLETE && rowCount != null && rowCount < rows.size) phaseInvalid()
    if (stableKey.isEmpty()) {
        if (status == ModuleStatus.COMPLETE || rows.isNotEmpty()) phaseInvalid()
        if (reason == "PG_TABLE_NO_STABLE_KEY" &&
            (status != ModuleStatus.DEGRADED || rowCount == null)
        ) {
            phaseInvalid()
        }
    } else if (reason == "PG_TABLE_NO_STABLE_KEY") {
        phaseInvalid()
    }
    val keyIndexes = stableKey.map(columns::indexOf)
    val normalizedRows =
        rows.sortedWith { left, right ->
            compareCells(keyIndexes.map(left::get), keyIndexes.map(right::get))
                .takeUnless { it == 0 }
                ?: compareCells(left, right)
        }
    return PostgresTable(
        schema,
        table,
        columns,
        columnTypes,
        stableKey,
        rowLimit,
        byteLimit,
        status,
        reason,
        rowCount,
        normalizedRows,
    )
}

private fun parseStatements(element: JsonElement): PostgresStatements {
    val value = element.phaseObject()
    value.rejectUnknown(STATEMENT_FIELDS)
    val status = value.phaseStatus()
    val reason = value.nullableReason()
    validateStatusReason(status, reason)
    val extensionVersion =
        value.nullableString("extension_version")?.also {
            validateText(it, MAX_IDENTIFIER_BYTES, false)
        }
    val statsReset =
        value.nullableString("stats_reset")?.also {
            validateText(it, MAX_TIMESTAMP_TEXT_BYTES, false)
        }
    val dealloc = value.nullableLong("dealloc")
    if (dealloc != null && dealloc < 0) phaseInvalid()
    val rowLimit = value.phaseInt("row_limit")
    if (rowLimit !in 1..MAX_STATEMENT_ROWS) phaseInvalid()
    val rawRows = value.phaseArray("rows")
    if (rawRows.size > rowLimit) phaseInvalid()
    if (status == ModuleStatus.COMPLETE &&
        (extensionVersion == null || statsReset == null || dealloc == null)
    ) {
        phaseInvalid()
    }
    val keys = HashSet<StatementKey>()
    val rows =
        rawRows
            .map { row ->
                parseStatementRow(row, status).also {
                    if (!keys.add(it.key)) phaseInvalid()
                }
            }.sortedWith { left, right -> compareStatementKeys(left.key, right.key) }
    return PostgresStatements(status, reason, extensionVersion, statsReset, dealloc, rowLimit, rows)
}

private fun parseStatementRow(
    element: JsonElement,
    moduleStatus: ModuleStatus,
): StatementRow {
    val value = element.phaseObject()
    value.rejectUnknown(STATEMENT_ROW_FIELDS)
    val dbid = value.phaseIntegerString("dbid")
    val userid = value.phaseIntegerString("userid")
    val queryid = value.nullableString("queryid")
    if (queryid == null) {
        if (moduleStatus != ModuleStatus.DEGRADED) phaseInvalid()
    } else {
        validateIntegerString(queryid)
    }
    val key = StatementKey(dbid, userid, queryid, value.phaseBoolean("toplevel"))
    return StatementRow(
        key,
        value.nonnegativeLong("calls"),
        value.boundedDecimal("total_exec_time"),
        value.nonnegativeLong("rows"),
        value.nonnegativeLong("shared_blks_hit"),
        value.nonnegativeLong("shared_blks_read"),
        value.nonnegativeLong("temp_blks_written"),
    )
}

private fun parsePgProfile(element: JsonElement): PgProfile {
    val value = element.phaseObject()
    value.rejectUnknown(PG_PROFILE_FIELDS)
    val status = value.phaseStatus()
    val reason = value.nullableReason()
    validateStatusReason(status, reason)
    val extensionVersion =
        value.nullableString("extension_version")?.also {
            validateText(it, MAX_IDENTIFIER_BYTES, false)
        }
    val statementsReset = value.nullableBoolean("statements_reset")
    val serverId = value.nullableLong("server_id")
    val startSampleId = value.nullableLong("start_sample_id")
    val endSampleId = value.nullableLong("end_sample_id")
    if (listOfNotNull(serverId, startSampleId, endSampleId).any { it < 0 }) phaseInvalid()
    if (startSampleId != null && endSampleId != null && startSampleId > endSampleId) phaseInvalid()
    val reportSha256 = value.nullableString("report_sha256")?.also(::requireSha256)
    if (status == ModuleStatus.COMPLETE &&
        (
            extensionVersion == null ||
                statementsReset != false ||
                serverId == null ||
                startSampleId == null ||
                endSampleId == null ||
                reportSha256 == null
        )
    ) {
        phaseInvalid()
    }
    return PgProfile(
        status,
        reason,
        extensionVersion,
        statementsReset,
        serverId,
        startSampleId,
        endSampleId,
        reportSha256,
    )
}

private fun bindingReasons(
    pre: PostgresPhase?,
    post: PostgresPhase?,
    preSha256: String?,
    startEpochMillis: Long,
    endEpochMillis: Long,
): List<String> {
    if (pre == null && post == null) return listOf("PG_CAPTURE_MISSING")
    if (pre == null) return listOf("PG_PRE_CAPTURE_MISSING")
    if (post == null) return listOf("PG_POST_CAPTURE_MISSING")
    val reasons = mutableListOf<String>()
    if (pre.phase != "pre") reasons += "PG_PRE_PHASE_INVALID"
    if (post.phase != "post") reasons += "PG_POST_PHASE_INVALID"
    if (pre.profileId != post.profileId) reasons += "PG_PROFILE_MISMATCH"
    if (pre.profileRevisionSha256 != post.profileRevisionSha256) {
        reasons += "PG_PROFILE_REVISION_MISMATCH"
    }
    if (pre.sourceDatabaseId != post.sourceDatabaseId) reasons += "PG_DATABASE_MISMATCH"
    if (pre.captureEndedEpochMillis > startEpochMillis) reasons += "PG_PRE_CAPTURE_LATE"
    if (post.captureStartedEpochMillis < endEpochMillis) reasons += "PG_POST_CAPTURE_EARLY"
    if (post.preSha256 != preSha256) reasons += "PG_PRE_HASH_MISMATCH"
    return reasons.distinct().sorted()
}

private fun sharedProfileId(
    pre: PostgresPhase?,
    post: PostgresPhase?,
): String? =
    when {
        pre == null -> post?.profileId
        post == null -> pre.profileId
        pre.profileId == post.profileId -> pre.profileId
        else -> null
    }

private fun compareTables(
    pre: PostgresPhase?,
    post: PostgresPhase?,
    bindingReasons: List<String>,
): TableResults {
    val preTables = pre?.tables.orEmpty().associateBy(PostgresTable::identity)
    val postTables = post?.tables.orEmpty().associateBy(PostgresTable::identity)
    val identities =
        (preTables.keys + postTables.keys)
            .distinct()
            .sortedWith(compareBy({ it.schema }, { it.table }))
    val results =
        identities.map { identity ->
            compareTable(preTables[identity], postTables[identity], bindingReasons)
        }
    return TableResults(results.map(TableResult::json), results.flatMap(TableResult::reasons))
}

private fun compareTable(
    pre: PostgresTable?,
    post: PostgresTable?,
    bindingReasons: List<String>,
): TableResult {
    val metadata = pre ?: post ?: error("table identity without table")
    if (bindingReasons.isNotEmpty()) return emptyTableResult(metadata, bindingReasons)
    if (pre == null) return emptyTableResult(metadata, listOf("PG_TABLE_PRE_MISSING"))
    if (post == null) return emptyTableResult(metadata, listOf("PG_TABLE_POST_MISSING"))
    if (pre.columns != post.columns ||
        pre.columnTypes != post.columnTypes ||
        pre.stableKey != post.stableKey
    ) {
        return emptyTableResult(metadata, listOf("PG_TABLE_SCHEMA_MISMATCH"))
    }
    if (pre.rowLimit != post.rowLimit || pre.byteLimit != post.byteLimit) {
        return emptyTableResult(metadata, listOf("PG_TABLE_LIMIT_MISMATCH"))
    }
    if (pre.stableKey.isEmpty()) {
        if (pre.reason != "PG_TABLE_NO_STABLE_KEY" || post.reason != "PG_TABLE_NO_STABLE_KEY") {
            return emptyTableResult(metadata, listOfNotNull(pre.reason, post.reason))
        }
        return TableResult(
            tableResultJson(
                metadata,
                ModuleStatus.DEGRADED,
                listOf("PG_TABLE_NO_STABLE_KEY"),
                requireNotNull(post.rowCount) - requireNotNull(pre.rowCount),
            ),
            listOf("PG_TABLE_NO_STABLE_KEY"),
        )
    }
    if (pre.status != ModuleStatus.COMPLETE || post.status != ModuleStatus.COMPLETE) {
        val reasons = listOfNotNull(pre.reason, post.reason).distinct().sorted()
        return emptyTableResult(metadata, reasons)
    }
    val preRows = pre.keyedRows()
    val postRows = post.keyedRows()
    val keyFailure = listOfNotNull(preRows.failure, postRows.failure).distinct().sorted()
    if (keyFailure.isNotEmpty()) return emptyTableResult(metadata, keyFailure)

    val before = preRows.rows
    val after = postRows.rows
    val keys = (before.keys + after.keys).distinct().sortedWith(::compareStringLists)
    val changes =
        keys.mapNotNull { key ->
            when {
                key !in before -> ChangedKey("inserted", key)
                key !in after -> ChangedKey("deleted", key)
                before.getValue(key) != after.getValue(key) -> ChangedKey("updated", key)
                else -> null
            }
        }
    val inserted = changes.count { it.change == "inserted" }.toLong()
    val deleted = changes.count { it.change == "deleted" }.toLong()
    val updated = changes.count { it.change == "updated" }.toLong()
    val visibleKeys = changes.take(MAX_CHANGED_KEYS)
    return TableResult(
        tableResultJson(
            metadata,
            ModuleStatus.COMPLETE,
            emptyList(),
            requireNotNull(post.rowCount) - requireNotNull(pre.rowCount),
            inserted,
            deleted,
            updated,
            visibleKeys,
            changes.size > visibleKeys.size,
        ),
        emptyList(),
    )
}

private fun emptyTableResult(
    table: PostgresTable,
    reasons: List<String>,
): TableResult {
    val normalizedReasons = reasons.ifEmpty { listOf("PG_TABLE_INCOMPLETE") }.distinct().sorted()
    return TableResult(
        tableResultJson(table, ModuleStatus.DEGRADED, normalizedReasons, null),
        normalizedReasons,
    )
}

private fun tableResultJson(
    table: PostgresTable,
    status: ModuleStatus,
    reasons: List<String>,
    rowCountDelta: Long?,
    inserted: Long? = null,
    deleted: Long? = null,
    updated: Long? = null,
    changedKeys: List<ChangedKey> = emptyList(),
    keysTruncated: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("schema", table.schema)
        put("table", table.table)
        put("stable_key", table.stableKey.jsonStrings())
        put("status", status.name)
        put("reasons", reasons.jsonStrings())
        putNullableLong("row_count_delta", rowCountDelta)
        putNullableLong("inserted", inserted)
        putNullableLong("deleted", deleted)
        putNullableLong("updated", updated)
        put(
            "changed_keys",
            buildJsonArray {
                changedKeys.forEach { changed ->
                    add(
                        buildJsonObject {
                            put("change", changed.change)
                            put("key", changed.key.jsonStrings())
                        },
                    )
                }
            },
        )
        put("keys_truncated", keysTruncated)
    }

private fun PostgresTable.keyedRows(): KeyedRows {
    val keyIndexes = stableKey.map(columns::indexOf)
    if (rows.any { row -> keyIndexes.any { row[it] == null } }) {
        return KeyedRows(emptyMap(), "PG_TABLE_NULL_KEY")
    }
    val result = LinkedHashMap<List<String>, List<String?>>()
    rows.forEach { row ->
        val key = keyIndexes.map { requireNotNull(row[it]) }
        if (result.put(key, row) != null) {
            return KeyedRows(emptyMap(), "PG_TABLE_KEY_NOT_UNIQUE")
        }
    }
    return KeyedRows(result, null)
}

private fun compareStatements(
    pre: PostgresPhase?,
    post: PostgresPhase?,
    bindingReasons: List<String>,
): JsonResult {
    if (bindingReasons.isNotEmpty()) return emptyStatementResult(bindingReasons)
    val before = requireNotNull(pre).statements
    val after = requireNotNull(post).statements
    if (before.status != ModuleStatus.COMPLETE || after.status != ModuleStatus.COMPLETE) {
        return emptyStatementResult(listOfNotNull(before.reason, after.reason))
    }
    if (before.statsReset != after.statsReset) {
        return emptyStatementResult(listOf("PG_STATEMENTS_RESET"))
    }
    val beforeRows = before.rows.associateBy(StatementRow::key)
    val afterRows = after.rows.associateBy(StatementRow::key)
    val commonKeys = beforeRows.keys.intersect(afterRows.keys).sortedWith(::compareStatementKeys)
    val unmatchedPre = (beforeRows.keys - afterRows.keys).sortedWith(::compareStatementKeys)
    val unmatchedPost = (afterRows.keys - beforeRows.keys).sortedWith(::compareStatementKeys)
    if (requireNotNull(after.dealloc) < requireNotNull(before.dealloc) ||
        commonKeys.any { key -> afterRows.getValue(key).regressedFrom(beforeRows.getValue(key)) }
    ) {
        return statementResult(
            listOf("PG_STATEMENTS_COUNTER_REGRESSION"),
            emptyList(),
            unmatchedPre,
            unmatchedPost,
        )
    }
    val rows =
        commonKeys.map { key ->
            afterRows.getValue(key).deltaFrom(beforeRows.getValue(key))
        }
    val reasons = mutableListOf<String>()
    if (after.dealloc > before.dealloc) reasons += "PG_STATEMENTS_EVICTED"
    if (unmatchedPre.isNotEmpty() || unmatchedPost.isNotEmpty()) {
        reasons += "PG_STATEMENTS_UNMATCHED"
    }
    return statementResult(reasons, rows, unmatchedPre, unmatchedPost)
}

private fun emptyStatementResult(reasons: List<String>): JsonResult =
    statementResult(
        reasons.ifEmpty { listOf("PG_STATEMENTS_INCOMPLETE") },
        emptyList(),
        emptyList(),
        emptyList(),
    )

private fun statementResult(
    reasons: List<String>,
    rows: List<StatementRow>,
    unmatchedPre: List<StatementKey>,
    unmatchedPost: List<StatementKey>,
): JsonResult {
    val normalizedReasons = reasons.distinct().sorted()
    return JsonResult(
        buildJsonObject {
            put("status", if (normalizedReasons.isEmpty()) "COMPLETE" else "DEGRADED")
            put("reasons", normalizedReasons.jsonStrings())
            put("rows", buildJsonArray { rows.forEach { add(it.json()) } })
            put("unmatched_pre", buildJsonArray { unmatchedPre.forEach { add(it.json()) } })
            put("unmatched_post", buildJsonArray { unmatchedPost.forEach { add(it.json()) } })
        },
        normalizedReasons,
    )
}

private fun comparePgProfile(
    pre: PostgresPhase?,
    post: PostgresPhase?,
    bindingReasons: List<String>,
): JsonResult {
    val before = pre?.pgProfile
    val after = post?.pgProfile
    val reasons = bindingReasons.toMutableList()
    if (bindingReasons.isEmpty()) {
        requireNotNull(before)
        requireNotNull(after)
        if (before.status != ModuleStatus.COMPLETE) reasons += requireNotNull(before.reason)
        if (after.status != ModuleStatus.COMPLETE) reasons += requireNotNull(after.reason)
        listOf(before, after).forEach { profile ->
            when (profile.statementsReset) {
                true -> reasons += "PG_PROFILE_STATEMENTS_RESET"
                null -> reasons += "PG_PROFILE_RESET_UNKNOWN"
                false -> Unit
            }
        }
        if (before.serverId != null && after.serverId != null && before.serverId != after.serverId) {
            reasons += "PG_PROFILE_SERVER_MISMATCH"
        }
        if (before.extensionVersion != null &&
            after.extensionVersion != null &&
            before.extensionVersion != after.extensionVersion
        ) {
            reasons += "PG_PROFILE_VERSION_MISMATCH"
        }
    }
    val normalizedReasons = reasons.distinct().sorted()
    return JsonResult(
        buildJsonObject {
            put("status", if (normalizedReasons.isEmpty()) "COMPLETE" else "DEGRADED")
            put("reasons", normalizedReasons.jsonStrings())
            putNullableString("pre_report_sha256", before?.reportSha256)
            putNullableString("post_report_sha256", after?.reportSha256)
        },
        normalizedReasons,
    )
}

private fun StatementRow.regressedFrom(before: StatementRow): Boolean =
    calls < before.calls ||
        totalExecTime < before.totalExecTime ||
        rows < before.rows ||
        sharedBlksHit < before.sharedBlksHit ||
        sharedBlksRead < before.sharedBlksRead ||
        tempBlksWritten < before.tempBlksWritten

private fun StatementRow.deltaFrom(before: StatementRow): StatementRow =
    StatementRow(
        key,
        calls - before.calls,
        normalizeDecimal(totalExecTime - before.totalExecTime),
        rows - before.rows,
        sharedBlksHit - before.sharedBlksHit,
        sharedBlksRead - before.sharedBlksRead,
        tempBlksWritten - before.tempBlksWritten,
    )

private fun compareCells(
    left: List<String?>,
    right: List<String?>,
): Int {
    left.indices.forEach { index ->
        val comparison =
            when {
                left[index] == null && right[index] == null -> 0
                left[index] == null -> -1
                right[index] == null -> 1
                else -> requireNotNull(left[index]).compareTo(requireNotNull(right[index]))
            }
        if (comparison != 0) return comparison
    }
    return left.size.compareTo(right.size)
}

private fun compareStringLists(
    left: List<String>,
    right: List<String>,
): Int = compareCells(left, right)

private fun compareStatementKeys(
    left: StatementKey,
    right: StatementKey,
): Int {
    val database = BigInteger(left.dbid).compareTo(BigInteger(right.dbid))
    if (database != 0) return database
    val user = BigInteger(left.userid).compareTo(BigInteger(right.userid))
    if (user != 0) return user
    val query =
        when {
            left.queryid == null && right.queryid == null -> 0
            left.queryid == null -> -1
            right.queryid == null -> 1
            else -> BigInteger(left.queryid).compareTo(BigInteger(right.queryid))
        }
    return if (query != 0) query else left.toplevel.compareTo(right.toplevel)
}

private fun validateStatusReason(
    status: ModuleStatus,
    reason: String?,
) {
    if ((status == ModuleStatus.COMPLETE) != (reason == null)) phaseInvalid()
}

private fun requireSha256(value: String) {
    if (!SHA256.matches(value)) phaseInvalid()
}

private fun validateText(
    value: String,
    maxBytes: Int,
    allowEmpty: Boolean,
) {
    if ((!allowEmpty && value.isEmpty()) ||
        value.encodeToByteArray().size > maxBytes ||
        value.any(Char::isISOControl)
    ) {
        phaseInvalid()
    }
}

private fun validateIntegerString(value: String) {
    validateText(value, MAX_IDENTIFIER_BYTES, false)
    if (!INTEGER_STRING.matches(value)) phaseInvalid()
}

private fun JsonElement.phaseObject(): JsonObject = this as? JsonObject ?: phaseInvalid()

private fun JsonElement.phaseArray(): JsonArray = this as? JsonArray ?: phaseInvalid()

private fun JsonElement.phaseString(): String = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: phaseInvalid()

private fun JsonObject.required(name: String): JsonElement = get(name) ?: phaseInvalid()

private fun JsonObject.phaseString(name: String): String = required(name).phaseString()

private fun JsonObject.phaseIdentifier(name: String): String = phaseString(name).also { validateText(it, MAX_IDENTIFIER_BYTES, false) }

private fun JsonObject.phaseIntegerString(name: String): String = phaseString(name).also(::validateIntegerString)

private fun JsonObject.phaseArray(name: String): JsonArray = required(name).phaseArray()

private fun JsonObject.phaseBoolean(name: String): Boolean {
    val value = required(name)
    if (value !is JsonPrimitive || value.isString || value === JsonNull) phaseInvalid()
    return value.content.toBooleanStrictOrNull() ?: phaseInvalid()
}

private fun JsonObject.phaseDecimal(name: String): BigDecimal {
    val value = required(name)
    if (value !is JsonPrimitive ||
        value.isString ||
        value === JsonNull ||
        value.content == "true" ||
        value.content == "false"
    ) {
        phaseInvalid()
    }
    return try {
        BigDecimal(value.content)
    } catch (_: NumberFormatException) {
        phaseInvalid()
    }
}

private fun JsonObject.phaseLong(name: String): Long =
    try {
        phaseDecimal(name).longValueExact()
    } catch (_: ArithmeticException) {
        phaseInvalid()
    }

private fun JsonObject.phaseInt(name: String): Int =
    try {
        phaseDecimal(name).intValueExact()
    } catch (_: ArithmeticException) {
        phaseInvalid()
    }

private fun JsonObject.nonnegativeLong(name: String): Long = phaseLong(name).also { if (it < 0) phaseInvalid() }

private fun JsonObject.boundedDecimal(name: String): BigDecimal {
    val value = phaseDecimal(name)
    if (value.signum() < 0 ||
        value > MAX_DECIMAL_MAGNITUDE ||
        value.precision() > MAX_DECIMAL_PRECISION ||
        value.scale() > MAX_DECIMAL_SCALE
    ) {
        phaseInvalid()
    }
    return normalizeDecimal(value)
}

private fun JsonObject.nullableString(name: String): String? =
    when (val value = required(name)) {
        JsonNull -> null
        else -> value.phaseString()
    }

private fun JsonObject.nullableLong(name: String): Long? =
    when (required(name)) {
        JsonNull -> null
        else -> phaseLong(name)
    }

private fun JsonObject.nullableBoolean(name: String): Boolean? =
    when (required(name)) {
        JsonNull -> null
        else -> phaseBoolean(name)
    }

private fun JsonObject.nullableReason(): String? =
    nullableString("reason")?.also {
        if (!REASON_CODE.matches(it)) phaseInvalid()
    }

private fun JsonObject.phaseStatus(): ModuleStatus = ModuleStatus.entries.find { it.name == phaseString("status") } ?: phaseInvalid()

private fun JsonObject.rejectUnknown(allowed: Set<String>) {
    if (keys.any { it !in allowed }) phaseInvalid()
}

private fun JsonArray.identifiers(): List<String> =
    map { value -> value.phaseString().also { validateText(it, MAX_IDENTIFIER_BYTES, false) } }

private fun List<String>.jsonStrings(): JsonArray =
    buildJsonArray {
        this@jsonStrings.forEach { add(it) }
    }

private fun List<List<String?>>.jsonRows(): JsonArray =
    buildJsonArray {
        this@jsonRows.forEach { row ->
            add(buildJsonArray { row.forEach { add(it?.let(::JsonPrimitive) ?: JsonNull) } })
        }
    }

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

private fun normalizeDecimal(value: BigDecimal): BigDecimal = BigDecimal(canonicalDecimal(value))

private fun phaseInvalid(): Nothing = phaseFailure("PG_PHASE_INVALID")

private fun phaseFailure(code: String): Nothing = throw PostgresPhaseFailure(code)

private class PostgresPhaseFailure(
    val code: String,
) : RuntimeException()

private enum class ModuleStatus {
    COMPLETE,
    DEGRADED,
    FAILED,
}

private data class PostgresPhase(
    val phase: String,
    val profileId: String,
    val sourceDatabaseId: String,
    val profileRevisionSha256: String,
    val preSha256: String?,
    val captureStartedEpochMillis: Long,
    val captureEndedEpochMillis: Long,
    val serverVersion: String,
    val configuration: Map<String, String>,
    val tables: List<PostgresTable>,
    val statements: PostgresStatements,
    val pgProfile: PgProfile,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("schema_version", "postgres-phase.v1")
            put("phase", phase)
            put("profile_id", profileId)
            put("source_database_id", sourceDatabaseId)
            put("profile_revision_sha256", profileRevisionSha256)
            putNullableString("pre_sha256", preSha256)
            put("capture_started_epoch_ms", captureStartedEpochMillis)
            put("capture_ended_epoch_ms", captureEndedEpochMillis)
            put("server_version", serverVersion)
            put(
                "configuration",
                buildJsonObject {
                    configuration.forEach { (key, value) -> put(key, value) }
                },
            )
            put("tables", buildJsonArray { tables.forEach { add(it.json()) } })
            put("statements", statements.json())
            put("pg_profile", pgProfile.json())
        }
}

private data class TableIdentity(
    val schema: String,
    val table: String,
)

private data class PostgresTable(
    val schema: String,
    val table: String,
    val columns: List<String>,
    val columnTypes: List<String>,
    val stableKey: List<String>,
    val rowLimit: Int,
    val byteLimit: Int,
    val status: ModuleStatus,
    val reason: String?,
    val rowCount: Long?,
    val rows: List<List<String?>>,
) {
    val identity = TableIdentity(schema, table)

    fun json(): JsonObject =
        buildJsonObject {
            put("schema", schema)
            put("table", table)
            put("columns", columns.jsonStrings())
            put("column_types", columnTypes.jsonStrings())
            put("stable_key", stableKey.jsonStrings())
            put("row_limit", rowLimit)
            put("byte_limit", byteLimit)
            put("status", status.name)
            putNullableString("reason", reason)
            putNullableLong("row_count", rowCount)
            put("rows", rows.jsonRows())
        }
}

private data class PostgresStatements(
    val status: ModuleStatus,
    val reason: String?,
    val extensionVersion: String?,
    val statsReset: String?,
    val dealloc: Long?,
    val rowLimit: Int,
    val rows: List<StatementRow>,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("status", status.name)
            putNullableString("reason", reason)
            putNullableString("extension_version", extensionVersion)
            putNullableString("stats_reset", statsReset)
            putNullableLong("dealloc", dealloc)
            put("row_limit", rowLimit)
            put("rows", buildJsonArray { rows.forEach { add(it.json()) } })
        }
}

private data class StatementKey(
    val dbid: String,
    val userid: String,
    val queryid: String?,
    val toplevel: Boolean,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("dbid", dbid)
            put("userid", userid)
            putNullableString("queryid", queryid)
            put("toplevel", toplevel)
        }
}

private data class StatementRow(
    val key: StatementKey,
    val calls: Long,
    val totalExecTime: BigDecimal,
    val rows: Long,
    val sharedBlksHit: Long,
    val sharedBlksRead: Long,
    val tempBlksWritten: Long,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("dbid", key.dbid)
            put("userid", key.userid)
            putNullableString("queryid", key.queryid)
            put("toplevel", key.toplevel)
            put("calls", calls)
            put("total_exec_time", JsonPrimitive(totalExecTime))
            put("rows", rows)
            put("shared_blks_hit", sharedBlksHit)
            put("shared_blks_read", sharedBlksRead)
            put("temp_blks_written", tempBlksWritten)
        }
}

private data class PgProfile(
    val status: ModuleStatus,
    val reason: String?,
    val extensionVersion: String?,
    val statementsReset: Boolean?,
    val serverId: Long?,
    val startSampleId: Long?,
    val endSampleId: Long?,
    val reportSha256: String?,
) {
    fun json(): JsonObject =
        buildJsonObject {
            put("status", status.name)
            putNullableString("reason", reason)
            putNullableString("extension_version", extensionVersion)
            put("statements_reset", statementsReset?.let(::JsonPrimitive) ?: JsonNull)
            putNullableLong("server_id", serverId)
            putNullableLong("start_sample_id", startSampleId)
            putNullableLong("end_sample_id", endSampleId)
            putNullableString("report_sha256", reportSha256)
        }
}

private data class KeyedRows(
    val rows: Map<List<String>, List<String?>>,
    val failure: String?,
)

private data class ChangedKey(
    val change: String,
    val key: List<String>,
)

private data class TableResult(
    val json: JsonObject,
    val reasons: List<String>,
)

private data class TableResults(
    val values: List<JsonObject>,
    val reasons: List<String>,
)

private data class JsonResult(
    val json: JsonObject,
    val reasons: List<String>,
)

private val ROOT_FIELDS =
    setOf(
        "schema_version",
        "phase",
        "profile_id",
        "source_database_id",
        "profile_revision_sha256",
        "pre_sha256",
        "capture_started_epoch_ms",
        "capture_ended_epoch_ms",
        "server_version",
        "configuration",
        "tables",
        "statements",
        "pg_profile",
    )
private val TABLE_FIELDS =
    setOf(
        "schema",
        "table",
        "columns",
        "column_types",
        "stable_key",
        "row_limit",
        "byte_limit",
        "status",
        "reason",
        "row_count",
        "rows",
    )
private val STATEMENT_FIELDS =
    setOf("status", "reason", "extension_version", "stats_reset", "dealloc", "row_limit", "rows")
private val STATEMENT_ROW_FIELDS =
    setOf(
        "dbid",
        "userid",
        "queryid",
        "toplevel",
        "calls",
        "total_exec_time",
        "rows",
        "shared_blks_hit",
        "shared_blks_read",
        "temp_blks_written",
    )
private val PG_PROFILE_FIELDS =
    setOf(
        "status",
        "reason",
        "extension_version",
        "statements_reset",
        "server_id",
        "start_sample_id",
        "end_sample_id",
        "report_sha256",
    )
private val SHA256 = Regex("[0-9a-f]{64}")
private val REASON_CODE = Regex("PG_[A-Z0-9_]{1,125}")
private val INTEGER_STRING = Regex("0|-?[1-9][0-9]*")
private val MAX_DECIMAL_MAGNITUDE = BigDecimal("1000000000000000000")
private const val MAX_PHASE_BYTES = 16 * 1024 * 1024
private const val MAX_JSON_DEPTH = 20
private const val MAX_NUMERIC_TOKEN_BYTES = 64
private const val MAX_NUMERIC_EXPONENT = 64
private const val MAX_CONFIGURATION_ENTRIES = 64
private const val MAX_IDENTIFIER_BYTES = 128
private const val MAX_CONFIGURATION_VALUE_BYTES = 4096
private const val MAX_TIMESTAMP_TEXT_BYTES = 128
private const val MAX_TABLES = 16
private const val MAX_COLUMNS = 128
private const val MAX_TABLE_ROWS = 10000
private const val MAX_TABLE_BYTES = 1048576
private const val MAX_STATEMENT_ROWS = 10000
private const val MAX_CELL_BYTES = 65536
private const val MAX_DECIMAL_PRECISION = 32
private const val MAX_DECIMAL_SCALE = 12
private const val MAX_CHANGED_KEYS = 100

package io.ltverdict.sources

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PostgresCaptureTest {
    @Test
    fun `configuration changes are sorted and require valid phase binding`() {
        val pre = JsonObject(phase("pre") + ("configuration" to Json.parseToJsonElement("""{"work_mem":"4096","old":"yes"}""")))
        val post =
            bindPost(
                pre,
                JsonObject(
                    phase("post") + ("configuration" to Json.parseToJsonElement("""{"work_mem":"8192","new":"yes"}""")),
                ),
            )
        assertEquals(
            Json.parseToJsonElement(
                """[{"name":"new","pre":null,"post":"yes"},{"name":"old","pre":"yes","post":null},{"name":"work_mem","pre":"4096","post":"8192"}]""",
            ),
            comparePostgresPhases(pre, post, HASH_C, 200, 300).getValue("configuration_changes"),
        )
        assertEquals(JsonArray(emptyList()), comparePostgresPhases(pre, post, HASH_C, 0, 300).getValue("configuration_changes"))
    }

    @Test
    fun `comparison reports literal table and statement deltas without raw rows`() {
        val pre =
            phase(
                "pre",
                tables = listOf(table(rows = listOf(listOf("1", "new"), listOf("2", "old")))),
                statements = statements(rows = listOf(statementRow(calls = 10, totalExecTime = "20", rows = 5))),
            )
        val post =
            bindPost(
                pre,
                phase(
                    "post",
                    tables = listOf(table(rows = listOf(listOf("3", "new"), listOf("1", "done")))),
                    statements =
                        statements(
                            rows =
                                listOf(
                                    statementRow(
                                        calls = 14,
                                        totalExecTime = "28",
                                        rows = 9,
                                        sharedBlksHit = 5,
                                        sharedBlksRead = 2,
                                        tempBlksWritten = 1,
                                    ),
                                ),
                        ),
                ),
            )

        val context = comparePostgresPhases(pre, post, HASH_C, 200, 300)
        val table =
            context
                .getValue("tables")
                .jsonArray
                .single()
                .jsonObject
        val statement = context.getValue("statements").jsonObject

        assertEquals("COMPLETE", context.string("status"))
        assertEquals(emptyList<String>(), context.reasons())
        assertEquals(1, table.long("inserted"))
        assertEquals(1, table.long("deleted"))
        assertEquals(1, table.long("updated"))
        assertEquals(0, table.long("row_count_delta"))
        assertEquals(
            Json.parseToJsonElement(
                """[{"change":"updated","key":["1"]},{"change":"deleted","key":["2"]},{"change":"inserted","key":["3"]}]""",
            ),
            table.getValue("changed_keys"),
        )
        assertFalse(
            table
                .getValue("keys_truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            Json.parseToJsonElement(
                """[{"dbid":"1","userid":"10","queryid":"42","toplevel":true,"calls":4,"total_exec_time":8,"rows":4,"shared_blks_hit":3,"shared_blks_read":2,"temp_blks_written":1}]""",
            ),
            statement.getValue("rows"),
        )
        assertEquals(JsonArray(emptyList()), statement.getValue("unmatched_pre"))
        assertEquals(JsonArray(emptyList()), statement.getValue("unmatched_post"))
        assertFalse(context.toString().contains("\"done\""))
        assertFalse(context.toString().contains("\"old\""))
    }

    @Test
    fun `global binding failures suppress every exact delta`() {
        val standardPre = phase("pre")
        val standardPost = bindPost(standardPre, phase("post"))
        val latePre = phase("pre", captureEnded = 201)
        val earlyPost = phase("post", captureStarted = 299)
        val cases =
            listOf(
                BindingCase(latePre, bindPost(latePre, phase("post")), "PG_PRE_CAPTURE_LATE"),
                BindingCase(standardPre, bindPost(standardPre, earlyPost), "PG_POST_CAPTURE_EARLY"),
                BindingCase(
                    standardPre,
                    bindPost(standardPre, phase("post", profileId = "other")),
                    "PG_PROFILE_MISMATCH",
                ),
                BindingCase(
                    standardPre,
                    bindPost(standardPre, phase("post", revision = HASH_B)),
                    "PG_PROFILE_REVISION_MISMATCH",
                ),
                BindingCase(
                    standardPre,
                    bindPost(standardPre, phase("post", databaseId = "other-db")),
                    "PG_DATABASE_MISMATCH",
                ),
                BindingCase(standardPre, standardPost.withValue("pre_sha256", JsonPrimitive(HASH_D)), "PG_PRE_HASH_MISMATCH"),
            )

        cases.forEach { case ->
            val context = comparePostgresPhases(case.pre, case.post, HASH_C, 200, 300)
            val table =
                context
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject

            assertEquals("DEGRADED", context.string("status"), case.reason)
            assertEquals(listOf(case.reason), context.reasons(), case.reason)
            listOf("row_count_delta", "inserted", "deleted", "updated").forEach { field ->
                assertEquals(JsonNull, table.getValue(field), "${case.reason}:$field")
            }
            assertEquals(JsonArray(emptyList()), context.getValue("statements").jsonObject.getValue("rows"))
        }
    }

    @Test
    fun `missing phases return degraded context instead of throwing`() {
        val pre = phase("pre")
        val post = phase("post")
        val cases =
            listOf(
                Triple<JsonObject?, JsonObject?, String>(null, null, "PG_CAPTURE_MISSING"),
                Triple(pre, null, "PG_POST_CAPTURE_MISSING"),
                Triple(null, post, "PG_PRE_CAPTURE_MISSING"),
            )

        cases.forEach { (candidatePre, candidatePost, reason) ->
            val context = comparePostgresPhases(candidatePre, candidatePost, HASH_C, 200, 300)

            assertEquals("DEGRADED", context.string("status"))
            assertTrue(reason in context.reasons())
            assertEquals(JsonNull, context.getValue("pre_sha256").takeIf { candidatePre == null } ?: JsonNull)
            assertEquals(JsonNull, context.getValue("post_sha256").takeIf { candidatePost == null } ?: JsonNull)
            context.getValue("tables").jsonArray.forEach { item ->
                assertEquals(JsonNull, item.jsonObject.getValue("inserted"))
            }
        }
    }

    @Test
    fun `keyless tables report only exact row count delta`() {
        val pre =
            phase(
                "pre",
                tables =
                    listOf(
                        table(
                            stableKey = emptyList(),
                            rows = emptyList(),
                            rowCount = 2,
                            status = "DEGRADED",
                            reason = "PG_TABLE_NO_STABLE_KEY",
                        ),
                    ),
            )
        val post =
            bindPost(
                pre,
                phase(
                    "post",
                    tables =
                        listOf(
                            table(
                                stableKey = emptyList(),
                                rows = emptyList(),
                                rowCount = 3,
                                status = "DEGRADED",
                                reason = "PG_TABLE_NO_STABLE_KEY",
                            ),
                        ),
                ),
            )

        val result =
            comparePostgresPhases(pre, post, HASH_C, 200, 300)
                .getValue("tables")
                .jsonArray
                .single()
                .jsonObject

        assertEquals("DEGRADED", result.string("status"))
        assertEquals(listOf("PG_TABLE_NO_STABLE_KEY"), result.reasons())
        assertEquals(1, result.long("row_count_delta"))
        listOf("inserted", "deleted", "updated").forEach { assertEquals(JsonNull, result.getValue(it)) }
        assertEquals(JsonArray(emptyList()), result.getValue("changed_keys"))
    }

    @Test
    fun `unsafe keyed and incomplete tables never claim exact changes`() {
        val cases =
            listOf(
                TableCase(
                    table(rows = listOf(listOf("1", "a"), listOf("1", "b"))),
                    table(rows = listOf(listOf("1", "c"))),
                    "PG_TABLE_KEY_NOT_UNIQUE",
                ),
                TableCase(
                    table(rows = listOf(listOf(null, "a"))),
                    table(rows = listOf(listOf("1", "a"))),
                    "PG_TABLE_NULL_KEY",
                ),
                TableCase(
                    table(
                        rows = listOf(listOf("1", "a")),
                        rowCount = 2,
                        status = "DEGRADED",
                        reason = "PG_TABLE_TRUNCATED",
                    ),
                    table(
                        rows = listOf(listOf("1", "b")),
                        rowCount = 2,
                        status = "DEGRADED",
                        reason = "PG_TABLE_TRUNCATED",
                    ),
                    "PG_TABLE_TRUNCATED",
                ),
                TableCase(
                    table(rows = listOf(listOf("1", "a"))),
                    table(stableKey = listOf("status"), rows = listOf(listOf("1", "a"))),
                    "PG_TABLE_SCHEMA_MISMATCH",
                ),
                TableCase(
                    table(
                        stableKey = emptyList(),
                        rows = emptyList(),
                        rowCount = null,
                        status = "FAILED",
                        reason = "PG_TABLE_QUERY_FAILED",
                    ),
                    table(
                        stableKey = emptyList(),
                        rows = emptyList(),
                        rowCount = null,
                        status = "FAILED",
                        reason = "PG_TABLE_QUERY_FAILED",
                    ),
                    "PG_TABLE_QUERY_FAILED",
                ),
            )

        cases.forEach { case ->
            val pre = phase("pre", tables = listOf(case.pre))
            val post = bindPost(pre, phase("post", tables = listOf(case.post)))
            val result =
                comparePostgresPhases(pre, post, HASH_C, 200, 300)
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject

            assertEquals("DEGRADED", result.string("status"), case.reason)
            assertTrue(case.reason in result.reasons(), case.reason)
            listOf("row_count_delta", "inserted", "deleted", "updated").forEach { field ->
                assertEquals(JsonNull, result.getValue(field), "${case.reason}:$field")
            }
        }
    }

    @Test
    fun `changed key evidence is deterministic and capped at one hundred`() {
        val pre = phase("pre", tables = listOf(table(rows = emptyList(), rowLimit = 200)))
        val postRows = (100 downTo 0).map { listOf(it.toString(), "value-$it") }
        val post = bindPost(pre, phase("post", tables = listOf(table(rows = postRows, rowLimit = 200))))

        val result =
            comparePostgresPhases(pre, post, HASH_C, 200, 300)
                .getValue("tables")
                .jsonArray
                .single()
                .jsonObject

        assertEquals(101, result.long("inserted"))
        assertEquals(100, result.getValue("changed_keys").jsonArray.size)
        assertTrue(
            result
                .getValue("keys_truncated")
                .jsonPrimitive.content
                .toBoolean(),
        )
        assertEquals(
            listOf("0", "1", "10"),
            result
                .getValue(
                    "changed_keys",
                ).jsonArray
                .take(3)
                .map {
                    it.jsonObject
                        .getValue("key")
                        .jsonArray
                        .single()
                        .jsonPrimitive.content
                },
        )
    }

    @Test
    fun `statement reset or regression invalidates all matched deltas`() {
        val pre = phase("pre", statements = statements(rows = listOf(statementRow(calls = 10, totalExecTime = "20"))))
        val resetPost =
            bindPost(
                pre,
                phase(
                    "post",
                    statements =
                        statements(
                            statsReset = "2026-01-02T00:00:00Z",
                            rows = listOf(statementRow(calls = 14, totalExecTime = "28")),
                        ),
                ),
            )
        val regressionPost =
            bindPost(
                pre,
                phase("post", statements = statements(rows = listOf(statementRow(calls = 9, totalExecTime = "28")))),
            )

        listOf(resetPost to "PG_STATEMENTS_RESET", regressionPost to "PG_STATEMENTS_COUNTER_REGRESSION").forEach { (post, reason) ->
            val result = comparePostgresPhases(pre, post, HASH_C, 200, 300).getValue("statements").jsonObject

            assertEquals("DEGRADED", result.string("status"))
            assertEquals(listOf(reason), result.reasons())
            assertEquals(JsonArray(emptyList()), result.getValue("rows"))
        }
    }

    @Test
    fun `statement eviction preserves matched deltas with degraded coverage`() {
        val pre = phase("pre", statements = statements(dealloc = 2, rows = listOf(statementRow(calls = 10, totalExecTime = "20"))))
        val post =
            bindPost(
                pre,
                phase(
                    "post",
                    statements = statements(dealloc = 3, rows = listOf(statementRow(calls = 14, totalExecTime = "28"))),
                ),
            )

        val result = comparePostgresPhases(pre, post, HASH_C, 200, 300).getValue("statements").jsonObject

        assertEquals("DEGRADED", result.string("status"))
        assertEquals(listOf("PG_STATEMENTS_EVICTED"), result.reasons())
        assertEquals(
            4,
            result
                .getValue("rows")
                .jsonArray
                .single()
                .jsonObject
                .long("calls"),
        )
    }

    @Test
    fun `new and missing statements stay unmatched instead of zero filled`() {
        val pre =
            phase(
                "pre",
                statements =
                    statements(
                        rows = listOf(statementRow(queryid = "42"), statementRow(queryid = "43")),
                    ),
            )
        val post =
            bindPost(
                pre,
                phase(
                    "post",
                    statements =
                        statements(
                            rows =
                                listOf(
                                    statementRow(queryid = "42", calls = 14, totalExecTime = "28"),
                                    statementRow(queryid = "44"),
                                ),
                        ),
                ),
            )

        val result = comparePostgresPhases(pre, post, HASH_C, 200, 300).getValue("statements").jsonObject

        assertEquals("DEGRADED", result.string("status"))
        assertEquals(listOf("PG_STATEMENTS_UNMATCHED"), result.reasons())
        assertEquals(1, result.getValue("rows").jsonArray.size)
        assertEquals(
            Json.parseToJsonElement("""[{"dbid":"1","userid":"10","queryid":"43","toplevel":true}]"""),
            result.getValue("unmatched_pre"),
        )
        assertEquals(
            Json.parseToJsonElement("""[{"dbid":"1","userid":"10","queryid":"44","toplevel":true}]"""),
            result.getValue("unmatched_post"),
        )
    }

    @Test
    fun `missing extensions degrade coverage without discarding exact table facts`() {
        val unavailableStatements =
            statements(
                status = "DEGRADED",
                reason = "PG_STATEMENTS_UNAVAILABLE",
                extensionVersion = null,
                statsReset = null,
                dealloc = null,
                rows = emptyList(),
            )
        val unavailableProfile =
            pgProfile(
                status = "DEGRADED",
                reason = "PG_PROFILE_UNAVAILABLE",
                extensionVersion = null,
                statementsReset = null,
                serverId = null,
                startSampleId = null,
                endSampleId = null,
                reportSha256 = null,
            )
        val pre = phase("pre", statements = unavailableStatements, pgProfile = unavailableProfile)
        val post = bindPost(pre, phase("post", statements = unavailableStatements, pgProfile = unavailableProfile))

        val context = comparePostgresPhases(pre, post, HASH_C, 200, 300)
        val table =
            context
                .getValue("tables")
                .jsonArray
                .single()
                .jsonObject

        assertEquals("DEGRADED", context.string("status"))
        assertTrue("PG_STATEMENTS_UNAVAILABLE" in context.reasons())
        assertTrue("PG_PROFILE_UNAVAILABLE" in context.reasons())
        assertEquals(0, table.long("inserted"))
        assertEquals(0, table.long("deleted"))
        assertEquals(0, table.long("updated"))
        assertEquals("DEGRADED", context.getValue("statements").jsonObject.string("status"))
        assertEquals("DEGRADED", context.getValue("pg_profile").jsonObject.string("status"))
    }

    @Test
    fun `phase validation normalizes table and statement row order`() {
        val forwardStatements = listOf(statementRow(queryid = "42"), statementRow(queryid = "43"))
        val reverseStatements = forwardStatements.reversed()
        val first =
            phase(
                "pre",
                tables = listOf(table(rows = listOf(listOf("2", "b"), listOf("1", "a")))),
                statements = statements(rows = reverseStatements),
            )
        val second =
            phase(
                "pre",
                tables = listOf(table(rows = listOf(listOf("1", "a"), listOf("2", "b")))),
                statements = statements(rows = forwardStatements),
            )

        val validatedFirst = validatePostgresPhase(first.toString().byteInputStream())
        val validatedSecond = validatePostgresPhase(second.toString().byteInputStream())

        assertEquals(validatedSecond, validatedFirst)
        assertEquals(sha256Hex(canonicalJson(validatedSecond)), sha256Hex(canonicalJson(validatedFirst)))
    }

    @Test
    fun `numeric equivalent statement keys are rejected in either row order`() {
        val rows = listOf(statementRow(queryid = "0"), statementRow(queryid = "-0"))

        listOf(rows, rows.reversed()).forEach { orderedRows ->
            val input = phase("pre", statements = statements(rows = orderedRows))

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    validatePostgresPhase(input.toString().byteInputStream())
                }

            assertEquals("PG_PHASE_INVALID", failure.message)
        }
    }

    @Test
    fun `phase validation rejects invalid types limits and inconsistent tables with safe code`() {
        val stringLimit = table().withValue("row_limit", JsonPrimitive("10000"))
        val unknown = phase("pre").withValue("top-secret", JsonPrimitive("do-not-echo"))
        val cases =
            listOf(
                unknown,
                phase("pre", databaseId = "jdbc:postgresql://secret@host/db"),
                phase("pre", tables = listOf(stringLimit)),
                phase("pre", tables = listOf(table(rows = listOf(listOf("1", "x".repeat(65_537)))))),
                phase("pre", tables = List(17) { index -> table(tableName = "table-$index") }),
                phase(
                    "pre",
                    tables =
                        listOf(
                            table(
                                stableKey = emptyList(),
                                rows = listOf(listOf("1", "a")),
                                status = "DEGRADED",
                                reason = "PG_TABLE_NO_STABLE_KEY",
                            ),
                        ),
                ),
            )

        cases.forEach { input ->
            val failure = assertThrows(IllegalArgumentException::class.java) { validatePostgresPhase(input.toString().byteInputStream()) }
            assertEquals("PG_PHASE_INVALID", failure.message)
            assertFalse(failure.toString().contains("do-not-echo"))
        }
    }

    @Test
    fun `strict phase boundary rejects duplicate keys depth numeric and byte attacks`() {
        val valid = phase("pre").toString()
        val duplicate =
            valid.replaceFirst(
                "\"schema_version\":\"postgres-phase.v1\"",
                "\"schema_version\":\"postgres-phase.v1\",\"schema_version\":\"postgres-phase.v1\"",
            )
        val tooDeep = "[".repeat(21) + "0" + "]".repeat(21)
        val largeExponent = valid.replaceFirst("\"capture_started_epoch_ms\":100", "\"capture_started_epoch_ms\":1e65")
        val longNumber = valid.replaceFirst("\"capture_started_epoch_ms\":100", "\"capture_started_epoch_ms\":${"1".repeat(65)}")

        listOf(duplicate, tooDeep, largeExponent, longNumber).forEach { raw ->
            assertPhaseFailure(raw.byteInputStream(), "PG_PHASE_INVALID")
        }
        assertPhaseFailure(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), "PG_PHASE_INVALID_UTF8")
        assertPhaseFailure(ByteArray(16 * 1024 * 1024 + 1).inputStream(), "PG_PHASE_TOO_LARGE")
    }

    @Test
    fun `comparison revalidates caller supplied JsonObjects`() {
        val invalid = phase("pre").withValue("unknown", JsonPrimitive(true))

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                comparePostgresPhases(invalid, null, HASH_C, 200, 300)
            }

        assertEquals("PG_PHASE_INVALID", failure.message)
    }

    @Test
    fun `canonical phase example passes the production validator`() {
        val path = Path.of("docs", "contracts", "sources", "v1", "postgres-phase.example.json")

        Files.newInputStream(path).use { input ->
            val example = validatePostgresPhase(input)

            assertEquals("postgres-phase.v1", example.string("schema_version"))
            assertEquals("pre", example.string("phase"))
            assertEquals("pg", example.string("profile_id"))
        }
    }

    private fun assertPhaseFailure(
        input: InputStream,
        expected: String,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) { validatePostgresPhase(input) }
        assertEquals(expected, failure.message)
    }

    private fun bindPost(
        pre: JsonObject,
        post: JsonObject,
    ): JsonObject = post.withValue("pre_sha256", JsonPrimitive(phaseHash(pre)))

    private fun phaseHash(phase: JsonObject): String = sha256Hex(canonicalJson(validatePostgresPhase(phase.toString().byteInputStream())))

    private fun phase(
        phase: String,
        preSha256: String? = null,
        profileId: String = "pg",
        databaseId: String = "test-db",
        revision: String = HASH_A,
        captureStarted: Long = if (phase == "pre") 100 else 300,
        captureEnded: Long = if (phase == "pre") 200 else 400,
        tables: List<JsonObject> = listOf(table()),
        statements: JsonObject = statements(),
        pgProfile: JsonObject = pgProfile(),
    ): JsonObject =
        buildJsonObject {
            put("schema_version", "postgres-phase.v1")
            put("phase", phase)
            put("profile_id", profileId)
            put("source_database_id", databaseId)
            put("profile_revision_sha256", revision)
            putNullable("pre_sha256", preSha256)
            put("capture_started_epoch_ms", captureStarted)
            put("capture_ended_epoch_ms", captureEnded)
            put("server_version", "15.0")
            put("configuration", buildJsonObject { put("work_mem", "4096") })
            put("tables", buildJsonArray { tables.forEach(::add) })
            put("statements", statements)
            put("pg_profile", pgProfile)
        }

    private fun table(
        tableName: String = "orders",
        columns: List<String> = listOf("id", "status"),
        columnTypes: List<String> = listOf("int8", "text"),
        stableKey: List<String> = listOf("id"),
        rowLimit: Int = 10_000,
        byteLimit: Int = 1_048_576,
        status: String = "COMPLETE",
        reason: String? = null,
        rows: List<List<String?>> = listOf(listOf("1", "new")),
        rowCount: Long? = rows.size.toLong(),
    ): JsonObject =
        buildJsonObject {
            put("schema", "public")
            put("table", tableName)
            put("columns", buildJsonArray { columns.forEach(::add) })
            put("column_types", buildJsonArray { columnTypes.forEach(::add) })
            put("stable_key", buildJsonArray { stableKey.forEach(::add) })
            put("row_limit", rowLimit)
            put("byte_limit", byteLimit)
            put("status", status)
            putNullable("reason", reason)
            putNullable("row_count", rowCount)
            put(
                "rows",
                buildJsonArray {
                    rows.forEach { row ->
                        add(buildJsonArray { row.forEach { cell -> add(cell?.let(::JsonPrimitive) ?: JsonNull) } })
                    }
                },
            )
        }

    private fun statements(
        status: String = "COMPLETE",
        reason: String? = null,
        extensionVersion: String? = "1.10",
        statsReset: String? = "2026-01-01T00:00:00Z",
        dealloc: Long? = 0,
        rowLimit: Int = 10_000,
        rows: List<JsonObject> = listOf(statementRow()),
    ): JsonObject =
        buildJsonObject {
            put("status", status)
            putNullable("reason", reason)
            putNullable("extension_version", extensionVersion)
            putNullable("stats_reset", statsReset)
            putNullable("dealloc", dealloc)
            put("row_limit", rowLimit)
            put("rows", buildJsonArray { rows.forEach(::add) })
        }

    private fun statementRow(
        dbid: String = "1",
        userid: String = "10",
        queryid: String? = "42",
        toplevel: Boolean = true,
        calls: Long = 10,
        totalExecTime: String = "20",
        rows: Long = 5,
        sharedBlksHit: Long = 2,
        sharedBlksRead: Long = 0,
        tempBlksWritten: Long = 0,
    ): JsonObject =
        buildJsonObject {
            put("dbid", dbid)
            put("userid", userid)
            putNullable("queryid", queryid)
            put("toplevel", toplevel)
            put("calls", calls)
            put("total_exec_time", JsonPrimitive(BigDecimal(totalExecTime)))
            put("rows", rows)
            put("shared_blks_hit", sharedBlksHit)
            put("shared_blks_read", sharedBlksRead)
            put("temp_blks_written", tempBlksWritten)
        }

    private fun pgProfile(
        status: String = "COMPLETE",
        reason: String? = null,
        extensionVersion: String? = "4.3",
        statementsReset: Boolean? = false,
        serverId: Long? = 1,
        startSampleId: Long? = 10,
        endSampleId: Long? = 11,
        reportSha256: String? = HASH_B,
    ): JsonObject =
        buildJsonObject {
            put("status", status)
            putNullable("reason", reason)
            putNullable("extension_version", extensionVersion)
            putNullable("statements_reset", statementsReset)
            putNullable("server_id", serverId)
            putNullable("start_sample_id", startSampleId)
            putNullable("end_sample_id", endSampleId)
            putNullable("report_sha256", reportSha256)
        }

    private fun JsonObject.withValue(
        name: String,
        value: JsonElement,
    ): JsonObject = JsonObject(toMutableMap().apply { put(name, value) })

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    private fun JsonObject.reasons(): List<String> = getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObjectBuilder.putNullable(
        name: String,
        value: String?,
    ) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(
        name: String,
        value: Long?,
    ) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.putNullable(
        name: String,
        value: Boolean?,
    ) {
        put(name, value?.let(::JsonPrimitive) ?: JsonNull)
    }

    private data class BindingCase(
        val pre: JsonObject,
        val post: JsonObject,
        val reason: String,
    )

    private data class TableCase(
        val pre: JsonObject,
        val post: JsonObject,
        val reason: String,
    )

    private companion object {
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val HASH_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}

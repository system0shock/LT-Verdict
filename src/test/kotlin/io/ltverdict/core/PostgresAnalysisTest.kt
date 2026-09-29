package io.ltverdict.core

import io.ltverdict.sources.PostgresAnalysisInput
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.sources.validatePostgresPhase
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class PostgresAnalysisTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `postgres analysis input rejects an empty import`() {
        val error = assertThrows(IllegalArgumentException::class.java) { readPostgresAnalysisInput() }

        assertEquals("PG_ANALYSIS_INPUT_MISSING", error.message)
    }

    @Test
    fun `postgres analysis input validates roles and binds the preferred report hash`() {
        val html = "<html>report</html>".encodeToByteArray()
        val hash = sha256Hex(html)
        val pre = phase("pre", reportHash = "a".repeat(64))
        val post = phase("post", reportHash = hash)

        val imported =
            readPostgresAnalysisInput(
                pre = pre.inputStream(),
                post = post.inputStream(),
                pgProfileHtml = html.inputStream(),
            )

        assertEquals(
            "pre",
            imported.pre
                ?.getValue("phase")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "post",
            imported.post
                ?.getValue("phase")
                ?.jsonPrimitive
                ?.content,
        )
        assertArrayEquals(html, imported.pgProfileHtml)

        assertFailure("PG_PRE_PHASE_INVALID") { readPostgresAnalysisInput(pre = post.inputStream()) }
        assertFailure("PG_POST_PHASE_INVALID") { readPostgresAnalysisInput(post = pre.inputStream()) }
        assertFailure("PG_PROFILE_HTML_HASH_MISMATCH") {
            readPostgresAnalysisInput(post = post.inputStream(), pgProfileHtml = "different".byteInputStream())
        }
    }

    @Test
    fun `standalone postgres HTML is bounded strict UTF8 and remains opaque bytes`() {
        val html = "<!doctype html><title>Отчёт</title>".encodeToByteArray()

        val imported = readPostgresAnalysisInput(pgProfileHtml = html.inputStream())

        assertNull(imported.pre)
        assertNull(imported.post)
        assertArrayEquals(html, imported.pgProfileHtml)
        assertFailure("PG_PROFILE_HTML_INVALID_UTF8") {
            readPostgresAnalysisInput(pgProfileHtml = ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))
        }
        assertFailure("PG_PROFILE_HTML_TOO_LARGE") {
            readPostgresAnalysisInput(pgProfileHtml = ByteArrayInputStream(ByteArray(4 * 1024 * 1024 + 1)))
        }
    }

    @Test
    fun `analysis compares postgres phases against parsed load window and stores fixed artifacts`() =
        withService { store, service ->
            val load = accept(store, VALID_CSV.encodeToByteArray(), "input.jtl")
            val fixture = boundFixture()

            val outcome = service.analyze(AnalysisRequest(load, null, postgres = fixture.input))
            val repeated = service.analyze(AnalysisRequest(load, null, postgres = fixture.input))
            val context = json(Files.readAllBytes(outcome.analysisDirectory.resolve("postgres-context.json")))

            assertEquals(outcome.analysisId, repeated.analysisId)
            assertEquals(load.sha256, context.getValue("load_input_sha256").jsonPrimitive.content)
            assertEquals(sha256Hex(fixture.html), context.getValue("pg_profile_html_sha256").jsonPrimitive.content)
            assertEquals("1767225600000", context.getValue("start_epoch_ms").jsonPrimitive.content)
            assertEquals("1767225601020", context.getValue("end_epoch_ms").jsonPrimitive.content)
            val table =
                context
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("1", table.getValue("inserted").jsonPrimitive.content)
            assertEquals("0", table.getValue("deleted").jsonPrimitive.content)
            assertEquals("1", table.getValue("updated").jsonPrimitive.content)
            assertArrayEquals(
                canonicalJson(requireNotNull(fixture.input.pre)),
                Files.readAllBytes(outcome.analysisDirectory.resolve("postgres-pre.json")),
            )
            assertArrayEquals(
                canonicalJson(requireNotNull(fixture.input.post)),
                Files.readAllBytes(outcome.analysisDirectory.resolve("postgres-post.json")),
            )
            assertArrayEquals(fixture.html, Files.readAllBytes(outcome.analysisDirectory.resolve("pg-profile.html")))
            assertTrue(result(outcome).getValue("evidence").jsonArray.contains(context))

            val identity = json(Files.readAllBytes(outcome.analysisDirectory.resolve("identity.json")))
            assertEquals(postgresHash(fixture.input), identity.getValue("postgres_input_sha256").jsonPrimitive.content)
            val withoutPostgres = service.analyze(AnalysisRequest(load, null))
            assertNotEquals(outcome.analysisId, withoutPostgres.analysisId)
            assertFalse(
                json(Files.readAllBytes(withoutPostgres.analysisDirectory.resolve("identity.json"))).containsKey("postgres_input_sha256"),
            )
        }

    @Test
    fun `analysis does not accept phase timestamps in place of the parsed load window`() =
        withService { store, service ->
            val load = accept(store, VALID_CSV.encodeToByteArray(), "window.jtl")
            val fixture = boundFixture(preEnded = 1_767_225_600_001)

            val outcome = service.analyze(AnalysisRequest(load, null, postgres = fixture.input))
            val context = json(Files.readAllBytes(outcome.analysisDirectory.resolve("postgres-context.json")))
            val table =
                context
                    .getValue("tables")
                    .jsonArray
                    .single()
                    .jsonObject

            assertTrue(context.getValue("reasons").jsonArray.any { it.jsonPrimitive.content == "PG_PRE_CAPTURE_LATE" })
            assertEquals(JsonNull, table.getValue("inserted"))
            assertEquals(JsonNull, table.getValue("deleted"))
            assertEquals(JsonNull, table.getValue("updated"))
        }

    @Test
    fun `invalid load retains postgres inputs with unavailable window and no deltas`() =
        withService { store, service ->
            val header = VALID_CSV.lineSequence().first()
            val load = accept(store, "$header\nmalformed\n".encodeToByteArray(), "invalid.jtl")
            val fixture = boundFixture()

            val outcome = service.analyze(AnalysisRequest(load, null, postgres = fixture.input))
            val context = json(Files.readAllBytes(outcome.analysisDirectory.resolve("postgres-context.json")))

            assertEquals("INVALID", result(outcome).getValue("run_validity").jsonPrimitive.content)
            assertEquals(JsonNull, context.getValue("start_epoch_ms"))
            assertEquals(JsonNull, context.getValue("end_epoch_ms"))
            assertEquals(sha256Hex(fixture.html), context.getValue("pg_profile_html_sha256").jsonPrimitive.content)
            assertEquals("DEGRADED", context.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("PG_LOAD_WINDOW_UNAVAILABLE"), context.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(context.getValue("tables").jsonArray.isEmpty())
            assertTrue(
                context
                    .getValue("statements")
                    .jsonObject
                    .getValue("rows")
                    .jsonArray
                    .isEmpty(),
            )
            assertEquals(
                setOf(
                    "analysis-result.json",
                    "identity.json",
                    "postgres-pre.json",
                    "postgres-post.json",
                    "postgres-context.json",
                    "pg-profile.html",
                ),
                store
                    .readAnalysis(load.runId, outcome.analysisId)!!
                    .artifacts
                    .map { it.path }
                    .toSet(),
            )
            assertTrue(result(outcome).getValue("evidence").jsonArray.contains(context))
        }

    @Test
    fun `analysis revalidates caller supplied postgres objects before identity or persistence`() =
        withService { store, service ->
            val load = accept(store, VALID_CSV.encodeToByteArray(), "untrusted.jtl")
            val validPre = requireNotNull(boundFixture().input.pre)
            val wrongRole = JsonObject(validPre + ("phase" to JsonPrimitive("post")))

            assertFailure("PG_PRE_PHASE_INVALID") {
                service.analyze(AnalysisRequest(load, null, postgres = PostgresAnalysisInput(pre = wrongRole)))
            }
            assertTrue(Files.walk(tempDir).use { paths -> paths.noneMatch { it.fileName.toString() == "identity.json" } })
        }

    private fun assertFailure(
        code: String,
        block: () -> Unit,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java, block)
        assertEquals(code, error.message)
    }

    private fun phase(
        role: String,
        reportHash: String? = null,
        preHash: String? = if (role == "post") "b".repeat(64) else null,
        captureStarted: Long = 100,
        captureEnded: Long = 200,
        rows: String? = null,
        rowCount: Int = 0,
    ): ByteArray {
        val encodedPreHash = preHash?.let { "\"$it\"" } ?: "null"
        val report = reportHash?.let { "\"$it\"" } ?: "null"
        val tables =
            rows?.let {
                """[{"schema":"public","table":"orders","columns":["id","status"],"column_types":["int8","text"],"stable_key":["id"],"row_limit":10000,"byte_limit":1048576,"status":"COMPLETE","reason":null,"row_count":$rowCount,"rows":$it}]"""
            } ?: "[]"
        return """
            {
              "schema_version":"postgres-phase.v1",
              "phase":"$role",
              "profile_id":"pg",
              "source_database_id":"test-db",
              "profile_revision_sha256":"${"a".repeat(64)}",
              "pre_sha256":$encodedPreHash,
              "capture_started_epoch_ms":$captureStarted,
              "capture_ended_epoch_ms":$captureEnded,
              "server_version":"15.0",
              "configuration":{"work_mem":"4096"},
              "tables":$tables,
              "statements":{"status":"DEGRADED","reason":"PG_STATEMENTS_UNAVAILABLE","extension_version":null,"stats_reset":null,"dealloc":null,"row_limit":10000,"rows":[]},
              "pg_profile":{"status":"DEGRADED","reason":"PG_PROFILE_UNAVAILABLE","extension_version":null,"statements_reset":null,"server_id":null,"start_sample_id":null,"end_sample_id":null,"report_sha256":$report}
            }
            """.trimIndent().encodeToByteArray()
    }

    private fun boundFixture(preEnded: Long = 1_767_225_599_999): Fixture {
        val html = "<!doctype html><title>pg_profile</title>".encodeToByteArray()
        val pre =
            phase(
                role = "pre",
                captureStarted = 1_767_225_599_900,
                captureEnded = preEnded,
                rows = "[[\"1\",\"new\"]]",
                rowCount = 1,
            )
        val preHash = sha256Hex(canonicalJson(validatePostgresPhase(pre.inputStream())))
        val post =
            phase(
                role = "post",
                reportHash = sha256Hex(html),
                preHash = preHash,
                captureStarted = 1_767_225_601_020,
                captureEnded = 1_767_225_601_100,
                rows = "[[\"1\",\"done\"],[\"2\",\"new\"]]",
                rowCount = 2,
            )
        return Fixture(
            readPostgresAnalysisInput(pre.inputStream(), post.inputStream(), html.inputStream()),
            html,
        )
    }

    private fun postgresHash(input: PostgresAnalysisInput): String =
        sha256Hex(
            canonicalJson(
                buildJsonObject {
                    input.pre?.let { put("pre_sha256", sha256Hex(canonicalJson(it))) }
                    input.post?.let { put("post_sha256", sha256Hex(canonicalJson(it))) }
                    input.pgProfileHtml?.let { put("pg_profile_html_sha256", sha256Hex(it)) }
                },
            ),
        )

    private fun withService(block: (RunBundleStore, AnalysisService) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()))
        }
    }

    private fun accept(
        store: RunBundleStore,
        bytes: ByteArray,
        name: String,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(bytes), name)

    private fun json(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun result(outcome: AnalysisOutcome): JsonObject = json(outcome.canonicalResult)

    private data class Fixture(
        val input: PostgresAnalysisInput,
        val html: ByteArray,
    )

    private companion object {
        val VALID_CSV =
            """
            timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
            1767225601000,20,steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            1767225600000,900,spike,500,Error,fixture,text,false,,0,0,1,1,null,0,0,0
            1767225600010,850,spike,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            """.trimIndent()
    }
}

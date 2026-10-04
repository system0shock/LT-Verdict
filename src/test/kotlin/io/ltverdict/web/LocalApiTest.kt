package io.ltverdict.web

import io.ltverdict.ai.AdviceUnavailableReason
import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AdvisoryRunner
import io.ltverdict.ai.AiAdviceStore
import io.ltverdict.ai.RunnerOutcome
import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.analysisIdentity
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.report.renderAsciiDocReport
import io.ltverdict.report.renderHtmlReport
import io.ltverdict.sources.ONLINE_LOAD
import io.ltverdict.sources.ONLINE_SOURCE_REQUEST
import io.ltverdict.sources.ONLINE_SOURCE_REQUEST_V3_AUTO
import io.ltverdict.sources.OnlineSourceFixture
import io.ltverdict.sources.PostgresProfile
import io.ltverdict.sources.PromqlSource
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.HdrHistogram.PackedHistogram
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

class LocalApiTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `saved analytics is bounded read only and Confluence report is downloadable`() =
        withServer { store, api ->
            api.bootstrap()
            val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
            val id = api.createJob(input.runId).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"
            val original = api.get("$base/result").body()
            val analytics = api.get("$base/analytics")
            assertEquals(200, analytics.statusCode())
            assertEquals(
                "saved-analytics.v1",
                analytics
                    .jsonObject()
                    .getValue("schema_version")
                    .jsonPrimitive.content,
            )
            assertEquals(
                1,
                analytics
                    .jsonObject()
                    .getValue("dynamics")
                    .jsonObject
                    .getValue("rows")
                    .jsonArray.size,
            )
            assertEquals(JsonNull, analytics.jsonObject()["transactions"])
            val hidden = api.get("$base/analytics?exclude=${input.runId}/$id").jsonObject()
            assertTrue(
                hidden
                    .getValue("dynamics")
                    .jsonObject
                    .getValue("rows")
                    .jsonArray
                    .isEmpty(),
            )
            assertEquals(400, api.get("$base/analytics?exclude=missing/analysis").statusCode())
            assertEquals(400, api.get("$base/analytics?format=unknown").statusCode())
            for (format in listOf("html", "asciidoc", "confluence")) {
                val export = api.get("$base/analytics?format=$format")
                assertEquals(200, export.statusCode())
                assertTrue(export.body().contains("LT Verdict N-run dynamics"))
                assertTrue(
                    export
                        .headers()
                        .firstValue("Content-Disposition")
                        .orElse("")
                        .contains("attachment"),
                )
            }
            for (query in listOf("limit=0", "limit=101", "transaction_limit=201", "transaction=" + "a".repeat(257))) {
                assertEquals(400, api.get("$base/analytics?$query").statusCode())
            }
            val report = api.get("$base/report?format=confluence")
            val chart = api.get("$base/report?format=svg")
            assertEquals(200, chart.statusCode())
            assertTrue(chart.body().startsWith("<svg "))
            assertTrue(
                chart
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .contains("image/svg+xml"),
            )
            assertEquals(200, report.statusCode())
            assertTrue(
                report
                    .headers()
                    .firstValue("Content-Disposition")
                    .orElse("")
                    .contains(".xhtml"),
            )
            assertEquals(original, api.get("$base/result").body())
            assertTrue(
                api
                    .get("/api/grafana")
                    .jsonObject()
                    .getValue("profiles")
                    .jsonArray
                    .isEmpty(),
            )
            assertEquals(404, api.get("$base/grafana-link?profile=missing&dashboard=demo&panel=1").statusCode())
        }

    @Test
    fun `advice status is restored by analysis and unavailable runner never changes verdict`() =
        withServer(
            adviceRunner = AdvisoryRunner { RunnerOutcome.Unavailable(AdviceUnavailableReason.OS_ISOLATION_NOT_PROVEN) },
        ) { store, api ->
            api.bootstrap()
            val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
            val id = api.createJob(input.runId).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"
            val original = api.get("$base/result").body()
            val response = api.post("$base/advice", "application/json", """{"confirm_external_transfer":true}""".encodeToByteArray())
            assertEquals(202, response.statusCode())
            val jobId =
                response
                    .jsonObject()
                    .getValue("job_id")
                    .jsonPrimitive.content
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var status = api.get("/api/advice-jobs/$jobId").jsonObject()
            while (status.getValue("state").jsonPrimitive.content in setOf("QUEUED", "PROCESSING") && System.nanoTime() < deadline) {
                LockSupport.parkNanos(1_000_000)
                status = api.get("/api/advice-jobs/$jobId").jsonObject()
            }
            assertEquals("UNAVAILABLE", status.getValue("state").jsonPrimitive.content)
            assertEquals(
                jobId,
                api
                    .get("$base/advice")
                    .jsonObject()
                    .getValue("job")
                    .jsonObject
                    .getValue("job_id")
                    .jsonPrimitive.content,
            )
            assertEquals(original, api.get("$base/result").body())
        }

    @Test
    fun `unconfigured Jenkins exposes no profiles or triggers`() =
        withServer { _, api ->
            api.bootstrap()
            assertTrue(
                api
                    .get("/api/jenkins")
                    .jsonObject()
                    .getValue("profiles")
                    .jsonArray
                    .isEmpty(),
            )
            assertEquals(
                404,
                api.post("/api/jenkins/missing/trigger", "application/json", "{\"parameters\":{}}".encodeToByteArray()).statusCode(),
            )
        }

    @Test
    fun `advice request takes a closed body without a transfer consent and missing runner preserves result`() =
        withServer { store, api ->
            api.bootstrap()
            val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
            val id = api.createJob(input.runId).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"
            val original = api.get("$base/result").body()
            assertEquals(200, api.get("$base/advice").statusCode())
            assertEquals(JsonNull, api.get("$base/advice").jsonObject()["advice"])
            val rejected =
                listOf(
                    """{"confirm_external_transfer":false}""",
                    """{"confirm_external_transfer":"true"}""",
                    """{"confirm_external_transfer":1}""",
                    """{"confirm_external_transfer":null}""",
                    """{"x":1}""",
                    """{"model_id":"deepseek-v4-flash-0731"}""",
                    """{"confirm_external_transfer":true,"x":1}""",
                    """{"confirm_external_transfer":true,"confirm_external_transfer":true}""",
                    """{"confirm_external_transfer":false,"confirm_external_transfer":true}""",
                    """{"confirm_external_transfer":true,"confirm_external_transfer":false}""",
                    """{"confirm\u005fexternal_transfer":true}""",
                    "[]",
                    "true",
                    "null",
                    "{",
                    "{} {}",
                )
            for (body in rejected) {
                val response = api.post("$base/advice", "application/json", body.encodeToByteArray())
                assertEquals(400, response.statusCode(), body)
                assertEquals("MALFORMED_REQUEST", response.errorCode())
            }
            // The size limit is 512 bytes and it is a 400, not a 413.
            val padded = { size: Int -> "{}" + " ".repeat(size - 2) }
            val oversized = api.post("$base/advice", "application/json", padded(513).encodeToByteArray())
            assertEquals(400, oversized.statusCode())
            assertEquals("MALFORMED_REQUEST", oversized.errorCode())
            // Valid bodies pass the check and reach the missing-runner branch (503), never a 400.
            val accepted =
                listOf(
                    "",
                    " ",
                    "{}",
                    " { } ",
                    "{\r\n}",
                    """{"confirm_external_transfer":true}""",
                    """ { "confirm_external_transfer" : true } """,
                    padded(512),
                )
            for (body in accepted) {
                val response = api.post("$base/advice", "application/json", body.encodeToByteArray())
                assertEquals(503, response.statusCode(), body)
                assertEquals("AI_UNAVAILABLE", response.errorCode())
            }
            assertEquals(403, api.postUnauthenticated("$base/advice", "{}".encodeToByteArray()).statusCode())
            assertEquals(original, api.get("$base/result").body())
        }

    @Test
    fun `advice request with an empty body or an empty object starts a task`() {
        for (body in listOf("", "{}")) {
            withServer(
                adviceRunner = AdvisoryRunner { RunnerOutcome.Unavailable(AdviceUnavailableReason.OS_ISOLATION_NOT_PROVEN) },
            ) { store, api ->
                api.bootstrap()
                val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
                val id = api.createJob(input.runId).analysisId(api)
                val response = api.post("/api/runs/${input.runId}/analyses/$id/advice", "application/json", body.encodeToByteArray())
                assertEquals(202, response.statusCode(), body)
                assertTrue("job_id" in response.jsonObject())
            }
        }
    }

    @Test
    fun `unconfigured backend exposes no online profiles`() =
        withServer { _, api ->
            api.bootstrap()
            val response = api.get("/api/sources")
            assertEquals(200, response.statusCode())
            assertTrue(
                response
                    .jsonObject()
                    .getValue("profiles")
                    .jsonArray
                    .isEmpty(),
            )
        }

    @Test
    fun `OpenSearch context import downloads and rejects wrong load binding`() =
        withServer { store, api ->
            api.bootstrap()
            val load = ONLINE_LOAD.replace("1767225600000,1000", "1000,2000")
            val input = store.acceptInput(load.byteInputStream(), "errors.jtl")
            val context =
                Files
                    .readString(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))
                    .replace("a".repeat(64), input.sha256)
            val id = api.createJob(input.runId, sourceContext = context.encodeToByteArray()).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"
            val download = api.get("$base/source-context")
            assertEquals(200, download.statusCode())
            assertTrue(
                download
                    .headers()
                    .firstValue("Content-Disposition")
                    .orElse("")
                    .contains("attachment"),
            )
            assertEquals(
                "3",
                download
                    .jsonObject()
                    .getValue("total_errors")
                    .jsonPrimitive.content,
            )
            assertError(api.get("$base/resource-snapshot"), 404)
            val replay = api.createJob(input.runId, sourceContext = download.body().encodeToByteArray()).analysisId(api)
            assertEquals(id, replay)
            assertError(api.createJob(input.runId, sourceContext = context.replace(input.sha256, "b".repeat(64)).encodeToByteArray()), 400)
            assertError(api.createJob(input.runId, sourceContext = "{}".encodeToByteArray()), 400)
            assertError(
                api.createJob(input.runId, sourceContext = context.encodeToByteArray(), source = ONLINE_SOURCE_REQUEST.encodeToByteArray()),
                400,
            )
        }

    @Test
    fun `multiple contexts download in profile order and replay deterministically`() =
        withServer { store, api ->
            api.bootstrap()
            val input = store.acceptInput(ONLINE_LOAD.byteInputStream(), "multiple.jtl")
            val first =
                Files
                    .readString(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))
                    .replace("a".repeat(64), input.sha256)
            val profile =
                Json
                    .parseToJsonElement(first)
                    .jsonObject
                    .getValue("profile_id")
                    .jsonPrimitive.content
            val second = first.replace("\"$profile\"", "\"z-errors\"")
            val contexts = listOf(second.encodeToByteArray(), first.encodeToByteArray())
            val id = api.createJob(input.runId, sourceContexts = contexts).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id/source-context"
            val downloads =
                (1..2).map { index ->
                    api
                        .get("$base/$index")
                        .also { assertEquals(200, it.statusCode()) }
                        .body()
                        .encodeToByteArray()
                }
            assertEquals(
                profile,
                Json
                    .parseToJsonElement(downloads.first().decodeToString())
                    .jsonObject
                    .getValue("profile_id")
                    .jsonPrimitive.content,
            )
            assertEquals(id, api.createJob(input.runId, sourceContexts = downloads).analysisId(api))
            assertError(api.get(base), 404)
            listOf("0", "3", "17", "01", "foo").forEach { assertError(api.get("$base/$it"), 404) }
            assertError(api.createJob(input.runId, sourceContexts = listOf(contexts.first(), contexts.first())), 400)
            assertError(api.createJob(input.runId, sourceContexts = List(17) { contexts.first() }), 400)
        }

    @Test
    fun `PostgreSQL files replay with inert download and capture rejects unconfigured profiles`() =
        withServer { store, api ->
            api.bootstrap()
            val input = store.acceptInput(ONLINE_LOAD.byteInputStream(), "postgres.jtl")
            val pre = Files.readAllBytes(Path.of("docs/contracts/sources/v1/postgres-phase.example.json"))
            val html = "<script>throw new Error('never inline')</script>".encodeToByteArray()
            val id = api.createJob(input.runId, postgresPre = pre, pgProfileHtml = html).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"
            val downloaded = api.get("$base/postgres-pre")
            assertEquals(200, downloaded.statusCode())
            val report = api.get("$base/pg-profile")
            assertEquals(200, report.statusCode())
            assertTrue(
                report
                    .headers()
                    .firstValue("Content-Type")
                    .orElseThrow()
                    .startsWith("application/octet-stream"),
            )
            assertTrue(
                report
                    .headers()
                    .firstValue("Content-Disposition")
                    .orElseThrow()
                    .contains("attachment"),
            )
            assertEquals(html.decodeToString(), report.body())
            assertEquals(200, api.get("$base/postgres-context").statusCode())
            assertError(api.get("$base/postgres-post"), 404)
            assertEquals(
                id,
                api.createJob(input.runId, postgresPre = downloaded.body().encodeToByteArray(), pgProfileHtml = html).analysisId(api),
            )
            assertError(api.createJob(input.runId, postgresPost = pre), 400)
            assertError(
                api.multipart("/api/sources/postgresql/pre", listOf(FormPart("profile_id", "not-configured".encodeToByteArray()))),
                400,
            )
        }

    @Test
    fun `PostgreSQL capture exposes only profile metadata and stable credential errors`() {
        val profile =
            PostgresProfile(
                id = "pg-private",
                sourceDatabaseId = "private-db-id",
                host = "private-db.example",
                database = "private-db",
                usernameEnv = "LTV_TEST_MISSING_PG_USER_671DB",
                passwordEnv = "LTV_TEST_MISSING_PG_PASSWORD_671DB",
            )
        withServer(postgresProfiles = listOf(profile)) { _, api ->
            api.bootstrap()
            val listed = api.get("/api/sources")
            assertEquals(
                Json.parseToJsonElement("""{"profiles":[{"id":"pg-private","source_kind":"postgresql","transport":"jdbc"}]}"""),
                listed.jsonObject(),
            )
            val part = FormPart("profile_id", "pg-private".encodeToByteArray())
            val failed = api.multipart("/api/sources/postgresql/pre", listOf(part))
            assertError(failed, 422, "PG_CREDENTIALS_MISSING")
            assertFalse(failed.body().contains("private-db"))
            assertFalse(failed.body().contains("LTV_TEST"))
            assertError(api.multipart("/api/sources/postgresql/pre", listOf(part, FormPart("sql", "SELECT 1".encodeToByteArray()))), 400)
            assertError(api.multipart("/api/sources/postgresql/pre", listOf(part, part)), 400)
            assertError(api.post("/api/sources/postgresql/pre", "multipart/form-data; boundary=broken", "broken".encodeToByteArray()), 400)
        }
    }

    @Test
    fun `online job persists evidence and snapshot download replays without network`() {
        OnlineSourceFixture().use { fixture ->
            val profiles = readSourceProfiles(fixture.profilesJson().byteInputStream())
            val source = PromqlSource(profiles, SourceHttp(profiles))
            withServer(
                jobsFactory = { store ->
                    val service = AnalysisService(store, EngineConfig())
                    AnalysisJobs(1) { request, progress, cancelled -> analyzeWithSources(service, request, source, progress, cancelled) }
                },
                sourceProfiles = profiles,
            ) { store, api ->
                api.bootstrap()
                val listed =
                    api
                        .get("/api/sources")
                        .jsonObject()
                        .getValue("profiles")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(setOf("id", "source_kind", "transport"), listed.keys)
                val input = store.acceptInput(ONLINE_LOAD.byteInputStream(), "online.jtl")
                val id = api.createJob(input.runId, source = ONLINE_SOURCE_REQUEST.encodeToByteArray()).analysisId(api)
                val base = "/api/runs/${input.runId}/analyses/$id"
                val result = api.get("$base/result").jsonObject()
                val summary =
                    result
                        .getValue("evidence")
                        .jsonArray
                        .single {
                            it.jsonObject["type"]?.jsonPrimitive?.content ==
                                "source_summary"
                        }.jsonObject
                assertEquals("COMPLETE", summary.getValue("status").jsonPrimitive.content)
                val download = api.get("$base/resource-snapshot")
                assertEquals(200, download.statusCode())
                assertTrue(
                    download
                        .headers()
                        .firstValue("Content-Disposition")
                        .orElse("")
                        .contains("attachment"),
                )
                val replayId = api.createJob(input.runId, resources = download.body().encodeToByteArray()).analysisId(api)
                assertEquals(
                    result.getValue("policy_verdict"),
                    api.get("/api/runs/${input.runId}/analyses/$replayId/result").jsonObject().getValue("policy_verdict"),
                )
                assertEquals(1, fixture.requests.get())
                assertTrue(store.readAnalysis(input.runId, id)!!.artifacts.any { it.path == "source-acquisition.json" })
                fixture.responseStatus = 401
                val failedId = api.createJob(input.runId, source = ONLINE_SOURCE_REQUEST.encodeToByteArray()).analysisId(api)
                val failedResult = api.get("/api/runs/${input.runId}/analyses/$failedId/result").jsonObject()
                val failedEvidence = failedResult.getValue("evidence").jsonArray
                assertEquals(
                    "FAILED",
                    failedEvidence
                        .single {
                            it.jsonObject["type"]?.jsonPrimitive?.content == "source_summary"
                        }.jsonObject
                        .getValue("status")
                        .jsonPrimitive.content,
                )
                assertTrue(
                    failedEvidence.any {
                        it.jsonObject["type"]?.jsonPrimitive?.content == "window_policy_summary" &&
                            it.jsonObject["resource_verdict"]?.jsonPrimitive?.content == "NO_VERDICT"
                    },
                )
                assertEquals(2, fixture.requests.get())
                assertError(api.createJob(input.runId, source = ONLINE_SOURCE_REQUEST.replace("local", "unknown").encodeToByteArray()), 400)
                assertError(
                    api.createJob(
                        input.runId,
                        resources = download.body().encodeToByteArray(),
                        source = ONLINE_SOURCE_REQUEST.encodeToByteArray(),
                    ),
                    400,
                )
            }
        }
    }

    @Test
    fun `online job derives the v3 auto window and rejects an invalid v3 document`() {
        OnlineSourceFixture().use { fixture ->
            val profiles = readSourceProfiles(fixture.profilesJson().byteInputStream())
            val source = PromqlSource(profiles, SourceHttp(profiles))
            withServer(
                jobsFactory = { store ->
                    val service = AnalysisService(store, EngineConfig())
                    AnalysisJobs(1) { request, progress, cancelled -> analyzeWithSources(service, request, source, progress, cancelled) }
                },
                sourceProfiles = profiles,
            ) { store, api ->
                api.bootstrap()
                val input = store.acceptInput(ONLINE_LOAD.byteInputStream(), "auto.jtl")
                val id = api.createJob(input.runId, source = ONLINE_SOURCE_REQUEST_V3_AUTO.encodeToByteArray()).analysisId(api)
                val summary =
                    api
                        .get("/api/runs/${input.runId}/analyses/$id/result")
                        .jsonObject()
                        .getValue("evidence")
                        .jsonArray
                        .single { it.jsonObject["type"]?.jsonPrimitive?.content == "source_summary" }
                        .jsonObject
                assertEquals("COMPLETE", summary.getValue("status").jsonPrimitive.content)
                assertEquals("auto", summary.getValue("window_origin").jsonPrimitive.content)
                assertEquals("DERIVED", summary.getValue("auto_window_status").jsonPrimitive.content)
                assertEquals(1, fixture.requests.get())

                assertError(
                    api.createJob(
                        input.runId,
                        source = ONLINE_SOURCE_REQUEST_V3_AUTO.replace("\"margin_ms\":0", "\"margin_ms\":1500").encodeToByteArray(),
                    ),
                    400,
                    "MALFORMED_REQUEST",
                )
                assertEquals(1, fixture.requests.get())
            }
        }
    }

    @Test
    fun `online job reports an auto window refusal as an actionable diagnostic`() {
        OnlineSourceFixture().use { fixture ->
            val profiles = readSourceProfiles(fixture.profilesJson().byteInputStream())
            val source = PromqlSource(profiles, SourceHttp(profiles))
            withServer(
                jobsFactory = { store ->
                    val service = AnalysisService(store, EngineConfig())
                    AnalysisJobs(1) { request, progress, cancelled -> analyzeWithSources(service, request, source, progress, cancelled) }
                },
                sourceProfiles = profiles,
            ) { store, api ->
                api.bootstrap()
                // Простой 298 s между секундными бакетами 1767225601 и 1767225900 длиннее допуска 60000 ms.
                val load =
                    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes," +
                        "grpThreads,allThreads,Latency,IdleTime,Connect\n" +
                        "1767225600000,1000,one,200,OK,thread,true,1,1,1,1,1,0,0\n" +
                        "1767225601000,1000,two,200,OK,thread,true,1,1,1,1,1,0,0\n" +
                        "1767225900000,1000,three,200,OK,thread,true,1,1,1,1,1,0,0\n"
                val input = store.acceptInput(ByteArrayInputStream(load.encodeToByteArray()), "gapped.jtl")

                val submitted = api.createJob(input.runId, source = ONLINE_SOURCE_REQUEST_V3_AUTO.encodeToByteArray())
                val submittedJob = submitted.jsonObject()
                val status = awaitFailed(api, submittedJob.getValue("job_id").jsonPrimitive.content)
                val diagnostic = status.getValue("diagnostic").jsonObject
                val message = diagnostic.getValue("message").jsonPrimitive.content

                assertEquals("AUTO_WINDOW_MULTI_TEST_SUSPECTED", diagnostic.getValue("code").jsonPrimitive.content)
                assertTrue(message.contains("max_idle_gap_ms"))
                assertEquals(0, fixture.requests.get())
            }
        }
    }

    @Test
    fun `baseline API rejects malformed unauthenticated oversized and ineligible requests`() =
        withServer { _, api ->
            assertError(api.postUnauthenticated("/api/baseline", "{}".encodeToByteArray()), 403, "FORBIDDEN")
            api.bootstrap()
            assertError(api.get("/api/baseline?extra=1"), 400)
            assertError(api.delete("/api/baseline?extra=1&extra=2"), 400)
            listOf(
                "{}",
                "[]",
                "{",
                """{"mode":"manual","series":"x","reference":{"run_id":"../outside","analysis_id":"a"}}""",
                """{"mode":"manual","series":"x","reference":null}""",
                """{"mode":"manual","series":"x","reference":{},"extra":true}""",
            ).forEach { body -> assertError(api.post("/api/baseline", "application/json", body.encodeToByteArray()), 400) }
            assertError(api.post("/api/baseline", "application/json", byteArrayOf(0xff.toByte())), 400)
            assertError(api.post("/api/baseline", "text/plain", "{}".encodeToByteArray()), 415)
            assertError(api.post("/api/baseline", "application/json", " ".repeat(16_385).encodeToByteArray()), 413)
            assertError(api.post("/api/baseline", "application/json", ("[".repeat(9) + "]".repeat(9)).encodeToByteArray()), 413)
            assertError(
                api.post(
                    "/api/baseline",
                    "application/json",
                    """{"mode":"statistical","series":"x","candidates":[],"comparable":true}""".encodeToByteArray(),
                ),
                422,
            )
            assertError(
                api.post(
                    "/api/baseline",
                    "application/json",
                    """{"mode":"statistical","series":"x","candidates":[],"comparable":false}""".encodeToByteArray(),
                ),
                422,
            )
            assertEquals(JsonNull, api.get("/api/baseline").jsonObject().getValue("baseline"))
        }

    @Test
    fun `baseline API manually selects reloads compares and clears a pinned analysis`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val baselineId = api.createJob(input.runId).analysisId(api)
            val currentId = api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)

            assertEquals(JsonNull, api.get("/api/baseline").jsonObject().getValue("baseline"))

            val selection =
                api
                    .post(
                        "/api/baseline",
                        "application/json",
                        """{"mode":"manual","series":"release","reference":{"run_id":"${input.runId}","analysis_id":"$baselineId"}}"""
                            .encodeToByteArray(),
                    ).jsonObject()
                    .getValue("baseline")
                    .jsonObject
            assertEquals(
                setOf("schema_version", "series", "mode", "reference", "algorithm", "candidates", "scores"),
                selection.keys,
            )
            assertEquals("local-baseline.v1", selection.getValue("schema_version").jsonPrimitive.content)
            assertEquals("release", selection.getValue("series").jsonPrimitive.content)
            assertEquals("manual", selection.getValue("mode").jsonPrimitive.content)
            assertEquals(JsonNull, selection.getValue("algorithm"))
            assertEquals(selection.getValue("reference"), selection.getValue("candidates").jsonArray.single())
            assertTrue(selection.getValue("scores").jsonArray.isEmpty())
            assertEquals(selection, api.get("/api/baseline").jsonObject().getValue("baseline"))

            val comparison =
                api
                    .get("/api/runs/${input.runId}/analyses/$currentId/comparison")
                    .jsonObject()
            assertEquals(setOf("baseline", "current", "comparability", "warnings", "metrics", "conditions"), comparison.keys)
            assertEquals(
                listOf("BASELINE_IS_CURRENT_RUN"),
                comparison.getValue("warnings").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(selection, comparison.getValue("baseline"))
            assertEquals("UNCONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
            assertEquals(JsonNull, comparison.getValue("conditions"))
            assertEquals(
                listOf("response_time_p95_ms", "response_time_p99_ms", "throughput_rps", "error_rate_ratio"),
                comparison
                    .getValue("metrics")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("metric")
                            .jsonPrimitive.content
                    },
            )

            val windowPath = "/api/runs/${input.runId}/analyses/$currentId/comparison"
            val windows = api.get("$windowPath?baseline_window=before&current_window=after")
            assertEquals(200, windows.statusCode())
            assertEquals(
                "NOT_EVALUATED",
                windows
                    .jsonObject()
                    .getValue("window_comparison")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertError(api.get("$windowPath?baseline_window=before"), 400, "MALFORMED_REQUEST")
            assertError(api.get("$windowPath?baseline_window=before&current_window=after&min_change_percent=0"), 400, "MALFORMED_REQUEST")
            assertError(
                api.get("$windowPath?baseline_window=before&current_window=after&min_error_rate_delta=1e999999"),
                400,
                "MALFORMED_REQUEST",
            )

            assertEquals(JsonNull, api.delete("/api/baseline").jsonObject().getValue("baseline"))
            assertEquals(JsonNull, api.get("/api/baseline").jsonObject().getValue("baseline"))
            assertError(api.get("/api/runs/${input.runId}/analyses/$currentId/comparison"), 404, "NOT_FOUND")
        }

    @Test
    fun `baseline conditions API persists three states and isolates exact pair and windows`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val baselineId = api.createJob(input.runId).analysisId(api)
            val currentId = api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)
            val baselineBody =
                """{"mode":"manual","series":"release","reference":{"run_id":"${input.runId}","analysis_id":"$baselineId"}}"""
            api.post("/api/baseline", "application/json", baselineBody.encodeToByteArray())
            val path = "/api/runs/${input.runId}/analyses/$currentId/baseline-conditions"

            assertEquals(JsonNull, api.get(path).jsonObject().getValue("conditions"))
            assertError(api.postUnauthenticated(path, """{"decision":"CONFIRMED"}""".encodeToByteArray()), 403, "FORBIDDEN")
            listOf(
                "{}",
                """{"decision":true}""",
                """{"decision":"MAYBE"}""",
                """{"decision":"CONFIRMED","extra":true}""",
            ).forEach { body -> assertError(api.post(path, "application/json", body.encodeToByteArray()), 400, "MALFORMED_REQUEST") }
            assertError(api.post(path, "text/plain", """{"decision":"CONFIRMED"}""".encodeToByteArray()), 415)
            assertError(api.post(path, "application/json", " ".repeat(16_385).encodeToByteArray()), 413)
            assertError(api.get("$path?extra=1"), 400)
            assertError(api.get("$path?baseline_window=before"), 400)
            assertError(api.get("/api/runs/not-a-run/analyses/$currentId/baseline-conditions"), 400)

            val confirmed =
                api
                    .post(path, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")
                    .jsonObject
            assertEquals(
                setOf("schema_version", "baseline", "current", "windows", "decision", "provenance", "updated_at"),
                confirmed.keys,
            )
            assertEquals("local-baseline-conditions.v1", confirmed.getValue("schema_version").jsonPrimitive.content)
            assertEquals("CONFIRMED", confirmed.getValue("decision").jsonPrimitive.content)
            assertEquals("EXPLICIT_LOCAL_ACTION", confirmed.getValue("provenance").jsonPrimitive.content)
            Instant.parse(confirmed.getValue("updated_at").jsonPrimitive.content)
            assertEquals(confirmed, api.get(path).jsonObject().getValue("conditions"))

            val comparison = api.get("/api/runs/${input.runId}/analyses/$currentId/comparison").jsonObject()
            assertEquals("USER_CONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
            assertEquals(confirmed, comparison.getValue("conditions"))
            val otherPair = api.get("/api/runs/${input.runId}/analyses/$baselineId/comparison").jsonObject()
            assertEquals("UNCONFIRMED", otherPair.getValue("comparability").jsonPrimitive.content)
            assertEquals(JsonNull, otherPair.getValue("conditions"))

            val notConfirmed =
                api
                    .post(path, "application/json", """{"decision":"NOT_CONFIRMED"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")
            assertEquals(
                "NOT_CONFIRMED",
                notConfirmed.jsonObject
                    .getValue("decision")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "UNCONFIRMED",
                api
                    .get("/api/runs/${input.runId}/analyses/$currentId/comparison")
                    .jsonObject()
                    .getValue("comparability")
                    .jsonPrimitive.content,
            )

            val unknown =
                api
                    .post(path, "application/json", """{"decision":"UNKNOWN"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")
            assertEquals(
                "UNKNOWN",
                unknown.jsonObject
                    .getValue("decision")
                    .jsonPrimitive.content,
            )
            assertEquals(unknown, api.get(path).jsonObject().getValue("conditions"))

            val windowPath = "$path?baseline_window=before&current_window=after"
            val windowed =
                api
                    .post(windowPath, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")
            assertEquals(windowed, api.get(windowPath).jsonObject().getValue("conditions"))
            assertEquals(JsonNull, api.get("$path?baseline_window=before&current_window=other").jsonObject().getValue("conditions"))
            assertEquals(
                windowed,
                api
                    .get(
                        "/api/runs/${input.runId}/analyses/$currentId/comparison?baseline_window=before&current_window=after&min_change_percent=10",
                    ).jsonObject()
                    .getValue("conditions"),
            )

            api.delete("/api/baseline")
            api.post("/api/baseline", "application/json", baselineBody.encodeToByteArray())
            assertEquals(JsonNull, api.get(path).jsonObject().getValue("conditions"))
            assertEquals(JsonNull, api.get(windowPath).jsonObject().getValue("conditions"))
        }

    @Test
    fun `statistical baseline is confirmed only by an explicit pair decision and reports warnings`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 1000, 2000))
            val candidates = runs.take(3)
            val baseline = api.selectStatistical(candidates)
            val winner = baseline.getValue("reference").jsonObject
            val winnerRun = winner.getValue("run_id").jsonPrimitive.content
            val winnerAnalysis = winner.getValue("analysis_id").jsonPrimitive.content
            val (testedRun, testedAnalysis) = runs[3]

            val before = api.comparisonOf(testedRun, testedAnalysis)
            assertEquals("UNCONFIRMED", before.getValue("comparability").jsonPrimitive.content)
            assertEquals(emptyList<String>(), before.warnings())
            assertEquals(JsonNull, before.getValue("conditions"))

            val saved =
                api
                    .post(
                        "/api/runs/$testedRun/analyses/$testedAnalysis/baseline-conditions",
                        "application/json",
                        """{"decision":"CONFIRMED"}""".encodeToByteArray(),
                    ).jsonObject()
                    .getValue("conditions")
            val after = api.comparisonOf(testedRun, testedAnalysis)
            assertEquals("USER_CONFIRMED", after.getValue("comparability").jsonPrimitive.content)
            assertEquals(saved, after.getValue("conditions"))
            assertEquals(before.getValue("metrics"), after.getValue("metrics"))
            assertEquals(emptyList<String>(), after.warnings())

            val (memberRun, memberAnalysis) = candidates.first { it.first != winnerRun }
            val member = api.comparisonOf(memberRun, memberAnalysis)
            assertEquals("UNCONFIRMED", member.getValue("comparability").jsonPrimitive.content)
            assertEquals(listOf("CURRENT_IN_CANDIDATE_SET"), member.warnings())

            val itself = api.comparisonOf(winnerRun, winnerAnalysis)
            assertEquals("UNCONFIRMED", itself.getValue("comparability").jsonPrimitive.content)
            assertEquals(listOf("BASELINE_IS_CURRENT_ANALYSIS", "CURRENT_IN_CANDIDATE_SET"), itself.warnings())

            val otherId = api.createJob(winnerRun, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)
            val sameRun = api.comparisonOf(winnerRun, otherId)
            assertEquals(listOf("BASELINE_IS_CURRENT_RUN", "CURRENT_IN_CANDIDATE_SET"), sameRun.warnings())
        }

    @Test
    fun `a saved pair decision follows the statistical winner across candidate changes and is cleared with the baseline`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 1000, 2000, 1500))
            val first = api.selectStatistical(runs.take(3))
            val winner = first.getValue("reference").jsonObject
            val winnerRun = winner.getValue("run_id").jsonPrimitive.content
            val (testedRun, testedAnalysis) = runs[3]
            val conditionsPath = "/api/runs/$testedRun/analyses/$testedAnalysis/baseline-conditions"

            val manualBody = """{"mode":"manual","series":"release","reference":$winner}"""
            assertEquals(200, api.post("/api/baseline", "application/json", manualBody.encodeToByteArray()).statusCode())
            val saved =
                api
                    .post(conditionsPath, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")

            api.selectStatistical(runs.take(3))
            val carried = api.comparisonOf(testedRun, testedAnalysis)
            assertEquals("USER_CONFIRMED", carried.getValue("comparability").jsonPrimitive.content)
            assertEquals(saved, carried.getValue("conditions"))

            val changed = api.selectStatistical(listOf(runs[0], runs[1], runs[4]))
            assertEquals(winner, changed.getValue("reference"))
            assertEquals(3, changed.getValue("candidates").jsonArray.size)
            assertTrue(
                changed.getValue("candidates").jsonArray.none {
                    it.jsonObject
                        .getValue("run_id")
                        .jsonPrimitive.content == testedRun
                },
            )
            val kept = api.comparisonOf(testedRun, testedAnalysis)
            assertEquals("USER_CONFIRMED", kept.getValue("comparability").jsonPrimitive.content)
            assertEquals(saved, api.get(conditionsPath).jsonObject().getValue("conditions"))

            assertEquals(JsonNull, api.delete("/api/baseline").jsonObject().getValue("baseline"))
            api.selectStatistical(runs.take(3))
            assertEquals(JsonNull, api.get(conditionsPath).jsonObject().getValue("conditions"))
            assertEquals(
                "UNCONFIRMED",
                api
                    .comparisonOf(testedRun, testedAnalysis)
                    .getValue("comparability")
                    .jsonPrimitive.content,
            )
        }

    private fun statisticalRuns(
        store: RunBundleStore,
        api: ApiClient,
        elapsed: List<Int>,
    ): List<Pair<String, String>> =
        elapsed.mapIndexed { index, value ->
            val load = "timeStamp,elapsed,label,success\n${1_767_225_600_000L + index * 1_000L},$value,checkout,true\n"
            val input = store.acceptInput(ByteArrayInputStream(load.encodeToByteArray()), "series-$index.jtl")
            input.runId to api.createJob(input.runId).analysisId(api)
        }

    private fun ApiClient.selectStatistical(candidates: List<Pair<String, String>>): JsonObject {
        val references = candidates.joinToString(",") { (run, analysis) -> """{"run_id":"$run","analysis_id":"$analysis"}""" }
        val response =
            post(
                "/api/baseline",
                "application/json",
                """{"mode":"statistical","series":"release","candidates":[$references],"comparable":true}""".encodeToByteArray(),
            )
        assertEquals(200, response.statusCode())
        return response.jsonObject().getValue("baseline").jsonObject
    }

    private fun ApiClient.comparisonOf(
        run: String,
        analysis: String,
    ): JsonObject = get("/api/runs/$run/analyses/$analysis/comparison").jsonObject()

    private fun JsonObject.warnings(): List<String> = getValue("warnings").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `private API uploads validates analyzes and returns exact result and bucket pages`() =
        withServer { store, api ->
            val bootstrap = api.bootstrap()
            assertEquals(200, bootstrap.statusCode())
            val bootstrapJson = bootstrap.jsonObject()
            assertEquals(setOf("csrf_token", "max_upload_bytes"), bootstrapJson.keys)
            assertTrue(
                bootstrapJson
                    .getValue("csrf_token")
                    .jsonPrimitive.content
                    .isNotEmpty(),
            )
            assertEquals(4_294_967_296L, bootstrapJson.getValue("max_upload_bytes").jsonPrimitive.long)

            val upload = api.upload(SPIKE_DROP)
            assertEquals(201, upload.statusCode())
            assertRunSummary(upload.jsonObject(), SPIKE_DROP)
            listOf(GATLING_TEXT, JMETER_XML).forEach { fixture ->
                val response = api.upload(fixture)
                assertEquals(201, response.statusCode())
                assertRunSummary(response.jsonObject(), fixture)
            }

            assertRunPage(api.get("/api/runs?limit=1"), GATLING_TEXT, GATLING_TEXT.runId)
            assertRunPage(
                api.get("/api/runs?after=${GATLING_TEXT.runId}&limit=1"),
                SPIKE_DROP,
                SPIKE_DROP.runId,
            )
            assertRunPage(
                api.get("/api/runs?after=${SPIKE_DROP.runId}&limit=1"),
                JMETER_XML,
                null,
            )
            assertRunPage(api.get("/api/runs?after=${JMETER_XML.runId}&limit=1"), null, null)

            val policyBytes = Files.readAllBytes(Path.of(PASS_POLICY))
            val validation = api.post("/api/policies/validate", "application/json", policyBytes)
            assertEquals(200, validation.statusCode())
            val validationJson = validation.jsonObject()
            assertEquals(setOf("valid", "policy", "sha256"), validationJson.keys)
            assertTrue(validationJson.getValue("valid").jsonPrimitive.boolean)
            assertEquals(
                Json.parseToJsonElement(Files.readString(Path.of(PASS_POLICY))),
                validationJson.getValue("policy"),
            )
            assertEquals(PASS_POLICY_SHA256, validationJson.getValue("sha256").jsonPrimitive.content)

            val submitted = api.createJob(SPIKE_DROP.runId, policyBytes)
            assertEquals(202, submitted.statusCode())
            val jobId =
                assertJobStatus(
                    submitted.jsonObject(),
                    state = "QUEUED",
                    runId = SPIKE_DROP.runId,
                    processedBytes = 0,
                    totalBytes = SPIKE_DROP.sizeBytes,
                )

            val complete = awaitComplete(api, jobId)
            val analysisId = complete.getValue("analysis_id").jsonPrimitive.content
            assertJobStatus(
                complete,
                state = "COMPLETE",
                runId = SPIKE_DROP.runId,
                processedBytes = SPIKE_DROP.sizeBytes,
                totalBytes = SPIKE_DROP.sizeBytes,
                jobId = jobId,
                analysisId = analysisId,
            )

            val result = api.get("/api/runs/${SPIKE_DROP.runId}/analyses/$analysisId/result")
            assertEquals(200, result.statusCode())
            val resultJson = result.jsonObject()
            assertEquals(
                setOf(
                    "schema_version",
                    "run_id",
                    "analysis_mode",
                    "run_validity",
                    "policy_verdict",
                    "analysis_coverage",
                    "findings",
                    "evidence",
                ),
                resultJson.keys,
            )
            assertEquals("analysis-result.v1", resultJson.getValue("schema_version").jsonPrimitive.content)
            assertEquals(SPIKE_DROP.runId, resultJson.getValue("run_id").jsonPrimitive.content)
            assertEquals("standard", resultJson.getValue("analysis_mode").jsonPrimitive.content)
            assertEquals("VALID", resultJson.getValue("run_validity").jsonPrimitive.content)
            assertEquals("FAIL", resultJson.getValue("policy_verdict").jsonPrimitive.content)
            val stored = store.readAnalysis(SPIKE_DROP.runId, analysisId) ?: fail("analysis was not stored")
            assertEquals(Files.readString(stored.path.resolve("analysis-result.json")), result.body())

            val firstPage =
                api.get(
                    "/api/runs/${SPIKE_DROP.runId}/analyses/$analysisId/buckets" +
                        "?rollup=1&from_ms=0&to_ms=2000&limit=1",
                )
            assertEquals(200, firstPage.statusCode())
            val firstPageJson = firstPage.jsonObject()
            assertEquals(setOf("buckets", "next_from_ms"), firstPageJson.keys)
            assertBucket(
                firstPageJson
                    .getValue("buckets")
                    .jsonArray
                    .single()
                    .jsonObject,
                0,
                2,
                0,
                900,
                900,
            )
            assertEquals(1_000L, firstPageJson.getValue("next_from_ms").jsonPrimitive.long)

            val secondPage =
                api.get(
                    "/api/runs/${SPIKE_DROP.runId}/analyses/$analysisId/buckets" +
                        "?rollup=1&from_ms=1000&to_ms=2000&limit=1",
                )
            assertEquals(200, secondPage.statusCode())
            val secondPageJson = secondPage.jsonObject()
            assertEquals(setOf("buckets", "next_from_ms"), secondPageJson.keys)
            assertBucket(
                secondPageJson
                    .getValue("buckets")
                    .jsonArray
                    .single()
                    .jsonObject,
                1_000,
                1,
                0,
                20,
                20,
            )
            assertEquals(null, secondPageJson.getValue("next_from_ms").jsonPrimitive.contentOrNull)
        }

    @Test
    fun `escaped-equivalent duplicate policy keys fail before job submission`() {
        val submissions = AtomicInteger()
        withServer(
            jobsFactory = {
                AnalysisJobs(1) { request, _, _ ->
                    submissions.incrementAndGet()
                    AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                }
            },
        ) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()

            val validation = api.post("/api/policies/validate", "application/json", DUPLICATE_POLICY.encodeToByteArray())
            assertDuplicatePolicy(validation)

            val job = api.createJob(input.runId, DUPLICATE_POLICY.encodeToByteArray())
            assertDuplicatePolicy(job)
            assertEquals(0, submissions.get())
            assertFalse(
                Files.exists(
                    input.path.parent.parent
                        .resolve("analyses"),
                ),
            )
        }
    }

    @Test
    fun `job rejects unknown length multipart before consuming it`() =
        withServer { _, api ->
            api.bootstrap()
            assertError(api.chunkedJob(), 411, "LENGTH_REQUIRED")
        }

    @Test
    fun `invalid diagnostic plan is rejected before job submission`() {
        val submissions = AtomicInteger()
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                submissions.incrementAndGet()
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val response = api.createJob(input.runId, diagnostics = "{}".encodeToByteArray())
            assertEquals(422, response.statusCode())
            assertEquals(
                "INVALID_DIAGNOSTICS",
                response
                    .jsonObject()
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content,
            )
            assertError(api.createJob(input.runId, diagnostics = ByteArray(1024 * 1024 + 1) { 32 }), 413)
            val plan =
                """
                {"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"${"0".repeat(64)}",
                 "anomalies":[{"id":"a","signal":{"series_id":"cpu"},"reference_window_id":"ref",
                 "window_id":"steady","direction":"increase","min_abs_delta":1,"min_duration_ms":1000}]}
                """.trimIndent()
            val missingResources = api.createJob(input.runId, diagnostics = plan.encodeToByteArray())
            assertError(missingResources, 422, "INVALID_DIAGNOSTICS", hasDetails = true)
            assertTrue(missingResources.body().contains("DIAGNOSTIC_RESOURCE_REQUIRED"))
            assertEquals(0, submissions.get())
        }
    }

    @Test
    fun `capacity plan is bounded and bound before job submission`() {
        val submissions = AtomicInteger()
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                submissions.incrementAndGet()
                check(request.capacity != null)
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":10,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":10000}]}"""
                    .encodeToByteArray()
            val hash = (validateResourceSnapshot(resources.inputStream()) as ResourceValidation.Valid).semanticSha256

            fun plan(snapshotHash: String = hash) =
                """{"schema_version":"capacity-plan.v1","load_input_sha256":"${input.sha256}","resource_snapshot_sha256":"$snapshotHash","load_axis":"rps","achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0},"generator_guard_rule_ids":[],"stages":[{"id":"steady","target":300,"from_epoch_ms":0,"to_epoch_ms":10000,"evaluation_window_id":"steady"}]}"""
                    .encodeToByteArray()

            api.bootstrap()
            assertError(api.createJob(input.runId, capacity = "{}".encodeToByteArray()), 422, "INVALID_CAPACITY_PLAN", hasDetails = true)
            assertError(api.createJob(input.runId, capacity = ByteArray(1024 * 1024 + 1) { 32 }), 413)
            val missingResources = api.createJob(input.runId, capacity = plan())
            assertError(missingResources, 422, "INVALID_CAPACITY_PLAN", hasDetails = true)
            assertTrue(missingResources.body().contains("CAPACITY_RESOURCE_REQUIRED"))
            val mismatch = api.createJob(input.runId, resources = resources, capacity = plan("0".repeat(64)))
            assertError(mismatch, 422, "INVALID_CAPACITY_PLAN", hasDetails = true)
            assertTrue(mismatch.body().contains("CAPACITY_SNAPSHOT_MISMATCH"))
            assertError(
                api.multipart(
                    "/api/jobs",
                    listOf(
                        FormPart("run_id", input.runId.encodeToByteArray()),
                        FormPart("capacity_plan", plan(), "capacity.json", "application/json"),
                        FormPart("capacity_plan", plan(), "capacity.json", "application/json"),
                    ),
                ),
                400,
                "MALFORMED_REQUEST",
            )
            assertError(
                api.multipart(
                    "/api/jobs",
                    listOf(FormPart("run_id", input.runId.encodeToByteArray()), FormPart("unexpected", byteArrayOf())),
                ),
                400,
                "MALFORMED_REQUEST",
            )
            assertEquals(FAKE_ANALYSIS_ID, api.createJob(input.runId, resources = resources, capacity = plan()).analysisId(api))
            assertEquals(1, submissions.get())
        }
    }

    @Test
    fun `trend plan is bounded and bound before job submission`() {
        val submissions = AtomicInteger()
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                submissions.incrementAndGet()
                check(request.trend != null)
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":10,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":10000}]}"""
                    .encodeToByteArray()
            val hash = (validateResourceSnapshot(resources.inputStream()) as ResourceValidation.Valid).semanticSha256

            fun plan(snapshotHash: String = hash) =
                """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$snapshotHash","checks":[{"id":"cpu-growth","series_id":"cpu","window_id":"steady","direction":"increase","min_cells":30,"magnitude_gate":{"min_slope_units_per_second":0.001,"min_split_half_shift_pct":5}}]}"""
                    .encodeToByteArray()

            api.bootstrap()
            assertError(api.createJob(input.runId, trend = "{}".encodeToByteArray()), 422, "INVALID_TREND_PLAN", hasDetails = true)
            assertError(api.createJob(input.runId, trend = ByteArray(1024 * 1024 + 1) { 32 }), 413)
            val missingResources = api.createJob(input.runId, trend = plan())
            assertError(missingResources, 422, "INVALID_TREND_PLAN", hasDetails = true)
            assertTrue(missingResources.body().contains("TREND_RESOURCE_REQUIRED"))
            val mismatch = api.createJob(input.runId, resources = resources, trend = plan("0".repeat(64)))
            assertError(mismatch, 422, "INVALID_TREND_PLAN", hasDetails = true)
            assertTrue(mismatch.body().contains("TREND_SNAPSHOT_MISMATCH"))
            val unknownSeries = api.createJob(input.runId, resources = resources, trend = unknownSeriesPlan(plan()))
            assertError(unknownSeries, 422, "INVALID_TREND_PLAN", hasDetails = true)
            assertTrue(unknownSeries.body().contains("TREND_SERIES_NOT_FOUND"))
            assertError(
                api.multipart(
                    "/api/jobs",
                    listOf(
                        FormPart("run_id", input.runId.encodeToByteArray()),
                        FormPart("trend_plan", plan(), "trend.json", "application/json"),
                        FormPart("trend_plan", plan(), "trend.json", "application/json"),
                    ),
                ),
                400,
                "MALFORMED_REQUEST",
            )
            assertEquals(FAKE_ANALYSIS_ID, api.createJob(input.runId, resources = resources, trend = plan()).analysisId(api))
            assertEquals(1, submissions.get())
        }
    }

    private fun unknownSeriesPlan(plan: ByteArray): ByteArray = plan.decodeToString().replace("\"cpu\"", "\"absent\"").encodeToByteArray()

    @Test
    fun `four job parts retain policy resources and diagnostics and reject wrong snapshot`() {
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                check(request.policy != null && request.resources != null && request.diagnostics != null)
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}],"windows":[{"id":"ref","from_epoch_ms":0,"to_epoch_ms":1000},{"id":"steady","from_epoch_ms":1000,"to_epoch_ms":2000}]}"""
                    .encodeToByteArray()
            val hash = (validateResourceSnapshot(resources.inputStream()) as ResourceValidation.Valid).semanticSha256
            val plan =
                """
                {"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"$hash",
                "anomalies":[{"id":"a","signal":{"series_id":"cpu"},"reference_window_id":"ref",
                "window_id":"steady","direction":"increase","min_abs_delta":0.1,"min_duration_ms":1000}]}
                """.trimIndent()
            api.bootstrap()
            assertEquals(
                FAKE_ANALYSIS_ID,
                api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY)), resources, plan.encodeToByteArray()).analysisId(api),
            )
            val mismatch =
                api.createJob(
                    input.runId,
                    resources = resources,
                    diagnostics = plan.replace(hash, "0".repeat(64)).encodeToByteArray(),
                )
            assertError(mismatch, 422, "INVALID_DIAGNOSTICS", hasDetails = true)
            assertTrue(mismatch.body().contains("DIAGNOSTIC_SNAPSHOT_MISMATCH"))
        }
    }

    @Test
    fun `resource snapshot upload accepts exactly 32 MiB`() {
        val submissions = AtomicInteger()
        withServer(
            jobsFactory = {
                AnalysisJobs(1) { request, _, _ ->
                    submissions.incrementAndGet()
                    check(request.resources != null)
                    AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                }
            },
        ) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val json =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}]}"""
                    .encodeToByteArray()
            val exact = ByteArray(32 * 1024 * 1024) { ' '.code.toByte() }.also { json.copyInto(it) }
            api.bootstrap()

            assertEquals(FAKE_ANALYSIS_ID, api.createJob(input.runId, resources = exact).analysisId(api))
            assertEquals(1, submissions.get())
        }
    }

    @Test
    fun `invalid resources return structured errors before job submission`() {
        val submissions = AtomicInteger()
        withServer(
            jobsFactory = {
                AnalysisJobs(1) { request, _, _ ->
                    submissions.incrementAndGet()
                    AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                }
            },
        ) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val invalid = api.createJob(input.runId, resources = "{}".encodeToByteArray())
            assertError(invalid, 422, "INVALID_RESOURCES", hasDetails = true)
            assertTrue(
                invalid
                    .jsonObject()
                    .getValue("error")
                    .jsonObject
                    .getValue("details")
                    .jsonArray
                    .isNotEmpty(),
            )
            assertError(api.createJob(input.runId, resources = "{".encodeToByteArray()), 422, "INVALID_RESOURCES", hasDetails = true)
            assertError(api.createJob(input.runId, resources = ByteArray(MAX_RESOURCE_SNAPSHOT_BYTES + 1) { 32 }), 413)
            assertEquals(0, submissions.get())
        }
    }

    @Test
    fun `analysis API paginates summaries and rejects invalid queries`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val empty = store.acceptInput(ByteArrayInputStream(GATLING_TEXT.bytes()), GATLING_TEXT.filename)

            fun publish(
                suffix: String,
                policy: String,
            ): String {
                val identity = "{\"policy_sha256\":\"$policy\",\"run_id\":\"${input.runId}\",\"suffix\":\"$suffix\"}".encodeToByteArray()
                val analysisId = sha256Hex(identity)
                store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                    Files.write(staging.resolve("identity.json"), identity)
                    Files.writeString(
                        staging.resolve("analysis-result.json"),
                        "{\"policy_verdict\":\"PASS\",\"run_validity\":\"VALID\"}",
                    )
                }
                return analysisId
            }
            val policies =
                mapOf(
                    publish("a", "a".repeat(64)) to "a".repeat(64),
                    publish("b", "b".repeat(64)) to "b".repeat(64),
                )
            val ids = policies.keys.sorted()
            api.bootstrap()

            assertTrue(
                api
                    .get("/api/runs/${empty.runId}/analyses")
                    .jsonObject()
                    .getValue("analyses")
                    .jsonArray
                    .isEmpty(),
            )
            val first = api.get("/api/runs/${input.runId}/analyses?limit=1").jsonObject()
            assertEquals(setOf("analyses", "next_after"), first.keys)
            val firstSummary =
                first
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(setOf("analysis_id", "policy_sha256", "policy_verdict", "run_validity"), firstSummary.keys)
            assertEquals(ids.first(), firstSummary.getValue("analysis_id").jsonPrimitive.content)
            assertEquals(policies.getValue(ids.first()), firstSummary.getValue("policy_sha256").jsonPrimitive.content)
            assertEquals("PASS", firstSummary.getValue("policy_verdict").jsonPrimitive.content)
            assertEquals("VALID", firstSummary.getValue("run_validity").jsonPrimitive.content)
            assertEquals(ids.first(), first.getValue("next_after").jsonPrimitive.content)
            val second = api.get("/api/runs/${input.runId}/analyses?after=${ids.first()}&limit=1").jsonObject()
            assertEquals(
                ids.last(),
                second
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_id")
                    .jsonPrimitive.content,
            )
            assertEquals(JsonNull, second.getValue("next_after"))
            assertError(api.get("/api/runs/${input.runId}/analyses?limit=0"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/${input.runId}/analyses?after=invalid"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/${input.runId}/analyses?after=${ids.first()}&after=${ids.last()}"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/jmeter_jtl_csv-${"0".repeat(64)}/analyses"), 404, "NOT_FOUND")
        }

    @Test
    fun `unknown valid run is not found for jobs and analyses`() =
        withServer { _, api ->
            api.bootstrap()
            val runId = "jmeter_jtl_csv-${"0".repeat(64)}"

            assertError(api.createJob(runId), 404, "NOT_FOUND")
            assertError(
                api.get("/api/runs/$runId/analyses/${"0".repeat(64)}/result"),
                404,
                "NOT_FOUND",
            )
        }

    @Test
    fun `job API exposes queued status BUSY and cancellation`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executions = AtomicInteger()
        try {
            withServer(
                jobsFactory = {
                    AnalysisJobs(1) { request, _, _ ->
                        executions.incrementAndGet()
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "test did not release analysis" }
                        AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                    }
                },
            ) { store, api ->
                store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
                api.bootstrap()

                val first = api.createJob(SPIKE_DROP.runId)
                assertEquals(202, first.statusCode())
                val firstId =
                    assertJobStatus(
                        first.jsonObject(),
                        "QUEUED",
                        SPIKE_DROP.runId,
                        0,
                        SPIKE_DROP.sizeBytes,
                    )
                assertTrue(started.await(5, TimeUnit.SECONDS))

                val second = api.createJob(SPIKE_DROP.runId)
                assertEquals(202, second.statusCode())
                val secondId =
                    assertJobStatus(
                        second.jsonObject(),
                        "QUEUED",
                        SPIKE_DROP.runId,
                        0,
                        SPIKE_DROP.sizeBytes,
                    )

                assertError(api.createJob(SPIKE_DROP.runId), 409, "BUSY")
                assertJobStatus(
                    api.get("/api/jobs/$secondId").jsonObject(),
                    "QUEUED",
                    SPIKE_DROP.runId,
                    0,
                    SPIKE_DROP.sizeBytes,
                    secondId,
                )

                val cancelledQueued = api.delete("/api/jobs/$secondId")
                assertEquals(200, cancelledQueued.statusCode())
                assertJobStatus(
                    cancelledQueued.jsonObject(),
                    "CANCELLED",
                    SPIKE_DROP.runId,
                    0,
                    SPIKE_DROP.sizeBytes,
                    secondId,
                )
                assertJobStatus(
                    api.get("/api/jobs/$secondId").jsonObject(),
                    "CANCELLED",
                    SPIKE_DROP.runId,
                    0,
                    SPIKE_DROP.sizeBytes,
                    secondId,
                )
                assertEquals(1, executions.get())

                val cancelledRunning = api.delete("/api/jobs/$firstId")
                assertEquals(200, cancelledRunning.statusCode())
                assertJobStatus(
                    cancelledRunning.jsonObject(),
                    "CANCELLED",
                    SPIKE_DROP.runId,
                    0,
                    SPIKE_DROP.sizeBytes,
                    firstId,
                )
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `job API lists only active jobs and rejects other queries`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(
                jobsFactory = {
                    AnalysisJobs(1) { request, _, _ ->
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "test did not release analysis" }
                        AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                    }
                },
            ) { store, api ->
                store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
                api.bootstrap()

                val empty = api.get("/api/jobs?state=active")
                assertEquals(200, empty.statusCode())
                val emptyBody = empty.jsonObject()
                assertEquals(setOf("jobs"), emptyBody.keys)
                assertTrue(emptyBody.getValue("jobs").jsonArray.isEmpty())

                val created = api.createJob(SPIKE_DROP.runId)
                assertEquals(202, created.statusCode())
                val createdBody = created.jsonObject()
                val jobId = createdBody.getValue("job_id").jsonPrimitive.content
                assertTrue(started.await(5, TimeUnit.SECONDS))

                val listed = api.get("/api/jobs?state=active")
                assertEquals(200, listed.statusCode())
                val listedJobs = listed.jsonObject().getValue("jobs").jsonArray
                val job = listedJobs.single().jsonObject
                assertJobStatus(job, "PROCESSING", SPIKE_DROP.runId, 0, SPIKE_DROP.sizeBytes, jobId)

                assertEquals(200, api.delete("/api/jobs/$jobId").statusCode())
                val drained = api.get("/api/jobs?state=active").jsonObject()
                assertTrue(drained.getValue("jobs").jsonArray.isEmpty())

                assertError(api.get("/api/jobs"), 400, "MALFORMED_REQUEST")
                assertError(api.get("/api/jobs?state=all"), 400, "MALFORMED_REQUEST")
                assertError(api.get("/api/jobs?state=active&limit=1"), 400, "MALFORMED_REQUEST")
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `bucket API caps pages and rejects invalid ranges`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val identity = """{"run_id":"${input.runId}"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                val histogram = PackedHistogram(1, 86_400_000, 3).apply { recordValue(1) }
                val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
                val encoded = Base64.getEncoder().encodeToString(buffer.array().copyOf(histogram.encodeIntoCompressedByteBuffer(buffer)))
                val rows =
                    (0..500).joinToString(separator = "", postfix = "") { index ->
                        "{\"bucket_start_ms\":${index * 1_000L},\"error_count\":0,\"hdr_v2_base64\":\"$encoded\"," +
                            "\"max_latency_ms\":1,\"sample_count\":1}\n"
                    }
                Files.writeString(staging.resolve("normalized-1s.ndjson"), rows)
            }
            api.bootstrap()

            val page =
                api
                    .get(
                        "/api/runs/${input.runId}/analyses/$analysisId/buckets?rollup=1&limit=500",
                    ).jsonObject()
            assertEquals(500, page.getValue("buckets").jsonArray.size)
            assertEquals(500_000L, page.getValue("next_from_ms").jsonPrimitive.long)

            listOf(
                "?rollup=2",
                "?rollup=1&from_ms=5&to_ms=5",
                "?rollup=1&limit=501",
            ).forEach { query ->
                assertError(
                    api.get("/api/runs/${input.runId}/analyses/$analysisId/buckets$query"),
                    400,
                )
            }
        }

    @Test
    fun `bucket API caps p95 at the stored max for any precision and leaves stored rows unchanged`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            // A saved analysis from before the metrics module version 2: same identity, older module version.
            val identity =
                analysisIdentity(input, null, EngineConfig())
                    .decodeToString()
                    .replace("{\"id\":\"metrics\",\"version\":\"2\"}", "{\"id\":\"metrics\",\"version\":\"1\"}")
                    .encodeToByteArray()
            assertTrue(identity.decodeToString().contains("{\"id\":\"metrics\",\"version\":\"1\"}"))
            val analysisId = sha256Hex(identity)

            fun encoded(
                digits: Int,
                values: List<Long>,
            ): String {
                val histogram = PackedHistogram(1, 86_400_000, digits).apply { values.forEach { recordValue(it) } }
                val buffer = ByteBuffer.allocate(histogram.neededByteBufferCapacity)
                return Base64.getEncoder().encodeToString(buffer.array().copyOf(histogram.encodeIntoCompressedByteBuffer(buffer)))
            }

            // Rows: all precisions round 262144 ms upward, then precision 3 retains rounding below a far maximum.
            val cases =
                listOf(
                    Triple(3, List(100) { 262_144L }, 262_144L),
                    Triple(4, List(100) { 262_144L }, 262_144L),
                    Triple(5, List(100) { 262_144L }, 262_144L),
                    Triple(3, List(100) { 60_000L } + listOf(70_000L), 70_000L),
                )
            val rows =
                cases
                    .mapIndexed { index, (digits, values, max) ->
                        "{\"bucket_start_ms\":${index * 1_000L},\"error_count\":0,\"hdr_v2_base64\":\"${encoded(digits, values)}\"," +
                            "\"max_latency_ms\":$max,\"sample_count\":${values.size}}\n"
                    }.joinToString("")
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.writeString(staging.resolve("normalized-1s.ndjson"), rows)
            }
            api.bootstrap()

            val page =
                api
                    .get("/api/runs/${input.runId}/analyses/$analysisId/buckets?rollup=1&limit=10")
                    .jsonObject()
                    .getValue("buckets")
                    .jsonArray
                    .map { it.jsonObject }

            assertEquals(cases.size, page.size)
            cases.take(3).forEachIndexed { index, (digits, values, max) ->
                val raw = PackedHistogram(1, 86_400_000, digits).apply { values.forEach { recordValue(it) } }.getValueAtPercentile(95.0)
                assertTrue(raw > max, "digits $digits")
                assertEquals(minOf(raw, max), page[index].getValue("p95_latency_ms").jsonPrimitive.long, "digits $digits")
            }
            assertEquals(60_031L, page[3].getValue("p95_latency_ms").jsonPrimitive.long)
            page.forEach { bucket ->
                assertTrue(bucket.getValue("p95_latency_ms").jsonPrimitive.long <= bucket.getValue("max_latency_ms").jsonPrimitive.long)
            }
            val stored = checkNotNull(store.readAnalysis(input.runId, analysisId))
            assertEquals(rows, Files.readString(stored.path.resolve("normalized-1s.ndjson")))
        }

    @Test
    fun `report downloads preserve result bytes and reject invalid queries without new analyses`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val submitted = api.createJob(input.runId)
            val complete =
                awaitComplete(
                    api,
                    submitted
                        .jsonObject()
                        .getValue("job_id")
                        .jsonPrimitive.content,
                )
            val analysisId = complete.getValue("analysis_id").jsonPrimitive.content
            val stored = checkNotNull(store.readAnalysis(input.runId, analysisId))
            val resultBytes = Files.readAllBytes(stored.path.resolve("analysis-result.json"))
            val before = Files.list(stored.path.parent).use { it.map { path -> path.fileName.toString() }.sorted().toList() }
            val base = "/api/runs/${input.runId}/analyses/$analysisId/report"

            for (format in listOf("json", "html", "asciidoc")) {
                val response = api.get("$base?format=$format")
                assertEquals(200, response.statusCode())
                assertEquals(
                    "attachment; filename=\"lt-verdict-$analysisId.${if (format == "asciidoc") "adoc" else format}\"",
                    response.headers().firstValue("Content-Disposition").orElseThrow(),
                )
                val expected =
                    when (format) {
                        "json" -> resultBytes
                        "html" -> renderHtmlReport(resultBytes, analysisId)
                        else -> renderAsciiDocReport(resultBytes, analysisId)
                    }
                assertEquals(expected.decodeToString(), response.body())
                assertTrue(
                    response.headers().firstValue("Content-Type").orElseThrow().startsWith(
                        when (format) {
                            "json" -> "application/json"
                            "html" -> "text/html"
                            else -> "text/plain"
                        },
                    ),
                )
            }
            for (query in listOf("", "?format=pdf", "?format=json&format=html", "?format=json&path=identity.json")) {
                assertError(api.get(base + query), 400, "MALFORMED_REQUEST")
            }
            assertError(api.get("/api/runs/${input.runId}/analyses/${"0".repeat(64)}/report?format=json"), 404, "NOT_FOUND")
            assertEquals(resultBytes.toList(), Files.readAllBytes(stored.path.resolve("analysis-result.json")).toList())
            assertEquals(before, Files.list(stored.path.parent).use { it.map { path -> path.fileName.toString() }.sorted().toList() })
        }

    @Test
    fun `bounded upload copy stops reading one byte past the limit`() {
        val read = AtomicLong()
        val endless =
            object : InputStream() {
                override fun read(): Int = error("single-byte read is not expected")

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    buffer.fill('x'.code.toByte(), offset, offset + length)
                    read.addAndGet(length.toLong())
                    return length
                }
            }
        val sink = ByteArrayOutputStream()

        val failure = assertThrows(IllegalArgumentException::class.java) { copyBoundedUpload(endless, sink, 100_000L) }

        assertEquals("RESOURCE_LIMIT_EXCEEDED", failure.message)
        assertEquals(100_001L, read.get())
        assertTrue(sink.size() <= 100_000)
    }

    @Test
    fun `input upload at the limit succeeds with and without Content-Length`() {
        withServer(uploadLimitBytes = SPIKE_DROP.sizeBytes) { _, api ->
            api.bootstrap()
            assertEquals(201, api.upload(SPIKE_DROP).statusCode())
            assertEquals(201, api.uploadChunked(SPIKE_DROP.filename, SPIKE_DROP.bytes()).statusCode())
        }
    }

    @Test
    fun `input upload over the limit is rejected with and without Content-Length and leaves no residue`() {
        val before = uploadTemporaryFiles()
        withServer(uploadLimitBytes = SPIKE_DROP.sizeBytes) { _, api ->
            api.bootstrap()
            val oneOver = SPIKE_DROP.bytes() + 'x'.code.toByte()
            val farOver = SPIKE_DROP.bytes() + ByteArray(65_536) { 'x'.code.toByte() }
            assertError(api.uploadChunked(SPIKE_DROP.filename, farOver), 413, "RESOURCE_LIMIT_EXCEEDED")
            assertError(api.uploadChunked(SPIKE_DROP.filename, oneOver), 413, "RESOURCE_LIMIT_EXCEEDED")
            assertError(api.upload(SPIKE_DROP.copy(inlineBytes = oneOver)), 413, "RESOURCE_LIMIT_EXCEEDED")
        }
        assertEquals(before, uploadTemporaryFiles())
        Files.newDirectoryStream(tempDir, "data-*").use { directories ->
            directories.forEach { data ->
                Files.newDirectoryStream(data.resolve(".staging")).use { staged -> assertEquals(emptyList<Path>(), staged.toList()) }
            }
        }
    }

    private fun uploadTemporaryFiles(): Set<Path> =
        Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")), "ltv-upload-*.tmp").use { it.toSet() }

    private fun withServer(
        jobsFactory: (RunBundleStore) -> AnalysisJobs = { store ->
            val service = AnalysisService(store, EngineConfig())
            AnalysisJobs(1, service::analyze)
        },
        sourceProfiles: List<SourceProfile> = emptyList(),
        postgresProfiles: List<PostgresProfile> = emptyList(),
        adviceRunner: AdvisoryRunner? = null,
        uploadLimitBytes: Long = 4_294_967_296L,
        block: (RunBundleStore, ApiClient) -> Unit,
    ) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            jobsFactory(store).use { jobs ->
                val adviceService = adviceRunner?.let { AdvisoryAiService(store, AiAdviceStore(directory, store), it) }
                adviceService?.let { AdvisoryAiJobs(it) }.use { adviceJobs ->
                    startLocalServer(
                        LocalApiContext(
                            store,
                            jobs,
                            sourceProfiles,
                            postgresProfiles,
                            adviceService,
                            adviceJobs,
                            uploadLimitBytes = uploadLimitBytes,
                        ),
                        openBrowser = false,
                    ).use { server ->
                        block(store, ApiClient(server.origin))
                    }
                }
            }
        }
    }

    private fun awaitComplete(
        api: ApiClient,
        jobId: String,
    ): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val response = api.get("/api/jobs/$jobId")
            assertEquals(200, response.statusCode())
            val status = response.jsonObject()
            when (status.getValue("state").jsonPrimitive.content) {
                "COMPLETE" -> return status
                "FAILED", "CANCELLED" -> fail("job ended as ${status.getValue("state")}: $status")
            }
            LockSupport.parkNanos(1_000_000)
        }
        return fail("job did not complete: $jobId")
    }

    private fun HttpResponse<String>.errorCode(): String =
        jsonObject()
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun HttpResponse<String>.analysisId(api: ApiClient): String =
        awaitComplete(api, jsonObject().getValue("job_id").jsonPrimitive.content)
            .getValue("analysis_id")
            .jsonPrimitive.content

    private fun awaitFailed(
        api: ApiClient,
        jobId: String,
    ): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val response = api.get("/api/jobs/$jobId")
            assertEquals(200, response.statusCode())
            val status = response.jsonObject()
            when (status.getValue("state").jsonPrimitive.content) {
                "FAILED" -> return status
                "COMPLETE", "CANCELLED" -> fail("job ended as ${status.getValue("state")}: $status")
            }
            LockSupport.parkNanos(1_000_000)
        }
        return fail("job did not fail: $jobId")
    }

    private fun assertRunPage(
        response: HttpResponse<String>,
        expected: RunFixture?,
        nextAfter: String?,
    ) {
        assertEquals(200, response.statusCode())
        val body = response.jsonObject()
        assertEquals(setOf("runs", "next_after"), body.keys)
        val runs = body.getValue("runs").jsonArray
        if (expected == null) {
            assertTrue(runs.isEmpty())
        } else {
            assertRunSummary(runs.single().jsonObject, expected)
        }
        assertEquals(nextAfter, body.getValue("next_after").jsonPrimitive.contentOrNull)
    }

    private fun assertRunSummary(
        actual: JsonObject,
        expected: RunFixture,
    ) {
        assertEquals(setOf("run_id", "source_type", "sha256", "size_bytes", "original_filename"), actual.keys)
        assertEquals(expected.runId, actual.getValue("run_id").jsonPrimitive.content)
        assertEquals(expected.sourceType, actual.getValue("source_type").jsonPrimitive.content)
        assertEquals(expected.sha256, actual.getValue("sha256").jsonPrimitive.content)
        assertEquals(expected.sizeBytes, actual.getValue("size_bytes").jsonPrimitive.long)
        assertEquals(expected.filename, actual.getValue("original_filename").jsonPrimitive.content)
    }

    private fun assertJobStatus(
        actual: JsonObject,
        state: String,
        runId: String,
        processedBytes: Long,
        totalBytes: Long,
        jobId: String? = null,
        analysisId: String? = null,
    ): String {
        assertEquals(
            setOf("job_id", "state", "processed_bytes", "total_bytes", "run_id", "analysis_id", "diagnostic"),
            actual.keys,
        )
        val actualJobId = actual.getValue("job_id").jsonPrimitive.content
        UUID.fromString(actualJobId)
        if (jobId != null) assertEquals(jobId, actualJobId)
        assertEquals(state, actual.getValue("state").jsonPrimitive.content)
        assertEquals(processedBytes, actual.getValue("processed_bytes").jsonPrimitive.long)
        assertEquals(totalBytes, actual.getValue("total_bytes").jsonPrimitive.long)
        assertEquals(runId, actual.getValue("run_id").jsonPrimitive.content)
        assertEquals(analysisId, actual.getValue("analysis_id").jsonPrimitive.contentOrNull)
        assertEquals(JsonNull, actual.getValue("diagnostic"))
        return actualJobId
    }

    private fun assertBucket(
        actual: JsonObject,
        startMillis: Long,
        sampleCount: Long,
        errorCount: Long,
        maxLatencyMillis: Long,
        p95LatencyMillis: Long,
    ) {
        assertEquals(
            setOf("bucket_start_ms", "sample_count", "error_count", "max_latency_ms", "p95_latency_ms", "hdr_v2_base64"),
            actual.keys,
        )
        assertEquals(startMillis, actual.getValue("bucket_start_ms").jsonPrimitive.long)
        assertEquals(sampleCount, actual.getValue("sample_count").jsonPrimitive.long)
        assertEquals(errorCount, actual.getValue("error_count").jsonPrimitive.long)
        assertEquals(maxLatencyMillis, actual.getValue("max_latency_ms").jsonPrimitive.long)
        assertEquals(p95LatencyMillis, actual.getValue("p95_latency_ms").jsonPrimitive.long)
        assertTrue(
            actual
                .getValue("hdr_v2_base64")
                .jsonPrimitive.content
                .isNotEmpty(),
        )
    }

    private fun assertDuplicatePolicy(response: HttpResponse<String>) {
        assertEquals(422, response.statusCode())
        val body = response.jsonObject()
        assertEquals(setOf("valid", "errors"), body.keys)
        assertFalse(body.getValue("valid").jsonPrimitive.boolean)
        val error =
            body
                .getValue("errors")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(setOf("code", "json_pointer", "message"), error.keys)
        assertEquals("DUPLICATE_OBJECT_KEY", error.getValue("code").jsonPrimitive.content)
        assertEquals("/policy_id", error.getValue("json_pointer").jsonPrimitive.content)
        assertEquals("duplicate object key", error.getValue("message").jsonPrimitive.content)
    }

    private fun assertError(
        response: HttpResponse<String>,
        status: Int,
        code: String? = null,
        hasDetails: Boolean = false,
    ) {
        assertEquals(status, response.statusCode())
        val body = response.jsonObject()
        assertEquals(setOf("error"), body.keys)
        val error = body.getValue("error").jsonObject
        assertEquals(setOf("code", "message", "details"), error.keys)
        val actualCode = error.getValue("code").jsonPrimitive.content
        if (code == null) assertTrue(actualCode.isNotEmpty()) else assertEquals(code, actualCode)
        assertTrue(
            error
                .getValue("message")
                .jsonPrimitive.content
                .isNotEmpty(),
        )
        assertEquals(hasDetails, error.getValue("details").jsonArray.isNotEmpty())
    }

    private data class RunFixture(
        val path: String,
        val filename: String,
        val sourceType: String,
        val sha256: String,
        val sizeBytes: Long,
        val inlineBytes: ByteArray? = null,
    ) {
        val runId = "$sourceType-$sha256"

        fun bytes(): ByteArray = inlineBytes ?: Files.readAllBytes(Path.of(path))
    }

    private class ApiClient(
        private val origin: String,
    ) {
        private val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
        private lateinit var cookie: String
        private lateinit var csrf: String

        fun bootstrap(): HttpResponse<String> {
            val response = get("/api/bootstrap")
            val body = response.jsonObject()
            cookie =
                response
                    .headers()
                    .firstValue("set-cookie")
                    .orElseThrow()
                    .substringBefore(';')
            csrf = body.getValue("csrf_token").jsonPrimitive.content
            return response
        }

        fun upload(fixture: RunFixture): HttpResponse<String> =
            multipart(
                "/api/inputs",
                listOf(
                    FormPart(
                        name = "file",
                        bytes = fixture.bytes(),
                        filename = fixture.filename,
                        contentType = "application/octet-stream",
                    ),
                ),
            )

        fun uploadChunked(
            filename: String,
            bytes: ByteArray,
        ): HttpResponse<String> {
            val boundary = "ltv-test-boundary"
            val body = ByteArrayOutputStream()
            body.writeUtf8("--$boundary\r\n")
            body.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
            body.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
            body.write(bytes)
            body.writeUtf8("\r\n--$boundary--\r\n")
            // ofInputStream has no known length, so the request is sent chunked without Content-Length.
            return send(
                authenticated(request("/api/inputs"))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { body.toByteArray().inputStream() }),
            )
        }

        fun createJob(
            runId: String,
            policy: ByteArray? = null,
            resources: ByteArray? = null,
            diagnostics: ByteArray? = null,
            source: ByteArray? = null,
            sourceContext: ByteArray? = null,
            sourceContexts: List<ByteArray> = emptyList(),
            postgresPre: ByteArray? = null,
            postgresPost: ByteArray? = null,
            pgProfileHtml: ByteArray? = null,
            capacity: ByteArray? = null,
            trend: ByteArray? = null,
        ): HttpResponse<String> =
            multipart(
                "/api/jobs",
                buildList {
                    add(FormPart("run_id", runId.encodeToByteArray()))
                    if (policy != null) add(FormPart("policy", policy, "policy.json", "application/json"))
                    if (resources != null) add(FormPart("resource_snapshot", resources, "resources.json", "application/json"))
                    if (diagnostics != null) add(FormPart("correlation_plan", diagnostics, "correlation.json", "application/json"))
                    if (source != null) add(FormPart("source_request", source, "source.json", "application/json"))
                    if (sourceContext != null) add(FormPart("source_context", sourceContext, "context.json", "application/json"))
                    sourceContexts.forEach { add(FormPart("source_context", it, "context.json", "application/json")) }
                    if (postgresPre != null) add(FormPart("postgres_pre", postgresPre, "pre.json", "application/json"))
                    if (postgresPost != null) add(FormPart("postgres_post", postgresPost, "post.json", "application/json"))
                    if (pgProfileHtml != null) add(FormPart("pg_profile_html", pgProfileHtml, "report.html", "text/html"))
                    if (capacity != null) add(FormPart("capacity_plan", capacity, "capacity.json", "application/json"))
                    if (trend != null) add(FormPart("trend_plan", trend, "trend.json", "application/json"))
                },
            )

        fun get(path: String): HttpResponse<String> = send(request(path).GET())

        fun post(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(
                authenticated(request(path))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)),
            )

        fun delete(path: String): HttpResponse<String> = send(authenticated(request(path)).DELETE())

        fun chunkedJob(): HttpResponse<String> =
            send(
                authenticated(request("/api/jobs"))
                    .header("Content-Type", "multipart/form-data; boundary=ltv-test")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { "--ltv-test--\r\n".byteInputStream() }),
            )

        fun postUnauthenticated(
            path: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun multipart(
            path: String,
            parts: List<FormPart>,
        ): HttpResponse<String> {
            val boundary = "ltv-test-boundary"
            val body = ByteArrayOutputStream()
            parts.forEach { part ->
                body.writeUtf8("--$boundary\r\n")
                body.writeUtf8("Content-Disposition: form-data; name=\"${part.name}\"")
                part.filename?.let { body.writeUtf8("; filename=\"$it\"") }
                body.writeUtf8("\r\n")
                part.contentType?.let { body.writeUtf8("Content-Type: $it\r\n") }
                body.writeUtf8("\r\n")
                body.write(part.bytes)
                body.writeUtf8("\r\n")
            }
            body.writeUtf8("--$boundary--\r\n")
            return post(path, "multipart/form-data; boundary=$boundary", body.toByteArray())
        }

        private fun request(path: String): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI.create("$origin$path"))
                .timeout(Duration.ofSeconds(10))
                .apply {
                    if (::cookie.isInitialized) header("Cookie", cookie)
                }

        private fun authenticated(request: HttpRequest.Builder): HttpRequest.Builder =
            request
                .header("Origin", origin)
                .header("Cookie", cookie)
                .header("X-LTV-CSRF", csrf)

        private fun send(request: HttpRequest.Builder): HttpResponse<String> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    }

    private data class FormPart(
        val name: String,
        val bytes: ByteArray,
        val filename: String? = null,
        val contentType: String? = null,
    )

    private companion object {
        const val PASS_POLICY = "fixtures/slice1/policies/pass.json"
        const val PASS_POLICY_SHA256 = "f35d1e8a110bca3d1457e780e5e32751fc91467e9a29d0ced7808822c118aa2b"
        const val FAKE_ANALYSIS_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DUPLICATE_POLICY =
            """{"schema_version":"policy.v1","policy\u005fid":"first","policy_id":"second","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}"""

        val SPIKE_DROP =
            RunFixture(
                "fixtures/slice1/normalization/spike-drop.jtl",
                "spike-drop.jtl",
                "jmeter_jtl_csv",
                "eac060a38a46fbfa96d26295225ed8665ab445261028485d95043a70c7f97ae0",
                382,
                (
                    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                        "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
                        "1767225601000,20,steady,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n" +
                        "1767225600000,900,spike,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n" +
                        "1767225600010,850,spike,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n"
                ).encodeToByteArray(),
            )
        val GATLING_TEXT =
            RunFixture(
                "fixtures/slice1/gatling/text-3.12.0/simulation.log",
                "simulation.log",
                "gatling_text",
                "d0bbdc54e8dd4c7adf1c0a7d0558f276b4be57dfa4c384faadf8b761654dede6",
                459,
            )
        val JMETER_XML =
            RunFixture(
                "fixtures/slice1/jmeter/xml-5.6.3/input.xml",
                "input.xml",
                "jmeter_jtl_xml",
                "e01fcf204803ad94f66f5bd5e96c7750a26922abfdc929489b6a5fc44627d82e",
                1_349,
            )
    }
}

private fun HttpResponse<String>.jsonObject(): JsonObject = Json.parseToJsonElement(body()).jsonObject

private fun ByteArrayOutputStream.writeUtf8(value: String) {
    write(value.toByteArray(UTF_8))
}

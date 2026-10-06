package io.ltverdict.web

import io.ltverdict.ai.AdviceUnavailableReason
import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AdvisoryEvidence
import io.ltverdict.ai.AdvisoryRunner
import io.ltverdict.ai.AiAdviceStore
import io.ltverdict.ai.AiModel
import io.ltverdict.ai.AiModelsConfig
import io.ltverdict.ai.RunnerOutcome
import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.MAX_RELEASE_ANALYSES
import io.ltverdict.core.MAX_RELEASE_NOTES_BYTES
import io.ltverdict.core.MAX_RELEASE_TEXT_BYTES
import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES
import io.ltverdict.core.PodViewValidation
import io.ltverdict.core.RELEASE_ID
import io.ltverdict.core.RELEASE_PROFILE_FIELDS
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.analysisIdentity
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.podViewTestJson
import io.ltverdict.core.releaseId
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validatePodView
import io.ltverdict.core.validateRelease
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
import io.ltverdict.storage.MAX_RELEASES
import io.ltverdict.storage.MAX_VERIFIED_RESULT_BYTES
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
import java.io.RandomAccessFile
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

    private fun releaseProfile(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(RELEASE_PROFILE_FIELDS.associateWith { name -> pairs.toMap()[name]?.let(::JsonPrimitive) ?: JsonNull })

    private fun analyticsPath(analysis: Pair<String, String>) = "/api/runs/${analysis.first}/analyses/${analysis.second}/analytics"

    private fun JsonObject.dynamicsRow(analysis: Pair<String, String>): JsonObject =
        getValue("dynamics")
            .jsonObject
            .getValue("rows")
            .jsonArray
            .map { it.jsonObject }
            .single {
                it
                    .getValue("reference")
                    .jsonObject
                    .getValue("analysis_id")
                    .jsonPrimitive.content == analysis.second
            }

    @Test
    fun `dynamics rows carry the release label and profile of registered analyses only`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120, 130))
            val marker = "NOTE-MARKER-4711"
            val markup = "<img src=x onerror=alert(1)>"
            val paced = releaseProfile("pacing" to "10 s", "load_model" to "open")
            val notes = JsonPrimitive(marker)
            assertEquals(
                201,
                api
                    .createRelease(
                        releaseBody(runs[0].first, listOf(runs[0].second), label = "2.4.1", profile = paced, notes = notes),
                    ).statusCode(),
            )
            assertEquals(201, api.createRelease(releaseBody(runs[1].first, listOf(runs[1].second), label = markup)).statusCode())
            assertEquals(
                201,
                api.createRelease(releaseBody(runs[2].first, listOf(runs[2].second), label = "a|b", profile = paced)).statusCode(),
            )

            val body = api.get(analyticsPath(runs[3])).body()
            val analytics = Json.parseToJsonElement(body).jsonObject
            val registered = analytics.dynamicsRow(runs[0])
            assertEquals(JsonPrimitive("2.4.1"), registered.getValue("application_version"))
            // the six fixed fields in the order of RELEASE_PROFILE_FIELDS, only the declared ones
            assertEquals(JsonPrimitive("load_model=open; pacing=10 s"), registered.getValue("load_profile"))
            // a release without a declared profile gives the label and no profile
            assertEquals(JsonPrimitive(markup), analytics.dynamicsRow(runs[1]).getValue("application_version"))
            assertEquals(JsonNull, analytics.dynamicsRow(runs[1]).getValue("load_profile"))
            // an unregistered analysis (here the current one) stays empty
            assertEquals(JsonNull, analytics.dynamicsRow(runs[3]).getValue("application_version"))
            assertEquals(JsonNull, analytics.dynamicsRow(runs[3]).getValue("load_profile"))
            analytics.getValue("dynamics").jsonObject.getValue("rows").jsonArray.forEach {
                assertEquals(JsonNull, it.jsonObject.getValue("jenkins_build"))
            }
            // a registered current analysis carries its own release fields too
            val own = Json.parseToJsonElement(api.get(analyticsPath(runs[0])).body()).jsonObject.dynamicsRow(runs[0])
            assertEquals(JsonPrimitive("2.4.1"), own.getValue("application_version"))
            assertEquals(JsonPrimitive("load_model=open; pacing=10 s"), own.getValue("load_profile"))

            // the notes are never copied; the label is escaped in every export format
            assertFalse(body.contains(marker))
            listOf("html", "asciidoc", "confluence").forEach { format ->
                val export = api.get(analyticsPath(runs[3]) + "?format=$format").body()
                assertFalse(export.contains(marker), format)
                // AsciiDoc keeps the text as is inside a specialchars block; the markup formats must not hold it raw
                if (format != "asciidoc") assertFalse(export.contains("<img"), format)
                assertTrue(export.contains("load_model=open; pacing=10 s"), format)
            }
            assertTrue(api.get(analyticsPath(runs[3]) + "?format=html").body().contains("&lt;img src=x onerror=alert(1)&gt;"))
            assertTrue(api.get(analyticsPath(runs[3]) + "?format=confluence").body().contains("&lt;img src=x onerror=alert(1)&gt;"))
            val asciiDoc = api.get(analyticsPath(runs[3]) + "?format=asciidoc").body()
            assertTrue(asciiDoc.contains("[subs=specialchars]"))
            assertTrue(asciiDoc.contains("a\\|b"))
        }

    @Test
    fun `dynamics ignore an ambiguous release lookup and a corrupt registry`() {
        val root = tempDir.resolve("analytics-registry")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            val paced = releaseProfile("pacing" to "10 s")
            val id = api.createRelease(releaseBody(runs[0].first, listOf(runs[0].second), label = "2.4.1", profile = paced)).newReleaseId()

            fun label(): JsonElement =
                Json
                    .parseToJsonElement(
                        api.get(analyticsPath(runs[1])).body(),
                    ).jsonObject
                    .dynamicsRow(runs[0])
                    .getValue("application_version")
            assertEquals(JsonPrimitive("2.4.1"), label())

            // a hand-copied record names the same analysis: no label of either file is shown
            val copyId = releaseId(1_767_225_600_000L, "ffffffff")
            val stored = Json.parseToJsonElement(Files.readString(root.resolve("releases/$id.json"))).jsonObject
            Files.write(
                root.resolve("releases/$copyId.json"),
                canonicalJson(
                    validateRelease(
                        JsonObject(
                            stored + ("release_id" to JsonPrimitive(copyId)),
                        ),
                    ),
                ),
            )
            assertEquals(200, api.get(analyticsPath(runs[1])).statusCode())
            assertEquals(JsonNull, label())
            Files.delete(root.resolve("releases/$copyId.json"))
            assertEquals(JsonPrimitive("2.4.1"), label())

            // a registry the API refuses to list does not break the analytics
            seedReleaseFiles(root, MAX_RELEASES)
            Files.writeString(root.resolve("releases/stray.txt"), "x")
            assertError(api.get("/api/releases"), 500, "CORRUPT_RELEASE_REGISTRY")
            assertEquals(200, api.get(analyticsPath(runs[1])).statusCode())
            assertEquals(JsonNull, label())
        }
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
    fun `advice request chooses a model from the configuration and the job status names it`() {
        val config =
            AiModelsConfig(
                endpointUrl = "https://gateway.internal.example/v1/chat/completions",
                endpointLabel = "Internal gateway",
                allowInsecureHttp = false,
                defaultModel = "qwen3.8-max",
                models = listOf(AiModel("qwen3.8-max", "Qwen 3.8 Max"), AiModel("org/deepseek:2", "DeepSeek")),
            )
        val requested = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val runner =
            object : AdvisoryRunner {
                override fun invoke(evidence: AdvisoryEvidence): RunnerOutcome = invoke(evidence, null)

                override fun invoke(
                    evidence: AdvisoryEvidence,
                    modelId: String?,
                ): RunnerOutcome {
                    requested += modelId
                    return RunnerOutcome.Unavailable(AdviceUnavailableReason.OS_ISOLATION_NOT_PROVEN)
                }
            }
        withServer(adviceRunner = runner, aiModels = config) { store, api ->
            api.bootstrap()
            val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
            val id = api.createJob(input.runId).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"

            fun submit(body: String): HttpResponse<String> = api.post("$base/advice", "application/json", body.encodeToByteArray())

            fun awaitUnavailable(response: HttpResponse<String>): JsonObject {
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
                return status
            }

            // No model in the body: the default model of the configuration is the selected one.
            val byDefault = submit("{}")
            assertEquals(202, byDefault.statusCode())
            assertEquals(
                "qwen3.8-max",
                byDefault
                    .jsonObject()
                    .getValue("model_id")
                    .jsonPrimitive.content,
            )
            val defaultStatus = awaitUnavailable(byDefault)
            assertEquals("qwen3.8-max", defaultStatus.getValue("model_id").jsonPrimitive.content)

            // A slug of the configuration is accepted and shown in the status of an unavailable job too.
            val chosen = submit("""{"confirm_external_transfer":true,"model_id":"org/deepseek:2"}""")
            assertEquals(202, chosen.statusCode())
            assertEquals(
                "org/deepseek:2",
                chosen
                    .jsonObject()
                    .getValue("model_id")
                    .jsonPrimitive.content,
            )
            assertEquals("org/deepseek:2", awaitUnavailable(chosen).getValue("model_id").jsonPrimitive.content)
            assertEquals(
                "org/deepseek:2",
                api
                    .get("$base/advice")
                    .jsonObject()
                    .getValue("job")
                    .jsonObject
                    .getValue("model_id")
                    .jsonPrimitive.content,
            )
            assertEquals(listOf<String?>("qwen3.8-max", "org/deepseek:2"), requested.toList())

            // A slug outside the configuration, with a well formed shape, is a 400 and never reaches the runner.
            for (body in listOf(
                """{"model_id":"org/unknown:1"}""",
                """{"model_id":"QWEN3.8-MAX"}""",
                """{"model_id":"qwen3.8-max","confirm_external_transfer":false}""",
            )) {
                val rejected = submit(body)
                assertEquals(400, rejected.statusCode(), body)
                assertEquals("MALFORMED_REQUEST", rejected.errorCode())
            }
            assertEquals(2, requested.size)
        }
    }

    @Test
    fun `advice request with a model is refused when no model configuration is available`() =
        withServer(
            adviceRunner = AdvisoryRunner { RunnerOutcome.Unavailable(AdviceUnavailableReason.MODEL_CONFIG_INVALID) },
        ) { store, api ->
            api.bootstrap()
            val input = store.acceptInput(SPIKE_DROP.bytes().inputStream(), "spike-drop.jtl")
            val id = api.createJob(input.runId).analysisId(api)
            val base = "/api/runs/${input.runId}/analyses/$id"

            val withModel = api.post("$base/advice", "application/json", """{"model_id":"deepseek-v4-flash-0731"}""".encodeToByteArray())
            assertEquals(400, withModel.statusCode())
            assertEquals("MALFORMED_REQUEST", withModel.errorCode())

            val plain = api.post("$base/advice", "application/json", "{}".encodeToByteArray())
            assertEquals(202, plain.statusCode())
            assertEquals(JsonNull, plain.jsonObject().getValue("model_id"))
        }

    @Test
    fun `bootstrap keeps its fields and reports no advisory AI when none is configured`() =
        withServer { _, api ->
            val body = api.bootstrap().jsonObject()

            assertEquals(setOf("csrf_token", "max_upload_bytes", "advisory_ai"), body.keys)
            assertEquals(JsonNull, body.getValue("advisory_ai"))
            assertEquals(4_294_967_296L, body.getValue("max_upload_bytes").jsonPrimitive.long)
        }

    @Test
    fun `bootstrap exposes the model configuration without the endpoint address`() {
        val config =
            AiModelsConfig(
                endpointUrl = "https://gateway.internal.example/v1/chat/completions",
                endpointLabel = "Internal gateway",
                allowInsecureHttp = false,
                defaultModel = "qwen3.8-max",
                models = listOf(AiModel("qwen3.8-max", "Qwen 3.8 Max"), AiModel("deepseek-v4-flash-0731", "DeepSeek")),
            )
        withServer(aiModels = config) { _, api ->
            val response = api.bootstrap()
            val advisory = response.jsonObject().getValue("advisory_ai").jsonObject

            assertEquals("qwen3.8-max", advisory.getValue("default_model_id").jsonPrimitive.content)
            assertEquals(setOf("default_model_id", "models"), advisory.keys)
            assertFalse(response.body().contains("Internal gateway"))
            val models = advisory.getValue("models").jsonArray.map { it.jsonObject }
            assertEquals(listOf("qwen3.8-max", "deepseek-v4-flash-0731"), models.map { it.getValue("id").jsonPrimitive.content })
            assertEquals(listOf(false, false), models.map { it.getValue("measured").jsonPrimitive.boolean })
            assertFalse(response.body().contains("gateway.internal"))
            assertFalse(response.body().contains("https://"))
        }
        withServer(aiModels = AiModelsConfig.BUILT_IN) { _, api ->
            val models =
                api
                    .bootstrap()
                    .jsonObject()
                    .getValue("advisory_ai")
                    .jsonObject
                    .getValue("models")
                    .jsonArray
            assertTrue(
                models
                    .single()
                    .jsonObject
                    .getValue("measured")
                    .jsonPrimitive.boolean,
            )
        }
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
                    """{"model_id":1}""",
                    """{"model_id":null}""",
                    """{"model_id":""}""",
                    """{"model_id":"a;b"}""",
                    """{"model_id":"a..b"}""",
                    """{"model_id":"a//b"}""",
                    """{"model_id":"a b"}""",
                    """{"model_id":"-a"}""",
                    """{"model_id":"a${'$'}(id)"}""",
                    """{"model_id":"${"a".repeat(129)}"}""",
                    """{"model_id":"a","model_id":"b"}""",
                    """{"model_id":"a","model_id":"a"}""",
                    """{"model${92.toChar()}u005fid":"a","model_id":"b"}""",
                    """{"confirm${92.toChar()}u005fexternal_transfer":true,"confirm_external_transfer":false}""",
                    """{"model_id":"a","x":1}""",
                    """{"confirm_external_transfer":true,"model_id":"a","confirm_external_transfer":true}""",
                    """{"confirm_external_transfer":true,"x":1}""",
                    """{"confirm_external_transfer":true,"confirm_external_transfer":true}""",
                    """{"confirm_external_transfer":false,"confirm_external_transfer":true}""",
                    """{"confirm_external_transfer":true,"confirm_external_transfer":false}""",
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
                    """{"model_id":"deepseek-v4-flash-0731"}""",
                    """{"confirm_external_transfer":true,"model_id":"org/model:1"}""",
                    """ { "model_id" : "a.b-c_d:e/f" } """,
                    """{"confirm${92.toChar()}u005fexternal_transfer":true}""",
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
    fun `sources list exposes the arm of a profile only when it declares one`() {
        OnlineSourceFixture().use { fixture ->
            val json = fixture.profilesJson()
            val profile = readSourceProfiles(json.byteInputStream()).single()
            withServer(sourceProfiles = listOf(profile.copy(id = "armed", arm = "A"), profile.copy(id = "plain"))) { _, api ->
                api.bootstrap()
                val listed =
                    api
                        .get("/api/sources")
                        .jsonObject()
                        .getValue("profiles")
                        .jsonArray
                        .map { it.jsonObject }
                        .associateBy { it.getValue("id").jsonPrimitive.content }
                val armed = listed.getValue("armed")
                assertEquals(JsonPrimitive("A"), armed["arm"])
                assertEquals(setOf("id", "source_kind", "transport", "arm"), armed.keys)
                assertEquals(setOf("id", "source_kind", "transport"), listed.getValue("plain").keys)
            }
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
            assertEquals(JsonNull, api.activeBaseline())
        }

    @Test
    fun `baseline API manually selects reloads compares and clears a pinned analysis`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val baselineId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val currentId = api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)

            assertEquals(JsonNull, api.activeBaseline())

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
            assertEquals(selection, api.activeBaseline())

            val comparison =
                api
                    .get("/api/runs/${input.runId}/analyses/$currentId/comparison?series=release")
                    .jsonObject()
            assertEquals(setOf("baseline", "current", "comparability", "warnings", "profile", "metrics", "conditions"), comparison.keys)
            assertEquals(JsonNull, comparison.getValue("profile"))
            assertEquals(
                listOf("BASELINE_IS_CURRENT_RUN", "POLICY_DIFFERS"),
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

            val windowPath = "/api/runs/${input.runId}/analyses/$currentId/comparison?series=release"
            val windows = api.get("$windowPath&baseline_window=before&current_window=after")
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
            assertError(api.get("$windowPath&baseline_window=before"), 400, "MALFORMED_REQUEST")
            assertError(api.get("$windowPath&baseline_window=before&current_window=after&min_change_percent=0"), 400, "MALFORMED_REQUEST")
            assertError(
                api.get("$windowPath&baseline_window=before&current_window=after&min_error_rate_delta=1e999999"),
                400,
                "MALFORMED_REQUEST",
            )

            assertEquals(JsonNull, api.delete("/api/baseline?series=release").jsonObject().getValue("baseline"))
            assertEquals(JsonNull, api.activeBaseline())
            assertError(api.get("/api/runs/${input.runId}/analyses/$currentId/comparison?series=release"), 404, "NOT_FOUND")
        }

    @Test
    fun `manual baseline accepts only a PASS analysis`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val passId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val failId = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val noPolicyId = api.createJob(input.runId).analysisId(api)

            val accepted = api.selectManual(input.runId, passId)
            assertEquals(200, accepted.statusCode())
            val selection = api.activeBaseline()
            assertEquals(accepted.jsonObject().getValue("baseline"), selection)

            val failed = api.selectManual(input.runId, failId)
            assertError(failed, 422, "BASELINE_CANDIDATE_NOT_PASS")
            assertTrue(failed.errorMessage().contains("policy_verdict=FAIL"), failed.errorMessage())
            val unknown = api.selectManual(input.runId, noPolicyId)
            assertError(unknown, 422, "BASELINE_CANDIDATE_NOT_PASS")
            assertTrue(unknown.errorMessage().contains("policy_verdict=NO_POLICY"), unknown.errorMessage())
            // A refusal writes nothing: the earlier selection is still pinned.
            assertEquals(selection, api.activeBaseline())
        }

    @Test
    fun `statistical baseline refuses a FAIL candidate with the same code as manual`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120))
            val failing = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val failingId = api.createJob(failing.runId, FAILING_POLICY).analysisId(api)
            val candidates = listOf(runs[0], runs[1], failing.runId to failingId)

            val response = api.post("/api/baseline", "application/json", statisticalBody(candidates, true))
            assertError(response, 422, "BASELINE_CANDIDATE_NOT_PASS")
            assertTrue(response.errorMessage().contains("policy_verdict=FAIL"), response.errorMessage())
            // Request checks come before candidate checks: the unconfirmed flag wins over a failing candidate.
            assertError(
                api.post("/api/baseline", "application/json", statisticalBody(candidates, false)),
                422,
                "BASELINE_COMPARABILITY_UNCONFIRMED",
            )
            assertEquals(JsonNull, api.activeBaseline())
        }

    @Test
    fun `a small sample PASS is admitted as a baseline in both modes and the comparison warns`() =
        withServer { store, api ->
            api.bootstrap()
            val small = statisticalRuns(store, api, listOf(100, 110, 120), SMALL_SAMPLE_POLICY)
            val (result, _) = store.readAnalysisDocuments(small[0].first, small[0].second)!!
            // the fixture is a real analysis, not a synthetic document: PASS with exactly one reason
            assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
            val coverage = result.getValue("analysis_coverage").jsonObject
            assertEquals("INCOMPLETE", coverage.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("SMALL_SAMPLE"), coverage.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
            val current = statisticalRuns(store, api, listOf(105)).single()

            assertEquals(200, api.selectManual(small[0].first, small[0].second).statusCode())
            assertEquals(listOf("BASELINE_SMALL_SAMPLE", "POLICY_DIFFERS"), api.comparisonOf(current.first, current.second).warnings())

            api.selectStatistical(small)
            val winner = api.activeBaseline().jsonObject
            assertEquals(3, winner.getValue("candidates").jsonArray.size)
            assertEquals(listOf("BASELINE_SMALL_SAMPLE", "POLICY_DIFFERS"), api.comparisonOf(current.first, current.second).warnings())
        }

    @Test
    fun `a small sample reason next to another coverage reason is still refused in both modes`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val identity = """{"run_id":"${input.runId}","tag":"small-and-gaps"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            val result =
                """{"schema_version":"analysis-result.v1","run_id":"${input.runId}","analysis_mode":"standard","run_validity":"VALID",""" +
                    """"policy_verdict":"PASS","analysis_coverage":{"status":"INCOMPLETE","reasons":["SMALL_SAMPLE","RESOURCE_GAPS"]},""" +
                    """"findings":[],"evidence":[]}"""
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.writeString(staging.resolve("analysis-result.json"), result)
            }
            val others = statisticalRuns(store, api, listOf(100, 110))

            assertError(api.selectManual(input.runId, analysisId), 422, "BASELINE_CANDIDATE_INCOMPLETE")
            assertError(
                api.post("/api/baseline", "application/json", statisticalBody(others + (input.runId to analysisId), true)),
                422,
                "BASELINE_CANDIDATE_INCOMPLETE",
            )
            assertEquals(JsonNull, api.activeBaseline())
        }

    @Test
    fun `baseline refuses a result larger than the bound`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val identity = """{"run_id":"${input.runId}","tag":"too-large"}""".encodeToByteArray()
            val analysisId = sha256Hex(identity)
            store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                // Sparse content that is not JSON: the size check must fire before anything is parsed.
                RandomAccessFile(
                    staging.resolve("analysis-result.json").toFile(),
                    "rw",
                ).use { it.setLength(MAX_VERIFIED_RESULT_BYTES + 1L) }
            }

            assertError(api.selectManual(input.runId, analysisId), 422, "BASELINE_CANDIDATE_TOO_LARGE")
            assertEquals(JsonNull, api.activeBaseline())
        }

    @Test
    fun `baseline detects a same-size substitution of the stored result`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val resultPath = store.readAnalysis(input.runId, analysisId)!!.path.resolve("analysis-result.json")
            val original = Files.readString(resultPath)
            val tampered = original.replace("\"policy_verdict\":\"FAIL\"", "\"policy_verdict\":\"PASS\"")
            assertEquals(original.length, tampered.length)
            assertTrue(original != tampered)
            Files.writeString(resultPath, tampered)

            assertError(api.selectManual(input.runId, analysisId), 500, "CORRUPT_BASELINE")
            assertEquals(JsonNull, api.activeBaseline())
        }

    @Test
    fun `a baseline file saved before the PASS rule is still read and used for comparison`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val noPolicyId = api.createJob(input.runId).analysisId(api)
            val currentId = api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)
            val reference = Json.parseToJsonElement("""{"run_id":"${input.runId}","analysis_id":"$noPolicyId"}""").jsonObject
            store.replaceBaseline(manualBaselineSelection("release", reference))

            assertEquals(
                reference,
                api
                    .get("/api/baseline")
                    .jsonObject()
                    .getValue("baseline")
                    .jsonObject
                    .getValue("reference"),
            )
            val comparison = api.get("/api/runs/${input.runId}/analyses/$currentId/comparison")
            assertEquals(200, comparison.statusCode())
            assertEquals(
                listOf("BASELINE_IS_CURRENT_RUN", "BASELINE_NOT_PASS", "POLICY_DIFFERS"),
                comparison.jsonObject().warnings(),
            )
        }

    @Test
    fun `comparison warns about the baseline verdict and the policy without blocking`() =
        withServer { store, api ->
            api.bootstrap()
            val failing = statisticalRuns(store, api, listOf(100, 110), FAILING_POLICY)
            val permissive = statisticalRuns(store, api, listOf(120), PERMISSIVE_POLICY).single()
            val unpoliced = statisticalRuns(store, api, listOf(130, 140), null)

            // baseline files written before the PASS rule: the API refuses to select such analyses today
            fun warningsAgainst(
                baseline: Pair<String, String>,
                current: Pair<String, String>,
            ): List<String> {
                val reference =
                    buildJsonObject {
                        put("run_id", baseline.first)
                        put("analysis_id", baseline.second)
                    }
                store.replaceBaseline(manualBaselineSelection("release", reference))
                return api.comparisonOf(current.first, current.second).warnings()
            }

            assertEquals(listOf("BASELINE_NOT_PASS"), warningsAgainst(failing[0], failing[1]))
            assertEquals(listOf("BASELINE_NOT_PASS", "POLICY_DIFFERS"), warningsAgainst(failing[0], permissive))
            assertEquals(listOf("BASELINE_NOT_PASS", "POLICY_DIFFERS"), warningsAgainst(unpoliced[0], permissive))
            assertEquals(listOf("BASELINE_NOT_PASS"), warningsAgainst(unpoliced[0], unpoliced[1]))
            assertEquals(listOf("POLICY_DIFFERS"), warningsAgainst(permissive, unpoliced[0]))
        }

    private fun profileBody(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(RELEASE_PROFILE_FIELDS.associateWith { name -> pairs.toMap()[name]?.let(::JsonPrimitive) ?: JsonNull })

    @Test
    fun `comparison reports the release profile and the series of the current analysis`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second).statusCode())
            assertEquals(JsonNull, api.comparisonOf(runs[1].first, runs[1].second).getValue("profile"))

            val paced = profileBody("pacing" to "10 s")
            val baselineRelease =
                api
                    .createRelease(releaseBody(runs[0].first, listOf(runs[0].second), series = "release", label = "1.0", profile = paced))
                    .newReleaseId()
            val currentRelease =
                api
                    .createRelease(releaseBody(runs[1].first, listOf(runs[1].second), series = "release", label = "1.1", profile = paced))
                    .newReleaseId()

            val match = api.comparisonOf(runs[1].first, runs[1].second)
            assertEquals(emptyList<String>(), match.warnings())
            assertEquals(
                buildJsonObject {
                    put("status", "MATCH")
                    put("differing_fields", JsonArray(emptyList()))
                    put("baseline_release_id", baselineRelease)
                    put("current_release_id", currentRelease)
                },
                match.getValue("profile"),
            )

            val changed = profileBody("pacing" to "20 s", "load_model" to "open")
            assertEquals(200, api.replaceRelease(currentRelease, releasePutBody("1.1", listOf(runs[1].second), changed)).statusCode())
            val mismatch = api.comparisonOf(runs[1].first, runs[1].second)
            assertEquals(listOf("PROFILE_MISMATCH"), mismatch.warnings())
            assertEquals(
                listOf("load_model", "pacing"),
                mismatch
                    .getValue("profile")
                    .jsonObject
                    .getValue("differing_fields")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertEquals(match.getValue("metrics"), mismatch.getValue("metrics"))
            assertEquals(match.getValue("comparability"), mismatch.getValue("comparability"))

            // a current release of another series is never compared with this baseline: its series selects another slot
            // (no baseline there), and a query naming the baseline series contradicts the release
            val other = api.createRelease(releaseBody(runs[2].first, listOf(runs[2].second), series = "other", label = "2.0"))
            assertEquals(201, other.statusCode())
            val otherPath = "/api/runs/${runs[2].first}/analyses/${runs[2].second}/comparison"
            assertError(api.get(otherPath), 404, "NOT_FOUND")
            assertError(api.get("$otherPath?series=release"), 422, "BASELINE_SERIES_CONFLICT")
            assertEquals(200, api.delete("/api/releases/${other.newReleaseId()}").statusCode())
            // a release without a profile gives profile null
            val third = api.createRelease(releaseBody(runs[2].first, listOf(runs[2].second), series = "release", label = "2.1"))
            assertEquals(201, third.statusCode())
            val unprofiled = api.comparisonOf(runs[2].first, runs[2].second)
            assertEquals(JsonNull, unprofiled.getValue("profile"))
            assertEquals(emptyList<String>(), unprofiled.warnings())
        }

    @Test
    fun `comparison survives an ambiguous release lookup and a corrupt registry`() {
        val root = tempDir.resolve("comparison-registry")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second).statusCode())
            val paced = profileBody("pacing" to "10 s")
            api.createRelease(releaseBody(runs[0].first, listOf(runs[0].second), series = "release", profile = paced))
            val currentRelease =
                api.createRelease(releaseBody(runs[1].first, listOf(runs[1].second), series = "release", profile = paced)).newReleaseId()
            val path = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/comparison"
            val status = {
                api
                    .get(path)
                    .jsonObject()
                    .getValue("profile")
                    .jsonObject
                    .getValue("status")
                    .jsonPrimitive.content
            }
            assertEquals("MATCH", status())

            // a hand-copied record with the same analysis makes the lookup ambiguous: no profile, no failure
            val copy = Json.parseToJsonElement(Files.readString(root.resolve("releases/$currentRelease.json"))).jsonObject
            val copyId = releaseId(1_767_225_600_000L + 1_000L, "ffffffff")
            val copied = validateRelease(JsonObject(copy + ("release_id" to JsonPrimitive(copyId))))
            Files.write(root.resolve("releases/$copyId.json"), canonicalJson(copied))
            val ambiguous = api.get("$path?series=release")
            assertEquals(200, ambiguous.statusCode())
            assertEquals(JsonNull, ambiguous.jsonObject().getValue("profile"))
            assertEquals(emptyList<String>(), ambiguous.jsonObject().warnings())
            Files.delete(root.resolve("releases/$copyId.json"))
            assertEquals("MATCH", status())

            // a registry the API itself refuses to list does not break the comparison either
            seedReleaseFiles(root, MAX_RELEASES - 1)
            Files.writeString(root.resolve("releases/stray.txt"), "x")
            assertError(api.get("/api/releases"), 500, "CORRUPT_RELEASE_REGISTRY")
            val damaged = api.get("$path?series=release")
            assertEquals(200, damaged.statusCode())
            assertEquals(JsonNull, damaged.jsonObject().getValue("profile"))
            assertEquals(emptyList<String>(), damaged.jsonObject().warnings())
        }
    }

    @Test
    fun `baseline conditions API persists three states and isolates exact pair and windows`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val baselineId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val currentId = api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY))).analysisId(api)
            val baselineBody =
                """{"mode":"manual","series":"release","reference":{"run_id":"${input.runId}","analysis_id":"$baselineId"}}"""
            api.post("/api/baseline", "application/json", baselineBody.encodeToByteArray())
            val path = "/api/runs/${input.runId}/analyses/$currentId/baseline-conditions?series=release"

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
            assertError(api.get("$path&extra=1"), 400)
            assertError(api.get("$path&baseline_window=before"), 400)
            assertError(api.get("/api/runs/not-a-run/analyses/$currentId/baseline-conditions?series=release"), 400)

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

            val comparison = api.get("/api/runs/${input.runId}/analyses/$currentId/comparison?series=release").jsonObject()
            assertEquals("USER_CONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
            assertEquals(confirmed, comparison.getValue("conditions"))
            val otherPair = api.get("/api/runs/${input.runId}/analyses/$baselineId/comparison?series=release").jsonObject()
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
                    .get("/api/runs/${input.runId}/analyses/$currentId/comparison?series=release")
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

            val windowPath = "$path&baseline_window=before&current_window=after"
            val windowed =
                api
                    .post(windowPath, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray())
                    .jsonObject()
                    .getValue("conditions")
            assertEquals(windowed, api.get(windowPath).jsonObject().getValue("conditions"))
            assertEquals(JsonNull, api.get("$path&baseline_window=before&current_window=other").jsonObject().getValue("conditions"))
            assertEquals(
                windowed,
                api
                    .get(
                        "/api/runs/${input.runId}/analyses/$currentId/comparison?series=release&baseline_window=before&current_window=after&min_change_percent=10",
                    ).jsonObject()
                    .getValue("conditions"),
            )

            api.delete("/api/baseline?series=release")
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
                        "/api/runs/$testedRun/analyses/$testedAnalysis/baseline-conditions?series=release",
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
            // the second analysis of the run uses another policy file: the verdict was reached under a different policy
            assertEquals(listOf("BASELINE_IS_CURRENT_RUN", "CURRENT_IN_CANDIDATE_SET", "POLICY_DIFFERS"), sameRun.warnings())
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
            val conditionsPath = "/api/runs/$testedRun/analyses/$testedAnalysis/baseline-conditions?series=release"

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

            assertEquals(JsonNull, api.delete("/api/baseline?series=release").jsonObject().getValue("baseline"))
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

    private fun releaseBody(
        runId: String,
        analyses: List<String>,
        series: String = "checkout",
        label: String = "1.0",
        profile: JsonElement = JsonNull,
        notes: JsonElement = JsonNull,
    ): JsonObject =
        buildJsonObject {
            put("series", series)
            put("label", label)
            put("run_id", runId)
            put("analyses", JsonArray(analyses.map { buildJsonObject { put("analysis_id", it) } }))
            put("profile", profile)
            put("notes", notes)
        }

    private fun releasePutBody(
        label: String,
        analyses: List<String>,
        profile: JsonElement = JsonNull,
        notes: JsonElement = JsonNull,
    ): JsonObject = JsonObject(releaseBody("unused", analyses, label = label, profile = profile, notes = notes) - setOf("series", "run_id"))

    private fun ApiClient.createRelease(body: JsonObject): HttpResponse<String> =
        post("/api/releases", "application/json", body.toString().encodeToByteArray())

    private fun ApiClient.replaceRelease(
        id: String,
        body: JsonObject,
    ): HttpResponse<String> = put("/api/releases/$id", "application/json", body.toString().encodeToByteArray())

    private fun HttpResponse<String>.newReleaseId(): String = jsonObject().getValue("release_id").jsonPrimitive.content

    private fun JsonObject.reasons(name: String = "ineligible_reasons"): List<String> =
        getValue(name).jsonArray.map {
            it.jsonPrimitive.content
        }

    private fun HttpResponse<String>.errorLimit(): Int? =
        jsonObject()
            .getValue("error")
            .jsonObject["limit"]
            ?.jsonPrimitive
            ?.long
            ?.toInt()

    /** Writes an analysis document set straight into the store, without the engine, to set up cases the engine never produces. */
    private fun writeSyntheticAnalysis(
        store: RunBundleStore,
        runId: String,
        tag: String,
        startedAt: String? = "2026-01-01T00:00:00Z",
        resultRunId: String = runId,
        metadataRunId: String = runId,
        arm: String? = null,
        verdict: String = "PASS",
    ): String {
        val identity =
            canonicalJson(
                buildJsonObject {
                    put("run_id", runId)
                    put("tag", tag)
                    put("policy_sha256", "a".repeat(64))
                    if (arm != null) put("resource_arm", arm)
                },
            )
        val analysisId = sha256Hex(identity)
        store.writeAnalysisAtomically(runId, analysisId) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            Files.write(
                staging.resolve("analysis-result.json"),
                canonicalJson(
                    buildJsonObject {
                        put("schema_version", "analysis-result.v1")
                        put("run_id", resultRunId)
                        put("run_validity", "VALID")
                        put("policy_verdict", verdict)
                        put(
                            "analysis_coverage",
                            buildJsonObject {
                                put("status", "COMPLETE")
                                put("reasons", JsonArray(emptyList()))
                            },
                        )
                    },
                ),
            )
            if (startedAt != null) {
                Files.write(
                    staging.resolve("run.json"),
                    canonicalJson(
                        buildJsonObject {
                            put("run_id", metadataRunId)
                            put("started_at", startedAt)
                        },
                    ),
                )
            }
        }
        return analysisId
    }

    private fun syntheticDraft(
        series: String,
        label: String,
        startedAt: String,
        analysisId: String,
    ): JsonObject =
        buildJsonObject {
            put("schema_version", "local-release.v1")
            put("series", series)
            put("label", label)
            put("run_id", "jmeter_jtl_csv-${"0".repeat(64)}")
            put("started_at", startedAt)
            put(
                "analyses",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("analysis_id", analysisId)
                            put("arm", JsonNull)
                            put("coverage_reasons", JsonArray(emptyList()))
                            put("coverage_status", "COMPLETE")
                            put("policy_sha256", "a".repeat(64))
                            put("policy_verdict", "PASS")
                            put("run_validity", "VALID")
                        },
                    ),
                ),
            )
            put("profile", JsonNull)
            put("notes", JsonNull)
        }

    private fun seedReleaseFiles(
        root: Path,
        count: Int,
    ) {
        val directory = Files.createDirectories(root.resolve("releases"))
        repeat(count) { index ->
            val millis = 1_767_225_600_000L + index
            val id = releaseId(millis, "%08x".format(index))
            val record =
                buildJsonObject {
                    put("schema_version", "local-release.v1")
                    put("release_id", id)
                    put("series", "seed")
                    put("label", "seed-$index")
                    put("run_id", "jmeter_jtl_csv-${"0".repeat(64)}")
                    put("started_at", Instant.ofEpochMilli(millis).toString())
                    put(
                        "analyses",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("analysis_id", "%064x".format(index + 1))
                                    put("arm", JsonNull)
                                    put("coverage_reasons", JsonArray(emptyList()))
                                    put("coverage_status", "COMPLETE")
                                    put("policy_sha256", "a".repeat(64))
                                    put("policy_verdict", "PASS")
                                    put("run_validity", "VALID")
                                },
                            ),
                        ),
                    )
                    put("profile", JsonNull)
                    put("notes", JsonNull)
                    put("created_at", "2026-01-02T00:00:00Z")
                    put("updated_at", "2026-01-02T00:00:00Z")
                }
            Files.write(directory.resolve("$id.json"), canonicalJson(validateRelease(record)))
        }
    }

    @Test
    fun `release is created from verified documents`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val empty = JsonObject(RELEASE_PROFILE_FIELDS.associateWith { JsonPrimitive("  ") })

            val response = api.createRelease(releaseBody(input.runId, listOf(analysisId), label = "  é ", profile = empty))
            assertEquals(201, response.statusCode())
            val release = response.jsonObject()
            assertTrue(RELEASE_ID.matches(release.getValue("release_id").jsonPrimitive.content))
            // NFD input is stored composed, and an all-empty profile is stored as null
            assertEquals("é", release.getValue("label").jsonPrimitive.content)
            assertEquals(JsonNull, release.getValue("profile"))
            assertEquals(JsonNull, release.getValue("notes"))
            assertEquals(
                store.readVerifiedAnalysis(input.runId, analysisId)!!.run!!.getValue("started_at"),
                release.getValue("started_at"),
            )
            assertTrue(
                release
                    .getValue("release_id")
                    .jsonPrimitive.content
                    .startsWith("0017672256"),
            )
            val analysis =
                release
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("PASS", analysis.getValue("policy_verdict").jsonPrimitive.content)
            assertEquals("OK", analysis.getValue("analysis_state").jsonPrimitive.content)
            assertTrue(analysis.getValue("baseline_eligible").jsonPrimitive.boolean)
            assertEquals(emptyList<String>(), analysis.reasons())
            assertTrue(release.getValue("baseline_eligible").jsonPrimitive.boolean)
            // the stored record is what the single read returns
            val read = api.get("/api/releases/${response.newReleaseId()}")
            assertEquals(200, read.statusCode())
            assertEquals(release, read.jsonObject())
        }

    @Test
    fun `release body is validated field by field before any analysis is read`() =
        withServer { store, api ->
            api.bootstrap()
            val run = "jmeter_jtl_csv-${"1".repeat(64)}"
            val good = releaseBody(run, listOf("a".repeat(64)))
            val nineIds = (0..8).map { "%064x".format(it + 1) }
            val bodies =
                mapOf(
                    "extra key" to JsonObject(good + ("extra_field" to JsonNull)),
                    "missing notes" to JsonObject(good - "notes"),
                    "empty analyses" to releaseBody(run, emptyList()),
                    "nine analyses" to releaseBody(run, nineIds),
                    "duplicate analysis" to releaseBody(run, listOf("a".repeat(64), "a".repeat(64))),
                    "label over 128 bytes" to releaseBody(run, listOf("a".repeat(64)), label = "x".repeat(129)),
                    "blank series" to releaseBody(run, listOf("a".repeat(64)), series = "   "),
                    "control character" to releaseBody(run, listOf("a".repeat(64)), label = "a\u0007b"),
                    "bad run id" to releaseBody("run", listOf("a".repeat(64))),
                    "bad analysis id" to releaseBody(run, listOf("A".repeat(64))),
                    "unknown profile key" to
                        releaseBody(
                            run,
                            listOf("a".repeat(64)),
                            profile = buildJsonObject { put("extra_field", "x") },
                        ),
                    "analysis entry with arm" to
                        JsonObject(
                            good +
                                (
                                    "analyses" to
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("analysis_id", "a".repeat(64))
                                                    put("arm", "x")
                                                },
                                            ),
                                        )
                                ),
                        ),
                )
            bodies.forEach { (name, body) ->
                assertError(api.createRelease(body), 400, "MALFORMED_REQUEST")
                assertEquals(0, store.listReleases(null, null, 10).releases.size, name)
            }
            assertError(api.post("/api/releases", "text/plain", good.toString().encodeToByteArray()), 415, "UNSUPPORTED_MEDIA_TYPE")
            assertError(
                api.createRelease(releaseBody(run, listOf("a".repeat(64)), notes = JsonPrimitive("x".repeat(17_000)))),
                413,
                "RESOURCE_LIMIT_EXCEEDED",
            )
            // an oversize note that fits the body limit is still refused by its own limit
            assertError(
                api.createRelease(releaseBody(run, listOf("a".repeat(64)), notes = JsonPrimitive("x".repeat(1025)))),
                400,
                "MALFORMED_REQUEST",
            )
            assertEquals(0, store.listReleases(null, null, 10).corruptCount)
        }

    @Test
    fun `unknown analysis or run is 404 and another run's analysis is not found`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val other = store.acceptInput(ByteArrayInputStream(JMETER_XML.bytes()), JMETER_XML.filename)
            api.bootstrap()
            val otherAnalysis = api.createJob(other.runId, PERMISSIVE_POLICY).analysisId(api)

            assertError(api.createRelease(releaseBody(input.runId, listOf("f".repeat(64)))), 404, "NOT_FOUND")
            assertError(api.createRelease(releaseBody("jmeter_jtl_csv-${"2".repeat(64)}", listOf(otherAnalysis))), 404, "NOT_FOUND")
            assertError(api.createRelease(releaseBody(input.runId, listOf(otherAnalysis))), 404, "NOT_FOUND")
            assertEquals(0, store.listReleases(null, null, 10).releases.size)
        }

    @Test
    fun `registration refuses analyses without run metadata with different starts or arms in conflict`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val real = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val failing = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val noMeta = writeSyntheticAnalysis(store, input.runId, "no-meta", startedAt = null)
            val otherStart = writeSyntheticAnalysis(store, input.runId, "other-start", startedAt = "2026-01-01T00:00:01Z")
            val foreign = writeSyntheticAnalysis(store, input.runId, "foreign", resultRunId = "jmeter_jtl_csv-${"3".repeat(64)}")
            val foreignMetadata =
                writeSyntheticAnalysis(store, input.runId, "foreign-metadata", metadataRunId = "jmeter_jtl_csv-${"4".repeat(64)}")
            val blue1 = writeSyntheticAnalysis(store, input.runId, "blue-1", arm = "blue")
            val blue2 = writeSyntheticAnalysis(store, input.runId, "blue-2", arm = "blue")
            val green = writeSyntheticAnalysis(store, input.runId, "green", arm = "green")

            assertError(api.createRelease(releaseBody(input.runId, listOf(noMeta))), 422, "RELEASE_ANALYSIS_NO_RUN_METADATA")
            assertError(api.createRelease(releaseBody(input.runId, listOf(real, otherStart))), 422, "RELEASE_STARTED_AT_MISMATCH")
            assertError(api.createRelease(releaseBody(input.runId, listOf(foreign))), 422, "RELEASE_RUN_MISMATCH")
            assertError(api.createRelease(releaseBody(input.runId, listOf(foreignMetadata))), 422, "RELEASE_RUN_MISMATCH")
            // two analyses without an arm, a repeated arm and an unmarked analysis next to a marked one
            assertError(api.createRelease(releaseBody(input.runId, listOf(real, failing))), 422, "RELEASE_ARM_CONFLICT")
            assertError(api.createRelease(releaseBody(input.runId, listOf(blue1, blue2))), 422, "RELEASE_ARM_CONFLICT")
            assertError(api.createRelease(releaseBody(input.runId, listOf(blue1, real))), 422, "RELEASE_ARM_CONFLICT")
            assertEquals(0, store.listReleases(null, null, 10).releases.size)

            val twoArms = api.createRelease(releaseBody(input.runId, listOf(green, blue1)))
            assertEquals(201, twoArms.statusCode())
            val arms =
                twoArms
                    .jsonObject()
                    .getValue("analyses")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("arm")
                            .jsonPrimitive.content
                    }
            assertEquals(listOf(green to "green", blue1 to "blue").sortedBy { it.first }.map { it.second }, arms)
        }

    @Test
    fun `registration refuses a tampered or oversized result`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val tampered = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val resultPath = store.readAnalysis(input.runId, tampered)!!.path.resolve("analysis-result.json")
            val original = Files.readString(resultPath)
            Files.writeString(resultPath, original.replace("\"policy_verdict\":\"FAIL\"", "\"policy_verdict\":\"PASS\""))
            assertError(api.createRelease(releaseBody(input.runId, listOf(tampered))), 500, "CORRUPT_RUN_BUNDLE")

            val identity = """{"run_id":"${input.runId}","tag":"too-large"}""".encodeToByteArray()
            val oversized = sha256Hex(identity)
            store.writeAnalysisAtomically(input.runId, oversized) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                RandomAccessFile(
                    staging.resolve("analysis-result.json").toFile(),
                    "rw",
                ).use { it.setLength(MAX_VERIFIED_RESULT_BYTES + 1L) }
            }
            assertError(api.createRelease(releaseBody(input.runId, listOf(oversized))), 422, "RELEASE_RESULT_TOO_LARGE")
            assertEquals(0, store.listReleases(null, null, 10).releases.size)
        }

    @Test
    fun `one analysis registers once even when two requests race`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val responses =
                    (1..2)
                        .map { worker ->
                            executor.submit<HttpResponse<String>> {
                                barrier.await(10, TimeUnit.SECONDS)
                                api.createRelease(releaseBody(input.runId, listOf(analysisId), label = "w$worker"))
                            }
                        }.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(listOf(201, 409), responses.map { it.statusCode() }.sorted())
                val refused = responses.single { it.statusCode() == 409 }
                assertError(refused, 409, "RELEASE_ANALYSIS_ALREADY_REGISTERED")
                // after the winner is removed the analysis registers again
                val winner = responses.single { it.statusCode() == 201 }
                assertEquals(200, api.delete("/api/releases/${winner.newReleaseId()}").statusCode())
                assertEquals(201, api.createRelease(releaseBody(input.runId, listOf(analysisId))).statusCode())
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun `list is complete newest first and paginated and reports damaged entries`() {
        val root = tempDir.resolve("release-list")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val created =
                (0 until 3).map { n ->
                    val draft =
                        syntheticDraft(
                            series = if (n == 1) "beta" else "alpha",
                            label = "r$n",
                            startedAt = "2026-01-0${n + 1}T00:00:00Z",
                            analysisId = "%064x".format(n + 1),
                        )
                    store
                        .createRelease(draft, Instant.parse("2026-02-01T00:00:00Z"))
                        .getValue("release_id")
                        .jsonPrimitive.content
                }
            val newestFirst = created.sortedDescending()

            val first = api.get("/api/releases?limit=2").jsonObject()
            assertEquals(
                newestFirst.take(2),
                first.getValue("releases").jsonArray.map {
                    it.jsonObject
                        .getValue("release_id")
                        .jsonPrimitive.content
                },
            )
            assertEquals(newestFirst[1], first.getValue("next_after").jsonPrimitive.content)
            val second = api.get("/api/releases?limit=2&after=${newestFirst[1]}").jsonObject()
            assertEquals(
                newestFirst.drop(2),
                second.getValue("releases").jsonArray.map {
                    it.jsonObject
                        .getValue("release_id")
                        .jsonPrimitive.content
                },
            )
            assertEquals(JsonNull, second.getValue("next_after"))
            val summary =
                first.getValue("series_summary").jsonArray.map {
                    it.jsonObject
                        .getValue("series")
                        .jsonPrimitive.content to
                        it.jsonObject
                            .getValue("count")
                            .jsonPrimitive.long
                }
            assertEquals(listOf("alpha" to 2L, "beta" to 1L), summary)
            val beta = api.get("/api/releases?series=beta&limit=1").jsonObject()
            assertEquals(1, beta.getValue("releases").jsonArray.size)
            assertEquals(JsonNull, beta.getValue("next_after"))
            // the summary counts every record whatever the filter
            assertEquals(2, beta.getValue("series_summary").jsonArray.size)
            listOf("?limit=0", "?limit=101", "?limit=x", "?after=garbage", "?other=1", "?limit=1&limit=2", "?series=").forEach {
                assertError(api.get("/api/releases$it"), 400, "MALFORMED_REQUEST")
            }

            Files.writeString(root.resolve("releases/stray.txt"), "x")
            val damaged = api.get("/api/releases").jsonObject()
            assertEquals(1, damaged.getValue("corrupt_count").jsonPrimitive.long)
            val entry =
                damaged
                    .getValue("corrupt_names")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("stray.txt", entry.getValue("name").jsonPrimitive.content)
            assertEquals("UNSAFE_ENTRY", entry.getValue("reason").jsonPrimitive.content)
            assertEquals(3, damaged.getValue("releases").jsonArray.size)
        }
    }

    @Test
    fun `list reports MISSING cheaply and the single read reports the full state`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val vanishing = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val damaged = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val first = api.createRelease(releaseBody(input.runId, listOf(vanishing), label = "gone")).newReleaseId()
            val second = api.createRelease(releaseBody(input.runId, listOf(damaged), label = "damaged")).newReleaseId()

            store
                .readAnalysis(input.runId, vanishing)!!
                .path
                .toFile()
                .deleteRecursively()
            val resultPath = store.readAnalysis(input.runId, damaged)!!.path.resolve("analysis-result.json")
            Files.write(resultPath, Files.readAllBytes(resultPath) + byteArrayOf(' '.code.toByte()))

            val listed =
                api.get("/api/releases").jsonObject().getValue("releases").jsonArray.associate {
                    it.jsonObject
                        .getValue("release_id")
                        .jsonPrimitive.content to it.jsonObject
                }
            val gone = listed.getValue(first)
            assertEquals(
                "MISSING",
                gone
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_state")
                    .jsonPrimitive.content,
            )
            assertFalse(gone.getValue("baseline_eligible").jsonPrimitive.boolean)
            assertEquals(listOf("ANALYSIS_MISSING"), gone.reasons())
            // existence only: the damaged artifact is not noticed in the list
            assertEquals(
                "OK",
                listed
                    .getValue(second)
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_state")
                    .jsonPrimitive.content,
            )

            val single = api.get("/api/releases/$first").jsonObject()
            assertEquals(
                "MISSING",
                single
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_state")
                    .jsonPrimitive.content,
            )
            val full = api.get("/api/releases/$second").jsonObject()
            assertEquals(
                "CORRUPT",
                full
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_state")
                    .jsonPrimitive.content,
            )
            assertEquals(listOf("ANALYSIS_CORRUPT"), full.reasons())
            assertEquals(
                listOf("ANALYSIS_CORRUPT"),
                full
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .reasons(),
            )
        }

    @Test
    fun `eligibility mirrors the baseline rule`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()

            fun eligibility(policy: ByteArray?): JsonObject {
                val analysis = api.createJob(input.runId, policy).analysisId(api)
                val response = api.createRelease(releaseBody(input.runId, listOf(analysis), label = "p${policy?.size}"))
                assertEquals(201, response.statusCode())
                return response.jsonObject()
            }
            val pass = eligibility(PERMISSIVE_POLICY)
            assertTrue(pass.getValue("baseline_eligible").jsonPrimitive.boolean)
            val fail = eligibility(FAILING_POLICY)
            assertFalse(fail.getValue("baseline_eligible").jsonPrimitive.boolean)
            assertEquals(listOf("BASELINE_CANDIDATE_NOT_PASS"), fail.reasons())
            val noPolicy = eligibility(null)
            assertEquals(listOf("BASELINE_CANDIDATE_NOT_PASS"), noPolicy.reasons())
            assertEquals(
                "NO_POLICY",
                noPolicy
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("policy_sha256")
                    .jsonPrimitive.content,
            )
            // a small-sample PASS is admitted by the shared rule and keeps its reason as a copied fact
            val small = eligibility(SMALL_SAMPLE_POLICY)
            assertTrue(small.getValue("baseline_eligible").jsonPrimitive.boolean)
            assertEquals(
                listOf("SMALL_SAMPLE"),
                small
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .reasons("coverage_reasons"),
            )
        }

    @Test
    fun `replace changes only the editable fields`() {
        val root = tempDir.resolve("release-replace")
        withServer(dataRoot = root) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val a = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val b = api.createJob(input.runId, FAILING_POLICY).analysisId(api)
            val c = api.createJob(input.runId, SMALL_SAMPLE_POLICY).analysisId(api)
            val firstId = api.createRelease(releaseBody(input.runId, listOf(a), label = "one")).newReleaseId()
            val secondId = api.createRelease(releaseBody(input.runId, listOf(b), label = "two")).newReleaseId()
            val original = api.get("/api/releases/$firstId").jsonObject()

            val profile =
                buildJsonObject {
                    RELEASE_PROFILE_FIELDS.forEach { put(it, if (it == "pacing") JsonPrimitive("1 s") else JsonNull) }
                }
            val renamed = api.replaceRelease(firstId, releasePutBody("renamed", listOf(a), profile, JsonPrimitive("note\nline")))
            assertEquals(200, renamed.statusCode())
            val updated = renamed.jsonObject()
            assertEquals("renamed", updated.getValue("label").jsonPrimitive.content)
            assertEquals(profile, updated.getValue("profile"))
            assertEquals("note\nline", updated.getValue("notes").jsonPrimitive.content)
            listOf("series", "run_id", "release_id", "started_at", "created_at").forEach {
                assertEquals(original.getValue(it), updated.getValue(it), it)
            }
            assertTrue(updated.getValue("updated_at").jsonPrimitive.content >= original.getValue("updated_at").jsonPrimitive.content)

            // immutable fields are not part of the body
            val withSeries = JsonObject(releasePutBody("x", listOf(a)) + ("series" to JsonPrimitive("other")))
            assertError(api.replaceRelease(firstId, withSeries), 400, "MALFORMED_REQUEST")
            val withRun = JsonObject(releasePutBody("x", listOf(a)) + ("run_id" to JsonPrimitive(input.runId)))
            assertError(api.replaceRelease(firstId, withRun), 400, "MALFORMED_REQUEST")

            // a re-analysis of the same run (another policy) may replace the reference and keeps the test start
            val reanalyzed = api.replaceRelease(firstId, releasePutBody("renamed", listOf(c)))
            assertEquals(200, reanalyzed.statusCode())
            assertEquals(original.getValue("started_at"), reanalyzed.jsonObject().getValue("started_at"))
            assertEquals(
                c,
                reanalyzed
                    .jsonObject()
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("analysis_id")
                    .jsonPrimitive.content,
            )
            // an analysis that belongs to another release is refused; the own analysis passes
            assertError(api.replaceRelease(firstId, releasePutBody("renamed", listOf(b))), 409, "RELEASE_ANALYSIS_ALREADY_REGISTERED")
            assertEquals(200, api.replaceRelease(firstId, releasePutBody("again", listOf(c))).statusCode())
            // another test start is refused
            val shifted = writeSyntheticAnalysis(store, input.runId, "shifted", startedAt = "2026-01-01T00:00:09Z")
            assertError(api.replaceRelease(firstId, releasePutBody("x", listOf(shifted))), 422, "RELEASE_STARTED_AT_MISMATCH")

            assertError(api.replaceRelease("999999999999999-ffffffff", releasePutBody("x", listOf(a))), 404, "NOT_FOUND")
            assertError(api.replaceRelease("garbage", releasePutBody("x", listOf(a))), 400, "MALFORMED_REQUEST")
            Files.writeString(root.resolve("releases/$secondId.json"), "not json")
            assertError(api.replaceRelease(secondId, releasePutBody("x", listOf(b))), 500, "CORRUPT_RELEASE")
        }
    }

    @Test
    fun `delete removes only the record`() {
        val root = tempDir.resolve("release-delete")
        withServer(dataRoot = root) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            assertEquals(200, api.selectManual(input.runId, analysisId).statusCode())
            val baseline = api.get("/api/baseline").jsonObject()
            val id = api.createRelease(releaseBody(input.runId, listOf(analysisId))).newReleaseId()

            val deleted = api.delete("/api/releases/$id")
            assertEquals(200, deleted.statusCode())
            assertEquals(JsonNull, deleted.jsonObject().getValue("release"))
            assertTrue(store.readAnalysis(input.runId, analysisId) != null)
            assertEquals(baseline, api.get("/api/baseline").jsonObject())
            assertError(api.delete("/api/releases/$id"), 404, "NOT_FOUND")
            assertError(api.delete("/api/releases/999999999999999-ffffffff"), 404, "NOT_FOUND")
            assertError(api.get("/api/releases/$id"), 404, "NOT_FOUND")

            // a damaged record is removed by its identifier without being parsed
            val damaged = "001767225600000-0badc0de"
            Files.createDirectories(root.resolve("releases"))
            Files.writeString(root.resolve("releases/$damaged.json"), "not json")
            assertError(api.get("/api/releases/$damaged"), 500, "CORRUPT_RELEASE")
            assertEquals(200, api.delete("/api/releases/$damaged").statusCode())
            assertFalse(Files.exists(root.resolve("releases/$damaged.json")))
        }
    }

    @Test
    fun `registry limit and corruption are reported with the limit`() {
        val root = tempDir.resolve("release-limit")
        withServer(dataRoot = root) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            seedReleaseFiles(root, MAX_RELEASES)

            val full = api.createRelease(releaseBody(input.runId, listOf(analysisId)))
            assertEquals(422, full.statusCode())
            assertEquals("RELEASE_LIMIT_REACHED", full.errorCode())
            assertEquals(MAX_RELEASES, full.errorLimit())

            Files.writeString(root.resolve("releases/stray.txt"), "x")
            assertError(api.get("/api/releases"), 500, "CORRUPT_RELEASE_REGISTRY")
            assertError(api.createRelease(releaseBody(input.runId, listOf(analysisId))), 500, "CORRUPT_RELEASE_REGISTRY")
        }
    }

    @Test
    fun `a record that overflows 8 KiB after escaping is a clear refusal`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val quotes = "\"".repeat(MAX_RELEASE_TEXT_BYTES - 1)
            val ids = (0 until MAX_RELEASE_ANALYSES).map { writeSyntheticAnalysis(store, input.runId, "arm-$it", arm = "$quotes$it") }
            val field = JsonPrimitive(quotes + "p")
            val body =
                releaseBody(
                    input.runId,
                    ids,
                    series = quotes + "s",
                    label = quotes + "l",
                    profile = JsonObject(RELEASE_PROFILE_FIELDS.associateWith { field }),
                    notes = JsonPrimitive("\"".repeat(MAX_RELEASE_NOTES_BYTES)),
                )
            assertError(api.createRelease(body), 422, "RELEASE_TOO_LARGE")
            // the same eight analyses with short texts register fine
            assertEquals(201, api.createRelease(releaseBody(input.runId, ids)).statusCode())
        }

    @Test
    fun `concurrent replacements leave one valid record`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            val id = api.createRelease(releaseBody(input.runId, listOf(analysisId))).newReleaseId()
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val executor =
                java.util.concurrent.Executors
                    .newFixedThreadPool(2)
            try {
                val labels = listOf("first", "second")
                val responses =
                    labels
                        .map { label ->
                            executor.submit<HttpResponse<String>> {
                                barrier.await(10, TimeUnit.SECONDS)
                                api.replaceRelease(id, releasePutBody(label, listOf(analysisId)))
                            }
                        }.map { it.get(30, TimeUnit.SECONDS) }
                responses.filter { it.statusCode() != 200 }.forEach { assertError(it, 409, "RELEASE_CHANGED") }
                assertTrue(responses.any { it.statusCode() == 200 })
                assertTrue(
                    api
                        .get("/api/releases/$id")
                        .jsonObject()
                        .getValue("label")
                        .jsonPrimitive.content in labels,
                )
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun `errors do not echo user text`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val analysisId = api.createJob(input.runId, PERMISSIVE_POLICY).analysisId(api)
            assertEquals(201, api.createRelease(releaseBody(input.runId, listOf(analysisId))).statusCode())
            val marker = "zz-marker-${UUID.randomUUID()}"
            val profile =
                buildJsonObject {
                    RELEASE_PROFILE_FIELDS.forEach {
                        put(
                            it,
                            if (it ==
                                "pacing"
                            ) {
                                JsonPrimitive(marker)
                            } else {
                                JsonNull
                            },
                        )
                    }
                }
            val responses =
                listOf(
                    api.createRelease(
                        releaseBody(input.runId, listOf(analysisId), label = marker, profile = profile, notes = JsonPrimitive(marker)),
                    ),
                    api.createRelease(releaseBody(input.runId, listOf(analysisId), label = "$marker\u0007", profile = profile)),
                    api.createRelease(releaseBody(input.runId, listOf("f".repeat(64)), series = marker, label = marker, profile = profile)),
                )
            assertEquals(listOf(409, 400, 404), responses.map { it.statusCode() })
            responses.forEach { assertFalse(it.body().contains(marker), it.body()) }
        }

    @Test
    fun `two series keep independent baseline slots and comparison follows the series`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120, 130))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "A").statusCode())
            assertEquals(200, api.selectManual(runs[1].first, runs[1].second, "B").statusCode())

            val listed = api.get("/api/baseline").jsonObject()
            assertEquals(setOf("baseline", "baselines"), listed.keys)
            assertEquals(JsonNull, listed.getValue("baseline"))
            val slots = listed.getValue("baselines").jsonArray
            assertEquals(listOf("A/null", "B/null"), slots.map { it.slotName() })
            assertEquals(setOf("series", "arm", "source", "baseline"), slots[0].jsonObject.keys)
            assertEquals(
                listOf("SLOT", "SLOT"),
                slots.map {
                    it.jsonObject
                        .getValue("source")
                        .jsonPrimitive.content
                },
            )

            // rewriting one series leaves the other
            assertEquals(200, api.selectManual(runs[2].first, runs[2].second, "A").statusCode())
            val rewritten = api.slots()
            assertEquals(reference(runs[2].first, runs[2].second), rewritten[0].slotReference())
            assertEquals(reference(runs[1].first, runs[1].second), rewritten[1].slotReference())

            val (currentRun, currentAnalysis) = runs[3]
            val path = "/api/runs/$currentRun/analyses/$currentAnalysis/comparison"
            assertEquals(reference(runs[2].first, runs[2].second), api.comparisonReference("$path?series=A"))
            assertEquals(reference(runs[1].first, runs[1].second), api.comparisonReference("$path?series=B"))
            // unregistered and no series: only the legacy file is read
            assertError(api.get(path), 404, "NOT_FOUND")
            assertError(api.get("$path?series=missing"), 404, "NOT_FOUND")
            assertError(api.get("$path?series="), 400, "MALFORMED_REQUEST")
            assertError(api.get("$path?series=A&series=B"), 400, "MALFORMED_REQUEST")

            // the series of the release of the current analysis wins; a contradicting query is refused
            assertEquals(201, api.createRelease(releaseBody(currentRun, listOf(currentAnalysis), series = "B")).statusCode())
            assertEquals(reference(runs[1].first, runs[1].second), api.comparisonReference(path))
            assertEquals(200, api.get("$path?series=B").statusCode())
            assertError(api.get("$path?series=A"), 422, "BASELINE_SERIES_CONFLICT")
            val conditions = "/api/runs/$currentRun/analyses/$currentAnalysis/baseline-conditions"
            assertError(api.get("$conditions?series=A"), 422, "BASELINE_SERIES_CONFLICT")
            val confirm = """{"decision":"CONFIRMED"}""".encodeToByteArray()
            assertError(api.post("$conditions?series=A", "application/json", confirm), 422, "BASELINE_SERIES_CONFLICT")
        }

    @Test
    fun `a corrupt release registry does not break the comparison`() {
        val root = tempDir.resolve("baseline-registry")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "A").statusCode())
            Files.createDirectories(root.resolve("releases"))
            Files.writeString(root.resolve("releases/stray.txt"), "x")
            val path = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/comparison"

            assertEquals(200, api.get("$path?series=A").statusCode())
            assertError(api.get(path), 404, "NOT_FOUND")
        }
    }

    @Test
    fun `legacy file is a slot until its key is written and the bare delete clears only it`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120))
            val legacy = manualBaselineSelection("release", reference(runs[0].first, runs[0].second))
            store.replaceBaseline(legacy)

            val listed = api.get("/api/baseline").jsonObject()
            assertEquals(legacy, listed.getValue("baseline"))
            assertEquals(
                listOf("LEGACY"),
                listed.getValue("baselines").jsonArray.map {
                    it.jsonObject
                        .getValue("source")
                        .jsonPrimitive.content
                },
            )
            val path = "/api/runs/${runs[2].first}/analyses/${runs[2].second}/comparison"
            assertEquals(200, api.get(path).statusCode())
            assertEquals(200, api.get("$path?series=release").statusCode())

            // writing the key of the legacy file replaces it: the file is gone
            assertEquals(200, api.selectManual(runs[1].first, runs[1].second).statusCode())
            val replaced = api.get("/api/baseline").jsonObject()
            assertEquals(JsonNull, replaced.getValue("baseline"))
            assertEquals(
                listOf("SLOT"),
                replaced.getValue("baselines").jsonArray.map {
                    it.jsonObject
                        .getValue("source")
                        .jsonPrimitive.content
                },
            )
            assertError(api.get(path), 404, "NOT_FOUND")

            // the bare delete clears the legacy file and no slot
            store.replaceBaseline(manualBaselineSelection("old", reference(runs[0].first, runs[0].second)))
            assertEquals(JsonNull, api.delete("/api/baseline").jsonObject().getValue("baseline"))
            assertEquals(listOf("release/null"), api.slots().map { it.slotName() })
            assertEquals(JsonNull, api.get("/api/baseline").jsonObject().getValue("baseline"))
            assertEquals(JsonNull, api.delete("/api/baseline").jsonObject().getValue("baseline"))
        }

    @Test
    fun `an addressed delete removes one slot and keeps conditions another slot uses`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120))
            val (currentRun, currentAnalysis) = runs[2]
            val conditions = "/api/runs/$currentRun/analyses/$currentAnalysis/baseline-conditions"
            val confirm = """{"decision":"CONFIRMED"}""".encodeToByteArray()
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "A").statusCode())
            assertEquals(200, api.selectManual(runs[1].first, runs[1].second, "B").statusCode())
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "C").statusCode())
            assertEquals(200, api.post("$conditions?series=A", "application/json", confirm).statusCode())
            assertEquals(200, api.post("$conditions?series=B", "application/json", confirm).statusCode())

            assertError(api.delete("/api/baseline?arm=blue"), 400, "MALFORMED_REQUEST")
            assertError(api.delete("/api/baseline?series="), 400, "MALFORMED_REQUEST")
            assertError(api.delete("/api/baseline?series=A&arm="), 400, "MALFORMED_REQUEST")
            assertError(api.delete("/api/baseline?series=A&series=B"), 400, "MALFORMED_REQUEST")
            assertError(api.delete("/api/baseline?extra=1"), 400, "MALFORMED_REQUEST")
            assertEquals(3, api.slots().size)

            // A and C share one baseline reference and one pair record: it stays for C
            assertEquals(JsonNull, api.delete("/api/baseline?series=A").jsonObject().getValue("baseline"))
            assertEquals(listOf("B/null", "C/null"), api.slots().map { it.slotName() })
            assertEquals("CONFIRMED", api.conditionDecision("$conditions?series=C"))
            assertEquals("CONFIRMED", api.conditionDecision("$conditions?series=B"))

            // the last user of a reference takes its records along
            assertEquals(200, api.delete("/api/baseline?series=C").statusCode())
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "C").statusCode())
            assertEquals(JsonNull, api.get("$conditions?series=C").jsonObject().getValue("conditions"))
            assertEquals("CONFIRMED", api.conditionDecision("$conditions?series=B"))

            // deleting what is not there is not an error, and the arm names a different slot
            assertEquals(200, api.delete("/api/baseline?series=missing").statusCode())
            assertEquals(200, api.delete("/api/baseline?series=B&arm=blue").statusCode())
            assertEquals(listOf("B/null", "C/null"), api.slots().map { it.slotName() })
            assertEquals(200, api.delete("/api/baseline?series=B").statusCode())
            assertEquals(listOf("C/null"), api.slots().map { it.slotName() })
        }

    @Test
    fun `the slot limit is 64 and replacing a key at the limit still works`() =
        withServer { store, api ->
            api.bootstrap()
            val (run, analysis) = statisticalRuns(store, api, listOf(100)).single()
            repeat(64) { assertEquals(200, api.selectManual(run, analysis, "series-$it").statusCode()) }
            assertEquals(64, api.slots().size)

            val refused = api.selectManual(run, analysis, "series-64")
            assertEquals(422, refused.statusCode())
            assertEquals("BASELINE_SLOTS_LIMIT_REACHED", refused.errorCode())
            assertEquals(64, refused.errorLimit())
            assertEquals(64, api.slots().size)
            assertEquals(200, api.selectManual(run, analysis, "series-3").statusCode())
            assertEquals(200, api.delete("/api/baseline?series=series-0").statusCode())
            assertEquals(200, api.selectManual(run, analysis, "series-64").statusCode())
        }

    @Test
    fun `series are normalized so padded and trimmed names are one slot`() =
        withServer { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110, 120))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, " Checkout ").statusCode())
            assertEquals(200, api.selectManual(runs[1].first, runs[1].second, "Checkout").statusCode())
            assertEquals(1, api.slots().size)
            assertEquals(
                "Checkout",
                api
                    .slots()
                    .single()
                    .jsonObject
                    .getValue("series")
                    .jsonPrimitive.content,
            )
            assertEquals(reference(runs[1].first, runs[1].second), api.slots().single().slotReference())
            assertError(api.selectManual(runs[0].first, runs[0].second, "   "), 400, "MALFORMED_REQUEST")
            // a series the query could not name again is refused when it is written
            assertError(api.selectManual(runs[0].first, runs[0].second, "A\\tB"), 400, "MALFORMED_REQUEST")
            assertError(api.selectManual(runs[0].first, runs[0].second, "x".repeat(129)), 400, "MALFORMED_REQUEST")
            val path = "/api/runs/${runs[2].first}/analyses/${runs[2].second}/comparison"
            assertEquals(200, api.get("$path?series=%20Checkout%20").statusCode())
            assertEquals(200, api.delete("/api/baseline?series=%20Checkout").statusCode())
            assertEquals(0, api.slots().size)
        }

    @Test
    fun `the arm of the baseline analysis keys the slot and another arm finds no baseline`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()
            val blue = writeSyntheticAnalysis(store, input.runId, "blue", arm = "blue")
            val plain = writeSyntheticAnalysis(store, input.runId, "plain")
            val green = writeSyntheticAnalysis(store, input.runId, "other-arm", arm = "green")
            assertEquals(200, api.selectManual(input.runId, blue, "S").statusCode())

            val slot = api.slots().single()
            assertEquals(
                "blue",
                slot.jsonObject
                    .getValue("arm")
                    .jsonPrimitive.content,
            )
            assertEquals("S/blue", slot.slotName())

            assertEquals(201, api.createRelease(releaseBody(input.runId, listOf(plain), series = "S")).statusCode())
            assertError(api.get("/api/runs/${input.runId}/analyses/$plain/comparison"), 404, "NOT_FOUND")
            assertError(api.get("/api/runs/${input.runId}/analyses/$green/comparison?series=S"), 404, "NOT_FOUND")

            // the same series at another arm is another slot, and the arm names the slot to delete
            assertEquals(200, api.selectManual(input.runId, plain, "S").statusCode())
            assertEquals(listOf("S/blue", "S/null"), api.slots().map { it.slotName() }.sorted())
            assertEquals(200, api.delete("/api/baseline?series=S&arm=blue").statusCode())
            assertEquals(listOf("S/null"), api.slots().map { it.slotName() })
        }

    @Test
    fun `a full condition directory is refused with its limit`() {
        val root = tempDir.resolve("baseline-conditions-limit")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second).statusCode())
            val directory = Files.createDirectories(root.resolve("baseline-conditions"))
            repeat(4_096) { Files.writeString(directory.resolve("%064x.json".format(it)), "{}") }

            val path = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/baseline-conditions?series=release"
            val refused = api.post(path, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray())
            assertEquals(422, refused.statusCode())
            assertEquals("BASELINE_CONDITIONS_LIMIT_REACHED", refused.errorCode())
            assertEquals(4_096, refused.errorLimit())
        }
    }

    @Test
    fun `a damaged slot fails closed on every baseline route`() {
        val root = tempDir.resolve("baseline-damaged-slot")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "A").statusCode())
            val file = Files.list(root.resolve("baselines")).use { it.toList().single() }
            Files.writeString(file, "not json")
            val path = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/comparison?series=A"

            assertError(api.get("/api/baseline"), 500, "CORRUPT_BASELINE")
            assertError(api.get(path), 500, "CORRUPT_BASELINE")
            // without a series only the legacy file is read, so a damaged slot of another series does not reach analytics
            val analytics = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/analytics"
            assertEquals(200, api.get(analytics).statusCode())
            assertError(api.get("$analytics?series=A"), 500, "CORRUPT_BASELINE")
            assertError(api.delete("/api/baseline?series=A"), 500, "CORRUPT_BASELINE")
            assertError(api.selectManual(runs[1].first, runs[1].second, "B"), 500, "CORRUPT_BASELINE")
        }
    }

    @Test
    fun `saved analytics compares transactions against the slot of the analysis series`() {
        val root = tempDir.resolve("baseline-analytics")
        withServer(dataRoot = root) { store, api ->
            api.bootstrap()
            val runs = statisticalRuns(store, api, listOf(100, 110))
            val path = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/analytics"
            assertEquals(200, api.selectManual(runs[0].first, runs[0].second, "A").statusCode())

            assertEquals(JsonNull, api.get(path).jsonObject().getValue("transactions"))
            assertTrue(api.get("$path?series=A").jsonObject().getValue("transactions") !is JsonNull)
            assertEquals(JsonNull, api.get("$path?series=B").jsonObject().getValue("transactions"))
            assertEquals(201, api.createRelease(releaseBody(runs[1].first, listOf(runs[1].second), series = "A")).statusCode())
            assertTrue(api.get(path).jsonObject().getValue("transactions") !is JsonNull)
            assertError(api.get("$path?series=B"), 422, "BASELINE_SERIES_CONFLICT")

            // analytics does not read the condition record: a damaged one fails the comparison, not the analytics
            val conditions = "/api/runs/${runs[1].first}/analyses/${runs[1].second}/baseline-conditions?series=A"
            assertEquals(200, api.post(conditions, "application/json", """{"decision":"CONFIRMED"}""".encodeToByteArray()).statusCode())
            Files.list(root.resolve("baseline-conditions")).use { it.toList().single() }.let { Files.writeString(it, "not json") }
            assertError(api.get(conditions), 500, "CORRUPT_BASELINE")
            assertTrue(api.get(path).jsonObject().getValue("transactions") !is JsonNull)
        }
    }

    private fun statisticalRuns(
        store: RunBundleStore,
        api: ApiClient,
        elapsed: List<Int>,
        policy: ByteArray? = PERMISSIVE_POLICY,
    ): List<Pair<String, String>> =
        elapsed.mapIndexed { index, value ->
            val load = "timeStamp,elapsed,label,success\n${1_767_225_600_000L + index * 1_000L},$value,checkout,true\n"
            val input = store.acceptInput(ByteArrayInputStream(load.encodeToByteArray()), "series-$index.jtl")
            input.runId to api.createJob(input.runId, policy).analysisId(api)
        }

    private fun statisticalBody(
        candidates: List<Pair<String, String>>,
        comparable: Boolean,
    ): ByteArray {
        val references = candidates.joinToString(",") { (run, analysis) -> """{"run_id":"$run","analysis_id":"$analysis"}""" }
        return """{"mode":"statistical","series":"release","candidates":[$references],"comparable":$comparable}""".encodeToByteArray()
    }

    private fun ApiClient.selectManual(
        run: String,
        analysis: String,
        series: String = "release",
    ): HttpResponse<String> =
        post(
            "/api/baseline",
            "application/json",
            """{"mode":"manual","series":"$series","reference":{"run_id":"$run","analysis_id":"$analysis"}}""".encodeToByteArray(),
        )

    private fun ApiClient.slots(): JsonArray = get("/api/baseline").jsonObject().getValue("baselines").jsonArray

    private fun ApiClient.comparisonReference(path: String): JsonObject =
        get(path)
            .jsonObject()
            .getValue("baseline")
            .jsonObject
            .getValue("reference")
            .jsonObject

    private fun ApiClient.conditionDecision(path: String): String =
        get(path)
            .jsonObject()
            .getValue("conditions")
            .jsonObject
            .getValue("decision")
            .jsonPrimitive.content

    private fun JsonElement.slotName(): String =
        jsonObject.let { "${it.getValue("series").jsonPrimitive.content}/${it.getValue("arm").jsonPrimitive.contentOrNull}" }

    private fun JsonElement.slotReference(): JsonObject =
        jsonObject
            .getValue("baseline")
            .jsonObject
            .getValue("reference")
            .jsonObject

    private fun reference(
        run: String,
        analysis: String,
    ): JsonObject =
        buildJsonObject {
            put("run_id", run)
            put("analysis_id", analysis)
        }

    private fun HttpResponse<String>.errorMessage(): String =
        jsonObject()
            .getValue("error")
            .jsonObject
            .getValue("message")
            .jsonPrimitive.content

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
        series: String = "release",
    ): JsonObject = get("/api/runs/$run/analyses/$analysis/comparison?series=$series").jsonObject()

    // The one active baseline of the store, from the slot list; JsonNull when there is none.
    private fun ApiClient.activeBaseline(): JsonElement =
        get("/api/baseline")
            .jsonObject()
            .getValue("baselines")
            .jsonArray
            .singleOrNull()
            ?.jsonObject
            ?.getValue("baseline") ?: JsonNull

    private fun JsonObject.warnings(): List<String> = getValue("warnings").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `private API uploads validates analyzes and returns exact result and bucket pages`() =
        withServer { store, api ->
            val bootstrap = api.bootstrap()
            assertEquals(200, bootstrap.statusCode())
            val bootstrapJson = bootstrap.jsonObject()
            assertEquals(setOf("csrf_token", "max_upload_bytes", "advisory_ai"), bootstrapJson.keys)
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
    fun `a platform rule policy with an SLA snapshot rule is rejected before a job exists`() {
        val submissions = AtomicInteger()
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                submissions.incrementAndGet()
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":2000}],"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,"min_consecutive_cells":1,"effect":"sla"}]}"""
                    .encodeToByteArray()
            val policy =
                """{"schema_version":"policy.v1","policy_id":"conflict","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}],"platform_rules":[{"id":"cpu","signal":"cpu_used","scope":{"kind":"service","services":["vm"]},"operator":"gt","threshold":0.8,"unit":"ratio","aggregation":"interval_mean","min_consecutive_cells":1,"effect":"diagnostic"}]}"""
                    .encodeToByteArray()
            api.bootstrap()

            val response = api.createJob(input.runId, policy, resources)

            assertEquals(422, response.statusCode())
            assertTrue(response.body().contains("PLATFORM_RULES_CONFLICT"), response.body())
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
    fun `pod view is validated and bound before job submission and reaches the analysis request`() {
        val requests = mutableListOf<AnalysisRequest>()
        withServer(jobsFactory = {
            AnalysisJobs(1) { request, _, _ ->
                requests += request
                AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
            }
        }) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":10,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1,0.1]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":10000}]}"""
                    .encodeToByteArray()
            val hash = (validateResourceSnapshot(resources.inputStream()) as ResourceValidation.Valid).semanticSha256

            fun view(
                loadHash: String = input.sha256,
                snapshotHash: String = hash,
                arm: String? = null,
                start: Long = 0,
                step: Long = 5_000,
                columns: Int = 2,
            ) = podViewTestJson(loadHash, snapshotHash, arm, start, step, columns).encodeToByteArray()

            fun refused(
                response: HttpResponse<String>,
                code: String,
            ) {
                assertError(response, 422, "INVALID_POD_VIEW", hasDetails = true)
                assertTrue(response.body().contains(code), response.body())
            }
            api.bootstrap()
            refused(api.createJob(input.runId, podView = view()), "POD_VIEW_RESOURCE_REQUIRED")
            refused(api.createJob(input.runId, resources = resources, podView = "{}".encodeToByteArray()), "POD_VIEW_INVALID")
            refused(api.createJob(input.runId, resources = resources, podView = view(loadHash = "f".repeat(64))), "POD_VIEW_INPUT_MISMATCH")
            refused(
                api.createJob(input.runId, resources = resources, podView = view(snapshotHash = "e".repeat(64))),
                "POD_VIEW_SNAPSHOT_MISMATCH",
            )
            refused(api.createJob(input.runId, resources = resources, podView = view(arm = "A")), "POD_VIEW_ARM_MISMATCH")
            refused(api.createJob(input.runId, resources = resources, podView = view(start = 1_000)), "POD_VIEW_GRID_MISMATCH")
            refused(api.createJob(input.runId, resources = resources, podView = view(columns = 3)), "POD_VIEW_GRID_MISMATCH")
            // A limit hit is a 413 like every other oversized part; nothing is truncated.
            assertError(api.createJob(input.runId, resources = resources, podView = ByteArray(12 * 1024 * 1024 + 1) { 32 }), 413)
            assertEquals(0, requests.size)
            assertEquals(0, store.listAnalyses(input.runId, null, 10).analyses.size)

            val exact = ByteArray(12 * 1024 * 1024) { ' '.code.toByte() }.also { view().copyInto(it) }
            assertEquals(FAKE_ANALYSIS_ID, api.createJob(input.runId, resources = resources, podView = exact).analysisId(api))
            assertEquals(1, requests.size)
            val submitted = checkNotNull(requests.single().podView)
            val expected = validatePodView(view().inputStream()) as PodViewValidation.Valid
            assertEquals(expected.canonicalSha256, submitted.canonicalSha256)
            assertEquals(
                FAKE_ANALYSIS_ID,
                api.createJob(input.runId, Files.readAllBytes(Path.of(PASS_POLICY)), resources, podView = view()).analysisId(api),
            )
            assertError(
                api.multipart(
                    "/api/jobs",
                    listOf(
                        FormPart("run_id", input.runId.encodeToByteArray()),
                        FormPart("resource_snapshot", resources, "resources.json", "application/json"),
                        FormPart("pod_view", view(), "pod-view.json", "application/json"),
                        FormPart("pod_view", view(), "pod-view.json", "application/json"),
                    ),
                ),
                400,
                "MALFORMED_REQUEST",
            )
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `pod view cannot be combined with an online source request and a job may have 25 parts`() {
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            api.bootstrap()

            fun messageOf(response: HttpResponse<String>) =
                response
                    .jsonObject()
                    .getValue("error")
                    .jsonObject
                    .getValue("message")
                    .jsonPrimitive.content

            val view = podViewTestJson("a".repeat(64), "b".repeat(64), null, 0, 5_000, 2).encodeToByteArray()
            val online = api.createJob(input.runId, podView = view, source = ONLINE_SOURCE_REQUEST.encodeToByteArray())
            assertError(online, 400, "MALFORMED_REQUEST")
            assertEquals("Online acquisition cannot be combined with manual source inputs", messageOf(online))

            fun junkParts(count: Int) =
                listOf(FormPart("run_id", input.runId.encodeToByteArray())) +
                    List(count - 1) { FormPart("unknown_$it", ByteArray(1), "x.bin", "application/octet-stream") }
            // 25 parts pass the counter (the unknown names fail the body at the end); the 26th is one too many.
            assertEquals("Job multipart body is invalid", messageOf(api.multipart("/api/jobs", junkParts(25))))
            assertEquals("Job multipart body has too many parts", messageOf(api.multipart("/api/jobs", junkParts(26))))
        }
    }

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
            assertEquals(setOf("analysis_id", "policy_sha256", "policy_id", "policy_verdict", "run_validity"), firstSummary.keys)
            assertEquals(ids.first(), firstSummary.getValue("analysis_id").jsonPrimitive.content)
            assertEquals(policies.getValue(ids.first()), firstSummary.getValue("policy_sha256").jsonPrimitive.content)
            assertEquals(JsonNull, firstSummary.getValue("policy_id"))
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
            assertEquals(
                JsonNull,
                second
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("policy_id"),
            )
            assertError(api.get("/api/runs/${input.runId}/analyses?limit=0"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/${input.runId}/analyses?after=invalid"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/${input.runId}/analyses?after=${ids.first()}&after=${ids.last()}"), 400, "MALFORMED_REQUEST")
            assertError(api.get("/api/runs/jmeter_jtl_csv-${"0".repeat(64)}/analyses"), 404, "NOT_FOUND")
        }

    @Test
    fun `analysis list exposes the resource arm and snapshot hash only when the identity has them`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)

            fun publish(
                suffix: String,
                extra: String,
            ): String {
                val identity =
                    "{\"policy_sha256\":\"NO_POLICY\",\"run_id\":\"${input.runId}\",\"suffix\":\"$suffix\"$extra}"
                        .encodeToByteArray()
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
            val hashA = "a".repeat(64)
            val hashB = "b".repeat(64)
            val armA = publish("1", ",\"resource_arm\":\"A\",\"resource_snapshot_sha256\":\"$hashA\"")
            val armB = publish("2", ",\"resource_arm\":\"B\",\"resource_snapshot_sha256\":\"$hashB\"")
            val noArm = publish("3", ",\"resource_snapshot_sha256\":\"$hashA\"")
            val noResources = publish("4", "")
            api.bootstrap()

            val items =
                api
                    .get("/api/runs/${input.runId}/analyses")
                    .jsonObject()
                    .getValue("analyses")
                    .jsonArray
                    .associate {
                        it.jsonObject
                            .getValue("analysis_id")
                            .jsonPrimitive.content to it.jsonObject
                    }
            assertEquals(4, items.size)
            assertEquals(
                "A",
                items
                    .getValue(armA)
                    .getValue("resource_arm")
                    .jsonPrimitive.content,
            )
            assertEquals(
                hashA,
                items
                    .getValue(armA)
                    .getValue("resource_snapshot_sha256")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "B",
                items
                    .getValue(armB)
                    .getValue("resource_arm")
                    .jsonPrimitive.content,
            )
            assertEquals(
                hashB,
                items
                    .getValue(armB)
                    .getValue("resource_snapshot_sha256")
                    .jsonPrimitive.content,
            )
            assertFalse(items.getValue(noArm).containsKey("resource_arm"))
            assertEquals(
                hashA,
                items
                    .getValue(noArm)
                    .getValue("resource_snapshot_sha256")
                    .jsonPrimitive.content,
            )
            assertEquals(
                setOf("analysis_id", "policy_sha256", "policy_id", "policy_verdict", "run_validity"),
                items.getValue(noResources).keys,
            )
        }

    @Test
    fun `analysis list refuses an identity with a non-string arm`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val identity = "{\"policy_sha256\":\"NO_POLICY\",\"run_id\":\"${input.runId}\",\"resource_arm\":1}".encodeToByteArray()
            store.writeAnalysisAtomically(input.runId, sha256Hex(identity)) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                Files.writeString(staging.resolve("analysis-result.json"), "{\"policy_verdict\":\"PASS\",\"run_validity\":\"VALID\"}")
            }
            api.bootstrap()

            val response = api.get("/api/runs/${input.runId}/analyses")
            assertEquals(500, response.statusCode())
        }

    @Test
    fun `analysis list identifies each policy and includes null without policy`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val policy = Files.readString(Path.of(PASS_POLICY))
            api.bootstrap()

            val first = api.createJob(input.runId, policy.replace("slice1-pass", "xyz-policy").encodeToByteArray()).analysisId(api)
            val second = api.createJob(input.runId, policy.replace("slice1-pass", "abc-policy").encodeToByteArray()).analysisId(api)
            val without = api.createJob(input.runId).analysisId(api)

            val response = api.get("/api/runs/${input.runId}/analyses")
            assertEquals(200, response.statusCode())
            val summaries =
                response.jsonObject().getValue("analyses").jsonArray.associate { item ->
                    val summary = item.jsonObject
                    summary.getValue("analysis_id").jsonPrimitive.content to summary.getValue("policy_id")
                }
            assertEquals(3, summaries.size)
            assertEquals("xyz-policy", summaries.getValue(first).jsonPrimitive.content)
            assertEquals("abc-policy", summaries.getValue(second).jsonPrimitive.content)
            assertEquals(JsonNull, summaries.getValue(without))
        }

    @Test
    fun `analysis list ignores a tampered policy artifact`() =
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val policy = Files.readString(Path.of(PASS_POLICY)).replace("slice1-pass", "xyz-policy")
            api.bootstrap()
            val id = api.createJob(input.runId, policy.encodeToByteArray()).analysisId(api)
            val stored = store.readAnalysis(input.runId, id)!!
            val path = stored.path.resolve("policy.json")
            val original = Files.readString(path)
            val changed = original.replace("xyz-policy", "abc-policy")
            assertEquals(original.encodeToByteArray().size, changed.encodeToByteArray().size)
            Files.writeString(path, changed)

            val response = api.get("/api/runs/${input.runId}/analyses")
            assertEquals(200, response.statusCode())
            assertEquals(
                JsonNull,
                response
                    .jsonObject()
                    .getValue("analyses")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("policy_id"),
            )
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
        aiModels: AiModelsConfig? = null,
        uploadLimitBytes: Long = 4_294_967_296L,
        dataRoot: Path = tempDir.resolve("data-${System.nanoTime()}"),
        block: (RunBundleStore, ApiClient) -> Unit,
    ) {
        DataDirectory.open(dataRoot).use { directory ->
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
                            aiModels = aiModels,
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
            podView: ByteArray? = null,
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
                    if (podView != null) add(FormPart("pod_view", podView, "pod-view.json", "application/json"))
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

        fun put(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(
                authenticated(request(path))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(body)),
            )

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

        // Few samples per run: the floor and minimum are lowered so a run is judged (PASS or FAIL), not marked small-sample.
        val PERMISSIVE_POLICY =
            """{"schema_version":"policy.v1","policy_id":"permissive","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
                .encodeToByteArray()
        val SMALL_SAMPLE_POLICY =
            """{"schema_version":"policy.v1","policy_id":"small-sample","defaults":{"sample_floor":1,"min_samples":1000000},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
                .encodeToByteArray()
        val FAILING_POLICY =
            """{"schema_version":"policy.v1","policy_id":"failing","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1,"scope":{"kind":"overall"}}]}"""
                .encodeToByteArray()
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

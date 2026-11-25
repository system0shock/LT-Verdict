package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.StageProfiles
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Plan W4.1, task 6: `POST /api/runs/{runId}/stage-proposal` (ADR 0036, D10, D12, D14). */
class LocalApiStageProposalTest {
    @TempDir
    lateinit var tempDir: Path

    private val anchorLoad = Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))
    private val anchorExample = Files.readAllBytes(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"))

    @Test
    fun `a stored run gets a proposal`() =
        withServer(anchorLoad) { _, api, runId ->
            val response = api.post("/api/runs/$runId/stage-proposal")

            assertEquals(200, response.statusCode(), response.body())
            val body = response.json()
            assertEquals("DETECTED", body.getValue("status").jsonPrimitive.content)
            assertEquals("rps-plateau-segmentation.v2", body.getValue("method").jsonPrimitive.content)
            assertEquals("top", body.getValue("steady_steps").jsonPrimitive.content)
            assertEquals("HIGH", body.getValue("confidence").jsonPrimitive.content)
            assertEquals(false, body.getValue("calibrated").jsonPrimitive.boolean)
            assertEquals(JsonNull, body.getValue("reason"))
            assertEquals(JsonNull, body.getValue("detail"))
            assertEquals(5_000L, body.getValue("cell_ms").jsonPrimitive.long)
            assertEquals(23L, body.getValue("cells").jsonPrimitive.long)
            assertFalse(body.getValue("noise_rps").jsonPrimitive.isString)
            assertEquals(0.2, body.getValue("noise_rps").jsonPrimitive.double)

            val steadyBoundary =
                body
                    .getValue("boundaries")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("stage_id").jsonPrimitive.content == "steady" }
            assertFalse(steadyBoundary.getValue("from_contrast_sigma").jsonPrimitive.isString)
            assertEquals(40.0, steadyBoundary.getValue("from_contrast_sigma").jsonPrimitive.double)
            assertEquals(40.0, steadyBoundary.getValue("to_contrast_sigma").jsonPrimitive.double)
            assertEquals(0L, steadyBoundary.getValue("trimmed_cells").jsonPrimitive.long)
            assertTrue(body.getValue("warnings").jsonArray.isEmpty())
            assertTrue(body.getValue("stretches").jsonArray.isEmpty())
            assertTrue(body.getValue("segments").jsonArray.size == 3)
            assertEquals(
                Json.parseToJsonElement(anchorExample.decodeToString()).jsonObject,
                body.getValue("proposal").jsonObject,
            )
        }

    @Test
    fun `a refusal is a 200 with a reason`() {
        val f4Path = StageProfiles.f4.writeJtl(tempDir.resolve("f4.jtl"))
        val f4Bytes = Files.readAllBytes(f4Path)
        withServer(f4Bytes) { _, api, runId ->
            val response = api.post("/api/runs/$runId/stage-proposal")

            assertEquals(200, response.statusCode(), response.body())
            val body = response.json()
            assertEquals("NOT_DETECTED", body.getValue("status").jsonPrimitive.content)
            assertEquals("NO_PLATEAU", body.getValue("reason").jsonPrimitive.content)
            val detail = body.getValue("detail").jsonPrimitive.content
            assertTrue(detail.startsWith("steady_share="), response.body())
            assertEquals(JsonNull, body.getValue("confidence"))
            assertEquals(false, body.getValue("calibrated").jsonPrimitive.boolean)
            assertEquals(JsonNull, body.getValue("proposal"))
            assertTrue(body.getValue("boundaries").jsonArray.isEmpty())
            assertTrue(body.getValue("warnings").jsonArray.isEmpty())
            assertTrue(body.getValue("stretches").jsonArray.isEmpty())
            assertTrue(body.getValue("segments").jsonArray.isNotEmpty())
        }
    }

    @Test
    fun `an unknown or malformed run is 404`() =
        withServer(anchorLoad) { _, api, runId ->
            val unknown = api.post("/api/runs/jmeter_jtl_csv-${"0".repeat(64)}/stage-proposal")
            val malformed = api.post("/api/runs/not-a-run-id/stage-proposal")

            assertEquals(404, unknown.statusCode(), unknown.body())
            assertEquals("NOT_FOUND", unknown.json().errorCode())
            assertEquals(404, malformed.statusCode(), malformed.body())
            assertEquals("NOT_FOUND", malformed.json().errorCode())

            // Permit is unaffected by 404s.
            assertEquals(200, api.post("/api/runs/$runId/stage-proposal").statusCode())
        }

    @Test
    fun `a second request while one runs is 409 BUSY`() {
        val permit = Semaphore(1)
        withServer(anchorLoad, permit) { _, api, runId ->
            permit.acquire()
            try {
                val busy = api.post("/api/runs/$runId/stage-proposal")
                assertEquals(409, busy.statusCode(), busy.body())
                assertEquals("BUSY", busy.json().errorCode())
                assertEquals(
                    "Stage proposal is already running",
                    busy
                        .json()
                        .getValue("error")
                        .jsonObject
                        .getValue("message")
                        .jsonPrimitive.content,
                )
            } finally {
                permit.release()
            }
            assertEquals(200, api.post("/api/runs/$runId/stage-proposal").statusCode())
        }
    }

    @Test
    fun `the route needs mutation credentials`() =
        withServer(anchorLoad) { _, api, runId ->
            val path = "/api/runs/$runId/stage-proposal"
            assertEquals(403, api.postMissingCsrf(path).statusCode())
            assertEquals(403, api.postMissingCookie(path).statusCode())
            assertEquals(403, api.postWrongOrigin(path).statusCode())
        }

    @Test
    fun `the proposal is not stored`() =
        withServer(anchorLoad) { store, api, runId ->
            val beforeAnalyses = store.listAnalyses(runId, null, 100).analyses
            val beforeEntries = Files.walk(tempDir).use { stream -> stream.map { tempDir.relativize(it).toString() }.sorted().toList() }

            val response = api.post("/api/runs/$runId/stage-proposal")

            assertEquals(200, response.statusCode(), response.body())
            val afterAnalyses = store.listAnalyses(runId, null, 100).analyses
            val afterEntries = Files.walk(tempDir).use { stream -> stream.map { tempDir.relativize(it).toString() }.sorted().toList() }
            assertEquals(beforeAnalyses, afterAnalyses)
            assertEquals(beforeEntries, afterEntries)
        }

    @Test
    fun `unexpected query parameters are 400`() =
        withServer(anchorLoad) { _, api, runId ->
            val response = api.post("/api/runs/$runId/stage-proposal?foo=1")

            assertEquals(400, response.statusCode(), response.body())
            assertEquals("MALFORMED_REQUEST", response.json().errorCode())
        }

    @Test
    fun `a degraded stretch appears in stretches and warnings`() {
        val collapsePath = StageProfiles.shortCollapse(1L).writeJtl(tempDir.resolve("collapse.jtl"))
        val collapseBytes = Files.readAllBytes(collapsePath)
        withServer(collapseBytes) { _, api, runId ->
            val response = api.post("/api/runs/$runId/stage-proposal")

            assertEquals(200, response.statusCode(), response.body())
            val body = response.json()
            assertEquals("DETECTED", body.getValue("status").jsonPrimitive.content)
            assertEquals("LOW", body.getValue("confidence").jsonPrimitive.content)
            val stretches = body.getValue("stretches").jsonArray.map { it.jsonObject }
            assertTrue(stretches.any { it.getValue("verdict").jsonPrimitive.content == "DEGRADED" }, response.body())
            val degradedWarning =
                body
                    .getValue("warnings")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("code").jsonPrimitive.content == "DEGRADED_TAIL_KEPT" }
            val stretch = degradedWarning.getValue("stretch").jsonObject
            assertEquals("DEGRADED", stretch.getValue("verdict").jsonPrimitive.content)
            assertFalse(stretch.getValue("error_ratio").jsonPrimitive.isString)
            assertFalse(stretch.getValue("mean_ms").jsonPrimitive.isString)
        }
    }

    @Test
    fun `submitted proposal produces the same analysis_id as CLI stages`() =
        withServer(anchorLoad) { store, api, runId ->
            val proposalResponse = api.post("/api/runs/$runId/stage-proposal")
            assertEquals(200, proposalResponse.statusCode())
            val proposalJson = proposalResponse.json().getValue("proposal").jsonObject
            val proposalBytes = proposalJson.toString().encodeToByteArray()

            val job = api.createJob(runId, PASSING, proposalBytes)
            assertEquals(202, job.statusCode(), job.body())
            val apiAnalysisId = complete(api, job)

            // Direct AnalysisService call with the canonical example (same as `ltv analyze --stages`).
            val cliData = tempDir.resolve("cli-data")
            val cliAnalysisId =
                DataDirectory.open(cliData).use { dir ->
                    val cliStore = RunBundleStore(dir)
                    val accepted = cliStore.acceptInput(anchorLoad.inputStream(), "anchor.jtl")
                    val policyValidation =
                        io.ltverdict.core.validatePolicy(PASSING.inputStream()) as io.ltverdict.core.PolicyValidation.Valid
                    val stagesValidation =
                        io.ltverdict.core.validateLoadStages(anchorExample.inputStream()) as io.ltverdict.core.LoadStagesValidation.Valid
                    val service = AnalysisService(cliStore, EngineConfig())
                    val result =
                        service.analyze(
                            io.ltverdict.core.AnalysisRequest(
                                accepted,
                                policyValidation,
                                stages = stagesValidation,
                            ),
                        )
                    result.analysisId
                }

            assertEquals(cliAnalysisId, apiAnalysisId)
            assertTrue(store.readAnalysis(runId, apiAnalysisId) != null)
        }

    private fun complete(
        api: ApiClient,
        accepted: HttpResponse<String>,
    ): String {
        val jobId =
            accepted
                .json()
                .getValue("job_id")
                .jsonPrimitive.content
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val status = api.get("/api/jobs/$jobId").json()
            when (val state = status.getValue("state").jsonPrimitive.content) {
                "COMPLETE" -> return status.getValue("analysis_id").jsonPrimitive.content
                "FAILED", "CANCELLED" -> fail<Unit>("job ended as $state: $status")
            }
            LockSupport.parkNanos(1_000_000)
        }
        return fail("job did not complete: $jobId")
    }

    private fun withServer(
        loadBytes: ByteArray,
        permit: Semaphore = Semaphore(1),
        block: (RunBundleStore, ApiClient, String) -> Unit,
    ) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs, stageProposalPermit = permit), openBrowser = false).use { server ->
                    val api = ApiClient(server.origin)
                    api.bootstrap()
                    val upload = api.upload(loadBytes, "load.jtl")
                    assertEquals(201, upload.statusCode(), upload.body())
                    block(store, api, "jmeter_jtl_csv-${sha256Hex(loadBytes)}")
                }
            }
        }
    }

    private fun HttpResponse<String>.json(): JsonObject = Json.parseToJsonElement(body()).jsonObject

    private fun JsonObject.errorCode(): String =
        getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private class ApiClient(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private lateinit var cookie: String
        private lateinit var csrf: String

        fun bootstrap() {
            val response = get("/api/bootstrap")
            cookie =
                response
                    .headers()
                    .firstValue("set-cookie")
                    .orElseThrow()
                    .substringBefore(';')
            csrf =
                Json
                    .parseToJsonElement(response.body())
                    .jsonObject
                    .getValue("csrf_token")
                    .jsonPrimitive.content
        }

        fun get(path: String): HttpResponse<String> =
            send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .apply { if (::cookie.isInitialized) header("Cookie", cookie) }
                    .GET(),
            )

        fun post(path: String): HttpResponse<String> =
            send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Cookie", cookie)
                    .header("X-LTV-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()),
            )

        fun postMissingCsrf(path: String): HttpResponse<String> =
            send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Cookie", cookie)
                    .POST(HttpRequest.BodyPublishers.noBody()),
            )

        fun postMissingCookie(path: String): HttpResponse<String> =
            send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("X-LTV-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()),
            )

        fun postWrongOrigin(path: String): HttpResponse<String> =
            send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", "http://127.0.0.1:1")
                    .header("Cookie", cookie)
                    .header("X-LTV-CSRF", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()),
            )

        fun upload(
            bytes: ByteArray,
            filename: String,
        ): HttpResponse<String> = multipart("/api/inputs", listOf(Triple("file", bytes, filename)))

        fun createJob(
            runId: String,
            policy: ByteArray,
            stages: ByteArray,
        ): HttpResponse<String> =
            multipart(
                "/api/jobs",
                listOf(
                    Triple("run_id", runId.encodeToByteArray(), null),
                    Triple("policy", policy, "policy.json"),
                    Triple("stages", stages, "load-stages.json"),
                ),
            )

        private fun multipart(
            path: String,
            parts: List<Triple<String, ByteArray, String?>>,
        ): HttpResponse<String> {
            val boundary = "ltv-stage-proposal-boundary"
            val body = ByteArrayOutputStream()
            parts.forEach { (name, bytes, filename) ->
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"".toByteArray(UTF_8))
                filename?.let { body.write("; filename=\"$it\"".toByteArray(UTF_8)) }
                body.write("\r\n".toByteArray(UTF_8))
                if (filename != null) body.write("Content-Type: application/octet-stream\r\n".toByteArray(UTF_8))
                body.write("\r\n".toByteArray(UTF_8))
                body.write(bytes)
                body.write("\r\n".toByteArray(UTF_8))
            }
            body.write("--$boundary--\r\n".toByteArray(UTF_8))
            return send(
                HttpRequest
                    .newBuilder(URI.create("$origin$path"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Origin", origin)
                    .header("Cookie", cookie)
                    .header("X-LTV-CSRF", csrf)
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())),
            )
        }

        private fun send(request: HttpRequest.Builder): HttpResponse<String> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    }

    private companion object {
        val PASSING =
            (
                """{"schema_version":"policy.v1","policy_id":"stages","defaults":{"sample_floor":1,"min_samples":1},"rules":[""" +
                    """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":250,"scope":{"kind":"overall"}},""" +
                    """{"id":"rps","metric":"throughput_rps","operator":"gte","threshold":9,"scope":{"kind":"overall"}}]}"""
            ).encodeToByteArray()
    }
}

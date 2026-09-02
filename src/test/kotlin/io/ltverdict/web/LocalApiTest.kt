package io.ltverdict.web

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class LocalApiTest {
    @TempDir
    lateinit var tempDir: Path

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

    private fun withServer(
        jobsFactory: (RunBundleStore) -> AnalysisJobs = { store ->
            val service = AnalysisService(store, EngineConfig())
            AnalysisJobs(1, service::analyze)
        },
        block: (RunBundleStore, ApiClient) -> Unit,
    ) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            jobsFactory(store).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    block(store, ApiClient(server.origin))
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
        assertEquals(JsonArray(emptyList()), error.getValue("details"))
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

        fun createJob(
            runId: String,
            policy: ByteArray? = null,
        ): HttpResponse<String> =
            multipart(
                "/api/jobs",
                buildList {
                    add(FormPart("run_id", runId.encodeToByteArray()))
                    if (policy != null) add(FormPart("policy", policy, "policy.json", "application/json"))
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

        private fun multipart(
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
        const val PASS_POLICY_SHA256 = "22c2036369dcd547643909dee86e2b43f6287fcc5fc21d4e5b113c417c4cf307"
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

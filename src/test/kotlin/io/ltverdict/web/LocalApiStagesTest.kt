package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.MAX_LOAD_STAGES_BYTES
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** ADR 0030, PR B: the `stages` part of the job request on fixtures/stages/ramp-steady-rampdown.jtl. */
class LocalApiStagesTest {
    @TempDir
    lateinit var tempDir: Path

    private val load = Files.readAllBytes(Path.of("fixtures/stages/ramp-steady-rampdown.jtl"))
    private val stages = Files.readAllBytes(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"))

    @Test
    fun `a job with stages evaluates the steady window and a job without them is unchanged`() =
        withServer { store, api, runId ->
            val staged = api.createJob(runId, policy = PASSING, stages = stages)
            val whole = api.createJob(runId, policy = PASSING)

            assertEquals(202, staged.statusCode(), staged.body())
            val stagedResult = result(store, runId, complete(api, staged))
            val wholeResult = result(store, runId, complete(api, whole))
            assertEquals("PASS", stagedResult.getValue("policy_verdict").jsonPrimitive.content)
            assertTrue(stagedResult.getValue("evidence").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "stage_binding" })
            assertEquals("FAIL", wholeResult.getValue("policy_verdict").jsonPrimitive.content)
            assertFalse(wholeResult.toString().contains("stage_binding"))
        }

    @Test
    fun `an invalid declaration is 422 INVALID_STAGES with the validator errors`() =
        withServer { _, api, runId ->
            val response =
                api.createJob(runId, stages = Files.readAllBytes(Path.of("docs/contracts/stages/v1/examples/invalid/no-steady.json")))

            assertEquals(422, response.statusCode())
            val error = response.json().getValue("error").jsonObject
            assertEquals("INVALID_STAGES", error.getValue("code").jsonPrimitive.content)
            val details = error.getValue("details").jsonArray.map { it.jsonObject }
            assertEquals(1, details.size)
            assertEquals(setOf("code", "json_pointer", "message"), details.single().keys)
            assertEquals(
                "STAGES_NO_STEADY",
                details
                    .single()
                    .getValue("code")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "/stages",
                details
                    .single()
                    .getValue("json_pointer")
                    .jsonPrimitive.content,
            )
            assertEquals("INVALID_STAGES", api.createJob(runId, stages = "{".encodeToByteArray()).json().errorCode())
        }

    @Test
    fun `too many stages and too large a file are 413`() =
        withServer { _, api, runId ->
            val seventeen = Files.readAllBytes(Path.of("docs/contracts/stages/v1/examples/invalid/too-many-stages.json"))
            val huge = stages + ByteArray(MAX_LOAD_STAGES_BYTES) { ' '.code.toByte() }

            listOf(seventeen, huge).forEach {
                val response = api.createJob(runId, stages = it)
                assertEquals(413, response.statusCode())
                assertEquals("RESOURCE_LIMIT_EXCEEDED", response.json().errorCode())
            }
        }

    @Test
    fun `a repeated stages part is a malformed request`() =
        withServer { _, api, runId ->
            val response =
                api.multipart(
                    listOf(
                        FormPart("run_id", runId.encodeToByteArray()),
                        FormPart("stages", stages, "stages.json"),
                        FormPart("stages", stages, "stages.json"),
                    ),
                )

            assertEquals(400, response.statusCode())
            assertEquals("MALFORMED_REQUEST", response.json().errorCode())
        }

    @Test
    fun `stages with a snapshot, a capacity plan or an online request are 422 INVALID_STAGES with the conflict code`() =
        withServer { _, api, runId ->
            val resources = Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))
            val capacity = Files.readAllBytes(Path.of("docs/contracts/capacity/v1/examples/valid/rps.json"))
            val source = Files.readAllBytes(Path.of("docs/contracts/sources/v1/request.example.json"))

            fun conflict(response: HttpResponse<String>): Pair<String, String> {
                assertEquals(422, response.statusCode(), response.body())
                val error = response.json().getValue("error").jsonObject
                assertEquals("INVALID_STAGES", error.getValue("code").jsonPrimitive.content)
                val detail =
                    error
                        .getValue("details")
                        .jsonArray
                        .single()
                        .jsonObject
                return detail.getValue("code").jsonPrimitive.content to detail.getValue("json_pointer").jsonPrimitive.content
            }

            // the conflict is found before the snapshot is compared with the load input and before the source profile is looked up
            assertEquals(
                "STAGES_RESOURCES_CONFLICT" to "/resource_snapshot",
                conflict(api.createJob(runId, stages = stages, resources = resources)),
            )
            assertEquals(
                "STAGES_CAPACITY_CONFLICT" to "/capacity_plan",
                conflict(api.createJob(runId, stages = stages, capacity = capacity)),
            )
            assertEquals(
                "STAGES_CAPACITY_CONFLICT" to "/capacity_plan",
                conflict(api.createJob(runId, stages = stages, resources = resources, capacity = capacity)),
            )
            assertEquals("STAGES_SOURCE_CONFLICT" to "/source_request", conflict(api.createJob(runId, stages = stages, source = source)))
        }

    @Test
    fun `a steady stage past the run end fails the job with STAGE_OUTSIDE_RUN`() =
        withServer { _, api, runId ->
            val late =
                """{"schema_version":"load-stages.v1","stages":[{"id":"late","role":"steady","from_offset_ms":130000,"to_offset_ms":140000}]}"""

            val accepted = api.createJob(runId, stages = late.encodeToByteArray())

            assertEquals(202, accepted.statusCode(), accepted.body())
            val failed =
                awaitState(
                    api,
                    accepted
                        .json()
                        .getValue("job_id")
                        .jsonPrimitive.content,
                    "FAILED",
                )
            assertEquals(
                "STAGE_OUTSIDE_RUN",
                failed
                    .getValue("diagnostic")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content,
            )
        }

    private fun result(
        store: RunBundleStore,
        runId: String,
        analysisId: String,
    ): JsonObject {
        val stored = checkNotNull(store.readAnalysis(runId, analysisId))
        return Json.parseToJsonElement(Files.readString(stored.path.resolve("analysis-result.json"))).jsonObject
    }

    private fun complete(
        api: ApiClient,
        accepted: HttpResponse<String>,
    ): String =
        awaitState(
            api,
            accepted
                .json()
                .getValue("job_id")
                .jsonPrimitive.content,
            "COMPLETE",
        ).getValue("analysis_id").jsonPrimitive.content

    private fun awaitState(
        api: ApiClient,
        jobId: String,
        expected: String,
    ): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val status = api.get("/api/jobs/$jobId").json()
            val state = status.getValue("state").jsonPrimitive.content
            if (state == expected) return status
            if (state in setOf("COMPLETE", "FAILED", "CANCELLED")) fail<Unit>("job ended as $state: $status")
            LockSupport.parkNanos(1_000_000)
        }
        return fail("job did not reach $expected: $jobId")
    }

    private fun withServer(block: (RunBundleStore, ApiClient, String) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    val api = ApiClient(server.origin)
                    api.bootstrap()
                    val upload = api.multipart(listOf(FormPart("file", load, "ramp.jtl")), "/api/inputs")
                    assertEquals(201, upload.statusCode(), upload.body())
                    block(store, api, "jmeter_jtl_csv-${sha256Hex(load)}")
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

    private data class FormPart(
        val name: String,
        val bytes: ByteArray,
        val filename: String? = null,
    )

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

        fun createJob(
            runId: String,
            policy: ByteArray? = null,
            stages: ByteArray? = null,
            resources: ByteArray? = null,
            capacity: ByteArray? = null,
            source: ByteArray? = null,
        ): HttpResponse<String> =
            multipart(
                buildList {
                    add(FormPart("run_id", runId.encodeToByteArray()))
                    if (policy != null) add(FormPart("policy", policy, "policy.json"))
                    if (stages != null) add(FormPart("stages", stages, "stages.json"))
                    if (resources != null) add(FormPart("resource_snapshot", resources, "resources.json"))
                    if (capacity != null) add(FormPart("capacity_plan", capacity, "capacity.json"))
                    if (source != null) add(FormPart("source_request", source, "source.json"))
                },
            )

        fun multipart(
            parts: List<FormPart>,
            path: String = "/api/jobs",
        ): HttpResponse<String> {
            val boundary = "ltv-stages-boundary"
            val body = ByteArrayOutputStream()
            parts.forEach { part ->
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"".toByteArray(UTF_8))
                part.filename?.let { body.write("; filename=\"$it\"".toByteArray(UTF_8)) }
                body.write("\r\n".toByteArray(UTF_8))
                if (part.filename != null) body.write("Content-Type: application/octet-stream\r\n".toByteArray(UTF_8))
                body.write("\r\n".toByteArray(UTF_8))
                body.write(part.bytes)
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

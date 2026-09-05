package io.ltverdict.integration

import io.ltverdict.cli.runCli
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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

class ShellParityTest {
    @TempDir
    lateinit var tempDir: Path

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "fixtures/slice1/jmeter/csv-5.6.3/input.jtl",
            "fixtures/slice1/jmeter/xml-5.6.3/input.xml",
            "fixtures/slice1/gatling/text-3.12.0/simulation.log",
            "fixtures/slice1/gatling/binary-3.15.1/simulation.log",
        ],
    )
    fun `CLI and HTTP produce identical analysis`(fixture: String) {
        val input = Path.of(fixture)

        val cli = analyzeWithCli(input, tempDir.resolve("cli"))
        val http = analyzeWithHttp(input, tempDir.resolve("http"))

        assertEquals(cli.runId, http.runId)
        assertEquals(cli.analysisId, http.analysisId)
        assertArrayEquals(cli.result, http.result)
    }

    private fun analyzeWithCli(
        input: Path,
        dataDir: Path,
    ): AnalysisSnapshot {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode =
            PrintStream(stdout, true, UTF_8).use { out ->
                PrintStream(stderr, true, UTF_8).use { err ->
                    runCli(arrayOf("analyze", input.toString(), "--data-dir", dataDir.toString()), out, err)
                }
            }

        assertEquals(0, exitCode, stderr.toString(UTF_8))
        assertEquals("", stderr.toString(UTF_8))
        val run = onlyChild(dataDir.resolve("runs"))
        val analysis = onlyChild(run.resolve("analyses"))
        val result = Files.readAllBytes(analysis.resolve("analysis-result.json"))
        assertArrayEquals(result, stdout.toByteArray())
        assertEquals(
            run.fileName.toString(),
            Json
                .parseToJsonElement(result.decodeToString())
                .jsonObject
                .getValue("run_id")
                .jsonPrimitive.content,
        )
        return AnalysisSnapshot(run.fileName.toString(), analysis.fileName.toString(), result)
    }

    private fun analyzeWithHttp(
        input: Path,
        dataDir: Path,
    ): AnalysisSnapshot =
        DataDirectory.open(dataDir).use { directory ->
            val store = RunBundleStore(directory)
            val service = AnalysisService(store, EngineConfig())
            AnalysisJobs(1, service::analyze).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    val api = ApiClient(server.origin)
                    api.bootstrap()

                    val upload = api.upload(input)
                    assertEquals(201, upload.statusCode())
                    val runId =
                        upload
                            .jsonObject()
                            .getValue("run_id")
                            .jsonPrimitive.content

                    val submitted = api.createJob(runId)
                    assertEquals(202, submitted.statusCode())
                    val jobId =
                        submitted
                            .jsonObject()
                            .getValue("job_id")
                            .jsonPrimitive.content
                    val analysisId = awaitComplete(api, jobId).getValue("analysis_id").jsonPrimitive.content

                    val result = api.get("/api/runs/$runId/analyses/$analysisId/result")
                    assertEquals(200, result.statusCode())
                    AnalysisSnapshot(runId, analysisId, result.body())
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

    private fun onlyChild(directory: Path): Path = Files.list(directory).use { paths -> paths.iterator().asSequence().single() }

    private data class AnalysisSnapshot(
        val runId: String,
        val analysisId: String,
        val result: ByteArray,
    )

    private class ApiClient(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private lateinit var cookie: String
        private lateinit var csrf: String

        fun bootstrap() {
            val response = get("/api/bootstrap")
            assertEquals(200, response.statusCode())
            cookie =
                response
                    .headers()
                    .firstValue("set-cookie")
                    .orElseThrow()
                    .substringBefore(';')
            csrf =
                response
                    .jsonObject()
                    .getValue("csrf_token")
                    .jsonPrimitive.content
        }

        fun upload(input: Path): HttpResponse<ByteArray> =
            multipart(
                "/api/inputs",
                "form-data; name=\"file\"; filename=\"${input.fileName}\"",
                Files.readAllBytes(input),
                "application/octet-stream",
            )

        fun createJob(runId: String): HttpResponse<ByteArray> =
            multipart("/api/jobs", "form-data; name=\"run_id\"", runId.encodeToByteArray())

        fun get(path: String): HttpResponse<ByteArray> = send(request(path).GET())

        private fun multipart(
            path: String,
            disposition: String,
            bytes: ByteArray,
            contentType: String? = null,
        ): HttpResponse<ByteArray> {
            val boundary = "ltv-parity-boundary"
            val body = ByteArrayOutputStream()
            body.writeUtf8("--$boundary\r\nContent-Disposition: $disposition\r\n")
            contentType?.let { body.writeUtf8("Content-Type: $it\r\n") }
            body.writeUtf8("\r\n")
            body.write(bytes)
            body.writeUtf8("\r\n--$boundary--\r\n")
            return send(
                authenticated(request(path))
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())),
            )
        }

        private fun request(path: String): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI.create("$origin$path"))
                .timeout(Duration.ofSeconds(10))
                .apply { if (::cookie.isInitialized) header("Cookie", cookie) }

        private fun authenticated(request: HttpRequest.Builder): HttpRequest.Builder =
            request.header("Origin", origin).header("Cookie", cookie).header("X-LTV-CSRF", csrf)

        private fun send(request: HttpRequest.Builder): HttpResponse<ByteArray> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
}

private fun HttpResponse<ByteArray>.jsonObject(): JsonObject = Json.parseToJsonElement(body().decodeToString()).jsonObject

private fun ByteArrayOutputStream.writeUtf8(value: String) {
    write(value.toByteArray(UTF_8))
}

package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.TimeUnit

class LocalSecurityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `server binds to IPv4 loopback and bootstrap reuses separate 256 bit credentials`() =
        withServer { fixture ->
            val connector =
                runBlocking {
                    fixture.server.engine
                        .resolvedConnectors()
                        .single()
                }
            assertEquals("127.0.0.1", connector.host)
            assertEquals("http://127.0.0.1:${connector.port}", fixture.server.origin)

            val first = fixture.bootstrap()
            val second = fixture.bootstrap()
            assertEquals(first, second)
            assertNotEquals(first.cookie.substringAfter('='), first.csrf)

            val preflight =
                fixture.request(
                    "OPTIONS",
                    "/api/inputs",
                    headers =
                        mapOf(
                            "Origin" to "https://attacker.invalid",
                            "Access-Control-Request-Method" to "POST",
                        ),
                )
            assertTrue(preflight.statusCode() in 400..499)
        }

    @Test
    fun `Host and mutation credentials are enforced exactly`() =
        withServer { fixture ->
            assertEquals(200, fixture.rawGet(fixture.authority).status)
            listOf(null, "localhost:${fixture.port}").forEach { host ->
                assertError(fixture.rawGet(host), 403)
            }

            val credentials = fixture.bootstrap()
            val authorized = fixture.auth(credentials)
            val validPolicy = VALID_POLICY.encodeToByteArray()
            val policyHeaders = authorized + ("Content-Type" to "application/json")
            assertEquals(
                200,
                fixture.request("POST", "/api/policies/validate", validPolicy, policyHeaders).statusCode(),
            )
            assertError(
                fixture.request("DELETE", "/api/jobs/$MISSING_JOB", headers = authorized),
                404,
            )

            val rejected =
                listOf(
                    authorized + ("Origin" to "http://localhost:${fixture.port}"),
                    authorized - "Cookie",
                    authorized + ("X-LTV-CSRF" to "0".repeat(64)),
                )
            rejected.forEach { headers ->
                assertError(
                    fixture.request(
                        "POST",
                        "/api/policies/validate",
                        validPolicy,
                        headers + ("Content-Type" to "application/json"),
                    ),
                    403,
                )
                assertError(
                    fixture.request("DELETE", "/api/jobs/$MISSING_JOB", headers = headers),
                    403,
                )
            }
        }

    @Test
    fun `invalid media malformed input and resource overflow use bounded JSON errors`() =
        withServer { fixture ->
            val credentials = fixture.bootstrap()
            val auth = fixture.auth(credentials)

            assertError(
                fixture.request(
                    "POST",
                    "/api/inputs",
                    "not multipart".encodeToByteArray(),
                    auth + ("Content-Type" to "application/octet-stream"),
                ),
                415,
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/policies/validate",
                    VALID_POLICY.encodeToByteArray(),
                    auth + ("Content-Type" to "text/plain"),
                ),
                415,
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/policies/validate",
                    "{".encodeToByteArray(),
                    auth + ("Content-Type" to "application/json"),
                ),
                400,
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/inputs",
                    "broken multipart".encodeToByteArray(),
                    auth + ("Content-Type" to "multipart/form-data; boundary=broken"),
                ),
                400,
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/inputs",
                    multipart("unsupported", "file", "unknown".encodeToByteArray(), "input.bin"),
                    auth + ("Content-Type" to "multipart/form-data; boundary=unsupported"),
                ),
                422,
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/inputs",
                    multipartWithExtra("extra", maliciousCsv()),
                    auth + ("Content-Type" to "multipart/form-data; boundary=extra"),
                ),
                400,
            )
            assertTrue(
                fixture
                    .request("GET", "/api/runs")
                    .json()
                    .getValue("runs")
                    .let { it as JsonArray }
                    .isEmpty(),
            )
            assertError(
                fixture.request(
                    "POST",
                    "/api/policies/validate",
                    ByteArray(MAX_POLICY_BYTES + 1) { ' '.code.toByte() },
                    auth + ("Content-Type" to "application/json"),
                ),
                413,
            )
            assertError(fixture.request("GET", "/api/unknown"), 404)
            assertError(fixture.oversizedUpload(credentials), 413)
        }

    @Test
    fun `hostile transaction text is returned only as JSON data`() =
        withServer { fixture ->
            val credentials = fixture.bootstrap()
            val upload =
                fixture.request(
                    "POST",
                    "/api/inputs",
                    multipart("upload", "file", maliciousCsv(), "input.jtl"),
                    fixture.auth(credentials) + ("Content-Type" to "multipart/form-data; boundary=upload"),
                )
            assertEquals(201, upload.statusCode())
            val runId =
                upload
                    .json()
                    .getValue("run_id")
                    .jsonPrimitive.content

            val job =
                fixture.request(
                    "POST",
                    "/api/jobs",
                    multipart("job", "run_id", runId.encodeToByteArray()),
                    fixture.auth(credentials) + ("Content-Type" to "multipart/form-data; boundary=job"),
                )
            assertEquals(202, job.statusCode())
            val jobId =
                job
                    .json()
                    .getValue("job_id")
                    .jsonPrimitive.content
            val analysisId = fixture.awaitAnalysis(jobId)
            val result =
                fixture.request(
                    "GET",
                    "/api/runs/$runId/analyses/$analysisId/result",
                )
            assertEquals(200, result.statusCode())
            assertTrue(result.json().containsString(MALICIOUS_LABEL))
            assertFalse(result.header("Content-Type").orEmpty().startsWith("text/html"))
        }

    @Test
    fun `runtime classpath has no outbound HTTP client library`() {
        val loader = Thread.currentThread().contextClassLoader
        listOf(
            "io/ktor/client/HttpClient.class",
            "okhttp3/OkHttpClient.class",
            "org/apache/hc/client5/http/classic/HttpClient.class",
            "org/eclipse/jetty/client/HttpClient.class",
        ).forEach { resource -> assertNull(loader.getResource(resource), resource) }
    }

    private fun withServer(block: (Fixture) -> Unit) {
        Fixture(tempDir.resolve(UUID.randomUUID().toString())).use(block)
    }

    private class Fixture(
        root: Path,
    ) : AutoCloseable {
        private val directory = DataDirectory.open(root)
        private val store = RunBundleStore(directory)
        private val service = AnalysisService(store, EngineConfig())
        private val jobs = AnalysisJobs(1, service::analyze)
        val server = startLocalServer(LocalApiContext(store, jobs), openBrowser = false)
        private val client =
            HttpClient
                .newBuilder()
                .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()
        val port = URI.create(server.origin).port
        val authority = "127.0.0.1:$port"

        fun bootstrap(): Credentials {
            val response = request("GET", "/api/bootstrap")
            assertEquals(200, response.statusCode())
            val body = response.json()
            assertEquals(setOf("csrf_token", "max_upload_bytes"), body.keys)
            assertEquals(MAX_UPLOAD_BYTES, body.getValue("max_upload_bytes").jsonPrimitive.long)

            val csrf = body.getValue("csrf_token").jsonPrimitive.content
            val setCookie = response.headers().allValues("Set-Cookie").single()
            val attributes = setCookie.split(';').drop(1).map(String::trim)
            assertTrue(attributes.any { it.equals("HttpOnly", ignoreCase = true) })
            assertTrue(attributes.any { it.equals("SameSite=Strict", ignoreCase = true) })
            assertTrue(attributes.any { it.equals("Path=/", ignoreCase = true) })
            val cookie = setCookie.substringBefore(';')
            assertRandom256(cookie.substringAfter('='))
            assertRandom256(csrf)
            return Credentials(cookie, csrf)
        }

        fun auth(credentials: Credentials): Map<String, String> =
            mapOf(
                "Origin" to server.origin,
                "Cookie" to credentials.cookie,
                "X-LTV-CSRF" to credentials.csrf,
            )

        fun request(
            method: String,
            path: String,
            body: ByteArray = byteArrayOf(),
            headers: Map<String, String> = emptyMap(),
        ): HttpResponse<ByteArray> {
            val builder =
                HttpRequest
                    .newBuilder(URI.create(server.origin + path))
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            headers.forEach(builder::header)
            builder.method(
                method,
                if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
            )
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).also(::assertBaseline)
        }

        fun rawGet(host: String?): WireResponse =
            raw(
                buildString {
                    append("GET /api/bootstrap HTTP/1.1\r\n")
                    if (host != null) append("Host: $host\r\n")
                    append("Connection: close\r\n\r\n")
                },
            )

        fun oversizedUpload(credentials: Credentials): WireResponse =
            raw(
                buildString {
                    append("POST /api/inputs HTTP/1.1\r\n")
                    append("Host: $authority\r\n")
                    auth(credentials).forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Content-Type: multipart/form-data; boundary=oversized\r\n")
                    append("Content-Length: ${Long.MAX_VALUE}\r\n")
                    append("Connection: close\r\n\r\n")
                },
            )

        fun awaitAnalysis(jobId: String): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                val status = request("GET", "/api/jobs/$jobId").json()
                when (status.getValue("state").jsonPrimitive.content) {
                    "COMPLETE" -> return status.getValue("analysis_id").jsonPrimitive.content
                    "FAILED", "CANCELLED" -> fail("job ended as ${status.getValue("state")}")
                }
                Thread.sleep(10)
            }
            return fail("job did not complete: $jobId")
        }

        private fun raw(request: String): WireResponse {
            val bytes =
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS).toInt()
                    socket.getOutputStream().apply {
                        write(request.toByteArray(StandardCharsets.ISO_8859_1))
                        flush()
                    }
                    socket.getInputStream().readAllBytes()
                }
            return WireResponse.parse(bytes).also(::assertBaseline)
        }

        override fun close() {
            try {
                client.close()
                server.close()
            } finally {
                jobs.close()
                directory.close()
            }
        }
    }

    private data class Credentials(
        val cookie: String,
        val csrf: String,
    )

    internal data class WireResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]

        companion object {
            fun parse(bytes: ByteArray): WireResponse {
                val wire = bytes.toString(StandardCharsets.ISO_8859_1)
                val separator = wire.indexOf("\r\n\r\n")
                require(separator >= 0) { "malformed HTTP response" }
                val lines = wire.substring(0, separator).split("\r\n")
                val status = lines.first().split(' ')[1].toInt()
                val headers =
                    lines.drop(1).associate { line ->
                        val colon = line.indexOf(':')
                        line.substring(0, colon).lowercase() to line.substring(colon + 1).trim()
                    }
                val encodedBody = bytes.copyOfRange(separator + 4, bytes.size)
                val body =
                    if (headers["transfer-encoding"]?.equals("chunked", ignoreCase = true) == true) {
                        decodeChunks(encodedBody)
                    } else {
                        encodedBody
                    }
                return WireResponse(status, headers, body)
            }
        }
    }

    companion object {
        const val MAX_POLICY_BYTES = 1_048_576
        const val MAX_UPLOAD_BYTES = 4_294_967_296L
        const val TIMEOUT_SECONDS = 10L
        const val MISSING_JOB = "00000000-0000-0000-0000-000000000000"
        const val MALICIOUS_LABEL = "</script><img src=x onerror=alert(1)>"
        const val VALID_POLICY =
            """{"schema_version":"policy.v1","policy_id":"security","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}"""
        const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"

        val CORS_HEADERS =
            listOf(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Credentials",
                "Access-Control-Allow-Headers",
                "Access-Control-Allow-Methods",
            )
        val HEX_256 = Regex("[0-9a-fA-F]{64}")
    }
}

private fun assertBaseline(response: HttpResponse<ByteArray>) = assertBaseline { name -> response.headers().firstValue(name).orElse(null) }

private fun assertBaseline(response: LocalSecurityTest.WireResponse) = assertBaseline(response::header)

private fun assertBaseline(header: (String) -> String?) {
    assertEquals(LocalSecurityTest.CSP, header("Content-Security-Policy"))
    assertEquals("nosniff", header("X-Content-Type-Options"))
    assertEquals("no-referrer", header("Referrer-Policy"))
    assertEquals("no-store", header("Cache-Control"))
    LocalSecurityTest.CORS_HEADERS.forEach { assertNull(header(it), it) }
}

private fun assertError(
    response: HttpResponse<ByteArray>,
    status: Int,
) {
    assertEquals(status, response.statusCode())
    assertError(response.json())
}

private fun assertError(
    response: LocalSecurityTest.WireResponse,
    status: Int,
) {
    assertEquals(status, response.status)
    assertError(Json.parseToJsonElement(response.body.decodeToString()).jsonObject)
}

private fun assertError(body: JsonObject) {
    assertEquals(setOf("error"), body.keys)
    val error = body.getValue("error").jsonObject
    assertEquals(setOf("code", "message", "details"), error.keys)
    assertTrue(
        error
            .getValue("code")
            .jsonPrimitive.content
            .isNotBlank(),
    )
    assertTrue(
        error
            .getValue("message")
            .jsonPrimitive.content
            .isNotBlank(),
    )
    assertTrue((error.getValue("details") as? JsonArray)?.isEmpty() == true)
}

private fun HttpResponse<ByteArray>.header(name: String): String? = headers().firstValue(name).orElse(null)

private fun HttpResponse<ByteArray>.json(): JsonObject {
    assertTrue(header("Content-Type")?.startsWith("application/json") == true)
    return Json.parseToJsonElement(body().decodeToString()).jsonObject
}

private fun assertRandom256(token: String) {
    val bytes =
        if (LocalSecurityTest.HEX_256.matches(token)) {
            HexFormat.of().parseHex(token)
        } else {
            try {
                Base64.getUrlDecoder().decode(token)
            } catch (_: IllegalArgumentException) {
                Base64.getDecoder().decode(token)
            }
        }
    assertEquals(32, bytes.size)
}

private fun multipart(
    boundary: String,
    name: String,
    value: ByteArray,
    filename: String? = null,
): ByteArray =
    ByteArrayOutputStream().use { output ->
        output.write("--$boundary\r\n".encodeToByteArray())
        val file = filename?.let { "; filename=\"$it\"" }.orEmpty()
        output.write("Content-Disposition: form-data; name=\"$name\"$file\r\n\r\n".encodeToByteArray())
        output.write(value)
        output.write("\r\n--$boundary--\r\n".encodeToByteArray())
        output.toByteArray()
    }

private fun multipartWithExtra(
    boundary: String,
    file: ByteArray,
): ByteArray =
    ByteArrayOutputStream().use { output ->
        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.jtl\"\r\n\r\n".encodeToByteArray())
        output.write(file)
        output.write("\r\n--$boundary\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\nx\r\n".encodeToByteArray())
        output.write("--$boundary--\r\n".encodeToByteArray())
        output.toByteArray()
    }

private fun maliciousCsv(): ByteArray =
    (
        "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage," +
            "bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
            "1700000000000,10,${LocalSecurityTest.MALICIOUS_LABEL},200,OK,test 1-1,text,true,,0,0,1,1,null,0,0,0\n"
    ).encodeToByteArray()

private fun JsonElement.containsString(expected: String): Boolean =
    when (this) {
        is JsonObject -> values.any { it.containsString(expected) }
        is JsonArray -> any { it.containsString(expected) }
        is JsonPrimitive -> isString && content == expected
    }

private fun decodeChunks(source: ByteArray): ByteArray =
    ByteArrayOutputStream().use { output ->
        var offset = 0
        while (true) {
            val lineEnd = source.indexOfCrlf(offset)
            require(lineEnd >= 0) { "malformed chunked response" }
            val size =
                source
                    .copyOfRange(offset, lineEnd)
                    .decodeToString()
                    .substringBefore(';')
                    .trim()
                    .toInt(16)
            if (size == 0) return@use output.toByteArray()
            offset = lineEnd + 2
            output.write(source, offset, size)
            offset += size + 2
        }
        error("unreachable")
    }

private fun ByteArray.indexOfCrlf(start: Int): Int {
    for (index in start until size - 1) {
        if (this[index] == '\r'.code.toByte() && this[index + 1] == '\n'.code.toByte()) return index
    }
    return -1
}

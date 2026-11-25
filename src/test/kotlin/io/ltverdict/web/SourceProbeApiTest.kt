package io.ltverdict.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.SchemaLite
import io.ltverdict.sources.SourceRegistry
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

class SourceProbeApiTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `all four new POST routes require Host Origin cookie and X-LTV-CSRF`() =
        withServer(connectionsPath = null) { _, api ->
            val paths =
                listOf(
                    "/api/sources/reload",
                    "/api/sources/validate",
                    "/api/sources/probe",
                    "/api/resources/semantic-hash",
                )
            paths.forEach { path ->
                val noCreds = api.postRaw(path, "application/json", "{}".encodeToByteArray(), emptyMap())
                assertEquals(403, noCreds.statusCode(), path)
                assertEquals("FORBIDDEN", noCreds.errorCode(), path)

                val badCsrf = api.postRaw(path, "application/json", "{}".encodeToByteArray(), api.authHeaders() + ("X-LTV-CSRF" to "bad"))
                assertEquals(403, badCsrf.statusCode(), path)
                assertEquals("FORBIDDEN", badCsrf.errorCode(), path)
            }
        }

    @Test
    fun `reload unconfigured returns 404 and path in body returns 400`() {
        withServer(connectionsPath = null) { _, api ->
            val response = api.postJson("/api/sources/reload", "")
            assertEquals(404, response.statusCode())
            assertEquals("SOURCE_RELOAD_NOT_CONFIGURED", response.errorCode())
        }
        val file = tempDir.resolve("conn.json").also { Files.writeString(it, singlePromJson("p1", 9)) }
        withServer(connectionsPath = file) { _, api ->
            val withPath = api.postJson("/api/sources/reload", """{"path":"/etc/passwd"}""")
            assertEquals(400, withPath.statusCode())
            assertEquals("MALFORMED_REQUEST", withPath.errorCode())
        }
    }

    @Test
    fun `reload updates GET api sources, validates schema, rejects invalid without leaking file text and enforces cooldown`() =
        withStub { stub ->
            val file = tempDir.resolve("connections.json")
            Files.writeString(file, singlePromJson("p1", stub.port))
            val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
            withServer(connectionsPath = file, clock = clock) { store, api ->
                val initialSources = api.get("/api/sources")
                assertEquals(200, initialSources.statusCode())
                assertEquals(listOf("p1"), initialSources.profileIds())

                // Broken file preserves old state and never leaks file text.
                Files.writeString(file, """{"secret_marker_leak": "SECRET_FILE_TEXT", "connections": []}""")
                val invalid = api.postJson("/api/sources/reload", "{}")
                assertEquals(422, invalid.statusCode())
                assertEquals("SOURCE_CONFIG_INVALID", invalid.errorCode())
                assertFalse(invalid.body().contains("SECRET_FILE_TEXT"), invalid.body())
                assertFalse(invalid.body().contains(file.toString()), invalid.body())
                assertEquals(listOf("p1"), api.get("/api/sources").profileIds())

                // Fix file and reload without body (no Content-Type) -> succeeds immediately.
                Files.writeString(file, twoProfileJson("p1", "p2", stub.port))
                val reloaded = api.postEmpty("/api/sources/reload")
                assertEquals(200, reloaded.statusCode(), reloaded.body())
                val doc = reloaded.json().jsonObject
                assertEquals(emptyList<String>(), SchemaLite.errors(doc, SchemaLite.schema("source-reload")), reloaded.body())
                assertEquals("RELOADED", doc.getValue("status").jsonPrimitive.content)
                assertEquals(listOf("p1", "p2"), doc.getValue("profiles").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(listOf("p2"), doc.getValue("added").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(listOf("p1", "p2"), api.get("/api/sources").profileIds())

                // Immediate second reload hits 429 RELOAD_TOO_FREQUENT; advancing clock by 5s allows it.
                val tooSoon = api.postJson("/api/sources/reload", "{}")
                assertEquals(429, tooSoon.statusCode())
                assertEquals("RELOAD_TOO_FREQUENT", tooSoon.errorCode())

                clock.advanceMillis(5_000)
                val again = api.postJson("/api/sources/reload", "{}")
                assertEquals(200, again.statusCode(), again.body())

                // Job with newly reloaded profile p2 is accepted by POST /api/jobs without restarting backend.
                val runId = uploadJtl(store)
                stub.handler = { exchange ->
                    val end = queryParam(exchange, "end")?.toDoubleOrNull()?.toLong() ?: 1_767_225_600L
                    exchange.respond(
                        200,
                        """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"host"},"values":[[${end - 1},"1"]]}]}}""",
                    )
                }
                val reqJson =
                    """{"schema_version":"source-request.v3","profile_ids":["p2"],""" +
                        """"window":{"origin":"explicit","start_epoch_ms":1767225599000,"end_epoch_ms":1767225601000,"step_ms":1000}}"""
                val jobResp =
                    api.multipart(
                        "/api/jobs",
                        listOf(
                            Part("run_id", runId.encodeToByteArray()),
                            Part("source_request", reqJson.encodeToByteArray(), "source-request.json", "application/json"),
                        ),
                    )
                assertEquals(202, jobResp.statusCode(), jobResp.body())
            }
        }

    @Test
    fun `in-flight job finishes on old generation while queued job missing profile after reload fails with SOURCE_PROFILE_NOT_FOUND`() =
        withStub { stub ->
            val file = tempDir.resolve("connections.json")
            Files.writeString(file, singlePromJson("p1", stub.port))
            val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstCall = AtomicInteger(0)
            stub.handler = { exchange ->
                if (firstCall.incrementAndGet() == 1) {
                    firstStarted.countDown()
                    releaseFirst.await(10, TimeUnit.SECONDS)
                }
                exchange.respond(
                    200,
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"host"},"values":[[1767225600,"1"],[1767225601,"1"]]}]}}""",
                )
            }
            withServer(connectionsPath = file, clock = clock) { store, api ->
                val runId = uploadJtl(store)
                val reqJson =
                    """{"schema_version":"source-request.v3","profile_ids":["p1"],""" +
                        """"window":{"origin":"explicit","start_epoch_ms":1767225600000,"end_epoch_ms":1767225601000,"step_ms":1000}}"""
                val job1 =
                    api
                        .multipart(
                            "/api/jobs",
                            listOf(
                                Part("run_id", runId.encodeToByteArray()),
                                Part("source_request", reqJson.encodeToByteArray(), "source-request.json", "application/json"),
                            ),
                        ).json()
                        .jsonObject
                        .getValue("job_id")
                        .jsonPrimitive.content
                assertTrue(firstStarted.await(5, TimeUnit.SECONDS))

                // Queue job2 while p1 is still configured.
                val job2 =
                    api
                        .multipart(
                            "/api/jobs",
                            listOf(
                                Part("run_id", runId.encodeToByteArray()),
                                Part("source_request", reqJson.encodeToByteArray(), "source-request.json", "application/json"),
                            ),
                        ).json()
                        .jsonObject
                        .getValue("job_id")
                        .jsonPrimitive.content

                // Reload removing p1 and replacing with p2.
                Files.writeString(file, singlePromJson("p2", stub.port))
                clock.advanceMillis(5_000)
                assertEquals(200, api.postJson("/api/sources/reload", "{}").statusCode())

                // Release job1 -> completes on old generation.
                releaseFirst.countDown()
                val status1 = awaitTerminal(api, job1)
                assertEquals("COMPLETE", status1.getValue("state").jsonPrimitive.content, status1.toString())

                // Job2 starts after reload -> fails cleanly with SOURCE_PROFILE_NOT_FOUND.
                val status2 = awaitTerminal(api, job2)
                assertEquals("FAILED", status2.getValue("state").jsonPrimitive.content, status2.toString())
                assertEquals(
                    "SOURCE_PROFILE_NOT_FOUND",
                    status2
                        .getValue("diagnostic")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
            }
        }

    @Test
    fun `grafana render reads profile and SourceHttp from a single generation across reload`() =
        withStub { stub ->
            val renderStarted = CountDownLatch(1)
            val releaseRender = CountDownLatch(1)
            stub.handler = { exchange ->
                if (exchange.requestURI.path.startsWith("/grafana/render/")) {
                    renderStarted.countDown()
                    releaseRender.await(10, TimeUnit.SECONDS)
                    exchange.respondBytes(200, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3))
                } else {
                    exchange.respond(200, """{"status":"success","data":{"resultType":"matrix","result":[]}}""")
                }
            }
            val file = tempDir.resolve("grafana.json")
            Files.writeString(file, grafanaPromJson("graf", stub.port))
            val clock = MutableClock(Instant.parse("2026-11-24T00:00:00Z"))
            withServer(connectionsPath = file, clock = clock) { store, api ->
                val runId = uploadJtl(store)
                val jobId =
                    api
                        .multipart("/api/jobs", listOf(Part("run_id", runId.encodeToByteArray())))
                        .json()
                        .jsonObject
                        .getValue("job_id")
                        .jsonPrimitive.content
                val analysisId = awaitTerminal(api, jobId).getValue("analysis_id").jsonPrimitive.content

                // Reload to a different profile while rendering -> completes on old generation (never 500 or SOURCE_PROFILE_NOT_CONFIGURED).
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val renderFuture =
                        executor.submit<HttpResponse<String>> {
                            api.postJson(
                                "/api/runs/$runId/analyses/$analysisId/grafana-render?profile=graf&dashboard=dash&panel=1",
                                "{}",
                            )
                        }
                    assertTrue(renderStarted.await(5, TimeUnit.SECONDS))
                    Files.writeString(file, grafanaPromJson("graf-next", stub.port))
                    clock.advanceMillis(5_000)
                    assertEquals(200, api.postJson("/api/sources/reload", "{}").statusCode())
                    releaseRender.countDown()

                    val render = renderFuture.get(5, TimeUnit.SECONDS)
                    assertEquals(200, render.statusCode(), render.body())
                    assertEquals(JsonNull, render.json().jsonObject.getValue("failure_code"))

                    val afterReload =
                        api.postJson(
                            "/api/runs/$runId/analyses/$analysisId/grafana-render?profile=graf&dashboard=dash&panel=1",
                            "{}",
                        )
                    assertEquals(404, afterReload.statusCode())
                } finally {
                    releaseRender.countDown()
                    executor.shutdownNow()
                }
            }
        }

    @Test
    fun `validate endpoint runs offline and enforces profile_id and closed schema`() =
        withStub { stub ->
            val file = tempDir.resolve("connections.json")
            Files.writeString(file, twoProfileJson("p1", "p2", stub.port))
            withServer(connectionsPath = file) { _, api ->
                val ok = api.postJson("/api/sources/validate", """{"profile_id":"p1"}""")
                assertEquals(200, ok.statusCode(), ok.body())
                val doc = ok.json().jsonObject
                assertEquals(emptyList<String>(), SchemaLite.errors(doc, SchemaLite.schema("source-check")), ok.body())
                assertEquals(0, stub.requests.get())

                val missing = api.postJson("/api/sources/validate", """{"profile_id":"unknown"}""")
                assertEquals(404, missing.statusCode())
                assertEquals("SOURCE_PROFILE_NOT_FOUND", missing.errorCode())

                val badField = api.postJson("/api/sources/validate", """{"profile_id":"p1","online":true}""")
                assertEquals(400, badField.statusCode())
                assertEquals("INVALID_PROBE", badField.errorCode())
            }
        }

    @Test
    fun `probe endpoint returns AMBIGUOUS_SERIES, redacts secrets, enforces concurrency limit 2 and leaves data dir untouched`() =
        withStub { stub ->
            val file = tempDir.resolve("connections.json")
            Files.writeString(file, singlePromJson("p1", stub.port))
            withServer(connectionsPath = file) { store, api ->
                val beforeEntries = Files.walk(tempDir).use { s -> s.map(Path::toString).sorted().toList() }

                // 3-series response -> 200 source-probe.v1 with AMBIGUOUS_SERIES
                stub.handler = { exchange ->
                    val end = queryParam(exchange, "end")?.toDoubleOrNull()?.toLong() ?: (System.currentTimeMillis() / 1000)
                    val series =
                        (1..3).joinToString(",") {
                            """{"metric":{"instance":"host","pod":"api-$it"},"values":[[${end - 15},"1"]]}"""
                        }
                    exchange.respond(200, """{"status":"success","data":{"resultType":"matrix","result":[$series]}}""")
                }
                val probeOk = api.postJson("/api/sources/probe", """{"profile_id":"p1","query_id":"cpu"}""")
                assertEquals(200, probeOk.statusCode(), probeOk.body())
                val doc = probeOk.json().jsonObject
                assertEquals(emptyList<String>(), SchemaLite.errors(doc, SchemaLite.schema("source-probe")), probeOk.body())
                assertEquals("3", doc.getValue("series_count").jsonPrimitive.content)
                assertEquals(
                    "AMBIGUOUS_SERIES",
                    doc
                        .getValue("decoder_check")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )

                val afterEntries = Files.walk(tempDir).use { s -> s.map(Path::toString).sorted().toList() }
                assertEquals(beforeEntries, afterEntries, "probe must not write anything to data dir")

                // 401 with secret body and Set-Cookie -> 200 FAILED SOURCE_HTTP_AUTH, zero leak
                stub.handler = { exchange ->
                    exchange.responseHeaders.add("Set-Cookie", "AUTH=SECRET-COOKIE")
                    exchange.respond(401, """{"error":"SECRET-BODY"}""")
                }
                val probeAuthFail = api.postJson("/api/sources/probe", """{"profile_id":"p1","query_id":"cpu"}""")
                assertEquals(200, probeAuthFail.statusCode(), probeAuthFail.body())
                val authFailDoc = probeAuthFail.json().jsonObject
                assertEquals("FAILED", authFailDoc.getValue("status").jsonPrimitive.content)
                assertEquals("SOURCE_HTTP_AUTH", authFailDoc.getValue("code").jsonPrimitive.content)
                assertFalse(probeAuthFail.body().contains("SECRET-BODY"))
                assertFalse(probeAuthFail.body().contains("SECRET-COOKIE"))
                assertFalse(probeAuthFail.body().contains("127.0.0.1"))

                // Reject expression / ad_hoc (ADR 0033 Q1)
                val adHoc = api.postJson("/api/sources/probe", """{"profile_id":"p1","expression":"up"}""")
                assertEquals(400, adHoc.statusCode())
                assertEquals("INVALID_PROBE", adHoc.errorCode())

                // Concurrency limit = 2 -> 3rd parallel probe gets 409 BUSY
                val enteredTwo = CountDownLatch(2)
                val releaseTwo = CountDownLatch(1)
                stub.handler = { exchange ->
                    enteredTwo.countDown()
                    releaseTwo.await(10, TimeUnit.SECONDS)
                    exchange.respond(200, """{"status":"success","data":{"resultType":"matrix","result":[]}}""")
                }
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val f1 =
                        pool.submit<HttpResponse<String>> {
                            api.postJson("/api/sources/probe", """{"profile_id":"p1","query_id":"cpu"}""")
                        }
                    val f2 =
                        pool.submit<HttpResponse<String>> {
                            api.postJson("/api/sources/probe", """{"profile_id":"p1","query_id":"cpu"}""")
                        }
                    assertTrue(enteredTwo.await(5, TimeUnit.SECONDS))
                    val third = api.postJson("/api/sources/probe", """{"profile_id":"p1","query_id":"cpu"}""")
                    assertEquals(409, third.statusCode())
                    assertEquals("BUSY", third.errorCode())
                    releaseTwo.countDown()
                    assertEquals(200, f1.get(5, TimeUnit.SECONDS).statusCode())
                    assertEquals(200, f2.get(5, TimeUnit.SECONDS).statusCode())
                } finally {
                    releaseTwo.countDown()
                    pool.shutdownNow()
                }
            }
        }

    @Test
    fun `semantic-hash matches analysis identity and rejects invalid snapshots, missing Content-Length and wrong media type`() =
        withServer(connectionsPath = null) { store, api ->
            val runId = uploadJtl(store)
            val loadSha = store.requireInput(runId).sha256
            val snapshotJson =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadSha",""" +
                    """"start_epoch_ms":1767225600000,"step_ms":1000,"point_count":3,""" +
                    """"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system",""" +
                    """"aggregation":"interval_mean","values":[0.1,0.2,0.3]}],"windows":[]}"""
            val expectedSemantic = (validateResourceSnapshot(snapshotJson.byteInputStream()) as ResourceValidation.Valid).semanticSha256

            val hashed = api.postJson("/api/resources/semantic-hash", snapshotJson)
            assertEquals(200, hashed.statusCode(), hashed.body())
            val doc = hashed.json().jsonObject
            assertEquals(emptyList<String>(), SchemaLite.errors(doc, SchemaLite.schema("resource-hash")), hashed.body())
            assertEquals(expectedSemantic, doc.getValue("semantic_sha256").jsonPrimitive.content)

            val invalid = api.postJson("/api/resources/semantic-hash", "{}")
            assertEquals(422, invalid.statusCode())
            assertEquals("INVALID_RESOURCES", invalid.errorCode())

            val wrongMedia = api.postRaw("/api/resources/semantic-hash", "text/plain", snapshotJson.encodeToByteArray(), api.authHeaders())
            assertEquals(415, wrongMedia.statusCode())

            val tooBig =
                api.postRaw(
                    "/api/resources/semantic-hash",
                    "application/json",
                    ByteArray(32 * 1024 * 1024 + 1) { ' '.code.toByte() },
                    api.authHeaders(),
                )
            assertEquals(413, tooBig.statusCode())
        }

    private fun uploadJtl(store: RunBundleStore): String =
        Files.newInputStream(Path.of("fixtures/slice1/normalization/spike-drop.jtl")).use {
            store.acceptInput(it, "spike-drop.jtl").runId
        }

    private fun awaitTerminal(
        api: Client,
        jobId: String,
    ): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val status = api.get("/api/jobs/$jobId").json().jsonObject
            val state = status.getValue("state").jsonPrimitive.content
            if (state in setOf("COMPLETE", "FAILED", "CANCELLED")) return status
            LockSupport.parkNanos(10_000_000)
        }
        error("job $jobId did not finish")
    }

    private fun singlePromJson(
        id: String,
        port: Int,
    ): String =
        """{"schema_version":"source-connections.v1","connections":[{
          "id":"$id","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:$port",
          "governor":{"requests_per_second":100,"max_concurrent":4,"timeout_ms":2000,"max_attempts":1},
          "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
            "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}]}"""

    private fun twoProfileJson(
        id1: String,
        id2: String,
        port: Int,
    ): String =
        """{"schema_version":"source-connections.v1","connections":[
          {"id":"$id1","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:$port",
           "governor":{"requests_per_second":100,"max_concurrent":4,"timeout_ms":2000,"max_attempts":1},
           "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
             "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]},
          {"id":"$id2","source_kind":"prometheus","transport":"direct","base_url":"http://127.0.0.1:$port",
           "governor":{"requests_per_second":100,"max_concurrent":4,"timeout_ms":2000,"max_attempts":1},
           "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
             "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}
        ]}"""

    private fun grafanaPromJson(
        id: String,
        port: Int,
    ): String =
        """{"schema_version":"source-connections.v1","connections":[{
          "id":"$id","source_kind":"prometheus","transport":"grafana_proxy","base_url":"http://127.0.0.1:$port/grafana",
          "datasource_uid":"vm",
          "governor":{"requests_per_second":100,"max_concurrent":4,"timeout_ms":2000,"max_attempts":1},
          "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])","metric":"cpu_used","unit":"ratio",
            "entity":"host","role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}]}]}"""

    private fun queryParam(
        exchange: HttpExchange,
        name: String,
    ): String? =
        exchange.requestURI.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter("$name=")

    private fun withServer(
        connectionsPath: Path?,
        clock: Clock = Clock.systemUTC(),
        block: (RunBundleStore, Client) -> Unit,
    ) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            val registry = SourceRegistry(connectionsPath, clock = clock)
            val service = AnalysisService(store, EngineConfig())
            AnalysisJobs(1) { request, progress, cancelled ->
                analyzeWithSources(service, request, registry.current().promql, progress, cancelled, cancelled::beforePublish)
            }.use { jobs ->
                startLocalServer(
                    LocalApiContext(store, jobs, sourceRegistry = registry),
                    openBrowser = false,
                ).use { server ->
                    val api = Client(server.origin)
                    api.bootstrap()
                    block(store, api)
                }
            }
        }
    }

    private inline fun withStub(block: (Stub) -> Unit) {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.executor = Executors.newCachedThreadPool { task -> Thread(task, "api-probe-stub").apply { isDaemon = true } }
        val stub = Stub(http.address.port)
        http.createContext("/") { exchange ->
            stub.requests.incrementAndGet()
            stub.handler(exchange)
        }
        http.start()
        try {
            block(stub)
        } finally {
            http.stop(0)
        }
    }

    private class Stub(
        val port: Int,
    ) {
        val requests = AtomicInteger()

        @Volatile
        var handler: (HttpExchange) -> Unit = {}
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) = respondBytes(status, body.toByteArray(UTF_8))

    private fun HttpExchange.respondBytes(
        status: Int,
        bytes: ByteArray,
    ) {
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpResponse<String>.json() = Json.parseToJsonElement(body())

    private fun HttpResponse<String>.profileIds(): List<String> {
        val items =
            json()
                .jsonObject
                .getValue("profiles")
                .jsonArray
        return items.map {
            it
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content
        }
    }

    private fun HttpResponse<String>.errorCode(): String =
        json()
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private class Part(
        val name: String,
        val bytes: ByteArray,
        val filename: String? = null,
        val contentType: String? = null,
    )

    private class Client(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        private var cookie = ""
        private var csrf = ""

        fun bootstrap() {
            val response = get("/api/bootstrap")
            assertEquals(200, response.statusCode())
            cookie =
                response
                    .headers()
                    .firstValue("set-cookie")
                    .orElseThrow()
                    .substringBefore(';')
            val bootstrapObj = Json.parseToJsonElement(response.body()).jsonObject
            csrf = bootstrapObj.getValue("csrf_token").jsonPrimitive.content
        }

        fun authHeaders(): Map<String, String> = mapOf("Origin" to origin, "Cookie" to cookie, "X-LTV-CSRF" to csrf)

        fun get(path: String): HttpResponse<String> =
            client.send(
                HttpRequest
                    .newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(UTF_8),
            )

        fun postEmpty(path: String): HttpResponse<String> = postRaw(path, null, ByteArray(0), authHeaders())

        fun postJson(
            path: String,
            jsonBody: String,
        ): HttpResponse<String> = postRaw(path, "application/json", jsonBody.encodeToByteArray(), authHeaders())

        fun postRaw(
            path: String,
            contentType: String?,
            body: ByteArray,
            headers: Map<String, String>,
        ): HttpResponse<String> {
            val builder =
                HttpRequest
                    .newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            if (contentType != null) builder.header("Content-Type", contentType)
            headers.forEach(builder::header)
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
        }

        fun multipart(
            path: String,
            parts: List<Part>,
        ): HttpResponse<String> {
            val boundary = "ltv-probe-boundary"
            val out = ByteArrayOutputStream()
            parts.forEach { part ->
                out.write("--$boundary\r\n".toByteArray(UTF_8))
                val file = part.filename?.let { "; filename=\"$it\"" }.orEmpty()
                out.write("Content-Disposition: form-data; name=\"${part.name}\"$file\r\n".toByteArray(UTF_8))
                part.contentType?.let { out.write("Content-Type: $it\r\n".toByteArray(UTF_8)) }
                out.write("\r\n".toByteArray(UTF_8))
                out.write(part.bytes)
                out.write("\r\n".toByteArray(UTF_8))
            }
            out.write("--$boundary--\r\n".toByteArray(UTF_8))
            return postRaw(path, "multipart/form-data; boundary=$boundary", out.toByteArray(), authHeaders())
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = current

        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }
}

package io.ltverdict.sources

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SourceHttpTest {
    @Test
    fun `OpenSearch does not persist HTTP success bodies that fail response validation`() =
        withServer { fixture ->
            var body = """{"error":"private source diagnostic"}"""
            fixture.server.createContext("/") { exchange -> exchange.respond(200, body) }
            val sourceProfile = profile(fixture.baseUrl, governor = governor())
                .copy(sourceKind = SourceKind.OPENSEARCH, queries = emptyList(), openSearch = OpenSearchMapping(
                    listOf("logs-*"), "@timestamp", "service", "type", "message", samplesPerGroup = 0,
                ))
            val source = PromqlSource(listOf(sourceProfile), SourceHttp(listOf(sourceProfile)))
            listOf(body, "{malformed private source diagnostic").forEach { response ->
                body = response
                val result = source.acquire(SourceRequest(sourceProfile.id, 1000, 3000, 1000), "a".repeat(64))
                assertEquals(setOf("source-acquisition.json"), result.artifacts.keys)
                assertTrue(result.contextEvidence.isEmpty())
                assertFalse(result.evidence.toString().contains("private"))
            }
        }

    @Test
    fun `OpenSearch search retries within budget and acquisition failure is not zero errors`() =
        withServer { fixture ->
            val attempts = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                attempts.incrementAndGet()
                assertEquals("POST", exchange.requestMethod)
                assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
                exchange.respond(503, "sensitive source failure")
            }
            val sourceProfile =
                profile(fixture.baseUrl, governor = governor().copy(maxAttempts = 2))
                    .copy(
                        sourceKind = SourceKind.OPENSEARCH,
                        queries = emptyList(),
                        openSearch =
                            OpenSearchMapping(
                                listOf("logs-*"),
                                "@timestamp",
                                "service",
                                "type",
                                "message",
                                samplesPerGroup = 0,
                            ),
                    )
            val http = SourceHttp(listOf(sourceProfile))
            val result =
                PromqlSource(listOf(sourceProfile), http)
                    .acquire(SourceRequest(sourceProfile.id, 1000, 3000, 1000), "a".repeat(64))
            assertEquals(2, attempts.get())
            assertEquals(null, result.snapshot)
            assertTrue(result.contextEvidence.isEmpty())
            assertEquals(setOf("source-acquisition.json"), result.artifacts.keys)
            assertTrue(result.evidence.toString().contains("SOURCE_HTTP_5XX"))
            assertFalse(result.evidence.toString().contains("sensitive"))
            val budget = SourceBudget(0)
            assertEquals(
                "SOURCE_REQUEST_CAP_EXCEEDED",
                assertThrows(SourceHttpFailure::class.java) {
                    http.search(sourceProfile, "{}".encodeToByteArray(), budget)
                }.code,
            )
            assertEquals(2, attempts.get())
        }

    @Test
    fun `unrelated origin cannot shorten connection timeout`() {
        val short = profile(URI("http://short.example.test"), governor = governor(timeoutMillis = 1), id = "short")
        val normal = profile(URI("http://normal.example.test"), governor = governor(timeoutMillis = 30_000), id = "normal")
        val http = SourceHttp(listOf(short, normal))
        val client =
            SourceHttp::class.java.getDeclaredField("client").run {
                isAccessible = true
                get(http) as HttpClient
            }

        assertTrue(client.connectTimeout().isEmpty || client.connectTimeout().orElseThrow().toMillis() >= 30_000)
    }

    @Test
    fun `direct and grafana requests stay on their fixed endpoints with encoded query and env auth`() =
        withServer { fixture ->
            val paths = CopyOnWriteArrayList<String>()
            val queries = CopyOnWriteArrayList<String>()
            val headers = CopyOnWriteArrayList<String?>()
            fixture.server.createContext("/") { exchange ->
                paths += exchange.requestURI.rawPath
                queries += exchange.requestURI.rawQuery
                headers += exchange.requestHeaders.getFirst("Authorization")
                exchange.respond(200, "ok")
            }
            val direct = profile(fixture.baseUrl.resolve("/tenant"), SourceTransport.DIRECT, SourceAuth.Bearer("TOKEN"))
            val proxy =
                profile(
                    fixture.baseUrl.resolve("/grafana"),
                    SourceTransport.GRAFANA_PROXY,
                    SourceAuth.Basic("USER", "PASSWORD"),
                    datasourceUid = "vm-main",
                    id = "proxy",
                )
            val http = SourceHttp(listOf(direct, proxy)) { name -> mapOf("TOKEN" to "a+b", "USER" to "u", "PASSWORD" to "p")[name] }

            assertArrayEquals("ok".encodeToByteArray(), http.get(direct, linkedMapOf("query" to "a + b", "start" to "1"), SourceBudget()))
            assertArrayEquals("ok".encodeToByteArray(), http.get(proxy, mapOf("query" to "up"), SourceBudget()))

            assertEquals(listOf("/tenant/api/v1/query_range", "/grafana/api/datasources/proxy/uid/vm-main/api/v1/query_range"), paths)
            assertEquals(listOf("query=a%20%2B%20b&start=1", "query=up"), queries)
            assertEquals("Bearer a+b", headers[0])
            assertEquals("Basic dTpw", headers[1])
        }

    @Test
    fun `same normalized origin uses strictest rate and waits one interval after construction`() =
        withServer { fixture ->
            val arrivals = CopyOnWriteArrayList<Long>()
            fixture.server.createContext("/") { exchange ->
                arrivals += System.nanoTime()
                exchange.respond(200, "ok")
            }
            val fast = profile(fixture.baseUrl, governor = governor(requestsPerSecond = 20.0), id = "fast")
            val strict =
                profile(
                    URI("http://LOCALHOST:${fixture.server.address.port}/other"),
                    governor = governor(requestsPerSecond = 5.0),
                    id = "strict",
                )
            val http = SourceHttp(listOf(fast, strict))
            Thread.sleep(250)
            val started = System.nanoTime()
            val firstBudget = SourceBudget()
            val secondBudget = SourceBudget()

            http.get(fast, mapOf("query" to "up"), firstBudget)
            http.get(strict, mapOf("query" to "up"), secondBudget)

            assertTrue(TimeUnit.NANOSECONDS.toMillis(arrivals[0] - started) >= 150, arrivals.toString())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(arrivals[1] - arrivals[0]) >= 150, arrivals.toString())
            assertTrue(firstBudget.throttleWaitMillis >= 150)
            assertTrue(secondBudget.throttleWaitMillis >= 150)
        }

    @Test
    fun `same origin applies strictest concurrency across profiles`() =
        withServer { fixture ->
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                val now = active.incrementAndGet()
                maximum.accumulateAndGet(now, ::maxOf)
                if (firstEntered.count > 0) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                exchange.respond(200, "ok")
                active.decrementAndGet()
            }
            val loose = profile(fixture.baseUrl, governor = governor(maxConcurrent = 2), id = "loose")
            val strict = profile(fixture.baseUrl.resolve("/other"), governor = governor(maxConcurrent = 1), id = "strict")
            val http = SourceHttp(listOf(loose, strict))
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<ByteArray> { http.get(loose, mapOf("query" to "a"), SourceBudget()) }
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
                val second = executor.submit<ByteArray> { http.get(strict, mapOf("query" to "b"), SourceBudget()) }

                assertFalse(second.isDone)
                Thread.sleep(75)
                assertEquals(1, maximum.get())
                releaseFirst.countDown()
                first.get(2, TimeUnit.SECONDS)
                second.get(2, TimeUnit.SECONDS)
                assertEquals(1, maximum.get())
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `requests queued behind concurrency cannot spend future rate tokens`() =
        withServer { fixture ->
            val arrivals = CopyOnWriteArrayList<Long>()
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val attempts = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                arrivals += System.nanoTime()
                if (attempts.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                exchange.respond(200, "ok")
            }
            val profile = profile(fixture.baseUrl, governor = governor(requestsPerSecond = 5.0))
            val http = SourceHttp(listOf(profile))
            val executor = Executors.newFixedThreadPool(3)
            try {
                val first = executor.submit<ByteArray> { http.get(profile, mapOf("query" to "a"), SourceBudget()) }
                assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
                val second = executor.submit<ByteArray> { http.get(profile, mapOf("query" to "b"), SourceBudget()) }
                val third = executor.submit<ByteArray> { http.get(profile, mapOf("query" to "c"), SourceBudget()) }

                Thread.sleep(450)
                releaseFirst.countDown()
                first.get(2, TimeUnit.SECONDS)
                second.get(2, TimeUnit.SECONDS)
                third.get(2, TimeUnit.SECONDS)

                assertEquals(3, arrivals.size)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(arrivals[2] - arrivals[1]) >= 150, arrivals.toString())
            } finally {
                releaseFirst.countDown()
                executor.shutdownNow()
            }
        }

    @Test
    fun `retry after is honored and every attempt updates the shared budget`() =
        withServer { fixture ->
            val attempts = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                if (attempts.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("Retry-After", "1")
                    exchange.respond(429, "slow down")
                } else {
                    exchange.respond(200, "ok")
                }
            }
            val profile = profile(fixture.baseUrl, governor = governor(maxAttempts = 2))
            val budget = SourceBudget()
            val started = System.nanoTime()

            assertArrayEquals("ok".encodeToByteArray(), SourceHttp(listOf(profile)).get(profile, mapOf("query" to "up"), budget))

            assertEquals(2, attempts.get())
            assertEquals(2, budget.requestCount)
            assertEquals(1, budget.retries)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 900)
            assertTrue(budget.throttleWaitMillis >= 900)
            assertFalse(budget.capExceeded)
        }

    @Test
    fun `request cap stops retries before another HTTP attempt`() =
        withServer { fixture ->
            val attempts = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                attempts.incrementAndGet()
                exchange.respond(503, "top-secret")
            }
            val profile = profile(fixture.baseUrl, governor = governor(maxAttempts = 3))
            val budget = SourceBudget(1)

            val failure =
                assertThrows(SourceHttpFailure::class.java) {
                    SourceHttp(listOf(profile)).get(profile, mapOf("query" to "up"), budget)
                }

            assertEquals("SOURCE_REQUEST_CAP_EXCEEDED", failure.code)
            assertEquals(1, attempts.get())
            assertEquals(1, budget.requestCount)
            assertEquals(0, budget.retries)
            assertTrue(budget.capExceeded)
            assertTrue("top-secret" !in failure.toString())
        }

    @Test
    fun `timeout and oversized body fail with bounded safe codes`() =
        withServer { fixture ->
            val slow = profile(fixture.baseUrl.resolve("/slow-base"), governor = governor(timeoutMillis = 50), id = "slow")
            val large = profile(fixture.baseUrl.resolve("/large-base"), id = "large")
            fixture.server.createContext("/slow-base/api/v1/query_range") { exchange ->
                Thread.sleep(250)
                runCatching { exchange.respond(200, "top-secret") }
            }
            fixture.server.createContext("/large-base/api/v1/query_range") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                try {
                    val chunk = ByteArray(8 * 1024)
                    repeat((16 * 1024 * 1024) / chunk.size + 1) { exchange.responseBody.write(chunk) }
                } catch (_: IOException) {
                    // Expected after client cancellation.
                } finally {
                    exchange.close()
                }
            }
            val timeout = assertThrows(SourceHttpFailure::class.java) { SourceHttp(listOf(slow)).get(slow, emptyMap(), SourceBudget()) }
            val oversized = assertThrows(SourceHttpFailure::class.java) { SourceHttp(listOf(large)).get(large, emptyMap(), SourceBudget()) }

            assertEquals("SOURCE_TIMEOUT", timeout.code)
            assertEquals("SOURCE_RESPONSE_TOO_LARGE", oversized.code)
            assertTrue("top-secret" !in timeout.toString())
            assertTrue("top-secret" !in oversized.toString())
        }

    @Test
    fun `cancellation interrupts an in flight request and authentication errors expose no env or secret`() =
        withServer { fixture ->
            val entered = AtomicBoolean()
            fixture.server.createContext("/") { exchange ->
                entered.set(true)
                Thread.sleep(2_000)
                runCatching { exchange.respond(200, "late") }
            }
            val anonymous = profile(fixture.baseUrl, governor = governor(timeoutMillis = 5_000))
            val missingAuth = profile(fixture.baseUrl.resolve("/auth"), auth = SourceAuth.Bearer("TOP_SECRET_ENV"), id = "auth")
            val http = SourceHttp(listOf(anonymous, missingAuth)) { null }

            assertThrows(CancellationException::class.java) {
                http.get(anonymous, emptyMap(), SourceBudget()) {
                    if (entered.get()) throw CancellationException("cancelled")
                }
            }
            val authFailure = assertThrows(SourceHttpFailure::class.java) { http.get(missingAuth, emptyMap(), SourceBudget()) }

            assertEquals("SOURCE_AUTH_UNAVAILABLE", authFailure.code)
            assertTrue("TOP_SECRET_ENV" !in authFailure.toString())
        }

    @Test
    fun `HTTP error body redirect and URL are not exposed`() =
        withServer { fixture ->
            val redirected = AtomicInteger()
            fixture.server.createContext("/api/v1/query_range") { exchange ->
                exchange.responseHeaders.add("Location", "${fixture.baseUrl}/redirected?token=top-secret")
                exchange.respond(302, "raw top-secret body")
            }
            fixture.server.createContext("/redirected") { exchange ->
                redirected.incrementAndGet()
                exchange.respond(200, "wrong")
            }
            val profile = profile(fixture.baseUrl)

            val failure =
                assertThrows(SourceHttpFailure::class.java) { SourceHttp(listOf(profile)).get(profile, emptyMap(), SourceBudget()) }

            assertEquals("SOURCE_HTTP_STATUS", failure.code)
            assertEquals(0, redirected.get())
            assertEquals("io.ltverdict.sources.SourceHttpFailure: SOURCE_HTTP_STATUS", failure.toString())
        }

    private fun profile(
        baseUrl: URI,
        transport: SourceTransport = SourceTransport.DIRECT,
        auth: SourceAuth = SourceAuth.None,
        datasourceUid: String? = null,
        governor: SourceGovernor = governor(),
        id: String = "profile",
    ): SourceProfile =
        SourceProfile(
            id,
            SourceKind.PROMETHEUS,
            transport,
            baseUrl,
            datasourceUid,
            auth,
            true,
            governor,
            listOf(
                SourceQuery(
                    "q",
                    "rate(x[${'$'}__interval])",
                    "x",
                    "ratio",
                    "entity",
                    ResourceRole.SYSTEM,
                    ResourceAggregation.INTERVAL_RATE,
                    emptyMap(),
                ),
            ),
        )

    private fun governor(
        requestsPerSecond: Double = 1_000.0,
        maxConcurrent: Int = 1,
        timeoutMillis: Long = 2_000,
        maxAttempts: Int = 1,
    ) = SourceGovernor(requestsPerSecond, 1, maxConcurrent, timeoutMillis, maxAttempts, true, null)

    private inline fun withServer(block: (ServerFixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "source-http-test").apply { isDaemon = true } }
        server.executor = executor
        server.start()
        try {
            block(ServerFixture(server, URI("http://localhost:${server.address.port}")))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class ServerFixture(
        val server: HttpServer,
        val baseUrl: URI,
    )
}

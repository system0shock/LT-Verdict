package io.ltverdict.integrations.jenkins

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JenkinsWorkflowTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `uncertain POST reconciles without duplicate trigger and journal contains no secrets`() =
        withServer { fixture ->
            val postCount = AtomicInteger()
            val intentExistedBeforePost = AtomicBoolean()
            val postedBody = AtomicReference("")
            fixture.server.createContext("/") { exchange ->
                when {
                    exchange.requestURI.path == "/crumbIssuer/api/json" -> exchange.respond(404, "")
                    exchange.requestURI.path == "/job/perf/buildWithParameters" -> {
                        postCount.incrementAndGet()
                        val journal = temporaryDirectory.resolve("attempt-1.jsonl")
                        intentExistedBeforePost.set(Files.exists(journal) && Files.readString(journal).contains("TRIGGER_INTENT"))
                        postedBody.set(exchange.requestBody.readAllBytes().decodeToString())
                        exchange.close()
                    }
                    exchange.requestURI.path == "/queue/api/json" -> exchange.respond(200, """{"items":[]}""")
                    exchange.requestURI.path == "/job/perf/api/json" -> exchange.respond(200, """{"builds":[]}""")
                    else -> exchange.respond(404, "")
                }
            }
            val workflow = workflow(fixture.baseUrl, reconciliationPolls = 1)

            val afterPost = workflow.trigger(JenkinsTriggerRequest(mapOf("SCENARIO" to "steady", "PASSWORD" to "job-secret")))
            val reconciled = workflow.reconcile(afterPost.attemptId)

            assertEquals(JenkinsStatus.RECONCILING, afterPost.status)
            assertEquals(JenkinsStatus.TRIGGER_UNKNOWN, reconciled.status)
            assertEquals(1, postCount.get())
            assertTrue(intentExistedBeforePost.get())
            assertTrue(postedBody.get().contains("LT_VERDICT_TRIGGER_ATTEMPT_ID=attempt-1"))
            val journal = Files.readString(temporaryDirectory.resolve("attempt-1.jsonl"))
            assertFalse(journal.contains("job-secret"))
            assertFalse(journal.contains("api-secret"))
            assertFalse(journal.contains("jenkins-user"))
        }

    @Test
    fun `queue build and verified artifact reach artifact ready through bounded streaming`() =
        withServer { fixture ->
            val artifact = "timeStamp,elapsed,label\n1,2,request\n".encodeToByteArray()
            val downloads = AtomicInteger()
            fixture.server.createContext("/") { exchange ->
                when (exchange.requestURI.path) {
                    "/crumbIssuer/api/json" -> exchange.respond(404, "")
                    "/job/perf/buildWithParameters" -> {
                        exchange.responseHeaders.add("Location", fixture.baseUrl.resolve("/queue/item/7/").toString())
                        exchange.respond(201, "")
                    }
                    "/queue/item/7/api/json" ->
                        exchange.respond(
                            200,
                            """{"cancelled":false,"executable":{"number":42,"url":"${fixture.baseUrl.resolve("/job/perf/42/")}"}}""",
                        )
                    "/job/perf/42/api/json" ->
                        exchange.respond(
                            200,
                            """{"building":false,"result":"SUCCESS","artifacts":[{"fileName":"results.jtl","relativePath":"run/results.jtl"}]}""",
                        )
                    "/job/perf/42/artifact/run/results.jtl" -> {
                        downloads.incrementAndGet()
                        exchange.respond(200, artifact)
                    }
                    else -> exchange.respond(404, "")
                }
            }
            val workflow = workflow(fixture.baseUrl)

            val queued = workflow.trigger(JenkinsTriggerRequest(mapOf("SCENARIO" to "steady")))
            val running = workflow.advance(queued.attemptId)
            val awaiting = workflow.advance(queued.attemptId)
            val ready =
                workflow.collectArtifact(
                    queued.attemptId,
                    ArtifactExpectation("run/results.jtl", artifact.size.toLong(), sha256(artifact)),
                    temporaryDirectory.resolve("download"),
                )
            val repeated =
                workflow.collectArtifact(
                    queued.attemptId,
                    ArtifactExpectation("run/results.jtl", artifact.size.toLong(), sha256(artifact)),
                    temporaryDirectory.resolve("download"),
                )
            val recovered = workflow(fixture.baseUrl).listStates().single()

            assertEquals(JenkinsStatus.QUEUED, queued.status)
            assertEquals(JenkinsStatus.RUNNING, running.status)
            assertEquals(42L, running.buildNumber)
            assertEquals(JenkinsStatus.AWAITING_ARTIFACT, awaiting.status)
            assertEquals(JenkinsStatus.ARTIFACT_READY, ready.status)
            assertEquals(sha256(artifact), ready.artifact?.sha256)
            assertEquals(artifact.size.toLong(), ready.artifact?.sizeBytes)
            assertEquals(artifact.toList(), Files.readAllBytes(requireNotNull(ready.artifact).path).toList())
            assertEquals(JenkinsStatus.ARTIFACT_READY, repeated.status)
            assertEquals(JenkinsStatus.ARTIFACT_READY, recovered.status)
            assertEquals(1, downloads.get())
        }

    @Test
    fun `missing and corrupt artifacts never become load results`() =
        withServer { fixture ->
            val artifactListed = AtomicBoolean(false)
            fixture.server.createContext("/") { exchange ->
                when (exchange.requestURI.path) {
                    "/crumbIssuer/api/json" -> exchange.respond(404, "")
                    "/job/perf/buildWithParameters" -> {
                        exchange.responseHeaders.add("Location", fixture.baseUrl.resolve("/queue/item/8/").toString())
                        exchange.respond(201, "")
                    }
                    "/queue/item/8/api/json" ->
                        exchange.respond(
                            200,
                            """{"cancelled":false,"executable":{"number":43,"url":"${fixture.baseUrl.resolve("/job/perf/43/")}"}}""",
                        )
                    "/job/perf/43/api/json" -> {
                        val artifacts =
                            if (artifactListed.get()) {
                                """[{"fileName":"results.jtl","relativePath":"run/results.jtl"}]"""
                            } else {
                                "[]"
                            }
                        exchange.respond(200, """{"building":false,"result":"SUCCESS","artifacts":$artifacts}""")
                    }
                    "/job/perf/43/artifact/run/results.jtl" -> exchange.respond(200, "corrupt".encodeToByteArray())
                    else -> exchange.respond(404, "")
                }
            }
            val workflow = workflow(fixture.baseUrl)
            val queued = workflow.trigger(JenkinsTriggerRequest(mapOf("SCENARIO" to "steady")))
            workflow.advance(queued.attemptId)
            workflow.advance(queued.attemptId)

            val missing =
                workflow.collectArtifact(
                    queued.attemptId,
                    ArtifactExpectation("run/results.jtl"),
                    temporaryDirectory.resolve("missing"),
                )
            artifactListed.set(true)
            val corruptDestination = temporaryDirectory.resolve("corrupt")
            val corrupt =
                workflow.collectArtifact(
                    queued.attemptId,
                    ArtifactExpectation("run/results.jtl", sha256 = "0".repeat(64)),
                    corruptDestination,
                )

            assertEquals(JenkinsStatus.AWAITING_ARTIFACT, missing.status)
            assertEquals(JenkinsStatus.FAILED, corrupt.status)
            assertEquals("JENKINS_ARTIFACT_HASH_MISMATCH", corrupt.failureCode)
            assertFalse(Files.exists(corruptDestination.resolve("run/results.jtl")))
            assertTrue(!Files.exists(corruptDestination) || Files.walk(corruptDestination).use { it.noneMatch(Files::isRegularFile) })
        }

    @Test
    fun `ambiguous correlation becomes trigger unknown`() =
        withServer { fixture ->
            fixture.server.createContext("/") { exchange ->
                when (exchange.requestURI.path) {
                    "/crumbIssuer/api/json" -> exchange.respond(404, "")
                    "/job/perf/buildWithParameters" -> exchange.close()
                    "/queue/api/json" ->
                        exchange.respond(
                            200,
                            """{"items":[${queueItem(fixture.baseUrl, 1)},${queueItem(fixture.baseUrl, 2)}]}""",
                        )
                    "/job/perf/api/json" -> exchange.respond(200, """{"builds":[]}""")
                    else -> exchange.respond(404, "")
                }
            }
            val workflow = workflow(fixture.baseUrl, reconciliationPolls = 1)
            val triggered = workflow.trigger(JenkinsTriggerRequest(mapOf("SCENARIO" to "steady")))

            val reconciled = workflow.reconcile(triggered.attemptId)

            assertEquals(JenkinsStatus.TRIGGER_UNKNOWN, reconciled.status)
            assertEquals("JENKINS_TRIGGER_AMBIGUOUS", reconciled.failureCode)
        }

    private fun workflow(
        baseUrl: URI,
        reconciliationPolls: Int = 1,
    ): JenkinsWorkflow =
        JenkinsWorkflow(
            profile =
                JenkinsProfile(
                    id = "perf",
                    controller = baseUrl,
                    jobPath = "job/perf",
                    auth = JenkinsAuth("JENKINS_USER", "JENKINS_TOKEN"),
                    allowInsecureHttp = true,
                    parameterNames = setOf("SCENARIO", "PASSWORD"),
                    sensitiveParameterNames = setOf("PASSWORD"),
                    artifactPaths = setOf("run/results.jtl"),
                    correlationParameter = "LT_VERDICT_TRIGGER_ATTEMPT_ID",
                    timeout = Duration.ofSeconds(2),
                    pollInterval = Duration.ZERO,
                    reconciliationPolls = reconciliationPolls,
                    maxArtifactBytes = 1024,
                ),
            journalRoot = temporaryDirectory,
            environment = { name -> mapOf("JENKINS_USER" to "jenkins-user", "JENKINS_TOKEN" to "api-secret")[name] },
            clock = { Instant.parse("2026-09-22T00:00:00Z") },
            attemptIds = { "attempt-1" },
            sleeper = {},
        )

    private fun queueItem(
        baseUrl: URI,
        id: Int,
    ): String =
        """{"id":$id,"url":"${baseUrl.resolve(
            "/queue/item/$id/",
        )}","actions":[{"parameters":[{"name":"LT_VERDICT_TRIGGER_ATTEMPT_ID","value":"attempt-1"}]}]}"""

    private inline fun withServer(block: (ServerFixture) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "jenkins-test").apply { isDaemon = true } }
        server.executor = executor
        server.start()
        try {
            block(ServerFixture(server, URI("http://localhost:${server.address.port}/")))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
    ) = respond(status, body.toByteArray(StandardCharsets.UTF_8))

    private fun HttpExchange.respond(
        status: Int,
        body: ByteArray,
    ) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private data class ServerFixture(
        val server: HttpServer,
        val baseUrl: URI,
    )
}

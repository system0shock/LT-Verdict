package io.ltverdict.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.server.application.plugin
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingRoot
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.podViewTestJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.integrations.jenkins.JenkinsAuth
import io.ltverdict.integrations.jenkins.JenkinsProfile
import io.ltverdict.integrations.jenkins.JenkinsProfileSummary
import io.ltverdict.integrations.jenkins.JenkinsWorkflow
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.SourceAuth
import io.ltverdict.sources.SourceGovernor
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceKind
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceTransport
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
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
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * W2.2 characterization of the HTTP layer: the route table and a sweep over every route are compared with files captured
 * from `origin/main` BEFORE `installLocalApi` was split into per-resource route files. Only non-deterministic values (job
 * ids, ISO timestamps, release ids, the CSRF token) are normalized; everything else, including report bodies, is compared by
 * SHA-256. Regenerate only on purpose with `LTV_UPDATE_HTTP_SNAPSHOT=1` (a change here is a change of the HTTP contract).
 */
class LocalApiSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val routesFile = Path.of("fixtures/http-layer/routes.txt")
    private val responsesFile = Path.of("fixtures/http-layer/responses.txt")

    @Test
    fun `route table and the responses of every route equal the snapshot taken before the split`() {
        val lines = mutableListOf<String>()
        val routes = mutableListOf<String>()
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                routes += routeTable(LocalApiContext(store, jobs))
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    Sweep(server.origin, store, lines).run()
                }
            }
        }
        integrations(lines)
        val routeText = routes.sorted().joinToString("\n", postfix = "\n")
        val responseText = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("LTV_UPDATE_HTTP_SNAPSHOT") == "1") {
            Files.createDirectories(routesFile.parent)
            Files.writeString(routesFile, routeText)
            Files.writeString(responsesFile, responseText)
        }
        assertTrue(routes.size >= 53, "route table is suspiciously small: ${routes.size}")
        assertEquals(readSnapshot(routesFile), routeText)
        val expected = readSnapshot(responsesFile).lines()
        val actual = responseText.lines()
        val differing = expected.zip(actual).filter { (a, b) -> a != b }
        assertEquals(emptyList<Pair<String, String>>(), differing, "responses differ from the snapshot")
        assertEquals(expected.size, actual.size)
    }

    /** Jenkins and Grafana configured against a local stub: the success paths that the unconfigured sweep cannot reach. */
    private fun integrations(lines: MutableList<String>) {
        val artifact = SPIKE_JTL.toByteArray(UTF_8)
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newCachedThreadPool { task -> Thread(task, "snapshot-stub").apply { isDaemon = true } }
        stub.executor = executor
        val base = URI("http://localhost:${stub.address.port}/")

        fun HttpExchange.respond(
            status: Int,
            body: ByteArray,
        ) {
            sendResponseHeaders(status, body.size.toLong())
            responseBody.use { it.write(body) }
        }
        stub.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/job/perf/buildWithParameters" -> {
                    exchange.responseHeaders.add("Location", base.resolve("/queue/item/7/").toString())
                    exchange.respond(201, ByteArray(0))
                }
                path == "/queue/item/7/api/json" ->
                    exchange.respond(
                        200,
                        """{"cancelled":false,"executable":{"number":42,"url":"${base.resolve("/job/perf/42/")}"}}""".toByteArray(),
                    )
                path == "/job/perf/42/api/json" ->
                    exchange.respond(
                        200,
                        (
                            """{"building":false,"result":"SUCCESS","artifacts":""" +
                                """[{"fileName":"results.jtl","relativePath":"run/results.jtl"}]}"""
                        ).toByteArray(),
                    )
                path == "/job/perf/42/artifact/run/results.jtl" -> exchange.respond(200, artifact)
                path.startsWith("/grafana/render/d-solo/") ->
                    exchange.respond(200, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3))
                else -> exchange.respond(404, ByteArray(0))
            }
        }
        stub.start()
        try {
            val grafana =
                SourceProfile(
                    "grafana",
                    SourceKind.PROMETHEUS,
                    SourceTransport.GRAFANA_PROXY,
                    base.resolve("/grafana"),
                    "vm",
                    SourceAuth.Bearer("TEST_TOKEN"),
                    true,
                    SourceGovernor(1_000.0, 1, 1, 2_000, 1, true, null),
                    emptyList(),
                )
            val jenkinsProfile =
                JenkinsProfile(
                    id = "perf",
                    controller = base,
                    jobPath = "job/perf",
                    auth = JenkinsAuth("JENKINS_USER", "JENKINS_TOKEN"),
                    allowInsecureHttp = true,
                    parameterNames = setOf("SCENARIO"),
                    sensitiveParameterNames = emptySet(),
                    artifactPaths = setOf("run/results.jtl"),
                    timeout = Duration.ofSeconds(2),
                    pollInterval = Duration.ZERO,
                    reconciliationPolls = 1,
                    maxArtifactBytes = 1_024,
                )
            val workflow =
                JenkinsWorkflow(
                    profile = jenkinsProfile,
                    journalRoot = Files.createDirectories(tempDir.resolve("jenkins-journal")),
                    environment = { name -> mapOf("JENKINS_USER" to "u", "JENKINS_TOKEN" to "t")[name] },
                    clock = { Instant.parse("2026-09-22T00:00:00Z") },
                    attemptIds = { "attempt-1" },
                    sleeper = {},
                )
            DataDirectory.open(tempDir.resolve("data-integrations-${System.nanoTime()}")).use { directory ->
                val store = RunBundleStore(directory)
                AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                    val context =
                        LocalApiContext(
                            store,
                            jobs,
                            sourceProfiles = listOf(grafana),
                            jenkinsProfiles =
                                listOf(
                                    JenkinsProfileSummary("perf", base.toString(), "job/perf", setOf("SCENARIO"), setOf("run/results.jtl")),
                                ),
                            jenkinsWorkflows = mapOf("perf" to workflow),
                            jenkinsArtifactRoot = Files.createDirectories(tempDir.resolve("jenkins-artifacts")),
                            sourceHttp = SourceHttp(listOf(grafana)) { "test-token" },
                        )
                    startLocalServer(context, openBrowser = false).use { server ->
                        Sweep(server.origin, store, lines).integrations()
                    }
                }
            }
        } finally {
            stub.stop(0)
            executor.shutdownNow()
        }
    }

    // A checkout with core.autocrlf stores the snapshot with CRLF; the generated text always has LF.
    private fun readSnapshot(file: Path): String = Files.readString(file).replace("\r\n", "\n")

    private fun routeTable(context: LocalApiContext): List<String> {
        // The engine of startLocalServer does not expose its application, so the routes are read from a twin server.
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { installLocalApi(context) }.start(wait = false)
        try {
            val found = mutableListOf<String>()

            fun walk(route: Route) {
                if (route.selector is HttpMethodRouteSelector || route.selector is PathSegmentTailcardRouteSelector) {
                    val text = route.toString()
                    if (text.startsWith("/api")) found += text
                }
                route.children.forEach(::walk)
            }
            walk(server.application.plugin(RoutingRoot))
            return found
        } finally {
            server.stop(100, 1_000)
        }
    }

    private class Sweep(
        origin: String,
        private val store: RunBundleStore,
        private val lines: MutableList<String>,
    ) {
        private val api = Client(origin)
        private val none = ByteArray(0)

        fun run() {
            // security and discovery
            record("GET /api/bootstrap", api.bootstrap())
            record("POST /api/policies/validate (no credentials)", api.postUnauthenticated("/api/policies/validate", none))

            // inputs and runs
            val upload = api.multipart("/api/inputs", listOf(Part("file", SPIKE_JTL.encodeToByteArray(), "spike-drop.jtl")))
            record("POST /api/inputs", upload)
            val runId =
                upload
                    .jsonObject()
                    .getValue("run_id")
                    .jsonPrimitive.content
            record("POST /api/inputs (not multipart)", api.post("/api/inputs", "application/json", none))
            record("GET /api/runs", api.get("/api/runs"))
            record("GET /api/runs?limit=0", api.get("/api/runs?limit=0"))
            record("GET /api/runs?limit=1&after=zzz", api.get("/api/runs?limit=1&after=zzz"))

            // policy validation
            val pass = Files.readAllBytes(Path.of("fixtures/slice1/policies/pass.json"))
            record("POST /api/policies/validate (valid)", api.post("/api/policies/validate", "application/json", pass))
            record(
                "POST /api/policies/validate (malformed)",
                api.post("/api/policies/validate", "application/json", "{".encodeToByteArray()),
            )
            record("POST /api/policies/validate (not json)", api.post("/api/policies/validate", "text/plain", pass))

            // jobs
            record("POST /api/jobs (not multipart)", api.post("/api/jobs", "application/json", none))
            record(
                "POST /api/jobs (unknown run)",
                api.multipart("/api/jobs", listOf(Part("run_id", "jmeter_jtl_csv-${"0".repeat(64)}".encodeToByteArray()))),
            )
            val bare = api.multipart("/api/jobs", listOf(Part("run_id", runId.encodeToByteArray())))
            record("POST /api/jobs (no policy)", bare, shapeOnly = true)
            val permissive = awaitComplete(bare.jobId())
            val permissiveJob =
                api.multipart(
                    "/api/jobs",
                    listOf(Part("run_id", runId.encodeToByteArray()), Part("policy", PERMISSIVE_POLICY, "policy.json", "application/json")),
                )
            record("POST /api/jobs (permissive policy)", permissiveJob, shapeOnly = true)
            val permId = awaitComplete(permissiveJob.jobId()).analysisId
            val resources = resourceSnapshot(store, runId)
            val viewBytes = podViewTestJson(sha(runId), semanticHash(resources), null, 1_767_225_600_000, 1_000, 3).encodeToByteArray()
            val full =
                api.multipart(
                    "/api/jobs",
                    listOf(
                        Part("run_id", runId.encodeToByteArray()),
                        Part("policy", PERMISSIVE_POLICY, "policy.json", "application/json"),
                        Part("resource_snapshot", resources, "resources.json", "application/json"),
                        Part("pod_view", viewBytes, "pod-view.json", "application/json"),
                    ),
                )
            record("POST /api/jobs (policy, resources, pod view)", full, shapeOnly = true)
            val withResources = awaitComplete(full.jobId())
            val passJob =
                api.multipart(
                    "/api/jobs",
                    listOf(Part("run_id", runId.encodeToByteArray()), Part("policy", pass, "policy.json", "application/json")),
                )
            record("POST /api/jobs (pass policy)", passJob, shapeOnly = true)
            val passStatus = awaitComplete(passJob.jobId())
            record(
                "POST /api/jobs (invalid resource snapshot)",
                api.multipart(
                    "/api/jobs",
                    listOf(
                        Part("run_id", runId.encodeToByteArray()),
                        Part("resource_snapshot", "{}".encodeToByteArray(), "resources.json", "application/json"),
                    ),
                ),
            )
            // Several defects in one request: the part that arrives first decides the answer, in either order.
            val badPolicy = Part("policy", """{"schema_version":"policy.v2"}""".encodeToByteArray(), "policy.json", "application/json")
            val badResources = Part("resource_snapshot", "{}".encodeToByteArray(), "resources.json", "application/json")
            val badCapacity = Part("capacity_plan", "{}".encodeToByteArray(), "capacity.json", "application/json")
            val badTrend = Part("trend_plan", "{}".encodeToByteArray(), "trend.json", "application/json")
            val badDiagnostics = Part("correlation_plan", "{}".encodeToByteArray(), "plan.json", "application/json")
            val badView = Part("pod_view", "{}".encodeToByteArray(), "view.json", "application/json")
            val unknown = Part("bogus", "x".encodeToByteArray(), "bogus.json", "application/json")
            val run = Part("run_id", runId.encodeToByteArray())
            val orders =
                mapOf(
                    "policy,resources" to listOf(run, badPolicy, badResources),
                    "resources,policy" to listOf(run, badResources, badPolicy),
                    "trend,capacity,diagnostics,view" to listOf(run, badTrend, badCapacity, badDiagnostics, badView),
                    "view,diagnostics,capacity,trend" to listOf(run, badView, badDiagnostics, badCapacity, badTrend),
                    "unknown,policy" to listOf(run, unknown, badPolicy),
                    "policy,unknown" to listOf(run, badPolicy, unknown),
                    "policy before run_id" to listOf(badPolicy, run),
                    "no run_id" to listOf(badPolicy),
                    "bad run_id" to listOf(Part("run_id", "x".encodeToByteArray())),
                    "duplicate run_id" to listOf(run, run),
                    "duplicate policy" to
                        listOf(run, Part("policy", pass, "p.json", "application/json"), Part("policy", pass, "p.json", "application/json")),
                )
            for ((name, parts) in orders) record("POST /api/jobs (parts: $name)", api.multipart("/api/jobs", parts))
            record("GET /api/jobs/{jobId} (complete)", api.get("/api/jobs/${permissive.jobId}"))
            record("GET /api/jobs/{jobId} (unknown)", api.get("/api/jobs/00000000-0000-0000-0000-000000000000"))
            record("GET /api/jobs?state=active", api.get("/api/jobs?state=active"))
            record("GET /api/jobs (no query)", api.get("/api/jobs"))
            record("DELETE /api/jobs/{jobId} (complete)", api.delete("/api/jobs/${permissive.jobId}"))
            record("DELETE /api/jobs/{jobId} (unknown)", api.delete("/api/jobs/00000000-0000-0000-0000-000000000000"))

            val bareId = permissive.analysisId
            val resourceId = withResources.analysisId
            val passId = passStatus.analysisId
            val base = "/api/runs/$runId/analyses"

            // analyses, results, reports, buckets
            record("GET /api/runs/{runId}/analyses", api.get(base))
            record("GET /api/runs/{runId}/analyses?limit=1", api.get("$base?limit=1"))
            record("GET /api/runs/{runId}/analyses?limit=0", api.get("$base?limit=0"))
            record("GET /api/runs/{unknown}/analyses", api.get("/api/runs/jmeter_jtl_csv-${"1".repeat(64)}/analyses"))
            for (id in listOf(bareId, resourceId, passId)) {
                val path = "$base/$id"
                record("GET .../{analysisId}/result", api.get("$path/result"))
                for (format in listOf("json", "html", "asciidoc", "confluence", "svg", "nope")) {
                    record("GET .../{analysisId}/report?format=$format", api.get("$path/report?format=$format"))
                }
                record("GET .../{analysisId}/report (no format)", api.get("$path/report"))
                for (rollup in listOf(1, 10, 30, 60, 5)) {
                    record("GET .../{analysisId}/buckets?rollup=$rollup", api.get("$path/buckets?rollup=$rollup"))
                }
                record("GET .../{analysisId}/buckets?rollup=1&limit=1", api.get("$path/buckets?rollup=1&limit=1&from_ms=0"))
                record("GET .../{analysisId}/buckets (bad range)", api.get("$path/buckets?rollup=1&from_ms=5&to_ms=1"))
                record("GET .../{analysisId}/analytics", api.get("$path/analytics"))
            }
            record("GET .../{unknown}/result", api.get("$base/${"a".repeat(64)}/result"))
            record("GET .../{analysisId}/analytics?format=html", api.get("$base/$bareId/analytics?format=html"))
            record("GET .../{analysisId}/analytics?format=asciidoc", api.get("$base/$bareId/analytics?format=asciidoc"))
            record("GET .../{analysisId}/analytics?format=confluence", api.get("$base/$bareId/analytics?format=confluence"))
            record("GET .../{analysisId}/analytics?format=nope", api.get("$base/$bareId/analytics?format=nope"))
            record("GET .../{analysisId}/analytics?limit=0", api.get("$base/$bareId/analytics?limit=0"))
            record("GET .../{analysisId}/analytics?exclude=missing", api.get("$base/$bareId/analytics?exclude=missing/analysis"))
            record("GET .../{analysisId}/analytics?exclude=self", api.get("$base/$bareId/analytics?exclude=$runId/$bareId"))

            // resource series and pod view
            val series = "$base/$resourceId/resource-series"
            record("GET .../resource-series", api.get(series))
            record("GET .../resource-series?limit=0", api.get("$series?limit=0"))
            record("GET .../resource-series/values?series_id=cpu", api.get("$series/values?series_id=cpu"))
            record("GET .../resource-series/values?series_id=none", api.get("$series/values?series_id=missing"))
            record("GET .../resource-series/values (no id)", api.get("$series/values"))
            record("GET .../resource-series (no snapshot)", api.get("$base/$bareId/resource-series"))
            val view = "$base/$resourceId/pod-view"
            val metadata = api.get(view)
            record("GET .../pod-view", metadata)
            val service =
                metadata
                    .jsonObject()
                    .getValue("services")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("service")
                    .jsonPrimitive.content
            record("GET .../pod-view/values?service=<first>", api.get("$view/values?service=$service"))
            record("GET .../pod-view/values?service=missing", api.get("$view/values?service=missing"))
            record("GET .../pod-view/values (no service)", api.get("$view/values"))
            record("GET .../pod-view (no pod view)", api.get("$base/$bareId/pod-view"))

            // stored artifacts
            record("GET .../resource-snapshot", api.get("$base/$resourceId/resource-snapshot"))
            record("GET .../resource-snapshot (absent)", api.get("$base/$bareId/resource-snapshot"))
            // advice (no runner configured), integrations (none configured), sources
            record("GET .../advice", api.get("$base/$bareId/advice"))
            record("POST .../advice (no runner)", api.post("$base/$bareId/advice", "application/json", none))
            record("POST .../advice (not json)", api.post("$base/$bareId/advice", "text/plain", none))
            record("POST .../advice (unknown analysis)", api.post("$base/${"a".repeat(64)}/advice", "application/json", none))
            record("GET /api/advice-jobs/{jobId}", api.get("/api/advice-jobs/00000000-0000-0000-0000-000000000000"))
            record("DELETE /api/advice-jobs/{jobId}", api.delete("/api/advice-jobs/00000000-0000-0000-0000-000000000000"))
            record("GET /api/sources", api.get("/api/sources"))
            for (phase in listOf("pre", "post")) {
                record(
                    "POST /api/sources/postgresql/$phase (unknown profile)",
                    api.multipart("/api/sources/postgresql/$phase", listOf(Part("profile_id", "missing".encodeToByteArray()))),
                )
                record(
                    "POST /api/sources/postgresql/$phase (not multipart)",
                    api.post("/api/sources/postgresql/$phase", "application/json", none),
                )
            }
            record("GET /api/grafana", api.get("/api/grafana"))
            record("GET .../grafana-link", api.get("$base/$bareId/grafana-link?profile=missing&dashboard=demo&panel=1"))
            record(
                "POST .../grafana-render",
                api.post("$base/$bareId/grafana-render?profile=missing&dashboard=demo&panel=1", "application/json", none),
            )
            record("GET /api/jenkins", api.get("/api/jenkins"))
            record("GET /api/jenkins/{profileId}/attempts", api.get("/api/jenkins/missing/attempts"))
            record(
                "POST /api/jenkins/{profileId}/trigger",
                api.post("/api/jenkins/missing/trigger", "application/json", """{"parameters":{}}""".encodeToByteArray()),
            )
            record(
                "POST /api/jenkins/{profileId}/attempts/{attemptId}/{operation}",
                api.post("/api/jenkins/missing/attempts/a/advance", "application/json", "{}".encodeToByteArray()),
            )

            // baseline, conditions, comparison
            record("GET /api/baseline (empty)", api.get("/api/baseline"))
            record("POST /api/baseline (malformed)", api.post("/api/baseline", "application/json", "{".encodeToByteArray()))
            record(
                "POST /api/baseline (unknown analysis)",
                api.post("/api/baseline", "application/json", manual("release", runId, "b".repeat(64))),
            )
            record("POST /api/baseline (manual)", api.post("/api/baseline", "application/json", manual("release", runId, permId)))
            record("GET /api/baseline", api.get("/api/baseline"))
            record("GET .../comparison?series=release", api.get("$base/$passId/comparison?series=release"))
            record("GET .../comparison (windows)", api.get("$base/$passId/comparison?series=release&baseline_window=a&current_window=b"))
            record(
                "GET .../comparison (windows, thresholds)",
                api.get("$base/$passId/comparison?series=release&baseline_window=a&current_window=b&min_change_percent=0"),
            )
            record("GET .../comparison (unknown series)", api.get("$base/$passId/comparison?series=other"))
            record("GET .../baseline-conditions", api.get("$base/$passId/baseline-conditions?series=release"))
            record(
                "POST .../baseline-conditions (malformed)",
                api.post(
                    "$base/$passId/baseline-conditions?series=release",
                    "application/json",
                    """{"decision":"MAYBE"}""".encodeToByteArray(),
                ),
            )
            record(
                "POST .../baseline-conditions",
                api.post(
                    "$base/$passId/baseline-conditions?series=release",
                    "application/json",
                    """{"decision":"CONFIRMED"}""".encodeToByteArray(),
                ),
            )
            record("GET .../baseline-conditions (stored)", api.get("$base/$passId/baseline-conditions?series=release"))
            record("GET .../comparison (confirmed)", api.get("$base/$passId/comparison?series=release"))
            record("GET .../analytics (with baseline)", api.get("$base/$passId/analytics?series=release"))

            // releases
            record("GET /api/releases (empty)", api.get("/api/releases"))
            record(
                "POST /api/releases (malformed)",
                api.post("/api/releases", "application/json", """{"series":"s"}""".encodeToByteArray()),
            )
            val created = api.post("/api/releases", "application/json", releaseBody(runId, listOf(bareId), "v1"))
            record("POST /api/releases", created)
            val releaseId =
                created
                    .jsonObject()
                    .getValue("release_id")
                    .jsonPrimitive.content
            record(
                "POST /api/releases (analysis already registered)",
                api.post("/api/releases", "application/json", releaseBody(runId, listOf(bareId), "v2")),
            )
            record("GET /api/releases", api.get("/api/releases"))
            record("GET /api/releases?series=s1", api.get("/api/releases?series=s1&limit=1"))
            record("GET /api/releases/{releaseId}", api.get("/api/releases/$releaseId"))
            record("GET /api/releases/{releaseId} (invalid)", api.get("/api/releases/not-an-id"))
            val updated =
                api.put(
                    "/api/releases/$releaseId",
                    "application/json",
                    """{"label":"v1b","analyses":[{"analysis_id":"$bareId"}],"profile":null,"notes":"n"}""".encodeToByteArray(),
                )
            record("PUT /api/releases/{releaseId}", updated)
            // The timestamps are normalized in the snapshot; the shape of the one made by the handler is checked here.
            check(UPDATED_AT.containsMatchIn(updated.body())) { updated.body() }
            record(
                "PUT /api/releases/{releaseId} (malformed)",
                api.put("/api/releases/$releaseId", "application/json", "{}".encodeToByteArray()),
            )
            record("GET .../analytics (with release)", api.get("$base/$bareId/analytics"))
            record("GET .../comparison (with release)", api.get("$base/$passId/comparison?series=release"))
            record("DELETE /api/releases/{releaseId}", api.delete("/api/releases/$releaseId"))
            record("DELETE /api/releases/{releaseId} (again)", api.delete("/api/releases/$releaseId"))
            record("DELETE /api/baseline?series=release", api.delete("/api/baseline?series=release"))
            record("DELETE /api/baseline", api.delete("/api/baseline"))
            record("DELETE /api/baseline?arm=x", api.delete("/api/baseline?arm=x"))

            // The synthetic analysis has no result document, so it joins the history last: analytics refuses such a history.
            val artifacts = publishArtifacts(runId)
            for (
            route in
            listOf(
                "postgres-pre",
                "postgres-post",
                "postgres-context",
                "pg-profile",
                "capacity-plan",
                "capacity",
                "trend-plan",
                "trend",
                "source-context",
                "source-context/1",
                "source-context/16",
                "source-context/17",
                "source-context/0",
            )
            ) {
                record("GET .../$route (stored)", api.get("$base/$artifacts/$route"))
                record("GET .../$route (absent)", api.get("$base/$bareId/$route"))
            }

            // everything else under /api
            record("GET /api/nope", api.get("/api/nope"))
            record("POST /api/nope", api.post("/api/nope", "application/json", none))
            record("PUT /api/runs", api.put("/api/runs", "application/json", none))
            record("GET /api/runs/ (trailing slash)", api.get("/api/runs/"))
            record("GET /api/baseline/ (trailing slash)", api.get("/api/baseline/"))
            record("DELETE /api/runs", api.delete("/api/runs"))
            record("POST /api/bootstrap", api.post("/api/bootstrap", "application/json", none))
            record("GET /api/inputs", api.get("/api/inputs"))
            record("PUT /api/jobs/{jobId}", api.put("/api/jobs/00000000-0000-0000-0000-000000000000", "application/json", none))
            record("POST $base/$bareId/result", api.post("$base/$bareId/result", "application/json", none))
            record("GET /api/releases/{releaseId}/extra", api.get("/api/releases/$releaseId/extra"))
            record("GET /api/runs/{runId}/analyses/{analysisId}/nope", api.get("$base/$bareId/nope"))
            record("GET /api/runs/{runId}", api.get("/api/runs/$runId"))
        }

        fun integrations() {
            record("GET /api/bootstrap (integrations)", api.bootstrap())
            val upload = api.multipart("/api/inputs", listOf(Part("file", SPIKE_JTL.encodeToByteArray(), "spike-drop.jtl")))
            val runId =
                upload
                    .jsonObject()
                    .getValue("run_id")
                    .jsonPrimitive.content
            val job = api.multipart("/api/jobs", listOf(Part("run_id", runId.encodeToByteArray())))
            val analysis = "/api/runs/$runId/analyses/${awaitComplete(job.jobId()).analysisId}"
            val json = "application/json"
            val empty = "{}".encodeToByteArray()

            record("GET /api/sources (configured)", api.get("/api/sources"))
            record("GET /api/grafana (configured)", api.get("/api/grafana"))
            record("GET .../grafana-link (configured)", api.get("$analysis/grafana-link?profile=grafana&dashboard=dash&panel=2&theme=dark"))
            record("GET .../grafana-link (no dashboard)", api.get("$analysis/grafana-link?profile=grafana&panel=2"))
            val render = "$analysis/grafana-render?profile=grafana&dashboard=dash&panel=2"
            record("POST .../grafana-render (empty body)", api.post(render, json, none))
            record("POST .../grafana-render (configured)", api.post(render, json, empty))
            record("POST .../grafana-render (body)", api.post(render, json, """{"x":1}""".encodeToByteArray()))
            record(
                "POST .../grafana-render (bad dashboard)",
                api.post("$analysis/grafana-render?profile=grafana&dashboard=..%2Fx&panel=2", json, empty),
            )

            record("GET /api/jenkins (configured)", api.get("/api/jenkins"))
            record("GET /api/jenkins/perf/attempts (empty)", api.get("/api/jenkins/perf/attempts"))
            val trigger = "/api/jenkins/perf/trigger"
            record("POST trigger (extra field)", api.post(trigger, json, """{"parameters":{},"x":1}""".encodeToByteArray()))
            record("POST trigger (parameter not allowed)", api.post(trigger, json, """{"parameters":{"BOGUS":"1"}}""".encodeToByteArray()))
            record("POST trigger", api.post(trigger, json, """{"parameters":{"SCENARIO":"steady"}}""".encodeToByteArray()))
            record("GET /api/jenkins/perf/attempts", api.get("/api/jenkins/perf/attempts"))
            val attempt = "/api/jenkins/perf/attempts/attempt-1"
            record("POST advance (empty body)", api.post("$attempt/advance", json, none))
            record("POST advance (running)", api.post("$attempt/advance", json, empty))
            record("POST advance (awaiting artifact)", api.post("$attempt/advance", json, empty))
            record("POST advance (body)", api.post("$attempt/advance", json, """{"x":1}""".encodeToByteArray()))
            record("POST bogus operation", api.post("$attempt/bogus", json, empty))
            record("POST advance (unknown attempt)", api.post("/api/jenkins/perf/attempts/other/advance", json, empty))
            record("POST collect (no path)", api.post("$attempt/collect", json, empty))
            record(
                "POST collect (path not offered)",
                api.post("$attempt/collect", json, """{"artifact_path":"other"}""".encodeToByteArray()),
            )
            record("POST collect", api.post("$attempt/collect", json, """{"artifact_path":"run/results.jtl"}""".encodeToByteArray()))
            record(
                "POST collect (again)",
                api.post("$attempt/collect", json, """{"artifact_path":"run/results.jtl"}""".encodeToByteArray()),
            )
            record("POST reconcile", api.post("$attempt/reconcile", json, empty))
            record("GET /api/jenkins/perf/attempts (collected)", api.get("/api/jenkins/perf/attempts"))
            record("GET /api/runs (after collect)", api.get("/api/runs"))
        }

        private fun manual(
            series: String,
            runId: String,
            analysisId: String,
        ) = """{"mode":"manual","series":"$series","reference":{"run_id":"$runId","analysis_id":"$analysisId"}}""".encodeToByteArray()

        private fun releaseBody(
            runId: String,
            analyses: List<String>,
            label: String,
        ) = (
            """{"series":"s1","label":"$label","run_id":"$runId","analyses":[${analyses.joinToString(
                ",",
            ) { """{"analysis_id":"$it"}""" }}],""" +
                """"profile":null,"notes":null}"""
        ).encodeToByteArray()

        private fun publishArtifacts(runId: String): String {
            val identity = """{"run_id":"$runId","suffix":"artifacts"}""".encodeToByteArray()
            val id = sha256Hex(identity)
            store.writeAnalysisAtomically(runId, id) { staging ->
                Files.write(staging.resolve("identity.json"), identity)
                listOf(
                    "postgres-pre.json",
                    "postgres-post.json",
                    "postgres-context.json",
                    "pg-profile.html",
                    "capacity-plan.json",
                    "capacity.json",
                    "trend-plan.json",
                    "trend.json",
                    "opensearch-errors.json",
                    "opensearch-errors-1.json",
                    "opensearch-errors-16.json",
                ).forEach { name -> Files.write(staging.resolve(name), """{"artifact":"$name"}""".encodeToByteArray()) }
            }
            return id
        }

        private fun resourceSnapshot(
            store: RunBundleStore,
            runId: String,
        ): ByteArray {
            val input = store.requireInput(runId)
            return (
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}",""" +
                    """"start_epoch_ms":1767225600000,"step_ms":1000,""" +
                    """"point_count":3,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system",""" +
                    """"aggregation":"interval_mean","values":[0.1,0.2,0.3]}],""" +
                    """"windows":[]}"""
            ).encodeToByteArray()
        }

        private fun semanticHash(resources: ByteArray): String =
            (validateResourceSnapshot(resources.inputStream()) as ResourceValidation.Valid).semanticSha256

        private fun sha(runId: String): String = store.requireInput(runId).sha256

        private fun HttpResponse<String>.jobId(): String = jsonObject().getValue("job_id").jsonPrimitive.content

        private class Finished(
            val jobId: String,
            val analysisId: String,
        )

        private fun awaitComplete(jobId: String): Finished {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val status = api.get("/api/jobs/$jobId").jsonObject()
                when (status.getValue("state").jsonPrimitive.content) {
                    "COMPLETE" -> return Finished(jobId, status.getValue("analysis_id").jsonPrimitive.content)
                    "FAILED", "CANCELLED" -> fail("job ended as ${status.getValue("state")}: $status")
                }
                LockSupport.parkNanos(1_000_000)
            }
            return fail("job did not complete: $jobId")
        }

        /** One line per response: label, status, the headers that are part of the contract, length and hash of the body. */
        private fun record(
            label: String,
            response: HttpResponse<String>,
            shapeOnly: Boolean = false,
        ) {
            val headers =
                listOf(
                    "content-type",
                    "content-disposition",
                    "cache-control",
                    "content-security-policy",
                    "x-content-type-options",
                    "referrer-policy",
                    "set-cookie",
                ).joinToString(";") { name ->
                    "$name=" + response.headers().allValues(name).joinToString(",") { normalize(it) }
                }
            val body =
                if (shapeOnly) {
                    // A job just submitted is QUEUED or RUNNING depending on the race: only the status and the keys are stable.
                    "keys=" + (
                        runCatching {
                            Json
                                .parseToJsonElement(response.body())
                                .jsonObject.keys
                                .sorted()
                        }.getOrNull() ?: "-"
                    )
                } else {
                    val normalized = normalize(response.body())
                    "len=${normalized.length} sha256=${sha256Hex(normalized.encodeToByteArray())}"
                }
            lines += "$label | ${response.statusCode()} | $headers | $body"
        }

        private fun normalize(text: String): String =
            text
                .replace(PORT, "localhost:<port>")
                .replace(UUID, "<uuid>")
                .replace(TIMESTAMP, "<ts>")
                .replace(RELEASE_ID_TEXT, "<release_id>")
                .replace(CSRF, "\"csrf_token\":\"<csrf>\"")
                .replace(SESSION, "ltv_session=<session>")

        private companion object {
            val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            val TIMESTAMP = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?Z")
            val RELEASE_ID_TEXT = Regex("[0-9]{15}-[0-9a-f]{8}")
            val CSRF = Regex("\"csrf_token\":\"[0-9a-f]{64}\"")
            val PORT = Regex("(?:localhost|127[.]0[.]0[.]1):[0-9]+")
            val SESSION = Regex("ltv_session=[0-9a-f]{64}")
            val UPDATED_AT = Regex("\"updated_at\":\"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:[.][0-9]{3})?Z\"")

            val PERMISSIVE_POLICY =
                """{"schema_version":"policy.v1","policy_id":"permissive","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
                    .encodeToByteArray()
        }
    }

    private companion object {
        // The same four-line file as the JMeter CSV fixture of LocalApiTest: two transactions, one run.
        const val SPIKE_JTL =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
                "1767225601000,20,steady,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n" +
                "1767225600000,900,spike,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n" +
                "1767225600010,850,spike,200,OK,fixture 1-1,text,true,,0,0,1,1,null,0,0,0\n"
    }

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
        private var cookie: String? = null
        private var csrf: String? = null

        fun bootstrap(): HttpResponse<String> {
            val response = get("/api/bootstrap")
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
            return response
        }

        fun get(path: String): HttpResponse<String> = send(request(path).GET())

        fun delete(path: String): HttpResponse<String> = send(authenticated(request(path)).DELETE())

        fun post(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(authenticated(request(path)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun put(
            path: String,
            contentType: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(authenticated(request(path)).header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun postUnauthenticated(
            path: String,
            body: ByteArray,
        ): HttpResponse<String> =
            send(request(path).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)))

        fun multipart(
            path: String,
            parts: List<Part>,
        ): HttpResponse<String> {
            val boundary = "ltv-test-boundary"
            val body = ByteArrayOutputStream()

            fun text(value: String) = body.write(value.toByteArray(UTF_8))
            parts.forEach { part ->
                text("--$boundary\r\nContent-Disposition: form-data; name=\"${part.name}\"")
                part.filename?.let { text("; filename=\"$it\"") }
                text("\r\n")
                part.contentType?.let { text("Content-Type: $it\r\n") }
                text("\r\n")
                body.write(part.bytes)
                text("\r\n")
            }
            text("--$boundary--\r\n")
            return post(path, "multipart/form-data; boundary=$boundary", body.toByteArray())
        }

        private fun request(path: String): HttpRequest.Builder =
            HttpRequest
                .newBuilder(URI.create("$origin$path"))
                .timeout(Duration.ofSeconds(20))
                .apply { cookie?.let { header("Cookie", it) } }

        private fun authenticated(request: HttpRequest.Builder): HttpRequest.Builder =
            request.header("Origin", origin).header("Cookie", cookie ?: "").header("X-LTV-CSRF", csrf ?: "")

        private fun send(request: HttpRequest.Builder): HttpResponse<String> =
            client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
    }
}

private fun HttpResponse<String>.jsonObject(): JsonObject = Json.parseToJsonElement(body()).jsonObject

package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceSeriesV1
import io.ltverdict.core.ResourceSnapshotV1
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.ref.WeakReference
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ResourceSeriesApiTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `catalog and values equal the oracle vectors`() =
        withServer { store, api, runId ->
            val path = Path.of("fixtures/resource-series/cases.json")
            assertTrue(Files.exists(path), "Oracle vectors are missing: $path")
            val cases =
                Json
                    .parseToJsonElement(Files.readString(path))
                    .jsonObject
                    .getValue("cases")
                    .jsonArray
            val analyses = mutableMapOf<String, String>()
            for (caseElement in cases) {
                val case = caseElement.jsonObject
                val snapshotName = case.getValue("snapshot").jsonPrimitive.content
                val analysisId =
                    analyses.getOrPut(snapshotName) {
                        val snapshotPath = path.parent.resolve(snapshotName)
                        assertTrue(Files.exists(snapshotPath), "Oracle snapshot is missing: $snapshotPath")
                        publish(store, runId, Files.readString(snapshotPath), snapshotName)
                    }
                val query = case.getValue("query").jsonObject
                val kind = case.getValue("endpoint").jsonPrimitive.content
                val route = base(runId, analysisId) + if (kind == "values") "/values" else ""
                val response = api.get(route + queryString(query))
                assertEquals(200, response.statusCode(), "Oracle case: $case")
                assertJsonEqual(case.getValue("expected"), Json.parseToJsonElement(response.body()))
            }
        }

    @Test
    fun `values reject repeated single parameters duplicates more than 32 ids and unknown parameters`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, snapshot(listOf("cpu")), "basic")
            val route = "${base(runId, id)}/values"
            listOf(
                "",
                "?series_id=",
                "?series_id=${"a".repeat(129)}",
                "?series_id=cpu&series_id=cpu",
                "?series_id=cpu&step_ms=1000&step_ms=1000",
                "?series_id=cpu&foo=1",
                "?series_id=cpu&limit=0",
                "?series_id=cpu&limit=100001",
                "?" + (0..32).joinToString("&") { "series_id=id$it" },
            ).forEach { assertEquals(400, api.get(route + it).statusCode(), it) }
            assertEquals(400, api.get(base(runId, id) + "?after=").statusCode())
        }

    @Test
    fun `values answer 404 for an unknown series and 404 for an analysis without a snapshot`() =
        withServer { store, api, runId ->
            val present = publish(store, runId, snapshot(listOf("cpu")), "present")
            val absent = publish(store, runId, null, "absent")
            assertEquals(404, api.get("${base(runId, present)}/values?series_id=missing").statusCode())
            val missingArtifact = api.get("${base(runId, absent)}/values?series_id=cpu")
            assertEquals(404, missingArtifact.statusCode())
            assertEquals(
                "Resource snapshot was not found",
                missingArtifact
                    .json()
                    .jsonObject
                    .getValue("error")
                    .jsonObject
                    .getValue("message")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `invalid stored snapshot answers 500 without poisoning the cache`() =
        withServer { store, api, runId ->
            val corrupt = publish(store, runId, "{}", "corrupt")
            val route = base(runId, corrupt)
            listOf(route, "$route/values?series_id=cpu").forEach { path ->
                val response = api.get(path)
                assertEquals(500, response.statusCode(), path)
                assertEquals(
                    "CORRUPT_RESOURCE_SNAPSHOT",
                    response
                        .json()
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
            }
            val valid = publish(store, runId, snapshot(listOf("cpu")), "valid-after-corrupt")
            assertEquals(200, api.get(base(runId, valid)).statusCode())
        }

    @Test
    fun `catalog validates parameters and reports declared windows`() =
        withServer { store, api, runId ->
            val windows = """[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":2000}]"""
            val id = publish(store, runId, snapshot(listOf("a", "b", "c"), windows), "catalog")
            val route = base(runId, id)
            listOf("?limit=0", "?limit=257", "?after=%01", "?limit=2&limit=2", "?unknown=1")
                .forEach { assertEquals(400, api.get(route + it).statusCode(), it) }
            assertEquals(200, api.get("$route?limit=256").statusCode())

            val first = api.get("$route?limit=2")
            assertEquals(200, first.statusCode())
            val firstBody = first.json().jsonObject
            assertEquals(
                listOf("a", "b"),
                firstBody.getValue("series").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                },
            )
            assertEquals("b", firstBody.getValue("next_after").jsonPrimitive.content)
            assertJsonEqual(Json.parseToJsonElement(windows), firstBody.getValue("windows"))

            val second = api.get("$route?after=b&limit=2")
            assertEquals(200, second.statusCode())
            val secondBody = second.json().jsonObject
            assertEquals(
                listOf("c"),
                secondBody.getValue("series").jsonArray.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                },
            )
            assertEquals(JsonNull, secondBody.getValue("next_after"))

            val missingCursor = api.get("$route?after=bb")
            assertEquals(200, missingCursor.statusCode())
            assertEquals(
                listOf("c"),
                missingCursor
                    .json()
                    .jsonObject
                    .getValue("series")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    },
            )
        }

    @Test
    fun `values answer 400 for off-grid boundaries and 413 for series times limit above the cap`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, snapshot((0..31).map { "s$it" }), "many")
            val route = "${base(runId, id)}/values?" + (0..31).joinToString("&") { "series_id=s$it" }
            assertEquals(400, api.get("$route&from_ms=1").statusCode())
            assertEquals(400, api.get("$route&step_ms=1500").statusCode())
            assertEquals(400, api.get("$route&to_ms=1500").statusCode())
            assertEquals(413, api.get("$route&limit=3201").statusCode())
            assertEquals(200, api.get(route).statusCode())
        }

    @Test
    fun `qualified identifiers with slash percent and non ascii round trip`() =
        withServer { store, api, runId ->
            val ids = listOf("prom/a%b", "\u043c\u0435\u0442\u0440\u0438\u043a\u0430 \u0431", "a+b", "a&b=c", "x y")
            val id = publish(store, runId, snapshot(ids), "qualified")
            val query = ids.joinToString("&") { "series_id=${encode(it)}" }
            val returned =
                api
                    .get("${base(runId, id)}/values?$query")
                    .json()
                    .jsonObject
                    .getValue("series")
                    .jsonArray
            assertEquals(
                ids,
                returned.map {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content
                },
            )
        }

    @Test
    fun `a request line near the Netty limit is accepted and a longer one fails cleanly`() =
        withServer { store, api, runId ->
            val ids = (0..31).map { it.toString().padStart(128, 'a') }
            val id = publish(store, runId, snapshot(ids), "long-ids")
            val route = "${base(runId, id)}/values?"
            assertEquals(200, api.get(route + ids.take(20).joinToString("&") { "series_id=$it" }).statusCode())
            // observed: 32 ids of 128 bytes give a request line of about 4.5 KB, above the Netty default of 4096; the server answers 400.
            val tooLong = api.get(route + ids.joinToString("&") { "series_id=$it" })
            assertEquals(400, tooLong.statusCode())
        }

    @Test
    fun `two consecutive values requests and one after cache eviction return correct data`() =
        withServer { store, api, runId ->
            val first = publish(store, runId, snapshot(listOf("first")), "first")
            val second = publish(store, runId, snapshot(listOf("second")), "second")
            val firstRoute = "${base(runId, first)}/values?series_id=first"
            repeat(2) {
                assertEquals(
                    "first",
                    api
                        .get(
                            firstRoute,
                        ).json()
                        .jsonObject
                        .getValue("series")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive.content,
                )
            }
            assertEquals(200, api.get("${base(runId, second)}/values?series_id=second").statusCode())
            assertEquals(
                "first",
                api
                    .get(firstRoute)
                    .json()
                    .jsonObject
                    .getValue("series")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `concurrent cache uses decode once and serialize blocks`() =
        runBlocking {
            val cache = SnapshotCache()
            val decodes = AtomicInteger()
            val events = mutableListOf<String>()
            val value = decoded()
            val started = CountDownLatch(1)
            val first =
                launch(Dispatchers.Default) {
                    cache.use("same", {
                        decodes.incrementAndGet()
                        value
                    }) {
                        synchronized(events) { events += "first start" }
                        started.countDown()
                        Thread.sleep(20)
                        synchronized(events) { events += "first end" }
                    }
                }
            val second =
                launch(Dispatchers.Default) {
                    started.await()
                    cache.use("same", {
                        decodes.incrementAndGet()
                        value
                    }) { synchronized(events) { events += "second start" } }
                }
            first.join()
            second.join()
            assertEquals(1, decodes.get())
            assertEquals(listOf("first start", "first end", "second start"), events)
        }

    @Test
    fun `cache key change releases the evicted snapshot before decode`() =
        runBlocking {
            val cache = SnapshotCache()
            var first: DecodedSnapshot? = decoded()
            val weak = WeakReference(checkNotNull(first))
            cache.use("first", { checkNotNull(first) }) { assertTrue(it === first) }
            first = null
            cache.use("second", {
                for (attempt in 0 until 50) {
                    System.gc()
                    Thread.sleep(10)
                    if (weak.get() == null) break
                }
                assertNull(weak.get())
                decoded()
            }) { assertEquals("hash", it.semanticSha256) }
        }

    private fun decoded(): DecodedSnapshot =
        DecodedSnapshot(
            ResourceSnapshotV1(
                "resource-snapshot.v1",
                "0".repeat(64),
                0,
                1_000,
                1,
                listOf(
                    ResourceSeriesV1(
                        "cpu",
                        "cpu",
                        "ratio",
                        "vm",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_MEAN,
                        emptyMap(),
                        listOf(BigDecimal.ONE),
                    ),
                ),
                emptyList(),
                emptyList(),
                null,
            ),
            "hash",
        )

    private fun snapshot(
        ids: List<String>,
        windows: String = "[]",
    ): String {
        val series =
            ids.sorted().joinToString(",") { id ->
                """{"id":${JsonPrimitive(
                    id,
                )},"metric":"cpu","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[1,null]}"""
            }
        return """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${"0".repeat(
            64,
        )}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[$series],"windows":$windows}"""
    }

    private fun publish(
        store: RunBundleStore,
        runId: String,
        snapshot: String?,
        suffix: String,
    ): String {
        val identity = """{"run_id":"$runId","suffix":"$suffix"}""".encodeToByteArray()
        val id = sha256Hex(identity)
        store.writeAnalysisAtomically(runId, id) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            if (snapshot != null) Files.writeString(staging.resolve("resource-snapshot.json"), snapshot)
        }
        return id
    }

    private fun withServer(block: (RunBundleStore, ApiClient, String) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze).use { jobs ->
                startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                    val runId =
                        Files.newInputStream(Path.of("fixtures/slice1/normalization/spike-drop.jtl")).use {
                            store.acceptInput(it, "spike-drop.jtl").runId
                        }
                    val api = ApiClient(server.origin)
                    api.bootstrap()
                    block(store, api, runId)
                }
            }
        }
    }

    private fun base(
        runId: String,
        analysisId: String,
    ): String = "/api/runs/$runId/analyses/$analysisId/resource-series"

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

    private fun queryString(query: JsonObject): String =
        query.entries
            .flatMap { (key, value) ->
                if (value is JsonArray) {
                    value.map {
                        "$key=${encode(
                            it.jsonPrimitive.content,
                        )}"
                    }
                } else {
                    listOf("$key=${encode(value.jsonPrimitive.content)}")
                }
            }.joinToString("&")
            .let { if (it.isEmpty()) "" else "?$it" }

    private fun assertJsonEqual(
        expected: JsonElement,
        actual: JsonElement,
    ) {
        when (expected) {
            is JsonObject -> {
                assertTrue(actual is JsonObject)
                actual as JsonObject
                assertEquals(expected.keys, actual.keys)
                expected.forEach { (key, value) -> assertJsonEqual(value, actual.getValue(key)) }
            }
            is JsonArray -> {
                assertTrue(actual is JsonArray)
                actual as JsonArray
                assertEquals(expected.size, actual.size)
                expected.indices.forEach { assertJsonEqual(expected[it], actual[it]) }
            }
            JsonNull -> assertEquals(JsonNull, actual)
            is JsonPrimitive -> {
                assertTrue(actual is JsonPrimitive)
                actual as JsonPrimitive
                if (expected.isString) {
                    assertEquals(
                        expected.content,
                        actual.content,
                    )
                } else {
                    assertTrue(!actual.isString)
                    assertEquals(expected.content.toDouble(), actual.content.toDouble())
                }
            }
        }
    }

    private class ApiClient(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

        fun bootstrap(): HttpResponse<String> = get("/api/bootstrap")

        fun get(path: String): HttpResponse<String> =
            client.send(
                HttpRequest
                    .newBuilder(URI.create(origin + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(UTF_8),
            )
    }

    private fun HttpResponse<String>.json(): JsonElement = Json.parseToJsonElement(body())
}

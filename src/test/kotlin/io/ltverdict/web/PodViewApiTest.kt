package io.ltverdict.web

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.START
import io.ltverdict.core.STEP
import io.ltverdict.core.podViewReadFixture
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validPodView
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PodViewApiTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `metadata answers the grid coverage and services without any values or snapshot series`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "meta")

            val response = api.get(route(runId, id))

            assertEquals(200, response.statusCode())
            val body = response.json().jsonObject
            assertEquals(
                setOf("schema_version", "arm", "grid", "coverage", "services", "pod_view_sha256", "resource_snapshot_sha256"),
                body.keys,
            )
            assertEquals(sha256Hex(canonical()), body.getValue("pod_view_sha256").jsonPrimitive.content)
            assertEquals(
                listOf("cart", "orders"),
                body.getValue("services").jsonArray.map {
                    it.jsonObject
                        .getValue("service")
                        .jsonPrimitive.content
                },
            )
            assertFalse(response.body().contains("\"values\""))
            assertFalse(response.body().contains("series"))
        }

    @Test
    fun `a missing analysis and an analysis without a pod view are different 404 answers`() =
        withServer { store, api, runId ->
            val bare = publish(store, runId, null, "bare")

            listOf(route(runId, bare), "${route(runId, bare)}/values?service=cart").forEach {
                val response = api.get(it)
                assertEquals(404, response.statusCode(), it)
                assertEquals("POD_VIEW_NOT_FOUND", response.errorCode(), it)
            }
            listOf("", "/values?service=cart").forEach {
                val response = api.get(route(runId, "0".repeat(64)) + it)
                assertEquals(404, response.statusCode(), it)
                assertEquals("NOT_FOUND", response.errorCode(), it)
            }
        }

    @Test
    fun `extra repeated and malformed parameters are refused`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "strict")
            assertEquals(400, api.get("${route(runId, id)}?x=1").statusCode())

            val values = "${route(runId, id)}/values"
            listOf(
                "",
                "?metric=container_cpu_ratio",
                "?service=",
                "?service=cart&service=orders",
                "?service=cart&foo=1",
                "?service=cart&limit=0",
                "?service=cart&limit=257",
                "?service=cart&limit=abc",
                "?service=cart&limit=1&limit=2",
                "?service=cart&from_ms=abc",
                "?service=cart&to_ms=1.5",
                "?service=${"s".repeat(129)}",
            ).forEach {
                val response = api.get(values + it)
                assertEquals(400, response.statusCode(), it)
                assertEquals("MALFORMED_REQUEST", response.errorCode(), it)
            }
        }

    @Test
    fun `an unknown service is 404 and a foreign cursor is INVALID_CURSOR`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "service")
            val values = "${route(runId, id)}/values"

            val unknown = api.get("$values?service=billing")
            assertEquals(404, unknown.statusCode())
            assertEquals("POD_VIEW_SERVICE_NOT_FOUND", unknown.errorCode())

            val foreign = api.get("$values?service=cart&after=r6")
            assertEquals(400, foreign.statusCode())
            assertEquals("INVALID_CURSOR", foreign.errorCode())
            assertEquals(400, api.get("$values?service=cart&metric=container_memory_ratio&after=r1").statusCode())
        }

    @Test
    fun `pages are stable and reach the end with a null cursor`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "pages")
            val values = "${route(runId, id)}/values?service=cart"
            val seen = mutableListOf<String>()
            var after: String? = null
            var pages = 0
            do {
                val response = api.get(values + "&limit=2" + (after?.let { "&after=$it" } ?: ""))
                assertEquals(200, response.statusCode())
                val body = response.json().jsonObject
                assertEquals("ieee754-double", body.getValue("numeric_encoding").jsonPrimitive.content)
                seen +=
                    body.getValue("rows").jsonArray.map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }
                val next = body.getValue("next_after")
                after = if (next == JsonNull) null else next.jsonPrimitive.content
                pages += 1
            } while (after != null)

            assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), seen)
            assertEquals(3, pages)
            assertEquals(
                seen,
                api
                    .get(values)
                    .json()
                    .jsonObject
                    .getValue("rows")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    },
            )
        }

    @Test
    fun `a window off a column boundary is 400 and a window on boundaries cuts the values`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "window")
            val values = "${route(runId, id)}/values?service=orders"

            assertEquals(400, api.get("$values&from_ms=${START + 1}").statusCode())
            assertEquals(400, api.get("$values&to_ms=${START + STEP + 1}").statusCode())
            assertEquals(400, api.get("$values&from_ms=${START + 4 * STEP}").statusCode())

            val cut = api.get("$values&from_ms=${START + STEP}&to_ms=${START + 3 * STEP}")
            assertEquals(200, cut.statusCode())
            val row =
                cut
                    .json()
                    .jsonObject
                    .getValue("rows")
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals(2, row.getValue("values").jsonArray.size)
        }

    @Test
    fun `same-size substituted bytes answer 500 CORRUPT_POD_VIEW on both routes`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "swap")
            assertEquals(200, api.get(route(runId, id)).statusCode())

            val file = store.readAnalysis(runId, id)!!.path.resolve("pod-view.json")
            val original = Files.readAllBytes(file)
            val swapped = original.decodeToString().replaceFirst("0.25", "0.26").encodeToByteArray()
            assertEquals(original.size, swapped.size)
            Files.write(file, swapped)

            listOf(route(runId, id), "${route(runId, id)}/values?service=cart").forEach {
                val response = api.get(it)
                assertEquals(500, response.statusCode(), it)
                assertEquals("CORRUPT_POD_VIEW", response.errorCode(), it)
            }
        }

    @Test
    fun `a bound file that is not a valid pod view answers 500 CORRUPT_POD_VIEW`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, "{}".encodeToByteArray(), "invalid")

            listOf(route(runId, id), "${route(runId, id)}/values?service=cart").forEach {
                val response = api.get(it)
                assertEquals(500, response.statusCode(), it)
                assertEquals("CORRUPT_POD_VIEW", response.errorCode(), it)
            }
        }

    @Test
    fun `a pod view binding without its file answers 500 CORRUPT_POD_VIEW`() =
        withServer { store, api, runId ->
            val id = publish(store, runId, canonical(), "missing-file", writeFile = false)

            val response = api.get(route(runId, id))
            assertEquals(500, response.statusCode())
            assertEquals("CORRUPT_POD_VIEW", response.errorCode())
        }

    @Test
    fun `a deleted or truncated pod view file answers 500 CORRUPT_POD_VIEW`() =
        withServer { store, api, runId ->
            val gone = publish(store, runId, canonical(), "gone")
            val short = publish(store, runId, canonical(), "short")
            Files.delete(store.readAnalysis(runId, gone)!!.path.resolve("pod-view.json"))
            Files.write(store.readAnalysis(runId, short)!!.path.resolve("pod-view.json"), "{}".encodeToByteArray())

            listOf(gone, short).forEach { id ->
                listOf(route(runId, id), "${route(runId, id)}/values?service=cart").forEach {
                    val response = api.get(it)
                    assertEquals(500, response.statusCode(), it)
                    assertEquals("CORRUPT_POD_VIEW", response.errorCode(), it)
                }
            }
        }

    private fun canonical(): ByteArray = validPodView(podViewReadFixture()).canonicalBytes()

    private fun publish(
        store: RunBundleStore,
        runId: String,
        podView: ByteArray?,
        suffix: String,
        writeFile: Boolean = true,
    ): String {
        val binding = podView?.let { """"pod_view_sha256":"${sha256Hex(it)}","pod_view_version":"pod-view.v1",""" } ?: ""
        val identity = """{$binding"run_id":"$runId","suffix":"$suffix"}""".encodeToByteArray()
        val id = sha256Hex(identity)
        store.writeAnalysisAtomically(runId, id) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            if (podView != null && writeFile) Files.write(staging.resolve("pod-view.json"), podView)
        }
        return id
    }

    private fun route(
        runId: String,
        analysisId: String,
    ): String = "/api/runs/$runId/analyses/$analysisId/pod-view"

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
                    api.get("/api/bootstrap")
                    block(store, api, runId)
                }
            }
        }
    }

    private fun HttpResponse<String>.json(): JsonElement = Json.parseToJsonElement(body())

    private fun HttpResponse<String>.errorCode(): String {
        val error = json().jsonObject.getValue("error").jsonObject
        assertTrue(error.containsKey("message"))
        return error.getValue("code").jsonPrimitive.content
    }

    private class ApiClient(
        private val origin: String,
    ) {
        private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

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
}

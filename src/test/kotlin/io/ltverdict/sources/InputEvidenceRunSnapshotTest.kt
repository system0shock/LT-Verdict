package io.ltverdict.sources

import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 slice 2c, end to end: whole analyses with an online source and with an imported OpenSearch context. The hash of every file
 * of the analysis directory (analysis-result.json, identity.json, source-acquisition.json, opensearch-errors.json, ...) and the
 * analysis_id equal the values captured from `origin/main` BEFORE the producers were typed. Regenerate only on purpose with
 * `LTV_UPDATE_INPUT_EVIDENCE=1`.
 */
class InputEvidenceRunSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val snapshotFile = Path.of("fixtures/typed-evidence/input-evidence-runs.sha256")
    private val start = ScriptedSource.START

    private val loadCsv =
        "timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes," +
            "grpThreads,allThreads,Latency,IdleTime,Connect\n" +
            (0 until 3).joinToString("") { "${start + it * 1_000L},1000,request,200,OK,thread,true,1,1,1,1,1,0,0\n" }

    private fun window(
        schema: String,
        profiles: List<String>,
        window: String,
    ) = readWindowedSourceRequest(
        """{"schema_version":"$schema","profile_ids":[${profiles.joinToString(
            ",",
        ) { "\"$it\"" }}],"window":$window}""".byteInputStream(),
    )

    @Test
    fun `whole runs with online sources and imported contexts equal the pre-typing snapshot`() {
        val lines = mutableListOf<String>()
        val seenTypes = sortedSetOf<String>()

        fun run(
            name: String,
            build: (io.ltverdict.storage.AcceptedInput) -> Pair<AnalysisRequest, PromqlSource?>,
        ) {
            DataDirectory.open(tempDir.resolve("data-$name")).use { dir ->
                val store = RunBundleStore(dir)
                val input = store.acceptInput(ByteArrayInputStream(loadCsv.encodeToByteArray()), "$name.jtl")
                val (request, source) = build(input)
                runCatching { analyzeWithSources(AnalysisService(store, EngineConfig()), request, source) }
                    .onFailure { lines += "$name THROWS ${it::class.simpleName}: ${it.message}" }
                    .onSuccess {
                        // manifest.json and run.json carry the time of the run and are not part of the evidence
                        val files =
                            Files.list(it.analysisDirectory).use { stream ->
                                stream
                                    .filter { path ->
                                        Files.isRegularFile(path) &&
                                            path.fileName.toString().let { n ->
                                                n.startsWith("source-") ||
                                                    n.startsWith("opensearch-") ||
                                                    n == "analysis-result.json" ||
                                                    n == "identity.json" ||
                                                    n == "resource-snapshot.json"
                                            }
                                    }.sorted()
                                    .toList()
                            }
                        // An online run measures the wait for a request slot, which lands in the result, in
                        // source-acquisition.json and so in the identity: those files are compared with the wait zeroed, and
                        // identity.json and the id are left out. An imported context makes no request, its files are exact.
                        val online = source != null

                        fun digest(path: Path): String =
                            if (online && path.fileName.toString() in setOf("analysis-result.json", "source-acquisition.json")) {
                                sha256Hex(withoutThrottle(Files.readAllBytes(path)).encodeToByteArray())
                            } else {
                                sha256Hex(Files.readAllBytes(path))
                            }
                        lines += "$name ${if (online) "" else "id=${it.analysisId} "}" +
                            files
                                .filter { path -> !online || path.fileName.toString() != "identity.json" }
                                .joinToString(" ") { path -> "${path.fileName}=${digest(path)}" }
                        Json
                            .parseToJsonElement(it.canonicalResult.decodeToString())
                            .jsonObject
                            .getValue("evidence")
                            .jsonArray
                            .forEach { item ->
                                seenTypes +=
                                    item.jsonObject
                                        .getValue("type")
                                        .jsonPrimitive.content
                            }
                        (Json.parseToJsonElement(it.canonicalResult.decodeToString()).jsonObject["context_evidence"]?.jsonArray)
                            ?.forEach { item ->
                                seenTypes +=
                                    item.jsonObject
                                        .getValue("type")
                                        .jsonPrimitive.content
                            }
                    }
            }
        }

        ScriptedSource().use { server ->
            server.promBody = ScriptedSource.matrix(listOf("0.9", "0.95", "0.85"))
            server.osBody = ScriptedSource.openSearchBody(listOf(1, 0, 2))
            val local = server.prometheus()
            val errors = server.openSearch()
            val explicit = """{"origin":"explicit","start_epoch_ms":$start,"end_epoch_ms":${start + 3_000},"step_ms":1000}"""
            val auto = """{"origin":"auto","step_ms":1000,"margin_ms":0,"max_idle_gap_ms":60000}"""

            run("metric-explicit-v1") { input ->
                AnalysisRequest(
                    input,
                    null,
                    sourceRequest =
                        readWindowedSourceRequest(
                            """{"schema_version":"source-request.v1","profile_id":"local","start_epoch_ms":$start,
                                "end_epoch_ms":${start + 3_000},"step_ms":1000}""".byteInputStream(),
                        ),
                ) to server.source(local)
            }
            run("metric-explicit-v3") { input ->
                AnalysisRequest(input, null, sourceRequest = window("source-request.v3", listOf("local"), explicit)) to
                    server.source(local)
            }
            run("metric-auto-v3") { input ->
                AnalysisRequest(input, null, sourceRequest = window("source-request.v3", listOf("local"), auto)) to
                    server.source(local)
            }
            run("metric-auto-v4-step") { input ->
                AnalysisRequest(
                    input,
                    null,
                    sourceRequest =
                        window(
                            "source-request.v4",
                            listOf("local"),
                            auto.replace("\"step_ms\"", "\"step_mode\":\"auto\",\"step_ms\""),
                        ),
                ) to
                    server.source(local.copy(scrapeIntervalMillis = 1_000))
            }
            run("metric-errors-auto-v3") { input ->
                AnalysisRequest(input, null, sourceRequest = window("source-request.v3", listOf("local", "errors"), auto)) to
                    server.source(local, errors)
            }
            run("errors-only-explicit-v3") { input ->
                AnalysisRequest(input, null, sourceRequest = window("source-request.v3", listOf("errors"), explicit)) to
                    server.source(errors)
            }
            run("metric-armed-spans") { input ->
                val armed = server.prometheus(arm = "blue", spans = mapOf("cpu-high" to 2_000L))
                AnalysisRequest(input, null, sourceRequest = window("source-request.v3", listOf("local"), explicit)) to server.source(armed)
            }

            // an imported context: the artifact is read with the hash of the run
            run("import-single") { input ->
                val artifact = importable(input.sha256, "errors")
                AnalysisRequest(input, null, sourceAcquisition = readOpenSearchContext(artifact.inputStream(), input.sha256)) to null
            }
            run("import-two") { input ->
                val acquisition =
                    readOpenSearchContexts(listOf(importable(input.sha256, "errors-b"), importable(input.sha256, "errors-a")), input.sha256)
                AnalysisRequest(input, null, sourceAcquisition = acquisition) to null
            }
        }

        val expected = setOf("source_summary", "resource_binding", "opensearch_errors")
        assertTrue(seenTypes.containsAll(expected), "the runs do not reach: ${expected - seenTypes}")
        assertTrue(lines.none { "THROWS" in it }, lines.joinToString("\n"))

        val actual = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("LTV_UPDATE_INPUT_EVIDENCE") == "1") Files.writeString(snapshotFile, actual)
        assertEquals(Files.readString(snapshotFile).replace("\r\n", "\n"), actual)
    }

    private fun importable(
        loadHash: String,
        profile: String,
    ): ByteArray {
        val mapping = OpenSearchMapping(listOf("logs-*"), "@timestamp", "service.name", "error.type", "error.message", samplesPerGroup = 0)
        val body = ScriptedSource.openSearchBody(listOf(1, 0, 2))
        return canonicalJson(
            decodeOpenSearchResponse(
                body.encodeToByteArray(),
                mapping,
                SourceRequest(profile, start, start + 3_000, 1_000),
                loadHash,
                URI.create("https://search.example/"),
            ),
        )
    }
}

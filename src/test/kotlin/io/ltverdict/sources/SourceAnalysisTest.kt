package io.ltverdict.sources

import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.RUN_PERIOD_RECOGNITION_METHOD
import io.ltverdict.core.RUN_PERIOD_SCHEMA_VERSION
import io.ltverdict.core.RUN_PERIOD_STATUS_RECOGNIZED
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.RunPeriodV1
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.recognizeRunPeriod
import io.ltverdict.core.runPeriodJson
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class SourceAnalysisTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `auto window acquires the derived period and publishes provenance`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 30), "auto.jtl")

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())

                // Период 1767225600000..1767225630000 кратен шагу 1000, заявленный запас равен нулю,
                // поэтому вывод окна совпадает с периодом: start = floor(1767225600000/1000)*1000,
                // end = ceil(1767225630000/1000)*1000, applied = min(0, 0) = 0.
                val period = recognizeRunPeriod(input.sourceType, input.path, input.sha256, 60_000L)
                assertEquals(RUN_START, period.firstSampleEpochMillis)
                assertEquals(RUN_END, period.lastSampleEpochMillis)
                val derived = (deriveAutoWindow(period, GRID_WINDOW) as AutoWindowOutcome.Derived).window
                assertEquals(RUN_START, derived.startMillis)
                assertEquals(RUN_END, derived.endMillis)
                assertEquals(0L, derived.appliedMarginMillis)

                assertEquals(1, fixture.requests.get())
                val recorded = fixture.windows.single()
                assertEquals("1767225601", recorded.getValue("start"))
                assertEquals("1767225630", recorded.getValue("end"))
                assertEquals("1", recorded.getValue("step"))
                assertEquals(runPeriodJson(period), store.readRunPeriod(input.runId))

                val summary = sourceSummary(outcome)
                assertEquals("COMPLETE", summary.getValue("status").jsonPrimitive.content)
                assertEquals("auto", summary.getValue("window_origin").jsonPrimitive.content)
                assertEquals(RUN_START, summary.getValue("recognized_start_epoch_ms").jsonPrimitive.long)
                assertEquals(RUN_END, summary.getValue("recognized_end_epoch_ms").jsonPrimitive.long)
                assertEquals(0L, summary.getValue("requested_margin_ms").jsonPrimitive.long)
                assertEquals(0L, summary.getValue("applied_margin_ms").jsonPrimitive.long)
                assertEquals(60_000L, summary.getValue("max_idle_gap_ms").jsonPrimitive.long)
                assertEquals(0L, summary.getValue("detected_idle_gaps").jsonPrimitive.long)
                assertEquals(JsonNull, summary.getValue("longest_idle_gap_ms"))
                assertEquals("DERIVED", summary.getValue("auto_window_status").jsonPrimitive.content)
                assertEquals("run_intersection", bindingEvidence(outcome).getValue("mode").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `auto window margin is fetched wider and clipped to the run at binding`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 30), "margin.jtl")

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = MARGIN_AUTO), fixture.source())

                // Период 1767225600000..1767225630000, margin 60000, шаг 15000:
                // start = floor((1767225600000 - 60000) / 15000) * 15000 = 117815036 * 15000 = 1767225540000,
                // end = ceil((1767225630000 + 60000) / 15000) * 15000 = 117815046 * 15000 = 1767225690000,
                // applied = min(60000, 60000) = 60000. Запрос к источнику уходит именно за это окно.
                val period = recognizeRunPeriod(input.sourceType, input.path, input.sha256, 1_800_000L)
                val derived = (deriveAutoWindow(period, MARGIN_WINDOW) as AutoWindowOutcome.Derived).window
                assertEquals(1_767_225_540_000L, derived.startMillis)
                assertEquals(1_767_225_690_000L, derived.endMillis)
                assertEquals(60_000L, derived.appliedMarginMillis)
                assertNotEquals(period.firstSampleEpochMillis, derived.startMillis)
                assertNotEquals(period.lastSampleEpochMillis, derived.endMillis)

                assertEquals(1, fixture.requests.get())
                val recorded = fixture.windows.single()
                assertEquals("1767225555", recorded.getValue("start"))
                assertEquals("1767225690", recorded.getValue("end"))
                assertEquals("15", recorded.getValue("step"))
                assertEquals(runPeriodJson(period), store.readRunPeriod(input.runId))

                val summary = sourceSummary(outcome)
                assertEquals("auto", summary.getValue("window_origin").jsonPrimitive.content)
                assertEquals(60_000L, summary.getValue("requested_margin_ms").jsonPrimitive.long)
                assertEquals(60_000L, summary.getValue("applied_margin_ms").jsonPrimitive.long)

                // Snapshot авто-окна не объявляет окон, поэтому запас обрезается пересечением с прогоном:
                // firstIndex = ceil((1767225600000 - 1767225540000) / 15000) = ceil(4.0) = 4,
                // endIndex = floor((1767225630000 - 1767225540000) / 15000) = floor(6.0) = 6,
                // окно = 1767225540000 + 4*15000 .. 1767225540000 + 6*15000 = 1767225600000..1767225630000.
                val binding = bindingEvidence(outcome)
                assertEquals("run_intersection", binding.getValue("mode").jsonPrimitive.content)
                assertEquals(1_767_225_540_000L, binding.getValue("snapshot_from_epoch_ms").jsonPrimitive.long)
                assertEquals(1_767_225_690_000L, binding.getValue("snapshot_to_epoch_ms").jsonPrimitive.long)
                assertEquals(RUN_START, binding.getValue("evaluation_from_epoch_ms").jsonPrimitive.long)
                assertEquals(RUN_END, binding.getValue("evaluation_to_epoch_ms").jsonPrimitive.long)
                assertEquals(RUN_START, binding.getValue("run_from_epoch_ms").jsonPrimitive.long)
                assertEquals(RUN_END, binding.getValue("run_to_epoch_ms").jsonPrimitive.long)
            }
        }
    }

    @Test
    fun `auto window refuses a long idle gap before any source request`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input =
                    accept(
                        store,
                        csv(
                            listOf(
                                row(RUN_START, 500, "one"),
                                row(RUN_START + 1_000, 500, "two"),
                                row(RUN_START + 300_000, 500, "three"),
                            ),
                        ),
                        "idle.jtl",
                    )

                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())
                    }

                assertEquals(AUTO_WINDOW_MULTI_TEST_SUSPECTED, failure.message)
                assertEquals(0, fixture.requests.get())
                // Отказ до выборки не оставляет каталога анализа: ложное покрытие невозможно.
                assertTrue(store.listAnalyses(input.runId, null, 10).analyses.isEmpty())
            }
        }
    }

    @Test
    fun `auto window refuses an unrecognized load input before any source request`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, csv(listOf("not-a-number,500,one,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0")), "invalid.jtl")

                val failure =
                    assertThrows(IllegalArgumentException::class.java) {
                        analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())
                    }

                assertEquals(AUTO_WINDOW_UNAVAILABLE, failure.message)
                assertEquals(0, fixture.requests.get())
                assertTrue(store.listAnalyses(input.runId, null, 10).analyses.isEmpty())
            }
        }
    }

    @Test
    fun `a stored run period is reused instead of recognizing the input again`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, root ->
                val input = accept(store, contiguousCsv(RUN_START, 30), "reuse.jtl")

                val first = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())
                assertEquals("1767225630", fixture.windows.single().getValue("end"))

                // Валидный документ того же формата, привязанный к тем же байтам, но с другим периодом.
                val replaced =
                    runPeriodJson(
                        RunPeriodV1(
                            RUN_PERIOD_SCHEMA_VERSION,
                            input.sha256,
                            RUN_PERIOD_RECOGNITION_METHOD,
                            1_767_225_605_000L,
                            1_767_225_620_000L,
                            null,
                            0,
                            RUN_PERIOD_STATUS_RECOGNIZED,
                        ),
                    )
                val path = root.resolve("runs").resolve(input.runId).resolve("run-period.json")
                val bytes = canonicalJson(replaced)
                Files.writeString(path, bytes.decodeToString(), StandardOpenOption.TRUNCATE_EXISTING)

                val second = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())

                assertEquals(2, fixture.requests.get())
                val recorded = fixture.windows.toList()[1]
                assertEquals("1767225606", recorded.getValue("start"))
                assertEquals("1767225620", recorded.getValue("end"))
                assertEquals("1", recorded.getValue("step"))
                val summary = sourceSummary(second)
                assertEquals(1_767_225_605_000L, summary.getValue("recognized_start_epoch_ms").jsonPrimitive.long)
                assertEquals(1_767_225_620_000L, summary.getValue("recognized_end_epoch_ms").jsonPrimitive.long)
                assertNotEquals(first.analysisId, second.analysisId)
                // Распознавание не повторялось: байты подменённого артефакта не перезаписаны.
                assertArrayEquals(bytes, Files.readAllBytes(path))
            }
        }
    }

    @Test
    fun `multi-profile auto window keeps provenance on the aggregate summary only`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 30), "multi.jtl")
                val windowed = autoRequest(GRID_WINDOW, listOf("local", "second"))

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = windowed), fixture.source())

                // Документированная боевая форма: два метрических профиля на одном распознанном окне.
                assertEquals(2, fixture.requests.get())
                assertEquals(2, fixture.windows.size)

                val snapshot =
                    Json
                        .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("resource-snapshot.json")))
                        .jsonObject
                assertFalse("windows" in snapshot)
                assertEquals("run_intersection", bindingEvidence(outcome).getValue("mode").jsonPrimitive.content)

                val summary = sourceSummary(outcome)
                assertEquals("multiple", summary.getValue("profile_id").jsonPrimitive.content)
                assertEquals(WINDOW_PROVENANCE_FIELDS, summary.keys.intersect(WINDOW_PROVENANCE_FIELDS))
                val profiles = summary.getValue("profiles").jsonArray
                assertEquals(2, profiles.size)
                profiles.forEach { profile ->
                    assertEquals(emptySet<String>(), profile.jsonObject.keys.intersect(WINDOW_PROVENANCE_FIELDS))
                }
            }
        }
    }

    @Test
    fun `v1 requests keep the source summary free of window provenance`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, csv(listOf(row(RUN_START, 1_000, "one"))), "v1.jtl")
                val windowed = readWindowedSourceRequest(ONLINE_SOURCE_REQUEST.byteInputStream())

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = windowed), fixture.source())

                val summary = sourceSummary(outcome)
                assertFalse(summary.containsKey("window_origin"))
                assertEquals(LEGACY_SUMMARY_FIELDS, summary.keys)
                assertEquals("explicit_windows", bindingEvidence(outcome).getValue("mode").jsonPrimitive.content)
                assertEquals(null, store.readRunPeriod(input.runId))
            }
        }
    }

    @Test
    fun `explicit windows publish their origin only for v3`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, csv(listOf(row(RUN_START, 1_000, "one"))), "v3-explicit.jtl")
                val document =
                    """{"schema_version":"source-request.v3","profile_ids":["local"],
                    "window":{"origin":"explicit","start_epoch_ms":1767225600000,"end_epoch_ms":1767225601000,"step_ms":1000}}"""
                val windowed = readWindowedSourceRequest(document.byteInputStream())

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = windowed), fixture.source())

                val summary = sourceSummary(outcome)
                assertEquals("explicit", summary.getValue("window_origin").jsonPrimitive.content)
                assertEquals(LEGACY_SUMMARY_FIELDS + "window_origin", summary.keys)
                assertEquals("explicit_windows", bindingEvidence(outcome).getValue("mode").jsonPrimitive.content)
                assertEquals(null, store.readRunPeriod(input.runId))
            }
        }
    }

    @Test
    fun `auto step coarsens an over-budget collection and publishes its provenance`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 3_600), "autostep.jtl")
                val request = v4Request(auto = true)

                val outcome =
                    analyzeWithSources(
                        service,
                        AnalysisRequest(input, null, sourceRequest = request),
                        fixture.source(queryCount = 2, scrapeIntervalMillis = 1_000L),
                        cellBudget = 3_600L,
                    )

                // Two series need 7,200 cells at 1 s and 3,600 cells at 2 s.
                assertEquals(2, fixture.requests.get())
                assertEquals(setOf("2"), fixture.windows.map { it.getValue("step") }.toSet())
                val summary = sourceSummary(outcome)
                assertEquals(2_000L, summary.getValue("step_ms").jsonPrimitive.long)
                assertEquals("auto", summary.getValue("step_origin").jsonPrimitive.content)
                assertEquals(1_000L, summary.getValue("requested_step_ms").jsonPrimitive.long)
                assertEquals(2L, summary.getValue("series_count").jsonPrimitive.long)
                assertEquals(3_600L, summary.getValue("cell_budget").jsonPrimitive.long)
                assertEquals(1_800L, summary.getValue("cells_per_series").jsonPrimitive.long)
                val warning =
                    summary
                        .getValue("warnings")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("RESOLUTION_REDUCED", warning.getValue("code").jsonPrimitive.content)
                assertEquals(1_000L, warning.getValue("requested_step_ms").jsonPrimitive.long)
                assertEquals(2_000L, warning.getValue("applied_step_ms").jsonPrimitive.long)
                assertEquals(
                    listOf("cpu-0", "cpu-1"),
                    warning.getValue("series").jsonArray.map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    },
                )
                assertEquals("auto", summary.getValue("window_origin").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `v3 requests never carry the step fields`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 30), "v3-no-step.jtl")

                val outcome = analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = GRID_AUTO), fixture.source())

                val stepFields =
                    setOf(
                        "step_origin",
                        "requested_step_ms",
                        "series_count",
                        "cell_budget",
                        "cells_per_series",
                        "warnings",
                        "rule_spans",
                    )
                assertEquals(emptySet<String>(), sourceSummary(outcome).keys.intersect(stepFields))
                assertEquals(LEGACY_SUMMARY_FIELDS + WINDOW_PROVENANCE_FIELDS, sourceSummary(outcome).keys)
            }
        }
    }

    @Test
    fun `auto step keeps the requested step inside the budget and fixed mode never changes it`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 3_600), "within.jtl")

                val within =
                    analyzeWithSources(
                        service,
                        AnalysisRequest(input, null, sourceRequest = v4Request(auto = true)),
                        fixture.source(queryCount = 2, scrapeIntervalMillis = 1_000L),
                        cellBudget = 7_200L,
                    )
                val fixed =
                    analyzeWithSources(
                        service,
                        AnalysisRequest(input, null, sourceRequest = v4Request(auto = false)),
                        fixture.source(queryCount = 2),
                        cellBudget = 10L,
                    )

                assertEquals(setOf("1"), fixture.windows.map { it.getValue("step") }.toSet())
                assertEquals("auto", sourceSummary(within).getValue("step_origin").jsonPrimitive.content)
                assertFalse(sourceSummary(within).containsKey("warnings"))
                assertEquals("explicit", sourceSummary(fixed).getValue("step_origin").jsonPrimitive.content)
                assertEquals(LEGACY_SUMMARY_FIELDS + WINDOW_PROVENANCE_FIELDS + "step_origin", sourceSummary(fixed).keys)
            }
        }
    }

    @Test
    fun `autostep refusals happen before any request and keep their code`() {
        RecordingPrometheus().use { fixture ->
            withService { store, service, _ ->
                val input = accept(store, contiguousCsv(RUN_START, 3_600), "refused.jtl")

                val noInterval =
                    assertThrows(SourcePlanRefusal::class.java) {
                        analyzeWithSources(
                            service,
                            AnalysisRequest(input, null, sourceRequest = v4Request(auto = true)),
                            fixture.source(queryCount = 2, scrapeIntervalMillis = null),
                            cellBudget = 3_600L,
                        )
                    }
                val unsatisfiable =
                    assertThrows(SourcePlanRefusal::class.java) {
                        analyzeWithSources(
                            service,
                            AnalysisRequest(input, null, sourceRequest = v4Request(auto = true, maxIdleGap = 1_000)),
                            fixture.source(queryCount = 2, scrapeIntervalMillis = 1_000L),
                            cellBudget = 3_600L,
                        )
                    }

                assertEquals(AUTO_STEP_SCRAPE_INTERVAL_REQUIRED, noInterval.code)
                assertEquals(AUTO_STEP_UNSATISFIABLE, unsatisfiable.code)
                assertEquals(0, fixture.requests.get())
            }
        }
    }

    private fun withService(block: (RunBundleStore, AnalysisService, Path) -> Unit) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()), root)
        }
    }

    private fun accept(
        store: RunBundleStore,
        content: String,
        name: String,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(content.encodeToByteArray()), name)

    private fun sourceSummary(outcome: AnalysisOutcome): JsonObject = evidence(outcome, "source_summary")

    private fun bindingEvidence(outcome: AnalysisOutcome): JsonObject = evidence(outcome, "resource_binding")

    private fun evidence(
        outcome: AnalysisOutcome,
        type: String,
    ): JsonObject =
        Json
            .parseToJsonElement(outcome.canonicalResult.decodeToString())
            .jsonObject
            .getValue("evidence")
            .jsonArray
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == type }

    private fun contiguousCsv(
        first: Long,
        seconds: Int,
    ): String = csv((0 until seconds).map { row(first + it * 1_000L, 1_000L, "sample-$it") })

    private fun csv(rows: List<String>): String = (listOf(CSV_HEADER) + rows).joinToString("\n", postfix = "\n")

    private fun row(
        startedAt: Long,
        elapsed: Long,
        label: String,
    ): String = "$startedAt,$elapsed,$label,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0"

    /** Prometheus-стенд, который отвечает на запрошенное окно и запоминает его параметры. */
    private class RecordingPrometheus : AutoCloseable {
        val requests = AtomicInteger()
        val windows = ConcurrentLinkedQueue<Map<String, String>>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/api/v1/query_range") { exchange ->
                requests.incrementAndGet()
                val parameters = queryParameters(exchange.requestURI.rawQuery)
                windows += parameters
                val first = parameters.getValue("start").toLong()
                val last = parameters.getValue("end").toLong()
                val step = parameters.getValue("step").toLong()
                val cells = (last - first) / step + 1
                val values = (0 until cells).joinToString(",", "[", "]") { "[${first + it * step},\"0.5\"]" }
                val body =
                    """{"status":"success","data":{"resultType":"matrix",
                    "result":[{"metric":{"instance":"node-a"},"values":$values}]}}""".encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }

        fun source(
            queryCount: Int = 1,
            scrapeIntervalMillis: Long? = null,
        ): PromqlSource {
            val profile =
                SourceProfile(
                    id = "local",
                    sourceKind = SourceKind.PROMETHEUS,
                    transport = SourceTransport.DIRECT,
                    baseUrl = URI.create("http://127.0.0.1:${server.address.port}"),
                    datasourceUid = null,
                    allowInsecureHttp = true,
                    governor = SourceGovernor(requestsPerSecond = 1_000.0, timeoutMillis = 2_000, maxAttempts = 1),
                    queries =
                        (0 until queryCount).map { index ->
                            SourceQuery(
                                id = if (queryCount == 1) "cpu" else "cpu-$index",
                                expression = "avg_over_time(cpu[\$__interval])",
                                metric = "cpu_used",
                                unit = "ratio",
                                entity = "node-a",
                                role = ResourceRole.SYSTEM,
                                aggregation = ResourceAggregation.INTERVAL_MEAN,
                                labels = mapOf("instance" to "node-a"),
                            )
                        },
                    scrapeIntervalMillis = scrapeIntervalMillis,
                )
            val profiles = listOf(profile, profile.copy(id = "second"))
            return PromqlSource(profiles, SourceHttp(profiles))
        }

        private fun queryParameters(raw: String): Map<String, String> =
            raw.split('&').associate { field ->
                field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), StandardCharsets.UTF_8)
            }

        override fun close() = server.stop(0)
    }

    private companion object {
        const val RUN_START = 1_767_225_600_000L
        const val RUN_END = 1_767_225_630_000L

        const val CSV_HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect"

        val GRID_WINDOW = AutoWindow(0L, 60_000L, 1_000L)

        val GRID_AUTO = autoRequest(GRID_WINDOW)

        val MARGIN_WINDOW = AutoWindow(60_000L, 1_800_000L, 15_000L)

        val MARGIN_AUTO = autoRequest(MARGIN_WINDOW)

        val LEGACY_SUMMARY_FIELDS =
            setOf(
                "cap_exceeded",
                "end_epoch_ms",
                "id",
                "profile_id",
                "queries",
                "request_count",
                "retries",
                "source_kind",
                "start_epoch_ms",
                "status",
                "step_ms",
                "throttle_wait_ms",
                "transport",
                "type",
            )

        // Девять полей provenance окна: их публикует только агрегированная сводка, не сводки профилей.
        val WINDOW_PROVENANCE_FIELDS =
            setOf(
                "applied_margin_ms",
                "auto_window_status",
                "detected_idle_gaps",
                "longest_idle_gap_ms",
                "max_idle_gap_ms",
                "recognized_end_epoch_ms",
                "recognized_start_epoch_ms",
                "requested_margin_ms",
                "window_origin",
            )

        fun v4Request(
            auto: Boolean,
            maxIdleGap: Long = 60_000,
        ): WindowedSourceRequest =
            readWindowedSourceRequest(
                """{"schema_version":"source-request.v4","profile_ids":["local"],
                "window":{"origin":"auto","step_ms":1000,"step_mode":"${if (auto) "auto" else "fixed"}",
                "margin_ms":0,"max_idle_gap_ms":$maxIdleGap}}""".byteInputStream(),
            )

        fun autoRequest(
            window: AutoWindow,
            profileIds: List<String> = listOf("local"),
        ): WindowedSourceRequest =
            readWindowedSourceRequest(
                """{"schema_version":"source-request.v3",
                "profile_ids":${profileIds.joinToString(",", "[", "]") { "\"$it\"" }},
                "window":{"origin":"auto","step_ms":${window.stepMillis},"margin_ms":${window.marginMillis},
                "max_idle_gap_ms":${window.maxIdleGapMillis}}}""".byteInputStream(),
            )
    }
}

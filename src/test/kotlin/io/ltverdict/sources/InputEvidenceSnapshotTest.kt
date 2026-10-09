package io.ltverdict.sources

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.resourceBindingEvidence
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 slice 2c characterization of the resource_binding, source_summary and opensearch_errors producers: the items they write
 * for a wide matrix of inputs hash to the values captured from `origin/main` BEFORE the producers were typed. The hash covers
 * the canonical bytes and every leaf as kotlinx writes it (so a number that lost its text would show), by sorted path. Regenerate
 * only on purpose with `LTV_UPDATE_INPUT_EVIDENCE=1` (a change here is a change of analysis-result bytes and of analysis_id).
 */
class InputEvidenceSnapshotTest {
    private val snapshotFile = Path.of("fixtures/typed-evidence/input-evidence.sha256")
    private val samplesFile = Path.of("fixtures/typed-evidence/samples-input-evidence.ndjson")
    private val keySetsFile = Path.of("fixtures/typed-evidence/input-evidence-keysets.txt")
    private val start = ScriptedSource.START
    private val hash = ScriptedSource.HASH

    private val groups = linkedMapOf<String, StringBuilder>()
    private val seenStates = sortedSetOf<String>()
    private val keySets = sortedSetOf<String>()
    private val samples = sortedMapOf<String, JsonObject>()

    private fun normalise(text: String) = text.replace(Regex("127\\.0\\.0\\.1:\\d+"), "127.0.0.1:PORT")

    private fun leaves(
        path: String,
        element: JsonElement,
        out: MutableList<String>,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> leaves("$path.$key", value, out) }
            is JsonArray -> element.forEachIndexed { index, value -> leaves("$path[$index]", value, out) }
            else -> out += "$path=${Json.encodeToString(JsonElement.serializer(), element)}"
        }
    }

    private fun outcome(raw: JsonElement): String {
        val element = withoutThrottle(raw)
        val bytes = runCatching { canonicalJson(element).decodeToString() }.getOrElse { "FAILS: ${it.message}" }
        val dump = mutableListOf<String>()
        leaves("", element, dump)
        return normalise(bytes + "\n" + dump.sorted().joinToString("\n"))
    }

    private fun states(
        path: String,
        element: JsonElement,
    ) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> states("$path.$key", value) }
            is JsonArray -> {
                seenStates += "$path#${if (element.isEmpty()) "empty" else "items"}"
                element.forEach { states("$path[]", it) }
            }
            is JsonNull -> seenStates += "$path=null"
            else -> seenStates += "$path=value"
        }
    }

    private fun record(
        group: String,
        items: List<JsonObject>,
        artifacts: Map<String, ByteArray> = emptyMap(),
    ) {
        items.forEach { item ->
            val type = item.getValue("type").jsonPrimitive.content
            states(type, item)
            keySets += "$group|$type:${item.keys.sorted()}"
            // The provenance of the window is merged after the typed item, so the UI sample of the type leaves it out.
            if ("window_origin" !in item &&
                "step_origin" !in item
            ) {
                samples.putIfAbsent("$type:${item.keys.sorted()}", withoutThrottle(item) as JsonObject)
            }
        }
        val text =
            items.joinToString("\n") { outcome(it) } +
                "\n--\n" +
                artifacts.toSortedMap().entries.joinToString("\n") { (name, bytes) -> normalise("$name=${withoutThrottle(bytes)}") }
        groups.getOrPut(group) { StringBuilder() }.append(sha256Hex(text.encodeToByteArray())).append('\n')
    }

    private fun recordFailure(
        group: String,
        error: Throwable,
    ) {
        groups
            .getOrPut(group) { StringBuilder() }
            .append(sha256Hex("THROWS ${error::class.simpleName}: ${error.message}".encodeToByteArray()))
            .append('\n')
    }

    private fun request(
        profile: String,
        cells: Int = 1,
        additional: List<String> = emptyList(),
        provenance: JsonObject? = null,
    ) = SourceRequest(profile, start, start + cells * 1_000L, 1_000, additional, provenance)

    private fun acquire(
        group: String,
        source: PromqlSource,
        request: SourceRequest,
    ): SourceAcquisition? =
        runCatching { source.acquire(request, hash) }
            .onSuccess {
                record(
                    group,
                    listOf(it.evidence) + it.contextEvidence,
                    it.artifacts.filterKeys { name ->
                        name.endsWith(".json") &&
                            !name.startsWith("source-response-")
                    },
                )
            }.onFailure { recordFailure(group, it) }
            .getOrNull()

    private val autoNull =
        buildJsonObject {
            put("window_origin", "auto")
            put("recognized_start_epoch_ms", start)
            put("recognized_end_epoch_ms", start + 3_000)
            put("requested_margin_ms", 0)
            put("applied_margin_ms", 0)
            put("max_idle_gap_ms", 60_000)
            put("detected_idle_gaps", 0)
            put("longest_idle_gap_ms", JsonNull)
            put("auto_window_status", "DERIVED")
        }
    private val autoStep =
        JsonObject(
            autoNull +
                buildJsonObject {
                    put("longest_idle_gap_ms", 4_000)
                    put("step_origin", "auto")
                    put("requested_step_ms", 500)
                    put("series_count", 2)
                    put("cell_budget", 1_000)
                    put("cells_per_series", 3)
                    put(
                        "warnings",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("code", "RESOLUTION_REDUCED")
                                    put("requested_step_ms", 500)
                                    put("applied_step_ms", 1_000)
                                    put("series", buildJsonArray { add(buildJsonObject { put("id", "cpu") }) })
                                },
                            )
                        },
                    )
                },
        )
    private val explicitV4 =
        buildJsonObject {
            put("window_origin", "explicit")
            put("step_origin", "explicit")
        }

    // Request bodies below answer a window of one to three 1000 ms cells that start at START.
    private fun openSearchDecodeCases(): List<Triple<String, String, Pair<OpenSearchMapping, SourceRequest>>> {
        fun mapping(
            samples: Int = 0,
            bytes: Int = 4_096,
            limit: Int = 50,
        ) = OpenSearchMapping(
            listOf("logs-*"),
            "@timestamp",
            "service.name",
            "error.type",
            "error.message",
            samplesPerGroup = samples,
            sampleMessageBytesMax = bytes,
            groupLimit = limit,
        )

        fun response(
            timeline: String = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":2}]""",
            total: String = "3",
            relation: String = "eq",
            timedOut: Boolean = false,
            shards: String = """{"total":1,"successful":1,"skipped":0,"failed":0}""",
            sumOther: String = "0",
            error: String = "0",
            groups: String = """[{"key":["api","Timeout"],"doc_count":3,"first_at":{"value":1100},"last_at":{"value":2900}}]""",
        ) = """{"took":4,"timed_out":$timedOut,"_shards":$shards,"hits":{"total":{"value":$total,"relation":"$relation"},"hits":[]},
            "aggregations":{"timeline":{"buckets":$timeline},
            "groups":{"doc_count_error_upper_bound":$error,"sum_other_doc_count":$sumOther,"buckets":$groups}}}"""

        val two = SourceRequest("errors", 1_000, 3_000, 1_000)
        val odd = SourceRequest("errors", 0, 21_000, 7_000)
        val sampleGroups =
            """[{"key":["api","Timeout"],"doc_count":2,"first_at":{"value":1100},"last_at":{"value":2100},"samples":{"hits":{"hits":[
              {"_index":"logs/2026","_id":"a b/ç?","sort":[1100],"_source":{"error":{"message":"ééé"},"url":"javascript:alert(1)"}},
              {"_index":"logs-2026","_id":"missing","sort":[2100],"_source":{}}]}}}]"""
        val huge = "4000000000000000000"
        return listOf(
            Triple("complete", response(), mapping() to two),
            Triple(
                "samples",
                response(total = "2", timeline = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":1}]""", groups = sampleGroups),
                mapping(2, 5) to two,
            ),
            Triple("gte", response(relation = "gte"), mapping() to two),
            Triple("timed-out", response(timedOut = true), mapping() to two),
            Triple("shards-failed", response(shards = """{"total":3,"successful":2,"skipped":0,"failed":1}"""), mapping() to two),
            Triple("shards-skipped", response(shards = """{"total":3,"successful":3,"skipped":2,"failed":0}"""), mapping() to two),
            Triple(
                "truncated-terms",
                response(
                    total = "9",
                    sumOther = "6",
                    error = "-1",
                    timeline = """[{"key":1000,"doc_count":4},{"key":2000,"doc_count":5}]""",
                    groups = """[{"key":["api","Timeout"],"doc_count":3,"first_at":{"value":1100},"last_at":{"value":2900}}]""",
                ),
                mapping(limit = 1) to two,
            ),
            Triple("approximate-counts", response(error = "7"), mapping() to two),
            Triple(
                "empty",
                response(total = "0", timeline = """[{"key":1000,"doc_count":0},{"key":2000,"doc_count":0}]""", groups = "[]"),
                mapping() to two,
            ),
            Triple(
                "huge-counts",
                response(
                    total = "9000000000000000000",
                    timeline = """[{"key":1000,"doc_count":$huge},{"key":2000,"doc_count":5000000000000000000}]""",
                    groups =
                        """[{"key":["api","Timeout"],"doc_count":9000000000000000000,
                        "first_at":{"value":1100},"last_at":{"value":2900}}]""",
                ),
                mapping() to two,
            ),
            Triple(
                "fractional-rates",
                response(
                    total = "3",
                    timeline = """[{"key":0,"doc_count":1},{"key":7000,"doc_count":2},{"key":14000,"doc_count":0}]""",
                    groups = """[{"key":["é","Timeout"],"doc_count":3,"first_at":{"value":100},"last_at":{"value":9000}}]""",
                ),
                mapping() to odd,
            ),
            Triple("malformed", "not json", mapping() to two),
            Triple("error-body", """{"error":"boom"}""", mapping() to two),
            Triple("counts-mismatch", response(total = "5"), mapping() to two),
        )
    }

    @Test
    fun `resource binding, source summaries and opensearch contexts equal the pre-typing snapshot`() {
        // resource_binding
        ScriptedSource.bindingCases().forEach { case ->
            runCatching { resourceBindingEvidence(case.snapshot, case.windows, case.runStart, case.runEnd) }
                .onSuccess { record("binding.${case.name}", listOf(it)) }
                .onFailure { recordFailure("binding.${case.name}", it) }
        }

        // opensearch_errors through the decoder and the strict importer, and the import of artifacts with other number texts
        val decoded = mutableMapOf<String, JsonObject>()
        openSearchDecodeCases().forEach { (name, body, setup) ->
            runCatching {
                decodeOpenSearchResponse(
                    body.encodeToByteArray(),
                    setup.first,
                    setup.second,
                    hash,
                    URI.create("https://search.example/base/"),
                )
            }.onSuccess {
                decoded[name] = it
                record("decode.$name", listOf(it))
                runCatching { validateOpenSearchArtifact(canonicalJson(it).inputStream(), hash) }
                    .onSuccess { validated -> record("validate.$name", listOf(validated)) }
                    .onFailure { failure -> recordFailure("validate.$name", failure) }
            }.onFailure { recordFailure("decode.$name", it) }
        }
        val complete = canonicalJson(decoded.getValue("complete")).decodeToString()
        listOf(
            "scale-exponent" to
                complete
                    .replace(
                        "\"error_rate_per_minute\":90",
                        "\"error_rate_per_minute\":9E+1",
                    ).replace("\"rate_per_minute\":60", "\"rate_per_minute\":6E+1"),
            "scale-trailing" to
                complete
                    .replace(
                        "\"error_rate_per_minute\":90",
                        "\"error_rate_per_minute\":90.0",
                    ).replace("\"rate_per_minute\":120", "\"rate_per_minute\":120.00"),
            "scale-thousand" to complete.replace("\"rate_per_minute\":120", "\"rate_per_minute\":1.2E+2"),
            "rate-mismatch" to complete.replace("\"error_rate_per_minute\":90", "\"error_rate_per_minute\":91"),
        ).forEach { (name, text) ->
            runCatching { readOpenSearchContext(text.byteInputStream(), hash) }
                .onSuccess { record("import.$name", listOf(it.evidence) + it.contextEvidence, it.artifacts) }
                .onFailure { recordFailure("import.$name", it) }
        }

        ScriptedSource().use { server ->
            val metric = server.prometheus()
            val errors = server.openSearch()

            fun prom(
                group: String,
                status: Int,
                body: String,
                profile: SourceProfile = metric,
                cells: Int = 1,
                provenance: JsonObject? = null,
            ) {
                server.promStatus = status
                server.promBody = body
                acquire(group, server.source(profile), request(profile.id, cells, provenance = provenance))
            }
            prom("prom.success", 200, ScriptedSource.matrix(listOf("0.9")))
            prom("prom.partial", 200, ScriptedSource.matrix(listOf("0.9")), cells = 3)
            prom("prom.empty", 200, """{"status":"success","data":{"resultType":"matrix","result":[]}}""")
            prom("prom.http-500", 500, "boom")
            prom("prom.http-503", 503, "boom")
            prom("prom.malformed", 200, "not json")
            prom(
                "prom.two-queries-cap",
                200,
                ScriptedSource.matrix(listOf("0.9")),
                server.prometheus(queryIds = listOf("cpu", "mem"), maxRequests = 1),
            )
            prom("prom.two-queries", 200, ScriptedSource.matrix(listOf("0.9")), server.prometheus(queryIds = listOf("cpu", "mem")))
            prom("prom.arm", 200, ScriptedSource.matrix(listOf("0.9")), server.prometheus(arm = "blue"))
            prom("prom.spans", 200, ScriptedSource.matrix(listOf("0.9")), server.prometheus(spans = mapOf("cpu-high" to 61_000L)))
            prom("prom.spans-unmatched", 200, ScriptedSource.matrix(listOf("0.9")), server.prometheus(spans = mapOf("unknown" to 5_000L)))
            prom("prom.victoria", 200, ScriptedSource.matrix(listOf("0.9")), server.prometheus(kind = SourceKind.VICTORIA_METRICS))
            prom("prom.provenance-auto-null", 200, ScriptedSource.matrix(listOf("0.9")), provenance = autoNull)
            prom("prom.provenance-auto-step", 200, ScriptedSource.matrix(listOf("0.9")), provenance = autoStep)
            prom("prom.provenance-explicit", 200, ScriptedSource.matrix(listOf("0.9")), provenance = explicitV4)
            prom(
                "prom.arm-spans-provenance",
                200,
                ScriptedSource.matrix(listOf("0.9")),
                server.prometheus(
                    arm = "blue",
                    spans =
                        mapOf(
                            "cpu-high" to 1_500L,
                        ),
                ),
                provenance = autoStep,
            )

            fun os(
                group: String,
                status: Int,
                body: String,
                profile: SourceProfile = errors,
                cells: Int = 1,
                provenance: JsonObject? = null,
            ): SourceAcquisition? {
                server.osStatus = status
                server.osBody = body
                return acquire(group, server.source(profile), request(profile.id, cells, provenance = provenance))
            }
            val liveComplete = os("os.complete", 200, ScriptedSource.openSearchBody(listOf(1)))
            val livePartial = os("os.gte", 200, ScriptedSource.openSearchBody(listOf(1, 2, 0), relation = "gte"), cells = 3)
            os("os.timed-out", 200, ScriptedSource.openSearchBody(listOf(1), timedOut = true))
            os("os.empty", 200, ScriptedSource.openSearchBody(listOf(0)))
            os(
                "os.samples",
                200,
                ScriptedSource.openSearchBody(listOf(1, 2), sample = true),
                server.openSearch(samplesPerGroup = 2),
                cells = 2,
            )
            os("os.http-503", 503, "boom")
            os("os.http-500", 500, "boom")
            os("os.malformed", 200, "not json")
            os("os.error-body", 200, """{"error":"boom"}""")
            os("os.cap", 200, ScriptedSource.openSearchBody(listOf(1)), server.openSearch(maxRequests = 0))
            os("os.provenance-auto-null", 200, ScriptedSource.openSearchBody(listOf(1)), provenance = autoNull)
            os("os.provenance-explicit", 200, ScriptedSource.openSearchBody(listOf(1)), provenance = explicitV4)

            // several profiles in one snapshot
            fun multi(
                group: String,
                promStatus: Int,
                osStatus: Int,
                profiles: List<SourceProfile>,
                provenance: JsonObject? = null,
            ) {
                server.promStatus = promStatus
                server.promBody = ScriptedSource.matrix(listOf("0.9"))
                server.osStatus = osStatus
                server.osBody = ScriptedSource.openSearchBody(listOf(1))
                acquire(
                    group,
                    server.source(*profiles.toTypedArray()),
                    request(profiles.first().id, 1, profiles.drop(1).map { it.id }, provenance),
                )
            }
            multi("multi.metric-errors", 200, 200, listOf(metric, errors))
            multi("multi.errors-503", 200, 503, listOf(metric, errors))
            multi("multi.metric-500", 500, 200, listOf(metric, errors))
            multi("multi.all-failed", 500, 503, listOf(metric, errors))
            multi(
                "multi.arm-two-metrics",
                200,
                200,
                listOf(server.prometheus("a", arm = "blue"), server.prometheus("b", listOf("mem"), arm = "blue"), errors),
                autoNull,
            )
            multi("multi.provenance-step", 200, 200, listOf(metric, server.prometheus("local2", listOf("mem")), errors), autoStep)
            multi("multi.spans", 200, 200, listOf(server.prometheus(spans = mapOf("cpu-high" to 61_000L)), errors), explicitV4)
            multi("multi.two-errors", 200, 200, listOf(server.openSearch("errors-a"), server.openSearch("errors-b")))

            // import of what the live path wrote, one and several contexts
            val completeArtifact = requireNotNull(liveComplete).artifacts.getValue("opensearch-errors.json")
            val partialArtifact = requireNotNull(livePartial).artifacts.getValue("opensearch-errors.json")
            runCatching { readOpenSearchContext(completeArtifact.inputStream(), hash) }
                .onSuccess { record("import.live-complete", listOf(it.evidence) + it.contextEvidence, it.artifacts) }
                .onFailure { recordFailure("import.live-complete", it) }
            runCatching { readOpenSearchContext(partialArtifact.inputStream(), hash) }
                .onSuccess { record("import.live-partial", listOf(it.evidence) + it.contextEvidence, it.artifacts) }
                .onFailure { recordFailure("import.live-partial", it) }

            fun artifactOf(profile: String): ByteArray {
                server.osStatus = 200
                server.osBody = ScriptedSource.openSearchBody(listOf(1))
                return server
                    .source(
                        server.openSearch(profile),
                    ).acquire(request(profile), hash)
                    .artifacts
                    .getValue("opensearch-errors.json")
            }
            val a = artifactOf("errors-a")
            val b = artifactOf("errors-b")
            val partialB = String(partialArtifact).replace("\"profile_id\":\"errors\"", "\"profile_id\":\"errors-c\"").encodeToByteArray()
            listOf(
                "two" to listOf(b, a),
                "three-with-partial" to listOf(a, b, partialB),
                "duplicate" to listOf(a, a),
                "single" to listOf(a),
            ).forEach { (name, inputs) ->
                runCatching { readOpenSearchContexts(inputs, hash) }
                    .onSuccess { record("imports.$name", listOf(it.evidence) + it.contextEvidence, it.artifacts) }
                    .onFailure { recordFailure("imports.$name", it) }
            }
        }

        val expectedStates =
            listOf(
                "resource_binding.mode=value",
                "resource_binding.dropped_leading_cells=value",
                "source_summary.arm=value",
                "source_summary.start_epoch_ms=value",
                "source_summary.queries[].reason=value",
                "source_summary.queries[].expression_sha256=value",
                "source_summary.rule_spans#empty",
                "source_summary.rule_spans#items",
                "source_summary.profiles#items",
                "source_summary.longest_idle_gap_ms=null",
                "source_summary.longest_idle_gap_ms=value",
                "source_summary.warnings#items",
                "opensearch_errors.groups#empty",
                "opensearch_errors.groups#items",
                "opensearch_errors.groups[].samples#empty",
                "opensearch_errors.groups[].samples#items",
                "opensearch_errors.groups[].samples[].message_truncated=value",
                "opensearch_errors.timeline#items",
                "opensearch_errors.coverage.reasons#empty",
                "opensearch_errors.coverage.reasons#items",
            )
        val missing = expectedStates.filterNot { it in seenStates }
        assertTrue(missing.isEmpty(), "snapshot does not reach: $missing")

        val actual = groups.entries.joinToString("") { (group, hashes) -> "$group ${sha256Hex(hashes.toString().encodeToByteArray())}\n" }
        val update = System.getenv("LTV_UPDATE_INPUT_EVIDENCE") == "1"
        val sampleLines = samples.values.joinToString("") { normalise(canonicalJson(it).decodeToString()) + "\n" }
        val keySetLines = keySets.joinToString("") { "$it\n" }
        if (update) {
            Files.writeString(snapshotFile, actual)
            Files.writeString(samplesFile, sampleLines)
            Files.writeString(keySetsFile, keySetLines)
        }
        assertEquals(Files.readString(snapshotFile).replace("\r\n", "\n"), actual)
        // The key set of every item per construction site (the compiler cannot tell the sites of one class apart).
        assertEquals(Files.readString(keySetsFile).replace("\r\n", "\n"), keySetLines, "the key sets per construction site changed")
        // One real item per type and key set: ui/scripts/verify-generated-types.mjs checks them against the generated TypeScript.
        assertEquals(Files.readString(samplesFile).replace("\r\n", "\n"), sampleLines)
    }
}

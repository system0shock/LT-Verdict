package io.ltverdict.core

import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

class StatisticalValidationTest {
    @Test
    fun `DEBUG window projections preserve saved load and policy evidence`() {
        val load = "timeStamp,elapsed,label,success\n0,100,request,true\n1000,100,request,true\n"
        val input = """{"operation":"analysis","run":{"load_jtl":${JsonPrimitive(load)},"resources":{
            "schema_version":"resource-snapshot.v1","load_input_sha256":"${sha256Hex(load.encodeToByteArray())}",
            "start_epoch_ms":0,"step_ms":1000,"point_count":1,
            "series":[{"id":"cpu","metric":"cpu","unit":"ratio","entity":"debug",
            "role":"system","aggregation":"interval_mean","values":[0.5]}],
            "windows":[{"id":"w","from_epoch_ms":0,"to_epoch_ms":1000}]}}}"""
        val document = Json.parseToJsonElement(input).jsonObject
        val run = document.getValue("run").jsonObject
        val resources = validateResourceSnapshot(canonicalJson(run.getValue("resources")).inputStream()) as ResourceValidation.Valid
        val diagnostics =
            Json.parseToJsonElement(
                """{"schema_version":"correlation-plan.v1","resource_snapshot_sha256":"${resources.semanticSha256}",
            "pairs":[{"id":"pair","resource_series_id":"cpu","load_metric":"response_time_p95_ms",
            "window_ids":["w"],"expected_sign":"either","max_lag_ms":0,"min_abs_effect":0.3,
            "min_resource_delta":0.1,"min_load_delta":20,"controls":[],"topology_basis":"debug",
            "clock_alignment":"declared_aligned"}]}""",
            )
        val bound = JsonObject(document + ("run" to JsonObject(run + ("diagnostics" to diagnostics))))
        val output = StatisticalValidationRunner.resource(bound.toString())
        assertEquals(
            "1",
            output
                .getValue("load_summaries")
                .jsonObject
                .getValue("w")
                .jsonObject
                .getValue("sample_count")
                .jsonPrimitive.content,
        )
        assertEquals(
            "NO_POLICY",
            output
                .getValue("window_policies")
                .jsonObject
                .getValue("w")
                .jsonObject
                .getValue("verdict")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `DEBUG confirmation context rejects non boolean before ingest`() {
        listOf("null", "\"true\"", "1", "{}", "[]").forEach { value ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    StatisticalValidationRunner.resource("""{"operation":"comparison","conditions_confirmed":$value}""")
                }
            assertEquals("conditions_confirmed must be a boolean", failure.message)
        }
    }

    @Test
    fun `DEBUG empty resource input records validation rejection not a healthy statistic`() {
        val output = StatisticalValidationRunner.resource("""{"operation":"resource","resources":{}}""")
        assertEquals("INVALID", output.getValue("validation_status").jsonPrimitive.content)
    }

    @Test
    fun `DEBUG resource fixture indexes summaries by window and series`() {
        val output =
            StatisticalValidationRunner.resource(
                """
                {
                  "operation":"resource",
                  "resources":{
                    "schema_version":"resource-snapshot.v1",
                    "load_input_sha256":"${"0".repeat(64)}",
                    "start_epoch_ms":0,"step_ms":1000,"point_count":2,
                    "series":[{
                      "id":"cpu","metric":"cpu","unit":"ratio","entity":"debug",
                      "role":"system","aggregation":"interval_mean","values":[1,2]
                    }],
                    "windows":[{"id":"debug","from_epoch_ms":0,"to_epoch_ms":2000}]
                  }
                }
                """.trimIndent(),
            )

        val summary =
            output
                .getValue("resource_summaries")
                .jsonObject
                .getValue("debug")
                .jsonObject
                .getValue("cpu")
                .jsonObject
        assertEquals("2", summary.getValue("observed_cells").jsonPrimitive.content)
    }

    @Test
    fun `frozen corpus runner is opt in`() {
        val paths = StatisticalValidationRunner.paths(System.getenv())
        assumeTrue(
            paths != null,
            "statistical validation corpus is not configured; set LTV_STATS_CORPUS and LTV_STATS_ACTUAL",
        )
        StatisticalValidationRunner.run(requireNotNull(paths))
    }
}

internal object StatisticalValidationRunner {
    private const val CORPUS = "LTV_STATS_CORPUS"
    private const val ACTUAL = "LTV_STATS_ACTUAL"

    internal data class Paths(
        val corpus: Path,
        val actual: Path,
    )

    fun paths(environment: Map<String, String>): Paths? {
        val corpus = environment[CORPUS]
        val actual = environment[ACTUAL]
        if (corpus == null && actual == null) return null
        require(!corpus.isNullOrBlank() && !actual.isNullOrBlank()) {
            "LTV_STATS_CORPUS and LTV_STATS_ACTUAL must be set together"
        }
        val corpusPath = Path.of(corpus).toAbsolutePath().normalize()
        val actualPath = Path.of(actual).toAbsolutePath().normalize()
        require(Files.isDirectory(corpusPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(corpusPath)) {
            "invalid LTV_STATS_CORPUS"
        }
        require(
            Files.isDirectory(requireNotNull(actualPath.parent), NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(actualPath.parent),
        ) {
            "invalid LTV_STATS_ACTUAL parent"
        }
        require(!Files.exists(actualPath, NOFOLLOW_LINKS)) {
            "LTV_STATS_ACTUAL must name a new file"
        }
        return Paths(corpusPath.toRealPath(), actualPath)
    }

    fun run(paths: Paths) {
        val cases = manifest(paths.corpus)
        var errors = 0
        Files.newBufferedWriter(paths.actual, UTF_8, CREATE_NEW, WRITE).use { writer ->
            cases.forEach { case ->
                val record =
                    try {
                        val inputBytes = Files.readAllBytes(case.input)
                        require(sha256Hex(inputBytes) == case.sha256) { "INPUT_SHA256_MISMATCH:${case.id}" }
                        buildJsonObject {
                            put("id", case.id)
                            put("output", execute(Json.parseToJsonElement(inputBytes.decodeToString()).jsonObject))
                        }
                    } catch (failure: Throwable) {
                        errors++
                        buildJsonObject {
                            put("id", case.id)
                            put("error", failure.message ?: failure::class.qualifiedName.orEmpty())
                        }
                    }
                writer.write(canonicalJson(record).decodeToString())
                writer.newLine()
                writer.flush()
            }
        }
        check(errors == 0) { "STATISTICAL_VALIDATION_RUNNER_ERRORS=$errors" }
    }

    fun resource(input: String): JsonObject = execute(Json.parseToJsonElement(input).jsonObject)

    private data class Case(
        val id: String,
        val input: Path,
        val sha256: String,
    )

    private fun manifest(corpus: Path): List<Case> {
        val manifestPath = contained(corpus, "manifest.json")
        val manifest = Json.parseToJsonElement(Files.readString(manifestPath)).jsonObject
        require(manifest["schema_version"]?.jsonPrimitive?.content == "stats-cases.v1") {
            "INVALID_STATS_MANIFEST"
        }
        val ids = mutableSetOf<String>()
        return manifest.getValue("cases").jsonArray.map { element ->
            val entry = element.jsonObject
            val id = entry.getValue("id").jsonPrimitive.content
            require(Regex("[A-Za-z0-9_-]+").matches(id) && ids.add(id)) {
                "DUPLICATE_OR_INVALID_CASE_ID"
            }
            val inputs = entry.getValue("inputs").jsonObject
            val input = contained(corpus, inputs.getValue("path").jsonPrimitive.content)
            require(sha256Hex(Files.readAllBytes(input)) == inputs.getValue("sha256").jsonPrimitive.content) {
                "INPUT_SHA256_MISMATCH:$id"
            }
            Case(id, input, inputs.getValue("sha256").jsonPrimitive.content)
        }
    }

    private fun contained(
        corpus: Path,
        relative: String,
    ): Path {
        val candidate = corpus.resolve(relative).normalize()
        require(candidate.startsWith(corpus) && Files.isRegularFile(candidate, NOFOLLOW_LINKS) && !Files.isSymbolicLink(candidate)) {
            "INVALID_CORPUS_PATH"
        }
        return candidate
    }

    private fun execute(input: JsonObject): JsonObject =
        when (input.getValue("operation").jsonPrimitive.content) {
            "resource" -> resource(input)
            "analysis" -> analysis(input)
            "comparison" -> comparison(input)
            else -> error("UNKNOWN_STATISTICAL_OPERATION")
        }

    private fun resource(input: JsonObject): JsonObject {
        val resources = validateResourceSnapshot(ByteArrayInputStream(canonicalJson(input.getValue("resources"))))
        if (resources is ResourceValidation.Invalid) {
            return buildJsonObject {
                put("validation_status", "INVALID")
                put("validation_errors", JsonArray(resources.errors.map { JsonPrimitive(it.code) }))
            }
        }
        require(resources is ResourceValidation.Valid)
        require(resources.snapshot.windows.isNotEmpty()) { "RESOURCE_WINDOWS_REQUIRED" }
        val evaluation = evaluateResources(resources.snapshot, resources.snapshot.windows)
        return buildJsonObject {
            put("evidence", JsonArray(evaluation.evidence))
            put("findings", JsonArray(evaluation.findings))
            put("coverage_reasons", JsonArray(evaluation.coverageReasons.map(::JsonPrimitive)))
            put("resource_summaries", resourceSummaries(evaluation.evidence))
        }
    }

    private fun analysis(input: JsonObject): JsonObject {
        val run = input.getValue("run").jsonObject
        val load =
            run
                .getValue("load_jtl")
                .jsonPrimitive
                .content
                .encodeToByteArray()
        val resources = run["resources"]?.let(::validatedResource)
        val diagnostics = run["diagnostics"]?.let(::validatedDiagnostics)
        val policy = run["policy"]?.let(::validatedPolicy)
        val root = Files.createTempDirectory("ltv-stats-")
        try {
            val first = analyze(root, load, resources, diagnostics, policy)
            val replay = replay(root, first.input, resources, diagnostics, policy)
            return analysisOutput(
                first.result,
                first.identity,
                first.resultBytes.contentEquals(replay.resultBytes) &&
                    first.identityBytes.contentEquals(replay.identityBytes),
            )
        } finally {
            DataDirectory.deleteTree(root)
        }
    }

    private data class StoredRun(
        val input: AcceptedInput,
        val result: JsonObject,
        val identity: JsonObject,
        val resultBytes: ByteArray,
        val identityBytes: ByteArray,
    )

    private fun analyze(
        root: Path,
        load: ByteArray,
        resources: ResourceValidation.Valid?,
        diagnostics: DiagnosticValidation.Valid?,
        policy: PolicyValidation.Valid?,
    ): StoredRun =
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            val input =
                store.acceptInput(
                    ByteArrayInputStream(load),
                    "stats.jtl",
                    load.size.toLong(),
                )
            val outcome =
                AnalysisService(store, EngineConfig()).analyze(
                    AnalysisRequest(
                        input,
                        policy,
                        resources = resources,
                        diagnostics = diagnostics,
                    ),
                )
            stored(store, input, outcome.analysisId)
        }

    private fun replay(
        root: Path,
        input: AcceptedInput,
        resources: ResourceValidation.Valid?,
        diagnostics: DiagnosticValidation.Valid?,
        policy: PolicyValidation.Valid?,
    ): StoredRun =
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            val accepted = store.requireInput(input.runId)
            val outcome =
                AnalysisService(store, EngineConfig()).analyze(
                    AnalysisRequest(
                        accepted,
                        policy,
                        resources = resources,
                        diagnostics = diagnostics,
                    ),
                )
            stored(store, accepted, outcome.analysisId)
        }

    private fun stored(
        store: RunBundleStore,
        input: AcceptedInput,
        analysisId: String,
    ): StoredRun {
        val stored =
            requireNotNull(store.readAnalysis(input.runId, analysisId)) {
                "PERSISTED_ANALYSIS_MISSING"
            }
        val resultBytes = Files.readAllBytes(stored.path.resolve("analysis-result.json"))
        val identityBytes = Files.readAllBytes(stored.path.resolve("identity.json"))
        val documents =
            requireNotNull(store.readAnalysisDocuments(input.runId, analysisId)) {
                "PERSISTED_DOCUMENTS_MISSING"
            }
        require(documents.first == Json.parseToJsonElement(resultBytes.decodeToString()).jsonObject) {
            "PERSISTED_RESULT_MISMATCH"
        }
        require(documents.second == Json.parseToJsonElement(identityBytes.decodeToString()).jsonObject) {
            "PERSISTED_IDENTITY_MISMATCH"
        }
        return StoredRun(input, documents.first, documents.second, resultBytes, identityBytes)
    }

    private fun analysisOutput(
        result: JsonObject,
        identity: JsonObject,
        persistedEqual: Boolean,
    ): JsonObject {
        val evidence = result.getValue("evidence").jsonArray.map(JsonElement::jsonObject)
        return buildJsonObject {
            put("result", result)
            put("identity", identity)
            put("persisted_equal", persistedEqual)
            put("evidence", JsonArray(evidence))
            put("findings", result.getValue("findings"))
            put("resource_summaries", resourceSummaries(evidence))
            put("correlation_pairs", correlationPairs(evidence))
            put("anomaly_checks", anomalyChecks(evidence))
            listOf("load_summaries" to "window_metric_summary", "window_policies" to "window_policy_summary").forEach { (name, type) ->
                put(name, JsonObject(evidence.filter { it.string("type") == type }.associateBy { it.string("window_id") }))
            }
            put(
                "anomaly_episodes",
                JsonObject(
                    result
                        .getValue("findings")
                        .jsonArray
                        .map(JsonElement::jsonObject)
                        .filter { it.string("type") == "anomaly_episode" }
                        .groupBy { it.string("rule_id") }
                        .mapValues { (_, episodes) -> JsonArray(episodes) },
                ),
            )
            evidence.singleOrNull { it.string("type") == "diagnostic_summary" }?.let { put("diagnostic_summary", it) }
        }
    }

    private fun comparison(input: JsonObject): JsonObject {
        val conditionsConfirmed =
            input["conditions_confirmed"]?.let { value ->
                requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) {
                    "conditions_confirmed must be a boolean"
                }
            }
        val baseline =
            analysis(
                buildJsonObject {
                    put("run", input.getValue("baseline").jsonObject)
                },
            )
        val current =
            analysis(
                buildJsonObject {
                    put("run", input.getValue("current").jsonObject)
                },
            )
        val baselineResult = baseline.getValue("result").jsonObject
        val baselineIdentity = baseline.getValue("identity").jsonObject
        val currentResult = current.getValue("result").jsonObject
        val currentIdentity = current.getValue("identity").jsonObject
        val baselineReference =
            reference(
                baselineResult,
                baselineIdentity,
            )
        val currentReference =
            reference(
                currentResult,
                currentIdentity,
            )
        val compared =
            compareAnalyses(
                manualBaselineSelection("statistical-validation", baselineReference),
                currentReference,
                baselineResult,
                baselineIdentity,
                currentResult,
                currentIdentity,
                WindowComparisonRequest(
                    input.getValue("baseline_window_id").jsonPrimitive.content,
                    input.getValue("current_window_id").jsonPrimitive.content,
                ),
                conditionsConfirmed = conditionsConfirmed,
            )
        return buildJsonObject {
            put("baseline", baseline)
            put("current", current)
            put("comparison", compared)
            put(
                "persisted_equal",
                baseline
                    .getValue("persisted_equal")
                    .jsonPrimitive
                    .content
                    .toBooleanStrict() &&
                    current
                        .getValue("persisted_equal")
                        .jsonPrimitive
                        .content
                        .toBooleanStrict(),
            )
        }
    }

    private fun reference(
        result: JsonObject,
        identity: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("run_id", result.getValue("run_id"))
            put("analysis_id", sha256Hex(canonicalJson(identity)))
        }

    private fun resourceSummaries(evidence: List<JsonObject>): JsonObject {
        val windows = linkedMapOf<String, MutableMap<String, JsonObject>>()
        evidence.filter { it.string("type") == "resource_summary" }.forEach { summary ->
            val bySeries = windows.getOrPut(summary.string("window_id")) { linkedMapOf() }
            require(bySeries.put(summary.string("series_id"), summary) == null) {
                "DUPLICATE_RESOURCE_SUMMARY"
            }
        }
        return JsonObject(windows.mapValues { (_, summaries) -> JsonObject(summaries) })
    }

    private fun correlationPairs(evidence: List<JsonObject>): JsonObject {
        val windows = linkedMapOf<String, MutableMap<String, JsonObject>>()
        evidence.filter { it.string("type") == "correlation_pair" }.forEach { pair ->
            val byPair = windows.getOrPut(pair.string("window_id")) { linkedMapOf() }
            require(byPair.put(pair.string("pair_id"), pair) == null) {
                "DUPLICATE_CORRELATION_PAIR"
            }
        }
        return JsonObject(windows.mapValues { (_, pairs) -> JsonObject(pairs) })
    }

    private fun anomalyChecks(evidence: List<JsonObject>): JsonObject {
        val checks = linkedMapOf<String, JsonObject>()
        evidence.filter { it.string("type") == "anomaly_check" }.forEach { check ->
            require(checks.put(check.string("rule_id"), check) == null) {
                "DUPLICATE_ANOMALY_CHECK"
            }
        }
        return JsonObject(checks)
    }

    private fun validatedResource(element: JsonElement): ResourceValidation.Valid =
        validateResourceSnapshot(ByteArrayInputStream(canonicalJson(element))).let { validation ->
            validation as? ResourceValidation.Valid
                ?: error(
                    "INVALID_RESOURCE_SNAPSHOT:" +
                        (validation as ResourceValidation.Invalid).errors.joinToString { it.code },
                )
        }

    private fun validatedDiagnostics(element: JsonElement): DiagnosticValidation.Valid =
        validateDiagnosticPlan(ByteArrayInputStream(canonicalJson(element))).let { validation ->
            validation as? DiagnosticValidation.Valid
                ?: error(
                    "INVALID_DIAGNOSTIC_PLAN:" +
                        (validation as DiagnosticValidation.Invalid).errors.joinToString { it.code },
                )
        }

    private fun validatedPolicy(element: JsonElement): PolicyValidation.Valid =
        validatePolicy(ByteArrayInputStream(canonicalJson(element))).let { validation ->
            validation as? PolicyValidation.Valid
                ?: error(
                    "INVALID_POLICY:" +
                        (validation as PolicyValidation.Invalid).errors.joinToString { it.code },
                )
        }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}

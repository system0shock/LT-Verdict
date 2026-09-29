package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.detectSource
import io.ltverdict.ingest.parseInput
import io.ltverdict.metrics.MetricWindow
import io.ltverdict.metrics.MetricsAccumulator
import io.ltverdict.metrics.UtcLoadMetricsAccumulator
import io.ltverdict.metrics.WindowMetricsAccumulator
import io.ltverdict.storage.AcceptedInput
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

class UsefulnessValidationTest {
    @Test
    fun `DEBUG resource projection keeps window and series summaries`() {
        val output =
            UsefulnessValidationRunner.resource(
                """
                {"operation":"resource","resources":{
                  "schema_version":"resource-snapshot.v1",
                  "load_input_sha256":"${"0".repeat(64)}",
                  "start_epoch_ms":0,"step_ms":1000,"point_count":2,
                  "series":[{"id":"cpu","metric":"cpu","unit":"ratio","entity":"debug",
                  "role":"system","aggregation":"interval_mean","values":[1,2]}],
                  "windows":[{"id":"debug","from_epoch_ms":0,"to_epoch_ms":2000}]
                }}
                """.trimIndent(),
            )

        assertEquals(
            "2",
            output
                .getValue("resource_summaries")
                .jsonObject
                .getValue("debug")
                .jsonObject
                .getValue("cpu")
                .jsonObject
                .getValue("observed_cells")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `DEBUG seed zero projections match persisted runner semantics`() {
        val inputPath = System.getenv("LTV_USEFULNESS_DEBUG_SEED0")?.let(Path::of)
        assumeTrue(inputPath != null, "set LTV_USEFULNESS_DEBUG_SEED0 to a generated seed-0 input")
        val input = Files.readString(requireNotNull(inputPath))

        assertEquals(
            persistedProjection(
                Json.parseToJsonElement(input).jsonObject,
                StatisticalValidationRunner.resource(input),
            ),
            UsefulnessValidationRunner.resource(input),
        )
    }

    @Test
    fun `frozen usefulness corpus runner is opt in`() {
        val paths = UsefulnessValidationRunner.paths(System.getenv())
        assumeTrue(paths != null, "set LTV_USEFULNESS_CORPUS and LTV_USEFULNESS_ACTUAL")
        UsefulnessValidationRunner.run(requireNotNull(paths))
    }
}

private object UsefulnessValidationRunner {
    private const val CORPUS = "LTV_USEFULNESS_CORPUS"
    private const val ACTUAL = "LTV_USEFULNESS_ACTUAL"

    data class Paths(
        val corpus: Path,
        val actual: Path,
    )

    fun paths(environment: Map<String, String>): Paths? {
        val corpus = environment[CORPUS]
        val actual = environment[ACTUAL]
        if (corpus == null && actual == null) return null
        require(!corpus.isNullOrBlank() && !actual.isNullOrBlank()) {
            "$CORPUS and $ACTUAL must be set together"
        }
        val corpusPath = Path.of(corpus).toAbsolutePath().normalize()
        val actualPath = Path.of(actual).toAbsolutePath().normalize()
        require(Files.isDirectory(corpusPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(corpusPath)) {
            "invalid $CORPUS"
        }
        require(
            Files.isDirectory(requireNotNull(actualPath.parent), NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(actualPath.parent),
        ) { "invalid $ACTUAL parent" }
        require(!Files.exists(actualPath, NOFOLLOW_LINKS)) { "$ACTUAL must name a new file" }
        return Paths(corpusPath.toRealPath(), actualPath)
    }

    fun run(paths: Paths) {
        var errors = 0
        Files.newBufferedWriter(paths.actual, UTF_8, CREATE_NEW, WRITE).use { writer ->
            usefulnessCases(paths.corpus).forEach { case ->
                val record =
                    try {
                        val bytes = Files.readAllBytes(case.input)
                        require(sha256Hex(bytes) == case.sha256) { "INPUT_SHA256_MISMATCH:${case.id}" }
                        buildJsonObject {
                            put("id", case.id)
                            put("output", resource(bytes.decodeToString()))
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
            }
        }
        check(errors == 0) { "USEFULNESS_VALIDATION_RUNNER_ERRORS=$errors" }
    }

    fun resource(input: String): JsonObject = execute(Json.parseToJsonElement(input).jsonObject)

    private data class Case(
        val id: String,
        val input: Path,
        val sha256: String,
    )

    private data class CoreRun(
        val result: JsonObject,
        val identity: JsonObject,
    )

    private fun usefulnessCases(corpus: Path): List<Case> {
        val manifestText = Files.readString(contained(corpus, "manifest.json"))
        val manifest = Json.parseToJsonElement(manifestText).jsonObject
        require(manifest["schema_version"]?.jsonPrimitive?.content == "stats-cases.v1") { "INVALID_STATS_MANIFEST" }
        val ids = mutableSetOf<String>()
        return manifest.getValue("cases").jsonArray.map { element ->
            val entry = element.jsonObject
            val id = entry.getValue("id").jsonPrimitive.content
            require(Regex("[A-Za-z0-9_-]+").matches(id) && ids.add(id)) { "DUPLICATE_OR_INVALID_CASE_ID" }
            val source = entry.getValue("inputs").jsonObject
            Case(
                id,
                contained(corpus, source.getValue("path").jsonPrimitive.content),
                source.getValue("sha256").jsonPrimitive.content,
            )
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
            "resource" -> resourceOnly(input)
            "analysis" -> projection(analyze(input.getValue("run").jsonObject).result)
            "comparison" -> comparison(input)
            else -> error("UNKNOWN_STATISTICAL_OPERATION")
        }

    private fun resourceOnly(input: JsonObject): JsonObject {
        val resources = validatedResource(input.getValue("resources"))
        require(resources.snapshot.windows.isNotEmpty()) { "RESOURCE_WINDOWS_REQUIRED" }
        val evaluation = evaluateResources(resources.snapshot, resources.snapshot.windows)
        return buildJsonObject {
            put("resource_summaries", resourceSummaries(evaluation.evidence))
            put("findings", JsonArray(evaluation.findings))
        }
    }

    private fun analyze(run: JsonObject): CoreRun {
        val load =
            run
                .getValue("load_jtl")
                .jsonPrimitive
                .content
                .encodeToByteArray()
        require("policy" !in run) { "USEFULNESS_POLICY_UNSUPPORTED" }
        val resources = validatedResource(run.getValue("resources"))
        val diagnostics = run["diagnostics"]?.let(::validatedDiagnostics)
        if (diagnostics != null) {
            validateDiagnosticBinding(diagnostics, resources)
                .firstOrNull()
                ?.let { throw IllegalArgumentException(it.code) }
        }
        return withTemporaryInput(load) { input ->
            var start: Long? = null
            var end: Long? = null
            val first =
                parseInput(input, { sample ->
                    start = minOf(start ?: sample.startedAtEpochMillis, sample.startedAtEpochMillis)
                    end = maxOf(end ?: sample.endedAtEpochMillis, sample.endedAtEpochMillis)
                })
            require(first.validity == RunValidity.VALID) { "INVALID_LOAD_INPUT" }
            val runStart = requireNotNull(start) { "PARSER_PASS_MISMATCH" }
            val runEnd = requireNotNull(end) { "PARSER_PASS_MISMATCH" }
            val windows = resolveResourceWindows(resources.snapshot, input.sha256, runStart, runEnd)
            diagnostics?.let {
                validateDiagnosticResolvedWindows(it, windows).firstOrNull()?.let { failure ->
                    throw IllegalArgumentException(failure.code)
                }
            }
            val config = EngineConfig().metrics
            val metrics = MetricsAccumulator(runStart, runEnd, config)
            val windowMetrics = WindowMetricsAccumulator(windows.map(::metricWindow), emptySet(), config)
            val resourceWindowHistograms = windows.size
            val diagnosticMetrics =
                diagnostics?.let { plan ->
                    val selected =
                        plan.plan.pairs
                            .flatMap(DiagnosticPairV1::windowIds)
                            .toSet() +
                            plan.plan.anomalies.flatMap { listOf(it.referenceWindowId, it.windowId) }
                    UtcLoadMetricsAccumulator(
                        windows.filter { it.id in selected }.map(::metricWindow),
                        resources.snapshot.stepMillis,
                        resourceWindowHistograms,
                        config,
                    )
                }
            val second =
                parseInput(input, { sample ->
                    metrics.record(sample)
                    windowMetrics?.record(sample)
                    diagnosticMetrics?.record(sample)
                })
            require(second == first) { "PARSER_PASS_MISMATCH" }
            val resourceEvaluation =
                evaluateResources(resources.snapshot, windows).let { evaluated ->
                    evaluated.copy(
                        evidence =
                            listOf(
                                resourceBindingEvidence(resources.snapshot, windows, runStart, runEnd),
                            ) + evaluated.evidence,
                    )
                }
            var evaluation =
                evaluateSharedWindowPolicy(
                    null,
                    first.validity,
                    metrics.finish(),
                    windowMetrics.finish(),
                    resourceEvaluation,
                    windows,
                    first.diagnostics,
                )
            diagnostics?.let { plan ->
                val diagnostic =
                    evaluateDiagnostics(
                        plan,
                        resources,
                        windows,
                        requireNotNull(diagnosticMetrics).finish(),
                        windowMetrics.finish(),
                    )
                evaluation =
                    evaluation.copy(
                        findings = evaluation.findings + diagnostic.findings,
                        evidence = evaluation.evidence + diagnostic.evidence,
                    )
            }
            val resultBytes = analysisResult(input.runId, first.validity, evaluation)
            val result = Json.parseToJsonElement(resultBytes.decodeToString()).jsonObject
            val identityBytes = analysisIdentity(input, null, EngineConfig(), resources, diagnostics)
            val identity = Json.parseToJsonElement(identityBytes.decodeToString()).jsonObject
            CoreRun(result, identity)
        }
    }

    private fun comparison(input: JsonObject): JsonObject {
        val conditionsConfirmed =
            input["conditions_confirmed"]?.let { value ->
                requireNotNull((value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull) {
                    "conditions_confirmed must be a boolean"
                }
            }
        val baseline = analyze(input.getValue("baseline").jsonObject)
        val current = analyze(input.getValue("current").jsonObject)
        val baselineReference = reference(baseline)
        val currentReference = reference(current)
        val compared =
            compareAnalyses(
                manualBaselineSelection("usefulness-validation", baselineReference),
                currentReference,
                baseline.result,
                baseline.identity,
                current.result,
                current.identity,
                WindowComparisonRequest(
                    input.getValue("baseline_window_id").jsonPrimitive.content,
                    input.getValue("current_window_id").jsonPrimitive.content,
                ),
                conditionsConfirmed,
            )
        return buildJsonObject {
            put("baseline", projection(baseline.result))
            put("current", projection(current.result))
            put(
                "comparison",
                buildJsonObject {
                    put("comparability", compared.getValue("comparability"))
                    put("metrics", compared.getValue("metrics"))
                    compared["window_comparison"]?.let { put("window_comparison", it) }
                },
            )
        }
    }

    private fun reference(run: CoreRun): JsonObject =
        buildJsonObject {
            put("run_id", run.result.getValue("run_id"))
            put("analysis_id", sha256Hex(canonicalJson(run.identity)))
        }

    private fun projection(result: JsonObject): JsonObject {
        val evidence = result.getValue("evidence").jsonArray.map(JsonElement::jsonObject)
        return buildJsonObject {
            put("resource_summaries", resourceSummaries(evidence))
            put("correlation_pairs", correlationPairs(evidence))
            put("anomaly_checks", anomalyChecks(evidence))
            put("findings", result.getValue("findings"))
        }
    }

    private fun validatedResource(element: JsonElement): ResourceValidation.Valid =
        validateResourceSnapshot(ByteArrayInputStream(canonicalJson(element))) as? ResourceValidation.Valid
            ?: error("INVALID_RESOURCE_SNAPSHOT")

    private fun validatedDiagnostics(element: JsonElement): DiagnosticValidation.Valid =
        validateDiagnosticPlan(ByteArrayInputStream(canonicalJson(element))) as? DiagnosticValidation.Valid
            ?: error("INVALID_DIAGNOSTIC_PLAN")

    private fun metricWindow(window: ResourceWindowV1) = MetricWindow(window.id, window.fromEpochMillis, window.toEpochMillis)

    private fun <T> withTemporaryInput(
        bytes: ByteArray,
        block: (AcceptedInput) -> T,
    ): T {
        val path = Files.createTempFile("ltv-usefulness-", ".jtl")
        try {
            Files.write(path, bytes)
            val sourceType = detectSource(path)
            return block(
                AcceptedInput(
                    "${sourceType.wireName}-${sha256Hex(bytes)}",
                    sourceType,
                    sha256Hex(bytes),
                    bytes.size.toLong(),
                    "usefulness.jtl",
                    path,
                ),
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun resourceSummaries(evidence: List<JsonObject>): JsonObject =
        groupedEvidence(evidence, "resource_summary", "window_id", "series_id")

    private fun correlationPairs(evidence: List<JsonObject>): JsonObject =
        groupedEvidence(evidence, "correlation_pair", "window_id", "pair_id")

    private fun anomalyChecks(evidence: List<JsonObject>): JsonObject {
        val checks = linkedMapOf<String, JsonObject>()
        evidence.filter { it.string("type") == "anomaly_check" }.forEach { check ->
            require(checks.put(check.string("rule_id"), check) == null) { "DUPLICATE_ANOMALY_CHECK" }
        }
        return JsonObject(checks)
    }

    private fun groupedEvidence(
        evidence: List<JsonObject>,
        type: String,
        outer: String,
        inner: String,
    ): JsonObject {
        val groups = linkedMapOf<String, MutableMap<String, JsonObject>>()
        evidence.filter { it.string("type") == type }.forEach { entry ->
            val nested = groups.getOrPut(entry.string(outer)) { linkedMapOf() }
            require(nested.put(entry.string(inner), entry) == null) { "DUPLICATE_$type" }
        }
        return JsonObject(groups.mapValues { JsonObject(it.value) })
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
}

private fun persistedProjection(
    input: JsonObject,
    output: JsonObject,
): JsonObject =
    when (input.getValue("operation").jsonPrimitive.content) {
        "resource" ->
            buildJsonObject {
                put("resource_summaries", output.getValue("resource_summaries"))
                put("findings", output.getValue("findings"))
            }

        "analysis" -> persistedAnalysisProjection(output)
        "comparison" ->
            buildJsonObject {
                put("baseline", persistedAnalysisProjection(output.getValue("baseline").jsonObject))
                put("current", persistedAnalysisProjection(output.getValue("current").jsonObject))
                val comparison = output.getValue("comparison").jsonObject
                put(
                    "comparison",
                    buildJsonObject {
                        put("comparability", comparison.getValue("comparability"))
                        put("metrics", comparison.getValue("metrics"))
                        comparison["window_comparison"]?.let { put("window_comparison", it) }
                    },
                )
            }

        else -> error("UNKNOWN_STATISTICAL_OPERATION")
    }

private fun persistedAnalysisProjection(output: JsonObject): JsonObject =
    buildJsonObject {
        put("resource_summaries", output.getValue("resource_summaries"))
        put("correlation_pairs", output.getValue("correlation_pairs"))
        put("anomaly_checks", output.getValue("anomaly_checks"))
        put("findings", output.getValue("findings"))
    }

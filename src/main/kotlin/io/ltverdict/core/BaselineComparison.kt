package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs

internal fun manualBaselineSelection(
    series: String,
    reference: JsonObject,
): JsonObject =
    Selection(
        series = requireSeries(series),
        mode = Mode.MANUAL,
        reference = reference.toReference(),
        algorithm = null,
        candidates = listOf(reference.toReference()),
        scores = emptyList(),
    ).toJson()

internal fun statisticalBaselineSelection(
    series: String,
    references: List<JsonObject>,
    results: List<JsonObject>,
    identities: List<JsonObject>,
): JsonObject {
    require(references.size in MIN_CANDIDATES..MAX_CANDIDATES && results.size == references.size && identities.size == references.size) {
        "BASELINE_CANDIDATE_COUNT"
    }
    val candidates =
        references.indices.map { index ->
            val reference = references[index].toReference()
            val result = results[index]
            require(result.stringOrNull("run_validity") == "VALID") { "BASELINE_CANDIDATE_INVALID" }
            require(result.objectOrNull("analysis_coverage")?.stringOrNull("status") == "COMPLETE") {
                "BASELINE_CANDIDATE_INCOMPLETE"
            }
            Candidate(
                reference,
                listOf(
                    requireNotNull(result.metricValue(Metric.P95)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                    requireNotNull(result.metricValue(Metric.THROUGHPUT)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                    requireNotNull(result.metricValue(Metric.ERROR_RATE)) { "BASELINE_CANDIDATE_MISSING_METRIC" },
                ),
                requireNotNull(semanticKey(result, identities[index])) { "BASELINE_CANDIDATE_INVALID_IDENTITY" },
            )
        }
    require(candidates.map { it.reference.runId }.toSet().size == candidates.size) { "BASELINE_DUPLICATE_RUN" }
    require(candidates.map { it.semantics }.distinct().size == 1) { "BASELINE_MIXED_SEMANTICS" }

    val center2 = candidates.size + 1
    // ponytail: quadratic ranks are bounded to 20 candidates; sort columns if the cap grows.
    val scores =
        candidates.associate { candidate ->
            candidate.reference to
                candidate.metrics.indices.sumOf { metricIndex ->
                    val value = candidate.metrics[metricIndex]
                    val values = candidates.map { it.metrics[metricIndex] }
                    val rank2 = 2 * values.count { it < value } + values.count { it.compareTo(value) == 0 } + 1
                    abs(rank2 - center2)
                }
        }
    val sortedReferences = candidates.map(Candidate::reference).sorted()
    val sortedScores = sortedReferences.map { Score(it, scores.getValue(it)) }
    val winner = sortedScores.minWith(compareBy<Score>({ it.score }, { it.reference }))
    return Selection(
        series = requireSeries(series),
        mode = Mode.STATISTICAL,
        reference = winner.reference,
        algorithm = ALGORITHM,
        candidates = sortedReferences,
        scores = sortedScores,
    ).toJson()
}

internal fun compareAnalyses(
    selection: JsonObject,
    currentReference: JsonObject,
    baselineResult: JsonObject,
    baselineIdentity: JsonObject,
    currentResult: JsonObject,
    currentIdentity: JsonObject,
): JsonObject {
    val parsedSelection = selection.toSelection()
    val current = currentReference.toReference()
    val compatible = semanticKey(baselineResult, baselineIdentity)?.let { it == semanticKey(currentResult, currentIdentity) } == true
    return buildJsonObject {
        put("baseline", parsedSelection.toJson())
        put("current", current.toJson())
        put(
            "comparability",
            if (parsedSelection.mode == Mode.STATISTICAL && current in parsedSelection.candidates) {
                "USER_CONFIRMED"
            } else {
                "UNCONFIRMED"
            },
        )
        put(
            "metrics",
            buildJsonArray {
                Metric.entries.forEach { metric ->
                    add(metricComparison(metric, currentResult.metricValue(metric), baselineResult.metricValue(metric), compatible))
                }
            },
        )
    }
}

internal fun validateBaselineSelection(selection: JsonObject): JsonObject = selection.toSelection().toJson()

private fun metricComparison(
    metric: Metric,
    current: Rational?,
    baseline: Rational?,
    compatible: Boolean,
): JsonObject {
    val reason =
        when {
            current == null || baseline == null -> "MISSING_METRIC"
            !compatible -> "INCOMPATIBLE_METRIC_DEFINITION"
            else -> null
        }
    val delta = if (reason == null) checkNotNull(current) - checkNotNull(baseline) else null
    val zeroBaseline = reason == null && checkNotNull(baseline).isZero()
    val percent = if (reason == null && !zeroBaseline) checkNotNull(delta) / checkNotNull(baseline) * HUNDRED else null
    return buildJsonObject {
        put("metric", metric.wireName)
        put("unit", metric.unit)
        put("current", current?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("baseline", baseline?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("delta", delta?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("delta_percent", percent?.format()?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", reason?.let(::JsonPrimitive) ?: JsonNull)
        put("percent_reason", (reason ?: if (zeroBaseline) "ZERO_BASELINE" else null)?.let(::JsonPrimitive) ?: JsonNull)
    }
}

private fun JsonObject.metricValue(metric: Metric): Rational? {
    val overall =
        (this["evidence"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.singleOrNull { evidence ->
                evidence.stringOrNull("type") == "metric_summary" &&
                    evidence.objectOrNull("scope")?.stringOrNull("kind") == "overall"
            } ?: return null
    return when (metric) {
        Metric.P95 -> overall.objectOrNull("latency_ms")?.nonNegativeInteger("p95")
        Metric.P99 -> overall.objectOrNull("latency_ms")?.nonNegativeInteger("p99")
        Metric.THROUGHPUT -> overall.ratioOrNull("throughput_rps")
        Metric.ERROR_RATE -> overall.ratioOrNull("error_rate_ratio")
    }
}

private fun semanticKey(
    result: JsonObject,
    identity: JsonObject,
): List<JsonElement>? {
    val values = mutableListOf(result["analysis_mode"] ?: return null)
    SEMANTIC_FIELDS.forEach { field -> values += identity[field] ?: return null }
    return values
}

private fun JsonObject.ratioOrNull(name: String): Rational? {
    val ratio = this[name] as? JsonObject ?: return null
    if (ratio.keys != RATIO_FIELDS) return null
    val numerator = ratio.nonNegativeBigInteger("numerator") ?: return null
    val denominator = ratio.nonNegativeBigInteger("denominator")?.takeUnless(BigInteger::equalsZero) ?: return null
    return Rational(numerator, denominator)
}

private fun JsonObject.nonNegativeInteger(name: String): Rational? = nonNegativeBigInteger(name)?.let { Rational(it, BigInteger.ONE) }

private fun JsonObject.nonNegativeBigInteger(name: String): BigInteger? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBigIntegerOrNull()?.takeIf { it.signum() >= 0 }
}

private fun JsonObject.toReference(): Reference {
    require(keys == REFERENCE_FIELDS) { "INVALID_BASELINE_REFERENCE" }
    val runId = requiredString("run_id")
    val analysisId = requiredString("analysis_id")
    require(RUN_ID.matches(runId) && SHA256.matches(analysisId)) { "INVALID_BASELINE_REFERENCE" }
    return Reference(runId, analysisId)
}

private fun JsonObject.toSelection(): Selection {
    require(keys == SELECTION_FIELDS && requiredString("schema_version") == SCHEMA_VERSION) { "INVALID_BASELINE_SELECTION" }
    val mode = Mode.entries.singleOrNull { it.wireName == requiredString("mode") } ?: error("INVALID_BASELINE_SELECTION")
    val reference = objectOrNull("reference")?.toReference() ?: error("INVALID_BASELINE_SELECTION")
    val candidates =
        (this["candidates"] as? JsonArray)?.map { (it as? JsonObject)?.toReference() ?: error("INVALID_BASELINE_SELECTION") }
            ?: error("INVALID_BASELINE_SELECTION")
    val scores =
        (this["scores"] as? JsonArray)?.map { element ->
            val score = element as? JsonObject ?: error("INVALID_BASELINE_SELECTION")
            require(score.keys == SCORE_FIELDS) { "INVALID_BASELINE_SELECTION" }
            Score(
                score.objectOrNull("reference")?.toReference() ?: error("INVALID_BASELINE_SELECTION"),
                score["score"]
                    ?.jsonPrimitive
                    ?.takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                    ?.takeIf { it >= 0 }
                    ?: error("INVALID_BASELINE_SELECTION"),
            )
        } ?: error("INVALID_BASELINE_SELECTION")
    val algorithm =
        when (val value = getValue("algorithm")) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(value.isString) { "INVALID_BASELINE_SELECTION" }
                value.content
            }

            else -> throw IllegalArgumentException("INVALID_BASELINE_SELECTION")
        }
    val selection = Selection(requireSeries(requiredString("series")), mode, reference, algorithm, candidates, scores)
    when (mode) {
        Mode.MANUAL ->
            require(algorithm == null && candidates == listOf(reference) && scores.isEmpty()) { "INVALID_BASELINE_SELECTION" }

        Mode.STATISTICAL -> {
            require(algorithm == ALGORITHM && candidates.size in MIN_CANDIDATES..MAX_CANDIDATES) { "INVALID_BASELINE_SELECTION" }
            require(candidates == candidates.sorted() && candidates.map(Reference::runId).toSet().size == candidates.size) {
                "INVALID_BASELINE_SELECTION"
            }
            require(scores.map(Score::reference) == candidates) { "INVALID_BASELINE_SELECTION" }
            require(reference == scores.minWith(compareBy<Score>({ it.score }, { it.reference })).reference) {
                "INVALID_BASELINE_SELECTION"
            }
        }
    }
    return selection
}

private fun Selection.toJson(): JsonObject =
    buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("series", series)
        put("mode", mode.wireName)
        put("reference", reference.toJson())
        put("algorithm", algorithm?.let(::JsonPrimitive) ?: JsonNull)
        put("candidates", buildJsonArray { candidates.forEach { add(it.toJson()) } })
        put(
            "scores",
            buildJsonArray {
                scores.forEach { score ->
                    add(
                        buildJsonObject {
                            put("reference", score.reference.toJson())
                            put("score", score.score)
                        },
                    )
                }
            },
        )
    }

private fun Reference.toJson(): JsonObject =
    buildJsonObject {
        put("run_id", runId)
        put("analysis_id", analysisId)
    }

private fun requireSeries(series: String): String {
    require(series.isNotEmpty() && series.encodeToByteArray().size <= MAX_SERIES_BYTES) { "INVALID_BASELINE_SERIES" }
    return series
}

private fun JsonObject.requiredString(name: String): String {
    val value = this[name] as? JsonPrimitive
    require(value != null && value.isString) { "INVALID_BASELINE_SELECTION" }
    return value.content
}

private fun JsonObject.stringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private data class Reference(
    val runId: String,
    val analysisId: String,
) : Comparable<Reference> {
    override fun compareTo(other: Reference): Int = compareValuesBy(this, other, Reference::runId, Reference::analysisId)
}

private data class Candidate(
    val reference: Reference,
    val metrics: List<Rational>,
    val semantics: List<JsonElement>,
)

private data class Score(
    val reference: Reference,
    val score: Int,
)

private data class Selection(
    val series: String,
    val mode: Mode,
    val reference: Reference,
    val algorithm: String?,
    val candidates: List<Reference>,
    val scores: List<Score>,
)

private data class Rational(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<Rational> {
    init {
        require(denominator.signum() > 0)
    }

    override fun compareTo(other: Rational): Int = (numerator * other.denominator).compareTo(other.numerator * denominator)

    operator fun minus(other: Rational) =
        Rational(
            numerator * other.denominator - other.numerator * denominator,
            denominator * other.denominator,
        )

    operator fun div(other: Rational) = Rational(numerator * other.denominator, denominator * other.numerator)

    operator fun times(value: BigInteger) = Rational(numerator * value, denominator)

    fun isZero(): Boolean = numerator.signum() == 0

    fun format(): String =
        BigDecimal(numerator)
            .divide(BigDecimal(denominator), DISPLAY_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
}

private enum class Mode(
    val wireName: String,
) {
    MANUAL("manual"),
    STATISTICAL("statistical"),
}

private enum class Metric(
    val wireName: String,
    val unit: String,
) {
    P95("response_time_p95_ms", "ms"),
    P99("response_time_p99_ms", "ms"),
    THROUGHPUT("throughput_rps", "requests/second"),
    ERROR_RATE("error_rate_ratio", "ratio"),
}

private fun BigInteger.equalsZero(): Boolean = signum() == 0

private const val SCHEMA_VERSION = "local-baseline.v1"
private const val ALGORITHM = "median-rank-v1"
private const val MIN_CANDIDATES = 3
private const val MAX_CANDIDATES = 20
private const val MAX_SERIES_BYTES = 128
private const val DISPLAY_SCALE = 6
private val HUNDRED = BigInteger.valueOf(100)
private val REFERENCE_FIELDS = setOf("run_id", "analysis_id")
private val SCORE_FIELDS = setOf("reference", "score")
private val SELECTION_FIELDS = setOf("schema_version", "series", "mode", "reference", "algorithm", "candidates", "scores")
private val RATIO_FIELDS = setOf("numerator", "denominator")
private val SEMANTIC_FIELDS =
    listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")
private val SHA256 = Regex("[0-9a-f]{64}")
private val RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

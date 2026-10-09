package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal

// The rules of the baseline API (ADR 0017, ADR 0019): what a request may say, which analyses may be a baseline, which slot an
// analysis belongs to. They take documents and strings, never the storage, so the routes read first and the rules decide after.

internal fun JsonObject.baselineString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ruleMalformed("$name must be a string")

internal fun JsonObject.baselineReference(): JsonObject {
    if (keys != setOf("run_id", "analysis_id") ||
        !BASELINE_RUN_ID.matches(baselineString("run_id")) ||
        !Regex("[0-9a-f]{64}").matches(baselineString("analysis_id"))
    ) {
        ruleMalformed("Baseline reference is invalid")
    }
    return this
}

internal fun baselineIneligible(
    code: String,
    verdict: String? = null,
): Nothing =
    ruleUnprocessable(
        code,
        "Baseline candidate is unavailable: $code" + (verdict?.let { " (policy_verdict=$it)" } ?: ""),
    )

// The same rule for both modes, applied in request order before any statistic is computed.
internal fun requireBaselineEligible(results: List<JsonObject>) {
    results.forEach { result ->
        val code = baselineCandidateRejection(result) ?: return@forEach
        baselineIneligible(code, if (code == "BASELINE_CANDIDATE_NOT_PASS") result.knownVerdict() else null)
    }
}

private fun JsonObject.knownVerdict(): String =
    (this["policy_verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KNOWN_VERDICTS } ?: "UNKNOWN"

private val KNOWN_VERDICTS = setOf("PASS", "FAIL", "NO_POLICY", "NO_VERDICT")

/** What a selection request asks for, checked as far as the request alone allows (before any analysis is read). */
internal class BaselineSelectionPlan(
    val manual: Boolean,
    val series: String,
    val references: List<JsonObject>,
)

internal fun planBaselineSelection(request: JsonObject): BaselineSelectionPlan {
    val mode = request.baselineString("mode")
    // The same rule as the `series` query, so that every stored slot can be addressed again.
    val series =
        releaseTextField(request.baselineString("series"), "series", MAX_RELEASE_TEXT_BYTES)
            ?: ruleMalformed("Comparison series must contain 1–128 UTF-8 bytes")
    return when (mode) {
        "manual" -> {
            if (request.keys != setOf("mode", "series", "reference")) ruleMalformed("Manual baseline fields are invalid")
            val reference = (request["reference"] as? JsonObject)?.baselineReference() ?: ruleMalformed("Baseline reference is invalid")
            BaselineSelectionPlan(true, series, listOf(reference))
        }
        "statistical" -> {
            if (request.keys != setOf("mode", "series", "candidates", "comparable")) {
                ruleMalformed("Statistical baseline fields are invalid")
            }
            val comparable =
                (request["comparable"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: ruleMalformed("comparable must be a boolean")
            if (!comparable) baselineIneligible("BASELINE_COMPARABILITY_UNCONFIRMED")
            val values = request["candidates"] as? JsonArray ?: ruleMalformed("candidates must be an array")
            if (values.size !in 3..20) baselineIneligible("BASELINE_CANDIDATE_COUNT")
            val references = values.map { (it as? JsonObject)?.baselineReference() ?: ruleMalformed("Candidate reference is invalid") }
            if (references.map { it.getValue("run_id") }.toSet().size != references.size) baselineIneligible("BASELINE_DUPLICATE_RUN")
            BaselineSelectionPlan(false, series, references)
        }
        else -> ruleMalformed("mode must be manual or statistical")
    }
}

/** The selection document, from the results and identities of the plan's references (read in the same order). */
internal fun selectBaseline(
    plan: BaselineSelectionPlan,
    results: List<JsonObject>,
    identities: List<JsonObject>,
): JsonObject {
    requireBaselineEligible(results)
    if (plan.manual) return manualBaselineSelection(plan.series, plan.references.single())
    return try {
        statisticalBaselineSelection(plan.series, plan.references, results, identities)
    } catch (failure: IllegalArgumentException) {
        baselineIneligible(failure.message ?: "BASELINE_CANDIDATE_INVALID")
    }
}

internal class BaselineScope(
    val series: String?,
    val arm: String?,
)

internal fun JsonObject.baselineArm(): String? =
    when (val arm = this["resource_arm"]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (arm.isString) arm.content else corruptBaseline()
        else -> corruptBaseline()
    }

private fun corruptBaseline(): Nothing = ruleCorrupt("CORRUPT_BASELINE", "Baseline or referenced analysis is corrupt")

/**
 * The baseline slot of an analysis (ADR 0019, section 7): the series of its release, else the `series` query, else the legacy
 * file; the arm comes from its identity. A query that contradicts the release series is refused.
 */
internal fun baselineScope(
    explicit: String?,
    registered: String?,
    identity: JsonObject,
): BaselineScope {
    if (explicit != null && registered != null && explicit != registered) {
        ruleUnprocessable("BASELINE_SERIES_CONFLICT", "Series differs from the release series")
    }
    return BaselineScope(registered ?: explicit, identity.baselineArm())
}

// The series of a baseline query, normalized like release series so that slot keys and release series agree.
internal fun baselineSeriesParameter(raw: String): String =
    releaseTextField(raw, "series", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("series is invalid")

/** The slot a deletion addresses: both values are normalized like release text, and an arm needs its series. */
internal fun baselineSlotAddress(
    seriesRaw: String?,
    armRaw: String?,
): Pair<String?, String?> {
    val series = seriesRaw?.let(::baselineSeriesParameter)
    val arm = armRaw?.let { releaseTextField(it, "arm", MAX_RELEASE_TEXT_BYTES) ?: ruleMalformed("arm is invalid") }
    if (series == null && arm != null) ruleMalformed("arm requires series")
    return series to arm
}

internal fun baselineConditionDecision(request: JsonObject): String {
    if (request.keys != setOf("decision")) ruleMalformed("Baseline condition fields are invalid")
    val decision = request.baselineString("decision")
    if (decision !in BASELINE_CONDITION_DECISIONS) ruleMalformed("decision is invalid")
    return decision
}

private val BASELINE_CONDITION_DECISIONS = setOf("CONFIRMED", "NOT_CONFIRMED", "UNKNOWN")

/** The window comparison of a query: none without both windows, thresholds only with them, each threshold bounded. */
internal fun windowComparisonRequest(
    baseline: String?,
    current: String?,
    minChangePercent: String?,
    minErrorRateDelta: String?,
): WindowComparisonRequest? {
    if (baseline == null && current == null) {
        if (minChangePercent != null || minErrorRateDelta != null) {
            ruleMalformed("Materiality thresholds require both windows")
        }
        return null
    }
    if (listOf(baseline, current).any { it == null || it.isBlank() || it.encodeToByteArray().size > 128 || it.any(Char::isISOControl) }) {
        ruleMalformed("Both window IDs must contain 1–128 UTF-8 bytes without control characters")
    }
    return WindowComparisonRequest(
        checkNotNull(baseline),
        checkNotNull(current),
        boundedDecimal("min_change_percent", minChangePercent, "5", "1000"),
        boundedDecimal("min_error_rate_delta", minErrorRateDelta, "0.001", "1"),
    )
}

private fun boundedDecimal(
    name: String,
    given: String?,
    default: String,
    maximum: String,
): BigDecimal {
    val raw = given ?: default
    if (raw.length > 64 || !Regex("[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?").matches(raw)) ruleMalformed("$name is invalid")
    val value = raw.toBigDecimalOrNull() ?: ruleMalformed("$name is invalid")
    if (value.precision() > 32 || value.scale() !in -12..12 || value.signum() <= 0 || value > BigDecimal(maximum)) {
        ruleMalformed("$name is invalid")
    }
    return value
}

private val BASELINE_RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")

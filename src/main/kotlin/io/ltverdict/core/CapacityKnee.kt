package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

// ADR 0026: a diagnostic only. It never feeds bounds, stage verdicts or the policy verdict, and it is not shown to the advisory AI.
internal data class KneePoint(
    val stageId: String,
    val load: BigDecimal,
    val p95Millis: Long,
)

/**
 * Piecewise-linear ("hinge") regression of ln(p95) on the achieved load, one knot per candidate stage:
 * y = a + b*x + c*max(0, x - x_j). The knot with the minimal SSE wins; the knee is then the interval
 * [x_j, x_j+1) between the last stable stage and the first degraded one. Refuses instead of guessing.
 */
internal fun capacityKneeEvidence(
    loadAxis: CapacityLoadAxis,
    points: List<KneePoint>,
    refusal: String?,
): JsonObject {
    val outcome = refusal?.let { KneeOutcome(reason = it) } ?: detectKnee(points)
    return buildJsonObject {
        put("id", "capacity-knee-diagnostic")
        put("type", "capacity_knee_diagnostic")
        put("method", KNEE_METHOD)
        put("metric", "response_time_p95_ms")
        put("load_axis", loadAxis.wireName)
        put("unit", loadAxis.unit)
        put("status", if (outcome.knotIndex != null) "DETECTED" else "NOT_DETECTED")
        put("confidence", "UNCALIBRATED")
        put("calibrated", false)
        put("diagnostic_only", true)
        val stable = outcome.knotIndex?.let { points[it] }
        val degraded = outcome.knotIndex?.let { points[it + 1] }
        put("last_stable_stage_id", stable?.stageId?.let(::JsonPrimitive) ?: JsonNull)
        put("last_stable_load", stable?.let { JsonPrimitive(BigDecimal(canonicalDecimal(it.load))) } ?: JsonNull)
        put("first_degraded_stage_id", degraded?.stageId?.let(::JsonPrimitive) ?: JsonNull)
        put("first_degraded_load", degraded?.let { JsonPrimitive(BigDecimal(canonicalDecimal(it.load))) } ?: JsonNull)
        put("sse_ratio", outcome.sseRatio?.let { JsonPrimitive(it.setScale(6, RoundingMode.HALF_EVEN)) } ?: JsonNull)
        put("excess_factor", outcome.excessFactor?.let { JsonPrimitive(it.setScale(2, RoundingMode.HALF_EVEN)) } ?: JsonNull)
        put("reasons", buildJsonArray { outcome.reason?.let { add(JsonPrimitive(it)) } })
        put(
            "points",
            buildJsonArray {
                points.forEach {
                    add(
                        buildJsonObject {
                            put("stage_id", it.stageId)
                            put("load", JsonPrimitive(BigDecimal(canonicalDecimal(it.load))))
                            put("value", it.p95Millis)
                        },
                    )
                }
            },
        )
        put(
            "parameters",
            buildJsonObject {
                put("min_stages", KNEE_MIN_STAGES)
                put("min_points_before_knee", KNEE_MIN_LEFT_POINTS)
                put("max_sse_ratio", KNEE_MAX_SSE_RATIO.toPlainString())
                put("min_excess_factor", KNEE_MIN_EXCESS_FACTOR.toPlainString())
                put("noise_multiplier", KNEE_NOISE_MULTIPLIER)
            },
        )
    }
}

private data class KneeOutcome(
    val knotIndex: Int? = null,
    val reason: String? = null,
    val sseRatio: BigDecimal? = null,
    val excessFactor: BigDecimal? = null,
)

private fun detectKnee(points: List<KneePoint>): KneeOutcome {
    if (points.size < KNEE_MIN_STAGES) return KneeOutcome(reason = "KNEE_TOO_FEW_STAGES")
    if (points.zipWithNext().any { (left, right) -> left.load >= right.load }) return KneeOutcome(reason = "KNEE_LOAD_NOT_INCREASING")
    val scale = points.last().load.toDouble()
    val x = DoubleArray(points.size) { points[it].load.toDouble() / scale }
    val y = DoubleArray(points.size) { StrictMath.log(points[it].p95Millis.coerceAtLeast(1L).toDouble()) }
    val linear = sse(x, y) { listOf(1.0, it) }
    var best = -1
    var bestSse = Double.MAX_VALUE
    for (knot in (KNEE_MIN_LEFT_POINTS - 1) until points.size - 1) {
        val value = sse(x, y) { listOf(1.0, it, maxOf(0.0, it - x[knot])) }
        if (value < bestSse) {
            bestSse = value
            best = knot
        }
    }
    val ratio = if (linear > 0.0) bestSse / linear else 1.0
    val sseRatio = BigDecimal(ratio)
    if (linear <= 0.0 || ratio > KNEE_MAX_SSE_RATIO.toDouble()) return KneeOutcome(reason = "KNEE_NO_BREAK", sseRatio = sseRatio)
    // The stable branch is a line through the points up to the knot; the break is how far the degraded stages rise above it.
    val (intercept, slope) = line(x.copyOfRange(0, best + 1), y.copyOfRange(0, best + 1))
    var residual = 0.0
    for (i in 0..best) residual += (y[i] - intercept - slope * x[i]).let { it * it }
    val sigma = StrictMath.sqrt(residual / (best + 1 - 2))
    val excess = (best + 1 until points.size).maxOf { y[it] - intercept - slope * x[it] }
    val factor = BigDecimal(StrictMath.exp(excess))
    val reason =
        when {
            excess < StrictMath.log(KNEE_MIN_EXCESS_FACTOR.toDouble()) -> "KNEE_BREAK_NOT_MATERIAL"
            excess < KNEE_NOISE_MULTIPLIER * sigma -> "KNEE_BREAK_WITHIN_NOISE"
            else -> null
        }
    return KneeOutcome(if (reason == null) best else null, reason, sseRatio, factor)
}

private fun line(
    x: DoubleArray,
    y: DoubleArray,
): Pair<Double, Double> {
    val meanX = x.average()
    val meanY = y.average()
    var covariance = 0.0
    var variance = 0.0
    for (i in x.indices) {
        covariance += (x[i] - meanX) * (y[i] - meanY)
        variance += (x[i] - meanX) * (x[i] - meanX)
    }
    val slope = covariance / variance
    return (meanY - slope * meanX) to slope
}

// Least squares through the given basis, solved from the normal equations by Gaussian elimination with partial pivoting.
private fun sse(
    x: DoubleArray,
    y: DoubleArray,
    basis: (Double) -> List<Double>,
): Double {
    val rows = x.map(basis)
    val n = rows.first().size
    val a =
        Array(n) { i ->
            DoubleArray(n + 1) { j ->
                if (j <
                    n
                ) {
                    rows.sumOf { it[i] * it[j] }
                } else {
                    rows.indices.sumOf { rows[it][i] * y[it] }
                }
            }
        }
    for (column in 0 until n) {
        val pivot = (column until n).maxBy { StrictMath.abs(a[it][column]) }
        a[column] = a[pivot].also { a[pivot] = a[column] }
        for (row in column + 1 until n) {
            val factor = a[row][column] / a[column][column]
            for (k in column..n) a[row][k] -= factor * a[column][k]
        }
    }
    val beta = DoubleArray(n)
    for (row in n - 1 downTo 0) {
        var value = a[row][n]
        for (k in row + 1 until n) value -= a[row][k] * beta[k]
        beta[row] = value / a[row][row]
    }
    return rows.indices.sumOf { i -> (y[i] - rows[i].indices.sumOf { rows[i][it] * beta[it] }).let { it * it } }
}

private const val KNEE_METHOD = "piecewise-hinge-ln-p95.v1"
private const val KNEE_MIN_STAGES = 5
private const val KNEE_MIN_LEFT_POINTS = 3
private const val KNEE_NOISE_MULTIPLIER = 3
private val KNEE_MAX_SSE_RATIO = BigDecimal("0.25")
private val KNEE_MIN_EXCESS_FACTOR = BigDecimal("1.5")

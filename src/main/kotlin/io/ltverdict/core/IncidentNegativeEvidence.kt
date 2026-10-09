package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// W3.7 / ADR 0029: what the incident synthesis reads from the evidence of a result (the closed list of types of the ADR) and the negative
// evidence of an incident: what was checked and not confirmed, and what was not checked. See IncidentSynthesis.kt.

private const val EPOCH_MS_MAX = 253_402_300_799_999L

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.epoch(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..EPOCH_MS_MAX }

private fun JsonObject.strings(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content } ?: emptyList()

private fun JsonObject.idOrEmpty(): String = string("id").orEmpty()

private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/** A check of the evidence that can give a negative result: the fields of the closed list of ADR 0029. */
internal class IncidentCheck(
    val id: String,
    val windowId: String?,
    val status: String,
    val codes: List<String>,
    val seriesId: String? = null,
)

internal class IncidentSeries(
    val entity: String?,
    val role: String?,
)

internal class IncidentEvidenceIndex(
    private val evidence: List<JsonObject>,
) {
    val byId: Map<String, JsonObject> = evidence.mapNotNull { item -> item.string("id")?.let { it to item } }.toMap()
    val hasBinding: Boolean = evidence.any { it.string("type") == "resource_binding" }

    private fun ofType(type: String): List<JsonObject> = evidence.filter { it.string("type") == type }

    /** The bounds of a window; if a window has several summaries the one with the least id decides. */
    val windows: Map<String, Pair<Long, Long>> =
        ofType("window_policy_summary")
            .sortedWith { a, b -> utf8Compare(a.idOrEmpty(), b.idOrEmpty()) }
            .mapNotNull { item ->
                val window = item.string("window_id")
                val from = item.epoch("from_epoch_ms")
                val to = item.epoch("to_epoch_ms")
                if (window != null && from != null && to != null && from < to) window to (from to to) else null
            }.reversed()
            .toMap()

    /** Entity and role of a series come from resource_summary by (window, series); the summary with the least id decides. */
    private val series: Map<Pair<String?, String>, IncidentSeries> =
        ofType("resource_summary")
            .sortedWith { a, b -> utf8Compare(a.idOrEmpty(), b.idOrEmpty()) }
            .mapNotNull { item ->
                val seriesId = item.string("series_id") ?: return@mapNotNull null
                (item.string("window_id") to seriesId) to IncidentSeries(item.string("entity"), item.string("role"))
            }.reversed()
            .toMap()

    private fun checks(
        type: String,
        codes: (JsonObject) -> List<String>,
    ): List<IncidentCheck> =
        ofType(type).mapNotNull { item ->
            val id = item.string("id") ?: return@mapNotNull null
            IncidentCheck(id, item.string("window_id"), item.string("status").orEmpty(), codes(item), item.string("series_id"))
        }

    val policyChecks = checks("policy_check") { listOfNotNull(it.string("reason_code")) }
    val resourceChecks = checks("resource_policy_check") { listOfNotNull(it.string("reason")) }
    val anomalyChecks = checks("anomaly_check") { it.strings("reasons") }
    val trendChecks = checks("trend_check") { it.strings("reasons") }
    val ruleWindowChecks = checks("rule_window_check") { listOfNotNull(it.string("reason_code")) }

    fun seriesOf(check: IncidentCheck): IncidentSeries? = check.seriesId?.let { series[check.windowId to it] }
}

internal class IncidentNegative(
    val check: String,
    val evidenceIds: List<String>,
    val reasonCode: String?,
    text: String,
) {
    val json: JsonObject =
        JsonObject(
            buildMap<String, JsonElement> {
                put("check", JsonPrimitive(check))
                put("outcome", JsonPrimitive(if (reasonCode == null) "NOT_CONFIRMED" else "NOT_EVALUATED"))
                if (reasonCode != null) put("reason_code", JsonPrimitive(reasonCode))
                put("text", JsonPrimitive(text))
                put("evidence_ids", strings(evidenceIds))
            },
        )
}

private fun List<IncidentCheck>.firstIds(): List<String> = map { it.id }.sortedWith(::utf8Compare).take(INCIDENT_NEGATIVE_IDS_MAX)

internal fun negativeEvidence(
    draft: IncidentDraft,
    validity: RunValidity,
    index: IncidentEvidenceIndex,
): List<IncidentNegative> {
    val window = draft.windowId
    val own = draft.evidenceIds.toSet()
    val inWindow = { check: IncidentCheck -> window != null && check.windowId == window }
    val out = mutableListOf<IncidentNegative>()

    fun confirmed(
        check: String,
        matching: List<IncidentCheck>,
        template: String,
    ) {
        if (matching.isEmpty()) return
        val text = renderIncidentText(template, "window_id" to window.orEmpty(), "n" to matching.size.toString())
        out += IncidentNegative(check, matching.firstIds(), null, text)
    }

    if (validity == RunValidity.DEGRADED && draft.family == IncidentFamily.RESOURCE) {
        out += IncidentNegative("POLICY_NOT_EVALUATED", emptyList(), "RUN_DEGRADED", NEGATIVE_POLICY_NOT_EVALUATED)
    }
    if (draft.family == IncidentFamily.TRANSACTION) {
        val passed = index.policyChecks.filter { it.windowId == window && it.status == "PASS" && it.id !in own }
        confirmed("OTHER_POLICY_CHECKS_PASSED", passed, if (window == null) NEGATIVE_OTHER_POLICY else NEGATIVE_OTHER_POLICY_WINDOW)
    }
    if (window != null) {
        val passedResources = index.resourceChecks.filter { inWindow(it) && it.status == "PASS" }
        // ADR 0029 restricts neither check by family; only a resource incident leaves out the entity it is about
        val ownEntity = if (draft.family == IncidentFamily.RESOURCE) draft.scope.string("entity") else null
        confirmed(
            "RESOURCE_RULES_WITHIN_LIMITS",
            passedResources.filter { check ->
                val series = index.seriesOf(check)
                series?.role != "generator" &&
                    (draft.family == IncidentFamily.TRANSACTION || (series?.entity != null && series.entity != ownEntity))
            },
            NEGATIVE_RESOURCE_RULES,
        )
        confirmed(
            "GENERATOR_RESOURCES_WITHIN_LIMITS",
            passedResources.filter { index.seriesOf(it)?.role == "generator" },
            NEGATIVE_GENERATOR_RESOURCES,
        )
        confirmed(
            "NO_ANOMALY_EPISODES",
            index.anomalyChecks.filter { inWindow(it) && it.status == "NO_MATERIAL_CHANGE" },
            NEGATIVE_NO_ANOMALY_EPISODES,
        )
        confirmed(
            "NO_MATERIAL_TREND",
            index.trendChecks.filter { inWindow(it) && it.status == "NO_MATERIAL_TREND" },
            NEGATIVE_NO_MATERIAL_TREND,
        )
    }
    if (!index.hasBinding) {
        out +=
            IncidentNegative(
                "RESOURCE_DATA_NOT_PROVIDED",
                emptyList(),
                "RESOURCE_SNAPSHOT_NOT_PROVIDED",
                NEGATIVE_RESOURCE_DATA_NOT_PROVIDED,
            )
    }
    if (window != null) {
        val notEvaluated =
            index.policyChecks.filter { inWindow(it) && it.status == "NO_VERDICT" } +
                index.resourceChecks.filter { inWindow(it) && it.status == "NO_VERDICT" } +
                index.anomalyChecks.filter { inWindow(it) && it.status == "INSUFFICIENT_DATA" } +
                index.trendChecks.filter { inWindow(it) && (it.status == "INSUFFICIENT_CELLS" || it.status == "UNAVAILABLE") } +
                index.ruleWindowChecks.filter { inWindow(it) && it.status == "NO_VERDICT" }
        if (notEvaluated.isNotEmpty()) {
            val code = notEvaluated.flatMap { it.codes.ifEmpty { listOf(it.status) } }.minWithOrNull(::utf8Compare)!!
            val text =
                renderIncidentText(
                    NEGATIVE_CHECKS_NOT_EVALUATED,
                    "window_id" to window,
                    "n" to notEvaluated.size.toString(),
                    "reason_code" to code,
                )
            out += IncidentNegative("CHECKS_NOT_EVALUATED", notEvaluated.firstIds(), code, text)
        }
    }
    return out
}

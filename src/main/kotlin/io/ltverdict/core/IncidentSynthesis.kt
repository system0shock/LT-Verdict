package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

// W3.7 / ADR 0029 (incident.v1, incident-synthesis.v1, incident-grouping.v1): a deterministic regrouping of the findings of ONE analysis
// result by window, time and scope. The function is pure: no input/output, clock, randomness or locale; every ordering is by the bytes of
// UTF-8 (utf8Compare); the input is exactly run_validity, findings and evidence. The output is a JsonObject with explicit nulls, to be
// put into the result after its serialization (as capacity_summary is); the function is not wired into the analysis yet (PR 2).
//
// An incident says what coincided in time and where. It has no cause, confidence, subsystem or impact, and it does not touch the verdict.

internal const val INCIDENT_SCHEMA_VERSION = "incident.v1"
internal const val INCIDENT_METHOD = "incident-synthesis.v1"
internal const val INCIDENT_GROUPING_RULE = "incident-grouping.v1"
internal const val INCIDENT_OVERVIEW_LIMIT = 7
internal const val INCIDENT_STORED_MAX = 64
internal const val INCIDENT_FINDING_IDS_MAX = 16
internal const val INCIDENT_EVIDENCE_IDS_MAX = 24
internal const val INCIDENT_NEGATIVE_IDS_MAX = 8
internal const val INCIDENT_LINKS_MAX = 5
internal const val INCIDENT_NAME_CODE_POINTS_MAX = 64

private const val EPOCH_MS_MAX = 253_402_300_799_999L

private const val TIER_POLICY_FAILURE = 1
private const val TIER_RESOURCE_SLA = 2
private const val TIER_RESOURCE_OTHER = 3
private const val TIER_ANOMALY_EPISODE = 4
private const val TIER_RESOURCE_TREND = 5

/** Orders strings by the bytes of their UTF-8, which is the order of code points (not of UTF-16 units, as String.compareTo is). */
internal fun utf8Compare(
    left: String,
    right: String,
): Int {
    var l = 0
    var r = 0
    while (l < left.length && r < right.length) {
        val a = left.codePointAt(l)
        val b = right.codePointAt(r)
        if (a != b) return a.compareTo(b)
        l += Character.charCount(a)
        r += Character.charCount(b)
    }
    return (left.length - l).compareTo(right.length - r)
}

/** The name as it is shown in a text: controls become a space, more than 64 code points are cut to 63 and an ellipsis. */
internal fun incidentDisplayName(name: String): String {
    val clean = StringBuilder()
    name.codePoints().forEach { clean.appendCodePoint(if (it <= 0x1F || it in 0x7F..0x9F) ' '.code else it) }
    val text = clean.toString()
    if (text.codePointCount(0, text.length) <= INCIDENT_NAME_CODE_POINTS_MAX) return text
    return text.substring(0, text.offsetByCodePoints(0, INCIDENT_NAME_CODE_POINTS_MAX - 1)) + "…"
}

internal fun synthesizeIncidents(
    validity: RunValidity,
    findings: List<JsonObject>,
    evidence: List<JsonObject>,
): JsonObject {
    requireUniqueIds(findings, "findings")
    requireUniqueIds(evidence, "evidence")
    if (validity == RunValidity.INVALID) return incidentDocument(notEvaluated = true, total = 0, omitted = 0, items = emptyList())
    val index = IncidentEvidenceIndex(evidence)
    val drafts = group(findings.mapNotNull { atomOf(it, index) })
    val stored = drafts.sortedWith(PRIORITY_ORDER).take(INCIDENT_STORED_MAX)
    val links = stored.map { draft -> eligibleLinks(draft, stored) }
    val items = stored.mapIndexed { position, draft -> incidentItem(position + 1, draft, links[position], validity, index) }
    return incidentDocument(notEvaluated = false, total = drafts.size, omitted = drafts.size - stored.size, items = items)
}

private fun requireUniqueIds(
    items: List<JsonObject>,
    what: String,
) {
    val seen = HashSet<String>()
    for (item in items) {
        val id = item.string("id") ?: continue
        require(seen.add(id)) { "$what: repeated id $id" }
    }
}

// ---------------------------------------------------------------------------------------------------------------- reading the input

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.epoch(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..EPOCH_MS_MAX }

// ------------------------------------------------------------------------------------------------------------------------- atoms

internal enum class IncidentFamily { TRANSACTION, RESOURCE }

internal class IncidentAtom(
    val findingId: String,
    val findingType: String,
    val evidenceIds: List<String>,
    val tier: Int,
    val windowId: String?,
    val scope: JsonObject,
    val fromEpochMs: Long,
    val toEpochMs: Long,
)

private fun atomOf(
    finding: JsonObject,
    index: IncidentEvidenceIndex,
): IncidentAtom? {
    val id = finding.string("id") ?: return null
    val type = finding.string("type") ?: return null
    val evidenceId = finding.string("evidence_id") ?: return null
    val evidence = index.byId[evidenceId] ?: return null
    return when (type) {
        "policy_failure" -> transactionAtom(id, type, finding, evidenceId, evidence, index)
        "resource_threshold_violation" -> {
            val confirmed = evidence.string("type") == "resource_policy_check" && evidence.string("effect") == "sla" && !finding.presumed()
            resourceAtom(id, type, finding, evidenceId, if (confirmed) TIER_RESOURCE_SLA else TIER_RESOURCE_OTHER)
        }
        "anomaly_episode" -> resourceAtom(id, type, finding, evidenceId, TIER_ANOMALY_EPISODE)
        "resource_trend" -> resourceAtom(id, type, finding, evidenceId, TIER_RESOURCE_TREND)
        else -> null
    }
}

private fun JsonObject.presumed(): Boolean = (this["presumed"] as? JsonPrimitive)?.booleanOrNull == true

private fun transactionAtom(
    id: String,
    type: String,
    finding: JsonObject,
    evidenceId: String,
    evidence: JsonObject,
    index: IncidentEvidenceIndex,
): IncidentAtom? {
    val windowId = finding.string("window_id")
    val metricId = evidence.string("metric_evidence_id")?.takeIf { it in index.byId }
    val scope =
        transactionScope((evidence["scope"] as? JsonObject) ?: metricId?.let { index.byId[it]?.get("scope") as? JsonObject }) ?: return null
    val bounds = if (windowId == null) 0L to 0L else index.windows[windowId] ?: return null
    return IncidentAtom(id, type, listOfNotNull(evidenceId, metricId), TIER_POLICY_FAILURE, windowId, scope, bounds.first, bounds.second)
}

private fun resourceAtom(
    id: String,
    type: String,
    finding: JsonObject,
    evidenceId: String,
    tier: Int,
): IncidentAtom? {
    val windowId = finding.string("window_id") ?: return null
    val entity = finding.string("entity")?.takeIf { it.isNotEmpty() } ?: return null
    val from = finding.epoch("from_epoch_ms") ?: return null
    val to = finding.epoch("to_epoch_ms")?.takeIf { it > from } ?: return null
    val scope = JsonObject(mapOf("kind" to JsonPrimitive("entity"), "entity" to JsonPrimitive(entity)))
    return IncidentAtom(id, type, listOf(evidenceId), tier, windowId, scope, from, to)
}

/** The scope as the schema has it: kind, label, group_path and sample_kind only; null if the scope names no overall run or transaction. */
private fun transactionScope(raw: JsonObject?): JsonObject? =
    when (raw?.string("kind")) {
        "overall" -> JsonObject(mapOf("kind" to JsonPrimitive("overall")))
        "transaction" -> {
            val label = raw.string("label")?.takeIf { it.isNotEmpty() }
            val path = (raw["group_path"] as? JsonArray)?.takeIf { array -> array.all { (it as? JsonPrimitive)?.isString == true } }
            val sampleKind = raw.string("sample_kind")
            if (label == null) {
                null
            } else {
                JsonObject(
                    buildMap<String, JsonElement> {
                        put("kind", JsonPrimitive("transaction"))
                        put("label", JsonPrimitive(label))
                        if (path != null) put("group_path", path)
                        if (sampleKind != null) put("sample_kind", JsonPrimitive(sampleKind))
                    },
                )
            }
        }
        else -> null
    }

// ------------------------------------------------------------------------------------------------------------------------ groups

internal class IncidentDraft(
    val family: IncidentFamily,
    val windowId: String?,
    val scope: JsonObject,
    val clusterFromEpochMs: Long?,
    val atoms: List<IncidentAtom>,
) {
    val tier: Int = atoms.minOf { it.tier }

    /** TRANSACTION: the bounds of the window (null without a window); RESOURCE: from the least start to the greatest end of the atoms. */
    val interval: Pair<Long, Long>? =
        when {
            family == IncidentFamily.RESOURCE -> atoms.minOf { it.fromEpochMs } to atoms.maxOf { it.toEpochMs }
            windowId != null -> atoms.first().fromEpochMs to atoms.first().toEpochMs
            else -> null
        }

    val key: JsonObject =
        JsonObject(
            mapOf(
                "family" to JsonPrimitive(family.name),
                "window_id" to (windowId?.let { JsonPrimitive(it) } ?: JsonNull),
                "scope" to scope,
                "cluster_from_epoch_ms" to (clusterFromEpochMs?.let { JsonPrimitive(it) } ?: JsonNull),
            ),
        )
    val id: String = "incident-" + sha256Hex(canonicalJson(key))

    /** All the evidence of the atoms, by UTF-8 bytes, before any cut. */
    val evidenceIds: List<String> = atoms.flatMap { it.evidenceIds }.distinct().sortedWith(::utf8Compare)
}

private fun group(atoms: List<IncidentAtom>): List<IncidentDraft> {
    val transaction = atoms.filter { it.scope.string("kind") != "entity" }.groupBy { it.windowId to String(canonicalJson(it.scope)) }
    val resource =
        atoms
            .filter { it.scope.string("kind") == "entity" }
            .groupBy { it.windowId to it.scope.string("entity") }
    return transaction.values.map { IncidentDraft(IncidentFamily.TRANSACTION, it.first().windowId, it.first().scope, null, it) } +
        resource.values.flatMap { sameEntity ->
            clusters(sameEntity).map {
                IncidentDraft(
                    IncidentFamily.RESOURCE,
                    it.first().windowId,
                    it.first().scope,
                    it.minOf { atom ->
                        atom.fromEpochMs
                    },
                    it,
                )
            }
        }
}

/** Atoms sorted by (from, to, finding id); an atom joins the current cluster if it starts no later than the cluster ends (touching joins). */
private fun clusters(atoms: List<IncidentAtom>): List<List<IncidentAtom>> {
    val sorted =
        atoms.sortedWith(
            compareBy<IncidentAtom> { it.fromEpochMs }.thenBy { it.toEpochMs }.thenComparator {
                a,
                b,
                ->
                utf8Compare(a.findingId, b.findingId)
            },
        )
    val result = mutableListOf<MutableList<IncidentAtom>>()
    var end = Long.MIN_VALUE
    for (atom in sorted) {
        if (result.isEmpty() || atom.fromEpochMs > end) {
            result += mutableListOf(atom)
            end = atom.toEpochMs
        } else {
            result.last() += atom
            end = maxOf(end, atom.toEpochMs)
        }
    }
    return result
}

/** tier ascending, finding count descending, start ascending (null last), id by UTF-8 bytes. */
private val PRIORITY_ORDER: Comparator<IncidentDraft> =
    Comparator { a, b ->
        val byTier = a.tier.compareTo(b.tier)
        if (byTier != 0) return@Comparator byTier
        val byCount = b.atoms.size.compareTo(a.atoms.size)
        if (byCount != 0) return@Comparator byCount
        val x = a.interval?.first
        val y = b.interval?.first
        val byStart =
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                else -> x.compareTo(y)
            }
        if (byStart != 0) byStart else utf8Compare(a.id, b.id)
    }

private class IncidentLink(
    val incidentId: String,
    val basis: String,
)

/** The first five incidents by rank that ADR 0029 (rule 7) relates to this one: the same window, a different family or overlapping entities. */
private fun eligibleLinks(
    draft: IncidentDraft,
    stored: List<IncidentDraft>,
): List<IncidentLink> {
    val window = draft.windowId ?: return emptyList()
    val links = mutableListOf<IncidentLink>()
    for (other in stored) {
        if (other === draft || other.windowId != window) continue
        val basis =
            when {
                other.family != draft.family -> "SAME_WINDOW"
                draft.family == IncidentFamily.RESOURCE &&
                    other.scope != draft.scope &&
                    overlaps(
                        draft.interval,
                        other.interval,
                    ) -> "INTERVAL_OVERLAP"
                else -> continue
            }
        links += IncidentLink(other.id, basis)
        if (links.size == INCIDENT_LINKS_MAX) break
    }
    return links
}

private fun overlaps(
    a: Pair<Long, Long>?,
    b: Pair<Long, Long>?,
): Boolean = a != null && b != null && a.first < b.second && b.first < a.second

// ---------------------------------------------------------------------------------------------------------------------- the output

private fun incidentDocument(
    notEvaluated: Boolean,
    total: Int,
    omitted: Int,
    items: List<JsonObject>,
): JsonObject =
    JsonObject(
        buildMap<String, JsonElement> {
            put("schema_version", JsonPrimitive(INCIDENT_SCHEMA_VERSION))
            put("method", JsonPrimitive(INCIDENT_METHOD))
            put("status", JsonPrimitive(if (notEvaluated) "NOT_EVALUATED" else "EVALUATED"))
            if (notEvaluated) put("reason_code", JsonPrimitive("RUN_NOT_VALID"))
            put("overview_limit", JsonPrimitive(INCIDENT_OVERVIEW_LIMIT))
            put("total_count", JsonPrimitive(total))
            put("omitted_count", JsonPrimitive(omitted))
            put("items", JsonArray(items))
        },
    )

private fun incidentItem(
    rank: Int,
    draft: IncidentDraft,
    links: List<IncidentLink>,
    validity: RunValidity,
    index: IncidentEvidenceIndex,
): JsonObject {
    val atoms = draft.atoms
    val findingIds =
        when (draft.family) {
            IncidentFamily.TRANSACTION -> atoms.sortedWith { a, b -> utf8Compare(a.findingId, b.findingId) }
            else ->
                atoms.sortedWith(
                    compareBy<IncidentAtom> { it.fromEpochMs }.thenComparator {
                        a,
                        b,
                        ->
                        utf8Compare(a.findingId, b.findingId)
                    },
                )
        }.map { it.findingId }
    val negative = negativeEvidence(draft, validity, index)
    val first = draft.interval?.first
    val texts = IncidentWording(draft)
    return JsonObject(
        mapOf(
            "id" to JsonPrimitive(draft.id),
            "rank" to JsonPrimitive(rank),
            "in_overview" to JsonPrimitive(rank <= INCIDENT_OVERVIEW_LIMIT),
            "priority" to
                JsonPrimitive(
                    when {
                        draft.tier <= 2 -> "HIGH"
                        draft.tier <= 4 -> "MEDIUM"
                        else -> "LOW"
                    },
                ),
            "priority_key" to
                JsonObject(
                    mapOf(
                        "tier" to JsonPrimitive(draft.tier),
                        "finding_count" to JsonPrimitive(atoms.size),
                        "first_epoch_ms" to (first?.let { JsonPrimitive(it) } ?: JsonNull),
                    ),
                ),
            "title" to JsonPrimitive(texts.title()),
            "summary" to JsonPrimitive(texts.summary()),
            "scope" to draft.scope,
            "window_id" to (draft.windowId?.let { JsonPrimitive(it) } ?: JsonNull),
            "interval_basis" to
                JsonPrimitive(
                    when {
                        draft.interval == null -> "UNKNOWN"
                        draft.family == IncidentFamily.RESOURCE -> "FINDINGS"
                        else -> "WINDOW"
                    },
                ),
            "interval" to
                (
                    draft.interval?.let {
                        JsonObject(
                            mapOf(
                                "from_epoch_ms" to JsonPrimitive(it.first),
                                "to_epoch_ms" to JsonPrimitive(it.second),
                            ),
                        )
                    }
                        ?: JsonNull
                ),
            "grouping" to JsonObject(mapOf("rule" to JsonPrimitive(INCIDENT_GROUPING_RULE), "key" to draft.key)),
            "finding_count" to JsonPrimitive(atoms.size),
            "finding_types" to strings(atoms.map { it.findingType }.distinct().sortedWith(::utf8Compare)),
            "finding_ids" to strings(findingIds.take(INCIDENT_FINDING_IDS_MAX)),
            "evidence_ids" to strings(draft.evidenceIds.take(INCIDENT_EVIDENCE_IDS_MAX)),
            "refs_truncated" to
                JsonPrimitive(atoms.size > INCIDENT_FINDING_IDS_MAX || draft.evidenceIds.size > INCIDENT_EVIDENCE_IDS_MAX),
            "negative_evidence" to JsonArray(negative.map { it.json }),
            "next_checks" to JsonArray(nextChecks(draft, links.size, negative, texts)),
            "coincident_with" to
                JsonArray(
                    links.map { JsonObject(mapOf("incident_id" to JsonPrimitive(it.incidentId), "basis" to JsonPrimitive(it.basis))) },
                ),
        ),
    )
}

private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/** The wording of one incident: only the templates of IncidentTexts.kt with the (cleaned) names substituted. */
private class IncidentWording(
    private val draft: IncidentDraft,
) {
    private val window = draft.windowId.orEmpty()
    private val count = draft.atoms.size.toString()
    private val scope = draft.scope
    private val isLoad = scope.string("kind") == "entity" && scope.string("entity") == "overall"
    private val entity = incidentDisplayName(scope.string("entity").orEmpty())
    private val label = incidentDisplayName(scope.string("label").orEmpty())

    private fun areaOf(
        overall: String,
        transaction: String,
    ) = if (scope.string("kind") == "overall") overall else renderIncidentText(transaction, "label" to label)

    private fun area() = areaOf(AREA_OVERALL, AREA_TRANSACTION)

    private fun areaGenitive() = areaOf(AREA_OVERALL_GENITIVE, AREA_TRANSACTION_GENITIVE)

    fun title(): String =
        when {
            draft.family == IncidentFamily.RESOURCE && isLoad -> renderIncidentText(TITLE_LOAD, "window_id" to window)
            draft.family == IncidentFamily.RESOURCE -> renderIncidentText(TITLE_RESOURCE, "entity" to entity, "window_id" to window)
            draft.windowId != null -> renderIncidentText(TITLE_TRANSACTION_WINDOW, "area" to area(), "window_id" to window)
            else -> renderIncidentText(TITLE_TRANSACTION, "area" to area())
        }

    fun summary(): String =
        when {
            draft.family == IncidentFamily.RESOURCE && isLoad -> renderIncidentText(SUMMARY_LOAD, "n" to count)
            draft.family == IncidentFamily.RESOURCE -> renderIncidentText(SUMMARY_RESOURCE, "entity" to entity, "n" to count)
            draft.windowId != null -> renderIncidentText(SUMMARY_TRANSACTION_WINDOW, "window_id" to window, "n" to count, "area" to area())
            else -> renderIncidentText(SUMMARY_TRANSACTION, "n" to count, "area" to area())
        }

    fun compare(): String = renderIncidentText(NEXT_COMPARE_WITH_BASELINE, "area" to areaGenitive())

    fun series(): String = if (isLoad) NEXT_OPEN_LOAD_SERIES else renderIncidentText(NEXT_OPEN_RESOURCE_SERIES, "entity" to entity)

    fun signals(links: Int): String = renderIncidentText(NEXT_OPEN_SAME_WINDOW_SIGNALS, "window_id" to window, "n" to links.toString())
}

// ---------------------------------------------------------------------------------------------------- negative evidence, next checks

private fun nextChecks(
    draft: IncidentDraft,
    links: Int,
    negative: List<IncidentNegative>,
    texts: IncidentWording,
): List<JsonObject> {
    fun next(
        check: String,
        text: String,
        evidenceIds: List<String>,
    ) = JsonObject(mapOf("check" to JsonPrimitive(check), "text" to JsonPrimitive(text), "evidence_ids" to strings(evidenceIds)))

    val firstEvidence = draft.evidenceIds.take(1)
    val out = mutableListOf<JsonObject>()
    out +=
        if (draft.family == IncidentFamily.TRANSACTION) {
            next("COMPARE_WITH_BASELINE", texts.compare(), firstEvidence)
        } else {
            next("OPEN_RESOURCE_SERIES", texts.series(), firstEvidence)
        }
    if (links > 0) out += next("OPEN_SAME_WINDOW_SIGNALS", texts.signals(links), emptyList())
    if (negative.any { it.check == "RESOURCE_DATA_NOT_PROVIDED" }) {
        out += next("PROVIDE_RESOURCE_SNAPSHOT", NEXT_PROVIDE_RESOURCE_SNAPSHOT, emptyList())
    }
    negative.firstOrNull { it.check == "CHECKS_NOT_EVALUATED" }?.let {
        out += next("COMPLETE_NOT_EVALUATED_CHECKS", NEXT_COMPLETE_NOT_EVALUATED_CHECKS, it.evidenceIds)
    }
    return out
}

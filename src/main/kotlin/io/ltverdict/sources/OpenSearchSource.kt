package io.ltverdict.sources

import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.core.canonicalJson
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class OpenSearchMapping(
    val indices: List<String>,
    val timestampField: String,
    val serviceField: String,
    val errorTypeField: String,
    val messageField: String,
    val groupLimit: Int = 50,
    val samplesPerGroup: Int = 2,
    val sampleMessageBytesMax: Int = 4_096,
) {
    init {
        if (indices.size !in 1..MAX_INDICES || indices.any { !validIndex(it) }) invalidMapping()
        if (listOf(timestampField, serviceField, errorTypeField, messageField).any { !validField(it) }) invalidMapping()
        if (groupLimit !in 1..MAX_GROUPS || samplesPerGroup !in 0..MAX_SAMPLES_PER_GROUP) invalidMapping()
        if (sampleMessageBytesMax !in 1..MAX_SAMPLE_MESSAGE_BYTES) invalidMapping()
    }
}

internal fun buildOpenSearchQuery(
    mapping: OpenSearchMapping,
    request: SourceRequest,
    timeoutMillis: Long,
): ByteArray {
    validateRequest(request)
    if (timeoutMillis <= 0) fail("OPENSEARCH_INVALID_TIMEOUT")
    val groupAggregations =
        buildJsonObject {
            put("first_at", metricAggregation("min", mapping.timestampField))
            put("last_at", metricAggregation("max", mapping.timestampField))
            if (mapping.samplesPerGroup > 0) {
                put(
                    "samples",
                    buildJsonObject {
                        put(
                            "top_hits",
                            buildJsonObject {
                                put("size", mapping.samplesPerGroup)
                                put(
                                    "sort",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(mapping.timestampField, buildJsonObject { put("order", "asc") })
                                            },
                                        )
                                    },
                                )
                                put(
                                    "_source",
                                    buildJsonObject {
                                        put("includes", buildJsonArray { add(JsonPrimitive(mapping.messageField)) })
                                    },
                                )
                            },
                        )
                    },
                )
            }
        }
    return canonicalJson(
        buildJsonObject {
            put("size", 0)
            put("track_total_hits", true)
            put("timeout", "${timeoutMillis}ms")
            put(
                "query",
                buildJsonObject {
                    put(
                        "bool",
                        buildJsonObject {
                            put(
                                "filter",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put(
                                                "range",
                                                buildJsonObject {
                                                    put(
                                                        mapping.timestampField,
                                                        buildJsonObject {
                                                            put("gte", request.startEpochMillis)
                                                            put("lt", request.endEpochMillis)
                                                            put("format", "epoch_millis")
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                    add(
                                        buildJsonObject {
                                            put("exists", buildJsonObject { put("field", mapping.errorTypeField) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "aggs",
                buildJsonObject {
                    put(
                        "timeline",
                        buildJsonObject {
                            put(
                                "date_histogram",
                                buildJsonObject {
                                    put("field", mapping.timestampField)
                                    put("fixed_interval", "${request.stepMillis}ms")
                                    put("offset", "${Math.floorMod(request.startEpochMillis, request.stepMillis)}ms")
                                    put("min_doc_count", 0)
                                    put(
                                        "extended_bounds",
                                        buildJsonObject {
                                            put("min", request.startEpochMillis)
                                            put("max", request.endEpochMillis - request.stepMillis)
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put(
                        "groups",
                        buildJsonObject {
                            put(
                                "multi_terms",
                                buildJsonObject {
                                    put(
                                        "terms",
                                        buildJsonArray {
                                            add(buildJsonObject { put("field", mapping.serviceField) })
                                            add(buildJsonObject { put("field", mapping.errorTypeField) })
                                        },
                                    )
                                    put("size", mapping.groupLimit)
                                    put("order", buildJsonObject { put("_count", "desc") })
                                },
                            )
                            put("aggs", groupAggregations)
                        },
                    )
                },
            )
        },
    )
}

internal fun decodeOpenSearchResponse(
    body: ByteArray,
    mapping: OpenSearchMapping,
    request: SourceRequest,
    loadInputSha256: String,
    baseUrl: URI,
): JsonObject {
    validateRequest(request)
    validateHash(loadInputSha256)
    val safeBaseUrl = safeBaseUrl(baseUrl)
    val root = parseJson(body, "OPENSEARCH_MALFORMED_RESPONSE")
    if ("error" in root) fail("OPENSEARCH_ERROR_RESPONSE")

    val timedOut = root.boolean("timed_out", "OPENSEARCH_MALFORMED_RESPONSE")
    val shards = parseShards(root.obj("_shards", "OPENSEARCH_MALFORMED_RESPONSE"))
    val totalObject = root.obj("hits", "OPENSEARCH_MALFORMED_RESPONSE").obj("total", "OPENSEARCH_MALFORMED_RESPONSE")
    val total = totalObject.nonNegativeLong("value", "OPENSEARCH_MALFORMED_RESPONSE")
    val totalRelation = totalObject.string("relation", "OPENSEARCH_MALFORMED_RESPONSE")
    if (totalRelation !in TOTAL_RELATIONS) fail("OPENSEARCH_MALFORMED_RESPONSE")

    val aggregations = root.obj("aggregations", "OPENSEARCH_MALFORMED_RESPONSE")
    val timeline = parseResponseTimeline(aggregations.obj("timeline", "OPENSEARCH_MALFORMED_RESPONSE"), request)
    val groupAggregate = aggregations.obj("groups", "OPENSEARCH_MALFORMED_RESPONSE")
    val sumOther = groupAggregate.nonNegativeLong("sum_other_doc_count", "OPENSEARCH_MALFORMED_RESPONSE")
    val countError = groupAggregate.long("doc_count_error_upper_bound", "OPENSEARCH_MALFORMED_RESPONSE")
    if (countError < -1) fail("OPENSEARCH_MALFORMED_RESPONSE")
    val groups = parseResponseGroups(groupAggregate, mapping, request, safeBaseUrl)
    val terms = OpenSearchTerms(mapping.groupLimit, groups.size, sumOther, countError)
    val baseCoverage =
        OpenSearchCoverage(
            status = "",
            reasons = emptyList(),
            timedOut = timedOut,
            totalRelation = totalRelation,
            shards = shards,
            terms = terms,
            samplesPerGroupLimit = mapping.samplesPerGroup,
            sampleMessageBytesMax = mapping.sampleMessageBytesMax,
        )
    val partial =
        OpenSearchArtifact(
            loadInputSha256,
            request.profileId,
            request.startEpochMillis,
            request.endEpochMillis,
            request.stepMillis,
            total,
            rate(total, request.endEpochMillis - request.startEpochMillis),
            timeline,
            groups,
            baseCoverage,
        )
    val reasons = coverageReasons(partial)
    val artifact = partial.copy(coverage = baseCoverage.copy(status = coverageStatus(reasons), reasons = reasons))
    validateArtifact(artifact, loadInputSha256)
    return artifact.json().also(::checkArtifactSize)
}

internal fun validateOpenSearchArtifact(
    input: InputStream,
    loadInputSha256: String,
): JsonObject {
    validateHash(loadInputSha256)
    val bytes = readArtifactBytes(input)
    val artifact = parseArtifact(parseJson(bytes, "OPENSEARCH_MALFORMED_ARTIFACT"))
    validateArtifact(artifact, loadInputSha256)
    return artifact.json().also(::checkArtifactSize)
}

private data class OpenSearchTimelineCell(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val count: Long,
    val ratePerMinute: BigDecimal,
)

private data class OpenSearchSample(
    val timestampEpochMillis: Long,
    val index: String,
    val documentId: String,
    val message: String,
    val messageTruncated: Boolean,
    val sourceUrl: String,
)

private data class OpenSearchGroup(
    val service: String,
    val errorType: String,
    val count: Long,
    val firstEpochMillis: Long,
    val lastEpochMillis: Long,
    val samples: List<OpenSearchSample>,
)

private data class OpenSearchShards(
    val total: Long,
    val successful: Long,
    val skipped: Long,
    val failed: Long,
)

private data class OpenSearchTerms(
    val groupLimit: Int,
    val returnedGroups: Int,
    val sumOtherDocCount: Long,
    val docCountErrorUpperBound: Long,
)

private data class OpenSearchCoverage(
    val status: String,
    val reasons: List<String>,
    val timedOut: Boolean,
    val totalRelation: String,
    val shards: OpenSearchShards,
    val terms: OpenSearchTerms,
    val samplesPerGroupLimit: Int,
    val sampleMessageBytesMax: Int,
)

private data class OpenSearchArtifact(
    val loadInputSha256: String,
    val profileId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val stepMillis: Long,
    val totalErrors: Long,
    val errorRatePerMinute: BigDecimal,
    val timeline: List<OpenSearchTimelineCell>,
    val groups: List<OpenSearchGroup>,
    val coverage: OpenSearchCoverage,
)

private fun parseResponseTimeline(
    value: JsonObject,
    request: SourceRequest,
): List<OpenSearchTimelineCell> {
    val buckets = value.arr("buckets", "OPENSEARCH_MALFORMED_RESPONSE")
    if (buckets.size > MAX_CELLS) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    return buckets.map { element ->
        val bucket = element.asObject("OPENSEARCH_MALFORMED_RESPONSE")
        val from = bucket.long("key", "OPENSEARCH_MALFORMED_RESPONSE")
        val count = bucket.nonNegativeLong("doc_count", "OPENSEARCH_MALFORMED_RESPONSE")
        val to =
            try {
                Math.addExact(from, request.stepMillis)
            } catch (_: ArithmeticException) {
                fail("OPENSEARCH_TIMELINE_GRID_INVALID")
            }
        OpenSearchTimelineCell(from, to, count, rate(count, request.stepMillis))
    }
}

private fun parseResponseGroups(
    aggregate: JsonObject,
    mapping: OpenSearchMapping,
    request: SourceRequest,
    baseUrl: URI,
): List<OpenSearchGroup> {
    val buckets = aggregate.arr("buckets", "OPENSEARCH_MALFORMED_RESPONSE")
    if (buckets.size > mapping.groupLimit || buckets.size > MAX_GROUPS) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    return buckets.map { element ->
        val bucket = element.asObject("OPENSEARCH_MALFORMED_RESPONSE")
        val key = bucket.arr("key", "OPENSEARCH_MALFORMED_RESPONSE")
        if (key.size != 2) fail("OPENSEARCH_MALFORMED_RESPONSE")
        val service = key[0].plainString("OPENSEARCH_MALFORMED_RESPONSE", MAX_GROUP_KEY_BYTES)
        val errorType = key[1].plainString("OPENSEARCH_MALFORMED_RESPONSE", MAX_GROUP_KEY_BYTES)
        val count = bucket.positiveLong("doc_count", "OPENSEARCH_MALFORMED_RESPONSE")
        val first = bucket.obj("first_at", "OPENSEARCH_MALFORMED_RESPONSE").long("value", "OPENSEARCH_MALFORMED_RESPONSE")
        val last = bucket.obj("last_at", "OPENSEARCH_MALFORMED_RESPONSE").long("value", "OPENSEARCH_MALFORMED_RESPONSE")
        val samples =
            if (mapping.samplesPerGroup == 0) {
                emptyList()
            } else {
                parseResponseSamples(
                    bucket.obj("samples", "OPENSEARCH_MALFORMED_RESPONSE"),
                    mapping,
                    request,
                    baseUrl,
                )
            }
        OpenSearchGroup(service, errorType, count, first, last, samples)
    }
}

private fun parseResponseSamples(
    aggregate: JsonObject,
    mapping: OpenSearchMapping,
    request: SourceRequest,
    baseUrl: URI,
): List<OpenSearchSample> {
    val hits = aggregate.obj("hits", "OPENSEARCH_MALFORMED_RESPONSE").arr("hits", "OPENSEARCH_MALFORMED_RESPONSE")
    if (hits.size > mapping.samplesPerGroup || hits.size > MAX_SAMPLES_PER_GROUP) {
        fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    }
    return hits.map { element ->
        val hit = element.asObject("OPENSEARCH_MALFORMED_RESPONSE")
        val index = hit.string("_index", "OPENSEARCH_MALFORMED_RESPONSE").also(::validateSourceSegment)
        val documentId = hit.string("_id", "OPENSEARCH_MALFORMED_RESPONSE").also(::validateSourceSegment)
        val sort = hit.arr("sort", "OPENSEARCH_MALFORMED_RESPONSE")
        if (sort.size != 1) fail("OPENSEARCH_MALFORMED_RESPONSE")
        val timestamp = sort.single().long("OPENSEARCH_MALFORMED_RESPONSE")
        if (timestamp !in request.startEpochMillis until request.endEpochMillis) fail("OPENSEARCH_SAMPLE_WINDOW_INVALID")
        val source =
            when (val raw = hit["_source"]) {
                null, JsonNull -> null
                is JsonObject -> raw
                else -> fail("OPENSEARCH_MALFORMED_RESPONSE")
            }
        val originalMessage = source?.dottedString(mapping.messageField).orEmpty()
        val (message, truncated) = truncateUtf8(originalMessage, mapping.sampleMessageBytesMax)
        OpenSearchSample(timestamp, index, documentId, message, truncated, sourceUrl(baseUrl, index, documentId))
    }
}

private fun parseShards(value: JsonObject): OpenSearchShards {
    val total = value.nonNegativeLong("total", "OPENSEARCH_MALFORMED_RESPONSE")
    val successful = value.nonNegativeLong("successful", "OPENSEARCH_MALFORMED_RESPONSE")
    val skipped = value.nonNegativeLong("skipped", "OPENSEARCH_MALFORMED_RESPONSE")
    val failed = value.nonNegativeLong("failed", "OPENSEARCH_MALFORMED_RESPONSE")
    if (successful > total || skipped > successful || failed > total) fail("OPENSEARCH_MALFORMED_RESPONSE")
    return OpenSearchShards(total, successful, skipped, failed)
}

private fun parseArtifact(root: JsonObject): OpenSearchArtifact {
    root.rejectUnknown(
        setOf(
            "schema_version",
            "id",
            "type",
            "load_input_sha256",
            "profile_id",
            "start_epoch_ms",
            "end_epoch_ms",
            "step_ms",
            "total_errors",
            "error_rate_per_minute",
            "timeline",
            "groups",
            "coverage",
        ),
    )
    if (root.string("schema_version", INVALID_ARTIFACT) != SCHEMA_VERSION ||
        root.string("id", INVALID_ARTIFACT) != ARTIFACT_ID ||
        root.string("type", INVALID_ARTIFACT) != ARTIFACT_TYPE
    ) {
        fail(INVALID_ARTIFACT)
    }
    val timeline = root.arr("timeline", INVALID_ARTIFACT)
    val groups = root.arr("groups", INVALID_ARTIFACT)
    if (timeline.size > MAX_CELLS || groups.size > MAX_GROUPS) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    return OpenSearchArtifact(
        loadInputSha256 = root.string("load_input_sha256", INVALID_ARTIFACT),
        profileId = root.string("profile_id", INVALID_ARTIFACT),
        startEpochMillis = root.long("start_epoch_ms", INVALID_ARTIFACT),
        endEpochMillis = root.long("end_epoch_ms", INVALID_ARTIFACT),
        stepMillis = root.long("step_ms", INVALID_ARTIFACT),
        totalErrors = root.nonNegativeLong("total_errors", INVALID_ARTIFACT),
        errorRatePerMinute = root.decimal("error_rate_per_minute", INVALID_ARTIFACT),
        timeline = timeline.map(::parseArtifactTimelineCell),
        groups = groups.map(::parseArtifactGroup),
        coverage = parseArtifactCoverage(root.obj("coverage", INVALID_ARTIFACT)),
    )
}

private fun parseArtifactTimelineCell(element: JsonElement): OpenSearchTimelineCell {
    val value = element.asObject(INVALID_ARTIFACT)
    value.rejectUnknown(setOf("from_epoch_ms", "to_epoch_ms", "count", "rate_per_minute"))
    return OpenSearchTimelineCell(
        value.long("from_epoch_ms", INVALID_ARTIFACT),
        value.long("to_epoch_ms", INVALID_ARTIFACT),
        value.nonNegativeLong("count", INVALID_ARTIFACT),
        value.decimal("rate_per_minute", INVALID_ARTIFACT),
    )
}

private fun parseArtifactGroup(element: JsonElement): OpenSearchGroup {
    val value = element.asObject(INVALID_ARTIFACT)
    value.rejectUnknown(setOf("service", "error_type", "count", "first_epoch_ms", "last_epoch_ms", "samples"))
    val samples = value.arr("samples", INVALID_ARTIFACT)
    if (samples.size > MAX_SAMPLES_PER_GROUP) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    return OpenSearchGroup(
        value.string("service", INVALID_ARTIFACT),
        value.string("error_type", INVALID_ARTIFACT),
        value.positiveLong("count", INVALID_ARTIFACT),
        value.long("first_epoch_ms", INVALID_ARTIFACT),
        value.long("last_epoch_ms", INVALID_ARTIFACT),
        samples.map(::parseArtifactSample),
    )
}

private fun parseArtifactSample(element: JsonElement): OpenSearchSample {
    val value = element.asObject(INVALID_ARTIFACT)
    value.rejectUnknown(
        setOf("timestamp_epoch_ms", "index", "document_id", "message", "message_truncated", "source_url"),
    )
    return OpenSearchSample(
        value.long("timestamp_epoch_ms", INVALID_ARTIFACT),
        value.string("index", INVALID_ARTIFACT),
        value.string("document_id", INVALID_ARTIFACT),
        value.string("message", INVALID_ARTIFACT),
        value.boolean("message_truncated", INVALID_ARTIFACT),
        value.string("source_url", INVALID_ARTIFACT),
    )
}

private fun parseArtifactCoverage(value: JsonObject): OpenSearchCoverage {
    value.rejectUnknown(
        setOf(
            "status",
            "reasons",
            "timed_out",
            "total_relation",
            "shards",
            "terms",
            "samples_per_group_limit",
            "sample_message_bytes_max",
        ),
    )
    val reasons = value.arr("reasons", INVALID_ARTIFACT)
    if (reasons.size > PARTIAL_REASONS.size) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    return OpenSearchCoverage(
        value.string("status", INVALID_ARTIFACT),
        reasons.map { it.plainString(INVALID_ARTIFACT, MAX_REASON_BYTES) },
        value.boolean("timed_out", INVALID_ARTIFACT),
        value.string("total_relation", INVALID_ARTIFACT),
        parseArtifactShards(value.obj("shards", INVALID_ARTIFACT)),
        parseArtifactTerms(value.obj("terms", INVALID_ARTIFACT)),
        value.int("samples_per_group_limit", INVALID_ARTIFACT),
        value.int("sample_message_bytes_max", INVALID_ARTIFACT),
    )
}

private fun parseArtifactShards(value: JsonObject): OpenSearchShards {
    value.rejectUnknown(setOf("total", "successful", "skipped", "failed"))
    return OpenSearchShards(
        value.nonNegativeLong("total", INVALID_ARTIFACT),
        value.nonNegativeLong("successful", INVALID_ARTIFACT),
        value.nonNegativeLong("skipped", INVALID_ARTIFACT),
        value.nonNegativeLong("failed", INVALID_ARTIFACT),
    )
}

private fun parseArtifactTerms(value: JsonObject): OpenSearchTerms {
    value.rejectUnknown(
        setOf("group_limit", "returned_groups", "sum_other_doc_count", "doc_count_error_upper_bound"),
    )
    return OpenSearchTerms(
        value.int("group_limit", INVALID_ARTIFACT),
        value.int("returned_groups", INVALID_ARTIFACT),
        value.nonNegativeLong("sum_other_doc_count", INVALID_ARTIFACT),
        value.long("doc_count_error_upper_bound", INVALID_ARTIFACT),
    )
}

private fun validateArtifact(
    artifact: OpenSearchArtifact,
    expectedLoadInputSha256: String,
) {
    validateHash(artifact.loadInputSha256)
    if (artifact.loadInputSha256 != expectedLoadInputSha256) fail("OPENSEARCH_LOAD_HASH_MISMATCH")
    validatePlainText(artifact.profileId, MAX_PROFILE_ID_BYTES, INVALID_ARTIFACT)
    val request = SourceRequest(artifact.profileId, artifact.startEpochMillis, artifact.endEpochMillis, artifact.stepMillis)
    validateRequest(request)
    if (artifact.timeline.size != ((artifact.endEpochMillis - artifact.startEpochMillis) / artifact.stepMillis).toInt()) {
        fail("OPENSEARCH_TIMELINE_GRID_INVALID")
    }

    var timelineSum = 0L
    artifact.timeline.forEachIndexed { index, cell ->
        val expectedFrom = artifact.startEpochMillis + artifact.stepMillis * index.toLong()
        if (cell.fromEpochMillis != expectedFrom || cell.toEpochMillis != expectedFrom + artifact.stepMillis) {
            fail("OPENSEARCH_TIMELINE_GRID_INVALID")
        }
        if (cell.count < 0 || cell.ratePerMinute.compareTo(rate(cell.count, artifact.stepMillis)) != 0) {
            fail("OPENSEARCH_DERIVED_FIELD_MISMATCH")
        }
        timelineSum = addCount(timelineSum, cell.count)
    }
    if (artifact.totalErrors < 0 ||
        artifact.errorRatePerMinute.compareTo(rate(artifact.totalErrors, artifact.endEpochMillis - artifact.startEpochMillis)) != 0
    ) {
        fail("OPENSEARCH_DERIVED_FIELD_MISMATCH")
    }

    if (artifact.groups.size > MAX_GROUPS) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    val uniqueGroups = HashSet<Pair<String, String>>()
    var groupSum = 0L
    artifact.groups.forEach { group ->
        validatePlainText(group.service, MAX_GROUP_KEY_BYTES, INVALID_ARTIFACT)
        validatePlainText(group.errorType, MAX_GROUP_KEY_BYTES, INVALID_ARTIFACT)
        if (!uniqueGroups.add(group.service to group.errorType)) fail("OPENSEARCH_DUPLICATE_GROUP")
        if (group.count <= 0 ||
            group.firstEpochMillis > group.lastEpochMillis ||
            group.firstEpochMillis !in artifact.startEpochMillis until artifact.endEpochMillis ||
            group.lastEpochMillis !in artifact.startEpochMillis until artifact.endEpochMillis
        ) {
            fail("OPENSEARCH_GROUP_WINDOW_INVALID")
        }
        groupSum = addCount(groupSum, group.count)
        if (group.samples.size > artifact.coverage.samplesPerGroupLimit) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
        group.samples.forEach { sample ->
            if (sample.timestampEpochMillis !in artifact.startEpochMillis until artifact.endEpochMillis) {
                fail("OPENSEARCH_SAMPLE_WINDOW_INVALID")
            }
            validateSourceSegment(sample.index)
            validateSourceSegment(sample.documentId)
            if (sample.message.encodeToByteArray().size > artifact.coverage.sampleMessageBytesMax) {
                fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
            }
            validateSourceUrl(sample.sourceUrl)
        }
    }

    val coverage = artifact.coverage
    if (coverage.totalRelation !in TOTAL_RELATIONS ||
        coverage.shards.total < 0 ||
        coverage.shards.successful < 0 ||
        coverage.shards.skipped < 0 ||
        coverage.shards.failed < 0 ||
        coverage.shards.successful > coverage.shards.total ||
        coverage.shards.skipped > coverage.shards.successful ||
        coverage.shards.failed > coverage.shards.total
    ) {
        fail(INVALID_ARTIFACT)
    }
    if (coverage.terms.groupLimit !in 1..MAX_GROUPS ||
        coverage.terms.returnedGroups != artifact.groups.size ||
        artifact.groups.size > coverage.terms.groupLimit ||
        coverage.terms.sumOtherDocCount < 0 ||
        coverage.terms.docCountErrorUpperBound < -1 ||
        coverage.samplesPerGroupLimit !in 0..MAX_SAMPLES_PER_GROUP ||
        coverage.sampleMessageBytesMax !in 1..MAX_SAMPLE_MESSAGE_BYTES
    ) {
        fail(INVALID_ARTIFACT)
    }
    addCount(groupSum, coverage.terms.sumOtherDocCount)
    if (timelineSum < 0) fail("OPENSEARCH_COUNT_OVERFLOW")
    val expectedReasons = coverageReasons(artifact)
    if (coverage.reasons != expectedReasons || coverage.status != coverageStatus(expectedReasons)) {
        fail("OPENSEARCH_DERIVED_FIELD_MISMATCH")
    }
}

private fun coverageReasons(artifact: OpenSearchArtifact): List<String> {
    val reasons = ArrayList<String>(PARTIAL_REASONS.size)
    val coverage = artifact.coverage
    if (coverage.timedOut) reasons += "OPENSEARCH_TIMED_OUT"
    if (coverage.shards.failed > 0 || coverage.shards.successful < coverage.shards.total) {
        reasons += "OPENSEARCH_SHARDS_INCOMPLETE"
    }
    if (coverage.totalRelation == "gte") reasons += "OPENSEARCH_TOTAL_LOWER_BOUND"
    if (coverage.terms.sumOtherDocCount > 0) reasons += "OPENSEARCH_TERMS_TRUNCATED"
    if (coverage.terms.docCountErrorUpperBound == -1L || coverage.terms.docCountErrorUpperBound > 0) {
        reasons += "OPENSEARCH_TERM_COUNTS_APPROXIMATE"
    }
    val timelineSum = artifact.timeline.fold(0L) { total, cell -> addCount(total, cell.count) }
    if (timelineSum != artifact.totalErrors) reasons += "OPENSEARCH_TIMELINE_COUNT_MISMATCH"
    val groupSum = artifact.groups.fold(0L) { total, group -> addCount(total, group.count) }
    if (addCount(groupSum, coverage.terms.sumOtherDocCount) != artifact.totalErrors) {
        reasons += "OPENSEARCH_GROUP_COUNT_MISMATCH"
    }
    return reasons.sorted()
}

private fun coverageStatus(reasons: List<String>): String = if (reasons.isEmpty()) "COMPLETE" else "PARTIAL"

private fun OpenSearchArtifact.json(): JsonObject =
    buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("id", ARTIFACT_ID)
        put("type", ARTIFACT_TYPE)
        put("load_input_sha256", loadInputSha256)
        put("profile_id", profileId)
        put("start_epoch_ms", startEpochMillis)
        put("end_epoch_ms", endEpochMillis)
        put("step_ms", stepMillis)
        put("total_errors", totalErrors)
        put("error_rate_per_minute", JsonPrimitive(errorRatePerMinute))
        put("timeline", buildJsonArray { timeline.forEach { add(it.json()) } })
        put("groups", buildJsonArray { groups.forEach { add(it.json()) } })
        put("coverage", coverage.json())
    }

private fun OpenSearchTimelineCell.json(): JsonObject =
    buildJsonObject {
        put("from_epoch_ms", fromEpochMillis)
        put("to_epoch_ms", toEpochMillis)
        put("count", count)
        put("rate_per_minute", JsonPrimitive(ratePerMinute))
    }

private fun OpenSearchGroup.json(): JsonObject =
    buildJsonObject {
        put("service", service)
        put("error_type", errorType)
        put("count", count)
        put("first_epoch_ms", firstEpochMillis)
        put("last_epoch_ms", lastEpochMillis)
        put("samples", buildJsonArray { samples.forEach { add(it.json()) } })
    }

private fun OpenSearchSample.json(): JsonObject =
    buildJsonObject {
        put("timestamp_epoch_ms", timestampEpochMillis)
        put("index", index)
        put("document_id", documentId)
        put("message", message)
        put("message_truncated", messageTruncated)
        put("source_url", sourceUrl)
    }

private fun OpenSearchCoverage.json(): JsonObject =
    buildJsonObject {
        put("status", status)
        put("reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("timed_out", timedOut)
        put("total_relation", totalRelation)
        put(
            "shards",
            buildJsonObject {
                put("total", shards.total)
                put("successful", shards.successful)
                put("skipped", shards.skipped)
                put("failed", shards.failed)
            },
        )
        put(
            "terms",
            buildJsonObject {
                put("group_limit", terms.groupLimit)
                put("returned_groups", terms.returnedGroups)
                put("sum_other_doc_count", terms.sumOtherDocCount)
                put("doc_count_error_upper_bound", terms.docCountErrorUpperBound)
            },
        )
        put("samples_per_group_limit", samplesPerGroupLimit)
        put("sample_message_bytes_max", sampleMessageBytesMax)
    }

private fun metricAggregation(
    operation: String,
    field: String,
): JsonObject = buildJsonObject { put(operation, buildJsonObject { put("field", field) }) }

private fun rate(
    count: Long,
    durationMillis: Long,
): BigDecimal {
    val normalized =
        BigDecimal
            .valueOf(count)
            .multiply(MILLIS_PER_MINUTE)
            .divide(BigDecimal.valueOf(durationMillis), RATE_SCALE, RoundingMode.HALF_UP)
            .stripTrailingZeros()
    return if (normalized.scale() < 0) normalized.setScale(0) else normalized
}

private fun addCount(
    left: Long,
    right: Long,
): Long =
    try {
        Math.addExact(left, right)
    } catch (_: ArithmeticException) {
        fail("OPENSEARCH_COUNT_OVERFLOW")
    }

private fun validateRequest(request: SourceRequest) {
    validatePlainText(request.profileId, MAX_PROFILE_ID_BYTES, "OPENSEARCH_INVALID_REQUEST")
    if (request.startEpochMillis !in 0 until MAX_TIMESTAMP_EPOCH_MILLIS ||
        request.endEpochMillis !in 1..MAX_TIMESTAMP_EPOCH_MILLIS ||
        request.endEpochMillis <= request.startEpochMillis ||
        request.stepMillis < 1_000
    ) {
        fail("OPENSEARCH_INVALID_REQUEST")
    }
    val duration = request.endEpochMillis - request.startEpochMillis
    if (duration % request.stepMillis != 0L || duration / request.stepMillis !in 1..MAX_CELLS.toLong()) {
        fail("OPENSEARCH_INVALID_REQUEST")
    }
}

private fun validateHash(value: String) {
    if (!SHA256.matches(value)) fail("OPENSEARCH_INVALID_LOAD_HASH")
}

private fun validIndex(value: String): Boolean =
    value.encodeToByteArray().size in 1..MAX_INDEX_BYTES &&
        INDEX.matches(value) &&
        value != "_all" &&
        value.any { it != '*' } &&
        ".." !in value &&
        value.first() !in "-._"

private fun validField(value: String): Boolean =
    value.encodeToByteArray().size in 1..MAX_FIELD_BYTES && FIELD.matches(value) && !value.any(Char::isISOControl)

private fun invalidMapping(): Nothing = fail("OPENSEARCH_INVALID_MAPPING")

private fun validatePlainText(
    value: String,
    maxBytes: Int,
    code: String,
) {
    if (value.isEmpty() || value.any(Char::isISOControl) || value.encodeToByteArray().size > maxBytes) fail(code)
}

private fun validateSourceSegment(value: String) {
    validatePlainText(value, MAX_SOURCE_SEGMENT_BYTES, "OPENSEARCH_UNSAFE_SOURCE_SEGMENT")
    if (value == "." || value == "..") fail("OPENSEARCH_UNSAFE_SOURCE_SEGMENT")
}

private fun safeBaseUrl(value: URI): URI {
    validateHttpUri(value, requireDocumentPath = false)
    if ('%' in value.rawPath.orEmpty()) fail("OPENSEARCH_UNSAFE_BASE_URL")
    val path = value.path.orEmpty().trimEnd('/')
    return try {
        URI(value.scheme.lowercase(), null, value.host.lowercase(), value.port, path, null, null)
    } catch (_: Exception) {
        fail("OPENSEARCH_UNSAFE_BASE_URL")
    }
}

private fun sourceUrl(
    baseUrl: URI,
    index: String,
    documentId: String,
): String = "${baseUrl.toASCIIString().trimEnd('/')}/${encodePathSegment(index)}/_doc/${encodePathSegment(documentId)}"

private fun encodePathSegment(value: String): String =
    buildString {
        value.encodeToByteArray().forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            if ((unsigned in 'a'.code..'z'.code) ||
                (unsigned in 'A'.code..'Z'.code) ||
                (unsigned in '0'.code..'9'.code) ||
                unsigned == '-'.code ||
                unsigned == '.'.code ||
                unsigned == '_'.code ||
                unsigned == '~'.code
            ) {
                append(unsigned.toChar())
            } else {
                append('%')
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

private fun validateSourceUrl(value: String) {
    validatePlainText(value, MAX_SOURCE_URL_BYTES, "OPENSEARCH_UNSAFE_SOURCE_URL")
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            fail("OPENSEARCH_UNSAFE_SOURCE_URL")
        }
    validateHttpUri(uri, requireDocumentPath = true)
}

private fun validateHttpUri(
    uri: URI,
    requireDocumentPath: Boolean,
) {
    val unsafeCode = if (requireDocumentPath) "OPENSEARCH_UNSAFE_SOURCE_URL" else "OPENSEARCH_UNSAFE_BASE_URL"
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https") ||
        uri.isOpaque ||
        uri.host == null ||
        uri.port == 0 ||
        uri.port > 65_535 ||
        uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        uri.toString().any(Char::isISOControl)
    ) {
        fail(unsafeCode)
    }
    val segments =
        uri.rawPath
            .orEmpty()
            .split('/')
            .filter(String::isNotEmpty)
    if (segments.any(::isUrlDotSegment) || ENCODED_CONTROL.containsMatchIn(uri.rawPath.orEmpty())) fail(unsafeCode)
    if (requireDocumentPath &&
        (segments.size < 3 || segments[segments.lastIndex - 1] != "_doc" || segments[segments.lastIndex - 2].isEmpty())
    ) {
        fail(unsafeCode)
    }
}

private fun isUrlDotSegment(segment: String): Boolean =
    segment.replace(ENCODED_DOT, ".") == "." || segment.replace(ENCODED_DOT, ".") == ".."

private fun JsonObject.dottedString(path: String): String? {
    var value: JsonElement = this
    path.split('.').forEach { segment ->
        val objectValue = value as? JsonObject ?: fail("OPENSEARCH_MALFORMED_RESPONSE")
        value = objectValue[segment] ?: return null
    }
    if (value === JsonNull) return null
    return value.string("OPENSEARCH_MALFORMED_RESPONSE")
}

private fun truncateUtf8(
    value: String,
    maxBytes: Int,
): Pair<String, Boolean> {
    val bytes = value.encodeToByteArray()
    if (bytes.size <= maxBytes) return value to false
    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
    return String(bytes, 0, end, StandardCharsets.UTF_8) to true
}

private fun readArtifactBytes(input: InputStream): ByteArray =
    try {
        input.readNBytes(MAX_ARTIFACT_BYTES + 1).also {
            if (it.size > MAX_ARTIFACT_BYTES) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
        }
    } catch (failure: IllegalArgumentException) {
        throw failure
    } catch (_: IOException) {
        fail("OPENSEARCH_ARTIFACT_READ_ERROR")
    }

private fun parseJson(
    bytes: ByteArray,
    malformedCode: String,
): JsonObject {
    if (bytes.size > MAX_ARTIFACT_BYTES) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
    val text = decodeUtf8(bytes)
    StrictJsonScanner(
        text,
        JSON_DEPTH_MAX,
        NUMERIC_TOKEN_BYTES_MAX,
        NUMERIC_EXPONENT_ABS_MAX,
        "OpenSearch JSON",
    ) { code, _, _ ->
        when (code) {
            "DUPLICATE_OBJECT_KEY" -> fail("OPENSEARCH_DUPLICATE_OBJECT_KEY")
            "RESOURCE_LIMIT_EXCEEDED" -> fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
            else -> fail(malformedCode)
        }
    }.scan()
    return try {
        Json.parseToJsonElement(text) as? JsonObject ?: fail(malformedCode)
    } catch (_: SerializationException) {
        fail(malformedCode)
    }
}

private fun decodeUtf8(bytes: ByteArray): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        fail("OPENSEARCH_INVALID_UTF8")
    }

private fun checkArtifactSize(value: JsonObject) {
    if (canonicalJson(value).size > MAX_ARTIFACT_BYTES) fail("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED")
}

private fun JsonObject.rejectUnknown(allowed: Set<String>) {
    if (keys.any { it !in allowed }) fail("OPENSEARCH_UNKNOWN_FIELD")
}

private fun JsonObject.required(
    name: String,
    code: String,
): JsonElement = this[name] ?: fail(code)

private fun JsonObject.obj(
    name: String,
    code: String,
): JsonObject = required(name, code).asObject(code)

private fun JsonObject.arr(
    name: String,
    code: String,
): JsonArray = required(name, code) as? JsonArray ?: fail(code)

private fun JsonObject.string(
    name: String,
    code: String,
): String = required(name, code).string(code)

private fun JsonObject.boolean(
    name: String,
    code: String,
): Boolean {
    val value = required(name, code) as? JsonPrimitive ?: fail(code)
    if (value.isString) fail(code)
    return value.content.toBooleanStrictOrNull() ?: fail(code)
}

private fun JsonObject.decimal(
    name: String,
    code: String,
): BigDecimal = required(name, code).decimal(code)

private fun JsonObject.long(
    name: String,
    code: String,
): Long = required(name, code).long(code)

private fun JsonObject.nonNegativeLong(
    name: String,
    code: String,
): Long = long(name, code).also { if (it < 0) fail(code) }

private fun JsonObject.positiveLong(
    name: String,
    code: String,
): Long = long(name, code).also { if (it <= 0) fail(code) }

private fun JsonObject.int(
    name: String,
    code: String,
): Int {
    val value = long(name, code)
    return value.toInt().takeIf { it.toLong() == value } ?: fail(code)
}

private fun JsonElement.asObject(code: String): JsonObject = this as? JsonObject ?: fail(code)

private fun JsonElement.string(code: String): String {
    val value = this as? JsonPrimitive ?: fail(code)
    if (!value.isString) fail(code)
    return value.content
}

private fun JsonElement.plainString(
    code: String,
    maxBytes: Int,
): String = string(code).also { validatePlainText(it, maxBytes, code) }

private fun JsonElement.decimal(code: String): BigDecimal {
    val value = this as? JsonPrimitive ?: fail(code)
    if (value.isString || value === JsonNull || value.content in setOf("true", "false")) fail(code)
    return try {
        BigDecimal(value.content)
    } catch (_: NumberFormatException) {
        fail(code)
    }
}

private fun JsonElement.long(code: String): Long =
    try {
        decimal(code).longValueExact()
    } catch (_: ArithmeticException) {
        fail(code)
    }

private fun fail(code: String): Nothing = throw IllegalArgumentException(code)

private const val SCHEMA_VERSION = "opensearch-errors.v1"
private const val ARTIFACT_ID = "opensearch-errors:errors"
private const val ARTIFACT_TYPE = "opensearch_errors"
private const val INVALID_ARTIFACT = "OPENSEARCH_INVALID_ARTIFACT"
private const val MAX_ARTIFACT_BYTES = 16 * 1024 * 1024
private const val MAX_CELLS = 100_000
private const val MAX_GROUPS = 200
private const val MAX_SAMPLES_PER_GROUP = 5
private const val MAX_SAMPLE_MESSAGE_BYTES = 65_536
private const val MAX_INDICES = 16
private const val MAX_INDEX_BYTES = 128
private const val MAX_FIELD_BYTES = 128
private const val MAX_PROFILE_ID_BYTES = 128
private const val MAX_GROUP_KEY_BYTES = 1_024
private const val MAX_SOURCE_SEGMENT_BYTES = 1_024
private const val MAX_SOURCE_URL_BYTES = 8_192
private const val MAX_REASON_BYTES = 128
private const val JSON_DEPTH_MAX = 128
private const val NUMERIC_TOKEN_BYTES_MAX = 64
private const val NUMERIC_EXPONENT_ABS_MAX = 64
private const val RATE_SCALE = 6
private val MILLIS_PER_MINUTE = BigDecimal.valueOf(60_000)
private val SHA256 = Regex("[0-9a-f]{64}")
private val INDEX = Regex("[a-z0-9._*-]+")
private val FIELD = Regex("[A-Za-z_@][A-Za-z0-9_@-]*(\\.[A-Za-z_@][A-Za-z0-9_@-]*)*")
private val TOTAL_RELATIONS = setOf("eq", "gte")
private val ENCODED_DOT = Regex("%2e", RegexOption.IGNORE_CASE)
private val ENCODED_CONTROL = Regex("%(?:[01][0-9a-f]|7f)", RegexOption.IGNORE_CASE)
private val PARTIAL_REASONS =
    setOf(
        "OPENSEARCH_TIMED_OUT",
        "OPENSEARCH_SHARDS_INCOMPLETE",
        "OPENSEARCH_TOTAL_LOWER_BOUND",
        "OPENSEARCH_TERMS_TRUNCATED",
        "OPENSEARCH_TERM_COUNTS_APPROXIMATE",
        "OPENSEARCH_TIMELINE_COUNT_MISMATCH",
        "OPENSEARCH_GROUP_COUNT_MISMATCH",
    )
private const val HEX = "0123456789ABCDEF"

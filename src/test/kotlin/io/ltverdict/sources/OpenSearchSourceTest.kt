package io.ltverdict.sources

import io.ltverdict.core.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class OpenSearchSourceTest {
    @Test
    fun `query fixes the range aligned aggregations and endpoint independent shape`() {
        val request = SourceRequest("errors", 1_500, 3_500, 1_000)
        val mapping = mapping(samplesPerGroup = 2)
        val query = buildOpenSearchQuery(mapping, request, 12_345)
        val expected =
            json(
                """
                {
                  "size":0,
                  "track_total_hits":true,
                  "timeout":"12345ms",
                  "query":{"bool":{"filter":[
                    {"range":{"@timestamp":{"gte":1500,"lt":3500,"format":"epoch_millis"}}},
                    {"exists":{"field":"error.type"}}
                  ]}},
                  "aggs":{
                    "timeline":{"date_histogram":{"field":"@timestamp","fixed_interval":"1000ms","offset":"500ms","min_doc_count":0,"extended_bounds":{"min":1500,"max":2500}}},
                    "groups":{"multi_terms":{"terms":[{"field":"service.name"},{"field":"error.type"}],"size":50,"order":{"_count":"desc"}},"aggs":{
                      "first_at":{"min":{"field":"@timestamp"}},
                      "last_at":{"max":{"field":"@timestamp"}},
                      "samples":{"top_hits":{"size":2,"sort":[{"@timestamp":{"order":"asc"}}],"_source":{"includes":["error.message"]}}}
                    }}
                  }
                }
                """.trimIndent(),
            )

        assertEquals(expected, Json.parseToJsonElement(query.decodeToString()))
        assertArrayEquals(
            query,
            buildOpenSearchQuery(mapping.copy(indices = listOf("another-*")), request, 12_345),
            "the fixed HTTP endpoint owns indices, not the JSON query",
        )

        val withoutSamples = json(buildOpenSearchQuery(mapping(samplesPerGroup = 0), request, 12_345).decodeToString())
        assertFalse("samples" in withoutSamples.obj("aggs").obj("groups").obj("aggs"))
    }

    @Test
    fun `query accepts any dividing step of at least one second`() {
        // OpenSearch не строит snapshot grid, поэтому шаг 90 s остаётся допустимым, а не кратным минуте.
        val query = buildOpenSearchQuery(mapping(), SourceRequest("errors", 0, 180_000, 90_000), 12_345)

        assertTrue("\"fixed_interval\":\"90000ms\"" in query.decodeToString())
        assertCode("OPENSEARCH_INVALID_REQUEST") { buildOpenSearchQuery(mapping(), SourceRequest("errors", 0, 180_000, 7_000), 12_345) }
    }

    @Test
    fun `mapping rejects unsafe or unbounded index field and aggregate settings`() {
        val valid = mapping()
        val invalid =
            listOf(
                { valid.copy(indices = emptyList()) },
                { valid.copy(indices = List(17) { "logs-$it" }) },
                { valid.copy(indices = listOf("Logs-*")) },
                { valid.copy(indices = listOf("*")) },
                { valid.copy(indices = listOf("**")) },
                { valid.copy(indices = listOf("***")) },
                { valid.copy(indices = listOf("_all")) },
                { valid.copy(indices = listOf("logs..old")) },
                { valid.copy(indices = listOf("logs,old")) },
                { valid.copy(indices = listOf("-logs")) },
                { valid.copy(indices = listOf(".logs")) },
                { valid.copy(indices = listOf("_logs")) },
                { valid.copy(indices = listOf("logs/path")) },
                { valid.copy(indices = listOf("x".repeat(129))) },
                { valid.copy(timestampField = "") },
                { valid.copy(serviceField = "service..name") },
                { valid.copy(errorTypeField = "error\ntype") },
                { valid.copy(messageField = "message/path") },
                { valid.copy(groupLimit = 0) },
                { valid.copy(groupLimit = 201) },
                { valid.copy(samplesPerGroup = -1) },
                { valid.copy(samplesPerGroup = 6) },
                { valid.copy(sampleMessageBytesMax = 0) },
                { valid.copy(sampleMessageBytesMax = 65_537) },
            )

        invalid.forEachIndexed { index, construct ->
            assertCode("OPENSEARCH_INVALID_MAPPING", index.toString(), construct)
        }
        assertEquals("@timestamp", valid.timestampField)
    }

    @Test
    fun `decoder normalizes literal complete aggregate and strict import roundtrips`() {
        val artifact = decode(completeResponse())
        val expected =
            json(
                """
                {
                  "schema_version":"opensearch-errors.v1",
                  "id":"opensearch-errors:errors",
                  "type":"opensearch_errors",
                  "load_input_sha256":"$HASH",
                  "profile_id":"errors",
                  "start_epoch_ms":1000,
                  "end_epoch_ms":3000,
                  "step_ms":1000,
                  "total_errors":3,
                  "error_rate_per_minute":90,
                  "timeline":[
                    {"from_epoch_ms":1000,"to_epoch_ms":2000,"count":1,"rate_per_minute":60},
                    {"from_epoch_ms":2000,"to_epoch_ms":3000,"count":2,"rate_per_minute":120}
                  ],
                  "groups":[{"service":"api","error_type":"Timeout","count":3,"first_epoch_ms":1100,"last_epoch_ms":2900,"samples":[]}],
                  "coverage":{
                    "status":"COMPLETE","reasons":[],"timed_out":false,"total_relation":"eq",
                    "shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                    "terms":{"group_limit":50,"returned_groups":1,"sum_other_doc_count":0,"doc_count_error_upper_bound":0},
                    "samples_per_group_limit":0,"sample_message_bytes_max":4096
                  }
                }
                """.trimIndent(),
            )

        assertEquals(expected, artifact)
        assertEquals(
            artifact,
            validateOpenSearchArtifact(ByteArrayInputStream(canonicalJson(artifact)), HASH),
        )
    }

    @Test
    fun `samples use numeric sort dotted messages utf8 truncation and escaped derived urls`() {
        val groups =
            """
            [{
              "key":["api","Timeout"],"doc_count":2,
              "first_at":{"value":1100},"last_at":{"value":2100},
              "samples":{"hits":{"total":{"value":2,"relation":"eq"},"hits":[
                {"_index":"logs/2026","_id":"a b/ç?","sort":[1100],"_source":{"error":{"message":"ééé"},"url":"javascript:alert(1)"}},
                {"_index":"logs-2026","_id":"missing","sort":[2100],"_source":{}}
              ]}}
            }]
            """.trimIndent()
        val artifact =
            decode(
                response(
                    total = 2,
                    timeline = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":1}]""",
                    groups = groups,
                ),
                mapping(samplesPerGroup = 2, messageBytesMax = 5),
                URI.create("https://search.example/base/"),
            )
        val samples =
            artifact
                .arr("groups")
                .single()
                .jsonObject
                .arr("samples")
        val first = samples[0].jsonObject
        val second = samples[1].jsonObject

        assertEquals(1100L, first.long("timestamp_epoch_ms"))
        assertEquals("éé", first.string("message"))
        assertTrue(first.boolean("message_truncated"))
        assertEquals(
            "https://search.example/base/logs%2F2026/_doc/a%20b%2F%C3%A7%3F",
            first.string("source_url"),
        )
        assertEquals("", second.string("message"))
        assertFalse(second.boolean("message_truncated"))
        assertFalse(canonicalJson(artifact).decodeToString().contains("javascript"))
        assertEquals(artifact, validateOpenSearchArtifact(ByteArrayInputStream(canonicalJson(artifact)), HASH))

        listOf(
            groups.replace("\"logs/2026\"", "\"..\""),
            groups.replace("\"a b/ç?\"", "\".\""),
        ).forEach { dotSegment ->
            assertCode("OPENSEARCH_UNSAFE_SOURCE_SEGMENT") {
                decode(
                    response(
                        total = 2,
                        timeline = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":1}]""",
                        groups = dotSegment,
                    ),
                    mapping(samplesPerGroup = 2, messageBytesMax = 5),
                )
            }
        }
    }

    @Test
    fun `raw messages above the configured maximum are truncated before artifact validation`() {
        val oversized = "x".repeat(65_537)
        val groups =
            """
            [{
              "key":["api","Timeout"],"doc_count":3,
              "first_at":{"value":1100},"last_at":{"value":2900},
              "samples":{"hits":{"hits":[
                {"_index":"logs","_id":"1","sort":[1100],"_source":{"error":{"message":"$oversized"}}}
              ]}}
            }]
            """.trimIndent()

        val sample =
            decode(
                response(groups = groups),
                mapping(samplesPerGroup = 1, messageBytesMax = 65_536),
            ).arr("groups").single().jsonObject.arr("samples").single().jsonObject

        assertEquals(65_536, sample.string("message").encodeToByteArray().size)
        assertTrue(sample.boolean("message_truncated"))
    }

    @Test
    fun `coverage records every partial cause independently and skipped shards alone stay complete`() {
        val cases =
            listOf(
                PartialCase(response(timedOut = true), "OPENSEARCH_TIMED_OUT"),
                PartialCase(response(shardsTotal = 2, shardsSuccessful = 1, shardsFailed = 1), "OPENSEARCH_SHARDS_INCOMPLETE"),
                PartialCase(response(relation = "gte"), "OPENSEARCH_TOTAL_LOWER_BOUND"),
                PartialCase(
                    response(
                        groupCount = 2,
                        sumOther = 1,
                    ),
                    "OPENSEARCH_TERMS_TRUNCATED",
                ),
                PartialCase(response(docCountError = -1), "OPENSEARCH_TERM_COUNTS_APPROXIMATE"),
                PartialCase(
                    response(timeline = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":1}]"""),
                    "OPENSEARCH_TIMELINE_COUNT_MISMATCH",
                ),
                PartialCase(response(groupCount = 2), "OPENSEARCH_GROUP_COUNT_MISMATCH"),
            )

        cases.forEach { case ->
            val coverage = decode(case.response).obj("coverage")
            assertEquals("PARTIAL", coverage.string("status"), case.reason)
            assertEquals(listOf(case.reason), coverage.strings("reasons"), case.reason)
        }

        val skipped = decode(response(shardsSkipped = 1)).obj("coverage")
        assertEquals("COMPLETE", skipped.string("status"))
        assertEquals(emptyList<String>(), skipped.strings("reasons"))

        val everyReason =
            decode(
                response(
                    timedOut = true,
                    relation = "gte",
                    shardsTotal = 2,
                    shardsSuccessful = 1,
                    shardsFailed = 1,
                    timeline = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":1}]""",
                    groupCount = 1,
                    sumOther = 1,
                    docCountError = -1,
                ),
            ).obj("coverage").strings("reasons")
        assertEquals(everyReason.sorted(), everyReason)
        assertEquals(7, everyReason.size)
    }

    @Test
    fun `response boundary rejects payload errors malformed json limits overflow and invalid grids`() {
        val overflowTimeline =
            """[{"key":1000,"doc_count":9223372036854775807},{"key":2000,"doc_count":1}]"""
        val duplicateGroup =
            """
            [
              {"key":["api","Timeout"],"doc_count":1,"first_at":{"value":1100},"last_at":{"value":1200}},
              {"key":["api","Timeout"],"doc_count":2,"first_at":{"value":2100},"last_at":{"value":2200}}
            ]
            """.trimIndent()
        val cases =
            listOf(
                DecodeCase("""{"error":{"reason":"secret"},"status":400}""", "OPENSEARCH_ERROR_RESPONSE"),
                DecodeCase("{", "OPENSEARCH_MALFORMED_RESPONSE"),
                DecodeCase(
                    completeResponse().replaceFirst("\"timed_out\":false", "\"timed_out\":false,\"\\u0074imed_out\":false"),
                    "OPENSEARCH_DUPLICATE_OBJECT_KEY",
                ),
                DecodeCase(completeResponse().dropLast(1) + ",\"unused\":1e65}", "OPENSEARCH_RESOURCE_LIMIT_EXCEEDED"),
                DecodeCase(response(timeline = overflowTimeline, total = Long.MAX_VALUE), "OPENSEARCH_COUNT_OVERFLOW"),
                DecodeCase(
                    response(timeline = """[{"key":1000,"doc_count":1},{"key":1000,"doc_count":2}]"""),
                    "OPENSEARCH_TIMELINE_GRID_INVALID",
                ),
                DecodeCase(response(groups = duplicateGroup), "OPENSEARCH_DUPLICATE_GROUP"),
                DecodeCase(
                    response(
                        groups =
                            """[{"key":["api","Timeout"],"doc_count":3,"first_at":{"value":2900},"last_at":{"value":1100}}]""",
                    ),
                    "OPENSEARCH_GROUP_WINDOW_INVALID",
                ),
            )

        cases.forEach { case -> assertCode(case.code, case.code) { decode(case.response) } }
        assertCode("OPENSEARCH_INVALID_UTF8") {
            decodeOpenSearchResponse(byteArrayOf(0xC3.toByte(), 0x28), mapping(), REQUEST, HASH, BASE_URL)
        }

        val withMetadata = completeResponse().dropLast(1) + ",\"pit_id\":\"opaque\",\"_clusters\":{\"total\":1}}"
        assertEquals(3L, decode(withMetadata).long("total_errors"))
    }

    @Test
    fun `strict import rejects unknown duplicate grid hash url and derived field tampering`() {
        val artifact = decode(completeResponse())
        val raw = canonicalJson(artifact).decodeToString()
        val withSample =
            decode(
                response(
                    groups =
                        """[{"key":["api","Timeout"],"doc_count":3,"first_at":{"value":1100},"last_at":{"value":2900},"samples":{"hits":{"hits":[{"_index":"logs","_id":"1","sort":[1100],"_source":{"error":{"message":"boom"}}}]}}}]""",
                ),
                mapping(samplesPerGroup = 1),
            )
        val unsafeUrl = canonicalJson(withSample).decodeToString().replace("https://search.example/logs/_doc/1", "javascript:alert(1)")
        val cases =
            listOf(
                ImportCase(canonicalJson(JsonObject(artifact + ("extra" to JsonPrimitive(true)))), "OPENSEARCH_UNKNOWN_FIELD"),
                ImportCase(
                    raw
                        .replaceFirst(
                            "\"schema_version\":\"opensearch-errors.v1\"",
                            "\"schema_version\":\"opensearch-errors.v1\",\"\\u0073chema_version\":\"opensearch-errors.v1\"",
                        ).encodeToByteArray(),
                    "OPENSEARCH_DUPLICATE_OBJECT_KEY",
                ),
                ImportCase(
                    raw.replace("\"to_epoch_ms\":2000", "\"to_epoch_ms\":1999").encodeToByteArray(),
                    "OPENSEARCH_TIMELINE_GRID_INVALID",
                ),
                ImportCase(
                    raw.replace("\"error_rate_per_minute\":90", "\"error_rate_per_minute\":91").encodeToByteArray(),
                    "OPENSEARCH_DERIVED_FIELD_MISMATCH",
                ),
                ImportCase(
                    raw.replace("\"status\":\"COMPLETE\"", "\"status\":\"PARTIAL\"").encodeToByteArray(),
                    "OPENSEARCH_DERIVED_FIELD_MISMATCH",
                ),
                ImportCase(unsafeUrl.encodeToByteArray(), "OPENSEARCH_UNSAFE_SOURCE_URL"),
            )

        cases.forEach { case ->
            assertCode(case.code, case.code) { validateOpenSearchArtifact(ByteArrayInputStream(case.bytes), HASH) }
        }
        assertCode("OPENSEARCH_LOAD_HASH_MISMATCH") {
            validateOpenSearchArtifact(ByteArrayInputStream(canonicalJson(artifact)), "b".repeat(64))
        }
        assertCode("OPENSEARCH_RESOURCE_LIMIT_EXCEEDED") {
            validateOpenSearchArtifact(ByteArrayInputStream(ByteArray(16 * 1024 * 1024 + 1) { ' '.code.toByte() }), HASH)
        }
    }

    @Test
    fun `canonical contract example is accepted`() {
        val example = Files.readAllBytes(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))

        val artifact = validateOpenSearchArtifact(ByteArrayInputStream(example), HASH)

        assertEquals("opensearch-errors.v1", artifact.string("schema_version"))
        assertEquals(3L, artifact.long("total_errors"))
    }

    private fun decode(
        response: String,
        mapping: OpenSearchMapping = mapping(),
        baseUrl: URI = BASE_URL,
    ): JsonObject = decodeOpenSearchResponse(response.encodeToByteArray(), mapping, REQUEST, HASH, baseUrl)

    private fun mapping(
        samplesPerGroup: Int = 0,
        messageBytesMax: Int = 4_096,
    ) = OpenSearchMapping(
        indices = listOf("logs-*"),
        timestampField = "@timestamp",
        serviceField = "service.name",
        errorTypeField = "error.type",
        messageField = "error.message",
        samplesPerGroup = samplesPerGroup,
        sampleMessageBytesMax = messageBytesMax,
    )

    private fun completeResponse(): String = response()

    private fun response(
        timedOut: Boolean = false,
        total: Long = 3,
        relation: String = "eq",
        shardsTotal: Long = 1,
        shardsSuccessful: Long = 1,
        shardsSkipped: Long = 0,
        shardsFailed: Long = 0,
        timeline: String = """[{"key":1000,"doc_count":1},{"key":2000,"doc_count":2}]""",
        groupCount: Long = 3,
        sumOther: Long = 0,
        docCountError: Long = 0,
        groups: String =
            """[{"key":["api","Timeout"],"doc_count":$groupCount,"first_at":{"value":1100},"last_at":{"value":2900}}]""",
    ): String =
        """
        {
          "took":4,
          "timed_out":$timedOut,
          "_shards":{"total":$shardsTotal,"successful":$shardsSuccessful,"skipped":$shardsSkipped,"failed":$shardsFailed},
          "hits":{"total":{"value":$total,"relation":"$relation"},"max_score":null,"hits":[]},
          "aggregations":{
            "timeline":{"buckets":$timeline},
            "groups":{"doc_count_error_upper_bound":$docCountError,"sum_other_doc_count":$sumOther,"buckets":$groups}
          }
        }
        """.trimIndent()

    private fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

    private fun JsonObject.arr(name: String): JsonArray = getValue(name).jsonArray

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()

    private fun JsonObject.strings(name: String): List<String> = arr(name).map { it.jsonPrimitive.content }

    private fun assertCode(
        code: String,
        message: String = code,
        action: () -> Any?,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java, { action() }, message)
        assertEquals(code, failure.message, message)
    }

    private data class PartialCase(
        val response: String,
        val reason: String,
    )

    private data class DecodeCase(
        val response: String,
        val code: String,
    )

    private data class ImportCase(
        val bytes: ByteArray,
        val code: String,
    )

    private companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val BASE_URL: URI = URI.create("https://search.example")
        val REQUEST = SourceRequest("errors", 1_000, 3_000, 1_000)
    }
}

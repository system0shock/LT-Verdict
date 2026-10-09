package io.ltverdict.sources

import com.sun.net.httpserver.HttpServer
import io.ltverdict.core.ResourceSnapshotV1
import io.ltverdict.core.ResourceWindowV1
import io.ltverdict.core.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.net.URI

/** A local source that answers Prometheus and OpenSearch requests with the status and body the test scripts. */
internal class ScriptedSource : AutoCloseable {
    @Volatile var promStatus = 200

    @Volatile var promBody = ""

    @Volatile var osStatus = 200

    @Volatile var osBody = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val prom = exchange.requestURI.path.startsWith("/api/v1/query_range")
            val bytes = (if (prom) promBody else osBody).encodeToByteArray()
            exchange.sendResponseHeaders(if (prom) promStatus else osStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    val baseUrl: URI get() = URI.create("http://127.0.0.1:${server.address.port}")

    private val governor =
        SourceGovernor(requestsPerSecond = 1000.0, burst = 1000, maxConcurrent = 4, timeoutMillis = 30_000, maxAttempts = 1)

    fun prometheus(
        id: String = "local",
        queryIds: List<String> = listOf("cpu"),
        arm: String? = null,
        spans: Map<String, Long> = emptyMap(),
        maxRequests: Int? = null,
        kind: SourceKind = SourceKind.PROMETHEUS,
    ): SourceProfile {
        val queries =
            queryIds.joinToString(",") {
                """{"id":"$it","expression":"avg_over_time($it[${'$'}__interval])","metric":"${it}_used","unit":"ratio","entity":"host",
                "role":"system","aggregation":"interval_mean","labels":{"instance":"host"}}"""
            }
        val json =
            """{"schema_version":"source-connections.v1","connections":[{"id":"$id","source_kind":"prometheus","transport":"direct",
              "base_url":"$baseUrl","governor":{"requests_per_second":100,"timeout_ms":1000,"max_attempts":1},"queries":[$queries],
              "rules":[{"id":"cpu-high","series_id":"${queryIds.first()}","unit":"ratio","operator":"gt","threshold":0.8,
              "min_consecutive_cells":1,"effect":"sla"}]}]}"""
        return readSourceProfiles(json.byteInputStream())
            .single()
            .copy(
                arm = arm,
                ruleSpansMillis = spans,
                sourceKind = kind,
                governor = governor.copy(maxRequestsPerRun = maxRequests),
            )
    }

    fun openSearch(
        id: String = "errors",
        samplesPerGroup: Int = 0,
        messageBytesMax: Int = 4_096,
        maxRequests: Int? = null,
    ): SourceProfile =
        SourceProfile(
            id,
            SourceKind.OPENSEARCH,
            SourceTransport.DIRECT,
            baseUrl,
            null,
            governor = governor.copy(maxRequestsPerRun = maxRequests),
            queries = emptyList(),
            openSearch =
                OpenSearchMapping(
                    listOf("application-errors-*"),
                    "@timestamp",
                    "service",
                    "type",
                    "message",
                    samplesPerGroup = samplesPerGroup,
                    sampleMessageBytesMax = messageBytesMax,
                ),
        )

    fun source(vararg profiles: SourceProfile): PromqlSource = PromqlSource(profiles.toList(), SourceHttp(profiles.toList()))

    override fun close() = server.stop(0)

    companion object {
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val START = 1_767_225_600_000L

        /** A Prometheus matrix answer with one value per listed second (the first cell ends one step after the start). */
        fun matrix(values: List<String>): String {
            val points = values.mapIndexed { index, value -> """[${START / 1000 + 1 + index},"$value"]""" }.joinToString(",")
            return """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"host"},"values":[$points]}]}}"""
        }

        /** An OpenSearch answer for [cells] cells of 1000 ms from [START]; [counts] are the cell counts, one group holds all of them. */
        fun openSearchBody(
            counts: List<Long>,
            sample: Boolean = false,
            relation: String = "eq",
            timedOut: Boolean = false,
        ): String {
            val total = counts.sum()
            val timeline =
                counts
                    .mapIndexed {
                        index,
                        count,
                        ->
                        """{"key":${START + index * 1_000L},"doc_count":$count}"""
                    }.joinToString(",")
            val samples =
                if (sample) {
                    ""","samples":{"hits":{"hits":[{"_index":"logs","_id":"1","sort":[$START],"_source":{"message":"boom"}}]}}"""
                } else {
                    ""
                }
            val groups =
                if (total == 0L) {
                    "[]"
                } else {
                    """[{"key":["api","Timeout"],"doc_count":$total,"first_at":{"value":$START},""" +
                        """"last_at":{"value":${START + 999}}$samples}]"""
                }
            return """{"timed_out":$timedOut,"_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                "hits":{"total":{"value":$total,"relation":"$relation"}},"aggregations":{"timeline":{"buckets":[$timeline]},
                "groups":{"sum_other_doc_count":0,"doc_count_error_upper_bound":0,"buckets":$groups}}}"""
        }

        data class BindingCase(
            val name: String,
            val snapshot: ResourceSnapshotV1,
            val windows: List<ResourceWindowV1>,
            val runStart: Long,
            val runEnd: Long,
        )

        private fun snapshot(
            start: Long,
            step: Long,
            points: Int,
            windows: List<ResourceWindowV1>,
        ) = ResourceSnapshotV1("resource-snapshot.v1", HASH, start, step, points, emptyList(), windows, emptyList(), null)

        /** Inputs of resourceBindingEvidence: explicit and implicit windows, dropped edges on both sides, extreme bounds. */
        fun bindingCases(): List<BindingCase> {
            val explicit = listOf(ResourceWindowV1("steady", 20_000, 40_000), ResourceWindowV1("ramp", 10_000, 20_000))
            val one = listOf(ResourceWindowV1("run-intersection", 20_000, 40_000))
            return listOf(
                BindingCase("explicit", snapshot(10_000, 10_000, 5, explicit), explicit, 10_000, 60_000),
                BindingCase("explicit-run-wider", snapshot(10_000, 10_000, 5, explicit), explicit, 0, 99_999),
                BindingCase("implicit", snapshot(10_000, 10_000, 4, emptyList()), one, 10_000, 50_000),
                BindingCase("implicit-both-dropped", snapshot(10_000, 10_000, 4, emptyList()), one, 15_001, 49_999),
                BindingCase("implicit-leading-only", snapshot(10_000, 10_000, 4, emptyList()), one, 15_001, 60_000),
                BindingCase("implicit-trailing-only", snapshot(10_000, 10_000, 4, emptyList()), one, 0, 49_999),
                BindingCase(
                    "implicit-windows-outside",
                    snapshot(10_000, 10_000, 4, emptyList()),
                    listOf(ResourceWindowV1("run-intersection", 5_000, 55_000)),
                    10_000,
                    50_000,
                ),
                BindingCase("zero-points", snapshot(0, 1_000, 0, emptyList()), one, 0, 0),
                BindingCase(
                    "wide-numbers",
                    snapshot(1_767_225_600_000, 60_000, 400, emptyList()),
                    one,
                    1_767_225_600_001,
                    1_767_249_599_999,
                ),
                BindingCase(
                    "extreme-run",
                    snapshot(1_767_225_600_000, 1_000, 10, emptyList()),
                    one,
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                ),
                BindingCase("extreme-snapshot", snapshot(Long.MAX_VALUE - 5, 1_000, 10, emptyList()), one, 0, Long.MAX_VALUE),
                BindingCase(
                    "extreme-windows",
                    snapshot(0, 1_000, 3, emptyList()),
                    listOf(ResourceWindowV1("run-intersection", Long.MIN_VALUE, Long.MAX_VALUE)),
                    0,
                    3_000,
                ),
                BindingCase("empty-windows", snapshot(0, 1_000, 3, emptyList()), emptyList(), 0, 3_000),
            )
        }
    }
}

/**
 * The time a request waited for a slot (`throttle_wait_ms`) is measured and differs from run to run; the snapshots compare
 * everything else, and the field is typed and compared against frozen builders with fixed budgets elsewhere.
 */
internal fun withoutThrottle(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject ->
            JsonObject(
                element.mapValues { (key, value) -> if (key == "throttle_wait_ms") JsonPrimitive(0) else withoutThrottle(value) },
            )
        is JsonArray -> JsonArray(element.map(::withoutThrottle))
        else -> element
    }

/** The text of a stored JSON artifact with the measured wait zeroed, in canonical form. */
internal fun withoutThrottle(bytes: ByteArray): String =
    runCatching { canonicalJson(withoutThrottle(Json.parseToJsonElement(bytes.decodeToString()))).decodeToString() }
        .getOrElse { bytes.decodeToString() }

package io.ltverdict.sources

import io.ltverdict.core.MAX_LABEL_KEY_BYTES
import io.ltverdict.core.MAX_LABEL_VALUE_BYTES
import io.ltverdict.core.MAX_POINTS_PER_SERIES
import io.ltverdict.core.RUN_PERIOD_STATUS_RECOGNIZED
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceOperator
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.ResourceRuleEffect
import io.ltverdict.core.ResourceRuleV1
import io.ltverdict.core.RunPeriodV1
import io.ltverdict.core.StrictJsonScanner
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal enum class SourceKind(
    val wireName: String,
) {
    PROMETHEUS("prometheus"),
    VICTORIA_METRICS("victoria_metrics"),
    INFLUXDB("influxdb"),
    OPENSEARCH("opensearch"),
}

internal enum class SourceTransport(
    val wireName: String,
) {
    DIRECT("direct"),
    GRAFANA_PROXY("grafana_proxy"),
}

internal sealed interface SourceAuth {
    data object None : SourceAuth

    data class Bearer(
        val tokenEnv: String,
    ) : SourceAuth

    data class Token(
        val tokenEnv: String,
    ) : SourceAuth

    data class Basic(
        val usernameEnv: String,
        val passwordEnv: String,
    ) : SourceAuth
}

internal data class SourceGovernor(
    val requestsPerSecond: Double = 0.5,
    val burst: Int = 1,
    val maxConcurrent: Int = 1,
    val timeoutMillis: Long = 30_000,
    val maxAttempts: Int = 3,
    val honorRetryAfter: Boolean = true,
    val maxRequestsPerRun: Int? = null,
)

internal data class SourceQuery(
    val id: String,
    val expression: String,
    val metric: String,
    val unit: String,
    val entity: String,
    val role: ResourceRole,
    val aggregation: ResourceAggregation,
    val labels: Map<String, String>,
    // Only source-connections.v3: series values are non-negative event increments; data is checked via NEGATIVE_EVENT_VALUE.
    val nonNegativeEvents: Boolean = false,
)

internal data class SourceTls(
    val caFile: Path?,
    val clientKeystoreFile: Path?,
    val clientKeystorePasswordEnv: String?,
)

internal data class SourceProfile(
    val id: String,
    val sourceKind: SourceKind,
    val transport: SourceTransport,
    val baseUrl: URI,
    val datasourceUid: String?,
    val auth: SourceAuth = SourceAuth.None,
    val allowInsecureHttp: Boolean = false,
    val governor: SourceGovernor = SourceGovernor(),
    val queries: List<SourceQuery>,
    val rules: List<ResourceRuleV1> = emptyList(),
    val database: String? = null,
    val openSearch: OpenSearchMapping? = null,
    val scrapeIntervalMillis: Long? = null,
    // Only source-connections.v3: duration rules store minConsecutiveCells = 1 in rules as a stub.
    // Use rulesAt(stepMillis) to obtain the actual cell count for a snapshot.
    val ruleSpansMillis: Map<String, Long> = emptyMap(),
    // Only source-connections.v3: the arm label stamped by collection on every series of this profile's snapshot.
    val arm: String? = null,
    val tls: SourceTls? = null,
)

internal fun spanToCells(
    spanMillis: Long,
    stepMillis: Long,
): Int = Math.floorDiv(spanMillis + stepMillis - 1, stepMillis).toInt()

internal fun SourceProfile.rulesAt(stepMillis: Long): List<ResourceRuleV1> =
    if (ruleSpansMillis.isEmpty()) {
        rules
    } else {
        rules.map { rule ->
            ruleSpansMillis[rule.id]?.let { span -> rule.copy(minConsecutiveCells = spanToCells(span, stepMillis)) } ?: rule
        }
    }

internal data class SourceRequest(
    val profileId: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val stepMillis: Long,
    val additionalProfileIds: List<String> = emptyList(),
    // Provenance окна публикуется только для v3: байты v1/v2 остаются неизменными.
    val windowProvenance: JsonObject? = null,
    // Выведенное окно шире прогона, поэтому snapshot не объявляет окна: ядро само обрезает его по пересечению с прогоном.
    val autoDerivedWindow: Boolean = false,
)

internal sealed interface RequestWindow {
    val stepMillis: Long
}

internal data class ExplicitWindow(
    val startMillis: Long,
    val endMillis: Long,
    override val stepMillis: Long,
    val stepAuto: Boolean = false,
) : RequestWindow

internal data class AutoWindow(
    val marginMillis: Long,
    val maxIdleGapMillis: Long,
    override val stepMillis: Long,
    val stepAuto: Boolean = false,
) : RequestWindow

internal data class WindowedSourceRequest(
    val schemaVersion: String,
    val profileIds: List<String>,
    val window: RequestWindow,
)

internal data class DerivedAutoWindow(
    val startMillis: Long,
    val endMillis: Long,
    val stepMillis: Long,
    val appliedMarginMillis: Long,
)

internal const val AUTO_WINDOW_UNAVAILABLE = "AUTO_WINDOW_UNAVAILABLE"
internal const val AUTO_WINDOW_MULTI_TEST_SUSPECTED = "AUTO_WINDOW_MULTI_TEST_SUSPECTED"
internal const val AUTO_WINDOW_SPAN_UNSUPPORTED = "AUTO_WINDOW_SPAN_UNSUPPORTED"

internal sealed interface AutoWindowOutcome {
    data class Derived(
        val window: DerivedAutoWindow,
    ) : AutoWindowOutcome

    data class Refused(
        val reasonCode: String,
    ) : AutoWindowOutcome
}

internal class SourceBudget(
    val maxRequests: Int? = null,
) {
    @Volatile
    var requestCount: Int = 0
        private set

    @Volatile
    var retries: Int = 0
        private set

    @Volatile
    var throttleWaitMillis: Long = 0
        private set

    @Volatile
    var capExceeded: Boolean = false
        private set

    init {
        require(maxRequests == null || maxRequests >= 0) { "SOURCE_INVALID_BUDGET" }
    }

    @Synchronized
    internal fun reserveAttempt(retry: Boolean): Boolean {
        if (maxRequests != null && requestCount >= maxRequests) {
            capExceeded = true
            return false
        }
        requestCount++
        if (retry) retries++
        return true
    }

    @Synchronized
    internal fun addThrottleWait(millis: Long) {
        if (millis <= 0) return
        throttleWaitMillis =
            try {
                Math.addExact(throttleWaitMillis, millis)
            } catch (_: ArithmeticException) {
                Long.MAX_VALUE
            }
    }
}

internal class SourceHttpFailure(
    val code: String,
    // The HTTP status of a refused response (source probe, W3.1); null when the failure had no response.
    val httpStatus: Int? = null,
) : RuntimeException(code)

internal data class SourceConnections(
    val http: List<SourceProfile>,
    val postgres: List<PostgresProfile>,
)

internal fun readSourceProfiles(source: InputStream): List<SourceProfile> = readSourceConnections(source).http

internal fun readSourceConnections(source: InputStream): SourceConnections =
    readSourceInput(source, MAX_SOURCE_CONFIG_BYTES, "CONFIG") { text ->
        scanSourceJson(text, "source connections", "SOURCE_CONFIG_INVALID")
        parseConnections(Json.parseToJsonElement(text))
    }

internal fun readSourceRequest(source: InputStream): SourceRequest =
    readSourceInput(source, MAX_SOURCE_REQUEST_BYTES, "REQUEST") { text ->
        scanSourceJson(text, "source request", "SOURCE_REQUEST_INVALID")
        parseRequest(Json.parseToJsonElement(text))
    }

internal fun readWindowedSourceRequest(source: InputStream): WindowedSourceRequest =
    readSourceInput(source, MAX_SOURCE_REQUEST_BYTES, "REQUEST") { text ->
        scanSourceJson(text, "source request", "SOURCE_REQUEST_INVALID")
        parseWindowedRequest(Json.parseToJsonElement(text))
    }

internal fun deriveAutoWindow(
    period: RunPeriodV1,
    auto: AutoWindow,
): AutoWindowOutcome {
    if (period.status != RUN_PERIOD_STATUS_RECOGNIZED) return AutoWindowOutcome.Refused(AUTO_WINDOW_UNAVAILABLE)
    val longestGap = period.longestIdleGapMillis
    // Отказывает только простой строго длиннее допуска: граница, равная допуску, остаётся одним наблюдаемым прогоном.
    if (longestGap != null && longestGap > auto.maxIdleGapMillis) {
        return AutoWindowOutcome.Refused(AUTO_WINDOW_MULTI_TEST_SUSPECTED)
    }
    val step = auto.stepMillis
    // Нижняя граница обрезается до нуля до выравнивания, иначе обрезка ушла бы за пределы epoch.
    val adjustedStart = maxOf(0L, period.firstSampleEpochMillis - auto.marginMillis)
    val start = Math.floorDiv(adjustedStart, step) * step
    // Выравнивание выполняется по абсолютной сетке epoch, а не относительно периода: ячейки совпадают с сеткой источника.
    val end = -Math.floorDiv(-(period.lastSampleEpochMillis + auto.marginMillis), step) * step
    val cells = (end - start) / step
    if (cells !in 1..MAX_POINTS_PER_SERIES.toLong()) return AutoWindowOutcome.Refused(AUTO_WINDOW_SPAN_UNSUPPORTED)
    // Применённый margin — запас, гарантированный с обеих сторон; после обрезки нуля он может быть меньше заявленного.
    val appliedMargin = minOf(period.firstSampleEpochMillis - start, end - period.lastSampleEpochMillis)
    return AutoWindowOutcome.Derived(DerivedAutoWindow(start, end, step, appliedMargin))
}

private fun parseConnections(element: JsonElement): SourceConnections {
    val pointer = ""
    val root = element.sourceObject(pointer = pointer)
    root.rejectUnknown(setOf("schema_version", "connections"), pointer = pointer)
    val version = root.sourceString("schema_version", pointer = pointer)
    if (version !in setOf("source-connections.v1", "source-connections.v2", "source-connections.v3")) {
        configInvalid(
            "/schema_version",
            "schema_version must be one of source-connections.v1, source-connections.v2, source-connections.v3",
        )
    }
    val values = root.sourceArray("connections", pointer = pointer)
    if (values.isEmpty() ||
        values.size > MAX_SOURCE_PROFILES
    ) {
        configInvalid("/connections", "connections must contain 1..$MAX_SOURCE_PROFILES profiles")
    }
    val ids = HashSet<String>()
    val http = mutableListOf<SourceProfile>()
    val postgres = mutableListOf<PostgresProfile>()
    values.forEachIndexed { index, value ->
        val pointer = pointer.child("connections").child(index.toString())
        if (!ids.add(
                value.sourceObject(pointer = pointer).sourceString("id", pointer = pointer),
            )
        ) {
            configInvalid(pointer.child("id"), "id must be unique")
        }
        if (version != "source-connections.v1" &&
            value.sourceObject(pointer = pointer).sourceString("source_kind", pointer = pointer) == "postgresql"
        ) {
            postgres += parsePostgresProfile(value, pointer)
        } else {
            http += parseProfile(value, allowAutoStep = version == "source-connections.v3", pointer = pointer)
        }
    }
    return SourceConnections(http, postgres)
}

private fun parsePostgresProfile(
    element: JsonElement,
    pointer: String,
): PostgresProfile {
    val value = element.sourceObject(pointer = pointer)
    value.rejectUnknown(
        setOf(
            "id",
            "source_kind",
            "source_database_id",
            "host",
            "port",
            "database",
            "username_env",
            "password_env",
            "allow_insecure",
            "tables",
            "pg_profile",
        ),
        pointer = pointer,
    )
    val tables =
        value.optionalArray("tables", pointer = pointer).orEmpty().mapIndexed { index, element ->
            val pointer = pointer.child("tables").child(index.toString())
            val table = element.sourceObject(pointer = pointer)
            table.rejectUnknown(setOf("schema", "table", "columns", "key", "row_limit", "byte_limit"), pointer = pointer)

            fun strings(name: String): List<String> =
                table.sourceArray(name, pointer = pointer).mapIndexed { index, it ->
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                        ?: configInvalid(pointer.child(name).child(index.toString()), "$name items must be strings")
                }
            PostgresTableProfile(
                table.sourceString("schema", pointer = pointer),
                table.sourceString("table", pointer = pointer),
                strings("columns"),
                if ("key" in table) strings("key") else emptyList(),
                table.optionalInt("row_limit", pointer = pointer) ?: 10_000,
                table.optionalInt("byte_limit", pointer = pointer) ?: 1_048_576,
            )
        }
    val report =
        value["pg_profile"]?.sourceObject(pointer.child("pg_profile"))?.also {
            val pointer = pointer.child("pg_profile")
            it.rejectUnknown(setOf("server_id", "start_sample_id", "end_sample_id"), pointer = pointer)
        }
    return PostgresProfile(
        id = value.sourceString("id", pointer = pointer),
        sourceDatabaseId = value.sourceString("source_database_id", pointer = pointer),
        host = value.sourceString("host", pointer = pointer),
        port = value.optionalInt("port", pointer = pointer) ?: 5432,
        database = value.sourceString("database", pointer = pointer),
        usernameEnv = value.sourceEnvironmentName("username_env", pointer = pointer),
        passwordEnv = value.sourceEnvironmentName("password_env", pointer = pointer),
        allowInsecure = value.optionalBoolean("allow_insecure", pointer = pointer) ?: false,
        tables = tables,
        pgProfileServerId = report?.sourceInt("server_id", pointer.child("pg_profile")),
        pgProfileStartSampleId = report?.sourceInt("start_sample_id", pointer.child("pg_profile")),
        pgProfileEndSampleId = report?.sourceInt("end_sample_id", pointer.child("pg_profile")),
    ).also {
        try {
            validatePostgresProfile(it)
        } catch (_: IllegalArgumentException) {
            configInvalid(pointer, "profile violates PostgreSQL constraints (PG_PROFILE_INVALID)")
        }
    }
}

private fun parseProfile(
    element: JsonElement,
    allowAutoStep: Boolean,
    pointer: String,
): SourceProfile {
    val value = element.sourceObject(pointer = pointer)
    value.rejectUnknown(
        buildSet {
            addAll(
                setOf(
                    "id",
                    "source_kind",
                    "transport",
                    "base_url",
                    "datasource_uid",
                    "database",
                    "opensearch",
                    "auth",
                    "tls",
                    "allow_insecure_http",
                    "governor",
                    "queries",
                    "rules",
                ),
            )
            if (allowAutoStep) addAll(setOf("scrape_interval_ms", "arm"))
        },
        pointer = pointer,
    )
    val id = value.sourceText("id", MAX_IDENTIFIER_BYTES, pointer = pointer)
    val sourceKind =
        SourceKind.entries.find { it.wireName == value.sourceString("source_kind", pointer = pointer) }
            ?: configInvalid(pointer.child("source_kind"), "source_kind must be one of ${SourceKind.entries.joinToString { it.wireName }}")
    val transport =
        SourceTransport.entries.find { it.wireName == value.sourceString("transport", pointer = pointer) }
            ?: configInvalid(pointer.child("transport"), "transport must be one of direct, grafana_proxy")
    val baseUrl = parseBaseUrl(value.sourceString("base_url", pointer = pointer), pointer.child("base_url"))
    val tls = value["tls"]?.let { parseTls(it, baseUrl, pointer.child("tls")) }
    val datasourceUid = value.optionalString("datasource_uid", pointer = pointer)
    val database =
        value.optionalString("database", pointer = pointer)?.let {
            validateText(it, MAX_DATABASE_BYTES, pointer.child("database"), "database")
        }
    when (sourceKind) {
        SourceKind.INFLUXDB ->
            if (database ==
                null
            ) {
                configInvalid(pointer.child("database"), "database is required for source_kind influxdb")
            }
        SourceKind.PROMETHEUS,
        SourceKind.VICTORIA_METRICS,
        SourceKind.OPENSEARCH,
        -> if (database != null) configInvalid(pointer.child("database"), "database is not allowed for source_kind ${sourceKind.wireName}")
    }
    when (transport) {
        SourceTransport.DIRECT ->
            if (datasourceUid !=
                null
            ) {
                configInvalid(pointer.child("datasource_uid"), "datasource_uid is not allowed for transport direct")
            }
        SourceTransport.GRAFANA_PROXY -> {
            if (datasourceUid == null || datasourceUid in setOf(".", "..") || !SAFE_PATH_SEGMENT.matches(datasourceUid)) {
                configInvalid(pointer.child("datasource_uid"), "datasource_uid must be a safe path segment for transport grafana_proxy")
            }
        }
    }
    val auth = value["auth"]?.let { parseAuth(it, pointer.child("auth")) } ?: SourceAuth.None
    val allowInsecureHttp = value.optionalBoolean("allow_insecure_http", pointer = pointer) ?: false
    if (baseUrl.scheme == "http" && auth != SourceAuth.None && !allowInsecureHttp) {
        configInvalid(pointer.child("allow_insecure_http"), "allow_insecure_http must be true when using auth over HTTP")
    }
    val governor = value["governor"]?.let { parseGovernor(it, pointer.child("governor")) } ?: SourceGovernor()
    val scrapeIntervalMillis = if (allowAutoStep) value.optionalLong("scrape_interval_ms", pointer = pointer) else null
    if (scrapeIntervalMillis != null &&
        (scrapeIntervalMillis !in 1_000..MAX_SCRAPE_INTERVAL_MILLIS || scrapeIntervalMillis % 1_000L != 0L)
    ) {
        configInvalid(
            pointer.child("scrape_interval_ms"),
            "scrape_interval_ms must be 1000..$MAX_SCRAPE_INTERVAL_MILLIS and divisible by 1000",
        )
    }
    val openSearch =
        if (sourceKind == SourceKind.OPENSEARCH) {
            if (transport != SourceTransport.DIRECT) {
                configInvalid(pointer.child("transport"), "transport must be direct for source_kind opensearch")
            }
            listOf("queries", "rules", "database", "datasource_uid", "scrape_interval_ms", "arm")
                .firstOrNull { it in value }
                ?.let {
                    configInvalid(pointer.child(it), "$it is not allowed for source_kind opensearch")
                }
            parseOpenSearch(
                value["opensearch"] ?: configInvalid(pointer.child("opensearch"), "opensearch is required for source_kind opensearch"),
                pointer.child("opensearch"),
            )
        } else {
            if ("opensearch" in value) configInvalid(pointer.child("opensearch"), "opensearch is only allowed for source_kind opensearch")
            null
        }
    val queries =
        if (openSearch !=
            null
        ) {
            emptyList()
        } else {
            parseQueries(value.sourceArray("queries", pointer = pointer), sourceKind, allowAutoStep, pointer.child("queries"))
        }
    val arm = if (allowAutoStep && "arm" in value) value.sourceText("arm", MAX_IDENTIFIER_BYTES, pointer = pointer) else null
    // The arm comes from the profile, never from a query label: a query that expects another arm contradicts it.
    if (arm != null) {
        queries.indexOfFirst { (it.labels["arm"] ?: arm) != arm }.takeIf { it >= 0 }?.let {
            configInvalid(
                pointer
                    .child("queries")
                    .child(it.toString())
                    .child("labels")
                    .child("arm"),
                "arm label must match the profile arm",
            )
        }
    }
    val (rules, spans) =
        value.optionalArray("rules", pointer = pointer)?.let { parseRules(it, queries, allowAutoStep, pointer.child("rules")) }
            ?: (emptyList<ResourceRuleV1>() to emptyMap<String, Long>())
    if (allowAutoStep) {
        val byId = queries.associateBy(SourceQuery::id)
        rules.forEachIndexed { index, rule ->
            val query = byId[rule.seriesId]
            if (query != null &&
                query.nonNegativeEvents &&
                eventIncrementRule(rule) &&
                query.aggregation == ResourceAggregation.INTERVAL_MIN
            ) {
                configInvalid(
                    pointer.child("rules").child(index.toString()),
                    "event increment rule cannot use a non_negative_events query with aggregation interval_min",
                )
            }
        }
    }
    return SourceProfile(
        id,
        sourceKind,
        transport,
        baseUrl,
        datasourceUid,
        auth,
        allowInsecureHttp,
        governor,
        queries,
        rules,
        database,
        openSearch,
        scrapeIntervalMillis,
        spans,
        arm,
        tls,
    )
}

private fun parseTls(
    element: JsonElement,
    baseUrl: URI,
    pointer: String,
): SourceTls {
    val value = element.sourceObject(pointer = pointer)
    if (value.isEmpty()) configInvalid(pointer, "tls must not be empty")
    if (baseUrl.scheme != "https") configInvalid(pointer, "tls requires an HTTPS base_url")
    value.rejectUnknown(setOf("ca_file", "client_keystore_file", "client_keystore_password_env"), pointer = pointer)
    val caFile = value.optionalString("ca_file", pointer = pointer)?.let { sourceTlsPath(it, pointer.child("ca_file")) }
    val keystoreFile =
        value.optionalString("client_keystore_file", pointer = pointer)?.let {
            sourceTlsPath(it, pointer.child("client_keystore_file"))
        }
    val passwordEnv =
        if ("client_keystore_password_env" in
            value
        ) {
            value.sourceEnvironmentName("client_keystore_password_env", pointer = pointer)
        } else {
            null
        }
    if ((keystoreFile == null) != (passwordEnv == null)) {
        val missing = if (keystoreFile == null) "client_keystore_file" else "client_keystore_password_env"
        configInvalid(pointer.child(missing), "$missing is required with its client keystore counterpart")
    }
    return SourceTls(caFile, keystoreFile, passwordEnv)
}

private fun sourceTlsPath(
    value: String,
    pointer: String,
): Path {
    if (value.isEmpty() ||
        value.encodeToByteArray().size > 1024 ||
        value.any(Char::isISOControl) ||
        value.startsWith("~") ||
        value.startsWith("//") ||
        value.startsWith("\\\\") ||
        value.startsWith("file:", ignoreCase = true) ||
        value.split('/', '\\').any { it == "." || it == ".." }
    ) {
        configInvalid(
            pointer,
            "TLS file path must be non-empty, bounded to 1024 UTF-8 bytes and contain no controls, " +
                "home expansion, UNC prefix, file URI or dot segments",
        )
    }
    return try {
        Path.of(value).also { if (!it.isAbsolute) configInvalid(pointer, "TLS file path must be absolute") }
    } catch (_: InvalidPathException) {
        configInvalid(pointer, "TLS file path is invalid")
    }
}

private fun parseOpenSearch(
    element: JsonElement,
    pointer: String,
): OpenSearchMapping {
    val value = element.sourceObject(pointer = pointer)
    value.rejectUnknown(
        setOf(
            "indices",
            "timestamp_field",
            "service_field",
            "error_type_field",
            "message_field",
            "group_limit",
            "samples_per_group",
            "sample_message_bytes_max",
        ),
        pointer = pointer,
    )
    return try {
        OpenSearchMapping(
            value.sourceArray("indices", pointer = pointer).mapIndexed { index, it ->
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    ?: configInvalid(pointer.child("indices").child(index.toString()), "indices items must be strings")
            },
            value.sourceString("timestamp_field", pointer = pointer),
            value.sourceString("service_field", pointer = pointer),
            value.sourceString("error_type_field", pointer = pointer),
            value.sourceString("message_field", pointer = pointer),
            value.optionalInt("group_limit", pointer = pointer) ?: 50,
            value.optionalInt("samples_per_group", pointer = pointer) ?: 2,
            value.optionalInt("sample_message_bytes_max", pointer = pointer) ?: 4096,
        )
    } catch (failure: SourceInputFailure) {
        throw failure
    } catch (_: IllegalArgumentException) {
        configInvalid(pointer, "mapping violates OpenSearch constraints (OPENSEARCH_INVALID_MAPPING)")
    }
}

private fun parseAuth(
    element: JsonElement,
    pointer: String,
): SourceAuth {
    val value = element.sourceObject(pointer = pointer)
    return when (value.sourceString("type", pointer = pointer)) {
        "none" -> {
            value.rejectUnknown(setOf("type"), pointer = pointer)
            SourceAuth.None
        }

        "bearer" -> {
            value.rejectUnknown(setOf("type", "token_env"), pointer = pointer)
            SourceAuth.Bearer(value.sourceEnvironmentName("token_env", pointer = pointer))
        }

        "token" -> {
            value.rejectUnknown(setOf("type", "token_env"), pointer = pointer)
            SourceAuth.Token(value.sourceEnvironmentName("token_env", pointer = pointer))
        }

        "basic" -> {
            value.rejectUnknown(setOf("type", "username_env", "password_env"), pointer = pointer)
            SourceAuth.Basic(
                value.sourceEnvironmentName("username_env", pointer = pointer),
                value.sourceEnvironmentName("password_env", pointer = pointer),
            )
        }

        else -> configInvalid(pointer.child("type"), "auth type must be one of none, bearer, token, basic")
    }
}

private fun parseGovernor(
    element: JsonElement,
    pointer: String,
): SourceGovernor {
    val value = element.sourceObject(pointer = pointer)
    value.rejectUnknown(
        setOf(
            "requests_per_second",
            "burst",
            "max_concurrent",
            "timeout_ms",
            "max_attempts",
            "honor_retry_after",
            "max_requests_per_run",
        ),
        pointer = pointer,
    )
    val requestsPerSecond = value.optionalDecimal("requests_per_second", pointer = pointer)?.toDouble() ?: 0.5
    val burst = value.optionalInt("burst", pointer = pointer) ?: 1
    val maxConcurrent = value.optionalInt("max_concurrent", pointer = pointer) ?: 1
    val timeoutMillis = value.optionalLong("timeout_ms", pointer = pointer) ?: 30_000
    val maxAttempts = value.optionalInt("max_attempts", pointer = pointer) ?: 3
    val honorRetryAfter = value.optionalBoolean("honor_retry_after", pointer = pointer) ?: true
    val maxRequestsPerRun = value.optionalInt("max_requests_per_run", pointer = pointer)
    if (!requestsPerSecond.isFinite() || requestsPerSecond <= 0.0 || requestsPerSecond > MAX_REQUESTS_PER_SECOND) {
        configInvalid(
            pointer.child("requests_per_second"),
            "requests_per_second must be finite, above 0 and at most $MAX_REQUESTS_PER_SECOND",
        )
    }
    if (burst !in 1..MAX_BURST) configInvalid(pointer.child("burst"), "burst must be 1..$MAX_BURST")
    if (maxConcurrent !in 1..MAX_CONCURRENT) configInvalid(pointer.child("max_concurrent"), "max_concurrent must be 1..$MAX_CONCURRENT")
    if (timeoutMillis !in 1..MAX_TIMEOUT_MILLIS) configInvalid(pointer.child("timeout_ms"), "timeout_ms must be 1..$MAX_TIMEOUT_MILLIS")
    if (maxAttempts !in 1..MAX_ATTEMPTS) configInvalid(pointer.child("max_attempts"), "max_attempts must be 1..$MAX_ATTEMPTS")
    if (maxRequestsPerRun != null && maxRequestsPerRun !in 0..MAX_REQUESTS_PER_RUN) {
        configInvalid(pointer.child("max_requests_per_run"), "max_requests_per_run must be 0..$MAX_REQUESTS_PER_RUN")
    }
    return SourceGovernor(
        requestsPerSecond,
        burst,
        maxConcurrent,
        timeoutMillis,
        maxAttempts,
        honorRetryAfter,
        maxRequestsPerRun,
    )
}

private fun parseQueries(
    values: JsonArray,
    sourceKind: SourceKind,
    allowAutoStep: Boolean,
    pointer: String,
): List<SourceQuery> {
    if (values.isEmpty() || values.size > MAX_SOURCE_QUERIES) configInvalid(pointer, "queries must contain 1..$MAX_SOURCE_QUERIES queries")
    val ids = HashSet<String>()
    return values.mapIndexed { index, element ->
        val pointer = pointer.child(index.toString())
        val value = element.sourceObject(pointer = pointer)
        value.rejectUnknown(
            setOf("id", "expression", "metric", "unit", "entity", "role", "aggregation", "labels") +
                if (allowAutoStep) setOf("non_negative_events") else emptySet(),
            pointer = pointer,
        )
        val id = value.sourceText("id", MAX_IDENTIFIER_BYTES, pointer = pointer)
        if (!ids.add(id)) configInvalid(pointer.child("id"), "id must be unique")
        val expression = value.sourceText("expression", MAX_QUERY_BYTES, pointer = pointer)
        when (sourceKind) {
            SourceKind.INFLUXDB -> validateInfluxqlExpression(expression, pointer.child("expression"))
            SourceKind.OPENSEARCH -> configInvalid(pointer, "queries are not allowed for source_kind opensearch")
            SourceKind.PROMETHEUS,
            SourceKind.VICTORIA_METRICS,
            ->
                if (!expression.contains(
                        INTERVAL_PLACEHOLDER,
                    )
                ) {
                    configInvalid(pointer.child("expression"), "expression must contain $INTERVAL_PLACEHOLDER")
                }
        }
        val role =
            ResourceRole.entries.find { it.wireName == value.sourceString("role", pointer = pointer) }
                ?: configInvalid(pointer.child("role"), "role must be one of ${ResourceRole.entries.joinToString { it.wireName }}")
        val aggregation =
            ResourceAggregation.entries.find { it.wireName == value.sourceString("aggregation", pointer = pointer) }
                ?: configInvalid(
                    pointer.child("aggregation"),
                    "aggregation must be one of ${ResourceAggregation.entries.joinToString { it.wireName }}",
                )
        SourceQuery(
            id,
            expression,
            value.sourceText("metric", MAX_IDENTIFIER_BYTES, pointer = pointer),
            value.sourceText("unit", MAX_IDENTIFIER_BYTES, pointer = pointer),
            value.sourceText("entity", MAX_IDENTIFIER_BYTES, pointer = pointer),
            role,
            aggregation,
            value["labels"]?.let { parseLabels(it, pointer.child("labels")) }.orEmpty(),
            if (allowAutoStep) value.optionalBoolean("non_negative_events", pointer = pointer) ?: false else false,
        )
    }
}

private fun validateInfluxqlExpression(
    expression: String,
    pointer: String,
) {
    val placeholders = INFLUX_PLACEHOLDER_PATTERN.findAll(expression).map(MatchResult::value).toList()
    if (INFLUX_REQUIRED_PLACEHOLDERS.any { it !in placeholders } || placeholders.any { it !in INFLUX_PLACEHOLDERS }) {
        configInvalid(pointer, "expression must contain the required InfluxQL time placeholders and only supported placeholders")
    }
    if ("${'$'}__" in
        INFLUX_PLACEHOLDER_PATTERN.replace(expression, "")
    ) {
        configInvalid(pointer, "expression contains a malformed InfluxQL placeholder")
    }
    if (';' in expression || INFLUX_COMMENTS.any(expression::contains) || INFLUX_INTO.containsMatchIn(expression)) {
        configInvalid(pointer, "expression must not contain semicolons, comments or INTO")
    }
    if (INFLUX_FILL.findAll(expression).any { it.groupValues[1].trim().lowercase() !in INFLUX_GAP_PRESERVING_FILL }) {
        configInvalid(pointer, "expression fill must be null or none")
    }
    if (!INFLUX_SELECT.matches(expression)) configInvalid(pointer, "expression must be a SELECT with FROM")
    if (!INFLUX_VALUE_ALIAS.containsMatchIn(expression) && !INFLUX_DIRECT_VALUE.matches(expression)) {
        configInvalid(pointer, "expression must select value or use AS value")
    }
}

private fun parseLabels(
    element: JsonElement,
    pointer: String,
): Map<String, String> {
    val value = element.sourceObject(pointer = pointer)
    if (value.size > MAX_LABELS) configInvalid(pointer, "labels must contain at most $MAX_LABELS entries")
    return value.mapValues { (key, element) ->
        val pointer = pointer.child(if (key.length > 64) key.take(64) + "..." else key)
        validateText(key, MAX_LABEL_KEY_BYTES, pointer, "label key")
        val label = element as? JsonPrimitive ?: configInvalid(pointer, "label value must be a string")
        if (!label.isString) configInvalid(pointer, "label value must be a string")
        validateText(label.content, MAX_LABEL_VALUE_BYTES, pointer, "label value")
    }
}

private fun parseRules(
    values: JsonArray,
    queries: List<SourceQuery>,
    allowSpan: Boolean,
    pointer: String,
): Pair<List<ResourceRuleV1>, Map<String, Long>> {
    if (values.size > MAX_RESOURCE_RULES) configInvalid(pointer, "rules must contain at most $MAX_RESOURCE_RULES entries")
    val ids = HashSet<String>()
    val queryById = queries.associateBy(SourceQuery::id)
    val spans = linkedMapOf<String, Long>()
    val rules =
        values.mapIndexed { index, element ->
            val pointer = pointer.child(index.toString())
            val value = element.sourceObject(pointer = pointer)
            value.rejectUnknown(
                setOf("id", "series_id", "unit", "operator", "threshold", "min_consecutive_cells", "effect") +
                    if (allowSpan) setOf("min_consecutive_span_ms") else emptySet(),
                pointer = pointer,
            )
            val id = value.sourceText("id", MAX_IDENTIFIER_BYTES, pointer = pointer)
            if (!ids.add(id)) configInvalid(pointer.child("id"), "id must be unique")
            val seriesId = value.sourceText("series_id", MAX_IDENTIFIER_BYTES, pointer = pointer)
            val unit = value.sourceText("unit", MAX_IDENTIFIER_BYTES, pointer = pointer)
            if (seriesId !in queryById) configInvalid(pointer.child("series_id"), "series_id must refer to an existing query")
            if (queryById[seriesId]?.unit != unit) configInvalid(pointer.child("unit"), "unit must match the referenced query unit")
            val operator =
                ResourceOperator.entries.find { it.wireName == value.sourceString("operator", pointer = pointer) }
                    ?: configInvalid(
                        pointer.child("operator"),
                        "operator must be one of ${ResourceOperator.entries.joinToString { it.wireName }}",
                    )
            val threshold = value.sourceDecimal("threshold", pointer = pointer)
            if (threshold.precision() > MAX_DECIMAL_PRECISION || maxOf(threshold.scale(), 0) > MAX_DECIMAL_SCALE) {
                configInvalid(
                    pointer.child("threshold"),
                    "threshold must have at most $MAX_DECIMAL_PRECISION digits and $MAX_DECIMAL_SCALE decimal places",
                )
            }
            if (threshold.abs() >
                MAX_DECIMAL_MAGNITUDE
            ) {
                configInvalid(pointer.child("threshold"), "threshold magnitude must be at most $MAX_DECIMAL_MAGNITUDE")
            }
            val hasCells = "min_consecutive_cells" in value
            if (hasCells == ("min_consecutive_span_ms" in value)) {
                configInvalid(
                    pointer.child("min_consecutive_cells"),
                    "exactly one of min_consecutive_cells or min_consecutive_span_ms is required",
                )
            }
            val minimum =
                if (hasCells) {
                    value.sourceInt("min_consecutive_cells", pointer = pointer)
                } else {
                    val span = value.sourceLong("min_consecutive_span_ms", pointer = pointer)
                    if (span !in
                        1..MAX_RULE_SPAN_MILLIS
                    ) {
                        configInvalid(
                            pointer.child("min_consecutive_span_ms"),
                            "min_consecutive_span_ms must be 1..$MAX_RULE_SPAN_MILLIS",
                        )
                    }
                    spans[id] = span
                    1
                }
            if (minimum !in
                1..MAX_POINTS_PER_SERIES
            ) {
                configInvalid(pointer.child("min_consecutive_cells"), "min_consecutive_cells must be 1..$MAX_POINTS_PER_SERIES")
            }
            val effect =
                ResourceRuleEffect.entries.find { it.wireName == value.sourceString("effect", pointer = pointer) }
                    ?: configInvalid(
                        pointer.child("effect"),
                        "effect must be one of ${ResourceRuleEffect.entries.joinToString { it.wireName }}",
                    )
            ResourceRuleV1(id, seriesId, unit, operator, threshold, minimum, effect)
        }
    return rules to spans
}

private fun parseRequest(element: JsonElement): SourceRequest =
    try {
        val value = element.sourceObject()
        val profileIds = parseProfileIds(value, value.sourceString("schema_version"))
        val window = parseExplicitWindow(value, strictStep = false)
        SourceRequest(profileIds.first(), window.startMillis, window.endMillis, window.stepMillis, profileIds.drop(1))
    } catch (_: SourceInputFailure) {
        requestInvalid()
    }

private fun parseWindowedRequest(element: JsonElement): WindowedSourceRequest =
    try {
        val value = element.sourceObject()
        val version = value.sourceString("schema_version")
        if (version == "source-request.v3") {
            value.rejectUnknown(setOf("schema_version", "profile_ids", "window"))
            val window = value["window"]?.let(::parseWindow) ?: requestInvalid()
            WindowedSourceRequest(version, sortedProfileIds(value.sourceArray("profile_ids")), window)
        } else if (version == "source-request.v4") {
            value.rejectUnknown(setOf("schema_version", "profile_ids", "window"))
            val window = value["window"]?.let(::parseWindowV4) ?: requestInvalid()
            WindowedSourceRequest(version, sortedProfileIds(value.sourceArray("profile_ids")), window)
        } else {
            WindowedSourceRequest(version, parseProfileIds(value, version), parseExplicitWindow(value, strictStep = false))
        }
    } catch (_: SourceInputFailure) {
        requestInvalid()
    }

private fun parseProfileIds(
    value: JsonObject,
    version: String,
): List<String> =
    when (version) {
        "source-request.v1" -> {
            value.rejectUnknown(setOf("schema_version", "profile_id", "start_epoch_ms", "end_epoch_ms", "step_ms"))
            listOf(value.sourceText("profile_id", MAX_IDENTIFIER_BYTES, ::requestInvalid))
        }
        "source-request.v2" -> {
            value.rejectUnknown(setOf("schema_version", "profile_ids", "start_epoch_ms", "end_epoch_ms", "step_ms"))
            sortedProfileIds(value.sourceArray("profile_ids"))
        }
        else -> requestInvalid()
    }

private fun sortedProfileIds(values: JsonArray): List<String> {
    val ids =
        values.map {
            val id = (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: requestInvalid()
            validateText(id, MAX_IDENTIFIER_BYTES)
        }
    if (ids.size !in 1..MAX_SOURCE_PROFILES || ids.distinct().size != ids.size) requestInvalid()
    return ids.sorted()
}

private fun parseWindow(element: JsonElement): RequestWindow {
    val value = element.sourceObject()
    return when (value.sourceString("origin")) {
        "explicit" -> {
            value.rejectUnknown(setOf("origin", "start_epoch_ms", "end_epoch_ms", "step_ms"))
            parseExplicitWindow(value, strictStep = true)
        }
        "auto" -> {
            value.rejectUnknown(setOf("origin", "step_ms", "margin_ms", "max_idle_gap_ms"))
            val step = value.sourceLong("step_ms", ::requestInvalid)
            validateStepMillis(step, strict = true)
            // Запас и допуск простоя объявляются целым числом ячеек: вывод окна не округляет заявленные границы.
            val margin = value.sourceLong("margin_ms", ::requestInvalid)
            if (margin !in 0..MAX_MARGIN_MILLIS || margin % step != 0L) requestInvalid()
            val maxIdleGap = value.sourceLong("max_idle_gap_ms", ::requestInvalid)
            if (maxIdleGap < step || maxIdleGap % step != 0L) requestInvalid()
            AutoWindow(margin, maxIdleGap, step)
        }
        else -> requestInvalid()
    }
}

private fun parseWindowV4(element: JsonElement): RequestWindow {
    val value = element.sourceObject()
    return when (value.sourceString("origin")) {
        "explicit" -> {
            value.rejectUnknown(setOf("origin", "start_epoch_ms", "end_epoch_ms", "step_ms", "step_mode"))
            val stepAuto = parseStepMode(value)
            parseExplicitWindow(value, strictStep = true, checkCells = !stepAuto).copy(stepAuto = stepAuto)
        }
        "auto" -> {
            value.rejectUnknown(setOf("origin", "step_ms", "step_mode", "margin_ms", "max_idle_gap_ms"))
            val step = value.sourceLong("step_ms", ::requestInvalid)
            validateStepMillis(step, strict = true)
            val stepAuto = parseStepMode(value)
            val margin = value.sourceLong("margin_ms", ::requestInvalid)
            val maxIdleGap = value.sourceLong("max_idle_gap_ms", ::requestInvalid)
            if (margin !in 0..MAX_MARGIN_MILLIS || maxIdleGap < step || maxIdleGap % 1_000L != 0L) requestInvalid()
            if (!stepAuto && (margin % step != 0L || maxIdleGap % step != 0L)) requestInvalid()
            AutoWindow(margin, maxIdleGap, step, stepAuto)
        }
        else -> requestInvalid()
    }
}

private fun parseStepMode(value: JsonObject): Boolean =
    when (value.sourceString("step_mode")) {
        "fixed" -> false
        "auto" -> true
        else -> requestInvalid()
    }

private fun parseExplicitWindow(
    value: JsonObject,
    strictStep: Boolean,
    checkCells: Boolean = true,
): ExplicitWindow {
    val start = value.sourceLong("start_epoch_ms", ::requestInvalid)
    val end = value.sourceLong("end_epoch_ms", ::requestInvalid)
    val step = value.sourceLong("step_ms", ::requestInvalid)
    if (start !in 0 until MAX_TIMESTAMP_EPOCH_MILLIS || end !in 1..MAX_TIMESTAMP_EPOCH_MILLIS || end <= start) {
        requestInvalid()
    }
    validateStepMillis(step, strictStep)
    if ((end - start) % step != 0L) requestInvalid()
    if (checkCells && (end - start) / step !in 1..MAX_POINTS_PER_SERIES.toLong()) requestInvalid()
    return ExplicitWindow(start, end, step)
}

private fun validateStepMillis(
    step: Long,
    strict: Boolean,
) {
    // Сетка snapshot принимает только целые секунды 1..60, но её нет у профилей opensearch:
    // поэтому v3 строг при разборе, а v1/v2 оставляют прежний шаг >= 1000 и проверяют его
    // в acquire по фактическому source_kind профиля — до внешних обращений.
    if (strict) {
        if (step !in 1_000..60_000 || step % 1_000L != 0L) requestInvalid()
    } else if (step < 1_000) {
        requestInvalid()
    }
}

private fun parseBaseUrl(
    value: String,
    pointer: String,
): URI {
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            configInvalid(pointer, "base_url must be a valid URI")
        }
    val scheme = uri.scheme?.lowercase() ?: configInvalid(pointer, "base_url must have a scheme")
    if (scheme !in setOf("http", "https") ||
        uri.isOpaque ||
        uri.host == null ||
        uri.port == 0 ||
        uri.port > 65_535
    ) {
        configInvalid(pointer, "base_url must be an absolute HTTP or HTTPS URL with a host and a valid port")
    }
    if (uri.rawUserInfo != null ||
        uri.rawQuery != null ||
        uri.rawFragment != null ||
        '%' in (uri.rawPath ?: "")
    ) {
        configInvalid(pointer, "base_url must not contain credentials, query, fragment or percent-encoded path")
    }
    val path = uri.path.orEmpty()
    if (path.split('/').any { it == "." || it == ".." }) configInvalid(pointer, "base_url must not contain dot path segments")
    return try {
        URI(scheme, null, uri.host.lowercase(), uri.port, path, null, null)
    } catch (_: Exception) {
        configInvalid(pointer, "base_url must be a valid HTTP or HTTPS URL")
    }
}

private inline fun <T> readSourceInput(
    source: InputStream,
    maxBytes: Int,
    subject: String,
    parse: (String) -> T,
): T =
    try {
        val bytes = source.readNBytes(maxBytes + 1)
        if (bytes.size > maxBytes) sourceInputFailure("SOURCE_${subject}_TOO_LARGE")
        parse(decodeUtf8(bytes, subject))
    } catch (failure: SourceInputFailure) {
        throw failure
    } catch (_: IOException) {
        sourceInputFailure("SOURCE_${subject}_READ_ERROR")
    } catch (_: SerializationException) {
        if (subject == "CONFIG") configInvalid("", "source connections must be valid JSON")
        sourceInputFailure("SOURCE_${subject}_INVALID")
    } catch (_: IllegalArgumentException) {
        if (subject == "CONFIG") configInvalid("", "source connections violate source-specific profile constraints")
        sourceInputFailure("SOURCE_${subject}_INVALID")
    }

private fun scanSourceJson(
    text: String,
    subject: String,
    failureCode: String,
) {
    StrictJsonScanner(text, MAX_JSON_DEPTH, MAX_NUMERIC_TOKEN_BYTES, MAX_NUMERIC_EXPONENT, subject) { _, pointer, detail ->
        if (failureCode == "SOURCE_CONFIG_INVALID") configInvalid(pointer, detail)
        sourceInputFailure(failureCode)
    }.scan()
}

private fun decodeUtf8(
    bytes: ByteArray,
    subject: String,
): String =
    try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        sourceInputFailure("SOURCE_${subject}_INVALID_UTF8")
    }

private fun String.child(token: String): String = "$this/${token.replace("~", "~0").replace("/", "~1")}"

private fun JsonElement.sourceObject(pointer: String = ""): JsonObject = this as? JsonObject ?: configInvalid(pointer, "must be an object")

private fun JsonObject.sourceArray(
    name: String,
    pointer: String = "",
): JsonArray = this[name] as? JsonArray ?: configInvalid(pointer.child(name), "$name is required and must be an array")

private fun JsonObject.optionalArray(
    name: String,
    pointer: String = "",
): JsonArray? {
    val value = this[name] ?: return null
    return value as? JsonArray ?: configInvalid(pointer.child(name), "$name must be an array")
}

private fun JsonObject.sourceString(
    name: String,
    pointer: String = "",
): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: configInvalid(pointer.child(name), "$name is required and must be a string")

private fun JsonObject.optionalString(
    name: String,
    pointer: String = "",
): String? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: configInvalid(pointer.child(name), "$name must be a string")
}

private fun JsonObject.sourceEnvironmentName(
    name: String,
    pointer: String = "",
): String =
    sourceString(name, pointer).also {
        if (!ENVIRONMENT_NAME.matches(it)) configInvalid(pointer.child(name), "$name must be a valid environment variable name")
    }

private fun JsonObject.sourceText(
    name: String,
    maxBytes: Int,
    invalid: (() -> Nothing)? = null,
    pointer: String = "",
): String {
    val value =
        try {
            sourceString(name, pointer)
        } catch (failure: SourceInputFailure) {
            invalid?.invoke() ?: throw failure
        }
    return try {
        validateText(value, maxBytes, pointer.child(name), name)
    } catch (failure: SourceInputFailure) {
        invalid?.invoke() ?: throw failure
    }
}

private fun validateText(
    value: String,
    maxBytes: Int,
    pointer: String = "",
    name: String = "text",
): String {
    if (value.isEmpty() || value.any(Char::isISOControl) || value.encodeToByteArray().size > maxBytes) {
        configInvalid(pointer, "$name must be non-empty, contain no control characters and fit in $maxBytes UTF-8 bytes")
    }
    return value
}

private fun JsonObject.optionalBoolean(
    name: String,
    pointer: String = "",
): Boolean? {
    val value = this[name] ?: return null
    if (value !is JsonPrimitive || value.isString) configInvalid(pointer.child(name), "$name must be a boolean")
    return value.content.toBooleanStrictOrNull() ?: configInvalid(pointer.child(name), "$name must be a boolean")
}

private fun JsonObject.sourceDecimal(
    name: String,
    pointer: String = "",
): BigDecimal {
    val value = this[name] as? JsonPrimitive ?: configInvalid(pointer.child(name), "$name is required and must be a number")
    if (value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        configInvalid(pointer.child(name), "$name must be a number")
    }
    return try {
        BigDecimal(value.content)
    } catch (_: NumberFormatException) {
        configInvalid(pointer.child(name), "$name must be a number")
    }
}

private fun JsonObject.optionalDecimal(
    name: String,
    pointer: String = "",
): BigDecimal? =
    if (name in this) {
        try {
            sourceDecimal(name, pointer)
        } catch (_: SourceInputFailure) {
            configInvalid(pointer.child(name), "$name must be a number")
        }
    } else {
        null
    }

private fun JsonObject.sourceLong(
    name: String,
    invalid: (() -> Nothing)? = null,
    pointer: String = "",
): Long =
    try {
        sourceDecimal(name, pointer).longValueExact()
    } catch (_: ArithmeticException) {
        invalid?.invoke() ?: configInvalid(pointer.child(name), "$name must be an integer in the 64-bit range")
    } catch (failure: SourceInputFailure) {
        invalid?.invoke() ?: throw failure
    }

private fun JsonObject.optionalLong(
    name: String,
    pointer: String = "",
): Long? =
    if (name in this) {
        try {
            sourceLong(name, pointer = pointer)
        } catch (_: SourceInputFailure) {
            configInvalid(pointer.child(name), "$name must be an integer in the 64-bit range")
        }
    } else {
        null
    }

private fun JsonObject.sourceInt(
    name: String,
    pointer: String = "",
): Int =
    try {
        sourceDecimal(name, pointer).intValueExact()
    } catch (_: ArithmeticException) {
        configInvalid(pointer.child(name), "$name must be an integer in the 32-bit range")
    }

private fun JsonObject.optionalInt(
    name: String,
    pointer: String = "",
): Int? =
    if (name in this) {
        try {
            sourceInt(name, pointer)
        } catch (_: SourceInputFailure) {
            configInvalid(pointer.child(name), "$name must be an integer in the 32-bit range")
        }
    } else {
        null
    }

private fun JsonObject.rejectUnknown(
    allowed: Set<String>,
    pointer: String = "",
) {
    keys.firstOrNull { it !in allowed }?.let {
        val key = if (it.length > 64) it.take(64) + "..." else it
        configInvalid(pointer.child(key), "unknown field $key")
    }
}

private fun configInvalid(
    pointer: String,
    detail: String,
): Nothing = throw SourceInputFailure("SOURCE_CONFIG_INVALID", pointer, detail)

private fun requestInvalid(): Nothing = sourceInputFailure("SOURCE_REQUEST_INVALID")

private fun sourceInputFailure(code: String): Nothing = throw SourceInputFailure(code)

internal class SourceInputFailure(
    val code: String,
    val jsonPointer: String? = null,
    val detail: String? = null,
) : IllegalArgumentException(code)

private const val MAX_SOURCE_CONFIG_BYTES = 1_048_576
private const val MAX_SOURCE_REQUEST_BYTES = 16 * 1024
internal const val MAX_MARGIN_MILLIS = 3_600_000L
private const val MAX_SCRAPE_INTERVAL_MILLIS = 3_600_000L
private const val MAX_RULE_SPAN_MILLIS = 86_400_000L
private const val MAX_SOURCE_PROFILES = 16
private const val MAX_SOURCE_QUERIES = 64
private const val MAX_RESOURCE_RULES = 256
private const val MAX_LABELS = 16
private const val MAX_IDENTIFIER_BYTES = 128
private const val MAX_DATABASE_BYTES = 128
private const val MAX_QUERY_BYTES = 65_536
private const val MAX_JSON_DEPTH = 12
private const val MAX_NUMERIC_TOKEN_BYTES = 64
private const val MAX_NUMERIC_EXPONENT = 64
private const val MAX_REQUESTS_PER_SECOND = 1_000.0
private const val MAX_BURST = 1_000
private const val MAX_CONCURRENT = 64
private const val MAX_TIMEOUT_MILLIS = 300_000L
private const val MAX_ATTEMPTS = 10
private const val MAX_REQUESTS_PER_RUN = 1_000_000
private const val MAX_DECIMAL_PRECISION = 32
private const val MAX_DECIMAL_SCALE = 12
private const val INTERVAL_PLACEHOLDER = "${'$'}__interval"
private const val START_PLACEHOLDER = "${'$'}__start"
private const val END_PLACEHOLDER = "${'$'}__end"
private const val OFFSET_PLACEHOLDER = "${'$'}__offset"
private val INFLUX_REQUIRED_PLACEHOLDERS = listOf(START_PLACEHOLDER, END_PLACEHOLDER, INTERVAL_PLACEHOLDER)
private val INFLUX_PLACEHOLDERS = INFLUX_REQUIRED_PLACEHOLDERS + OFFSET_PLACEHOLDER
private val INFLUX_PLACEHOLDER_PATTERN = Regex("\\${'$'}__[A-Za-z0-9_]+")
private val INFLUX_COMMENTS = listOf("--", "/*", "*/", "#")
private val INFLUX_INTO = Regex("""(?i)\bINTO\b""")
private val INFLUX_FILL = Regex("""(?i)\bfill\s*\(([^()]*)\)""")
private val INFLUX_SELECT = Regex("""(?is)^\s*SELECT\b.+\bFROM\b.+$""")
private val INFLUX_VALUE_ALIAS = Regex("""(?i)\bAS\s+"?value"?(?=\s|,|$)""")
private val INFLUX_DIRECT_VALUE = Regex("""(?is)^\s*SELECT\s+"?value"?(?:\s*,|\s+FROM\b).*$""")

// `fill(null)` и `fill(none)` сохраняют пропуски; остальные режимы фабрикуют значения,
// которые resource statistics приняли бы за наблюдения.
private val INFLUX_GAP_PRESERVING_FILL = setOf("null", "none")
private val MAX_DECIMAL_MAGNITUDE = BigDecimal("1000000000000000000")
private val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._~-]{1,128}")

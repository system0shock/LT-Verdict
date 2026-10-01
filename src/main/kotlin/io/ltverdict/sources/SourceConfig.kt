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
)

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
) : RequestWindow

internal data class AutoWindow(
    val marginMillis: Long,
    val maxIdleGapMillis: Long,
    override val stepMillis: Long,
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
    val root = element.sourceObject()
    root.rejectUnknown(setOf("schema_version", "connections"))
    val version = root.sourceString("schema_version")
    if (version !in setOf("source-connections.v1", "source-connections.v2")) configInvalid()
    val values = root.sourceArray("connections")
    if (values.isEmpty() || values.size > MAX_SOURCE_PROFILES) configInvalid()
    val ids = HashSet<String>()
    val http = mutableListOf<SourceProfile>()
    val postgres = mutableListOf<PostgresProfile>()
    values.forEach { value ->
        if (!ids.add(value.sourceObject().sourceString("id"))) configInvalid()
        if (version == "source-connections.v2" && value.sourceObject().sourceString("source_kind") == "postgresql") {
            postgres += parsePostgresProfile(value)
        } else {
            http += parseProfile(value)
        }
    }
    return SourceConnections(http, postgres)
}

private fun parsePostgresProfile(element: JsonElement): PostgresProfile {
    val value = element.sourceObject()
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
    )
    val tables =
        value.optionalArray("tables").orEmpty().map { element ->
            val table = element.sourceObject()
            table.rejectUnknown(setOf("schema", "table", "columns", "key", "row_limit", "byte_limit"))

            fun strings(name: String): List<String> =
                table.sourceArray(name).map {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: configInvalid()
                }
            PostgresTableProfile(
                table.sourceString("schema"),
                table.sourceString("table"),
                strings("columns"),
                if ("key" in table) strings("key") else emptyList(),
                table.optionalInt("row_limit") ?: 10_000,
                table.optionalInt("byte_limit") ?: 1_048_576,
            )
        }
    val report =
        value["pg_profile"]?.sourceObject()?.also {
            it.rejectUnknown(setOf("server_id", "start_sample_id", "end_sample_id"))
        }
    return PostgresProfile(
        id = value.sourceString("id"),
        sourceDatabaseId = value.sourceString("source_database_id"),
        host = value.sourceString("host"),
        port = value.optionalInt("port") ?: 5432,
        database = value.sourceString("database"),
        usernameEnv = value.sourceEnvironmentName("username_env"),
        passwordEnv = value.sourceEnvironmentName("password_env"),
        allowInsecure = value.optionalBoolean("allow_insecure") ?: false,
        tables = tables,
        pgProfileServerId = report?.sourceInt("server_id"),
        pgProfileStartSampleId = report?.sourceInt("start_sample_id"),
        pgProfileEndSampleId = report?.sourceInt("end_sample_id"),
    ).also(::validatePostgresProfile)
}

private fun parseProfile(element: JsonElement): SourceProfile {
    val value = element.sourceObject()
    value.rejectUnknown(
        setOf(
            "id",
            "source_kind",
            "transport",
            "base_url",
            "datasource_uid",
            "database",
            "opensearch",
            "auth",
            "allow_insecure_http",
            "governor",
            "queries",
            "rules",
        ),
    )
    val id = value.sourceText("id", MAX_IDENTIFIER_BYTES)
    val sourceKind = SourceKind.entries.find { it.wireName == value.sourceString("source_kind") } ?: configInvalid()
    val transport = SourceTransport.entries.find { it.wireName == value.sourceString("transport") } ?: configInvalid()
    val baseUrl = parseBaseUrl(value.sourceString("base_url"))
    val datasourceUid = value.optionalString("datasource_uid")
    val database = value.optionalString("database")?.let { validateText(it, MAX_DATABASE_BYTES) }
    when (sourceKind) {
        SourceKind.INFLUXDB -> if (database == null) configInvalid()
        SourceKind.PROMETHEUS,
        SourceKind.VICTORIA_METRICS,
        SourceKind.OPENSEARCH,
        -> if (database != null) configInvalid()
    }
    when (transport) {
        SourceTransport.DIRECT -> if (datasourceUid != null) configInvalid()
        SourceTransport.GRAFANA_PROXY -> {
            if (datasourceUid == null || datasourceUid in setOf(".", "..") || !SAFE_PATH_SEGMENT.matches(datasourceUid)) configInvalid()
        }
    }
    val auth = value["auth"]?.let(::parseAuth) ?: SourceAuth.None
    val allowInsecureHttp = value.optionalBoolean("allow_insecure_http") ?: false
    if (baseUrl.scheme == "http" && auth != SourceAuth.None && !allowInsecureHttp) configInvalid()
    val governor = value["governor"]?.let(::parseGovernor) ?: SourceGovernor()
    val openSearch =
        if (sourceKind == SourceKind.OPENSEARCH) {
            if (transport != SourceTransport.DIRECT ||
                listOf("queries", "rules", "database", "datasource_uid").any { it in value }
            ) {
                configInvalid()
            }
            parseOpenSearch(value["opensearch"] ?: configInvalid())
        } else {
            if ("opensearch" in value) configInvalid()
            null
        }
    val queries = if (openSearch != null) emptyList() else parseQueries(value.sourceArray("queries"), sourceKind)
    val rules = value.optionalArray("rules")?.let { parseRules(it, queries) }.orEmpty()
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
    )
}

private fun parseOpenSearch(element: JsonElement): OpenSearchMapping {
    val value = element.sourceObject()
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
    )
    return OpenSearchMapping(
        value.sourceArray("indices").map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: configInvalid() },
        value.sourceString("timestamp_field"),
        value.sourceString("service_field"),
        value.sourceString("error_type_field"),
        value.sourceString("message_field"),
        value.optionalInt("group_limit") ?: 50,
        value.optionalInt("samples_per_group") ?: 2,
        value.optionalInt("sample_message_bytes_max") ?: 4096,
    )
}

private fun parseAuth(element: JsonElement): SourceAuth {
    val value = element.sourceObject()
    return when (value.sourceString("type")) {
        "none" -> {
            value.rejectUnknown(setOf("type"))
            SourceAuth.None
        }

        "bearer" -> {
            value.rejectUnknown(setOf("type", "token_env"))
            SourceAuth.Bearer(value.sourceEnvironmentName("token_env"))
        }

        "token" -> {
            value.rejectUnknown(setOf("type", "token_env"))
            SourceAuth.Token(value.sourceEnvironmentName("token_env"))
        }

        "basic" -> {
            value.rejectUnknown(setOf("type", "username_env", "password_env"))
            SourceAuth.Basic(value.sourceEnvironmentName("username_env"), value.sourceEnvironmentName("password_env"))
        }

        else -> configInvalid()
    }
}

private fun parseGovernor(element: JsonElement): SourceGovernor {
    val value = element.sourceObject()
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
    )
    val requestsPerSecond = value.optionalDecimal("requests_per_second")?.toDouble() ?: 0.5
    val burst = value.optionalInt("burst") ?: 1
    val maxConcurrent = value.optionalInt("max_concurrent") ?: 1
    val timeoutMillis = value.optionalLong("timeout_ms") ?: 30_000
    val maxAttempts = value.optionalInt("max_attempts") ?: 3
    val honorRetryAfter = value.optionalBoolean("honor_retry_after") ?: true
    val maxRequestsPerRun = value.optionalInt("max_requests_per_run")
    if (!requestsPerSecond.isFinite() || requestsPerSecond <= 0.0 || requestsPerSecond > MAX_REQUESTS_PER_SECOND) configInvalid()
    if (burst !in 1..MAX_BURST || maxConcurrent !in 1..MAX_CONCURRENT) configInvalid()
    if (timeoutMillis !in 1..MAX_TIMEOUT_MILLIS || maxAttempts !in 1..MAX_ATTEMPTS) configInvalid()
    if (maxRequestsPerRun != null && maxRequestsPerRun !in 0..MAX_REQUESTS_PER_RUN) configInvalid()
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
): List<SourceQuery> {
    if (values.isEmpty() || values.size > MAX_SOURCE_QUERIES) configInvalid()
    val ids = HashSet<String>()
    return values.map { element ->
        val value = element.sourceObject()
        value.rejectUnknown(setOf("id", "expression", "metric", "unit", "entity", "role", "aggregation", "labels"))
        val id = value.sourceText("id", MAX_IDENTIFIER_BYTES)
        if (!ids.add(id)) configInvalid()
        val expression = value.sourceText("expression", MAX_QUERY_BYTES)
        when (sourceKind) {
            SourceKind.INFLUXDB -> validateInfluxqlExpression(expression)
            SourceKind.OPENSEARCH -> configInvalid()
            SourceKind.PROMETHEUS,
            SourceKind.VICTORIA_METRICS,
            -> if (!expression.contains(INTERVAL_PLACEHOLDER)) configInvalid()
        }
        val role = ResourceRole.entries.find { it.wireName == value.sourceString("role") } ?: configInvalid()
        val aggregation =
            ResourceAggregation.entries.find { it.wireName == value.sourceString("aggregation") } ?: configInvalid()
        SourceQuery(
            id,
            expression,
            value.sourceText("metric", MAX_IDENTIFIER_BYTES),
            value.sourceText("unit", MAX_IDENTIFIER_BYTES),
            value.sourceText("entity", MAX_IDENTIFIER_BYTES),
            role,
            aggregation,
            value["labels"]?.let(::parseLabels).orEmpty(),
        )
    }
}

private fun validateInfluxqlExpression(expression: String) {
    val placeholders = INFLUX_PLACEHOLDER_PATTERN.findAll(expression).map(MatchResult::value).toList()
    if (INFLUX_REQUIRED_PLACEHOLDERS.any { it !in placeholders } || placeholders.any { it !in INFLUX_PLACEHOLDERS }) {
        configInvalid()
    }
    if ("${'$'}__" in INFLUX_PLACEHOLDER_PATTERN.replace(expression, "")) configInvalid()
    if (';' in expression || INFLUX_COMMENTS.any(expression::contains) || INFLUX_INTO.containsMatchIn(expression)) configInvalid()
    if (INFLUX_FILL.findAll(expression).any { it.groupValues[1].trim().lowercase() !in INFLUX_GAP_PRESERVING_FILL }) configInvalid()
    if (!INFLUX_SELECT.matches(expression)) configInvalid()
    if (!INFLUX_VALUE_ALIAS.containsMatchIn(expression) && !INFLUX_DIRECT_VALUE.matches(expression)) configInvalid()
}

private fun parseLabels(element: JsonElement): Map<String, String> {
    val value = element.sourceObject()
    if (value.size > MAX_LABELS) configInvalid()
    return value.mapValues { (key, element) ->
        validateText(key, MAX_LABEL_KEY_BYTES)
        val label = element as? JsonPrimitive ?: configInvalid()
        if (!label.isString) configInvalid()
        validateText(label.content, MAX_LABEL_VALUE_BYTES)
    }
}

private fun parseRules(
    values: JsonArray,
    queries: List<SourceQuery>,
): List<ResourceRuleV1> {
    if (values.size > MAX_RESOURCE_RULES) configInvalid()
    val ids = HashSet<String>()
    val queryById = queries.associateBy(SourceQuery::id)
    return values.map { element ->
        val value = element.sourceObject()
        value.rejectUnknown(setOf("id", "series_id", "unit", "operator", "threshold", "min_consecutive_cells", "effect"))
        val id = value.sourceText("id", MAX_IDENTIFIER_BYTES)
        if (!ids.add(id)) configInvalid()
        val seriesId = value.sourceText("series_id", MAX_IDENTIFIER_BYTES)
        val unit = value.sourceText("unit", MAX_IDENTIFIER_BYTES)
        if (queryById[seriesId]?.unit != unit) configInvalid()
        val operator = ResourceOperator.entries.find { it.wireName == value.sourceString("operator") } ?: configInvalid()
        val threshold = value.sourceDecimal("threshold")
        if (threshold.precision() > MAX_DECIMAL_PRECISION || maxOf(threshold.scale(), 0) > MAX_DECIMAL_SCALE) configInvalid()
        if (threshold.abs() > MAX_DECIMAL_MAGNITUDE) configInvalid()
        val minimum = value.sourceInt("min_consecutive_cells")
        if (minimum !in 1..MAX_POINTS_PER_SERIES) configInvalid()
        val effect = ResourceRuleEffect.entries.find { it.wireName == value.sourceString("effect") } ?: configInvalid()
        ResourceRuleV1(id, seriesId, unit, operator, threshold, minimum, effect)
    }
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

private fun parseExplicitWindow(
    value: JsonObject,
    strictStep: Boolean,
): ExplicitWindow {
    val start = value.sourceLong("start_epoch_ms", ::requestInvalid)
    val end = value.sourceLong("end_epoch_ms", ::requestInvalid)
    val step = value.sourceLong("step_ms", ::requestInvalid)
    if (start !in 0 until MAX_TIMESTAMP_EPOCH_MILLIS || end !in 1..MAX_TIMESTAMP_EPOCH_MILLIS || end <= start) {
        requestInvalid()
    }
    validateStepMillis(step, strictStep)
    if ((end - start) % step != 0L) requestInvalid()
    if ((end - start) / step !in 1..MAX_POINTS_PER_SERIES.toLong()) requestInvalid()
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

private fun parseBaseUrl(value: String): URI {
    val uri =
        try {
            URI(value)
        } catch (_: Exception) {
            configInvalid()
        }
    val scheme = uri.scheme?.lowercase() ?: configInvalid()
    if (scheme !in setOf("http", "https") || uri.isOpaque || uri.host == null || uri.port == 0 || uri.port > 65_535) configInvalid()
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || '%' in (uri.rawPath ?: "")) configInvalid()
    val path = uri.path.orEmpty()
    if (path.split('/').any { it == "." || it == ".." }) configInvalid()
    return try {
        URI(scheme, null, uri.host.lowercase(), uri.port, path, null, null)
    } catch (_: Exception) {
        configInvalid()
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
        sourceInputFailure("SOURCE_${subject}_INVALID")
    } catch (_: IllegalArgumentException) {
        sourceInputFailure("SOURCE_${subject}_INVALID")
    }

private fun scanSourceJson(
    text: String,
    subject: String,
    failureCode: String,
) {
    StrictJsonScanner(text, MAX_JSON_DEPTH, MAX_NUMERIC_TOKEN_BYTES, MAX_NUMERIC_EXPONENT, subject) { _, _, _ ->
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

private fun JsonElement.sourceObject(): JsonObject = this as? JsonObject ?: configInvalid()

private fun JsonObject.sourceArray(name: String): JsonArray = this[name] as? JsonArray ?: configInvalid()

private fun JsonObject.optionalArray(name: String): JsonArray? {
    val value = this[name] ?: return null
    return value as? JsonArray ?: configInvalid()
}

private fun JsonObject.sourceString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: configInvalid()

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: configInvalid()
}

private fun JsonObject.sourceEnvironmentName(name: String): String =
    sourceString(name).also { if (!ENVIRONMENT_NAME.matches(it)) configInvalid() }

private fun JsonObject.sourceText(
    name: String,
    maxBytes: Int,
    invalid: () -> Nothing = ::configInvalid,
): String {
    val value =
        try {
            sourceString(name)
        } catch (_: SourceInputFailure) {
            invalid()
        }
    return try {
        validateText(value, maxBytes)
    } catch (_: SourceInputFailure) {
        invalid()
    }
}

private fun validateText(
    value: String,
    maxBytes: Int,
): String {
    if (value.isEmpty() || value.any(Char::isISOControl) || value.encodeToByteArray().size > maxBytes) configInvalid()
    return value
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value !is JsonPrimitive || value.isString) configInvalid()
    return value.content.toBooleanStrictOrNull() ?: configInvalid()
}

private fun JsonObject.sourceDecimal(name: String): BigDecimal {
    val value = this[name] as? JsonPrimitive ?: configInvalid()
    if (value.isString || value === JsonNull || value.content in setOf("true", "false")) configInvalid()
    return try {
        BigDecimal(value.content)
    } catch (_: NumberFormatException) {
        configInvalid()
    }
}

private fun JsonObject.optionalDecimal(name: String): BigDecimal? = if (name in this) sourceDecimal(name) else null

private fun JsonObject.sourceLong(
    name: String,
    invalid: () -> Nothing = ::configInvalid,
): Long =
    try {
        sourceDecimal(name).longValueExact()
    } catch (_: ArithmeticException) {
        invalid()
    } catch (_: SourceInputFailure) {
        invalid()
    }

private fun JsonObject.optionalLong(name: String): Long? = if (name in this) sourceLong(name) else null

private fun JsonObject.sourceInt(name: String): Int =
    try {
        sourceDecimal(name).intValueExact()
    } catch (_: ArithmeticException) {
        configInvalid()
    }

private fun JsonObject.optionalInt(name: String): Int? = if (name in this) sourceInt(name) else null

private fun JsonObject.rejectUnknown(allowed: Set<String>) {
    if (keys.any { it !in allowed }) configInvalid()
}

private fun configInvalid(): Nothing = sourceInputFailure("SOURCE_CONFIG_INVALID")

private fun requestInvalid(): Nothing = sourceInputFailure("SOURCE_REQUEST_INVALID")

private fun sourceInputFailure(code: String): Nothing = throw SourceInputFailure(code)

private class SourceInputFailure(
    code: String,
) : IllegalArgumentException(code)

private const val MAX_SOURCE_CONFIG_BYTES = 1_048_576
private const val MAX_SOURCE_REQUEST_BYTES = 16 * 1024
internal const val MAX_MARGIN_MILLIS = 3_600_000L
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

package io.ltverdict.sources

import io.ltverdict.integrations.grafana.GrafanaPanelRequest
import io.ltverdict.integrations.grafana.grafanaRenderUri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.min

internal class SourceHttp(
    profiles: List<SourceProfile>,
    private val environment: (String) -> String? = System::getenv,
) {
    private val configured: Map<String, ConfiguredProfile>
    private val client: HttpClient

    init {
        if (profiles.map(SourceProfile::id).toSet().size != profiles.size) sourceFailure("SOURCE_PROFILE_INVALID")
        val origins = profiles.associateWith(::origin)
        val states =
            profiles
                .groupBy { origins.getValue(it) }
                .mapValues { (_, sameOrigin) -> OriginState(strictest(sameOrigin.map(SourceProfile::governor))) }
        configured =
            profiles.associate { profile ->
                profile.id to ConfiguredProfile(profile, endpoint(profile), states.getValue(origins.getValue(profile)))
            }
        client =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
    }

    internal fun get(
        profile: SourceProfile,
        queryParameters: Map<String, String>,
        budget: SourceBudget,
        checkCancelled: () -> Unit = {},
    ): ByteArray = execute(profile, queryParameters, null, budget, checkCancelled)

    internal fun getGrafanaPanel(
        profile: SourceProfile,
        panel: GrafanaPanelRequest,
        budget: SourceBudget = SourceBudget(),
        checkCancelled: () -> Unit = {},
    ): ByteArray = execute(profile, emptyMap(), null, budget, checkCancelled, grafanaRenderUri(profile, panel), 8 * 1024 * 1024)

    internal fun search(
        profile: SourceProfile,
        body: ByteArray,
        budget: SourceBudget,
        checkCancelled: () -> Unit = {},
    ): ByteArray {
        if (profile.sourceKind != SourceKind.OPENSEARCH) sourceFailure("SOURCE_PROFILE_INVALID")
        return execute(
            profile,
            mapOf(
                "allow_no_indices" to "false",
                "ignore_unavailable" to "false",
                "allow_partial_search_results" to "true",
                "typed_keys" to "false",
            ),
            body,
            budget,
            checkCancelled,
        )
    }

    private fun execute(
        profile: SourceProfile,
        queryParameters: Map<String, String>,
        body: ByteArray?,
        budget: SourceBudget,
        checkCancelled: () -> Unit,
        endpointOverride: URI? = null,
        responseLimit: Int = MAX_HTTP_RESPONSE_BYTES,
    ): ByteArray {
        val configuredProfile =
            configured[profile.id]?.takeIf { it.profile == profile }
                ?: sourceFailure("SOURCE_PROFILE_NOT_CONFIGURED")
        val authorization = authorization(profile.auth, environment)
        val request =
            request(
                uriWithQuery(endpointOverride ?: configuredProfile.endpoint, queryParameters),
                authorization,
                configuredProfile.state.settings,
                body,
            )
        var attempt = 0
        while (true) {
            checkCancelled()
            configuredProfile.state.acquireConcurrency(budget, checkCancelled)
            val response =
                try {
                    configuredProfile.state.acquireToken(budget, checkCancelled)
                    checkCancelled()
                    if (!budget.reserveAttempt(attempt > 0)) sourceFailure("SOURCE_REQUEST_CAP_EXCEEDED")
                    send(request, configuredProfile.state.settings.timeoutMillis, checkCancelled, responseLimit)
                } catch (failure: AttemptFailure) {
                    if (!failure.retryable || attempt + 1 >= configuredProfile.state.settings.maxAttempts) {
                        sourceFailure(failure.code)
                    }
                    null
                } finally {
                    configuredProfile.state.releaseConcurrency()
                }

            if (response != null && response.statusCode() in 200..299) return response.body()

            val status = response?.statusCode()
            val failureCode =
                when {
                    status == null -> "SOURCE_HTTP_ERROR"
                    status == 401 || status == 403 -> "SOURCE_HTTP_AUTH"
                    status == 429 -> "SOURCE_HTTP_429"
                    status in 500..599 -> "SOURCE_HTTP_5XX"
                    else -> "SOURCE_HTTP_STATUS"
                }
            val retryable = status == null || status == 429 || status in 500..599
            if (!retryable || attempt + 1 >= configuredProfile.state.settings.maxAttempts) sourceFailure(failureCode)

            val retryAfter =
                response
                    ?.takeIf { configuredProfile.state.settings.honorRetryAfter }
                    ?.headers()
                    ?.firstValue("Retry-After")
                    ?.orElse(null)
                    ?.let(::retryAfterMillis)
            if (retryAfter != null && retryAfter > configuredProfile.state.settings.timeoutMillis) {
                sourceFailure("SOURCE_RETRY_AFTER_TOO_LONG")
            }
            val delay = retryAfter ?: exponentialBackoffMillis(attempt, configuredProfile.state.settings.timeoutMillis)
            waitCancellable(TimeUnit.MILLISECONDS.toNanos(delay), budget, checkCancelled)
            attempt++
        }
    }

    private fun send(
        request: HttpRequest,
        timeoutMillis: Long,
        checkCancelled: () -> Unit,
        responseLimit: Int,
    ): HttpResponse<ByteArray> {
        val future = client.sendAsync(request, BoundedBodyHandler(responseLimit))
        val deadline = saturatedDeadline(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeoutMillis))
        while (true) {
            try {
                checkCancelled()
            } catch (failure: Throwable) {
                future.cancel(true)
                throw failure
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                future.cancel(true)
                throw AttemptFailure("SOURCE_TIMEOUT", true)
            }
            try {
                return future.get(min(remaining, CANCELLATION_POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Poll cancellation while the request is active.
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw SourceHttpFailure("SOURCE_CANCELLED")
            } catch (failure: ExecutionException) {
                when (unwrap(failure)) {
                    is BodyLimitFailure -> throw AttemptFailure("SOURCE_RESPONSE_TOO_LARGE", false)
                    is HttpTimeoutException -> throw AttemptFailure("SOURCE_TIMEOUT", true)
                    is IOException -> throw AttemptFailure("SOURCE_HTTP_ERROR", true)
                    else -> throw AttemptFailure("SOURCE_HTTP_ERROR", true)
                }
            } catch (_: java.util.concurrent.CancellationException) {
                throw AttemptFailure("SOURCE_HTTP_ERROR", true)
            }
        }
    }
}

private data class ConfiguredProfile(
    val profile: SourceProfile,
    val endpoint: URI,
    val state: OriginState,
)

private data class OriginKey(
    val scheme: String,
    val host: String,
    val port: Int,
)

private data class OriginSettings(
    val requestsPerSecond: Double,
    val burst: Int,
    val maxConcurrent: Int,
    val timeoutMillis: Long,
    val maxAttempts: Int,
    val honorRetryAfter: Boolean,
)

private class OriginState(
    val settings: OriginSettings,
) {
    private val concurrency = Semaphore(settings.maxConcurrent, true)
    private val tokenLock = Any()
    private var tokens = 0.0
    private var lastRefillNanos = System.nanoTime()
    private var started = false

    fun acquireToken(
        budget: SourceBudget,
        checkCancelled: () -> Unit,
    ) {
        while (true) {
            checkCancelled()
            val waitNanos =
                synchronized(tokenLock) {
                    val now = System.nanoTime()
                    val elapsed = if (started) (now - lastRefillNanos).coerceAtLeast(0) else 0
                    started = true
                    tokens = min(settings.burst.toDouble(), tokens + elapsed.toDouble() * settings.requestsPerSecond / NANOS_PER_SECOND)
                    lastRefillNanos = now
                    if (tokens >= 1.0) {
                        tokens -= 1.0
                        0L
                    } else {
                        ceil((1.0 - tokens) * NANOS_PER_SECOND / settings.requestsPerSecond).toLong().coerceAtLeast(1)
                    }
                }
            if (waitNanos == 0L) return
            waitCancellable(waitNanos, budget, checkCancelled)
        }
    }

    fun acquireConcurrency(
        budget: SourceBudget,
        checkCancelled: () -> Unit,
    ) {
        while (true) {
            checkCancelled()
            val started = System.nanoTime()
            val acquired =
                try {
                    concurrency.tryAcquire(CANCELLATION_POLL_NANOS, TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw SourceHttpFailure("SOURCE_CANCELLED")
                } finally {
                    budget.addThrottleWait(TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0)))
                }
            if (acquired) return
        }
    }

    fun releaseConcurrency() {
        concurrency.release()
    }
}

private class BoundedBodyHandler(
    private val maxBytes: Int,
) : HttpResponse.BodyHandler<ByteArray> {
    override fun apply(responseInfo: HttpResponse.ResponseInfo): HttpResponse.BodySubscriber<ByteArray> = BoundedBodySubscriber(maxBytes)
}

private class BoundedBodySubscriber(
    private val maxBytes: Int,
) : HttpResponse.BodySubscriber<ByteArray> {
    private val body = ByteArrayOutputStream()
    private val result = CompletableFuture<ByteArray>()
    private lateinit var subscription: Flow.Subscription
    private var size = 0

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        if (this::subscription.isInitialized) {
            subscription.cancel()
            return
        }
        this.subscription = subscription
        subscription.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        val incoming = items.sumOf { it.remaining().toLong() }
        if (incoming > maxBytes.toLong() - size) {
            subscription.cancel()
            result.completeExceptionally(BodyLimitFailure())
            return
        }
        items.forEach { buffer ->
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            body.write(bytes)
            size += bytes.size
        }
        subscription.request(1)
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(body.toByteArray())
    }
}

private fun strictest(values: List<SourceGovernor>): OriginSettings =
    OriginSettings(
        values.minOf(SourceGovernor::requestsPerSecond),
        values.minOf(SourceGovernor::burst),
        values.minOf(SourceGovernor::maxConcurrent),
        values.minOf(SourceGovernor::timeoutMillis),
        values.minOf(SourceGovernor::maxAttempts),
        values.any(SourceGovernor::honorRetryAfter),
    )

private fun origin(profile: SourceProfile): OriginKey {
    validateProfileUrl(profile)
    val uri = profile.baseUrl
    val scheme = uri.scheme.lowercase()
    val port =
        if (uri.port >= 0) {
            uri.port
        } else if (scheme == "https") {
            443
        } else {
            80
        }
    return OriginKey(scheme, uri.host.lowercase(), port)
}

private fun endpoint(profile: SourceProfile): URI {
    validateProfileUrl(profile)
    val basePath =
        profile.baseUrl.path
            .orEmpty()
            .trimEnd('/')
    val suffix =
        when (profile.transport) {
            SourceTransport.DIRECT -> {
                if (profile.datasourceUid != null) sourceFailure("SOURCE_PROFILE_INVALID")
                when (profile.sourceKind) {
                    SourceKind.INFLUXDB -> "/query"
                    SourceKind.OPENSEARCH -> {
                        val indices = (profile.openSearch ?: sourceFailure("SOURCE_PROFILE_INVALID")).indices
                        "/${indices.joinToString(",")}/_search"
                    }
                    else -> "/api/v1/query_range"
                }
            }

            SourceTransport.GRAFANA_PROXY -> {
                if (profile.sourceKind == SourceKind.OPENSEARCH) sourceFailure("SOURCE_PROFILE_INVALID")
                val uid =
                    profile.datasourceUid
                        ?.takeIf { it !in setOf(".", "..") && SAFE_PATH_SEGMENT.matches(it) }
                        ?: sourceFailure("SOURCE_PROFILE_INVALID")
                if (profile.sourceKind == SourceKind.INFLUXDB) {
                    "/api/datasources/proxy/uid/$uid/query"
                } else {
                    "/api/datasources/proxy/uid/$uid/api/v1/query_range"
                }
            }
        }
    return try {
        URI(
            profile.baseUrl.scheme.lowercase(),
            null,
            profile.baseUrl.host.lowercase(),
            profile.baseUrl.port,
            "$basePath$suffix",
            null,
            null,
        )
    } catch (_: Exception) {
        sourceFailure("SOURCE_PROFILE_INVALID")
    }
}

private fun validateProfileUrl(profile: SourceProfile) {
    val uri = profile.baseUrl
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null || uri.isOpaque || uri.port == 0 || uri.port > 65_535) {
        sourceFailure("SOURCE_PROFILE_INVALID")
    }
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || '%' in uri.rawPath.orEmpty()) {
        sourceFailure("SOURCE_PROFILE_INVALID")
    }
    if (uri.path
            .orEmpty()
            .split('/')
            .any { it == "." || it == ".." }
    ) {
        sourceFailure("SOURCE_PROFILE_INVALID")
    }
    if (uri.scheme.equals("http", true) && profile.auth != SourceAuth.None && !profile.allowInsecureHttp) {
        sourceFailure("SOURCE_PROFILE_INVALID")
    }
}

private fun uriWithQuery(
    endpoint: URI,
    parameters: Map<String, String>,
): URI {
    if (parameters.keys.any { it.isEmpty() || it.any(Char::isISOControl) }) sourceFailure("SOURCE_QUERY_INVALID")
    val query =
        parameters
            .toSortedMap()
            .entries
            .joinToString("&") { (key, value) -> "${encodeQuery(key)}=${encodeQuery(value)}" }
    return try {
        URI("${endpoint.toASCIIString()}${if (query.isEmpty()) "" else "?$query"}")
    } catch (_: Exception) {
        sourceFailure("SOURCE_QUERY_INVALID")
    }
}

private fun encodeQuery(value: String): String =
    URLEncoder
        .encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")

private fun authorization(
    auth: SourceAuth,
    environment: (String) -> String?,
): String? =
    when (auth) {
        SourceAuth.None -> null
        is SourceAuth.Bearer -> "Bearer ${credential(auth.tokenEnv, environment)}"
        is SourceAuth.Token -> "Token ${credential(auth.tokenEnv, environment)}"
        is SourceAuth.Basic -> {
            val username = credential(auth.usernameEnv, environment)
            val password = credential(auth.passwordEnv, environment)
            "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))}"
        }
    }

private fun credential(
    name: String,
    environment: (String) -> String?,
): String {
    val value = environment(name)?.takeIf(String::isNotEmpty) ?: sourceFailure("SOURCE_AUTH_UNAVAILABLE")
    if (value.any(Char::isISOControl)) sourceFailure("SOURCE_AUTH_INVALID")
    return value
}

private fun SourceHttp.request(
    uri: URI,
    authorization: String?,
    settings: OriginSettings,
    body: ByteArray?,
): HttpRequest =
    try {
        HttpRequest
            .newBuilder(uri)
            .timeout(Duration.ofMillis(settings.timeoutMillis))
            .apply {
                if (body == null) {
                    GET()
                } else {
                    header("Accept", "application/json")
                    header("Content-Type", "application/json")
                    POST(HttpRequest.BodyPublishers.ofByteArray(body))
                }
            }.apply { if (authorization != null) header("Authorization", authorization) }
            .build()
    } catch (_: IllegalArgumentException) {
        sourceFailure("SOURCE_REQUEST_INVALID")
    }

private fun retryAfterMillis(value: String): Long? {
    value.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        return if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
    }
    return try {
        Math
            .subtractExact(
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli(),
                System.currentTimeMillis(),
            ).coerceAtLeast(0)
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

private fun exponentialBackoffMillis(
    attempt: Int,
    capMillis: Long,
): Long = min(capMillis, RETRY_BACKOFF_MILLIS shl min(attempt, 20))

private fun waitCancellable(
    requestedNanos: Long,
    budget: SourceBudget,
    checkCancelled: () -> Unit,
) {
    val started = System.nanoTime()
    val deadline = saturatedDeadline(started, requestedNanos)
    try {
        while (true) {
            checkCancelled()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return
            LockSupport.parkNanos(min(remaining, CANCELLATION_POLL_NANOS))
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt()
                throw SourceHttpFailure("SOURCE_CANCELLED")
            }
        }
    } finally {
        budget.addThrottleWait(TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - started).coerceAtLeast(0)))
    }
}

private fun saturatedDeadline(
    now: Long,
    delay: Long,
): Long =
    try {
        Math.addExact(now, delay)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

private fun unwrap(failure: Throwable): Throwable {
    var current = failure
    while (current is ExecutionException || current is java.util.concurrent.CompletionException) {
        current = current.cause ?: return current
    }
    return current
}

private fun sourceFailure(code: String): Nothing = throw SourceHttpFailure(code)

private class AttemptFailure(
    val code: String,
    val retryable: Boolean,
) : Exception()

private class BodyLimitFailure : Exception()

private const val MAX_HTTP_RESPONSE_BYTES = 16 * 1024 * 1024
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val RETRY_BACKOFF_MILLIS = 100L
private val CANCELLATION_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(25)
private val SAFE_PATH_SEGMENT = Regex("[A-Za-z0-9._~-]{1,128}")

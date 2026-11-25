package io.ltverdict.mcp

import io.ltverdict.core.canonicalJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

internal class McpApiException(
    val statusCode: Int,
    val code: String,
    override val message: String,
    val details: JsonArray = JsonArray(emptyList()),
) : RuntimeException(message)

internal sealed interface MultipartPart {
    val name: String

    data class Text(
        override val name: String,
        val value: String,
    ) : MultipartPart

    data class JsonBytes(
        override val name: String,
        val filename: String,
        val bytes: ByteArray,
    ) : MultipartPart
}

internal class McpApiClient(
    rawUrl: String,
    private val requestTimeout: Duration = Duration.ofSeconds(60),
) {
    val origin: String = validateLoopbackOrigin(rawUrl)

    private val client: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    private val authLock = Any()
    private var sessionToken: String? = null
    private var csrfToken: String? = null

    fun get(
        pathAndQuery: String,
        maxBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    ): JsonObject {
        val response = execute("GET", pathAndQuery, body = null, contentType = null, mutating = false, maxBytes = maxBytes)
        if (response.statusCode >= 400) throw parseApiError(response.statusCode, response.body)
        return parseJsonObject(response.statusCode, response.body)
    }

    fun postJson(
        pathAndQuery: String,
        body: JsonElement,
        allowUnprocessable: Boolean = false,
    ): Pair<Int, JsonObject> = sendJson("POST", pathAndQuery, body, allowUnprocessable)

    fun putJson(
        pathAndQuery: String,
        body: JsonElement,
    ): Pair<Int, JsonObject> = sendJson("PUT", pathAndQuery, body, allowUnprocessable = false)

    fun uploadInputFile(file: Path): JsonObject {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw McpApiException(400, "INVALID_INPUT", "input_path must point to a regular non-symlink file")
        }
        val filename = safeFilename(file.fileName?.toString() ?: "input.jtl")
        val boundary = "ltv-mcp-${UUID.randomUUID()}"
        val header =
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray(UTF_8)
        val trailer = "\r\n--$boundary--\r\n".toByteArray(UTF_8)
        val response =
            executeMultipartStream(
                "/api/inputs",
                boundary,
                header,
                file,
                trailer,
            )
        if (response.statusCode >= 400) throw parseApiError(response.statusCode, response.body)
        return parseJsonObject(response.statusCode, response.body)
    }

    fun postMultipartJob(parts: List<MultipartPart>): JsonObject {
        val boundary = "ltv-mcp-${UUID.randomUUID()}"
        val payload = encodeMultipart(boundary, parts)
        val response =
            execute(
                "POST",
                "/api/jobs",
                body = payload,
                contentType = "multipart/form-data; boundary=$boundary",
                mutating = true,
                maxBytes = DEFAULT_MAX_RESPONSE_BYTES,
            )
        if (response.statusCode >= 400) throw parseApiError(response.statusCode, response.body)
        return parseJsonObject(response.statusCode, response.body)
    }

    private fun sendJson(
        method: String,
        pathAndQuery: String,
        body: JsonElement,
        allowUnprocessable: Boolean,
    ): Pair<Int, JsonObject> {
        val payload = canonicalJson(body)
        val response =
            execute(
                method,
                pathAndQuery,
                body = payload,
                contentType = "application/json",
                mutating = true,
                maxBytes = DEFAULT_MAX_RESPONSE_BYTES,
            )
        if (response.statusCode >= 400 && !(allowUnprocessable && response.statusCode == 422)) {
            throw parseApiError(response.statusCode, response.body)
        }
        return response.statusCode to parseJsonObject(response.statusCode, response.body)
    }

    private data class RawResponse(
        val statusCode: Int,
        val body: ByteArray,
    )

    private fun execute(
        method: String,
        pathAndQuery: String,
        body: ByteArray?,
        contentType: String?,
        mutating: Boolean,
        maxBytes: Int,
    ): RawResponse =
        withBootstrapRetry(mutating) { creds ->
            val builder =
                HttpRequest
                    .newBuilder(URI.create(origin + pathAndQuery))
                    .timeout(requestTimeout)
            if (mutating) {
                val (session, csrf) = checkNotNull(creds)
                builder.header("Origin", origin)
                builder.header("Cookie", "ltv_session=$session")
                builder.header("X-LTV-CSRF", csrf)
            }
            if (contentType != null) builder.header("Content-Type", contentType)
            val publisher = body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
            builder.method(method, publisher)
            sendBounded(builder.build(), maxBytes)
        }

    private fun executeMultipartStream(
        pathAndQuery: String,
        boundary: String,
        header: ByteArray,
        file: Path,
        trailer: ByteArray,
    ): RawResponse =
        withBootstrapRetry(mutating = true) { creds ->
            val (session, csrf) = checkNotNull(creds)
            val chunks =
                Iterable {
                    iterator {
                        yield(header)
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                yield(buffer.copyOf(read))
                            }
                        }
                        yield(trailer)
                    }
                }
            val request =
                HttpRequest
                    .newBuilder(URI.create(origin + pathAndQuery))
                    .timeout(Duration.ofMinutes(5))
                    .header("Origin", origin)
                    .header("Cookie", "ltv_session=$session")
                    .header("X-LTV-CSRF", csrf)
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofByteArrays(chunks))
                    .build()
            sendBounded(request, DEFAULT_MAX_RESPONSE_BYTES)
        }

    private fun withBootstrapRetry(
        mutating: Boolean,
        block: (Pair<String, String>?) -> RawResponse,
    ): RawResponse {
        val creds = if (mutating) ensureBootstrap(forceRefresh = false) else null
        val first = block(creds)
        if (!mutating || first.statusCode != 403) return first
        val refreshed = ensureBootstrap(forceRefresh = true)
        return block(refreshed)
    }

    private fun ensureBootstrap(forceRefresh: Boolean): Pair<String, String> =
        synchronized(authLock) {
            val currentSession = sessionToken
            val currentCsrf = csrfToken
            if (!forceRefresh && currentSession != null && currentCsrf != null) {
                return@synchronized currentSession to currentCsrf
            }
            val request =
                HttpRequest
                    .newBuilder(URI.create("$origin/api/bootstrap"))
                    .timeout(requestTimeout)
                    .GET()
                    .build()
            val response = sendBounded(request, DEFAULT_MAX_RESPONSE_BYTES)
            if (response.statusCode != 200) {
                throw parseApiError(response.statusCode, response.body)
            }
            val body = parseJsonObject(response.statusCode, response.body)
            val csrf =
                (body["csrf_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw McpApiException(502, "BOOTSTRAP_INVALID", "Bootstrap response did not include csrf_token")
            val session =
                latestSessionToken ?: throw McpApiException(502, "BOOTSTRAP_INVALID", "Bootstrap response did not set ltv_session cookie")
            sessionToken = session
            csrfToken = csrf
            session to csrf
        }

    @Volatile
    private var latestSessionToken: String? = null

    private fun sendBounded(
        request: HttpRequest,
        maxBytes: Int,
    ): RawResponse {
        val response =
            try {
                client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (_: IOException) {
                throw McpApiException(503, "LOCAL_API_UNAVAILABLE", "Local LT Verdict API is unreachable")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw McpApiException(503, "LOCAL_API_INTERRUPTED", "Local LT Verdict API request was interrupted")
            }
        response.headers().allValues("Set-Cookie").forEach { header ->
            parseSessionCookie(header)?.let { latestSessionToken = it }
        }
        val body =
            response.body().use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buf)
                    if (read == -1) break
                    total += read
                    if (total > maxBytes) {
                        throw McpApiException(502, "RESPONSE_TOO_LARGE", "Local API response exceeded MCP size bound")
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        return RawResponse(response.statusCode(), body)
    }

    private fun parseJsonObject(
        statusCode: Int,
        bytes: ByteArray,
    ): JsonObject =
        try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: SerializationException) {
            throw McpApiException(statusCode, "INVALID_API_RESPONSE", "Local API returned invalid JSON")
        } catch (_: IllegalArgumentException) {
            throw McpApiException(statusCode, "INVALID_API_RESPONSE", "Local API returned non-object JSON")
        }

    private fun parseApiError(
        statusCode: Int,
        bytes: ByteArray,
    ): McpApiException {
        val parsed =
            try {
                Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            } catch (_: Exception) {
                null
            }
        val error = parsed?.get("error") as? JsonObject
        val code =
            error?.get("code")?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                ?: "HTTP_$statusCode"
        val message =
            error?.get("message")?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
                ?: "Local API request failed with status $statusCode"
        val details = (error?.get("details") as? JsonArray) ?: JsonArray(emptyList())
        return McpApiException(statusCode, code, message, details)
    }

    companion object {
        internal const val DEFAULT_MAX_RESPONSE_BYTES = 16 * 1024 * 1024

        internal fun validateLoopbackOrigin(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            val uri =
                try {
                    URI(trimmed)
                } catch (_: URISyntaxException) {
                    throw IllegalArgumentException("INVALID_MCP_URL")
                }
            require(uri.scheme == "http") { "INVALID_MCP_URL" }
            require(uri.rawUserInfo == null) { "INVALID_MCP_URL" }
            require(uri.host == "127.0.0.1") { "INVALID_MCP_URL" }
            require(uri.port in 1..65_535) { "INVALID_MCP_URL" }
            require(uri.rawPath.isNullOrEmpty()) { "INVALID_MCP_URL" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "INVALID_MCP_URL" }
            return "http://127.0.0.1:${uri.port}"
        }

        internal fun encodeQuery(params: List<Pair<String, String>>): String =
            if (params.isEmpty()) {
                ""
            } else {
                params.joinToString("&", prefix = "?") { (k, v) ->
                    "${URLEncoder.encode(k, UTF_8)}=${URLEncoder.encode(v, UTF_8)}"
                }
            }
    }
}

private fun parseSessionCookie(header: String): String? {
    val firstSegment = header.substringBefore(';').trim()
    if (!firstSegment.startsWith("ltv_session=")) return null
    return firstSegment.removePrefix("ltv_session=").takeIf { it.isNotEmpty() }
}

private fun safeFilename(raw: String): String =
    raw.map { ch -> if (ch.isISOControl() || ch == '"' || ch == '\\' || ch == '/' || ch == ':') '_' else ch }.joinToString("")

private fun encodeMultipart(
    boundary: String,
    parts: List<MultipartPart>,
): ByteArray {
    val out = ByteArrayOutputStream()
    parts.forEach { part ->
        out.write("--$boundary\r\n".toByteArray(UTF_8))
        when (part) {
            is MultipartPart.Text -> {
                out.write("Content-Disposition: form-data; name=\"${part.name}\"\r\n\r\n".toByteArray(UTF_8))
                out.write(part.value.toByteArray(UTF_8))
                out.write("\r\n".toByteArray(UTF_8))
            }
            is MultipartPart.JsonBytes -> {
                out.write(
                    "Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.filename}\"\r\n"
                        .toByteArray(UTF_8),
                )
                out.write("Content-Type: application/json\r\n\r\n".toByteArray(UTF_8))
                out.write(part.bytes)
                out.write("\r\n".toByteArray(UTF_8))
            }
        }
    }
    out.write("--$boundary--\r\n".toByteArray(UTF_8))
    return out.toByteArray()
}

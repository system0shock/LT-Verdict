package io.ltverdict.integrations.report

import java.net.URI
import java.util.concurrent.CancellationException

internal enum class ConfluencePublishStatus {
    NOT_CONFIGURED,
    PUBLISHING,
    PUBLISHED,
    FAILED,
}

internal data class ConfluencePublishRequest(
    val title: String,
    val storageXhtml: ByteArray,
)

internal fun interface ConfluencePublishStrategy {
    fun publish(request: ConfluencePublishRequest): URI
}

internal data class ConfluencePublishResult(
    val status: ConfluencePublishStatus,
    val pageUrl: URI? = null,
    val failureCode: String? = null,
)

internal class ConfluencePublisher(
    private val strategy: ConfluencePublishStrategy?,
) {
    @Volatile
    var status: ConfluencePublishStatus =
        if (strategy == null) ConfluencePublishStatus.NOT_CONFIGURED else ConfluencePublishStatus.PUBLISHING
        private set

    @Synchronized
    fun publish(request: ConfluencePublishRequest): ConfluencePublishResult {
        validate(request)
        val configured = strategy ?: return ConfluencePublishResult(ConfluencePublishStatus.NOT_CONFIGURED)
        status = ConfluencePublishStatus.PUBLISHING
        return try {
            val pageUrl = configured.publish(request)
            if (!safePageUrl(pageUrl)) throw IllegalArgumentException("unsafe page URL")
            status = ConfluencePublishStatus.PUBLISHED
            ConfluencePublishResult(status, pageUrl)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            status = ConfluencePublishStatus.FAILED
            ConfluencePublishResult(status, failureCode = "CONFLUENCE_PUBLISH_FAILED")
        }
    }
}

private fun validate(request: ConfluencePublishRequest) {
    if (request.title.isBlank() || request.title.length > 256 || request.title.any(Char::isISOControl)) {
        throw IllegalArgumentException("CONFLUENCE_REQUEST_INVALID")
    }
    if (request.storageXhtml.isEmpty() || request.storageXhtml.size > MAX_STORAGE_BYTES) {
        throw IllegalArgumentException("CONFLUENCE_REQUEST_INVALID")
    }
    request.storageXhtml.decodeToString(throwOnInvalidSequence = true)
}

private fun safePageUrl(value: URI): Boolean =
    value.scheme?.lowercase() in setOf("http", "https") &&
        value.host != null &&
        !value.isOpaque &&
        value.rawUserInfo == null &&
        value.rawQuery == null &&
        value.rawFragment == null

private const val MAX_STORAGE_BYTES = 16 * 1024 * 1024

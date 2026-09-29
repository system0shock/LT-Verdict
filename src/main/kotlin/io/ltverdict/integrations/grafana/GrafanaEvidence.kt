package io.ltverdict.integrations.grafana

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import io.ltverdict.sources.SourceHttpFailure
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceTransport
import java.net.URI
import java.util.concurrent.CancellationException

internal data class GrafanaPanelRequest(
    val dashboardUid: String,
    val panelId: Int,
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val width: Int = 1_200,
    val height: Int = 500,
    val theme: String = "light",
)

internal data class GrafanaRenderedPanel(
    val sourceLink: URI,
    val png: ByteArray?,
    val failureCode: String?,
)

internal fun grafanaPanelLink(
    profile: SourceProfile,
    request: GrafanaPanelRequest,
): URI = grafanaUri(profile, request, render = false)

internal fun grafanaRenderUri(
    profile: SourceProfile,
    request: GrafanaPanelRequest,
): URI = grafanaUri(profile, request, render = true)

internal fun renderGrafanaPanel(
    profile: SourceProfile,
    request: GrafanaPanelRequest,
    fetch: () -> ByteArray,
): GrafanaRenderedPanel {
    val link = grafanaPanelLink(profile, request)
    return try {
        val png = fetch()
        if (png.size > MAX_GRAFANA_RENDER_BYTES || !png.startsWith(PNG_SIGNATURE)) {
            GrafanaRenderedPanel(link, null, "GRAFANA_RENDER_INVALID")
        } else {
            GrafanaRenderedPanel(link, png, null)
        }
    } catch (failure: SourceHttpFailure) {
        GrafanaRenderedPanel(link, null, failure.code)
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        GrafanaRenderedPanel(link, null, "GRAFANA_RENDER_FAILED")
    }
}

internal fun validateGrafanaPanelRequest(
    profile: SourceProfile,
    request: GrafanaPanelRequest,
) {
    val base = profile.baseUrl
    if (profile.transport != SourceTransport.GRAFANA_PROXY ||
        base.scheme?.lowercase() !in setOf("http", "https") ||
        base.host == null ||
        base.isOpaque ||
        base.rawUserInfo != null ||
        base.rawQuery != null ||
        base.rawFragment != null ||
        '%' in base.rawPath.orEmpty() ||
        base.path
            .orEmpty()
            .split('/')
            .any { it == "." || it == ".." }
    ) {
        grafanaInvalid()
    }
    if (!DASHBOARD_UID.matches(request.dashboardUid) ||
        request.panelId < 1 ||
        request.fromEpochMillis !in 0 until MAX_TIMESTAMP_EPOCH_MILLIS ||
        request.toEpochMillis !in 1..MAX_TIMESTAMP_EPOCH_MILLIS ||
        request.toEpochMillis <= request.fromEpochMillis ||
        request.toEpochMillis - request.fromEpochMillis > MAX_RANGE_MILLIS ||
        request.width !in 320..2_400 ||
        request.height !in 200..1_600 ||
        request.theme !in setOf("light", "dark")
    ) {
        grafanaInvalid()
    }
}

private fun grafanaUri(
    profile: SourceProfile,
    request: GrafanaPanelRequest,
    render: Boolean,
): URI {
    validateGrafanaPanelRequest(profile, request)
    val basePath =
        profile.baseUrl.path
            .orEmpty()
            .trimEnd('/')
    val prefix = if (render) "/render/d-solo/" else "/d-solo/"
    val query =
        buildList {
            add("panelId=${request.panelId}")
            add("from=${request.fromEpochMillis}")
            add("to=${request.toEpochMillis}")
            if (render) {
                add("width=${request.width}")
                add("height=${request.height}")
            }
            add("theme=${request.theme}")
        }.joinToString("&")
    return try {
        URI(
            profile.baseUrl.scheme.lowercase(),
            null,
            profile.baseUrl.host.lowercase(),
            profile.baseUrl.port,
            "$basePath$prefix${request.dashboardUid}",
            query,
            null,
        )
    } catch (_: Exception) {
        grafanaInvalid()
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun grafanaInvalid(): Nothing = throw IllegalArgumentException("GRAFANA_REQUEST_INVALID")

internal const val MAX_GRAFANA_RENDER_BYTES = 8 * 1024 * 1024
private const val MAX_RANGE_MILLIS = 31L * 24 * 60 * 60 * 1_000
private val DASHBOARD_UID = Regex("[A-Za-z0-9_-]{1,128}")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

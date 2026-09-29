package io.ltverdict.integrations.grafana

import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import io.ltverdict.sources.SourceAuth
import io.ltverdict.sources.SourceHttpFailure
import io.ltverdict.sources.SourceKind
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceQuery
import io.ltverdict.sources.SourceTransport
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class GrafanaEvidenceTest {
    @Test
    fun `panel and render URLs stay on configured Grafana origin and fixed paths`() {
        val profile = profile(URI("https://grafana.example/tenant"))
        val request = GrafanaPanelRequest("load-main", 7, 1_000, 4_000, 1200, 500, "dark")

        val link = grafanaPanelLink(profile, request)
        val render = grafanaRenderUri(profile, request)

        assertEquals("https://grafana.example/tenant/d-solo/load-main?panelId=7&from=1000&to=4000&theme=dark", link.toString())
        assertEquals(
            "https://grafana.example/tenant/render/d-solo/load-main?panelId=7&from=1000&to=4000&width=1200&height=500&theme=dark",
            render.toString(),
        )
        assertNull(link.userInfo)
        assertNull(render.userInfo)
    }

    @Test
    fun `unsafe dashboard window and non Grafana profiles are rejected`() {
        val profile = profile(URI("https://grafana.example/"))
        val invalid =
            listOf(
                GrafanaPanelRequest("../admin", 1, 0, 1),
                GrafanaPanelRequest("safe", 0, 0, 1),
                GrafanaPanelRequest("safe", 1, 2, 1),
                GrafanaPanelRequest("safe", 1, 0, 32L * 24 * 60 * 60 * 1000),
            )
        invalid.forEach { request ->
            assertEquals(
                "GRAFANA_REQUEST_INVALID",
                assertThrows(IllegalArgumentException::class.java) { grafanaRenderUri(profile, request) }.message,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            grafanaRenderUri(profile.copy(transport = SourceTransport.DIRECT), GrafanaPanelRequest("safe", 1, 0, 1))
        }
    }

    @Test
    fun `render is fail soft and accepts only bounded PNG evidence`() {
        val profile = profile(URI("https://grafana.example/"))
        val request = GrafanaPanelRequest("safe", 1, 0, 1)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

        val rendered = renderGrafanaPanel(profile, request) { png }
        val transportFailure = renderGrafanaPanel(profile, request) { throw SourceHttpFailure("SOURCE_TIMEOUT") }
        val invalid = renderGrafanaPanel(profile, request) { "not-png".encodeToByteArray() }

        assertArrayEquals(png, rendered.png)
        assertNull(rendered.failureCode)
        assertNull(transportFailure.png)
        assertEquals("SOURCE_TIMEOUT", transportFailure.failureCode)
        assertNull(invalid.png)
        assertEquals("GRAFANA_RENDER_INVALID", invalid.failureCode)
        assertTrue(rendered.sourceLink.toString().startsWith("https://grafana.example/d-solo/safe"))
    }

    private fun profile(baseUrl: URI): SourceProfile =
        SourceProfile(
            id = "grafana",
            sourceKind = SourceKind.PROMETHEUS,
            transport = SourceTransport.GRAFANA_PROXY,
            baseUrl = baseUrl,
            datasourceUid = "prometheus",
            auth = SourceAuth.None,
            queries =
                listOf(
                    SourceQuery(
                        "q",
                        "rate(x[${'$'}__interval])",
                        "x",
                        "ratio",
                        "entity",
                        ResourceRole.SYSTEM,
                        ResourceAggregation.INTERVAL_RATE,
                        emptyMap(),
                    ),
                ),
        )
}

@file:Suppress("ktlint:standard:filename")

package io.ltverdict.web

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.awt.Desktop
import java.net.URI

internal class StartedLocalServer(
    val engine: ApplicationEngine,
    val origin: String,
) : AutoCloseable {
    override fun close() = engine.stop(1_000, 5_000)
}

internal fun startLocalServer(
    context: LocalApiContext,
    port: Int = 0,
    openBrowser: Boolean = true,
): StartedLocalServer {
    val server =
        embeddedServer(Netty, host = LOOPBACK_HOST, port = port) {
            installLocalApi(context)
        }.start(wait = false)
    val engine = server.engine
    return try {
        val connector = runBlocking { engine.resolvedConnectors().single() }
        val origin = "http://$LOOPBACK_HOST:${connector.port}"
        if (openBrowser) openBrowser(origin)
        StartedLocalServer(engine, origin)
    } catch (failure: Exception) {
        engine.stop(1_000, 5_000)
        throw failure
    }
}

private fun openBrowser(origin: String) {
    try {
        check(Desktop.isDesktopSupported())
        Desktop.getDesktop().browse(URI(origin))
    } catch (_: Exception) {
        System.err.println(origin)
    }
}

private const val LOOPBACK_HOST = "127.0.0.1"

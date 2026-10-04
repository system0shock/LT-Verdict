package io.ltverdict.e2e

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.OnlineSourceFixture
import io.ltverdict.sources.PromqlSource
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val dataDirectory = DataDirectory.open(Path.of(checkNotNull(System.getProperty("e2eDataDir"))))
    val store = RunBundleStore(dataDirectory)
    val service = AnalysisService(store, EngineConfig())
    val sourceFixture = OnlineSourceFixture()
    val profiles = readSourceProfiles(sourceFixture.profilesJson().byteInputStream())
    val source = PromqlSource(profiles, SourceHttp(profiles))
    val jobs =
        AnalysisJobs(1) { request, processedBytes, checkCancelled ->
            if (request.input.originalFilename == BLOCKING_INPUT) blockUntilCancelled()
            analyzeWithSources(service, request, source, processedBytes, checkCancelled)
        }
    val rawPort = System.getenv("LTV_E2E_PORT")
    val port =
        if (rawPort.isNullOrBlank()) {
            18_473
        } else {
            rawPort.toIntOrNull()?.takeIf { it in 1024..65535 }
                ?: error("Invalid LTV_E2E_PORT: $rawPort (expected integer 1024..65535)")
        }
    val server = startLocalServer(LocalApiContext(store, jobs, profiles), port = port, openBrowser = false)
    val stopped = CountDownLatch(1)
    val closed = AtomicBoolean()
    val close = {
        if (closed.compareAndSet(false, true)) {
            try {
                server.close()
            } finally {
                try {
                    jobs.close()
                } finally {
                    dataDirectory.close()
                    sourceFixture.close()
                    stopped.countDown()
                }
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread(close, "lt-verdict-e2e-shutdown"))
    stopped.await()
}

private fun blockUntilCancelled(): Nothing {
    CountDownLatch(1).await()
    error("blocking E2E analysis was released")
}

private const val BLOCKING_INPUT = "sustained.jtl"

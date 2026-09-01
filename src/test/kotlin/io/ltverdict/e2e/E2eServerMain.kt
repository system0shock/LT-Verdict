package io.ltverdict.e2e

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.jobs.AnalysisJobs
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
    val jobs = AnalysisJobs(1, AnalysisService(store, EngineConfig())::analyze)
    val server = startLocalServer(LocalApiContext(store, jobs), port = 18_473, openBrowser = false)
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
                    stopped.countDown()
                }
            }
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread(close, "lt-verdict-e2e-shutdown"))
    stopped.await()
}

package io.ltverdict.sources

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

internal class OnlineSourceFixture : AutoCloseable {
    val requests = AtomicInteger()

    @Volatile
    var responseStatus = 200
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/api/v1/query_range") { exchange ->
            requests.incrementAndGet()
            val bytes =
                """{"status":"success","data":{"resultType":"matrix",
                    "result":[{"metric":{"instance":"host"},"values":[[1767225601,"0.9"]]}]}}""".encodeToByteArray()
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun profilesJson(): String =
        """
        {"schema_version":"source-connections.v1","connections":[{
          "id":"local","source_kind":"prometheus","transport":"direct",
          "base_url":"http://127.0.0.1:${server.address.port}",
          "governor":{"requests_per_second":100,"timeout_ms":1000,"max_attempts":1},
          "queries":[{"id":"cpu","expression":"avg_over_time(cpu[${'$'}__interval])",
            "metric":"cpu_used","unit":"ratio","entity":"host","role":"system",
            "aggregation":"interval_mean","labels":{"instance":"host"}}],
          "rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt",
            "threshold":0.8,"min_consecutive_cells":1,"effect":"sla"}]
        }]}
        """.trimIndent()

    override fun close() = server.stop(0)
}

internal const val ONLINE_SOURCE_REQUEST = """{"schema_version":"source-request.v1","profile_id":"local",
    "start_epoch_ms":1767225600000,"end_epoch_ms":1767225601000,"step_ms":1000}"""
internal const val ONLINE_LOAD =
    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,success,bytes,sentBytes," +
        "grpThreads,allThreads,Latency,IdleTime,Connect\n" +
        "1767225600000,1000,request,200,OK,thread,true,1,1,1,1,1,0,0\n"

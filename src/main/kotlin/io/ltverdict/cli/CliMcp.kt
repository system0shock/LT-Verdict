package io.ltverdict.cli

import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AiAdviceStore
import io.ltverdict.ai.advisoryAiSetup
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.mcp.McpApiClient
import io.ltverdict.mcp.McpServer
import io.ltverdict.mcp.McpTools
import io.ltverdict.sources.SourceInputFailure
import io.ltverdict.sources.SourceRegistry
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path

internal fun mcpCommand(
    args: List<String>,
    stdout: PrintStream,
    stderr: PrintStream,
    stdin: InputStream,
): Int {
    var url: String? = null
    var dataDir: Path? = null
    var connectionsPath: Path? = null
    var index = 0
    while (index < args.size) {
        if (index + 1 >= args.size) usage()
        when (args[index]) {
            "--url" -> {
                if (url != null) usage()
                url = args[index + 1]
            }
            "--data-dir" -> {
                if (dataDir != null) usage()
                dataDir = path(args[index + 1])
            }
            "--connections" -> {
                if (connectionsPath != null) usage()
                connectionsPath = path(args[index + 1])
            }
            else -> usage()
        }
        index += 2
    }
    if (url != null && (dataDir != null || connectionsPath != null)) usage()

    if (url != null) {
        val client =
            try {
                McpApiClient(url)
            } catch (_: IllegalArgumentException) {
                usage()
            }
        McpServer(McpTools(client), ltvVersion(), stdin, stdout, stderr).run()
        return EXIT_OK
    }

    val registry =
        try {
            SourceRegistry(connectionsPath)
        } catch (failure: SourceInputFailure) {
            throw CliFailure(
                EXIT_INVALID_INPUT,
                failure.code,
                jsonPointer = failure.jsonPointer,
                detail = failure.detail,
            )
        }
    val initialSources = registry.current()
    val directory = DataDirectory.open(dataDir ?: defaultDataDir(), "server")
    val store = RunBundleStore(directory)
    val service = AnalysisService(store, EngineConfig())
    val jobs =
        try {
            AnalysisJobs(1) { request, progress, cancelled ->
                analyzeWithSources(service, request, registry.current().promql, progress, cancelled, cancelled::beforePublish)
            }
        } catch (failure: Exception) {
            directory.close()
            throw failure
        }
    val advisorySetup = advisoryAiSetup()
    val (adviceService, adviceJobs) =
        try {
            val advisory = AdvisoryAiService(store, AiAdviceStore(directory, store), advisorySetup.runner)
            advisory to AdvisoryAiJobs(advisory)
        } catch (failure: Exception) {
            jobs.close()
            directory.close()
            throw failure
        }
    val server =
        try {
            startLocalServer(
                LocalApiContext(
                    store = store,
                    jobs = jobs,
                    sourceProfiles = initialSources.profiles,
                    postgresProfiles = initialSources.postgres,
                    adviceService = adviceService,
                    adviceJobs = adviceJobs,
                    sourceHttp = initialSources.http,
                    aiModels = advisorySetup.models,
                    sourceRegistry = registry,
                ),
                port = 0,
                openBrowser = false,
            )
        } catch (failure: Exception) {
            adviceJobs.close()
            jobs.close()
            directory.close()
            throw failure
        }
    try {
        McpServer(McpTools(McpApiClient(server.origin)), ltvVersion(), stdin, stdout, stderr).run()
        return EXIT_OK
    } finally {
        try {
            server.close()
        } finally {
            try {
                adviceJobs.close()
            } finally {
                try {
                    jobs.close()
                } finally {
                    directory.close()
                }
            }
        }
    }
}

package io.ltverdict.cli

import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AiAdviceStore
import io.ltverdict.ai.ModelStudioAdvisoryRunner
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.CapacityPlanValidation
import io.ltverdict.core.DiagnosticValidation
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.ResourceValidation
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.validateCapacityBinding
import io.ltverdict.core.validateCapacityPlan
import io.ltverdict.core.validateDiagnosticBinding
import io.ltverdict.core.validateDiagnosticPlan
import io.ltverdict.core.validatePolicy
import io.ltverdict.core.validateResourceSnapshot
import io.ltverdict.integrations.jenkins.JenkinsConnections
import io.ltverdict.integrations.jenkins.readJenkinsConnections
import io.ltverdict.integrations.report.renderConfluenceReport
import io.ltverdict.integrations.report.renderSavedLoadChart
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.report.renderAsciiDocReport
import io.ltverdict.report.renderHtmlReport
import io.ltverdict.sources.PostgresAnalysisInput
import io.ltverdict.sources.PromqlSource
import io.ltverdict.sources.SourceConnections
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.sources.capturePostgresPhase
import io.ltverdict.sources.readOpenSearchContexts
import io.ltverdict.sources.readPostgresAnalysisInput
import io.ltverdict.sources.readSourceConnections
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.sources.readWindowedSourceRequest
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal fun runCli(
    args: Array<String>,
    stdout: PrintStream = System.out,
    stderr: PrintStream = System.err,
): Int =
    try {
        when (args.firstOrNull()) {
            "analyze" -> analyze(args.drop(1), stdout)
            "policy" -> validatePolicyCommand(args.drop(1), stdout)
            "report" -> report(args.drop(1), stdout)
            "ui" -> ui(args.drop(1))
            "source" -> captureSource(args.drop(1), stdout)
            "opensearch" -> prepareOpenSearchCommand(args.drop(1), stdout)
            else -> usage()
        }
    } catch (failure: OpenSearchPrepareFailure) {
        stderr.println(failure.message)
        EXIT_INVALID_INPUT
    } catch (failure: CliFailure) {
        stderr.println(failure.message)
        failure.exitCode
    } catch (failure: IllegalStateException) {
        if (failure.message == "DATA_DIR_BUSY") {
            stderr.println("DATA_DIR_BUSY")
            EXIT_DATA_DIR_BUSY
        } else {
            stderr.println("INTERNAL_ERROR: ${failure.message ?: failure.javaClass.simpleName}")
            EXIT_INTERNAL
        }
    } catch (failure: Exception) {
        stderr.println("INTERNAL_ERROR: ${failure.message ?: failure.javaClass.simpleName}")
        EXIT_INTERNAL
    }

private fun analyze(
    args: List<String>,
    stdout: PrintStream,
): Int {
    if (args.isEmpty() || args.first().startsWith("--")) usage()
    val input = path(args.first())
    var policyPath: Path? = null
    var resourcesPath: Path? = null
    var diagnosticsPath: Path? = null
    var capacityPath: Path? = null
    var connectionsPath: Path? = null
    var sourcePath: Path? = null
    val sourceContextPaths = mutableListOf<Path>()
    var postgresPrePath: Path? = null
    var postgresPostPath: Path? = null
    var pgProfileHtmlPath: Path? = null
    var dataDir = defaultDataDir()
    var policySeen = false
    var dataDirSeen = false
    var index = 1
    while (index < args.size) {
        when (args[index]) {
            "--policy" -> {
                if (policySeen || index + 1 >= args.size) usage()
                policySeen = true
                policyPath = path(args[index + 1])
            }
            "--resources" -> {
                if (resourcesPath != null || index + 1 >= args.size) usage()
                resourcesPath = path(args[index + 1])
            }
            "--correlation" -> {
                if (diagnosticsPath != null || index + 1 >= args.size) usage()
                diagnosticsPath = path(args[index + 1])
            }
            "--capacity" -> {
                if (capacityPath != null || index + 1 >= args.size) usage()
                capacityPath = path(args[index + 1])
            }
            "--connections" -> {
                if (connectionsPath != null || index + 1 >= args.size) usage()
                connectionsPath = path(args[index + 1])
            }
            "--source" -> {
                if (sourcePath != null || index + 1 >= args.size) usage()
                sourcePath = path(args[index + 1])
            }
            "--source-context" -> {
                if (sourceContextPaths.size == 16 || index + 1 >= args.size) usage()
                sourceContextPaths.add(path(args[index + 1]))
            }
            "--data-dir" -> {
                if (dataDirSeen || index + 1 >= args.size) usage()
                dataDirSeen = true
                dataDir = path(args[index + 1])
            }
            "--postgres-pre" -> {
                if (postgresPrePath != null || index + 1 >= args.size) usage()
                postgresPrePath = path(args[index + 1])
            }
            "--postgres-post" -> {
                if (postgresPostPath != null || index + 1 >= args.size) usage()
                postgresPostPath = path(args[index + 1])
            }
            "--pg-profile-html" -> {
                if (pgProfileHtmlPath != null || index + 1 >= args.size) usage()
                pgProfileHtmlPath = path(args[index + 1])
            }
            else -> usage()
        }
        index += 2
    }

    requireRegularFile(input, EXIT_INVALID_INPUT, "INVALID_INPUT")
    if ((sourcePath == null) != (connectionsPath == null)) usage()
    if (sourcePath != null &&
        (resourcesPath != null || diagnosticsPath != null || capacityPath != null || sourceContextPaths.isNotEmpty())
    ) {
        throw CliFailure(EXIT_INVALID_INPUT, "SOURCE_INPUT_CONFLICT: acquire first, then replay the saved snapshot with --correlation")
    }
    val profiles = connectionsPath?.let { readSourceFile(it, ::readSourceProfiles) }.orEmpty()
    val sourceRequest = sourcePath?.let { readSourceFile(it, ::readWindowedSourceRequest) }
    if (sourceRequest != null && sourceRequest.profileIds.any { id -> profiles.none { it.id == id } }) {
        throw CliFailure(EXIT_INVALID_INPUT, "SOURCE_PROFILE_NOT_FOUND")
    }
    val source = if (sourceRequest == null) null else PromqlSource(profiles, SourceHttp(profiles))
    val policy = policyPath?.let(::readPolicy)
    val resources = resourcesPath?.let(::readResources)
    val diagnostics = diagnosticsPath?.let(::readDiagnostics)
    val capacity = capacityPath?.let(::readCapacity)
    val postgres = readPostgresFiles(postgresPrePath, postgresPostPath, pgProfileHtmlPath)
    if (diagnostics != null) {
        if (resources == null) throw CliFailure(EXIT_INVALID_INPUT, "DIAGNOSTIC_RESOURCE_REQUIRED")
        val errors = validateDiagnosticBinding(diagnostics, resources)
        if (errors.isNotEmpty()) {
            throw CliFailure(
                EXIT_INVALID_INPUT,
                errors.joinToString("\n") { "${it.code} ${it.jsonPointer}: ${it.message}" },
            )
        }
    }
    if (capacity != null && resources == null) throw CliFailure(EXIT_INVALID_INPUT, "CAPACITY_RESOURCE_REQUIRED")
    val result =
        DataDirectory.open(dataDir).use { directory ->
            val store = RunBundleStore(directory)
            val accepted =
                try {
                    Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS).use {
                        store.acceptInput(it, input.fileName.toString())
                    }
                } catch (failure: IOException) {
                    throw CliFailure(EXIT_INVALID_INPUT, "INVALID_INPUT: ${failure.message ?: "read failed"}")
                } catch (failure: IllegalArgumentException) {
                    throw CliFailure(EXIT_INVALID_INPUT, failure.message ?: "INVALID_INPUT")
                }
            try {
                capacity?.let {
                    val errors = validateCapacityBinding(it, accepted.sha256, checkNotNull(resources))
                    if (errors.isNotEmpty()) {
                        throw CliFailure(
                            EXIT_INVALID_INPUT,
                            errors.joinToString("\n") { error -> "${error.code} ${error.jsonPointer}: ${error.message}" },
                        )
                    }
                }
                val context =
                    sourceContextPaths.takeIf { it.isNotEmpty() }?.let { paths ->
                        var total = 0L
                        val bytes =
                            paths.map { file ->
                                readSourceFile(file) { stream ->
                                    stream.readNBytes(16 * 1024 * 1024 + 1).also {
                                        total += it.size
                                        require(
                                            it.size <= 16 * 1024 * 1024 && total <= 32L * 1024 * 1024,
                                        ) { "SOURCE_CONTEXT_LIMIT_EXCEEDED" }
                                    }
                                }
                            }
                        readOpenSearchContexts(bytes, accepted.sha256, resources)
                    }
                analyzeWithSources(
                    AnalysisService(store, EngineConfig()),
                    AnalysisRequest(
                        accepted,
                        policy,
                        resources = resources,
                        diagnostics = diagnostics,
                        capacity = capacity,
                        sourceRequest = sourceRequest,
                        sourceAcquisition = context,
                        postgres = postgres,
                    ),
                    source,
                ).canonicalResult
            } catch (failure: IllegalArgumentException) {
                throw CliFailure(EXIT_INVALID_INPUT, failure.message ?: "INVALID_INPUT")
            }
        }

    val json = Json.parseToJsonElement(result.decodeToString()).jsonObject
    val exitCode =
        when (json.getValue("run_validity").jsonPrimitive.content) {
            "INVALID" -> EXIT_INVALID_INPUT
            "DEGRADED" -> EXIT_NO_VERDICT
            else ->
                when (json.getValue("policy_verdict").jsonPrimitive.content) {
                    "PASS", "NO_POLICY" -> EXIT_OK
                    "FAIL" -> EXIT_FAIL
                    "NO_VERDICT" -> EXIT_NO_VERDICT
                    else -> error("UNKNOWN_POLICY_VERDICT")
                }
        }
    stdout.write(result)
    return exitCode
}

private fun captureSource(
    args: List<String>,
    stdout: PrintStream,
): Int {
    val phase = args.firstOrNull()?.takeIf { it in setOf("pre", "post") } ?: usage()
    val options = linkedMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val option = args[index]
        if (option !in setOf("--connections", "--profile", "--pre", "--pg-profile-html") ||
            index + 1 >= args.size ||
            options.put(option, args[index + 1]) != null
        ) {
            usage()
        }
        index += 2
    }
    if (phase == "pre" && "--pre" in options) usage()
    val connections = readSourceFile(path(options["--connections"] ?: usage()), ::readSourceConnections)
    val profileId = options["--profile"] ?: usage()
    val profile =
        connections.postgres.singleOrNull { it.id == profileId } ?: throw CliFailure(EXIT_INVALID_INPUT, "SOURCE_PROFILE_NOT_FOUND")
    val pre = readPostgresFiles(options["--pre"]?.let(::path), null, null)?.pre
    val output = options["--pg-profile-html"]?.let(::path)
    if (output != null && Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw CliFailure(EXIT_INVALID_INPUT, "PG_REPORT_EXISTS")
    val captured =
        try {
            capturePostgresPhase(profile, pre, post = phase == "post")
        } catch (failure: IllegalArgumentException) {
            throw CliFailure(EXIT_INVALID_INPUT, failure.message?.takeIf { Regex("PG_[A-Z_]+").matches(it) } ?: "PG_CAPTURE_FAILED")
        }
    if (output != null && captured.pgProfileHtml != null) {
        try {
            Files.write(output, captured.pgProfileHtml, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "PG_REPORT_WRITE_FAILED")
        }
    }
    stdout.write(canonicalJson(captured.phase))
    return EXIT_OK
}

private fun readPostgresFiles(
    pre: Path?,
    post: Path?,
    html: Path?,
): PostgresAnalysisInput? {
    if (pre == null && post == null && html == null) return null

    fun bytes(
        file: Path?,
        limit: Int,
    ): ByteArray? =
        file?.let {
            readSourceFile(it) { stream ->
                stream.readNBytes(limit + 1).also { value -> require(value.size <= limit) { "PG_INPUT_TOO_LARGE" } }
            }
        }
    return try {
        readPostgresAnalysisInput(
            bytes(pre, 16 * 1024 * 1024)?.inputStream(),
            bytes(post, 16 * 1024 * 1024)?.inputStream(),
            bytes(html, 4 * 1024 * 1024)?.inputStream(),
        )
    } catch (failure: IllegalArgumentException) {
        throw CliFailure(EXIT_INVALID_INPUT, failure.message?.takeIf { Regex("PG_[A-Z_]+").matches(it) } ?: "PG_INPUT_INVALID")
    }
}

private fun report(
    args: List<String>,
    stdout: PrintStream,
): Int {
    if (args.size < 4 || args[0].startsWith("--") || args[1].startsWith("--")) usage()
    val runId = args[0]
    val analysisId = args[1]
    var dataDir = defaultDataDir()
    var format: String? = null
    var dataDirSeen = false
    var index = 2
    while (index < args.size) {
        if (index + 1 >= args.size) usage()
        when (args[index]) {
            "--format" -> if (format == null) format = args[index + 1] else usage()
            "--data-dir" ->
                if (!dataDirSeen) {
                    dataDirSeen = true
                    dataDir = path(args[index + 1])
                } else {
                    usage()
                }
            else -> usage()
        }
        index += 2
    }
    if (format !in setOf("json", "html", "asciidoc", "confluence", "svg")) usage()
    val result =
        DataDirectory.open(dataDir).use { directory ->
            val analysis =
                try {
                    RunBundleStore(directory).readAnalysis(runId, analysisId)
                } catch (_: NoSuchElementException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                    ?: throw CliFailure(EXIT_INVALID_INPUT, "ANALYSIS_NOT_FOUND")
            val artifact =
                analysis.artifacts.singleOrNull { it.path == "analysis-result.json" }
                    ?: throw IllegalStateException("CORRUPT_RUN_BUNDLE: missing analysis result")
            if (format ==
                "svg"
            ) {
                renderSavedLoadChart(analysis.path.resolve("rollup-60s.ndjson"))
            } else {
                Files.readAllBytes(analysis.path.resolve(artifact.path))
            }
        }
    stdout.write(
        when (format) {
            "json" -> result
            "svg" -> result
            "html" -> renderHtmlReport(result, analysisId)
            "confluence" -> renderConfluenceReport(result, analysisId)
            else -> renderAsciiDocReport(result, analysisId)
        },
    )
    return EXIT_OK
}

private fun validatePolicyCommand(
    args: List<String>,
    stdout: PrintStream,
): Int {
    if (args.size != 2 || args[0] != "validate") usage()
    stdout.write(readPolicy(path(args[1])).canonicalBytes)
    return EXIT_OK
}

private fun ui(args: List<String>): Int {
    var dataDir = defaultDataDir()
    var parallelism = 1
    var dataDirSeen = false
    var parallelismSeen = false
    var connectionsPath: Path? = null
    var jenkinsConfigPath: Path? = null
    var index = 0
    while (index < args.size) {
        if (index + 1 >= args.size) usage()
        when (args[index]) {
            "--data-dir" -> {
                if (dataDirSeen) usage()
                dataDirSeen = true
                dataDir = path(args[index + 1])
            }
            "--analysis-parallelism" -> {
                if (parallelismSeen) usage()
                parallelismSeen = true
                parallelism = args[index + 1].toIntOrNull() ?: usage()
                if (parallelism !in 1..Runtime.getRuntime().availableProcessors()) usage()
            }
            "--connections" -> {
                if (connectionsPath != null) usage()
                connectionsPath = path(args[index + 1])
            }
            "--jenkins-config" -> {
                if (jenkinsConfigPath != null) usage()
                jenkinsConfigPath = path(args[index + 1])
            }
            else -> usage()
        }
        index += 2
    }

    val connections = connectionsPath?.let { readSourceFile(it, ::readSourceConnections) } ?: SourceConnections(emptyList(), emptyList())
    val jenkins =
        jenkinsConfigPath?.let {
            requireRegularFile(it, EXIT_INVALID_INPUT, "JENKINS_CONFIG_INVALID")
            try {
                Files.newInputStream(it, LinkOption.NOFOLLOW_LINKS).use(::readJenkinsConnections)
            } catch (
                _: Exception,
            ) {
                throw CliFailure(EXIT_INVALID_INPUT, "JENKINS_CONFIG_INVALID")
            }
        } ?: JenkinsConnections(emptyList())
    val profiles = connections.http
    val sourceHttp = if (profiles.isEmpty()) null else SourceHttp(profiles)
    val source = sourceHttp?.let { PromqlSource(profiles, it) }
    val directory = DataDirectory.open(dataDir)
    val store = RunBundleStore(directory)
    val jenkinsWorkflows =
        try {
            jenkins.profiles.associate { it.id to jenkins.workflow(it.id, directory) }
        } catch (failure: Exception) {
            directory.close()
            throw CliFailure(EXIT_INVALID_INPUT, "JENKINS_CONFIG_INVALID")
        }
    val service = AnalysisService(store, EngineConfig())
    val jobs =
        try {
            AnalysisJobs(parallelism) { request, progress, cancelled ->
                analyzeWithSources(service, request, source, progress, cancelled)
            }
        } catch (failure: Exception) {
            directory.close()
            throw failure
        }
    val (adviceService, adviceJobs) =
        try {
            val advisory = AdvisoryAiService(store, AiAdviceStore(directory, store), ModelStudioAdvisoryRunner.fromEnvironment())
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
                    store,
                    jobs,
                    profiles,
                    connections.postgres,
                    adviceService,
                    adviceJobs,
                    jenkins.summaries(),
                    jenkinsWorkflows,
                    directory.root.resolve("transport/jenkins-artifacts"),
                    sourceHttp,
                ),
            )
        } catch (failure: Exception) {
            adviceJobs.close()
            jobs.close()
            directory.close()
            throw failure
        }

    val stopped = CountDownLatch(1)
    val closed = AtomicBoolean()
    val close = {
        if (closed.compareAndSet(false, true)) {
            try {
                server.close()
            } finally {
                try {
                    adviceJobs.close()
                } finally {
                    try {
                        jobs.close()
                    } finally {
                        try {
                            directory.close()
                        } finally {
                            stopped.countDown()
                        }
                    }
                }
            }
        }
    }
    val shutdownHook = Thread(close, "lt-verdict-shutdown")
    try {
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        stopped.await()
        return EXIT_OK
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IllegalStateException("UI_INTERRUPTED")
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        close()
    }
}

private fun <T> readSourceFile(
    path: Path,
    read: (java.io.InputStream) -> T,
): T {
    requireRegularFile(path, EXIT_INVALID_INPUT, "SOURCE_FILE_INVALID")
    return try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(read)
    } catch (failure: IllegalArgumentException) {
        throw CliFailure(
            EXIT_INVALID_INPUT,
            failure.message?.takeIf { Regex("(?:SOURCE|PG)_[A-Z_]+").matches(it) } ?: "SOURCE_CONFIG_INVALID",
        )
    } catch (_: IOException) {
        throw CliFailure(EXIT_INVALID_INPUT, "SOURCE_READ_ERROR")
    }
}

private fun readPolicy(path: Path): PolicyValidation.Valid {
    requireRegularFile(path, EXIT_INVALID_POLICY, "INVALID_POLICY")
    val validation =
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::validatePolicy)
        } catch (failure: IOException) {
            throw CliFailure(EXIT_INVALID_POLICY, "INVALID_POLICY: ${failure.message ?: "read failed"}")
        }
    return when (validation) {
        is PolicyValidation.Valid -> validation
        is PolicyValidation.Invalid ->
            throw CliFailure(
                EXIT_INVALID_POLICY,
                validation.errors.joinToString(System.lineSeparator()) {
                    "${it.code} ${it.jsonPointer.ifEmpty { "/" }}: ${it.message}"
                },
            )
    }
}

private fun readResources(path: Path): ResourceValidation.Valid {
    requireRegularFile(path, EXIT_INVALID_INPUT, "INVALID_RESOURCES")
    val validation =
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::validateResourceSnapshot)
        } catch (failure: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "INVALID_RESOURCES: ${failure.message ?: "read failed"}")
        }
    return when (validation) {
        is ResourceValidation.Valid -> validation
        is ResourceValidation.Invalid ->
            throw CliFailure(
                EXIT_INVALID_INPUT,
                validation.errors.joinToString(System.lineSeparator()) {
                    "${it.code} ${it.jsonPointer.ifEmpty { "/" }}: ${it.message}"
                },
            )
    }
}

private fun readDiagnostics(path: Path): DiagnosticValidation.Valid {
    requireRegularFile(path, EXIT_INVALID_INPUT, "INVALID_DIAGNOSTICS")
    val validation =
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::validateDiagnosticPlan)
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "INVALID_DIAGNOSTICS: read failed")
        }
    return when (validation) {
        is DiagnosticValidation.Valid -> validation
        is DiagnosticValidation.Invalid -> throw CliFailure(
            EXIT_INVALID_INPUT,
            validation.errors.joinToString("\n") {
                "${it.code} ${it.jsonPointer}: ${it.message}"
            },
        )
    }
}

private fun readCapacity(path: Path): CapacityPlanValidation.Valid {
    requireRegularFile(path, EXIT_INVALID_INPUT, "INVALID_CAPACITY_PLAN")
    val validation =
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::validateCapacityPlan)
        } catch (_: IOException) {
            throw CliFailure(EXIT_INVALID_INPUT, "INVALID_CAPACITY_PLAN: read failed")
        }
    return when (validation) {
        is CapacityPlanValidation.Valid -> validation
        is CapacityPlanValidation.Invalid -> throw CliFailure(
            EXIT_INVALID_INPUT,
            validation.errors.joinToString("\n") { "${it.code} ${it.jsonPointer}: ${it.message}" },
        )
    }
}

private fun requireRegularFile(
    path: Path,
    exitCode: Int,
    code: String,
) {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw CliFailure(exitCode, code)
    }
}

private fun path(value: String): Path =
    try {
        Path.of(value)
    } catch (_: InvalidPathException) {
        usage()
    }

private fun defaultDataDir(): Path = Path.of(System.getProperty("user.home"), ".lt-verdict")

private fun usage(): Nothing =
    throw CliFailure(
        EXIT_USAGE,
        "Usage: ltv ui [--data-dir <path>] [--analysis-parallelism <n>] " +
            "[--connections <profiles.json>] [--jenkins-config <jenkins.json>] | " +
            "ltv analyze <input> [--policy <policy.json>] [--resources <snapshot.json>] [--capacity <plan.json>] " +
            "[--correlation <plan.json>] [--source-context <context.json>] " +
            "[--postgres-pre <pre.json>] [--postgres-post <post.json>] [--pg-profile-html <report.html>] " +
            "[--connections <profiles.json> --source <source.json>] [--data-dir <path>] | " +
            "ltv source pre|post --connections <profiles.json> --profile <id> [--pre <pre.json>] [--pg-profile-html <output.html>] | " +
            "ltv opensearch prepare --context <file> --templates <file> --load-sha256 <hash> --output-dir <new-dir> | " +
            "ltv policy validate <policy.json> | ltv report <run-id> <analysis-id> " +
            "--format json|html|asciidoc|confluence|svg [--data-dir <path>]" + System.lineSeparator() +
            "--source accepts source-request.v1|v2|v3; a v3 window is explicit or auto",
    )

private class CliFailure(
    val exitCode: Int,
    override val message: String,
) : RuntimeException(message)

private const val EXIT_OK = 0
private const val EXIT_FAIL = 2
private const val EXIT_NO_VERDICT = 3
private const val EXIT_INVALID_INPUT = 4
private const val EXIT_INVALID_POLICY = 5
private const val EXIT_DATA_DIR_BUSY = 6
private const val EXIT_USAGE = 64
private const val EXIT_INTERNAL = 70

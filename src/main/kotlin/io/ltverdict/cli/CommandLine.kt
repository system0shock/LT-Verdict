package io.ltverdict.cli

import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.validatePolicy
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.report.renderHtmlReport
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
            else -> usage()
        }
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
            "--data-dir" -> {
                if (dataDirSeen || index + 1 >= args.size) usage()
                dataDirSeen = true
                dataDir = path(args[index + 1])
            }
            else -> usage()
        }
        index += 2
    }

    requireRegularFile(input, EXIT_INVALID_INPUT, "INVALID_INPUT")
    val policy = policyPath?.let(::readPolicy)
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
            AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(accepted, policy)).canonicalResult
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
    if (format !in setOf("json", "html")) usage()
    val result =
        DataDirectory.open(dataDir).use { directory ->
            val analysis =
                try {
                    RunBundleStore(directory).readAnalysis(runId, analysisId)
                } catch (_: IllegalArgumentException) {
                    null
                }
                    ?: throw CliFailure(EXIT_INVALID_INPUT, "ANALYSIS_NOT_FOUND")
            val artifact =
                analysis.artifacts.singleOrNull { it.path == "analysis-result.json" }
                    ?: throw IllegalStateException("CORRUPT_RUN_BUNDLE: missing analysis result")
            Files.readAllBytes(analysis.path.resolve(artifact.path))
        }
    stdout.write(if (format == "json") result else renderHtmlReport(result, analysisId))
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
            else -> usage()
        }
        index += 2
    }

    val directory = DataDirectory.open(dataDir)
    val store = RunBundleStore(directory)
    val service = AnalysisService(store, EngineConfig())
    val jobs =
        try {
            AnalysisJobs(parallelism, service::analyze)
        } catch (failure: Exception) {
            directory.close()
            throw failure
        }
    val server =
        try {
            startLocalServer(LocalApiContext(store, jobs))
        } catch (failure: Exception) {
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
        "Usage: ltv ui [--data-dir <path>] [--analysis-parallelism <n>] | " +
            "ltv analyze <input> [--policy <policy.json>] [--data-dir <path>] | " +
            "ltv policy validate <policy.json> | ltv report <run-id> <analysis-id> --format json|html [--data-dir <path>]",
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

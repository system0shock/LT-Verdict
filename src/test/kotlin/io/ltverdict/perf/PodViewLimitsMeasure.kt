package io.ltverdict.perf

import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.PodViewValidation
import io.ltverdict.core.podViewMetadataJson
import io.ltverdict.core.podViewValuesJson
import io.ltverdict.core.sha256Hex
import io.ltverdict.core.validatePodView
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Measurement driver of platform slice P2e (ADR 0020 limits). Not a test: it is started by tools/perf/pod_view_limits_measure.ps1
 * with `java -cp <installed ltv libs>;<test classes>` so that it runs on the same JVM and the same flags as the product. Every
 * result line is `RESULT<TAB>mode<TAB>label<TAB>metric<TAB>value`.
 *
 *   stages <pod-view file> <reps>                            in-process stage times and retained heap of one file
 *   server <data dir> <load> <snapshot dir> <shape|none> <arms> [reject files...]
 *                                                             arms in a row through the real HTTP job path, then the read API
 */
fun main(args: Array<String>) {
    java.util.Locale.setDefault(java.util.Locale.ROOT)
    startSampler()
    when (args.firstOrNull()) {
        "stages" -> stages(Path.of(args[1]), args[2].toInt())
        "server" -> server(args)
        else -> error("usage: stages <file> <reps> | server <dataDir> <load> <dir> <shape|none> <arms> [reject...]")
    }
}

private val runtime = Runtime.getRuntime()
private val peakUsed = AtomicLong()

private fun usedMb(): Double = (runtime.totalMemory() - runtime.freeMemory()) / MB

private fun settledUsedMb(): Double {
    repeat(3) {
        System.gc()
        Thread.sleep(150)
    }
    return usedMb()
}

private fun startSampler() {
    Thread {
        while (true) {
            val used = runtime.totalMemory() - runtime.freeMemory()
            peakUsed.accumulateAndGet(used, ::maxOf)
            Thread.sleep(2)
        }
    }.apply {
        isDaemon = true
        start()
    }
}

private fun result(
    mode: String,
    label: String,
    metric: String,
    value: Any,
) = println("RESULT\t$mode\t$label\t$metric\t$value")

private inline fun <T> timed(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / 1_000_000.0
}

private fun stages(
    file: Path,
    reps: Int,
) {
    val label = file.fileName.toString()
    result("stages", label, "max_heap_mb", runtime.maxMemory() / MB)
    result("stages", label, "file_bytes", Files.size(file))
    val baseline = settledUsedMb()
    result("stages", label, "baseline_used_mb", "%.1f".format(baseline))
    var keep: PodViewValidation.Valid? = null
    for (rep in 1..reps) {
        keep = null
        try {
            val (bytes, readMs) = timed { Files.readAllBytes(file) }
            val (_, rawHashMs) = timed { sha256Hex(bytes) }
            peakUsed.set(0)
            val (validation, validateMs) = timed { validatePodView(ByteArrayInputStream(bytes)) }
            result("stages", label, "rep$rep.peak_sampled_used_mb", "%.0f".format(peakUsed.get() / MB))
            if (validation !is PodViewValidation.Valid) {
                val error = (validation as PodViewValidation.Invalid).errors.first()
                result("stages", label, "rep$rep.rejected", "${error.code} ${error.jsonPointer} in %.0f ms".format(validateMs))
                continue
            }
            keep = validation
            val (canonical, canonicalMs) = timed { validation.canonicalBytes() }
            val (sha, canonicalHashMs) = timed { sha256Hex(canonical) }
            val (metadata, metadataMs) = timed { podViewMetadataJson(validation.view, sha).toString() }
            val service =
                validation.view.pods
                    .first()
                    .service
            val (page, pageMs) = timed { podViewValuesJson(validation.view, sha, service, null, null, null, 256, null).toString() }
            result("stages", label, "rep$rep.read_ms", "%.1f".format(readMs))
            result("stages", label, "rep$rep.hash_raw_ms", "%.1f".format(rawHashMs))
            result("stages", label, "rep$rep.validate_ms", "%.1f".format(validateMs))
            result("stages", label, "rep$rep.canonical_bytes_ms", "%.1f".format(canonicalMs))
            result("stages", label, "rep$rep.hash_canonical_ms", "%.1f".format(canonicalHashMs))
            result("stages", label, "rep$rep.metadata_ms", "%.1f".format(metadataMs))
            result("stages", label, "rep$rep.values_first_service_page256_ms", "%.1f".format(pageMs))
            if (rep == 1) {
                result("stages", label, "canonical_bytes", canonical.size)
                result("stages", label, "metadata_bytes", metadata.length)
                result("stages", label, "values_first_service_page256_bytes", page.length)
            }
            if (rep == reps) {
                val retained = settledUsedMb() - baseline
                result("stages", label, "retained_valid_mb", "%.1f".format(retained))
                result("stages", label, "retained_per_cell_bytes", "%.0f".format(retained * MB / maxOf(1, cellCount(validation))))
            }
        } catch (oom: OutOfMemoryError) {
            keep = null
            result("stages", label, "rep$rep.rejected", "OutOfMemoryError")
        }
    }
    if (keep != null) result("stages", label, "cells", cellCount(keep))
}

private fun cellCount(valid: PodViewValidation.Valid): Long = valid.view.rows.sumOf { it.values.size.toLong() }

private fun server(args: Array<String>) {
    val dataDir = Path.of(args[1])
    val load = Path.of(args[2])
    val dir = Path.of(args[3])
    val shape = args[4]
    val arms = args[5].split(",").filter { it.isNotEmpty() }
    val rejects = args.drop(6)
    result("server", "jvm", "max_heap_mb", runtime.maxMemory() / MB)
    DataDirectory.open(dataDir).use { directory ->
        val store = RunBundleStore(directory)
        val service = AnalysisService(store, EngineConfig())
        AnalysisJobs(1) { request, progress, cancelled ->
            analyzeWithSources(service, request, null, progress, cancelled, cancelled::beforePublish)
        }.use { jobs ->
            startLocalServer(LocalApiContext(store, jobs), openBrowser = false).use { server ->
                val runId = Files.newInputStream(load).use { store.acceptInput(it, "load.jtl").runId }
                val api = Api(server.origin)
                api.bootstrap()
                result("server", "jvm", "baseline_used_mb", "%.0f".format(settledUsedMb()))
                val analyses = mutableListOf<String>()
                for (arm in arms) {
                    val parts = mutableListOf("run_id" to runId, "resource_snapshot" to "@${dir.resolve("snapshot-$arm.json")}")
                    if (shape != "none") parts += "pod_view" to "@${dir.resolve("pod-view-$shape-$arm.json")}"
                    peakUsed.set(0)
                    val (submit, postMs) = timed { api.job(parts) }
                    check(submit.statusCode() == 202) { "job rejected: ${submit.statusCode()} ${submit.body().take(300)}" }
                    val status = Json.parseToJsonElement(submit.body()).jsonObject
                    val jobId = status.getValue("job_id").jsonPrimitive.content
                    val (final, totalMs) = timed { api.await(jobId) }
                    val label = "$shape-$arm"
                    result("server", label, "submit_response_ms", "%.0f".format(postMs))
                    result("server", label, "job_total_ms", "%.0f".format(postMs + totalMs))
                    result("server", label, "job_state", final.getValue("state").jsonPrimitive.content)
                    check(final.getValue("state").jsonPrimitive.content == "COMPLETE") { "job did not complete: $final" }
                    result("server", label, "peak_sampled_used_mb", "%.0f".format(peakUsed.get() / MB))
                    result("server", label, "used_after_gc_mb", "%.0f".format(settledUsedMb()))
                    (final["analysis_id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()?.let { analyses += it }
                }
                if (shape != "none" && analyses.isNotEmpty()) readApi(api, runId, analyses.first(), shape)
                rejects.forEach { reject(api, Path.of(it), dir, runId, arms.firstOrNull() ?: "A") }
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = if (isString) content else null

private fun readApi(
    api: Api,
    runId: String,
    analysisId: String,
    shape: String,
) {
    val base = "/api/runs/$runId/analyses/$analysisId/pod-view"
    val label = "api-$shape"
    val (cold, coldMs) = timed { api.get(base) }
    result("server", label, "metadata_cold_ms", "%.1f".format(coldMs))
    result("server", label, "metadata_bytes", cold.body().length)
    val warm = List(15) { timed { api.get(base) }.second }
    result("server", label, "metadata_warm_median_ms", "%.1f".format(warm.sorted()[warm.size / 2]))
    result("server", label, "metadata_warm_max_ms", "%.1f".format(warm.max()))
    val services =
        Json
            .parseToJsonElement(cold.body())
            .jsonObject
            .getValue("services")
            .jsonArray
    val service =
        services
            .first()
            .jsonObject
            .getValue("service")
            .jsonPrimitive.content
    val encoded = URLEncoder.encode(service, UTF_8)
    for (limit in listOf(64, 256)) {
        val path = "$base/values?service=$encoded&limit=$limit"
        val samples = List(16) { timed { api.get(path) } }
        val body = samples.first().first.body()
        result("server", label, "values_limit${limit}_status", samples.first().first.statusCode())
        result("server", label, "values_limit${limit}_bytes", body.length)
        result("server", label, "values_limit${limit}_cold_ms", "%.1f".format(samples.first().second))
        val warmValues = samples.drop(1).map { it.second }.sorted()
        result("server", label, "values_limit${limit}_warm_median_ms", "%.1f".format(warmValues[warmValues.size / 2]))
        result("server", label, "values_limit${limit}_warm_max_ms", "%.1f".format(warmValues.last()))
    }
    var after: String? = null
    var pages = 0
    val (_, walkMs) =
        timed {
            do {
                val page =
                    Json
                        .parseToJsonElement(
                            api
                                .get(
                                    "$base/values?service=$encoded&limit=64" +
                                        (after?.let { "&after=" + URLEncoder.encode(it, UTF_8) } ?: ""),
                                ).body(),
                        ).jsonObject
                after = (page.getValue("next_after") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull()
                pages++
            } while (after != null)
        }
    result("server", label, "walk_service_pages", pages)
    result("server", label, "walk_service_ms", "%.0f".format(walkMs))
    result("server", label, "used_after_gc_after_reads_mb", "%.0f".format(settledUsedMb()))
}

private fun reject(
    api: Api,
    file: Path,
    dir: Path,
    runId: String,
    arm: String,
) {
    val label = file.fileName.toString()
    val bytes = Files.readAllBytes(file)
    try {
        peakUsed.set(0)
        val (validation, ms) = timed { validatePodView(ByteArrayInputStream(bytes)) }
        val code = (validation as? PodViewValidation.Invalid)?.errors?.first()?.let { "${it.code} ${it.jsonPointer}" } ?: "VALID"
        result("reject", label, "in_process", "$code in %.0f ms (peak sampled %.0f MB)".format(ms, peakUsed.get() / MB))
    } catch (oom: OutOfMemoryError) {
        result("reject", label, "in_process", "OutOfMemoryError")
    }
    try {
        peakUsed.set(0)
        val (response, ms) =
            timed {
                api.job(
                    listOf(
                        "run_id" to runId,
                        "resource_snapshot" to "@${dir.resolve("snapshot-$arm.json")}",
                        "pod_view" to "@$file",
                    ),
                )
            }
        val code =
            runCatching {
                Json
                    .parseToJsonElement(response.body())
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
        result(
            "reject",
            label,
            "http_job",
            "${response.statusCode()} $code in %.0f ms (peak sampled %.0f MB)".format(
                ms,
                peakUsed.get() / MB,
            ),
        )
    } catch (failure: Exception) {
        result("reject", label, "http_job", "client error ${failure.javaClass.simpleName}: ${failure.message}")
    }
}

private class Api(
    private val origin: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private lateinit var cookie: String
    private lateinit var csrf: String

    fun bootstrap() {
        val response = send(request("/api/bootstrap").GET())
        cookie =
            response
                .headers()
                .firstValue("set-cookie")
                .orElseThrow()
                .substringBefore(';')
        csrf =
            Json
                .parseToJsonElement(response.body())
                .jsonObject
                .getValue("csrf_token")
                .jsonPrimitive.content
    }

    fun get(path: String): HttpResponse<String> = send(request(path).GET())

    fun await(jobId: String): kotlinx.serialization.json.JsonObject {
        while (true) {
            val status = Json.parseToJsonElement(get("/api/jobs/$jobId").body()).jsonObject
            if (status.getValue("state").jsonPrimitive.content !in setOf("QUEUED", "PROCESSING")) return status
            Thread.sleep(50)
        }
    }

    /** A multipart job whose parts are plain values or `@path` files; the body is staged on disk so that the client holds no file bytes. */
    fun job(parts: List<Pair<String, String>>): HttpResponse<String> {
        val boundary = "ltv-measure-boundary"
        val body = Files.createTempFile("ltv-measure", ".multipart")
        try {
            Files.newOutputStream(body).use { out ->
                parts.forEach { (name, value) ->
                    out.write("--$boundary\r\n".toByteArray())
                    if (value.startsWith("@")) {
                        val file = Path.of(value.substring(1))
                        out.write(
                            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.fileName}\"\r\nContent-Type: application/json\r\n\r\n"
                                .toByteArray(),
                        )
                        Files.copy(file, out)
                    } else {
                        out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value".toByteArray())
                    }
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
            return send(
                request("/api/jobs")
                    .header("Origin", origin)
                    .header("X-LTV-CSRF", csrf)
                    .header("Content-Type", "multipart/form-data; boundary=$boundary")
                    .POST(HttpRequest.BodyPublishers.ofFile(body)),
            )
        } finally {
            Files.deleteIfExists(body)
        }
    }

    private fun request(path: String): HttpRequest.Builder =
        HttpRequest
            .newBuilder(URI.create("$origin$path"))
            .timeout(Duration.ofMinutes(10))
            .apply { if (::cookie.isInitialized) header("Cookie", cookie) }

    private fun send(request: HttpRequest.Builder): HttpResponse<String> =
        client.send(request.build(), HttpResponse.BodyHandlers.ofString(UTF_8))
}

private const val MB = 1024.0 * 1024.0

package io.ltverdict.storage

import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.WindowComparisonRequest
import io.ltverdict.core.baselineConditionRecord
import io.ltverdict.core.canonicalJson
import io.ltverdict.core.manualBaselineSelection
import io.ltverdict.core.recognizeRunPeriod
import io.ltverdict.core.runPeriodJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * W2.2 PR 3 characterization of the data directory: after each step of a fixed scenario through the store API, the files and
 * directories that appeared, changed or disappeared (path, size, SHA-256), the documents the reads return, and the exceptions
 * of a battery of refused calls (class and message). Captured from `origin/main` BEFORE `RunBundleStore` was split. Regenerate
 * only on purpose with `LTV_UPDATE_STORE_SNAPSHOT=1` (a change here is a change of the on-disk format or of the store contract).
 */
class StoreTreeSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val snapshotFile = Path.of("fixtures/storage-layout/store-tree.txt")
    private val lines = mutableListOf<String>()
    private var previous = emptyMap<String, String>()

    @Test
    fun `the data directory and the store answers equal the snapshot taken before the split`() {
        val root = tempDir.resolve("data")
        DataDirectory.open(root).use { directory ->
            scenario(root, RunBundleStore(directory, SteppingClock(Instant.parse("2026-02-03T04:05:06Z"))))
        }
        // a reopened directory reads what the first one wrote
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            step(root, "reopen")
            lines += "reopen readBaseline | ${store.readBaseline()}"
            lines += "reopen listBaselineSlots | ${store.listBaselineSlots().map { "${it.series}/${it.arm}/${it.legacy}" }}"
            lines += "reopen listReleases | ${store.listReleases(null, null, 10).releases.map { it["release_id"] }}"
        }
        val text = lines.joinToString("\n", postfix = "\n")
        if (System.getenv("LTV_UPDATE_STORE_SNAPSHOT") == "1") {
            Files.createDirectories(snapshotFile.parent)
            Files.writeString(snapshotFile, text)
        }
        val expected = Files.readString(snapshotFile).replace("\r\n", "\n").lines()
        val actual = text.lines()
        assertEquals(
            emptyList<Pair<String, String>>(),
            expected.zip(actual).filter { (a, b) ->
                a != b
            },
            "store answers differ from the snapshot",
        )
        assertEquals(expected.size, actual.size)
    }

    private fun scenario(
        root: Path,
        store: RunBundleStore,
    ) {
        step(root, "open")
        val a = store.acceptInput(Files.newInputStream(Path.of(CSV)), "a.jtl")
        lines += "accept A | ${a.runId} ${a.sourceType} ${a.sizeBytes} ${a.originalFilename} ${a.acceptedAt}"
        step(root, "accept A")
        val again = store.acceptInput(Files.newInputStream(Path.of(CSV)), "other-name.jtl")
        lines += "accept A again | ${again.runId} ${again.originalFilename} ${again.acceptedAt}"
        step(root, "accept A again")
        val b = store.acceptInput(Files.newInputStream(Path.of(XML)), "b.xml")
        lines += "accept B | ${b.runId} ${b.sourceType} ${b.acceptedAt}"
        step(root, "accept B")
        lines += "listRuns | ${store.listRuns(null, 10)}"
        lines += "listRuns page 1 | ${store.listRuns(null, 1)}"
        lines += "listRuns page 2 | ${store.listRuns(store.listRuns(null, 1).nextAfter, 1)}"
        lines += "requireInput | ${store.requireInput(a.runId)}".replace(root.toString(), "<root>")

        val a1 = save(store, a, "a1", policy = true)
        val a2 = save(store, a, "a2", arm = "blue", podView = true)
        val b1 = save(store, b, "b1", verdict = "FAIL", startedAt = null)
        step(root, "three analyses")
        lines += "re-save a1 | ${save(store, a, "a1", policy = true) == a1}"
        step(root, "re-save a1")

        lines += "listAnalyses A | ${store.listAnalyses(a.runId, null, 10)}"
        lines += "listAnalyses A paged | ${store.listAnalyses(a.runId, null, 1)}"
        lines += "listAnalyses B | ${store.listAnalyses(b.runId, null, 10)}"
        lines += "readAnalysis | ${store.readAnalysis(a.runId, a2)?.artifacts}"
        lines += "readAnalysisDocuments | ${store.readAnalysisDocuments(a.runId, a1)}"
        lines += "readVerifiedAnalysis | ${store.readVerifiedAnalysis(a.runId, a1)}"
        lines += "readVerifiedAnalysis no run | ${store.readVerifiedAnalysis(b.runId, b1)?.run}"
        lines += "readPodViewBytes | ${store.readPodViewBytes(a.runId, a2)?.let { sha256Hex(it) }} ${store.readPodViewBytes(a.runId, a1)}"
        lines += "readComparisonDocuments | ${store.readComparisonDocuments(a.runId, a2)}"
        lines +=
            "readComparisonHistory | ${store.readComparisonHistory().entries.map {
                it.analysisId
            }} ${store.readComparisonHistory().truncated}"
        lines += "analysisExists | ${store.analysisExists(a.runId, a1)} ${store.analysisExists(a.runId, "f".repeat(64))}"
        lines += "analysisState | ${store.analysisState(a.runId, a1)} ${store.analysisState(a.runId, "f".repeat(64))}"
        lines += "readAnalysisIdentity | ${store.readAnalysisIdentity(a.runId, a2)}"

        val outcome = AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(a, null))
        lines += "analyze | ${outcome.analysisId} ${outcome.canonicalResult.size}"
        step(root, "real analysis")

        lines += "readRunPeriod before | ${store.readRunPeriod(a.runId)}"
        val period = runPeriodJson(recognizeRunPeriod(a.sourceType, a.path, a.sha256, 60_000))
        store.replaceRunPeriod(a.runId, period)
        step(root, "run period")
        lines += "readRunPeriod | ${store.readRunPeriod(a.runId) == period}"

        val legacyReference = reference(a, a1)
        store.replaceBaseline(manualBaselineSelection("legacy", legacyReference))
        step(root, "legacy baseline")
        val condition = baselineConditionRecord(legacyReference, reference(a, a2), null, "CONFIRMED", Instant.parse("2026-09-06T10:00:00Z"))
        store.replaceBaselineCondition(condition)
        val windows = WindowComparisonRequest("before", "after")
        store.replaceBaselineCondition(
            baselineConditionRecord(legacyReference, reference(a, a2), windows, "NOT_CONFIRMED", Instant.parse("2026-09-06T11:00:00Z")),
        )
        step(root, "conditions")
        lines += "readBaseline | ${store.readBaseline()}"
        lines += "readBaselineCondition | ${store.readBaselineCondition(legacyReference, reference(a, a2), null)}"
        store.replaceBaselineSlot(manualBaselineSelection("blue-series", reference(a, a2)), "blue")
        store.replaceBaselineSlot(manualBaselineSelection("plain", reference(a, a1)), null)
        store.replaceBaselineSlot(manualBaselineSelection("legacy", reference(a, a1)), null)
        step(root, "slots (the last one shadows the legacy file)")
        lines += "listBaselineSlots | ${store.listBaselineSlots().map { "${it.series}/${it.arm}/${it.legacy}/${it.key()}" }}"
        lines += "readBaselineSlotWithCondition legacy | ${store.readBaselineSlotWithCondition(null, null, reference(a, a2), null)}"
        lines +=
            "readBaselineSlotWithCondition slot | ${store.readBaselineSlotWithCondition("blue-series", "blue", reference(a, a1), null)}"
        lines += "clearBaselineSlot | ${store.clearBaselineSlot("blue-series", "blue")} ${store.clearBaselineSlot("blue-series", "blue")}"
        step(root, "slot cleared")
        lines += "clearBaseline | ${store.clearBaseline()}"
        step(root, "legacy cleared")

        val first =
            store.createRelease(
                draft(a, listOf(a1), "checkout", "1.0", "2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T03:04:05.006789Z"),
            ) {
                "0123abcd"
            }
        store.createRelease(
            draft(a, listOf(a2), "checkout", "2.0", "2026-01-03T00:00:00Z", arm = "blue"),
            Instant.parse("2026-01-04T03:04:05Z"),
        ) { "89abcdef" }
        step(root, "two releases")
        lines += "createRelease | ${first["release_id"]}"
        lines += "listReleases | ${store.listReleases(null, null, 10)}"
        lines += "listReleases series | ${store.listReleases("checkout", "999999999999999-00000000", 1)}"
        lines += "findReleasesByAnalysis | ${store.findReleasesByAnalysis(setOf(a1, a2, "e".repeat(64)))}"
        val replaced =
            store.replaceRelease((first["release_id"] as JsonPrimitive).content) {
                JsonObject(
                    it + ("label" to JsonPrimitive("1.0.1")) + ("updated_at" to JsonPrimitive("2026-01-05T00:00:00Z")),
                )
            }
        lines += "replaceRelease | $replaced"
        step(root, "release replaced")
        lines +=
            "deleteRelease | ${store.deleteRelease(
                (first["release_id"] as JsonPrimitive).content,
            )} ${store.deleteRelease((first["release_id"] as JsonPrimitive).content)}"
        step(root, "release deleted")

        refused(store, root, a, b, a1, a2, b1, first)
    }

    private fun refused(
        store: RunBundleStore,
        root: Path,
        a: AcceptedInput,
        b: AcceptedInput,
        a1: String,
        a2: String,
        b1: String,
        first: JsonObject,
    ) {
        val releaseId = (first["release_id"] as JsonPrimitive).content
        val secondId = "001767398400000-89abcdef"
        val bad = "0".repeat(64)
        val calls =
            linkedMapOf<String, () -> Any?>(
                "accept unsafe filename" to { store.acceptInput(ByteArrayInputStream(byteArrayOf(1)), "a/b") },
                "accept empty" to { store.acceptInput(ByteArrayInputStream(ByteArray(0)), "e.jtl") },
                "accept unsupported" to { store.acceptInput(ByteArrayInputStream("not a load file".toByteArray()), "x.txt") },
                "accept over the limit" to { store.acceptInput(Files.newInputStream(Path.of(CSV)), "big.jtl", 10) },
                "accept negative limit" to { store.acceptInput(Files.newInputStream(Path.of(CSV)), "n.jtl", -1) },
                "listRuns limit 0" to { store.listRuns(null, 0) },
                "listRuns unknown cursor" to { store.listRuns("jmeter_jtl_csv-${"9".repeat(64)}", 10) },
                "listRuns bad cursor" to { store.listRuns("x", 10) },
                "requireInput unknown" to { store.requireInput("jmeter_jtl_csv-${"9".repeat(64)}") },
                "requireInput bad id" to { store.requireInput("x") },
                "write analysis unknown run" to { store.writeAnalysisAtomically("jmeter_jtl_csv-${"9".repeat(64)}", bad) { } },
                "write analysis bad id" to { store.writeAnalysisAtomically(a.runId, "x") { } },
                "write analysis with a manifest" to
                    { store.writeAnalysisAtomically(a.runId, "1".repeat(64)) { Files.writeString(it.resolve("manifest.json"), "{}") } },
                "write analysis failing writer" to { store.writeAnalysisAtomically(a.runId, "2".repeat(64)) { error("writer failed") } },
                "listAnalyses limit 101" to { store.listAnalyses(a.runId, null, 101) },
                "listAnalyses bad cursor" to { store.listAnalyses(a.runId, "x", 10) },
                "listAnalyses unknown run" to { store.listAnalyses("jmeter_jtl_csv-${"9".repeat(64)}", null, 10) },
                "readAnalysis unknown" to { store.readAnalysis(a.runId, bad) },
                "readVerifiedAnalysis too small a limit" to { store.readVerifiedAnalysis(a.runId, a1, 1) },
                "readVerifiedAnalysis limit 0" to { store.readVerifiedAnalysis(a.runId, a1, 0) },
                "readAnalysisIdentity unknown" to { store.readAnalysisIdentity(a.runId, bad) },
                "readRunPeriod unknown run" to { store.readRunPeriod("jmeter_jtl_csv-${"9".repeat(64)}") },
                "replaceRunPeriod other input" to
                    { store.replaceRunPeriod(b.runId, runPeriodJson(recognizeRunPeriod(a.sourceType, a.path, a.sha256, 60_000))) },
                "replaceBaseline invalid" to { store.replaceBaseline(JsonObject(emptyMap())) },
                "replaceBaselineSlot unknown analysis" to
                    { store.replaceBaselineSlot(manualBaselineSelection("s", reference(a, bad)), null) },
                "replaceBaselineSlot wrong arm" to { store.replaceBaselineSlot(manualBaselineSelection("s", reference(a, a2)), "green") },
                "replaceBaselineCondition invalid" to { store.replaceBaselineCondition(JsonObject(emptyMap())) },
                "createRelease wrong keys" to { store.createRelease(JsonObject(emptyMap()), Instant.EPOCH) },
                "createRelease bad start" to {
                    store.createRelease(
                        draft(a, listOf("c".repeat(64)), "s", "l", "yesterday"),
                        Instant.EPOCH,
                    )
                },
                "createRelease already registered" to
                    {
                        store.createRelease(
                            draft(a, listOf(a2), "s", "dup", "2026-01-03T00:00:00Z", arm = "blue"),
                            Instant.EPOCH,
                        ) { "00000000" }
                    },
                "createRelease too large" to
                    {
                        store.createRelease(
                            draft(a, listOf("c".repeat(64)), "s", "l", "2026-01-01T00:00:00Z", notes = "n".repeat(20_000)),
                            Instant.EPOCH,
                        ) {
                            "00000001"
                        }
                    },
                "readRelease bad id" to { store.readRelease("zz") },
                "readRelease deleted" to { store.readRelease(releaseId) },
                "replaceRelease unknown" to { store.replaceRelease(releaseId) { it } },
                "replaceRelease immutable" to { store.replaceRelease(secondId) { JsonObject(it + ("series" to JsonPrimitive("other"))) } },
                "replaceRelease bad id" to { store.replaceRelease("zz") { it } },
                "deleteRelease bad id" to { store.deleteRelease("zz") },
                "listReleases limit 0" to { store.listReleases(null, null, 0) },
                "listReleases bad cursor" to { store.listReleases(null, "zz", 10) },
            )
        for ((name, call) in calls) {
            val outcome =
                try {
                    "ok ${call()}".replace(root.toString(), "<root>").take(160)
                } catch (failure: Throwable) {
                    "${failure::class.simpleName}: ${failure.message}".replace(root.toString(), "<root>")
                }
            lines += "refused | $name | $outcome"
        }
        step(root, "refused calls leave no residue")

        // damaged private state is reported, not repaired
        Files.writeString(root.resolve("baseline.json"), "{}")
        lines +=
            "corrupt baseline | ${runCatching { store.readBaseline() }.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
        Files.delete(root.resolve("baseline.json"))
        Files.writeString(root.resolve("runs").resolve(a.runId).resolve("run-period.json"), "{}")
        lines +=
            "corrupt run period | ${runCatching {
                store.readRunPeriod(
                    a.runId,
                )
            }.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
        Files.delete(root.resolve("runs").resolve(a.runId).resolve("run-period.json"))
        val result =
            root
                .resolve("runs")
                .resolve(a.runId)
                .resolve("analyses")
                .resolve(a1)
                .resolve("analysis-result.json")
        val original = Files.readAllBytes(result)
        Files.write(result, original + byteArrayOf(32))
        lines +=
            "corrupt analysis | ${runCatching {
                store.readAnalysis(
                    a.runId,
                    a1,
                )
            }.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
        lines += "corrupt analysis state | ${store.analysisState(a.runId, a1)}"
        Files.write(result, original)
        val release = root.resolve("releases").resolve("$secondId.json")
        Files.write(release, Files.readAllBytes(release) + byteArrayOf(32))
        lines +=
            "corrupt release | ${runCatching {
                store.readRelease(
                    secondId,
                )
            }.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }}"
        lines +=
            "corrupt release list | ${store.listReleases(
                null,
                null,
                10,
            ).let { "${it.releases.size} ${it.corruptCount} ${it.corruptNames}" }}"
        Files.write(release, Files.readAllBytes(release).copyOf(Files.size(release).toInt() - 1))
    }

    private fun save(
        store: RunBundleStore,
        input: AcceptedInput,
        tag: String,
        verdict: String = "PASS",
        startedAt: String? = "2026-01-01T00:00:00Z",
        arm: String? = null,
        policy: Boolean = false,
        podView: Boolean = false,
    ): String {
        val podViewBytes = "{\"pod\":\"view\"}".toByteArray()
        val identity =
            canonicalJson(
                buildJsonObject {
                    put("run_id", input.runId)
                    put("policy_sha256", "a".repeat(64))
                    put("tag", tag)
                    arm?.let { put("resource_arm", it) }
                    if (podView) put("pod_view_sha256", sha256Hex(podViewBytes))
                },
            )
        val analysisId = sha256Hex(identity)
        store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            Files.write(
                staging.resolve("analysis-result.json"),
                canonicalJson(
                    buildJsonObject {
                        put("run_id", input.runId)
                        put("run_validity", "VALID")
                        put("policy_verdict", verdict)
                    },
                ),
            )
            if (startedAt != null) {
                Files.write(
                    staging.resolve("run.json"),
                    canonicalJson(
                        buildJsonObject {
                            put("run_id", input.runId)
                            put("started_at", startedAt)
                        },
                    ),
                )
            }
            if (policy) {
                Files.write(
                    staging.resolve("policy.json"),
                    """{"policy_id":"fixture","schema_version":"policy.v1"}""".toByteArray(),
                )
            }
            if (podView) Files.write(staging.resolve("pod-view.json"), podViewBytes)
            Files.createDirectories(staging.resolve("nested"))
            Files.write(staging.resolve("nested").resolve("note.txt"), "note $tag".toByteArray())
        }
        return analysisId
    }

    private fun reference(
        input: AcceptedInput,
        analysisId: String,
    ) = buildJsonObject {
        put("run_id", input.runId)
        put("analysis_id", analysisId)
    }

    private fun draft(
        input: AcceptedInput,
        analysisIds: List<String>,
        series: String,
        label: String,
        startedAt: String,
        arm: String? = null,
        notes: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("schema_version", "local-release.v1")
            put("series", series)
            put("label", label)
            put("run_id", input.runId)
            put("started_at", startedAt)
            put(
                "analyses",
                JsonArray(
                    analysisIds.map { id ->
                        buildJsonObject {
                            put("analysis_id", id)
                            put("arm", arm?.let(::JsonPrimitive) ?: JsonNull)
                            put("coverage_reasons", JsonArray(emptyList()))
                            put("coverage_status", "COMPLETE")
                            put("policy_sha256", "a".repeat(64))
                            put("policy_verdict", "PASS")
                            put("run_validity", "VALID")
                        }
                    },
                ),
            )
            put("profile", JsonNull)
            put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
        }

    /** Prints what appeared, changed or disappeared in the data directory since the previous step. */
    private fun step(
        root: Path,
        name: String,
    ) {
        val now = tree(root)
        lines += "== $name"
        (previous.keys + now.keys).sorted().forEach { path ->
            val before = previous[path]
            val after = now[path]
            when {
                before == null -> lines += "+ $path $after"
                after == null -> lines += "- $path"
                before != after -> lines += "~ $path $after"
            }
        }
        previous = now
    }

    private fun tree(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            paths
                .filter { it != root }
                .map { path ->
                    val relative = root.relativize(path).joinToString("/")
                    // The lock file is locked by the open data directory (Windows refuses to read it); its size is all it has.
                    relative to
                        when {
                            Files.isDirectory(path) -> "dir"
                            relative == ".ltv.lock" -> "lock ${Files.size(path)}"
                            else -> "${Files.size(path)} ${sha256Hex(Files.readAllBytes(path))}"
                        }
                }.toList()
                .toMap()
        }

    /** Every reading of the clock is one second later than the previous one, so each accepted input gets its own instant. */
    private class SteppingClock(
        private var next: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        @Synchronized
        override fun instant(): Instant = next.also { next = it.plusSeconds(1) }
    }

    private companion object {
        const val CSV = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
        const val XML = "fixtures/slice1/jmeter/xml-5.6.3/input.xml"
    }
}

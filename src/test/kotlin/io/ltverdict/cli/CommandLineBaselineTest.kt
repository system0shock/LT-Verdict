package io.ltverdict.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** W2.3: `--baseline <analysis-result.json>` on fixtures/stages/ramp-steady-rampdown.jtl (plateau p95 194 ms, whole run 821 ms). */
class CommandLineBaselineTest {
    @TempDir
    lateinit var tempDir: Path

    private val load = Path.of("fixtures/stages/ramp-steady-rampdown.jtl").toString()
    private val stages = Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json").toString()

    // The same load with every response twice as slow: another run, comparable with the first one.
    private val slow: String by lazy {
        val lines = Files.readAllLines(Path.of(load))
        val body =
            lines.drop(1).map { line ->
                val cells = line.split(",").toMutableList()
                cells[1] = (cells[1].toInt() * 2).toString()
                cells.joinToString(",")
            }
        tempDir.resolve("slow.jtl").also { Files.write(it, listOf(lines.first()) + body) }.toString()
    }

    private class Saved(
        val dataDir: Path,
        val runId: String,
        val analysisId: String,
    ) {
        val result: Path get() =
            dataDir
                .resolve(
                    "runs",
                ).resolve(runId)
                .resolve("analyses")
                .resolve(analysisId)
                .resolve("analysis-result.json")
    }

    private fun save(
        name: String,
        input: String,
        vararg extra: String,
        dataDir: Path = tempDir.resolve("data-$name"),
    ): Saved {
        val done = run("analyze", input, "--data-dir", dataDir.toString(), *extra)
        val ids = Regex("analysis_id=([0-9a-f]{64}) run_id=(\\S+)").find(done.stderr) ?: error(done.stderr)
        return Saved(dataDir, ids.groupValues[2], ids.groupValues[1])
    }

    private fun summary(
        saved: Saved,
        baseline: Path,
    ): JsonObject {
        val done = run("summary", saved.runId, saved.analysisId, "--baseline", baseline.toString(), "--data-dir", saved.dataDir.toString())
        assertEquals(0, done.exitCode, done.stderr)
        return Json.parseToJsonElement(done.stdout).jsonObject
    }

    @Test
    fun `a baseline adds deltas to the report and the summary and leaves the verdict, exit code and bytes alone`() {
        val baseline = save("base", load)
        val withBaseline = tempDir.resolve("out-with")
        val without = tempDir.resolve("out-without")

        val compared =
            run(
                "analyze",
                slow,
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                withBaseline.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        val control = run("analyze", slow, "--out-dir", without.toString(), "--data-dir", tempDir.resolve("data-control").toString())

        assertEquals(control.exitCode, compared.exitCode, compared.stderr)
        assertEquals(control.stdout, compared.stdout)
        listOf("result.json", "junit.xml", "chart.svg").forEach {
            assertEquals(Files.readString(without.resolve(it)), Files.readString(withBaseline.resolve(it)), it)
        }
        val html = Files.readString(withBaseline.resolve("report.html"))
        val text = Files.readString(withBaseline.resolve("summary.txt"))
        assertTrue(html.contains("<h2>Изменения относительно baseline</h2>"), html)
        assertTrue(html.contains("response_time_p95_ms (ms)"), html)
        assertTrue(
            text.contains(
                "baseline: run_id=${baseline.runId} analysis_id=${baseline.analysisId} comparability=UNCONFIRMED scope=whole_run",
            ),
            text,
        )
        assertTrue(text.contains("response_time_p95_ms: 821 -> "), text)
        assertFalse(Files.readString(without.resolve("report.html")).contains("относительно baseline"))
        assertFalse(Files.readString(without.resolve("summary.txt")).contains("baseline"))
    }

    @Test
    fun `with stages the deltas are those of the steady window, not of the whole run`() {
        val baseline = save("base", load, "--stages", stages)
        val current = save("cur", slow, "--stages", stages, dataDir = baseline.dataDir)

        val json = summary(current, baseline.result)
        val comparison = json.getValue("baseline_comparison").jsonObject

        assertEquals("steady_window", comparison.getValue("scope").jsonPrimitive.content)
        assertEquals("UNCONFIRMED", comparison.getValue("comparability").jsonPrimitive.content)
        assertNull(comparison["metrics"], "no whole-run table")
        assertFalse(comparison.getValue("warnings").jsonArray.any { it.jsonPrimitive.content == "WHOLE_RUN_METRICS_WITH_STAGES" })
        val window =
            comparison
                .getValue("windows")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("steady", window.getValue("window_id").jsonPrimitive.content)
        assertTrue(window.getValue("status").jsonPrimitive.content in setOf("DESCRIPTIVE", "NO_MATERIAL_CHANGE"), window.toString())
        val p95 =
            window.getValue("metrics").jsonArray.map { it.jsonObject }.single {
                it.getValue("metric").jsonPrimitive.content ==
                    "response_time_p95_ms"
            }
        assertEquals("194", p95.getValue("baseline").jsonPrimitive.content)
        assertTrue(
            p95
                .getValue("current")
                .jsonPrimitive.content
                .toInt() > 194,
            p95.toString(),
        )
        assertEquals("DESCRIPTIVE", p95.getValue("status").jsonPrimitive.content)
        assertEquals("CONDITIONS_UNCONFIRMED", p95.getValue("reason").jsonPrimitive.content)
        assertFalse(json.toString().contains("CANDIDATE"), "an unconfirmed comparison never reaches CANDIDATE")
        val html =
            run(
                "report",
                current.runId,
                current.analysisId,
                "--format",
                "html",
                "--baseline",
                baseline.result.toString(),
                "--data-dir",
                current.dataDir.toString(),
            )
        assertTrue(html.stdout.contains("Окно steady"), html.stdout)
        assertTrue(html.stdout.contains("window_metric_summary"), html.stdout)
        listOf("asciidoc", "confluence").forEach {
            val other =
                run(
                    "report",
                    current.runId,
                    current.analysisId,
                    "--format",
                    it,
                    "--baseline",
                    baseline.result.toString(),
                    "--data-dir",
                    current.dataDir.toString(),
                )
            assertEquals(0, other.exitCode, other.stderr)
            assertTrue(other.stdout.contains("Изменения относительно baseline"), other.stdout)
        }
    }

    @Test
    fun `a staged run against a baseline without stages and the reverse are not comparable, with a reason`() {
        val whole = save("whole", load)
        val staged = save("staged", slow, "--stages", stages, dataDir = whole.dataDir)
        val stagedBase = save("staged-base", load, "--stages", stages, dataDir = whole.dataDir)
        val wholeCurrent = save("whole-current", slow, dataDir = whole.dataDir)

        listOf(summary(staged, whole.result), summary(wholeCurrent, stagedBase.result)).forEach { json ->
            val comparison = json.getValue("baseline_comparison").jsonObject
            val window =
                comparison
                    .getValue("windows")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("NOT_EVALUATED", window.getValue("status").jsonPrimitive.content)
            assertTrue(
                window.getValue("reasons").jsonArray.any { it.jsonPrimitive.content == "INCOMPATIBLE_METRIC_DEFINITION" },
                window.toString(),
            )
            assertTrue(window.getValue("metrics").jsonArray.isEmpty())
            assertNull(comparison["metrics"], "no whole-run fallback")
        }
        val html =
            run(
                "report",
                staged.runId,
                staged.analysisId,
                "--format",
                "html",
                "--baseline",
                whole.result.toString(),
                "--data-dir",
                whole.dataDir.toString(),
            )
        assertTrue(html.stdout.contains("разное объявление стадий"), html.stdout)
    }

    @Test
    fun `a baseline without a policy is compared and the exit code follows the verdict only`() {
        val baseline = save("base", load, "--stages", stages)
        val failing =
            policy(
                "failing",
                """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":190,"scope":{"kind":"overall"}}""",
            )
        val bound =
            policy(
                "bound",
                """{"id":"ramp","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"window_ids":["ramp-up"],"scope":{"kind":"overall"}}""",
            )
        assertEquals(
            "NO_POLICY",
            Json
                .parseToJsonElement(Files.readString(baseline.result))
                .jsonObject
                .getValue("policy_verdict")
                .jsonPrimitive.content,
        )

        listOf(failing to 2, bound to 3).forEachIndexed { index, (policyFile, expected) ->
            val out = tempDir.resolve("out-$index")
            val compared =
                run(
                    "analyze",
                    slow,
                    "--policy",
                    policyFile,
                    "--stages",
                    stages,
                    "--baseline",
                    baseline.result.toString(),
                    "--out-dir",
                    out.toString(),
                    "--data-dir",
                    baseline.dataDir.toString(),
                )
            val control =
                run("analyze", slow, "--policy", policyFile, "--stages", stages, "--data-dir", tempDir.resolve("control-$index").toString())

            assertEquals(expected, compared.exitCode, compared.stderr)
            assertEquals(control.exitCode, compared.exitCode)
            assertEquals(control.stdout, compared.stdout)
            val text = Files.readString(out.resolve("summary.txt"))
            assertTrue(text.contains("scope=steady_window"), text)
            assertTrue(Files.readString(out.resolve("report.html")).contains("Вердикт baseline не PASS"))
        }
    }

    @Test
    fun `comparing an analysis with itself is warned about`() {
        val only = save("only", load)

        val json = summary(only, only.result)

        assertTrue(
            json.getValue("baseline_comparison").jsonObject.getValue("warnings").jsonArray.any {
                it.jsonPrimitive.content == "BASELINE_IS_CURRENT_ANALYSIS"
            },
        )
    }

    @Test
    fun `a baseline that is not a saved analysis of the data directory is exit 4 before anything is written`() {
        val baseline = save("base", load)
        val out = tempDir.resolve("out")
        val fresh = tempDir.resolve("data-fresh")
        val copy = tempDir.resolve("copy").also { Files.createDirectories(it) }.resolve("analysis-result.json")
        Files.copy(baseline.result, copy)
        val unknown = Saved(baseline.dataDir, baseline.runId, "f".repeat(64))

        listOf(
            tempDir.resolve("missing.json"),
            copy,
            unknown.result,
            baseline.result.resolveSibling("identity.json"),
            baseline.dataDir,
        ).forEach { path ->
            val done = run("analyze", slow, "--baseline", path.toString(), "--out-dir", out.toString(), "--data-dir", fresh.toString())
            assertEquals(4, done.exitCode, path.toString())
            assertEquals("BASELINE_NOT_FOUND", done.stderr.trim(), path.toString())
            assertFalse(Files.exists(fresh), "no data directory is created")
            assertFalse(Files.exists(out), "no artifact is written")
        }
        val sameDir =
            run(
                "analyze",
                slow,
                "--baseline",
                unknown.result.toString(),
                "--out-dir",
                out.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(4, sameDir.exitCode)
        assertEquals("BASELINE_NOT_FOUND", sameDir.stderr.trim())
        assertFalse(Files.exists(out))
    }

    @Test
    fun `a degraded or malformed current analysis exits as it does without a baseline`() {
        val baseline = save("base", load)
        val malformed = tempDir.resolve("malformed.jtl")
        Files.writeString(malformed, Files.readAllLines(Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")).first() + "\nmalformed\n")
        val degraded = tempDir.resolve("truncated.log")
        Files.write(degraded, Files.readAllBytes(Path.of("fixtures/slice1/gatling/binary-3.13.5/simulation.log")) + byteArrayOf(2, 0))
        val pass = Path.of("fixtures/slice1/policies/pass.json").toString()

        listOf(degraded to 3, malformed to 4).forEachIndexed { index, (input, expected) ->
            val compared =
                run(
                    "analyze",
                    input.toString(),
                    "--policy",
                    pass,
                    "--baseline",
                    baseline.result.toString(),
                    "--out-dir",
                    tempDir.resolve("out-$index").toString(),
                    "--data-dir",
                    baseline.dataDir.toString(),
                )
            val control = run("analyze", input.toString(), "--policy", pass, "--data-dir", tempDir.resolve("control-$index").toString())

            assertEquals(expected, compared.exitCode, compared.stderr)
            assertEquals(control.stdout, compared.stdout)
            assertTrue(Files.readString(tempDir.resolve("out-$index").resolve("report.html")).contains("Изменения относительно baseline"))
        }
    }

    @Test
    fun `a baseline path through a link is refused`() {
        val baseline = save("base", load)
        val link = tempDir.resolve("link")
        try {
            Files.createSymbolicLink(link, baseline.dataDir)
        } catch (_: Exception) {
            org.junit.jupiter.api.Assumptions
                .abort<Unit>("symbolic links are not available")
        }
        val through =
            link
                .resolve(
                    "runs",
                ).resolve(baseline.runId)
                .resolve("analyses")
                .resolve(baseline.analysisId)
                .resolve("analysis-result.json")

        val done =
            run(
                "analyze",
                slow,
                "--baseline",
                through.toString(),
                "--out-dir",
                tempDir.resolve("o").toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )

        assertEquals(4, done.exitCode)
        assertEquals("BASELINE_NOT_FOUND", done.stderr.trim())
    }

    @Test
    fun `the flag needs an out dir on analyze and is a usage error when repeated, valueless or with json and svg`() {
        val baseline = save("base", load)
        val fresh = tempDir.resolve("data-fresh")

        val noOut = run("analyze", slow, "--baseline", baseline.result.toString(), "--data-dir", fresh.toString())
        val twice =
            run(
                "analyze",
                slow,
                "--baseline",
                baseline.result.toString(),
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                tempDir.resolve("o").toString(),
            )
        val valueless = run("analyze", slow, "--baseline")
        val json =
            run(
                "report",
                baseline.runId,
                baseline.analysisId,
                "--format",
                "json",
                "--baseline",
                baseline.result.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        val svg =
            run(
                "report",
                baseline.runId,
                baseline.analysisId,
                "--format",
                "svg",
                "--baseline",
                baseline.result.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )

        assertEquals(4, noOut.exitCode)
        assertEquals("BASELINE_OUT_DIR_REQUIRED", noOut.stderr.trim())
        assertFalse(Files.exists(fresh))
        listOf(twice, valueless, json, svg).forEach { assertEquals(64, it.exitCode, it.stderr) }
        assertTrue(run("--help").stdout.contains("[--baseline <analysis-result.json>]"))
    }

    @Test
    fun `without the flag a staged run writes no baseline words`() {
        val out = tempDir.resolve("out")
        save("plain", load, "--stages", stages, "--out-dir", out.toString())

        listOf("report.html", "summary.txt").forEach { assertFalse(Files.readString(out.resolve(it)).contains("относительно baseline")) }
        assertFalse(Files.readString(out.resolve("summary.txt")).contains("baseline"))
    }

    @Test
    fun `policy baseline_regression fail gates verdict exit code and separates cached analysis ids per baseline`() {
        val baseline = save("base", load, "--stages", stages)
        val fasterPath =
            tempDir
                .resolve("fast.jtl")
                .also { path ->
                    val lines = Files.readAllLines(Path.of(load))
                    val body =
                        lines.drop(1).map { line ->
                            val cells = line.split(",").toMutableList()
                            cells[1] = maxOf(1, cells[1].toInt() / 2).toString()
                            cells.joinToString(",")
                        }
                    Files.write(path, listOf(lines.first()) + body)
                }.toString()
        val fastBaseline = save("fast-base", fasterPath, "--stages", stages, dataDir = baseline.dataDir)

        val failOnRegressionPolicy =
            policyWithDefaults(
                "reg-fail",
                """"sample_floor":1,"min_samples":1,"baseline_regression":"fail"""",
                """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}""",
            )
        val ignoreRegressionPolicy =
            policyWithDefaults(
                "reg-ignore",
                """"sample_floor":1,"min_samples":1,"baseline_regression":"ignore"""",
                """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}""",
            )

        // 1. Missing --baseline with baseline_regression: fail -> exit 3 (NO_VERDICT, BASELINE_REQUIRED)
        val missingBaseline =
            run(
                "analyze",
                slow,
                "--policy",
                failOnRegressionPolicy,
                "--stages",
                stages,
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(3, missingBaseline.exitCode, missingBaseline.stderr)
        val missingJson = Json.parseToJsonElement(missingBaseline.stdout).jsonObject
        assertEquals("NO_VERDICT", missingJson.getValue("policy_verdict").jsonPrimitive.content)
        assertTrue(
            missingJson
                .getValue("analysis_coverage")
                .jsonObject
                .getValue("reasons")
                .jsonArray
                .any { it.jsonPrimitive.content == "BASELINE_REQUIRED" },
        )

        // 2. Regression against baseline -> exit 2 (FAIL) even though policy rule p95 <= 100000 passed
        val regOut = tempDir.resolve("out-reg-fail")
        val regressed =
            run(
                "analyze",
                slow,
                "--policy",
                failOnRegressionPolicy,
                "--stages",
                stages,
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                regOut.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(2, regressed.exitCode, regressed.stderr)
        val regJson = Json.parseToJsonElement(regressed.stdout).jsonObject
        assertEquals("FAIL", regJson.getValue("policy_verdict").jsonPrimitive.content)
        assertTrue(
            regJson.getValue("findings").jsonArray.any {
                it.jsonObject
                    .getValue("type")
                    .jsonPrimitive.content == "baseline_regression_failure"
            },
        )
        val regAnalysisId = Regex("analysis_id=([0-9a-f]{64})").find(regressed.stderr)!!.groupValues[1]
        val regIdentity =
            Json
                .parseToJsonElement(
                    Files.readString(
                        baseline.dataDir
                            .resolve("runs")
                            .resolve(regJson.getValue("run_id").jsonPrimitive.content)
                            .resolve("analyses")
                            .resolve(regAnalysisId)
                            .resolve("identity.json"),
                    ),
                ).jsonObject
        assertEquals(baseline.analysisId, regIdentity.getValue("baseline_analysis_id").jsonPrimitive.content)
        assertTrue(Files.readString(regOut.resolve("junit.xml")).contains("<failure "))
        assertTrue(Files.readString(regOut.resolve("report.html")).contains("baseline_regression"))
        assertTrue(Files.readString(regOut.resolve("summary.txt")).contains("FAIL baseline_regression"))

        // 3. Same JTL + policy against a DIFFERENT baseline produces a distinct analysis_id (no cache collision)
        val regOut2 = tempDir.resolve("out-reg-fail-2")
        val regressed2 =
            run(
                "analyze",
                slow,
                "--policy",
                failOnRegressionPolicy,
                "--stages",
                stages,
                "--baseline",
                fastBaseline.result.toString(),
                "--out-dir",
                regOut2.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(2, regressed2.exitCode, regressed2.stderr)
        val regAnalysisId2 = Regex("analysis_id=([0-9a-f]{64})").find(regressed2.stderr)!!.groupValues[1]
        assertFalse(regAnalysisId == regAnalysisId2)

        // 4. Improvement / no regression against slow baseline -> exit 0 (PASS)
        val passOut = tempDir.resolve("out-reg-pass")
        val improved =
            run(
                "analyze",
                load,
                "--policy",
                failOnRegressionPolicy,
                "--stages",
                stages,
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                passOut.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(0, improved.exitCode, improved.stderr)
        val improvedJson = Json.parseToJsonElement(improved.stdout).jsonObject
        assertEquals("PASS", improvedJson.getValue("policy_verdict").jsonPrimitive.content)

        // 5. Incompatible baseline (plain vs staged) -> exit 3 (NO_VERDICT, BASELINE_REGRESSION_NOT_EVALUATED)
        val plainOut = tempDir.resolve("out-reg-incompat")
        val incompatible =
            run(
                "analyze",
                slow,
                "--policy",
                failOnRegressionPolicy,
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                plainOut.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(3, incompatible.exitCode, incompatible.stderr)
        val incompatJson = Json.parseToJsonElement(incompatible.stdout).jsonObject
        assertEquals("NO_VERDICT", incompatJson.getValue("policy_verdict").jsonPrimitive.content)
        assertTrue(
            incompatJson
                .getValue("analysis_coverage")
                .jsonObject
                .getValue("reasons")
                .jsonArray
                .any { it.jsonPrimitive.content == "BASELINE_REGRESSION_NOT_EVALUATED" },
        )

        // 6. baseline_regression: ignore preserves backward-compatible PASS (exit 0)
        val ignoredOut = tempDir.resolve("out-reg-ignore")
        val ignored =
            run(
                "analyze",
                slow,
                "--policy",
                ignoreRegressionPolicy,
                "--stages",
                stages,
                "--baseline",
                baseline.result.toString(),
                "--out-dir",
                ignoredOut.toString(),
                "--data-dir",
                baseline.dataDir.toString(),
            )
        assertEquals(0, ignored.exitCode, ignored.stderr)
        val ignoredJson = Json.parseToJsonElement(ignored.stdout).jsonObject
        assertEquals("PASS", ignoredJson.getValue("policy_verdict").jsonPrimitive.content)
    }

    private fun policyWithDefaults(
        name: String,
        defaults: String,
        rule: String,
    ): String =
        tempDir
            .resolve("$name-policy.json")
            .also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"$name","defaults":{$defaults},"rules":[$rule]}""",
                )
            }.toString()

    private fun policy(
        name: String,
        rule: String,
    ): String =
        tempDir
            .resolve("$name-policy.json")
            .also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"$name","defaults":{"sample_floor":1,"min_samples":1},"rules":[$rule]}""",
                )
            }.toString()

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf(*args), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8))
        return CliResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }
}

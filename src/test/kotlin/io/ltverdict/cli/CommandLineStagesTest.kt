package io.ltverdict.cli

import io.ltverdict.core.sha256Hex
import io.ltverdict.sources.ONLINE_LOAD
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0030, PR B: `ltv analyze --stages` on fixtures/stages/ramp-steady-rampdown.jtl (plateau p95 194 ms, whole run 821 ms). */
class CommandLineStagesTest {
    @TempDir
    lateinit var tempDir: Path

    private val load = Path.of("fixtures/stages/ramp-steady-rampdown.jtl").toString()
    private val stages = Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json").toString()

    @Test
    fun `help names the stages flag`() {
        val help = run("--help")

        assertEquals(0, help.exitCode)
        assertTrue(help.stdout.contains("[--stages <load-stages.json>]"), help.stdout)
    }

    @Test
    fun `the plateau passes where the whole run fails and a failing plateau rule exits 2`() {
        val passing = policy("passing", rule("p95", 250) + "," + rpsRule())
        val failing = policy("failing", rule("p95", 190, """,""" + """"window_ids":["steady"]"""))

        val staged = run("analyze", load, "--policy", passing, "--stages", stages, "--data-dir", data("a"))
        val whole = run("analyze", load, "--policy", passing, "--data-dir", data("b"))
        val plateauFail = run("analyze", load, "--policy", failing, "--stages", stages, "--data-dir", data("c"))
        val noPolicy = run("analyze", load, "--stages", stages, "--data-dir", data("d"))

        assertEquals(0, staged.exitCode, staged.stderr)
        assertEquals("PASS", staged.stdout.json("policy_verdict"))
        assertTrue(
            Json
                .parseToJsonElement(staged.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .any { it.type() == "stage_binding" },
        )
        assertEquals(2, whole.exitCode, whole.stderr)
        assertFalse(whole.stdout.contains("stage_binding"))
        assertEquals(2, plateauFail.exitCode, plateauFail.stderr)
        assertEquals("FAIL", plateauFail.stdout.json("policy_verdict"))
        assertEquals(0, noPolicy.exitCode, noPolicy.stderr)
        assertEquals("NO_POLICY", noPolicy.stdout.json("policy_verdict"))
    }

    @Test
    fun `an unsatisfied rule of the window is no verdict, exit 3`() {
        val bound = policy("bound", rule("ramp", 1000, ""","window_ids":["ramp-up"]"""))

        val result = run("analyze", load, "--policy", bound, "--stages", stages, "--data-dir", data("a"))

        assertEquals(3, result.exitCode, result.stderr)
        assertEquals("NO_VERDICT", result.stdout.json("policy_verdict"))
    }

    @Test
    fun `a repeated or valueless stages flag is a usage error`() {
        assertEquals(64, run("analyze", load, "--stages", stages, "--stages", stages).exitCode)
        assertEquals(64, run("analyze", load, "--stages").exitCode)
    }

    @Test
    fun `an unreadable or invalid declaration is exit 4 with code, pointer and message`() {
        val invalid = Path.of("docs/contracts/stages/v1/examples/invalid")

        val noSteady = run("analyze", load, "--stages", invalid.resolve("no-steady.json").toString(), "--data-dir", data("a"))
        val overlap = run("analyze", load, "--stages", invalid.resolve("overlap.json").toString(), "--data-dir", data("b"))
        val missing = run("analyze", load, "--stages", tempDir.resolve("missing.json").toString(), "--data-dir", data("c"))

        assertEquals(4, noSteady.exitCode)
        assertEquals("STAGES_NO_STEADY /stages: at least one stage must have the role steady", noSteady.stderr.trim())
        assertEquals(4, overlap.exitCode)
        assertTrue(overlap.stderr.startsWith("OVERLAPPING_STAGES /stages/1/from_offset_ms: "), overlap.stderr)
        assertEquals(4, missing.exitCode)
        assertTrue(missing.stderr.startsWith("INVALID_STAGES"), missing.stderr)
        assertFalse(Files.exists(Path.of(data("a"))), "the data directory is not created for an invalid declaration")
    }

    @Test
    fun `stages with resources, a capacity plan or an online source are refused before any input is read`() {
        val resources = Path.of("docs/contracts/resources/v1/examples/valid/basic.json").toString()
        val capacity = Path.of("docs/contracts/capacity/v1/examples/valid/rps.json").toString()
        val dir = data("conflict")

        val withResources = run("analyze", load, "--stages", stages, "--resources", resources, "--data-dir", dir)
        val withCapacity = run("analyze", load, "--stages", stages, "--capacity", capacity, "--data-dir", dir)
        val both = run("analyze", load, "--stages", stages, "--resources", resources, "--capacity", capacity, "--data-dir", dir)
        // the files of the source do not exist: the conflict is found before they are opened
        val withSource =
            run("analyze", load, "--stages", stages, "--connections", "c.json", "--source", "s.json", "--data-dir", dir)

        assertEquals(4, withResources.exitCode)
        assertEquals("STAGES_RESOURCES_CONFLICT", withResources.stderr.trim())
        assertEquals(4, withCapacity.exitCode)
        assertEquals("STAGES_CAPACITY_CONFLICT", withCapacity.stderr.trim())
        assertEquals("STAGES_CAPACITY_CONFLICT", both.stderr.trim())
        assertEquals(4, withSource.exitCode)
        // ADR 0037, D7: stages no longer refuse an online request; the missing source file is reported
        assertEquals(
            "SOURCE_FILE_INVALID",
            withSource.stderr
                .lineSequence()
                .first()
                .trim(),
        )
        assertFalse(Files.exists(Path.of(dir)), "no data directory is created")
    }

    @Test
    fun `stages with a snapshot without windows analyze and print the steady scope`() {
        val out = tempDir.resolve("out")

        val result =
            run(
                "analyze",
                load,
                "--policy",
                PLATFORM_POLICY,
                "--stages",
                stages,
                "--resources",
                SNAPSHOT,
                "--out-dir",
                out.toString(),
                "--data-dir",
                data("a"),
            )

        assertEquals(0, result.exitCode, result.stderr)
        listOf("report.html", "junit.xml", "summary.txt", "result.json").forEach {
            assertTrue(Files.isRegularFile(out.resolve(it)), it)
        }
        assertTrue(Files.readString(out.resolve("summary.txt")).contains("scope: steady window (steady)"))
        val html = Files.readString(out.resolve("report.html"))
        assertTrue(html.contains("Область вердикта") && html.contains("Привязка ресурсов"))
        val evidence =
            Json
                .parseToJsonElement(Files.readString(out.resolve("result.json")))
                .jsonObject
                .getValue("evidence")
                .jsonArray
        val binding = evidence.single { it.type() == "resource_binding" }.jsonObject
        assertEquals("declared_stages", binding.getValue("mode").jsonPrimitive.content)
        val steady =
            evidence
                .single { it.type() == "stage_binding" }
                .jsonObject
                .getValue("stages")
                .jsonArray
                .single {
                    it.jsonObject
                        .getValue("id")
                        .jsonPrimitive.content == "steady"
                }.jsonObject
        assertEquals("1767225645000", steady.getValue("resource_from_epoch_ms").jsonPrimitive.content)
        assertEquals("1767225690000", steady.getValue("resource_to_epoch_ms").jsonPrimitive.content)
    }

    @Test
    fun `a snapshot that starts inside the steady stage is partial coverage named in words in the report`() {
        val out = tempDir.resolve("out")
        val late =
            tempDir.resolve("late-snapshot.json").also {
                Files.writeString(
                    it,
                    Files
                        .readString(Path.of(SNAPSHOT))
                        .replace("\"start_epoch_ms\": 1767225600000", "\"start_epoch_ms\": 1767225660000")
                        .replace("\"point_count\": 8", "\"point_count\": 4")
                        .replace("[0.2, 0.2, 0.9, 0.9, 0.2, 0.2, 0.2, 0.2]", "[0.2, 0.2, 0.2, 0.2]")
                        .replace("[0, 0, 0, 0, 0, 0, 0, 0]", "[0, 0, 0, 0]"),
                )
            }

        val result =
            run(
                "analyze",
                load,
                "--policy",
                PLATFORM_POLICY,
                "--stages",
                stages,
                "--resources",
                late.toString(),
                "--out-dir",
                out.toString(),
                "--data-dir",
                data("a"),
            )

        assertEquals(0, result.exitCode, result.stderr)
        val coverage =
            Json
                .parseToJsonElement(result.stdout)
                .jsonObject
                .getValue("analysis_coverage")
                .jsonObject
        assertEquals("INCOMPLETE", coverage.getValue("status").jsonPrimitive.content)
        assertTrue(coverage.getValue("reasons").toString().contains("STAGE_WINDOW_SNAPSHOT_PARTIAL"))
        val html = Files.readString(out.resolve("report.html"))
        assertTrue(html.contains("STAGE_WINDOW_SNAPSHOT_PARTIAL"))
        assertFalse(html.contains("Причина без расшифровки"), "the coverage reason is named in words")
        assertTrue(html.contains("Стадии: steady."), "the coverage reason names the partial stage")
    }

    @Test
    fun `stages refuse a capacity plan, a snapshot with windows, a correlation plan and a trend plan in this order`() {
        val windowed = Path.of("docs/contracts/resources/v1/examples/valid/basic.json").toString()
        val capacity = Path.of("docs/contracts/capacity/v1/examples/valid/rps.json").toString()
        val dir = data("refused")

        // the plans do not exist: the refusals come before they are opened
        val withCorrelation = run("analyze", load, "--stages", stages, "--correlation", "c.json", "--data-dir", dir)
        val withTrend = run("analyze", load, "--stages", stages, "--trend", "t.json", "--data-dir", dir)
        val windowedFirst =
            run(
                "analyze",
                load,
                "--stages",
                stages,
                "--resources",
                windowed,
                "--correlation",
                "c.json",
                "--trend",
                "t.json",
                "--data-dir",
                dir,
            )
        val capacityFirst = run("analyze", load, "--stages", stages, "--resources", windowed, "--capacity", capacity, "--data-dir", dir)
        val correlationFirst = run("analyze", load, "--stages", stages, "--correlation", "c.json", "--trend", "t.json", "--data-dir", dir)
        // an online request is no longer refused for stages: the missing connections file is reported instead
        val withSource =
            run("analyze", load, "--stages", stages, "--connections", "c.json", "--source", "s.json", "--data-dir", dir)

        assertEquals(4, withCorrelation.exitCode)
        assertEquals("STAGES_CORRELATION_CONFLICT", withCorrelation.stderr.trim())
        assertEquals(4, withTrend.exitCode)
        assertEquals("STAGES_TREND_CONFLICT", withTrend.stderr.trim())
        assertEquals(4, windowedFirst.exitCode)
        assertEquals("STAGES_RESOURCES_CONFLICT", windowedFirst.stderr.trim())
        assertEquals("STAGES_CAPACITY_CONFLICT", capacityFirst.stderr.trim())
        assertEquals("STAGES_CORRELATION_CONFLICT", correlationFirst.stderr.trim())
        assertEquals(4, withSource.exitCode)
        assertEquals(
            "SOURCE_FILE_INVALID",
            withSource.stderr
                .lineSequence()
                .first()
                .trim(),
        )
        assertFalse(Files.exists(Path.of(dir)), "no data directory is created")
    }

    @Test
    fun `stages with a snapshot without windows refuse a correlation or trend plan by the codes of the core`() {
        val dir = data("plans")

        // the plans do not exist: the refusals come before they are opened and before the snapshot binding is checked
        val withCorrelation =
            run("analyze", load, "--stages", stages, "--resources", SNAPSHOT, "--correlation", "c.json", "--data-dir", dir)
        val withTrend = run("analyze", load, "--stages", stages, "--resources", SNAPSHOT, "--trend", "t.json", "--data-dir", dir)

        assertEquals(4, withCorrelation.exitCode)
        assertEquals("STAGES_CORRELATION_CONFLICT", withCorrelation.stderr.trim())
        assertEquals(4, withTrend.exitCode)
        assertEquals("STAGES_TREND_CONFLICT", withTrend.stderr.trim())
        assertFalse(Files.exists(Path.of(dir)), "no data directory is created")
    }

    @Test
    fun `a conflict is refused before the policy is read from stdin and before any artifact is written`() {
        val resources = Path.of("docs/contracts/resources/v1/examples/valid/basic.json").toString()
        val out = tempDir.resolve("out")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val unreadable =
            object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("stdin must not be read")
            }

        val exit =
            runCli(
                arrayOf("analyze", load, "--policy", "-", "--stages", stages, "--resources", resources, "--out-dir", out.toString()),
                PrintStream(stdout, true, UTF_8),
                PrintStream(stderr, true, UTF_8),
                unreadable,
            )

        assertEquals(4, exit)
        assertEquals("STAGES_RESOURCES_CONFLICT", stderr.toString(UTF_8).trim())
        assertFalse(Files.exists(out), "no artifact is written")
        val late = stagesFile("late", """{"id":"late","role":"steady","from_offset_ms":130000,"to_offset_ms":140000}""")
        val outside = run("analyze", load, "--stages", late, "--out-dir", out.toString(), "--data-dir", data("a"))
        assertEquals(4, outside.exitCode)
        assertFalse(Files.exists(out.resolve("result.json")), "no artifact is written for STAGE_OUTSIDE_RUN")
    }

    @Test
    fun `a steady stage past the run end is exit 4, an excluded one and a clipped end pass`() {
        val late = stagesFile("late", """{"id":"late","role":"steady","from_offset_ms":130000,"to_offset_ms":140000}""")
        val tail = stagesFile("tail", """{"id":"tail","role":"steady","from_offset_ms":100000,"to_offset_ms":130000}""")

        val outside = run("analyze", load, "--stages", late, "--data-dir", data("a"))
        val clipped = run("analyze", load, "--stages", tail, "--data-dir", data("b"))
        val excluded = run("analyze", load, "--stages", stages, "--data-dir", data("c"))

        assertEquals(4, outside.exitCode)
        assertEquals("STAGE_OUTSIDE_RUN", outside.stderr.trim())
        assertEquals(0, clipped.exitCode, clipped.stderr)
        assertTrue(clipped.stdout.contains("\"clipped_to_run_end\":true"))
        assertEquals(0, excluded.exitCode, excluded.stderr)
    }

    @Test
    fun `an offline source context without a snapshot is allowed with stages`() {
        val onlineLoad = ONLINE_LOAD.replace("1767225600000,1000", "1000,2000")
        val input = tempDir.resolve("context.jtl")
        Files.writeString(input, onlineLoad)
        val context = tempDir.resolve("errors.json")
        Files.writeString(
            context,
            Files
                .readString(Path.of("docs/contracts/sources/v1/opensearch-errors.example.json"))
                .replace("a".repeat(64), sha256Hex(onlineLoad.encodeToByteArray())),
        )
        val window = stagesFile("window", """{"id":"steady","role":"steady","from_offset_ms":0,"to_offset_ms":1000}""")

        val result = run("analyze", input.toString(), "--stages", window, "--source-context", context.toString(), "--data-dir", data("a"))

        assertEquals(0, result.exitCode, result.stderr)
        val types =
            Json
                .parseToJsonElement(result.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .map { it.type() }
        assertTrue("stage_binding" in types && "opensearch_errors" in types, types.toString())
    }

    @Test
    fun `a run without the flag is repeatable and carries no stage fields`() {
        val first = run("analyze", load, "--data-dir", data("a"))
        val second = run("analyze", load, "--data-dir", data("b"))

        assertEquals(first.stdout, second.stdout)
        assertFalse(first.stdout.contains("stage_binding"))
        assertFalse(first.stdout.contains("load_stages"))
    }

    private fun data(name: String) = tempDir.resolve("data-$name").toString()

    private fun rule(
        id: String,
        threshold: Int,
        extra: String = "",
    ) = """{"id":"$id","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold$extra,"scope":{"kind":"overall"}}"""

    private fun rpsRule() = """{"id":"rps","metric":"throughput_rps","operator":"gte","threshold":9,"scope":{"kind":"overall"}}"""

    private fun policy(
        name: String,
        rules: String,
    ): String =
        tempDir
            .resolve("$name-policy.json")
            .also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"$name","defaults":{"sample_floor":1,"min_samples":1},"rules":[$rules]}""",
                )
            }.toString()

    private fun stagesFile(
        name: String,
        vararg stages: String,
    ): String =
        tempDir
            .resolve("$name-stages.json")
            .also { Files.writeString(it, """{"schema_version":"load-stages.v1","stages":[${stages.joinToString(",")}]}""") }
            .toString()

    private fun kotlinx.serialization.json.JsonElement.type() = jsonObject["type"]?.jsonPrimitive?.content

    private fun String.json(field: String) =
        Json
            .parseToJsonElement(this)
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

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

    private companion object {
        // ADR 0037: a snapshot without windows on a 15 s grid for this JTL and a policy with a platform SLA rule
        val SNAPSHOT = Path.of("fixtures/stages/cross-version/snapshot-15s.json").toString()
        val PLATFORM_POLICY = Path.of("fixtures/stages/cross-version/policy-platform.json").toString()
    }
}

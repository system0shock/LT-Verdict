package io.ltverdict.cli

import io.ltverdict.core.StageProfiles
import io.ltverdict.core.containerRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
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

/** Plan W4.1, task 4: `ltv stages detect` (ADR 0036, D8, D12). */
class CommandLineStageDetectTest {
    @TempDir
    lateinit var tempDir: Path

    private val load = Path.of("fixtures/stages/ramp-steady-rampdown.jtl")
    private val example = Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json")

    private val anchorExplanation =
        listOf(
            "stage_proposal status=DETECTED method=rps-plateau-segmentation.v1 steady_steps=top confidence=HIGH calibrated=false",
            "run start_epoch_ms=1767225600000 end_epoch_ms=1767225719800 cell_ms=5000 cells=23 noise_rps=0.200",
            "segment from_offset_ms=0 to_offset_ms=40000 kind=FLAT level_rps=2.000 drift=0.000 residual_cv=0.000",
            "segment from_offset_ms=40000 to_offset_ms=100000 kind=FLAT level_rps=10.000 drift=0.000 residual_cv=0.000",
            "segment from_offset_ms=100000 to_offset_ms=115000 kind=FLAT level_rps=2.000 drift=0.000 residual_cv=0.000",
            "stage id=ramp-up role=excluded from_offset_ms=0 to_offset_ms=40000 basis=BEFORE_FIRST_STEADY",
            "stage id=steady role=steady from_offset_ms=40000 to_offset_ms=100000 basis=PLATEAU level_rps=10.000 " +
                "from_contrast_sigma=40.000 to_contrast_sigma=40.000 trimmed_cells=0",
            "stage id=ramp-down role=excluded from_offset_ms=100000 to_offset_ms=120000 basis=AFTER_LAST_STEADY",
            "next: review the stages, then run ltv analyze <input> --stages <file>",
        ).joinToString("") { "$it\n" }

    @Test
    fun `help names the detect command`() {
        val help = run("--help")

        assertEquals(0, help.exitCode)
        assertTrue(help.stdout.contains("ltv stages detect <input> [--out <file>] [--steady-steps top|all]"), help.stdout)
    }

    @Test
    fun `the anchor fixture prints the example declaration`() {
        val result = run("stages", "detect", load.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(exampleCanonical(), result.stdout)
        assertEquals(anchorExplanation, result.stderr)
    }

    @Test
    fun `the steady steps mode is printed and only flips roles`() {
        val top = run("stages", "detect", load.toString())
        val explicitTop = run("stages", "detect", load.toString(), "--steady-steps", "top")
        val all = run("stages", "detect", load.toString(), "--steady-steps", "all")

        assertEquals(top, explicitTop)
        assertEquals(top.stdout, all.stdout)
        assertEquals(top.stderr.replace("steady_steps=top", "steady_steps=all"), all.stderr)

        val steps = StageProfiles.f2.writeJtl(tempDir.resolve("steps.jtl"))
        val stepsTop = run("stages", "detect", steps.toString())
        val stepsAll = run("stages", "detect", steps.toString(), "--steady-steps", "all")
        assertEquals(0, stepsTop.exitCode, stepsTop.stderr)
        assertEquals(listOf("step-5"), steadyIds(stepsTop.stdout))
        assertEquals(listOf("step-1", "step-2", "step-3", "step-4", "step-5"), steadyIds(stepsAll.stdout))
    }

    @Test
    fun `the proposal drives the analysis to the plateau`() {
        val proposal = tempDir.resolve("proposal.json")
        Files.writeString(proposal, run("stages", "detect", load.toString()).stdout)

        val analysis = run("analyze", load.toString(), "--policy", guidePolicy(), "--stages", proposal.toString(), "--data-dir", data("a"))

        assertEquals(0, analysis.exitCode, analysis.stderr)
        val result = Json.parseToJsonElement(analysis.stdout).jsonObject
        assertEquals("PASS", result.getValue("policy_verdict").jsonPrimitive.content)
        val window = steadyWindow(result)
        assertEquals("600", window.getValue("sample_count").jsonPrimitive.content)
        assertEquals(
            "194",
            window
                .getValue("latency_ms")
                .jsonObject
                .getValue("p95")
                .jsonPrimitive.content,
        )
        val throughput = window.getValue("throughput_rps").jsonObject
        assertEquals("600000", throughput.getValue("numerator").jsonPrimitive.content, "$throughput")
        assertEquals("60000", throughput.getValue("denominator").jsonPrimitive.content, "$throughput")
    }

    @Test
    fun `an early setUp sample becomes a lead-in and the window stays on the plateau`() {
        val lines = Files.readAllLines(load)
        val withSetUp = tempDir.resolve("setup.jtl")
        Files.writeString(
            withSetUp,
            (
                listOf(lines.first(), "1767225570000,50,setUp,200,OK,setUp 1-1,text,true,,128,64,1,1,http://example.test/setup,50,0,5") +
                    lines.drop(1)
            ).joinToString("\n", postfix = "\n"),
        )

        val result = run("stages", "detect", withSetUp.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(
            listOf("lead-in:0-30000", "ramp-up:30000-70000", "steady:70000-130000", "ramp-down:130000-150000"),
            stages(result.stdout),
        )
        assertTrue(result.stderr.contains("\nwarning code=LEAD_IN_IDLE stage_id=lead-in at_offset_ms=0 duration_ms=30000\n"), result.stderr)
        assertTrue(
            result.stderr.startsWith(
                "stage_proposal status=DETECTED method=rps-plateau-segmentation.v1 steady_steps=top confidence=MEDIUM",
            ),
        )
        val proposal = Files.writeString(tempDir.resolve("setup-stages.json"), result.stdout)
        val analysis =
            run("analyze", withSetUp.toString(), "--policy", guidePolicy(), "--stages", proposal.toString(), "--data-dir", data("b"))
        assertEquals(0, analysis.exitCode, analysis.stderr)
        val document = Json.parseToJsonElement(analysis.stdout).jsonObject
        assertEquals("PASS", document.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals("600", steadyWindow(document).getValue("sample_count").jsonPrimitive.content)
        assertEquals(
            "194",
            steadyWindow(document)
                .getValue("latency_ms")
                .jsonObject
                .getValue("p95")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `a steady to the end is clipped by the analysis`() {
        val jtl = StageProfiles.f9.writeJtl(tempDir.resolve("to-the-end.jtl"))
        val result = run("stages", "detect", jtl.toString())
        assertEquals(0, result.exitCode, result.stderr)
        val proposal = Files.writeString(tempDir.resolve("to-the-end.json"), result.stdout)

        val analysis = run("analyze", jtl.toString(), "--stages", proposal.toString(), "--data-dir", data("c"))

        assertEquals(0, analysis.exitCode, analysis.stderr)
        assertFalse(analysis.stderr.contains("STAGE_OUTSIDE_RUN"))
        val binding =
            Json
                .parseToJsonElement(analysis.stdout)
                .jsonObject
                .getValue("evidence")
                .jsonArray
                .map { it.jsonObject }
                .single { it["type"]?.jsonPrimitive?.content == "stage_binding" }
        val steady =
            binding
                .getValue("stages")
                .jsonArray
                .map { it.jsonObject }
                .single { it["id"]?.jsonPrimitive?.content == "steady" }
        assertEquals("true", steady.getValue("clipped_to_run_end").jsonPrimitive.content)
    }

    @Test
    fun `row order does not change the proposal`() {
        val lines = Files.readAllLines(load)
        val reversed = tempDir.resolve("reversed.jtl")
        Files.writeString(reversed, (listOf(lines.first()) + lines.drop(1).reversed()).joinToString("\n", postfix = "\n"))

        val forward = run("stages", "detect", load.toString())
        val backward = run("stages", "detect", reversed.toString())

        assertEquals(forward, backward)
    }

    @Test
    fun `the same input gives the same bytes twice`() {
        val first = run("stages", "detect", load.toString())
        val second = run("stages", "detect", load.toString())

        assertArrayEquals(first.stdout.toByteArray(UTF_8), second.stdout.toByteArray(UTF_8))
        assertArrayEquals(first.stderr.toByteArray(UTF_8), second.stderr.toByteArray(UTF_8))
    }

    @Test
    fun `a refusal writes nothing and exits 3`() {
        val jtl = StageProfiles.f4.writeJtl(tempDir.resolve("ramp.jtl"))
        val out = tempDir.resolve("never.json")

        val result = run("stages", "detect", jtl.toString(), "--out", out.toString())

        assertEquals(3, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        assertFalse(Files.exists(out))
        assertEquals(
            "stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v1 steady_steps=top reason=NO_PLATEAU",
            result.stderr.lineSequence().first(),
        )
        assertTrue(result.stderr.contains("\ndetail steady_share="), result.stderr)
    }

    @Test
    fun `--out writes the file and refuses to overwrite`() {
        val out = tempDir.resolve("stages.json")

        val first = run("stages", "detect", load.toString(), "--out", out.toString())
        assertEquals(0, first.exitCode, first.stderr)
        assertEquals("", first.stdout)
        assertEquals(exampleCanonical(), Files.readString(out))

        Files.writeString(out, "keep")
        val second = run("stages", "detect", load.toString(), "--out", out.toString())
        assertEquals(4, second.exitCode)
        assertTrue(second.stderr.startsWith("STAGES_OUT_EXISTS"), second.stderr)
        assertEquals("keep", Files.readString(out))
    }

    @Test
    fun `--out on a link is refused`() {
        val link = tempDir.resolve("link.json")
        try {
            Files.createSymbolicLink(link, tempDir.resolve("missing-target.json"))
        } catch (_: Exception) {
            org.junit.jupiter.api.Assumptions
                .abort<Unit>("symbolic links are not available")
        }
        val throughLink = run("stages", "detect", load.toString(), "--out", link.toString())
        assertEquals(4, throughLink.exitCode)
        assertTrue(throughLink.stderr.startsWith("STAGES_OUT_EXISTS"), throughLink.stderr)
        assertFalse(Files.exists(tempDir.resolve("missing-target.json")))
    }

    @Test
    fun `usage errors exit 64`() {
        val input = load.toString()
        listOf(
            arrayOf("stages"),
            arrayOf("stages", "detect"),
            arrayOf("stages", "validate", "x"),
            arrayOf("stages", "detect", input, "--out", "a.json", "--out", "b.json"),
            arrayOf("stages", "detect", input, "extra"),
            arrayOf("stages", "detect", input, "--out"),
            arrayOf("stages", "detect", input, "--steady-steps", "middle"),
            arrayOf("stages", "detect", input, "--steady-steps", "top", "--steady-steps", "all"),
        ).forEach { args -> assertEquals(64, run(*args).exitCode, args.joinToString(" ")) }
    }

    @Test
    fun `a missing or unsupported input exits 4`() {
        val missing = run("stages", "detect", tempDir.resolve("missing.jtl").toString())
        val text = Files.writeString(tempDir.resolve("notes.txt"), "not a load file\n")
        val unsupported = run("stages", "detect", text.toString())
        val invalid =
            Files.writeString(
                tempDir.resolve("invalid.jtl"),
                Files
                    .readAllLines(load)
                    .let { (it.take(5) + "soon,1,x,200,OK,t,text,true,,1,1,1,1,u,1,0,1" + it.drop(5)) }
                    .joinToString("\n", postfix = "\n"),
            )
        val refused = run("stages", "detect", invalid.toString())

        assertEquals(4, missing.exitCode)
        assertTrue(missing.stderr.startsWith("INVALID_INPUT"), missing.stderr)
        assertEquals(4, unsupported.exitCode)
        assertTrue(unsupported.stderr.startsWith("UNSUPPORTED_INPUT"), unsupported.stderr)
        assertEquals(4, refused.exitCode, refused.stderr)
        assertTrue(refused.stderr.startsWith("stage_proposal status=NOT_DETECTED") && refused.stderr.contains("reason=INPUT_INVALID"))
        assertEquals("", refused.stdout)
    }

    @Test
    fun `the gatling log is accepted`() {
        // Ruling (task 4): fixtures/slice0/simulation.log is a Slice 0 contract fixture whose REQUEST rows the Kotlin parser does not
        // accept (8 fields), so the proposal refuses it like `ltv analyze` does; the supported Gatling logs reach the method.
        val slice0 = run("stages", "detect", "fixtures/slice0/simulation.log")
        val analyzed = run("analyze", "fixtures/slice0/simulation.log", "--data-dir", data("g"))

        assertEquals(4, slice0.exitCode, slice0.stderr)
        assertEquals(
            "stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v1 steady_steps=top reason=INPUT_INVALID",
            slice0.stderr.lineSequence().first(),
        )
        assertEquals(4, analyzed.exitCode, analyzed.stderr)
        listOf("fixtures/slice1/gatling/text-3.12.0/simulation.log", "fixtures/slice1/gatling/binary-3.15.1/simulation.log").forEach {
            val result = run("stages", "detect", it)
            assertTrue(result.exitCode == 0 || result.exitCode == 3, result.stderr)
            assertTrue(result.stderr.startsWith("stage_proposal status="), result.stderr)
        }
    }

    @Test
    fun `the command takes no data directory`() {
        assertEquals(64, run("stages", "detect", load.toString(), "--data-dir", data("d")).exitCode)
    }

    @Test
    fun `a container row before the load is the run start`() {
        val lines = Files.readAllLines(load)
        val jtl = tempDir.resolve("container.jtl")
        Files.writeString(jtl, (listOf(lines.first(), containerRow(1_767_225_570_000)) + lines.drop(1)).joinToString("\n", postfix = "\n"))

        val result = run("stages", "detect", jtl.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(stages(result.stdout).contains("steady:70000-130000"), result.stdout)
    }

    private fun exampleCanonical(): String =
        String(
            Files.newInputStream(example).use {
                (io.ltverdict.core.validateLoadStages(it) as io.ltverdict.core.LoadStagesValidation.Valid).canonicalBytes()
            },
            UTF_8,
        )

    private fun stages(document: String): List<String> =
        Json.parseToJsonElement(document).jsonObject.getValue("stages").jsonArray.map {
            val stage = it.jsonObject
            "${stage.getValue("id").jsonPrimitive.content}:${stage.getValue("from_offset_ms").jsonPrimitive.content}-" +
                stage.getValue("to_offset_ms").jsonPrimitive.content
        }

    private fun steadyIds(document: String): List<String> =
        Json
            .parseToJsonElement(document)
            .jsonObject
            .getValue("stages")
            .jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("role").jsonPrimitive.content == "steady" }
            .map { it.getValue("id").jsonPrimitive.content }

    private fun steadyWindow(result: JsonObject): JsonObject =
        result
            .getValue("evidence")
            .jsonArray
            .map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "window_metric_summary" && it["window_id"]?.jsonPrimitive?.content == "steady" }

    /** The policy of the guide: p95 at most 250 ms and at least 9 rps. */
    private fun guidePolicy(): String =
        tempDir
            .resolve("guide-policy.json")
            .also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"guide","defaults":{"sample_floor":1,"min_samples":1},"rules":[""" +
                        """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":250,"scope":{"kind":"overall"}},""" +
                        """{"id":"rps","metric":"throughput_rps","operator":"gte","threshold":9,"scope":{"kind":"overall"}}]}""",
                )
            }.toString()

    private fun data(name: String) = tempDir.resolve("data-$name").toString()

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

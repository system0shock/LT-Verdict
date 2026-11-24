package io.ltverdict.cli

import io.ltverdict.core.StageProfile
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
            "stage_proposal status=DETECTED method=rps-plateau-segmentation.v2 steady_steps=top confidence=HIGH calibrated=false",
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
        assertTrue(
            help.stdout.contains("ltv stages detect <input> [--out <file>] [--steady-steps top|all] [--like <load-stages.json>]"),
            help.stdout,
        )
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
                "stage_proposal status=DETECTED method=rps-plateau-segmentation.v2 steady_steps=top confidence=MEDIUM",
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
            "stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v2 steady_steps=top reason=NO_PLATEAU",
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
            "stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v2 steady_steps=top reason=INPUT_INVALID",
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
    fun `the proposal gives the verdict of the careful window`() {
        // Second review round (R2-R4): the careful engineer's window is the ground truth of the generator. R1 (a collapse at the
        // end) joined with ADR 0036, D14: from the request rate alone a collapse and a planned ramp-down look the same, with
        // latency and errors they do not, and the collapse stays in the window as the careful engineer keeps it.
        val stepDownAbrupt = listOf(60_000L until 1_860_000L, 1_860_000L until 1_980_000L)
        val stepDown120 = listOf(60_000L until 1_860_000L, 1_980_000L until 2_100_000L)
        val cases =
            listOf(
                Triple(StageProfiles.equalDips, "all", listOf(0L until 1_680_000L)),
                Triple(StageProfiles.equalDips, "top", listOf(0L until 1_680_000L)),
                Triple(StageProfiles.riseAbort, "all", listOf(60_000L until 660_000L)),
                Triple(StageProfiles.riseAbort, "top", listOf(60_000L until 660_000L)),
                Triple(StageProfiles.stepDownAbrupt, "all", stepDownAbrupt),
                Triple(StageProfiles.stepDownAbrupt, "top", stepDownAbrupt),
                Triple(StageProfiles.stepDown120, "all", stepDown120),
                Triple(StageProfiles.stepDown120, "top", stepDown120),
            ) +
                (
                    listOf(
                        StageProfiles.collapse(600, 20.0),
                        StageProfiles.collapse(300, 40.0),
                        StageProfiles.soakCollapse,
                        StageProfiles.hang,
                    ) +
                        (1L..3L).map { StageProfiles.shortCollapse(it) }
                ).flatMap { profile ->
                    // The plateau and everything after it, to the end of the run.
                    listOf("all", "top").map { Triple(profile, it, listOf(120_000L until profile.seconds * 1000L + 60_000L)) }
                }
        cases.forEachIndexed { index, (profile, mode, careful) ->
            val jtl = profile.writeJtl(tempDir.resolve("careful-$index.jtl"))
            val detected = run("stages", "detect", jtl.toString(), "--steady-steps", mode)
            assertEquals(0, detected.exitCode, detected.stderr)
            val proposal = Files.writeString(tempDir.resolve("careful-$index-proposal.json"), detected.stdout)
            val declared = carefulStages(profile, careful, "careful-$index")

            val byProposal = verdict(jtl, proposal, "p$index")
            val byCareful = verdict(jtl, declared, "c$index")

            assertEquals(byCareful, byProposal, "${profile.name} $mode:\n${detected.stderr}")
            if (profile.name.startsWith("R1 collapse") || profile.name.startsWith("N1 ") || profile == StageProfiles.soakCollapse) {
                assertEquals("FAIL", byProposal, "${profile.name} $mode:\n${detected.stderr}")
            }
        }
    }

    @Test
    fun `a kept degraded gap leaves the throughput of its step alone`() {
        // Re-review N3: the gap merged into its step pulled a 100 rps step to about 92 rps, and a rule throughput_rps >= 95 bound
        // to the step failed for the wrong reason. As its own window the gap leaves the step at its level.
        val cases =
            listOf(
                Triple(StageProfiles.degradedRiseGap, "all", "step-2" to (1_200_000L until 2_100_000L)),
                Triple(StageProfiles.degradedDeclineGap, "top", "step-1" to (60_000L until 960_000L)),
            )
        cases.forEachIndexed { index, (profile, mode, bound) ->
            val (window, careful) = bound
            val jtl = profile.writeJtl(tempDir.resolve("gap-$index.jtl"))
            val detected = run("stages", "detect", jtl.toString(), "--steady-steps", mode)
            assertEquals(0, detected.exitCode, detected.stderr)
            assertTrue(detected.stderr.contains("warning code=DEGRADED_GAP_KEPT stage_id=gap-2 "), detected.stderr)
            val proposal = Files.writeString(tempDir.resolve("gap-$index-proposal.json"), detected.stdout)
            val policy =
                Files.writeString(
                    tempDir.resolve("gap-$index-policy.json"),
                    """{"schema_version":"policy.v1","policy_id":"gap","defaults":{"sample_floor":1,"min_samples":1},"rules":[""" +
                        """{"id":"rps","metric":"throughput_rps","operator":"gte","threshold":95,"scope":{"kind":"overall"},""" +
                        """"window_ids":["$window"]}]}""",
                )
            val shift = profile.firstRequestOffsetMillis()
            val declared =
                Files.writeString(
                    tempDir.resolve("gap-$index-careful.json"),
                    """{"schema_version":"load-stages.v1","stages":[{"id":"$window","role":"steady",""" +
                        """"from_offset_ms":${careful.first - shift},"to_offset_ms":${careful.last + 1 - shift}}]}""",
                )

            val byProposal = verdictWith(jtl, proposal, policy, "gp$index")
            val byCareful = verdictWith(jtl, declared, policy, "gc$index")

            assertEquals("PASS", byCareful, "${profile.name}:\n${detected.stderr}")
            assertEquals(byCareful, byProposal, "${profile.name}:\n${detected.stderr}")
        }
    }

    private fun verdictWith(
        jtl: Path,
        stages: Path,
        policy: Path,
        name: String,
    ): String {
        val result = run("analyze", jtl.toString(), "--policy", policy.toString(), "--stages", stages.toString(), "--data-dir", data(name))
        assertTrue(result.exitCode in 0..3, result.stderr)
        return Json
            .parseToJsonElement(result.stdout)
            .jsonObject
            .getValue("policy_verdict")
            .jsonPrimitive.content
    }

    @Test
    fun `a degraded tail prints the judged stretch and its facts`() {
        val jtl = StageProfiles.collapse(600, 20.0).writeJtl(tempDir.resolve("collapse.jtl"))

        val result = run("stages", "detect", jtl.toString())

        assertEquals(0, result.exitCode, result.stderr)
        val lines = result.stderr.lines()
        assertTrue(lines.first().startsWith("stage_proposal status=DETECTED method=rps-plateau-segmentation.v2 "), result.stderr)
        val stretch = lines.single { it.startsWith("stretch ") }
        assertTrue(
            Regex(
                "stretch kind=TAIL from_offset_ms=\\d+ to_offset_ms=\\d+ reference_id=steady samples=\\d+ failures=\\d+ " +
                    "error_ratio=0\\.\\d{3} mean_ms=\\d+\\.\\d{3} reference_samples=\\d+ reference_failures=0 " +
                    "reference_error_ratio=0\\.000 reference_mean_ms=100\\.000 verdict=DEGRADED",
            ).matches(stretch),
            stretch,
        )
        val warning = lines.single { it.startsWith("warning code=DEGRADED_TAIL_KEPT ") }
        assertTrue(
            Regex(
                "warning code=DEGRADED_TAIL_KEPT stage_id=steady at_offset_ms=\\d+ from_rps=\\S+ to_rps=\\S+ duration_ms=\\d+ " +
                    "error_ratio=0\\.\\d{3} mean_ms=\\d+\\.\\d{3} reference_id=steady reference_error_ratio=0\\.000 " +
                    "reference_mean_ms=100\\.000",
            ).matches(warning),
            warning,
        )
        assertTrue(lines.indexOf(stretch) < lines.indexOf(warning) && lines[lines.indexOf(stretch) - 1].startsWith("stage "))
        assertFalse(Regex("\"id\":\"ramp-down\"").containsMatchIn(result.stdout), result.stdout)
    }

    @Test
    fun `a stretch too small to judge says so on its warning`() {
        val profile =
            StageProfile(
                "too few samples",
                listOf(
                    io.ltverdict.core.RatePiece(600, 0.0, 0.5),
                    io.ltverdict.core.RatePiece(10_800, 0.5, 0.5),
                    io.ltverdict.core.RatePiece(360, 0.5, 0.0, 2000, 0.30),
                ),
                poissonSeed = 11L,
            )
        val result = run("stages", "detect", profile.writeJtl(tempDir.resolve("few.jtl")).toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(
            Regex("warning code=LONG_RAMP_DOWN .* duration_ms=\\d+ degradation=NOT_JUDGED samples=\\d+ reference_samples=\\d+\n")
                .containsMatchIn(result.stderr),
            result.stderr,
        )
        assertTrue(Regex("\nstretch kind=TAIL .* verdict=NOT_JUDGED\n").containsMatchIn(result.stderr), result.stderr)
    }

    @Test
    fun `row order does not change a judged proposal`() {
        val jtl = StageProfiles.degradedGap.writeJtl(tempDir.resolve("gap.jtl"))
        val lines = Files.readAllLines(jtl)
        val shuffled = tempDir.resolve("gap-shuffled.jtl")
        val rows = lines.drop(1).toMutableList()
        java.util.Collections.shuffle(rows, java.util.Random(7))
        Files.writeString(shuffled, (listOf(lines.first()) + rows).joinToString("\n", postfix = "\n"))

        val forward = run("stages", "detect", jtl.toString(), "--steady-steps", "all")
        val mixed = run("stages", "detect", shuffled.toString(), "--steady-steps", "all")

        assertEquals(0, forward.exitCode, forward.stderr)
        assertTrue(forward.stderr.contains("warning code=DEGRADED_GAP_KEPT "), forward.stderr)
        assertArrayEquals(forward.stdout.toByteArray(UTF_8), mixed.stdout.toByteArray(UTF_8))
        assertArrayEquals(forward.stderr.toByteArray(UTF_8), mixed.stderr.toByteArray(UTF_8))
    }

    private fun carefulStages(
        profile: StageProfile,
        windows: List<LongRange>,
        name: String,
    ): Path {
        val shift = profile.firstRequestOffsetMillis()
        val stages =
            windows.mapIndexed { index, window ->
                """{"id":"w$index","role":"steady","from_offset_ms":${window.first - shift},"to_offset_ms":${window.last + 1 - shift}}"""
            }
        return Files.writeString(
            tempDir.resolve("$name-careful.json"),
            """{"schema_version":"load-stages.v1","stages":[${stages.joinToString(",")}]}""",
        )
    }

    private fun verdict(
        jtl: Path,
        stages: Path,
        name: String,
    ): String {
        val policy =
            Files.writeString(
                tempDir.resolve("careful-policy.json"),
                """{"schema_version":"policy.v1","policy_id":"careful","defaults":{"sample_floor":1,"min_samples":1},"rules":[""" +
                    """{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}},""" +
                    """{"id":"errors","metric":"error_rate_ratio","operator":"lte","threshold":0.05,"scope":{"kind":"overall"}}]}""",
            )
        val result = run("analyze", jtl.toString(), "--policy", policy.toString(), "--stages", stages.toString(), "--data-dir", data(name))
        assertTrue(result.exitCode in 0..3, result.stderr)
        return Json
            .parseToJsonElement(result.stdout)
            .jsonObject
            .getValue("policy_verdict")
            .jsonPrimitive.content
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

    @Test
    fun `--like matches a non-canonical example and prints its canonical bytes`() {
        val result = run("stages", "detect", load.toString(), "--like", example.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals(exampleCanonical(), result.stdout)
        assertTrue(
            result.stderr.startsWith(
                "stage_proposal status=MATCHED method=rps-plateau-segmentation.v2 steady_steps=top confidence=HIGH calibrated=false\n",
            ),
            result.stderr,
        )
        assertTrue(
            result.stderr.contains(
                "\nlike stage_id=steady proposal_id=steady declared_from_ms=40000 declared_to_ms=100000 " +
                    "proposal_from_ms=40000 proposal_to_ms=100000 from_diff_ms=0 to_diff_ms=0 tolerance_ms=10000\n",
            ),
            result.stderr,
        )
        assertTrue(result.stderr.endsWith("\nnext: run ltv analyze <input> --stages <file>\n"), result.stderr)
    }

    @Test
    fun `--like keeps declared bytes within 2W on stdout and --out`() {
        val jtl = StageProfiles.f1.writeJtl(tempDir.resolve("f1.jtl"))
        val baseline = run("stages", "detect", jtl.toString())
        assertEquals(0, baseline.exitCode, baseline.stderr)
        val steady =
            Json
                .parseToJsonElement(baseline.stdout)
                .jsonObject
                .getValue("stages")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("role").jsonPrimitive.content == "steady" }
        val w = Regex("cell_ms=(\\d+)").find(baseline.stderr)!!.groupValues[1].toLong()
        val from =
            steady
                .getValue("from_offset_ms")
                .jsonPrimitive
                .content
                .toLong() + 2 * w
        val to =
            steady
                .getValue("to_offset_ms")
                .jsonPrimitive
                .content
                .toLong() - 2 * w
        val customDeclared =
            Files.writeString(
                tempDir.resolve("declared-2w.json"),
                """{"schema_version":"load-stages.v1","stages":[{"id":"steady-custom","role":"steady","from_offset_ms":$from,"to_offset_ms":$to}]}""",
            )

        val matchedStdout = run("stages", "detect", jtl.toString(), "--like", customDeclared.toString())
        assertEquals(0, matchedStdout.exitCode, matchedStdout.stderr)
        assertTrue(matchedStdout.stdout.contains("steady-custom"), matchedStdout.stdout)
        assertTrue(matchedStdout.stderr.contains("from_diff_ms=${2 * w} to_diff_ms=-${2 * w}"), matchedStdout.stderr)

        val out = tempDir.resolve("matched-out.json")
        val matchedOut = run("stages", "detect", jtl.toString(), "--like", customDeclared.toString(), "--out", out.toString())
        assertEquals(0, matchedOut.exitCode, matchedOut.stderr)
        assertEquals("", matchedOut.stdout)
        assertEquals(matchedStdout.stdout, Files.readString(out))
    }

    @Test
    fun `--like refuses large boundary shifts, shape mismatch, and NO_PLATEAU with exit 3 and no --out file`() {
        val jtl = StageProfiles.f1.writeJtl(tempDir.resolve("f1-refuse.jtl"))
        val baseline = run("stages", "detect", jtl.toString())
        val steady =
            Json
                .parseToJsonElement(baseline.stdout)
                .jsonObject
                .getValue("stages")
                .jsonArray
                .map { it.jsonObject }
                .single { it.getValue("role").jsonPrimitive.content == "steady" }
        val w = Regex("cell_ms=(\\d+)").find(baseline.stderr)!!.groupValues[1].toLong()
        val from =
            steady
                .getValue("from_offset_ms")
                .jsonPrimitive
                .content
                .toLong() + 10 * w
        val to =
            steady
                .getValue("to_offset_ms")
                .jsonPrimitive
                .content
                .toLong()
        val shifted =
            Files.writeString(
                tempDir.resolve("shifted-10w.json"),
                """{"schema_version":"load-stages.v1","stages":[{"id":"steady","role":"steady","from_offset_ms":$from,"to_offset_ms":$to}]}""",
            )
        val outShifted = tempDir.resolve("never-shifted.json")
        val refusedShift = run("stages", "detect", jtl.toString(), "--like", shifted.toString(), "--out", outShifted.toString())
        assertEquals(3, refusedShift.exitCode, refusedShift.stderr)
        assertEquals("", refusedShift.stdout)
        assertFalse(Files.exists(outShifted))
        assertTrue(refusedShift.stderr.contains("status=NOT_MATCHED") && refusedShift.stderr.contains("reason=LIKE_BOUNDARY_SHIFTED"))
        assertTrue(refusedShift.stderr.contains("\nstage id=steady "), refusedShift.stderr)
        assertTrue(refusedShift.stderr.contains("\ndetail "), refusedShift.stderr)

        val f2Jtl = StageProfiles.f2.writeJtl(tempDir.resolve("f2.jtl"))
        val outShape = tempDir.resolve("never-shape.json")
        val refusedShape = run("stages", "detect", f2Jtl.toString(), "--like", example.toString(), "--out", outShape.toString())
        assertEquals(3, refusedShape.exitCode, refusedShape.stderr)
        assertEquals("", refusedShape.stdout)
        assertFalse(Files.exists(outShape))
        assertTrue(refusedShape.stderr.contains("reason=LIKE_SHAPE_DIFFERS"), refusedShape.stderr)

        val f4Jtl = StageProfiles.f4.writeJtl(tempDir.resolve("f4.jtl"))
        val outNoPlateau = tempDir.resolve("never-f4.json")
        val refusedF4 = run("stages", "detect", f4Jtl.toString(), "--like", example.toString(), "--out", outNoPlateau.toString())
        assertEquals(3, refusedF4.exitCode, refusedF4.stderr)
        assertEquals("", refusedF4.stdout)
        assertFalse(Files.exists(outNoPlateau))
        assertTrue(refusedF4.stderr.contains("status=NOT_DETECTED") && refusedF4.stderr.contains("reason=NO_PLATEAU"))
    }

    @Test
    fun `--like validates arguments and pre-flight stages before reading input`() {
        val input = load.toString()
        assertEquals(64, run("stages", "detect", input, "--like").exitCode)
        assertEquals(64, run("stages", "detect", input, "--like", example.toString(), "--like", example.toString()).exitCode)

        val missing = run("stages", "detect", input, "--like", tempDir.resolve("missing.json").toString())
        assertEquals(4, missing.exitCode)
        assertTrue(missing.stderr.startsWith("INVALID_STAGES"), missing.stderr)

        val invalid = run("stages", "detect", input, "--like", "docs/contracts/stages/v1/examples/invalid/no-steady.json")
        assertEquals(4, invalid.exitCode)
        assertTrue(invalid.stderr.startsWith("STAGES_NO_STEADY /stages:"), invalid.stderr)
    }

    @Test
    fun `--like is deterministic regardless of JTL row order`() {
        val lines = Files.readAllLines(load)
        val reversed = tempDir.resolve("reversed-like.jtl")
        Files.writeString(reversed, (listOf(lines.first()) + lines.drop(1).reversed()).joinToString("\n", postfix = "\n"))

        val first = run("stages", "detect", load.toString(), "--like", example.toString())
        val second = run("stages", "detect", reversed.toString(), "--like", example.toString())

        assertEquals(0, first.exitCode, first.stderr)
        assertArrayEquals(first.stdout.toByteArray(UTF_8), second.stdout.toByteArray(UTF_8))
        assertArrayEquals(first.stderr.toByteArray(UTF_8), second.stderr.toByteArray(UTF_8))
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

package io.ltverdict.core

import io.ltverdict.sources.ExplicitWindow
import io.ltverdict.sources.SourceAcquisition
import io.ltverdict.sources.WindowedSourceRequest
import io.ltverdict.sources.analyzeWithSources
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/** ADR 0030, AC1-AC3, AC6-AC9, AC11a, AC12, AC13 on fixtures/stages/ramp-steady-rampdown.jtl (hand-calculated, see the plan). */
class StageWindowPolicyTest {
    @TempDir
    lateinit var tempDir: Path

    private val ramp = Files.readAllBytes(Path.of(RAMP_FIXTURE))

    @Test
    fun `the steady window reproduces the hand calculation of the plateau and passes where the whole run fails`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val policy = policy(rules(P95_LTE_250, RPS_GTE_9))

            val staged = service.analyze(AnalysisRequest(input, policy, stages = stages(RAMP_STEADY_DOWN)))
            val whole = service.analyze(AnalysisRequest(input, policy))

            assertEquals("PASS", field(staged, "policy_verdict"))
            val summary = single(staged, "window_metric_summary")
            assertEquals("steady", summary.str("window_id"))
            assertEquals("600", summary.str("sample_count"))
            assertEquals("0", summary.str("error_count"))
            assertEquals(listOf("149", "194", "198", "199"), listOf("p50", "p95", "p99", "max").map { summary.obj("latency_ms").str(it) })
            assertEquals("600000" to "60000", summary.obj("throughput_rps").let { it.str("numerator") to it.str("denominator") })
            assertEquals("[]", summary.getValue("resource_bindings").toString())
            val checks = evidence(staged).filter { it.str("type") == "policy_check" }
            assertEquals(listOf("PASS", "PASS"), checks.map { it.str("status") })
            assertEquals(listOf("steady", "steady"), checks.map { it.str("window_id") })
            assertEquals("194", checks.single { it.str("rule_id") == "p95" }.getValue("observed").toString())
            assertEquals(
                "600000" to "60000",
                checks.single { it.str("rule_id") == "rps" }.obj("observed").let { it.str("numerator") to it.str("denominator") },
            )

            assertEquals("FAIL", field(whole, "policy_verdict"))
            val overall = evidence(whole).single { it.str("type") == "metric_summary" && it.obj("scope").str("kind") == "overall" }
            assertEquals("821", overall.obj("latency_ms").str("p95"))
            assertEquals("720000" to "119800", overall.obj("throughput_rps").let { it.str("numerator") to it.str("denominator") })
        }

    @Test
    fun `the whole-run metric summary stays in the result of a staged run`() =
        withService { store, service ->
            val outcome = service.analyze(AnalysisRequest(accept(store, ramp), null, stages = stages(RAMP_STEADY_DOWN)))

            val overall = evidence(outcome).single { it.str("type") == "metric_summary" && it.obj("scope").str("kind") == "overall" }
            assertEquals("720", overall.str("sample_count"))
            assertEquals("NO_POLICY", field(outcome, "policy_verdict"))
        }

    @Test
    fun `a rule that the plateau violates fails with the plateau value and a finding bound to the window`() =
        withService { store, service ->
            val rule = p95Rule("p95", 190, windowIds = """["steady"]""")

            val outcome = service.analyze(AnalysisRequest(accept(store, ramp), policy(rules(rule)), stages = stages(RAMP_STEADY_DOWN)))

            assertEquals("FAIL", field(outcome, "policy_verdict"))
            assertEquals("194", evidence(outcome).single { it.str("type") == "policy_check" }.getValue("observed").toString())
            val finding = findings(outcome).single { it.str("type") == "policy_failure" }
            assertEquals("steady", finding.str("window_id"))
            assertEquals("p95", finding.str("rule_id"))
        }

    @Test
    fun `the window is half open and counts a sample by its start`() =
        withService { store, service ->
            val input = accept(store, ramp)

            fun count(declaration: String) =
                single(
                    service.analyze(AnalysisRequest(input, null, stages = stages(declaration))),
                    "window_metric_summary",
                ).str("sample_count")

            assertEquals("600", count(declaration(stage("steady", "steady", 40_000, 100_000))))
            // the sample that starts exactly at 40000 is in; the sample that starts exactly at 100000 is out
            assertEquals("599", count(declaration(stage("steady", "steady", 40_001, 100_000))))
            assertEquals("601", count(declaration(stage("steady", "steady", 40_000, 100_001))))
            assertEquals("599", count(declaration(stage("steady", "steady", 39_501, 99_900))))
        }

    @Test
    fun `a staged result has a stage binding and a window summary but no resource evidence`() =
        withService { store, service ->
            val outcome = service.analyze(AnalysisRequest(accept(store, ramp), null, stages = stages(RAMP_STEADY_DOWN)))
            val types = evidence(outcome).map { it.str("type") }

            listOf("resource_binding", "resource_summary", "resource_policy_check", "resource_threshold_violation").forEach {
                assertFalse(it in types, it)
            }
            // after the whole-run summaries (overall and GET /items: the base evidence), before the window checks; window metrics last
            assertEquals(
                listOf("metric_summary", "metric_summary", "stage_binding", "window_policy_summary", "window_metric_summary"),
                types,
            )
            val binding = single(outcome, "stage_binding")
            assertEquals("stage-binding", binding.str("id"))
            assertEquals("declared_stages", binding.str("mode"))
            assertEquals("STEADY_WINDOW", binding.str("verdict_scope"))
            assertEquals(T0.toString(), binding.str("run_from_epoch_ms"))
            assertEquals((T0 + 119_800).toString(), binding.str("run_to_epoch_ms"))
            assertEquals(listOf("steady"), binding.getValue("evaluated_window_ids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("60000", binding.str("evaluated_millis"))
            assertEquals("59800", binding.str("excluded_millis"))
            assertEquals(stages(RAMP_STEADY_DOWN).sha256, binding.str("declaration_sha256"))
            val listed = binding.getValue("stages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("ramp-up", "steady", "ramp-down"), listed.map { it.str("id") })
            assertEquals(listOf("excluded", "steady", "excluded"), listed.map { it.str("role") })
            assertEquals(listOf(T0, T0 + 40_000, T0 + 100_000), listed.map { it.str("from_epoch_ms").toLong() })
            assertEquals(listOf(T0 + 40_000, T0 + 100_000, T0 + 120_000), listed.map { it.str("to_epoch_ms").toLong() })
            assertEquals(listOf(null, "false", null), listed.map { it["clipped_to_run_end"]?.jsonPrimitive?.content })
            val summary = single(outcome, "window_policy_summary")
            assertEquals("steady", summary.str("window_id"))
            assertEquals("NO_POLICY", summary.str("resource_verdict"))
            assertEquals((T0 + 40_000).toString(), summary.str("from_epoch_ms"))
            assertEquals((T0 + 100_000).toString(), summary.str("to_epoch_ms"))
        }

    @Test
    fun `a steady stage past the run end is clipped and says so, and starting at the run end is outside the run`() =
        withService { store, service ->
            val input = accept(store, ramp)

            val clipped =
                service.analyze(
                    AnalysisRequest(input, null, stages = stages(declaration(stage("tail", "steady", 100_000, 130_000)))),
                )
            val binding = single(clipped, "stage_binding")
            val tail =
                binding
                    .getValue("stages")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("true", tail.str("clipped_to_run_end"))
            assertEquals((T0 + 119_800).toString(), tail.str("to_epoch_ms"))
            assertEquals("19800", binding.str("evaluated_millis"))
            assertEquals("100000", binding.str("excluded_millis"))
            assertEquals("40", single(clipped, "window_metric_summary").str("sample_count"))

            val outside =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, null, stages = stages(declaration(stage("late", "steady", 119_800, 130_000)))))
                }
            assertEquals("STAGE_OUTSIDE_RUN", outside.message)
        }

    @Test
    fun `a rule bound to an excluded stage is not found, a rule without window ids applies to every steady window`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val bound = p95Rule("ramp", 1000, windowIds = """["ramp-up"]""")

            val missing = service.analyze(AnalysisRequest(input, policy(rules(bound)), stages = stages(RAMP_STEADY_DOWN)))
            assertEquals("NO_VERDICT", field(missing, "policy_verdict"))
            val check = single(missing, "rule_window_check")
            assertEquals("ramp-up", check.str("window_id"))
            assertEquals("RULE_WINDOW_NOT_FOUND", check.str("reason_code"))
            assertTrue("RULE_WINDOW_NOT_FOUND" in coverageReasons(missing))

            val two =
                service.analyze(
                    AnalysisRequest(
                        input,
                        policy(
                            rules(
                                p95Rule("p95", 300),
                            ),
                        ),
                        stages = stages(TWO_STEADY),
                    ),
                )
            val summaries = evidence(two).filter { it.str("type") == "window_policy_summary" }
            assertEquals(listOf("steady-a", "steady-b"), summaries.map { it.str("window_id") })
            assertEquals(listOf("FAIL", "PASS"), summaries.map { it.str("verdict") })
            assertEquals("FAIL", field(two, "policy_verdict"))
            assertEquals(
                listOf("steady-a", "steady-b"),
                evidence(two).filter { it.str("type") == "window_metric_summary" }.map { it.str("window_id") },
            )
        }

    @Test
    fun `a platform sla rule cannot be checked without a snapshot, so every window is no verdict`() =
        withService { store, service ->
            val input = accept(store, ramp)

            val outcome = service.analyze(AnalysisRequest(input, policy(platformPolicy("sla")), stages = stages(RAMP_STEADY_DOWN)))

            // a validated policy always has a business rule (EMPTY_RULES), so there is no platform-only policy to test
            assertEquals("NO_VERDICT", field(outcome, "policy_verdict"))
            assertTrue("RESOURCE_SNAPSHOT_REQUIRED" in coverageReasons(outcome))
            val summary = single(outcome, "window_policy_summary")
            assertEquals("PASS", summary.str("business_verdict"))
            assertEquals("NO_VERDICT", summary.str("resource_verdict"))
            assertEquals("NO_VERDICT", summary.str("verdict"))
        }

    @Test
    fun `a platform rule without sla keeps the business verdict and only adds the information`() =
        withService { store, service ->
            val outcome =
                service.analyze(
                    AnalysisRequest(accept(store, ramp), policy(platformPolicy("diagnostic")), stages = stages(RAMP_STEADY_DOWN)),
                )

            assertEquals("PASS", field(outcome, "policy_verdict"))
            assertTrue("RESOURCE_SNAPSHOT_REQUIRED" in coverageReasons(outcome))
            assertEquals("NO_POLICY", single(outcome, "window_policy_summary").str("resource_verdict"))
        }

    @Test
    fun `a transaction rule is evaluated in the window, an empty window and a small window abstain`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val rule = p95Rule("items", 250, scope = """{"kind":"transaction","name":"GET /items"}""")

            val plateau = service.analyze(AnalysisRequest(input, policy(rules(rule)), stages = stages(RAMP_STEADY_DOWN)))
            val check = single(plateau, "policy_check")
            assertEquals("PASS", field(plateau, "policy_verdict"))
            assertEquals("transaction", check.obj("scope").str("kind"))
            assertEquals("194", check.getValue("observed").toString())

            val empty =
                service.analyze(
                    AnalysisRequest(input, policy(rules(rule)), stages = stages(declaration(stage("gap", "steady", 100_100, 100_400)))),
                )
            assertEquals("NO_VERDICT", field(empty, "policy_verdict"))
            assertTrue("BUSINESS_OBSERVATIONS_NOT_FOUND" in coverageReasons(empty))
            assertEquals("0", single(empty, "window_metric_summary").str("sample_count"))

            // the default sample floor (20) applies: the window holds three samples
            val noDefaults = """{"schema_version":"policy.v1","policy_id":"floor","rules":[$rule]}"""
            val small =
                service.analyze(
                    AnalysisRequest(input, policy(noDefaults), stages = stages(declaration(stage("short", "steady", 100_000, 101_200)))),
                )
            assertEquals("NO_VERDICT", field(small, "policy_verdict"))
            assertTrue("INSUFFICIENT_SAMPLES" in coverageReasons(small))
        }

    @Test
    fun `a degraded run keeps the stage binding and the window metrics but has no verdict`() =
        withService { store, service ->
            val complete = Files.readAllBytes(Path.of("fixtures/slice1/gatling/binary-3.13.5/simulation.log"))
            val input = accept(store, complete + byteArrayOf(2, 0))

            val outcome =
                service.analyze(
                    AnalysisRequest(input, policy(rules(PASSING_RULE)), stages = stages(declaration(stage("steady", "steady", 0, 100)))),
                )

            assertEquals("DEGRADED", field(outcome, "run_validity"))
            assertEquals("NO_VERDICT", field(outcome, "policy_verdict"))
            assertEquals(
                "steady",
                single(outcome, "stage_binding")
                    .getValue("evaluated_window_ids")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            assertEquals("steady", single(outcome, "window_metric_summary").str("window_id"))
            assertTrue(evidence(outcome).none { it.str("type") == "policy_check" })
        }

    @Test
    fun `an invalid input gets no stage binding but keeps the declaration next to the identity`() =
        withService { store, service ->
            val declared = stages(RAMP_STEADY_DOWN)
            val input = accept(store, (ramp.decodeToString().lineSequence().first() + "\nmalformed\n").encodeToByteArray())

            val outcome = service.analyze(AnalysisRequest(input, null, stages = declared))

            assertEquals("INVALID", field(outcome, "run_validity"))
            assertTrue(evidence(outcome).none { it.str("type") in setOf("stage_binding", "window_metric_summary") })
            val stored = outcome.analysisDirectory.resolve("load-stages.json")
            assertEquals(declared.sha256, sha256Hex(Files.readAllBytes(stored)))
            assertEquals(
                declared.sha256,
                Json
                    .parseToJsonElement(
                        Files.readString(outcome.analysisDirectory.resolve("identity.json")),
                    ).jsonObject
                    .str("load_stages_sha256"),
            )
        }

    @Test
    fun `the declaration is stored in canonical bytes and a repeated analysis returns the stored directory`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val declared = stages(RAMP_STEADY_DOWN)

            val first = service.analyze(AnalysisRequest(input, null, stages = declared))
            val again = service.analyze(AnalysisRequest(input, null, stages = stages(RAMP_STEADY_DOWN_RESPELLED)))
            val other =
                service.analyze(
                    AnalysisRequest(input, null, stages = stages(declaration(stage("steady", "steady", 40_000, 100_001)))),
                )
            val plain = service.analyze(AnalysisRequest(input, null))

            assertArrayEquals(declared.canonicalBytes(), Files.readAllBytes(first.analysisDirectory.resolve("load-stages.json")))
            assertEquals(declared.sha256, sha256Hex(Files.readAllBytes(first.analysisDirectory.resolve("load-stages.json"))))
            assertEquals(first.analysisId, again.analysisId)
            assertEquals(first.analysisDirectory, again.analysisDirectory)
            assertArrayEquals(first.canonicalResult, again.canonicalResult)
            assertNotEquals(first.analysisId, other.analysisId)
            assertNotEquals(first.analysisId, plain.analysisId)
            assertFalse(Files.exists(plain.analysisDirectory.resolve("load-stages.json")))
            val stored = store.readAnalysis(input.runId, first.analysisId)
            assertTrue(stored != null && stored.artifacts.any { it.path == "load-stages.json" })
            assertFalse("load-stages" in Files.readString(first.analysisDirectory.resolve("run.json")))
        }

    @Test
    fun `an early foreign sample moves the run start and with it every window`() =
        withService { store, service ->
            val setUp = "${T0 - 30_000},50,setUp,200,OK,setUp 1-1,text,true,,128,64,1,1,http://example.test/setup,25,0,5\n"
            val header = ramp.decodeToString().lineSequence().first()
            val shifted =
                (
                    header + "\n" + setUp +
                        ramp
                            .decodeToString()
                            .lineSequence()
                            .drop(1)
                            .joinToString("\n")
                ).encodeToByteArray()

            val outcome = service.analyze(AnalysisRequest(accept(store, shifted), null, stages = stages(RAMP_STEADY_DOWN)))

            val binding = single(outcome, "stage_binding")
            assertEquals((T0 - 30_000).toString(), binding.str("run_from_epoch_ms"))
            val steady =
                binding
                    .getValue("stages")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.str("id") == "steady" }
            assertEquals((T0 + 10_000).toString(), steady.str("from_epoch_ms"))
            assertEquals((T0 + 70_000).toString(), steady.str("to_epoch_ms"))
            // the declared plateau [40 s, 100 s) is now [10 s, 70 s) of the real timeline: 60 ramp samples and 300 plateau samples
            assertEquals("360", single(outcome, "window_metric_summary").str("sample_count"))
        }

    @Test
    fun `stages with a resource snapshot, a capacity plan or an online source are a conflict`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val declared = stages(RAMP_STEADY_DOWN)
            val snapshot =
                assertInstanceOf(
                    ResourceValidation.Valid::class.java,
                    validateResourceSnapshot(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))),
                    ),
                )
            val capacity =
                assertInstanceOf(
                    CapacityPlanValidation.Valid::class.java,
                    validateCapacityPlan(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/capacity/v1/examples/valid/rps.json"))),
                    ),
                )
            val online = WindowedSourceRequest("source-request.v2", listOf("prod"), ExplicitWindow(T0, T0 + 1_000, 15_000))

            fun message(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block).message

            // the conflicts are found before the snapshot is compared with the load input
            assertEquals(
                "STAGES_RESOURCES_CONFLICT",
                message {
                    service.analyze(AnalysisRequest(input, null, resources = snapshot, stages = declared))
                },
            )
            assertEquals(
                "STAGES_CAPACITY_CONFLICT",
                message { service.analyze(AnalysisRequest(input, null, resources = snapshot, capacity = capacity, stages = declared)) },
            )
            // ADR 0037, D7: stages no longer refuse an online request; with no source configured the source check answers
            assertEquals(
                "SOURCE_NOT_CONFIGURED",
                message { analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = online, stages = declared), null) },
            )
            assertEquals(
                "SOURCE_NOT_CONFIGURED",
                message { analyzeWithSources(service, AnalysisRequest(input, null, sourceRequest = online), null) },
            )
            // ADR 0037, D7: an online request with a snapshot stays a source input conflict, with or without stages
            assertEquals(
                "SOURCE_INPUT_CONFLICT",
                message {
                    analyzeWithSources(
                        service,
                        AnalysisRequest(input, null, sourceRequest = online, resources = snapshot, stages = declared),
                        null,
                    )
                },
            )
        }

    @Test
    fun `an offline source context without a snapshot is allowed with stages and its evidence follows the window metrics`() =
        withService { store, service ->
            val context =
                buildJsonObject {
                    put("id", "source-summary")
                    put("type", "source_summary")
                    put("status", "COMPLETE")
                }

            val outcome =
                service.analyze(
                    AnalysisRequest(
                        accept(store, ramp),
                        null,
                        sourceAcquisition = SourceAcquisition(null, context, emptyMap()),
                        stages = stages(RAMP_STEADY_DOWN),
                    ),
                )

            assertEquals(
                listOf(
                    "metric_summary",
                    "metric_summary",
                    "stage_binding",
                    "window_policy_summary",
                    "window_metric_summary",
                    "source_summary",
                ),
                evidence(outcome).map { it.str("type") },
            )
        }

    @Test
    fun `stages with a snapshot evaluate business rules on the stage and resource rules on its full cells`() =
        withService { store, service ->
            val outcome =
                service.analyze(
                    AnalysisRequest(
                        accept(store, ramp),
                        policy(PLATFORM_POLICY),
                        resources = snapshot(),
                        stages = stages(RAMP_STEADY_DOWN),
                    ),
                )

            assertEquals(
                listOf(
                    "metric_summary",
                    "metric_summary",
                    "stage_binding",
                    "resource_binding",
                    "resource_summary",
                    "resource_summary",
                    "resource_policy_check",
                    "resource_policy_check",
                    "policy_check",
                    "window_policy_summary",
                    "window_metric_summary",
                ),
                evidence(outcome).map { it.str("type") },
            )
            val window = single(outcome, "window_policy_summary")
            assertEquals("steady", window.str("window_id"))
            assertEquals((T0 + 40_000).toString(), window.str("from_epoch_ms"))
            assertEquals((T0 + 100_000).toString(), window.str("to_epoch_ms"))
            evidence(outcome).filter { it.str("type") in setOf("resource_summary", "resource_policy_check") }.forEach {
                assertEquals("steady", it.str("window_id"))
            }
            evidence(outcome).filter { it.str("type") == "resource_summary" }.forEach {
                assertEquals((T0 + 45_000).toString(), it.str("from_epoch_ms"))
                assertEquals((T0 + 90_000).toString(), it.str("to_epoch_ms"))
            }
            val steady =
                single(outcome, "stage_binding")
                    .getValue("stages")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.str("id") == "steady" }
            assertEquals((T0 + 45_000).toString(), steady.str("resource_from_epoch_ms"))
            assertEquals((T0 + 90_000).toString(), steady.str("resource_to_epoch_ms"))
            val binding = single(outcome, "resource_binding")
            assertEquals("declared_stages", binding.str("mode"))
            assertEquals((T0 + 45_000).toString(), binding.str("evaluation_from_epoch_ms"))
            assertEquals((T0 + 90_000).toString(), binding.str("evaluation_to_epoch_ms"))
            // business metrics come from the exact stage window, not from the grid cells (F7)
            val metrics = single(outcome, "window_metric_summary")
            assertEquals("600", metrics.str("sample_count"))
            assertEquals("600000" to "60000", metrics.obj("throughput_rps").let { it.str("numerator") to it.str("denominator") })
            assertEquals("194", single(outcome, "policy_check").getValue("observed").toString())
            // cells 2 and 3 are 0.9; the stage takes cells 3 to 5, so the two-cell CPU rule sees one high cell and passes
            val cpu = evidence(outcome).single { it.str("type") == "resource_policy_check" && it.str("platform_rule_id") == "cpu" }
            assertEquals("PASS", cpu.str("status"))
            assertEquals("3", cpu.str("expected_cells"))
            assertEquals("PASS", window.str("resource_verdict"))
            assertEquals("PASS", field(outcome, "policy_verdict"))
            assertEquals("COMPLETE", result(outcome).obj("analysis_coverage").str("status"))
        }

    @Test
    fun `a platform sla rule is checked on the steady window when a snapshot is given`() =
        withService { store, service ->
            val input = accept(store, ramp)

            fun analyze(values: String) =
                service.analyze(
                    AnalysisRequest(input, policy(PLATFORM_POLICY), resources = snapshot(cpu = values), stages = stages(RAMP_STEADY_DOWN)),
                )

            val passing = analyze("0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.2")
            val failing = analyze("0.2, 0.2, 0.2, 0.9, 0.9, 0.9, 0.2, 0.2")
            // the ramp-up cell [30 s, 45 s) and the ramp-down cells are outside the stage: they do not change the verdict
            val rampsOnly = analyze("0.9, 0.9, 0.9, 0.2, 0.2, 0.2, 0.9, 0.9")

            assertEquals("PASS", single(passing, "window_policy_summary").str("resource_verdict"))
            assertEquals("FAIL", single(failing, "window_policy_summary").str("resource_verdict"))
            assertEquals("FAIL", field(failing, "policy_verdict"))
            assertEquals("PASS", single(rampsOnly, "window_policy_summary").str("resource_verdict"))
            listOf(passing, failing, rampsOnly).forEach { assertFalse("RESOURCE_SNAPSHOT_REQUIRED" in coverageReasons(it)) }
        }

    @Test
    fun `a platform rule bound to an excluded stage is not found`() =
        withService { store, service ->
            val bound = PLATFORM_POLICY.replace("\"id\": \"cpu\",", "\"id\": \"cpu\", \"window_ids\": [\"ramp-up\"],")

            val outcome =
                service.analyze(
                    AnalysisRequest(accept(store, ramp), policy(bound), resources = snapshot(), stages = stages(RAMP_STEADY_DOWN)),
                )

            assertEquals("NO_VERDICT", field(outcome, "policy_verdict"))
            val check = single(outcome, "rule_window_check")
            assertEquals("cpu", check.str("rule_id"))
            assertEquals("ramp-up", check.str("window_id"))
            assertEquals("RULE_WINDOW_NOT_FOUND", check.str("reason_code"))
            assertTrue(evidence(outcome).none { it.str("type") == "resource_policy_check" && it.str("platform_rule_id") == "cpu" })
        }

    @Test
    fun `a snapshot that starts inside the steady stage is partial`() =
        withService { store, service ->
            val late = snapshot(start = T0 + 60_000, points = 4, cpu = "0.2, 0.2, 0.2, 0.2", replicas = "0, 0, 0, 0")

            val outcome =
                service.analyze(
                    AnalysisRequest(accept(store, ramp), policy(rules(P95_LTE_250)), resources = late, stages = stages(RAMP_STEADY_DOWN)),
                )

            assertEquals(listOf("STAGE_WINDOW_SNAPSHOT_PARTIAL"), coverageReasons(outcome))
            assertEquals("INCOMPLETE", result(outcome).obj("analysis_coverage").str("status"))
            assertEquals("PASS", field(outcome, "policy_verdict"))
            val steady =
                single(outcome, "stage_binding")
                    .getValue("stages")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.str("id") == "steady" }
            assertEquals((T0 + 60_000).toString(), steady.str("resource_from_epoch_ms"))
            assertEquals((T0 + 90_000).toString(), steady.str("resource_to_epoch_ms"))
        }

    @Test
    fun `a steady stage without a full cell of the snapshot grid is refused`() =
        withService { store, service ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(
                        AnalysisRequest(
                            accept(store, ramp),
                            null,
                            resources = snapshot(),
                            stages = stages(declaration(stage("short", "steady", 16_000, 29_000))),
                        ),
                    )
                }

            assertEquals("STAGE_WINDOW_NO_FULL_CELLS", failure.message)
        }

    @Test
    fun `an invalid input with stages and a snapshot has no stage binding but keeps both files`() =
        withService { store, service ->
            val input = accept(store, (ramp.decodeToString().lineSequence().first() + "\nmalformed\n").encodeToByteArray())
            val declared = stages(RAMP_STEADY_DOWN)
            val resources = snapshot(loadHash = input.sha256)

            val outcome = service.analyze(AnalysisRequest(input, null, resources = resources, stages = declared))

            assertEquals("INVALID", field(outcome, "run_validity"))
            assertTrue(
                evidence(outcome).none {
                    it.str("type") in setOf("stage_binding", "resource_binding", "resource_summary", "window_metric_summary")
                },
            )
            assertEquals(declared.sha256, sha256Hex(Files.readAllBytes(outcome.analysisDirectory.resolve("load-stages.json"))))
            assertArrayEquals(resources.rawBytes(), Files.readAllBytes(outcome.analysisDirectory.resolve("resource-snapshot.json")))
        }

    @Test
    fun `the same inputs with stages and a snapshot return the stored analysis`() =
        withService { store, service ->
            val input = accept(store, ramp)

            val first =
                service.analyze(
                    AnalysisRequest(input, policy(PLATFORM_POLICY), resources = snapshot(), stages = stages(RAMP_STEADY_DOWN)),
                )
            val again =
                service.analyze(
                    AnalysisRequest(input, policy(PLATFORM_POLICY), resources = snapshot(), stages = stages(RAMP_STEADY_DOWN)),
                )
            val snapshotOnly = service.analyze(AnalysisRequest(input, policy(PLATFORM_POLICY), resources = snapshot()))

            assertEquals(first.analysisId, again.analysisId)
            assertEquals(first.analysisDirectory, again.analysisDirectory)
            assertArrayEquals(first.canonicalResult, again.canonicalResult)
            assertNotEquals(first.analysisId, snapshotOnly.analysisId)
            assertFalse(Files.exists(snapshotOnly.analysisDirectory.resolve("load-stages.json")))
        }

    @Test
    fun `stages with a capacity plan, a snapshot with windows, a correlation plan or a trend plan are refused`() =
        withService { store, service ->
            val input = accept(store, ramp)
            val declared = stages(RAMP_STEADY_DOWN)
            val windowed =
                assertInstanceOf(
                    ResourceValidation.Valid::class.java,
                    validateResourceSnapshot(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))),
                    ),
                )
            val capacity =
                assertInstanceOf(
                    CapacityPlanValidation.Valid::class.java,
                    validateCapacityPlan(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/capacity/v1/examples/valid/rps.json"))),
                    ),
                )
            val correlation =
                assertInstanceOf(
                    DiagnosticValidation.Valid::class.java,
                    validateDiagnosticPlan(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/diagnostics/v1/examples/valid/basic.json"))),
                    ),
                )
            val trend =
                assertInstanceOf(
                    TrendPlanValidation.Valid::class.java,
                    validateTrendPlan(
                        ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/trend/v1/examples/valid/basic.json"))),
                    ),
                )

            fun message(request: AnalysisRequest) = assertThrows(IllegalArgumentException::class.java) { service.analyze(request) }.message

            // order: capacity, snapshot with windows, correlation, trend; all before the snapshot is compared with the load input
            assertEquals(
                "STAGES_CAPACITY_CONFLICT",
                message(
                    AnalysisRequest(input, null, resources = windowed, capacity = capacity, diagnostics = correlation, stages = declared),
                ),
            )
            assertEquals(
                "STAGES_RESOURCES_CONFLICT",
                message(AnalysisRequest(input, null, resources = windowed, diagnostics = correlation, trend = trend, stages = declared)),
            )
            assertEquals(
                "STAGES_CORRELATION_CONFLICT",
                message(AnalysisRequest(input, null, resources = snapshot(), diagnostics = correlation, trend = trend, stages = declared)),
            )
            assertEquals(
                "STAGES_TREND_CONFLICT",
                message(AnalysisRequest(input, null, resources = snapshot(), trend = trend, stages = declared)),
            )
            // without a snapshot the stage codes come before DIAGNOSTIC_RESOURCE_REQUIRED and TREND_RESOURCE_REQUIRED
            assertEquals("STAGES_CORRELATION_CONFLICT", message(AnalysisRequest(input, null, diagnostics = correlation, stages = declared)))
            assertEquals("STAGES_TREND_CONFLICT", message(AnalysisRequest(input, null, trend = trend, stages = declared)))
            assertEquals("DIAGNOSTIC_RESOURCE_REQUIRED", message(AnalysisRequest(input, null, diagnostics = correlation)))
            assertEquals("TREND_RESOURCE_REQUIRED", message(AnalysisRequest(input, null, trend = trend)))
        }

    @Test
    fun `incidents of the combination reference stage windows`() =
        withService { store, service ->
            val outcome =
                service.analyze(
                    AnalysisRequest(
                        accept(store, ramp),
                        // p95 on the plateau is 194: a business failure in the window next to the resource failure
                        policy(PLATFORM_POLICY.replace("\"threshold\": 250", "\"threshold\": 190")),
                        resources = snapshot(cpu = "0.2, 0.2, 0.2, 0.9, 0.9, 0.9, 0.2, 0.2"),
                        stages = stages(RAMP_STEADY_DOWN),
                    ),
                )

            val incidents = synthesizeIncidents(io.ltverdict.ingest.RunValidity.VALID, findings(outcome), evidence(outcome))
            val items = incidents.getValue("items").jsonArray.map { it.jsonObject }
            assertEquals(setOf("overall", "entity"), items.map { it.obj("scope").str("kind") }.toSet())
            items.forEach { item ->
                assertEquals("steady", item.str("window_id"))
                val negatives = item.getValue("negative_evidence").jsonArray.map { it.jsonObject.str("check") }
                assertFalse("RESOURCE_DATA_NOT_PROVIDED" in negatives)
            }
            // a business atom takes the exact stage bounds of window_policy_summary; a resource atom takes the cells it violated
            val window = single(outcome, "window_policy_summary")
            val business = items.single { it.obj("scope").str("kind") == "overall" }.obj("interval")
            assertEquals(
                window.str("from_epoch_ms") to window.str("to_epoch_ms"),
                business.str("from_epoch_ms") to business.str("to_epoch_ms"),
            )
            val resource = items.single { it.obj("scope").str("kind") == "entity" }.obj("interval")
            assertEquals((T0 + 45_000).toString() to (T0 + 90_000).toString(), resource.str("from_epoch_ms") to resource.str("to_epoch_ms"))
        }

    private fun snapshot(
        loadHash: String? = null,
        start: Long = T0,
        points: Int = 8,
        cpu: String = "0.2, 0.2, 0.9, 0.9, 0.2, 0.2, 0.2, 0.2",
        replicas: String = "0, 0, 0, 0, 0, 0, 0, 0",
    ): ResourceValidation.Valid {
        val text =
            SNAPSHOT_15S
                .replace("\"start_epoch_ms\": $T0,", "\"start_epoch_ms\": $start,")
                .replace("\"point_count\": 8,", "\"point_count\": $points,")
                .replace("[0.2, 0.2, 0.9, 0.9, 0.2, 0.2, 0.2, 0.2]", "[$cpu]")
                .replace("[0, 0, 0, 0, 0, 0, 0, 0]", "[$replicas]")
                .let { if (loadHash == null) it else it.replace(RAMP_SHA256, loadHash) }
        return assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(text.encodeToByteArray())),
        )
    }

    private fun withService(block: (RunBundleStore, AnalysisService) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()))
        }
    }

    private fun accept(
        store: RunBundleStore,
        bytes: ByteArray,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(bytes), "input.jtl")

    private fun stages(json: String) =
        assertInstanceOf(LoadStagesValidation.Valid::class.java, validateLoadStages(ByteArrayInputStream(json.encodeToByteArray())))

    private fun policy(json: String) =
        assertInstanceOf(PolicyValidation.Valid::class.java, validatePolicy(ByteArrayInputStream(json.encodeToByteArray())))

    private fun rules(vararg rule: String) =
        """{"schema_version":"policy.v1","policy_id":"stages","defaults":{"sample_floor":1,"min_samples":1},"rules":[${rule.joinToString(
            ",",
        )}]}"""

    private fun platformPolicy(effect: String): String {
        val coverage = if (effect == "sla") """"platform_coverage":{"signal":"unavailable"},""" else ""
        return """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
            """"rules":[$PASSING_RULE],"platform_services":["orders"],$coverage"platform_rules":[""" +
            """{"id":"cpu","signal":"cpu_ratio","scope":{"kind":"all_services"},"operator":"gt","threshold":0.4,"unit":"ratio",""" +
            """"aggregation":"interval_mean","min_consecutive_cells":2,"effect":"$effect"},""" +
            """{"id":"cover","signal":"unavailable","scope":{"kind":"all_services"},"operator":"gt","threshold":0,"unit":"count",""" +
            """"aggregation":"interval_max","min_consecutive_cells":1,"effect":"$effect"}]}"""
    }

    private fun result(outcome: AnalysisOutcome) = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject

    private fun field(
        outcome: AnalysisOutcome,
        name: String,
    ) = result(outcome).str(name)

    private fun evidence(outcome: AnalysisOutcome) = result(outcome).getValue("evidence").jsonArray.map { it.jsonObject }

    private fun findings(outcome: AnalysisOutcome) = result(outcome).getValue("findings").jsonArray.map { it.jsonObject }

    private fun single(
        outcome: AnalysisOutcome,
        type: String,
    ) = evidence(outcome).single { it.str("type") == type }

    private fun coverageReasons(outcome: AnalysisOutcome) =
        result(outcome)
            .obj("analysis_coverage")
            .getValue("reasons")
            .jsonArray
            .map { it.jsonPrimitive.content }

    private fun JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.obj(name: String) = getValue(name).jsonObject
}

private const val RAMP_FIXTURE = "fixtures/stages/ramp-steady-rampdown.jtl"
private const val T0 = 1_767_225_600_000L
private val P95_LTE_250 = p95Rule("p95", 250)
private const val RPS_GTE_9 = """{"id":"rps","metric":"throughput_rps","operator":"gte","threshold":9,"scope":{"kind":"overall"}}"""
private val PASSING_RULE = p95Rule("p95", 100_000)

private fun p95Rule(
    id: String,
    threshold: Int,
    windowIds: String? = null,
    scope: String = """{"kind":"overall"}""",
) = """{"id":"$id","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,""" +
    (windowIds?.let { """"window_ids":$it,""" } ?: "") +
    """"scope":$scope}"""

private fun stage(
    id: String,
    role: String,
    from: Int,
    to: Int,
) = """{"id":"$id","role":"$role","from_offset_ms":$from,"to_offset_ms":$to}"""

private fun declaration(vararg stages: String) = """{"schema_version":"load-stages.v1","stages":[${stages.joinToString(",")}]}"""

private val RAMP_STEADY_DOWN = Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/ramp-steady-down.json"))
private val TWO_STEADY = Files.readString(Path.of("docs/contracts/stages/v1/examples/valid/two-steady.json"))
private val SNAPSHOT_15S = Files.readString(Path.of("fixtures/stages/cross-version/snapshot-15s.json"))
private val PLATFORM_POLICY = Files.readString(Path.of("fixtures/stages/cross-version/policy-platform.json"))
private const val RAMP_SHA256 = "ec01e6fe71409fc8ad3654e16f759bf7c7ad0661aad40802f45ecb0f894fd3e4"
private val RAMP_STEADY_DOWN_RESPELLED =
    declaration(
        stage("ramp-down", "excluded", 100_000, 120_000),
        stage("steady", "steady", 40_000, 100_000),
        stage("ramp-up", "excluded", 0, 40_000),
    )

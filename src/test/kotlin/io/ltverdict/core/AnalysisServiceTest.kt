package io.ltverdict.core

import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.sources.SourceAcquisition
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
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
import java.security.MessageDigest
import java.util.HexFormat

class AnalysisServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquisition artifacts and status are immutable and distinct from offline replay`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "online.jtl")
            val resource = resources(resourceJson(input.sha256, "online", "0.8").encodeToByteArray())
            val evidence =
                buildJsonObject {
                    put("id", "source-summary")
                    put("type", "source_summary")
                    put("status", "PARTIAL")
                }
            val artifacts =
                mapOf("source-acquisition.json" to canonicalJson(evidence), "source-response-0.json" to "{}".encodeToByteArray())
            val acquisition = SourceAcquisition(resource, evidence, artifacts)
            val request = AnalysisRequest(input, passPolicy(), resources = resource, sourceAcquisition = acquisition)
            val online = service.analyze(request)
            val stored = store.readAnalysis(input.runId, online.analysisId)!!
            assertTrue(stored.artifacts.map { it.path }.containsAll(artifacts.keys))
            artifacts.forEach { (name, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(stored.path.resolve(name))) }
            val onlineResult = Json.parseToJsonElement(online.canonicalResult.decodeToString()).jsonObject
            assertTrue(onlineResult.getValue("evidence").jsonArray.contains(evidence))
            assertArrayEquals(online.canonicalResult, service.analyze(request).canonicalResult)
            val replay = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource))
            assertNotEquals(online.analysisId, replay.analysisId)
            assertEquals(result(online, "policy_verdict"), result(replay, "policy_verdict"))
            assertFalse(Files.exists(replay.analysisDirectory.resolve("source-acquisition.json")))
        }

    @Test
    fun `standard analysis uses the final two-pass window and commits the complete bundle`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "out-of-order.jtl")
            val progress = mutableListOf<Long>()

            val outcome = service.analyze(AnalysisRequest(input, passPolicy()), progress::add)

            assertEquals(input.runId, outcome.runId)
            assertTrue(Regex("[0-9a-f]{64}").matches(outcome.analysisId))
            assertEquals(outcome.analysisId, outcome.analysisDirectory.fileName.toString())
            assertArrayEquals(outcome.canonicalResult, Files.readAllBytes(outcome.analysisDirectory.resolve("analysis-result.json")))
            assertEquals("VALID", result(outcome, "run_validity"))
            assertEquals("PASS", result(outcome, "policy_verdict"))
            assertMonotonicProgress(progress, input.sizeBytes)

            assertEquals(
                expectedRun(input, "2026-01-01T00:00:00Z", "2026-01-01T00:00:01.020Z"),
                Files.readString(outcome.analysisDirectory.resolve("run.json")),
            )
            val buckets = Files.readAllLines(outcome.analysisDirectory.resolve("normalized-1s.ndjson"))
            assertEquals(
                listOf("0", "1000"),
                buckets.map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject
                        .getValue("bucket_start_ms")
                        .jsonPrimitive.content
                },
            )

            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            assertEquals(COMPLETE_ARTIFACTS, stored.artifacts.map { it.path }.toSet())
            assertTrue(Files.isRegularFile(stored.path.resolve("manifest.json")))
            assertTrue(stored.artifacts.all { it.sizeBytes > 0 && Regex("[0-9a-f]{64}").matches(it.sha256) })

            val identity = Json.parseToJsonElement(Files.readString(stored.path.resolve("identity.json"))).jsonObject
            assertEquals(
                "lt-verdict",
                identity
                    .getValue("engine")
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "1",
                identity
                    .getValue("engine")
                    .jsonObject
                    .getValue("version")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "86400000",
                identity
                    .getValue("histogram")
                    .jsonObject
                    .getValue("highest_trackable_value_ms")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `identical request is byte stable and a new policy cannot change the old analysis`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "input.jtl")
            val first = service.analyze(AnalysisRequest(input, passPolicy()))
            val firstSnapshot = snapshot(first)

            val repeated = service.analyze(AnalysisRequest(input, passPolicy()))
            assertEquals(first.analysisId, repeated.analysisId)
            assertEquals(first.analysisDirectory, repeated.analysisDirectory)
            assertArrayEquals(first.canonicalResult, repeated.canonicalResult)
            assertSnapshotEquals(firstSnapshot, snapshot(repeated))

            val changed = service.analyze(AnalysisRequest(input, policyFromFile(FAIL_POLICY)))
            assertNotEquals(first.analysisId, changed.analysisId)
            assertEquals("FAIL", result(changed, "policy_verdict"))
            assertSnapshotEquals(firstSnapshot, snapshot(first))
            assertTrue(store.readAnalysis(input.runId, first.analysisId) != null)
            assertTrue(store.readAnalysis(input.runId, changed.analysisId) != null)
        }

    @Test
    fun `degraded binary keeps complete samples but cannot produce a verdict`() =
        withService { store, service ->
            val complete = Files.readAllBytes(Path.of(GATLING_BINARY_FIXTURE))
            val input = accept(store, complete + byteArrayOf(2, 0), "simulation.log")

            val outcome = service.analyze(AnalysisRequest(input, passPolicy()))

            assertEquals("DEGRADED", result(outcome, "run_validity"))
            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            assertEquals(
                expectedRun(input, "2026-08-31T21:36:13.295Z", "2026-08-31T21:36:13.436Z"),
                Files.readString(outcome.analysisDirectory.resolve("run.json")),
            )
            assertEquals(
                COMPLETE_ARTIFACTS,
                store
                    .readAnalysis(input.runId, outcome.analysisId)!!
                    .artifacts
                    .map { it.path }
                    .toSet(),
            )
        }

    @Test
    fun `invalid input commits only identity and no-verdict result`() =
        withService { store, service ->
            val header = OUT_OF_ORDER_CSV.lineSequence().first()
            val input = accept(store, "$header\nmalformed\n".encodeToByteArray(), "invalid.jtl")

            val outcome = service.analyze(AnalysisRequest(input, null))

            assertEquals("INVALID", result(outcome, "run_validity"))
            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            assertEquals(setOf("analysis-result.json", "identity.json"), stored.artifacts.map { it.path }.toSet())
            assertFalse(Files.exists(stored.path.resolve("run.json")))
            assertFalse(Files.exists(stored.path.resolve("normalized-1s.ndjson")))
        }

    @Test
    fun `metric bucket limit commits invalid resource-limit result`() =
        withService(EngineConfig(metrics = MetricsConfig(maxOneSecondBuckets = 1))) { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "bucket-limit.jtl")

            val outcome = service.analyze(AnalysisRequest(input, passPolicy()))
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject

            assertEquals("INVALID", result.getValue("run_validity").jsonPrimitive.content)
            assertEquals("NO_VERDICT", result.getValue("policy_verdict").jsonPrimitive.content)
            assertEquals(
                listOf("RESOURCE_LIMIT_EXCEEDED"),
                result
                    .getValue("analysis_coverage")
                    .jsonObject
                    .getValue("reasons")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertEquals(
                setOf("analysis-result.json", "identity.json"),
                store
                    .readAnalysis(input.runId, outcome.analysisId)!!
                    .artifacts
                    .map { it.path }
                    .toSet(),
            )
        }

    @Test
    fun `capacity mode without a plan is rejected before an analysis directory exists`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "capacity.jtl")
            val analyses =
                input.path.parent.parent
                    .resolve("analyses")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, null, AnalysisMode.CAPACITY_STEP))
                }

            assertEquals("CAPACITY_PLAN_REQUIRED", failure.message)
            assertFalse(Files.exists(analyses))
        }

    @Test
    fun `capacity plan is bound and stored with a stable canonical result`() =
        withService { store, service ->
            val input = accept(store, capacityCsv().encodeToByteArray(), "capacity.jtl")
            val resource = resources(capacityResourceJson(input.sha256).encodeToByteArray())
            val rawPlan = capacityPlanJson(input.sha256, resource.semanticSha256).encodeToByteArray()
            val plan = assertInstanceOf(CapacityPlanValidation.Valid::class.java, validateCapacityPlan(ByteArrayInputStream(rawPlan)))
            val analyses =
                input.path.parent.parent
                    .resolve("analyses")

            val resourceRequired =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, passPolicy(), capacity = plan))
                }
            val bindingFailure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(
                        AnalysisRequest(
                            input,
                            passPolicy(),
                            resources = resources(capacityResourceJson(input.sha256).replace("0.5", "0.6").encodeToByteArray()),
                            capacity = plan,
                        ),
                    )
                }
            assertEquals("CAPACITY_RESOURCE_REQUIRED", resourceRequired.message)
            assertEquals("CAPACITY_SNAPSHOT_MISMATCH", bindingFailure.message)
            assertFalse(Files.exists(analyses))

            val contradictory =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, passPolicy(), AnalysisMode.STANDARD, resource, capacity = plan))
                }
            assertEquals("CAPACITY_MODE_CONFLICT", contradictory.message)
            assertFalse(Files.exists(analyses))

            val outcome = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, capacity = plan))
            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject

            assertEquals("capacity_step", result.getValue("analysis_mode").jsonPrimitive.content)
            assertEquals(
                "INDETERMINATE",
                result
                    .getValue("capacity_summary")
                    .jsonObject
                    .getValue("bound_type")
                    .jsonPrimitive
                    .content,
            )
            assertArrayEquals(rawPlan, Files.readAllBytes(outcome.analysisDirectory.resolve(CAPACITY_PLAN_FILE)))
            assertArrayEquals(
                canonicalJson(result.getValue("capacity_summary").jsonObject),
                Files.readAllBytes(outcome.analysisDirectory.resolve(CAPACITY_FILE)),
            )
            assertEquals(
                COMPLETE_ARTIFACTS + RESOURCE_FILE + CAPACITY_PLAN_FILE + CAPACITY_FILE,
                stored.artifacts.map { it.path }.toSet(),
            )
            assertTrue(
                Json
                    .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json")))
                    .jsonObject
                    .containsKey("capacity_plan_sha256"),
            )

            val repeated = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, capacity = plan))
            assertEquals(outcome.analysisId, repeated.analysisId)
            assertSnapshotEquals(snapshot(outcome), snapshot(repeated))
            listOf(CAPACITY_PLAN_FILE, CAPACITY_FILE, RESOURCE_FILE).forEach { name ->
                assertArrayEquals(
                    Files.readAllBytes(outcome.analysisDirectory.resolve(name)),
                    Files.readAllBytes(repeated.analysisDirectory.resolve(name)),
                    name,
                )
            }
        }

    @Test
    fun `invalid capacity load preserves raw plan and indeterminate metrics`() =
        withService { store, service ->
            val input = accept(store, "${OUT_OF_ORDER_CSV.lineSequence().first()}\nmalformed\n".encodeToByteArray(), "invalid-capacity.jtl")
            val resource = resources(capacityResourceJson(input.sha256).encodeToByteArray())
            val rawPlan = capacityPlanJson(input.sha256, resource.semanticSha256).encodeToByteArray()
            val plan = assertInstanceOf(CapacityPlanValidation.Valid::class.java, validateCapacityPlan(ByteArrayInputStream(rawPlan)))

            val outcome = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, capacity = plan))
            val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val stage =
                result
                    .getValue("capacity_summary")
                    .jsonObject
                    .getValue("stages")
                    .jsonArray
                    .single()
                    .jsonObject

            assertEquals("INVALID", result.getValue("run_validity").jsonPrimitive.content)
            assertEquals("NO_VERDICT", result.getValue("policy_verdict").jsonPrimitive.content)
            assertFalse(result.getValue("capacity_summary").toString().contains("CAPACITY_SLA_MISSING"))
            assertEquals(
                "INDETERMINATE",
                result
                    .getValue("capacity_summary")
                    .jsonObject
                    .getValue("bound_type")
                    .jsonPrimitive
                    .content,
            )
            assertEquals("null", stage.getValue("achieved").toString())
            assertEquals("0", stage.getValue("complete_bins").jsonPrimitive.content)
            assertArrayEquals(rawPlan, Files.readAllBytes(outcome.analysisDirectory.resolve(CAPACITY_PLAN_FILE)))
            assertArrayEquals(
                canonicalJson(result.getValue("capacity_summary").jsonObject),
                Files.readAllBytes(outcome.analysisDirectory.resolve(CAPACITY_FILE)),
            )
        }

    @Test
    fun `enriched analysis stores immutable raw snapshot and shares one SLA window`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "resources.jtl")
            val firstRaw = resourceJson(input.sha256, "first", "0.8").encodeToByteArray()
            val first = service.analyze(AnalysisRequest(input, passPolicy(), resources = resources(firstRaw)))
            val firstResult = Json.parseToJsonElement(first.canonicalResult.decodeToString()).jsonObject
            val window =
                firstResult
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.getValue("type").jsonPrimitive.content == "window_policy_summary" }

            assertEquals("FAIL", firstResult.getValue("policy_verdict").jsonPrimitive.content)
            assertEquals("PASS", window.getValue("business_verdict").jsonPrimitive.content)
            assertEquals("FAIL", window.getValue("resource_verdict").jsonPrimitive.content)
            assertEquals("FAIL", window.getValue("verdict").jsonPrimitive.content)
            assertEquals(
                1,
                firstResult
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .count {
                        it["type"]?.jsonPrimitive?.content == "metric_summary" &&
                            it["scope"]
                                ?.jsonObject
                                ?.get("kind")
                                ?.jsonPrimitive
                                ?.content == "overall"
                    },
            )
            val artifacts = storedArtifacts(store, input, first)
            assertEquals(COMPLETE_ARTIFACTS + RESOURCE_FILE, artifacts)
            assertArrayEquals(firstRaw, Files.readAllBytes(first.analysisDirectory.resolve(RESOURCE_FILE)))

            val identity = Json.parseToJsonElement(Files.readString(first.analysisDirectory.resolve("identity.json"))).jsonObject
            assertTrue(identity.containsKey("resource_snapshot_sha256"))
            assertTrue(identity.containsKey("resource_config_sha256"))
            assertEquals(
                "10000",
                identity
                    .getValue("limits")
                    .jsonObject
                    .getValue("resource_window_histograms_max")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "10000",
                identity
                    .getValue("limits")
                    .jsonObject
                    .getValue("resource_findings_max")
                    .jsonPrimitive.content,
            )

            val provenanceOnly =
                service.analyze(
                    AnalysisRequest(
                        input,
                        passPolicy(),
                        resources = resources(resourceJson(input.sha256, "second", "0.8").encodeToByteArray()),
                    ),
                )
            assertEquals(first.analysisId, provenanceOnly.analysisId)
            assertArrayEquals(firstRaw, Files.readAllBytes(first.analysisDirectory.resolve(RESOURCE_FILE)))

            val changed =
                service.analyze(
                    AnalysisRequest(
                        input,
                        passPolicy(),
                        resources = resources(resourceJson(input.sha256, "third", "1.0").encodeToByteArray()),
                    ),
                )
            assertNotEquals(first.analysisId, changed.analysisId)
            assertEquals("PASS", result(changed, "policy_verdict"))
            assertArrayEquals(firstRaw, Files.readAllBytes(first.analysisDirectory.resolve(RESOURCE_FILE)))
        }

    @Test
    fun `resource binding failures publish no analysis bundle`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "binding.jtl")
            val analyses =
                input.path.parent.parent
                    .resolve("analyses")
            val mismatched = resources(resourceJson("0".repeat(64), "mismatch", "0.8").encodeToByteArray())
            val outside =
                resources(
                    resourceJson(input.sha256, "outside", "0.8", pointCount = 2, toEpochMillis = 1_767_225_602_000).encodeToByteArray(),
                )

            val hashFailure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, null, resources = mismatched))
                }
            val windowFailure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, null, resources = outside))
                }

            assertEquals("RESOURCE_LOAD_HASH_MISMATCH", hashFailure.message)
            assertEquals("RESOURCE_WINDOW_OUTSIDE_RUN", windowFailure.message)
            assertFalse(Files.exists(analyses))
        }

    @Test
    fun `first pass bounds transaction candidate identity bytes before retaining them`() {
        val header = OUT_OF_ORDER_CSV.lineSequence().first()
        val cases =
            listOf(
                Triple(
                    MetricsConfig(maxTransactionIdentityBytes = 20),
                    policy(
                        """{"schema_version":"policy.v1","policy_id":"per-identity","rules":[{"id":"oversized","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"transaction","name":"oversized"}}]}""",
                    ),
                    listOf("oversized"),
                ),
                Triple(
                    MetricsConfig(maxTransactionIdentityBytes = 100, maxTotalTransactionIdentityBytes = 30),
                    policy(
                        """{"schema_version":"policy.v1","policy_id":"total","rules":[{"id":"first","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"transaction","name":"first"}},{"id":"second","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"transaction","name":"second"}}]}""",
                    ),
                    listOf("first", "second"),
                ),
            )

        cases.forEachIndexed { index, (metrics, candidatePolicy, labels) ->
            withService(EngineConfig(metrics = metrics)) { store, service ->
                val rows =
                    labels.mapIndexed { row, label ->
                        "17672256000${row}0,10,$label,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0"
                    }
                val input =
                    accept(store, (listOf(header) + rows + "malformed").joinToString("\n").encodeToByteArray(), "candidate-$index.jtl")
                val raw = resourceJson(input.sha256, "candidate-$index", "1.0").encodeToByteArray()

                val outcome = service.analyze(AnalysisRequest(input, candidatePolicy, resources = resources(raw)))
                val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject

                assertEquals("INVALID", result.getValue("run_validity").jsonPrimitive.content)
                assertEquals(
                    listOf("RESOURCE_LIMIT_EXCEEDED"),
                    result
                        .getValue("analysis_coverage")
                        .jsonObject
                        .getValue("reasons")
                        .jsonArray
                        .map { it.jsonPrimitive.content },
                )
            }
        }
    }

    @Test
    fun `resource metadata path resolves from the run root and its hash matches the artifact`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "resource-path.jtl")
            val raw = resourceJson(input.sha256, "path", "1.0").encodeToByteArray()
            val outcome = service.analyze(AnalysisRequest(input, null, resources = resources(raw)))
            val inputs =
                Json
                    .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("run.json")))
                    .jsonObject
                    .getValue("inputs")
                    .jsonArray
                    .map { it.jsonObject }
            val runRoot = input.path.parent.parent
            inputs.forEach { metadata ->
                val path = Path.of(metadata.getValue("path").jsonPrimitive.content)
                assertFalse(path.isAbsolute)
                val storedBytes = Files.readAllBytes(runRoot.resolve(path).normalize())
                assertEquals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(storedBytes)),
                    metadata.getValue("sha256").jsonPrimitive.content,
                )
            }
            val resourceInput = inputs.single { it.getValue("type").jsonPrimitive.content == "resource_snapshot" }
            val relativePath = resourceInput.getValue("path").jsonPrimitive.content
            val resolvedPath = runRoot.resolve(relativePath).normalize()

            assertEquals("analyses/${outcome.analysisId}/$RESOURCE_FILE", relativePath)
            assertEquals(outcome.analysisDirectory.resolve(RESOURCE_FILE), resolvedPath)
            assertArrayEquals(raw, Files.readAllBytes(resolvedPath))
        }

    @Test
    fun `window latency keeps the full sample that crosses its end`() =
        withService { store, service ->
            val input = accept(store, CROSSING_CSV.encodeToByteArray(), "crossing.jtl")
            val policy =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"latency","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":400,"scope":{"kind":"overall"}}]}""",
                )
            val raw = resourceJson(input.sha256, "crossing", "1.0").encodeToByteArray()
            val outcome = service.analyze(AnalysisRequest(input, policy, resources = resources(raw)))
            val evidence =
                Json
                    .parseToJsonElement(outcome.canonicalResult.decodeToString())
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
            val business = evidence.map { it.jsonObject }.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

            assertEquals("FAIL", business.getValue("status").jsonPrimitive.content)
            assertEquals("evaluation", business.getValue("window_id").jsonPrimitive.content)
            assertEquals("FAIL", result(outcome, "policy_verdict"))
        }

    private fun withService(
        config: EngineConfig = EngineConfig(),
        block: (RunBundleStore, AnalysisService) -> Unit,
    ) {
        val root = tempDir.resolve("data-${System.nanoTime()}")
        DataDirectory.open(root).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, config))
        }
    }

    private fun accept(
        store: RunBundleStore,
        bytes: ByteArray,
        name: String,
    ): AcceptedInput = store.acceptInput(ByteArrayInputStream(bytes), name)

    private fun passPolicy(): PolicyValidation.Valid =
        policy(
            """{"schema_version":"policy.v1","policy_id":"pass","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}""",
        )

    private fun policyFromFile(path: String): PolicyValidation.Valid =
        Files.newInputStream(Path.of(path)).use { source ->
            assertInstanceOf(PolicyValidation.Valid::class.java, validatePolicy(source))
        }

    private fun policy(json: String): PolicyValidation.Valid =
        assertInstanceOf(
            PolicyValidation.Valid::class.java,
            validatePolicy(ByteArrayInputStream(json.encodeToByteArray())),
        )

    private fun resources(bytes: ByteArray): ResourceValidation.Valid =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(bytes)),
        )

    private fun resourceJson(
        loadHash: String,
        provenance: String,
        threshold: String,
        pointCount: Int = 1,
        toEpochMillis: Long = 1_767_225_601_000,
    ): String {
        val values = List(pointCount) { "0.9" }.joinToString(",")
        return """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"$loadHash",
              "start_epoch_ms":1767225600000,
              "step_ms":1000,
              "point_count":$pointCount,
              "series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[$values]}],
              "windows":[{"id":"evaluation","from_epoch_ms":1767225600000,"to_epoch_ms":$toEpochMillis}],
              "rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":$threshold,"min_consecutive_cells":1,"effect":"sla"}],
              "provenance":{"source_kind":"fixture","query_semantics":"interval mean","clock_alignment":"$provenance"}
            }
            """.trimIndent()
    }

    private fun capacityResourceJson(loadHash: String): String {
        val values = List(30) { "0.5" }.joinToString(",")
        return """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"$loadHash",
              "start_epoch_ms":1767225600000,
              "step_ms":10000,
              "point_count":30,
              "series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[$values]}],
              "windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000}],
              "rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":1,"min_consecutive_cells":1,"effect":"sla"}],
              "provenance":{"source_kind":"fixture","query_semantics":"interval mean","clock_alignment":"capacity"}
            }
            """.trimIndent()
    }

    private fun capacityPlanJson(
        loadHash: String,
        resourceHash: String,
    ): String =
        """{"schema_version":"capacity-plan.v1","load_input_sha256":"$loadHash","resource_snapshot_sha256":"$resourceHash","load_axis":"rps","achieved_load":{"statistic":"p05_10s","target_tolerance_ratio":0,"required_capacity":1},"generator_guard_rule_ids":[],"stages":[{"id":"steady","target":1,"from_epoch_ms":1767225600000,"to_epoch_ms":1767225900000,"evaluation_window_id":"steady"}]}"""

    private fun capacityCsv(): String =
        (
            listOf(OUT_OF_ORDER_CSV.lineSequence().first()) +
                (0 until 30).map { index ->
                    "${1767225600000L + index * 10_000L},${if (index == 29) 10_000 else 10},steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0"
                }
        ).joinToString("\n")

    private fun storedArtifacts(
        store: RunBundleStore,
        input: AcceptedInput,
        outcome: AnalysisOutcome,
    ): Set<String> =
        store
            .readAnalysis(input.runId, outcome.analysisId)!!
            .artifacts
            .map { it.path }
            .toSet()

    private fun result(
        outcome: AnalysisOutcome,
        field: String,
    ): String =
        Json
            .parseToJsonElement(outcome.canonicalResult.decodeToString())
            .jsonObject
            .getValue(field)
            .jsonPrimitive
            .content

    private fun expectedRun(
        input: AcceptedInput,
        startedAt: String,
        endedAt: String,
    ): String =
        """{"analysis_mode":"standard","ended_at":"$endedAt","inputs":[{"path":"inputs/source.bin","sha256":"${input.sha256}","type":"${input.sourceType.wireName}"}],"run_id":"${input.runId}","schema_version":"run.v1","started_at":"$startedAt"}"""

    private fun assertMonotonicProgress(
        values: List<Long>,
        final: Long,
    ) {
        assertTrue(values.isNotEmpty())
        assertTrue(values.all { it in 0..final })
        assertTrue(values.zipWithNext().all { (left, right) -> left <= right })
        assertEquals(final, values.last())
    }

    private fun snapshot(outcome: AnalysisOutcome): Map<String, ByteArray> =
        (COMPLETE_ARTIFACTS + "manifest.json").associateWith { name ->
            Files.readAllBytes(outcome.analysisDirectory.resolve(name))
        }

    private fun assertSnapshotEquals(
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, actual.getValue(name), name) }
    }

    private companion object {
        const val FAIL_POLICY = "fixtures/slice1/policies/fail.json"
        const val GATLING_BINARY_FIXTURE = "fixtures/slice1/gatling/binary-3.13.5/simulation.log"
        const val RESOURCE_FILE = "resource-snapshot.json"
        const val CAPACITY_PLAN_FILE = "capacity-plan.json"
        const val CAPACITY_FILE = "capacity.json"

        val COMPLETE_ARTIFACTS =
            setOf(
                "analysis-result.json",
                "identity.json",
                "normalized-1s.ndjson",
                "rollup-10s.ndjson",
                "rollup-30s.ndjson",
                "rollup-60s.ndjson",
                "run.json",
            )

        val OUT_OF_ORDER_CSV =
            """
            timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
            1767225601000,20,steady,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            1767225600000,900,spike,500,Error,fixture,text,false,,0,0,1,1,null,0,0,0
            1767225600010,850,spike,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            """.trimIndent()

        val CROSSING_CSV =
            """
            timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
            1767225600000,10,first,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            1767225600900,500,crossing,200,OK,fixture,text,true,,0,0,1,1,null,0,0,0
            """.trimIndent()
    }
}

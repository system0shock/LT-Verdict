package io.ltverdict.core

import io.ltverdict.metrics.MetricsConfig
import io.ltverdict.sources.SourceAcquisition
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
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

class AnalysisServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `controller parents are excluded from overall counts and evaluated by transaction policy`() =
        withService { store, service ->
            val input = accept(store, controllerCsv("child").encodeToByteArray(), "parents.jtl")
            val rule =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"parent","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"controller","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"transaction","name":"controller"}}]}""",
                )
            val outcome = service.analyze(AnalysisRequest(input, rule))
            val evidence =
                Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject.getValue("evidence").jsonArray.map {
                    it.jsonObject
                }
            val summaries = evidence.filter { it.getValue("type").jsonPrimitive.content == "metric_summary" }
            val overall =
                summaries.single {
                    it
                        .getValue("scope")
                        .jsonObject
                        .getValue("kind")
                        .jsonPrimitive.content == "overall"
                }
            val controller =
                summaries.single {
                    it
                        .getValue("scope")
                        .jsonObject["label"]
                        ?.jsonPrimitive
                        ?.content == "controller"
                }
            val check = evidence.single { it.getValue("type").jsonPrimitive.content == "policy_check" }

            assertEquals("2", overall.getValue("sample_count").jsonPrimitive.content)
            assertEquals(
                "JMETER_CONTAINER",
                controller
                    .getValue("scope")
                    .jsonObject
                    .getValue("sample_kind")
                    .jsonPrimitive.content,
            )
            assertEquals("1", controller.getValue("sample_count").jsonPrimitive.content)
            assertEquals("PASS", check.getValue("status").jsonPrimitive.content)
            assertEquals(controller.getValue("id"), check.getValue("metric_evidence_id"))
        }

    @Test
    fun `shared controller and sampler label makes transaction policy ambiguous`() =
        withService { store, service ->
            val input = accept(store, controllerCsv("controller").encodeToByteArray(), "ambiguous.jtl")
            val rule =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"parent","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"controller","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"transaction","name":"controller"}}]}""",
                )
            val outcome = service.analyze(AnalysisRequest(input, rule))
            val evidence =
                Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject.getValue("evidence").jsonArray.map {
                    it.jsonObject
                }
            val check = evidence.single { it.getValue("type").jsonPrimitive.content == "policy_check" }

            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            assertEquals("NO_VERDICT", check.getValue("status").jsonPrimitive.content)
            assertEquals("AMBIGUOUS_TRANSACTION", check.getValue("reason_code").jsonPrimitive.content)
            assertTrue("AMBIGUOUS_TRANSACTION" in coverageReasons(outcome))
        }

    @Test
    fun `flat CSV without parent rows retains its overall sample count`() =
        withService { store, service ->
            val flat =
                controllerCsv(
                    "child",
                ).lineSequence().filter { it.isNotEmpty() && ",controller," !in it }.joinToString("\n", postfix = "\n")
            val input = accept(store, flat.encodeToByteArray(), "flat.jtl")
            val outcome = service.analyze(AnalysisRequest(input, null))
            val summaries =
                Json
                    .parseToJsonElement(
                        outcome.canonicalResult.decodeToString(),
                    ).jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .map {
                        it.jsonObject
                    }
            val overall =
                summaries.single {
                    it["type"]?.jsonPrimitive?.content == "metric_summary" &&
                        it
                            .getValue("scope")
                            .jsonObject
                            .getValue("kind")
                            .jsonPrimitive.content == "overall"
                }

            assertEquals("2", overall.getValue("sample_count").jsonPrimitive.content)
        }

    private fun controllerCsv(childLabel: String): String =
        listOf(
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect",
            "1767225600000,100,controller,200,\"Number of samples in transaction : 2, number of failing samples : 0\",thread,,true,,0,0,1,1,,0,0,0",
            "1767225600000,50,$childLabel,200,OK,thread,text,true,,0,0,1,1,,0,0,0",
            "1767225600050,50,other,200,OK,thread,text,true,,0,0,1,1,,0,0,0",
        ).joinToString("\n", postfix = "\n")

    @Test
    fun `beforePublish gates valid and invalid analysis writes exactly once`() {
        listOf(
            OUT_OF_ORDER_CSV to "valid.jtl",
            "${OUT_OF_ORDER_CSV.lineSequence().first()}\nmalformed\n" to "invalid.jtl",
        ).forEach { (contents, name) ->
            withService { store, service ->
                val input = accept(store, contents.encodeToByteArray(), name)
                val request = AnalysisRequest(input, null)
                val analyses =
                    input.path.parent.parent
                        .resolve("analyses")
                val cancelledCalls = AtomicInteger()
                assertThrows(CancellationException::class.java) {
                    service.analyze(
                        request,
                        beforePublish = {
                            cancelledCalls.incrementAndGet()
                            throw CancellationException("CANCELLED")
                        },
                    )
                }
                assertEquals(1, cancelledCalls.get())
                assertEquals(0L, Files.list(analyses).use { it.count() })

                val publishedCalls = AtomicInteger()
                val outcome = service.analyze(request, beforePublish = { publishedCalls.incrementAndGet() })
                assertEquals(1, publishedCalls.get())
                assertTrue(store.readAnalysis(input.runId, outcome.analysisId) != null)
                assertEquals(if (name == "invalid.jtl") "INVALID" else "VALID", result(outcome, "run_validity"))
            }
        }
    }

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
    fun `acquisition artifacts accept response numbers up to 1024 and reject 5 digits`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "responses.jtl")
            val resource = resources(resourceJson(input.sha256, "responses", "0.8").encodeToByteArray())
            val evidence =
                buildJsonObject {
                    put("id", "source-summary")
                    put("type", "source_summary")
                    put("status", "COMPLETE")
                }

            fun request(responses: List<String>): AnalysisRequest {
                val extra = responses.associateWith { "{}".encodeToByteArray() }
                val artifacts = mapOf("source-acquisition.json" to canonicalJson(evidence)) + extra
                val acquisition = SourceAcquisition(resource, evidence, artifacts)
                return AnalysisRequest(input, passPolicy(), resources = resource, sourceAcquisition = acquisition)
            }

            val names = listOf("source-response-1000.json", "source-response-1024.json")
            val outcome = service.analyze(request(names))
            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            assertTrue(stored.artifacts.map { it.path }.containsAll(names))

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(request(listOf("source-response-10000.json")))
                }
            assertEquals("SOURCE_ARTIFACT_NAME_INVALID", failure.message)
        }

    @Test
    fun `source acquisition degradation reaches analysis coverage`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "coverage.jtl")
            val resource = resources(resourceJson(input.sha256, "coverage", "0.8").encodeToByteArray())

            val complete = analyzeWithSummary(service, input, resource, "COMPLETE", capExceeded = false)
            assertTrue(coverageReasons(complete).none { it.startsWith("SOURCE_") })

            val partial = analyzeWithSummary(service, input, resource, "PARTIAL", capExceeded = false)
            assertEquals("INCOMPLETE", coverageStatus(partial))
            assertTrue(coverageReasons(partial).contains("SOURCE_ACQUISITION_PARTIAL"))

            val failed = analyzeWithSummary(service, input, resource, "FAILED", capExceeded = false)
            assertEquals("INCOMPLETE", coverageStatus(failed))
            assertTrue(coverageReasons(failed).contains("SOURCE_ACQUISITION_FAILED"))

            val capped = analyzeWithSummary(service, input, resource, "COMPLETE", capExceeded = true)
            assertEquals("INCOMPLETE", coverageStatus(capped))
            assertTrue(coverageReasons(capped).contains("SOURCE_REQUEST_CAP_EXCEEDED"))
        }

    @Test
    fun `trend plan is bound stored and evaluated without touching coverage or verdict`() =
        withService { store, service ->
            val input = accept(store, trendCsv().encodeToByteArray(), "trend.jtl")
            val resource = resources(trendResourceJson(input.sha256).encodeToByteArray())
            val plan = trendPlanJson(resource.semanticSha256)
            val trend = validTrend(plan.encodeToByteArray())

            val outcome = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, trend = trend))
            val withoutTrend = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource))

            assertEquals("PASS", result(outcome, "policy_verdict"))
            assertEquals(result(withoutTrend, "policy_verdict"), result(outcome, "policy_verdict"))
            assertEquals("COMPLETE", coverageStatus(outcome))
            assertNotEquals(withoutTrend.analysisId, outcome.analysisId)

            val json = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
            val evidence = json.getValue("evidence").jsonArray.map { it.jsonObject }
            assertEquals("1", evidence.single { it.type() == "trend_summary" }.value("checks_total"))
            assertEquals("TREND_OBSERVED", evidence.single { it.type() == "trend_check" }.value("status"))
            val finding =
                json
                    .getValue("findings")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.type() == "resource_trend" }
            assertEquals("diagnostic", finding.value("effect"))
            assertEquals("NOT_ESTIMATED", finding.value("uncertainty"))

            val stored = checkNotNull(store.readAnalysis(input.runId, outcome.analysisId))
            assertTrue(stored.artifacts.map { it.path }.containsAll(listOf("trend-plan.json", "trend.json")))
            assertArrayEquals(plan.encodeToByteArray(), Files.readAllBytes(stored.path.resolve("trend-plan.json")))
            assertEquals(
                "trend.v1",
                Json
                    .parseToJsonElement(Files.readAllBytes(stored.path.resolve("trend.json")).decodeToString())
                    .jsonObject
                    .value("schema_version"),
            )

            val identity = Json.parseToJsonElement(Files.readAllBytes(stored.path.resolve("identity.json")).decodeToString()).jsonObject
            assertEquals(trend.semanticSha256, identity.value("trend_plan_sha256"))
            assertEquals("trend-plan.v1", identity.value("trend_plan_version"))
            assertEquals("trend-plan.v1", identity.getValue("input_versions").jsonObject.value("trend"))
            assertEquals("32", identity.getValue("limits").jsonObject.value("trend_checks_max"))
            assertEquals("slope-materiality.v1", identity.getValue("limits").jsonObject.value("trend_method"))
            assertTrue(
                identity
                    .getValue("modules")
                    .jsonArray
                    .map { it.jsonObject.value("id") to it.jsonObject.value("version") }
                    .contains("resource-trend-evaluation" to "1"),
            )

            val run = Json.parseToJsonElement(Files.readAllBytes(stored.path.resolve("run.json")).decodeToString()).jsonObject
            val trendInput =
                run
                    .getValue("inputs")
                    .jsonArray
                    .map { it.jsonObject }
                    .single { it.value("type") == "trend_plan" }
            assertEquals("analyses/${outcome.analysisId}/trend-plan.json", trendInput.value("path"))
            assertEquals(sha256Hex(plan.encodeToByteArray()), trendInput.value("sha256"))
        }

    @Test
    fun `trend plan requires a matching snapshot before any analysis is stored`() =
        withService { store, service ->
            val input = accept(store, trendCsv().encodeToByteArray(), "trend-rejected.jtl")
            val resource = resources(trendResourceJson(input.sha256).encodeToByteArray())

            val stale = validTrend(trendPlanJson("b".repeat(64)).encodeToByteArray())
            assertEquals(
                "TREND_SNAPSHOT_MISMATCH",
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, trend = stale))
                }.message,
            )

            assertEquals(
                "TREND_RESOURCE_REQUIRED",
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, passPolicy(), trend = stale))
                }.message,
            )
        }

    @Test
    fun `an invalid load input keeps the raw trend plan and abstains on every check`() =
        withService { store, service ->
            val input = accept(store, "timeStamp,elapsed,label,success\nnot-a-number,1,request,true\n".encodeToByteArray(), "bad.jtl")
            val resource = resources(trendResourceJson(input.sha256).encodeToByteArray())
            val plan = trendPlanJson(resource.semanticSha256)
            val trend = validTrend(plan.encodeToByteArray())

            val outcome = service.analyze(AnalysisRequest(input, passPolicy(), resources = resource, trend = trend))

            assertEquals("INVALID", result(outcome, "run_validity"))
            assertEquals("NO_VERDICT", result(outcome, "policy_verdict"))
            val evidence =
                Json
                    .parseToJsonElement(outcome.canonicalResult.decodeToString())
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
            val check = evidence.single { it.type() == "trend_check" }
            assertEquals("UNAVAILABLE", check.value("status"))
            assertEquals(
                "RUN_NOT_VALID",
                check
                    .getValue("reasons")
                    .jsonArray
                    .single()
                    .jsonPrimitive.content,
            )
            val findings =
                Json
                    .parseToJsonElement(outcome.canonicalResult.decodeToString())
                    .jsonObject
                    .getValue("findings")
                    .jsonArray
                    .map { it.jsonObject }
            assertTrue(findings.none { it.type() == "resource_trend" })

            val stored = checkNotNull(store.readAnalysis(input.runId, outcome.analysisId))
            assertArrayEquals(plan.encodeToByteArray(), Files.readAllBytes(stored.path.resolve("trend-plan.json")))
        }

    @Test
    fun `standard analysis uses the final two-pass window and commits the complete bundle`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "out-of-order.jtl")
            val progress = mutableListOf<Long>()

            val policy = passPolicy()
            val outcome = service.analyze(AnalysisRequest(input, policy), progress::add)

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
            val policyBytes = Files.readAllBytes(stored.path.resolve("policy.json"))
            assertArrayEquals(policy.canonicalBytes, policyBytes)
            assertEquals(identity.getValue("policy_sha256").jsonPrimitive.content, sha256Hex(policyBytes))
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
    fun `two policies for one load give separate analyses that stay comparable`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "two-policies.jtl")

            fun policyFor(threshold: String) =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"p-$threshold","defaults":{"sample_floor":1,"min_samples":1},""" +
                        """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,"scope":{"kind":"overall"}}]}""",
                )

            val lenient = service.analyze(AnalysisRequest(input, policyFor("1000000")))
            val strict = service.analyze(AnalysisRequest(input, policyFor("0")))

            fun identity(outcome: AnalysisOutcome) =
                Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject

            assertNotEquals(lenient.analysisId, strict.analysisId)
            assertNotEquals(identity(lenient).getValue("policy_sha256"), identity(strict).getValue("policy_sha256"))
            listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")
                .forEach { assertEquals(identity(lenient).getValue(it), identity(strict).getValue(it), it) }
            assertEquals(result(lenient, "analysis_mode"), result(strict, "analysis_mode"))
            assertEquals("PASS", result(lenient, "policy_verdict"))
            assertEquals("FAIL", result(strict, "policy_verdict"))
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
            assertFalse(Files.exists(stored.path.resolve("policy.json")))
            assertFalse(Files.exists(stored.path.resolve("run.json")))
            assertFalse(Files.exists(stored.path.resolve("normalized-1s.ndjson")))
        }

    @Test
    fun `invalid input with policy persists canonical policy`() =
        withService { store, service ->
            val header = OUT_OF_ORDER_CSV.lineSequence().first()
            val input = accept(store, "$header\nmalformed\n".encodeToByteArray(), "invalid-policy.jtl")
            val policy = passPolicy()
            val outcome = service.analyze(AnalysisRequest(input, policy))
            val stored = store.readAnalysis(input.runId, outcome.analysisId)!!
            val policyBytes = Files.readAllBytes(stored.path.resolve("policy.json"))
            val identity = Json.parseToJsonElement(Files.readString(stored.path.resolve("identity.json"))).jsonObject

            assertEquals("INVALID", result(outcome, "run_validity"))
            assertEquals(setOf("analysis-result.json", "identity.json", "policy.json"), stored.artifacts.map { it.path }.toSet())
            assertArrayEquals(policy.canonicalBytes, policyBytes)
            assertEquals(identity.getValue("policy_sha256").jsonPrimitive.content, sha256Hex(policyBytes))
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
                setOf("analysis-result.json", "identity.json", "policy.json"),
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
    fun `a capacity analysis records the stage sample gate even without a policy`() =
        withService { store, service ->
            val input = accept(store, capacityCsv().encodeToByteArray(), "capacity-gates.jtl")
            val resource = resources(capacityResourceJson(input.sha256).encodeToByteArray())
            val plan =
                assertInstanceOf(
                    CapacityPlanValidation.Valid::class.java,
                    validateCapacityPlan(ByteArrayInputStream(capacityPlanJson(input.sha256, resource.semanticSha256).encodeToByteArray())),
                )

            val outcome = service.analyze(AnalysisRequest(input, null, resources = resource, capacity = plan))
            val gates =
                Json
                    .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json")))
                    .jsonObject
                    .getValue("verdict_gates")
                    .jsonObject

            assertEquals(
                mapOf("capacity_stage_sample_gate" to "true", "min_samples_default" to "100"),
                gates.mapValues { it.value.jsonPrimitive.content },
            )
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

            val gates =
                Json
                    .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json")))
                    .jsonObject
                    .getValue("verdict_gates")
                    .jsonObject
            assertEquals(
                mapOf(
                    "min_samples_floor" to "20",
                    "min_samples_default" to "100",
                    "throughput_exempt" to "true",
                    "capacity_stage_sample_gate" to "true",
                ),
                gates.mapValues { it.value.jsonPrimitive.content },
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
                    """{"schema_version":"policy.v1","policy_id":"latency","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":400,"scope":{"kind":"overall"}}]}""",
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

    @Test
    fun `platform rules and an SLA rule of the snapshot are rejected before an analysis exists`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "platform-conflict.jtl")
            val analyses =
                input.path.parent.parent
                    .resolve("analyses")

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(
                        AnalysisRequest(
                            input,
                            platformPolicy("diagnostic"),
                            resources = resources(resourceJson(input.sha256, "conflict", "0.8").encodeToByteArray()),
                        ),
                    )
                }

            assertEquals("PLATFORM_RULES_CONFLICT", failure.message)
            assertFalse(Files.exists(analyses))
        }

    @Test
    fun `platform SLA rules decide the window verdict of an analysis`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "platform-verdict.jtl")
            val outcome =
                service.analyze(
                    AnalysisRequest(
                        input,
                        platformPolicy("sla"),
                        resources = resources(platformResourceJson(input.sha256, cpu = "0.9").encodeToByteArray()),
                    ),
                )
            val checks =
                Json
                    .parseToJsonElement(outcome.canonicalResult.decodeToString())
                    .jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .filter { it.getValue("type").jsonPrimitive.content == "resource_policy_check" }

            assertEquals("FAIL", result(outcome, "policy_verdict"))
            assertEquals(
                listOf("cpu/host" to "FAIL", "cover/host" to "PASS"),
                checks.map { it.getValue("rule_id").jsonPrimitive.content to it.getValue("status").jsonPrimitive.content },
            )
            assertEquals(
                setOf("cpu", "cover"),
                checks.map { it.getValue("platform_rule_id").jsonPrimitive.content }.toSet(),
            )

            val passing =
                service.analyze(
                    AnalysisRequest(
                        input,
                        platformPolicy("sla"),
                        resources = resources(platformResourceJson(input.sha256, cpu = "0.1").encodeToByteArray()),
                    ),
                )
            assertEquals("PASS", result(passing, "policy_verdict"))
            assertNotEquals(outcome.analysisId, passing.analysisId)
        }

    private fun platformPolicy(effect: String): PolicyValidation.Valid =
        policy(
            """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
                """"platform_services":["host"],"platform_coverage":{"signal":"unavailable"},""" +
                """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}],""" +
                """"platform_rules":[""" +
                """{"id":"cpu","signal":"cpu_used","scope":{"kind":"all_services"},"operator":"gt","threshold":0.8,"unit":"ratio",""" +
                """"aggregation":"interval_mean","min_consecutive_cells":1,"effect":"$effect"},""" +
                """{"id":"cover","signal":"unavailable","scope":{"kind":"all_services"},"operator":"gt","threshold":0,"unit":"count",""" +
                """"aggregation":"interval_max","min_consecutive_cells":1,"effect":"$effect"}]}""",
        )

    private fun platformResourceJson(
        loadHash: String,
        cpu: String,
    ): String =
        """
        {
          "schema_version":"resource-snapshot.v1",
          "load_input_sha256":"$loadHash",
          "start_epoch_ms":1767225600000,
          "step_ms":1000,
          "point_count":1,
          "series":[
            {"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":[$cpu]},
            {"id":"unavailable","metric":"unavailable","unit":"count","entity":"host","role":"system","aggregation":"interval_max","values":[0]}
          ],
          "windows":[{"id":"evaluation","from_epoch_ms":1767225600000,"to_epoch_ms":1767225601000}],
          "rules":[],
          "provenance":{"source_kind":"fixture","query_semantics":"interval mean","clock_alignment":"platform"}
        }
        """.trimIndent()

    @Test
    fun `one base profile policy gives every arm its own independent result`() =
        withService { store, service ->
            val input = accept(store, trendCsv().encodeToByteArray(), "arms.jtl")
            val baseText = Files.readString(Path.of(BASE_PROFILE_PATH))
            val base = policy(baseText)

            fun analyze(
                cpuOrders: String,
                services: List<String> = listOf("orders", "payments"),
                using: PolicyValidation.Valid = base,
            ) = service.analyze(
                AnalysisRequest(
                    input,
                    using,
                    resources = resources(baseProfileSnapshotJson(input.sha256, cpuOrders, services).encodeToByteArray()),
                ),
            )

            val first = analyze("0.5")
            val second = analyze("0.1")
            val third = analyze("0.1", services = listOf("orders"))
            val relaxed = analyze("0.5", using = policy(baseText.replace("\"threshold\": 0.4", "\"threshold\": 0.6")))

            assertEquals(
                listOf("FAIL", "PASS", "NO_VERDICT", "PASS"),
                listOf(first, second, third, relaxed).map { result(it, "policy_verdict") },
            )
            assertTrue("RESOURCE_SERIES_NOT_FOUND" in coverageReasons(third))
            assertEquals(3, listOf(first, second, third).map { it.analysisId }.toSet().size)
            val policyHashes =
                listOf(first, second, third).map {
                    Json
                        .parseToJsonElement(
                            Files.readString(it.analysisDirectory.resolve("identity.json")),
                        ).jsonObject
                        .getValue("policy_sha256")
                }
            assertEquals(1, policyHashes.toSet().size)
        }

    private fun baseProfileSnapshotJson(
        loadHash: String,
        cpuOrders: String,
        services: List<String>,
    ): String {
        fun series(
            id: String,
            metric: String,
            entity: String,
            unit: String,
            aggregation: String,
            value: String,
        ) = """{"id":"$id","metric":"$metric","unit":"$unit","entity":"$entity","role":"system","aggregation":"$aggregation",""" +
            """"values":[${List(30) { value }.joinToString(",")}]}"""
        val all =
            services.flatMap { service ->
                val cpu = if (service == "orders") cpuOrders else "0.1"
                listOf(
                    series("cpu-$service", "openshift_container_cpu_limit_ratio", service, "ratio", "interval_mean", cpu),
                    series("memory-$service", "openshift_container_memory_limit_ratio", service, "ratio", "interval_max", "0.3"),
                    series("oom-$service", "openshift_oom", service, "events/s", "interval_rate", "0"),
                    series("restarts-$service", "openshift_restarts", service, "events/s", "interval_rate", "0"),
                    series("throttling-$service", "openshift_cpu_throttling", service, "ratio", "interval_mean", "0"),
                    series("replicas-$service", "openshift_unavailable_replicas", service, "count", "interval_max", "0"),
                )
            }
        val seriesJson = all.joinToString(",")
        return """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadHash",""" +
            """"start_epoch_ms":1767225600000,"step_ms":1000,"point_count":30,"series":[$seriesJson],""" +
            """"windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225630000}],""" +
            """"provenance":{"source_kind":"fixture","query_semantics":"interval","clock_alignment":"arm"}}"""
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
            """{"schema_version":"policy.v1","policy_id":"pass","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}""",
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

    private fun trendCsv(): String {
        val rows = List(40) { index -> "${1_767_225_600_000L + index * 1_000L},${if (index == 39) 1_000 else 500},request,true" }
        return (listOf("timeStamp,elapsed,label,success") + rows).joinToString("\n", postfix = "\n")
    }

    private fun trendResourceJson(loadHash: String): String {
        val values = List(40) { (100 + it).toString() }.joinToString(",")
        return """
            {
              "schema_version":"resource-snapshot.v1",
              "load_input_sha256":"$loadHash",
              "start_epoch_ms":1767225600000,
              "step_ms":1000,
              "point_count":40,
              "series":[{"id":"cpu","metric":"cpu_used","unit":"percent","entity":"host","role":"system","aggregation":"interval_mean","values":[$values]}],
              "windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225639000}],
              "rules":[{"id":"cpu-diagnostic","series_id":"cpu","unit":"percent","operator":"gt","threshold":1000,"min_consecutive_cells":1,"effect":"diagnostic"}],
              "provenance":{"source_kind":"fixture","query_semantics":"interval mean","clock_alignment":"unknown"}
            }
            """.trimIndent()
    }

    private fun trendPlanJson(snapshotHash: String): String =
        """{"schema_version":"trend-plan.v1","resource_snapshot_sha256":"$snapshotHash","checks":[""" +
            """{"id":"cpu-growth","series_id":"cpu","window_id":"steady","direction":"increase","min_cells":30,""" +
            """"magnitude_gate":{"min_slope_units_per_second":0.5,"min_split_half_shift_pct":5}}]}"""

    private fun validTrend(bytes: ByteArray): TrendPlanValidation.Valid =
        assertInstanceOf(TrendPlanValidation.Valid::class.java, validateTrendPlan(ByteArrayInputStream(bytes)))

    private fun JsonObject.type(): String = getValue("type").jsonPrimitive.content

    private fun JsonObject.value(name: String): String = getValue(name).jsonPrimitive.content

    private fun analyzeWithSummary(
        service: AnalysisService,
        input: AcceptedInput,
        resource: ResourceValidation.Valid,
        status: String,
        capExceeded: Boolean,
    ): AnalysisOutcome {
        val evidence =
            buildJsonObject {
                put("id", "source-summary")
                put("type", "source_summary")
                put("status", status)
                put("cap_exceeded", capExceeded)
            }
        val artifacts = mapOf("source-acquisition.json" to canonicalJson(evidence))
        val request =
            AnalysisRequest(
                input,
                passPolicy(),
                resources = resource,
                sourceAcquisition = SourceAcquisition(resource, evidence, artifacts),
            )
        return service.analyze(request)
    }

    private fun coverage(outcome: AnalysisOutcome): JsonObject =
        Json
            .parseToJsonElement(outcome.canonicalResult.decodeToString())
            .jsonObject
            .getValue("analysis_coverage")
            .jsonObject

    private fun coverageStatus(outcome: AnalysisOutcome): String = coverage(outcome).getValue("status").jsonPrimitive.content

    private fun coverageReasons(outcome: AnalysisOutcome): List<String> =
        coverage(outcome).getValue("reasons").jsonArray.map { it.jsonPrimitive.content }

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
        const val BASE_PROFILE_PATH = "docs/contracts/policy/v1/examples/valid/platform-base-profile.json"

        val COMPLETE_ARTIFACTS =
            setOf(
                "analysis-result.json",
                "identity.json",
                "policy.json",
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

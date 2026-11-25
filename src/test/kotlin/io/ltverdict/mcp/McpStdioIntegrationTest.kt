package io.ltverdict.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ltverdict.ai.AdviceUnavailableReason
import io.ltverdict.ai.AdvisoryAiJobs
import io.ltverdict.ai.AdvisoryAiService
import io.ltverdict.ai.AdvisoryRunner
import io.ltverdict.ai.AiAdviceStore
import io.ltverdict.ai.RunnerOutcome
import io.ltverdict.cli.EXIT_OK
import io.ltverdict.cli.EXIT_USAGE
import io.ltverdict.cli.runCli
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.ResourceAggregation
import io.ltverdict.core.ResourceRole
import io.ltverdict.core.sha256Hex
import io.ltverdict.jobs.AnalysisJobs
import io.ltverdict.sources.SourceAuth
import io.ltverdict.sources.SourceGovernor
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceKind
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceQuery
import io.ltverdict.sources.SourceTransport
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import io.ltverdict.web.LocalApiContext
import io.ltverdict.web.startLocalServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class McpStdioIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `full stdio MCP workflow runs end-to-end without secret or raw-error leaks`() {
        val jtlBytes =
            (
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                    "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
                    "1767225600000,10,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225601000,950,GET /pay?token=supersecret123,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225615000,920,GET /pay?token=supersecret123,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225625000,12,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225645000,11,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n"
            ).toByteArray(UTF_8)
        val jtlFile = tempDir.resolve("run.jtl")
        Files.write(jtlFile, jtlBytes)
        val jtlSha256 = sha256Hex(jtlBytes)

        val baselineJtlBytes =
            (
                "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                    "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
                    "1767225600000,10,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225601000,10,GET /pay?token=supersecret123,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225615000,12,GET /pay?token=supersecret123,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225625000,10,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n" +
                    "1767225645000,10,GET /health,200,OK,t1,text,true,,100,10,1,1,null,0,0,0\n"
            ).toByteArray(UTF_8)
        val baselineJtlFile = tempDir.resolve("baseline.jtl")
        Files.write(baselineJtlFile, baselineJtlBytes)
        val baselineJtlSha256 = sha256Hex(baselineJtlBytes)

        val resourceTemplate = Files.readString(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))
        val baselineResourceJson =
            Json
                .parseToJsonElement(
                    resourceTemplate
                        .replace("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", baselineJtlSha256)
                        .replace("0.84, 0.91", "0.44, 0.51"),
                ).jsonObject
        val resourceJson =
            Json
                .parseToJsonElement(
                    resourceTemplate.replace("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", jtlSha256),
                ).jsonObject

        val promSecretEnv = "LTV_MCP_TEST_PROM_TOKEN"
        val promStub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val promExecutor = Executors.newSingleThreadExecutor()
        promStub.executor = promExecutor
        promStub.createContext("/") { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            if (query.contains("broken")) {
                exchange.respondText(500, "SECRET_UPSTREAM_BODY_LEAK bearer=topsecret_upstream")
            } else {
                val payload =
                    """{"status":"success","data":{"resultType":"matrix","result":[{"metric":{"host":"host-a","password":"db_secret_val"},"values":[[1767225600,"0.55"],[1767225610,"0.85"]]}]}}"""
                exchange.respondText(200, payload)
            }
        }
        promStub.start()

        try {
            val promPort = promStub.address.port
            val promProfile =
                SourceProfile(
                    id = "prom-main",
                    sourceKind = SourceKind.PROMETHEUS,
                    transport = SourceTransport.DIRECT,
                    baseUrl = URI("http://127.0.0.1:$promPort"),
                    datasourceUid = null,
                    auth = SourceAuth.Bearer(promSecretEnv),
                    allowInsecureHttp = true,
                    governor = SourceGovernor(1000.0, 2, 1, 5_000, 1, true, null),
                    queries =
                        listOf(
                            SourceQuery(
                                "cpu",
                                "avg_over_time(cpu[\$__interval])",
                                "cpu_used",
                                "ratio",
                                "host",
                                ResourceRole.SYSTEM,
                                ResourceAggregation.INTERVAL_MEAN,
                                mapOf("instance" to "host"),
                            ),
                            SourceQuery(
                                "broken",
                                "avg_over_time(broken[\$__interval])",
                                "cpu_used",
                                "ratio",
                                "host",
                                ResourceRole.SYSTEM,
                                ResourceAggregation.INTERVAL_MEAN,
                                mapOf("instance" to "host"),
                            ),
                        ),
                )

            DataDirectory.open(tempDir.resolve("data")).use { directory ->
                val store = RunBundleStore(directory)
                val service = AnalysisService(store, EngineConfig())
                val jobs = AnalysisJobs(1, service::analyze)
                val adviceService =
                    AdvisoryAiService(
                        store,
                        AiAdviceStore(directory, store),
                        AdvisoryRunner { RunnerOutcome.Unavailable(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED) },
                    )
                val adviceJobs = AdvisoryAiJobs(adviceService)
                val server =
                    startLocalServer(
                        LocalApiContext(
                            store = store,
                            jobs = jobs,
                            sourceProfiles = listOf(promProfile),
                            adviceService = adviceService,
                            adviceJobs = adviceJobs,
                            sourceHttp = SourceHttp(listOf(promProfile)),
                        ),
                        port = 0,
                        openBrowser = false,
                    )
                try {
                    val invalidPolicy =
                        buildJsonObject {
                            put("schema_version", "policy.v1")
                            put("policy_id", "bad-policy")
                            put("rules", buildJsonArray {})
                        }
                    val validPolicy =
                        Json
                            .parseToJsonElement(
                                """{"schema_version":"policy.v1","policy_id":"mcp-sla","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"pay-p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"}}]}""",
                            ).jsonObject

                    // 1) Lifecycle + probe_source + validate_policy + analyze baseline + analyze current
                    val session1Requests =
                        listOf(
                            rpcReq(1, "initialize", buildJsonObject { put("protocolVersion", "2024-11-05") }),
                            rpcNotif("notifications/initialized"),
                            rpcReq(2, "ping"),
                            rpcReq(3, "tools/list"),
                            rpcReq(4, "tools/call", callArgs("probe_source", buildJsonObject { put("mode", "list") })),
                            rpcReq(
                                5,
                                "tools/call",
                                callArgs(
                                    "probe_source",
                                    buildJsonObject {
                                        put("mode", "validate")
                                        put("profile_id", "prom-main")
                                    },
                                ),
                            ),
                            rpcReq(
                                6,
                                "tools/call",
                                callArgs(
                                    "probe_source",
                                    buildJsonObject {
                                        put("profile_id", "prom-main")
                                        put("query_id", "cpu")
                                    },
                                ),
                            ),
                            rpcReq(
                                7,
                                "tools/call",
                                callArgs(
                                    "probe_source",
                                    buildJsonObject {
                                        put("profile_id", "prom-main")
                                        put("query_id", "broken")
                                    },
                                ),
                            ),
                            rpcReq(
                                8,
                                "tools/call",
                                callArgs(
                                    "probe_source",
                                    buildJsonObject {
                                        put("profile_id", "prom-main")
                                        put("expression", "up")
                                    },
                                ),
                            ),
                            rpcReq(9, "tools/call", callArgs("probe_source", buildJsonObject { put("url", "http://127.0.0.1:$promPort") })),
                            rpcReq(10, "tools/call", callArgs("validate_policy", buildJsonObject { put("policy", invalidPolicy) })),
                            rpcReq(11, "tools/call", callArgs("validate_policy", buildJsonObject { put("policy", validPolicy) })),
                            rpcReq(
                                12,
                                "tools/call",
                                callArgs(
                                    "analyze",
                                    buildJsonObject {
                                        put("input_path", baselineJtlFile.toString())
                                        put("policy", validPolicy)
                                        put("resource_snapshot", baselineResourceJson)
                                        put("jenkins_build", "build-41")
                                        put("commit", "aaa111")
                                    },
                                ),
                            ),
                            rpcReq(
                                13,
                                "tools/call",
                                callArgs(
                                    "analyze",
                                    buildJsonObject {
                                        put("input_path", jtlFile.toString())
                                        put("policy", validPolicy)
                                        put("resource_snapshot", resourceJson)
                                        put("jenkins_build", "build-42")
                                        put("commit", "bbb222")
                                    },
                                ),
                            ),
                        )
                    val (res1, err1) = runMcpCli(server.origin, session1Requests)
                    assertEquals(13, res1.size)
                    val combinedOutput1 = res1.joinToString("\n") + "\n" + err1
                    assertFalse(combinedOutput1.contains("SECRET_UPSTREAM_BODY_LEAK"))
                    assertFalse(combinedOutput1.contains("topsecret_upstream"))
                    assertFalse(combinedOutput1.contains(promSecretEnv))
                    assertFalse(combinedOutput1.contains("127.0.0.1:$promPort"))

                    // Verify initialize & tools/list
                    val initRes = res1.byId(1).getValue("result").jsonObject
                    assertEquals(JsonPrimitive("2024-11-05"), initRes["protocolVersion"])
                    val tools =
                        res1
                            .byId(3)
                            .getValue("result")
                            .jsonObject
                            .getValue("tools")
                            .jsonArray
                    val toolNames =
                        tools
                            .map {
                                it.jsonObject
                                    .getValue("name")
                                    .jsonPrimitive.content
                            }.toSet()
                    assertEquals(
                        setOf(
                            "analyze",
                            "get_result",
                            "list_incidents",
                            "list_transactions",
                            "validate_policy",
                            "compare",
                            "get_resource_series",
                            "probe_source",
                            "save_advice",
                        ),
                        toolNames,
                    )

                    // probe_source assertions
                    assertFalse(res1.byId(4).toolIsError())
                    assertFalse(res1.byId(5).toolIsError())
                    assertFalse(res1.byId(6).toolIsError())
                    val brokenProbe = res1.byId(7).toolPayload()
                    assertFalse(brokenProbe.toString().contains("SECRET_UPSTREAM_BODY_LEAK"))
                    assertTrue(res1.byId(8).toolIsError())
                    assertEquals(
                        JsonPrimitive("INVALID_PROBE"),
                        res1
                            .byId(8)
                            .toolPayload()
                            .getValue("error")
                            .jsonObject["code"],
                    )
                    assertTrue(res1.byId(9).toolIsError())
                    assertEquals(
                        JsonPrimitive("INVALID_PROBE"),
                        res1
                            .byId(9)
                            .toolPayload()
                            .getValue("error")
                            .jsonObject["code"],
                    )

                    // validate_policy assertions
                    assertEquals(JsonPrimitive(false), res1.byId(10).toolPayload()["valid"])
                    assertEquals(JsonPrimitive(true), res1.byId(11).toolPayload()["valid"])

                    // analyze assertions
                    assertFalse(res1.byId(12).toolIsError()) { res1.byId(12).toolPayload().toString() }
                    val baseSummary = res1.byId(12).toolPayload()
                    val baseRunId = baseSummary.getValue("run_id").jsonPrimitive.content
                    val baseAnalysisId = baseSummary.getValue("analysis_id").jsonPrimitive.content
                    assertEquals(JsonPrimitive("PASS"), baseSummary["policy_verdict"])

                    assertFalse(res1.byId(13).toolIsError()) { res1.byId(13).toolPayload().toString() }
                    val curSummary = res1.byId(13).toolPayload()
                    val curRunId = curSummary.getValue("run_id").jsonPrimitive.content
                    val curAnalysisId = curSummary.getValue("analysis_id").jsonPrimitive.content
                    assertEquals(JsonPrimitive("FAIL"), curSummary["policy_verdict"])
                    assertEquals(JsonPrimitive("build-42"), curSummary["jenkins_build"])
                    assertEquals(JsonPrimitive("bbb222"), curSummary["commit"])

                    // Set baseline via local API so compare tool works
                    setBaseline(server.origin, baseRunId, baseAnalysisId)

                    val originalResultBytes =
                        Files.readAllBytes(
                            directory.runs
                                .resolve(curRunId)
                                .resolve("analyses")
                                .resolve(curAnalysisId)
                                .resolve("analysis-result.json"),
                        )

                    // 2) Inspect incidents, transactions, resource series, compare, get_result -> save_advice
                    val session2Requests =
                        listOf(
                            rpcReq(
                                20,
                                "tools/call",
                                callArgs(
                                    "list_incidents",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                    },
                                ),
                            ),
                            rpcReq(
                                21,
                                "tools/call",
                                callArgs(
                                    "list_transactions",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("query", "pay")
                                    },
                                ),
                            ),
                            rpcReq(
                                22,
                                "tools/call",
                                callArgs(
                                    "get_resource_series",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("mode", "catalog")
                                    },
                                ),
                            ),
                            rpcReq(
                                23,
                                "tools/call",
                                callArgs(
                                    "get_resource_series",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("mode", "values")
                                        put("series_ids", buildJsonArray { add(JsonPrimitive("host-a-cpu")) })
                                        put("limit", 10)
                                    },
                                ),
                            ),
                            rpcReq(
                                24,
                                "tools/call",
                                callArgs(
                                    "get_resource_series",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("mode", "values")
                                        put(
                                            "series_ids",
                                            buildJsonArray {
                                                (1..9).forEach { add(JsonPrimitive("s$it")) }
                                            },
                                        )
                                    },
                                ),
                            ),
                            rpcReq(
                                25,
                                "tools/call",
                                callArgs(
                                    "get_resource_series",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("mode", "values")
                                        put("series_ids", buildJsonArray { add(JsonPrimitive("host-a-cpu")) })
                                        put("limit", 501)
                                    },
                                ),
                            ),
                            rpcReq(
                                26,
                                "tools/call",
                                callArgs(
                                    "compare",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("series", "default")
                                    },
                                ),
                            ),
                            rpcReq(
                                27,
                                "tools/call",
                                callArgs(
                                    "get_result",
                                    buildJsonObject {
                                        put("run_id", curRunId)
                                        put("analysis_id", curAnalysisId)
                                        put("include_evidence", false)
                                    },
                                ),
                            ),
                        )
                    val (res2, _) = runMcpCli(server.origin, session2Requests)
                    val combinedOutput2 = res2.joinToString("\n")
                    assertFalse(combinedOutput2.contains("supersecret123"))

                    val incidentsPayload = res2.byId(20).toolPayload()
                    val incidents = incidentsPayload.getValue("incidents").jsonArray
                    assertTrue(incidents.isNotEmpty())
                    val firstEvidenceRef =
                        incidents[0]
                            .jsonObject
                            .getValue("evidence_refs")
                            .jsonArray[0]
                            .jsonPrimitive.content
                    assertTrue(firstEvidenceRef.startsWith("analysis-result.json#/"))

                    val txPayload = res2.byId(21).toolPayload()
                    assertEquals(1, txPayload.getValue("total").jsonPrimitive.int)
                    val txRow = txPayload.getValue("transactions").jsonArray[0].jsonObject
                    val txScopeLabel =
                        txRow
                            .getValue("scope")
                            .jsonObject
                            .getValue("label")
                            .jsonPrimitive.content
                    assertTrue(txScopeLabel.contains("[REDACTED]"))
                    assertFalse(txScopeLabel.contains("supersecret123"))
                    val txRef = txRow.getValue("evidence_ref").jsonPrimitive.content
                    assertTrue(txRef.startsWith("analysis-result.json#/evidence/"))

                    val catalogPayload = res2.byId(22).toolPayload()
                    assertEquals(2, catalogPayload.getValue("series").jsonArray.size)
                    val valuesPayload = res2.byId(23).toolPayload()
                    assertEquals(1, valuesPayload.getValue("series").jsonArray.size)
                    assertTrue(res2.byId(24).toolIsError())
                    assertTrue(res2.byId(25).toolIsError())

                    assertFalse(res2.byId(26).toolIsError()) { res2.byId(26).toolPayload().toString() }
                    val comparePayload = res2.byId(26).toolPayload()
                    val compareMetrics = comparePayload.getValue("metrics").jsonArray
                    assertTrue(compareMetrics.isNotEmpty())
                    assertEquals(
                        kotlinx.serialization.json.JsonNull,
                        compareMetrics[0].jsonObject["reason"],
                    )

                    val summaryPayload = res2.byId(27).toolPayload()
                    assertEquals(JsonPrimitive("FAIL"), summaryPayload["policy_verdict"])

                    // 3) save_advice grounded in evidence_refs from list_incidents & list_transactions
                    val adviceOutput =
                        buildJsonObject {
                            put("schema_version", "ai-advice-output.v1")
                            put("summary", "High p95 latency coincides with host CPU saturation.")
                            put(
                                "hypotheses",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("rank", 1)
                                            put("observation", "Overall p95 breached 100ms while host-a CPU exceeded 0.8.")
                                            put("possible_explanation", "CPU saturation on host-a slowed /pay.")
                                            put("recommended_check", "Profile /pay under steady load.")
                                            put(
                                                "evidence_refs",
                                                buildJsonArray {
                                                    add(JsonPrimitive(firstEvidenceRef))
                                                    add(JsonPrimitive(txRef))
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("recommendations", buildJsonArray {})
                            put("caveats", buildJsonArray { add(JsonPrimitive("Advisory hypothesis only.")) })
                        }
                    val (res3, _) =
                        runMcpCli(
                            server.origin,
                            listOf(
                                rpcReq(
                                    29,
                                    "tools/call",
                                    callArgs(
                                        "save_advice",
                                        buildJsonObject {
                                            put("run_id", curRunId)
                                            put("analysis_id", curAnalysisId)
                                            put("runner_id", "gigacode-qwen-code")
                                            put("model_id", "claude-sonnet-4-5")
                                            put("output", adviceOutput)
                                        },
                                    ),
                                ),
                                rpcReq(
                                    30,
                                    "tools/call",
                                    callArgs(
                                        "save_advice",
                                        buildJsonObject {
                                            put("run_id", curRunId)
                                            put("analysis_id", curAnalysisId)
                                            put("runner_id", "claude-code")
                                            put("runner_version", "1.0.0")
                                            put("model_id", "claude-sonnet-4-5")
                                            put("duration_ms", 420)
                                            put("output", adviceOutput)
                                        },
                                    ),
                                ),
                            ),
                        )
                    assertTrue(res3.byId(29).toolIsError())
                    assertEquals(
                        JsonPrimitive("INVALID_OUTPUT"),
                        res3
                            .byId(29)
                            .toolPayload()
                            .getValue("error")
                            .jsonObject["code"],
                    )
                    assertFalse(res3.byId(30).toolIsError())
                    val savedAdvice =
                        res3
                            .byId(30)
                            .toolPayload()
                            .getValue("advice")
                            .jsonObject
                    assertEquals(
                        JsonPrimitive("self-reported"),
                        savedAdvice.getValue("provenance").jsonObject["provenance_kind"],
                    )
                    assertArrayEquals(
                        originalResultBytes,
                        Files.readAllBytes(
                            directory.runs
                                .resolve(curRunId)
                                .resolve("analyses")
                                .resolve(curAnalysisId)
                                .resolve("analysis-result.json"),
                        ),
                    )
                } finally {
                    server.close()
                    adviceJobs.close()
                    jobs.close()
                }
            }
        } finally {
            promStub.stop(0)
            promExecutor.shutdownNow()
        }
    }

    @Test
    fun `mcp server supports Content-Length framing, legacy or invalid incidents, standalone data-dir, and URL check`() {
        DataDirectory.open(tempDir.resolve("edge-data")).use { directory ->
            val store = RunBundleStore(directory)
            val service = AnalysisService(store, EngineConfig())
            val jobs = AnalysisJobs(1, service::analyze)
            val server = startLocalServer(LocalApiContext(store, jobs), openBrowser = false)
            try {
                // Invalid run -> NOT_EVALUATED incidents
                val invalidJtl = tempDir.resolve("bad.jtl")
                Files.writeString(
                    invalidJtl,
                    "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success," +
                        "failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect\n" +
                        "not-a-timestamp,10,op,200,OK,t1,text,true,,10,10,1,1,null,0,0,0\n",
                )
                val badInput = Files.newInputStream(invalidJtl).use { store.acceptInput(it, "bad.jtl") }
                val invalidOutcome = service.analyze(io.ltverdict.core.AnalysisRequest(badInput, policy = null))

                // Synthetic legacy run without "incidents" key
                val validJtl = Path.of("fixtures/slice1/jmeter/csv-5.6.3/input.jtl")
                val goodInput = Files.newInputStream(validJtl).use { store.acceptInput(it, "input.jtl") }
                val goodOutcome = service.analyze(io.ltverdict.core.AnalysisRequest(goodInput, policy = null))
                val resultFile = goodOutcome.analysisDirectory.resolve("analysis-result.json")
                val parsed = Json.parseToJsonElement(Files.readString(resultFile)).jsonObject
                val legacyResultBytes =
                    io.ltverdict.core.canonicalJson(
                        JsonObject(parsed.filterKeys { it != "incidents" }),
                    )
                Files.write(resultFile, legacyResultBytes)
                val manifestFile = goodOutcome.analysisDirectory.resolve("manifest.json")
                val manifestObj = Json.parseToJsonElement(Files.readString(manifestFile)).jsonObject
                val updatedArtifacts =
                    manifestObj.getValue("artifacts").jsonArray.map { entry ->
                        val obj = entry.jsonObject
                        if (obj.getValue("path").jsonPrimitive.content == "analysis-result.json") {
                            buildJsonObject {
                                put("path", "analysis-result.json")
                                put("size_bytes", legacyResultBytes.size.toLong())
                                put("sha256", sha256Hex(legacyResultBytes))
                            }
                        } else {
                            obj
                        }
                    }
                Files.write(
                    manifestFile,
                    io.ltverdict.core.canonicalJson(
                        JsonObject(manifestObj + ("artifacts" to kotlinx.serialization.json.JsonArray(updatedArtifacts))),
                    ),
                )

                // Test Content-Length framing on initialize + list_incidents
                val req1 = rpcReq(1, "initialize", buildJsonObject { put("protocolVersion", "2024-11-05") })
                val req2 =
                    rpcReq(
                        2,
                        "tools/call",
                        callArgs(
                            "list_incidents",
                            buildJsonObject {
                                put("run_id", invalidOutcome.runId)
                                put("analysis_id", invalidOutcome.analysisId)
                            },
                        ),
                    )
                val req3 =
                    rpcReq(
                        3,
                        "tools/call",
                        callArgs(
                            "list_incidents",
                            buildJsonObject {
                                put("run_id", goodOutcome.runId)
                                put("analysis_id", goodOutcome.analysisId)
                            },
                        ),
                    )
                val clInput =
                    buildString {
                        listOf(req1, req2, req3).forEach { msg ->
                            val bytes = msg.toByteArray(UTF_8)
                            append("Content-Length: ${bytes.size}\r\n\r\n")
                            append(msg)
                        }
                    }.toByteArray(UTF_8)
                val clOut = ByteArrayOutputStream()
                val clErr = ByteArrayOutputStream()
                val exit =
                    runCli(
                        arrayOf("mcp", "--url", server.origin),
                        PrintStream(clOut),
                        PrintStream(clErr),
                        ByteArrayInputStream(clInput),
                    )
                assertEquals(EXIT_OK, exit)
                val frames = parseContentLengthFrames(clOut.toByteArray())
                assertEquals(3, frames.size)
                assertEquals(JsonPrimitive("NOT_EVALUATED"), frames[1].toolPayload()["status"])
                assertEquals(JsonPrimitive("ABSENT"), frames[2].toolPayload()["status"])
            } finally {
                server.close()
                jobs.close()
            }
        }

        // Standalone --data-dir closes cleanly on EOF
        val standaloneOut = ByteArrayOutputStream()
        val standaloneExit =
            runCli(
                arrayOf("mcp", "--data-dir", tempDir.resolve("standalone").toString()),
                PrintStream(standaloneOut),
                PrintStream(ByteArrayOutputStream()),
                ByteArrayInputStream((rpcReq(1, "ping") + "\n").toByteArray(UTF_8)),
            )
        assertEquals(EXIT_OK, standaloneExit)
        val pingResp = Json.parseToJsonElement(standaloneOut.toString(UTF_8).trim()).jsonObject
        assertEquals(JsonPrimitive(1), pingResp["id"])

        // Strict loopback URL validation
        listOf(
            "http://example.com:8080",
            "https://127.0.0.1:8080",
            "http://user@127.0.0.1:8080",
            "http://127.0.0.1:8080/api",
            "http://127.0.0.1:8080?x=1",
            "http://localhost:8080",
        ).forEach { badUrl ->
            assertThrows(IllegalArgumentException::class.java) { McpApiClient.validateLoopbackOrigin(badUrl) }
            val code =
                runCli(
                    arrayOf("mcp", "--url", badUrl),
                    PrintStream(ByteArrayOutputStream()),
                    PrintStream(ByteArrayOutputStream()),
                )
            assertEquals(EXIT_USAGE, code)
        }
    }

    @Test
    fun `mcp framing handles Content-Type-first headers, drains oversized NDJSON lines, refreshes 403 CSRF, and bounds get_result`() {
        // 1) Content-Type preceding Content-Length in LSP header block without spurious -32700 or framing flip
        val initReq = rpcReq(1, "initialize", buildJsonObject { put("protocolVersion", "2024-11-05") })
        val pingReq = rpcReq(2, "ping")
        val contentTypeFirstInput =
            buildString {
                listOf(initReq, pingReq).forEach { msg ->
                    val bytes = msg.toByteArray(UTF_8)
                    append("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n")
                    append("Content-Length: ${bytes.size}\r\n\r\n")
                    append(msg)
                }
            }.toByteArray(UTF_8)
        val ctOut = ByteArrayOutputStream()
        val ctErr = ByteArrayOutputStream()
        val ctExit =
            runCli(
                arrayOf("mcp", "--data-dir", tempDir.resolve("ct-data").toString()),
                PrintStream(ctOut),
                PrintStream(ctErr),
                ByteArrayInputStream(contentTypeFirstInput),
            )
        assertEquals(EXIT_OK, ctExit)
        assertEquals("", ctErr.toString(UTF_8))
        val ctFrames = parseContentLengthFrames(ctOut.toByteArray())
        assertEquals(2, ctFrames.size)
        assertEquals(JsonPrimitive(1), ctFrames[0]["id"])
        assertEquals(JsonPrimitive(2), ctFrames[1]["id"])
        assertTrue("result" in ctFrames[1])

        // 2) Oversized >4 MiB NDJSON line drains up to '\n' and keeps subsequent NDJSON frames aligned
        val oversizedLine = """{"jsonrpc":"2.0","id":1,"method":"ping","pad":"${"x".repeat(4 * 1024 * 1024 + 64)}"}"""
        val colonGarbageLine = "not-a-header: malformed-ndjson"
        val recoveryInput =
            (oversizedLine + "\n" + colonGarbageLine + "\n" + rpcReq(2, "ping") + "\n").toByteArray(UTF_8)
        val recOut = ByteArrayOutputStream()
        val recErr = ByteArrayOutputStream()
        val recExit =
            runCli(
                arrayOf("mcp", "--data-dir", tempDir.resolve("rec-data").toString()),
                PrintStream(recOut),
                PrintStream(recErr),
                ByteArrayInputStream(recoveryInput),
            )
        assertEquals(EXIT_OK, recExit)
        assertTrue(recErr.toString(UTF_8).contains("line exceeds maximum message size"))
        val recResponses =
            recOut
                .toString(UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()
        assertEquals(3, recResponses.size)
        assertEquals(JsonNull, recResponses[0]["id"])
        assertEquals(
            JsonPrimitive(-32700),
            recResponses[0]
                .getValue("error")
                .jsonObject["code"],
        )
        assertEquals(JsonNull, recResponses[1]["id"])
        assertEquals(
            JsonPrimitive(-32700),
            recResponses[1]
                .getValue("error")
                .jsonObject["code"],
        )
        assertEquals(JsonPrimitive(2), recResponses[2]["id"])
        assertEquals(JsonObject(emptyMap()), recResponses[2]["result"])

        // 3) 403 CSRF auto-refresh retry and MAX_MCP_RESULT_BYTES truncation check
        val bootCalls = AtomicInteger()
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        stub.executor = executor
        val largeEvidence =
            buildJsonArray {
                val pad = "v".repeat(6_000)
                repeat(100) { idx ->
                    add(
                        buildJsonObject {
                            put("id", "ev-$idx")
                            put("type", "metric_summary")
                            put("payload", pad)
                        },
                    )
                }
            }
        val largeResult =
            buildJsonObject {
                put("schema_version", "analysis-result.v1")
                put("analysis_mode", "standard")
                put("run_validity", "VALID")
                put("policy_verdict", "PASS")
                put("analysis_coverage", buildJsonObject {})
                put("findings", buildJsonArray {})
                put("evidence", largeEvidence)
            }.toString()
        stub.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            when {
                path == "/api/bootstrap" -> {
                    val seq = bootCalls.incrementAndGet()
                    exchange.responseHeaders.add("Set-Cookie", "ltv_session=sess-$seq; HttpOnly")
                    exchange.respondText(200, """{"csrf_token":"csrf-$seq"}""")
                }
                path == "/api/policies/validate" -> {
                    val csrf = exchange.requestHeaders.getFirst("X-LTV-CSRF")
                    val cookie = exchange.requestHeaders.getFirst("Cookie")
                    if (csrf == "csrf-1") {
                        exchange.respondText(403, """{"error":{"code":"CSRF_INVALID","message":"stale csrf"}}""")
                    } else if (csrf == "csrf-2" && cookie == "ltv_session=sess-2") {
                        exchange.respondText(200, """{"valid":true,"errors":[]}""")
                    } else {
                        exchange.respondText(403, """{"error":{"code":"CSRF_INVALID","message":"unexpected auth"}}""")
                    }
                }
                path.endsWith("/result") -> exchange.respondText(200, largeResult)
                else -> exchange.respondText(404, """{"error":{"code":"NOT_FOUND","message":"not found"}}""")
            }
        }
        stub.start()
        try {
            val api = McpApiClient("http://127.0.0.1:${stub.address.port}")
            val (status, validated) = api.postJson("/api/policies/validate", buildJsonObject {}, allowUnprocessable = true)
            assertEquals(200, status)
            assertEquals(JsonPrimitive(true), validated["valid"])
            assertEquals(2, bootCalls.get())

            val tools = McpTools(api)
            val runId = "jmeter_jtl_csv-${"a".repeat(64)}"
            val analysisId = "b".repeat(64)
            val summaryRes =
                tools.callTool(
                    "get_result",
                    buildJsonObject {
                        put("run_id", runId)
                        put("analysis_id", analysisId)
                        put("include_evidence", false)
                    },
                )
            assertFalse(summaryRes.isError)
            val tooLargeRes =
                tools.callTool(
                    "get_result",
                    buildJsonObject {
                        put("run_id", runId)
                        put("analysis_id", analysisId)
                        put("include_evidence", true)
                        put("limit", 100)
                    },
                )
            assertTrue(tooLargeRes.isError)
            assertEquals(
                JsonPrimitive("MCP_RESULT_TOO_LARGE"),
                tooLargeRes.payload.getValue("error").jsonObject["code"],
            )
        } finally {
            stub.stop(0)
            executor.shutdownNow()
        }
    }

    private fun runMcpCli(
        origin: String,
        requests: List<String>,
    ): Pair<List<JsonObject>, String> {
        val stdinBytes = requests.joinToString("\n", postfix = "\n").toByteArray(UTF_8)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit =
            runCli(
                arrayOf("mcp", "--url", origin),
                PrintStream(stdout),
                PrintStream(stderr),
                ByteArrayInputStream(stdinBytes),
            )
        assertEquals(EXIT_OK, exit)
        val responses =
            stdout
                .toString(UTF_8)
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()
        return responses to stderr.toString(UTF_8)
    }

    private fun setBaseline(
        origin: String,
        runId: String,
        analysisId: String,
    ) {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val boot =
            http.send(
                HttpRequest.newBuilder(URI.create("$origin/api/bootstrap")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        val cookie =
            boot
                .headers()
                .firstValue("set-cookie")
                .orElseThrow()
                .substringBefore(';')
        val csrf =
            Json
                .parseToJsonElement(boot.body())
                .jsonObject
                .getValue("csrf_token")
                .jsonPrimitive.content
        val body = """{"mode":"manual","series":"default","reference":{"run_id":"$runId","analysis_id":"$analysisId"}}"""
        val res =
            http.send(
                HttpRequest
                    .newBuilder(URI.create("$origin/api/baseline"))
                    .header("Origin", origin)
                    .header("Cookie", cookie)
                    .header("X-LTV-CSRF", csrf)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, res.statusCode())
    }

    private fun parseContentLengthFrames(bytes: ByteArray): List<JsonObject> {
        val frames = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < bytes.size) {
            val headerEnd = findCrlfCrlf(bytes, offset)
            assertTrue(headerEnd > offset)
            val header = bytes.copyOfRange(offset, headerEnd).decodeToString()
            val len = header.substringAfter("Content-Length:").trim().toInt()
            val bodyStart = headerEnd + 4
            val body = bytes.copyOfRange(bodyStart, bodyStart + len).decodeToString()
            frames += Json.parseToJsonElement(body).jsonObject
            offset = bodyStart + len
        }
        return frames
    }

    private fun findCrlfCrlf(
        bytes: ByteArray,
        start: Int,
    ): Int {
        for (i in start until bytes.size - 3) {
            if (bytes[i] == '\r'.code.toByte() &&
                bytes[i + 1] == '\n'.code.toByte() &&
                bytes[i + 2] == '\r'.code.toByte() &&
                bytes[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun rpcReq(
        id: Int,
        method: String,
        params: JsonObject? = null,
    ): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }.toString()

    private fun rpcNotif(method: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }.toString()

    private fun callArgs(
        name: String,
        arguments: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }

    private fun List<JsonObject>.byId(id: Int): JsonObject = first { it["id"]?.jsonPrimitive?.int == id }

    private fun JsonObject.toolIsError(): Boolean =
        getValue("result")
            .jsonObject
            .getValue("isError")
            .jsonPrimitive.boolean

    private fun JsonObject.toolPayload(): JsonObject {
        val text =
            getValue("result")
                .jsonObject
                .getValue("content")
                .jsonArray[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun HttpExchange.respondText(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

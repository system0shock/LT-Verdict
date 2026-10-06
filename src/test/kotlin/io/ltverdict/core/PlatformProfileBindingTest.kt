package io.ltverdict.core

import com.sun.net.httpserver.HttpServer
import io.ltverdict.sources.PromqlSource
import io.ltverdict.sources.SourceAcquisition
import io.ltverdict.sources.SourceHttp
import io.ltverdict.sources.SourceProfile
import io.ltverdict.sources.SourceRequest
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.storage.AcceptedInput
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

// Plan P3a: the profile generated for the OpenShift label contract (P0b) and the base platform policy (B-S9) must describe the
// same signals. Snapshots here are acquired by PromqlSource from the committed example profile over canned query_range answers,
// then bound against docs/contracts/policy/v1/examples/valid/platform-base-profile.json.
class PlatformProfileBindingTest {
    @TempDir
    lateinit var tempDir: Path

    private val peakExample = Path.of("docs/contracts/sources/v1/platform-openshift-peak-connections.example.json")
    private val plainExample = Path.of("docs/contracts/sources/v1/platform-openshift-connections.example.json")
    private val peakConfig = Path.of("fixtures/platform/profile-config.peak.example.json")
    private val basePolicy = Path.of("docs/contracts/policy/v1/examples/valid/platform-base-profile.json")

    @Test
    fun `every rule of the base policy binds to every service of the peak example`() {
        val policy = basePolicy()
        val snapshot = acquire(peakProfiles("A"), healthy).snapshot!!.snapshot

        val expansion = expandPlatformRules(policy.policy, snapshot)

        assertEquals(emptyMap<String, String>(), expansion.bindingFailures)
        assertEquals(policy.policy.platformRules.size * policy.policy.platformServices!!.size, expansion.rules.size)
        assertEquals(emptyList<PolicyValidationError>(), validatePlatformBinding(policy.policy, snapshot))
    }

    @Test
    fun `the policy catalog equals the services of the profile configuration and of the snapshot rows the rules read`() {
        val policy = basePolicy().policy
        val config = Json.parseToJsonElement(Files.readString(peakConfig)).jsonObject
        val configured = config.getValue("services").jsonArray.map { it.jsonPrimitive.content }
        val signals = policy.platformRules.map { it.signal }.toSet()
        val snapshot = acquire(peakProfiles("A"), healthy).snapshot!!.snapshot
        val entities =
            snapshot.series
                .filter { it.metric in signals }
                .map { it.entity }
                .toSet()

        assertEquals(configured.toSet(), policy.platformServices!!.toSet())
        assertEquals(configured.toSet(), entities)
    }

    @Test
    fun `the examples carry no rules of their own so a platform policy never meets an SLA rule in the profile`() {
        listOf(peakExample, plainExample).forEach { example ->
            assertTrue(Files.newInputStream(example).use(::readSourceProfiles).all { it.rules.isEmpty() }, example.toString())
        }
    }

    @Test
    fun `healthy arms pass on their own and each arm keeps its own analysis`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "arms.jtl")
            val outcomes = listOf("A", "B", "C").map { arm -> analyze(service, input, arm, healthy) }

            assertEquals(listOf("PASS", "PASS", "PASS"), outcomes.map { it.verdict })
            assertEquals(3, outcomes.map { it.analysisId }.toSet().size)
            assertEquals(
                listOf("A", "B", "C"),
                outcomes.map {
                    it.identity
                        .getValue("resource_arm")
                        .jsonPrimitive.content
                },
            )
            assertEquals(1, outcomes.map { it.identity.getValue("policy_sha256") }.toSet().size)
        }

    @Test
    fun `memory above the limit on one arm fails that arm only`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "memory.jtl")
            val breach = healthy.override("payments-svc.memory_limit_ratio", List(27) { "0.3" } + List(3) { "0.9" })

            val armA = analyze(service, input, "A", healthy)
            val armB = analyze(service, input, "B", breach)

            assertEquals("PASS", armA.verdict)
            assertEquals("FAIL", armB.verdict)
            assertEquals("FAIL", armB.checks.getValue("memory-limit/payments-svc"))
            assertEquals("PASS", armB.checks.getValue("memory-limit/orders-svc"))
            assertNotEquals(armA.analysisId, armB.analysisId)
        }

    @Test
    fun `a service of the catalog without a series is no verdict and never pass`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "service-missing.jtl")
            val ordersOnly =
                peakProfiles("A").map { profile ->
                    profile.copy(queries = profile.queries.filter { it.entity == "orders-svc" })
                }

            val outcome = analyze(service, input, "A", healthy, ordersOnly)

            assertEquals("NO_VERDICT", outcome.verdict)
            assertTrue("RESOURCE_SERIES_NOT_FOUND" in outcome.reasons, outcome.reasons.toString())
        }

    @Test
    fun `a container without a limit leaves the series empty and gives no verdict`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "no-limit.jtl")
            // The guarded expression of P0b answers with no series when a container lacks a limit.
            val lost = healthy.override("payments-svc.memory_limit_ratio", null)

            val outcome = analyze(service, input, "A", lost)

            assertEquals("NO_VERDICT", outcome.verdict)
            assertTrue(outcome.reasons.any { it == "MISSING_RESOURCE_CELLS" || it == "RESOURCE_GAPS" }, outcome.reasons.toString())
        }

    @Test
    fun `one unavailable replica in the window violates the coverage rule`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "replicas.jtl")
            val unavailable = healthy.override("payments-svc.unavailable_replicas", List(30) { if (it == 10) "1" else "0" })

            val outcome = analyze(service, input, "A", unavailable)

            assertEquals("FAIL", outcome.verdict)
            assertEquals("FAIL", outcome.checks.getValue("replicas/payments-svc"))
        }

    @Test
    fun `an SLA rule in the profile conflicts with the platform rules of the policy`() =
        withService { store, service ->
            val input = store.acceptInput(ByteArrayInputStream(loadCsv().encodeToByteArray()), "conflict.jtl")
            val sla =
                ResourceRuleV1(
                    "cpu-high",
                    "orders-svc.cpu_limit_ratio",
                    "ratio",
                    ResourceOperator.GT,
                    BigDecimal("0.8"),
                    1,
                    ResourceRuleEffect.SLA,
                )
            val withRules = peakProfiles("A").map { it.copy(rules = listOf(sla)) }
            val acquisition = acquire(withRules, healthy, input.sha256)

            assertEquals(
                listOf("PLATFORM_RULES_CONFLICT"),
                validatePlatformBinding(basePolicy().policy, acquisition.snapshot!!.snapshot).map { it.code },
            )
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, basePolicy(), resources = acquisition.snapshot))
                }
            assertEquals("PLATFORM_RULES_CONFLICT", failure.message)
        }

    @Test
    fun `a mean where the policy asks for a maximum is an aggregation mismatch`() {
        val plain = Files.newInputStream(plainExample).use(::readSourceProfiles).map { it.copy(arm = "A") }
        val snapshot = acquire(plain, healthy).snapshot!!.snapshot

        val failures = expandPlatformRules(basePolicy().policy, snapshot).bindingFailures

        assertEquals(
            setOf("memory-limit/orders-svc", "memory-limit/payments-svc", "replicas/orders-svc", "replicas/payments-svc"),
            failures.keys,
        )
        assertEquals(setOf("PLATFORM_AGGREGATION_MISMATCH"), failures.values.toSet())
    }

    private fun basePolicy(): PolicyValidation.Valid =
        assertInstanceOf(PolicyValidation.Valid::class.java, Files.newInputStream(basePolicy).use(::validatePolicy))

    private fun peakProfiles(arm: String): List<SourceProfile> =
        Files.newInputStream(peakExample).use(::readSourceProfiles).map { it.copy(arm = arm) }

    private class Answers(
        private val values: Map<String, List<String>?>,
    ) {
        fun override(
            queryId: String,
            series: List<String>?,
        ) = Answers(values + (queryId to series))

        fun of(queryId: String): List<String>? = if (queryId in values) values.getValue(queryId) else default(queryId)

        private fun default(queryId: String): List<String> =
            List(POINTS) {
                when (queryId.substringAfter('.')) {
                    "cpu_limit_ratio", "pod_imbalance" -> "0.1"
                    "memory_limit_ratio" -> "0.3"
                    else -> "0"
                }
            }
    }

    private val healthy = Answers(emptyMap())

    private fun acquire(
        profiles: List<SourceProfile>,
        answers: Answers,
        loadSha256: String = "a".repeat(64),
    ): SourceAcquisition {
        val byExpression =
            profiles
                .flatMap { it.queries }
                .associateBy { it.expression.replace("\$__interval", "${STEP_MS}ms") }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/query_range") { exchange ->
            val expression = queryParameter(exchange.requestURI.rawQuery, "query")
            val series = byExpression[expression]?.let { answers.of(it.id) }
            val result =
                if (series == null) {
                    ""
                } else {
                    val samples = series.mapIndexed { index, value -> "[${(START_MS + STEP_MS * (index + 1)) / 1000},\"$value\"]" }
                    """{"metric":{"namespace":"shop"},"values":[${samples.joinToString(",")}]}"""
                }
            val bytes = """{"status":"success","data":{"resultType":"matrix","result":[$result]}}""".encodeToByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val local =
                profiles.map {
                    it.copy(
                        baseUrl = URI("http://127.0.0.1:${server.address.port}"),
                        governor = it.governor.copy(requestsPerSecond = 1_000.0, burst = 1_000),
                    )
                }
            val request = SourceRequest(local.first().id, START_MS, START_MS + STEP_MS * POINTS, STEP_MS)
            return PromqlSource(local, SourceHttp(local)).acquire(request, loadSha256)
        } finally {
            server.stop(0)
        }
    }

    private fun queryParameter(
        rawQuery: String,
        name: String,
    ): String =
        rawQuery
            .split('&')
            .map { it.substringBefore('=') to it.substringAfter('=') }
            .single { it.first == name }
            .second
            .let { URLDecoder.decode(it, StandardCharsets.UTF_8) }

    private class Outcome(
        val analysisId: String,
        val verdict: String,
        val reasons: List<String>,
        val checks: Map<String, String>,
        val identity: JsonObject,
    )

    private fun analyze(
        service: AnalysisService,
        input: AcceptedInput,
        arm: String,
        answers: Answers,
        profiles: List<SourceProfile> = peakProfiles(arm),
    ): Outcome {
        val acquisition = acquire(profiles.map { it.copy(arm = arm) }, answers, input.sha256)
        val outcome = service.analyze(AnalysisRequest(input, basePolicy(), resources = acquisition.snapshot))
        val result = Json.parseToJsonElement(outcome.canonicalResult.decodeToString()).jsonObject
        val checks =
            result
                .getValue("evidence")
                .jsonArray
                .map { it.jsonObject }
                .filter { it.getValue("type").jsonPrimitive.content == "resource_policy_check" }
                .associate { it.getValue("rule_id").jsonPrimitive.content to it.getValue("status").jsonPrimitive.content }
        return Outcome(
            outcome.analysisId,
            result.getValue("policy_verdict").jsonPrimitive.content,
            result
                .getValue("analysis_coverage")
                .jsonObject
                .getValue("reasons")
                .jsonArray
                .map { it.jsonPrimitive.content },
            checks,
            Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject,
        )
    }

    private fun withService(block: (RunBundleStore, AnalysisService) -> Unit) {
        DataDirectory.open(tempDir.resolve("data-${System.nanoTime()}")).use { directory ->
            val store = RunBundleStore(directory)
            block(store, AnalysisService(store, EngineConfig()))
        }
    }

    private fun loadCsv(): String {
        val rows = List(40) { index -> "${START_MS + index * 1_000L},${if (index == 39) 1_000 else 500},request,true" }
        return (listOf("timeStamp,elapsed,label,success") + rows).joinToString("\n", postfix = "\n")
    }

    private companion object {
        const val START_MS = 1_767_225_600_000L
        const val STEP_MS = 1_000L
        const val POINTS = 30
    }
}

package io.ltverdict.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PodViewTest {
    @Test
    fun `valid example is canonical sorted and keeps immutable raw bytes`() {
        val raw = Files.readAllBytes(EXAMPLES.resolve("valid/two-pods.json"))

        val valid = valid(raw)

        assertEquals("A", valid.view.arm)
        assertEquals(10_000L, valid.view.startEpochMillis)
        assertEquals(20_000L, valid.view.stepMillis)
        assertEquals(2, valid.view.columnCount)
        assertEquals(PodViewSelectionKind.ALL, valid.view.coverage.selection.kind)
        assertEquals(listOf("orders-svc-6b2d8-a1", "orders-svc-6b2d8-a2"), valid.view.pods.map(PodViewPod::pod))
        assertEquals(listOf("r1", "r2", "r3"), valid.view.rows.map(PodViewRow::id))
        assertEquals(listOf(BigDecimal("0.62"), null), valid.view.rows[0].values)
        assertNull(valid.view.rows[2].container)
        assertEquals(64, valid.canonicalSha256.length)
        assertNotEquals(sha256Hex(raw), valid.canonicalSha256)
        val exposed = valid.rawBytes()
        exposed[0] = 0
        assertArrayEquals(raw, valid.rawBytes())
    }

    @Test
    fun `second valid example has no arm a real timestamp and a reduced coverage`() {
        val valid = valid(Files.readAllBytes(EXAMPLES.resolve("valid/worst-by-metric.json")))

        assertNull(valid.view.arm)
        assertEquals(1_767_225_600_000L, valid.view.startEpochMillis)
        assertEquals(PodViewSelectionKind.WORST_BY_METRIC, valid.view.coverage.selection.kind)
        assertEquals("openshift_container_cpu_limit_ratio", valid.view.coverage.selection.metric)
        assertEquals(1, valid.view.coverage.selection.limit)
        assertEquals(300, valid.view.coverage.podsObservedTotal)
        assertEquals(1, valid.view.coverage.podsIncluded)
        assertEquals(ResourceAggregation.INTERVAL_MAX, valid.view.rows[0].aggregation)
    }

    @Test
    fun `canonical hash ignores key order row order pod order and number spelling`() {
        val base = valid(example().encodeToByteArray())
        val shuffled = valid(SHUFFLED.encodeToByteArray())
        val podsSwapped =
            example().replace(
                POD_A1_LINE + "\n" + POD_A2_LINE,
                POD_A2_LINE.trimEnd(',') + ",\n" + POD_A1_LINE.trimEnd(','),
            )

        assertNotEquals(example(), podsSwapped)
        assertEquals(base.canonicalSha256, shuffled.canonicalSha256)
        assertEquals(base.canonicalSha256, valid(podsSwapped.encodeToByteArray()).canonicalSha256)
        assertEquals(listOf("r1", "r2", "r3"), shuffled.view.rows.map(PodViewRow::id))
        assertNotEquals(
            base.canonicalSha256,
            valid(example().replace("\"arm\": \"A\"", "\"arm\": \"B\"").encodeToByteArray()).canonicalSha256,
        )
        assertNotEquals(base.canonicalSha256, valid(example().replace("[0.31, 0.33]", "[0.31, 0.34]").encodeToByteArray()).canonicalSha256)
    }

    @Test
    fun `rows are ordered by UTF-8 bytes of service pod container and metric with a null container first`() {
        val bmp = "\uFF5E"
        val supplementary = "\uD83D\uDE00"
        val document =
            example()
                .replace(
                    "\"name\": \"app\", \"role\": \"app\" }, { \"name\": \"istio-proxy\"",
                    "\"name\": \"$supplementary\", \"role\": \"app\" }, { \"name\": \"$bmp\"",
                ).replace(
                    "\"container\": \"app\", \"metric\": \"openshift_container_memory_limit_ratio\"",
                    "\"container\": \"$supplementary\", \"metric\": \"openshift_container_memory_limit_ratio\"",
                ).replace("\"container\": \"istio-proxy\"", "\"container\": \"$bmp\"")

        val view = valid(document.encodeToByteArray()).view

        assertEquals(listOf(bmp, supplementary), view.pods[0].containers.map(PodViewContainer::name))
        assertEquals(listOf("r2", "r1", "r3"), view.rows.map(PodViewRow::id))
        val withNull = valid(example().encodeToByteArray()).view
        assertEquals(listOf("r1", "r2", "r3"), withNull.rows.map(PodViewRow::id))
        val nullFirst =
            example().replace(
                "\"pod\": \"orders-svc-6b2d8-a2\", \"container\": null",
                "\"pod\": \"orders-svc-6b2d8-a1\", \"container\": null",
            )
        assertEquals(
            "r3",
            valid(nullFirst.encodeToByteArray())
                .view.rows
                .first()
                .id,
        )
    }

    @Test
    fun `every invalid example is rejected with its code and pointer`() {
        val expected =
            mapOf(
                "unknown-field" to (INVALID to "/unexpected"),
                "column-count-241" to (LIMIT to "/column_count"),
                "values-too-short" to (INVALID to "/rows/0/values"),
                "number-exponent" to (INVALID to "/rows/0/values/0"),
                "duplicate-pod" to (DUPLICATE to "/pods/1/pod"),
                "duplicate-row" to (DUPLICATE to "/rows/1"),
                "unknown-pod" to (UNKNOWN to "/rows/0/pod"),
                "unknown-container" to (UNKNOWN to "/rows/1/container"),
                "empty-pod-name" to (INVALID to "/pods/0/pod"),
                "coverage-pods-mismatch" to (INVALID to "/coverage"),
                "worst-without-metric" to (INVALID to "/coverage/selection"),
                "aggregation-instant" to (INVALID to "/rows/0/aggregation"),
            )
        val files =
            Files.list(EXAMPLES.resolve("invalid")).use { stream ->
                stream.map { it.fileName.toString().removeSuffix(".json") }.sorted().toList()
            }

        assertEquals(expected.keys.sorted(), files)
        expected.forEach { (name, codeAndPointer) ->
            assertInvalid(Files.readAllBytes(EXAMPLES.resolve("invalid/$name.json")), codeAndPointer.first, codeAndPointer.second, name)
        }
    }

    @Test
    fun `strict boundary rejects malformed duplicate key bad types and bad hashes`() {
        val cases =
            listOf(
                Case("", INVALID, ""),
                Case("{", INVALID, ""),
                Case(example().replace("\"step_ms\": 20000", "\"step_ms\": 20000, \"\\u0073tep_ms\": 20000"), INVALID, "/step_ms"),
                Case(example().replace("\"pod-view.v1\"", "\"pod-view.v2\""), INVALID, "/schema_version"),
                Case(example().substringBefore("\"pods\"") + "\"pods\": 5, \"rows\": [] }", INVALID, "/pods"),
                Case(
                    example().replace("\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"", "\"ABC\""),
                    INVALID,
                    "/load_input_sha256",
                ),
                Case(
                    example().replace("\"resource_snapshot_sha256\": \"fedcba", "\"resource_snapshot_sha256\": \"FEDCBA"),
                    INVALID,
                    "/resource_snapshot_sha256",
                ),
                Case(example().replace("\"arm\": \"A\"", "\"arm\": \"\""), INVALID, "/arm"),
                Case(example().replace("\"arm\": \"A\"", "\"arm\": null"), INVALID, "/arm"),
                Case(example().replace("\"arm\": \"A\"", "\"arm\": \"${"a".repeat(129)}\""), LIMIT, "/arm"),
                Case(example().replace("\"start_epoch_ms\": 10000", "\"start_epoch_ms\": -1"), INVALID, "/start_epoch_ms"),
                Case(example().replace("\"start_epoch_ms\": 10000", "\"start_epoch_ms\": 253402300799999"), INVALID, "/step_ms"),
                Case(example().replace("\"start_epoch_ms\": 10000", "\"start_epoch_ms\": 1.5"), INVALID, "/start_epoch_ms"),
                Case(example().replace("\"start_epoch_ms\": 10000", "\"start_epoch_ms\": 1e4"), INVALID, "/start_epoch_ms"),
                Case(example().replace("\"start_epoch_ms\": 10000", "\"start_epoch_ms\": \"10000\""), INVALID, "/start_epoch_ms"),
                Case(example().replace("\"step_ms\": 20000", "\"step_ms\": 20500"), INVALID, "/step_ms"),
                Case(example().replace("\"step_ms\": 20000", "\"step_ms\": 0"), INVALID, "/step_ms"),
                Case(example().replace("\"column_count\": 2", "\"column_count\": 0"), INVALID, "/column_count"),
                Case(example().replace("\"column_count\": 2,", ""), INVALID, "/column_count"),
                Case(example().replace("\"role\": \"sidecar\"", "\"role\": \"proxy\""), INVALID, "/pods/0/containers/1/role"),
                Case(
                    example().replace(
                        "\"service\": \"orders-svc\", \"containers\": [{ \"name\": \"app\", \"role\": \"app\" }] }",
                        "\"containers\": [] }",
                    ),
                    INVALID,
                    "/pods/1/service",
                ),
                Case(
                    example().replace(
                        "\"pod\": \"orders-svc-6b2d8-a1\", \"service\": \"orders-svc\"",
                        "\"pod\": \"orders\\u0007\", \"service\": \"orders-svc\"",
                    ),
                    INVALID,
                    "/pods/0/pod",
                ),
                Case(
                    example().replace(
                        "\"pod\": \"orders-svc-6b2d8-a1\", \"service\": \"orders-svc\"",
                        "\"pod\": \"orders\\uD800\", \"service\": \"orders-svc\"",
                    ),
                    INVALID,
                    "/pods/0/pod",
                ),
                Case(
                    example().replace(
                        "\"service\": \"orders-svc\", \"containers\": [{ \"name\": \"app\", \"role\": \"app\" }, ",
                        "\"service\": \"\", \"containers\": [{ \"name\": \"app\", \"role\": \"app\" }, ",
                    ),
                    INVALID,
                    "/pods/0/service",
                ),
                Case(example().replace("\"id\": \"r3\"", "\"id\": \"\""), INVALID, "/rows/2/id"),
                Case(
                    example().replace(
                        "\"unit\": \"ratio\", \"aggregation\": \"interval_mean\", \"values\": [0.05",
                        "\"unit\": \"\", \"aggregation\": \"interval_mean\", \"values\": [0.05",
                    ),
                    INVALID,
                    "/rows/2/unit",
                ),
                Case(example().replace("\"container\": null, \"metric\"", "\"metric\""), INVALID, "/rows/2/container"),
                Case(example().replace("\"values\": [0.62, null]", "\"values\": [0.62, null, 0.1]"), INVALID, "/rows/0/values"),
                Case(example().replace("\"values\": [0.62, null]", "\"values\": [\"0.62\", null]"), INVALID, "/rows/0/values/0"),
                Case(example().replace("\"values\": [0.62, null]", "\"values\": [true, null]"), INVALID, "/rows/0/values/0"),
                Case(example().replace("\"values\": [0.62, null]", "\"values\": [0.62, 1E0]"), INVALID, "/rows/0/values/1"),
                Case(
                    example().replace("\"values\": [0.62, null]", "\"values\": [0.62, 12345678901234567890123]"),
                    INVALID,
                    "/rows/0/values/1",
                ),
                Case(example().replace("\"values\": [0.62, null]", "\"values\": [0.62, 0.12345678901]"), INVALID, "/rows/0/values/1"),
                Case(
                    example().replace("\"metric\": \"openshift_pod_imbalance\"", "\"metric\": \"${"m".repeat(129)}\""),
                    LIMIT,
                    "/rows/2/metric",
                ),
                Case(
                    example().replace(
                        "\"pod\": \"orders-svc-6b2d8-a1\", \"service\": \"orders-svc\"",
                        "\"pod\": \"${"p".repeat(254)}\", \"service\": \"orders-svc\"",
                    ),
                    LIMIT,
                    "/pods/0/pod",
                ),
                Case(deeplyNested(), LIMIT, ""),
            )

        cases.forEach { assertInvalid(it.text.encodeToByteArray(), it.code, it.pointer, it.text.take(200)) }
    }

    @Test
    fun `value tokens of 12 bytes are accepted and one real timestamp is accepted`() {
        val twelve = example().replace("\"values\": [0.62, null]", "\"values\": [-0.123456789, 0.1234567890]")

        val valid = valid(twelve.encodeToByteArray())

        assertEquals(listOf(BigDecimal("-0.123456789"), BigDecimal("0.1234567890")), valid.view.rows[0].values)
    }

    @Test
    fun `duplicate ids duplicate container names and aggregation spellings are decided by code`() {
        assertInvalid(
            example().replace("\"id\": \"r2\"", "\"id\": \"r1\"").encodeToByteArray(),
            DUPLICATE,
            "/rows/1/id",
        )
        assertInvalid(
            example()
                .replace(
                    "{ \"name\": \"istio-proxy\", \"role\": \"sidecar\" }",
                    "{ \"name\": \"app\", \"role\": \"sidecar\" }",
                ).encodeToByteArray(),
            DUPLICATE,
            "/pods/0/containers/1/name",
        )
        assertInvalid(
            example()
                .replace(
                    "\"aggregation\": \"interval_mean\", \"values\": [0.62",
                    "\"aggregation\": \"Interval_Mean\", \"values\": [0.62",
                ).encodeToByteArray(),
            INVALID,
            "/rows/0/aggregation",
        )
        ResourceAggregation.entries.forEach { aggregation ->
            valid(
                example()
                    .replace(
                        "\"aggregation\": \"interval_mean\", \"values\": [0.62",
                        "\"aggregation\": \"${aggregation.wireName}\", \"values\": [0.62",
                    ).encodeToByteArray(),
            )
        }
    }

    @Test
    fun `coverage is honest about reduced reach`() {
        assertInvalid(
            example().replace("\"pods_observed_total\": 2", "\"pods_observed_total\": 3").encodeToByteArray(),
            INVALID,
            "/coverage",
        )
        assertInvalid(
            example().replace("\"rows_observed_total\": 3", "\"rows_observed_total\": 4").encodeToByteArray(),
            INVALID,
            "/coverage",
        )
        assertInvalid(example().replace("\"rows_included\": 3", "\"rows_included\": 2").encodeToByteArray(), INVALID, "/coverage")
        assertInvalid(
            example().replace("\"pods_observed_total\": 2", "\"pods_observed_total\": 1").encodeToByteArray(),
            INVALID,
            "/coverage",
        )
        assertInvalid(
            example().replace("\"pods_observed_total\": 2", "\"pods_observed_total\": 1000001").encodeToByteArray(),
            INVALID,
            "/coverage/pods_observed_total",
        )
        assertInvalid(
            example().replace("\"pods_observed_total\": 2", "\"pods_observed_total\": -1").encodeToByteArray(),
            INVALID,
            "/coverage/pods_observed_total",
        )
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", "{ \"kind\": \"ALL\", \"limit\": 1 }").encodeToByteArray(),
            INVALID,
            "/coverage/selection/limit",
        )
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", "{ \"kind\": \"BEST\" }").encodeToByteArray(),
            INVALID,
            "/coverage/selection/kind",
        )
        val worst = "{ \"kind\": \"WORST_BY_METRIC\", \"metric\": \"openshift_pod_imbalance\", \"limit\": LIMIT }"
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "0")).encodeToByteArray(),
            INVALID,
            "/coverage/selection",
        )
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "257")).encodeToByteArray(),
            INVALID,
            "/coverage/selection",
        )
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "24.5")).encodeToByteArray(),
            INVALID,
            "/coverage/selection/limit",
        )
        assertInvalid(
            example().replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "1, \"extra\": 1")).encodeToByteArray(),
            INVALID,
            "/coverage/selection/extra",
        )
        val reduced =
            example()
                .replace(
                    "\"pods_observed_total\": 2",
                    "\"pods_observed_total\": 9",
                ).replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "256"))
        assertEquals(9, valid(reduced.encodeToByteArray()).view.coverage.podsObservedTotal)
        val equal = example().replace("{ \"kind\": \"ALL\" }", worst.replace("LIMIT", "1"))
        assertEquals(
            PodViewSelectionKind.WORST_BY_METRIC,
            valid(equal.encodeToByteArray())
                .view.coverage.selection.kind,
        )
    }

    @Test
    fun `limits are rejected without truncation`() {
        assertInvalid(generated(pods = 257, services = 64, metrics = 0).encodeToByteArray(), LIMIT, "/pods")
        assertInvalid(generated(pods = 65, services = 65, metrics = 0).encodeToByteArray(), LIMIT, "/pods")
        assertInvalid(generated(pods = 1, services = 1, metrics = 2561, columns = 1).encodeToByteArray(), LIMIT, "/rows")
        assertInvalid(generated(pods = 1, services = 1, metrics = 0, containers = 5).encodeToByteArray(), LIMIT, "/pods/0/containers")
        assertInvalid(generated(pods = 1, services = 1, metrics = 0, columns = 241).encodeToByteArray(), LIMIT, "/column_count")
        assertInvalid(ByteArray(MAX_POD_VIEW_BYTES + 1), LIMIT, "")
        assertInvalid(example().encodeToByteArray(), LIMIT, "", maxBytes = 100)
        assertEquals(12 * 1024 * 1024, MAX_POD_VIEW_BYTES)
    }

    @Test
    fun `the limits themselves are accepted`() {
        val text = generated(pods = 256, services = 64, metrics = 10, columns = 240, containers = 4)
        assertEquals(true, text.length < MAX_POD_VIEW_BYTES)

        val valid = valid(text.encodeToByteArray())

        assertEquals(256, valid.view.pods.size)
        assertEquals(2560, valid.view.rows.size)
        assertEquals(
            240,
            valid.view.rows
                .first()
                .values.size,
        )
        assertEquals(2560, valid.view.coverage.rowsIncluded)
    }

    @Test
    fun `a file close to the byte limit with 12 byte tokens is accepted`() {
        val text = generated(pods = 256, services = 64, metrics = 10, columns = 240, containers = 4, cellText = "-0.123456789")
        val bytes = text.encodeToByteArray()
        assertEquals(true, bytes.size > 7 * 1024 * 1024 && bytes.size < MAX_POD_VIEW_BYTES)

        val valid = valid(bytes)

        assertEquals(2560, valid.view.rows.size)
        assertEquals(
            BigDecimal("-0.123456789"),
            valid.view.rows
                .last()
                .values
                .last(),
        )
    }

    @Test
    fun `invalid input reports one error and an unreadable stream is invalid`() {
        val invalid =
            assertInstanceOf(PodViewValidation.Invalid::class.java, validatePodView(ByteArrayInputStream("not json".encodeToByteArray())))
        assertEquals(1, invalid.errors.size)
        assertEquals("POD_VIEW_INVALID", invalid.errors.single().code)

        val bytes = byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)
        assertInvalid(bytes, INVALID, "")
        val broken =
            object : java.io.InputStream() {
                override fun read(): Int = throw java.io.IOException("boom")
            }
        val failure = assertInstanceOf(PodViewValidation.Invalid::class.java, validatePodView(broken))
        assertEquals("POD_VIEW_INVALID", failure.errors.single().code)
    }

    private data class Case(
        val text: String,
        val code: String,
        val pointer: String,
    )

    private fun valid(raw: ByteArray): PodViewValidation.Valid {
        val result = validatePodView(ByteArrayInputStream(raw))
        if (result is PodViewValidation.Invalid) throw AssertionError("unexpected errors: ${result.errors}")
        return assertInstanceOf(PodViewValidation.Valid::class.java, result)
    }

    private fun assertInvalid(
        raw: ByteArray,
        code: String,
        pointer: String,
        label: String = "",
        maxBytes: Int = MAX_POD_VIEW_BYTES,
    ) {
        val result = validatePodView(ByteArrayInputStream(raw), maxBytes)
        val invalid = assertInstanceOf(PodViewValidation.Invalid::class.java, result, label)
        assertEquals(listOf(code to pointer), invalid.errors.map { it.code to it.jsonPointer }, label)
    }

    private fun example(): String = Files.readString(EXAMPLES.resolve("valid/two-pods.json")).replace("\r\n", "\n")

    private fun deeplyNested(): String =
        example().replace(
            "\"selection\": { \"kind\": \"ALL\" }",
            "\"selection\": " + "[".repeat(9) + "]".repeat(9),
        )

    private fun generated(
        pods: Int,
        services: Int,
        metrics: Int,
        columns: Int = 2,
        containers: Int = 1,
        cellText: String = "0.5",
    ): String {
        val cells = (1..columns).joinToString(",") { cellText }
        val podText =
            (0 until pods).joinToString(",") { pod ->
                val names = (0 until containers).joinToString(",") { "{\"name\":\"c$it\",\"role\":\"app\"}" }
                "{\"pod\":\"pod-$pod\",\"service\":\"svc-${pod % services}\",\"containers\":[$names]}"
            }
        val rowCount = if (metrics <= 10) pods * metrics else metrics
        val rowText =
            (0 until rowCount).joinToString(",") { row ->
                val pod = if (metrics <= 10) row / metrics else 0
                val metric = if (metrics <= 10) row % metrics else row
                "{\"id\":\"r$row\",\"pod\":\"pod-$pod\",\"container\":\"c0\",\"metric\":\"m$metric\",\"unit\":\"ratio\",\"aggregation\":\"interval_mean\",\"values\":[$cells]}"
            }
        return "{\"schema_version\":\"pod-view.v1\",\"load_input_sha256\":\"$HASH\",\"resource_snapshot_sha256\":\"$HASH\"," +
            "\"start_epoch_ms\":1767225600000,\"step_ms\":60000,\"column_count\":$columns," +
            "\"coverage\":{\"pods_observed_total\":$pods,\"pods_included\":$pods," +
            "\"rows_observed_total\":$rowCount,\"rows_included\":$rowCount," +
            "\"selection\":{\"kind\":\"ALL\"}},\"pods\":[$podText],\"rows\":[$rowText]}"
    }

    private companion object {
        const val INVALID = "POD_VIEW_INVALID"
        const val LIMIT = "POD_VIEW_LIMIT_EXCEEDED"
        const val DUPLICATE = "POD_VIEW_DUPLICATE_ROW"
        const val UNKNOWN = "POD_VIEW_UNKNOWN_POD"
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val POD_A1_LINE =
            "    { \"pod\": \"orders-svc-6b2d8-a1\", \"service\": \"orders-svc\", \"containers\": [{ \"name\": \"app\", \"role\": \"app\" }, { \"name\": \"istio-proxy\", \"role\": \"sidecar\" }] },"
        const val POD_A2_LINE =
            "    { \"pod\": \"orders-svc-6b2d8-a2\", \"service\": \"orders-svc\", \"containers\": [{ \"name\": \"app\", \"role\": \"app\" }] }"
        val SHUFFLED =
            """
            {
              "rows": [
                { "values": [0.05, 0.04], "aggregation": "interval_mean", "unit": "ratio", "metric": "openshift_pod_imbalance", "container": null, "pod": "orders-svc-6b2d8-a2", "id": "r3" },
                { "values": [0.31, 0.33], "aggregation": "interval_mean", "unit": "ratio", "metric": "openshift_container_memory_limit_ratio", "container": "istio-proxy", "pod": "orders-svc-6b2d8-a1", "id": "r2" },
                { "values": [0.620, null], "aggregation": "interval_mean", "unit": "ratio", "metric": "openshift_container_memory_limit_ratio", "container": "app", "pod": "orders-svc-6b2d8-a1", "id": "r1" }
              ],
              "pods": [
                { "containers": [{ "role": "app", "name": "app" }], "service": "orders-svc", "pod": "orders-svc-6b2d8-a2" },
                { "containers": [{ "role": "sidecar", "name": "istio-proxy" }, { "role": "app", "name": "app" }], "service": "orders-svc", "pod": "orders-svc-6b2d8-a1" }
              ],
              "coverage": {
                "selection": { "kind": "ALL" },
                "rows_included": 3, "rows_observed_total": 3, "pods_included": 2, "pods_observed_total": 2
              },
              "column_count": 2,
              "step_ms": 20000,
              "start_epoch_ms": 10000,
              "resource_snapshot_sha256": "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
              "load_input_sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "schema_version": "pod-view.v1",
              "arm": "A"
            }
            """.trimIndent()
        val EXAMPLES: Path = Path.of("docs/contracts/pod-view/v1/examples")
    }
}

package io.ltverdict.core

import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class PodViewBindingTest {
    private val snapshot = snapshot("docs/contracts/resources/v1/examples/valid/arm.json")

    @Test
    fun `a pod view bound to the input and the snapshot has no binding errors`() {
        assertEquals(emptyList<PolicyValidationError>(), bind(podView()))
        assertEquals(emptyList<PolicyValidationError>(), bind(podView(step = 30_000, columns = 2)))
        assertEquals(emptyList<PolicyValidationError>(), bind(podView(step = 10_000, columns = 4)))
    }

    @Test
    fun `another load input is refused`() {
        assertSingle("POD_VIEW_INPUT_MISMATCH", "/load_input_sha256", bind(podView(), loadInputSha256 = "f".repeat(64)))
        assertSingle("POD_VIEW_INPUT_MISMATCH", "/load_input_sha256", bind(podView(loadHash = "f".repeat(64))))
    }

    @Test
    fun `another snapshot is refused by the semantic hash`() {
        assertSingle("POD_VIEW_SNAPSHOT_MISMATCH", "/resource_snapshot_sha256", bind(podView(snapshotHash = "e".repeat(64))))
    }

    @Test
    fun `arm must match the snapshot arm and both may be absent`() {
        assertSingle("POD_VIEW_ARM_MISMATCH", "/arm", bind(podView(arm = "B")))
        assertSingle("POD_VIEW_ARM_MISMATCH", "/arm", bind(podView(arm = null)))

        val noArm = snapshot("docs/contracts/resources/v1/examples/valid/basic.json")
        assertSingle("POD_VIEW_ARM_MISMATCH", "/arm", bind(podView(snapshotHash = noArm.semanticSha256), snapshot = noArm))
        assertEquals(
            emptyList<PolicyValidationError>(),
            bind(podView(snapshotHash = noArm.semanticSha256, arm = null), snapshot = noArm),
        )
    }

    @Test
    fun `the grid must start with the snapshot and cover all of it`() {
        assertSingle("POD_VIEW_GRID_MISMATCH", "/start_epoch_ms", bind(podView(start = SNAPSHOT_START + 10_000)))
        assertSingle("POD_VIEW_GRID_MISMATCH", "/step_ms", bind(podView(step = 15_000, columns = 3)))
        assertSingle("POD_VIEW_GRID_MISMATCH", "/step_ms", bind(podView(step = 5_000, columns = 8)))
        assertSingle("POD_VIEW_GRID_MISMATCH", "/column_count", bind(podView(columns = 3)))
        assertSingle("POD_VIEW_GRID_MISMATCH", "/column_count", bind(podView(columns = 1)))
        // 4 points of 10 s under a 30 s step: the last column is partial, so two columns are required.
        assertSingle("POD_VIEW_GRID_MISMATCH", "/column_count", bind(podView(step = 30_000, columns = 1)))
        assertSingle("POD_VIEW_GRID_MISMATCH", "/column_count", bind(podView(step = 30_000, columns = 3)))
    }

    @Test
    fun `canonical bytes hash to the canonical sha256 and validate again`() {
        val valid = podView()
        val bytes = valid.canonicalBytes()

        assertEquals(valid.canonicalSha256, sha256Hex(bytes))
        val again = assertInstanceOf(PodViewValidation.Valid::class.java, validatePodView(ByteArrayInputStream(bytes)))
        assertEquals(valid.canonicalSha256, again.canonicalSha256)
        assertArrayEquals(bytes, again.canonicalBytes())
    }

    @Test
    fun `identity without a pod view keeps its committed bytes and has no pod view fields`() {
        val expected = Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity-resources.v1.json"))
        val policy =
            validatePolicy(
                ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/slice1/identity/policy.canonical.json"))),
            ) as PolicyValidation.Valid
        val basic = snapshot("docs/contracts/resources/v1/examples/valid/basic.json")

        val actual = analysisIdentity(input, policy, EngineConfig(), resources = basic)

        assertArrayEquals(expected, actual)
        assertFalse(identityJson(actual).containsKey("pod_view_sha256"))
        assertFalse(identityJson(actual).containsKey("pod_view_version"))
    }

    @Test
    fun `identity with a pod view adds only the two conditional fields`() {
        val view = podView()
        val without = identityJson(analysisIdentity(input, null, EngineConfig(), resources = snapshot))
        val with = identityJson(analysisIdentity(input, null, EngineConfig(), resources = snapshot, podView = view))

        assertEquals(view.canonicalSha256, with.getValue("pod_view_sha256").jsonPrimitive.content)
        assertEquals("pod-view.v1", with.getValue("pod_view_version").jsonPrimitive.content)
        assertEquals(setOf("pod_view_sha256", "pod_view_version"), with.keys - without.keys)
        // The comparability key is built from these fields only (ADR 0020, section 5): they must not change.
        listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")
            .forEach { field -> assertEquals(without.getValue(field), with.getValue(field), field) }
        assertEquals(without["resource_arm"], with["resource_arm"])
    }

    @Test
    fun `the synthetic producer set validates and binds to its own snapshot`() {
        val dir = "fixtures/platform/pod-view-synthetic"
        val resources = snapshot("$dir/resource-snapshot.json")
        val view =
            assertInstanceOf(
                PodViewValidation.Valid::class.java,
                validatePodView(ByteArrayInputStream(Files.readAllBytes(Path.of("$dir/pod-view.json")))),
            )

        assertEquals("B", resources.snapshot.arm)
        assertEquals(emptyList<PolicyValidationError>(), bind(view, view.view.loadInputSha256, resources))
    }

    private fun bind(
        view: PodViewValidation.Valid,
        loadInputSha256: String = HASH,
        snapshot: ResourceValidation.Valid = this.snapshot,
    ): List<PolicyValidationError> = validatePodViewBinding(view, loadInputSha256, snapshot)

    private fun podView(
        loadHash: String = HASH,
        snapshotHash: String = snapshot.semanticSha256,
        arm: String? = "A",
        start: Long = SNAPSHOT_START,
        step: Long = 20_000,
        columns: Int = 2,
    ): PodViewValidation.Valid = validPodView(podViewTestJson(loadHash, snapshotHash, arm, start, step, columns))

    private fun assertSingle(
        code: String,
        pointer: String,
        errors: List<PolicyValidationError>,
    ) {
        assertEquals(listOf(code to pointer), errors.map { it.code to it.jsonPointer })
    }

    private fun identityJson(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private val input =
        AcceptedInput(
            runId = "jmeter_jtl_csv-$HASH",
            sourceType = SourceType.JMETER_CSV,
            sha256 = HASH,
            sizeBytes = 1,
            originalFilename = "input.jtl",
            path = Path.of("unused"),
        )

    private fun snapshot(path: String): ResourceValidation.Valid =
        assertInstanceOf(
            ResourceValidation.Valid::class.java,
            validateResourceSnapshot(ByteArrayInputStream(Files.readAllBytes(Path.of(path)))),
        )

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val SNAPSHOT_START = 1_767_225_600_000L
    }
}

/** A one-pod pod-view.v1 document with the given grid, for the binding and job-input tests of slice P2b. */
internal fun podViewTestJson(
    loadHash: String,
    snapshotHash: String,
    arm: String?,
    start: Long,
    step: Long,
    columns: Int,
): String {
    val armField = if (arm == null) "" else """"arm":"$arm","""
    val values = List(columns) { "0.5" }.joinToString(",")
    return """{"schema_version":"pod-view.v1","load_input_sha256":"$loadHash","resource_snapshot_sha256":"$snapshotHash",$armField""" +
        """"start_epoch_ms":$start,"step_ms":$step,"column_count":$columns,""" +
        """"coverage":{"pods_observed_total":1,"pods_included":1,"rows_observed_total":1,"rows_included":1,"selection":{"kind":"ALL"}},""" +
        """"pods":[{"pod":"orders-1","service":"orders","containers":[{"name":"app","role":"app"}]}],""" +
        """"rows":[{"id":"r1","pod":"orders-1","container":"app","metric":"container_memory_ratio","unit":"ratio",""" +
        """"aggregation":"interval_mean","values":[$values]}]}"""
}

internal fun validPodView(json: String): PodViewValidation.Valid =
    assertInstanceOf(PodViewValidation.Valid::class.java, validatePodView(ByteArrayInputStream(json.encodeToByteArray())))

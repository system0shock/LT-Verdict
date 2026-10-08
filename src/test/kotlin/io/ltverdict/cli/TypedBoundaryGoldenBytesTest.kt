package io.ltverdict.cli

import io.ltverdict.core.sha256Hex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

/**
 * W2.1 characterization: the bytes of `analysis-result.json` and `identity.json` written by a CLI run of every fixture
 * match the files captured from `origin/main` BEFORE the typed-boundary refactor. Regenerate only on purpose, with
 * `LTV_UPDATE_TYPED_BOUNDARY=1` (this is a contract change: `analysis_id` moves).
 */
class TypedBoundaryGoldenBytesTest {
    @TempDir
    lateinit var tempDir: Path

    private data class Case(
        val name: String,
        val input: String,
        val policy: String?,
        val resources: Boolean = false,
    )

    private val cases =
        listOf(
            Case("csv-no-policy", "jmeter/csv-5.6.3/input.jtl", null),
            Case("csv-pass", "jmeter/csv-5.6.3/input.jtl", "policies/pass.json"),
            Case("csv-fail", "jmeter/csv-5.6.3/input.jtl", "policies/fail.json"),
            Case("csv-missing-transaction", "jmeter/csv-5.6.3/input.jtl", "policies/missing-transaction.json"),
            Case("xml-pass", "jmeter/xml-5.6.3/input.xml", "policies/pass.json"),
            Case("gatling-text-no-policy", "gatling/text-3.12.0/simulation.log", null),
            Case("gatling-binary-pass", "gatling/binary-3.15.1/simulation.log", "policies/pass.json"),
            Case("csv-resources-arm-pass", "../slice0/jmeter.jtl", "policies/pass.json", resources = true),
        )

    @Test
    fun `result and identity bytes of every fixture run equal the pre-refactor goldens`() {
        val update = System.getenv("LTV_UPDATE_TYPED_BOUNDARY") == "1"
        cases.forEach { case ->
            val dataDir = tempDir.resolve(case.name)
            val args =
                buildList {
                    add("analyze")
                    add(Path.of("fixtures/slice1").resolve(case.input).toString())
                    case.policy?.let { addAll(listOf("--policy", Path.of("fixtures/slice1").resolve(it).toString())) }
                    if (case.resources) {
                        val load = Files.readAllBytes(Path.of("fixtures/slice1").resolve(case.input))
                        val snapshot = tempDir.resolve("${case.name}-resources.json")
                        Files.writeString(snapshot, resourceSnapshot(sha256Hex(load)))
                        addAll(listOf("--resources", snapshot.toString()))
                    }
                    addAll(listOf("--data-dir", dataDir.toString()))
                }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val exit = runCli(args.toTypedArray(), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8), ByteArrayInputStream(ByteArray(0)))
            assertTrue(exit in 0..3, "${case.name}: exit $exit ${stderr.toString(UTF_8)}")

            val runId = Files.list(dataDir.resolve("runs")).use { it.toList().single().fileName.toString() }
            val analysis = Files.list(dataDir.resolve("runs/$runId/analyses")).use { it.toList().single() }
            val result = Files.readAllBytes(analysis.resolve("analysis-result.json"))
            val identity = Files.readAllBytes(analysis.resolve("identity.json"))
            assertArrayEquals(result, stdout.toByteArray(), "${case.name}: stdout is the stored result")
            assertEquals(analysis.fileName.toString().length, 64)

            val golden = Path.of("fixtures/typed-boundary/golden").resolve(case.name)
            if (update) {
                Files.createDirectories(golden)
                Files.write(golden.resolve("analysis-result.json"), result)
                Files.write(golden.resolve("identity.json"), identity)
                Files.writeString(golden.resolve("analysis_id.txt"), analysis.fileName.toString() + "\n")
            }
            assertArrayEquals(Files.readAllBytes(golden.resolve("analysis-result.json")), result, "${case.name}: analysis-result.json")
            assertArrayEquals(Files.readAllBytes(golden.resolve("identity.json")), identity, "${case.name}: identity.json")
            assertEquals(Files.readString(golden.resolve("analysis_id.txt")).trim(), analysis.fileName.toString(), "${case.name}: analysis_id")
        }
    }

    private fun resourceSnapshot(loadSha256: String) =
        """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadSha256","start_epoch_ms":1767225600000,""" +
            """"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm",""" +
            """"role":"system","aggregation":"interval_mean","labels":{"arm":"A"},"values":[0.1,0.9]}],""" +
            """"windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225601000}],""" +
            """"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,""" +
            """"min_consecutive_cells":1,"effect":"sla"}]}"""
}

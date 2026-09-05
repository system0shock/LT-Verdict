package io.ltverdict.cli

import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.validatePolicy
import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class CommandLineTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `usage rejects exact syntax unknown flags and duplicate flags without stdout`() {
        val input = fixture("jmeter/csv-5.6.3/input.jtl")
        val dataDir = tempDir.resolve("data")
        val cases =
            listOf(
                emptyArray(),
                arrayOf("unknown"),
                arrayOf("analyze"),
                arrayOf("analyze", "--data-dir", dataDir.toString(), input.toString()),
                arrayOf("analyze", input.toString(), "--unknown"),
                arrayOf("analyze", input.toString(), "--data-dir", dataDir.toString(), "--data-dir", dataDir.toString()),
                arrayOf(
                    "analyze",
                    input.toString(),
                    "--policy",
                    fixture("policies/pass.json").toString(),
                    "--policy",
                    fixture("policies/pass.json").toString(),
                ),
                arrayOf("policy"),
                arrayOf("policy", "validate"),
                arrayOf("policy", "validate", fixture("policies/pass.json").toString(), "--data-dir", dataDir.toString()),
            )

        cases.forEach { args -> assertError(run(*args), 64, args.joinToString(" ")) }
    }

    @Test
    fun `analyze maps pass fail degraded and invalid outcomes to exit codes`() {
        val malformed = tempDir.resolve("malformed.jtl")
        Files.writeString(malformed, Files.readAllLines(fixture("jmeter/csv-5.6.3/input.jtl")).first() + "\nmalformed\n")
        val degraded = tempDir.resolve("truncated.log")
        Files.write(degraded, Files.readAllBytes(fixture("gatling/binary-3.13.5/simulation.log")) + byteArrayOf(2, 0))
        val cases =
            listOf(
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), fixture("policies/pass.json"), 0, "PASS"),
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), null, 0, "NO_POLICY"),
                AnalyzeCase(fixture("jmeter/xml-5.6.3/input.xml"), fixture("policies/fail.json"), 2, "FAIL"),
                AnalyzeCase(degraded, fixture("policies/pass.json"), 3, "NO_VERDICT"),
                AnalyzeCase(malformed, null, 4, "NO_VERDICT"),
            )

        cases.forEachIndexed { index, case ->
            val result =
                run(
                    "analyze",
                    case.input.toString(),
                    *(case.policy?.let { arrayOf("--policy", it.toString()) } ?: emptyArray()),
                    "--data-dir",
                    tempDir.resolve("data-$index").toString(),
                )

            assertEquals(case.exitCode, result.exitCode, case.input.toString())
            assertTrue(result.stderr.isEmpty(), result.stderr)
            assertEquals(case.policyVerdict, result.stdout.json("policy_verdict"))
        }
    }

    @Test
    fun `policy validation separates normalized output from invalid diagnostics`() {
        val valid = fixture("policies/pass.json")
        val invalid = tempDir.resolve("invalid-policy.json")
        Files.writeString(invalid, "{\"schema_version\":\"policy.v1\",\"rules\":[]}")

        val validResult = run("policy", "validate", valid.toString())
        assertEquals(0, validResult.exitCode)
        assertTrue(validResult.stderr.isEmpty(), validResult.stderr)
        assertEquals(validCanonical(valid), validResult.stdout.trim())

        assertError(run("policy", "validate", invalid.toString()), 5, "invalid policy")
    }

    @Test
    fun `analyze rejects nonregular and symlink input before writing stdout`() {
        assertError(
            run("analyze", tempDir.toString(), "--data-dir", tempDir.resolve("directory-data").toString()),
            4,
            "directory input",
        )

        val link = tempDir.resolve("input-link.jtl")
        assumeTrue(createSymlink(link, fixture("jmeter/csv-5.6.3/input.jtl")), "symbolic links are unavailable")
        assertError(run("analyze", link.toString(), "--data-dir", tempDir.resolve("link-data").toString()), 4, "symlink input")
    }

    @Test
    fun `analyze reports data directory busy without partial stdout`() {
        val dataDir = tempDir.resolve("locked")
        DataDirectory.open(dataDir).use {
            assertError(
                run("analyze", fixture("jmeter/csv-5.6.3/input.jtl").toString(), "--data-dir", dataDir.toString()),
                6,
                "DATA_DIR_BUSY",
            )
        }
    }

    @Test
    fun `report returns stored JSON bytes without changing the analysis`() {
        val dataDir = tempDir.resolve("report-data")
        val analyzed = run("analyze", fixture("jmeter/xml-5.6.3/input.xml").toString(), "--data-dir", dataDir.toString())
        assertEquals(0, analyzed.exitCode)
        val runId = analyzed.stdout.json("run_id")
        val analyses = dataDir.resolve("runs").resolve(runId).resolve("analyses")
        val analysisId =
            Files.list(analyses).use {
                it
                    .findFirst()
                    .orElseThrow()
                    .fileName
                    .toString()
            }
        val result = analyses.resolve(analysisId).resolve("analysis-result.json")
        val before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(result))

        val exported = run("report", runId, analysisId, "--format", "json", "--data-dir", dataDir.toString())

        assertEquals(0, exported.exitCode)
        assertEquals(Files.readAllBytes(result).decodeToString(), exported.stdout)
        assertEquals(before.toList(), MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(result)).toList())
    }

    @Test
    fun `report validates syntax and maps missing busy corrupt and fail results without partial stdout`() {
        val saved = savedAnalysis("report-boundaries")
        listOf(
            arrayOf("report"),
            arrayOf("report", saved.runId, saved.analysisId, "--format", "xml"),
            arrayOf("report", saved.runId, saved.analysisId, "--format", "json", "--format", "html"),
            arrayOf("report", saved.runId, saved.analysisId, "--unknown", "json"),
        ).forEach { args -> assertError(run(*args), 64, args.joinToString(" ")) }
        assertError(
            run("report", saved.runId, "0".repeat(64), "--format", "json", "--data-dir", saved.dataDir.toString()),
            4,
            "unknown analysis",
        )
        DataDirectory.open(saved.dataDir).use {
            assertError(
                run("report", saved.runId, saved.analysisId, "--format", "json", "--data-dir", saved.dataDir.toString()),
                6,
                "busy data directory",
            )
        }
        Files.writeString(saved.result, "{}")
        assertError(
            run("report", saved.runId, saved.analysisId, "--format", "json", "--data-dir", saved.dataDir.toString()),
            70,
            "corrupt analysis",
        )

        val failed = savedAnalysis("failed-report", fixture("policies/fail.json"))
        val exported = run("report", failed.runId, failed.analysisId, "--format", "html", "--data-dir", failed.dataDir.toString())
        assertEquals(0, exported.exitCode)
        assertTrue(exported.stderr.isEmpty(), exported.stderr)
        assertTrue(exported.stdout.startsWith("<!doctype html>"))
    }

    private fun run(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf(*args), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8))
        return CliResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }

    private fun assertError(
        result: CliResult,
        expectedExitCode: Int,
        message: String,
    ) {
        assertEquals(expectedExitCode, result.exitCode, message)
        assertTrue(result.stdout.isEmpty(), "unexpected stdout for $message: ${result.stdout}")
        assertFalse(result.stderr.isEmpty(), "missing stderr for $message")
    }

    private fun fixture(path: String): Path = Path.of("fixtures/slice1").resolve(path)

    private fun savedAnalysis(
        name: String,
        policy: Path? = null,
    ): SavedAnalysis {
        val dataDir = tempDir.resolve(name)
        val analyzed =
            run(
                "analyze",
                fixture("jmeter/xml-5.6.3/input.xml").toString(),
                *(policy?.let { arrayOf("--policy", it.toString()) } ?: emptyArray()),
                "--data-dir",
                dataDir.toString(),
            )
        assertTrue(analyzed.exitCode in setOf(0, 2), analyzed.stderr)
        val runId = analyzed.stdout.json("run_id")
        val analysisId =
            Files.list(dataDir.resolve("runs").resolve(runId).resolve("analyses")).use {
                it
                    .findFirst()
                    .orElseThrow()
                    .fileName
                    .toString()
            }
        return SavedAnalysis(
            dataDir,
            runId,
            analysisId,
            dataDir
                .resolve("runs")
                .resolve(runId)
                .resolve("analyses")
                .resolve(analysisId)
                .resolve("analysis-result.json"),
        )
    }

    private fun validCanonical(path: Path): String =
        (Files.newInputStream(path).use(::validatePolicy) as PolicyValidation.Valid).canonicalBytes.decodeToString()

    private fun String.json(field: String): String =
        Json
            .parseToJsonElement(trim())
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

    private fun createSymlink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: Exception) {
            false
        }

    private data class AnalyzeCase(
        val input: Path,
        val policy: Path?,
        val exitCode: Int,
        val policyVerdict: String,
    )

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private data class SavedAnalysis(
        val dataDir: Path,
        val runId: String,
        val analysisId: String,
        val result: Path,
    )
}

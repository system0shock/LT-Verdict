package io.ltverdict.cli

import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64

class QuickstartCommandTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `quickstart generates report with transaction and resource incidents in one command`() {
        val dataDir = tempDir.resolve("data")
        val outDir = tempDir.resolve("out")

        val result = run("quickstart", "--out-dir", outDir.toString(), "--data-dir", dataDir.toString())

        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("", result.stderr)
        assertTrue(result.stdout.contains("Quickstart analysis complete:"), result.stdout)
        assertTrue(result.stdout.contains("policy_verdict: FAIL"), result.stdout)
        assertTrue(result.stdout.contains("ltv ui --data-dir"), result.stdout)

        val expectedFiles = setOf("result.json", "report.html", "chart.svg", "summary.txt", "junit.xml")
        assertEquals(expectedFiles, Files.list(outDir).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })
        expectedFiles.forEach { file ->
            assertTrue(Files.size(outDir.resolve(file)) > 0, "$file should be non-empty")
        }

        val resultJson = Json.parseToJsonElement(Files.readString(outDir.resolve("result.json"))).jsonObject
        assertEquals("VALID", resultJson.getValue("run_validity").jsonPrimitive.content)
        assertEquals("FAIL", resultJson.getValue("policy_verdict").jsonPrimitive.content)

        val incidents = resultJson.getValue("incidents").jsonObject
        assertEquals("EVALUATED", incidents.getValue("status").jsonPrimitive.content)
        val totalCount = incidents.getValue("total_count").jsonPrimitive.int
        assertEquals(2, totalCount)

        val items = incidents.getValue("items").jsonArray.map { it.jsonObject }
        val families =
            items
                .map { item ->
                    item
                        .getValue("scope")
                        .jsonObject
                        .getValue("kind")
                        .jsonPrimitive.content
                }.toSet()
        assertEquals(setOf("transaction", "entity"), families)
        assertTrue(
            items.all { item ->
                item.getValue("coincident_with").jsonArray.any { link ->
                    link
                        .jsonObject
                        .getValue("basis")
                        .jsonPrimitive.content == "SAME_WINDOW"
                }
            },
        )

        val html = Files.readString(outDir.resolve("report.html"))
        assertTrue(html.contains("<h2>Инциденты</h2>"), "HTML report must render Incidents section")
        assertTrue(html.contains("Высокий"), "HTML report must render HIGH priority badge")
        assertTrue(html.contains("<h2>Ошибки</h2>"), "HTML report must render error groups section")
        assertTrue(html.contains("POST /checkout"), html)
        assertTrue(html.contains("checkout-api"), html)

        val styleStart = html.indexOf("<style>") + "<style>".length
        val styleEnd = html.indexOf("</style>", styleStart)
        val styleBytes = html.substring(styleStart, styleEnd).toByteArray(UTF_8)
        val expectedHash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(styleBytes))
        assertTrue(html.contains("style-src 'sha256-$expectedHash'"), "CSP style hash must match inline style")
    }

    @Test
    fun `quickstart is idempotent on repeated execution with the same data-dir and out-dir`() {
        val dataDir = tempDir.resolve("data")
        val outDir = tempDir.resolve("out")

        val first = run("quickstart", "--out-dir", outDir.toString(), "--data-dir", dataDir.toString())
        assertEquals(0, first.exitCode, first.stderr)
        val firstBytes = Files.readAllBytes(outDir.resolve("result.json"))

        val second = run("quickstart", "--out-dir", outDir.toString(), "--data-dir", dataDir.toString())
        assertEquals(0, second.exitCode, second.stderr)
        assertArrayEquals(firstBytes, Files.readAllBytes(outDir.resolve("result.json")))
        assertEquals(first.stdout, second.stdout)
    }

    @Test
    fun `quickstart rejects invalid arguments, invalid out-dir, and busy data-dir`() {
        val unusedDataDir = tempDir.resolve("unused-data")
        listOf(
            arrayOf("quickstart", "--unknown"),
            arrayOf("quickstart", "--out-dir"),
            arrayOf("quickstart", "--out-dir", tempDir.resolve("a").toString(), "--out-dir", tempDir.resolve("b").toString()),
            arrayOf("quickstart", "--data-dir", unusedDataDir.toString(), "--data-dir", unusedDataDir.toString()),
        ).forEach { args ->
            val bad = run(*args)
            assertEquals(64, bad.exitCode, args.joinToString(" "))
            assertFalse(Files.exists(unusedDataDir))
        }

        val plainFile = tempDir.resolve("not-a-dir")
        Files.writeString(plainFile, "x")
        val invalidOut = run("quickstart", "--out-dir", plainFile.toString(), "--data-dir", unusedDataDir.toString())
        assertEquals(4, invalidOut.exitCode)
        assertEquals("OUT_DIR_INVALID", invalidOut.stderr.trim())
        assertFalse(Files.exists(unusedDataDir))

        val lockedDataDir = tempDir.resolve("locked-data")
        DataDirectory.open(lockedDataDir, "server").use {
            val busy = run("quickstart", "--out-dir", tempDir.resolve("busy-out").toString(), "--data-dir", lockedDataDir.toString())
            assertEquals(6, busy.exitCode)
            assertTrue(busy.stderr.startsWith("DATA_DIR_BUSY"), busy.stderr)
        }
    }

    @Test
    fun `committed quickstart snapshot binds to committed quickstart jtl sha256`() {
        val jtlBytes = Files.readAllBytes(Path.of("examples/quickstart/input.jtl"))
        val sha256 = MessageDigest.getInstance("SHA-256").digest(jtlBytes).joinToString("") { "%02x".format(it) }
        val snapshot = Json.parseToJsonElement(Files.readString(Path.of("examples/quickstart/resource-snapshot.json"))).jsonObject
        assertEquals(sha256, snapshot.getValue("load_input_sha256").jsonPrimitive.content)
    }

    private fun run(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode =
            runCli(
                arrayOf(*args),
                PrintStream(stdout, true, UTF_8),
                PrintStream(stderr, true, UTF_8),
                ByteArrayInputStream(ByteArray(0)),
            )
        return CliResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

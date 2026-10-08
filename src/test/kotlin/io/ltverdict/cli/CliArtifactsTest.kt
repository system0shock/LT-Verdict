package io.ltverdict.cli

import io.ltverdict.storage.DataDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class CliArtifactsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `version prints the build version and help prints usage without touching stdin`() {
        val noStdin =
            object : InputStream() {
                override fun read(): Int = throw AssertionError("help and version must not read stdin")
            }
        val version = run(noStdin, "--version")
        assertEquals(0, version.exitCode)
        assertTrue(Regex("ltv \\d+\\.\\d+\\.\\d+\\S*\n").matches(version.stdout), version.stdout)
        assertEquals("", version.stderr)
        listOf(
            arrayOf("--help"),
            arrayOf("-h"),
            arrayOf("help"),
            arrayOf("analyze", "--help"),
            arrayOf("report", "-h"),
        ).forEach { args ->
            val help = run(noStdin, *args)
            assertEquals(0, help.exitCode, args.joinToString(" "))
            assertEquals("", help.stderr)
            assertTrue(help.stdout.startsWith("Usage: ltv "), help.stdout)
            listOf("--out-dir", "ltv summary", "--version", "--policy <policy.json>|-").forEach {
                assertTrue(help.stdout.contains(it), "usage lacks $it")
            }
        }
        assertEquals(64, run(noStdin).exitCode)
    }

    @Test
    fun `analyze prints the analysis id and run id on stderr and keeps stdout canonical`() {
        val dataDir = tempDir.resolve("ids")
        val result = run("analyze", input().toString(), "--data-dir", dataDir.toString())
        assertEquals(0, result.exitCode, result.stderr)
        val runId = result.stdout.json("run_id")
        val analysisId = onlyAnalysis(dataDir, runId)
        assertEquals("analysis_id=$analysisId run_id=$runId\n", result.stderr.replace("\r\n", "\n"))
        assertArrayEquals(
            Files.readAllBytes(dataDir.resolve("runs/$runId/analyses/$analysisId/analysis-result.json")),
            result.stdout.toByteArray(),
        )
    }

    @Test
    fun `policy from stdin gives the same analysis as the file and validates like the file`() {
        val policy = fixture("policies/fail.json")
        val fromFile = run("analyze", input().toString(), "--policy", policy.toString(), "--data-dir", tempDir.resolve("a").toString())
        val fromStdin =
            run(
                Files.readAllBytes(policy).inputStream(),
                "analyze",
                input().toString(),
                "--policy",
                "-",
                "--data-dir",
                tempDir.resolve("b").toString(),
            )
        assertEquals(2, fromStdin.exitCode, fromStdin.stderr)
        assertEquals(fromFile.stdout, fromStdin.stdout)
        assertEquals(fromFile.stderr, fromStdin.stderr)

        val validated = run(Files.readAllBytes(policy).inputStream(), "policy", "validate", "-")
        assertEquals(run("policy", "validate", policy.toString()).stdout, validated.stdout)
        assertEquals(0, validated.exitCode)

        val invalid = run("{\"schema_version\":\"policy.v1\",\"rules\":[]}".byteInputStream(), "policy", "validate", "-")
        assertEquals(5, invalid.exitCode)
        assertEquals("", invalid.stdout)
        val tooLarge =
            run(
                ByteArray(1_048_577) { ' '.code.toByte() }.inputStream(),
                "analyze",
                input().toString(),
                "--policy",
                "-",
                "--data-dir",
                tempDir.resolve("c").toString(),
            )
        assertEquals(5, tooLarge.exitCode)
        assertEquals("", tooLarge.stdout)
    }

    @Test
    fun `out-dir writes all five artifacts that equal the report command output`() {
        val dataDir = tempDir.resolve("data")
        val out = tempDir.resolve("nested/out")
        val result =
            run(
                "analyze",
                input().toString(),
                "--policy",
                fixture("policies/fail.json").toString(),
                "--out-dir",
                out.toString(),
                "--data-dir",
                dataDir.toString(),
            )
        assertEquals(2, result.exitCode, result.stderr)
        val runId = result.stdout.json("run_id")
        val analysisId = onlyAnalysis(dataDir, runId)
        assertEquals(setOf("result.json", "report.html", "chart.svg", "summary.txt", "junit.xml"), names(out))
        assertArrayEquals(result.stdout.toByteArray(), Files.readAllBytes(out.resolve("result.json")))
        listOf("json" to "result.json", "html" to "report.html", "svg" to "chart.svg").forEach { (format, file) ->
            val report = run("report", runId, analysisId, "--format", format, "--data-dir", dataDir.toString())
            assertEquals(0, report.exitCode, report.stderr)
            assertEquals(report.stdout, Files.readString(out.resolve(file)), file)
        }
        val summary = Files.readString(out.resolve("summary.txt"))
        assertTrue(summary.contains("run_id: $runId\n"), summary)
        assertTrue(summary.contains("analysis_id: $analysisId\n"), summary)
        assertTrue(summary.contains("policy_verdict: FAIL\n"), summary)
        assertTrue(summary.contains("exit_code: 2\n"), summary)
        assertTrue(summary.contains("FAIL overall-errors: error_rate_ratio: observed 0.333333"), summary)
        val suite = junit(out.resolve("junit.xml"))
        assertEquals("lt-verdict", suite.getAttribute("name"))
        val cases = suite.getElementsByTagName("testcase")
        assertEquals(suite.getAttribute("tests").toInt(), cases.length)
        assertTrue(suite.getAttribute("failures").toInt() >= 2)
        val gate = (0 until cases.length).map { cases.item(it) as Element }.single { it.getAttribute("name") == "gate" }
        assertEquals("lt-verdict.gate", gate.getAttribute("classname"))
        assertEquals(1, gate.getElementsByTagName("failure").length)
        val rule = (0 until cases.length).map { cases.item(it) as Element }.single { it.getAttribute("name") == "overall-errors" }
        assertEquals("lt-verdict.policy", rule.getAttribute("classname"))
        val failure = rule.getElementsByTagName("failure").item(0) as Element
        assertTrue(failure.getAttribute("message").contains("error_rate_ratio"), failure.getAttribute("message"))
        assertEquals(failure.getAttribute("message"), failure.textContent)
    }

    @Test
    fun `out-dir keeps the verdict exit code and junit counters agree with it for every outcome`() {
        val degraded = tempDir.resolve("truncated.log")
        Files.write(degraded, Files.readAllBytes(fixture("gatling/binary-3.13.5/simulation.log")) + byteArrayOf(2, 0))
        val malformed = tempDir.resolve("malformed.jtl")
        Files.writeString(malformed, Files.readAllLines(fixture("jmeter/csv-5.6.3/input.jtl")).first() + "\nmalformed\n")
        val pass = fixture("policies/pass.json")
        val cases =
            listOf(
                Triple(input(), pass, 0),
                Triple(input(), fixture("policies/fail.json"), 2),
                Triple(input(), null, 0),
                Triple(degraded, pass, 3),
                Triple(malformed, null, 4),
            )
        cases.forEachIndexed { index, (file, policy, expected) ->
            val out = tempDir.resolve("matrix-$index")
            val result =
                run(
                    "analyze",
                    file.toString(),
                    *(policy?.let { arrayOf("--policy", it.toString()) } ?: emptyArray()),
                    "--out-dir",
                    out.toString(),
                    "--data-dir",
                    tempDir.resolve("matrix-data-$index").toString(),
                )
            assertEquals(expected, result.exitCode, result.stderr)
            assertEquals(5, names(out).size, "case $index")
            val suite = junit(out.resolve("junit.xml"))
            val bad = suite.getAttribute("failures").toInt() + suite.getAttribute("errors").toInt()
            assertEquals(expected == 0, bad == 0, "case $index counters must agree with exit code $expected")
            assertTrue(Files.readString(out.resolve("summary.txt")).contains("exit_code: $expected\n"))
        }
    }

    @Test
    fun `out-dir is validated before the analysis and rerun overwrites stale files`() {
        val dataDir = tempDir.resolve("pre-data")
        val file = tempDir.resolve("plain-file")
        Files.writeString(file, "x")
        val notDirectory = run("analyze", input().toString(), "--out-dir", file.toString(), "--data-dir", dataDir.toString())
        assertEquals(4, notDirectory.exitCode)
        assertEquals("OUT_DIR_INVALID", notDirectory.stderr.trim())
        assertEquals("", notDirectory.stdout)
        assertFalse(Files.exists(dataDir), "no data dir before out-dir validation")

        val aliasDir = Files.createDirectory(tempDir.resolve("alias"))
        val alias = aliasDir.resolve("result.json")
        Files.copy(input(), alias)
        val before = Files.readAllBytes(alias)
        val aliased = run("analyze", alias.toString(), "--out-dir", aliasDir.toString(), "--data-dir", dataDir.toString())
        assertEquals(4, aliased.exitCode)
        assertEquals("OUT_DIR_INVALID", aliased.stderr.trim())
        assertArrayEquals(before, Files.readAllBytes(alias))
        assertFalse(Files.exists(dataDir))

        val directoryAsFile = Files.createDirectories(tempDir.resolve("dir-target/junit.xml"))
        val nonRegular =
            run("analyze", input().toString(), "--out-dir", directoryAsFile.parent.toString(), "--data-dir", dataDir.toString())
        assertEquals(4, nonRegular.exitCode)
        assertEquals("OUT_DIR_INVALID", nonRegular.stderr.trim())

        val reused = Files.createDirectory(tempDir.resolve("reused"))
        Files.writeString(reused.resolve("junit.xml"), "stale")
        val again = run("analyze", input().toString(), "--out-dir", reused.toString(), "--data-dir", dataDir.toString())
        assertEquals(0, again.exitCode, again.stderr)
        assertEquals("lt-verdict", junit(reused.resolve("junit.xml")).getAttribute("name"))
    }

    @Test
    fun `out-dir refuses symbolic links for the directory and for each artifact`() {
        val real = Files.createDirectory(tempDir.resolve("real"))
        val link = tempDir.resolve("link")
        assumeTrue(createLink(link, real))
        val linked = run("analyze", input().toString(), "--out-dir", link.toString(), "--data-dir", tempDir.resolve("l1").toString())
        assertEquals(4, linked.exitCode)
        assertEquals("OUT_DIR_INVALID", linked.stderr.trim())

        val out = Files.createDirectory(tempDir.resolve("with-link"))
        val victim = tempDir.resolve("victim.txt")
        Files.writeString(victim, "keep")
        assumeTrue(createLink(out.resolve("summary.txt"), victim))
        val artifact = run("analyze", input().toString(), "--out-dir", out.toString(), "--data-dir", tempDir.resolve("l2").toString())
        assertEquals(4, artifact.exitCode)
        assertEquals("OUT_DIR_INVALID", artifact.stderr.trim())
        assertEquals("keep", Files.readString(victim))
    }

    @Test
    fun `a write failure keeps the analysis, prints the ids and leaves stdout empty`() {
        val out = Files.createDirectory(tempDir.resolve("readonly"))
        val locked = out.resolve("junit.xml")
        Files.writeString(locked, "stale")
        locked.toFile().setReadOnly()
        try {
            assumeTrue(!Files.isWritable(locked), "read-only files are writable here")
            val dataDir = tempDir.resolve("wf-data")
            val result = run("analyze", input().toString(), "--out-dir", out.toString(), "--data-dir", dataDir.toString())
            assertEquals(4, result.exitCode)
            assertEquals("", result.stdout)
            assertTrue(result.stderr.contains("OUT_DIR_WRITE_FAILED"), result.stderr)
            assertTrue(Regex("analysis_id=[0-9a-f]{64} run_id=\\S+").containsMatchIn(result.stderr), result.stderr)
            DataDirectory.open(dataDir).close()
            assertEquals(1, Files.list(dataDir.resolve("runs")).use { it.count() })
        } finally {
            locked.toFile().setWritable(true)
        }
    }

    @Test
    fun `summary prints compact transactions and percentiles and fails like report`() {
        val dataDir = tempDir.resolve("summary-data")
        val analyzed =
            run("analyze", input().toString(), "--policy", fixture("policies/fail.json").toString(), "--data-dir", dataDir.toString())
        val runId = analyzed.stdout.json("run_id")
        val analysisId = onlyAnalysis(dataDir, runId)

        val summary = run("summary", runId, analysisId, "--data-dir", dataDir.toString())
        assertEquals(0, summary.exitCode, summary.stderr)
        assertEquals("", summary.stderr)
        assertFalse(summary.stdout.contains("\n") || summary.stdout.contains("\": ") || summary.stdout.contains(", "), "compact JSON")
        val json = Json.parseToJsonElement(summary.stdout).jsonObject
        assertEquals("cli-summary.v1", json.getValue("schema_version").jsonPrimitive.content)
        assertEquals(runId, json.getValue("run_id").jsonPrimitive.content)
        assertEquals(analysisId, json.getValue("analysis_id").jsonPrimitive.content)
        assertEquals("FAIL", json.getValue("policy_verdict").jsonPrimitive.content)
        val overall = json.getValue("overall").jsonObject
        assertEquals("3", overall.getValue("samples").jsonPrimitive.content)
        assertEquals("1", overall.getValue("errors").jsonPrimitive.content)
        assertEquals("26", overall.getValue("p95").jsonPrimitive.content)
        assertEquals("0.333333", overall.getValue("error_rate").jsonPrimitive.content)
        assertEquals("34.883721", overall.getValue("rps").jsonPrimitive.content)
        val transactions = json.getValue("transactions").jsonArray
        assertEquals(5, transactions.size)
        val order = transactions.map { it.jsonObject }.single { it.getValue("label").jsonPrimitive.content == "POST /order" }
        assertEquals(listOf("Checkout scenario"), (order.getValue("group_path") as JsonArray).map { it.jsonPrimitive.content })
        listOf("p50", "p95", "p99", "max", "samples", "errors", "kind", "rps", "error_rate").forEach {
            assertTrue(order.containsKey(it), it)
        }
        val rule =
            json
                .getValue("rules")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("overall-errors", rule.getValue("rule_id").jsonPrimitive.content)
        assertEquals("FAIL", rule.getValue("status").jsonPrimitive.content)

        assertEquals(4, run("summary", runId, "0".repeat(64), "--data-dir", dataDir.toString()).exitCode)
        assertEquals(64, run("summary", runId).exitCode)
        DataDirectory.open(dataDir).use {
            assertEquals(6, run("summary", runId, analysisId, "--data-dir", dataDir.toString()).exitCode)
        }
    }

    @Test
    fun `junit and summary cover resource sla checks and survive characters that XML forbids`() {
        val result =
            """
            {"schema_version":"analysis-result.v1","run_id":"r","analysis_mode":"standard","run_validity":"VALID",
            "policy_verdict":"FAIL","analysis_coverage":{"status":"COMPLETE","reasons":[]},"findings":[],"evidence":[
            {"id":"a","type":"policy_check","rule_id":"bad\u0001<&>rule","metric":"response_time_p95_ms","operator":"lte",
             "threshold":300,"status":"FAIL","observed":339},
            {"id":"b","type":"policy_check","rule_id":"missing","metric":"error_rate_ratio","operator":"lte","threshold":0.1,
             "status":"NO_VERDICT","reason_code":"TRANSACTION_NOT_FOUND"},
            {"id":"c","type":"resource_policy_check","window_id":"steady","rule_id":"cpu","series_id":"cpu-1","unit":"percent",
             "operator":"gt","threshold":"80","effect":"sla","status":"FAIL","reason":null},
            {"id":"d","type":"resource_policy_check","window_id":"steady","rule_id":"diag","series_id":"mem","unit":"percent",
             "operator":"gt","threshold":"90","effect":"diagnostic","status":"FAIL","reason":null},
            {"id":"e","type":"resource_policy_check","window_id":"steady","rule_id":"gap","series_id":"disk","unit":"percent",
             "operator":"gt","threshold":"70","effect":"sla","status":"NO_VERDICT","reason":"MISSING_RESOURCE_CELLS"}]}
            """.trimIndent().toByteArray()
        val document = parse(junitXml(result))
        val suite = document.documentElement
        assertEquals("5", suite.getAttribute("tests"))
        assertEquals("3", suite.getAttribute("failures"))
        assertEquals("0", suite.getAttribute("errors"))
        assertEquals("2", suite.getAttribute("skipped"))
        val names =
            (
                0 until
                    suite
                        .getElementsByTagName(
                            "testcase",
                        ).length
            ).map { (suite.getElementsByTagName("testcase").item(it) as Element) }
        assertEquals(
            listOf("gate", "bad?<&>rule", "missing", "cpu @ steady", "gap @ steady"),
            names.map { it.getAttribute("name") },
        )
        assertEquals("lt-verdict.resource-sla", names[3].getAttribute("classname"))
        val text = summaryText("f".repeat(64), 2, result).decodeToString()
        assertTrue(text.contains("FAIL cpu @ steady: series cpu-1, window steady (violation gt 80 percent)"), text)
        assertTrue(text.contains("NO_VERDICT missing: TRANSACTION_NOT_FOUND"), text)
        assertTrue(text.contains("NO_VERDICT gap @ steady: MISSING_RESOURCE_CELLS"), text)
        assertTrue(text.contains("rules: 4 (PASS 0, FAIL 2, NO_VERDICT 2)"), text)
        val rules =
            Json
                .parseToJsonElement(summaryJson("f".repeat(64), result).decodeToString())
                .jsonObject
                .getValue("rules")
                .jsonArray
        assertEquals(4, rules.size)
    }

    @Test
    fun `junit marks not evaluated checks skipped when the gate is decided and keeps them errors otherwise`() {
        val warn =
            """{"id":"b","type":"policy_check","rule_id":"a<&>\"b","metric":"error_rate_ratio","operator":"lte",""" +
                """"threshold":0.1,"status":"NO_VERDICT","reason_code":"TRANSACTION_NOT_FOUND"}"""
        val gap =
            """{"id":"e","type":"resource_policy_check","window_id":"steady","rule_id":"gap","series_id":"disk","unit":"percent",""" +
                """"operator":"gt","threshold":"70","effect":"sla","status":"NO_VERDICT","reason":"MISSING_RESOURCE_CELLS"}"""
        val ok =
            """{"id":"p","type":"policy_check","rule_id":"ok","metric":"error_rate_ratio","operator":"lte",""" +
                """"threshold":0.1,"status":"PASS","observed":0}"""
        val bad =
            """{"id":"f","type":"policy_check","rule_id":"bad","metric":"response_time_p95_ms","operator":"lte",""" +
                """"threshold":300,"status":"FAIL","observed":339}"""

        fun suite(
            validity: String,
            verdict: String,
            vararg checks: String,
        ): Element {
            val result =
                (
                    """{"schema_version":"analysis-result.v1","run_id":"r","analysis_mode":"standard","run_validity":"$validity",""" +
                        """"policy_verdict":"$verdict","analysis_coverage":{"status":"COMPLETE","reasons":[]},"findings":[],""" +
                        """"evidence":[${checks.joinToString(",")}]}"""
                ).toByteArray()
            return parse(junitXml(result)).documentElement
        }

        fun count(
            suite: Element,
            tag: String,
        ) = suite.getElementsByTagName(tag).length

        val warnPass = suite("VALID", "PASS", ok, warn, gap)
        assertEquals("4", warnPass.getAttribute("tests"))
        assertEquals("0", warnPass.getAttribute("failures"))
        assertEquals("0", warnPass.getAttribute("errors"))
        assertEquals("2", warnPass.getAttribute("skipped"))
        assertEquals(0, count(warnPass, "error") + count(warnPass, "failure"))
        val skipped = warnPass.getElementsByTagName("skipped")
        assertEquals(2, skipped.length)
        assertEquals("не вычислено: TRANSACTION_NOT_FOUND", (skipped.item(0) as Element).getAttribute("message"))
        assertEquals("не вычислено: MISSING_RESOURCE_CELLS", (skipped.item(1) as Element).getAttribute("message"))
        assertEquals("", skipped.item(0).textContent)

        val warnFail = suite("VALID", "FAIL", bad, warn)
        assertEquals("2", warnFail.getAttribute("failures"))
        assertEquals("0", warnFail.getAttribute("errors"))
        assertEquals("1", warnFail.getAttribute("skipped"))

        val noVerdict = suite("DEGRADED", "NO_VERDICT", ok, warn)
        assertEquals("0", noVerdict.getAttribute("failures"))
        assertEquals("2", noVerdict.getAttribute("errors"))
        assertEquals("0", noVerdict.getAttribute("skipped"))
        assertEquals(0, count(noVerdict, "skipped"))

        val reason =
            """{"id":"x","type":"policy_check","rule_id":"r","metric":"m","operator":"lte",""" +
                """"threshold":1,"status":"NO_VERDICT","reason_code":"a<&>\"b"}"""
        val escaped = suite("VALID", "PASS", reason)
        assertEquals("не вычислено: a<&>\"b", (escaped.getElementsByTagName("skipped").item(0) as Element).getAttribute("message"))
    }

    private fun input(): Path = fixture("jmeter/xml-5.6.3/input.xml")

    private fun fixture(path: String): Path = Path.of("fixtures/slice1").resolve(path)

    private fun names(directory: Path): Set<String> =
        Files.list(directory).use { stream ->
            stream
                .map {
                    it.fileName.toString()
                }.toList()
                .toSet()
        }

    private fun onlyAnalysis(
        dataDir: Path,
        runId: String,
    ): String =
        Files.list(dataDir.resolve("runs").resolve(runId).resolve("analyses")).use { stream ->
            stream
                .toList()
                .single()
                .fileName
                .toString()
        }

    private fun junit(path: Path): Element = parse(Files.readAllBytes(path)).documentElement

    private fun parse(bytes: ByteArray) =
        DocumentBuilderFactory
            .newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))

    private fun createLink(
        link: Path,
        target: Path,
    ): Boolean =
        try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: Exception) {
            false
        }

    private fun String.json(field: String): String =
        Json
            .parseToJsonElement(trim())
            .jsonObject
            .getValue(field)
            .jsonPrimitive.content

    private fun run(vararg args: String): CliResult = run(ByteArrayInputStream(ByteArray(0)), *args)

    private fun run(
        stdin: InputStream,
        vararg args: String,
    ): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runCli(arrayOf(*args), PrintStream(stdout, true, UTF_8), PrintStream(stderr, true, UTF_8), stdin)
        return CliResult(exitCode, stdout.toString(UTF_8), stderr.toString(UTF_8))
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

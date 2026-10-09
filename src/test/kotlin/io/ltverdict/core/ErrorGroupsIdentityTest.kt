package io.ltverdict.core

import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

// W2.6: the error breakdown is a separate artifact outside the identity, so a run with failed samples keeps the analysis_id and the
// bytes of analysis-result.json it had before the response code and the failure message were stored.
class ErrorGroupsIdentityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `run with failed samples keeps analysis id and result bytes`() {
        DataDirectory.open(tempDir.resolve("data")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(FAILED_CSV.encodeToByteArray()), "failed.jtl")
            val outcome = AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(input, null))

            assertEquals(EXPECTED_ANALYSIS_ID, outcome.analysisId)
            assertEquals(EXPECTED_RESULT_SHA256, sha256Hex(outcome.canonicalResult))
        }
    }

    @Test
    fun `failed samples are written as a manifest artifact whose counts match the overall error count`() {
        DataDirectory.open(tempDir.resolve("data")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(FAILED_CSV.encodeToByteArray()), "failed.jtl")
            val service = AnalysisService(store, EngineConfig())
            val outcome = service.analyze(AnalysisRequest(input, null))

            val groups = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("error-groups.json"))).jsonObject
            val manifest = Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("manifest.json"))).jsonObject
            val overall =
                Json
                    .parseToJsonElement(
                        outcome.canonicalResult.decodeToString(),
                    ).jsonObject
                    .getValue("evidence")
                    .jsonArray
                    .map { it.jsonObject }
                    .single {
                        it["type"]?.jsonPrimitive?.content == "metric_summary" &&
                            it
                                .getValue("scope")
                                .jsonObject["kind"]
                                ?.jsonPrimitive
                                ?.content == "overall"
                    }

            assertEquals(overall.getValue("error_count").jsonPrimitive.content, groups.getValue("total_error_count").jsonPrimitive.content)
            assertEquals(
                listOf(
                    "login|503|Service Unavailable|2",
                    "search|200|Test failed: text expected to contain 'ok'|1",
                    "search|Non HTTP response code: java.net.SocketTimeoutException|Non HTTP response message: Read timed out|1",
                ),
                groups.getValue("groups").jsonArray.map { group ->
                    val item = group.jsonObject
                    listOf(
                        item.getValue("scope").jsonObject.getValue("label"),
                        item.getValue("response_code"),
                        item.getValue("message"),
                        item.getValue("count"),
                    ).joinToString("|") { it.jsonPrimitive.content }
                },
            )
            assertTrue(
                manifest.getValue("artifacts").jsonArray.any {
                    it.jsonObject
                        .getValue("path")
                        .jsonPrimitive.content ==
                        "error-groups.json"
                },
            )
            assertEquals(outcome.analysisId, service.analyze(AnalysisRequest(input, null)).analysisId)
            assertTrue(Files.exists(outcome.analysisDirectory.resolve("error-groups.json")))
        }
    }

    @Test
    fun `a run without failed samples gets no breakdown artifact`() {
        DataDirectory.open(tempDir.resolve("data")).use { directory ->
            val store = RunBundleStore(directory)
            val clean = FAILED_CSV.lines().filterNot { "false," in it }.joinToString("\n")
            val input = store.acceptInput(ByteArrayInputStream(clean.encodeToByteArray()), "clean.jtl")
            val outcome = AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(input, null))

            assertFalse(Files.exists(outcome.analysisDirectory.resolve("error-groups.json")))
        }
    }
}

private const val EXPECTED_ANALYSIS_ID = "cede7f378c0f5177e15365f3418dc25edfe3bc514be136aa10c75176a52460db"
private const val EXPECTED_RESULT_SHA256 = "c5f592260b11f1ea9c89af8724590eb14d2c641851797970177d5b19a83149a8"

private val FAILED_CSV =
    listOf(
        "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect",
        "1767225600000,100,login,200,OK,t,text,true,,0,0,1,1,,0,0,0",
        "1767225600100,120,login,503,Service Unavailable,t,text,false,,0,0,1,1,,0,0,0",
        "1767225600200,90,login,503,Service Unavailable,t,text,false,,0,0,1,1,,0,0,0",
        "1767225600300,80,search,200,OK,t,text,false,Test failed: text expected to contain 'ok',0,0,1,1,,0,0,0",
        "1767225600400,70,search,Non HTTP response code: java.net.SocketTimeoutException,Non HTTP response message: Read timed out,t,text,false,,0,0,1,1,,0,0,0",
        "1767225600500,60,search,200,OK,t,text,true,,0,0,1,1,,0,0,0",
    ).joinToString("\n", postfix = "\n")

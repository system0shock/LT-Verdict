package io.ltverdict.ai

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@EnabledIfSystemProperty(named = "ltverdict.aiAcceptanceValidateOutputs", matches = "true")
class AdvisoryAcceptanceOutputTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `reject result copied from another frozen attempt`() {
        val evidence = """{"facts":[],"findings":[],"evidence":[]}""".encodeToByteArray()
        val evidencePath = tempDir.resolve("evidence.json")
        Files.write(evidencePath, evidence)
        val attempt =
            buildJsonObject {
                put("attempt_id", "attempt-1")
                put("case_id", "case-1")
                put("ordinal", 1)
                put("repeat_index", 1)
                put("evidence_path", evidencePath.toString())
                put("evidence_sha256", sha256Hex(evidence))
            }
        val base =
            buildJsonObject {
                attempt.forEach { (name, value) ->
                    if (name != "evidence_path") put(name, value)
                }
                put("status", "COMPLETED")
                put("advice_output_sha256", "0".repeat(64))
            }
        val resultPath = tempDir.resolve("attempts/attempt-1/result.json")
        Files.createDirectories(resultPath.parent)
        listOf(
            "case_id" to JsonPrimitive("case-2"),
            "ordinal" to JsonPrimitive(2),
            "repeat_index" to JsonPrimitive(2),
            "evidence_sha256" to JsonPrimitive("1".repeat(64)),
            "attempt_id" to JsonPrimitive("attempt-2"),
        ).forEach { (field, value) ->
            Files.write(resultPath, canonicalJson(JsonObject(base + (field to value))))
            assertEquals("INVALID" to "RESULT_BINDING_MISMATCH", validate(tempDir, attempt), field)
        }
    }

    @Test
    fun `validate frozen acceptance outputs without semantic scoring`() {
        val root = Path.of(System.getProperty("ltverdict.aiAcceptanceRoot", "build/ai-acceptance/v1/resume-2026-09-21"))
        val plan = Json.parseToJsonElement(Files.readString(root.resolve("attempt-plan.json"))).jsonObject
        val attempts = plan.getValue("attempts").jsonArray.map { it.jsonObject }
        check(attempts.size == 60 && attempts.map { it.text("attempt_id") }.toSet().size == 60) {
            "INVALID_ATTEMPT_PLAN"
        }
        val rows =
            attempts.map { attempt ->
                val id = attempt.text("attempt_id")
                val (status, failure) = validate(root, attempt)
                buildJsonObject {
                    put("ordinal", attempt.getValue("ordinal"))
                    put("attempt_id", id)
                    put("case_id", attempt.text("case_id"))
                    put("repeat_index", attempt.getValue("repeat_index"))
                    put("status", status)
                    put("failure_code", failure?.let(::JsonPrimitive) ?: JsonNull)
                }
            }
        val counts = rows.groupingBy { it.text("status") }.eachCount().toSortedMap()
        val report =
            buildJsonObject {
                put("schema_version", "advisory-ai-output-validation.v1")
                put("status", "VALIDATION_RECORDED")
                put("attempt_count", rows.size)
                put("counts", buildJsonObject { counts.forEach { (status, count) -> put(status, count) } })
                put("attempts", JsonArray(rows))
            }
        Files.write(root.resolve("output-validation.json"), canonicalJson(report))
    }

    private fun validate(
        root: Path,
        attempt: JsonObject,
    ): Pair<String, String?> {
        val evidencePath = Path.of(attempt.text("evidence_path"))
        if (!Files.isRegularFile(evidencePath)) return "MISSING" to "EVIDENCE_MISSING"
        val evidenceBytes = Files.readAllBytes(evidencePath)
        if (sha256Hex(evidenceBytes) != attempt.text("evidence_sha256")) {
            return "INVALID" to "EVIDENCE_HASH_MISMATCH"
        }
        val references = mutableSetOf<String>()
        val evidence =
            try {
                Json.parseToJsonElement(evidenceBytes.decodeToString()) as? JsonObject
                    ?: return "INVALID" to "EVIDENCE_INVALID"
            } catch (_: IllegalArgumentException) {
                return "INVALID" to "EVIDENCE_INVALID"
            }
        for (field in listOf("facts", "findings", "evidence")) {
            val records = evidence[field] as? JsonArray ?: return "INVALID" to "EVIDENCE_INVALID"
            for (record in records) {
                val ref =
                    (record as? JsonObject)?.optionalText("ref")
                        ?: return "INVALID" to "EVIDENCE_INVALID"
                references += ref
            }
        }
        val attemptRoot = root.resolve("attempts").resolve(attempt.text("attempt_id"))
        val resultPath = attemptRoot.resolve("result.json")
        if (!Files.isRegularFile(resultPath)) return "MISSING" to "RESULT_MISSING"
        val result =
            try {
                Json.parseToJsonElement(Files.readString(resultPath)) as? JsonObject
                    ?: return "INVALID" to "RESULT_INVALID"
            } catch (_: IllegalArgumentException) {
                return "INVALID" to "RESULT_INVALID"
            }
        if (
            result.optionalText("attempt_id") != attempt.text("attempt_id") ||
            result.optionalText("case_id") != attempt.text("case_id") ||
            result["ordinal"] != attempt["ordinal"] ||
            result["repeat_index"] != attempt["repeat_index"] ||
            result.optionalText("evidence_sha256") != attempt.text("evidence_sha256")
        ) {
            return "INVALID" to "RESULT_BINDING_MISMATCH"
        }
        if (result.optionalText("status") != "COMPLETED") return "FAILED" to "ATTEMPT_NOT_COMPLETED"
        val outputPath = attemptRoot.resolve("advice-output.json")
        if (!Files.isRegularFile(outputPath)) return "MISSING" to "OUTPUT_MISSING"
        val outputBytes = Files.readAllBytes(outputPath)
        if (result.optionalText("advice_output_sha256") != sha256Hex(outputBytes)) {
            return "INVALID" to "OUTPUT_HASH_MISMATCH"
        }
        return try {
            AdviceOutputValidator.validate(outputBytes, references)
            "VALID" to null
        } catch (failure: AdviceValidationException) {
            "INVALID" to failure.reason.name
        }
    }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private fun JsonObject.optionalText(name: String) = (this[name] as? JsonPrimitive)?.contentOrNull
}

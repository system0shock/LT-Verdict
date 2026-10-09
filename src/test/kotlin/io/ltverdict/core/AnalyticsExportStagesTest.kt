package io.ltverdict.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** ADR 0030, R7: the export of the dynamics says its metrics are of the whole run when the analysis has stages. */
class AnalyticsExportStagesTest {
    private val dynamics =
        Json.parseToJsonElement(
            """{"schema_version":"run-dynamics.v1","limit":10,"comparable_count":0,"excluded_incompatible_count":0,"baseline":null,"rows":[]}""",
        ) as JsonObject
    private val note = "Metrics are of the whole run, for reference only"

    @Test
    fun `every format carries the note for a staged analysis and none carries it otherwise`() {
        AnalyticsExportFormat.entries.forEach { format ->
            val staged = renderRunDynamicsExport(dynamics, format, wholeRunWithStages = true).decodeToString()
            val plain = renderRunDynamicsExport(dynamics, format).decodeToString()

            assertTrue(staged.contains(note), "$format: $staged")
            assertFalse(plain.contains(note), format.name)
            assertArrayEquals(plain.encodeToByteArray(), renderRunDynamicsExport(dynamics, format, wholeRunWithStages = false))
        }
    }
}

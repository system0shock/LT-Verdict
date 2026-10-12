package io.ltverdict.core

import io.ltverdict.ingest.LoadSample
import io.ltverdict.ingest.RunValidity
import io.ltverdict.ingest.parseJtlCsv
import io.ltverdict.sources.ExplicitWindow
import io.ltverdict.sources.readSourceProfiles
import io.ltverdict.sources.readWindowedSourceRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class BasicExampleTest {
    private val example = Path.of("examples/basic")

    @Test
    fun `basic example inputs are valid and bound to the same run`() {
        val profiles = Files.newInputStream(example.resolve("connections.json")).use(::readSourceProfiles)
        val profile = profiles.single()
        assertEquals(setOf("cpu", "rss"), profile.queries.map { it.id }.toSet())
        assertEquals(setOf("cpu", "rss"), profile.rules.map { it.seriesId }.toSet())
        val request = Files.newInputStream(example.resolve("source-request.json")).use(::readWindowedSourceRequest)
        assertEquals(listOf(profile.id), request.profileIds)

        val policy = Files.newInputStream(example.resolve("policy.json")).use(::validatePolicy)
        val validPolicy = assertInstanceOf(PolicyValidation.Valid::class.java, policy)
        assertEquals(
            PolicyMetric.entries.toSet(),
            validPolicy.policy.rules
                .map { it.metric }
                .toSet(),
        )

        val resources = Files.newInputStream(example.resolve("resource-snapshot.json")).use(::validateResourceSnapshot)
        val validResources = assertInstanceOf(ResourceValidation.Valid::class.java, resources)
        val loadHash = sha256Hex(Files.readAllBytes(example.resolve("input.jtl")))
        assertEquals(loadHash, validResources.snapshot.loadInputSha256)
        val samples = mutableListOf<LoadSample>()
        assertEquals(RunValidity.VALID, parseJtlCsv(example.resolve("input.jtl"), samples::add).validity)
        val runStart = samples.minOf { it.startedAtEpochMillis }
        val runEnd = samples.maxOf { it.endedAtEpochMillis }
        assertEquals(profile.rules, validResources.snapshot.rules)
        assertEquals(
            profile.queries.map { it.id }.toSet(),
            validResources.snapshot.series
                .map { it.id }
                .toSet(),
        )
        val window = assertInstanceOf(ExplicitWindow::class.java, request.window)
        assertEquals(validResources.snapshot.startEpochMillis, window.startMillis)
        assertEquals(validResources.snapshot.stepMillis, window.stepMillis)
        assertTrue(window.startMillis >= runStart && window.endMillis <= runEnd)
        assertTrue(window.endMillis <= window.startMillis + validResources.snapshot.pointCount * window.stepMillis)
        assertTrue(
            validResources.snapshot.windows.all { it.fromEpochMillis >= window.startMillis && it.toEpochMillis <= window.endMillis },
        )

        val diagnostics = Files.newInputStream(example.resolve("correlation-plan.json")).use(::validateDiagnosticPlan)
        val validDiagnostics = assertInstanceOf(DiagnosticValidation.Valid::class.java, diagnostics)
        assertEquals(emptyList<PolicyValidationError>(), validateDiagnosticBinding(validDiagnostics, validResources))
        assertEquals(
            emptyList<PolicyValidationError>(),
            validateDiagnosticResolvedWindows(validDiagnostics, validResources.snapshot.windows),
        )

        val podView = Files.newInputStream(example.resolve("pod-view.json")).use(::validatePodView)
        val validPodView = assertInstanceOf(PodViewValidation.Valid::class.java, podView)
        assertEquals(emptyList<PolicyValidationError>(), validatePodViewBinding(validPodView, loadHash, validResources))

        val stages = Files.newInputStream(example.resolve("load-stages.json")).use(::validateLoadStages)
        val validStages = assertInstanceOf(LoadStagesValidation.Valid::class.java, stages)
        val resolved = resolveStageWindows(validStages.stages, runStart, runEnd)
        assertEquals(validResources.snapshot.windows, resolved.windows)
        assertEquals(validResources.snapshot.windows, resolveResourceWindows(validResources.snapshot, loadHash, runStart, runEnd))
    }
}

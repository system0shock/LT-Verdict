package io.ltverdict.jobs

import io.ltverdict.core.AnalysisRequest
import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

class AnalysisJobsStagesTest {
    @Test
    fun `stage failures of the analysis are actionable in the failed job`() {
        listOf(
            "STAGE_OUTSIDE_RUN" to "STAGE_OUTSIDE_RUN",
            "STAGES_RESOURCES_CONFLICT" to "STAGES_RESOURCES_CONFLICT",
            "STAGES_CAPACITY_CONFLICT" to "STAGES_CAPACITY_CONFLICT",
            "STAGES_SOURCE_CONFLICT" to "STAGES_SOURCE_CONFLICT",
            "private filesystem detail" to "ANALYSIS_FAILED",
        ).forEach { (failureMessage, expectedCode) ->
            AnalysisJobs(1) { _, _, _ -> throw IllegalArgumentException(failureMessage) }.use { jobs ->
                val submitted = assertInstanceOf(SubmitResult.Accepted::class.java, jobs.submit(request()))
                val failed = awaitFailed(jobs, submitted.status.jobId)

                assertEquals(expectedCode, failed.diagnostic?.code)
            }
        }
    }

    private fun awaitFailed(
        jobs: AnalysisJobs,
        jobId: String,
    ): JobStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            jobs.status(jobId)?.let { if (it.state == JobState.FAILED) return it }
            LockSupport.parkNanos(100_000L)
        }
        return fail("job $jobId did not fail; last=${jobs.status(jobId)}")
    }

    private fun request(): AnalysisRequest {
        val sha256 = "7".padStart(64, '0')
        return AnalysisRequest(
            AcceptedInput(
                runId = "jmeter_jtl_csv-$sha256",
                sourceType = SourceType.JMETER_CSV,
                sha256 = sha256,
                sizeBytes = 10,
                originalFilename = "input.jtl",
                path = Path.of("input.jtl"),
            ),
            policy = null,
        )
    }
}

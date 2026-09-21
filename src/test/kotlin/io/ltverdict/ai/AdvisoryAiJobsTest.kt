package io.ltverdict.ai

import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class AdvisoryAiJobsTest {
    @Test
    fun `latest tracks one active job per analysis through completion`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        AdvisoryAiJobs(
            generate = { _, _ ->
                started.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                AdviceRunResult.Saved(StoredAdvice(Path.of("advice"), buildJsonObject {}), reused = true)
            },
        ).use { jobs ->
            try {
                val submitted = accepted(jobs.submit(RUN_ID, ANALYSIS_ID)).status
                assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(AdviceJobState.PROCESSING, awaitState(jobs, submitted.jobId, AdviceJobState.PROCESSING).state)
                assertEquals(AdviceSubmitResult.Busy, jobs.submit(RUN_ID, ANALYSIS_ID))
                assertEquals(submitted.jobId, jobs.latest(RUN_ID, ANALYSIS_ID)?.jobId)

                release.countDown()
                val complete = awaitState(jobs, submitted.jobId, AdviceJobState.COMPLETE)
                assertEquals(true, complete.reused)
                assertNull(complete.failure)
                assertNull(complete.unavailableReason)
                assertEquals(complete, jobs.latest(RUN_ID, ANALYSIS_ID))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `typed failures and unavailable reasons survive status polling`() {
        val outcomes =
            mapOf(
                "failed" to AdviceRunResult.Failed(AdviceFailure.TIMEOUT),
                "unavailable" to AdviceRunResult.Unavailable(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED),
            )
        AdvisoryAiJobs(generate = { _, analysisId -> outcomes.getValue(analysisId) }).use { jobs ->
            val failed = accepted(jobs.submit(RUN_ID, "failed")).status
            val failedStatus = awaitState(jobs, failed.jobId, AdviceJobState.FAILED)
            assertEquals(AdviceFailure.TIMEOUT, failedStatus.failure)
            assertNull(failedStatus.unavailableReason)

            val unavailable = accepted(jobs.submit(RUN_ID, "unavailable")).status
            val unavailableStatus = awaitState(jobs, unavailable.jobId, AdviceJobState.UNAVAILABLE)
            assertEquals(AdviceUnavailableReason.CREDENTIAL_NOT_CONFIGURED, unavailableStatus.unavailableReason)
            assertNull(unavailableStatus.failure)
        }
    }

    @Test
    fun `cancel interrupts running work and prevents queued work from starting`() {
        val firstStarted = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val queuedStarted = AtomicBoolean(false)
        AdvisoryAiJobs(
            generate = { _, analysisId ->
                if (analysisId == "queued") queuedStarted.set(true)
                if (analysisId == "running") {
                    firstStarted.countDown()
                    try {
                        blocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (failure: InterruptedException) {
                        interrupted.countDown()
                        throw failure
                    }
                }
                AdviceRunResult.Failed(AdviceFailure.PROCESS_FAILED)
            },
        ).use { jobs ->
            val running = accepted(jobs.submit(RUN_ID, "running")).status
            assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val queued = accepted(jobs.submit(RUN_ID, "queued")).status

            assertEquals(AdviceJobState.CANCELLED, jobs.cancel(queued.jobId)?.state)
            assertEquals(AdviceJobState.CANCELLED, jobs.cancel(running.jobId)?.state)
            assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(queuedStarted.get())
            assertEquals(AdviceJobState.CANCELLED, jobs.status(running.jobId)?.state)
            assertEquals(AdviceJobState.CANCELLED, jobs.latest(RUN_ID, "queued")?.state)
        }
    }

    private fun accepted(result: AdviceSubmitResult): AdviceSubmitResult.Accepted =
        assertInstanceOf(AdviceSubmitResult.Accepted::class.java, result)

    private fun awaitState(
        jobs: AdvisoryAiJobs,
        jobId: String,
        expected: AdviceJobState,
    ): AdviceJobStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            jobs.status(jobId)?.let { if (it.state == expected) return it }
            LockSupport.parkNanos(100_000)
        }
        return fail("Advice job $jobId did not reach $expected; last=${jobs.status(jobId)}")
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
        const val RUN_ID = "jmeter_jtl_csv-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ANALYSIS_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}

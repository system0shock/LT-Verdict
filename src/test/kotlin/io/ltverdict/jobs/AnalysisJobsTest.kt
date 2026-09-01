package io.ltverdict.jobs

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.ingest.SourceType
import io.ltverdict.storage.AcceptedInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

class AnalysisJobsTest {
    @Test
    fun `parallelism must stay within the available processor range`() {
        val analyze: (AnalysisRequest, (Long) -> Unit, () -> Unit) -> AnalysisOutcome = { request, _, _ -> outcome(request) }
        val processors = Runtime.getRuntime().availableProcessors()

        assertThrows(IllegalArgumentException::class.java) { AnalysisJobs(0, analyze) }
        assertThrows(IllegalArgumentException::class.java) { AnalysisJobs(processors + 1, analyze) }
        setOf(1, processors).forEach { parallelism -> AnalysisJobs(parallelism, analyze).close() }
    }

    @Test
    fun `one running and one queued job make the next submission busy`() {
        val first = request(1)
        val second = request(2)
        val releaseFirst = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        val workerNames = ArrayBlockingQueue<String>(2)
        val jobs =
            AnalysisJobs(1) { request, _, _ ->
                workerNames.add(Thread.currentThread().name)
                if (request == first) {
                    firstStarted.countDown()
                    check(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "test did not release first job" }
                }
                outcome(request)
            }

        jobs.use {
            try {
                val running = accepted(jobs.submit(first))
                assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertEquals(JobState.PROCESSING, awaitState(jobs, running.status.jobId, JobState.PROCESSING).state)

                val queued = accepted(jobs.submit(second))
                assertEquals(JobState.QUEUED, jobs.status(queued.status.jobId)?.state)
                assertSame(SubmitResult.Busy, jobs.submit(request(3)))

                releaseFirst.countDown()
                assertEquals(JobState.COMPLETE, awaitState(jobs, running.status.jobId, JobState.COMPLETE).state)
                assertEquals(JobState.COMPLETE, awaitState(jobs, queued.status.jobId, JobState.COMPLETE).state)
                assertEquals(2, workerNames.size)
                assertTrue(workerNames.none { it.contains("netty", ignoreCase = true) })
            } finally {
                releaseFirst.countDown()
            }
        }
    }

    @Test
    fun `cancelling a queued job prevents it from starting`() {
        val first = request(10)
        val queued = request(11)
        val marker = request(12)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val queuedStarted = AtomicBoolean(false)
        val jobs =
            AnalysisJobs(1) { request, _, _ ->
                when (request) {
                    first -> {
                        firstStarted.countDown()
                        check(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "test did not release first job" }
                    }

                    queued -> queuedStarted.set(true)
                }
                outcome(request)
            }

        jobs.use {
            try {
                val runningJob = accepted(jobs.submit(first))
                assertTrue(firstStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val queuedJob = accepted(jobs.submit(queued))

                val cancelled = jobs.cancel(queuedJob.status.jobId)
                assertNotNull(cancelled)
                assertEquals(JobState.CANCELLED, cancelled?.state)

                releaseFirst.countDown()
                awaitState(jobs, runningJob.status.jobId, JobState.COMPLETE)
                val markerJob = submitEventually(jobs, marker)
                awaitState(jobs, markerJob.status.jobId, JobState.COMPLETE)

                assertFalse(queuedStarted.get())
                assertEquals(cancelled, jobs.status(queuedJob.status.jobId))
                assertEquals(cancelled, jobs.cancel(queuedJob.status.jobId))
            } finally {
                releaseFirst.countDown()
            }
        }
    }

    @Test
    fun `cancelling a running job interrupts it and trips the cooperative check`() {
        val runningRequest = request(20)
        val successorRequest = request(21)
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val cooperativeCheckFailed = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val jobs =
            AnalysisJobs(1) { request, _, checkCancelled ->
                if (request == successorRequest) {
                    checkCancelled()
                    return@AnalysisJobs outcome(request)
                }
                started.countDown()
                try {
                    check(blocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "running job was not interrupted" }
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                    val cancellation = runCatching { checkCancelled() }.exceptionOrNull()
                    checkNotNull(cancellation) { "cooperative cancellation check did not fail" }
                    cooperativeCheckFailed.countDown()
                    throw cancellation
                }
                outcome(request)
            }

        jobs.use {
            val submitted = accepted(jobs.submit(runningRequest))
            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val successor = accepted(jobs.submit(successorRequest))

            val cancelled = jobs.cancel(submitted.status.jobId)

            assertEquals(JobState.CANCELLED, cancelled?.state)
            assertTrue(interrupted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(cooperativeCheckFailed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(cancelled, jobs.status(submitted.status.jobId))
            assertEquals(cancelled, jobs.cancel(submitted.status.jobId))
            assertEquals(JobState.COMPLETE, awaitState(jobs, successor.status.jobId, JobState.COMPLETE).state)
        }
    }

    @Test
    fun `progress is observable monotonic bounded and stable after completion`() {
        val analysisRequest = request(30, sizeBytes = 10)
        val checkpoints = ArrayBlockingQueue<Long>(3)
        val advance = ArrayBlockingQueue<Unit>(3)
        val jobs =
            AnalysisJobs(1) { current, progress, _ ->
                listOf(2L, 7L, 10L).forEach { processed ->
                    progress(processed)
                    checkpoints.put(processed)
                    checkNotNull(advance.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "test did not advance progress" }
                }
                outcome(current)
            }

        jobs.use {
            val submitted = accepted(jobs.submit(analysisRequest))
            listOf(2L, 7L, 10L).forEach { expected ->
                assertEquals(expected, checkpoints.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val status = jobs.status(submitted.status.jobId)
                assertEquals(JobState.PROCESSING, status?.state)
                assertEquals(expected, status?.processedBytes)
                assertEquals(10L, status?.totalBytes)
                advance.put(Unit)
            }

            val complete = awaitState(jobs, submitted.status.jobId, JobState.COMPLETE)
            assertEquals(10L, complete.processedBytes)
            assertEquals(10L, complete.totalBytes)
            assertEquals(analysisRequest.input.sha256, complete.analysisId)
            assertEquals(complete, jobs.status(submitted.status.jobId))
            assertEquals(complete, jobs.cancel(submitted.status.jobId))
        }
    }

    @Test
    fun `only the 1024 newest terminal statuses are retained in transition order`() {
        AnalysisJobs(1) { request, _, _ -> outcome(request) }.use { jobs ->
            var oldestId: String? = null
            var secondOldestId: String? = null
            repeat(1_024) { index ->
                val submitted = submitEventually(jobs, request(100 + index))
                if (oldestId == null) oldestId = submitted.status.jobId
                if (index == 1) secondOldestId = submitted.status.jobId
                awaitState(jobs, submitted.status.jobId, JobState.COMPLETE)
            }

            assertNotNull(jobs.status(checkNotNull(oldestId)))
            val newest = submitEventually(jobs, request(2_000))
            awaitState(jobs, newest.status.jobId, JobState.COMPLETE)

            assertNull(jobs.status(checkNotNull(oldestId)))
            assertNotNull(jobs.status(checkNotNull(secondOldestId)))
            assertEquals(JobState.COMPLETE, jobs.status(newest.status.jobId)?.state)
        }
    }

    private fun accepted(result: SubmitResult): SubmitResult.Accepted = assertInstanceOf(SubmitResult.Accepted::class.java, result)

    private fun submitEventually(
        jobs: AnalysisJobs,
        request: AnalysisRequest,
    ): SubmitResult.Accepted {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            when (val result = jobs.submit(request)) {
                is SubmitResult.Accepted -> return result
                SubmitResult.Busy -> LockSupport.parkNanos(POLL_NANOS)
            }
        }
        return fail("job was not accepted before timeout")
    }

    private fun awaitState(
        jobs: AnalysisJobs,
        jobId: String,
        vararg expected: JobState,
    ): JobStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            jobs.status(jobId)?.let { status ->
                if (status.state in expected) return status
            }
            LockSupport.parkNanos(POLL_NANOS)
        }
        return fail("job $jobId did not reach ${expected.toList()}; last=${jobs.status(jobId)}")
    }

    private fun request(
        number: Int,
        sizeBytes: Long = 10,
    ): AnalysisRequest {
        val sha256 = number.toString(16).padStart(64, '0')
        return AnalysisRequest(
            AcceptedInput(
                runId = "jmeter_jtl_csv-$sha256",
                sourceType = SourceType.JMETER_CSV,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                originalFilename = "input-$number.jtl",
                path = Path.of("input-$number.jtl"),
            ),
            policy = null,
        )
    }

    private fun outcome(request: AnalysisRequest): AnalysisOutcome =
        AnalysisOutcome(
            runId = request.input.runId,
            analysisId = request.input.sha256,
            canonicalResult = request.input.runId.encodeToByteArray(),
            analysisDirectory = Path.of("analyses", request.input.sha256),
        )

    private companion object {
        const val TIMEOUT_SECONDS = 3L
        const val POLL_NANOS = 100_000L
    }
}

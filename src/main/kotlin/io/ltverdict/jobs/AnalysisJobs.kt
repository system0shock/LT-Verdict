package io.ltverdict.jobs

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.ingest.Diagnostic
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class JobState {
    QUEUED,
    PROCESSING,
    COMPLETE,
    FAILED,
    CANCELLED,
}

internal data class JobStatus(
    val jobId: String,
    val state: JobState,
    val processedBytes: Long,
    val totalBytes: Long,
    val runId: String,
    val analysisId: String? = null,
    val diagnostic: Diagnostic? = null,
)

internal sealed interface SubmitResult {
    data class Accepted(
        val status: JobStatus,
    ) : SubmitResult

    data object Busy : SubmitResult
}

internal class AnalysisJobs(
    parallelism: Int,
    private val analyze: (AnalysisRequest, (Long) -> Unit, () -> Unit) -> AnalysisOutcome,
) : AutoCloseable {
    private val lock = Any()
    private val statuses = mutableMapOf<String, JobStatus>()
    private val active = mutableMapOf<String, JobRecord>()
    private val terminalOrder = ArrayDeque<String>()
    private var closed = false
    private val executor: ThreadPoolExecutor

    init {
        require(parallelism in 1..Runtime.getRuntime().availableProcessors()) { "INVALID_ANALYSIS_PARALLELISM" }
        val threadNumber = AtomicInteger()
        val threadFactory =
            ThreadFactory { task ->
                Thread(task, "lt-verdict-analysis-${threadNumber.incrementAndGet()}")
            }
        executor =
            ThreadPoolExecutor(
                parallelism,
                parallelism,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(parallelism),
                threadFactory,
                ThreadPoolExecutor.AbortPolicy(),
            )
    }

    fun submit(request: AnalysisRequest): SubmitResult =
        synchronized(lock) {
            if (closed) return@synchronized SubmitResult.Busy
            val status =
                JobStatus(
                    jobId = UUID.randomUUID().toString(),
                    state = JobState.QUEUED,
                    processedBytes = 0,
                    totalBytes = request.input.sizeBytes,
                    runId = request.input.runId,
                )
            val record = JobRecord(request)
            val task = Runnable { run(status.jobId, record) }
            record.task = task
            statuses[status.jobId] = status
            active[status.jobId] = record
            try {
                executor.execute(task)
                SubmitResult.Accepted(statuses.getValue(status.jobId))
            } catch (_: RejectedExecutionException) {
                active.remove(status.jobId)
                statuses.remove(status.jobId)
                SubmitResult.Busy
            }
        }

    fun status(jobId: String): JobStatus? = synchronized(lock) { statuses[jobId] }

    fun cancel(jobId: String): JobStatus? {
        synchronized(lock) {
            val current = statuses[jobId] ?: return null
            if (current.state.isTerminal()) return current
            val record = active.getValue(jobId)
            record.cancelled.set(true)
            executor.remove(record.task)
            record.runner?.interrupt()
            return terminal(jobId, current.copy(state = JobState.CANCELLED))
        }
    }

    override fun close() {
        val runners =
            synchronized(lock) {
                if (closed) return
                closed = true
                active.entries.toList().mapNotNull { (jobId, record) ->
                    record.cancelled.set(true)
                    executor.remove(record.task)
                    val current = statuses.getValue(jobId)
                    terminal(jobId, current.copy(state = JobState.CANCELLED))
                    record.runner
                }
            }
        runners.forEach(Thread::interrupt)
        executor.shutdownNow()
        executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun run(
        jobId: String,
        record: JobRecord,
    ) {
        synchronized(lock) {
            val current = statuses[jobId] ?: return
            if (current.state.isTerminal() || record.cancelled.get()) return
            record.runner = Thread.currentThread()
            statuses[jobId] = current.copy(state = JobState.PROCESSING)
        }

        try {
            val outcome =
                analyze(
                    record.request,
                    { processed -> updateProgress(jobId, processed) },
                    { requireNotCancelled(record) },
                )
            synchronized(lock) {
                val current = statuses[jobId] ?: return@synchronized
                if (!current.state.isTerminal()) {
                    terminal(
                        jobId,
                        current.copy(
                            state = JobState.COMPLETE,
                            processedBytes = current.totalBytes,
                            analysisId = outcome.analysisId,
                        ),
                    )
                }
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                val current = statuses[jobId] ?: return@synchronized
                if (!current.state.isTerminal()) {
                    val cancelled = record.cancelled.get() || failure is CancellationException || failure is InterruptedException
                    terminal(
                        jobId,
                        current.copy(
                            state = if (cancelled) JobState.CANCELLED else JobState.FAILED,
                            diagnostic =
                                if (cancelled) {
                                    null
                                } else {
                                    Diagnostic("ANALYSIS_FAILED", "Analysis failed")
                                },
                        ),
                    )
                }
            }
        } finally {
            synchronized(lock) { record.runner = null }
        }
    }

    private fun updateProgress(
        jobId: String,
        processedBytes: Long,
    ) = synchronized(lock) {
        val current = statuses[jobId] ?: return@synchronized
        if (!current.state.isTerminal()) {
            statuses[jobId] =
                current.copy(
                    processedBytes = maxOf(current.processedBytes, processedBytes.coerceIn(0, current.totalBytes)),
                )
        }
    }

    private fun terminal(
        jobId: String,
        status: JobStatus,
    ): JobStatus {
        statuses[jobId] = status
        active.remove(jobId)
        terminalOrder.addLast(jobId)
        while (terminalOrder.size > RETAINED_TERMINAL_STATUSES) {
            statuses.remove(terminalOrder.removeFirst())
        }
        return status
    }

    private fun requireNotCancelled(record: JobRecord) {
        if (record.cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("CANCELLED")
    }

    private class JobRecord(
        val request: AnalysisRequest,
    ) {
        val cancelled = AtomicBoolean()
        lateinit var task: Runnable
        var runner: Thread? = null
    }

    private companion object {
        const val RETAINED_TERMINAL_STATUSES = 1_024
        const val CLOSE_TIMEOUT_SECONDS = 5L
    }
}

private fun JobState.isTerminal(): Boolean = this == JobState.COMPLETE || this == JobState.FAILED || this == JobState.CANCELLED

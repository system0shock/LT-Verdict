package io.ltverdict.ai

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

internal enum class AdviceJobState {
    QUEUED,
    PROCESSING,
    COMPLETE,
    FAILED,
    UNAVAILABLE,
    CANCELLED,
}

internal data class AdviceJobStatus(
    val jobId: String,
    val runId: String,
    val analysisId: String,
    val state: AdviceJobState,
    val reused: Boolean? = null,
    val failure: AdviceFailure? = null,
    val unavailableReason: AdviceUnavailableReason? = null,
)

internal sealed interface AdviceSubmitResult {
    data class Accepted(
        val status: AdviceJobStatus,
    ) : AdviceSubmitResult

    data object Busy : AdviceSubmitResult
}

internal class AdvisoryAiJobs(
    private val generate: (String, String) -> AdviceRunResult,
    parallelism: Int = 1,
) : AutoCloseable {
    constructor(service: AdvisoryAiService, parallelism: Int = 1) : this(service::generate, parallelism)

    private val lock = Any()
    private val statuses = mutableMapOf<String, AdviceJobStatus>()
    private val active = mutableMapOf<String, JobRecord>()
    private val activeByAnalysis = mutableMapOf<Pair<String, String>, String>()
    private val latestByAnalysis = mutableMapOf<Pair<String, String>, String>()
    private val terminalOrder = ArrayDeque<String>()
    private val executor: ThreadPoolExecutor
    private var closed = false

    init {
        require(parallelism in 1..Runtime.getRuntime().availableProcessors()) { "INVALID_AI_PARALLELISM" }
        val threadNumber = AtomicInteger()
        val threadFactory = ThreadFactory { task -> Thread(task, "lt-verdict-advisory-${threadNumber.incrementAndGet()}") }
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

    fun submit(
        runId: String,
        analysisId: String,
    ): AdviceSubmitResult =
        synchronized(lock) {
            val key = runId to analysisId
            if (closed || key in activeByAnalysis) return@synchronized AdviceSubmitResult.Busy
            val status =
                AdviceJobStatus(
                    jobId = UUID.randomUUID().toString(),
                    runId = runId,
                    analysisId = analysisId,
                    state = AdviceJobState.QUEUED,
                )
            val record = JobRecord(runId, analysisId)
            val task = Runnable { run(status.jobId, record) }
            record.task = task
            statuses[status.jobId] = status
            active[status.jobId] = record
            activeByAnalysis[key] = status.jobId
            latestByAnalysis[key] = status.jobId
            try {
                executor.execute(task)
                AdviceSubmitResult.Accepted(statuses.getValue(status.jobId))
            } catch (_: RejectedExecutionException) {
                active.remove(status.jobId)
                activeByAnalysis.remove(key)
                statuses.remove(status.jobId)
                if (latestByAnalysis[key] == status.jobId) latestByAnalysis.remove(key)
                AdviceSubmitResult.Busy
            }
        }

    fun status(jobId: String): AdviceJobStatus? = synchronized(lock) { statuses[jobId] }

    fun latest(
        runId: String,
        analysisId: String,
    ): AdviceJobStatus? = synchronized(lock) { latestByAnalysis[runId to analysisId]?.let(statuses::get) }

    fun cancel(jobId: String): AdviceJobStatus? =
        synchronized(lock) {
            val current = statuses[jobId] ?: return null
            if (current.state.isTerminal()) return current
            val record = active.getValue(jobId)
            record.cancelled.set(true)
            executor.remove(record.task)
            record.runner?.interrupt()
            terminal(jobId, current.copy(state = AdviceJobState.CANCELLED))
        }

    override fun close() {
        val runners =
            synchronized(lock) {
                if (closed) return
                closed = true
                active.entries.toList().mapNotNull { (jobId, record) ->
                    record.cancelled.set(true)
                    executor.remove(record.task)
                    terminal(jobId, statuses.getValue(jobId).copy(state = AdviceJobState.CANCELLED))
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
            statuses[jobId] = current.copy(state = AdviceJobState.PROCESSING)
        }
        try {
            val result = generate(record.runId, record.analysisId)
            synchronized(lock) {
                val current = statuses[jobId] ?: return@synchronized
                if (!current.state.isTerminal()) {
                    terminal(
                        jobId,
                        when (result) {
                            is AdviceRunResult.Saved -> current.copy(state = AdviceJobState.COMPLETE, reused = result.reused)
                            is AdviceRunResult.Failed -> current.copy(state = AdviceJobState.FAILED, failure = result.reason)
                            is AdviceRunResult.Unavailable ->
                                current.copy(state = AdviceJobState.UNAVAILABLE, unavailableReason = result.reason)
                        },
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
                            state = if (cancelled) AdviceJobState.CANCELLED else AdviceJobState.FAILED,
                            failure = if (cancelled) null else AdviceFailure.PROCESS_FAILED,
                        ),
                    )
                }
            }
        } finally {
            synchronized(lock) { record.runner = null }
        }
    }

    private fun terminal(
        jobId: String,
        status: AdviceJobStatus,
    ): AdviceJobStatus {
        statuses[jobId] = status
        val record = active.remove(jobId)
        if (record != null) activeByAnalysis.remove(record.runId to record.analysisId, jobId)
        terminalOrder.addLast(jobId)
        while (terminalOrder.size > RETAINED_TERMINAL_STATUSES) {
            val removedId = terminalOrder.removeFirst()
            val removed = statuses.remove(removedId) ?: continue
            latestByAnalysis.remove(removed.runId to removed.analysisId, removedId)
        }
        return status
    }

    private class JobRecord(
        val runId: String,
        val analysisId: String,
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

private fun AdviceJobState.isTerminal(): Boolean =
    this == AdviceJobState.COMPLETE ||
        this == AdviceJobState.FAILED ||
        this == AdviceJobState.UNAVAILABLE ||
        this == AdviceJobState.CANCELLED

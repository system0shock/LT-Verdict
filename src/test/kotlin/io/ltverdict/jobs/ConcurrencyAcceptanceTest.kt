package io.ltverdict.jobs

import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService
import io.ltverdict.core.EngineConfig
import io.ltverdict.core.PolicyValidation
import io.ltverdict.core.validatePolicy
import io.ltverdict.storage.DataDirectory
import io.ltverdict.storage.RunBundleStore
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConcurrencyAcceptanceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `parallel analyses match fresh sequential canonical results`() {
        val csv = Files.readAllBytes(Path.of(CSV_FIXTURE))
        val xml = Files.readAllBytes(Path.of(XML_FIXTURE))
        val policy = passPolicy()
        val expectedCsv = sequentialResult(csv, "input.jtl", policy)
        val expectedXml = sequentialResult(xml, "input.xml", policy)

        DataDirectory.open(Files.createTempDirectory(tempDir, "parallel-")).use { directory ->
            val store = RunBundleStore(directory)
            val csvInput = store.acceptInput(ByteArrayInputStream(csv), "input.jtl")
            val xmlInput = store.acceptInput(ByteArrayInputStream(xml), "input.xml")
            val service = AnalysisService(store, EngineConfig())
            val bothParsing = CountDownLatch(2)

            AnalysisJobs(
                parallelism = 2,
                analyze = { request, progress, checkCancelled ->
                    var entered = false
                    service.analyze(
                        request,
                        { processed ->
                            if (!entered) {
                                entered = true
                                bothParsing.countDown()
                                check(bothParsing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    "analyses did not run concurrently"
                                }
                            }
                            progress(processed)
                        },
                        checkCancelled,
                    )
                },
            ).use { jobs ->
                val csvJob = accepted(jobs.submit(AnalysisRequest(csvInput, policy)))
                val xmlJob = accepted(jobs.submit(AnalysisRequest(xmlInput, policy)))
                val csvStatus = awaitComplete(jobs, csvJob.jobId)
                val xmlStatus = awaitComplete(jobs, xmlJob.jobId)

                val csvStored = store.readAnalysis(csvInput.runId, requireNotNull(csvStatus.analysisId))!!
                val xmlStored = store.readAnalysis(xmlInput.runId, requireNotNull(xmlStatus.analysisId))!!
                assertArrayEquals(expectedCsv, Files.readAllBytes(csvStored.path.resolve(RESULT_FILE)))
                assertArrayEquals(expectedXml, Files.readAllBytes(xmlStored.path.resolve(RESULT_FILE)))
            }
        }
    }

    @Test
    fun `duplicate concurrent jobs converge on one validated analysis directory`() {
        val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))
        val policy = passPolicy()

        DataDirectory.open(Files.createTempDirectory(tempDir, "duplicate-")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(bytes), "input.jtl")
            val request = AnalysisRequest(input, policy)
            val service = AnalysisService(store, EngineConfig())
            val bothParsing = CountDownLatch(2)

            AnalysisJobs(
                parallelism = 2,
                analyze = { submitted, progress, checkCancelled ->
                    var entered = false
                    service.analyze(
                        submitted,
                        { processed ->
                            if (!entered) {
                                entered = true
                                bothParsing.countDown()
                                check(bothParsing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                                    "analyses did not run concurrently"
                                }
                            }
                            progress(processed)
                        },
                        checkCancelled,
                    )
                },
            ).use { jobs ->
                val firstJob = accepted(jobs.submit(request))
                val secondJob = accepted(jobs.submit(request))
                assertNotEquals(firstJob.jobId, secondJob.jobId)

                val first = awaitComplete(jobs, firstJob.jobId)
                val second = awaitComplete(jobs, secondJob.jobId)
                val analysisId = requireNotNull(first.analysisId)
                assertEquals(analysisId, second.analysisId)

                val analyses =
                    input.path.parent.parent
                        .resolve("analyses")
                val published =
                    Files.list(analyses).use { paths ->
                        paths.map { it.fileName.toString() }.toList()
                    }
                assertEquals(listOf(analysisId), published)

                val stored = requireNotNull(store.readAnalysis(input.runId, analysisId))
                assertEquals(analyses.resolve(analysisId), stored.path)
                assertEquals(COMPLETE_ARTIFACTS, stored.artifacts.map { it.path }.toSet())
                assertTrue(Files.isRegularFile(stored.path.resolve("manifest.json")))
            }
        }
    }

    @Test
    fun `running cancellation removes derived staging and preserves accepted input`() {
        val bytes = Files.readAllBytes(Path.of(CSV_FIXTURE))

        DataDirectory.open(Files.createTempDirectory(tempDir, "cancel-")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(bytes), "input.jtl")
            val staging = AtomicReference<Path>()
            val writing = CountDownLatch(1)
            val cleaned = CountDownLatch(1)
            val analysisId = "0".repeat(64)

            AnalysisJobs(1) { request, _, _ ->
                try {
                    store.writeAnalysisAtomically(request.input.runId, analysisId) { path ->
                        staging.set(path)
                        Files.writeString(path.resolve("partial"), "partial")
                        writing.countDown()
                        CountDownLatch(1).await()
                    }
                } finally {
                    cleaned.countDown()
                }
                error("cancelled analysis continued")
            }.use { jobs ->
                val job = accepted(jobs.submit(AnalysisRequest(input, null)))
                assertTrue(writing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

                assertEquals(JobState.CANCELLED, jobs.cancel(job.jobId)?.state)
                assertTrue(cleaned.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(Files.exists(staging.get()))
                assertArrayEquals(bytes, Files.readAllBytes(input.path))
                assertNull(store.readAnalysis(input.runId, analysisId))
            }
        }
    }

    private fun sequentialResult(
        bytes: ByteArray,
        filename: String,
        policy: PolicyValidation.Valid,
    ): ByteArray =
        DataDirectory.open(Files.createTempDirectory(tempDir, "sequential-")).use { directory ->
            val store = RunBundleStore(directory)
            val input = store.acceptInput(ByteArrayInputStream(bytes), filename)
            AnalysisService(store, EngineConfig()).analyze(AnalysisRequest(input, policy)).canonicalResult
        }

    private fun accepted(result: SubmitResult): JobStatus = assertInstanceOf(SubmitResult.Accepted::class.java, result).status

    private fun awaitComplete(
        jobs: AnalysisJobs,
        jobId: String,
    ): JobStatus {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val status = jobs.status(jobId) ?: fail("job disappeared: $jobId")
            when (status.state) {
                JobState.COMPLETE -> return status
                JobState.FAILED,
                JobState.CANCELLED,
                -> fail("job ended as ${status.state}: ${status.diagnostic}")

                JobState.QUEUED,
                JobState.PROCESSING,
                -> Thread.yield()
            }
        }
        return fail("job did not complete: $jobId")
    }

    private fun passPolicy(): PolicyValidation.Valid =
        assertInstanceOf(
            PolicyValidation.Valid::class.java,
            validatePolicy(ByteArrayInputStream(PASS_POLICY.encodeToByteArray())),
        )

    private companion object {
        const val CSV_FIXTURE = "fixtures/slice1/jmeter/csv-5.6.3/input.jtl"
        const val XML_FIXTURE = "fixtures/slice1/jmeter/xml-5.6.3/input.xml"
        const val RESULT_FILE = "analysis-result.json"
        const val TIMEOUT_SECONDS = 10L
        const val PASS_POLICY =
            """{"schema_version":"policy.v1","policy_id":"parallel","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}]}"""

        val COMPLETE_ARTIFACTS =
            setOf(
                "analysis-result.json",
                "identity.json",
                "normalized-1s.ndjson",
                "rollup-10s.ndjson",
                "rollup-30s.ndjson",
                "rollup-60s.ndjson",
                "run.json",
            )
    }
}

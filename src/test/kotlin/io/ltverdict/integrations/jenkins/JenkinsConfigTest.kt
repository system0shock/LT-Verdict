package io.ltverdict.integrations.jenkins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JenkinsConfigTest {
    @Test
    fun `strict config exposes browser-safe summaries without credential references`() {
        val connections =
            readJenkinsConnections(
                """
                {
                  "schema_version":"jenkins-connections.v1",
                  "profiles":[{
                    "id":"perf",
                    "controller":"https://jenkins.example/",
                    "job_path":"job/performance-test",
                    "auth":{"username_env":"JENKINS_USER","api_token_env":"JENKINS_API_TOKEN"},
                    "parameter_names":["SCENARIO","PASSWORD"],
                    "sensitive_parameter_names":["PASSWORD"],
                    "artifact_paths":["run/results.jtl","run/simulation.log"],
                    "correlation_parameter":"LT_VERDICT_TRIGGER_ATTEMPT_ID",
                    "timeout_ms":30000,
                    "poll_interval_ms":1000,
                    "reconciliation_polls":3,
                    "max_artifact_bytes":3221225472
                  }]
                }
                """.trimIndent().byteInputStream(),
            )

        val profile = connections.profile("perf")
        assertEquals("JENKINS_USER", profile.auth.usernameEnvironment)
        assertEquals("JENKINS_API_TOKEN", profile.auth.apiTokenEnvironment)
        assertEquals(setOf("run/results.jtl", "run/simulation.log"), profile.artifactPaths)
        assertEquals(3_221_225_472L, profile.maxArtifactBytes)

        val summary = connections.summaries().single()
        assertEquals("perf", summary.id)
        assertEquals(setOf("SCENARIO", "PASSWORD"), summary.parameterNames)
        assertTrue("JENKINS_USER" !in summary.toString())
        assertTrue("JENKINS_API_TOKEN" !in summary.toString())
        assertTrue("PASSWORD" in summary.parameterNames)
    }

    @Test
    fun `config rejects unknown fields unsafe paths and invalid sensitive parameter declarations`() {
        val invalidProfiles =
            listOf(
                """{"id":"perf","controller":"https://jenkins.example/","job_path":"job/perf","auth":{"username_env":"USER","api_token_env":"TOKEN"},"parameter_names":[],"sensitive_parameter_names":[],"artifact_paths":["run/results.jtl"],"unknown":true}""",
                """{"id":"perf","controller":"https://jenkins.example/","job_path":"job/perf","auth":{"username_env":"USER","api_token_env":"TOKEN"},"parameter_names":[],"sensitive_parameter_names":[],"artifact_paths":["../results.jtl"]}""",
                """{"id":"perf","controller":"https://user:secret@jenkins.example/","job_path":"job/perf","auth":{"username_env":"USER","api_token_env":"TOKEN"},"parameter_names":[],"sensitive_parameter_names":[],"artifact_paths":["run/results.jtl"]}""",
                """{"id":"perf","controller":"http://jenkins.example/","job_path":"job/perf","auth":{"username_env":"USER","api_token_env":"TOKEN"},"parameter_names":[],"sensitive_parameter_names":[],"artifact_paths":["run/results.jtl"]}""",
                """{"id":"perf","controller":"https://jenkins.example/","job_path":"job/perf","auth":{"username_env":"USER","api_token_env":"TOKEN"},"parameter_names":["SCENARIO"],"sensitive_parameter_names":["PASSWORD"],"artifact_paths":["run/results.jtl"]}""",
            )

        invalidProfiles.forEach { profile ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    readJenkinsConnections(
                        """{"schema_version":"jenkins-connections.v1","profiles":[$profile]}""".byteInputStream(),
                    )
                }
            assertEquals("JENKINS_CONFIG_INVALID", failure.message)
        }
    }
}

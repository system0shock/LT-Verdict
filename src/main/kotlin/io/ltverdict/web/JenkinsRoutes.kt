package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ltverdict.integrations.jenkins.ArtifactExpectation
import io.ltverdict.integrations.jenkins.JenkinsRunState
import io.ltverdict.integrations.jenkins.JenkinsTriggerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore

internal fun Route.jenkinsRoutes(
    context: LocalApiContext,
    jenkinsPermit: Semaphore,
) {
    get("/api/jenkins") {
        call.requireOnlyQueries()
        call.respondJson(
            buildJsonObject {
                put(
                    "profiles",
                    buildJsonArray {
                        context.jenkinsProfiles.forEach { profile ->
                            add(
                                buildJsonObject {
                                    put("id", profile.id)
                                    put("controller", profile.controller.toString())
                                    put("job_path", profile.jobPath)
                                    put("parameter_names", JsonArray(profile.parameterNames.map(::JsonPrimitive)))
                                    put("artifact_paths", JsonArray(profile.artifactPaths.map(::JsonPrimitive)))
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    get("/api/jenkins/{profileId}/attempts") {
        call.requireOnlyQueries()
        val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
        val states = withContext(Dispatchers.IO) { workflow.listStates() }
        call.respondJson(buildJsonObject { put("attempts", JsonArray(states.map { it.toJson() })) })
    }

    post("/api/jenkins/{profileId}/trigger") {
        call.requireOnlyQueries()
        call.requireJson()
        val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
        val body = receiveBaselineRequest(call)
        if (body.keys != setOf("parameters")) malformed("Only parameters are allowed")
        val parameters = body["parameters"] as? JsonObject ?: malformed("parameters must be an object")
        val values =
            parameters.mapValues { (_, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: malformed("Parameter values must be strings")
            }
        if (!jenkinsPermit.tryAcquire()) throw ApiFailure(HttpStatusCode.Conflict, "JENKINS_BUSY", "A Jenkins operation is active")
        try {
            val state = withContext(Dispatchers.IO) { workflow.trigger(JenkinsTriggerRequest(values)) }
            call.respondJson(state.toJson(), HttpStatusCode.Accepted)
        } catch (_: IllegalArgumentException) {
            malformed("Jenkins request is invalid")
        } finally {
            jenkinsPermit.release()
        }
    }

    post("/api/jenkins/{profileId}/attempts/{attemptId}/{operation}") {
        call.requireOnlyQueries()
        call.requireJson()
        val workflow = context.jenkinsWorkflows[call.parameters["profileId"]] ?: notFound("Jenkins profile was not found")
        val attemptId = call.parameters["attemptId"].orEmpty()
        val operation = call.parameters["operation"].orEmpty()
        val body = receiveBaselineRequest(call)
        if (operation !in setOf("advance", "reconcile", "collect")) notFound("Jenkins operation was not found")
        if (operation != "collect" && body.isNotEmpty()) malformed("Operation body must be empty")
        if (!jenkinsPermit.tryAcquire()) throw ApiFailure(HttpStatusCode.Conflict, "JENKINS_BUSY", "A Jenkins operation is active")
        try {
            val response =
                withContext(Dispatchers.IO) {
                    val state =
                        when (operation) {
                            "advance" -> workflow.advance(attemptId)
                            "reconcile" -> workflow.reconcile(attemptId)
                            else -> {
                                if (body.keys != setOf("artifact_path")) malformed("artifact_path is required")
                                val artifact =
                                    (body["artifact_path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                                        ?: malformed("artifact_path must be a string")
                                workflow.collectArtifact(
                                    attemptId,
                                    ArtifactExpectation(artifact),
                                    context.jenkinsArtifactRoot ?: error("JENKINS_STORAGE_UNAVAILABLE"),
                                )
                            }
                        }
                    buildJsonObject {
                        put("attempt", state.toJson())
                        if (operation == "collect" && state.artifact != null) {
                            val artifact = checkNotNull(state.artifact)
                            val input =
                                Files.newInputStream(artifact.path).use {
                                    context.store.acceptInput(it, Path.of(artifact.relativePath).fileName.toString())
                                }
                            put("run", input.toJson())
                        } else {
                            put("run", JsonNull)
                        }
                    }
                }
            call.respondJson(response)
        } catch (_: NoSuchElementException) {
            notFound("Jenkins attempt was not found")
        } catch (_: IllegalArgumentException) {
            malformed("Jenkins operation or artifact is invalid")
        } finally {
            jenkinsPermit.release()
        }
    }
}

private fun JenkinsRunState.toJson(): JsonObject =
    buildJsonObject {
        put("attempt_id", attemptId)
        put("status", status.name)
        put("build_number", buildNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("failure_code", failureCode?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "artifact",
            artifact?.let { value ->
                buildJsonObject {
                    put("relative_path", value.relativePath)
                    put("size_bytes", value.sizeBytes)
                    put("sha256", value.sha256)
                }
            } ?: JsonNull,
        )
    }

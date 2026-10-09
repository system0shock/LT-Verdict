package io.ltverdict.web

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ltverdict.core.canonicalJson
import io.ltverdict.sources.capturePostgresPhase
import io.ltverdict.sources.readPostgresAnalysisInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Semaphore

internal fun Route.sourceRoutes(
    context: LocalApiContext,
    postgresCapturePermit: Semaphore,
) {
    get("/api/sources") {
        call.requireOnlyQueries()
        call.respondJson(
            buildJsonObject {
                put(
                    "profiles",
                    buildJsonArray {
                        context.sourceProfiles.forEach { profile ->
                            add(
                                buildJsonObject {
                                    put("id", profile.id)
                                    put("source_kind", profile.sourceKind.wireName)
                                    put("transport", profile.transport.wireName)
                                    profile.arm?.let { put("arm", it) }
                                },
                            )
                        }
                        context.postgresProfiles.forEach { profile ->
                            add(
                                buildJsonObject {
                                    put("id", profile.id)
                                    put("source_kind", "postgresql")
                                    put("transport", "jdbc")
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    listOf("pre", "post").forEach { phase ->
        post("/api/sources/postgresql/$phase") {
            call.requireOnlyQueries()
            call.requireMultipart()
            val (profileId, pre) = receivePostgresCapture(call, phase == "post")
            val profile = context.postgresProfiles.singleOrNull { it.id == profileId } ?: malformed("Source profile is not configured")
            if (!postgresCapturePermit.tryAcquire()) conflict("BUSY", "PostgreSQL capture is already running")
            val captured =
                try {
                    withContext(Dispatchers.IO) {
                        val jobContext = currentCoroutineContext()
                        capturePostgresPhase(profile, pre, post = phase == "post", checkCancelled = { jobContext.ensureActive() })
                    }
                } catch (failure: IllegalArgumentException) {
                    throw ApiFailure(
                        HttpStatusCode.UnprocessableEntity,
                        failure.message?.takeIf { Regex("PG_[A-Z_]+").matches(it) } ?: "PG_CAPTURE_FAILED",
                        "PostgreSQL capture failed",
                    )
                } finally {
                    postgresCapturePermit.release()
                }
            call.respondJson(
                buildJsonObject {
                    put("schema_version", "postgres-capture.v1")
                    put("phase_json", canonicalJson(captured.phase).decodeToString())
                    put(
                        "pg_profile_html_base64",
                        captured.pgProfileHtml?.let { JsonPrimitive(Base64.getEncoder().encodeToString(it)) } ?: JsonNull,
                    )
                },
            )
        }
    }
}

private suspend fun receivePostgresCapture(
    call: ApplicationCall,
    post: Boolean,
): Pair<String, JsonObject?> {
    val length =
        call.request.contentLength()
            ?: throw ApiFailure(HttpStatusCode.LengthRequired, "LENGTH_REQUIRED", "Capture request requires Content-Length")
    if (length > MAX_RESOURCE_BYTES + MAX_POLICY_BYTES) tooLarge("Capture request exceeds its resource limit")
    var profileId: String? = null
    var pre: JsonObject? = null
    var parts = 0
    try {
        call.receiveMultipart(formFieldLimit = (MAX_RESOURCE_BYTES + 1).toLong()).forEachPart { part ->
            try {
                if (++parts > 2) malformed("Capture request has too many parts")
                when {
                    part is PartData.FormItem && part.name == "profile_id" && profileId == null -> {
                        if (part.value.isBlank() ||
                            part.value.encodeToByteArray().size > 128 ||
                            part.value.any(Char::isISOControl)
                        ) {
                            malformed("Profile ID is invalid")
                        }
                        profileId = part.value
                    }
                    part is PartData.FileItem && part.name == "pre" && post && pre == null -> {
                        pre = withContext(Dispatchers.IO) { readPostgresAnalysisInput(pre = part.provider().toInputStream()).pre }
                    }
                    else -> malformed("Capture multipart body is invalid")
                }
            } finally {
                part.release()
            }
        }
    } catch (failure: ApiFailure) {
        throw failure
    } catch (_: IllegalArgumentException) {
        malformed("PostgreSQL pre phase is invalid")
    } catch (_: java.io.IOException) {
        malformed("Capture multipart body is malformed")
    }
    return (profileId ?: malformed("Profile ID is required")) to pre
}

package io.ltverdict.integrations.jenkins

import io.ltverdict.core.canonicalJson
import io.ltverdict.core.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

internal data class JenkinsTriggerRequest(
    val parameters: Map<String, String>,
)

internal data class ArtifactExpectation(
    val relativePath: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
)

internal data class JenkinsArtifact(
    val path: Path,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal enum class JenkinsStatus {
    TRIGGER_INTENT,
    TRIGGERING,
    RECONCILING,
    QUEUED,
    RUNNING,
    AWAITING_ARTIFACT,
    ARTIFACT_READY,
    TRIGGER_UNKNOWN,
    FAILED,
}

internal data class JenkinsRunState(
    val attemptId: String,
    val status: JenkinsStatus,
    val queueUrl: URI? = null,
    val buildUrl: URI? = null,
    val buildNumber: Long? = null,
    val buildResult: String? = null,
    val availableArtifactPaths: Set<String> = emptySet(),
    val artifact: JenkinsArtifact? = null,
    val failureCode: String? = null,
    val parameterSha256: String,
    val updatedAt: Instant,
)

internal class JenkinsCredentials(
    val username: String,
    val apiToken: String,
) {
    override fun toString(): String = "JenkinsCredentials(***)"
}

internal class JenkinsWorkflow(
    private val profile: JenkinsProfile,
    private val journalRoot: Path,
    private val environment: (String) -> String? = System::getenv,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val clock: () -> Instant = Instant::now,
    private val attemptIds: () -> String = { UUID.randomUUID().toString() },
    private val sleeper: (Long) -> Unit = Thread::sleep,
) {
    init {
        validateProfile(profile)
    }

    @Synchronized
    fun trigger(request: JenkinsTriggerRequest): JenkinsRunState {
        validateParameters(request.parameters)
        val attemptId = attemptIds().also(::requireAttemptId)
        val parameters = request.parameters + (profile.correlationParameter to attemptId)
        val initial =
            JenkinsRunState(
                attemptId = attemptId,
                status = JenkinsStatus.TRIGGER_INTENT,
                parameterSha256 = parameterHash(parameters),
                updatedAt = clock(),
            )
        createJournal(initial, "TRIGGER_INTENT")

        val credentials =
            try {
                credentials()
            } catch (_: JenkinsFailure) {
                return transition(initial, JenkinsStatus.FAILED, "AUTH_UNAVAILABLE", failureCode = "JENKINS_AUTH_UNAVAILABLE")
            }
        val crumb =
            try {
                crumb(credentials)
            } catch (_: JenkinsFailure) {
                return transition(initial, JenkinsStatus.FAILED, "CRUMB_FAILED", failureCode = "JENKINS_CRUMB_FAILED")
            }
        val triggering = transition(initial, JenkinsStatus.TRIGGERING, "TRIGGERING")
        val response =
            try {
                send(
                    request(profile.controller.resolve("${profile.jobPath}/buildWithParameters"), credentials)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .apply { crumb?.let { header(it.first, it.second) } }
                        .POST(HttpRequest.BodyPublishers.ofString(form(parameters)))
                        .build(),
                    MAX_CONTROL_RESPONSE_BYTES,
                )
            } catch (_: JenkinsFailure) {
                return transition(triggering, JenkinsStatus.RECONCILING, "TRIGGER_OUTCOME_UNKNOWN")
            }

        if (response.statusCode in 200..299) {
            val location = response.location?.let(::validatedQueueUrl)
            return if (location == null) {
                transition(triggering, JenkinsStatus.RECONCILING, "TRIGGER_LOCATION_UNKNOWN")
            } else {
                transition(triggering.copy(queueUrl = location), JenkinsStatus.QUEUED, "QUEUED")
            }
        }
        return if (response.statusCode >= 500) {
            transition(triggering, JenkinsStatus.RECONCILING, "TRIGGER_OUTCOME_UNKNOWN")
        } else {
            transition(triggering, JenkinsStatus.FAILED, "TRIGGER_REJECTED", failureCode = "JENKINS_TRIGGER_REJECTED")
        }
    }

    @Synchronized
    fun reconcile(attemptId: String): JenkinsRunState {
        requireAttemptId(attemptId)
        var state = state(attemptId)
        if (state.status !in
            setOf(JenkinsStatus.RECONCILING, JenkinsStatus.TRIGGER_UNKNOWN, JenkinsStatus.TRIGGER_INTENT, JenkinsStatus.TRIGGERING)
        ) {
            return state
        }
        state = transition(state, JenkinsStatus.RECONCILING, "RECONCILING", failureCode = null)
        val credentials =
            runCatching { credentials() }.getOrNull()
                ?: return transition(
                    state,
                    JenkinsStatus.TRIGGER_UNKNOWN,
                    "RECONCILIATION_AUTH_FAILED",
                    failureCode = "JENKINS_RECONCILIATION_FAILED",
                )

        repeat(profile.reconciliationPolls) { poll ->
            val candidates =
                try {
                    reconciliationCandidates(attemptId, credentials)
                } catch (_: JenkinsFailure) {
                    emptyList()
                }
            if (candidates.size > 1) {
                return transition(state, JenkinsStatus.TRIGGER_UNKNOWN, "TRIGGER_AMBIGUOUS", failureCode = "JENKINS_TRIGGER_AMBIGUOUS")
            }
            candidates.singleOrNull()?.let { candidate ->
                return if (candidate.buildUrl != null) {
                    transition(
                        state.copy(buildUrl = candidate.buildUrl, buildNumber = candidate.buildNumber, queueUrl = candidate.queueUrl),
                        JenkinsStatus.RUNNING,
                        "BUILD_RECONCILED",
                    )
                } else {
                    transition(state.copy(queueUrl = candidate.queueUrl), JenkinsStatus.QUEUED, "QUEUE_RECONCILED")
                }
            }
            if (poll + 1 < profile.reconciliationPolls) sleep()
        }
        return transition(state, JenkinsStatus.TRIGGER_UNKNOWN, "TRIGGER_NOT_FOUND", failureCode = "JENKINS_TRIGGER_NOT_FOUND")
    }

    @Synchronized
    fun advance(attemptId: String): JenkinsRunState {
        requireAttemptId(attemptId)
        val current = state(attemptId)
        val credentials =
            try {
                credentials()
            } catch (_: JenkinsFailure) {
                return transition(current, JenkinsStatus.FAILED, "AUTH_UNAVAILABLE", failureCode = "JENKINS_AUTH_UNAVAILABLE")
            }
        return when (current.status) {
            JenkinsStatus.TRIGGER_INTENT,
            JenkinsStatus.TRIGGERING,
            JenkinsStatus.RECONCILING,
            JenkinsStatus.TRIGGER_UNKNOWN,
            -> reconcile(attemptId)
            JenkinsStatus.QUEUED -> advanceQueue(current, credentials)
            JenkinsStatus.RUNNING,
            JenkinsStatus.AWAITING_ARTIFACT,
            -> refreshBuild(current, credentials)
            JenkinsStatus.ARTIFACT_READY,
            JenkinsStatus.FAILED,
            -> current
        }
    }

    @Synchronized
    fun collectArtifact(
        attemptId: String,
        expectation: ArtifactExpectation,
        destinationRoot: Path,
    ): JenkinsRunState {
        requireAttemptId(attemptId)
        validateExpectation(expectation)
        var current = state(attemptId)
        if (current.status == JenkinsStatus.ARTIFACT_READY && current.artifact?.let(::artifactStillValid) == true) return current
        if (current.status !in setOf(JenkinsStatus.RUNNING, JenkinsStatus.AWAITING_ARTIFACT, JenkinsStatus.ARTIFACT_READY)) {
            throw IllegalArgumentException("JENKINS_INVALID_STATE")
        }
        val credentials =
            try {
                credentials()
            } catch (_: JenkinsFailure) {
                return transition(current, JenkinsStatus.FAILED, "AUTH_UNAVAILABLE", failureCode = "JENKINS_AUTH_UNAVAILABLE")
            }
        current = refreshBuild(current, credentials)
        if (current.status == JenkinsStatus.RUNNING) return current
        if (expectation.relativePath !in current.availableArtifactPaths) {
            return if (current.status == JenkinsStatus.AWAITING_ARTIFACT) {
                current
            } else {
                transition(current, JenkinsStatus.AWAITING_ARTIFACT, "ARTIFACT_MISSING")
            }
        }

        val root = ensureDirectory(destinationRoot.toAbsolutePath().normalize())
        val attemptRoot = ensureDirectory(root.resolve(attemptId))
        val target = attemptRoot.resolve(expectation.relativePath).normalize()
        if (!target.startsWith(attemptRoot)) throw IllegalArgumentException("JENKINS_ARTIFACT_PATH_INVALID")
        existingArtifact(target, expectation)?.let { artifact ->
            return transition(current.copy(artifact = artifact), JenkinsStatus.ARTIFACT_READY, "ARTIFACT_REUSED")
        }
        ensureDirectory(checkNotNull(target.parent))
        val staging = attemptRoot.resolve(".download.part")
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) Files.delete(staging)
        return try {
            val buildUrl = current.buildUrl ?: throw JenkinsFailure()
            val artifactUrl = validatedArtifactUrl(buildUrl, expectation.relativePath)
            val response = sendStream(request(artifactUrl, credentials).GET().build())
            if (response.statusCode == 404) {
                response.body.close()
                return transition(current, JenkinsStatus.AWAITING_ARTIFACT, "ARTIFACT_MISSING")
            }
            if (response.statusCode !in 200..299) {
                response.body.close()
                return transition(current, JenkinsStatus.FAILED, "ARTIFACT_HTTP_FAILED", failureCode = "JENKINS_ARTIFACT_HTTP_FAILED")
            }
            val declaredLength = response.contentLength
            if (declaredLength != null &&
                (declaredLength > profile.maxArtifactBytes || expectation.sizeBytes?.let { it != declaredLength } == true)
            ) {
                response.body.close()
                val code = if (declaredLength > profile.maxArtifactBytes) "JENKINS_ARTIFACT_TOO_LARGE" else "JENKINS_ARTIFACT_SIZE_MISMATCH"
                return transition(current, JenkinsStatus.FAILED, "ARTIFACT_REJECTED", failureCode = code)
            }
            val downloaded = response.body.use { copyArtifact(it, staging) }
            val failure =
                when {
                    expectation.sizeBytes != null && expectation.sizeBytes != downloaded.sizeBytes -> "JENKINS_ARTIFACT_SIZE_MISMATCH"
                    expectation.sha256 != null && expectation.sha256.lowercase() != downloaded.sha256 -> "JENKINS_ARTIFACT_HASH_MISMATCH"
                    else -> null
                }
            if (failure != null) {
                Files.deleteIfExists(staging)
                return transition(current, JenkinsStatus.FAILED, "ARTIFACT_REJECTED", failureCode = failure)
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(staging)
                val existing =
                    existingArtifact(target, expectation)
                        ?: return transition(current, JenkinsStatus.FAILED, "ARTIFACT_EXISTS", failureCode = "JENKINS_ARTIFACT_EXISTS")
                return transition(current.copy(artifact = existing), JenkinsStatus.ARTIFACT_READY, "ARTIFACT_REUSED")
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
            forceDirectory(target.parent)
            val artifact = JenkinsArtifact(target, expectation.relativePath, downloaded.sizeBytes, downloaded.sha256)
            transition(current.copy(artifact = artifact), JenkinsStatus.ARTIFACT_READY, "ARTIFACT_READY")
        } catch (_: ArtifactTooLarge) {
            Files.deleteIfExists(staging)
            transition(current, JenkinsStatus.FAILED, "ARTIFACT_REJECTED", failureCode = "JENKINS_ARTIFACT_TOO_LARGE")
        } catch (_: JenkinsFailure) {
            Files.deleteIfExists(staging)
            transition(current, JenkinsStatus.FAILED, "ARTIFACT_TRANSPORT_FAILED", failureCode = "JENKINS_ARTIFACT_TRANSPORT_FAILED")
        } catch (_: IOException) {
            Files.deleteIfExists(staging)
            transition(current, JenkinsStatus.FAILED, "ARTIFACT_STORAGE_FAILED", failureCode = "JENKINS_ARTIFACT_STORAGE_FAILED")
        }
    }

    @Synchronized
    fun state(attemptId: String): JenkinsRunState {
        requireAttemptId(attemptId)
        return readJournal(journalPath(attemptId))
    }

    @Synchronized
    fun listStates(limit: Int = 20): List<JenkinsRunState> {
        require(limit in 1..MAX_LIST_STATES) { "JENKINS_LIST_LIMIT_INVALID" }
        if (!Files.exists(journalRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(journalRoot) || !Files.isDirectory(journalRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        }
        val journals =
            Files.newDirectoryStream(journalRoot, "*.jsonl").use { entries ->
                entries
                    .asSequence()
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    .take(MAX_JOURNAL_FILES + 1)
                    .toList()
            }
        if (journals.size > MAX_JOURNAL_FILES) throw IllegalStateException("JENKINS_JOURNAL_LIMIT_EXCEEDED")
        return journals
            .sortedByDescending { Files.getLastModifiedTime(it) }
            .take(limit)
            .map(::readJournal)
    }

    private fun advanceQueue(
        state: JenkinsRunState,
        credentials: JenkinsCredentials,
    ): JenkinsRunState {
        val queueUrl = state.queueUrl ?: return transition(state, JenkinsStatus.RECONCILING, "QUEUE_URL_MISSING")
        val response =
            try {
                getJson(apiUrl(queueUrl), credentials)
            } catch (_: JenkinsNotFound) {
                return reconcile(state.attemptId)
            } catch (_: JenkinsFailure) {
                return transition(state, JenkinsStatus.QUEUED, "QUEUE_POLL_FAILED", failureCode = "JENKINS_QUEUE_POLL_FAILED")
            }
        if (response.boolean("cancelled") == true) {
            return transition(state, JenkinsStatus.FAILED, "QUEUE_CANCELLED", failureCode = "JENKINS_QUEUE_CANCELLED")
        }
        val executable = response.objectValueOrNull("executable") ?: return state
        val buildNumber = executable.long("number") ?: return transition(state, JenkinsStatus.RECONCILING, "BUILD_NUMBER_MISSING")
        val buildUrl =
            executable.string("url")?.let(::parseUri)?.let { validatedBuildUrl(it, buildNumber) }
                ?: return transition(state, JenkinsStatus.RECONCILING, "BUILD_URL_MISSING")
        return transition(state.copy(buildUrl = buildUrl, buildNumber = buildNumber), JenkinsStatus.RUNNING, "RUNNING")
    }

    private fun refreshBuild(
        state: JenkinsRunState,
        credentials: JenkinsCredentials,
    ): JenkinsRunState {
        val buildUrl = state.buildUrl ?: return transition(state, JenkinsStatus.RECONCILING, "BUILD_URL_MISSING")
        val response =
            try {
                getJson(apiUrl(buildUrl), credentials)
            } catch (_: JenkinsFailure) {
                return transition(state, state.status, "BUILD_POLL_FAILED", failureCode = "JENKINS_BUILD_POLL_FAILED")
            }
        if (response.boolean("building") == true) {
            return if (state.status == JenkinsStatus.RUNNING && state.failureCode == null) {
                state
            } else {
                transition(state, JenkinsStatus.RUNNING, "RUNNING", failureCode = null)
            }
        }
        val available =
            response
                .arrayOrNull("artifacts")
                .orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("relativePath") }
                .filterTo(sortedSetOf()) { it in profile.artifactPaths }
        return transition(
            state.copy(buildResult = response.string("result"), availableArtifactPaths = available),
            JenkinsStatus.AWAITING_ARTIFACT,
            if (available.isEmpty()) "ARTIFACT_MISSING" else "ARTIFACT_AVAILABLE",
            failureCode = null,
        )
    }

    private fun reconciliationCandidates(
        attemptId: String,
        credentials: JenkinsCredentials,
    ): List<ReconciliationCandidate> {
        val queueTree = formEncode("items[id,url,executable[number,url],actions[parameters[name,value]]]")
        val buildsTree = formEncode("builds[number,url,building,result,actions[parameters[name,value]]]")
        val queue = getJson(profile.controller.resolve("queue/api/json?tree=$queueTree"), credentials)
        val builds = getJson(profile.controller.resolve("${profile.jobPath}/api/json?tree=$buildsTree"), credentials)
        val candidates = mutableListOf<ReconciliationCandidate>()
        queue.arrayOrNull("items").orEmpty().filterIsInstance<JsonObject>().forEach { item ->
            if (!item.hasCorrelation(attemptId)) return@forEach
            val queueUrl = item.string("url")?.let(::parseUri)?.let(::validatedQueueUrl) ?: return@forEach
            val executable = item.objectValueOrNull("executable")
            val number = executable?.long("number")
            val buildUrl =
                executable
                    ?.string(
                        "url",
                    )?.takeIf { number != null }
                    ?.let(::parseUri)
                    ?.let { validatedBuildUrl(it, checkNotNull(number)) }
            candidates += ReconciliationCandidate(queueUrl, buildUrl, number)
        }
        builds.arrayOrNull("builds").orEmpty().filterIsInstance<JsonObject>().forEach { build ->
            if (!build.hasCorrelation(attemptId)) return@forEach
            val number = build.long("number") ?: return@forEach
            val buildUrl = build.string("url")?.let(::parseUri)?.let { validatedBuildUrl(it, number) } ?: return@forEach
            candidates += ReconciliationCandidate(null, buildUrl, number)
        }
        return candidates.distinctBy { it.buildUrl ?: it.queueUrl }
    }

    private fun JsonObject.hasCorrelation(attemptId: String): Boolean =
        arrayOrNull("actions")
            .orEmpty()
            .filterIsInstance<JsonObject>()
            .flatMap { it.arrayOrNull("parameters").orEmpty().filterIsInstance<JsonObject>() }
            .any { it.string("name") == profile.correlationParameter && it.string("value") == attemptId }

    private fun crumb(credentials: JenkinsCredentials): Pair<String, String>? {
        val response =
            send(request(profile.controller.resolve("crumbIssuer/api/json"), credentials).GET().build(), MAX_CONTROL_RESPONSE_BYTES)
        if (response.statusCode == 404) return null
        if (response.statusCode !in 200..299) throw JenkinsFailure()
        val value = parseJson(response.body)
        val field = value.string("crumbRequestField") ?: throw JenkinsFailure()
        val crumb = value.string("crumb") ?: throw JenkinsFailure()
        if (!HEADER_NAME.matches(field) || crumb.isEmpty() || crumb.length > MAX_CREDENTIAL_CHARS || crumb.any(Char::isISOControl)) {
            throw JenkinsFailure()
        }
        return field to crumb
    }

    private fun getJson(
        uri: URI,
        credentials: JenkinsCredentials,
    ): JsonObject {
        val response = send(request(uri, credentials).GET().build(), MAX_CONTROL_RESPONSE_BYTES)
        if (response.statusCode == 404) throw JenkinsNotFound()
        if (response.statusCode !in 200..299) throw JenkinsFailure()
        return parseJson(response.body)
    }

    private fun send(
        request: HttpRequest,
        maxBytes: Int,
    ): JenkinsResponse {
        val response = sendStream(request)
        val bytes = response.body.use { it.readNBytes(maxBytes + 1) }
        if (bytes.size > maxBytes) throw JenkinsFailure()
        return JenkinsResponse(
            response.statusCode,
            bytes,
            response.location,
        )
    }

    private fun sendStream(request: HttpRequest): StreamResponse =
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            StreamResponse(
                response.statusCode(),
                response.body(),
                response
                    .headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1)
                    .takeIf { it >= 0 },
                response
                    .headers()
                    .firstValue("Location")
                    .orElse(null)
                    ?.let { runCatching { URI(it) }.getOrNull() },
            )
        } catch (_: IOException) {
            throw JenkinsFailure()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw JenkinsFailure()
        }

    private fun request(
        uri: URI,
        credentials: JenkinsCredentials,
    ): HttpRequest.Builder {
        requireSameOrigin(uri)
        val encoded = Base64.getEncoder().encodeToString("${credentials.username}:${credentials.apiToken}".toByteArray(Charsets.UTF_8))
        return HttpRequest
            .newBuilder(uri)
            .timeout(profile.timeout)
            .header("Accept", "application/json")
            .header("Authorization", "Basic $encoded")
    }

    private fun credentials(): JenkinsCredentials {
        val username = environment(profile.auth.usernameEnvironment)
        val apiToken = environment(profile.auth.apiTokenEnvironment)
        if (!validCredential(username) || !validCredential(apiToken)) throw JenkinsFailure()
        return JenkinsCredentials(checkNotNull(username), checkNotNull(apiToken))
    }

    private fun validateParameters(parameters: Map<String, String>) {
        if (parameters.keys.any { it !in profile.parameterNames } || parameters.size > profile.parameterNames.size) {
            throw IllegalArgumentException("JENKINS_PARAMETERS_INVALID")
        }
        if (parameters.values.any { it.length > MAX_PARAMETER_CHARS || it.any(Char::isISOControl) }) {
            throw IllegalArgumentException("JENKINS_PARAMETERS_INVALID")
        }
    }

    private fun validateExpectation(expectation: ArtifactExpectation) {
        if (expectation.relativePath !in profile.artifactPaths) throw IllegalArgumentException("JENKINS_ARTIFACT_PATH_INVALID")
        if (expectation.sizeBytes != null && expectation.sizeBytes !in 0..profile.maxArtifactBytes) {
            throw IllegalArgumentException("JENKINS_ARTIFACT_SIZE_INVALID")
        }
        if (expectation.sha256 != null && !SHA256.matches(expectation.sha256)) {
            throw IllegalArgumentException("JENKINS_ARTIFACT_HASH_INVALID")
        }
    }

    private fun parameterHash(parameters: Map<String, String>): String =
        sha256Hex(
            canonicalJson(
                buildJsonObject {
                    parameters.toSortedMap().forEach { (name, value) ->
                        put(name, if (name in profile.sensitiveParameterNames) JsonNull else JsonPrimitive(value))
                    }
                },
            ),
        )

    private fun form(parameters: Map<String, String>): String =
        parameters.toSortedMap().entries.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }

    private fun validatedQueueUrl(uri: URI): URI? =
        uri.takeIf {
            sameOrigin(it, profile.controller) &&
                normalizedPath(it).startsWith(normalizedPath(profile.controller) + "queue/item/") &&
                it.rawQuery == null &&
                it.rawFragment == null &&
                it.rawUserInfo == null
        }

    private fun validatedBuildUrl(
        uri: URI,
        number: Long,
    ): URI? =
        uri.takeIf {
            number >= 0 &&
                sameOrigin(it, profile.controller) &&
                normalizedPath(it).startsWith(normalizedPath(profile.controller) + profile.jobPath + "/$number/") &&
                it.rawQuery == null &&
                it.rawFragment == null &&
                it.rawUserInfo == null
        }

    private fun validatedArtifactUrl(
        buildUrl: URI,
        relativePath: String,
    ): URI {
        val uri = buildUrl.resolve("artifact/${relativePath.split('/').joinToString("/") { pathEncode(it) }}")
        requireSameOrigin(uri)
        return uri
    }

    private fun apiUrl(base: URI): URI {
        requireSameOrigin(base)
        return base.resolve("api/json")
    }

    private fun requireSameOrigin(uri: URI) {
        if (!sameOrigin(uri, profile.controller) || uri.rawUserInfo != null) throw JenkinsFailure()
    }

    private fun transition(
        source: JenkinsRunState,
        status: JenkinsStatus,
        event: String,
        failureCode: String? = source.failureCode,
    ): JenkinsRunState {
        val state = source.copy(status = status, failureCode = failureCode, updatedAt = clock())
        appendJournal(state, event)
        return state
    }

    private fun createJournal(
        state: JenkinsRunState,
        event: String,
    ) {
        ensureDirectory(journalRoot)
        val path = journalPath(state.attemptId)
        val bytes = journalRecord(state, event, 1) + byteArrayOf('\n'.code.toByte())
        try {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.writeFully(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            forceDirectory(journalRoot)
        } catch (_: IOException) {
            throw IllegalStateException("JENKINS_JOURNAL_WRITE_FAILED")
        }
    }

    private fun appendJournal(
        state: JenkinsRunState,
        event: String,
    ) {
        val path = journalPath(state.attemptId)
        val sequence = journalSequence(path) + 1
        val bytes = journalRecord(state, event, sequence) + byteArrayOf('\n'.code.toByte())
        try {
            FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS).use { channel ->
                channel.writeFully(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
        } catch (_: IOException) {
            throw IllegalStateException("JENKINS_JOURNAL_WRITE_FAILED")
        }
    }

    private fun journalRecord(
        state: JenkinsRunState,
        event: String,
        sequence: Long,
    ): ByteArray {
        val payload = state.toJson(sequence, event)
        return canonicalJson(
            buildJsonObject {
                put("payload", payload)
                put("sha256", sha256Hex(canonicalJson(payload)))
            },
        )
    }

    private fun journalSequence(path: Path): Long = readJournalRecords(path).last().first

    private fun readJournal(path: Path): JenkinsRunState = readJournalRecords(path).last().second

    private fun readJournalRecords(path: Path): List<Pair<Long, JenkinsRunState>> {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path)
        ) {
            throw NoSuchElementException("JENKINS_ATTEMPT_NOT_FOUND")
        }
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_JOURNAL_BYTES + 1) }
        if (bytes.size > MAX_JOURNAL_BYTES) throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        val text = bytes.decodeToString()
        val complete = if (text.endsWith('\n')) text.dropLast(1) else text.substringBeforeLast('\n', "")
        if (complete.isEmpty()) throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        val records = complete.lines()
        if (records.size > MAX_JOURNAL_RECORDS) throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        var expected = 1L
        return records.map { line ->
            val record = parseJson(line.encodeToByteArray())
            val payload = record["payload"] as? JsonObject ?: throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
            val hash = record.string("sha256")
            if (hash != sha256Hex(canonicalJson(payload))) throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
            val sequence = payload.long("sequence") ?: throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
            if (sequence != expected++) throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
            sequence to payload.toState()
        }
    }

    private fun JenkinsRunState.toJson(
        sequence: Long,
        event: String,
    ): JsonObject =
        buildJsonObject {
            put("sequence", sequence)
            put("event", event)
            put("attempt_id", attemptId)
            put("profile_id", profile.id)
            put("job_path", profile.jobPath)
            put("parameter_sha256", parameterSha256)
            put("recorded_at", updatedAt.toString())
            put("status", status.name)
            putNullable("queue_url", queueUrl?.toString())
            putNullable("build_url", buildUrl?.toString())
            put("build_number", buildNumber?.let(::JsonPrimitive) ?: JsonNull)
            putNullable("build_result", buildResult)
            put("available_artifact_paths", buildJsonArray { availableArtifactPaths.sorted().forEach { add(JsonPrimitive(it)) } })
            putNullable("failure_code", failureCode)
            put(
                "artifact",
                artifact?.let {
                    buildJsonObject {
                        put(
                            "path",
                            it.path
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        )
                        put("relative_path", it.relativePath)
                        put("size_bytes", it.sizeBytes)
                        put("sha256", it.sha256)
                    }
                } ?: JsonNull,
            )
        }

    private fun JsonObject.toState(): JenkinsRunState {
        if (string("profile_id") != profile.id ||
            string("job_path") != profile.jobPath
        ) {
            throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        }
        val artifactValue = this["artifact"] as? JsonObject
        return try {
            JenkinsRunState(
                attemptId = checkNotNull(string("attempt_id")).also(::requireAttemptId),
                status = JenkinsStatus.valueOf(checkNotNull(string("status"))),
                queueUrl = string("queue_url")?.let { URI(it) }?.also { if (validatedQueueUrl(it) == null) throw IllegalStateException() },
                buildUrl = string("build_url")?.let { URI(it) },
                buildNumber = long("build_number"),
                buildResult = string("build_result"),
                availableArtifactPaths = arrayOrNull("available_artifact_paths").orEmpty().map { (it as JsonPrimitive).content }.toSet(),
                artifact =
                    artifactValue?.let {
                        JenkinsArtifact(
                            Path.of(checkNotNull(it.string("path"))),
                            checkNotNull(it.string("relative_path")),
                            checkNotNull(it.long("size_bytes")),
                            checkNotNull(it.string("sha256")),
                        )
                    },
                failureCode = string("failure_code"),
                parameterSha256 = checkNotNull(string("parameter_sha256")),
                updatedAt = Instant.parse(checkNotNull(string("recorded_at"))),
            ).also { state ->
                state.buildUrl?.let {
                    if (state.buildNumber == null ||
                        validatedBuildUrl(it, state.buildNumber) == null
                    ) {
                        throw IllegalStateException()
                    }
                }
                if (!SHA256.matches(state.parameterSha256) ||
                    state.availableArtifactPaths.any { it !in profile.artifactPaths }
                ) {
                    throw IllegalStateException()
                }
            }
        } catch (_: Exception) {
            throw IllegalStateException("JENKINS_JOURNAL_CORRUPT")
        }
    }

    private fun copyArtifact(
        source: InputStream,
        target: Path,
    ): DownloadedArtifact {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                if (total > profile.maxArtifactBytes) throw ArtifactTooLarge()
                digest.update(buffer, 0, count)
                channel.writeFully(ByteBuffer.wrap(buffer, 0, count))
            }
            channel.force(true)
        }
        return DownloadedArtifact(total, HexFormat.of().formatHex(digest.digest()))
    }

    private fun existingArtifact(
        path: Path,
        expectation: ArtifactExpectation,
    ): JenkinsArtifact? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = Files.size(path)
        if (size > profile.maxArtifactBytes || expectation.sizeBytes?.let { it != size } == true) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = HexFormat.of().formatHex(digest.digest())
        if (expectation.sha256?.lowercase()?.let { it != hash } == true) return null
        return JenkinsArtifact(path, expectation.relativePath, size, hash)
    }

    private fun artifactStillValid(artifact: JenkinsArtifact): Boolean =
        existingArtifact(artifact.path, ArtifactExpectation(artifact.relativePath, artifact.sizeBytes, artifact.sha256)) != null

    private fun sleep() {
        try {
            sleeper(profile.pollInterval.toMillis())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw JenkinsFailure()
        }
    }

    private fun journalPath(attemptId: String): Path = journalRoot.resolve("$attemptId.jsonl")

    private fun requireAttemptId(value: String) {
        if (!ATTEMPT_ID.matches(value)) throw IllegalArgumentException("JENKINS_ATTEMPT_INVALID")
    }
}

private data class JenkinsResponse(
    val statusCode: Int,
    val body: ByteArray,
    val location: URI?,
)

private data class StreamResponse(
    val statusCode: Int,
    val body: InputStream,
    val contentLength: Long?,
    val location: URI?,
)

private data class ReconciliationCandidate(
    val queueUrl: URI?,
    val buildUrl: URI?,
    val buildNumber: Long?,
)

private data class DownloadedArtifact(
    val sizeBytes: Long,
    val sha256: String,
)

private open class JenkinsFailure : RuntimeException()

private class JenkinsNotFound : JenkinsFailure()

private class ArtifactTooLarge : RuntimeException()

private fun parseJson(bytes: ByteArray): JsonObject =
    try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    } catch (_: Exception) {
        throw JenkinsFailure()
    }

private fun sameOrigin(
    first: URI,
    second: URI,
): Boolean =
    first.scheme.equals(second.scheme, true) &&
        first.host.equals(second.host, true) &&
        effectivePort(first) == effectivePort(second)

private fun effectivePort(uri: URI): Int =
    when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("http", true) -> 80
        uri.scheme.equals("https", true) -> 443
        else -> -1
    }

private fun normalizedPath(uri: URI): String = uri.path.orEmpty().let { if (it.endsWith('/')) it else "$it/" }

private fun formEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

private fun pathEncode(value: String): String = formEncode(value).replace("+", "%20")

private fun validCredential(value: String?): Boolean =
    value != null && value.isNotEmpty() && value.length <= MAX_CREDENTIAL_CHARS && value.none { it == '\r' || it == '\n' }

private fun JsonObject.string(name: String): String? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw JenkinsFailure()
}

private fun JsonObject.long(name: String): Long? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    if (value !is JsonPrimitive || value.isString) throw JenkinsFailure()
    return value.longOrNull ?: throw JenkinsFailure()
}

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    if (value !is JsonPrimitive || value.isString) throw JenkinsFailure()
    return value.booleanOrNull ?: throw JenkinsFailure()
}

private fun JsonObject.objectValueOrNull(name: String): JsonObject? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    return value as? JsonObject ?: throw JenkinsFailure()
}

private fun JsonObject.arrayOrNull(name: String): JsonArray? {
    val value = this[name] ?: return null
    if (value === JsonNull) return null
    return value as? JsonArray ?: throw JenkinsFailure()
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun ensureDirectory(path: Path): Path {
    rejectSymlinkComponents(path)
    Files.createDirectories(path)
    rejectSymlinkComponents(path)
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw IllegalArgumentException("JENKINS_STORAGE_UNSAFE")
    return path
}

private fun rejectSymlinkComponents(path: Path) {
    var current = path.root
    path.forEach { component ->
        current = current.resolve(component)
        if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw IllegalArgumentException("JENKINS_STORAGE_UNSAFE")
        }
    }
}

private fun parseUri(value: String): URI? = runCatching { URI(value) }.getOrNull()

private fun forceDirectory(path: Path) {
    try {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: UnsupportedOperationException) {
        // Directory fsync is not exposed on every supported filesystem.
    } catch (_: IOException) {
        if (!System.getProperty("os.name").startsWith("Windows", true)) throw JenkinsFailure()
    }
}

private fun FileChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) write(buffer)
}

private const val MAX_CONTROL_RESPONSE_BYTES = 1_048_576
private const val MAX_PARAMETER_CHARS = 8_192
private const val MAX_CREDENTIAL_CHARS = 8_192
private const val MAX_JOURNAL_BYTES = 4 * 1_048_576
private const val MAX_JOURNAL_RECORDS = 1_024
private const val MAX_JOURNAL_FILES = 10_000
private const val MAX_LIST_STATES = 100
private val ATTEMPT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
private val SHA256 = Regex("[0-9a-fA-F]{64}")
private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,128}")

package io.ltverdict.mcp

import io.ltverdict.core.canonicalJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8

internal class McpServer(
    private val tools: McpTools,
    private val serverVersion: String,
    private val stdin: InputStream = System.`in`,
    private val stdout: PrintStream = System.out,
    private val stderr: PrintStream = System.err,
) {
    private var useContentLengthFraming = false

    fun run() {
        val buffered = BufferedInputStream(stdin, STREAM_BUFFER_BYTES)
        while (true) {
            val frame =
                try {
                    readFrame(buffered) ?: return
                } catch (failure: McpFramingException) {
                    stderr.println("mcp framing error: ${failure.message}")
                    writeError(JsonNull, -32700, "Parse error")
                    continue
                }
            handleMessage(frame)
        }
    }

    private fun readFrame(input: BufferedInputStream): String? {
        while (true) {
            val line = readLineUtf8(input) ?: return null
            if (line.isEmpty()) continue
            if (useContentLengthFraming) {
                if (!looksLikeHeaderLine(line)) throw McpFramingException("expected header line")
                val headers = readHeaderBlock(input, line, allowReset = false) ?: return null
                return readBodyFromHeaders(input, headers)
            }
            if (looksLikeHeaderLine(line)) {
                val knownLspHeader =
                    line.startsWith("Content-Length:", ignoreCase = true) ||
                        line.startsWith("Content-Type:", ignoreCase = true)
                if (knownLspHeader) useContentLengthFraming = true
                val headers = readHeaderBlock(input, line, allowReset = !knownLspHeader)
                if (headers != null) {
                    return readBodyFromHeaders(input, headers)
                }
                if (knownLspHeader) return null
            }
            return line
        }
    }

    private fun readHeaderBlock(
        input: BufferedInputStream,
        firstLine: String,
        allowReset: Boolean,
    ): List<String>? {
        if (allowReset) input.mark(MAX_HEADER_BLOCK_BYTES)
        val headers = mutableListOf(firstLine)
        var totalBytes = firstLine.encodeToByteArray().size
        while (true) {
            val remainingBudget = if (allowReset) MAX_HEADER_BLOCK_BYTES - totalBytes else MAX_MESSAGE_BYTES
            if (remainingBudget <= 0) {
                if (allowReset) {
                    input.reset()
                    return null
                }
                throw McpFramingException("malformed header block")
            }
            val next = readLineUtf8(input, maxLineBytes = remainingBudget, drainOversized = !allowReset)
            if (next == null) {
                if (allowReset) input.reset()
                return null
            }
            if (next.isEmpty()) {
                if (allowReset && headers.none { it.startsWith("Content-Length:", ignoreCase = true) }) {
                    input.reset()
                    return null
                }
                return headers
            }
            totalBytes += next.encodeToByteArray().size + 2
            if (!looksLikeHeaderLine(next) || headers.size >= MAX_HEADER_LINES || totalBytes > MAX_HEADER_BLOCK_BYTES) {
                if (allowReset) {
                    input.reset()
                    return null
                }
                throw McpFramingException("malformed header block")
            }
            headers += next
        }
    }

    private fun readBodyFromHeaders(
        input: BufferedInputStream,
        headers: List<String>,
    ): String? {
        var contentLength: Int? = null
        for (header in headers) {
            val name = header.substringBefore(':').trim()
            if (name.equals("Content-Length", ignoreCase = true)) {
                if (contentLength != null) throw McpFramingException("duplicate Content-Length")
                contentLength =
                    header
                        .substringAfter(':')
                        .trim()
                        .toIntOrNull()
                        ?.takeIf { it in 1..MAX_MESSAGE_BYTES }
                        ?: throw McpFramingException("invalid Content-Length")
            }
        }
        val length = contentLength ?: throw McpFramingException("missing Content-Length")
        useContentLengthFraming = true
        val bytes = input.readNBytes(length)
        if (bytes.size != length) return null
        return bytes.decodeToString()
    }

    private fun looksLikeHeaderLine(line: String): Boolean = HEADER_LINE_REGEX.matches(line)

    private fun readLineUtf8(
        input: BufferedInputStream,
        maxLineBytes: Int = MAX_MESSAGE_BYTES,
        drainOversized: Boolean = true,
    ): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (out.size() == 0) null else out.toByteArray().decodeToString()
            }
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return bytes.decodeToString(0, end)
            }
            if (out.size() >= maxLineBytes) {
                if (!drainOversized) return null
                while (true) {
                    val skipped = input.read()
                    if (skipped == -1 || skipped == '\n'.code) break
                }
                throw McpFramingException("line exceeds maximum message size")
            }
            out.write(b)
        }
    }

    private fun handleMessage(raw: String) {
        val element =
            try {
                Json.parseToJsonElement(raw)
            } catch (_: SerializationException) {
                writeError(JsonNull, -32700, "Parse error")
                return
            }
        val request = element as? JsonObject
        if (request == null) {
            writeError(JsonNull, -32600, "Invalid Request")
            return
        }
        val id = request["id"]
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            if (id != null && id !is JsonNull) writeError(id, -32600, "Invalid Request")
            return
        }
        // Notifications carry no id and expect no response.
        if (id == null) {
            return
        }
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        when (method) {
            "initialize" -> {
                val requestedVersion =
                    (params["protocolVersion"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: DEFAULT_PROTOCOL_VERSION
                writeResult(
                    id,
                    buildJsonObject {
                        put("protocolVersion", requestedVersion)
                        put(
                            "capabilities",
                            buildJsonObject {
                                put("tools", buildJsonObject { put("listChanged", false) })
                            },
                        )
                        put(
                            "serverInfo",
                            buildJsonObject {
                                put("name", "lt-verdict-mcp")
                                put("version", serverVersion)
                            },
                        )
                    },
                )
            }
            "ping" -> writeResult(id, JsonObject(emptyMap()))
            "tools/list" ->
                writeResult(
                    id,
                    buildJsonObject {
                        put("tools", tools.listTools())
                    },
                )
            "tools/call" -> {
                val toolName = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (toolName == null) {
                    writeError(id, -32602, "Invalid params: tool name is required")
                    return
                }
                val args =
                    when (val rawArgs = params["arguments"]) {
                        null, is JsonNull -> JsonObject(emptyMap())
                        is JsonObject -> rawArgs
                        else -> {
                            writeError(id, -32602, "Invalid params: arguments must be an object")
                            return
                        }
                    }
                val result = tools.callTool(toolName, args)
                writeResult(
                    id,
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", canonicalJson(result.payload).decodeToString())
                                    },
                                )
                            },
                        )
                        put("isError", result.isError)
                    },
                )
            }
            else -> writeError(id, -32601, "Method not found: $method")
        }
    }

    private fun writeResult(
        id: JsonElement,
        result: JsonObject,
    ) {
        writeResponse(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    private fun writeError(
        id: JsonElement,
        code: Int,
        message: String,
    ) {
        writeResponse(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            },
        )
    }

    private fun writeResponse(payload: JsonObject) {
        val bytes = canonicalJson(payload)
        if (useContentLengthFraming) {
            stdout.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(UTF_8))
            stdout.write(bytes)
        } else {
            stdout.write(bytes)
            stdout.write('\n'.code)
        }
        stdout.flush()
    }

    private class McpFramingException(
        override val message: String,
    ) : RuntimeException(message)

    companion object {
        private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
        private const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val MAX_HEADER_BLOCK_BYTES = 16 * 1024
        private const val MAX_HEADER_LINES = 64
        private val HEADER_LINE_REGEX = Regex("^[A-Za-z][A-Za-z0-9-]*:[ \\t]*.*$")
    }
}

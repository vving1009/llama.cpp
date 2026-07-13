package com.arm.aichat.mcp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A single MCP (Model Context Protocol) connection over an [McpTransport].
 *
 * Implements JSON-RPC 2.0 over newline-delimited JSON (NDJSON), mirroring the
 * original TypeScript implementation at `claude-code-from-scratch/src/mcp.ts`.
 *
 * Lifecycle: connect → initialize → listTools → callTool (×N) → close
 *            └─── all happens in constructor ───┘
 */
class McpConnection internal constructor(
    private val serverName: String,
    private val transport: McpTransport
) {
    private val reader: BufferedReader
    private val writer: PrintWriter
    private var nextId = 1
    private val pending = mutableMapOf<Int, PendingRequest>()
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private data class PendingRequest(
        val resolve: (JSONObject) -> Unit,
        val reject: (Exception) -> Unit
    )

    init {
        reader = BufferedReader(InputStreamReader(transport.inputStream))
        writer = PrintWriter(transport.outputStream, true) // auto-flush

        // Background reader: read NDJSON lines from the transport
        readerJob = scope.launch {
            try {
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line == null) {
                        Log.w(TAG, "MCP reader for '$serverName' reached end of stream")
                        break
                    }
                    handleLine(line)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "MCP reader for '$serverName' stopped: ${e.message}")
                }
            }
        }
    }

    /**
     * Perform the MCP initialize handshake.
     * Sends `initialize` request, then `notifications/initialized` notification.
     */
    suspend fun initialize() {
        withTimeout(TIMEOUT_MS) {
            sendRequest("initialize", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "android-aichat")
                    put("version", "1.0.0")
                })
            })
        }
        sendNotification("notifications/initialized")
        Log.i(TAG, "MCP initialized for '$serverName'")
    }

    /**
     * Discover available tools from this server.
     * Returns an empty list on any failure (the manager handles errors).
     */
    suspend fun listTools(): List<McpToolInfo> {
        return try {
            val result = withTimeout(TIMEOUT_MS) {
                sendRequest("tools/list")
            }
            val tools = result.optJSONArray("tools") ?: return emptyList()
            val list = mutableListOf<McpToolInfo>()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                list.add(
                    McpToolInfo(
                        name = t.getString("name"),
                        description = t.optString("description", ""),
                        inputSchema = t.optJSONObject("inputSchema"),
                        serverName = serverName
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list tools from '$serverName': ${e.message}")
            emptyList()
        }
    }

    /**
     * Call a tool on this server.
     *
     * @param name The tool name (without the `mcp__server__` prefix).
     * @param args Typed JSON arguments for the tool.
     * @return The concatenated text content from the MCP response.
     */
    suspend fun callTool(name: String, args: JSONObject): String {
        val result = sendRequest("tools/call", JSONObject().apply {
            put("name", name)
            put("arguments", args)
        })
        return McpJsonRpc.extractTextContent(result) ?: result.toString()
    }

    /**
     * Close the connection and cancel the background reader.
     * Closes the transport first to unblock the reader, then cancels the
     * coroutine scope and rejects any remaining pending requests.
     */
    fun close() {
        // Close the transport first (this unblocks the reader thread)
        transport.close()

        // Reject all pending requests
        for ((_, entry) in pending) {
            entry.reject(McpException(-1, "MCP connection closed"))
        }
        pending.clear()
        readerJob?.cancel()
        readerJob = null
        scope.cancel()
        try { writer.close() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }

    // ─── Private: JSON-RPC protocol ───────────────────────────

    /**
     * Send a JSON-RPC 2.0 request and wait for the response by ID.
     */
    private suspend fun sendRequest(method: String, params: JSONObject = JSONObject()): JSONObject {
        return suspendCancellableCoroutine { cont ->
            val id = nextId++
            pending[id] = PendingRequest(
                resolve = { cont.resume(it) },
                reject = { cont.resumeWithException(it) }
            )
            try {
                val msg = McpJsonRpc.buildRequest(id, method, params)
                writer.print(msg)
                writer.flush()
            } catch (e: Exception) {
                // Writer failed (e.g. socket closed) — clean up and propagate
                pending.remove(id)
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * Send a JSON-RPC 2.0 notification (fire-and-forget, no response expected).
     */
    private fun sendNotification(method: String, params: JSONObject = JSONObject()) {
        val msg = McpJsonRpc.buildNotification(method, params)
        writer.print(msg)
        writer.flush()
    }

    /**
     * Handle a single line of NDJSON from the transport.
     * Matches responses to pending requests by ID.
     */
    private fun handleLine(line: String) {
        try {
            val json = JSONObject(line)
            if (!json.has("jsonrpc")) return // ignore non-JSON-RPC lines

            // Check for response (has id) vs notification (no id)
            val id = json.optInt("id", -1)
            if (id != -1) {
                val entry = pending.remove(id) ?: return // unknown id, ignore
                if (json.has("error")) {
                    val error = json.getJSONObject("error")
                    entry.reject(McpException(
                        code = error.optInt("code", -1),
                        message = error.optString("message", "Unknown error")
                    ))
                } else {
                    entry.resolve(json.getJSONObject("result"))
                }
            }
        } catch (e: JSONException) {
            // Non-JSON line (e.g. server log output) — ignore silently
        }
    }

    companion object {
        private const val TAG = "McpConnection"
        private const val TIMEOUT_MS = 15_000L
    }
}
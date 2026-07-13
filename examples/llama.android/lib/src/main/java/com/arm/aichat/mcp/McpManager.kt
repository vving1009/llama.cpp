package com.arm.aichat.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Central coordinator for MCP server connections.
 *
 * Manages lifecycle, tool discovery, and tool call routing for all configured
 * MCP servers. Each server connection is independent — a failure on one does
 * not affect others.
 *
 * Tool naming convention: `mcp__serverName__toolName`
 * - `mcp__` prefix identifies MCP tools vs built-in tools
 * - `serverName` routes the call to the correct connection
 * - `toolName` identifies the specific tool on that server
 */
class McpManager(private val context: Context) {

    private val connections = mutableMapOf<String, McpConnection>()
    private val _tools = mutableListOf<McpToolInfo>()
    private var connected = false

    /**
     * Load configs, connect to all MCP servers, and discover their tools.
     *
     * Safe to call multiple times — subsequent calls are no-ops.
     * Failed servers are logged and skipped; other servers are unaffected.
     */
    suspend fun loadAndConnect() {
        if (connected) return
        connected = true

        val configs = McpConfigLoader.load(context)
        if (configs.isEmpty()) {
            Log.i(TAG, "No MCP servers configured")
            return
        }

        Log.i(TAG, "Connecting to ${configs.size} MCP server(s)...")

        for ((name, config) in configs) {
            try {
                val transport = McpTransport.create(config)
                val conn = McpConnection(name, transport)

                withTimeout(TIMEOUT_MS) {
                    conn.initialize()
                }

                val serverTools = withTimeout(TIMEOUT_MS) {
                    conn.listTools()
                }

                connections[name] = conn
                _tools.addAll(serverTools)
                Log.i(TAG, "MCP connected to '$name' — ${serverTools.size} tools")
            } catch (e: Exception) {
                Log.w(TAG, "MCP failed to connect to '$name': ${e.message}")
                // Clean up partial connection
                // (if the connection was created but not fully initialized)
            }
        }

        Log.i(TAG, "MCP initialized: ${connections.size} servers, ${_tools.size} total tools")
    }

    /**
     * Route a prefixed tool call to the correct MCP server.
     *
     * @param prefixedName Tool name in the format `mcp__serverName__toolName`.
     * @param params String-keyed parameters from ToolCallParser.
     * @return The text result from the MCP server.
     */
    suspend fun callTool(prefixedName: String, params: Map<String, String>): String {
        val parts = prefixedName.split("__")
        if (parts.size < 3) {
            throw IllegalArgumentException("Invalid MCP tool name: $prefixedName")
        }
        val serverName = parts[1]
        val toolName = parts.drop(2).joinToString("__") // tool name might contain "__"

        val conn = connections[serverName]
            ?: throw IllegalStateException("MCP server '$serverName' not connected")

        // Find the inputSchema for type conversion
        val toolInfo = _tools.find { it.serverName == serverName && it.name == toolName }
        val args = McpJsonRpc.convertParamsToJson(params, toolInfo?.inputSchema)

        return conn.callTool(toolName, args)
    }

    /**
     * Check if a tool name belongs to an MCP server.
     */
    fun isMcpTool(name: String): Boolean = name.startsWith("mcp__")

    /**
     * Returns tool definitions with the `mcp__serverName__toolName` prefix.
     */
    fun getToolDefinitions(): List<McpToolDefinition> {
        return _tools.map { t ->
            McpToolDefinition(
                name = "mcp__${t.serverName}__${t.name}",
                description = t.description,
                inputSchema = t.inputSchema
            )
        }
    }

    /**
     * Returns true if any MCP servers are connected with tools.
     */
    fun hasTools(): Boolean = _tools.isNotEmpty()

    /**
     * Build MCP tool descriptions in the same markdown format as
     * [com.arm.aichat.tool.ToolRegistry.buildToolDescriptions].
     */
    fun buildToolDescriptions(): String {
        if (_tools.isEmpty()) return ""

        val lines = mutableListOf<String>()
        lines.add("## MCP Tools (External Servers)")
        lines.add("")

        val byServer = _tools.groupBy { it.serverName }
        for ((serverName, tools) in byServer) {
            lines.add("### Server: $serverName")
            lines.add("")
            for (tool in tools) {
                val prefixedName = "mcp__${serverName}__${tool.name}"
                lines.add("#### $prefixedName")
                if (tool.description.isNotEmpty()) {
                    lines.add(tool.description)
                    lines.add("")
                }
                lines.add("Parameters:")
                val props = tool.inputSchema?.optJSONObject("properties")
                if (props != null && props.length() > 0) {
                    val required = tool.inputSchema?.optJSONArray("required")
                    val requiredKeys = if (required != null) {
                        (0 until required.length()).map { required.optString(it, "") }.toSet()
                    } else emptySet()
                    for (key in props.keys()) {
                        val prop = props.getJSONObject(key)
                        val type = prop.optString("type", "string")
                        val desc = prop.optString("description", "")
                        val isRequired = key in requiredKeys
                        val reqTag = if (isRequired) "(required)" else "(optional)"
                        lines.add("- $key: $type - $desc $reqTag")
                    }
                } else {
                    lines.add("- (none)")
                }
                lines.add("")
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * Disconnect all MCP servers and clear discovered tools.
     */
    suspend fun disconnectAll() {
        Log.i(TAG, "Disconnecting all MCP servers...")
        for ((name, conn) in connections) {
            try {
                conn.close()
                Log.d(TAG, "Disconnected MCP server '$name'")
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting MCP server '$name': ${e.message}")
            }
        }
        connections.clear()
        _tools.clear()
        connected = false
        Log.i(TAG, "All MCP servers disconnected")
    }

    companion object {
        private const val TAG = "McpManager"
        private const val TIMEOUT_MS = 15_000L
    }
}
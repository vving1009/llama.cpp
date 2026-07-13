package com.arm.aichat.mcp

import org.json.JSONObject

/**
 * Configuration for a single MCP server connection.
 *
 * Two transport modes are supported:
 * - **LocalSocket** (Android Unix domain socket, same-device IPC): set [address].
 * - **TCP socket** (remote network): set [host] + [port].
 *
 * Example `.mcp.json`:
 * ```
 * { "mcpServers": {
 *     "my_local":   { "address": "com.example.mcp.my_server" },
 *     "my_remote":  { "host": "192.168.1.100", "port": 5000 }
 *   }
 * }
 * ```
 */
data class McpServerConfig(
    /** LocalSocket address (for same-device cross-process IPC). */
    val address: String? = null,
    /** TCP hostname/IP (for remote MCP servers). */
    val host: String? = null,
    /** TCP port (required when [host] is set). */
    val port: Int? = null,
)

/**
 * Metadata for a single tool discovered from an MCP server via `tools/list`.
 */
data class McpToolInfo(
    val name: String,
    val description: String,
    /** JSON Schema object describing the tool's input parameters. */
    val inputSchema: JSONObject?,
    /** Which server this tool belongs to. */
    val serverName: String,
)

/**
 * A tool definition ready for the system prompt, with the `mcp__serverName__toolName` prefix.
 */
data class McpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JSONObject?,
)

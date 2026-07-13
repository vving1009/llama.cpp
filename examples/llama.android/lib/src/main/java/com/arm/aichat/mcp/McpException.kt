package com.arm.aichat.mcp

/**
 * Exception representing a JSON-RPC 2.0 error returned by an MCP server.
 */
class McpException(val code: Int, message: String) : Exception(message)

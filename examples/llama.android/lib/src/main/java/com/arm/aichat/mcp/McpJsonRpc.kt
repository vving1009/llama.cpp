package com.arm.aichat.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helper functions for constructing and parsing JSON-RPC 2.0 messages.
 *
 * The wire format is newline-delimited JSON (NDJSON), same as the original
 * TypeScript implementation — one JSON object per line terminated by '\n'.
 */
object McpJsonRpc {

    private const val JSONRPC_VERSION = "2.0"

    /**
     * Build a JSON-RPC 2.0 request string: `{ jsonrpc, id, method, params }`.
     * Suitable for writing directly to the transport output stream.
     */
    fun buildRequest(id: Int, method: String, params: JSONObject = JSONObject()): String {
        return JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString() + "\n"
    }

    /**
     * Build a JSON-RPC 2.0 notification (no `id`, no response expected).
     */
    fun buildNotification(method: String, params: JSONObject = JSONObject()): String {
        return JSONObject()
            .put("jsonrpc", JSONRPC_VERSION)
            .put("method", method)
            .put("params", params)
            .toString() + "\n"
    }

    /**
     * Extract text content from an MCP `tools/call` result.
     *
     * MCP returns: `{ content: [{ type: "text", text: "..." }, ...] }`
     * Returns the concatenation of all text-type content parts.
     */
    fun extractTextContent(result: JSONObject): String? {
        val content = result.optJSONArray("content") ?: return null
        val texts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            if (item.optString("type", "") == "text") {
                texts.add(item.optString("text", ""))
            }
        }
        return if (texts.isEmpty()) null else texts.joinToString("\n")
    }

    /**
     * Convert a `Map<String, String>` (from ToolCallParser) to a typed JSONObject
     * according to the given JSON Schema.
     *
     * This is the bridge between the Android agent loop (which produces
     * `Map<String, String>` for all tool params) and the MCP protocol (which
     * requires properly typed JSON arguments).
     *
     * @param params The string-keyed parameters from ToolCallParser.
     * @param schema The inputSchema from the tool definition, or null.
     * @return A JSONObject with values coerced to the schema types.
     */
    fun convertParamsToJson(params: Map<String, String>, schema: JSONObject?): JSONObject {
        val args = JSONObject()

        if (schema == null || !schema.has("properties")) {
            // No schema: pass everything as strings
            params.forEach { (k, v) -> args.put(k, v) }
            return args
        }

        val properties = schema.getJSONObject("properties")

        for ((key, value) in params) {
            val propSchema = properties.optJSONObject(key)
            if (propSchema != null) {
                val type = propSchema.optString("type", "string")
                when (type) {
                    "number" -> args.put(key, value.toDoubleOrNull() ?: value)
                    "integer" -> args.put(key, value.toIntOrNull() ?: value)
                    "boolean" -> args.put(key, value.toBooleanStrictOrNull() ?: value)
                    "array" -> {
                        try {
                            args.put(key, JSONArray(value))
                        } catch (_: Exception) {
                            args.put(key, value.split(",").map { it.trim() })
                        }
                    }
                    "object" -> {
                        try {
                            args.put(key, JSONObject(value))
                        } catch (_: Exception) {
                            args.put(key, value)
                        }
                    }
                    else -> args.put(key, value) // "string" and default
                }
            } else {
                args.put(key, value)
            }
        }
        return args
    }
}

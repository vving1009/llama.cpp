package com.arm.aichat.mcp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Loads MCP server configurations from `.mcp.json` files.
 *
 * Priority order (highest wins on conflict):
 * 1. `context.filesDir/.mcp.json` — user override, writable at runtime
 * 2. `assets/.mcp.json` — bundled with the APK, read-only
 *
 * This mirrors the two-tier priority from [SkillRegistry], and the original
 * TypeScript implementation's config loading from `~/.claude/settings.json`
 * and project `.mcp.json`.
 */
object McpConfigLoader {

    private const val TAG = "McpConfigLoader"
    private const val MCP_CONFIG_FILE = ".mcp.json"

    /**
     * Load MCP server configurations from all available sources.
     *
     * @return Map of server name → [McpServerConfig]. Empty if no config found.
     */
    fun load(context: Context): Map<String, McpServerConfig> {
        val merged = mutableMapOf<String, McpServerConfig>()

        // 1. Bundled config (assets/.mcp.json) — lowest priority
        try {
            context.assets.open(MCP_CONFIG_FILE).use { input ->
                val raw = input.bufferedReader().use { it.readText() }
                mergeConfig(raw, merged)
            }
        } catch (e: IOException) {
            Log.d(TAG, "No bundled .mcp.json in assets")
        }

        // 2. User override (filesDir/.mcp.json) — highest priority
        val userConfig = File(context.filesDir, MCP_CONFIG_FILE)
        if (userConfig.exists()) {
            try {
                val raw = userConfig.readText()
                mergeConfig(raw, merged)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read user .mcp.json", e)
            }
        }

        return merged
    }

    /**
     * Parse a raw JSON string and merge its server entries into [target].
     * Supports both:
     * - `{ "mcpServers": { ... } }` (standard format)
     * - `{ "serverName": { ... } }` (direct format, same as original)
     */
    private fun mergeConfig(raw: String, target: MutableMap<String, McpServerConfig>) {
        val root = JSONObject(raw)
        // Try "mcpServers" wrapper first, fall back to direct object
        val servers = root.optJSONObject("mcpServers") ?: root

        for (key in servers.keys()) {
            val cfg = servers.getJSONObject(key)

            // Determine transport type
            val address = cfg.optString("address", null)
            val host = cfg.optString("host", null)
            val port = if (cfg.has("port")) cfg.optInt("port", -1) else null

            if (address != null) {
                // LocalSocket transport
                target[key] = McpServerConfig(address = address)
            } else if (host != null && port != null && port > 0) {
                // TCP transport
                target[key] = McpServerConfig(host = host, port = port)
            } else {
                Log.w(TAG, "Skipping MCP server '$key': need 'address' (local) or 'host'+'port' (TCP)")
            }
        }
    }
}
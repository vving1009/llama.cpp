package com.arm.aichat.skill

import android.util.Log

/**
 * Manual YAML frontmatter parser for SKILL.md files.
 *
 * Parses the format:
 * ```
 * ---
 * key: value
 * key2: value2
 * ---
 * body text...
 * ```
 *
 * No external YAML dependency — this handles the subset that SKILL.md files
 * actually need: flat key-value pairs with optional multi-line body.
 */
object FrontmatterParser {

    private const val TAG = "FrontmatterParser"

    /**
     * Parse frontmatter from a raw SKILL.md string.
     *
     * @param content the full file content
     * @return FrontmatterResult with parsed meta and body
     */
    fun parse(content: String): FrontmatterResult {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return FrontmatterResult(emptyMap(), content.trim())
        }

        // Find the closing ---
        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                endIdx = i
                break
            }
        }
        if (endIdx == -1) {
            Log.w(TAG, "Frontmatter delimiter --- found but no closing ---, treating whole file as body")
            return FrontmatterResult(emptyMap(), content.trim())
        }

        // Parse key-value pairs between the two --- delimiters
        val meta = mutableMapOf<String, Any>()
        var i = 1
        while (i < endIdx) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }

            // Check for pipe-style multi-line value (|)
            if (line.contains(": |")) {
                val colonIdx = line.indexOf(':')
                if (colonIdx == -1) { i++; continue }
                val key = line.substring(0, colonIdx).trim()
                // Collect subsequent indented lines
                val valueLines = mutableListOf<String>()
                i++
                while (i < endIdx && lines[i].startsWith(" ")) {
                    valueLines.add(lines[i].trim())
                    i++
                }
                meta[key] = valueLines.joinToString("\n")
                continue
            }

            // Regular key: value line
            val colonIdx = line.indexOf(':')
            if (colonIdx == -1) { i++; continue }

            val key = line.substring(0, colonIdx).trim()
            if (key.isEmpty()) { i++; continue }

            val value = line.substring(colonIdx + 1).trim()

            // Parse typed values
            meta[key] = parseValue(value)
            i++
        }

        // Everything after the closing --- is the body
        val body = lines.drop(endIdx + 1).joinToString("\n").trim()

        return FrontmatterResult(meta, body)
    }

    /**
     * Parse a frontmatter value string into a typed Kotlin value.
     */
    private fun parseValue(value: String): Any {
        return when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            value.startsWith("[") -> {
                // Try JSON array, else comma-separated
                try {
                    val tokens = value.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().removeSurrounding("\"") }
                        .filter { it.isNotEmpty() }
                    if (tokens.isEmpty()) value else tokens
                } catch (_: Exception) {
                    value
                }
            }
            value.startsWith("\"") && value.endsWith("\"") ->
                value.removeSurrounding("\"")
            else -> value
        }
    }

    /**
     * Helper to extract a string value from the parsed meta map.
     */
    fun getString(meta: Map<String, Any>, vararg keys: String): String? {
        for (key in keys) {
            val v = meta[key]
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    /**
     * Helper to extract a boolean value from the parsed meta map.
     */
    fun getBool(meta: Map<String, Any>, vararg keys: String, default: Boolean = true): Boolean {
        for (key in keys) {
            val v = meta[key]
            if (v is Boolean) return v
        }
        return default
    }

    /**
     * Helper to extract a list value from the parsed meta map.
     */
    @Suppress("UNCHECKED_CAST")
    fun getList(meta: Map<String, Any>, vararg keys: String): List<String>? {
        for (key in keys) {
            val v = meta[key]
            if (v is List<*>) return v as List<String>
        }
        return null
    }
}
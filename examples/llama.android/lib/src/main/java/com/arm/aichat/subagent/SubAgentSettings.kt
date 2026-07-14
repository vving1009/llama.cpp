package com.arm.aichat.subagent

/**
 * Configuration for the remote OpenAI-compatible API endpoint.
 *
 * Hardcoded for now; will be loaded from settings in the future.
 */
data class SubAgentSettings(
    /** Base URL of the OpenAI-compatible API (e.g. "https://api.openai.com/v1"). */
    val apiBase: String = "https://api.openai.com/v1",
    /** API key for authentication. */
    val apiKey: String = "",
    /** Model identifier (e.g. "gpt-4o-mini", "claude-sonnet-4-6"). */
    val model: String = "gpt-4o-mini"
)
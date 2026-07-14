package com.arm.aichat.subagent

/**
 * Result from a sub-agent execution.
 */
data class SubAgentResult(
    /** The final text output of the sub-agent. */
    val text: String,
    /** Total prompt tokens consumed across all turns. */
    val inputTokens: Int = 0,
    /** Total completion tokens generated across all turns. */
    val outputTokens: Int = 0
)
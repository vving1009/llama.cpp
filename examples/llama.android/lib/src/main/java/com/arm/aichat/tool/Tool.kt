package com.arm.aichat.tool

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 工具定义（类似 Python 版 tool_definitions 中的单个工具）
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    /**
     * Whether the AgentLoop should feed this tool's result back to the LLM
     * and ask for a natural-language summary (default true).
     *
     * Set to false for "fire-and-forget" tools whose action itself is the
     * user-facing confirmation (e.g. CallPhoneTool, which surfaces the
     * system in-call UI and a "Calling ..." tool-result bubble in chat).
     * Summarising these via a second LLM turn produces a redundant
     * "I am now trying to call X" line that adds latency and noise.
     */
    val requireFollowUp: Boolean = true,
)

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

/**
 * 工具执行接口
 */
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(params: Map<String, String>): String

    /**
     * Execute the tool with streaming output.
     *
     * Default implementation wraps [execute] in a single-emission flow.
     * Tools that support progressive streaming (e.g. SubAgentTool) override
     * this to emit tokens as they arrive.
     */
    suspend fun executeStreaming(params: Map<String, String>): Flow<String> = flow {
        emit(execute(params))
    }

    /**
     * Whether the AgentLoop should send this tool's result back to the LLM
     * for a natural-language follow-up turn.
     *
     * The default is the static [ToolDefinition.requireFollowUp] flag. Tools
     * whose follow-up need depends on the runtime result — e.g. CallPhoneTool
     * suppresses the redundant summary on a successful call but keeps it on
     * error so the model can explain the failure — override this method.
     */
    fun requiresFollowUp(result: String): Boolean = definition.requireFollowUp
}

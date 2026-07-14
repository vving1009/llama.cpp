package com.arm.aichat.tool

import android.util.Log
import com.arm.aichat.subagent.SubAgent
import com.arm.aichat.subagent.SubAgentSettings
import com.arm.aichat.subagent.SubAgentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Tool that launches a sub-agent to handle a task autonomously.
 *
 * The sub-agent runs independently with its own context, making API calls to a
 * remote OpenAI-compatible model. It has access to a subset of the available
 * tools (determined by the agent type). The returned text is fed back to the
 * local LLM for summarization.
 *
 * Tool parameters:
 * - `type`: Agent type — "explore", "plan", "general" (default: "general")
 * - `description`: Short (3-5 word) description of the task
 * - `prompt`: Detailed task instructions for the sub-agent
 */
class AgentTool(
    private val settings: SubAgentSettings,
    private val toolRegistry: ToolRegistry
) : Tool {

    override val definition = ToolDefinition(
        name = "agent",
        description = "Launch a sub-agent to handle a task autonomously. " +
            "Sub-agents have isolated context and return their result. " +
            "Types: 'explore' (read-only, fast search), 'plan' (read-only, " +
            "structured planning), 'general' (full tools). " +
            "Default type: general.",
        parameters = listOf(
            ToolParameter(
                name = "description",
                type = "string",
                description = "Short (3-5 word) description of the sub-agent's task"
            ),
            ToolParameter(
                name = "prompt",
                type = "string",
                description = "Detailed task instructions for the sub-agent"
            ),
            ToolParameter(
                name = "type",
                type = "string",
                description = "Agent type: explore (read-only), plan (planning), " +
                    "general (full tools). Default: general",
                required = false
            )
        ),
        requireFollowUp = true
    )

    override suspend fun execute(params: Map<String, String>): String {
        val type = params["type"] ?: "general"
        val prompt = params["prompt"] ?: return "Error: Missing 'prompt' parameter"

        val subAgent = createSubAgent(type) ?: return "Error: Unknown agent type '$type'"
        val result = subAgent.runOnce(prompt)
        return result.text.ifBlank { "(Sub-agent produced no output)" }
    }

    override suspend fun executeStreaming(params: Map<String, String>): Flow<String> = flow {
        val type = params["type"] ?: "general"
        val prompt = params["prompt"] ?: return@flow

        val subAgent = createSubAgent(type)
        if (subAgent == null) {
            emit("Error: Unknown agent type '$type'")
            return@flow
        }

        subAgent.runStreaming(prompt).collect { token ->
            emit(token)
        }
    }

    /**
     * Create a [SubAgent] for the given type name.
     * Resolves built-in types (explore, plan, general).
     */
    private fun createSubAgent(type: String): SubAgent? {
        val agentType = SubAgentType.getByName(type) ?: return null
        return SubAgent(agentType, settings, toolRegistry)
    }

    companion object {
        private const val TAG = "AgentTool"
    }
}
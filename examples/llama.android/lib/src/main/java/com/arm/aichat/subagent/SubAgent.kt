package com.arm.aichat.subagent

import android.util.Log
import com.arm.aichat.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/**
 * A sub-agent that runs autonomously with its own message history, making API
 * calls to a remote OpenAI-compatible model and executing tools via the shared
 * [ToolRegistry].
 *
 * The sub-agent runs its own tool-calling loop: API call -> parse tool calls ->
 * execute tools -> add results to history -> repeat. Text tokens from each turn
 * are emitted through the returned [Flow].
 *
 * @param agentType The agent type definition (system prompt + allowed tools).
 * @param settings API connection settings.
 * @param toolRegistry Shared tool registry for executing tool calls.
 */
class SubAgent(
    private val agentType: SubAgentType,
    private val settings: SubAgentSettings,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "SubAgent"
        private const val MAX_TURNS = 10
    }

    private val messages = mutableListOf<ChatMessage>()
    private var totalInputTokens = 0
    private var totalOutputTokens = 0

    /**
     * Run the sub-agent with the given prompt, streaming text tokens.
     *
     * The returned flow emits tokens from all API turns. Internal tool calls
     * are handled transparently and do not appear in the flow (they execute
     * silently and the results are fed back to the model for the next turn).
     *
     * @param prompt The user prompt to execute.
     * @return Flow of text tokens from the sub-agent's output.
     */
    fun runStreaming(prompt: String): Flow<String> = flow {
        messages.clear()
        messages.add(ChatMessage(role = "system", content = agentType.systemPrompt))
        messages.add(ChatMessage(role = "user", content = prompt))

        val client = OpenAiClient(settings)
        var turns = 0

        try {
            while (turns < MAX_TURNS) {
                turns++
                Log.d(TAG, "Turn $turns (type=${agentType.name})")

                val toolDefs = buildToolDefs()

                val result = client.chatCompletion(
                    messages = messages.toList(),
                    toolDefs = toolDefs,
                    onTokens = { token -> emit(token) }
                )

                if (result.usage != null) {
                    totalInputTokens += result.usage.promptTokens
                    totalOutputTokens += result.usage.completionTokens
                }

                messages.add(
                    ChatMessage(
                        role = "assistant",
                        content = result.content,
                        toolCalls = result.toolCalls
                    )
                )

                if (result.toolCalls.isNullOrEmpty()) {
                    Log.d(TAG, "Sub-agent completed after $turns turns")
                    break
                }

                Log.d(TAG, "Executing ${result.toolCalls.size} tool call(s)")

                for (tc in result.toolCalls) {
                    val params = parseArguments(tc.arguments)
                    val toolResult = toolRegistry.execute(tc.name, params)
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = toolResult,
                            toolCallId = tc.id
                        )
                    )
                }
            }
        } finally {
            client.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Run the sub-agent and return the complete result as a [SubAgentResult].
     * Convenience wrapper for non-streaming use.
     */
    suspend fun runOnce(prompt: String): SubAgentResult {
        val buffer = StringBuilder()
        runStreaming(prompt).collect { token ->
            buffer.append(token)
        }
        return SubAgentResult(
            text = buffer.toString(),
            inputTokens = totalInputTokens,
            outputTokens = totalOutputTokens
        )
    }

    /**
     * Build the list of [ChatTool] definitions for the allowed tool set.
     * Filters the [ToolRegistry]'s tools to only those in the agentType's allowedToolNames.
     */
    private fun buildToolDefs(): List<ChatTool>? {
        val tools = toolRegistry.getAll().filter { it.name in agentType.allowedToolNames }
        if (tools.isEmpty()) return null
        return OpenAiClient.buildToolDefs(tools)
    }

    /**
     * Parse a JSON arguments string into a Map<String, String>.
     * The existing Android tools expect String values.
     */
    private fun parseArguments(arguments: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        try {
            val json = JSONObject(arguments)
            for (key in json.keys()) {
                val value = json.get(key)
                params[key] = value.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool arguments: $arguments", e)
        }
        return params
    }
}
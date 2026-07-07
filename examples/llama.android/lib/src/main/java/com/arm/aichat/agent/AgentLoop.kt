package com.arm.aichat.agent

import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.tool.*
import kotlinx.coroutines.flow.*

/**
 * Agent 核心循环
 *
 * 连接 LLM 生成 + 工具执行 + 结果反馈
 * 利用 InferenceEngine 的 stateful 特性（native 层维护对话历史）
 */
class AgentLoop(
    private val inferenceEngine: InferenceEngine,
    private val toolRegistry: ToolRegistry
) {

    companion object {
        private const val TAG = "AgentLoop"
        private const val MAX_TURNS = 10
    }

    private var isInitialized = false
    private var thinkingEnabled = true

    /**
     * 初始化 Agent：设置系统提示词（包含工具描述）
     *
     * @param thinkingEnabled 是否允许模型输出 thinking/reasoning 内容（Qwen3 等模型）
     */
    suspend fun initialize(thinkingEnabled: Boolean = true) {
        if (isInitialized) return

        this.thinkingEnabled = thinkingEnabled
        // Push the flag to native BEFORE the system prompt is formatted, so the
        // jinja template renders without the thinking block (--reasoning off).
        inferenceEngine.setThinkingEnabled(thinkingEnabled)
        val systemPrompt = buildSystemPrompt()
        inferenceEngine.setSystemPrompt(systemPrompt)
        isInitialized = true
        Log.i(TAG, "AgentLoop initialized (thinkingEnabled=$thinkingEnabled)")
    }

    /**
     * 发送用户消息
     * 返回流式事件，UI 可实时响应
     */
    fun sendUserMessage(message: String): Flow<AgentEvent> = flow {
        check(isInitialized) { "AgentLoop must be initialized before chat" }

        emit(AgentEvent.UserMessage(message))
        Log.d(TAG, "User: $message")

        var turns = 0
        var currentPrompt = message

        while (turns < MAX_TURNS) {
            turns++
            Log.d(TAG, "Turn $turns, prompt: ${currentPrompt.take(50)}...")

            // 收集模型响应，同时流式 emit 每个 token 给 UI
            val responseBuilder = StringBuilder()
            inferenceEngine.sendUserPrompt(currentPrompt)
                .collect { token ->
                    responseBuilder.append(token)
                    emit(AgentEvent.Generating(token))
                }

            val responseText = responseBuilder.toString().trim()
            Log.d(TAG, "Assistant raw: ${responseText.take(100)}...")

            // 检查是否包含工具调用
            if (ToolCallParser.hasToolCall(responseText)) {
                // 将 <tool_call> 之前的文本（前言说明）单独作为 AssistantMessage 发出
                val preamble = responseText.substringBefore("<tool_call>").trim()
                if (preamble.isNotEmpty()) {
                    emit(AgentEvent.AssistantMessage(preamble))
                }

                val toolCall = ToolCallParser.parse(responseText)

                if (toolCall != null) {
                    emit(AgentEvent.ToolCallDetected(toolCall.name, toolCall.params))
                    Log.i(TAG, "Tool call: ${toolCall.name}(${toolCall.params})")

                    // 执行工具
                    val result = toolRegistry.execute(toolCall.name, toolCall.params)
                    emit(AgentEvent.ToolResult(toolCall.name, result))
                    Log.i(TAG, "Tool result (${toolCall.name}): ${result.take(50)}...")

                    // 下一轮发送 tool result，让模型总结结果
                    currentPrompt = buildToolResultPrompt(toolCall.name, result)
                    // Continue loop
                } else {
                    // 解析失败，当做普通文本
                    emit(AgentEvent.AssistantMessage(responseText))
                    break
                }
            } else {
                // 纯文本回复，对话结束
                emit(AgentEvent.AssistantMessage(responseText))
                break
            }
        }

        emit(AgentEvent.Completed)
        Log.i(TAG, "Chat completed after $turns turns")

    }.catch { e ->
        Log.e(TAG, "Agent error", e)
        emit(AgentEvent.Error(e.message ?: "Unknown error"))
    }

    /**
     * 清空对话历史，可选择切换 thinking 模式
     *
     * @param thinkingEnabled 新的 thinking 设置，如果为 null 则保持当前值
     */
    suspend fun reset(thinkingEnabled: Boolean? = null) {
        isInitialized = false
        thinkingEnabled?.let { this.thinkingEnabled = it }
        initialize(thinkingEnabled = this.thinkingEnabled)
        Log.i(TAG, "AgentLoop reset (thinkingEnabled=$thinkingEnabled)")
    }

    /**
     * 构建系统提示词
     */
    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are an AI assistant running on Android. You can use tools to help the user.")
            appendLine()
            appendLine(toolRegistry.buildToolDescriptions())
            appendLine()
            appendLine("## 规则 Rules (IMPORTANT — follow strictly)")
            appendLine("1. Analyze the user's request. If the user speaks Chinese, respond in Chinese.")
            appendLine()
            appendLine("2. When you need to use a tool, you MUST output a brief Chinese explanation first,")
            appendLine("   then the tool call on a new line:")
            appendLine("   我将为你读取这个文件。")
            appendLine("   <tool_call>{\"name\":\"read_file\",\"params\":{\"file_path\":\"test.txt\"}}</tool_call>")
            appendLine("   让我列出目录内容。")
            appendLine("   <tool_call>{\"name\":\"list_files\",\"params\":{\"base_path\":\"/tmp\"}}</tool_call>")
            appendLine()
            appendLine("3. Tool results will be sent back to you automatically. The format will be:")
            appendLine("   The tool 'X' returned this result:")
            appendLine("   <content>")
            appendLine()
            appendLine("4. Keep JSON inside <tool_call> on a single line.")
            appendLine("5. If no tool is needed, respond with normal text.")
            appendLine()
            appendLine("## Important: After Receiving a Tool Result")
            appendLine("Messages that start with \"The tool 'X' returned this result:\" are TOOL RETURN VALUES,")
            appendLine("not user input. Treat them as the output of your previous tool call.")
            appendLine("After receiving a tool result, you MUST:")
            appendLine("- Summarize the result for the user in their language (Chinese).")
            appendLine("- If the result has data, present it clearly.")
            appendLine("- If the result is empty or an error, explain that.")
            appendLine("Never output just the raw tool result. Always provide a natural-language summary.")
        }
    }

    /**
     * 构建发送给模型的 Tool Result 提示（多轮对话）
     */
    private fun buildToolResultPrompt(toolName: String, result: String): String {
        return buildString {
            appendLine("The tool '$toolName' returned this result:")
            appendLine(result)
            appendLine()
            appendLine("---")
            appendLine("Based on this result, please answer the user's original question concisely.")
        }
    }
}

// 事件类型（用于 UI 更新）
sealed class AgentEvent {
    data class UserMessage(val message: String) : AgentEvent()
    /** 流式生成的单个 token，UI 可实时拼接显示 */
    data class Generating(val token: String) : AgentEvent()
    data class AssistantMessage(val message: String) : AgentEvent()
    data class ToolCallDetected(val toolName: String, val params: Map<String, String>) : AgentEvent()
    data class ToolResult(val toolName: String, val result: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    object Completed : AgentEvent()
}

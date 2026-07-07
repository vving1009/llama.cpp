package com.arm.aichat.tool

/**
 * 工具定义（类似 Python 版 tool_definitions 中的单个工具）
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
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
}

package com.arm.aichat.tool

import android.content.Context

/**
 * 工具注册中心
 * 类似 Python 版的 tool_definitions + execute_tool
 */
class ToolRegistry(context: Context) {

    private val tools = mutableMapOf<String, Tool>()

    init {
        // 注册内置工具
        register(ReadFileTool(context))
        register(WriteFileTool(context))
        register(ListFilesTool(context))
        register(DeleteFileTool(context))
        register(RunShellTool(context))
        register(CallPhoneTool(context))
    }

    fun register(tool: Tool) {
        tools[tool.definition.name] = tool
    }

    fun get(name: String): Tool? = tools[name]

    fun getAll(): List<ToolDefinition> = tools.values.map { it.definition }

    fun getAllTools(): Map<String, Tool> = tools.toMap()

    /**
     * 执行工具
     */
    suspend fun execute(name: String, params: Map<String, String>): String {
        val tool = tools[name]
            ?: return "Error: Unknown tool '$name'. Available tools: ${tools.keys.joinToString(", ")}"
        return tool.execute(params)
    }

    /**
     * 构建工具描述文本（用于 System Prompt）
     */
    fun buildToolDescriptions(): String {
        val lines = mutableListOf<String>()
        lines.add("## Available Tools")
        lines.add("")

        tools.values.forEach { tool ->
            val def = tool.definition
            lines.add("### ${def.name}")
            lines.add(def.description)
            lines.add("")
            lines.add("Parameters:")
            def.parameters.forEach { param ->
                val required = if (param.required) "(required)" else "(optional)"
                lines.add("- ${param.name}: ${param.type} - ${param.description} $required")
            }
            lines.add("")
        }

        return lines.joinToString("\n")
    }
}

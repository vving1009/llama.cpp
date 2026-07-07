package com.arm.aichat.tool

import org.json.JSONObject

/**
 * 解析模型输出的 <tool_call> 标记
 * 处理换行修复、容错解析
 */
object ToolCallParser {

    /**
     * 从模型输出中提取工具调用
     * 支持格式：<tool_call>{"name":"...","params":{...}}</tool_call>
     */
    fun parse(text: String): ToolCall? {
        // 1. 提取 <tool_call>...</tool_call> 之间的内容
        val xmlPattern = "<tool_call>(.*?)</tool_call>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = xmlPattern.find(text) ?: return null

        var jsonStr = match.groupValues[1].trim()

        // 2. 修复换行导致的 JSON 断裂（兜底保护）
        jsonStr = fixBrokenJson(jsonStr)

        // 3. 解析 JSON
        return parseJsonSafely(jsonStr)
    }

    /**
     * 检查文本是否包含工具调用标记
     */
    fun hasToolCall(text: String): Boolean {
        return text.contains("<tool_call>") && text.contains("</tool_call>")
    }

    /**
     * 修复因换行导致的 JSON 断裂
     * 例如：{\"a\":\"b\nc\"} → {\"a\":\"b c\"}
     */
    private fun fixBrokenJson(jsonStr: String): String {
        return jsonStr
            // 移除 JSON 字符串值内部的换行
            .replace(Regex("(?<=[:\\s])\\n(?=\\s*[\"'])"), " ")
            // 合并因换行断裂的 key-value
            .replace(Regex("\\n\\s*[\"']"), "\"")
    }

    /**
     * 安全解析 JSON，处理异常情况
     */
    private fun parseJsonSafely(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)

            val name = json.getString("name")
            val paramsObj = json.optJSONObject("params")
            val params = mutableMapOf<String, String>()

            if (paramsObj != null) {
                for (key in paramsObj.keys()) {
                    params[key] = paramsObj.getString(key)
                }
            }

            ToolCall(name = name, params = params)
        } catch (e: Exception) {
            // 解析失败，返回 null
            null
        }
    }
}

/**
 * 工具调用数据类
 */
data class ToolCall(
    val name: String,
    val params: Map<String, String>
)

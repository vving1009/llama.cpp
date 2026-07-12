package com.arm.aichat.tool

import android.content.Context
import java.io.File

/**
 * 列出目录文件工具
 */
class ListFilesTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "list_files",
        description = "List files matching a glob pattern. Returns matching file paths.",
        parameters = listOf(
            ToolParameter(
                "pattern",
                "string",
                "Glob pattern to match files (e.g., '*.txt', 'src/**/*')"
            ),
            ToolParameter(
                "path",
                "string",
                "Base directory to search from. Defaults to current directory.",
                required = false
            )
        )
    )

    override suspend fun execute(params: Map<String, String>): String {
        val pattern = params["pattern"] ?: return "Error: Missing 'pattern' parameter"
        val basePath = params["path"] ?: "."

        return try {
            val dir = if (basePath.startsWith("/")) {
                File(basePath)
            } else {
                File(context.externalCacheDir!!, basePath)
            }

            if (!dir.exists() || !dir.isDirectory) {
                return "Error: Directory does not exist: ${dir.absolutePath}"
            }

            // 安全检查
            if (!isPathAllowed(dir)) {
                return "Error: Access denied. Can only list files within the app's sandbox."
            }

            // 最大返回 200 个结果
            val maxResults = 200
            val files = mutableListOf<String>()
            var extraCount = 0

            dir.walkTopDown().forEach { file ->
                // 排除隐藏文件和目录
                if (file.name.startsWith(".")) return@forEach

                val relativePath = file.relativeTo(dir).path

                // 简单 glob 匹配（支持 * 和 **）
                if (matchesGlob(relativePath, pattern)) {
                    if (files.size < maxResults) {
                        val prefix = if (file.isDirectory) "[DIR]" else "[FILE]"
                        files.add("$prefix $relativePath")
                    } else {
                        extraCount++
                    }
                }
            }

            if (files.isEmpty()) {
                "No files found matching pattern: $pattern"
            } else {
                val result = files.joinToString("\n")
                if (extraCount > 0) {
                    result + "\n... and $extraCount more"
                } else {
                    result
                }
            }

        } catch (e: Exception) {
            "Error listing files: ${e.message}"
        }
    }

    /**
     * 简单的 glob 模式匹配
     */
    private fun matchesGlob(path: String, pattern: String): Boolean {
        // 简化实现：将 glob 转换为正则
        val regex = pattern
            .replace("**", "###DOUBLESTAR###")
            .replace("*", "[^/]*")
            .replace("###DOUBLESTAR###", ".*")
            .replace("?", ".")
            .toRegex()

        return regex.matches(path)
    }

    /**
     * 安全检查
     */
    private fun isPathAllowed(file: File): Boolean {
        val canonicalPath = file.canonicalPath
        val allowedRoots = listOf(
            context.externalCacheDir!!.canonicalPath,
            context.cacheDir.canonicalPath
        )
        return allowedRoots.any { canonicalPath.startsWith(it) }
    }
}

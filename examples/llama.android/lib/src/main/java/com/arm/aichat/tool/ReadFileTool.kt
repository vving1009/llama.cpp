package com.arm.aichat.tool

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 读取文件工具
 * 安全限制：只能访问应用沙箱内文件
 */
class ReadFileTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file. Returns the file content with line numbers. Only accesses files within the app's sandbox.",
        parameters = listOf(
            ToolParameter("file_path", "string", "The path to the file to read")
        )
    )

    override suspend fun execute(params: Map<String, String>): String {
        val path = params["file_path"] ?: return "Error: Missing 'file_path' parameter"

        return try {
            val file = resolvePath(path)

            // 安全检查：确保文件在允许范围内
            if (!isPathAllowed(file)) {
                return "Error: Access denied. Can only read files within the app's sandbox."
            }

            if (!file.exists()) {
                return "Error: File does not exist: ${file.absolutePath}"
            }

            if (!file.isFile) {
                return "Error: Path is not a file: ${file.absolutePath}"
            }

            // 添加行号输出（与 Python 版一致）
            val content = file.readText()
            val lines = content.lines()
            val maxLineNumWidth = lines.size.toString().length

            lines.mapIndexed { index, line ->
                val lineNum = (index + 1).toString().padStart(maxLineNumWidth, ' ')
                "$lineNum | $line"
            }.joinToString("\n")

        } catch (e: IOException) {
            "Error reading file: ${e.message}"
        }
    }

    /**
     * 解析路径：支持相对路径和绝对路径
     */
    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(context.filesDir, path)
        }
    }

    /**
     * 安全检查：只允许访问应用沙箱内文件
     */
    private fun isPathAllowed(file: File): Boolean {
        val canonicalPath = file.canonicalPath
        val allowedRoots = listOf(
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath
        )

        return allowedRoots.any { canonicalPath.startsWith(it) }
    }
}

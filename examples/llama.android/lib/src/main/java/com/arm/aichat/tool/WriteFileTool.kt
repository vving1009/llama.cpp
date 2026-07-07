package com.arm.aichat.tool

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 写文件工具
 */
class WriteFileTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write content to a file. Creates the file if it doesn't exist, overwrites if it does. Only writes within the app's sandbox.",
        parameters = listOf(
            ToolParameter("file_path", "string", "The path to the file to write"),
            ToolParameter("content", "string", "The content to write to the file")
        )
    )

    override suspend fun execute(params: Map<String, String>): String {
        val path = params["file_path"] ?: return "Error: Missing 'file_path' parameter"
        val content = params["content"] ?: return "Error: Missing 'content' parameter"

        return try {
            val file = resolvePath(path)

            // 安全检查：确保文件在允许范围内
            if (!isPathAllowed(file)) {
                return "Error: Access denied. Can only write files within the app's sandbox."
            }

            // 创建父目录
            file.parentFile?.mkdirs()

            file.writeText(content)

            val lineCount = content.lines().size
            "Successfully wrote to ${file.absolutePath} ($lineCount lines)"
        } catch (e: IOException) {
            "Error writing file: ${e.message}"
        }
    }

    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(context.filesDir, path)
        }
    }

    private fun isPathAllowed(file: File): Boolean {
        val canonicalPath = file.canonicalPath
        val allowedRoots = listOf(
            context.filesDir.canonicalPath,
            context.cacheDir.canonicalPath
        )
        return allowedRoots.any { canonicalPath.startsWith(it) }
    }
}

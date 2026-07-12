package com.arm.aichat.tool

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 删除文件工具
 * 只能删除应用沙箱内的文件或目录（外部文件目录和缓存目录）
 * 删除目录时会递归删除整个目录树
 */
class DeleteFileTool(private val context: Context) : Tool {

    override val definition = ToolDefinition(
        name = "delete_file",
        description = "Delete a file or directory. Permanently removes the file/folder from the app's sandbox. Directories are deleted recursively with all contents.",
        parameters = listOf(
            ToolParameter("file_path", "string", "The path to the file or directory to delete")
        )
    )

    override suspend fun execute(params: Map<String, String>): String {
        val path = params["file_path"] ?: return "Error: Missing 'file_path' parameter"

        return try {
            val file = resolvePath(path)

            // 安全检查：确保文件在允许范围内
            if (!isPathAllowed(file)) {
                return "Error: Access denied. Can only delete files within the app's sandbox."
            }

            if (!file.exists()) {
                return "Error: File does not exist: ${file.absolutePath}"
            }

            val type = if (file.isDirectory) "directory" else "file"

            val deleted = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
            if (deleted) {
                "Successfully deleted $type: ${file.absolutePath}"
            } else {
                "Error: Failed to delete $type: ${file.absolutePath}"
            }

        } catch (e: IOException) {
            "Error deleting file: ${e.message}"
        } catch (e: SecurityException) {
            "Error: Permission denied: ${e.message}"
        }
    }

    private fun resolvePath(path: String): File {
        return if (path.startsWith("/")) {
            File(path)
        } else {
            File(context.externalCacheDir!!, path)
        }
    }

    private fun isPathAllowed(file: File): Boolean {
        val canonicalPath = file.canonicalPath
        val allowedRoots = listOf(
            context.externalCacheDir!!.canonicalPath,
            context.cacheDir.canonicalPath
        )
        return allowedRoots.any { canonicalPath.startsWith(it) }
    }
}

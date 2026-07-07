package com.arm.aichat.tool

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 执行 shell 命令（受限版本，仅允许白名单命令）
 */
class RunShellTool(private val context: Context) : Tool {

    // 允许的安全命令白名单
    private val allowedCommands = setOf(
        "ls", "pwd", "cat", "echo", "grep", "find",
        "head", "tail", "wc", "sort", "uniq",
        "ps", "top", "df", "du", "whoami"
    )

    override val definition = ToolDefinition(
        name = "run_shell",
        description = "Execute a safe shell command. Only a limited set of commands are allowed.",
        parameters = listOf(
            ToolParameter("command", "string", "The shell command to execute (e.g., 'ls -la')"),
            ToolParameter("timeout", "integer", "Timeout in milliseconds (default: 30000)", required = false)
        )
    )

    override suspend fun execute(params: Map<String, String>): String {
        val command = params["command"] ?: return "Error: Missing 'command' parameter"
        val timeout = params["timeout"]?.toIntOrNull() ?: 30000

        // 安全检查：提取命令名
        val cmdName = command.trim().split("\\s+").first().removePrefix("/system/bin/")

        if (cmdName !in allowedCommands) {
            return "Error: Command '$cmdName' is not in the whitelist. Allowed: ${allowedCommands.sorted().joinToString(", ")}"
        }

        // 拒绝危险参数
        if (containsDangerousParams(command)) {
            return "Error: Command contains dangerous parameters"
        }

        return try {
            val process = Runtime.getRuntime().exec(command)

            // 使用线程读取输出，防止阻塞
            val output = StringBuilder()
            val error = StringBuilder()

            val outputThread = Thread {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { output.appendLine(it) }
                }
            }

            val errorThread = Thread {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { error.appendLine(it) }
                }
            }

            outputThread.start()
            errorThread.start()

            // 等待完成或超时
            val finished = process.waitFor(timeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                return "Error: Command timed out after ${timeout}ms"
            }

            outputThread.join(1000)
            errorThread.join(1000)

            if (process.exitValue() == 0) {
                output.toString().ifEmpty { "(no output)" }
            } else {
                "Error (exit ${process.exitValue()}): ${error.ifEmpty { output }}"
            }

        } catch (e: IOException) {
            "Error executing command: ${e.message}"
        }
    }

    /**
     * 检查命令是否包含危险参数
     */
    private fun containsDangerousParams(command: String): Boolean {
        val dangerousPatterns = listOf(
            ">",              // 重定向
            "<",              // 输入重定向
            "|",              // 管道
            ";",              // 命令分隔
            "&&",             // 条件执行
            "||",             // 逻辑或
            "\$",             // 变量
            "`"               // 命令替换
        )

        return dangerousPatterns.any { command.contains(it) }
    }
}

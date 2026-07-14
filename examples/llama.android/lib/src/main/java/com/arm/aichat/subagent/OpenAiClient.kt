package com.arm.aichat.subagent

import android.util.Log
import com.arm.aichat.tool.ToolDefinition
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

/**
 * Ktor-based HTTP client for OpenAI-compatible chat completion API.
 *
 * Supports SSE streaming with tool call accumulation. Uses Moshi for JSON
 * serialization/deserialization. No external HTTP dependencies beyond Ktor.
 */
class OpenAiClient(private val settings: SubAgentSettings) {

    /** Shared Moshi instance with Kotlin reflection adapter. */
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /** Shared Ktor HttpClient with OkHttp engine. */
    private val client = HttpClient(io.ktor.client.engine.okhttp.OkHttp)

    /**
     * Stream a chat completion request.
     *
     * @param messages The conversation messages in OpenAI format.
     * @param toolDefs Tool definitions to include (null if no tools).
     * @param onTokens Suspend callback invoked for each text token received.
     * @return [ChatCompletionResult] with the accumulated content, tool calls, and usage.
     */
    suspend fun chatCompletion(
        messages: List<ChatMessage>,
        toolDefs: List<ChatTool>?,
        onTokens: suspend (String) -> Unit
    ): ChatCompletionResult {
        val requestBody = buildRequest(messages, toolDefs)
        val requestJson = moshi.adapter(ChatCompletionRequest::class.java).toJson(requestBody)

        Log.d(TAG, "Sending request to ${settings.apiBase}/chat/completions")
        Log.d(TAG, "Model: ${settings.model}, Messages: ${messages.size}, Tools: ${toolDefs?.size ?: 0}")

        val response = client.post("${settings.apiBase.trimEnd('/')}/chat/completions") {
            headers {
                append(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
                append(HttpHeaders.Accept, "text/event-stream")
            }
            contentType(ContentType.Application.Json)
            setBody(requestJson)
        }

        val channel = response.bodyAsChannel()
        val content = StringBuilder()
        val toolCalls = mutableMapOf<Int, ToolCallAccumulator>()
        var finishReason = ""
        var usage: Usage? = null

        // Read SSE line by line
        while (channel.isClosedForRead.not() && coroutineContext.isActive) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data: ")) continue

            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break

            try {
                val chunk = moshi.adapter(ChatCompletionChunk::class.java).fromJson(data)
                    ?: continue

                // Parse usage from final chunk
                if (chunk.usage != null) {
                    usage = chunk.usage
                }

                val choices = chunk.choices ?: continue
                if (choices.isEmpty()) continue
                val choice = choices[0]
                val delta = choice.delta ?: continue

                // Text content
                if (delta.content != null) {
                    onTokens(delta.content)
                    content.append(delta.content)
                }

                // Tool call deltas (accumulate by index)
                delta.toolCalls?.forEach { tc ->
                    val acc = toolCalls.getOrPut(tc.index) {
                        ToolCallAccumulator("", "", "")
                    }
                    if (tc.id != null) acc.id = tc.id
                    if (tc.function?.name != null) acc.name = tc.function.name
                    if (tc.function?.arguments != null) acc.arguments += tc.function.arguments
                }

                if (choice.finishReason != null) {
                    finishReason = choice.finishReason
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SSE chunk", e)
            }
        }

        // Assemble tool calls sorted by index
        val assembledToolCalls = if (toolCalls.isNotEmpty()) {
            toolCalls.entries
                .sortedBy { it.key }
                .map { (_, acc) ->
                    ToolCallData(id = acc.id, name = acc.name, arguments = acc.arguments)
                }
        } else null

        Log.d(TAG, "Response: content=${content.length} chars, toolCalls=${assembledToolCalls?.size ?: 0}, finishReason=$finishReason")

        return ChatCompletionResult(
            content = content.toString(),
            toolCalls = assembledToolCalls,
            finishReason = finishReason,
            usage = usage
        )
    }

    /**
     * Build the JSON request body from messages and tool definitions.
     */
    private fun buildRequest(
        messages: List<ChatMessage>,
        toolDefs: List<ChatTool>?
    ): ChatCompletionRequest {
        return ChatCompletionRequest(
            model = settings.model,
            messages = OpenAiClient.buildMessages(messages),
            tools = toolDefs?.takeIf { it.isNotEmpty() },
            stream = true,
            streamOptions = StreamOptions(includeUsage = true)
        )
    }

    /**
     * Convert Android [ToolDefinition] objects to OpenAI [ChatTool] format.
     */
    companion object {
        private const val TAG = "OpenAiClient"
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 120_000L

        fun buildToolDefs(definitions: List<ToolDefinition>): List<ChatTool> {
            return definitions.map { def ->
                val properties = mutableMapOf<String, Any>()
                val required = mutableListOf<String>()

                for (param in def.parameters) {
                    val prop = mutableMapOf<String, Any>(
                        "type" to param.type,
                        "description" to param.description
                    )
                    properties[param.name] = prop
                    if (param.required) {
                        required.add(param.name)
                    }
                }

                val parameters = mutableMapOf<String, Any>(
                    "type" to "object",
                    "properties" to properties
                )
                if (required.isNotEmpty()) {
                    parameters["required"] = required
                }

                ChatTool(
                    type = "function",
                    function = ChatFunction(
                        name = def.name,
                        description = def.description,
                        parameters = parameters
                    )
                )
            }
        }

        /**
         * Build messages array from chat message list.
         */
        fun buildMessages(chatMessages: List<ChatMessage>): List<OpenAiMessage> {
            return chatMessages.map { msg ->
                OpenAiMessage(
                    role = msg.role,
                    content = msg.content,
                    toolCallId = msg.toolCallId,
                    toolCalls = msg.toolCalls?.map { tc ->
                        ToolCall(
                            id = tc.id,
                            type = "function",
                            function = FunctionCall(
                                name = tc.name,
                                arguments = tc.arguments
                            )
                        )
                    }
                )
            }
        }
    }

    /**
     * Clean up the HTTP client.
     */
    fun close() {
        client.close()
    }

    // ─── Internal accumulator for streaming tool call arguments ───

    private data class ToolCallAccumulator(
        var id: String,
        var name: String,
        var arguments: String
    )
}

// ═══════════════════════════════════════════════════════════════
// Data classes for OpenAI API request/response
// ═══════════════════════════════════════════════════════════════

/** Request body for POST /v1/chat/completions. */
@JsonClass(generateAdapter = false)
data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val tools: List<ChatTool>? = null,
    val stream: Boolean = true,
    @Json(name = "stream_options") val streamOptions: StreamOptions? = null
)

@JsonClass(generateAdapter = false)
data class StreamOptions(
    @Json(name = "include_usage") val includeUsage: Boolean = true
)

/** A message in the OpenAI chat format. */
@JsonClass(generateAdapter = false)
data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @Json(name = "tool_call_id") val toolCallId: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<ToolCall>? = null
)

/** A tool call in an assistant message. */
@JsonClass(generateAdapter = false)
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@JsonClass(generateAdapter = false)
data class FunctionCall(
    val name: String,
    val arguments: String
)

/** A tool definition for the OpenAI API. */
@JsonClass(generateAdapter = false)
data class ChatTool(
    val type: String = "function",
    val function: ChatFunction
)

@JsonClass(generateAdapter = false)
data class ChatFunction(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>?
)

/** Internal message type for SubAgent bookkeeping. */
data class ChatMessage(
    val role: String,          // "system", "user", "assistant", "tool"
    val content: String? = null,
    val toolCallId: String? = null,   // for "tool" role messages
    val toolCalls: List<ToolCallData>? = null // for "assistant" messages
)

/** Parsed tool call from the API response. */
data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String   // JSON string
)

/** Result of a chat completion request. */
data class ChatCompletionResult(
    val content: String,
    val toolCalls: List<ToolCallData>?,
    val finishReason: String,
    val usage: Usage?
)

/** Streaming chunk from the SSE response. */
@JsonClass(generateAdapter = false)
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<ChunkChoice>? = null,
    val usage: Usage? = null
)

@JsonClass(generateAdapter = false)
data class ChunkChoice(
    val delta: Delta? = null,
    val index: Int? = null,
    @Json(name = "finish_reason") val finishReason: String? = null
)

@JsonClass(generateAdapter = false)
data class Delta(
    val content: String? = null,
    @Json(name = "tool_calls") val toolCalls: List<DeltaToolCall>? = null
)

@JsonClass(generateAdapter = false)
data class DeltaToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: DeltaFunction? = null
)

@JsonClass(generateAdapter = false)
data class DeltaFunction(
    val name: String? = null,
    val arguments: String? = null
)

/** Token usage from the API response. */
@JsonClass(generateAdapter = false)
data class Usage(
    @Json(name = "prompt_tokens") val promptTokens: Int = 0,
    @Json(name = "completion_tokens") val completionTokens: Int = 0
)
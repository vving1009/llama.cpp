package com.example.llama

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.agent.AgentEvent
import com.arm.aichat.agent.AgentLoop
import com.arm.aichat.mcp.McpManager
import com.arm.aichat.skill.InvokeSkillTool
import com.arm.aichat.skill.SkillRegistry
import com.arm.aichat.subagent.SubAgentSettings
import com.arm.aichat.tool.AgentTool
import com.arm.aichat.tool.ToolRegistry
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private lateinit var toolRegistry: ToolRegistry
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var mcpManager: McpManager
    private lateinit var agentLoop: AgentLoop
    private var generationJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        // View model boilerplate and state management is out of this basic sample's scope
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)

        // Arm AI Chat initialization
        lifecycleScope.launch(Dispatchers.Default) {
            engine = AiChat.getInferenceEngine(applicationContext)
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                // If model is ready, validate input and send to engine
                handleUserInput()
            } else {
                // Otherwise, prompt user to select a GGUF metadata on the device
                getContent.launch(arrayOf("*/*"))
            }
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        // Update UI states
        userActionFab.isEnabled = false
        userInputEt.hint = "Parsing GGUF..."
        ggufTv.text = "Parsing metadata from selected file \n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            // Parse GGUF metadata
            Log.i(TAG, "Parsing GGUF metadata...")
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                // Update UI to show GGUF metadata to user
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    ggufTv.text = metadata.toString()
                }

                // Ensure the model file is available
                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModel(modelName, modelFile)

                    // Set up the Agent loop: system prompt is set here with tool descriptions
                    toolRegistry = ToolRegistry(applicationContext)
                    skillRegistry = SkillRegistry(applicationContext)
                    toolRegistry.register(InvokeSkillTool(skillRegistry))

                    // Register the AgentTool for sub-agent execution (remote API)
                    val subAgentSettings = SubAgentSettings(
                        apiBase = "https://api.openai.com/v1",
                        apiKey = "",
                        model = "gpt-4o-mini"
                    )
                    toolRegistry.register(AgentTool(subAgentSettings, toolRegistry))

                    // Initialize MCP manager (connect to configured servers)
                    mcpManager = McpManager(applicationContext)
                    mcpManager.loadAndConnect()
                    Log.i(TAG, "MCP: ${if (mcpManager.hasTools()) mcpManager.getToolDefinitions().size else 0} tools discovered")

                    agentLoop = AgentLoop(engine, toolRegistry, skillRegistry, mcpManager)
                    // thinkingEnabled=false suppresses empty <thinking> tags from Qwen3 models
                    agentLoop.initialize(thinkingEnabled = false)

                    withContext(Dispatchers.Main) {
                        isModelReady = true
                        userInputEt.hint = "Type and send a message!"
                        userInputEt.isEnabled = true
                        userActionFab.setImageResource(R.drawable.outline_send_24)
                        userActionFab.isEnabled = true
                    }
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                // Copy the file into local storage if not yet done
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "Copying file..."
                    }

                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                } else {
                    Log.i(TAG, "File already exists $modelName")
                }
            }
        }

    /**
     * Load the model file from the app private storage
     */
    private suspend fun loadModel(modelName: String, modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Loading model..."
            }
            engine.loadModel(modelFile.path)
        }

    /**
     * Validate and send the user message into [AgentLoop]
     */
    private fun handleUserInput() {
        var userMsg = userInputEt.text.toString()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "Input message is empty!", Toast.LENGTH_SHORT).show()
            return
        }

        // Handle slash commands (skill invocation)
        if (userMsg.startsWith("/")) {
            val spaceIdx = userMsg.indexOf(' ')
            val cmdName = if (spaceIdx > 0) userMsg.substring(1, spaceIdx) else userMsg.substring(1)
            val cmdArgs = if (spaceIdx > 0) userMsg.substring(spaceIdx + 1) else ""

            val skill = skillRegistry.getByName(cmdName)
            if (skill != null && skill.userInvocable) {
                val resolved = skillRegistry.resolvePrompt(skill, cmdArgs)
                Toast.makeText(this, "Invoking skill: ${skill.name}", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Skill invoked: /${skill.name} args='$cmdArgs'")
                userMsg = resolved
            }
            // If skill not found, fall through — let the LLM handle it
        }

        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false

        // Update UI: add user message and a placeholder for the assistant reply
        messages.add(Message(UUID.randomUUID().toString(), userMsg, MessageType.USER))
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", MessageType.ASSISTANT))
        messageAdapter.notifyItemRangeChanged(messages.size - 2, 2)

        generationJob = lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        agentLoop.sendUserMessage(userMsg).collect { event ->
                            withContext(Dispatchers.Main) {
                                when (event) {
                                    is AgentEvent.Generating -> {
                                        // Stream tokens into a trailing ASSISTANT bubble. After a
                                        // tool round, no such bubble exists yet, so lazily create one.
                                        val lastIdx = messages.size - 1
                                        val tailType = messages.lastOrNull()?.type?.toString() ?: "NONE"
                                        Log.d(TAG, "[trace] EVT Generating tailIdx=$lastIdx tailType=$tailType token='${event.token.take(20)}' msgCount=${messages.size}")
                                        if (lastIdx >= 0 && messages[lastIdx].type == MessageType.ASSISTANT) {
                                            lastAssistantMsg.append(event.token)
                                            messages.removeAt(lastIdx)
                                            messages.add(Message(UUID.randomUUID().toString(),
                                                lastAssistantMsg.toString(), MessageType.ASSISTANT))
                                            messageAdapter.notifyItemChanged(messages.size - 1)
                                        } else {
                                            // No trailing ASSISTANT (e.g. turn N>1 after a tool
                                            // result); create a fresh bubble. lastAssistantMsg
                                            // was cleared in the ToolCallDetected handler that
                                            // started this turn.
                                            lastAssistantMsg.append(event.token)
                                            messages.add(Message(UUID.randomUUID().toString(),
                                                lastAssistantMsg.toString(), MessageType.ASSISTANT))
                                            messageAdapter.notifyItemInserted(messages.size - 1)
                                        }
                                    }
                                    is AgentEvent.ToolCallDetected -> {
                                        // Reset the streaming buffer for the next turn. We
                                        // deliberately do NOT pre-insert an empty ASSISTANT
                                        // placeholder here: if the generation is cancelled
                                        // before the next Generating event (e.g. CallPhoneTool
                                        // surfaces the in-call screen and the activity hits
                                        // onStop), a pre-inserted empty bubble would survive as
                                        // an "air bubble". The Generating / AssistantMessage
                                        // handlers lazily create the next bubble.
                                        val tailType = messages.lastOrNull()?.type?.toString() ?: "NONE"
                                        Log.d(TAG, "[trace] EVT ToolCallDetected tool=${event.toolName} tailType=$tailType tailContent='${messages.lastOrNull()?.content?.take(40)}' msgCount=${messages.size}")
                                        lastAssistantMsg.clear()
                                        val callText = "${event.toolName}(${event.params})"
                                        messages.add(Message(UUID.randomUUID().toString(), callText, MessageType.TOOL_CALL))
                                        messageAdapter.notifyItemInserted(messages.size - 1)
                                        Log.d(TAG, "[trace] AFTER ToolCallDetected tail=${messages.lastOrNull()?.type} msgCount=${messages.size}")
                                    }
                                    is AgentEvent.ToolResult -> {
                                        val resultPreview = event.result.take(200)
                                        // Insert before the trailing ASSISTANT bubble if there
                                        // is one (turn 1), otherwise at the end (turn N>1 with
                                        // no streaming yet).
                                        val tailType = messages.lastOrNull()?.type?.toString() ?: "NONE"
                                        Log.d(TAG, "[trace] EVT ToolResult tool=${event.toolName} tailBeforeInsertType=$tailType tailContent='${messages.lastOrNull()?.content?.take(40)}' msgCount=${messages.size}")
                                        val insertIdx = if (messages.lastOrNull()?.type == MessageType.ASSISTANT)
                                            messages.size - 1
                                        else
                                            messages.size
                                        messages.add(insertIdx, Message(UUID.randomUUID().toString(),
                                            "${event.toolName}: $resultPreview", MessageType.TOOL_RESULT))
                                        messageAdapter.notifyItemInserted(insertIdx)
                                        Log.d(TAG, "[trace] AFTER ToolResult insertIdx=$insertIdx newTailType=${messages.lastOrNull()?.type} msgCount=${messages.size}")
                                    }
                                    is AgentEvent.SubAgentToken -> {
                                        // Stream sub-agent tokens into the last TOOL_RESULT bubble,
                                        // or create one if it doesn't exist yet.
                                        val lastIdx = messages.size - 1
                                        if (lastIdx >= 0 && messages[lastIdx].type == MessageType.TOOL_RESULT) {
                                            val updated = messages[lastIdx].content + event.token
                                            messages.removeAt(lastIdx)
                                            messages.add(Message(UUID.randomUUID().toString(), updated, MessageType.TOOL_RESULT))
                                            messageAdapter.notifyItemChanged(messages.size - 1)
                                        } else {
                                            messages.add(Message(UUID.randomUUID().toString(), event.token, MessageType.TOOL_RESULT))
                                            messageAdapter.notifyItemInserted(messages.size - 1)
                                        }
                                    }
                                    is AgentEvent.AssistantMessage -> {
                                        Log.d(TAG, "[trace] EVT AssistantMessage len=${event.message.length} content='${event.message.take(60)}' msgCount=${messages.size}")
                                        // Final assistant reply: replace the trailing streaming
                                        // bubble, or append a new one if there is none (e.g.
                                        // final turn after a tool result, when the model emitted
                                        // no streaming tokens).
                                        val lastIdx = messages.size - 1
                                        if (lastIdx >= 0 && messages[lastIdx].type == MessageType.ASSISTANT) {
                                            messages.removeAt(lastIdx)
                                            messages.add(Message(UUID.randomUUID().toString(),
                                                event.message, MessageType.ASSISTANT))
                                            messageAdapter.notifyItemChanged(messages.size - 1)
                                        } else {
                                            messages.add(Message(UUID.randomUUID().toString(),
                                                event.message, MessageType.ASSISTANT))
                                            messageAdapter.notifyItemInserted(messages.size - 1)
                                        }
                                        Log.d(TAG, "[trace] AFTER AssistantMessage newTailType=${messages.lastOrNull()?.type} tailContent='${messages.lastOrNull()?.content?.take(40)}' msgCount=${messages.size}")
                                    }
                                    else -> {}
                                }
                            }
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            // Sweep any trailing empty ASSISTANT bubble left by the pre-turn
                            // placeholder and never filled (e.g. cancellation between turns).
                            // This is the "air bubble" guard.
                            val lastIdx = messages.size - 1
                            if (lastIdx >= 0 && messages[lastIdx].type == MessageType.ASSISTANT
                                && messages[lastIdx].content.isEmpty()) {
                                messages.removeAt(lastIdx)
                                messageAdapter.notifyItemRemoved(lastIdx)
                            }
                            // Always re-enable input, including on cancellation. Previously
                            // this only ran after collect returned normally, so a cancelled
                            // turn (e.g. CallPhoneTool surfacing the in-call screen) would
                            // leave the user unable to type the next message.
                            userInputEt.isEnabled = true
                            userActionFab.isEnabled = true
                        }
                    }
                }
    }

    /**
     * Run a benchmark with the model file
     */
    @Deprecated("This benchmark doesn't accurately indicate GUI performance expected by app developers")
    private suspend fun runBenchmark(modelName: String, modelFile: File) =
        withContext(Dispatchers.Default) {
            Log.i(TAG, "Starts benchmarking $modelName")
            withContext(Dispatchers.Main) {
                userInputEt.hint = "Running benchmark..."
            }
            engine.bench(
                pp=BENCH_PROMPT_PROCESSING_TOKENS,
                tg=BENCH_TOKEN_GENERATION_TOKENS,
                pl=BENCH_SEQUENCE,
                nr=BENCH_REPETITION
            ).let { result ->
                messages.add(Message(UUID.randomUUID().toString(), result, MessageType.ASSISTANT))
                withContext(Dispatchers.Main) {
                    messageAdapter.notifyItemChanged(messages.size - 1)
                }
            }
        }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    override fun onStop() {
        // Intentionally NOT cancelling generationJob here. Tool calls such as
        // CallPhoneTool surface system UI (the in-call screen) which briefly
        // moves this Activity through onPause/onStop. Cancelling mid-turn in
        // that case would (a) leave an empty ASSISTANT bubble on screen and
        // (b) skip the input re-enable that lived outside the cancelled
        // collect block, trapping the user. The job is bound to lifecycleScope
        // and is cancelled automatically in onDestroy via the scope.
        super.onStop()
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            mcpManager.disconnectAll()
        }
        engine.destroy()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val FILE_EXTENSION_GGUF = ".gguf"

        private const val BENCH_PROMPT_PROCESSING_TOKENS = 512
        private const val BENCH_TOKEN_GENERATION_TOKENS = 128
        private const val BENCH_SEQUENCE = 1
        private const val BENCH_REPETITION = 3
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}

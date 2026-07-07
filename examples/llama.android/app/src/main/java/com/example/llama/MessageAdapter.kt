package com.example.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Message(
    val id: String,
    val content: String,
    val type: MessageType
)

enum class MessageType {
    USER,
    ASSISTANT,
    TOOL_CALL,
    TOOL_RESULT
}

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_TOOL_CALL = 3
        private const val VIEW_TYPE_TOOL_RESULT = 4
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].type) {
            MessageType.USER -> VIEW_TYPE_USER
            MessageType.ASSISTANT -> VIEW_TYPE_ASSISTANT
            MessageType.TOOL_CALL -> VIEW_TYPE_TOOL_CALL
            MessageType.TOOL_RESULT -> VIEW_TYPE_TOOL_RESULT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = layoutInflater.inflate(R.layout.item_message_user, parent, false)
                UserMessageViewHolder(view)
            }
            VIEW_TYPE_ASSISTANT -> {
                val view = layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
                AssistantMessageViewHolder(view)
            }
            VIEW_TYPE_TOOL_CALL -> {
                val view = layoutInflater.inflate(R.layout.item_message_tool_call, parent, false)
                ToolCallViewHolder(view)
            }
            VIEW_TYPE_TOOL_RESULT -> {
                val view = layoutInflater.inflate(R.layout.item_message_tool_result, parent, false)
                ToolResultViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val textView = holder.itemView.findViewById<TextView>(R.id.msg_content)
        textView.text = when (message.type) {
            MessageType.TOOL_CALL -> "🔧 ${message.content}"
            MessageType.TOOL_RESULT -> "✅ ${message.content}"
            else -> message.content
        }
    }

    override fun getItemCount(): Int = messages.size

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class ToolCallViewHolder(view: View) : RecyclerView.ViewHolder(view)
    class ToolResultViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
